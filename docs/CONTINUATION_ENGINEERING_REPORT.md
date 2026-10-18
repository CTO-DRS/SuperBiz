# SuperBiz Continuation Engineering Report — Wave R6 (2026-10-18)

Author: independent chief-engineer continuation session · Branch: `review/independent-premerge-2026-10` · Tip at writing: `be74956` · Remote policy: **nothing pushed, ever, without the owner's explicit act**

## 1. Executive Summary

Per the continuation master prompt, this wave re-verified every prior claim before acting ("verify first"), then closed what the re-run surfaced. The headline: **the test suite itself was not fully reproducible across hosts** — a sync-assert-on-async race in the R5 wave's `ConfigRbacGateTest` presented a false RBAC denial on a slower host. Production code was exonerated by construction (no `-1` return path exists). Two latent integrity gaps were also closed: the migration chain's last two untested steps (1→2, 2→3) and a network-boundary CI gate that had been vacuous since day one. All work is test/tooling integrity; **no production behavior changed**. Final verified state: **4/4 gates · 1764/0/0/0 (161 files) · assembleDebug (26,645,626 B, versionCode 14) · lintRelease green · decision unchanged: APPROVED FOR MERGE**.

## 2. Repository State (verified, not assumed)

- HEAD `be74956` on `review/independent-premerge-2026-10`, work tree clean; 33 commits ahead of `origin/main`.
- `origin/main` = `672d995` — fetch `--prune` confirmed the remote was never touched; the branch topology (main → audit `31752e7` → review) matches all prior records.
- Tags unchanged (to `v3.2.0`); no stash; local git config clean (no embedded credentials; no `~/.git-credentials`).
- Credential reality: the supplied PAT returns **HTTP 401** on `api.github.com` (both plausible shapes tested); the repo is **public** — `ls-remote`/`fetch` work anonymously, so reads never needed it. The token was never persisted to disk (askpass reads a session-only env var) and appears in no commit/log/document.

## 3. Baseline Reproduction

| Check | Result at wave tip `be74956` (V 3.2.5 / versionCode 14) |
|---|---|
| 4 Python gates | PASS ×4 — string parity 2441 keys × all locales; network gate now with self-test and honest counts |
| Unit suite | `testDebugUnitTest --no-daemon` → **1764/0/0/0 across 161 files** in 2m28s |
| Debug package | `assembleDebug` → SUCCESSFUL, 26,645,626 bytes, aapt confirms `versionCode='14' versionName='3.2.5'` |
| Lint | `lintRelease` → SUCCESSFUL in 3m45s |

Pre-fix baseline at `e01c23a` measured **1762 tests / 2 failures** (both `ConfigRbacGateTest`) on this host — the wave's discovery (§5). Environment notes: the sandbox reaps background processes between tool calls (proven by control experiment), and two genuine kernel OOM kills occurred (compile ~2.5 GB RSS; lint ~2.66 GB with an 803 MB orphaned Kotlin daemon squatting memory) — all documented in VERIFICATION_RESULTS §12 so future sessions can reproduce honestly.

## 4. Prior Remediations — Status After Re-Verification

| Class | Items | Status |
|---|---|---|
| Confirmed fixed, independently re-verified | AUD-1..9; RVW-1..13 | All spot-checked live at tip; R4/R5 commits present in history; remote untouched |
| Confirmed fixed this wave (test/tooling integrity) | RVW-14, RVW-15, RVW-16 | Committed with regression proof (`349ec3a`, `0ef8d1f`, `929afa8`) |
| Owner decisions, documented, deliberately not implemented | General RBAC closure below the execution boundary (automation contract); coupon append-only ledger design (FINDINGS §7-ب); financial-history merge stays out (Full replace only) | Unchanged — they remain the owner's acts |
| Structurally NOT VERIFIABLE in this environment | Release signing, device E2E, live ZATCA/SMTP gateways, real sync relay, JDK-21 CI leg | Owner pre-release checklist (VERIFICATION_RESULTS §4) |

## 5. New Findings and Dispositions (Wave R6)

1. **RVW-14 — false RBAC denial from a test race (fixed).** `importSettingsFrom` is fire-and-forget; the test asserted the callback result synchronously and read the initial `-1`. Production `FeatureVMs.kt` cannot emit `-1` (failure maps to `onResult(0, "apply_failed")`), so the observed value could only be "callback not yet called". Fix: deterministic `CompletableDeferred` + 15s await; manager case 4.4–5.1s red → **0.25s green**. No contract weakened: a real denial still fails loudly with an honest timeout.
2. **RVW-15 — migration chain gap (fixed).** 1→2 and 2→3 were the only untested steps of 16. New tests build a minimal v1 base, run the real migrations, and assert columns/indexes against Room's exported `5.json` (non-circular), plus row survival, writability, and rerun safety. The chain is now gap-free step by step.
3. **RVW-16 — vacuous network gate (fixed).** The ADR-001 D1 checker matched `import` statements only while the codebase wires via fully-qualified names; it printed `scanned import sites: 0` forever. Now both shapes are matched (comments stripped), the honest site count is printed (3 real wires; zero = FAIL), and `--self-test` proves it can fail on both violation shapes.

