#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
"""Generate deterministic, APK-embedded source and license provenance."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import sys
import tempfile


HERE = Path(__file__).resolve().parent
AUDIT_PATH = HERE / "audit-source.py"
spec = importlib.util.spec_from_file_location("mirakc_source_audit", AUDIT_PATH)
if spec is None or spec.loader is None:
    raise ImportError("cannot load audit-source.py")
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)
EXPECTED = audit.EXPECTED
ARIB_SUBMODULES = audit.MIRAKC_ARIB_SUBMODULES
ARIB_APK_SUBMODULES = audit.MIRAKC_ARIB_APK_SUBMODULES
LIBARIB25_TREE_IDENTITY = audit.LIBARIB25_TREE_IDENTITY
FIRMWARE_SHA256 = EXPECTED["linux-firmware-siano"]["sha256"]
FIRMWARE_LICENSE_SHA256 = EXPECTED["linux-firmware-siano"]["license_sha256"]


class MetadataError(Exception):
    pass


def fail(message: str) -> None:
    raise MetadataError(message)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def git(root: Path, *args: str) -> str:
    try:
        result = subprocess.run(
            ["git", "-C", str(root), *args], check=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        )
    except (OSError, subprocess.CalledProcessError) as error:
        fail(f"git command failed in {root}: {error}")
    return result.stdout.strip()


def git_bytes(root: Path, *args: str) -> bytes:
    try:
        result = subprocess.run(
            ["git", "-C", str(root), *args], check=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
    except (OSError, subprocess.CalledProcessError) as error:
        fail(f"git command failed in {root}: {error}")
    return result.stdout


def check_checkout(root: Path, expected: str, name: str) -> str:
    if not root.is_dir():
        fail(f"{name} checkout is missing: {root}")
    actual = git(root, "rev-parse", "HEAD")
    if actual != expected:
        fail(f"{name} HEAD mismatch: expected {expected}, found {actual}")
    status = subprocess.run(
        ["git", "-C", str(root), "status", "--porcelain", "--untracked-files=all", "--ignore-submodules=dirty"],
        check=False, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
    )
    if status.returncode != 0 or status.stdout:
        fail(f"{name} checkout is not clean: {root}")
    return actual


def candidate(path: str) -> bool:
    name = PurePosixPath(path).name.lower()
    return name.startswith(("license", "licence", "copying", "notice", "third_party_notices", "dependency-notice"))


def tracked_license_paths(root: Path, ref: str) -> list[str]:
    raw = git_bytes(root, "ls-tree", "-r", "--name-only", ref)
    # The caller reads these paths from the Git object below.  Do not inspect
    # the worktree here: a dirty submodule must never replace bytes belonging
    # to the pinned ref in the APK inventory.
    return sorted(path for path in raw.decode("utf-8").splitlines() if candidate(path))


def submodules(root: Path, ref: str) -> list[tuple[str, str]]:
    result: list[tuple[str, str]] = []
    for record in git_bytes(root, "ls-tree", "-r", "-z", ref).split(b"\0"):
        if not record:
            continue
        header, path = record.split(b"\t", 1)
        mode, kind, commit = header.decode("ascii").split()
        if mode == "160000" and kind == "commit":
            result.append((path.decode("utf-8"), commit))
    return result


def module_url(root: Path, path: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(root), "config", "-f", ".gitmodules", "--get", f"submodule.{path}.url"],
        check=False, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
    )
    if result.returncode != 0 or not result.stdout.strip():
        fail(f"missing .gitmodules URL for {root}/{path}")
    return result.stdout.strip()


def copy_license(output: Path, source: Path, component_key: str, relative: str, seen: set[str], data: bytes | None = None) -> dict[str, str]:
    if data is None:
        if not source.is_file() or source.is_symlink():
            fail(f"license/notice file is missing or symlinked: {source}")
        data = source.read_bytes()
    safe_component = component_key.replace("/", "__")
    safe_relative = PurePosixPath(relative)
    if safe_relative.is_absolute() or ".." in safe_relative.parts:
        fail(f"unsafe license path: {relative}")
    apk_path = PurePosixPath("source-metadata/licenses") / safe_component / safe_relative
    apk_name = apk_path.as_posix()
    if apk_name in seen:
        fail(f"license destination collision: {apk_name}")
    seen.add(apk_name)
    destination = output / apk_path
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(data)
    return {"source_path": relative, "apk_path": apk_name, "sha256": digest(data), "size": len(data)}


def add_component(output: Path, components: list[dict], seen: set[str], *, name: str, version: str, commit: str,
                  url: str, spdx: str, role: list[str], root: Path, ref: str, license_paths: list[str] | None = None,
                  tree: str | None = None, source_inputs: list[dict[str, str]] | None = None,
                  git_license_ref: str | None = None) -> None:
    licenses = []
    for relative in license_paths if license_paths is not None else tracked_license_paths(root, ref):
        pinned_data = git_bytes(root, "show", f"{git_license_ref}:{relative}") if git_license_ref is not None else None
        licenses.append(copy_license(output, root / relative, name, relative, seen, pinned_data))
    if not licenses:
        fail(f"component has no license/notice files: {name}")
    component = {
        "name": name,
        "version": version,
        "commit": commit,
        "url": url,
        "spdx": spdx,
        "license_basis": "bundled-exact-files",
        "packaged_native_consumers": sorted(role),
        "license_files": licenses,
    }
    if tree is not None:
        component["tree"] = tree
    if source_inputs:
        component["source_inputs"] = source_inputs
    components.append(component)


def add_recursive_submodules(output: Path, root: Path, ref: str, parent: str, role: list[str], components: list[dict], seen: set[str]) -> None:
    for path, commit in submodules(root, ref):
        child = root / path
        if not child.is_dir():
            fail(f"recursive submodule checkout is missing: {child}")
        if git(child, "rev-parse", "HEAD") != commit:
            fail(f"submodule ref mismatch: {child}")
        key = f"{parent}/{path}"
        expected = ARIB_SUBMODULES.get(key)
        if expected is None or expected != (commit, module_url(root, path)):
            fail(f"unexpected or mismatched mirakc-arib submodule: {key}")
        if key in ARIB_APK_SUBMODULES:
            add_component(
                output, components, seen, name=key, version="vendored", commit=commit,
                url=expected[1], spdx="LicenseRef-Bundled-" + key.replace("/", "-"), role=role,
                root=child, ref=commit, git_license_ref=commit,
            )
        add_recursive_submodules(output, child, commit, key, role, components, seen)


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def generate(args: argparse.Namespace) -> Path:
    output = args.output.resolve()
    if output == Path("/") or len(output.parts) < 4:
        fail(f"refusing unsafe metadata output path: {output}")
    if output.exists() and output.is_symlink():
        fail("metadata output must not be a symlink")
    if output.exists():
        for child in output.iterdir():
            if child.is_dir() and not child.is_symlink():
                shutil.rmtree(child)
            elif child.is_file() or child.is_symlink():
                child.unlink()
    output.mkdir(parents=True, exist_ok=True)

    dtv_root = args.dtv_root.resolve()
    dtv_commit = git(dtv_root, "rev-parse", f"{args.dtv_ref}^{{commit}}")
    dtv_tree = git(dtv_root, "rev-parse", f"{args.dtv_ref}^{{tree}}")
    dtv_version = git(dtv_root, "show", f"{args.dtv_ref}:mirakc/VERSION")
    check_checkout(dtv_root, git(dtv_root, "rev-parse", "HEAD"), "dtv-android")
    components: list[dict] = []
    seen: set[str] = set()
    add_component(output, components, seen, name="dtv-android", version=dtv_version, commit=dtv_commit,
                  tree=dtv_tree, url=EXPECTED["dtv-android"]["url"], spdx=EXPECTED["dtv-android"]["spdx"],
                  role=["application", "native build harness"], root=dtv_root, ref=args.dtv_ref,
                  license_paths=["LICENSE"], git_license_ref=args.dtv_ref)

    mirakc_root = args.mirakc_root.resolve()
    check_checkout(mirakc_root, EXPECTED["mirakc"]["commit"], "mirakc")
    add_component(output, components, seen, name="mirakc", version=EXPECTED["mirakc"]["version"],
                  commit=EXPECTED["mirakc"]["commit"], url=EXPECTED["mirakc"]["url"], spdx=EXPECTED["mirakc"]["spdx"],
                  role=["libmirakc.so"], root=mirakc_root, ref=EXPECTED["mirakc"]["commit"],
                  source_inputs=[{"path": "Cargo.lock", "sha256": digest((mirakc_root / "Cargo.lock").read_bytes())}],
                  git_license_ref=EXPECTED["mirakc"]["commit"])

    arib_root = args.arib_root.resolve()
    check_checkout(arib_root, EXPECTED["mirakc-arib"]["commit"], "mirakc-arib")
    add_component(output, components, seen, name="mirakc-arib", version=EXPECTED["mirakc-arib"]["version"],
                  commit=EXPECTED["mirakc-arib"]["commit"], url=EXPECTED["mirakc-arib"]["url"], spdx=EXPECTED["mirakc-arib"]["spdx"],
                  role=["libmirakc-arib.so"], root=arib_root, ref=EXPECTED["mirakc-arib"]["commit"],
                  git_license_ref=EXPECTED["mirakc-arib"]["commit"])
    add_recursive_submodules(output, arib_root, EXPECTED["mirakc-arib"]["commit"], "mirakc-arib", ["libmirakc-arib.so"], components, seen)
    if {component["name"] for component in components if component["name"].startswith("mirakc-arib/")} != set(ARIB_APK_SUBMODULES):
        fail("mirakc-arib recursive submodule inventory is incomplete")

    for name, root_arg, role in (("siano-userland", args.siano_root, ["libsiano-ts.so"]),
                                 ("px4-userland", args.px4_root, ["libpx4d.so", "libpx4-ts.so", "libpx4ctl.so"]),
                                 ("px4_drv", args.px4_drv_root, ["libmirakc-px4-fwtool.so"])):
        root = root_arg.resolve()
        check_checkout(root, EXPECTED[name]["commit"], name)
        add_component(output, components, seen, name=name, version=EXPECTED[name]["version"],
                      commit=EXPECTED[name]["commit"], url=EXPECTED[name]["url"], spdx=EXPECTED[name]["spdx"],
                      role=role, root=root, ref=EXPECTED[name]["commit"], git_license_ref=EXPECTED[name]["commit"])

    arib25_root = args.arib25_root.resolve()
    if not arib25_root.is_dir():
        fail(f"vendored libarib25 tree is missing: {arib25_root}")
    arib25_files = sorted(p for p in arib25_root.rglob("*") if p.is_file() and not p.is_symlink())
    if not arib25_files:
        fail("vendored libarib25 tree is empty")
    arib25_tree = hashlib.sha256()
    for path in arib25_files:
        relative = path.relative_to(arib25_root).as_posix()
        arib25_tree.update(("sources/libarib25/" + relative).encode() + b"\0" + bytes.fromhex(digest(path.read_bytes())) + b"\n")
    if arib25_tree.hexdigest() != LIBARIB25_TREE_IDENTITY:
        fail("vendored libarib25 tree identity mismatch")
    add_component(output, components, seen, name="libarib25", version="vendored", commit="vendored-tree-sha256:" + arib25_tree.hexdigest(),
                  url=EXPECTED["libarib25"]["url"], spdx=EXPECTED["libarib25"]["spdx"], role=["libmirakc-b25-filter.so", "libmirakc-px4-adapter.so"],
                  root=arib25_root, ref=".", license_paths=[str(p.relative_to(arib25_root)) for p in arib25_files if candidate(p.name)])

    libusb = args.libusb_archive.resolve()
    if not libusb.is_file():
        fail(f"libusb archive is missing: {libusb}")
    libusb_digest = digest(libusb.read_bytes())
    if libusb_digest != "fea36f34f9156400209595e300840767ab1a385ede1dc7ee893015aea9c6dbaf":
        fail("libusb archive digest mismatch")
    with tempfile.TemporaryDirectory(prefix="mirakc-apk-license-") as temporary:
        extract = Path(temporary)
        import tarfile
        with tarfile.open(libusb, "r:bz2") as archive:
            member = archive.getmember("libusb-1.0.30/COPYING")
            license_data = archive.extractfile(member).read()
        libusb_license = extract / "COPYING"
        libusb_license.write_bytes(license_data)
        add_component(output, components, seen, name="libusb", version="1.0.30", commit="archive-sha256:" + libusb_digest,
                      url=EXPECTED["libusb"]["url"], spdx=EXPECTED["libusb"]["spdx"], role=["libsiano-ts.so", "libpx4d.so"],
                      root=extract, ref=".", license_paths=["COPYING"])

    inventory = sorted((entry for component in components for entry in component["license_files"] for entry in [
        {"component": component["name"], **entry}
    ]), key=lambda entry: (entry["component"], entry["apk_path"]))
    firmware_license = output / "source-metadata/licenses/linux-firmware-siano/LICENCE.siano"
    if firmware_license.relative_to(output).as_posix() in seen:
        fail("firmware license destination collision")
    seen.add(firmware_license.relative_to(output).as_posix())
    firmware_license.parent.mkdir(parents=True, exist_ok=True)
    siano_license = args.siano_root.resolve() / "LICENCE.siano"
    if not siano_license.is_file() or digest(siano_license.read_bytes()) != FIRMWARE_LICENSE_SHA256:
        fail("Siano firmware license digest mismatch")
    firmware_license.write_bytes(siano_license.read_bytes())
    firmware_entry = {"source_path": "LICENSES/LICENCE.siano", "apk_path": firmware_license.relative_to(output).as_posix(), "sha256": FIRMWARE_LICENSE_SHA256, "size": firmware_license.stat().st_size}
    inventory.append({"component": "linux-firmware-siano", **firmware_entry})
    components.append({
        "name": "linux-firmware-siano", "version": EXPECTED["linux-firmware-siano"]["version"],
        "commit": EXPECTED["linux-firmware-siano"]["commit"], "url": EXPECTED["linux-firmware-siano"]["url"],
        "spdx": EXPECTED["linux-firmware-siano"]["spdx"], "license_basis": "bundled-exact-files",
        "packaged_native_consumers": ["firmware asset"],
        "sha256": FIRMWARE_SHA256,
        "firmware_binary": {"apk_path": "assets/isdbt_rio.inp", "sha256": FIRMWARE_SHA256, "corresponding_source": False},
        "license_files": [firmware_entry], "license_url": EXPECTED["linux-firmware-siano"]["license_url"],
        "license_sha256": FIRMWARE_LICENSE_SHA256,
    })
    inventory.sort(key=lambda entry: (entry["component"], entry["apk_path"]))
    manifest = {"schema": 1, "kind": "mirakc-apk-source-metadata", "components": sorted(components, key=lambda item: item["name"]), "license_inventory": inventory}
    files = {}
    manifest_path = output / "source-metadata/manifest.json"
    for path in sorted(output.rglob("*")):
        if path.is_file() and path != manifest_path:
            files[path.relative_to(output).as_posix()] = {"sha256": digest(path.read_bytes()), "size": path.stat().st_size}
    manifest["files"] = files
    write_json(manifest_path, manifest)
    return output


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--dtv-root", type=Path, required=True)
    parser.add_argument("--dtv-ref", default="HEAD")
    parser.add_argument("--mirakc-root", type=Path, required=True)
    parser.add_argument("--arib-root", type=Path, required=True)
    parser.add_argument("--siano-root", type=Path, required=True)
    parser.add_argument("--px4-root", type=Path, required=True)
    parser.add_argument("--px4-drv-root", type=Path, required=True)
    parser.add_argument("--arib25-root", type=Path, required=True)
    parser.add_argument("--libusb-archive", type=Path, required=True)
    args = parser.parse_args()
    print(f"generated APK source metadata: {generate(args)}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (MetadataError, OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print(f"generate-apk-metadata: {error}", file=sys.stderr)
        raise SystemExit(1)
