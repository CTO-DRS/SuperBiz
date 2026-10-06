package com.superbiz.app.domain

import java.util.Calendar

/**
 * محرك جدولة الأقساط — حسابات حتمية خالصة بلا أي اعتماد على قاعدة البيانات.
 * يولّد جدولاً شهرياً بحيث يساوي مجموع الأقساط المبلغ المُجدول تماماً.
 *
 * [P33-P8] الترحيل المالي: كل المبالغ قروش صحيحة (Long) — كان المحرك يعمل على
 * Double ريال مع تقريب BigDecimal HALF_UP وتوزيع «هللات» بالضرب في 100 عند التدني،
 * وصار التوزيع نفسه هو العمل الصحيح الأصلي: base = floor(financed / n) وبقايا
 * أقل من n قرشاً تُمتص قرشاً واحداً في الأقساط الأولى — بلا أي فاصلة عائمة
 * وبلا عتبة EPS، ومجموع الجدول = المبلغ تماماً بمساواة صحيحة.
 */
object InstallmentEngine {

    /** حالة القسط */
    object St {
        const val DUE = 0        // مستحق
        const val PAID = 1       // مدفوع
        const val LATE = 2       // متأخر
        const val PARTIAL = 3    // مدفوع جزئياً
    }

    data class ScheduleItem(val seq: Int, val amount: Long, val dueDate: Long)

    /**
     * توليد جدول الأقساط: قسط شهري ثابت مضاف إليه فروق القروش في الأقساط الأولى.
     * startDate هو تاريخ استحقاق القسط الأول.
     */
    fun buildSchedule(financed: Long, months: Int, startDate: Long): List<ScheduleItem> {
        require(months >= 1) { "months must be >= 1: $months" }
        val n = months
        val raw = financed.coerceAtLeast(0L)
        if (raw <= 0L) return emptyList()
        if (n == 1) {
            return listOf(ScheduleItem(seq = 1, amount = raw, dueDate = addMonths(startDate, 0)))
        }
        val base = raw / n
        if (base <= 0L) {
            // تمويل ضئيل لا يحتمل قرشاً واحداً لكل شهر — قسط واحد يساوي المبلغ
            // (نفس فلسفة السقوط التاريخية M-2.9/P6-M4 — كانت تولّد أقساطاً صفريّة)
            return listOf(ScheduleItem(seq = 1, amount = raw, dueDate = addMonths(startDate, 0)))
        }
        // البقايا (أقل من n قروش) توزَّع قرشاً واحداً على الأقساط الأولى
        var leftover = raw % n
        val items = ArrayList<ScheduleItem>(n)
        for (i in 0 until n) {
            val amount = base + if (leftover > 0L) { leftover -= 1L; 1L } else 0L
            items += ScheduleItem(seq = i + 1, amount = amount, dueDate = addMonths(startDate, i))
        }
        return items
    }

    /** إضافة أشهر تقويمية حقيقية (تحافظ على اليوم وتتعامل مع نهايات الشهور) */
    fun addMonths(ts: Long, months: Int): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ts
        c.add(Calendar.MONTH, months)
        return c.timeInMillis
    }

    /** بداية الشهر الحالي لاستخدامها في KPI ‏"قسط هذا الشهر" */
    fun monthStart(today: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = today
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun monthEnd(today: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = monthStart(today)
        c.add(Calendar.MONTH, 1)
        return c.timeInMillis - 1
    }

    /** حالة قسط واحد عند تاريخ اليوم — [P33-P8] مقارنات صحيحة تامة */
    fun statusOf(amount: Long, paid: Long, dueDate: Long, today: Long): Int = when {
        paid >= amount -> St.PAID
        paid > 0L && today <= dueDate -> St.PARTIAL
        today > dueDate -> St.LATE
        else -> St.DUE
    }

    /** بيانات مخطط مشتقة من أسطر الجدول */
    data class PlanStats(
        val paid: Long,            // المدفوع فعلياً (قروش)
        val remaining: Long,       // المتبقي (قروش)
        val progress: Double,      // 0..1
        val lateCount: Int,        // أقساط متأخرة (غير مسددة تجاوز موعدها)
        val lateAmount: Long,
        val dueThisMonth: Long,    // مستحقات الشهر الحالي (غير المدفوعة)
        val nextDue: ScheduleItem?,// القسط التالي غير المسدد
        val done: Boolean          // اكتملت الخطة؟
    )

    fun stats(rows: List<ScheduleRow>, today: Long = System.currentTimeMillis()): PlanStats {
        var paid = 0L
        var lateCount = 0
        var lateAmount = 0L
        var dueMonth = 0L
        var next: ScheduleItem? = null
        val ms = monthStart(today)
        val me = monthEnd(today)
        for (r in rows.sortedBy { it.seq }) {
            paid += r.paidAmount
            val st = statusOf(r.amount, r.paidAmount, r.dueDate, today)
            if (st != St.PAID) {
                val open = r.amount - r.paidAmount
                if (st == St.LATE) { lateCount++; lateAmount += open }
                if (r.dueDate in ms..me) dueMonth += open
                if (next == null) next = ScheduleItem(r.seq, open, r.dueDate)
            }
        }
        val financed = rows.sumOf { it.amount }
        val remaining = financed - paid
        return PlanStats(
            paid = paid,
            remaining = remaining,
            progress = if (financed > 0) (paid.toDouble() / financed).coerceIn(0.0, 1.0) else 0.0,
            lateCount = lateCount,
            lateAmount = lateAmount,
            dueThisMonth = dueMonth,
            nextDue = next,
            done = remaining == 0L
        )
    }

    /** صف جدول كما يأتي من قاعدة البيانات (تقليل الاقتران) — [P33-P8] قروش */
    interface ScheduleRow {
        val seq: Int
        val amount: Long
        val paidAmount: Long
        val dueDate: Long
    }

    /** أقساط تستحق خلال نافذة زمنية (للتذكيرات الآلية) */
    fun dueWithin(rows: List<ScheduleRow>, from: Long, to: Long): List<ScheduleRow> =
        rows.filter { it.dueDate in from..to && statusOf(it.amount, it.paidAmount, it.dueDate, from) != St.PAID }
}
