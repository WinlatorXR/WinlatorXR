/*
 * Copyright (C) 2024-2026 WinlatorXR
 *
 * This file is part of WinlatorXR.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

/* DXVK Vulkan-interop probe.
 *
 * Answers, from inside a container, whether the shipped DXVK exposes enough
 * interop for a Windows-side XR runtime to share images with the Android
 * compositor without a CPU copy. It renders nothing and modifies nothing.
 *
 * Six questions, in the order they gate each other:
 *
 *   1. Are we under Wine, and is the unixlib dispatcher reachable? That is the
 *      PE->native transport GameNative uses instead of a socket.
 *   2. Is d3d11.dll DXVK at all? (WineD3D exposes no interop interface.)
 *   3. Does IDXGIVkInteropDevice / ...Device1 answer, and what VkInstance,
 *      VkPhysicalDevice, VkDevice and queue do they hand back?
 *   4. Is there a *tenth* vtable slot (CreateTexture2DFromVkImage)? Upstream
 *      DXVK stops at nine, so this only exists on a patched build. Reading a
 *      slot that is not there walks off the end of .rdata, so this is a
 *      heuristic inspection by default and only called with --try-create.
 *   5. Does the stock path work instead: ID3D11Texture2D -> IDXGIVkInteropSurface
 *      -> GetVulkanImageInfo, giving us the VkImage behind a real D3D11 texture?
 *      That one is upstream and needs no DXVK patch.
 *   6. Which external-memory / semaphore extensions are actually *enabled* on
 *      the VkDevice DXVK created. Supported-by-the-physical-device is not
 *      enough: if DXVK did not enable it at device creation the entry point is
 *      absent, so vkGetDeviceProcAddr is the honest test.
 *
 * Interface IIDs and vtable layouts below are ABI facts about DXVK; the slot-10
 * shape is cross-checked against GameNative's gamenative_dxvk.c (also GPLv3).
 */

#define COBJMACROS
#define WIN32_LEAN_AND_MEAN

#include <windows.h>
#include <d3d11.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Structured exception handling guards the one call that can legitimately jump
 * into nowhere. Without it --try-create degrades to the inspection-only path
 * rather than risking an unguarded fault.
 *
 * MSVC always has it. clang has it only with -fms-extensions, which no macro
 * reports, so build.sh defines WXR_HAVE_SEH alongside that flag; the two must
 * stay coupled. mingw-w64's GCC does not implement __try at all. */
#ifndef WXR_HAVE_SEH
#  if defined(_MSC_VER)
#    define WXR_HAVE_SEH 1
#  else
#    define WXR_HAVE_SEH 0
#  endif
#endif

/* Names both the build and its log file. Two containers of different
 * architectures share one Downloads folder, so an arch-less log name would
 * mean the second run silently overwrote the first. */
#if defined(_M_ARM64EC)
#  define WXR_ARCH "arm64ec"
#elif defined(__aarch64__) || defined(_M_ARM64)
#  define WXR_ARCH "aarch64"
#elif defined(__x86_64__) || defined(_M_X64)
#  define WXR_ARCH "x86_64"
#else
#  define WXR_ARCH "unknown"
#endif

/* ------------------------------------------------------------------ output */

static FILE* g_log = NULL;
static int g_failures = 0;

static void probe_log(const char* fmt, ...)
{
    char line[2048];
    va_list args;
    va_start(args, fmt);
    vsnprintf(line, sizeof(line), fmt, args);
    va_end(args);

    fputs(line, stdout);
    fputc('\n', stdout);
    fflush(stdout);
    if (g_log) {
        fputs(line, g_log);
        fputc('\n', g_log);
        fflush(g_log);
    }
    OutputDebugStringA(line);
    OutputDebugStringA("\n");
}

static void section(const char* name)
{
    probe_log("");
    probe_log("== %s ==", name);
}

/* A question we wanted answered and could not answer. Counted so the summary
 * can say whether the run is trustworthy, not just what it saw. */
