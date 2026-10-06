package com.superbiz.app.data.repo

import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.Installment
import com.superbiz.app.data.db.InstallmentPlan
import com.superbiz.app.data.db.InstallmentRow
import com.superbiz.app.data.db.Payment
import com.superbiz.app.domain.AccountingEngine
import com.superbiz.app.domain.EarlyPay
import com.superbiz.app.domain.InstallmentEngine
import kotlinx.coroutines.flow.Flow
import androidx.room.withTransaction

/**
 * مستودع خطط الأقساط — ربط كامل بمحرك القيد المزدوج
 * فتح الخطة يسجّل بيعاً/شراءً آجلاً، وكل قسط يسدَّد يسجّل قبضاً/سداداً.
 * : كل العمليات متعددة الجداول ذرّية عبر withTransaction — لا حالة نصف مكتملة.
*/
class InstallmentRepo(private val db: AppDatabase, private val ledger: LedgerRepo) {

    companion object {
        const val REF = "plan"
    }

    fun plans(): Flow<List<InstallmentPlan>> = db.installments().plans()
    suspend fun plan(id: Long): InstallmentPlan? = db.installments().planById(id)
    suspend fun installmentsOf(planId: Long): List<Installment> =
        db.installments().installmentsOf(planId)

    fun installmentsOfFlow(planId: Long): Flow<List<Installment>> =
        db.installments().installmentsOfFlow(planId)

    /**
     * إنشاء خطة تقسيط كاملة: قيد البيع/الشراء + المقدمة + توليد الجدول — ذرّي بالكامل
     * [P33-P8] total/downPayment قروش Long — حساب صحيح تام بلا round2 ولا عتبات
     */
    suspend fun createPlan(
        title: String,
        partyId: Long,
        direction: Int,
        total: Long,
        downPayment: Long,
        months: Int,
        startDate: Long,
        currency: String,
        note: String,
        today: Long = System.currentTimeMillis()
    ): Long {
        val t = total
        require(t > 0L) { "total must be positive" }
        // المقدمة ≥ الإجمالي كانت تصنع خطة بأقساط صفرية صامتة — تُرفض
        // (حرس NaN ساق لأن Long لا يكون NaN — [P33-P8])
        require(downPayment >= 0L) { "downPayment must be >= 0" }
        val down = downPayment.coerceIn(0L, t)
        val financed = t - down
        require(financed > 0L) { "financed amount must be > 0 (down payment must be less than total)" }
        require(months in 1..120) { "months in 1..120" }
        // عنوان الخطة إلزامي — كانت الخطط بعنوان فارغ تُعرض أعمى في
        // القوائم والتذكيرات، وسقف 120 قسطاً يمنع توليد آلاف الصفوف بخطأ إدخال
        require(title.isNotBlank()) { "plan title required" }
        var planId = 0L
        db.withTransaction {
            planId = db.installments().insertPlan(
                InstallmentPlan(
                    title = title.trim(), partyId = partyId, direction = direction,
                    total = t, downPayment = down, financed = financed,
                    months = months, startDate = startDate, currency = currency, note = note.trim()
                )
            )
            // قيد فتح الخطة (إجمالي الاتفاق) — ترحيل داخلي ضمن المعاملة نفسها
            ledger.postInternal(
                if (direction == 0) AccountingEngine.installmentSale(partyId, t, today, planId)
                else AccountingEngine.installmentPurchase(partyId, t, today, planId)
            )
            // الدفعة المقدمة تُسجَّل فوراً
            if (down > 0L) {
                ledger.postInternal(
                    if (direction == 0) AccountingEngine.installmentPaidIn(partyId, down, today, planId)
                    else AccountingEngine.installmentPaidOut(partyId, down, today, planId)
                )
                db.payments().insert(
                    Payment(
                        partyId = partyId, amount = down, date = today,
                        direction = if (direction == 0) 0 else 1,
                        method = "INSTALLMENT_DOWN", note = "دفعة مقدمة — $title",
                        planId = planId // (M-4.9 توحيد): الدفعة المقدمة مرتبطة بخطتها
                    )
                )
            }
            // توليد جدول الأقساط
            db.installments().insertInstallments(
                InstallmentEngine.buildSchedule(financed, months, startDate).map {
                    Installment(planId = planId, seq = it.seq, amount = it.amount, dueDate = it.dueDate)
                }
            )
        }
        ledger.onMutate?.invoke()
        return planId
    }

