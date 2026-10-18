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

---

## 8. Wave R2 Update (2026-10-18) — Decision Status After Implementing the Priorities

The owner directed continued development; the three priorities named in §6-2 (widget redaction, sync_log retention shape, RBAC below the VM layer) and the statement-ID hardening were implemented on the same review branch as **RVW-4 … RVW-7** — full evidence in `INDEPENDENT_REVIEW_FINDINGS.md` §5, per-commit log in `INDEPENDENT_REMEDIATION_LOG.md` (Wave R2), fresh execution in `INDEPENDENT_VERIFICATION_RESULTS.md` §7.

| §6 condition | Status after Wave R2 |
|---|---|
| 1. Land RVW-1 with/before the remediation branch | Unchanged — already on this review branch (`e383cef`) |
| 2. Track the eight owner risks as backlog; widget redaction / sync_log retention / RBAC-below-VM deserve priority | **Substantially executed**: RVW-4 (lock-aware widget redaction, fail-closed), RVW-6 (never-synced sync_ledger prune + 50k audit cap, sync-correctness-preserving), RVW-5 (honest system attribution in multi-user mode) implemented; RVW-7 (unpredictable verification IDs, backward compatible) implemented. Remaining as documented owner decisions: Argon2id pairing salt, RBAC full enforcement closure (attribution layer delivered), emergency-unlock preventive PIN, SMTP NONE, zatca_docs financial-history merge package, release.yml CI gate |
| 3. Branch is local-only; push/merge is the owner's action | Unchanged — nothing pushed by this review |
| 4. lintRelease as pre-merge CI gate | Re-verified green this session (5m 21s) on top of wave R2 |

**Re-executed verification (wave R2):** 4 Python gates PASS · full suite **1746/0/0** (157 files) · `assembleDebug` SUCCESSFUL · `lintRelease` SUCCESSFUL — toolchain rebuilt from scratch; version 3.2.2 (code 11).

**Merge-readiness decision: APPROVED WITH CONDITIONS (reaffirmed, conditions now narrower).** No P0/P1 open; every implementable priority condition has been executed with regression tests; what remains are owner decisions that require product/protocol/infrastructure authority by nature (pairing-protocol v2, worker enforcement redesign, enrollment UX, financial-history merge package, release workflow). The decision to merge `review/independent-premerge-2026-10` (and/or fold it into `audit/remediation-2026-10`) remains the owner's; this review pushes and merges nothing.

---

## 9. Wave R3 Update (2026-10-18) — Verification Chain Closed at the Tip

The full verification matrix was re-executed at the tip `ebc8b4a` in a third independently rebuilt toolchain: 4 Python gates PASS · full suite **1746/0/0/0** (157 files) · `assembleDebug` SUCCESSFUL · `lintRelease` SUCCESSFUL — with the two transient daemon-death build attempts honestly recorded (environment, not code). Surviving wave-R2 artifacts re-parsed (19/0/0). Remote still untouched (`main` @ `672d995` only); nothing pushed. Full evidence: `INDEPENDENT_VERIFICATION_RESULTS.md` §9. **Decision unchanged: APPROVED WITH CONDITIONS.**

---

## 10. Wave R4 Update (2026-10-18) — Completion Wave: All Conditions Discharged

Under the owner's closing directive ("Complete the development"), every remaining item of §6-2 and the §5 hold list was either implemented with tested, contract-preserving code (**RVW-8** per-pairing Argon2id salt for new pairings with byte-preserved v1 compatibility, **RVW-9** release.yml verify-gate before sign/publish, **RVW-10** permanent audit trail for every plaintext SMTP send, **RVW-11** the no-biometrics-without-PIN gate locked as an executable contract — the gate itself proven pre-existing since P5-H4, **RVW-12** data-layer `BACKUP_RESTORE` enforcement on all five backup/restore entry points with §4.3 and automation contracts proven by test) or closed as a final documented decision (**risk 2 general enforcement: not adopted** — automation contract and matrix-drift rationale; **risk 8 financial-history merge: stays out of merge by contract** — Full replace is the financial-history path). Full detail: FINDINGS §6; per-commit log: REMEDIATION_LOG (Wave R4).

**Re-executed verification (wave R4):** 4 Python gates PASS · full suite **1756/0/0/0** (160 files) · `assembleDebug` SUCCESSFUL · `lintRelease` SUCCESSFUL — same session, same rebuilt toolchain as §9; version 3.2.3 (versionCode 12). Evidence: VERIFICATION_RESULTS §10.

