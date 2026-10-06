package com.superbiz.app

import com.superbiz.app.data.db.Invoice
import com.superbiz.app.widget.SalesMath
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * — اختبارات منطق ويدجت «مبيعات اليوم»
 * عزل فواتير البيع النشطة لليوم فقط + حساب ربح البيع الإجمالي.
 * [P33-P8] الإجماليات قروش Long — مقارنات مساواة تامة بلا عتبات 0.001.
*/
class SalesMathTest {

    // يوم مرجعي يبدأ من منتصف الليل (مضاعف 24 ساعة) — لا يعتمد على المنطقة الزمنية
    private val dayStart = 86_400_000L * 100
    private val noon = dayStart + 12 * 3_600_000L
    private val dayEnd = dayStart + 86_399_999L
    private val nextDay = dayStart + 86_400_000L

    // [P33-P8] المبالغ قروش Long (50.25 ريال = 5025L) — taxRate/fxRate النسب تبقى Double
    private fun inv(date: Long, total: Long, type: Int = 0, status: Int = 0) = Invoice(
        number = "INV-$total",
        partyId = 1L,
        type = type,
        date = date,
        dueDate = date,
        subtotal = total,
        total = total
    ).copy(status = status)

    @Test
    fun `sums only today's active sale invoices`() {
        val invoices = listOf(
            inv(noon, 10_000L),                     // بيع اليوم — يُحتسب
            inv(dayEnd, 5_025L),                    // آخر ملّي ثانية من اليوم — يُحتسب
            inv(nextDay, 99_900L),                  // بيع الغد — يُستثنى
            inv(dayStart - 1, 88_800L),             // بيع الأمس — يُستثنى
            inv(noon, 7_000L, type = 1),            // شراء — يُستثنى
            inv(noon, 6_000L, status = 3)           // فاتورة ملغاة — يُستثنى
        )
        val (total, count) = SalesMath.todaySales(invoices, dayStart)
        assertEquals(15_025L, total) // [P33-P8] 100.00 + 50.25 ريال = 15025 قرشاً تام (كان بعتبة 0.001)
        assertEquals(2, count)
    }

    @Test
    fun `empty ledger yields zero sales`() {
        val (total, count) = SalesMath.todaySales(emptyList(), dayStart)
        assertEquals(0L, total)
        assertEquals(0, count)
    }

    @Test
    fun `gross profit is revenue minus cogs exact`() {
        // [P33-P8] الطرح صحيح تام — لا تقريب ولا عتبة 0.001
        assertEquals(11_000L, SalesMath.grossProfit(revenue = 15_000L, cogs = 4_000L))
        assertEquals(-1_250L, SalesMath.grossProfit(revenue = 10_000L, cogs = 11_250L))
        assertEquals(0L, SalesMath.grossProfit(revenue = 0L, cogs = 0L))
    }

    @Test
    fun `boundary invoice exactly at day start counts`() {
        val (total, count) = SalesMath.todaySales(listOf(inv(dayStart, 2_500L)), dayStart)
        assertEquals(2_500L, total)
        assertEquals(1, count)
    }
}
