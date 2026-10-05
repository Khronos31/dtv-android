#!/bin/sh
# SPDX-License-Identifier: GPL-2.0-or-later
set -eu

root=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd -P)
: "${MIRAKC_PLEX_DRIVER_ZIP:?set MIRAKC_PLEX_DRIVER_ZIP to the pinned PLEX driver ZIP}"
: "${MIRAKC_PX4_REFERENCE_FIRMWARE:?set MIRAKC_PX4_REFERENCE_FIRMWARE to the reference it930x-firmware.bin}"
[ -f "$MIRAKC_PLEX_DRIVER_ZIP" ] || {
    printf '%s\n' "PLEX driver ZIP not found: $MIRAKC_PLEX_DRIVER_ZIP" >&2
    exit 2
}
[ -f "$MIRAKC_PX4_REFERENCE_FIRMWARE" ] || {
    printf '%s\n' "PX4 reference firmware not found: $MIRAKC_PX4_REFERENCE_FIRMWARE" >&2
    exit 2
}

cd "$root"
exec ./gradlew "$@" :mirakc:testDebugUnitTest --tests dev.khronos31.mirakc.Px4FirmwareAcquirerTest
