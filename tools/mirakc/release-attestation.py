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
SOURCE_REFS = {
    "dtv_userland_core_ref": "67420d1c909e9205841612a186ae4c7fbf4dd092",
    "asicen_userland_ref": "e2a86ca194b60f1111ecfa0a020cd0944c351c76",
    "pix_userland_ref": "66f5a731784d205d1d306edda9cc57071c087f8e",
    "px4_userland_ref": "5c91e8c57b288c0f490061789829e42dbbf61b3d",
    "siano_userland_ref": "33c3468e7e10781ae6c7e9a5fd57a0c1b0825288",
    "siano_px4_userland_ref": "90b08dfe79b0b40e393f015975c761863830866a",
    "px4_drv_ref": "2b3f79b5bc5db56e8556bb28397f7d8f74b2adeb",
}
PATCH_DIGESTS = {
    "dtv_userland_core_patch_sha256": "9da0cf46a7727a5c7e20de165babd0bc2d78e47c119cad38c1f2c00abeb10521",
    "px4_userland_build_patch_sha256": "ba9c4607e3411e706e537a38cc879990f46db04977ca717e714e0004553a4a42",
    "pix_userland_build_patch_sha256": "a1155cb6400c59222abc5348d3e31a89def378e24c5540e62aed272fb02590d2",
}
VERSION_PATTERN = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")
HEADER = "MIRAKC-ATTESTATION-V1"
FIELDS = (
    "candidate_run_id",
    "candidate_git_head",
    "candidate_apk_sha256",
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
    if set(value) != {"schema", "candidate"}:
        raise AttestationError("BUILD_INFO.json must contain only schema and candidate")
    return value


def parse_candidate_info(path: Path) -> dict[str, str]:
    require_regular(path, "CANDIDATE_BUILD_INFO.txt")
    values: dict[str, str] = {}
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise AttestationError(f"CANDIDATE_BUILD_INFO.txt is invalid: {error}") from error
    for line in lines:
        key, separator, value = line.partition("=")
        if not separator or not key or key in values:
            raise AttestationError("CANDIDATE_BUILD_INFO.txt has a malformed or duplicate field")
        values[key] = value
    return values


def verify_candidate_build(candidate: dict[str, object], expected_version: str) -> None:
    match = VERSION_PATTERN.fullmatch(expected_version)
    if match is None:
        raise AttestationError("expected version is not canonical semantic version")
    if candidate.get("kind") != "candidate":
        raise AttestationError("candidate build-info identity mismatch")
    version = candidate.get("version")
    if version != expected_version:
        raise AttestationError("candidate build-info version does not match expected version")
    if re.fullmatch(r"[1-9][0-9]*", str(candidate.get("candidate_run_id", ""))) is None:
        raise AttestationError("candidate run id is malformed")
    if not isinstance(candidate.get("git_ref"), str) or not candidate["git_ref"]:
        raise AttestationError("candidate git_ref is missing")
    if re.fullmatch(r"[0-9a-f]{40}", str(candidate.get("git_head", ""))) is None:
        raise AttestationError("candidate git_head is malformed")
    if re.fullmatch(r"[0-9a-f]{64}", str(candidate.get("unsigned_apk_sha256", ""))) is None:
        raise AttestationError("candidate unsigned APK digest is malformed")
    version_parts = tuple(int(part) for part in match.groups())
    if version_parts >= (0, 4, 0):
        expected_fields = {
            "kind", "candidate_run_id", "git_ref", "git_head", "version",
            "unsigned_apk_sha256", *SOURCE_REFS, *PATCH_DIGESTS,
        }
        if set(candidate) != expected_fields:
            raise AttestationError("candidate source provenance fields are missing or unexpected")
        for field, expected in SOURCE_REFS.items():
            if candidate.get(field) != expected:
                raise AttestationError(f"candidate source ref mismatch: {field}")
        for field, expected in PATCH_DIGESTS.items():
            if candidate.get(field) != expected:
                raise AttestationError(f"candidate patch digest mismatch: {field}")


def verify_unsigned_candidate(
    candidate: Path,
    build_info: Path,
    expected_version: str,
    expected_head: str,
    expected_run: str,
    expected_ref: str,
) -> dict[str, str]:
    require_regular(candidate, "unsigned candidate APK")
    values = parse_candidate_info(build_info)
    verify_candidate_build(values, expected_version)
    if re.fullmatch(r"[0-9a-f]{40}", expected_head) is None:
        raise AttestationError("expected head is malformed")
    if re.fullmatch(r"[1-9][0-9]*", expected_run) is None:
        raise AttestationError("expected run id is malformed")
    if not expected_ref:
        raise AttestationError("expected ref is missing")
    if values["git_head"] != expected_head or values["candidate_run_id"] != expected_run:
        raise AttestationError("candidate run/head does not match expected build")
    if values["git_ref"] != expected_ref:
        raise AttestationError("candidate git_ref does not match expected build")
    candidate_hash = digest(candidate)
    if values["unsigned_apk_sha256"] != candidate_hash:
        raise AttestationError("unsigned APK hash differs from candidate build-info")
    return {"unsigned_apk_sha256": candidate_hash, "git_head": expected_head, "candidate_run_id": expected_run}


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
    candidate: Path,
    build_info: Path,
    expected_tag_target: str | None = None,
    expected_version: str | None = None,
) -> dict[str, object]:
    require_regular(candidate, "candidate APK")
    build = parse_info(build_info)
    candidate_build = build.get("candidate")
    if not isinstance(candidate_build, dict):
        raise AttestationError("BUILD_INFO.json candidate record is missing")
    candidate_record = candidate_build.get("build")
    if not isinstance(candidate_record, dict):
        raise AttestationError("BUILD_INFO.json nested candidate build record is missing")
    version = candidate_record.get("version")
    if not isinstance(version, str) or VERSION_PATTERN.fullmatch(version) is None:
        raise AttestationError("candidate build-info version is malformed")
    if expected_version is not None and version != expected_version:
        raise AttestationError("candidate build-info version does not match expected version")
    verify_candidate_build(candidate_record, expected_version or version)
    candidate_head = candidate_record.get("git_head")
    run_id = candidate_record.get("candidate_run_id")
    if not isinstance(candidate_head, str) or not isinstance(run_id, str):
        raise AttestationError("candidate build identity is malformed")
    candidate_hash = digest(candidate)
    if candidate_build.get("signed_apk_sha256") != candidate_hash:
        raise AttestationError("signed APK hash differs from BUILD_INFO.json")
    if candidate_build.get("certificate_sha256") != CERT:
        raise AttestationError("BUILD_INFO.json certificate mismatch")
    if expected_tag_target is not None and re.fullmatch(r"[0-9a-f]{40}", expected_tag_target) is None:
        raise AttestationError("annotated tag target is not a commit hash")
    values = {
        "candidate_run_id": run_id,
        "candidate_git_head": candidate_head,
        "candidate_apk_sha256": candidate_hash,
    }
    message = format_message(values)
    acceptance = {"schema": 1, "kind": "mirakc-release-acceptance", **values}
    return {"values": values, "message": message, "acceptance": acceptance}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("generate", "verify", "verify-unsigned", "parse", "acceptance"))
    parser.add_argument("--device-receipt", help="accepted for compatibility and ignored")
    parser.add_argument("--candidate")
    parser.add_argument("--build-info")
    parser.add_argument("--tag-target")
    parser.add_argument("--expected-version", help="expected version of the candidate build")
    parser.add_argument("--expected-head")
    parser.add_argument("--expected-run")
    parser.add_argument("--expected-ref")
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
        if args.command == "verify-unsigned":
            required = {
                "--candidate": args.candidate,
                "--build-info": args.build_info,
                "--expected-version": args.expected_version,
                "--expected-head": args.expected_head,
                "--expected-run": args.expected_run,
                "--expected-ref": args.expected_ref,
            }
            missing = [name for name, value in required.items() if not value]
            if missing:
                raise AttestationError("missing required arguments: " + ", ".join(missing))
            result = verify_unsigned_candidate(
                Path(args.candidate), Path(args.build_info), args.expected_version,
                args.expected_head, args.expected_run, args.expected_ref,
            )
            print(json.dumps(result, sort_keys=True))
            return 0
        required = {
            "--candidate": args.candidate,
            "--build-info": args.build_info,
        }
        missing = [name for name, value in required.items() if not value]
        if missing:
            raise AttestationError("missing required arguments: " + ", ".join(missing))
        if args.command == "generate":
            if args.expected_version is None:
                raise AttestationError("--expected-version is required for generate")
            result = verify_inputs(
                Path(args.candidate), Path(args.build_info), args.tag_target, args.expected_version
            )
            if args.message:
                Path(args.message).write_text(result["message"], encoding="utf-8")
            else:
                print(result["message"], end="")
            if args.acceptance_output:
                Path(args.acceptance_output).write_text(json.dumps(result["acceptance"], sort_keys=True, indent=2) + "\n", encoding="utf-8")
        else:
            if not args.message:
                raise AttestationError("--message is required for verify")
            if args.expected_version is None:
                raise AttestationError("--expected-version is required for verify")
            message = Path(args.message).read_text(encoding="utf-8")
            parsed = parse_message(message)
            result = verify_inputs(
                Path(args.candidate), Path(args.build_info), args.tag_target, args.expected_version
            )
            if parsed != result["values"]:
                raise AttestationError("attestation fields do not match verified evidence")
            print(json.dumps(result["acceptance"], sort_keys=True))
    except (OSError, ValueError, AttestationError) as error:
        print(f"release-attestation: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
