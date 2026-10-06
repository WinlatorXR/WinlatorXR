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

/* Native half of the bridge, loaded by Wine into the game's process.
 *
 * Wine 11 exports a D3D11 shared texture as a DxgkSharedResource NT handle,
 * whose wineserver object has no fd of its own; the D3DKMT resource behind it
 * does. d3dkmt_object_open is the request win32u itself uses to reach that
 * resource, so the fd comes from there. This ties the bridge to one wineserver
 * protocol (the installed Proton 11.0-2 build) -- deliberate, since PC VR pins
 * the Proton build.
 *
 * The fd is the driver's opaque fd, importable only by the same driver, so a
 * relay device on the same Turnip imports it and copies it into an
 * AHardwareBuffer, which the app process can import on any driver.
 */

#include <dlfcn.h>
#include <stddef.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#define VK_NO_PROTOTYPES
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>
#include <android/hardware_buffer.h>

#define WIN32_NO_STATUS
#include "windef.h"
#include "winbase.h"
#include "winuser.h"
#include "winternl.h"
#undef WIN32_NO_STATUS
#include "ntstatus.h"
#include "wine/server.h"
#include "wine/unixlib.h"

#include "wxr_bridge.h"
#include "direct.h"   /* app/src/main/cpp/xr: the app side of the socket */

/* The shipped Proton-11.0-2-arm64ec-1 numbers its requests three higher than the
 * branch headers (its win32u.so sends d3dkmt_object_open as 304), so build.sh
 * passes the number read from that binary. */
#ifndef WXR_REQ_D3DKMT_OBJECT_OPEN
#define WXR_REQ_D3DKMT_OBJECT_OPEN REQ_d3dkmt_object_open
#endif

#define MAX_LAYERS 2
#define MAX_SLOTS 64  /* never freed yet: swapchains recreated across session restarts use new slots */

static struct {
    void* library;
    VkInstance instance;
    VkPhysicalDevice gpu;
    VkDevice device;
    VkQueue queue;
    uint32_t family;
    VkCommandPool pool;
    int sock;                   /* connection to the app, or -1 */
} g;

static struct slot {
    VkImage src, dst[MAX_LAYERS];
    VkDeviceMemory src_memory, dst_memory[MAX_LAYERS];
    AHardwareBuffer* buffer[MAX_LAYERS];   /* one per eye */
    uint32_t width, height, layers;
    VkFormat format;
    VkCommandBuffer cmd[MAX_LAYERS];   /* SUBMIT's, re-recorded each frame */
    VkFence fence[MAX_LAYERS];         /* signalled once that layer's copy has finished */
    VkSemaphore done[MAX_LAYERS];      /* PRESENT's copy signals it; exported to the app as a sync fd */
} g_slots[MAX_SLOTS];
static uint32_t g_slot_count;

#define VK_FN(name) static PFN_##name p##name
VK_FN(vkGetInstanceProcAddr);
VK_FN(vkCreateInstance);
VK_FN(vkEnumeratePhysicalDevices);
VK_FN(vkGetPhysicalDeviceProperties);
VK_FN(vkGetPhysicalDeviceQueueFamilyProperties);
VK_FN(vkGetPhysicalDeviceMemoryProperties);
VK_FN(vkEnumerateDeviceExtensionProperties);
VK_FN(vkCreateDevice);
VK_FN(vkGetDeviceProcAddr);
VK_FN(vkGetDeviceQueue);
VK_FN(vkCreateCommandPool);
VK_FN(vkAllocateCommandBuffers);
VK_FN(vkFreeCommandBuffers);
VK_FN(vkBeginCommandBuffer);
VK_FN(vkEndCommandBuffer);
VK_FN(vkCmdPipelineBarrier);
VK_FN(vkCmdCopyImage);
VK_FN(vkCmdBlitImage);
VK_FN(vkResetFences);
VK_FN(vkResetCommandBuffer);
VK_FN(vkQueueSubmit);
VK_FN(vkCreateFence);
VK_FN(vkCreateSemaphore);
VK_FN(vkGetSemaphoreFdKHR);
VK_FN(vkWaitForFences);
VK_FN(vkDestroyFence);
VK_FN(vkCreateImage);
VK_FN(vkGetImageMemoryRequirements);
VK_FN(vkAllocateMemory);
VK_FN(vkBindImageMemory);
VK_FN(vkGetAndroidHardwareBufferPropertiesANDROID);

static int fail(char* message, const char* fmt, ...)
{
    va_list args;
    va_start(args, fmt);
    vsnprintf(message, 256, fmt, args);
    va_end(args);
    return -1;
}

static int app_send_buffer(uint32_t id, uint32_t layer);

/* ------------------------------------------------ NT handle -> host fd */

