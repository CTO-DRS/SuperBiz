# SuperBiz — Independent Pre-Merge Audit

**Date:** 2026-10-17 · **Reviewer:** Independent engineering review (separate session from the remediation wave)
**Repository:** github.com/CTO-DRS/SuperBiz
**Branch under review:** `audit/remediation-2026-10` @ `31752e7` (11 commits ahead of `main` @ `672d995`)
**Review branch created:** `review/independent-premerge-2026-10` (based on the remediation branch; the remediation branch was not modified)

---

## 1. Executive Summary

This review independently verified the previous audit-and-remediation wave (branch `audit/remediation-2026-10`) against the actual source code, Git history, on-disk Gradle result artifacts, and a **fresh, self-executed** build and full unit-test suite. Every structural claim of the previous audit was confirmed: the baseline commit, the branch, all 11 remediation commits, the version bump 3.2.0 → 3.2.1 (versionCode 9 → 10), and the Room schema migration v16 → v17 with its exported `17.json`.

All nine previous findings (AUD-1 … AUD-9) were re-verified in code one by one and are **real defects with correctly implemented, tested fixes** — with two precision caveats documented in §4 (an over-broad completeness claim on the merge backup path, and an inaccurate "no warning" claim about SMTP NONE mode).

The review surfaced **three new items** (one P2, two P3) and corrected two inaccuracies in the previous report. The single actionable defect — the merge-restore path reads 8 financial tables from an archive but never imports them while the success dialog lists them as "found" — was fixed on the review branch with a UI-transparency change, corrected pre-import warning text in all 6 locales, and a contract-locking test. No P0 or P1 defect was found. The eight previously documented owner-decision risks were re-verified with code evidence; one (widget redaction default) remains the most user-visible, and none is a merge blocker under the stated criteria.

**Merge-readiness: APPROVED WITH CONDITIONS** — see §6.

## 2. Actual Repository State (verified, not assumed)

| Item | Claimed | Verified | Evidence |
|---|---|---|---|
| Baseline commit | `672d995` | ✅ VERIFIED | `git log origin/main -1` after `git fetch`; local `main` matches remote |
| Remediation branch | `audit/remediation-2026-10` | ✅ VERIFIED | Local branch exists; **not present on remote** (`git ls-remote --heads` shows only `main`) — nothing was pushed |
| Remediation commits | 11 | ✅ VERIFIED | `git rev-list --count 672d995..HEAD` = 11; commit list matches REMEDIATION_LOG |
| Version bump | 3.2.0 → 3.2.1 | ✅ VERIFIED | `app/build.gradle.kts:39-40`: versionCode 9→10, versionName "3.2.0"→"3.2.1" |
| DB schema v16→v17 | Migration + 17.json | ✅ VERIFIED | `Daos.kt` `version = 17`; base had schemas ≤16; `17.json` exported, `version: 17`, 29 entities, 8 identity indexes with Room-standard names |
| Full suite | 1725 tests / 0 failed / 0 skipped over 154 files | ✅ VERIFIED (artifacts) + RE-EXECUTED (fresh) | Stale Gradle XMLs on disk parsed: exactly 154 suite files, total 1725 / 0 / 0 / 0, mtime 2026-10-17 04:00Z; and this review re-ran the complete suite from a clean toolchain (see VERIFICATION_RESULTS) |
| APK | assembleDebug 26.6 MB | ✅ VERIFIED + RE-EXECUTED | Stale artifact `app-debug-unsigned.apk` 26.6 MB @ 04:11Z; fresh build reproduced |
| Working tree "clean" at handoff | Clean | ✅ VERIFIED (content) | 395 files showed as modified but **zero content changes** — all were `644→755` mode artifacts of the container filesystem; neutralized via `git config core.filemode false` (local-only, no file touched, no history change) |

Environment note: the previous session's toolchain (`/home/z/android-sdk`, `/home/z/jdk17`) did not survive into this session. It was rebuilt from scratch (Temurin JDK 17.0.13 + cmdline-tools + platform-36 + build-tools 35.0.0) so that every build/test claim below is the product of an independent execution, not an inheritance of the previous session's outputs.

## 3. Methodology

