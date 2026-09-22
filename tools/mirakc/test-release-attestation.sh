#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
set -eu

root=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd -P)
python3 - "$root" <<'PY'
import importlib.util
import json
import tempfile
from hashlib import sha256
from pathlib import Path

root = Path(__import__("sys").argv[1])
release_workflow = (root / ".github/workflows/release.yml").read_text(encoding="utf-8")
assert "mirakc-device-evidence" not in release_workflow
assert "device_receipt_manifest_sha256" not in release_workflow
path = root / "tools/mirakc/release-attestation.py"
spec = importlib.util.spec_from_file_location("release_attestation", path)
assert spec and spec.loader
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
artifact_spec = importlib.util.spec_from_file_location(
    "release_artifact", root / "tools/mirakc/verify-release-artifact.py"
)
assert artifact_spec and artifact_spec.loader
artifact_module = importlib.util.module_from_spec(artifact_spec)
artifact_spec.loader.exec_module(artifact_module)

valid = module.format_message({
    "candidate_run_id": "123",
    "candidate_git_head": "a" * 40,
    "candidate_apk_sha256": "b" * 64,
})
assert module.parse_message(valid)["candidate_run_id"] == "123"
assert module.parse_message(valid)["candidate_git_head"] == "a" * 40
acceptance_values = {"schema": 1, "kind": "mirakc-release-acceptance", **module.parse_message(valid)}
assert acceptance_values["candidate_apk_sha256"] == "b" * 64
assert "device_receipt_manifest_sha256" not in acceptance_values
for malformed in (
    valid.replace("candidate_run_id=123", "unknown=123"),
    valid + "candidate_run_id=123\n",
    valid.replace("candidate_git_head=" + "a" * 40, "candidate_git_head=" + "A" * 40),
    valid + "device_receipt_manifest_sha256=" + "d" * 64 + "\n",
    valid.replace("candidate_apk_sha256=" + "b" * 64, "candidate_apk_sha256=" + "b" * 63),
):
    try:
        module.parse_message(malformed)
    except module.AttestationError:
        pass
    else:
        raise AssertionError("malformed attestation was accepted")

with tempfile.TemporaryDirectory(prefix="mirakc-release-attestation-") as temporary:
    directory = Path(temporary)
    candidate = directory / "candidate.apk"
    candidate.write_bytes(b"candidate")
    candidate_hash = sha256(candidate.read_bytes()).hexdigest()
    build_info = directory / "BUILD_INFO.json"
    build_info.write_text(json.dumps({
        "schema": 1,
        "candidate": {"build": {"kind": "candidate", "candidate_run_id": "123", "git_ref": "main", "git_head": "a" * 40, "version": "0.3.0", "unsigned_apk_sha256": "f" * 64}, "signed_apk_sha256": candidate_hash, "certificate_sha256": module.CERT},
    }))
    (directory / "candidate-info.txt").write_text("kind=candidate\ncandidate_run_id=123\ngit_ref=main\ngit_head=" + "a" * 40 + "\nversion=0.3.0\nunsigned_apk_sha256=" + "f" * 64 + "\n")
    result = module.verify_inputs(candidate, build_info, "a" * 40)
    assert module.parse_message(result["message"]) == result["values"]
    result = module.verify_inputs(candidate, build_info, "b" * 40)
    assert result["values"]["candidate_git_head"] == "a" * 40
    stale_build_info = directory / "stale-BUILD_INFO.json"
    stale_build_info.write_text(json.dumps({
        "schema": 1,
        "candidate": {"build": {"kind": "candidate", "candidate_run_id": "123", "git_ref": "main", "git_head": "a" * 40, "version": "0.3.0", "unsigned_apk_sha256": "f" * 64}, "signed_apk_sha256": candidate_hash, "certificate_sha256": module.CERT},
        "unknown": {"build": {"kind": "unknown", "version": "0.3.0"}, "signed_apk_sha256": "c" * 64, "certificate_sha256": module.CERT},
    }))
    try:
        module.verify_inputs(candidate, stale_build_info, "a" * 40)
    except module.AttestationError:
        pass
    else:
        raise AssertionError("BUILD_INFO.json with an unknown record was accepted")
    artifact = directory / "artifact"
    artifact.mkdir()
    for source, name in ((candidate, "mirakc-signed-candidate.apk"), (directory / "candidate-info.txt", "CANDIDATE_BUILD_INFO.txt"), (build_info, "BUILD_INFO.json")):
        (artifact / name).write_bytes(source.read_bytes())
    (artifact / "SHA256SUMS").write_text(
        f"{candidate_hash}  mirakc-signed-candidate.apk\n"
    )
    checked = artifact_module.verify(artifact, "a" * 40, "123")
    assert checked["candidate_apk_sha256"] == candidate_hash
    combined_data = json.loads((artifact / "BUILD_INFO.json").read_text())
    combined_data["candidate"]["build"]["git_head"] = "b" * 40
    (artifact / "BUILD_INFO.json").write_text(json.dumps(combined_data))
    try:
        artifact_module.verify(artifact, "a" * 40, "123")
    except artifact_module.ArtifactError:
        pass
    else:
        raise AssertionError("mismatched nested build-info was accepted")
    (artifact / "BUILD_INFO.json").write_text(build_info.read_text())
    (artifact / "extra").write_text("reject")
    try:
        artifact_module.verify(artifact, "a" * 40, "123")
    except artifact_module.ArtifactError:
        pass
    else:
        raise AssertionError("extra artifact was accepted")

print("release attestation host self-test: PASS")
PY
