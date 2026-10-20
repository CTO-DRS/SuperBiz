package com.superbiz.app.data.repo

import androidx.room.withTransaction
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.CouponEntity
import com.superbiz.app.data.db.LoyaltyEntryEntity
import com.superbiz.app.domain.LoyaltyP46
import kotlinx.coroutines.flow.Flow

/**
 * [P46-W1] جولة 7 — مستودع نقاط الولاء والكوبونات.
 *
 * عقد الرصيد: رصيد الطرف = مجموع دلتا دفتره (SUM حي لا أرصدة مخزنة) —
 * كل الكتابة إلحاق في الدفتر داخل معاملات الكتابة (البيع/الاستبدال/الإلغاء)
 * فلا انحراف بين ما قيل للعميل وما في الدفتر أبداً.
 *
 * عقد الاستهلاك: الكوبون يُستهلك بتحديث ذرّي مشروط (CouponDao.consume) داخل
 * نفس معاملة حفظ الفاتورة — فلا كوبون يُستهلك بفاتورة فشلت ولا فاتورة تمر
 * بكوبون استُنفد (السباق مغلقالباب بسطر UPDATE الواحد).
 *
 * الكسب لا يُكسب من فراغ: يُمرَّر إليه المرسل صافي البيع والمعاملات من
 * الإعدادات، والرياضيات كلها في LoyaltyP46 (صندوق الواحد).
 */
class LoyaltyRepo(private val db: AppDatabase) {

    // ── القراءة ────────────────────────────────────────────────────────

    /** رصيد نقاط طرف (مجموع الدلتا — الدفتر الفارغ = 0 صادق) */
    suspend fun balance(partyId: Long): Long =
        db.loyalty().sumByParty(partyId) ?: 0L

    /** كشف نقاط طرف حي — بطاقة الطرف في الذمم */
    fun ledger(partyId: Long): Flow<List<LoyaltyEntryEntity>> = db.loyalty().forParty(partyId)

    /** لقطة الكشف — التصدير والنسخ */
    suspend fun ledgerOnce(partyId: Long): List<LoyaltyEntryEntity> = db.loyalty().forPartyOnce(partyId)

    /**
     * فحص كوبون بالكود عبر البوابة الموحدة — يعيد الخصم أو سبب الرفض.
     * (الجلب هنا والمحاكمة في LoyaltyP46.checkSpec — الحساب نقي والوصول للقاعدة هنا فقط)
     * [RVW-17 V 3.2.6] العدّ من الدفتر الحي (شواهد coupon_redemptions للكود) يُمرّر
     * للمواصفة — بوابة الاستهلاك الموزّع fail-closed تجري حيةً من قاعدة الحقائق نفسها.
     */
    suspend fun checkCoupon(code: String, now: Long, basePiasters: Long): LoyaltyP46.CouponCheck {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return LoyaltyP46.CouponCheck.Rejected(LoyaltyP46.CouponReject.EMPTY)
        val c = db.coupons().byCode(trimmed)
            ?: return LoyaltyP46.CouponCheck.Rejected(LoyaltyP46.CouponReject.NOT_FOUND)
        val ledgerUses = db.couponRedemptions().countByCode(trimmed)
        return LoyaltyP46.checkSpec(c.toSpec(ledgerUses), now, basePiasters)
    }

    /** الكوبونات كلها — شاشة الإدارة في الإعدادات */
    fun coupons(): Flow<List<CouponEntity>> = db.coupons().all()

    suspend fun coupon(id: Long): CouponEntity? = db.coupons().byId(id)

    // ── الكتابة الإدارية ───────────────────────────────────────────────

    /** حفظ كوبون (جديد أو تعديل) — الكود فريد بمستوى القاعدة، التعارض يرمي للمستدعي */
    suspend fun saveCoupon(c: CouponEntity): Long {
        // [E0.4 V3.3.0] توازي طبقة البيانات — سطر المصفوفة 14: إنشاء/تعديل قواعد
        // الولاء والكوبونات — المالك والمدير (توازياً مع بوابة الVM الموثقة
        // في FeatureVMs LOYALTY_MANAGE). مسارات التطبيق على البيع (LOYALTY_APPLY)
        // لا تمس هنا — تمر عبر بوابة POS_SELL داخل save.
        com.superbiz.app.domain.rbac.RoleGate.require(
            com.superbiz.app.domain.rbac.SessionState.effective(),
            com.superbiz.app.domain.rbac.Op.LOYALTY_MANAGE
        )
        return db.coupons().upsert(c)
    }

