package com.superbiz.app.domain.backup

import com.superbiz.app.data.db.CheckEntity
import com.superbiz.app.data.db.Currency
import com.superbiz.app.data.db.Expense
import com.superbiz.app.data.db.Invoice
import com.superbiz.app.data.db.InvoiceItem
import com.superbiz.app.data.db.Installment
import com.superbiz.app.data.db.InstallmentPlan
import com.superbiz.app.data.db.JournalEntry
import com.superbiz.app.data.db.JournalLine
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.db.Payment
import com.superbiz.app.data.db.Product
import com.superbiz.app.data.db.Rule
import com.superbiz.app.data.db.StockMove
import com.superbiz.app.data.db.Visit
// [P34-M1] نطاق v4 — كيانات جداول منظومة الكشوف الثمانية (23/23)
import com.superbiz.app.data.db.AuditLogEntity
import com.superbiz.app.data.db.CouponEntity
import com.superbiz.app.data.db.LoyaltyEntryEntity
import com.superbiz.app.data.db.NoteTemplateEntity
import com.superbiz.app.data.db.SignatureEntity
import com.superbiz.app.data.db.StatementDeliveryEntity
import com.superbiz.app.data.db.StatementEntity
import com.superbiz.app.data.db.StatementRuleEntity
import com.superbiz.app.data.db.StatementTemplateEntity
import com.superbiz.app.data.db.StampEntity
import com.superbiz.app.util.Money // [P33-P8] نقطة تحويل الريال→القروش الوحيدة (legacy backups)

/**
 * [P13-b] BackupKit — محرك النسخ الاحتياطي (Kotlin نقي، بلا أي استيراد Android) — [P14-b] نطاق الاستعادة 
 *
 * • buildBackupJson: يصدّر كل الجداول (15 جدولاً) إلى نص JSON موسوم بالإصدار
 * {"format":"superbiz-backup","version":1,"exportedAt":<epochMs>,
 * "counts":{عدد صفوف كل جدول},"tables":{اسم الجدول:[صف كائن لكل حقل]}}
 * كل حقل من حقول الكيان يُكتب باسمه الأصلي (id/name/createdAt/…) — لا إعادة تسمية
 * تُقلّل الأخطاء. Long/Int/Boolean أرقام JSON أصلية، String عبر escapeJson،
 * وDouble مع NaN/Infinity يُكتب null (الرقم غير الشرعي كان سيكسر الملف كله).
 * • parseBackup: يتحقق format=="superbiz-backup" وversion==1 — إصدار أحدث يعيد خطأ
 * «نسخة أحدث غير مدعومة» صريحاً. الفشل كلي أو كلي: أي صف مشوّه يفشل التحليل برسالة
 * تسمّي الجدول ورقم الصف (v1 بلا استعادة جزئية — أبسط وأصدق للمستخدم).
 * • planImport: خطة دمج نقية للاستعادة — نفس id يُتخطى، id غير موجودة تُستورد،
 * الصفوف بمعرف سالب/صفر تُعدّ تالفة (failed) — [P14-b] عبر أربعة جداول ثم [P15-b]
 * عبر سبعة جداول (الأطراف/المنتجات/الزيارات/المصروفات/الشيكات/الخطط/الأقساط).
 *
 * ── قرار أمان واعٍ (نطاق الاستعادة — v1 ثم v2) ──
 * : الاستعادة تُدمج الأطراف والمنتجات فقط.
 * [P14-b] توسّع النطاق إلى الزيارات والمصروفات أيضاً — هذان «جدولا أرشيف معزولان»
 * بلا مفاتيح أجنبية (انظر Visit/Expense في Entities.kt) ولا قيد مزدوج ولا دور في حساب
 * الأرصدة، فدمج صفوف جديدة بهما آمن تماماً ولا يمسّ أي حساب. الفواتير/البنود/المدفوعات/
 * الشيكات/القيود ما زالت تُصدَّر في الملف لكن لا تُستعاد: إعادة إدخالها بمعرفاتها الأصلية
 * فوق قاعدة حية قد تكسر اتساق القيد المزدوج (أرصدة الأطراف، COGS، روابط المفاتيح
 * الأجنبية) — والاستعادة الكاملة المسح-ثم-الإرجاع موجودة أصلاً في المسار القديم (BackupRepo).
 *
 * ── [P15-b] الشيكات وخطط الأقساط والأقساط تنضم إلى نطاق الاستعادة ──
 * نتيجة تدقيق Entities.kt الفعلي (تصحيح لافتراض الموجة — انظر worklog 15-b)
 * • checks وinstallment_plans كلاهما يحمل ForeignKey باتجاه parties بـ onDelete=RESTRICT
 * (ليسا «جدولي أرشيف معزلين» كما فُرض)، وinstallments يحمل ForeignKey باتجاه
 * installment_plans بـ onDelete=CASCADE. تبعات ذلك الثلاثة
 * ١. ترتيب التنفيذ إلزامي: parties أولاً (كما في v1/v2) ثم plans ثم installments ثم
 * checks — الأب يسبق الابن وإلا انكسر الإدراج على القيد.
 * ٢. قاعدة اليتيم: صف يشير إلى أبٍ غير موجود (لا في القاعدة ولا ضمن المُدخل في المعاملة
 * نفسها) يُتخطى skipped — يتيم بنيوي ليس صفاً تالفاً (لا failed)، وإلا انكسر إدراجه
 * فتتراجع المعاملة كلها ويفقد المستخدم الاستعادة برمّتها بسبب صف واحد. الأقساط اليتيمة
 * (planId غائب أو ≤0) تجرّ معها أقساط الخطط اليتيمة بدورها (تسلسل اليتامي متسق).
 * ٣. الأمان المحاسبي قائم: الشيكات والخطط صفوف أرشيف لا تُقيّد ولا تعيد حساب أرصدة
 * (payments.checkId وpayments.planId مرجعا SET_NULL باتجاه واحد لا يقيّد الإدراج)،
 * فإضافتها لا تمسّ أي قيد مزدوج قائم — والفواتير/القيود ما زالت مستثناة كما في v1/v2.
 *
 * ── [P14-b] رقم الإصدار يبقى 1 ──
 * صيغة الملف لم تتغير إطلاقاً (نفس الحقول ونفس الجداول الخمسة عشر) — توسّع نطاق
 * الاستيراد فقط، وملفات المصدَّرة سابقاً تحمل الزيارات والمصروفات أصلاً فتُستورد
 * بلا أي تحويل. رفع الرقم كان سيرفض ملفات قابلة للعمل بلا داعٍ.
 * [P15-b] يبقى 1 في v3 للسبب نفسه: الجداول الثلاثة الجديدة تُكتب في الملف منذ،
 * فملف / القديم يُستعاد منه الشيكات والخطط فوراً بلا أي تحويل أو رفع إصدار.
 *
 * ── [P33-P8] الترحيل المالي: ريال Double ← → قروش Long ──
 * كل حقول المبالغ في الكيانات صارت Long قروش (1 ريال = 100 قرشاً — انظر util/Money.kt)
 * • التصدير: المبالغ تُكتب أعداداً صحيحة (lng لا dbl) — «12.5 ريال» يخرج 1250.
 * • علامة الصيغة: الملفات الجديدة تحمل "p8":true أعلى الجذر وقيم مبالغها قروش تُقرأ كما هي.
 * غياب العلامة = نسخة قديمة (≤ ) مخزّنة ريالاً عشرياً ("amount":12.5) — وعندها
 * يمر كل حقل مبلغ عبر Money.toPiasters (HALF_UP) عند القراءة في moneyF أدناه —
 * نقطة تحويل واحدة لا غير، بلا كشف إرشادي مغامر (طالع/كسر) يخمّن العالَمين.
 * • BACKUP_VERSION يبقى 1 عمداً: رفع بوابته كان سيرفض كل ملفات v1.. الشرعية،
 * فالتمييز بين العالَمين بعلامة "p8" حصراً (انظر parseBackup وBACKUP_P8_FLAG).
 * • الكميات (qty/stockQty/reorderLevel) والنسب (taxRate/fxRate/rateToBase) تبقى Double
 * خاماً بلا أي تحويل — قاعدة 6 في p8-api.
*/

/** [P13-b] معرّف الصيغة — نص ثابت يميّز نسخ SuperBiz عن أي JSON آخر (بما فيه الصيغة القديمة) */
const val BACKUP_FORMAT = "superbiz-backup"

/**
 * [P36-BK] عدد جداول النسخة — العقد المرجعي للرقاقة المعروضة في مركز الإعدادات
 * («تغطية النسخ: 25/25»). يُختبر بالانعكاس في BackupContractP36Test ضد عدد حقول
 * [BackupData] نفسها فلا ينحرف الرقم المعلن عن الواقع المصدَّر أبداً.
 */
const val BACKUP_TABLE_COUNT = 25

/** [P13-b] إصدار الصيغة الحالي — أي إصدار أعلى يُرفض برسالة واضحة لا بمحاولة تحليل أعمى
 * [P14-b] يبقى 1 في v2 و[P15-b] يبقى 1 في الصيغة لم تتغير، فقط توسّع نطاق
 * الاستيراد (انظر رأس الملف)
 * [P33-P8] يبقى 1 في موجة القروش أيضاً — بوابة الإصدار يجب ألا تُغلق على الملفات
 * القديمة الشرعية (≤ )، فالتمييز ريال/قروش بعلامة "p8" لا برقم الإصدار */
const val BACKUP_VERSION = 1

/** [P33-P8] علامة عالم القروش — ملفات ما بعد الترحيل تُصدَّر بـ"p8":true وقيم مبالغها
 *  قروش Long تُقرأ كما هي. غياب العلامة (أي قيمة/نوع آخر) = نسخة قديمة مخزّنة ريالاً
 *  عشرياً، فيُحوَّل كل حقل مبلغ عبر Money.toPiasters عند القراءة (انظر moneyF) */
const val BACKUP_P8_FLAG = "p8"

