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

`git diff 37d13ce..HEAD` after wave R4: 25 files, +689/−7 (7 modified main sources, 2 new domain files, 3 new test files, 6 strings.xml, release.yml, build.gradle.kts, CHANGELOG.md, 4 docs) — every hunk tagged `[RVW-8..12 V 3.2.3]`, no secrets, no unrelated modifications, `local.properties` local-only and gitignored, working tree clean after commits. No force-push, no history rewrite, no branch deletion, nothing pushed. Full re-verification green at the new tip: 1756/0/0/0 (160 files), assembleDebug + lintRelease successful (VERIFICATION_RESULTS §10).

---

## Wave R5 (2026-10-18) — RVW-13: authorization at the execution boundary for alternate config paths

Continuation master prompt triggered a full RBAC-surface survey below the ViewModel. Six ungated alternate paths found and closed in one focused change (no merge semantics touched, no financial semantics touched):

- `FeatureVMs.kt` — `importSettingsFrom` gated `GENERAL_SETTINGS` (was bypassing the per-key gates of every key it writes); `setReportRecipient` / `setReportScheduleDays` / `setReportScheduleHour` / `setReportScheduleChannel` / `setMonthlyGoal` / `setAutoBackupDays` gated `GENERAL_SETTINGS`; `setBackupDir(Uri?)` / `setBackupDir(String?)` gated `BACKUP_RESTORE`; `setAddress` / `setTaxNumber` gated `TAX_OWNER_DATA`. All tagged `[RVW-13 V 3.2.4]` in-code.
- `WebhookStore.kt` — `setEnabled` / `setUrl` / `setSecret` gated `BACKUP_RESTORE` at the data layer (the UI switch guard alone was defense-optional; the URL retarget was the real exfiltration vector for HMAC-signed events).
- `StatementPrefs.kt` — `save` gated `GENERAL_SETTINGS` (single choke point behind all four statement-settings save paths; reads stay ungated by design for the SYSTEM scheduled sender).

Regression protection: `ConfigRbacGateTest` (6 tests) — role matrix (cashier/manager/owner), implicit-owner §4.3 contract, SYSTEM automation contract, before/after no-write-on-denial proof, all against real DataStore with injected PinVault test key (no mocks of the security path).

Contracts preserved literally: IMPLICIT_OWNER and SYSTEM identities pass every new gate; `RoleDeniedException` at the data layer fires before any write; launchSafe converts VM-level denial into a localized message. No previously green behavior changes for legitimate roles in either single-user or multi-user mode.

Verification at this wave's tip: 4 Python gates PASS; full suite 1762/0/0/0 across 161 files (+6); assembleDebug + lintRelease green (VERIFICATION_RESULTS §11). Version 3.2.4 (versionCode 13).

## Wave R6 (2026-10-18) — Verify-First Re-Run: Three Integrity Fixes, No Production Change

Discovery: re-verification of every prior claim at tip `e01c23a` per the continuation master prompt (remote untouched at `672d995`; HEAD clean; toolchain rebuilt). The re-run exposed an environment-sensitive suite flake and two latent integrity gaps:

