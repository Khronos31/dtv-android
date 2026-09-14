#!/bin/sh
# SPDX-License-Identifier: MIT OR Apache-2.0
# Build the pinned upstream mirakc server for Android.
set -eu

project_root=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd -P)
work_root=$project_root/.work
source_url=${MIRAKC_SOURCE_URL:-https://github.com/mirakc/mirakc.git}
source_ref=${MIRAKC_SOURCE_REF:-b7a20d75d95595e0bca83dfb1b5473cfa5be6a93}
requested_abi=${ANDROID_ABI:-arm64-v8a}
source_dir=${MIRAKC_SOURCE_DIR:-$work_root/mirakc-3.4.85}
build_dir=${MIRAKC_BUILD_DIR:-$work_root/build-mirakc-$requested_abi}
output_dir=${MIRAKC_OUTPUT_DIR:-$work_root/mirakc-output-$requested_abi}

fail()
{
    printf '%s\n' "mirakc Android build: $*" >&2
    exit 1
}

case "$requested_abi" in
arm64-v8a|aarch64)
    abi=arm64-v8a
    rust_target=aarch64-linux-android
    clang_triple=aarch64-linux-android24
    cargo_target_var=AARCH64_LINUX_ANDROID
    ;;
armeabi-v7a|armv7a)
    abi=armeabi-v7a
    rust_target=armv7-linux-androideabi
    clang_triple=armv7a-linux-androideabi24
    cargo_target_var=ARMV7_LINUX_ANDROIDEABI
    ;;
*) fail "unsupported ABI '$requested_abi' (use arm64-v8a or armeabi-v7a)" ;;
esac

[ ! -L "$work_root" ] || fail "managed work root must not be a symlink: $work_root"
mkdir -p "$work_root"
work_root=$(CDPATH='' cd -- "$work_root" && pwd -P)