/** [P13-b] خطأ عملية نسخ/استعادة برسالة عربية جاهزة للعرض (يرفعه parseBackup وطبقة التنفيذ) */
class BackupFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** [P13-b] لقطات كل الجداول في لحظة التصدير — القوائم افتراضياً فارغة لسهولة البناء والاختبار */
data class BackupData(
    val version: Int,
    val exportedAt: Long,
    val parties: List<Party> = emptyList(),
    val products: List<Product> = emptyList(),
    val invoices: List<Invoice> = emptyList(),
    val invoiceItems: List<InvoiceItem> = emptyList(),
    val payments: List<Payment> = emptyList(),
    val visits: List<Visit> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val checks: List<CheckEntity> = emptyList(),
    val plans: List<InstallmentPlan> = emptyList(),
    val installments: List<Installment> = emptyList(),
    val currencies: List<Currency> = emptyList(),
    val rules: List<Rule> = emptyList(),
    val journal: List<JournalEntry> = emptyList(),
    val journalLines: List<JournalLine> = emptyList(),
    val stockMoves: List<StockMove> = emptyList(),
    // [P34-M1] نطاق v4 — جداول منظومة الكشوف الثمانية (23/23): كلها قوائم جديدة افتراضياً
    // فتنجو ملفات v1.. القديمة وبناة BackupData الموضعية في الاختبارات بلا تعديل
    val statementTemplates: List<StatementTemplateEntity> = emptyList(),
    val signatures: List<SignatureEntity> = emptyList(),
    val stamps: List<StampEntity> = emptyList(),
    val noteTemplates: List<NoteTemplateEntity> = emptyList(),
    val statements: List<StatementEntity> = emptyList(),
    val statementDeliveries: List<StatementDeliveryEntity> = emptyList(),
    val statementRules: List<StatementRuleEntity> = emptyList(),
    val auditLog: List<AuditLogEntity> = emptyList(),
    // [P46-W1] نطاق v5 — جدولا الولاء والكوبونات (25/25): قائمتان جديدتان افتراضيتان
    // فتنجو ملفات النسخ السابقة وبناة BackupData الموضعية في الاختبارات بلا تعديل
    val loyaltyEntries: List<LoyaltyEntryEntity> = emptyList(),
    val coupons: List<CouponEntity> = emptyList()
)

/** [P13-b] نتيجة التحليل: إما بيانات كاملة أو رسالة خطأ عربية — لا حالات وسطى */
data class BackupParseResult(val data: BackupData?, val error: String?)

/** [P13-b] إحصاء خطة الدمج — تغذي رسالة النجاح في واجهة الإعدادات
 * [P14-b] حقول الزيارات والمصروفات الأربعة الجديدة بقيم افتراضية صفرية كي تبقى نداءات
 * الموضعية (6 وسائط) ومقارنات الاختبارات القديمة تعمل بلا تعديل — للتوافق الخلفي
 * [P15-b] ستة حقول أخرى لنطاق v3 (الشيكات/الخطط/الأقساط) بنفس الأسلوب الصفري —
 * البناء الموضعي بستة وسائط كما في يبقى صالحاً حرفياً */
data class ImportStats(
    val importedParties: Int,
    val skippedParties: Int,
    val importedProducts: Int,
    val skippedProducts: Int,
    val failed: Int,
    /** كل الجداول الـ23 بحجمها داخل الملف (بما فيها الصفرية) — لعرض «الجداول المكتشفة» */
    val tablesFound: Map<String, Int>,
    // [P14-b] نطاق v2 — إحصاء جدولي الأرشيف المعزول (زيارات/مصروفات)
    val importedVisits: Int = 0,
    val skippedVisits: Int = 0,
    val importedExpenses: Int = 0,
    val skippedExpenses: Int = 0,
    // [P15-b] نطاق v3 — إحصاء الشيكات وخطط الأقساط والأقساط
    val importedChecks: Int = 0,
    val skippedChecks: Int = 0,
    val importedPlans: Int = 0,
    val skippedPlans: Int = 0,
    val importedInstallments: Int = 0,
    val skippedInstallments: Int = 0,
    // [P34-M1] نطاق v4 — إحصاء جداول الكشوف الثمانية (القيم الافتراضية صفرية للتوافق الخلفي)
    val importedStatementTemplates: Int = 0,
    val skippedStatementTemplates: Int = 0,
    val importedSignatures: Int = 0,
    val skippedSignatures: Int = 0,
    val importedStamps: Int = 0,
    val skippedStamps: Int = 0,
    val importedNoteTemplates: Int = 0,
    val skippedNoteTemplates: Int = 0,
    val importedStatements: Int = 0,
    val skippedStatements: Int = 0,
    val importedStatementDeliveries: Int = 0,
    val skippedStatementDeliveries: Int = 0,
    val importedStatementRules: Int = 0,
    val skippedStatementRules: Int = 0,
    val importedAuditLog: Int = 0,
    val skippedAuditLog: Int = 0,
    // [P46-W1] نطاق v5 — إحصاء جدولا الولاء والكوبونات (القيم الافتراضية صفرية للتوافق الخلفي)
    val importedLoyaltyEntries: Int = 0,
    val skippedLoyaltyEntries: Int = 0,
    val importedCoupons: Int = 0,
    val skippedCoupons: Int = 0
)

/** [P13-b] أسماء جداول الملف بترتيب ثابت — تستخدم في counts/tables/tablesFound */
object BackupTables {
    const val PARTIES = "parties"
    const val PRODUCTS = "products"
    const val INVOICES = "invoices"
    const val INVOICE_ITEMS = "invoice_items"
    const val PAYMENTS = "payments"
    const val VISITS = "visits"
    const val EXPENSES = "expenses"
    const val CHECKS = "checks"
    const val PLANS = "installment_plans"
    const val INSTALLMENTS = "installments"
    const val CURRENCIES = "currencies"
    const val RULES = "rules"
    const val JOURNAL = "journal"
    const val JOURNAL_LINES = "journal_lines"
    const val STOCK_MOVES = "stock_moves"
    // [P34-M1] نطاق v4 — جداول منظومة الكشوف الثمانية (اكتمال 23/23)
    const val STATEMENT_TEMPLATES = "statement_templates"
    const val SIGNATURES = "signatures"
    const val STAMPS = "stamps"
    const val NOTE_TEMPLATES = "note_templates"
    const val STATEMENTS = "statements"
    const val STATEMENT_DELIVERIES = "statement_deliveries"
    const val STATEMENT_RULES = "statement_rules"
    const val AUDIT_LOG = "audit_log"

    const val LOYALTY_ENTRIES = "loyalty_entries"
    const val COUPONS = "coupons"

    val ALL: List<String> = listOf(
        PARTIES, PRODUCTS, INVOICES, INVOICE_ITEMS, PAYMENTS, VISITS, EXPENSES,
        CHECKS, PLANS, INSTALLMENTS, CURRENCIES, RULES, JOURNAL, JOURNAL_LINES, STOCK_MOVES,
        STATEMENT_TEMPLATES, SIGNATURES, STAMPS, NOTE_TEMPLATES,
        STATEMENTS, STATEMENT_DELIVERIES, STATEMENT_RULES, AUDIT_LOG,
        LOYALTY_ENTRIES, COUPONS
    )
}

/** [P13-b] مجموع صفوف كل الجداول — يغذي «تم تصدير N سجلاً» — [P34-M1] 23 جدولاً */
fun totalRows(d: BackupData): Int =
    d.parties.size + d.products.size + d.invoices.size + d.invoiceItems.size +
        d.payments.size + d.visits.size + d.expenses.size + d.checks.size +
        d.plans.size + d.installments.size + d.currencies.size + d.rules.size +
        d.journal.size + d.journalLines.size + d.stockMoves.size +
        d.statementTemplates.size + d.signatures.size + d.stamps.size + d.noteTemplates.size +
        d.statements.size + d.statementDeliveries.size + d.statementRules.size + d.auditLog.size +
        d.loyaltyEntries.size + d.coupons.size

// ─────────────────────────── الكتابة ───────────────────────────

/** [P13-b] بناء نص النسخة كاملاً — StringBuilder واحد، كل جدول يُسلسَل في موضعه */
fun buildBackupJson(d: BackupData): String {
    val sb = StringBuilder(64 * 1024)
    sb.append("{\"format\":\"").append(BACKUP_FORMAT).append('"')
    sb.append(",\"version\":").append(BACKUP_VERSION)
    // [P33-P8] علامة عالم القروش — غيابها في ملفات ما قبل الترحيل هو معرّف النسخة الريالية
    sb.append(",\"").append(BACKUP_P8_FLAG).append("\":true")
    sb.append(",\"exportedAt\":").append(d.exportedAt)
    // counts: عدد صفوف كل جدول — يتيح فحص النسخة بلا تحليل الجداول
    sb.append(",\"counts\":{")
    var first = true
    fun count(name: String, n: Int) {
        if (!first) sb.append(',')
        first = false
        sb.append('"').append(name).append("\":").append(n)
    }
    count(BackupTables.PARTIES, d.parties.size)
    count(BackupTables.PRODUCTS, d.products.size)
    count(BackupTables.INVOICES, d.invoices.size)
    count(BackupTables.INVOICE_ITEMS, d.invoiceItems.size)
    count(BackupTables.PAYMENTS, d.payments.size)
    count(BackupTables.VISITS, d.visits.size)
    count(BackupTables.EXPENSES, d.expenses.size)
    count(BackupTables.CHECKS, d.checks.size)
    count(BackupTables.PLANS, d.plans.size)
    count(BackupTables.INSTALLMENTS, d.installments.size)
    count(BackupTables.CURRENCIES, d.currencies.size)
    count(BackupTables.RULES, d.rules.size)
    count(BackupTables.JOURNAL, d.journal.size)
    count(BackupTables.JOURNAL_LINES, d.journalLines.size)
    count(BackupTables.STOCK_MOVES, d.stockMoves.size)
    count(BackupTables.STATEMENT_TEMPLATES, d.statementTemplates.size)
    count(BackupTables.SIGNATURES, d.signatures.size)
    count(BackupTables.STAMPS, d.stamps.size)
    count(BackupTables.NOTE_TEMPLATES, d.noteTemplates.size)
    count(BackupTables.STATEMENTS, d.statements.size)
    count(BackupTables.STATEMENT_DELIVERIES, d.statementDeliveries.size)
    count(BackupTables.STATEMENT_RULES, d.statementRules.size)
    count(BackupTables.AUDIT_LOG, d.auditLog.size)
    count(BackupTables.LOYALTY_ENTRIES, d.loyaltyEntries.size)
    count(BackupTables.COUPONS, d.coupons.size)
    sb.append('}')
    // tables: صفوف كل جدول كائنات بحقول الكيان الأصلية
    sb.append(",\"tables\":{")
    first = true
    fun <T> table(name: String, rows: List<T>, item: (T) -> String) {
        if (!first) sb.append(',')
        first = false
        sb.append('"').append(name).append("\":[")
        rows.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            sb.append(item(r))
        }
        sb.append(']')
    }
    table(BackupTables.PARTIES, d.parties) { partyToJson(it) }
    table(BackupTables.PRODUCTS, d.products) { productToJson(it) }
    table(BackupTables.INVOICES, d.invoices) { invoiceToJson(it) }
    table(BackupTables.INVOICE_ITEMS, d.invoiceItems) { invoiceItemToJson(it) }
    table(BackupTables.PAYMENTS, d.payments) { paymentToJson(it) }
    table(BackupTables.VISITS, d.visits) { visitToJson(it) }
    table(BackupTables.EXPENSES, d.expenses) { expenseToJson(it) }
    table(BackupTables.CHECKS, d.checks) { checkToJson(it) }
    table(BackupTables.PLANS, d.plans) { planToJson(it) }
    table(BackupTables.INSTALLMENTS, d.installments) { installmentToJson(it) }
    table(BackupTables.CURRENCIES, d.currencies) { currencyToJson(it) }
    table(BackupTables.RULES, d.rules) { ruleToJson(it) }
    table(BackupTables.JOURNAL, d.journal) { journalEntryToJson(it) }
    table(BackupTables.JOURNAL_LINES, d.journalLines) { journalLineToJson(it) }
    table(BackupTables.STOCK_MOVES, d.stockMoves) { stockMoveToJson(it) }
    table(BackupTables.STATEMENT_TEMPLATES, d.statementTemplates) { statementTemplateToJson(it) }
    table(BackupTables.SIGNATURES, d.signatures) { signatureToJson(it) }
    table(BackupTables.STAMPS, d.stamps) { stampToJson(it) }
    table(BackupTables.NOTE_TEMPLATES, d.noteTemplates) { noteTemplateToJson(it) }
    table(BackupTables.STATEMENTS, d.statements) { statementToJson(it) }
    table(BackupTables.STATEMENT_DELIVERIES, d.statementDeliveries) { statementDeliveryToJson(it) }
    table(BackupTables.STATEMENT_RULES, d.statementRules) { statementRuleToJson(it) }
    table(BackupTables.AUDIT_LOG, d.auditLog) { auditLogToJson(it) }
    table(BackupTables.LOYALTY_ENTRIES, d.loyaltyEntries) { loyaltyEntryToJson(it) }
    table(BackupTables.COUPONS, d.coupons) { couponToJson(it) }
    sb.append("}}")
    return sb.toString()
}

