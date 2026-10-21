package com.superbiz.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.PaymentTxEntity
import com.superbiz.app.data.db.RefundLedgerEntity
import com.superbiz.app.data.repo.PaymentsRepo
import com.superbiz.app.domain.algo.GatewayChargeResult
import com.superbiz.app.domain.algo.PspGateway
import com.superbiz.app.domain.algo.RefundResult
import com.superbiz.app.domain.algo.moneyEquals
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [E2.2 V3.6.0] اختبارات مستودع المدفوعات — العقد النقي مقفلاً على المخطط الحقيقي
 * ═══════════════════════════════════════════════════════════════════════════
 * RED-first موثق: هذه الحزمة كُتبت أولاً على stub مخطئ عمداً (بلا بوابة انقطاع،
 * بلا دفتر تعريف على مستوى المستودع، بلا سقف استرداد) ففشلت قبل التنفيذ الحقيقي.
 *
 * العقد المُختبَر هنا (نص خطة الموجة docs/P2_WAVE2_PLAN.md):
 *  1) تقسيم الدفع يُسجَّل صفّين هللة-دقيق: نقد مُحصَّل + بطاقة عبر البوابة، والمجموع = الإجمالي.
 *  2) بلا اتصال: البطاقة تُرفض إقرارياً بالسياسة — البوابة لا تُستدعى إطلاقاً ولا يُحصَّل شيء صامت.
 *  3) الاسترداد at-most-once على مستوى المستودع: فهرس فريد على المفتاح — المفتاح نفسه
 *     لا يستدعي بوابة المسترد مرتين بعمر القاعدة، والتكرار يعيد النتيجة الأولى same-ref repeated=true.
 *  4) سقف الاسترداد: مجموع المسترد لا يتجاوز المحصَّل الأصلي — هللة زائدة تُرفض باسم.
 *  5) فشل البوابة يُسجَّل FAILED صادقاً بلا إسقاط البيع النقدي.
 *  6) SAQ-A: لا حقل بيانات بطاقة في أي كيان أو توقيع — مرجع الاعتماد وحده يُخزَّن.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class PaymentsRepoE22Test {

    private var db: AppDatabase? = null

    @After
    fun tearDown() {
        db?.close()
    }

    private fun freshDb(): AppDatabase {
        val d = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        db = d
        return d
    }

    /** بوابة تسجيلية — تحصيل ناجح افتراضياً، وتحفظ كل استدعاءاتها للتحقق */
    private class FakeGateway(
        val throwOnCharge: Boolean = false,
    ) : PspGateway {
        override val pspId = "FAKE_PSP"
        val charges = mutableListOf<Pair<String, Double>>()
        val refunds = mutableListOf<Triple<String, Double, String>>()

        override fun charge(idempotencyKey: String, amount: Double): GatewayChargeResult {
            if (throwOnCharge) throw IllegalStateException("psp timeout")
            charges += idempotencyKey to amount
            return GatewayChargeResult(authRef = "auth-${charges.size}", approved = true)
        }

        override fun refund(idempotencyKey: String, amount: Double, authRef: String): RefundResult {
            refunds += Triple(idempotencyKey, amount, authRef)
            return RefundResult(refundId = "rf-${refunds.size}", amount = amount, repeated = false)
        }
    }

    // ───────── 1) تقسيم الدفع هللة-دقيق عبر البوابة ─────────

    @Test
    fun `split tender records halala-exact cash and card rows via gateway`() = runBlocking {
        val d = freshDb()
        val repo = PaymentsRepo(d)
        val gw = FakeGateway()

        // سيناريو الخلاط نفسه من نواة النطاق: 152.50 — 100 نقداً والباقي بطاقة
        val out = repo.recordSplitTender(
            invoiceId = 41L, total = 152.50, cashGiven = 100.0,
            online = true, gateway = gw, now = 1_700_000_000_000,
        )

        assertTrue(moneyEquals(out.split.cash, 100.0))
        assertTrue(moneyEquals(out.split.card, 52.50))
        assertEquals("البوابة استُدعيت مرة واحدة بحصة البطاقة وحدها", 1, gw.charges.size)
        assertTrue(moneyEquals(gw.charges[0].second, 52.50))

        val cash = d.paymentsE22().txById(out.cashTxId!!)!!
        assertEquals("CASH", cash.method)
        assertEquals("CAPTURED", cash.status)
        assertEquals(10_000L, cash.amountPiasters)
        assertEquals(41L, cash.invoiceId)

        val card = d.paymentsE22().txById(out.cardTxId!!)!!
        assertEquals("CARD", card.method)
        assertEquals("CAPTURED", card.status)
        assertEquals(5_250L, card.amountPiasters)
        assertEquals("auth-1", card.authRef)
        // SAQ-A بنيوي: كيان payment_tx بلا أي حقل بيانات بطاقة — ما يُخزَّن مرجع اعتماد
        // واسم مقدم الخدمة فقط (العقد نصياً في KDoc الكيان، وهذا التأكيد يعين الصفات المخزنة)
        assertEquals("FAKE_PSP", card.psp)
        assertTrue(card.authRef!!.startsWith("auth-"))

        // المجموع المحصَّل مطابق للإجمالي هللة هللة
        assertEquals(15_250L, cash.amountPiasters + card.amountPiasters)
    }

    // ───────── 2) بلا اتصال: رفض إقراري بلا استدعاء بوابة ─────────

    @Test
    fun `offline card is declined by policy and the gateway is never called`() = runBlocking {
        val d = freshDb()
        val repo = PaymentsRepo(d)
        val gw = FakeGateway()

        val out = repo.recordSplitTender(
            invoiceId = 42L, total = 200.0, cashGiven = 50.0,
            online = false, gateway = gw, now = 1_700_000_000_001,
        )

        // النقد مُحصَّل كالمعتاد — الانقطاع لا يلمس الحصة النقدية
        val cash = d.paymentsE22().txById(out.cashTxId!!)!!
        assertEquals("CAPTURED", cash.status)
        assertEquals(5_000L, cash.amountPiasters)

        // البطاقة: رفض صريح موثّق بالصف، والبوابة لم تُلمس إطلاقاً — لا تحصيل صامت ولا طابور يُحصَّل لاحقاً
        assertEquals("DECLINED_OFFLINE", out.cardStatus)
        assertTrue("بوابة منقطعة لا تُستدعى", gw.charges.isEmpty())
        val card = d.paymentsE22().txById(out.cardTxId!!)!!
        assertEquals("DECLINED_OFFLINE", card.status)
        assertEquals(15_000L, card.amountPiasters)
        assertEquals(null, card.authRef)
    }

    // ───────── 3) الاسترداد at-most-once على مستوى المستودع ─────────

    @Test
    fun `refund is at-most-once per key at the repo layer`() = runBlocking {
        val d = freshDb()
        val repo = PaymentsRepo(d)
        val gw = FakeGateway()

        val out = repo.recordSplitTender(
            invoiceId = 43L, total = 152.50, cashGiven = 0.0,
            online = true, gateway = gw, now = 1_700_000_000_002,
        )
        val cardTx = out.cardTxId!!

        val r1 = repo.refund("inv-43|refund|1", cardTx, 52.50, gw, now = 1_700_000_000_003)
        val r2 = repo.refund("inv-43|refund|1", cardTx, 52.50, gw, now = 1_700_000_000_004)

        assertEquals("بوابة المسترد استُدعيت مرة واحدة بعمر المفتاح", 1, gw.refunds.size)
        assertEquals(r1.refundId, r2.refundId)
        assertEquals(false, r1.repeated)
        assertEquals(true, r2.repeated)
        assertEquals("صف دفتر واحد بالفهرس الفريد", 1, d.paymentsE22().refundCount())
        assertTrue(moneyEquals(r2.amount, 52.50))
    }

    @Test
    fun `duplicate key resolves as repeated even without a prior read — unique index is the backstop`() = runBlocking {
        val d = freshDb()
        val repo = PaymentsRepo(d)
        val gw = FakeGateway()

        val out = repo.recordSplitTender(
            invoiceId = 44L, total = 80.0, cashGiven = 0.0,
            online = true, gateway = gw, now = 1_700_000_000_005,
        )
        // صف دفتر مُدرج خارج المستودع (محاكاة تسابق جهازين على المفتاح نفسه)
        d.paymentsE22().insertRefund(
            RefundLedgerEntity(
                idempotencyKey = "inv-44|refund|1", paymentTxId = out.cardTxId,
                amountPiasters = 8_000L, refundRef = "rf-external", createdAt = 1L,
            )
        )
        gw.refunds.clear()

        val r = repo.refund("inv-44|refund|1", out.cardTxId, 80.0, gw, now = 1_700_000_000_006)

        assertEquals("الفهرس الفريد صمّام الأمان: لا استدعاء ثانٍ للبوابة", 0, gw.refunds.size)
        assertTrue(r.repeated)
        assertEquals("rf-external", r.refundId)
    }

    // ───────── 4) سقف الاسترداد — هللة زائدة تُرفض باسم ─────────

    @Test
    fun `refund cannot exceed captured amount minus prior refunds`() = runBlocking {
        val d = freshDb()
        val repo = PaymentsRepo(d)
        val gw = FakeGateway()

        val out = repo.recordSplitTender(
            invoiceId = 45L, total = 100.0, cashGiven = 0.0,
            online = true, gateway = gw, now = 1_700_000_000_007,
        )
        val cardTx = out.cardTxId!!

        repo.refund("inv-45|refund|1", cardTx, 60.0, gw, now = 1_700_000_000_008)

        try {
            repo.refund("inv-45|refund|2", cardTx, 40.01, gw, now = 1_700_000_000_009)
            fail("الاسترداد فوق السقف يجب أن يُرفض")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("سقف الاسترداد"))
        }

        // استرداد يُنهي السقف بالتمام مشروع
        val r = repo.refund("inv-45|refund|3", cardTx, 40.0, gw, now = 1_700_000_000_010)
        assertTrue(moneyEquals(r.amount, 40.0))
        assertEquals(10_000L, d.paymentsE22().refundedSumPiasters(cardTx))
    }

    // ───────── 5) فشل البوابة يُسجَّل صادقاً ولا يُسقط البيع النقدي ─────────

    @Test
    fun `gateway failure records FAILED honestly without dropping the cash share`() = runBlocking {
        val d = freshDb()
        val repo = PaymentsRepo(d)
        val gw = FakeGateway(throwOnCharge = true)

        val out = repo.recordSplitTender(
            invoiceId = 46L, total = 152.50, cashGiven = 100.0,
            online = true, gateway = gw, now = 1_700_000_000_011,
        )

        val cash = d.paymentsE22().txById(out.cashTxId!!)!!
        assertEquals("CAPTURED", cash.status)
        val card = d.paymentsE22().txById(out.cardTxId!!)!!
        assertEquals("FAILED", card.status)
        assertEquals("FAILED", out.cardStatus)
        assertEquals(null, card.authRef)
        assertEquals("لا صف دفتر استرداد لعملية لم تُحصَّل", 0, d.paymentsE22().refundCount())
    }
}
