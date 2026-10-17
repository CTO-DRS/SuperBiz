package com.superbiz.app.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.AuditLogEntity
import com.superbiz.app.data.db.SyncLogEntity
import com.superbiz.app.domain.Retention
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [RVW-6 V 3.2.2] جدار حصر النمو — sync_log وaudit_log على قاعدة Room حقيقية
 * ═══════════════════════════════════════════════════════════════════════════
 * نمط BackupCrossRoundtripTest حرفياً: AndroidJUnit4 + sdk 34 + inMemory +
 * allowMainThreadQueries + runBlocking. يثبّت:
 *
 * 1) بوابة Retention.pruneSyncLedger (نقي): يُقلَّم فقط جهاز بلا مزامنة
 *    مفعّلة **و**بلا دفعة كاملة سابقة — أي مزاج آخر يُبقي الدفتر (عقد
 *    «لا مخاطرة انحراف دلتا»).
 * 2) pruneUnsyncedLedger يمحو المعلّق (imported=0) وحده — الشواهد
 *    (imported=1) لا تُمس إطلاقاً حتى في التقليم.
 * 3) keepNewest يحفظ الأحدث N ويحذف الأقدم فحسب (إلحاق فقط: البقاء حسب
 *    ترتيب الإلحاق/المعرّف لا حسب المحتوى).
 * 4) سقف AUDIT_KEEP_ROWS كريم (القيمة الموثقة في السياسة).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RetentionMaintenanceTest {

    private lateinit var ctx: Context
    private lateinit var db: AppDatabase

    private val t0 = 1_700_000_000_000L

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ─── البوابة النقية ───

    @Test
    fun `prune gate opens only for never-synced devices`() {
        // جهاز بلا مزامنة قط — الحالة الشائعة التي كان الخطر 7 ينمو فيها
        assertTrue(Retention.pruneSyncLedger(syncEnabled = false, fullPushed = false))
        // مفعّلة الآن: العقد القائم يديره markPushed — لا تقليم آلي
        assertFalse(Retention.pruneSyncLedger(syncEnabled = true, fullPushed = false))
        // سبق دفع كامل وإن عُطّلت المزامنة: الدلتا تعتمد الدفتر — لا تقليم
        assertFalse(Retention.pruneSyncLedger(syncEnabled = false, fullPushed = true))
        assertFalse(Retention.pruneSyncLedger(syncEnabled = true, fullPushed = true))
    }

    @Test
    fun `unsynced ledger prune removes pending rows and keeps tombstones`() = runBlocking {
        // بذر: 3 معلّقة محلية (imported=0) + 2 شاهد مستورد (imported=1, deleted=1)
        repeat(3) { n ->
            db.syncLog().upsert(
                SyncLogEntity(
                    tableName = "parties", originDeviceId = "", originId = n + 1L,
                    updatedAt = t0 + n, deleted = false, imported = false, at = t0
                )
            )
        }
        repeat(2) { n ->
            db.syncLog().upsert(
                SyncLogEntity(
                    tableName = "products", originDeviceId = "device-9", originId = n + 1L,
                    updatedAt = t0 + n, deleted = true, imported = true, at = t0
                )
            )
        }
        assertEquals(5, db.syncLog().pendingCount() + 2)

        val deleted = db.syncLog().pruneUnsyncedLedger()
        assertEquals(3, deleted)
        // المعلّق ذهب
        assertEquals(0, db.syncLog().pendingCount())
        // الشواهد المستوردة بقيت حرفياً — عقد «الشواهد لا تُمس»
        assertEquals(2, db.syncLog().allTombstones().size)
        assertTrue(db.syncLog().allTombstones().all { it.imported && it.deleted })
    }

    @Test
    fun `keepNewest retains newest N rows by append order`() = runBlocking {
        // 120 حدثاً مُلحقاً بترتيب زمني صاعد
        repeat(120) { n ->
            db.auditLog().insert(
                AuditLogEntity(
                    actor = "owner", action = "STATEMENT_ISSUE",
                    details = "row-$n", ts = t0 + n
                )
            )
        }
        assertEquals(120, db.auditLog().count())

        val removed = db.auditLog().keepNewest(100)
        assertEquals(20, removed)
        assertEquals(100, db.auditLog().count())

        val rest = db.auditLog().allLogs()
        // الأقدم (المعرفات الأولى) هو ما حُذف — والبقاء بإلحاقه الأصلي
        assertEquals("row-20", rest.first().details)
        assertEquals("row-119", rest.last().details)
        // ترتيب الإلحاق محفوظ بلا إعادة ترتيب (عقد إلحاق فقط)
        assertEquals(rest.map { it.id }, rest.map { it.id }.sorted())
    }

    @Test
    fun `keepNewest below cap is a no-op`() = runBlocking {
        repeat(5) { n ->
            db.auditLog().insert(
                AuditLogEntity(actor = "owner", action = "A", details = "d$n", ts = t0 + n)
            )
        }
        assertEquals(0, db.auditLog().keepNewest(100))
        assertEquals(5, db.auditLog().count())
    }

    @Test
    fun `documented audit cap is generous - policy constant contract`() {
        // 50,000 صف أعوام استخدام فعلي — السقف سياسة موثقة لا فعل تشغيلي يومي
        assertEquals(50_000, Retention.AUDIT_KEEP_ROWS)
    }
}
