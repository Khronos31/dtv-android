#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
set -eu

root=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd -P)
python3 - "$root/tools/mirakc/device-evidence.py" <<'PY'
import importlib.util
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import threading
import time

path = sys.argv[1]
source = Path(path).read_text()
repo = Path(path).parents[2]
manifest = (repo / "mirakc/src/main/AndroidManifest.xml").read_text()
provider_source = (repo / "mirakc/src/main/java/dev/khronos31/mirakc/MirakcDiagnosticsProvider.kt").read_text()
supervisor_source = (repo / "mirakc/src/main/java/dev/khronos31/mirakc/MirakcSupervisor.kt").read_text()
resilience_patch = (repo / "tools/mirakc/patches/mirakc-android-web-resilience.patch").read_text()
spec = importlib.util.spec_from_file_location("device_evidence", path)
assert spec and spec.loader
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
assert module.EXPECTED_PACKAGE_VERSION == "0.3.2"
assert module.EXPECTED_PACKAGE_VERSION_CODE == "302"
assert module.EXPECTED_CERT_SHA256.startswith("1fd02c")
assert module.EPGSTATION_DEVICE_PORT == 8888
assert module.EPGSTATION_PACKAGE == "dev.khronos31.epgstation.server"
assert module.DEFAULT_MAX_BYTES % 188 == 0 and module.DEFAULT_MAX_BYTES >= 1880
assert module.validate_loopback_url("http://127.0.0.1:40772/api/version")
for invalid_url in (
    "https://127.0.0.1:40772/api/version",
    "http://127.0.0.1/api/version",
    "http://user:pass@127.0.0.1:40772/api/version",
    "http://127.0.0.2:40772/api/version",
):
    try:
        module.validate_loopback_url(invalid_url)
    except module.EvidenceError:
        pass
    else:
        raise AssertionError(f"non-loopback URL was accepted: {invalid_url}")
assert '"forward"' in source and '"reverse"' not in source
assert '"--no-rebind"' in source
assert '"am", "start", "-n", f"{PACKAGE}/.MainActivity"' in source
assert '"shell", "am", "force-stop", PACKAGE' in source and "stopservice" not in source
assert "job_path" not in source and "rss_job_path" not in source
assert "/api/streams/live/" in source and '"/api/streams"' in source
assert '"logcat"' in source and '"-s", "EPGStationServer"' in source
assert '"-T"' not in source
assert 'android:permission="android.permission.DUMP"' in manifest
assert 'android:exported="true"' in manifest and "MirakcDiagnosticsProvider" in manifest
assert "Binder.getCallingUid()" in provider_source
assert "Process.SHELL_UID" in provider_source
assert "checkCallingPermission(android.Manifest.permission.DUMP)" in provider_source
assert "checkCallingOrSelfPermission" not in provider_source
assert "Os.readlink" in provider_source and "canonicalPath" not in provider_source
assert "MAX_PROCESSES" in provider_source and "MAX_SNAPSHOT_NANOS" in provider_source
assert "1000)).await" in resilience_patch
assert provider_source.count("ensureWithinDeadline") >= 4
assert ".acceptance-update-schedules" in provider_source or ".acceptance-update-schedules" in supervisor_source
assert 'ProcessBuilder("/system/bin/timeout"' not in supervisor_source
assert ".acceptance-update-schedules" in supervisor_source
assert supervisor_source.count("clearUpdateSchedulesTrigger()") >= 3
service_source = (repo / "mirakc/src/main/java/dev/khronos31/mirakc/MirakcService.kt").read_text()
assert "@Volatile private var mirakcSupervisor" in service_source
assert "MirakcDiagnostics.triggerUpdateSchedules = null" in service_source
assert "clearUpdateSchedulesTrigger" in supervisor_source
assert "parse_device_epoch(self.adb_shell" in source
cycle_source = source[source.index("    def check_cycles"):source.index("    def tracked_rows")]
assert cycle_source.index("self.observe_job") < cycle_source.index("self.forward_mirakc")
assert "job_thread.join(2 * self.args.job_timeout + self.args.timeout + 1)" in source
assert "thread.join()" not in source

