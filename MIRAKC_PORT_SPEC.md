# mirakc Android port specification

Status: Draft; Phase 0 feasibility is complete; remaining acceptance evidence is open
Date: 2026-10-02

## Objective

Replace the Kotlin Mirakurun-compatible server in the `mirakc` APK with an
Android port of upstream mirakc, while keeping Android-specific lifecycle and
USB-permission handling in a thin Kotlin/JNI supervisor. Package pinned,
published releases of both tuner backends so one APK can operate Siano RIO devices
and multiple PX4 enclosures without a kernel driver. The Android software
profile matrix covers every USB `DeviceProfile` in the pinned px4-userland
source: all 16 products listed in its v0.1.10 `identity.cpp` table, including
the DTV/e-Better counterparts. This is a software mapping scope, not a claim
that every model has passed Android hardware validation. The upstream README
and its per-model validation evidence remain authoritative for hardware status.

The dependency refresh pins the following published upstream tags (2026-10-08):

- mirakc `3.4.88` (`09664c2eefd0dccc38cabd3717b081ac84bf0833`);
- mirakc-arib `0.24.39` (`3266523d535b301141c533592a46d32617094439`);
- siano-userland `v0.1.10` (`89c240b8af021d55d81b3b90fce79a3690605811`);
- px4-userland `v0.1.10` (`7ad5f6691f77a9aa58c4097f8b6dfea77f6d9b6a`).

Builds remain reproducible by pinning these exact refs rather than following a
moving branch. The v0.1.10 Siano update has only been statically inspected;
integration and hardware behavior remain unverified.
The new pins have only been statically inspected in this change. Required
next-release verification is tracked in
[`tools/mirakc/PENDING-DEPENDENCY-VALIDATION.md`](tools/mirakc/PENDING-DEPENDENCY-VALIDATION.md).

## Acceptance criteria

1. `./gradlew --no-daemon :mirakc:assembleDebug` exits 0 from a clean checkout
   and builds every bundled native program from the four pinned source trees.
2. An APK inventory check finds Android API 24 PIE executables for every enabled
   ABI: `mirakc`, `mirakc-arib`, `siano-ts`, `px4d`, `px4-ts`, and `px4ctl`.
   `readelf` must show the Android loader, 16 KiB-compatible `PT_LOAD`
   alignment, no `RPATH`/`RUNPATH`, and only an explicit allow-list of Bionic
   shared libraries.
3. The APK contains the exact upstream licenses/notices and a machine-readable
   source manifest containing component name, version, commit and source URL.
   Each release also publishes a corresponding-source bundle containing the
   pinned source and submodules, every Android patch, generated build input,
   toolchain manifest, build script, and any object/relink material required by
   a statically linked LGPL dependency.  An independent clean-room job rebuilds
   every shipped native program using only that bundle.  CI fails if the
   manifest, source ref, binary version, license inventory, or rebuild differs;
   a documented license review remains a release gate.
4. `./gradlew --no-daemon test :mirakc:lintDebug` exits 0.  Existing tests and
   their expected values are not weakened or skipped.
5. On the Google TV Streamer, the Android foreground service starts the bundled
   upstream mirakc, `GET /api/version` reports `3.4.88`, and stopping/restarting
   the service leaves no mirakc, mirakc-arib, siano or px4 child processes.
   Across ten tune/job/stop/reconnect cycles, `/proc/<pid>/fd` confirms that
   mirakc and unrelated children inherit no USB or smart-card descriptors and
   only the active owner holds each descriptor.
6. With a Siano tuner and external CCID B-CAS reader, service scan produces at
   least one service and EPGStation can play a service stream.  A captured
   12-seg stream is descrambled and accepted by `ffprobe`.
7. Offline regression tests map every pinned PX4 product ID to its exact
   enclosure shape, serial rules, receiver count and T/S capabilities; each
   complete enclosure receives one independent owner. This verifies software
   mapping only. With a PX-Q3U4, Android grants both bridge permissions, one `px4d` owns both
   descriptors, mirakc exposes eight tuners (four GR and four BS/CS), and at
   least one GR and one BS service stream pass MPEG-TS integrity checks. The
   built-in card path descrambles 12-seg content. Verified on the connected
   PX-Q3U4 hardware.
