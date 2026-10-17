# SuperBiz — Independent Remediation Log

**Date:** 2026-10-17 · **Branch:** `review/independent-premerge-2026-10` (based on `audit/remediation-2026-10` @ `31752e7`; the remediation branch itself was not modified)

Scope discipline observed: smallest safe fixes consistent with repository conventions (Arabic inline-audit comment tags, additive-only, no refactoring, no new dependencies, offline-first and no-AI architecture untouched). Security-sensitive behaviors of the app were **not** changed by this review; the eight owner-decision risks remain documented decisions (see FINDINGS §2).

---

## Commit `e383cef` — fix(backup): merge-path transparency [RVW-1]

**Root cause:** the merge executor (`BackupRestoreRepo.importFrom`) never inserts the 8 financial tables it exports/counts/parses (in-code contract since P13-b), while the success dialog labeled them "Tables found" with no exclusion note and `bk_warn3` understated both sides of the contract.

**Files (9):**
- `app/src/main/java/com/superbiz/app/domain/backup/BackupKit.kt` — `NON_MERGEABLE_TABLES` (8 tables) + `nonMergeableTablesFound(counts)`
- `app/src/main/java/com/superbiz/app/ui/screens/settings/SettingsHubScreen.kt` — post-import note (shown only when skipped tables have rows) naming them and pointing to Full replace
- `app/src/main/res/{values,values-en,values-fr,values-id,values-tr,values-ur}/strings.xml` — new `bk_merge_skips_financial` (6 locales) + corrected `bk_warn3` (6 locales); 2437 keys total, parity gates re-run green
- `app/src/test/java/com/superbiz/app/BackupKitTest.kt` — `nonMergeableTables_contractAndFiltering` (subset-of-ALL, uniqueness, exclusion of mergeable tables, filtering semantics)

**Regression test:** added (fails conceptually if the NON_MERGEABLE list drifts from the executor contract — e.g., if a table is later made mergeable, the test's size/exclusion assertions force a conscious update). UI note verified by the pure-function contract test; compose dialog rendering unchanged otherwise.

**Verification:** full suite green post-change (1727/0/0, see VERIFICATION_RESULTS §3).

## Commit `48d1c0c` — test(sync): adversarial documented-limitation test [RVW-2]

**File:** `app/src/test/java/com/superbiz/app/domain/sync/SyncConvergenceTest.kt`
**Adds:** `adversarial documents the known limit - simultaneous offline consumption converges to max but under-counts` — two real devices, both consume offline (0→1 each), full convergence, asserts `usedCount == 1` on both. Locks the AUD-3 limitation as executable evidence (max-merge = convergence, not exactly-once); the append-only consumption-event ledger remains the documented owner-decision remedy. The test is explicitly labeled as documenting current semantics, not endorsing them.

## Commit `8cda90a` — test(migrations): EXPLAIN QUERY PLAN for v17 index [RVW-3]

**File:** `app/src/test/java/com/superbiz/app/SchemaMigrationTest.kt` (`migration16to17_createsIdentityIndexesOnly`)
**Adds:** seeded multi-row parties table + `EXPLAIN QUERY PLAN` assertion that `byOrigin` searches via `index_parties_originDeviceId_originId` rather than a full table scan — plan-level performance evidence per the review mandate ("use query-plan inspection; do not claim speedups without measurements"). Green in the full run.

## Commit `<docs>` — docs(independent): four review reports

- `docs/INDEPENDENT_PREMERGE_AUDIT.md` — executive summary, verified repo state, per-finding verification, decision
- `docs/INDEPENDENT_REVIEW_FINDINGS.md` — RVW-1/2/3 + D-1/D-2, the eight risks with evidence, security-audit summary
- `docs/INDEPENDENT_VERIFICATION_RESULTS.md` — claim-by-claim verification, fresh execution results, NOT VERIFIED list
- `docs/INDEPENDENT_REMEDIATION_LOG.md` — this file

## What this review deliberately did NOT change

- **RBAC fail-open (risk 2)** — requires a data-layer/worker enforcement design; owner decision.
- **Emergency unlock (risk 3)** — removing it would lock out broken-sensor/no-PIN users; the correct fix (force PIN at biometric enrollment) is a product decision.
- **Argon2id global salt (risk 1)** — per-pairing salt is a pairing-protocol change (format v2).
- **Statement verification IDs (risk 5)** — randomizing affects existing printed QR codes; owner decision.
- **sync_log/audit_log retention (risk 7)** — tombstone pruning semantics affect sync correctness; needs a policy, not a blind DELETE.
- **zatca_docs on merge path (risk 8)** — correct only as part of a financial-history-merge package (see RVW-1 analysis); one-table patch would orphan archives and disturb PIH/ICV continuity.
- **Widget redaction default (risk 6)** — security-sensitive default change; recommend, did not flip.
- **SMTP NONE (risk 4)** — warning dialog already exists (D-1); removal is a product decision.
- **CI/release workflow (release.yml lacks CI-green gate)** — infrastructure change outside review scope; documented.

## Final diff review

Post-change diff inspected (`git diff 31752e7..HEAD`): 12 files (9 code/test + 3… plus docs), no secrets, no unrelated modifications, `local.properties` created locally for the build and **gitignored** (never committed). Working tree clean after commits. No force-push, no history rewrite, no branch deletion, nothing pushed.
