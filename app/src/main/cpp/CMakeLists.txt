cmake_minimum_required(VERSION 3.22.1)
project("camerainterceptor")

# Searches for the Android Log library
find_library(
        log-lib
        log)

# Include the libyuv subdirectory - we have patched its CMake to avoid SME detection bugs
add_subdirectory(libyuv)

# Add the libyuv include directory
include_directories(libyuv/include)

add_library(
        image_processor
        SHARED
        image_processor.cpp)

target_link_libraries(
        image_processor
        ${log-lib}
        yuv
        android
        jnigraphics)