1. Cloned state inspection: remotes, branches, tags, worktree, reflog-free diff of the entire remediation range (`git diff 672d995..HEAD`, 26 files, +3994/−64).
2. Per-commit code review of all 11 commits, cross-read against the two previous reports.
3. Deep-dive verification of AUD-1 … AUD-9 in the live files (not the diffs alone), including the surrounding call paths (e.g., where journal validation sits relative to `wipeAll`; where `Money.toPiasters` rejects non-finite values; the atomic coupon-consume guard; the webhook signing path).
4. Two parallel evidence sweeps: (a) manifest/exported surface, backup rules, FileProvider, TLS posture, secrets in code and Git history, dependency versions, CI workflows; (b) the eight documented owner-decision risks.
5. Independent re-execution: 4 Python CI gates; `compileReleaseKotlin`; full `testReleaseUnitTest`; `assembleDebug` — all from a freshly installed JDK/SDK.
6. Remediation of newly confirmed defects on a dedicated review branch, with regression tests, following repository conventions (Arabic inline-audit comments, additive-only changes, no refactoring).

## 4. Verification of the Nine Previous Findings

| ID | Previous claim | Verdict | Key independent evidence |
|---|---|---|---|
| AUD-1 | Backup restore silently destroyed ZATCA identity + `zatca_docs` archive | **FIX CONFIRMED** | `BackupRepo.kt`: `invoiceJson`/reader now carry uuid/icv/pih/subtype/deliveryDate/buyer*/zatcaStatus + orig* stamps (raw Longs, not legacy-riyal converted); `zatca_docs` exported as table 26, wiped in `wipeAll` (`Daos.kt:1101,1119`), restored via `archiveFirst` (IGNORE) inside the restore transaction; `BackupZatcaAuditTest` (4 tests) covers the byte-exact roundtrip |
| AUD-2 | Restore bypassed double-entry balance | **FIX CONFIRMED** | Validation block (`BackupRepo.kt:833-852`) runs at decode phase — **before** the `parties.isEmpty()` gate and **before** `db.withTransaction { wipeAll() … }` (line 858+); exact Long equality `debit != credit`; lineless-entry and orphan-line rejection; throw → outer catch → `false` with DB untouched |
| AUD-3 | LWW merge zeroed coupon counter (double spend) | **FIX CONFIRMED — with a documented limit** | `SyncEngine.kt`: `applyIncoming` merges `usedCount = max(local, incoming)`; new `counterMerge` hook absorbs a higher incoming counter even when local wins LWW; atomic single-device guard `WHERE usedCount < maxUses` confirmed in `Daos.kt:1247-1248`. **The documented limitation is real**: simultaneous offline consumption on two devices converges to `max` and under-counts (new adversarial test on the review branch locks this fact — FINDING RVW-2) |
| AUD-4 | ZATCA worker callbacks not transactional | **FIX CONFIRMED** | `ZatcaReportWorker.kt`: `onAccepted` (status 2 + `markReported` + `markClearedXml`) and `onRejected` (status 3 + `markRejected`) each wrapped in `db.withTransaction` |
| AUD-5 | PinVault global keyProvider swap race | **FIX CONFIRMED** | `encryptFor` = `encryptWith(userKeyProvider(userId), …)`; `decryptFor` tries owner seed then explicit user key; no swap/restore of mutable state remains. The two contracts broken by the first refactor cut were restored in `b7798a6` (PinVaultException wrap on encrypt; `v1:` blobs decoded key-free) — the honest self-correction cycle is documented in REMEDIATION_LOG and matches the code |
| AUD-6 | Fixed `+86,399,999` end-of-day broke DST days | **FIX CONFIRMED** | `TimeMath.endOfDay` = calendar next-local-midnight − 1ms; routed in `SalesMath.todaySales`, both `ReportsRepo` windows; **zero** remaining `86_399_999` literals in main sources; tests cover 25h/23h New_York days + Riyadh byte-parity |
| AUD-7 | Webhook HMAC secret plaintext in DataStore | **FIX CONFIRMED** | `WebhookStore.kt`: `wh_secret_ks` via `PinVault.encrypt` (ks: blob); read-time transparent migration removes legacy key; migration failure returns `""` (fail-closed, secret never logged); `setSecret` never writes plaintext and clears both keys when emptied. Signing side (`WebhookKit`) is HMAC-SHA256 over the exact payload — sender-only, so constant-time comparison is not applicable |
| AUD-8 | `byOrigin` queries unindexed (O(N) per incoming row) | **FIX CONFIRMED** | `Entities.kt`: composite `(originDeviceId, originId)` index on the 8 syncable tables; `MIGRATION_16_17` = 8× `CREATE INDEX IF NOT EXISTS` (additive, repeat-safe); present in both runtime `addMigrations` and the test `MIGRATIONS` list; `17.json` consistent (createSql verified); review branch adds an **EXPLAIN QUERY PLAN** assertion (RVW-3) |
| AUD-9 | NaN trap + silent catch in restore reader | **FIX CONFIRMED** | Tolerant defaults for `taxRate/stockQty/reorderLevel`; remaining default-less `optDouble` calls are only nullable lat/lng (benign: absent key → `isNull` → null; NaN → null); failure cause now logged (`Log.e("BackupRepo", …)`). Non-finite legacy amounts route through `Money.toPiasters` which explicitly maps NaN/∞ → 0 (exact `BigDecimal…longValueExact()` otherwise) |

