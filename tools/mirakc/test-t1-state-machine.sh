#!/bin/sh
set -eu

repo_dir=$(CDPATH=; cd -- "$(dirname -- "$0")/../.." && pwd)
build_dir="$repo_dir/.work/host-t1-state-machine"
mkdir -p "$build_dir"

cc -std=c11 -Wall -Wextra -Werror \
    -I"$repo_dir/mirakc/src/main/cpp" \
    -c "$repo_dir/mirakc/src/main/cpp/t1_state_machine.c" \
    -o "$build_dir/t1_state_machine.o"

c++ -std=c++17 -Wall -Wextra -Werror \
    -I"$repo_dir/mirakc/src/main/cpp" \
    "$repo_dir/mirakc/src/main/cpp/tests/t1_state_machine_test.cpp" \
    "$build_dir/t1_state_machine.o" \
    -o "$build_dir/t1_state_machine_test"
"$build_dir/t1_state_machine_test"
