#
# D Music v1.4.58：FFmpeg DTS 软解模块（media3 官方 decoder_ffmpeg 集成）
#
# 静态库由 G:\WorkBuddy\SolaraAndroid\.workbuddy\ffmpeg-build\ 下的
# build-ffmpeg-dts.sh 预编译产出（FFmpeg 6.0 + NDK r26d，只开 dts 解码器，
# --disable-everything 极简构建），复制到本目录 ffmpeg/android-libs/arm64-v8a/。
# 头文件从 FFmpeg 6.0 源码复制到本目录 ffmpeg/include/。
#

cmake_minimum_required(VERSION 3.22.1 FATAL_ERROR)

# Enable C++11 features.
set(CMAKE_CXX_STANDARD 11)

project(libffmpegJNI C CXX)

set(ffmpeg_location "${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg")
set(ffmpeg_binaries "${ffmpeg_location}/android-libs/${ANDROID_ABI}")

foreach(ffmpeg_lib avutil swresample avcodec)
    set(ffmpeg_lib_filename lib${ffmpeg_lib}.a)
    set(ffmpeg_lib_file_path ${ffmpeg_binaries}/${ffmpeg_lib_filename})
    if(NOT EXISTS ${ffmpeg_lib_file_path})
        message(FATAL_ERROR "FFmpeg 静态库缺失: ${ffmpeg_lib_file_path}——先跑 .workbuddy/ffmpeg-build/build-ffmpeg-dts.sh")
    endif()
    add_library(
            ${ffmpeg_lib}
            STATIC
            IMPORTED)
    set_target_properties(
            ${ffmpeg_lib} PROPERTIES
            IMPORTED_LOCATION
            ${ffmpeg_lib_file_path})
endforeach()

include_directories(${ffmpeg_location}/include)
find_library(android_log_lib log)

add_library(ffmpegJNI
            SHARED
            ffmpeg_jni.cc)

target_link_libraries(ffmpegJNI
                      PRIVATE android
                      PRIVATE swresample
                      PRIVATE avcodec
                      PRIVATE avutil
                      PRIVATE ${android_log_lib})

# Additional flags needed for "arm64-v8a" from NDK 23.1.7779620 and above.
# See https://github.com/google/ExoPlayer/issues/9933#issuecomment-1029775358.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_link_options(ffmpegJNI PRIVATE "-Wl,-Bsymbolic")
endif()
