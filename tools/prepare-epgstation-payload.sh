#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
work_root="$repo_root/.work"
epg_root="$work_root/EPGStation-v2.10.0"
payload_root="$repo_root/epgstation-server/.generated/epgstation-payload"
node_mobile_zip="$work_root/nodejs-mobile-v16.17.0-android.zip"
node_headers="$work_root/node-v16.17.0-headers"
node_mobile_source="$work_root/nodejs-mobile-src-v16.17.0"
ndk_root="${ANDROID_NDK_HOME:-}"

if [[ -z "$ndk_root" ]]; then
    sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/config/.tools/android-sdk}}"
    ndk_root="$(find "$sdk_root/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -1 || true)"
fi
if [[ -z "$ndk_root" || ! -x "$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin/clang++" ]]; then
    echo "EPGStation payload: Android NDK r26+ is required; set ANDROID_NDK_HOME" >&2
    exit 2
fi

mkdir -p "$work_root"
if [[ ! -f "$epg_root/package.json" ]]; then
    git clone --depth 1 --branch v2.10.0 https://github.com/l3tnun/EPGStation.git "$epg_root"
fi
if [[ ! -f "$node_mobile_zip" ]]; then
    curl -fsSL -o "$node_mobile_zip" \
        https://github.com/nodejs-mobile/nodejs-mobile/releases/download/nodejs-mobile-v16.17.0/nodejs-mobile-v16.17.0-android.zip
fi
if [[ ! -f "$node_headers/include/node/node.h" ]]; then
    mkdir -p "$node_headers"
    curl -fsSL https://nodejs.org/download/release/v16.17.0/node-v16.17.0-headers.tar.gz \
        | tar -xz --strip-components=1 -C "$node_headers"
fi
if [[ ! -f "$node_mobile_source/LICENSE" ]]; then
    git clone --depth 1 --branch nodejs-mobile-v16.17.0 \
        https://github.com/nodejs-mobile/nodejs-mobile.git "$node_mobile_source"
fi

if [[ ! -f "$epg_root/dist/index.js" ]]; then
    (cd "$epg_root" && npm ci --ignore-scripts --no-audit --no-fund && npm run compile)
fi
if [[ ! -f "$epg_root/client/dist/index.html" ]]; then
    (cd "$epg_root/client" && npm ci --ignore-scripts --no-audit --no-fund && npm run build)
fi
(cd "$epg_root" && npm prune --omit=dev --no-audit --no-fund)
# EPGStation's arib parser uses @node-rs/crc32. npm refuses to install an
# Android optional package from a Linux host, so fetch the pinned registry
# tarballs and unpack them explicitly. This keeps host ELF files out.
for crc_spec in \
    "android-arm64 node-rs-crc32-android-arm64-1.4.3.tgz" \
    "android-arm-eabi node-rs-crc32-android-arm-eabi-1.4.3.tgz"; do
    read -r crc_abi crc_archive <<< "$crc_spec"
    crc_dir="$epg_root/node_modules/@node-rs/crc32-$crc_abi"
    mkdir -p "$crc_dir"
    if [[ ! -f "$work_root/$crc_archive" ]]; then
        (cd "$work_root" && npm pack --ignore-scripts --pack-destination "$work_root" \
            "@node-rs/crc32-$crc_abi@1.4.3" >/dev/null)
    fi
    tar -xzf "$work_root/$crc_archive" --strip-components=1 -C "$crc_dir"
done

ndk_bin="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin"
node_gyp="$epg_root/node_modules/.bin/node-gyp"
if [[ ! -x "$node_gyp" ]]; then
    echo "EPGStation payload: production install did not provide node-gyp for sqlite3" >&2
    exit 3
fi

mkdir -p "$work_root/native/armeabi-v7a" "$work_root/native/arm64-v8a"
for spec in \
    "armeabi-v7a armv7a-linux-androideabi24 arm" \
    "arm64-v8a aarch64-linux-android24 arm64"; do
    read -r abi target npm_arch <<< "$spec"
    native_out="$work_root/native/$abi/node_sqlite3.node"
    if [[ ! -f "$native_out" ]]; then
        (cd "$epg_root/node_modules/sqlite3" && \
            PYTHONPATH="$repo_root/tools/python-shim" "$node_gyp" clean && \
            PYTHONPATH="$repo_root/tools/python-shim" \
            CC="$ndk_bin/clang --target=$target" \
            CXX="$ndk_bin/clang++ --target=$target" \
            AR="$ndk_bin/llvm-ar" \
            "$node_gyp" rebuild --nodedir="$node_headers" --arch="$npm_arch")
        cp "$epg_root/node_modules/sqlite3/build/Release/node_sqlite3.node" "$native_out"
    fi
done