static int shared_handle_to_fd(HANDLE shared, char* message)
{
    NTSTATUS status;
    HANDLE object = 0;
    unsigned int global = 0, runtime_size = 0;
    int fd = -1;

    SERVER_START_REQ( d3dkmt_object_open )
    {
        __req.u.req.request_header.req = WXR_REQ_D3DKMT_OBJECT_OPEN;
        req->type = D3DKMT_RESOURCE;
        req->global = 0;
        req->handle = wine_server_obj_handle( shared );
        status = wine_server_call( req );
        object = wine_server_ptr_handle( reply->handle );
        global = reply->global;
        runtime_size = reply->runtime_size;
    }
    SERVER_END_REQ;
    if (status) {
        fail(message, "d3dkmt_object_open failed, status %#x", (unsigned)status);
        return -1;
    }

    status = wine_server_handle_to_fd( object, GENERIC_ALL, &fd, NULL );
    NtClose( object );
    if (status) {
        /* An all-zero reply here means the request number does not match the installed wineserver. */
        fail(message, "wine_server_handle_to_fd failed, status %#x (open gave handle %p global %#x runtime %u)",
             (unsigned)status, object, global, runtime_size);
        return -1;
    }
    return fd;
}

/* ------------------------------------------------------- relay device */

static uint32_t pick_memory_type(uint32_t bits, VkMemoryPropertyFlags wanted)
{
    VkPhysicalDeviceMemoryProperties props;
    uint32_t i;
    pvkGetPhysicalDeviceMemoryProperties(g.gpu, &props);
    for (i = 0; i < props.memoryTypeCount; i++)
        if ((bits & (1u << i)) && (props.memoryTypes[i].propertyFlags & wanted) == wanted) return i;
    for (i = 0; i < props.memoryTypeCount; i++)
        if (bits & (1u << i)) return i;
    return UINT32_MAX;
}

static NTSTATUS bridge_init(void* data)
{
    static const char* extensions[] = {
        "VK_KHR_external_memory_fd",
        "VK_ANDROID_external_memory_android_hardware_buffer",
        "VK_EXT_queue_family_foreign",
        "VK_KHR_external_semaphore_fd",
    };
    struct wxr_bridge_init_args* args = data;
    VkApplicationInfo app = { VK_STRUCTURE_TYPE_APPLICATION_INFO };
    VkInstanceCreateInfo instance_info = { VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO };
    VkPhysicalDeviceProperties props;
    VkQueueFamilyProperties families[8];
    VkExtensionProperties available[512];
    uint32_t count = 1, family_count = 8, available_count = 512, i, j;
    float priority = 1.0f;
    VkDeviceQueueCreateInfo queue_info = { VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO };
    VkDeviceCreateInfo device_info = { VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO };
    VkCommandPoolCreateInfo pool_info = { VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO };
    VkResult result;

    args->result = -1;
    if (args->abi != WXR_BRIDGE_ABI) {
        fail(args->message, "ABI %u, bridge is %u", args->abi, WXR_BRIDGE_ABI);
        return STATUS_SUCCESS;
    }
    if (!g.device) g.sock = -1;
    if (g.device) {
        args->result = 0;
        return STATUS_SUCCESS;
    }

    /* The same loader winevulkan resolves, so the same Turnip DXVK runs on. */
    g.library = dlopen("libvulkan.so.1", RTLD_NOW | RTLD_LOCAL);
    if (!g.library) g.library = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!g.library) {
        fail(args->message, "dlopen libvulkan: %s", dlerror());
        return STATUS_SUCCESS;
    }
    pvkGetInstanceProcAddr = (PFN_vkGetInstanceProcAddr)dlsym(g.library, "vkGetInstanceProcAddr");
    if (!pvkGetInstanceProcAddr) {
        fail(args->message, "vkGetInstanceProcAddr missing");
        return STATUS_SUCCESS;
    }

#define LOAD(inst, name) p##name = (PFN_##name)pvkGetInstanceProcAddr(inst, #name)
    LOAD(NULL, vkCreateInstance);
    app.apiVersion = VK_API_VERSION_1_1;
    app.pApplicationName = "wxr_bridge";
    instance_info.pApplicationInfo = &app;
    if ((result = pvkCreateInstance(&instance_info, NULL, &g.instance))) {
        fail(args->message, "vkCreateInstance: %d", result);
        return STATUS_SUCCESS;
    }
    LOAD(g.instance, vkEnumeratePhysicalDevices);
    LOAD(g.instance, vkGetPhysicalDeviceProperties);
    LOAD(g.instance, vkGetPhysicalDeviceQueueFamilyProperties);
    LOAD(g.instance, vkGetPhysicalDeviceMemoryProperties);
    LOAD(g.instance, vkEnumerateDeviceExtensionProperties);
    LOAD(g.instance, vkCreateDevice);
    LOAD(g.instance, vkGetDeviceProcAddr);

    pvkEnumeratePhysicalDevices(g.instance, &count, &g.gpu);
    if (!count) {
        fail(args->message, "no physical device");
        return STATUS_SUCCESS;
    }
    pvkGetPhysicalDeviceProperties(g.gpu, &props);

    pvkEnumerateDeviceExtensionProperties(g.gpu, NULL, &available_count, available);
    for (i = 0; i < sizeof(extensions) / sizeof(*extensions); i++) {
        for (j = 0; j < available_count; j++)
            if (!strcmp(available[j].extensionName, extensions[i])) break;
        if (j == available_count) {
            fail(args->message, "%s lacks %s", props.deviceName, extensions[i]);
            return STATUS_SUCCESS;
        }
    }

    pvkGetPhysicalDeviceQueueFamilyProperties(g.gpu, &family_count, families);
    for (g.family = 0; g.family < family_count; g.family++)
        if (families[g.family].queueFlags & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_TRANSFER_BIT)) break;

    queue_info.queueFamilyIndex = g.family;
    queue_info.queueCount = 1;
    queue_info.pQueuePriorities = &priority;
    device_info.queueCreateInfoCount = 1;
    device_info.pQueueCreateInfos = &queue_info;
    device_info.enabledExtensionCount = sizeof(extensions) / sizeof(*extensions);
    device_info.ppEnabledExtensionNames = extensions;
    if ((result = pvkCreateDevice(g.gpu, &device_info, NULL, &g.device))) {
        fail(args->message, "vkCreateDevice on %s: %d", props.deviceName, result);
        return STATUS_SUCCESS;
    }

