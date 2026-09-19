#!/bin/sh
set -eu

repo_dir=$(CDPATH=; cd -- "$(dirname -- "$0")/../.." && pwd)
build_dir="$repo_dir/.work/host-px4-tune-plan"
mkdir -p "$build_dir"

c++ -std=c++17 -Wall -Wextra -Werror \
    -I"$repo_dir/mirakc/src/main/cpp" \
    "$repo_dir/mirakc/src/main/cpp/px4_tune_plan.cpp" \
    "$repo_dir/mirakc/src/main/cpp/tests/px4_tune_plan_test.cpp" \
    -o "$build_dir/px4_tune_plan_test"
"$build_dir/px4_tune_plan_test"

c++ -std=c++17 -Wall -Wextra -Werror \
    -I"$repo_dir/mirakc/src/main/cpp" \
    "$repo_dir/mirakc/src/main/cpp/tests/px4_card_retry_test.cpp" \
    -o "$build_dir/px4_card_retry_test"
"$build_dir/px4_card_retry_test"
