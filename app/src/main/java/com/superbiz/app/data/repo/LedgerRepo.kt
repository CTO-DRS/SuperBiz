package com.superbiz.app.data.repo

import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.Invoice
import com.superbiz.app.data.db.JournalEntry
import com.superbiz.app.data.db.JournalLine
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.db.Payment
import com.superbiz.app.data.db.StatementRow
import com.superbiz.app.domain.AccountingEngine
import com.superbiz.app.domain.Accounts
import com.superbiz.app.domain.JournalDraft
import com.superbiz.app.domain.OpenInvoice
import com.superbiz.app.domain.RiskEngine
import com.superbiz.app.domain.RiskResult
import com.superbiz.app.util.Money
import kotlinx.coroutines.flow.Flow
import androidx.room.withTransaction

class LedgerRepo(private val db: AppDatabase) {

    /** يُنبه بعد أي تغيير محاسبي فعلي (ترحيل قيد) — يستعمله تحديث الويدجت */
    var onMutate: (() -> Unit)? = null

    fun parties(): Flow<List<Party>> = db.parties().all()
    suspend fun party(id: Long): Party? = db.parties().byId(id)
    suspend fun partyFlow(id: Long): Flow<Party?> = db.parties().byIdFlow(id)
    suspend fun saveParty(p: Party): Long {
        // رفض الاسم الفارغ — كان يُقبل ويكسر كشف الحساب والبحث والفهرسة بالاسم
        require(p.name.isNotBlank()) { "party name required" }
        // @Upsert في مسار التحديث يعيد -1 — نجيب معرّف الكيان الصريح عند التعديل
        val id = db.parties().upsert(p.copy(name = p.name.trim()))
        return if (id == -1L && p.id != 0L) p.id else id
    }
    suspend fun deleteParty(id: Long) {
        db.parties().archive(id)
    }

    /** ترحيل قيد مزدوج إلى دفتر اليومية — ذرّي: القيد + أسطره معاً أو لا شيء */
    suspend fun post(draft: JournalDraft): Long {
        require(draft.balanced) { "unbalanced journal: ${draft.totalDebit} != ${draft.totalCredit}" }
        var entryId = 0L
        db.withTransaction {
            entryId = db.journal().insertEntry(
                JournalEntry(date = draft.date, memo = draft.memo, refType = draft.refType, refId = draft.refId)
            )
            // [P33-P8] قروش صحيحة — القيد يُخزَّن كما هو بلا تقريب (مساواة تامة)
            db.journal().insertLines(draft.lines.map {
                JournalLine(
                    entryId = entryId, account = it.account,
                    debit = it.debit,
                    credit = it.credit,
                    partyId = it.partyId
                )
            })
        }
        onMutate?.invoke()
        return entryId
    }

    suspend fun unpost(refType: String, refId: Long) {
        db.withTransaction {
            db.journal().deleteLinesByRef(refType, refId)
            db.journal().deleteEntriesByRef(refType, refId)
        }
        onMutate?.invoke()
    }

    /** رصيد طرف: موجب = يدين لك (عميل مدين)، سالب = أنت مدين له — [P33-P8] قروش Long */
    suspend fun partyBalance(partyId: Long): Long =
        db.journal().linesForParty(partyId).sumOf { it.debit - it.credit }

    /** كشف حساب تفصيلي برصيد متحرك — [P33-P8] تراكم قروش صحيح بلا تقريب */
    suspend fun statement(partyId: Long): List<StatementRow> {
        val rows = db.journal().partyRows(partyId)
        var running = 0L
        return rows.map { r ->
            running += r.debit - r.credit
            StatementRow(
                date = r.date, title = r.memo,
                debit = r.debit, credit = r.credit,
                balance = running,
                refType = r.refType, refId = r.refId
            )
        }
    }

