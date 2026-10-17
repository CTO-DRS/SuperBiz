# SuperBiz — Independent Review Findings

**Date:** 2026-10-17 · **Branch:** `review/independent-premerge-2026-10` (review of `audit/remediation-2026-10` @ `31752e7`)
Companion documents: `INDEPENDENT_PREMERGE_AUDIT.md`, `INDEPENDENT_VERIFICATION_RESULTS.md`, `INDEPENDENT_REMEDIATION_LOG.md`

Severity model: **P0** exploitable / data-destroying defect · **P1** financial-integrity, compliance or authorization defect · **P2** real defect with workaround or disclosure gap · **P3** hardening / accuracy / transparency.

---

## 1. New Findings

### RVW-1 — P2 · Merge-restore silently skips the financial core while advertising it as "found" — **FIXED on review branch (`e383cef`)**

**Affected files (evidence):**
- `app/src/main/java/com/superbiz/app/data/repo/BackupRestoreRepo.kt` — executor inserts parties/products/visits/expenses/plans/installments/checks/coupons/loyalty + the 7 statement tables + audit_log, and **never** calls `db.invoices()`, `db.payments()`, `db.journal()`, `db.stockMoves()`, `db.currencies()` or `db.rules()` for inserts; the in-code comment (`[P46-W1]` block, line ~267) states «الفواتير لا تُستعاد في المسار الدمجي».
- `app/src/main/java/com/superbiz/app/domain/backup/BackupKit.kt` — the same 8 tables are exported (`buildBackupJson`), counted (`counts`), parsed (`decodeTable`) and listed in `BackupTables.ALL` (25 tables) — export/parse exists with no matching insert.
- `app/src/main/java/com/superbiz/app/ui/screens/settings/SettingsHubScreen.kt:1580` (pre-fix) — success dialog renders `tablesFound.filterValues { it > 0 }` as "Tables found: invoices N · …" with no note that those tables are read-only for merge.
- `app/src/main/res/values*/strings.xml:1809` — pre-fix `bk_warn3` names only parties/products/visits/expenses/checks/plans/installments as imported and only invoices/payments/journal as excluded (stale since waves P34/P46 expanded the merge scope to 17 imported tables and 8 excluded ones).

**Reproduction:** Settings → Backup card → mode "Merge" → import any full backup JSON → success dialog shows e.g. `invoices 120 · journal 340` under "Tables found" → after import, zero invoices/journal/payments/stock-moves/currencies/rules rows were added. Same on base `672d995` (behavior predates the remediation branch).

