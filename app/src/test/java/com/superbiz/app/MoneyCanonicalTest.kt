package com.superbiz.app

import com.superbiz.app.util.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * (M-2.2/M-2.3/M-7.6): اختبارات المساعد النقدي القانوني —
 * تقريب BigDecimal HALF_UP موحّد، وتطبيع الأرقام العربية-الهندية في parse،
 * وثبات التنسيق عبر اللغات (أرقام لاتينية دائماً قابلة لإعادة القراءة).
*/
class MoneyCanonicalTest {

    // ─── round2: نصف بعيداً عن الصفر (HALF_UP) ───

    @Test
    fun round2_halfUpOnExactTies() {
        // 0.125 و0.135 قيم تمثيلها الثنائي مضبوط — نصف القرش يذهب بعيداً عن الصفر
        assertEquals(0.13, Money.round2(0.125), 1e-9)
        assertEquals(0.14, Money.round2(0.135), 1e-9)
        assertEquals(-0.13, Money.round2(-0.125), 1e-9)
    }

    @Test
    fun round2_ordinaryValues() {
        assertEquals(2.67, Money.round2(2.6749999999), 1e-9)
        assertEquals(12.0, Money.round2(11.995 + 0.0049), 1e-9)
        assertEquals(0.0, Money.round2(0.0), 1e-9)
    }

    @Test
    fun round2_NaNAndInfinity_safe() {
        assertEquals(0.0, Money.round2(Double.NaN), 1e-9)
        assertEquals(0.0, Money.round2(Double.POSITIVE_INFINITY), 1e-9)
    }

    // ─── التطبيع: أرقام عربية-هندية ومفصولات عربية ───

    @Test
    fun normalizeDigits_arabicIndicToLatin() {
        assertEquals("123.45", Money.normalizeDigits("١٢٣٫٤٥"))
        assertEquals("12", Money.normalizeDigits("١٢"))
        assertEquals("1,234.5", Money.normalizeDigits("1٬234٫5"))
        assertEquals("15%", Money.normalizeDigits("١٥٪"))
    }

    @Test
    fun parse_arabicIndicDigits() {
        assertEquals(1234.56, Money.parse("١٢٣٤٫٥٦")!!, 1e-9)
        assertEquals(1234567.89, Money.parse("١٬٢٣٤٬٥٦٧٫٨٩")!!, 1e-9)
    }

    @Test
    fun parse_latinWithGrouping() {
        assertEquals(1234.56, Money.parse("1,234.56")!!, 1e-9)
        assertEquals(-50.0, Money.parse("-50")!!, 1e-9)
    }

    @Test
    fun parse_canReadItsOwnFormatOutput() {
        // (M-2.3): round-trip كامل — كان parse يعجز عن قراءة مخرجات format
        // تحت اللغة العربية (أرقام هندية + فاصل تجميع عربي)
        val a = 1234567.89
        assertEquals(a, Money.parse(Money.num(a))!!, 1e-6)
        assertEquals(a, Money.parse(Money.format(a, "", showDecimals = true))!!, 1e-6)
    }

    @Test
    fun parse_garbageReturnsNull() {
        assertNull(Money.parse("abc"))
        assertNull(Money.parse(""))
    }

    @Test
    fun parseLenient_defaultsToZero() {
        assertEquals(0.0, Money.parseLenient("abc"), 1e-9)
        assertEquals(7.5, Money.parseLenient("٧٫٥"), 1e-9)
    }

    // ─── ثبات التنسيق عبر اللغات ───

    @Test
    fun format_usesLatinDigitsAlways() {
        // منسّقات صريحة اللغة: النتيجة نفسها تحت أي لغة افتراضية للجهاز
        assertEquals("1,234.56", Money.num(1234.56))
        assertEquals("-50", Money.num(-50.0))
        assertEquals("2,670 ر.س", Money.format(2670.0, "ر.س"))
    }
}