    /** دين جديد (بيع/شراء آجل) — ذرّي: القيد + سطر الدفعة معاً — [P33-P8] amount قروش Long
     *
     * [تدقيق M-6] التوجيه بالاتجاه الصريح لا نوع الطرف وحده: كان الطرف ثنائي
     * الدور (عميل ومورد معاً، type=2) يقع دائماً في فرع دين العميل —
     * فدين مورد (ذمة دائنة عليك) يُرحَّل صمتاً ذمماً مدينة عليه ويظهر في
     * التحصيل بدل السداد. direction=1 يوجّه صراحةً لجانب المورد؛
     * الافتراضي 0 يحفظ سلوك المستدعين الحاليين حرفياً (الأطراف
     * أحادية الدور تتوجّه بنوعها كما كان، وثنائي الدور بلا تمرير يبقى على
     * جانب العميل — الواجهة الآن تعرض الاختيار للأدوار المزدوجة).
     */
    suspend fun addDebt(
        party: Party, amount: Long, date: Long, note: String, direction: Int = 0
    ): Long {
        // رفض المبلغ غير المنطقي — الصفر/السالب يُرحّل قيداً مشوّهاً بلا قيمة
        // [P33-P8] عتبة 0.004 حُذفت — مقارنة صحيحة تامة (القرش الواحد مبلغ حقيقي)
        require(amount > 0L) { "مبلغ الدين غير صالح: $amount" }
        // [P6-M11 إصلاح]: رفض صريح لطرف بلا دور (type خارج 0/1/2 من نسخة احتياطية أو بيانات قديمة)
        // — كان يقع في فرع else فيُرحّل قيد «دين مورد» وهمياً (INVENTORY/PAYABLE) فيظهر
        // في الذمم الدائنة ومخزون وهمي. الرفض برسالة واضحة عبر مسار الرفض الموجود نفسه
        // (require/IllegalArgumentException) الذي يلتقطه launchSafe ويعرضه عبر ErrorCenter —
        // والتوقيع محفوظ Long كي لا يكسر المستدعين خارج الملكية (FeatureVMs/DebtsScreen والاختبارات).
        require(party.isCustomer || party.isSupplier) {
            "لا يمكن تسجيل دين لطرف بلا دور (لا عميل ولا مورد): ${party.name} (id=${party.id})"
        }
        val draft = if (direction == 1 || (direction != 0 && direction != 1)) {
            // اتجاه صريح لجانب المورد — أو أي قيمة غريبة تعامل كأكثر تقييداً (مورد)
            AccountingEngine.newSupplierDebt(party.id, amount, date)
        } else if (party.isSupplier && !party.isCustomer) {
            // مورد خالص — لا غموض، يبقى على جانبه كما كان
            AccountingEngine.newSupplierDebt(party.id, amount, date)
        } else {
            AccountingEngine.newCustomerDebt(party.id, amount, date)
        }
        // سطر الدفعة يحمل اتجاه الدين نفسه — كان 0 دائماً فتُحصى ديون المورّد
        // (صادر) ضمن التحصيل (وارد) في تقارير المدفوعات [تدقيق M-6]
        val payDirection = if (direction == 1 || (direction != 0 && direction != 1)) 1 else
            if (party.isSupplier && !party.isCustomer) 1 else 0
        var entryId = 0L
        db.withTransaction {
            entryId = postInternal(draft)
            db.payments().insert(
                Payment(partyId = party.id, amount = amount, date = date,
                    direction = payDirection, method = "DEBT", note = note.ifBlank { "دين جديد" })
            )
        }
        onMutate?.invoke()
        return entryId
    }

