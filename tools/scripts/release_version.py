#!/usr/bin/env python3
"""Keep the dtv-android version consistent across files, tags and artifacts.

VERSION is the single source of truth. The module build scripts read it through
rootProject.extra, so the job here is to prove that nothing has drifted back to
a hardcoded literal, and that the release tag and the built APKs agree with it.
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VERSION_FILE = ROOT / "VERSION"
ROOT_BUILD = ROOT / "build.gradle.kts"
MODULE_BUILDS = (
    ROOT / "mirakc" / "build.gradle.kts",
    ROOT / "epgstation-server" / "build.gradle.kts",
)
SEMVER = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$")
LITERAL_VERSION_NAME = re.compile(r"""^\s*versionName\s*=\s*['"]""", re.MULTILINE)
LITERAL_VERSION_CODE = re.compile(r"^\s*versionCode\s*=\s*\d", re.MULTILINE)
BADGING_VERSION = re.compile(r"versionCode='(\d+)'\s+versionName='([^']*)'")


def read_version() -> str:
    version = VERSION_FILE.read_text(encoding="utf-8").strip()
    if not SEMVER.fullmatch(version):
        raise ValueError(f"VERSION must hold a semantic version such as 1.2.3, found {version!r}")
    return version


def version_code(version: str) -> int:
    major, minor, patch = (int(part) for part in version.split("."))
    return major * 10000 + minor * 100 + patch


def set_version(version: str) -> None:
    if not SEMVER.fullmatch(version):
        raise ValueError(f"Refusing to write a non-semantic version: {version!r}")
    VERSION_FILE.write_text(f"{version}\n", encoding="utf-8")


def check_sources(version: str) -> None:
    root_text = ROOT_BUILD.read_text(encoding="utf-8")
    if 'file("VERSION")' not in root_text:
        raise ValueError("build.gradle.kts no longer reads VERSION")
    for build_file in MODULE_BUILDS:
        text = build_file.read_text(encoding="utf-8")
        rel = build_file.relative_to(ROOT)
        if LITERAL_VERSION_NAME.search(text):
            raise ValueError(f"{rel} hardcodes versionName; it must read rootProject.extra")
        if LITERAL_VERSION_CODE.search(text):
            raise ValueError(f"{rel} hardcodes versionCode; it must read rootProject.extra")
        if "appVersionName" not in text or "appVersionCode" not in text:
            raise ValueError(f"{rel} does not take its version from rootProject.extra")


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


def check_apks(version: str, apks: list[str]) -> None:
    aapt2 = find_aapt2()
    expected_code = str(version_code(version))
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
            raise ValueError(f"{apk}: versionName {name!r} does not match VERSION {version!r}")
        if code != expected_code:
            raise ValueError(f"{apk}: versionCode {code} does not match the derived {expected_code}")
        print(f"{apk}: {name} ({code})")


def check(tag: str | None, apks: list[str]) -> None:
    version = read_version()
    check_sources(version)
    if tag is not None and tag != f"v{version}":
        raise ValueError(f"Release tag {tag!r} must equal 'v{version}'")
    if apks:
        check_apks(version, apks)
    print(f"dtv-android release metadata is consistent: {version} ({version_code(version)})")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    set_parser = subparsers.add_parser("set", help="write the canonical version")
    set_parser.add_argument("version")
    check_parser = subparsers.add_parser("check", help="validate every version-bearing place")
    check_parser.add_argument("--tag", help="release tag that must equal v<VERSION>")
    check_parser.add_argument("--apk", action="append", default=[], help="built APK to verify")
    print_parser = subparsers.add_parser("print", help="print the canonical version")
    print_parser.add_argument("--code", action="store_true", help="print the derived versionCode")
    args = parser.parse_args()
    try:
        if args.command == "set":
            set_version(args.version)
            check(None, [])
        elif args.command == "print":
            version = read_version()
            print(version_code(version) if args.code else version)
        else:
            check(args.tag, args.apk)
    except (OSError, ValueError, subprocess.CalledProcessError) as err:
        print(f"release metadata error: {err}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
