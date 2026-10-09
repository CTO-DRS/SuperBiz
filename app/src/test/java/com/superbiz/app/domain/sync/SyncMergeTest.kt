package com.superbiz.app.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات [SyncMerge] النقية — عقود ADR-002 D3/D4:
 * LWW الحتمي بكسر التعادل المعجمي، التجزئة بلا فقدان، وصمة الإقران.
 */
class SyncMergeTest {

    @Test
    fun `remote wins strictly by clock`() {
        assertTrue(SyncMerge.remoteWins(remoteAt = 100, localAt = 99, remoteDevice = "a", localDevice = "z"))
        assertFalse(SyncMerge.remoteWins(remoteAt = 99, localAt = 100, remoteDevice = "z", localDevice = "a"))
    }

    @Test
    fun `tie breaks lexicographically on device id — deterministic both ways`() {
        // نفس الساعة: الجهاز الأكبر معجمياً يفوز — كلا الجهازين يحسمان النتيجة نفسها
        assertTrue(SyncMerge.remoteWins(100, 100, "device-b", "device-a"))
        assertFalse(SyncMerge.remoteWins(100, 100, "device-a", "device-b"))
        // والتقارب: من منظور A وB، الفائز دائماً device-b
        val aPerspective = SyncMerge.remoteWins(100, 100, "device-b", "device-a") // B يفوز عند A
        val bPerspective = !SyncMerge.remoteWins(100, 100, "device-a", "device-b") // تغيير A لا يفوز عند B
        assertTrue(aPerspective && bPerspective)
    }

    @Test
    fun `equal clock and device — echo does not win (no ping-pong)`() {
        assertFalse(SyncMerge.remoteWins(100, 100, "same", "same"))
    }

    @Test
    fun `chunking keeps all rows and respects the cap`() {
        val weights = listOf(1000, 2000, 3_000_000, 4_000_000, 10)
        val batches = SyncMerge.chunkWeights(weights, 4_000_000)
        assertEquals(listOf(listOf(0, 1, 2), listOf(3), listOf(4)), batches)
        // الصف الواحد الأضخم من السقف يبقى كتلة وحيدة — لا يُقص
        val huge = SyncMerge.chunkWeights(listOf(9_999_999), 4_000_000)
        assertEquals(listOf(listOf(0)), huge)
        assertTrue(SyncMerge.chunkWeights(emptyList(), 100).isEmpty())
    }

    @Test
    fun `fingerprint is 4x4 hex uppercase groups`() {
        val kek = ByteArray(32) { it.toByte() }
        val fp = SyncMerge.fingerprint(kek)
        assertEquals(19, fp.length) // 16 hex + 3 شرطات
        assertTrue(fp.matches(Regex("[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}")))
        // مفاتيح مختلفة → بصمتان مختلفتان حتماً (شرط D5)
        val kek2 = ByteArray(32) { (it + 1).toByte() }
        assertTrue(fp != SyncMerge.fingerprint(kek2))
        // نفس المفتاح → نفس البصمة (شرط التوأمة)
        assertEquals(fp, SyncMerge.fingerprint(kek))
    }

    @Test
    fun `envelope json parses back with rows in order`() {
        val rows = listOf(
            SyncMerge.rowJson("parties", "dev1|7", 123L, false, "{\"name\":\"مؤسسة النور\"}"),
            SyncMerge.rowJson("currencies", "USD", 5L, true, null)
        )
        val env = SyncMerge.envelopeJson("dev1", 3, 999, rows)
        assertTrue(env.contains("\"format\":\"superbiz-sync\""))
        assertTrue(env.contains("\"version\":1"))
        assertTrue(env.contains("\"deviceId\":\"dev1\""))
        assertTrue(env.contains("\"seq\":3"))
        assertTrue(env.contains("\"d\":0,\"r\":{\"name\":"))
        assertTrue(env.contains("\"d\":1}"))
    }

    @Test
    fun `syncable tables exclude all financial ledgers`() {
        // عقد D3: السجلات المالية لا تُدمج قط — الحارس بنيوي
        val banned = listOf("journal", "journal_lines", "payments", "invoices", "invoice_items",
            "zatca_docs", "audit_log", "users", "user_secrets", "statements", "statement_deliveries")
        for (b in banned) assertFalse("جدول مالي في المزامنة: $b", SyncMerge.SYNCABLE_TABLES.contains(b))
    }
}
