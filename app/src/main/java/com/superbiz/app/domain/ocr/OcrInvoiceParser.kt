package com.superbiz.app.domain.ocr

import com.superbiz.app.util.Money
import java.util.GregorianCalendar

/**
 * [F1 - ADR-005] محرك تحليل الفواتير من نص OCR — منطق نقي صفر اعتماديات أندرويد.
 *
 * العقد (مختبر وحدويًا في InvoiceOcrParserTest — RED-first موثق في CHANGELOG):
 * - المدخل نص خام من محرك OCR (ML Kit) والمخرج مسودة مهيكلة OcrInvoiceDraft.
 * - المال قروش Long حصرًا (عقد P33-P8) — كل تحويل نصي→قروش يمر عبر Money.parseToPiasters
 *   (النقطة القانونية الوحيدة، HALF_UP على التمثيل العشري المكتوب) — صفر ضرب يدوي بـ100.
 * - الأرقام العربية-الهندية والفواصل العربية تُطبَّع عبر Money.normalizeDigits حصرًا.
 * - lineTotal يُعاد حسابه دائمًا Math.round(qty × unitPrice) بنفس صيغة InvoiceItem.lineTotal —
 *   المجموع المطبوع في الإيصال يُقارن به فقط للتحذير ولا يُعتمد ماليًا أبدًا.
 * - الحدود الصلبة: طول النص، عدد البنود، سقف السعر والكمية — التجاوز يُرفض بتحذير لا استثناء.
 * - لا شبكة ولا socket ولا أي وصول للسياق — هذا المحرك قابل للاختبار على JVM صرف.
 */
object OcrInvoiceParser {

    /** أقصى طول نص خام يُقبل — ما بعده يُقص مع تحذير (حماية ذاكرة على إيصالات ملتقطة بخلل) */
    const val MAX_RAW_CHARS = 20_000

    /** أقصى عدد بنود يُقتنص من الإيصال */
    const val MAX_LINES = 60

    /** أقصى سعر بند معقول — 10 مليون ريال بالقروش (ما بعده خطأ OCR يُرفض) */
    const val MAX_LINE_QIRUSH = 1_000_000_000L

    /** أقصى كمية معقولة للبند الواحد */
    const val MAX_QTY = 10_000.0

    private const val MAX_MERCHANT_SCAN_LINES = 8
    private const val MAX_MERCHANT_CHARS = 60
    private const val MAX_DESC_CHARS = 80

    /** بند فاتورة مُستخرج — desc نص حر، المال قروش Long، الكمية Double بعقد InvoiceItem نفسه */
    data class OcrItemLine(
        val desc: String,
        val qty: Double,
        val unitPriceQirush: Long,
        val lineTotalQirush: Long
    )

    /** مسودة فاتورة مهيكلة — كل الحقول اختيارية إلا الثقة والتحذيرات */
    data class OcrInvoiceDraft(
        val merchant: String?,
        val dateMillis: Long?,
        val lines: List<OcrItemLine>,
        val subtotalQirush: Long?,
        val vatRate: Double?,
        val vatQirush: Long?,
        val totalQirush: Long?,
        val totalsConsistent: Boolean,
        val confidence: Double,
        val warnings: List<String>
    )

    // ── قوائم الكلمات المفتاحية (تُطابق على السطر كما هو — العربية لا تتأثر بlowercase) ──
    private val MERCHANT_SKIP = listOf(
        "فاتورة", "invoice", "تاريخ", "date", "رقم", "ضريبة", "tax", "vat",
        "شكرا", "thank", "اجمالي", "إجمالي", "مجموع", "total", "subtotal",
        "المبلغ", "طاولة", "table", "طلب", "order", "كاشير", "cashier",
        "قيمة مضافة", "مدفوع", "المتبقي", "الباقي", "الاجمالي"
    )
    private val SUBTOTAL_KEYS = listOf("المجموع الفرعي", "الإجمالي الفرعي", "المجموع قبل", "subtotal", "sub-total")
    private val TOTAL_KEYS = listOf("الإجمالي", "اجمالي", "إجمالي", "المجموع", "المبلغ النهائي", "grand total", "total due", "total")
    private val VAT_KEYS = listOf("ضريبة", "قيمة مضافة", "vat", "tax")
    private val ITEM_SKIP_EXTRA = listOf("خصم", "discount", "نقد", "cash", "مدفوع", "المتبقي", "الباقي", "رصيد")

