#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
set -eu

root=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd -P)
python3 - "$root" <<'PY'
import importlib.util
import copy
import json
import os
import subprocess
import tempfile
import zipfile
from pathlib import Path

root = Path(__import__("sys").argv[1])

def load(name):
    path = root / "tools/mirakc" / f"{name}.py"
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

rehearsal = load("rescue-rehearsal")
apk = load("verify-rescue-apk")
verify = load("verify-rescue-rehearsal")
build_info = load("verify-rescue-build-info")
build_source = (root / "tools/mirakc/build-legacy-rescue.sh").read_text()
rehearsal_source = (root / "tools/mirakc/rescue-rehearsal.py").read_text()
assert "5a4d647c9e4b46f3f637165fa107f87d34ea22ed" in build_source
assert "1a22a7180abd6c7be1d1dda6b866ec321a4e28ab" in build_source
assert 'rescue_version="0.3.1"' in build_source and 'rescue_code="301"' in build_source
assert '"--no-rebind"' in rehearsal_source
assert '"install", "-r"' in rehearsal_source
assert '"rescue-retry"' in rehearsal_source
assert "--ready-timeout" in rehearsal_source and "--install-timeout" in rehearsal_source
assert "subprocess.TimeoutExpired" in rehearsal_source and "cleanup_error" in rehearsal_source
assert "default=960.0" in rehearsal_source
assert rehearsal_source.index('legacy_ready = self.start_and_wait("legacy", "3.4.82")') < rehearsal_source.index('candidate_after = self.install("candidate", candidate)')
assert rehearsal_source.index('candidate_ready = self.start_and_wait("candidate", "3.4.86")') < rehearsal_source.index('self.install("rescue", rescue)')
assert "BUILD_INFO-legacy-server-rescue.txt" in rehearsal_source
workflow_source = (root / ".github/workflows/signed-candidate.yml").read_text()
assert "CANDIDATE_BUILD_INFO.txt" in workflow_source and "RESCUE_BUILD_INFO.txt" in workflow_source
assert "BUILD_INFO.json" in workflow_source
assert "steps.resolve-candidate.outputs.git_head" in workflow_source
assert "needs.build.outputs.git_head" in workflow_source
with tempfile.TemporaryDirectory(prefix="rescue-build-info-test-") as temporary:
    directory = Path(temporary)
    candidate_info = directory / "candidate.txt"
    rescue_info = directory / "rescue.txt"
    candidate_info.write_text("\n".join([
        "kind=candidate", "git_ref=main", "git_head=" + "a" * 40,
        "version=0.3.0", "unsigned_apk_sha256=" + "b" * 64, "",
    ]))
    rescue_info.write_text("\n".join([
        "kind=legacy-server-rescue", "version_name=0.3.1", "version_code=301",
        "legacy_dtv_ref=mirakc-v0.2.0", "legacy_dtv_commit=5a4d647c9e4b46f3f637165fa107f87d34ea22ed",
        "legacy_siano_ref=v0.1.1", "legacy_siano_commit=1a22a7180abd6c7be1d1dda6b866ec321a4e28ab",
        "unsigned_apk_sha256=" + "c" * 64, "",
    ]))
    assert build_info.verify(candidate_info, rescue_info)["candidate"]["version"] == "0.3.0"
    rescue_info.write_text(rescue_info.read_text().replace("301", "302"))
    try:
        build_info.verify(candidate_info, rescue_info)
    except build_info.BuildInfoError:
        pass
    else:
        raise AssertionError("stale rescue build-info was accepted")
with tempfile.TemporaryDirectory(prefix="rescue-rehearsal-output-") as temporary:
    out = Path(temporary)
    (out / "stale").write_text("must not be reused\n")
    rejected = subprocess.run([
        "python3", str(root / "tools/mirakc/rescue-rehearsal.py"),
        "--candidate", "candidate.apk", "--rescue", "rescue.apk", "--plan", "plan.json",
        "--out", str(out), "--aapt2", "aapt2", "--apksigner", "apksigner",
    ], capture_output=True, text=True, check=False)
    assert rejected.returncode != 0 and "output directory is not empty" in rejected.stderr
