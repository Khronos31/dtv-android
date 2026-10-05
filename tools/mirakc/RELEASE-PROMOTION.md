# mirakc release promotion

`mirakc-v0.4.0` is promoted only from an annotated tag and the exact, successful
`signed-candidate.yml` artifact. The release workflow does not rebuild mirakc.

The annotation body must contain exactly this UTF-8 record (one key per line):

```text
MIRAKC-ATTESTATION-V1
candidate_run_id=<positive decimal workflow run id>
candidate_git_head=<40 lowercase hex characters>
candidate_apk_sha256=<64 lowercase hex characters>
```

The tag must be a descendant of `candidate_git_head`; extra commits between the
candidate and the tag are release-tooling changes only and must not rebuild the
APK. Generate and verify the record locally with `release-attestation.py`; it
writes files or stdout only and never creates or pushes a Git tag:

```sh
tools/mirakc/release-attestation.py generate \
  --candidate mirakc-signed-candidate.apk \
  --expected-version 0.4.0 \
  --build-info BUILD_INFO.json --tag-target "$(git rev-parse HEAD)" \
  --message mirakc-attestation.txt \
  --acceptance-output mirakc-acceptance.json
```

The candidate build-info records both the pinned PX4 upstream commit and the
SHA-256 of the DTV-owned PX4 patch. The release verifier checks those values
for 0.4.0. The device evidence harness (`device-evidence.py`) remains
verification material; it does not replace the signed-artifact checks.

The release job fetches only the exact signed-candidate artifact and creates a
**draft** GitHub Release. Review and replace the draft notes with the reviewed
Japanese release notes before publishing. Confirm the draft assets are exactly
the APK, acceptance JSON, and `SHA256SUMS`, then verify the checksums from the
downloaded directory with `sha256sum -c SHA256SUMS`. Publish the same draft
with `gh release edit mirakc-v0.4.0 --draft=false`; do not rebuild or replace
the candidate APK. GitHub artifact retention is three days, so promotion must
be performed before that window expires. Missing, stale, extra, or ambiguous
candidate artifacts fail closed.

The published mirakc files are deliberately explicit: `mirakc-0.4.0.apk`,
`mirakc-0.4.0-acceptance.json`, and `SHA256SUMS`. EPGStation continues to
use its existing tagged build path.
