The Android PX-Q3U4 firmware generator is the unmodified fwtool from
nns779/px4_drv, pinned to commit
2b3f79b5bc5db56e8556bb28397f7d8f74b2adeb (v0.2.1).

It is GPL-2.0-only. The build copies the upstream LICENSE and fwinfo.tsv into
the generated asset directory and packages the generator separately as the
executable native library libmirakc-px4-fwtool.so. The vendor ZIP, SYS input,
and generated it930x-firmware.bin are never embedded in the APK; they are
fetched and generated at runtime only when a permitted PX-Q3U4 pair is present.