8. EPGStation Server using `http://127.0.0.1:40772/` can scan channels, receive
   schedule updates and start/stop live streams without API-shape workarounds
   in EPGStation.
9. CI reports, for each ABI, native binary sizes, final APK download size,
   installed size, cold-start time, idle RSS and RSS during one EPG collection
   job.  Full mirakc-arib is kept unless those observations show a concrete
   device limit or regression; any pruning requires a separate recorded
   decision and behavior-equivalence tests.

## Non-goals

- Port mirakc-timeshift-fs/FUSE.
- Add a television-side program-guide UI.
- Change the EPGStation Server APK or its stored database.
- Add unsupported tuner hardware beyond the device matrices of the two pinned
  userland projects.
- Merge into `main`, create a tag, publish a release, install an APK, or restart
  a Home Assistant/add-on service in this implementation phase. Branch commits,
  pushes and draft PR creation are authorized for this task.
- Reimplement mirakc HTTP endpoints in Kotlin or Java.

## Constraints

- Preserve the current `dev.khronos31.mirakc` application ID and the update
  path from version `0.2.0`.
- Kotlin owns Android foreground-service lifecycle, status presentation,
  UsbManager permission flow and restart policy only.  Upstream mirakc owns the
  HTTP API, tuner arbitration, jobs, EPG store and streaming pipeline.
- Native executables run from `nativeLibraryDir`; Android-owned USB descriptors
  are duplicated to documented fixed descriptors by a descriptor-sanitizing
  native launcher.  The launcher closes every descriptor except stdio and an
  explicit per-process allow-list before `exec`.  Dedicated device-owner
  processes receive the USB/smart-card descriptors and expose local IPC to
  mirakc command adapters; mirakc itself receives no device descriptor.  Owner
  death or USB detach invalidates the IPC generation and triggers bounded
  teardown/reacquisition rather than reusing a stale descriptor.
- `px4d` is the only owner of one PX4 enclosure (a two-bridge Q3-family pair,
  or one single-USB device). Each enclosure has a stable model/product/serial-derived
  `--instance` token and a private runtime directory. mirakc tuner commands and
  PC/SC use that same token with px4-userland's versioned local IPC.
- The full upstream mirakc-arib command set is the baseline.  Size or feature
  reductions must not substitute the current Kotlin parser or invent a second
  wire/API compatibility layer.
- Do not modify existing tests to make a failing implementation pass.
- Do not touch `secrets.yaml`, `.ssh/`, `.storage/`, release signing material or
  production EPGStation data.
- Do not release, push, version-bump or install on a device without an explicit
  phase-boundary decision.

## Prior art

| Candidate | Classification | Evidence |
| --- | --- | --- |
| Former Kotlin server in this repository | adapt/reference | Reuse its Android lifecycle, UsbManager and proven CCID/libarib25 code.  It is no longer the APK's HTTP, EPG or stream implementation. |
| `mirakc/mirakc` 3.4.88 | adapt/port | Canonical server.  Its pinned Rust workspace is the source for the Android build; Android packaging and descriptor inheritance are not upstream features. |
| `mirakc/mirakc-arib` 0.24.39 | adapt/port | Canonical companion commands used by mirakc jobs and filters.  The pinned source and submodules are built for Android by the repository harness. |
| Linux/musl mirakc container binaries | reject | Wrong ABI/runtime for Bionic and cannot receive Android UsbManager descriptors. |
| Public Android mirakc ports | build | GitHub repository and code searches on 2026-09-13 found no maintained Android port to adopt. |
| `hassio-addons/mirakc` | adapt/reference | Reuse configuration and process topology concepts; its container/device access model cannot be adopted on Android. |

## Increment plan

