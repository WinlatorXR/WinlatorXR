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

/* ─────────── map_merge_shim.c ───────────
 * A Linux process may hold about 65,530 memory mappings (vm.max_map_count),
 * and the limit cannot be raised without root. Windows hands out virtual
 * memory at 64 KB-aligned addresses, so a game that makes tens of thousands
 * of small VirtualAlloc calls leaves a hole after every block, the kernel
 * can never merge neighbours, and each block costs one mapping. Half-Life:
 * Alyx reaches the limit while loading a level and then hangs or segfaults
 * (measured on a Pico 4 Ultra, 2026-10-07: 57,000 of 60,000 mappings were
 * such blocks, almost all between 4 and 64 KB).
 *
 * This preload hooks mmap/munmap/mprotect in the Wine processes. When Wine
 * places an anonymous view at a 64 KB-aligned address, the mapping is
 * extended to the end of its last 64 KB slot. Nothing else can be put in
 * that tail on the Windows side, and it uses no memory until touched, but
 * consecutive blocks now touch each other and the kernel merges them.
 *
 * The extension is only tried with MAP_FIXED_NOREPLACE, which fails rather
 * than overwrite anything already in the tail. Each extended view is
 * remembered by where it ends, and later calls that end at the same address
 * are extended the same way, so the tail always follows the view's last page.
 *
 * Set WXR_MAP_MERGE=0 to turn it off.
 */

#define _GNU_SOURCE
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef MAP_FIXED_NOREPLACE
#define MAP_FIXED_NOREPLACE 0x100000
#endif

#define SLOT_SHIFT 16
#define SLOT_SIZE  ((uintptr_t)1 << SLOT_SHIFT)
#define SLOT_MASK  (SLOT_SIZE - 1)
#define PAGE_SHIFT_4K 12
#define PAGE_MASK_4K (((uintptr_t)1 << PAGE_SHIFT_4K) - 1)
#define ADDR_LIMIT ((uintptr_t)1 << 40)
#define TABLE_SIZE (ADDR_LIMIT >> SLOT_SHIFT)

/* One byte per 64 KB slot: the number of 4 KB pages an extended view uses in
 * its last slot, or 0 when no view ending in that slot was extended */
static _Atomic(uint8_t *) g_tails;
static int g_enabled;

__attribute__((constructor))
static void map_merge_init(void) {
    const char *env = getenv("WXR_MAP_MERGE");
    g_enabled = !(env && env[0] == '0') && sysconf(_SC_PAGESIZE) == 4096;
}

static void *real_mmap(void *addr, size_t len, int prot, int flags, int fd, off_t off) {
    return (void *)syscall(__NR_mmap, addr, len, prot, flags, fd, off);
}

static uint8_t *tails(int create) {
    uint8_t *table = atomic_load(&g_tails);
    if (table || !create) return table;
    uint8_t *fresh = real_mmap(NULL, TABLE_SIZE, PROT_READ | PROT_WRITE,
                               MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE, -1, 0);
    if (fresh == MAP_FAILED) return NULL;
    if (atomic_compare_exchange_strong(&g_tails, &table, fresh)) return fresh;
    syscall(__NR_munmap, fresh, TABLE_SIZE);
    return table;
}

/* The length of the tail after a view that ends at `end`, or 0 if that view was not extended */
static size_t tail_after(uintptr_t end) {
    uint8_t *table = tails(0);
    if (!table || !(end & SLOT_MASK) || end >= ADDR_LIMIT) return 0;
    if (table[end >> SLOT_SHIFT] != (end & SLOT_MASK) >> PAGE_SHIFT_4K) return 0;
    return SLOT_SIZE - (end & SLOT_MASK);
}

static void forget(uintptr_t end) {
    uint8_t *table = tails(0);
    if (table && end < ADDR_LIMIT) table[end >> SLOT_SHIFT] = 0;
}

/* Forgets every extended view that ends inside (start, end): its tail is being replaced or unmapped */
static void forget_inside(uintptr_t start, uintptr_t end) {
    uint8_t *table = tails(0);
    if (!table || start >= ADDR_LIMIT) return;
    if (end > ADDR_LIMIT) end = ADDR_LIMIT;
    for (uintptr_t slot = start >> SLOT_SHIFT; slot <= (end - 1) >> SLOT_SHIFT; slot++) {
        uintptr_t tracked = (slot << SLOT_SHIFT) | ((uintptr_t)table[slot] << PAGE_SHIFT_4K);
        if (table[slot] && tracked > start && tracked < end) table[slot] = 0;
    }
}

static void *hooked_mmap(void *addr, size_t len, int prot, int flags, int fd, off_t off) {
    uintptr_t start = (uintptr_t)addr;
    uintptr_t end = start + ((len + PAGE_MASK_4K) & ~PAGE_MASK_4K);
    int anon = (flags & MAP_ANONYMOUS) && (flags & MAP_PRIVATE);

    if (!g_enabled || !start || !len || end <= start ||
        !(flags & (MAP_FIXED | MAP_FIXED_NOREPLACE)))
        return real_mmap(addr, len, prot, flags, fd, off);

    if (flags & MAP_FIXED) {
        size_t tail = tail_after(end);
        forget_inside(start, end);
        if (tail && anon) return real_mmap(addr, (end - start) + tail, prot, flags, fd, off);
        void *ret = real_mmap(addr, len, prot, flags, fd, off);
        if (tail && ret != MAP_FAILED) {
            /* A file now covers the end of the view, so the tail can no longer merge with it */
            syscall(__NR_munmap, end, tail);
            forget(end);
        }
        return ret;
    }

    if (anon && !(start & SLOT_MASK) && (end & SLOT_MASK) && end < ADDR_LIMIT && tails(1)) {
        size_t tail = SLOT_SIZE - (end & SLOT_MASK);
        void *ret = real_mmap(addr, (end - start) + tail, prot, flags, fd, off);
        if (ret == addr) {
            tails(0)[end >> SLOT_SHIFT] = (uint8_t)((end & SLOT_MASK) >> PAGE_SHIFT_4K);
            return ret;
        }
        /* A kernel without MAP_FIXED_NOREPLACE treats the address as a hint */
        if (ret != MAP_FAILED) syscall(__NR_munmap, ret, (end - start) + tail);
    }
    void *ret = real_mmap(addr, len, prot, flags, fd, off);
    if (ret == addr) forget(end);
    return ret;
}

void *mmap(void *addr, size_t len, int prot, int flags, int fd, off_t off) {
    return hooked_mmap(addr, len, prot, flags, fd, off);
}

void *mmap64(void *addr, size_t len, int prot, int flags, int fd, off64_t off) {
    return hooked_mmap(addr, len, prot, flags, fd, (off_t)off);
}

int munmap(void *addr, size_t len) {
    uintptr_t start = (uintptr_t)addr;
    uintptr_t end = start + ((len + PAGE_MASK_4K) & ~PAGE_MASK_4K);
    if (g_enabled && len && end > start) {
        size_t tail = tail_after(end);
        forget_inside(start, end);
        if (tail) {
            forget(end);
            len = (end - start) + tail;
        }
    }
    return (int)syscall(__NR_munmap, addr, len);
}

int mprotect(void *addr, size_t len, int prot) {
    uintptr_t start = (uintptr_t)addr;
    uintptr_t end = start + ((len + PAGE_MASK_4K) & ~PAGE_MASK_4K);
    if (g_enabled && len && end > start) {
        size_t tail = tail_after(end);
        if (tail) len = (end - start) + tail;
    }
    return (int)syscall(__NR_mprotect, addr, len, prot);
}
