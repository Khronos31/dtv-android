#!/bin/sh
# SPDX-License-Identifier: GPL-2.0-or-later
set -eu

root=$(cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$root"
package=$root/tools/mirakc/package-source.py
audit=$root/tools/mirakc/audit-source.py
temporary=$(mktemp -d /tmp/mirakc-source-test.XXXXXX)
trap 'rm -rf "$temporary"' EXIT HUP INT TERM

mkdir "$temporary/dtv"
git archive HEAD | tar -x -C "$temporary/dtv"
mkdir -p "$temporary/dtv/tools/mirakc"
cp "$package" "$audit" "$0" "$root/tools/mirakc/test-source-native-rebuild.sh" "$temporary/dtv/tools/mirakc/"
cp "$root/rust-toolchain.toml" "$temporary/dtv/rust-toolchain.toml"
cp "$root/gradle/wrapper/gradle-wrapper.properties" \
   "$temporary/dtv/gradle/wrapper/gradle-wrapper.properties"
cp "$root/tools/mirakc/build-android.sh" "$temporary/dtv/tools/mirakc/build-android.sh"
mkdir -p "$temporary/dtv/tools/mirakc-arib"
cp "$root/tools/mirakc-arib/build-android.sh" "$root/tools/mirakc-arib/bootstrap-autotools.sh" \
   "$temporary/dtv/tools/mirakc-arib/"
cp "$root/tools/mirakc/patches/mirakc-android-web-resilience.patch" \
   "$temporary/dtv/tools/mirakc/patches/mirakc-android-web-resilience.patch"
(cd "$temporary/dtv" && git init -q && git config user.name fixture && git config user.email fixture@example.invalid && git add -A && git commit -q -m fixture)
dtv_commit=$(git -C "$temporary/dtv" rev-parse HEAD)
mirakc_root=${MIRAKC_TEST_MIRAKC_ROOT:-$root/.work/mirakc-3.4.86}
arib_root=${MIRAKC_TEST_ARIB_ROOT:-$root/.work/mirakc-arib-0.24.38}
siano_root=${MIRAKC_TEST_SIANO_ROOT:-$root/.work/pinned-siano-userland}
px4_root=${MIRAKC_TEST_PX4_ROOT:-$root/.work/pinned-px4-userland}
px4_drv_root=${MIRAKC_TEST_PX4_DRV_ROOT:-$root/.work/pinned-px4_drv}
libusb_archive=${MIRAKC_TEST_LIBUSB_ARCHIVE:-$siano_root/build/android-aarch64/src/libusb-1.0.30.tar.bz2}
swagger_archive=${MIRAKC_TEST_SWAGGER_UI_ARCHIVE:-/config/.work/mirakc-swagger-ui/swagger-ui-5.17.14.zip}
cargo_vendor="$temporary/cargo-vendor"
autotools_cache=${MIRAKC_TEST_AUTOTOOLS_CACHE:-/config/.work/mirakc-arib-tools/autotools-sources}
cargo vendor --manifest-path "$mirakc_root/Cargo.toml" --locked --versioned-dirs "$cargo_vendor" >/dev/null 2>&1
common_args="--dtv-root $temporary/dtv --dtv-ref HEAD --mirakc-root $mirakc_root --arib-root $arib_root --siano-root $siano_root --px4-root $px4_root --px4-drv-root $px4_drv_root --libusb-archive $libusb_archive --swagger-ui-archive $swagger_archive --autotools-cache $autotools_cache --cargo-vendor-dir $cargo_vendor"
if python3 "$package" --output-dir "$temporary/dirty" --dtv-root "$root" >/dev/null 2>&1; then
    printf '%s\n' 'source package accepted the dirty DTV checkout' >&2
    exit 1
fi
# shellcheck disable=SC2086
python3 "$package" --output-dir "$temporary/one" $common_args >/dev/null
# shellcheck disable=SC2086
python3 "$package" --output-dir "$temporary/two" $common_args >/dev/null
cmp -s \
    "$temporary/one/mirakc-corresponding-source.tar.gz" \
    "$temporary/two/mirakc-corresponding-source.tar.gz"
python3 "$audit" --expected-dtv-commit "$dtv_commit" "$temporary/one/mirakc-corresponding-source.tar.gz" >/dev/null
if [ -n "${MIRAKC_TEST_ARCHIVE_OUTPUT:-}" ]; then
    cp "$temporary/one/mirakc-corresponding-source.tar.gz" "$MIRAKC_TEST_ARCHIVE_OUTPUT"
fi

# Prove the extracted archive resolves and type-checks without registry/network
# state. The root .cargo/config.toml must select only the archived vendor tree.
mkdir "$temporary/extracted"
tar -xzf "$temporary/one/mirakc-corresponding-source.tar.gz" -C "$temporary/extracted"
mkdir "$temporary/cargo-home" "$temporary/cargo-target"
mkdir "$temporary/network-bin"
for network_tool in curl wget; do
    cat > "$temporary/network-bin/$network_tool" <<'EOF'
#!/bin/sh
printf '%s\n' 'network access attempted during offline Cargo proof' >&2
exit 97
EOF
    chmod 0755 "$temporary/network-bin/$network_tool"
done
cat > "$temporary/network-bin/git" <<'EOF'
#!/bin/sh
set -eu
case "${1:-}" in
clone|fetch|pull|push|remote|ls-remote|archive)
    printf '%s\n' 'network/source fetch attempted during offline Cargo proof' >&2
    exit 97
    ;;
