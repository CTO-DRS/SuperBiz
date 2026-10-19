package com.superbiz.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [RVW-17 V 3.2.6] عقد بوابة الدفتر — استهلاك الكوبونات الموزَّع (تصميم §7-ب)
 * ═══════════════════════════════════════════════════════════════════════════
 * الخلفية (AUD-3 + RVW-2): عدّاد usedCount مجمَّع يُدمج بحدّ أقصى — استهلاكان
 * دون اتصال على جهازين يتقاربان على usedCount=1 رغم أن الاستهلاك حصل مرتين.
 * دفتر الأحداث الإلحاقي (coupon_redemptions) يُصحّح المحصلة عند التقارب:
 * الاستخدام الفعلي = COUNT صفوف الدفتر للكود، ويُستشار كبوابة رفض إضافية
 * fail-closed عند ≥ maxUses — لا كوبون مستهلك موزَّعاً يمر من البوابة مرتين.
 *
 * عقد بوابة الدفتر (المقفول بهذا الاختبار):
 *  1) ledgerUses ≥ maxUses ⇒ رفض EXHAUSTED حتى لو كان usedCount أقل (تحت-عدّ الدمج).
 *  2) ledgerUses < maxUses لا يرفض وحده — البوابة إضافية لا بديلة عن شروط الفحص القديمة.
 *  3) maxUses = 0 (بلا حد) لا يستشير الدفتر إطلاقاً — العقد الموثق سابقاً يبقى حرفياً.
 *  4) ledgerUses = 0 (الافتراضي — مستهلك لا يستشير الدفتر) لا يغيّر حكم الفحص القديم.
 *  5) الرفض fail-closed بالاتجاه الصحيح فقط: قصاد أي مشكلة دفتر ترفض الكوبون،
 *     ولا تمنح خصماً بلا شاهد استهلاك أبداً.
 */
class CouponLedgerP48Test {

    private fun spec(
        maxUses: Int,
        usedCount: Int,
        ledgerUses: Int,
        active: Boolean = true,
        expiresAt: Long = 0L
    ) = LoyaltyP46.CouponSpec(
        couponId = 1L, kind = LoyaltyP46.KIND_FIXED, amountPiasters = 1_000L,
        percent = 0.0, expiresAt = expiresAt, maxUses = maxUses,
        usedCount = usedCount, active = active, ledgerUses = ledgerUses
    )

    @Test
    fun `ledger at or above maxUses rejects even when merged usedCount under-counts`() {
        // السيناريو الموثق في RVW-2: جهازان استهلكا دون اتصال — usedCount تقارب على 1
        // لكن دفتر الأحداث يجمع شاهدين فعليين. maxUses=2 ⇒ الدفتر يغلّق الباب.
        val s = spec(maxUses = 2, usedCount = 1, ledgerUses = 2)
        val verdict = LoyaltyP46.checkSpec(s, now = 1_000L, basePiasters = 10_000L)
        assertTrue("الدفتر يرفض عند بلوغ الحد رغم تحت-عدّ العدّاد المجمع",
            verdict is LoyaltyP46.CouponCheck.Rejected &&
                verdict.reason == LoyaltyP46.CouponReject.EXHAUSTED)
    }

    @Test
    fun `ledger below maxUses does not reject by itself`() {
        // البوابة إضافية لا بديلة: دفتر تحت الحد لا يحكم وحده — بقي شرط الفحص القديم حاكماً
        val s = spec(maxUses = 5, usedCount = 0, ledgerUses = 2)
        val verdict = LoyaltyP46.checkSpec(s, now = 1_000L, basePiasters = 10_000L)
        assertTrue("استخدامات الدفتر تحت الحد لا ترفض",
            verdict is LoyaltyP46.CouponCheck.Ok)
        assertEquals(1_000L, (verdict as LoyaltyP46.CouponCheck.Ok).discountPiasters)
    }

    @Test
    fun `unlimited coupon ignores the ledger entirely`() {
        // maxUses = 0 = بلا حد — العقد الموثق سابقاً يبقى حرفياً حتى مع دفتر مليء
        val s = spec(maxUses = 0, usedCount = 0, ledgerUses = 999)
        val verdict = LoyaltyP46.checkSpec(s, now = 1_000L, basePiasters = 10_000L)
        assertTrue("كوبون بلا حد لا يستشير الدفتر",
            verdict is LoyaltyP46.CouponCheck.Ok)
    }

    @Test
    fun `default ledgerUses zero preserves the legacy verdict exactly`() {
        // الافتراضي 0 = مستهلك لا يستشير الدفتر — حكم v17 يتطابق مع حكم v17- حرفياً
        val legacy = LoyaltyP46.CouponSpec(
            couponId = 1L, kind = LoyaltyP46.KIND_FIXED, amountPiasters = 1_000L,
            percent = 0.0, expiresAt = 0L, maxUses = 3, usedCount = 2, active = true
        )
        val carried = legacy.copy(ledgerUses = 0)
        assertEquals(
            LoyaltyP46.checkSpec(legacy, 1_000L, 10_000L),
            LoyaltyP46.checkSpec(carried, 1_000L, 10_000L)
        )
        assertTrue(LoyaltyP46.checkSpec(carried, 1_000L, 10_000L) is LoyaltyP46.CouponCheck.Ok)
    }

    @Test
    fun `fail-closed direction - ledger never grants what the counter denies`() {
        // الاتجاه الوحيد المسموح: إضافة رفض. الدفتر لا يلغي شرط العدّاد القديم
        val s = spec(maxUses = 2, usedCount = 2, ledgerUses = 0)
        val verdict = LoyaltyP46.checkSpec(s, now = 1_000L, basePiasters = 10_000L)
        assertTrue("العدّاد المجمع يرفض كما كان — الدفتر لا يخفف شيئاً",
            verdict is LoyaltyP46.CouponCheck.Rejected &&
                verdict.reason == LoyaltyP46.CouponReject.EXHAUSTED)
    }

    @Test
    fun `either gate alone closes the door`() {
        // أي بوابة من الاثنتين تكفي للإغلاق — لا ثقة بغائب أيٍّ منهما
        val byCounter = LoyaltyP46.checkSpec(spec(2, 2, 0), 1_000L, 10_000L)
        val byLedger = LoyaltyP46.checkSpec(spec(2, 0, 2), 1_000L, 10_000L)
        val both = LoyaltyP46.checkSpec(spec(2, 2, 2), 1_000L, 10_000L)
        for ((label, v) in listOf("counter" to byCounter, "ledger" to byLedger, "both" to both)) {
            assertTrue("بوابة $label يجب أن ترفض",
                v is LoyaltyP46.CouponCheck.Rejected &&
                    v.reason == LoyaltyP46.CouponReject.EXHAUSTED)
        }
    }
}
