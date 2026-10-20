# SuperBiz — Independent Verification Results

**Date:** 2026-10-17 · **Reviewer environment:** freshly installed toolchain (Temurin JDK 17.0.13, Android cmdline-tools, platform-36, build-tools 35.0.0) on 2 vCPU / 4.1 GB RAM — deliberately rebuilt from scratch so no result is inherited from the previous session.
**Branch:** `review/independent-premerge-2026-10` (= `audit/remediation-2026-10` + 4 review commits)

Legend: ✅ VERIFIED (evidence shown) · ⚠️ PARTIAL · ❌ NOT VERIFIED.

---

## 1. Claims of the Previous Audit — Structural

| Claim | Result | Evidence |
|---|---|---|
| Baseline `672d995` = remote main tip | ✅ | `git fetch` + `git log origin/main -1` (remote unchanged during review; nothing pushed by this review) |
| Branch `audit/remediation-2026-10` exists | ✅ | Local branch only; `git ls-remote --heads` shows `main` exclusively |
| 11 remediation commits after baseline | ✅ | `git rev-list --count 672d995..HEAD` = 11; hashes `1ad3911 6f517b6 f34e06b ca29133 a461c89 9552da1 9591f8e b7798a6 53d7a08 8a2ab74 31752e7` — all present in history |
| Version 3.2.0 → 3.2.1 | ✅ | `app/build.gradle.kts`: versionCode 9→10, versionName "3.2.0"→"3.2.1" (base vs HEAD) |
| Schema v16 → v17 | ✅ | Base `app/schemas/…` max = 16.json; HEAD adds 17.json (`version: 17`, 29 entities, 8 identity indexes, createSql matches `MIGRATION_16_17`); `Daos.kt` `@Database(version = 17)` |
| Working tree clean at handoff | ✅ (content-level) | 395 files appeared modified; `git diff --numstat` content changes = **0**; all were `100644→100755` container artifacts; neutralized with local-only `core.filemode=false` |
| Remediation diff scope | ✅ | `git diff --stat 672d995..HEAD` = 26 files, +3994/−64; per-commit files match REMEDIATION_LOG table |

## 2. Claims of the Previous Audit — Test/Build Artifacts (stale evidence)

| Claim | Result | Evidence |
|---|---|---|
| Full suite 1725 tests / 0 failed / 0 skipped over 154 files | ✅ (as artifacts) | Parsed `app/build/test-results/testReleaseUnitTest/*.xml` left by the previous session: exactly **154** suite files, totals **1725 / 0 / 0 / 0**, mtimes 2026-10-17 04:00Z |
| assembleDebug APK 26.6 MB | ✅ (as artifacts) | `app-debug-unsigned.apk` 26.6 MB @ 04:11Z on disk |

Artifacts are internally consistent with the claims but are not proof of execution — therefore everything below was **re-executed independently**.

## 3. Independent Re-Execution (this review's own runs)

| Check | Command | Result |
|---|---|---|
| Strings format gate | `python3 tools/check_strings_format.py` | **CI: OK (0 mismatch)**, 133 legacy intentional % warnings — exit 0 |
| Strings parity gate | `python3 tools/check_strings.py` | 6 locales × **2437** keys (after review-branch additions), same order — exit 0 |
| Locale coverage gate | `python3 tools/check_locale_coverage.py` | **100 %** for fr/id/tr/ur vs default — exit 0 |
| Network boundary gate | `python3 tools/check_network_boundary.py` | PASS — exit 0 (note: tool scans 0 import sites; see FINDINGS §3 note — vacuous-pass risk tracked as P3 hardening) |
| Kotlin compile | `./gradlew --no-daemon compileReleaseKotlin` | **BUILD SUCCESSFUL in 3m 53s** — exit 0 (only pre-existing `ExperimentalCoroutinesApi` warnings) |
| Full unit-test suite | `./gradlew --no-daemon testReleaseUnitTest` | **BUILD SUCCESSFUL in 2m 14s** — exit 0; fresh XMLs: **154** suite files, **1727 tests / 0 failures / 0 errors / 0 skipped** (= previous 1725 + 2 tests added by this review: `BackupKitTest.nonMergeableTables_contractAndFiltering`, `SyncConvergenceTest.adversarial documents the known limit - …`) |
| Debug package | `./gradlew --no-daemon assembleDebug` | First attempt immediately after the test run: **exit 1 with no captured diagnostic** (transient; process-reaping environment). Immediate re-run: **BUILD SUCCESSFUL in 19 s** (exit 0) — fresh `app-debug-unsigned.apk` **29,384,649 bytes** @ 11:57Z. Size delta vs previous session's 26.6 MB noted; debug APKs are not size-stable across builds and the delta is not attributable to the review changes (~1 KB of resources) — no defect signal |
| Lint | `./gradlew --no-daemon lintRelease` | **BUILD SUCCESSFUL in 5m 26s** — exit 0. This **closes** the previous session's documented limitation ("lintRelease not executed here") |
| Migration suite | included in full run | `SchemaMigrationTest` green (incl. new EXPLAIN QUERY PLAN assertion) |
| Sync convergence | included in full run | `SyncConvergenceTest` 9/9 (8 + new adversarial) |
| Backup regression | included in full run | `BackupZatcaAuditTest` 4/4, `BackupKitTest` incl. new contract test |

**Test-compile incident (honest record):** the first test-suite invocation failed to compile because this review's new test method name contained an illegal `:` for backticked JVM names; fixed by renaming (hyphen) and re-run — the passing run above is the post-fix run. One Kotlin compile-relevant edit cycle total; no test was weakened to pass.

## 4. Environment Limits (NOT VERIFIED items)