esac
exec /usr/bin/git "$@"
EOF
chmod 0755 "$temporary/network-bin/git"
swagger_archive="$temporary/extracted/third_party/swagger-ui/swagger-ui-5.17.14.zip"
test -f "$swagger_archive"
(
    cd "$temporary/extracted"
    PATH="$temporary/network-bin:$PATH" \
        SWAGGER_UI_DOWNLOAD_URL="file://$swagger_archive" \
        CARGO_HOME="$temporary/cargo-home" CARGO_TARGET_DIR="$temporary/cargo-target" \
        CARGO_NET_OFFLINE=true cargo metadata --manifest-path sources/mirakc/Cargo.toml --locked --format-version 1 >/dev/null
    VERGEN_GIT_SHA=archive-test PATH="$temporary/network-bin:$PATH" \
        SWAGGER_UI_DOWNLOAD_URL="file://$swagger_archive" \
        CARGO_HOME="$temporary/cargo-home" CARGO_TARGET_DIR="$temporary/cargo-target" \
        CARGO_NET_OFFLINE=true cargo check --manifest-path sources/mirakc/Cargo.toml --locked --workspace >/dev/null
)

python3 - "$temporary/one/mirakc-corresponding-source.tar.gz" "$temporary/extra.tar.gz" <<'PY'
import io
import sys
import tarfile

source, destination = sys.argv[1:]
with tarfile.open(source, "r:gz") as source_tar, tarfile.open(destination, "w:gz") as destination_tar:
    for member in source_tar.getmembers():
        payload = source_tar.extractfile(member)
        destination_tar.addfile(member, payload)
    extra = tarfile.TarInfo("unexpected.txt")
    extra.mode = 0o644
    extra.size = 1
    destination_tar.addfile(extra, io.BytesIO(b"x"))
PY
if python3 "$audit" "$temporary/extra.tar.gz" >/dev/null 2>&1; then
    printf '%s\n' 'source audit accepted an unexpected member' >&2
    exit 1
fi

python3 - "$temporary/one/mirakc-corresponding-source.tar.gz" "$temporary/manifest-mutated.tar.gz" <<'PY'
import hashlib
import io
import json
import sys
import tarfile

source, destination = sys.argv[1:]
payloads = {}
infos = {}
with tarfile.open(source, "r:gz") as archive:
    for info in archive.getmembers():
        infos[info.name] = info
        payloads[info.name] = archive.extractfile(info).read()
