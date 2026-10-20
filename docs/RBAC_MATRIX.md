# مصفوفة RBAC المنشورة — الحقيقة الواحدة للمنتج والهندسة
# Published RBAC Matrix — the single source of truth for product and engineering

> **[E0.4 V 3.3.0]** هذه الوثيقة تنشر المصفوفة كجدول قراءة موحّد فوق عقد التصميم
> `docs/RBAC_V13_DESIGN.md` §2–§3 (المصدر التأسيسي) واختبار المصفوفة المحركي
> `RbacMatrixTest` (العقد التنفيذي — 68 خلية بالاسم). أي تعديل هنا يجب أن يعدّل
> الثلاثة معاً في نفس الـPR، وإلا يكسر البناء.

---

## 1) مصفوفة المحرك — 4 أدوار × 17 عملية (Rbac.kt / RoleGate.allows)

| العملية (`Op`) | Owner | Manager | Accountant | Cashier |
|---|---|---|---|---|
| POS_SELL — نقطة البيع وإنشاء فاتورة بيع | ✓ | ✓ | ✗ | ✓ |
| INVOICE_SHARE — طباعة/QR/مشاركة فاتورة قائمة | ✓ | ✓ | ✓ | ✓ |
| INVOICE_CANCEL — إلغاء فاتورة | ✓ | ✓ | ✓ | ✗ |
| PARTY_PRODUCT_EDIT — تعديل طرف/منتج قائم | ✓ | ✓ | ✗ | ✗ |
| HARD_DELETE — حذف نهائي لمنتج/طرف | ✓ | ✗ | ✗ | ✗ |
| INVENTORY_READ — اطلاع المخزون والجرد | ✓ | ✓ | ✗ | ✓ (قراءة) |
| INVENTORY_MANAGE — تعديل المخزون/الجرد/حدود الطلب | ✓ | ✓ | ✗ | ✗ |
| FINANCIAL_REPORTS — التقارير المالية الكاملة + KPI | ✓ | ✓ | ✓ | ✗ |
| TAX_RETURN_VIEW — اطلاع الإقرار الضريبي | ✓ | ✗ | ✓ (قراءة) | ✗ |
| TAX_OWNER_DATA — بيانات الملك الضريبية وإعدادات ZATCA | ✓ | ✗ | ✗ | ✗ |
| EXPENSES_CHECKS_INSTALLMENTS — مصروفات/شيكات/أقساط | ✓ | ✓ | ✓ | ✗ |
| GENERAL_SETTINGS — الإعدادات العامة (ضريبة/عملة/طابعات/أتمتة) | ✓ | ✓ | ✗ | ✗ |
| USERS_MANAGE — إدارة المستخدمين والأدوار | ✓ | ✗ | ✗ | ✗ |
| BACKUP_RESTORE — النسخ الاحتياطي/الاستعادة/الاستيراد الجماعي | ✓ | ✗ | ✗ | ✗ |
| PRO_MANAGE — تفعيل Pro / شراء / استعادة | ✓ | ✗ | ✗ | ✗ |
| LOYALTY_APPLY — تطبيق الولاء والكوبونات على بيع | ✓ | ✓ | ✗ | ✓ |
| LOYALTY_MANAGE — إنشاء/تعديل قواعد الولاء والكوبونات | ✓ | ✓ | ✗ | ✗ |

قاعدة الحسم: **الافتراض مغلق** — أي عملية غير ممنوحة صراحةً محرَّمة، وأي
`Op` جديد يُبنى مقفولاً على غير المالك والمدير حتى يُفتح بقرار موثق يعدّل
المصفوفة واختبارها معاً. هوية بلا دور صالح (`Role.fromId`) تنهار إلى
Cashier (الأدنى صلاحية) — فشل مغلق عند أي قيمة غريبة في المخطط.

## 2) خريطة الحرس على طبقة البيانات — [E0.4 V 3.3.0]

بوابات الواجهة/VM كانت القاعدة منذ v13؛ هذه الموجة تكمل **توازي الدفاع عند
حد المستودع** لنقاط الكتابة (mutation) في بقية المستودعات — دفاع عميق خلف
بوابات الـVM لا بديل عنها. كل حرس أدناه **انعكاس لبوابة قائمة على المستدعي
أو لسطر صريح في مصفوفة §3** — لا سياسة جديدة ولا `Op` جديد:

