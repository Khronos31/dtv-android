#!/bin/sh
# SPDX-License-Identifier: GPL-2.0-or-later
#
# Clean-room native relink slice for the aggregate corresponding-source
# archive. This covers the Siano/PX4 static-libusb consumers; the complete
# 22-payload APK rebuild remains a separate gate.
set -eu

archive=${1:-}
[ -n "$archive" ] && [ -f "$archive" ] || {
    printf '%s\n' "usage: $0 CORRESPONDING_SOURCE_ARCHIVE [ORDINARY_APK]" >&2
    exit 2
}
ordinary_apk=${2:-}
[ -z "$ordinary_apk" ] || [ -f "$ordinary_apk" ] || {
    printf '%s\n' "ordinary APK not found: $ordinary_apk" >&2
    exit 2
}
full_gate=0
[ -z "$ordinary_apk" ] || full_gate=1
script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd -P)
audit=$script_dir/audit-source.py
[ -x "$audit" ] || { printf '%s\n' "source auditor is missing: $audit" >&2; exit 1; }

ndk=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${NDK:-}}}
[ -n "$ndk" ] && [ -d "$ndk" ] || {
    printf '%s\n' 'ANDROID_NDK_HOME/ANDROID_NDK_ROOT must point to the pinned NDK' >&2
    exit 1
}
ndk=$(CDPATH='' cd -- "$ndk" && pwd -P)
revision=$(sed -n 's/^Pkg.Revision = //p' "$ndk/source.properties" | head -n 1)
case "$revision" in
27.0.12077973) ;;
*) printf '%s\n' "NDK 27.0.12077973 is required, got ${revision:-unknown}" >&2; exit 1 ;;
esac

for tool in python3 tar sha256sum strings make cmake ninja; do
    command -v "$tool" >/dev/null 2>&1 || {
        printf '%s\n' "$tool is required for the clean-room relink slice" >&2
        exit 1
    }
done

python3 "$audit" "$archive" >/dev/null
work=$(mktemp -d "${TMPDIR:-/tmp}/mirakc-native-rebuild.XXXXXX")
trap 'rm -rf "$work"' EXIT HUP INT TERM
mkdir -p "$work/source" "$work/bin"
tar -xzf "$archive" -C "$work/source"

source_root=$work/source
archived_audit=$source_root/sources/dtv-android/tools/mirakc/audit-source.py
[ -x "$archived_audit" ] || {
    printf '%s\n' 'archive is missing its own source auditor' >&2
    exit 1
}
python3 "$archived_audit" "$archive" >/dev/null
siano=$source_root/sources/siano-userland
px4=$source_root/sources/px4-userland
px4_drv=$source_root/sources/px4_drv
mirakc=$source_root/sources/mirakc
arib=$source_root/sources/mirakc-arib
dtv=$source_root/sources/dtv-android
libusb=$source_root/third_party/libusb-1.0.30
libusb_archive=$source_root/third_party/libusb-1.0.30.tar.bz2
swagger_archive=$source_root/third_party/swagger-ui/swagger-ui-5.17.14.zip
px4_build=$px4/scripts/build-android.sh
siano_build=$siano/scripts/build-android.sh
mirakc_build=$dtv/tools/mirakc/build-android.sh
arib_build=$dtv/tools/mirakc-arib/build-android.sh
for required in "$libusb/configure" "$libusb/COPYING" "$libusb_archive" "$swagger_archive" \
    "$px4_build" "$siano_build" "$px4/scripts/verify-android-elf.sh" \
    "$siano/scripts/verify-android-elf.sh" "$mirakc_build" "$arib_build" \
    "$dtv/tools/mirakc/audit-apk-native.sh" "$dtv/tools/mirakc/verify-android-elf.sh" \
    "$dtv/tools/mirakc-arib/bootstrap-autotools.sh" "$source_root/.cargo/config.toml"; do
    [ -f "$required" ] || {
        printf '%s\n' "archive is missing clean-room input: $required" >&2
        exit 1
    }
