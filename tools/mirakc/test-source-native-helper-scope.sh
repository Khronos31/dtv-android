#!/bin/sh
set -eu

root=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd -P)
MIRAKC_TEST_HELPER_SCOPE_ONLY=1 sh "$root/tools/mirakc/test-source-native-rebuild.sh"