#define LOADD(name) p##name = (PFN_##name)pvkGetDeviceProcAddr(g.device, #name)
    LOADD(vkGetDeviceQueue);
    LOADD(vkCreateCommandPool);
    LOADD(vkAllocateCommandBuffers);
    LOADD(vkFreeCommandBuffers);
    LOADD(vkBeginCommandBuffer);
    LOADD(vkEndCommandBuffer);
    LOADD(vkCmdPipelineBarrier);
    LOADD(vkCmdCopyImage);
    LOADD(vkCmdBlitImage);
    LOADD(vkResetFences);
    LOADD(vkResetCommandBuffer);
    LOADD(vkQueueSubmit);
    LOADD(vkCreateFence);
    LOADD(vkCreateSemaphore);
    LOADD(vkGetSemaphoreFdKHR);
    LOADD(vkWaitForFences);
    LOADD(vkDestroyFence);
    LOADD(vkCreateImage);
    LOADD(vkGetImageMemoryRequirements);
    LOADD(vkAllocateMemory);
    LOADD(vkBindImageMemory);
    LOADD(vkGetAndroidHardwareBufferPropertiesANDROID);

    pvkGetDeviceQueue(g.device, g.family, 0, &g.queue);
    pool_info.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pool_info.queueFamilyIndex = g.family;
    pvkCreateCommandPool(g.device, &pool_info, NULL, &g.pool);

    snprintf(args->message, 256, "%s (API %u.%u.%u)", props.deviceName,
             VK_VERSION_MAJOR(props.apiVersion), VK_VERSION_MINOR(props.apiVersion),
             VK_VERSION_PATCH(props.apiVersion));
    args->result = 0;
    return STATUS_SUCCESS;
}

/* ------------------------------------------------------------ import */

