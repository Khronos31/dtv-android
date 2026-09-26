#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Verify a device-evidence receipt and its exact evidence-file manifest."""

from __future__ import annotations

import argparse
import hashlib
import json
import stat
import sys
from pathlib import Path, PurePosixPath


class VerificationError(RuntimeError):
    pass


EXPECTED_PACKAGE = "dev.khronos31.mirakc"
EXPECTED_VERSION_NAME = "0.3.2"
EXPECTED_VERSION_CODE = "302"
EXPECTED_CERT_SHA256 = "1fd02c94f29a5756ed1d560ac4ecb6813fa0b2e634473a6f31da210ffd5223c4"


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
        raise VerificationError(f"evidence path is missing: {path}") from error
    if not stat.S_ISREG(mode):
        raise VerificationError(f"evidence path is not a regular file: {path}")


def safe_relative(value: str) -> Path:
    if not isinstance(value, str):
        raise VerificationError(f"manifest path is not a string: {value!r}")
    path = PurePosixPath(value)
    if not value or path.is_absolute() or ".." in path.parts or "\\" in value:
        raise VerificationError(f"unsafe manifest path: {value!r}")
    return Path(*path.parts)


def verify(root: Path, apk: Path | None = None) -> dict[str, object]:
    receipt_path = root / "receipt.json"
    manifest_path = root / "receipt-manifest.json"
    manifest_hash_path = root / "receipt-manifest.sha256"
    for required in (receipt_path, manifest_path, manifest_hash_path):
        require_regular(required)
    if not root.is_dir() or root.is_symlink():
        raise VerificationError("evidence root is not a real directory")
    for path in root.rglob("*"):
        if path.is_symlink():
            raise VerificationError(f"evidence tree contains a symlink: {path}")
        if not path.is_dir() and not path.is_file():
            raise VerificationError(f"evidence tree contains a non-regular entry: {path}")
        if path.is_file():
            require_regular(path)
    try:
        receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise VerificationError(f"invalid receipt or manifest: {error}") from error
    if receipt.get("schema") != 1 or receipt.get("kind") != "mirakc-device-evidence":
        raise VerificationError("receipt schema/kind mismatch")
    if manifest.get("schema") != 1 or manifest.get("kind") != "mirakc-device-evidence-manifest":
        raise VerificationError("manifest schema/kind mismatch")
    evidence = manifest.get("evidence")
    if not isinstance(evidence, dict):
        raise VerificationError("manifest evidence map is missing")
    receipt_files = receipt.get("evidence_files")
    if sorted(receipt_files or []) != sorted(evidence):
        raise VerificationError("receipt and manifest evidence sets differ")
    expected_files = {"receipt.json", "receipt-manifest.json", "receipt-manifest.sha256", *evidence}
    actual_files = {
        str(path.relative_to(root).as_posix()) for path in root.rglob("*") if path.is_file()
    }
    if actual_files != expected_files:
        raise VerificationError(f"evidence directory contains unexpected/missing files: {sorted(actual_files ^ expected_files)}")
    for relative, metadata in evidence.items():
        path = root / safe_relative(relative)
        require_regular(path)
        if not isinstance(metadata, dict) or metadata.get("sha256") != digest(path) or metadata.get("size") != path.stat().st_size:
            raise VerificationError(f"evidence digest mismatch: {relative}")
    receipt_metadata = manifest.get("receipt")
    if not isinstance(receipt_metadata, dict) or receipt_metadata.get("sha256") != digest(receipt_path) or receipt_metadata.get("size") != receipt_path.stat().st_size:
        raise VerificationError("receipt digest mismatch")
    try:
        line = manifest_hash_path.read_text(encoding="utf-8").strip().split()
    except OSError as error:
        raise VerificationError(f"manifest digest is missing: {error}") from error
    if len(line) != 2 or line[1] != "receipt-manifest.json" or line[0] != digest(manifest_path):
        raise VerificationError("manifest digest mismatch")
    candidate_hash = manifest.get("candidate_apk_sha256")
    if not isinstance(candidate_hash, str) or len(candidate_hash) != 64:
        raise VerificationError("candidate APK digest is missing")
    candidate = receipt.get("candidate")
    if not isinstance(candidate, dict) or manifest.get("candidate") != candidate:
        raise VerificationError("receipt/manifest candidate metadata differs")
    if candidate.get("package") != EXPECTED_PACKAGE or candidate.get("version_name") != EXPECTED_VERSION_NAME or candidate.get("version_code") != EXPECTED_VERSION_CODE or candidate.get("certificate_sha256") != EXPECTED_CERT_SHA256:
        raise VerificationError("candidate package/version/certificate does not match the release gate")
    if candidate.get("apk_sha256") != candidate_hash:
        raise VerificationError("candidate APK digest differs between receipt and manifest")
    if receipt.get("status") != "pass":
        raise VerificationError("receipt status is not pass")
    checks = receipt.get("checks")
    required_checks = {
        "candidate_binding", "service_api", "ten_cycles", "descriptor_ownership",
        "zero_orphan", "siano_12seg", "q3u4_eight_tuner", "bs_cs_integrity",
        "concurrency_single_px4d", "epgstation_scan_schedule_live", "size_metrics",
        "rss_metrics", "usb_detach_reconnect",
    }
    if not isinstance(checks, dict) or set(checks) != required_checks or any(not isinstance(item, dict) or item.get("status") != "pass" for item in checks.values()):
        raise VerificationError("receipt does not contain a passing result for every mandatory check")
    if apk is not None and candidate_hash != digest(apk):
        raise VerificationError("candidate APK digest mismatch")
    return {"status": receipt.get("status"), "candidate_apk_sha256": candidate_hash, "evidence_files": len(evidence)}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("receipt_directory")
    parser.add_argument("--apk")
    args = parser.parse_args(argv)
    try:
        result = verify(Path(args.receipt_directory).resolve(), Path(args.apk).resolve() if args.apk else None)
    except (OSError, VerificationError, ValueError, KeyError, TypeError) as error:
        print(f"verify-device-evidence: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
