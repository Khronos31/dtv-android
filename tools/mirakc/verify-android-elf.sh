#!/bin/sh
# SPDX-License-Identifier: MIT OR Apache-2.0
set -eu

binary=${1:-}
abi=${2:-}
kind=${3:-pie}
[ -f "$binary" ] || { printf '%s\n' "ELF not found: $binary" >&2; exit 2; }
case "$abi" in
arm64-v8a) expected_machine=AArch64; expected_interp=/system/bin/linker64 ;;
armeabi-v7a) expected_machine=ARM; expected_interp=/system/bin/linker ;;
*) printf '%s\n' "unsupported ABI: $abi" >&2; exit 2 ;;
esac
case "$kind" in
pie) expected_type='DYN (Position-Independent Executable file)'; kind_label=PIE ;;
shared) expected_type='DYN (Shared object file)'; kind_label='shared' ;;
*) printf '%s\n' "unsupported ELF kind: $kind" >&2; exit 2 ;;
esac
readelf_bin=${READELF:-$(command -v readelf || true)}
[ -x "$readelf_bin" ] || { printf '%s\n' 'readelf is required' >&2; exit 1; }

header=$($readelf_bin -h "$binary")
printf '%s\n' "$header" | grep -F "Type:                              $expected_type" >/dev/null \
    || { printf '%s\n' "ELF type mismatch (expected $kind)" >&2; exit 1; }
printf '%s\n' "$header" | grep -F "Machine:                           $expected_machine" >/dev/null \
    || { printf '%s\n' "unexpected machine (expected $expected_machine)" >&2; exit 1; }
program=$($readelf_bin -lW "$binary")
if [ "$kind" = pie ]; then
    printf '%s\n' "$program" | grep -F "[Requesting program interpreter: $expected_interp]" >/dev/null \
        || { printf '%s\n' "unexpected interpreter (expected $expected_interp)" >&2; exit 1; }
else
    if printf '%s\n' "$program" | grep -F ' INTERP ' >/dev/null; then
        printf '%s\n' 'shared ELF must not have a program interpreter' >&2
        exit 1
    fi
fi
alignments=$(printf '%s\n' "$program" | awk '$1 == "LOAD" { print $NF }')
[ -n "$alignments" ] || { printf '%s\n' 'ELF has no PT_LOAD segments' >&2; exit 1; }
for alignment in $alignments; do
    case "$alignment" in
    0x*) value=$((alignment)) ;;
    *) printf '%s\n' "unexpected PT_LOAD alignment: $alignment" >&2; exit 1 ;;
    esac
    [ "$value" -ge 16384 ] || { printf '%s\n' "PT_LOAD below 16 KiB: $alignment" >&2; exit 1; }
    [ $((value & (value - 1))) -eq 0 ] || { printf '%s\n' "PT_LOAD alignment is not a power of two: $alignment" >&2; exit 1; }
done

dynamic=$($readelf_bin -d "$binary")
if printf '%s\n' "$dynamic" | grep -E '\((RPATH|RUNPATH)\)' >/dev/null; then
    printf '%s\n' 'RPATH/RUNPATH is forbidden' >&2
    exit 1
fi
allowed='libc.so libdl.so libm.so liblog.so libandroid.so libatomic.so libc++_shared.so'
needed=$(printf '%s\n' "$dynamic" | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p')
for library in $needed; do
    case " $allowed " in
    *" $library "*) ;;
    *) printf '%s\n' "unapproved DT_NEEDED library: $library" >&2; exit 1 ;;
    esac
done
printf '%s\n' "verified $binary ($abi): Android $kind_label, 16 KiB PT_LOAD, no RPATH/RUNPATH, allowed Bionic dependencies"
