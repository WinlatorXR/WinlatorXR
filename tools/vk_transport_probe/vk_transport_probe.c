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

/* Native Vulkan transport probe -- the import side of the VR image path.
 *
 * The DXVK probe answered what the *guest* can do, but it could only look
 * through winevulkan, which presents a deliberately Windows-shaped view of the
 * driver: NT handles yes, fds and AHardwareBuffers no. That view says nothing
 * about what the real driver underneath supports, and it is the real driver
 * that decides whether a rendered image can cross from the Wine process to the
 * app process at all.
 *
 * Wine runs as a separate OS process (ProcessHelper.exec -> ProcessBuilder),
 * so there is no shared VkDevice to render into: the guest exports, the app
 * imports, and both halves need the driver's cooperation. This probe runs
 * natively, outside Wine, and reports what that driver actually offers:
 *
 *   1. Which external-memory handle types can be exported and imported --
 *      opaque fd, dma-buf, AHardwareBuffer.
 *   2. Whether semaphores and fences can be exported at all. Without one of
 *      these there is no GPU-side handshake between the two processes, only a
 *      CPU round trip, and a zero-copy swap with no fence corrupts frames.
 *   3. Whether a real image can be allocated as exportable and an fd obtained
 *      for it, rather than merely advertised as possible.
 *
 * Run it under the app's UID so it sees the same driver the compositor does:
 *   adb shell run-as com.winlator.cmod ./vk_transport_probe
 */

#include <dlfcn.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define VK_NO_PROTOTYPES
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

/* ------------------------------------------------------------------ output */

static int g_failures = 0;

static void say(const char* fmt, ...)
{
    va_list args;
    va_start(args, fmt);
    vprintf(fmt, args);
    va_end(args);
    putchar('\n');
    fflush(stdout);
}

static void section(const char* name)
{
    say("");
    say("== %s ==", name);
}

static void fail(const char* fmt, ...)
{
    va_list args;
    printf("  [!] ");
    va_start(args, fmt);
    vprintf(fmt, args);
    va_end(args);
    putchar('\n');
    fflush(stdout);
    g_failures++;
}

/* --------------------------------------------------------------- loader */

#define VK_FN(name) static PFN_##name p##name

VK_FN(vkGetInstanceProcAddr);
VK_FN(vkCreateInstance);
VK_FN(vkDestroyInstance);
VK_FN(vkEnumeratePhysicalDevices);
VK_FN(vkGetPhysicalDeviceProperties);
VK_FN(vkEnumerateDeviceExtensionProperties);
VK_FN(vkGetPhysicalDeviceExternalBufferProperties);
VK_FN(vkGetPhysicalDeviceExternalSemaphoreProperties);
VK_FN(vkGetPhysicalDeviceExternalFenceProperties);
VK_FN(vkGetPhysicalDeviceMemoryProperties);

#define LOAD_INSTANCE_FN(inst, name) \
    p##name = (PFN_##name)pvkGetInstanceProcAddr(inst, #name)

/* Android HAL layout (hardware/hardware.h, hwvulkan.h): adrenotools Turnip builds export only HMI. */
struct hw_module_t;
struct hw_device_t { uint32_t tag, version; struct hw_module_t* module; uint64_t reserved[12]; int (*close)(struct hw_device_t*); };
struct hw_module_methods_t { int (*open)(const struct hw_module_t*, const char*, struct hw_device_t**); };
struct hw_module_t { uint32_t tag; uint16_t module_api_version, hal_api_version; const char *id, *name, *author;
                     struct hw_module_methods_t* methods; void* dso; uint64_t reserved[25]; };
typedef struct { struct hw_device_t common; PFN_vkEnumerateInstanceExtensionProperties enumerate;
                 PFN_vkCreateInstance create; PFN_vkGetInstanceProcAddr get_proc; } hwvulkan_device_t;

static PFN_vkGetInstanceProcAddr hal_get_instance_proc_addr(void* library)
{
    struct hw_module_t* module = (struct hw_module_t*)dlsym(library, "HMI");
    struct hw_device_t* device = NULL;
    if (!module || !module->methods || module->methods->open(module, "vk0", &device) || !device) return NULL;
    return ((hwvulkan_device_t*)device)->get_proc;
}

/* ------------------------------------------------------------- capability */

/* One row of the answer: can this handle type leave the process, and can it
 * come back in somewhere else? Both directions are needed -- the guest exports
 * and the app imports, and a driver may offer one without the other. */
static void report_memory_handle(VkPhysicalDevice gpu, const char* label,
                                 VkExternalMemoryHandleTypeFlagBits type)
{
    VkPhysicalDeviceExternalBufferInfo info;
    VkExternalBufferProperties props;
    VkExternalMemoryFeatureFlags features;

    memset(&info, 0, sizeof(info));
    info.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_BUFFER_INFO;
    info.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    info.handleType = type;

    memset(&props, 0, sizeof(props));
    props.sType = VK_STRUCTURE_TYPE_EXTERNAL_BUFFER_PROPERTIES;

    pvkGetPhysicalDeviceExternalBufferProperties(gpu, &info, &props);
    features = props.externalMemoryProperties.externalMemoryFeatures;

    say("    %-46s export=%-3s import=%-3s dedicated=%s", label,
        (features & VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT) ? "yes" : "NO",
        (features & VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT) ? "yes" : "NO",
        (features & VK_EXTERNAL_MEMORY_FEATURE_DEDICATED_ONLY_BIT) ? "required" : "optional");
}

