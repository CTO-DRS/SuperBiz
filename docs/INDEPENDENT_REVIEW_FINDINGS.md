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

---

## 6. Wave R4 (2026-10-18) — Completion Wave: the Remaining Owner Items, Executed or Finally Decided

Directive: «أكمل التطوير» / "Complete the development". Each remaining item from §5's hold list was either implemented with minimal, tested code (contracts preserved byte-for-byte where they protect existing users) or closed as a **final documented design decision**. Finding IDs continue the RVW sequence.

### RVW-8 — P2 · Argon2id globally fixed salt — **FIXED for all new pairings (`00c5208`), legacy pairings preserved byte-for-byte**

**Evidence (risk 1 of §2):** `Argon2id.DOMAIN_SALT = "superbiz-sync-v1"` let one precomputation amortize across every SuperBiz installation if wrapped KEK blocks ever leaked.

**Fix (protocol v2, additive — never breaking):** `domain/sync/PairingSalt.kt` (JVM-pure: SecureRandom 16 B, SyncCrypto codec, strict `decodeOrNull`). `SyncEnableStore` persists the pairing salt (plaintext — public by design; *uniqueness*, not secrecy, is the security property). `PassphraseDialog` resolution order (each arm preserves an existing contract literally):
1. entered pairing code → strict decode (invalid → visible error, no derivation);
2. stored salt (re-pairing) → reused — no silent breakage of an in-progress pairing;
3. stored empty + wrapped KEK exists (legacy pre-3.2.3 pairing) → `DOMAIN_SALT` exactly as old versions derived — v1 contract byte-preserved, cross-version pairing still works;
4. fresh pairing → random salt generated, persisted, displayed as a copyable pairing code next to the fingerprint.
A mistyped code produces a different KEK → the face-to-face fingerprint check fails visibly (same detection mechanism as a mistyped passphrase). Corrupt stored salt falls back to v1 rather than deriving a broken key.

**Regression tests:** `PairingSaltTest` (5): size/uniqueness, encode/decode identity + display stability, wrong-size `encode` throws, strict decode (blank/wrong-size/garbage → null, whitespace-trimmed valid), derivation determinism per salt + divergence across salts (downscaled parameters; production-parameter vectors remain in `Argon2idTest`).

### RVW-9 — P3 · release.yml published signed artifacts without a green check — **FIXED (`ece27b5`)**

`build` now `needs: verify`, and `verify` runs the 4 Python gates + `testReleaseUnitTest` on the tag ref itself before any build/sign/publish step. Closes the security-sweep hardening note; no repository code touched.

### RVW-10 — P3 · plaintext SMTP sends left no per-execution record — **FIXED (`00ede4d`)**

The scheduled statement path cannot carry a per-send dialog (risk 4/D-1 mitigation stays at configuration time, mandatory dialog since M-2). The achievable closure: every actual `NONE`-mode execution now writes `STATEMENT_SMTP_PLAINTEXT` (host:port, file) to `audit_log` before sending — a permanent trail the owner can audit. `sendViaSmtp` gained a `repo` parameter (private, two call sites updated).

### RVW-11 — P3 · "no biometrics without PIN" was a free-floating UI condition — **FIXED as executable contract (`cc719f3`)**

Discovery: the preventive gate this review recommended for risk 3 **already exists** (wave P5-H4, `SettingsHubScreen` — toggle blocked with a toast when no PIN; `UsersVM.setBiometricAllowed` requires an existing `pinWrapped`). The residual one-tap emergency unlock therefore serves only legacy pre-gate state that can no longer be newly created — removing it would permanently lock out broken-sensor/no-PIN users (documented owner trade-off, unchanged). The fix locks the existing gate as a domain contract: `domain/LockPolicy.canEnableBiometric(pinConfigured)` (JVM-pure, fail-closed) wired into the settings toggle, tested by `LockPolicyTest`. Risk 3 is hereby **closed at enrollment**: new biometric enrollments without a PIN are impossible, verified by test.

### RVW-12 — P2 · RBAC had zero data-layer enforcement on the crown-jewel op — **defense-in-depth delivered (`cc719f3`); full closure finally decided**

All five backup/restore entry points (`BackupRepo.exportTo/importFrom/exportLocal`, `BackupRestoreRepo.exportTo/importFrom`) now enforce `RoleGate.require(effective(), Op.BACKUP_RESTORE)` themselves — the matrix already makes it owner-only at the VM layer, so this adds the layer below: a non-owner explicit session is denied *visibly* (exception / `Result.failure`) even if a future call path bypasses the VMs. Both protected contracts pass byte-for-byte and are proven by test: implicit owner (design §4.3, single-user null session) and `SYSTEM` (role OWNER — the automation contract; the weekly auto-backup worker is unaffected). **Final decision on full enforcement closure (risk 2): not adopted.** Beyond backup/restore, generalizing data-layer gates would either duplicate the VM matrix (drift risk) or require demoting SYSTEM below OWNER, breaking the documented automation-while-locked contract; the honest attribution layer (RVW-5) plus this targeted crown-jewel gate is the closing design. Risk 2 is **closed as decided**.

### Risk 8 (zatca_docs / financial-history merge) — **closed as a final decision, no code**

The P13-b contract (merge = reference-data convergence, never duplicating financial history) is confirmed as the intended design. One-table patching `zatca_docs` into merge would orphan archives and disturb PIH/ICV continuity (RVW-1 analysis). Financial history (invoices + items + payments + journal + stock moves + currencies + rules + zatca_docs, as one package) moves via **Full replace only** — complete on the JSON path since AUD-1. Documented in `BackupKit.NON_MERGEABLE_TABLES` semantics and here; no code change.

### Wave R4 verification summary