assert '"uninstall"' not in rehearsal_source
assert '"pm", "clear"' not in rehearsal_source
assert " reverse " not in rehearsal_source
assert apk.parse_badging("package: name='dev.khronos31.mirakc' versionCode='301' versionName='0.3.1'") == {
    "package": "dev.khronos31.mirakc", "version_code": "301", "version_name": "0.3.1"
}
asset = "\n".join([
    "kind=legacy-server-rescue", "package=dev.khronos31.mirakc",
    "version_name=0.3.1", "version_code=301",
    "legacy_dtv_ref=mirakc-v0.2.0", "legacy_dtv_commit=5a4d647c9e4b46f3f637165fa107f87d34ea22ed",
    "legacy_siano_ref=v0.1.1", "legacy_siano_commit=1a22a7180abd6c7be1d1dda6b866ec321a4e28ab",
    "not-a-normal-release=true",
])
assert apk.parse_asset((asset + "\n").encode()) ["kind"] == "legacy-server-rescue"
with tempfile.TemporaryDirectory(prefix="rescue-apk-test-") as temporary:
    unsafe_apk = Path(temporary) / "unsafe.apk"
    with zipfile.ZipFile(unsafe_apk, "w") as archive:
        archive.writestr("../escape", b"bad")
    try:
        apk.safe_zip_entries(unsafe_apk)
    except apk.RescueVerificationError:
        pass
    else:
        raise AssertionError("unsafe APK ZIP path was accepted")
assert rehearsal.safe_route("/api/services", "services_path") == "/api/services"
assert rehearsal.parse_version(b'{"current":"3.4.86"}') == "3.4.86"
try:
    rehearsal.parse_version(b'{"version":"3.4.86"}')
except rehearsal.RehearsalError:
    pass
else:
    raise AssertionError("legacy version response without current was accepted")
import argparse
valid_args = argparse.Namespace(adb_timeout=1.0, install_timeout=1.0, start_timeout=1.0, http_timeout=1.0, ready_timeout=1.0, host_port=40773, device_port=40772, max_bytes=1880)
rehearsal.validate_args(valid_args)
for field, value in (("host_port", 0), ("ready_timeout", float("nan"))):
    invalid = argparse.Namespace(**vars(valid_args))
    setattr(invalid, field, value)
    try:
        rehearsal.validate_args(invalid)
    except rehearsal.RehearsalError:
        pass
    else:
        raise AssertionError(f"invalid {field} was accepted")
with tempfile.TemporaryDirectory(prefix="rescue-forward-cleanup-") as temporary:
    cleanup = object.__new__(rehearsal.Rehearsal)
    cleanup.forwarded = True
    cleanup.args = argparse.Namespace(host_port=40773)
    cleanup.evidence_dir = Path(temporary) / "evidence"
    cleanup.evidence_dir.mkdir()
    cleanup.adb = lambda *parts, **kwargs: __import__("subprocess").CompletedProcess(parts, 9, "out\n", "err\n")
    try:
        cleanup.remove_forward()
    except rehearsal.RehearsalError:
        pass
    else:
        raise AssertionError("nonzero forward cleanup was accepted")
    assert cleanup.forwarded is False
    assert (cleanup.evidence_dir / "forward/remove.json").is_file()
for bad in ("http://127.0.0.1/api/services", "/api/../private", "//other/api"):
    try:
        rehearsal.safe_route(bad, "route")
    except rehearsal.RehearsalError:
        pass
    else:
        raise AssertionError(f"unsafe route accepted: {bad}")
clear = bytearray()
for _ in range(10):
    packet = bytearray(188)
    packet[0] = 0x47
    packet[3] = 0x10
    clear.extend(packet)
assert rehearsal.ts_summary(bytes(clear))["packets"] == 10
scrambled = bytearray(clear)
scrambled[3] = 0x90
try:
    rehearsal.ts_summary(bytes(scrambled))
except rehearsal.RehearsalError:
    pass
else:
    raise AssertionError("scrambled rescue stream was accepted")

