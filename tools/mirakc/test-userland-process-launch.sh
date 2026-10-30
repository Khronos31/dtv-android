#!/bin/sh
set -eu

repo_dir=$(CDPATH=; cd -- "$(dirname -- "$0")/../.." && pwd)
build_dir=$(mktemp -d)
trap 'rm -rf "$build_dir"' EXIT HUP INT TERM

"${CXX:-c++}" -std=c++17 -Wall -Wextra -Werror -pthread \
    -I "$repo_dir/mirakc/src/main/cpp" \
    "$repo_dir/mirakc/src/main/cpp/tests/userland_process_launcher_test.cpp" \
    -o "$build_dir/userland_process_launcher_test"
"$build_dir/userland_process_launcher_test"
