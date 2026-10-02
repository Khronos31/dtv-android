#!/bin/sh
set -eu

root=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd -P)
build_dir="$root/.work/host-ccid-log-redaction"
mkdir -p "$build_dir"

cc -std=c11 -D_DEFAULT_SOURCE -Wall -Wextra -Werror \
    -I"$root/mirakc/src/main/cpp/tests/stubs" \
    -I"$root/mirakc/src/main/cpp" \
    "$root/mirakc/src/main/cpp/tests/ccid_reader_redaction_test.c" \
    "$root/mirakc/src/main/cpp/t1_state_machine.c" \
    -o "$build_dir/ccid_reader_redaction_test"
"$build_dir/ccid_reader_redaction_test"
