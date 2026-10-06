package com.superbiz.app

import com.superbiz.app.domain.LineTaxP41
import com.superbiz.app.domain.algo.ZatcaInvoiceXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P41-L3] اختبارات فئة الضريبة S/Z/E في XML القانوني (v11 — جولة 2).
 *
 * العقد المجرَّب:
 *  - كل سطر يُصدر `<TaxCategory>S|Z|E</TaxCategory>` بعد `<VATRate>` مباشرة.
 *  - الافتراضي (بدون taxKind) قياسية S — توافق مع كل استدعاءات ما قبل v11.
 *  - الحتمية بايتاً ببايت محفوظة لنفس المدخلات (يشمل taxKind).
 *  - نسبة السطر الصفرية/المعفاة تُصدر 0 كما مرّت للنسبة الفعالة.
 */
class ZatcaInvoiceXmlP41Test {

    @Test
    fun defaultKind_emitsStandardCategory_afterVatRate() {
        val doc = ZatcaInvoiceXml.invoiceXml(
            "S", "V", 0L, "INV-1",
            listOf(ZatcaInvoiceXml.XmlLine("Item", 2.0, 10.0, 15.0, 20.0)),
            20.0, 0.0, 3.0, 23.0, null
        )
        assertTrue(doc.contains("<TaxCategory>S</TaxCategory>"))
        assertTrue("العنصر يلي VATRate مباشرة", doc.contains("<VATRate>15</VATRate><TaxCategory>S</TaxCategory>"))
    }

    @Test
    fun zeroAndExemptKinds_emitTheirCategories() {
        val doc = ZatcaInvoiceXml.invoiceXml(
            "S", "V", 0L, "INV-2",
            listOf(
                ZatcaInvoiceXml.XmlLine("صفرية", 1.0, 5.0, 0.0, 5.0, LineTaxP41.KIND_ZERO),
                ZatcaInvoiceXml.XmlLine("معفاة", 1.0, 7.0, 0.0, 7.0, LineTaxP41.KIND_EXEMPT),
            ),
            12.0, 0.0, 0.0, 12.0, null
        )
        assertTrue(doc.contains("<TaxCategory>Z</TaxCategory>"))
        assertTrue(doc.contains("<TaxCategory>E</TaxCategory>"))
        assertTrue(doc.contains("<VATRate>0</VATRate><TaxCategory>E</TaxCategory>"))
    }

    @Test
    fun deterministic_byteExactForSameInputsIncludingKind() {
        val lines = listOf(
            ZatcaInvoiceXml.XmlLine("قياسية", 2.0, 10.0, 15.0, 20.0, LineTaxP41.KIND_STANDARD),
            ZatcaInvoiceXml.XmlLine("صفرية", 1.0, 5.0, 0.0, 5.0, LineTaxP41.KIND_ZERO),
        )
        val a = ZatcaInvoiceXml.invoiceXml("ش", "VAT", 1_650_900_600_000L, "INV-3", lines, 25.0, 0.0, 3.0, 28.0, null)
        val b = ZatcaInvoiceXml.invoiceXml("ش", "VAT", 1_650_900_600_000L, "INV-3", lines, 25.0, 0.0, 3.0, 28.0, null)
        assertEquals(a, b)
    }

    @Test
    fun categoryEscapingNotNeeded_fixedAlphabetOnly() {
        // ألفباءة S/Z/E ثابتة — لا مسار تهريب، والعنصر يحصر الحرف وحده
        val doc = ZatcaInvoiceXml.invoiceXml(
            "S", "V", 0L, "N",
            listOf(ZatcaInvoiceXml.XmlLine("d", 1.0, 1.0, 0.0, 1.0, LineTaxP41.KIND_EXEMPT)),
            1.0, 0.0, 0.0, 1.0, null
        )
        assertTrue(doc.contains("<TaxCategory>E</TaxCategory>"))
        assertEquals("E", LineTaxP41.categoryId(LineTaxP41.KIND_EXEMPT))
    }
}
