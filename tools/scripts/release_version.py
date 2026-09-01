#!/usr/bin/env python3
"""Validate per-APK versions, release tags, and built APK metadata."""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MODULES = {
    "mirakc": {
        "version_file": ROOT / "mirakc" / "VERSION",
        "build_file": ROOT / "mirakc" / "build.gradle.kts",
        "apk_prefix": "mirakc",
    },
    "epgstation-server": {
        "version_file": ROOT / "epgstation-server" / "VERSION",
        "build_file": ROOT / "epgstation-server" / "build.gradle.kts",
        "apk_prefix": "epgstation-server",
    },
}
SEMVER = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$")
LITERAL_VERSION_NAME = re.compile(r'''^\s*versionName\s*=\s*['"]''', re.MULTILINE)
LITERAL_VERSION_CODE = re.compile(r"^\s*versionCode\s*=\s*\d", re.MULTILINE)
BADGING_VERSION = re.compile(r"versionCode='(\d+)'\s+versionName='([^']*)'")


def module_data(component: str) -> dict[str, Path | str]:
    try:
        return MODULES[component]
    except KeyError as error:
        choices = ", ".join(MODULES)
        raise ValueError(f"component must be one of: {choices}") from error


def read_version(component: str) -> str:
    data = module_data(component)
    version = Path(data["version_file"]).read_text(encoding="utf-8").strip()
    if not SEMVER.fullmatch(version):
        raise ValueError(f"{component}/VERSION must hold a semantic version, found {version!r}")
    return version


def version_code(version: str) -> int:
    major, minor, patch = (int(part) for part in version.split("."))
    return major * 10000 + minor * 100 + patch


def check_sources(component: str, version: str) -> None:
    data = module_data(component)
    build_file = Path(data["build_file"])
    text = build_file.read_text(encoding="utf-8")
    rel = build_file.relative_to(ROOT)
    if 'file("VERSION")' not in text:
        raise ValueError(f"{rel} no longer reads its module VERSION")
    if LITERAL_VERSION_NAME.search(text):
        raise ValueError(f"{rel} hardcodes versionName; it must read its module VERSION")
    if LITERAL_VERSION_CODE.search(text):
        raise ValueError(f"{rel} hardcodes versionCode; it must derive it from its module VERSION")
    if "versionName = appVersionText" not in text or "versionCode = appVersionCode" not in text:
        raise ValueError(f"{rel} does not take version metadata from its module VERSION")
    if version_code(version) <= 0:
        raise ValueError(f"{component}: derived versionCode must be positive")


def find_aapt2() -> str:
    found = shutil.which("aapt2")
    if found:
        return found
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if sdk:
        candidates = sorted((Path(sdk) / "build-tools").glob("*/aapt2"))
        if candidates:
            return str(candidates[-1])
    raise ValueError("aapt2 was not found; set ANDROID_SDK_ROOT or put it on PATH")


def check_apks(component: str, version: str, apks: list[str]) -> None:
    aapt2 = find_aapt2()
    expected_code = str(version_code(version))
    expected_prefix = str(module_data(component)["apk_prefix"])
    for apk in apks:
        output = subprocess.run(
            [aapt2, "dump", "badging", apk],
            check=True,
            capture_output=True,
            text=True,
        ).stdout
        match = BADGING_VERSION.search(output)
        if match is None:
            raise ValueError(f"{apk}: could not read versionCode/versionName")
        code, name = match.groups()
        if name != version:
            raise ValueError(f"{apk}: versionName {name!r} does not match {component}/VERSION {version!r}")
        if code != expected_code:
            raise ValueError(f"{apk}: versionCode {code} does not match the derived {expected_code}")
        if not Path(apk).name.startswith(f"{expected_prefix}-"):
            raise ValueError(f"{apk}: artifact name does not belong to {component}")
        print(f"{apk}: {name} ({code})")


def check(component: str, tag: str | None, apks: list[str]) -> None:
    version = read_version(component)
    check_sources(component, version)
    expected_tag = f"{component}-v{version}"
    if tag is not None and tag != expected_tag:
        raise ValueError(f"Release tag {tag!r} must equal {expected_tag!r}")
    if apks:
        check_apks(component, version, apks)
    print(f"{component} release metadata is consistent: {version} ({version_code(version)})")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    check_parser = subparsers.add_parser("check", help="validate one APK's version-bearing places")
    check_parser.add_argument("--component", required=True, choices=sorted(MODULES))
    check_parser.add_argument("--tag", help="release tag that must equal <component>-v<VERSION>")
    check_parser.add_argument("--apk", action="append", default=[], help="built APK to verify")
    print_parser = subparsers.add_parser("print", help="print one APK's version")
    print_parser.add_argument("--component", required=True, choices=sorted(MODULES))
    print_parser.add_argument("--code", action="store_true", help="print the derived versionCode")
    args = parser.parse_args()
    try:
        if args.command == "print":
            version = read_version(args.component)
            print(version_code(version) if args.code else version)
        else:
            check(args.component, args.tag, args.apk)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"release metadata error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