**Impact:** A user adopting a new device with "Merge" believes financial history transferred (the dialog says the tables were found; the warning's list is incomplete). Data-loss class, mitigated by: the mode is an explicit user choice, "Full replace" exists on the same card, and the in-code contract has excluded invoices since P13-b (merge semantics = reference-data convergence, avoiding duplicate financial history across devices). Not exploitable; transparency/UX defect.

**Fix implemented (smallest safe):** BackupKit gains `NON_MERGEABLE_TABLES` + `nonMergeableTablesFound(counts)`; the success dialog appends an explicit note naming the skipped tables and pointing to Full replace whenever any of them has rows in the file; `bk_warn3` corrected in all 6 locales to the accurate complete scope; contract locked by `BackupKitTest.nonMergeableTables_contractAndFiltering`. No behavior of the merge executor was changed.

### RVW-2 — P3 · AUD-3's distributed-consumption limit made provable — **documented + test-locked (`48d1c0c`)**

The previous remediation honestly documented (SyncEngine AUD-3 comment) that a max-counter merge cannot detect simultaneous offline consumption of the same coupon on two devices. This review confirms the limit is real and materialized it as a deterministic adversarial test (`SyncConvergenceTest.adversarial documents the known limit - …`): both devices consume (0→1), sync converges, `usedCount == 1` on both while 2 consumptions happened. **Convergence of `usedCount` is guaranteed; exactly-once distributed consumption is not.** The single-device exactly-once guard (`UPDATE coupons … WHERE usedCount < maxUses`, `Daos.kt:1247`) is unaffected.

**Recommended architecture (owner decision, not implemented):** append-only consumption-event ledger synced like other tables; coupon usability derived from ledger aggregation (device-unique event IDs make merges idempotent). Preserves offline-first semantics; requires a new sync table + adapter.

### RVW-3 — P3 · v17 index now proven by query plan — **FIXED on review branch (`8cda90a`)**

The AUD-8 migration test asserted index existence, data preservation and repeat-safety, but its comment mentioned EXPLAIN without running it. The review branch adds an actual `EXPLAIN QUERY PLAN SELECT … WHERE originDeviceId = ? AND originId = ?` assertion (expects `SEARCH … USING INDEX index_parties_originDeviceId_originId`), with enough seeded rows to defeat trivial-scan planning. No unmeasured speedup claim exists anywhere in the previous report (complexity reasoning only) — after this change there is also plan-level evidence.

### D-1 — Accuracy correction · SMTP NONE "no warning" claim was wrong

Previous report §5 item 4: "`SmtpSecurity.NONE` … available in the UI without an explicit warning". **Inaccurate at audit time**: a mandatory confirmation dialog (`confirmNone` → AlertDialog, `[تدقيق M-2]` label) exists in `StatementSettingsScreen.kt:296-332` **and is present on base `672d995`**. Re-scoped: NONE remains a selectable plaintext mode (port 25, credentials + financial PDFs unencrypted) — the risk is real but the mitigation claim must be corrected; severity P3 with dialog.

### D-2 — Accuracy correction · "completeness on both paths" overstated for the merge path

Previous report AUD-1 fix wording: "اكتمال التصدير/الاستعادة على كلا المسارين (JSON + الدمج)" ("export/restore completeness on both paths"). The merge path never imports invoices (RVW-1), so the 13 identity columns added to BackupKit's invoice writer/reader in commit `1ad3911` are exercised only at the data layer (`BackupCrossRoundtripTest`), never by the production merge executor. The JSON full-restore path — the primary restore contract — is genuinely complete; the merge-path claim is corrected by this review (and made transparent in the UI by `e383cef`).

---

## 2. Re-verification of the Eight Previously Documented Risks

| # | Risk | Verdict | Severity | Evidence (verified this review) |
|---|---|---|---|---|
| 1 | Argon2id globally fixed salt | CONFIRMED | **P2** (owner) | `Argon2id.kt:22-23` `DOMAIN_SALT = "superbiz-sync-v1"`; sole derivation call `SyncLinkCard.kt:263` uses default salt; salt never transmitted. Enables amortized precomputation if wrapped blocks leak; strength rests on passphrase entropy. Fix = per-pairing random salt exchanged at pairing (protocol change, format v2) |
| 2 | RBAC fail-open when session==null | CONFIRMED | **P2** (owner) | `Rbac.kt:146-150` `session ?: SessionState.IMPLICIT_OWNER`; guards live only in VM/UI (`UsersVM`, `ProVM`, `FeatureVMs`, 2 screens); **zero RoleGate usage in `work/`** while workers write rules/statements/sync; no data-layer enforcement (repos read session for attribution only) |
| 3 | Emergency unlock one-tap | CONFIRMED | **P2** (owner) | `LockScreen.kt:326-350`: `!multiUser && biometricOn && !bioReady && !hasPin` → single tap `startSingleOwnerSession`, zero auth; deliberate + user-disclosed ("Continue unprotected"); multi-user mode never gets this path. Real fix is preventive: force PIN setup when enabling biometrics (product decision); removing the escape hatch would permanently lock out broken-sensor/no-PIN users |
| 4 | SMTP NONE mode | CONFIRMED (with D-1 correction) | **P3** | `StatementEmail.kt:55,92-96,575-586` (raw socket, AUTH LOGIN + PDF cleartext); mandatory confirm dialog exists since wave M-2 (`StatementSettingsScreen.kt:295-332`) |
| 5 | Statement verification IDs predictable | CONFIRMED | **P3** | `StatementService.kt:168-182` `SB-ST-<yyyyMMdd>-<seq6>`; seq = per-install counter (`StatementRepo.kt:218-225`). Verification is a local-DB lookup; guessing enables enumeration of others' statements via the deep link/QR. Fix = SecureRandom component (consider compat of existing printed QRs) |
| 6 | Widgets show amounts while locked | CONFIRMED | **P2→P3** (owner; privacy) | `redactWidgets` default **false** (`SettingsRepo.kt:66`); widgets render cash/sales/receivables regardless of app-lock state; no lock check exists in `widget/`; `FLAG_SECURE` default-on mitigates screenshots only. Recommend default-redact when a lock is configured, or lock-aware rendering |
| 7 | sync_log / audit_log retention | CONFIRMED, **amplified** | **P3** | 27 sync triggers are installed for **all** installs at migration 15→16 (`SuperBizApp.kt:842-845`) — sync_log grows on devices that never enable sync; only pruning is `prunePushed()` (imported=0 after successful push); imported tombstones are kept forever **by contract** (re-sent each round); `clearAll()` has no production caller; audit_log has no age/size retention (the "50MB" is the backup-import file cap, not a DB cap). Tombstone retention changes require a sync-correctness policy — owner decision |
| 8 | zatca_docs missing from merge restore | CONFIRMED, **re-scoped** | **P3** (owner) | Absent from BackupKit's 25-table list/writer/reader/executor (grep-verified). Importing zatca_docs **alone** would create orphaned archives whose `latestHash()` could chain PIH against documents whose invoices do not exist locally — the gap is a symptom of RVW-1's scope boundary; correct fix = decide financial-history merge as a package (invoices+items+payments+journal+docs), not a one-table patch |

## 3. Independent Security Audit — Result Summary

**Clean (verified positively):** exactly one exported component (MainActivity, lock-gated); single data-free deep link `superbiz://verify` strictly parsed and opened only after unlock; `allowBackup=false` + blanket backup/data-extraction exclusions on every domain; FileProvider non-exported, scoped to `pdfs/`, `xmls/`, `backups/`, `cache/shared` (never databases/DataStore); TLS: HTTPS-only clients, system trust always runs before SPKI pin check, no trust-all verifiers, SMTP enforces endpoint identification (HTTPS alg) and STARTTLS refuses plaintext downgrade; zero secrets in sources or in any Git commit ever (`.gitignore` covers keystore material; `git log --all` checks negative); ProGuard rules do not weaken crypto; dependencies contain no known-vulnerable versions of the pinned artifacts (AGP 8.9.2, Kotlin 2.0.21, Room 2.6.1, WorkManager 2.9.1, Billing 7.1.1, Compose BOM 2024.10.01); CI runs the 4 Python gates + debug/release unit tests on JDK 17/21 + lintRelease.

**Hardening notes (P3, non-blocking):**
- `release.yml` publishes signed artifacts for any `v*` tag without requiring a green CI check on that ref.
- ZATCA SPKI pin set is optional and empty by default (system-CA trust only).
- No `networkSecurityConfig` (cleartext blocked by targetSdk 36 default + absence of `http://` usage).
- FileProvider serves `files/backups/` (full-DB archives) — inherent to the share feature; user-driven grants only.
- ProGuard keeps entity classes unobfuscated (schema names survive in APK; schemas are also shipped in `app/schemas/`).
- Billing entitlements: local `ProEntitlement`/`ProStore` gates premium UI; Play Billing 7.1.1 purchases are acknowledged via the library's standard flow; no server-side receipt validation exists (offline-first product decision — noted, not exploitable client-side beyond Play's own protections).

