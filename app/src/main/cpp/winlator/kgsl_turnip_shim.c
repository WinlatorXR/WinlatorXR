/* ─────────── kgsl_turnip_shim.c ───────────
 * Quest 3 / Quest 3S ship a Horizon OS kernel whose KGSL driver appears to
 * have dropped (or SELinux-blocks) the legacy IOCTL_KGSL_GPUMEM_ALLOC_ID /
 * IOCTL_KGSL_GPUMEM_FREE_ID entry points that Turnip's kgsl backend
 * (tu_knl_kgsl.c) still issues. Modern KGSL exposes the same functionality
 * via IOCTL_KGSL_GPUOBJ_ALLOC / IOCTL_KGSL_GPUOBJ_FREE (+ GPUOBJ_INFO to
 * recover the gpuaddr, which GPUOBJ_ALLOC does not return directly).
 *
 * This preload hooks ioctl() and, only for fds pointing at /dev/kgsl-3d0,
 * first tries the legacy call unmodified (so this is a no-op on kernels
 * that still support it, e.g. Quest 2) and falls back to the GPUOBJ_*
 * translation on any failure - not just ENOTTY, since an EACCES/EPERM
 * from a per-ioctl SELinux xperm rule on the legacy command number can
 * still leave the GPUOBJ command number allowed.
 *
 * ioctl numbers/struct layouts below are transcribed from the upstream
 * msm_kgsl.h (KGSL_IOC_TYPE 0x09, GPUMEM_ALLOC_ID=0x34, FREE_ID=0x35,
 * GPUOBJ_ALLOC=0x45, GPUOBJ_FREE=0x46, GPUOBJ_INFO=0x47).
 *
 * Tested on-device (Quest 3, 2026-08-30, KGSL_SHIM_DEBUG=1): the legacy
 * ALLOC_ID/FREE_ID ioctls succeed with no fallback ever triggering, so the
 * "Quest 3 dropped the legacy KGSL ioctls" premise this was written for does
 * not hold on this kernel - the shim is a confirmed no-op pass-through here.
 * The static/noise corruption originally suspected to be this was actually
 * a regression in the adrenotools-Turnip_v26.3.0_r9 build; switching to
 * turnip25.1.0 fixed it outright. Left in place as defensive no-op code in
 * case a different kernel build genuinely lacks the legacy path.
 */

#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

static int g_debug_enabled = 0;

#define LOGI(...) do { if (g_debug_enabled) dprintf(STDOUT_FILENO, "[kgsl_turnip_shim] " __VA_ARGS__); } while (0)
#define LOGE(...) dprintf(STDERR_FILENO, "[kgsl_turnip_shim] " __VA_ARGS__)

__attribute__((constructor))
static void kgsl_turnip_shim_init(void)
{
    const char *dbg = getenv("KGSL_SHIM_DEBUG");
    g_debug_enabled = dbg && strchr("1yY", *dbg);
    LOGI("loaded\n");
}

#define KGSL_IOC_TYPE 0x09

struct kgsl_gpumem_alloc_id {
    unsigned int id;
    unsigned int flags;
    size_t size;
    size_t mmapsize;
    unsigned long gpuaddr;
    unsigned long __pad[2];
};

struct kgsl_gpumem_free_id {
    unsigned int id;
    unsigned int __pad;
};

struct kgsl_gpuobj_alloc {
    uint64_t size;
    uint64_t flags;
    uint64_t va_len;
    uint64_t mmapsize;
    unsigned int id;
    unsigned int metadata_len;
    uint64_t metadata;
};

struct kgsl_gpuobj_free {
    uint64_t flags;
    uint64_t priv;
    unsigned int id;
    unsigned int type;
    unsigned int len;
};

struct kgsl_gpuobj_info {
    uint64_t gpuaddr;
    uint64_t flags;
    uint64_t size;
    uint64_t va_len;
    uint64_t va_addr;
    unsigned int id;
};

#define IOCTL_KGSL_GPUMEM_ALLOC_ID _IOWR(KGSL_IOC_TYPE, 0x34, struct kgsl_gpumem_alloc_id)
#define IOCTL_KGSL_GPUMEM_FREE_ID  _IOWR(KGSL_IOC_TYPE, 0x35, struct kgsl_gpumem_free_id)
#define IOCTL_KGSL_GPUOBJ_ALLOC    _IOWR(KGSL_IOC_TYPE, 0x45, struct kgsl_gpuobj_alloc)
#define IOCTL_KGSL_GPUOBJ_FREE     _IOW(KGSL_IOC_TYPE, 0x46, struct kgsl_gpuobj_free)
#define IOCTL_KGSL_GPUOBJ_INFO     _IOWR(KGSL_IOC_TYPE, 0x47, struct kgsl_gpuobj_info)

