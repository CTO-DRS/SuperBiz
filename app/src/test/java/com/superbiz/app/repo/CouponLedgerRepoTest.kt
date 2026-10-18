package com.superbiz.app.repo

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.CouponEntity
import com.superbiz.app.data.db.CouponRedemptionEntity
import com.superbiz.app.data.repo.LoyaltyRepo
import com.superbiz.app.domain.LoyaltyP46
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
 * [RVW-17 V 3.2.6] دفتر استهلاك الكوبونات — قاعدة حقيقية (Room في الذاكرة، لا mocks)
 * ═══════════════════════════════════════════════════════════════════════════
 * يغلق اختباراً السيناريو المثير للانتباه الذي أوثقه RVW-2: تحت-عدّ الدمج.
 * العقد المقفول هنا على مستوى المستودع (حيث المعاملة الحقيقية):
 *  1) كل استهلاك ذرّي ناجح يكتب حدثاً واحداً بمعرّف حتمي deviceId|invoiceId|code
 *     — وإعادة إدراج الحدث نفسه (دورة استعادة/إعادة دفع) تُتجاهل بالمعرّف الفريد.
 *  2) بوابة الدفتر داخل المعاملة: حتى لو عاد usedCount إلى ما دون الحد (تحت-عدّ
 *     الدمج الموثق أو أي انجراف) — الاستهلاك الثاني يُرفض ما دام الدفتر يشهد بالحد.
 *  3) فحص الكوبون يعبّئ ledgerUses من الدفتر الحي — الرفض من الدفتر وحده ممكن.
 *  4) كوبون بلا حد (maxUses=0) يستهلك بحرية ويُبقي شاهد الحدث في الدفتر.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CouponLedgerRepoTest {

    private lateinit var db: AppDatabase
    private lateinit var loyalty: LoyaltyRepo

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = androidx.room.Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        loyalty = LoyaltyRepo(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun newCoupon(code: String, maxUses: Int): Long =
        db.coupons().upsert(CouponEntity(code = code, maxUses = maxUses, active = true))

    @Test
    fun `consume writes exactly one deterministic event and duplicate event id is ignored`() = runBlocking {
        val cid = newCoupon("LEDGER-1", maxUses = 5)
        db.withTransaction {
            loyalty.consumeCouponInTransaction(cid, now = 1_000L, invoiceId = 42L, deviceId = "dev-A")
        }
        val rows = db.couponRedemptions().allOnce()
        assertEquals(1, rows.size)
        assertEquals("dev-A|42|LEDGER-1", rows[0].eventId)
        assertEquals("LEDGER-1", rows[0].code)
        assertEquals(42L, rows[0].invoiceId)
        assertEquals(1, rows[0].qty)
        assertEquals("dev-A", rows[0].origin)
        assertEquals(1, db.coupons().byId(cid)?.usedCount)

        // إعادة إدراج الحدث نفسه (نفس المعرّف الحتمي) تُتجاهل — الاتحاد بالمعرّف ودال
        val ignored = db.couponRedemptions().insertOrIgnore(
            CouponRedemptionEntity(
                eventId = "dev-A|42|LEDGER-1", code = "LEDGER-1",
                invoiceId = 42L, qty = 1, at = 1_000L, origin = "dev-A"
            )
        )
        assertEquals("إعادة الحدث نفسه لا تُدرج صفّاً ثانياً", -1L, ignored)
        assertEquals(1, db.couponRedemptions().countByCode("LEDGER-1"))
    }

    @Test
    fun `ledger gate rejects second consume even when merged usedCount under-counts`() = runBlocking {
        val cid = newCoupon("LEDGER-2", maxUses = 1)
        db.withTransaction {
            loyalty.consumeCouponInTransaction(cid, now = 1_000L, invoiceId = 7L, deviceId = "dev-A")
        }
        // محاكاة تحت-عدّ الدمج الموثق (RVW-2): العدّاد المجمّع عاد إلى 0 رغم حدوث الاستهلاك —
        // دفتر الأحداث هو شاهد الحقيقة، والبوابة fail-closed تُغلق الباب تِمة الشاهد
        val row = db.coupons().byId(cid)!!
        db.coupons().upsert(row.copy(usedCount = 0))
        assertEquals(0, db.coupons().byId(cid)?.usedCount)
        assertEquals("الدفتر يشهد باستهلاك واحد", 1, db.couponRedemptions().countByCode("LEDGER-2"))

        var message = ""
        try {
            db.withTransaction {
                loyalty.consumeCouponInTransaction(cid, now = 2_000L, invoiceId = 8L, deviceId = "dev-A")
            }
        } catch (e: IllegalArgumentException) {
            message = e.message ?: ""
        }
        assertTrue("الاستهلاك الثاني يُرفض بالدفتر رغم عدّاد under-count: $message",
            message.contains("ledger exhausted"))
        assertEquals("لم يُكتب حدث ثانٍ", 1, db.couponRedemptions().countByCode("LEDGER-2"))
        assertEquals("العدّاد لم يرتفع فوق الحد", 0, db.coupons().byId(cid)?.usedCount)
    }

    @Test
    fun `checkCoupon reflects live ledger and rejects from ledger alone`() = runBlocking {
        val cid = newCoupon("LEDGER-3", maxUses = 2)
        // حكم الافتتاح: بلا أحداث — صالح
        val open = loyalty.checkCoupon("LEDGER-3", now = 1_000L, basePiasters = 10_000L)
        assertTrue(open is LoyaltyP46.CouponCheck.Ok)

        db.withTransaction {
            loyalty.consumeCouponInTransaction(cid, now = 1_000L, invoiceId = 1L, deviceId = "dev-A")
        }
        // حدث واحد من حدّين — ما يزال صالحاً
        val mid = loyalty.checkCoupon("LEDGER-3", now = 2_000L, basePiasters = 10_000L)
        assertTrue(mid is LoyaltyP46.CouponCheck.Ok)

        db.withTransaction {
            loyalty.consumeCouponInTransaction(cid, now = 2_000L, invoiceId = 2L, deviceId = "dev-B")
        }
        // حدثان من حدّين — الرفض من الدفتر وحده (usedCount=2 هنا لكن القيد يُختبر بالتركيبة)
        val closed = loyalty.checkCoupon("LEDGER-3", now = 3_000L, basePiasters = 10_000L)
        assertTrue("بلوغ الحد بالدفتر يرفض",
            closed is LoyaltyP46.CouponCheck.Rejected &&
                closed.reason == LoyaltyP46.CouponReject.EXHAUSTED)

        // تحتن-عدّ مركّب: عدّاد مجمع قديم + دفتر مكتمل ⇒ الرفض يبقى
        val row = db.coupons().byId(cid)!!
        db.coupons().upsert(row.copy(usedCount = 0))
        val stillClosed = loyalty.checkCoupon("LEDGER-3", now = 4_000L, basePiasters = 10_000L)
        assertTrue("الدفتر وحده يكفل الرفض بعد انجراف العدّاد",
            stillClosed is LoyaltyP46.CouponCheck.Rejected &&
                stillClosed.reason == LoyaltyP46.CouponReject.EXHAUSTED)
    }

    @Test
    fun `unlimited coupon consumes freely and still records the evidence`() = runBlocking {
        val cid = newCoupon("LEDGER-4", maxUses = 0)
        repeat(3) { i ->
            db.withTransaction {
                loyalty.consumeCouponInTransaction(cid, now = 1_000L + i, invoiceId = 10L + i, deviceId = "dev-A")
            }
        }
        assertEquals("بلا حد — الاستهلاك حر", 3, db.coupons().byId(cid)?.usedCount)
        assertEquals("وشواهد الثلاثة كلها في الدفتر", 3, db.couponRedemptions().countByCode("LEDGER-4"))
    }

    @Test
    fun `different devices same invoice id never collide - device scoping in event id`() = runBlocking {
        val cid = newCoupon("LEDGER-5", maxUses = 0)
        // جهازان يستعملان نفس رقم الفاتورة المحلي (مساحات معرّف منفصلة) — لا تصادم
        db.withTransaction {
            loyalty.consumeCouponInTransaction(cid, now = 1_000L, invoiceId = 5L, deviceId = "dev-A")
        }
        db.withTransaction {
            loyalty.consumeCouponInTransaction(cid, now = 1_000L, invoiceId = 5L, deviceId = "dev-B")
        }
        assertEquals(2, db.couponRedemptions().countByCode("LEDGER-5"))
        val events = db.couponRedemptions().allOnce().map { it.eventId }.toSet()
        assertEquals(2, events.size)
        assertTrue(events.contains("dev-A|5|LEDGER-5"))
        assertTrue(events.contains("dev-B|5|LEDGER-5"))
    }
}
