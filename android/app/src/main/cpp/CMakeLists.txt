cmake_minimum_required(VERSION 3.22.1)
project(vdubbing_whisper LANGUAGES C CXX)

include(FetchContent)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_compile_options(-O3)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
FetchContent_Declare(whisper
    URL "https://codeload.github.com/ggml-org/whisper.cpp/tar.gz/refs/tags/v${VDUBBING_WHISPER_VERSION}"
    URL_HASH SHA256=ff1a9053feb509ff9d7729703355541ae9690073a6b1c40eb692c962e0dc1720)
FetchContent_MakeAvailable(whisper)

add_library(vdubbing-whisper SHARED whisper_jni.cpp)
target_link_libraries(vdubbing-whisper PRIVATE whisper log android)
