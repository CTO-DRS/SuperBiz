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
     */
    suspend fun checkCoupon(code: String, now: Long, basePiasters: Long): LoyaltyP46.CouponCheck {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return LoyaltyP46.CouponCheck.Rejected(LoyaltyP46.CouponReject.EMPTY)
        val c = db.coupons().byCode(trimmed)
            ?: return LoyaltyP46.CouponCheck.Rejected(LoyaltyP46.CouponReject.NOT_FOUND)
        return LoyaltyP46.checkSpec(c.toSpec(), now, basePiasters)
    }

    /** الكوبونات كلها — شاشة الإدارة في الإعدادات */
    fun coupons(): Flow<List<CouponEntity>> = db.coupons().all()

    suspend fun coupon(id: Long): CouponEntity? = db.coupons().byId(id)

    // ── الكتابة الإدارية ───────────────────────────────────────────────

    /** حفظ كوبون (جديد أو تعديل) — الكود فريد بمستوى القاعدة، التعارض يرمي للمستدعي */
    suspend fun saveCoupon(c: CouponEntity): Long = db.coupons().upsert(c)

    suspend fun deleteCoupon(id: Long) = db.coupons().delete(id)

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
     */
    suspend fun consumeCouponInTransaction(couponId: Long, now: Long) {
        require(db.coupons().consume(couponId, now) == 1) { "coupon not consumable: $couponId" }
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

/** مواصفة الكوبون للبوابة النقية — من كيان Room (نمط المحركات النقية) */
fun CouponEntity.toSpec(): LoyaltyP46.CouponSpec = LoyaltyP46.CouponSpec(
    couponId = id, kind = kind, amountPiasters = amountPiasters, percent = percent,
    expiresAt = expiresAt, maxUses = maxUses, usedCount = usedCount, active = active
)
