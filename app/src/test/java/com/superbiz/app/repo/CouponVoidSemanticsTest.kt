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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [RVW-21 — wave R9] دلالة إلغاء فاتورة كوبونية — العقد يُقفل كما هو، والقرار للمالك
 * ═══════════════════════════════════════════════════════════════════════════
 * الخلفية: voidInvoice يعكس كل أثر الفاتورة (القيد المزدوج، الدفعات، المخزون،
 * ونقاط الولاء صراحة بعقد «لا نقاط أشباح») — لكنه لا يُعيد استهلاك الكوبون:
 * usedCount يبقى مرفوعاً وشاهد الدفتر يبقى قائماً. سلوك حقيقي بلا أي توثيق
 * في الوثائق الأربعة ولا في الكود — انكشف في جولة R9 (فحص تخريبي على أحدث
 * كود مالي، نمط R8) ولم يكن مُقفولاً بأي اختبار قبله.
 *
 * لماذا لا يُصلَح تلقائياً؟ عكس الاستهلاك تغيير دلالة مالية بقرار مالك
 * (نمط §7-ب: التصميم يُقترح، والتبنّي أمر المالك) — والاتجاه الحالي آمن
 * للمال أصلاً: فشل مغلق يمنع إحياء كوبون مستهلك عبر إلغاء وإعادة إنشاء.
 * الخياران الموثقان للمالك (FINDINGS §11):
 *  أ) الإبقاء على الدلالة الحالية (الاستهلاك نهائي) — ما يقفله هذا الاختبار.
 *  ب) الاسترجاع التعويضي بصف VOID في الدفتر الإلحاقي (qty=-1) مع تحويل
 *     بوابة countByCode إلى SUM(qty) — لا يُنفَّذ دون أمر صريح.
 *
 * العقود المقفولة هنا (Room حقيقي في الذاكرة — نمط LoyaltyLedgerWitnessTest):
 *  1) إلغاء فاتورة استهلكت كوبوناً: العدّاد كما هو والشاهد يبقى صادقاً
 *     (حدث استهلاك حقيقي وقع ولا يُمحى) والفاتورة تُلغى فعلاً.
 *  2) لا إحياء: إعادة نفس الكوبون (maxUses=1) بعد الإلغاء تُرفض فشلاً
 *     مغلقاً من بوابة الدفتر، والحفظ المرفوض لا يترك فاتورة جزئية.
 *  3) كوبون متعدد الاستخدام (maxUses=2): الإلغاء يحرق الاستخدام الأول،
 *     والفاتورة التالية تستهلك الثاني — والشاهدان كلاهما حيّان (الإلغاء
 *     لا يمسح تاريخ الدفتر).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CouponVoidSemanticsTest {

    private lateinit var db: AppDatabase
    private lateinit var invoices: InvoiceRepo
    private var partyId = 0L

    @Before
    fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = androidx.room.Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // [M-3.3] فاتورة بلا طرف مستحيلة — FK على invoices.partyId (RESTRICT)
        partyId = db.parties().upsert(Party(name = "عميل اختبار", type = 0))
        // المسار الإنتاجي الحقيقي: الحفظ يستهلك الكوبون داخل معاملة الفاتورة
        // (deviceIdProvider مثبت كما يربطه SuperBizApp بالإنتاج)
        invoices = InvoiceRepo(db, loyalty = LoyaltyRepo(db), deviceIdProvider = { "devA" })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun newCoupon(code: String, maxUses: Int): Long =
        db.coupons().upsert(CouponEntity(code = code, maxUses = maxUses))

    private fun saleInvoice(number: String) = Invoice(
        number = number, partyId = partyId, type = 0,
        date = 1_700_000_000_000L, dueDate = 1_700_000_000_000L,
        subtotal = 10_000L, taxAmount = 0L, total = 10_000L
    )

    private fun item() = InvoiceItem(invoiceId = 0, desc = "خدمة", qty = 1.0, unitPrice = 10_000L)

    @Test
    fun `voiding a couponed invoice never restores the burned use - counter and witness stand`() = runBlocking {
        val cid = newCoupon("ONCE", maxUses = 1)
        val invId = invoices.save(saleInvoice("INV-V1"), listOf(item()), couponId = cid)
        assertEquals("الاستهلاك عبر مسار الحفظ الحقيقي", 1, db.coupons().byId(cid)!!.usedCount)
        assertEquals(1, db.couponRedemptions().countByCode("ONCE"))

        invoices.voidInvoice(db.invoices().byId(invId)!!)

        assertEquals("الفاتورة ملغاة فعلاً", 3, db.invoices().byId(invId)!!.status)
        assertEquals("الإلغاء لا يُعيد الاستهلاك — العقد المقفول (RVW-21)", 1, db.coupons().byId(cid)!!.usedCount)
        assertEquals("الشاهد يبقى صادقاً — حدث استهلاك حقيقي وقع ولا يُمحى", 1, db.couponRedemptions().countByCode("ONCE"))
    }

    @Test
    fun `same single-use coupon after a void is refused fail-closed - no resurrection`() = runBlocking {
        val cid = newCoupon("ONCE2", maxUses = 1)
        val id1 = invoices.save(saleInvoice("INV-V2"), listOf(item()), couponId = cid)
        invoices.voidInvoice(db.invoices().byId(id1)!!)

        var threw = false
        try {
            invoices.save(saleInvoice("INV-V3"), listOf(item()), couponId = cid)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("إعادة نفس الكوبون بعد الإلغاء تُرفض فشلاً مغلقاً من بوابة الدفتر", threw)
        assertEquals("الرفض لا يترك فاتورة جزئية (تراجع كامل)", 1, db.invoices().count())
        assertEquals(1, db.coupons().byId(cid)!!.usedCount)
        assertEquals(1, db.couponRedemptions().countByCode("ONCE2"))
    }

    @Test
    fun `multi-use coupon - void burns the first use and the next invoice takes the second`() = runBlocking {
        val cid = newCoupon("TWICE", maxUses = 2)
        val id1 = invoices.save(saleInvoice("INV-V4"), listOf(item()), couponId = cid)
        invoices.voidInvoice(db.invoices().byId(id1)!!)
        assertEquals("الإلغاء يحرق الاستخدام الأول بلا استرجاع", 1, db.coupons().byId(cid)!!.usedCount)

        val id2 = invoices.save(saleInvoice("INV-V5"), listOf(item()), couponId = cid)
        assertEquals("الاستهلاك الثاني يمر — الاستخدام الفعلي 2/2", 2, db.coupons().byId(cid)!!.usedCount)
        assertEquals(2, db.couponRedemptions().countByCode("TWICE"))
        val witnesses = db.couponRedemptions().allOnce()
        assertEquals("كلا الشاهدين حيّان — الإلغاء لا يمسح تاريخ الدفتر", setOf(id1, id2), witnesses.map { it.invoiceId }.toSet())
    }
}
