package com.superbiz.app.data.repo

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.PaymentTxEntity
import com.superbiz.app.data.db.RefundLedgerEntity
import com.superbiz.app.domain.algo.AuthDecision
import com.superbiz.app.domain.algo.PspGateway
import com.superbiz.app.domain.algo.RefundResult
import com.superbiz.app.domain.algo.TenderSplit
import com.superbiz.app.domain.algo.cardAuthPolicy
import com.superbiz.app.domain.algo.splitTender
import com.superbiz.app.util.Money

/**
 * — مستودع المدفوعات (E2.2 — الموجة الثانية من المرحلة 2).
 *
 * يصل عقد النطاق النقي (PaymentsE22) بمستودع schema 20: كل قرش يمرّ من هنا
 * يُسجَّل صادقاً — نقد يُحصَّل محلياً، وبطاقة لا تعبر إلا عبر بوابة [PspGateway]
 * حاضرة واتصال حيّ، والاسترداد at-most-once بالفهرس الفريد على مستوى القاعدة.
 *
 * [SAQ-A] لا بيانات بطاقة تعبر هذا الملف إطلاقاً — مرجع اعتماد البوابة وحده يُخزَّن
 * (كيان payment_tx بنيوياً بلا حقول بطاقة).
 *
 * [RED-first]: الاختبارات السبعة كُتبت أولاً على stub مخطئ عمداً — ستة منها فشلت
 * موثقةً العيوب الثلاثة (بوابة انقطاع مُهمَلة، لا دفتر تعريف، لا سقف استرداد) —
 * ثم نُفِّذ هذا التنفيذ فأخضرّت كلها. الدليل النصي: scripts/red_e22.log.
 */
class PaymentsRepo(private val db: AppDatabase) {

    /** نتيجة تسجيل تقسيم الدفع: هويتا الصفين وحالة البطاقة الإقرارية */
    data class SplitOutcome(
        val split: TenderSplit,
        val cashTxId: Long?,
        val cardTxId: Long?,
        val cardStatus: String,     // CAPTURED | DECLINED_OFFLINE | FAILED
    )

    /**
     * تسجيل تقسيم دفع (نقد + بطاقة خلطاً) في معاملة واحدة:
     * - النقد يُحصَّل محلياً CAPTURED (لا بوابة في عقد النقد).
     * - البطاقة: بوابة سياسة الاتصال أولاً — بلا اتصال يُرفض القرار إقرارياً
     *   DECLINED_OFFLINE بلا أي استدعاء بوابة ولا تحصيل صامت ولا طابور يُحصَّل لاحقاً
     *   (OFFLINE_QUEUE_AUTO_CHARGE = false). مع اتصال: تحصيل عبر البوابة — الاعتماد
     *   يُسجَّل CAPTURED بمرجعه، والرفض/الانفجار يُسجَّل FAILED صادقاً دون إسقاط
     *   الحصة النقدية المحصَّلة.
     */
    suspend fun recordSplitTender(
        invoiceId: Long?,
        total: Double,
        cashGiven: Double,
        online: Boolean,
        gateway: PspGateway?,
        now: Long,
    ): SplitOutcome = db.withTransaction {
        val split = splitTender(total, cashGiven)
        var cashTxId: Long? = null
        var cardTxId: Long? = null
        var cardStatus = "CAPTURED"

        if (split.cash > 0.0) {
            cashTxId = db.paymentsE22().insertTx(
                PaymentTxEntity(
                    invoiceId = invoiceId, amountPiasters = Money.toPiasters(split.cash),
                    method = "CASH", status = "CAPTURED", psp = null, authRef = null, createdAt = now,
                )
            )
        }
        if (split.card > 0.0) {
            when (cardAuthPolicy(online)) {
                is AuthDecision.DeclineOffline -> {
                    // الرفض الإقراري الموثَّق: صف يشرح ما حدث — لا بوابة ولا تحصيل مؤجَّل
                    cardTxId = db.paymentsE22().insertTx(
                        PaymentTxEntity(
                            invoiceId = invoiceId, amountPiasters = Money.toPiasters(split.card),
                            method = "CARD", status = "DECLINED_OFFLINE", psp = gateway?.pspId,
                            authRef = null, createdAt = now,
                        )
                    )
                    cardStatus = "DECLINED_OFFLINE"
                }
                is AuthDecision.Approve -> {
                    val gw = gateway
                        ?: throw IllegalArgumentException("بوابة البطاقة مطلوبة لحصة بطاقة فوق صفر")
                    cardStatus = try {
                        val res = gw.charge("card|$invoiceId|$now", split.card)
                        if (res.approved) {
                            cardTxId = db.paymentsE22().insertTx(
                                PaymentTxEntity(
                                    invoiceId = invoiceId, amountPiasters = Money.toPiasters(split.card),
                                    method = "CARD", status = "CAPTURED", psp = gw.pspId,
                                    authRef = res.authRef.ifEmpty { null }, createdAt = now,
                                )
                            )
                            "CAPTURED"
                        } else {
                            cardTxId = db.paymentsE22().insertTx(
                                PaymentTxEntity(
                                    invoiceId = invoiceId, amountPiasters = Money.toPiasters(split.card),
                                    method = "CARD", status = "FAILED", psp = gw.pspId,
                                    authRef = null, createdAt = now,
                                )
                            )
                            "FAILED"
                        }
                    } catch (t: Throwable) {
                        // الصدق الإقراري: فشل البوابة يُسجَّل ولا يُبتلع — والبيع النقدي ينجو
                        cardTxId = db.paymentsE22().insertTx(
                            PaymentTxEntity(
                                invoiceId = invoiceId, amountPiasters = Money.toPiasters(split.card),
                                method = "CARD", status = "FAILED", psp = gw.pspId,
                                authRef = null, createdAt = now,
                            )
                        )
                        "FAILED"
                    }
                }
            }
        }
        SplitOutcome(split, cashTxId, cardTxId, cardStatus)
    }