// أدوات كتابة الحقول — أسماء الحقول ثوابت كود فلا تحتاج تهريباً
private fun StringBuilder.str(name: String, v: String) =
    append('"').append(name).append("\":\"").append(escapeJson(v)).append('"')

private fun StringBuilder.lng(name: String, v: Long) =
    append('"').append(name).append("\":").append(v)

private fun StringBuilder.int(name: String, v: Int) =
    append('"').append(name).append("\":").append(v)

private fun StringBuilder.bool(name: String, v: Boolean) =
    append('"').append(name).append("\":").append(v)

/** [P13-b] NaN/Infinity ليست أرقاماً JSON شرعية — تُكتب null ولا تفسد الملف كله */
private fun doubleJson(v: Double): String =
    if (v.isNaN() || v.isInfinite()) "null" else v.toString()

private fun StringBuilder.dbl(name: String, v: Double) =
    append('"').append(name).append("\":").append(doubleJson(v))

private fun StringBuilder.dblN(name: String, v: Double?) {
    if (v == null) nul(name) else dbl(name, v)
}

private fun StringBuilder.lngN(name: String, v: Long?) {
    if (v == null) nul(name) else append('"').append(name).append("\":").append(v)
}

/** [P34-M1] عدد صحيح قابل للإفراغ — weekday/dayOfMonth في قواعد الكشف */
private fun StringBuilder.intN(name: String, v: Int?) {
    if (v == null) nul(name) else append('"').append(name).append("\":").append(v)
}

private fun StringBuilder.strN(name: String, v: String?) {
    if (v == null) nul(name) else str(name, v)
}

private fun StringBuilder.nul(name: String) = append('"').append(name).append("\":null")

// مُسلسِلات الكيانات — قائمة الحقول من Entities.kt واحدة واحدة (بما فيها الافتراضيات)

private fun partyToJson(p: Party): String {
    val sb = StringBuilder(192)
    sb.append('{')
    sb.lng("id", p.id); sb.append(',')
    sb.str("name", p.name); sb.append(',')
    sb.str("phone", p.phone); sb.append(',')
    sb.int("type", p.type); sb.append(',')
    sb.str("note", p.note); sb.append(',')
    sb.lng("createdAt", p.createdAt); sb.append(',')
    sb.bool("archived", p.archived); sb.append(',')
    sb.bool("favorite", p.favorite); sb.append(',')
    sb.dblN("lat", p.lat); sb.append(',')
    sb.dblN("lng", p.lng)
    sb.append('}')
    return sb.toString()
}

private fun productToJson(p: Product): String {
    val sb = StringBuilder(224)
    sb.append('{')
    sb.lng("id", p.id); sb.append(',')
    sb.str("name", p.name); sb.append(',')
    sb.str("sku", p.sku); sb.append(',')
    sb.str("barcode", p.barcode); sb.append(',')
    sb.str("unit", p.unit); sb.append(',')
    sb.lng("costPrice", p.costPrice); sb.append(',') // [P33-P8] قروش Long لا ريال Double
    sb.lng("salePrice", p.salePrice); sb.append(',') // [P33-P8] قروش Long لا ريال Double
    sb.dbl("stockQty", p.stockQty); sb.append(',')
    sb.dbl("reorderLevel", p.reorderLevel); sb.append(',')
    sb.str("category", p.category); sb.append(',')
    sb.lng("createdAt", p.createdAt); sb.append(',')
    sb.bool("archived", p.archived)
    sb.append('}')
    return sb.toString()
}

private fun invoiceToJson(i: Invoice): String {
    val sb = StringBuilder(288)
    sb.append('{')
    sb.lng("id", i.id); sb.append(',')
    sb.str("number", i.number); sb.append(',')
    sb.lng("partyId", i.partyId); sb.append(',')
    sb.int("type", i.type); sb.append(',')
    sb.lng("date", i.date); sb.append(',')
    sb.lng("dueDate", i.dueDate); sb.append(',')
    sb.lng("subtotal", i.subtotal); sb.append(',') // [P33-P8] قروش
    sb.lng("discount", i.discount); sb.append(',') // [P33-P8] قروش
    sb.dbl("taxRate", i.taxRate); sb.append(',') // نسبة تبقى Double خام
    sb.lng("taxAmount", i.taxAmount); sb.append(',') // [P33-P8] قروش
    sb.lng("total", i.total); sb.append(',') // [P33-P8] قروش
    sb.lng("paid", i.paid); sb.append(',') // [P33-P8] قروش
    sb.lng("costTotal", i.costTotal); sb.append(',') // [P33-P8] قروش
    sb.int("status", i.status); sb.append(',')
    sb.str("currency", i.currency); sb.append(',')
    sb.dbl("fxRate", i.fxRate); sb.append(',')
    sb.str("note", i.note)
    sb.append('}')
    return sb.toString()
}

private fun invoiceItemToJson(t: InvoiceItem): String {
    val sb = StringBuilder(176)
    sb.append('{')
    sb.lng("id", t.id); sb.append(',')
    sb.lng("invoiceId", t.invoiceId); sb.append(',')
    sb.lngN("productId", t.productId); sb.append(',')
    sb.str("desc", t.desc); sb.append(',')
    sb.dbl("qty", t.qty); sb.append(',')
    sb.lng("unitPrice", t.unitPrice); sb.append(',') // [P33-P8] قروش
    sb.lng("discount", t.discount); sb.append(',') // [P33-P8] قروش
    sb.int("taxKind", t.taxKind); sb.append(',') // [P41-L1] فئة السطر v11
    sb.dbl("taxRate", t.taxRate) // [P41-L1] نسبة السطر المعلنة (-1 وراثة)
    sb.append('}')
    return sb.toString()
}

private fun paymentToJson(p: Payment): String {
    val sb = StringBuilder(192)
    sb.append('{')
    sb.lng("id", p.id); sb.append(',')
    sb.lngN("partyId", p.partyId); sb.append(',')
    sb.lngN("invoiceId", p.invoiceId); sb.append(',')
    sb.lngN("checkId", p.checkId); sb.append(',')
    sb.lng("amount", p.amount); sb.append(',') // [P33-P8] قروش
    sb.lng("date", p.date); sb.append(',')
    sb.int("direction", p.direction); sb.append(',')
    sb.str("method", p.method); sb.append(',')
    sb.str("note", p.note); sb.append(',')
    sb.lngN("planId", p.planId)
    sb.append('}')
    return sb.toString()
}

private fun visitToJson(v: Visit): String {
    val sb = StringBuilder(144)
    sb.append('{')
    sb.lng("id", v.id); sb.append(',')
    sb.lng("partyId", v.partyId); sb.append(',')
    sb.lng("visitedAt", v.visitedAt); sb.append(',')
    sb.dblN("lat", v.lat); sb.append(',')
    sb.dblN("lng", v.lng); sb.append(',')
    sb.str("note", v.note)
    sb.append('}')
    return sb.toString()
}

private fun expenseToJson(e: Expense): String {
    val sb = StringBuilder(128)
    sb.append('{')
    sb.lng("id", e.id); sb.append(',')
    sb.lng("amount", e.amount); sb.append(',') // [P33-P8] قروش
    sb.str("category", e.category); sb.append(',')
    sb.str("note", e.note); sb.append(',')
    sb.lng("date", e.date); sb.append(',')
    sb.lng("createdAt", e.createdAt)
    sb.append('}')
    return sb.toString()
}

// [P46-W1] جولة 7 — تسلسل جدولا الولاء والكوبونات (25/25) بنمط العقد الحرفي:
// كل الحقول دائماً + المبالغ قروش Long كما هي (ملف p8) + الحقل الاختياري invoiceId
// يُكتب دائماً كرقم (0 بدل null عند القراءة فتتطابق دلالة «بلا فاتورة»)
private fun loyaltyEntryToJson(e: LoyaltyEntryEntity): String {
    val sb = StringBuilder(128)
    sb.append('{')
    sb.lng("id", e.id); sb.append(',')
    sb.lng("partyId", e.partyId); sb.append(',')
    sb.lng("invoiceId", e.invoiceId ?: 0L); sb.append(',')
    sb.lng("delta", e.delta); sb.append(',')
    sb.str("reason", e.reason); sb.append(',')
    sb.str("note", e.note); sb.append(',')
    sb.lng("createdAt", e.createdAt)
    sb.append('}')
    return sb.toString()
}

private fun couponToJson(c: CouponEntity): String {
    val sb = StringBuilder(160)
    sb.append('{')
    sb.lng("id", c.id); sb.append(',')
    sb.str("code", c.code); sb.append(',')
    sb.append("\"kind\":").append(c.kind); sb.append(',')
    sb.lng("amountPiasters", c.amountPiasters); sb.append(',')
    sb.append("\"percent\":").append(c.percent); sb.append(',')
    sb.lng("expiresAt", c.expiresAt); sb.append(',')
    sb.append("\"maxUses\":").append(c.maxUses); sb.append(',')
    sb.append("\"usedCount\":").append(c.usedCount); sb.append(',')
    sb.append("\"active\":").append(if (c.active) "true" else "false"); sb.append(',')
    sb.str("note", c.note); sb.append(',')
    sb.lng("createdAt", c.createdAt)
    sb.append('}')
    return sb.toString()
}

