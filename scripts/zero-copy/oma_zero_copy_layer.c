/*
 * VK_LAYER_OMA_zero_copy: Vulkan swapchains of Wayland surfaces in buffers that
 * OmaLauncher's compositor allocates (oma_ahb_allocator_v1, oma-ahb-v1.xml) and
 * shows without copying them.
 *
 * Without it, Mesa's WSI on OmaLauncher (MESA_VK_WSI_DEBUG=sw) copies every frame
 * from the GPU into a wl_shm buffer, and the compositor copies it twice more.
 * Here the swapchain's images are the compositor's AHardwareBuffers, imported as
 * dma-bufs: the GPU renders straight into what the display shows.
 *
 * Only Wayland surfaces whose compositor offers the allocator are ours; anything
 * else (another compositor, no dma-buf import in the driver, OMA_ZERO_COPY_PASSTHROUGH=1)
 * goes to the driver as if the layer weren't there.
 *
 * Stage 1 synchronization: vkQueuePresentKHR waits on the
 * CPU for the frame's rendering before it commits the buffer, and an image is
 * acquired only once the compositor released it, so the acquire semaphore and
 * fence are signaled right away.
 *
 * Environment: OMA_ZERO_COPY=1 enables the layer (its manifest), OMA_ZERO_COPY_STATS=1
 * logs frames per second to stderr, OMA_ZERO_COPY_PASSTHROUGH=1 keeps it loaded but
 * hands everything to the driver (for comparing the two with the same stats).
 */
#define VK_USE_PLATFORM_WAYLAND_KHR
#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <vulkan/vk_layer.h>
#include <vulkan/vulkan.h>
#include <wayland-client.h>

#include "oma-ahb-v1-client-protocol.h"

#define EXPORT __attribute__((visibility("default")))
#define MAX_IMAGES 8
#define DRM_FORMAT_ABGR8888 0x34324241u /* bytes R, G, B, A */
#define DRM_FORMAT_XBGR8888 0x34324258u
#define DRM_FORMAT_ARGB8888 0x34325241u /* bytes B, G, R, A */
#define DRM_FORMAT_XRGB8888 0x34325258u
#define DRM_FORMAT_MOD_LINEAR 0ull
#define ARRAY_LEN(a) (sizeof(a) / sizeof((a)[0]))

static void say(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    char line[512];
    vsnprintf(line, sizeof(line), fmt, ap);
    va_end(ap);
    fprintf(stderr, "oma-zero-copy: %s\n", line);
}

static int64_t now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000 + ts.tv_nsec;
}

static int env_on(const char *name) {
    const char *v = getenv(name);
    return v && *v && strcmp(v, "0");
}

/* Dispatchable handles (instance and its physical devices, device and its queues) share a loader table: the key. */
static void *key_of(const void *dispatchable) { return *(void *const *)dispatchable; }

static pthread_mutex_t lists_lock = PTHREAD_MUTEX_INITIALIZER;

/* ---- instances ---- */

struct instance {
    void *key;
    VkInstance handle;
    PFN_vkGetInstanceProcAddr gipa;
    PFN_vkDestroyInstance DestroyInstance;
    PFN_vkCreateWaylandSurfaceKHR CreateWaylandSurfaceKHR;
    PFN_vkDestroySurfaceKHR DestroySurfaceKHR;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR GetPhysicalDeviceSurfaceCapabilitiesKHR;
    PFN_vkGetPhysicalDeviceSurfaceCapabilities2KHR GetPhysicalDeviceSurfaceCapabilities2KHR;
    PFN_vkGetPhysicalDeviceSurfaceFormatsKHR GetPhysicalDeviceSurfaceFormatsKHR;
    PFN_vkGetPhysicalDeviceSurfaceFormats2KHR GetPhysicalDeviceSurfaceFormats2KHR;
    PFN_vkGetPhysicalDeviceSurfacePresentModesKHR GetPhysicalDeviceSurfacePresentModesKHR;
    PFN_vkEnumerateDeviceExtensionProperties EnumerateDeviceExtensionProperties;
    PFN_vkGetPhysicalDeviceImageFormatProperties2 GetPhysicalDeviceImageFormatProperties2;
    PFN_vkGetPhysicalDeviceExternalSemaphoreProperties GetPhysicalDeviceExternalSemaphoreProperties;
    PFN_vkGetPhysicalDeviceExternalFenceProperties GetPhysicalDeviceExternalFenceProperties;
    PFN_vkGetPhysicalDeviceProperties GetPhysicalDeviceProperties;
    struct instance *next;
};

static struct instance *instances;

static struct instance *instance_of(const void *dispatchable) {
    void *key = key_of(dispatchable);
    pthread_mutex_lock(&lists_lock);
    struct instance *in = instances;
    while (in && in->key != key) in = in->next;
    pthread_mutex_unlock(&lists_lock);
    return in;
}

/* A core 1.1 entry point, or its KHR original on an older instance. */
static PFN_vkVoidFunction instance_fn(struct instance *in, const char *core, const char *khr) {
    PFN_vkVoidFunction f = in->gipa(in->handle, core);
    return f ? f : in->gipa(in->handle, khr);
}

/* ---- surfaces ---- */

struct swapchain;

struct wsurface {
    VkSurfaceKHR handle;
    struct instance *in;
    struct wl_display *display;
    struct wl_surface *surface;
    pthread_mutex_t lock; /* the event queue and the state of this surface's swapchains */
    int probed;
    int usable; /* the compositor offers the allocator and the device can import its buffers */
    struct wl_event_queue *queue;
    struct wl_display *display_wrapper; /* on queue */
    struct wl_surface *surface_wrapper; /* on queue: frame callbacks come there */
    struct oma_ahb_allocator_v1 *allocator;
    int rgba, bgra;             /* the compositor allocates R8G8B8A8 / B8G8R8A8 buffers (format events) */
    struct swapchain *building; /* the swapchain whose buffers are being allocated */
    struct wl_callback *frame;  /* FIFO: the last commit's frame callback, until it's done */
    struct wsurface *next;
};

static struct wsurface *surfaces;

static struct wsurface *surface_find(VkSurfaceKHR handle) {
    pthread_mutex_lock(&lists_lock);
    struct wsurface *ws = surfaces;
    while (ws && ws->handle != handle) ws = ws->next;
    pthread_mutex_unlock(&lists_lock);
    return ws;
}

/* ---- devices ---- */

struct device {
    void *key;
    VkDevice handle;
    VkPhysicalDevice phys;
    struct instance *in;
    PFN_vkGetDeviceProcAddr gdpa;
    PFN_vkDestroyDevice DestroyDevice;
    PFN_vkCreateSwapchainKHR CreateSwapchainKHR;
    PFN_vkDestroySwapchainKHR DestroySwapchainKHR;
    PFN_vkGetSwapchainImagesKHR GetSwapchainImagesKHR;
    PFN_vkAcquireNextImageKHR AcquireNextImageKHR;
    PFN_vkAcquireNextImage2KHR AcquireNextImage2KHR;
    PFN_vkQueuePresentKHR QueuePresentKHR;
    PFN_vkCreateImage CreateImage;
    PFN_vkDestroyImage DestroyImage;
    PFN_vkGetImageMemoryRequirements GetImageMemoryRequirements;
    PFN_vkAllocateMemory AllocateMemory;
    PFN_vkFreeMemory FreeMemory;
    PFN_vkBindImageMemory BindImageMemory;
    PFN_vkGetMemoryFdPropertiesKHR GetMemoryFdPropertiesKHR;
    PFN_vkCreateFence CreateFence;
    PFN_vkDestroyFence DestroyFence;
    PFN_vkWaitForFences WaitForFences;
    PFN_vkResetFences ResetFences;
    PFN_vkQueueSubmit QueueSubmit;
    PFN_vkGetDeviceQueue GetDeviceQueue;
    PFN_vkImportSemaphoreFdKHR ImportSemaphoreFdKHR;
    PFN_vkImportFenceFdKHR ImportFenceFdKHR;
    int dmabuf;          /* the import extensions are enabled */
    int sync_fd_sem;     /* an acquire semaphore can be signaled by importing sync fd -1 */
    int sync_fd_fence;   /* the same for an acquire fence */
    VkQueue queue;       /* else an empty submit on this one signals them */
    pthread_mutex_t queue_lock;
    VkFence present_fence; /* the app's rendering, waited for in vkQueuePresentKHR */
    pthread_mutex_t present_lock;
    VkPhysicalDeviceMemoryProperties memprops;
    struct device *next;
};

