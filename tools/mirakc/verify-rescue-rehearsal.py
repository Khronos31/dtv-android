#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Verify a staging rescue-rehearsal receipt and its tamper-evident files."""

from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import re
import stat
import sys
from pathlib import Path, PurePosixPath

EXPECTED_PACKAGE = "dev.khronos31.mirakc"
EXPECTED_CERT = "1fd02c94f29a5756ed1d560ac4ecb6813fa0b2e634473a6f31da210ffd5223c4"
EXPECTED_RESCUE_PROVENANCE = {
    "kind": "legacy-server-rescue",
    "package": EXPECTED_PACKAGE,
    "version_name": "0.3.1",
    "version_code": "301",
    "legacy_dtv_ref": "mirakc-v0.2.0",
    "legacy_dtv_commit": "5a4d647c9e4b46f3f637165fa107f87d34ea22ed",
    "legacy_siano_ref": "v0.1.1",
    "legacy_siano_commit": "1a22a7180abd6c7be1d1dda6b866ec321a4e28ab",
    "not-a-normal-release": "true",
}
REQUIRED_CHECKS = {
    "legacy_precondition", "candidate_install_r", "rescue_install_r",
    "data_continuity", "legacy_services_restored", "s1ud_stream_integrity",
}


class VerificationError(RuntimeError):
    pass


def require_sha(value: object, field: str) -> None:
    if not isinstance(value, str) or re.fullmatch(r"[0-9a-f]{64}", value) is None:
        raise VerificationError(f"{field} is not a lowercase SHA-256")


def require_package_state(value: object, expected_code: str, expected_name: str, field: str) -> dict[str, str]:
    if not isinstance(value, dict):
        raise VerificationError(f"{field} package state is missing")
    expected = {"package": EXPECTED_PACKAGE, "version_code": expected_code, "version_name": expected_name}
    if any(value.get(key) != expected_value for key, expected_value in expected.items()):
        raise VerificationError(f"{field} package state is not {expected}")
    if not isinstance(value.get("first_install_time"), str) or not value["first_install_time"]:
        raise VerificationError(f"{field} firstInstallTime is missing")
    return value


