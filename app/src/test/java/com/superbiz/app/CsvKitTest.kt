package com.superbiz.app

import com.superbiz.app.domain.algo.CsvKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P12-a] اختبارات CsvKit النقية (JUnit4 بلا Android):
 * التهيئة (فاصلة/اقتباس/سطر جديد)، والبناء الكامل (CRLF، BOM، تعبئة الصفوف القصيرة،
 * الترويسات وحدها عند خلو الصفوف).
 */
class CsvKitTest {

    // ─── escape ───

    @Test
    fun escape_plainFieldStaysBare() {
        assertEquals("أحمد", CsvKit.escape("أحمد"))
        assertEquals("0501234567", CsvKit.escape("0501234567"))
        assertEquals("", CsvKit.escape(""))
    }

    @Test
    fun escape_commaWrapsInQuotes() {
        assertEquals("\"أ,ب\"", CsvKit.escape("أ,ب"))
        assertEquals("\"سعر 50, ريال\"", CsvKit.escape("سعر 50, ريال"))
    }

    @Test
    fun escape_innerQuotesAreDoubled() {
        // قال "مرحبا" → "قال ""مرحبا"""
        assertEquals("\"قال \"\"مرحبا\"\"\"", CsvKit.escape("قال \"مرحبا\""))
        assertEquals("\"\"\"\"", CsvKit.escape("\""))
    }

    @Test
    fun escape_newlineWrapsInQuotes() {
        assertEquals("\"س1\nس2\"", CsvKit.escape("س1\nس2"))
        assertEquals("\"ملاحظة\rثانية\"", CsvKit.escape("ملاحظة\rثانية"))
    }

    @Test
    fun escape_commaQuoteNewlineCombined() {
        // أ,"ب" + سطر جديد → كل شيء داخل اقتباس والاقتباسات الداخلية مضاعفة
        assertEquals("\"أ,\"\"ب\"\"\nس\"", CsvKit.escape("أ,\"ب\"\nس"))
    }

    // ─── buildCsv ───

    @Test
    fun buildCsv_twoHeadersTwoRows_exactStringWithCrlf() {
        val csv = CsvKit.buildCsv(
            listOf("الاسم", "الهاتف"),
            listOf(listOf("أحمد", "0501"), listOf("سارة", "0502")),
            bom = false
        )
        assertEquals("الاسم,الهاتف\r\nأحمد,0501\r\nسارة,0502", csv)
    }

    @Test
    fun buildCsv_bomTrueStartsWithUtf8Bom() {
        val csv = CsvKit.buildCsv(listOf("الاسم", "الهاتف"), emptyList(), bom = true)
        assertTrue("يجب أن يبدأ الملف بـ BOM \\uFEFF", csv.startsWith("\uFEFF"))
        assertEquals("\uFEFFالاسم,الهاتف", csv)
    }

    @Test
    fun buildCsv_bomFalseHasNoBom() {
        val csv = CsvKit.buildCsv(listOf("أ"), emptyList(), bom = false)
        assertEquals("أ", csv)
        assertEquals('أ', csv[0])
    }

    @Test
    fun buildCsv_shortRowIsPaddedWithEmptyFields() {
        val csv = CsvKit.buildCsv(
            listOf("الاسم", "الهاتف", "النوع"),
            listOf(listOf("أحمد")),
            bom = false
        )
        assertEquals("الاسم,الهاتف,النوع\r\nأحمد,,", csv)
    }

    @Test
    fun buildCsv_emptyRowsListYieldsHeadersOnly() {
        val csv = CsvKit.buildCsv(
            listOf("الاسم", "الهاتف", "النوع", "خط العرض", "خط الطول", "ملاحظة"),
            emptyList(),
            bom = false
        )
        assertEquals("الاسم,الهاتف,النوع,خط العرض,خط الطول,ملاحظة", csv)
    }

    @Test
    fun buildCsv_fieldsAreEscapedIncludingHeaders() {
        // ترويسة بفاصلة وصف فيه اقتباس — التهيئة تُطبَّق على الكل
        val csv = CsvKit.buildCsv(
            listOf("ملاحظة,عامة"),
            listOf(listOf("قال \"أهلا\"")),
            bom = false
        )
        assertEquals("\"ملاحظة,عامة\"\r\n\"قال \"\"أهلا\"\"\"", csv)
    }

    @Test
    fun buildCsv_longerRowIsTruncatedToHeaderWidth() {
        val csv = CsvKit.buildCsv(
            listOf("أ", "ب"),
            listOf(listOf("س1", "س2", "س3")),
            bom = false
        )
        assertEquals("أ,ب\r\nس1,س2", csv)
    }
}