1. **Android feasibility harness.** Cross-build pinned mirakc for armv7a and
   aarch64, run `--version` on Android, then start it with a no-tuner temporary
   config.  Verify loader, HTTP health, signals, filesystem paths and measured
   RSS.  The armv7a build, version/HTTP checks, SIGTERM shutdown and idle PSS
   measurement are green on the Google TV Streamer; aarch64 runtime remains
   unverified because that device exposes no 64-bit ABI.  Risk: Linux
   assumptions compile but fail at runtime.
2. **Mandatory mirakc-arib feasibility gate.** Before replacing any Kotlin
   server path, cross-build the exact, unpruned `0.24.39` source and submodules
   for armv7a, package and execute it from `nativeLibraryDir`, and pass upstream
   or behavior-equivalent fixtures for `scan-services`, `sync-clocks`,
   `collect-eits`, `filter-service`, and `filter-program`.  Measure one live
   Siano EPG job on the Google TV Streamer, including peak RSS, elapsed time and
   output validity.  Repeat compile/fixture checks for aarch64 in CI.  Any red
   result stops migration work; pruning is a later decision, not a workaround.
   Risk: vendor projects contain Linux-only assumptions or exceed armv7 device
   limits.
3. **Reproducible native supply chain.** Add source-ref inputs, build scripts,
   complete corresponding-source/relink bundle generation, source manifest,
   license inventory and ELF checks for all four pinned projects and their
   bundled dependencies.  Verify both ABIs and the clean-room rebuild in CI.
   Risk: stale local artifacts make Gradle appear green or published sources do
   not reconstruct the APK payload.
4. **Android supervisor and descriptor contract.** Replace the Kotlin HTTP/EPG
   implementation with a supervisor that generates config, launches native
   owner daemons through the descriptor-sanitizing launcher, passes only
   per-process allow-listed descriptors, and reaps the whole process group.
   mirakc commands communicate with device owners over generation-scoped local
   IPC, never inherited device FDs.  Verify process FD tables, detach/crash
   handling, ten immediate restarts, port release and orphan checks.  Risk:
   descriptor leakage or a restart loop monopolizes USB devices/port 40772.
5. **Siano path.** Wire a dedicated Siano owner and mirakc tuner adapter around
   `siano-ts`, plus an executable decode filter using a dedicated external CCID
   owner.  Verify real GR scan, EPG and 12-seg playback.  Risk: owner failure or
   backpressure corrupts the adapter stream.
6. **PX4 path.** Start one `px4d` per complete supported enclosure (Q3U4 bridge
   pair or single-USB model), expose its supported mirakc tuner entries through
   `px4-ts`, and add a decode-filter card adapter over portable IPC. Verify
   offline config/process tests, then the hardware matrix. Risk: bridge
   pairing, LNB state, or card ownership survives a failed child.
7. **End-to-end compatibility.** Run EPGStation and stream workflows, detach and
   reconnect USB, stop/restart the APK, and produce the final evidence receipt.
   Risk: individually green components fail under concurrent job/stream load.

### PX-Q3U4 satellite inventory increment (2026-09-20)

The first BS/CS increment extends the existing PX4 adapter with a host-testable,
fail-closed tune-plan parser.  PX-Q3U4 receiver IDs `2,3,6,7` remain the four
`[GR]` tuners; `0,1,4,5` are published as four `[BS, CS]` tuners.  The adapter
maps the 38 physical channels observed from a live NIT on 2026-09-20 and
always passes `--lnb-voltage 0`.  Every BS entry carries the observed TSID as
`extra-args: '--tsid=N'`; CS entries use the unpadded physical token and no
TSID override.

| type | physical channel tokens |
| --- | --- | --- |
| BS | `BS01_0`, `BS01_1`, `BS01_2`, `BS03_0`, `BS03_1`, `BS03_2`, `BS05_0`, `BS05_1`, `BS09_0`, `BS09_1`, `BS13_0`, `BS13_1`, `BS13_2`, `BS15_0`, `BS15_1`, `BS15_2`, `BS19_0`, `BS19_1`, `BS19_2`, `BS19_3`, `BS21_0`, `BS21_1`, `BS21_2`, `BS23_0`, `BS23_1`, `BS23_2` |
| CS | `CS2`, `CS4`, `CS6`, `CS8`, `CS10`, `CS12`, `CS14`, `CS16`, `CS18`, `CS20`, `CS22`, `CS24` |