private fun checkToJson(c: CheckEntity): String {
    val sb = StringBuilder(192)
    sb.append('{')
    sb.lng("id", c.id); sb.append(',')
    sb.str("number", c.number); sb.append(',')
    sb.lng("partyId", c.partyId); sb.append(',')
    sb.str("bank", c.bank); sb.append(',')
    sb.lng("amount", c.amount); sb.append(',') // [P33-P8] قروش
    sb.lng("issueDate", c.issueDate); sb.append(',')
    sb.lng("dueDate", c.dueDate); sb.append(',')
    sb.int("direction", c.direction); sb.append(',')
    sb.int("status", c.status); sb.append(',')
    sb.str("note", c.note)
    sb.append('}')
    return sb.toString()
}

private fun planToJson(p: InstallmentPlan): String {
    val sb = StringBuilder(224)
    sb.append('{')
    sb.lng("id", p.id); sb.append(',')
    sb.str("title", p.title); sb.append(',')
    sb.lng("partyId", p.partyId); sb.append(',')
    sb.int("direction", p.direction); sb.append(',')
    sb.lng("total", p.total); sb.append(',') // [P33-P8] قروش
    sb.lng("downPayment", p.downPayment); sb.append(',') // [P33-P8] قروش
    sb.lng("financed", p.financed); sb.append(',') // [P33-P8] قروش
    sb.int("months", p.months); sb.append(',')
    sb.lng("startDate", p.startDate); sb.append(',')
    sb.str("currency", p.currency); sb.append(',')
    sb.str("note", p.note); sb.append(',')
    sb.lng("createdAt", p.createdAt); sb.append(',')
    sb.bool("archived", p.archived)
    sb.append('}')
    return sb.toString()
}

private fun installmentToJson(i: Installment): String {
    val sb = StringBuilder(144)
    sb.append('{')
    sb.lng("id", i.id); sb.append(',')
    sb.lng("planId", i.planId); sb.append(',')
    sb.int("seq", i.seq); sb.append(',')
    sb.lng("amount", i.amount); sb.append(',') // [P33-P8] قروش
    sb.lng("dueDate", i.dueDate); sb.append(',')
    sb.lng("paidAmount", i.paidAmount); sb.append(',') // [P33-P8] قروش
    sb.lngN("paidDate", i.paidDate); sb.append(',')
    sb.int("status", i.status)
    sb.append('}')
    return sb.toString()
}

private fun currencyToJson(c: Currency): String {
    val sb = StringBuilder(144)
    sb.append('{')
    sb.str("code", c.code); sb.append(',')
    sb.str("nameAr", c.nameAr); sb.append(',')
    sb.str("nameEn", c.nameEn); sb.append(',')
    sb.str("symbol", c.symbol); sb.append(',')
    sb.dbl("rateToBase", c.rateToBase); sb.append(',')
    sb.bool("isBase", c.isBase)
    sb.append('}')
    return sb.toString()
}

private fun ruleToJson(r: Rule): String {
    val sb = StringBuilder(96)
    sb.append('{')
    sb.lng("id", r.id); sb.append(',')
    sb.str("kind", r.kind); sb.append(',')
    sb.bool("enabled", r.enabled); sb.append(',')
    sb.int("daysBefore", r.daysBefore); sb.append(',')
    sb.lng("lastRun", r.lastRun)
    sb.append('}')
    return sb.toString()
}

private fun journalEntryToJson(e: JournalEntry): String {
    val sb = StringBuilder(112)
    sb.append('{')
    sb.lng("id", e.id); sb.append(',')
    sb.lng("date", e.date); sb.append(',')
    sb.str("memo", e.memo); sb.append(',')
    sb.strN("refType", e.refType); sb.append(',')
    sb.lngN("refId", e.refId)
    sb.append('}')
    return sb.toString()
}

private fun journalLineToJson(l: JournalLine): String {
    val sb = StringBuilder(144)
    sb.append('{')
    sb.lng("id", l.id); sb.append(',')
    sb.lng("entryId", l.entryId); sb.append(',')
    sb.str("account", l.account); sb.append(',')
    sb.lng("debit", l.debit); sb.append(',') // [P33-P8] قروش
    sb.lng("credit", l.credit); sb.append(',') // [P33-P8] قروش
    sb.lngN("partyId", l.partyId); sb.append(',')
    sb.str("currency", l.currency); sb.append(',')
    sb.dbl("fxRate", l.fxRate)
    sb.append('}')
    return sb.toString()
}

private fun stockMoveToJson(s: StockMove): String {
    val sb = StringBuilder(144)
    sb.append('{')
    sb.lng("id", s.id); sb.append(',')
    sb.lng("productId", s.productId); sb.append(',')
    sb.dbl("qty", s.qty); sb.append(',')
    sb.str("reason", s.reason); sb.append(',')
    sb.lng("date", s.date); sb.append(',')
    sb.strN("refType", s.refType); sb.append(',')
    sb.lngN("refId", s.refId); sb.append(',')
    sb.str("note", s.note)
    sb.append('}')
    return sb.toString()
}

// ── [P34-M1] كتّاب جداول منظومة الكشوف الثمانية (23/23) — العقد نفسه: كل الحقول دائماً ──
// ملاحظة مالية: StatementRuleEntity.threshold قروش منذ P33-P8 — تُكتب lng (قروش صحيحة
// كما كل ملف p8) وتُقرأ عبر moneyF حصراً (كسر عشري في ملف قروش = صف تالف صريح).

private fun statementTemplateToJson(t: StatementTemplateEntity): String {
    val sb = StringBuilder(192)
    sb.append('{')
    sb.lng("id", t.id); sb.append(',')
    sb.str("name", t.name); sb.append(',')
    sb.str("baseTemplateId", t.baseTemplateId); sb.append(',')
    sb.str("configJson", t.configJson); sb.append(',')
    sb.bool("isDefault", t.isDefault); sb.append(',')
    sb.bool("favorite", t.favorite); sb.append(',')
    sb.lng("createdAt", t.createdAt); sb.append(',')
    sb.lng("updatedAt", t.updatedAt)
    sb.append('}')
    return sb.toString()
}

private fun signatureToJson(s: SignatureEntity): String {
    val sb = StringBuilder(128)
    sb.append('{')
    sb.lng("id", s.id); sb.append(',')
    sb.str("name", s.name); sb.append(',')
    sb.strN("jobTitle", s.jobTitle); sb.append(',')
    sb.str("imagePath", s.imagePath); sb.append(',')
    sb.bool("isDefault", s.isDefault); sb.append(',')
    sb.bool("active", s.active); sb.append(',')
    sb.lng("createdAt", s.createdAt)
    sb.append('}')
    return sb.toString()
}

private fun stampToJson(s: StampEntity): String {
    val sb = StringBuilder(112)
    sb.append('{')
    sb.lng("id", s.id); sb.append(',')
    sb.str("name", s.name); sb.append(',')
    sb.str("imagePath", s.imagePath); sb.append(',')
    sb.bool("isDefault", s.isDefault); sb.append(',')
    sb.bool("active", s.active); sb.append(',')
    sb.lng("createdAt", s.createdAt)
    sb.append('}')
    return sb.toString()
}

private fun noteTemplateToJson(t: NoteTemplateEntity): String {
    val sb = StringBuilder(128)
    sb.append('{')
    sb.lng("id", t.id); sb.append(',')
    sb.str("title", t.title); sb.append(',')
    sb.str("body", t.body); sb.append(',')
    sb.bool("isDefault", t.isDefault)
    sb.append('}')
    return sb.toString()
}

private fun statementToJson(s: StatementEntity): String {
    val sb = StringBuilder(288)
    sb.append('{')
    sb.lng("id", s.id); sb.append(',')
    sb.str("statementNumber", s.statementNumber); sb.append(',')
    sb.str("verificationId", s.verificationId); sb.append(',')
    sb.lng("partyId", s.partyId); sb.append(',')
    sb.lng("fromTs", s.fromTs); sb.append(',')
    sb.lng("toTs", s.toTs); sb.append(',')
    sb.str("templateId", s.templateId); sb.append(',')
    sb.str("currency", s.currency); sb.append(',')
    sb.str("contentHash", s.contentHash); sb.append(',')
    sb.str("filePath", s.filePath); sb.append(',')
    sb.strN("note", s.note); sb.append(',')
    sb.lng("createdAt", s.createdAt); sb.append(',')
    sb.str("lang", s.lang)
    sb.append('}')
    return sb.toString()
}

private fun statementDeliveryToJson(d: StatementDeliveryEntity): String {
    val sb = StringBuilder(224)
    sb.append('{')
    sb.lng("id", d.id); sb.append(',')
    sb.lng("statementId", d.statementId); sb.append(',')
    sb.str("channel", d.channel); sb.append(',')
    sb.str("status", d.status); sb.append(',')
    sb.int("attempts", d.attempts); sb.append(',')
    sb.strN("lastError", d.lastError); sb.append(',')
    sb.lngN("sentAt", d.sentAt); sb.append(',')
    sb.lngN("scheduledFor", d.scheduledFor); sb.append(',')
    sb.str("dedupKey", d.dedupKey); sb.append(',')
    sb.lngN("lastAttemptAt", d.lastAttemptAt)
    sb.append('}')
    return sb.toString()
}

private fun statementRuleToJson(r: StatementRuleEntity): String {
    val sb = StringBuilder(320)
    sb.append('{')
    sb.lng("id", r.id); sb.append(',')
    sb.str("name", r.name); sb.append(',')
    sb.bool("enabled", r.enabled); sb.append(',')
    sb.str("partyMode", r.partyMode); sb.append(',')
    sb.str("partyIdsJson", r.partyIdsJson); sb.append(',')
    sb.str("frequency", r.frequency); sb.append(',')
    sb.intN("weekday", r.weekday); sb.append(',')
    sb.intN("dayOfMonth", r.dayOfMonth); sb.append(',')
    sb.int("hour", r.hour); sb.append(',')
    sb.int("minute", r.minute); sb.append(',')
    sb.str("periodPreset", r.periodPreset); sb.append(',')
    sb.strN("templateId", r.templateId); sb.append(',')
    sb.lngN("signatureId", r.signatureId); sb.append(',')
    sb.lngN("stampId", r.stampId); sb.append(',')
    sb.str("channel", r.channel); sb.append(',')
    sb.strN("eventFlagsJson", r.eventFlagsJson); sb.append(',')
    sb.lngN("threshold", r.threshold); sb.append(',')
    sb.lngN("lastRunAt", r.lastRunAt); sb.append(',')
    sb.lngN("nextRunAt", r.nextRunAt)
    sb.append('}')
    return sb.toString()
}

