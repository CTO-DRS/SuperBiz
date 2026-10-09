package com.superbiz.app.domain

/**
 * — بنّاء/قارئ تصدير إعدادات JSON (وظيفة 33) — نقي بالكامل
 * بلا أي اعتماديات Android (حتى org.json غير مستخدم ليبقى قابلاً للاختبار وحداتياً).
 *
 * القاعدة الأمنية: **قائمة بيضاء** — المفاتيح المصدَّرة/المستوردة هي وحدها المدرجة في
 * SPECS أدناه، وكل مفتاح آخر يُرفض صراحةً عند الاستيراد (مجهول = مرفوض).
 *
 * قائمة الاستثناءات الموثَّقة (لا تُصدَّر ولا تُستورد أبداً)
 * • أسرار/أمان: pinHash, pinSalt, pinBlob, pinIters, pinLength (مواد الرمز)،
 * biometric (بوابة دخول حساسة تُضبط محلياً فقط).
 * • بيانات أعمال: businessName, ownerName, phone, email, address, taxNumber,
 * monthlyGoal, walkInPartyId, posHeldCart (سلة معلّقة)، recentSearches.
 * • حالة جهاز/مسارات محلية: backupDirUri (SAF URI لا يصلح لجهاز آخر)، avatarPath.
 * • أعلام حالة داخلية: seeded, welcomeSeen, permissionsSeen، وأختام الأوقات
 * lastAutoBackup, lastScheduledReport.
 *
 * الصيغة: ‎{"format":"superbiz-settings","version":1,"values":{...}}‎ — قيم عددية فقط
 * (نص/عدد/قيمة منطقية)، ورفض صارم: مفتاح مجهول، مفتاح محظور (غير مدرج)، نوع خطأ،
 * قيمة خارج النطاق، مفتاح مكرر، كائن/مصفوفة متداخلة، JSON مشوّه، ترميز قابلية فارغ.
*/
object SettingsCodec {

    const val FORMAT = "superbiz-settings"
    const val VERSION = 1

    /** قيمة مُصدَّرة: نص أو عدد (Double سالم) أو قيمة منطقية */
    sealed class Val {
        data class S(val v: String) : Val()
        data class N(val v: Double) : Val()
        data class B(val v: Boolean) : Val()
    }

    data class Entry(val key: String, val value: Val)

    /** مواصفة مفتاح: نوعه + نطاقه العددي أو قيمه النصية المسموحة */
    data class Spec(
        val type: Char,                       // 's' نص | 'n' عدد | 'b' منطقي
        val min: Double = 0.0,
        val max: Double = 0.0,
        val allowed: Set<String>? = null,     // للنصوص: قيمة مسموحة محددة
        val pattern: Regex? = null            // للنصوص الحرّة: قيد صيغة
    )

    /** القائمة البيضاء — منتفاة عنها كل المفاتيح المحظورة أعلاه */
    val SPECS: Map<String, Spec> = linkedMapOf(
        // عام
        // [H5-2 V 3.2.0] اللغات الست (العربية، الإنجليزية، التركية، الفرنسية، الأردية، الإندونيسية)
        "language" to Spec('s', allowed = setOf("ar", "en", "tr", "fr", "ur", "id")),
        // [H5-3 V 3.2.0] الولاية الضريبية الخليجية
        "taxJurisdiction" to Spec('s', allowed = com.superbiz.app.domain.GulfTax.CODES),
        "theme" to Spec('s', allowed = setOf("dark", "light", "auto")),
        "baseCurrency" to Spec('s', pattern = Regex("[A-Z]{3}")),
        "taxRate" to Spec('n', min = 0.0, max = 100.0),
        // مظهر
        "fontScale" to Spec('n', min = 0.85, max = 1.30),
        "dynamicColors" to Spec('b'),
        "mirrorChartsRtl" to Spec('b'),
        // سلوك
        "hapticsEnabled" to Spec('b'),
        "confirmDestructive" to Spec('b'),
        // أداء
        "animationsEnabled" to Spec('b'),
        // خصوصية (الطمس الجديد للوظيفة 38 يُصدَّر أيضاً — تفضيل عرض لا سرّ)
        "flagSecure" to Spec('b'),
        "redactWidgets" to Spec('b'),
        "privacyBlur" to Spec('b'),
        "lockTimeoutMin" to Spec('n', min = 0.0, max = 60.0),
        // إشعارات
        "lowStockAlerts" to Spec('b'),
        "receivableAlerts" to Spec('b'),
        // بيانات
        // السقف 30 كان يرمي out_of_range لقيمة مشروعة (31..365) تكتبها
        // setAutoBackupDays (تقبل حتى 365) فيموت تصدير الإعدادات كله برسالة «فشل» عمياء
        "autoBackupDays" to Spec('n', min = 0.0, max = 365.0),
        "reportScheduleDays" to Spec('n', min = 0.0, max = 365.0),
        "reportScheduleHour" to Spec('n', min = 0.0, max = 23.0),
        "reportScheduleChannel" to Spec('s', allowed = setOf("whatsapp", "email")),
        "reportRecipient" to Spec('s', pattern = Regex("[^\\r\\n\\u0000-\\u001F]{0,254}")),
        // متقدم
        "arabicReceiptMode" to Spec('n', min = 0.0, max = 1.0),
        "defaultLowStockQty" to Spec('n', min = 0.0, max = 9999.0),
        "defaultTargetMargin" to Spec('n', min = 0.0, max = 90.0),
        "lateFeeDailyPct" to Spec('n', min = 0.0, max = 5.0),
        "lateFeeCapPct" to Spec('n', min = 0.0, max = 100.0),
        "weekendFriSat" to Spec('b'),
        "searchFuzzyThreshold" to Spec('n', min = 0.2, max = 0.8),
        "eoqOrderCost" to Spec('n', min = 0.0, max = 10000.0),
        // مصروفات
        "expenseMonthlyLimit" to Spec('n', min = 0.0, max = 1_000_000_000.0)
    )

