package com.superbiz.app.data.repo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.superbiz.app.R
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.domain.backup.BACKUP_VERSION
import com.superbiz.app.domain.backup.BackupData
import com.superbiz.app.domain.backup.BackupFormatException
import com.superbiz.app.domain.backup.ImportStats
import com.superbiz.app.domain.backup.buildBackupJson
import com.superbiz.app.domain.backup.parseBackup
import com.superbiz.app.domain.backup.planImport
import com.superbiz.app.domain.backup.totalRows
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [P13-b] منفّذ النسخ الاحتياطي/الاستعادة v1 (طبقة الإدخال/الإخراج عبر SAF)
 * [P14-b] — الاستعادة الآن أربعة جداول تُدمج (الأطراف + المنتجات + الزيارات + المصروفات).
 * [P15-b] — الاستعادة الآن سبعة جداول (السابقة + الشيكات + خطط الأقساط + الأقساط).
 *
 * • تصدير: جمع كل الجداول (15) في BackupData → buildBackupJson → كتابة نصية UTF-8
 * إلى Uri المستخدم اختاره (CreateDocument) مع flush وإغلاق — النتيجة عدد السجلات الكلي.
 * • استيراد: قراءة النص بحجم محدود (50MB كحد أقصى) → parseBackup → داخل معاملة واحدة
 * يُعاد فحص المعرّفات الموجودة (حماية من تغيّر القاعدة بين الخطة والتنفيذ) ثم تُدرَج
 * الصفوف الجديدة فقط في الجداول السبعة.
 *
 * ملاحظة معمارية صادقة: هذا الصنف مستقل تماماً عن [BackupRepo] القديم ( — النسخ
 * التلقائي المجدول) لأن AppGraph في SuperBizApp.kt يربط `graph.backup` بمنشئه
 * (context, db, settings) وهو ملف ممنوع تعديله في هذه الموجة — فجاء المنفّذ الجديد
 * باسم مختلف في ملف مستقل حتى لا ينكسر أي مسار قائم.
 *
 * دلالات الدمج (الأهم)
 * • PartyDao/ProductDao.upsertAll هي @Upsert أي «حدّث أو أدرج». لتخطي ما هو موجود فعلاً
 * — فلا نسمح بإعادة كتابة بيانات المستخدم — تُفلتر القوائم قبل الإدراج إلى المعرّفات
 * الغائبة فقط، فيعمل upsertAll إدراجاً خالصاً ولا يلمس صفوفاً قائمة.
 * • [P14-b] الزيارات/المصروفات: VisitDao.insert وExpenseDao.insert هما @Insert بلا
 * onConflict (ABORT افتراضياً) — إدراج id موجود أو مكرر داخل الملف كان سيرفع
 * SQLiteConstraintException ويتراجع بالمعاملة كلها. لهذا تُفلتر القائمتان إلى
 * المعرّفات الجديدة غير الموجودة ثم distinctBy (الأول يفوز — مطابق تماماً لدلالة
 * planImport التي تعدّ المكرر متخطى)، ويُدرج صفّاً صفّاً داخل المعاملة (مئات الصفوف
 * مقبولة داخل معاملة Room). وبدلالة distinctBy نفسها صار تنفيذ الجداول الأربعة
 * مطابقاً للخطة حرفياً — حتى الأطراف/المنتجات (الأول يفوز في التكرار داخل الملف).
 * • [P15-b] الشيكات/الخطط/الأقساط — تدقيق Entities.kt بيّن قيود مفاتيح أجنبية حقيقية
 * (تصحيح لافتراض الموجة): checks→parties (RESTRICT) وinstallment_plans→parties
 * (RESTRICT) وinstallments→installment_plans (CASCADE). تبعات ذلك
 * ١. ترتيب التنفيذ إلزامي: parties (الموجود أصلاً أولاً) ثم plans ثم installments
 * ثم checks — الأب يسبق الابن داخل المعاملة نفسها (القيد يُفحص فورياً بوجود
 * foreign_keys=ON في Room).
 * ٢. قاعدة اليتيم: صف يشير إلى أبٍ غير موجود (لا في القاعدة ولا ضمن المستورد فعلاً)
 * يُتخطى — مطابقاً لعدّاد الخطة (planImport يحسب «المستورد فعلاً» بالقواعد نفسها)،
 * وإلا كان إدراجه سيرفع استثناء القيد ويتراجع بالاستعادة كلها.
 * ٣. التصفية هنا تعيد حساب مجموعتي «المستورد فعلاً» (الأطراف/الخطط) بدل توسيع
 * ImportStats بحقول تشغيلية — أبسط وأصح: نفس مرشّح الخطة حرفياً (id>0 + غير موجود
 * + غير مكرر داخل الملف بأسلوب «الأول يفوز» عبر seen-set بدل distinctBy لأن قاعدة
 * اليتيم تجعل الترتيب مهماً — راجع test planImport_v3). الخطة = التنفيذ بلا انحراف.
 * • الزيارات والمصروفات «جدولا أرشيف معزولان» بلا مفاتيح أجنبية ولا دور في الأرصدة
 * — انظر رأس BackupKit لقرار الأمان الكامل، و[P15-b] لسبب انضمام الشيكات/الخطط.
