# Dependency refresh: required pre-release gates

This document tracks verification deferred by the 2026-10-08 dependency
refresh. The pin update records published upstream inputs; it does not claim
that this source revision builds or works on hardware.

## Old-to-new provenance inventory

| Component/input | Previous pin | New pin | Source identity / integrity data |
|---|---|---|---|
| mirakc | 3.4.86, `fc9610f51f8621aa8db508ddd36c7f1e2785d7be` | 3.4.88, `09664c2eefd0dccc38cabd3717b081ac84bf0833` | Deterministic `git archive | gzip -n` SHA-256 `5a7d1b46e46421c89abfaf1808d231ccacf5e1ec1a778daa2c6897c4de1c4703`; `Cargo.lock` SHA-256 `d31a12f832fcad3d916108910f1b2d23afdb665391fe2f912c01771a506e9842`. |
| mirakc-arib | 0.24.38, `e85e1f991aa91ba0e6c6e02d14a17d159901e181` | 0.24.39, `3266523d535b301141c533592a46d32617094439` | `CMakeLists.txt` reports 0.24.39. Its recursive vendor submodule refs are listed below. |
| px4-userland | v0.1.9, `cf38742618bb02db41a95def619fbff50e9eb0f3` | v0.1.10, `7ad5f6691f77a9aa58c4097f8b6dfea77f6d9b6a` | DTV receiver-control patch remains SHA-256 `c60214256a40b03f3469b5a508f149c12e599282edf103875a2170b816c4b697`; upstream profile table still has 16 product IDs by static inspection. |
| Swagger UI archive embedded by utoipa-swagger-ui | 5.17.14, `481244d0812097b11fbaeef79f71d942b171617f9c9f9514e63acbe13e71ccdc` | 5.32.6 | Archive SHA-256 `b3c07e091559b59a833f66547eb1fc18f2896f96e3f1f953e2f1efb328aa3394`. This is the v5.32.6 archive referenced by utoipa-swagger-ui 10.0.1. |
| siano-userland | v0.1.9, `d1f4e42810d5a2023ff4a6c31f798cb381026693` | unchanged | Explicitly deferred; no v0.1.10 pin is inferred. |
| PX4 Android adaptation | unchanged patch | unchanged patch | Apply/build against v0.1.10 has not been checked. |

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
release commit, record the commit and artifact hashes, and resolve every
failure before signing or publishing:

1. Build and test both Android ABIs (arm64-v8a and armeabi-v7a), including the
   complete mirakc-arib graph and the patched PX4 payload. Run the repository's
   host tests, lint, version checks, native ELF checks, and complete source
   archive/package audits. Do not skip gates to accommodate the new versions.
2. Verify the mirakc Android patch and mirakc-arib Android patch apply cleanly
   to the exact selected upstream trees. Confirm the `utoipa` 6 / Swagger UI
   10 APIs compile and that the API version endpoint reports 3.4.88.
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

No patch-application command, test, build, CI workflow, APK audit, or device
operation was run as part of this dependency pin change.
