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

/* Clicks OpenTrack's tray icon, so the XR menu can show its window.
 *
 * OpenTrack WXR starts hidden in the tray, and a hidden window cannot be raised
 * from outside. A single click on the tray icon toggles it, so this posts the
 * message the taskbar sends Qt 6's tray icon window for that click. */

#include <windows.h>
#include <wchar.h>

/* Qt's own tray callback message and icon id, from qwindowssystemtrayicon.cpp */
#define QT_MYWM_NOTIFYICON (WM_APP + 101)
#define QT_NOTIFYICON_ID 0

#ifndef NIN_SELECT
#define NIN_SELECT (WM_USER + 0)
#endif

#define RELAUNCH_WAIT_MS 20000
#define POLL_MS 250

static BOOL is_opentrack(HWND hwnd)
{
    DWORD pid = 0;
    GetWindowThreadProcessId(hwnd, &pid);
    HANDLE process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    if (!process) return FALSE;

    WCHAR path[MAX_PATH];
    DWORD len = MAX_PATH;
    BOOL ok = QueryFullProcessImageNameW(process, 0, path, &len);
    CloseHandle(process);
    if (!ok) return FALSE;

    WCHAR *name = wcsrchr(path, L'\\');
    return _wcsicmp(name ? name + 1 : path, L"opentrack.exe") == 0;
}

static BOOL CALLBACK find_visible(HWND hwnd, LPARAM found)
{
    if (IsWindowVisible(hwnd) && is_opentrack(hwnd)) {
        *(BOOL *)found = TRUE;
        return FALSE;
    }
    return TRUE;
}

static BOOL is_window_shown(void)
{
    BOOL found = FALSE;
    EnumWindows(find_visible, (LPARAM)&found);
    return found;
}

static HWND find_tray_window(void)
{
    HWND hwnd = NULL;
    while ((hwnd = FindWindowExW(NULL, hwnd, NULL, L"QTrayIconMessageWindow")) != NULL) {
        if (is_opentrack(hwnd)) return hwnd;
    }
    return NULL;
}

/* Starts opentrack.exe from this exe's folder, for when its window was closed with X and took the process with it */
static BOOL launch_opentrack(void)
{
    WCHAR dir[MAX_PATH], exe[MAX_PATH];
    DWORD len = GetModuleFileNameW(NULL, dir, MAX_PATH);
    if (len == 0 || len >= MAX_PATH) return FALSE;
    WCHAR *slash = wcsrchr(dir, L'\\');
    if (!slash) return FALSE;
    *slash = 0;
    if (swprintf(exe, MAX_PATH, L"%s\\opentrack.exe", dir) < 0) return FALSE;

    STARTUPINFOW si = { sizeof(si) };
    PROCESS_INFORMATION pi;
    if (!CreateProcessW(exe, NULL, NULL, NULL, FALSE, 0, NULL, dir, &si, &pi)) return FALSE;
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return TRUE;
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE prev, LPWSTR cmdline, int show)
{
    HWND hwnd = find_tray_window();
    if (!hwnd) {
        if (!launch_opentrack()) return 1;
        for (DWORD waited = 0; !hwnd && waited < RELAUNCH_WAIT_MS; waited += POLL_MS) {
            Sleep(POLL_MS);
            hwnd = find_tray_window();
        }
        if (!hwnd) return 1;
        // Give the main window time to finish starting before it is asked to show
        Sleep(1000);
    }
    // The click toggles, so leave a window that is already up alone: this only ever shows it
    if (is_window_shown()) return 0;
    PostMessageW(hwnd, QT_MYWM_NOTIFYICON, 0, MAKELPARAM(NIN_SELECT, QT_NOTIFYICON_ID));
    return 0;
}