    suspend fun deleteCoupon(id: Long) {
        // [E0.4 V3.3.0] توازي طبقة البيانات — سطر المصفوفة 14 نفسه.
        com.superbiz.app.domain.rbac.RoleGate.require(
            com.superbiz.app.domain.rbac.SessionState.effective(),
            com.superbiz.app.domain.rbac.Op.LOYALTY_MANAGE
        )
        db.coupons().delete(id)
    }

    /** منح/خصم يدوي إداري (بلا فاتورة) — يُلحق بالدفتر كأي صف */
    suspend fun manualAdjust(partyId: Long, delta: Long, note: String): Unit {
        require(delta != 0L) { "manual loyalty delta must be non-zero" }
        require(partyId > 0L) { "loyalty party required" }
        if (delta < 0L) {
            // الخصم اليدوي لا يتجاوز الرصيد — نفس حرس الاستبدال
            db.withTransaction {
                val bal = db.loyalty().sumByParty(partyId) ?: 0L
                require(bal >= -delta) { "redeem exceeds balance: $bal < ${-delta}" }
                db.loyalty().insert(
                    LoyaltyEntryEntity(partyId = partyId, invoiceId = null,
                        delta = delta, reason = "MANUAL_REDEEM", note = note)
                )
            }
        } else {
            db.loyalty().insert(
                LoyaltyEntryEntity(partyId = partyId, invoiceId = null,
                    delta = delta, reason = "MANUAL_GRANT", note = note)
            )
        }
    }

    // ── مسارات المعاملات (تُستدعى داخل withTransaction من InvoiceRepo) ──

    /**
     * كسب داخل معاملة قائمة — لا يفتح معاملة خاصة به (withTransaction متداخل آمن
     * لكن العقد هنا: تُستدعى حصراً من معاملة save/voidInvoice). كسب صفر (إعداد
     * فاسد أو صافي صفر) لا يكتب صفاً — الدفتر لا يحفظ أصفاراً كاذبة.
     */
    suspend fun earnInTransaction(partyId: Long, points: Long, invoiceId: Long, note: String) {
        if (points <= 0L) return
        db.loyalty().insert(
            LoyaltyEntryEntity(partyId = partyId, invoiceId = invoiceId,
                delta = points, reason = "EARN", note = note)
        )
    }

    /**
     * استبدال داخل معاملة قائمة — يتحقق من الرصيد الحي داخل نفس المعاملة
     * (لا ثقة بالرصيد المقروء خارجها) فلا استبدال يتجاوز الدفتر أبداً.
     */
    suspend fun redeemInTransaction(partyId: Long, points: Long, invoiceId: Long, note: String) {
        if (points <= 0L) return
        val bal = db.loyalty().sumByParty(partyId) ?: 0L
        require(bal >= points) { "redeem exceeds balance: $bal < $points" }
        db.loyalty().insert(
            LoyaltyEntryEntity(partyId = partyId, invoiceId = invoiceId,
                delta = -points, reason = "REDEEM", note = note)
        )
    }

