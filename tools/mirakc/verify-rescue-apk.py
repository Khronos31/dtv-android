#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Fail-closed audit for the forward-versioned legacy-server rescue APK."""

from __future__ import annotations

import argparse
import json
import re
import stat
import subprocess
import sys
import zipfile
from pathlib import Path, PurePosixPath

EXPECTED_PACKAGE = "dev.khronos31.mirakc"
EXPECTED_VERSION = "0.3.1"
EXPECTED_CODE = "301"
EXPECTED_CERT_SHA256 = "1fd02c94f29a5756ed1d560ac4ecb6813fa0b2e634473a6f31da210ffd5223c4"
EXPECTED_ASSET = "assets/BUILD_INFO-legacy-server-rescue.txt"
EXPECTED_REFS = {
    "legacy_dtv_ref": "mirakc-v0.2.0",
    "legacy_dtv_commit": "5a4d647c9e4b46f3f637165fa107f87d34ea22ed",
    "legacy_siano_ref": "v0.1.1",
    "legacy_siano_commit": "1a22a7180abd6c7be1d1dda6b866ec321a4e28ab",
}


class RescueVerificationError(RuntimeError):
    pass


def parse_badging(text: str) -> dict[str, str]:
    match = re.search(
        r"package:\s+name='([^']+)'\s+versionCode='([^']+)'\s+versionName='([^']+)'",
        text,
    )
    if match is None:
        raise RescueVerificationError("aapt2 output has no package/version record")
    return {"package": match.group(1), "version_code": match.group(2), "version_name": match.group(3)}


def parse_asset(data: bytes) -> dict[str, str]:
    try:
        lines = data.decode("utf-8").splitlines()
    except UnicodeDecodeError as error:
        raise RescueVerificationError("rescue asset is not UTF-8") from error
    result: dict[str, str] = {}
    for line in lines:
        if "=" not in line:
            raise RescueVerificationError(f"malformed rescue asset line: {line!r}")
        key, value = line.split("=", 1)
        if not key or key in result:
            raise RescueVerificationError("rescue asset has duplicate/empty key")
        result[key] = value
    expected = {
        "kind": "legacy-server-rescue",
        "package": EXPECTED_PACKAGE,
        "version_name": EXPECTED_VERSION,
        "version_code": EXPECTED_CODE,
        "not-a-normal-release": "true",
        **EXPECTED_REFS,
    }
    if result != expected:
        raise RescueVerificationError(f"rescue asset metadata mismatch: {result!r}")
    return result


def safe_zip_entries(apk: Path) -> dict[str, zipfile.ZipInfo]:
    try:
        archive = zipfile.ZipFile(apk)
    except (OSError, zipfile.BadZipFile) as error:
        raise RescueVerificationError(f"APK is not a readable ZIP: {error}") from error
    with archive:
        entries: dict[str, zipfile.ZipInfo] = {}
        for info in archive.infolist():
            path = PurePosixPath(info.filename)
            if not info.filename or path.is_absolute() or ".." in path.parts or "\\" in info.filename:
                raise RescueVerificationError(f"APK has an unsafe ZIP path: {info.filename!r}")
            if info.filename in entries:
                raise RescueVerificationError(f"APK has duplicate ZIP path: {info.filename}")
            if stat.S_ISLNK((info.external_attr >> 16) & 0o170000):
                raise RescueVerificationError(f"APK has a symlink entry: {info.filename}")
            entries[info.filename] = info
        if EXPECTED_ASSET not in entries:
            raise RescueVerificationError(f"APK is missing {EXPECTED_ASSET}")
        parse_asset(archive.read(entries[EXPECTED_ASSET]))
    return entries


def verify(apk: Path, aapt2: str, apksigner: str | None = None) -> dict[str, object]:
    if not apk.is_file() or apk.is_symlink():
        raise RescueVerificationError(f"rescue APK is not a regular file: {apk}")
    badging = subprocess.run([aapt2, "dump", "badging", str(apk)], capture_output=True, text=True, check=True).stdout
    metadata = parse_badging(badging)
    expected = {"package": EXPECTED_PACKAGE, "version_code": EXPECTED_CODE, "version_name": EXPECTED_VERSION}
    if metadata != expected:
        raise RescueVerificationError(f"rescue APK metadata mismatch: {metadata!r}")
    entries = safe_zip_entries(apk)
    certificate = None
    if apksigner is not None:
        cert_output = subprocess.run([apksigner, "verify", "--print-certs", str(apk)], capture_output=True, text=True, check=True).stdout
        match = re.search(r"certificate SHA-256 digest:\s*([0-9A-Fa-f:]+)", cert_output)
        if match is None:
            raise RescueVerificationError("apksigner output has no certificate SHA-256 digest")
        certificate = match.group(1).replace(":", "").lower()
        if certificate != EXPECTED_CERT_SHA256:
            raise RescueVerificationError(f"rescue certificate mismatch: {certificate}")
    return {**metadata, "certificate_sha256": certificate, "asset": EXPECTED_ASSET, "zip_entries": len(entries)}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk")
    parser.add_argument("--aapt2", required=True)
    parser.add_argument("--apksigner")
    args = parser.parse_args(argv)
    try:
        print(json.dumps(verify(Path(args.apk).resolve(), args.aapt2, args.apksigner), sort_keys=True))
    except (OSError, RescueVerificationError, subprocess.CalledProcessError, ValueError) as error:
        print(f"verify-rescue-apk: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
