package com.superbiz.app

import com.superbiz.app.domain.backup.MiniJsonException
import com.superbiz.app.domain.backup.escapeJson
import com.superbiz.app.domain.backup.parseJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * [P13-b] اختبارات MiniJson النقية (JUnit4 بلا Android):
 * التهريب (اقتباس/مائل/أسطر/محارف تحكم/يونيكود يبقى كما هو)، والتحليل (كائنات/مصفوفات/
 * أطوال 64-bit بدقة/كسور/أسي/تسامح الفراغ/رفض الحمولة التالفة بموضع صحيح)،
 * واختبار خاصية: 50 سلسلة عشوائية (اقتباسات/مائل/أسطر/عربي/إيموجي) تدور escape→parse
 * وتعود كما هي.
 */
class MiniJsonTest {

    // ─── escapeJson ───

    @Test
    fun escape_plainTextStaysAsIs() {
        assertEquals("أحمد", escapeJson("أحمد"))
        assertEquals("客户", escapeJson("客户"))
        assertEquals("", escapeJson(""))
        assertEquals("0501234567", escapeJson("0501234567"))
    }

    @Test
    fun escape_quoteAndBackslash() {
        assertEquals("\\\"مقتبس\\\"", escapeJson("\"مقتبس\""))
        assertEquals("\\\\", escapeJson("\\"))
        assertEquals("a\\\\b", escapeJson("a\\b"))
    }

    @Test
    fun escape_newlineCarriageTab() {
        assertEquals("س1\\nس2", escapeJson("س1\nس2"))
        assertEquals("س1\\rس2", escapeJson("س1\rس2"))
        assertEquals("أ\\tب", escapeJson("أ\tب"))
    }

    @Test
    fun escape_controlCharsBecomeUnicode() {
        assertEquals("\\u0001", escapeJson("\u0001"))
        assertEquals("\\u001f", escapeJson("\u001F"))
        assertEquals("\\u0000", escapeJson("\u0000"))
        assertEquals("أ\\u000bب", escapeJson("أ\u000Bب"))
    }

    @Test
    fun escape_unicodeAndEmojiUntouched() {
        assertEquals("مرحبا 😀", escapeJson("مرحبا 😀"))
        assertEquals("🙂ↀ", escapeJson("🙂ↀ"))
    }

    // ─── parseJson: القيم الأساسية ───

    @Test
    fun parse_scalarsWithCorrectTypes() {
        assertEquals(42L, parseJson("42"))
        assertEquals(-7L, parseJson("-7"))
        assertEquals(1.5, parseJson("1.5"))
        assertEquals(true, parseJson("true"))
        assertEquals(false, parseJson("false"))
        assertEquals(null, parseJson("null"))
        assertEquals("نص", parseJson("\"نص\""))
        // عدد صحيح يبقى Long بدقة 64-bit كاملة — 2^53+1 كان سيُفسد لو مرّ عبر Double
        assertEquals(9007199254740993L, parseJson("9007199254740993"))
    }

    @Test
    fun parse_fractionsAndExponents() {
        assertEquals(2.5e2, parseJson("2.5e2"))
        assertEquals(-0.25, parseJson("-0.25"))
        assertEquals(1000.0, parseJson("1E3"))
        assertEquals(1.0, parseJson("1.0"))
        // طفح Long يهبط Double محسوباً بدل فشل الملف
        assertEquals(1e20, parseJson("99999999999999999999"))
    }

    @Test
    fun parse_objectAndNestedArray() {
        val v = parseJson("""{"a":1,"b":"x","c":true,"d":null,"e":1.5,"f":[1,[2,{"g":3}]]}""")
        assertTrue(v is Map<*, *>)
        v as Map<*, *>
        assertEquals(1L, v["a"])
        assertEquals("x", v["b"])
        assertEquals(true, v["c"])
        assertEquals(null, v["d"])
        assertEquals(1.5, v["e"])
        val f = v["f"] as List<*>
        assertEquals(1L, f[0])
        val inner = f[1] as List<*>
        val obj = inner[1] as Map<*, *>
        assertEquals(3L, obj["g"])
    }

