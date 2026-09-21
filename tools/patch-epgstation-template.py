#!/usr/bin/env python3
"""Patch the EPGStation payload for the Android build.

The APK ships a minimal LGPL ffmpeg + libopenh264 (see
tools/build-ffmpeg-lgpl.sh).  The upstream config uses libx264 (GPL) and
yadif-based live profiles.  This script rewrites:

- config/config.yml.template
  * libx264 -> libopenh264
  * drops x264-only flags (-preset veryfast, -profile:v baseline,
    -tune fastdecode,zerolatency)
  * live 720p/480p profiles lose yadif (deinterlace costs ~2x CPU on the
    TV Streamer's A55 cores); yadif variants are kept as "720p (yadif)"
    entries for faster devices
- config/enc.js.template (recorded encode)
  * libx264 -> libopenh264, CRF -> bitrate-based, drop -preset
- dist/*.js entrypoints
  * install a global fetch polyfill (nodejs-mobile v16.17.0 has no
    native fetch; EPGStation's EPG updater calls fetch())

The patch is idempotent: running it twice on the same tree is safe.
"""
import re
import sys
from pathlib import Path


def patch_config_template(text: str) -> str:
    # Global codec / flag replacements (idempotent).
    text = text.replace("libx264", "libopenh264")
    text = re.sub(r"\s*-preset\s+veryfast", "", text)
    text = re.sub(r"\s*-profile:v\s+baseline", "", text)
    text = re.sub(r"\s*-tune\s+fastdecode,zerolatency", "", text)

    live_start = text.index("stream:\n    live:")
    live_end = text.index("\n    recorded:", live_start)
    live = text[live_start:live_end]

    # Deinterlace-free defaults for live 720p/480p.
    live = live.replace("-vf yadif,scale=-2:720", "-vf scale=-2:720")
    live = live.replace("-vf yadif,scale=-2:480", "-vf scale=-2:480")

    # Keep yadif variants for faster devices, in m2tsll and hls.
    m2tsll_yadif = """
                - name: 720p (yadif)
                  cmd:
                      '%FFMPEG% -dual_mono_mode main -f mpegts -analyzeduration 500000 -i pipe:0 -map 0 -c:s copy -c:d
                      copy -ignore_unknown -fflags nobuffer -flags low_delay -max_delay 250000 -max_interleave_delta 1
                      -threads 0 -c:a aac -ar 48000 -b:a 192k -ac 2 -c:v libopenh264 -flags +cgop -vf yadif,scale=-2:720
                      -b:v 3000k -y -f mpegts pipe:1'
                - name: 480p (yadif)
                  cmd:
                      '%FFMPEG% -dual_mono_mode main -f mpegts -analyzeduration 500000 -i pipe:0 -map 0 -c:s copy -c:d
                      copy -ignore_unknown -fflags nobuffer -flags low_delay -max_delay 250000 -max_interleave_delta 1
                      -threads 0 -c:a aac -ar 48000 -b:a 128k -ac 2 -c:v libopenh264 -flags +cgop -vf yadif,scale=-2:480
                      -b:v 1500k -y -f mpegts pipe:1'
"""
    marker = "\n            webm:"
    if marker in live and "720p (yadif)" not in live:
        live = live.replace(marker, m2tsll_yadif + marker, 1)

    hls_yadif = """
                - name: 720p (yadif)
                  cmd:
                      '%FFMPEG% -re -dual_mono_mode main -i pipe:0 -sn -map 0 -threads 0 -ignore_unknown
                      -max_muxing_queue_size 1024 -f hls -hls_time 3 -hls_list_size 17 -hls_allow_cache 1
                      -hls_segment_filename %streamFileDir%/stream%streamNum%-%09d.ts -hls_flags delete_segments -c:a
                      aac -ar 48000 -b:a 192k -ac 2 -c:v libopenh264 -vf yadif,scale=-2:720 -b:v 3000k
                      -flags +loop-global_header %OUTPUT%'
                - name: 480p (yadif)
                  cmd:
                      '%FFMPEG% -re -dual_mono_mode main -i pipe:0 -sn -map 0 -threads 0 -ignore_unknown
                      -max_muxing_queue_size 1024 -f hls -hls_time 3 -hls_list_size 17 -hls_allow_cache 1
                      -hls_segment_filename %streamFileDir%/stream%streamNum%-%09d.ts -hls_flags delete_segments -c:a
                      aac -ar 48000 -b:a 128k -ac 2 -c:v libopenh264 -vf yadif,scale=-2:480 -b:v 1500k
                      -flags +loop-global_header %OUTPUT%'
"""
    # Insert hls yadif variants at the end of the live block, before recorded.
    if "720p (yadif)" not in live.split("\n    recorded:")[0][-400:]:
        live = live + hls_yadif

    return text[:live_start] + live + text[live_end:]


def patch_enc_js(text: str) -> str:
    text = text.replace("libx264", "libopenh264")
    text = re.sub(r"const preset = 'veryfast';\n", "", text)
    text = re.sub(r"const crf = 23;\n", "const videoBitrate = videoHeight > 480 ? '3000k' : '1500k';\n", text)
    text = text.replace(
        "    '-preset', preset,\n", ""
    ).replace(
        "    '-crf', crf,\n", "    '-b:v', videoBitrate,\n"
    )
    return text