static struct device *devices;

static struct device *device_of(const void *dispatchable) {
    void *key = key_of(dispatchable);
    pthread_mutex_lock(&lists_lock);
    struct device *d = devices;
    while (d && d->key != key) d = d->next;
    pthread_mutex_unlock(&lists_lock);
    return d;
}

/* ---- swapchains ---- */

struct image {
    struct swapchain *sc;
    struct wl_buffer *buffer;
    int answered, failed;
    int fd;
    uint32_t offset, stride;
    uint64_t modifier;
    VkImage image;
    VkDeviceMemory memory;
    int busy;     /* committed, the compositor hasn't released it */
    int acquired; /* with the app */
};

struct swapchain {
    struct device *d;
    struct wsurface *ws;
    VkExtent2D extent;
    VkFormat format;
    VkPresentModeKHR mode;
    uint32_t count;
    struct image images[MAX_IMAGES];
    int retired;
    /* OMA_ZERO_COPY_STATS */
    uint32_t frames;
    int64_t t_report, wait_ns, gpu_ns;
    struct swapchain *next;
};

static struct swapchain *swapchains;
static int stats, passthrough;

static struct swapchain *swapchain_find(VkSwapchainKHR handle) {
    pthread_mutex_lock(&lists_lock);
    struct swapchain *sc = swapchains;
    while (sc && (VkSwapchainKHR)(uintptr_t)sc != handle) sc = sc->next;
    pthread_mutex_unlock(&lists_lock);
    return sc;
}

/* ---- Wayland ---- */

static void on_allocated(void *data, struct oma_ahb_allocator_v1 *a, struct wl_buffer *buffer, int32_t fd, uint32_t offset,
                         uint32_t stride, uint32_t mod_hi, uint32_t mod_lo) {
    struct wsurface *ws = data;
    struct swapchain *sc = ws->building;
    for (uint32_t i = 0; sc && i < sc->count; i++) {
        struct image *img = &sc->images[i];
        if (img->buffer != buffer) continue;
        img->fd = fd;
        img->offset = offset;
        img->stride = stride;
        img->modifier = (uint64_t)mod_hi << 32 | mod_lo;
        img->answered = 1;
        return;
    }
    close(fd);
}

static void on_failed(void *data, struct oma_ahb_allocator_v1 *a, struct wl_buffer *buffer) {
    struct wsurface *ws = data;
    struct swapchain *sc = ws->building;
    for (uint32_t i = 0; sc && i < sc->count; i++) {
        if (sc->images[i].buffer != buffer) continue;
        sc->images[i].answered = sc->images[i].failed = 1;
    }
}

static void on_format(void *data, struct oma_ahb_allocator_v1 *a, uint32_t format) {
    struct wsurface *ws = data;
    if (format == DRM_FORMAT_ABGR8888) ws->rgba = 1;
    if (format == DRM_FORMAT_ARGB8888) ws->bgra = 1;
}

static const struct oma_ahb_allocator_v1_listener allocator_listener = {on_allocated, on_failed, on_format};

static void registry_global(void *data, struct wl_registry *reg, uint32_t name, const char *iface, uint32_t version) {
    struct wsurface *ws = data;
    if (strcmp(iface, oma_ahb_allocator_v1_interface.name) || ws->allocator) return;
    ws->allocator = wl_registry_bind(reg, name, &oma_ahb_allocator_v1_interface, 1);
    oma_ahb_allocator_v1_add_listener(ws->allocator, &allocator_listener, ws);
}

static void registry_remove(void *data, struct wl_registry *reg, uint32_t name) {}

static const struct wl_registry_listener registry_listener = {registry_global, registry_remove};

static void buffer_release(void *data, struct wl_buffer *buffer) { ((struct image *)data)->busy = 0; }

static const struct wl_buffer_listener buffer_listener = {buffer_release};

static void frame_done(void *data, struct wl_callback *cb, uint32_t time) {
    struct wsurface *ws = data;
    if (ws->frame == cb) ws->frame = NULL;
    wl_callback_destroy(cb);
}

static const struct wl_callback_listener frame_listener = {frame_done};

/*
 * Reads and dispatches events of the surface's queue, waiting until [deadline] (ns, -1 for
 * ever) for some to come. 0 when it dispatched (or was interrupted), 1 on timeout, -1 when the
 * connection broke. Other threads may read the same display: prepare/read keeps it safe.
 */
static int wait_events(struct wsurface *ws, int64_t deadline) {
    if (wl_display_prepare_read_queue(ws->display, ws->queue) != 0)
        return wl_display_dispatch_queue_pending(ws->display, ws->queue) < 0 ? -1 : 0;
    wl_display_flush(ws->display);
    int timeout = -1;
    if (deadline >= 0) {
        int64_t left = deadline - now_ns();
        timeout = left <= 0 ? 0 : (int)((left + 999999) / 1000000);
    }
    struct pollfd pfd = {.fd = wl_display_get_fd(ws->display), .events = POLLIN};
    int r = poll(&pfd, 1, timeout);
    if (r <= 0) {
        wl_display_cancel_read(ws->display);
        if (r < 0) return errno == EINTR ? 0 : -1;
        return 1;
    }
    if (wl_display_read_events(ws->display) < 0) return -1;
    return wl_display_dispatch_queue_pending(ws->display, ws->queue) < 0 ? -1 : 0;
}

/* Whether a dma-buf of [format] can be imported as a LINEAR image for [usage]; yes if the driver can't say. */
static int import_ok(struct instance *in, VkPhysicalDevice pd, VkFormat format, VkImageUsageFlags usage) {
    if (!in->GetPhysicalDeviceImageFormatProperties2) return 1;
    VkPhysicalDeviceImageDrmFormatModifierInfoEXT mod = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_DRM_FORMAT_MODIFIER_INFO_EXT,
        .drmFormatModifier = DRM_FORMAT_MOD_LINEAR,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
    };
    VkPhysicalDeviceExternalImageFormatInfo ext = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_IMAGE_FORMAT_INFO,
        .pNext = &mod,
        .handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT,
    };
    VkPhysicalDeviceImageFormatInfo2 info = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2,
        .pNext = &ext,
        .format = format,
        .type = VK_IMAGE_TYPE_2D,
        .tiling = VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT,
        .usage = usage,
    };
    VkExternalImageFormatProperties eprops = {.sType = VK_STRUCTURE_TYPE_EXTERNAL_IMAGE_FORMAT_PROPERTIES};
    VkImageFormatProperties2 props = {.sType = VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2, .pNext = &eprops};
    if (in->GetPhysicalDeviceImageFormatProperties2(pd, &info, &props) != VK_SUCCESS) return 0;
    return (eprops.externalMemoryProperties.externalMemoryFeatures & VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT) != 0;
}

/* Vulkan formats and the DRM formats of their buffers, opaque and not; R8G8B8A8 first, as tested the most. */
static const struct {
    VkFormat vk;
    uint32_t drm, drm_opaque;
    int bgra;
} FORMATS[] = {
    {VK_FORMAT_R8G8B8A8_SRGB, DRM_FORMAT_ABGR8888, DRM_FORMAT_XBGR8888, 0},
    {VK_FORMAT_R8G8B8A8_UNORM, DRM_FORMAT_ABGR8888, DRM_FORMAT_XBGR8888, 0},
    {VK_FORMAT_B8G8R8A8_SRGB, DRM_FORMAT_ARGB8888, DRM_FORMAT_XRGB8888, 1},
    {VK_FORMAT_B8G8R8A8_UNORM, DRM_FORMAT_ARGB8888, DRM_FORMAT_XRGB8888, 1},
};

