# Builds whisper.cpp with every CPU variant and the Vulkan GPU backend (AMD, Intel and NVIDIA cards),
# plus the JNI library the transcriber process loads. Backends are separate libraries that load at run
# time, so a computer without a working Vulkan driver still transcribes on the CPU.
#
#   cmake -S native -B native/build -DWHISPER_CPP=<whisper.cpp checkout> && cmake --build native/build --config Release
#   cmake --install native/build   -> native/build/resources/<os>/ (packaged with the app)
cmake_minimum_required(VERSION 3.21)
project(readalong_whisper LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(WHISPER_CPP "" CACHE PATH "whisper.cpp source checkout")
option(READALONG_VULKAN "Build the Vulkan GPU backend" ON)

set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
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
    # Find whisper and ggml beside this library, wherever the app is installed.
    set_target_properties(readalong_whisper PROPERTIES INSTALL_RPATH "$ORIGIN" BUILD_WITH_INSTALL_RPATH ON)
    set_target_properties(whisper ggml ggml-base PROPERTIES INSTALL_RPATH "$ORIGIN" BUILD_WITH_INSTALL_RPATH ON)
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
