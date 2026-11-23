#!/bin/bash
# Build the FFmpeg static decode libraries shipped in shared/src/main/cpp/prebuilt/.
#
# FFmpeg 7.1.1, trimmed to H.264 decode only (LGPL-2.1+; no GPL components are enabled).
# Output: libavcodec.a + libavutil.a for armeabi-v7a / arm64-v8a / x86 / x86_64 (API 24).
#
# Usage:
#   FFMMPEG_SRC=/path/to/ffmpeg-7.1.1   # configured FFmpeg source tree
#   ANDROID_NDK=/path/to/android-ndk-r27.2
#   ./build-ffmpeg-android.sh
#
# Requires: NDK r27.2 (matches shared/build.gradle), a POSIX shell, make.
# On Windows run inside MSYS2/Git Bash; the script rewrites MSYS paths that the
# native Windows NDK make cannot follow.
set -euo pipefail

SRC="${FFMPEG_SRC:?set FFMPEG_SRC to the ffmpeg-7.1.1 source directory}"
NDK="${ANDROID_NDK:?set ANDROID_NDK to the NDK root (r27.2)}"
ROOT="$(pwd)/ffmpeg-build"
TC="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
MAKE="$NDK/prebuilt/windows-x86_64/bin/make.exe"
OUT="shared/src/main/cpp/prebuilt"

# Assemble clang wrappers: the NDK's target-prefixed clang binaries set target+sysroot.
mkwrap() { # $1 wrapdir  $2 clang target triple
    local W="$1" T="$2"
    rm -rf "$W"; mkdir -p "$W"
    printf '#!/bin/sh\nexec "%s/%s-clang" "$@"\n' "$TC" "$T" > "$W/cc"
    printf '#!/bin/sh\nexec "%s/%s-clang++" "$@"\n' "$TC" "$T" > "$W/ccx"
    printf '#!/bin/sh\nexec "%s/llvm-ar.exe" "$@"\n' "$TC" > "$W/ar"
    printf '#!/bin/sh\nexec "%s/llvm-nm.exe" "$@"\n' "$TC" > "$W/nm"
    printf '#!/bin/sh\nexec "%s/llvm-ranlib.exe" "$@"\n' "$TC" > "$W/ranlib"
    printf '#!/bin/sh\nexec "%s/llvm-strip.exe" "$@"\n' "$TC" > "$W/strip"
    chmod +x "$W"/*
}

build_lib() { # $1 abi  $2 arch  $3 cpu  $4 triple  $5 asm on/off
    local ABI="$1" ARCH="$2" CPU="$3" TRIPLE="$4" ASM="$5"
    local W="$ROOT/wrap-$ABI" B="$ROOT/build-$ABI"
    mkwrap "$W" "$TRIPLE"
    rm -rf "$B"; mkdir -p "$B"; cd "$B"
    if [ "$ASM" = off ]; then ASM_FLAGS="--disable-asm"; else ASM_FLAGS="--enable-asm --enable-neon"; fi
    # shellcheck disable=SC2086
    "$SRC/configure" \
        --target-os=linux --arch="$ARCH" --cpu="$CPU" --enable-cross-compile \
        --cc="$W/cc" --cxx="$W/ccx" --host-cc="$W/cc" --host-ld="$W/cc" --ar="$W/ar" --nm="$W/nm" --ranlib="$W/ranlib" --strip="$W/strip" \
        --enable-pthreads --enable-pic \
        --disable-shared --enable-static \
        --disable-programs --disable-doc --disable-debug \
        --disable-avdevice --disable-avfilter --disable-avformat --disable-swscale --disable-swresample --disable-postproc \
        --disable-network --disable-autodetect --disable-iconv --disable-x86asm \
        --disable-everything --enable-decoder=h264 --enable-parser=h264 --enable-parser=hevc \
        $ASM_FLAGS \
        --logfile=conf.log > "$B/conf-out.log" 2>&1
    # configure records MSYS-style paths that the native Windows make cannot follow.
    grep -rl "$ROOT" --include='*.mak' --include='Makefile' . | xargs -r sed -i "s|$ROOT|$(cygpath -m "$ROOT" 2>/dev/null || echo "$ROOT")|g"
    "$MAKE" -j8 > "$B/make-out.log" 2>&1
    mkdir -p "$OUT/$ABI/ffmpeg"
    cp libavcodec/libavcodec.a libavutil/libavutil.a "$OUT/$ABI/ffmpeg/"
    echo "OK $ABI: $(ls -la "$OUT/$ABI/ffmpeg/" | awk 'NR>1{print $9}')"
}

cd "$(dirname "$0")/.."

build_lib armeabi-v7a arm   armv7-a armv7a-linux-androideabi24      on
build_lib arm64-v8a  aarch64 armv8-a aarch64-linux-android24        on
build_lib x86        i686   i686    i686-linux-android24            off
build_lib x86_64     x86_64 x86-64  x86_64-linux-android24          off

echo "ALL_DONE"