    /** تسجيل دفعة (تحصيل أو سداد) مع ترحيل محاسبي كامل — ذرّي بالكامل — [P33-P8] amount قروش Long */
    suspend fun addPayment(
        party: Party, amount: Long, date: Long,
        direction: Int, method: String, invoiceId: Long? = null, note: String = ""
    ): Long {
        // حماية المبالغ — السالب/الصفر يُرحّل دفاتر معكوسة بلا تحذير
        // [P33-P8] عتبة 0.004 حُذفت — مقارنة صحيحة تامة
        require(amount > 0L) { "مبلغ الدفعة غير صالح: $amount" }
        var effective = amount
        var entryId = 0L
        db.withTransaction {
            // القصّ إلى المفتوح كان يُقرأ خارج المعاملة — دفعتان متزامنتان
            // كانتا تقرآن نفس المفتوح وتتجاوزان الإجمالي. تُقرأ الفاتورة وتُقصّ الدفعة داخل المعاملة.
            if (invoiceId != null) {
                // لا تجاوز للمبلغ المفتوح — الدفعة الزائدة تُقصّ إلى المتبقي بدل رصيد سالب
                val inv = db.invoices().byId(invoiceId)
                    ?: throw IllegalArgumentException("الفاتورة المرجعية غير موجودة: $invoiceId")
                // [P33-P8] فرق قروش صحيح تام — بلا round2
                val open = inv.total - inv.paid
                require(open > 0L) { "الفاتورة مسددة بالكامل ولا تقبل دفعة جديدة" }
                if (effective > open) effective = open
            }
            val d = if (direction == 0)
                AccountingEngine.receipt(party.id, effective, date, invoiceId)
            else AccountingEngine.supplierPayment(party.id, effective, date, invoiceId)
            entryId = postInternal(d)
            db.payments().insert(
                Payment(partyId = party.id, invoiceId = invoiceId, amount = effective,
                    date = date, direction = direction, method = method, note = note)
            )
            if (invoiceId != null) {
                val inv = db.invoices().byId(invoiceId)
                if (inv != null) {
                    // [P33-P8] مساواة تامة — عتبة 0.005 حُذفت
                    val newPaid = inv.paid + effective
                    val status = when {
                        newPaid >= inv.total -> 2
                        newPaid > 0L -> 1
                        else -> 0
                    }
                    db.invoices().updatePaid(invoiceId, newPaid, status)
                }
            }
        }
        onMutate?.invoke()
        return entryId
    }

    /** ترحيل داخلي بلا إشعار ويدجت — يُستدعى داخل معاملات أكبر (نفس الحزمة) */
    internal suspend fun postInternal(draft: JournalDraft): Long {
        require(draft.balanced) { "unbalanced journal: ${draft.totalDebit} != ${draft.totalCredit}" }
        val entryId = db.journal().insertEntry(
            JournalEntry(date = draft.date, memo = draft.memo, refType = draft.refType, refId = draft.refId)
        )
        // [P33-P8] قروش صحيحة — القيد يُخزَّن كما هو بلا تقريب
        db.journal().insertLines(draft.lines.map {
            JournalLine(
                entryId = entryId, account = it.account,
                debit = it.debit,
                credit = it.credit,
                partyId = it.partyId
            )
        })
        return entryId
    }

    /** إلغاء ترحيل داخلي بلا إشعار — يُستدعى داخل معاملات أكبر (نفس الحزمة) */
    internal suspend fun unpostInternal(refType: String, refId: Long) {
        db.journal().deleteLinesByRef(refType, refId)
        db.journal().deleteEntriesByRef(refType, refId)
    }

    private suspend fun openInvoicesFor(pid: Long): List<OpenInvoice> =
        db.invoices().forParty(pid)
            // [P33-P8] «مفتوح» بمساواة صحيحة تامة — عتبة 0.004 حُذفت
            .filter { it.open > 0L }
            // [P33-P8] حدود الاستهلاك العرضي: OpenInvoice ما زال Double (ForecastEngine/RiskEngine نسب) — fromPiasters فقط هنا
            .map { OpenInvoice(it.id, it.partyId, Money.fromPiasters(it.open), it.date, it.dueDate, it.isSale) }

    suspend fun risk(partyId: Long, today: Long = System.currentTimeMillis()): RiskResult {
        // كانت forParty تُستدعى مرتين (داخل openInvoicesFor ثم هنا)
        // = 3 استعلامات لكل طرف في كل تحديث لقائمة الذمم — قائمة واحدة تكفي
        val invoices = db.invoices().forParty(partyId)
        // [P33-P8] «مفتوح» بمساواة صحيحة تامة — عتبة 0.004 حُذفت
        val open = invoices.filter { it.open > 0L }
            // [P33-P8] حدود الاستهلاك العرضي: OpenInvoice ما زال Double — fromPiasters فقط هنا
            .map { OpenInvoice(it.id, it.partyId, Money.fromPiasters(it.open), it.date, it.dueDate, it.isSale) }
        val totalInvoiced = invoices.sumOf { it.total }
        val payments = db.payments().forParty(partyId)
        // كان المجموع يتجاهل الاتجاه — مرتجع الشيك (CHECK_BOUNCE، صادر)
        // يُجمع كتحصيل فيتضخم totalPaid لعميل ارتدت كل شيكاته. الآن: الوارد غير المدين
        // ناقص مرتجعات الشيكات (صافي ما دخل فعلاً)
        val totalPaid = payments.filter { it.method != "DEBT" && it.direction == 0 }.sumOf { it.amount } -
            payments.filter { it.method == "CHECK_BOUNCE" }.sumOf { it.amount }
        // متوسط فجوة السداد: آخر دفعة للفاتورة مقابل تاريخ استحقاقها
        val gaps = mutableListOf<Double>()
        for (inv in invoices) {
            val pays = payments.filter { it.invoiceId == inv.id && it.method != "DEBT" }
            if (pays.isNotEmpty()) {
                val last = pays.maxOf { it.date }
                gaps += ((last - inv.dueDate) / 86_400_000.0)
            }
        }
        // [P33-P8] حدود الاستهلاك العرضي: RiskEngine نسب Double — التحويل عبر fromPiasters هنا فقط
        return RiskEngine.score(
            open, Money.fromPiasters(totalInvoiced), Money.fromPiasters(totalPaid),
            if (gaps.isEmpty()) null else gaps.average(), today
        )
    }