    /**
     * سداد قسط (كلي أو جزئي): قيد نقدي + سطر دفعة + تحديث حالة القسط — ذرّي.
     * يعيد المبلغ المسدد فعلياً (قروش). يقرأ القسط من جديد داخل المعاملة كي لا يسدد على نسخة قديمة.
     * [P33-P8] amount قروش Long — coerceIn صحيح ومساواة تامة بلا عتبة 0.005
     */
    suspend fun pay(
        inst: Installment,
        amount: Long? = null,
        today: Long = System.currentTimeMillis()
    ): Long {
        var paidNow = 0L
        db.withTransaction {
            val p = db.installments().planById(inst.planId) ?: return@withTransaction
            val fresh = db.installments().installmentsOf(inst.planId)
                .firstOrNull { it.id == inst.id } ?: return@withTransaction
            val open = fresh.amount - fresh.paidAmount
            // [P33-P8] حرس NaN ساق (Long لا يكون NaN) — القصّ إلى المفتوح بمقارنة صحيحة تامة
            val requested = amount ?: open
            val amt = requested.coerceIn(0L, open)
            if (amt <= 0L) return@withTransaction
            ledger.postInternal(
                if (p.direction == 0) AccountingEngine.installmentPaidIn(p.partyId, amt, today, p.id)
                else AccountingEngine.installmentPaidOut(p.partyId, amt, today, p.id)
            )
            db.payments().insert(
                Payment(
                    partyId = p.partyId, amount = amt, date = today,
                    direction = if (p.direction == 0) 0 else 1,
                    method = "INSTALLMENT", note = "قسط ${fresh.seq}/${p.months} — ${p.title}",
                    planId = p.id // (M-4.9 توحيد): دفعة القسط مرتبطة بخطتها
                )
            )
            val newPaid = fresh.paidAmount + amt
            // [P33-P8] مساواة تامة — عتبة 0.005 حُذفت
            val st = if (newPaid >= fresh.amount) InstallmentEngine.St.PAID
            else InstallmentEngine.St.PARTIAL
            db.installments().updatePaid(fresh.id, newPaid, today, st)
            paidNow = amt
        }
        if (paidNow > 0L) ledger.onMutate?.invoke()
        return paidNow
    }

