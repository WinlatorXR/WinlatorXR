#!/bin/sh
#
# Builds both halves of the bridge:
#   build/wxr_bridge.dll  arm64ec PE, stamped as a Wine builtin
#   build/i386/wxr_bridge.dll  i386 PE for 32-bit games, stamped the same
#   build/wxr_bridge.so   aarch64 bionic unixlib
#
#   LLVM_MINGW=/path/to/llvm-mingw \
#   ANDROID_NDK=/path/to/ndk \
#   WINE_SRC=/path/to/proton-wine \     # checkout of proton_11.0-2 (include/ is enough)
#   WINE_LIB=/path/to/lib/wine/aarch64-unix \   # that build's unix libs, for ntdll.so
#   ./build.sh
#
# The unixlib speaks the wineserver protocol directly, so WINE_SRC must be the
# exact branch the installed Proton was built from.

set -eu

here=$(cd "$(dirname "$0")" && pwd)
out="$here/build"
mkdir -p "$out"

: "${LLVM_MINGW:?set LLVM_MINGW}"
: "${ANDROID_NDK:?set ANDROID_NDK}"
: "${WINE_SRC:?set WINE_SRC}"
: "${WINE_LIB:?set WINE_LIB}"

"$LLVM_MINGW/bin/arm64ec-w64-mingw32-clang" -O2 -Wall -Wextra -shared \
    -o "$out/wxr_bridge.dll" "$here/wxr_bridge_pe.c"

# ntdll only hands a unixlib to a DLL carrying Wine's builtin signature at
# offset 64 of the DOS header, which is what winebuild --builtin writes. The
# server compares the terminating NUL too.
printf 'Wine builtin DLL\000' | dd of="$out/wxr_bridge.dll" bs=1 seek=64 conv=notrunc 2>/dev/null

# 32-bit games load this one from lib/wine/i386-windows; --kill-at keeps the export undecorated.
mkdir -p "$out/i386"
"$LLVM_MINGW/bin/i686-w64-mingw32-clang" -O2 -Wall -Wextra -shared -Wl,--kill-at \
    -o "$out/i386/wxr_bridge.dll" "$here/wxr_bridge_pe.c"
printf 'Wine builtin DLL\000' | dd of="$out/i386/wxr_bridge.dll" bs=1 seek=64 conv=notrunc 2>/dev/null

cc=""
for candidate in \
    "$ANDROID_NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe" \
    "$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" \
    "$ANDROID_NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/clang"; do
    if [ -f "$candidate" ]; then cc=$candidate; break; fi
done
[ -n "$cc" ] || { echo "no clang under $ANDROID_NDK" >&2; exit 1; }

# Proton 11's own unix libs target android28. WXR_REQ_D3DKMT_OBJECT_OPEN is the
# request number the installed win32u.so uses (disassemble its d3dkmt_object_open);
# 304 for Proton-11.0-2-arm64ec-1, whose tree has three requests the branch lacks.
"$cc" --target=aarch64-linux-android28 -O2 -Wall -Wextra -Wno-unused-parameter -fPIC -shared \
    -Wno-missing-field-initializers -D__WINESRC__ -DWINE_UNIX_LIB -I"$WINE_SRC/include" \
    -I"$here/../../app/src/main/cpp/xr" \
    -DWXR_REQ_D3DKMT_OBJECT_OPEN="${WXR_REQ_D3DKMT_OBJECT_OPEN:-304}" \
    -o "$out/wxr_bridge.so" "$here/wxr_bridge_unix.c" \
    "$WINE_LIB/ntdll.so" -lnativewindow -ldl

echo "  -> $out/wxr_bridge.dll"
echo "  -> $out/i386/wxr_bridge.dll"
echo "  -> $out/wxr_bridge.so"
