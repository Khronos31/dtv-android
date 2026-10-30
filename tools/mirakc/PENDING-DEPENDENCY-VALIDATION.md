# Dependency refresh: required pre-release gates

This document tracks verification deferred by the 2026-10-08 and 2026-10-30
dependency refreshes. Source pins do not claim that this source revision builds
or works on hardware.

## Four-product refresh (2026-10-30)

| Component/input | Exact pin | Evidence boundary |
|---|---|---|
| px4-userland | `5c91e8c57b288c0f490061789829e42dbbf61b3d` | Core receiver-control patch SHA-256 `9da0cf46a7727a5c7e20de165babd0bc2d78e47c119cad38c1f2c00abeb10521`; deterministic host concurrency proof is tracked by `tools/mirakc/test-userland-concurrency.sh` and upstream discussion by [dtv-userland-core PR 121](https://github.com/Khronos31/dtv-userland-core/pull/121). This does not constitute tuner hardware validation. |
| siano-userland | `33c3468e7e10781ae6c7e9a5fd57a0c1b0825288` | daemon, ctl and TS programs are the packaging inventory; Core pin is `67420d1c909e9205841612a186ae4c7fbf4dd092`; nested PX4 pin is `90b08dfe79b0b40e393f015975c761863830866a`. |
| asicen-userland | `e2a86ca194b60f1111ecfa0a020cd0944c351c76` | daemon, ctl and TS programs; bundled PX4 reference is `dd1c1bea16a5871085d5f2a42c009364c1ec7345`; loader hash is recorded in APK/source metadata. Provisioning grants and uploads primary and sibling loaders sequentially before starting the complete runtime owner. Loader redistribution rights remain unresolved, and ASICEN hardware validation is not complete. |
| pix-userland | `66f5a731784d205d1d306edda9cc57071c087f8e` | daemon, ctl and TS programs; both embedded firmware bundles are hash checked. |
| dtv-userland-core | `67420d1c909e9205841612a186ae4c7fbf4dd092` | Shared product/adaptor source input; same-release card-session behavior remains unverified. |

The requested native APK inventory, corresponding-source bundle and clean-room
rebuild remain release gates. No new tuner hardware has been validated by the
packaging changes.

## Latest stable mirakc refresh (2026-10-30)

| Component | Exact pin | Integrity data |
|---|---|---|
| mirakc | 3.4.91, `4f0767e87b109cabd17ed1ded0311355244fa018` | Deterministic `git archive | gzip -n` SHA-256 `b0b4913c8c6451dff7be0dccc9423fb458d346857471e65c6574a7aabe6cc4f6`; `Cargo.lock` SHA-256 `005a492cfdd0f6d44dd78585359caa0aa28b3c78eb46aaa6c55b18854f78a8b8`. |
| mirakc-arib | 0.24.40, `312168e85b30941a1fc0344900fd543a036ea341` | `CMakeLists.txt` reports 0.24.40; recursive vendor gitlinks match the preceding release. |
| locked Git Cargo package | `utoipa-swagger-ui` 10.0.1 from `git+https://github.com/mirakc/utoipa?branch=fix-zip-v9.0#5243d0c6772b8585f2ffeabe31e941dab898b3eb` | Cargo 1.98.1 vendor tree SHA-256 `d5a9fc47a33afa3341f13e89633cb8e6be0b3fd7f5fe257d1f1ce7a611bb3381`; tree digest covers sorted relative paths and bytes, including `.cargo-checksum.json`. |

The exact mirakc and mirakc-arib patches must be validated on these checkouts
before release. API version checks must expect 3.4.91. The source archive
fixture must verify the lock-pinned Git vendor tree digest as well as registry
checksums, and reject Cargo config directives outside `[source]`.

## Historical dependency inventory (2026-10-08; current-state claims superseded by the table above)

| Component/input | Previous pin | New pin | Source identity / integrity data |
|---|---|---|---|
| mirakc | 3.4.86, `fc9610f51f8621aa8db508ddd36c7f1e2785d7be` | 3.4.88, `09664c2eefd0dccc38cabd3717b081ac84bf0833` | Deterministic `git archive | gzip -n` SHA-256 `5a7d1b46e46421c89abfaf1808d231ccacf5e1ec1a778daa2c6897c4de1c4703`; `Cargo.lock` SHA-256 `d31a12f832fcad3d916108910f1b2d23afdb665391fe2f912c01771a506e9842`. |
| mirakc-arib | 0.24.38, `e85e1f991aa91ba0e6c6e02d14a17d159901e181` | 0.24.39, `3266523d535b301141c533592a46d32617094439` | `CMakeLists.txt` reports 0.24.39. Its recursive vendor submodule refs are listed below. |
| px4-userland | v0.1.9, `cf38742618bb02db41a95def619fbff50e9eb0f3` | v1.0.0, `5c91e8c57b288c0f490061789829e42dbbf61b3d` | Initial 2026-10-08 finding: the then-current DTV receiver-control patch did not apply to v1.0.0. Superseded by the final Core patch recorded in the current-state table above. |
| Swagger UI archive embedded by utoipa-swagger-ui | 5.17.14, `481244d0812097b11fbaeef79f71d942b171617f9c9f9514e63acbe13e71ccdc` | 5.32.6 | Archive SHA-256 `b3c07e091559b59a833f66547eb1fc18f2896f96e3f1f953e2f1efb328aa3394`. This is the v5.32.6 archive referenced by utoipa-swagger-ui 10.0.1. |
| siano-userland | v0.1.9, `d1f4e42810d5a2023ff4a6c31f798cb381026693` | v1.0.0, `33c3468e7e10781ae6c7e9a5fd57a0c1b0825288` | Three-program Android packaging; integration behavior remains unverified. |
| asicen-userland | — | v1.0.0, `e2a86ca194b60f1111ecfa0a020cd0944c351c76` | Includes ASICEN's pinned vendored PX4 source and sequential loader/runtime provisioning requirements. |
| pix-userland | — | v1.0.0, `66f5a731784d205d1d306edda9cc57071c087f8e` | Build must set `PIX_EMBEDDED_ASSETS=ON`; actual reception remains unverified. |
| dtv-userland-core | — | v1.0.0, `67420d1c909e9205841612a186ae4c7fbf4dd092` | Shared same-release implementation; persistent card-session equivalence is unverified. |
| PX4 Android adaptation | initial 2026-10-08 finding | resolved for the current pin | The initial patch did not apply to px4-userland 1.0.0. That blocker was superseded by the final Core receiver-control patch, SHA-256 `9da0cf46a7727a5c7e20de165babd0bc2d78e47c119cad38c1f2c00abeb10521`, with deterministic host proof and PR 121. This history is not a current build blocker or hardware-validation claim. |

mirakc-arib v0.24.39 records the same vendor gitlinks as v0.24.38. The root
vendor refs (path = commit) are: `vendor/aribb24` =
`654026e064ec127beff43bf3ecdcebd54119be15`, `vendor/cppcodec` =
`bd6ddf95129e769b50ef63e0f558fa21364f3f65`, `vendor/docopt` =
`2df2b1cd28a870c810da2e0fcffbb97c9f89708e`, `vendor/fmt` =
`407c905e45ad75fc29bf0f9bb7c5c2fd3475976f`, `vendor/google-benchmark` =
`0d98dba29d66e93259db7daa53a9327df767a415`, `vendor/googletest` =
`52eb8108c5bdec04579160ae17225d66034bd723`, `vendor/libisdb` =
`1d73edac60f918d6ea777cd15793b885d14d5a87`, `vendor/rapidjson` =
`24b5e7a8b27f42fa16b96fc70aade9106cf7102f`, `vendor/spdlog` =
`79524ddd08a4ec981b7fea76afd08ee05f83755d`, and `vendor/tsduck-arib` =
`c400025b7d31e26c0c15471e81adf2ad50632281`. The nested refs are
`cppcodec/test/catch` = `15cf3caaceb21172ea42a24e595a2eb58c3ec960`,
`rapidjson/thirdparty/gtest` = `ba96d0b1161f540656efdaed035b3c062b60e006`, and
`libisdb/Thirdparty/fdk-aac` = `3f864cce9736cc8e9312835465fae18428d76295`.

The mirakc 3.4.88 lock updates `utoipa` 5.5.0 to 6.0.0,
`utoipa-swagger-ui` 9.0.2 to 10.0.1 and related locked crates. Upstream also
adds IPv6 ULA recognition in `mirakc-core/src/web/access_control.rs`. The
Android patch touches that same file for unavailable peer-info rejection, in
a separate function/hunk by static inspection. The mirakc-arib Android patch
also touches `CMakeLists.txt`; the upstream change in that file is the version
constant near the top, while the patch's hunks are elsewhere. The PX4 patch's
four target paths are not among the files changed between v0.1.9 and v0.1.10.
These are source-diff observations only, not patch-application or compiler
proofs.

## Required gates before the next release

The maintainer preparing the next release must run these gates on the exact
release commit (record its full Git commit ID), record the commit and artifact
hashes, and resolve every failure before signing or publishing:

1. Build and test both Android ABIs (arm64-v8a and armeabi-v7a), including the
   complete mirakc-arib graph and the patched PX4 payload. Run the repository's
   host tests, lint, version checks, native ELF checks, and complete source
   archive/package audits. Do not skip gates to accommodate the new versions.
2. Verify the mirakc Android patch and mirakc-arib Android patch apply cleanly
   to the exact selected upstream trees. Confirm the `utoipa` 6 / Swagger UI
   10 APIs compile and that the API version endpoint reports 3.4.91.
3. Rebuild every packaged native executable from the corresponding-source
   archive in the clean-room job for both ABIs; compare the full inventory and
   verify source manifest, recursive submodule refs, generated metadata, and
   APK source payload against the build inputs.
4. Run the signed-candidate CI and promotion audits on the exact release
   commit. Review the resulting source archive and signed APK before creating
   or publishing a release.
5. On the supported Android TV target, check installation/start/stop/restart,
   scan progress and saved-channel preservation, EPG/service operation, and
   cleanup after cancellation. Exercise PX4 Q3U4 multi-receiver operation,
   M1UR and S1UR where available, and mixed Siano/PX4 operation. The v0.1.10
   changes include Q3U4 disconnect cleanup, one-receiver tuning settle and
   satellite slot/TSID selection; the new behavior needs hardware coverage.
   Do not treat profile mapping or earlier v0.1.9 device evidence as proof for
   this refreshed payload.
6. Exercise the pinned Siano v0.1.10 path on Android TV with a Siano RIO tuner:
   use the actual APK pipe and B25 filter path to verify normal reception and
   reception while the consumer is stalled, then confirm normal-reception TS
   sync, TEI, and continuity. Stop/cancel while output is backpressured; retune
   while a TS packet is partially written and confirm packet framing across
   the channel boundary; disconnect the USB tuner while output is pending.
   At exit, confirm child-process termination and USB cleanup, as well as the
   reported exit behavior. Also check CLI parsing for representative lower
   and upper `T13`..`T62` inputs on the exact release artifact. Upstream Termux
   results are reference evidence and must be recorded separately from Android
   APK integration results. These are required integration and device gates,
   not claims made by the static source inspection.

Host, source-package, metadata, and Cargo vendor tests were run for this pin
change. No Android APK/native build, CI workflow, APK audit, or device
operation was run. The Siano source diffs are observations only; compatibility
with the Android adapter and runtime behavior remain unverified.
