package com.superbiz.app

import com.superbiz.app.domain.algo.AuthDecision
import com.superbiz.app.domain.algo.OFFLINE_QUEUE_AUTO_CHARGE
import com.superbiz.app.domain.algo.PspCandidate
import com.superbiz.app.domain.algo.RefundIdempotencyLedger
import com.superbiz.app.domain.algo.RefundResult
import com.superbiz.app.domain.algo.cardAuthPolicy
import com.superbiz.app.domain.algo.moneyEquals
import com.superbiz.app.domain.algo.pspSelectionMatrix
import com.superbiz.app.domain.algo.splitTender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * — اختبارات نواة المدفوعات (E2.2): قرشاً بقرش، والعقد قبل أي بوابة حقيقية.
 *
 * RED-first موثق: الحزمة كُتبت أولاً على هيكل أعور (عقود خاطئة عمداً) وفشلت
 * اختباراتها الأربعة كلها قبل التنفيذ الحقيقي — لا اختبار هنا ولّد بعد الأخضر.
 */
class PaymentsE22Test {

    @Test
    fun `split tender is halala-exact and card never negative`() {
        // سيناريو الخلاط الحقيقي: إجمالي 152.50 — 100 نقداً والباقي بطاقة
        val s = splitTender(total = 152.50, cashGiven = 100.0)
        assertTrue(moneyEquals(s.cash, 100.0))
        assertTrue(moneyEquals(s.card, 52.50))
        assertTrue(moneyEquals(s.cash + s.card, 152.50))

        // قيم غير تمثيلية بثنائية الفاصلة يبقى مجموعها مطابقاً ضمن نصف هللة
        val odd = splitTender(total = 33.33, cashGiven = 10.0)
        assertTrue(moneyEquals(odd.cash, 10.0))
        assertTrue(moneyEquals(odd.cash + odd.card, 33.33))

        // النقد يغطي الكل → حصة البطاقة صفر (البقشش يُعالج خارج هذا العقد)
        val full = splitTender(total = 88.0, cashGiven = 150.0)
        assertTrue(moneyEquals(full.cash, 88.0))
        assertTrue(moneyEquals(full.card, 0.0))

        // نقد صفري = بطاقة كاملة
        val cardOnly = splitTender(total = 152.50, cashGiven = 0.0)
        assertTrue(moneyEquals(cardOnly.cash, 0.0))
        assertTrue(moneyEquals(cardOnly.card, 152.50))
    }

    @Test
    fun `refund idempotency — same key charges the PSP exactly once`() {
        var calls = 0
        val ledger = RefundIdempotencyLedger()

        val r1 = ledger.execute("ord-7|refund|1", 52.50) { key, amount ->
            calls++
            RefundResult(refundId = "rf-$key-1", amount = amount, repeated = false)
        }
        val r2 = ledger.execute("ord-7|refund|1", 52.50) { key, amount ->
            calls++
            RefundResult(refundId = "rf-$key-2", amount = amount, repeated = false)
        }

        // البوابة استُدعيت مرة واحدة بعمر الدفتر — التكرار أعاد النتيجة الأولى نفسها
        assertEquals(1, calls)
        assertFalse(r1.repeated)
        assertTrue(r2.repeated)
        assertEquals(r1.refundId, r2.refundId)
        assertTrue(moneyEquals(r1.amount, r2.amount))

        // مفتاح مختلف = عملية جديدة كاملة تخصم فعلاً
        val r3 = ledger.execute("ord-7|refund|2", 10.0) { key, amount ->
            calls++
            RefundResult(refundId = "rf-$key-3", amount = amount, repeated = false)
        }
        assertEquals(2, calls)
        assertFalse(r3.repeated)
        assertEquals("rf-ord-7|refund|2-3", r3.refundId)
    }

    @Test
    fun `card authorization requires connectivity — offline declined by policy`() {
        assertTrue(cardAuthPolicy(online = true) is AuthDecision.Approve)
        assertTrue(cardAuthPolicy(online = false) is AuthDecision.DeclineOffline)

        // سياسة الصف: قائمة الانتظار بلا اتصال لا تُحصّل بطاقة تلقائياً — إطلاقاً
        assertFalse(OFFLINE_QUEUE_AUTO_CHARGE)
    }

    @Test
    fun `psp selection matrix — three candidates scored on six auditable dimensions`() {
        val matrix = pspSelectionMatrix()
        assertEquals(3, matrix.size)
        assertEquals(
            setOf(PspCandidate.MOYASAR, PspCandidate.HYPERPAY, PspCandidate.TAP),
            matrix.map { it.psp }.toSet(),
        )

        matrix.forEach { score ->
            val dims = listOf(
                score.madaCoverage, score.applePay, score.stcPay,
                score.sdkQuality, score.settlementTerms, score.sandboxFidelity,
            )
            dims.forEach { assertTrue("each dimension in 1..5: $score", it in 1..5) }
            assertEquals("total is the exact sum: $score", dims.sum(), score.total)
        }

        // مرتبة تنازلياً — القرار قابل للتدقيق لا وجداني
        assertTrue(
            "matrix sorted by total desc",
            matrix.zipWithNext().all { (a, b) -> a.total >= b.total },
        )
    }
}
