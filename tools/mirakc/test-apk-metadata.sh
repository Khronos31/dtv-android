#!/bin/sh
# SPDX-License-Identifier: GPL-2.0-or-later
set -eu

root=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd -P)
generator=$root/tools/mirakc/generate-apk-metadata.py
auditor=$root/tools/mirakc/audit-apk-metadata.py

# CI checks out external source repositories at these exact root paths.  They
# are ignored as whole checkout directories, while an unrelated untracked
# directory must remain visible to the DTV cleanliness gate.
for external_checkout in siano-userland px4-userland asicen-userland pix-userland dtv-userland-core siano-px4-userland px4_drv; do
    git -C "$root" check-ignore -q -- "$external_checkout/probe" || {
        printf '%s\n' "missing root ignore for $external_checkout" >&2
        exit 1
    }
done
if git -C "$root" check-ignore -q -- unexpected-external/probe; then
    printf '%s\n' 'root ignore is too broad for external checkouts' >&2
    exit 1
fi
for python_cache in \
    tools/mirakc/__pycache__/audit-source.cpython-313.pyc \
    tools/python-shim/distutils/__pycache__/core.cpython-313.pyc; do
    git -C "$root" check-ignore -q -- "$python_cache" || {
        printf '%s\n' "missing Python cache ignore for $python_cache" >&2
        exit 1
    }
done
if git -C "$root" check-ignore -q -- unexpected-source.py; then
    printf '%s\n' 'root ignore hides an unexpected source file' >&2
    exit 1
fi

temporary=$(mktemp -d /tmp/mirakc-apk-metadata-test.XXXXXX)
cleanup()
{
    rm -rf "$temporary"
}
trap cleanup EXIT HUP INT TERM

mkdir "$temporary/dtv"
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
        fixture_module = fixture / relative
        gitlink = git(source, "ls-tree", "HEAD", "--", relative).stdout.decode().strip()
        match = re.match(r"^160000 commit ([0-9a-f]+)\t", gitlink)
        if match is None or not source_module.is_dir():
            raise RuntimeError(f"local fixture submodule is missing: {relative}")
        expected = match.group(1)
        actual = git(source_module, "rev-parse", "HEAD").stdout.decode().strip()
        if actual != expected:
            raise RuntimeError(f"local fixture submodule is at the wrong commit: {relative}")
        git(fixture, "config", "--local", f"submodule.{name}.url", str(source_module))
        git(fixture, "-c", "protocol.file.allow=always", "submodule", "update", "--init", "--", relative)
        populate(source_module, fixture_module)

populate(source_root, fixture_root)
PY
GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
    git -C "$root" archive HEAD | tar -x -C "$temporary/dtv"
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
mkdir -p "$temporary/dtv/tools/mirakc/patches"
cp "$root/tools/mirakc/patches/dtv-core-receiver-control-concurrency.patch" \
    "$root/tools/mirakc/patches/px4-userland-build-jobs.patch" \
    "$temporary/dtv/tools/mirakc/patches/"
(
    cd "$temporary/dtv"
    export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null
    git init -q
    git config user.name fixture
    git config user.email fixture@example.invalid
    git add -A
    git commit -q -m fixture
)
dtv_commit=$(git -C "$temporary/dtv" rev-parse HEAD)
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
libusb_archive=${MIRAKC_TEST_LIBUSB_ARCHIVE:-$root/.work/libusb-1.0.30.tar.bz2}
args="--dtv-root $temporary/dtv --dtv-ref HEAD --mirakc-root $mirakc_root --arib-root $arib_root --siano-root $siano_root --px4-root $px4_root --asicen-root $asicen_root --pix-root $pix_root --core-root $core_root --siano-px4-root $siano_px4_root --px4-drv-root $px4_drv_root --arib25-root $root/mirakc/src/main/cpp/arib25 --libusb-archive $libusb_archive"
printf '%s\n' 'untracked fixture path' > "$temporary/dtv/unexpected-untracked.txt"
# shellcheck disable=SC2086
if python3 "$generator" --output "$temporary/dirty-dtv" $args \
    > /dev/null 2> "$temporary/dirty-dtv.stderr"; then
    printf '%s\n' 'metadata generator accepted an arbitrary untracked DTV path' >&2
    exit 1
