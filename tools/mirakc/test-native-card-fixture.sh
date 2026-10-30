#!/bin/sh
set -eu

repo_dir=$(CDPATH=; cd -- "$(dirname -- "$0")/../.." && pwd)
if [ "$#" -ne 5 ] || [ ! -f "$1/CMakeLists.txt" ]; then
    printf '%s\n' "usage: $0 <dtv-userland-core-copy> <build-dir> <baseline|patched> <px4-userland> <asicen-userland>" >&2
    exit 2
fi
core_dir=$1
build_dir=$2
mode=$3
px4_dir=$4
asicen_dir=$5
case "$mode" in
    baseline|patched) ;;
    *) printf '%s\n' "mode must be baseline or patched" >&2; exit 2 ;;
esac

cmake -S "$repo_dir/mirakc/src/main/cpp" -B "$build_dir" -G Ninja \
    -DMIRAKC_BUILD_ADAPTER_CLI_TESTS=ON \
    -DMIRAKC_BUILD_USERLAND_CONCURRENCY_TESTS=ON \
    -DMIRAKC_BUILD_NATIVE_CARD_FIXTURE_TESTS=ON \
    -DDTV_USERLAND_CORE_DIR="$core_dir" \
    -DPX4_USERLAND_DIR="$px4_dir" \
    -DASICEN_USERLAND_DIR="$asicen_dir"
cmake --build "$build_dir" --target \
    adapter_cli_test userland_concurrency_test userland_worker_resource_test \
    px4_card_fixture_test asicen_card_fixture_test card_ownership_engine_tests \
    host_worker_closure_tests host_authority_owner_tests host_authority_worker_tests \
    host_authority_bridge_tests host_listener_context_seam_tests \
    host_listener_context_tests host_listener_context_integration_tests -j4

"$build_dir/adapter_cli_test"
if [ "$mode" = baseline ]; then
    "$build_dir/userland_concurrency_test" --expect-blocked
    "$build_dir/userland_worker_resource_test" --baseline
else
    "$build_dir/userland_concurrency_test"
    "$build_dir/userland_worker_resource_test"
fi
"$build_dir/px4_card_fixture_test"
"$build_dir/asicen_card_fixture_test"
core_build="$build_dir/dtv-userland-core"
for test_name in card_ownership_engine_tests host_worker_closure_tests \
    host_authority_owner_tests host_authority_worker_tests \
    host_authority_bridge_tests host_listener_context_seam_tests \
    host_listener_context_tests host_listener_context_integration_tests; do
    "$core_build/$test_name"
done