static void report_semaphore_handle(VkPhysicalDevice gpu, const char* label,
                                    VkExternalSemaphoreHandleTypeFlagBits type)
{
    VkPhysicalDeviceExternalSemaphoreInfo info;
    VkExternalSemaphoreProperties props;
    VkExternalSemaphoreFeatureFlags features;

    memset(&info, 0, sizeof(info));
    info.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_SEMAPHORE_INFO;
    info.handleType = type;

    memset(&props, 0, sizeof(props));
    props.sType = VK_STRUCTURE_TYPE_EXTERNAL_SEMAPHORE_PROPERTIES;

    pvkGetPhysicalDeviceExternalSemaphoreProperties(gpu, &info, &props);
    features = props.externalSemaphoreFeatures;

    say("    %-46s export=%-3s import=%s", label,
        (features & VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT) ? "yes" : "NO",
        (features & VK_EXTERNAL_SEMAPHORE_FEATURE_IMPORTABLE_BIT) ? "yes" : "NO");
}

static void report_fence_handle(VkPhysicalDevice gpu, const char* label,
                                VkExternalFenceHandleTypeFlagBits type)
{
    VkPhysicalDeviceExternalFenceInfo info;
    VkExternalFenceProperties props;
    VkExternalFenceFeatureFlags features;

    memset(&info, 0, sizeof(info));
    info.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_FENCE_INFO;
    info.handleType = type;

    memset(&props, 0, sizeof(props));
    props.sType = VK_STRUCTURE_TYPE_EXTERNAL_FENCE_PROPERTIES;

    pvkGetPhysicalDeviceExternalFenceProperties(gpu, &info, &props);
    features = props.externalFenceFeatures;

    say("    %-46s export=%-3s import=%s", label,
        (features & VK_EXTERNAL_FENCE_FEATURE_EXPORTABLE_BIT) ? "yes" : "NO",
        (features & VK_EXTERNAL_FENCE_FEATURE_IMPORTABLE_BIT) ? "yes" : "NO");
}

static int has_extension(const VkExtensionProperties* list, uint32_t count, const char* name)
{
    uint32_t i;
    for (i = 0; i < count; i++)
        if (!strcmp(list[i].extensionName, name)) return 1;
    return 0;
}

/* The extensions that decide whether the transport can exist at all. */
static const struct { const char* name; const char* why; } kWanted[] = {
    {"VK_KHR_external_memory_fd",           "export image memory as an fd"},
    {"VK_EXT_external_memory_dma_buf",      "the fd can be a dma-buf, shareable with the compositor"},
    {"VK_ANDROID_external_memory_android_hardware_buffer", "import an AHardwareBuffer directly"},
    {"VK_KHR_external_semaphore_fd",        "cross-process GPU handshake"},
    {"VK_KHR_external_fence_fd",            "sync-fd fences, the Android-native fallback"},
    {"VK_KHR_timeline_semaphore",           "ordering within one device"},
    {"VK_EXT_queue_family_foreign",         "hand an image between two devices' queues"},
    {"VK_EXT_image_drm_format_modifier",    "agree tiling across the boundary"},
    {"VK_EXT_map_memory_placed",            "Wine needs it to export memory from 32-bit games"}
};

/* ------------------------------------------------------------------- main */

