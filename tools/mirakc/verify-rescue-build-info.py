#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Validate the two build-info records used by the signed rescue artifact."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

EXPECTED_RESCUE = {
    "kind": "legacy-server-rescue",
    "version_name": "0.3.1",
    "version_code": "301",
    "legacy_dtv_ref": "mirakc-v0.2.0",
    "legacy_dtv_commit": "5a4d647c9e4b46f3f637165fa107f87d34ea22ed",
    "legacy_siano_ref": "v0.1.1",
    "legacy_siano_commit": "1a22a7180abd6c7be1d1dda6b866ec321a4e28ab",
}


class BuildInfoError(RuntimeError):
    pass


def read_info(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        key, separator, value = line.partition("=")
        if not separator or not key or key in values:
            raise BuildInfoError(f"invalid build-info line in {path.name}")
        values[key] = value
    return values


def require_sha(value: object, field: str) -> None:
    if not isinstance(value, str) or re.fullmatch(r"[0-9a-f]{64}", value) is None:
        raise BuildInfoError(f"{field} is not a lowercase SHA-256")


def verify(candidate_path: Path, rescue_path: Path) -> dict[str, object]:
    candidate = read_info(candidate_path)
    rescue = read_info(rescue_path)
    if candidate.get("kind") != "candidate" or candidate.get("version") != "0.3.0":
        raise BuildInfoError("candidate build-info identity mismatch")
    if not candidate.get("git_ref") or re.fullmatch(r"[0-9a-f]{40}", candidate.get("git_head", "")) is None:
        raise BuildInfoError("candidate ref/head is missing or malformed")
    require_sha(candidate.get("unsigned_apk_sha256"), "candidate unsigned APK")
    for key, expected in EXPECTED_RESCUE.items():
        if rescue.get(key) != expected:
            raise BuildInfoError(f"rescue build-info {key} mismatch")
    require_sha(rescue.get("unsigned_apk_sha256"), "rescue unsigned APK")
    return {"candidate": candidate, "rescue": rescue}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("candidate")
    parser.add_argument("rescue")
    args = parser.parse_args(argv)
    try:
        print(json.dumps(verify(Path(args.candidate), Path(args.rescue)), sort_keys=True))
    except (BuildInfoError, OSError, ValueError) as error:
        print(f"verify-rescue-build-info: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
