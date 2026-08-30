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

/* ─────────── turnip_compat_shim.c ───────────
 * Some Turnip builds (compiled against a newer NDK / newer Android baseline)
 * reference libc/platform symbols that don't exist on older devices, and
 * fail to dlopen instead - confirmed on a Pico Neo 3 (Android 10 / API 29),
 * where hook_android_dlopen_ext logged, one undefined symbol at a time as
 * each got patched in turn:
 *   "cannot locate symbol \"call_once\" referenced by \".../vulkan.ad07xx.so\""
 *   "cannot locate symbol \"mtx_lock\" referenced by \".../vulkan.ad07xx.so\""
 *   "cannot locate symbol \"atrace_get_enabled_tags\" referenced by \".../vulkan.ad07xx.so\""
 * and silently fell back to the real system driver instead of loading
 * Turnip. This file collects every such symbol found so far into one shim,
 * grouped by source below - re-run the same DT_NEEDED patch + relaunch +
 * logcat loop if a new one surfaces; add it here rather than growing a
 * second shim library.
 *
 * This is loaded by adding an explicit DT_NEEDED on this .so (via patchelf
 * --add-needed, see AdrenotoolsManager.patchC11ThreadsIfNeeded) to the
 * Turnip driver's own .so, placed in the same directory - the isolated
 * linker namespace adrenotools loads the custom driver into resolves
 * DT_NEEDED entries against that directory (ADRENOTOOLS_DRIVER_PATH), so
 * this gets pulled in and resolves these symbols at relocation time.
 * (ADRENOTOOLS_HOOKS_PATH looked like the right place for this at first, but
 * it's unrelated - confirmed by inspecting the actual on-device
 * libadrenotools.so/libvulkan_wrapper.so: that path is only for adrenotools'
 * own libhook_impl.so/libmain_hook.so, used to redirect the driver's *own*
 * internal android_dlopen_ext calls to other vendor libs like
 * libvndksupport.so.)
 *
 * --- C11 <threads.h> (call_once, mtx_*, cnd_*, thrd_*, tss_*) ---
 * Bionic only exports these from libc.so starting API 30 (Android 11).
 * Every type, enum value, and function signature below is transcribed
 * verbatim from bionic's actual threads.h
 * (https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/include/threads.h)
 * so calling code compiled against a newer NDK's headers sees identical
 * ABI. cnd_t/thrd_t/tss_t/mtx_t/once_flag are all plain pthread_* aliases on
 * bionic, same as this repo's already-vendored portable C11 threads shim at
 * virglrenderer/src/gallium/include/c11/threads_posix.h (used there for a
 * desktop-Linux gallium build, not loaded on-device, and written against an
 * older pre-standardization draft API with different signatures - not
 * reused directly here for that reason).
 *
 * --- cutils/trace.h internal ATrace symbols (atrace_init, atrace_get_enabled_tags,
 *     atrace_setup, atrace_update_tags, atrace_set_tracing_enabled, atrace_*_body) ---
 * Not a version gap - these are libcutils' internal ATrace implementation, never part
 * of the public NDK/app-linkable surface on ANY Android version (apps only ever get the
 * public android/trace.h ATrace_* wrappers). Mesa's u_trace calls the internal ones
 * directly as a fast path, one at a time as each turned up missing across different
 * Turnip builds - all stubbed as no-ops (atrace_get_enabled_tags always reports "no trace
 * tags enabled", 0), which just makes the driver skip its systrace/perfetto
 * instrumentation entirely - same as tracing being off, which is the normal state anyway;
 * no effect on rendering. Full symbol list transcribed verbatim from the real header
 * (https://android.googlesource.com/platform/system/core/+/refs/heads/main/libcutils/include/cutils/trace.h)
 * so future Turnip builds referencing any of the rest resolve too, without another
 * discover-one-symbol-at-a-time round trip.
 */

#include <errno.h>
#include <pthread.h>
#include <sched.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <time.h>

typedef pthread_cond_t  cnd_t;
typedef pthread_t       thrd_t;
typedef pthread_key_t   tss_t;
typedef pthread_mutex_t mtx_t;
typedef int             once_flag;

typedef int (*thrd_start_t)(void *);
typedef void (*tss_dtor_t)(void *);

enum { mtx_plain = 0x1, mtx_recursive = 0x2, mtx_timed = 0x4 };
enum { thrd_success = 0, thrd_busy = 1, thrd_error = 2, thrd_nomem = 3, thrd_timedout = 4 };

#define EXPORT __attribute__((visibility("default")))

EXPORT void call_once(once_flag *flag, void (*func)(void))
{
    pthread_once((pthread_once_t *)flag, func);
}

EXPORT int cnd_broadcast(cnd_t *cond)
{
    return pthread_cond_broadcast(cond) == 0 ? thrd_success : thrd_error;
}

EXPORT void cnd_destroy(cnd_t *cond)
{
    pthread_cond_destroy(cond);
}

EXPORT int cnd_init(cnd_t *cond)
{
    return pthread_cond_init(cond, NULL) == 0 ? thrd_success : thrd_error;
}

EXPORT int cnd_signal(cnd_t *cond)
{
    return pthread_cond_signal(cond) == 0 ? thrd_success : thrd_error;
}

