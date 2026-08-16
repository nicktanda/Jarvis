cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni)

set(WHISPER_DIR ${CMAKE_SOURCE_DIR}/../../../../whisper.cpp)

# Build whisper.cpp as a static library
set(BUILD_SHARED_LIBS OFF)
set(WHISPER_BUILD_TESTS OFF)
set(WHISPER_BUILD_EXAMPLES OFF)
set(GGML_OPENMP OFF)

# Enable NEON optimizations for ARM
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv8.2-a+dotprod")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8.2-a+dotprod")
endif()

add_subdirectory(${WHISPER_DIR} ${CMAKE_BINARY_DIR}/whisper.cpp)

# JNI bridge library
add_library(whisper_jni SHARED whisper_jni.cpp)

target_include_directories(whisper_jni PRIVATE
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/ggml/include
)

target_link_libraries(whisper_jni
    whisper
    android
    log
)
