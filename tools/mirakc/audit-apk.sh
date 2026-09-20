#!/bin/sh
# SPDX-License-Identifier: MIT OR Apache-2.0
set -eu

apk=${1:-}
[ -n "$apk" ] || { printf '%s\n' "usage: $0 APK [expected-dtv-commit]" >&2; exit 2; }
[ -f "$apk" ] || { printf '%s\n' "APK not found: $apk" >&2; exit 2; }
script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd -P)
expected=${2:-}

"$script_dir/audit-apk-native.sh" "$apk"
if [ -n "$expected" ]; then
    python3 "$script_dir/audit-apk-metadata.py" --expected-dtv-commit "$expected" "$apk"
else
    python3 "$script_dir/audit-apk-metadata.py" "$apk"
fi
