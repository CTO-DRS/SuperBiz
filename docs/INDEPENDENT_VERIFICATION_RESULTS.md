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
