package com.superbiz.app.ui.screens.statement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** [P17-c] اختبارات جافية صرفة لمنطق واجهة كشف الحساب — بلا Robolectric ولا Gradle عند هذه الموجة */
class StatementUiLogicTest {

    private fun cal(y: Int, m: Int, d: Int, h: Int = 12): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply {
            clear()
            set(y, m, d, h, 0, 0)
        }

    // ── 1) حسم النوافذ الزمنية ──
    @Test
    fun todaySpansWholeDay() {
        val now = cal(2025, Calendar.MARCH, 10, 15)
        val (from, to) = resolvePeriod(UiPeriodPreset.TODAY, now)
        val s = cal(2025, Calendar.MARCH, 10, 0).timeInMillis
        assertEquals(s, from)
        assertEquals(s + 86_400_000L - 1, to)
    }

    @Test
    fun thisWeekStartsMondayRegardlessOfLocale() {
        // 2025-03-16 هو أحد ⇒ بداية الأسبوع اثنان 2025-03-10
        val now = cal(2025, Calendar.MARCH, 16, 9)
        val (from, _) = resolvePeriod(UiPeriodPreset.THIS_WEEK, now)
        assertEquals(cal(2025, Calendar.MARCH, 10, 0).timeInMillis, from)
    }

    @Test
    fun lastMonthCoversFullPreviousMonth() {
        val now = cal(2025, Calendar.MARCH, 5, 10)
        val (from, to) = resolvePeriod(UiPeriodPreset.LAST_MONTH, now)
        assertEquals(cal(2025, Calendar.FEBRUARY, 1, 0).timeInMillis, from)
        assertEquals(cal(2025, Calendar.FEBRUARY, 28, 0).timeInMillis + 86_400_000L - 1, to)
    }

    @Test
    fun customSwapsReversedWindow() {
        val now = cal(2025, Calendar.MARCH, 5, 10)
        val (from, to) = resolvePeriod(UiPeriodPreset.CUSTOM, now, customFrom = 200L, customTo = 100L)
        assertEquals(100L, from)
        assertEquals(200L, to)
    }

    @Test
    fun customMissingWindowFallsBackToThisMonth() {
        val now = cal(2025, Calendar.MARCH, 5, 10)
        val (from, _) = resolvePeriod(UiPeriodPreset.CUSTOM, now, customFrom = 0L, customTo = 5L)
        assertEquals(cal(2025, Calendar.MARCH, 1, 0).timeInMillis, from)
    }

    @Test
    fun presetByNameIsTolerant() {
        assertEquals(UiPeriodPreset.TODAY, UiPeriodPreset.ofName("TODAY"))
        assertEquals(UiPeriodPreset.THIS_MONTH, UiPeriodPreset.ofName("WEIRD"))
        assertEquals(UiPeriodPreset.THIS_MONTH, UiPeriodPreset.ofName(null))
    }

    // ── 2) فرض قوالب الاستوديو ──
    private fun t(id: String, cat: String, builtIn: Boolean = true, fav: Boolean = false) =
        TemplateUi(id, id, id, "", "", cat, builtIn, fav, false)

    @Test
    fun filterByQueryCategoryAndMasks() {
        val list = listOf(
            t("classic_a4", "classic"),
            t("my_custom", "classic", builtIn = false, fav = true),
            t("bold_modern", "bold", fav = true)
        )
        assertEquals(2, filterTemplates(list, "", "classic", favoritesOnly = false, customOnly = false).size)
        assertEquals(1, filterTemplates(list, "bold", null, false, false).size)
        assertEquals(2, filterTemplates(list, "", null, favoritesOnly = true, customOnly = false).size)
        assertEquals(1, filterTemplates(list, "", null, false, customOnly = true).size)
        assertEquals(list[1].id, filterTemplates(list, "", null, true, true).single().id)
        assertTrue(filterTemplates(list, "MY_", null, false, false).any { it.id == "my_custom" })
    }