**Exploitable defects found: none.** No finding in this section carries a reproducible exploit path; all are hardening or privacy defaults.

## 4. Merge-Blocking Assessment

None of the above blocks merge: RVW-1 is fixed on the review branch; RVW-2/3 are evidence improvements; the eight risks are documented owner decisions that predate this wave and are consistent with the repository's disclosed design contracts. Final decision in `INDEPENDENT_PREMERGE_AUDIT.md` §6.

---

## 5. Wave R2 (2026-10-18) — The Priority Risks, Implemented

Following the owner's directive to continue development, the three priorities named in the merge decision §6-2 (widget redaction default, sync_log retention shape, RBAC below the VM layer) plus the statement-ID hardening (risk 5) were implemented as **four new findings with minimal, tested fixes** on the same review branch. Each preserves the documented design contracts; none changes security-sensitive defaults beyond what the finding itself demands.

### RVW-4 — P2 · Widgets exposed financial amounts while the app was locked — **FIXED (`356f8be`)**

**Evidence (risk 6 of §2):** the lock is a Compose stage inside `MainActivity` (stage 2/3) with no process-wide signal, so nothing outside the composition — including all four widgets — can know the lock state. `WidgetSync.redactOn` read only the `redactWidgets` preference (default `false`), so cash/sales/receivables amounts stayed readable on the home screen with the app fully locked.

