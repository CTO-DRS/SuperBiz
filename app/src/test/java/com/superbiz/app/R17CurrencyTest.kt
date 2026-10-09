package com.superbiz.app

import com.superbiz.app.domain.algo.FxStampMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [H4-1 V 2.5.0] اختبارات FxStampMath — موجة R17 «العملات المتعددة»
 * ═══════════════════════════════════════════════════════════════════════════
 * كل توقع محسوب يدوياً وحتمي على JVM خالص (نمط R16SmartTest) — القيم
 * مُتحقَّق منها بمقسوم عشري دقيق (BigDecimal) خارج الكود:
 * 1e8/0.2665 = 375234521.576… → 375_234_522، و10000¢×375000000/1e8 = 37500 بالضبط،
 * و1250 fils×312500000/1e9 = 390.625 → 391 (HALF_UP)، وهكذا تحت كل اختبار.
 * بوابة H4-1: صفر Double في أي مسار مالي — الدوال نفسها لا تلمس Double إلا
 * عند قراءة rateToBase الكتالوجي عبر BigDecimal(toString) (نمط P20-FIX).
 */
class R17CurrencyTest {

    // ═══════════ (1) rateMicrosFromCatalog — الاشتقاق الحتمي من الكتالوج ═══════════

    @Test
    fun c1_catalogDerivation_handComputed() {
        // اليدوي: 1e8/0.2665 = 375234521.5763… → HALF_UP 375_234_522
        assertEquals(375_234_522L, FxStampMath.rateMicrosFromCatalog(0.2665))
        // 1e8/0.2453 = 407664084.79… → 407_664_085
        assertEquals(407_664_085L, FxStampMath.rateMicrosFromCatalog(0.2453))
        // 1e8/0.9785 = 102197240.67… → 102_197_241
        assertEquals(102_197_241L, FxStampMath.rateMicrosFromCatalog(0.9785))
        // 1e8/12.95 = 7722007.722… → 7_722_008
        assertEquals(7_722_008L, FxStampMath.rateMicrosFromCatalog(12.95))
        // 1e8/664.5 = 150489.089… → 150_489
        assertEquals(150_489L, FxStampMath.rateMicrosFromCatalog(664.5))
        // الهوية: الأساس نفسه = 100_000_000 (1.0 × 1e8)
        assertEquals(FxStampMath.BASE_RATE_MICROS, FxStampMath.rateMicrosFromCatalog(1.0))
    }

    @Test
    fun c1_catalogDerivation_rejectsInvalid() {
        assertNull(FxStampMath.rateMicrosFromCatalog(0.0))
        assertNull(FxStampMath.rateMicrosFromCatalog(-3.75))
        assertNull(FxStampMath.rateMicrosFromCatalog(Double.NaN))
        assertNull(FxStampMath.rateMicrosFromCatalog(Double.POSITIVE_INFINITY))
    }

    @Test
    fun c1_derivation_isDeterministic() {
        // نفس المدخل → نفس المخرج بايتياً (عقد الختم التاريخي)
        val a = FxStampMath.rateMicrosFromCatalog(0.2665)
        val b = FxStampMath.rateMicrosFromCatalog(0.2665)
        assertEquals(a, b)
    }

    // ═══════════ (2) toBasePiasters — التحويل الأمامي عند الإدخال ═══════════

    @Test
    fun c2_usdExactRate_noRounding() {
        // اليدوي: 10000¢ × 375000000 / (1e6×10²) = 3.75e12/1e8 = 37500 قرشاً بالضبط ($100 @ 3.75 = 375 ر.س)
        assertEquals(37_500L, FxStampMath.toBasePiasters(10_000L, 2, 375_000_000L))
        // $1 → 375 قرشاً
        assertEquals(375L, FxStampMath.toBasePiasters(100L, 2, 375_000_000L))
    }

    @Test
    fun c2_usdDerivedRate_halfUp() {
        // اليدوي: 10000 × 375234522 / 1e8 = 37523.4522 → 37523 (HALF_UP على 0.4522)
        assertEquals(37_523L, FxStampMath.toBasePiasters(10_000L, 2, 375_234_522L))
    }

    @Test
    fun c2_kwdThreeMinorUnits() {
        // اليدوي: 1250 fils (1.25 د.ك) × 312500000 / (1e6×10³) = 390.625 → 391 قرشاً (HALF_UP)
        assertEquals(391L, FxStampMath.toBasePiasters(1_250L, 3, 312_500_000L))
    }

