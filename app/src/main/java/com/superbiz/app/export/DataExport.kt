package com.superbiz.app.export

import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * مولّد تصدير جداول: Excel حقيقي (.xlsx — حزمة OOXML مبنية يدوياً بلا مكتبات)
 * و CSV بترميز UTF-8 مع BOM ليفتح Excel العربية مباشرة.
 * الخلايا إمّا نص (String) أو رقم (Double/Number) يُكتب كرقم حقيقي في Excel.
 */
object DataExport {

    /** اسم ملف مؤرّخ: prefix-20260913-1425.xlsx أو .csv */
    fun fileName(prefix: String, isXlsx: Boolean): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(java.util.Date())
        return prefix + "-" + stamp + if (isXlsx) ".xlsx" else ".csv"
    }

    // ─────────── CSV ───────────

    /** نص CSV مع اقتباس قياسي: الحقل الذي يحوي فاصلة/اقتباس/سطراً جديداً يُحاط بعلامات اقتباس مزدوجة */
    fun csv(header: List<String>, rows: List<List<Any?>>): String {
        val sb = StringBuilder()
        sb.appendLine(header.joinToString(",") { csvField(it.toString()) })
        rows.forEach { r ->
            sb.appendLine(r.joinToString(",") { csvField(it?.toString() ?: "") })
        }
        return sb.toString()
    }

    /** كتابة CSV مع BOM (EF BB BF) — يضمن قراءة العربية في Excel */
    fun writeCsv(out: OutputStream, header: List<String>, rows: List<List<Any?>>) {
        out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        out.write(csv(header, rows).toByteArray(StandardCharsets.UTF_8))
        out.flush()
    }

    /**
     * حرس حقن صيغ CSV (H-21) — [تدقيق L-5] توحيد المسارين: التفويض الكامل
     * لـCsvKit.escape (المصدر الوحيد) الذي يعتمد نفس مجموعة H-21
     * (= + @ TAB CR — السالب مستثنى لأنه قيمة عددية لا صيغة)،
     * فلا انقسام سلوك بين تصدير التقارير والمفضّلات بعد اليوم.
     */
    private fun csvField(v: String): String =
        com.superbiz.app.domain.algo.CsvKit.escape(v)

    // ─────────── XLSX ───────────

    /**
     * كتابة ملف xlsx صالح (يفتح في Excel وLibreOffice وWPS) بورقة واحدة.
     * يعتمد inline strings فلا حاجة لجدول sharedStrings، مع عرض أعمدة تلقائي.
     */
    fun writeXlsx(out: OutputStream, sheetName: String, header: List<String>, rows: List<List<Any?>>) {
        ZipOutputStream(out.buffered()).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
            val sheet = sanitizeSheetName(sheetName)
            entry("[Content_Types].xml", CONTENT_TYPES)
            entry("_rels/.rels", ROOT_RELS)
            entry("xl/workbook.xml", workbookXml(sheet))
            entry("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            entry("xl/styles.xml", STYLES)
            entry("xl/worksheets/sheet1.xml", sheetXml(header, rows))
        }
    }

    private fun sanitizeSheetName(name: String): String =
        name.replace(Regex("[\\[\\]:*?/\\\\]"), " ").trim().take(31).ifBlank { "Sheet1" }

    private fun sheetXml(header: List<String>, rows: List<List<Any?>>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")

        // عرض الأعمدة تلقائياً من أطول قيمة (بحدود معقولة)
        val all = listOf(header) + rows
        val widths = (0 until header.size).map { c ->
            all.maxOfOrNull { row -> row.getOrNull(c)?.toString()?.length ?: 0 } ?: 8
        }.map { it.coerceIn(8, 50) * 1.2 + 2 }
        sb.append("<cols>")
        widths.forEachIndexed { i, w ->
            sb.append("""<col min="${i + 1}" max="${i + 1}" width="${"%.1f".format(Locale.US, w)}" customWidth="1"/>""")
        }
        sb.append("</cols>")

        sb.append("<sheetData>")
        appendRow(sb, 1, header, isHeader = true)
        rows.forEachIndexed { i, r -> appendRow(sb, i + 2, r, isHeader = false) }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun appendRow(sb: StringBuilder, index: Int, cells: List<Any?>, isHeader: Boolean) {
        sb.append("""<row r="$index">""")
        cells.forEachIndexed { c, v ->
            val ref = colLetter(c) + index
            if (v == null) return@forEachIndexed
            if (isHeader) {
                sb.append("""<c r="$ref" t="inlineStr" s="1"><is><t>${xml(v.toString())}</t></is></c>""")
            } else if (v is Number) {
                // كانت %.2f تُشوّه الكميات بدقة أكبر من منزلتين (2.375 → 2.38)
                // وتكتب NaN/Infinity كخلية رقمية تكسر Excel — دقة كاملة للمنتهي وتجاهل غير المنتهي
                val d = v.toDouble()
                if (d.isFinite()) sb.append("""<c r="$ref"><v>${d}</v></c>""")
            } else {
                sb.append("""<c r="$ref" t="inlineStr"><is><t>${xml(v.toString())}</t></is></c>""")
            }
        }
        sb.append("</row>")
    }

    private fun colLetter(i: Int): String {
        var n = i
        val sb = StringBuilder()
        while (n >= 0) {
            sb.insert(0, ('A' + n % 26))
            n = n / 26 - 1
        }
        return sb.toString()
    }

    /** تهريب أحرف XML الخمسة
     *  [P20-FIX agent17]: نفس تنظيف محارف التحكم في XlsxSheets.xml — الباركود الممسوح يحمل
     * فواصل GS/FS (U+001D/001C) فكانت تُكتب خاماً في sheet1.xml فيكسر الملف كله
     */
    private fun xml(s: String): String {
        val cleaned = StringBuilder(s.length)
        for (c in s) {
            val legal = c == '\t' || c == '\n' || c == '\r' || c >= ' ' && c != '\uFFFE' && c != '\uFFFF'
            if (legal) cleaned.append(c)
        }
        return cleaned.toString()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    // ── أجزاء الحزمة الثابتة ──

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private const val WORKBOOK_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs>
</styleSheet>"""

    private fun workbookXml(sheet: String) = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="$sheet" sheetId="1" r:id="rId1"/></sheets>
</workbook>"""
}