EXPORT int cnd_timedwait(cnd_t *cond, mtx_t *mtx, const struct timespec *ts)
{
    int rc = pthread_cond_timedwait(cond, mtx, ts);
    if (rc == 0) return thrd_success;
    return rc == ETIMEDOUT ? thrd_timedout : thrd_error;
}

EXPORT int cnd_wait(cnd_t *cond, mtx_t *mtx)
{
    return pthread_cond_wait(cond, mtx) == 0 ? thrd_success : thrd_error;
}

EXPORT void mtx_destroy(mtx_t *mtx)
{
    pthread_mutex_destroy(mtx);
}

EXPORT int mtx_init(mtx_t *mtx, int type)
{
    pthread_mutexattr_t attr;
    pthread_mutexattr_init(&attr);
    if (type & mtx_recursive) pthread_mutexattr_settype(&attr, PTHREAD_MUTEX_RECURSIVE);
    int rc = pthread_mutex_init(mtx, &attr);
    pthread_mutexattr_destroy(&attr);
    return rc == 0 ? thrd_success : thrd_error;
}

EXPORT int mtx_lock(mtx_t *mtx)
{
    return pthread_mutex_lock(mtx) == 0 ? thrd_success : thrd_error;
}

EXPORT int mtx_timedlock(mtx_t *mtx, const struct timespec *ts)
{
    int rc = pthread_mutex_timedlock(mtx, ts);
    if (rc == 0) return thrd_success;
    return rc == ETIMEDOUT ? thrd_timedout : thrd_error;
}

EXPORT int mtx_trylock(mtx_t *mtx)
{
    return pthread_mutex_trylock(mtx) == 0 ? thrd_success : thrd_busy;
}

EXPORT int mtx_unlock(mtx_t *mtx)
{
    return pthread_mutex_unlock(mtx) == 0 ? thrd_success : thrd_error;
}

struct impl_thrd_param {
    thrd_start_t func;
    void *arg;
};

static void *impl_thrd_routine(void *p)
{
    struct impl_thrd_param pack = *(struct impl_thrd_param *)p;
    free(p);
    return (void *)(intptr_t)pack.func(pack.arg);
}

EXPORT int thrd_create(thrd_t *thr, thrd_start_t func, void *arg)
{
    struct impl_thrd_param *pack = malloc(sizeof(*pack));
    if (!pack) return thrd_nomem;
    pack->func = func;
    pack->arg = arg;
    if (pthread_create(thr, NULL, impl_thrd_routine, pack) != 0) {
        free(pack);
        return thrd_error;
    }
    return thrd_success;
}

EXPORT thrd_t thrd_current(void)
{
    return pthread_self();
}

EXPORT int thrd_detach(thrd_t thr)
{
    return pthread_detach(thr) == 0 ? thrd_success : thrd_error;
}

EXPORT int thrd_equal(thrd_t a, thrd_t b)
{
    return pthread_equal(a, b);
}

EXPORT __attribute__((noreturn)) void thrd_exit(int res)
{
    pthread_exit((void *)(intptr_t)res);
    abort(); /* pthread_exit is noreturn; silence -Wreturn-type */
}

EXPORT int thrd_join(thrd_t thr, int *res)
{
    void *code;
    if (pthread_join(thr, &code) != 0) return thrd_error;
    if (res) *res = (int)(intptr_t)code;
    return thrd_success;
}

EXPORT int thrd_sleep(const struct timespec *duration, struct timespec *remaining)
{
    return nanosleep(duration, remaining);
}

EXPORT void thrd_yield(void)
{
    sched_yield();
}

EXPORT int tss_create(tss_t *key, tss_dtor_t dtor)
{
    return pthread_key_create(key, dtor) == 0 ? thrd_success : thrd_error;
}

EXPORT void tss_delete(tss_t key)
{
    pthread_key_delete(key);
}

EXPORT void *tss_get(tss_t key)
{
    return pthread_getspecific(key);
}

EXPORT int tss_set(tss_t key, void *val)
{
    return pthread_setspecific(key, val) == 0 ? thrd_success : thrd_error;
}

/* --- cutils/trace.h internal ATrace symbols (see file header) --- */
EXPORT uint64_t atrace_get_enabled_tags(void)
{
    return 0;
}

EXPORT void atrace_init(void) {}
EXPORT void atrace_setup(void) {}
EXPORT void atrace_update_tags(void) {}
EXPORT void atrace_set_tracing_enabled(bool enabled) { (void)enabled; }

EXPORT void atrace_begin_body(const char *name) { (void)name; }
EXPORT void atrace_end_body(void) {}
EXPORT void atrace_async_begin_body(const char *name, int32_t cookie) { (void)name; (void)cookie; }
EXPORT void atrace_async_end_body(const char *name, int32_t cookie) { (void)name; (void)cookie; }
EXPORT void atrace_async_for_track_begin_body(const char *track_name, const char *name, int32_t cookie) { (void)track_name; (void)name; (void)cookie; }
EXPORT void atrace_async_for_track_end_body(const char *track_name, int32_t cookie) { (void)track_name; (void)cookie; }
EXPORT void atrace_instant_body(const char *name) { (void)name; }
EXPORT void atrace_instant_for_track_body(const char *track_name, const char *name) { (void)track_name; (void)name; }
EXPORT void atrace_int_body(const char *name, int32_t value) { (void)name; (void)value; }
EXPORT void atrace_int64_body(const char *name, int64_t value) { (void)name; (void)value; }