assert module.parse_package_badging(
    "package: name='dev.khronos31.mirakc' versionCode='302' versionName='0.3.2'"
) == {"package": "dev.khronos31.mirakc", "version_code": "302", "version_name": "0.3.2"}
assert module.parse_cert_digest("Signer #1 certificate SHA-256 digest: AA:bb:00") == "aabb00"
try:
    module.parse_int(True)
except (ValueError, TypeError):
    pass
else:
    raise AssertionError("boolean was accepted as an integer")
processes = module.parse_processes("PID PPID ARGS\n123 1 libmirakc.so\n124 123 libpx4d.so\n")
assert processes[124]["ppid"] == 123
assert processes[124]["argv0"] == "libpx4d.so"
assert processes[124]["args"] == ["libpx4d.so"]
collect_process = module.parse_processes(
    "123 1 /data/app/dev.khronos31.mirakc-abc/lib/libmirakc-arib.so collect-eits --sids=7\n"
)[123]
scan_process = module.parse_processes(
    "124 1 /data/app/dev.khronos31.mirakc-abc/lib/libmirakc-arib.so scan-services\n"
)[124]
assert module.is_collect_eits_process(collect_process)
assert not module.is_collect_eits_process(scan_process)
try:
    module.parse_processes("123 1 libmirakc.so\n123 1 libpx4d.so\n")
except module.EvidenceError:
    pass
else:
    raise AssertionError("duplicate process PID was accepted")

clear = bytearray()
for _ in range(12):
    packet = bytearray(188)
    packet[0] = 0x47
    packet[1] = 0x01
    packet[2] = 0x11
    packet[3] = 0x10
    clear.extend(packet)
summary = module.ts_summary(bytes(clear), [273])
assert summary["pids"][273]["scrambled"] == 0
# The first tune in every cycle uses this same packet-alignment/integrity
# contract; recovery is not the only tune that is checked.
initial_tune = module.ts_summary(bytes(clear))
assert initial_tune["packets"] == 12

scrambled = bytearray(clear)
scrambled[3] = 0x90
try:
    module.ts_summary(bytes(scrambled), [273])
except module.EvidenceError:
    pass
else:
    raise AssertionError("scrambled TS was accepted")

try:
    module.parse_package_badging("malformed package metadata")
except module.EvidenceError:
    pass
else:
    raise AssertionError("malformed candidate metadata was accepted")

channels = module.parse_epg_channels([{
    "id": 7, "serviceId": 70, "networkId": 700, "name": "Test",
    "halfWidthName": "Test", "hasLogoData": False, "channelType": "GR", "channel": "27",
}])
assert channels[0]["id"] == 7
now = 1_700_000_000_000
schedules = module.parse_epg_schedules([{
    "channel": {"id": 7, "serviceId": 70, "networkId": 700, "name": "Test", "channelType": "GR", "hasLogoData": False},
    "programs": [{"id": 9, "channelId": 7, "startAt": now - 1000, "endAt": now + 1000, "isFree": False, "name": "Live"}],
}], now)
assert schedules[0]["program_id"] == 9
streams = module.parse_epg_streams({"items": [{
    "streamId": 3, "type": "LiveStream", "mode": 0, "isEnable": True,
    "channelId": 7, "name": "Live", "startAt": now - 1000, "endAt": now + 1000,
}]})
assert streams[0]["streamId"] == 3
assert module.parse_epg_streams({"items": []}) == []
assert 'method="DELETE"' not in source
try:
    module.require_no_epg_streams({"items": streams})
except module.EvidenceError:
    pass
else:
    raise AssertionError("pre-existing EPGStation stream was accepted")
old_log = f"{now / 1000 - 1:.3f} I/EPGStationServer: event stream started\n"
try:
    module.parse_logcat_epoch(old_log, now / 1000)
except module.EvidenceError:
    pass
else:
    raise AssertionError("pre-boundary EPGStation log was accepted")
