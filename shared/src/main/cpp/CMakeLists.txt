cmake_minimum_required(VERSION 3.22.1)
project(xcertplay_lwip C)

set(LWIP ${CMAKE_CURRENT_SOURCE_DIR}/lwip/src)
file(GLOB LWIP_CORE CONFIGURE_DEPENDS ${LWIP}/core/*.c)
file(GLOB LWIP_IPV6 CONFIGURE_DEPENDS ${LWIP}/core/ipv6/*.c)
list(FILTER LWIP_IPV6 EXCLUDE REGEX ".*/ethip6\\.c$")
# sys.c is the OS port. This build is NO_SYS and provides sys_now() itself.
list(FILTER LWIP_CORE EXCLUDE REGEX ".*/(sys|altcp|altcp_alloc|altcp_tcp)\\.c$")

add_library(xcertplay_lwip SHARED
        jni_stack.c
        jni_usb.c
        jni_chacha.c
        userspace_stack.c
        ${LWIP_CORE}
        ${LWIP_IPV6})

target_include_directories(xcertplay_lwip PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${LWIP}/include)
target_compile_options(xcertplay_lwip PRIVATE -Wall -Wno-unused-parameter -Wno-unused-function)
set(CONSCRYPT_PREBUILT ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI}/libconscrypt_jni.so)
if(NOT EXISTS ${CONSCRYPT_PREBUILT})
    message(FATAL_ERROR "Missing Conscrypt library ${CONSCRYPT_PREBUILT}")
endif()
add_library(conscrypt_jni SHARED IMPORTED)
set_target_properties(conscrypt_jni PROPERTIES IMPORTED_LOCATION "${CONSCRYPT_PREBUILT}")
target_link_libraries(xcertplay_lwip log "-Wl,--no-as-needed" conscrypt_jni "-Wl,--as-needed")

# FFmpeg H.264 software decoder (the "video decoder" setting's FFmpeg engine): a prebuilt static
# libavcodec/libavutil linked into one JNI library, LGPL 2.1 with the source kept in ffmpeg-work.
set(FFMPEG_PREBUILT ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI}/ffmpeg)
if(EXISTS "${FFMPEG_PREBUILT}/libavcodec.a" AND EXISTS "${FFMPEG_PREBUILT}/libavutil.a")
    add_library(xcertplay_ffmpeg SHARED ffmpeg_jni.c)
    target_include_directories(xcertplay_ffmpeg PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg-include)
    target_compile_options(xcertplay_ffmpeg PRIVATE -Wall -Wno-unused-parameter)
    target_link_libraries(xcertplay_ffmpeg
            ${FFMPEG_PREBUILT}/libavcodec.a
            ${FFMPEG_PREBUILT}/libavutil.a
            log)
else()
    message(WARNING "FFmpeg prebuilt missing for ${ANDROID_ABI}; the FFmpeg decoder engine is off")
endif()