    // ─────────────── البنّاء ───────────────

    /**
     * بناء JSON من أزواج قيمة مُسمّاة — يتحقق من كل زوج مقابل SPECS (نوع/نطاق/سلامة عددية)
     * ويرفض بالمفتاح المجهول أو القيمة غير الصالحة برمي IllegalArgumentException برمز السبب.
     */
    fun build(entries: List<Entry>): String {
        require(entries.isNotEmpty()) { "empty" }
        val sb = StringBuilder()
        sb.append("{\"format\":\"").append(FORMAT).append("\",\"version\":").append(VERSION).append(",\"values\":{")
        val seen = HashSet<String>()
        entries.forEachIndexed { i, e ->
            val spec = SPECS[e.key] ?: throw IllegalArgumentException("unknown_key:${e.key}")
            require(seen.add(e.key)) { "duplicate:${e.key}" }
            sb.append(quote(e.key)).append(":")
            when (val v = e.value) {
                is Val.S -> {
                    if (spec.type != 's') throw IllegalArgumentException("bad_type:${e.key}")
                    validateString(e.key, v.v, spec)
                    sb.append(quote(v.v))
                }
                is Val.N -> {
                    if (spec.type != 'n') throw IllegalArgumentException("bad_type:${e.key}")
                    if (!v.v.isFinite()) throw IllegalArgumentException("bad_number:${e.key}")
                    if (v.v < spec.min || v.v > spec.max) throw IllegalArgumentException("out_of_range:${e.key}")
                    sb.append(num(v.v))
                }
                is Val.B -> {
                    if (spec.type != 'b') throw IllegalArgumentException("bad_type:${e.key}")
                    sb.append(if (v.v) "true" else "false")
                }
            }
            if (i < entries.size - 1) sb.append(",")
        }
        sb.append("}}")
        return sb.toString()
    }

    // ─────────────── القارئ ───────────────

    sealed class ParseResult {
        data class Ok(val values: List<Entry>) : ParseResult()
        data class Err(val reason: String) : ParseResult()
    }

    /** قراءة صارمة: إما قائمة أزواج صالحة أو سبب رفض (لا استثناءات) */
    fun parse(json: String?): ParseResult {
        if (json.isNullOrBlank()) return ParseResult.Err("empty")
        return try {
            ParseResult.Ok(parseStrict(json))
        } catch (e: IllegalArgumentException) {
            ParseResult.Err(e.message ?: "malformed")
        } catch (e: Exception) {
            ParseResult.Err("malformed")
        }
    }

    private fun parseStrict(raw: String): List<Entry> {
        val p = Parser(raw)
        p.skipWs()
        p.expect('{')
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        var first = true
        // ترويسة الصيغة
        val header = HashSet<String>()
        var values: List<Entry>? = null
        while (true) {
            p.skipWs()
            if (p.peek() == '}' && first) { p.next(); break }        // كائن فارغ تماماً
            if (!first) {
                p.skipWs()
                if (p.peek() == '}') { p.next(); break }
                p.expect(',')
            }
            p.skipWs()
            val key = p.readString()
            p.skipWs()
            p.expect(':')
            p.skipWs()
            when (key) {
                "format" -> {
                    val v = p.readString()
                    if (v != FORMAT) throw IllegalArgumentException("bad_format")
                    header.add("format")
                }
                "version" -> {
                    val v = p.readNumber()
                    if (v != VERSION.toDouble()) throw IllegalArgumentException("bad_version")
                    header.add("version")
                }
                "values" -> {
                    if (p.peek() != '{') throw IllegalArgumentException("bad_values")
                    p.next()
                    values = readValues(p, seen)
                    header.add("values")
                }
                else -> throw IllegalArgumentException("unknown_key:$key")
            }
            first = false
            if (header.size == 3) {
                // بعد values لا يُقبل إلا الإغلاق أو فاصلة (تُعالج في رأس الحلقة) — تابع القراءة
            }
        }
        p.skipWs()
        if (!p.atEnd()) throw IllegalArgumentException("malformed")
        if (!header.containsAll(setOf("format", "version", "values"))) throw IllegalArgumentException("bad_format")
        if (values.isNullOrEmpty()) throw IllegalArgumentException("empty_values")
        return values
    }