static NTSTATUS bridge_import(void* data)
{
    struct wxr_bridge_import_args* args = data;
    struct slot* s;
    int fd;
    off_t fd_size;
    uint32_t layer;
    VkResult result;
    VkExternalMemoryImageCreateInfo external = { VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO };
    VkImageFormatListCreateInfo format_list = { VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO };
    VkImageCreateInfo image = { VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO };
    VkMemoryRequirements requirements;
    VkMemoryDedicatedAllocateInfo dedicated = { VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO };
    VkImportMemoryFdInfoKHR import_fd = { VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR };
    VkMemoryAllocateInfo allocation = { VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO };
    AHardwareBuffer_Desc desc = { 0 };
    VkAndroidHardwareBufferFormatPropertiesANDROID format_props = { VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID };
    VkAndroidHardwareBufferPropertiesANDROID buffer_props = { VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID };
    VkImportAndroidHardwareBufferInfoANDROID import_buffer = { VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID };

    args->result = -1;
    if (!g.device) return fail(args->message, "bridge not initialised"), STATUS_SUCCESS;
    if (g_slot_count == MAX_SLOTS) return fail(args->message, "out of slots"), STATUS_SUCCESS;
    s = &g_slots[g_slot_count];
    s->width = args->width;
    s->height = args->height;
    s->layers = args->layers ? args->layers : 1;
    s->format = args->vk_format ? (VkFormat)args->vk_format : VK_FORMAT_R8G8B8A8_UNORM;

    fd = shared_handle_to_fd((HANDLE)(uintptr_t)args->nt_handle, args->message);
    if (fd < 0) return STATUS_SUCCESS;
    fd_size = lseek(fd, 0, SEEK_END);
    lseek(fd, 0, SEEK_SET);

    /* Matches what DXVK creates for a render target / shader resource of this format. */
    external.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;
    image.pNext = &external;
    if (args->flags & WXR_BRIDGE_IMPORT_MUTABLE_FORMAT) {
        /* DXVK 2.6.2's view formats for the typeless families. Turnip keeps UBWC for
         * a mutable image only when told these, so without them the layouts differ. */
        static const VkFormat rgba[] = { VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R8G8B8A8_SNORM, VK_FORMAT_R8G8B8A8_SRGB,
                                         VK_FORMAT_R8G8B8A8_UINT, VK_FORMAT_R8G8B8A8_SINT };
        static const VkFormat bgra[] = { VK_FORMAT_B8G8R8A8_UNORM, VK_FORMAT_B8G8R8A8_SRGB };
        int is_bgra = s->format == VK_FORMAT_B8G8R8A8_UNORM;
        format_list.viewFormatCount = is_bgra ? 2 : 5;
        format_list.pViewFormats = is_bgra ? bgra : rgba;
        external.pNext = &format_list;
        image.flags = VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT;
    }
    image.imageType = VK_IMAGE_TYPE_2D;
    image.format = s->format;
    image.extent.width = args->width;
    image.extent.height = args->height;
    image.extent.depth = 1;
    image.mipLevels = 1;
    image.arrayLayers = s->layers;
    image.samples = VK_SAMPLE_COUNT_1_BIT;
    image.tiling = VK_IMAGE_TILING_OPTIMAL;
    image.usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT |
                  VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    if (args->flags & WXR_BRIDGE_IMPORT_STORAGE) image.usage |= VK_IMAGE_USAGE_STORAGE_BIT;
    image.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if ((result = pvkCreateImage(g.device, &image, NULL, &s->src))) {
        close(fd);
        return fail(args->message, "source vkCreateImage: %d", result), STATUS_SUCCESS;
    }
    pvkGetImageMemoryRequirements(g.device, s->src, &requirements);

    dedicated.image = s->src;
    import_fd.pNext = &dedicated;
    import_fd.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;
    import_fd.fd = fd;
    allocation.pNext = &import_fd;
    /* An opaque-fd import must match the exporter's size, which the fd knows. */
    allocation.allocationSize = fd_size > 0 ? (VkDeviceSize)fd_size : requirements.size;
    allocation.memoryTypeIndex = pick_memory_type(requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if ((result = pvkAllocateMemory(g.device, &allocation, NULL, &s->src_memory))) {
        close(fd);
        return fail(args->message, "opaque-fd import: %d (fd size %lld, image needs %llu)", result,
                    (long long)fd_size, (unsigned long long)requirements.size), STATUS_SUCCESS;
    }
    pvkBindImageMemory(g.device, s->src, s->src_memory, 0);

    /* One single-layer RGBA8 buffer per layer: gralloc's layered buffers do not
     * import into Vulkan here, and the app wants one buffer per eye anyway.
     * RGBA8 always, since SUBMIT blits any other source format into it. */
    if (s->layers > MAX_LAYERS) return fail(args->message, "%u layers, bridge takes %d", s->layers, MAX_LAYERS), STATUS_SUCCESS;
    external.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    external.pNext = NULL;
    image.flags = 0;
    image.format = VK_FORMAT_R8G8B8A8_UNORM;
    image.arrayLayers = 1;
    image.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    for (layer = 0; layer < s->layers; layer++) {
        desc.width = args->width;
        desc.height = args->height;
        desc.layers = 1;
        desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        desc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT |
                     AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN;
        if (AHardwareBuffer_allocate(&desc, &s->buffer[layer]))
            return fail(args->message, "AHardwareBuffer_allocate failed"), STATUS_SUCCESS;

        buffer_props.pNext = &format_props;
        if ((result = pvkGetAndroidHardwareBufferPropertiesANDROID(g.device, s->buffer[layer], &buffer_props)))
            return fail(args->message, "AHardwareBuffer properties: %d", result), STATUS_SUCCESS;

        if ((result = pvkCreateImage(g.device, &image, NULL, &s->dst[layer])))
            return fail(args->message, "buffer vkCreateImage: %d", result), STATUS_SUCCESS;

        dedicated.image = s->dst[layer];
        import_buffer.pNext = &dedicated;
        import_buffer.buffer = s->buffer[layer];
        allocation.pNext = &import_buffer;
        allocation.allocationSize = buffer_props.allocationSize;
        allocation.memoryTypeIndex = pick_memory_type(buffer_props.memoryTypeBits, 0);
        if ((result = pvkAllocateMemory(g.device, &allocation, NULL, &s->dst_memory[layer])))
            return fail(args->message, "AHardwareBuffer import: %d", result), STATUS_SUCCESS;
        pvkBindImageMemory(g.device, s->dst[layer], s->dst_memory[layer], 0);
    }

    {
        VkCommandBufferAllocateInfo alloc = { VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO };
        VkFenceCreateInfo fence_info = { VK_STRUCTURE_TYPE_FENCE_CREATE_INFO };
        alloc.commandPool = g.pool;
        alloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        alloc.commandBufferCount = s->layers;
        pvkAllocateCommandBuffers(g.device, &alloc, s->cmd);
        fence_info.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        for (layer = 0; layer < s->layers; layer++) pvkCreateFence(g.device, &fence_info, NULL, &s->fence[layer]);
        for (layer = 0; layer < s->layers; layer++) {
            VkExportSemaphoreCreateInfo export_info = { VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO };
            VkSemaphoreCreateInfo semaphore_info = { VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO };
            export_info.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
            semaphore_info.pNext = &export_info;
            pvkCreateSemaphore(g.device, &semaphore_info, NULL, &s->done[layer]);
        }
    }

    args->id = g_slot_count++;
    if (g.sock >= 0)
        for (layer = 0; layer < s->layers; layer++)
            if (app_send_buffer(args->id, layer)) break;
    snprintf(args->message, 256, "fd %d (%lld bytes) imported, image needs %llu; buffer %ux%u x%u, format %d",
             fd, (long long)fd_size, (unsigned long long)requirements.size, args->width, args->height,
             s->layers, s->format);
    args->result = 0;
    return STATUS_SUCCESS;
}

/* ----------------------------------------------------- copy + readback */

static void barrier(VkCommandBuffer cmd, VkImage image, VkImageLayout from, VkImageLayout to,
                    uint32_t src_family, uint32_t dst_family)
{
    VkImageMemoryBarrier b = { VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER };
    b.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT;
    b.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    b.oldLayout = from;
    b.newLayout = to;
    b.srcQueueFamilyIndex = src_family;
    b.dstQueueFamilyIndex = dst_family;
    b.image = image;
    b.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    b.subresourceRange.levelCount = 1;
    b.subresourceRange.layerCount = VK_REMAINING_ARRAY_LAYERS;
    pvkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                          0, 0, NULL, 0, NULL, 1, &b);
}