with tempfile.TemporaryDirectory(prefix="rescue-rehearsal-test-") as temporary:
    out = Path(temporary)
    (out / "evidence").mkdir()
    (out / "evidence" / "marker.txt").write_text("fixture\n")
    for phase in ("candidate", "rescue"):
        (out / "evidence" / "install").mkdir(exist_ok=True)
        (out / "evidence" / "install" / f"{phase}.json").write_text("{}\n")
        (out / "evidence" / "install" / f"{phase}.stdout.txt").write_text("Success\n")
        (out / "evidence" / "install" / f"{phase}.stderr.txt").write_text("\n")
    metadata = {
        "package": "dev.khronos31.mirakc", "version_name": "0.3.0", "version_code": "300",
        "certificate_sha256": verify.EXPECTED_CERT, "apk_sha256": "a" * 64, "apk_basename": "candidate.apk",
    }
    recovery = {
        "package": "dev.khronos31.mirakc", "version_name": "0.3.1", "version_code": "301",
        "certificate_sha256": verify.EXPECTED_CERT, "apk_sha256": "b" * 64, "apk_basename": "rescue.apk",
        "provenance": verify.EXPECTED_RESCUE_PROVENANCE,
    }
    services = [{"name": "fixture-s1ud"}]
    snapshot = {
        "services": services,
        "services_canonical": json.dumps(services, sort_keys=True, separators=(",", ":")),
        "stream": {"bytes": 1880, "packets": 10, "scrambled_packets": 0},
    }
    installed_legacy = {"certificate_sha256": verify.EXPECTED_CERT, "installed_apk_sha256": "c" * 64}
    package_legacy = {"package": "dev.khronos31.mirakc", "version_name": "0.2.0", "version_code": "200", "first_install_time": "fixture-time"}
    package_candidate = {"package": "dev.khronos31.mirakc", "version_name": "0.3.0", "version_code": "300", "first_install_time": "fixture-time"}
    package_rescue = {"package": "dev.khronos31.mirakc", "version_name": "0.3.1", "version_code": "301", "first_install_time": "fixture-time"}
    result = {
        "schema": 1, "kind": "mirakc-rescue-rehearsal", "status": "pass",
        "candidate": metadata, "rescue": recovery,
        "started_at": "2026-09-21T00:00:00+00:00",
        "finished_at": "2026-09-21T00:00:01+00:00",
        "device": {
            "serial": "fixture-serial",
            "first_install_time": "fixture-time",
            "build": {
                "ro.build.type": "user", "ro.build.id": "fixture-build",
                "ro.build.version.release": "14", "ro.product.device": "fixture-device",
            },
        },
        "installed_legacy": installed_legacy,
        "initial_package": package_legacy,
        "candidate_after": package_candidate,
        "final_package": package_rescue,
        "before": snapshot,
        "candidate_snapshot": snapshot,
        "after_rescue": snapshot,
        "candidate_ready": {"phase": "candidate", "version": "3.4.86", "service_count": 1},
        "ready_records": [
            {"phase": "legacy", "version": "3.4.82", "service_count": 1},
            {"phase": "candidate", "version": "3.4.86", "service_count": 1},
            {"phase": "rescue", "version": "3.4.82", "service_count": 1},
        ],
        "install_records": [
            {"phase": "candidate", "command": "adb -s fixture-serial install -r " + "a" * 64 + "/candidate.apk", "apk_sha256": "a" * 64, "basename": "candidate.apk", "returncode": 0, "timed_out": False},
            {"phase": "rescue", "command": "adb -s fixture-serial install -r " + "b" * 64 + "/rescue.apk", "apk_sha256": "b" * 64, "basename": "rescue.apk", "returncode": 0, "timed_out": False},
        ],
        "checks": {key: "pass" for key in verify.REQUIRED_CHECKS},
    }
    rehearsal.write_receipt(out, result)
    assert verify.verify(out)["status"] == "pass"
    manifest = json.loads((out / "receipt-manifest.json").read_text())
    manifest["evidence"]["../escape"] = {"sha256": "0" * 64, "size": 0}
    (out / "receipt-manifest.json").write_text(json.dumps(manifest))
    try:
        verify.verify(out)
    except verify.VerificationError:
        pass
    else:
        raise AssertionError("path traversal manifest was accepted")