    /**
 * : تسوية السداد المبكر المخفَّضة — كانت الدفعة المخفَّضة تمر عبر
 * pay() فيبقى المفتوح = قيمة الخصم (2.00 من 100.00) والقسط عالقاً شبه مفتوح
 * للأبد ويمكن «تحصيل الخصم» مرة ثانية. هذا المسار يغلق القسط كلياً داخل
 * معاملة واحدة: قيد التحصيل الفعلي بالمبلغ المخفَّض + قيد إعداب الخصم الذي
 * يوازن الذمم حتى الصفر، ثم paidAmount = amount وحالة PAID.
 * شرط التنفيذ: أهلية السداد المبكر حقيقية (eligible) وقرص العرض محسوب داخلياً
 * من المفتوح الحالي — لا اعتماد على مبلغ يمرر من الخارج.
 * يعيد المبلغ المسدد فعلياً، أو 0 إن لم تُنفَّذ التسوية.
*/
    // [P33-P8] قروش Long — EarlyPay يعمل على قروش (DebtPlanP4 مُرحّل بنفس الموجة P33)
    suspend fun payEarlySettlement(inst: Installment, today: Long = System.currentTimeMillis()): Long {
        var paidNow = 0L
        db.withTransaction {
            val p = db.installments().planById(inst.planId) ?: return@withTransaction
            val fresh = db.installments().installmentsOf(inst.planId)
                .firstOrNull { it.id == inst.id } ?: return@withTransaction
            if (!EarlyPay.eligible(fresh.amount, fresh.paidAmount, fresh.dueDate, today)) return@withTransaction
            val open = fresh.amount - fresh.paidAmount
            val quote = EarlyPay.quote(open) ?: return@withTransaction
            if (quote.first <= 0L) return@withTransaction
            ledger.postInternal(
                if (p.direction == 0) AccountingEngine.installmentPaidIn(p.partyId, quote.first, today, p.id)
                else AccountingEngine.installmentPaidOut(p.partyId, quote.first, today, p.id)
            )
            ledger.postInternal(
                AccountingEngine.installmentEarlyWriteOff(p.partyId, quote.second, today, p.id, p.direction == 0)
            )
            db.payments().insert(
                Payment(
                    partyId = p.partyId, amount = quote.first, date = today,
                    direction = if (p.direction == 0) 0 else 1,
                    method = "INSTALLMENT", note = "سداد مبكر مخفّض — قسط ${fresh.seq}/${p.months} — ${p.title}",
                    planId = p.id // (M-4.9 توحيد): دفعة التسوية مرتبطة بخطتها
                )
            )
            db.installments().updatePaid(fresh.id, fresh.amount, today, InstallmentEngine.St.PAID)
            paidNow = quote.first
        }
        if (paidNow > 0L) ledger.onMutate?.invoke()
        return paidNow
    }

    /** تحديث حالة القسط من الصفر (تصحيح حالة قاعدة البيانات) */
    suspend fun recomputeStatus(inst: Installment, today: Long = System.currentTimeMillis()) {
        val st = InstallmentEngine.statusOf(inst.amount, inst.paidAmount, inst.dueDate, today)
        db.installments().updatePaid(inst.id, inst.paidAmount, inst.paidDate, st)
    }

    // ══ : إعادة جدولة قسط متأخر (وظيفة 28) ══

    /**
     * تأجيل قسط غير مسدد إلى تاريخ جديد لا يسبق اليوم — معاملة واحدة:
     * قراءة القسط بحالته الحالية داخل المعاملة (لا جدولة على نسخة متقادمة)،
     * رفض المسدد كلياً والتاريخ الماضي، ثم تحديث dueDate فقط (بلا لمس للسداد
     * أو القيود — لا حركة مال هنا). سطر تدقيق يُكتب في سجل الأحداث القائم
     * (ErrorCenter — يُعرض في الإعدادات ← متقدم ← سجل الأخطاء) لأن المخطط
     * لا يحتوي جدول AuditLog أصلاً، وهذا هو مسار السجل الوحيد القائم.
     * يعيد false بأمان عند أي رفض.
     */
    suspend fun reschedule(
        inst: Installment,
        newDueDate: Long,
        today: Long = System.currentTimeMillis()
    ): Boolean {
        // التاريخ الجديد لا يسبق بداية اليوم
        if (newDueDate < com.superbiz.app.domain.algo.TimeMath.startOfDay(today)) return false
        var ok = false
        db.withTransaction {
            val fresh = db.installments().installmentsOf(inst.planId)
                .firstOrNull { it.id == inst.id } ?: return@withTransaction
            // المسدد كلياً لا يُعاد جدولته — [P33-P8] مساواة تامة بلا عتبة
            if (fresh.paidAmount >= fresh.amount) return@withTransaction
            val plan = db.installments().planById(fresh.planId) ?: return@withTransaction
            db.installments().updateDueDate(fresh.id, newDueDate)
            // [P31-A]: سطر تدقيق تقني بالإنجليزية في السجل فقط — تأكيد المستخدم يأتي
            // Toast موطّناً من الشاشة (كان يُبثّ بالعربية في الـSnackbar لكل المستخدمين
            // عبر كشف الأحرف العربية في Nav)
            com.superbiz.app.core.ErrorCenter.info(
                "Installments",
                "deferred installment #${fresh.seq}/${plan.months} (plan ${plan.title}) " +
                    "from ${com.superbiz.app.util.Dates.short(fresh.dueDate)} " +
                    "to ${com.superbiz.app.util.Dates.short(newDueDate)}"
            )
            ok = true
        }
        if (ok) ledger.onMutate?.invoke()
        return ok
    }