log = "\n".join([
    f"{now / 1000 + 1:.3f} I/EPGStationServer: event stream started",
    f"{now / 1000 + 2:.3f} I/EPGStationServer: done update channel",
    f"{now / 1000 + 3:.3f} I/EPGStationServer: done update programs",
])
assert all(module.parse_logcat_epoch(log, now / 1000).values())
assert module.parse_device_epoch("1700000000.125\n") == 1700000000.125
try:
    module.parse_device_epoch("not-an-epoch")
except module.EvidenceError:
    pass
else:
    raise AssertionError("invalid device epoch was accepted")
with tempfile.TemporaryDirectory(prefix="device-evidence-args-test-") as temporary:
    assert module.main(["missing.apk", "--out", temporary, "--max-bytes", "1881"]) == 2

state_harness = module.Harness.__new__(module.Harness)
state_harness.epg_preexisting = False
state_harness.epg_started = False
state_harness.epg_started_by_harness = False
state_harness.adb_shell = lambda *args: "1700000000.125\n"
state_harness.package_running = lambda *args: True
state_harness.adb_command = lambda *args: (_ for _ in ()).throw(AssertionError("preexisting EPGStation was started"))
assert state_harness.start_epgstation() == 1700000000.125
assert state_harness.epg_preexisting and not state_harness.epg_started_by_harness

started_harness = module.Harness.__new__(module.Harness)
started_harness.epg_preexisting = False
started_harness.epg_started = False
started_harness.epg_started_by_harness = False
started_harness.adb_shell = lambda *args: "1700000000.125\n"
started_harness.package_running = lambda *args: False
started_commands = []
started_harness.adb_command = lambda *args: started_commands.append(args)
assert started_harness.start_epgstation() == 1700000000.125
assert started_harness.epg_started_by_harness
started_harness.stop_epgstation()
assert not started_harness.epg_started_by_harness
assert any("force-stop" in command for command in started_commands[1])

q3u4 = [{"name": f"PX4-GR-{index}", "types": ["GR"]} for index in range(4)]
q3u4.extend({"name": f"PX4-S-{index}", "types": ["BS", "CS"]} for index in range(4))
q3u4.append({"name": "Siano-12seg", "types": ["GR"]})
assert module.validate_q3u4_inventory(q3u4)["px4_count"] == 8
q3u4[0]["types"] = ["GR", "BS"]
try:
    module.validate_q3u4_inventory(q3u4)
except module.EvidenceError:
    pass
else:
    raise AssertionError("non-exact PX4-GR types were accepted")

active_rows = {
    1: {"argv0": "dev.khronos31.mirakc", "text": "1 0 dev.khronos31.mirakc"},
    2: {"argv0": "/data/app/dev.khronos31.mirakc-abc/lib/libsiano-ts.so", "text": "2 1 /data/app/dev.khronos31.mirakc-abc/lib/libsiano-ts.so"},
    3: {"argv0": "/data/app/dev.khronos31.mirakc-abc/lib/libmirakc-b25-filter.so", "text": "3 1 /data/app/dev.khronos31.mirakc-abc/lib/libmirakc-b25-filter.so"},
    4: {"argv0": "/data/app/dev.khronos31.mirakc-abc/lib/libpx4d.so", "text": "4 1 /data/app/dev.khronos31.mirakc-abc/lib/libpx4d.so"},
}
active_tables = {
    1: [],
    2: ["lrwx------ 1 u u 64 x -> /dev/bus/usb/001/002"],
    3: ["lrwx------ 1 u u 64 x -> /dev/usb/ccid-reader"],
    4: ["lrwx------ 1 u u 64 x -> /dev/bus/usb/001/003"],
}
assert module.validate_descriptor_snapshot(active_tables, active_rows)["px4_owner_pids"] == [4]
snapshot_json = json.dumps({"schema": 1, "uid": 10131, "processes": [
    {"pid": 2, "ppid": 1, "argv0": active_rows[2]["argv0"], "fds": [{"fd": 4, "target": "/dev/bus/usb/001/002"}]},
]})
diagnostic_rows, diagnostic_tables = module.parse_diagnostic_snapshot("Row: 0 json=" + snapshot_json)
assert diagnostic_rows[2]["argv0"].endswith("libsiano-ts.so")
assert diagnostic_tables[2] == ["4 -> /dev/bus/usb/001/002"]
try:
    module.validate_descriptor_snapshot({1: []}, active_rows)
