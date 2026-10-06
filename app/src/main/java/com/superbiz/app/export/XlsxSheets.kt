package com.superbiz.app.export

import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ═══════════════════════════════════════════════════════════════
// تصدير XLSX متعدد الأوراق (بديل الاحتياطي R1 للوظيفة 11
// المنفَّذة مسبقاً: مسح الباركود للبحث في المخزون). كاتب OOXML مستقل
// بلا مكتبات — نفس نهج DataExport لكن بأوراق متعددة، وتوثيق كل جزء.
// ═══════════════════════════════════════════════════════════════

/** ورقة واحدة: اسم + رأس الأعمدة + الصفوف (نص أو رقم حقيقي) */
data class ExportSheet(
    val name: String,
    val header: List<String>,
    val rows: List<List<Any?>>
)

object XlsxSheets {

    /**
     * كتابة مصنف Excel صالح متعدد الأوراق (يفتح في Excel وLibreOffice وWPS).
     * أوراق فارغة أو بلا رأس تُهمل؛ مصنف بلا أوراق صالحة → IllegalArgumentException.
     */
    fun write(out: OutputStream, sheets: List<ExportSheet>) {
        val valid = sheets.filter { it.header.isNotEmpty() }
        require(valid.isNotEmpty()) { "at least one sheet with a header is required" }
        // [P6-M30 إصلاح] أسماء الأوراق بعد التطهير قد تتطابق (مثل "a:b" و"a b") —
        // Excel يرفض التكرار فيفسد الملف؛ لوطة تفرد تُلحق (2)، (3)... مع سقف 31 محرفاً
        val names = uniqueSheetNames(valid.map { it.name })
        ZipOutputStream(out.buffered()).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
            entry("[Content_Types].xml", contentTypes(valid.size))
            entry("_rels/.rels", ROOT_RELS)
            entry("xl/workbook.xml", workbookXml(valid, names))
            entry("xl/_rels/workbook.xml.rels", workbookRels(valid.size))
            entry("xl/styles.xml", STYLES)
            valid.forEachIndexed { i, s ->
                entry("xl/worksheets/sheet${i + 1}.xml", sheetXml(s))
            }
        }
    }

    // ── أجزاء الحزمة ──

    private fun contentTypes(count: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        for (i in 1..count) {
            append("""<Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        append("</Types>")
    }

    private val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private fun workbookXml(sheets: List<ExportSheet>, names: List<String>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
        sheets.forEachIndexed { i, _ ->
            append("""<sheet name="${xml(names[i])}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRels(count: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (i in 1..count) {
            append("""<Relationship Id="rId$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$i.xml"/>""")
        }
        // styles.xml كان معلناً في [Content_Types] دون علاقة من
        // workbook.xml.rels — خلاف لقاعدة OOXML (ISO 29500) فبرامج العرض الصارمة
        // (بعض إصدارات Excel/تطبيقات الجوال) تطلب إصلاح الملف عند فتحه
        append("""<Relationship Id="rId${count + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        append("</Relationships>")
    }

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs>
</styleSheet>"""

    private fun sheetXml(s: ExportSheet): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        appendRow(sb, 1, s.header, isHeader = true)
        s.rows.forEachIndexed { i, r -> appendRow(sb, i + 2, r, isHeader = false) }
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

    private fun sanitizeSheetName(name: String): String =
        name.replace(Regex("[\\[\\]:*?/\\\\]"), " ").trim().take(31).ifBlank { "Sheet1" }

    /**
     * [P6-M30 إصلاح] لوطة تفرد أسماء الأوراق بعد التطهير: التكرار (حتى بحالة أحرف
     * مختلفة — Excel يقارن الأسماء بلا حساسية للحالة) يُحلّ بلائحة " (2)" و" (3)"
     * بنمط Excel نفسه، مع إبقاء الاسم ≤ 31 محرفاً (سقف OOXML) بقتطاع الأساس عند اللزوم.
     */
    private fun uniqueSheetNames(raw: List<String>): List<String> {
        val used = HashSet<String>()
        val out = ArrayList<String>(raw.size)
        for (n in raw) {
            val base = sanitizeSheetName(n)
            var candidate = base
            var k = 2
            while (!used.add(candidate.lowercase(java.util.Locale.ROOT))) {
                val suffix = " ($k)"
                candidate = base.take(31 - suffix.length).trimEnd() + suffix
                k++
            }
            out += candidate
        }
        return out
    }

    /**
     * تهريب أحرف XML الخمسة + [P6-M30 إصلاح] إزالة محارف التحكم غير الشرعية في
     * XML 1.0 — أي محرف < 0x20 عدا \t \n \r (وأيضاً FFFE/FFFF المحظورين) كان
     * يُكتب خاماً فيُنتج ملف xlsx مكسوراً لا يفتح.
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
}
