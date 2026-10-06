package com.superbiz.app.domain

/**
 * [P46-W1] جولة 7 — نقاط الولاء والكوبونات: صندوق الرياضيات الوحيد.
 *
 * نفس عقود الصندوق النقي الموحد التي أرستها الموجات السابقة (LineTaxP41/ZatcaReturnP38):
 * الحفظ والتقرير والواجهة كلها تستمد أرقامها من هنا فلا انحراف بين المحجوز والمعلن أبداً.
 * المال كله قروش Long (عقد P33-P8) — لا Double في أي مبلغ، والتقريب الوحيد هنا
 * قسمة أعداد صحيحة (أرضية) بلا أي فاصلة عائمة على المبالغ.
 *
 * عقد الرصيد: رصيد الطرف = مجموع دلتا دفتر loyalty_entries له (لا جدول أرصدة
 * منفصل ينجرف عن دفتره — «الخطة = التنفيذ» بنمط P34). الكسب إيجابي والاستبدال
 * سالب، والإلغاء يعكس أثر فاتورته بصفي تعويض reason=VOID (مكتوب من InvoiceRepo).
 *
 * عقد الربح: النقاط تُكسب على الصافي (بعد الخصم وقبل الضريبة) — عرف شائع
 * موثق هنا، والاستبدال يتحول إلى خصم على الفاتورة بقيمة النقطة المضبوطة
 * في الإعدادات، مُسقفاً بالصافي كي لا يولّد خصماً أكبر من البيع نفسه.
 */
object LoyaltyP46 {

    // ── الكسب ─────────────────────────────────────────────────────────

    /**
     * نقاط بيع فاتورة: القسمة الصحيحة الأرضية — كل [divisorPiasters] قروش
     * صافية تمنح نقطة، والبقية تنتظر البيع التالي (لا كسور نقاط ولا ترحيل عشري).
     * divisor ≤ 0 (إعداد فاسد) يمنح صفراً بصمت — الكسب لا يقطع البيع أبداً.
     */
    fun earnPoints(netPiasters: Long, divisorPiasters: Long): Long {
        if (divisorPiasters <= 0L) return 0L
        if (netPiasters <= 0L) return 0L
        return netPiasters / divisorPiasters
    }

    // ── الاستبدال ──────────────────────────────────────────────────────

    /** قيمة نقاط بالقروش: عدد النقاط × قيمة النقطة — ضرب صحيح تام. */
    fun redeemValue(points: Long, pointValuePiasters: Long): Long {
        if (points <= 0L || pointValuePiasters <= 0L) return 0L
        return points * pointValuePiasters
    }

    /**
     * أقصى نقاط يستبدلها البيع: قيمتها لا تتجاوز صافي الفاتورة —
     * استبدال أكبر من البيع نفسه يولّد خصماً وهمياً فيُقص هنا حصراً.
     * pointValue ≤ 0 (إعداد فاسد) ⇒ لا استبدال إطلاقاً (fail-closed).
     */
    fun maxRedeemablePoints(netPiasters: Long, pointValuePiasters: Long): Long {
        if (pointValuePiasters <= 0L || netPiasters <= 0L) return 0L
        return netPiasters / pointValuePiasters
    }

    /** نقاط الاستبدال الفعلية: رصيد المتبرِّك مقصوصاً بسقف البيع — ما لا يُستبدل يبقى رصيداً. */
    fun clampRedeem(requestedPoints: Long, balance: Long, netPiasters: Long, pointValuePiasters: Long): Long {
        if (requestedPoints <= 0L) return 0L
        val byBalance = if (requestedPoints > balance) balance else requestedPoints
        return minOf(byBalance, maxRedeemablePoints(netPiasters, pointValuePiasters)).coerceAtLeast(0L)
    }

    // ── الكوبونات ──────────────────────────────────────────────────────

    /** 0 = مبلغ ثابت قروش · 1 = نسبة مئوية من الأساس */
    const val KIND_FIXED = 0
    const val KIND_PERCENT = 1

