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
from urllib.parse import parse_qsl, urlsplit


class AuditError(Exception):
    pass


_PAYLOAD_CACHE: dict[str, dict[str, bytes]] = {}
SWAGGER_UI_ARCHIVE = "third_party/swagger-ui/swagger-ui-5.32.6.zip"
SWAGGER_UI_ARCHIVE_SHA256 = "b3c07e091559b59a833f66547eb1fc18f2896f96e3f1f953e2f1efb328aa3394"
FIRMWARE_SOURCE_ASSETS = {
    "sources/dtv-android/mirakc/src/main/assets/asicen-loader.bin": "5db39facda140bdcb3a238dad10da5aac752a1ba3aaf17e2239f8d79a0ad6a2a",
    "sources/pix-userland/firmware/dt181.bin": "a8532036b2995c869f713714b85609d2d21508f8848b96e5c7528ab75f3dd7a0",
    "sources/pix-userland/firmware/dt295.bin": "3cabe15f585a8a7a7d7b9b3db548b677b0732c13926dc3a2b5270acf1bc6ffc8",
    "sources/pix-userland/firmware/preserved/verified-ts-iv.bin": "69a206b3bbcd395b81b201f762355fb224c4e9e16439090663b8dfc1f5b03301",
}
SIANO_FIRMWARE_EXCLUSION_REASON = (
    "linux-firmware Siano binary excluded because it has no corresponding source; "
    "redistribution is permitted in binary form"
)
CORE_ANDROID_PATCH_PATH = (
    "sources/dtv-android/tools/mirakc/patches/"
    "dtv-core-receiver-control-concurrency.patch"
)
CORE_ANDROID_PATCH_SHA256 = "9da0cf46a7727a5c7e20de165babd0bc2d78e47c119cad38c1f2c00abeb10521"
PX4_BUILD_JOBS_PATCH_PATH = (
    "sources/dtv-android/tools/mirakc/patches/px4-userland-build-jobs.patch"
)
PX4_BUILD_JOBS_PATCH_SHA256 = "ba9c4607e3411e706e537a38cc879990f46db04977ca717e714e0004553a4a42"
PIX_ANDROID_16K_PATCH_PATH = (
    "sources/dtv-android/tools/mirakc/patches/pix-userland-android-16k.patch"
)
PIX_ANDROID_16K_PATCH_SHA256 = "a1155cb6400c59222abc5348d3e31a89def378e24c5540e62aed272fb02590d2"
CARGO_GIT_VENDOR_PINS = {
    (
        "git+https://github.com/mirakc/utoipa?branch=fix-zip-v9.0#"
        "5243d0c6772b8585f2ffeabe31e941dab898b3eb",
        "utoipa-swagger-ui",
        "10.0.1",
    ): "d5a9fc47a33afa3341f13e89633cb8e6be0b3fd7f5fe257d1f1ce7a611bb3381",
}


def fail(message: str) -> None:
    raise AuditError(message)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def cargo_vendor_tree_sha256(files: dict[str, bytes]) -> str:
    digest = hashlib.sha256()
    for relative, data in sorted(files.items()):
        path = relative.encode("utf-8")
        digest.update(len(path).to_bytes(8, "big"))
        digest.update(path)
        digest.update(len(data).to_bytes(8, "big"))
        digest.update(data)
    return digest.hexdigest()