**Fix (smallest safe):** `domain/AppLockSignal.kt` — JVM-pure process signal + unified decision:
- `unlocked` (volatile, default **false** = fail-closed over cold start and process death) — set true by `MainActivity` on unlock, false on **both** re-lock paths (immediate ON_STOP and ON_START timeout), each followed by an immediate widget re-render so amounts do not linger in the background.
- `shouldRedactWidgets(prefRedact, lockConfigured)` = `prefRedact || (lockConfigured && !unlocked)`. Owner preference still wins outright; a configured lock with a not-open app redacts even without the preference; **no lock configured = historic behavior byte-for-byte**.
- `WidgetSync.redactOn` now routes through it; settings-read failure now redacts (fail-closed) instead of showing (fail-open).
- Lock-not-configured devices and the manual `redactWidgets` preference keep their exact historic semantics — the default flip the review had only *recommended* is now implemented lock-conditionally, which is strictly narrower than a blind default change.

**Regression test:** `WidgetPrivacyTest` (9 tests, JVM-pure): full redaction matrix, fail-closed default, credential detection parity with `MainActivity`'s exact condition, plus the RVW-7 verification-ID contract (below).

### RVW-5 — P3 · Background operations attributed to a fake "owner" in multi-user mode — **FIXED (`8e5d428`)**

**Evidence (risk 2 of §2):** `SessionState.effective()` returned `IMPLICIT_OWNER` unconditionally — even on multi-user devices, where the documented design contract (§4.3: explicit session becomes the condition once a second user exists) implies null-session callers should not wear the owner's name. Workers legitimately run while locked (automation contract), but their audit rows were indistinguishable from a human owner's.

**Fix (honest and non-breaking):** `SessionState.SYSTEM` (role OWNER **deliberately** — the automation-while-locked contract is preserved; full enforcement closure remains a separate documented owner decision) + `multiUserMode` flag (set from `AppGraph.seedIfFirstRun` at process start and refreshed on every users read in `UsersVM.refresh/refreshActiveUsers`, so user add/disable/delete keeps it current). `effective()` = explicit session, else mode identity: implicit owner (single-owner, contract §4.3 byte-identical) or system (multi-user). `auditAttribution()` null-contract unchanged in both modes.

**Regression test:** `SessionStateModeTest` (5 tests): single-owner contract preserved, multi-user+no-session → SYSTEM, explicit session always wins, attribution null-contract both modes, clear() returns to mode identity.

### RVW-6 — P3 · Unbounded sync_log (never-synced devices) and audit_log (all devices) — **FIXED (`d3bf570`)**

