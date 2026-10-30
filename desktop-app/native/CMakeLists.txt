# Builds whisper.cpp with every CPU variant and a GPU backend (Vulkan for AMD, Intel and NVIDIA cards on
# Windows and Linux, Metal on a Mac), plus the JNI library the transcriber process loads. Backends are
# separate libraries that load at run time, so a computer without a working GPU driver still transcribes
# on the CPU.
#
#   cmake -S native -B native/build -DWHISPER_CPP=<whisper.cpp checkout> && cmake --build native/build --config Release
#   cmake --install native/build   -> native/build/resources/<os>/ (packaged with the app)
cmake_minimum_required(VERSION 3.21)
project(readalong_whisper LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(WHISPER_CPP "" CACHE PATH "whisper.cpp source checkout")
if(APPLE)
    option(READALONG_VULKAN "Build the Vulkan GPU backend" OFF)
else()
    option(READALONG_VULKAN "Build the Vulkan GPU backend" ON)
endif()

set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
if(APPLE AND CMAKE_SYSTEM_PROCESSOR MATCHES "arm64|aarch64")
    # One CPU library for every Apple silicon chip: Apple's compiler already targets the M1 by default.
    set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
else()
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
endif()
if(APPLE)
    # Metal for the GPU, with its shaders compiled into the library so nothing else has to be shipped.
    set(GGML_METAL ON CACHE BOOL "" FORCE)
    set(GGML_METAL_EMBED_LIBRARY ON CACHE BOOL "" FORCE)
    set(GGML_BLAS OFF CACHE BOOL "" FORCE)
endif()
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN ${READALONG_VULKAN} CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
add_subdirectory(${WHISPER_CPP} whisper.cpp EXCLUDE_FROM_ALL)

find_package(JNI REQUIRED COMPONENTS JVM)
add_library(readalong_whisper SHARED readalong_whisper.cpp)
target_include_directories(readalong_whisper PRIVATE ${JNI_INCLUDE_DIRS})
target_link_libraries(readalong_whisper PRIVATE whisper ggml)
if(NOT WIN32)
    # Find whisper, ggml and the backends beside each other, wherever the app is installed. Unversioned
    # names (libwhisper.so, not libwhisper.so.1.8.7) so the files copied below are the ones the libraries ask for.
    if(APPLE)
        set(READALONG_RPATH "@loader_path")
    else()
        set(READALONG_RPATH "$ORIGIN")
    endif()
    foreach(target IN LISTS GGML_AVAILABLE_BACKENDS ITEMS readalong_whisper whisper ggml ggml-base)
        set_target_properties(${target} PROPERTIES INSTALL_RPATH "${READALONG_RPATH}" BUILD_WITH_INSTALL_RPATH ON INSTALL_NAME_DIR "@rpath")
        set_property(TARGET ${target} PROPERTY VERSION)
        set_property(TARGET ${target} PROPERTY SOVERSION)
    endforeach()
endif()

# Compose packages native/build/resources/<os>/ next to the app's jars.
if(WIN32)
    set(READALONG_OS windows)
elseif(APPLE)
    set(READALONG_OS macos)
else()
    set(READALONG_OS linux)
endif()
set(READALONG_OUT ${CMAKE_CURRENT_SOURCE_DIR}/build/resources/${READALONG_OS}/whisper)
add_custom_target(readalong_bundle ALL
    COMMAND ${CMAKE_COMMAND} -E make_directory ${READALONG_OUT}
    COMMAND ${CMAKE_COMMAND} -E copy_if_different $<TARGET_FILE:readalong_whisper> $<TARGET_FILE:whisper> $<TARGET_FILE:ggml> $<TARGET_FILE:ggml-base> ${READALONG_OUT}
    DEPENDS readalong_whisper)
# Each backend (ggml-cpu-<variant>, ggml-vulkan) is its own library, found by ggml_backend_load_all_from_path.
foreach(backend IN LISTS GGML_AVAILABLE_BACKENDS)
    add_dependencies(readalong_bundle ${backend})
    add_custom_command(TARGET readalong_bundle POST_BUILD COMMAND ${CMAKE_COMMAND} -E copy_if_different $<TARGET_FILE:${backend}> ${READALONG_OUT})
endforeach()
