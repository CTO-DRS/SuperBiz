# سياسة الخصوصية — SuperBiz · Privacy Policy

> **آخر تحديث / Last updated:** 2026-10-03 · **الإصدار المعني / Applicable version:** 1.0.0+

---

## العربية

### الخلاصة في سطر واحد
SuperBiz يعمل **دون اتصال بالإنترنت 100٪** — بياناتك المالية تعيش على جهازك فقط، ولا نرى شيئاً منها، ولا نجمع أي شيء عنك تقريباً.

### ١. ما نجمعه (ولا نجمع)
- **لا نجمع** بيانات شخصية ولا مالية ولا استخدام على خوادمنا — لا يوجد خادم لنا أصلاً في المسار التشغيلي للتطبيق.
- **بيانات عملك** (الفواتير، المخزون، الأطراف، القيود): تُخزَّن محلياً في قاعدة بيانات على جهازك ولا تخرج منه إلا حين تُصدّرها أنت بنفسك (PDF/Excel/نسخة احتياطية) أو ترسلها أنت عبر بريدك أو واتساب.
- **النسخ الاحتياطية**: تُكتب في المجلد الذي تختاره أنت (SAF) على جهازك أو حصة سحابية تختارها أنت — نحن لا نطّلع عليها.
- **كلمة مرور القفل/البصمة**: تُحفظ بصمة الرمز مغلّفة بمفاتيح أندرويد Keystore على جهازك، ولا يمكن لنا — ولا لأي طرف — استعادتها.

### ٢. الاتصالات الوحيدة التي يُجريها التطبيق
| الاتصال | الغرض | ما يُرسَل |
|---|---|---|
| البريد المجدول (SMTP الخاص بك) | إرسال كشوف الحساب | بيانات الكشف التي تختارها + بيانات دخول بريدك |
| طباعة البلوتوث الحرارية | إيصالات ومستندات | محتوى المستند للطابعة مباشرة |
| Google Play Billing (اختياري — Pro) | شراء/استعادة ميزات Pro | تُديره Google وفق سياستها؛ لا يتلقى التطبيق بياناتك الشخصية، فقط حالة الشراء |
| **منصة فاتورة (ZATCA) — اختيارية حصراً** [V 1.5.0] | الإبلاغ عن الفواتير المبسطة وتخليص القياسية عند هيئة الزكاة والضريبة والجمارك — **لا يُرسل socket واحد قبل تفعيلك الصريح للربط من شاشة ZATCA داخل التطبيق** | حصراً: مستند الفاتورة بصيغة UBL 2.1 + بصمته الرقمية (SHA-256) + معرّفها الموحّد وعدّادها + بيانات اعتماد CSID نحو نطاق `gw-fatura.zatca.gov.sa` — **لا شيء آخر إطلاقاً**: لا تحليلات، لا تتبع، لا سجلات أعطال، لا «فحص تحديث» |

### ٣. الصلاحيات
الكاميرا (ماسح الباركود)· جهات الاتصال (استيراد اختياري بأمر أنت)· الإشعارات (تنبيهات الذمم/المخزون)· البلوتوث (الطابعات) — كلها لأغراض وظيفية ظاهرة داخل التطبيق ولا تُستخدم لأي تتبّع.

### ٤. الأطفال والاحتفاظ والحذف
التطبيق ليس موجهاً للأطفال. بياناتك تبقى على جهازك حتى تحذفها أنت (حذف التطبيق يحذف قاعدة البيانات؛ وهناك «فحص صحة البيانات» وتصدير كامل قبل الحذف).

### ٥. التعديلات
أي تغيير جوهري يُنشر في وصف المتجر وفي هذه الصفحة مع تحديث التاريخ أعلاه.

**تواصل:** عبر صفحة المستودع (GitHub Issues) أو بريد الدعم المعلن في متجر Google Play.

---

## English

### One-line summary
SuperBiz works **100% offline** — your business data lives only on your device; we see none of it and collect virtually nothing.

### 1. What we collect (and don't)
- **We collect no** personal, financial, or usage data on any server of ours — there is no operational server at all.
- **Your business data** (invoices, inventory, parties, ledger) is stored locally in a database on your device and never leaves it unless *you* export it (PDF/Excel/backup) or send it via your own email/WhatsApp.
- **Backups** are written to the folder *you* choose (SAF) on your device or your chosen cloud share — we never see them.
- **Lock PIN/biometrics**: the PIN digest is wrapped by Android Keystore keys on your device; neither we nor anyone else can recover it.

### 2. The only connections the app makes
| Connection | Purpose | What is sent |
|---|---|---|
| Scheduled email (your SMTP) | sending account statements | chosen statement data + your mail credentials |
| Bluetooth thermal printing | receipts & documents | document content directly to the printer |
| Google Play Billing (optional — Pro) | purchase/restore of Pro features | managed by Google under its policy; the app receives only purchase state, never your personal data |
| **Fatoora platform (ZATCA) — strictly opt-in** [V 1.5.0] | reporting simplified and clearing standard invoices with the Zakat, Tax and Customs Authority — **not a single socket before you explicitly enable tax linking in the in-app ZATCA screen** | exclusively: the invoice UBL 2.1 document + its digital hash (SHA-256) + its UUID/counter + CSID credentials toward `gw-fatura.zatca.gov.sa` — **nothing else, ever**: no analytics, no tracking, no crash logs, no update checks |

### 3. Permissions
Camera (barcode scanner) · Contacts (optional import at your command) · Notifications (debts/stock alerts) · Bluetooth (printers) — all used for visible in-app functionality only, never for tracking.

### 4. Children, retention & deletion
Not directed at children. Data stays on your device until *you* delete it (uninstalling removes the database; a full export and a data-health check are available beforehand).

### 5. Changes
Material changes will be posted in the store listing and on this page with an updated date above.

**Contact:** via the GitHub repository issues page, or the support email listed on Google Play.