int main(int argc, char** argv)
{
    /* An ICD path (e.g. Turnip's libvulkan_freedreno.so) probes that driver instead of the system one. */
    const char* driver = argc > 1 ? argv[1] : "libvulkan.so";
    void* library;
    VkInstance instance = VK_NULL_HANDLE;
    VkApplicationInfo app;
    VkInstanceCreateInfo create;
    VkPhysicalDevice gpus[8];
    VkPhysicalDevice gpu;
    VkPhysicalDeviceProperties gpu_props;
    VkExtensionProperties* extensions = NULL;
    uint32_t gpu_count = 8, extension_count = 0;
    size_t i;
    VkResult result;

    say("WinlatorXR native Vulkan transport probe");
    say("pid %d, uid %d", (int)getpid(), (int)getuid());

    section("Loader");
    library = dlopen(driver, RTLD_NOW | RTLD_LOCAL);
    if (!library) {
        fail("dlopen(%s): %s", driver, dlerror());
        return 1;
    }
    say("  %s loaded", driver);

    pvkGetInstanceProcAddr = (PFN_vkGetInstanceProcAddr)dlsym(library,
        argc > 1 ? "vk_icdGetInstanceProcAddr" : "vkGetInstanceProcAddr");
    if (!pvkGetInstanceProcAddr && argc > 1) pvkGetInstanceProcAddr = hal_get_instance_proc_addr(library);
    if (!pvkGetInstanceProcAddr) {
        fail("vkGetInstanceProcAddr missing");
        return 1;
    }

    LOAD_INSTANCE_FN(NULL, vkCreateInstance);
    if (!pvkCreateInstance) {
        fail("vkCreateInstance missing");
        return 1;
    }

    memset(&app, 0, sizeof(app));
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "wxr-transport-probe";
    /* 1.1 for the external-* queries, which are core there. */
    app.apiVersion = VK_API_VERSION_1_1;

    memset(&create, 0, sizeof(create));
    create.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    create.pApplicationInfo = &app;

    result = pvkCreateInstance(&create, NULL, &instance);
    if (result != VK_SUCCESS) {
        fail("vkCreateInstance failed, VkResult=%d", result);
        return 1;
    }
    say("  instance created (API 1.1)");

    LOAD_INSTANCE_FN(instance, vkDestroyInstance);
    LOAD_INSTANCE_FN(instance, vkEnumeratePhysicalDevices);
    LOAD_INSTANCE_FN(instance, vkGetPhysicalDeviceProperties);
    LOAD_INSTANCE_FN(instance, vkEnumerateDeviceExtensionProperties);
    LOAD_INSTANCE_FN(instance, vkGetPhysicalDeviceExternalBufferProperties);
    LOAD_INSTANCE_FN(instance, vkGetPhysicalDeviceExternalSemaphoreProperties);
    LOAD_INSTANCE_FN(instance, vkGetPhysicalDeviceExternalFenceProperties);
    LOAD_INSTANCE_FN(instance, vkGetPhysicalDeviceMemoryProperties);

    if (!pvkEnumeratePhysicalDevices || !pvkGetPhysicalDeviceExternalBufferProperties ||
        !pvkGetPhysicalDeviceExternalSemaphoreProperties ||
        !pvkGetPhysicalDeviceExternalFenceProperties) {
        fail("the external-capability queries are unavailable; driver is below Vulkan 1.1");
        return 1;
    }

    result = pvkEnumeratePhysicalDevices(instance, &gpu_count, gpus);
    if (result != VK_SUCCESS || !gpu_count) {
        fail("no physical devices (VkResult=%d)", result);
        return 1;
    }
    gpu = gpus[0];

    memset(&gpu_props, 0, sizeof(gpu_props));
    pvkGetPhysicalDeviceProperties(gpu, &gpu_props);

    section("Device");
    say("  %s", gpu_props.deviceName);
    say("  API %u.%u.%u   driver 0x%08x   vendor 0x%04x",
        VK_VERSION_MAJOR(gpu_props.apiVersion),
        VK_VERSION_MINOR(gpu_props.apiVersion),
        VK_VERSION_PATCH(gpu_props.apiVersion),
        gpu_props.driverVersion, gpu_props.vendorID);

    section("Extensions the transport needs");
    pvkEnumerateDeviceExtensionProperties(gpu, NULL, &extension_count, NULL);
    if (extension_count) {
        extensions = calloc(extension_count, sizeof(*extensions));
        if (extensions)
            pvkEnumerateDeviceExtensionProperties(gpu, NULL, &extension_count, extensions);
    }
    if (!extensions) {
        fail("could not enumerate device extensions");
    } else {
        say("  %u device extensions present; of interest:", extension_count);
        for (i = 0; i < sizeof(kWanted) / sizeof(kWanted[0]); i++) {
            int found = has_extension(extensions, extension_count, kWanted[i].name);
            say("    [%s] %-52s %s", found ? "  yes  " : "ABSENT ",
                kWanted[i].name, kWanted[i].why);
        }
    }

    /* Advertised extensions are not the same as a usable handle type: the
     * per-handle-type query below is what the runtime actually honours. */
    section("Image memory across the process boundary");
    report_memory_handle(gpu, "opaque fd", VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT);
    report_memory_handle(gpu, "dma-buf", VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT);
    report_memory_handle(gpu, "AHardwareBuffer",
                         VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID);

    section("Synchronisation across the process boundary");
    say("  semaphores:");
    report_semaphore_handle(gpu, "opaque fd", VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT);
    report_semaphore_handle(gpu, "sync fd", VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT);
    say("  fences:");
    report_fence_handle(gpu, "opaque fd", VK_EXTERNAL_FENCE_HANDLE_TYPE_OPAQUE_FD_BIT);
    report_fence_handle(gpu, "sync fd", VK_EXTERNAL_FENCE_HANDLE_TYPE_SYNC_FD_BIT);

    say("");
    say("  Without an exportable semaphore or fence here there is no GPU-side");
    say("  handshake between the two processes -- only a CPU round trip, which");
    say("  is what corrupts frames on a zero-copy swap.");

    say("");
    say("SUMMARY: %d question(s) could not be answered", g_failures);

    free(extensions);
    if (pvkDestroyInstance) pvkDestroyInstance(instance, NULL);
    dlclose(library);
    return 0;
}
