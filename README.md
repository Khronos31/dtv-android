# dtv-android

Android TV and phone APKs for the same stack as the HAOS addons: **mirakc**, **EPGStation Server**, and later **EPGStation** (guide + reservations only).

This repository does not replace [epcltvapp](https://github.com/daig0rian/epcltvapp). Live viewing, one-tap recording, and playback stay there.

| Display name | applicationId | Role |
|---|---|---|
| mirakc | `dev.khronos31.mirakc` | Resident tuner. Bundles [siano-userland](https://github.com/Khronos31/siano-userland). No settings. |
| EPGStation Server | `dev.khronos31.epgstation.server` | Resident. Mirakurun URL is the only setting. |
| EPGStation | `dev.khronos31.epgstation` | Program guide and recording reservations. Not started yet. |

Sideload fat APKs (`armeabi-v7a` + `arm64-v8a`). Not a Play Store listing. Firmware `isdbt_rio.inp` is shipped as a file, not baked into a `.so`.