| المستودع | النقطة المحروسة | `Op` الحكوم | مصدر السلطة |
|---|---|---|---|
| `InvoiceRepo` | `save` (فاتورة بيع type=0) | POS_SELL | §3 سطر 1 |
| `InvoiceRepo` | `save` (فاتورة شراء type=1) | INVENTORY_MANAGE | §3 سطر 6-ب (رفع المخزون) |
| `InvoiceRepo` | `voidInvoice` | INVOICE_CANCEL | §3 سطر 3 + بوابة InvoicesVM القائمة |
| `InventoryRepo` | `saveProduct` (قائم id≠0) | PARTY_PRODUCT_EDIT | §3 سطر 4 (الإنشاء الجديد id=0 بلا بوابة — عقد السطر 4 حرفياً) |
| `InventoryRepo` | `setArchived` | PARTY_PRODUCT_EDIT | §3 سطر 4 (تعديل صنف قائم) |
| `InventoryRepo` | `deleteProduct` | HARD_DELETE | §3 سطر 5 + بوابة HARD_DELETE القائمة |
| `InventoryRepo` | `moveStock` | INVENTORY_MANAGE | §3 سطر 6-ب (مستدعيات VM فقط — معاملة الفاتورة تحرك المخزون عبر DAO مباشرة فلا يُمس مسار بيع الكاشير) |
| `LedgerRepo` | `saveParty` (قائم id≠0) | PARTY_PRODUCT_EDIT | §3 سطر 4 |
| `LedgerRepo` | `deleteParty` | HARD_DELETE | §3 سطر 5 |
| `ExpenseRepo` | `add` / `addStamped` / `delete` | EXPENSES_CHECKS_INSTALLMENTS | §3 سطر 9 |
| `ChecksRepo` | `save` / `setStatus` / `delete` | EXPENSES_CHECKS_INSTALLMENTS | §3 سطر 9 |
| `InstallmentRepo` | `createPlan` / `pay` / `payEarlySettlement` / `reschedule` / `deletePlan` | EXPENSES_CHECKS_INSTALLMENTS | §3 سطر 9 |
| `LoyaltyRepo` | `saveCoupon` / `deleteCoupon` / `manualAdjust` | LOYALTY_MANAGE | §3 سطر 14-ب (مُعاملات earn/redeem/consume/reverse داخلية تحت سلطة save/voidInvoice أصلاً — لا بوابة مزدوجة داخل المعاملة) |
| `CsvImportRepo` | `import` | BACKUP_RESTORE | انعكاس بوابة `importCsv` الموثقة (إدخال جماعي متغيّر = باب الاستعادة نفسه) |
| `ZatcaEnableStore` | `setEnabled` / `setProduction` | TAX_OWNER_DATA | §3 سطر 8-ب («إعدادات ZATCA الرسمية — المالك وحده» حرفياً) |
| `SettingsRepo` | `setAddress` / `setTaxNumber` | TAX_OWNER_DATA | توازي بوابتي RVW-13 في SettingsVM |
| `SettingsRepo` | `setTaxRate` / `setBaseCurrency` / `setAutoBackupDays` / `setReportScheduleDays` / `setReportScheduleHour` / `setReportScheduleChannel` | GENERAL_SETTINGS | §3 سطر 10 (أختام العمال `setLast*` بلا بوابة عمداً — الكتابة الأتمتية المشروعة) |

القصد المسند والباقي بلا حرس (موثق لا مصمت):

- **مسارات القراءة** (INVENTORY_READ / FINANCIAL_REPORTS / TAX_RETURN_VIEW):
  الحكم يبقى على مستوى الشاشة/VM كما شُيّد منذ v13 — حراسة مسارات القراءة
  التفاعلية (Flows) على طبقة DAO/Repo تفتح باب كسر الويدجات والتقارير المجدولة
  ولا تضيف حماية حقيقية فوق بوابات الشاشات القائمة.