*/

/** [P13-b] منفّذ التصدير/الاستعادة — [P14-b] الاستعادة v2 بأربعة جداول — [P15-b] v3 بسبعة — كل العمليات على Dispatchers.IO والنتائج Result */
class BackupRestoreRepo(
    private val db: AppDatabase,
    private val context: Context
) {

    companion object {
        /** [P13-b] سقف حجم ملف الاستيراد 50MB — نسخة تطبيق جوال لا تحتاج أكثر عملياً */
        const val MAX_IMPORT_BYTES: Long = 50L * 1024 * 1024
    }

    /**
     * تصدير كل الجداول إلى [uri] — يعيد Result.success(إجمالي السجلات) أو failure بخطأ واضح.
     * التصدير يشمل المؤرشف والملغاة (exportOnce/plansExport) كي لا تُفقد أي صف في النسخة.
     */
    suspend fun exportTo(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val data = BackupData(
                version = BACKUP_VERSION,
                exportedAt = System.currentTimeMillis(),
                // exportOnce تشمل المؤرشف (تعليق في Daos.kt) — allOnce كان يفلتره
                parties = db.parties().exportOnce(),
                products = db.products().exportOnce(),
                // exportOnce تشمل الملغاة (status=3) — allOnce كان يفلترها
                invoices = db.invoices().exportOnce(),
                // [P13-b] طرق الوصول الثلاث التالية (allOnce) تُضاف بالتوازي في 13-a — انظر worklog
                invoiceItems = db.invoiceItems().allOnce(),
                payments = db.payments().allOnce(),
                visits = db.visits().allOnce(),
                expenses = db.expenses().allOnce(),
                checks = db.checks().allOnce(),
                // plansExport تشمل الخطط المؤرشفة — plansOnce كان يفلترها
                plans = db.installments().plansExport(),
                installments = db.installments().allInstallments(),
                currencies = db.currencies().allOnce(),
                rules = db.rules().allOnce(),
                journal = db.journal().allEntries(),
                journalLines = db.journal().allLines(),
                stockMoves = db.stockMoves().allMoves(),
                // [P34-M1] نطاق v4 — جداول الكشوف الثمانية (اكتمال 23/23)
                statementTemplates = db.statementTemplates().allOnce(),
                signatures = db.signatures().allOnce(),
                stamps = db.stamps().allOnce(),
                noteTemplates = db.noteTemplates().allOnce(),
                statements = db.statements().listAll(Int.MAX_VALUE),
                statementDeliveries = db.statements().allDeliveries(),
                statementRules = db.statementRules().allOnce(),
                auditLog = db.auditLog().allLogs(),
                // [P46-W1] نطاق v5 — جدولا الولاء والكوبونات (اكتمال 25/25)
                loyaltyEntries = db.loyalty().allOnce(),
                coupons = db.coupons().allOnce()
            )
            val json = buildBackupJson(data)
            val os = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("openOutputStream returned null")
            os.use { stream ->
                BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8)).use { w ->
                    w.write(json)
                    w.flush()
                }
            }
            Result.success(totalRows(data))
        } catch (ce: CancellationException) {
            throw ce // إلغاء الكوروتين لا يُبتلع في Result — يعاد رميه كما هو
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
 * استعادة بالدمج من [uri] — [P14-b] أطراف ومنتجات وزيارات ومصروفات غير موجودة فقط
 * — [P15-b] تضاف الشيكات وخطط الأقساط والأقساط (بقاعدة اليتيم — انظر رأس الملف)
 * — داخل معاملة واحدة ذرّية (فشل أي إدراج يتراجع كله).
 * يعيد Result.success(ImportStats) أو failure برسالة عربية.
*/
    suspend fun importFrom(uri: Uri): Result<ImportStats> = withContext(Dispatchers.IO) {
        try {
            val text = readTextCapped(uri)
            val parsed = parseBackup(text)
            val data = parsed.data
                ?: throw BackupFormatException(parsed.error ?: context.getString(R.string.bk_err_unsupported))
            // المعاملة تحسب الخطة من حالة القاعدة اللحظية ثم تنفّذها — لا نافذة بين الفحص والإدراج
            val stats = db.withTransaction {
                val existingPartyIds = db.parties().exportOnce().mapTo(HashSet()) { it.id }
                val existingProductIds = db.products().exportOnce().mapTo(HashSet()) { it.id }
                // [P14-b] جدولا الأرشيف المعزول — إعادة فحص داخل المعاملة نفسها (نمط الجدولين الأولين)
                val existingVisitIds = db.visits().allOnce().mapTo(HashSet()) { it.id }
                val existingExpenseIds = db.expenses().allOnce().mapTo(HashSet()) { it.id }
                // [P15-b] الجداول الثلاثة الجديدة — إعادة فحص داخل المعاملة (نمط الجداول السابقة).
                // plansExport وليس plansOnce: الخطة المؤرشفة تبقى أباً صالحاً لأقساطها،
                // وallInstallments/allOnce بلا فلترة — اليتيم في الاستيراد يُحكم عليه هنا لا في SQL
                val existingCheckIds = db.checks().allOnce().mapTo(HashSet()) { it.id }
                val existingPlanIds = db.installments().plansExport().mapTo(HashSet()) { it.id }
                val existingInstallmentIds = db.installments().allInstallments().mapTo(HashSet()) { it.id }
                // [P34-M1] نطاق v4 — إعادة فحص داخل المعاملة نفسها (نمط الجداول السابقة حرفياً)
                val existingStatementTemplateIds = db.statementTemplates().allOnce().mapTo(HashSet()) { it.id }
                val existingSignatureIds = db.signatures().allOnce().mapTo(HashSet()) { it.id }
                val existingStampIds = db.stamps().allOnce().mapTo(HashSet()) { it.id }
                val existingNoteTemplateIds = db.noteTemplates().allOnce().mapTo(HashSet()) { it.id }
                val existingStatementIds = db.statements().listAll(Int.MAX_VALUE).mapTo(HashSet()) { it.id }
                val existingStatementDeliveryIds = db.statements().allDeliveries().mapTo(HashSet()) { it.id }
                val existingStatementRuleIds = db.statementRules().allOnce().mapTo(HashSet()) { it.id }
                val existingAuditLogIds = db.auditLog().allLogs().mapTo(HashSet()) { it.id }
                // [P46-W1] نطاق v5 — موجودات الجدولين الجديدين + الفواتير (أبٍ محتمل للولاء)
                val existingInvoiceIds = db.invoices().exportOnce().mapTo(HashSet()) { it.id }
                val existingLoyaltyEntryIds = db.loyalty().allOnce().mapTo(HashSet()) { it.id }
                val existingCouponIds = db.coupons().allOnce().mapTo(HashSet()) { it.id }
                val plan = planImport(
                    data, existingPartyIds, existingProductIds,
                    existingVisitIds, existingExpenseIds, // [P14-b] نطاق v2
                    existingCheckIds, existingPlanIds, existingInstallmentIds, // [P15-b] نطاق v3
                    // [P34-M1] نطاق v4 — موجودات جداول الكشوف الثمانية
                    existingStatementTemplateIds, existingSignatureIds, existingStampIds,
                    existingNoteTemplateIds, existingStatementIds, existingStatementDeliveryIds,
                    existingStatementRuleIds, existingAuditLogIds,
                    // [P46-W1] نطاق v5 — الفواتير (أبٍ الولاء) + الجدولان الجديدان
                    existingInvoiceIds, existingLoyaltyEntryIds, existingCouponIds
                )
                // distinctBy: المكرر داخل الملف يُدرج مرة واحدة (الأول يفوز — مطابق لعدّاد الخطة)
                val newParties = data.parties
                    .filter { it.id > 0 && it.id !in existingPartyIds }
                    .distinctBy { it.id }
                val newProducts = data.products
                    .filter { it.id > 0 && it.id !in existingProductIds }
                    .distinctBy { it.id }
                if (newParties.isNotEmpty()) db.parties().upsertAll(newParties)
                if (newProducts.isNotEmpty()) db.products().upsertAll(newProducts)
                // [P14-b] الزيارات: VisitDao.insert هي @Insert بلا onConflict (ABORT افتراضياً)
                // — إدراج id موجود/مكرر كان سيرفع استثناء ويتراجع بالمعاملة كلها؛ لذا تُفلتر
                // القائمة إلى المعرّفات الجديدة فقط + distinctBy ثم إدراج صفّاً صفّاً داخل المعاملة.
                // لا مفاتيح أجنبية في visits فلا قيد ترتيب الإدراج.
                val newVisits = data.visits
                    .filter { it.id > 0 && it.id !in existingVisitIds }
                    .distinctBy { it.id }
                for (v in newVisits) db.visits().insert(v)
                // [P14-b] المصروفات: النمط نفسه تماماً (ExpenseDao.insert @Insert ABORT افتراضياً)
                val newExpenses = data.expenses
                    .filter { it.id > 0 && it.id !in existingExpenseIds }
                    .distinctBy { it.id }
                for (e in newExpenses) db.expenses().insert(e)
                // ═══ [P15-b] نطاق v3 — الترتيب إلزامي (FK): الخطط أولاً ثم الأقساط ثم الشيكات ═══
                // الأباء الصالحون للخطط/الشيكات = أطراف القاعدة (الموجودة أصلاً) + أطراف الملف
                // المدرجة أعلاه فعلاً (نفس مرشّح planImport حرفياً — الخطة = التنفيذ)
                val validPartyIds = existingPartyIds + newParties.mapTo(HashSet()) { it.id }
                // الخطط: InstallmentDao.insertPlan هي @Upsert لكن تُمرَّر معرفات جديدة غير موجودة
                // فقط فتعمل إدراجاً خالصاً (نمط upsertAll في الأطراف) — صفّاً صفّاً داخل المعاملة.
                // مرشّح قاعدة اليتيم بأسلوب seen-set لا distinctBy: مع تكرار id داخل الملف
                // يحتكم «الأول يفوز» حتى لو كان الأول يتيمًا — مطابق تماماً لعدّاد الخطة
                // (planImport يستهلك seen بالأول بغضّ النظر عن صلاحية أبيّه)
                val seenPlans = HashSet<Long>()
                val newPlans = data.plans.filter {
                    it.id > 0 && it.id !in existingPlanIds && seenPlans.add(it.id) &&
                        it.partyId in validPartyIds
                }
                for (p in newPlans) db.installments().insertPlan(p)
                // الأقساط: أبٌ صالح = خطة موجودة بالقاعدة (أصلية أو مؤرشفة) أو خطة أُدخلت أعلاه —
                // insertInstallments دفعة واحدة @Insert بلا onConflict (ABORT): التصفية المسبقة
                // (معرفات جديدة + غير مكررة + أبٍ صالح) هي ما يمنع SQLiteConstraintException
                val importedPlanIds = newPlans.mapTo(HashSet()) { it.id }
                val validPlanIds = existingPlanIds + importedPlanIds
                val seenInstallments = HashSet<Long>()
                val newInstallments = data.installments.filter {
                    it.id > 0 && it.id !in existingInstallmentIds && seenInstallments.add(it.id) &&
                        it.planId in validPlanIds
                }
                if (newInstallments.isNotEmpty()) db.installments().insertInstallments(newInstallments)
                // الشيكات: CheckDao.upsert هي @Upsert لكن تُمرَّر معرفات جديدة فقط فتعمل إدراجاً
                // خالصاً — صفّاً صفّاً (لا توجد دالة إدراج دفعي في CheckDao أصلاً)، وقاعدة اليتيم
                // باتجاه الأطراف (RESTRICT) تمنع انكسار الإدراج على قيد غائب
                val seenChecks = HashSet<Long>()
                val newChecks = data.checks.filter {
                    it.id > 0 && it.id !in existingCheckIds && seenChecks.add(it.id) &&
                        it.partyId in validPartyIds
                }
                for (c in newChecks) db.checks().upsert(c)
                // ═══ [P46-W1] نطاق v5 — الكوبونات (مستقلة) ثم دفتر الولاء (يتيم الأطراف والفواتير) ═══
                // نفس مرشّح planImport حرفياً: الخطة = التنفيذ بلا انحراف. الفواتير لا تُستعاد
                // في المسار الدمجي فالصف المرتبط بفاتورة غائمة عن القاعدة يُتخطى (لا يفشل).
                val newCoupons = data.coupons
                    .filter { it.id > 0 && it.id !in existingCouponIds }
                    .distinctBy { it.id }
                for (c in newCoupons) db.coupons().upsert(c)
                val seenLoyalty = HashSet<Long>()
                val newLoyalty = data.loyaltyEntries.filter {
                    it.id > 0 && it.id !in existingLoyaltyEntryIds && seenLoyalty.add(it.id) &&
                        it.partyId in validPartyIds &&
                        (it.invoiceId == null || it.invoiceId in existingInvoiceIds)
                }
                for (e in newLoyalty) db.loyalty().insert(e)
                // ═══ [P34-M1] نطاق v4 — الترتيب إلزامي: المستقل ثم الكشوف (يتيم الأطراف) ثم ═══
                // التسليمات (يتيمة الكشوف) ثم القواعد ثم سجل التدقيق — قواعد اليتيم نفسها
                // التي احتسبها planImport حرفياً: الخطة = التنفيذ بلا انحراف.
                // المستقلان بلا مفاتيح أجنبية (قوالب/تواقيع/أختام/قوالب ملاحظات):
                // @Upsert بمعرفات جديدة غير موجودة فقط ⇒ إدراج خالص لا يلمس صفوفاً قائمة
                val newStatementTemplates = data.statementTemplates
                    .filter { it.id > 0 && it.id !in existingStatementTemplateIds }
                    .distinctBy { it.id }
                for (t in newStatementTemplates) db.statementTemplates().upsert(t)
                val newSignatures = data.signatures
                    .filter { it.id > 0 && it.id !in existingSignatureIds }
                    .distinctBy { it.id }
                for (s in newSignatures) db.signatures().upsert(s)
                val newStamps = data.stamps
                    .filter { it.id > 0 && it.id !in existingStampIds }
                    .distinctBy { it.id }
                for (s in newStamps) db.stamps().upsert(s)
                val newNoteTemplates = data.noteTemplates
                    .filter { it.id > 0 && it.id !in existingNoteTemplateIds }
                    .distinctBy { it.id }
                for (n in newNoteTemplates) db.noteTemplates().upsert(n)
                // الكشوف: StatementDao.insert هي @Insert (ABORT افتراضياً) — التصفية المسبقة
                // (معرفات جديدة + غير مكررة بأسلوب seen «الأول يفوز» + أبٍ طرف صالح) هي ما
                // يمنع SQLiteConstraintException — مطابق تماماً لعدّاد الخطة
                val seenStatements = HashSet<Long>()
                val newStatements = data.statements.filter {
                    it.id > 0 && it.id !in existingStatementIds && seenStatements.add(it.id) &&
                        it.partyId in validPartyIds
                }
                for (s in newStatements) db.statements().insert(s)
                // التسليمات: أبٌ صالح = كشف موجود بالقاعدة أو كشف أُدخل أعلاه — insert بلا
                // onConflict فالتصفية المسبقة هي الحارس (نمط الأقساط حرفياً)
                val importedStatementIds = newStatements.mapTo(HashSet()) { it.id }
                val validStatementIds = existingStatementIds + importedStatementIds
                val seenDeliveries = HashSet<Long>()
                val newDeliveries = data.statementDeliveries.filter {
                    it.id > 0 && it.id !in existingStatementDeliveryIds && seenDeliveries.add(it.id) &&
                        it.statementId in validStatementIds
                }
                for (dl in newDeliveries) db.statements().insertDelivery(dl)
                // القواعد: بلا مفاتيح أجنبية (مراجع ناعمة) — إدراج معرفات جديدة فقط
                val newStatementRules = data.statementRules
                    .filter { it.id > 0 && it.id !in existingStatementRuleIds }
                    .distinctBy { it.id }
                for (r in newStatementRules) db.statementRules().upsert(r)
                // سجل التدقيق: @Insert بلا onConflict — معرفات جديدة فقط (إلحاق لا يفسد السجل)
                val newAuditLogs = data.auditLog
                    .filter { it.id > 0 && it.id !in existingAuditLogIds }
                    .distinctBy { it.id }
                for (a in newAuditLogs) db.auditLog().insert(a)
                plan
            }
            Result.success(stats)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * قراءة النص كاملاً مع سقف صارم أثناء القراءة نفسها (حتى لو أخفى المزوّد حجمه الحقيقي،
     * بعض موفري المستندات يعيدون SIZE=-1) — تجاوز السقف يرفع خطأ «الملف أكبر من الحد».
     */
    private fun readTextCapped(uri: Uri): String {
        queryDeclaredSize(uri)?.let { declared ->
            if (declared > MAX_IMPORT_BYTES) {
                throw BackupFormatException(context.getString(R.string.bk_err_too_large))
            }
        }
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("openInputStream returned null")
        input.use { stream ->
            val out = ByteArrayOutputStream(256 * 1024)
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                if (out.size() + n > MAX_IMPORT_BYTES) {
                    throw BackupFormatException(context.getString(R.string.bk_err_too_large))
                }
                out.write(chunk, 0, n)
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }
    }

    /** حجم الملف المعلن من مزوّد المستندات إن وُجد و كان موثوقاً — وإلا null (السقف أثناء القراءة يكفي) */
    private fun queryDeclaredSize(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()
}
