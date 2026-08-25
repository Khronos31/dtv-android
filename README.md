# dtv-android

Android TV and phone APKs for the Khronos31地デジ stack. Milestone 1 contains
the resident tuner (`mirakc`) and the resident EPGStation server. The
program-guide APK (`:epgstation`) is intentionally not part of this milestone.

| Display name | Gradle module | applicationId |
| --- | --- | --- |
| mirakc | `:mirakc` | `dev.khronos31.mirakc` |
| EPGStation Server | `:epgstation-server` | `dev.khronos31.epgstation.server` |

## Build

The checked-in `local.properties` points at the SDK used by this workspace:

```text
sdk.dir=/config/.tools/android-sdk
```

Use JDK 17 and an Android NDK r26 or newer. The Gradle task automatically picks
the newest installed NDK below `/config/.tools/android-sdk/ndk/`; set
`ANDROID_NDK_HOME` when building elsewhere. It invokes
`/config/GitHub/siano-userland/scripts/build-android.sh` for both ABIs, then
places the verified executables in the mirakc APK:

```sh
export JAVA_HOME=/config/.tools/jdk17
export ANDROID_NDK_HOME=/config/.tools/android-sdk/ndk/27.0.12077973
./gradlew :mirakc:assembleDebug :epgstation-server:assembleDebug
```

The native step fails with an explicit NDK or siano-userland path error when
those prerequisites are missing. The resulting debug APKs are under
`mirakc/build/outputs/apk/debug/` and
`epgstation-server/build/outputs/apk/debug/`.

The EPGStation task pins upstream `l3tnun/EPGStation` v2.10.0, builds the
server and client, builds `sqlite3` for each Android ABI, and stages the
JS payload under app-private storage at first launch. The Node.js-mobile
v16.17.0 launcher (`libepgstation-node.so`), `libnode.so`, `libc++_shared.so`,
and native addons are packaged as `jniLibs` and executed from
`nativeLibraryDir`. Android 10+ will not exec ELF copied into `filesDir`.
A network connection, host Node/npm, the NDK, and `patchelf` are required
the first time the payload is prepared. `patchelf` adds a `libnode.so`
dependency to the Node addons so Android's linker can resolve N-API.

The APKs are fat packages for `armeabi-v7a` and `arm64-v8a`. This includes the
32-bit-only Google TV Streamer userspace. `siano-ts` is built as an Android
Bionic PIE executable and is checked for `/system/bin/linker` or
`/system/bin/linker64`; it is not a musl/glibc binary. It is packaged as
`libsiano-ts.so` and exec'd from `nativeLibraryDir` (`extractNativeLibs=true`).
Android 10+ will not exec a copy placed in `filesDir`.

## mirakc

Launch `mirakc` to start its `connectedDevice` foreground service. The one
status screen shows USB permission, the listener address, current stream, and
the last error. Its controls are ordinary focusable Android buttons so TV
D-pad navigation does not depend on Leanback rows.

The service requests Android USB permission for these Siano IDs:

* `3275:0080`
* `187f:0600`
* `187f:0302`

The service retains the duplicated USB `ParcelFileDescriptor` while a stream
is active. A small in-app JNI launcher passes that descriptor as fd 3 to:

```text
siano-ts --channel N --firmware <filesDir>/isdbt_rio.inp --fd 3
```

The listener binds unauthenticated to `0.0.0.0:40772`. The configured
terrestrial channel list is T16, T21–T27, T30, T31, and T32, matching the
HAOS mirakc addon. The firmware asset is the linux-firmware
`isdbt_rio.inp` (MD5 `9b762c1808fd8da81bbec3e24ddb04a3`) and
`LICENCE.siano` is shipped beside it. It is not embedded in a `.so`.

### Implemented HTTP surface

These are the routes served for EPGStation (mirakc-compatible, not a
Mirakurun clone of `/api/config`):

* `GET /api/version` — Mirakurun-shaped `current` and `latest`.
* `GET /api/status` — `{}`.
* `GET /api/docs` — OpenAPI used by `mirakurun.Client`.
* `GET /api/channels` — configured GR list plus discovered services.
* `GET /api/services`, `GET /api/services/{id}`
* `GET /api/programs`, `GET /api/programs/{id}`
* `GET /api/services/{id}/programs`
* `GET /events` — SSE `epg.programs-updated` and `onair.program-changed`.
* `GET /api/tuners` — Siano tuner state.
* `GET /api/channels/GR/{channel}/stream` — raw MPEG-TS from `siano-ts`.
* `GET /api/services/{id}/stream` and `GET /api/programs/{id}/stream`.

On USB grant the service scans each configured GR channel (~16s) and
parses SDT/EIT from the TS. Live streams feed the same parser. Names use
ARIB STD-B24. recisdb, B-CAS, and ffmpeg stay out of this APK.
`/api/services/{id}/stream` and `/api/programs/{id}/stream` keep one
program. 12-seg MPEG-2 is MULTI2-scrambled. When an Identive/CCID
reader with a B-CAS card is granted USB permission, TS is piped through
libarib25 (Apache-2.0) before serving. Without a card, the clear 1-seg
H.264 on the same transponder is substituted. Recording UI remains EPGStation.

## EPGStation Server

This is an unofficial Android port of upstream
[l3tnun/EPGStation](https://github.com/l3tnun/EPGStation), pinned to v2.10.0.
It is not a Play listing. The APK starts a `dataSync` foreground service,
extracts the upstream server and client build into app-private
`filesDir`, and listens on port 8888. The status screen shows a QR code for `http://<LAN-IP>:8888/` so a
phone or PC can open the stock EPGStation UI. There is no TV program-guide
APK; D-pad operation of that SPA is out of scope. The only other user
setting is the Mirakurun/mirakc base URL, persisted with this default:

```text
http://127.0.0.1:40772/
```

On every service start, the app copies the complete upstream
`config/config.yml.template` to `config/config.yml`, then changes only the
Mirakurun URL, `port`, `clientSocketioPort`, and recorded/thumbnail
locations. Recordings can go on internal storage or a removable USB volume
(exFAT). The SQLite database stays on internal `filesDir`. The status screen
lists each volume with free space; the app-specific directory on the USB is
`/storage/<UUID>/Android/data/dev.khronos31.epgstation.server/files/recorded`.
No `subDirectory` is added. Sample log YAML files and the
upstream `enc.js` templates are included. ffmpeg is intentionally not
bundled in this slice, so unconverted live operation is the supported path.

The resident supervisor holds a partial wake lock, execs the ABI-matched
`libepgstation-node.so` from `nativeLibraryDir` (`extractNativeLibs=true`),
restarts it with backoff after a crash, and reports the running/down state
in its notification. sqlite3 and `@node-rs/crc32` addons are the same
extracted native libraries, exposed to Node through symlinks under
`node_modules`. The APK contains EPGStation's MIT license, the Node license,
and a generated `licenses/NOTICE.npm.txt` dependency list. It contains no
`siano-ts`, firmware, recisdb, or guide UI.

Live viewing, one-tap recording, playback, and the program guide remain the
responsibility of [epcltvapp](https://github.com/daig0rian/epcltvapp); these APKs
do not replace it.