static NTSTATUS bridge_copy_readback(void* data)
{
    struct wxr_bridge_readback_args* args = data;
    struct slot* s;
    VkCommandBufferAllocateInfo alloc = { VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO };
    VkCommandBufferBeginInfo begin = { VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO };
    VkFenceCreateInfo fence_info = { VK_STRUCTURE_TYPE_FENCE_CREATE_INFO };
    VkSubmitInfo submit = { VK_STRUCTURE_TYPE_SUBMIT_INFO };
    VkImageCopy region = { 0 };
    VkCommandBuffer cmd;
    VkFence fence;
    VkResult result;
    AHardwareBuffer_Desc desc;
    uint8_t* pixels = NULL;
    uint32_t x, y, step_x, step_y;

    args->result = -1;
    if (args->id >= g_slot_count) return fail(args->message, "bad slot %u", args->id), STATUS_SUCCESS;
    s = &g_slots[args->id];

    alloc.commandPool = g.pool;
    alloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    alloc.commandBufferCount = 1;
    pvkAllocateCommandBuffers(g.device, &alloc, &cmd);
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    pvkBeginCommandBuffer(cmd, &begin);

    /* The game's device owns the source; acquire it from outside this device.
     * Its layout is whatever DXVK left it in -- GENERAL is the safe read of it. */
    barrier(cmd, s->src, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            VK_QUEUE_FAMILY_EXTERNAL, g.family);
    barrier(cmd, s->dst[0], VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED);

    region.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    region.srcSubresource.layerCount = 1;
    region.dstSubresource = region.srcSubresource;
    region.extent.width = s->width;
    region.extent.height = s->height;
    region.extent.depth = 1;
    pvkCmdCopyImage(cmd, s->src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    s->dst[0], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);

    barrier(cmd, s->src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
            g.family, VK_QUEUE_FAMILY_EXTERNAL);
    barrier(cmd, s->dst[0], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
            g.family, VK_QUEUE_FAMILY_FOREIGN_EXT);
    pvkEndCommandBuffer(cmd);

    pvkCreateFence(g.device, &fence_info, NULL, &fence);
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    if ((result = pvkQueueSubmit(g.queue, 1, &submit, fence))) {
        pvkDestroyFence(g.device, fence, NULL);
        return fail(args->message, "vkQueueSubmit: %d", result), STATUS_SUCCESS;
    }
    result = pvkWaitForFences(g.device, 1, &fence, VK_TRUE, 2000000000ull);
    pvkDestroyFence(g.device, fence, NULL);
    pvkFreeCommandBuffers(g.device, g.pool, 1, &cmd);
    if (result) return fail(args->message, "copy fence: %d", result), STATUS_SUCCESS;

    /* A grid of samples: a uniform colour would pass even with the wrong tiling. */
    AHardwareBuffer_describe(s->buffer[0], &desc);
    if (AHardwareBuffer_lock(s->buffer[0], AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, NULL, (void**)&pixels) || !pixels)
        return fail(args->message, "AHardwareBuffer_lock failed"), STATUS_SUCCESS;
    step_x = s->width > 16 ? s->width / 16 : 1;
    step_y = s->height > 16 ? s->height / 16 : 1;
    args->checked = args->mismatches = 0;
    for (y = 0; y < s->height; y += step_y) {
        for (x = 0; x < s->width; x += step_x) {
            uint32_t got;
            memcpy(&got, pixels + ((size_t)y * desc.stride + x) * 4, 4);
            args->checked++;
            if (got != wxr_bridge_pattern(x, y) && !args->mismatches++) args->first_bad = got;
        }
    }
    AHardwareBuffer_unlock(s->buffer[0], NULL);

    snprintf(args->message, 256, "copied %ux%u, stride %u", s->width, s->height, desc.stride);
    args->result = 0;
    return STATUS_SUCCESS;
}

/* ------------------------------------------------------------ submit */

