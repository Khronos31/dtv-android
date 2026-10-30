#!/bin/sh
# SPDX-License-Identifier: GPL-2.0-or-later
set -eu

source_dir=$1
destination=$2
work_root=$3

source_dir=$(CDPATH='' cd -- "$source_dir" && pwd -P)
work_root=$(CDPATH='' cd -- "$work_root" && pwd -P)
case "$destination" in
    /*) ;;
    *) destination=$PWD/$destination ;;
esac
destination=$(realpath -m -- "$destination")
case "$destination" in
    "$work_root"/*) ;;
    *) printf '%s\n' "ARIB build source must be under $work_root: $destination" >&2; exit 1 ;;
esac
[ "$destination" != "$source_dir" ] || { printf '%s\n' 'ARIB build source cannot alias immutable source' >&2; exit 1; }
[ -d "$destination" ] || { printf '%s\n' "ARIB build source directory must already exist: $destination" >&2; exit 1; }
[ ! -L "$destination" ] || { printf '%s\n' "ARIB build source must not be a symlink: $destination" >&2; exit 1; }
[ -z "$(find "$destination" -mindepth 1 -maxdepth 1 -print -quit)" ] \
    || { printf '%s\n' "ARIB build source directory must be empty: $destination" >&2; exit 1; }
archive=$(mktemp "$work_root/mirakc-arib-source.XXXXXX.tar")
trap 'rm -f -- "$archive"' EXIT
git -C "$source_dir" archive --format=tar HEAD > "$archive"
tar --extract --file="$archive" --directory="$destination"
# The command is evaluated by each submodule, not this shell.
# shellcheck disable=SC2016
MIRAKC_ARIB_STAGE_DESTINATION=$destination \
MIRAKC_ARIB_STAGE_SOURCE=$source_dir \
    git -C "$source_dir" submodule foreach --quiet --recursive '
    target=$MIRAKC_ARIB_STAGE_DESTINATION/$displaypath
    mkdir -p "$(dirname -- "$target")"
    if [ -d "$target" ]; then rmdir "$target"; fi
    git -c init.defaultBranch=main -c protocol.file.allow=always clone --quiet --shared --no-checkout \
        "$MIRAKC_ARIB_STAGE_SOURCE/$displaypath" "$target"
    git -C "$target" checkout --quiet --detach "$(git -C "$MIRAKC_ARIB_STAGE_SOURCE/$displaypath" rev-parse HEAD)"
'
