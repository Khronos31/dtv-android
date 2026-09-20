# Legacy server rescue rehearsal

This is a staging-only rollback rehearsal and is separate from the final
`device-evidence.py` candidate matrix. It must be completed before installing a
0.3.0 candidate on the production device.

The rescue uses the same package and production signing certificate as the
candidate, but its identity is deliberately reserved: versionName `0.3.1`,
versionCode `301`, and artifact/asset names containing
`legacy-server-rescue`. It contains the legacy server from annotated
`mirakc-v0.2.0`, peeled commit
`5a4d647c9e4b46f3f637165fa107f87d34ea22ed`, and the pinned
`siano-userland` `v0.1.1`, peeled commit
`1a22a7180abd6c7be1d1dda6b866ec321a4e28ab`. It is not a normal 0.3.1 release;
future native releases must start at 0.3.2 or later.

The signed-candidate workflow refuses to create this pair unless the selected
candidate checkout reads `mirakc/VERSION` as `0.3.0`. It builds the legacy
checkout in a temporary archive, adds the explicit
`BUILD_INFO-legacy-server-rescue.txt` asset, signs candidate and rescue with
the same keystore, and verifies package/version/code/certificate for both.

## Staging command

Install neither APK manually before the rehearsal. The staging user-build
device must already have the production-signed 0.2.0 package and representative
data. Confirm exactly one online adb device, then run:

```sh
python3 tools/mirakc/rescue-rehearsal.py \
  --serial DEVICE_SERIAL \
  --candidate mirakc-signed-candidate.apk \
  --rescue mirakc-signed-legacy-server-rescue-0.3.1.apk \
  --plan staging-rescue-plan.json \
  --aapt2 "$ANDROID_SDK_ROOT/build-tools/VERSION/aapt2" \
  --apksigner "$ANDROID_SDK_ROOT/build-tools/VERSION/apksigner" \
  --out evidence/rescue-YYYY-MM-DD
python3 tools/mirakc/verify-rescue-rehearsal.py \
  evidence/rescue-YYYY-MM-DD \
  --candidate mirakc-signed-candidate.apk \
  --rescue mirakc-signed-legacy-server-rescue-0.3.1.apk
```

The plan contains only observed routes; the script accepts loopback API paths,
not arbitrary URLs:

```json
{
  "services_path": "/api/services",
  "stream_path": "/api/services/<observed-S1UD-service>/stream"
}
```

The harness first proves the installed package is version 0.2.0, code 200,
production-signed, and has a non-empty services response plus at least ten
clear, aligned MPEG-TS packets. It starts the legacy `MainActivity` and polls
`/api/version` plus non-empty `/api/services` until ready. It then uses only
`adb install -r` for exact candidate (300) followed by rescue (301), preserving
firstInstallTime and application data. The candidate is started and must report
upstream `/api/version` `current=3.4.86` before rescue is attempted; legacy
and rescue use the pinned legacy server's `current=3.4.82`; all three
readiness observations are recorded. A candidate's first-run namespace may
wait for upstream initial jobs; allow up to roughly 15 minutes (the default
`--ready-timeout` is 960 seconds). Each install records a reproducible
`adb -s SERIAL install -r SHA256/basename` command, package-manager
stdout/stderr, and return code. It verifies the embedded rescue provenance
asset, requires an exact canonical `/api/services` snapshot match before and
after, and rechecks S1UD stream integrity. Readiness/install/http timeout
budgets are separate CLI options and readiness is poll-based, not a fixed
sleep. It never calls uninstall, `pm clear`, or `adb install -d`; no package
cleanup is performed, so a successful run leaves the rescue installed.
Forwarding uses `adb forward --no-rebind` and is removed afterward. A
pre-existing forward or an unreadable/empty route is a hard failure. USB
permission or stream recovery that cannot be observed is also a hard failure;
rerun only after the operator has restored the staging condition.

The receipt verifier rechecks package states, firstInstallTime continuity,
candidate version readiness, complete before/candidate/after snapshots, clear
188-byte-aligned TS summaries, lowercase APK digests, and pinned rescue
provenance. `CANDIDATE_BUILD_INFO.txt` and `RESCUE_BUILD_INFO.txt` remain
separate in the signed artifact; `BUILD_INFO.json` is the namespaced combined
record.

If an install fails after the candidate was accepted, the receipt is fail
closed and records the reached package state. The device is not automatically
uninstalled or data-reset; the operator must use the same-key rescue APK or
rerun the rehearsal before proceeding.