## 6. Risk Items A–G — Final Dispositions

A) RBAC below ViewModel — closed R5 (RVW-13, six alternate paths) + R6 re-verified; general closure remains a documented owner decision. B) Pairing salt/KDF — closed R4 (RVW-8: per-pairing SecureRandom salt for new pairings, v1 byte-compatible). C) Emergency unlock / PIN brute-force — verified closed (LockoutPolicy exponential/monotonic/persistent, tested). D) SMTP "NONE" semantics — closed R4 (RVW-10 audit row per plaintext send; no silent fallback). E) BackupKit merge classification — verified (warnings in 6 locales; financial tables never imported in merge). F) Coupon distributed consumption — design-only, owner decision (FINDINGS §7-ب). G) release.yml — closed R4 (RVW-9 verify-gate before sign/publish).

## 7. Financial Correctness

No new defects. Structural guarantees re-confirmed: piasters-integer money storage since v10 (`migration9to10` test), canonical HALF_UP rounding via one `round2` choke point, double-entry validation on restore, ZATCA identity columns + audit trail. RVW-15 adds the last missing migration coverage — the historical path a v1-era device takes is now proven step-correct.

## 8. Privacy & Security

No new attack surface. This wave *strengthened* assurance tooling: the RBAC suite can no longer produce false denials that would mask real ones, and the network-boundary gate can now actually fail (its previous vacuity is itself recorded as an audit finding). Session hygiene: token material never persisted; secret scan of the wave's commits clean.

## 9. Product Quality

Version 3.2.5 (versionCode 14). User-facing behavior unchanged in R6 by design. CHANGELOG carries the wave entry in the established format; all six locales untouched (string parity gate green).

## 10. Test Gates

4/4 Python gates PASS; suite 1764/0/0/0 across 161 files. The suite is now **reproducibility-hardened**: no synchronous assert on async callbacks remains in the RBAC wave class (checked by survey — only `ConfigRbacGateTest` used the pattern), flow-wait walls are realistic for slow CI hosts, and the network gate has a self-test.

## 11. Performance

Measured, not assumed: suite 2m28s; assembleDebug 29s; lintRelease 3m45s; manager test-case latency 4.4–5.1s (racing) → 0.25s (deterministic). No optimization was performed (nothing warranted it); the RVW-3 EXPLAIN QUERY PLAN proof for v17 identity indexes remains the standing query-plan evidence.

## 12. Documentation Governance

