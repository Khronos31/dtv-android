#!/bin/sh
# SPDX-License-Identifier: MIT OR Apache-2.0
set -eu

apk=${1:-}
[ -n "$apk" ] || { printf '%s\n' "usage: $0 APK" >&2; exit 2; }
[ -f "$apk" ] || { printf '%s\n' "APK not found: $apk" >&2; exit 2; }

script_dir=$(cd -- "$(dirname -- "$0")" && pwd)
verifier=${ELF_VERIFIER:-$script_dir/verify-android-elf.sh}
[ -x "$verifier" ] || { printf '%s\n' "ELF verifier is not executable: $verifier" >&2; exit 2; }
unzip_bin=${UNZIP:-$(command -v unzip || true)}
[ -x "$unzip_bin" ] || { printf '%s\n' 'unzip is required' >&2; exit 1; }

tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/mirakc-apk-audit.XXXXXX")
cleanup() {
    rm -rf "$tmp_dir"
}
trap cleanup EXIT HUP INT TERM
umask 077

entries_file=$tmp_dir/entries
if ! "$unzip_bin" -Z1 "$apk" >"$entries_file"; then
    printf '%s\n' "cannot list APK: $apk" >&2
    exit 1
fi

# Selective extraction below never asks unzip to materialize untrusted paths.
# Reject traversal-looking entries up front even though only fixed native paths
# are extracted, so malformed APKs fail closed rather than being ambiguous.
while IFS= read -r entry || [ -n "$entry" ]; do
    case "$entry" in
    /*|*/../*|../*|*/..|*//* )
        printf '%s\n' "unsafe APK entry: $entry" >&2
        exit 1
        ;;
    esac
done <"$entries_file"

expected_names='libmirakc.so libmirakc-arib.so libsiano-ts.so libpx4d.so libpx4-ts.so libpx4ctl.so libusb_process.so libmirakc-siano-adapter.so libmirakc-b25-filter.so libmirakc-px4-adapter.so libmirakc-px4-fwtool.so'
abis='arm64-v8a armeabi-v7a'

is_expected_name() {
    case " $expected_names " in
    *" $1 "*) return 0 ;;
    *) return 1 ;;
    esac
}

for abi in $abis; do
    mkdir -p "$tmp_dir/$abi"
done

# The native inventory is exact: only the two enabled ABIs and the complete
# expected payload may occur below lib/. Other APK content is not relevant.
while IFS= read -r entry || [ -n "$entry" ]; do
    case "$entry" in
    lib/*)
        rest=${entry#lib/}
        abi=${rest%%/*}
        name=${rest#*/}
        [ "$name" != "$rest" ] || {
            [ -z "$rest" ] || {
                printf '%s\n' "invalid native entry: $entry" >&2
                exit 1
            }
            continue
        }
        case "$abi" in
        arm64-v8a|armeabi-v7a) ;;
        *) printf '%s\n' "unexpected native ABI: $abi" >&2; exit 1 ;;
        esac
        [ -n "$name" ] || continue
        case "$name" in
        */*) printf '%s\n' "nested native entry: $entry" >&2; exit 1 ;;
        esac
        is_expected_name "$name" || {
            printf '%s\n' "unexpected native payload: $entry" >&2
            exit 1
        }
        ;;
    esac
done <"$entries_file"

for abi in $abis; do
    for name in $expected_names; do
        entry="lib/$abi/$name"
        count=$(awk -v wanted="$entry" '$0 == wanted { count++ } END { print count + 0 }' "$entries_file")
        [ "$count" -eq 1 ] || {
            printf '%s\n' "expected native entry count for $entry is 1, got $count" >&2
            exit 1
        }
        output="$tmp_dir/$abi/$name"
        "$unzip_bin" -p "$apk" "$entry" >"$output"
        [ -s "$output" ] || {
            printf '%s\n' "empty native payload: $entry" >&2
            exit 1
        }
        if [ "$name" = libusb_process.so ]; then
            "$verifier" "$output" "$abi" shared
        else
            "$verifier" "$output" "$abi"
        fi
    done
done

printf '%s\n' "APK native inventory verified: $apk"
