package com.superbiz.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P41-L1] اختبارات المحرك النقي لحقول السطر الضريبية (v11 — جولة 2).
 *
 * العقد المجرَّب:
 *  - معرفات الفئات S/Z/E ثابتة (العقد المشترك مع ZatcaInvoiceXml).
 *  - الصفرية/المعفاة ضريبتها صفر دائماً بلا استثناء — والفرق خانة إقرار لا حساب.
 *  - النسبة الصريحة تُحصر 0..100 (حرس دفاعي)، والوراثة (-1) تلتقط نسبة الرأس.
 *  - تقريب الضريبة نفس حرف التاريخ: Math.round(net × rate / 100).
 *  - invoiceTaxFromLines يعيد null عند غياب أي سطر صريح ⇒ المسار التاريخي
 *    على مستوى الرأس لا يُلمس (عقد التوافق الجذري للموجة).
 */
class LineTaxP41Test {

    // ── معرفات الفئات ──
    @Test
    fun categoryId_fixedMapping() {
        assertEquals("S", LineTaxP41.categoryId(LineTaxP41.KIND_STANDARD))
        assertEquals("Z", LineTaxP41.categoryId(LineTaxP41.KIND_ZERO))
        assertEquals("E", LineTaxP41.categoryId(LineTaxP41.KIND_EXEMPT))
        assertEquals("قيمة غير معروفة تنحازر إلى القياسية", "S", LineTaxP41.categoryId(99))
    }

    // ── الكشف عن الصراحة ──
    @Test
    fun isExplicit_kindOrRateDrivesIt() {
        assertFalse("الصف التاريخي الحرفي (0, -1) ليس صريحاً", LineTaxP41.isExplicit(0, -1.0))
        assertTrue("فئة صفرية صريحة ولو بنسبة وراثة", LineTaxP41.isExplicit(LineTaxP41.KIND_ZERO, -1.0))
        assertTrue("فئة معفاة صريحة ولو بنسبة وراثة", LineTaxP41.isExplicit(LineTaxP41.KIND_EXEMPT, -1.0))
        assertTrue("نسبة معلنة تصرّح السطر القياسي", LineTaxP41.isExplicit(0, 15.0))
    }

    @Test
    fun isLineAware_anyExplicitLineOpensThePath() {
        assertFalse(LineTaxP41.isLineAware(emptyList()))
        assertFalse("كل الصفوف التاريخية ⇒ مسار الرأس", LineTaxP41.isLineAware(listOf(0 to -1.0, 0 to -1.0)))
        assertTrue("سطر صريح واحد يكفي", LineTaxP41.isLineAware(listOf(0 to -1.0, 2 to -1.0)))
    }

    // ── النسبة الفعالة ──
    @Test
    fun effectiveRate_zeroAndExemptAlwaysZero() {
        assertEquals(0.0, LineTaxP41.effectiveRate(LineTaxP41.KIND_ZERO, 15.0, 15.0), 0.0)
        assertEquals(0.0, LineTaxP41.effectiveRate(LineTaxP41.KIND_EXEMPT, 15.0, 15.0), 0.0)
        assertEquals("حتى مع نسبة صريحة مخالفة", 0.0, LineTaxP41.effectiveRate(LineTaxP41.KIND_ZERO, 5.0, 15.0), 0.0)
    }

    @Test
    fun effectiveRate_explicitAndInherit_andClamp() {
        assertEquals(5.0, LineTaxP41.effectiveRate(0, 5.0, 15.0), 0.0)
        assertEquals("الوراثة تلتقط نسبة الرأس", 15.0, LineTaxP41.effectiveRate(0, -1.0, 15.0), 0.0)
        assertEquals("حرس دفاعي: نسبة فوق 100 تُحصر", 100.0, LineTaxP41.effectiveRate(0, 150.0, 15.0), 0.0)
        assertEquals("أي سالب = وراثة (عقد البذرة -1.0)", 15.0, LineTaxP41.effectiveRate(0, -0.5, 15.0), 0.0)
        assertEquals("الصفرية تسبق أي شيء", 0.0, LineTaxP41.effectiveRate(LineTaxP41.KIND_ZERO, 150.0, 15.0), 0.0)
    }

    // ── ضريبة السطر ──
    @Test
    fun lineTax_matchesHistoricalRoundingCharacter() {
        assertEquals("10000 × 15% = 1500 تامة", 1500L, LineTaxP41.lineTax(10_000L, 0, -1.0, 15.0))
        assertEquals("10005 × 15% = 1500.75 تقرب نصف الوحدة للأعلى", 1501L, LineTaxP41.lineTax(10_005L, 0, -1.0, 15.0))
        assertEquals("1001 × 15% = 150.15 تنزل", 150L, LineTaxP41.lineTax(1_001L, 0, -1.0, 15.0))
        assertEquals("نسبة صريحة 5% على 2000", 100L, LineTaxP41.lineTax(2_000L, 0, 5.0, 15.0))
        assertEquals("الصفرية صفر", 0L, LineTaxP41.lineTax(10_000L, LineTaxP41.KIND_ZERO, -1.0, 15.0))
        assertEquals("المعفاة صفر", 0L, LineTaxP41.lineTax(10_000L, LineTaxP41.KIND_EXEMPT, -1.0, 15.0))
    }

    // ── ضريبة الفاتورة من أسطرها ──
    @Test
    fun invoiceTaxFromLines_nullWithoutExplicitLine() {
        assertNull("لا سطر صريح ⇒ المسار التاريخي على الرأس", LineTaxP41.invoiceTaxFromLines(listOf(10_000L), listOf(0), listOf(-1.0), 15.0))
        assertNull("طول القوائم غير متطابق ⇒ حرس دفاعي", LineTaxP41.invoiceTaxFromLines(listOf(10_000L, 500L), listOf(0), listOf(-1.0), 15.0))
    }

    @Test
    fun invoiceTaxFromLines_mixedInvoiceSumsPerLine() {
        val tax = LineTaxP41.invoiceTaxFromLines(
            lineNets = listOf(2_000L, 500L, 700L),
            kinds = listOf(0, LineTaxP41.KIND_ZERO, LineTaxP41.KIND_EXEMPT),
            rates = listOf(-1.0, -1.0, -1.0),
            settingsRate = 15.0,
        )
        assertEquals("قياسية 300 + صفرية 0 + معفاة 0", 300L, tax)
    }

    @Test
    fun invoiceTaxFromLines_explicitRateLineParticipates() {
        val tax = LineTaxP41.invoiceTaxFromLines(
            lineNets = listOf(1_000L, 2_000L),
            kinds = listOf(0, 0),
            rates = listOf(5.0, -1.0),
            settingsRate = 15.0,
        )
        assertEquals("سطر 5% (50) + سطر وراثة 15% (300)", 350L, tax)
    }
}
