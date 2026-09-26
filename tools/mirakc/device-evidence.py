#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Fail-closed, candidate-bound evidence harness for a real mirakc APK.

This tool deliberately does not invent channel IDs, process names, or USB
descriptors. The plan supplies observed mirakc service stream inputs; the
EPGStation routes and response shapes are fixed to the pinned v2.10.0 source.
Every result below is obtained from the candidate/device.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath
from typing import Any

PACKAGE = "dev.khronos31.mirakc"
EPGSTATION_PACKAGE = "dev.khronos31.epgstation.server"
MIRAKC_VERSION = "3.4.86"
EXPECTED_PACKAGE_VERSION = "0.3.2"
EXPECTED_PACKAGE_VERSION_CODE = "302"
EXPECTED_CERT_SHA256 = "1fd02c94f29a5756ed1d560ac4ecb6813fa0b2e634473a6f31da210ffd5223c4"
COLLECT_EITS_EXECUTABLE = "libmirakc-arib.so"
EPGSTATION_DEVICE_PORT = 8888
DIAGNOSTIC_URI = "content://dev.khronos31.mirakc.diagnostics/fds"
DIAGNOSTIC_TRIGGER_METHOD = "trigger_update_schedules"
EPGSTATION_REQUIRED_LOG_MARKERS = (
    "event stream started",
    "done update channel",
    "done update programs",
)
DEFAULT_MAX_BYTES = (32 * 1024 * 1024 // 188) * 188
MAX_DIAGNOSTIC_PROCESSES = 128
MAX_DIAGNOSTIC_FDS = 256
MAX_DIAGNOSTIC_TARGET_LENGTH = 1024
NATIVE_NAMES = (
    "libmirakc.so", "libmirakc-arib.so", "libsiano-ts.so", "libpx4d.so",
    "libpx4-ts.so", "libpx4ctl.so", "libusb_process.so",
    "libmirakc-siano-adapter.so", "libmirakc-b25-filter.so",
    "libmirakc-px4-adapter.so", "libmirakc-px4-fwtool.so",
)
CHECKS = (
    "candidate_binding", "service_api", "ten_cycles", "descriptor_ownership",
    "zero_orphan", "siano_12seg", "q3u4_eight_tuner", "bs_cs_integrity",
    "concurrency_single_px4d", "epgstation_scan_schedule_live",
    "size_metrics", "rss_metrics", "usb_detach_reconnect",
)


class EvidenceError(RuntimeError):
    pass


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def iso_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def parse_package_badging(text: str) -> dict[str, str]:
    match = re.search(
        r"package:\s+name='([^']+)'\s+versionCode='([^']+)'\s+versionName='([^']+)'",
        text,
    )
    if not match:
        raise EvidenceError("aapt2 output has no package/version fields")
    return {"package": match.group(1), "version_code": match.group(2), "version_name": match.group(3)}


def parse_cert_digest(text: str) -> str:
    match = re.search(r"certificate SHA-256 digest:\s*([0-9A-Fa-f:]+)", text)
    if not match:
        raise EvidenceError("apksigner output has no SHA-256 certificate digest")
    return match.group(1).replace(":", "").lower()


def parse_int(value: Any) -> int:
    if isinstance(value, int) and not isinstance(value, bool):
        return value
    if isinstance(value, str):
        return int(value, 0)
    raise ValueError(f"not an integer: {value!r}")


def parse_processes(text: str) -> dict[int, dict[str, Any]]:
    """Parse Android ps output without assuming a NAME/ARGS column layout."""
    result: dict[int, dict[str, Any]] = {}
    for line in text.splitlines():
        fields = line.split()
        if len(fields) < 3 or not fields[0].isdigit() or not fields[1].isdigit():
            continue
        pid, ppid = int(fields[0]), int(fields[1])
        if pid <= 0 or pid in result:
            raise EvidenceError(f"process listing contains invalid or duplicate PID: {pid}")
        result[pid] = {
            "pid": pid,
            "ppid": ppid,
            "argv0": fields[2],
            "args": fields[2:],
            "text": line,
        }
    return result


def is_collect_eits_process(row: dict[str, Any]) -> bool:
    """Accept only the real mirakc-arib collect-eits child, never a job label."""
    argv0 = row.get("argv0")
    args = row.get("args")
    return (
        isinstance(argv0, str)
        and PurePosixPath(argv0).name == COLLECT_EITS_EXECUTABLE
        and isinstance(args, list)
        and all(isinstance(value, str) for value in args)
        and "collect-eits" in args[1:]
    )


def parse_meminfo_pss(text: str) -> int:
    match = re.search(r"^\s*TOTAL\s+PSS:\s*([\d,]+)", text, re.MULTILINE)
    if match:
        return int(match.group(1).replace(",", ""))
    match = re.search(r"^\s*TOTAL\s+([\d,]+)\s+kB", text, re.MULTILINE)
    if not match:
        raise EvidenceError("dumpsys meminfo has no TOTAL PSS line")
    return int(match.group(1).replace(",", ""))


def ts_summary(data: bytes, pids: list[int] | None = None) -> dict[str, Any]:
    if len(data) < 188 * 10:
        raise EvidenceError(f"TS capture is too short: {len(data)} bytes")
    if len(data) % 188:
        raise EvidenceError(f"TS capture is not packet-aligned: {len(data)} bytes")
    packet_count = len(data) // 188
    usable = data[: packet_count * 188]
    if any(usable[offset] != 0x47 for offset in range(0, len(usable), 188)):
        raise EvidenceError("TS sync-byte integrity check failed")
    counts: dict[int, dict[str, int]] = {}
    for offset in range(0, len(usable), 188):
        packet = usable[offset : offset + 188]
        pid = ((packet[1] & 0x1F) << 8) | packet[2]
        scrambling = (packet[3] >> 6) & 0x03
        entry = counts.setdefault(pid, {"packets": 0, "scrambled": 0})
        entry["packets"] += 1
        entry["scrambled"] += int(scrambling != 0)
    selected = pids or sorted(counts)
    missing = [pid for pid in selected if pid not in counts]
    if missing:
        raise EvidenceError(f"requested TS PIDs are absent: {missing}")
    dirty = {str(pid): counts[pid] for pid in selected if counts[pid]["scrambled"]}
    if dirty:
        raise EvidenceError(f"selected TS PIDs remain scrambled: {dirty}")
    return {"packets": packet_count, "pids": counts, "selected_pids": selected}


def parse_epg_channels(value: Any) -> list[dict[str, Any]]:
    """Validate EPGStation v2.10.0 ChannelItems, not an arbitrary JSON list."""
    if not isinstance(value, list) or not value:
        raise EvidenceError("EPGStation /api/channels must be a non-empty array")
    required = ("id", "serviceId", "networkId", "name", "halfWidthName", "hasLogoData", "channel", "channelType")
    result = []
    for item in value:
        if not isinstance(item, dict) or any(key not in item for key in required):
            raise EvidenceError("EPGStation channel item has the wrong shape")
        try:
            channel_id = parse_int(item["id"])
            parse_int(item["serviceId"])
            parse_int(item["networkId"])
        except (TypeError, ValueError) as error:
            raise EvidenceError("EPGStation channel identifiers are not integers") from error
        if not isinstance(item["name"], str) or not item["name"] or not isinstance(item["halfWidthName"], str):
            raise EvidenceError("EPGStation channel name is not non-empty")
        if not isinstance(item["hasLogoData"], bool):
            raise EvidenceError("EPGStation channel hasLogoData is not boolean")
        if not isinstance(item["channel"], str) or not item["channel"]:
            raise EvidenceError("EPGStation channel number is not non-empty")
        if item["channelType"] not in {"GR", "BS", "CS", "SKY"}:
            raise EvidenceError("EPGStation channelType is not an upstream type")
        result.append({**item, "id": channel_id})
    return result


def parse_epg_schedules(value: Any, now_ms: int) -> list[dict[str, Any]]:
    """Validate /api/schedules and require a program covering now."""
    if not isinstance(value, list) or not value:
        raise EvidenceError("EPGStation /api/schedules must be a non-empty array")
    current = []
    for schedule in value:
        if not isinstance(schedule, dict) or not isinstance(schedule.get("channel"), dict):
            raise EvidenceError("EPGStation schedule item has no channel object")
        channel = schedule["channel"]
        schedule_channel_required = ("id", "serviceId", "networkId", "name", "channelType", "hasLogoData")
        if any(key not in channel for key in schedule_channel_required):
            raise EvidenceError("EPGStation schedule channel is missing required fields")
        if (
            not isinstance(channel.get("id"), int)
            or isinstance(channel.get("id"), bool)
            or not isinstance(channel.get("serviceId"), int)
            or isinstance(channel.get("serviceId"), bool)
            or not isinstance(channel.get("networkId"), int)
            or isinstance(channel.get("networkId"), bool)
            or not isinstance(channel.get("name"), str)
            or channel.get("channelType") not in {"GR", "BS", "CS", "SKY"}
            or not isinstance(channel.get("hasLogoData"), bool)
        ):
            raise EvidenceError("EPGStation schedule channel has the wrong shape")
        programs = schedule.get("programs")
        if not isinstance(programs, list):
            raise EvidenceError("EPGStation schedule item has no programs array")
        for program in programs:
            if not isinstance(program, dict):
                raise EvidenceError("EPGStation schedule program is not an object")
            required = ("id", "channelId", "startAt", "endAt", "isFree", "name")
            if any(key not in program for key in required):
                raise EvidenceError("EPGStation schedule program has the wrong shape")
            try:
                start_at = parse_int(program["startAt"])
                end_at = parse_int(program["endAt"])
                parse_int(program["id"])
                channel_id = parse_int(program["channelId"])
            except (TypeError, ValueError) as error:
                raise EvidenceError("EPGStation schedule timestamps/identifiers are invalid") from error
            if (
                end_at <= start_at
                or not isinstance(program["name"], str)
                or not program["name"]
                or not isinstance(program.get("isFree"), bool)
            ):
                raise EvidenceError("EPGStation schedule program has invalid interval/name")
            if start_at <= now_ms <= end_at:
                current.append({"channel_id": channel_id, "program_id": parse_int(program["id"]), "start_at": start_at, "end_at": end_at})
    if not current:
        raise EvidenceError("EPGStation /api/schedules has no program covering the current time")
    return current


def parse_epg_streams(value: Any) -> list[dict[str, Any]]:
    """Validate v2.10.0 StreamInfo ({items: [...]}) returned by /api/streams."""
    if not isinstance(value, dict) or not isinstance(value.get("items"), list):
        raise EvidenceError("EPGStation /api/streams must be an object with items")
    items = value["items"]
    for item in items:
        if not isinstance(item, dict):
            raise EvidenceError("EPGStation stream item is not an object")
        required = ("streamId", "type", "mode", "isEnable", "channelId", "name", "startAt", "endAt")
        if any(key not in item for key in required):
            raise EvidenceError("EPGStation stream item has the wrong shape")
        if item["type"] not in {"LiveStream", "LiveHLS", "RecordedStream", "RecordedHLS"}:
            raise EvidenceError("EPGStation stream type is invalid")
        try:
            for key in ("streamId", "mode", "channelId", "startAt", "endAt"):
                parse_int(item[key])
        except (TypeError, ValueError) as error:
            raise EvidenceError("EPGStation stream identifiers/timestamps are invalid") from error
        if not isinstance(item["isEnable"], bool) or not isinstance(item["name"], str):
            raise EvidenceError("EPGStation stream isEnable is not boolean")
    return items


def require_no_epg_streams(value: Any) -> None:
    if parse_epg_streams(value):
        raise EvidenceError("EPGStation already has active streams; refusing to affect another user")


def parse_logcat_epoch(text: str, minimum_epoch: float, markers: tuple[str, ...] = EPGSTATION_REQUIRED_LOG_MARKERS) -> dict[str, list[str]]:
    """Return marker lines at/after the start boundary from `logcat -v epoch`."""
    found: dict[str, list[str]] = {marker: [] for marker in markers}
    for line in text.splitlines():
        match = re.match(r"\s*(\d+(?:\.\d+)?)\s+.*", line)
        if not match or float(match.group(1)) < minimum_epoch or "EPGStationServer" not in line:
            continue
        for marker in markers:
            if marker in line:
                found[marker].append(line)
    missing = [marker for marker, lines in found.items() if not lines]
    if missing:
        raise EvidenceError(f"EPGStation logcat is missing post-start markers: {missing}")
    return found


def parse_device_epoch(text: str) -> float:
    value = text.strip()
    if not re.fullmatch(r"\d+(?:\.\d+)?", value):
        raise EvidenceError(f"device date did not return a numeric epoch: {value!r}")
    return float(value)


def parse_diagnostic_snapshot(text: str) -> tuple[dict[int, dict[str, Any]], dict[int, list[str]]]:
    marker = "json="
    position = text.find(marker)
    if position < 0:
        raise EvidenceError("diagnostic provider returned no JSON column")
    try:
        value = json.loads(text[position + len(marker):].strip())
    except json.JSONDecodeError as error:
        raise EvidenceError(f"diagnostic provider JSON is invalid: {error}") from error
    if not isinstance(value, dict) or value.get("schema") != 1 or not isinstance(value.get("processes"), list):
        raise EvidenceError("diagnostic provider schema mismatch")
    process_items = value["processes"]
    if not process_items or len(process_items) > MAX_DIAGNOSTIC_PROCESSES:
        raise EvidenceError("diagnostic process set is empty or exceeds bound")
    rows: dict[int, dict[str, Any]] = {}
    tables: dict[int, list[str]] = {}
    for item in process_items:
        if (
            not isinstance(item, dict)
            or type(item.get("pid")) is not int
            or type(item.get("ppid")) is not int
        ):
            raise EvidenceError("diagnostic process record is malformed")
        pid = item["pid"]
        ppid = item["ppid"]
        if pid <= 0 or ppid < 0 or pid in rows:
            raise EvidenceError("diagnostic process PID is invalid or duplicated")
        argv0 = item.get("argv0")
        fds = item.get("fds")
        if not isinstance(argv0, str) or not isinstance(fds, list):
            raise EvidenceError("diagnostic process record has invalid argv0/fds")
        if len(fds) > MAX_DIAGNOSTIC_FDS:
            raise EvidenceError("diagnostic fd set exceeds bound")
        rows[pid] = {"pid": pid, "ppid": ppid, "argv0": argv0, "text": f"{pid} {ppid} {argv0}"}
        tables[pid] = []
        seen_fds: set[int] = set()
        for fd in fds:
            if (
                not isinstance(fd, dict)
                or type(fd.get("fd")) is not int
                or not isinstance(fd.get("target"), str)
            ):
                raise EvidenceError("diagnostic fd record is malformed")
            fd_number = fd["fd"]
            target = fd["target"]
            if fd_number < 0 or fd_number in seen_fds or not target or len(target) > MAX_DIAGNOSTIC_TARGET_LENGTH:
                raise EvidenceError("diagnostic fd is invalid, duplicated, or exceeds bound")
            seen_fds.add(fd_number)
            tables[pid].append(f"{fd_number} -> {target}")
    return rows, tables


def validate_q3u4_inventory(tuners: Any) -> dict[str, Any]:
    """Validate the eight PX4 entries while allowing Siano entries alongside them."""
    if not isinstance(tuners, list):
        raise EvidenceError("/api/tuners must be an array")
    px4 = []
    for item in tuners:
        if not isinstance(item, dict):
            raise EvidenceError("tuner object is not an object")
        name = item.get("name")
        if isinstance(name, str) and name.startswith("PX4-"):
            px4.append(item)
    if len(px4) != 8:
        raise EvidenceError(f"expected exactly eight PX4 tuner objects, got {len(px4)}")
    gr = []
    satellite = []
    for item in px4:
        name = item["name"]
        types = item.get("types")
        if not isinstance(types, list):
            raise EvidenceError("PX4 tuner object has no upstream `types` array")
        normalized = {str(value).upper() for value in types}
        if name.startswith("PX4-GR-"):
            if len(types) != 1 or normalized != {"GR"}:
                raise EvidenceError("PX4-GR tuner types must be exactly {GR}")
            gr.append(item)
        elif name.startswith("PX4-S-"):
            if len(types) != 2 or normalized != {"BS", "CS"}:
                raise EvidenceError("PX4-S tuner types must be exactly {BS, CS}")
            satellite.append(item)
        else:
            raise EvidenceError(f"unexpected PX4 tuner name: {name}")
    if len(gr) != 4 or len(satellite) != 4:
        raise EvidenceError(f"expected four PX4-GR and four PX4-S entries, got {len(gr)}/{len(satellite)}")
    return {"px4_count": 8, "px4_gr_count": len(gr), "px4_satellite_count": len(satellite), "inventory": px4}


def validate_descriptor_snapshot(tables: dict[int, list[str]], rows: dict[int, dict[str, Any]]) -> dict[str, Any]:
    owners: dict[str, list[int]] = {}
    for pid, lines in tables.items():
        for line in lines:
            target = line.split(" -> ", 1)[1] if " -> " in line else ""
            if re.search(r"(?:/dev/bus/usb|/dev/usb|usbfs|ccid|smart.?card)", target, re.IGNORECASE):
                owners.setdefault(target, []).append(pid)
    duplicate = {target: pids for target, pids in owners.items() if len(set(pids)) != 1}
    if duplicate:
        raise EvidenceError(f"USB/smart-card descriptor has multiple owners: {duplicate}")
    main = []
    for pid, row in rows.items():
        argv0 = row.get("argv0", "")
        if argv0 == PACKAGE or argv0.startswith(PACKAGE + ":") or PurePosixPath(argv0).name == "libmirakc.so":
            main.append(pid)
    inherited = {target: pids for target, pids in owners.items() if any(pid in main for pid in pids)}
    if inherited:
        raise EvidenceError(f"mirakc process inherited USB/smart-card descriptors: {inherited}")
    expected_owner_markers = ("libsiano-ts.so", "libmirakc-b25-filter.so", "libpx4d.so")
    marker_processes: dict[str, list[int]] = {}
    for marker in expected_owner_markers:
        pids = [pid for pid, row in rows.items() if marker in row["text"]]
        if len(pids) != 1:
            raise EvidenceError(f"expected exactly one active owner process for {marker}, got {pids}")
        marker_processes[marker] = pids
        if not any(pid in owner_pids for owner_pids in owners.values() for pid in pids):
            raise EvidenceError(f"active owner process has no observed USB/CCID descriptor: {marker}")
    px4_targets = {pid for pids in owners.values() for pid in pids if pid in marker_processes["libpx4d.so"]}
    if len(px4_targets) != 1:
        raise EvidenceError(f"PX4 USB descriptors must have exactly one px4d owner: {sorted(px4_targets)}")
    return {"descriptor_owners": owners, "owner_processes": marker_processes, "px4_owner_pids": sorted(px4_targets), "tracked_pids": sorted(tables)}


def require_dict(value: Any, name: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise EvidenceError(f"{name} must be an object")
    return value


def require_string(value: Any, name: str) -> str:
    if not isinstance(value, str) or not value:
        raise EvidenceError(f"{name} must be a non-empty string")
    return value


def validate_loopback_url(url: str) -> str:
    try:
        parsed = urllib.parse.urlsplit(url)
        port = parsed.port
    except ValueError as error:
        raise EvidenceError(f"probe URL has an invalid port: {url}") from error
    if (
        parsed.scheme != "http"
        or parsed.hostname not in {"127.0.0.1", "localhost"}
        or port is None
        or not 1 <= port <= 65535
        or parsed.username is not None
        or parsed.password is not None
        or parsed.fragment
    ):
        raise EvidenceError(f"probe URL must be plain HTTP loopback with an explicit port: {url}")
    return url


class Harness:
    def __init__(self, args: argparse.Namespace, plan: dict[str, Any], out: Path):
        self.args = args
        self.plan = plan
        self.out = out
        self.adb = args.adb
        self.serial = args.serial
        self.evidence: dict[str, dict[str, Any]] = {}
        self.results: dict[str, dict[str, Any]] = {}
        self.processes_after_start: dict[int, dict[str, Any]] = {}
        self.candidate_observed: dict[str, Any] = {}
        self.command_counts: dict[str, int] = {}
        self.http_counts: dict[str, int] = {}
        self.command_lock = threading.Lock()
        self.cycle_usb_observations: list[dict[str, Any]] = []
        self.started = False
        self.epg_started = False
        self.epg_preexisting = False
        self.epg_started_by_harness = False
        self.epg_host_port: int | None = None
        self.forwards: dict[int, int] = {}
        self.started_at = iso_now()

    def write_bytes(self, relative: str, data: bytes) -> str:
        relative_path = PurePosixPath(relative)
        if (
            not relative
            or relative_path.is_absolute()
            or ".." in relative_path.parts
            or "\\" in relative
            or not relative_path.parts
        ):
            raise EvidenceError(f"unsafe evidence path: {relative}")
        path = self.out.joinpath(*relative_path.parts)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        digest = sha256_file(path)
        self.evidence[relative] = {"sha256": digest, "size": len(data)}
        return relative

    def write_text(self, relative: str, text: str) -> str:
        return self.write_bytes(relative, text.encode("utf-8"))

    def command(self, label: str, argv: list[str], timeout: float | None = None) -> subprocess.CompletedProcess[str]:
        with self.command_lock:
            count = self.command_counts.get(label, 0) + 1
            self.command_counts[label] = count
        evidence_label = label if count == 1 else f"{label}-{count:02d}"
        try:
            result = subprocess.run(
                argv, capture_output=True, text=True, timeout=timeout or self.args.timeout,
                check=False,
            )
        except (OSError, subprocess.TimeoutExpired) as error:
            self.write_text(f"commands/{evidence_label}.json", json.dumps({"argv": argv, "error": str(error)}, sort_keys=True) + "\n")
            raise EvidenceError(f"command failed: {label}: {error}") from error
        self.write_text(f"commands/{evidence_label}.stdout", result.stdout)
        self.write_text(f"commands/{evidence_label}.stderr", result.stderr)
        self.write_text(
            f"commands/{evidence_label}.json",
            json.dumps({"argv": argv, "returncode": result.returncode}, sort_keys=True) + "\n",
        )
        if result.returncode != 0:
            raise EvidenceError(f"command failed: {label}: exit {result.returncode}")
        return result

    def adb_command(self, label: str, *parts: str) -> subprocess.CompletedProcess[str]:
        if self.serial is None:
            raise EvidenceError("adb serial was not selected")
        return self.command(label, [self.adb, "-s", self.serial, *parts])

    def adb_shell(self, label: str, *parts: str) -> str:
        return self.adb_command(label, "shell", *parts).stdout

    def check(self, name: str, function) -> None:
        try:
            value = function()
        except Exception as error:  # noqa: BLE001 - all mandatory checks fail closed
            self.results[name] = {"status": "fail", "error": str(error)}
        else:
            self.results[name] = {"status": "pass", "observed": value}

    def read_plan_path(self, key: str, section: dict[str, Any] | None = None) -> str:
        source = section if section is not None else self.plan
        return require_string(source.get(key), f"plan.{key}")

    def http(self, label: str, url: str, method: str = "GET", data: bytes | None = None) -> tuple[int, bytes, dict[str, str]]:
        validate_loopback_url(url)
        request = urllib.request.Request(url, method=method, data=data)
        try:
            with urllib.request.urlopen(request, timeout=self.args.timeout) as response:
                body = response.read(self.args.max_bytes)
                status = int(response.status)
                headers = dict(response.headers.items())
        except (OSError, urllib.error.URLError, urllib.error.HTTPError) as error:
            raise EvidenceError(f"HTTP probe failed {label}: {error}") from error
        evidence_label = self.http_evidence_label(label)
        self.write_bytes(f"http/{evidence_label}.body", body)
        self.write_text(f"http/{evidence_label}.json", json.dumps({"url": url, "method": method, "status": status, "headers": headers}, sort_keys=True) + "\n")
        if status < 200 or status >= 300:
            raise EvidenceError(f"HTTP probe {label} returned {status}")
        return status, body, headers

    def http_evidence_label(self, label: str) -> str:
        with self.command_lock:
            count = self.http_counts.get(label, 0) + 1
            self.http_counts[label] = count
        return label if count == 1 else f"{label}-{count:02d}"

    def record_http(self, label: str, url: str, method: str, status: int, headers: dict[str, str], body: bytes) -> None:
        evidence_label = self.http_evidence_label(label)
        self.write_bytes(f"http/{evidence_label}.body", body)
        self.write_text(
            f"http/{evidence_label}.json",
            json.dumps({"url": url, "method": method, "status": status, "headers": headers}, sort_keys=True) + "\n",
        )

    def base_url(self, path: str, base: str | None = None) -> str:
        if path.startswith("http://"):
            url = path
        else:
            url = (base or "http://127.0.0.1:40772").rstrip("/") + "/" + path.lstrip("/")
        return validate_loopback_url(url)

    def epg_url(self, path: str) -> str:
        return self.base_url(path, f"http://127.0.0.1:{self.epg_host_port}")

    def observe_epg_logs(self, boundary: float) -> dict[str, list[str]]:
        deadline = time.monotonic() + self.args.epg_log_timeout
        last = ""
        while time.monotonic() < deadline:
            result = self.adb_command(
                "epgstation-logcat",
                "logcat", "-d", "-v", "epoch", "-s", "EPGStationServer",
            )
            last = result.stdout
            try:
                markers = parse_logcat_epoch(last, boundary)
            except EvidenceError:
                time.sleep(0.5)
                continue
            self.write_text("epgstation/logcat-post-start.txt", last)
            return markers
        self.write_text("epgstation/logcat-post-start.txt", last)
        raise EvidenceError("EPGStation post-start logcat did not contain all required update markers")

    def processes(self, label: str) -> dict[int, dict[str, Any]]:
        # Android's default `ps -A` column order is not stable across releases.
        # Ask for a stable PID/PPID/ARGS shape and fall back only for old toys.
        try:
            result = self.adb_command(label, "shell", "ps", "-A", "-o", "PID,PPID,ARGS")
        except EvidenceError:
            result = self.adb_command(label + "-fallback", "shell", "ps", "-A")
        return parse_processes(result.stdout)

    def observed_rows(self, rows: dict[int, dict[str, Any]]) -> dict[int, dict[str, Any]]:
        native_markers = tuple(name.removeprefix("lib").split(".")[0] for name in NATIVE_NAMES)
        return {
            pid: row for pid, row in rows.items()
            if PACKAGE in row["text"] or any(token in row["text"] for token in native_markers)
        }

    def ensure_service(self) -> None:
        if not self.started:
            self.start_service()
        self.forward_mirakc()

    def start_service(self) -> dict[int, dict[str, Any]]:
        self.adb_command("activity-start", "shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")
        self.started = True
        deadline = time.monotonic() + self.args.start_timeout
        while time.monotonic() < deadline:
            processes = self.processes("processes-start")
            observed = self.observed_rows(processes)
            if observed:
                self.processes_after_start = observed
                return observed
            time.sleep(0.25)
        raise EvidenceError("service start produced no observable process")

    def stop_service(self) -> None:
        self.adb_command("package-force-stop", "shell", "am", "force-stop", PACKAGE)
        self.started = False

    def start_epgstation(self) -> float:
        boundary = parse_device_epoch(self.adb_shell("epgstation-epoch-before", "date", "+%s.%N"))
        self.epg_preexisting = self.package_running(EPGSTATION_PACKAGE, "epgstation-state-before")
        if self.epg_preexisting:
            return boundary
        self.adb_command(
            "epgstation-activity-start",
            "shell", "am", "start", "-n", f"{EPGSTATION_PACKAGE}/.MainActivity",
        )
        self.epg_started = True
        self.epg_started_by_harness = True
        return boundary

    def stop_epgstation(self) -> None:
        self.adb_command("epgstation-force-stop", "shell", "am", "force-stop", EPGSTATION_PACKAGE)
        self.epg_started = False
        self.epg_preexisting = False
        self.epg_started_by_harness = False

    def package_running(self, package: str, label: str) -> bool:
        """Observe package process state without assuming pidof is available."""
        rows = self.processes(label)
        marker = re.compile(rf"(?<![A-Za-z0-9_.]){re.escape(package)}(?![A-Za-z0-9_.])")
        return any(marker.search(row["text"]) for row in rows.values())

    def forward_port(self, host_port: int, device_port: int, label: str) -> None:
        if not (1 <= host_port <= 65535 and 1 <= device_port <= 65535):
            raise EvidenceError(f"invalid adb forward port: {host_port}/{device_port}")
        self.adb_command(label, "forward", "--no-rebind", f"tcp:{host_port}", f"tcp:{device_port}")
        self.forwards[host_port] = device_port

    def forward_mirakc(self) -> None:
        if 40772 not in self.forwards:
            self.forward_port(40772, 40772, "mirakc-forward")

    def remove_forwards(self) -> None:
        errors = []
        for host_port in list(self.forwards):
            try:
                self.adb_command("forward-remove", "forward", "--remove", f"tcp:{host_port}")
            except Exception as error:  # noqa: BLE001 - attempt every cleanup
                errors.append(error)
            finally:
                self.forwards.pop(host_port, None)
        if errors:
            raise errors[0]

    def check_candidate(self) -> dict[str, Any]:
        apk = Path(self.args.apk).resolve()
        if not apk.is_file():
            raise EvidenceError(f"APK not found: {apk}")
        self.candidate_observed = {"apk_sha256": sha256_file(apk), "apk_size": apk.stat().st_size}
        aapt = self.command("aapt2-badging", [self.args.aapt2, "dump", "badging", str(apk)])
        apksigner = self.command("apksigner-certs", [self.args.apksigner, "verify", "--print-certs", str(apk)])
        metadata = parse_package_badging(aapt.stdout)
        cert = parse_cert_digest(apksigner.stdout + apksigner.stderr)
        self.candidate_observed.update(metadata, certificate_sha256=cert)
        self.write_text("candidate/aapt2-badging.txt", aapt.stdout)
        self.write_text("candidate/apksigner-certs.txt", apksigner.stdout + apksigner.stderr)
        if metadata["package"] != PACKAGE:
            raise EvidenceError(f"candidate package mismatch: {metadata['package']}")
        if metadata["version_name"] != EXPECTED_PACKAGE_VERSION:
            raise EvidenceError("candidate versionName mismatch")
        if metadata["version_code"] != EXPECTED_PACKAGE_VERSION_CODE:
            raise EvidenceError("candidate versionCode mismatch")
        if cert != EXPECTED_CERT_SHA256:
            raise EvidenceError("candidate signing certificate mismatch")
        audit = Path(__file__).with_name("audit-apk.sh")
        self.command("apk-audit", [str(audit), str(apk)])
        self.serial = self.select_serial()
        props = {}
        for prop in ("ro.serialno", "ro.build.fingerprint", "ro.build.version.sdk", "ro.product.model"):
            props[prop] = self.adb_shell("getprop-" + prop.replace(".", "-"), "getprop", prop).strip()
            if not props[prop]:
                raise EvidenceError(f"device property is empty: {prop}")
        paths = [line.strip()[len("package:"):] for line in self.adb_shell("pm-path", "pm", "path", PACKAGE).splitlines() if line.startswith("package:")]
        if len(paths) != 1:
            raise EvidenceError(f"expected one installed base APK, got {paths}")
        remote_hash = self.adb_shell("installed-sha256", "sha256sum", paths[0]).split()[0]
        local_hash = self.candidate_observed["apk_sha256"]
        if remote_hash != local_hash:
            raise EvidenceError("installed APK SHA-256 differs from candidate")
        package_dump = self.adb_shell("package-dump", "dumpsys", "package", PACKAGE)
        version_match = re.search(r"versionCode=(\d+).*?versionName=([^\s]+)", package_dump, re.DOTALL)
        if not version_match or version_match.group(1) != metadata["version_code"] or version_match.group(2) != metadata["version_name"]:
            raise EvidenceError("installed package version differs from candidate")
        code_path_match = re.search(r"^\s*codePath=([^\s]+)", package_dump, re.MULTILINE)
        if not code_path_match or code_path_match.group(1).endswith(".apk"):
            raise EvidenceError("installed package codePath directory is not observable")
        self.candidate_observed["installed_code_path"] = code_path_match.group(1)
        self.write_text("device/properties.txt", json.dumps(props, sort_keys=True, indent=2) + "\n")
        self.write_text("device/package-dump.txt", package_dump)
        return {
            **self.candidate_observed,
            "apk_size": apk.stat().st_size, "installed_path": paths[0],
            "device_serial": self.serial, "device_properties": props,
        }

    def select_serial(self) -> str:
        output = self.command("adb-devices", [self.adb, "devices", "-l"]).stdout
        devices = []
        for line in output.splitlines()[1:]:
            fields = line.split()
            if len(fields) >= 2 and fields[1] == "device":
                devices.append(fields[0])
        if self.serial:
            if self.serial not in devices:
                raise EvidenceError(f"requested adb serial is not online: {self.serial}")
            return self.serial
        if len(devices) != 1:
            raise EvidenceError(f"adb serial is required unless exactly one device is online: {devices}")
        return devices[0]

    def check_service(self) -> dict[str, Any]:
        self.start_service()
        self.forward_mirakc()
        _, body, _ = self.http("mirakc-version", self.base_url("/api/version"))
        try:
            version = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise EvidenceError(f"/api/version is not JSON: {error}") from error
        observed = version.get("version") if isinstance(version, dict) else None
        if observed != MIRAKC_VERSION:
            raise EvidenceError(f"mirakc version mismatch: {observed!r}")
        return {"version": observed}

    def check_cycles(self) -> dict[str, Any]:
        cycles = require_dict(self.plan.get("cycles"), "plan.cycles")
        tune_path = self.read_plan_path("tune_path", cycles)
        minimum = int(cycles.get("minimum_bytes", 188 * 10))
        if not self.args.interactive_usb:
            raise EvidenceError("ten cycles require --interactive-usb so every reconnect is observed")
        if not self.plan.get("usb_device_markers"):
            raise EvidenceError("ten cycles require plan.usb_device_markers")
        if self.started:
            self.stop_service()
            self.remove_forwards()
            self.wait_no_tracked_processes("cycles-initial-stop")
        observations = []
        for index in range(10):
            self.start_service()
            # Startup jobs can finish before the first tune. Observe their
            # actual child process immediately after activity start, rather
            # than treating a later HTTP response as job evidence.
            job = self.observe_job(f"cycle-{index:02d}-job")
            self.forward_mirakc()
            _, tune_body, _ = self.http(f"cycle-{index:02d}-tune", self.base_url(tune_path))
            if len(tune_body) < minimum:
                raise EvidenceError(f"cycle {index + 1} tune body is too short")
            initial_ts = ts_summary(tune_body)
            observations.append({"cycle": index + 1, "tune_bytes": len(tune_body), "initial_ts": initial_ts, "job": job})
            self.stop_service()
            self.remove_forwards()
            self.wait_no_tracked_processes(f"cycle-{index:02d}-stop")
            self.cycle_usb_observations.append(self.perform_usb_transition(f"cycle-{index:02d}"))
            self.start_service()
            self.forward_mirakc()
            _, recovery_version_body, _ = self.http(f"cycle-{index:02d}-recovery-version", self.base_url("/api/version"))
            try:
                recovery_version = json.loads(recovery_version_body.decode("utf-8")).get("version")
            except (UnicodeDecodeError, json.JSONDecodeError, AttributeError) as error:
                raise EvidenceError(f"cycle {index + 1} recovery version is invalid") from error
            if recovery_version != MIRAKC_VERSION:
                raise EvidenceError(f"cycle {index + 1} recovery version mismatch: {recovery_version!r}")
            _, recovery_body, _ = self.http(f"cycle-{index:02d}-recovery-tune", self.base_url(tune_path))
            if len(recovery_body) < minimum:
                raise EvidenceError(f"cycle {index + 1} recovery tune body is too short")
            ts_summary(recovery_body)
            self.stop_service()
            self.remove_forwards()
            self.wait_no_tracked_processes(f"cycle-{index:02d}-recovery-stop")
        return {"cycles": observations}

    def tracked_rows(self) -> dict[int, dict[str, Any]]:
        rows = self.processes("processes-tracked")
        tracked = self.observed_rows(rows)
        # Include every observed descendant, even when its command name is not
        # one of the native payloads. This checks unrelated children without
        # inventing a process-name allow-list.
        changed = True
        while changed:
            changed = False
            for pid, row in rows.items():
                if pid not in tracked and row["ppid"] in tracked:
                    tracked[pid] = row
                    changed = True
        return tracked

    def observe_job(self, label: str) -> dict[str, Any]:
        """Observe an actual configured mirakc-arib job and its exit."""
        self.trigger_update_schedules(label)
        deadline = time.monotonic() + self.args.job_timeout
        observed: dict[int, dict[str, Any]] = {}
        observed_at: float | None = None
        while time.monotonic() < deadline:
            rows = self.processes(label + "-start")
            observed = {
                pid: row for pid, row in rows.items()
                if is_collect_eits_process(row)
            }
            if observed:
                observed_at = time.monotonic()
                break
            time.sleep(0.25)
        if not observed:
            raise EvidenceError(
                "no actual libmirakc-arib.so collect-eits process was observed; "
                "HTTP responses cannot substitute for job execution"
            )
        exit_deadline = time.monotonic() + self.args.job_timeout
        while time.monotonic() < exit_deadline:
            rows = self.processes(label + "-complete")
            remaining = {pid: row for pid, row in rows.items() if pid in observed}
            if not remaining:
                return {
                    "started": sorted(observed),
                    "completed": True,
                    "observed_monotonic_start": observed_at,
                    "observed_monotonic_end": time.monotonic(),
                }
            time.sleep(0.25)
        raise EvidenceError(f"job process did not complete: {sorted(observed)}")

    def wait_no_tracked_processes(self, label: str) -> None:
        deadline = time.monotonic() + self.args.stop_timeout
        last = {}
        while time.monotonic() < deadline:
            last = self.tracked_rows()
            if not last:
                return
            time.sleep(0.25)
        self.write_text(f"process/{label}-remaining.txt", "\n".join(row["text"] for row in last.values()) + "\n")
        raise EvidenceError(f"tracked mirakc processes remain after stop: {sorted(last)}")

    def fd_tables(self, label: str, rows: dict[int, dict[str, Any]] | None = None) -> dict[int, list[str]]:
        rows = rows if rows is not None else self.tracked_rows()
        if not rows:
            raise EvidenceError("no tracked mirakc/native process is observable")
        result = {}
        for pid in sorted(rows):
            try:
                listing = self.adb_shell(f"fd-{label}-{pid}", "ls", "-l", f"/proc/{pid}/fd")
            except EvidenceError as error:
                raise EvidenceError(
                    f"cannot read /proc/{pid}/fd from adb shell (likely production SELinux restriction); "
                    "descriptor ownership is unverified and fails closed"
                ) from error
            self.write_text(f"process/{label}-fd-{pid}.txt", listing)
            result[pid] = listing.splitlines()
        return result

    def diagnostic_fd_tables(self, label: str) -> tuple[dict[int, dict[str, Any]], dict[int, list[str]]]:
        result = self.adb_command(
            f"diagnostic-{label}",
            "shell", "content", "query", "--uri", DIAGNOSTIC_URI, "--projection", "json",
        )
        rows, tables = parse_diagnostic_snapshot(result.stdout)
        self.write_text(f"process/{label}-diagnostic.json", json.dumps({"rows": rows, "tables": tables}, sort_keys=True) + "\n")
        return rows, tables

    def trigger_update_schedules(self, label: str) -> None:
        result = self.adb_command(
            f"{label}-trigger-update-schedules",
            "shell", "content", "call", "--uri", DIAGNOSTIC_URI,
            "--method", DIAGNOSTIC_TRIGGER_METHOD,
        )
        if "triggered=true" not in result.stdout.replace(" ", "").lower():
            raise EvidenceError("diagnostic update-schedules trigger was not accepted")

    def check_descriptors(self) -> dict[str, Any]:
        self.ensure_service()
        siano = require_dict(self.plan.get("siano_12seg"), "plan.siano_12seg")
        concurrency = require_dict(self.plan.get("concurrency"), "plan.concurrency")
        stream_paths = {
            "siano": self.read_plan_path("stream_path", siano),
            "satellite": self.read_plan_path("satellite_path", concurrency),
        }
        if stream_paths["siano"] == stream_paths["satellite"]:
            raise EvidenceError("Siano and satellite descriptor streams must use distinct paths")
        responses = []
        stream_evidence = {}
        try:
            for name, path in stream_paths.items():
                url = self.base_url(path)
                request = urllib.request.Request(url, method="GET")
                try:
                    response = urllib.request.urlopen(request, timeout=self.args.timeout)
                except (OSError, urllib.error.URLError, urllib.error.HTTPError) as error:
                    raise EvidenceError(f"descriptor snapshot {name} stream failed: {error}") from error
                responses.append(response)
                status = int(response.status)
                headers = dict(response.headers.items())
                if status < 200 or status >= 300:
                    raise EvidenceError(f"descriptor snapshot {name} stream returned {status}")
                body = response.read(188 * 10)
                stream_summary = ts_summary(body)
                self.record_http(f"descriptor-{name}-stream", url, "GET", status, headers, body)
                stream_evidence[name] = {"bytes": len(body), "ts": stream_summary}
            # Both HTTP responses remain open while this exact descriptor
            # snapshot is taken; idle process state is not ownership evidence.
            diagnostic_rows, tables = self.diagnostic_fd_tables("active-streams")
        finally:
            for response in reversed(responses):
                response.close()
        descriptor_result = validate_descriptor_snapshot(tables, diagnostic_rows)
        return {
            **descriptor_result,
            "active_streams": stream_evidence,
        }

    def capture_and_validate(self, label: str, path: str, expected_codecs: set[str], pids: list[int] | None = None) -> dict[str, Any]:
        _, body, _ = self.http(label, self.base_url(path))
        stream_relative = f"streams/{label}.ts"
        self.write_bytes(stream_relative, body)
        stream_path = self.out / stream_relative
        summary = ts_summary(body, pids)
        ffprobe = shutil.which(self.args.ffprobe) or self.args.ffprobe
        try:
            probe = subprocess.run(
                [ffprobe, "-v", "error", "-show_streams", "-show_format", "-of", "json", str(stream_path)],
                capture_output=True, text=True, timeout=self.args.timeout, check=False,
            )
        except OSError as error:
            raise EvidenceError(f"ffprobe unavailable: {error}") from error
        self.write_text(f"streams/{label}.ffprobe.stdout", probe.stdout)
        self.write_text(f"streams/{label}.ffprobe.stderr", probe.stderr)
        if probe.returncode != 0:
            raise EvidenceError(f"ffprobe failed for {label}: exit {probe.returncode}")
        try:
            parsed = json.loads(probe.stdout)
        except json.JSONDecodeError as error:
            raise EvidenceError(f"ffprobe JSON invalid for {label}: {error}") from error
        streams = parsed.get("streams") if isinstance(parsed, dict) else None
        codecs = {stream.get("codec_name") for stream in streams or [] if isinstance(stream, dict)}
        if not expected_codecs.issubset(codecs):
            raise EvidenceError(f"{label} codecs {sorted(codecs)} do not include {sorted(expected_codecs)}")
        observed_pids = []
        for stream in streams or []:
            if isinstance(stream, dict) and stream.get("id") is not None:
                try:
                    observed_pids.append(parse_int(stream["id"]))
                except (TypeError, ValueError):
                    pass
        return {"bytes": len(body), "sha256": hashlib.sha256(body).hexdigest(), "ts": summary, "codecs": sorted(codecs), "ffprobe_pids": observed_pids}

    def check_siano(self) -> dict[str, Any]:
        self.ensure_service()
        section = require_dict(self.plan.get("siano_12seg"), "plan.siano_12seg")
        services = self.get_json("siano-services", self.read_plan_path("services_path", section))
        if not isinstance(services, list) or not services:
            raise EvidenceError("Siano scan produced no observable services")
        path = self.read_plan_path("stream_path", section)
        pids = [parse_int(item) for item in section.get("pids", [])]
        return {
            "services": len(services),
            "stream": self.capture_and_validate("siano-12seg", path, {"mpeg2video", "aac"}, pids or None),
        }

    def get_json(self, label: str, path: str) -> Any:
        _, body, _ = self.http(label, self.base_url(path))
        try:
            return json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise EvidenceError(f"{label} is not JSON: {error}") from error

    def check_q3u4(self) -> dict[str, Any]:
        self.ensure_service()
        section = require_dict(self.plan.get("q3u4"), "plan.q3u4")
        tuners = self.get_json("q3u4-tuners", self.read_plan_path("tuners_path", section))
        return validate_q3u4_inventory(tuners)

    def check_satellite(self) -> dict[str, Any]:
        self.ensure_service()
        section = require_dict(self.plan.get("bs_cs_integrity"), "plan.bs_cs_integrity")
        result = {}
        for kind in ("gr", "bs", "cs"):
            path = self.read_plan_path(kind + "_stream_path", section)
            result[kind] = self.capture_and_validate(f"px4-{kind}", path, {"mpeg2video", "aac"})
        return result

    def check_concurrency(self) -> dict[str, Any]:
        self.ensure_service()
        section = require_dict(self.plan.get("concurrency"), "plan.concurrency")
        paths = {key: self.read_plan_path(key + "_path", section) for key in ("gr", "satellite")}
        errors: list[str] = []
        output: dict[str, Any] = {}
        def worker(key: str) -> None:
            try:
                started = time.monotonic()
                _, body, _ = self.http("concurrency-" + key, self.base_url(paths[key]))
                ended = time.monotonic()
                if len(body) < int(section.get("minimum_bytes", 188 * 10)):
                    raise EvidenceError(f"{key} response is too short")
                output[key] = {
                    "bytes": len(body),
                    "ts": ts_summary(body),
                    "monotonic_start": started,
                    "monotonic_end": ended,
                }
            except Exception as error:  # noqa: BLE001
                errors.append(f"{key}: {error}")
        def observe_epg_job() -> None:
            try:
                output["epg_job"] = self.observe_job("concurrency-epg")
            except Exception as error:  # noqa: BLE001
                errors.append(f"epg job: {error}")
        stream_threads = [threading.Thread(target=worker, args=(key,)) for key in paths]
        job_thread = threading.Thread(target=observe_epg_job)
        threads = stream_threads + [job_thread]
        for thread in threads:
            thread.start()
        for thread in stream_threads:
            thread.join(self.args.timeout + 1)
        job_thread.join(2 * self.args.job_timeout + self.args.timeout + 1)
        alive = [thread for thread in threads if thread.is_alive()]
        if alive:
            raise EvidenceError("concurrency probe thread exceeded its bounded join")
        if errors or not all(key in output for key in ("gr", "satellite", "epg_job")):
            raise EvidenceError("concurrent probes failed: " + "; ".join(errors))
        intervals = [
            (output[key]["monotonic_start"], output[key]["monotonic_end"])
            for key in ("gr", "satellite")
        ]
        job_interval = (
            output["epg_job"]["observed_monotonic_start"],
            output["epg_job"]["observed_monotonic_end"],
        )
        overlap_start = max(start for start, _ in intervals + [job_interval])
        overlap_end = min(end for _, end in intervals + [job_interval])
        if overlap_end <= overlap_start:
            raise EvidenceError("GR, satellite, and EPG job intervals did not overlap")
        output["common_overlap_seconds"] = overlap_end - overlap_start
        rows = self.tracked_rows()
        owners = [row for row in rows.values() if "libpx4d.so" in row["text"] or "px4d" in row["text"]]
        if len(owners) != 1:
            raise EvidenceError(f"expected exactly one observed px4d owner, got {len(owners)}")
        output["px4d_owner"] = owners[0]["text"]
        return output

    def check_epgstation(self) -> dict[str, Any]:
        section = require_dict(self.plan.get("epgstation"), "plan.epgstation")
        try:
            host_port = int(section["host_port"])
        except (KeyError, TypeError, ValueError) as error:
            raise EvidenceError("plan.epgstation.host_port is required for adb forward") from error
        if not 1 <= host_port <= 65535:
            raise EvidenceError("plan.epgstation.host_port is invalid")
        planned_device_port = section.get("device_port")
        if planned_device_port is not None and int(planned_device_port) != EPGSTATION_DEVICE_PORT:
            raise EvidenceError(f"EPGStation device port must be the app's fixed {EPGSTATION_DEVICE_PORT}")
        self.epg_host_port = host_port
        boundary = self.start_epgstation()
        self.forward_port(host_port, EPGSTATION_DEVICE_PORT, "epgstation-forward")
        log_markers = self.observe_epg_logs(boundary)

        channels_value = self.get_json("epgstation-channels", self.epg_url("/api/channels"))
        channels = parse_epg_channels(channels_value)
        schedule_epoch = parse_device_epoch(self.adb_shell("epgstation-epoch-schedules", "date", "+%s.%N"))
        now_ms = int(schedule_epoch * 1000)
        self.write_text("epgstation/schedule-device-epoch.txt", f"{schedule_epoch:.9f}\n")
        query = urllib.parse.urlencode({
            "startAt": now_ms - 60 * 60 * 1000,
            "endAt": now_ms + 2 * 60 * 60 * 1000,
            "isHalfWidth": "false",
            "GR": "true",
            "BS": "true",
            "CS": "true",
            "SKY": "true",
        })
        schedules_value = self.get_json("epgstation-schedules", self.epg_url(f"/api/schedules?{query}"))
        schedules = parse_epg_schedules(schedules_value, now_ms)
        channel_ids = {int(channel["id"]) for channel in channels}
        channel_id = int(schedules[0]["channel_id"])
        if channel_id not in channel_ids:
            raise EvidenceError("EPGStation current schedule references a channel absent from /api/channels")
        existing_streams_value = self.get_json("epgstation-streams-before-live", self.epg_url("/api/streams"))
        require_no_epg_streams(existing_streams_value)
        stream_url = self.epg_url(f"/api/streams/live/{channel_id}/m2ts?mode=0")
        request = urllib.request.Request(stream_url, method="GET")
        try:
            response = urllib.request.urlopen(request, timeout=self.args.timeout)
        except (OSError, urllib.error.URLError, urllib.error.HTTPError) as error:
            raise EvidenceError(f"EPGStation live M2TS request failed: {error}") from error
        try:
            status = int(response.status)
            headers = dict(response.headers.items())
            if status < 200 or status >= 300:
                raise EvidenceError(f"EPGStation live M2TS returned {status}")
            body = response.read(188 * 10)
            stream_summary = ts_summary(body)
            self.record_http("epgstation-live-m2ts", stream_url, "GET", status, headers, body)
            active_value = self.get_json("epgstation-streams-active", self.epg_url("/api/streams"))
            active = parse_epg_streams(active_value)
            if not active or not any(parse_int(item["channelId"]) == channel_id for item in active):
                raise EvidenceError("EPGStation active stream list did not contain the observed live channel")
            owned = [item for item in active if parse_int(item["channelId"]) == channel_id]
            if len(owned) != 1:
                raise EvidenceError("EPGStation live probe did not produce exactly one owned stream")
            owned_stream_id = parse_int(owned[0]["streamId"])
        finally:
            response.close()

        deadline = time.monotonic() + self.args.timeout
        active_after: list[dict[str, Any]] = active
        while time.monotonic() < deadline:
            active_after = parse_epg_streams(self.get_json("epgstation-streams-after-close", self.epg_url("/api/streams")))
            if not any(parse_int(item["streamId"]) == owned_stream_id for item in active_after):
                break
            time.sleep(0.25)
        if any(parse_int(item["streamId"]) == owned_stream_id for item in active_after):
            raise EvidenceError("EPGStation owned stream remained after the HTTP response was closed")
        return {
            "package": EPGSTATION_PACKAGE,
            "preexisting_process": self.epg_preexisting,
            "started_by_harness": self.epg_started_by_harness,
            "device_port": EPGSTATION_DEVICE_PORT,
            "log_markers": {key: len(value) for key, value in log_markers.items()},
            "channels": len(channels),
            "schedules_covering_now": len(schedules),
            "channel_id": channel_id,
            "live_m2ts": {"bytes": len(body), "ts": stream_summary},
            "active_stream_id": owned_stream_id,
            "stopped_after_close": True,
        }

    def check_sizes(self, candidate: dict[str, Any]) -> dict[str, Any]:
        apk = Path(self.args.apk).resolve()
        with zipfile.ZipFile(apk) as archive:
            names = archive.namelist()
            native = {name: archive.getinfo(name).file_size for name in names if "/lib/" in name and name.endswith(".so")}
            expected = {
                f"lib/{abi}/{name}" for abi in ("arm64-v8a", "armeabi-v7a") for name in NATIVE_NAMES
            }
            if set(native) != expected:
                raise EvidenceError("APK native size inventory is not exactly the two ABI inventories")
        code_path = candidate.get("installed_code_path")
        if not isinstance(code_path, str) or not code_path:
            raise EvidenceError("installed codePath is missing from candidate binding")
        installed = self.adb_shell("installed-codepath-size", "du", "-sk", code_path).strip()
        if not installed:
            raise EvidenceError("installed codePath size command returned no output")
        base = self.adb_shell("installed-base-size", "du", "-k", candidate["installed_path"]).strip()
        return {"apk_download_bytes": apk.stat().st_size, "native_bytes": native, "installed_codepath_size_du_kb": installed, "installed_base_apk_size_du_kb": base}

    def check_rss(self) -> dict[str, Any]:
        if self.started:
            self.stop_service()
        self.remove_forwards()
        self.adb_command("force-stop-for-cold-start", "shell", "am", "force-stop", PACKAGE)
        self.wait_no_tracked_processes("cold-start-precondition")
        start = time.monotonic()
        self.start_service()
        self.forward_mirakc()
        self.http("rss-cold-start-version", self.base_url("/api/version"))
        cold_ms = int((time.monotonic() - start) * 1000)
        mem = self.measure_tracked_pss("rss-idle")
        job = self.observe_job("rss-epg-job")
        epg_mem = self.measure_tracked_pss("rss-epg")
        return {"cold_start_ms": cold_ms, "idle_tracked_pss_kb": mem, "epg_job": job, "epg_tracked_pss_kb": epg_mem}

    def measure_tracked_pss(self, label: str) -> dict[str, Any]:
        rows = self.tracked_rows()
        if not rows:
            raise EvidenceError("no tracked process exists for RSS measurement")
        values = {}
        for pid in sorted(rows):
            dump = self.adb_shell(f"{label}-meminfo-{pid}", "dumpsys", "meminfo", str(pid))
            values[str(pid)] = parse_meminfo_pss(dump)
        return {"pids": values, "total_pss_kb": sum(values.values())}

    def check_orphans(self) -> dict[str, Any]:
        if self.started:
            self.stop_service()
        self.remove_forwards()
        self.wait_no_tracked_processes("final")
        return {"orphan_count": 0}

    def perform_usb_transition(self, label: str) -> dict[str, Any]:
        if not self.serial:
            raise EvidenceError("serial is required for USB checkpoint")
        markers = self.plan.get("usb_device_markers")
        if not isinstance(markers, list) or not markers or not all(isinstance(item, str) and item for item in markers):
            raise EvidenceError("plan.usb_device_markers must list observed dumpsys usb markers")
        before = self.adb_shell(f"{label}-usb-state-before", "dumpsys", "usb")
        if any(marker not in before for marker in markers):
            raise EvidenceError("not all planned USB markers are present before detach")
        print(f"Disconnect the USB hardware now; waiting for the observed USB markers to disappear (adb {self.serial}).", flush=True)
        deadline = time.monotonic() + self.args.usb_timeout
        offline_seen = False
        states = []
        while time.monotonic() < deadline:
            usb = self.adb_shell(f"{label}-usb-state-detach-poll", "dumpsys", "usb")
            present = all(marker in usb for marker in markers)
            states.append({"present": present, "usb": usb})
            if not present:
                offline_seen = True
                break
            time.sleep(0.5)
        if not offline_seen:
            self.write_text(f"device/{label}-usb-state.json", json.dumps(states, indent=2, sort_keys=True) + "\n")
            raise EvidenceError("USB detach was not observed before timeout")
        print("Reconnect the USB hardware now; waiting for the observed USB markers to return.", flush=True)
        deadline = time.monotonic() + self.args.usb_timeout
        while time.monotonic() < deadline:
            usb = self.adb_shell(f"{label}-usb-state-reconnect-poll", "dumpsys", "usb")
            present = all(marker in usb for marker in markers)
            states.append({"present": present, "usb": usb})
            if present:
                break
            time.sleep(0.5)
        else:
            self.write_text(f"device/{label}-usb-state.json", json.dumps(states, indent=2, sort_keys=True) + "\n")
            raise EvidenceError("USB reconnect was not observed before timeout")
        self.write_text(f"device/{label}-usb-state.json", json.dumps(states, indent=2, sort_keys=True) + "\n")
        self.adb_shell(f"{label}-usb-check-version", "getprop", "ro.serialno")
        return {"serial": self.serial, "markers": markers, "physical_transition": "dumpsys usb markers disappeared and returned"}

    def check_usb_reconnect(self) -> dict[str, Any]:
        if len(self.cycle_usb_observations) != 10:
            raise EvidenceError("USB reconnect evidence does not contain all ten cycle transitions")
        return {"transitions": self.cycle_usb_observations}

    def cleanup(self) -> None:
        try:
            if self.started:
                self.stop_service()
        except Exception as error:  # noqa: BLE001
            self.write_text("cleanup-error.txt", str(error) + "\n")
        try:
            if self.epg_started_by_harness:
                self.stop_epgstation()
        except Exception as error:  # noqa: BLE001
            self.write_text("cleanup-epgstation-error.txt", str(error) + "\n")
        try:
            self.remove_forwards()
        except Exception as error:  # noqa: BLE001
            self.write_text("cleanup-forward-error.txt", str(error) + "\n")

    def run(self) -> int:
        candidate: dict[str, Any] = {}
        try:
            self.check("candidate_binding", lambda: self.check_candidate())
            candidate = dict(self.candidate_observed)
            candidate.update(self.results.get("candidate_binding", {}).get("observed", {}))
            if self.results["candidate_binding"]["status"] == "pass":
                self.check("service_api", self.check_service)
                self.check("ten_cycles", self.check_cycles)
                self.check("siano_12seg", self.check_siano)
                self.check("q3u4_eight_tuner", self.check_q3u4)
                self.check("bs_cs_integrity", self.check_satellite)
                self.check("concurrency_single_px4d", self.check_concurrency)
                self.check("descriptor_ownership", self.check_descriptors)
                self.check("epgstation_scan_schedule_live", self.check_epgstation)
                self.check("size_metrics", lambda: self.check_sizes(candidate))
                self.check("rss_metrics", self.check_rss)
                self.check("usb_detach_reconnect", self.check_usb_reconnect)
                self.check("zero_orphan", self.check_orphans)
            else:
                for name in CHECKS[1:]:
                    self.results[name] = {"status": "fail", "error": "candidate binding failed; dependent check not run"}
        finally:
            self.cleanup()
            self.finish(candidate)
        return 0 if all(self.results.get(name, {}).get("status") == "pass" for name in CHECKS) else 1

    def finish(self, candidate: dict[str, Any]) -> None:
        receipt = {
            "schema": 1,
            "kind": "mirakc-device-evidence",
            "status": "pass" if all(self.results.get(name, {}).get("status") == "pass" for name in CHECKS) else "fail",
            "candidate": candidate,
            "device_serial": self.serial,
            "started_at": self.started_at,
            "ended_at": iso_now(),
            "checks": self.results,
            "evidence_files": sorted(self.evidence),
            "proc_fd_policy": "adb shell /proc/<pid>/fd read failure is a mandatory failure; no SELinux assumption",
        }
        receipt_bytes = (json.dumps(receipt, indent=2, sort_keys=True) + "\n").encode()
        receipt_path = self.out / "receipt.json"
        receipt_path.write_bytes(receipt_bytes)
        receipt_hash = hashlib.sha256(receipt_bytes).hexdigest()
        manifest = {
            "schema": 1,
            "kind": "mirakc-device-evidence-manifest",
            "candidate_apk_sha256": candidate.get("apk_sha256"),
            "candidate": candidate,
            "receipt": {"path": "receipt.json", "sha256": receipt_hash, "size": len(receipt_bytes)},
            "evidence": {path: self.evidence[path] for path in sorted(self.evidence)},
            "self_hash": "excluded; publish the SHA-256 of this manifest alongside the receipt",
        }
        manifest_bytes = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()
        (self.out / "receipt-manifest.json").write_bytes(manifest_bytes)
        (self.out / "receipt-manifest.sha256").write_text(hashlib.sha256(manifest_bytes).hexdigest() + "  receipt-manifest.json\n")


def load_plan(path: str | None) -> dict[str, Any]:
    if not path:
        return {}
    try:
        value = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise EvidenceError(f"cannot read plan: {error}") from error
    return require_dict(value, "plan")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk")
    parser.add_argument("--out", required=True)
    parser.add_argument("--plan")
    parser.add_argument("--serial")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--aapt2", default="aapt2")
    parser.add_argument("--apksigner", default="apksigner")
    parser.add_argument("--ffprobe", default="ffprobe")
    parser.add_argument("--timeout", type=float, default=20.0)
    parser.add_argument("--start-timeout", type=float, default=20.0)
    parser.add_argument("--stop-timeout", type=float, default=10.0)
    parser.add_argument("--job-timeout", type=float, default=120.0)
    parser.add_argument("--epg-log-timeout", type=float, default=120.0)
    parser.add_argument("--max-bytes", type=int, default=DEFAULT_MAX_BYTES)
    parser.add_argument("--usb-timeout", type=float, default=120.0)
    parser.add_argument("--interactive-usb", action="store_true")
    args = parser.parse_args(argv)
    if args.max_bytes < 188 * 10 or args.max_bytes % 188:
        print("device-evidence: --max-bytes must be at least 1880 and divisible by 188", file=sys.stderr)
        return 2
    out = Path(args.out).resolve()
    if out.exists() and any(out.iterdir()):
        print(f"device-evidence: output directory is not empty: {out}", file=sys.stderr)
        return 2
    out.mkdir(parents=True, exist_ok=True)
    try:
        plan = load_plan(args.plan)
        return Harness(args, plan, out).run()
    except EvidenceError as error:
        print(f"device-evidence: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
