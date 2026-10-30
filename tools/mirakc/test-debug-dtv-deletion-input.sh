#!/bin/sh
set -eu

root=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd -P)
temporary=$(mktemp -d /tmp/mirakc-dtv-status-input-test.XXXXXX)
cleanup()
{
    rm -rf "$temporary"
}
trap cleanup EXIT HUP INT TERM

fixture=$temporary/dtv
mkdir "$fixture"
git -C "$fixture" init -q
git -C "$fixture" config user.name fixture
git -C "$fixture" config user.email fixture@example.invalid
printf '%s\n' tracked > "$fixture/tracked.txt"
git -C "$fixture" add tracked.txt
git -C "$fixture" commit -q -m fixture

run_fixture_task()
{
    JAVA_HOME=${JAVA_HOME:-/config/.tools/jdk17} \
        ANDROID_HOME=${ANDROID_HOME:-/config/.tools/android-sdk} \
        "$root/gradlew" --no-daemon -PdtvRoot="$fixture" \
        :mirakc:writeDebugDtvStatusInputFixture > "$temporary/gradle.log"
}

run_fixture_task
if grep -F 'UP-TO-DATE' "$temporary/gradle.log" >/dev/null; then
    printf '%s\n' 'initial fixture status task unexpectedly up-to-date' >&2
    exit 1
fi
run_fixture_task
grep -F 'UP-TO-DATE' "$temporary/gradle.log" >/dev/null

printf '%s\n' changed > "$fixture/tracked.txt"
git -C "$fixture" add tracked.txt
git -C "$fixture" commit -q -m 'change only the fixture HEAD'
[ -z "$(git -C "$fixture" status --porcelain=v1)" ]
run_fixture_task
if grep -F 'UP-TO-DATE' "$temporary/gradle.log" >/dev/null; then
    printf '%s\n' 'clean HEAD-only change did not invalidate the Gradle status input' >&2
    exit 1
fi
[ ! -s "$root/mirakc/build/tmp/debug-dtv-status-input-fixture.txt" ]
run_fixture_task
grep -F 'UP-TO-DATE' "$temporary/gradle.log" >/dev/null

printf '%s\n' 'modified one' > "$fixture/tracked.txt"
run_fixture_task
if grep -F 'UP-TO-DATE' "$temporary/gradle.log" >/dev/null; then
    printf '%s\n' 'first tracked modification did not invalidate the Gradle byte input' >&2
    exit 1
fi
base64 -d < "$root/mirakc/build/tmp/debug-dtv-status-input-fixture.txt" > "$temporary/status-one.bin"
printf ' M tracked.txt\0' > "$temporary/expected-status.bin"
cmp "$temporary/expected-status.bin" "$temporary/status-one.bin"

printf '%s\n' 'modified two' > "$fixture/tracked.txt"
run_fixture_task
if grep -F 'UP-TO-DATE' "$temporary/gradle.log" >/dev/null; then
    printf '%s\n' 'second tracked modification with unchanged M status did not invalidate the Gradle byte input' >&2
    exit 1
fi
base64 -d < "$root/mirakc/build/tmp/debug-dtv-status-input-fixture.txt" > "$temporary/status-two.bin"
cmp "$temporary/status-one.bin" "$temporary/status-two.bin"

rm -- "$fixture/tracked.txt"
run_fixture_task
if grep -F 'UP-TO-DATE' "$temporary/gradle.log" >/dev/null; then
    printf '%s\n' 'tracked deletion did not invalidate the Gradle status input' >&2
    exit 1
fi
status=$(base64 -d < "$root/mirakc/build/tmp/debug-dtv-status-input-fixture.txt")
case "$status" in
    *'D tracked.txt'*) ;;
    *) printf '%s\n' 'Gradle status input did not retain the tracked deletion' >&2; exit 1 ;;
esac

git -C "$fixture" checkout -- tracked.txt
run_fixture_task
if grep -F 'UP-TO-DATE' "$temporary/gradle.log" >/dev/null; then
    printf '%s\n' 'restored tracked file did not invalidate the Gradle status input' >&2
    exit 1
fi
[ ! -s "$root/mirakc/build/tmp/debug-dtv-status-input-fixture.txt" ]
run_fixture_task
grep -F 'UP-TO-DATE' "$temporary/gradle.log" >/dev/null

printf '%s\n' 'Debug DTV deletion input self-test: PASS'