**Final merge-readiness decision: APPROVED FOR MERGE.** The four §6 conditions are discharged: (1) RVW-1 landed on this branch; (2) the eight risks all carry final dispositions — six implemented, two decided with rationale; (3) the branch remains local-only — pushing/merging is the owner's sole remaining action; (4) lintRelease is locally verified and now a required pre-publish gate (RVW-9). No P0/P1/P2 finding is open. Residual NOT-VERIFIED items (§4 of VERIFICATION_RESULTS — signing, device E2E, live gateways, real relay, JDK-21 CI leg) are structural to this environment and remain the owner's pre-release checklist. This review merges and pushes nothing.

## 11. Wave R6 Update (2026-10-18) — Verify-First Re-Run: Decision Re-Confirmed, Suite Reproducibility Hardened

The continuation master prompt's verify-first re-run at tip `e01c23a` re-confirmed every structural claim of this review (remote untouched at `672d995`; 29 local commits; clean tree) and surfaced three integrity findings, all closed in-wave: **RVW-14** — the R5 wave's `ConfigRbacGateTest` contained a sync-assert-on-async race that presented a false RBAC denial on slower hosts (production matrix verified correct; test now waits deterministically); **RVW-15** — migrations 1→2 and 2→3 were the only untested steps of the 16-step chain (now asserted against Room's exported `5.json`); **RVW-16** — the ADR-001 network-boundary CI gate had been vacuous since day one (`scanned import sites: 0`; FQN wiring invisible) and now detects both usage shapes with a self-test proving it can fail. Full detail: FINDINGS §8; per-commit log: REMEDIATION_LOG (Wave R6); fresh numbers: VERIFICATION_RESULTS §12.

**Re-executed verification (wave R6):** 4 Python gates PASS · full suite **1764/0/0/0** (161 files) · `assembleDebug` SUCCESSFUL (versionCode 14) · `lintRelease` SUCCESSFUL — version 3.2.5 (versionCode 14).

**Decision: APPROVED FOR MERGE — unchanged.** No production behavior changed in R6; the branch remains local-only; merging/pushing remains the owner's sole action.

## 12. Wave R7 Update (2026-10-18) — §7-ب Discharged by Explicit Owner Instruction; Decision Unchanged

The owner's "complete the development, expand and finalize features, and fix bugs" instruction adopted the attached §7-ب design, and the R7 roadmap's executable items were all implemented and verified (RVW-17 coupon redemption ledger with schema v18 + fail-closed gate; RVW-18 full-chain v1→v18 composition test; RVW-19 `--self-test` across the three remaining CI gates). The last open item of the §6 risk table (coupon distributed consumption) is now closed in code with its residual honestly restated: *convergent reconciliation with bounded per-device overshoot* — offline exactly-once without a consensus protocol remains impossible and is documented as such, with the adversarial convergence test locking that contract. The owner-only items are unchanged and still open by design: merge + tag once a valid credential exists, the pre-release instrumented-device checklist, and the general RBAC-closure decision (documented, not adopted — automation contract stands). Verification at the wave tip: gates 4/4 + self-tests 4/4, suite 1778/0/0 (165 files), `assembleDebug` successful (`versionCode='15'`), `lintRelease` successful. **Decision remains: APPROVED FOR MERGE — merge and push are the owner's sole acts; nothing has been pushed at any point (`origin/main` still `672d995`).**

## 13. Wave R8 Update (2026-10-18) — One More Integrity Hole Found and Closed; Decision Unchanged

A fresh-eyes adversarial pass over the R7 coupon ledger (the newest financial code) found RVW-20: the transactional witness write could be silently dropped on an `eventId` collision *after* the atomic consume — the exact fail-open direction the §7-ب design forbids. Closed in-wave with tests-first discipline (`LoyaltyLedgerWitnessTest`, real Room: red on the collision case, green after asserting the insert's return value, full transactional rollback proven), schema untouched (no migration, no strings — locale parity intact). Verification at the tip: gates 4/4 + self-tests 4/4, forced-rerun suite **1781/0/0 (166 files)**, `assembleDebug` (`versionCode='16'`, `versionName='3.2.7'`), `lintRelease` green in 5m23s (first attempt kernel-OOM with an orphaned Kotlin daemon — the documented 4 GB-sandbox pattern, third occurrence). Environment note: the credential supplied this session is **valid** (HTTP 200, administrative scopes) — recorded as a fact, used for no write operation, stored nowhere on disk; validity is not a push authorization and the rotation reminder stands. **Decision remains: APPROVED FOR MERGE — merge and push are the owner's sole acts; nothing has been pushed at any point (`origin/main` still `672d995`).**
