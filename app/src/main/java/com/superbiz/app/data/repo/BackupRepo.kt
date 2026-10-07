package com.superbiz.app.data.repo

import android.content.Context
import android.net.Uri
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.CheckEntity
import com.superbiz.app.data.db.Currency
import com.superbiz.app.data.db.Expense
import com.superbiz.app.data.db.Invoice
import com.superbiz.app.data.db.InvoiceItem
import com.superbiz.app.data.db.JournalEntry
import com.superbiz.app.data.db.JournalLine
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.db.Payment
import com.superbiz.app.data.db.Product
import com.superbiz.app.data.db.Rule
import com.superbiz.app.data.db.StockMove
import org.json.JSONArray
import org.json.JSONObject
import com.superbiz.app.domain.backup.BackupTables
import com.superbiz.app.util.Money
import java.io.File
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * نسخ احتياطي حقيقي كامل الدقة ()
 * • تصدير ١٠٠٪ من الجداول: كل حالات الشيكات (وليس قيد التحصيل فقط)، أعلام الأرشفة،
 * عملة الفواتير وسعر الصرف، حالة الأقساط، المصروفات، lastRun للقواعد.
 * • استيراد مُتحقَّق منه: تُفكَّك كل البيانات وتُفحص أولاً في الذاكرة،
 * ثم يُمسح ويُعاد الإدخال داخل معاملة واحدة — فشل أي صف يعيد قاعدة البيانات كما كانت.
 * التصدير عبر SAF (Uri) أو إلى filesDir/backups.