done
[ -d "$source_root/third_party/cargo/vendor" ] || {
    printf '%s\n' 'archive is missing Cargo vendor material' >&2
    exit 1
}
[ -x "$px4_build" ] && [ -x "$siano_build" ] || {
    printf '%s\n' 'archived Android build scripts must be executable' >&2
    exit 1
}

# Basename clients are covered by the guarded PATH below. Absolute clients,
# PATH-changing env invocations, and command -p/busybox bypasses would evade
# that guard and are rejected before any build starts.
python3 - "$px4_build" "$siano_build" "$mirakc_build" "$arib_build" <<'PY'
from pathlib import Path
import re
import sys

absolute_client = re.compile(
    r"(?m)(?:^|[;&|(\s])/(?:[^\s;&|()]+/)+(?:curl|wget|git)(?:\s|$|[;)])"
)
bypass_client = re.compile(
    r"(?m)(?:^|[;&|(\s])(?:env\s+[^;\n]*\bPATH\s*=\s*[^;\n]*\b"
    r"(?:curl|wget|git)\b|command\s+-p\s+(?:curl|wget|git)\b|"
    r"(?:busybox|toybox)\s+(?:curl|wget|git)\b)"
)
for name in sys.argv[1:]:
    text = Path(name).read_text(encoding="utf-8")
    if absolute_client.search(text):
        raise SystemExit(f"absolute network/source client bypass in archived harness: {name}")
    if bypass_client.search(text):
        raise SystemExit(f"network/source client bypass in archived harness: {name}")
PY

# Any attempted fallback download or source fetch is a hard failure.
for network_tool in curl wget; do
    cat >"$work/bin/$network_tool" <<'EOF'
#!/bin/sh
printf '%s\n' 'network access attempted during clean-room native rebuild' >&2
exit 97
EOF
    chmod 0755 "$work/bin/$network_tool"
done
# The archived CMake ExternalProject recipes use local Git operations to reset
# their already-archived trees before patching.  Keep those operations usable,
# but reject every Git subcommand that can resolve or fetch a remote object.
cat >"$work/bin/git" <<'EOF'
#!/bin/sh
set -eu
case "${1:-}" in
clone|fetch|pull|push|remote|ls-remote|archive)
    printf '%s\n' 'network/source fetch attempted during clean-room native rebuild' >&2
    exit 97
    ;;
esac
exec /usr/bin/git "$@"
EOF
chmod 0755 "$work/bin/git"
if [ "$full_gate" -eq 1 ]; then
    for tool in cargo rustc rustup patch git; do
        command -v "$tool" >/dev/null 2>&1 || {
            printf '%s\n' "$tool is required for the complete clean-room native gate" >&2
            exit 1
        }
    done
fi
cargo_bin_dir=$(dirname "$(command -v cargo 2>/dev/null || printf '%s' /nonexistent)")
git_bin=$(command -v git 2>/dev/null || printf '%s' /nonexistent)
guard_path=$work/bin:$cargo_bin_dir:$(dirname "$(command -v cmake)"):$(dirname "$(command -v ninja)"):/usr/bin:/bin
network_namespace=${MIRAKC_REQUIRE_NETWORK_NAMESPACE:-auto}
network_launcher=
run_uid=$(id -u)
run_gid=$(id -g)
case "$network_namespace" in
auto|required)
    if command -v unshare >/dev/null 2>&1; then
        if unshare -n -- true >/dev/null 2>&1; then
            network_launcher=unshare
        fi
    fi
    if [ -z "$network_launcher" ] && command -v sudo >/dev/null 2>&1; then
        # sudo is needed only to create the network namespace. Drop back to
        # the invoking uid/gid before running the build so its outputs remain
        # removable by the caller's EXIT trap.
        if sudo -n unshare -n --setuid "$run_uid" --setgid "$run_gid" -- true >/dev/null 2>&1; then
            network_launcher=sudo-unshare
        fi
    fi
    if [ -z "$network_launcher" ] && [ "$network_namespace" = required ]; then
        printf '%s\n' 'network namespace isolation was required but unavailable' >&2
        exit 1
    fi
    ;;
