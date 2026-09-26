<!-- SPDX-License-Identifier: Apache-2.0 -->
# Device evidence harness

`device-evidence.py` produces a fail-closed, candidate-bound receipt for the
hardware gates in `MIRAKC_PORT_SPEC.md`. It never installs or uninstalls an
APK and does not delete application data. The APK must already be installed by
the operator; the harness proves that the installed base APK has the exact
SHA-256 of the candidate file. The release binding is package
`dev.khronos31.mirakc`, versionName `0.3.2`, versionCode `302`, and signing
certificate SHA-256
`1fd02c94f29a5756ed1d560ac4ecb6813fa0b2e634473a6f31da210ffd5223c4`.

## Run order

1. Build and audit the exact candidate APK with `audit-apk.sh`.
2. Confirm that exactly one adb device is attached, or pass its explicit
   `--serial`.
3. Write a probe plan from the actual device's `/api/channels`, `/api/services`,
   and `/api/tuners` configuration. Mirakc stream paths still identify the
   observed service stream. EPGStation routes are not plan inputs: the harness
   uses the pinned v2.10.0 API shape (`/api/channels`, `/api/schedules`,
   `/api/streams/live/{channelId}/m2ts?mode=0`, and `/api/streams` GET).
4. Run the harness. It starts the non-exported service through the exported
   `MainActivity`, stops the package with shell-safe `am force-stop`, and uses
   a non-rebinding adb forward for fixed mirakc port 40772 (and the explicitly
   planned EPGStation port), then
   writes `receipt.json`, `receipt-manifest.json`, and
   `receipt-manifest.sha256` to a new output directory.
5. For the USB gate, pass `--interactive-usb`. Each of the ten cycles prompts
   for a physical detach and reconnect, and independently observes the planned
   `dumpsys usb` markers disappear and return. It does not accept a typed
   “passed” value.

Example (the paths below are illustrative only and must be replaced with
paths observed on the connected installation):

```sh
python3 tools/mirakc/device-evidence.py candidate.apk \
  --serial 192.0.2.10:5555 \
  --plan device-plan.json \
  --out evidence/2026-09-21-candidate \
  --interactive-usb
```

The plan must contain all of these fields; omitting any one makes its mandatory
check fail:

```json
{
  "cycles": {"tune_path": "/api/channels/GR/<observed>/stream", "minimum_bytes": 1880},
  "siano_12seg": {"services_path": "/api/services", "stream_path": "/api/services/<observed>/stream"},
  "q3u4": {"tuners_path": "/api/tuners"},
  "bs_cs_integrity": {
    "gr_stream_path": "/api/services/<observed-gr>/stream",
    "bs_stream_path": "/api/services/<observed-bs>/stream",
    "cs_stream_path": "/api/services/<observed-cs>/stream"
  },
  "concurrency": {
    "gr_path": "/api/services/<observed-gr>/stream",
    "satellite_path": "/api/services/<observed-bs>/stream"
  },
  "usb_device_markers": ["<observed dumpsys usb VID/PID or device marker>"],
  "epgstation": {"host_port": 8888}
}
```

Each cycle observes the actual `scan-services` or `collect-eits` child process
from immediately after service start through its exit; a generic non-empty HTTP
response is never a job proof. The initial tune and every recovery tune require
at least ten packet-aligned MPEG-TS packets, valid sync bytes, and clear
scrambling control. After each USB transition it starts mirakc, verifies
`/api/version`, retunes, checks TS alignment, and records orphan cleanup.

