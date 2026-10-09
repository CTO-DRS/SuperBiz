package com.superbiz.app.domain.algo

import com.superbiz.app.util.Money

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [H4-6 V 2.5.0] CsvImportKit — الاستيراد النقي (H4-6 «واجهة تصدير وتكامل»)
 * ═══════════════════════════════════════════════════════════════════════════
 * الطرف المقابل لـCsvKit.buildCsv/DataExport: يقرأ ملفات CSV التي يصدّرها
 * التطبيق نفسه (رؤوس عربية موطّنة) أو نظائرها الإنجليزية، ويحوّلها صفوفاً
 * جاهزة للإدخال — بلا أي لمسة لأندرويد (JVM خالص، نمط R17/R16).
 *
 * عقد الموجة:
 * 1) **جولة ذهاب-إياب**: ما يُصدَّر من الأصناف يعود بلا فقدان (الاسم/SKU/الباركود/
 *    الوحدة/الأسعار/حد الطلب/الكمية)، والأطراف تعود بهويتها — الرصيد عمود معلوماتي
 *    في الاستيراد لا يُكتب (قرار أمان موثق: الرصيد مشتق قيدياً — إدخاله نصاً يكسر
 *    القيد المزدوج، نفس عقد «نطاق الاستعادة» في BackupKit).
 * 2) **حرس الحقن موحّد**: الاستيراد يخزّن النص كما هو — الحرس الفعلي ضد حقن صيغ
 *    Excel يبقى في CsvKit.escape عند كل تصدير لاحق (المصدر الوحيد للسلوك على
 *    المسارين، عقد L-5). ما يخرج من التطبيق محروب دائماً مهما كان مصدر النص.
 * 3) **الإخفاق صفّاً لا ملفّاً**: صف تالف (اسم فارغ/رقم غير قابل للتحليل) يُتخطى
 *    مع سببٍ مسمّى — ولا يُسقط الملف كله (النقيض من BackupKit حيث الاستعادة
 *    كل-أو-لاشيء لأنها استعادة حالة كاملة؛ هنا إدخال جماعي يرحم الصف الواحد).
 * 4) **كشف الجدول من الرؤوس**: وجود «الاسم» وحده لا يكفي — يُطلب عمود مميز
 *    (SKU/الباركود/الأسعار للأصناف، الهاتف للأطراف) لكشف النوع حتمياً.
 * 5) التحليل RFC4180-lite: BOM يزال، اقتباس كامل الحقل، «""» هروب، CRLF/LF/CR،
 *    والصفوف تُحاذى عرض الرأس (قصّ الزائد + حشو الناقص).
 */
object CsvImportKit {

    data class ParsedCsv(val header: List<String>, val rows: List<List<String>>)

    /** صف صنف جاهز للإدخال — الكميات Double والأسعار قروش (عقد P33-P8) */
    data class ProductRow(
        val name: String,
        val sku: String,
        val barcode: String,
        val unit: String,
        val costPiasters: Long,
        val salePiasters: Long,
        val stockQty: Double,
        val reorderLevel: Double
    )

    /** صف طرف جاهز للإدخال — الرصيد عمداً غير موجود (بند 1 من العقد) */
    data class PartyRow(val name: String, val phone: String, val type: Int)

    /** نتيجة التحويل: الصفوف الصالحة + أسباب تخطي الصفوف التالفة (رقم الصف 1-على-الرأس) */
    data class MapResult<T>(val rows: List<T>, val skipped: List<String>)

    // ─────────────────────────── 1) المحلل ───────────────────────────