except module.EvidenceError:
    pass
else:
    raise AssertionError("idle descriptor snapshot without active owners was accepted")

def expect_bad_snapshot(processes):
    try:
        module.parse_diagnostic_snapshot("json=" + json.dumps({"schema": 1, "processes": processes}))
    except module.EvidenceError:
        return
    raise AssertionError("malformed diagnostic snapshot was accepted")

expect_bad_snapshot([])
expect_bad_snapshot([
    {"pid": 2, "ppid": 1, "argv0": "x", "fds": []},
    {"pid": 2, "ppid": 1, "argv0": "y", "fds": []},
])
expect_bad_snapshot([{"pid": 0, "ppid": 1, "argv0": "x", "fds": []}])
expect_bad_snapshot([{"pid": 2, "ppid": 1, "argv0": "x", "fds": [
    {"fd": 4, "target": "a"}, {"fd": 4, "target": "b"},
]}])
expect_bad_snapshot([{"pid": 2, "ppid": 1, "argv0": "x", "fds": [{"fd": -1, "target": "a"}]}])
expect_bad_snapshot([{"pid": 2, "ppid": 1, "argv0": "x", "fds": [{"fd": 4, "target": "x" * (module.MAX_DIAGNOSTIC_TARGET_LENGTH + 1)}]}])

concurrency_harness = module.Harness.__new__(module.Harness)
concurrency_harness.plan = {"concurrency": {"gr_path": "/gr", "satellite_path": "/sat", "minimum_bytes": 1880}}
concurrency_harness.args = type("Args", (), {"timeout": 1.0, "job_timeout": 1.0})()
concurrency_harness.ensure_service = lambda: None
concurrency_harness.base_url = lambda path: path
def concurrency_http(*args):
    time.sleep(0.05)
    return 200, bytes(clear), {}
concurrency_harness.http = concurrency_http
concurrency_harness.observe_job = lambda label: {
    "observed_monotonic_start": time.monotonic() - 100.0,
    "observed_monotonic_end": time.monotonic() + 100.0,
    "started": [1],
    "completed": True,
}
concurrency_harness.tracked_rows = lambda: {1: {"text": "1 0 libpx4d.so"}}
threads_before = {thread.ident for thread in threading.enumerate()}
concurrency_result = concurrency_harness.check_concurrency()
assert concurrency_result["common_overlap_seconds"] > 0
assert not [thread for thread in threading.enumerate() if thread.ident not in threads_before]
assert "trigger_update_schedules" in source

with tempfile.TemporaryDirectory(prefix="device-evidence-fd-test-") as temporary:
    harness = module.Harness.__new__(module.Harness)
    harness.out = __import__("pathlib").Path(temporary)
    harness.evidence = {}
    harness.tracked_rows = lambda: {123: {"pid": 123, "ppid": 1, "text": "123 1 dev.khronos31.mirakc"}}
    harness.adb_shell = lambda *args: (_ for _ in ()).throw(module.EvidenceError("permission denied"))
    try:
        harness.fd_tables("fixture")
    except module.EvidenceError as error:
        assert "fails closed" in str(error)
    else:
        raise AssertionError("unreadable /proc/<pid>/fd was accepted")

with tempfile.TemporaryDirectory(prefix="device-evidence-path-test-") as temporary:
    harness = module.Harness.__new__(module.Harness)
    harness.out = Path(temporary).resolve()
    harness.evidence = {}
    assert harness.write_bytes("nested/output.bin", b"ok") == "nested/output.bin"
    for unsafe in ("../escape", "/tmp/escape", "nested\\escape"):
        try:
            harness.write_bytes(unsafe, b"bad")
        except module.EvidenceError:
            pass
        else:
            raise AssertionError(f"unsafe evidence path was accepted: {unsafe}")