    @Test
    fun parse_whitespaceTolerantEverywhere() {
        val v = parseJson("  \n\t { \"a\" : [ 1 , 2 ] , \"b\":\"x\" } \r\n ")
        assertTrue(v is Map<*, *>)
        assertEquals(listOf(1L, 2L), (v as Map<*, *>)["a"])
        assertEquals("x", v["b"])
    }

    @Test
    fun parse_emptyObjectAndArray() {
        assertEquals(emptyMap<String, Any?>(), parseJson(" {} "))
        assertEquals(emptyList<Any?>(), parseJson("[]"))
    }

    @Test
    fun parse_duplicateKeyLastWins() {
        val v = parseJson("""{"k":1,"k":2}""") as Map<*, *>
        assertEquals(2L, v["k"])
    }

    @Test
    fun parse_escapeSequencesAndUnicode() {
        assertEquals("مرحبا", parseJson("\"\\u0645\\u0631\\u062d\\u0628\\u0627\""))
        assertEquals("س\nطر", parseJson("\"س\\nطر\""))
        assertEquals("A", parseJson("\"\\u0041\""))
        // زوج بديل إيموجي عبر تهريبين متتاليين
        assertEquals("😀", parseJson("\"\\uD83D\\uDE00\""))
        assertEquals("a/b\\c", parseJson("\"a\\/b\\\\c\""))
    }

    @Test
    fun parse_longOverflowFallsBackToDouble() {
        val v = parseJson("99999999999999999999")
        assertTrue("يجب أن يكون Double بعد طفح Long", v is Double)
        assertEquals(1e20, v)
    }

    // ─── parseJson: المدخلات المشوّهة (برسائل تحمل الموضع) ───

    private fun expectFail(json: String, positionMarker: String? = null) {
        try {
            parseJson(json)
            fail("كان يجب أن يرمي MiniJsonException للمدخل: $json")
        } catch (e: MiniJsonException) {
            if (positionMarker != null) {
                assertTrue(
                    "الرسالة يجب أن تحمل الموضع: ${e.message}",
                    e.message!!.contains(positionMarker)
                )
            }
        }
    }

    @Test
    fun parse_trailingGarbageRejected() {
        expectFail("{\"a\":1} x", "position 8")
        expectFail("1 2")
        expectFail("{}{}")
    }

    @Test
    fun parse_unterminatedStringReportsStart() {
        // السلسلة تبدأ عند الموضع 1 في "{\"a"
        expectFail("{\"a", "position 1")
        expectFail("\"بدون إغلاق")
    }

    @Test
    fun parse_invalidLiteralAndStructure() {
        expectFail("{\"a\":tru}")
        expectFail("tru")
        expectFail("{a:1}")
        expectFail("{\"a\":1,}")
        expectFail("[1,")
        expectFail("")
        expectFail("   ")
    }

    @Test
    fun parse_rawControlCharInStringRejected() {
        expectFail("{\"a\":\"\u0001\"}")
        expectFail("{\n\"a\":\"س1\nس2\"}")
    }

    @Test
    fun parse_badNumbersRejected() {
        expectFail("1.")
        expectFail("-")
        expectFail("1e")
        expectFail("1e+")
        expectFail(".5")
        expectFail("--1")
    }

    @Test
    fun parse_badEscapeRejected() {
        expectFail("\"\\x\"")
        expectFail("\"\\u12\"")
    }

    // ─── خاصية الدوران الكامل: escape → parse يعيد الأصل ───

    @Test
    fun roundtrip_escapeThenParseRestoresOriginal_50RandomStrings() {
        val pool = charArrayOf(
            'a', 'ز', '9', '،', ' ', '"', '\\', '\n', '\r', '\t', '\u0001', '\u001F', '\uD83D', '\uDE00'
        )
        val rnd = Random(42) // بذرة ثابتة — الاختبار حتمي وقابل للتكرار
        repeat(50) {
            val len = rnd.nextInt(41) // 0..40 حرفاً
            val sb = StringBuilder(len)
            repeat(len) { sb.append(pool[rnd.nextInt(pool.size)]) }
            val original = sb.toString()
            val parsed = parseJson("\"" + escapeJson(original) + "\"")
            assertTrue("يجب أن تكون النتيجة سلسلة", parsed is String)
            assertEquals("الدوران فشل عند التكرار $it", original, parsed)
        }
    }
}
