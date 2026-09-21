#!/usr/bin/env python3
"""Patch the EPGStation payload templates for the bundled LGPL ffmpeg.

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

The patch is idempotent: running it twice on the same template is safe.
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
    print("patched EPGStation templates for bundled LGPL ffmpeg")
    return 0


if __name__ == "__main__":
    sys.exit(main())
