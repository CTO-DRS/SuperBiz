package com.superbiz.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P46-W1] جولة 7 — عقد صندوق رياضيات الولاء النقي (LoyaltyP46).
 *
 * نفس فلسفة عقود المحركات النقية (LineTaxP41Test نموذجاً): القسمة الأرضية
 * للكسب، قص الاستبدال بالرصيد وسقف الصافي، تقريب النسبة بحرف التاريخ
 * Math.round، ورفض الكوبون بأسبابه الخمسة — كلها قرارات موثقة في رأس المحرك.
 */
class LoyaltyP46Test {

    // ── الكسب ─────────────────────────────────────────────────────────

    @Test
    fun earn_floor_division_per_divisor() {
        // 15500 قروش صافية مع 1000 قروش/نقطة = 15 نقطة والبقية تنتظر
        assertEquals(15L, LoyaltyP46.earnPoints(15500L, 1000L))
        // مضاعبة تامة — بلا بقايا
        assertEquals(25L, LoyaltyP46.earnPoints(25000L, 1000L))
        // أقل من المضاعف = صفر (لا كسور نقاط)
        assertEquals(0L, LoyaltyP46.earnPoints(999L, 1000L))
    }

    @Test
    fun earn_guards_zero_divisor_and_zero_net() {
        // إعداد فاسد (≤0) يمنح صفراً بصمت — الكسب لا يقطع البيع أبداً
        assertEquals(0L, LoyaltyP46.earnPoints(50000L, 0L))
        assertEquals(0L, LoyaltyP46.earnPoints(50000L, -5L))
        // صافي صفر/سالب = بلا كسب
        assertEquals(0L, LoyaltyP46.earnPoints(0L, 1000L))
        assertEquals(0L, LoyaltyP46.earnPoints(-100L, 1000L))
    }

    // ── الاستبدال ──────────────────────────────────────────────────────

    @Test
    fun redeem_value_is_exact_integer_product() {
        assertEquals(120L, LoyaltyP46.redeemValue(12L, 10L))
        assertEquals(0L, LoyaltyP46.redeemValue(0L, 10L))
        assertEquals(0L, LoyaltyP46.redeemValue(12L, 0L))   // قيمة فاسدة fail-closed
        assertEquals(0L, LoyaltyP46.redeemValue(-1L, 10L))
    }

    @Test
    fun max_redeemable_capped_by_net() {
        // صافي 950 قروش وقيمة النقطة 10 ⇒ أقصى 95 نقطة (قيمتها 950 بالضبط)
        assertEquals(95L, LoyaltyP46.maxRedeemablePoints(950L, 10L))
        // صافي أقل من قيمة نقطة واحدة = لا استبدال
        assertEquals(0L, LoyaltyP46.maxRedeemablePoints(9L, 10L))
        assertEquals(0L, LoyaltyP46.maxRedeemablePoints(950L, 0L))
    }

    @Test
    fun clamp_redeem_respects_balance_then_net() {
        // الرصيد هو السقف: طلب 500 والرصيد 200 ⇒ 200
        assertEquals(200L, LoyaltyP46.clampRedeem(500L, 200L, 100_000L, 10L))
        // الصافي هو السقف: رصيد 500 وصافي 500 قروش وقيمة 10 ⇒ 50 نقطة
        assertEquals(50L, LoyaltyP46.clampRedeem(500L, 500L, 500L, 10L))
        // طلب سالب/صفر = صفر
        assertEquals(0L, LoyaltyP46.clampRedeem(0L, 500L, 500L, 10L))
        assertEquals(0L, LoyaltyP46.clampRedeem(-3L, 500L, 500L, 10L))
    }

    // ── الكوبونات ──────────────────────────────────────────────────────

    @Test
    fun coupon_fixed_is_capped_at_base() {
        assertEquals(2500L, LoyaltyP46.couponDiscount(LoyaltyP46.KIND_FIXED, 2500L, 0.0, 10_000L))
        // ثابت أكبر من الأساس يقف عنده
        assertEquals(10_000L, LoyaltyP46.couponDiscount(LoyaltyP46.KIND_FIXED, 50_000L, 0.0, 10_000L))
        // سالب يُقص صفراً (بوابة القبول مسؤولية checkSpec)
        assertEquals(0L, LoyaltyP46.couponDiscount(LoyaltyP46.KIND_FIXED, -100L, 0.0, 10_000L))
        // أساس صفر = لا خصم مهما كانت القيمة
        assertEquals(0L, LoyaltyP46.couponDiscount(LoyaltyP46.KIND_FIXED, 500L, 0.0, 0L))
    }

