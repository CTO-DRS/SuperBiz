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

---

# Wave R2 (2026-10-18) — Priority Risks Implemented on the Same Review Branch

Directive: continue development by executing the priorities named in the merge decision §6-2. Same discipline as wave 1: smallest safe fixes, Arabic inline-audit comment tags (`[RVW-n V 3.2.2]`), additive-only, no refactoring, no new dependencies, offline-first and no-AI architecture untouched, nothing pushed.

## Commit `356f8be` — fix(privacy): lock-aware widget redaction via process lock signal [RVW-4]

- NEW `domain/AppLockSignal.kt` — JVM-pure process signal (`unlocked`, fail-closed default) + unified decision `shouldRedactWidgets(prefRedact, lockConfigured)`.
- `widget/ReceivablesWidget.kt` — `WidgetSync.redactOn` routes through the unified decision; settings-read failure now redacts (was: shows).
- `MainActivity.kt` — signal raised on unlock, lowered on **both** re-lock paths, each followed by `WidgetSync.push(immediate = true)` so masked widgets are re-rendered at once.
- TEST `WidgetPrivacyTest.kt` — 9 tests (redaction matrix, fail-closed default, credential detection parity).

## Commit `8e5d428` — fix(rbac): honest system attribution in multi-user mode [RVW-5]

- `domain/rbac/Rbac.kt` — `SessionState.SYSTEM` + `multiUserMode`; `effective()` = explicit session → mode identity. Single-owner contract §4.3 byte-identical; SYSTEM role OWNER deliberately (automation-while-locked preserved; enforcement closure = separate owner decision).
- `vm/UsersVM.kt` — `refreshMultiUserMode()` wired into `refresh()` and `refreshActiveUsers()` (every lock-screen load and admin mutation).
- TEST `rbac/SessionStateModeTest.kt` — 5 contract tests. (The process-start seed lives in `AppGraph.seedIfFirstRun`, committed with the RVW-6 commit below.)

## Commit `d3bf570` — feat(db): bounded growth — unsynced sync_ledger prune + audit_log cap [RVW-6]

- NEW `domain/Retention.kt` — pure gate `pruneSyncLedger(syncEnabled, fullPushed)` + `AUDIT_KEEP_ROWS = 50_000` with the full policy rationale in-code.
- `data/db/Daos.kt` — `SyncLogDao.pruneUnsyncedLedger()` (same SQL as `prunePushed`, distinct documented semantics: tombstones never touched) + `AuditLogDao.keepNewest(keep)` (`LIMIT -1 OFFSET :keep`).
- `SuperBizApp.kt` — `AppGraph.maintenance()` (both branches try-isolated) launched once from `onCreate`; `seedIfFirstRun` also seeds `SessionState.multiUserMode`.
- TEST `repo/RetentionMaintenanceTest.kt` — 5 tests on real in-memory Room (gate truth table, tombstones untouched, newest-N by append order, below-cap no-op, policy constant).

## Commit `aac7020` — fix(statement): unpredictable verification-ID component [RVW-7]

- `domain/statement/StatementService.kt` — `newVerificationEntropy(count, Random)` (SecureRandom at call sites; `Random` parameter for deterministic tests; ambiguity-free alphabet) + 3-arg `verificationId` overload (blank entropy = historic bytes).
- `data/repo/StatementRepo.kt` — issuance passes `newVerificationEntropy()`; legacy IDs untouched (exact-match verification keeps old QRs working).
- TEST: contract locked in `WidgetPrivacyTest` (legacy byte-identity, prefix preservation, alphabet/determinism).

## Commit `<docs+version>` — V 3.2.2 bookkeeping

- `app/build.gradle.kts` — versionCode 10→11, versionName 3.2.1→3.2.2.
- `CHANGELOG.md` — V 3.2.2 entry (wave R2) prepended in the house format.
- The four `INDEPENDENT_*` documents updated with wave R2 sections.

## Corrections to wave-1's "deliberately did NOT change" list

Three items on that list were explicit owner-decision holds at the time; the owner's "continue development" directive for these priorities supersedes the holds, and each was implemented contract-preserving (not flipped blindly):

- ~~Widget redaction default (risk 6)~~ → **implemented as RVW-4**, narrower than a default flip: redaction applies only while a lock is configured **and** the app is not open; no-lock devices keep historic behavior.
- ~~Statement verification IDs (risk 5)~~ → **implemented as RVW-7** compatibility-first: only new statements randomized; every previously printed ID verifies unchanged.
- ~~sync_log/audit_log retention (risk 7)~~ → **implemented as RVW-6** policy-shaped: never-synced devices only, tombstones never touched automatically, audit cap keeps newest 50k.

Remaining owner-decision holds (unchanged): Argon2id pairing salt (risk 1), RBAC full enforcement closure below VM (risk 2 — RVW-5 delivered the attribution layer; enforcement design remains open), emergency-unlock preventive PIN (risk 3), SMTP NONE removal (risk 4), zatca_docs financial-history merge package (risk 8), release.yml CI-green gate.

## Wave R2 final diff review

`git diff 31752e7..HEAD` after wave R2: 16 files changed (8 modified main sources, 2 new domain files, 3 new test files, build.gradle.kts, CHANGELOG.md, 4 docs) — +~700/−10, every hunk traceable to an RVW tag, no secrets (grep: none), no unrelated modifications, `local.properties` local-only and gitignored, working tree clean after commits. No force-push, no history rewrite, no branch deletion, nothing pushed.

---

# Wave R3 (2026-10-18) — Verification Chain Closed at the Tip (no code)