/* The FORMATS entry of [format] if the compositor and driver take it for [ws], else -1. */
static int format_index(struct wsurface *ws, VkPhysicalDevice pd, VkFormat format) {
    for (size_t i = 0; i < ARRAY_LEN(FORMATS); i++) {
        if (FORMATS[i].vk != format) continue;
        if (!(FORMATS[i].bgra ? ws->bgra : ws->rgba)) return -1;
        return import_ok(ws->in, pd, format, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) ? (int)i : -1;
    }
    return -1;
}

/*
 * Whether [ws] is ours: looks once for the allocator, on a queue of our own so the app's events
 * stay the app's, and checks that [pd] imports its buffers. Call with ws->lock held.
 */
static int probe_locked(struct wsurface *ws, VkPhysicalDevice pd) {
    if (ws->probed) return ws->usable;
    ws->probed = 1;
    if (passthrough) return 0;
    if (!import_ok(ws->in, pd, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)) {
        say("the driver can't import dma-bufs as images: it presents as usual");
        return 0;
    }
    ws->queue = wl_display_create_queue(ws->display);
    ws->display_wrapper = wl_proxy_create_wrapper(ws->display);
    wl_proxy_set_queue((struct wl_proxy *)ws->display_wrapper, ws->queue);
    struct wl_registry *reg = wl_display_get_registry(ws->display_wrapper);
    wl_registry_add_listener(reg, &registry_listener, ws);
    wl_display_roundtrip_queue(ws->display, ws->queue);
    wl_registry_destroy(reg);
    /* The format events follow the bind. */
    if (ws->allocator) wl_display_roundtrip_queue(ws->display, ws->queue);
    if (!ws->allocator) {
        say("the compositor has no oma_ahb_allocator_v1: the driver presents as usual");
        return 0;
    }
    if (!ws->rgba && !ws->bgra) {
        say("the compositor offers no format we know: the driver presents as usual");
        return 0;
    }
    ws->surface_wrapper = wl_proxy_create_wrapper(ws->surface);
    wl_proxy_set_queue((struct wl_proxy *)ws->surface_wrapper, ws->queue);
    ws->usable = 1;
    return 1;
}

/* The surface if it's ours, else NULL (the driver handles it). */
static struct wsurface *ours(VkSurfaceKHR surface, VkPhysicalDevice pd) {
    struct wsurface *ws = surface_find(surface);
    if (!ws) return NULL;
    pthread_mutex_lock(&ws->lock);
    int usable = probe_locked(ws, pd);
    pthread_mutex_unlock(&ws->lock);
    return usable ? ws : NULL;
}

static void surface_free(struct wsurface *ws) {
    /* A hidden window's last frame callback never comes: it would outlive its queue. */
    if (ws->frame) wl_callback_destroy(ws->frame);
    if (ws->allocator) oma_ahb_allocator_v1_destroy(ws->allocator);
    if (ws->surface_wrapper) wl_proxy_wrapper_destroy(ws->surface_wrapper);
    if (ws->display_wrapper) wl_proxy_wrapper_destroy(ws->display_wrapper);
    if (ws->queue) wl_event_queue_destroy(ws->queue);
    pthread_mutex_destroy(&ws->lock);
    free(ws);
}

/* ---- surface queries ---- */

static void fill_caps(struct instance *in, VkPhysicalDevice pd, VkSurfaceCapabilitiesKHR *caps) {
    /* The compositor gives a buffer back once the next one is on screen: with two, every other frame waits. */
    caps->minImageCount = 3;
    caps->maxImageCount = MAX_IMAGES;
    /* As on any Wayland surface: the swapchain decides the size. */
    caps->currentExtent = (VkExtent2D){UINT32_MAX, UINT32_MAX};
    caps->minImageExtent = (VkExtent2D){1, 1};
    caps->maxImageExtent = (VkExtent2D){16384, 16384};
    caps->maxImageArrayLayers = 1;
    caps->supportedTransforms = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    caps->currentTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    caps->supportedCompositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR | VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR;
    caps->supportedUsageFlags = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    const VkImageUsageFlags extra[] = {VK_IMAGE_USAGE_TRANSFER_SRC_BIT, VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                                       VK_IMAGE_USAGE_SAMPLED_BIT, VK_IMAGE_USAGE_STORAGE_BIT,
                                       VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT};
    for (size_t i = 0; i < ARRAY_LEN(extra); i++)
        if (import_ok(in, pd, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | extra[i]))
            caps->supportedUsageFlags |= extra[i];
}

static uint32_t our_formats(struct wsurface *ws, VkPhysicalDevice pd, VkSurfaceFormatKHR *out) {
    uint32_t n = 0;
    for (size_t i = 0; i < ARRAY_LEN(FORMATS); i++)
        if (format_index(ws, pd, FORMATS[i].vk) >= 0)
            out[n++] = (VkSurfaceFormatKHR){FORMATS[i].vk, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR};
    return n;
}

static VKAPI_ATTR VkResult VKAPI_CALL oma_GetPhysicalDeviceSurfaceCapabilitiesKHR(VkPhysicalDevice pd, VkSurfaceKHR surface,
                                                                                 VkSurfaceCapabilitiesKHR *caps) {
    struct instance *in = instance_of(pd);
    if (!ours(surface, pd)) return in->GetPhysicalDeviceSurfaceCapabilitiesKHR(pd, surface, caps);
    fill_caps(in, pd, caps);
    return VK_SUCCESS;
}

static VKAPI_ATTR VkResult VKAPI_CALL oma_GetPhysicalDeviceSurfaceCapabilities2KHR(VkPhysicalDevice pd,
                                                                                  const VkPhysicalDeviceSurfaceInfo2KHR *info,
                                                                                  VkSurfaceCapabilities2KHR *caps) {
    struct instance *in = instance_of(pd);
    if (!ours(info->surface, pd)) return in->GetPhysicalDeviceSurfaceCapabilities2KHR(pd, info, caps);
    /* The driver fills whatever extension structs are chained (its own surface underneath), we the basics. */
    if (in->GetPhysicalDeviceSurfaceCapabilities2KHR) in->GetPhysicalDeviceSurfaceCapabilities2KHR(pd, info, caps);
    fill_caps(in, pd, &caps->surfaceCapabilities);
    return VK_SUCCESS;
}

static VKAPI_ATTR VkResult VKAPI_CALL oma_GetPhysicalDeviceSurfaceFormatsKHR(VkPhysicalDevice pd, VkSurfaceKHR surface,
                                                                            uint32_t *count, VkSurfaceFormatKHR *formats) {
    struct instance *in = instance_of(pd);
    struct wsurface *ws = ours(surface, pd);
    if (!ws) return in->GetPhysicalDeviceSurfaceFormatsKHR(pd, surface, count, formats);
    VkSurfaceFormatKHR all[ARRAY_LEN(FORMATS)];
    uint32_t n = our_formats(ws, pd, all);
    if (!formats) {
        *count = n;
        return VK_SUCCESS;
    }
    uint32_t k = *count < n ? *count : n;
    memcpy(formats, all, k * sizeof(*formats));
    *count = k;
    return k < n ? VK_INCOMPLETE : VK_SUCCESS;
}

static VKAPI_ATTR VkResult VKAPI_CALL oma_GetPhysicalDeviceSurfaceFormats2KHR(VkPhysicalDevice pd,
                                                                             const VkPhysicalDeviceSurfaceInfo2KHR *info,
                                                                             uint32_t *count, VkSurfaceFormat2KHR *formats) {
    struct instance *in = instance_of(pd);
    struct wsurface *ws = ours(info->surface, pd);
    if (!ws) return in->GetPhysicalDeviceSurfaceFormats2KHR(pd, info, count, formats);
    VkSurfaceFormatKHR all[ARRAY_LEN(FORMATS)];
    uint32_t n = our_formats(ws, pd, all);
    if (!formats) {
        *count = n;
        return VK_SUCCESS;
    }
    uint32_t k = *count < n ? *count : n;
    for (uint32_t i = 0; i < k; i++) formats[i].surfaceFormat = all[i];
    *count = k;
    return k < n ? VK_INCOMPLETE : VK_SUCCESS;
}

