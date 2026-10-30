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
candidate_workflow = (root / ".github/workflows/signed-candidate.yml").read_text(encoding="utf-8")
assert "mirakc-device-evidence" not in release_workflow
assert "device_receipt_manifest_sha256" not in release_workflow
for old_value in (
    "89c240b8af021d55d81b3b90fce79a3690605811",
    "7ad5f6691f77a9aa58c4097f8b6dfea77f6d9b6a",
    "c60214256a40b03f3469b5a508f149c12e599282edf103875a2170b816c4b697",
    "px4-userland-receiver-control-concurrency.patch",
):
    assert old_value not in candidate_workflow
for reference in (
    "67420d1c909e9205841612a186ae4c7fbf4dd092",
    "e2a86ca194b60f1111ecfa0a020cd0944c351c76",
    "66f5a731784d205d1d306edda9cc57071c087f8e",
    "5c91e8c57b288c0f490061789829e42dbbf61b3d",
    "33c3468e7e10781ae6c7e9a5fd57a0c1b0825288",
    "90b08dfe79b0b40e393f015975c761863830866a",
    "2b3f79b5bc5db56e8556bb28397f7d8f74b2adeb",
):
    assert reference in candidate_workflow
assert "--max-workers=1" in candidate_workflow
for workflow in (candidate_workflow, release_workflow, (root / ".github/workflows/ci.yml").read_text(encoding="utf-8")):
    assert "JAVA_RUNTIME_VERSION: \"17.0.20.1+1\"" in workflow
    assert "JAVA_ARCHIVE_URL: \"https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz\"" in workflow
    assert "JAVA_ARCHIVE_SHA256: \"3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e\"" in workflow
    assert workflow.count("distribution: jdkfile") == workflow.count("name: Download and verify the pinned Temurin JDK")
    assert workflow.count("sha256sum --check --strict") == workflow.count("distribution: jdkfile")
    assert workflow.count('test "$runtime_version" = "$JAVA_RUNTIME_VERSION"') == workflow.count("distribution: jdkfile")
    assert "java-version: ${{ env.JAVA_VERSION }}" in workflow
    assert "sha256sum --check --strict" in workflow
assert candidate_workflow.count('tools/mirakc/audit-apk.sh') == 2
sign_workflow = candidate_workflow.split("- name: Sign and verify candidate", 1)[1].split("\n      - ", 1)[0]
assert 'checkout_version="$(cat mirakc/VERSION)"' in sign_workflow
assert 'test "$candidate_version" = "$checkout_version"' in sign_workflow
assert '--expected-version "$checkout_version"' in sign_workflow
assert '<<<"$checkout_version"' in sign_workflow
assert '"$checkout_version" "$candidate_version_code"' in sign_workflow
assert '"$expected_version"' in sign_workflow and '"$expected_code"' in sign_workflow
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
source_spec = importlib.util.spec_from_file_location(
    "source_audit", root / "tools/mirakc/audit-source.py"
)
assert source_spec and source_spec.loader
source_audit = importlib.util.module_from_spec(source_spec)
source_spec.loader.exec_module(source_audit)
package_spec = importlib.util.spec_from_file_location(
    "source_package", root / "tools/mirakc/package-source.py"
)
assert package_spec and package_spec.loader
source_package = importlib.util.module_from_spec(package_spec)
package_spec.loader.exec_module(source_package)
assert source_audit.TOOLCHAIN["jdk"] == {
    "distribution": "temurin",
    "installer": "setup-java@v5 jdkfile",
    "selector": "17.0.20",
    "runtime_version": "17.0.20.1+1",
    "observed": "Temurin 17.0.20.1+1",
    "archive_url": "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz",
    "archive_sha256": "3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e",
    "required_major": 17,
    "verification": "pinned official Temurin archive SHA-256 and exact runtime gate",
}
assert source_package.TOOLCHAIN["jdk"] == source_audit.TOOLCHAIN["jdk"]
for field, component in (
    ("dtv_userland_core_ref", "dtv-userland-core"),
    ("asicen_userland_ref", "asicen-userland"),
    ("pix_userland_ref", "pix-userland"),
    ("px4_userland_ref", "px4-userland"),
    ("siano_userland_ref", "siano-userland"),
    ("siano_px4_userland_ref", "siano-px4-userland"),
    ("px4_drv_ref", "px4_drv"),
):
    assert module.SOURCE_REFS[field] == source_audit.EXPECTED[component]["commit"]
