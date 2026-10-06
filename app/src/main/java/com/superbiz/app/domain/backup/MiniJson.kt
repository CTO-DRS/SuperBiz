package com.superbiz.app.domain.backup

/**
 * [P13-b] MiniJson — أصغر عدة JSON مكتوبة يدوياً (Kotlin نقي، بلا أي استيراد Android):
 * تخدم صيغة النسخ الاحتياطي فقط (BackupKit) ولا تدعي تغطية JSON العام.
 *
 * • escapeJson: تهريب `" \` \n \r \t` وبقية محارف التحكم <0x20 إلى \u00XX.
 *   محارف يونيكود (عربي/صيني/إيموجي) تُمرَّر كما هي — النص كله UTF-8 عند الكتابة.
 * • parseJson/MiniJsonParser: محلل تنازلي متكرر يزحف بمؤشر واحد (O(n) بلا فحص substring
 *   متكرر في كل خطوة)، ينتج:
 *     object → Map<String, Any?> (محافظة على ترتيب المفاتيح)
 *     array  → List<Any?>
 *     string → String، عدد صحيح → Long، عدد عشري/أسي → Double، true/false/null
 * • [P33-P8] قرار الترحيل المالي: لا تعديل هنا عمداً — parseNumber يفصل أصلاً الصحيح
 *   (Long) عن العشري/الأسي (Double)، وهو بالضبط ما يلزم BackupKit: قروش الملفات الجديدة
 *   (1250) تعود Long فتُقرأ كما هي، وريالات الملفات القديمة (12.5) تعود Double فيحوّلها
 *   moneyF عبر Money.toPiasters — التحويل كله في طبقة BackupKit (انظر moneyF هناك).
 * • المدخلات المشوّهة ترمي MiniJsonException برسالة تحمل موضع الخطأ — المستدعي يلتقطها
 *   ولا تنهار أبداً (عقد parseBackup: يلتقط ويعيد error نصياً).
 * • الأداء: 10 آلاف صف تُحلَّل خلال ثوانٍ — لا استدعاءات تخصيص ثقيلة لكل حرف.
 */

/** [P13-b] خطأ تحليل JSON مع موضع الحرف الذي وقع عنده (يبدأ من 0) */
class MiniJsonException(message: String) : Exception(message)

/** [P13-b] تهريب نص واحد إلى قيمة JSON سلسلة آمنة (داخل اقتباسات يضيفها المستدعي) */
fun escapeJson(s: String): String {
    // المسار السريع: بلا محارف خاصة يعود النص كما هو — الصفوف النظيفة لا تُكلف شيئاً
    var needs = false
    for (ch in s) {
        if (ch == '"' || ch == '\\' || ch == '\n' || ch == '\r' || ch == '\t' || ch < ' ') {
            needs = true
            break
        }
    }
    if (!needs) return s
    val sb = StringBuilder(s.length + 16)
    for (ch in s) {
        when {
            ch == '"' -> sb.append("\\\"")
            ch == '\\' -> sb.append("\\\\")
            ch == '\n' -> sb.append("\\n")
            ch == '\r' -> sb.append("\\r")
            ch == '\t' -> sb.append("\\t")
            // بقية محارف التحكم (<0x20) تُهرب \u00XX — الصيغة السداسية العشرية صغيرة دائماً
            ch < ' ' -> sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
            else -> sb.append(ch)
        }
    }
    return sb.toString()
}

/** [P13-b] تحليل نص JSON كامل — أي محتوى تالٍ بعد القيمة الجذرية يرفض (لا سمج بالحمولة) */
fun parseJson(text: String): Any? = MiniJsonParser(text).parse()

/** [P13-b] محلل JSON تنازلي متكرر بمؤشر واحد — لا يعتمد على أي واجهة Android */
class MiniJsonParser(private val text: String) {

    private var pos = 0
    // [P20-FIX agent18]: حدّ التداخل — كان الحلّال العودي بلا سقف، ملف فاسد بعشرة آلاف [
    // (=10KB فقط) كان يُجهد المكدّس بـ StackOverflowError وهو Error يهرب من catch(Exception)
    // في حوافّ الاستعادة كلها فيُسقط العملية. 64 مستوى أوسع بكثير من أي نسخة حقيقية
    private var depth = 0

    /** يُحلِّل قيمة واحدة ثم يصرّ أن ينتهي النص (أي ما بعد الجذر = خطأ «حمولة تالفة») */
    fun parse(): Any? {
        skipWs()
        val v = parseValue()
        skipWs()
        if (pos < text.length) fail("unexpected trailing content")
        return v
    }

    private fun fail(msg: String): Nothing = throw MiniJsonException("$msg (position $pos)")

    /** فراغات JSON المسموحة فقط: مسافة، جدولة، سطر جديد، إرجاع سرّ */
    private fun skipWs() {
        while (pos < text.length) {
            when (text[pos]) {
                ' ', '\t', '\n', '\r' -> pos++
                else -> return
            }
        }
    }

