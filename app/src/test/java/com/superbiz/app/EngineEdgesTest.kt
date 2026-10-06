package com.superbiz.app

import com.superbiz.app.domain.InstallmentEngine
import com.superbiz.app.domain.analytics.agingBuckets
import com.superbiz.app.util.Dates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * توحيد — حواف المحركات من خط التدقيق
 * M-2.9 جدولة أقساط بلا حالات منحلة (تمويل ضئيل/NaN/أشهر < 1)،
 * M-2.5 أيام التأخير بالتقريب لأعلى وسلات أعمار متطابقة مع كشف «متأخر».
 *
 * [P33-P8] الجدولة صارت قروش Long: قيم الهللات القديمة هي نفسها قروش اليوم
 * (المحرك القديم كان يضرب في 100 إلى هللات عند التدني)، فالاختبارات تحمل أسماءها
 * وقيمها الرقمية نفسها بصيغة صحيحة — والمقارنات كلها تامة بلا 1e-9 ولا عتبات.
*/
class EngineEdgesTest {

    private val day0 = 1_760_000_000_000L // أي تاريخ ثابت

    // ─── M-2.9: حواف buildSchedule ───

    @Test
    fun `tiny financing below half halala yields one installment carrying the raw amount`() {
        // [P33-P8] ما دون نصف هللة لم يعد يمثَّل أصلاً — طبقة التحويل Money.toPiasters
        // تُنزّله صفراً قبل أن يصل للمحرك، فأصغر مبلغ حيّ هو القرش الواحد، وهو لا يحتمل
        // قسمة على 6 فيسقط قسطاً واحداً يحمل المبلغ الخام كاملاً
        val rows = InstallmentEngine.buildSchedule(1L, 6, day0)
        assertEquals(1, rows.size)
        assertEquals(1, rows[0].seq)
        assertEquals(1L, rows[0].amount)
    }

    @Test
    fun `tiny financing that cannot split yields single full installment`() {
        // 1 قرش على 3 أشهر: base = 1/3 = 0 → قسط واحد يحمل المبلغ — لا أقساط صفريّة أبداً
        // (كان 0.006 ريال → round2 = 0.01 → base = 0.0 بنفس السقوط)
        val rows = InstallmentEngine.buildSchedule(1L, 3, day0)
        assertEquals(1, rows.size)
        assertEquals(1L, rows[0].amount)
    }

    @Test
    fun `months zero is rejected explicitly`() {
        try {
            InstallmentEngine.buildSchedule(10_000L, 0, day0)
            fail("months < 1 يجب أن يُرفض صراحةً")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("months"))
        }
    }

    @Test
    fun `negative financing yields an empty schedule instead of throwing`() {
        // [P33-P8] كان المحرك يرفض NaN/غير نهي صراحةً — بلا فاصلة عائمة لم يعد ذلك
        // ممكناً أصلاً (Long لا يكون NaN)، والسالب/الصفر يُسقطان الجدول بسلامة بلا أي قسط
        assertTrue(InstallmentEngine.buildSchedule(-1L, 3, day0).isEmpty())
        assertTrue(InstallmentEngine.buildSchedule(0L, 3, day0).isEmpty())
    }

    @Test
    fun `schedule still sums to financed amount`() {
        val rows = InstallmentEngine.buildSchedule(100_000L, 3, day0)
        assertEquals(3, rows.size)
        assertEquals(100_000L, rows.sumOf { it.amount }) // [P33-P8] مساواة تامة بلا 1e-9
    }

    // ─── M-2.5: أيام التأخير بالتقريب لأعلى ───

    @Test
    fun `days overdue counts any overdue as one day`() {
        val due = day0
        val now = due + 3_600_000L // ساعة واحدة بعد الاستحقاق
        assertEquals(1, Dates.daysOverdue(due, now))
    }

    @Test
    fun `days overdue is zero for future and exact boundary`() {
        assertEquals(0, Dates.daysOverdue(day0, day0))
        assertEquals(0, Dates.daysOverdue(day0, day0 - 1))
        assertEquals(1, Dates.daysOverdue(day0, day0 + 86_400_000L))
        // يومان + 1ms → تجاوز على الحد الصحيح يفتح يوماً جديداً (ceil صارم — دلالة M-2.5)
        assertEquals(3, Dates.daysOverdue(day0, day0 + 2 * 86_400_000L + 1))
    }

    @Test
    fun `aging buckets match overdue semantics`() {
        val today = day0 + 100L * 86_400_000L
        val buckets = agingBuckets(
            listOf(
                100.0 to today - 3_600_000L,      // متأخرة بساعة — ceil = 1 يوم → سلة 0
                200.0 to today - 45L * 86_400_000L, // 45 يوماً → سلة 1
                300.0 to today - 95L * 86_400_000L, // 95 يوماً → سلة 3
                50.0 to today + 4L * 86_400_000L    // مستحقة لاحقاً → سلة 0
            ),
            todayMs = today
        )
        assertEquals(150.0, buckets[0], 1e-9)
        assertEquals(200.0, buckets[1], 1e-9)
        assertEquals(0.0, buckets[2], 1e-9)
        assertEquals(300.0, buckets[3], 1e-9)
    }
}
