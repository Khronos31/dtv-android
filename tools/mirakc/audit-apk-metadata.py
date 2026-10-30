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
APK_EXPECTED = {name: item for name, item in EXPECTED.items() if name != "px4_drv"}
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


def read_apk(path, expected_dtv_commit: str | None, allow_development_worktree: bool) -> None:
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
        expected_names = set(APK_EXPECTED) | set(ARIB_APK_SUBMODULES) | {"asicen-loader-firmware"}
        actual_names = {component.get("name") for component in components if isinstance(component, dict)}
        if actual_names != expected_names:
            fail("APK source metadata component set mismatch")
        by_name = {component["name"]: component for component in components}
        dtv = by_name["dtv-android"]
        if not re.fullmatch(r"[0-9a-f]{40}", str(dtv.get("commit", ""))) or not re.fullmatch(r"[0-9a-f]{40}", str(dtv.get("tree", ""))):
            fail("APK DTV commit/tree is not canonical")
        development = dtv.get("development_source")
        if development is not None:
            if not allow_development_worktree:
                fail("development APK source metadata is not allowed by this audit")
            if not isinstance(development, dict) or development.get("mode") != "working-tree-overlay":
                fail("APK development source identity is invalid")
            if development.get("base_commit") != dtv["commit"] or development.get("base_tree") != dtv["tree"]:
                fail("APK development source base does not match DTV commit/tree")
            overlay_files = development.get("files")
            deleted_paths = development.get("deleted_paths")
            if not isinstance(overlay_files, list) or not isinstance(deleted_paths, list):
                fail("APK development source file list is invalid")
            if any(not isinstance(item, dict) or not isinstance(item.get("path"), str)
                   or not re.fullmatch(r"[0-9a-f]{64}", str(item.get("sha256", "")))
                   or not isinstance(item.get("size"), int) for item in overlay_files):
                fail("APK development source file record is invalid")
            overlay_files = sorted(overlay_files, key=lambda item: item["path"])
            identity = {"base_commit": dtv["commit"], "base_tree": dtv["tree"],
                        "files": overlay_files, "deleted_paths": deleted_paths}
            expected_overlay = sha256(json.dumps(identity, sort_keys=True, separators=(",", ":")).encode())
            if development.get("overlay_sha256") != expected_overlay:
                fail("APK development source overlay digest mismatch")
            development_prefix = "source-metadata/development-source/"
            actual_overlay_paths = {name.removeprefix(development_prefix) for name in files
                                    if name.startswith(development_prefix)}
            if actual_overlay_paths != {item["path"] for item in overlay_files}:
                fail("APK development source files do not match the embedded overlay")
            for item in overlay_files:
                metadata = files[development_prefix + item["path"]]
                data = payloads["assets/" + development_prefix + item["path"]]
                if metadata.get("sha256") != item["sha256"] or metadata.get("size") != item["size"] or len(data) != item["size"]:
                    fail("APK development source file digest/size mismatch")
        elif allow_development_worktree:
            fail("development source identity is missing")
        if expected_dtv_commit is not None and dtv["commit"] != expected_dtv_commit:
            fail("APK DTV commit mismatch")
        for name, expected in APK_EXPECTED.items():
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
            if name == "px4-userland":
                expected_source_inputs = [{
                    "path": source_audit.PX4_BUILD_JOBS_PATCH_PATH.removeprefix("sources/dtv-android/"),
                    "sha256": source_audit.PX4_BUILD_JOBS_PATCH_SHA256,
                }]
                if component.get("source_inputs") != expected_source_inputs:
                    fail("APK PX4 bounded-job patch provenance mismatch")
            if name == "dtv-userland-core":
                expected_source_inputs = [{
                    "path": source_audit.CORE_ANDROID_PATCH_PATH.removeprefix("sources/dtv-android/"),
                    "sha256": source_audit.CORE_ANDROID_PATCH_SHA256,
                }]
                if component.get("source_inputs") != expected_source_inputs:
                    fail("APK Core concurrency patch provenance mismatch")
                expected_relationships = {
                    "project_sources": {"spdx": "MIT", "license_file": "LICENSE"},
                    "statically_linked_adapters": [
                        {"spdx": "GPL-2.0-only", "source_prefix": "adapters/host_daemon/", "license_file": "adapters/host_daemon/COPYING"},
                        {"spdx": "GPL-2.0-only", "source_prefix": "adapters/host_ts/", "license_file": "adapters/host_ts/COPYING"},
                    ],
                }
                if component.get("license_relationships") != expected_relationships:
                    fail("APK Core source/license relationship inventory mismatch")
            if name == "siano-userland":
                expected_px4_revision = (
                    EXPECTED["siano-px4-userland"]["commit"] + "\n"
                ).encode("ascii")
                if component.get("source_inputs") != [{
                    "path": "cmake/px4-userland.revision",
                    "sha256": sha256(expected_px4_revision),
                }]:
                    fail("APK Siano nested PX4 provenance mismatch")
            if name == "asicen-userland":
                if component.get("commit") != EXPECTED["asicen-userland"]["commit"]:
                    fail("APK ASICEN source pin mismatch")
            if name == "pix-userland":
                expected_assets = [
                    {"path": "firmware/dt181.bin", "sha256": "a8532036b2995c869f713714b85609d2d21508f8848b96e5c7528ab75f3dd7a0"},
                    {"path": "firmware/dt295.bin", "sha256": "3cabe15f585a8a7a7d7b9b3db548b677b0732c13926dc3a2b5270acf1bc6ffc8"},
                    {"path": "firmware/manifest.json", "sha256": "b11b16d1e4af0fb83db14be5275d04332e03a0c780f4a0934fa4855d0f847ed9"},
                    {"path": "firmware/metadata/dt181-source.json", "sha256": "429531563d5bca117d219883f34534ad2f3ec77420d981c385b9b52effcac368"},
                    {"path": "firmware/metadata/dt295-preservation.json", "sha256": "029c4f73d32bc2b539115db22cc9214b9d146d6856560d5cc0cceb365c029884"},
                    {"path": "firmware/metadata/dt295-satellite-generation.json", "sha256": "518eb54c51950e4220d6269e356b0d82fdb56284223bae19d6f3635814a745e5"},
                    {"path": "firmware/preserved/verified-ts-iv.bin", "sha256": "69a206b3bbcd395b81b201f762355fb224c4e9e16439090663b8dfc1f5b03301"},
                    {"path": "THIRD_PARTY_NOTICES.md", "sha256": "f5c8e0c590c08cf846b5976e561fd5c6c57ac8dd0dccfe06c22feaadfd633efd"},
                ]
                actual_assets = component.get("source_inputs")
                if not isinstance(actual_assets, list) or [item.get("path") for item in actual_assets] != [item["path"] for item in expected_assets]:
                    fail("APK PIX embedded firmware provenance mismatch")
                for expected_input, actual_input in zip(expected_assets, actual_assets):
                    if actual_input.get("sha256") != expected_input["sha256"]:
                        fail("APK PIX preserved firmware digest mismatch")
                    if not re.fullmatch(r"[0-9a-f]{64}", str(actual_input.get("sha256", ""))):
                        fail("APK PIX provenance input digest is invalid")
                expected_relationships = {
                    "project_sources": {"spdx": "MIT", "license_file": "LICENSE"},
                    "linked_component_notice": "THIRD_PARTY_NOTICES.md",
                    "firmware_terms": "third-party terms are separately recorded; not covered by the project MIT license",
                }
                if component.get("license_relationships") != expected_relationships:
                    fail("APK PIX source/license relationship inventory mismatch")
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
        asicen_firmware = archive.read("assets/asicen-loader.bin") if "assets/asicen-loader.bin" in names else None
        if asicen_firmware is None or sha256(asicen_firmware) != "5db39facda140bdcb3a238dad10da5aac752a1ba3aaf17e2239f8d79a0ad6a2a":
            fail("APK ASICEN loader firmware is missing or mismatched")
        asicen_component = by_name["asicen-loader-firmware"]
        if (asicen_component.get("commit") != EXPECTED["asicen-userland"]["commit"] or
                asicen_component.get("url") != EXPECTED["asicen-userland"]["url"]):
            fail("APK ASICEN loader firmware source pin mismatch")
        asicen_provenance = asicen_component.get("firmware_binary")
        if asicen_provenance != {
            "apk_path": "assets/asicen-loader.bin",
            "sha256": "5db39facda140bdcb3a238dad10da5aac752a1ba3aaf17e2239f8d79a0ad6a2a",
            "size": 16384,
            "redistribution_rights": "unresolved",
        }:
            fail("APK ASICEN loader firmware metadata mismatch")
        if asicen_component.get("source_inputs") != [
            {"path": "mirakc/src/main/assets/asicen-loader.bin", "sha256": "5db39facda140bdcb3a238dad10da5aac752a1ba3aaf17e2239f8d79a0ad6a2a"},
            {"path": "mirakc/src/main/assets/ASICEN-LOADER-NOTICE.txt", "sha256": sha256(payloads["assets/source-metadata/licenses/asicen-loader-firmware/README.txt"])},
        ]:
            fail("APK ASICEN loader source provenance mismatch")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk")
    parser.add_argument("--expected-dtv-commit")
    parser.add_argument("--allow-development-worktree", action="store_true")
    args = parser.parse_args()
    read_apk(args.apk, args.expected_dtv_commit, args.allow_development_worktree)
    print(f"APK source metadata verified: {args.apk}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ApkAuditError, OSError, ValueError, KeyError) as error:
        print(f"audit-apk-metadata: {error}", file=sys.stderr)
        raise SystemExit(1)