4 Python gates PASS · full suite **1756 / 0 / 0** across **160** files (1746 + 10 new: PairingSalt 5, LockPolicy 1, BackupRbacGate 4) · `assembleDebug` BUILD SUCCESSFUL (26,645,626 bytes) · `lintRelease` BUILD SUCCESSFUL (5m 11s) — toolchain rebuilt from scratch this session; version 3.2.2 → 3.2.3 (versionCode 12). Full evidence: `INDEPENDENT_VERIFICATION_RESULTS.md` §10.

### Risks §2/§5 — final status after Wave R4

| # | Risk | Final status |
|---|---|---|
| 1 | Argon2id global fixed salt | **RVW-8 implemented** — new pairings randomized (v2); legacy pairings preserved byte-for-byte |
| 2 | RBAC fail-open below VM | **RVW-12 delivered** (crown-jewel data-layer gate) + **final decision: no general closure** (automation contract + matrix-duplication drift); RVW-5 attribution stands |
| 3 | Emergency unlock one-tap | **Closed at enrollment** (RVW-11: gate proven pre-existing + locked as executable contract); legacy-only escape hatch documented as deliberate |
| 4 | SMTP NONE | **RVW-10 implemented** (per-send audit trail); configuration-time dialog (D-1) unchanged |
| 5 | Statement IDs predictable | RVW-7 (wave R2) — new IDs randomized, legacy verifiable |
| 6 | Widgets show amounts while locked | RVW-4 (wave R2) — lock-aware redaction, fail-closed |
| 7 | sync_log / audit_log retention | RVW-6 (wave R2) — never-synced prune + 50k audit cap |
| 8 | zatca_docs absent from merge restore | **Final decision: merge stays reference-data convergence**; financial history via Full replace only |

---

## 7. Wave R5 (2026-10-18) — Continuation Master Prompt: RBAC Surface Below the ViewModel, Executed

Fresh master-prompt directive ("continue through the highest-priority actionable findings") triggered a targeted re-survey of every data-touching execution path reachable by an authenticated session outside the gated screens. Method: enumerate all `RoleGate.require` call sites, all worker/repo entry points, all settings-mutation setters, and all Composable-level direct writes; for each, ask "does the direct path for the same key carry a gate that this path skips?"

### 7-أ. New findings and their fixes (RVW-13, V 3.2.4)

