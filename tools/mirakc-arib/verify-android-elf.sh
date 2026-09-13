#!/bin/sh
# Verify the Android executable contract for the mirakc-arib gate.
set -eu

binary=${1:-}
abi=${2:-}
[ -f "$binary" ] || { printf '%s\n' "ELF not found: $binary" >&2; exit 2; }
case "$abi" in
armeabi-v7a) expected_machine=ARM; expected_interp=/system/bin/linker ;;
arm64-v8a) expected_machine=AArch64; expected_interp=/system/bin/linker64 ;;
*) printf '%s\n' "unsupported ABI: $abi" >&2; exit 2 ;;
esac
readelf_bin=${READELF:-$(command -v readelf || true)}
[ -x "$readelf_bin" ] || { printf '%s\n' 'readelf is required' >&2; exit 1; }

header=$($readelf_bin -h "$binary")
printf '%s\n' "$header" | grep -F 'Type:                              DYN (Position-Independent Executable file)' >/dev/null \
    || { printf '%s\n' 'ELF is not a PIE DYN executable' >&2; exit 1; }
printf '%s\n' "$header" | grep -F "Machine:                           $expected_machine" >/dev/null \
    || { printf '%s\n' "unexpected machine (expected $expected_machine)" >&2; exit 1; }
program=$($readelf_bin -lW "$binary")
printf '%s\n' "$program" | grep -F "[Requesting program interpreter: $expected_interp]" >/dev/null \
    || { printf '%s\n' "unexpected Android interpreter (expected $expected_interp)" >&2; exit 1; }
alignments=$(printf '%s\n' "$program" | awk '$1 == "LOAD" { print $NF }')
[ -n "$alignments" ] || { printf '%s\n' 'ELF has no PT_LOAD segments' >&2; exit 1; }
for alignment in $alignments; do
    case "$alignment" in
    0x*) value=$((alignment)) ;;
    *) printf '%s\n' "unexpected PT_LOAD alignment: $alignment" >&2; exit 1 ;;
    esac
    [ "$value" -ge 16384 ] || { printf '%s\n' "PT_LOAD alignment below 16 KiB: $alignment" >&2; exit 1; }
    [ $((value & (value - 1))) -eq 0 ] || { printf '%s\n' "PT_LOAD alignment is not a power of two: $alignment" >&2; exit 1; }
done

dynamic=$($readelf_bin -d "$binary")
if printf '%s\n' "$dynamic" | grep -E '\((RPATH|RUNPATH)\)' >/dev/null; then
    printf '%s\n' 'RPATH/RUNPATH is forbidden in Android payloads' >&2
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
printf '%s\n' "verified $binary ($abi): PIE, $expected_interp, 16 KiB PT_LOAD, no RPATH/RUNPATH, allowed Bionic dependencies"
