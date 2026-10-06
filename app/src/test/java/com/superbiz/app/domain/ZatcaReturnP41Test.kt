package com.superbiz.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P41-L2] اختبارات فصل الصفرية عن المعفاة في تقرير الضريبة الدوري (v11).
 *
 * العقد المجرَّب:
 *  - الصفوف التاريخية (kind افتراضي 0 وrate = 0.0) تبقى في خانة **الصفرية**
 *    كما كانت موحّدة منذ P38 — تقارير P38 القائمة تعطي نفس أرقامها حرفياً.
 *  - فئة صريحة KIND_EXEMPT وحدها تملأ خانة المعفاة.
 *  - ضريبة المخرجات تُجمع من الصفوف الخاضعة فقط (قياسية بنسبة ≠ 0) حتى لو
 *    تسربت ضريبة غير صفرية في صف صفرية/معفاة.
 *  - salesNet يشمل الخزانات الثلاث جميعاً، وbyRate للخاضعة فقط تنازلياً.
 */
class ZatcaReturnP41Test {

    @Test
    fun legacyZeroRate_staysInZeroBucket_exemptStaysEmpty() {
        val r = ZatcaReturnP38.build(
            sales = listOf(
                ZatcaReturnP38.SaleRow(15.0, 10_000, 1_500),
                ZatcaReturnP38.SaleRow(0.0, 500, 0),      // تاريخية: صفرية/معفاة موحّدة
            ),
            purchases = emptyList(),
        )
        assertEquals("الخانة الموحدة التاريخية صفرية كما في P38", 500L, r.salesZeroNet)
        assertEquals("المعفاة لا تُملأ بلا فئة صريحة", 0L, r.salesExemptNet)
        assertEquals(10_500L, r.salesNet)
        assertEquals(1_500L, r.outputVat)
    }

    @Test
    fun explicitZeroAndExempt_splitIntoIndependentBuckets() {
        val r = ZatcaReturnP38.build(
            sales = listOf(
                ZatcaReturnP38.SaleRow(0.0, 700, 0, LineTaxP41.KIND_ZERO),
                ZatcaReturnP38.SaleRow(0.0, 300, 0, LineTaxP41.KIND_EXEMPT),
            ),
            purchases = emptyList(),
        )
        assertEquals(700L, r.salesZeroNet)
        assertEquals(300L, r.salesExemptNet)
        assertEquals(1_000L, r.salesNet)
        assertEquals(0L, r.outputVat)
        assertTrue(r.byRate.isEmpty())
    }

    @Test
    fun mixedKinds_outputVatFromStandardOnly_evenIfVatLeaked() {
        val r = ZatcaReturnP38.build(
            sales = listOf(
                ZatcaReturnP38.SaleRow(15.0, 2_000, 300),
                ZatcaReturnP38.SaleRow(0.0, 500, 0, LineTaxP41.KIND_ZERO),
                ZatcaReturnP38.SaleRow(0.0, 700, 0, LineTaxP41.KIND_EXEMPT),
            ),
            purchases = emptyList(),
        )
        assertEquals("المخرجات من الخاضعة فقط", 300L, r.outputVat)
        assertEquals(500L, r.salesZeroNet)
        assertEquals(700L, r.salesExemptNet)
        assertEquals("المجموع يشمل الثلاث", 3_200L, r.salesNet)
        assertEquals(300L, r.netVat)
    }

    @Test
    fun standardRows_groupByRateDescending_includingExplicitLineRates() {
        val r = ZatcaReturnP38.build(
            sales = listOf(
                ZatcaReturnP38.SaleRow(15.0, 10_000, 1_500),
                ZatcaReturnP38.SaleRow(5.0, 2_000, 100),   // سطر بنسبة صريحة (v11)
                ZatcaReturnP38.SaleRow(15.0, 30_000, 4_500),
            ),
            purchases = emptyList(),
        )
        assertEquals(listOf(15.0, 5.0), r.byRate.map { it.rate })
        assertEquals(40_000L, r.byRate[0].net)
        assertEquals(6_000L, r.byRate[0].vat)
        assertEquals(2_000L, r.byRate[1].net)
        assertEquals(100L, r.byRate[1].vat)
    }

    @Test
    fun zeroAndExempt_doNotEnterByRate() {
        val r = ZatcaReturnP38.build(
            sales = listOf(
                ZatcaReturnP38.SaleRow(0.0, 400, 0, LineTaxP41.KIND_ZERO),
                ZatcaReturnP38.SaleRow(0.0, 600, 0, LineTaxP41.KIND_EXEMPT),
                ZatcaReturnP38.SaleRow(0.0, 800, 0),       // تاريخية موحّدة
            ),
            purchases = emptyList(),
        )
        assertTrue("لا صف نسب لغير الخاضعة", r.byRate.isEmpty())
        assertEquals("الصفرية = صريحة + تاريخية", 1_200L, r.salesZeroNet)
        assertEquals(600L, r.salesExemptNet)
    }

    @Test
    fun purchasesUnaffected_byLineKindWave() {
        val r = ZatcaReturnP38.build(
            sales = emptyList(),
            purchases = listOf(ZatcaReturnP38.PurchRow(4_000, 600)),
        )
        assertEquals(4_000L, r.purchasesNet)
        assertEquals(600L, r.inputVat)
        assertEquals(-600L, r.netVat)
        assertEquals(0L, r.salesExemptNet)
    }
}
