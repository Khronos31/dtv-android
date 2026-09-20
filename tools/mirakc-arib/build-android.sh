#!/bin/sh
# SPDX-License-Identifier: GPL-2.0-or-later
#
# Build the complete, pinned mirakc-arib command suite for Android.  This
# intentionally invokes upstream's CMake ExternalProject graph: all vendor
# libraries (including tsduck-arib and LibISDB) are built before the command
# executable.  No source component is pruned or replaced.
set -eu

project_root=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd -P)
source_url=${MIRAKC_ARIB_SOURCE_URL:-https://github.com/mirakc/mirakc-arib.git}
source_ref=${MIRAKC_ARIB_SOURCE_REF:-e85e1f991aa91ba0e6c6e02d14a17d159901e181}
source_dir=${MIRAKC_ARIB_SOURCE_DIR:-$project_root/.work/mirakc-arib-0.24.38}
requested_abi=${ANDROID_ABI:-${MIRAKC_ARIB_ABI:-armeabi-v7a}}
build_dir=${MIRAKC_ARIB_BUILD_DIR:-$project_root/.work/build-mirakc-arib-$requested_abi}
output=${MIRAKC_ARIB_OUTPUT:-$project_root/mirakc/src/main/jniLibs/$requested_abi/libmirakc-arib.so}
abi=$requested_abi
api=${MIRAKC_ARIB_API:-24}
ndk=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}
clean_room=${MIRAKC_CLEAN_ROOM:-0}

fail()
{
    printf '%s\n' "mirakc-arib Android build: $*" >&2
    exit 1
}

case "$clean_room" in
0|1) ;;
*) fail "MIRAKC_CLEAN_ROOM must be 0 or 1" ;;
esac

