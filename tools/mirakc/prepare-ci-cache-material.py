#!/usr/bin/env python3
"""Fetch and validate pinned ARIB autotools material for CI caches."""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from urllib.request import urlopen


class CacheMaterialError(RuntimeError):
    pass


def read_bootstrap_spec(path: Path) -> tuple[dict[str, dict[str, str]], str]:
    text = path.read_text(encoding="utf-8")
    values = {
        f"{name}_{field}": value
        for name, field, value in re.findall(
            r"^(m4|autoconf|automake|libtool|pkg_config)_(version|sha256|url)=([^\n]+)$",
            text,
            re.MULTILINE,
        )
    }
    spec: dict[str, dict[str, str]] = {}
    for name in ("m4", "autoconf", "automake", "libtool", "pkg_config"):
        fields = {field: values.get(f"{name}_{field}") for field in ("version", "sha256", "url")}
        if any(value is None for value in fields.values()):
            raise CacheMaterialError(f"bootstrap script is missing pinned {name} source fields")
        spec[name] = fields  # type: ignore[assignment]
    pkg_m4_sha256 = re.search(r"^pkg_m4_sha256=([0-9a-f]{64})$", text, re.MULTILINE)
    if pkg_m4_sha256 is None:
        raise CacheMaterialError("bootstrap script is missing the pkg.m4 checksum")
    return spec, pkg_m4_sha256.group(1)


def source_archive_name(name: str, version: str) -> str:
    suffix = ".tar.gz" if name == "pkg_config" else ".tar.xz"
    filename = "pkg-config" if name == "pkg_config" else name
    return f"{filename}-{version}{suffix}"


def verify_source_archives(spec: dict[str, dict[str, str]], cache_dir: Path) -> None:
    for name, fields in spec.items():
        archive = cache_dir / source_archive_name(name, fields["version"])
        if not archive.is_file():
            raise CacheMaterialError(f"pinned autotools source archive is missing: {archive.name}")
        actual = hashlib.sha256(archive.read_bytes()).hexdigest()
        if actual != fields["sha256"]:
            raise CacheMaterialError(f"pinned autotools source archive checksum mismatch: {archive.name}")


def ensure_source_archives(
    spec: dict[str, dict[str, str]], cache_dir: Path, opener=urlopen
) -> None:
    cache_dir.mkdir(parents=True, exist_ok=True)
    for name, fields in spec.items():
        archive = cache_dir / source_archive_name(name, fields["version"])
        if archive.exists():
            actual = hashlib.sha256(archive.read_bytes()).hexdigest()
            if actual != fields["sha256"]:
                raise CacheMaterialError(f"pinned autotools source archive checksum mismatch: {archive.name}")
            continue
        temporary_path = None
        try:
            with opener(fields["url"], timeout=60) as response:
                with tempfile.NamedTemporaryFile(dir=cache_dir, prefix=f".{archive.name}.", delete=False) as output:
                    temporary_path = Path(output.name)
                    shutil.copyfileobj(response, output)
            actual = hashlib.sha256(temporary_path.read_bytes()).hexdigest()
            if actual != fields["sha256"]:
                raise CacheMaterialError(f"downloaded autotools source archive checksum mismatch: {archive.name}")
            os.replace(temporary_path, archive)
        finally:
            if temporary_path is not None:
                temporary_path.unlink(missing_ok=True)


def verify_host_prefix(
    spec: dict[str, dict[str, str]], pkg_m4_sha256: str, prefix: Path
) -> None:
    versions = {
        "m4": spec["m4"]["version"],
        "autoconf": spec["autoconf"]["version"],
        "automake": spec["automake"]["version"],
        "libtool": spec["libtool"]["version"],
        "pkg-config": spec["pkg_config"]["version"],
        "pkg.m4": pkg_m4_sha256,
    }
    marker = prefix / ".mirakc-autotools-versions"
    expected_marker = "".join(f"{name}={version}\n" for name, version in versions.items())
    if not marker.is_file() or marker.read_text(encoding="utf-8") != expected_marker:
        raise CacheMaterialError("portable autotools completion marker is missing or mismatched")

    pkg_m4 = prefix / "share/aclocal/pkg.m4"
    if not pkg_m4.is_file() or hashlib.sha256(pkg_m4.read_bytes()).hexdigest() != pkg_m4_sha256:
        raise CacheMaterialError("portable autotools pkg.m4 checksum is missing or mismatched")

    for tool, version in (
        ("m4", spec["m4"]["version"]),
        ("autoconf", spec["autoconf"]["version"]),
        ("autoreconf", spec["autoconf"]["version"]),
        ("automake", spec["automake"]["version"]),
        ("aclocal", spec["automake"]["version"]),
        ("libtoolize", spec["libtool"]["version"]),
    ):
        executable = prefix / "bin" / tool
        if not executable.is_file() or not os.access(executable, os.X_OK):
            raise CacheMaterialError(f"portable autotools executable is missing: {tool}")
        try:
            result = subprocess.run(
                [str(executable), "--version"], capture_output=True, text=True, check=True, timeout=10
            )
        except (OSError, subprocess.SubprocessError) as error:
            raise CacheMaterialError(f"portable autotools executable failed validation: {tool}: {error}") from error
        first_line = result.stdout.splitlines()[0] if result.stdout else ""
        if version not in first_line:
            raise CacheMaterialError(f"portable autotools version mismatch: {tool}: {first_line}")


def prepare(bootstrap: Path, tool_root: Path, fetch_only: bool = False) -> None:
    spec, pkg_m4_sha256 = read_bootstrap_spec(bootstrap)
    cache_dir = tool_root / "autotools-sources"
    prefix = tool_root / "autotools"
    ensure_source_archives(spec, cache_dir)
    verify_source_archives(spec, cache_dir)
    if fetch_only:
        return
    environment = os.environ.copy()
    environment.update(
        {
            "MIRAKC_ARIB_AUTOTOOLS_ROOT": str(tool_root),
            "MIRAKC_ARIB_AUTOTOOLS_PREFIX": str(prefix),
            "MIRAKC_ARIB_AUTOTOOLS_CACHE": str(cache_dir),
            "MIRAKC_ARIB_AUTOTOOLS_WORK": str(tool_root / "autotools-build"),
            "MIRAKC_ARIB_AUTOTOOLS_JOBS": "1",
            "CFLAGS": "",
        }
    )
    subprocess.run(["/bin/sh", str(bootstrap)], check=True, env=environment)
    verify_source_archives(spec, cache_dir)
    verify_host_prefix(spec, pkg_m4_sha256, prefix)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bootstrap", type=Path, required=True)
    parser.add_argument("--tool-root", type=Path, required=True)
    parser.add_argument("--fetch-only", action="store_true")
    arguments = parser.parse_args()
    try:
        prepare(arguments.bootstrap.resolve(), arguments.tool_root.resolve(), arguments.fetch_only)
    except (CacheMaterialError, OSError, subprocess.SubprocessError) as error:
        print(f"CI cache material preparation: {error}", file=sys.stderr)
        return 1
    if arguments.fetch_only:
        print("CI cache source archives: pinned inputs acquired and verified")
    else:
        print("CI cache material: pinned archives and completed portable host tools verified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
