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
cp "$package" "$audit" "$0" "$temporary/dtv/tools/mirakc/"
(cd "$temporary/dtv" && git init -q && git config user.name fixture && git config user.email fixture@example.invalid && git add -A && git commit -q -m fixture)
dtv_commit=$(git -C "$temporary/dtv" rev-parse HEAD)
common_args="--dtv-root $temporary/dtv --dtv-ref HEAD --mirakc-root $root/.work/mirakc-3.4.86 --arib-root $root/.work/mirakc-arib-0.24.38 --siano-root $root/.work/pinned-siano-userland --px4-root $root/.work/pinned-px4-userland --px4-drv-root $root/.work/pinned-px4_drv --libusb-archive $root/../siano-userland/build/android-aarch64/src/libusb-1.0.30.tar.bz2 --autotools-cache /config/.work/mirakc-arib-tools/autotools-sources"
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
