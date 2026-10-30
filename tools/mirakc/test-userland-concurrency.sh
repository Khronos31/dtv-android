#!/bin/sh
set -eu

repo_dir=$(CDPATH=; cd -- "$(dirname -- "$0")/../.." && pwd)
if [ "$#" -ne 3 ] || [ ! -f "$1/CMakeLists.txt" ]; then
    printf '%s\n' "usage: $0 <dtv-userland-core-checkout> <build-dir> <baseline|patched>" >&2
    exit 2
fi
core_dir=$1
build_dir=$2
mode=$3
case "$mode" in
    baseline|patched) ;;
    *) printf '%s\n' "mode must be baseline or patched" >&2; exit 2 ;;
esac

cmake -S "$repo_dir/mirakc/src/main/cpp" -B "$build_dir" -G Ninja \
    -DMIRAKC_BUILD_ADAPTER_CLI_TESTS=ON \
    -DMIRAKC_BUILD_USERLAND_CONCURRENCY_TESTS=ON \
    -DDTV_USERLAND_CORE_DIR="$core_dir"
cmake --build "$build_dir" --target \
    adapter_cli_test userland_concurrency_test userland_worker_resource_test \
    host_worker_closure_tests -j4
"$build_dir/adapter_cli_test"
if [ "$mode" = baseline ]; then
    "$build_dir/userland_concurrency_test" --expect-blocked
    "$build_dir/userland_worker_resource_test" --baseline
else
    "$build_dir/userland_concurrency_test"
    "$build_dir/userland_worker_resource_test"
fi
"$build_dir/dtv-userland-core/host_worker_closure_tests"
