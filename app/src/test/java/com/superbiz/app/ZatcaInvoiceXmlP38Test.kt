package com.superbiz.app

import com.superbiz.app.domain.algo.ZatcaInvoiceXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات [P38-Z4] — XML القانوني كامل البنود للفاتورة (تصدير موائم UBL
 * مبسّط). عقد الحتمية والتهريب والتنسيق الحرفي.
 */
class ZatcaInvoiceXmlP38Test {

    private fun sampleLines() = listOf(
        ZatcaInvoiceXml.XmlLine("قهوة مختصة", 2.0, 10.0, 15.0, 20.0),
        ZatcaInvoiceXml.XmlLine("Sugar & Syrup <extra>", 0.5, 25.0, 15.0, 12.5),
    )

    @Test
    fun deterministic_sameInputsSameBytes() {
        val a = ZatcaInvoiceXml.invoiceXml(
            "مؤسسة النور", "310122393500003", 1_650_911_400_000L, "INV-12",
            sampleLines(), 32.5, 0.0, 4.875, 37.375, "عميل التجارب"
        )
        val b = ZatcaInvoiceXml.invoiceXml(
            "مؤسسة النور", "310122393500003", 1_650_911_400_000L, "INV-12",
            sampleLines(), 32.5, 0.0, 4.875, 37.375, "عميل التجارب"
        )
        assertEquals(a, b)
    }

    @Test
    fun structure_fixedOrderAndFormatting() {
        // 1_650_900_600_000 = 2022-04-25T15:30:00Z (العينة المرجعية المنشورة — حساب UTC صريح)
        val doc = ZatcaInvoiceXml.invoiceXml(
            "S", "VAT1", 1_650_900_600_000L, "INV-1",
            listOf(ZatcaInvoiceXml.XmlLine("Item", 2.0, 10.0, 15.0, 20.0)),
            20.0, 0.0, 3.0, 23.0, null
        )
        assertTrue(doc.startsWith("<Invoice>"))
        assertTrue(doc.contains("<InvoiceNumber>INV-1</InvoiceNumber>"))
        assertTrue(doc.contains("<TimeStamp>2022-04-25T15:30:00Z</TimeStamp>"))
        assertTrue(doc.contains("<Seller><Name>S</Name><VATNumber>VAT1</VATNumber></Seller>"))
        assertTrue(doc.contains("<Index>1</Index>"))
        assertTrue(doc.contains("<Qty>2</Qty>"))                       // صحيح بلا كسور
        assertTrue(doc.contains("<UnitPrice>10.00</UnitPrice>"))       // خانتان عشريتان
        assertTrue(doc.contains("<VATRate>15</VATRate>"))
        assertTrue(doc.contains("<LineTotal>20.00</LineTotal>"))
        assertTrue(doc.contains("<Subtotal>20.00</Subtotal>"))
        assertTrue(doc.contains("<VATTotal>3.00</VATTotal>"))
        assertTrue(doc.contains("<InvoiceTotal>23.00</InvoiceTotal>"))
        assertTrue(doc.endsWith("</Invoice>"))
    }

    @Test
    fun escaping_fiveEntities_andNoDoubleEscape() {
        val doc = ZatcaInvoiceXml.invoiceXml(
            "A&B <Co> \"Ltd\" 'x'", "V", 0L, "N&1",
            listOf(ZatcaInvoiceXml.XmlLine("d<&>", 1.0, 1.0, 0.0, 1.0)),
            1.0, 0.0, 0.0, 1.0, null
        )
        assertTrue(doc.contains("<Name>A&amp;B &lt;Co&gt; &quot;Ltd&quot; &apos;x&apos;</Name>"))
        assertTrue(doc.contains("<InvoiceNumber>N&amp;1</InvoiceNumber>"))
        assertTrue(doc.contains("<Description>d&lt;&amp;&gt;</Description>"))
        // «&» تهرب أولاً — لا سلسلة &amp;amp; (تهريب مزدوج)
        assertFalse(doc.contains("&amp;amp;"))
    }

    @Test
    fun buyerOmitted_whenNullOrBlank() {
        val without = ZatcaInvoiceXml.invoiceXml(
            "S", "V", 0L, "N", emptyList(), 0.0, 0.0, 0.0, 0.0, null
        )
        val blank = ZatcaInvoiceXml.invoiceXml(
            "S", "V", 0L, "N", emptyList(), 0.0, 0.0, 0.0, 0.0, "   "
        )
        assertFalse(without.contains("<Buyer>"))
        assertFalse(blank.contains("<Buyer>"))
        val with = ZatcaInvoiceXml.invoiceXml(
            "S", "V", 0L, "N", emptyList(), 0.0, 0.0, 0.0, 0.0, "العميل"
        )
        assertTrue(with.contains("<Buyer><Name>العميل</Name></Buyer>"))
    }

    @Test
    fun qty3_deterministicTrimming() {
        assertEquals("2", ZatcaInvoiceXml.qty3(2.0))
        assertEquals("0.5", ZatcaInvoiceXml.qty3(0.5))
        assertEquals("1.25", ZatcaInvoiceXml.qty3(1.25))
        assertEquals("2.675", ZatcaInvoiceXml.qty3(2.675))
        assertEquals("0.125", ZatcaInvoiceXml.qty3(0.125))
        assertEquals("15", ZatcaInvoiceXml.qty3(15.0))
    }

    @Test
    fun arabicPassesThroughUtf8_andEmptyLinesTolerated() {
        val doc = ZatcaInvoiceXml.invoiceXml(
            "مؤسسة الأعمال", "٣١٠", 0L, "ف-1",
            emptyList(), 0.0, 0.0, 0.0, 0.0, null
        )
        assertTrue(doc.contains("<Seller><Name>مؤسسة الأعمال</Name>"))
        assertTrue(doc.contains("<Lines></Lines>"))
    }
}
