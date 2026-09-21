# mirakc release promotion

`mirakc-v0.3.0` is promoted only from an annotated tag and the exact, successful
`signed-candidate.yml` artifact. The release workflow does not rebuild mirakc.

The annotation body must contain exactly this UTF-8 record (one key per line):

```text
MIRAKC-ATTESTATION-V1
candidate_run_id=<positive decimal workflow run id>
candidate_git_head=<40 lowercase hex characters>
candidate_apk_sha256=<64 lowercase hex characters>
device_receipt_manifest_sha256=<64 lowercase hex characters>
```

The tag must resolve to `candidate_git_head`. Generate and verify the record
locally with `release-attestation.py`; it writes files or stdout only and never
creates or pushes a Git tag:

```sh
tools/mirakc/release-attestation.py generate \
  --device-receipt DEVICE_RECEIPT \
  --candidate mirakc-signed-candidate.apk \
  --build-info BUILD_INFO.json --tag-target "$(git rev-parse HEAD)" \
  --message mirakc-attestation.txt \
  --acceptance-output mirakc-acceptance.json
```

Receipt directories are verified locally before this annotation is created;
their manifest hashes are retained in the annotation as the human-approved
acceptance record. The release job does not fetch or trust receipt artifacts.
It fetches only the exact signed-candidate artifact. GitHub artifact retention
is three days, so promotion must be performed before that retention window
expires. Missing, stale, extra, or ambiguous candidate artifacts fail closed.

The published mirakc files are deliberately explicit: `mirakc-0.3.0.apk`,
`mirakc-0.3.0-acceptance.json`, and `SHA256SUMS`. The legacy rescue helpers
remain in `tools/mirakc/` as non-required, outside the normal release path and
are not built, signed, or published by the release workflow. EPGStation
continues to use its existing tagged build path.