off)
    network_launcher=disabled
    ;;
*)
    printf '%s\n' 'MIRAKC_REQUIRE_NETWORK_NAMESPACE must be auto, required, or off' >&2
    exit 2
    ;;
esac
run_guarded() {
    case "$network_launcher" in
    unshare) unshare -n -- "$@" ;;
    sudo-unshare) sudo -n unshare -n --setuid "$run_uid" --setgid "$run_gid" -- "$@" ;;
    disabled|'') "$@" ;;
    esac
}
printf 'clean-room source-fetch guard: PATH wrappers; network namespace=%s\n' \
    "${network_launcher:-unavailable}"

full_native_root=$work/full-native
if [ "$full_gate" -eq 1 ]; then
    mkdir -p "$full_native_root/lib/arm64-v8a" "$full_native_root/lib/armeabi-v7a"
fi

modified_libusb=$work/libusb-modified
cp -a "$libusb" "$modified_libusb"
python3 - "$modified_libusb/libusb/core.c" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
data = path.read_text(encoding="utf-8")
old = "https://libusb.info"
new = "https://libusb.relink-test.invalid"
if data.count(old) != 2:
    raise SystemExit(f"expected two libusb markers, found {data.count(old)}")
path.write_text(data.replace(old, new), encoding="utf-8")
PY

modified_archive=$work/libusb-modified.tar.bz2
cp -a "$modified_libusb" "$work/libusb-1.0.30"
tar -cjf "$modified_archive" -C "$work" libusb-1.0.30
patch_siano_script() {
    tree=$1
    digest=$2
    script=$tree/scripts/build-android.sh
    python3 - "$script" "$digest" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
digest = sys.argv[2]
data = path.read_text(encoding="utf-8")
old_digest = "libusb_sha256=fea36f34f9156400209595e300840767ab1a385ede1dc7ee893015aea9c6dbaf"
if data.count(old_digest) != 1:
    raise SystemExit("unexpected pinned libusb digest count in Siano build script")
data = data.replace(old_digest, "libusb_sha256=" + digest)
needle = 'if [ ! -f "$src/libusb-${libusb_ver}.tar.bz2" ]; then\n'
replacement = (
    'if [ -n "${MIRAKC_TEST_LIBUSB_ARCHIVE:-}" ]; then\n'
    '\tcp -f "$MIRAKC_TEST_LIBUSB_ARCHIVE" "$src/libusb-${libusb_ver}.tar.bz2"\n'
    'elif [ ! -f "$src/libusb-${libusb_ver}.tar.bz2" ]; then\n'
)
if data.count(needle) != 1:
    raise SystemExit("Siano build script download guard changed unexpectedly")
path.write_text(data.replace(needle, replacement), encoding="utf-8")
PY
}

build_px4() {
    tree=$1
    abi=$2
    source_input=$3
    output=$4
    run_guarded env ANDROID_NDK_HOME="$ndk" PATH="$guard_path" \
        "$tree/scripts/build-android.sh" --abi "$abi" --output "$output" \
        --libusb-source "$source_input"
}

build_siano() {
    tree=$1
    abi=$2
    input_archive=$3
    patch_siano_script "$tree" "$(sha256sum "$input_archive" | awk '{print $1}')"
    log=$work/siano-$(basename "$tree")-$abi.log
    if ! run_guarded env ANDROID_NDK_HOME="$ndk" ANDROID_ABI="$abi" PATH="$guard_path" \
        MIRAKC_TEST_LIBUSB_ARCHIVE="$input_archive" \
        "$tree/scripts/build-android.sh" >"$log" 2>&1; then
        cat "$log" >&2
        return 1
    fi
    printf '%s/%s\n' "$tree/build/android-$abi" siano-ts
}