*/
class BackupRepo(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepo
) {

    // [P33-P8] الصيغة 3: كل المبالغ قروش Long. الصيغتان 1/2 كانتان تخزنان المبالغ
    // ريالاً Double — تُحوَّل قروش عبر Money.toPiasters عند الاستيراد (توافق النسخ القديم)
    companion object { const val FORMAT_VERSION = 3 }

    /**
     * قراءة مبلغ مالي من نسخة احتياطية — نقطة التوافق الوحيدة بين عالمَي التخزين:
     * legacyRiyal=true (صيغة ≤2): القيمة ريال Double → Money.toPiasters (HALF_UP)
     * legacyRiyal=false (صيغة 3): القيمة قروش Long تُقرأ كما هي.
     * المفاتيح الصارمة (required) ترمي JSONException عند غيابها كما كان —
     * فشل الاستعادة الكامل لا صفوف ناقصة. [P33-P8]
     */
    private fun moneyField(j: JSONObject, key: String, legacyRiyal: Boolean, required: Boolean = false): Long {
        if (!legacyRiyal) return if (required) j.getLong(key) else j.optLong(key, 0L)
        val v = if (required) j.getDouble(key) else j.optDouble(key, 0.0)
        return Money.toPiasters(v)
    }

    private inline fun org.json.JSONArray.each(block: (org.json.JSONObject) -> Unit) {
        for (i in 0 until length()) block(getJSONObject(i))
    }

    suspend fun exportJson(): JSONObject {
        val root = JSONObject()
        root.put("app", "SuperBiz")
        root.put("format", FORMAT_VERSION)
        root.put("exportedAt", System.currentTimeMillis())
        // [تدقيق H-3] القراءة الشاملة داخل معاملة Room واحدة — عزل اللقطة (snapshot isolation)
        // يجعل النسخة متسقة: عملية أُلصقت أثناء التصدير تُدرج كاملة (فاتورة + بنودها + قيدها)
        // أو لا تُدرج إطلاقاً — لا نسخة ممزقة تعيد دفتراً غير متوازن بعد الاستعادة.
        db.withTransaction {
        // exportOnce تشمل المؤرشف — كان allOnce() يفلتره فيُحذف نهائياً بعد الاستعادة
        root.put("parties", JSONArray(db.parties().exportOnce().map { partyJson(it) }))
        root.put("products", JSONArray(db.products().exportOnce().map { productJson(it) }))
        // تصدير كل الفواتير حتى الملغاة — كان allOnce يفلتر status<3 فيُسقط الفواتير الملغاة وبنودها
        root.put("invoices", JSONArray(db.invoices().exportOnce().map { invoiceJson(it) }))
        // إصلاح عطل تشغيلي: الدوال الأربع أدناه تعيد JSONArray جاهزاً —
        // كان تغليفها بـ JSONArray() أخرى يرمي "Not a primitive array" فيفشل
        // تصدير النسخ الاحتياطي دائماً (رسالة «فشل النسخ» رغم سلامة البيانات)
        root.put("invoice_items", itemsArray())
        root.put("checks", JSONArray(db.checks().allOnce().map { checkJson(it) }))
        root.put("installment_plans", plansArray())
        root.put("installments", installmentsArray())
        root.put("payments", paymentsArray())
        // [P37-TD]: محاذاة مفتاح القيود مع عقد BackupTables.JOURNAL = "journal"
        // (كان "journal_entries" — الكاتب والقارئ متوافقان داخلياً لكن المفتاح كان منحرفاً
        // عن القائمة القياسية الـ23 التي تعتمدها رقاقة التغطية واختبار العقد؛ القارئ يقبل
        // المفتاحين — الجديد القياسي والقديم للنسخ المحفوظة سابقاً)
        root.put(BackupTables.JOURNAL, journalEntriesArray())
        root.put(BackupTables.JOURNAL_LINES, journalLinesArray())
        root.put("rules", JSONArray(db.rules().allOnce().map { ruleJson(it) }))
        root.put("currencies", JSONArray(db.currencies().allOnce().map { currencyJson(it) }))
        root.put("expenses", expensesArray())
        // حركات المخزون — كان الجدول بلا مسار تصدير/استعادة إطلاقاً
        root.put("stock_moves", stockMovesArray())
        // [P20-FIX agent18]: تصدير الزيارات (بلا FK — الإدراج بعد الأطراف في الاستعادة)
        root.put("visits", JSONArray(db.visits().allOnce().map { visitJson(it) }))
        // ── [P36-BK] جداول منظومة الكشوف الثمانية — اكتمال التغطية 23/23 في مسار النسخ
        // التلقائي أيضاً (كانت مسار الدمج BackupRestoreRepo وحده يغطيها منذ P34).
        // أسماء الحقول مطابقة لعقد BackupKit حرفياً (statementTemplateToJson وشرِكه)،
        // والإضافة تراكمية: الصيغة تبقى 3 والقارئات القديمة تتجاهل المفاتيح الجديدة بأمان.
        root.put("statement_templates", JSONArray(db.statementTemplates().allOnce().map { statementTemplateJson(it) }))
        root.put("signatures", JSONArray(db.signatures().allOnce().map { signatureJson(it) }))
        root.put("stamps", JSONArray(db.stamps().allOnce().map { stampJson(it) }))
        root.put("note_templates", JSONArray(db.noteTemplates().allOnce().map { noteTemplateJson(it) }))
        root.put("statements", JSONArray(db.statements().listAll(Int.MAX_VALUE).map { statementJson(it) }))
        root.put("statement_deliveries", JSONArray(db.statements().allDeliveries().map { statementDeliveryJson(it) }))
        root.put("statement_rules", JSONArray(db.statementRules().allOnce().map { statementRuleJson(it) }))
        root.put("audit_log", JSONArray(db.auditLog().allLogs().map { auditLogJson(it) }))
        // [P46-W1] جولة 7 — جدولا الولاء والكوبونات (اكتمال 25/25)
        root.put("loyalty_entries", JSONArray(db.loyalty().allOnce().map { loyaltyEntryJson(it) }))
        root.put("coupons", JSONArray(db.coupons().allOnce().map { couponJson(it) }))
        } // [تدقيق H-3] نهاية معاملة اللقطة المتسقة
        // أرشيف الشيكات (DataStore مستقل) لم يكن يُصدَّر — بعد الاستعادة
        // كانت كل الشيكات المؤرشفة تعود إلى القائمة النشطة
        // (قراءة DataStore خارج معاملة Room عمداً — لا يشتركان المعاملة)
        root.put("checks_archive", JSONArray(com.superbiz.app.data.repo.ChecksArchiveStore(context)
            .ids.first().sorted()))
        val s = settings.snapshot()
        // تصدير كل مفاتيح الإعدادات التي يعرضها SettingsRepo (كانت 5 فقط) —
        // مفاتيح إضافية دون تغيير إصدار الصيغة (النسخ القديمة تتجاهلها بأمان)
        // مادة الرمز (pin*) حُذفت من التصدير نهائياً — خطر تخمين دون اتصال
        root.put("settings", JSONObject()
            .put("businessName", s.businessName)
            // [P7-X2 إصلاح]: avatarPath و backupDirUri أُقصيا من التصدير — عقد SettingsCodec
            // يصنفهما «حالة جهاز: مسارات محلية لا تصلح لجهاز آخر» (مسار أفاتار محلي وSAF-URI
            // بإذن ممنوح لجهاز المصدر فقط)؛ كانت تُزرع في الجهاز المستعير قيمًا ميتة
            .put("language", s.language)
            .put("theme", s.theme)
            .put("baseCurrency", s.baseCurrency)
            .put("taxRate", s.taxRate)
            // مادة التحقق من الرمز لم تعد تُصدَّر إطلاقاً — hashPBKDF2 لرمز 4-8
            // أرقام يُخمَّن دون اتصال من أي نسخة مسرّبة، و ks: لا تعمل خارج الجهاز أصلاً
            // [P7-X2 إصلاح]: backupDirUri أُقصي أيضاً (كان يُصدَّر هنا) — انظر وسم P7-X2 أعلاه
            .put("lastAutoBackup", s.lastAutoBackup)
            .put("walkInPartyId", s.walkInPartyId)
            .put("seeded", s.seeded)
            .put("ownerName", s.ownerName)
            .put("phone", s.phone)
            .put("email", s.email)
            .put("address", s.address)
            .put("taxNumber", s.taxNumber)
            // [P5-H2 إصلاح]: علم biometric حُذف من التصدير نهائياً — كان يُستعاد سابقاً على جهاز
            // جديد بلا pin* فينتج حالة «بصمة بلا رمز» تتيح تجاوز القفل ببصمة مالك الجهاز الجديد
            // أو بنقرة «متابعة (طارئ)». سياسة R12-C11 نفسها: بوابة الأمان لا تترك الجهاز أبداً،
            // والنسخ القديمة تحمل المفتاح لكن استيرادنا يتجاهله بأمان
            .put("welcomeSeen", s.welcomeSeen)
            .put("permissionsSeen", s.permissionsSeen)
            .put("autoBackupDays", s.autoBackupDays)
            .put("reportScheduleDays", s.reportScheduleDays)
            .put("reportScheduleHour", s.reportScheduleHour)
            .put("reportScheduleChannel", s.reportScheduleChannel)
            .put("lastScheduledReport", s.lastScheduledReport)
            .put("reportRecipient", s.reportRecipient)
            .put("monthlyGoal", s.monthlyGoal)
            .put("redactWidgets", s.redactWidgets)
            // مفاتيح الإعدادات المركزية الجديدة — تُصدَّر وتُستعاد كاملة
            .put("hapticsEnabled", s.hapticsEnabled)
            .put("confirmDestructive", s.confirmDestructive)
            .put("flagSecure", s.flagSecure)
            .put("lockTimeoutMin", s.lockTimeoutMin)
            .put("fontScale", s.fontScale.toDouble())
            .put("dynamicColors", s.dynamicColors)
            .put("mirrorChartsRtl", s.mirrorChartsRtl)
            .put("animationsEnabled", s.animationsEnabled)
            .put("arabicReceiptMode", s.arabicReceiptMode)
            .put("defaultLowStockQty", s.defaultLowStockQty)
            .put("lowStockAlerts", s.lowStockAlerts)
            .put("receivableAlerts", s.receivableAlerts)
            // 8 مفاتيح لها مستهلكون فعليون كانت تُهمل من التصدير
            // فتعود لقيمها الافتراضية بعد أي استعادة (الغرامات، حد المصروفات، الطمس…)
            .put("defaultTargetMargin", s.defaultTargetMargin)
            .put("lateFeeDailyPct", s.lateFeeDailyPct)
            .put("lateFeeCapPct", s.lateFeeCapPct)
            .put("weekendFriSat", s.weekendFriSat)
            .put("searchFuzzyThreshold", s.searchFuzzyThreshold)
            .put("eoqOrderCost", s.eoqOrderCost)
            .put("expenseMonthlyLimit", s.expenseMonthlyLimit)
            .put("privacyBlur", s.privacyBlur))
        return root
    }

    private fun partyJson(p: Party) = JSONObject()
        .put("id", p.id).put("name", p.name).put("phone", p.phone).put("type", p.type)
        .put("note", p.note).put("createdAt", p.createdAt).put("archived", p.archived)
        // [P20-FIX agent18]: favorite/lat/lng (P11-a) لم تكن تُصدَّر — كل دورة نسخ→استعادة
        // كانت تمسح كل المفضّلات وبطاقات المواقع الجغرافية صمتاً رغم وعد «تصدير 100%»
        .put("favorite", p.favorite)
        .put("lat", p.lat ?: JSONObject.NULL)
        .put("lng", p.lng ?: JSONObject.NULL)

    private fun visitJson(v: com.superbiz.app.data.db.Visit) = JSONObject()
        // [P20-FIX agent18]: جدول الزيارات كان بلا مسار تصدير/استعادة — جهاز مفقود = سجل الزيارات مفقود
        .put("id", v.id).put("partyId", v.partyId).put("visitedAt", v.visitedAt)
        .put("lat", v.lat ?: JSONObject.NULL)
        .put("lng", v.lng ?: JSONObject.NULL)
        .put("note", v.note)

    // ── [P36-BK] كتّاب جداول الكشوف الثمانية — أسماء الحقول مطابقة لعقد BackupKit حرفياً ──
    // (statementTemplateToJson/signatureToJson/stampToJson/noteTemplateToJson/statementToJson/
    //  statementDeliveryToJson/statementRuleToJson/auditLogToJson) — القيم nullable تُكتب
    // JSONObject.NULL صراحة (العقد الحرفي: كل الحقول دائماً) وthreshold قروش منذ P33-P8.
    private fun statementTemplateJson(t: com.superbiz.app.data.db.StatementTemplateEntity) = JSONObject()
        .put("id", t.id).put("name", t.name).put("baseTemplateId", t.baseTemplateId)
        .put("configJson", t.configJson).put("isDefault", t.isDefault).put("favorite", t.favorite)
        .put("createdAt", t.createdAt).put("updatedAt", t.updatedAt)

    private fun signatureJson(s: com.superbiz.app.data.db.SignatureEntity) = JSONObject()
        .put("id", s.id).put("name", s.name)
        .put("jobTitle", s.jobTitle ?: JSONObject.NULL)
        .put("imagePath", s.imagePath).put("isDefault", s.isDefault).put("active", s.active)
        .put("createdAt", s.createdAt)

    private fun stampJson(s: com.superbiz.app.data.db.StampEntity) = JSONObject()
        .put("id", s.id).put("name", s.name).put("imagePath", s.imagePath)
        .put("isDefault", s.isDefault).put("active", s.active).put("createdAt", s.createdAt)

    private fun noteTemplateJson(t: com.superbiz.app.data.db.NoteTemplateEntity) = JSONObject()
        .put("id", t.id).put("title", t.title).put("body", t.body).put("isDefault", t.isDefault)

    private fun statementJson(s: com.superbiz.app.data.db.StatementEntity) = JSONObject()
        .put("id", s.id).put("statementNumber", s.statementNumber).put("verificationId", s.verificationId)
        .put("partyId", s.partyId).put("fromTs", s.fromTs).put("toTs", s.toTs)
        .put("templateId", s.templateId).put("currency", s.currency)
        .put("contentHash", s.contentHash).put("filePath", s.filePath)
        .put("note", s.note ?: JSONObject.NULL)
        .put("createdAt", s.createdAt).put("lang", s.lang)

    private fun statementDeliveryJson(d: com.superbiz.app.data.db.StatementDeliveryEntity) = JSONObject()
        .put("id", d.id).put("statementId", d.statementId).put("channel", d.channel)
        .put("status", d.status).put("attempts", d.attempts)
        .put("lastError", d.lastError ?: JSONObject.NULL)
        .put("sentAt", d.sentAt ?: JSONObject.NULL)
        .put("scheduledFor", d.scheduledFor ?: JSONObject.NULL)
        .put("dedupKey", d.dedupKey)
        .put("lastAttemptAt", d.lastAttemptAt ?: JSONObject.NULL)

    private fun statementRuleJson(r: com.superbiz.app.data.db.StatementRuleEntity) = JSONObject()
        .put("id", r.id).put("name", r.name).put("enabled", r.enabled)
        .put("partyMode", r.partyMode).put("partyIdsJson", r.partyIdsJson)
        .put("frequency", r.frequency)
        .put("weekday", r.weekday ?: JSONObject.NULL)
        .put("dayOfMonth", r.dayOfMonth ?: JSONObject.NULL)
        .put("hour", r.hour).put("minute", r.minute)
        .put("periodPreset", r.periodPreset)
        .put("templateId", r.templateId ?: JSONObject.NULL)
        .put("signatureId", r.signatureId ?: JSONObject.NULL)
        .put("stampId", r.stampId ?: JSONObject.NULL)
        .put("channel", r.channel)
        .put("eventFlagsJson", r.eventFlagsJson ?: JSONObject.NULL)
        .put("threshold", r.threshold ?: JSONObject.NULL)
        .put("lastRunAt", r.lastRunAt ?: JSONObject.NULL)
        .put("nextRunAt", r.nextRunAt ?: JSONObject.NULL)

    private fun auditLogJson(a: com.superbiz.app.data.db.AuditLogEntity) = JSONObject()
        .put("id", a.id).put("actor", a.actor).put("action", a.action)
        .put("details", a.details).put("ts", a.ts)

    // [P46-W1] جولة 7 — تسلسل جدولا الولاء والكوبونات (القروش كما هي — ملف p8 دائماً)
    private fun loyaltyEntryJson(e: com.superbiz.app.data.db.LoyaltyEntryEntity) = JSONObject()
        .put("id", e.id).put("partyId", e.partyId)
        .put("invoiceId", e.invoiceId ?: 0L)
        .put("delta", e.delta).put("reason", e.reason)
        .put("note", e.note).put("createdAt", e.createdAt)

    private fun couponJson(c: com.superbiz.app.data.db.CouponEntity) = JSONObject()
        .put("id", c.id).put("code", c.code).put("kind", c.kind)
        // [تدقيق L-4] NaN/∞ يرمي JSONException في org.json فيفشل التصدير كله —
        // JSONObject.NULL يُقرأ optDouble بالافتراضي 0.0 بأمان
        .put("percent", if (c.percent.isNaN() || c.percent.isInfinite()) JSONObject.NULL else c.percent)
        .put("expiresAt", c.expiresAt).put("maxUses", c.maxUses)
        .put("usedCount", c.usedCount).put("active", c.active)
        .put("note", c.note).put("createdAt", c.createdAt)

    private fun productJson(p: Product) = JSONObject()
        .put("id", p.id).put("name", p.name).put("sku", p.sku).put("barcode", p.barcode)
        .put("unit", p.unit).put("costPrice", p.costPrice).put("salePrice", p.salePrice)
        .put("stockQty", p.stockQty).put("reorderLevel", p.reorderLevel).put("category", p.category)
        .put("archived", p.archived).put("createdAt", p.createdAt)
        // تاريخ إنشاء المنتج لم يكن يُصدَّر — كان يُكتب زمن الاستعادة (نمط partyJson)

    private fun invoiceJson(i: Invoice) = JSONObject()
        .put("id", i.id).put("number", i.number).put("partyId", i.partyId).put("type", i.type)
        .put("date", i.date).put("dueDate", i.dueDate).put("subtotal", i.subtotal)
        .put("discount", i.discount).put("taxRate", i.taxRate).put("taxAmount", i.taxAmount)
        .put("total", i.total).put("paid", i.paid).put("costTotal", i.costTotal)
        .put("status", i.status).put("currency", i.currency).put("fxRate", i.fxRate)
        .put("note", i.note)

    private fun checkJson(c: CheckEntity) = JSONObject()
        .put("id", c.id).put("number", c.number).put("partyId", c.partyId)
        .put("bank", c.bank).put("amount", c.amount).put("issueDate", c.issueDate)
        .put("dueDate", c.dueDate).put("direction", c.direction).put("status", c.status)
        .put("note", c.note)

    private suspend fun itemsArray(): JSONArray {
        val arr = JSONArray()
        // [P34-M1] كان N+1: استعلام forInvoice لكل فاتورة — الآن استعلام واحد allOnce
        // (يشمل بنود الفواتير الملغاة كما كانت التغطية) ثم تجميع بالذاكرة حسب الفاتورة
        val byInvoice = db.invoiceItems().allOnce().groupBy { it.invoiceId }
        for (inv in db.invoices().exportOnce()) {
            for (it in byInvoice[inv.id].orEmpty()) arr.put(JSONObject()
                .put("invoiceId", it.invoiceId).put("productId", it.productId)
                .put("desc", it.desc).put("qty", it.qty)
                .put("unitPrice", it.unitPrice).put("discount", it.discount)
                .put("taxKind", it.taxKind).put("taxRate", it.taxRate)) // [P41-L1] فئة/نسبة السطر v11
        }
        return arr
    }

    private suspend fun plansArray(): JSONArray {
        val arr = JSONArray()
        // تصدير الخطط المؤرشفة أيضاً
        for (p in db.installments().plansExport()) arr.put(JSONObject()
            .put("id", p.id).put("title", p.title).put("partyId", p.partyId)
            .put("direction", p.direction).put("total", p.total)
            .put("downPayment", p.downPayment).put("financed", p.financed)
            .put("months", p.months).put("startDate", p.startDate)
            .put("currency", p.currency).put("note", p.note).put("createdAt", p.createdAt)
            // علم الأرشفة لم يكن يُصدَّر — كل خطة مؤرشفة كانت تعود نشطة بعد الاستعادة
            .put("archived", p.archived))
        return arr
    }

    private suspend fun installmentsArray(): JSONArray {
        val arr = JSONArray()
        // [P34-M1] كان N+1: استعلام installmentsOf لكل خطة — الآن استعلام واحد
        // allInstallments (يشمل أقساط الخطط المؤرشفة كما كانت التغطية) بترتيبها الأصلي
        for (i in db.installments().allInstallments()) arr.put(JSONObject()
            .put("id", i.id).put("planId", i.planId).put("seq", i.seq)
            .put("amount", i.amount).put("dueDate", i.dueDate)
            .put("paidAmount", i.paidAmount).put("paidDate", i.paidDate)
            .put("status", i.status))
        return arr
    }

    private suspend fun paymentsArray(): JSONArray {
        val arr = JSONArray()
        // [P7-X1 إصلاح]: planId أُضيف إلى الحمولة — كان غائباً منذ M-4.9 () فتعود دفعات
        // الأقساط بعد الاستعادة بلا ربط بخططها (يتيمة: حذف الخطة لاحقاً يترك دفعاتها عبر SET NULL)
        for (p in db.payments().since(0)) arr.put(JSONObject()
            .put("id", p.id).put("partyId", p.partyId).put("invoiceId", p.invoiceId)
            .put("checkId", p.checkId).put("planId", p.planId ?: JSONObject.NULL)
            .put("amount", p.amount).put("date", p.date)
            .put("direction", p.direction).put("method", p.method).put("note", p.note))
        return arr
    }

    private suspend fun journalEntriesArray(): JSONArray {
        val arr = JSONArray()
        for (e in db.journal().allEntries()) arr.put(JSONObject()
            .put("id", e.id).put("date", e.date).put("memo", e.memo)
            .put("refType", e.refType).put("refId", e.refId))
        return arr
    }

    private suspend fun journalLinesArray(): JSONArray {
        val arr = JSONArray()
        for (l in db.journal().allLines()) arr.put(JSONObject()
            .put("entryId", l.entryId).put("account", l.account)
            .put("debit", l.debit).put("credit", l.credit)
            .put("partyId", l.partyId).put("currency", l.currency).put("fxRate", l.fxRate))
        return arr
    }

    private suspend fun expensesArray(): JSONArray {
        val arr = JSONArray()
        for (e in db.expenses().between(0, Long.MAX_VALUE)) arr.put(JSONObject()
            .put("id", e.id).put("amount", e.amount).put("category", e.category)
            .put("note", e.note).put("date", e.date).put("createdAt", e.createdAt))
        return arr
    }

    private suspend fun stockMovesArray(): JSONArray {
        val arr = JSONArray()
        for (m in db.stockMoves().allMoves()) arr.put(JSONObject()
            .put("id", m.id).put("productId", m.productId).put("qty", m.qty)
            .put("reason", m.reason).put("date", m.date)
            .put("refType", m.refType).put("refId", m.refId)
            .put("note", m.note))
        return arr
    }

    private fun ruleJson(r: Rule) = JSONObject()
        .put("kind", r.kind).put("enabled", r.enabled).put("daysBefore", r.daysBefore)
        .put("lastRun", r.lastRun)

    private fun currencyJson(c: Currency) = JSONObject()
        .put("code", c.code).put("nameAr", c.nameAr).put("nameEn", c.nameEn)
        .put("symbol", c.symbol).put("rateToBase", c.rateToBase).put("isBase", c.isBase)

    /** كتابة الملف إلى Uri عبر SAF */
    suspend fun exportTo(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        // كل تسلسل JSON + إدخال/إخراج الملف انتقل إلى Dispatcher.IO بدل خيط الاستدعاء (H-06)
        try {
            val json = exportJson()
            context.contentResolver.openOutputStream(uri, "wt")?.use { os ->
                os.write(json.toString(2).toByteArray(Charsets.UTF_8))
            } ?: return@withContext false
            true
        } catch (e: Exception) {
            false
        }
    }

    /** نسخة تلقائية داخل مسار التطبيق (يستخدمها العامل الأسبوعي) */
    suspend fun exportLocal(): File {
        val dir = File(context.filesDir, "backups").apply { mkdirs() }
        val name = "superbiz-auto-${System.currentTimeMillis()}.json"
        val f = File(dir, name)
        val json = exportJson()
        // [P20-FIX agent14]: كتابة ذرّية عبر tmp+rename — كانت writeText مباشرة في الملف النهائي،
        // وموت عملية/امتلاء قرص في المنتصف يترك نسخة مقطوعة تحتل خانة من الاحتفاظ وتبدو سليمة
        val tmp = File(dir, "$name.tmp")
        try {
            tmp.writeText(json.toString(2))
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) {
                tmp.delete()
                f.writeText(json.toString(2))
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        // احتفظ بآخر 5 نسخ فقط (أُسقط ملفات .tmp من العدّ)
        dir.listFiles()?.filter { it.name.endsWith(".json") }
            ?.sortedByDescending { it.name }?.drop(5)?.forEach { it.delete() }
        return f
    }

    /**
     * استعادة موثقة: فك + فحص كامل أولاً، ثم مسح وإعادة إدخال داخل معاملة واحدة
     *
     * ── [تدقيق M-7] سياسة صفوف اليتامى في الاستعادة الكاملة — العقد الموثق رسمياً ──
     * المساران التاريخيان (دمج P34 هنا ومسار BackupRestoreRepo) كانا يطبقان القاعدة
     * نفسها عملياً لكن بلا توثيق موحّد، فبدت الفروق عشوائية. العقد الصادق:
     *
     * ① النواة المالية — صارمة (فشل الاستعادة كلها بتراجع تام):
     *    فواتير، بنود فواتير، شيكات، خطط أقساط، أقساط، دفعات، قيود وسطور دفتر.
     *    صف يشير إلى أبٍ غائب عن الملف (فاتورة بلا طرف/بند بلا فاتورة/قيد بلا
     *    سطر مكتمل…) = ملف غير قابل للاستعادة. السبب: هذه الجداول تحقق التوازن
     *    المحاسبي واكتمال المستندات — تخطي صف واحد ينتج دفتراً غير متوازن
     *    أو مستنداً ناقصاً بصمت، وهذا أخطر من فشل صريح يعيد المستخدم للنسخة السابقة.
     *
     * ② السجلات المرافقة — متسامحة (تخطّي اليتيم وإكمال البقية):
     *    الزيارات (بلا FK — سجل GPS تاريخي)، الكشوف وتسليماتها (تخطّي متسلسل:
     *    كشف بطرف غائب يتخطى فتتخطى تسليماته)، دفتر الولاء (بطرف/فاتورة غائبة
     *    يتخطى — invoiceId قد يكون null أصلاً).
     *    السبب: فقدان سجل مرافق لا يمس الرصيد المحاسبي ولا اتزان القيود،
     *    وفشل الاستعادة كلها بسبب تاريخ عرض ظالم للمستخدم.
     *
     * ③ المستقلات بلا آباء — تُستعاد كما هي: قوالب/تواقيع/أختام/ملاحظات/قواعد
     *    كشف، كوبونات، عملات، قواعد تذكير، سجل تدقيق.
     *
     * أي تغيير مستقبلي على هذه السياسة يجب أن يحدّث هذه الفقرة واختبارات
     * الدوران الكامل (BackupRoundtrip/BackupCrossRoundtrip) معاً.
     */
    suspend fun importFrom(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        // كل قراءة الملف + فك JSON + عمليات القاعدة انتقلت إلى Dispatcher.IO بدل خيط الاستدعاء (H-06)
        try {
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                // [P20-FIX agent18]: قراءة بحدّ حجم متدفق (50MB مثل readTextCapped الشقيق) —
                // readBytes() غير المحدود على ملف خاطئ/مُبلَّغ عنه خطأً كان OOM Error يهرب من كل الـcatch
                val cap = 50L * 1024 * 1024
                val buf = StringBuilder(64 * 1024)
                val chunk = ByteArray(64 * 1024)
                var total = 0L
                var over = false
                while (true) {
                    val n = input.read(chunk)
                    if (n <= 0) break
                    total += n
                    if (total > cap) { over = true; break }
                    buf.append(String(chunk, 0, n, Charsets.UTF_8))
                }
                if (over) return@withContext false
                buf.toString()
            } ?: return@withContext false
            val root = JSONObject(text)
            if (root.optString("app") != "SuperBiz") return@withContext false
            // فرض إصدار الصيغة — نرفض الملفات بلا إصدار أو من إصدار أحدث من التطبيق
            val format = root.optInt("format", 0)
            if (format < 1 || format > FORMAT_VERSION) return@withContext false
            // [P33-P8] توافق النسخ الاحتياطي: الصيغ 1/2 كانت تخزن المبالغ ريالاً Double
            // وصيغة 3 تخزنها قروش Long — كل قراءة مبلغ تمر عبر moneyField
            val legacyRiyal = format < FORMAT_VERSION

            // ── 1) فك وفحص كل الصفوف في الذاكرة قبل لمس قاعدة البيانات ──
            val parties = ArrayList<Party>()
            root.optJSONArray("parties")?.each { j ->
                parties += Party(
                    id = j.getLong("id"), name = j.getString("name"),
                    phone = j.optString("phone"), type = j.optInt("type"),
                    note = j.optString("note"), createdAt = j.optLong("createdAt"),
                    archived = j.optBoolean("archived"),
                    // [P20-FIX agent18]: نفس حقول partyJson المضافة — كانت تُفقد بعد الاستعادة
                    favorite = j.optBoolean("favorite"),
                    lat = if (j.isNull("lat")) null else j.optDouble("lat"),
                    lng = if (j.isNull("lng")) null else j.optDouble("lng"))
            }
            val products = ArrayList<Product>()
            root.optJSONArray("products")?.each { j ->
                products += Product(
                    id = j.getLong("id"), name = j.getString("name"),
                    sku = j.optString("sku"), barcode = j.optString("barcode"),
                    unit = j.optString("unit", "قطعة"),
                    // [P33-P8] قروش — النسخ القديمة (≤2) ريال Double تُحوَّل via moneyField
                    costPrice = moneyField(j, "costPrice", legacyRiyal),
                    salePrice = moneyField(j, "salePrice", legacyRiyal),
                    stockQty = j.optDouble("stockQty"), reorderLevel = j.optDouble("reorderLevel"),
                    category = j.optString("category"), archived = j.optBoolean("archived"),
                    createdAt = j.optLong("createdAt"))
            }
            val invoices = ArrayList<Invoice>()
            root.optJSONArray("invoices")?.each { j ->
                invoices += Invoice(
                    id = j.getLong("id"), number = j.getString("number"),
                    partyId = j.getLong("partyId"), type = j.optInt("type"),
                    date = j.getLong("date"), dueDate = j.getLong("dueDate"),
                    // [P33-P8] المبالغ قروش — taxRate/fxRate نسب تبقى Double
                    subtotal = moneyField(j, "subtotal", legacyRiyal, required = true),
                    discount = moneyField(j, "discount", legacyRiyal),
                    taxRate = j.optDouble("taxRate"),
                    taxAmount = moneyField(j, "taxAmount", legacyRiyal),
                    total = moneyField(j, "total", legacyRiyal, required = true),
                    paid = moneyField(j, "paid", legacyRiyal),
                    costTotal = moneyField(j, "costTotal", legacyRiyal),
                    status = j.optInt("status"),
                    currency = j.optString("currency", "SAR"), fxRate = j.optDouble("fxRate", 1.0),
                    note = j.optString("note"))
            }
            val items = ArrayList<InvoiceItem>()
            root.optJSONArray("invoice_items")?.each { j ->
                items += InvoiceItem(
                    invoiceId = j.getLong("invoiceId"),
                    productId = if (j.isNull("productId")) null else j.optLong("productId"),
                    desc = j.getString("desc"), qty = j.getDouble("qty"),
                    // [P33-P8] السعر والخصم قروش — qty كمية تبقى Double
                    unitPrice = moneyField(j, "unitPrice", legacyRiyal, required = true),
                    discount = moneyField(j, "discount", legacyRiyal),
                    // [P41-L1] ملفات ما قبل v11 بلا الحقلين ⇒ البذرتان المحايدتان
                    taxKind = j.optInt("taxKind", 0),
                    taxRate = j.optDouble("taxRate", -1.0))
            }
            val checks = ArrayList<CheckEntity>()
            root.optJSONArray("checks")?.each { j ->
                checks += CheckEntity(
                    id = j.getLong("id"), number = j.getString("number"),
                    partyId = j.getLong("partyId"), bank = j.optString("bank"),
                    amount = moneyField(j, "amount", legacyRiyal, required = true),
                    issueDate = j.getLong("issueDate"),
                    dueDate = j.getLong("dueDate"), direction = j.optInt("direction"),
                    status = j.optInt("status"), note = j.optString("note"))
            }
            val plans = ArrayList<com.superbiz.app.data.db.InstallmentPlan>()
            root.optJSONArray("installment_plans")?.each { j ->
                plans += com.superbiz.app.data.db.InstallmentPlan(
                    id = j.getLong("id"), title = j.getString("title"),
                    partyId = j.getLong("partyId"), direction = j.optInt("direction"),
                    total = moneyField(j, "total", legacyRiyal, required = true),
                    downPayment = moneyField(j, "downPayment", legacyRiyal),
                    financed = moneyField(j, "financed", legacyRiyal, required = true),
                    months = j.getInt("months"),
                    startDate = j.getLong("startDate"),
                    currency = j.optString("currency", "SAR"), note = j.optString("note"),
                    createdAt = j.optLong("createdAt"),
                    // العلم المؤرشف لم يُقرأ في الاستيراد — كان يستعيد
                    // كل خطة مؤرشفة كنشطة وتعود لقائمة الخطوات والتذكيرات
                    archived = j.optBoolean("archived", false))
            }
            val installments = ArrayList<com.superbiz.app.data.db.Installment>()
            root.optJSONArray("installments")?.each { j ->
                installments += com.superbiz.app.data.db.Installment(
                    id = j.getLong("id"), planId = j.getLong("planId"), seq = j.getInt("seq"),
                    amount = moneyField(j, "amount", legacyRiyal, required = true),
                    dueDate = j.getLong("dueDate"),
                    paidAmount = moneyField(j, "paidAmount", legacyRiyal),
                    paidDate = if (j.isNull("paidDate")) null else j.optLong("paidDate"),
                    status = j.optInt("status"))
            }
            val payments = ArrayList<Payment>()
            root.optJSONArray("payments")?.each { j ->
                payments += Payment(
                    id = j.optLong("id", 0),
                    partyId = if (j.isNull("partyId")) null else j.optLong("partyId"),
                    invoiceId = if (j.isNull("invoiceId")) null else j.optLong("invoiceId"),
                    checkId = if (j.isNull("checkId")) null else j.optLong("checkId"),
                    // [P7-X1 إصلاح]: قراءة planId — النسخ القديمة بلا المفتاح تعود null بأمان
                    planId = if (j.isNull("planId")) null else j.optLong("planId"),
                    amount = moneyField(j, "amount", legacyRiyal, required = true),
                    date = j.getLong("date"),
                    direction = j.optInt("direction"), method = j.optString("method", "CASH"),
                    note = j.optString("note"))
            }
            val jEntries = ArrayList<JournalEntry>()
            // [P37-TD]: المفتاح القياسي "journal" (عقد BackupTables) مع قبول القديم
            // "journal_entries" للنسخ المحفوظة قبل المحاذاة
            (root.optJSONArray(BackupTables.JOURNAL) ?: root.optJSONArray("journal_entries"))?.each { j ->
                jEntries += JournalEntry(
                    id = j.getLong("id"), date = j.getLong("date"), memo = j.getString("memo"),
                    refType = if (j.isNull("refType")) null else j.optString("refType"),
                    refId = if (j.isNull("refId")) null else j.optLong("refId"))
            }
            val jLines = ArrayList<JournalLine>()
            root.optJSONArray("journal_lines")?.each { j ->
                jLines += JournalLine(
                    id = j.optLong("id", 0),
                    entryId = j.getLong("entryId"), account = j.getString("account"),
                    // [P33-P8] قروش — fxRate نسبة تبقى Double
                    debit = moneyField(j, "debit", legacyRiyal, required = true),
                    credit = moneyField(j, "credit", legacyRiyal, required = true),
                    partyId = if (j.isNull("partyId")) null else j.optLong("partyId"),
                    currency = j.optString("currency", "SAR"), fxRate = j.optDouble("fxRate", 1.0))
            }
            val rules = ArrayList<Rule>()
            root.optJSONArray("rules")?.each { j ->
                rules += Rule(
                    kind = j.getString("kind"), enabled = j.optBoolean("enabled", true),
                    daysBefore = j.optInt("daysBefore", 3), lastRun = j.optLong("lastRun"))
            }
            val currencies = ArrayList<Currency>()
            root.optJSONArray("currencies")?.each { j ->
                currencies += Currency(
                    code = j.getString("code"), nameAr = j.getString("nameAr"),
                    nameEn = j.getString("nameEn"), symbol = j.getString("symbol"),
                    rateToBase = j.optDouble("rateToBase", 1.0),
                    isBase = j.optBoolean("isBase"))
            }
            val expenses = ArrayList<Expense>()
            root.optJSONArray("expenses")?.each { j ->
                expenses += Expense(
                    id = j.optLong("id", 0), amount = moneyField(j, "amount", legacyRiyal, required = true),
                    category = j.optString("category"), note = j.optString("note"),
                    date = j.getLong("date"), createdAt = j.optLong("createdAt"))
            }
            val moves = ArrayList<StockMove>()
            root.optJSONArray("stock_moves")?.each { j ->
                moves += StockMove(
                    id = j.optLong("id", 0),
                    productId = j.getLong("productId"), qty = j.getDouble("qty"),
                    reason = j.optString("reason"), date = j.getLong("date"),
                    refType = if (j.isNull("refType")) null else j.optString("refType"),
                    refId = if (j.isNull("refId")) null else j.optLong("refId"),
                    note = j.optString("note"))
            }
            // [P20-FIX agent18]: استعادة الزيارات — كانت تُمسح مع wipeAll ولا تعود أبداً
            val visits = ArrayList<com.superbiz.app.data.db.Visit>()
            root.optJSONArray("visits")?.each { j ->
                visits += com.superbiz.app.data.db.Visit(
                    id = j.optLong("id", 0), partyId = j.getLong("partyId"),
                    visitedAt = j.optLong("visitedAt"),
                    lat = if (j.isNull("lat")) null else j.optDouble("lat"),
                    lng = if (j.isNull("lng")) null else j.optDouble("lng"),
                    note = j.optString("note"))
            }
            // فلترة أيتام: زيارة لطرف غير موجود في الملف تُسقط (لا FK على الجدول فلا يحميها شيء)
            val partyIds = parties.map { it.id }.toSet()
            val validVisits = visits.filter { it.partyId in partyIds }
            // ── [P36-BK] جداول الكشوف الثمانية — قارئات بفحص أنواع صريح (عقد BackupKit نفسه) ──
            // المفاتيح غائبة في النسخ القديمة (≤ ) ⇒ optJSONArray تعيد null ⇒ قوائم فارغة،
            // وجداول الكشوف أُضيفت في عهد الصيغة 3 (قروش) فلا تحويل legacyRiyal على threshold.
            val stTemplates = ArrayList<com.superbiz.app.data.db.StatementTemplateEntity>()
            root.optJSONArray("statement_templates")?.each { j ->
                stTemplates += com.superbiz.app.data.db.StatementTemplateEntity(
                    id = j.getLong("id"), name = j.getString("name"),
                    baseTemplateId = j.getString("baseTemplateId"), configJson = j.getString("configJson"),
                    isDefault = j.optBoolean("isDefault"), favorite = j.optBoolean("favorite"),
                    createdAt = j.optLong("createdAt"), updatedAt = j.optLong("updatedAt"))
            }
            val stSignatures = ArrayList<com.superbiz.app.data.db.SignatureEntity>()
            root.optJSONArray("signatures")?.each { j ->
                stSignatures += com.superbiz.app.data.db.SignatureEntity(
                    id = j.getLong("id"), name = j.getString("name"),
                    jobTitle = if (j.isNull("jobTitle")) null else j.optString("jobTitle"),
                    imagePath = j.getString("imagePath"),
                    isDefault = j.optBoolean("isDefault"), active = j.optBoolean("active", true),
                    createdAt = j.optLong("createdAt"))
            }
            val stStamps = ArrayList<com.superbiz.app.data.db.StampEntity>()
            root.optJSONArray("stamps")?.each { j ->
                stStamps += com.superbiz.app.data.db.StampEntity(
                    id = j.getLong("id"), name = j.getString("name"),
                    imagePath = j.getString("imagePath"),
                    isDefault = j.optBoolean("isDefault"), active = j.optBoolean("active", true),
                    createdAt = j.optLong("createdAt"))
            }
            val stNoteTemplates = ArrayList<com.superbiz.app.data.db.NoteTemplateEntity>()
            root.optJSONArray("note_templates")?.each { j ->
                stNoteTemplates += com.superbiz.app.data.db.NoteTemplateEntity(
                    id = j.getLong("id"), title = j.getString("title"),
                    body = j.getString("body"), isDefault = j.optBoolean("isDefault"))
            }
            val stStatements = ArrayList<com.superbiz.app.data.db.StatementEntity>()
            root.optJSONArray("statements")?.each { j ->
                stStatements += com.superbiz.app.data.db.StatementEntity(
                    id = j.getLong("id"), statementNumber = j.getString("statementNumber"),
                    verificationId = j.getString("verificationId"), partyId = j.getLong("partyId"),
                    fromTs = j.getLong("fromTs"), toTs = j.getLong("toTs"),
                    templateId = j.getString("templateId"), currency = j.getString("currency"),
                    contentHash = j.getString("contentHash"), filePath = j.getString("filePath"),
                    note = if (j.isNull("note")) null else j.optString("note"),
                    createdAt = j.optLong("createdAt"), lang = j.getString("lang"))
            }
            val stDeliveries = ArrayList<com.superbiz.app.data.db.StatementDeliveryEntity>()
            root.optJSONArray("statement_deliveries")?.each { j ->
                stDeliveries += com.superbiz.app.data.db.StatementDeliveryEntity(
                    id = j.getLong("id"), statementId = j.getLong("statementId"),
                    channel = j.getString("channel"), status = j.getString("status"),
                    attempts = j.optInt("attempts"),
                    lastError = if (j.isNull("lastError")) null else j.optString("lastError"),
                    sentAt = if (j.isNull("sentAt")) null else j.optLong("sentAt"),
                    scheduledFor = if (j.isNull("scheduledFor")) null else j.optLong("scheduledFor"),
                    dedupKey = j.getString("dedupKey"),
                    lastAttemptAt = if (j.isNull("lastAttemptAt")) null else j.optLong("lastAttemptAt"))
            }
            val stRules = ArrayList<com.superbiz.app.data.db.StatementRuleEntity>()
            root.optJSONArray("statement_rules")?.each { j ->
                stRules += com.superbiz.app.data.db.StatementRuleEntity(
                    id = j.getLong("id"), name = j.getString("name"),
                    enabled = j.optBoolean("enabled", true),
                    partyMode = j.getString("partyMode"), partyIdsJson = j.optString("partyIdsJson"),
                    frequency = j.getString("frequency"),
                    weekday = if (j.isNull("weekday")) null else j.optInt("weekday"),
                    dayOfMonth = if (j.isNull("dayOfMonth")) null else j.optInt("dayOfMonth"),
                    hour = j.getInt("hour"), minute = j.getInt("minute"),
                    periodPreset = j.getString("periodPreset"),
                    templateId = if (j.isNull("templateId")) null else j.optString("templateId"),
                    signatureId = if (j.isNull("signatureId")) null else j.optLong("signatureId"),
                    stampId = if (j.isNull("stampId")) null else j.optLong("stampId"),
                    channel = j.getString("channel"),
                    eventFlagsJson = if (j.isNull("eventFlagsJson")) null else j.optString("eventFlagsJson"),
                    threshold = if (j.isNull("threshold")) null else j.optLong("threshold"),
                    lastRunAt = if (j.isNull("lastRunAt")) null else j.optLong("lastRunAt"),
                    nextRunAt = if (j.isNull("nextRunAt")) null else j.optLong("nextRunAt"))
            }
            val stAudit = ArrayList<com.superbiz.app.data.db.AuditLogEntity>()
            root.optJSONArray("audit_log")?.each { j ->
                stAudit += com.superbiz.app.data.db.AuditLogEntity(
                    id = j.getLong("id"), actor = j.getString("actor"),
                    action = j.getString("action"), details = j.getString("details"),
                    ts = j.optLong("ts"))
            }
            // [P46-W1] جولة 7 — قراءة جدولا الولاء والكوبونات (متسامحة: غياب المفتاح = قائمة فارغة
            // — ملفات ما قبل تُستورد بدلالتها بلا مساس)
            val stLoyalty = ArrayList<com.superbiz.app.data.db.LoyaltyEntryEntity>()
            root.optJSONArray("loyalty_entries")?.each { j ->
                stLoyalty += com.superbiz.app.data.db.LoyaltyEntryEntity(
                    id = j.getLong("id"), partyId = j.getLong("partyId"),
                    invoiceId = j.optLong("invoiceId", 0L).takeIf { it > 0L },
                    delta = j.getLong("delta"), reason = j.optString("reason", "MANUAL_GRANT"),
                    note = j.optString("note", ""), createdAt = j.optLong("createdAt"))
            }
            val stCoupons = ArrayList<com.superbiz.app.data.db.CouponEntity>()
            root.optJSONArray("coupons")?.each { j ->
                stCoupons += com.superbiz.app.data.db.CouponEntity(
                    id = j.getLong("id"), code = j.getString("code"),
                    kind = j.optInt("kind", 0),
                    amountPiasters = j.optLong("amountPiasters", 0L),
                    percent = j.optDouble("percent", 0.0),
                    expiresAt = j.optLong("expiresAt", 0L),
                    maxUses = j.optInt("maxUses", 0),
                    usedCount = j.optInt("usedCount", 0),
                    active = j.optBoolean("active", true),
                    note = j.optString("note", ""), createdAt = j.optLong("createdAt"))
            }
            // قاعدة اليتيم البنيوية (P34 نفسها دفاعياً): كشف بطرف غائب عن الملف يتخطى،
            // وتسليم بكشف غائب يتخطى بالتسلسل — ملف سليم لا تُمسّ صفوفه إطلاقاً
            val validStStatements = stStatements.filter { it.partyId in partyIds }
            val stStatementIds = validStStatements.map { it.id }.toSet()
            val validStDeliveries = stDeliveries.filter { it.statementId in stStatementIds }
            val st = root.optJSONObject("settings")

            // رفض الملفات الفارغة المحتوى — كانت تمسح قاعدة البيانات كاملة ثم تعيد true
            if (parties.isEmpty() && products.isEmpty() && invoices.isEmpty()) return@withContext false

            // ── 2) مسح وإعادة إدخال داخل معاملة واحدة — ذرّي بالكامل ──
            db.withTransaction {
                db.maintenance().wipeAll()
                // إصلاح خطأ ترجمة: upsert المفردة لا تقبل قائمة — الصواب upsertAll
                db.parties().upsertAll(parties)
                db.products().upsertAll(products)
                // استعادة حركات المخزون — كانت تُمسح مع wipeAll ولا تعود أبداً
                moves.forEach { db.stockMoves().insert(it) }
                invoices.forEach { db.invoices().upsert(it) }
                if (items.isNotEmpty()) db.invoiceItems().insertAll(items)
                checks.forEach { db.checks().upsert(it) }
                plans.forEach { db.installments().insertPlan(it) }
                if (installments.isNotEmpty()) db.installments().insertInstallments(installments)
                payments.forEach { db.payments().insert(it) }
                jEntries.forEach { db.journal().insertEntry(it) }
                if (jLines.isNotEmpty()) db.journal().insertLines(jLines)
                rules.forEach { db.rules().upsert(it) }
                if (currencies.isNotEmpty()) db.currencies().upsertAll(currencies)
                expenses.forEach { db.expenses().insert(it) }
                // [P20-FIX agent18]: إعادة إدخال الزيارات بعد الأطراف (بلا FK — ترتيبًا منطقياً فقط)
                validVisits.forEach { db.visits().insert(it) }
                // ── [P36-BK] إعادة إدخال الكشوف الثمانية — الترتيب إلزامي (نمط P34):
                // المستقلات أولاً ثم الكشوف (الأطراف مُدخلة أعلاه فالRESTRICT راضٍ)
                // ثم التسليمات (CASCADE باتجاه الكشوف) ثم القواعد ثم سجل التدقيق
                stTemplates.forEach { db.statementTemplates().upsert(it) }
                stSignatures.forEach { db.signatures().upsert(it) }
                stStamps.forEach { db.stamps().upsert(it) }
                stNoteTemplates.forEach { db.noteTemplates().upsert(it) }
                validStStatements.forEach { db.statements().insert(it) }
                validStDeliveries.forEach { db.statements().insertDelivery(it) }
                stRules.forEach { db.statementRules().upsert(it) }
                stAudit.forEach { db.auditLog().insert(it) }
                // ── [P46-W1] إعادة إدخال الولاء والكوبونات — الترتيب إلزامي:
                // الكوبونات مستقلة، ودفتر الولاء بعد الأطراف والفواتير (المُدخلة أعلاه)
                // — قاعدة اليتيم الدفاعية نفسها: صف بطرف/فاتورة غائب عن الملف يتخطى
                stCoupons.forEach { db.coupons().upsert(it) }
                val fileInvoiceIds = invoices.map { it.id }.toSet()
                val validStLoyalty = stLoyalty.filter {
                    it.partyId in partyIds && (it.invoiceId == null || it.invoiceId in fileInvoiceIds)
                }
                validStLoyalty.forEach { db.loyalty().insert(it) }
            }

            // ── 3) الإعدادات (خارج المعاملة — DataStore مستقل) ──
            // best-effort مستقل بـ try/catch — فشل الإعدادات بعد استبدال القاعدة
            // لا يُرجع false كأن الاستعادة كلها فشلت، ولا يكسر النتيجة الناجحة
            try {
                st?.let {
                    // استعادة كل المفاتيح التي يعرضها SettingsRepo عند وجودها في الملف (كانت 5 فقط)
                    runCatching { if (it.has("businessName")) settings.setBusinessName(it.getString("businessName")) }
                    // [P7-X2 إصلاح]: avatarPath/backupDirUri من النسخة تُتجاهل بأمان — قيمة
                    // الجهاز المحلي تبقى كما هي (النسخ القديمة تحمل المفتاحين بلا أثر)
                    // [P20-FIX agent18]: قائمة بيضاء ar/en — نفس عقد SettingsCodec؛ ملف فاسد/محرّر
                    // كان يزرع لغة غير معروفة تفشل عند التطبيق
                    runCatching { if (it.has("language") && it.getString("language") in setOf("ar", "en")) settings.setLanguage(it.getString("language")) }
                    runCatching { if (it.has("theme")) settings.setTheme(it.getString("theme")) }
                    if (it.has("baseCurrency") && it.getString("baseCurrency").isNotBlank())
                        settings.setBaseCurrency(it.getString("baseCurrency"))
                    runCatching { if (it.has("taxRate")) settings.setTaxRate(it.getDouble("taxRate")) }
                    // مفاتيح الرمز (pin*) لم تعد تُستعاد — الرمز يُعاد تعيينه محلياً
                    // بعد الاستعادة، ومحاولة استيراد مادة تحقق قديمة كانت تفسد الحماية المحلية
                    // [P20-FIX agent18]: أختام الأوقات (lastAutoBackup/lastScheduledReport) حالة جهاز
                    // حسب عقد SettingsCodec — كانت تُستعاد فتُكبِح أول دورة نسخ/تقرير على الجهاز الجديد
                    // حتّى انقضاء فترة الجهاز المصدر
                    runCatching { if (it.has("walkInPartyId")) settings.setWalkInPartyId(it.getLong("walkInPartyId")) }
                    if (it.optBoolean("seeded")) settings.setSeeded()
                    runCatching { if (it.has("ownerName")) settings.setOwnerName(it.getString("ownerName")) }
                    runCatching { if (it.has("phone")) settings.setPhone(it.getString("phone")) }
                    runCatching { if (it.has("email")) settings.setEmail(it.getString("email")) }
                    runCatching { if (it.has("address")) settings.setAddress(it.getString("address")) }
                    runCatching { if (it.has("taxNumber")) settings.setTaxNumber(it.getString("taxNumber")) }
                    // [P5-H2 إصلاح]: لا يُستعاد علم biometric إطلاقاً — استيراده على جهاز جديد بلا
                    // pin* ينتج «بصمة بلا رمز»: زر «متابعة (طارئ)» في LockScreen يفتح التطبيق بنقرة،
                    // والبصمة تجتاز ببصمة مالك الجهاز الجديد لا المالك الأصلي. توحيداً مع سياسة
                    // SettingsCodec (المفحوصة في SettingsSecurityP4Test: unknown_key:biometric)
                    // ومع سابقة R12-C11 لمفاتيح pin* — إعدادات الأمان تنشأ محلياً فقط.
                    if (it.optBoolean("welcomeSeen")) settings.setWelcomeSeen()
                    if (it.optBoolean("permissionsSeen")) settings.setPermissionsSeen()
                    runCatching { if (it.has("autoBackupDays")) settings.setAutoBackupDays(it.getInt("autoBackupDays")) }
                    runCatching { if (it.has("reportScheduleDays")) settings.setReportScheduleDays(it.getInt("reportScheduleDays")) }
                    runCatching { if (it.has("reportScheduleHour")) settings.setReportScheduleHour(it.getInt("reportScheduleHour")) }
                    runCatching { if (it.has("reportScheduleChannel")) settings.setReportScheduleChannel(it.getString("reportScheduleChannel")) }
                    runCatching { if (it.has("reportRecipient")) settings.setReportRecipient(it.getString("reportRecipient")) }
                    runCatching { if (it.has("monthlyGoal")) settings.setMonthlyGoal(it.getDouble("monthlyGoal")) }
                    runCatching { if (it.has("redactWidgets")) settings.setRedactWidgets(it.getBoolean("redactWidgets")) }
                    // استعادة المفاتيح الجديدة عند وجودها (نسخ قديمة بلاها تُترك على الافتراضات)
                    runCatching { if (it.has("hapticsEnabled")) settings.setHapticsEnabled(it.getBoolean("hapticsEnabled")) }
                    runCatching { if (it.has("confirmDestructive")) settings.setConfirmDestructive(it.getBoolean("confirmDestructive")) }
                    runCatching { if (it.has("flagSecure")) settings.setFlagSecure(it.getBoolean("flagSecure")) }
                    runCatching { if (it.has("lockTimeoutMin")) settings.setLockTimeoutMin(it.getInt("lockTimeoutMin")) }
                    runCatching { if (it.has("fontScale")) settings.setFontScale(it.getDouble("fontScale").toFloat()) }
                    runCatching { if (it.has("dynamicColors")) settings.setDynamicColors(it.getBoolean("dynamicColors")) }
                    runCatching { if (it.has("mirrorChartsRtl")) settings.setMirrorChartsRtl(it.getBoolean("mirrorChartsRtl")) }
                    runCatching { if (it.has("animationsEnabled")) settings.setAnimationsEnabled(it.getBoolean("animationsEnabled")) }
                    runCatching { if (it.has("arabicReceiptMode")) settings.setArabicReceiptMode(it.getInt("arabicReceiptMode")) }
                    runCatching { if (it.has("defaultLowStockQty")) settings.setDefaultLowStockQty(it.getInt("defaultLowStockQty")) }
                    runCatching { if (it.has("lowStockAlerts")) settings.setLowStockAlerts(it.getBoolean("lowStockAlerts")) }
                    runCatching { if (it.has("receivableAlerts")) settings.setReceivableAlerts(it.getBoolean("receivableAlerts")) }
                    // استعادة المفاتيح الثمانية المهمَلة سابقاً
                    runCatching { if (it.has("defaultTargetMargin")) settings.setDefaultTargetMargin(it.getDouble("defaultTargetMargin")) }
                    runCatching { if (it.has("lateFeeDailyPct")) settings.setLateFeeDailyPct(it.getDouble("lateFeeDailyPct")) }
                    runCatching { if (it.has("lateFeeCapPct")) settings.setLateFeeCapPct(it.getDouble("lateFeeCapPct")) }
                    runCatching { if (it.has("weekendFriSat")) settings.setWeekendFriSat(it.getBoolean("weekendFriSat")) }
                    runCatching { if (it.has("searchFuzzyThreshold")) settings.setSearchFuzzyThreshold(it.getDouble("searchFuzzyThreshold")) }
                    runCatching { if (it.has("eoqOrderCost")) settings.setEoqOrderCost(it.getDouble("eoqOrderCost")) }
                    runCatching { if (it.has("expenseMonthlyLimit")) settings.setExpenseMonthlyLimit(it.getDouble("expenseMonthlyLimit")) }
                    runCatching { if (it.has("privacyBlur")) settings.setPrivacyBlur(it.getBoolean("privacyBlur")) }
                }
            } catch (e: Exception) {
                // نتجاهل فشل الإعدادات فقط — القاعدة نفسها استُعيدت داخل المعاملة بنجاح
            }

            // استعادة جدولة النسخ والتقرير — كانت autoBackupDays و
            // reportScheduleDays تُستعاد كقيم دون إعادة جدولة العاملين، فبقيت دورة
            // الجهاز القديمة تعمل (أو لا شيء) حتى الإقلاع التالي رغم تغيّر الإعداد
            try {
                val restored = settings.snapshot()
                com.superbiz.app.work.BackupWorker.schedule(context, restored.autoBackupDays)
                com.superbiz.app.work.ReportScheduleWorker.schedule(
                    context, restored.reportScheduleDays, restored.reportScheduleHour
                )
            } catch (e: Exception) {
                // الجدولة مجهود أمثل — لا يؤثر على نجاح الاستعادة
            }

            // ── 4) أرشيف الشيكات (DataStore مستقل — /R12-C19) ──
            // استبدال الأرشيف بما في الملف بعد حصره بمعرّفات الشيكات المستعادة فعلاً؛
            // المعرّفات المجهولة تُهمل. best-effort مستقل لا يفسد نجاح الاستعادة.
            try {
                val importedCheckIds = checks.map { it.id }.toSet()
                val archiveIds = root.optJSONArray("checks_archive")?.let { arr ->
                    (0 until arr.length()).mapNotNull { k ->
                        val v = arr.optLong(k, -1L)
                        if (v >= 0) v else null
                    }
                } ?: emptyList()
                ChecksArchiveStore(context).replaceAll(archiveIds.filter { it in importedCheckIds })
            } catch (e: Exception) {
                // نتجاهل فشل الأرشيف فقط — قاعدة البيانات نفسها استُعيدت بنجاح
            }

            // (M-5.6 توحيد): إعادة البذر عبر SeedDefaults الموحّد (مصدر واحد للحقيقة) —
            // كانت نسخة مضمنة مكررة هنا تسبق استخلاص line التدقيق
            try {
                com.superbiz.app.data.repo.SeedDefaults.ensure(db)
            } catch (e: Exception) {
                // البذر مجهود أمثل — لا يؤثر على نجاح الاستعادة
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
