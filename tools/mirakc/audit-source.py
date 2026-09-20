#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
"""Fail-closed audit for the aggregate mirakc corresponding-source bundle."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import sys
import tarfile
import tomllib


class AuditError(Exception):
    pass


_PAYLOAD_CACHE: dict[str, dict[str, bytes]] = {}
CARGO_VENDOR_CONFIG = (
    b"[source.crates-io]\n"
    b"replace-with = \"vendored-sources\"\n\n"
    b"[source.vendored-sources]\n"
    b"directory = \"third_party/cargo/vendor\"\n"
)
SWAGGER_UI_ARCHIVE = "third_party/swagger-ui/swagger-ui-5.17.14.zip"
SWAGGER_UI_ARCHIVE_SHA256 = "481244d0812097b11fbaeef79f71d942b171617f9c9f9514e63acbe13e71ccdc"


def fail(message: str) -> None:
    raise AuditError(message)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def safe_member(name: str) -> None:
    path = PurePosixPath(name)
    if not name or name.startswith("/") or "\\" in name or ".." in path.parts:
        fail(f"unsafe archive member: {name!r}")


def archive_members(path: Path) -> dict[str, tarfile.TarInfo]:
    if not path.is_file() or path.is_symlink():
        fail(f"archive is not an ordinary file: {path}")
    members: dict[str, tarfile.TarInfo] = {}
    try:
        with tarfile.open(path, "r:gz") as stream:
            for member in stream.getmembers():
                safe_member(member.name)
                if member.name in members:
                    fail(f"duplicate archive member: {member.name}")
                if not member.isfile() or member.issym() or member.islnk() or member.isdev():
                    fail(f"archive member is not a regular file: {member.name}")
                if re.search(r"(^|/)(?:\.git|__pycache__)(?:/|$)", member.name):
                    fail(f"forbidden generated member: {member.name}")
                cargo_vendor_member = member.name.startswith("third_party/cargo/vendor/")
                if not cargo_vendor_member and re.search(r"(?:\.o$|\.a$|\.so(?:\.|$)|\.dylib$|\.apk$|\.inp$|\.bin$|\.py[co]$)", member.name, re.I):
                    fail(f"forbidden binary/generated member: {member.name}")
                members[member.name] = member
    except (OSError, tarfile.TarError) as error:
        fail(f"cannot read source archive: {error}")
    return members


def member_bytes(path: Path, name: str) -> bytes:
    cache_key = str(path)
    if cache_key not in _PAYLOAD_CACHE:
        payloads: dict[str, bytes] = {}
        try:
            with tarfile.open(path, "r:gz") as stream:
                for member in stream.getmembers():
                    handle = stream.extractfile(member)
                    if handle is None:
                        fail(f"cannot read archive member: {member.name}")
                    payloads[member.name] = handle.read()
        except (OSError, tarfile.TarError) as error:
            fail(f"cannot load archive payloads: {error}")
        _PAYLOAD_CACHE[cache_key] = payloads
    if name in _PAYLOAD_CACHE[cache_key]:
        return _PAYLOAD_CACHE[cache_key][name]
    fail(f"archive member is missing: {name}")


def parse_json(data: bytes, name: str) -> object:
    try:
        return json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        fail(f"invalid {name}: {error}")


def verify_checksums(path: Path, members: dict[str, tarfile.TarInfo]) -> None:
    if "SHA256SUMS" not in members:
        fail("SHA256SUMS is missing")
    try:
        text = member_bytes(path, "SHA256SUMS").decode("ascii")
    except UnicodeDecodeError as error:
        fail(f"SHA256SUMS is not ASCII: {error}")
    listed: dict[str, str] = {}
    for line in text.splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([^\r\n]+)", line)
        if not match or match.group(2) in listed:
            fail(f"invalid or duplicate SHA256SUMS line: {line!r}")
        digest, name = match.groups()
        safe_member(name)
        listed[name] = digest
    expected = set(members) - {"SHA256SUMS"}
    if set(listed) != expected:
        fail("SHA256SUMS must list exactly every other archive member")
    for name, expected_digest in listed.items():
        if sha256_bytes(member_bytes(path, name)) != expected_digest:
            fail(f"checksum mismatch: {name}")


EXPECTED = {
    "dtv-android": {
        "url": "https://github.com/Khronos31/dtv-android.git",
        "spdx": "GPL-2.0-or-later",
        "prefix": "sources/dtv-android/",
        "required_paths": (
            "sources/dtv-android/tools/mirakc/package-source.py",
            "sources/dtv-android/tools/mirakc/audit-source.py",
            "sources/dtv-android/tools/mirakc/test-source-package.sh",
            "sources/dtv-android/tools/mirakc/test-source-native-rebuild.sh",
            "sources/dtv-android/tools/mirakc/build-android.sh",
            "sources/dtv-android/tools/mirakc-arib/build-android.sh",
            "sources/dtv-android/rust-toolchain.toml",
        ),
    },
    "mirakc": {
        "version": "3.4.86",
        "commit": "fc9610f51f8621aa8db508ddd36c7f1e2785d7be",
        "url": "https://github.com/mirakc/mirakc.git",
        "spdx": "Apache-2.0 OR MIT",
        "prefix": "sources/mirakc/",
    },
    "mirakc-arib": {
        "version": "0.24.38",
        "commit": "e85e1f991aa91ba0e6c6e02d14a17d159901e181",
        "url": "https://github.com/mirakc/mirakc-arib.git",
        "spdx": "GPL-2.0-or-later",
        "prefix": "sources/mirakc-arib/",
    },
    "siano-userland": {
        "version": "0.1.5",
        "commit": "d4f8930ab56d13c479037f2e242461062d96c127",
        "url": "https://github.com/Khronos31/siano-userland.git",
        "spdx": "GPL-2.0-or-later",
        "prefix": "sources/siano-userland/",
    },
    "px4-userland": {
        "version": "0.1.3",
        "commit": "639e65feee7c9f503d44023edd9ab9bba12d5d74",
        "url": "https://github.com/Khronos31/px4-userland.git",
        "spdx": "GPL-2.0-only",
        "prefix": "sources/px4-userland/",
    },
    "px4_drv": {
        "version": "0.2.1",
        "commit": "2b3f79b5bc5db56e8556bb28397f7d8f74b2adeb",
        "url": "https://github.com/nns779/px4_drv.git",
        "spdx": "GPL-2.0-only",
        "prefix": "sources/px4_drv/",
    },
    "libarib25": {
        "version": "vendored",
        "commit_prefix": "vendored-tree-sha256:",
        "url": "https://github.com/stz2012/libarib25.git",
        "spdx": "Apache-2.0",
        "prefix": "sources/libarib25/",
    },
    "libusb": {
        "version": "1.0.30",
        "commit": "archive-sha256:fea36f34f9156400209595e300840767ab1a385ede1dc7ee893015aea9c6dbaf",
        "url": "https://github.com/libusb/libusb/releases/download/v1.0.30/libusb-1.0.30.tar.bz2",
        "spdx": "LGPL-2.1-or-later",
        "prefix": "third_party/libusb-1.0.30/",
    },
    "linux-firmware-siano": {
        "version": "e981caea6ed33c48d25b7dbf473327dbd01df163",
        "commit": "e981caea6ed33c48d25b7dbf473327dbd01df163",
        "url": "https://git.kernel.org/pub/scm/linux/kernel/git/firmware/linux-firmware.git/plain/isdbt_rio.inp?id=e981caea6ed33c48d25b7dbf473327dbd01df163",
        "license_url": "https://git.kernel.org/pub/scm/linux/kernel/git/firmware/linux-firmware.git/plain/LICENSES/LICENCE.siano?id=e981caea6ed33c48d25b7dbf473327dbd01df163",
        "license_sha256": "cf2b4a0301e028d7357ca84644c5094dec0aa6a4e8f8616426c3d126788a406a",
        "sha256": "054520642d5d09cb7ab7d08dbd6fd9ba9365de56adf2e7d7d06927f9845ff818",
        "spdx": "LicenseRef-Siano-Firmware",
        "excluded": True,
    },
}

# The mirakc-arib build links these pinned recursive submodules.  Keeping the
# refs and URLs here lets both the corresponding-source and APK metadata
# auditors share one provenance table.
MIRAKC_ARIB_SUBMODULES = {
    "mirakc-arib/vendor/aribb24": ("654026e064ec127beff43bf3ecdcebd54119be15", "https://github.com/mirakc/aribb24.git"),
    "mirakc-arib/vendor/cppcodec": ("bd6ddf95129e769b50ef63e0f558fa21364f3f65", "https://github.com/tplgy/cppcodec.git"),
    "mirakc-arib/vendor/cppcodec/test/catch": ("15cf3caaceb21172ea42a24e595a2eb58c3ec960", "https://github.com/catchorg/Catch2.git"),
    "mirakc-arib/vendor/docopt": ("2df2b1cd28a870c810da2e0fcffbb97c9f89708e", "https://github.com/mirakc/docopt.cpp.git"),
    "mirakc-arib/vendor/fmt": ("407c905e45ad75fc29bf0f9bb7c5c2fd3475976f", "https://github.com/fmtlib/fmt.git"),
    "mirakc-arib/vendor/google-benchmark": ("0d98dba29d66e93259db7daa53a9327df767a415", "https://github.com/google/benchmark.git"),
    "mirakc-arib/vendor/googletest": ("52eb8108c5bdec04579160ae17225d66034bd723", "https://github.com/google/googletest.git"),
    "mirakc-arib/vendor/libisdb": ("1d73edac60f918d6ea777cd15793b885d14d5a87", "https://github.com/DBCTRADO/LibISDB.git"),
    "mirakc-arib/vendor/libisdb/Thirdparty/fdk-aac": ("3f864cce9736cc8e9312835465fae18428d76295", "https://github.com/mstorsjo/fdk-aac.git"),
    "mirakc-arib/vendor/rapidjson": ("24b5e7a8b27f42fa16b96fc70aade9106cf7102f", "https://github.com/Tencent/rapidjson.git"),
    "mirakc-arib/vendor/rapidjson/thirdparty/gtest": ("ba96d0b1161f540656efdaed035b3c062b60e006", "https://github.com/google/googletest.git"),
    "mirakc-arib/vendor/spdlog": ("79524ddd08a4ec981b7fea76afd08ee05f83755d", "https://github.com/gabime/spdlog.git"),
    "mirakc-arib/vendor/tsduck-arib": ("c400025b7d31e26c0c15471e81adf2ad50632281", "https://github.com/mirakc/tsduck-arib.git"),
}

# These submodules are present in the corresponding-source checkout, but are
# only used by MIRAKC_ARIB_TEST=ON (or nested test targets).  They therefore
# are not packaged-native consumers and are deliberately omitted from the APK
# provenance inventory.  The source archive still carries and audits them.
MIRAKC_ARIB_APK_SOURCE_ONLY = frozenset({
    "mirakc-arib/vendor/cppcodec/test/catch",
    "mirakc-arib/vendor/google-benchmark",
    "mirakc-arib/vendor/googletest",
    "mirakc-arib/vendor/rapidjson/thirdparty/gtest",
})
MIRAKC_ARIB_APK_SUBMODULES = {
    name: value for name, value in MIRAKC_ARIB_SUBMODULES.items()
    if name not in MIRAKC_ARIB_APK_SOURCE_ONLY
}

LIBARIB25_TREE_IDENTITY = "93c5d79f3aaa8215c28fbf5e44287f49d78dbc07df58c5199a53e6b7887d91e9"

TOOLCHAIN = {
    "schema": 2,
    "gradle": {"wrapper": "7.6.4", "distribution_url": "https://services.gradle.org/distributions/gradle-7.6.4-bin.zip", "distribution_sha256": "bed1da33cca0f557ab13691c77f38bb67388119e4794d113e051039b80af9bb1", "verification": "official Gradle release checksum"},
    "jdk": {"distribution": "temurin", "version": "17.0.20.1+1", "observed": "Temurin 17.0.20.1+1", "required_major": 17, "verification": "setup-java exact version selector; no archive checksum recorded"},
    "android_gradle_plugin": "7.4.2",
    "kotlin": "1.9.24",
    "android_ndk": {"version": "27.0.12077973", "verification": "sdkmanager exact package version; no installer archive checksum recorded"},
    "android_cmake": {"version": "3.22.1", "sdk_package": "cmake;3.22.1", "verification": "sdkmanager exact package version; no installer archive checksum recorded"},
    "cmake": {"version": "3.22.1", "provider": "Android SDK cmake;3.22.1/bin/cmake", "verification": "binary version checked before clean build"},
    "ninja": {"version": "1.10.2", "provider": "bundled with Android SDK cmake;3.22.1", "verification": "binary version checked before clean build"},
    "rust": {"channel": "1.98.1", "toolchain_file": "rust-toolchain.toml", "targets": ["aarch64-linux-android", "armv7-linux-androideabi"], "rustc": "1.98.1", "rustc_commit": "48a229ceaefd4985c50990b14116b6d856af0985", "cargo": "1.98.1", "cargo_commit": "797e8a9bca276c1c9f9f738d2a20f484fa4eea9d", "verification": "exact rustup channel and targets; no installer archive checksum recorded"},
    "cargo": {"lock_path": "sources/mirakc/Cargo.lock", "lock_sha256": "42749dcfa137347602a770fd86bae1691ad60b8d363e36f05e99b840f314acf7", "cargo_ndk": {"version": None, "provider": "not used; build invokes NDK clang/linker directly", "installer_pin": "NOT_APPLICABLE"}},
    "autotools": {"m4": "1.4.19", "autoconf": "2.72", "automake": "1.17", "libtool": "2.5.4", "pkg-config": "0.29.2", "installer_pin": "source_archives_below"},
}

TOOLCHAIN_ARCHIVES = {
    "toolchain-sources/m4-1.4.19.tar.xz": "63aede5c6d33b6d9b13511cd0be2cac046f2e70fd0a07aa9573a04a82783af96",
    "toolchain-sources/autoconf-2.72.tar.xz": "ba885c1319578d6c94d46e9b0dceb4014caafe2490e437a0dbca3f270a223f5a",
    "toolchain-sources/automake-1.17.tar.xz": "8920c1fc411e13b90bf704ef9db6f29d540e76d232cb3b2c9f4dc4cc599bd990",
    "toolchain-sources/libtool-2.5.4.tar.xz": "f81f5860666b0bc7d84baddefa60d1cb9fa6fceb2398cc3baca6afaa60266675",
    "toolchain-sources/pkg-config-0.29.2.tar.gz": "6fc69c01688c9458a57eb9a1664c9aba372ccda420a02bf4429fe610e7e7d591",
}


def verify_cargo_vendor(path: Path, members: dict[str, tarfile.TarInfo], manifest: dict) -> None:
    inventory = manifest.get("cargo_vendor")
    if not isinstance(inventory, dict) or inventory.get("config") != ".cargo/config.toml" or \
       inventory.get("root") != "third_party/cargo/vendor" or inventory.get("lock") != "sources/mirakc/Cargo.lock":
        fail("Cargo vendor inventory is missing or stale")
    if ".cargo/config.toml" not in members or member_bytes(path, ".cargo/config.toml") != CARGO_VENDOR_CONFIG:
        fail("Cargo offline source configuration is missing or mismatched")
    try:
        lock = tomllib.loads(member_bytes(path, "sources/mirakc/Cargo.lock").decode("utf-8"))
    except (UnicodeDecodeError, tomllib.TOMLDecodeError) as error:
        fail(f"Cargo.lock is not valid TOML: {error}")
    lock_packages = []
    for package in lock.get("package", []):
        source = package.get("source", "")
        if source.startswith("registry+"):
            if not isinstance(package.get("checksum"), str):
                fail(f"registry Cargo package has no checksum: {package.get('name')}")
            lock_packages.append((package.get("name"), package.get("version"), package["checksum"]))
    prefix = "third_party/cargo/vendor/"
    vendor_members = sorted(name for name in members if name.startswith(prefix))
    if any("/" not in name[len(prefix):] for name in vendor_members):
        fail("Cargo vendor contains a file outside a package directory")
    package_dirs = sorted({name[len(prefix):].split("/", 1)[0] for name in vendor_members if "/" in name})
    actual = []
    for package_dir in package_dirs:
        package_prefix = prefix + package_dir + "/"
        cargo_toml_path = package_prefix + "Cargo.toml"
        checksum_path = package_prefix + ".cargo-checksum.json"
        if cargo_toml_path not in members or checksum_path not in members:
            fail(f"Cargo vendor package is incomplete: {package_dir}")
        try:
            package = tomllib.loads(member_bytes(path, cargo_toml_path).decode("utf-8"))["package"]
            checksum = json.loads(member_bytes(path, checksum_path).decode("utf-8"))
        except (UnicodeDecodeError, tomllib.TOMLDecodeError, json.JSONDecodeError, KeyError) as error:
            fail(f"Cargo vendor package metadata is invalid: {package_dir}: {error}")
        file_digests = checksum.get("files")
        if not isinstance(checksum.get("package"), str) or not isinstance(file_digests, dict):
            fail(f"Cargo vendor package checksum is missing: {package_dir}")
        package_files = {
            name[len(package_prefix):] for name in vendor_members
            if name.startswith(package_prefix) and name != checksum_path
        }
        if (not all(isinstance(relative, str) and isinstance(digest, str)
                    for relative, digest in file_digests.items())
                or set(file_digests) != package_files):
            fail(f"Cargo vendor package file checksum inventory mismatch: {package_dir}")
        for relative, digest in file_digests.items():
            safe_member(relative)
            member_name = package_prefix + relative
            if member_name not in members or sha256_bytes(member_bytes(path, member_name)) != digest:
                fail(f"Cargo vendor package file checksum mismatch: {package_dir}/{relative}")
        actual.append({
            "name": package.get("name"),
            "version": package.get("version"),
            "path": prefix[:-1] + "/" + package_dir,
            "checksum": checksum["package"],
            "files": [name for name in vendor_members if name.startswith(package_prefix)],
        })
    actual.sort(key=lambda item: item["path"])
    if inventory.get("packages") != actual:
        fail("Cargo vendor package inventory or file list mismatch")
    if sorted((item["name"], item["version"], item["checksum"]) for item in actual) != sorted(lock_packages):
        fail("Cargo vendor packages do not exactly match registry Cargo.lock checksums")


def audit_source_archive(path: Path, expected_dtv_commit: str | None = None) -> None:
    members = archive_members(path)
    verify_checksums(path, members)
    for required in ("source-manifest.json", "LICENSE-INVENTORY.json", "toolchain-manifest.json", "DEPENDENCY-NOTICE.txt"):
        if required not in members:
            fail(f"required aggregate member is missing: {required}")
    manifest = parse_json(member_bytes(path, "source-manifest.json"), "source-manifest.json")
    inventory = parse_json(member_bytes(path, "LICENSE-INVENTORY.json"), "LICENSE-INVENTORY.json")
    toolchain = parse_json(member_bytes(path, "toolchain-manifest.json"), "toolchain-manifest.json")
    if not isinstance(manifest, dict) or manifest.get("schema") != 1 or manifest.get("kind") != "mirakc-corresponding-source":
        fail("source manifest schema/kind mismatch")
    if not isinstance(inventory, list) or manifest.get("license_inventory") != inventory:
        fail("license inventory is not identical to source manifest")
    if toolchain != TOOLCHAIN:
        fail("toolchain manifest is stale or mismatched")
    lock_path = toolchain["cargo"]["lock_path"]
    if lock_path not in members or sha256_bytes(member_bytes(path, lock_path)) != toolchain["cargo"]["lock_sha256"]:
        fail("Cargo.lock is missing or mismatched")
    if "sources/dtv-android/gradle/wrapper/gradle-wrapper.properties" not in members:
        fail("Gradle wrapper manifest is missing")
    wrapper_properties = member_bytes(
        path, "sources/dtv-android/gradle/wrapper/gradle-wrapper.properties"
    ).decode("utf-8")
    expected_gradle_sum = "distributionSha256Sum=" + TOOLCHAIN["gradle"]["distribution_sha256"]
    if expected_gradle_sum not in wrapper_properties.splitlines():
        fail("Gradle wrapper distribution checksum is missing or mismatched")
    try:
        rust_toolchain = tomllib.loads(
            member_bytes(path, "sources/dtv-android/rust-toolchain.toml").decode("utf-8")
        )
    except (UnicodeDecodeError, tomllib.TOMLDecodeError) as error:
        fail(f"Rust toolchain file is not valid TOML: {error}")
    expected_rust = TOOLCHAIN["rust"]
    rust_toolchain_table = rust_toolchain.get("toolchain")
    if not isinstance(rust_toolchain_table, dict) or \
            rust_toolchain_table.get("channel") != expected_rust["channel"] or \
            rust_toolchain_table.get("targets") != expected_rust["targets"]:
        fail("Rust toolchain file is stale or mismatched")
    files = manifest.get("files")
    if not isinstance(files, dict):
        fail("source manifest files map is missing")
    expected_files = set(members) - {"source-manifest.json", "SHA256SUMS"}
    if set(files) != expected_files:
        fail("source manifest files map does not match archive members")
    for name, metadata in files.items():
        if not isinstance(metadata, dict) or metadata.get("sha256") != sha256_bytes(member_bytes(path, name)):
            fail(f"source manifest digest mismatch: {name}")
        if metadata.get("size") != len(member_bytes(path, name)):
            fail(f"source manifest size mismatch: {name}")
    verify_cargo_vendor(path, members, manifest)
    components = manifest.get("components")
    if not isinstance(components, list) or {c.get("name") for c in components if isinstance(c, dict)} != set(EXPECTED):
        fail("source manifest component set mismatch")
    by_name = {component["name"]: component for component in components}
    dtv = by_name.get("dtv-android")
    if not isinstance(dtv, dict) or not re.fullmatch(r"[0-9a-f]{40}", str(dtv.get("commit", ""))):
        fail("dtv-android commit must be a canonical 40-hex revision")
    if not re.fullmatch(r"[0-9a-f]{40}", str(dtv.get("tree", ""))):
        fail("dtv-android tree must be a canonical 40-hex revision")
    if expected_dtv_commit is not None:
        if not re.fullmatch(r"[0-9a-f]{40}", expected_dtv_commit):
            fail("expected DTV commit must be a canonical 40-hex revision")
        if dtv.get("commit") != expected_dtv_commit:
            fail("dtv-android commit does not match expected revision")
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?", str(dtv.get("version", ""))):
        fail("dtv-android version is not a release version")
    for name, expected in EXPECTED.items():
        component = by_name.get(name)
        if not isinstance(component, dict):
            fail(f"component missing: {name}")
        for field in ("url", "spdx"):
            if component.get(field) != expected[field]:
                fail(f"component {name} field mismatch: {field}")
        if name != "dtv-android" and component.get("version") != expected["version"]:
            fail(f"component {name} field mismatch: version")
        if expected.get("excluded"):
            for field in ("commit", "sha256", "license_url", "license_sha256"):
                if component.get(field) != expected[field]:
                    fail(f"excluded component {name} field mismatch: {field}")
            expected_license = "sources/dtv-android/mirakc/src/main/assets/LICENCE.siano"
            if component.get("excluded_from_archive") is not True or component.get("files") != [expected_license]:
                fail(f"excluded component {name} is not marked excluded")
            license_files = component.get("license_files")
            if license_files != [{"path": expected_license, "sha256": expected["license_sha256"]}]:
                fail(f"excluded component {name} license inventory mismatch")
            if inventory.count({"component": name, "path": expected_license, "sha256": expected["license_sha256"]}) != 1:
                fail(f"excluded component {name} is absent from license inventory")
            continue
        if name == "libarib25":
            if component.get("commit") != expected["commit_prefix"] + LIBARIB25_TREE_IDENTITY:
                fail("libarib25 tree identity is missing")
        elif name != "dtv-android" and component.get("commit") != expected["commit"]:
            fail(f"component {name} commit mismatch")
        prefix = expected["prefix"]
        listed = component.get("files")
        actual = sorted(member for member in members if member.startswith(prefix))
        if listed != actual:
            fail(f"component {name} file inventory mismatch")
        license_files = component.get("license_files")
        if not isinstance(license_files, list) or not license_files:
            fail(f"component {name} has no license inventory")
        for item in license_files:
            if not isinstance(item, dict) or item.get("path") not in actual:
                fail(f"component {name} has invalid license path")
            if item.get("sha256") != sha256_bytes(member_bytes(path, item["path"])):
                fail(f"component {name} license digest mismatch")
    for required_path in EXPECTED["dtv-android"]["required_paths"]:
        if required_path not in members:
            fail(f"required DTV packaging path is missing: {required_path}")
    for item in inventory:
        if not isinstance(item, dict) or item.get("sha256") != sha256_bytes(member_bytes(path, item.get("path", ""))):
            fail("license inventory digest mismatch")
    for name, expected_digest in TOOLCHAIN_ARCHIVES.items():
        if name not in members or sha256_bytes(member_bytes(path, name)) != expected_digest:
            fail(f"toolchain archive mismatch: {name}")
    if (SWAGGER_UI_ARCHIVE not in members or
            sha256_bytes(member_bytes(path, SWAGGER_UI_ARCHIVE)) != SWAGGER_UI_ARCHIVE_SHA256):
        fail("pinned Swagger UI archive is missing or mismatched")
    notice = member_bytes(path, "DEPENDENCY-NOTICE.txt").decode("utf-8")
    required_notice = (
        "firmware.status=excluded-from-source-archive",
        "firmware.sha256=054520642d5d09cb7ab7d08dbd6fd9ba9365de56adf2e7d7d06927f9845ff818",
        "firmware.license.sha256=cf2b4a0301e028d7357ca84644c5094dec0aa6a4e8f8616426c3d126788a406a",
    )
    if any(line not in notice.splitlines() for line in required_notice):
        fail("firmware exclusion provenance is missing")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", type=Path)
    parser.add_argument("--expected-dtv-commit")
    args = parser.parse_args()
    audit_source_archive(args.archive.resolve(), args.expected_dtv_commit)
    print(f"aggregate source audit: PASS ({args.archive})")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AuditError, OSError, ValueError, KeyError) as error:
        print(f"audit-source: {error}", file=sys.stderr)
        raise SystemExit(1)
