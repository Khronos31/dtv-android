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
assert "mirakc-rescue-rehearsal" not in release_workflow
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
    "rescue_apk_sha256": "c" * 64,
    "device_receipt_manifest_sha256": "d" * 64,
    "rescue_receipt_manifest_sha256": "e" * 64,
})
assert module.parse_message(valid)["candidate_run_id"] == "123"
acceptance_values = {"schema": 1, "kind": "mirakc-release-acceptance", **module.parse_message(valid)}
assert acceptance_values["device_receipt_manifest_sha256"] == "d" * 64
for malformed in (valid.replace("candidate_run_id=123", "unknown=123"), valid + "candidate_run_id=123\n", valid.replace("rescue_apk_sha256=c" + "c" * 63, "rescue_apk_sha256=Z" * 64)):
    try:
        module.parse_message(malformed)
    except module.AttestationError:
        pass
    else:
        raise AssertionError("malformed attestation was accepted")

with tempfile.TemporaryDirectory(prefix="mirakc-release-attestation-") as temporary:
    directory = Path(temporary)
    candidate = directory / "candidate.apk"
    rescue = directory / "rescue.apk"
    candidate.write_bytes(b"candidate")
    rescue.write_bytes(b"rescue")
    candidate_hash = sha256(candidate.read_bytes()).hexdigest()
    rescue_hash = sha256(rescue.read_bytes()).hexdigest()
    device = directory / "device"
    rescue_receipt = directory / "rescue-receipt"
    device.mkdir()
    rescue_receipt.mkdir()
    (device / "receipt-manifest.json").write_text("device\n")
    (rescue_receipt / "receipt-manifest.json").write_text("rescue\n")
    build_info = directory / "BUILD_INFO.json"
    build_info.write_text(json.dumps({
        "schema": 1,
        "candidate": {"build": {"kind": "candidate", "candidate_run_id": "123", "git_ref": "main", "git_head": "a" * 40, "version": "0.3.0", "unsigned_apk_sha256": "f" * 64}, "signed_apk_sha256": candidate_hash, "certificate_sha256": module.CERT},
        "rescue": {"build": {"kind": "legacy-server-rescue", "version_name": "0.3.1", "version_code": "301", "legacy_dtv_ref": "mirakc-v0.2.0", "legacy_dtv_commit": "5a4d647c9e4b46f3f637165fa107f87d34ea22ed", "legacy_siano_ref": "v0.1.1", "legacy_siano_commit": "1a22a7180abd6c7be1d1dda6b866ec321a4e28ab", "unsigned_apk_sha256": "f" * 64}, "signed_apk_sha256": rescue_hash, "certificate_sha256": module.CERT},
    }))
    (directory / "candidate-info.txt").write_text("kind=candidate\ncandidate_run_id=123\ngit_ref=main\ngit_head=" + "a" * 40 + "\nversion=0.3.0\nunsigned_apk_sha256=" + "f" * 64 + "\n")
    (directory / "rescue-info.txt").write_text("kind=legacy-server-rescue\nversion_name=0.3.1\nversion_code=301\nlegacy_dtv_ref=mirakc-v0.2.0\nlegacy_dtv_commit=5a4d647c9e4b46f3f637165fa107f87d34ea22ed\nlegacy_siano_ref=v0.1.1\nlegacy_siano_commit=1a22a7180abd6c7be1d1dda6b866ec321a4e28ab\nunsigned_apk_sha256=" + "f" * 64 + "\n")
    module.device_verifier.verify = lambda *args: {"candidate_apk_sha256": candidate_hash}
    module.rescue_verifier.verify = lambda *args: {"candidate_apk_sha256": candidate_hash, "rescue_apk_sha256": rescue_hash}
    result = module.verify_inputs(device, rescue_receipt, candidate, rescue, build_info, "a" * 40)
    assert module.parse_message(result["message"]) == result["values"]
    try:
        module.verify_inputs(device, rescue_receipt, candidate, rescue, build_info, "b" * 40)
    except module.AttestationError:
        pass
    else:
        raise AssertionError("tag target mismatch was accepted")
    artifact = directory / "artifact"
    artifact.mkdir()
    for source, name in ((candidate, "mirakc-signed-candidate.apk"), (rescue, "mirakc-signed-legacy-server-rescue-0.3.1.apk"), (directory / "candidate-info.txt", "CANDIDATE_BUILD_INFO.txt"), (directory / "rescue-info.txt", "RESCUE_BUILD_INFO.txt"), (build_info, "BUILD_INFO.json")):
        (artifact / name).write_bytes(source.read_bytes())
    (artifact / "SHA256SUMS").write_text(
        f"{candidate_hash}  mirakc-signed-candidate.apk\n{rescue_hash}  mirakc-signed-legacy-server-rescue-0.3.1.apk\n"
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