/* Records one layer's copy into the slot's AHardwareBuffer. */
static void record_copy(VkCommandBuffer cmd, struct slot* s, uint32_t layer)
{
    VkImageBlit region = { 0 };

    barrier(cmd, s->src, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            VK_QUEUE_FAMILY_EXTERNAL, g.family);
    barrier(cmd, s->dst[layer], VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED);

    /* A same-size blit rather than a copy, so a BGRA source lands as RGBA; the layer goes to its own buffer. */
    region.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    region.srcSubresource.baseArrayLayer = layer;
    region.srcSubresource.layerCount = 1;
    region.dstSubresource = region.srcSubresource;
    region.dstSubresource.baseArrayLayer = 0;
    region.srcOffsets[1].x = region.dstOffsets[1].x = (int32_t)s->width;
    region.srcOffsets[1].y = region.dstOffsets[1].y = (int32_t)s->height;
    region.srcOffsets[1].z = region.dstOffsets[1].z = 1;
    pvkCmdBlitImage(cmd, s->src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    s->dst[layer], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region, VK_FILTER_NEAREST);

    barrier(cmd, s->src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
            g.family, VK_QUEUE_FAMILY_EXTERNAL);
    barrier(cmd, s->dst[layer], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
            g.family, VK_QUEUE_FAMILY_FOREIGN_EXT);
}

/*
 * Per frame, after the caller's CPU wait: queue one layer's copy and return
 * without waiting. The slot's fence only blocks when the same swapchain image
 * comes round again before its last copy finished.
 */
static VkResult queue_copy(struct slot* s, uint32_t layer)
{
    VkCommandBufferBeginInfo begin = { VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO };
    VkSubmitInfo submit = { VK_STRUCTURE_TYPE_SUBMIT_INFO };
    VkCommandBuffer cmd = s->cmd[layer];

    pvkWaitForFences(g.device, 1, &s->fence[layer], VK_TRUE, 100000000ull);
    pvkResetFences(g.device, 1, &s->fence[layer]);
    pvkResetCommandBuffer(cmd, 0);
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    pvkBeginCommandBuffer(cmd, &begin);
    record_copy(cmd, s, layer);
    pvkEndCommandBuffer(cmd);

    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    return pvkQueueSubmit(g.queue, 1, &submit, s->fence[layer]);
}

static NTSTATUS bridge_submit(void* data)
{
    struct wxr_bridge_submit_args* args = data;

    args->result = VK_ERROR_UNKNOWN;
    if (args->id >= g_slot_count || args->layer >= g_slots[args->id].layers) return STATUS_SUCCESS;
    args->result = queue_copy(&g_slots[args->id], args->layer);
    return STATUS_SUCCESS;
}

/* ------------------------------------------------------ app connection */

static void app_disconnect(void)
{
    if (g.sock >= 0) close(g.sock);
    g.sock = -1;
}

static int app_send(const void* data, size_t size, const int* fds, int fd_count)
{
    char control[CMSG_SPACE(sizeof(int) * WXR_DIRECT_MAX_FDS)];
    struct iovec iov = { (void*)data, size };
    struct msghdr msg = { 0 };

    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    if (fd_count) {
        struct cmsghdr* cmsg;
        memset(control, 0, sizeof(control));
        msg.msg_control = control;
        msg.msg_controllen = CMSG_SPACE(sizeof(int) * fd_count);
        cmsg = CMSG_FIRSTHDR(&msg);
        cmsg->cmsg_level = SOL_SOCKET;
        cmsg->cmsg_type = SCM_RIGHTS;
        cmsg->cmsg_len = CMSG_LEN(sizeof(int) * fd_count);
        memcpy(CMSG_DATA(cmsg), fds, sizeof(int) * fd_count);
    }
    if (sendmsg(g.sock, &msg, MSG_NOSIGNAL) != (ssize_t)size) {
        app_disconnect();
        return -1;
    }
    return 0;
}

static int app_send_buffer(uint32_t id, uint32_t layer)
{
    struct wxr_direct_buffer msg = { { WXR_DIRECT_BUFFER, sizeof(msg) } };
    msg.slot = id;
    msg.layer = layer;
    msg.width = g_slots[id].width;
    msg.height = g_slots[id].height;
    if (app_send(&msg, sizeof(msg), NULL, 0)) return -1;
    if (AHardwareBuffer_sendHandleToUnixSocket(g_slots[id].buffer[layer], g.sock)) {
        app_disconnect();
        return -1;
    }
    return 0;
}

/* The app may start listening after the game does, so this is retried from PRESENT.
 * On connecting, every buffer imported so far is sent first. */
static void app_connect(void)
{
    struct wxr_direct_hello hello = { { WXR_DIRECT_HELLO, sizeof(hello) } };
    struct sockaddr_un addr = { 0 };
    socklen_t length;
    uint32_t id, layer;

    g.sock = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    if (g.sock < 0) return;
    addr.sun_family = AF_UNIX;
    memcpy(addr.sun_path + 1, WXR_DIRECT_SOCKET, strlen(WXR_DIRECT_SOCKET));  /* sun_path[0] = 0: abstract */
    length = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(WXR_DIRECT_SOCKET);
    if (connect(g.sock, (struct sockaddr*)&addr, length)) {
        app_disconnect();
        return;
    }
    hello.version = WXR_DIRECT_VERSION;
    hello.pid = getpid();
    if (app_send(&hello, sizeof(hello), NULL, 0)) return;
    for (id = 0; id < g_slot_count; id++)
        for (layer = 0; layer < g_slots[id].layers; layer++)
            if (app_send_buffer(id, layer)) return;
}

