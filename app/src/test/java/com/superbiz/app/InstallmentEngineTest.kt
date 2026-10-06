package com.superbiz.app

import com.superbiz.app.domain.InstallmentEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class InstallmentEngineTest {

    private fun dayOf(y: Int, m: Int, d: Int): Long {
        val c = Calendar.getInstance()
        c.set(y, m - 1, d, 12, 0, 0)
        return c.timeInMillis
    }

    // [P33-P8] كل مبالغ الاختبار كانت ريال Double وصارت قروش Long — وقيم الهللات
    // القديمة هي نفسها قروش اليوم، والتوزيع صار صحيحاً تاماً: base = المبلغ/n مُدنّناً،
    // وبقايا أقل من n قرشاً تُمتص قرشاً واحداً في الأقساط الأولى (لا في الأخيرة)

    @Test
    fun `schedule sums exactly to financed amount`() {
        val rows = InstallmentEngine.buildSchedule(100_000L, 3, dayOf(2026, 1, 15))
        assertEquals(3, rows.size)
        assertEquals(100_000L, rows.sumOf { it.amount })
        // base = 33_333 لكل شهر + بقايا القرش الواحد تُمتص في الأول
        assertEquals(33_334L, rows[0].amount)
        assertEquals(33_333L, rows[2].amount)
    }

    @Test
    fun `single month pays full amount`() {
        val rows = InstallmentEngine.buildSchedule(75_000L, 1, dayOf(2026, 3, 1))
        assertEquals(1, rows.size)
        assertEquals(75_000L, rows[0].amount)
    }

    @Test
    fun `monthly cadence adds calendar months`() {
        val rows = InstallmentEngine.buildSchedule(60_000L, 4, dayOf(2026, 11, 30))
        val c = Calendar.getInstance()
        c.timeInMillis = rows[1].dueDate
        assertEquals(Calendar.DECEMBER, c.get(Calendar.MONTH)) // 11/30 → 12/30
        c.timeInMillis = rows[3].dueDate
        assertEquals(Calendar.FEBRUARY, c.get(Calendar.MONTH))
        assertEquals(2027, c.get(Calendar.YEAR))
    }

    @Test
    fun `zero or negative financed yields empty schedule`() {
        assertTrue(InstallmentEngine.buildSchedule(0L, 5, 0L).isEmpty())
        assertTrue(InstallmentEngine.buildSchedule(-10_000L, 5, 0L).isEmpty())
    }

    @Test
    fun `micro amount over many months never yields a negative installment`() {
        // [P6-M4 إصلاح]: كانت 0.10 ريال = 10 هللات على 12 شهراً تنتج قسطاً أخيراً −0.01
        // (base=0.01 × 11 = 0.11) يفجّر coerceIn في pay() — القيم نفسها قروش اليوم:
        // 10 قروش لا تحتمل قرشاً واحداً موجباً لكل شهر (base = 0) فتسقط إلى قسط واحد كامل
        val rows = InstallmentEngine.buildSchedule(10L, 12, dayOf(2026, 1, 10))
        assertEquals(1, rows.size)
        assertEquals(10L, rows[0].amount)
        assertEquals(10L, rows.sumOf { it.amount })
    }

    @Test
    fun `rounding overshoot is absorbed into earlier installments`() {
        // [P6-M4 إصلاح] بالهللات: 240 هللة على 25 شهراً — القيم نفسها قروش اليوم:
        // floor 9 للجميع + قرش لبواكير 15 قسطاً: 15×10 + 10×9 = 240 بالضبط
        // (لا قسط صفر إطلاقاً — عكس السلوك القديم الذي كان يجعل الأخير صفراً)
        val rows = InstallmentEngine.buildSchedule(240L, 25, dayOf(2026, 1, 10))
        assertEquals(25, rows.size)
        assertEquals(240L, rows.sumOf { it.amount })
        assertEquals(10L, rows[0].amount)
        assertEquals(9L, rows.last().amount)
        // [P33-P8] بلا عتبة 0.005 — كل قسط قرش صحيح واحد على الأقل (مقارنة تامة)
        rows.forEach { assertTrue("amount=${it.amount}", it.amount >= 1L) }
    }

    @Test
    fun `status detection due late partial paid`() {
        val due = dayOf(2026, 6, 10)
        val today = dayOf(2026, 6, 1)
        assertEquals(InstallmentEngine.St.DUE,
            InstallmentEngine.statusOf(10_000L, 0L, due, today))
        assertEquals(InstallmentEngine.St.PARTIAL,
            InstallmentEngine.statusOf(10_000L, 4_000L, due, today))
        assertEquals(InstallmentEngine.St.PAID,
            InstallmentEngine.statusOf(10_000L, 10_000L, due, today))
        assertEquals(InstallmentEngine.St.LATE,
            InstallmentEngine.statusOf(10_000L, 0L, due, dayOf(2026, 6, 15)))
        // [P33-P8] الدفع الزائد يبقى مدفوعاً — paid >= amount بمساواة صحيحة تامة
        assertEquals(InstallmentEngine.St.PAID,
            InstallmentEngine.statusOf(10_000L, 10_500L, due, today))
    }

    @Test
    fun `stats aggregates progress and late amounts`() {
        val d1 = dayOf(2026, 1, 10)
        val d2 = dayOf(2026, 2, 10)
        val d3 = dayOf(2026, 3, 10)
        val rows = listOf(
            row(1, 30_000L, 30_000L, d1),                 // مدفوع
            row(2, 30_000L, 10_000L, d2),                 // جزئي (متجاوز موعده → متأخر)
            row(3, 30_000L, 0L, d3)                       // مستحق
        )
        val today = dayOf(2026, 2, 20)
        val s = InstallmentEngine.stats(rows, today)
        assertEquals(40_000L, s.paid)
        assertEquals(50_000L, s.remaining)
        assertEquals(1, s.lateCount)
        assertEquals(20_000L, s.lateAmount)
        assertEquals(40_000.0 / 90_000.0, s.progress, 1e-9) // 40_000 من أصل 90_000 (مجموع الجدول) — النسب تبقى Double
        assertTrue(!s.done)
        assertEquals(2, s.nextDue?.seq ?: 0) // التالي غير المسدد بالترتيب هو القسط رقم 2
    }

    @Test
    fun `done plan has zero remaining`() {
        val rows = listOf(
            row(1, 50_000L, 50_000L, dayOf(2026, 1, 1)),
            row(2, 50_000L, 50_000L, dayOf(2026, 2, 1))
        )
        val s = InstallmentEngine.stats(rows, dayOf(2026, 3, 1))
        assertEquals(0L, s.remaining)
        assertTrue(s.done)
        assertEquals(null, s.nextDue)
    }

    @Test
    fun `dueWithin finds open installments in window`() {
        val rows = listOf(
            row(1, 20_000L, 0L, dayOf(2026, 5, 5)),
            row(2, 20_000L, 20_000L, dayOf(2026, 5, 15)),
            row(3, 20_000L, 0L, dayOf(2026, 6, 5))
        )
        val found = InstallmentEngine.dueWithin(
            rows, dayOf(2026, 5, 1), dayOf(2026, 5, 30)
        )
        assertEquals(1, found.size)
        assertEquals(1, found[0].seq)
    }

    // [P33-P8] المبالغ قروش Long — كانت ريال Double
    private fun row(seq: Int, amount: Long, paid: Long, due: Long) = object : InstallmentEngine.ScheduleRow {
        override val seq = seq
        override val amount = amount
        override val paidAmount = paid
        override val dueDate = due
    }
}
