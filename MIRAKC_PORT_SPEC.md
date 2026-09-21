# mirakc Android port specification

Status: Draft; Phase 0 feasibility is complete; remaining acceptance evidence is open
Date: 2026-09-13

## Objective

Replace the Kotlin Mirakurun-compatible server in the `mirakc` APK with an
Android port of upstream mirakc, while keeping Android-specific lifecycle and
USB-permission handling in a thin Kotlin/JNI supervisor.  Package the latest
stable releases of both tuner backends so one APK can operate either Siano RIO
devices or a PLEX PX-Q3U4 without a kernel driver.

The pinned inputs for the first port are:

- mirakc `3.4.86` (`fc9610f51f8621aa8db508ddd36c7f1e2785d7be`);
- mirakc-arib `0.24.38` (`e85e1f991aa91ba0e6c6e02d14a17d159901e181`);
- siano-userland `v0.1.5` (`d4f8930ab56d13c479037f2e242461062d96c127`);
- px4-userland `v0.1.3` (`639e65feee7c9f503d44023edd9ab9bba12d5d74`).

`latest` means the newest non-prerelease release/tag observed on 2026-09-13.
Builds remain reproducible by pinning these exact refs rather than following a
moving branch.

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
   upstream mirakc, `GET /api/version` reports `3.4.86`, and stopping/restarting
   the service leaves no mirakc, mirakc-arib, siano or px4 child processes.
   Across ten tune/job/stop/reconnect cycles, `/proc/<pid>/fd` confirms that
   mirakc and unrelated children inherit no USB or smart-card descriptors and
   only the active owner holds each descriptor.
6. With a Siano tuner and external CCID B-CAS reader, service scan produces at
   least one service and EPGStation can play a service stream.  A captured
   12-seg stream is descrambled and accepted by `ffprobe`.
7. With a PX-Q3U4, Android grants both bridge permissions, one `px4d` owns both
   descriptors, mirakc exposes eight tuners (four GR and four BS/CS), and at
   least one GR and one BS service stream pass MPEG-TS integrity checks.  The
   built-in card path descrambles 12-seg content.  Verified on the connected
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
- Publish a release, create a tag, push commits, install an APK, or restart a
  Home Assistant/add-on service in this implementation phase.
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
- `px4d` is the only owner of a Q3U4 pair.  mirakc tuner commands use `px4-ts`
  over px4-userland's versioned local IPC.
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
| `mirakc/mirakc` 3.4.86 | adapt/port | Canonical server.  Its pinned Rust workspace is the source for the Android build; Android packaging and descriptor inheritance are not upstream features. |
| `mirakc/mirakc-arib` 0.24.38 | adapt/port | Canonical companion commands used by mirakc jobs and filters.  The pinned source and submodules are built for Android by the repository harness. |
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
   server path, cross-build the exact, unpruned `0.24.38` source and submodules
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
6. **PX4 path.** Start one `px4d` from the Q3U4 bridge pair, expose eight mirakc
   tuner entries through `px4-ts`, and add a decode-filter card adapter over
   portable IPC.  Verify offline config/process tests, then the hardware matrix.
   Risk: bridge pairing, LNB state, or card ownership survives a failed child.
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
concurrency evidence that GR, satellite and EPG jobs share the single px4d
owner correctly.  The host parser test can be run with
`./tools/mirakc/test-px4-tune-plan.sh`.

The two least-known mandatory components were increments 1 and 2.  Both Phase 0
feasibility gates are complete; remaining acceptance evidence is still required.

## Rollback

Before implementation, record the starting commit
`5a4d647c9e4b46f3f637165fa107f87d34ea22ed`.  Keep all migration changes as
reviewable commits after the specification.  Source rollback reverts those
commits without rewriting history.

The released `0.2.0` APK is not an on-device rollback mechanism: Android user
builds reject a lower `versionCode`, and uninstalling would clear application
data.  After review, the user decided that the migration release does **not**
require a same-key forward-versioned rescue APK or a rescue rehearsal.
Returning to `0.2.0` is an operator task: uninstall the candidate and reinstall
`0.2.0`; configuration retention across that rollback is accepted and is not a
release blocker.

The previously added rescue helpers (`tools/mirakc/build-legacy-rescue.sh`,
`tools/mirakc/rescue-rehearsal.py`, `tools/mirakc/verify-rescue-apk.py`,
`tools/mirakc/verify-rescue-rehearsal.py`, `tools/mirakc/verify-rescue-build-info.py`
and `tools/mirakc/RESCUE-REHEARSAL.md`) remain in the repository as
**non-required, outside the normal release path**.  The signed-candidate and
release workflows do not build, sign, verify, or publish a rescue APK, and the
annotated-tag attestation no longer carries rescue fields.  The reserved rescue
identity stays `0.3.1`/301 (never a normal `0.3.1` release; future native
releases start at `0.3.2` or later) for anyone who chooses to exercise the
optional helpers.