def cargo_lock_dependencies(lock_data: bytes) -> list[dict]:
    try:
        lock = tomllib.loads(lock_data.decode("utf-8"))
    except (UnicodeDecodeError, tomllib.TOMLDecodeError) as error:
        fail(f"Cargo.lock is not valid TOML: {error}")
    dependencies = []
    for package in lock.get("package", []):
        source = package.get("source")
        if source is None:
            continue
        name = package.get("name")
        version = package.get("version")
        if not isinstance(name, str) or not isinstance(version, str):
            fail("Cargo.lock dependency has no package name or version")
        if source.startswith("registry+"):
            checksum = package.get("checksum")
            if not isinstance(checksum, str) or not re.fullmatch(r"[0-9a-f]{64}", checksum):
                fail(f"registry Cargo package has no valid checksum: {name}")
            dependencies.append({
                "name": name,
                "version": version,
                "source": source,
                "checksum": checksum,
            })
        elif source.startswith("git+"):
            source_key, separator, revision = source.rpartition("#")
            if not separator or not re.fullmatch(r"[0-9a-f]{40}", revision):
                fail(f"Git Cargo package has no full locked commit: {name}")
            raw_url = source_key.removeprefix("git+")
            parsed = urlsplit(raw_url)
            if parsed.scheme not in {"file", "git", "http", "https", "ssh"} or \
                    (parsed.scheme != "file" and not parsed.netloc) or \
                    parsed.username is not None or parsed.password is not None or parsed.fragment:
                fail(f"Git Cargo package has an invalid source URL: {name}")
            selectors = parse_qsl(parsed.query, keep_blank_values=True, strict_parsing=True)
            if any(key not in {"branch", "tag", "rev"} for key, _ in selectors) or \
                    len({key for key, _ in selectors}) != len(selectors):
                fail(f"Git Cargo package has unsupported or duplicate source selectors: {name}")
            if package.get("checksum") is not None:
                fail(f"Git Cargo package unexpectedly has a registry checksum: {name}")
            if (source, name, version) not in CARGO_GIT_VENDOR_PINS:
                fail(f"Cargo.lock contains an unpinned Git package: {name} {version} ({source})")
            dependencies.append({
                "name": name,
                "version": version,
                "source": source,
                "checksum": None,
            })
        else:
            fail(f"Cargo.lock contains an unsupported dependency source: {source}")
    return dependencies


def verify_cargo_source_config(config_data: bytes, dependencies: list[dict], expected_directory: str) -> None:
    try:
        config = tomllib.loads(config_data.decode("utf-8"))
    except (UnicodeDecodeError, tomllib.TOMLDecodeError) as error:
        fail(f"Cargo vendor config is not valid TOML: {error}")
    if set(config) != {"source"}:
        fail("Cargo vendor config contains unsupported top-level settings")
    sources = config.get("source")
    if not isinstance(sources, dict):
        fail("Cargo vendor config has no source mappings")
    expected_git_sources = {item["source"].rsplit("#", 1)[0] for item in dependencies
                            if item["source"].startswith("git+")}
    expected_source_names = {"crates-io", "vendored-sources", *expected_git_sources}
    if set(sources) != expected_source_names:
        fail("Cargo vendor source mappings do not exactly match Cargo.lock")
    if sources.get("crates-io") != {"replace-with": "vendored-sources"}:
        fail("Cargo vendor config does not replace crates.io")
    vendored = sources.get("vendored-sources")
    if vendored != {"directory": expected_directory}:
        fail("Cargo vendor directory mapping is missing or mismatched")
    for source_key in expected_git_sources:
        raw_url = source_key.removeprefix("git+")
        parsed = urlsplit(raw_url)
        selector_pairs = parse_qsl(parsed.query, keep_blank_values=True, strict_parsing=True)
        mapping = {
            "git": parsed._replace(query="").geturl(),
            "replace-with": "vendored-sources",
            **dict(selector_pairs),
        }
        if sources.get(source_key) != mapping:
            fail(f"Cargo vendor Git mapping does not match Cargo.lock: {source_key}")


