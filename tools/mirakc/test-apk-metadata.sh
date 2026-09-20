#!/bin/sh
# SPDX-License-Identifier: GPL-2.0-or-later
set -eu

root=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd -P)
generator=$root/tools/mirakc/generate-apk-metadata.py
auditor=$root/tools/mirakc/audit-apk-metadata.py

# CI checks out external source repositories at these exact root paths.  They
# are ignored as whole checkout directories, while an unrelated untracked
# directory must remain visible to the DTV cleanliness gate.
for external_checkout in siano-userland px4-userland px4_drv; do
    git -C "$root" check-ignore -q -- "$external_checkout/probe" || {
        printf '%s\n' "missing root ignore for $external_checkout" >&2
        exit 1
    }
done
if git -C "$root" check-ignore -q -- unexpected-external/probe; then
    printf '%s\n' 'root ignore is too broad for external checkouts' >&2
    exit 1
fi

temporary=$(mktemp -d /tmp/mirakc-apk-metadata-test.XXXXXX)
dirty_license=
dirty_license_backup=
cleanup()
{
    if [ -n "$dirty_license" ] && [ -f "$dirty_license_backup" ]; then
        cp "$dirty_license_backup" "$dirty_license"
    fi
    rm -rf "$temporary"
}
trap cleanup EXIT HUP INT TERM

mkdir "$temporary/dtv"
git -C "$root" archive HEAD | tar -x -C "$temporary/dtv"
(
    cd "$temporary/dtv"
    git init -q
    git config user.name fixture
    git config user.email fixture@example.invalid
    git add -A
    git commit -q -m fixture
)
dtv_commit=$(git -C "$temporary/dtv" rev-parse HEAD)
mirakc_root=${MIRAKC_TEST_MIRAKC_ROOT:-$root/.work/mirakc-3.4.86}
arib_root=${MIRAKC_TEST_ARIB_ROOT:-$root/.work/mirakc-arib-0.24.38}
siano_root=${MIRAKC_TEST_SIANO_ROOT:-$root/.work/pinned-siano-userland}
px4_root=${MIRAKC_TEST_PX4_ROOT:-$root/.work/pinned-px4-userland}
px4_drv_root=${MIRAKC_TEST_PX4_DRV_ROOT:-$root/.work/pinned-px4_drv}
libusb_archive=${MIRAKC_TEST_LIBUSB_ARCHIVE:-$siano_root/build/android-aarch64/src/libusb-1.0.30.tar.bz2}
args="--dtv-root $temporary/dtv --dtv-ref HEAD --mirakc-root $mirakc_root --arib-root $arib_root --siano-root $siano_root --px4-root $px4_root --px4-drv-root $px4_drv_root --arib25-root $root/mirakc/src/main/cpp/arib25 --libusb-archive $libusb_archive"
# shellcheck disable=SC2086
python3 "$generator" --output "$temporary/one" $args >/dev/null
# shellcheck disable=SC2086
python3 "$generator" --output "$temporary/two" $args >/dev/null
diff -ru "$temporary/one" "$temporary/two"

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
dirty_license=
dirty_license_backup=

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
PY
python3 "$auditor" --expected-dtv-commit "$dtv_commit" "$temporary/good.apk" >/dev/null

python3 - "$temporary/good.apk" "$temporary/manifest-mutated.apk" "$temporary/pin-mutated.apk" "$temporary/firmware-mutated.apk" <<'PY'
import json
from pathlib import Path
import sys
import zipfile

source, manifest_out, pin_out, firmware_out = map(Path, sys.argv[1:])
with zipfile.ZipFile(source) as original:
    entries = {info.filename: original.read(info) for info in original.infolist()}

def write(destination, altered):
    with zipfile.ZipFile(destination, "w") as archive:
        for name in sorted(altered):
            archive.writestr(name, altered[name])

manifest = json.loads(entries["assets/source-metadata/manifest.json"])
first = sorted(manifest["files"])[0]
manifest["files"][first]["sha256"] = "0" * 64
changed = dict(entries)
changed["assets/source-metadata/manifest.json"] = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()
write(manifest_out, changed)

manifest = json.loads(entries["assets/source-metadata/manifest.json"])
manifest["components"][0]["commit"] = "f" * 40
changed = dict(entries)
changed["assets/source-metadata/manifest.json"] = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()
write(pin_out, changed)

changed = dict(entries)
changed["assets/isdbt_rio.inp"] = b"bad firmware"
write(firmware_out, changed)
PY
for apk in "$temporary/manifest-mutated.apk" "$temporary/pin-mutated.apk" "$temporary/firmware-mutated.apk"; do
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
