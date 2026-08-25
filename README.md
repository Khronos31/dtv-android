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
payload under app-private storage at first launch. It also packages a
Node.js-mobile v16.17.0 Android runtime and launcher. A network connection,
host Node/npm, and the NDK are required the first time the payload is
prepared.

The APKs are fat packages for `armeabi-v7a` and `arm64-v8a`. This includes the
32-bit-only Google TV Streamer userspace. `siano-ts` is built as an Android
Bionic PIE executable and is checked for `/system/bin/linker` or
`/system/bin/linker64`; it is not a musl/glibc binary. At runtime it is copied
from the packaged native library directory to `filesDir`, marked executable,
and launched by the mirakc foreground service.

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

These are the routes actually served by this milestone:

* `GET /api/version` — Mirakurun-shaped `current` and `latest` version fields.
* `GET /api/status` — `{}` as mirakc does.
* `GET /api/channels` — the fixed GR channel list.
* `GET /api/tuners` — detected Siano tuner state (`types`, `isAvailable`,
  `isFree`).
* `GET /api/channels/GR/{channel}/stream` — raw MPEG-TS from `siano-ts`; both
  `T27` and `27` are accepted for the channel path.

The stream is intentionally raw TS. recisdb, B-CAS, service/program/EPG
decoding, recording, and playback are out of scope. The remaining Mirakurun
and mirakc routes return 404; no fake EPG or recording API is exposed.

## EPGStation Server

This is an unofficial Android port of upstream
[l3tnun/EPGStation](https://github.com/l3tnun/EPGStation), pinned to v2.10.0.
It is not a Play listing. The APK starts a `dataSync` foreground service,
extracts the upstream server and client build into app-private
`filesDir`, and listens on port 8888. The only user setting is the
Mirakurun/mirakc base URL, persisted with this default:

```text
http://127.0.0.1:40772/
```

On every service start, the app copies the complete upstream
`config/config.yml.template` to `config/config.yml`, then changes only the
Mirakurun URL, `port`, `clientSocketioPort`, and recorded/thumbnail
locations. The database is the upstream SQLite database under the same
app-private root; no `subDirectory` is added. Sample log YAML files and the
upstream `enc.js` templates are included. ffmpeg is intentionally not
bundled in this slice, so unconverted live operation is the supported path.

The resident supervisor holds a partial wake lock, starts the Android Bionic
Node launcher for the selected ABI, restarts it with backoff after a crash,
and reports the running/down state in its notification. The APK contains
EPGStation's MIT license, the Node license, and a generated
`licenses/NOTICE.npm.txt` dependency list. It contains no `siano-ts`,
firmware, recisdb, or guide UI.

Live viewing, one-tap recording, playback, and the program guide remain the
responsibility of [epcltvapp](https://github.com/daig0rian/epcltvapp); these APKs
do not replace it.
