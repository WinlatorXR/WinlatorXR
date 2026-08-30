cmake_minimum_required(VERSION 3.22.1)

project(PatchElf)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2")

include_directories(src)

add_library(patchelf SHARED
            src/patchelf.cc)

target_link_libraries(patchelf)

# CLI build of the same source, so it can be shelled out to directly (same
# naming trick as proot/CMakeLists.txt's libproot.so - target name doubles as
# the required lib*.so output name so AGP packages it into nativeLibraryDir
# with exec permissions, even though it's really an executable).
add_executable(libpatchelf_bin.so
               src/patchelf.cc)