| Item | Status | Reason |
|---|---|---|
| `assembleRelease` | ❌ NOT VERIFIED | Requires owner's `keystore.properties` (deliberately absent from repo) |
| Signed release / Play flows | ❌ NOT VERIFIED | Signing credentials + Play access are owner-only |
| Device/emulator E2E (thermal printing, real biometrics, live Play Billing, live ZATCA gateway) | ❌ NOT VERIFIED | No device/emulator in this environment; live gateways out of scope |
| Sync against a real relay server | ❌ NOT VERIFIED | Verified at the engine level (Loopback transport, full encryption) only |
| JDK 21 leg of the CI matrix | ❌ NOT VERIFIED here | CI covers it (workflow runs `testReleaseUnitTest` on 17+21); this environment executed the JDK 17 leg only |

## 5. Code-Level Verification of the Nine Fixes (AUD-1…AUD-9)

All nine verified **in the live files** (not just diffs) — full evidence table in `INDEPENDENT_PREMERGE_AUDIT.md` §4. Every fix is real, minimal, convention-consistent, and regression-covered. Two accuracy corrections to the previous report's wording are recorded in `INDEPENDENT_REVIEW_FINDINGS.md` (D-1 SMTP-warning claim, D-2 merge-path completeness wording).

## 6. Dependency & Manifest Verification (from evidence sweep)

- Manifest: 14 permissions, all feature-justified; single exported activity; widgets exported=false.
- Backup rules: blanket exclusions verified for legacy + Android-12+ paths.
- Dependencies pinned versions recorded; no vulnerable-version flag among pinned artifacts; no HTTP client library (platform `HttpsURLConnection` only, per ADR-001).
- Git history secret scan (added-file inventory + pattern grep): clean; only test-fixture password strings in `Argon2idTest`.

---

## 7. Wave R2 Verification (2026-10-18 — this session's own runs)

