package com.superbiz.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * [P36-M4-5] اختبارات نقية لرياضيات تاريخ منتقي M3 الموحّد (JUnit4 بلا Android/Robolectric):
 * • toPickerMillis: طابع محلي بأي وقت → منتصف نهار UTC لنفس التاريخ (صيغة منتقي M3) —
 *   يُعرض اليوم المحلي الصحيح داخل المنتقي حتى في المناطق عالية الإزاحة.
 * • fromPickerMillis: منتصف نهار UTC → ظهر اليوم نفسه بالتوقيت المحلي (عقد dueFromFields
 *   القديم المقاوم لحواف التوقيت الصيفي).
 * • الدوران الكامل محلي → منتقي → محلي يحفظ التاريخ التقويمي ويوحّد الوقت على الظهر.
 * كلها حتمية بغضّ النظر عن منطقة الجهاز الزمنية (القراءة دائماً بحقول التقويم لا بالطرح الخام).
 */
class M4PickerDatesTest {

    private fun utcCal(y: Int, m: Int, d: Int, h: Int = 0): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply {
            clear()
            set(y, m, d, h, 0, 0)
        }

    private fun localCal(y: Int, m: Int, d: Int, h: Int, min: Int): Calendar =
        Calendar.getInstance().apply {
            clear()
            set(y, m, d, h, min, 0)
        }

    // ── 1) التطبيع إلى صيغة المنتقي: منتصف نهار UTC لنفس التاريخ المحلي ──
    @Test
    fun toPickerMillisNormalizesToUtcMidnightOfSameLocalDate() {
        val picked = PickerDates.toPickerMillis(localCal(2025, Calendar.MARCH, 10, 15, 37).timeInMillis)
        assertEquals(utcCal(2025, Calendar.MARCH, 10).timeInMillis, picked)
    }

    // ── 2) من المنتقي إلى المحلي: ظهر اليوم نفسه ──
    @Test
    fun fromPickerMillisGivesLocalNoonOfSameDate() {
        val back = PickerDates.fromPickerMillis(utcCal(2025, Calendar.MARCH, 10).timeInMillis)
        val c = Calendar.getInstance().apply { timeInMillis = back }
        assertEquals(2025, c.get(Calendar.YEAR))
        assertEquals(Calendar.MARCH, c.get(Calendar.MONTH))
        assertEquals(10, c.get(Calendar.DAY_OF_MONTH))
        assertEquals(12, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, c.get(Calendar.MINUTE))
    }

    // ── 3) الدوران الكامل يحفظ التاريخ التقويمي (طابع قرب منتصف الليل 23:10) ──
    @Test
    fun roundTripKeepsCalendarDate() {
        val back = PickerDates.fromPickerMillis(
            PickerDates.toPickerMillis(localCal(2025, Calendar.DECEMBER, 31, 23, 10).timeInMillis)
        )
        val c = Calendar.getInstance().apply { timeInMillis = back }
        assertEquals(2025, c.get(Calendar.YEAR))
        assertEquals(Calendar.DECEMBER, c.get(Calendar.MONTH))
        assertEquals(31, c.get(Calendar.DAY_OF_MONTH))
        assertEquals(12, c.get(Calendar.HOUR_OF_DAY))
    }

    // ── 4) يوم بأول/آخر السنة وحدود شهور 30/31 يمرّ بلا انزياح ──
    @Test
    fun monthBoundariesRoundTripExactly() {
        listOf(
            Triple(2025, Calendar.JANUARY, 1),
            Triple(2025, Calendar.APRIL, 30),
            Triple(2024, Calendar.FEBRUARY, 29) // سنة كبيسة
        ).forEach { (y, m, d) ->
            val back = PickerDates.fromPickerMillis(
                PickerDates.toPickerMillis(localCal(y, m, d, 8, 5).timeInMillis)
            )
            val c = Calendar.getInstance().apply { timeInMillis = back }
            assertEquals(y, c.get(Calendar.YEAR))
            assertEquals(m, c.get(Calendar.MONTH))
            assertEquals(d, c.get(Calendar.DAY_OF_MONTH))
        }
    }
}