    // ══ : درجة الجدارة + دمج الأطراف المكررة ══

    /** مدخلات درجة الجدارة من بيانات سلوكية حقيقية — غذاء BizMath.creditScore */
    // [P33-P8] openTotal/overdueTotal قروش Long — النسب تبقى Double
    data class CreditInputs(
        val openTotal: Long,
        val overdueTotal: Long,
        val avgDelayDays: Double?,
        val invoiceCount: Int,
        val largestOpenShare: Double,
        val paidRatio: Double
    )

    suspend fun creditInputs(partyId: Long, today: Long = System.currentTimeMillis()): CreditInputs {
        val invoices = db.invoices().forParty(partyId).filter { it.isSale && it.status < 3 }
        // [P33-P8] «مفتوح» بمساواة صحيحة تامة — عتبة 0.004 حُذفت
        val openList = invoices.filter { it.open > 0L }
        val openTotal = openList.sumOf { it.open }
        val overdueTotal = openList.filter { it.dueDate < today }.sumOf { it.open }
        // [P33-P8] نسبة (Double تبقى) — من قروش صحيحة
        val largestShare = if (openTotal > 0L) (openList.maxOfOrNull { it.open } ?: 0L).toDouble() / openTotal.toDouble() else 0.0
        val payments = db.payments().forParty(partyId).filter { it.method != "DEBT" }
        val gaps = mutableListOf<Double>()
        for (inv in invoices) {
            val pays = payments.filter { it.invoiceId == inv.id }
            if (pays.isNotEmpty()) gaps += ((pays.maxOf { it.date } - inv.dueDate) / 86_400_000.0)
        }
        val paidRatio = if (invoices.isEmpty()) 1.0
        else invoices.count { it.status == 2 } / invoices.size.toDouble()
        // [P33-P8] قروش كما هي — بلا round2
        return CreditInputs(
            openTotal,
            overdueTotal,
            if (gaps.isEmpty()) null else gaps.average(),
            invoices.size, largestShare, paidRatio
        )
    }