private fun auditLogToJson(a: AuditLogEntity): String {
    val sb = StringBuilder(160)
    sb.append('{')
    sb.lng("id", a.id); sb.append(',')
    sb.str("actor", a.actor); sb.append(',')
    sb.str("action", a.action); sb.append(',')
    sb.str("details", a.details); sb.append(',')
    sb.lng("ts", a.ts)
    sb.append('}')
    return sb.toString()
}

// ─────────────────────────── القراءة ───────────────────────────

private const val ERR_UNSUPPORTED = "صيغة الملف غير مدعومة — هذا ليس ملف نسخة SuperBiz صالحاً"
private const val ERR_NEWER = "نسخة أحدث غير مدعومة — حدّث التطبيق أولاً"
private const val ERR_MALFORMED = "ملف تالف"

/** [P13-b] خطأ حقل داخل صف — يُلتقط داخلياً ويُغلَّف باسم الجدول ورقم الصف */
private class BackupFieldException(message: String) : Exception(message)

/**
 * [P13-b] تحليل نص نسخة إلى BackupData — الفشل كلي أو كلي:
 * • نص غير JSON أصلاً / جذر غير كائن / format غير مطابق → ERR_UNSUPPORTED.
 * • version أكبر من المدعوم → ERR_NEWER («نسخة أحدث غير مدعومة»).
 * • صف مشوّه → رسالة تسمّي الجدول ورقم الصف والحقل (لا انهيار أبداً — لا رميات خارجية).
 * • جدول غائب → قائمة فارغة (تسامح مع نسخ أحدث حذفت جدولاً)، وجدول مجهول في tables يُتجاهل.
 * • BOM يقاد الملف أحياناً بعد تحرير خارجي — يُزال قبل التحليل.
 * • [P33-P8] كشف ريال/قروش: غياب علامة "p8" ⇒ نسخة قديمة بديناريّات ريال — كل حقل مبلغ
 *   يُحوَّل عبر Money.toPiasters داخل moneyF (العلامة موجودة ⇒ قروش تُقرأ كما هي).
 */
fun parseBackup(text: String): BackupParseResult {
    return try {
        val root = parseJson(text.removePrefix("\uFEFF")) // BOM قد يُضاف بمحرر خارجي — يزال قبل التحليل
        if (root !is Map<*, *> || root["format"] != BACKUP_FORMAT) {
            return BackupParseResult(null, ERR_UNSUPPORTED)
        }
        val version = when (val v = root["version"]) {
            is Long -> v
            is Double -> v.toLong()
            else -> return BackupParseResult(null, ERR_UNSUPPORTED)
        }
        if (version > BACKUP_VERSION) return BackupParseResult(null, ERR_NEWER)
        if (version < BACKUP_VERSION) return BackupParseResult(null, ERR_UNSUPPORTED)
        // ── [P33-P8] كشف النسخة القديمة/الجديدة — نقطة الكشف الوحيدة في الملف كله ──
        // العلامة "p8":true (قيمة منطقية حرفية كما يكتبها buildBackupJson) ⇒ المبالغ
        // قروش صحيحة تُقرأ كما هي. أي حالة أخرى (العلامة غائبة في ملفات ≤، أو نوع
        // خاطئ في ملف محرَّر يدوياً) ⇒ نسخة قديمة بديناريّات ريال — كل حقل مبلغ يُقرأ
        // عبر moneyF أدناه فيُحوَّل عبر Money.toPiasters حصراً. كشف حتمي بالعلامة
        // (لا إرشاد طالع/كسر يخمّن) كي لا يُفسَّر ملف قروش كمبالغ ريال ولا العكس.
        val legacy = root[BACKUP_P8_FLAG] != true
        val exportedAt = when (val e = root["exportedAt"]) {
            is Long -> e
            else -> 0L
        }
        val tables: Map<*, *> = when (val t = root["tables"]) {
            null -> emptyMap<Any?, Any?>()
            is Map<*, *> -> t
            else -> throw BackupFormatException("قسم \"tables\" تالف: يجب أن يكون كائناً")
        }
        BackupParseResult(
            BackupData(
                version = version.toInt(),
                exportedAt = exportedAt,
                parties = decodeTable(tables, BackupTables.PARTIES, legacy) { m, _ -> partyFromJson(m) },
                products = decodeTable(tables, BackupTables.PRODUCTS, legacy) { m, l -> productFromJson(m, l) },
                invoices = decodeTable(tables, BackupTables.INVOICES, legacy) { m, l -> invoiceFromJson(m, l) },
                invoiceItems = decodeTable(tables, BackupTables.INVOICE_ITEMS, legacy) { m, l -> invoiceItemFromJson(m, l) },
                payments = decodeTable(tables, BackupTables.PAYMENTS, legacy) { m, l -> paymentFromJson(m, l) },
                visits = decodeTable(tables, BackupTables.VISITS, legacy) { m, _ -> visitFromJson(m) },
                expenses = decodeTable(tables, BackupTables.EXPENSES, legacy) { m, l -> expenseFromJson(m, l) },
                checks = decodeTable(tables, BackupTables.CHECKS, legacy) { m, l -> checkFromJson(m, l) },
                plans = decodeTable(tables, BackupTables.PLANS, legacy) { m, l -> planFromJson(m, l) },
                installments = decodeTable(tables, BackupTables.INSTALLMENTS, legacy) { m, l -> installmentFromJson(m, l) },
                currencies = decodeTable(tables, BackupTables.CURRENCIES, legacy) { m, _ -> currencyFromJson(m) },
                rules = decodeTable(tables, BackupTables.RULES, legacy) { m, _ -> ruleFromJson(m) },
                journal = decodeTable(tables, BackupTables.JOURNAL, legacy) { m, _ -> journalEntryFromJson(m) },
                journalLines = decodeTable(tables, BackupTables.JOURNAL_LINES, legacy) { m, l -> journalLineFromJson(m, l) },
                stockMoves = decodeTable(tables, BackupTables.STOCK_MOVES, legacy) { m, _ -> stockMoveFromJson(m) },
                // [P34-M1] نطاق v4 — جداول الكشوف الثمانية (غياب الجدول ⇒ قائمة فارغة — تسامح العقد)
                statementTemplates = decodeTable(tables, BackupTables.STATEMENT_TEMPLATES, legacy) { m, _ -> statementTemplateFromJson(m) },
                signatures = decodeTable(tables, BackupTables.SIGNATURES, legacy) { m, _ -> signatureFromJson(m) },
                stamps = decodeTable(tables, BackupTables.STAMPS, legacy) { m, _ -> stampFromJson(m) },
                noteTemplates = decodeTable(tables, BackupTables.NOTE_TEMPLATES, legacy) { m, _ -> noteTemplateFromJson(m) },
                statements = decodeTable(tables, BackupTables.STATEMENTS, legacy) { m, _ -> statementFromJson(m) },
                statementDeliveries = decodeTable(tables, BackupTables.STATEMENT_DELIVERIES, legacy) { m, _ -> statementDeliveryFromJson(m) },
                statementRules = decodeTable(tables, BackupTables.STATEMENT_RULES, legacy) { m, l -> statementRuleFromJson(m, l) },
                auditLog = decodeTable(tables, BackupTables.AUDIT_LOG, legacy) { m, _ -> auditLogFromJson(m) },
                // [P46-W1] نطاق v5 — جدولا الولاء والكوبونات (متسامحان: جدول غائب = قائمة فارغة)
                loyaltyEntries = decodeTable(tables, BackupTables.LOYALTY_ENTRIES, legacy) { m, _ -> loyaltyEntryFromJson(m) },
                coupons = decodeTable(tables, BackupTables.COUPONS, legacy) { m, _ -> couponFromJson(m) }
            ),
            null
        )
    } catch (e: MiniJsonException) {
        BackupParseResult(null, "$ERR_MALFORMED: ${e.message}")
    } catch (e: BackupFormatException) {
        BackupParseResult(null, e.message ?: ERR_MALFORMED)
    }
}

private fun <T> decodeTable(
    tables: Map<*, *>,
    name: String,
    legacy: Boolean, // [P33-P8] يمرَّر لقارئات الصفوف ذات المبالغ — انظر moneyF
    conv: (Map<*, *>, Boolean) -> T
): List<T> {
    val raw = tables[name] ?: return emptyList()
    if (raw !is List<*>) {
        throw BackupFormatException("الجدول \"$name\" تالف: يجب أن يكون مصفوفة")
    }
    return raw.mapIndexed { i, row ->
        try {
            conv(row as? Map<*, *> ?: throw BackupFieldException("الصف يجب أن يكون كائناً"), legacy)
        } catch (e: BackupFieldException) {
            throw BackupFormatException("الجدول \"$name\" صف $i: ${e.message}")
        }
    }
}

// قارئات الحقول: غياب المفتاح → قيمة افتراضية من الكيان، ونوع خاطئ → خطأ صريح
// (الكاتب يكتب كل الحقول دائماً، فالغياب يعني ملفاً محرّراً يدوياً أو مقطوعاً)

private fun badField(key: String, expected: String): Nothing =
    throw BackupFieldException("الحقل \"$key\" يجب أن يكون $expected")

private fun Map<*, *>.lngF(key: String, def: Long): Long = when (val v = this[key]) {
    null -> def
    is Long -> v
    else -> badField(key, "عدداً صحيحاً")
}

private fun Map<*, *>.lngN(key: String): Long? = when (val v = this[key]) {
    null -> null
    is Long -> v
    else -> badField(key, "عدداً صحيحاً أو null")
}

private fun Map<*, *>.intF(key: String, def: Int): Int = when (val v = this[key]) {
    null -> def
    is Long -> v.toInt()
    else -> badField(key, "عدداً صحيحاً")
}

private fun Map<*, *>.dblF(key: String, def: Double): Double = when (val v = this[key]) {
    null -> def
    is Double -> v
    is Long -> v.toDouble()
    else -> badField(key, "رقماً")
}

/** null يعني قيمة مفقودة أصلاً (كتابة NaN) — يستقبلها المستدعي كـ null حقيقية */
private fun Map<*, *>.dblN(key: String): Double? = when (val v = this[key]) {
    null -> null
    is Double -> v
    is Long -> v.toDouble()
    else -> badField(key, "رقماً أو null")
}

