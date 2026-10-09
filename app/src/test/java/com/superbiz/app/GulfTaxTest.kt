package com.superbiz.app

import com.superbiz.app.domain.GulfTax
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [H5-4 V 3.0.0] «الأفق الخامس — العالمية»: ولايات الضريبة الخليجية
 * ═══════════════════════════════════════════════════════════════════════════
 * عقد القبول (ROADMAP الأفق الخامس H5-3):
 * - بذور رسمية: SA 15% / SAR، AE 5% / AED، BH 10% / BHD.
 * - ZATCA مرحلة-2 سعودية حصراً — بوابة رقم البائع fail-closed.
 * - مجهول/فارغ ⇒ SA (الترقية التراكمية بلا تغيير سلوك تاجر قائم).
 */
class GulfTaxTest {

    @Test
    fun `presets carry statutory rates`() {
        assertEquals(15.0, GulfTax.SA.defaultRate, 1e-9)
        assertEquals(5.0, GulfTax.AE.defaultRate, 1e-9)
        assertEquals(10.0, GulfTax.BH.defaultRate, 1e-9)
        assertEquals("SAR", GulfTax.SA.currency)
        assertEquals("AED", GulfTax.AE.currency)
        assertEquals("BHD", GulfTax.BH.currency)
    }

    @Test
    fun `weekend conventions match each jurisdiction`() {
        assertTrue(GulfTax.SA.weekendFriSat)   // الجمعة+السبت
        assertFalse(GulfTax.AE.weekendFriSat)  // السبت+الأحد (القطاع الخاص منذ 2022)
        assertTrue(GulfTax.BH.weekendFriSat)   // الجمعة+السبت
    }

    @Test
    fun `zatca is saudi-only`() {
        assertTrue(GulfTax.isZatcaJurisdiction("SA"))
        assertFalse(GulfTax.isZatcaJurisdiction("AE"))
        assertFalse(GulfTax.isZatcaJurisdiction("BH"))
    }

    @Test
    fun `unknown jurisdiction fails closed to SA`() {
        // [P51-4] فارغ/مجهول/حرف صغير ⇒ السلوك التاريخي SA
        assertEquals(GulfTax.SA, GulfTax.preset(""))
        assertEquals(GulfTax.SA, GulfTax.preset("US"))
        assertEquals(GulfTax.SA, GulfTax.preset("sa".uppercase())) // استهلاك التطبيع في الrepo
        assertEquals("SA", GulfTax.preset("XX").code)
    }

    @Test
    fun `seller vat gate empties vat outside SA`() {
        // [P51-3] عقد البوابة النقية — خارج SA يخرج البائع بلا رقم ضريبي
        assertEquals("300012345600003", GulfTax.zatcaSellerVat("SA", "300012345600003"))
        assertEquals("", GulfTax.zatcaSellerVat("AE", "300012345600003"))
        assertEquals("", GulfTax.zatcaSellerVat("BH", "300012345600003"))
        // داخل SA الفارغ يبقى فارغاً (عقد ZatcaStamper الأصلي يبقى صامتاً)
        assertEquals("", GulfTax.zatcaSellerVat("SA", ""))
        assertEquals(3, GulfTax.ALL.size)
        assertEquals(GulfTax.CODES, setOf("SA", "AE", "BH"))
    }
}
