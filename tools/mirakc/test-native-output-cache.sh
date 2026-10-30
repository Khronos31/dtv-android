#!/bin/sh
set -eu

root=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd -P)
mirakc_source=${MIRAKC_TEST_MIRAKC_ROOT:-$root/.work/mirakc-3.4.91}
arib_source=${MIRAKC_TEST_ARIB_ROOT:-$root/.work/mirakc-arib-0.24.40}
cmake_bin=${MIRAKC_TEST_CMAKE_BIN:-$(command -v cmake || true)}
fixture=$(mktemp -d "${TMPDIR:-/tmp}/mirakc-native-cache.XXXXXX")
cleanup()
{
    status=$?
    trap - EXIT HUP INT TERM
    if [ "$status" -ne 0 ]; then
        for log in "$fixture"/*.log; do
            [ -f "$log" ] || continue
            printf '%s\n' "--- $log ---" >&2
            cat "$log" >&2
        done
    fi
    rm -rf "$fixture"
    exit "$status"
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM

[ -d "$mirakc_source/.git" ] || { printf '%s\n' "mirakc source checkout missing: $mirakc_source" >&2; exit 1; }
[ -d "$arib_source/.git" ] || { printf '%s\n' "mirakc-arib source checkout missing: $arib_source" >&2; exit 1; }
[ -x "$cmake_bin/cmake" ] && [ -x "$cmake_bin/ninja" ] || { printf '%s\n' 'pinned CMake and Ninja are required' >&2; exit 1; }

ndk=$fixture/ndk/27.0.12077973
toolchain=$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin
mkdir -p "$toolchain"
printf '%s\n' 'Pkg.Desc = Android NDK' 'Pkg.Revision = 27.0.12077973' > "$ndk/source.properties"
for tool in clang clang++ llvm-ar llvm-strip; do
    printf '#!/bin/sh\nexit 0\n' > "$toolchain/$tool"
    chmod 0755 "$toolchain/$tool"
done

run_gradle()
{
    MIRAKC_CACHE_FIXTURE_CACHE=$fixture/cache \
    MIRAKC_CACHE_FIXTURE_OUTPUTS=$fixture/outputs \
    ANDROID_NDK_HOME=$ndk \
    CARGO_BUILD_JOBS=1 \
    PATH=$cmake_bin:$PATH \
        "$root/gradlew" --no-daemon --offline --max-workers=1 --build-cache \
        --init-script "$root/tools/mirakc/native-cache-fixture.init.gradle" \
        -PciNativeCache=true \
        -PmirakcNativeCacheFixtureOutputDir="$fixture/outputs" \
        -PmirakcSourceDir="$mirakc_source" \
        -PmirakcAribSourceDir="$arib_source" \
        "$@"
}

log=$fixture/cold.log
run_gradle :mirakc:prepareMirakcBinary :mirakc:prepareMirakcAribBinaries >"$log" 2>&1
grep -F '> Task :mirakc:prepareMirakcBinary' "$log" | grep -v 'FROM-CACHE' >/dev/null
grep -F '> Task :mirakc:prepareMirakcAribBinaries' "$log" | grep -v 'FROM-CACHE' >/dev/null
[ -s "$fixture/outputs/prepareMirakcBinary.so" ] && [ -s "$fixture/outputs/prepareMirakcAribBinaries.so" ]

rm -rf "$fixture/outputs"
log=$fixture/warm.log
run_gradle :mirakc:prepareMirakcBinary :mirakc:prepareMirakcAribBinaries >"$log" 2>&1
grep -F ':mirakc:prepareMirakcBinary FROM-CACHE' "$log" >/dev/null
grep -F ':mirakc:prepareMirakcAribBinaries FROM-CACHE' "$log" >/dev/null

printf '%s\n' '#!/bin/sh' 'exit 0' 'toolchain-byte-change' > "$toolchain/clang"
rm -rf "$fixture/outputs"
log=$fixture/toolchain-byte-change.log
run_gradle :mirakc:prepareMirakcBinary :mirakc:prepareMirakcAribBinaries >"$log" 2>&1
grep -F '> Task :mirakc:prepareMirakcBinary' "$log" | grep -v 'FROM-CACHE' >/dev/null
grep -F '> Task :mirakc:prepareMirakcAribBinaries' "$log" | grep -v 'FROM-CACHE' >/dev/null

clone_source()
{
    source=$1
    destination=$2
    git clone --quiet --shared --no-checkout "$source" "$destination"
    git -C "$destination" checkout --quiet --detach "$(git -C "$source" rev-parse HEAD)"
    git -C "$destination" config user.name cache-fixture
    git -C "$destination" config user.email cache-fixture@example.invalid
}

clone_source "$mirakc_source" "$fixture/wrong-pin"
git -C "$fixture/wrong-pin" commit --quiet --allow-empty -m wrong-pin
if run_gradle -PmirakcSourceDir="$fixture/wrong-pin" :mirakc:prepareMirakcBinary >"$fixture/wrong-pin.log" 2>&1; then
    printf '%s\n' 'wrong mirakc source pin unexpectedly passed cache validation' >&2
    exit 1
fi
grep -F 'mirakc cache source pin mismatch' "$fixture/wrong-pin.log" >/dev/null

clone_source "$mirakc_source" "$fixture/dirty-source"
printf '\n' >> "$fixture/dirty-source/Cargo.toml"
if run_gradle -PmirakcSourceDir="$fixture/dirty-source" :mirakc:prepareMirakcBinary >"$fixture/dirty-source.log" 2>&1; then
    printf '%s\n' 'unstaged source modification unexpectedly passed cache validation' >&2
    exit 1
fi
grep -F 'mirakc cache source checkout must be clean' "$fixture/dirty-source.log" >/dev/null

clone_source "$mirakc_source" "$fixture/staged-source"
printf '\n' >> "$fixture/staged-source/Cargo.toml"
git -C "$fixture/staged-source" add Cargo.toml
if run_gradle -PmirakcSourceDir="$fixture/staged-source" :mirakc:prepareMirakcBinary >"$fixture/staged-source.log" 2>&1; then
    printf '%s\n' 'staged source modification unexpectedly passed cache validation' >&2
    exit 1
fi
grep -F 'mirakc cache source checkout must be clean' "$fixture/staged-source.log" >/dev/null

if CARGO_BUILD_RUSTFLAGS='-C opt-level=0' run_gradle :mirakc:prepareMirakcBinary >"$fixture/cargo-build-rustflags.log" 2>&1; then
    printf '%s\n' 'Cargo build rustflags override unexpectedly passed cache validation' >&2
    exit 1
fi
grep -F 'CARGO_BUILD_RUSTFLAGS' "$fixture/cargo-build-rustflags.log" >/dev/null

if CARGO_PROFILE_RELEASE_OPT_LEVEL=1 CARGO_PROFILE_RELEASE_DEBUG=1 \
    run_gradle :mirakc:prepareMirakcBinary >"$fixture/cargo-profile.log" 2>&1; then
    printf '%s\n' 'Cargo release profile override unexpectedly passed cache validation' >&2
    exit 1
fi
grep -F 'CARGO_PROFILE_RELEASE_OPT_LEVEL' "$fixture/cargo-profile.log" >/dev/null
grep -F 'CARGO_PROFILE_RELEASE_DEBUG' "$fixture/cargo-profile.log" >/dev/null

clone_source "$mirakc_source" "$fixture/project-cargo-config"
mkdir -p "$fixture/project-cargo-config/.cargo"
printf '%s\n' '[build]' 'rustflags = ["-C", "opt-level=0"]' > "$fixture/project-cargo-config/.cargo/config.toml"
if run_gradle -PmirakcSourceDir="$fixture/project-cargo-config" :mirakc:prepareMirakcBinary >"$fixture/project-cargo-config.log" 2>&1; then
    printf '%s\n' 'project Cargo config unexpectedly passed cache validation' >&2
    exit 1
fi
grep -F 'Cargo configuration overrides are unsupported' "$fixture/project-cargo-config.log" >/dev/null

cargo_home=$fixture/cargo-home
mkdir -p "$cargo_home"
printf '%s\n' '[build]' 'rustflags = ["-C", "opt-level=0"]' > "$cargo_home/config"
if CARGO_HOME=$cargo_home run_gradle :mirakc:prepareMirakcBinary >"$fixture/cargo-home-config.log" 2>&1; then
    printf '%s\n' 'Cargo home config unexpectedly passed cache validation' >&2
    exit 1
fi
grep -F 'Cargo configuration overrides are unsupported' "$fixture/cargo-home-config.log" >/dev/null

if MIRAKC_SOURCE_REF=other-pinned-ref run_gradle :mirakc:prepareMirakcBinary >"$fixture/source-ref-override.log" 2>&1; then
    printf '%s\n' 'mirakc source ref override unexpectedly passed cache validation' >&2
    exit 1
fi
grep -F 'MIRAKC_SOURCE_REF' "$fixture/source-ref-override.log" >/dev/null

if ANDROID_ABI=arm64-v8a run_gradle :mirakc:prepareMirakcAribBinaries >"$fixture/abi-override.log" 2>&1; then
    printf '%s\n' 'exported Android ABI unexpectedly passed ARIB cache validation' >&2
    exit 1
fi
grep -F 'ANDROID_ABI' "$fixture/abi-override.log" >/dev/null

source_file=$(git -C "$arib_source/vendor/tsduck-arib" ls-files | head -n 1)
[ -n "$source_file" ] || { printf '%s\n' 'ARIB nested tracked fixture file was not found' >&2; exit 1; }
parent_dtv=$fixture/parent-dtv
mkdir -p "$parent_dtv/.work"
git -c init.defaultBranch=main -C "$parent_dtv" init -q
git -C "$parent_dtv" config user.name cache-fixture
git -C "$parent_dtv" config user.email cache-fixture@example.invalid
printf '%s\n' parent-baseline > "$parent_dtv/parent-sentinel.txt"
git -C "$parent_dtv" add parent-sentinel.txt
git -C "$parent_dtv" commit -q -m parent-baseline
printf '%s\n' parent-dirty > "$parent_dtv/parent-sentinel.txt"
immutable_source=$parent_dtv/.work/immutable-source
cp -a "$arib_source" "$immutable_source"
printf '%s\n' immutable-source-dirty > "$immutable_source/CMakeLists.txt"
arib_build_source=$parent_dtv/.work/mirakc-arib-build-source-arm64-v8a.fixture
mkdir "$arib_build_source"
/bin/sh "$root/tools/mirakc-arib/prepare-build-source.sh" "$immutable_source" "$arib_build_source" "$parent_dtv/.work"
[ ! -e "$arib_build_source/.git" ]
inherited_root=$(git -C "$arib_build_source" rev-parse --show-toplevel)
[ "$inherited_root" = "$parent_dtv" ]
for vendor in spdlog libisdb; do
    vendor_root=$(git -C "$arib_build_source/vendor/$vendor" rev-parse --show-toplevel)
    expected_root=$(CDPATH='' cd -- "$arib_build_source/vendor/$vendor" && pwd -P)
    [ "$vendor_root" = "$expected_root" ]
    [ "$vendor_root" != "$parent_dtv" ] && [ "$vendor_root" != "$immutable_source" ]
done
for vendor in docopt fmt spdlog rapidjson cppcodec aribb24 tsduck-arib libisdb googletest google-benchmark; do
    source_version=$(cd "$immutable_source/vendor/$vendor" && /bin/sh "$immutable_source/scripts/tag-or-hash")
    staged_version=$(cd "$arib_build_source/vendor/$vendor" && /bin/sh "$arib_build_source/scripts/tag-or-hash")
    [ "$source_version" = "$staged_version" ]
done
source_describe=$(git -C "$immutable_source/vendor/google-benchmark" describe --tags --match 'v[0-9]*.[0-9]*.[0-9]*' --abbrev=8)
staged_describe=$(git -C "$arib_build_source/vendor/google-benchmark" describe --tags --match 'v[0-9]*.[0-9]*.[0-9]*' --abbrev=8)
[ "$source_describe" = "$staged_describe" ]
[ -z "$(git -C "$arib_build_source/vendor/google-benchmark" diff-index --name-only HEAD --)" ]

git -C "$arib_build_source/vendor/spdlog" checkout -f
(cd "$arib_build_source/vendor/spdlog" && patch -p0 < "$arib_build_source/patches/spdlog.patch")
git -C "$arib_build_source/vendor/libisdb" checkout -f
(cd "$arib_build_source/vendor/libisdb" && patch -p0 < "$arib_build_source/patches/LibISDB.patch")
git -C "$arib_build_source/vendor/spdlog" diff --quiet && exit 1
git -C "$arib_build_source/vendor/libisdb" diff --quiet && exit 1
printf '%s\n' 'compiler mutation' >> "$arib_build_source/vendor/tsduck-arib/$source_file"
printf '%s\n' generated-by-compiler > "$arib_build_source/vendor/tsduck-arib/cache-fixture-generated.h"
grep -F immutable-source-dirty "$immutable_source/CMakeLists.txt" >/dev/null
grep -F parent-dirty "$parent_dtv/parent-sentinel.txt" >/dev/null
git -C "$parent_dtv" diff --quiet && exit 1
git -C "$immutable_source" checkout -- CMakeLists.txt
[ -z "$(git -C "$immutable_source" status --porcelain=v1 --untracked-files=all)" ]
nested_pin=$(git -C "$immutable_source/vendor/tsduck-arib" rev-parse HEAD)
git -C "$immutable_source/vendor/tsduck-arib" config user.name cache-fixture
git -C "$immutable_source/vendor/tsduck-arib" config user.email cache-fixture@example.invalid
git -C "$immutable_source/vendor/tsduck-arib" commit --allow-empty -q -m cache-fixture-gitlink
if run_gradle -PmirakcAribSourceDir="$immutable_source" :mirakc:prepareMirakcAribBinaries >"$fixture/dirty-gitlink.log" 2>&1; then
    printf '%s\n' 'changed recursive ARIB gitlink unexpectedly passed cache validation' >&2
    exit 1
fi
grep -F 'mirakc-arib cache source checkout must be clean' "$fixture/dirty-gitlink.log" >/dev/null
git -C "$immutable_source/vendor/tsduck-arib" reset --hard -q "$nested_pin"
printf '\n' >> "$immutable_source/vendor/tsduck-arib/$source_file"
if run_gradle -PmirakcAribSourceDir="$immutable_source" :mirakc:prepareMirakcAribBinaries >"$fixture/dirty-arib.log" 2>&1; then
    printf '%s\n' 'dirty recursive ARIB source unexpectedly passed cache validation' >&2
    exit 1
fi
grep -F 'mirakc-arib recursive source checkout must be clean' "$fixture/dirty-arib.log" >/dev/null

rm -rf "$fixture/outputs"
log=$fixture/lifecycle-warm.log
run_gradle :mirakc:prepareMirakcBinary :mirakc:prepareMirakcAribBinaries >"$log" 2>&1
grep -F ':mirakc:prepareMirakcBinary FROM-CACHE' "$log" >/dev/null
grep -F ':mirakc:prepareMirakcAribBinaries FROM-CACHE' "$log" >/dev/null

if CMAKE="$cmake_bin/cmake" run_gradle :mirakc:prepareMirakcAribBinaries >"$fixture/unsupported-cmake.log" 2>&1; then
    printf '%s\n' 'unsupported CMake override unexpectedly passed cache validation' >&2
    exit 1
fi
grep -F 'unsupported mirakc-arib cache environment overrides: CMAKE' "$fixture/unsupported-cmake.log" >/dev/null

printf '%s\n' 'mirakc native task cache fixture: PASS (mock compiler boundary; production inputs and refusals exercised)'