    @Test
    fun coupon_percent_rounds_with_math_round_heritage() {
        // 1550 × 15% = 232.5 ⇒ Math.round = 233 (نفس حرف التاريخ المحاسبي LineTaxP41)
        assertEquals(233L, LoyaltyP46.couponDiscount(LoyaltyP46.KIND_PERCENT, 0L, 15.0, 1550L))
        assertEquals(1500L, LoyaltyP46.couponDiscount(LoyaltyP46.KIND_PERCENT, 0L, 15.0, 10_000L))
        // نسبة خارج 0..100 تُحصر قبل الحساب
        assertEquals(10_000L, LoyaltyP46.couponDiscount(LoyaltyP46.KIND_PERCENT, 0L, 150.0, 10_000L))
        assertEquals(0L, LoyaltyP46.couponDiscount(LoyaltyP46.KIND_PERCENT, 0L, -5.0, 10_000L))
        // NaN يُعامل صفراً
        assertEquals(0L, LoyaltyP46.couponDiscount(LoyaltyP46.KIND_PERCENT, 0L, Double.NaN, 10_000L))
    }

    private val spec = LoyaltyP46.CouponSpec(
        couponId = 7L, kind = LoyaltyP46.KIND_FIXED, amountPiasters = 2000L,
        percent = 0.0, expiresAt = 0L, maxUses = 0, usedCount = 0, active = true
    )

    @Test
    fun check_spec_ok_with_discount() {
        val r = LoyaltyP46.checkSpec(spec, now = 1000L, basePiasters = 50_000L)
        assertTrue("كوبون سليم يُقبل", r is LoyaltyP46.CouponCheck.Ok)
        assertEquals(2000L, (r as LoyaltyP46.CouponCheck.Ok).discountPiasters)
    }

    @Test
    fun check_spec_rejections_are_explicit() {
        assertEquals(
            LoyaltyP46.CouponReject.INACTIVE,
            (LoyaltyP46.checkSpec(spec.copy(active = false), 1000L, 50_000L) as LoyaltyP46.CouponCheck.Rejected).reason
        )
        assertEquals(
            LoyaltyP46.CouponReject.EXPIRED,
            (LoyaltyP46.checkSpec(spec.copy(expiresAt = 999L), 1000L, 50_000L) as LoyaltyP46.CouponCheck.Rejected).reason
        )
        // expiresAt == لحظة الفحص الآن ما زال صالحاً (العقد: now > expiresAt يرفض)
        assertTrue(LoyaltyP46.checkSpec(spec.copy(expiresAt = 1000L), 1000L, 50_000L) is LoyaltyP46.CouponCheck.Ok)
        assertEquals(
            LoyaltyP46.CouponReject.EXHAUSTED,
            (LoyaltyP46.checkSpec(spec.copy(maxUses = 3, usedCount = 3), 1000L, 50_000L) as LoyaltyP46.CouponCheck.Rejected).reason
        )
        // maxUses = 0 = بلا حد — usedCount مهما بلغ لا يرفض
        assertTrue(LoyaltyP46.checkSpec(spec.copy(maxUses = 0, usedCount = 99), 1000L, 50_000L) is LoyaltyP46.CouponCheck.Ok)
        // فارغ/مجهول عبر البوابة العامة
        assertEquals(
            LoyaltyP46.CouponReject.EMPTY,
            (LoyaltyP46.checkCoupon("  ", { null }, 1000L, 50_000L) as LoyaltyP46.CouponCheck.Rejected).reason
        )
        assertEquals(
            LoyaltyP46.CouponReject.NOT_FOUND,
            (LoyaltyP46.checkCoupon("GHOST", { null }, 1000L, 50_000L) as LoyaltyP46.CouponCheck.Rejected).reason
        )
    }

    // ── التركيب ────────────────────────────────────────────────────────

    @Test
    fun total_discount_sums_then_caps_at_net() {
        // جمع تام دون السقف
        assertEquals(1800L, LoyaltyP46.totalDiscount(1000L, 500L, 300L, 10_000L))
        // التركيب يتجاوز الصافي فيقف عنده تامة القص
        assertEquals(1500L, LoyaltyP46.totalDiscount(1000L, 500L, 500L, 1500L))
        // سالبات تُعامل صفراً
        assertEquals(1000L, LoyaltyP46.totalDiscount(1000L, -50L, -50L, 10_000L))
        // صافي صفر = صفر
        assertEquals(0L, LoyaltyP46.totalDiscount(1000L, 500L, 300L, 0L))
    }
}
