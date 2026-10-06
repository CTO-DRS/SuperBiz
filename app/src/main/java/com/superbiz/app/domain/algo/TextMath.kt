package com.superbiz.app.domain.algo

/**
 * — TextMath: خوارزميات نصية نقية للبحث الضبابي العربي.
 *
 * كل الدوال نقية وبلا حالة وبلا Android وبلا Regex — قابلة للاختبار على JVM، ومغطاة بالكامل في TextMathTest.
 * الغرض: ترتيب نتائج البحث عن الأطراف والأصناف (deep search ranking) بأسماء عربية واقعية.
 * التطبيع العربي الموحّد في arabicNormalize() هو المدخل القياسي لأي مقارنة مركّبة (fuzzyScore).
*/
object TextMath {

    // ───────── 1) مسافة ليفنشتاين والتشابه النسبي ─────────

    /**
     * مسافة التحرير (Levenshtein): أقل عدد من عمليات (إدراج/حذف/استبدال) لتحويل a إلى b.
     * برمجة ديناميكية بصفّين int فقط — ذاكرة O(طول أقصر النصين).
     * النص الفارغ يعطي طول الآخر، والنصان المتطابقان يعطيان 0.
     * مثال: levenshtein("كتاب", "كتب") = 1 (حذف الألف فقط).
     */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        var longer = a
        var shorter = b
        if (shorter.length > longer.length) {
            val tmp = longer
            longer = shorter
            shorter = tmp
        }
        if (shorter.isEmpty()) return longer.length
        var prev = IntArray(shorter.length + 1) { it }
        var curr = IntArray(shorter.length + 1)
        for (i in 1..longer.length) {
            curr[0] = i
            val ca = longer[i - 1]
            for (j in 1..shorter.length) {
                val cost = if (ca == shorter[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[shorter.length]
    }

    /**
     * نسبة التشابه من 0.0 إلى 1.0 = 1 − (levenshtein / أطول طول).
     * النصان الفارغان معاً → 1.0 (تطابق تام بلا معلومات)،
     * واحد منهما فارغ → 0.0 (مسافة = طول الآخر).
     */
    fun similarityRatio(a: String, b: String): Double {
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / maxLen
    }

    // ───────── 2) جارو-وينكلر ─────────

    /**
     * تشابه جارو (نسبة الحروف المتطابقة داخل نافذة + انتقالات) مع دفعة وينكلر:
     * إذا كان جارو > 0.7 تُضاف دفعة طول البادئة المشتركة (حتى 4) × prefixScale × (1 − jaro)
     * — الصيغة الكلاسيكية لـ Winkler — مع سقف 1.0.
     * المرجع القياسي: jaroWinkler("MARTHA", "MARHTA") ≈ 0.9611.
     * النصان الفارغان معاً → 1.0، وأحدهما فارغ أو لا حروف مشتركة → 0.0.
     */
    fun jaroWinkler(a: String, b: String, prefixScale: Double = 0.1): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val lenA = a.length
        val lenB = b.length
        val matchDist = maxOf(0, maxOf(lenA, lenB) / 2 - 1)
        val aMatch = BooleanArray(lenA)
        val bMatch = BooleanArray(lenB)
        var matches = 0
        for (i in 0 until lenA) {
            val start = maxOf(0, i - matchDist)
            val end = minOf(lenB - 1, i + matchDist)
            for (j in start..end) {
                if (!bMatch[j] && a[i] == b[j]) {
                    aMatch[i] = true
                    bMatch[j] = true
                    matches++
                    break
                }
            }
        }
        if (matches == 0) return 0.0
        var halfTranspositions = 0
        var k = 0
        for (i in 0 until lenA) {
            if (!aMatch[i]) continue
            while (!bMatch[k]) k++
            if (a[i] != b[k]) halfTranspositions++
            k++
        }
        val m = matches.toDouble()
        val jaro = (m / lenA + m / lenB + (m - halfTranspositions / 2.0) / m) / 3.0
        if (jaro <= 0.7) return jaro
        val maxPrefix = minOf(4, lenA, lenB)
        var prefix = 0
        while (prefix < maxPrefix && a[prefix] == b[prefix]) prefix++
        val boosted = jaro + prefix * prefixScale * (1.0 - jaro)
        return minOf(1.0, boosted)
    }

    // ───────── 3) التطابق بالبداية والكلمات ─────────

    /** تقسيم على المسافات البيضاء يدوياً (بلا Regex) مع إسقاط الفراغات المكررة */
    private fun tokenize(s: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (c in s) {
            if (Character.isWhitespace(c)) {
                if (sb.isNotEmpty()) {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
            } else {
                sb.append(c)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /**
     * درجة مطابقة البداية: 1.0 إذا بدأ الهدف بالاستعلام كاملاً،
     * 0.5 إذا بدأت **أي كلمة** من كلمات الهدف بالاستعلام، وإلا 0.0.
     * تجاهل حالة الأحرف والفراغات الطرفية؛ الاستعلام الفارغ → 0.0.
     */
    fun prefixScore(query: String, target: String): Double {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return 0.0
        val t = target.trim().lowercase()
        if (t.startsWith(q)) return 1.0
        for (word in tokenize(t)) {
            if (word.startsWith(q)) return 0.5
        }
        return 0.0
    }

    /**
     * نسبة كلمات الاستعلام التي تظهر كبداية (prefix) لأي كلمة من كلمات الهدف (تجاهل حالة الأحرف).
     * مثال: tokenScore("نجم عام", "نجم للخدمات العامة") = 0.5 (نجم ✓، عام ✗).
     * استعلام فارغ/فراغات فقط → 0.0.
     */
    fun tokenScore(query: String, target: String): Double {
        val queryTokens = tokenize(query.lowercase())
        if (queryTokens.isEmpty()) return 0.0
        val targetTokens = tokenize(target.lowercase())
        if (targetTokens.isEmpty()) return 0.0
        var hits = 0
        for (q in queryTokens) {
            for (t in targetTokens) {
                if (t.startsWith(q)) {
                    hits++
                    break
                }
            }
        }
        return hits.toDouble() / queryTokens.size
    }

    /**
     * مطابقة الأحرف الأولى: هل حروف أوائل كلمات الاستعلام تساوي بالترتيب أوائل كلمات الهدف؟
     * قاعدتان متكافئتان للبحث الواقعي:
     *  (أ) أوائل كلمات الهدف تبدأ بتسلسل أوائل كلمات الاستعلام — "ن ل" تطابق "نجم للخدمات".
     *  (ب) بداية الهدف بلا فراغات تبدأ بتسلسل الأحرف المختصرة — "ن ج م" تطابق "نجم للخدمات"
     *      (لأن حروف كلمة "نجم" نفسها ن + ج + م).
     * استعلام بكلمة واحدة فقط أو فارغ → false.
     */
    fun initialsMatch(query: String, target: String): Boolean {
        val queryTokens = tokenize(arabicNormalize(query))
        if (queryTokens.size < 2) return false
        val targetTokens = tokenize(arabicNormalize(target))
        if (targetTokens.isEmpty()) return false
        val initials = StringBuilder()
        for (t in queryTokens) initials.append(t.first())
        // (أ) أوائل كلمات الهدف بالترتيب
        if (targetTokens.size >= queryTokens.size) {
            var ok = true
            for (i in queryTokens.indices) {
                if (targetTokens[i].first() != queryTokens[i].first()) {
                    ok = false
                    break
                }
            }
            if (ok) return true
        }
        // (ب) اختصار حروف متصلة في بداية الاسم بلا فراغات
        val joined = StringBuilder()
        for (t in targetTokens) joined.append(t)
        return joined.startsWith(initials.toString())
    }

    // ───────── 4) التطبيع العربي والأرقام ─────────

    /**
     * توحيد النص العربي للبحث: أ إ آ → ا، ة → ه، ؤ → و، ئ → ي،
     * إسقاط التشكيل (U+064B..U+0652)، وتصغير اللاتينية A-Z.
     * بلا Regex — معالجة حرفاً بحرف، والنص فارغ يبقى فارغاً.
     *
     * [P7-L3 إصلاح] هذه هي الدالة التطبيعية **القياسية** للمشروع الآن، وأكملت
     * الفجوة مع عرف BizMath.arabicNormalize (المصلَّح ذاته هنا لا هناك — BizMath
     * خارج الملكية): ألف الوصل ٱ (U+0671) → ا، الألف المقصورة ى (U+0649) → ي،
     * وإسقاط التطويل ـ (U+0640). قبلها كانت «علي»/«على» و«شــركة»/«شركة»
     * تُعامَل نصين مختلفين في fuzzyScore/initialsMatch ومطابقات الأطراف
     * (DebtsVM/InvoiceText/DebtPlanP4/R10Smart كلها تستهلك هذه الدالة).
     * الدلتا المتبقية الموثقة مع BizMath: هو يصغّر كل محرف (lowercaseChar)
     * بينما هنا A-Z فقط — سلوك واحد على العربية واللاتينية القياسية،
     * ومطابقة ContactsSheet (تطبيع جهات الاتصال) مفوَّضة إلى هذه الدالة منذ L3.
     */
    fun arabicNormalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                'أ', 'إ', 'آ', 'ٱ' -> sb.append('ا')
                'ة' -> sb.append('ه')
                'ؤ' -> sb.append('و')
                'ئ', 'ى' -> sb.append('ي')
                in '\u064B'..'\u0652' -> { /* تشكيل: يُهمَل */ }
                '\u0640' -> { /* تطويل: يُهمَل */ }
                in 'A'..'Z' -> sb.append(c.lowercaseChar())
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * استخراج الأرقام فقط: الأرقام العربية-الهندية ٠-٩ (U+0660..U+0669)
     * والفارسية/الأردية ۰-۹ (U+06F0..U+06F9) تُحوَّل إلى ASCII،
     * والأرقام اللاتينية تبقى كما هي، وكل حرف آخر يُهمَل.
     * مثال: "+٩٦٦ 50 ab" → "96650".
     */
    fun digitsOnly(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                in '0'..'9' -> sb.append(c)
                in '\u0660'..'\u0669' -> sb.append('0' + (c - '\u0660'))
                in '\u06F0'..'\u06F9' -> sb.append('0' + (c - '\u06F0'))
            }
        }
        return sb.toString()
    }

    // ───────── 5) التقييم المركّب وترتيب النتائج ─────────

    /**
     * درجة ضبابية مركّبة على النصين بعد التطبيع العربي (arabicNormalize + trim لكليهما):
     * تطابق تام → 1.0؛ الهدف يحوي الاستعلام كجزء → 0.95؛
     * وإلا أعلى: prefixScore، أو 0.9 × tokenScore، أو 0.85 × jaroWinkler.
     * استعلام فارغ أو يصير فارغاً بعد التطبيع (فراغات/تشكيل فقط) → 0.0.
     */
    fun fuzzyScore(query: String, target: String): Double {
        if (query.isBlank()) return 0.0
        val q = arabicNormalize(query).trim()
        if (q.isEmpty()) return 0.0
        val t = arabicNormalize(target).trim()
        if (q == t) return 1.0
        if (t.contains(q)) return 0.95
        return maxOf(
            prefixScore(q, t),
            0.9 * tokenScore(q, t),
            0.85 * jaroWinkler(q, t),
        )
    }

    /**
     * ترتيب الأهداف حسب fuzzyScore تنازلياً: يعيد أزواج (الفهرس الأصلي، الدرجة)
     * بشرط الدرجة ≥ threshold، مع تثبيت التعادل بترتيب الفهرس تصاعدياً،
     * وقصّ النتيجة إلى limit. limit ≤ 0 → قائمة فارغة.
     */
    fun rankByFuzzy(
        query: String,
        targets: List<String>,
        limit: Int = 10,
        threshold: Double = 0.4,
    ): List<Pair<Int, Double>> {
        if (limit <= 0) return emptyList()
        return targets.asSequence()
            .mapIndexed { index, target -> index to fuzzyScore(query, target) }
            .filter { it.second >= threshold }
            .sortedWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { it.first })
            .take(limit)
            .toList()
    }
}
