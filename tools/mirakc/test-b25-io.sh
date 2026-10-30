#!/bin/sh
set -eu

repo_dir=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd -P)
cpp_dir="$repo_dir/mirakc/src/main/cpp"
build_dir="$repo_dir/.work/host-b25-io"
mkdir -p "$build_dir"

c++ -std=c++17 -Wall -Wextra -Werror \
    -I"$cpp_dir/tests/stubs" -I"$cpp_dir" -I"$cpp_dir/arib25" \
    -Dread=b25_test_read -Dwrite=b25_test_write \
    -c "$cpp_dir/b25_filter.cpp" -o "$build_dir/b25_filter.o"
c++ -std=c++17 -Wall -Wextra -Werror \
    -I"$cpp_dir/tests/stubs" -I"$cpp_dir" -I"$cpp_dir/arib25" \
    "$cpp_dir/tests/b25_filter_io_test.cpp" "$build_dir/b25_filter.o" \
    -o "$build_dir/b25_filter_io_test"
"$build_dir/b25_filter_io_test"

c++ -std=c++17 -Wall -Wextra -Werror -I"$cpp_dir" -Dmain=b25_filter_main_entry \
    -c "$cpp_dir/b25_filter_main.cpp" -o "$build_dir/b25_filter_main.o"
c++ -std=c++17 -Wall -Wextra -Werror -I"$cpp_dir" \
    "$cpp_dir/tests/b25_filter_main_exit_test.cpp" "$build_dir/b25_filter_main.o" \
    -pthread -o "$build_dir/b25_filter_main_exit_test"
"$build_dir/b25_filter_main_exit_test"

c++ -std=c++17 -Wall -Wextra -Werror \
    -I"$cpp_dir/tests/stubs" -I"$cpp_dir" -I"$cpp_dir/arib25" \
    -c "$cpp_dir/b25_filter.cpp" -o "$build_dir/b25_filter_real.o"
c++ -std=c++17 -Wall -Wextra -Werror \
    -I"$cpp_dir/tests/stubs" -I"$cpp_dir" -I"$cpp_dir/arib25" \
    "$cpp_dir/tests/b25_filter_sigpipe_test.cpp" \
    "$build_dir/b25_filter_real.o" "$build_dir/b25_filter_main.o" \
    -pthread -o "$build_dir/b25_filter_sigpipe_test"
"$build_dir/b25_filter_sigpipe_test"

c++ -std=c++17 -Wall -Wextra -Werror -I"$cpp_dir/tests/stubs" \
    -Dmain=siano_adapter_main_entry -Dread=host_read -Dwrite=host_write \
    -c "$cpp_dir/siano_adapter.cpp" -o "$build_dir/siano_adapter.o"
c++ -std=c++17 -Wall -Wextra -Werror -I"$cpp_dir/tests/stubs" \
    "$cpp_dir/tests/siano_adapter_io_test.cpp" "$build_dir/siano_adapter.o" \
    -pthread -o "$build_dir/siano_adapter_io_test"
"$build_dir/siano_adapter_io_test"

c++ -std=c++17 -Wall -Wextra -Werror -I"$cpp_dir/tests/stubs" \
    -Dmain=siano_adapter_entry -c "$cpp_dir/siano_adapter.cpp" \
    -o "$build_dir/siano_adapter_terminal.o"
c++ -std=c++17 -Wall -Wextra -Werror -I"$cpp_dir/tests/stubs" \
    "$cpp_dir/tests/siano_adapter_terminal_host.cpp" "$build_dir/siano_adapter_terminal.o" \
    -pthread -o "$build_dir/siano_adapter_terminal_host"
"$build_dir/siano_adapter_terminal_host" --self-test \
    "$repo_dir/mirakc/src/test/resources/siano-terminal"