- **الذمم** (`LedgerRepo.addDebt`/`addPayment`): لا سطر في مصفوفة §3 يحكم
  تحصيل الذمم تحديداً — إسنادها لعملية قائمة (POS_SELL؟ FINANCIAL_REPORTS؟)
  قرار سياسة وليس توازي؛ يُرفع للمالك كبند قرار مستقبلي مع بقية البنود.
- **مسارات المزامنة** (`SyncEngine`): تكتب عبر DAO مباشرة كسلطة إقران
  (pairing) وليست سلطة مستخدم — المستند في ADR-002، والحرس عليها يُسقط
  المزامنة تحت جلسة أدنى دور.
- **`InstallmentRepo.recomputeStatus`**: بلا مستدعٍ خارجي (داخلية للصفوف) —
  لا حرس بلا مسار يحميه.

## 3) عقد الأتمتة (§4.3) — الهويات بلا جلسة

| الهوية الفعالة | متى | الحكم |
|---|---|---|
| `IMPLICIT_OWNER` | وضع المالك الأحادي (لا جلسة صريحة) | يمر على كل الحرس — القفل الفعلي PIN/بصمة الجهاز |
| `SYSTEM` (actor "system") | تعدد المستخدمين بلا جلسة حية (عمّال WorkManager) | دور OWNER — الكتابات الخلفية المشروعة تعمل أثناء القفل (عقد RVW-5) |
| جلسة صريحة | مستخدم مسجّل | تُحكم بالمصفوفة عند حد المستودع والـVM معاً |

العمال الخلفيون (Backup/Automation/Sync/ZatcaReport/Reminders) مستدعِ
قراءة أو منسوب لـSYSTEM — مُوثَّق بالفحص: لا مستدعٍ في `work/` يمر من نقطة
كتابة محروسة أعلاه.

## 4) سياسة ارتداد الرمز (PIN-fallback) — [E0.4 V 3.3.0]

عند غياب البصمة (لا عتاد / لا تسجيل / عطل مفتاح — `BiometricGate` يفشل
مغلقاً دائماً وفق M-9)، الرمز هو مسار الارتداد الوحيد والعقد:

1. **نفس الخزنة**: PBKDF2-HMAC-SHA256 (600k) + AES-256-GCM في Keystore —
   لا مسار رمز ثانٍ ولا مفتاح إضافي (`PinManager`/`PinVault` وحدتان).
2. **حدّ المحاولات التصاعدي**: `LockoutPolicy` (5 حرية ثم 30ث ← 900ث حد
   أعلى، تصفير كامل عند النجاح) — محكوم بـ`LockoutElapsedM9Test` و
   `UserAuthLockoutTest`، والقرار بالزمن الأحادي لا حائط النظام.
3. **دفتر التدقيق** (المضاف في هذه الموجة): كل فشل تحقق فعلي لرمز الجهاز
   يكتب `DEVICE_PIN_FAIL` في `audit_log` بإسناد ما قبل المصادقة الصادق
   (`actor="device"`, `actorId/actorRole` فارغان — الجلسة لم تولد بعد)؛
   النجاح لا يكتب (النجاح جلسة لا حدث — اتفاق `USER_LOGIN_FAIL` نفسه)،
   والرفض أثناء قفل سارٍ ليس محاولة تحقق فلا يكتب ولا يزيد العدّاد.
   الحكم: `PinFallbackAuditTest`.

## 5) خريطة الاختبارات — كل خلية موثقة بالاسم

| الاختبار | ما يقفله |
|---|---|
| `RbacMatrixTest` | محرك المصفوفة: 68 خلية (4×17) + قاعدة الافتراض المغلق |
| `RbacDataLayerMatrixTest` | خريطة §2 أعلاه خلية خلية: كل عملية محروسة × 4 أدوار (مسموح = تنفيذ فعلي، محرَم = `RoleDeniedException` قبل أي كتابة) + خليتا الضمني/SYSTEM (عقد الأتمتة) |
| `PinFallbackAuditTest` | سياسة §4: الفشل يُدوَّن، النجاح لا يُدوَّن، القفل يرفض بلا صف جديد ولا زيادة عدّاد |
| `UsersVMGateTest` / `ConfigRbacGateTest` / `BackupRbacGateTest` / `SessionStateModeTest` | بوابات VM/الجلسة القائمة (لم تُمس) |
