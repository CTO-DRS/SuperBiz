package com.superbiz.app

import com.superbiz.app.domain.ExpenseAlert
import com.superbiz.app.domain.ExpenseTemplates
import com.superbiz.app.print.EscPos
import com.superbiz.app.print.ReceiptPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * : اختبارات موجة المصروفات + الأتمتة (R6-P4-4a) —
 * كاشف قوالب المصروفات المتكررة (وظيفة 31): التكرار، نافذة الـ60 يوماً،
 * تطبيع trim/case، الفئات والمبالغ، الفراغ —
 * وحالات إنذار حد المصروف (وظيفة 32): 0/79/80/99/100% —
 * ومعاينة الإيصال النصية (وظيفة 36) بمطابقة تخطيط EscPos القائم.
*/
class ExpensesAutoP4Test {

    private val now = 1_700_000_000_000L
    private val day = 86_400_000L

    private fun e(
        note: String,
        category: String = "أخرى",
        amount: Double = 10.0,
        daysAgo: Long
    ) = ExpenseTemplates.ExpenseLike(
        note = note, category = category, amount = amount, date = now - daysAgo * day
    )

    // ══ وظيفة 31 — كاشف القوالب المتكررة ══

    @Test
    fun detect_groupsRepeatedDescriptions_orderedByCountThenRecency() {
        val templates = ExpenseTemplates.detect(
            listOf(
                e("علف خرفة", "مستلزمات", 20.0, daysAgo = 50),
                e("علف خرفة", "مستلزمات", 22.0, daysAgo = 20),
                e("علف خرفة", "مستلزمات", 25.0, daysAgo = 2),
                e("فاتورة كهرباء", "كهرباء وماء", 100.0, daysAgo = 30),
                e("فاتورة كهرباء", "كهرباء وماء", 95.0, daysAgo = 3)
            ),
            now = now
        )
        assertEquals(2, templates.size)
        // الأكثر تكراراً أولاً
        assertEquals("علف خرفة", templates[0].description)
        assertEquals(3, templates[0].count)
        // المبلغ من آخر استخدام
        assertEquals(25.0, templates[0].amount, 0.001)
        assertEquals("مستلزمات", templates[0].category)
        assertEquals("علف خرفة", templates[0].note)
        assertEquals("فاتورة كهرباء", templates[1].description)
        assertEquals(2, templates[1].count)
        assertEquals(95.0, templates[1].amount, 0.001)
    }

    @Test
    fun detect_ignoresOutside60DayWindow_boundaryInclusive() {
        // الحد شامل: 60 يوماً بالضبط يُحتسب
        val inclusive = ExpenseTemplates.detect(
            listOf(
                e("اشتراك نت", "اتصالات وإنترنت", 99.0, daysAgo = 60),
                e("اشتراك نت", "اتصالات وإنترنت", 99.0, daysAgo = 10)
            ),
            now = now
        )
        assertEquals(1, inclusive.size)
        assertEquals(2, inclusive[0].count)

        // خارج النافذة (61 يوماً) لا يُحتسب → تكرار واحد فقط → لا قالب
        val outside = ExpenseTemplates.detect(
            listOf(
                e("اشتراك نت", "اتصالات وإنترنت", 99.0, daysAgo = 61),
                e("اشتراك نت", "اتصالات وإنترنت", 99.0, daysAgo = 10)
            ),
            now = now
        )
        assertTrue(outside.isEmpty())
    }

    @Test
    fun detect_mergesAfterTrimSpacesAndCase_displaysLatestCleaned() {
        val templates = ExpenseTemplates.detect(
            listOf(
                e("  water   bill ", "كهرباء وماء", 80.0, daysAgo = 9),
                e("water bill", "كهرباء وماء", 82.0, daysAgo = 5),
                e("WATER BILL", "كهرباء وماء", 84.0, daysAgo = 1)
            ),
            now = now
        )
        assertEquals(1, templates.size)
        assertEquals(3, templates[0].count)
        // الوصف المعروض منظَّف من آخر استخدام (trim + مسافات مفردة)
        assertEquals("WATER BILL", templates[0].description)
        assertEquals(84.0, templates[0].amount, 0.001)
    }

    @Test
    fun detect_categoryMostFrequent_tiePrefersLatest_amountFromLatest() {
        val templates = ExpenseTemplates.detect(
            listOf(
                // الفئة الأكثر تكراراً تفوز وإن لم تكن الأحدث
                e("قرطاسية", "مستلزمات", 15.0, daysAgo = 30),
                e("قرطاسية", "مستلزمات", 16.0, daysAgo = 20),
                e("قرطاسية", "أخرى", 17.0, daysAgo = 1),
                // التعادل → الفئة الأحدث
                e("شاي وقهوة", "مستلزمات", 30.0, daysAgo = 25),
                e("شاي وقهوة", "ضيافة", 31.0, daysAgo = 5)
            ),
            now = now
        )
        assertEquals(2, templates.size)
        assertEquals("مستلزمات", templates[0].category)
        assertEquals(17.0, templates[0].amount, 0.001)
        assertEquals("ضيافة", templates[1].category)
    }