fi
grep -F -- 'unexpected-untracked.txt' "$temporary/dirty-dtv.stderr" >/dev/null
rm -- "$temporary/dtv/unexpected-untracked.txt"
# shellcheck disable=SC2086
python3 "$generator" --output "$temporary/one" $args >/dev/null
# shellcheck disable=SC2086
python3 "$generator" --output "$temporary/two" $args >/dev/null
diff -ru "$temporary/one" "$temporary/two"
cp "$temporary/dtv/mirakc/src/main/assets/asicen-loader.bin" "$temporary/asicen-loader.bin"
# shellcheck disable=SC2086
python3 "$generator" --output "$temporary/clean-allow" $args --allow-development-worktree >/dev/null
diff -ru "$temporary/one" "$temporary/clean-allow"
python3 - "$temporary/clean-allow" "$temporary/clean-allow.apk" <<'PY'
from pathlib import Path
import sys
import zipfile

source, destination = map(Path, sys.argv[1:])
with zipfile.ZipFile(destination, "w") as archive:
    for path in sorted(source.rglob("*")):
        if path.is_file():
            archive.writestr("assets/" + path.relative_to(source).as_posix(), path.read_bytes())
    archive.writestr("assets/isdbt_rio.inp", Path("mirakc/src/main/assets/isdbt_rio.inp").read_bytes())
    archive.writestr("assets/asicen-loader.bin", destination.parent.joinpath("asicen-loader.bin").read_bytes())
PY
python3 "$auditor" --expected-dtv-commit "$dtv_commit" "$temporary/clean-allow.apk" >/dev/null
cp "$temporary/dtv/mirakc/build.gradle.kts" "$temporary/build.gradle.kts.original"
printf '\n# debug worktree overlay fixture\n' >> "$temporary/dtv/mirakc/build.gradle.kts"
# shellcheck disable=SC2086
python3 "$generator" --output "$temporary/debug" $args --allow-development-worktree >/dev/null
python3 - "$temporary/debug" "$temporary/debug.apk" <<'PY'
from pathlib import Path
import sys
import zipfile

source, destination = map(Path, sys.argv[1:])
with zipfile.ZipFile(destination, "w") as archive:
    for path in sorted(source.rglob("*")):
        if path.is_file():
            archive.writestr("assets/" + path.relative_to(source).as_posix(), path.read_bytes())
    archive.writestr("assets/isdbt_rio.inp", Path("mirakc/src/main/assets/isdbt_rio.inp").read_bytes())
    archive.writestr("assets/asicen-loader.bin", destination.parent.joinpath("asicen-loader.bin").read_bytes())
PY
python3 "$auditor" --allow-development-worktree --expected-dtv-commit "$dtv_commit" "$temporary/debug.apk" >/dev/null
if python3 "$auditor" --expected-dtv-commit "$dtv_commit" "$temporary/debug.apk" >/dev/null 2>&1; then
    printf '%s\n' 'strict APK metadata audit accepted development worktree provenance' >&2
    exit 1
fi
cp "$temporary/build.gradle.kts.original" "$temporary/dtv/mirakc/build.gradle.kts"

# A recursive submodule is intentionally allowed to have a dirty worktree by
# the build harness. Its embedded license must still come from the pinned Git
# object, never from that worktree.
dirty_license="$arib_root/vendor/fmt/LICENSE"
dirty_license_backup="$temporary/fmt-license.original"
[ -f "$dirty_license" ] || { printf '%s\n' "missing test license: $dirty_license" >&2; exit 1; }
cp "$dirty_license" "$dirty_license_backup"
printf '%s\n' 'metadata self-test mutation' >> "$dirty_license"
# shellcheck disable=SC2086
python3 "$generator" --output "$temporary/dirty-license" $args >/dev/null
cmp -s "$dirty_license_backup" \
    "$temporary/dirty-license/source-metadata/licenses/mirakc-arib__vendor__fmt/LICENSE"
cp "$dirty_license_backup" "$dirty_license"

python3 - "$temporary/one" "$temporary/good.apk" <<'PY'
from pathlib import Path
import sys
import zipfile

source, destination = map(Path, sys.argv[1:])
with zipfile.ZipFile(destination, "w") as archive:
    for path in sorted(source.rglob("*")):
        if path.is_file():
            archive.writestr("assets/" + path.relative_to(source).as_posix(), path.read_bytes())
    archive.writestr("assets/isdbt_rio.inp", Path("mirakc/src/main/assets/isdbt_rio.inp").read_bytes())
    archive.writestr("assets/asicen-loader.bin", Path(sys.argv[1]).parent.joinpath("asicen-loader.bin").read_bytes())
PY
python3 "$auditor" --expected-dtv-commit "$dtv_commit" "$temporary/good.apk" >/dev/null