    // ── 3) عناصر show ──
    @Test
    fun toggleElementAddsAndRemoves() {
        val s = setOf("HEADER", "QR")
        assertTrue(toggleElement(s, "LOGO").contains("LOGO"))
        assertFalse(toggleElement(s, "QR").contains("QR"))
        assertTrue(toggleElement(setOf(), "X").contains("X"))
    }

    // ── 4) اسم ملف ──
    @Test
    fun sanitizeKeepsArabicAndSlashesOut() {
        assertEquals("كشف_حساب_12", sanitizeFileName("كشف/حساب:12"))
        assertEquals("statement", sanitizeFileName("///"))
        assertTrue(sanitizeFileName("x".repeat(200)).length <= 60)
    }

    // ── 5) ترميز الحقول الإضافية ──
    @Test
    fun extrasRoundTripWithSpecialChars() {
        val map = mapOf("city" to "الرياض", "note" to "a=b&c", "site" to "")
        val enc = encodeExtras(map)
        assertEquals(map, decodeExtras(enc))
        assertTrue(decodeExtras("broken").isEmpty())
    }

    // ── 6) ألوان HEX ──
    @Test
    fun hexParsingValidAndInvalid() {
        assertEquals(0xFF112233.toInt(), parseHexColor("#112233"))
        assertEquals(0xFF112233.toInt(), parseHexColor("123"))
        assertEquals(0x80112233.toInt(), parseHexColor("#80112233"))
        assertNull(parseHexColor("#12345"))
        assertNull(parseHexColor("zzzzzz"))
    }

    // ── 7) ترقيم صفحات المعاينة ──
    @Test
    fun pageLabelClampsAndFormats() {
        assertEquals("1 / 5", pageLabel(1, 5))
        assertEquals("5 / 5", pageLabel(9, 5))
        assertEquals("1 / 1", pageLabel(0, 0))
    }

    // ── 8) الأحدث يفوز ──
    @Test
    fun latestWinsDiscardsStaleTokens() {
        val lw = LatestWins()
        val a = lw.next()
        val b = lw.next()
        assertFalse(lw.isLatest(a)) // أُطلق قبل b فلا يُطبَّق
        assertTrue(lw.isLatest(b))
        val c = lw.next()
        assertFalse(lw.isLatest(b))
        assertTrue(lw.isLatest(c))
    }

    // ── 9) بطاقة القالب: تسميات اللغتين والفئات — [P17-c-fin] ──
    @Test
    fun templateUiLabelsFollowLanguage() {
        val t = TemplateUi(
            id = "COR-01", nameAr = "أزرق", nameEn = "Blue",
            descAr = "وصف عربي", descEn = "English desc",
            category = "CORPORATE", isBuiltIn = true, isFavorite = false, isDefault = true
        )
        assertEquals("أزرق", t.label(ar = true))
        assertEquals("Blue", t.label(ar = false))
        assertEquals("وصف عربي", t.desc(ar = true))
        assertEquals("English desc", t.desc(ar = false))
        assertTrue(t.isDefault && t.isBuiltIn && !t.isFavorite)
    }

    // ── 10) نافذة مخصصة متطابقة ومنطق show المجموعي — [P17-c-fin] ──
    @Test
    fun customEqualBoundsAndToggleSemantics() {
        val now = cal(2025, Calendar.JUNE, 15, 8)
        val ts = 1_000_000L
        // من=إلى في CUSTOM ⇒ نافذة منحلة لكن صالحة (لا انقلاب ولا سقوط لهذا الشهر)
        assertEquals(ts to ts, resolvePeriod(UiPeriodPreset.CUSTOM, now, ts, ts))
        // toggleElement: إضافة ثم إزالة تعيد المجموعة الأصلية بالترتيب نفسه دلالياً
        val base = setOf("LOGO", "NOTES")
        assertEquals(base, toggleElement(toggleElement(base, "STAMP"), "STAMP"))
        assertTrue(isPreviewPossible(100L, 200L))
        assertFalse(isPreviewPossible(200L, 100L))
    }
}
