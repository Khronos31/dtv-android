#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
"""Build a deterministic aggregate corresponding-source archive for mirakc."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import importlib.util
import io
import json
from pathlib import Path, PurePosixPath
import subprocess
import sys
import tarfile
import tempfile


AUDIT_PATH = Path(__file__).with_name("audit-source.py")
spec = importlib.util.spec_from_file_location("mirakc_source_audit", AUDIT_PATH)
if spec is None or spec.loader is None:
    raise ImportError("cannot load audit-source.py")
audit_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit_module)
AuditError = audit_module.AuditError
EXPECTED = audit_module.EXPECTED
TOOLCHAIN = audit_module.TOOLCHAIN
TOOLCHAIN_ARCHIVES = audit_module.TOOLCHAIN_ARCHIVES
audit_source_archive = audit_module.audit_source_archive


LIBUSB_ARCHIVE_SHA256 = "fea36f34f9156400209595e300840767ab1a385ede1dc7ee893015aea9c6dbaf"
LIBUSB_ARCHIVE_NAME = "libusb-1.0.30.tar.bz2"
FIRMWARE_URL = EXPECTED["linux-firmware-siano"]["url"]
FIRMWARE_SHA256 = EXPECTED["linux-firmware-siano"]["sha256"]
FIRMWARE_LICENSE_URL = EXPECTED["linux-firmware-siano"]["license_url"]
FIRMWARE_LICENSE_SHA256 = EXPECTED["linux-firmware-siano"]["license_sha256"]


def fail(message: str) -> None:
    raise AuditError(message)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_file(path: Path, data: bytes, mode: int = 0o644) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    path.chmod(mode & 0o7777)


def safe_name(name: str) -> None:
    path = PurePosixPath(name)
    if not name or name.startswith("/") or "\\" in name or ".." in path.parts:
        fail(f"unsafe source member: {name!r}")


def run_git(root: Path, *arguments: str, binary: bool = False) -> bytes | str:
    try:
        result = subprocess.run(
            ["git", "-C", str(root), *arguments],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    except (OSError, subprocess.CalledProcessError) as error:
        fail(f"git command failed in {root}: {error}")
    if binary:
        return result.stdout
    return result.stdout.decode("utf-8", "strict")


def ensure_pinned_clean(root: Path, commit: str, *, allow_dirty_submodules: bool = False) -> None:
    if not root.is_dir():
        fail(f"source checkout is missing: {root}")
    actual = str(run_git(root, "rev-parse", "HEAD")).strip()
    if actual != commit:
        fail(f"source ref mismatch in {root}: expected {commit}, found {actual}")
    status = subprocess.run(
        ["git", "-C", str(root), "status", "--porcelain", "--untracked-files=all"]
        + (["--ignore-submodules=dirty"] if allow_dirty_submodules else []),
        check=False, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
    )
    if status.returncode != 0:
        fail(f"cannot inspect source checkout {root}: {status.stderr.strip()}")
    if status.stdout:
        fail(f"source checkout is dirty (including untracked files): {root}")


def copy_git_archive(root: Path, ref: str, stage: Path, prefix: str, normalized: list[dict[str, str]]) -> None:
    archive_data = run_git(root, "archive", "--format=tar", ref, binary=True)
    assert isinstance(archive_data, bytes)
    entries: dict[str, tuple[tarfile.TarInfo, bytes | None]] = {}
    with tarfile.open(fileobj=io.BytesIO(archive_data), mode="r:") as stream:
        for member in stream.getmembers():
            safe_name(member.name)
            if member.isdir():
                continue
            if member.islnk() or member.isdev() or not (member.isfile() or member.issym()):
                fail(f"unsupported Git archive member: {member.name}")
            data = None if member.issym() else stream.extractfile(member).read()
            entries[member.name] = (member, data)
    for name in sorted(entries):
        member, data = entries[name]
        destination = stage / prefix / name
        if member.issym():
            target = PurePosixPath(name).parent.joinpath(member.linkname).as_posix()
            safe_name(target)
            target_entry = entries.get(target)
            if target_entry is None or target_entry[0].issym() or target_entry[1] is None:
                fail(f"cannot materialize Git symlink {name} -> {member.linkname}")
            data = target_entry[1]
            normalized.append({"path": (PurePosixPath(prefix) / name).as_posix(), "target": target})
        assert data is not None
        if prefix == "sources/dtv-android" and name == "mirakc/src/main/assets/isdbt_rio.inp":
            continue
        write_file(destination, data, member.mode & 0o7777)


def submodules(root: Path, ref: str) -> list[tuple[str, str]]:
    raw = run_git(root, "ls-tree", "-r", "-z", ref, binary=True)
    assert isinstance(raw, bytes)
    result = []
    for record in raw.split(b"\0"):
        if not record:
            continue
        header, path = record.split(b"\t", 1)
        mode, kind, commit = header.decode("ascii").split()
        if mode == "160000" and kind == "commit":
            result.append((path.decode("utf-8", "strict"), commit))
    return result


def snapshot_repository(root: Path, ref: str, stage: Path, prefix: str, normalized: list[dict[str, str]], recurse: bool = True) -> None:
    copy_git_archive(root, ref, stage, prefix, normalized)
    if not recurse:
        return
    for path, commit in submodules(root, ref):
        child = root / path
        if not child.is_dir():
            fail(f"recursive submodule checkout is missing: {child}")
        actual = str(run_git(child, "rev-parse", "HEAD")).strip()
        if actual != commit:
            fail(f"submodule ref mismatch in {child}: expected {commit}, found {actual}")
        snapshot_repository(child, commit, stage, f"{prefix}/{path}", normalized, recurse=True)


def copy_archive_contents(archive: Path, stage: Path) -> None:
    if archive.name != LIBUSB_ARCHIVE_NAME or sha256(archive) != LIBUSB_ARCHIVE_SHA256:
        fail("the exact libusb 1.0.30 archive is required")
    write_file(stage / "third_party" / LIBUSB_ARCHIVE_NAME, archive.read_bytes())
    with tarfile.open(archive, "r:bz2") as stream:
        for member in stream.getmembers():
            safe_name(member.name)
            if member.isdir():
                continue
            if member.issym() or member.islnk() or member.isdev() or not member.isfile():
                fail(f"libusb archive contains an unsafe member: {member.name}")
            handle = stream.extractfile(member)
            if handle is None:
                fail(f"cannot extract libusb member: {member.name}")
            write_file(stage / "third_party" / member.name, handle.read(), member.mode & 0o7777)
    if not (stage / "third_party/libusb-1.0.30/COPYING").is_file():
        fail("libusb archive has no COPYING")


def copy_toolchain_archives(cache: Path, stage: Path) -> None:
    for relative, expected in TOOLCHAIN_ARCHIVES.items():
        source = cache / Path(relative).name
        if not source.is_file() or sha256(source) != expected:
            fail(f"missing or mismatched toolchain archive: {source}")
        write_file(stage / relative, source.read_bytes())


def component_files(stage: Path, prefix: str) -> list[str]:
    root = stage / prefix
    if not root.is_dir():
        fail(f"component source tree is missing: {prefix}")
    return sorted((path.relative_to(stage).as_posix() for path in root.rglob("*") if path.is_file()))


def license_candidates(files: list[str]) -> list[str]:
    result = []
    for path in files:
        name = PurePosixPath(path).name.lower()
        if name.startswith(("license", "licence", "copying", "notice", "third_party_notices", "dependency-notice")):
            result.append(path)
    return result


def tree_identity(stage: Path, files: list[str]) -> str:
    digest = hashlib.sha256()
    for path in files:
        digest.update(path.encode("utf-8"))
        digest.update(b"\0")
        digest.update(bytes.fromhex(sha256(stage / path)))
        digest.update(b"\n")
    return digest.hexdigest()


def write_json(path: Path, value: object) -> None:
    write_file(path, (json.dumps(value, indent=2, sort_keys=True) + "\n").encode("utf-8"))


def write_checksums(stage: Path) -> None:
    rows = []
    for path in sorted(stage.rglob("*")):
        if path.is_file() and path.name != "SHA256SUMS":
            rows.append(f"{sha256(path)}  {path.relative_to(stage).as_posix()}")
    write_file(stage / "SHA256SUMS", ("\n".join(rows) + "\n").encode("ascii"))


def deterministic_tar(stage: Path, output: Path) -> None:
    with output.open("wb") as raw:
        with gzip.GzipFile(fileobj=raw, mode="wb", filename="", mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode="w|", format=tarfile.PAX_FORMAT) as stream:
                for path in sorted(stage.rglob("*")):
                    if path.is_symlink():
                        fail(f"symlink reached archive writer: {path}")
                    if not path.is_file():
                        continue
                    info = tarfile.TarInfo(path.relative_to(stage).as_posix())
                    info.size = path.stat().st_size
                    info.mode = path.stat().st_mode & 0o7777
                    info.uid = info.gid = 0
                    info.uname = info.gname = ""
                    info.mtime = 0
                    with path.open("rb") as handle:
                        stream.addfile(info, handle)


def build_manifest(stage: Path, commits: dict[str, str], normalized: list[dict[str, str]], dtv_version: str, dtv_tree: str) -> None:
    components = []
    inventory = []
    for name, expected in EXPECTED.items():
        component = {
            "name": name,
            "version": dtv_version if name == "dtv-android" else expected["version"],
            "url": expected["url"],
            "spdx": expected["spdx"],
        }
        if expected.get("excluded"):
            component.update({
                "commit": expected["commit"],
                "license_url": FIRMWARE_LICENSE_URL,
                "license_sha256": FIRMWARE_LICENSE_SHA256,
                "sha256": FIRMWARE_SHA256,
                "excluded_from_archive": True,
                "files": ["sources/dtv-android/mirakc/src/main/assets/LICENCE.siano"],
                "license_files": [{
                    "path": "sources/dtv-android/mirakc/src/main/assets/LICENCE.siano",
                    "sha256": FIRMWARE_LICENSE_SHA256,
                }],
            })
            inventory.append({
                "component": name,
                "path": "sources/dtv-android/mirakc/src/main/assets/LICENCE.siano",
                "sha256": FIRMWARE_LICENSE_SHA256,
            })
        else:
            prefix = expected["prefix"]
            files = component_files(stage, prefix)
            component["commit"] = commits.get(name, expected.get("commit", ""))
            if name == "dtv-android":
                component["tree"] = dtv_tree
            if name == "libarib25":
                component["commit"] = "vendored-tree-sha256:" + tree_identity(stage, files)
            component["files"] = files
            license_files = []
            for path in license_candidates(files):
                digest = sha256(stage / path)
                item = {"path": path, "sha256": digest}
                license_files.append(item)
                inventory.append({"component": name, **item})
            if not license_files:
                fail(f"component has no license files: {name}")
            component["license_files"] = license_files
        components.append(component)
    inventory.sort(key=lambda item: (item["component"], item["path"]))
    write_json(stage / "LICENSE-INVENTORY.json", inventory)
    files = {}
    for path in sorted(stage.rglob("*")):
        if path.is_file() and path.name not in ("source-manifest.json", "SHA256SUMS"):
            files[path.relative_to(stage).as_posix()] = {"size": path.stat().st_size, "sha256": sha256(path)}
    manifest = {
        "schema": 1,
        "kind": "mirakc-corresponding-source",
        "components": components,
        "license_inventory": inventory,
        "files": files,
        "toolchain_manifest": "toolchain-manifest.json",
        "normalizations": normalized,
        "excluded_materials": {
            "siano_firmware_url": FIRMWARE_URL,
            "siano_firmware_sha256": FIRMWARE_SHA256,
            "siano_firmware_license_url": FIRMWARE_LICENSE_URL,
            "siano_firmware_license_sha256": FIRMWARE_LICENSE_SHA256,
            "reason": "binary firmware excluded because linux-firmware supplies no corresponding source; redistribution is permitted in binary form",
        },
    }
    write_json(stage / "source-manifest.json", manifest)


def package(args: argparse.Namespace) -> Path:
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    output = output_dir / "mirakc-corresponding-source.tar.gz"
    if output.exists() or output.is_symlink():
        fail(f"refusing to overwrite existing archive: {output}")
    repos = {name: Path(value).resolve() for name, value in {
        "dtv-android": args.dtv_root,
        "mirakc": args.mirakc_root,
        "mirakc-arib": args.arib_root,
        "siano-userland": args.siano_root,
        "px4-userland": args.px4_root,
        "px4_drv": args.px4_drv_root,
    }.items()}
    dtv_commit = str(run_git(repos["dtv-android"], "rev-parse", f"{args.dtv_ref}^{{commit}}")).strip()
    dtv_tree = str(run_git(repos["dtv-android"], "rev-parse", f"{args.dtv_ref}^{{tree}}")).strip()
    dtv_version = str(run_git(repos["dtv-android"], "show", f"{args.dtv_ref}:mirakc/VERSION")).strip()
    if len(dtv_commit) != 40 or any(character not in "0123456789abcdef" for character in dtv_commit):
        fail("resolved DTV commit is not canonical")
    if len(dtv_tree) != 40 or any(character not in "0123456789abcdef" for character in dtv_tree):
        fail("resolved DTV tree is not canonical")
    if not dtv_version:
        fail("mirakc/VERSION is empty in the DTV ref")
    ensure_pinned_clean(repos["dtv-android"], str(run_git(repos["dtv-android"], "rev-parse", "HEAD")).strip())
    for name in ("mirakc", "mirakc-arib", "siano-userland", "px4-userland", "px4_drv"):
        ensure_pinned_clean(repos[name], EXPECTED[name]["commit"], allow_dirty_submodules=name == "mirakc-arib")
    with tempfile.TemporaryDirectory(prefix="mirakc-source-") as temporary:
        stage = Path(temporary) / "stage"
        stage.mkdir()
        normalized: list[dict[str, str]] = []
        for name in ("dtv-android", "mirakc", "mirakc-arib", "siano-userland", "px4-userland", "px4_drv"):
            ref = args.dtv_ref if name == "dtv-android" else EXPECTED[name]["commit"]
            snapshot_repository(repos[name], ref, stage, f"sources/{name}", normalized, recurse=name in ("mirakc-arib",))
        arib25_files = component_files(stage, "sources/dtv-android/mirakc/src/main/cpp/arib25")
        for path in arib25_files:
            relative = path.removeprefix("sources/dtv-android/mirakc/src/main/cpp/arib25/")
            write_file(stage / "sources/libarib25" / relative, (stage / path).read_bytes(), (stage / path).stat().st_mode & 0o7777)
        copy_archive_contents(args.libusb_archive.resolve(), stage)
        copy_toolchain_archives(args.autotools_cache.resolve(), stage)
        write_json(stage / "toolchain-manifest.json", TOOLCHAIN)
        write_file(stage / "DEPENDENCY-NOTICE.txt", (
            "firmware.status=excluded-from-source-archive\n"
            f"firmware.url={FIRMWARE_URL}\n"
            f"firmware.sha256={FIRMWARE_SHA256}\n"
            f"firmware.license.url={FIRMWARE_LICENSE_URL}\n"
            f"firmware.license.sha256={FIRMWARE_LICENSE_SHA256}\n"
            "libusb.linkage=static\n"
            f"libusb.archive.sha256={LIBUSB_ARCHIVE_SHA256}\n"
            "source.archive.contains=recursive-submodules,android-patches,build-inputs,relink-material\n"
        ).encode("utf-8"))
        commits = {name: EXPECTED[name]["commit"] for name in repos if name != "dtv-android"}
        commits["dtv-android"] = dtv_commit
        build_manifest(stage, commits, normalized, dtv_version, dtv_tree)
        write_checksums(stage)
        deterministic_tar(stage, output)
    audit_source_archive(output, expected_dtv_commit=dtv_commit)
    return output


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--dtv-root", type=Path, default=Path("."))
    parser.add_argument("--dtv-ref", default="HEAD")
    parser.add_argument("--mirakc-root", type=Path, default=Path(".work/mirakc-3.4.86"))
    parser.add_argument("--arib-root", type=Path, default=Path(".work/mirakc-arib-0.24.38"))
    parser.add_argument("--siano-root", type=Path, default=Path(".work/pinned-siano-userland"))
    parser.add_argument("--px4-root", type=Path, default=Path(".work/pinned-px4-userland"))
    parser.add_argument("--px4-drv-root", type=Path, default=Path(".work/pinned-px4_drv"))
    parser.add_argument("--libusb-archive", type=Path, default=Path("/config/GitHub/siano-userland/build/android-aarch64/src/libusb-1.0.30.tar.bz2"))
    parser.add_argument("--autotools-cache", type=Path, default=Path("/config/.work/mirakc-arib-tools/autotools-sources"))
    args = parser.parse_args()
    output = package(args)
    print(f"aggregate source package: {output}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AuditError, OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print(f"package-source: {error}", file=sys.stderr)
        raise SystemExit(1)