    @Test
    fun detect_emptyAndSingleOccurrence_andCategoryFallbackForBlankNotes() {
        // فراغ كامل → فراغ صادق
        assertTrue(ExpenseTemplates.detect(emptyList(), now = now).isEmpty())
        // استخدام واحد لا يكفي
        assertTrue(
            ExpenseTemplates.detect(listOf(e("مرة واحدة", "أخرى", 5.0, daysAgo = 1)), now = now).isEmpty()
        )
        // بلا ملاحظة ولا فئة → يُتجاهل
        assertTrue(
            ExpenseTemplates.detect(
                listOf(e("", "", 5.0, daysAgo = 1), e("", "", 6.0, daysAgo = 2)),
                now = now
            ).isEmpty()
        )
        // ملاحظة فارغة تُجمَّع على الفئة — والملاحظة المعبَّأة تبقى فارغة في القالب
        val fromCategory = ExpenseTemplates.detect(
            listOf(
                e("", "كهرباء وماء", 100.0, daysAgo = 15),
                e("", "كهرباء وماء", 110.0, daysAgo = 2)
            ),
            now = now
        )
        assertEquals(1, fromCategory.size)
        assertEquals("كهرباء وماء", fromCategory[0].description)
        assertEquals("", fromCategory[0].note)
        assertEquals(110.0, fromCategory[0].amount, 0.001)
    }

    // ══ وظيفة 32 — حالات إنذار حد المصروف ══

    @Test
    fun alert_noLimitReturnsNull_honestHide() {
        assertNull(ExpenseAlert.state(100.0, 0.0))                       // بلا حد
        assertNull(ExpenseAlert.state(100.0, -5.0))                      // حد غير صالح
        assertNull(ExpenseAlert.state(100.0, Double.NaN))                // غير سالم
        assertNull(ExpenseAlert.state(100.0, Double.POSITIVE_INFINITY))  // غير سالم
    }

    @Test
    fun alert_boundaries_okWarnOver() {
        // حد = 300
        val ok0 = ExpenseAlert.state(0.0, 300.0)!!
        assertEquals(ExpenseAlert.Level.OK, ok0.level)
        assertEquals(0, ok0.pct)

        assertEquals(ExpenseAlert.Level.OK, ExpenseAlert.state(237.0, 300.0)!!.level)   // 79%
        assertEquals(ExpenseAlert.Level.WARN, ExpenseAlert.state(240.0, 300.0)!!.level) // 80% بالضبط
        assertEquals(ExpenseAlert.Level.WARN, ExpenseAlert.state(297.0, 300.0)!!.level) // 99%
        assertEquals(ExpenseAlert.Level.OVER, ExpenseAlert.state(300.0, 300.0)!!.level) // 100% بالضبط
        assertEquals(ExpenseAlert.Level.OVER, ExpenseAlert.state(450.0, 300.0)!!.level) // 150%
    }

    @Test
    fun alert_percentIsFloored() {
        assertEquals(79, ExpenseAlert.state(239.9, 300.0)!!.pct)  // 79.96 → 79 (ما زال OK)
        assertEquals(ExpenseAlert.Level.OK, ExpenseAlert.state(239.9, 300.0)!!.level)
        assertEquals(80, ExpenseAlert.state(241.2, 300.0)!!.pct)  // 80.4 → 80 (WARN)
        assertEquals(ExpenseAlert.Level.WARN, ExpenseAlert.state(241.2, 300.0)!!.level)
        assertEquals(99, ExpenseAlert.state(299.4, 300.0)!!.pct)  // 99.8 → 99 (ليس 100)
    }

    @Test
    fun alert_negativeTotalTreatedAsZero() {
        val st = ExpenseAlert.state(-50.0, 300.0)!!
        assertEquals(ExpenseAlert.Level.OK, st.level)
        assertEquals(0, st.pct)
    }

    // ══ وظيفة 36 — معاينة الإيصال النصية (بلا أوامر تحكم) ══

    @Test
    fun receiptPreview_mirrorsEscPosLayout_withinPaperWidth() {
        val receipt = EscPos.Receipt(
            businessName = "متجر النور",
            title = "فاتورة بيع • INV-1",
            dateText = "01/05/2025 10:00",
            partyName = "أحمد",
            lines = listOf(
                EscPos.ItemLine("شاي", "2", "5.00", "10.00"),
                EscPos.ItemLine("رسوم خدمة", "", "", "3.00")
            ),
            totals = listOf("المجموع" to "13.00", "الإجمالي" to "13.00"),
            statusText = "مدفوعة",
            footer = "شكراً لزيارتكم"
        )
        val preview = ReceiptPreview.text(receipt, width = 32)
        val lines = preview.lines()

        // الترويسة ممركزة (بلا أوامر — مسافات فقط) وتقطّع الأسطر مطابق للمولّد
        assertEquals("متجر النور", lines.first().trim())
        assertTrue(lines.contains("-".repeat(32)))
        // سطر الكمية × السعر مبطَّن بعرض الورق كاملاً
        val qtyRow = lines.first { it.startsWith("2 × 5.00") }
        assertEquals(32, qtyRow.length)
        assertTrue(qtyRow.endsWith("10.00"))
        // السطر المفرد «بيان ..... قيمة» بعرض الورق كاملاً
        val plainRow = lines.first { it.startsWith("رسوم خدمة") }
        assertEquals(32, plainRow.length)
        assertTrue(plainRow.endsWith("3.00"))
        // الإجماليات موجودة قبل الحالة والتذييل
        assertTrue(lines.indexOfFirst { it.contains("المجموع") } < lines.indexOfFirst { it.trim() == "مدفوعة" })
        assertTrue(lines.last().trim() == "شكراً لزيارتكم")
        // كل الأسطر مقيَّدة بعرض الورق (32 حرفاً)
        assertTrue(lines.all { it.length <= 32 })
    }
}