Full re-verification at `ebc8b4a` in a third independently rebuilt toolchain: 4 gates PASS · 1746/0/0/0 (157 files) · assembleDebug + lintRelease successful (two transient daemon-death attempts honestly recorded). Docs-only commit `37d13ce`. Evidence: `INDEPENDENT_VERIFICATION_RESULTS.md` §9.

---

# Wave R4 (2026-10-18) — Completion Wave: Remaining Owner Items Executed or Finally Decided

Directive: "Complete the development" — the owner's closing instruction for the items left as decisions after wave R2. Same discipline: smallest safe fixes, Arabic inline-audit tags (`[RVW-n V 3.2.3]`), additive-only, no refactoring, no new dependencies, offline-first / no-AI / accounting contracts untouched, nothing pushed.

## Commit `00c5208` — feat(sync): per-pairing random salt [RVW-8]

- NEW `domain/sync/PairingSalt.kt` — SecureRandom 16 B, SyncCrypto codec, strict `decodeOrNull` (any doubt → null → visible error, never a wrong KEK).
- `data/repo/SyncEnableStore.kt` — `sync_pairing_salt` key + once/set/clear (plaintext: public by design, uniqueness is the property).
- `ui/screens/settings/SyncLinkCard.kt` — optional pairing-code field + code display beside the fingerprint; `resolvePairingSalt` order: entered code → stored salt (re-pair) → DOMAIN_SALT for legacy wrapped-KEK pairings (v1 byte-preserved, cross-version pairing intact) → fresh random for new pairings.
- `domain/sync/Argon2id.kt` — doc-comment only: v2 mode documented, DOMAIN_SALT remains the literal default.
- 6 locales: `sync_pair_code_label/hint/invalid/show` (after `sync_fingerprint_hint`; parity + format gates green; 2441 keys).
- TEST `PairingSaltTest` (5, JVM-pure).

## Commit `ece27b5` — ci(release): verify-gate before sign/publish [RVW-9]

`.github/workflows/release.yml` — new `verify` job (4 gates + `testReleaseUnitTest`, JDK 17, tag ref); `build` gains `needs: verify`. No app code touched.

## Commit `00ede4d` — audit(statement): plaintext-send trail [RVW-10]

`work/StatementSchedule.kt` — `sendViaSmtp` gains `repo` param; `security == NONE` writes `STATEMENT_SMTP_PLAINTEXT` (host:port, file) to audit_log before every actual send. Configuration-time consent (mandatory dialog) unchanged.

## Commit `cc719f3` — fix(rbac,lock): data-layer backup gate + LockPolicy contract [RVW-11/RVW-12]

- NEW `domain/LockPolicy.kt` (JVM-pure) + `SettingsHubScreen` wiring — the P5-H4 gate (verified pre-existing during this wave) locked as an executable contract. Emergency unlock unchanged, deliberate, legacy-only.
- `data/repo/BackupRepo.kt` — `BACKUP_RESTORE` gate on `exportTo`/`importFrom` (before `try`, denial throws — no silent `false`) and `exportLocal`.
- `data/repo/BackupRestoreRepo.kt` — same gate on `exportTo`/`importFrom` (inside `try`, denial surfaces as `Result.failure` with cause).
- TEST `BackupRbacGateTest` (4, real in-memory Room, Robolectric): non-owner denial on both repos both ops, owner pass, §4.3 implicit contract, SYSTEM automation contract (incl. `exportLocal`).
- TEST `LockPolicyTest` (1).

## Commit `<docs+version>` — V 3.2.3 bookkeeping

- `app/build.gradle.kts` — versionCode 11→12, versionName 3.2.2→3.2.3.
- `CHANGELOG.md` — V 3.2.3 entry prepended in the house format.
- The four `INDEPENDENT_*` documents updated with wave R4 sections.

## Final decisions closing the remaining holds (no code — documented authority)

The wave-R2 hold list is hereby fully resolved:

- ~~Argon2id pairing salt (risk 1)~~ → **RVW-8** (above).
- ~~RBAC full enforcement closure (risk 2)~~ → **RVW-12** delivered the crown-jewel data-layer gate; **general closure: not adopted, finally decided** — it would duplicate the VM matrix (drift) or demote SYSTEM (break the automation-while-locked contract). RVW-5 attribution + RVW-12 gate = the closing design.
- ~~Emergency-unlock preventive PIN (risk 3)~~ → gate proven already present since P5-H4 and locked by **RVW-11**; escape hatch deliberately retained for legacy state that can no longer be created.
- ~~SMTP NONE removal (risk 4)~~ → mode retained (legitimate for LAN relays), **RVW-10** adds the per-send audit trail on top of the mandatory configuration dialog.
- ~~zatca_docs financial-history merge package (risk 8)~~ → **final decision: merge stays reference-data convergence (P13-b)**; financial history moves via Full replace only (complete since AUD-1). No one-table patch.
- ~~release.yml CI-green gate~~ → **RVW-9** (above).

## Wave R4 final diff review

`git diff 37d13ce..HEAD` after wave R4: 21 files (10 modified main sources, 2 new domain files, 3 new test files, 6 strings.xml, release.yml, build.gradle.kts, CHANGELOG.md, 4 docs) — every hunk tagged `[RVW-8..12 V 3.2.3]`, no secrets, no unrelated modifications, `local.properties` local-only and gitignored, working tree clean after commits. No force-push, no history rewrite, no branch deletion, nothing pushed. Full re-verification green at the new tip: 1756/0/0/0 (160 files), assembleDebug + lintRelease successful (VERIFICATION_RESULTS §10).