    /**
     * استهلاك كوبون داخل معاملة قائمة — الحكم من الصفوف المتغيرة:
     * 0 = الكوبون غير صالح لحظة الحفظ (استُنفد/انتهى/عُطّل) ⇒ فشل الحفظ كله،
     * وهذا هو العقد: لا فاتورة بخصم كوبون لم يُستهلك فعلاً.
     *
     * [RVW-17 V 3.2.6] كتابة شاهد الدفتر داخل المعاملة نفسها (تصميم §7-ب):
     *  ① بوابة الدفتر fail-closed قبل التحديث الذري — حتى لو انجرع usedCount
     *     إلى ما دون الحد (تحت-عدّ الدمج الموثق أو أي انجراف) فما دام الدفتر
     *     يشهد ببلوغ الحد فالاستهلاك مرفض (اتجاه الفشل: رفض بلا خصم، أبداً العكس).
     *  ② التحديث الذري المشروط (consume) يبقى حارس الاستهلاك الفردي كما هو.
     *  ③ الحدث بساعة صفِر يتركها مشغّل اللمس — فيدخل دفتر الدفع دلتا تلقائياً.
     *  ④ [RVW-20 V 3.2.7] الشاهد إلزامي لا اختياري: قيمة insertOrIgnore محسوبة —
     *     ‎-1 (تصادم eventId الحتمي: نفس الفاتورة والكود على نفس الجهاز) يُسقط
     *     المعاملة كلها فلا انجراف عدّاد بلا شاهد أبداً (الاتجاه المفتوح الموثق
     *     كان يسمح بصمت بخصم بلا دفتر). التسامح مع ‎-1 حصراً في المسار الاتحادي
     *     (المزامنة/الاستعادة — دورة إعادة الدفع موثقة هناك) لا في مسار الاستهلاك.
     *     لا مسار حي يصطدم بالعقد: POS يولّد invId جديداً كل مرة، والمحرر لا يمرّر
     *     couponId أصلاً — فالعقد دفاع عميق بحدّ قاعدة البيانات مهما جاء مستدعٍ مستقبلاً.
     * deviceId يُحلّ قبل فتح المعاملة (InvoiceRepo يمرره — قراءة DataStore داخل
     * معاملة Room غير مضمونة) — فراغه لا يمنع الشاهد ("|id|code" ما يزال فريداً
     * بمفتاح الفاتورة المحلي) لكنه يوثّق في السجل أن المزوّد غير مربوط.
     */
    suspend fun consumeCouponInTransaction(couponId: Long, now: Long, invoiceId: Long, deviceId: String) {
        val c = db.coupons().byId(couponId)
            ?: throw IllegalArgumentException("coupon not found: $couponId")
        if (c.maxUses > 0) {
            val uses = db.couponRedemptions().countByCode(c.code)
            require(uses < c.maxUses) {
                "coupon ledger exhausted: ${c.code} $uses/${c.maxUses} (fail-closed)"
            }
        }
        require(db.coupons().consume(couponId, now) == 1) { "coupon not consumable: $couponId" }
        // [RVW-20 V 3.2.7] الشاهد إلزامي: ‎-1 = تصادم المعرّف الحتمي (استهلاك مكرر لنفس
        // الفاتورة على نفس الجهاز) — يُرمى هنا فيتراجع الاستهلاك الذري والحفظ كله معه:
        // فشل مغلَق بالاتجاه الصحيح (رفض بلا خصم) لا انجراف عدّاد بلا شاهد أبداً.
        val witness = db.couponRedemptions().insertOrIgnore(
            com.superbiz.app.data.db.CouponRedemptionEntity(
                eventId = "$deviceId|$invoiceId|${c.code}", code = c.code,
                invoiceId = invoiceId, qty = 1, at = now, origin = deviceId
            )
        )
        require(witness != -1L) {
            "coupon ledger witness collision: $deviceId|$invoiceId|${c.code} (fail-closed rollback)"
        }
    }

    /**
     * عكس أثر نقاط فاتورة ملغاة — صف تعويض واحد reason=VOID بمجموع أثرها المعاكس،
     * يُستدعى داخل معاملة voidInvoice. أثر صفري (فاتورة بلا نقاط) لا يكتب شيئاً.
     */
    suspend fun reverseInvoiceInTransaction(invoiceId: Long, note: String) {
        val net = db.loyalty().sumByInvoice(invoiceId) ?: 0L
        if (net == 0L) return
        val partyId = db.loyalty().partyIdByInvoice(invoiceId)
        require(partyId != null) { "loyalty rows vanished for invoice $invoiceId" }
        db.loyalty().insert(
            LoyaltyEntryEntity(partyId = partyId, invoiceId = invoiceId,
                delta = -net, reason = "VOID", note = note)
        )
    }
}

/** مواصفة الكوبون للبوابة النقية — من كيان Room (نمط المحركات النقية).
 *  [RVW-17] ledgerUses تُمرّر صراحة من مستدعيها (من يستشير الدفتر يُمرّر عدّه؛
 *  الافتراضي 0 يحفظ حكم ما قبل v18 لمن لا يستشير). */
fun CouponEntity.toSpec(ledgerUses: Int = 0): LoyaltyP46.CouponSpec = LoyaltyP46.CouponSpec(
    couponId = id, kind = kind, amountPiasters = amountPiasters, percent = percent,
    expiresAt = expiresAt, maxUses = maxUses, usedCount = usedCount, active = active,
    ledgerUses = ledgerUses
)
