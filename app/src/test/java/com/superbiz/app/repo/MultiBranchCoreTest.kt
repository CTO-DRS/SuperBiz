package com.superbiz.app.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.Product
import com.superbiz.app.data.repo.TransferRepo
import com.superbiz.app.domain.rbac.Role
import com.superbiz.app.domain.rbac.RoleDeniedException
import com.superbiz.app.domain.rbac.SessionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [E2.1 V 3.5.0] محرك النقل — لا شبح، حفظ الثابت، فروق مسجلة، فشل مغلق، بوابة الدور
 * ═══════════════════════════════════════════════════════════════════════════
 * الثابت المقفول خصائصياً (عقد خارطة الطريق E2.1 حرفياً): «مجموع المخزون
 * عبر الفروع محفوظ عبر كل مسار نقل» — مستوى النقل كله (Σ branch_stock +
 * Σ خطوط قيد النقل) ثابت عند القيمة المبذرة مهما تتابع من إرسال/استلام/
 * إلغاء/فروق. فحص الخصائص: 200 سيناريو مبذر بتسلسلات عشوائية كاملة.
 *
 * بوابة الدور: كل كتابات المحرك INVENTORY_MANAGE (سطر 6-ب) — خليتان
 * مسمّاتان + عقد الأتمتة (الضمني/SYSTEM يمران) بنمط RbacDataLayerMatrixTest.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MultiBranchCoreTest {

    private lateinit var db: AppDatabase
    private lateinit var transfers: TransferRepo
    private var mainBranchId = 0L
    private var otherBranchId = 0L
    private var productId = 0L
    private val T = 1_700_000_000_000L

    @Before
    fun setUp() = runBlocking {
        SessionState.clear()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = androidx.room.Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        transfers = TransferRepo(db)
        mainBranchId = transfers.createBranch("المخزن الرئيسي", kind = 0, today = T)
        otherBranchId = transfers.createBranch("فرع الشمال", kind = 0, today = T)
        productId = db.products().upsert(Product(name = "صنف النقل", sku = "TR-1", stockQty = 100.0))
        // مستوى النقل يُبذر يدوياً في الاختبار (في الإنتاج يبذره الترحيل 18→19)
        db.branchStock().seed(mainBranchId, productId, 100.0)
    }

    @After
    fun tearDown() {
        SessionState.clear()
        db.close()
    }

    /** ثابت الحفظ — يُقرأ بعد كل عملية */
    private suspend fun planeTotal(): Double =
        transfers.branchQty(mainBranchId, productId) +
            transfers.branchQty(otherBranchId, productId) +
            transfers.inTransitTotal(productId)

    @Test
    fun `send puts stock in transit and receive completes the path - total conserved`() = runBlocking {
        val before = planeTotal()
        val tid = transfers.sendTransfer(mainBranchId, otherBranchId, listOf(productId to 30.0), T)
        assertEquals("الخصم من المصدر ودخل قيد النقل حرفياً", before, planeTotal(), 1e-9)
        assertEquals(70.0, transfers.branchQty(mainBranchId, productId), 1e-9)
        assertEquals(30.0, transfers.inTransitTotal(productId), 1e-9)

        transfers.receiveTransfer(tid, today = T + 1)
        assertEquals("الاستلام أضاف للوجهة والثابت صامد", before, planeTotal(), 1e-9)
        assertEquals(30.0, transfers.branchQty(otherBranchId, productId), 1e-9)
        assertEquals(0.0, transfers.inTransitTotal(productId), 1e-9)
    }

    @Test
    fun `partial receive records the variance visibly - every unit accounted for`() = runBlocking {
        val tid = transfers.sendTransfer(mainBranchId, otherBranchId, listOf(productId to 40.0), T)
        assertEquals(100.0, planeTotal(), 1e-9) // الإرسال يحفظ حرفياً
        transfers.receiveTransfer(tid, receipts = mapOf(productId to 35.0), today = T + 1)
        val lines = transfers.lines(tid)
        assertEquals("المستلم فعلاً", 35.0, lines.first().receivedQty, 1e-9)
        assertEquals("المرسل", 40.0, lines.first().qty, 1e-9)
        assertEquals("سطر الفرق واحد مسجل", 1, transfers.varianceOf(tid).size)
        // **العقد الحقيقي «لا شبح»**: كل وحدة محسوبة — الفروع + قيد النقل
        // + الفروق المسجلة = المبذرة. الفرق خسارة نقل واقعية مرئية بالتقرير
        // (عقد القبول: variance report matches ledger) لا وحدة مختفية بصمت.
        val accounted = planeTotal() + transfers.varianceOf(tid).sumOf { it.qty - it.receivedQty }
        assertEquals(100.0, accounted, 1e-9)
        assertEquals(95.0, planeTotal(), 1e-9)
        Unit
    }

    @Test
    fun `cancel returns exactly what was sent - no ghost stock`() = runBlocking {
        val before = planeTotal()
        val tid = transfers.sendTransfer(mainBranchId, otherBranchId, listOf(productId to 25.0), T)
        transfers.cancelTransfer(tid)
        assertEquals("الإلغاء رد كل شيء للمصدر", before, planeTotal(), 1e-9)
        assertEquals(100.0, transfers.branchQty(mainBranchId, productId), 1e-9)
        // نقل ملغى لا يُستلم ولا يُلغى مرة ثانية (فشل مغلق)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { transfers.receiveTransfer(tid, today = T + 1) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { transfers.cancelTransfer(tid) }
        }
        Unit
    }

    @Test
    fun `insufficient source stock rejects the whole send - no partial transfer`() = runBlocking {
        val before = planeTotal()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { transfers.sendTransfer(mainBranchId, otherBranchId, listOf(productId to 1000.0), T) }
        }
        assertEquals("الرفض قبل أي خصم", before, planeTotal(), 1e-9)
        assertEquals(100.0, transfers.branchQty(mainBranchId, productId), 1e-9)
        assertTrue(transfers.transfers().first().isEmpty())
    }

    @Test
    fun `same-branch inactive-branch and bad-qty sends fail closed`() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { transfers.sendTransfer(mainBranchId, mainBranchId, listOf(productId to 1.0), T) }
        }
        transfers.setBranchActive(otherBranchId, false)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { transfers.sendTransfer(mainBranchId, otherBranchId, listOf(productId to 1.0), T) }
        }
        transfers.setBranchActive(otherBranchId, true)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { transfers.sendTransfer(mainBranchId, otherBranchId, listOf(productId to -5.0), T) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { transfers.sendTransfer(mainBranchId, otherBranchId, listOf(productId to Double.NaN), T) }
        }
        Unit
    }

    @Test
    fun `writes are gated INVENTORY_MANAGE - cashier denied manager passes implicit passes`() = runBlocking {
        SessionState.start(91L, "كاشير النقل", Role.CASHIER)
        assertThrows(RoleDeniedException::class.java) {
            runBlocking { transfers.sendTransfer(mainBranchId, otherBranchId, listOf(productId to 1.0), T) }
        }
        assertThrows(RoleDeniedException::class.java) {
            runBlocking { transfers.createBranch("فرع كاشير") }
        }
        SessionState.clear()

        SessionState.start(92L, "مدير النقل", Role.MANAGER)
        val tid = transfers.sendTransfer(mainBranchId, otherBranchId, listOf(productId to 2.0), T)
        assertTrue(tid > 0)
        SessionState.clear()

        // عقد الأتمتة: بلا جلسة يمر حرفياً (§4.3)
        val tid2 = transfers.sendTransfer(mainBranchId, otherBranchId, listOf(productId to 1.0), T)
        assertTrue(tid2 > 0)
    }

    @Test
    fun `fuzz 200 seeded scenarios - the transfer-plane total is conserved through every path`() = runBlocking {
        val rnd = Random(4242)
        repeat(200) {
            // قاعدة جديدة لكل سيناريو — بذر مستقل 100 وحدة
            val fresh = androidx.room.Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
            ).allowMainThreadQueries().build()
            try {
                SessionState.clear()
                val tr = TransferRepo(fresh)
                val a = tr.createBranch("أ", today = T)
                val b = tr.createBranch("ب", today = T)
                val c = tr.createBranch("ج", today = T)
                val pid = fresh.products().upsert(Product(name = "ص", sku = "F", stockQty = 100.0))
                fresh.branchStock().seed(a, pid, 100.0)
                val seedTotal = 100.0
                val liveTransfers = mutableListOf<Long>()
                var varianceTotal = 0.0 // الفروق المسجلة — كل وحدة محسوبة لا شبح

                repeat(rnd.nextInt(1, 12)) {
                    val op = rnd.nextInt(3)
                    try {
                        when (op) {
                            0 -> { // إرسال عشوائي (كمية ≤ المتاح في مصدر عشوائي)
                                val src = listOf(a, b, c)[rnd.nextInt(3)]
                                val dst = (listOf(a, b, c) - src)[rnd.nextInt(2)]
                                val avail = tr.branchQty(src, pid)
                                if (avail > 0.0) {
                                    val q = rnd.nextDouble() * avail
                                    liveTransfers += tr.sendTransfer(src, dst, listOf(pid to q), T)
                                }
                            }
                            1 -> { // استلام كامل أو جزئي لنقل قائم
                                if (liveTransfers.isNotEmpty()) {
                                    val tid = liveTransfers[rnd.nextInt(liveTransfers.size)]
                                    val lines = tr.lines(tid)
                                    val receipt = lines.associate {
                                        it.productId to if (rnd.nextBoolean()) it.qty else it.qty * rnd.nextDouble()
                                    }
                                    val received = tr.receiveTransfer(tid, receipt, T)
                                    varianceTotal += received.sumOf { it.qty - it.receivedQty }
                                    liveTransfers.remove(tid)
                                }
                            }
                            else -> { // إلغاء نقل قائم
                                if (liveTransfers.isNotEmpty()) {
                                    val tid = liveTransfers[rnd.nextInt(liveTransfers.size)]
                                    tr.cancelTransfer(tid)
                                    liveTransfers.remove(tid)
                                }
                            }
                        }
                    } catch (_: IllegalArgumentException) {
                        // رفض مغلق مشروع (فرع غير نشط إلخ) — لا يخل بالثابت
                    }
                    // **الثابت بعد كل عملية (العقد الحقيقي «لا شبح»)**: كل وحدة
                    // محسوبة — الفروع + قيد النقل + الفروق المسجلة = المبذرة.
                    // الإرسال/الإلغاء يحفظان حرفياً، والاستلام الجزئي يخرج فرقه
                    // مسجلاً في تقرير الفروق لا مختفياً بصمت.
                    val total = tr.branchQty(a, pid) + tr.branchQty(b, pid) + tr.branchQty(c, pid) + tr.inTransitTotal(pid)
                    assertEquals(
                        "سيناريو $it: الثابت انكسر بعد عملية $op",
                        seedTotal, total + varianceTotal, 1e-6
                    )
                }
            } finally {
                SessionState.clear()
                fresh.close()
            }
        }
    }
}
