# RBAC Matrix — the single source of truth (V 3.3.0)

كل التغييرات على هذه المصفوفة تُعدَّل هنا ومعها `Rbac.kt` و`RbacMatrixTest` معاً —
أي باب جديد يبدأ مقفولاً على غير المالك والمدير حتى يُفتح بقرار موثق (عقد «الافتراض مغلق»).

This is the matrix published per the Phase-0 roadmap (E0.4): **4 roles × 17 operations = 68 cells**,
engine-locked byte-for-byte in `RbacMatrixTest`, and — as of V 3.3.0 — enforced at the **data layer**
for every operational money/inventory/config surface listed below, on top of the VM/UI gates that
pre-existed them. Background workers run under `SessionState.SYSTEM` (role OWNER) and single-owner
devices under `IMPLICIT_OWNER`, so automation never stalls behind these gates (RVW-5 contract).

Roles: `OWNER` (المالك) · `MANAGER` (المدير) · `ACCOUNTANT` (المحاسب) · `CASHIER` (الكاشير)

Legend — enforcement layer: **D** = data layer (`RoleGate.require` inside the repo, this wave) ·
**V** = VM/execution boundary (RVW-13 and friends) · **E** = pure engine only (reads/UI flows,
no data-layer mutation surface) · `—` = denied.

## The matrix (design §3, 14 rows → 17 ops)

| # | Operation | OWNER | MANAGER | ACCOUNTANT | CASHIER | Enforcement surfaces (layer D unless marked) |
|---|---|---|---|---|---|---|
| 1 | `POS_SELL` | ✓ | ✓ | — | ✓ | **D** `InvoiceRepo.save` (sale invoices); **V** POS flow |
| 2 | `INVOICE_SHARE` | ✓ | ✓ | ✓ | ✓ | **E** print/QR/share — no data mutation |
| 3 | `INVOICE_CANCEL` | ✓ | ✓ | ✓ | — | **D** `InvoiceRepo.voidInvoice` + **V** FeatureVMs |
| 4 | `PARTY_PRODUCT_EDIT` | ✓ | ✓ | — | — | **D** `LedgerRepo.saveParty`/`InventoryRepo.saveProduct` (edit path `id != 0` only — documented contract: *new-record creation is ungated*, exact matrix wording) + `LedgerRepo.mergeParties` |
| 5 | `HARD_DELETE` | ✓ | — | — | — | **D** `LedgerRepo.deleteParty` / `InventoryRepo.deleteProduct` + **V** FeatureVMs ×2 |
| 6 | `INVENTORY_READ` | ✓ | ✓ | — | ✓ | **E** read-only flows (screen visibility) |
| 7 | `INVENTORY_MANAGE` | ✓ | ✓ | — | — | **D** `InventoryRepo.moveStock` / `setArchived`; purchase invoices gated here via `InvoiceRepo.save` (a purchase adds stock — accountant has no inventory access by row 6) |
| 8a | `TAX_RETURN_VIEW` | ✓ | — | ✓ | — | **E** tax-return read |
| 8b | `TAX_OWNER_DATA` | ✓ | — | — | — | **V** FeatureVMs ( setAddress/setTaxNumber — RVW-13) |
| 9 | `EXPENSES_CHECKS_INSTALLMENTS` | ✓ | ✓ | ✓ | — | **D** `ExpenseRepo.addStamped`/`delete`; `ChecksRepo.save`/`delete`/`setStatus`; `InstallmentRepo.createPlan`/`pay`/`payEarlySettlement`/`reschedule`/`deletePlan` |
| 10 | `GENERAL_SETTINGS` | ✓ | ✓ | — | — | **V** FeatureVMs setters + settings JSON import (RVW-13); data-layer write path is `SettingsRepo` DataStore APIs — enforcement stays at the documented execution boundary |
| 11 | `USERS_MANAGE` | ✓ | — | — | — | **V** UsersVM all mutations (refresh/add/setActive/delete/resetPin/setBiometricAllowed) |
| 12 | `BACKUP_RESTORE` | ✓ | — | — | — | **D** `BackupRestoreRepo` ×5 entry points + `BackupRepo` (RVW-11) + `WebhookStore.setEnabled/setUrl/setSecret` (RVW-13) + **V** FeatureVMs ×5 |
| 13 | `PRO_MANAGE` | ✓ | — | — | — | **V** ProVM |
| 14a | `LOYALTY_APPLY` | ✓ | ✓ | — | ✓ | **E** applying loyalty/coupons happens inside `InvoiceRepo.save` → guarded by row 1; no standalone rule mutation |
| 14b | `LOYALTY_MANAGE` | ✓ | ✓ | — | — | **D** `LoyaltyRepo.saveCoupon`/`deleteCoupon` + **V** FeatureVMs |

## The four rows that had NO enforcement anywhere before V 3.3.0

`POS_SELL`, `PARTY_PRODUCT_EDIT`, `INVENTORY_MANAGE`, `EXPENSES_CHECKS_INSTALLMENTS` — discovered
by the E0.4 survey (enforcement map: engine ✓ / VM partial / data layer 3 files only) and closed
at the data layer in this wave, matrix-driven, **one named assertion per role × operation cell**
(`DataLayerRbacMatrixTest`, real Room in-memory):

- `POS_SELL`: O✓ M✓ C✓ A✗ (sale save)
- purchase invoice → `INVENTORY_MANAGE`: O✓ M✓ A✗ C✗
- `INVOICE_CANCEL` at data layer: O✓ M✓ A✓ C✗ (denied cancel leaves the invoice untouched — asserted)
- `PARTY_PRODUCT_EDIT`: edit O✓ M✓ A✗ C✗; **creation ungated for all roles (documented contract)**
- `HARD_DELETE`: O✓ M✗ A✗ C✗ (denied delete leaves the row — asserted)
- `INVENTORY_MANAGE` moves/archive: O✓ M✓ A✗ C✗
- `EXPENSES_CHECKS_INSTALLMENTS`: O✓ M✓ A✓ C✗ across expenses/checks/installments surfaces
- `LOYALTY_MANAGE`: O✓ M✓ A✗ C✗ (rule save/delete; denied delete leaves the coupon — asserted)
- `SYSTEM` identity (multi-user, no session) passes **every** surface — background workers unaffected (RVW-5)

## PIN-fallback / biometric lockout parity (E0.4, same wave)

The documented decision (RVW-11, FINDINGS §2/§5): biometric enabling requires a configured PIN
(`LockPolicy.canEnableBiometric`); the legacy one-tap emergency path stays for pre-gate devices.
V 3.3.0 adds the missing half — **an active `LockoutPolicy` lockout blocks every unlock path**,
biometric included (`UsersVM.openBiometricSession` now refuses with an attributed
`USER_LOGIN_FAIL` audit row carrying `lockout` in its details, and never clears the target's
counter). Locked via `BiometricLockoutParityTest` (real Room + real DataStore guards).

## What is deliberately NOT here

- `SettingsRepo` data-layer gates for GENERAL_SETTINGS/TAX_OWNER_DATA: RVW-13 chose the execution
  boundary (FeatureVMs) deliberately — the repo exposes 67 functions whose callers are the gated VMs;
  adding 60+ redundant gates would be surface without risk reduction. Recorded as a conscious
  documented trade-off, revisitable.
- Reads: rows 2/6/8a are read-only surfaces with no data-layer mutation to guard; enforcement is
  screen/report visibility (E).
- `recomputeStatus`: zero production callers (derivation helper) — ungated by absence of a path.
