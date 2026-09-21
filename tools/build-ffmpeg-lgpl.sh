#!/usr/bin/env bash
# Build a minimal LGPL ffmpeg (+ffprobe) with OpenH264 for the EPGStation APK.
#
# Produces, per ABI, under .work/ffmpeg-lgpl/<abi>:
#   ffmpeg            (ELF executable, renamed libffmpeg.so when staged)
#   ffprobe           (ELF executable, renamed libffprobe.so when staged)
#   libopenh264.so    (runtime dependency of the two above)
#   libc++_shared.so  (runtime dependency of libopenh264.so)
#
# The build is intentionally LGPL-only: libopenh264 is BSD-2-Clause and
# --enable-gpl/--enable-nonfree are never used.
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
work_root="$repo_root/.work"

FFMPEG_VERSION="n7.1"
OPENH264_VERSION="v2.5.0"
ffmpeg_dir="$work_root/FFmpeg-$FFMPEG_VERSION"
openh264_src="$work_root/openh264-$OPENH264_VERSION"
out_root="$work_root/ffmpeg-lgpl"

ndk_root="${ANDROID_NDK_HOME:-}"
if [[ -z "$ndk_root" ]]; then
    sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/config/.tools/android-sdk}}"
    ndk_root="$(find "$sdk_root/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -1 || true)"
fi
if [[ -z "$ndk_root" || ! -x "$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ]]; then
    echo "ffmpeg-lgpl: Android NDK r26+ is required; set ANDROID_NDK_HOME" >&2
    exit 2
fi
ndk_bin="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin"
ndk_sysroot_lib="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib"

mkdir -p "$work_root"
if [[ ! -f "$ffmpeg_dir/configure" ]]; then
    mkdir -p "$ffmpeg_dir"
    curl -fsSL "https://github.com/FFmpeg/FFmpeg/archive/refs/tags/$FFMPEG_VERSION.tar.gz" \
        | tar -xz --strip-components=1 -C "$ffmpeg_dir"
fi
if [[ ! -f "$openh264_src/Makefile" ]]; then
    mkdir -p "$openh264_src"
    curl -fsSL "https://github.com/cisco/openh264/archive/refs/tags/$OPENH264_VERSION.tar.gz" \
        | tar -xz --strip-components=1 -C "$openh264_src"
fi

build_one() {
    local abi="$1"
    local target="$2"
    local arch="$3"
    local cpu="$4"
    local libcxx="$5"
    local out="$out_root/$abi"
    local oh_dir="$work_root/openh264-build-$abi"
    local ff_dir="$work_root/ffmpeg-build-$abi"

    if [[ -f "$out/.complete" && -x "$out/ffmpeg" && -x "$out/ffprobe" && -f "$out/libopenh264.so" ]]; then
        echo "ffmpeg-lgpl: $abi already built"
        return 0
    fi

    rm -rf "$oh_dir" "$ff_dir" "$out"
    mkdir -p "$out"
    cp -a "$openh264_src" "$oh_dir"

    # OpenH264's Android demo targets use nested make that breaks under the
    # jobserver in this environment; the library itself is what we need.
    (cd "$oh_dir" && make OS=android NDKROOT="$ndk_root" TARGET=android-24 \
        ARCH="$arch" -j"$(nproc)" >/dev/null 2>&1 || true)
    test -f "$oh_dir/libopenh264.so" || { echo "ffmpeg-lgpl: OpenH264 build failed for $abi" >&2; exit 3; }

    mkdir -p "$work_root/pkgconfig-$abi"
    cat > "$work_root/pkgconfig-$abi/openh264.pc" <<EOF
prefix=$work_root
libdir=$oh_dir
includedir=$oh_dir/codec/api
Name: OpenH264
Description: OpenH264 codec library
Version: 2.5.0
Libs: -L\${libdir} -lopenh264
Cflags: -I\${includedir}
EOF

    mkdir -p "$ff_dir"
    (cd "$ff_dir" && PKG_CONFIG_PATH="$work_root/pkgconfig-$abi" \
        "$ffmpeg_dir/configure" \
        --target-os=android --arch="$arch" --cpu="$cpu" \
        --enable-cross-compile \
        --cc="$ndk_bin/$target-clang" --cxx="$ndk_bin/$target-clang++" \
        --ar="$ndk_bin/llvm-ar" --nm="$ndk_bin/llvm-nm" --ranlib="$ndk_bin/llvm-ranlib" \
        --strip="$ndk_bin/llvm-strip" --sysroot="$ndk_bin/../sysroot" \
        --disable-ffplay --disable-doc --disable-avdevice --disable-postproc \
        --disable-network --disable-everything \
        --enable-ffmpeg --enable-ffprobe \
        --enable-avcodec --enable-avformat --enable-avfilter --enable-swscale --enable-swresample \
        --enable-decoder=mpeg2video --enable-decoder=aac \
        --enable-encoder=libopenh264 --enable-encoder=aac \
        --enable-demuxer=mpegts \
        --enable-muxer=mpegts --enable-muxer=hls --enable-muxer=mp4 \
        --enable-protocol=pipe --enable-protocol=file \
        --enable-filter=yadif --enable-filter=scale --enable-filter=format \
        --enable-filter=aformat --enable-filter=fps --enable-filter=null --enable-filter=anull \
        --enable-parser=mpegvideo --enable-parser=aac \
        --enable-bsf=aac_adtstoasc --enable-bsf=h264_mp4toannexb \
        --enable-libopenh264 \
        --extra-cflags="-O2" >/dev/null 2>&1)
    (cd "$ff_dir" && make -j"$(nproc)" >/dev/null 2>&1)
    test -x "$ff_dir/ffmpeg" && test -x "$ff_dir/ffprobe" || {
        echo "ffmpeg-lgpl: ffmpeg build failed for $abi" >&2; exit 4
    }

    cp -f "$ff_dir/ffmpeg" "$out/ffmpeg"
    cp -f "$ff_dir/ffprobe" "$out/ffprobe"
    cp -f "$oh_dir/libopenh264.so" "$out/libopenh264.so"
    cp -f "$libcxx" "$out/libc++_shared.so"
    chmod 0755 "$out/ffmpeg" "$out/ffprobe"
    touch "$out/.complete"
    echo "ffmpeg-lgpl: built $abi"
}

build_one armeabi-v7a armv7a-linux-androideabi24 arm armv7-a \
    "$ndk_sysroot_lib/arm-linux-androideabi/libc++_shared.so"
build_one arm64-v8a aarch64-linux-android24 arm64 armv8-a \
    "$ndk_sysroot_lib/aarch64-linux-android/libc++_shared.so"

echo "ffmpeg-lgpl: done"