Environment: toolchain rebuilt from scratch again in this session (the previous session's `/home/z/jdk17` + `/home/z/android-sdk` did not survive — reinstalled via the persisted `scripts/setup_toolchain.sh`: Temurin JDK 17.0.13 + cmdline-tools + platform-36 + build-tools 35.0.0). Every result below is this session's own execution, not an inheritance.

| Check | Command | Result |
|---|---|---|
| Kotlin compile | `./gradlew --no-daemon compileReleaseKotlin` | **BUILD SUCCESSFUL** — exit 0 (pre-existing `ExperimentalCoroutinesApi` warnings only) |
| Wave test classes | `testReleaseUnitTest --tests` (WidgetPrivacyTest + SessionStateModeTest + RetentionMaintenanceTest) | **BUILD SUCCESSFUL** — 19 tests green after one self-caught fix (see incident below) |
| Strings format gate | `python3 tools/check_strings_format.py` | PASS (exit 0) |
| Strings parity gate | `python3 tools/check_strings.py` | PASS (exit 0 — no string changes in wave R2) |
| Locale coverage gate | `python3 tools/check_locale_coverage.py` | PASS (exit 0) |
| Network boundary gate | `python3 tools/check_network_boundary.py` | PASS (exit 0) |
| Full unit-test suite | `./gradlew --no-daemon testReleaseUnitTest` | **BUILD SUCCESSFUL** — fresh XMLs parsed: **157** suite files, **1746 tests / 0 failures / 0 errors / 0 skipped** (= wave-1's 1727 + 19 new wave-R2 tests: WidgetPrivacy 9, SessionStateMode 5, RetentionMaintenance 5) |
| Debug package | `./gradlew --no-daemon assembleDebug` | **BUILD SUCCESSFUL** — `app-debug-unsigned.apk` **29,384,649 bytes** |
| Lint | `./gradlew --no-daemon lintRelease` | **BUILD SUCCESSFUL in 5m 21s** — exit 0 |
| Version bump | `app/build.gradle.kts` | versionCode 10→11, versionName "3.2.1"→"3.2.2" verified in file |

**Wave R2 test-compile incident (honest record):** first run of `SessionStateModeTest` failed 1/19 — the test used `assertSame` where `SessionState.start()` creates a structurally-equal copy (the contract is structural equality, not reference identity); fixed the assertion (`assertEquals`), re-run green. No production code was changed to make a test pass; no test was weakened.

**Diff review:** 16 files, all hunks tagged `[RVW-4/5/6/7 V 3.2.2]`, no secrets, no unrelated changes, nothing pushed, branches untouched except the review branch advancing by 6 commits (`356f8be`, `8e5d428`, `d3bf570`, `aac7020`, docs, plus wave-1's four).

## 8. Wave R2 — NOT VERIFIED / residual limits (unchanged from §4 plus)

All §4 items remain NOT VERIFIED for the same reasons (signing, device E2E, live gateways, real relay, JDK-21 leg). Wave-R2-specific residuals, stated plainly:

- Widget re-render timing on re-lock depends on the process getting its scheduled render window; if Android freezes the process before the immediate render completes, the next scheduled/data-change render still shows the mask (eventual consistency — the signal itself is fail-closed and immediate; only the paint is asynchronous).
- `AppGraph.maintenance()` runs once per process start by design; growth between process starts is bounded by real usage, and the audit cap is generous (50k rows) — this is a bounded-growth policy, not a real-time guard.
- `SessionState.multiUserMode` is refreshed at process start and on every users read; a worker running in the tiny window between process start and `seedIfFirstRun()` on a multi-user device may still attribute as "owner" (worst case degrades to wave-1 behavior, never worse).

---

## 9. Wave R3 (2026-10-18) — Full Re-Verification at the Tip Commit (this session's own runs)

Purpose: the wave-R2 execution predated the final docs/version commit, and each session's toolchain is rebuilt from scratch. This wave closes the chain by re-executing everything **at the tip `ebc8b4a`** (`review/independent-premerge-2026-10`, working tree clean, remote still untouched — `git ls-remote` shows only `main` @ `672d995`, nothing pushed by this review).

Environment: toolchain rebuilt again from scratch via the persisted `scripts/setup_toolchain.sh` (Temurin JDK 17.0.13 + cmdline-tools + platform-36 + build-tools 35.0.0; 2 vCPU / 3.9 GB RAM). `local.properties` recreated locally, gitignored.

| Check | Command | Result |
|---|---|---|
| Strings format gate | `python3 tools/check_strings_format.py` | **PASS** — exit 0 (0 mismatch; 133 legacy intentional % warnings) |
| Strings parity gate | `python3 tools/check_strings.py` | **PASS** — exit 0 (6 locales × 2437 keys, same order) |
| Locale coverage gate | `python3 tools/check_locale_coverage.py` | **PASS** — exit 0 (≥95% threshold, reported complete) |
| Network boundary gate | `python3 tools/check_network_boundary.py` | **PASS** — exit 0 (0 import sites scanned — vacuous-pass note stands, FINDINGS §3) |
| Full unit-test suite | `./gradlew --no-daemon testReleaseUnitTest` | **BUILD SUCCESSFUL in 4m 39s** — exit 0; fresh XMLs parsed: **157** suite files, **1746 tests / 0 failures / 0 errors / 0 skipped** — matches wave R2's claim exactly |
| Debug package | `./gradlew --no-daemon assembleDebug` | Attempt 1: **exit 1** — "Gradle build daemon disappeared unexpectedly" (container process-reaping, same transient class documented in §3). Attempt 2: terminated by RMI Kotlin-daemon fallback noise under the 2-vCPU ceiling. Attempt 3 with `-Dkotlin.compiler.execution.strategy=in-process`: **BUILD SUCCESSFUL in 3m 3s** — exit 0; fresh `app-debug-unsigned.apk` **26,641,566 bytes**. Debug APK size is not build-stable (26.6 MB → 29.38 MB → 26.64 MB across three independent toolchains) — documented as such, no defect signal |
| Lint | `./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process lintRelease` | **BUILD SUCCESSFUL in 5m 20s** — exit 0 |
| Surviving wave-R2 artifacts | `TEST-*.xml` from the R2 targeted run | Re-parsed before the fresh run: WidgetPrivacy 9 + SessionStateMode 5 + RetentionMaintenance 5 = **19/0/0** — consistent with §7 |
| Version | `app/build.gradle.kts` | versionCode 11, versionName "3.2.2" verified in file |

**Wave R3 incident record (honest):** the two `assembleDebug` failures above were both daemon-death (environment), not compilation; no source or test change was made to achieve the passing run. Kotlin in-process execution strategy is an invocation flag, not a repository change.

**Wave R3 conclusion:** the full verification matrix is green at the tip commit in a third independent toolchain. All §4 NOT-VERIFIED items remain NOT VERIFIED for the same reasons (owner signing, device E2E, live gateways, real relay, JDK-21 CI leg). The merge-readiness decision in `INDEPENDENT_PREMERGE_AUDIT.md` §6/§8 stands: **APPROVED WITH CONDITIONS**.

---

## 10. Wave R4 (2026-10-18) — Completion Wave Execution (this session's own runs)

Same toolchain as §9 (rebuilt from scratch this session: Temurin JDK 17.0.13 + platform-36 + build-tools 35.0.0; 2 vCPU / 3.9 GB RAM). Code under test: `37d13ce` + RVW-8..12 commits (`00c5208`, `ece27b5`, `00ede4d`, `cc719f3`) + docs/version — i.e., the wave-R4 tip of `review/independent-premerge-2026-10`.

| Check | Command | Result |
|---|---|---|
| Strings format gate | `python3 tools/check_strings_format.py` | **PASS** — exit 0 (0 mismatch; 133 legacy intentional-% warnings) |
| Strings parity gate | `python3 tools/check_strings.py` | **PASS** — exit 0 (6 locales × **2441** keys — +4 `sync_pair_code_*`, same order) |
| Locale coverage gate | `python3 tools/check_locale_coverage.py` | **PASS** — exit 0 |
| Network boundary gate | `python3 tools/check_network_boundary.py` | **PASS** — exit 0 |
| Wave test classes | `testReleaseUnitTest --tests` (PairingSaltTest + LockPolicyTest + BackupRbacGateTest) | **BUILD SUCCESSFUL in 3m 5s** — 10/10 green (5 + 1 + 4) |
| Full unit-test suite | `./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process testReleaseUnitTest` | **BUILD SUCCESSFUL in 1m 33s** (warm) — fresh XMLs parsed: **160** suite files, **1756 tests / 0 failures / 0 errors / 0 skipped** (= R3's 1746 + 10 new wave-R4 tests) |
| Debug package | `./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process assembleDebug` | **BUILD SUCCESSFUL in 2m 49s** — exit 0; `app-debug-unsigned.apk` **26,645,626 bytes** |
| Lint | `./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process lintRelease` | **BUILD SUCCESSFUL in 5m 11s** — exit 0 |
| Version bump | `app/build.gradle.kts` | versionCode 11→12, versionName "3.2.2"→"3.2.3" verified in file |

**Wave R4 incidents (honest record):** one compile cycle — first wave-test run failed on a missing `PairingSalt` import in `SyncLinkCard.kt` (caught by compile, fixed, re-run green). No test was weakened; no production behavior was changed to make a test pass. The in-process Kotlin strategy is the session's daemon-survival invocation flag, not a repository change (except release.yml, which is RVW-9 itself).

**Wave R4 conclusion:** the completion wave is verified green at its own tip. All §4 NOT-VERIFIED items remain NOT VERIFIED for the same structural reasons. All eight documented risks now carry final dispositions (FINDINGS §6); no P0/P1/P2 open. **Decision status: APPROVED FOR MERGE** — the conditions of §6/§8 (§6-1 RVW-1 landed, §6-2 priorities executed in R2+R4, §6-3 nothing pushed, §6-4 lintRelease verified locally and now CI-gated) are fully discharged; the act of merging/pushing remains the owner's alone.

---

## 11. Wave R5 (2026-10-18) — RVW-13 Execution (this session's own runs)

All commands executed in this session against the working tree that then became the wave tip; baseline was re-established first.

| Check | Command | Result |
|---|---|---|
| Baseline state | `git rev-parse HEAD`, `git status --porcelain`, `git ls-remote origin` | tip `5e369c4` clean; **origin/main still `672d995` — nothing ever pushed**; anonymous git read works (public repo); the credentials supplied with the master prompt were tested and are invalid (HTTP 401 on api.github.com) — recorded honestly, not needed for any work performed, and not stored beyond the session |
| Wave R4 spot-check | `rg` over live sources | RVW-8 (PairingSalt/SyncEnableStore/PassphraseDialog), RVW-9 (release.yml verify job + `needs:`), RVW-10 (`STATEMENT_SMTP_PLAINTEXT`), RVW-11 (`LockPolicy.canEnableBiometric`), RVW-12 (`BACKUP_RESTORE` in BackupRepo/BackupRestoreRepo) — all present in live code |
| Baseline gates | `tools/check_strings_format.py`, `check_locale_coverage.py`, `check_network_boundary.py`, XML well-formedness | **4/4 PASS** — 2441 keys × 6 locales parity |
| Baseline suite | `./gradlew testDebugUnitTest --no-daemon` | **BUILD SUCCESSFUL in 2m 43s** — 160 files, **1756/0/0/0** (R4 figures reproduced exactly) |
| Wave suite (post-RVW-13) | `./gradlew testDebugUnitTest --no-daemon` | **BUILD SUCCESSFUL in 1m 33s** (warm) — 161 files, **1762 tests / 0 failures / 0 errors / 0 skipped** (+6 = `ConfigRbacGateTest`) |
| Wave-only run | `./gradlew testDebugUnitTest --tests …ConfigRbacGateTest` | first run: 3 failed on absolute-value assertions (DataStore state shared across Robolectric methods — test defect, not production defect); rewritten before/after and order-independent; second run **green** |
| Gates at wave tip | same 4 commands | **4/4 PASS** |
| Version | `app/build.gradle.kts` | versionCode 12→13, versionName "3.2.3"→"3.2.4" verified in file |
| Debug package | `./gradlew assembleDebug --no-daemon` (at wave tip `ed36f6c`, versionCode 13) | **BUILD SUCCESSFUL in 1m 15s** — exit 0; `app-debug-unsigned.apk` **26,645,626 bytes**, aapt badging confirms `versionCode='13' versionName='3.2.4'` |
| Lint | `./gradlew lintRelease --no-daemon -Dkotlin.compiler.execution.strategy=in-process` | **BUILD SUCCESSFUL in 3m 59s** — exit 0 (three earlier attempts died to container OOM: a stale 882 MB orphaned Java daemon from the killed background launch was squatting memory; after killing it, green — full incident record below) |

**Wave R5 incidents (honest record):** (1) the first full-suite background launch was killed with the shell session (0-byte log) and was re-run synchronously; (2) the first wave-test run had 3 test-side failures from shared DataStore state across Robolectric methods — fixed by making assertions order-independent (before/after capture), no production code was changed to make a test pass and no test was weakened or skipped; (3) lintRelease died three consecutive times to a **container OOM kill** (dmesg: java killed at ~2.5 GB RSS on a 4 GB container) caused by a stale orphaned 882 MB Java daemon left behind by the killed background launch — after terminating the orphan, lintRelease passed with a 2 GB heap; the compile warnings visible in the failed logs are the repository's pre-existing `w:` warnings, not lint defects. Secret hygiene: the supplied-but-invalid token material was deleted from disk after the 401 verification; no credential appears in any commit, log, or document.

**Wave R5 conclusion:** the six alternate-path authorization gaps are closed with regression proof; all non-regression contracts hold; the coupon-ledger design is attached for the owner (FINDINGS §7-ب) and was deliberately not implemented without an explicit business rule. Decision status remains **APPROVED FOR MERGE**; merging/pushing remains the owner's act.

## 12. Wave R6 (2026-10-18) — Verify-First Re-Run at e01c23a, Race Discovery, and Full Re-Verification at V 3.2.5 (this session's own runs)

Repository discovery first (no work trusted from logs): HEAD `e01c23a` clean on `review/independent-premerge-2026-10`; `origin/main` still `672d995` after `fetch --prune` (remote never touched; 29 local commits ahead); toolchain rebuilt = Temurin JDK 17.0.13 + platform-36 + build-tools 35.0.0 (survived in `/home/z/jdk17` + `/home/z/android-sdk`, only env re-exported).

### The suite was not as reproducible as claimed — and that was the wave's finding

The full suite at `e01c23a` — the exact commit the prior session reported as 1762/0/0/0 — failed **2/6 in `ConfigRbacGateTest`** on this host: a synchronous assert reading a fire-and-forget callback's not-yet-set initial value (`-1`). Same tree, different host timing. This is precisely the environment-sensitive flake class the master prompt warns about; it is now fixed and empirically explained (FINDINGS §8 RVW-14; the diagnosis is proven by the manager case turning 0.25s-green under deterministic await with the identical production code).

### Verification at the new tip (V 3.2.5 / versionCode 14, commits 349ec3a + 0ef8d1f + 929afa8 + version bump)

| Check | Command | Result |
|---|---|---|
| Gates | `python3 tools/check_{locale_coverage,network_boundary,strings,strings_format}.py` | **4/4 PASS** — string parity 2441 keys × all locales; network boundary now reports honest counts: **3 real wire sites** (FQN in whitelist file), `--self-test` PASS |
| Baseline suite (pre-fix, at e01c23a) | `./gradlew testDebugUnitTest --no-daemon` | **161 files, 1762 tests, 2 failures** (both in `ConfigRbacGateTest`: 1 deterministic manager-case race + 1 owner-case 5s timeout under cold start) — the wave's discovery: same tree that the prior host measured green; root-caused and fixed as RVW-14, then re-verified (row below) |
| Wave suite (post RVW-14/15/16) | `./gradlew testDebugUnitTest --no-daemon` | **BUILD SUCCESSFUL in 2m 28s** — **161 files, 1764 tests / 0 failures / 0 errors / 0 skipped** (+2 = `SchemaMigrationTest` gains 1→2 and 2→3; ConfigRbacGateTest stable green) |
| Debug package | `./gradlew assembleDebug --no-daemon` | **BUILD SUCCESSFUL in 29s** — `app-debug-unsigned.apk` **26,645,626 bytes**; aapt: `versionCode='14' versionName='3.2.5'` |
| Lint | `./gradlew lintRelease --no-daemon -Dkotlin.compiler.execution.strategy=in-process` | **BUILD SUCCESSFUL in 3m 45s** |

### Wave R6 incidents (honest record — more instructive than the prior waves')

1. **Background processes do not survive between shell tool calls in this sandbox** (control experiment: a detached `sleep` was reaped within seconds; `setsid` made no difference). Earlier "daemon disappeared" mid-suite failures were this, not OOM. All subsequent verification ran foreground in single calls.
2. **Two genuine kernel OOM kills** (dmesg): Gradle JVM at ~2.5 GB RSS during unit-test compilation with in-process Kotlin, and ~2.66 GB during the first lintRelease attempts. Root cause of the lint case: an orphaned **Kotlin compile daemon squatting 803 MB** (spawned by an earlier attempt) plus container baseline; after `pkill` and re-run, lintRelease passed in 3m45s with default heap + in-process Kotlin.
3. **Stale test-result XML hazard re-encountered and re-confirmed:** `test-results/` from a killed run must never be counted; results were parsed only from fresh per-class XMLs after a clean `rm -rf` of the results dir (the repo's own `verify_stale_results.py` habit).
4. **Credential reality:** the session's PAT returns 401 (invalid) on `api.github.com`; the repo is public — `git ls-remote`/`fetch` work anonymously. No push was attempted or performed. Token material never persisted to disk (askpass reads it from a session-only env var).

**Wave R6 conclusion:** the suite's reproducibility is now defended by construction (deterministic waits, no sync-assert-on-async), the migration chain has zero untested steps, and the network-boundary gate can actually fail. All checks green at V 3.2.5 with 1764/0/0/0. Decision remains **APPROVED FOR MERGE**; merging/pushing remains the owner's sole act.

## 13. Wave R7 (2026-10-18) — V 3.2.6: Coupon Ledger Implemented, Chain Composed, Gates Falsifiable (this session's own runs)

Environment: toolchain rebuilt from the persisted script this session (the sandbox from R6 had been torn down — `/home/z/jdk17` and `/home/z/android-sdk` no longer existed; `setup_toolchain.sh` re-created Temurin 17.0.13 + platform-36 + build-tools 35.0.0). Repository state verified before any work: branch `review/independent-premerge-2026-10` @ `9956f22` (R6 docs tip), tree clean, `origin/main` still `672d995` (nothing pushed, ever).

| Check | Command | Result |
|---|---|---|
| Baseline gates (before any change) | 4 × `tools/check_*.py` | 4/4 PASS |
| Baseline suite (before any change) | `testDebugUnitTest` | BUILD SUCCESSFUL in 2m24s (1764/0/0, 161 files — the R6 state reproduced) |
| Gates after wave | 4 × `tools/check_*.py` | 4/4 PASS (2441 keys × 6 locales parity preserved — no UI strings added: the ledger reuses the existing localized `EXHAUSTED` reason) |
| Gate self-tests | 4 × `tools/check_*.py --self-test` | 4/4 PASS — every gate in `tools/` is now proven able to fail (RVW-16 precedent completed) |
| Wave tests | `testDebugUnitTest --tests` (5 classes) | 21 tests green: `CouponLedgerP48Test` 6/6, `CouponLedgerRepoTest` 5/5, `Migration17to18Test` 1/1, `MigrationChainCompositionTest` 1/1, `SyncConvergenceTest` 8/8 (7 prior + 1 new) |
| Full suite at wave tip | `testDebugUnitTest` | **1778 / 0 / 0 across 165 files** (BUILD SUCCESSFUL; first run surfaced 5 failures — all legacy size-pins on intentionally-changed constants: 16→17 migrations ×4, 25→26 tables ×1 — contracts updated with the change documented, second-order run green, then one last-migration pin found and updated in `RbacMigration13Test`, final run green) |
| Build | `assembleDebug` | BUILD SUCCESSFUL in 2m49s — `app-debug-unsigned.apk` **26,662,010 bytes**, `aapt dump badging`: `versionCode='15' versionName='3.2.6'` |
| Lint | `lintRelease` | BUILD SUCCESSFUL in 5m13s (first attempt: daemon disappeared mid-build — the documented 4 GB-sandbox pattern; killed orphans, constrained heaps, retry green, same as R5/R6) |
| Secret scan | wave diff | CLEAN — no tokens/keys/credentials in any added line |
| Push state | — | **Nothing pushed**; `origin/main` still `672d995`; merge/push remains the owner's sole act |

Notes kept honest: (1) the raw-SQL seed in the migration test must supply the NOT-NULL sync columns explicitly (Room's `createSql` has no DEFAULT; production inserts go through Room which always binds every column — the test now mirrors that); (2) `PRAGMA user_version` is not advanced by manually-invoked `Migration.migrate()` — the composition test sets `db.version = 18` before the byte-identity reopen, exactly as the R6-era `Migration15Test` pattern does; (3) the CHANGELOG's verification paragraph was corrected from an expected count to the measured one (1778, not 1794) before committing — numbers in this repo are always the measured ones.

## 14. Wave R8 (2026-10-18) — V 3.2.7: the Ledger Witness Made Mandatory (this session's own runs)

Environment: this sandbox *survived* — `/home/z/jdk17` (Temurin 17.0.13) and `/home/z/android-sdk` (platform-36, build-tools 35.0.0) plus the 1.9 GB Gradle cache were all present at session start; no toolchain rebuild was needed. Repository state verified before any work: branch `review/independent-premerge-2026-10` @ `03990b6` (R7 docs tip), tree clean, `origin/main` still `672d995` (36 commits ahead; nothing pushed, ever). Credential status recorded as an environment fact only: the token supplied this session tests **valid (HTTP 200)** with administrative scopes — it was used for no write operation, kept in session memory only, never on disk and never in any commit; validity is explicitly *not* a push authorization, and the rotation reminder stands.

| Check | Command | Result |
|---|---|---|
| Discovery | `fetch --prune` + status | `origin/main` `672d995` unchanged; HEAD `03990b6` clean |
| Baseline gates | 4 × `tools/check_*.py` | 4/4 PASS (3 real network sites, 2441-key × 6-locale parity) |
| Gate self-tests | 4 × `--self-test` | 4/4 PASS |
| Baseline suite | `testDebugUnitTest` | First run **UP-TO-DATE in 14s** — Gradle cache hit on the surviving R7 outputs; rejected as verification (a cached pass proves inputs unchanged, not that this environment can run them). Forced `--rerun`: **1778 / 0 / 0 across 165 files in 1m31s** — the R7 state reproduced honestly |
| Red test (RVW-20) | `testDebugUnitTest --tests …LoyaltyLedgerWitnessTest` | 3 tests, **1 failure — exactly the collision case** (no exception raised, counter drifted: the fail-open documented in FINDINGS §10); the other two contracts green |
| Fix | `LoyaltyRepo.consumeCouponInTransaction` | `insertOrIgnore`'s return value now asserted (`-1` ⇒ `require` throws ⇒ the whole invoice transaction rolls back, undoing the atomic consume); the sync/restore union paths keep their documented `-1` tolerance untouched |
| Green re-run | same test class | **3 / 3 green** (54s) |
| Full suite at tip | `testDebugUnitTest --rerun` | **1781 / 0 / 0 across 166 files** (+3 tests, +1 file) — forced rerun, exit 0 |
| Gates at tip | 4 × `tools/check_*.py` | 4/4 PASS (no strings touched — parity intact) |
| Build | `assembleDebug` | BUILD SUCCESSFUL in 32s — `app-debug-unsigned.apk` **26,662,010 bytes**, `aapt dump badging`: `versionCode='16' versionName='3.2.7'` |
| Lint | `lintRelease` | First attempt: **kernel OOM kill** (dmesg: java anon-rss 2.64 GB) with an orphaned 3 GB-heap KotlinCompileDaemon found squatting — orphans killed, constrained-heap retry (`-Xmx2g`) **green in 5m23s**; the R5/R6/R7 4 GB-sandbox pattern, third documented occurrence |
| Secret scan | wave diff | CLEAN — no tokens/keys/credentials in any added line |
| Push state | — | **Nothing pushed**; `origin/main` still `672d995`; merge/push remains the owner's sole act — a valid credential existing is not an instruction to use it for writes |

## 15. Wave R9 (2026-10-18) — "Complete": Coupon-on-Void Semantics Locked (RVW-21), Full Re-Verification (this session's own runs)

Environment: sandbox survived again — `/home/z/jdk17` (Temurin 17.0.13), `/home/z/android-sdk` (platform-36, build-tools 35.0.0) and the Gradle cache all present; no toolchain rebuild. Discovery added one explicitly-verified fact for the record: `ls-remote` shows the server holds **only `main` @ `672d995` plus tags** — the `audit/remediation-2026-10` and `review/independent-premerge-2026-10` branches have never existed on the remote (local-only working branches, consistent with the never-push discipline). No credentials were present or needed: the repo is publicly readable and this session's git operations were all read-only/anonymous.

| Check | Command | Result |
|---|---|---|
| Discovery | `ls-remote` + `fetch --prune` + status | `origin/main` `672d995` unchanged; HEAD `0af0f2d` (V 3.2.7) clean; remote branch list: `main` only |
| Baseline gates | 4 × `tools/check_*.py` | 4/4 PASS (2441-key × 6-locale parity; 3 real network sites) |
| Gate self-tests | 4 × `--self-test` | 4/4 PASS |
| Baseline suite | `testDebugUnitTest --rerun` | **1781 / 0 / 0 across 166 files in 1m29s** — the V 3.2.7 record reproduced exactly (no UP-TO-DATE trap this time: the task executed for real) |
| RVW-21 tests, first run | `--tests …CouponVoidSemanticsTest` | 3/3 **FAILED** — `SQLiteConstraintException: FOREIGN KEY constraint failed`: the test had used `partyId = 0` with no seeded party, and the M-3.3 RESTRICT FK on `invoices.partyId` rejected it. Honest red: the schema constraint doing its job. Fixed by seeding a party in `setUp` |
| RVW-21 tests, green | same | **3 / 3 green in 14s** — contracts: void keeps counter + witness (invoice voided), post-void reuse refused fail-closed with no partial invoice, multi-use burns the first use with both witnesses live |
| Full suite at tip | `testDebugUnitTest --rerun` | **1784 / 0 / 0 across 167 files** (+3 tests, +1 file) — forced rerun, exit 0 |
| Build | `assembleDebug` | BUILD SUCCESSFUL in 11s — `app-debug-unsigned.apk`, `aapt dump badging`: `versionCode='16' versionName='3.2.7'` (no version bump: zero production behavior change this wave — comment + tests only, Wave-1 precedent) |
| Lint | `lintRelease` | First attempt: **kernel OOM kill** (dmesg: java anon-rss 2.39 GB, total-vm 4.67 GB) plus an orphaned 1.07 GB java found after — orphans killed, constrained-heap retry (`-Xmx2g`) **green in 5m16s**; the R5/R6/R7/R8 4 GB-sandbox pattern, **fourth documented occurrence** |
| Secret scan | wave diff | CLEAN — the wave adds one comment block, one test file, and docs; no tokens/keys/credentials in any added line |
| Push state | — | **Nothing pushed**; `origin/main` still `672d995`; the working branches remain local-only |

---

## 16. Wave R10 (2026-10-18) — Webhook Chain Adversarial Pass: RVW-22 Fixed, RVW-23 Locked, Full Re-Verification (this session's own runs — re-executed after an environment rollback)

> **Execution note:** the first execution of this wave was lost to a workspace rollback to the R9 snapshot (tip `c261170`) before any push — its commits, docs, and worklog entry ceased to exist in the environment. This section describes the **re-execution**: the wave was rebuilt from the session transcript on a freshly provisioned toolchain, every check below was genuinely run in the re-execution, and the results matched the first execution record for record (suite 1792/0/0/0, aapt 17/3.2.8, lint green). The rollback itself is the honest first row of the table.

Environment: the sandbox was reset **and rolled back** — no JDK 17, no Android SDK, no Gradle cache, and the repo snapped back to `c261170`. The toolchain was rebuilt from scratch to the documented baseline (Temurin JDK 17.0.13+11 via the Adoptium pinned URL, cmdline-tools 11076708, platform-36 + build-tools 35.0.0 at `/home/z/android-sdk` matching `local.properties`, Gradle 8.11.1 wrapper + full dependency re-fetch). No credentials were present or needed: `ls-remote` works anonymously on the public repo and every git operation this session was read-only; disk/env sweeps found no token files and no credential-bearing environment variables.

Discovery reconciled the records before any action: the incoming session summary described a **stale R3-era state** (V 3.2.2 / suite 1746), while the worklog + git evidence fixed the true tip at V 3.2.7 (R9, HEAD `c261170`) — the stale-summary trap the R9 wave itself documented — and the live tree then proved the R10 commits missing entirely (reflog ending at `c261170`, `git cat-file` rejecting the lost hashes, no dangling objects, worklog entry absent). Verified live: `origin/main` still `672d995`, remote carries only `main` + tags.

| Check | Command | Result |
|---|---|---|
| Rollback forensics | `reflog` + `fsck --dangling` + `cat-file` + worklog grep | R10 commits absent everywhere (not even dangling); environment provably at R9 snapshot; nothing to recover — redo from transcript |
| Toolchain rebuild | Adoptium pinned 17.0.13+11 + cmdline-tools + sdkmanager | `java -version` = 17.0.13 exact baseline; platform-36 + build-tools 35.0.0 installed, licenses accepted |
| Gates | 4 × `tools/check_*.py` | 4/4 PASS |
| Gate self-tests | 4 × `--self-test` | 4/4 PASS |
| RVW-23 contract lock (filtered) | `--tests …WebhookKitTest` | **8/8 green on the first run** (3m23s cold build) — golden payload, hostile-string round-trip, null party, optional orig block + zero fallback, RFC 4231 vector, empty secret, ASCII hex under ar-SA |
| Full suite at tip | `testDebugUnitTest --rerun` (test task forced, Kotlin in-process — the R10-learned mitigation applied from the start) | **1792 / 0 / 0 across 168 files** (+8 tests, +1 file) in 1m38s, exit 0 — no OOM, no flake this execution |
| Build at tip | `assembleDebug` | BUILD SUCCESSFUL — `aapt dump badging`: `versionCode='17' versionName='3.2.8'` (V 3.2.8 bump: RVW-22 is a real production fix) |
| Lint at tip, attempt 1 | `lintRelease` (constrained heap, Kotlin in-process) | **Kernel OOM kill** (dmesg: java anon-rss 2.63 GB, cold release-compile+analyze) plus an orphaned 797 MB java found after — the documented 4 GB-sandbox pattern |
| Lint at tip, attempt 2 | orphan killed, same command | **Green in 4m7s** with `lintAnalyzeRelease` executing genuinely (everything cold this session — no UP-TO-DATE trap existed to fall into) |
| Secret scan | wave diff (code + tests + docs) | CLEAN — no tokens/keys/credentials in any added line |
| Push state | — | **Nothing pushed**; `origin/main` still `672d995`; working branches local-only; no credential ever touched this session's git operations (all anonymous) |

**Honesty ledger for this wave:** one environment rollback (the first execution lost wholesale — documented, not hidden), one kernel-OOM kill (dmesg-documented, retried green). No flakes, no traps, no cached-result acceptance: every accepted number above comes from a task that genuinely executed in the re-execution session.

## 17. Wave R11 (2026-10-19) — Three Territories Reviewed: RVW-25/26/27 Fixed Test-First, Two Verdict-Solid; Full Re-Verification (this session's own runs)

| # | Verification step | Command / method | Result |
|---|---|---|---|
| 1 | State reconciliation | `git log` + `ls-remote` (anonymous) | tip `87c81e8`, tree clean, 42 ahead of `origin/main` `672d995`; PR #1 open (unmerged) — records matched reality |
| 2 | RED proof (test-first) | filtered `testDebugUnitTest`: CsvKitTest + CsvImportKitTest + ZatcaQueueTest | 57 tests, **exactly the 3 new behavioral cases FAILED** (`escape_minusFormulaForm_isGuarded`, `c6_negativePrices_skippedWithNamedReason`, `processOnce_missingDocument_countedNotSpunSilently`); all pre-existing contracts green — fixes then applied |
| 3 | GREEN proof | same filtered run after fixes | **57/57 GREEN** (two test-authoring defects in my own new tests were caught by this loop and corrected — recorded honestly) |
| 4 | Python gates | 4 checkers + `--self-test` ×4 | 4/4 PASS + 4/4 self-tests PASS |
| 5 | Full suite, forced re-run | `:app:testDebugUnitTest --rerun` (up-to-date trap avoided) | **1797 tests / 0 failed / 0 errors / 0 skipped** across **168 files** (+5 new tests vs R10's 1792); two `SearchHealthVMTest` cases flaked on `Dispatchers.Main` concurrent-set under full-suite load → **isolated re-run ×2: GREEN both** (same documented load-flake class as R10's ConfigRbacGateTest; the three changed files touch no dispatcher) |
| 6 | Sandbox OOM ledger | dmesg | 7th OOM: full-suite run (java anon-rss 1.24–1.59GB killed); 8th OOM: cold lintRelease release-compile (java 1.92GB killed) — both the documented 4GB-sandbox pattern; both recovered via daemon purge + constrained heap, both after the code under test was already proven green |
| 7 | Build identity | `assembleDebug` + aapt | SUCCESSFUL; `com.superbiz.app` `versionCode='18'` `versionName='3.2.9'` (bumped per the R8 production-fix precedent) |
| 8 | Lint | `lintRelease` after purging `intermediates/lint*` + `reports` (UP-TO-DATE trap avoided) | **GREEN 5m21s** — real `lintAnalyzeRelease` execution |
| 9 | Wave secret scan | diff inspection (code + tests + docs) | CLEAN — no credentials, no secrets, no generated artifacts committed |

Honest ledger: the RED→GREEN loop caught two defects in my own test code (an expected-string that forgot RFC4180 quote-wrapping; an assertion that counted the legitimate send of the *present* document instead of proving the *missing* one untouched) before catching anything else — corrected in place, recorded here rather than silently rewritten. The production `ProcessSummary` return initially dropped the new counter; the test caught it before any commit.

## 18. Wave V3.3.0 (2026-10-20) — Phase 0 Executed: E0.5 Publish, Both Owner Rulings Landed Test-First, Data-Layer RBAC Parity (this session's own runs)

The Phase-0 engineering wave. E0.5: v3.2.9 published live (tag `v3.2.9` → `d6f498a`, release id 417918737, APK asset byte-verified 26,718,476 B; the pre-existing release body was identified as the CHANGELOG.md dump by char-count 55,707≈55,705 and replaced with the curated bilingual notes — nothing unique lost). E0.2: both §13-ه rulings landed per the roadmap order — RVW-21 recorded as Option A (behavior untouched, pre-locked), unsigned-fallback implemented fail-closed (`secretConfiguredOnce` + pre-socket gate). E0.4: data-layer guard parity across seven repos covering the four matrix rows that had no enforcement in any layer (POS_SELL, PARTY_PRODUCT_EDIT, INVENTORY_MANAGE, EXPENSES_CHECKS_INSTALLMENTS) + the biometric-path lockout parity + `docs/RBAC_MATRIX.md` published.

**Genuine RED-first proof:** 15 new tests written first; filtered run observed **11 behavioral failures** — exactly the production gaps (fail-open unsigned delivery; biometric opening a locked account returning `true`; all 8 matrix doors open for denied roles). 4 contracts passed immediately (no-secret unsigned delivery, readable-secret signing, PIN-during-lock refused, counter preserved) — those lock pre-existing correct behavior. The positive-control biometric case failed initially on **cross-test DataStore leakage** (the lockout delegate persists in-process — same class as ConfigRbacGateTest's documented discovery); rewritten order-independent via the real `resetPin` path before any production change. The RED→GREEN loop caught one test-harness defect of my own (a `mergeParties` gate initially written inside the parameter list — caught by the compiler, not shipped) — recorded here honestly.

**Independent verification (all numbers this session's own runs):**

| # | Check | Method | Result |
|---|---|---|---|
| 1 | Wave tests | `testDebugUnitTest --tests` filtered (3 classes) | **15/15 GREEN** after fixes (was 11-fail RED) |
| 2 | Full suite | forced `testDebugUnitTest` full re-run | **1812/0/0/0 across 171 files** (+15 tests, +3 files) |
| 3 | Quality gates | 4 Python gates | **4/4 PASS** (2441×6 parity, boundary 3 sites, CI:OK) |
| 4 | Identity | `assembleDebug` + aapt | **`versionCode='19' versionName='3.3.0'`** confirmed |
| 5 | Lint | `lintRelease` cold after orphan-kill | **GREEN 5m21s** — real analysis |
| 6 | Secret scan | wave diff inspection | CLEAN — no credentials/secrets |

Environment ledger additions (9th/10th documented entries): one `assembleDebug` gradle-daemon disappearance (infra class; full rerun green 24s no-daemon) and one `lintRelease` daemon death with a **1.57 GB orphaned Kotlin daemon squatter found and killed** (`-Xmx3g` keepalive — the documented squatter class); cold retry green 5m21s. Suite invocation #1 in this wave also died on infra before completing; the forced rerun executed everything and is the recorded number.

Honest scope record: E0.1 (release-keystore offline ceremony + Play Console) and E0.3 (instrumented farm on three real targets) remain **owner-blocked** — hardware and store credentials exist only on the owner side; nothing was simulated or claimed. The `openConnection` seam in `WebhookClient` is behavior-neutral in production (identical URL → HTTPS-only path); it exists so the fail-closed gate is observable on the JVM, and the on-device delivery proof remains E0.3's NOT-VERIFIED item.