    private fun parseValue(): Any? {
        if (pos >= text.length) fail("unexpected end of input")
        if (++depth > 64) fail("nesting too deep")
        try {
            return when (text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { literal("true"); true }
                'f' -> { literal("false"); false }
                'n' -> { literal("null"); null }
                else ->
                    if (text[pos] == '-' || text[pos] in '0'..'9') parseNumber()
                    else fail("unexpected character '${text[pos]}'")
            }
        } finally {
            depth--
        }
    }

    /** كلمة محفوظة بالضبط (true/false/null) — أي اختصار مثل tru يُرفض */
    private fun literal(word: String) {
        if (pos + word.length > text.length || !text.startsWith(word, pos)) {
            fail("invalid literal, expected '$word'")
        }
        pos += word.length
    }

    private fun parseObject(): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        pos++ // '{'
        skipWs()
        if (pos < text.length && text[pos] == '}') { pos++; return map }
        while (true) {
            skipWs()
            if (pos >= text.length) fail("unterminated object")
            if (text[pos] != '"') fail("expected string key in object")
            val key = parseString()
            skipWs()
            if (pos >= text.length || text[pos] != ':') fail("expected ':' after object key")
            pos++
            skipWs()
            map[key] = parseValue() // مفتاح مكرر: الأخير يفوز (السلوك القياسي)
            skipWs()
            if (pos >= text.length) fail("unterminated object")
            when (text[pos]) {
                ',' -> pos++
                '}' -> { pos++; return map }
                else -> fail("expected ',' or '}' in object")
            }
        }
    }

    private fun parseArray(): List<Any?> {
        val list = ArrayList<Any?>()
        pos++ // '['
        skipWs()
        if (pos < text.length && text[pos] == ']') { pos++; return list }
        while (true) {
            skipWs()
            list.add(parseValue())
            skipWs()
            if (pos >= text.length) fail("unterminated array")
            when (text[pos]) {
                ',' -> pos++
                ']' -> { pos++; return list }
                else -> fail("expected ',' or ']' in array")
            }
        }
    }

    private fun parseString(): String {
        val start = pos
        pos++ // '"'
        val sb = StringBuilder()
        while (true) {
            if (pos >= text.length) {
                pos = start // الإبلاغ عن موضع بداية السلسلة غير المكتملة أدق للمستخدم
                fail("unterminated string")
            }
            when (val c = text[pos]) {
                '"' -> { pos++; return sb.toString() }
                '\\' -> {
                    pos++
                    if (pos >= text.length) fail("dangling escape at end of input")
                    when (val e = text[pos]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C') // تغذية النموذج \u000C — لا حرف اقتباس مباشراً لها في Kotlin
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 5 > text.length) fail("invalid unicode escape")
                            val code = text.substring(pos + 1, pos + 5).toIntOrNull(16)
                                ?: fail("invalid unicode escape")
                            sb.append(code.toChar()) // الأزواج البديلة تُجمَّع طبيعياً في نص UTF-16
                            pos += 4
                        }
                        else -> fail("invalid escape '\\$e'")
                    }
                    pos++
                }
                else -> {
                    // محرف تحكم خام داخل سلسلة = JSON مشوّه (كاتبنا يهربها دائماً)
                    if (c < ' ') fail("unescaped control character in string")
                    sb.append(c)
                    pos++
                }
            }
        }
    }

    /**
     * أعداد: [-] int [.frac] [eE[+-]digits] — صحيح بلا كسر/أس → Long (يومض على 64-bit
     * بدقة كاملة، وإن فاض فسقوط محسوب إلى Double)، وسائرها → Double.
     */
    private fun parseNumber(): Any {
        val start = pos
        if (text[pos] == '-') pos++
        var sawDigit = false
        while (pos < text.length && text[pos] in '0'..'9') { pos++; sawDigit = true }
        var isDouble = false
        if (pos < text.length && text[pos] == '.') {
            pos++
            // [P13-b] JSON spec: frac = '.' 1*DIGIT — «1.» غير صالح ويجب أن يُرفض
            if (pos >= text.length || text[pos] !in '0'..'9') fail("invalid number fraction")
            isDouble = true
            while (pos < text.length && text[pos] in '0'..'9') { pos++; sawDigit = true }
        }
        if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
            isDouble = true
            pos++
            if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
            var expDigits = false
            while (pos < text.length && text[pos] in '0'..'9') { pos++; expDigits = true }
            if (!expDigits) fail("invalid number exponent")
        }
        if (!sawDigit) fail("invalid number")
        val token = text.substring(start, pos)
        return if (!isDouble) {
            // فائض Long (أرقام عملاقة يدوية) يهبط Double بدل أن يفشل الملف كله
            token.toLongOrNull() ?: token.toDoubleOrNull() ?: fail("invalid number '$token'")
        } else {
            token.toDoubleOrNull() ?: fail("invalid number '$token'")
        }
    }
}
