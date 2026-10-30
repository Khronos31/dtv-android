#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
"""Exercise Cargo registry and Git source vendoring provenance checks."""

from __future__ import annotations

import hashlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import sys
import tarfile
import tempfile


TOOLS = Path(__file__).parent
spec = importlib.util.spec_from_file_location("mirakc_source_audit", TOOLS / "audit-source.py")
if spec is None or spec.loader is None:
    raise SystemExit("cannot load source auditor")
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)
package_spec = importlib.util.spec_from_file_location("mirakc_source_package", TOOLS / "package-source.py")
if package_spec is None or package_spec.loader is None:
    raise SystemExit("cannot load source packager")
package_module = importlib.util.module_from_spec(package_spec)
package_spec.loader.exec_module(package_module)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)


def package_tree(root: Path, name: str, version: str, content: bytes, package_checksum: str | None) -> None:
    package = root / f"{name}-{version}"
    (package / "src").mkdir(parents=True)
    files = {
        "Cargo.toml": f'[package]\nname = "{name}"\nversion = "{version}"\nedition = "2021"\n'.encode(),
        "src/lib.rs": content,
    }
    for relative, data in files.items():
        (package / relative).write_bytes(data)
    checksums = {relative: hashlib.sha256(data).hexdigest() for relative, data in files.items()}
    (package / ".cargo-checksum.json").write_text(
        json.dumps({"files": checksums, "package": package_checksum}, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def refresh_package_checksums(package: Path) -> None:
    checksums = {
        path.relative_to(package).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in package.rglob("*") if path.is_file() and path.name != ".cargo-checksum.json"
    }
    checksum_path = package / ".cargo-checksum.json"
    checksum = json.loads(checksum_path.read_text(encoding="utf-8"))
    checksum["files"] = checksums
    checksum_path.write_text(json.dumps(checksum, sort_keys=True) + "\n", encoding="utf-8")


def vendor_tree_sha256(package: Path) -> str:
    return audit.cargo_vendor_tree_sha256({
        path.relative_to(package).as_posix(): path.read_bytes()
        for path in package.rglob("*") if path.is_file()
    })


def archive_fixture(path: Path, lock: bytes, config: bytes, vendor: Path, inventory: list[dict]) -> None:
    payloads = {
        ".cargo/config.toml": config,
        "sources/mirakc/Cargo.lock": lock,
    }
    for file in vendor.rglob("*"):
        if file.is_file():
            payloads[f"third_party/cargo/vendor/{file.relative_to(vendor).as_posix()}"] = file.read_bytes()
    manifest = {
        "cargo_vendor": {
            "config": ".cargo/config.toml",
            "root": "third_party/cargo/vendor",
            "lock": "sources/mirakc/Cargo.lock",
            "packages": inventory,
        }
    }
    payloads["source-manifest.json"] = json.dumps(manifest).encode()
    with tarfile.open(path, "w:gz") as archive:
        for name, data in sorted(payloads.items()):
            member = tarfile.TarInfo(name)
            member.mode = 0o644
            member.size = len(data)
            archive.addfile(member, io.BytesIO(data))


def audit_fixture(path: Path) -> None:
    members = audit.archive_members(path)
    with tarfile.open(path, "r:gz") as archive:
        manifest = json.load(archive.extractfile("source-manifest.json"))
    audit.verify_cargo_vendor(path, members, manifest)


def must_reject(path: Path, message: str) -> None:
    try:
        audit_fixture(path)
    except audit.AuditError:
        return
    raise SystemExit(message)


with tempfile.TemporaryDirectory(prefix="mirakc-cargo-vendor-test-") as temporary:
    root = Path(temporary)
    vendor = root / "vendor"
    vendor.mkdir()
    git_url = "https://example.invalid/git-dep"
    git_ref = "feature/fixed"
    git_commit = "1" * 40
    git_source = f"git+{git_url}?branch={git_ref}#{git_commit}"
    registry_checksum = "a" * 64
    lock = (
        'version = 4\n\n'
        '[[package]]\nname = "git-dep"\nversion = "1.2.3"\n'
        f'source = "{git_source}"\n\n'
        '[[package]]\nname = "registry-dep"\nversion = "0.9.0"\n'
        'source = "registry+https://github.com/rust-lang/crates.io-index"\n'
        f'checksum = "{registry_checksum}"\n'
    ).encode()
    raw_config = (
        '[source.crates-io]\nreplace-with = "vendored-sources"\n\n'
        f'[source."git+{git_url}?branch={git_ref}"]\n'
        f'git = "{git_url}"\nbranch = "{git_ref}"\nreplace-with = "vendored-sources"\n\n'
        '[source.vendored-sources]\n'
        f'directory = "{root / "vendor"}"\n'
    ).encode()
    supplied_config = root / "cargo-vendor-config.toml"
    supplied_config.write_bytes(raw_config)
    package_tree(vendor, "git-dep", "1.2.3", b"pub fn value() -> u8 { 1 }\n", None)
    git_package = vendor / "git-dep-1.2.3"
    git_tree_pin = vendor_tree_sha256(git_package)
    fixture_git_pin = {(git_source, "git-dep", "1.2.3"): git_tree_pin}
    audit.CARGO_GIT_VENDOR_PINS = fixture_git_pin
    package_module.audit_module.CARGO_GIT_VENDOR_PINS = fixture_git_pin
    package_tree(vendor, "registry-dep", "0.9.0", b"pub fn value() -> u8 { 2 }\n", registry_checksum)
    stage = root / "stage"
    (stage / "sources/mirakc").mkdir(parents=True)
    (stage / "sources/mirakc/Cargo.lock").write_bytes(lock)
    vendor_inventory = package_module.cargo_vendor_dependencies(
        root, stage, vendor, supplied_config
    )
    vendor = stage / "third_party/cargo/vendor"
    config = (stage / ".cargo/config.toml").read_bytes()
    inventory = vendor_inventory["packages"]
    valid = root / "valid.tar.gz"
    archive_fixture(valid, lock, config, vendor, inventory)
    audit_fixture(valid)

    tampered_vendor = root / "tampered-vendor"
    shutil.copytree(vendor, tampered_vendor)
    tampered_git = tampered_vendor / "git-dep-1.2.3"
    (tampered_git / "src/lib.rs").write_bytes(b"pub fn value() -> u8 { 9 }\n")
    refresh_package_checksums(tampered_git)
    tampered_inventory = [dict(item) for item in inventory]
    tampered_git_entry = next(item for item in tampered_inventory if item["source"].startswith("git+"))
    tampered_git_entry["tree_sha256"] = vendor_tree_sha256(tampered_git)
    tampered_git_entry["files"] = sorted(
        f"third_party/cargo/vendor/git-dep-1.2.3/{path.relative_to(tampered_git).as_posix()}"
        for path in tampered_git.rglob("*") if path.is_file()
    )
    tampered = root / "tampered.tar.gz"
    archive_fixture(tampered, lock, config, tampered_vendor, tampered_inventory)
    must_reject(tampered, "Cargo audit accepted same-source Git content with recalculated checksums")

    supplied_tampered = root / "supplied-tampered-vendor"
    shutil.copytree(vendor, supplied_tampered)
    supplied_git = supplied_tampered / "git-dep-1.2.3"
    (supplied_git / "src/lib.rs").write_bytes(b"pub fn value() -> u8 { 8 }\n")
    refresh_package_checksums(supplied_git)
    try:
        package_module.cargo_vendor_directory_inventory(
            supplied_tampered, lock, config, "third_party/cargo/vendor"
        )
    except package_module.AuditError:
        pass
    else:
        raise SystemExit("source packager accepted recalculated same-source Git checksums")

    unknown_source = lock.replace(git_source.encode(),
                                  git_source.replace(git_url, "https://example.invalid/unknown").encode())
    unknown = root / "unknown-git-source.tar.gz"
    archive_fixture(unknown, unknown_source, config, vendor, inventory)
    must_reject(unknown, "Cargo audit accepted a Git dependency without its exact source mapping")

    unknown_revision = lock.replace(git_commit.encode(), ("2" * 40).encode())
    revision = root / "unknown-git-revision.tar.gz"
    changed_revision_source = git_source.replace(git_commit, "2" * 40)
    changed_revision_config = config.replace(git_source.rsplit("#", 1)[0].encode(),
                                             changed_revision_source.rsplit("#", 1)[0].encode())
    revision_vendor = root / "unknown-revision-vendor"
    shutil.copytree(vendor, revision_vendor)
    revision_package = revision_vendor / "git-dep-1.2.3"
    (revision_package / "src/lib.rs").write_bytes(b"pub fn value() -> u8 { 7 }\n")
    refresh_package_checksums(revision_package)
    revision_inventory = [dict(item) for item in inventory]
    revision_git_entry = next(item for item in revision_inventory if item["source"].startswith("git+"))
    revision_git_entry["source"] = changed_revision_source
    revision_git_entry["tree_sha256"] = vendor_tree_sha256(revision_package)
    revision_git_entry["files"] = sorted(
        f"third_party/cargo/vendor/git-dep-1.2.3/{path.relative_to(revision_package).as_posix()}"
        for path in revision_package.rglob("*") if path.is_file()
    )
    archive_fixture(revision, unknown_revision, changed_revision_config, revision_vendor, revision_inventory)
    must_reject(revision, "Cargo audit accepted a same-name/version Git replacement with recalculated checksums")

    bad_registry = lock.replace(registry_checksum.encode(), ("b" * 64).encode())
    registry = root / "registry-checksum.tar.gz"
    archive_fixture(registry, bad_registry, config, vendor, inventory)
    must_reject(registry, "Cargo audit accepted a changed registry checksum")

    git_checksum_vendor = root / "git-checksum-vendor"
    shutil.copytree(vendor, git_checksum_vendor)
    checksum_path = git_checksum_vendor / "git-dep-1.2.3/.cargo-checksum.json"
    checksum = json.loads(checksum_path.read_text(encoding="utf-8"))
    checksum["package"] = registry_checksum
    checksum_path.write_text(json.dumps(checksum, sort_keys=True) + "\n", encoding="utf-8")
    bad_git_checksum = root / "git-package-checksum.tar.gz"
    archive_fixture(bad_git_checksum, lock, config, git_checksum_vendor, inventory)
    must_reject(bad_git_checksum, "Cargo audit accepted a non-null Git package checksum")

    extra_configs = (
        ("build", b"\n[build]\njobs = 8\n"),
        ("env", b"\n[env]\nCARGO_NET_OFFLINE = \"false\"\n"),
        ("target", b"\n[target.aarch64-linux-android]\nlinker = \"unexpected-linker\"\n"),
    )
    for label, extra_config in extra_configs:
        malicious_config = config + extra_config
        try:
            package_module.normalize_cargo_vendor_config(
                malicious_config, "third_party/cargo/vendor"
            )
        except package_module.AuditError:
            pass
        else:
            raise SystemExit(f"Cargo vendor config normalizer accepted a [{label}] directive")
        extra_config_archive = root / f"cargo-config-with-{label}.tar.gz"
        archive_fixture(extra_config_archive, lock, malicious_config, vendor, inventory)
        must_reject(extra_config_archive, f"Cargo source audit accepted an extra [{label}] directive")

print("Cargo registry/Git vendor provenance fixtures: PASS")