manifest = json.loads(payloads["source-manifest.json"])
first = sorted(manifest["files"])[0]
manifest["files"][first]["sha256"] = "0" * 64
payloads["source-manifest.json"] = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()
rows = []
for name in sorted(payloads):
    if name != "SHA256SUMS":
        rows.append(f"{hashlib.sha256(payloads[name]).hexdigest()}  {name}")
payloads["SHA256SUMS"] = ("\n".join(rows) + "\n").encode()
with tarfile.open(destination, "w:gz") as archive:
    for name in sorted(payloads):
        info = infos[name]
        info.size = len(payloads[name])
        archive.addfile(info, io.BytesIO(payloads[name]))
PY
if python3 "$audit" --expected-dtv-commit "$dtv_commit" "$temporary/manifest-mutated.tar.gz" >/dev/null 2>&1; then
    printf '%s\n' 'source audit accepted a manifest digest mutation' >&2
    exit 1
fi

python3 - "$temporary/one/mirakc-corresponding-source.tar.gz" "$temporary/vendor-mutated.tar.gz" <<'PY'
import hashlib
import io
import sys
import tarfile

source, destination = sys.argv[1:]
payloads = {}
infos = {}
with tarfile.open(source, "r:gz") as archive:
    for info in archive.getmembers():
        infos[info.name] = info
        payloads[info.name] = archive.extractfile(info).read()
vendor_name = next(
    name for name in sorted(payloads)
    if name.startswith("third_party/cargo/vendor/")
    and not name.endswith("/")
)
payloads[vendor_name] += b"\nmutation\n"
rows = []
for name in sorted(payloads):
    if name != "SHA256SUMS":
        rows.append(f"{hashlib.sha256(payloads[name]).hexdigest()}  {name}")
payloads["SHA256SUMS"] = ("\n".join(rows) + "\n").encode()
with tarfile.open(destination, "w:gz") as archive:
    for name in sorted(payloads):
        info = infos[name]
        info.size = len(payloads[name])
        archive.addfile(info, io.BytesIO(payloads[name]))
PY
if python3 "$audit" --expected-dtv-commit "$dtv_commit" "$temporary/vendor-mutated.tar.gz" >/dev/null 2>&1; then
    printf '%s\n' 'source audit accepted a Cargo vendor mutation' >&2
    exit 1
fi

python3 - "$temporary/path-traversal.tar.gz" "$temporary/hardlink.tar.gz" <<'PY'
import sys
import tarfile

for destination, kind in zip(sys.argv[1:], ("traversal", "hardlink")):
    with tarfile.open(destination, "w:gz") as archive:
        member = tarfile.TarInfo("../outside" if kind == "traversal" else "sources/hardlink")
        if kind == "traversal":
            member.size = 0
            archive.addfile(member)
        else:
            member.type = tarfile.LNKTYPE
            member.linkname = "outside"
            archive.addfile(member)
PY
if python3 "$audit" "$temporary/path-traversal.tar.gz" >/dev/null 2>&1 || \
   python3 "$audit" "$temporary/hardlink.tar.gz" >/dev/null 2>&1; then
    printf '%s\n' 'source audit accepted an unsafe path or hardlink' >&2
    exit 1
fi

python3 - "$temporary/one/mirakc-corresponding-source.tar.gz" "$temporary/symlink.tar.gz" <<'PY'
import sys
import tarfile

_, destination = sys.argv[1:]
with tarfile.open(destination, "w:gz") as archive:
    link = tarfile.TarInfo("sources/unsafe")
    link.type = tarfile.SYMTYPE
    link.linkname = "../outside"
    archive.addfile(link)
PY
if python3 "$audit" "$temporary/symlink.tar.gz" >/dev/null 2>&1; then
    printf '%s\n' 'source audit accepted a symlink' >&2
    exit 1
fi

printf '%s\n' 'aggregate source package self-test: PASS'