/**
 * [P33-P8] قارئ حقل مبلغ — النقطة الوحيدة لواجهة ريال/قروش في قراءة النسخ الاحتياطية
 * • ملف جديد (علامة "p8" حاضرة ⇒ legacy=false): المبالغ مكتوبة قروش صحيحة —
 * Long يُقرأ كما هو، وDouble يُقبل فقط إن كان صحيح الشكل بلا كسر (تسامح مع تحرير
 * يدوي) — أي كسر عشري في ملف قروش = صف تالف صريح، لا قَطّ صامت يخسر قروشاً.
 * • ملف قديم (legacy=true، ≤ ): المبالغ مخزّنة ريالاً عشرياً ("amount":12.5) —
 * Long وDouble معاً يمرّان عبر Money.toPiasters (HALF_UP على التمثيل العشري
 * المكتوب) حصراً. النسخ القديمة كتبت المبالغ dbl دائماً فجاءت عشرية ("30.0")،
 * وصحيحٌ بلا علامة هنا يعني ملفاً محرَّراً يدوياً — يُعامَل ريالاً كلياً بلا
 * كشف إرشادي مغامر (الكشف حتمي بعلامة "p8" في parseBackup وحدها).
 * غياب المفتاح → 0 قروش (المقابل الصحيح لافتراض 0.0 ريال في القارئ القديم،
 * وكتابة NaN القديمة كانت null أصلاً).
 * الكميات والنسب (qty/stockQty/reorderLevel/taxRate/fxRate/rateToBase) لا تمر هنا
 * أبداً — تبقى dblF/dblN خاماً بلا تحويل (قاعدة 6 في p8-api).
*/
private fun Map<*, *>.moneyF(key: String, legacy: Boolean): Long = when (val v = this[key]) {
    null -> 0L
    is Long -> if (legacy) Money.toPiasters(v.toDouble()) else v
    is Double ->
        if (legacy) Money.toPiasters(v)
        else if (v.isFinite() && v == Math.floor(v)) v.toLong()
        else badField(key, "عدداً صحيحاً من القروش (نسخة p8 لا تقبل كسراً عشرياً)")
    else -> badField(key, "رقماً")
}

private fun Map<*, *>.strF(key: String, def: String): String = when (val v = this[key]) {
    null -> def
    is String -> v
    else -> badField(key, "نصاً")
}

private fun Map<*, *>.boolF(key: String, def: Boolean): Boolean = when (val v = this[key]) {
    null -> def
    is Boolean -> v
    else -> badField(key, "قيمة منطقية")
}

private fun Map<*, *>.strNF(key: String): String? = when (val v = this[key]) {
    null -> null
    is String -> v
    else -> badField(key, "نصاً أو null")
}

private fun partyFromJson(m: Map<*, *>) = Party(
    id = m.lngF("id", 0L),
    name = m.strF("name", ""),
    phone = m.strF("phone", ""),
    type = m.intF("type", 0),
    note = m.strF("note", ""),
    createdAt = m.lngF("createdAt", 0L),
    archived = m.boolF("archived", false),
    favorite = m.boolF("favorite", false),
    lat = m.dblN("lat"),
    lng = m.dblN("lng")
)

private fun productFromJson(m: Map<*, *>, legacy: Boolean) = Product(
    id = m.lngF("id", 0L),
    name = m.strF("name", ""),
    sku = m.strF("sku", ""),
    barcode = m.strF("barcode", ""),
    unit = m.strF("unit", "قطعة"),
    costPrice = m.moneyF("costPrice", legacy), // [P33-P8] ريال قديم→قروش / قروش جديد كما هي
    salePrice = m.moneyF("salePrice", legacy), // [P33-P8]
    stockQty = m.dblF("stockQty", 0.0),
    reorderLevel = m.dblF("reorderLevel", 0.0),
    category = m.strF("category", ""),
    createdAt = m.lngF("createdAt", 0L),
    archived = m.boolF("archived", false)
)

private fun invoiceFromJson(m: Map<*, *>, legacy: Boolean) = Invoice(
    id = m.lngF("id", 0L),
    number = m.strF("number", ""),
    partyId = m.lngF("partyId", 0L),
    type = m.intF("type", 0),
    date = m.lngF("date", 0L),
    dueDate = m.lngF("dueDate", 0L),
    subtotal = m.moneyF("subtotal", legacy), // [P33-P8]
    discount = m.moneyF("discount", legacy), // [P33-P8]
    taxRate = m.dblF("taxRate", 0.0),
    taxAmount = m.moneyF("taxAmount", legacy), // [P33-P8]
    total = m.moneyF("total", legacy), // [P33-P8]
    paid = m.moneyF("paid", legacy), // [P33-P8]
    costTotal = m.moneyF("costTotal", legacy), // [P33-P8]
    status = m.intF("status", 0),
    currency = m.strF("currency", "SAR"),
    fxRate = m.dblF("fxRate", 1.0),
    note = m.strF("note", "")
)

private fun invoiceItemFromJson(m: Map<*, *>, legacy: Boolean) = InvoiceItem(
    id = m.lngF("id", 0L),
    invoiceId = m.lngF("invoiceId", 0L),
    productId = m.lngN("productId"),
    desc = m.strF("desc", ""),
    qty = m.dblF("qty", 0.0),
    unitPrice = m.moneyF("unitPrice", legacy), // [P33-P8]
    discount = m.moneyF("discount", legacy), // [P33-P8]
    // [P41-L1] ملفات ما قبل v11 بلا الحقلين ⇒ البذرتان المحايدتان (دلالة تاريخية حرفية)
    taxKind = m.intF("taxKind", 0),
    taxRate = m.dblF("taxRate", -1.0)
)

private fun paymentFromJson(m: Map<*, *>, legacy: Boolean) = Payment(
    id = m.lngF("id", 0L),
    partyId = m.lngN("partyId"),
    invoiceId = m.lngN("invoiceId"),
    checkId = m.lngN("checkId"),
    amount = m.moneyF("amount", legacy), // [P33-P8]
    date = m.lngF("date", 0L),
    direction = m.intF("direction", 0),
    method = m.strF("method", "CASH"),
    note = m.strF("note", ""),
    planId = m.lngN("planId")
)

private fun visitFromJson(m: Map<*, *>) = Visit(
    id = m.lngF("id", 0L),
    partyId = m.lngF("partyId", 0L),
    visitedAt = m.lngF("visitedAt", 0L),
    lat = m.dblN("lat"),
    lng = m.dblN("lng"),
    note = m.strF("note", "")
)

private fun expenseFromJson(m: Map<*, *>, legacy: Boolean) = Expense(
    id = m.lngF("id", 0L),
    amount = m.moneyF("amount", legacy), // [P33-P8]
    category = m.strF("category", ""),
    note = m.strF("note", ""),
    date = m.lngF("date", 0L),
    createdAt = m.lngF("createdAt", 0L)
)

private fun checkFromJson(m: Map<*, *>, legacy: Boolean) = CheckEntity(
    id = m.lngF("id", 0L),
    number = m.strF("number", ""),
    partyId = m.lngF("partyId", 0L),
    bank = m.strF("bank", ""),
    amount = m.moneyF("amount", legacy), // [P33-P8]
    issueDate = m.lngF("issueDate", 0L),
    dueDate = m.lngF("dueDate", 0L),
    direction = m.intF("direction", 0),
    status = m.intF("status", 0),
    note = m.strF("note", "")
)

private fun planFromJson(m: Map<*, *>, legacy: Boolean) = InstallmentPlan(
    id = m.lngF("id", 0L),
    title = m.strF("title", ""),
    partyId = m.lngF("partyId", 0L),
    direction = m.intF("direction", 0),
    total = m.moneyF("total", legacy), // [P33-P8]
    downPayment = m.moneyF("downPayment", legacy), // [P33-P8]
    financed = m.moneyF("financed", legacy), // [P33-P8]
    months = m.intF("months", 0),
    startDate = m.lngF("startDate", 0L),
    currency = m.strF("currency", "SAR"),
    note = m.strF("note", ""),
    createdAt = m.lngF("createdAt", 0L),
    archived = m.boolF("archived", false)
)

private fun installmentFromJson(m: Map<*, *>, legacy: Boolean) = Installment(
    id = m.lngF("id", 0L),
    planId = m.lngF("planId", 0L),
    seq = m.intF("seq", 0),
    amount = m.moneyF("amount", legacy), // [P33-P8]
    dueDate = m.lngF("dueDate", 0L),
    paidAmount = m.moneyF("paidAmount", legacy), // [P33-P8]
    paidDate = m.lngN("paidDate"),
    status = m.intF("status", 0)
)

private fun currencyFromJson(m: Map<*, *>) = Currency(
    code = m.strF("code", ""),
    nameAr = m.strF("nameAr", ""),
    nameEn = m.strF("nameEn", ""),
    symbol = m.strF("symbol", ""),
    rateToBase = m.dblF("rateToBase", 1.0),
    isBase = m.boolF("isBase", false)
)

private fun ruleFromJson(m: Map<*, *>) = Rule(
    id = m.lngF("id", 0L),
    kind = m.strF("kind", ""),
    enabled = m.boolF("enabled", true),
    daysBefore = m.intF("daysBefore", 3),
    lastRun = m.lngF("lastRun", 0L)
)

private fun journalEntryFromJson(m: Map<*, *>) = JournalEntry(
    id = m.lngF("id", 0L),
    date = m.lngF("date", 0L),
    memo = m.strF("memo", ""),
    refType = m.strNF("refType"),
    refId = m.lngN("refId")
)

private fun journalLineFromJson(m: Map<*, *>, legacy: Boolean) = JournalLine(
    id = m.lngF("id", 0L),
    entryId = m.lngF("entryId", 0L),
    account = m.strF("account", ""),
    debit = m.moneyF("debit", legacy), // [P33-P8]
    credit = m.moneyF("credit", legacy), // [P33-P8]
    partyId = m.lngN("partyId"),
    currency = m.strF("currency", "SAR"),
    fxRate = m.dblF("fxRate", 1.0)
)

private fun stockMoveFromJson(m: Map<*, *>) = StockMove(
    id = m.lngF("id", 0L),
    productId = m.lngF("productId", 0L),
    qty = m.dblF("qty", 0.0),
    reason = m.strF("reason", ""),
    date = m.lngF("date", 0L),
    refType = m.strNF("refType"),
    refId = m.lngN("refId"),
    note = m.strF("note", "")
)

// ── [P34-M1] قارئات جداول منظومة الكشوف الثمانية (23/23) — بلا مبالغ إلا threshold ──

private fun Map<*, *>.intNF(key: String): Int? = when (val v = this[key]) {
    null -> null
    is Long -> v.toInt()
    else -> badField(key, "عدداً صحيحاً أو null")
}

private fun statementTemplateFromJson(m: Map<*, *>) = StatementTemplateEntity(
    id = m.lngF("id", 0L),
    name = m.strF("name", ""),
    baseTemplateId = m.strF("baseTemplateId", "CUSTOM"),
    configJson = m.strF("configJson", "{}"),
    isDefault = m.boolF("isDefault", false),
    favorite = m.boolF("favorite", false),
    createdAt = m.lngF("createdAt", 0L),
    updatedAt = m.lngF("updatedAt", 0L)
)

private fun signatureFromJson(m: Map<*, *>) = SignatureEntity(
    id = m.lngF("id", 0L),
    name = m.strF("name", ""),
    jobTitle = m.strNF("jobTitle"),
    imagePath = m.strF("imagePath", ""),
    isDefault = m.boolF("isDefault", false),
    active = m.boolF("active", true),
    createdAt = m.lngF("createdAt", 0L)
)

