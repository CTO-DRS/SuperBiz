package com.superbiz.app.domain.ocr

/**
 * [F1 - ADR-005] مطابق أسماء OCR بأسماء المنتجات — منطق نقي صفر اعتماديات.
 *
 * العقد (مختبر وحدويًا في OcrProductMatcherTest):
 * - التطبيع العربي الكامل قبل المقارنة: تطبيع الأرقام (نفس تعاقد Money.normalizeDigits)،
 *   إسقاط التشكيل والتطويل، توحيد الهمزات (أإآٱ→ا) و(ى→ي) و(ئ→ي) و(ؤ→و) و(ة→ه)،
 *   إسقاط كل ما ليس حرفًا أو رقمًا، وتوحيد الحروف اللاتينية lowercase.
 * - الدرجة: 1.0 للمطابقة التامة بعد التطبيع، 0.85 + تناسب الأطوال للاحتواء،
 *   وإلا نسبة Levenshtein (1 − المسافة/أطول النصين).
 * - حد القرار الافتراضي 0.55 — تحته null صريح، لا «أقرب تخمين» خلف الحد.
 * - حتمي بالكامل: نفس المدخلات تعيد نفس المخرج دائمًا (عقد خوارزميات SuperBiz — بلا AI خارجي).
 */
object OcrProductMatcher {

    /** حد القرار الافتراضي — تحت هذا لا مطابقة */
    const val DEFAULT_THRESHOLD = 0.55

    private const val MAX_NORM_LEN = 64

    /** نتيجة مطابقة — معرف المنتج واسمه الكتالوجي ودرجة الثقة */
    data class Match(val productId: Long, val productName: String, val score: Double)

    private val TASHKEEL = Regex("[\\u064B-\\u065F\\u0670\\u0640]")

    /** التطبيع القانوني للنص قبل أي مقارنة — نفس روح Money.normalizeDigits مع التوحيد العربي */
    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when {
                ch in '\u0660'..'\u0669' -> sb.append(('0' + (ch - '\u0660')))
                ch in '\u06F0'..'\u06F9' -> sb.append(('0' + (ch - '\u06F0')))
                ch == 'أ' || ch == 'إ' || ch == 'آ' || ch == 'ٱ' -> sb.append('ا')
                ch == 'ى' || ch == 'ئ' -> sb.append('ي')
                ch == 'ؤ' -> sb.append('و')
                ch == 'ة' -> sb.append('ه')
                else -> sb.append(ch)
            }
        }
        val deTashkeel = TASHKEEL.replace(sb.toString(), "")
        val kept = deTashkeel.filter { it.isLetterOrDigit() }
        return kept.lowercase().take(MAX_NORM_LEN)
    }

    /** درجة التشابه بين نصين بعد التطبيع — بين 0.0 و1.0 */
    fun similarity(a: String, b: String): Double {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0
        val minLen = minOf(na.length, nb.length)
        val maxLen = maxOf(na.length, nb.length)
        if (na.contains(nb) || nb.contains(na)) {
            return 0.85 + 0.10 * (minLen.toDouble() / maxLen)
        }
        return 1.0 - (levenshtein(na, nb).toDouble() / maxLen)
    }

    /**
     * أفضل مطابقة لاسم OCR ضمن كتالوج المنتجات — أو null تحت حد القرار.
     * عند التعادل تُفضل الدرجة الأعلى ثم الترتيب الأسبق في الكتالوج.
     */
    fun match(
        ocrName: String,
        products: List<Pair<Long, String>>,
        threshold: Double = DEFAULT_THRESHOLD
    ): Match? {
        if (ocrName.isBlank() || products.isEmpty()) return null
        var best: Match? = null
        for ((id, name) in products) {
            if (name.isBlank()) continue
            val score = similarity(ocrName, name)
            if (score >= threshold && (best == null || score > best!!.score)) {
                best = Match(id, name, score)
            }
        }
        return best
    }

    /** Levenshtein كلاسيكي بصفين — على النصين المطبعين المقصوصين حصرًا */
    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }
}