static void probe_fail(const char* fmt, ...)
{
    char line[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(line, sizeof(line), fmt, args);
    va_end(args);
    g_failures++;
    probe_log("  [!] %s", line);
}

/* ------------------------------------------------------------- DXVK interop */

static const GUID IID_IDXGIVkInteropDevice =
    {0xe2ef5fa5, 0xdc21, 0x4af7, {0x90, 0xc4, 0xf6, 0x7e, 0xf6, 0xa0, 0x93, 0x23}};
static const GUID IID_IDXGIVkInteropDevice1 =
    {0xe2ef5fa5, 0xdc21, 0x4af7, {0x90, 0xc4, 0xf6, 0x7e, 0xf6, 0xa0, 0x93, 0x24}};
static const GUID IID_IDXGIVkInteropSurface =
    {0x5546cf8c, 0x77e7, 0x4341, {0xb0, 0x5d, 0x8d, 0x4d, 0x50, 0x00, 0xe7, 0x7d}};

typedef struct InteropDevice InteropDevice;
typedef struct InteropSurface InteropSurface;

/* Slots 0-8 are IDXGIVkInteropDevice, slot 9 is IDXGIVkInteropDevice1's
 * GetSubmissionQueue1. Slot 10 is where a patched DXVK puts
 * CreateTexture2DFromVkImage; upstream has nothing there. */
typedef struct InteropDeviceVtbl {
    HRESULT (STDMETHODCALLTYPE* QueryInterface)(InteropDevice*, REFIID, void**);
    ULONG   (STDMETHODCALLTYPE* AddRef)(InteropDevice*);
    ULONG   (STDMETHODCALLTYPE* Release)(InteropDevice*);
    void    (STDMETHODCALLTYPE* GetVulkanHandles)(InteropDevice*, uint64_t*, uint64_t*, uint64_t*);
    void    (STDMETHODCALLTYPE* GetSubmissionQueue)(InteropDevice*, uint64_t*, uint32_t*);
    void    (STDMETHODCALLTYPE* TransitionSurfaceLayout)(InteropDevice*, void*, const void*, uint32_t, uint32_t);
    void    (STDMETHODCALLTYPE* FlushRenderingCommands)(InteropDevice*);
    void    (STDMETHODCALLTYPE* LockSubmissionQueue)(InteropDevice*);
    void    (STDMETHODCALLTYPE* ReleaseSubmissionQueue)(InteropDevice*);
    void    (STDMETHODCALLTYPE* GetSubmissionQueue1)(InteropDevice*, uint64_t*, uint32_t*, uint32_t*);
    HRESULT (STDMETHODCALLTYPE* CreateTexture2DFromVkImage)(InteropDevice*, const void*, uint64_t, ID3D11Texture2D**);
} InteropDeviceVtbl;

struct InteropDevice { const InteropDeviceVtbl* lpVtbl; };

typedef struct InteropSurfaceVtbl {
    HRESULT (STDMETHODCALLTYPE* QueryInterface)(InteropSurface*, REFIID, void**);
    ULONG   (STDMETHODCALLTYPE* AddRef)(InteropSurface*);
    ULONG   (STDMETHODCALLTYPE* Release)(InteropSurface*);
    HRESULT (STDMETHODCALLTYPE* GetDevice)(InteropSurface*, InteropDevice**);
    HRESULT (STDMETHODCALLTYPE* GetVulkanImageInfo)(InteropSurface*, uint64_t*, uint32_t*, void*);
} InteropSurfaceVtbl;

struct InteropSurface { const InteropSurfaceVtbl* lpVtbl; };

/* ------------------------------------------------------------------- Vulkan */

/* Declared by hand so the probe builds with nothing but mingw-w64 headers. */
typedef uint64_t VkHandle64;
typedef void*    VkDispatchable;

enum {
    VK_SUCCESS                            = 0,
    VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO   = 14,
    VK_FORMAT_R8G8B8A8_UNORM              = 37,
    VK_IMAGE_TYPE_2D                      = 1,
    VK_IMAGE_TILING_OPTIMAL               = 0,
    VK_SAMPLE_COUNT_1_BIT                 = 1,
    VK_SHARING_MODE_EXCLUSIVE             = 0,
    VK_IMAGE_LAYOUT_UNDEFINED             = 0,
    VK_IMAGE_USAGE_TRANSFER_SRC_BIT       = 0x001,
    VK_IMAGE_USAGE_TRANSFER_DST_BIT       = 0x002,
    VK_IMAGE_USAGE_SAMPLED_BIT            = 0x004,
    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT   = 0x010
};

typedef struct VkExtent3D { uint32_t width, height, depth; } VkExtent3D;

typedef struct VkImageCreateInfo {
    uint32_t        sType;
    const void*     pNext;
    uint32_t        flags;
    uint32_t        imageType;
    uint32_t        format;
    VkExtent3D      extent;
    uint32_t        mipLevels;
    uint32_t        arrayLayers;
    uint32_t        samples;
    uint32_t        tiling;
    uint32_t        usage;
    uint32_t        sharingMode;
    uint32_t        queueFamilyIndexCount;
    const uint32_t* pQueueFamilyIndices;
    uint32_t        initialLayout;
} VkImageCreateInfo;

typedef struct VkExtensionProperties {
    char     extensionName[256];
    uint32_t specVersion;
} VkExtensionProperties;

/* Only the head of VkPhysicalDeviceProperties is spelled out; the tail (limits
 * and sparse properties, ~600 bytes) is reserved padding so the driver has
 * somewhere to write without us restating the whole limits struct. */
typedef struct VkPhysicalDevicePropertiesHead {
    uint32_t apiVersion;
    uint32_t driverVersion;
    uint32_t vendorID;
    uint32_t deviceID;
    uint32_t deviceType;
    char     deviceName[256];
    uint8_t  pipelineCacheUUID[16];
    uint8_t  reserved[4096];
} VkPhysicalDevicePropertiesHead;

typedef void*   (WINAPI* PFN_vkGetDeviceProcAddr)(VkDispatchable, const char*);
typedef void    (WINAPI* PFN_vkGetPhysicalDeviceProperties)(VkDispatchable, void*);
typedef int32_t (WINAPI* PFN_vkEnumerateDeviceExtensionProperties)(VkDispatchable, const char*, uint32_t*, VkExtensionProperties*);
typedef int32_t (WINAPI* PFN_vkCreateImage)(VkDispatchable, const VkImageCreateInfo*, const void*, VkHandle64*);
typedef void    (WINAPI* PFN_vkDestroyImage)(VkDispatchable, VkHandle64, const void*);

/* Extensions worth asking about, and why. */
static const struct { const char* name; const char* why; } kInterestingExtensions[] = {
    {"VK_KHR_external_memory",              "base external-memory support"},
    {"VK_KHR_external_memory_fd",           "fd import - the Linux/Android sharing path"},
    {"VK_KHR_external_memory_win32",        "NT-handle import - what Wine/DXVK normally uses"},
    {"VK_KHR_external_semaphore_fd",        "fd fences for cross-process GPU sync"},
    {"VK_KHR_external_semaphore_win32",     "NT-handle fences"},
    {"VK_KHR_timeline_semaphore",           "preferred sync primitive for a shared swapchain"},
    {"VK_EXT_external_memory_dma_buf",      "dma-buf import, pairs with AHardwareBuffer"},
    {"VK_ANDROID_external_memory_android_hardware_buffer", "direct AHardwareBuffer import"},
    {"VK_KHR_image_format_list",            "needed for sRGB/linear aliasing of a shared image"},
    {"VK_KHR_swapchain",                    "sanity check that enumeration works at all"}
};

/* Entry points that only resolve on a device if the matching extension was
 * enabled at vkCreateDevice time. This is the question that actually matters:
 * DXVK creates the device, so we do not get to choose what it turned on. */
static const struct { const char* proc; const char* ext; } kDeviceProcs[] = {
    {"vkGetMemoryFdKHR",                            "VK_KHR_external_memory_fd"},
    {"vkGetMemoryWin32HandleKHR",                   "VK_KHR_external_memory_win32"},
    {"vkGetSemaphoreFdKHR",                         "VK_KHR_external_semaphore_fd"},
    {"vkImportSemaphoreFdKHR",                      "VK_KHR_external_semaphore_fd"},
    {"vkGetSemaphoreWin32HandleKHR",                "VK_KHR_external_semaphore_win32"},
    {"vkImportSemaphoreWin32HandleKHR",             "VK_KHR_external_semaphore_win32"},
    {"vkWaitSemaphoresKHR",                         "VK_KHR_timeline_semaphore"},
    {"vkWaitSemaphores",                            "Vulkan 1.2 core timeline semaphores"},
    {"vkGetAndroidHardwareBufferPropertiesANDROID", "VK_ANDROID_external_memory_AHB"}
};

/* ------------------------------------------------------------------ helpers */

/* Is `candidate` a plausible function pointer belonging to the same module as
 * `reference`? Used to guess whether vtable slot 10 is real code or whatever
 * .rdata happens to follow a nine-slot vtable. */
static int looks_like_code_in_same_module(const void* reference, const void* candidate)
{
    HMODULE ref_module = NULL, cand_module = NULL;
    MEMORY_BASIC_INFORMATION info;
    DWORD flags = GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
                  GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT;

    if (!candidate) return 0;
    if (!VirtualQuery(candidate, &info, sizeof(info))) return 0;
    if (info.State != MEM_COMMIT) return 0;
    if (!(info.Protect & (PAGE_EXECUTE | PAGE_EXECUTE_READ |
                          PAGE_EXECUTE_READWRITE | PAGE_EXECUTE_WRITECOPY)))
        return 0;

    if (!GetModuleHandleExA(flags, (LPCSTR)reference, &ref_module)) return 0;
    if (!GetModuleHandleExA(flags, (LPCSTR)candidate, &cand_module)) return 0;
    return ref_module == cand_module;
}

static int memory_is_readable(const void* address, size_t size)
{
    MEMORY_BASIC_INFORMATION info;
    if (!VirtualQuery(address, &info, sizeof(info))) return 0;
    if (info.State != MEM_COMMIT) return 0;
    if (info.Protect & PAGE_NOACCESS) return 0;
    return (const char*)address + size <=
           (const char*)info.BaseAddress + info.RegionSize;
}

static void log_module_version(const char* label, HMODULE module)
{
    char path[MAX_PATH] = {0};
    DWORD ignored = 0, size;
    void* block;
    VS_FIXEDFILEINFO* fixed = NULL;
    UINT fixed_size = 0;

    if (!GetModuleFileNameA(module, path, sizeof(path))) {
        probe_log("  %s: <path unavailable>", label);
        return;
    }
    probe_log("  %s: %s", label, path);

    size = GetFileVersionInfoSizeA(path, &ignored);
    if (!size) return;
    block = HeapAlloc(GetProcessHeap(), 0, size);
    if (!block) return;
    if (GetFileVersionInfoA(path, 0, size, block) &&
        VerQueryValueA(block, "\\", (void**)&fixed, &fixed_size) && fixed) {
        probe_log("  %s version: %u.%u.%u.%u", label,
                  HIWORD(fixed->dwFileVersionMS), LOWORD(fixed->dwFileVersionMS),
                  HIWORD(fixed->dwFileVersionLS), LOWORD(fixed->dwFileVersionLS));
    }
    HeapFree(GetProcessHeap(), 0, block);
}

/* --------------------------------------------------------------- the probe */

typedef struct ProbeOptions {
    int try_create;   /* actually call vtable slot 10 (may fault on stock DXVK) */
} ProbeOptions;

static void probe_environment(void)
{
    HMODULE ntdll = GetModuleHandleA("ntdll.dll");
    const char* (__cdecl *wine_get_version)(void);
    void* dispatcher;

    section("Environment");

    probe_log("  probe build: %s", WXR_ARCH);

    if (!ntdll) {
        probe_fail("ntdll.dll not resolvable - this is not a sane Windows process");
        return;
    }

    wine_get_version = (const char* (__cdecl *)(void))
        (void*)GetProcAddress(ntdll, "wine_get_version");
    if (wine_get_version)
        probe_log("  Wine version: %s", wine_get_version());
    else
        probe_log("  Wine version: <not Wine, or export hidden>");

    /* Exported as data, not as a callable function: this is the address of the
     * dispatcher pointer a unixlib-based runtime would call through. Its
     * presence is what makes a socket-free PE->native transport possible. */
    dispatcher = (void*)GetProcAddress(ntdll, "__wine_unix_call_dispatcher");
    if (dispatcher)
        probe_log("  __wine_unix_call_dispatcher: present at %p", dispatcher);
    else
        probe_log("  __wine_unix_call_dispatcher: ABSENT (no unixlib transport)");
}

static const GUID IID_IDXGIFactory1_local =
    {0x770aae78, 0xf26f, 0x4dba, {0xa8, 0x29, 0x25, 0x3c, 0x83, 0xd1, 0xb3, 0x87}};

/* Which adapters DXGI is willing to report. If device creation fails, this says
 * whether the failure is "no adapter at all" or "adapter present, D3D11 refused
 * it" -- a distinction that decides whether to look at the driver or at DXVK. */
static void probe_adapters(void)
{
    typedef HRESULT (WINAPI* PFN_CreateDXGIFactory1)(REFIID, void**);
    HMODULE module;
    PFN_CreateDXGIFactory1 create;
    IDXGIFactory1* factory = NULL;
    UINT index;
    HRESULT hr;

    module = LoadLibraryA("dxgi.dll");
    if (!module) {
        probe_fail("LoadLibrary(dxgi.dll) failed, error %lu", GetLastError());
        return;
    }
    log_module_version("dxgi", module);

    create = (PFN_CreateDXGIFactory1)(void*)GetProcAddress(module, "CreateDXGIFactory1");
    if (!create) {
        probe_fail("CreateDXGIFactory1 not exported");
        return;
    }

    hr = create(&IID_IDXGIFactory1_local, (void**)&factory);
    if (FAILED(hr) || !factory) {
        probe_fail("CreateDXGIFactory1 failed, hr=0x%08lx", (unsigned long)hr);
        return;
    }

    for (index = 0; ; index++) {
        IDXGIAdapter1* adapter = NULL;
        DXGI_ADAPTER_DESC1 desc;

        if (FAILED(IDXGIFactory1_EnumAdapters1(factory, index, &adapter)) || !adapter) break;

        memset(&desc, 0, sizeof(desc));
        if (SUCCEEDED(IDXGIAdapter1_GetDesc1(adapter, &desc))) {
            probe_log("  adapter %u: %ls (vendor 0x%04x device 0x%04x, %u MB, flags 0x%x)",
                      index, desc.Description, desc.VendorId, desc.DeviceId,
                      (unsigned)(desc.DedicatedVideoMemory / (1024 * 1024)), desc.Flags);
        }
        IDXGIAdapter1_Release(adapter);
    }
    if (!index) probe_fail("DXGI reports no adapters at all");

    IDXGIFactory1_Release(factory);
}

/* Which feature levels the driver will actually give us.
 *
 * D3D11CreateDevice walks the array it is handed, so a single E_FAIL says only
 * that every level failed, not where the ceiling is. DXVK 2.x has a higher
 * Vulkan baseline than 1.x, and an older driver can leave it unable to create
 * any device at all -- asking one level at a time distinguishes that from a
 * driver that merely cannot reach 11_1. */
static void probe_feature_levels(void* create_fn)
{
    typedef HRESULT (WINAPI* PFN_D3D11CreateDevice)(
        IDXGIAdapter*, D3D_DRIVER_TYPE, HMODULE, UINT,
        const D3D_FEATURE_LEVEL*, UINT, UINT,
        ID3D11Device**, D3D_FEATURE_LEVEL*, ID3D11DeviceContext**);

    static const struct { D3D_FEATURE_LEVEL level; const char* name; } levels[] = {
        {D3D_FEATURE_LEVEL_12_1, "12_1"}, {D3D_FEATURE_LEVEL_12_0, "12_0"},
        {D3D_FEATURE_LEVEL_11_1, "11_1"}, {D3D_FEATURE_LEVEL_11_0, "11_0"},
        {D3D_FEATURE_LEVEL_10_1, "10_1"}, {D3D_FEATURE_LEVEL_10_0, "10_0"},
        {D3D_FEATURE_LEVEL_9_3,  "9_3"}
    };

    PFN_D3D11CreateDevice create = (PFN_D3D11CreateDevice)create_fn;
    size_t i;

    probe_log("  retrying one feature level at a time:");
    for (i = 0; i < ARRAYSIZE(levels); i++) {
        ID3D11Device* device = NULL;
        HRESULT hr = create(NULL, D3D_DRIVER_TYPE_HARDWARE, NULL, 0,
                            &levels[i].level, 1, D3D11_SDK_VERSION,
                            &device, NULL, NULL);
        probe_log("    %-5s hr=0x%08lx%s", levels[i].name, (unsigned long)hr,
                  SUCCEEDED(hr) ? "  <- this one works" : "");
        if (device) ID3D11Device_Release(device);
        if (SUCCEEDED(hr)) return;
    }

    /* WARP is DXVK's software path. If even that fails the problem is not the
     * GPU driver's feature set. */
    {
        ID3D11Device* device = NULL;
        HRESULT hr = create(NULL, D3D_DRIVER_TYPE_WARP, NULL, 0,
                            NULL, 0, D3D11_SDK_VERSION, &device, NULL, NULL);
        probe_log("    WARP  hr=0x%08lx", (unsigned long)hr);
        if (device) ID3D11Device_Release(device);
    }
}

static ID3D11Device* create_device(void)
{
    typedef HRESULT (WINAPI* PFN_D3D11CreateDevice)(
        IDXGIAdapter*, D3D_DRIVER_TYPE, HMODULE, UINT,
        const D3D_FEATURE_LEVEL*, UINT, UINT,
        ID3D11Device**, D3D_FEATURE_LEVEL*, ID3D11DeviceContext**);

    static const D3D_FEATURE_LEVEL levels[] = {
        D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0, D3D_FEATURE_LEVEL_10_1
    };

    HMODULE module;
    PFN_D3D11CreateDevice create;
    ID3D11Device* device = NULL;
    ID3D11DeviceContext* context = NULL;
    D3D_FEATURE_LEVEL level = 0;
    HRESULT hr;

    section("d3d11.dll");

    /* Loaded by name rather than linked, so the probe can report which
     * implementation the container actually resolved. */
    module = LoadLibraryA("d3d11.dll");
    if (!module) {
        probe_fail("LoadLibrary(d3d11.dll) failed, error %lu", GetLastError());
        return NULL;
    }
    log_module_version("d3d11", module);

    create = (PFN_D3D11CreateDevice)(void*)GetProcAddress(module, "D3D11CreateDevice");
    if (!create) {
        probe_fail("D3D11CreateDevice not exported");
        return NULL;
    }

    probe_adapters();

    hr = create(NULL, D3D_DRIVER_TYPE_HARDWARE, NULL, 0,
                levels, ARRAYSIZE(levels), D3D11_SDK_VERSION,
                &device, &level, &context);
    if (FAILED(hr) || !device) {
        probe_fail("D3D11CreateDevice failed, hr=0x%08lx", (unsigned long)hr);
        probe_feature_levels((void*)create);
        probe_log("  DXVK's own reason is in its log beside this one "
                  "(rundll32_d3d11.log / rundll32_dxgi.log)");
        return NULL;
    }
    probe_log("  device created, feature level 0x%04x", (unsigned)level);
    if (context) ID3D11DeviceContext_Release(context);

    return device;
}

/* Returns the interop device, or NULL. Fills in the Vulkan handles used later;
 * `is_device1` says whether the ...Device1 IID answered. */
static InteropDevice* probe_interop_device(ID3D11Device* device,
                                           uint64_t* out_physical_device,
                                           uint64_t* out_device,
                                           int* is_device1)
{
    InteropDevice* interop = NULL;
    uint64_t instance = 0, physical_device = 0, vk_device = 0, queue = 0;
    uint32_t queue_family = 0, queue_index = 0;
    HRESULT hr;

    section("IDXGIVkInteropDevice");

    hr = ID3D11Device_QueryInterface(device, &IID_IDXGIVkInteropDevice1, (void**)&interop);
    *is_device1 = SUCCEEDED(hr) && interop != NULL;
    if (*is_device1) {
        probe_log("  IDXGIVkInteropDevice1: YES");
    } else {
        probe_log("  IDXGIVkInteropDevice1: no (hr=0x%08lx)", (unsigned long)hr);
        hr = ID3D11Device_QueryInterface(device, &IID_IDXGIVkInteropDevice, (void**)&interop);
        if (FAILED(hr) || !interop) {
            probe_fail("IDXGIVkInteropDevice: no (hr=0x%08lx) - this d3d11 is not DXVK",
                       (unsigned long)hr);
            return NULL;
        }
        probe_log("  IDXGIVkInteropDevice: YES (base interface only)");
    }

    interop->lpVtbl->GetVulkanHandles(interop, &instance, &physical_device, &vk_device);
    probe_log("  VkInstance:       0x%016llx", (unsigned long long)instance);
    probe_log("  VkPhysicalDevice: 0x%016llx", (unsigned long long)physical_device);
    probe_log("  VkDevice:         0x%016llx", (unsigned long long)vk_device);

    if (*is_device1) {
        interop->lpVtbl->GetSubmissionQueue1(interop, &queue, &queue_index, &queue_family);
        probe_log("  VkQueue:          0x%016llx (family %u, index %u)",
                  (unsigned long long)queue, queue_family, queue_index);
    } else {
        interop->lpVtbl->GetSubmissionQueue(interop, &queue, &queue_family);
        probe_log("  VkQueue:          0x%016llx (family %u)",
                  (unsigned long long)queue, queue_family);
    }

    if (!physical_device || !vk_device || !queue)
        probe_fail("interop returned a null Vulkan handle - handles are not usable");

    *out_physical_device = physical_device;
    *out_device = vk_device;
    return interop;
}

/* The slot-10 question. Upstream DXVK's vtable ends at slot 9, so slot 10 is
 * only meaningful on a patched build; by default we look at it rather than
 * call it. */
static void probe_create_texture_slot(InteropDevice* interop, const ProbeOptions* options)
{
    const InteropDeviceVtbl* vtbl = interop->lpVtbl;
    const void* slot9;
    const void* slot10;

    section("CreateTexture2DFromVkImage (patched-DXVK extension, vtable slot 10)");

    if (!memory_is_readable(vtbl, sizeof(void*) * 11)) {
        probe_log("  vtable is shorter than 11 slots - slot 10 does not exist");
        probe_log("  verdict: stock DXVK, no native-image wrapping");
        return;
    }

    slot9 = (const void*)vtbl->GetSubmissionQueue1;
    slot10 = (const void*)vtbl->CreateTexture2DFromVkImage;
    probe_log("  slot 9  (GetSubmissionQueue1):        %p", slot9);
    probe_log("  slot 10 (CreateTexture2DFromVkImage): %p", slot10);

    if (!looks_like_code_in_same_module((const void*)vtbl->GetVulkanHandles, slot10)) {
        probe_log("  slot 10 does not point at executable code in d3d11.dll");
        probe_log("  verdict: stock DXVK - CreateTexture2DFromVkImage is NOT available");
        probe_log("  (a native-allocates / PE-wraps design would need a patched DXVK)");
        return;
    }

    /* Weak evidence, and worth saying so: DXVK packs its vtables back-to-back in
     * .rdata, so the word after a nine-slot vtable is very often the next
     * vtable's QueryInterface -- which is also real code in d3d11.dll and passes
     * every test above. Only the call below tells the two apart. */
    probe_log("  slot 10 is executable code in d3d11.dll, but that is expected either way:");
    probe_log("  on stock DXVK the next vtable's QueryInterface sits at this address.");
    if (!options->try_create) {
        probe_log("  verdict: INCONCLUSIVE - rerun with --try-create to settle it");
        return;
    }
#if !WXR_HAVE_SEH
    probe_log("  --try-create needs SEH; this build (mingw-gcc) has none, skipping");
    probe_log("  rebuild with llvm-mingw or MSVC to attempt the call");
#else

    /* Even once it looks plausible, the two known signatures disagree on
     * argument count, so a call can still go wrong. Guarded, and reported as
     * inconclusive rather than negative if it dies. */
    probe_log("  --try-create given: calling with the 4-argument signature");
    {
        ID3D11Texture2D* wrapped = NULL;
        HRESULT hr;
        struct {
            UINT Width, Height, MipLevels, ArraySize;
            DXGI_FORMAT Format;
            DXGI_SAMPLE_DESC SampleDesc;
            D3D11_USAGE Usage;
            UINT BindFlags, CPUAccessFlags, MiscFlags;
            UINT TextureLayout;
        } desc;

        memset(&desc, 0, sizeof(desc));
        desc.Width = 256;
        desc.Height = 256;
        desc.MipLevels = 1;
        desc.ArraySize = 1;
        desc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
        desc.SampleDesc.Count = 1;
        desc.BindFlags = D3D11_BIND_SHADER_RESOURCE | D3D11_BIND_RENDER_TARGET;

        /* VkImage 0 on purpose: a real implementation should reject it cleanly.
         * We are testing that the entry point exists and validates, not that it
         * can wrap an image we never allocated. */
        __try {
            hr = vtbl->CreateTexture2DFromVkImage(interop, &desc, 0, &wrapped);
            probe_log("  returned hr=0x%08lx, texture=%p", (unsigned long)hr, (void*)wrapped);

            /* One discriminator: called as QueryInterface(this, riid, ppv) the
             * arguments land as riid=&desc and ppv=0, and every DXVK COM object
             * rejects a null ppvObject with E_POINTER before touching riid. */
            if (hr == (HRESULT)0x80004003L) {
                probe_log("  E_POINTER: that is a QueryInterface rejecting a null out-pointer");
                probe_log("  verdict: stock DXVK - slot 10 belongs to the NEXT vtable");
            } else if (SUCCEEDED(hr) && wrapped) {
                /* A returned pointer is not proof by itself -- some other
                 * four-argument method could have written one. Ask the object
                 * to describe itself: only a real texture built from our
                 * descriptor reports our dimensions and format back. */
                D3D11_TEXTURE2D_DESC got;
                memset(&got, 0, sizeof(got));
                ID3D11Texture2D_GetDesc(wrapped, &got);
                probe_log("  returned object describes itself as %ux%u fmt=%u "
                          "mips=%u layers=%u bind=0x%x",
                          got.Width, got.Height, (unsigned)got.Format,
                          got.MipLevels, got.ArraySize, got.BindFlags);
                if (got.Width == 256 && got.Height == 256 &&
                    got.Format == DXGI_FORMAT_R8G8B8A8_UNORM) {
                    probe_log("  matches what we asked for");
                    probe_log("  verdict: slot 10 IS CreateTexture2DFromVkImage - patched DXVK");
                } else {
                    probe_log("  does NOT match the descriptor we passed");
                    probe_log("  verdict: slot 10 is some other method - NOT a usable entry point");
                }
            } else {
                probe_log("  verdict: slot 10 rejected the call (hr above); not usable");
            }
        } __except (EXCEPTION_EXECUTE_HANDLER) {
            probe_fail("call raised exception 0x%08lx - slot 10 is not this function",
                       (unsigned long)GetExceptionCode());
            probe_log("  verdict: stock DXVK after all");
        }
        if (wrapped) ID3D11Texture2D_Release(wrapped);
    }
#endif /* WXR_HAVE_SEH */
}

/* The stock path: given any D3D11 texture, hand back the VkImage behind it.
 * This is upstream DXVK and needs no patch, so it is the fallback design. */
static void probe_interop_surface(ID3D11Device* device)
{
    D3D11_TEXTURE2D_DESC desc;
    ID3D11Texture2D* texture = NULL;
    InteropSurface* surface = NULL;
    uint64_t image = 0;
    uint32_t layout = 0;
    unsigned char info_storage[512];
    VkImageCreateInfo* info = (VkImageCreateInfo*)info_storage;
    HRESULT hr;

    section("IDXGIVkInteropSurface (stock DXVK, no patch required)");

    memset(&desc, 0, sizeof(desc));
    desc.Width = 256;
    desc.Height = 256;
    desc.MipLevels = 1;
    desc.ArraySize = 1;
    desc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    desc.SampleDesc.Count = 1;
    desc.Usage = D3D11_USAGE_DEFAULT;
    desc.BindFlags = D3D11_BIND_SHADER_RESOURCE | D3D11_BIND_RENDER_TARGET;

    hr = ID3D11Device_CreateTexture2D(device, &desc, NULL, &texture);
    if (FAILED(hr) || !texture) {
        probe_fail("CreateTexture2D failed, hr=0x%08lx", (unsigned long)hr);
        return;
    }

    hr = ID3D11Texture2D_QueryInterface(texture, &IID_IDXGIVkInteropSurface, (void**)&surface);
    if (FAILED(hr) || !surface) {
        probe_fail("IDXGIVkInteropSurface: no (hr=0x%08lx)", (unsigned long)hr);
        ID3D11Texture2D_Release(texture);
        return;
    }
    probe_log("  IDXGIVkInteropSurface: YES");

    memset(info_storage, 0, sizeof(info_storage));
    hr = surface->lpVtbl->GetVulkanImageInfo(surface, &image, &layout, info_storage);
    if (FAILED(hr)) {
        /* Some DXVK builds reject a non-null pInfo. The handle and layout are
         * what we actually need, so ask again without it before giving up --
         * this is the form GameNative uses. */
        probe_log("  GetVulkanImageInfo(pInfo) failed, hr=0x%08lx; retrying with pInfo=NULL",
                  (unsigned long)hr);
        image = 0;
        layout = 0;
        hr = surface->lpVtbl->GetVulkanImageInfo(surface, &image, &layout, NULL);
        info = NULL;
    }

    if (FAILED(hr)) {
        probe_fail("GetVulkanImageInfo failed both ways, hr=0x%08lx", (unsigned long)hr);
    } else {
        probe_log("  VkImage:  0x%016llx", (unsigned long long)image);
        probe_log("  layout:   %u", layout);
        if (info) {
            probe_log("  extent:   %ux%u, %u mip(s), %u layer(s)",
                      info->extent.width, info->extent.height,
                      info->mipLevels, info->arrayLayers);
            probe_log("  format:   %u   tiling: %u   usage: 0x%08x",
                      info->format, info->tiling, info->usage);
        } else {
            probe_log("  (create-info unavailable on this build)");
        }
        if (!image)
            probe_fail("VkImage is null despite success - texture is not Vulkan-backed");
        else
            probe_log("  verdict: a D3D11 texture's VkImage IS reachable from the PE side");
    }

    surface->lpVtbl->Release(surface);
    ID3D11Texture2D_Release(texture);
}

/* Which sharing extensions DXVK actually turned on. Supported-on-the-physical-
 * device is only half the answer; the entry point resolving on the *device* is
 * the half that decides whether we can share an image at all. */
static void probe_vulkan(uint64_t physical_device, uint64_t vk_device)
{
    HMODULE vulkan;
    PFN_vkGetDeviceProcAddr get_device_proc;
    PFN_vkGetPhysicalDeviceProperties get_properties;
    PFN_vkEnumerateDeviceExtensionProperties enumerate;
    PFN_vkCreateImage create_image;
    PFN_vkDestroyImage destroy_image;
    VkDispatchable phys = (VkDispatchable)(uintptr_t)physical_device;
    VkDispatchable dev = (VkDispatchable)(uintptr_t)vk_device;
    VkExtensionProperties* extensions = NULL;
    uint32_t count = 0;
    size_t i;
    uint32_t j;

    section("Vulkan state of the DXVK device");

    if (!physical_device || !vk_device) {
        probe_fail("no Vulkan handles to inspect");
        return;
    }

    vulkan = LoadLibraryA("vulkan-1.dll");
    if (!vulkan) {
        probe_fail("LoadLibrary(vulkan-1.dll) failed, error %lu", GetLastError());
        return;
    }
    log_module_version("vulkan-1", vulkan);

    get_device_proc = (PFN_vkGetDeviceProcAddr)(void*)GetProcAddress(vulkan, "vkGetDeviceProcAddr");
    get_properties = (PFN_vkGetPhysicalDeviceProperties)(void*)GetProcAddress(vulkan, "vkGetPhysicalDeviceProperties");
    enumerate = (PFN_vkEnumerateDeviceExtensionProperties)(void*)GetProcAddress(vulkan, "vkEnumerateDeviceExtensionProperties");
    create_image = (PFN_vkCreateImage)(void*)GetProcAddress(vulkan, "vkCreateImage");
    destroy_image = (PFN_vkDestroyImage)(void*)GetProcAddress(vulkan, "vkDestroyImage");

    if (!get_device_proc || !enumerate) {
        probe_fail("vulkan-1.dll is missing core entry points");
        return;
    }

    if (get_properties) {
        VkPhysicalDevicePropertiesHead props;
        memset(&props, 0, sizeof(props));
        get_properties(phys, &props);
        props.deviceName[sizeof(props.deviceName) - 1] = '\0';
        probe_log("  device:  %s", props.deviceName);
        probe_log("  API:     %u.%u.%u   driver 0x%08x   vendor 0x%04x",
                  (props.apiVersion >> 22) & 0x7f, (props.apiVersion >> 12) & 0x3ff,
                  props.apiVersion & 0xfff, props.driverVersion, props.vendorID);
    }

    /* Supported by the physical device. */
    if (enumerate(phys, NULL, &count, NULL) == VK_SUCCESS && count) {
        extensions = HeapAlloc(GetProcessHeap(), 0, count * sizeof(*extensions));
        if (extensions && enumerate(phys, NULL, &count, extensions) != VK_SUCCESS) {
            HeapFree(GetProcessHeap(), 0, extensions);
            extensions = NULL;
        }
    }
    if (!extensions) {
        probe_fail("could not enumerate device extensions");
    } else {
        probe_log("  %u device extensions supported; of interest:", count);
        for (i = 0; i < ARRAYSIZE(kInterestingExtensions); i++) {
            int found = 0;
            for (j = 0; j < count; j++) {
                if (!strcmp(extensions[j].extensionName, kInterestingExtensions[i].name)) {
                    found = 1;
                    break;
                }
            }
            probe_log("    [%s] %-52s %s", found ? "supported" : "  absent  ",
                      kInterestingExtensions[i].name, kInterestingExtensions[i].why);
        }
        HeapFree(GetProcessHeap(), 0, extensions);
    }

    /* Enabled on the device DXVK created. This is the load-bearing result. */
    probe_log("  entry points on DXVK's VkDevice (absent == extension not enabled):");
    for (i = 0; i < ARRAYSIZE(kDeviceProcs); i++) {
        void* proc = get_device_proc(dev, kDeviceProcs[i].proc);
        probe_log("    [%s] %-46s (%s)", proc ? "enabled" : "absent ",
                  kDeviceProcs[i].proc, kDeviceProcs[i].ext);
    }

    /* Can the PE side drive DXVK's device at all through winevulkan? Everything
     * a Windows-side XR runtime would do depends on this being yes. */
    if (create_image && destroy_image) {
        VkImageCreateInfo create;
        VkHandle64 image = 0;
        int32_t result;

        memset(&create, 0, sizeof(create));
        create.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        create.imageType = VK_IMAGE_TYPE_2D;
        create.format = VK_FORMAT_R8G8B8A8_UNORM;
        create.extent.width = 256;
        create.extent.height = 256;
        create.extent.depth = 1;
        create.mipLevels = 1;
        create.arrayLayers = 1;
        create.samples = VK_SAMPLE_COUNT_1_BIT;
        create.tiling = VK_IMAGE_TILING_OPTIMAL;
        create.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
                       VK_IMAGE_USAGE_SAMPLED_BIT |
                       VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                       VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        create.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        create.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

        result = create_image(dev, &create, NULL, &image);
        if (result == VK_SUCCESS && image) {
            probe_log("  PE-side vkCreateImage on DXVK's VkDevice: OK (0x%016llx)",
                      (unsigned long long)image);
            destroy_image(dev, image, NULL);
        } else {
            probe_fail("PE-side vkCreateImage on DXVK's VkDevice failed, VkResult=%d", result);
        }
    }
}

/* Prefer a log the user can actually reach. Drive C lives under /data/data and
 * needs root to read off-device; drive D is mapped to the Downloads folder by
 * default, so try there first and only fall back to C.
 *
 * Opened for append, never truncate. The interesting comparison is one DXVK
 * build or graphics driver against another, and those runs differ in neither
 * the architecture nor anything else in the filename -- truncating threw away
 * every earlier run and left only the last. Each run writes a banner naming
 * itself, so one file holds the whole series in order. */
static void open_log(void)
{
    static const char* const candidates[] = {
        "D:\\wxr_dxvk_probe-" WXR_ARCH ".log",
        "C:\\wxr_dxvk_probe-" WXR_ARCH ".log"
    };
    const char* forced = getenv("WXR_DXVK_PROBE_LOG");
    const char* tag = getenv("WXR_DXVK_PROBE_TAG");
    const char* path = NULL;
    SYSTEMTIME now;
    size_t i;

    if (forced && *forced) {
        g_log = fopen(forced, "a");
        if (g_log) path = forced;
        /* Not fatal: the console and debugger still get everything. */
        else probe_log("log: could not open %s, falling back", forced);
    }

    for (i = 0; !g_log && i < ARRAYSIZE(candidates); i++) {
        g_log = fopen(candidates[i], "a");
        if (g_log) path = candidates[i];
    }

    if (!g_log) {
        probe_log("log: no writable log file; console output only");
        return;
    }

    /* Written straight to the file: the banner separates this run from the ones
     * already in it, which is meaningless on the console. */
    GetLocalTime(&now);
    fprintf(g_log,
            "\n\n================================================================\n"
            "RUN %04u-%02u-%02u %02u:%02u:%02u  arch=%s  %s\n"
            "================================================================\n",
            now.wYear, now.wMonth, now.wDay, now.wHour, now.wMinute, now.wSecond,
            WXR_ARCH, (tag && *tag) ? tag : "(no tag)");
    fflush(g_log);

    probe_log("log: %s (appending)", path);
    if (tag && *tag) probe_log("run: %s", tag);
}

/* -------------------------------------------------- native-allocates round trip */

enum {
    VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO = 5,
    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT    = 0x1,
    VK_MAX_MEMORY_TYPES                    = 32,
    VK_MAX_MEMORY_HEAPS                    = 16
};

typedef struct VkMemoryRequirements {
    uint64_t size;
    uint64_t alignment;
    uint32_t memoryTypeBits;
} VkMemoryRequirements;

typedef struct VkMemoryAllocateInfo {
    uint32_t    sType;
    const void* pNext;
    uint64_t    allocationSize;
    uint32_t    memoryTypeIndex;
} VkMemoryAllocateInfo;

typedef struct VkMemoryType { uint32_t propertyFlags; uint32_t heapIndex; } VkMemoryType;
typedef struct VkMemoryHeap { uint64_t size; uint32_t flags; } VkMemoryHeap;

typedef struct VkPhysicalDeviceMemoryProperties {
    uint32_t     memoryTypeCount;
    VkMemoryType memoryTypes[VK_MAX_MEMORY_TYPES];
    uint32_t     memoryHeapCount;
    VkMemoryHeap memoryHeaps[VK_MAX_MEMORY_HEAPS];
} VkPhysicalDeviceMemoryProperties;

typedef void    (WINAPI* PFN_vkGetImageMemoryRequirements)(VkDispatchable, VkHandle64, VkMemoryRequirements*);
typedef void    (WINAPI* PFN_vkGetPhysicalDeviceMemoryProperties)(VkDispatchable, VkPhysicalDeviceMemoryProperties*);
typedef int32_t (WINAPI* PFN_vkAllocateMemory)(VkDispatchable, const VkMemoryAllocateInfo*, const void*, VkHandle64*);
typedef int32_t (WINAPI* PFN_vkBindImageMemory)(VkDispatchable, VkHandle64, VkHandle64, uint64_t);
typedef void    (WINAPI* PFN_vkFreeMemory)(VkDispatchable, VkHandle64, const void*);

static uint32_t pick_memory_type(const VkPhysicalDeviceMemoryProperties* props,
                                 uint32_t type_bits, uint32_t required)
{
    uint32_t i;
    for (i = 0; i < props->memoryTypeCount && i < VK_MAX_MEMORY_TYPES; i++) {
        if (!(type_bits & (1u << i))) continue;
        if ((props->memoryTypes[i].propertyFlags & required) == required) return i;
    }
    return 0xffffffffu;
}

/*
 * The test the whole design rests on: allocate a VkImage ourselves, hand it to
 * CreateTexture2DFromVkImage, and ask the resulting texture which VkImage it is
 * backed by. If the handle that comes back is the one we passed in, then a
 * native allocation really can become the surface a game renders into -- which
 * is the entire point of the zero-copy path.
 *
 * The earlier check only proved slot 10 accepts a descriptor and hands back an
 * object describing it. That is consistent with the real function, but it was
 * reached by passing VkImage 0 and getting S_OK, so it says nothing about
 * whether the image argument is honoured. This closes that gap.
 */
static void probe_roundtrip(InteropDevice* interop, uint64_t physical_device, uint64_t vk_device)
{
    HMODULE vulkan;
    VkDispatchable phys = (VkDispatchable)(uintptr_t)physical_device;
    VkDispatchable dev = (VkDispatchable)(uintptr_t)vk_device;

    PFN_vkCreateImage create_image;
    PFN_vkDestroyImage destroy_image;
    PFN_vkGetImageMemoryRequirements get_requirements;
    PFN_vkGetPhysicalDeviceMemoryProperties get_memory_properties;
    PFN_vkAllocateMemory allocate;
    PFN_vkBindImageMemory bind;
    PFN_vkFreeMemory free_memory;

    VkImageCreateInfo create;
    VkMemoryRequirements requirements;
    VkPhysicalDeviceMemoryProperties memory_properties;
    VkMemoryAllocateInfo allocation;
    VkHandle64 image = 0, memory = 0;
    uint32_t type_index;
    int32_t result;

    section("Native-allocates round trip (VkImage -> D3D11 texture -> VkImage)");

    if (!physical_device || !vk_device) {
        probe_fail("no Vulkan handles; cannot round trip");
        return;
    }

    vulkan = LoadLibraryA("vulkan-1.dll");
    if (!vulkan) {
        probe_fail("vulkan-1.dll unavailable");
        return;
    }

    create_image = (PFN_vkCreateImage)(void*)GetProcAddress(vulkan, "vkCreateImage");
    destroy_image = (PFN_vkDestroyImage)(void*)GetProcAddress(vulkan, "vkDestroyImage");
    get_requirements = (PFN_vkGetImageMemoryRequirements)(void*)GetProcAddress(vulkan, "vkGetImageMemoryRequirements");
    get_memory_properties = (PFN_vkGetPhysicalDeviceMemoryProperties)(void*)GetProcAddress(vulkan, "vkGetPhysicalDeviceMemoryProperties");
    allocate = (PFN_vkAllocateMemory)(void*)GetProcAddress(vulkan, "vkAllocateMemory");
    bind = (PFN_vkBindImageMemory)(void*)GetProcAddress(vulkan, "vkBindImageMemory");
    free_memory = (PFN_vkFreeMemory)(void*)GetProcAddress(vulkan, "vkFreeMemory");

    if (!create_image || !destroy_image || !get_requirements ||
        !get_memory_properties || !allocate || !bind || !free_memory) {
        probe_fail("vulkan-1.dll is missing an entry point this test needs");
        return;
    }

    /* Deliberately the same shape as the descriptor handed to slot 10 below: a
     * mismatch there is a legitimate reason for a real implementation to
     * refuse, and would muddle the result. */
    memset(&create, 0, sizeof(create));
    create.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    create.imageType = VK_IMAGE_TYPE_2D;
    create.format = VK_FORMAT_R8G8B8A8_UNORM;
    create.extent.width = 256;
    create.extent.height = 256;
    create.extent.depth = 1;
    create.mipLevels = 1;
    create.arrayLayers = 1;
    create.samples = VK_SAMPLE_COUNT_1_BIT;
    create.tiling = VK_IMAGE_TILING_OPTIMAL;
    create.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
                   VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    create.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    create.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    result = create_image(dev, &create, NULL, &image);
    if (result != VK_SUCCESS || !image) {
        probe_fail("vkCreateImage failed, VkResult=%d", result);
        return;
    }
    probe_log("  allocated VkImage 0x%016llx", (unsigned long long)image);

    /* Bound to real memory, so the texture wraps a usable image rather than a
     * bare handle -- an implementation is entitled to reject the latter. */
    memset(&requirements, 0, sizeof(requirements));
    get_requirements(dev, image, &requirements);
    memset(&memory_properties, 0, sizeof(memory_properties));
    get_memory_properties(phys, &memory_properties);
    type_index = pick_memory_type(&memory_properties, requirements.memoryTypeBits,
                                  VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (type_index == 0xffffffffu) {
        probe_fail("no device-local memory type for this image (bits 0x%08x)",
                   requirements.memoryTypeBits);
        destroy_image(dev, image, NULL);
        return;
    }

    memset(&allocation, 0, sizeof(allocation));
    allocation.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocation.allocationSize = requirements.size;
    allocation.memoryTypeIndex = type_index;
    result = allocate(dev, &allocation, NULL, &memory);
    if (result != VK_SUCCESS || !memory) {
        probe_fail("vkAllocateMemory failed, VkResult=%d", result);
        destroy_image(dev, image, NULL);
        return;
    }
    result = bind(dev, image, memory, 0);
    if (result != VK_SUCCESS) {
        probe_fail("vkBindImageMemory failed, VkResult=%d", result);
        free_memory(dev, memory, NULL);
        destroy_image(dev, image, NULL);
        return;
    }
    probe_log("  bound %llu bytes of device-local memory (type %u)",
              (unsigned long long)requirements.size, type_index);

#if !WXR_HAVE_SEH
    probe_log("  wrapping needs SEH; this build has none, stopping here");
#else
    {
        ID3D11Texture2D* wrapped = NULL;
        struct {
            UINT Width, Height, MipLevels, ArraySize;
            DXGI_FORMAT Format;
            DXGI_SAMPLE_DESC SampleDesc;
            D3D11_USAGE Usage;
            UINT BindFlags, CPUAccessFlags, MiscFlags;
            UINT TextureLayout;
        } desc;
        HRESULT hr = E_FAIL;

        memset(&desc, 0, sizeof(desc));
        desc.Width = 256;
        desc.Height = 256;
        desc.MipLevels = 1;
        desc.ArraySize = 1;
        desc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
        desc.SampleDesc.Count = 1;
        desc.BindFlags = D3D11_BIND_SHADER_RESOURCE | D3D11_BIND_RENDER_TARGET;

        __try {
            hr = interop->lpVtbl->CreateTexture2DFromVkImage(interop, &desc, image, &wrapped);
        } __except (EXCEPTION_EXECUTE_HANDLER) {
            probe_fail("wrapping raised exception 0x%08lx", (unsigned long)GetExceptionCode());
        }

        if (FAILED(hr) || !wrapped) {
            probe_fail("CreateTexture2DFromVkImage refused a real image, hr=0x%08lx",
                       (unsigned long)hr);
        } else {
            InteropSurface* surface = NULL;
            probe_log("  wrapped as ID3D11Texture2D %p", (void*)wrapped);

            hr = ID3D11Texture2D_QueryInterface(wrapped, &IID_IDXGIVkInteropSurface,
                                                (void**)&surface);
            if (FAILED(hr) || !surface) {
                probe_fail("wrapped texture has no IDXGIVkInteropSurface, hr=0x%08lx",
                           (unsigned long)hr);
            } else {
                uint64_t readback = 0;
                uint32_t layout = 0;
                hr = surface->lpVtbl->GetVulkanImageInfo(surface, &readback, &layout, NULL);
                if (FAILED(hr)) {
                    probe_fail("GetVulkanImageInfo on the wrapped texture failed, hr=0x%08lx",
                               (unsigned long)hr);
                } else {
                    probe_log("  texture reports VkImage 0x%016llx (layout %u)",
                              (unsigned long long)readback, layout);
                    if (readback == image) {
                        probe_log("  IDENTICAL to the image we allocated");
                        probe_log("  verdict: ROUND TRIP CONFIRMED - a natively allocated image");
                        probe_log("  can be the surface a D3D11 game renders into, zero copy");
                    } else {
                        probe_fail("handle differs - the image argument was NOT honoured; "
                                   "slot 10 is not usable for the zero-copy path");
                    }
                }
                surface->lpVtbl->Release(surface);
            }
            ID3D11Texture2D_Release(wrapped);
        }
    }
#endif

    /* Released after the texture, which held its own reference to the image. */
    free_memory(dev, memory, NULL);
    destroy_image(dev, image, NULL);
}

/* ------------------------------------------- is the NT handle fd-backed? */

enum {
    VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO = 1000072001,
    VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO       = 1000072002,
    VK_STRUCTURE_TYPE_MEMORY_GET_WIN32_HANDLE_INFO_KHR  = 1000073003,
    VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO    = 1000127001,
    VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT     = 0x2
};

typedef struct VkExternalMemoryImageCreateInfo {
    uint32_t    sType;
    const void* pNext;
    uint32_t    handleTypes;
} VkExternalMemoryImageCreateInfo;

typedef struct VkExportMemoryAllocateInfo {
    uint32_t    sType;
    const void* pNext;
    uint32_t    handleTypes;
} VkExportMemoryAllocateInfo;

typedef struct VkMemoryDedicatedAllocateInfo {
    uint32_t    sType;
    const void* pNext;
    VkHandle64  image;
    VkHandle64  buffer;
} VkMemoryDedicatedAllocateInfo;

typedef struct VkMemoryGetWin32HandleInfoKHR {
    uint32_t    sType;
    const void* pNext;
    VkHandle64  memory;
    uint32_t    handleType;
} VkMemoryGetWin32HandleInfoKHR;

typedef int32_t (WINAPI* PFN_vkGetMemoryWin32HandleKHR)(VkDispatchable, const VkMemoryGetWin32HandleInfoKHR*, HANDLE*);

/* Lists the Wine process's open unix file descriptors through /proc, which Wine
 * maps onto the Z: drive. An export that opens a new descriptor is an export
 * that produced an fd, and the numbers let us say which one it was. */
#define MAX_TRACKED_FDS 512

static int list_unix_fds(int* out, int max)
{
    WIN32_FIND_DATAA entry;
    HANDLE search = FindFirstFileA("Z:\\proc\\self\\fd\\*", &entry);
    int count = 0;

    if (search == INVALID_HANDLE_VALUE) return -1;
    do {
        if (entry.cFileName[0] == '.') continue;
        if (count < max) out[count] = atoi(entry.cFileName);
        count++;
    } while (FindNextFileA(search, &entry));
    FindClose(search);
    return count;
}

static int fd_in(const int* list, int count, int fd)
{
    int i;
    for (i = 0; i < count; i++) if (list[i] == fd) return 1;
    return 0;
}

/*
 * Asks Wine what kind of object the handle names, which is the question the
 * descriptor count was standing in for.
 *
 * Wine has no NT kernel: every handle is a wineserver object, and one that is
 * backed by a host file descriptor is reported as a file-like type and answers
 * file queries. A handle naming a purely internal object answers none of them.
 * This needs no /proc, which matters because Wine's Z: drive is the container
 * rootfs rather than the host root, so the guest's /proc is not reachable
 * through it.
 */
typedef struct { USHORT Length; USHORT MaximumLength; PWSTR Buffer; } ProbeUnicodeString;

/* Returns 1 when the handle names a wineserver object that carries a host file
 * descriptor, 0 when it clearly does not, -1 when Wine would not say. */
static int describe_handle(HANDLE handle)
{
    typedef LONG (WINAPI* PFN_NtQueryObject)(HANDLE, int, void*, ULONG, ULONG*);

    HMODULE ntdll = GetModuleHandleA("ntdll.dll");
    PFN_NtQueryObject query;
    unsigned char buffer[1024];
    ProbeUnicodeString* name = (ProbeUnicodeString*)buffer;
    ULONG written = 0;
    LARGE_INTEGER size;
    DWORD type;

    type = GetFileType(handle);
    probe_log("  GetFileType: %s (%lu)",
              type == FILE_TYPE_DISK    ? "DISK -- file-like, so fd-backed" :
              type == FILE_TYPE_CHAR    ? "CHAR -- a device, so fd-backed" :
              type == FILE_TYPE_PIPE    ? "PIPE -- fd-backed" :
              type == FILE_TYPE_UNKNOWN ? "UNKNOWN -- not a file-like object" : "?",
              (unsigned long)type);

    memset(&size, 0, sizeof(size));
    if (GetFileSizeEx(handle, &size))
        probe_log("  GetFileSizeEx: %lld bytes -- it has a size, so it is a real "
                  "host object", (long long)size.QuadPart);
    else
        probe_log("  GetFileSizeEx: refused (error %lu)", GetLastError());

    /* ObjectTypeInformation == 2. Wine fills in the wineserver type name. */
    query = ntdll ? (PFN_NtQueryObject)(void*)GetProcAddress(ntdll, "NtQueryObject") : NULL;
    if (!query) {
        probe_log("  NtQueryObject: unavailable");
        return -1;
    }
    memset(buffer, 0, sizeof(buffer));
    if (query(handle, 2, buffer, sizeof(buffer), &written) == 0 && name->Buffer && name->Length) {
        probe_log("  wineserver object type: %.*ls", name->Length / 2, name->Buffer);
        /* Wine builds a File object around a host descriptor -- that is what
         * the type means, and what wine_server_handle_to_fd hands back. The
         * file queries above failing is consistent with it: the descriptor is
         * a driver or memfd object, not a regular file. */
        if (name->Length == (USHORT)(4 * sizeof(WCHAR)) &&
            !memcmp(name->Buffer, L"File", 4 * sizeof(WCHAR)))
            return 1;
        return 0;
    }
    probe_log("  NtQueryObject: no type name returned");
    return -1;
}

/* What the descriptor actually refers to -- a dma-buf, a driver node, a memfd.
 * Wine resolves /proc symlinks where it can reach them; where it cannot, the
 * handle interrogation above answers the question instead. */
static void describe_fd(int fd)
{
    char path[64];
    char resolved[MAX_PATH] = {0};
    HANDLE file;
    DWORD length;

    snprintf(path, sizeof(path), "Z:\\proc\\self\\fd\\%d", fd);
    file = CreateFileA(path, 0, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                       NULL, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, NULL);
    if (file == INVALID_HANDLE_VALUE) {
        probe_log("    fd %d -> (cannot open to identify: error %lu)", fd, GetLastError());
        return;
    }
    length = GetFinalPathNameByHandleA(file, resolved, sizeof(resolved) - 1, 0);
    CloseHandle(file);

    if (length && length < sizeof(resolved)) probe_log("    fd %d -> %s", fd, resolved);
    else probe_log("    fd %d -> (target not resolvable)", fd);
}

/* The host-side pid, not the Windows one, so /proc can be inspected from adb
 * while the probe holds the handle open. */
static int read_unix_pid(void)
{
    char buffer[64] = {0};
    DWORD read = 0;
    HANDLE file = CreateFileA("Z:\\proc\\self\\stat", GENERIC_READ,
                              FILE_SHARE_READ | FILE_SHARE_WRITE, NULL,
                              OPEN_EXISTING, 0, NULL);
    if (file == INVALID_HANDLE_VALUE) return -1;
    ReadFile(file, buffer, sizeof(buffer) - 1, &read, NULL);
    CloseHandle(file);
    return read ? atoi(buffer) : -1;
}

/*
 * DXVK's device has VK_KHR_external_memory_win32 enabled and nothing else, so
 * an NT handle is the only way image memory can leave it. Wine has no NT
 * kernel: it backs handles with host objects, and if this one is backed by a
 * file descriptor then a unixlib could unwrap it and hand the fd to the
 * compositor process -- which the native probe confirmed can import one.
 *
 * The test is indirect but hard to argue with: count the process's open
 * descriptors, export, count again.
 */
static void probe_win32_handle_backing(uint64_t physical_device, uint64_t vk_device)
{
    HMODULE vulkan;
    VkDispatchable phys = (VkDispatchable)(uintptr_t)physical_device;
    VkDispatchable dev = (VkDispatchable)(uintptr_t)vk_device;

    PFN_vkGetDeviceProcAddr get_device_proc;
    PFN_vkCreateImage create_image;
    PFN_vkDestroyImage destroy_image;
    PFN_vkGetImageMemoryRequirements get_requirements;
    PFN_vkGetPhysicalDeviceMemoryProperties get_memory_properties;
    PFN_vkAllocateMemory allocate;
    PFN_vkFreeMemory free_memory;
    PFN_vkBindImageMemory bind;
    PFN_vkGetMemoryWin32HandleKHR get_handle;

    VkExternalMemoryImageCreateInfo external;
    VkImageCreateInfo create;
    VkMemoryRequirements requirements;
    VkPhysicalDeviceMemoryProperties memory_properties;
    VkMemoryDedicatedAllocateInfo dedicated;
    VkExportMemoryAllocateInfo export_info;
    VkMemoryAllocateInfo allocation;
    VkMemoryGetWin32HandleInfoKHR handle_info;

    VkHandle64 image = 0, memory = 0;
    HANDLE nt_handle = NULL;
    uint32_t type_index;
    int fds_before[MAX_TRACKED_FDS], fds_after[MAX_TRACKED_FDS];
    int before, after, pid, backing = -1;
    const char* hold;
    int32_t result;

    section("Is Wine's Win32 memory handle backed by a file descriptor?");

    if (!physical_device || !vk_device) {
        probe_fail("no Vulkan handles to export from");
        return;
    }

    vulkan = LoadLibraryA("vulkan-1.dll");
    if (!vulkan) {
        probe_fail("vulkan-1.dll unavailable");
        return;
    }

    get_device_proc = (PFN_vkGetDeviceProcAddr)(void*)GetProcAddress(vulkan, "vkGetDeviceProcAddr");
    create_image = (PFN_vkCreateImage)(void*)GetProcAddress(vulkan, "vkCreateImage");
    destroy_image = (PFN_vkDestroyImage)(void*)GetProcAddress(vulkan, "vkDestroyImage");
    get_requirements = (PFN_vkGetImageMemoryRequirements)(void*)GetProcAddress(vulkan, "vkGetImageMemoryRequirements");
    get_memory_properties = (PFN_vkGetPhysicalDeviceMemoryProperties)(void*)GetProcAddress(vulkan, "vkGetPhysicalDeviceMemoryProperties");
    allocate = (PFN_vkAllocateMemory)(void*)GetProcAddress(vulkan, "vkAllocateMemory");
    free_memory = (PFN_vkFreeMemory)(void*)GetProcAddress(vulkan, "vkFreeMemory");
    bind = (PFN_vkBindImageMemory)(void*)GetProcAddress(vulkan, "vkBindImageMemory");

    if (!get_device_proc || !create_image || !destroy_image || !get_requirements ||
        !get_memory_properties || !allocate || !free_memory || !bind) {
        probe_fail("vulkan-1.dll is missing an entry point this test needs");
        return;
    }

    /* Resolved on the device, so its absence means DXVK never enabled the
     * extension rather than that the loader lacks it. */
    get_handle = (PFN_vkGetMemoryWin32HandleKHR)get_device_proc(dev, "vkGetMemoryWin32HandleKHR");
    if (!get_handle) {
        probe_fail("vkGetMemoryWin32HandleKHR absent - no export route at all");
        return;
    }

    /* Declared exportable at creation: memory not allocated for export cannot
     * be exported afterwards. */
    memset(&external, 0, sizeof(external));
    external.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    external.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;

    memset(&create, 0, sizeof(create));
    create.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    create.pNext = &external;
    create.imageType = VK_IMAGE_TYPE_2D;
    create.format = VK_FORMAT_R8G8B8A8_UNORM;
    create.extent.width = 256;
    create.extent.height = 256;
    create.extent.depth = 1;
    create.mipLevels = 1;
    create.arrayLayers = 1;
    create.samples = VK_SAMPLE_COUNT_1_BIT;
    create.tiling = VK_IMAGE_TILING_OPTIMAL;
    create.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    create.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    create.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    result = create_image(dev, &create, NULL, &image);
    if (result != VK_SUCCESS || !image) {
        probe_fail("exportable vkCreateImage failed, VkResult=%d "
                   "(the driver may refuse OPAQUE_WIN32 images)", result);
        return;
    }

    memset(&requirements, 0, sizeof(requirements));
    get_requirements(dev, image, &requirements);
    memset(&memory_properties, 0, sizeof(memory_properties));
    get_memory_properties(phys, &memory_properties);
    type_index = pick_memory_type(&memory_properties, requirements.memoryTypeBits,
                                  VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (type_index == 0xffffffffu) {
        probe_fail("no device-local memory type for an exportable image");
        destroy_image(dev, image, NULL);
        return;
    }

    /* Dedicated because the native side reported fd memory as dedicated-only,
     * and the same allocation has to satisfy both ends. */
    memset(&dedicated, 0, sizeof(dedicated));
    dedicated.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicated.image = image;

    memset(&export_info, 0, sizeof(export_info));
    export_info.sType = VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO;
    export_info.pNext = &dedicated;
    export_info.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;

    memset(&allocation, 0, sizeof(allocation));
    allocation.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocation.pNext = &export_info;
    allocation.allocationSize = requirements.size;
    allocation.memoryTypeIndex = type_index;

    result = allocate(dev, &allocation, NULL, &memory);
    if (result != VK_SUCCESS || !memory) {
        /* arm64ec rejected the dedicated form where x86_64 accepted it, which
         * points at the pNext chain rather than at the export itself. Drop the
         * dedicated link and see whether the export alone is acceptable --
         * worth knowing, since it changes what the transport can promise. */
        probe_log("  dedicated+export allocation failed (VkResult=%d), "
                  "retrying export-only", result);
        export_info.pNext = NULL;
        result = allocate(dev, &allocation, NULL, &memory);
    }
    if (result != VK_SUCCESS || !memory) {
        probe_fail("exportable vkAllocateMemory failed both ways, VkResult=%d", result);
        destroy_image(dev, image, NULL);
        return;
    }
    if (bind(dev, image, memory, 0) != VK_SUCCESS)
        probe_log("  (bind failed; the export test below still stands)");

    before = list_unix_fds(fds_before, MAX_TRACKED_FDS);
    pid = read_unix_pid();
    if (before < 0) {
        probe_log("  /proc is not reachable through Z:, so the descriptor count "
                  "cannot be compared; reporting the handle only");
    }

    memset(&handle_info, 0, sizeof(handle_info));
    handle_info.sType = VK_STRUCTURE_TYPE_MEMORY_GET_WIN32_HANDLE_INFO_KHR;
    handle_info.memory = memory;
    handle_info.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;

    result = get_handle(dev, &handle_info, &nt_handle);
    if (result != VK_SUCCESS || !nt_handle) {
        probe_fail("vkGetMemoryWin32HandleKHR failed, VkResult=%d", result);
    } else {
        after = list_unix_fds(fds_after, MAX_TRACKED_FDS);
        probe_log("  exported NT handle %p", (void*)nt_handle);
        probe_log("  host pid %d", pid);

        /* The answer, independent of whether /proc was reachable. */
        backing = describe_handle(nt_handle);

        if (before < 0 || after < 0) {
            /* No descriptor count available, so the wineserver object type is
             * the whole answer. */
            if (backing > 0) {
                probe_log("  verdict: FD-BACKED - Wine wrapped this export in a File");
                probe_log("  object, which carries a host descriptor a unixlib can unwrap");
            } else if (backing == 0) {
                probe_log("  verdict: EXPORTED, not a File - on Wine 11 the host fd sits behind "
                          "a D3DKMT object; see its type above");
            } else {
                probe_log("  verdict: INCONCLUSIVE - Wine would not name the object");
            }
        } else {
            probe_log("  open unix fds: %d before, %d after (delta %+d)",
                      before, after, after - before);
            if (after > before) {
                int i, shown = 0;
                for (i = 0; i < after && i < MAX_TRACKED_FDS && shown < 4; i++) {
                    if (fd_in(fds_before, before < MAX_TRACKED_FDS ? before : MAX_TRACKED_FDS,
                              fds_after[i]))
                        continue;
                    describe_fd(fds_after[i]);
                    shown++;
                }
                probe_log("  verdict: FD-BACKED - the export opened a host descriptor,");
                probe_log("  so a unixlib could unwrap this handle and pass the fd on");
            } else {
                probe_log("  verdict: NOT fd-backed - no descriptor was opened, so this");
                probe_log("  handle names something a unixlib cannot turn into an fd");
            }
        }

        /* Held open on request so the descriptor's target can be read from
         * /proc/<pid>/fd, which says what kind of object it actually is. */
        hold = getenv("WXR_DXVK_PROBE_HOLD");
        if (hold && atoi(hold) > 0) {
            probe_log("  holding the handle open for %d seconds "
                      "(inspect /proc/%d/fd now)", atoi(hold), pid);
            Sleep((DWORD)atoi(hold) * 1000);
        }
        CloseHandle(nt_handle);
    }

    free_memory(dev, memory, NULL);
    destroy_image(dev, image, NULL);
}

/* --------------------------------------------- can a semaphore be exported? */

enum {
    VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO                = 9,
    VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO         = 1000077000,
    VK_STRUCTURE_TYPE_SEMAPHORE_GET_WIN32_HANDLE_INFO_KHR  = 1000078003,
    VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT     = 0x2
};

typedef struct { uint32_t sType; const void* pNext; uint32_t flags; } VkSemaphoreCreateInfo;
typedef struct { uint32_t sType; const void* pNext; uint32_t handleTypes; } VkExportSemaphoreCreateInfo;
typedef struct { uint32_t sType; const void* pNext; VkHandle64 semaphore; uint32_t handleType; } VkSemaphoreGetWin32HandleInfoKHR;

typedef int32_t (WINAPI* PFN_vkCreateSemaphore)(VkDispatchable, const VkSemaphoreCreateInfo*, const void*, VkHandle64*);
typedef void    (WINAPI* PFN_vkDestroySemaphore)(VkDispatchable, VkHandle64, const void*);
typedef int32_t (WINAPI* PFN_vkGetSemaphoreWin32HandleKHR)(VkDispatchable, const VkSemaphoreGetWin32HandleInfoKHR*, HANDLE*);

/* The other half of the transport: without a semaphore that leaves the process,
 * the compositor could read an image the game is still writing. A binary one,
 * so it holds with DXVK_DISABLE_TIMELINE_SEMAPHORES=1. */
static void probe_semaphore_export(uint64_t vk_device)
{
    VkDispatchable dev = (VkDispatchable)(uintptr_t)vk_device;
    HMODULE vulkan = LoadLibraryA("vulkan-1.dll");
    PFN_vkGetDeviceProcAddr get_device_proc;
    PFN_vkCreateSemaphore create_semaphore;
    PFN_vkDestroySemaphore destroy_semaphore;
    PFN_vkGetSemaphoreWin32HandleKHR get_handle;
    VkExportSemaphoreCreateInfo export_info;
    VkSemaphoreCreateInfo create;
    VkSemaphoreGetWin32HandleInfoKHR handle_info;
    VkHandle64 semaphore = 0;
    HANDLE nt_handle = NULL;
    int32_t result;

    section("Can a semaphore be exported from DXVK's device?");

    if (!vk_device || !vulkan) {
        probe_fail("no Vulkan device to export from");
        return;
    }
    get_device_proc = (PFN_vkGetDeviceProcAddr)(void*)GetProcAddress(vulkan, "vkGetDeviceProcAddr");
    create_semaphore = (PFN_vkCreateSemaphore)(void*)GetProcAddress(vulkan, "vkCreateSemaphore");
    destroy_semaphore = (PFN_vkDestroySemaphore)(void*)GetProcAddress(vulkan, "vkDestroySemaphore");
    if (!get_device_proc || !create_semaphore || !destroy_semaphore) {
        probe_fail("vulkan-1.dll is missing an entry point this test needs");
        return;
    }

    get_handle = (PFN_vkGetSemaphoreWin32HandleKHR)get_device_proc(dev, "vkGetSemaphoreWin32HandleKHR");
    if (!get_handle) {
        probe_fail("vkGetSemaphoreWin32HandleKHR absent - DXVK did not enable "
                   "VK_KHR_external_semaphore_win32");
        return;
    }

    memset(&export_info, 0, sizeof(export_info));
    export_info.sType = VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO;
    export_info.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;

    memset(&create, 0, sizeof(create));
    create.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    create.pNext = &export_info;

    result = create_semaphore(dev, &create, NULL, &semaphore);
    if (result != VK_SUCCESS || !semaphore) {
        probe_fail("exportable vkCreateSemaphore failed, VkResult=%d", result);
        return;
    }

    memset(&handle_info, 0, sizeof(handle_info));
    handle_info.sType = VK_STRUCTURE_TYPE_SEMAPHORE_GET_WIN32_HANDLE_INFO_KHR;
    handle_info.semaphore = semaphore;
    handle_info.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;

    result = get_handle(dev, &handle_info, &nt_handle);
    if (result != VK_SUCCESS || !nt_handle) {
        probe_fail("vkGetSemaphoreWin32HandleKHR failed, VkResult=%d", result);
    } else {
        probe_log("  exported NT handle %p", (void*)nt_handle);
        describe_handle(nt_handle);
        probe_log("  verdict: SEMAPHORE EXPORTED - a GPU fence can leave the process");
        CloseHandle(nt_handle);
    }
    destroy_semaphore(dev, semaphore, NULL);
}

/* ------------------------------------------- bridge round trip (CPU wait) */

#include "../wxr_bridge/wxr_bridge.h"

typedef LONG (WINAPI* PFN_WxrBridgeCall)(unsigned int, void*);

/* IDXGIResource1, declared here so the probe needs no dxguid. */
static const GUID kIID_IDXGIResource1 =
    {0x30961379, 0x4609, 0x4a41, {0x99, 0x8e, 0x54, 0xfe, 0x56, 0x7e, 0xe0, 0xc1}};

typedef struct DxgiResource1 DxgiResource1;
typedef struct {
    HRESULT (WINAPI* QueryInterface)(DxgiResource1*, REFIID, void**);
    ULONG   (WINAPI* AddRef)(DxgiResource1*);
    ULONG   (WINAPI* Release)(DxgiResource1*);
    void*   object[4];      /* IDXGIObject */
    void*   device_sub[1];  /* IDXGIDeviceSubObject */
    void*   resource[4];    /* IDXGIResource */
    HRESULT (WINAPI* CreateSubresourceSurface)(DxgiResource1*, UINT, void**);
    HRESULT (WINAPI* CreateSharedHandle)(DxgiResource1*, const SECURITY_ATTRIBUTES*, DWORD, LPCWSTR, HANDLE*);
} DxgiResource1Vtbl;
struct DxgiResource1 { const DxgiResource1Vtbl* lpVtbl; };

/*
 * The whole transport, minus the app: a shared D3D11 texture holding a known
 * pattern, the CPU waiting until the GPU has written it, then wxr_bridge
 * turning the handle into an fd, importing it on its own device, copying it
 * into an AHardwareBuffer and checking the pattern survived.
 */
static void probe_bridge(ID3D11Device* device, DXGI_FORMAT format)
{
    enum { W = 256, H = 256 };
    HMODULE bridge;
    PFN_WxrBridgeCall call;
    D3D11_TEXTURE2D_DESC desc;
    D3D11_SUBRESOURCE_DATA initial;
    D3D11_QUERY_DESC query_desc;
    ID3D11Texture2D* texture = NULL;
    ID3D11DeviceContext* context = NULL;
    ID3D11Query* query = NULL;
    DxgiResource1* resource = NULL;
    HANDLE shared = NULL;
    uint32_t* pattern;
    struct wxr_bridge_init_args init;
    struct wxr_bridge_import_args import;
    struct wxr_bridge_readback_args readback;
    LONG status;
    HRESULT hr;
    DWORD start;
    int x, y;

    section(format == DXGI_FORMAT_R8G8B8A8_TYPELESS
            ? "wxr_bridge round trip, typeless texture (how OXRWXR creates swapchains)"
            : "wxr_bridge round trip (shared texture -> fd -> AHardwareBuffer)");

    bridge = LoadLibraryA("wxr_bridge.dll");
    if (!bridge) {
        probe_log("  wxr_bridge.dll not installed (error %lu) - skipped", GetLastError());
        return;
    }
    call = (PFN_WxrBridgeCall)(void*)GetProcAddress(bridge, "WxrBridgeCall");
    if (!call) {
        probe_fail("WxrBridgeCall not exported");
        return;
    }

    memset(&init, 0, sizeof(init));
    init.abi = WXR_BRIDGE_ABI;
    status = call(WXR_BRIDGE_INIT, &init);
    if (status) {
        probe_fail("bridge unixlib unreachable, NTSTATUS 0x%08lx "
                   "(wxr_bridge.so missing, or the DLL was not loaded as a builtin)", (unsigned long)status);
        return;
    }
    if (init.result) {
        probe_fail("relay device: %s", init.message);
        return;
    }
    probe_log("  relay device: %s", init.message);

    pattern = malloc(W * H * 4);
    if (!pattern) return;
    for (y = 0; y < H; y++)
        for (x = 0; x < W; x++) pattern[y * W + x] = wxr_bridge_pattern(x, y);

    memset(&desc, 0, sizeof(desc));
    desc.Width = W;
    desc.Height = H;
    desc.MipLevels = 1;
    desc.ArraySize = 1;
    desc.Format = format;
    desc.SampleDesc.Count = 1;
    desc.Usage = D3D11_USAGE_DEFAULT;
    desc.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
    desc.MiscFlags = D3D11_RESOURCE_MISC_SHARED | D3D11_RESOURCE_MISC_SHARED_NTHANDLE;
    initial.pSysMem = pattern;
    initial.SysMemPitch = W * 4;
    initial.SysMemSlicePitch = 0;

    hr = ID3D11Device_CreateTexture2D(device, &desc, &initial, &texture);
    free(pattern);
    if (FAILED(hr)) {
        probe_fail("shared CreateTexture2D failed, hr=0x%08lx", (unsigned long)hr);
        return;
    }

    hr = ID3D11Texture2D_QueryInterface(texture, &kIID_IDXGIResource1, (void**)&resource);
    if (SUCCEEDED(hr))
        hr = resource->lpVtbl->CreateSharedHandle(resource, NULL,
                 0x80000000 /* DXGI_SHARED_RESOURCE_READ */ | 1 /* WRITE */, NULL, &shared);
    if (FAILED(hr) || !shared) {
        probe_fail("CreateSharedHandle failed, hr=0x%08lx", (unsigned long)hr);
        goto done;
    }
    probe_log("  shared NT handle %p", (void*)shared);

    /* The CPU wait: flush, then spin on an event query until the upload is done. */
    ID3D11Device_GetImmediateContext(device, &context);
    memset(&query_desc, 0, sizeof(query_desc));
    query_desc.Query = D3D11_QUERY_EVENT;
    ID3D11Device_CreateQuery(device, &query_desc, &query);
    ID3D11DeviceContext_End(context, (ID3D11Asynchronous*)query);
    ID3D11DeviceContext_Flush(context);
    start = GetTickCount();
    while (ID3D11DeviceContext_GetData(context, (ID3D11Asynchronous*)query, NULL, 0, 0) == S_FALSE) {
        if (GetTickCount() - start > 2000) { probe_fail("event query never signalled"); goto done; }
        Sleep(0);
    }
    probe_log("  GPU finished writing after %lu ms (CPU wait)", GetTickCount() - start);

    memset(&import, 0, sizeof(import));
    import.nt_handle = (uint64_t)(uintptr_t)shared;
    import.width = W;
    import.height = H;
    import.layers = 1;
    import.vk_format = 37; /* VK_FORMAT_R8G8B8A8_UNORM: DXVK maps the typeless format to it too */
    if (format == DXGI_FORMAT_R8G8B8A8_TYPELESS) import.flags = WXR_BRIDGE_IMPORT_MUTABLE_FORMAT;
    call(WXR_BRIDGE_IMPORT, &import);
    if (import.result) {
        probe_fail("import: %s", import.message);
        goto done;
    }
    probe_log("  import: %s", import.message);

    memset(&readback, 0, sizeof(readback));
    readback.id = import.id;
    call(WXR_BRIDGE_COPY_READBACK, &readback);
    if (readback.result) {
        probe_fail("copy: %s", readback.message);
        goto done;
    }
    probe_log("  copy: %s", readback.message);
    if (readback.mismatches)
        probe_log("  verdict: DATA WRONG - %u of %u sampled pixels differ (first 0x%08x); "
                  "the layouts disagree", readback.mismatches, readback.checked, readback.first_bad);
    else
        probe_log("  verdict: TRANSPORT WORKS - all %u sampled pixels arrived intact in the "
                  "AHardwareBuffer", readback.checked);

    /* The per-frame path blits instead of copying; its result is dumped for an offline pattern check. */
    {
        struct wxr_bridge_submit_args submit;
        struct wxr_bridge_dump_args dump;
        memset(&submit, 0, sizeof(submit));
        submit.id = import.id;
        call(WXR_BRIDGE_SUBMIT, &submit);
        memset(&dump, 0, sizeof(dump));
        dump.id = import.id;
        snprintf(dump.path, sizeof(dump.path), "/storage/emulated/0/Download/wxr_probe_submit_%s.rgba",
                 format == DXGI_FORMAT_R8G8B8A8_TYPELESS ? "typeless" : "typed");
        call(WXR_BRIDGE_DUMP, &dump);
        probe_log("  submit: VkResult %d; dump: %s", submit.result, dump.message);
    }

done:
    if (shared) CloseHandle(shared);
    if (query) ID3D11Query_Release(query);
    if (context) ID3D11DeviceContext_Release(context);
    if (resource) resource->lpVtbl->Release(resource);
    if (texture) ID3D11Texture2D_Release(texture);
}

static void run_probe(const ProbeOptions* options)
{
    ID3D11Device* device;
    InteropDevice* interop;
    uint64_t physical_device = 0, vk_device = 0;
    int is_device1 = 0;

    g_failures = 0;

    probe_log("WinlatorXR DXVK interop probe");
    open_log();

    probe_environment();

    device = create_device();
    if (!device) {
        probe_log("");
        probe_log("SUMMARY: no D3D11 device, nothing else can be answered (%d failure(s))",
                  g_failures);
    } else {
        interop = probe_interop_device(device, &physical_device, &vk_device, &is_device1);
        if (interop) probe_create_texture_slot(interop, options);

        probe_interop_surface(device);
        probe_vulkan(physical_device, vk_device);

        /* Last, and only once the pieces it depends on have reported: it needs
         * the interop device, working Vulkan entry points, and slot 10. */
        if (interop && options->try_create)
            probe_roundtrip(interop, physical_device, vk_device);

        /* Independent of slot 10: this asks whether an image can leave the Wine
         * process at all, which is the other half of the transport. */
        probe_win32_handle_backing(physical_device, vk_device);
        probe_semaphore_export(vk_device);
        probe_bridge(device, DXGI_FORMAT_R8G8B8A8_UNORM);
        probe_bridge(device, DXGI_FORMAT_R8G8B8A8_TYPELESS);

        if (interop) interop->lpVtbl->Release(interop);
        ID3D11Device_Release(device);

        probe_log("");
        probe_log("SUMMARY: %d question(s) could not be answered", g_failures);
        probe_log("Read the verdict lines above: interop device, slot 10, interop surface.");
    }

    if (g_log) {
        fclose(g_log);
        g_log = NULL;
    }
}

/* ------------------------------------------------------------------ exports */

/* rundll32.exe dxvk_interop_probe.dll,Probe [--try-create] */
__declspec(dllexport) void CALLBACK Probe(HWND window, HINSTANCE instance,
                                          LPSTR command_line, int show)
{
    ProbeOptions options = {0};
    (void)window; (void)instance; (void)show;
    if (command_line && strstr(command_line, "--try-create"))
        options.try_create = 1;
    run_probe(&options);
}

/* Same thing for a host that would rather call it directly. */
__declspec(dllexport) void WxrDxvkProbe(int try_create)
{
    ProbeOptions options;
    options.try_create = try_create;
    run_probe(&options);
}

static DWORD WINAPI probe_thread(LPVOID parameter)
{
    ProbeOptions options = {0};
    const char* value = (const char*)parameter;
    if (value && strstr(value, "try-create")) options.try_create = 1;
    run_probe(&options);
    return 0;
}

BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID reserved)
{
    static char mode[64];
    (void)reserved;

    if (reason == DLL_PROCESS_ATTACH) {
        const char* enabled = getenv("WXR_DXVK_PROBE");
        DisableThreadLibraryCalls(instance);
        /* Only auto-run when asked, and never on the loader lock: creating a
         * D3D11 device from DllMain would deadlock against the loader. */
        if (enabled && *enabled && strcmp(enabled, "0") != 0) {
            HANDLE thread;
            strncpy(mode, enabled, sizeof(mode) - 1);
            mode[sizeof(mode) - 1] = '\0';
            thread = CreateThread(NULL, 0, probe_thread, mode, 0, NULL);
            if (thread) CloseHandle(thread);
        }
    }
    return TRUE;
}
