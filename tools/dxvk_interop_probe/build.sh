#!/bin/sh
#
# Builds the DXVK interop probe for every Windows target the toolchain offers.
#
# llvm-mingw is preferred because it covers arm64ec (the proton-9.0-arm64ec
# containers) and implements SEH, which --try-create needs. Plain mingw-w64
# builds only x86_64 and drops --try-create to inspection-only.
#
#   LLVM_MINGW=/path/to/llvm-mingw ./build.sh     # explicit toolchain root
#   ./build.sh                                    # whatever is on PATH
#
# Output: build/dxvk_interop_probe-<arch>.dll
#
# The arch is in the filename, not a parent directory, because the device side
# is flat: drive D maps to one Downloads folder, so identically-named builds
# would collide there. This way every target can sit on the device at once and
# EXTRA_EXEC_ARGS picks one by name.

set -eu

here=$(cd "$(dirname "$0")" && pwd)
out="$here/build"
src="$here/dxvk_interop_probe.c"

# -municode is deliberately absent: the probe is ANSI-only so rundll32 passes a
# LPSTR command line. -lversion covers the d3d11/vulkan-1 version reporting.
cflags="-O2 -Wall -Wextra -Wno-unused-parameter -shared"
ldlibs="-lversion -lkernel32 -luser32"

if [ -n "${LLVM_MINGW:-}" ]; then
    PATH="$LLVM_MINGW/bin:$PATH"
    export PATH
fi

built=0

build_target() {
    target=$1
    arch=$2
    compiler=""
    for candidate in "$target-clang" "$target-gcc"; do
        if command -v "$candidate" >/dev/null 2>&1; then
            compiler=$candidate
            break
        fi
    done

    if [ -z "$compiler" ]; then
        echo "skip $target (no $target-clang or $target-gcc on PATH)"
        return 0
    fi

    # __try/__except only exists under clang with -fms-extensions, and no
    # predefined macro reports that flag, so the two are set together here.
    # GCC gets neither and compiles the inspection-only path.
    seh=""
    case $compiler in
        *clang) seh="-fms-extensions -DWXR_HAVE_SEH=1" ;;
    esac

    mkdir -p "$out"
    dll="$out/dxvk_interop_probe-$arch.dll"
    echo "build $arch with $compiler"
    # shellcheck disable=SC2086
    "$compiler" $cflags $seh -o "$dll" "$src" $ldlibs
    echo "  -> $dll"
    built=$((built + 1))
}

build_target x86_64-w64-mingw32  x86_64
build_target arm64ec-w64-mingw32 arm64ec
build_target aarch64-w64-mingw32 aarch64

if [ "$built" -eq 0 ]; then
    echo "" >&2
    echo "No Windows cross-compiler found." >&2
    echo "Install llvm-mingw (it has arm64ec and SEH, both of which this needs):" >&2
    echo "  https://github.com/mstorsjo/llvm-mingw/releases" >&2
    echo "then rerun with LLVM_MINGW=/path/to/llvm-mingw ./build.sh" >&2
    exit 1
fi

echo ""
echo "$built target(s) built."