assert module.PATCH_DIGESTS["dtv_userland_core_patch_sha256"] == source_audit.CORE_ANDROID_PATCH_SHA256
assert module.PATCH_DIGESTS["px4_userland_build_patch_sha256"] == source_audit.PX4_BUILD_JOBS_PATCH_SHA256
assert module.PATCH_DIGESTS["pix_userland_build_patch_sha256"] == source_audit.PIX_ANDROID_16K_PATCH_SHA256
assert "PIX_BUILD_PATCH_SHA256" in candidate_workflow
assert "pix_userland_build_patch_sha256=$pix_build_patch_sha256" in candidate_workflow

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
        "candidate": {"build": {"kind": "candidate", "candidate_run_id": "123", "git_ref": "main", "git_head": "a" * 40, "version": "0.3.3", "unsigned_apk_sha256": "f" * 64}, "signed_apk_sha256": candidate_hash, "certificate_sha256": module.CERT},
    }))
    (directory / "candidate-info.txt").write_text("kind=candidate\ncandidate_run_id=123\ngit_ref=main\ngit_head=" + "a" * 40 + "\nversion=0.3.3\nunsigned_apk_sha256=" + "f" * 64 + "\n")
    result = module.verify_inputs(candidate, build_info, "a" * 40, "0.3.3")
    assert module.parse_message(result["message"]) == result["values"]
    result = module.verify_inputs(candidate, build_info, "b" * 40, "0.3.3")
    assert result["values"]["candidate_git_head"] == "a" * 40
    stale_build_info = directory / "stale-BUILD_INFO.json"
    stale_build_info.write_text(json.dumps({
        "schema": 1,
        "candidate": {"build": {"kind": "candidate", "candidate_run_id": "123", "git_ref": "main", "git_head": "a" * 40, "version": "0.3.3", "unsigned_apk_sha256": "f" * 64}, "signed_apk_sha256": candidate_hash, "certificate_sha256": module.CERT},
        "unknown": {"build": {"kind": "unknown", "version": "0.3.3"}, "signed_apk_sha256": "c" * 64, "certificate_sha256": module.CERT},
    }))
    try:
        module.verify_inputs(candidate, stale_build_info, "a" * 40, "0.3.3")
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
    checked = artifact_module.verify(artifact, "a" * 40, "123", "0.3.3")
    assert checked["candidate_apk_sha256"] == candidate_hash
    combined_data = json.loads((artifact / "BUILD_INFO.json").read_text())
    combined_data["candidate"]["build"]["git_head"] = "b" * 40
    (artifact / "BUILD_INFO.json").write_text(json.dumps(combined_data))
    try:
        artifact_module.verify(artifact, "a" * 40, "123", "0.3.3")
    except artifact_module.ArtifactError:
        pass
    else:
        raise AssertionError("mismatched nested build-info was accepted")
    (artifact / "BUILD_INFO.json").write_text(build_info.read_text())
    (artifact / "extra").write_text("reject")
    try:
        artifact_module.verify(artifact, "a" * 40, "123", "0.3.3")
    except artifact_module.ArtifactError:
        pass
    else:
        raise AssertionError("extra artifact was accepted")

    new_candidate = directory / "candidate-040.apk"
    new_candidate.write_bytes(b"candidate-040")
    new_candidate_hash = sha256(new_candidate.read_bytes()).hexdigest()
    new_build = {
        "kind": "candidate",
        "candidate_run_id": "456",
        "git_ref": "release/0.4.1",
        "git_head": "c" * 40,
        "version": "0.4.1",
        **module.SOURCE_REFS,
        **module.PATCH_DIGESTS,
        "unsigned_apk_sha256": new_candidate_hash,
    }
    new_build_info = directory / "BUILD_INFO-040.json"
    new_build_info.write_text(json.dumps({
        "schema": 1,
        "candidate": {
            "build": new_build,
            "signed_apk_sha256": new_candidate_hash,
            "certificate_sha256": module.CERT,
        },
    }))
    (directory / "candidate-info-040.txt").write_text(
        "kind=candidate\ncandidate_run_id=456\ngit_ref=release/0.4.1\n"
        + "git_head=" + "c" * 40 + "\nversion=0.4.1\n"
        + "".join(f"{key}={value}\n" for key, value in module.SOURCE_REFS.items())
        + "".join(f"{key}={value}\n" for key, value in module.PATCH_DIGESTS.items())
        + "unsigned_apk_sha256=" + new_candidate_hash + "\n"
    )
    unsigned_info = module.parse_candidate_info(directory / "candidate-info-040.txt")
    module.verify_candidate_build(unsigned_info, "0.4.1")
    checkout_version_file = directory / "checkout/mirakc/VERSION"
    checkout_version_file.parent.mkdir(parents=True)
    checkout_version_file.write_text("0.4.1\n")
    checkout_version = checkout_version_file.read_text(encoding="utf-8").strip()
    assert unsigned_info["version"] == checkout_version
    assert module.verify_unsigned_candidate(
        new_candidate, directory / "candidate-info-040.txt", checkout_version, "c" * 40,
        "456", "release/0.4.1"
    )["unsigned_apk_sha256"] == new_candidate_hash
    wrong_version_info = dict(unsigned_info)
    wrong_version_info["version"] = "0.4.2"
    wrong_version_path = directory / "candidate-info-wrong-version.txt"
    wrong_version_path.write_text("".join(f"{key}={value}\n" for key, value in wrong_version_info.items()))
    try:
        module.verify_unsigned_candidate(
            new_candidate, wrong_version_path, checkout_version, "c" * 40,
            "456", "release/0.4.1"
        )
    except module.AttestationError:
        pass
    else:
        raise AssertionError("candidate version differing from checked-out VERSION was accepted")
    for wrong_head, wrong_run, wrong_ref in (
        ("d" * 40, "456", "release/0.4.1"),
        ("c" * 40, "457", "release/0.4.1"),
        ("c" * 40, "456", "release/0.4.2"),
    ):
        try:
            module.verify_unsigned_candidate(
                new_candidate, directory / "candidate-info-040.txt", "0.4.1",
                wrong_head, wrong_run, wrong_ref
            )
        except module.AttestationError:
            pass
        else:
            raise AssertionError("unsigned candidate with mismatched run binding was accepted")
    wrong_unsigned = directory / "wrong-unsigned.apk"
    wrong_unsigned.write_bytes(b"wrong unsigned candidate")
    try:
        module.verify_unsigned_candidate(
            wrong_unsigned, directory / "candidate-info-040.txt", "0.4.1", "c" * 40,
            "456", "release/0.4.1"
        )
    except module.AttestationError:
        pass
    else:
        raise AssertionError("unsigned APK hash mismatch was accepted")
    for field in module.SOURCE_REFS:
        mismatch = dict(unsigned_info)
        mismatch[field] = "0" * 40
        try:
            module.verify_candidate_build(mismatch, "0.4.1")
        except module.AttestationError:
            pass
        else:
            raise AssertionError(f"candidate with mismatched provenance was accepted: {field}")
    mismatch = dict(unsigned_info)
    mismatch["dtv_userland_core_patch_sha256"] = "0" * 64
    try:
        module.verify_candidate_build(mismatch, "0.4.1")
    except module.AttestationError:
        pass
    else:
        raise AssertionError("candidate with an altered Core patch digest was accepted")
    mismatch = dict(unsigned_info)
    mismatch["pix_userland_build_patch_sha256"] = "0" * 64
    try:
        module.verify_candidate_build(mismatch, "0.4.1")
    except module.AttestationError:
        pass
    else:
        raise AssertionError("candidate with an altered PIX patch digest was accepted")
    missing_product = dict(unsigned_info)
    del missing_product["pix_userland_ref"]
    try:
        module.verify_candidate_build(missing_product, "0.4.1")
    except module.AttestationError:
        pass
    else:
        raise AssertionError("candidate with missing product provenance was accepted")
    legacy_current = {
        key: value for key, value in unsigned_info.items()
        if key not in module.SOURCE_REFS and key not in module.PATCH_DIGESTS
    }
    legacy_current["px4_userland_ref"] = "7ad5f6691f77a9aa58c4097f8b6dfea77f6d9b6a"
    legacy_current["px4_userland_patch_sha256"] = "c60214256a40b03f3469b5a508f149c12e599282edf103875a2170b816c4b697"
    try:
        module.verify_candidate_build(legacy_current, "0.4.1")
    except module.AttestationError:
        pass
    else:
        raise AssertionError("legacy PX4-only provenance was accepted for 0.4.1")
    new_attestation = module.verify_inputs(
        new_candidate, new_build_info, "c" * 40, "0.4.1"
    )
    assert new_attestation["values"]["candidate_run_id"] == "456"
    for field, replacement in (
        ("signed_apk_sha256", "0" * 64),
        ("certificate_sha256", "0" * 64),
    ):
        mismatch = json.loads(new_build_info.read_text())
        mismatch["candidate"][field] = replacement
        mismatch_path = directory / f"mismatched-{field}.json"
        mismatch_path.write_text(json.dumps(mismatch))
        try:
            module.verify_inputs(new_candidate, mismatch_path, "c" * 40, "0.4.1")
        except module.AttestationError:
            pass
        else:
            raise AssertionError(f"mismatched {field} was accepted")
    new_artifact = directory / "artifact-040"
    new_artifact.mkdir()
    for source, name in (
        (new_candidate, "mirakc-signed-candidate.apk"),
        (directory / "candidate-info-040.txt", "CANDIDATE_BUILD_INFO.txt"),
        (new_build_info, "BUILD_INFO.json"),
    ):
        (new_artifact / name).write_bytes(source.read_bytes())
    (new_artifact / "SHA256SUMS").write_text(
        f"{new_candidate_hash}  mirakc-signed-candidate.apk\n"
    )
    checked = artifact_module.verify(new_artifact, "c" * 40, "456", "0.4.1")
    assert checked["candidate_apk_sha256"] == new_candidate_hash
    for wrong_head, wrong_run in (("d" * 40, "456"), ("c" * 40, "457")):
        try:
            artifact_module.verify(new_artifact, wrong_head, wrong_run, "0.4.1")
        except artifact_module.ArtifactError:
            pass
        else:
            raise AssertionError("signed artifact with mismatched run/commit binding was accepted")
    for field, replacement in (
        ("signed_apk_sha256", "0" * 64),
        ("certificate_sha256", "0" * 64),
    ):
        mismatch = json.loads(new_build_info.read_text())
        mismatch["candidate"][field] = replacement
        (new_artifact / "BUILD_INFO.json").write_text(json.dumps(mismatch))
        try:
            artifact_module.verify(new_artifact, "c" * 40, "456", "0.4.1")
        except artifact_module.ArtifactError:
            pass
        else:
            raise AssertionError(f"signed artifact with mismatched {field} was accepted")
    bad_build = json.loads(new_build_info.read_text())
    bad_build["candidate"]["build"]["dtv_userland_core_patch_sha256"] = "0" * 64
    (new_artifact / "BUILD_INFO.json").write_text(json.dumps(bad_build))
    try:
        artifact_module.verify(new_artifact, "c" * 40, "456", "0.4.1")
    except artifact_module.ArtifactError:
        pass
    else:
        raise AssertionError("0.4.1 artifact with the wrong Core patch provenance was accepted")

print("release attestation host self-test: PASS")
PY