    /**
     * دمج طرف مكرر في طرف موحَّد — معاملة واحدة ذرّية:
     * فواتيره ودفعاته وأسطر دفتره تُنقل كاملة ثم يُحذف صفه. الرصيد لا يضيع أبداً.
     * يعيد false بأمان إذا اختفى أحد الطرفين أو تساوي المعرفان.
     *
     * [P6-M12 إصلاح]: معاملات اختيارية متوافقة مع التوقيع القديم (القيم الافتراضية
     * تحفظ سلوك المستدعين الحاليين حرفياً) لإصلاح المرجع المعلق للزبون النقدي:
     * إذا كان الطرف المحذوف (dup) هو walk-in الحالي في الإعدادات فإن دمجه يترك
     * walkInPartyId يشير لمعرف محذوف. يمسك المستدعي (الذي يقرأ الإعدادات أصلاً)
     * قيمة walkInPartyId ويمرّرها هنا مع دالة إعادة التوجيه فتنقل المرجع
     * للطرف الباقي (keepId) بعد التثبت من المعاملة — خارجها لأن DataStore
     * لا يشترك المعاملة مع Room. عند غياب الممررين يبقى السلوك كما كان
     * (وensureWalkIn في PosVM يعيد إنشاء زبون نقدي عند المرجع المعلق كخط دفاع ثانٍ).
     * القيد: تعديل SettingsRepo/BackupRepo/AppPrefs ممنوع — فالكتابة الفعلية
     * للإعدادات تبقى مسؤولية المستدعي عبر onWalkInRemap.
     */
    suspend fun mergeParties(
        keepId: Long, dupId: Long,
        walkInPartyId: Long = 0L,
        onWalkInRemap: (suspend (Long) -> Unit)? = null
    ): Boolean {
        if (keepId == dupId) return false
        val merged = db.withTransaction {
            val keep = db.parties().byId(keepId) ?: return@withTransaction false
            val dup = db.parties().byId(dupId) ?: return@withTransaction false
            db.invoices().moveParty(dupId, keepId)
            db.payments().moveParty(dupId, keepId)
            db.journal().movePartyLines(dupId, keepId)
            // كانت الشيكات وخطط الأقساط تبقى على الطرف المحذوف —
            // فلا يمكن تحصيل الشيك بعدها (لغياب الطرف) ويظهر مدين وهمي في الأرصدة
            db.checks().moveParty(dupId, keepId)
            db.installments().moveParty(dupId, keepId)
            // [تدقيق H-1/H-2]: ثلاثة أبناء بقوا خلف الحذف بعد الشيكات والأقساط:
            // نقاط الولاء (FK=CASCADE كانت تمحو الرصيد التاريخي بصمت)،
            // الكشوف (FK=RESTRICT كانت ترمي استثناء قيد فيفشل الدمج كلياً
            // لزبائن صادروا كشوفاً)، والزيارات (بلا FK كانت تُترك يتيمة
            // فيختفي تاريخها من بطاقة الطرف الباقي). الكل تُنقل قبل deleteRow
            // داخل المعاملة نفسها — فشل أي نقل يُرجع الدمج كله.
            db.loyalty().moveParty(dupId, keepId)
            db.statements().moveParty(dupId, keepId)
            db.visits().moveParty(dupId, keepId)
            val mergedNote = listOf(keep.note, dup.note)
                .filter { it.isNotBlank() }
                .joinToString(" | ")
                .take(500)
            // توحيد الدورين — كان دمج مورّد في عميل يحمل فواتير شراء
            // وذمم دائنة إلى طرف «عميل» فتُبنى شاشة الذمم عليها اتجاه قبض خاطئ
            // (receipt بدل supplierPayment) ولا يُقفل الدفع إلا بنوع 2 (كلاهما)
            val mergedType = if (keep.type == dup.type) keep.type else 2
            db.parties().upsert(keep.copy(note = mergedNote, phone = keep.phone.ifBlank { dup.phone }, type = mergedType))
            db.parties().deleteRow(dupId)
            true
        }
        // [P6-M12 إصلاح]: نقل مرجع الزبون النقدي للطرف الباقي بعد نجاح الدمج —
        // خارج المعاملة عمداً (DataStore بلا معاملة مع Room)، وفشلها لا يُبطل
        // الدمج المكتمل أصلاً (ويغطيه ensureWalkIn كخط دفاع ثانٍ)
        if (merged && walkInPartyId > 0 && walkInPartyId == dupId && onWalkInRemap != null) {
            try {
                onWalkInRemap(keepId)
            } catch (e: Exception) {
                android.util.Log.e("LedgerRepo", "walk-in remap after merge failed", e)
            }
        }
        return merged
    }

    /** أرصدة كل الأطراف في استعلام SQL واحد — حقيقي من دفتر الأستاذ بلا N+1 — [P33-P8] قروش Long */
    suspend fun balances(): Map<Long, Long> {
        val map = mutableMapOf<Long, Long>()
        db.journal().partyBalances().forEach {
            map[it.pid] = it.balance
        }
        return map
    }

    /** إجمالي الذمم المدينة والدائنة من دفتر الأستاذ — [P33-P8] قروش Long */
    suspend fun arAp(): Pair<Long, Long> {
        val sums = db.journal().accountSums()
        var arD = 0L; var arC = 0L; var apD = 0L; var apC = 0L
        sums.forEach {
            if (it.account == Accounts.RECEIVABLE) { arD += it.d; arC += it.c }
            if (it.account == Accounts.PAYABLE) { apD += it.d; apC += it.c }
        }
        return (arD - arC) to (apC - apD)
    }
}
