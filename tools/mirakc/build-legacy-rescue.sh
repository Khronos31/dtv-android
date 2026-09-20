#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

usage() {
    echo "usage: $0 --legacy-root DIR --siano-root DIR --out DIR" >&2
    exit 2
}

legacy_root=
siano_root=
out_dir=
while (($#)); do
    case "$1" in
        --legacy-root) [[ $# -ge 2 ]] || usage; legacy_root=$2; shift 2 ;;
        --siano-root) [[ $# -ge 2 ]] || usage; siano_root=$2; shift 2 ;;
        --out) [[ $# -ge 2 ]] || usage; out_dir=$2; shift 2 ;;
        *) usage ;;
    esac
done
[[ -n "$legacy_root" && -n "$siano_root" && -n "$out_dir" ]] || usage

readonly legacy_ref="mirakc-v0.2.0"
readonly legacy_commit="5a4d647c9e4b46f3f637165fa107f87d34ea22ed"
readonly siano_ref="v0.1.1"
readonly siano_commit="1a22a7180abd6c7be1d1dda6b866ec321a4e28ab"
readonly rescue_version="0.3.1"
readonly rescue_code="301"

require_clean_ref() {
    local root=$1 ref=$2 expected=$3 actual peeled ref_type
    git -C "$root" rev-parse --git-dir >/dev/null 2>&1 \
        || { echo "not a git checkout: $root" >&2; exit 1; }
    [[ -z "$(git -C "$root" status --porcelain --untracked-files=all)" ]] \
        || { echo "checkout is dirty: $root" >&2; exit 1; }
    ref_type=$(git -C "$root" cat-file -t "refs/tags/$ref" 2>/dev/null || true)
    [[ "$ref_type" == "tag" ]] \
        || { echo "$root is missing annotated tag $ref" >&2; exit 1; }
    actual=$(git -C "$root" rev-parse "HEAD^{commit}")
    peeled=$(git -C "$root" rev-parse "${ref}^{commit}")
    [[ "$actual" == "$expected" && "$peeled" == "$expected" ]] \
        || { echo "$root is not $ref peeled commit $expected (HEAD=$actual peeled=$peeled)" >&2; exit 1; }
}

require_clean_ref "$legacy_root" "$legacy_ref" "$legacy_commit"
require_clean_ref "$siano_root" "$siano_ref" "$siano_commit"
mkdir -p "$out_dir"
for output in \
    "$out_dir/mirakc-legacy-server-rescue-0.3.1-unsigned.apk" \
    "$out_dir/mirakc-legacy-server-rescue-0.3.1-unsigned.sha256" \
    "$out_dir/RESCUE_BUILD_INFO.txt"; do
    [[ ! -e "$output" ]] || { echo "refusing to reuse stale rescue output: $output" >&2; exit 1; }
done
work=$(mktemp -d "${TMPDIR:-/tmp}/mirakc-legacy-rescue.XXXXXX")
trap 'rm -rf -- "$work"' EXIT HUP INT TERM

legacy_build="$work/dtv-android"
legacy_siano="$work/siano-userland"
mkdir -p "$legacy_build" "$legacy_siano"
git -C "$legacy_root" archive --format=tar "$legacy_commit" | tar -xf - -C "$legacy_build"
git -C "$siano_root" archive --format=tar "$siano_commit" | tar -xf - -C "$legacy_siano"

printf '%s\n' "$rescue_version" > "$legacy_build/mirakc/VERSION"
rescue_asset="$legacy_build/mirakc/src/main/assets/BUILD_INFO-legacy-server-rescue.txt"
mkdir -p "$(dirname -- "$rescue_asset")"
printf '%s\n' \
    "kind=legacy-server-rescue" \
    "package=dev.khronos31.mirakc" \
    "version_name=$rescue_version" \
    "version_code=$rescue_code" \
    "legacy_dtv_ref=$legacy_ref" \
    "legacy_dtv_commit=$legacy_commit" \
    "legacy_siano_ref=$siano_ref" \
    "legacy_siano_commit=$siano_commit" \
    "not-a-normal-release=true" > "$rescue_asset"

(
    cd "$legacy_build"
    ./gradlew --no-daemon -PsianoUserlandDir="$legacy_siano" :mirakc:assembleRelease
)

unsigned="$legacy_build/mirakc/build/outputs/apk/release/mirakc-release-unsigned.apk"
[[ -f "$unsigned" ]] || { echo "legacy rescue APK was not produced: $unsigned" >&2; exit 1; }
cp -- "$unsigned" "$out_dir/mirakc-legacy-server-rescue-0.3.1-unsigned.apk"
sha256sum "$out_dir/mirakc-legacy-server-rescue-0.3.1-unsigned.apk" > "$out_dir/mirakc-legacy-server-rescue-0.3.1-unsigned.sha256"
{
    echo "kind=legacy-server-rescue"
    echo "artifact=mirakc-legacy-server-rescue-0.3.1-unsigned.apk"
    echo "package=dev.khronos31.mirakc"
    echo "version_name=$rescue_version"
    echo "version_code=$rescue_code"
    echo "legacy_dtv_ref=$legacy_ref"
    echo "legacy_dtv_commit=$legacy_commit"
    echo "legacy_siano_ref=$siano_ref"
    echo "legacy_siano_commit=$siano_commit"
    echo "unsigned_apk_sha256=$(sha256sum "$unsigned" | awk '{print $1}')"
    echo "asset=BUILD_INFO-legacy-server-rescue.txt"
} > "$out_dir/RESCUE_BUILD_INFO.txt"
