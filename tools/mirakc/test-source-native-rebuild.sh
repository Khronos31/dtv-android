#!/bin/sh
# SPDX-License-Identifier: GPL-2.0-or-later
#
# Clean-room native relink slice for the aggregate corresponding-source
# archive. This covers the Siano/PX4 static-libusb consumers; the complete
# 22-payload APK rebuild remains a separate gate.
set -eu

archive=${1:-}
[ -n "$archive" ] && [ -f "$archive" ] || {
    printf '%s\n' "usage: $0 CORRESPONDING_SOURCE_ARCHIVE" >&2
    exit 2
}
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
libusb=$source_root/third_party/libusb-1.0.30
libusb_archive=$source_root/third_party/libusb-1.0.30.tar.bz2
px4_build=$px4/scripts/build-android.sh
siano_build=$siano/scripts/build-android.sh
for required in "$libusb/configure" "$libusb/COPYING" "$libusb_archive" \
    "$px4_build" "$siano_build" "$px4/scripts/verify-android-elf.sh" \
    "$siano/scripts/verify-android-elf.sh"; do
    [ -f "$required" ] || {
        printf '%s\n' "archive is missing clean-room input: $required" >&2
        exit 1
    }
done
[ -x "$px4_build" ] && [ -x "$siano_build" ] || {
    printf '%s\n' 'archived Android build scripts must be executable' >&2
    exit 1
}

# Basename clients are covered by the guarded PATH below. Absolute clients,
# PATH-changing env invocations, and command -p/busybox bypasses would evade
# that guard and are rejected before any build starts.
python3 - "$px4_build" "$siano_build" <<'PY'
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
for network_tool in curl wget git; do
    cat >"$work/bin/$network_tool" <<'EOF'
#!/bin/sh
printf '%s\n' 'network access attempted during clean-room native rebuild' >&2
exit 97
EOF
    chmod 0755 "$work/bin/$network_tool"
done
guard_path=$work/bin:$(dirname "$(command -v cmake)"):$(dirname "$(command -v ninja)"):/usr/bin:/bin
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
done

printf '%s\n' 'clean-room Siano/PX4 static libusb relink slice: PASS'
