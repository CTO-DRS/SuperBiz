package com.superbiz.app

import com.superbiz.app.domain.algo.R18Kpi
import com.superbiz.app.domain.export.WebhookKit
import com.superbiz.app.print.InvoiceTemplate
import com.superbiz.app.ui.adaptive.AdaptiveMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات القطع النقية لدفعة V 3.0: النبض (H4-7) والويب هوك (H4-6) والتكيف (H4-4). */
class V30PureTest {

    // ───────── R18Kpi — بطاقة المؤشرات الكاملة (H4-7) ─────────

    @Test
    fun `weights sum to exactly 100 — contract`() {
        assertEquals(100, R18Kpi.WEIGHTS.values.sum())
    }

    @Test
    fun `no inputs — no pulse (honest emptiness)`() {
        assertNull(
            R18Kpi.pulse(
                R18Kpi.Input(0, 0, 0, 0, 0, 0, null, 15, 30)
            )
        )
    }

    @Test
    fun `perfect month scores high — failing month scores low`() {
        val good = R18Kpi.pulse(
            R18Kpi.Input(
                salesMonthPiasters = 60_000_000, profitMonthPiasters = 30_000_000,
                expensesMonthPiasters = 0, overduePiasters = 0,
                lowStockCount = 0, totalProducts = 50,
                cash90NetPiasters = 150_000_000, 15, 30
            )
        )!!
        assertTrue("good=${good.score}", good.score >= 75)
        assertEquals(0, good.verdict)

        val bad = R18Kpi.pulse(
            R18Kpi.Input(
                salesMonthPiasters = 6_000_000, profitMonthPiasters = 0,
                expensesMonthPiasters = 6_000_000, overduePiasters = 6_000_000,
                lowStockCount = 50, totalProducts = 50,
                cash90NetPiasters = -100_000_000, 15, 30
            )
        )!!
        assertTrue("bad=${bad.score}", bad.score < 35)
        assertEquals(3, bad.verdict)
    }

    @Test
    fun `month fraction clamps and guards zero days`() {
        assertEquals(0.5, R18Kpi.monthFraction(15, 30), 1e-9)
        assertEquals(1.0, R18Kpi.monthFraction(31, 30), 1e-9)
        assertEquals(1.0, R18Kpi.monthFraction(10, 0), 1e-9)
    }

    @Test
    fun `pulse is deterministic — same input same output`() {
        fun p() = R18Kpi.pulse(
            R18Kpi.Input(30_000_000, 6_000_000, 3_000_000, 1_000_000, 2, 40, 20_000_000, 10, 30)
        )!!
        assertEquals(p().score, p().score)
        assertEquals(p().verdict, p().verdict)
        assertEquals(p().components.size, p().components.size)
    }

    // ───────── WebhookKit — الحمولة والتوقيع (H4-6) ─────────

    @Test
    fun `invoice payload is stable json with p8 piasters and orig stamp`() {
        val json = WebhookKit.invoiceCreated(
            invoiceId = 9, invoiceNumber = "INV-1/2026", totalPiasters = 25_000,
            taxPiasters = 3_750, partyName = "مؤسسة \"النور\"", currencyCode = "SAR",
            origCurrency = "USD", origTotal = 6_664, at = 123L
        )
        assertTrue(json.contains("\"event\":\"invoice.created\""))
        assertTrue(json.contains("\"totalPiasters\":25000"))
        assertTrue(json.contains("\"origCurrency\":\"USD\""))
        assertTrue(json.contains("\"origTotalPiasters\":6664"))
        // تهريب الاقتباسات داخل الاسم
        assertTrue(json.contains("مؤسسة \\\"النور\\\""))
        val withoutOrig = WebhookKit.invoiceCreated(1, "N", 1, 0, null, "SAR", "", null, 5)
        assertTrue(!withoutOrig.contains("origCurrency"))
        assertTrue(withoutOrig.contains("\"party\":null"))
    }

    @Test
    fun `hmac signature is deterministic, hex16, and empty without secret`() {
        val s1 = WebhookKit.sign("payload", "secret-key")
        val s2 = WebhookKit.sign("payload", "secret-key")
        assertEquals(s1, s2)
        assertEquals(32, s1.length)
        assertTrue(s1.matches(Regex("[0-9a-f]{32}")))
        assertEquals("", WebhookKit.sign("payload", ""))
        assertTrue(WebhookKit.sign("payload", "k") != WebhookKit.sign("payload2", "k"))
    }

    // ───────── AdaptiveMath — فئات النافذة (H4-4) ─────────

    @Test
    fun `window classes thresholds`() {
        assertEquals(2, AdaptiveMath.posColumns(360))
        assertEquals(2, AdaptiveMath.posColumns(599))
        assertEquals(3, AdaptiveMath.posColumns(600))
        assertEquals(3, AdaptiveMath.posColumns(839))
        assertEquals(5, AdaptiveMath.posColumns(840))
        assertEquals(5, AdaptiveMath.posColumns(1280))
        assertEquals(1, AdaptiveMath.kpiColumns(400))
        assertEquals(2, AdaptiveMath.kpiColumns(700))
        assertEquals(false, AdaptiveMath.isSideBySide(600))
        assertEquals(true, AdaptiveMath.isSideBySide(900))
        assertNull(AdaptiveMath.maxContentWidth(500))
        assertEquals(840, AdaptiveMath.maxContentWidth(1000)!!)
    }

    // ───────── InvoiceTemplate — القوالب (H4-6) ─────────

    @Test
    fun `template specs — classic keeps everything, compact strips presentation`() {
        val c = InvoiceTemplate.CLASSIC
        assertTrue(c.showDiscountRow && c.showTafqit)
        val compact = InvoiceTemplate.COMPACT
        assertTrue(!compact.showDiscountRow && !compact.showTafqit && !compact.showPartyVat)
        val detailed = InvoiceTemplate.DETAILED
        assertTrue(detailed.showTafqit && detailed.showPartyVat)
        assertEquals(InvoiceTemplate.CLASSIC, InvoiceTemplate.fromId(0))
        assertEquals(InvoiceTemplate.COMPACT, InvoiceTemplate.fromId(2))
        // أي قيمة غريبة تعود للكلاسيكي (لا انهيار)
        assertEquals(InvoiceTemplate.CLASSIC, InvoiceTemplate.fromId(99))
    }
}