/* ------------------------------------------------------------ present */

/* PRESENT's command buffers, one per frame for all of its copies. A ring, so a
 * fence only blocks when the copies from PRESENT_RING frames ago are still running. */
#define PRESENT_RING 4
static struct {
    VkCommandBuffer cmd;
    VkFence fence;
} g_present[PRESENT_RING];
static uint32_t g_present_next;

/*
 * Queues the copies of count images in one submit, each signalling its
 * semaphore, and exports those as sync fds. All or nothing: on failure no fd
 * is left open.
 */
static VkResult copy_to_app(const uint32_t (*images)[2], uint32_t count, int* fds)
{
    VkCommandBufferBeginInfo begin = { VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO };
    VkSubmitInfo submit = { VK_STRUCTURE_TYPE_SUBMIT_INFO };
    VkSemaphoreGetFdInfoKHR get_fd = { VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR };
    VkSemaphore signals[WXR_DIRECT_MAX_FDS];
    uint32_t first[WXR_DIRECT_MAX_FDS];  /* the earlier entry naming the same image, or itself */
    uint32_t i, j, signal_count = 0;
    VkCommandBuffer cmd;
    VkFence fence;
    VkResult result;

    if (!g_present[0].cmd) {
        VkCommandBufferAllocateInfo alloc = { VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO };
        VkFenceCreateInfo fence_info = { VK_STRUCTURE_TYPE_FENCE_CREATE_INFO };
        alloc.commandPool = g.pool;
        alloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        alloc.commandBufferCount = 1;
        fence_info.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        for (i = 0; i < PRESENT_RING; i++) {
            if ((result = pvkAllocateCommandBuffers(g.device, &alloc, &g_present[i].cmd)) ||
                (result = pvkCreateFence(g.device, &fence_info, NULL, &g_present[i].fence))) {
                g_present[0].cmd = VK_NULL_HANDLE;
                return result;
            }
        }
    }
    cmd = g_present[g_present_next % PRESENT_RING].cmd;
    fence = g_present[g_present_next++ % PRESENT_RING].fence;

    pvkWaitForFences(g.device, 1, &fence, VK_TRUE, 100000000ull);
    pvkResetFences(g.device, 1, &fence);
    pvkResetCommandBuffer(cmd, 0);
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    pvkBeginCommandBuffer(cmd, &begin);
    for (i = 0; i < count; i++) {
        struct slot* s = &g_slots[images[i][0]];
        for (j = 0; j < i; j++)
            if (images[j][0] == images[i][0] && images[j][1] == images[i][1]) break;
        first[i] = j;
        if (j < i) continue;  /* a semaphore can only be signalled once per submit */
        record_copy(cmd, s, images[i][1]);
        signals[signal_count++] = s->done[images[i][1]];
    }
    pvkEndCommandBuffer(cmd);

    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    submit.signalSemaphoreCount = signal_count;
    submit.pSignalSemaphores = signals;
    if ((result = pvkQueueSubmit(g.queue, 1, &submit, fence))) return result;

    /* Every semaphore is exported even after a failure: that is what unsignals it for the next frame. */
    get_fd.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    for (i = 0; i < count; i++) {
        VkResult exported = VK_SUCCESS;
        fds[i] = -1;
        if (first[i] < i) {
            if (fds[first[i]] >= 0) fds[i] = dup(fds[first[i]]);
        } else {
            get_fd.semaphore = g_slots[images[i][0]].done[images[i][1]];
            exported = pvkGetSemaphoreFdKHR(g.device, &get_fd, &fds[i]);
        }
        if (exported || fds[i] < 0) {
            fds[i] = -1;
            if (!result) result = exported ? exported : VK_ERROR_UNKNOWN;
        }
    }
    if (result)
        for (i = 0; i < count; i++)
            if (fds[i] >= 0) close(fds[i]);
    return result;
}

/*
 * Per frame, after the caller's CPU wait: queue every view's and quad's copy
 * in one submit, each signalling a semaphore exported as a sync fd, and send
 * the frame to the app without waiting for any of it. The app waits on the
 * sync fds itself.
 */
