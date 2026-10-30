#!/bin/sh
# SPDX-License-Identifier: GPL-2.0-or-later
set -eu

root=$(cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$root"
package=$root/tools/mirakc/package-source.py
audit=$root/tools/mirakc/audit-source.py
temporary=$(mktemp -d /tmp/mirakc-source-test.XXXXXX)
trap 'rm -rf "$temporary"' EXIT HUP INT TERM
python3 "$root/tools/mirakc/test-cargo-vendor.py"

mkdir "$temporary/dtv"
git archive HEAD | tar -x -C "$temporary/dtv"
python3 - "$root" "$temporary/dtv" <<'PY'
from pathlib import Path
import shutil
import subprocess
import sys

root, fixture = map(Path, sys.argv[1:])
cached = subprocess.run(
    ["git", "-C", str(root), "ls-files", "--stage", "-z"],
    check=True, stdout=subprocess.PIPE,
).stdout.split(b"\0")
tracked = []
submodules = []
for record in cached:
    if not record:
        continue
    metadata, raw_path = record.split(b"\t", 1)
    path = Path(raw_path.decode("utf-8", "surrogateescape"))
    if metadata.split()[0] == b"160000":
        submodules.append(path)
    else:
        tracked.append(path)
untracked = subprocess.run(
    ["git", "-C", str(root), "ls-files", "--others", "--exclude-standard", "-z"],
    check=True, stdout=subprocess.PIPE,
).stdout.split(b"\0")
paths = tracked + [
    Path(raw.decode("utf-8", "surrogateescape")) for raw in untracked if raw
]
for path in paths:
    if any(path == submodule or submodule in path.parents for submodule in submodules):
        continue
    parts = path.parts
    basename = path.name.lower()
    if (".ssh" in parts or ".storage" in parts or basename == "secrets.yaml"
            or basename.startswith(("credential", ".env")) or basename.endswith(".env")):
        raise SystemExit("fixture source selection encountered a sensitive path")
    source = root / path
    destination = fixture / path
    if not source.exists() and not source.is_symlink():
        if destination.is_dir() and not destination.is_symlink():
            shutil.rmtree(destination)
        else:
            destination.unlink(missing_ok=True)
        continue
    destination.parent.mkdir(parents=True, exist_ok=True)
    if source.is_symlink():
        if destination.exists() or destination.is_symlink():
            if destination.is_dir() and not destination.is_symlink():
                shutil.rmtree(destination)
            else:
                destination.unlink()
        destination.symlink_to(source.readlink())
    elif source.is_file():
        if destination.is_dir() and not destination.is_symlink():
            shutil.rmtree(destination)
        shutil.copy2(source, destination)
    else:
        raise SystemExit(f"fixture source is not a regular file: {path}")
PY
arib_source_root=${MIRAKC_TEST_ARIB_ROOT:-$root/.work/mirakc-arib-0.24.40}
arib_root=$temporary/arib
GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
    git clone --quiet --no-hardlinks --no-recurse-submodules "$arib_source_root" "$arib_root"
GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
    git -C "$arib_root" checkout --detach 312168e85b30941a1fc0344900fd543a036ea341 >/dev/null
python3 - "$arib_source_root" "$arib_root" <<'PY'
from pathlib import Path
import re
import subprocess
import sys

source_root, fixture_root = map(Path, sys.argv[1:])
environment = {"GIT_CONFIG_GLOBAL": "/dev/null", "GIT_CONFIG_SYSTEM": "/dev/null"}

def git(directory, *arguments, check=True):
    return subprocess.run(
        ["git", "-C", str(directory), *arguments],
        check=check, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=environment,
    )

def populate(source, fixture):
    if not (source / ".gitmodules").is_file():
        return
    modules = git(source, "config", "-f", ".gitmodules", "--get-regexp", r"^submodule\..*\.path$", check=False)
    if modules.returncode == 1:
        return
    if modules.returncode != 0:
        raise RuntimeError(f"cannot read fixture submodules: {source}")
    for record in modules.stdout.decode().splitlines():
        key, relative = record.split(maxsplit=1)
        name = key.removeprefix("submodule.").removesuffix(".path")
        source_module = source / relative
        gitlink = git(source, "ls-tree", "HEAD", "--", relative).stdout.decode().strip()
        match = re.match(r"^160000 commit ([0-9a-f]+)\t", gitlink)
        if match is None or not source_module.is_dir():
            raise RuntimeError(f"local fixture submodule is missing: {relative}")
        if git(source_module, "rev-parse", "HEAD").stdout.decode().strip() != match.group(1):
            raise RuntimeError(f"local fixture submodule is at the wrong commit: {relative}")
        git(fixture, "config", "--local", f"submodule.{name}.url", str(source_module))
        git(fixture, "-c", "protocol.file.allow=always", "submodule", "update", "--init", "--", relative)
        populate(source_module, fixture / relative)

populate(source_root, fixture_root)
PY
mkdir -p "$temporary/dtv/tools/mirakc"
cp "$package" "$audit" "$0" "$root/tools/mirakc/test-source-native-rebuild.sh" "$temporary/dtv/tools/mirakc/"
mkdir -p "$temporary/dtv/tools/mirakc/patches"
cp "$root/tools/mirakc/patches/dtv-core-receiver-control-concurrency.patch" \
   "$root/tools/mirakc/patches/px4-userland-build-jobs.patch" \
   "$temporary/dtv/tools/mirakc/patches/"
cp -R "$root/mirakc-ui-contract" "$root/mirakc-tv-ui" "$temporary/dtv/"
cp "$root/rust-toolchain.toml" "$temporary/dtv/rust-toolchain.toml"
cp "$root/gradle/wrapper/gradle-wrapper.properties" \
   "$temporary/dtv/gradle/wrapper/gradle-wrapper.properties"
cp "$root/tools/mirakc/build-android.sh" "$temporary/dtv/tools/mirakc/build-android.sh"
mkdir -p "$temporary/dtv/tools/mirakc-arib"
cp "$root/tools/mirakc-arib/build-android.sh" "$root/tools/mirakc-arib/bootstrap-autotools.sh" \
   "$temporary/dtv/tools/mirakc-arib/"
cp "$root/tools/mirakc/patches/mirakc-android-web-resilience.patch" \
   "$temporary/dtv/tools/mirakc/patches/mirakc-android-web-resilience.patch"
(cd "$temporary/dtv" && export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null && git init -q && git config user.name fixture && git config user.email fixture@example.invalid && git add -A && git commit -q -m fixture)
dtv_commit=$(git -C "$temporary/dtv" rev-parse HEAD)
python3 - "$root" "$temporary/dtv" <<'PY'
from pathlib import Path
import subprocess
import sys

root, fixture = map(Path, sys.argv[1:])
required = (
    "mirakc/src/main/assets/asicen-loader.bin",
    "mirakc/src/main/assets/ASICEN-LOADER-NOTICE.txt",
    "mirakc/src/main/cpp/userland_process_launcher.h",
    "mirakc/src/main/cpp/tests/userland_process_launcher_test.cpp",
    "mirakc/src/main/cpp/adapter_cli.h",
    "mirakc/src/main/cpp/pix_adapter.cpp",
    "mirakc/src/main/cpp/tests/adapter_cli_test.cpp",
    "mirakc/src/main/cpp/tests/ifd_card_fixture_client.cpp",
    "mirakc/src/main/cpp/tests/ifd_card_fixture_test.cpp",
    "mirakc/src/main/cpp/tests/userland_concurrency_test.cpp",
    "mirakc/src/main/cpp/tests/userland_worker_resource_test.cpp",
    "mirakc/src/main/java/dev/khronos31/mirakc/AsicenProvisioningController.kt",
    "mirakc/src/main/java/dev/khronos31/mirakc/UserlandControl.kt",
    "mirakc/src/main/java/dev/khronos31/mirakc/UserlandRuntimeLayout.kt",
    "tools/mirakc/test-userland-process-launch.sh",
    "tools/mirakc/test-userland-concurrency.sh",
)
for relative in required:
    source = root / relative
    committed = subprocess.run(
        ["git", "-C", str(fixture), "show", f"HEAD:{relative}"],
        check=True, stdout=subprocess.PIPE,
    ).stdout
    if not source.is_file() or committed != source.read_bytes():
        raise SystemExit(f"fixture commit omitted or changed current DTV input: {relative}")
print(f"fixture commit contains {len(required)} current DTV build and test inputs")
PY
mirakc_source_root=${MIRAKC_TEST_MIRAKC_ROOT:-$root/.work/mirakc-3.4.91}
mirakc_root=$temporary/mirakc
GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
    git clone --quiet --no-hardlinks --no-recurse-submodules "$mirakc_source_root" "$mirakc_root"
GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
    git -C "$mirakc_root" checkout --detach 4f0767e87b109cabd17ed1ded0311355244fa018 >/dev/null
siano_root=${MIRAKC_TEST_SIANO_ROOT:-$root/.work/pinned-siano-userland}
px4_root=${MIRAKC_TEST_PX4_ROOT:-$root/.work/pinned-px4-userland}
asicen_root=${MIRAKC_TEST_ASICEN_ROOT:-$root/.work/pinned-asicen-userland}
pix_root=${MIRAKC_TEST_PIX_ROOT:-$root/.work/pinned-pix-userland}
core_root=${MIRAKC_TEST_CORE_ROOT:-$root/.work/pinned-dtv-userland-core}
siano_px4_root=${MIRAKC_TEST_SIANO_PX4_ROOT:-$root/.work/pinned-siano-px4-userland}
px4_drv_root=${MIRAKC_TEST_PX4_DRV_ROOT:-$root/.work/pinned-px4_drv}
libusb_archive=${MIRAKC_TEST_LIBUSB_ARCHIVE:-$siano_root/build/android-aarch64/src/libusb-1.0.30.tar.bz2}
swagger_archive=${MIRAKC_TEST_SWAGGER_UI_ARCHIVE:-$root/.work/swagger-ui-5.32.6.zip}
cargo_vendor="$temporary/cargo-vendor"
cargo_vendor_config="$temporary/cargo-vendor-config.toml"
autotools_cache=${MIRAKC_TEST_AUTOTOOLS_CACHE:-$root/.work/autotools-sources}
CARGO_BUILD_JOBS=1 cargo vendor --manifest-path "$mirakc_root/Cargo.toml" --locked --versioned-dirs "$cargo_vendor" >"$cargo_vendor_config"
common_args="--dtv-root $temporary/dtv --dtv-ref HEAD --mirakc-root $mirakc_root --arib-root $arib_root --siano-root $siano_root --px4-root $px4_root --asicen-root $asicen_root --pix-root $pix_root --core-root $core_root --siano-px4-root $siano_px4_root --px4-drv-root $px4_drv_root --libusb-archive $libusb_archive --swagger-ui-archive $swagger_archive --autotools-cache $autotools_cache --cargo-vendor-dir $cargo_vendor --cargo-vendor-config $cargo_vendor_config"
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
python3 - "$temporary/one/mirakc-corresponding-source.tar.gz" "$temporary/dtv" <<'PY'
import hashlib
import json
import sys
import tarfile
from pathlib import Path

archive_path, fixture_path = map(Path, sys.argv[1:])
required = (
    "mirakc/src/main/assets/asicen-loader.bin",
    "mirakc/src/main/assets/ASICEN-LOADER-NOTICE.txt",
    "mirakc/src/main/cpp/userland_process_launcher.h",
    "mirakc/src/main/cpp/tests/userland_process_launcher_test.cpp",
    "mirakc/src/main/cpp/adapter_cli.h",
    "mirakc/src/main/cpp/pix_adapter.cpp",
    "mirakc/src/main/java/dev/khronos31/mirakc/AsicenProvisioningController.kt",
    "tools/mirakc/test-userland-process-launch.sh",
)
with tarfile.open(archive_path, "r:gz") as archive:
    manifest = json.load(archive.extractfile("source-manifest.json"))
    component = next(item for item in manifest["components"] if item["name"] == "dtv-android")
    packaged = set(component["files"])
    for relative in required:
        member = "sources/dtv-android/" + relative
        source = fixture_path / relative
        if member not in packaged or not source.is_file():
            raise SystemExit(f"source archive omitted current DTV input: {relative}")
        payload = archive.extractfile(member).read()
        if hashlib.sha256(payload).hexdigest() != hashlib.sha256(source.read_bytes()).hexdigest():
            raise SystemExit(f"source archive does not match fixture DTV input: {relative}")
PY
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
swagger_archive="$temporary/extracted/third_party/swagger-ui/swagger-ui-5.32.6.zip"
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

python3 - "$temporary/one/mirakc-corresponding-source.tar.gz" "$temporary/siano-exclusion-mutated.tar.gz" <<'PY'
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
manifest["excluded_materials"]["reason"] = "unspecified firmware exclusion"
payloads["source-manifest.json"] = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()
rows = [
    f"{hashlib.sha256(payloads[name]).hexdigest()}  {name}"
    for name in sorted(payloads) if name != "SHA256SUMS"
]
payloads["SHA256SUMS"] = ("\n".join(rows) + "\n").encode()
with tarfile.open(destination, "w:gz") as archive:
    for name in sorted(payloads):
        info = infos[name]
        info.size = len(payloads[name])
        archive.addfile(info, io.BytesIO(payloads[name]))
PY
if python3 "$audit" --expected-dtv-commit "$dtv_commit" "$temporary/siano-exclusion-mutated.tar.gz" >/dev/null 2>&1; then
    printf '%s\n' 'source audit accepted a changed Siano firmware exclusion reason' >&2
    exit 1
fi

python3 - "$temporary/one/mirakc-corresponding-source.tar.gz" "$temporary/px4-patch-mutated.tar.gz" <<'PY'
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
patch_path = (
    "sources/dtv-android/tools/mirakc/patches/"
    "dtv-core-receiver-control-concurrency.patch"
)
payloads[patch_path] += b"\n# source-package tamper test\n"
manifest = json.loads(payloads["source-manifest.json"])
manifest["files"][patch_path] = {
    "size": len(payloads[patch_path]),
    "sha256": hashlib.sha256(payloads[patch_path]).hexdigest(),
}
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
if python3 "$audit" "$temporary/px4-patch-mutated.tar.gz" >/dev/null 2>&1; then
    printf '%s\n' 'source audit accepted a modified pinned PX4 patch' >&2
    exit 1
fi

python3 - "$temporary/one/mirakc-corresponding-source.tar.gz" "$temporary/core-pin-mutated.tar.gz" "$temporary/asicen-firmware-mutated.tar.gz" <<'PY'
import hashlib
import io
import json
import sys
import tarfile

source, core_out, firmware_out = sys.argv[1:]
payloads = {}
infos = {}
with tarfile.open(source, "r:gz") as archive:
    for info in archive.getmembers():
        infos[info.name] = info
        payloads[info.name] = archive.extractfile(info).read()

def write(destination, changes):
    altered = dict(payloads)
    altered.update(changes)
    manifest = json.loads(altered["source-manifest.json"])
    manifest["files"].update({
        name: {"size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
        for name, data in changes.items() if name in manifest["files"]
    })
    altered["source-manifest.json"] = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()
    rows = [
        f"{hashlib.sha256(altered[name]).hexdigest()}  {name}"
        for name in sorted(altered) if name != "SHA256SUMS"
    ]
    altered["SHA256SUMS"] = ("\n".join(rows) + "\n").encode()
    with tarfile.open(destination, "w:gz") as archive:
        for name in sorted(altered):
            info = infos[name]
            info.size = len(altered[name])
            archive.addfile(info, io.BytesIO(altered[name]))

manifest = json.loads(payloads["source-manifest.json"])
manifest["dependency_pins"]["siano-userland/dtv-userland-core"] = "0" * 40
write(core_out, {"source-manifest.json": (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()})
firmware = "sources/dtv-android/mirakc/src/main/assets/asicen-loader.bin"
write(firmware_out, {firmware: payloads[firmware] + b"\0"})
PY
for archive in "$temporary/core-pin-mutated.tar.gz" "$temporary/asicen-firmware-mutated.tar.gz"; do
    if python3 "$audit" "$archive" >/dev/null 2>&1; then
        printf '%s\n' "source audit accepted a transitive-pin or firmware mutation: $archive" >&2
        exit 1
    fi
done

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
