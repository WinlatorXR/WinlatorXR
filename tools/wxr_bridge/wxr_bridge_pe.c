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

/* PE half of the bridge. Wine only hands a unixlib to a DLL it loaded as a
 * builtin, so build.sh stamps this one with Wine's builtin signature and it is
 * installed into the Wine build's lib/wine/aarch64-windows beside nothing else
 * of ours. Its only job is to forward calls to wxr_bridge.so.
 */

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include "wxr_bridge.h"

typedef LONG (WINAPI* PFN_NtQueryVirtualMemory)(HANDLE, const void*, int, void*, SIZE_T, SIZE_T*);
typedef LONG (WINAPI* PFN_unix_call_dispatcher)(uint64_t, unsigned int, void*);

#define MemoryWineUnixFuncs 1000
#ifndef STATUS_DLL_NOT_FOUND
#define STATUS_DLL_NOT_FOUND ((LONG)0xC0000135)
#endif

static HMODULE g_module;
static uint64_t g_unix_handle;
static PFN_unix_call_dispatcher g_dispatcher;

static LONG load_unixlib(void)
{
    HMODULE ntdll = GetModuleHandleA("ntdll.dll");
    PFN_NtQueryVirtualMemory query;
    PFN_unix_call_dispatcher* dispatcher;

    if (g_dispatcher) return 0;
    if (!ntdll) return STATUS_DLL_NOT_FOUND;

    query = (PFN_NtQueryVirtualMemory)(void*)GetProcAddress(ntdll, "NtQueryVirtualMemory");
    /* Exported as a variable holding the dispatcher, not as the function itself. */
    dispatcher = (PFN_unix_call_dispatcher*)(void*)GetProcAddress(ntdll, "__wine_unix_call_dispatcher");
    if (!query || !dispatcher || !*dispatcher) return STATUS_DLL_NOT_FOUND;

    LONG status = query(GetCurrentProcess(), g_module, MemoryWineUnixFuncs,
                        &g_unix_handle, sizeof(g_unix_handle), NULL);
    if (status) return status;
    g_dispatcher = *dispatcher;
    return 0;
}

/* Returns an NTSTATUS: nonzero means the call never reached the unixlib. */
__declspec(dllexport) LONG WINAPI WxrBridgeCall(unsigned int code, void* args)
{
    LONG status = load_unixlib();
    if (status) return status;
    return g_dispatcher(g_unix_handle, code, args);
}

BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID reserved)
{
    (void)reserved;
    if (reason == DLL_PROCESS_ATTACH) {
        g_module = instance;
        DisableThreadLibraryCalls(instance);
    }
    return TRUE;
}
