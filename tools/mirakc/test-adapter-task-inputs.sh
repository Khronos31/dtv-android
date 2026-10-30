#!/bin/sh
set -eu

root=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd -P)
set --
if [ -n "${MIRAKC_TEST_DTV_ROOT:-}" ]; then
    set -- "-PdtvRoot=$MIRAKC_TEST_DTV_ROOT"
fi
JAVA_HOME=${JAVA_HOME:-/config/.tools/jdk17} \
    ANDROID_HOME=${ANDROID_HOME:-/config/.tools/android-sdk} \
    "$root/gradlew" --no-daemon --offline --max-workers=1 \
        --init-script "$root/tools/mirakc/adapter-task-inputs.gradle" "$@" \
        :mirakc:writeDebugDtvStatusInputFixture help