build_runtime() {
    local abi="$1"
    local target="$2"
    local triple="$3"
    local cxx_lib="$4"
    local out="$work_root/runtime/$abi"
    mkdir -p "$out"
    if [[ ! -f "$out/libnode.so" ]]; then
        unzip -p "$node_mobile_zip" "nodejs-mobile-v16.17.0-android/bin/$abi/libnode.so" > "$out/libnode.so"
    fi
    cp "$cxx_lib" "$out/libc++_shared.so"
    "$ndk_bin/clang++" --target="$target" -fPIE -pie -O2 \
        -I"$node_headers/include/node" \
        -L"$out" -Wl,-rpath,'$ORIGIN' -Wl,--no-as-needed \
        -lnode -lc++_shared -ldl -llog -lm \
        "$repo_root/tools/epgstation-node-launcher.cc" -o "$out/node"
    if [[ "$triple" == "aarch64-linux-android" ]]; then
        readelf -l "$out/node" | grep -q '/system/bin/linker64'
    else
        readelf -l "$out/node" | grep -q '/system/bin/linker'
    fi
}

sysroot="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib"
build_runtime armeabi-v7a armv7a-linux-androideabi24 arm-linux-androideabi \
    "$sysroot/arm-linux-androideabi/libc++_shared.so"
build_runtime arm64-v8a aarch64-linux-android24 aarch64-linux-android \
    "$sysroot/aarch64-linux-android/libc++_shared.so"

# Android 10+ refuses to exec or dlopen ELF from app-private filesDir
# (SELinux execute_no_trans on app_data_file). Package the Node launcher
# and addons as lib*.so so they land in nativeLibraryDir.
stage_jni_libs() {
    local generated_jni="$repo_root/epgstation-server/.generated/jniLibs"
    stage_one() {
        local abi="$1"
        local crc_src="$2"
        local dest="$generated_jni/$abi"
        mkdir -p "$dest"
        cp -f "$work_root/runtime/$abi/libnode.so" "$dest/libnode.so"
        cp -f "$work_root/runtime/$abi/libc++_shared.so" "$dest/libc++_shared.so"
        cp -f "$work_root/runtime/$abi/node" "$dest/libepgstation-node.so"
        cp -f "$work_root/native/$abi/node_sqlite3.node" "$dest/libnode_sqlite3.so"
        if [[ ! -f "$crc_src" ]]; then
            echo "EPGStation payload: missing crc32 addon $crc_src" >&2
            exit 4
        fi
        cp -f "$crc_src" "$dest/libcrc32_android.so"
        chmod 0755 "$dest"/lib*.so
        # Android dlopen does not see the main executable's DT_NEEDED
        # symbols. Node addons must themselves NEEDED libnode.so.
        if ! command -v patchelf >/dev/null; then
            echo "EPGStation payload: patchelf is required to add libnode.so to Node addons" >&2
            exit 5
        fi
        for addon in libnode_sqlite3.so libcrc32_android.so; do
            if ! readelf -d "$dest/$addon" | grep -q 'Shared library: \[libnode.so\]'; then
                patchelf --add-needed libnode.so "$dest/$addon"
            fi
        done
        if [[ "$abi" == "arm64-v8a" ]]; then
            readelf -l "$dest/libepgstation-node.so" | grep -q '/system/bin/linker64'
        else
            readelf -l "$dest/libepgstation-node.so" | grep -q '/system/bin/linker'
        fi
    }
    stage_one armeabi-v7a \
        "$epg_root/node_modules/@node-rs/crc32-android-arm-eabi/crc32.android-arm-eabi.node"
    stage_one arm64-v8a \
        "$epg_root/node_modules/@node-rs/crc32-android-arm64/crc32.android-arm64.node"
}

stage_jni_libs

if [[ -f "$payload_root/.complete" ]]; then
    if [[ ! -f "$payload_root/payload.version" ]]; then
        printf 'EPGStation-v2.10.0-nodejs-mobile-v16.17.0\n' > "$payload_root/payload.version"
    fi
    echo "EPGStation payload already prepared: $payload_root"
    exit 0
fi
mkdir -p "$payload_root"
cp -a "$epg_root/dist" "$payload_root/dist"
mkdir -p "$payload_root/client"
cp -a "$epg_root/client/dist" "$payload_root/client/dist"
cp -a "$epg_root/config" "$payload_root/config"
cp -a "$epg_root/node_modules" "$payload_root/node_modules"
cp "$epg_root/package.json" "$payload_root/package.json"
cp "$epg_root/api.yml" "$payload_root/api.yml"
node "$repo_root/tools/patch-crc32-android.mjs" \
    "$payload_root/node_modules/@node-rs/crc32/index.js"
mkdir -p "$payload_root/licenses"
# ELF belongs in jniLibs, not assets. Node will dlopen the nativeLibraryDir
# copies through symlinks created at runtime.
find "$payload_root" \( -name '*.node' -o -name '*.so' \) -type f -delete
rm -rf "$payload_root/runtime" "$payload_root/native"
rm -rf "$payload_root/node_modules/sqlite3/build"
cp "$epg_root/LICENSE" "$payload_root/licenses/EPGStation-LICENSE"
cp "$node_mobile_source/LICENSE" "$payload_root/licenses/Node-LICENSE"
node "$repo_root/tools/generate-npm-notice.mjs" \
    "$payload_root/node_modules" "$payload_root/licenses/NOTICE.npm.txt"
printf 'EPGStation v2.10.0\nNode.js mobile v16.17.0\n' > "$payload_root/.versions"
printf 'EPGStation-v2.10.0-nodejs-mobile-v16.17.0\n' > "$payload_root/payload.version"
touch "$payload_root/.complete"
echo "Prepared EPGStation payload: $payload_root"
