#!/usr/bin/env python3
"""Exercise CI cache material verification with pinned synthetic fixtures."""

from __future__ import annotations

from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
import runpy
import tempfile


root = Path(__file__).resolve().parents[2]
helper = runpy.run_path(str(root / "tools/mirakc/prepare-ci-cache-material.py"))
error = helper["CacheMaterialError"]
source_name = helper["source_archive_name"]

with tempfile.TemporaryDirectory(prefix="mirakc-ci-cache-material-") as temporary:
    fixture = Path(temporary)
    bootstrap = fixture / "bootstrap-autotools.sh"
    cache = fixture / "autotools-sources"
    prefix = fixture / "autotools"
    pkg_m4 = b"synthetic pkg.m4\n"
    payloads = {
        "m4": b"synthetic m4 source\n",
        "autoconf": b"synthetic autoconf source\n",
        "automake": b"synthetic automake source\n",
        "libtool": b"synthetic libtool source\n",
        "pkg_config": b"synthetic pkg-config source\n",
    }
    versions = {
        "m4": "1.4.19",
        "autoconf": "2.72",
        "automake": "1.17",
        "libtool": "2.5.4",
        "pkg_config": "0.29.2",
    }
    lines = []
    for name, payload in payloads.items():
        lines.extend(
            (
                f"{name}_version={versions[name]}",
                f"{name}_sha256={sha256(payload).hexdigest()}",
                f"{name}_url=https://cache-fixture.invalid/{name}",
            )
        )
    lines.append(f"pkg_m4_sha256={sha256(pkg_m4).hexdigest()}")
    bootstrap.write_text("\n".join(lines) + "\n", encoding="utf-8")
    spec, pkg_m4_sha256 = helper["read_bootstrap_spec"](bootstrap)

    requested = []

    def fixture_opener(url: str, timeout: int):
        requested.append((url, timeout))
        name = url.rsplit("/", 1)[1]
        return BytesIO(payloads[name])

    helper["ensure_source_archives"](spec, cache, fixture_opener)
    helper["verify_source_archives"](spec, cache)
    if len(requested) != len(payloads) or any(timeout != 60 for _, timeout in requested):
        raise SystemExit("source archive fallback did not request each missing pinned input")
    expected_names = {
        source_name(name, fields["version"]) for name, fields in spec.items()
    }
    if {path.name for path in cache.iterdir()} != expected_names:
        raise SystemExit("source archive cache contains unexpected files")

    missing = cache / source_name("automake", versions["automake"])
    missing.unlink()
    requested.clear()
    helper["ensure_source_archives"](spec, cache, fixture_opener)
    if len(requested) != 1 or requested[0][0].endswith("/automake") is False:
        raise SystemExit("independent source-cache miss did not reacquire only its missing archive")
    helper["verify_source_archives"](spec, cache)

    corrupted = cache / source_name("m4", versions["m4"])
    corrupted.write_bytes(b"corrupt cache entry")
    requested.clear()
    try:
        helper["ensure_source_archives"](spec, cache, fixture_opener)
    except error:
        pass
    else:
        raise SystemExit("corrupt cached archive was accepted")
    if requested or corrupted.read_bytes() != b"corrupt cache entry":
        raise SystemExit("corrupt cache entry was fetched over or changed before rejection")

    (prefix / "bin").mkdir(parents=True)
    (prefix / "share/aclocal").mkdir(parents=True)
    (prefix / "share/aclocal/pkg.m4").write_bytes(pkg_m4)
    marker = [
        f"m4={versions['m4']}",
        f"autoconf={versions['autoconf']}",
        f"automake={versions['automake']}",
        f"libtool={versions['libtool']}",
        f"pkg-config={versions['pkg_config']}",
        f"pkg.m4={pkg_m4_sha256}",
    ]
    (prefix / ".mirakc-autotools-versions").write_text("\n".join(marker) + "\n", encoding="utf-8")
    tool_versions = {
        "m4": versions["m4"],
        "autoconf": versions["autoconf"],
        "autoreconf": versions["autoconf"],
        "automake": versions["automake"],
        "aclocal": versions["automake"],
        "libtoolize": versions["libtool"],
    }
    for tool, version in tool_versions.items():
        executable = prefix / "bin" / tool
        executable.write_text(f"#!/bin/sh\nprintf '%s\\n' '{tool} {version}'\n", encoding="utf-8")
        executable.chmod(0o755)
    helper["verify_host_prefix"](spec, pkg_m4_sha256, prefix)
    (prefix / "bin/autoconf").write_text("#!/bin/sh\nprintf '%s\\n' 'autoconf wrong-version'\n", encoding="utf-8")
    (prefix / "bin/autoconf").chmod(0o755)
    try:
        helper["verify_host_prefix"](spec, pkg_m4_sha256, prefix)
    except error:
        pass
    else:
        raise SystemExit("completed host tool with the wrong version was accepted")

print("CI cache material helper fixtures: PASS")