FETCH_POLYFILL = """'use strict';
// Minimal WHATWG fetch polyfill for nodejs-mobile v16.17.0, which predates
// Node 18's native global fetch. EPGStation's EPG updater uses
// fetch(url).then((response) => response.json()) against local mirakurun.
if (typeof globalThis.fetch !== 'function') {
    const http = require('http');
    const https = require('https');

    globalThis.fetch = function fetch(input, init) {
        init = init || {};
        const url = new URL(String(input));
        const lib = url.protocol === 'https:' ? https : http;
        return new Promise((resolve, reject) => {
            const request = lib.request(
                url,
                { method: init.method || 'GET', headers: init.headers || {} },
                (response) => {
                    const chunks = [];
                    response.on('data', (chunk) => chunks.push(chunk));
                    response.on('end', () => {
                        const body = Buffer.concat(chunks);
                        const headers = {};
                        for (const [name, value] of Object.entries(response.headers)) {
                            headers[String(name).toLowerCase()] =
                                Array.isArray(value) ? value.join(', ') : String(value);
                        }
                        resolve({
                            ok: response.statusCode >= 200 && response.statusCode < 300,
                            status: response.statusCode,
                            statusText: response.statusMessage || '',
                            headers: {
                                get: (name) => headers[String(name).toLowerCase()] || null,
                            },
                            json: () => Promise.resolve(JSON.parse(body.toString('utf8'))),
                            text: () => Promise.resolve(body.toString('utf8')),
                            arrayBuffer: () => Promise.resolve(
                                body.buffer.slice(body.byteOffset, body.byteOffset + body.byteLength)
                            ),
                        });
                    });
                }
            );
            request.on('error', reject);
            if (init.body != null) request.write(init.body);
            request.end();
        });
    };
}
"""


def prepend_after_use_strict(path: Path, require_line: str) -> None:
    text = path.read_text()
    if require_line in text:
        return
    if '"use strict";' in text:
        text = text.replace('"use strict";', '"use strict";\n' + require_line, 1)
    else:
        text = require_line + text
    path.write_text(text)


def apply_fetch_polyfill(root: Path) -> None:
    polyfill = root / "dist/fetch-polyfill.js"
    polyfill.write_text(FETCH_POLYFILL)
    prepend_after_use_strict(root / "dist/index.js", "require('./fetch-polyfill');\n")
    prepend_after_use_strict(
        root / "dist/model/service/ServiceExecutor.js",
        "require('../../fetch-polyfill');\n",
    )
    prepend_after_use_strict(
        root / "dist/model/epgUpdater/EPGUpdateExecutor.js",
        "require('../../fetch-polyfill');\n",
    )


def patch_epg_updater(root: Path) -> None:
    """Avoid a startup double-updateAll race.

    EPGUpdater.start() leaves lastUpdatedTime at 0 until the event-stream
    startup updateAll completes. On slow devices that updateAll can exceed the
    10s interval tick, so the tick sees lastUpdatedTime=0 and starts a second
    concurrent updateAll; the two SQLite write transactions then collide and
    one fails with InsertError. Initializing the timestamps before the first
    updateAll keeps the tick from scheduling a duplicate.
    """
    updater = root / "dist/model/epgUpdater/EPGUpdater.js"
    text = updater.read_text()
    marker = "this.log.system.info('start EPG update');"
    init = (
        marker
        + "\n"
        + "        this.lastUpdatedTime = new Date().getTime();\n"
        + "        this.lastDeletedTime = this.lastUpdatedTime;"
    )
    if "this.lastUpdatedTime = new Date().getTime();" not in text:
        if marker not in text:
            print(f"warning: EPGUpdater start marker not found in {updater}", file=sys.stderr)
            return
        text = text.replace(marker, init, 1)
        updater.write_text(text)


def bump_payload_version(root: Path) -> None:
    """Force the app to re-extract the payload after every patch change.

    EpgStationService only re-extracts when assets/payload.version differs
    from the installed copy. The upstream prepare script writes a static
    version, so patched payloads would never be picked up by install -r.
    Append a marker that must be bumped when the patches change.
    """
    marker = "-android-patched-v2"
    version_file = root / "payload.version"
    text = version_file.read_text().strip()
    if marker not in text:
        version_file.write_text(text + marker + "\n")


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: patch-epgstation-template.py <payload-root>", file=sys.stderr)
        return 2
    root = Path(sys.argv[1])
    config_template = root / "config/config.yml.template"
    enc_template = root / "config/enc.js.template"
    if not config_template.is_file():
        print(f"missing {config_template}", file=sys.stderr)
        return 3
    config_template.write_text(patch_config_template(config_template.read_text()))
    if enc_template.is_file():
        enc_template.write_text(patch_enc_js(enc_template.read_text()))
    apply_fetch_polyfill(root)
    patch_epg_updater(root)
    bump_payload_version(root)
    print("patched EPGStation payload for the Android build")
    return 0


if __name__ == "__main__":
    sys.exit(main())