def normalize_cargo_vendor_config(config_data: bytes, expected_directory: str) -> bytes:
    try:
        text = config_data.decode("utf-8")
        config = tomllib.loads(text)
    except (UnicodeDecodeError, tomllib.TOMLDecodeError) as error:
        fail(f"Cargo vendor config is not valid TOML: {error}")
    if set(config) != {"source"}:
        fail("Cargo vendor config contains unsupported top-level settings")
    sources = config.get("source")
    if not isinstance(sources, dict) or not isinstance(sources.get("vendored-sources"), dict):
        fail("Cargo vendor config has no vendored-sources directory")
    block = re.compile(r"(?ms)(^\[source\.vendored-sources\]\s*\n)(.*?)(?=^\[|\Z)")
    match = block.search(text)
    if match is None:
        fail("Cargo vendor config has no vendored-sources table")
    body = match.group(2)
    directory = re.compile(r'(?m)^directory\s*=\s*"(?:[^"\\]|\\.)*"\s*$')
    if len(directory.findall(body)) != 1:
        fail("Cargo vendor config must contain one vendored-sources directory")
    replacement = f'directory = {json.dumps(expected_directory)}'
    normalized_body = directory.sub(replacement, body)
    return (text[:match.start(2)] + normalized_body + text[match.end(2):]).encode("utf-8")


def cargo_lock_package(dependencies: list[dict], name: str, version: str, vendor_checksum: object,
                       package_dir: str) -> dict:
    matches = [item for item in dependencies if item["name"] == name and item["version"] == version]
    if len(matches) != 1:
        fail(f"Cargo vendor package does not have a unique locked source: {package_dir}")
    locked = matches[0]
    if vendor_checksum != locked["checksum"]:
        fail(f"Cargo vendor package checksum does not match Cargo.lock: {package_dir}")
    return locked


def cargo_vendor_git_tree_pin(locked: dict, name: str, version: str, files: dict[str, bytes],
                              package_dir: str) -> str | None:
    if not locked["source"].startswith("git+"):
        return None
    expected = CARGO_GIT_VENDOR_PINS[(locked["source"], name, version)]
    actual = cargo_vendor_tree_sha256(files)
    if actual != expected:
        fail(f"Cargo Git vendor tree digest mismatch: {package_dir}")
    return actual


