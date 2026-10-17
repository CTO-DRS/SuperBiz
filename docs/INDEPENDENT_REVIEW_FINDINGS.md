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
