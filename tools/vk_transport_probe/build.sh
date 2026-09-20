#!/bin/sh
#
# Builds the native Vulkan transport probe for the device's own CPU.
#
# Unlike the DXVK probe this is a plain Android ELF, not a PE binary: the whole
# point is to see the driver directly rather than through winevulkan, which
# presents a Windows-shaped view (NT handles yes, fds and AHardwareBuffers no)
# and therefore cannot answer what the compositor side is able to import.
#
#   ANDROID_NDK=/path/to/ndk ./build.sh
#
# Output: build/vk_transport_probe

set -eu

here=$(cd "$(dirname "$0")" && pwd)
out="$here/build"

ndk=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
if [ -z "$ndk" ]; then
    echo "Set ANDROID_NDK to an NDK root (r27 or newer)." >&2
    exit 1
fi

# .cmd wrappers on Windows, bare names elsewhere.
cc=""
for candidate in \
    "$ndk/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android26-clang.cmd" \
    "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang" \
    "$ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android26-clang"; do
    if [ -x "$candidate" ]; then cc=$candidate; break; fi
done

if [ -z "$cc" ]; then
    echo "No aarch64-linux-android26-clang under $ndk" >&2
    exit 1
fi

mkdir -p "$out"
"$cc" -O2 -Wall -Wextra -o "$out/vk_transport_probe" "$here/vk_transport_probe.c" -ldl
echo "  -> $out/vk_transport_probe"