The diagnostics provider is exported only for this acceptance path, but it
requires both `android.permission.DUMP` and the actual adb shell UID; a normal
application (even one holding DUMP) cannot call it. EPGStation is started
through its exported `MainActivity` only when its package was not already
running; an already-running package is left running and is not
force-stopped during cleanup. If the pre-state cannot be observed, the check
fails closed. A package started by the harness is stopped with `am force-stop`;
its package is `dev.khronos31.epgstation.server` and its device port is fixed at
8888. The harness checks the canonical v2.10.0 JSON shapes:
non-empty channel items, a schedule program covering the current time, and a
live M2TS response for an observed channel. Before opening it, the active
stream list must be empty; otherwise the check refuses to affect another user.
While that response is open it requires the corresponding item in
`/api/streams`, then closes the connection and polls until that exact stream ID
disappears. No stop-all request is sent. It also captures `logcat -v
epoch` after the activity-start epoch and requires `event stream started`,
`done update channel`, and `done update programs`; old log lines cannot satisfy
the check. No arbitrary GET/HEAD route or existing database read substitutes
for these state transitions.

The mirakc endpoint is fixed at loopback port 40772; arbitrary base URLs are
not accepted. All forwards use `adb forward --no-rebind`; a host-port collision is a
fail-closed error rather than an overwrite of another client's forwarding.

These routes and shapes are pinned against
`.work/EPGStation-v2.10.0/src/model/service/api/{channels,schedules,streams}.ts`,
`src/model/service/api/streams/live/{channelId}/m2ts.ts`, and
`api.d.ts`; the update markers come from
`src/model/epgUpdater/{EPGUpdater,EPGUpdateManageModel}.ts`.

The Siano and BS/CS checks capture MPEG-TS, verify 188-byte sync and selected
PIDs' `scrambling_control == 0`, and run `ffprobe` requiring MPEG-2 video and
AAC. The Q3U4 check filters the full `/api/tuners` inventory to entries named
`PX4-*`, then requires exactly eight PX4 entries: four `PX4-GR-*` and four
`PX4-S-*`, with exclusive GR versus BS/CS types. Siano entries may coexist and
are not counted as PX4 evidence. Concurrent probes also require exactly one
observed `libpx4d.so` owner. Native/APK/installed sizes, cold-start,
idle-PSS, and PSS totals for every tracked child during an observed EPG job are
recorded from the candidate and device.

The descriptor check opens the planned Siano and satellite streams, keeps both
HTTP responses open, and reads `/proc/<pid>/fd` through adb shell for every
observed mirakc/native process in that active snapshot. It requires
`libsiano-ts.so`, `libmirakc-b25-filter.so`, and exactly one `libpx4d.so`
owner, each with a unique USB/CCID target; the main mirakc/upstream
`libmirakc.so` process must own none. Main classification uses structured
`argv0` (exact app package/suffix or `libmirakc.so` basename), not a package
substring in a child path. Production user-build SELinux may deny the shell
read. The candidate therefore also exposes an app-internal diagnostics
provider at the fixed `content://dev.khronos31.mirakc.diagnostics/fds` URI,
protected by the signature-level `android.permission.DUMP` permission and an
explicit adb shell-UID check. The upstream marker watcher polls at one-second
intervals to keep this acceptance-only trigger from adding a 10 Hz wakeup to
normal operation. It discovers only same-UID descendants of the candidate
package, with bounded process/fd counts and output, and accepts no
caller-supplied PID or path. The
same protected call creates a fixed app-private trigger marker consumed by the
running upstream mirakc JobManager, so the observed `collect-eits` child is
the real update-schedules job and can run while listener/stream checks remain
active. A denied or malformed provider response is still a mandatory failure;
the harness never treats unreadable descriptors as proof of safe ownership and
does not add a public LAN diagnostic endpoint.

The receipt status is `pass` only when every named check passes. The manifest
contains the candidate APK digest, receipt digest, every evidence-file digest,
and a separately published digest of the manifest itself. Keep all three
manifest/receipt files and the evidence directory together; changing any file
requires a new receipt.

Verify a saved receipt (and optionally the candidate again) with:

```sh
python3 tools/mirakc/verify-device-evidence.py \
  evidence/2026-09-21-candidate --apk candidate.apk
```