def require_snapshot(value: object, field: str) -> dict[str, object]:
    if not isinstance(value, dict):
        raise VerificationError(f"{field} snapshot is missing")
    services = value.get("services")
    canonical = value.get("services_canonical")
    if not isinstance(services, list) or not services or not isinstance(canonical, str):
        raise VerificationError(f"{field} services snapshot is incomplete")
    expected_canonical = json.dumps(services, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    if canonical != expected_canonical:
        raise VerificationError(f"{field} services canonical form is inconsistent")
    stream = value.get("stream")
    if not isinstance(stream, dict):
        raise VerificationError(f"{field} stream summary is missing")
    size = stream.get("bytes")
    packets = stream.get("packets")
    if not isinstance(size, int) or size < 1880 or size % 188 != 0 or packets != size // 188 or stream.get("scrambled_packets") != 0:
        raise VerificationError(f"{field} stream summary is not clear aligned TS")
    return value


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def require_regular(path: Path) -> None:
    try:
        mode = path.lstat().st_mode
    except OSError as error:
        raise VerificationError(f"missing path: {path}") from error
    if not stat.S_ISREG(mode):
        raise VerificationError(f"path is not a regular file: {path}")


def safe_relative(value: str) -> Path:
    if not isinstance(value, str):
        raise VerificationError("manifest path is not a string")
    path = PurePosixPath(value)
    if not value or path.is_absolute() or ".." in path.parts or "\\" in value:
        raise VerificationError(f"unsafe manifest path: {value!r}")
    return Path(*path.parts)


def verify(root: Path, candidate: Path | None = None, rescue: Path | None = None) -> dict[str, object]:
    if root.is_symlink() or not root.is_dir():
        raise VerificationError("receipt root is not a real directory")
    receipt_path = root / "receipt.json"
    manifest_path = root / "receipt-manifest.json"
    manifest_hash_path = root / "receipt-manifest.sha256"
    for path in (receipt_path, manifest_path, manifest_hash_path):
        require_regular(path)
    for path in root.rglob("*"):
        if path.is_symlink() or (not path.is_dir() and not path.is_file()):
            raise VerificationError(f"receipt tree contains an unsafe entry: {path}")
        if path.is_file():
            require_regular(path)
    try:
        receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise VerificationError(f"receipt JSON is invalid: {error}") from error
    if receipt.get("schema") != 1 or receipt.get("kind") != "mirakc-rescue-rehearsal" or receipt.get("status") != "pass":
        raise VerificationError("receipt is not a passing rescue rehearsal")
    if manifest.get("schema") != 1 or manifest.get("kind") != "mirakc-rescue-rehearsal-manifest":
        raise VerificationError("manifest schema/kind mismatch")
    checks = receipt.get("checks")
    if not isinstance(checks, dict) or set(checks) != REQUIRED_CHECKS or any(value != "pass" for value in checks.values()):
        raise VerificationError("mandatory rescue checks are incomplete")
    try:
        started_at = datetime.datetime.fromisoformat(receipt["started_at"])
        finished_at = datetime.datetime.fromisoformat(receipt["finished_at"])
    except (KeyError, TypeError, ValueError) as error:
        raise VerificationError("receipt start/end timestamps are invalid") from error
    if started_at.tzinfo is None or finished_at.tzinfo is None or finished_at < started_at:
        raise VerificationError("receipt timestamps are not an ordered timezone-aware interval")
    device = receipt.get("device")
    if not isinstance(device, dict) or not isinstance(device.get("serial"), str) or not device["serial"]:
        raise VerificationError("device serial is missing")
    build = device.get("build")
    required_build = ("ro.build.type", "ro.build.id", "ro.build.version.release", "ro.product.device")
    if not isinstance(build, dict) or any(not isinstance(build.get(key), str) or not build[key] for key in required_build):
        raise VerificationError("device build identity is incomplete")
    if build["ro.build.type"] != "user":
        raise VerificationError("receipt device is not a user build")
    candidate_info = receipt.get("candidate")
    rescue_info = receipt.get("rescue")
    if not isinstance(candidate_info, dict) or not isinstance(rescue_info, dict):
        raise VerificationError("candidate/rescue metadata is missing")
    if manifest.get("candidate") != candidate_info or manifest.get("rescue") != rescue_info:
        raise VerificationError("candidate/rescue metadata differs in manifest")
    if candidate_info.get("package") != EXPECTED_PACKAGE or candidate_info.get("version_name") != "0.3.0" or candidate_info.get("version_code") != "300":
        raise VerificationError("candidate metadata is not 0.3.0/code 300")
    if rescue_info.get("package") != EXPECTED_PACKAGE or rescue_info.get("version_name") != "0.3.1" or rescue_info.get("version_code") != "301":
        raise VerificationError("rescue metadata is not 0.3.1/code 301")
    for metadata in (candidate_info, rescue_info):
        if metadata.get("certificate_sha256") != EXPECTED_CERT:
            raise VerificationError("candidate/rescue certificate or digest is invalid")
        require_sha(metadata.get("apk_sha256"), "APK digest")
        if not isinstance(metadata.get("apk_basename"), str) or not metadata["apk_basename"] or "/" in metadata["apk_basename"]:
            raise VerificationError("APK basename is invalid")
    if rescue_info.get("provenance") != EXPECTED_RESCUE_PROVENANCE:
        raise VerificationError("rescue provenance is missing or mismatched")
    installed = receipt.get("installed_legacy")
    if not isinstance(installed, dict):
        raise VerificationError("installed legacy APK evidence is missing")
    if installed.get("certificate_sha256") != EXPECTED_CERT:
        raise VerificationError("installed legacy certificate is not the production certificate")
    require_sha(installed.get("installed_apk_sha256"), "installed legacy APK digest")
    initial = require_package_state(receipt.get("initial_package"), "200", "0.2.0", "initial package")
    candidate_after = require_package_state(receipt.get("candidate_after"), "300", "0.3.0", "candidate after install")
    final_package = require_package_state(receipt.get("final_package"), "301", "0.3.1", "final rescue")
    first_install = receipt.get("device", {}).get("first_install_time")
    if not isinstance(first_install, str) or any(state["first_install_time"] != first_install for state in (initial, candidate_after, final_package)):
        raise VerificationError("firstInstallTime/data continuity is not proven")
    before = require_snapshot(receipt.get("before"), "before")
    require_snapshot(receipt.get("candidate_snapshot"), "candidate")
    after = require_snapshot(receipt.get("after_rescue"), "after rescue")
    if before["services_canonical"] != after["services_canonical"]:
        raise VerificationError("before/after services snapshots differ")
    readiness = receipt.get("ready_records")
    if not isinstance(readiness, list) or {entry.get("phase") for entry in readiness if isinstance(entry, dict)} != {"legacy", "candidate", "rescue"}:
        raise VerificationError("readiness records are missing")
    expected_versions = {"legacy": "3.4.82", "candidate": "3.4.86", "rescue": "3.4.82"}
    for phase in ("legacy", "candidate", "rescue"):
        entries = [entry for entry in readiness if isinstance(entry, dict) and entry.get("phase") == phase]
        if len(entries) != 1 or entries[0].get("version") != expected_versions[phase] or not isinstance(entries[0].get("service_count"), int) or entries[0]["service_count"] <= 0:
            raise VerificationError(f"{phase} readiness is incomplete")
    candidate_ready = receipt.get("candidate_ready")
    if candidate_ready != next(entry for entry in readiness if entry["phase"] == "candidate") or candidate_ready.get("version") != "3.4.86":
        raise VerificationError("candidate exact /api/version readiness is missing")
    install_records = receipt.get("install_records")
    if not isinstance(install_records, list) or [record.get("phase") for record in install_records[:2]] != ["candidate", "rescue"]:
        raise VerificationError("candidate/rescue install records are missing")
    for record, expected_digest, expected_name in (
        (install_records[0], candidate_info["apk_sha256"], candidate_info["apk_basename"]),
        (install_records[1], rescue_info["apk_sha256"], rescue_info["apk_basename"]),
    ):
        if not isinstance(record, dict) or record.get("returncode") != 0 or record.get("timed_out") is not False:
            raise VerificationError("package-manager install did not complete successfully")
        if record.get("apk_sha256") != expected_digest or record.get("basename") != expected_name:
            raise VerificationError("install record APK binding is inconsistent")
        command = record.get("command")
        if not isinstance(command, str) or re.fullmatch(r"adb -s [^ ]+ install -r [0-9a-f]{64}/[^/]+", command) is None:
            raise VerificationError("install command is not the reproducible redacted form")
    evidence = manifest.get("evidence")
    if not isinstance(evidence, dict) or receipt.get("evidence_files") != sorted(evidence):
        raise VerificationError("receipt/manifest evidence inventory differs")
    actual = {path.relative_to(root).as_posix() for path in root.rglob("*") if path.is_file()}
    expected = {"receipt.json", "receipt-manifest.json", "receipt-manifest.sha256", *evidence}
    if actual != expected:
        raise VerificationError(f"receipt file inventory differs: {sorted(actual ^ expected)}")
    for phase in ("candidate", "rescue"):
        required_evidence = {
            f"evidence/install/{phase}.json",
            f"evidence/install/{phase}.stdout.txt",
            f"evidence/install/{phase}.stderr.txt",
        }
        if not required_evidence.issubset(evidence):
            raise VerificationError(f"{phase} package-manager evidence is incomplete")
    for relative, metadata in evidence.items():
        path = root / safe_relative(relative)
        require_regular(path)
        if not isinstance(metadata, dict) or metadata.get("sha256") != digest(path) or metadata.get("size") != path.stat().st_size:
            raise VerificationError(f"evidence digest mismatch: {relative}")
    receipt_metadata = manifest.get("receipt")
    if not isinstance(receipt_metadata, dict) or receipt_metadata.get("sha256") != digest(receipt_path) or receipt_metadata.get("size") != receipt_path.stat().st_size:
        raise VerificationError("receipt digest mismatch")
    if manifest_hash_path.read_text(encoding="utf-8").strip() != f"{digest(manifest_path)}  receipt-manifest.json":
        raise VerificationError("manifest digest mismatch")
    if candidate is not None and candidate_info["apk_sha256"] != digest(candidate):
        raise VerificationError("candidate APK digest mismatch")
    if rescue is not None and rescue_info["apk_sha256"] != digest(rescue):
        raise VerificationError("rescue APK digest mismatch")
    return {"status": "pass", "candidate_apk_sha256": candidate_info["apk_sha256"], "rescue_apk_sha256": rescue_info["apk_sha256"], "evidence_files": len(evidence)}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("receipt_directory")
    parser.add_argument("--candidate")
    parser.add_argument("--rescue")
    args = parser.parse_args(argv)
    try:
        print(json.dumps(verify(Path(args.receipt_directory).resolve(), Path(args.candidate).resolve() if args.candidate else None, Path(args.rescue).resolve() if args.rescue else None), sort_keys=True))
    except (OSError, VerificationError, ValueError, KeyError, TypeError) as error:
        print(f"verify-rescue-rehearsal: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