- **RVW-14** (`349ec3a`) — `app/src/test/java/com/superbiz/app/rbac/ConfigRbacGateTest.kt`: the manager/owner import assertions read a fire-and-forget callback synchronously and raced the IO dispatch (initial `-1` read as a false denial on slower hosts; production `FeatureVMs.kt` cannot emit `-1` — proven by reading every `onResult` path). Fix: `CompletableDeferred` + `withTimeout(15_000)` deterministic await; class flow-wait walls 5s→15s. Empirical confirmation: manager case 4.4–5.1s red → 0.25s green, production RBAC untouched.
- **RVW-15** (`0ef8d1f`) — `app/src/test/java/com/superbiz/app/SchemaMigrationTest.kt`: added `migration1to2_…` and `migration2to3_…` on a hand-built minimal v1 base (the 9 founding tables), asserted column-by-column against Room's exported `5.json` (the three tables are unchanged since creation), plus index names, row survival, writability, and rerun safety. The 16-step migration chain now has zero untested steps.
- **RVW-16** (`929afa8`) — `tools/check_network_boundary.py`: the ADR-001 D1 gate matched `import` statements only while the codebase wires the network package via fully-qualified names — it had reported `scanned import sites: 0` since day one. Now matches both shapes with comments stripped, prints the honest real-site count (3; `expected >= 1` — zero sites = FAIL), and carries a `--self-test` synthetic-tree mode proving it can fail on both violation shapes.
- Version bump — `app/build.gradle.kts`: 3.2.5 / versionCode 14 (this commit, with the CHANGELOG entry and the four docs' Wave R6 sections).

Verification at this wave's tip: 4 Python gates PASS (network gate now with self-test); full suite **1764/0/0/0 across 161 files** (+2); `assembleDebug` SUCCESSFUL (26,645,626 bytes, aapt confirms versionCode 14); `lintRelease` SUCCESSFUL in 3m45s (VERIFICATION_RESULTS §12). Nothing pushed; decision unchanged: APPROVED FOR MERGE.

## Wave R7 (2026-10-18) — Completion Wave: the §7-ب Ledger, the Composable Chain, the Falsifiable Gates

Owner instruction activated the R7 roadmap items; execution order was tests-first throughout.

- **RVW-17 (coupon redemption ledger, schema v18):** failing tests first (`CouponLedgerP48Test` — the fail-closed contract; `CouponLedgerRepoTest` — the under-count reconciliation; the convergence extension — the two-device adversarial), then: `CouponRedemptionEntity` + `CouponRedemptionDao` (insert-or-ignore by unique `eventId`, no update/delete anywhere), `@Database` version 17→18 + 30th entity, `MIGRATION_17_18` (creation-only + the table's sync-touch triggers from `syncRuntimeSql18()`), `MIGRATIONS`/`addMigrations` extended, `LoyaltyP46.CouponSpec.ledgerUses` + `checkSpec` gate, `LoyaltyRepo.checkCoupon` consults the live ledger and `consumeCouponInTransaction(couponId, now, invoiceId, deviceId)` adds the in-transaction ledger guard + witness write, `InvoiceRepo` resolves the device id *before* the transaction (`deviceIdProvider`, wired to `SyncEnableStore.deviceIdOnce()` in `SuperBizApp`), `CouponRedemptionAdapter` (add-only union — the first adapter that never deletes and never LWWs), `BackupKit` 26/26 (entity field, table const, ALL, totalRows, counts, tables, decode, JSON helpers, `NON_MERGEABLE_TABLES` 8→9 with the wrong-fail-direction rationale), `BackupRestoreRepo` export carries it / merge executor skips it with the honest block comment, `BackupRepo` full export + tolerant restore with union insert, and the three size contracts updated (17 migrations, 26 tables, NON_MERGEABLE 9).
- **RVW-18 (chain composition):** `MigrationChainCompositionTest` — true v1 = 11 tables + 19 indexes (provenance documented in-test), seeded, migrated through all 17 steps, name-keyed `<v>.json` shape assertions for v≥5, money-conversion survival checks, Room v18 reopen, live DAO assertions. First run honestly failed twice on under-specified v1 shape (missing v1 indexes; missing NOT-NULL sync columns in a raw seed) — the test now pins the true v1 shape permanently, and the benign ALTER-order/declaration-order divergence at v16 is documented as Room-contract-neutral (name-keyed `TableInfo`).
- **RVW-19 (gate self-tests):** `check_strings.py` / `check_locale_coverage.py` / `check_strings_format.py` refactored minimally to parameterized cores with `--self-test` modes; real-tree behavior verified identical before/after (same output, same exit codes).
- **Version:** 3.2.6 (versionCode 15); CHANGELOG entry with the measured verification numbers; verification details in `INDEPENDENT_VERIFICATION_RESULTS.md` §13; findings and residuals in `INDEPENDENT_REVIEW_FINDINGS.md` §9.

Commits: (code + tests + schema) and (docs + version) — nothing pushed; remote untouched.

## Wave R8 (2026-10-18) — RVW-20: the Coupon Ledger's Witness Made Mandatory

Fresh-eyes adversarial re-review of the wave-R7 coupon ledger (the newest financial code, targeted by the "fix bugs" mandate) surfaced one real defect; tests-first throughout.

- **RVW-20 (mandatory ledger witness):** `LoyaltyRepo.consumeCouponInTransaction` performed the atomic `consume` (counter +1) and then wrote its `coupon_redemptions` witness via `insertOrIgnore` while discarding the return value — on a deterministic `eventId` collision the witness is silently dropped *after* the counter moved: a real consumption the ledger does not see, i.e. the fail-open direction (discount without ledger evidence) that the RVW-17 contract explicitly forbids. Fix: the insert's return value is asserted inside the transaction (`require(witness != -1L)`) so a collision aborts the whole invoice save — the atomic consume rolls back with it. The `-1` tolerance stays only in the sync/restore union paths where the dedupe is the documented contract. No live caller can collide (POS mints a new invoice id per checkout; the invoice editor passes no coupon; restore preserves ids), so the change is pure defense in depth — zero behavior change on the happy path, fail-closed locked at the database boundary.
- **Tests:** `LoyaltyLedgerWitnessTest` (3, real in-memory Room): collision aborts atomically (red before the fix — no exception, counter drifted to 1 while the witness count stayed 1), fresh consumption leaves exactly one witness with both counters agreeing and the local-birth identity pinned, ledger gate rejects before the atomic consume with full rollback.
- **Version:** 3.2.7 (versionCode 16); CHANGELOG entry with measured numbers; verification in `INDEPENDENT_VERIFICATION_RESULTS.md` §14; finding record in `INDEPENDENT_REVIEW_FINDINGS.md` §10.

Commits: (code + tests + version) and (docs + CHANGELOG) — nothing pushed; remote untouched.

## Wave R9 (2026-10-18) — RVW-21: the Coupon-on-Void Semantics Made Explicit, Locked, and Routed to the Owner

The "Complete" continuation wave: verify-first baseline reproduced exactly (4/4 gates + 4/4 self-tests; forced-rerun suite 1781/0/0 across 166 files in 1m29s), then the bounded adversarial pass over the newest financial code examined the R9 roadmap's coupon `qty > 1` candidate and moved on when it proved structurally sound (single scalar `couponId` per cart — same-invoice double-redemption impossible; `maxUses > 1` across invoices witnesses correctly per invoice). What the pass actually found was a different, undocumented asymmetry:

- **RVW-21 (documented-limit lock, zero semantic change):** `voidInvoice` reverses the double-entry journal (invoice and payment refs), payment rows, ZATCA status, inventory, and loyalty points — the explicit "no ghost points" contract — but never restores the consumed coupon: `usedCount` stays incremented and the `coupon_redemptions` witness stands. After a void + re-create, a maxUses=1 coupon is refused by the ledger gate (fail-closed — no resurrection path) and a maxUses=2 coupon has burned a use on a sale that no longer exists. The direction is money-safe, but nothing in code or docs said it was intentional.
- **Disposition (RVW-2 precedent):** behavior locked by `CouponVoidSemanticsTest` (3 contracts, real in-memory Room, through the REAL production path `save → consumeCouponInTransaction → voidInvoice` with `deviceIdProvider` wired like production and the M-3.3 party FK honored — the first filtered run honestly failed on that FK with `partyId = 0`, fixed by seeding a party); an in-situ comment at the `voidInvoice` site records the intent so no future reader mistakes it for an oversight; the semantics choice itself is routed to the owner with two designed options — (a) keep as-is (what the test locks) or (b) compensating restore via a VOID witness row (`qty = -1`, append-only preserved) with the gate moving from `COUNT(*)` to `SUM(qty)`. NOT implemented without an owner instruction.
- **Deliberately no version bump / no CHANGELOG entry:** production behavior is unchanged — one comment block + one test file (the Wave-1 precedent for test/docs-only waves). Version history remains V 3.2.7 (versionCode 16).
- **Verification:** final forced-rerun suite **1784/0/0 across 167 files** (+3 tests, +1 file); `assembleDebug` successful (`aapt`: `versionCode='16' versionName='3.2.7'`); `lintRelease` green in 5m16s after the fourth documented 4 GB-sandbox kernel-OOM (orphan killed, `-Xmx2g` retry); secret scan CLEAN; details in `INDEPENDENT_VERIFICATION_RESULTS.md` §15 and finding record in `INDEPENDENT_REVIEW_FINDINGS.md` §11.

Commits: (test + in-situ comment) and (docs) — nothing pushed; remote untouched (`origin/main` still `672d995`; `ls-remote` confirms the remote never carried the working branches).

---

## Wave R10 (2026-10-18) — RVW-22/23/24: the Webhook Chain — Leak Fixed, Contract Locked, Suspect Dismissed (executed twice: the first run was lost to an environment rollback, re-executed in full from the session transcript, results identical)

The "Complete" continuation wave, opened on a rebuilt toolchain after a sandbox reset that had also **rolled the workspace back to the R9 snapshot** (the wave's first execution — commits, docs, worklog entry — ceased to exist in the environment; reflog/fsck/object checks prove the loss; nothing was ever pushed), moved the adversarial pass to the webhook delivery chain — the one externally-facing path (payload builder → transport client → store/secret gate) no previous wave had reviewed end-to-end. The store side held as previously fixed (AUD-7 encrypted secret, RVW-13 data-layer RBAC gates, honest `""` degradation). What the pass found:

- **RVW-22 (fixed in place):** `WebhookClient.send` closed only `conn.inputStream` — on a 4xx/5xx response that call throws, `runCatching` swallows it, and `errorStream` is never closed; an unclosed error body prevents pool return, so a moved/misconfigured endpoint answering 404/500 leaked one connection per invoice event on a device that fires one event per sale. Fix: the closed stream now follows the response code (`inputStream` for 2xx, null-safe `errorStream` otherwise), still swallowed-no-escape; success-path keep-alive untouched; an in-situ comment records the mechanism. Resource handling only — the fix cannot change what is sent or recorded, which is why it was auto-fixed where semantics forks (RVW-21) are routed to the owner.
- **RVW-23 (contract lock):** `WebhookKit` — the documented public payload contract ("fields added only, never removed or renamed") — had zero tests. `WebhookKitTest` (8 contracts, pure JVM) locks the golden payload literally (any shape change fails deliberately), hostile-string escape→parse round-trips through MiniJson byte-identically, the optional orig-currency block and its `?: 0L` fallback, the empty-secret honest `""`, the signature against the independent RFC 4231 case-2 vector truncated to 16 bytes, and pure-ASCII hex under an ar-SA locale. 8/8 green on the first run in both executions — a pure lock, CouponVoidSemanticsTest precedent.
- **RVW-24 (dismissed with evidence):** the suspect that default-locale `String.format("%02x", …)` could localize hex digits under non-Latin locales (which would silently break every receiver's verification in an Arabic-first app) was probed empirically on JDK 17.0.13 — `ar-SA`/`ar`/`fa-IR`/`hi-IN`/`dz`/`mr` all emit ASCII; the Formatter spec localizes only `'d'`. No code change; a permanent tripwire test locks the property anyway.
- **Routed to the owner (documented observation, not implemented):** when a configured webhook secret becomes undecryptable (PinVault failure — e.g. keystore invalidation after device restore), `secretOnce()` degrades honestly to `""` and `send` delivers *unsigned* while the owner believes signing is configured — the "no secret" and "secret unreadable" cases are conflated at the delivery site. The honest fail-closed alternative (no-send when a secret is configured but unreadable) changes delivery semantics and needs an owner ruling, like RVW-21.
- **Version V 3.2.8 (versionCode 17):** a real production fix takes a bump (R8 precedent), unlike test/docs-only R9. Schema stays v18; no API or flow change.
- **Verification (re-execution):** forced-rerun suite **1792/0/0 across 168 files** (+8, +1) in 1m38s; `assembleDebug` successful (`aapt`: `versionCode='17' versionName='3.2.8'`); `lintRelease` green in 4m7s after one documented 4 GB-sandbox kernel-OOM (dmesg java 2.63 GB; orphaned 797 MB killed; constrained-heap retry); secret scan CLEAN; details in `INDEPENDENT_VERIFICATION_RESULTS.md` §16 and finding record in `INDEPENDENT_REVIEW_FINDINGS.md` §12.

Commits: (code fix + tests + version) and (docs) — nothing pushed; remote untouched (`origin/main` still `672d995`).

## Wave R11 (2026-10-19) — RVW-25/26/27: the CSV Injection Hole Closed, Import Validation Unified, the ZATCA Poison Element Made Visible

- **[RVW-25] fix(webhook's sibling — export): `CsvKit.escape` shape-aware minus guard.** Leading `-` followed by a letter (`-SUM(A1)`, `-1+WEBSERVICE(…)`, `-مرحبا`) now gets the `'` guard; pure numerics stay bare exactly as L-5 locked them. Test-first: `escape_minusFormulaForm_isGuarded` (RED on the 4 formula forms, GREEN after) + `escape_minusNumericForms_stayBare` locking 6 numeric forms including the pre-existing `-2+3` contract.
- **[RVW-26] fix(import): negative cost/sale prices skip the row with named reasons** (`سعر تكلفة سالب` / `سعر بيع سالب`) — consistency with the H4-6 negative-quantity contract; zero stays legal (gifts). Test-first: `c6_negativePrices_skippedWithNamedReason` (RED → GREEN) + `c6_zeroPrices_stillAccepted_giftItems`.
- **[RVW-27] fix(queue): `ProcessSummary.missingDocs` (additive, default 0)** — `processOnce` counts archived-document misses instead of `continue`-ing silently; policy unchanged (nothing sent, nothing written). Test-first: `processOnce_missingDocument_countedNotSpunSilently` (RED → GREEN; also caught the return-statement dropping the counter before any commit).
- **Version**: V3.2.9 / versionCode 18 (production code changed; R8 precedent).
- **Verification**: RED(3)→GREEN(57) filtered; gates 4/4 + self-tests 4/4; forced full suite 1797/0/0/0 (168 files; 2 load-flakes isolated-green ×2); assembleDebug aapt `18`/`3.2.9`; lintRelease GREEN 5m21s fresh; secret scan CLEAN.
- **Verdict-solid (no change)**: Installments (bounds at repo layer, overpay-clamped pay, engine fallbacks unreachable), Checks (whitelist transitions, fresh-read posting, ghost-free delete), ZATCA reboot-stall (production passes `lastAttemptElapsedMs=0` — immune), callback-crash at-least-once (idempotent AUD-4 transactions).

## Wave V3.5.1 (2026-10-20) — the Phase-0 delta: E0.2 (both rulings) + E0.4-b (biometric lockout) on branch `roadmap/p0-delta-v351`

- **Reconciliation**: PRs #2/#3/#4 (parallel session) merged E0.4 parity + P1 + P2-wave-1; PR #5 (this pipeline, stale base) closed unmerged with cross-validation note; delta re-founded on `482b970`.
- **[E0.2-a] fix(webhook): unsigned-fallback fail-closed (Ruling 2, Option A)** — `WebhookStore.secretConfiguredOnce()` + pre-socket gate in `WebhookClient.send`; `WebhookFailClosedTest` (3 contracts; production failure simulated by PinVault key rotation).
- **[E0.2-b] docs(loyalty): RVW-21 Ruling 1 recorded as Option A** — FINDINGS §13-و (date, rationale, override path); behavior untouched.
- **[E0.4-b] fix(lock): biometric path respects active lockout** — `openBiometricSession` refuses locked accounts with `USER_LOGIN_FAIL`/`lockout` audit and preserves the counter; `BiometricLockoutParityTest` (3 contracts incl. order-independent positive control via real `resetPin`).
- **Version**: V3.5.1 / versionCode 22 (no schema change).
- **Verification**: RED-first (6 tests written first; refusal contract observed failing on main's code) → GREEN 6/6; full suite + gates + aapt + lint numbers recorded in the wave commit; secret scan CLEAN.
- **Owner-blocked, honestly not simulated**: E0.1 (release-keystore ceremony, Play Console), E0.3 (instrumented farm on three real targets).