for abi in aarch64 armv7a; do
    case "$abi" in
    aarch64) output_abi=arm64-v8a ;;
    armv7a) output_abi=armeabi-v7a ;;
    esac
    base_siano=$work/siano-base-$abi
    changed_siano=$work/siano-changed-$abi
    cp -a "$siano" "$base_siano"
    cp -a "$siano" "$changed_siano"
    base_siano_bin=$(build_siano "$base_siano" "$abi" "$libusb_archive")
    changed_siano_bin=$(build_siano "$changed_siano" "$abi" "$modified_archive")
    [ -s "$base_siano_bin" ] && [ -s "$changed_siano_bin" ] || {
        printf '%s\n' "Siano output is missing for $abi" >&2
        find "$base_siano" "$changed_siano" -maxdepth 4 -type f -name 'siano-ts' -printf '%p %s bytes\n' >&2
        exit 1
    }
    [ "$(sha256sum "$base_siano_bin" | awk '{print $1}')" != \
       "$(sha256sum "$changed_siano_bin" | awk '{print $1}')" ] || {
        printf '%s\n' "Siano relink did not change the $abi payload" >&2
        exit 1
    }
    strings "$changed_siano_bin" | grep -F 'libusb.relink-test.invalid' >/dev/null || {
        printf '%s\n' "Siano $abi payload lacks the modified libusb marker" >&2
        exit 1
    }
    if strings "$base_siano_bin" | grep -F 'libusb.relink-test.invalid' >/dev/null; then
        printf '%s\n' "Siano baseline unexpectedly contains the modified marker: $abi" >&2
        exit 1
    fi
    if [ "$full_gate" -eq 1 ]; then
        cp -f "$base_siano_bin" "$full_native_root/lib/$output_abi/libsiano-ts.so"
    fi

    build_px4 "$px4" "$abi" "$libusb" "$work/px4-base-$abi"
    cp -a "$modified_libusb" "$work/px4-modified-libusb-$abi"
    build_px4 "$px4" "$abi" "$work/px4-modified-libusb-$abi" "$work/px4-changed-$abi"
    base_px4=$work/px4-base-$abi/px4d-$output_abi
    changed_px4=$work/px4-changed-$abi/px4d-$output_abi
    [ -s "$base_px4" ] && [ -s "$changed_px4" ] || {
        printf '%s\n' "PX4 daemon output is missing for $abi" >&2
        exit 1
    }
    base_px4_digest=$(sha256sum "$base_px4" | awk '{print $1}')
    changed_px4_digest=$(sha256sum "$changed_px4" | awk '{print $1}')
    [ "$base_px4_digest" != "$changed_px4_digest" ] || {
        printf '%s\n' "PX4 relink did not change the $abi payload" >&2
        exit 1
    }
    if ! strings "$changed_px4" | grep -F 'libusb.relink-test.invalid' >/dev/null; then
        printf '%s\n' "PX4 digest changed ($base_px4_digest -> $changed_px4_digest) but marker is absent: $abi" >&2
        exit 1
    fi
    if strings "$base_px4" | grep -F 'libusb.relink-test.invalid' >/dev/null; then
        printf '%s\n' "PX4 baseline unexpectedly contains the modified marker: $abi" >&2
        exit 1
    fi
    if [ "$full_gate" -eq 1 ]; then
        cp -f "$base_px4" "$full_native_root/lib/$output_abi/libpx4d.so"
        cp -f "$work/px4-base-$abi/px4-ts-$output_abi" "$full_native_root/lib/$output_abi/libpx4-ts.so"
        cp -f "$work/px4-base-$abi/px4ctl-$output_abi" "$full_native_root/lib/$output_abi/libpx4ctl.so"
    fi
done