    private fun readValues(p: Parser, seen: HashSet<String>): List<Entry> {
        val out = ArrayList<Entry>()
        var first = true
        while (true) {
            p.skipWs()
            if (p.peek() == '}') { p.next(); break }
            if (!first) { p.expect(','); p.skipWs() }
            val key = p.readString()
            val spec = SPECS[key] ?: throw IllegalArgumentException("unknown_key:$key")
            if (!seen.add(key)) throw IllegalArgumentException("duplicate:$key")
            p.skipWs()
            p.expect(':')
            p.skipWs()
            val entry = when (spec.type) {
                's' -> {
                    if (p.peek() != '"') throw IllegalArgumentException("bad_type:$key")
                    Entry(key, Val.S(validateString(key, p.readString(), spec)))
                }
                'n' -> {
                    if (p.peek() != '-' && (p.peek() < '0' || p.peek() > '9'))
                        throw IllegalArgumentException("bad_type:$key")
                    val n = p.readNumber()
                    if (!n.isFinite()) throw IllegalArgumentException("bad_number:$key")
                    if (n < spec.min || n > spec.max) throw IllegalArgumentException("out_of_range:$key")
                    Entry(key, Val.N(n))
                }
                'b' -> {
                    val b = p.readBoolean()
                        ?: throw IllegalArgumentException("bad_type:$key")
                    Entry(key, Val.B(b))
                }
                else -> throw IllegalArgumentException("malformed")
            }
            out.add(entry)
            first = false
        }
        return out
    }

    private fun validateString(key: String, v: String, spec: Spec): String {
        spec.allowed?.let { if (v !in it) throw IllegalArgumentException("bad_value:$key") }
        spec.pattern?.let { if (!it.matches(v)) throw IllegalArgumentException("bad_value:$key") }
        return v
    }

    // ─────────────── أدوات الترميز ───────────────

    private fun num(v: Double): String = if (v == v.toLong().toDouble() && kotlin.math.abs(v) < 1e15)
        v.toLong().toString() else v.toString()

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        sb.append('"')
        return sb.toString()
    }

    /** ماسح JSON صارم لمجموعة جزئية كافية: سلاسل/أعداد/قيم منطقية فقط */
    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd(): Boolean = i >= s.length
        fun peek(): Char {
            if (atEnd()) throw IllegalArgumentException("malformed")
            return s[i]
        }
        fun next(): Char {
            if (atEnd()) throw IllegalArgumentException("malformed")
            return s[i++]
        }
        fun skipWs() { while (!atEnd() && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++ }
        fun expect(c: Char) { if (next() != c) throw IllegalArgumentException("malformed") }

        fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = next()
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        val e = next()
                        when (e) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 4 > s.length) throw IllegalArgumentException("malformed")
                                val hex = s.substring(i, i + 4)
                                i += 4
                                val code = hex.toIntOrNull(16) ?: throw IllegalArgumentException("malformed")
                                sb.append(code.toChar())
                            }
                            else -> throw IllegalArgumentException("malformed")
                        }
                    }
                    c < ' ' -> throw IllegalArgumentException("malformed")
                    else -> sb.append(c)
                }
            }
        }

        fun readNumber(): Double {
            val start = i
            if (peek() == '-') next()
            while (!atEnd()) {
                val c = s[i]
                if (c.isDigit() || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') i++ else break
            }
            val token = s.substring(start, i)
            if (token.isEmpty() || token == "-") throw IllegalArgumentException("malformed")
            // رفض صيغ كسرية/أسي مشوّهة (1.2.3، 1e، --1، 1+2)
            if (!Regex("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?").matches(token))
                throw IllegalArgumentException("malformed")
            return token.toDoubleOrNull() ?: throw IllegalArgumentException("malformed")
        }

        fun readBoolean(): Boolean? = when {
            s.startsWith("true", i) -> { i += 4; true }
            s.startsWith("false", i) -> { i += 5; false }
            else -> null
        }
    }
}