    /** محلل RFC4180-lite حتمي — null لملف فارغ/بلا رأس */
    fun parse(text: String): ParsedCsv? {
        val t = text.removePrefix("\uFEFF")
        if (t.isBlank()) return null
        val records = ArrayList<MutableList<String>>()
        val field = StringBuilder()
        var row = ArrayList<String>()
        var inQuotes = false
        var i = 0
        val n = t.length
        while (i < n) {
            val c = t[i]
            when {
                inQuotes -> when {
                    c == '"' -> {
                        if (i + 1 < n && t[i + 1] == '"') { field.append('"'); i++ }
                        else inQuotes = false
                    }
                    else -> field.append(c)
                }
                c == '"' -> inQuotes = true
                c == ',' -> { row.add(field.toString()); field.setLength(0) }
                c == '\r' -> {
                    if (i + 1 < n && t[i + 1] == '\n') i++
                    row.add(field.toString()); field.setLength(0)
                    records.add(row); row = ArrayList()
                }
                c == '\n' -> {
                    row.add(field.toString()); field.setLength(0)
                    records.add(row); row = ArrayList()
                }
                else -> field.append(c)
            }
            i++
        }
        // السجل الأخير (بلا سطر جديد ختامي)
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            records.add(row)
        }
        if (records.isEmpty()) return null
        val header = records[0].map { it.trim() }
        if (header.none { it.isNotBlank() }) return null
        val rows = records.drop(1)
            .filter { r -> r.any { it.isNotBlank() } } // صفوف الفراغ البنيوية تُهمل لا تُعَدّ تالفة
            .map { r ->
                when {
                    r.size > header.size -> r.take(header.size) // قصّ الزائد — عرض الرأس عقد الملف
                    r.size < header.size -> r + List(header.size - r.size) { "" } // حشو الناقص
                    else -> r
                }
            }
        return ParsedCsv(header, rows)
    }

    // ─────────────────────────── 2) كشف الجدول ───────────────────────────

    /**
     * كشف حتمي لنوع الملف من رؤوسه — "products"/"parties"/null.
     * الأصناف: عمود SKU أو الباركود أو سعر التكلفة حاضر.
     * الأطراف: عمود الهاتف حاضر (بلا أي عمود أصناف مميز).
     */
    fun detectKind(header: List<String>): String? {
        fun has(vararg keys: String) = header.any { h -> keys.any { k -> h.equals(k, ignoreCase = true) } }
        val productMarkers = has("sku", "رمز SKU", "barcode", "الباركود", "cost_price", "سعر التكلفة", "sale_price", "سعر البيع")
        val partyMarkers = has("phone", "الهاتف", "transaction_type", "النوع")
        if (productMarkers) return "products"
        if (partyMarkers) return "parties"
        return null
    }

    // ─────────────────────────── 3) خريطة الأصناف ───────────────────────────

    /**
     * تحويل CSV أصناف → صفوف إدخال. الرؤوس المقبولة: عربية (صادرات التطبيق نفسه)
     * أو إنجليزية قيانية. «الاسم» مطلوب — صف بلا اسم يُتخطى بسبب مسمّى.
     * عمود قيمة المخزون (inv_stock_value/قيمة المخزون) معلوماتي — لا يُدخل (مشتق).
     */
    fun mapProducts(csv: ParsedCsv): MapResult<ProductRow>? {
        val h = csv.header
        val iName = indexOf(h, "name", "الاسم") ?: return null
        val iSku = indexOf(h, "sku", "رمز SKU")
        val iBarcode = indexOf(h, "barcode", "الباركود")
        val iUnit = indexOf(h, "unit", "الوحدة")
        val iCost = indexOf(h, "cost_price", "سعر التكلفة")
        val iSale = indexOf(h, "sale_price", "سعر البيع")
        val iStock = indexOf(h, "stock_qty", "الكمية المتوفرة")
        val iReorder = indexOf(h, "reorder_level", "حد الطلب")
        val rows = ArrayList<ProductRow>()
        val skipped = ArrayList<String>()
        csv.rows.forEachIndexed { idx, r ->
            val name = r[iName].trim()
            if (name.isEmpty()) { skipped.add("صف ${idx + 2}: الاسم فارغ"); return@forEachIndexed }
            val cost = parseMoney(r, iCost) ?: run { skipped.add("صف ${idx + 2}: سعر تكلفة غير قابل للتحليل"); return@forEachIndexed }
            val sale = parseMoney(r, iSale) ?: run { skipped.add("صف ${idx + 2}: سعر بيع غير قابل للتحليل"); return@forEachIndexed }
            val stock = parseQty(r, iStock) ?: run { skipped.add("صف ${idx + 2}: كمية غير قابلة للتحليل"); return@forEachIndexed }
            val reorder = parseQty(r, iReorder) ?: run { skipped.add("صف ${idx + 2}: حد طلب غير قابل للتحليل"); return@forEachIndexed }
            rows.add(
                ProductRow(
                    name = name,
                    sku = col(r, iSku),
                    barcode = col(r, iBarcode),
                    unit = col(r, iUnit).ifBlank { "قطعة" },
                    costPiasters = cost,
                    salePiasters = sale,
                    stockQty = stock,
                    reorderLevel = reorder
                )
            )
        }
        return MapResult(rows, skipped)
    }

    // ─────────────────────────── 4) خريطة الأطراف ───────────────────────────

    /**
     * تحويل CSV أطراف → صفوف إدخال. «الاسم» مطلوب. نوع الطرف من النص الموطّن
     * (كما يكتبه تصدير المطالبات نفسه) أو رقم خام 0/1/2 — فارغ = عميل (0).
     * أعمدة الرصيد/الخطر/الحالة معلوماتية — لا تُدخل (بند 1 من العقد).
     */
    fun mapParties(csv: ParsedCsv, customerWord: String, supplierWord: String, bothWord: String): MapResult<PartyRow>? {
        val h = csv.header
        val iName = indexOf(h, "name", "الاسم") ?: return null
        val iPhone = indexOf(h, "phone", "الهاتف")
        val iType = indexOf(h, "transaction_type", "النوع")
        val rows = ArrayList<PartyRow>()
        val skipped = ArrayList<String>()
        csv.rows.forEachIndexed { idx, r ->
            val name = r[iName].trim()
            if (name.isEmpty()) { skipped.add("صف ${idx + 2}: الاسم فارغ"); return@forEachIndexed }
            val typeText = col(r, iType)
            val type = when {
                typeText.isBlank() -> 0
                typeText == "0" || typeText == "1" || typeText == "2" -> typeText.toInt()
                else -> mapPartyType(typeText, customerWord, supplierWord, bothWord)
                    ?: run { skipped.add("صف ${idx + 2}: نوع طرف غير معروف \"$typeText\""); return@forEachIndexed }
            }
            rows.add(PartyRow(name = name, phone = col(r, iPhone), type = type))
        }
        return MapResult(rows, skipped)
    }

    /** تطبيع نص النوع — تطابق جزئي متسامح مع صيغ التوطين («عميل ومورد»/«العملاء»/…) */
    fun mapPartyType(text: String, customerWord: String, supplierWord: String, bothWord: String): Int? {
        val t = text.trim()
        return when {
            bothWord.isNotBlank() && (t == bothWord || t.contains(bothWord)) -> 2
            t.contains("ومورد") || t.equals("both", ignoreCase = true) -> 2
            supplierWord.isNotBlank() && (t == supplierWord || t.contains(supplierWord)) -> 1
            t.contains("مورد") || t.equals("supplier", ignoreCase = true) -> 1
            customerWord.isNotBlank() && (t == customerWord || t.contains(customerWord)) -> 0
            t.contains("عميل") || t.contains("زبون") || t.equals("customer", ignoreCase = true) -> 0
            else -> null
        }
    }

    // ─────────────────────────── مساعدات ───────────────────────────

    private fun indexOf(header: List<String>, en: String, ar: String): Int? {
        val i = header.indexOfFirst {
            it.equals(en, ignoreCase = true) || it.equals(ar, ignoreCase = false)
        }
        return if (i >= 0) i else null
    }

    private fun col(r: List<String>, i: Int?): String = if (i == null || i >= r.size) "" else r[i].trim()

    /** مبلغ نصي → قروش — غير قابل للتحليل ⇒ null (يُتخطى الصف لا صفر زائف) */
    private fun parseMoney(r: List<String>, i: Int?): Long? {
        val s = col(r, i)
        if (s.isEmpty()) return 0L // عمود غائب/فارغ = صفر مقصود لا خطأ
        val v = Money.parse(s) ?: return null
        return Money.toPiasters(v)
    }

    /** كمية نصية → Double — غير قابل للتحليل أو سالب ⇒ null */
    private fun parseQty(r: List<String>, i: Int?): Double? {
        val s = col(r, i)
        if (s.isEmpty()) return 0.0
        val v = Money.parse(s) ?: return null
        return if (v < 0.0) null else v
    }
}
