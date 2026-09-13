#!/bin/sh
# SPDX-License-Identifier: GPL-2.0-or-later
#
# Build the host autotools needed by aribb24 from pinned GNU release archives.
# The result is deliberately kept outside the repository so generated host
# tools cannot become part of the Android source or APK inputs.
set -eu

# The default is the persistent HAOS tools volume.  CI sets the root to its
# per-job RUNNER_TEMP directory; keeping one explicit root makes the safety
# check valid in both environments without permitting arbitrary host paths.
tool_root=${MIRAKC_ARIB_AUTOTOOLS_ROOT:-/config/.tools}
prefix=${MIRAKC_ARIB_AUTOTOOLS_PREFIX:-$tool_root/autotools}
cache_dir=${MIRAKC_ARIB_AUTOTOOLS_CACHE:-$tool_root/autotools-sources}
work_dir=${MIRAKC_ARIB_AUTOTOOLS_WORK:-$tool_root/autotools-build}
jobs=${MIRAKC_ARIB_AUTOTOOLS_JOBS:-}

fail()
{
    printf '%s\n' "autotools bootstrap: $*" >&2
    exit 1
}

case "$tool_root" in
    /*) ;;
    *) fail "MIRAKC_ARIB_AUTOTOOLS_ROOT must be an absolute path: $tool_root" ;;
esac
case "$prefix" in
    "$tool_root"/*) ;;
    *) fail "installation prefix must be under MIRAKC_ARIB_AUTOTOOLS_ROOT: $prefix" ;;
esac
case "$cache_dir" in
    "$tool_root"/*) ;;
    *) fail "source cache must be under MIRAKC_ARIB_AUTOTOOLS_ROOT: $cache_dir" ;;
esac
case "$work_dir" in
    "$tool_root"/*) ;;
    *) fail "build directory must be under MIRAKC_ARIB_AUTOTOOLS_ROOT: $work_dir" ;;
esac

for tool in curl sha256sum tar make; do
    command -v "$tool" >/dev/null 2>&1 || fail "$tool is required"
done

if [ -z "$jobs" ]; then
    jobs=1
    if command -v getconf >/dev/null 2>&1; then
        jobs=$(getconf _NPROCESSORS_ONLN 2>/dev/null || printf '1')
    fi
fi
case "$jobs" in
    ''|*[!0-9]*) fail "MIRAKC_ARIB_AUTOTOOLS_JOBS must be a positive integer" ;;
esac
[ "$jobs" -gt 0 ] || fail "MIRAKC_ARIB_AUTOTOOLS_JOBS must be positive"

# GNU release archives and their SHA-256 digests.  Keep this table explicit:
# changing a tool requires a reviewed script change and a new digest.
m4_version=1.4.19
m4_sha256=63aede5c6d33b6d9b13511cd0be2cac046f2e70fd0a07aa9573a04a82783af96
m4_url=https://ftp.gnu.org/gnu/m4/m4-1.4.19.tar.xz
autoconf_version=2.72
autoconf_sha256=ba885c1319578d6c94d46e9b0dceb4014caafe2490e437a0dbca3f270a223f5a
autoconf_url=https://ftp.gnu.org/gnu/autoconf/autoconf-2.72.tar.xz
automake_version=1.17
automake_sha256=8920c1fc411e13b90bf704ef9db6f29d540e76d232cb3b2c9f4dc4cc599bd990
automake_url=https://ftp.gnu.org/gnu/automake/automake-1.17.tar.xz
libtool_version=2.5.4
libtool_sha256=f81f5860666b0bc7d84baddefa60d1cb9fa6fceb2398cc3baca6afaa60266675
libtool_url=https://ftp.gnu.org/gnu/libtool/libtool-2.5.4.tar.xz

marker=$prefix/.mirakc-autotools-versions
if [ -f "$marker" ] \
    && [ "$(sed -n '1p' "$marker")" = "m4=$m4_version" ] \
    && [ "$(sed -n '2p' "$marker")" = "autoconf=$autoconf_version" ] \
    && [ "$(sed -n '3p' "$marker")" = "automake=$automake_version" ] \
    && [ "$(sed -n '4p' "$marker")" = "libtool=$libtool_version" ] \
    && [ -x "$prefix/bin/m4" ] && [ -x "$prefix/bin/autoreconf" ] \
    && [ -x "$prefix/bin/autoconf" ] && [ -x "$prefix/bin/automake" ] \
    && [ -x "$prefix/bin/aclocal" ] && [ -x "$prefix/bin/libtoolize" ]; then
    printf '%s\n' "autotools bootstrap: using $prefix"
    exit 0
fi

download()
{
    name=$1
    url=$2
    expected=$3
    archive=$cache_dir/$name.tar.xz
    mkdir -p "$cache_dir"
    if [ ! -f "$archive" ]; then
        temporary=$archive.tmp.$$
        trap 'rm -f "$temporary"' EXIT HUP INT TERM
        curl -fL --retry 3 --connect-timeout 30 -o "$temporary" "$url"
        mv "$temporary" "$archive"
        trap - EXIT HUP INT TERM
    fi
    printf '%s  %s\n' "$expected" "$archive" | sha256sum -c -
}

build_tool()
{
    name=$1
    version=$2
    url=$3
    expected=$4
    archive=$cache_dir/$name-$version.tar.xz
    source=$work_dir/$name-$version

    download "$name-$version" "$url" "$expected"
    rm -rf "$source"
    mkdir -p "$work_dir"
    tar -xJf "$archive" -C "$work_dir"
    [ -d "$source" ] || fail "archive did not contain $name-$version"
    (
        cd "$source"
        ./configure --prefix="$prefix"
        make -j "$jobs"
        make install
    )
}

mkdir -p "$prefix/bin"
# m4 must be first: autoconf, automake and libtool use it while configuring.
build_tool m4 "$m4_version" "$m4_url" "$m4_sha256"
PATH=$prefix/bin:$PATH
export PATH
build_tool autoconf "$autoconf_version" "$autoconf_url" "$autoconf_sha256"
build_tool automake "$automake_version" "$automake_url" "$automake_sha256"
build_tool libtool "$libtool_version" "$libtool_url" "$libtool_sha256"

temporary_marker=$marker.tmp.$$
trap 'rm -f "$temporary_marker"' EXIT HUP INT TERM
mkdir -p "$prefix"
{
    printf 'm4=%s\n' "$m4_version"
    printf 'autoconf=%s\n' "$autoconf_version"
    printf 'automake=%s\n' "$automake_version"
    printf 'libtool=%s\n' "$libtool_version"
} > "$temporary_marker"
mv "$temporary_marker" "$marker"
trap - EXIT HUP INT TERM
printf '%s\n' "autotools bootstrap: installed pinned tools in $prefix"
