package com.superbiz.app

import com.superbiz.app.work.BackupTreeP7
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * [P7-L17 إصلاح] اختبارات منطق التدوير النقي لمرآة النسخ الاحتياطي في شجرة SAF.
 *
 * BackupTreeP7 (في BackupWorker.kt، نمط BackupAutoLogic) — بلا أي اعتماد على Android:
 * • pruneOld: حذف الأقدم من 7 أيام بنفس بادئة النسخ، مع حماية الأحدث keep نسخة،
 *   وتجاهل أي اسم لا يطابق بنية superbiz-backup-YYYY-MM-DD.json (ملفات المستخدم أمانة).
 * • backupName / dateFromName / dayMsFromName: بناء وتفكيك أسماء النسخ.
 */
class BackupTreeP7Test {

    private val day = BackupTreeP7.DAY_MS

    /** ثابت زمني حتمي: لحظة عشوائية بعيدة عن الأصفار الحدودية */
    private val now = 1_800_000_000_000L

    private fun name(date: String) = BackupTreeP7.backupName(date)

    /** أزواج (اسم، زمن) لعشر نسخ متتالية تبدأ قبل [now] بمقدار [ageDaysStart] يوماً */
    private fun tenDocs(ageDaysStart: Long): List<Pair<String, Long>> =
        (0L until 10L).map { i ->
            val age = ageDaysStart + i // كلما زاد i قِدّمت النسخة
            name("2026-01-${(10 + i).toString().padStart(2, '0')}") to (now - age * day)
        }

    // ─── pruneOld ───

    @Test
    fun `beyond 7 days the oldest three are deleted while newest seven kept`() {
        val docs = tenDocs(ageDaysStart = 1) // أعمار 1..10 أيام — الأقدم من 7 أيام حصراً: أعمار 8..10
        val toDelete = BackupTreeP7.pruneOld(docs, now)
        // الأقدم من 7 أيام (01-17 و01-18 و01-19 — أعمار 8 و9 و10) تُحذف، والبقية داخل النافذة
        assertEquals(
            listOf(name("2026-01-17"), name("2026-01-18"), name("2026-01-19")).sorted(),
            toDelete.sorted()
        )
    }

    @Test
    fun `all fresh backups delete nothing`() {
        val docs = (0L until 7L).map { i ->
            // أعمار 0..6 يوماً فقط — كلها داخل نافذة 7 أيام
            name("2026-01-${(10 + i).toString().padStart(2, '0')}") to (now - i * day)
        }
        assertTrue(BackupTreeP7.pruneOld(docs, now).isEmpty())
    }

    @Test
    fun `keep protects newest seven even when all are old`() {
        val docs = tenDocs(ageDaysStart = 30) // كلها أقدم من 7 أيام (أعمار 30..39)
        val toDelete = BackupTreeP7.pruneOld(docs, now)
        // يُحذف الثلاث الأقدم فقط ويبقى الأحدث 7
        assertEquals(3, toDelete.size)
        assertTrue(toDelete.contains(name("2026-01-19")))
        assertTrue(toDelete.contains(name("2026-01-18")))
        assertTrue(toDelete.contains(name("2026-01-17")))
        // الباقي = الأحدث 7 بالأصالة (i=0..6)
        assertEquals(docs.take(7).map { it.first }.toSet(), docs.map { it.first }.toSet() - toDelete.toSet())
    }

    @Test
    fun `boundary exactly 7 days is not old`() {
        val docs = listOf(name("2026-01-05") to (now - 7 * day))
        assertTrue(BackupTreeP7.pruneOld(docs, now).isEmpty())
    }

    @Test
    fun `a single old backup still survives keep protection`() {
        // حتى الأقدم من 7 أيام يبقى إن كانت سعة keep لم تمتلئ بعد (أرشيف صغير) —
        // الحذف الفعلي لا يحدث إلا لما يتجاوز الحماية (اختبار النسخ العشر أعلاه)
        val docs = listOf(name("2026-01-05") to (now - 8 * day))
        assertTrue(BackupTreeP7.pruneOld(docs, now).isEmpty())
    }