    /**
     * خصم الكوبون على أساس (صافي السلة بعد خصم السلة القياسي):
     * الثابت min(المبلغ, الأساس) — والنسبة Math.round(الأساس × النسبة / 100)
     * بحرف التاريخ المحاسبي نفسه (نمط LineTaxP41.lineTax) ثم القص بالأساس.
     * قيم فاسدة (سالب/نسبة خارج 0..100) تُعامل صفراً هنا — بوابة القبول
     * (couponUsable) هي التي ترفض الكوبون ككل، وهذه الدالة رياضيات فقط.
     */
    fun couponDiscount(kind: Int, amountPiasters: Long, percent: Double, basePiasters: Long): Long {
        if (basePiasters <= 0L) return 0L
        return when (kind) {
            KIND_PERCENT -> {
                val p = if (!percent.isFinite()) 0.0 else percent.coerceIn(0.0, 100.0)
                Math.round(basePiasters * p / 100.0).coerceIn(0L, basePiasters)
            }
            else -> amountPiasters.coerceIn(0L, basePiasters)
        }
    }

    /** أسباب رفض الكوبون — رسائلها الموطنة في الواجهة، والقرار هنا موحّد لكل المسارات. */
    enum class CouponReject { EMPTY, NOT_FOUND, INACTIVE, EXPIRED, EXHAUSTED }

    /** حالة فحص كوبون بالكود: صالح بقيمته أو مرفوض بسببه — لا حالة غامضة. */
    sealed class CouponCheck {
        data class Ok(val couponId: Long, val discountPiasters: Long) : CouponCheck()
        data class Rejected(val reason: CouponReject) : CouponCheck()
    }

    /**
     * فحص الكوبون كاملاً (فارغ/موجود/مفعّل/غير منتهٍ/لم يستنفد) ثم حساب خصمه —
     * البوابة الوحيدة التي تمررها POS وأي مستهلك مستقبلي، فلا مسار يتحقق نصف تحقق.
     * قيمة maxUses = 0 تعني بلا حد للاستخدام (عقد موثق).
     */
    fun checkCoupon(
        code: String,
        find: (String) -> CouponSpec?,
        now: Long,
        basePiasters: Long
    ): CouponCheck {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return CouponCheck.Rejected(CouponReject.EMPTY)
        val c = find(trimmed) ?: return CouponCheck.Rejected(CouponReject.NOT_FOUND)
        return checkSpec(c, now, basePiasters)
    }

    /** فحص مواصفة كوبون جالبة من القاعدة — نفس البوابة للكوبون المُحمَّل بمعرّفه. */
    fun checkSpec(c: CouponSpec, now: Long, basePiasters: Long): CouponCheck {
        if (!c.active) return CouponCheck.Rejected(CouponReject.INACTIVE)
        if (c.expiresAt > 0L && now > c.expiresAt) return CouponCheck.Rejected(CouponReject.EXPIRED)
        if (c.maxUses > 0 && c.usedCount >= c.maxUses) return CouponCheck.Rejected(CouponReject.EXHAUSTED)
        val d = couponDiscount(c.kind, c.amountPiasters, c.percent, basePiasters)
        return CouponCheck.Ok(c.couponId, d)
    }

    /** مواصفة الكوبون المجرّدة — تفصل الرياضيات عن كيان Room (نمط المحركات النقية). */
    data class CouponSpec(
        val couponId: Long,
        val kind: Int,
        val amountPiasters: Long,
        val percent: Double,
        val expiresAt: Long,
        val maxUses: Int,
        val usedCount: Int,
        val active: Boolean
    )

    // ── التركيب ────────────────────────────────────────────────────────

    /**
     * الخصم النهائي للفاتورة = خصم السلة القياسي + خصم الكوبون + قيمة النقاط
     * المُستبدلة — مقصوصاً بالصافي تامة القص: أي تركيب يتجاوز الصافي يقف عنده
     * (لا فاتورة سالبة مهما جمعت المصادر)، والقرار موثق هنا لا موزعاً على المسارات.
     */
    fun totalDiscount(cartDiscount: Long, couponDiscount: Long, redeemValuePiasters: Long, netPiasters: Long): Long {
        val sum = cartDiscount.coerceAtLeast(0L) + couponDiscount.coerceAtLeast(0L) + redeemValuePiasters.coerceAtLeast(0L)
        return if (sum > netPiasters) netPiasters.coerceAtLeast(0L) else sum
    }
}