**Evidence (risk 7 of §2, amplified):** 27 sync triggers installed for every install since migration 15→16; `markPushed()` is the only sync_log pruning and only runs on successful pushes; audit_log is append-only with no cap of any kind.

**Fix (policy-shaped, correctness-first):** `domain/Retention.kt` + `AppGraph.maintenance()` (called once per process start, each branch failure-isolated):
- `pruneSyncLedger(syncEnabled, fullPushed)` — pure gate: prune **only** when sync was never enabled **and** no full push ever completed. In that state pending rows (`imported=0`) are provably dead weight: no peer ever consumed this device's data, and enabling sync later starts with a full scan (`fullPushed=false`), so live data is covered without the ledger. **Any other state keeps the existing contract literally** — `markPushed()` prunes after successful pushes, and imported tombstones are never touched automatically (sync correctness first; tombstone policy remains an owner decision).
- `audit_log` cap of **50,000 rows** keeping the newest and deleting only the oldest — append-only preserved in its operational meaning (no modification, no reordering); documented as a storage policy, generous to the point of being years of real usage.

**Regression test:** `RetentionMaintenanceTest` (5 tests, real in-memory Room): gate truth table (all four states), prune removes pending **and keeps imported tombstones byte-identically**, `keepNewest` retains the newest N in append order, below-cap no-op, policy constant documented value.

### RVW-7 — P3 · Guessable statement verification IDs — **FIXED (`aac7020`, backward compatible)**

**Evidence (risk 5 of §2):** `SB-ST-<yyyyMMdd>-<seq6>` is fully guessable (date + monotonic per-install counter) while verification is an exact local-DB lookup — guessing enables enumerating other parties' statements via the deep link/QR.

**Fix (compatibility-first):** `StatementService.newVerificationEntropy()` (SecureRandom, 4 chars from an ambiguity-free alphabet — no 0/O, 1/I/L, so spoken/printed IDs stay readable) appended to **new** statements only: `SB-ST-…-K7QM`. The two-arg function returns the historic contract byte-for-byte; old printed QRs keep verifying via exact match (verified: `parseVerifyLink` is format-agnostic and `byVerificationId` is an exact lookup). Side benefit: two independent devices issuing the same seq on the same day no longer collide on the `UNIQUE` constraint during backup merge.

**Regression test:** covered in `WidgetPrivacyTest` (legacy byte-identity incl. non-numeric-tail fallback, blank-entropy identity, prefix preservation, alphabet/length/determinism).

### Wave R2 verification summary

4 Python gates PASS · full suite **1746 / 0 / 0** across **157** files (1727 + 19 new) · `assembleDebug` BUILD SUCCESSFUL (29,384,649 bytes) · `lintRelease` BUILD SUCCESSFUL (5m 21s) — all on a toolchain rebuilt from scratch in this session. Version 3.2.1 → 3.2.2 (versionCode 11). Full evidence: `INDEPENDENT_VERIFICATION_RESULTS.md` §7.

### Risks §2 status after Wave R2

| # | Risk | Status after R2 |
|---|---|---|
| 1 | Argon2id global fixed salt | Owner decision (pairing-protocol v2) — unchanged |
| 2 | RBAC fail-open below VM | **RVW-5** implemented (honest attribution; enforcement closure still a documented owner decision) |
| 3 | Emergency unlock one-tap | Owner decision (force-PIN at biometric enrollment) — unchanged |
| 4 | SMTP NONE | P3 with mandatory dialog (D-1) — unchanged |
| 5 | Statement IDs predictable | **RVW-7 implemented** (new IDs randomized; legacy IDs verifiable) |
| 6 | Widgets show amounts while locked | **RVW-4 implemented** (lock-aware redaction, fail-closed) |
| 7 | sync_log / audit_log retention | **RVW-6 implemented** (never-synced prune + 50k audit cap; tombstone policy still owner's) |
| 8 | zatca_docs absent from merge restore | Owner decision (financial-history-merge package with RVW-1 boundary) — unchanged |
