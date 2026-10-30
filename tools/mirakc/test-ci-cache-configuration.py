import argparse
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[2]
workflow = (root / ".github/workflows/ci.yml").read_text()
gradle = (root / "mirakc/build.gradle.kts").read_text()

def section(start, end):
    return workflow.split(start, 1)[1].split(end, 1)[0]

mirakc_job = section("  mirakc:\n", "  mirakc-native-clean-room:\n")
cold_job = section("  mirakc-native-clean-room:\n", "  epgstation-server:\n")
arguments = argparse.ArgumentParser()
arguments.add_argument("--workflow-only", action="store_true")
workflow_only = arguments.parse_args().workflow_only

for task in (
    ":mirakc:test",
    ":mirakc-ui-contract:test",
    ":updater:test",
    ":mirakc:lintDebug",
    ":mirakc:assembleDebug",
):
    if task not in mirakc_job:
        raise SystemExit(f"normal mirakc Gradle invocation omits {task}")
if "gradle/actions/setup-gradle@v4" not in mirakc_job:
    raise SystemExit("normal mirakc job does not use setup-gradle v4")
policy = re.search(r"cache-read-only:\s*\$\{\{\s*(.*?)\s*\}\}", mirakc_job)
expected_policy = "github.event_name == 'pull_request' && github.event.pull_request.head.repo.full_name != github.repository"
if policy is None or policy.group(1) != expected_policy:
    raise SystemExit("Gradle cache write policy is not explicit")
policy_cases = (
    ("push", "refs/heads/main", "Khronos31/dtv-android", "Khronos31/dtv-android", False),
    ("pull_request", "refs/pull/34/merge", "Khronos31/dtv-android", "Khronos31/dtv-android", False),
    ("pull_request", "refs/pull/1/merge", "contributor/dtv-android", "Khronos31/dtv-android", True),
)
for event, ref, head_repository, repository, expected in policy_cases:
    read_only = event == "pull_request" and head_repository != repository
    if read_only is not expected:
        raise SystemExit(f"Gradle cache policy truth table failed for {event} {ref}")
if "cargo/registry/index" not in mirakc_job or "cargo/registry/cache" not in mirakc_job:
    raise SystemExit("Cargo cache omits registry download material")
if "cargo/registry/src" not in mirakc_job or "cargo/git/db" not in mirakc_job or "cargo/git/checkouts" not in mirakc_job:
    raise SystemExit("Cargo cache omits locked dependency sources")
for excluded in ("cargo/credentials", "cargo/config", "cargo/target", "autotools-build"):
    if excluded in mirakc_job:
        raise SystemExit(f"cache scope includes excluded material: {excluded}")
if "branches: [main]" not in workflow:
    raise SystemExit("push trigger is not deduplicated to main")
if "needs: mirakc" not in cold_job or "MIRAKC_REQUIRE_NETWORK_NAMESPACE: required" not in cold_job:
    raise SystemExit("cold proof is not a required isolated dependent job")
if "continue-on-error" in cold_job or "if: ${{" in cold_job:
    raise SystemExit("cold proof job is optional or conditionally skipped")
if "mirakc-corresponding-source" not in cold_job or "mirakc-debug-apk" not in cold_job:
    raise SystemExit("cold proof does not download the normal job's exact artifacts")
if "test-source-native-rebuild.sh" not in cold_job:
    raise SystemExit("cold proof job omits the full native rebuild gate")
if any(token in cold_job for token in ("setup-gradle", "actions/cache", "CARGO_HOME", "GRADLE_USER_HOME")):
    raise SystemExit("cold proof job must not restore normal build caches")
if "mirakc:test" in cold_job or "epgstation" in cold_job:
    raise SystemExit("cold proof job target scope expanded")
if "actions/setup-python@v6" not in cold_job or 'python-version: "3.13"' not in cold_job:
    raise SystemExit("cold proof does not provision the Python version required by the archive audit")

provision = mirakc_job.index("Provision pinned mirakc source checkouts before cache lookup")
cargo_restore = mirakc_job.index("Restore Cargo dependency downloads")
cargo_fetch = mirakc_job.index("Fetch locked mirakc Cargo dependencies")
cargo_save = mirakc_job.index("Save validated Cargo downloads")
arib_source_restore = mirakc_job.index("Restore pinned ARIB source archives")
arib_prefix_restore = mirakc_job.index("Restore completed portable ARIB host tools")
arib_sources = mirakc_job.index("Acquire and verify pinned ARIB source archives")
arib_source_save = mirakc_job.index("Save verified ARIB source archives")
arib_tools = mirakc_job.index("Bootstrap and verify completed portable ARIB host tools")
arib_prefix_save = mirakc_job.index("Save verified portable ARIB host tools")
gradle_setup = mirakc_job.index("gradle/actions/setup-gradle@v4")
first_gradle_fixture = mirakc_job.index("Debug DTV deletion input fixture")
adapter_gradle_fixture = mirakc_job.index("Custom adapter Gradle task input fixture")
gradle_build = mirakc_job.index("Test, lint, and assemble debug")
package_source = mirakc_job.index("Build and audit corresponding source for checked-out commit")
if not (gradle_setup < first_gradle_fixture < adapter_gradle_fixture < provision < cargo_restore < cargo_fetch < cargo_save < arib_source_restore < arib_prefix_restore < arib_sources < arib_source_save < arib_tools < arib_prefix_save < gradle_build < package_source):
    raise SystemExit("pinned sources or unconditional cache materials are not prepared before consumers")