## 5. New Findings and Corrections (this review)

Full detail with reproduction steps in `INDEPENDENT_REVIEW_FINDINGS.md`. Summary:

| ID | Severity | Title |
|---|---|---|
| RVW-1 | **P2** (fixed on review branch) | Merge-restore path reads 8 financial tables (invoices, invoice_items, payments, journal, journal_lines, stock_moves, currencies, rules) but never imports them; success dialog lists them as "found" with no exclusion note; pre-import warning `bk_warn3` stale (predates P34/P46 scope, names only 3 of the 8 excluded tables) |
| RVW-2 | P3 (documented, test locked) | AUD-3's known limit made provable: simultaneous offline coupon consumption on two devices converges to `max` and under-counts — an append-only consumption-event ledger is the correct future design (owner decision) |
| RVW-3 | P3 (fixed on review branch) | v17 index benefit now proven by query-plan inspection, not just index existence |
| D-1 | Accuracy correction | Previous report §5.4 claimed SMTP `NONE` is selectable "without an explicit warning" — a mandatory confirmation dialog has existed since audit wave M-2 (verified present on base `672d995`). Risk re-scoped P3 with the dialog as mitigation |
| D-2 | Accuracy correction | Previous report's AUD-1 wording "export/restore completeness on **both** paths (JSON + merge)" is misleading: the merge path never imports invoices at all (in-code contract since P13-b); the 13 identity columns added to BackupKit's invoice writer/reader are exercised only at the data layer, not by the merge executor |

## 6. Merge-Readiness Decision

**APPROVED WITH CONDITIONS.**

Rationale: all nine AUD findings are genuinely fixed and regression-tested; the complete test suite and debug build pass under an independent, freshly installed toolchain; no P0/P1 defect is open; no secret ever entered the history; the manifest/backup-rules/TLS posture is strong. Conditions (non-blocking, owner decisions documented in FINDINGS §6):

1. Land the RVW-1 transparency fix together with (or before) the remediation branch so merge-restore users are correctly informed.
2. Track the eight owner-decision risks (FINDINGS §6) as explicit backlog items — RBAC below the VM layer, widget redaction default, and the sync_log retention shape deserve priority.
3. The branch is local-only; pushing/merging remains the owner's action (this review pushes nothing).
4. `lintRelease` runs in CI but was not executable in this environment within resource limits (see VERIFICATION_RESULTS §4) — it remains a pre-merge CI gate, not a locally verified step.

## 7. Deliverables Produced by This Review

- `docs/INDEPENDENT_REVIEW_FINDINGS.md` — P0–P3 findings with evidence and repro steps
- `docs/INDEPENDENT_VERIFICATION_RESULTS.md` — claim-by-claim verification + exact test/build output
- `docs/INDEPENDENT_REMEDIATION_LOG.md` — fixes implemented on the review branch
- Review branch `review/independent-premerge-2026-10` (4 commits: docs, RVW-1 fix, tests, review log)