if [ "$full_gate" -eq 1 ]; then
    mirakc_source=$dtv/.work/mirakc-3.4.86
    arib_source=$dtv/.work/mirakc-arib-0.24.38
    mkdir -p "$dtv/.work"
    cp -a "$mirakc" "$mirakc_source"
    [ -d "$px4_drv/fwtool" ] || {
        printf '%s\n' 'archive is missing px4_drv fwtool source' >&2
        exit 1
    }

    cargo_home=$work/cargo-home
    mkdir -p "$cargo_home" "$mirakc_source/.cargo"
    # The archive auditor already checked this file; point the copied build
    # tree at the archive's vendor directory without relying on a host cache.
    printf '%s\n' \
        '[source.crates-io]' \
        'replace-with = "vendored-sources"' \
        '' \
        '[source.vendored-sources]' \
        "directory = \"$source_root/third_party/cargo/vendor\"" \
        > "$mirakc_source/.cargo/config.toml"

    autotools_root=$dtv/.work/autotools
    mkdir -p "$autotools_root/autotools-sources"
    cp -f "$source_root"/toolchain-sources/* "$autotools_root/autotools-sources/"

    for abi in arm64-v8a armeabi-v7a; do
        # Each ABI's ExternalProject build mutates the TSDuck/arib vendor
        # trees. Recopy the archived bytes so the second ABI cannot reuse
        # generated objects or already-applied patches. The archive omits VCS
        # metadata, so provide disposable local Git repositories for the two
        # patch commands that use `git checkout -f`.
        rm -rf "$arib_source"
        cp -a "$arib" "$arib_source"
        for local_repo in "$arib_source/vendor/libisdb" "$arib_source/vendor/spdlog"; do
            [ -d "$local_repo" ] || {
                printf '%s\n' "archive is missing clean-room Git tree: $local_repo" >&2
                exit 1
            }
            "$git_bin" -C "$local_repo" init -q
            "$git_bin" -C "$local_repo" config user.name clean-room
            "$git_bin" -C "$local_repo" config user.email clean-room@example.invalid
            "$git_bin" -C "$local_repo" add -A -f
            if ! "$git_bin" -C "$local_repo" commit -q -m clean-room-source; then
                "$git_bin" -C "$local_repo" commit --allow-empty -q -m clean-room-source
            fi
        done
        mirakc_output=$dtv/.work/mirakc-output-$abi/mirakc-$abi
        run_guarded env \
            ANDROID_NDK_HOME="$ndk" ANDROID_ABI="$abi" PATH="$guard_path" \
            CARGO_HOME="$cargo_home" CARGO_NET_OFFLINE=true \
            VERGEN_GIT_SHA=fc9610f51f8621aa8db508ddd36c7f1e2785d7be \
            SWAGGER_UI_DOWNLOAD_URL="file://$swagger_archive" \
            MIRAKC_GIT_BIN="$git_bin" \
            MIRAKC_CLEAN_ROOM=1 MIRAKC_SOURCE_DIR="$mirakc_source" \
            MIRAKC_BUILD_DIR="$dtv/.work/build-mirakc-$abi" \
            MIRAKC_OUTPUT_DIR="$dtv/.work/mirakc-output-$abi" \
            "$dtv/tools/mirakc/build-android.sh"
        [ -s "$mirakc_output" ] || {
            printf '%s\n' "clean-room mirakc output is missing: $mirakc_output" >&2
            exit 1
        }
        cp -f "$mirakc_output" "$full_native_root/lib/$abi/libmirakc.so"

        arib_output=$full_native_root/lib/$abi/libmirakc-arib.so
        run_guarded env \
            ANDROID_NDK_HOME="$ndk" ANDROID_ABI="$abi" PATH="$guard_path" \
            MIRAKC_CLEAN_ROOM=1 MIRAKC_ARIB_SOURCE_DIR="$arib_source" \
            MIRAKC_ARIB_BUILD_DIR="$dtv/.work/build-mirakc-arib-$abi" \
            MIRAKC_ARIB_OUTPUT="$arib_output" \
            MIRAKC_ARIB_AUTOTOOLS_ROOT="$autotools_root" \
            MIRAKC_ARIB_AUTOTOOLS_PREFIX="$autotools_root/autotools" \
            "$dtv/tools/mirakc-arib/build-android.sh"
        [ -s "$arib_output" ] || {
            printf '%s\n' "clean-room mirakc-arib output is missing: $arib_output" >&2
            exit 1
        }
    done

    native_cmake_source=$dtv/mirakc/src/main/cpp
    ndk_cmake=$ndk/build/cmake/android.toolchain.cmake
    build_cmake_native() {
        abi=$1
        target=$2
        name=$3
        kind=$4
        shift 4
        build_dir=$work/native-cmake-$abi-$target
        destination=$full_native_root/lib/$abi/$name
        run_guarded env PATH="$guard_path" ANDROID_NDK_HOME="$ndk" \
            cmake -S "$native_cmake_source" -B "$build_dir" -G Ninja \
            -DCMAKE_BUILD_TYPE=Release \
            -DCMAKE_TOOLCHAIN_FILE="$ndk_cmake" \
            -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-24 \
            -DANDROID_STL=c++_static "$@"
        run_guarded env PATH="$guard_path" ninja -C "$build_dir" "$target"
        built=$build_dir/$name
        [ -s "$built" ] || {
            printf '%s\n' "clean-room native output is missing: $built" >&2
            exit 1
        }
        cp -f "$built" "$destination"
        "$dtv/tools/mirakc/verify-android-elf.sh" "$destination" "$abi" "$kind"
    }

    for abi in arm64-v8a armeabi-v7a; do
        build_cmake_native "$abi" usb_process libusb_process.so shared
        build_cmake_native "$abi" siano_adapter libmirakc-siano-adapter.so pie \
            -DMIRAKC_BUILD_SIANO_ADAPTER=ON
        build_cmake_native "$abi" b25_filter libmirakc-b25-filter.so pie \
            -DMIRAKC_BUILD_B25_FILTER=ON
        build_cmake_native "$abi" px4_adapter libmirakc-px4-adapter.so pie \
            -DMIRAKC_BUILD_PX4_ADAPTER=ON -DPX4_USERLAND_DIR="$px4"
        build_cmake_native "$abi" px4_fwtool libmirakc-px4-fwtool.so pie \
            -DMIRAKC_BUILD_PX4_FWTOOL=ON -DPX4_DRV_DIR="$px4_drv"
    done

    rebuilt_apk=$work/clean-room-native.apk
    python3 - "$full_native_root" "$rebuilt_apk" <<'PY'
from pathlib import Path
import sys
import zipfile

root = Path(sys.argv[1])
output = Path(sys.argv[2])
paths = sorted(path for path in root.rglob("*") if path.is_file())
if len(paths) != 22:
    raise SystemExit(f"clean-room native inventory has {len(paths)} files, expected 22")
with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
    for path in paths:
        archive.writestr(path.relative_to(root).as_posix(), path.read_bytes())
PY
    "$dtv/tools/mirakc/audit-apk-native.sh" "$rebuilt_apk"
    "$dtv/tools/mirakc/audit-apk-native.sh" "$ordinary_apk"
    python3 - "$ordinary_apk" "$rebuilt_apk" <<'PY'
from collections import Counter
from pathlib import Path
import sys
import zipfile

expected_names = {
    "libmirakc.so", "libmirakc-arib.so", "libsiano-ts.so", "libpx4d.so",
    "libpx4-ts.so", "libpx4ctl.so", "libusb_process.so",
    "libmirakc-siano-adapter.so", "libmirakc-b25-filter.so",
    "libmirakc-px4-adapter.so", "libmirakc-px4-fwtool.so",
}
expected = {
    f"lib/{abi}/{name}"
    for abi in ("arm64-v8a", "armeabi-v7a")
    for name in expected_names
}
for raw in sys.argv[1:]:
    with zipfile.ZipFile(Path(raw)) as archive:
        entries = [name for name in archive.namelist() if name.startswith("lib/")]
    counts = Counter(entries)
    if set(counts) != expected or any(count != 1 for count in counts.values()):
        raise SystemExit(f"native inventory mismatch in {raw}")
print("clean-room native inventory matches ordinary APK: 22 payloads")
PY
    printf '%s\n' 'clean-room complete native inventory gate: PASS'
fi

printf '%s\n' 'clean-room Siano/PX4 static libusb relink slice: PASS'