def cargo_vendor_directory_inventory(vendor_root: Path, lock_data: bytes, config_data: bytes,
                                     expected_directory: str) -> list[dict]:
    dependencies = cargo_lock_dependencies(lock_data)
    verify_cargo_source_config(config_data, dependencies, expected_directory)
    if not vendor_root.is_dir() or vendor_root.is_symlink():
        fail(f"Cargo vendor directory is missing or unsafe: {vendor_root}")
    actual = []
    for package_root in sorted(vendor_root.iterdir()):
        if not package_root.is_dir() or package_root.is_symlink():
            fail(f"Cargo vendor root contains an unsafe package: {package_root}")
        cargo_toml = package_root / "Cargo.toml"
        checksum_path = package_root / ".cargo-checksum.json"
        if not cargo_toml.is_file() or not checksum_path.is_file():
            fail(f"Cargo vendor package is incomplete: {package_root}")
        try:
            package = tomllib.loads(cargo_toml.read_text(encoding="utf-8"))["package"]
            checksum = json.loads(checksum_path.read_text(encoding="utf-8"))
        except (OSError, KeyError, UnicodeDecodeError, json.JSONDecodeError, tomllib.TOMLDecodeError) as error:
            fail(f"invalid Cargo vendor package {package_root}: {error}")
        file_digests = checksum.get("files")
        if "package" not in checksum or not isinstance(file_digests, dict):
            fail(f"Cargo vendor package checksum is missing: {package_root}")
        name = package.get("name")
        version = package.get("version")
        if not isinstance(name, str) or not isinstance(version, str):
            fail(f"Cargo vendor package identity is invalid: {package_root}")
        locked = cargo_lock_package(dependencies, name, version, checksum["package"], package_root.name)
        package_paths = list(package_root.rglob("*"))
        if any(path.is_symlink() for path in package_paths):
            fail(f"Cargo vendor package contains a symlink: {package_root}")
        package_files = {
            path.relative_to(package_root).as_posix() for path in package_paths
            if path.is_file() and path.name != ".cargo-checksum.json"
        }
        if (not all(isinstance(relative, str) and isinstance(digest, str)
                    and re.fullmatch(r"[0-9a-f]{64}", digest)
                    for relative, digest in file_digests.items())
                or set(file_digests) != package_files):
            fail(f"Cargo vendor package file checksum inventory mismatch: {package_root}")
        for relative, digest in file_digests.items():
            safe_member(relative)
            if sha256_bytes((package_root / relative).read_bytes()) != digest:
                fail(f"Cargo vendor package file checksum mismatch: {package_root / relative}")
        tree_files = {
            path.relative_to(package_root).as_posix(): path.read_bytes()
            for path in package_paths if path.is_file()
        }
        tree_sha256 = cargo_vendor_git_tree_pin(locked, name, version, tree_files, package_root.name)
        inventory = {
            "name": name,
            "version": version,
            "source": locked["source"],
            "path": f"{expected_directory}/{package_root.name}",
            "checksum": checksum["package"],
            "files": sorted(f"{expected_directory}/{package_root.name}/"
                             f"{path.relative_to(package_root).as_posix()}"
                             for path in package_paths if path.is_file()),
        }
        if tree_sha256 is not None:
            inventory["tree_sha256"] = tree_sha256
        actual.append(inventory)
    actual.sort(key=lambda item: item["path"])
    if sorted((item["name"], item["version"], item["source"], item["checksum"])
              for item in actual) != sorted((item["name"], item["version"], item["source"], item["checksum"])
                                            for item in dependencies):
        fail("Cargo vendor packages do not exactly match Cargo.lock sources and checksums")
    return actual


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
                if not cargo_vendor_member and member.name not in FIRMWARE_SOURCE_ASSETS and re.search(r"(?:\.o$|\.a$|\.so(?:\.|$)|\.dylib$|\.apk$|\.inp$|\.bin$|\.py[co]$)", member.name, re.I):
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
            "sources/dtv-android/mirakc-ui-contract/build.gradle.kts",
            "sources/dtv-android/mirakc-ui-contract/src/main/kotlin/dev/khronos31/mirakc/ui/contract/MirakcUiContract.kt",
            "sources/dtv-android/mirakc-tv-ui/build.gradle.kts",
            "sources/dtv-android/mirakc-tv-ui/src/main/java/dev/khronos31/mirakc/ui/MirakcTvScreen.kt",
            CORE_ANDROID_PATCH_PATH,
            PX4_BUILD_JOBS_PATCH_PATH,
            PIX_ANDROID_16K_PATCH_PATH,
            "sources/dtv-android/tools/mirakc/audit-apk-native.sh",
            "sources/dtv-android/tools/mirakc/patches/px4-userland-build-jobs.patch",
            "sources/dtv-android/tools/mirakc/fetch-pinned-userland-sources.sh",
            "sources/dtv-android/mirakc/src/main/assets/asicen-loader.bin",
            "sources/dtv-android/mirakc/src/main/assets/ASICEN-LOADER-NOTICE.txt",
            "sources/dtv-android/tools/mirakc-arib/build-android.sh",
            "sources/dtv-android/rust-toolchain.toml",
        ),
    },
    "mirakc": {
        "version": "3.4.91",
        "commit": "4f0767e87b109cabd17ed1ded0311355244fa018",
        "url": "https://github.com/mirakc/mirakc.git",
        "spdx": "Apache-2.0 OR MIT",
        "prefix": "sources/mirakc/",
    },
    "mirakc-arib": {
        "version": "0.24.40",
        "commit": "312168e85b30941a1fc0344900fd543a036ea341",
        "url": "https://github.com/mirakc/mirakc-arib.git",
        "spdx": "GPL-2.0-or-later",
        "prefix": "sources/mirakc-arib/",
    },
    "siano-userland": {
        "version": "1.0.0",
        "commit": "33c3468e7e10781ae6c7e9a5fd57a0c1b0825288",
        "url": "https://github.com/Khronos31/siano-userland.git",
        "spdx": "GPL-2.0-or-later",
        "prefix": "sources/siano-userland/",
    },
    "px4-userland": {
        "version": "1.0.0",
        "commit": "5c91e8c57b288c0f490061789829e42dbbf61b3d",
        "url": "https://github.com/Khronos31/px4-userland.git",
        "spdx": "GPL-2.0-only",
        "prefix": "sources/px4-userland/",
    },
    "asicen-userland": {
        "version": "1.0.0",
        "commit": "e2a86ca194b60f1111ecfa0a020cd0944c351c76",
        "url": "https://github.com/Khronos31/asicen-userland.git",
        "spdx": "GPL-2.0-only",
        "prefix": "sources/asicen-userland/",
    },
    "pix-userland": {
        "version": "1.0.0",
        "commit": "66f5a731784d205d1d306edda9cc57071c087f8e",
        "url": "https://github.com/Khronos31/pix-userland.git",
        "spdx": "MIT",
        "prefix": "sources/pix-userland/",
        "required_paths": (
            "sources/pix-userland/THIRD_PARTY_NOTICES.md",
            "sources/pix-userland/firmware/manifest.json",
            "sources/pix-userland/firmware/metadata/dt181-source.json",
            "sources/pix-userland/firmware/metadata/dt295-preservation.json",
            "sources/pix-userland/firmware/metadata/dt295-satellite-generation.json",
            "sources/pix-userland/firmware/preserved/verified-ts-iv.bin",
        ),
    },
    "dtv-userland-core": {
        "version": "1.0.0",
        "commit": "67420d1c909e9205841612a186ae4c7fbf4dd092",
        "url": "https://github.com/Khronos31/dtv-userland-core.git",
        "spdx": "MIT",
        "prefix": "sources/dtv-userland-core/",
    },
    "siano-px4-userland": {
        "version": "0.2.1",
        "commit": "90b08dfe79b0b40e393f015975c761863830866a",
        "url": "https://github.com/Khronos31/px4-userland.git",
        "spdx": "GPL-2.0-only",
        "prefix": "sources/siano-px4-userland/",
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
    "jdk": {"distribution": "temurin", "installer": "setup-java@v5 jdkfile", "selector": "17.0.20", "runtime_version": "17.0.20.1+1", "observed": "Temurin 17.0.20.1+1", "archive_url": "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz", "archive_sha256": "3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e", "required_major": 17, "verification": "pinned official Temurin archive SHA-256 and exact runtime gate"},
    "android_gradle_plugin": "7.4.2",
    "kotlin": "1.9.24",
    "android_ndk": {"version": "27.0.12077973", "verification": "sdkmanager exact package version; no installer archive checksum recorded"},
    "android_cmake": {"version": "3.22.1", "sdk_package": "cmake;3.22.1", "verification": "sdkmanager exact package version; no installer archive checksum recorded"},
    "cmake": {"version": "3.22.1", "provider": "Android SDK cmake;3.22.1/bin/cmake", "verification": "binary version checked before clean build"},
    "ninja": {"version": "1.10.2", "provider": "bundled with Android SDK cmake;3.22.1", "verification": "binary version checked before clean build"},
    "rust": {"channel": "1.98.1", "toolchain_file": "rust-toolchain.toml", "targets": ["aarch64-linux-android", "armv7-linux-androideabi"], "rustc": "1.98.1", "rustc_commit": "48a229ceaefd4985c50990b14116b6d856af0985", "cargo": "1.98.1", "cargo_commit": "797e8a9bca276c1c9f9f738d2a20f484fa4eea9d", "verification": "exact rustup channel and targets; no installer archive checksum recorded"},
    "cargo": {"lock_path": "sources/mirakc/Cargo.lock", "lock_sha256": "005a492cfdd0f6d44dd78585359caa0aa28b3c78eb46aaa6c55b18854f78a8b8", "cargo_ndk": {"version": None, "provider": "not used; build invokes NDK clang/linker directly", "installer_pin": "NOT_APPLICABLE"}},
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
    if ".cargo/config.toml" not in members:
        fail("Cargo offline source configuration is missing")
    dependencies = cargo_lock_dependencies(member_bytes(path, "sources/mirakc/Cargo.lock"))
    verify_cargo_source_config(member_bytes(path, ".cargo/config.toml"), dependencies,
                               "third_party/cargo/vendor")
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
        if "package" not in checksum or not isinstance(file_digests, dict):
            fail(f"Cargo vendor package checksum is missing: {package_dir}")
        name = package.get("name")
        version = package.get("version")
        if not isinstance(name, str) or not isinstance(version, str):
            fail(f"Cargo vendor package identity is invalid: {package_dir}")
        locked = cargo_lock_package(dependencies, name, version, checksum["package"], package_dir)
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
        tree_files = {
            name[len(package_prefix):]: member_bytes(path, name)
            for name in vendor_members if name.startswith(package_prefix)
        }
        tree_sha256 = cargo_vendor_git_tree_pin(locked, name, version, tree_files, package_dir)
        package_inventory = {
            "name": name,
            "version": version,
            "source": locked["source"],
            "path": prefix[:-1] + "/" + package_dir,
            "checksum": checksum["package"],
            "files": [name for name in vendor_members if name.startswith(package_prefix)],
        }
        if tree_sha256 is not None:
            package_inventory["tree_sha256"] = tree_sha256
        actual.append(package_inventory)
    actual.sort(key=lambda item: item["path"])
    manifest_packages = inventory.get("packages")
    legacy_registry_inventory = not any(item["source"].startswith("git+") for item in dependencies) and \
        isinstance(manifest_packages, list) and all(
            isinstance(item, dict) and "source" not in item for item in manifest_packages
        )
    if legacy_registry_inventory:
        comparable_actual = [{key: value for key, value in item.items() if key != "source"}
                             for item in actual]
    else:
        comparable_actual = actual
    if manifest_packages != comparable_actual:
        fail("Cargo vendor package inventory or file list mismatch")
    expected_packages = sorted((item["name"], item["version"], item["source"], item["checksum"])
                               for item in dependencies)
    actual_packages = sorted((item["name"], item["version"], item["source"], item["checksum"])
                             for item in actual)
    if actual_packages != expected_packages:
        fail("Cargo vendor packages do not exactly match Cargo.lock sources and checksums")


def audit_source_archive(path: Path, expected_dtv_commit: str | None = None) -> None:
    members = archive_members(path)
    verify_checksums(path, members)
    for name, expected_digest in FIRMWARE_SOURCE_ASSETS.items():
        if name not in members or sha256_bytes(member_bytes(path, name)) != expected_digest:
            fail(f"firmware source asset is missing or mismatched: {name}")
    for required in ("source-manifest.json", "LICENSE-INVENTORY.json", "toolchain-manifest.json", "DEPENDENCY-NOTICE.txt"):
        if required not in members:
            fail(f"required aggregate member is missing: {required}")
    manifest = parse_json(member_bytes(path, "source-manifest.json"), "source-manifest.json")
    inventory = parse_json(member_bytes(path, "LICENSE-INVENTORY.json"), "LICENSE-INVENTORY.json")
    toolchain = parse_json(member_bytes(path, "toolchain-manifest.json"), "toolchain-manifest.json")
    if not isinstance(manifest, dict) or manifest.get("schema") != 1 or manifest.get("kind") != "mirakc-corresponding-source":
        fail("source manifest schema/kind mismatch")
    if manifest.get("excluded_materials") != {
        "siano_firmware_url": EXPECTED["linux-firmware-siano"]["url"],
        "siano_firmware_sha256": EXPECTED["linux-firmware-siano"]["sha256"],
        "siano_firmware_license_url": EXPECTED["linux-firmware-siano"]["license_url"],
        "siano_firmware_license_sha256": EXPECTED["linux-firmware-siano"]["license_sha256"],
        "reason": SIANO_FIRMWARE_EXCLUSION_REASON,
    }:
        fail("excluded Siano firmware provenance is missing or stale")
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
    if manifest.get("dependency_pins") != {
        "siano-userland/dtv-userland-core": EXPECTED["dtv-userland-core"]["commit"],
        "siano-userland/px4-userland": EXPECTED["siano-px4-userland"]["commit"],
        "asicen-userland/dtv-userland-core": EXPECTED["dtv-userland-core"]["commit"],
        "asicen-userland/vendored-px4-userland": "dd1c1bea16a5871085d5f2a42c009364c1ec7345",
        "pix-userland/dtv-userland-core": EXPECTED["dtv-userland-core"]["commit"],
    }:
        fail("transitive Core/PX4 source pin inventory is missing or stale")
    if manifest.get("bundled_firmware_assets") != {
        "sources/dtv-android/mirakc/src/main/assets/asicen-loader.bin": {
            "sha256": FIRMWARE_SOURCE_ASSETS["sources/dtv-android/mirakc/src/main/assets/asicen-loader.bin"],
            "provenance_path": "sources/dtv-android/mirakc/src/main/assets/ASICEN-LOADER-NOTICE.txt",
            "redistribution_rights": "unresolved",
        },
        "sources/pix-userland/firmware/dt181.bin": {
            "sha256": FIRMWARE_SOURCE_ASSETS["sources/pix-userland/firmware/dt181.bin"],
            "provenance_path": "sources/pix-userland/firmware/metadata/dt181-source.json",
        },
        "sources/pix-userland/firmware/dt295.bin": {
            "sha256": FIRMWARE_SOURCE_ASSETS["sources/pix-userland/firmware/dt295.bin"],
            "provenance_path": "sources/pix-userland/firmware/metadata/dt295-satellite-generation.json",
        },
        "sources/pix-userland/firmware/preserved/verified-ts-iv.bin": {
            "sha256": FIRMWARE_SOURCE_ASSETS["sources/pix-userland/firmware/preserved/verified-ts-iv.bin"],
            "provenance_path": "sources/pix-userland/firmware/metadata/dt295-preservation.json",
        },
    }:
        fail("bundled firmware provenance is missing or stale")
    if "sources/dtv-android/mirakc/src/main/assets/ASICEN-LOADER-NOTICE.txt" not in members:
        fail("ASICEN loader provenance notice is missing")
    for source_path, expected_pin in (
        ("sources/siano-userland/cmake/dtv-userland-core.revision", EXPECTED["dtv-userland-core"]["commit"]),
        ("sources/siano-userland/cmake/px4-userland.revision", EXPECTED["siano-px4-userland"]["commit"]),
        ("sources/asicen-userland/cmake/dtv-userland-core.revision", EXPECTED["dtv-userland-core"]["commit"]),
        ("sources/pix-userland/core-integration/dtv-userland-core.revision", EXPECTED["dtv-userland-core"]["commit"]),
    ):
        if source_path not in members or member_bytes(path, source_path).decode("ascii").strip() != expected_pin:
            fail(f"transitive dependency revision mismatch: {source_path}")
    vendor_manifest = "sources/asicen-userland/third_party/px4-userland/UPSTREAM.md"
    if vendor_manifest not in members or "dd1c1bea16a5871085d5f2a42c009364c1ec7345" not in member_bytes(path, vendor_manifest).decode("utf-8"):
        fail("ASICEN vendored PX4 reference provenance mismatch")
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
        if name == "dtv-userland-core":
            expected_relationships = {
                "project_sources": {"spdx": "MIT", "license_file": prefix + "LICENSE"},
                "statically_linked_adapters": [
                    {"spdx": "GPL-2.0-only", "source_prefix": prefix + "adapters/host_daemon/", "license_file": prefix + "adapters/host_daemon/COPYING"},
                    {"spdx": "GPL-2.0-only", "source_prefix": prefix + "adapters/host_ts/", "license_file": prefix + "adapters/host_ts/COPYING"},
                ],
            }
            if component.get("license_relationships") != expected_relationships:
                fail("Core source/license relationship inventory is missing or stale")
        if name == "pix-userland":
            expected_relationships = {
                "project_sources": {"spdx": "MIT", "license_file": prefix + "LICENSE"},
                "linked_component_notice": prefix + "THIRD_PARTY_NOTICES.md",
                "firmware_terms": "third-party terms are separately recorded; not covered by the project MIT license",
            }
            if component.get("license_relationships") != expected_relationships:
                fail("PIX source/license relationship inventory is missing or stale")
    for required_path in EXPECTED["dtv-android"]["required_paths"]:
        if required_path not in members:
            fail(f"required DTV packaging path is missing: {required_path}")
    for required_path in EXPECTED["pix-userland"]["required_paths"]:
        if required_path not in members:
            fail(f"PIX firmware provenance path is missing: {required_path}")
    px4_component = by_name["px4-userland"]
    expected_px4_patch = {
        "path": PX4_BUILD_JOBS_PATCH_PATH,
        "sha256": PX4_BUILD_JOBS_PATCH_SHA256,
        "upstream_commit": EXPECTED["px4-userland"]["commit"],
    }
    if px4_component.get("android_build_patch") != expected_px4_patch:
        fail("PX4 bounded build-job patch provenance is missing or mismatched")
    if PX4_BUILD_JOBS_PATCH_PATH not in members or \
            sha256_bytes(member_bytes(path, PX4_BUILD_JOBS_PATCH_PATH)) != PX4_BUILD_JOBS_PATCH_SHA256:
        fail("PX4 bounded build-job patch is missing or mismatched")
    pix_component = by_name["pix-userland"]
    expected_pix_patch = {
        "path": PIX_ANDROID_16K_PATCH_PATH,
        "sha256": PIX_ANDROID_16K_PATCH_SHA256,
        "upstream_commit": EXPECTED["pix-userland"]["commit"],
    }
    if pix_component.get("android_build_patch") != expected_pix_patch:
        fail("PIX Android 16 KiB patch provenance is missing or mismatched")
    if PIX_ANDROID_16K_PATCH_PATH not in members or \
            sha256_bytes(member_bytes(path, PIX_ANDROID_16K_PATCH_PATH)) != PIX_ANDROID_16K_PATCH_SHA256:
        fail("PIX Android 16 KiB patch is missing or mismatched")
    core_component = by_name["dtv-userland-core"]
    expected_core_patch = {
        "path": CORE_ANDROID_PATCH_PATH,
        "sha256": CORE_ANDROID_PATCH_SHA256,
        "upstream_commit": EXPECTED["dtv-userland-core"]["commit"],
    }
    if core_component.get("android_integration_patch") != expected_core_patch:
        fail("Core concurrency patch provenance is missing or mismatched")
    if CORE_ANDROID_PATCH_PATH not in members or \
            sha256_bytes(member_bytes(path, CORE_ANDROID_PATCH_PATH)) != CORE_ANDROID_PATCH_SHA256:
        fail("Core concurrency patch is missing or mismatched")
    pix_iv = "sources/pix-userland/firmware/preserved/verified-ts-iv.bin"
    if sha256_bytes(member_bytes(path, pix_iv)) != FIRMWARE_SOURCE_ASSETS[pix_iv]:
        fail("PIX preserved DT295 TS IV digest mismatch")
    pix_manifest = parse_json(member_bytes(path, "sources/pix-userland/firmware/manifest.json"), "PIX firmware manifest")
    if not isinstance(pix_manifest, dict) or pix_manifest.get("models", {}).get("dt295", {}).get("sha256") != FIRMWARE_SOURCE_ASSETS["sources/pix-userland/firmware/dt295.bin"]:
        fail("PIX DT295 manifest digest mismatch")
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