python3 - "$temporary/good.apk" "$temporary/manifest-mutated.apk" "$temporary/pin-mutated.apk" "$temporary/firmware-mutated.apk" "$temporary/px4-patch-provenance-mutated.apk" "$temporary/core-patch-provenance-mutated.apk" "$temporary/siano-px4-pin-mutated.apk" "$temporary/asicen-firmware-pin-mutated.apk" <<'PY'
import json
from pathlib import Path
import sys
import zipfile

source, manifest_out, pin_out, firmware_out, px4_patch_out, core_patch_out, siano_pin_out, asicen_pin_out = map(Path, sys.argv[1:])
with zipfile.ZipFile(source) as original:
    entries = {info.filename: original.read(info) for info in original.infolist()}

def write(destination, altered):
    with zipfile.ZipFile(destination, "w") as archive:
        for name in sorted(altered):
            archive.writestr(name, altered[name])

manifest = json.loads(entries["assets/source-metadata/manifest.json"])
first = next(component for component in manifest["components"] if component["name"] == "mirakc")
first["commit"] = "f" * 40
changed = dict(entries)
changed["assets/source-metadata/manifest.json"] = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()
write(manifest_out, changed)

manifest = json.loads(entries["assets/source-metadata/manifest.json"])
nested_px4 = next(component for component in manifest["components"] if component["name"] == "siano-px4-userland")
nested_px4["commit"] = "f" * 40
changed = dict(entries)
changed["assets/source-metadata/manifest.json"] = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()
write(pin_out, changed)

manifest = json.loads(entries["assets/source-metadata/manifest.json"])
px4 = next(component for component in manifest["components"] if component["name"] == "px4-userland")
px4["source_inputs"][0]["sha256"] = "0" * 64
changed = dict(entries)
changed["assets/source-metadata/manifest.json"] = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()
write(px4_patch_out, changed)

manifest = json.loads(entries["assets/source-metadata/manifest.json"])
core = next(component for component in manifest["components"] if component["name"] == "dtv-userland-core")
core["source_inputs"][0]["sha256"] = "0" * 64
changed = dict(entries)
changed["assets/source-metadata/manifest.json"] = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()
write(core_patch_out, changed)

manifest = json.loads(entries["assets/source-metadata/manifest.json"])
siano = next(component for component in manifest["components"] if component["name"] == "siano-userland")
siano["source_inputs"][0]["sha256"] = "0" * 64
changed = dict(entries)
changed["assets/source-metadata/manifest.json"] = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()
write(siano_pin_out, changed)

manifest = json.loads(entries["assets/source-metadata/manifest.json"])
asicen = next(component for component in manifest["components"] if component["name"] == "asicen-loader-firmware")
asicen["commit"] = "0" * 40
changed = dict(entries)
changed["assets/source-metadata/manifest.json"] = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()
write(asicen_pin_out, changed)

changed = dict(entries)
changed["assets/isdbt_rio.inp"] = b"bad firmware"
write(firmware_out, changed)
PY
for apk in "$temporary/manifest-mutated.apk" "$temporary/pin-mutated.apk" "$temporary/firmware-mutated.apk" "$temporary/px4-patch-provenance-mutated.apk" "$temporary/core-patch-provenance-mutated.apk" "$temporary/siano-px4-pin-mutated.apk" "$temporary/asicen-firmware-pin-mutated.apk"; do
    if python3 "$auditor" --expected-dtv-commit "$dtv_commit" "$apk" >/dev/null 2>&1; then
        printf '%s\n' "APK metadata auditor accepted mutation: $apk" >&2
        exit 1
    fi
done

python3 - "$temporary/good.apk" "$temporary/path-traversal.apk" "$temporary/duplicate.apk" <<'PY'
from pathlib import Path
import sys
import warnings
import zipfile

warnings.filterwarnings("ignore", category=UserWarning, module="zipfile")

source, traversal, duplicate = map(Path, sys.argv[1:])
with zipfile.ZipFile(source) as original:
    entries = [(info.filename, original.read(info)) for info in original.infolist()]
with zipfile.ZipFile(traversal, "w") as archive:
    for name, payload in entries:
        archive.writestr(name, payload)
    archive.writestr("assets/source-metadata/../escape", b"x")
with zipfile.ZipFile(duplicate, "w") as archive:
    for name, payload in entries:
        archive.writestr(name, payload)
    name, payload = entries[0]
    archive.writestr(name, payload)
PY
for apk in "$temporary/path-traversal.apk" "$temporary/duplicate.apk"; do
    if python3 "$auditor" "$apk" >/dev/null 2>&1; then
        printf '%s\n' "APK metadata auditor accepted malformed ZIP: $apk" >&2
        exit 1
    fi
done

printf '%s\n' 'APK source metadata self-test: PASS'