private fun stampFromJson(m: Map<*, *>) = StampEntity(
    id = m.lngF("id", 0L),
    name = m.strF("name", ""),
    imagePath = m.strF("imagePath", ""),
    isDefault = m.boolF("isDefault", false),
    active = m.boolF("active", true),
    createdAt = m.lngF("createdAt", 0L)
)

private fun noteTemplateFromJson(m: Map<*, *>) = NoteTemplateEntity(
    id = m.lngF("id", 0L),
    title = m.strF("title", ""),
    body = m.strF("body", ""),
    isDefault = m.boolF("isDefault", false)
)

private fun statementFromJson(m: Map<*, *>) = StatementEntity(
    id = m.lngF("id", 0L),
    statementNumber = m.strF("statementNumber", ""),
    verificationId = m.strF("verificationId", ""),
    partyId = m.lngF("partyId", 0L),
    fromTs = m.lngF("fromTs", 0L),
    toTs = m.lngF("toTs", 0L),
    templateId = m.strF("templateId", "CUSTOM"),
    currency = m.strF("currency", "SAR"),
    contentHash = m.strF("contentHash", ""),
    filePath = m.strF("filePath", ""),
    note = m.strNF("note"),
    createdAt = m.lngF("createdAt", 0L),
    lang = m.strF("lang", "AR")
)

private fun statementDeliveryFromJson(m: Map<*, *>) = StatementDeliveryEntity(
    id = m.lngF("id", 0L),
    statementId = m.lngF("statementId", 0L),
    channel = m.strF("channel", "SHARE"),
    status = m.strF("status", "PENDING"),
    attempts = m.intF("attempts", 0),
    lastError = m.strNF("lastError"),
    sentAt = m.lngN("sentAt"),
    scheduledFor = m.lngN("scheduledFor"),
    dedupKey = m.strF("dedupKey", ""),
    lastAttemptAt = m.lngN("lastAttemptAt")
)

private fun statementRuleFromJson(m: Map<*, *>, legacy: Boolean) = StatementRuleEntity(
    id = m.lngF("id", 0L),
    name = m.strF("name", ""),
    enabled = m.boolF("enabled", true),
    partyMode = m.strF("partyMode", "ALL"),
    partyIdsJson = m.strF("partyIdsJson", ""),
    frequency = m.strF("frequency", "MONTHLY"),
    weekday = m.intNF("weekday"),
    dayOfMonth = m.intNF("dayOfMonth"),
    hour = m.intF("hour", 8),
    minute = m.intF("minute", 0),
    periodPreset = m.strF("periodPreset", "PREVIOUS_MONTH"),
    templateId = m.strNF("templateId"),
    signatureId = m.lngN("signatureId"),
    stampId = m.lngN("stampId"),
    channel = m.strF("channel", "SHARE"),
    eventFlagsJson = m.strNF("eventFlagsJson"),
    threshold = m.moneyF("threshold", legacy), // [P33-P8] قروش — نفس واجهة ريال/قروش
    lastRunAt = m.lngN("lastRunAt"),
    nextRunAt = m.lngN("nextRunAt")
)

private fun auditLogFromJson(m: Map<*, *>) = AuditLogEntity(
    id = m.lngF("id", 0L),
    actor = m.strF("actor", "owner"),
    action = m.strF("action", ""),
    details = m.strF("details", ""),
    ts = m.lngF("ts", 0L)
)

// [P46-W1] قراءة جدولا الولاء والكوبونات — invoiceId=0 يعود null (دلالة «بلا فاتورة»)
private fun loyaltyEntryFromJson(m: Map<*, *>) = LoyaltyEntryEntity(
    id = m.lngF("id", 0L),
    partyId = m.lngF("partyId", 0L),
    invoiceId = m.lngF("invoiceId", 0L).takeIf { it > 0L },
    delta = m.lngF("delta", 0L),
    reason = m.strF("reason", "MANUAL_GRANT"),
    note = m.strF("note", ""),
    createdAt = m.lngF("createdAt", 0L)
)

private fun couponFromJson(m: Map<*, *>) = CouponEntity(
    id = m.lngF("id", 0L),
    code = m.strF("code", ""),
    kind = m.intF("kind", 0),
    amountPiasters = m.lngF("amountPiasters", 0L),
    percent = m.dblF("percent", 0.0),
    expiresAt = m.lngF("expiresAt", 0L),
    maxUses = m.intF("maxUses", 0),
    usedCount = m.intF("usedCount", 0),
    active = m.boolF("active", true),
    note = m.strF("note", ""),
    createdAt = m.lngF("createdAt", 0L)
)

// ─────────────────────────── خطة الدمج ───────────────────────────