static VKAPI_ATTR VkResult VKAPI_CALL oma_GetPhysicalDeviceSurfacePresentModesKHR(VkPhysicalDevice pd, VkSurfaceKHR surface,
                                                                                 uint32_t *count, VkPresentModeKHR *modes) {
    struct instance *in = instance_of(pd);
    if (!ours(surface, pd)) return in->GetPhysicalDeviceSurfacePresentModesKHR(pd, surface, count, modes);
    /* MAILBOX: commit at once, the compositor shows the latest at its next frame. */
    static const VkPresentModeKHR all[] = {VK_PRESENT_MODE_FIFO_KHR, VK_PRESENT_MODE_MAILBOX_KHR};
    if (!modes) {
        *count = ARRAY_LEN(all);
        return VK_SUCCESS;
    }
    uint32_t k = *count < ARRAY_LEN(all) ? *count : (uint32_t)ARRAY_LEN(all);
    memcpy(modes, all, k * sizeof(*modes));
    *count = k;
    return k < ARRAY_LEN(all) ? VK_INCOMPLETE : VK_SUCCESS;
}

static VKAPI_ATTR VkResult VKAPI_CALL oma_CreateWaylandSurfaceKHR(VkInstance instance, const VkWaylandSurfaceCreateInfoKHR *ci,
                                                                 const VkAllocationCallbacks *alloc, VkSurfaceKHR *out) {
    struct instance *in = instance_of(instance);
    /* The driver's surface stays underneath: it presents when the surface isn't ours. */
    VkResult r = in->CreateWaylandSurfaceKHR(instance, ci, alloc, out);
    if (r != VK_SUCCESS) return r;
    struct wsurface *ws = calloc(1, sizeof(*ws));
    ws->handle = *out;
    ws->in = in;
    ws->display = ci->display;
    ws->surface = ci->surface;
    pthread_mutex_init(&ws->lock, NULL);
    pthread_mutex_lock(&lists_lock);
    ws->next = surfaces;
    surfaces = ws;
    pthread_mutex_unlock(&lists_lock);
    return VK_SUCCESS;
}

static VKAPI_ATTR void VKAPI_CALL oma_DestroySurfaceKHR(VkInstance instance, VkSurfaceKHR surface,
                                                       const VkAllocationCallbacks *alloc) {
    struct instance *in = instance_of(instance);
    pthread_mutex_lock(&lists_lock);
    struct wsurface **p = &surfaces, *ws = NULL;
    while (*p && (*p)->handle != surface) p = &(*p)->next;
    if (*p) {
        ws = *p;
        *p = ws->next;
    }
    pthread_mutex_unlock(&lists_lock);
    if (ws) surface_free(ws);
    in->DestroySurfaceKHR(instance, surface, alloc);
}

/* ---- swapchains ---- */

static int pick_memory(struct device *d, uint32_t bits, int fd) {
    if (d->GetMemoryFdPropertiesKHR) {
        VkMemoryFdPropertiesKHR fp = {.sType = VK_STRUCTURE_TYPE_MEMORY_FD_PROPERTIES_KHR};
        if (d->GetMemoryFdPropertiesKHR(d->handle, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT, fd, &fp) == VK_SUCCESS)
            bits &= fp.memoryTypeBits;
    }
    for (uint32_t i = 0; i < d->memprops.memoryTypeCount; i++)
        if (bits & (1u << i)) return (int)i;
    return -1;
}

/* The VkImage of a buffer, bound to its imported dma-buf (whose fd it takes). */
static VkResult import_image(struct device *d, const VkSwapchainCreateInfoKHR *ci, struct image *img) {
    VkExternalMemoryImageCreateInfo ext = {.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO,
                                           .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT};
    VkSubresourceLayout plane = {.offset = img->offset, .rowPitch = img->stride};
    VkImageDrmFormatModifierExplicitCreateInfoEXT mod = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_DRM_FORMAT_MODIFIER_EXPLICIT_CREATE_INFO_EXT,
        .pNext = &ext,
        .drmFormatModifier = img->modifier,
        .drmFormatModifierPlaneCount = 1,
        .pPlaneLayouts = &plane,
    };
    VkImageCreateFlags flags = 0;
    void *chain = &mod;
    VkImageFormatListCreateInfo list;
    if (ci->flags & VK_SWAPCHAIN_CREATE_MUTABLE_FORMAT_BIT_KHR) {
        flags |= VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT | VK_IMAGE_CREATE_EXTENDED_USAGE_BIT;
        for (const VkBaseInStructure *s = ci->pNext; s; s = s->pNext) {
            if (s->sType != VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO) continue;
            list = *(const VkImageFormatListCreateInfo *)s;
            list.pNext = chain;
            chain = &list;
        }
    }
    VkImageCreateInfo ici = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .pNext = chain,
        .flags = flags,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = ci->imageFormat,
        .extent = {ci->imageExtent.width, ci->imageExtent.height, 1},
        .mipLevels = 1,
        .arrayLayers = ci->imageArrayLayers,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT,
        .usage = ci->imageUsage,
        .sharingMode = ci->imageSharingMode,
        .queueFamilyIndexCount = ci->queueFamilyIndexCount,
        .pQueueFamilyIndices = ci->pQueueFamilyIndices,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
    };
    VkResult r = d->CreateImage(d->handle, &ici, NULL, &img->image);
    if (r != VK_SUCCESS) return r;
    VkMemoryRequirements req;
    d->GetImageMemoryRequirements(d->handle, img->image, &req);
    int type = pick_memory(d, req.memoryTypeBits, img->fd);
    if (type < 0) return VK_ERROR_INVALID_EXTERNAL_HANDLE;
    VkMemoryDedicatedAllocateInfo dedicated = {.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO,
                                               .image = img->image};
    VkImportMemoryFdInfoKHR import = {.sType = VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR,
                                      .pNext = &dedicated,
                                      .handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT,
                                      .fd = img->fd};
    VkMemoryAllocateInfo mai = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                                .pNext = &import,
                                .allocationSize = req.size,
                                .memoryTypeIndex = (uint32_t)type};
    r = d->AllocateMemory(d->handle, &mai, NULL, &img->memory);
    if (r != VK_SUCCESS) return r;
    img->fd = -1; /* the driver owns it now */
    return d->BindImageMemory(d->handle, img->image, img->memory, 0);
}

static void swapchain_free(struct swapchain *sc) {
    struct device *d = sc->d;
    for (uint32_t i = 0; i < sc->count; i++) {
        struct image *img = &sc->images[i];
        if (img->image) d->DestroyImage(d->handle, img->image, NULL);
        if (img->memory) d->FreeMemory(d->handle, img->memory, NULL);
        if (img->fd >= 0) close(img->fd);
        if (img->buffer) wl_buffer_destroy(img->buffer);
    }
    free(sc);
}

static const char *format_name(VkFormat f) {
    switch (f) {
        case VK_FORMAT_R8G8B8A8_SRGB: return "R8G8B8A8_SRGB";
        case VK_FORMAT_R8G8B8A8_UNORM: return "R8G8B8A8_UNORM";
        case VK_FORMAT_B8G8R8A8_SRGB: return "B8G8R8A8_SRGB";
        case VK_FORMAT_B8G8R8A8_UNORM: return "B8G8R8A8_UNORM";
        default: return "?";
    }
}

