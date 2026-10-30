#!/usr/bin/env python3
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import tempfile
import io


root = Path(__file__).resolve().parents[2]
pix_root = Path(os.environ.get("MIRAKC_TEST_PIX_ROOT", root / ".work/pinned-pix-userland")).resolve()
core_root = Path(os.environ.get(
    "MIRAKC_TEST_CORE_ROOT", root / ".work/pinned-dtv-userland-core")).resolve()
pix_revision = "66f5a731784d205d1d306edda9cc57071c087f8e"
core_revision = "67420d1c909e9205841612a186ae4c7fbf4dd092"


def run(*command, cwd=None, **kwargs):
    result = subprocess.run(command, cwd=cwd, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, **kwargs)
    if result.returncode:
        sys.stderr.write(result.stdout)
        raise subprocess.CalledProcessError(result.returncode, command, output=result.stdout)
    return result.stdout


if run("git", "-C", str(pix_root), "rev-parse", "HEAD").strip() != pix_revision:
    raise SystemExit("PIX fixture source does not match the audited pin")
if run("git", "-C", str(core_root), "rev-parse", "HEAD").strip() != core_revision:
    raise SystemExit("Core fixture source does not match the audited pin")

gradle = (root / "mirakc/build.gradle.kts").read_text(encoding="utf-8")
native_harness = (root / "tools/mirakc/test-source-native-rebuild.sh").read_text(encoding="utf-8")
if 'environment("PIX_SOURCE_REVISION_OVERRIDE", pixPinnedRef)' not in gradle:
    raise SystemExit("Gradle PIX build does not pass its pinned source revision")
if f"PIX_SOURCE_REVISION_OVERRIDE={pix_revision}" not in native_harness:
    raise SystemExit("source-native PIX build does not pass its audited source revision")

with tempfile.TemporaryDirectory(prefix="pix-source-revision-") as temporary:
    temporary = Path(temporary)
    enclosing = temporary / "enclosing-dtv"
    stage = enclosing / "staged-pix-userland"
    stage.mkdir(parents=True)
    run("git", "init", str(enclosing))
    run("git", "-C", str(enclosing), "config", "user.name", "PIX source revision fixture")
    run("git", "-C", str(enclosing), "config", "user.email", "fixture@example.invalid")
    (enclosing / "DTV-COMMIT").write_text("enclosing DTV source\n", encoding="ascii")
    (enclosing / ".gitignore").write_text("staged-pix-userland/\n", encoding="ascii")
    run("git", "-C", str(enclosing), "add", ".gitignore", "DTV-COMMIT")
    run("git", "-C", str(enclosing), "commit", "-m", "fixture DTV commit")
    enclosing_revision = run("git", "-C", str(stage), "rev-parse", "HEAD").strip()
    if enclosing_revision == pix_revision:
        raise SystemExit("fixture enclosing revision unexpectedly matches PIX pin")
    if run("git", "-C", str(stage), "status", "--porcelain").strip():
        raise SystemExit("fixture PIX stage should be clean and ignored by its enclosing checkout")

    archive = subprocess.check_output(["git", "-C", str(pix_root), "archive", pix_revision])
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:") as source_archive:
        source_archive.extractall(stage, filter="data")

    build = temporary / "cmake-build"
    trace = run(
        "cmake", "--trace-expand",
        f"--trace-source={stage / 'core-integration/CMakeLists.txt'}",
        "-S", str(stage / "core-integration"), "-B", str(build),
        f"-DPIX_CORE_SOURCE_DIR={core_root}", "-DPIX_EMBEDDED_ASSETS=ON",
        f"-DPIX_SOURCE_REVISION_OVERRIDE={pix_revision}",
        f"-DPIX_DT181_FIRMWARE_FILE={stage / 'firmware/dt181.bin'}",
        f"-DPIX_DT295_FIRMWARE_FILE={stage / 'firmware/dt295.bin'}",
        f"-DPIX_CORE_REVISION={core_revision}")
    manifest_command = next((line for line in trace.splitlines()
                             if "tools/embed-assets.py" in line and "--source-revision" in line), None)
    revision_match = (re.search(r"--source-revision\s+[\"']?([0-9a-f]+(?:-dirty)?)",
                                manifest_command) if manifest_command else None)
    revision_argument = revision_match.group(1) if revision_match else None
    if revision_argument != pix_revision:
        raise SystemExit(f"pinned CMake did not pass PIX source revision to embedded manifest generator: {manifest_command!r}")
    if enclosing_revision in manifest_command:
        raise SystemExit("embedded manifest generator command uses enclosing DTV revision")

    manifest_dir = temporary / "embedded-assets"
    run("python3", str(stage / "tools/embed-assets.py"),
        "--dt181-firmware", str(stage / "firmware/dt181.bin"),
        "--dt295-firmware", str(stage / "firmware/dt295.bin"),
        "--output-dir", str(manifest_dir), "--source-revision", pix_revision,
        "--core-revision", core_revision, "--toolchain", "fixture")
    manifest = json.loads((manifest_dir / "pix_embedded_assets.json").read_text(encoding="ascii"))
    if manifest.get("source_revision") != pix_revision:
        raise SystemExit("embedded asset manifest does not record the PIX pin")
    if manifest.get("dtv_userland_core_revision") != core_revision:
        raise SystemExit("embedded asset manifest changed the pinned Core revision")

print(f"PASS: staged PIX manifest records {pix_revision}; enclosing DTV revision {enclosing_revision} is ignored")