    /** إحصاءات خطة (تقدم/متأخرات/التالي) */
    suspend fun statsOf(planId: Long, today: Long = System.currentTimeMillis()) =
        InstallmentEngine.stats(rowsOf(planId), today)

    /**
 * : إحصاءات عدة خطط باستعلام واحد — statsOf القديمة تقرأ
 * أقساط كل خطة على حدة (300 خطة = 300 استعلام) في كل انبعاث للقائمة.
 * الخطط بلا صفوف أقساط تُغيب عن الخريطة عمداً فيرجع المستدعي لـstatsOf.
*/
    suspend fun statsForPlans(
        planIds: List<Long>,
        today: Long = System.currentTimeMillis()
    ): Map<Long, InstallmentEngine.PlanStats> {
        if (planIds.isEmpty()) return emptyMap()
        val want = planIds.toHashSet()
        val rows = db.installments().allInstallments()
            .filter { it.planId in want }
            .map { InstallmentRow(it.id, it.planId, it.seq, it.amount, it.dueDate, it.paidAmount, it.paidDate) }
        return rows.groupBy { it.planId }
            .mapValues { (_, rs) -> InstallmentEngine.stats(rs, today) }
    }

    private suspend fun rowsOf(planId: Long): List<InstallmentRow> =
        db.installments().installmentsOf(planId).map {
            InstallmentRow(it.id, it.planId, it.seq, it.amount, it.dueDate, it.paidAmount, it.paidDate)
        }

    /** حذف خطة مع عكس قيودها بالكامل وتنظيف دفعاتها — ذرّي */
    suspend fun deletePlan(plan: InstallmentPlan) {
        db.withTransaction {
            ledger.unpostInternal(REF, plan.id)
            // (M-4.9 توحيد): مع عمود payments.planId أصبح حذف دفعات الخطة دقيقاً
            // بمعرّف الخطة مباشرة — كان R12-C3 يُقدّرها استدلالياً بنافذة زمنية + LIKE على
            // العنوان (هشّ عند تشابه عناوين خطط الطرف نفسه). قيود SET NULL في المخطط v5
            // شبكة أمان إضافية عند أي مسار حذف آخر.
            db.payments().deleteByPlanId(plan.id)
            db.installments().deleteInstallmentsOf(plan.id)
            db.installments().deletePlan(plan.id)
        }
        ledger.onMutate?.invoke()
    }

    /** أقساط تستحق/متأخرة خلال نافذة — للتذكيرات الآلية
 * : كانت تقرأ خطط كل خطة بأقساطها استعلاماً لكل خطة (N+1 داخل
 * دورة الأتمتة كل 6 ساعات، 300 خطة = 301 استعلاماً) — استعلامان الآن.
*/
    suspend fun dueBetween(from: Long, to: Long): List<Triple<InstallmentPlan, Installment, Int>> {
        val out = ArrayList<Triple<InstallmentPlan, Installment, Int>>()
        val plansById = db.installments().plansOnce().associateBy { it.id }
        for ((p, i) in db.installments().allInstallments().mapNotNull { inst ->
            plansById[inst.planId]?.let { it to inst }
        }) {
            val st = InstallmentEngine.statusOf(i.amount, i.paidAmount, i.dueDate, from)
            if (st != InstallmentEngine.St.PAID && i.dueDate in from..to) {
                out += Triple(p, i, st)
            }
        }
        return out
    }
}
