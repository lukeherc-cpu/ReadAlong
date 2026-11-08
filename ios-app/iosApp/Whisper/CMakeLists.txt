# whisper.cpp for the iPhone: static libraries with the Metal GPU backend (shaders compiled in) and the CPU
# backend with Accelerate, plus ReadAlong's C wrapper. The workflow merges them into one libreadalong_whisper_all.a
# that Xcode links (project.yml).
#
#   cmake -S ios-app/iosApp/Whisper -B build -G Xcode -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_ARCHITECTURES=arm64 \
#         -DCMAKE_OSX_DEPLOYMENT_TARGET=16.0 -DWHISPER_CPP=<whisper.cpp checkout>
cmake_minimum_required(VERSION 3.21)
project(readalong_whisper_ios LANGUAGES C CXX OBJC)
set(CMAKE_CXX_STANDARD 17)
set(WHISPER_CPP "" CACHE PATH "whisper.cpp source checkout")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_METAL ON CACHE BOOL "" FORCE)
set(GGML_METAL_EMBED_LIBRARY ON CACHE BOOL "" FORCE)
set(GGML_BLAS ON CACHE BOOL "" FORCE)
set(GGML_ACCELERATE ON CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
# The same two DTW fixes as the desktop build (abort() on short windows and failed decoder passes).
include(${CMAKE_CURRENT_SOURCE_DIR}/../../../desktop-app/native/patch_whisper.cmake)
add_subdirectory(${WHISPER_CPP} whisper.cpp EXCLUDE_FROM_ALL)

add_library(readalong_whisper STATIC readalong_whisper.cpp)
target_link_libraries(readalong_whisper PRIVATE whisper ggml)