static NTSTATUS bridge_present(void* data)
{
    struct wxr_bridge_present_args* args = data;
    struct wxr_direct_frame msg = { { WXR_DIRECT_FRAME, sizeof(msg) } };
    int fds[WXR_DIRECT_MAX_FDS];
    uint32_t images[WXR_DIRECT_MAX_FDS][2];  /* slot, layer */
    static uint32_t since_attempt;
    uint32_t v, q, fd_count = 0;

    args->result = 0;
    args->sent = 0;
    if (args->view_count > WXR_BRIDGE_MAX_VIEWS) args->view_count = WXR_BRIDGE_MAX_VIEWS;
    if (args->quad_count > WXR_BRIDGE_MAX_QUADS) args->quad_count = WXR_BRIDGE_MAX_QUADS;
    if (g.sock < 0 && (since_attempt++ % 300) == 0) app_connect();
    if (g.sock < 0) return STATUS_SUCCESS;  /* nobody to copy for */

    for (v = 0; v < args->view_count; v++) {
        const struct wxr_bridge_present_view* view = &args->views[v];
        if (view->id >= g_slot_count || view->layer >= g_slots[view->id].layers) {
            args->result = VK_ERROR_UNKNOWN;
            continue;
        }
        images[fd_count][0] = view->id;
        images[fd_count++][1] = view->layer;
        msg.views[msg.view_count].slot = view->id;
        msg.views[msg.view_count].layer = view->layer;
        memcpy(msg.views[msg.view_count].rect, view->rect, sizeof(view->rect));
        memcpy(msg.views[msg.view_count].orientation, view->orientation, sizeof(view->orientation));
        memcpy(msg.views[msg.view_count].position, view->position, sizeof(view->position));
        memcpy(msg.views[msg.view_count].fov, view->fov, sizeof(view->fov));
        msg.view_count++;
    }
    if (msg.view_count != args->view_count)  /* all eyes or none */
        fd_count = msg.view_count = 0;

    for (q = 0; q < args->quad_count; q++) {
        const struct wxr_bridge_present_quad* quad = &args->quads[q];
        struct wxr_direct_quad* out = &msg.quads[msg.quad_count];
        if (quad->id >= g_slot_count || quad->layer >= g_slots[quad->id].layers) {
            args->result = VK_ERROR_UNKNOWN;
            continue;
        }
        images[fd_count][0] = quad->id;
        images[fd_count++][1] = quad->layer;
        out->space = quad->space;
        out->slot = quad->id;
        out->layer = quad->layer;
        memcpy(out->rect, quad->rect, sizeof(quad->rect));
        memcpy(out->orientation, quad->orientation, sizeof(quad->orientation));
        memcpy(out->position, quad->position, sizeof(quad->position));
        memcpy(out->size, quad->size, sizeof(quad->size));
        out->eye_visibility = quad->eye_visibility;
        out->flags = quad->flags;
        msg.quad_count++;
    }

    if (fd_count) {
        VkResult result = copy_to_app(images, fd_count, fds);
        if (result) {
            args->result = result;
            return STATUS_SUCCESS;
        }
        msg.frame = args->frame;
        msg.display_time = args->display_time;
        msg.space = args->space;
        args->sent = !app_send(&msg, sizeof(msg), fds, fd_count);
    }
    for (v = 0; v < fd_count; v++) close(fds[v]);  /* the app has its own copies now */
    return STATUS_SUCCESS;
}

/* -------------------------------------------------------------- dump */

/* Writes one layer's buffer as raw RGBA8 rows, to check a real game
 * frame by eye before the app side exists. */
static NTSTATUS bridge_dump(void* data)
{
    struct wxr_bridge_dump_args* args = data;
    AHardwareBuffer_Desc desc;
    uint8_t* pixels = NULL;
    struct slot* s;
    uint32_t y;
    int file;

    args->result = -1;
    if (args->id >= g_slot_count) return fail(args->message, "bad slot %u", args->id), STATUS_SUCCESS;
    s = &g_slots[args->id];
    if (args->layer >= s->layers) return fail(args->message, "bad layer %u", args->layer), STATUS_SUCCESS;
    pvkWaitForFences(g.device, 1, &s->fence[args->layer], VK_TRUE, 1000000000ull);

    AHardwareBuffer_describe(s->buffer[args->layer], &desc);
    if (AHardwareBuffer_lock(s->buffer[args->layer], AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, NULL, (void**)&pixels) || !pixels)
        return fail(args->message, "AHardwareBuffer_lock failed"), STATUS_SUCCESS;
    file = open(args->path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (file < 0) {
        AHardwareBuffer_unlock(s->buffer[args->layer], NULL);
        return fail(args->message, "open %s failed", args->path), STATUS_SUCCESS;
    }
    for (y = 0; y < desc.height; y++)
        if (write(file, pixels + (size_t)y * desc.stride * 4, (size_t)desc.width * 4) < 0) break;
    close(file);
    AHardwareBuffer_unlock(s->buffer[args->layer], NULL);

    args->width = desc.width;
    args->height = desc.height;
    snprintf(args->message, 256, "wrote %ux%u to %s", desc.width, desc.height, args->path);
    args->result = 0;
    return STATUS_SUCCESS;
}

const unixlib_entry_t __wine_unix_call_funcs[] = {
    bridge_init,
    bridge_import,
    bridge_copy_readback,
    bridge_submit,
    bridge_dump,
    bridge_present,
};

/* 32-bit games reach the same calls through WoW64; the args structs are fixed-size, so no thunks. */
const unixlib_entry_t __wine_unix_call_wow64_funcs[] = {
    bridge_init,
    bridge_import,
    bridge_copy_readback,
    bridge_submit,
    bridge_dump,
    bridge_present,
};
