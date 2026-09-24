# mirakc release promotion

`mirakc-v0.3.1` is promoted only from an annotated tag and the exact, successful
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
  --build-info BUILD_INFO.json --tag-target "$(git rev-parse HEAD)" \
  --message mirakc-attestation.txt \
  --acceptance-output mirakc-acceptance.json
```

The device evidence harness (`device-evidence.py`) is a non-required
verification tool and is not part of the release gate. The release job fetches
only the exact signed-candidate artifact. GitHub artifact retention is three
days, so promotion must be performed before that retention window expires.
Missing, stale, extra, or ambiguous candidate artifacts fail closed.

The published mirakc files are deliberately explicit: `mirakc-0.3.1.apk`,
`mirakc-0.3.1-acceptance.json`, and `SHA256SUMS`. EPGStation continues to
use its existing tagged build path.
