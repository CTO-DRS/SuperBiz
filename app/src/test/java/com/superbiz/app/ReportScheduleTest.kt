package com.superbiz.app

import com.superbiz.app.work.PdfRetention
import com.superbiz.app.work.ReportScheduleLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/**
 * — اختبارات منطق جدولة إرسال التقرير A4
 * حساب التأخير حتى الموعد + استحقاق الدورة + نطاق المدة.
 * [P6-M38 إصلاح]: إضافات دقة الدقائق لحساب initialDelay عند إعادة التثبيت على الساعة.
 * [P6-M41 إصلاح]: اختبارات سياسة احتفاظ ملفات PDF (PdfRetention — نقية فوق قوائم ملفات).
*/
class ReportScheduleTest {

    private val tz = TimeZone.getTimeZone("GMT")

    /** ثابت زمني: 2026-09-13 10:00:00 GMT */
    private val at10 = utc(2026, 8, 13, 10, 0)

    private fun utc(y: Int, m: Int, d: Int, h: Int, min: Int): Long {
        val c = Calendar.getInstance(TimeZone.getTimeZone("GMT"))
        c.clear()
        c.set(y, m, d, h, min, 0)
        return c.timeInMillis
    }

    @Test
    fun `delay to a later hour today is the difference`() {
        // الموعد 20:00 والآن 10:00 → 10 ساعات
        val delay = ReportScheduleLogic.nextDelayMillis(at10, 20, tz)
        assertEquals(10L * 3_600_000L, delay)
    }

    @Test
    fun `delay to a passed hour rolls to tomorrow`() {
        // الموعد 8:00 مضى (الآن 10:00) → غداً 8:00 = 22 ساعة
        val delay = ReportScheduleLogic.nextDelayMillis(at10, 8, tz)
        assertEquals(22L * 3_600_000L, delay)
    }

    @Test
    fun `delay to exact now rolls to tomorrow`() {
        val delay = ReportScheduleLogic.nextDelayMillis(at10, 10, tz)
        assertEquals(24L * 3_600_000L, delay)
    }

    @Test
    fun `hour is clamped into 0 to 23`() {
        val delay = ReportScheduleLogic.nextDelayMillis(at10, 99, tz)
        assertEquals(13L * 3_600_000L, delay) // يُقيَّد إلى 23:00 اليوم = بعد 13 ساعة
    }

    // [P6-M38 إصلاح]: تغطية حساب initialDelay بدقة الدقائق — إعادة الجدولة (REPLACE)
    // تحسب التأخير من «الآن» حتى أقرب موعد قادم للساعة المختارة لا من زمن الإنشاء
    @Test
    fun `delay accounts for minutes past the hour`() {
        val at1030 = utc(2026, 8, 13, 10, 30)
        // الساعة 11:00 قادمة اليوم بعد 30 دقيقة
        assertEquals(30L * 60_000L, ReportScheduleLogic.nextDelayMillis(at1030, 11, tz))
        // الساعة 10:00 مضت (10:30 الآن) → غداً 10:00 = 23.5 ساعة
        assertEquals((23L * 3_600_000L) + (30L * 60_000L), ReportScheduleLogic.nextDelayMillis(at1030, 10, tz))
    }

    @Test
    fun `never run is always due when enabled`() {
        assertTrue(ReportScheduleLogic.isDue(at10, 0L, 1))
        assertTrue(ReportScheduleLogic.isDue(at10, 0L, 7))
    }

    @Test
    fun `disabled schedule is never due`() {
        assertFalse(ReportScheduleLogic.isDue(at10, 0L, 0))
        assertFalse(ReportScheduleLogic.isDue(at10, at10 - 90_000_000L, 0))
    }

    @Test
    fun `within period is not due`() {
        val last = at10 - 2L * 86_400_000L
        assertFalse(ReportScheduleLogic.isDue(at10, last, 7))
    }

    @Test
    fun `full period elapsed is due`() {
        val last = at10 - 7L * 86_400_000L
        assertTrue(ReportScheduleLogic.isDue(at10, last, 7))
        // داخل الفاصل السماحي (5 دقائق) يبقى مستحقاً — يمنع الازدواج لا الاستحقاق
        val almost = at10 - 7L * 86_400_000L + 4L * 60_000L
        assertTrue(ReportScheduleLogic.isDue(at10, almost, 7))
    }

    @Test
    fun `period days clamps to at least one day`() {
        assertEquals(1L, ReportScheduleLogic.periodDays(0))
        assertEquals(1L, ReportScheduleLogic.periodDays(-5))
        assertEquals(7L, ReportScheduleLogic.periodDays(7))
    }

    // ═══════ [P6-M41 إصلاح]: سياسة احتفاظ ملفات PDF (PdfRetention) ═══════

    /** ملف pdf مؤقت بآخر تعديل محسوب نسبةً إلى at10 — القرار كله بدالة نقية بزمن صريح */
    private fun tempPdf(suffix: String, modifiedAt: Long): File {
        val f = File.createTempFile("ret-$suffix-${System.nanoTime()}", ".pdf")
        f.deleteOnExit()
        f.setLastModified(modifiedAt)
        return f
    }

    @Test
    fun `retention keeps newest twenty and deletes the older surplus`() {
        // 25 ملفاً حديثاً بطوابع زمنية متمايزة (الأقدم أولاً في القائمة) —
        // المرشَّح للحذف هو الأقدم 5 فقط (كلها داخل حد 30 يوماً)
        val files = (1..25).map { tempPdf("n$it", at10 - (25 - it) * 60_000L) }
        val toDelete = PdfRetention.candidatesToDelete(files, at10, keepNewest = 20)
        assertEquals(5, toDelete.size)
        // الأحدث 20 تبقى — أحدث ملف (آخر القائمة) ضمن الباقين وأقدمه (أول القائمة) محذوف
        val remaining = files.map { it.absolutePath }.toSet() - toDelete.map { it.absolutePath }.toSet()
        assertEquals(20, remaining.size)
        assertTrue(remaining.contains(files.last().absolutePath))
        assertFalse(remaining.contains(files.first().absolutePath))
    }

    @Test
    fun `retention deletes files older than thirty days even when few exist`() {
        val old = tempPdf("old", at10 - 31L * 86_400_000L)
        val recent = tempPdf("new", at10 - 1L * 86_400_000L)
        val toDelete = PdfRetention.candidatesToDelete(listOf(old, recent), at10, keepNewest = 20)
        assertEquals(listOf(old.absolutePath), toDelete.map { it.absolutePath })
    }

    @Test
    fun `retention boundary thirty days exactly is not expired`() {
        // عند 30 يوماً بالضبط (> maxAgeMs) لا يُحذف — الهامش حصري من جهة الأقدم
        val edge = tempPdf("edge", at10 - 30L * 86_400_000L)
        val toDelete = PdfRetention.candidatesToDelete(listOf(edge), at10, keepNewest = 20)
        assertTrue(toDelete.isEmpty())
    }

    @Test
    fun `retention ignores non pdf files`() {
        val txt = File.createTempFile("ret-note-${System.nanoTime()}", ".txt")
        txt.deleteOnExit()
        txt.setLastModified(at10 - 90L * 86_400_000L) // قديم جداً لكنه ليس pdf
        val toDelete = PdfRetention.candidatesToDelete(listOf(txt), at10, keepNewest = 20)
        assertTrue(toDelete.isEmpty())
    }
}
