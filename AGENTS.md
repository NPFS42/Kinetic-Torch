# STARS / NPFS engineering rules

Before engineering, build, test, packaging, deployment or runner work, consult the VPS-first Engineering Problem/Solution KB and the [mandatory CI dependency lifecycle policy](https://github.com/NPFS-STARS/STARS-Master-Archive/blob/main/00_Catalog_and_Provenance/Engineering_Problem_Solution_KB/CI_DEPENDENCY_POLICY.md). The linked policy is authoritative, not duplicated here.

Use compatible verified persistent local tool and package caches; automatically provision missing approved dependencies with pinned versions, publisher integrity checks, disk reserves and safe isolated staging. Use only authorized target-compatible local runners; no paid hosted fallback, arbitrary public PR execution on private runners, global security-policy changes, or cache storage of credentials/signing keys. Apple-only acceptance remains deferred absent authorized compatible hardware/compute. Preserve rollback and attended hardware/production gates. Report source, CI and live results separately with exact evidence; record deviations and approved KB updates.

Existing workflows must be reconciled against the policy; this file alone is not a compliance or test receipt.
