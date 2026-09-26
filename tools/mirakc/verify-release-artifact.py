#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Verify the exact signed-candidate artifact used by mirakc promotion."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import stat
import sys
from pathlib import Path, PurePosixPath

EXPECTED = {
    "mirakc-signed-candidate.apk",
    "CANDIDATE_BUILD_INFO.txt",
    "BUILD_INFO.json",
    "SHA256SUMS",
}
CERT = "1fd02c94f29a5756ed1d560ac4ecb6813fa0b2e634473a6f31da210ffd5223c4"


class ArtifactError(RuntimeError):
    pass


def read_info(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        key, separator, value = line.partition("=")
        if not separator or not key or key in values:
            raise ArtifactError(f"invalid build-info line in {path.name}")
        values[key] = value
    return values


def verify_candidate_info(candidate: dict[str, str]) -> None:
    if candidate.get("kind") != "candidate" or candidate.get("version") != "0.3.2":
        raise ArtifactError("candidate build-info identity mismatch")
    if re.fullmatch(r"[1-9][0-9]*", candidate.get("candidate_run_id", "")) is None:
        raise ArtifactError("candidate run id is malformed")
    if not candidate.get("git_ref"):
        raise ArtifactError("candidate git ref is missing")
    if re.fullmatch(r"[0-9a-f]{40}", candidate.get("git_head", "")) is None:
        raise ArtifactError("candidate git_head is malformed")
    if re.fullmatch(r"[0-9a-f]{64}", candidate.get("unsigned_apk_sha256", "")) is None:
        raise ArtifactError("candidate unsigned APK digest is malformed")


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def files(root: Path) -> dict[str, Path]:
    if root.is_symlink() or not root.is_dir():
        raise ArtifactError("artifact root is not a real directory")
    result: dict[str, Path] = {}
    for path in root.rglob("*"):
        relative = path.relative_to(root)
        safe = PurePosixPath(relative.as_posix())
        if safe.is_absolute() or ".." in safe.parts or "\\" in relative.as_posix():
            raise ArtifactError(f"unsafe artifact path: {relative}")
        mode = path.lstat().st_mode
        if stat.S_ISLNK(mode) or not stat.S_ISREG(mode):
            raise ArtifactError(f"artifact contains non-regular file: {relative}")
        key = safe.as_posix()
        if key in result:
            raise ArtifactError(f"duplicate artifact path: {key}")
        result[key] = path
    if set(result) != EXPECTED:
        raise ArtifactError(f"artifact inventory mismatch: {sorted(result)}")
    return result


def verify_sums(entries: dict[str, Path]) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in entries["SHA256SUMS"].read_text(encoding="utf-8").splitlines():
        parts = line.split()
        if len(parts) != 2 or not re.fullmatch(r"[0-9a-f]{64}", parts[0]):
            raise ArtifactError("malformed SHA256SUMS")
        if parts[1].startswith("/") or ".." in PurePosixPath(parts[1]).parts or parts[1] in values:
            raise ArtifactError("unsafe or duplicate SHA256SUMS path")
        values[parts[1]] = parts[0]
    expected = {
        "mirakc-signed-candidate.apk",
    }
    if set(values) != expected:
        raise ArtifactError("SHA256SUMS inventory mismatch")
    for name, value in values.items():
        if digest(entries[name]) != value:
            raise ArtifactError(f"SHA256SUMS digest mismatch: {name}")
    return values


def verify(root: Path, expected_head: str, expected_run: str) -> dict[str, object]:
    if re.fullmatch(r"[0-9a-f]{40}", expected_head) is None:
        raise ArtifactError("expected head is malformed")
    if re.fullmatch(r"[1-9][0-9]*", expected_run) is None:
        raise ArtifactError("expected run id is malformed")
    entries = files(root)
    hashes = verify_sums(entries)
    candidate = read_info(entries["CANDIDATE_BUILD_INFO.txt"])
    verify_candidate_info(candidate)
    if candidate["candidate_run_id"] != expected_run or candidate["git_head"] != expected_head:
        raise ArtifactError("artifact candidate run/head does not match attestation")
    combined = json.loads(entries["BUILD_INFO.json"].read_text(encoding="utf-8"))
    if combined.get("schema") != 1 or not isinstance(combined.get("candidate"), dict):
        raise ArtifactError("combined BUILD_INFO.json schema mismatch")
    if set(combined) != {"schema", "candidate"}:
        raise ArtifactError("combined BUILD_INFO.json must contain only schema and candidate")
    if combined["candidate"].get("build") != candidate:
        raise ArtifactError("combined BUILD_INFO.json build record differs from flat build-info")
    record = combined["candidate"]
    if record.get("signed_apk_sha256") != hashes["mirakc-signed-candidate.apk"] or record.get("certificate_sha256") != CERT:
        raise ArtifactError("combined BUILD_INFO.json candidate mismatch")
    return {"candidate_apk_sha256": hashes["mirakc-signed-candidate.apk"], "candidate_git_head": expected_head, "candidate_run_id": expected_run}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact")
    parser.add_argument("--expected-head", required=True)
    parser.add_argument("--expected-run", required=True)
    args = parser.parse_args(argv)
    try:
        print(json.dumps(verify(Path(args.artifact).resolve(), args.expected_head, args.expected_run), sort_keys=True))
    except (ArtifactError, OSError, ValueError, json.JSONDecodeError) as error:
        print(f"verify-release-artifact: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