BS entries use the observed TSID for `--stream-id`; CS and GR reject TSID
overrides.  Satellite requests follow
px4-userland's receiver/CLI contract and use explicit 0V LNB state; the
Japanese physical-channel-to-frequency mapping is separately implemented and
covered by this adapter's host tests.  HAOS is only a cross-check for that
mapping, not its source of truth.  No LNB power permission is enabled.  The
inventory is a static snapshot: future NIT changes require regenerating and
updating the inventory; no runtime auto-NIT requirement is established here.

This increment does not claim full satellite acceptance.  The remaining gate is
at least one BS and one CS integrity stream with card descrambling, and
concurrency evidence that GR, satellite and EPG jobs share each active px4d
owner correctly. The host parser test can be run with
`./tools/mirakc/test-px4-tune-plan.sh`.

### Android PX4 profile coverage (2026-10-04)

The 2026-10-04 mapping review covered all 16 product IDs in px4-userland v0.1.9
`DeviceProfile` table (`cf38742618bb02db41a95def619fbff50e9eb0f3`): Q3U4
`084a`, W3U4 `083f`, MLT5PE / DTV02A-5TS-P `024e` / `924e`, W3PE4/5
`023f` / `073f`, Q3PE4/5 `024a` / `074a`, MLT8PE3/5 `0252` / `0253`,
DTV02A-4TS-P `0254`, M1UR `0854`, S1UR `0855`, DTV03A-1TU `0052`,
DTV02-1T1S-U `004b`, and DTV02A-1T1S-U `084b` (all IDs use vendor `0511`).
The pinned source is the authority for USB profile identity and per-profile
bridge/receiver/T/S properties. Its README and validation table identify which
hardware profiles are verified or unverified; APK mapping does not upgrade
those statuses or claim Android hardware operation.

The 2026-10-08 dependency refresh selects px4-userland v0.1.10
(`7ad5f6691f77a9aa58c4097f8b6dfea77f6d9b6a`). Static inspection found the
same 16 profile IDs, but mapping/build/runtime compatibility against that tag
is not yet verified; see
[`tools/mirakc/PENDING-DEPENDENCY-VALIDATION.md`](tools/mirakc/PENDING-DEPENDENCY-VALIDATION.md).

Two-bridge Q3-family devices pair only within the exact same product profile,
by matching 14-digit base serial and suffixes `1`/`2`. Each single-bridge
profile uses its full 15-digit USB serial. Repeated same-profile serials,
malformed/incomplete pairs and duplicate Android USB paths are rejected.
MLT5PE and DTV02A-5TS-P deliberately retain the existing Android `mlt5` model
and tuner identities; their product IDs remain in the enclosure token so
runtime directories stay distinct.

Each complete enclosure receives one owning `px4d`, a model/product/serial-
derived runtime-safe `--instance`, and a private app runtime directory. The
generic JNI launcher passes one USB FD for single-bridge profiles, or two
distinct FDs for Q3-family pairs, as `--fd 3 [--fd 4]`. Pinned px4-userland
derives the daemon hardware profile from those opened USB descriptors. The
Android adapter's separate `--model` argument selects local tune validation
and retry capabilities; it is not forwarded to `px4-ts` or `px4d`. The same
instance token is used for `px4-ts` and the PX4 card endpoint.

The receiver map covers fixed-system Q3 (8) and W3 (4) layouts, 3/4/5-way
dual-system MLT profiles, single dual-system M1UR/DTV02 profiles and
terrestrial-only S1UR/DTV03 profiles. M1UR and DTV02 single-receiver satellite
requests remain limited to 0 V; S1UR/DTV03 satellite requests are rejected.
Retries stay within the selected model's receivers that support the requested
broadcast system; a failed/busy receiver is never silently replaced by another
physical receiver inside the adapter. Native PX4 control requests use one
bounded FIFO worker lane per logical receiver, plus the separate card lane, so
independent receivers can progress concurrently while one receiver's requests
remain serialized. Regression coverage must enumerate all 16 IDs, their exact
receiver counts/capabilities and bridge counts, invalid pair/serial cases,
mixed independent enclosures and detach isolation. These offline checks prove
only software mapping. Profile-specific Android reception, card operation,
descrambling and simultaneous-enclosure hardware validation remain separate,
explicitly unverified evidence; criterion 7 retains its PX-Q3U4 hardware gate.

