package com.superbiz.app

import com.superbiz.app.domain.ZatcaReturnP38
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * اختبارات [P38-Z3] — محرك تقرير الضريبة الدوري النقي (أساس الإقرار).
 * كل المبالغ قروش Long — الجمع مساواة صحيحة تامة بلا تقريب.
 */
class ZatcaReturnP38Test {

    @Test
    fun empty_inputs_produceZeroReport() {
        val r = ZatcaReturnP38.build(emptyList(), emptyList())
        assertEquals(0L, r.salesNet)
        assertEquals(0L, r.salesZeroNet)
        assertEquals(0L, r.outputVat)
        assertEquals(0L, r.purchasesNet)
        assertEquals(0L, r.inputVat)
        assertEquals(0L, r.netVat)
        assertEquals(0, r.byRate.size)
    }

    @Test
    fun ratesAggregate_sortedDescending_byRateOnlyTaxable() {
        val sales = listOf(
            ZatcaReturnP38.SaleRow(15.0, 10_000, 1_500),
            ZatcaReturnP38.SaleRow(5.0, 2_000, 100),
            ZatcaReturnP38.SaleRow(0.0, 500, 0),      // صفرية/معفاة
            ZatcaReturnP38.SaleRow(15.0, 30_000, 4_500),
        )
        val r = ZatcaReturnP38.build(sales, emptyList())
        assertEquals(42_500L, r.salesNet)          // 10000+2000+500+30000
        assertEquals(500L, r.salesZeroNet)         // النسبة صفر وحدها
        assertEquals(6_100L, r.outputVat)          // 1500+100+4500 (بلا الصفرية)
        assertEquals(listOf(15.0, 5.0), r.byRate.map { it.rate })
        assertEquals(40_000L, r.byRate[0].net)
        assertEquals(6_000L, r.byRate[0].vat)
        assertEquals(2_000L, r.byRate[1].net)
        assertEquals(100L, r.byRate[1].vat)
    }

    @Test
    fun purchasesFeedInputVat_andNetIsExact() {
        val sales = listOf(ZatcaReturnP38.SaleRow(15.0, 20_000, 3_000))
        val purchases = listOf(
            ZatcaReturnP38.PurchRow(8_000, 1_200),
            ZatcaReturnP38.PurchRow(4_000, 600),
        )
        val r = ZatcaReturnP38.build(sales, purchases)
        assertEquals(12_000L, r.purchasesNet)
        assertEquals(1_800L, r.inputVat)
        assertEquals(3_000L, r.outputVat)
        assertEquals(1_200L, r.netVat)             // 3000 − 1800 تامة
    }

    @Test
    fun refundCase_negativeNetPreserved() {
        // المدخلات أكبر من المخرجات — استرداد مستحق: الصافي سالب بلا قصّ
        val sales = listOf(ZatcaReturnP38.SaleRow(15.0, 2_000, 300))
        val purchases = listOf(ZatcaReturnP38.PurchRow(20_000, 3_000))
        val r = ZatcaReturnP38.build(sales, purchases)
        assertEquals(-2_700L, r.netVat)
    }

    @Test
    fun discountAlreadyNetted_noDoubleCount() {
        // net يمرر «بعد الخصم» من القارئ (subtotal − discount) — المحرك لا يخصم ثانية
        val sales = listOf(ZatcaReturnP38.SaleRow(15.0, 9_500, 1_425))
        val r = ZatcaReturnP38.build(sales, emptyList())
        assertEquals(9_500L, r.salesNet)
        assertEquals(1_425L, r.outputVat)
    }

    @Test
    fun tinyRatesAndLongSums_stayExact() {
        // مبالغ كبرى (≥ 10^9 قروش) — عقد الحدود المالي
        val sales = listOf(ZatcaReturnP38.SaleRow(15.0, 2_000_000_000L, 300_000_000L))
        val r = ZatcaReturnP38.build(sales, emptyList())
        assertEquals(2_000_000_000L, r.salesNet)
        assertEquals(300_000_000L, r.outputVat)
    }
}