# The build uses recursive reset/clean because upstream's ExternalProject graph
# builds some dependencies in source. Restrict that destructive operation to a
# dedicated persistent scratch tree; unrelated checkouts are rejected.
work_root=$project_root/.work
mkdir -p "$work_root"
case "$source_dir" in
    /*) ;;
    *) source_dir=$project_root/$source_dir ;;
esac
source_dir=$(realpath -m -- "$source_dir") \
    || fail "cannot normalize source directory: $source_dir"
source_parent=$(CDPATH='' cd -- "$(dirname -- "$source_dir")" 2>/dev/null && pwd -P) \
    || fail "source directory parent is not accessible: $source_dir"
source_dir=$source_parent/$(basename -- "$source_dir")
case "$source_dir" in
    "$work_root"/*) ;;
    *) fail "MIRAKC_ARIB_SOURCE_DIR must be under $work_root: $source_dir" ;;
esac
case "$build_dir" in
    /*) ;;
    *) build_dir=$project_root/$build_dir ;;
esac
build_requested=$build_dir
case "$output" in
    /*) ;;
    *) output=$project_root/$output ;;
esac
case "$build_dir" in
    "$work_root"/*) ;;
    *) fail "MIRAKC_ARIB_BUILD_DIR must be under $work_root: $build_dir" ;;
esac
# Canonicalize the build parent before any cleanup.  The lexical check above
# rejects obvious escapes; this check also rejects symlinked parents escaping
# the dedicated scratch tree.  Never let a build directory alias the source
# tree, since its rerun cleanup is intentionally destructive.
[ ! -L "$build_requested" ] \
    || fail "MIRAKC_ARIB_BUILD_DIR must not be a symlink: $build_requested"
build_dir=$(realpath -m -- "$build_requested") \
    || fail "cannot normalize build directory: $build_requested"
case "$build_dir" in
    "$work_root"/*) ;;
    *) fail "MIRAKC_ARIB_BUILD_DIR resolves outside $work_root: $build_dir" ;;
esac
case "$build_dir" in
    "$source_dir"|"$source_dir"/*) fail 'MIRAKC_ARIB_BUILD_DIR must not alias or contain source tree' ;;
esac
case "$source_dir" in
    "$build_dir"|"$build_dir"/*) fail 'MIRAKC_ARIB_SOURCE_DIR must not be inside build directory' ;;
esac

case "$abi" in
armeabi-v7a|armv7a)
    abi=armeabi-v7a
    ;;
arm64-v8a|aarch64)
    abi=arm64-v8a
    ;;
*)
    fail "unsupported ABI '$abi' (use armeabi-v7a or arm64-v8a)"
    ;;
esac
case "$api" in
''|*[!0-9]*) fail "MIRAKC_ARIB_API must be an integer, got '$api'" ;;
esac
[ "$api" -ge 24 ] || fail "Android API 24 or newer is required, got $api"
[ -n "$ndk" ] && [ -d "$ndk" ] || fail 'ANDROID_NDK_HOME/ANDROID_NDK_ROOT must point to NDK r27'
ndk=$(CDPATH='' cd -- "$ndk" && pwd -P)
ndk_revision=$(sed -n 's/^Pkg.Revision = //p' "$ndk/source.properties" | head -n 1)
case "$ndk_revision" in
27.*) ;;
*) fail "NDK r27 is required, got '${ndk_revision:-unknown}'" ;;
esac

cmake_bin=${CMAKE:-$(command -v cmake || true)}
ninja_bin=${NINJA:-$(command -v ninja || true)}
[ -x "$cmake_bin" ] || fail 'cmake is required'
[ -x "$ninja_bin" ] || fail 'ninja is required'

# aribb24 is the only pinned dependency whose repository intentionally omits
# generated autotools files.  Bootstrap the pinned host toolchain; its
# validated marker fast path makes this cheap when already up to date.  Then
# export that PATH for every ExternalProject configure command.
autotools_root=${MIRAKC_ARIB_AUTOTOOLS_ROOT:-/config/.tools}
autotools_prefix=${MIRAKC_ARIB_AUTOTOOLS_PREFIX:-$autotools_root/autotools}
autotools_bootstrap=$project_root/tools/mirakc-arib/bootstrap-autotools.sh
[ -x "$autotools_bootstrap" ] || fail "autotools bootstrap script is missing: $autotools_bootstrap"
MIRAKC_ARIB_AUTOTOOLS_ROOT="$autotools_root" \
    MIRAKC_ARIB_AUTOTOOLS_PREFIX="$autotools_prefix" "$autotools_bootstrap"
PATH=$autotools_prefix/bin:$PATH
export PATH
ACLOCAL_PATH=$autotools_prefix/share/aclocal
export ACLOCAL_PATH
[ -s "$autotools_prefix/share/aclocal/pkg.m4" ] \
    || fail "portable pkg.m4 is missing: $autotools_prefix/share/aclocal/pkg.m4"
for tool in autoreconf autoconf automake aclocal m4; do
    command -v "$tool" >/dev/null 2>&1 || fail "$tool is required to regenerate aribb24 autotools input (install portable autotools; no generated files are accepted from the host tree)"
done
command -v pkg-config >/dev/null 2>&1 || fail 'pkg-config is required by aribb24 configure'

if [ "$clean_room" -eq 1 ]; then
    [ -d "$source_dir" ] || fail "clean-room source tree is missing: $source_dir"
else
    if [ ! -d "$source_dir/.git" ]; then
        mkdir -p "$(dirname -- "$source_dir")"
        git clone --recurse-submodules --no-shallow-submodules "$source_url" "$source_dir"
    fi

    git -C "$source_dir" fetch --no-tags "$source_url" "$source_ref"
    git -C "$source_dir" checkout --detach "$source_ref"
    git -C "$source_dir" submodule sync --recursive
    git -C "$source_dir" submodule update --init --recursive
    [ "$(git -C "$source_dir" rev-parse HEAD)" = "$source_ref" ] || fail "source ref mismatch"
fi

# ExternalProject builds aribb24 in-source and TSDuck regenerates files in its
# submodule.  Remove those outputs before each ABI build so no host objects or
# stale generated headers can be reused.  Tracked edits in the top-level source
# are refused; reviewed ExternalProject patches are reapplied below.
# Ignore submodule worktree state for the parent check: ExternalProject
# applies its reviewed source patches in-place, then reset every submodule
# below to the gitlink before starting this build.
source_patch_backup=$work_root/mirakc-arib-source-CMakeLists-$abi
if [ "$clean_room" -eq 1 ]; then
    cp -f "$source_dir/CMakeLists.txt" "$source_patch_backup"
else
    git -C "$source_dir" checkout -- CMakeLists.txt
    git -C "$source_dir" diff --ignore-submodules=all --quiet \
        || fail "source tree has tracked modifications: $source_dir"
    git -C "$source_dir" diff --ignore-submodules=all --cached --quiet \
        || fail "source tree has staged modifications: $source_dir"
    git -C "$source_dir" clean -ffdqx
    git -C "$source_dir" submodule foreach --recursive 'git reset --hard && git clean -ffdqx'
fi

android_patch=$project_root/tools/mirakc-arib/patches/tsduck-android.patch
[ -f "$android_patch" ] || fail "Android TSDuck patch is missing: $android_patch"
source_patch_applied=0
restore_source_patch()
{
    if [ "$source_patch_applied" -eq 1 ]; then
        if [ "$clean_room" -eq 1 ]; then
            cp -f "$source_patch_backup" "$source_dir/CMakeLists.txt"
        else
            git -C "$source_dir" checkout -- CMakeLists.txt
        fi
        source_patch_applied=0
    fi
}
trap restore_source_patch EXIT
patch -d "$source_dir" -p0 < "$android_patch"
source_patch_applied=1

# `git submodule status` prefixes a line with '+' or '-' when a checkout does
# not match its gitlink.  That must never be silently accepted for this gate.
if [ "$clean_room" -eq 0 ]; then
    if git -C "$source_dir" submodule status --recursive | awk 'substr($1,1,1) == "+" || substr($1,1,1) == "-" || substr($1,1,1) == "U" { bad=1 } END { exit bad }'; then
        :
    else
        fail 'one or more recursive submodules do not match the pinned gitlinks'
    fi
fi

# A previous configure or interrupted build may leave this dedicated build
# directory populated.  It is safe to replace only after the strict .work
# guard above; this makes failed ABI attempts reproducibly rerunnable.
if [ -e "$build_dir" ] && [ -n "$(find "$build_dir" -mindepth 1 -maxdepth 1 -print -quit)" ]; then
    find "$build_dir" -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
fi
mkdir -p "$build_dir" "$(dirname -- "$output")"

# ExternalProject child configurations (notably spdlog) must resolve the
# packages installed by the preceding vendor targets.  Keep this as an
# environment-level prefix so every upstream child receives the same path.
CMAKE_PREFIX_PATH=$build_dir/vendor${CMAKE_PREFIX_PATH:+:$CMAKE_PREFIX_PATH}
export CMAKE_PREFIX_PATH
PKG_CONFIG_PATH=$build_dir/vendor/lib/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}
export PKG_CONFIG_PATH

export MIRAKC_ARIB_NDK="$ndk"
export MIRAKC_ARIB_ANDROID_ABI="$abi"
export MIRAKC_ARIB_ANDROID_PLATFORM="android-$api"
toolchain_file=$project_root/tools/mirakc-arib/android.toolchain.cmake
elf_verifier=$project_root/tools/mirakc-arib/verify-android-elf.sh
toolchain_bin=$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin
case "$abi" in
armeabi-v7a)
    linker_flags='-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,-z,relro -Wl,-z,now'
    expected_machine=ARM
    android_target=armv7-none-linux-androideabi
    android_compiler_target=armv7a-linux-androideabi${api}
    cmake_compiler_target=armv7-none-linux-androideabi${api}
    android_cc=$toolchain_bin/armv7a-linux-androideabi${api}-clang
    android_cxx=$toolchain_bin/armv7a-linux-androideabi${api}-clang++
    ;;
arm64-v8a)
    linker_flags='-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,-z,relro -Wl,-z,now'
    expected_machine=AArch64
    android_target=aarch64-none-linux-android
    android_compiler_target=aarch64-linux-android${api}
    cmake_compiler_target=aarch64-none-linux-android${api}
    android_cc=$toolchain_bin/aarch64-linux-android${api}-clang
    android_cxx=$toolchain_bin/aarch64-linux-android${api}-clang++
    ;;
esac
[ -x "$android_cc" ] || fail "NDK compiler is missing: $android_cc"
[ -x "$android_cxx" ] || fail "NDK C++ compiler is missing: $android_cxx"
# aribb24 is an Autoconf ExternalProject and otherwise falls back to the host
# gcc when it cannot find a target-prefixed compiler.  Export the exact NDK
# compiler and binutils so its configure/build steps are genuinely cross.
export CC="$android_cc"
export CXX="$android_cxx"
export AR="$toolchain_bin/llvm-ar"
export RANLIB="$toolchain_bin/llvm-ranlib"
export STRIP="$toolchain_bin/llvm-strip"
tsduck_cxxflags='-Wno-reserved-identifier -Wno-unsafe-buffer-usage -Wno-implicit-int-conversion -Wno-cast-qual -Wno-error'
tsduck_target_flags="--target=$android_compiler_target --sysroot=$ndk/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
mkdir -p "$build_dir/host-tools"
# TSDuck receives CMAKE_C_COMPILER_TARGET-ld as a tool name, while NDK r27
# exposes the linker as ld.lld.  This symlink selects that real linker without
# introducing a host linker fallback or a fake command.
ln -sf "$toolchain_bin/ld.lld" "$build_dir/host-tools/${cmake_compiler_target}-ld"
PATH=$build_dir/host-tools:$PATH
export PATH

MIRAKC_ARIB_CI=1 "$cmake_bin" -S "$source_dir" -B "$build_dir" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$toolchain_file" \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM="android-$api" \
    -DCMAKE_EXE_LINKER_FLAGS="$linker_flags" \
    -DMIRAKC_ARIB_TSDUCK_ARIB_CXXFLAGS="$tsduck_cxxflags" \
    -DMIRAKC_ARIB_TSDUCK_TARGET_FLAGS="$tsduck_target_flags" \
    -DMIRAKC_ARIB_TEST=OFF \
    -DMIRAKC_ARIB_VENDOR_TEST=OFF
# Keep the generated command line as an auditable cross-compilation contract:
# TSDuck must receive the API-qualified NDK target, and its LD name must match
# CMake's API-qualified compiler target.  Without these checks, TSDuck's make
# wrapper can silently produce host objects while the parent configure is
# cross-compiling successfully.
[ -f "$build_dir/build.ninja" ] || fail "CMake did not generate $build_dir/build.ninja"
grep -F "TARGET_FLAGS=--target=$android_compiler_target --sysroot=" "$build_dir/build.ninja" >/dev/null \
    || fail "TSDuck TARGET_FLAGS do not contain API-qualified target $android_compiler_target"
grep -F "LD=$cmake_compiler_target-ld" "$build_dir/build.ninja" >/dev/null \
    || fail "TSDuck LD does not match CMake compiler target $cmake_compiler_target"
MIRAKC_ARIB_CI=1 "$ninja_bin" -C "$build_dir" vendor

# The vendor target re-runs CMake after installing its ExternalProjects.  The
# first configure necessarily cached NOTFOUND results, so clear only the
# vendor discovery cache entries before the second configure; otherwise the
# executable target is omitted even though every vendor artifact exists.
MIRAKC_ARIB_CI=1 "$cmake_bin" -S "$source_dir" -B "$build_dir" \
    -U aribb24_LIB -U tsduck-arib_LIB -U libisdb_LIB -U cppcodec \
    -U docopt_DIR -U fmt_DIR -U spdlog_DIR -U RapidJSON_DIR

aribb24_config_log=$source_dir/vendor/aribb24/config.log
[ -f "$aribb24_config_log" ] || fail "aribb24 configure log is missing: $aribb24_config_log"
if ! awk '/checking whether we are cross compiling/{seen=1} seen && /result: yes/{found=1; exit} END { exit !found }' "$aribb24_config_log"; then
    fail 'aribb24 was not configured as a cross build'
fi
grep -F "$android_target" "$aribb24_config_log" >/dev/null \
    || fail "aribb24 configure target is not $android_target"
[ -f "$build_dir/vendor/lib/libaribb24.a" ] || fail 'aribb24 static library was not installed'
[ -f "$build_dir/vendor/lib/libtsduck.a" ] || fail 'tsduck static library was not installed'

# Inspect every member of every vendor archive.  A successful archive command
# is not sufficient: TSDuck's Makefile can continue after compiler failures,
# and a stale host object would otherwise be accepted until final linking.
readelf_bin=${READELF:-$(command -v readelf || true)}
[ -x "$readelf_bin" ] || fail 'readelf is required for vendor archive checks'
archive_check_dir=$build_dir/archive-member-check
rm -rf "$archive_check_dir"
mkdir -p "$archive_check_dir"
archive_count=0
member_count=0
while IFS= read -r archive; do
    archive_count=$((archive_count + 1))
    while IFS= read -r member; do
        [ -n "$member" ] || continue
        member_count=$((member_count + 1))
        member_file=$archive_check_dir/member-$member_count.o
        "$toolchain_bin/llvm-ar" p "$archive" "$member" > "$member_file"
        archive_header=$($readelf_bin -h "$member_file") \
            || fail "cannot inspect archive member $archive:$member"
        printf '%s\n' "$archive_header" | grep -F "Machine:                           $expected_machine" >/dev/null \
            || fail "non-$expected_machine archive member: $archive:$member"
    done <<EOF
$("$toolchain_bin/llvm-ar" t "$archive")
EOF
done <<EOF
$(find "$build_dir/vendor/lib" -type f -name '*.a' -print)
EOF
[ "$archive_count" -gt 0 ] || fail 'no vendor static archives were found'
[ "$member_count" -gt 0 ] || fail 'vendor static archives contain no members'
rm -rf "$archive_check_dir"

# ExternalProject installs the vendor packages, after which upstream's normal
# configure pass creates the full mirakc-arib target.
MIRAKC_ARIB_CI=1 "$cmake_bin" -S "$source_dir" -B "$build_dir" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$toolchain_file" \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM="android-$api" \
    -DCMAKE_EXE_LINKER_FLAGS="$linker_flags" \
    -DMIRAKC_ARIB_TSDUCK_ARIB_CXXFLAGS="$tsduck_cxxflags" \
    -DMIRAKC_ARIB_TSDUCK_TARGET_FLAGS="$tsduck_target_flags" \
    -DMIRAKC_ARIB_TEST=OFF \
    -DMIRAKC_ARIB_VENDOR_TEST=OFF
MIRAKC_ARIB_CI=1 "$ninja_bin" -C "$build_dir" mirakc-arib

binary=$build_dir/bin/mirakc-arib
[ -f "$binary" ] || fail "upstream build did not produce $binary"
cp "$binary" "$output"
"$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded "$output"
READELF=${READELF:-$(command -v readelf || true)} "$elf_verifier" "$output" "$abi"
printf '%s\n' "built $output"