    private val HAS_LETTER = Regex("[A-Za-z\\u0600-\\u06FF]")
    private val NUM_TOKEN = Regex("\\d+(?:\\.\\d+)?%?")
    private val PERCENT_RATE = Regex("\\d{1,2}(?:\\.\\d+)?\\s*%")

    // أنماط البنود — بالترتيب: (اسم كمية×سعر مجموع) ثم (اسم كمية سعر مجموع) ثم (اسم كمية×سعر) ثم (اسم سعر)
    private val P_QPT = Regex("^(.+?)\\s+(\\d+(?:\\.\\d+)?)\\s*[x×•]\\s*(\\d+(?:\\.\\d+)?)\\s+(\\d+(?:\\.\\d+)?)$")
    private val P_QPT_SP = Regex("^(.+?)\\s+(\\d+(?:\\.\\d+)?)\\s+(\\d+(?:\\.\\d+)?)\\s+(\\d+(?:\\.\\d+)?)$")
    private val P_QP = Regex("^(.+?)\\s+(\\d+(?:\\.\\d+)?)\\s*[x×•]\\s*(\\d+(?:\\.\\d+)?)$")
    private val P_P = Regex("^(.+?)\\s+(\\d+(?:\\.\\d+)?)$")

    // صيغ التاريخ المدعومة
    private val D_ISO = Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})")
    private val D_DMY_SLASH = Regex("(\\d{1,2})/(\\d{1,2})/(\\d{4})")
    private val D_DMY_DASH = Regex("(\\d{1,2})-(\\d{1,2})-(\\d{4})")
    private val D_DMY_DOT = Regex("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})")

    /** التحليل الرئيسي — لا استثناءات تهرَّب أبدًا، كل شذوذ تحذير في المخرج */
    fun parse(raw: String, allowNamePriceLines: Boolean = false): OcrInvoiceDraft {
        val warnings = mutableListOf<String>()
        fun warn(msg: String) { if (!warnings.contains(msg)) warnings.add(msg) }

        if (raw.isBlank()) {
            return OcrInvoiceDraft(null, null, emptyList(), null, null, null, null, false, 0.0, listOf("نص فارغ — لا شيء لتحليله"))
        }

        val truncated = raw.length > MAX_RAW_CHARS
        if (truncated) warn("النص أطول من الحد المسموح (${MAX_RAW_CHARS} حرفًا) — قُصّ")
        val normalized = Money.normalizeDigits(raw.take(MAX_RAW_CHARS))

        val merchant = extractMerchant(normalized.lines())
        val dateMillis = extractDate(normalized)

        var subtotal: Long? = null
        var vatAmount: Long? = null
        var vatRate: Double? = null
        var total: Long? = null
        val items = mutableListOf<OcrItemLine>()
        var cappedWarned = false

        for (rawLine in normalized.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val lower = line.lowercase()
            // طي فواصل الآلاف قبل أي قارئ أرقام — الفاصلة هنا فاصل آلاف حصرًا (العشرية العربية طُبّعت فوق)
            val collapsed = line.replace(",", "")

            // ── المجاميع أولاً (سطور الكلمات المفتاحية لا تصلح بنودًا مهما بدت أرقامها) ──
            val isSubtotal = SUBTOTAL_KEYS.any { lower.contains(it) }
            if (isSubtotal) {
                lastAmount(collapsed)?.let { subtotal = it }
                continue
            }
            val isVat = VAT_KEYS.any { lower.contains(it) } && !ITEM_SKIP_EXTRA.any { lower.contains(it) }
            if (isVat) {
                lastAmount(collapsed)?.let { vatAmount = it }
                PERCENT_RATE.find(collapsed)?.value?.trimEnd('%', ' ')?.trim()?.toDoubleOrNull()?.let { r ->
                    if (r in 0.0..100.0) vatRate = r
                }
                continue
            }
            if (TOTAL_KEYS.any { lower.contains(it) }) {
                lastAmount(collapsed)?.let { total = it }
                continue
            }
            // التاريخ استُخرج فوق — سطره يُتخطى
            if (lower.contains("تاريخ") || lower.contains("date")) continue
            if (ITEM_SKIP_EXTRA.any { lower.contains(it) }) continue

            // ── البنود ──
            if (items.size >= MAX_LINES) {
                if (!cappedWarned) { warn("عدد البنود تجاوز الحد المسموح — قُصّ إلى ${MAX_LINES} بندًا"); cappedWarned = true }
                continue
            }
            extractItem(collapsed, lower, allowNamePriceLines)?.let { (item, warnMsg) ->
                if (item != null && items.size < MAX_LINES) items.add(item)
                if (warnMsg != null) warn(warnMsg)
            }
        }
        if (items.size == MAX_LINES && !cappedWarned) {
            warn("عدد البنود تجاوز الحد المسموح — قُصّ إلى ${MAX_LINES} بندًا")
        }

        // ── فحوص الاتساق — التسامح: قروشان أو 1% من الأكبر أيهما أعلى ──
        val computedSubtotal = items.sumOf { it.lineTotalQirush }
        var consistent = true
        fun tol(x: Long): Long = maxOf(2L, Math.round(x * 0.01))

        if (items.isNotEmpty() && subtotal != null && Math.abs(computedSubtotal - subtotal!!) > tol(subtotal!!)) {
            warn("عدم تطابق المجموع الفرعي المطبوع مع مجموع البنود المكتشفة")
            consistent = false
        }
        if (subtotal != null && vatAmount != null && total != null &&
            Math.abs(total!! - (subtotal!! + vatAmount!!)) > tol(total!!)
        ) {
            warn("عدم تطابق الإجمالي (المجموع الفرعي + الضريبة)")
            consistent = false
        }
        if (vatRate != null && vatAmount != null && subtotal != null) {
            val expected = Math.round(subtotal!! * vatRate!! / 100.0)
            if (Math.abs(vatAmount!! - expected) > tol(expected)) {
                warn("الضريبة المطبوعة لا تطابق النسبة المعلنة")
                consistent = false
            }
        }

        return OcrInvoiceDraft(
            merchant = merchant,
            dateMillis = dateMillis,
            lines = items.toList(),
            subtotalQirush = subtotal,
            vatRate = vatRate,
            vatQirush = vatAmount,
            totalQirush = total,
            totalsConsistent = consistent,
            confidence = confidence(merchant, dateMillis, items.size, subtotal, vatAmount, vatRate, total, consistent),
            warnings = warnings.toList()
        )
    }

    // ── الثقة: درجة بين 0.0 و0.98 حتمية من وجود الحقول وسلامة الاتساق (لا عشوائية ولا شبكة) ──
    private fun confidence(
        merchant: String?, date: Long?, lineCount: Int,
        subtotal: Long?, vatAmount: Long?, vatRate: Double?, total: Long?, consistent: Boolean
    ): Double {
        if (merchant == null && date == null && lineCount == 0 &&
            subtotal == null && vatAmount == null && vatRate == null && total == null
        ) return 0.05 // فاقد كامل — لا بنود ولا مجاميع ولا هوية
        var c = 0.30
        if (merchant != null) c += 0.10
        if (date != null) c += 0.10
        c += minOf(0.18, 0.06 * lineCount)
        if (subtotal != null) c += 0.12
        if (vatAmount != null || vatRate != null) c += 0.10
        if (total != null) c += 0.12
        if (consistent && (lineCount > 0 || total != null)) c += 0.08
        // [A1] غياب أي مضمون مالي كليًا = مسودة عديمة الفائدة مهما بدا الاسم — سقف صارم 0.30
        if (lineCount == 0 && subtotal == null && vatAmount == null && vatRate == null && total == null) {
            c = minOf(c, 0.30)
        }
        return minOf(0.98, c)
    }

    /** آخر رقم في السطر (بعد طي الآلاف) → قروش عبر المسار القانوني — null إن لم يوجد رقم */
    private fun lastAmount(collapsed: String): Long? =
        NUM_TOKEN.findAll(collapsed).map { it.value }.lastOrNull()
            ?.let { Money.parseToPiasters(it) }

    /** استخراج بند من السطر — يعيد (البند أو null، رسالة تحذير أو null) */
    private fun extractItem(
        line: String, lower: String, allowNamePriceLines: Boolean
    ): Pair<OcrItemLine?, String?> {
        val m = P_QPT.find(line) ?: P_QPT_SP.find(line)
        val qp = P_QP.find(line)
        val isNamePriceOnly: Boolean
        val name: String
        val qtyStr: String
        val priceStr: String
        val statedStr: String
        when {
            m != null -> {
                name = m.groupValues[1]; qtyStr = m.groupValues[2]
                priceStr = m.groupValues[3]; statedStr = m.groupValues[4]
                isNamePriceOnly = false
            }
            qp != null -> {
                name = qp.groupValues[1]; qtyStr = qp.groupValues[2]
                priceStr = qp.groupValues[3]; statedStr = ""
                isNamePriceOnly = false
            }
            allowNamePriceLines -> {
                val md = P_P.find(line) ?: return null to null
                name = md.groupValues[1]; qtyStr = "1"
                priceStr = md.groupValues[2]; statedStr = ""
                isNamePriceOnly = true
            }
            else -> return null to null // ليس بندًا — تجاهل صامت
        }

        val cleanName = name.trim()
        if (!HAS_LETTER.containsMatchIn(cleanName)) return null to null
        if (MERCHANT_SKIP.any { lower.contains(it) } || ITEM_SKIP_EXTRA.any { lower.contains(it) }) return null to null

        val qty = qtyStr.toDoubleOrNull() ?: return null to null
        if (qty <= 0.0 || qty > MAX_QTY) return null to "كمية خارج النطاق المسموح — البند مرفوض"

        val price = Money.parseToPiasters(priceStr)
        if (price <= 0L || price > MAX_LINE_QIRUSH) return null to "سعر خارج النطاق المسموح — البند مرفوض"

        val computed = Math.round(qty * price.toDouble())
        if (statedStr.isNotBlank()) {
            val stated = Money.parseToPiasters(statedStr)
            val tolerance = maxOf(2L, Math.round(computed * 0.01))
            if (Math.abs(computed - stated) > tolerance) {
                // المطبوع يُقارن فقط — المعتمد دائمًا إعادة الحساب الكنونية
                return Pair(
                    OcrItemLine(cleanName.take(MAX_DESC_CHARS), qty, price, computed),
                    "عدم تطابق مجموع السطر «${cleanName.take(20)}» مع الكمية×السعر — اعتُمد الحساب"
                )
            }
        }
        val warnMsg = if (isNamePriceOnly) "سطر بلا كمية — قُبل بكمية 1" else null
        return OcrItemLine(cleanName.take(MAX_DESC_CHARS), qty, price, computed) to warnMsg
    }

    /** الجهة: أول سطر مؤهل من أعلى الإيصال (بلا كلمات محاسبية وبحروف كافية) */
    private fun extractMerchant(allLines: List<String>): String? {
        var scanned = 0
        for (rawLine in allLines) {
            if (scanned >= MAX_MERCHANT_SCAN_LINES) break
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            scanned++
            val lower = line.lowercase()
            if (MERCHANT_SKIP.any { lower.contains(it) }) continue
            if (!HAS_LETTER.containsMatchIn(line)) continue
            val letters = line.count { it.isLetter() }
            val digits = line.count { it.isDigit() }
            if (letters < 2 || digits > letters) continue
            if (line.length > MAX_MERCHANT_CHARS) continue
            return line.take(MAX_MERCHANT_CHARS)
        }
        return null
    }

    /** أول تاريخ معقول في النص — الضربات المدعومة: yyyy-MM-dd · dd/MM/yyyy · dd-MM-yyyy · dd.MM.yyyy */
    private fun extractDate(text: String): Long? {
        fun valid(y: Int, m: Int, d: Int): Long? {
            if (y !in 2000..2100 || m !in 1..12 || d !in 1..31) return null
            val c = GregorianCalendar(y, m - 1, d, 12, 0, 0)
            c.isLenient = false
            return try {
                c.timeInMillis
            } catch (e: Exception) {
                null
            }
        }
        D_ISO.find(text)?.let {
            return valid(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())
        }
        listOf(D_DMY_SLASH, D_DMY_DASH, D_DMY_DOT).forEach { rx ->
            rx.find(text)?.let {
                return valid(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt())
            }
        }
        return null
    }
}