harness = module.Harness.__new__(module.Harness)
harness.serial = None
harness.adb = "adb"
harness.command = lambda *args: type("Result", (), {"stdout": "List of devices attached\nA device\nB device\n"})()
try:
    harness.select_serial()
except module.EvidenceError as error:
    assert "exactly one device" in str(error)
else:
    raise AssertionError("multiple adb devices were accepted without --serial")

print("device evidence host self-test: PASS")

verifier_path = path.replace("device-evidence.py", "verify-device-evidence.py")
verifier_spec = importlib.util.spec_from_file_location("verify_device_evidence", verifier_path)
assert verifier_spec and verifier_spec.loader
verifier = importlib.util.module_from_spec(verifier_spec)
verifier_spec.loader.exec_module(verifier)
with tempfile.TemporaryDirectory(prefix="device-evidence-manifest-test-") as temporary:
    root = Path(temporary)
    evidence = b"observed command output\n"
    (root / "commands").mkdir()
    (root / "commands/output.txt").write_bytes(evidence)
    evidence_hash = hashlib.sha256(evidence).hexdigest()
    receipt = {
        "schema": 1,
        "kind": "mirakc-device-evidence",
        "status": "pass",
        "candidate": {
            "apk_sha256": "a" * 64,
            "package": "dev.khronos31.mirakc",
            "version_name": "0.3.2",
            "version_code": "302",
            "certificate_sha256": "1fd02c94f29a5756ed1d560ac4ecb6813fa0b2e634473a6f31da210ffd5223c4",
        },
        "evidence_files": ["commands/output.txt"],
        "checks": {name: {"status": "pass"} for name in module.CHECKS},
    }
    receipt_bytes = (json.dumps(receipt, sort_keys=True) + "\n").encode()
    (root / "receipt.json").write_bytes(receipt_bytes)
    manifest = {
        "schema": 1,
        "kind": "mirakc-device-evidence-manifest",
        "candidate_apk_sha256": "a" * 64,
        "candidate": receipt["candidate"],
        "receipt": {"path": "receipt.json", "sha256": hashlib.sha256(receipt_bytes).hexdigest(), "size": len(receipt_bytes)},
        "evidence": {"commands/output.txt": {"sha256": evidence_hash, "size": len(evidence)}},
    }
    manifest_bytes = (json.dumps(manifest, sort_keys=True) + "\n").encode()
    (root / "receipt-manifest.json").write_bytes(manifest_bytes)
    (root / "receipt-manifest.sha256").write_text(hashlib.sha256(manifest_bytes).hexdigest() + "  receipt-manifest.json\n")
    verifier.verify(root)
    (root / "commands/link.txt").symlink_to(root / "commands/output.txt")
    try:
        verifier.verify(root)
    except verifier.VerificationError:
        pass
    else:
        raise AssertionError("symlink evidence was accepted")
    (root / "commands/link.txt").unlink()
    (root / "commands/output.txt").write_bytes(b"mutated\n")
    try:
        verifier.verify(root)
    except verifier.VerificationError:
        pass
    else:
        raise AssertionError("mutated evidence was accepted")
    (root / "commands/output.txt").write_bytes(evidence)
    receipt["status"] = "fail"
    receipt_bytes = (json.dumps(receipt, sort_keys=True) + "\n").encode()
    (root / "receipt.json").write_bytes(receipt_bytes)
    manifest["receipt"] = {"path": "receipt.json", "sha256": hashlib.sha256(receipt_bytes).hexdigest(), "size": len(receipt_bytes)}
    manifest_bytes = (json.dumps(manifest, sort_keys=True) + "\n").encode()
    (root / "receipt-manifest.json").write_bytes(manifest_bytes)
    (root / "receipt-manifest.sha256").write_text(hashlib.sha256(manifest_bytes).hexdigest() + "  receipt-manifest.json\n")
    try:
        verifier.verify(root)
    except verifier.VerificationError:
        pass
    else:
        raise AssertionError("failed receipt was accepted")
    assert verifier.main([str(root)]) == 1

print("device evidence manifest self-test: PASS")
PY