/**
 * [P13-b] خطة دمج نقية (بلا قاعدة بيانات ولا Android) — [P14-b] للاستعادة 
 * • صف بمعرف >0 وغير موجود في القاعدة وغير مكرر داخل الملف → imported.
 * • صف بمعرف موجود أصلاً في القاعدة أو مكرر داخل الملف → skipped (لا يمسّ بيانات المستخدم).
 * • صف بمعرف 0 أو سالب → failed (صف تالف العُرف) — العدّاد يجمع الجداول الأربعة كلها.
 * • [P14-b] الجداول الأربعة: parties/products (كما في v1) + visits/expenses بذواتها
 * القواعد حرفياً — مستقلة تماماً عن بعضها (تكرار id في الزيارات لا علاقة له بالمصروفات).
 * • [P14-b] الوسيطتان الجديدتان لهما افتراضياً emptySet — نداءات بثلاث وسيطات
 * تعمل كما هي وتعامل الزيارات/المصروفات كأن القاعدة خالية منها (نمط الاستيراد الكامل).
 * استرداد الجداول الأخرى (فواتير/قيود/…) مقصود غير مُفعّل — انظر رأس الملف.
 *
 * [P15-b] ثلاث وسيطات جديدة (الشيكات/الخطط/الأقساط — emptySet افتراضياً بذات لغز
 * التوافق) وثلاث حلقات، بقاعدة اليتيم الإضافية المفروضة بتدقيق Entities.kt
 * • checks وinstallment_plans لهما ForeignKey باتجاه parties (RESTRICT) — صف يشير إلى
 * طرف غير موجود (لا في existingPartyIds ولا ضمن أطراف الملف التي ستُستورد فعلاً)
 * يُتخطى skipped لا failed (يتيم بنيوي: إدراجه كان سيرفع SQLiteConstraintException
 * ويراجع المعاملة كلها).
 * • installments له ForeignKey باتجاه installment_plans (CASCADE) — planId ≤0 أو غير
 * موجود في (existingPlanIds ∪ معرفات خطط الملف التي ستُستورد فعلاً) → skipped.
 * خطةٌ يتيمةُ أبّها داخل الملف تجرّ أقساطها إلى التخطي تلقائياً (تسلسل متسق).
 * • ترتيب الحلقات مقصود: parties (لجمع معرفاتها المستوردة) ثم plans (لجمع معرفاتها)
 * ثم installments ثم checks — كل حلقة تحتاج مجموعات الحلقات السابقة.
 * مجموعتا «المستورد فعلاً» (importedPartyIds/importedPlanIds) تحسبان هنا بالقواعد نفسها
 * التي يطبّقها منفّذ الاستيراد في BackupRestoreRepo حرفياً — الخطة = التنفيذ بلا انحراف.
*/
fun planImport(
    d: BackupData,
    existingPartyIds: Set<Long>,
    existingProductIds: Set<Long>,
    existingVisitIds: Set<Long> = emptySet(),       // [P14-b] نطاق v2
    existingExpenseIds: Set<Long> = emptySet(),     // [P14-b] نطاق v2
    existingCheckIds: Set<Long> = emptySet(),       // [P15-b] نطاق v3
    existingPlanIds: Set<Long> = emptySet(),        // [P15-b] نطاق v3
    existingInstallmentIds: Set<Long> = emptySet(), // [P15-b] نطاق v3
    // [P34-M1] نطاق v4 — موجودات جداول الكشوف الثمانية (بقيم افتراضية للتوافق الخلفي)
    existingStatementTemplateIds: Set<Long> = emptySet(),
    existingSignatureIds: Set<Long> = emptySet(),
    existingStampIds: Set<Long> = emptySet(),
    existingNoteTemplateIds: Set<Long> = emptySet(),
    existingStatementIds: Set<Long> = emptySet(),
    existingStatementDeliveryIds: Set<Long> = emptySet(),
    existingStatementRuleIds: Set<Long> = emptySet(),
    existingAuditLogIds: Set<Long> = emptySet(),
    // [P46-W1] نطاق v5 — موجودات جدولا الولاء والكوبونات + الفواتير (أبٍ الولاء)
    existingInvoiceIds: Set<Long> = emptySet(),
    existingLoyaltyEntryIds: Set<Long> = emptySet(),
    existingCouponIds: Set<Long> = emptySet()
): ImportStats {
    var importedP = 0
    var skippedP = 0
    var failed = 0
    val seenParties = HashSet<Long>()
    // [P15-b] معرفات الأطراف التي ستُستورد فعلاً — أباء صالحون للشيكات والخطط
    val importedPartyIds = HashSet<Long>()
    for (p in d.parties) {
        when {
            p.id <= 0L -> failed++
            p.id in existingPartyIds || !seenParties.add(p.id) -> skippedP++
            else -> { importedPartyIds.add(p.id); importedP++ }
        }
    }
    // [P15-b] الأباء الصالحون = الموجودون في القاعدة + المُستوردون في المعاملة نفسها
    val validPartyIds = existingPartyIds + importedPartyIds
    var importedPr = 0
    var skippedPr = 0
    val seenProducts = HashSet<Long>()
    for (p in d.products) {
        when {
            p.id <= 0L -> failed++
            p.id in existingProductIds || !seenProducts.add(p.id) -> skippedPr++
            else -> importedPr++
        }
    }
    // [P14-b] الزيارات — نفس القواعد حرفياً: موجود/مكرر يُتخطى، تالف يُعدّ في failed المشترك
    var importedV = 0
    var skippedV = 0
    val seenVisits = HashSet<Long>()
    for (v in d.visits) {
        when {
            v.id <= 0L -> failed++
            v.id in existingVisitIds || !seenVisits.add(v.id) -> skippedV++
            else -> importedV++
        }
    }
    // [P14-b] المصروفات — مستقلة تماماً عن الزيارات (مجموعة seen خاصة بها)
    var importedE = 0
    var skippedE = 0
    val seenExpenses = HashSet<Long>()
    for (e in d.expenses) {
        when {
            e.id <= 0L -> failed++
            e.id in existingExpenseIds || !seenExpenses.add(e.id) -> skippedE++
            else -> importedE++
        }
    }
    // [P15-b] الخطط — قواعد الجداول السابقة + قاعدة اليتيم (ForeignKey باتجاه parties RESTRICT):
    // خطة يشير طرفها إلى طرف غائب تُتخطى skipped. الحلقة قبل الأقساط عمداً — معرفاتها
    // المستوردة هي التي تُجيز أقساطها في الحلقة التالية.
    var importedPl = 0
    var skippedPl = 0
    val seenPlans = HashSet<Long>()
    val importedPlanIds = HashSet<Long>()
    for (p in d.plans) {
        when {
            p.id <= 0L -> failed++
            p.id in existingPlanIds || !seenPlans.add(p.id) -> skippedPl++
            p.partyId !in validPartyIds -> skippedPl++ // يتيمة بنيوياً — لا failed
            else -> { importedPlanIds.add(p.id); importedPl++ }
        }
    }
    // [P15-b] الأقساط — قاعدة اليتيم باتجاه الخطط (ForeignKey CASCADE):
    // planId ≤0 أو غير موجود في (الخطط الموجودة بالقاعدة ∪ خطط الملف المستوردة فعلاً)
    // → skipped. الأباء الصالحون هنا يشملون خطط القاعدة القديمة فحتى أقساط خطة موجودة
    // (مؤرشفة كذلك — plansExport يشملها) تُستورد، وخطة الملف المتخطاة ليتاميها تجعل
    // أقساطها يتيمة بالتسلسل (معرفها ليس في المجموعتين).
    var importedIn = 0
    var skippedIn = 0
    val seenInstallments = HashSet<Long>()
    val validPlanIds = existingPlanIds + importedPlanIds
    for (i in d.installments) {
        when {
            i.id <= 0L -> failed++
            i.id in existingInstallmentIds || !seenInstallments.add(i.id) -> skippedIn++
            i.planId <= 0L || i.planId !in validPlanIds -> skippedIn++ // يتيمة بنيوياً — لا failed
            else -> importedIn++
        }
    }
    // [P15-b] الشيكات — قاعدة اليتيم باتجاه الأطراف (ForeignKey RESTRICT) ذاتها
    var importedC = 0
    var skippedC = 0
    val seenChecks = HashSet<Long>()
    for (c in d.checks) {
        when {
            c.id <= 0L -> failed++
            c.id in existingCheckIds || !seenChecks.add(c.id) -> skippedC++
            c.partyId !in validPartyIds -> skippedC++ // يتيم بنيوياً — لا failed
            else -> importedC++
        }
    }
    // ═══ [P34-M1] نطاق v4 — جداول منظومة الكشوف الثمانية (23/23) ═══
    // قواعد الجداول السابقة حرفياً + سلسلة اليتيم الممتدة حسب مفاتيح Entities.kt:
    // • statements → parties (RESTRICT): كشف يشير إلى طرف غائب (لا موجود أصلاً ولا
    //   مُستورد فعلاً) يُتخطى skipped — إدراجه كان سيرفع SQLiteConstraintException.
    // • statement_deliveries → statements (CASCADE): تسليم يشير إلى كشف غائب (لا موجود
    //   بالقاعدة ولا ضمن كشوف الملف التي ستُستورد فعلاً) يُتخطى skipped.
    // • statement_templates/signatures/stamps/note_templates/audit_log: مستقلة بلا
    //   مفاتيح أجنبية — قواعد المعرف الخالصة (id>0، غير موجود، غير مكرر داخل الملف).
    // • statement_rules: بلا مفاتيح أجنبية في المخطط (templateId/signatureId/stampId
    //   مراجع ناعمة اختيارية) — قواعد المعرف الخالصة؛ مرجعٌ ناعمٌ دangling يتعامل معه
    //   المنفّذ وقت التشغيل بالافتراضي (سلوك degrade موثق في StatementService) فلا
    //   حاجة لتخطي قاعدة سليمة بغير مرجعها.
    val validStatementTemplateIds = existingStatementTemplateIds
    var importedT = 0
    var skippedT = 0
    val seenTemplates = HashSet<Long>()
    for (t in d.statementTemplates) {
        when {
            t.id <= 0L -> failed++
            t.id in validStatementTemplateIds || !seenTemplates.add(t.id) -> skippedT++
            else -> importedT++
        }
    }
    var importedSig = 0
    var skippedSig = 0
    val seenSignatures = HashSet<Long>()
    for (s in d.signatures) {
        when {
            s.id <= 0L -> failed++
            s.id in existingSignatureIds || !seenSignatures.add(s.id) -> skippedSig++
            else -> importedSig++
        }
    }
    var importedSt = 0
    var skippedSt = 0
    val seenStamps = HashSet<Long>()
    for (s in d.stamps) {
        when {
            s.id <= 0L -> failed++
            s.id in existingStampIds || !seenStamps.add(s.id) -> skippedSt++
            else -> importedSt++
        }
    }
    var importedNt = 0
    var skippedNt = 0
    val seenNoteTemplates = HashSet<Long>()
    for (n in d.noteTemplates) {
        when {
            n.id <= 0L -> failed++
            n.id in existingNoteTemplateIds || !seenNoteTemplates.add(n.id) -> skippedNt++
            else -> importedNt++
        }
    }
    // الكشوف — قاعدة اليتيم باتجاه الأطراف (RESTRICT) بذات مرشّح الشيكات حرفياً
    var importedSm = 0
    var skippedSm = 0
    val seenStatements = HashSet<Long>()
    val importedStatementIds = HashSet<Long>()
    for (s in d.statements) {
        when {
            s.id <= 0L -> failed++
            s.id in existingStatementIds || !seenStatements.add(s.id) -> skippedSm++
            s.partyId !in validPartyIds -> skippedSm++ // يتيم بنيوياً — لا failed
            else -> { importedStatementIds.add(s.id); importedSm++ }
        }
    }
    // التسليمات — قاعدة اليتيم باتجاه الكشوف (CASCADE): الأباء الصالحون = كشوف القاعدة
    // الموجودة أصلاً + كشوف الملف التي ستُستورد فعلاً في المعاملة نفسها
    val validStatementIds = existingStatementIds + importedStatementIds
    var importedDl = 0
    var skippedDl = 0
    val seenDeliveries = HashSet<Long>()
    for (dl in d.statementDeliveries) {
        when {
            dl.id <= 0L -> failed++
            dl.id in existingStatementDeliveryIds || !seenDeliveries.add(dl.id) -> skippedDl++
            dl.statementId !in validStatementIds -> skippedDl++ // يتيم بنيوياً — لا failed
            else -> importedDl++
        }
    }
    // القواعد — بلا مفاتيح أجنبية (مراجع ناعمة) — قواعد المعرف الخالصة
    var importedRl = 0
    var skippedRl = 0
    val seenRules = HashSet<Long>()
    for (r in d.statementRules) {
        when {
            r.id <= 0L -> failed++
            r.id in existingStatementRuleIds || !seenRules.add(r.id) -> skippedRl++
            else -> importedRl++
        }
    }
    // سجل التدقيق — إلحاق فقط، مستقل تماماً — قواعد المعرف الخالصة
    var importedAl = 0
    var skippedAl = 0
    val seenAudit = HashSet<Long>()
    for (a in d.auditLog) {
        when {
            a.id <= 0L -> failed++
            a.id in existingAuditLogIds || !seenAudit.add(a.id) -> skippedAl++
            else -> importedAl++
        }
    }
    // [P46-W1] الكوبونات — مستقلة تماماً — قواعد المعرف الخالصة
    var importedCp = 0
    var skippedCp = 0
    val seenCoupons = HashSet<Long>()
    for (c in d.coupons) {
        when {
            c.id <= 0L -> failed++
            c.id in existingCouponIds || !seenCoupons.add(c.id) -> skippedCp++
            else -> importedCp++
        }
    }
    // [P46-W1] دفتر الولاء — يتيم باتجاه الطرف (CASCADE) وباتجاه الفاتورة إن وُجدت:
    // صف بطرف غائب أو بفاتورة غائمة (invoiceId ≠ null وليست في القاعدة) يتخطى لا يفشل
    var importedL = 0
    var skippedL = 0
    val seenLoyalty = HashSet<Long>()
    for (e in d.loyaltyEntries) {
        when {
            e.id <= 0L -> failed++
            e.id in existingLoyaltyEntryIds || !seenLoyalty.add(e.id) -> skippedL++
            e.partyId !in validPartyIds -> skippedL++ // يتيم بنيوياً — لا failed
            e.invoiceId != null && e.invoiceId !in existingInvoiceIds -> skippedL++ // فاتورة غائمة — لا failed
            else -> importedL++
        }
    }
    val tables = linkedMapOf(
        BackupTables.PARTIES to d.parties.size,
        BackupTables.PRODUCTS to d.products.size,
        BackupTables.INVOICES to d.invoices.size,
        BackupTables.INVOICE_ITEMS to d.invoiceItems.size,
        BackupTables.PAYMENTS to d.payments.size,
        BackupTables.VISITS to d.visits.size,
        BackupTables.EXPENSES to d.expenses.size,
        BackupTables.CHECKS to d.checks.size,
        BackupTables.PLANS to d.plans.size,
        BackupTables.INSTALLMENTS to d.installments.size,
        BackupTables.CURRENCIES to d.currencies.size,
        BackupTables.RULES to d.rules.size,
        BackupTables.JOURNAL to d.journal.size,
        BackupTables.JOURNAL_LINES to d.journalLines.size,
        BackupTables.STOCK_MOVES to d.stockMoves.size,
        BackupTables.STATEMENT_TEMPLATES to d.statementTemplates.size,
        BackupTables.SIGNATURES to d.signatures.size,
        BackupTables.STAMPS to d.stamps.size,
        BackupTables.NOTE_TEMPLATES to d.noteTemplates.size,
        BackupTables.STATEMENTS to d.statements.size,
        BackupTables.STATEMENT_DELIVERIES to d.statementDeliveries.size,
        BackupTables.STATEMENT_RULES to d.statementRules.size,
        BackupTables.AUDIT_LOG to d.auditLog.size,
        BackupTables.LOYALTY_ENTRIES to d.loyaltyEntries.size,
        BackupTables.COUPONS to d.coupons.size
    )
    return ImportStats(
        importedP, skippedP, importedPr, skippedPr, failed, tables,
        importedV, skippedV, importedE, skippedE, // [P14-b] إحصاء الجدولين الجديدين
        importedC, skippedC, importedPl, skippedPl, importedIn, skippedIn, // [P15-b] نطاق v3
        // [P34-M1] نطاق v4 — إحصاء جداول الكشوف الثمانية
        importedT, skippedT, importedSig, skippedSig, importedSt, skippedSt,
        importedNt, skippedNt, importedSm, skippedSm, importedDl, skippedDl,
        importedRl, skippedRl, importedAl, skippedAl,
        // [P46-W1] نطاق v5 — إحصاء جدولا الولاء والكوبونات
        importedL, skippedL, importedCp, skippedCp
    )
}
