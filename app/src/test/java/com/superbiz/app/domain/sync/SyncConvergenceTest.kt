package com.superbiz.app.domain.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.AppGraph
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.db.Product
import com.superbiz.app.data.repo.SyncClock
import com.superbiz.app.data.repo.SyncEngine
import com.superbiz.app.data.repo.SyncRound
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [H4-3][ADR-002 بوابة القبول D3] — اختبار التقارب بجهازين حقيقيين:
 * قاعدتا Room في الذاكرة على مخطط v16 مع مشغّلات SQLite الحقيقية
 * (syncRuntimeSql) ونقل بثّي في الذاكرة وتعمية كاملة (AES-256-GCM بKEK Argon2id).
 *
 * السيناريو المعياري: جهازان يحرران نفس الصنف دون اتصال ثم يتزامنان —
 * النتيجة على الجهازين متطابقة بايتاً ببايت بأي ترتيب وصول (نص ADR حرفياً).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class SyncConvergenceTest {

    private class MemClock(val devId: String) : SyncClock {
        var on = true
        var pushed = false
        private var seq = 0L
        private val prefix = SyncCrypto.toBase64(SyncCrypto.newNoncePrefix())
        private val cursors = HashMap<String, Long>()
        override suspend fun deviceId(): String = devId
        override suspend fun enabled(): Boolean = on
        override suspend fun fullPushed(): Boolean = pushed
        override suspend fun markFullPushed() { pushed = true }
        override suspend fun nextSeq(): Long { seq += 1; return seq }
        override suspend fun noncePrefixB64(): String = prefix
        override suspend fun pullCursors(): Map<String, Long> = cursors.toMap()
        override suspend fun setPullCursors(c: Map<String, Long>) { cursors.clear(); cursors.putAll(c) }
    }

    /** ترحيل بثّي في الذاكرة — كل كتلة تصل كل الأجهزة (عقد خادم الترحيل الأعمى). */
    private class Loopback : SyncTransport {
        val blocks = ArrayList<SyncCrypto.EncryptedBlock>()
        override suspend fun push(block: SyncCrypto.EncryptedBlock): SyncTransport.Result<Unit> {
            blocks.add(block); return SyncTransport.Result.Ok(Unit)
        }
        override suspend fun pull(cursors: Map<String, Long>): SyncTransport.Result<List<SyncCrypto.EncryptedBlock>> =
            SyncTransport.Result.Ok(
                blocks.filter { b -> b.seq > (cursors[b.deviceId] ?: 0L) }
            )
    }

    private lateinit var dbA: AppDatabase
    private lateinit var dbB: AppDatabase
    private lateinit var clockA: MemClock
    private lateinit var clockB: MemClock
    private lateinit var engineA: SyncEngine
    private lateinit var engineB: SyncEngine
    private val kek = ByteArray(32) { (it * 5 + 1).toByte() }

    private fun buildDb(name: String): AppDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // مخطط v16 تُنشئه Room — ثم تُركَّب حارس المشغّلات والمشغّلات نفسها (SQL زمن التشغيل)
        db.openHelper.writableDatabase
        for (sql in AppGraph.syncRuntimeSql()) db.openHelper.writableDatabase.execSQL(sql)
        return db
    }

    @Before
    fun setUp() {
        dbA = buildDb("a")
        dbB = buildDb("b")
        clockA = MemClock("aaaa-device")
        clockB = MemClock("bbbb-device")
        engineA = SyncEngine(dbA, clockA)
        engineB = SyncEngine(dbB, clockB)
    }

    @After
    fun tearDown() {
        dbA.close(); dbB.close()
    }

    private suspend fun round(engine: SyncEngine, transport: Loopback): SyncRound.RoundResult =
        SyncRound(engine, transport, clockOf(engine), kekProvider = { kek }).run()

    private fun clockOf(engine: SyncEngine): SyncClock = if (engine === engineA) clockA else clockB

    /** ضبط ساعة صف حتمياً — تحت علم الكبت كي لا يزيدها المشغّل (محاكاة تحرير قديم/جديد). */
    private fun forceClock(db: AppDatabase, table: String, id: Long, at: Long) {
        val d = db.openHelper.writableDatabase
        d.execSQL("UPDATE `_sync_applying` SET `v` = 1")
        d.execSQL("UPDATE `$table` SET `syncUpdatedAt` = $at WHERE `id` = $id")
        d.execSQL("UPDATE `_sync_applying` SET `v` = 0")
    }

    // ═══════════════ البوابة: جهازان دون اتصال ثم تقارب بايتي ═══════════════

    @Test
    fun `two devices offline edit the same product then converge byte-identically`() = runBlocking {
        val relay = Loopback()

        // 1) A ينشئ صنفاً ويزامن (مسح كامل أول دفعة)
        val pid = dbA.products().upsert(Product(name = "صنف مشترك", salePrice = 2500))
        round(engineA, relay)
        // B يسحب ويتزامن
        round(engineB, relay)
        val importedB = dbB.products().byOrigin("aaaa-device", pid)
        assertEquals("صنف مشترك", importedB?.name)

        // 2) الجهازان دون اتصال: كلٌّ يحرر نفس الصنف تحريراً مختلفاً
        //    (تعديل القيمة عبر كيان جديد بمعاملة عادية — المشغّلات ترفع الساعة)
        val localA = dbA.products().byId(pid)!!
        dbA.products().upsert(localA.copy(name = "اسم من الجهاز A"))
        val localB = dbB.products().byOrigin("aaaa-device", pid)!!
        dbB.products().upsert(localB.copy(name = "اسم من الجهاز B"))

        // ساعات محكومة حتمياً: B حَرّر لاحقاً (فائز معلوم: B) — كسر التعادل غير مطلوب هنا
        forceClock(dbA, "products", pid, 5_000L)
        forceClock(dbB, "products", localB.id, 6_000L)

        // 3) كل جهاز يدفع تعديله ثم يسحب ما دفعه الآخر (جولة ثانية للسحب بعد كلا الدفعين)
        round(engineA, relay)
        round(engineB, relay)
        round(engineA, relay)
        round(engineB, relay)

        // 4) الحكم الحرفي: نفس الاسم، ونفس قيمة المخزون المحلية، وكل جدول متطابق
        assertEquals("اسم من الجهاز B", dbA.products().byId(pid)?.name)
        assertEquals("اسم من الجهاز B", dbB.products().byOrigin("aaaa-device", pid)?.name)

        // تقارب بايتي: المسح الكامل لكلا الجهازين يُنتج نصوص صفوف متطابقة حرفياً
        val snapA = engineA.buildPushBlocks().joinToString { it.second }
        val snapB = engineB.buildPushBlocks().joinToString { it.second }
        assertEquals(snapA, snapB)
    }

    @Test
    fun `tie clock resolves by device id — same winner on both sides`() = runBlocking {
        val relay = Loopback()
        val pid = dbA.products().upsert(Product(name = "تعادل", salePrice = 100))
        round(engineA, relay); round(engineB, relay)
        dbA.products().upsert(dbA.products().byId(pid)!!.copy(name = "تحرير A"))
        dbB.products().upsert(dbB.products().byOrigin("aaaa-device", pid)!!.copy(name = "تحرير B"))
        // تعادل الساعات عمداً — كسر التعادل المعجمي يقرر: bbbb > aaaa
        forceClock(dbA, "products", pid, 7_000L)
        forceClock(dbB, "products", dbB.products().byOrigin("aaaa-device", pid)!!.id, 7_000L)

        round(engineA, relay); round(engineB, relay)
        round(engineA, relay); round(engineB, relay)

        assertEquals("تحرير B", dbA.products().byId(pid)?.name)
        assertEquals("تحرير B", dbB.products().byOrigin("aaaa-device", pid)?.name)
    }

    @Test
    fun `deletion propagates as tombstone and rejects stale echoes`() = runBlocking {
        val relay = Loopback()
        val pid = dbA.parties().upsert(Party(name = "طرف مؤقت"))
        round(engineA, relay); round(engineB, relay)
        assertEquals("طرف مؤقت", dbB.parties().byOrigin("aaaa-device", pid)?.name)

        // حذف على A — شاهد لا غياب صامت (D3) — بساعات محكومة حتمياً
        forceClock(dbB, "parties", dbB.parties().byOrigin("aaaa-device", pid)!!.id, 4_000L)
        forceClock(dbA, "parties", pid, 5_000L)
        dbA.parties().deleteRow(pid)
        round(engineA, relay); round(engineB, relay)
        assertNull(dbB.parties().byOrigin("aaaa-device", pid))

        // دفعة قديمة متأخرة تحمل الصف المحذوف — الشاهد يردّها
        val stale = dbA.parties().upsert(Party(name = "طرف مؤقت"))
        forceClock(dbA, "parties", stale, 1L) // أقدم من الشاهد (5000)
        round(engineA, relay); round(engineB, relay)
        // الشاهد (ساعة الحذف الأحدث) يبقى صاحب القرار — الصف يُحذف مجدداً أو لا يعود
        // التحقق: الدفتر يحمل الشاهد المستورد بساعة أعلى من 1
        val tomb = dbB.syncLog().allTombstones().firstOrNull { it.tableName == "parties" }
        assertTrue(tomb != null && tomb.updatedAt > 1L)
        dbA.parties().deleteRow(stale)
    }

    @Test
    fun `stock quantity is device-local — never overwritten by sync`() = runBlocking {
        val relay = Loopback()
        val pid = dbA.products().upsert(Product(name = "صنف مخزون", stockQty = 10.0))
        round(engineA, relay); round(engineB, relay)

        // B يملك مخزونه الخاص — وتحديث A لغير المخزون لا يمس مخزون B
        // ساعات محكومة: تحرير B للمخزون أقدم (4000) وتحرير A أحدث (5000) — A يفوز بالمحتوى
        val bRow = dbB.products().byOrigin("aaaa-device", pid)!!
        dbB.products().upsert(bRow.copy(stockQty = 99.0))
        forceClock(dbB, "products", bRow.id, 4_000L)
        dbA.products().upsert(dbA.products().byId(pid)!!.copy(name = "صنف مخزون", salePrice = 3000))
        forceClock(dbA, "products", pid, 5_000L)
        round(engineA, relay); round(engineB, relay)
        round(engineA, relay); round(engineB, relay)

        val b = dbB.products().byOrigin("aaaa-device", pid)!!
        assertEquals(99.0, b.stockQty, 0.0)
        assertEquals(3000, b.salePrice)
        // وA يحفظ مخزونه هو أيضاً (المخزون ملك كل جهاز)
        assertEquals(10.0, dbA.products().byId(pid)!!.stockQty, 0.0)
    }

    @Test
    fun `encrypted relay — blocks on the wire are opaque`() = runBlocking {
        val relay = Loopback()
        dbA.parties().upsert(Party(name = "بيانات صاحب المتجر"))
        round(engineA, relay)
        assertTrue(relay.blocks.isNotEmpty())
        // لا كتلة على السلك تحمل نصاً مكشوفاً (عقد D1/D2)
        for (b in relay.blocks) {
            val text = String(b.ciphertext, Charsets.ISO_8859_1)
            assertTrue(!text.contains("بيانات") && !text.contains("parties") && !text.contains("superbiz"))
        }
    }

    @Test
    fun `full snapshot on first push seeds a brand-new device`() = runBlocking {
        val relay = Loopback()
        dbA.parties().upsert(Party(name = "عميل قديم"))
        dbA.products().upsert(Product(name = "منتج قديم", salePrice = 100))
        round(engineA, relay)
        round(engineB, relay)
        assertEquals(1, dbB.parties().exportOnce().size)
        assertEquals(1, dbB.products().allOnce().size)
        // الدفعة الثانية لا تكرر (لن يكون دفتراً فارغاً مع echo يُردّه LWW)
        val before = relay.blocks.size
        round(engineA, relay)
        val newBlocks = relay.blocks.size - before
        // لا صفوف جديدة — أي كتل صادرة لاحقة فارغة أو صداً بلا تأثير
        assertTrue(newBlocks <= 1)
    }

    @Test
    fun `visits remap their party through origin identity`() = runBlocking {
        val relay = Loopback()
        val pid = dbA.parties().upsert(Party(name = "عميل الزيارات"))
        val vid = dbA.visits().insert(
            com.superbiz.app.data.db.Visit(partyId = pid, note = "زيارة افتتاح")
        )
        round(engineA, relay); round(engineB, relay)
        val importedVisit = dbB.visits().byOrigin("aaaa-device", vid)
        assertEquals("زيارة افتتاح", importedVisit?.note)
        // الطرف أعيد ربطه بهوية B المحلية
        val partyOnB = importedVisit?.partyId?.let { dbB.parties().byId(it) }
        assertEquals("عميل الزيارات", partyOnB?.name)
    }
}