    /**
     * استرداد at-most-once: المفتاح الواحد يضرب بوابة المسترد مرة واحدة بعمر القاعدة.
     * ترتيب الحُجُب: (1) المفتاح مرئي في الدفتر → النتيجة الأولى نفسها repeated=true
     * بلا بوابة؛ (2) سقف الاسترداد — مجموع المسترد لا يتجاوز المحصَّل الأصلي، والهللة
     * الزائدة تُرفض باسم؛ (3) بوابة المسترد ثم إدراج الدفتر — وتسابق الجهازين على
     * المفتاح نفسه يحسمه الفهرس الفريد (SQLiteConstraintException → النتيجة الفائزة
     * تُعاد repeated=true بلا استدعاء ثانٍ).
     */
    suspend fun refund(
        idempotencyKey: String,
        paymentTxId: Long?,
        amount: Double,
        gateway: PspGateway,
        now: Long,
    ): RefundResult {
        require(amount > 0.0) { "amount > 0" }
        val dao = db.paymentsE22()

        // (1) المفتاه مرئية مسبقاً — لا بوابة، النتيجة الأولى نفسها
        dao.refundByKey(idempotencyKey)?.let {
            return RefundResult(
                refundId = it.refundRef,
                amount = Money.fromPiasters(it.amountPiasters),
                repeated = true,
            )
        }

        // (2) سقف الاسترداد على المعاملة المرتبطة
        val tx = paymentTxId?.let { dao.txById(it) }
        if (paymentTxId != null && tx == null) {
            throw IllegalArgumentException("معاملة الدفع غير موجودة: $paymentTxId")
        }
        tx?.let {
            val cap = it.amountPiasters - dao.refundedSumPiasters(it.id)
            if (Money.toPiasters(amount) > cap) {
                throw IllegalArgumentException(
                    "سقف الاسترداد: المطلوب ${Money.toPiasters(amount)} هللة فوق المتاح $cap هللة"
                )
            }
        }

        // (3) بوابة المسترد مرة واحدة — الدفتر صمّام الأمان على التسابق
        val res = gateway.refund(idempotencyKey, amount, authRef = tx?.authRef ?: "")
        try {
            dao.insertRefund(
                RefundLedgerEntity(
                    idempotencyKey = idempotencyKey, paymentTxId = paymentTxId,
                    amountPiasters = Money.toPiasters(amount), refundRef = res.refundId, createdAt = now,
                )
            )
            return res
        } catch (e: SQLiteConstraintException) {
            val winner = dao.refundByKey(idempotencyKey)
                ?: throw IllegalStateException("قيود فريدة رمت بلا صف فائز: $idempotencyKey", e)
            return RefundResult(
                refundId = winner.refundRef,
                amount = Money.fromPiasters(winner.amountPiasters),
                repeated = true,
            )
        }
    }
}