normalise_managed_path()
{
    value=$1
    case "$value" in
    /*) ;;
    *) value=$project_root/$value ;;
    esac
    value=$(realpath -m -- "$value") || fail "cannot normalize managed path: $1"
    case "$value" in
    "$work_root"/*) ;;
    *) fail "path must be under $work_root: $value" ;;
    esac
    printf '%s\n' "$value"
}

source_dir=$(normalise_managed_path "$source_dir")
build_dir=$(normalise_managed_path "$build_dir")
output_dir=$(normalise_managed_path "$output_dir")
case "$source_dir" in
    "$build_dir"|"$build_dir"/*|"$output_dir"|"$output_dir"/*) fail 'source directory overlaps build/output' ;;
esac
case "$build_dir" in
    "$source_dir"|"$source_dir"/*|"$output_dir"|"$output_dir"/*) fail 'build directory overlaps source/output' ;;
esac
case "$output_dir" in
    "$source_dir"|"$source_dir"/*|"$build_dir"|"$build_dir"/*) fail 'output directory overlaps source/build' ;;
esac

ndk=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${NDK:-}}}
if [ -z "$ndk" ]; then
    ndk=$(find /config/.tools/android-sdk/ndk -mindepth 1 -maxdepth 1 -type d \
        -name '[0-9]*.[0-9]*.[0-9]*' -print | sort -V | tail -n 1)
fi
[ -n "$ndk" ] && [ -d "$ndk" ] || fail 'ANDROID_NDK_HOME/ANDROID_NDK_ROOT must point to NDK r27'
ndk=$(CDPATH='' cd -- "$ndk" && pwd -P)
revision=$(sed -n 's/^Pkg.Revision = //p' "$ndk/source.properties" | head -n 1)
case "$revision" in
27.*) ;;
*) fail "NDK r27 is required, got '${revision:-unknown}'" ;;
esac
toolchain_bin=$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin
sysroot=$ndk/toolchains/llvm/prebuilt/linux-x86_64/sysroot
cc=$toolchain_bin/$clang_triple-clang
ar=$toolchain_bin/llvm-ar
strip=$toolchain_bin/llvm-strip
for tool in "$cc" "$ar" "$strip"; do
    [ -x "$tool" ] || fail "missing NDK tool: $tool"
done

for tool in cargo rustc rustup git sha256sum tar gzip readelf realpath; do
    command -v "$tool" >/dev/null 2>&1 || fail "$tool is required"
done
rustup target list --installed | grep -Fx "$rust_target" >/dev/null \
    || fail "Rust target $rust_target is not installed (install it with rustup target add)"

# Deterministic digests of the pinned Git tree and lockfile.
source_tree_sha256=e52b10a87c9fafecbb59641a7989075c1a4e0fa0e4c98ea6cdfb88e2da723551
cargo_lock_sha256=e0a246be3977014523f46e15a1cdadd946513bc443a12ee9629142740b9cf6d9
patch_file=$project_root/tools/mirakc/patches/mirakc-android-web-resilience.patch
patch_sha256=ed942f8d1f24dc7ba87387d0d7bc37bd9d61ce49a17091a3bcc8f52d6d0ed67f

mkdir -p "$(dirname -- "$source_dir")"
if [ ! -e "$source_dir" ]; then
    git clone --recurse-submodules --no-shallow-submodules "$source_url" "$source_dir"
elif [ ! -e "$source_dir/.git" ]; then
    fail "managed source path is not a Git checkout: $source_dir"
fi
git -C "$source_dir" diff --quiet \
    || fail "source checkout has tracked modifications: $source_dir"
git -C "$source_dir" diff --cached --quiet \
    || fail "source checkout has staged modifications: $source_dir"
git -C "$source_dir" fetch --no-tags "$source_url" "$source_ref"
git -C "$source_dir" checkout --detach "$source_ref"
git -C "$source_dir" submodule sync --recursive
git -C "$source_dir" submodule update --init --recursive
[ "$(git -C "$source_dir" rev-parse HEAD)" = "$source_ref" ] \
    || fail "source ref mismatch: expected $source_ref"
git -C "$source_dir" clean -ffdqx
git -C "$source_dir" submodule foreach --recursive 'git reset --hard && git clean -ffdqx'

actual_tree_sha256=$(git -C "$source_dir" archive --format=tar "$source_ref" | gzip -n | sha256sum | awk '{print $1}')
[ "$actual_tree_sha256" = "$source_tree_sha256" ] \
    || fail "source tree checksum mismatch: $actual_tree_sha256"
actual_lock_sha256=$(sha256sum "$source_dir/Cargo.lock" | awk '{print $1}')
[ "$actual_lock_sha256" = "$cargo_lock_sha256" ] \
    || fail "Cargo.lock checksum mismatch: $actual_lock_sha256"
grep -F 'version = "3.4.85"' "$source_dir/mirakc/Cargo.toml" >/dev/null \
    || fail 'pinned source is not mirakc 3.4.85'
[ -f "$patch_file" ] || fail "missing upstream patch: $patch_file"
actual_patch_sha256=$(sha256sum "$patch_file" | awk '{print $1}')
[ "$actual_patch_sha256" = "$patch_sha256" ] \
    || fail "upstream patch checksum mismatch: $actual_patch_sha256"

# Apply the source adaptation only for this invocation.  Reversing it on every
# exit keeps the managed checkout clean so a second ABI invocation can repeat
# the exact same verification and application steps.
patch_applied=0
cleanup_patch()
{
    exit_code=$?
    cleanup_failed=0
    if [ "$patch_applied" -eq 1 ]; then
        if git -C "$source_dir" apply --reverse --check --unidiff-zero -p0 "$patch_file" >/dev/null 2>&1; then
            if ! git -C "$source_dir" apply --reverse --unidiff-zero -p0 "$patch_file"; then
                printf '%s\n' 'mirakc Android build: failed to reverse upstream patch' >&2
                cleanup_failed=1
            fi
        else
            printf '%s\n' 'mirakc Android build: source patch could not be reversed' >&2
            cleanup_failed=1
        fi
    fi
    if [ "$cleanup_failed" -ne 0 ]; then
        exit_code=1
    fi
    trap - EXIT
    exit "$exit_code"
}
trap cleanup_patch EXIT
git -C "$source_dir" apply --check --unidiff-zero -p0 "$patch_file" \
    || fail 'upstream Android patch does not apply cleanly'
git -C "$source_dir" apply --unidiff-zero -p0 "$patch_file" \
    || fail 'failed to apply upstream Android patch'
patch_applied=1

mkdir -p "$build_dir" "$output_dir"
find "$build_dir" -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
find "$output_dir" -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +

# Keep Rust state in the persistent HAOS tools volume when it exists. On a
# hosted runner that volume is absent, so fall back to the runner user's
# normal rustup location; callers may always provide explicit overrides.
if [ -z "${CARGO_HOME:-}" ]; then
    if [ -d /config/.tools ]; then CARGO_HOME=/config/.tools/cargo; else CARGO_HOME=${HOME:-.}/.cargo; fi
fi
if [ -z "${RUSTUP_HOME:-}" ]; then
    if [ -d /config/.tools ]; then RUSTUP_HOME=/config/.tools/rustup; else RUSTUP_HOME=${HOME:-.}/.rustup; fi
fi
export CARGO_HOME RUSTUP_HOME
mkdir -p "$CARGO_HOME" "$RUSTUP_HOME"
# Cargo's release LTO otherwise peaks during the concurrent Gradle native
# build; thin LTO preserves release optimization while reducing link memory.
export CARGO_PROFILE_RELEASE_LTO=thin
export CARGO_TARGET_DIR="$build_dir/cargo-target"
export "CARGO_TARGET_${cargo_target_var}_LINKER=$cc"
export "CARGO_TARGET_${cargo_target_var}_AR=$ar"
export "CARGO_TARGET_${cargo_target_var}_RUSTFLAGS=-C link-arg=--target=$clang_triple -C link-arg=--sysroot=$sysroot -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384 -C link-arg=-Wl,-z,relro -C link-arg=-Wl,-z,now"
unset RUSTFLAGS CARGO_ENCODED_RUSTFLAGS

(cd "$source_dir" && cargo build --release --locked --package mirakc --target "$rust_target")
binary=$CARGO_TARGET_DIR/$rust_target/release/mirakc
[ -f "$binary" ] || fail "Cargo build did not produce $binary"
"$strip" --strip-unneeded "$binary"
"$project_root/tools/mirakc/verify-android-elf.sh" "$binary" "$abi"
cp "$binary" "$output_dir/mirakc-$abi"
chmod 0755 "$output_dir/mirakc-$abi"
printf '%s\n' "built $output_dir/mirakc-$abi"