### Android TV setup and runtime split (2026-10-05; 0.4.0)

The mirakc APK opens a Compose-based Android TV screen without starting the
public listener or native tuner stack. Initial terrestrial setup is an explicit
**Channel Scan** action. It requests permissions for detected tuners and CCID
readers, resumes the requested scan after grants, prepares any required PX4
firmware, then owns a scan-only mirakc runtime bound to
`127.0.0.1:40773`. That runtime uses separate cache/runtime paths and disables
the regular EPG startup jobs; native GR scan progress is persisted and shown in
the UI. It does not replace tuner scheduling, scan services, or SI parsing in
upstream mirakc.

Successful scan results are committed as prepared channel settings. An empty,
failed, canceled, or interrupted rescan preserves the prior good channel set.
If the first setup has no GR-capable tuner but does have a satellite tuner, the
app prepares the bundled BS 26 / CS 12 channels without fabricating a GR scan.
Manual GR scan concurrency is capped at the lesser of eight workers and the
number of enabled GR-capable receivers. A PX-Q3U4 therefore scans on four GR
receivers; the scan-only runtime has no viewing workload and reserves none for
viewing. Device capabilities and native tuner leases determine the available
receivers; model-name special cases do not assign the work.
After settings are prepared, the user explicitly starts the public runtime on
`0.0.0.0:40772`; that runtime owns the configured EPG jobs. The scan-only
process and its children are torn down before the public runtime is admitted.
EPGStation continues to use its existing default
`http://127.0.0.1:40772/` endpoint and its implementation is unchanged.

The Compose screen renders immutable typed state and dispatches typed actions
through the Android service/controller. Kotlin owns permission prompts,
firmware preparation, setup persistence and process lifecycle. Upstream mirakc
continues to own the HTTP/API surface, tuner leases, scan job, channel/service
discovery, SI parsing, EPG jobs and streams. The screen does not implement a
second tuner scheduler or scan engine.

These setup and lifecycle behaviors are implemented and covered by offline
controller, state-recovery, USB permission, and runtime-owner tests. The
connected-device scan-only proof reached 50/50 GR scan entries and preserved
the resulting settings, but public stream/recording workflows, process-kill
child cleanup, init-failure recovery, and the separate PX4 profile hardware
matrix remain explicit hardware acceptance gates. Television reboot does not
launch the APK automatically.

USB lifecycle broadcasts reconfigure upstream mirakc for supported Siano/PX4
tuners and CCID-reader attach/detach or permission changes. For a newly
attached device without permission, reconfiguration waits until permission is
granted. Unrelated USB accessories do not restart mirakc. A relevant attach,
detach, or permission change restarts mirakc and rotates the Siano/reader
generation; this may interrupt streams and recording/EPG jobs, including work
using another enclosure. Unchanged PX4 owners keep their `px4d` process and USB
descriptors, but this does not promise uninterrupted upstream service. A PX4
detach invalidates the owner's saved device path immediately, before
asynchronous reconfiguration, and drains any in-flight startup before the path
can be reused.

The two least-known mandatory components were increments 1 and 2.  Both Phase 0
feasibility gates are complete; remaining acceptance evidence is still required.

## Rollback

Before implementation, record the starting commit
`5a4d647c9e4b46f3f637165fa107f87d34ea22ed`.  Keep all migration changes as
reviewable commits after the specification.  Source rollback reverts those
commits without rewriting history.

The released `0.2.0` APK is not an on-device rollback mechanism: Android user
builds reject a lower `versionCode`, and uninstalling would clear application
data.  Returning to `0.2.0` is an operator task: uninstall the candidate and
reinstall `0.2.0`; configuration retention across that rollback is accepted
and is not a release blocker.