static VKAPI_ATTR VkResult VKAPI_CALL oma_CreateSwapchainKHR(VkDevice device, const VkSwapchainCreateInfoKHR *ci,
                                                            const VkAllocationCallbacks *alloc, VkSwapchainKHR *out) {
    struct device *d = device_of(device);
    struct wsurface *ws = d->dmabuf ? ours(ci->surface, d->phys) : NULL;
    struct swapchain *old = swapchain_find(ci->oldSwapchain);
    int fmt = ws ? format_index(ws, d->phys, ci->imageFormat) : -1;
    if (ws && (fmt < 0 || ci->imageExtent.width == 0 || ci->imageExtent.height == 0)) {
        /* Some apps (zink) ask for a format without looking at the list: better copies than nothing. */
        say("swapchain format %d at %ux%u isn't one of ours: the driver presents it, with copies", ci->imageFormat,
            ci->imageExtent.width, ci->imageExtent.height);
        ws = NULL;
    }
    if (old) old->retired = 1;
    if (!ws) {
        VkSwapchainCreateInfoKHR copy = *ci;
        if (old) copy.oldSwapchain = VK_NULL_HANDLE; /* ours: the driver doesn't know it */
        return d->CreateSwapchainKHR(device, &copy, alloc, out);
    }

    struct swapchain *sc = calloc(1, sizeof(*sc));
    sc->d = d;
    sc->ws = ws;
    sc->extent = ci->imageExtent;
    sc->format = ci->imageFormat;
    sc->mode = ci->presentMode == VK_PRESENT_MODE_MAILBOX_KHR || ci->presentMode == VK_PRESENT_MODE_IMMEDIATE_KHR
                   ? VK_PRESENT_MODE_MAILBOX_KHR
                   : VK_PRESENT_MODE_FIFO_KHR;
    /* Mailbox: the compositor can hold three at once (the one on screen, the one going off it till its release
     * fence, the newest commit), so a fourth keeps the app drawing instead of waiting, as Mesa's own WSI does. */
    uint32_t least = sc->mode == VK_PRESENT_MODE_MAILBOX_KHR ? 4 : 3;
    sc->count = ci->minImageCount < least ? least : ci->minImageCount > MAX_IMAGES ? MAX_IMAGES : ci->minImageCount;
    uint32_t drm = ci->compositeAlpha == VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR ? FORMATS[fmt].drm_opaque : FORMATS[fmt].drm;

    pthread_mutex_lock(&ws->lock);
    ws->building = sc;
    for (uint32_t i = 0; i < sc->count; i++) {
        struct image *img = &sc->images[i];
        img->sc = sc;
        img->fd = -1;
        img->buffer = oma_ahb_allocator_v1_allocate(ws->allocator, (int32_t)sc->extent.width, (int32_t)sc->extent.height, drm, 0);
        wl_buffer_add_listener(img->buffer, &buffer_listener, img);
    }
    VkResult r = VK_SUCCESS;
    for (uint32_t i = 0; i < sc->count && r == VK_SUCCESS; i++)
        while (!sc->images[i].answered)
            if (wl_display_roundtrip_queue(ws->display, ws->queue) < 0) {
                r = VK_ERROR_SURFACE_LOST_KHR;
                break;
            }
    ws->building = NULL;
    pthread_mutex_unlock(&ws->lock);

    for (uint32_t i = 0; i < sc->count && r == VK_SUCCESS; i++) {
        struct image *img = &sc->images[i];
        if (img->failed) {
            say("the compositor could not allocate a %ux%u buffer", sc->extent.width, sc->extent.height);
            r = VK_ERROR_OUT_OF_DEVICE_MEMORY;
        } else if ((r = import_image(d, ci, img)) != VK_SUCCESS) {
            say("importing buffer %u (stride %u, modifier %#llx) failed: VkResult %d", i, img->stride,
                (unsigned long long)img->modifier, r);
        }
    }
    if (r != VK_SUCCESS) {
        swapchain_free(sc);
        return r;
    }
    sc->t_report = now_ns();
    pthread_mutex_lock(&lists_lock);
    sc->next = swapchains;
    swapchains = sc;
    pthread_mutex_unlock(&lists_lock);
    say("swapchain %ux%u %s, %u images, %s: in the compositor's buffers, no copies", sc->extent.width, sc->extent.height,
        format_name(sc->format), sc->count, sc->mode == VK_PRESENT_MODE_FIFO_KHR ? "fifo" : "mailbox");
    *out = (VkSwapchainKHR)(uintptr_t)sc;
    return VK_SUCCESS;
}

static VKAPI_ATTR void VKAPI_CALL oma_DestroySwapchainKHR(VkDevice device, VkSwapchainKHR handle,
                                                         const VkAllocationCallbacks *alloc) {
    struct device *d = device_of(device);
    pthread_mutex_lock(&lists_lock);
    struct swapchain **p = &swapchains, *sc = NULL;
    while (*p && (VkSwapchainKHR)(uintptr_t)*p != handle) p = &(*p)->next;
    if (*p) {
        sc = *p;
        *p = sc->next;
    }
    pthread_mutex_unlock(&lists_lock);
    if (!sc) {
        d->DestroySwapchainKHR(device, handle, alloc);
        return;
    }
    struct wsurface *ws = sc->ws;
    pthread_mutex_lock(&ws->lock);
    swapchain_free(sc);
    pthread_mutex_unlock(&ws->lock);
}

static VKAPI_ATTR VkResult VKAPI_CALL oma_GetSwapchainImagesKHR(VkDevice device, VkSwapchainKHR handle, uint32_t *count,
                                                               VkImage *images) {
    struct swapchain *sc = swapchain_find(handle);
    if (!sc) return device_of(device)->GetSwapchainImagesKHR(device, handle, count, images);
    if (!images) {
        *count = sc->count;
        return VK_SUCCESS;
    }
    uint32_t k = *count < sc->count ? *count : sc->count;
    for (uint32_t i = 0; i < k; i++) images[i] = sc->images[i].image;
    *count = k;
    return k < sc->count ? VK_INCOMPLETE : VK_SUCCESS;
}

/* Signals what the app waits on after an acquire: the image is free already (stage 1). */
static VkResult signal_now(struct device *d, VkSemaphore sem, VkFence fence) {
    if (sem && d->sync_fd_sem) {
        /* A sync fd of -1 is one that has signaled. */
        VkImportSemaphoreFdInfoKHR info = {.sType = VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR,
                                           .semaphore = sem,
                                           .flags = VK_SEMAPHORE_IMPORT_TEMPORARY_BIT,
                                           .handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT,
                                           .fd = -1};
        if (d->ImportSemaphoreFdKHR(d->handle, &info) == VK_SUCCESS) sem = VK_NULL_HANDLE;
    }
    if (fence && d->sync_fd_fence) {
        VkImportFenceFdInfoKHR info = {.sType = VK_STRUCTURE_TYPE_IMPORT_FENCE_FD_INFO_KHR,
                                       .fence = fence,
                                       .flags = VK_FENCE_IMPORT_TEMPORARY_BIT,
                                       .handleType = VK_EXTERNAL_FENCE_HANDLE_TYPE_SYNC_FD_BIT,
                                       .fd = -1};
        if (d->ImportFenceFdKHR(d->handle, &info) == VK_SUCCESS) fence = VK_NULL_HANDLE;
    }
    if (!sem && !fence) return VK_SUCCESS;
    VkSubmitInfo si = {.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .signalSemaphoreCount = sem ? 1 : 0, .pSignalSemaphores = &sem};
    pthread_mutex_lock(&d->queue_lock);
    VkResult r = d->QueueSubmit(d->queue, 1, &si, fence);
    pthread_mutex_unlock(&d->queue_lock);
    return r;
}

static VkResult acquire(VkDevice device, VkSwapchainKHR handle, uint64_t timeout, VkSemaphore sem, VkFence fence,
                        uint32_t *index) {
    struct device *d = device_of(device);
    struct swapchain *sc = swapchain_find(handle);
    if (!sc) return d->AcquireNextImageKHR(device, handle, timeout, sem, fence, index);
    struct wsurface *ws = sc->ws;
    int64_t deadline = timeout == UINT64_MAX ? -1 : now_ns() + (int64_t)(timeout > INT64_MAX / 2 ? INT64_MAX / 2 : timeout);
    pthread_mutex_lock(&ws->lock);
    VkResult r = VK_SUCCESS;
    int64_t t0 = now_ns();
    for (;;) {
        if (sc->retired) {
            r = VK_ERROR_OUT_OF_DATE_KHR;
            break;
        }
        /* Events already read (a release) count before we decide to wait. */
        wl_display_dispatch_queue_pending(ws->display, ws->queue);
        uint32_t i;
        for (i = 0; i < sc->count; i++)
            if (!sc->images[i].busy && !sc->images[i].acquired) break;
        if (i < sc->count) {
            sc->images[i].acquired = 1;
            *index = i;
            break;
        }
        if (timeout == 0) {
            r = VK_NOT_READY;
            break;
        }
        int w = wait_events(ws, deadline);
        if (w < 0) {
            r = VK_ERROR_SURFACE_LOST_KHR;
            break;
        }
        if (w > 0) {
            r = VK_TIMEOUT;
            break;
        }
    }
    sc->wait_ns += now_ns() - t0;
    if (stats && r == VK_TIMEOUT) say("acquire timed out: the compositor holds every image");
    pthread_mutex_unlock(&ws->lock);
    if (r == VK_SUCCESS) r = signal_now(d, sem, fence);
    return r;
}