typedef int (*ioctl_f)(int, int, ...);
static ioctl_f real_ioctl;

static inline int is_kgsl_fd(int fd)
{
    char linkbuf[64], path[128];
    snprintf(linkbuf, sizeof linkbuf, "/proc/self/fd/%d", fd);
    ssize_t n = readlink(linkbuf, path, sizeof path - 1);
    if (n <= 0) return 0;
    path[n] = 0;
    return !strncmp(path, "/dev/kgsl-3d0", 13);
}

/* Recovers gpuaddr (not returned by GPUOBJ_ALLOC) and fills in the legacy
 * struct's fields so Turnip sees the same shape it asked for. */
static int translate_alloc_id(int fd, struct kgsl_gpumem_alloc_id *legacy)
{
    struct kgsl_gpuobj_alloc obj_alloc;
    memset(&obj_alloc, 0, sizeof obj_alloc);
    obj_alloc.size = legacy->size;
    obj_alloc.flags = legacy->flags;
    obj_alloc.va_len = legacy->size;

    int r = real_ioctl(fd, IOCTL_KGSL_GPUOBJ_ALLOC, &obj_alloc);
    if (r != 0) return r;

    struct kgsl_gpuobj_info info;
    memset(&info, 0, sizeof info);
    info.id = obj_alloc.id;

    r = real_ioctl(fd, IOCTL_KGSL_GPUOBJ_INFO, &info);
    if (r != 0) {
        int saved_errno = errno;
        struct kgsl_gpuobj_free free_req;
        memset(&free_req, 0, sizeof free_req);
        free_req.id = obj_alloc.id;
        real_ioctl(fd, IOCTL_KGSL_GPUOBJ_FREE, &free_req);
        errno = saved_errno;
        return r;
    }

    legacy->id = obj_alloc.id;
    legacy->flags = (unsigned int)info.flags;
    legacy->size = (size_t)info.size;
    legacy->mmapsize = (size_t)obj_alloc.mmapsize;
    legacy->gpuaddr = (unsigned long)info.gpuaddr;
    legacy->__pad[0] = legacy->__pad[1] = 0;
    return 0;
}

static int translate_free_id(int fd, struct kgsl_gpumem_free_id *legacy)
{
    struct kgsl_gpuobj_free free_req;
    memset(&free_req, 0, sizeof free_req);
    free_req.id = legacy->id;
    return real_ioctl(fd, IOCTL_KGSL_GPUOBJ_FREE, &free_req);
}

int ioctl(int fd, int request, ...) __attribute__((visibility("default")));
int ioctl(int fd, int request, ...)
{
    if (!real_ioctl) real_ioctl = (ioctl_f)dlsym(RTLD_NEXT, "ioctl");

    va_list ap;
    va_start(ap, request);
    void *arg = va_arg(ap, void *);
    va_end(ap);

    if ((unsigned int)request != IOCTL_KGSL_GPUMEM_ALLOC_ID &&
        (unsigned int)request != IOCTL_KGSL_GPUMEM_FREE_ID)
        return real_ioctl(fd, request, arg);

    if (!is_kgsl_fd(fd))
        return real_ioctl(fd, request, arg);

    int r = real_ioctl(fd, request, arg);
    if (r == 0) return 0;

    int first_errno = errno;
    LOGI("legacy ioctl 0x%x on kgsl fd %d failed (errno=%d %s), trying GPUOBJ_* translation\n",
         (unsigned int)request, fd, first_errno, strerror(first_errno));

    int tr = ((unsigned int)request == IOCTL_KGSL_GPUMEM_ALLOC_ID)
        ? translate_alloc_id(fd, (struct kgsl_gpumem_alloc_id *)arg)
        : translate_free_id(fd, (struct kgsl_gpumem_free_id *)arg);

    if (tr == 0) {
        LOGI("translation succeeded for ioctl 0x%x\n", (unsigned int)request);
        return 0;
    }

    LOGE("translation failed too (errno=%d %s), returning original error\n", errno, strerror(errno));
    errno = first_errno;
    return r;
}