    @Test
    fun c2_jpyZeroMinorUnits() {
        // اليدوي: 1000 ين × 2500000 / (1e6×10⁰) = 2.5e9/1e6 = 2500 قرشاً (25 ر.س @ 0.025)
        assertEquals(2_500L, FxStampMath.toBasePiasters(1_000L, 0, 2_500_000L))
    }

    @Test
    fun c2_yerTinyRate() {
        // اليدوي: 100000 (1000 ر.ي) × 150489 / 1e8 = 150.489 → 150 قرشاً (1.5 ر.س)
        assertEquals(150L, FxStampMath.toBasePiasters(100_000L, 2, 150_489L))
    }

    @Test
    fun c2_identityRate_sarToSar() {
        // الأساس نفسه: أي مبلغ يمر بلا تغيير حرفياً
        assertEquals(99_999L, FxStampMath.toBasePiasters(99_999L, 2, FxStampMath.BASE_RATE_MICROS))
    }

    @Test
    fun c2_rejectsInvalidInputs_failClosed() {
        assertNull("سالب يُرفض لا يُقلب", FxStampMath.toBasePiasters(-1L, 2, 375_000_000L))
        assertNull("سعر صفر يُرفض", FxStampMath.toBasePiasters(100L, 2, 0L))
        assertNull("سعر سالب يُرفض", FxStampMath.toBasePiasters(100L, 2, -375_000_000L))
        assertNull("وحدات فرعية سالبة", FxStampMath.toBasePiasters(100L, -1, 375_000_000L))
        assertNull("وحدات فرعية فوق السقف", FxStampMath.toBasePiasters(100L, 5, 375_000_000L))
        assertNull("سعر فوق السقف العبثي", FxStampMath.toBasePiasters(100L, 2, FxStampMath.MAX_RATE_MICROS + 1))
        assertNull("مبلغ فوق السقف", FxStampMath.toBasePiasters(FxStampMath.MAX_FOREIGN_MINOR + 1, 2, 375_000_000L))
    }

    @Test
    fun c2_overflow_failClosedNotClamped() {
        // اليدوي: 1e15 × 1e15 / 1e6 = 1e24 ⟩ Long.MAX (9.22e18) — يُرفض null لا يُقص
        assertNull(FxStampMath.toBasePiasters(1_000_000_000_000_000L, 0, 1_000_000_000_000_000L))
    }

    // ═══════════ (3) fromBasePiasters — التحويل العكسي للعرض فقط ═══════════

    @Test
    fun c3_reverse_exactCase() {
        // اليدوي: 37500 × 1e8 / 375000000 = 10000¢ بالضبط
        assertEquals(10_000L, FxStampMath.fromBasePiasters(37_500L, 2, 375_000_000L))
    }

    @Test
    fun c3_reverse_roundedCase() {
        // اليدوي: 37523 × 1e8 / 375234522 = 9999.88… → 10000¢ (HALF_UP)
        assertEquals(10_000L, FxStampMath.fromBasePiasters(37_523L, 2, 375_234_522L))
    }

    @Test
    fun c3_reverse_kwdRoundTripDriftDocumented() {
        // 1250 fils → 391 قرشاً → عكسياً 1251 fils: انزياح ±1 وحدة فرعية متوقع وموثق —
        // القيمة المخزنة دائماً قروش الأساس (391) والعكس للعرض فقط فلا يتراكم الخطأ
        assertEquals(1_251L, FxStampMath.fromBasePiasters(391L, 3, 312_500_000L))
    }

    @Test
    fun c3_reverse_rejectsInvalid() {
        assertNull(FxStampMath.fromBasePiasters(-5L, 2, 375_000_000L))
        assertNull(FxStampMath.fromBasePiasters(100L, 2, 0L))
        assertNull(FxStampMath.fromBasePiasters(100L, 7, 375_000_000L))
    }

    // ═══════════ (4) parseRateToMicros / formatRate — نص السعر بلا Double ═══════════