static VKAPI_ATTR VkResult VKAPI_CALL oma_AcquireNextImageKHR(VkDevice device, VkSwapchainKHR handle, uint64_t timeout,
                                                             VkSemaphore sem, VkFence fence, uint32_t *index) {
    return acquire(device, handle, timeout, sem, fence, index);
}

static VKAPI_ATTR VkResult VKAPI_CALL oma_AcquireNextImage2KHR(VkDevice device, const VkAcquireNextImageInfoKHR *info,
                                                              uint32_t *index) {
    if (!swapchain_find(info->swapchain)) return device_of(device)->AcquireNextImage2KHR(device, info, index);
    return acquire(device, info->swapchain, info->timeout, info->semaphore, info->fence, index);
}

/* Hands an image the GPU finished to the compositor. */
static VkResult present_one(struct swapchain *sc, uint32_t index, int64_t gpu_ns) {
    struct wsurface *ws = sc->ws;
    pthread_mutex_lock(&ws->lock);
    sc->gpu_ns += gpu_ns;
    struct image *img = &sc->images[index];
    img->acquired = 0;
    if (sc->retired) {
        pthread_mutex_unlock(&ws->lock);
        return VK_ERROR_OUT_OF_DATE_KHR;
    }
    int64_t t0 = now_ns();
    if (sc->mode == VK_PRESENT_MODE_FIFO_KHR) {
        /* One frame per display frame: the last one is on screen first. A hidden window gets no frame
         * callbacks; then one frame a second, rather than none. */
        int64_t deadline = now_ns() + 1000000000LL;
        while (ws->frame)
            if (wait_events(ws, deadline) != 0) break;
        /* Timed out: that callback may never come; a late one mustn't count for the next frame. */
        if (ws->frame) wl_callback_destroy(ws->frame);
        ws->frame = wl_surface_frame(ws->surface_wrapper);
        wl_callback_add_listener(ws->frame, &frame_listener, ws);
    }
    sc->wait_ns += now_ns() - t0;
    wl_surface_attach(ws->surface_wrapper, img->buffer, 0, 0);
    wl_surface_damage_buffer(ws->surface_wrapper, 0, 0, INT32_MAX, INT32_MAX);
    wl_surface_commit(ws->surface_wrapper);
    img->busy = 1;
    wl_display_flush(ws->display);
    if (stats) {
        sc->frames++;
        int64_t t = now_ns();
        if (t - sc->t_report >= 2000000000LL) {
            uint32_t held = 0;
            for (uint32_t i = 0; i < sc->count; i++) held += sc->images[i].busy;
            say("%.1f fps, waiting for the compositor %.2f ms, for the GPU %.2f ms per frame, %u of %u images with it",
                sc->frames / ((t - sc->t_report) / 1e9), sc->wait_ns / 1e6 / sc->frames, sc->gpu_ns / 1e6 / sc->frames,
                held, sc->count);
            sc->frames = 0;
            sc->wait_ns = 0;
            sc->gpu_ns = 0;
            sc->t_report = t;
        }
    }
    pthread_mutex_unlock(&ws->lock);
    return VK_SUCCESS;
}

