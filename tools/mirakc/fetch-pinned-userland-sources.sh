#!/bin/sh
# SPDX-License-Identifier: GPL-2.0-or-later
set -eu

root=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd -P)
work=$root/.work

checkout()
{
    name=$1
    url=$2
    pin=$3
    destination=$work/pinned-$name
    if [ -d "$destination/.git" ] || [ -f "$destination/.git" ]; then
        head=$(git -C "$destination" rev-parse HEAD)
        [ "$head" = "$pin" ] || {
            printf '%s\n' "$name checkout at $destination has $head, expected $pin" >&2
            exit 1
        }
        if [ -n "$(git -C "$destination" status --porcelain --untracked-files=all)" ]; then
            printf '%s\n' "$name checkout is not clean: $destination" >&2
            exit 1
        fi
        return
    fi
    [ ! -e "$destination" ] || {
        printf '%s\n' "refusing to replace non-Git source path: $destination" >&2
        exit 1
    }
    mkdir -p "$work"
    git clone --no-checkout "$url" "$destination"
    git -C "$destination" fetch --depth=1 origin "$pin"
    git -C "$destination" checkout --detach "$pin"
}

checkout px4-userland https://github.com/Khronos31/px4-userland.git 5c91e8c57b288c0f490061789829e42dbbf61b3d
checkout siano-userland https://github.com/Khronos31/siano-userland.git 33c3468e7e10781ae6c7e9a5fd57a0c1b0825288
checkout asicen-userland https://github.com/Khronos31/asicen-userland.git e2a86ca194b60f1111ecfa0a020cd0944c351c76
checkout pix-userland https://github.com/Khronos31/pix-userland.git 66f5a731784d205d1d306edda9cc57071c087f8e
checkout dtv-userland-core https://github.com/Khronos31/dtv-userland-core.git 67420d1c909e9205841612a186ae4c7fbf4dd092
checkout siano-px4-userland https://github.com/Khronos31/px4-userland.git 90b08dfe79b0b40e393f015975c761863830866a

printf '%s\n' "Pinned userland sources are ready under $work/pinned-*"