fetch_block = mirakc_job.split("      - name: Fetch locked mirakc Cargo dependencies\n", 1)[1].split("      - ", 1)[0]
if "cargo fetch --locked --manifest-path .work/mirakc-3.4.91/Cargo.toml" not in fetch_block or "if:" in fetch_block:
    raise SystemExit("Cargo cache miss does not unconditionally fetch the pinned lockfile")
if "tools/mirakc/prepare-ci-cache-material.py" not in mirakc_job:
    raise SystemExit("ARIB source acquisition/bootstrap helper is missing")
source_fetch_block = mirakc_job.split("      - name: Acquire and verify pinned ARIB source archives\n", 1)[1].split("      - ", 1)[0]
if "--fetch-only" not in source_fetch_block or "if:" in source_fetch_block:
    raise SystemExit("ARIB source archives are not unconditionally fetched and validated")
for save_step, successful_step in (
    ("Save validated Cargo downloads", "steps.cargo_fetch.outcome == 'success'"),
    ("Save verified ARIB source archives", "steps.arib_sources.outcome == 'success'"),
    ("Save verified portable ARIB host tools", "steps.arib_tools.outcome == 'success'"),
):
    save_block = mirakc_job.split(f"      - name: {save_step}\n", 1)[1].split("      - ", 1)[0]
    if "always()" not in save_block or successful_step not in save_block:
        raise SystemExit(f"{save_step} is not failure-safe and success-gated")
    if "github.event.pull_request.head.repo.full_name == github.repository" not in save_block:
        raise SystemExit(f"{save_step} permits untrusted pull-request cache writes")
for cache_prefix in ("cargo-downloads-", "arib-sources-", "arib-tools-"):
    if cache_prefix not in mirakc_job or "github.run_id" not in mirakc_job or "github.run_attempt" not in mirakc_job:
        raise SystemExit(f"immutable cache update key is missing for {cache_prefix}")
if "steps.arib_host_toolchain.outputs.fingerprint" not in mirakc_job:
    raise SystemExit("completed host tool cache key omits host toolchain fingerprint")
if "steps.mirakc_gradle_build.outcome" in mirakc_job:
    raise SystemExit("dependency cache save incorrectly depends on later Gradle success")

gradle_block = mirakc_job.split("      - name: Test, lint, and assemble debug\n", 1)[1].split("      - ", 1)[0]
for task in (":mirakc:test", ":mirakc-ui-contract:test", ":updater:test", ":mirakc:lintDebug", ":mirakc:assembleDebug"):
    if gradle_block.count(task) != 1:
        raise SystemExit(f"normal mirakc Gradle invocation must schedule {task} exactly once")
for token in ("--build-cache", "-PciNativeCache=true"):
    if token not in gradle_block:
        raise SystemExit(f"normal mirakc Gradle invocation omits {token}")
if "actions/upload-artifact@v4" not in mirakc_job or "name: mirakc-corresponding-source" not in mirakc_job or "name: mirakc-debug-apk" not in mirakc_job:
    raise SystemExit("normal job does not upload the exact artifacts consumed by cold proof")

if workflow_only:
    print("CI cache workflow structure and write-policy truth table: PASS")
    raise SystemExit(0)

for task in ("prepareMirakcBinary", "prepareMirakcAribBinaries"):
    block = gradle.split(f'tasks.register("{task}")', 1)[1].split("\n}", 1)[0]
    if "outputs.cacheIf(" not in block or "inputs.files(" not in block:
        raise SystemExit(f"{task} is missing declared inputs or scoped cache opt-in")
if gradle.count('outputs.cacheIf("CI native cache is enabled")') != 2:
    raise SystemExit("native task cache opt-in is not restricted to the two reviewed producers")
for token in (
    "validateMirakcNativeCacheInputs",
    "validateMirakcAribCacheInputs",
    "mirakcAribRecursiveGitlinks",
    "aribHostToolchain",
    "verifyCachedMirakcNativeBinaries",
):
    if token not in gradle:
        raise SystemExit(f"native cache validation/integrity declaration missing: {token}")

print("CI cache scope and cold-proof structure: PASS")
