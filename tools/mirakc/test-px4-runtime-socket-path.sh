#!/bin/sh
set -eu

repo_dir=$(CDPATH=; cd -- "$(dirname -- "$0")/../.." && pwd)
if [ "$#" -ne 1 ] || [ ! -f "$1/adapters/host_runtime/src/control_socket.cpp" ]; then
    printf '%s\n' "usage: $0 <pinned-dtv-userland-core-checkout>" >&2
    exit 2
fi
core_dir=$1
expected_core_ref=67420d1c909e9205841612a186ae4c7fbf4dd092
actual_core_ref=$(git -C "$core_dir" rev-parse HEAD)
if [ "$actual_core_ref" != "$expected_core_ref" ]; then
    printf '%s\n' "dtv-userland-core HEAD mismatch: expected $expected_core_ref, found $actual_core_ref" >&2
    exit 1
fi
build_dir="$repo_dir/.work/host-px4-runtime-socket-path"
cmake -S "$repo_dir/mirakc/src/main/cpp" -B "$build_dir" -G Ninja \
    -DMIRAKC_BUILD_PX4_RUNTIME_SOCKET_PATH_TESTS=ON \
    -DDTV_USERLAND_CORE_DIR="$core_dir"
cmake --build "$build_dir" --target px4_runtime_socket_path_test
"$build_dir/px4_runtime_socket_path_test"
