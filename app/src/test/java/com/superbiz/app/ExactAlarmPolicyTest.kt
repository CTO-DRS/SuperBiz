package com.superbiz.app

import com.superbiz.app.domain.algo.ExactAlarmPolicy
import com.superbiz.app.work.ExactAlarms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P11-b] اختبارات منطق التنبيهات الدقيقة — JVM نقي بلا أندرويد:
 * قرار الجدولة (canSchedule)، فصل الدفعات المستقبلية (schedulable)،
 * ودفتر اليومية المبسّط (encode/decodeJournal)، وترميز صفوف الدفتر
 * الكامل (ExactAlarms.JournalCodec) مع عربي وشرَطات وأسطر جديدة.
 */
class ExactAlarmPolicyTest {

    // ═══ canSchedule ═══

    @Test
    fun canSchedule_beforeApi31_alwaysTrue() {
        assertEquals(true, ExactAlarmPolicy.canSchedule(24, false))
        assertEquals(true, ExactAlarmPolicy.canSchedule(30, true))
        assertEquals(true, ExactAlarmPolicy.canSchedule(30, false))
    }

    @Test
    fun canSchedule_api31Plus_followsSystemGrant() {
        assertEquals(false, ExactAlarmPolicy.canSchedule(31, false))
        assertEquals(true, ExactAlarmPolicy.canSchedule(31, true))
        assertEquals(true, ExactAlarmPolicy.canSchedule(35, true))
    }

    // ═══ schedulable ═══

    @Test
    fun schedulable_keepsOnlyFutureSortedAscending() {
        val now = 1_000_000L
        val out = ExactAlarmPolicy.schedulable(listOf(3_000_000L, 500_000L, 2_000_000L, now, 900_000L), now)
        // الماضي والحاضر يُستبعدان تماماً — المستقبلية تصاعدياً بلا تكرار حذف مطلوب
        assertEquals(listOf(2_000_000L, 3_000_000L), out)
    }

    @Test
    fun schedulable_emptyAndAllPast() {
        val now = 500L
        assertEquals(emptyList<Long>(), ExactAlarmPolicy.schedulable(emptyList(), now))
        assertEquals(emptyList<Long>(), ExactAlarmPolicy.schedulable(listOf(1L, 499L, 500L), now))
    }

    // ═══ دفتر اليومية المبسّط «partyId:dueDate» ═══

    @Test
    fun journal_roundTripsPairs() {
        val entries = listOf(5L to 1_700_000_000_000L, 12L to 1_800_000_000_999L)
        val encoded = ExactAlarmPolicy.encodeJournal(entries)
        assertEquals("5:1700000000000;12:1800000000999", encoded)
        assertEquals(entries, ExactAlarmPolicy.decodeJournal(encoded))
    }

    @Test
    fun journal_decodeSkipsMalformedRows() {
        val out = ExactAlarmPolicy.decodeJournal("abc:def;;7:1000;x9:2000;9:2000")
        assertEquals(listOf(7L to 1000L, 9L to 2000L), out)
        assertEquals(emptyList<Pair<Long, Long>>(), ExactAlarmPolicy.decodeJournal(""))
        assertEquals(emptyList<Pair<Long, Long>>(), ExactAlarmPolicy.decodeJournal("نص عربي بلا مفاتيح"))
    }

    // ═══ ترميز صفوف الدفتر الكامل (ExactAlarms.JournalCodec) ═══

    @Test
    fun journalCodec_roundTripsArabicTitleWithSeparatorsAndNewlineBody() {
        // العنوان يحوي «|» و«;» و«,» — والمتن يحوي سطراً جديداً وعربية
        val rows = listOf(
            ExactAlarms.Row(
                partyId = 42L, seq = 3, dueAt = 1_777_777_777_777L, notifId = 10_123_003,
                title = "خطة سداد — عميل | خاص; نسخة, جديدة",
                body = "الدفعة 3 من 12\nمبلغ 500.00 ر.س\nسلامٌ | عليكم; قبل, الغد"
            ),
            ExactAlarms.Row(
                partyId = 7L, seq = 0, dueAt = 1_888_888_888_888L, notifId = 10_007_000,
                title = "‏+966 55 123 4567",
                body = "Percent 100% plus+sign ~tilde"
            )
        )
        val encoded = ExactAlarms.JournalCodec.encode(rows)
        // الترميز لا يحوي فواصل الدفتر الخام داخل الحقول النصية — لا «|» داخل سطر من الترميز إلا الحقول
        rows.forEach { r ->
            val line = ExactAlarms.JournalCodec.encodeRow(r)
            // الصف = 5 حقول مفصولة بـ|، والبادئة «partyId:dueAt»
            assertTrue(line.startsWith("${r.partyId}:${r.dueAt}|${r.seq}|${r.notifId}|"))
            assertEquals(5, line.split('|').size)
        }
        val decoded = ExactAlarms.JournalCodec.decode(encoded)
        assertEquals(rows, decoded)
    }

    @Test
    fun journalCodec_decodeToleratesMalformedLines() {
        val good = ExactAlarms.JournalCodec.encodeRow(
            ExactAlarms.Row(1L, 1, 2_000L, 3, "عنوان", "متن")
        )
        val garbage = listOf(
            "سطر مشوّه",                  // بلا حقول
            "1:2000|seq|nid|title",        // 4 حقول بدل 5
            "1:2000|x|3|%zz|",             // seq غير رقمي
            "1:2000|1|3|%zz|",             // عنوان بنسبة مئوية مشوهة
            "",                            // سطر فارغ
            "1:2000|1|3|%D8%B9|"           // سليم التركيب — يُقبل بعنوان «ع» ومتن فارغ
        )
        val decoded = ExactAlarms.JournalCodec.decode((listOf(good) + garbage).joinToString("\n"))
        assertEquals(
            listOf(
                ExactAlarms.Row(1L, 1, 2_000L, 3, "عنوان", "متن"),
                ExactAlarms.Row(1L, 1, 2_000L, 3, "ع", "")
            ),
            decoded
        )
    }

    @Test
    fun journalCodec_emptyRoundTrip() {
        assertEquals("", ExactAlarms.JournalCodec.encode(emptyList()))
        assertEquals(emptyList<ExactAlarms.Row>(), ExactAlarms.JournalCodec.decode(""))
    }

    // ═══ requestCode ═══

    @Test
    fun requestCode_isStableAndWithinIntBounds() {
        assertEquals(5000, ExactAlarms.requestCode(5L, 0))
        assertEquals(5999, ExactAlarms.requestCode(5L, 1234)) // seq يُقيَّد إلى 999
        assertEquals(99_999_999, ExactAlarms.requestCode(99_999L, 999))
        // sideId يلف بعد 100k — حدّ موثق لا يجب أن يتجاوز حدود int
        assertEquals(ExactAlarms.requestCode(100_000L, 0), ExactAlarms.requestCode(0L, 0))
        assertTrue(ExactAlarms.requestCode(987_654_321L, 500) in 0..99_999_999)
    }
}
