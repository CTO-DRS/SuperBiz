package com.superbiz.app.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.CouponEntity
import com.superbiz.app.data.db.Invoice
import com.superbiz.app.data.db.InvoiceItem
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.repo.InvoiceRepo
import com.superbiz.app.data.repo.LoyaltyRepo
import com.superbiz.app.domain.LoyaltyP46
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [P46-W1] جولة 7 — جدار تكاملي على قاعدة Room حقيقية للولاء والكوبونات (v12).
 *
 * العقود المجرَّبة:
 *  - الرصيد = مجموع دلتا الدفتر (لا أرصدة مخزنة تنحرف) — الكسب والاستبدال
 *    والتعويض صفوف تُلحق فتجمُع صادقة.
 *  - الاستبدال داخل معاملة الحفظ يتحقق من الرصيد الحي — تجاوزه يفشل الحفظ
 *    كله (الفاتورة لا تُكتب) لا نقاطاً سالبة.
 *  - الكوبون يُستهلك بتحديث ذرّي مشروط داخل معاملة الحفظ — الاستهلاك الثاني
 *    فوق الحد يفشل والفاتورة تتراجع معه.
 *  - إلغاء فاتورة يكتب صف تعويض reason=VOID داخل معاملة الإلغاء — الرصيد
 *    يعيد نفسه بالضبط (لا نقاط أشباح بإلغاء وإعادة إنشاء البيع).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LoyaltyRepoP46Test {

    private lateinit var db: AppDatabase
    private lateinit var loyalty: LoyaltyRepo
    private lateinit var invoices: InvoiceRepo

    private val t0 = 1_700_000_000_000L
    private val DAY = 86_400_000L

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = androidx.room.Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        loyalty = LoyaltyRepo(db)
        invoices = InvoiceRepo(db, loyalty)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun party(name: String = "عميل ولاء"): Long =
        db.parties().upsert(Party(name = name, type = 0))

    private fun saleInvoice(pid: Long, total: Long, number: String = "INV-9001") = Invoice(
        number = number, partyId = pid, type = 0,
        date = t0, dueDate = t0 + 14 * DAY,
        subtotal = total, discount = 0L,
        taxRate = 0.0, taxAmount = 0L, total = total,
        note = "P46"
    )

    private fun line(net: Long) = InvoiceItem(
        invoiceId = 0, productId = null, desc = "بند",
        qty = 1.0, unitPrice = net, discount = 0L
    )

    // ── الرصيد = مجموع الدلتا ──────────────────────────────────────────

    @Test
    fun balance_is_sum_of_ledger_deltas() = runBlocking {
        val pid = party()
        db.loyalty().insert(com.superbiz.app.data.db.LoyaltyEntryEntity(partyId = pid, delta = 15L, reason = "EARN"))
        db.loyalty().insert(com.superbiz.app.data.db.LoyaltyEntryEntity(partyId = pid, delta = 10L, reason = "EARN"))
        db.loyalty().insert(com.superbiz.app.data.db.LoyaltyEntryEntity(partyId = pid, delta = -5L, reason = "REDEEM"))
        assertEquals(20L, loyalty.balance(pid))
        // طرف آخر بلا دفتر = صفر صادق
        val other = party("آخر")
        assertEquals(0L, loyalty.balance(other))
    }

    // ── الكسب والاستبدال داخل معاملة حفظ الفاتورة ─────────────────────

    @Test
    fun save_earn_then_void_reverses_exactly() = runBlocking {
        val pid = party()
        // بيع 50 ريال (5000 قروش) بمضاعف 1000 ⇒ 5 نقاط تُكسب داخل المعاملة
        val id = invoices.save(
            saleInvoice(pid, 5000L), listOf(line(5000L)), moveStock = false,
            loyaltyEarn = LoyaltyP46.earnPoints(5000L, 1000L)
        )
        assertEquals(5L, loyalty.balance(pid))
        val earnRow = loyalty.ledgerOnce(pid).first { it.reason == "EARN" }
        assertEquals(id, earnRow.invoiceId)
        // الإلغاء يكتب تعويض -5 داخل معاملة voidInvoice فيعود الرصيد صفراً
        val inv = db.invoices().byId(id)!!
        invoices.voidInvoice(inv)
        assertEquals(0L, loyalty.balance(pid))
        val voidRow = loyalty.ledgerOnce(pid).first { it.reason == "VOID" }
        assertEquals(-5L, voidRow.delta)
        assertEquals(id, voidRow.invoiceId)
    }

    @Test
    fun save_with_redeem_writes_negative_row_and_save_fails_when_exceeding_balance() = runBlocking {
        val pid = party()
        // بذر رصيد 10 نقاط ثم بيع يستبدل 4 منها
        db.loyalty().insert(com.superbiz.app.data.db.LoyaltyEntryEntity(partyId = pid, delta = 10L, reason = "MANUAL_GRANT"))
        invoices.save(
            saleInvoice(pid, 2000L, "INV-9002"), listOf(line(2000L)), moveStock = false,
            loyaltyRedeem = 4L
        )
        assertEquals(6L, loyalty.balance(pid))
        assertTrue(loyalty.ledgerOnce(pid).any { it.reason == "REDEEM" && it.delta == -4L })

        // استبدال يتجاوز الرصيد (6) يفشل الحفظ كله — لا فاتورة ولا صف
        val invoicesBefore = db.invoices().allOnce().size
        var threw = false
        try {
            invoices.save(
                saleInvoice(pid, 3000L, "INV-9003"), listOf(line(3000L)), moveStock = false,
                loyaltyRedeem = 7L
            )
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("تجاوز الرصيد يجب أن يفشل الحفظ", threw)
        assertEquals("الفاتورة الفاشلة لا تُكتب", invoicesBefore, db.invoices().allOnce().size)
        assertEquals("الرصيد لم يُمس", 6L, loyalty.balance(pid))
    }

    // ── الكوبون: استهلاك ذرّي مشروط ───────────────────────────────────

    @Test
    fun coupon_consume_is_atomic_and_honors_limits() = runBlocking {
        val cid = loyalty.saveCoupon(
            CouponEntity(code = "WELCOME", kind = LoyaltyP46.KIND_FIXED,
                amountPiasters = 2500L, maxUses = 2)
        )
        val now = t0 + DAY
        assertEquals(1, db.coupons().consume(cid, now))
        assertEquals(1, db.coupons().consume(cid, now))
        assertEquals(0, db.coupons().consume(cid, now)) // استُنفد
        assertEquals(2, db.coupons().byId(cid)!!.usedCount)

        // الموقوف والمتقاعد يرفضهما الحرس الذرّي نفسه
        val stopped = loyalty.saveCoupon(
            CouponEntity(code = "STOPPED", kind = LoyaltyP46.KIND_FIXED, amountPiasters = 100L, active = false)
        )
        assertEquals(0, db.coupons().consume(stopped, now))
        val expired = loyalty.saveCoupon(
            CouponEntity(code = "OLD", kind = LoyaltyP46.KIND_FIXED, amountPiasters = 100L, expiresAt = t0)
        )
        assertEquals(0, db.coupons().consume(expired, now))
        // بلا حد (maxUses=0) لا يستنفد أبداً
        val endless = loyalty.saveCoupon(
            CouponEntity(code = "ALWAYS", kind = LoyaltyP46.KIND_FIXED, amountPiasters = 100L)
        )
        repeat(5) { assertEquals(1, db.coupons().consume(endless, now)) }
    }

    @Test
    fun checkCoupon_via_repo_returns_ok_or_explicit_rejection() = runBlocking {
        loyalty.saveCoupon(
            CouponEntity(code = "PCT10", kind = LoyaltyP46.KIND_PERCENT, percent = 10.0)
        )
        val ok = loyalty.checkCoupon(" PCT10 ", t0, 30_000L)
        assertTrue("الكود يُقص من الفراغات ويُقبل", ok is LoyaltyP46.CouponCheck.Ok)
        assertEquals(3000L, (ok as LoyaltyP46.CouponCheck.Ok).discountPiasters)
        assertEquals(
            LoyaltyP46.CouponReject.NOT_FOUND,
            (loyalty.checkCoupon("GHOST", t0, 30_000L) as LoyaltyP46.CouponCheck.Rejected).reason
        )
    }

    @Test
    fun save_with_coupon_consumes_inside_transaction_and_rolls_back_on_failure() = runBlocking {
        val pid = party()
        val cid = loyalty.saveCoupon(
            CouponEntity(code = "ONE", kind = LoyaltyP46.KIND_FIXED, amountPiasters = 500L, maxUses = 1)
        )
        invoices.save(
            saleInvoice(pid, 2000L, "INV-9004"), listOf(line(2000L)), moveStock = false,
            couponId = cid
        )
        assertEquals(1, db.coupons().byId(cid)!!.usedCount)

        // الاستهلاك الثاني (فوق maxUses=1) يفشل معاملته فتتراجع الفاتورة كلها
        val invoicesBefore = db.invoices().allOnce().size
        var threw = false
        try {
            invoices.save(
                saleInvoice(pid, 2000L, "INV-9005"), listOf(line(2000L)), moveStock = false,
                couponId = cid
            )
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("الكوبون المستنفد يجب أن يفشل الحفظ", threw)
        assertEquals(invoicesBefore, db.invoices().allOnce().size)
    }

    @Test
    fun manual_adjust_respects_floor_and_ledger_reasons() = runBlocking {
        val pid = party()
        loyalty.manualAdjust(pid, 25L, "هدية انضمام")
        assertEquals(25L, loyalty.balance(pid))
        var threw = false
        try {
            loyalty.manualAdjust(pid, -30L, "خصم أكبر من الرصيد")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("الخصم اليدوي لا يتجاوز الرصيد", threw)
        loyalty.manualAdjust(pid, -10L, "استبدال إداري")
        assertEquals(15L, loyalty.balance(pid))
        val reasons = loyalty.ledgerOnce(pid).map { it.reason }.toSet()
        assertTrue(reasons.contains("MANUAL_GRANT"))
        assertTrue(reasons.contains("MANUAL_REDEEM"))
        assertNotNull(db.loyalty().sumByParty(pid))
    }
}