static VKAPI_ATTR VkResult VKAPI_CALL oma_QueuePresentKHR(VkQueue queue, const VkPresentInfoKHR *info) {
    struct device *d = device_of(queue);
    uint32_t mine = 0;
    for (uint32_t i = 0; i < info->swapchainCount; i++) mine += swapchain_find(info->pSwapchains[i]) != NULL;
    if (!mine) {
        if (!stats) return d->QueuePresentKHR(queue, info);
        /* OMA_ZERO_COPY_PASSTHROUGH with stats: the driver's path, counted the same way. */
        static uint32_t frames;
        static int64_t t_report;
        int64_t t = now_ns();
        if (!t_report) t_report = t;
        VkResult r = d->QueuePresentKHR(queue, info);
        if (++frames, t - t_report >= 2000000000LL) {
            say("driver's present: %.1f fps", frames / ((t - t_report) / 1e9));
            frames = 0;
            t_report = t;
        }
        return r;
    }
    if (mine != info->swapchainCount) {
        say("presenting to our swapchains and the driver's at once isn't supported");
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    /* Stage 1: the frame is done before the compositor gets it. */
    int64_t gpu_t0 = now_ns();
    if (info->waitSemaphoreCount) {
        VkPipelineStageFlags stages[16];
        uint32_t n = info->waitSemaphoreCount < 16 ? info->waitSemaphoreCount : 16;
        for (uint32_t i = 0; i < n; i++) stages[i] = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
        VkSubmitInfo si = {.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
                           .waitSemaphoreCount = n,
                           .pWaitSemaphores = info->pWaitSemaphores,
                           .pWaitDstStageMask = stages};
        pthread_mutex_lock(&d->present_lock);
        VkResult r = d->QueueSubmit(queue, 1, &si, d->present_fence);
        if (r == VK_SUCCESS) r = d->WaitForFences(d->handle, 1, &d->present_fence, VK_TRUE, UINT64_MAX);
        d->ResetFences(d->handle, 1, &d->present_fence);
        pthread_mutex_unlock(&d->present_lock);
        if (r != VK_SUCCESS) return r;
    }
    int64_t gpu_ns = now_ns() - gpu_t0;
    VkResult worst = VK_SUCCESS;
    for (uint32_t i = 0; i < info->swapchainCount; i++) {
        VkResult r = present_one(swapchain_find(info->pSwapchains[i]), info->pImageIndices[i], i ? 0 : gpu_ns);
        if (info->pResults) info->pResults[i] = r;
        if (r < 0 && worst >= 0) worst = r;
    }
    return worst;
}

/* ---- device ---- */

static const char *const DEVICE_EXTS[] = {
    /* Promoted to 1.1/1.2 but needed by name on a 1.0 device, for the three below. */
    VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME,
    VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME,
    VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME,
    VK_KHR_BIND_MEMORY_2_EXTENSION_NAME,
    VK_KHR_IMAGE_FORMAT_LIST_EXTENSION_NAME,
    VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME,
    "VK_KHR_maintenance1",
    VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME,
    VK_KHR_EXTERNAL_FENCE_EXTENSION_NAME,
    VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME,
    VK_EXT_EXTERNAL_MEMORY_DMA_BUF_EXTENSION_NAME,
    VK_EXT_IMAGE_DRM_FORMAT_MODIFIER_EXTENSION_NAME,
    VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME,
    VK_KHR_EXTERNAL_FENCE_FD_EXTENSION_NAME,
};

static int listed(const char *const *names, uint32_t n, const char *name) {
    for (uint32_t i = 0; i < n; i++)
        if (!strcmp(names[i], name)) return 1;
    return 0;
}

static VKAPI_ATTR VkResult VKAPI_CALL oma_CreateDevice(VkPhysicalDevice pd, const VkDeviceCreateInfo *ci,
                                                      const VkAllocationCallbacks *alloc, VkDevice *out) {
    struct instance *in = instance_of(pd);
    VkLayerDeviceCreateInfo *link = NULL, *loader_data = NULL;
    for (VkLayerDeviceCreateInfo *s = (VkLayerDeviceCreateInfo *)ci->pNext; s; s = (VkLayerDeviceCreateInfo *)s->pNext) {
        if (s->sType != VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO) continue;
        if (s->function == VK_LAYER_LINK_INFO && !link) link = s;
        if (s->function == VK_LOADER_DATA_CALLBACK && !loader_data) loader_data = s;
    }
    if (!link) return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkGetInstanceProcAddr gipa = link->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    PFN_vkGetDeviceProcAddr gdpa = link->u.pLayerInfo->pfnNextGetDeviceProcAddr;
    link->u.pLayerInfo = link->u.pLayerInfo->pNext;
    PFN_vkCreateDevice create = (PFN_vkCreateDevice)gipa(in->handle, "vkCreateDevice");

    /* The extensions importing buffers needs, added to the app's where the driver has them. */
    uint32_t navail = 0;
    in->EnumerateDeviceExtensionProperties(pd, NULL, &navail, NULL);
    VkExtensionProperties *avail = calloc(navail ? navail : 1, sizeof(*avail));
    in->EnumerateDeviceExtensionProperties(pd, NULL, &navail, avail);
    const char **names = calloc(ci->enabledExtensionCount + ARRAY_LEN(DEVICE_EXTS), sizeof(*names));
    uint32_t n = 0;
    for (uint32_t i = 0; i < ci->enabledExtensionCount; i++) names[n++] = ci->ppEnabledExtensionNames[i];
    int have[ARRAY_LEN(DEVICE_EXTS)] = {0};
    for (size_t e = 0; e < ARRAY_LEN(DEVICE_EXTS); e++) {
        for (uint32_t i = 0; i < navail && !have[e]; i++) have[e] = !strcmp(avail[i].extensionName, DEVICE_EXTS[e]);
        if (have[e] && !listed(names, n, DEVICE_EXTS[e])) names[n++] = DEVICE_EXTS[e];
    }
    free(avail);
    VkDeviceCreateInfo copy = *ci;
    copy.enabledExtensionCount = n;
    copy.ppEnabledExtensionNames = names;
    VkResult r = create(pd, &copy, alloc, out);
    free(names);
    if (r != VK_SUCCESS) return r;

    struct device *d = calloc(1, sizeof(*d));
    d->key = key_of(*out);
    d->handle = *out;
    d->phys = pd;
    d->in = in;
    d->gdpa = gdpa;
    pthread_mutex_init(&d->queue_lock, NULL);
    pthread_mutex_init(&d->present_lock, NULL);
#define DEV(name) d->name = (PFN_vk##name)gdpa(*out, "vk" #name)
    DEV(DestroyDevice);
    DEV(CreateSwapchainKHR);
    DEV(DestroySwapchainKHR);
    DEV(GetSwapchainImagesKHR);
    DEV(AcquireNextImageKHR);
    DEV(AcquireNextImage2KHR);
    DEV(QueuePresentKHR);
    DEV(CreateImage);
    DEV(DestroyImage);
    DEV(GetImageMemoryRequirements);
    DEV(AllocateMemory);
    DEV(FreeMemory);
    DEV(BindImageMemory);
    DEV(GetMemoryFdPropertiesKHR);
    DEV(CreateFence);
    DEV(DestroyFence);
    DEV(WaitForFences);
    DEV(ResetFences);
    DEV(QueueSubmit);
    DEV(GetDeviceQueue);
    DEV(ImportSemaphoreFdKHR);
    DEV(ImportFenceFdKHR);
#undef DEV
    PFN_vkGetPhysicalDeviceMemoryProperties memprops =
        (PFN_vkGetPhysicalDeviceMemoryProperties)in->gipa(in->handle, "vkGetPhysicalDeviceMemoryProperties");
    memprops(pd, &d->memprops);

    int fd_ext = 0, dmabuf_ext = 0, mod_ext = 0, sem_fd = 0, fence_fd = 0;
    for (size_t e = 0; e < ARRAY_LEN(DEVICE_EXTS); e++) {
        if (!have[e]) continue;
        const char *x = DEVICE_EXTS[e];
        fd_ext |= !strcmp(x, VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME);
        dmabuf_ext |= !strcmp(x, VK_EXT_EXTERNAL_MEMORY_DMA_BUF_EXTENSION_NAME);
        mod_ext |= !strcmp(x, VK_EXT_IMAGE_DRM_FORMAT_MODIFIER_EXTENSION_NAME);
        sem_fd |= !strcmp(x, VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME);
        fence_fd |= !strcmp(x, VK_KHR_EXTERNAL_FENCE_FD_EXTENSION_NAME);
    }
    d->dmabuf = fd_ext && dmabuf_ext && mod_ext && d->CreateImage;
    if (sem_fd && d->ImportSemaphoreFdKHR && in->GetPhysicalDeviceExternalSemaphoreProperties) {
        VkPhysicalDeviceExternalSemaphoreInfo si = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_SEMAPHORE_INFO,
                                                    .handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT};
        VkExternalSemaphoreProperties sp = {.sType = VK_STRUCTURE_TYPE_EXTERNAL_SEMAPHORE_PROPERTIES};
        in->GetPhysicalDeviceExternalSemaphoreProperties(pd, &si, &sp);
        d->sync_fd_sem = (sp.externalSemaphoreFeatures & VK_EXTERNAL_SEMAPHORE_FEATURE_IMPORTABLE_BIT) != 0;
    }
    if (fence_fd && d->ImportFenceFdKHR && in->GetPhysicalDeviceExternalFenceProperties) {
        VkPhysicalDeviceExternalFenceInfo fi = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_FENCE_INFO,
                                                .handleType = VK_EXTERNAL_FENCE_HANDLE_TYPE_SYNC_FD_BIT};
        VkExternalFenceProperties fp = {.sType = VK_STRUCTURE_TYPE_EXTERNAL_FENCE_PROPERTIES};
        in->GetPhysicalDeviceExternalFenceProperties(pd, &fi, &fp);
        d->sync_fd_fence = (fp.externalFenceFeatures & VK_EXTERNAL_FENCE_FEATURE_IMPORTABLE_BIT) != 0;
    }
    /* For signaling acquires without sync fds: a queue of ours, which the loader must know about. */
    d->GetDeviceQueue(*out, ci->pQueueCreateInfos[0].queueFamilyIndex, 0, &d->queue);
    if (loader_data) loader_data->u.pfnSetDeviceLoaderData(*out, d->queue);
    VkFenceCreateInfo fci = {.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    d->CreateFence(*out, &fci, NULL, &d->present_fence);
    if (stats || !d->dmabuf)
        say("device: dma-buf import %s, sync fd for acquire: semaphores %s, fences %s", d->dmabuf ? "yes" : "NO",
            d->sync_fd_sem ? "yes" : "no", d->sync_fd_fence ? "yes" : "no");

    pthread_mutex_lock(&lists_lock);
    d->next = devices;
    devices = d;
    pthread_mutex_unlock(&lists_lock);
    return VK_SUCCESS;
}

static VKAPI_ATTR void VKAPI_CALL oma_DestroyDevice(VkDevice device, const VkAllocationCallbacks *alloc) {
    void *key = key_of(device);
    pthread_mutex_lock(&lists_lock);
    struct device **p = &devices, *d = NULL;
    while (*p && (*p)->key != key) p = &(*p)->next;
    if (*p) {
        d = *p;
        *p = d->next;
    }
    pthread_mutex_unlock(&lists_lock);
    if (!d) return;
    d->DestroyFence(device, d->present_fence, NULL);
    d->DestroyDevice(device, alloc);
    pthread_mutex_destroy(&d->queue_lock);
    pthread_mutex_destroy(&d->present_lock);
    free(d);
}

/* ---- instance ---- */

/* Instance extensions the device extensions above depend on, on a 1.0 instance. */
static const char *const INSTANCE_EXTS[] = {
    VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME,
    VK_KHR_EXTERNAL_MEMORY_CAPABILITIES_EXTENSION_NAME,
    VK_KHR_EXTERNAL_SEMAPHORE_CAPABILITIES_EXTENSION_NAME,
    VK_KHR_EXTERNAL_FENCE_CAPABILITIES_EXTENSION_NAME,
};