with tempfile.TemporaryDirectory(prefix="rescue-rehearsal-link-test-") as temporary:
    out = Path(temporary)
    (out / "evidence").mkdir()
    (out / "evidence" / "marker.txt").write_text("fixture\n")
    for phase in ("candidate", "rescue"):
        (out / "evidence" / "install").mkdir(exist_ok=True)
        (out / "evidence" / "install" / f"{phase}.json").write_text("{}\n")
        (out / "evidence" / "install" / f"{phase}.stdout.txt").write_text("Success\n")
        (out / "evidence" / "install" / f"{phase}.stderr.txt").write_text("\n")
    result = {
        "schema": 1, "kind": "mirakc-rescue-rehearsal", "status": "pass",
        "candidate": metadata, "rescue": recovery,
        "started_at": "2026-09-21T00:00:00+00:00",
        "finished_at": "2026-09-21T00:00:01+00:00",
        "device": {
            "serial": "fixture-serial",
            "first_install_time": "fixture-time",
            "build": {
                "ro.build.type": "user", "ro.build.id": "fixture-build",
                "ro.build.version.release": "14", "ro.product.device": "fixture-device",
            },
        },
        "installed_legacy": installed_legacy,
        "initial_package": package_legacy,
        "candidate_after": package_candidate,
        "final_package": package_rescue,
        "before": snapshot,
        "candidate_snapshot": snapshot,
        "after_rescue": snapshot,
        "candidate_ready": {"phase": "candidate", "version": "3.4.86", "service_count": 1},
        "ready_records": [
            {"phase": "legacy", "version": "3.4.82", "service_count": 1},
            {"phase": "candidate", "version": "3.4.86", "service_count": 1},
            {"phase": "rescue", "version": "3.4.82", "service_count": 1},
        ],
        "install_records": [
            {"phase": "candidate", "command": "adb -s fixture-serial install -r " + "a" * 64 + "/candidate.apk", "apk_sha256": "a" * 64, "basename": "candidate.apk", "returncode": 0, "timed_out": False},
            {"phase": "rescue", "command": "adb -s fixture-serial install -r " + "b" * 64 + "/rescue.apk", "apk_sha256": "b" * 64, "basename": "rescue.apk", "returncode": 0, "timed_out": False},
        ],
        "checks": {key: "pass" for key in verify.REQUIRED_CHECKS},
    }
    rehearsal.write_receipt(out, result)
    os.symlink("/etc/hosts", out / "evidence" / "external-link")
    try:
        verify.verify(out)
    except verify.VerificationError:
        pass
    else:
        raise AssertionError("symlink evidence was accepted")

def assert_rejected(mutator):
    with tempfile.TemporaryDirectory(prefix="rescue-rehearsal-negative-") as temporary:
        out = Path(temporary)
        (out / "evidence").mkdir()
        (out / "evidence" / "marker.txt").write_text("fixture\n")
        negative = copy.deepcopy(result)
        mutator(negative)
        rehearsal.write_receipt(out, negative)
        try:
            verify.verify(out)
        except verify.VerificationError:
            return
        raise AssertionError("invalid receipt was accepted")

assert_rejected(lambda value: value.pop("candidate_snapshot"))
assert_rejected(lambda value: value.pop("initial_package"))
assert_rejected(lambda value: value["initial_package"].update(version_code="201"))
assert_rejected(lambda value: value["candidate_after"].update(version_code="301"))
assert_rejected(lambda value: value["final_package"].update(first_install_time="other-time"))
assert_rejected(lambda value: value["after_rescue"].update(services_canonical="[]"))
assert_rejected(lambda value: value["before"]["stream"].update(bytes=1881))
assert_rejected(lambda value: value["candidate"].update(apk_sha256="A" * 64))

print("rescue rehearsal host self-test: PASS")
PY
