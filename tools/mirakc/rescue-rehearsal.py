#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Rehearse same-key, forward-versioned legacy rescue on a staging device.

This intentionally performs only ``adb install -r`` and an exported activity
start. It never uninstalls, clears data, requests a downgrade, or stops a
package. On failure it writes a fail-closed receipt and leaves the device in
whatever package-manager state was actually reached; if the rescue install
was accepted, it is left installed.
"""

from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import math
import re
import stat
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath
from typing import Any

PACKAGE = "dev.khronos31.mirakc"
CERT_SHA256 = "1fd02c94f29a5756ed1d560ac4ecb6813fa0b2e634473a6f31da210ffd5223c4"
EXPECTED_RESCUE_ASSET = "assets/BUILD_INFO-legacy-server-rescue.txt"
EXPECTED_RESCUE_PROVENANCE = {
    "kind": "legacy-server-rescue",
    "package": PACKAGE,
    "version_name": "0.3.1",
    "version_code": "301",
    "legacy_dtv_ref": "mirakc-v0.2.0",
    "legacy_dtv_commit": "5a4d647c9e4b46f3f637165fa107f87d34ea22ed",
    "legacy_siano_ref": "v0.1.1",
    "legacy_siano_commit": "1a22a7180abd6c7be1d1dda6b866ec321a4e28ab",
    "not-a-normal-release": "true",
}
MAX_HTTP_BYTES = 4 * 1024 * 1024


class RehearsalError(RuntimeError):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def parse_badging(text: str) -> dict[str, str]:
    match = re.search(
        r"package:\s+name='([^']+)'\s+versionCode='([^']+)'\s+versionName='([^']+)'",
        text,
    )
    if match is None:
        raise RehearsalError("aapt2 returned no package/version metadata")
    return {"package": match.group(1), "version_code": match.group(2), "version_name": match.group(3)}


def parse_package_dump(text: str) -> dict[str, str]:
    version = re.search(r"versionCode=(\d+).*?versionName=([^\s]+)", text, re.DOTALL)
    first_install = re.search(r"firstInstallTime=([^\s]+)", text)
    if version is None or first_install is None:
        raise RehearsalError("dumpsys package lacks version or firstInstallTime")
    return {"version_code": version.group(1), "version_name": version.group(2), "first_install_time": first_install.group(1)}


def parse_cert(text: str) -> str:
    match = re.search(r"certificate SHA-256 digest:\s*([0-9A-Fa-f:]+)", text)
    if match is None:
        raise RehearsalError("apksigner returned no certificate digest")
    return match.group(1).replace(":", "").lower()


def parse_version(data: bytes) -> str:
    try:
        value = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise RehearsalError(f"/api/version is not JSON: {error}") from error
    observed = value.get("current") if isinstance(value, dict) else None
    if not isinstance(observed, str) or not observed:
        raise RehearsalError(f"/api/version has no current version: {value!r}")
    return observed


def parse_rescue_asset(data: bytes) -> dict[str, str]:
    try:
        lines = data.decode("utf-8").splitlines()
    except UnicodeDecodeError as error:
        raise RehearsalError("rescue BUILD_INFO asset is not UTF-8") from error
    result: dict[str, str] = {}
    for line in lines:
        if "=" not in line:
            raise RehearsalError(f"malformed rescue asset line: {line!r}")
        key, value = line.split("=", 1)
        if not key or key in result:
            raise RehearsalError("rescue asset has duplicate/empty key")
        result[key] = value
    if result != EXPECTED_RESCUE_PROVENANCE:
        raise RehearsalError(f"rescue provenance mismatch: {result!r}")
    return result


def verify_rescue_provenance(path: Path) -> dict[str, str]:
    if path.is_symlink() or not path.is_file():
        raise RehearsalError(f"rescue APK is not a regular file: {path.name}")
    try:
        archive = zipfile.ZipFile(path)
    except (OSError, zipfile.BadZipFile) as error:
        raise RehearsalError(f"rescue APK is not a readable ZIP: {error}") from error
    with archive:
        names: set[str] = set()
        for info in archive.infolist():
            posix = PurePosixPath(info.filename)
            if not info.filename or posix.is_absolute() or ".." in posix.parts or "\\" in info.filename:
                raise RehearsalError(f"rescue APK has unsafe ZIP path: {info.filename!r}")
            if info.filename in names:
                raise RehearsalError(f"rescue APK has duplicate ZIP path: {info.filename}")
            if stat.S_ISLNK((info.external_attr >> 16) & 0o170000):
                raise RehearsalError(f"rescue APK has symlink entry: {info.filename}")
            names.add(info.filename)
        if EXPECTED_RESCUE_ASSET not in names:
            raise RehearsalError(f"rescue APK is missing {EXPECTED_RESCUE_ASSET}")
        return parse_rescue_asset(archive.read(EXPECTED_RESCUE_ASSET))


def inspect_local_metadata(path: Path, expected_code: str, expected_name: str, aapt2: str, apksigner: str, provenance: dict[str, str] | None = None) -> dict[str, Any]:
    if path.is_symlink() or not path.is_file():
        raise RehearsalError(f"APK is not a regular file: {path.name}")
    result = subprocess.run([aapt2, "dump", "badging", str(path)], capture_output=True, text=True, check=True)
    metadata = parse_badging(result.stdout)
    expected = {"package": PACKAGE, "version_code": expected_code, "version_name": expected_name}
    if metadata != expected:
        raise RehearsalError(f"local APK metadata mismatch for {path.name}: {metadata}")
    cert = subprocess.run([apksigner, "verify", "--print-certs", str(path)], capture_output=True, text=True, check=True)
    cert_sha = parse_cert(cert.stdout)
    if cert_sha != CERT_SHA256:
        raise RehearsalError(f"local APK certificate mismatch for {path.name}: {cert_sha}")
    result_metadata: dict[str, Any] = {
        **metadata, "certificate_sha256": cert_sha, "apk_sha256": sha256(path), "apk_basename": path.name,
    }
    if provenance is not None:
        result_metadata["provenance"] = provenance
    return result_metadata


def safe_route(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value.startswith("/"):
        raise RehearsalError(f"{field} must be an absolute HTTP route")
    parsed = PurePosixPath(value)
    if ".." in parsed.parts or "\\" in value or value.startswith("//"):
        raise RehearsalError(f"{field} contains an unsafe path")
    return value


def ts_summary(data: bytes) -> dict[str, int]:
    if len(data) < 1880 or len(data) % 188:
        raise RehearsalError(f"stream is not at least ten aligned TS packets: {len(data)} bytes")
    packets = len(data) // 188
    if any(data[offset] != 0x47 for offset in range(0, len(data), 188)):
        raise RehearsalError("stream TS sync-byte check failed")
    scrambled = sum(1 for offset in range(0, len(data), 188) if ((data[offset + 3] >> 6) & 0x03))
    if scrambled:
        raise RehearsalError(f"stream remains scrambled: {scrambled} packets")
    return {"bytes": len(data), "packets": packets, "scrambled_packets": scrambled}


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


class Rehearsal:
    def __init__(self, args: argparse.Namespace, plan: dict[str, Any]):
        self.args = args
        self.plan = plan
        self.forwarded = False
        self.out = Path(args.out).resolve()
        if self.out.is_symlink() or not self.out.is_dir():
            raise RehearsalError(f"output must be an existing real directory: {self.out}")
        if any(self.out.iterdir()):
            raise RehearsalError(f"output directory is not empty: {self.out}")
        self.evidence_dir = self.out / "evidence"
        self.evidence_dir.mkdir()
        self.candidate_installed = False
        self.rescue_installed = False
        self.phase = "initialized"
        self.install_records: list[dict[str, Any]] = []
        self.ready_records: list[dict[str, Any]] = []
        self.cleanup_error: str | None = None
        self.device_build = self.read_device_build()
        self.candidate_meta = self.local_metadata(Path(args.candidate).resolve(), "300", "0.3.0")
        self.rescue_provenance = verify_rescue_provenance(Path(args.rescue).resolve())
        self.rescue_meta = self.local_metadata(Path(args.rescue).resolve(), "301", "0.3.1")

    def adb(self, *parts: str, check: bool = True, timeout: float | None = None) -> subprocess.CompletedProcess[str]:
        result = subprocess.run(
            [self.args.adb, "-s", self.args.serial, *parts],
            capture_output=True, text=True, timeout=timeout or self.args.adb_timeout, check=False,
        )
        if check and result.returncode != 0:
            raise RehearsalError(f"adb {' '.join(parts)} failed: {result.stderr.strip()}")
        return result

    def evidence_text(self, name: str, text: str) -> None:
        relative = PurePosixPath(name)
        if relative.is_absolute() or ".." in relative.parts or "\\" in name or not name:
            raise RehearsalError(f"unsafe evidence path: {name!r}")
        path = self.evidence_dir.joinpath(*relative.parts)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def http(self, label: str, route: str, max_bytes: int | None = None) -> bytes:
        url = f"http://127.0.0.1:{self.args.host_port}{route}"
        try:
            with urllib.request.urlopen(url, timeout=self.args.http_timeout) as response:
                body = response.read(max_bytes or MAX_HTTP_BYTES)
                status = response.status
                headers = dict(response.headers.items())
        except (OSError, urllib.error.URLError, urllib.error.HTTPError) as error:
            raise RehearsalError(f"{label} HTTP request failed: {error}") from error
        if status < 200 or status >= 300:
            raise RehearsalError(f"{label} HTTP status was {status}")
        self.evidence_text(f"http/{label}.json", json.dumps({"url": url, "status": status, "headers": headers}, sort_keys=True) + "\n")
        self.evidence_dir.joinpath("http", f"{label}.body").write_bytes(body)
        return body

    def forward(self) -> None:
        self.adb("forward", "--no-rebind", f"tcp:{self.args.host_port}", f"tcp:{self.args.device_port}")
        self.forwarded = True

    def remove_forward(self) -> None:
        if self.forwarded:
            try:
                result = self.adb("forward", "--remove", f"tcp:{self.args.host_port}", check=False)
            except subprocess.TimeoutExpired as error:
                self.evidence_text(
                    "forward/remove.json",
                    json.dumps({"returncode": None, "timed_out": True}, sort_keys=True) + "\n",
                )
                self.forwarded = False
                raise RehearsalError("adb forward cleanup timed out") from error
            self.forwarded = False
            self.evidence_text("forward/remove.stdout.txt", result.stdout)
            self.evidence_text("forward/remove.stderr.txt", result.stderr)
            self.evidence_text(
                "forward/remove.json",
                json.dumps({"returncode": result.returncode, "timed_out": False}, sort_keys=True) + "\n",
            )
            if result.returncode != 0:
                raise RehearsalError(f"adb forward cleanup failed with returncode {result.returncode}")

    def package_dump(self, label: str) -> dict[str, str]:
        result = self.adb("shell", "dumpsys", "package", PACKAGE)
        self.evidence_text(f"package/{label}.txt", result.stdout)
        return parse_package_dump(result.stdout)

    def capture_failure_state(self) -> dict[str, Any]:
        try:
            result = self.adb("shell", "dumpsys", "package", PACKAGE, check=False)
            state = {"returncode": result.returncode, "stdout_file": "package/failure.txt"}
            self.evidence_text("package/failure.txt", result.stdout)
            self.evidence_text("package/failure.stderr.txt", result.stderr)
            return state
        except (OSError, subprocess.TimeoutExpired, RehearsalError) as error:
            return {"error": str(error)}

    def start_and_wait(self, label: str, expected_version: str | None = None) -> dict[str, Any]:
        result = self.adb(
            "shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity",
            check=False, timeout=self.args.start_timeout,
        )
        self.evidence_text(f"start/{label}.stdout.txt", result.stdout)
        self.evidence_text(f"start/{label}.stderr.txt", result.stderr)
        if result.returncode != 0:
            raise RehearsalError(f"{label} MainActivity start failed with returncode {result.returncode}")
        deadline = time.monotonic() + self.args.ready_timeout
        last_error = "no response"
        while time.monotonic() < deadline:
            try:
                version = parse_version(self.http(f"ready-{label}-version", "/api/version", 65536))
                services_body = self.http(f"ready-{label}-services", "/api/services", MAX_HTTP_BYTES)
                services = json.loads(services_body.decode("utf-8"))
                if not isinstance(services, list) or not services:
                    raise RehearsalError("/api/services is empty or has wrong shape")
                if expected_version is not None and version != expected_version:
                    raise RehearsalError(f"expected /api/version {expected_version}, observed {version}")
                ready = {"phase": label, "version": version, "service_count": len(services)}
                self.ready_records.append(ready)
                return ready
            except (OSError, RehearsalError, UnicodeDecodeError, json.JSONDecodeError) as error:
                last_error = str(error)
                time.sleep(min(0.5, max(0.05, deadline - time.monotonic())))
        raise RehearsalError(f"{label} readiness timed out: {last_error}")

    def read_device_build(self) -> dict[str, str]:
        result = self.adb("shell", "getprop")
        properties: dict[str, str] = {}
        for line in result.stdout.splitlines():
            match = re.fullmatch(r"\[([^]]+)\]: \[([^]]*)\]", line.strip())
            if match is not None:
                properties[match.group(1)] = match.group(2)
        required = ("ro.build.type", "ro.build.id", "ro.build.version.release", "ro.product.device")
        if any(not properties.get(key) for key in required):
            raise RehearsalError("adb getprop did not expose the required device build identity")
        if properties["ro.build.type"] != "user":
            raise RehearsalError(f"staging device is not a production user build: {properties['ro.build.type']!r}")
        build = {key: properties[key] for key in required}
        self.evidence_text("device/build.json", json.dumps(build, sort_keys=True) + "\n")
        return build

    def verify_installed_legacy(self) -> dict[str, str]:
        result = self.adb("shell", "pm", "path", PACKAGE)
        paths = [line.removeprefix("package:").strip() for line in result.stdout.splitlines() if line.startswith("package:")]
        if len(paths) != 1 or not paths[0].startswith("/"):
            raise RehearsalError(f"expected one installed base APK, got {paths}")
        with tempfile.TemporaryDirectory(prefix="mirakc-installed-") as temporary:
            local = Path(temporary) / "base.apk"
            self.adb("pull", paths[0], str(local))
            cert = subprocess.run([self.args.apksigner, "verify", "--print-certs", str(local)], capture_output=True, text=True, check=True)
            cert_sha = parse_cert(cert.stdout)
            if cert_sha != CERT_SHA256:
                raise RehearsalError(f"installed legacy certificate mismatch: {cert_sha}")
            metadata = parse_badging(subprocess.run([self.args.aapt2, "dump", "badging", str(local)], capture_output=True, text=True, check=True).stdout)
            if metadata != {"package": PACKAGE, "version_code": "200", "version_name": "0.2.0"}:
                raise RehearsalError(f"installed legacy APK metadata mismatch: {metadata}")
            result = {
                "installed_apk_path": paths[0], "installed_apk_sha256": sha256(local),
                "certificate_sha256": cert_sha,
            }
        self.evidence_text("package/installed-base.txt", json.dumps(result, sort_keys=True) + "\n")
        return result

    def local_metadata(self, path: Path, expected_code: str, expected_name: str) -> dict[str, Any]:
        provenance = self.rescue_provenance if expected_code == "301" else None
        return inspect_local_metadata(path, expected_code, expected_name, self.args.aapt2, self.args.apksigner, provenance)

    def install(self, label: str, apk: Path) -> dict[str, str]:
        apk_digest = sha256(apk)
        command = f"adb -s {self.args.serial} install -r {apk_digest}/{apk.name}"
        try:
            result = self.adb("install", "-r", str(apk), check=False, timeout=self.args.install_timeout)
        except subprocess.TimeoutExpired as error:
            record = {"phase": label, "command": command, "apk_sha256": apk_digest, "basename": apk.name, "returncode": None, "timed_out": True}
            self.install_records.append(record)
            self.evidence_text(f"install/{label}.json", json.dumps(record, sort_keys=True) + "\n")
            raise RehearsalError(f"{label} install timed out") from error
        record = {"phase": label, "command": command, "apk_sha256": apk_digest, "basename": apk.name, "returncode": result.returncode, "timed_out": False}
        self.install_records.append(record)
        self.evidence_text(f"install/{label}.json", json.dumps(record, sort_keys=True) + "\n")
        self.evidence_text(f"install/{label}.stdout.txt", result.stdout)
        self.evidence_text(f"install/{label}.stderr.txt", result.stderr)
        if result.returncode != 0:
            raise RehearsalError(f"{label} install failed with returncode {result.returncode}")
        return self.package_dump(f"after-{label}")

    def snapshot(self, label: str) -> dict[str, Any]:
        services_body = self.http(f"{label}-services", safe_route(self.plan["services_path"], "services_path"))
        try:
            services = json.loads(services_body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise RehearsalError(f"{label} /api/services is not JSON") from error
        if not isinstance(services, list) or not services:
            raise RehearsalError(f"{label} /api/services is empty or has wrong shape")
        stream_route = safe_route(self.plan["stream_path"], "stream_path")
        stream_body = self.http(f"{label}-stream", stream_route, self.args.max_bytes)
        summary = ts_summary(stream_body)
        return {"services": services, "services_canonical": canonical_json(services), "stream": summary}

    def run(self) -> dict[str, Any]:
        candidate = Path(self.args.candidate).resolve()
        rescue = Path(self.args.rescue).resolve()
        candidate_meta = self.candidate_meta
        rescue_meta = self.rescue_meta
        self.phase = "legacy-precondition"
        installed_legacy = self.verify_installed_legacy()
        initial = self.package_dump("before")
        if initial["version_code"] != "200" or initial["version_name"] != "0.2.0":
            raise RehearsalError(f"staging device is not the expected legacy install: {initial}")
        initial_first_install = initial["first_install_time"]
        self.forward()
        self.phase = "legacy-start"
        legacy_ready = self.start_and_wait("legacy", "3.4.82")
        before = self.snapshot("before")
        self.evidence_text("snapshot-before.json", json.dumps(before, ensure_ascii=False, sort_keys=True, indent=2) + "\n")
        self.phase = "candidate-install"
        candidate_after = self.install("candidate", candidate)
        self.candidate_installed = True
        if candidate_after["version_code"] != "300" or candidate_after["first_install_time"] != initial_first_install:
            raise RehearsalError(f"candidate install changed unexpected package state: {candidate_after}")
        self.phase = "candidate-start"
        candidate_ready = self.start_and_wait("candidate", "3.4.86")
        candidate_snapshot = self.snapshot("candidate")
        self.evidence_text("snapshot-candidate.json", json.dumps(candidate_snapshot, ensure_ascii=False, sort_keys=True, indent=2) + "\n")
        try:
            self.phase = "rescue-install"
            rescue_after = self.install("rescue", rescue)
        except RehearsalError as error:
            # A package-manager transport failure may happen after the
            # package was committed. Retry the same non-destructive install so
            # a failed rehearsal does not strand the staging device on 0.3.0.
            try:
                self.phase = "rescue-install-retry"
                rescue_after = self.install("rescue-retry", rescue)
            except RehearsalError as retry_error:
                raise RehearsalError(f"rescue install failed and retry failed: {error}; {retry_error}") from error
            self.rescue_installed = True
            raise RehearsalError(f"rescue install required a retry: {error}") from error
        self.rescue_installed = True
        if rescue_after["version_code"] != "301" or rescue_after["version_name"] != "0.3.1":
            raise RehearsalError(f"rescue install did not become active: {rescue_after}")
        if rescue_after["first_install_time"] != initial_first_install:
            raise RehearsalError("rescue install changed firstInstallTime/data continuity")
        self.phase = "rescue-start"
        rescue_ready = self.start_and_wait("rescue", "3.4.82")
        after = self.snapshot("after-rescue")
        if after["services_canonical"] != before["services_canonical"]:
            raise RehearsalError("legacy /api/services snapshot was not restored exactly")
        self.evidence_text("snapshot-after-rescue.json", json.dumps(after, ensure_ascii=False, sort_keys=True, indent=2) + "\n")
        return {
            "schema": 1,
            "kind": "mirakc-rescue-rehearsal",
            "status": "pass",
            "installed_legacy": installed_legacy,
            "initial_package": initial,
            "candidate": candidate_meta,
            "rescue": rescue_meta,
            "device": {
                "serial": self.args.serial,
                "first_install_time": initial_first_install,
                "build": self.device_build,
            },
            "before": before,
            "candidate_after": candidate_after,
            "candidate_snapshot": candidate_snapshot,
            "after_rescue": after,
            "final_package": rescue_after,
            "legacy_ready": legacy_ready,
            "candidate_ready": candidate_ready,
            "rescue_ready": rescue_ready,
            "ready_records": self.ready_records,
            "install_records": self.install_records,
            "rescue_provenance": self.rescue_provenance,
            "phase": "complete",
            "checks": {
                "legacy_precondition": "pass",
                "candidate_install_r": "pass",
                "rescue_install_r": "pass",
                "data_continuity": "pass",
                "legacy_services_restored": "pass",
                "s1ud_stream_integrity": "pass",
            },
        }


def select_serial(adb: str) -> str:
    result = subprocess.run([adb, "devices"], capture_output=True, text=True, check=True)
    devices = [line.split()[0] for line in result.stdout.splitlines()[1:] if len(line.split()) >= 2 and line.split()[1] == "device"]
    if len(devices) != 1:
        raise RehearsalError(f"exactly one online adb device is required, found {devices}")
    return devices[0]


def validate_args(args: argparse.Namespace) -> None:
    for name in ("adb_timeout", "install_timeout", "start_timeout", "http_timeout", "ready_timeout"):
        value = getattr(args, name)
        if not math.isfinite(value) or value <= 0:
            raise RehearsalError(f"--{name.replace('_', '-')} must be finite and positive")
    for name in ("host_port", "device_port"):
        value = getattr(args, name)
        if value < 1 or value > 65535:
            raise RehearsalError(f"--{name.replace('_', '-')} must be between 1 and 65535")
    if args.max_bytes < 1880 or args.max_bytes % 188:
        raise RehearsalError("--max-bytes must be at least 1880 and divisible by 188")


def write_receipt(out: Path, receipt: dict[str, Any]) -> None:
    receipt_path = out / "receipt.json"
    manifest_path = out / "receipt-manifest.json"
    receipt_path.write_text(json.dumps(receipt, ensure_ascii=False, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    evidence_dir = out / "evidence"
    evidence_dir.mkdir(parents=True, exist_ok=True)
    evidence: dict[str, dict[str, Any]] = {}
    for path in sorted(evidence_dir.rglob("*")):
        if path.is_file():
            relative = path.relative_to(out).as_posix()
            evidence[relative] = {"sha256": sha256(path), "size": path.stat().st_size}
    receipt["evidence_files"] = sorted(evidence)
    receipt_path.write_text(json.dumps(receipt, ensure_ascii=False, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    manifest = {
        "schema": 1,
        "kind": "mirakc-rescue-rehearsal-manifest",
        "receipt": {"sha256": sha256(receipt_path), "size": receipt_path.stat().st_size},
        "candidate": receipt.get("candidate"),
        "rescue": receipt.get("rescue"),
        "evidence": evidence,
    }
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    (out / "receipt-manifest.sha256").write_text(f"{sha256(manifest_path)}  receipt-manifest.json\n", encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", required=True)
    parser.add_argument("--rescue", required=True)
    parser.add_argument("--plan", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--serial")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--aapt2", required=True)
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--host-port", type=int, default=40773)
    parser.add_argument("--device-port", type=int, default=40772)
    parser.add_argument("--max-bytes", type=int, default=1880)
    parser.add_argument("--adb-timeout", type=float, default=30.0)
    parser.add_argument("--install-timeout", type=float, default=120.0)
    parser.add_argument("--start-timeout", type=float, default=30.0)
    parser.add_argument("--http-timeout", type=float, default=10.0)
    parser.add_argument("--ready-timeout", type=float, default=960.0)
    args = parser.parse_args(argv)
    out = Path(args.out).resolve()
    started_at = datetime.datetime.now(datetime.timezone.utc).isoformat()
    if out.is_symlink() or (out.exists() and not out.is_dir()):
        print(f"rescue-rehearsal: output is not a real directory: {out}", file=sys.stderr)
        return 1
    if out.exists() and any(out.iterdir()):
        print(f"rescue-rehearsal: output directory is not empty: {out}", file=sys.stderr)
        return 1
    out.mkdir(parents=True, exist_ok=True)
    rehearsal: Rehearsal | None = None
    try:
        validate_args(args)
        plan = json.loads(Path(args.plan).read_text(encoding="utf-8"))
        if not isinstance(plan, dict) or "services_path" not in plan or "stream_path" not in plan:
            raise RehearsalError("plan must contain services_path and stream_path")
        candidate_path = Path(args.candidate).resolve()
        rescue_path = Path(args.rescue).resolve()
        inspect_local_metadata(candidate_path, "300", "0.3.0", args.aapt2, args.apksigner)
        rescue_provenance = verify_rescue_provenance(rescue_path)
        inspect_local_metadata(rescue_path, "301", "0.3.1", args.aapt2, args.apksigner, rescue_provenance)
        args.serial = args.serial or select_serial(args.adb)
        rehearsal = Rehearsal(args, plan)
        run_error: Exception | None = None
        result: dict[str, Any] | None = None
        try:
            result = rehearsal.run()
        except (OSError, RehearsalError, json.JSONDecodeError, subprocess.CalledProcessError, subprocess.TimeoutExpired, ValueError, KeyError, TypeError) as error:
            run_error = error
        try:
            rehearsal.remove_forward()
        except (OSError, RehearsalError, subprocess.TimeoutExpired) as cleanup_error:
            rehearsal.cleanup_error = str(cleanup_error)
            if run_error is None:
                run_error = cleanup_error
        if run_error is not None:
            raise run_error
        assert result is not None
        result["started_at"] = started_at
        result["finished_at"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        write_receipt(out, result)
        print(json.dumps(result, ensure_ascii=False, sort_keys=True))
        return 0
    except (OSError, RehearsalError, json.JSONDecodeError, subprocess.CalledProcessError, subprocess.TimeoutExpired, ValueError) as error:
        out.mkdir(parents=True, exist_ok=True)
        failure_state = rehearsal.capture_failure_state() if rehearsal is not None else None
        failed = {
            "schema": 1,
            "kind": "mirakc-rescue-rehearsal",
            "status": "fail",
            "error": str(error),
            "checks": {},
            "candidate": rehearsal.candidate_meta if rehearsal is not None else None,
            "rescue": rehearsal.rescue_meta if rehearsal is not None else None,
            "rescue_provenance": rehearsal.rescue_provenance if rehearsal is not None else None,
            "install_records": rehearsal.install_records if rehearsal is not None else [],
            "ready_records": rehearsal.ready_records if rehearsal is not None else [],
            "failure_state": failure_state,
            "phase": rehearsal.phase if rehearsal is not None else "preflight",
            "cleanup_error": rehearsal.cleanup_error if rehearsal is not None else None,
            "started_at": started_at,
            "finished_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        }
        write_receipt(out, failed)
        print(f"rescue-rehearsal: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