CHANGELOG V 3.2.5 entry; four INDEPENDENT_* docs carry Wave R6 sections with real numbers and honest incident records (VERIFICATION_RESULTS §12 documents the sandbox's background-process reaping, the two OOM kills, and the credential status); this report added as the wave's single-page synthesis. No artificial "end of report" markers; no fabricated numbers anywhere.

## 13. Owner Decision List (unchanged items + hygiene)

1. **Merge/push** — the branch is now 37 commits ahead, verified, APPROVED FOR MERGE; the act is yours (requires a *valid* credential — the supplied PAT is 401).
2. **Token rotation (action now)** — the PAT was pasted into a chat channel; revoke and rotate regardless of validity.
3. ~~Coupon append-only ledger~~ — **ADOPTED AND IMPLEMENTED in Wave R7 (RVW-17, schema v18)** per your instruction to complete the development; residual documented (convergent reconciliation, bounded overshoot).
4. **General RBAC closure below the execution boundary** — keep the automation-contract decision or revisit.
5. **Pre-release checklist** — signing, device E2E, live gateways, real relay, JDK-21 CI leg (structurally unverifiable in this sandbox).
6. **Coupon-void semantics (RVW-21, Wave R9)** — voiding a couponed invoice keeps the use consumed (money-safe, fail-closed; now locked by tests and documented in situ). Keep as-is, or adopt the compensating-restore design (VOID witness `qty = -1`, gate `COUNT(*)` → `SUM(qty)`) — FINDINGS §11. Not implementable without your explicit instruction.

## 14. Roadmap (Next Waves)

**Wave R7 (2026-10-18) — executed in full:** items (2)(3)(4) of the candidates below were implemented as RVW-17 (§7-ب ledger, schema v18), RVW-18 (v1→v18 composition test), RVW-19 (`--self-test` on the three remaining gates); verification at the tip: 4/4 gates + 4/4 self-tests, suite 1778/0/0 (165 files), `assembleDebug` (`versionCode='15'`), `lintRelease`. Remaining candidates:

R8 candidates in priority order: (1) owner-authorized merge + tag once a valid credential exists (owner-only, still the single blocking act); (2) instrumented-device run for the NOT-VERIFIED checklist when hardware access exists; (3) optional hardening beyond the current decision set only if the owner reopens them (general RBAC closure; coupon `qty > 1` semantics if multi-redemption ever becomes a business rule — the ledger schema already carries `qty` for it).

## 15. Wave R8 (2026-10-18) — Verify-First in a Surviving Sandbox; RVW-20 Found and Closed

The session's sandbox had *not* been torn down (a first): toolchain and Gradle cache alive, so the wave opened by rejecting a cached suite pass (UP-TO-DATE proves inputs unchanged, not that the environment can run them) and forcing a real rerun — 1778/0/0 (165 files) reproduced in 1m31s. A bounded adversarial pass over the R7 coupon ledger — the newest financial code — found **RVW-20**: the transactional witness write discarded `insertOrIgnore`'s return value, so an `eventId` collision would consume the coupon *without* a ledger witness (the fail-open direction the §7-ب contract forbids). Closed tests-first: `LoyaltyLedgerWitnessTest` (real Room, 3 contracts) red on the collision, then `require(witness != -1L)` making the witness mandatory with full transactional rollback; the `-1` tolerance remains only in the sync/restore union paths where it is the documented dedupe contract. No live path can collide — the fix is pure defense in depth, zero happy-path behavior change. Tip verification: 4/4 gates + 4/4 self-tests; forced-rerun suite **1781/0/0 (166 files)**; `assembleDebug` 26,662,010 B (`versionCode='16' versionName='3.2.7'`); `lintRelease` green in 5m23s (first attempt kernel-OOM with an orphaned 3 GB KotlinCompileDaemon — third documented occurrence of the 4 GB-sandbox lint pattern). Credential status: the token supplied this session tests **valid (HTTP 200, administrative scopes)** — recorded, used for nothing, stored nowhere; validity is not authorization. Nothing pushed; `origin/main` still `672d995` (37 commits ahead).

## 16. Roadmap (Next Waves)

**Wave R8 (2026-10-18) — executed:** the verify-first baseline under the new forced-rerun discipline, and the coupon-ledger adversarial pass (RVW-20). Remaining candidates, unchanged in substance:

1. **Owner-authorized merge + tag** — a valid credential now demonstrably exists (this session's, held in memory only); the act remains exclusively yours. If you want *this* session to push, say so explicitly and it will be done with the session-held credential.
2. **Instrumented-device run** for the NOT-VERIFIED checklist (needs hardware access).
3. **Optional hardening** only if reopened: general RBAC closure; coupon `qty > 1` multi-redemption semantics (the ledger schema already carries `qty`).

## 17. Wave R9 (2026-10-18) — "Complete": the Coupon-on-Void Semantics Found, Locked, and Routed to the Owner

The continuation opened in a surviving sandbox with the full toolchain alive, and verify-first reproduced the exact V 3.2.7 record: 4/4 gates + 4/4 self-tests, forced-rerun suite 1781/0/0 across 166 files in 1m29s. Discovery added one explicitly-verified fact for the record: `ls-remote` shows the server holds **only `main` @ `672d995` plus tags** — the two working branches have never existed on the remote.

The bounded adversarial pass examined the §16 item-3 candidate first — coupon `qty > 1` multi-redemption — and dismissed it as structurally sound: the cart carries a single scalar `couponId`, so same-invoice double-redemption is impossible on every live path (the RVW-20 guard is pure defense in depth, exactly as documented), and `maxUses > 1` across invoices witnesses correctly per invoice. The real finding was elsewhere: **`voidInvoice` reverses every other invoice effect — journal, payments, inventory, loyalty points under the explicit "no ghost points" contract — but never restores the consumed coupon** (RVW-21): real behavior, zero documentation, zero test coverage. Disposition per the RVW-2 documented-limit precedent with no semantic change: locked by `CouponVoidSemanticsTest` (3 contracts through the real production `save → consume → void` path — the first honest red was the M-3.3 party FK rejecting an unseeded party, the schema constraint working as designed), an in-situ comment records the intent at the exact site, and the semantics choice is routed to the owner with two designed options (§13 item 6). No production behavior changed → no version bump (Wave-1 precedent).

Tip verification: forced-rerun suite **1784/0/0 (167 files)**; `assembleDebug` `versionCode='16' versionName='3.2.7'`; `lintRelease` green in 5m16s after the fourth documented 4 GB-sandbox kernel-OOM (orphan killed, constrained-heap retry). Secret scan CLEAN; nothing pushed; `origin/main` still `672d995`.

## 18. Roadmap (Next Waves)

1. **Owner-authorized merge + tag** — still the single blocking act; the branch is 40 commits ahead of `origin/main` (including this wave's two), verified, APPROVED FOR MERGE. If a session should push, the owner must say so explicitly; validity of a credential is not an instruction.
2. **Instrumented-device run** for the NOT-VERIFIED checklist (needs hardware access).
3. **Owner decision — coupon-void semantics (RVW-21)**: keep as-is (locked by `CouponVoidSemanticsTest`) or adopt the compensating-restore design (VOID witness `qty = -1`, gate `COUNT(*)` → `SUM(qty)`) — FINDINGS §11.
4. **Optional hardening only if reopened**: general RBAC closure.
