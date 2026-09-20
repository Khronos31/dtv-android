#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
"""Fail-closed audit of source/license provenance embedded in a mirakc APK."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import PurePosixPath
from pathlib import Path
import re
import sys
import zipfile


HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("mirakc_source_audit", HERE / "audit-source.py")
if spec is None or spec.loader is None:
    raise ImportError("cannot load audit-source.py")
source_audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(source_audit)
EXPECTED = source_audit.EXPECTED
ARIB_SUBMODULES = source_audit.MIRAKC_ARIB_SUBMODULES
ARIB_APK_SUBMODULES = source_audit.MIRAKC_ARIB_APK_SUBMODULES
LIBARIB25_TREE_IDENTITY = source_audit.LIBARIB25_TREE_IDENTITY


class ApkAuditError(Exception):
    pass


def fail(message: str) -> None:
    raise ApkAuditError(message)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def safe_zip_name(name: str) -> None:
    path = PurePosixPath(name)
    if not name or name.startswith("/") or "\\" in name or ".." in path.parts:
        fail(f"unsafe APK entry: {name!r}")


def read_apk(path, expected_dtv_commit: str | None) -> None:
    try:
        archive = zipfile.ZipFile(path)
    except (OSError, zipfile.BadZipFile) as error:
        fail(f"cannot read APK: {error}")
    with archive:
        infos = archive.infolist()
        names = [info.filename for info in infos]
        for name in names:
            safe_zip_name(name)
            if name.startswith("assets/source-metadata/") and name.endswith("/"):
                fail(f"APK source metadata contains a directory entry: {name}")
        if len(names) != len(set(names)):
            fail("APK contains duplicate ZIP entries")
        prefix = "assets/source-metadata/"
        metadata_names = sorted(name for name in names if name.startswith(prefix))
        manifest_name = prefix + "manifest.json"
        if manifest_name not in metadata_names:
            fail("APK source metadata manifest is missing")
        payloads = {name: archive.read(name) for name in metadata_names}
        try:
            manifest = json.loads(payloads[manifest_name].decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            fail(f"invalid APK source metadata manifest: {error}")
        if not isinstance(manifest, dict) or manifest.get("schema") != 1 or manifest.get("kind") != "mirakc-apk-source-metadata":
            fail("APK source metadata schema/kind mismatch")
        files = manifest.get("files")
        if not isinstance(files, dict):
            fail("APK source metadata files map is missing")
        actual_files = {
            name.removeprefix("assets/") for name in metadata_names if name != manifest_name
        }
        if set(files) != actual_files:
            fail("APK source metadata files map is not exact")
        for relative, metadata in files.items():
            apk_name = "assets/" + relative
            if not isinstance(metadata, dict) or metadata.get("sha256") != sha256(payloads[apk_name]):
                fail(f"APK source metadata digest mismatch: {relative}")
            if metadata.get("size") != len(payloads[apk_name]):
                fail(f"APK source metadata size mismatch: {relative}")
        components = manifest.get("components")
        inventory = manifest.get("license_inventory")
        if not isinstance(components, list) or not isinstance(inventory, list):
            fail("APK source metadata components/inventory missing")
        if len({component.get("name") for component in components if isinstance(component, dict)}) != len(components):
            fail("APK source metadata contains duplicate components")
        expected_names = set(EXPECTED) | set(ARIB_APK_SUBMODULES)
        actual_names = {component.get("name") for component in components if isinstance(component, dict)}
        if actual_names != expected_names:
            fail("APK source metadata component set mismatch")
        by_name = {component["name"]: component for component in components}
        dtv = by_name["dtv-android"]
        if not re.fullmatch(r"[0-9a-f]{40}", str(dtv.get("commit", ""))) or not re.fullmatch(r"[0-9a-f]{40}", str(dtv.get("tree", ""))):
            fail("APK DTV commit/tree is not canonical")
        if expected_dtv_commit is not None and dtv["commit"] != expected_dtv_commit:
            fail("APK DTV commit mismatch")
        for name, expected in EXPECTED.items():
            component = by_name[name]
            for field in ("url", "spdx"):
                if component.get(field) != expected[field]:
                    fail(f"APK component {name} field mismatch: {field}")
            if name != "dtv-android" and component.get("version") != expected["version"]:
                fail(f"APK component {name} version mismatch")
            if name == "dtv-android" and not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?", str(component.get("version", ""))):
                fail("APK DTV version is invalid")
            if name == "libarib25":
                if component.get("commit") != "vendored-tree-sha256:" + LIBARIB25_TREE_IDENTITY:
                    fail("APK libarib25 tree identity is invalid")
            elif name != "dtv-android" and component.get("commit") != expected.get("commit"):
                fail(f"APK component {name} commit mismatch")
            if name == "linux-firmware-siano":
                if component.get("license_url") != expected["license_url"] or component.get("license_sha256") != expected["license_sha256"] or component.get("sha256") != expected["sha256"]:
                    fail("APK Siano firmware provenance mismatch")
                firmware = component.get("firmware_binary")
                if firmware != {"apk_path": "assets/isdbt_rio.inp", "sha256": expected["sha256"], "corresponding_source": False}:
                    fail("APK firmware binary provenance mismatch")
        for name, (commit, url) in ARIB_APK_SUBMODULES.items():
            component = by_name[name]
            if component.get("commit") != commit or component.get("url") != url:
                fail(f"APK recursive submodule pin mismatch: {name}")
            if component.get("spdx") != "LicenseRef-Bundled-" + name.replace("/", "-"):
                fail(f"APK recursive submodule license basis mismatch: {name}")
        flattened = []
        for component in components:
            if not isinstance(component.get("packaged_native_consumers"), list) or not component["packaged_native_consumers"]:
                fail(f"APK component role inventory is missing: {component.get('name')}")
            if component.get("license_basis") != "bundled-exact-files":
                fail(f"APK component license basis is not exact: {component.get('name')}")
            license_files = component.get("license_files")
            if not isinstance(license_files, list) or not license_files:
                fail(f"APK component has no license inventory: {component.get('name')}")
            for item in license_files:
                if not isinstance(item, dict) or item.get("apk_path") in {None, ""}:
                    fail("APK license inventory entry is malformed")
                apk_name = "assets/" + item["apk_path"]
                if apk_name not in payloads or item.get("sha256") != sha256(payloads[apk_name]) or item.get("size") != len(payloads[apk_name]):
                    fail(f"APK license file mismatch: {apk_name}")
                flattened.append({"component": component["name"], **item})
        if sorted(flattened, key=lambda item: (item["component"], item["apk_path"])) != sorted(inventory, key=lambda item: (item.get("component", ""), item.get("apk_path", ""))):
            fail("APK license inventory is not identical to component inventories")
        license_paths = [item["apk_path"] for item in flattened]
        if len(license_paths) != len(set(license_paths)):
            fail("APK license inventory contains duplicate paths")
        firmware = archive.read("assets/isdbt_rio.inp") if "assets/isdbt_rio.inp" in names else None
        if firmware is None or sha256(firmware) != EXPECTED["linux-firmware-siano"]["sha256"]:
            fail("APK Siano firmware asset is missing or mismatched")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk")
    parser.add_argument("--expected-dtv-commit")
    args = parser.parse_args()
    read_apk(args.apk, args.expected_dtv_commit)
    print(f"APK source metadata verified: {args.apk}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ApkAuditError, OSError, ValueError, KeyError) as error:
        print(f"audit-apk-metadata: {error}", file=sys.stderr)
        raise SystemExit(1)
