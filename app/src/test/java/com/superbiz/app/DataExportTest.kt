package com.superbiz.app

import com.superbiz.app.export.DataExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class DataExportTest {

    @Test
    fun csv_quotesFieldsWithCommasAndQuotes() {
        val csv = DataExport.csv(
            listOf("name", "note"),
            listOf(listOf("أحمد", "دفع, جزئي"), listOf("مقتبس \"خاص\"", "سطر\nجديد"))
        )
        val lines = csv.trim().split("\n")
        // سجلّان منطقيان — والسطر الجديد داخل حقل مقتبس يجعل الأسطر الفيزيائية 4
        assertEquals(4, lines.size)
        assertTrue(lines[1].contains("\"دفع, جزئي\""))
        assertTrue(lines[2].contains("\"مقتبس \"\"خاص\"\"\""))
        assertTrue(lines[2] + "\n" + lines[3] == "\"مقتبس \"\"خاص\"\"\",\"سطر\nجديد\"")
    }

    @Test
    fun writeCsv_prependsUtf8Bom() {
        val out = ByteArrayOutputStream()
        DataExport.writeCsv(out, listOf("الاسم"), listOf(listOf("سالم")))
        val b = out.toByteArray()
        assertEquals(0xEF, b[0].toInt() and 0xFF)
        assertEquals(0xBB, b[1].toInt() and 0xFF)
        assertEquals(0xBF, b[2].toInt() and 0xFF)
        assertTrue(String(b, 3, b.size - 3, Charsets.UTF_8).contains("سالم"))
    }

    @Test
    fun xlsx_isValidZipWithOoxmlParts() {
        val out = ByteArrayOutputStream()
        DataExport.writeXlsx(
            out, "الفواتير",
            listOf("الفاتورة", "الإجمالي"),
            listOf(listOf("INV-1", 125.5), listOf("INV-2", 90.0))
        )
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z ->
            var e = z.nextEntry
            while (e != null) {
                entries[e.name] = z.readBytes().toString(Charsets.UTF_8)
                e = z.nextEntry
            }
        }
        assertTrue(entries.containsKey("[Content_Types].xml"))
        assertTrue(entries.containsKey("_rels/.rels"))
        assertTrue(entries.containsKey("xl/workbook.xml"))
        assertTrue(entries.containsKey("xl/worksheets/sheet1.xml"))
        assertTrue(entries.containsKey("xl/styles.xml"))
        assertTrue(entries["xl/workbook.xml"]!!.contains("sheet name=\"الفواتير\""))
    }

    @Test
    fun xlsx_sheetHasInlineStringsAndRealNumbers() {
        val out = ByteArrayOutputStream()
        DataExport.writeXlsx(
            out, "Claims",
            listOf("Name", "Total"),
            listOf(listOf("INV-1", 125.5))
        )
        val sheet = readEntry(out.toByteArray(), "xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("t=\"inlineStr\" s=\"1\""))  // رأس عريض
        assertTrue(sheet.contains("<t>INV-1</t>"))
        assertTrue(sheet.contains("<v>125.5</v>"))             // رقم حقيقي بدقة كاملة (R12-C16) بلا t
        assertTrue(!sheet.contains("t=\"inlineStr\"><v>"))
    }

    @Test
    fun xlsx_escapesXmlSpecialChars() {
        val out = ByteArrayOutputStream()
        DataExport.writeXlsx(out, "S", listOf("Note"), listOf(listOf("a&b<c>")))
        val sheet = readEntry(out.toByteArray(), "xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("a&amp;b&lt;c&gt;"))
    }

    @Test
    fun xlsx_columnRefsPassZ() {
        val out = ByteArrayOutputStream()
        val header = (0 until 28).map { "c$it" }   // يتجاوز Z إلى AA/AB
        DataExport.writeXlsx(out, "S", header, listOf(header.map { "x" }))
        val sheet = readEntry(out.toByteArray(), "xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("r=\"AA1\""))
        assertTrue(sheet.contains("r=\"AB1\""))
    }

    private fun readEntry(zip: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (e.name == name) return z.readBytes().toString(Charsets.UTF_8)
                e = z.nextEntry
            }
        }
        return ""
    }

    // ─── [P6-M31 إصلاح] اختبارات حرس حقن صيغ CSV (ميزة H-21 الموثقة بلا تغطية) ───

    @Test
    fun csv_injectionGuardPrefixesDangerousLeadingChars() {
        val csv = DataExport.csv(
            listOf("v"),
            listOf(listOf("=1+1"), listOf("+5"), listOf("@SUM(A1)"), listOf("\t9"), listOf("سلام"))
        )
        val lines = csv.trim().split("\n")
        assertEquals("'=1+1", lines[1])
        assertEquals("'+5", lines[2])
        assertEquals("'@SUM(A1)", lines[3])
        assertEquals("'\t9", lines[4])
        assertEquals("سلام", lines[5]) // غير الخطرة تمر كما هي
    }

    @Test
    fun csv_negativeStaysNumber_crGuarded() {
        val csv = DataExport.csv(
            listOf("v"),
            listOf(listOf(-50.0), listOf("\rx"))
        )
        val lines = csv.trim().split("\n")
        // R11-C10: السالب لا يُسبق باقتباس (كان يفسد الجداول)
        assertEquals("-50.0", lines[1])
        // CR الخطرة تُحرس ثم تُقتبس لاحتوائها محرف تحكم
        assertEquals("\"'\rx\"", lines[2])
    }

    // ─── [P6-M30 إصلاح] اختبار تفرد أسماء الأوراق بعد التطهير ───

    @Test
    fun writeXlsx_deduplicatesSheetNames_afterSanitize() {
        val out = ByteArrayOutputStream()
        // "a:b" و"a b" يُطبَّعان معاً إلى "a b" — Excel يرفض التكرار فيفسد الملف
        val sheets = listOf(
            com.superbiz.app.export.ExportSheet("a:b", listOf("k"), listOf(listOf("v1"))),
            com.superbiz.app.export.ExportSheet("a b", listOf("k"), listOf(listOf("v2")))
        )
        com.superbiz.app.export.XlsxSheets.write(out, sheets)
        val wb = readEntry(out.toByteArray(), "xl/workbook.xml")
        assertTrue(wb.contains("name=\"a b\""))
        assertTrue(wb.contains("name=\"a b (2)\""))
    }

    @Test
    fun writeXlsx_stripsIllegalXmlControlChars() {
        val out = ByteArrayOutputStream()
        val sheets = listOf(
            com.superbiz.app.export.ExportSheet("s", listOf("k"), listOf(listOf("قبل\u0001بعد\u0002نهاية")))
        )
        com.superbiz.app.export.XlsxSheets.write(out, sheets)
        val sheet = readEntry(out.toByteArray(), "xl/worksheets/sheet1.xml")
        assertFalse(sheet.contains('\u0001'))
        assertFalse(sheet.contains('\u0002'))
        assertTrue(sheet.contains("قبل"))
        assertTrue(sheet.contains("نهاية"))
    }
}
