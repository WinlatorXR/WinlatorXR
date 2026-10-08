cmake_minimum_required(VERSION 3.22.1)

project(Winlator)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")

add_subdirectory(OpenXR-SDK)
add_subdirectory(patchelf)

# Add Winlator shared library
add_library(winlator SHARED
        winlator/drawable.c
        winlator/gpu_image.c
        winlator/sysvshared_memory.c
        winlator/xconnector_epoll.c
        winlator/alsa_client.c
        winlator/patchelf_wrapper.cpp
        winlator/vulkan.cpp
)

target_link_libraries(winlator
        log
        android
        jnigraphics
        aaudio
        EGL
        GLESv2
)

# Add XR shared library
add_library(xr SHARED
        xr/engine.c
        xr/edgeglow.c
        xr/framebuffer.c
        xr/input.c
        xr/direct.c
        xr/main.cpp
        xr/math.c
        xr/oneeuro.c
        xr/renderer.c
)

target_link_libraries(xr
        log
        openxr_loader
        EGL
        GLESv3
        nativewindow
        jnigraphics
)


# ---- EVSHIM (phase-0 stub) -----------------------------------
add_library(evshim SHARED winlator/evshim.c)

# ---------- SDL2 library (Build from source) ----------
# Add SDL2 as a library target.
# This assumes cpp/sdl2 has its own CMakeLists.txt or you list its sources here.
# Most commonly, SDL2 comes with its own CMake build system.
# If cpp/sdl2 has a CMakeLists.txt:
add_subdirectory(sdl2) # This will create an SDL2 target (often named SDL2::SDL2 or just SDL2)

# If you need to list SDL2 source files manually (less ideal if SDL2 is complex):
# file(GLOB SDL2_SOURCES "sdl2/*.c" "sdl2/src/*/*.c" ...) # Adjust glob patterns
# add_library(MySDL2 STATIC ${SDL2_SOURCES}) # Or SHARED
# target_include_directories(MySDL2 PUBLIC "${CMAKE_CURRENT_LIST_DIR}/sdl2/include") # Assuming headers are in sdl2/include


# Tell evshim's compiler where to find SDL2 headers (still needed)
target_include_directories(evshim PRIVATE
        "${CMAKE_CURRENT_LIST_DIR}/sdl2/include" # Adjust if SDL headers are elsewhere in your sdl2 dir
)

# ---------- link everything ----------
target_link_libraries(evshim PRIVATE # Or PUBLIC
        SDL2            # If add_subdirectory(sdl2) created a target named SDL2
        # Or MySDL2     # If you used add_library(MySDL2 ...)
        # Or SDL2::SDL2 # Common imported target name for modern CMake SDL2
        log
        dl
)

# drop .so into jniLibs/<abi> so Gradle packs it automatically
set_target_properties(evshim PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY
        "${CMAKE_CURRENT_SOURCE_DIR}/jniLibs/${ANDROID_ABI}")

# ---- KGSL_TURNIP_SHIM (legacy GPUMEM_ALLOC_ID/FREE_ID -> GPUOBJ_* translation) ----
add_library(kgsl_turnip_shim SHARED winlator/kgsl_turnip_shim.c)

target_link_libraries(kgsl_turnip_shim PRIVATE
        log
        dl
)

set_target_properties(kgsl_turnip_shim PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY
        "${CMAKE_CURRENT_SOURCE_DIR}/jniLibs/${ANDROID_ABI}")

# ---- MAP_MERGE_SHIM (keeps small 64 KB-aligned Wine allocations mergeable, for vm.max_map_count) ----
add_library(map_merge_shim SHARED winlator/map_merge_shim.c)

set_target_properties(map_merge_shim PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY
        "${CMAKE_CURRENT_SOURCE_DIR}/jniLibs/${ANDROID_ABI}")

# ---- TURNIP_COMPAT_SHIM (libc/platform symbol gaps on older Android, needed by some Turnip builds) ----
add_library(turnip_compat_shim SHARED winlator/turnip_compat_shim.c)

set_target_properties(turnip_compat_shim PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY
        "${CMAKE_CURRENT_SOURCE_DIR}/jniLibs/${ANDROID_ABI}")
