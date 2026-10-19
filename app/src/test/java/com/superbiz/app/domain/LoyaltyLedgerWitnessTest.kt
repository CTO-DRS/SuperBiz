package com.superbiz.app.domain

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.CouponRedemptionEntity
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
 * [RVW-20 V 3.2.7] عقد شاهد الدفتر الإلزامي — لا استهلاك بلا شاهد حيّ
 * ═══════════════════════════════════════════════════════════════════════════
 * الخلفية: consumeCouponInTransaction كان يستهلك ذرياً (consume == 1) ثم يُدرج
 * الشاهد بـ insertOrIgnore دون النظر إلى القيمة المعادة — فلو تصادم eventId
 * (نفس الفاتورة ونفس الكود على نفس الجهاز) لصمت الإدراج وعلا usedCount
 * بلا شاهد جديد: انجراف عدّاد بالاتجاه المفتوح — خصم بلا دفتر، بعكس العقد
 * الموثق في RVW-17 («اتجاه الفشل: رفض بلا خصم، أبداً العكس»).
 *
 * العقود المقفولة بهذا الاختبار (Room حقيقي في الذاكرة — نمط BackupRbacGateTest):
 *  1) تصادم الشاهد يسقط معاملة الحفظ كلها: رفض ظاهر لا صامت، وusedCount يرجع
 *     لحظة الفشل (التراجع الذري يلحق الاستهلاك)، والدفتر بلا زائدة كاذبة.
 *  2) الاستهلاك الطازج يترك شاهداً واحداً بالضبط ويتفق العدّادان (المجمَّع والدفتر)،
 *     وبهوية الولادة المحلية الموثقة (origin بالجهاز، بلا هوية مزامنة بعد).
 *  3) بوابة الدفتر (COUNT >= maxUses) تغلق قبل الاستهلاك الذري — والرفض داخل
 *     المعاملة يرجّع الحالة كما كانت.
 *
 * لا مسار حي يصطدم بالعقد ①: الدفع في POS يولّد invId جديداً كل مرة، ومحرر
 * الفواتير لا يمرّر couponId أصلاً (save بلا وسيطة الكوبون)، والاستعادة تحافظ
 * على المعرّفات فيستأنف الترقيم التلقائي بعد آخر فاتورة. العقد دفاع عميق بحدّ
 * قاعدة البيانات — لا استهلاك بلا شاهد مهما جاء المستدعي مستقبلاً.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LoyaltyLedgerWitnessTest {

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
        db.coupons().upsert(com.superbiz.app.data.db.CouponEntity(code = code, maxUses = maxUses))

    @Test
    fun `witness collision aborts consumption atomically - counter never drifts`() = runBlocking {
        val cid = newCoupon("SAVE10", maxUses = 2)
        // شاهد سابق لنفس (الجهاز، الفاتورة، الكود) — مستدعي مكرر أو دورة استعادة غريبة
        db.couponRedemptions().insertOrIgnore(
            CouponRedemptionEntity(
                eventId = "devA|42|SAVE10", code = "SAVE10",
                invoiceId = 42L, qty = 1, at = 1_000L, origin = "devA"
            )
        )
        var threw = false
        try {
            db.withTransaction {
                loyalty.consumeCouponInTransaction(cid, now = 2_000L, invoiceId = 42L, deviceId = "devA")
            }
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("تصادم الشاهد يجب أن يُسقط الحفظ برفض ظاهر (fail-closed)", threw)
        // التراجع الذري يلحق الاستهلاك: العدّاد كما كان والدفتر بلا زائدة كاذبة
        assertEquals("لا انجراف في usedCount بعد الفشل", 0, db.coupons().byId(cid)!!.usedCount)
        assertEquals("الشاهد الوحيد هو السابق — لا ازدواج ولا فقد", 1, db.couponRedemptions().countByCode("SAVE10"))
    }

    @Test
    fun `fresh consumption leaves exactly one witness and both counters agree`() = runBlocking {
        val cid = newCoupon("SAVE20", maxUses = 2)
        db.withTransaction {
            loyalty.consumeCouponInTransaction(cid, now = 3_000L, invoiceId = 43L, deviceId = "devA")
        }
        assertEquals(1, db.coupons().byId(cid)!!.usedCount)
        assertEquals(1, db.couponRedemptions().countByCode("SAVE20"))
        val rows = db.couponRedemptions().allOnce()
        assertEquals(1, rows.size)
        assertEquals("devA|43|SAVE20", rows[0].eventId)
        assertEquals("devA", rows[0].origin)
        // هوية الولادة المحلية الموثقة: بلا هوية مزامنة حتى يدفعها مشغّل اللمس
        assertEquals("", rows[0].originDeviceId)
        assertEquals(43L, rows[0].invoiceId)
    }

    @Test
    fun `ledger gate closes before atomic consume and rolls the attempt back`() = runBlocking {
        val cid = newCoupon("ONCE", maxUses = 1)
        db.couponRedemptions().insertOrIgnore(
            CouponRedemptionEntity(
                eventId = "devA|7|ONCE", code = "ONCE",
                invoiceId = 7L, qty = 1, at = 1_000L, origin = "devA"
            )
        )
        var threw = false
        try {
            db.withTransaction {
                loyalty.consumeCouponInTransaction(cid, now = 2_000L, invoiceId = 9L, deviceId = "devA")
            }
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("بوابة الدفتر ترفض عند بلوغ الحد", threw)
        assertEquals(0, db.coupons().byId(cid)!!.usedCount)
        assertEquals(1, db.couponRedemptions().countByCode("ONCE"))
    }
}