    @Test
    fun `mixed old and new caps retention at keep`() {
        val newDocs = (0L until 3L).map { i -> name("2026-03-${(10 + i)}") to (now - i * day) }
        val oldDocs = (0L until 5L).map { i -> name("2026-01-${(10 + i)}") to (now - (30 + i) * day) }
        val toDelete = BackupTreeP7.pruneOld(newDocs + oldDocs, now)
        // الأحدث 7 إجمالاً = 3 الجديدة + 4 القديمة الأحدث؛ المحذوف = القديمة الوحيدة خارج الحماية
        assertEquals(listOf(name("2026-01-14")), toDelete)
    }

    @Test
    fun `unknown age is treated as oldest but protected by keep`() {
        val fresh = (0L until 7L).map { i -> name("2026-03-${(10 + i)}") to (now - i * day) }
        val unknown = listOf(name("2026-01-01") to 0L)
        // سعة keep=7 مشغولة بالأحدث السبع => المجهول الزمن خارج الحماية ويُحذف
        assertEquals(listOf(name("2026-01-01")), BackupTreeP7.pruneOld(fresh + unknown, now))
        // ومع سعة أوسع يبقى
        assertTrue(BackupTreeP7.pruneOld(fresh + unknown, now, keep = 8).isEmpty())
    }

    @Test
    fun `foreign names are never touched`() {
        val docs = listOf(
            "notes.txt" to (now - 100 * day),
            "superbiz-backup.json" to (now - 100 * day),              // بلا تاريخ
            "superbiz-backup-2026-1-5.json" to (now - 100 * day),     // تاريخ غير مكتمل
            "superbiz-auto-1730000000000.json" to (now - 100 * day),  // تسمية النسخ الداخلية القديمة
            "superbiz-backup-2026-01-05.bak" to (now - 100 * day)     // لاحقة مختلفة
        )
        assertTrue(BackupTreeP7.pruneOld(docs, now).isEmpty())
    }

    @Test
    fun `keep zero or negative protects nothing`() {
        val docs = listOf(name("2026-01-05") to (now - 8 * day))
        assertEquals(listOf(name("2026-01-05")), BackupTreeP7.pruneOld(docs, now, keep = 0))
        assertEquals(listOf(name("2026-01-05")), BackupTreeP7.pruneOld(docs, now, keep = -3))
    }

    @Test
    fun `maxAgeDays zero horizon means only keep protects`() {
        val docs = tenDocs(ageDaysStart = 0)
        // أفق صفر: كل النسخ «قديمة» نظرياً — الأحدث 7 تبقى بحماية keep وحدها
        assertEquals(3, BackupTreeP7.pruneOld(docs, now, maxAgeDays = 0).size)
    }

    // ─── بناء وتفكيك الأسماء ───

    @Test
    fun `name roundtrip`() {
        val n = name("2026-02-05")
        assertEquals("superbiz-backup-2026-02-05.json", n)
        assertEquals("2026-02-05", BackupTreeP7.dateFromName(n))
    }

    @Test
    fun `dateFromName rejects non-conforming names`() {
        assertNull(BackupTreeP7.dateFromName("notes.txt"))
        assertNull(BackupTreeP7.dateFromName("superbiz-backup.json"))
        assertNull(BackupTreeP7.dateFromName("superbiz-backup-2026-1-5.json"))
        assertNull(BackupTreeP7.dateFromName("superbiz-backup-2026-02-05.bak"))
        assertNull(BackupTreeP7.dateFromName("superbiz-backup-abcdefghij.json"))
        assertNull(BackupTreeP7.dateFromName("superbiz-backup-20x6-01-05.json")) // حرف داخل السنة
        assertNull(BackupTreeP7.dateFromName("superbiz-backup-2026-0x-05.json")) // حرف داخل الشهر
    }

    @Test
    fun `dayMsFromName equals local midnight of that date`() {
        val expected = Calendar.getInstance().apply {
            clear()
            set(2026, 0, 5, 0, 0, 0) // 2026-01-05 منتصف الليل بالتوقيت المحلي
        }.timeInMillis
        assertEquals(expected, BackupTreeP7.dayMsFromName(name("2026-01-05")))
        assertNull(BackupTreeP7.dayMsFromName("superbiz-backup.json"))
    }

    @Test
    fun `consecutive dates ascend by about one day`() {
        val a = BackupTreeP7.dayMsFromName(name("2026-01-05"))!!
        val b = BackupTreeP7.dayMsFromName(name("2026-01-06"))!!
        assertNotEquals(a, b)
        assertTrue(b - a in (23 * 3_600_000L)..(25 * 3_600_000L)) // سماح لفرق التوقيت الصيفي
    }
}