| ID | Sev | Finding | Evidence (pre-fix) | Fix |
|---|---|---|---|---|
| RVW-13-أ | **P1** | Settings JSON import bypassed per-key gates — a CASHIER session could change taxRate, baseCurrency, taxJurisdiction, flagSecure, lockTimeoutMin, redactWidgets, reportRecipient, autoBackupDays via an import file | `FeatureVMs.kt` `importSettingsFrom` had no `RoleGate` while the direct setters for the same keys were gated `GENERAL_SETTINGS`; the Settings-hub import row is reachable by any role (hub is not RbacGated) | `RoleGate.require(…, GENERAL_SETTINGS)` at the top of `importSettingsFrom` — the strongest gate of the keys it writes |
| RVW-13-ب | **P1** | Webhook retarget/silent-redirect — enable toggle was UI-guarded (`BACKUP_RESTORE`), but `setUrl`/`setSecret` were completely ungated at any layer; a lower-privilege session could point an enabled webhook at an attacker HTTPS endpoint and signed payloads (HMAC with the owner's secret) would follow | `WebhookStore.setEnabled/setUrl/setSecret` — only the switch had a `can(BACKUP_RESTORE)` guard inline in `SettingsHubScreen` | Data-layer gates on all three setters (`BACKUP_RESTORE`, owner-only) inside `WebhookStore` — enforcement survives any future UI path |
| RVW-13-ج | **P1** | Statement SMTP configuration (outgoing financial channel + credentials + sender identity) writable by any session | `StatementPrefs.save` — the single choke point behind all four save paths of `StatementSettingsScreen` — had no gate; the settings route is not RbacGated | `GENERAL_SETTINGS` gate inside `StatementPrefs.save`; reads deliberately ungated (scheduled sender runs as SYSTEM and never writes) |
| RVW-13-د | P2 | Financial-report delivery configuration (recipient, channel, schedule, monthly goal, auto-backup days) ungated at the VM although the Automation screen is nav-gated `GENERAL_SETTINGS` | `SettingsVM.setReportRecipient/setReportScheduleDays/Hour/Channel/setMonthlyGoal/setAutoBackupDays` | `GENERAL_SETTINGS` on all six — nav gate duplicated at the execution boundary per the master prompt's "negative tests for out-of-UI-path initiation" |
| RVW-13-هـ | P2 | Backup-folder destination writable by any session (both overloads) | `SettingsVM.setBackupDir(Uri?)` and `setBackupDir(String?)` ungated | `BACKUP_RESTORE` (owner-only) on both overloads |
| RVW-13-و | **P1** | Owner tax identity (address + tax number) writable by any session through Profile | `SettingsVM.setAddress/setTaxNumber` ungated; matrix §3 line 8 says `TAX_OWNER_DATA` = owner alone; Profile is reachable by all roles | `TAX_OWNER_DATA` on both; the restore path remains covered by its own `BACKUP_RESTORE` entry gate (repo-level, writes tax fields only under that already-gated operation) |

**Regression tests:** `app/src/test/java/com/superbiz/app/rbac/ConfigRbacGateTest.kt` (6 tests, Robolectric + real DataStore): cashier denied on all six paths with zero state change (before/after capture, order-independent), manager passes GENERAL_SETTINGS gates but is denied owner-only channels, explicit owner passes everything, implicit-owner single-user contract §4.3 preserved, SYSTEM automation contract preserved, and data-layer denial provably precedes any write (legitimate values survive a denial attempt untouched).

**Contracts preserved literally:** implicit owner (single-user, no session) passes every new gate; SYSTEM (multi-user, no session) passes every new gate (automation contract); denial at the data layer throws `RoleDeniedException` before any write; VM-level denial is swallowed by `launchSafe` into a localized user message. No setter's direct-path semantics changed; no merge/financial semantics changed.

### 7-ب. Coupon distributed consumption — evaluated, still an owner decision (design attached)

The master prompt asked for an evaluation of the append-only consumption-event ledger. Verdict after re-inspection: the AUD-3 max-merge fix (LWW can no longer erase consumption) plus the RVW-2 adversarial ceiling test remain accurate; simultaneous offline consumption on two devices still converges to max and under-counts by one. An additive implementation is protocol-feasible (sync dispatch is name-keyed and `adapterFor` returns null for unknown tables, so an extra table is tolerated by older peers), but it changes POS-adjacent financial semantics and requires schema v18 — the exact class of change the master prompt reserves for "an explicit business rule". **Decision: not implemented unilaterally; attached design for owner approval:** entity `coupon_redemptions(id TEXT PK = deviceId|invoiceId|couponCode hash, code, qty, at, origin)` recorded at consume time; synced as an add-only table (union by id, idempotent); effective uses = `COUNT(DISTINCT id)` per code, consulted as an additional fail-closed rejection when ≥ maxUses; `usedCount` kept and merged as today for display/back-compat. Residual honestly stated: this reconciles at convergence and bounds overshoot, but offline exactly-once across devices is impossible without a reservation/consensus protocol — the explicit business rule to adopt is "convergent reconciliation with bounded per-device overshoot". Requires schema v18, migration + adversarial multi-device tests, and a merge-package decision.

### Risks table — unchanged from §6 except

| # | Risk | Delta |
|---|---|---|
| 2 | RBAC fail-open below VM | RVW-13 **reduces the surface further**: the six alternate config paths now enforce at the boundary; the general no-general-closure decision (automation contract) stands |
| New | Coupon ledger | Design attached (§7-ب) — requires explicit owner decision + schema v18 |

## 8. Wave R6 (2026-10-18) — Continuation: Verify-First Re-Run Surfaced Three New Findings, All Closed

Per the continuation master prompt's verify-first discipline, the entire prior state was re-verified at tip `e01c23a` in a rebuilt environment before any new work: branch topology, HEAD, clean tree, remote state (`origin/main` still `672d995` — nothing ever pushed), the 4 Python gates, and the full suite. The re-run immediately exposed a **false-green risk in the suite itself**: `ConfigRbacGateTest` failed 2/6 on this (slower, 2-CPU) host while the same tree was green on the prior host — the exact class of environment-sensitive flake that hides real regressions later. Investigation produced three findings; all three are closed with regression proof in this wave.

| ID | Finding | Class | Root cause | Disposition |
|---|---|---|---|---|
| RVW-14 | `ConfigRbacGateTest` red 2/6 on a slower host, green on the prior host — a race, not a gate denial | Test-side defect (false denial) | `importSettingsFrom` is fire-and-forget (`viewModelScope.launch` via `launchSafe`), but the test asserted the callback result **synchronously** (`var applied = -1` then immediate `assertEquals(1, applied)`). The initial `-1` was read before the IO-dispatched callback could ever fire. Production RBAC code has no `-1` return path at all (`FeatureVMs.kt` maps `applyImport` failure to `onResult(0, "apply_failed")`), proving the observed `-1` could only be "callback not yet called" | Fixed: deterministic await (`CompletableDeferred` + `withTimeout(15_000)`), and the class's flow-wait walls raised 5s→15s for slow CI hosts. No contract weakened — a genuine denial still fails loudly, now with an honest timeout instead of a misleading `-1`. Empirical proof of diagnosis: the manager case went from 4.4–5.1s red to **0.25s green** once awaited |
| RVW-15 | Migrations **1→2 and 2→3** were the only steps of the 16-step chain without any test (everything from 3→4 to 16→17 was step-covered) | Coverage gap (low practical risk — both are creation-only) | The two founding migrations predate the hand-built schema tests, whose bases start at v3 | Fixed: minimal v1 base (the 9 founding tables) + real `MIGRATIONS[0]/[1]` executed, asserted against Room's own exported `5.json` (non-circular ground truth: exact column order/type/notnull, index names, pre-migration row survival, writability, rerun safety). The chain 1→17 is now covered step by step with no gap |
| RVW-16 | `tools/check_network_boundary.py` (ADR-001 D1) was **vacuous since day one**: it reported `scanned import sites: 0` on every run, because the actual wiring uses fully-qualified names (`com.superbiz.app.network.SyncClient` in `SuperBizApp.kt`) while the checker matched only `import` statements | Tooling defect (gate that cannot fail on the pattern the codebase actually uses) | Import-only regex; FQN references invisible; comments mentioning the package also not distinguished | Fixed: both shapes matched after comment stripping; honest counters printed (3 real wire sites found, expected ≥ 1 — zero sites now FAILs the gate as "boundary no longer wired as ADR-001 assumes"); `--self-test` mode builds a synthetic tree proving both violation shapes are caught while commented/whitelisted/in-package references pass |

Commit `349ec3a` (RVW-14), `0ef8d1f` (RVW-15), `929afa8` (RVW-16). No production behavior changed in this wave — all three fixes are test/tooling integrity; the production RBAC matrix, migration SQL, and network boundary itself were verified correct as they stood.

### Residual / owner-visibility items added in R6

- **Environment fragility of the verification host (recorded, not a repo defect):** the sandbox reaps *all* background processes between shell tool calls (proven with a control `sleep` process) — earlier "daemon disappeared" failures during this session were this, not OOM; separately, two genuine kernel OOM kills occurred (compile at ~2.5 GB RSS; lint at ~2.66 GB) and one 803 MB orphaned Kotlin daemon squatted memory until killed. Long builds in this environment must run foreground in a single call. This is documented because it changes how future sessions must reproduce the baseline — it has no effect on the repository or on owner hardware.
- **Supplied credential status:** the PAT provided for this session returns HTTP 401 on `api.github.com` (both plausible token shapes tested; read of the public repo never needs it — `ls-remote` works anonymously). Nothing was pushed (unchanged policy: push/merge is the owner's act). The token was exposed in a chat channel and should be revoked/rotated regardless of validity.

## 9. Wave R7 (2026-10-18) — Completion Wave: §7-ب Adopted and Implemented, the Chain Proved Composable, Every CI Gate Now Falsifiable

The owner's instruction to "complete the development, expand and finalize features, and fix bugs" resolves the one condition §7-ب was waiting on ("if the owner adopts §7-ب") and activates the R7 roadmap's executable items (the R6 report §14). All three were executed with the standing discipline: failing tests written first (the new contract does not compile before its API exists, then the fail-closed behavior is red before the gate is implemented and green after), every claim re-verified at the wave tip, nothing pushed.

| ID | Item | What was done | Verification |
|---|---|---|---|
| RVW-17 | **Coupon redemption ledger — §7-ب implemented as designed, schema v18** (closes the last open §6F item) | New append-only entity `CouponRedemptionEntity` (deterministic unique `eventId = deviceId|invoiceId|code`, code-indexed, no update/delete path anywhere in the DAO); the consume path writes its witness **inside the invoice transaction** (`LoyaltyRepo.consumeCouponInTransaction` now takes `invoiceId` + `deviceId`, resolved by `InvoiceRepo` *before* the transaction opens via a `deviceIdProvider` wired to `SyncEnableStore.deviceIdOnce()` in production — a DataStore read inside a Room transaction is not guaranteed); `LoyaltyP46.CouponSpec` gains `ledgerUses` and `checkSpec` gains the additional fail-closed rejection (`usedCount ≥ maxUses ‖ ledgerUses ≥ maxUses`); `usedCount` semantics untouched (display/back-compat). Sync: new `CouponRedemptionAdapter` — the first add-only adapter (insert-if-absent, **never** deletes, no LWW, no counter-merge; first witness is the truth), registered in `adapterFor`/`allAdapters`; unknown-table tolerance keeps older peers safe (documented, tested in the convergence suite). Migration 17→18 is creation-only (table + 3 indexes + the table's own sync-touch triggers via the shared `syncTouchSql` helper, `syncRuntimeSql18()`), byte-identity with the exported `18.json` enforced by a Room reopen test. Backup split: the ledger is serialized/parsed/counted everywhere (26/26) for full fidelity, but the merge executor **never inserts it** — `NON_MERGEABLE_TABLES` 8→9 (importing witnesses without their invoices would count consumptions the merge path drops and falsely exhaust a valid coupon: the wrong fail-closed direction); full replace and live sync are its only carriers | `CouponLedgerP48Test` (6: gate closed from either counter alone, unlimited coupons ignore the ledger, default 0 preserves the pre-v18 verdict exactly, fail direction never grants), `CouponLedgerRepoTest` (5, real Room: deterministic event written once, duplicate event id ignored, second consume rejected **even after the merged counter was driven back under the limit** — the AUD-3 under-count made flesh and closed, live-ledger rejection, device-scoped ids never collide), `SyncConvergenceTest` new adversarial case (two offline devices consume; counter still converges to the documented 1; **ledger converges to 2 on both devices, idempotent under full re-sync rounds; checkSpec rejects the third use on both**), `Migration17to18Test` (v17 built from `17.json`, coupon survives with its counters, Room byte-identity reopen, trigger writes to the delta log), plus the three size contracts updated (17 migrations, 26 backup tables, NON_MERGEABLE 9) |
| RVW-18 | **Full-chain migration composition test v1→v18** (R7 roadmap item 3) | One continuous ancient-device upgrade: the true v1 schema (RVW-15's 9 founding tables **+ `rules` + `currencies`, provably v1-resident: no migration creates them and `fullV4Schema` — the documented v4 ground truth — includes them** + the 19 v1 indexes, derived by subtracting the migrations' own additions from `fullV4Schema`), seeded with v1 data (parties/products/invoice/items/payment/check/journal/lines/rules/currency at pre-P33 REAL riyals) and mid-chain rows (installment plan at v2, expense at v3, coupon at v12); after every hop reaching v≥5 the schema is asserted against the exported `<v>.json` (non-circular) **name-keyed** — Room's own identity contract (`TableInfo` is keyed by name; column order is not part of schema identity), which the ALTER-appended v16/v17 columns make strictly necessary (the benign ALTER-order vs declaration-order divergence is documented in the test); final Room reopen at v18 enforces full byte identity including foreign keys, and live DAO reads prove the money migration's ×100 conversion survived exactly (80.0 → 8000 piasters) | `MigrationChainCompositionTest`: **green end-to-end**. Genuine first-run finding recorded honestly: without the v1 indexes the chain *fails* at v5 (`index_products_barcode` missing on the rebuilt products table) — the per-hop tests never exercised composition, and this test is what pins the true v1 shape from now on |
| RVW-19 | **`--self-test` for the three remaining Python gates** (R7 roadmap item 4 — completes the RVW-16 precedent) | `check_strings.py` (clean balance passes; a missing key fails; an order divergence fails), `check_locale_coverage.py` (complete locale passes; 90% < 95% threshold fails; an orphan key fails), `check_strings_format.py` (all four FAIL classes caught: key mismatch, arity mismatch, positional/non-positional mixing, stray-% mixed with placeholders; literal-% warns without breaking CI) — each via a minimal core refactor taking an explicit `res_root`/parameterization, with the real-tree behavior byte-identical (same output lines, same exit codes, verified before/after) | All four gates (including the network one) pass their self-tests and their real runs; a CI gate that cannot fail is no longer possible anywhere in `tools/` |

### Residual honestly restated (unchanged in nature, now bounded and tested)

- **Offline exactly-once across devices remains impossible** without a reservation/consensus protocol — the business rule adopted with this implementation is the §7-ب one: *convergent reconciliation with bounded per-device overshoot*. The ledger reconciles at convergence and the fail-closed gate bounds the overshoot to the devices that consumed concurrently before syncing; the adversarial convergence test documents this contract in code. The `usedCount` display counter's under-count is now cosmetic (the gate no longer trusts it alone).
- **Event-id collision semantics when `deviceIdProvider` is unwired:** the default provider returns `""` (tests, or a future construction site that forgets to wire it) — witnesses remain unique per `(invoiceId, code)` on that device but lose cross-device scoping; production wiring in `SuperBizApp` is explicit and verified. This is documented at the constructor and in the repo method.

## 10. Wave R8 (2026-10-18) — One New Finding, Closed In-Wave: the Ledger Witness Was Advisory, Now Mandatory

A bounded fresh-eyes adversarial pass over the R7 coupon ledger — the newest financial code, targeted by the owner's "fix bugs" mandate — surfaced one real defect in the fail-closed design itself.

| ID | Finding | Root cause | Fix | Verification |
|---|---|---|---|---|
| RVW-20 | **`consumeCouponInTransaction` could consume without leaving a witness — a fail-open counter drift inside the RVW-17 design** | The in-transaction witness write used `insertOrIgnore` and discarded its return value: on an `eventId` collision (same device, same invoice, same code) the insert is silently ignored *after* the atomic `consume` has already incremented `usedCount` — the ledger then under-counts a real consumption, which is precisely the "opposite direction" the RVW-17 contract forbids (a discount with no ledger evidence) | The return value is now asserted inside the transaction: `-1` ⇒ `require` throws ⇒ the whole invoice save rolls back, undoing the atomic consume with it (no invoice, no discount, no drift). The tolerance for `-1` remains *only* where it is the documented contract — the sync/restore union paths (restore-then-repush dedupe). No live path can collide: POS mints a new invoice id per checkout, the invoice editor never passes a coupon, restore preserves ids so AUTOINCREMENT resumes past them — the assertion is pure defense in depth at the database boundary | `LoyaltyLedgerWitnessTest` (3, real in-memory Room, `BackupRbacGateTest` pattern): ① collision aborts atomically — visible rejection, `usedCount` back to 0, ledger untouched (red before the fix: no exception, counter drifted); ② fresh consumption leaves exactly one witness, both counters agree, local-birth identity pinned (`origin` = device, `originDeviceId` empty); ③ the ledger gate closes before the atomic consume and the rejected attempt rolls back. Full suite 1781/0/0 (166 files), forced rerun |

Residual unchanged: the §9 residuals stand as written (convergent reconciliation with bounded overshoot; unwired-provider scoping). RVW-20 narrows the second one's blast radius — a colliding witness can no longer drift the counter even if a future construction site misuses the repo method, and the unwired-provider case (`""` device id) now fails loudly instead of silently if a duplicate save is ever attempted.

## 11. Wave R9 (2026-10-18) — Adversarial Pass on the Coupon-on-Void Semantics: Real Behavior, Zero Documentation, Now Locked and Routed to the Owner

The R9 roadmap candidate "coupon `qty > 1` semantics" was examined first and **dismissed as structurally sound**: `PosCart` carries a single scalar `couponId` per cart, so same-invoice double-redemption is impossible on every live path (the RVW-20 collision guard is pure defense in depth, exactly as documented), and `maxUses > 1` across invoices works correctly — each invoice mints its own `invId`, so each redemption gets its own deterministic witness and the ledger gate counts them correctly. The `qty` column remains design margin, as documented.

The real gap found in this pass is a different, **undocumented asymmetry** in `voidInvoice`:

| ID | Finding | Root cause | Disposition | Verification |
|---|---|---|---|---|
| RVW-21 | **Voiding a couponed invoice never restores the coupon use — real behavior, zero documentation, zero test coverage** (P3; semantics = owner decision) | `voidInvoice` reverses every other invoice effect — the double-entry journal (invoice AND payment refs), payment rows, ZATCA status, inventory, and loyalty points under the explicit "no ghost points" contract — but touches nothing coupon-side: `usedCount` stays incremented and the `coupon_redemptions` witness stands. After a void + re-create, a maxUses=1 coupon is refused by the ledger gate and a maxUses=2 coupon has burned its first use on a sale that no longer exists | **No semantic change made** — disposition follows the RVW-2 documented-limit precedent. The behavior is locked by `CouponVoidSemanticsTest` (3 contracts through the REAL production path `save → consumeCouponInTransaction → voidInvoice`, `deviceIdProvider` wired like production, party FK honored per M-3.3): ① void leaves the counter and witness standing and the invoice voided; ② re-using a single-use coupon after a void is refused fail-closed with no partial invoice left behind; ③ multi-use (maxUses=2): the void burns the first use, the next invoice consumes the second, both witnesses stay live. An in-situ comment at the `voidInvoice` site records the decision so the next reader cannot mistake it for an oversight. **Two owner options documented**: (a) keep as-is — consumption is final (what the test locks); (b) compensating restore — a VOID witness row (`qty = -1`, append-only contract preserved) with the gate moving from `COUNT(*)` to `SUM(qty)`; designed, NOT implemented | First filtered run was honestly red for an instructive reason: SQLite FK enforcement rejected `partyId = 0` (M-3.3: "an invoice without a party is impossible") — the test now seeds a party, and the failure was the schema constraint working as designed. Final suite **1784/0/0 (167 files)**, forced rerun |

**Fail-direction analysis (why this was not auto-"fixed"):** the current behavior is the money-safe direction — a consumed coupon cannot be resurrected through void/re-create cycles, and the only cost is over-refusal (a legitimate re-sale may need a fresh discount decision). Restoring a use on void is a real business-semantics choice with fraud-surface implications (void/re-create churn to farm uses back), which is precisely why it is routed to the owner rather than implemented unilaterally. No version bump and no CHANGELOG entry for this wave: production behavior is unchanged (one comment + one test file — the Wave-1 precedent for test/docs-only waves).

---

## 12. Wave R10 (2026-10-18) — "Complete": Adversarial Pass over the Webhook Delivery Chain — a Resource Leak Fixed, the Public Contract Locked, a Suspect Dismissed with Evidence

> **Execution note (honesty):** this wave was executed twice. The first execution's commits were lost to an environment rollback before any push (the workspace snapped back to the R9 tip `c261170`; the original commits exist only in the session transcript). The wave was re-executed in full from that transcript on a rebuilt toolchain, with every verification step genuinely re-run; the results were identical in both executions and the records below describe the re-executed state.

The R10 pass moved to the one externally-facing delivery path that no previous wave had adversarially reviewed end-to-end: the webhook chain (`WebhookKit` payload/signing → `WebhookClient` transport → `WebhookStore` gating/secret handling). The store side was verified solid before anything else — the secret is encrypted at rest in PinVault `ks:` form (the AUD-7 fix), all three setters are RBAC-gated at the data layer (`Op.BACKUP_RESTORE`, the RVW-13 fix — redirecting an enabled HTTPS endpoint exfiltrates owner-signed events, so the gate lives below the UI), and the transparent legacy-migration read path degrades honestly to `""` (no hiding) with an atomic write that removes the plaintext twin on every successful save. `escapeJson` (MiniJson) is a fast-path-plus-full-escape implementation covering `"`, `\`, `\n`, `\r`, `\t`, and all control characters below 0x20 — the injection surface for user-controlled invoice numbers and party names is closed.

| ID | Finding | Root cause | Disposition | Verification |
|---|---|---|---|---|
| RVW-22 | **Every failed delivery leaked its connection: on HTTP error responses the error stream was never closed** (P3; operational, not money-touching) | `WebhookClient.send` closed `conn.inputStream` inside `runCatching` — but on a 4xx/5xx response `getInputStream()` throws, the runCatching swallows it, and `errorStream` is never read or closed. An unclosed error body means HttpsURLConnection cannot return the connection to the pool; it lingers until GC finalization. A moved or misconfigured endpoint answering 404/500 on every event — exactly the failure mode a long-lived offline-first POS device actually hits — accumulated one leaked socket/fd per invoice event | **Fixed in place** (resource handling only, no delivery semantics changed): the stream selected for closing now follows the response code — `inputStream` for 2xx, `errorStream` (null-safe) for everything else, still inside `runCatching` so an IOException during close stays a non-escape. Keep-alive behavior on the success path is untouched (no `disconnect()` added — it would disable pooling for no benefit on a once-per-event sender), the fail-transient-and-record contract (`setLastResult`, no auto-retry) is untouched, and an in-situ comment records the leak mechanism at the site | No JVM-feasible repro: `send` hard-requires `https://` and opens a real socket — a local-HTTP repro would need a self-signed-TLS harness, disproportionate for a P3 fd-leak, and the instrumented-device run (owner-gated, hardware) is the honest place for empirical confirmation. The fix is verified by the full-suite regression at the tip plus code inspection; the failure-path stream-selection logic is deliberately trivial (one ternary) to keep the un-tested surface minimal |
| RVW-23 | **The webhook payload contract had zero test coverage** — a documented public contract ("receivers depend on field names; fields are added only, never removed or renamed") with nothing locking it (P3; documentation-integrity) | `WebhookKit` was written as a stable-format pure object (ADR-001 D1) but never acquired the contract-lock test the house style applies to every other public contract (widgets, statements, coupons) | **Locked by `WebhookKitTest`** (8 contracts, pure JVM, MiniJson round-trip): ① golden payload for canonical input — field names, order, types, and values pinned literally, so any shape change fails the test deliberately (the intended friction: a contract change must be a conscious review, never a silent slip); ② hostile strings (quotes, backslash, newline, tab, control char, Arabic) survive escape→parse round-trip byte-identical in both `number` and `party`; ③ absent party serializes as JSON `null` (key present, value null); ④ orig-currency block omitted entirely when absent; ⑤ orig block present with `origTotal ?: 0L` fallback pinned (currency without amount → 0); ⑥ signature matches the independent RFC 4231 case-2 vector truncated to 16 bytes; ⑦ empty secret returns `""` honestly; ⑧ signature hex is pure ASCII under an ar-SA default locale | **8/8 green on the first run in both executions** — a pure contract lock, exactly like `CouponVoidSemanticsTest`: the tests confirm existing behavior rather than driving a fix. Suite at tip **1792/0/0 across 168 files** (+8 tests, +1 file), forced rerun |
| RVW-24 | **Suspect, dismissed with evidence: does `String.format("%02x", …)` localize hex digits under non-Latin locales?** If it did, the signature header would become non-ASCII in an Arabic-first app and every receiver's byte-comparison verification would break — a silent whole-feature breakage | `WebhookKit.sign` formats each byte with default-locale `String.format` | **Dismissed — no code change.** Empirical probe on the wave's own JDK 17.0.13: `ar-SA`, `ar`, `fa-IR`, `hi-IN`, `dz`, `mr` all emit pure ASCII hex; the Formatter specification localizes only the decimal `'d'` conversion, and Android's OpenJDK-derived Formatter follows the same spec (Android N+). A permanent regression tripwire was still added cheaply: `signAsciiHexUnderArabicLocale` runs `sign` under an ar-SA default locale (restored in `finally`) and asserts the RFC vector byte-for-byte — if any future platform/toolchain ever deviates, the suite catches it before a release does | Probe run recorded in the wave log; tripwire is test ⑧ of `WebhookKitTest`, green |

**Version discipline:** RVW-22 is a real production-code fix (resource handling on the failure path), so the wave takes a bump — **V 3.2.8, versionCode 17** — unlike the test/docs-only R9. No data model change (schema stays v18), no API surface change, no user-visible flow change: the only observable difference is that failed deliveries stop leaking their sockets.

**Fail-direction note (why RVW-22 was auto-fixed while RVW-21 was routed to the owner):** RVW-22 is a defect with a strictly better fix — closing the correct stream cannot change what is sent, recorded, or decided; it only reclaims the resource the code already intended to release (the original intent is visible in the closed success-path stream). RVW-21, by contrast, is a business-semantics fork with fraud-surface implications in either direction — exactly the class the engagement routes to the owner. One more candidate examined and left alone by the same logic: `secretOnce()` degrading to `""` on PinVault decryption failure makes `send` deliver *unsigned* while the owner believes a secret is configured (the conflation of "no secret" with "secret unreadable"). The fail direction is receiver-dependent and the honest fix (fail-closed no-send when a secret is configured but unreadable) changes delivery semantics — routed to the owner as a documented observation, not implemented unilaterally (report §19).

## 13. Wave R11 (2026-10-19) — "Complete the remaining tasks": Three New Territories Adversarially Reviewed (CSV Injection Surface; Checks/Installments Money Math; ZATCA Queue Transitions) — Two Real Gaps Fixed, One Visibility Hole Fixed, Two Territories Verdict-Solid

### 13-أ. RVW-25 — CSV export guard had exactly one surviving formula-injection hole: the minus-sign function form (FIXED)

L-5 (V3.0.2 era) unified the export guard to `= + @ TAB CR` and removed `-`, on the claim "a leading minus never starts a formula in Excel" (it broke `-46.6` numeric reading in Sheets as text). **New evidence, partial reopening**: the claim holds only for pure numerics. Excel *does* evaluate a leading `-` followed by a function or cell reference as a formula: `-SUM(A1)`, `-1+WEBSERVICE("http://x")`, `-A1`. The full attack chain is real in this codebase: a hostile CSV *import* stores text verbatim (documented import contract, item 2 of the H4-6 wave) → the string sits in a party/product name → a later export (parties, debts, favorites, all delegating to `CsvKit.escape` as the single source per the L-5 unification) writes it bare → the shop owner opens the file in Excel → the formula executes (data exfiltration via WEBSERVICE, the classic OWASP CSV-injection payload). Every other leading char was guarded; `-` was the only hole.

**Fix (shape-aware, preserves the L-5 contract verbatim)**: the `'` guard prefix is now added for a leading `-` **iff the remainder contains any letter** (`Char.isLetter()`, Arabic included) — i.e. the function/reference form — while pure numerics (`-46.6`, `-1`, `-250.75`, `-2+3`, `- 46`) stay bare exactly as the L-5 test contract locks them. No numeric coordinate or negative balance is mangled (the original L-5 harm), and the exfiltration form is neutralized (Excel shows it as text, apostrophe hidden). Locked in `CsvKitTest`: `escape_minusFormulaForm_isGuarded` (4 forms incl. Arabic letters and RFC4180 quote-wrapped variants) and `escape_minusNumericForms_stayBare` (6 numeric forms — the existing L-5 assertions pass untouched).

### 13-ب. RVW-26 — CSV import accepted negative cost/sale prices while rejecting negative quantities (FIXED)

`CsvImportKit.parseQty` has rejected negative quantities since H4-6, but `parseMoney` accepted negative cost/sale prices — the same file format applied two different validity disciplines to sibling columns. Consequence chain: a hostile (or typo'd) import file creates products with negative prices → POS is *money-safe* (cart line entry coerces ≥ 0 at `PosScreen` price capture; stored-cart restoration drops negative-priced lines at `PosCart`; totals floor at 0) but inventory valuation (`valRetail = Σ qty·salePrice`, raw sum) skews negative, and the product behaves bizarrely in the cart. The import contract (item 3: "a broken row is skipped with a named reason") already demanded this rigor.

**Fix**: negative cost or sale price now skips the row with a named reason (`سعر تكلفة سالب` / `سعر بيع سالب`), consistent with the quantity guard. Zero prices remain valid (gift/promo items) — locked by `c6_zeroPrices_stillAccepted_giftItems`.

### 13-ج. RVW-27 — ZATCA queue: a missing archived document spun silently forever, invisible in every counter (FIXED, additive)

`Processor.processOnce` skipped a queued invoice whose archived document is missing (`loadDocument == null → continue`) without touching any summary counter. A data anomaly (AUD-4 made doc+status atomic, so this should not occur — which is exactly why it must be *visible* if it does) becomes a silent poison element: reprocessed every batch, consuming a batch slot, reported by nothing. **Fix (additive, non-breaking)**: `ProcessSummary` gains `missingDocs: Int = 0`; the processor counts and still sends nothing and writes no state for the missing item (policy unchanged — visibility only). The production wrapper (`ZatcaReportWorker`) surfaces it in the batch summary. Locked by `processOnce_missingDocument_countedNotSpunSilently`.

### 13-د. Verified solid — verdicts recorded, no code changed

- **Installments money math (engine + repo)**: `createPlan` rejects non-positive totals, coerces the down payment into `[0, total]`, requires financed > 0 and months ∈ 1..120 (the engine's silent empty-schedule path for non-positive financing and the unbounded-months OOM suspect are **unreachable from production**); `pay` re-reads inside the transaction and clamps to the open amount (overpayment impossible → `stats.remaining` can never go negative); early settlement quotes internally and closes fully; reschedule rejects past dates and fully-paid items. The P33-P8 piaster migration left no float behind.
- **Checks money math**: `save` guards positive amount / due ≥ issue / non-blank number; `setStatus` decides from the **fresh row read inside the transaction** (the stale-copy double-post is already fixed and documented), enforces a transition whitelist (cleared-from-bounced = legitimate re-honor posting a new entry; 2→0/1 impossible without un-posting), and `delete` cleans ledger ghosts + payments atomically.
- **ZATCA queue, reboot-stall suspect dismissed with evidence**: the pure-API `isDue` would stall after an `elapsedRealtime` reset (negative diff), **but the production wrapper passes `lastAttemptElapsedMs = 0L` every cycle** (documented in-code), making every pending item due each 6-hour cycle — the 6h period ≥ the 6h backoff cap makes this equivalent to honoring backoff at period granularity. Production is immune; the quirk affects hypothetical alternative callers only. Callback-exception semantics (batch aborts mid-way; an accepted-but-unwritten item is re-sent next cycle and converges via the caller's idempotent single transaction — AUD-4) are the documented at-least-once contract; no change.
- **Import/export parsing robustness**: RFC4180-lite parser is allocation-bounded by input size, regex-free (no ReDoS), quote-mid-field degrades via width normalization instead of crashing.

### 13-ه. Owner decision briefs (the two open rulings, made one-glance)

**Ruling 1 — RVW-21 coupon-void semantics** (full design in §11):
| Option | Meaning | Cost | Risk |
|---|---|---|---|
| **A. Keep as-is** (status quo) | Consumed coupon stays consumed on invoice void — the append-only witness stands | Zero (contract already test-locked) | A customer loses the coupon if the sale itself is voided — merchant goodwill issue only |
| **B. Compensating restore** | Void posts a VOID witness (qty = −1) and the coupon gate switches `COUNT(*) → SUM(qty)` | Adapter + migration-aware gate change + new tests | Re-opens a financial ledger semantics; needs its own adversarial pass |

**Ruling 2 — webhook unsigned-fallback** (FINDINGS §12 / report §19 item 4):
| Option | Meaning | Cost | Risk |
|---|---|---|---|
| **A. Fail-closed** | A configured-but-unreadable secret (PinVault/Keystore invalidated, e.g. after device restore) → `send` returns false, nothing delivered unsigned; honest `lastResult` | Small: one branch + tests | Deliveries stop until the owner re-enters the secret — visible, requires action |
| **B. Keep as-is** | Unreadable secret → delivered unsigned, silently | Zero | Receivers accepting unsigned events lose integrity verification without anyone noticing — a silent security downgrade |

Both rulings are pure owner decisions; nothing was implemented beyond the lock/tests that make either choice safe to take later.

### 13-و. Rulings executed (V 3.5.1 — Phase-0 delta after the parallel-wave reconciliation): safe defaults implemented test-first, overridable

Reconciliation note: the parallel Phase-0/1/2 waves (PRs #2/#3/#4) landed E0.4's data-layer guard
parity and the matrix publication, but neither ruling was executed there. The delta wave below
lands both, on top of current main, with the same discipline ordered by E0.2.

**Ruling 1 — RVW-21 coupon-void semantics: Option A (keep-as-is) — RECORDED, behavior untouched.**
- Decision date: 2026-10-20. Rationale: option B re-opens financial-ledger semantics and, per the
  §13-ه table itself, "needs its own adversarial pass" — not something engineering adopts
  unilaterally; option A is the financial-conservative status quo already locked by
  `CouponVoidSemanticsTest` (3 contracts: counter stands, no revival, multi-use burn order).
- What shipped: this record plus the pre-existing test lock; zero behavior change. Override path:
  owner picks B → compensating VOID witness (`qty = −1`) + `COUNT(*) → SUM(qty)` gate change +
  fresh adversarial pass, RED-first.

**Ruling 2 — webhook unsigned-fallback: Option A (fail-closed) — IMPLEMENTED test-first.**
- Decision date: 2026-10-20. Rationale: option B is "a silent security downgrade" per the table;
  option A is small, visible (honest `lastResult`), and reversible. A configured-but-unreadable
  secret is a real production failure mode (Keystore invalidated by device restore).
- What shipped: `WebhookStore.secretConfiguredOnce()` (separates *no secret configured* — signing
  is optional by the H4-6 contract, unsigned delivery stays the owner's explicit choice — from
  *configured but unreadable*), and a pre-socket gate in `WebhookClient.send`: configured-and-
  unreadable → no connection attempt at all, `send = false`, `lastResult` written honestly.
  Locked by `WebhookFailClosedTest` (3 contracts). The production failure mode is simulated by
  rotating the injected PinVault key between store and read — precisely what a device restore
  does to the Keystore. Race note (in-code): the worst concurrent case with `setSecret` lands
  toward fail-closed refusal, never toward a silently-unsigned delivery of a configured secret.