static VKAPI_ATTR VkResult VKAPI_CALL oma_CreateInstance(const VkInstanceCreateInfo *ci, const VkAllocationCallbacks *alloc,
                                                        VkInstance *out) {
    VkLayerInstanceCreateInfo *link = NULL;
    for (VkLayerInstanceCreateInfo *s = (VkLayerInstanceCreateInfo *)ci->pNext; s && !link;
         s = (VkLayerInstanceCreateInfo *)s->pNext)
        if (s->sType == VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO && s->function == VK_LAYER_LINK_INFO) link = s;
    if (!link) return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkGetInstanceProcAddr gipa = link->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    link->u.pLayerInfo = link->u.pLayerInfo->pNext;
    PFN_vkCreateInstance create = (PFN_vkCreateInstance)gipa(VK_NULL_HANDLE, "vkCreateInstance");
    stats = env_on("OMA_ZERO_COPY_STATS");
    passthrough = env_on("OMA_ZERO_COPY_PASSTHROUGH");

    uint32_t api = ci->pApplicationInfo ? ci->pApplicationInfo->apiVersion : VK_API_VERSION_1_0;
    const char **names = calloc(ci->enabledExtensionCount + ARRAY_LEN(INSTANCE_EXTS), sizeof(*names));
    uint32_t n = 0;
    for (uint32_t i = 0; i < ci->enabledExtensionCount; i++) names[n++] = ci->ppEnabledExtensionNames[i];
    uint32_t added = 0;
    if (api < VK_API_VERSION_1_1 && !passthrough)
        for (size_t e = 0; e < ARRAY_LEN(INSTANCE_EXTS); e++)
            if (!listed(names, n, INSTANCE_EXTS[e])) names[n++] = INSTANCE_EXTS[e], added++;
    VkInstanceCreateInfo copy = *ci;
    copy.enabledExtensionCount = n;
    copy.ppEnabledExtensionNames = names;
    VkResult r = create(&copy, alloc, out);
    /* Every Mesa driver has them; one that doesn't still gets the app's own list. */
    if (r == VK_ERROR_EXTENSION_NOT_PRESENT && added) r = create(ci, alloc, out);
    free(names);
    if (r != VK_SUCCESS) return r;

    struct instance *in = calloc(1, sizeof(*in));
    in->key = key_of(*out);
    in->handle = *out;
    in->gipa = gipa;
#define INST(name) in->name = (PFN_vk##name)gipa(*out, "vk" #name)
    INST(DestroyInstance);
    INST(CreateWaylandSurfaceKHR);
    INST(DestroySurfaceKHR);
    INST(GetPhysicalDeviceSurfaceCapabilitiesKHR);
    INST(GetPhysicalDeviceSurfaceCapabilities2KHR);
    INST(GetPhysicalDeviceSurfaceFormatsKHR);
    INST(GetPhysicalDeviceSurfaceFormats2KHR);
    INST(GetPhysicalDeviceSurfacePresentModesKHR);
    INST(EnumerateDeviceExtensionProperties);
    INST(GetPhysicalDeviceProperties);
#undef INST
    in->GetPhysicalDeviceImageFormatProperties2 = (PFN_vkGetPhysicalDeviceImageFormatProperties2)instance_fn(
        in, "vkGetPhysicalDeviceImageFormatProperties2", "vkGetPhysicalDeviceImageFormatProperties2KHR");
    in->GetPhysicalDeviceExternalSemaphoreProperties = (PFN_vkGetPhysicalDeviceExternalSemaphoreProperties)instance_fn(
        in, "vkGetPhysicalDeviceExternalSemaphoreProperties", "vkGetPhysicalDeviceExternalSemaphorePropertiesKHR");
    in->GetPhysicalDeviceExternalFenceProperties = (PFN_vkGetPhysicalDeviceExternalFenceProperties)instance_fn(
        in, "vkGetPhysicalDeviceExternalFenceProperties", "vkGetPhysicalDeviceExternalFencePropertiesKHR");
    pthread_mutex_lock(&lists_lock);
    in->next = instances;
    instances = in;
    pthread_mutex_unlock(&lists_lock);
    return VK_SUCCESS;
}

static VKAPI_ATTR void VKAPI_CALL oma_DestroyInstance(VkInstance instance, const VkAllocationCallbacks *alloc) {
    void *key = key_of(instance);
    pthread_mutex_lock(&lists_lock);
    struct instance **p = &instances, *in = NULL;
    while (*p && (*p)->key != key) p = &(*p)->next;
    if (*p) {
        in = *p;
        *p = in->next;
    }
    /* Surfaces the app didn't destroy. */
    struct wsurface **s = &surfaces, *gone = NULL;
    while (in && *s) {
        if ((*s)->in != in) {
            s = &(*s)->next;
            continue;
        }
        struct wsurface *ws = *s;
        *s = ws->next;
        ws->next = gone;
        gone = ws;
    }
    pthread_mutex_unlock(&lists_lock);
    while (gone) {
        struct wsurface *ws = gone;
        gone = ws->next;
        surface_free(ws);
    }
    if (!in) return;
    in->DestroyInstance(instance, alloc);
    free(in);
}

/* ---- entry points ---- */

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL oma_GetDeviceProcAddr(VkDevice device, const char *name);

#define HOOK(name) {"vk" #name, (PFN_vkVoidFunction)oma_##name}

static const struct {
    const char *name;
    PFN_vkVoidFunction fn;
} DEVICE_HOOKS[] = {
    HOOK(GetDeviceProcAddr),   HOOK(DestroyDevice),          HOOK(CreateSwapchainKHR),      HOOK(DestroySwapchainKHR),
    HOOK(GetSwapchainImagesKHR), HOOK(AcquireNextImageKHR), HOOK(AcquireNextImage2KHR), HOOK(QueuePresentKHR),
};

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL oma_GetInstanceProcAddr(VkInstance instance, const char *name);

static const struct {
    const char *name;
    PFN_vkVoidFunction fn;
} INSTANCE_HOOKS[] = {
    HOOK(GetInstanceProcAddr),
    HOOK(CreateInstance),
    HOOK(DestroyInstance),
    HOOK(CreateDevice),
    HOOK(CreateWaylandSurfaceKHR),
    HOOK(DestroySurfaceKHR),
    HOOK(GetPhysicalDeviceSurfaceCapabilitiesKHR),
    HOOK(GetPhysicalDeviceSurfaceCapabilities2KHR),
    HOOK(GetPhysicalDeviceSurfaceFormatsKHR),
    HOOK(GetPhysicalDeviceSurfaceFormats2KHR),
    HOOK(GetPhysicalDeviceSurfacePresentModesKHR),
};

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL oma_GetDeviceProcAddr(VkDevice device, const char *name) {
    for (size_t i = 0; i < ARRAY_LEN(DEVICE_HOOKS); i++)
        if (!strcmp(name, DEVICE_HOOKS[i].name)) return DEVICE_HOOKS[i].fn;
    struct device *d = device ? device_of(device) : NULL;
    return d ? d->gdpa(device, name) : NULL;
}

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL oma_GetInstanceProcAddr(VkInstance instance, const char *name) {
    for (size_t i = 0; i < ARRAY_LEN(INSTANCE_HOOKS); i++)
        if (!strcmp(name, INSTANCE_HOOKS[i].name)) return INSTANCE_HOOKS[i].fn;
    if (!instance) return NULL;
    struct instance *in = instance_of(instance);
    if (!in) return NULL;
    /* Device functions through the instance go to ours too, but only where the driver has them. */
    for (size_t i = 0; i < ARRAY_LEN(DEVICE_HOOKS); i++)
        if (!strcmp(name, DEVICE_HOOKS[i].name)) return in->gipa(instance, name) ? DEVICE_HOOKS[i].fn : NULL;
    PFN_vkVoidFunction f = in->gipa(instance, name);
    /* Surface queries only exist where the driver has them (an instance without the extension). */
    return f;
}

EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkNegotiateLoaderLayerInterfaceVersion(VkNegotiateLayerInterface *v) {
    if (v->sType != LAYER_NEGOTIATE_INTERFACE_STRUCT || v->loaderLayerInterfaceVersion < 2) return VK_ERROR_INITIALIZATION_FAILED;
    v->loaderLayerInterfaceVersion = 2;
    v->pfnGetInstanceProcAddr = oma_GetInstanceProcAddr;
    v->pfnGetDeviceProcAddr = oma_GetDeviceProcAddr;
    v->pfnGetPhysicalDeviceProcAddr = NULL;
    return VK_SUCCESS;
}