    @Test
    fun c4_parse_handComputed() {
        assertEquals(375_000_000L, FxStampMath.parseRateToMicros("3.75"))
        assertEquals(375_150_000L, FxStampMath.parseRateToMicros("3.7515"))
        // اليدوي: 0.00150489 × 1e8 = 150_489 (8 منازل — حد الدقة)
        assertEquals(150_489L, FxStampMath.parseRateToMicros("0.00150489"))
        assertEquals(100_000_000L, FxStampMath.parseRateToMicros("1"))
        assertEquals(50_000_000L, FxStampMath.parseRateToMicros("0.5"))
        assertEquals(50_000_000L, FxStampMath.parseRateToMicros(".5"))
        assertEquals(375_000_000L, FxStampMath.parseRateToMicros("3.750"))
    }

    @Test
    fun c4_parse_arabicDigitsAndDecimalSeparator() {
        // ٣.٧٥ (أرقام عربية-هندية) و٣٫٧٥ (فاصلة عشرية عربية) — التطبيع نفسه
        assertEquals(375_000_000L, FxStampMath.parseRateToMicros("٣.٧٥"))
        assertEquals(375_000_000L, FxStampMath.parseRateToMicros("٣٫٧٥"))
        assertEquals(150_489L, FxStampMath.parseRateToMicros("۰.۰۰۱۵۰۴۸۹")) // أرقام فارسية-أردية
    }

    @Test
    fun c4_parse_rejectsGarbage() {
        assertNull(FxStampMath.parseRateToMicros(""))
        assertNull(FxStampMath.parseRateToMicros("abc"))
        assertNull(FxStampMath.parseRateToMicros("-3.75"))      // سعر سالب
        assertNull(FxStampMath.parseRateToMicros("0"))          // صفر
        assertNull(FxStampMath.parseRateToMicros("0.00000000")) // صفر مقنّع
        assertNull(FxStampMath.parseRateToMicros("3.123456789"))// 9 منازل > حد الدقة
        assertNull(FxStampMath.parseRateToMicros("3.7.5"))      // نقطتان
        assertNull(FxStampMath.parseRateToMicros("3,75"))       // فاصلة لاتينية تُرفض عمداً (خطر 375)
        assertNull(FxStampMath.parseRateToMicros("1e5"))        // صيغة علمية
        assertNull(FxStampMath.parseRateToMicros("1234567890")) // 10 خانات صحيحة > السقف النصي
        assertNull(FxStampMath.parseRateToMicros("  "))         // فراغ فقط
    }

    @Test
    fun c4_format_noDouble() {
        assertEquals("3.75", FxStampMath.formatRate(375_000_000L))
        assertEquals("3.7515", FxStampMath.formatRate(375_150_000L))
        assertEquals("0.00150489", FxStampMath.formatRate(150_489L))
        assertEquals("1", FxStampMath.formatRate(100_000_000L))
        assertEquals("0.5", FxStampMath.formatRate(50_000_000L))
        assertEquals("3.75234522", FxStampMath.formatRate(375_234_522L))
        assertEquals("", FxStampMath.formatRate(-1L)) // غير معرّف → نص فارغ لا "-0.00000001"
    }

    @Test
    fun c4_parseFormat_roundTrip() {
        // نص → micros → نص = النص نفسه (للقيم داخل حد الدقة)
        for (t in listOf("3.75", "0.00150489", "1", "0.5", "12.95")) {
            assertEquals(t, FxStampMath.formatRate(FxStampMath.parseRateToMicros(t)!!))
        }
    }

    // ═══════════ (5) سيناريو مختلط كامل — بوابة H4-1 على مستوى الوحدة ═══════════

    @Test
    fun c5_mixedCurrency_reportUnifiesInBase() {
        // ثلاثة صفوف: 100 ر.س + $100 @ 3.75 + €50 @ 4.07664085 (مشتق 1/0.2453)
        val eur = FxStampMath.rateMicrosFromCatalog(0.2453)!!
        val sar = 10_000L
        val usd = FxStampMath.toBasePiasters(10_000L, 2, 375_000_000L)!!
        val eurBase = FxStampMath.toBasePiasters(5_000L, 2, eur)!!
        // اليدوي: €50 × 407664085 / 1e8 = 20383.20425 → 20383 قرشاً
        assertEquals(20_383L, eurBase)
        // التقرير الموحد = مجموع قروش الأساس — جمع صحيح تام بلا فواصل عائمة
        val unified = sar + usd + eurBase
        assertEquals(10_000L + 37_500L + 20_383L, unified)
        // وسعر الاشتقاق حتمي: الكتالوج نفسه يعيد نفس الـmicros دائماً
        assertTrue(usd > 0L && eurBase > 0L)
    }
}
