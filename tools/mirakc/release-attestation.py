#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Create and verify the v1 annotated-tag attestation for a mirakc promotion."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
PACKAGE = "dev.khronos31.mirakc"
CERT = "1fd02c94f29a5756ed1d560ac4ecb6813fa0b2e634473a6f31da210ffd5223c4"
HEADER = "MIRAKC-ATTESTATION-V1"
FIELDS = (
    "candidate_run_id",
    "candidate_git_head",
    "candidate_apk_sha256",
    "rescue_apk_sha256",
    "device_receipt_manifest_sha256",
    "rescue_receipt_manifest_sha256",
)


class AttestationError(RuntimeError):
    pass


def load(name: str, filename: str):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    if spec is None or spec.loader is None:
        raise ImportError(f"cannot load {filename}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


device_verifier = load("mirakc_device_verifier", "verify-device-evidence.py")
rescue_verifier = load("mirakc_rescue_verifier", "verify-rescue-rehearsal.py")


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def require_regular(path: Path, label: str) -> None:
    if path.is_symlink() or not path.is_file():
        raise AttestationError(f"{label} is not a regular file")


def parse_info(path: Path) -> dict[str, object]:
    require_regular(path, "BUILD_INFO.json")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise AttestationError(f"BUILD_INFO.json is invalid: {error}") from error
    if not isinstance(value, dict) or value.get("schema") != 1:
        raise AttestationError("BUILD_INFO.json schema mismatch")
    return value


def parse_message(text: str) -> dict[str, str]:
    lines = text.splitlines()
    if lines and lines[-1] == "":
        lines.pop()
    if not lines or lines.pop(0) != HEADER:
        raise AttestationError("attestation header is missing")
    values: dict[str, str] = {}
    for line in lines:
        key, separator, value = line.partition("=")
        if not separator or key not in FIELDS or key in values or not value:
            raise AttestationError(f"unknown, duplicate, or malformed attestation field: {line!r}")
        values[key] = value
    if set(values) != set(FIELDS):
        raise AttestationError("attestation fields are incomplete")
    if re.fullmatch(r"[1-9][0-9]*", values["candidate_run_id"]) is None:
        raise AttestationError("candidate_run_id is not a positive integer")
    for field in FIELDS[1:]:
        if re.fullmatch(r"[0-9a-f]{40}", values[field]) is None and field == "candidate_git_head":
            raise AttestationError(f"{field} is not a lowercase commit hash")
        if field != "candidate_git_head" and re.fullmatch(r"[0-9a-f]{64}", values[field]) is None:
            raise AttestationError(f"{field} is not a lowercase SHA-256")
    return values


def format_message(values: dict[str, str]) -> str:
    parse_message(HEADER + "\n" + "\n".join(f"{field}={values[field]}" for field in FIELDS) + "\n")
    return HEADER + "\n" + "\n".join(f"{field}={values[field]}" for field in FIELDS) + "\n"


def verify_inputs(
    device_receipt: Path,
    rescue_receipt: Path,
    candidate: Path,
    rescue: Path,
    build_info: Path,
    expected_tag_target: str | None = None,
) -> dict[str, object]:
    for path, label in ((candidate, "candidate APK"), (rescue, "rescue APK")):
        require_regular(path, label)
    build = parse_info(build_info)
    candidate_build = build.get("candidate")
    rescue_build = build.get("rescue")
    if not isinstance(candidate_build, dict) or not isinstance(rescue_build, dict):
        raise AttestationError("BUILD_INFO.json candidate/rescue records are missing")
    candidate_record = candidate_build.get("build")
    rescue_record = rescue_build.get("build")
    if not isinstance(candidate_record, dict) or not isinstance(rescue_record, dict):
        raise AttestationError("BUILD_INFO.json nested build records are missing")
    if candidate_record.get("kind") != "candidate" or candidate_record.get("version") != "0.3.0":
        raise AttestationError("candidate build-info identity mismatch")
    candidate_head = candidate_record.get("git_head")
    if not isinstance(candidate_head, str) or re.fullmatch(r"[0-9a-f]{40}", candidate_head) is None:
        raise AttestationError("candidate git_head is malformed")
    run_id = candidate_record.get("candidate_run_id")
    if not isinstance(run_id, str) or re.fullmatch(r"[1-9][0-9]*", run_id) is None:
        raise AttestationError("candidate run id is malformed")
    expected_rescue = {
        "kind": "legacy-server-rescue", "version_name": "0.3.1", "version_code": "301",
        "legacy_dtv_ref": "mirakc-v0.2.0", "legacy_dtv_commit": "5a4d647c9e4b46f3f637165fa107f87d34ea22ed",
        "legacy_siano_ref": "v0.1.1", "legacy_siano_commit": "1a22a7180abd6c7be1d1dda6b866ec321a4e28ab",
    }
    if any(rescue_record.get(key) != value for key, value in expected_rescue.items()):
        raise AttestationError("rescue build-info provenance mismatch")
    candidate_hash = digest(candidate)
    rescue_hash = digest(rescue)
    if candidate_build.get("signed_apk_sha256") != candidate_hash or rescue_build.get("signed_apk_sha256") != rescue_hash:
        raise AttestationError("signed APK hash differs from BUILD_INFO.json")
    if candidate_build.get("certificate_sha256") != CERT or rescue_build.get("certificate_sha256") != CERT:
        raise AttestationError("BUILD_INFO.json certificate mismatch")
    try:
        device_result = device_verifier.verify(device_receipt, candidate)
        rescue_result = rescue_verifier.verify(rescue_receipt, candidate, rescue)
    except (OSError, ValueError, KeyError, TypeError, device_verifier.VerificationError, rescue_verifier.VerificationError) as error:
        raise AttestationError(f"receipt verification failed: {error}") from error
    if device_result["candidate_apk_sha256"] != candidate_hash or rescue_result["candidate_apk_sha256"] != candidate_hash:
        raise AttestationError("device/rescue candidate APK hashes do not agree")
    if rescue_result["rescue_apk_sha256"] != rescue_hash:
        raise AttestationError("rescue receipt APK hash does not agree")
    device_manifest = digest(device_receipt / "receipt-manifest.json")
    rescue_manifest = digest(rescue_receipt / "receipt-manifest.json")
    if expected_tag_target is not None and expected_tag_target != candidate_head:
        raise AttestationError("annotated tag target does not equal candidate git head")
    values = {
        "candidate_run_id": run_id,
        "candidate_git_head": candidate_head,
        "candidate_apk_sha256": candidate_hash,
        "rescue_apk_sha256": rescue_hash,
        "device_receipt_manifest_sha256": device_manifest,
        "rescue_receipt_manifest_sha256": rescue_manifest,
    }
    message = format_message(values)
    acceptance = {"schema": 1, "kind": "mirakc-release-acceptance", **values}
    return {"values": values, "message": message, "acceptance": acceptance}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("generate", "verify", "parse", "acceptance"))
    parser.add_argument("--device-receipt")
    parser.add_argument("--rescue-receipt")
    parser.add_argument("--candidate")
    parser.add_argument("--rescue")
    parser.add_argument("--build-info")
    parser.add_argument("--tag-target")
    parser.add_argument("--message", help="attestation text for verify, or output file for generate")
    parser.add_argument("--acceptance-output")
    args = parser.parse_args(argv)
    try:
        if args.command in ("parse", "acceptance"):
            if not args.message:
                raise AttestationError("--message is required for parse")
            values = parse_message(Path(args.message).read_text(encoding="utf-8"))
            if args.command == "acceptance":
                values = {"schema": 1, "kind": "mirakc-release-acceptance", **values}
            print(json.dumps(values, sort_keys=True))
            return 0
        required = {
            "--device-receipt": args.device_receipt,
            "--rescue-receipt": args.rescue_receipt,
            "--candidate": args.candidate,
            "--rescue": args.rescue,
            "--build-info": args.build_info,
        }
        missing = [name for name, value in required.items() if not value]
        if missing:
            raise AttestationError("missing required arguments: " + ", ".join(missing))
        if args.command == "generate":
            result = verify_inputs(Path(args.device_receipt), Path(args.rescue_receipt), Path(args.candidate), Path(args.rescue), Path(args.build_info), args.tag_target)
            if args.message:
                Path(args.message).write_text(result["message"], encoding="utf-8")
            else:
                print(result["message"], end="")
            if args.acceptance_output:
                Path(args.acceptance_output).write_text(json.dumps(result["acceptance"], sort_keys=True, indent=2) + "\n", encoding="utf-8")
        else:
            if not args.message:
                raise AttestationError("--message is required for verify")
            message = Path(args.message).read_text(encoding="utf-8")
            parsed = parse_message(message)
            result = verify_inputs(Path(args.device_receipt), Path(args.rescue_receipt), Path(args.candidate), Path(args.rescue), Path(args.build_info), args.tag_target)
            if parsed != result["values"]:
                raise AttestationError("attestation fields do not match verified evidence")
            print(json.dumps(result["acceptance"], sort_keys=True))
    except (OSError, ValueError, AttestationError) as error:
        print(f"release-attestation: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
