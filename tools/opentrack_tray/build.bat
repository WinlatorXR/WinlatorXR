@echo off
rem Builds opentrack_tray.exe (x64, runs in both x86_64 and arm64ec containers) with MSVC.
rem Ship it by adding it to app/src/main/assets/opentrack_wxr.tzst as opentrack_wxr/opentrack_tray.exe.
setlocal
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat" >nul || exit /b 1
cd /d "%~dp0"
if not exist build mkdir build
cl /nologo /O2 /W3 /MT opentrack_tray.c /Fobuild\ /Febuild\opentrack_tray.exe /link /SUBSYSTEM:WINDOWS user32.lib kernel32.lib
