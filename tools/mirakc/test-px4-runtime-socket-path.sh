#!/bin/sh
set -eu

repo_dir=$(CDPATH=; cd -- "$(dirname -- "$0")/../.." && pwd)
if [ "$#" -ne 1 ] || [ ! -f "$1/userland/src/posix_ipc.cpp" ]; then
    printf '%s\n' "usage: $0 <pinned-px4-userland-checkout>" >&2
    exit 2
fi
px4_userland_dir=$1
expected_px4_ref=cf38742618bb02db41a95def619fbff50e9eb0f3
actual_px4_ref=$(git -C "$px4_userland_dir" rev-parse HEAD)
if [ "$actual_px4_ref" != "$expected_px4_ref" ]; then
    printf '%s\n' "px4-userland HEAD mismatch: expected $expected_px4_ref, found $actual_px4_ref" >&2
    exit 1
fi
build_dir="$repo_dir/.work/host-px4-runtime-socket-path"
mkdir -p "$build_dir"

c++ -std=c++17 -Wall -Wextra -Werror \
    -I"$px4_userland_dir/userland/include" \
    "$repo_dir/mirakc/src/main/cpp/tests/px4_runtime_socket_path_test.cpp" \
    "$px4_userland_dir/userland/src/posix_ipc.cpp" \
    "$px4_userland_dir/userland/src/ipc.cpp" \
    "$px4_userland_dir/userland/src/error.cpp" \
    -o "$build_dir/px4_runtime_socket_path_test"
"$build_dir/px4_runtime_socket_path_test"
