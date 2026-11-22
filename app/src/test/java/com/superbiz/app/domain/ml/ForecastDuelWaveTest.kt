package com.superbiz.app.domain.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [F3 - ADR-005 D6] اختبارات الموجة الثالثة من المسار F «التنبؤ المزدوج على الجهاز».
 *
 * العقد المختبر هنا حرفيًا:
 * - بوابة ForecastGateway النقية: ثوابت حد الحجم وأشكال الإدخال/الإخراج ودلالتها.
 * - الخط الإحصائي القائم (StatBaseline): متوسط آخر نافذة — نفس بداية CashFlow90Math —
 *   والفراغ الصادق حين النافذة غير مكتملة أو فيها غير منتهٍ.
 * - متعلم التنعيم المُخمَّد (DampedTrendLearner): بحث شبكي حتمي (α, β, φ) يقلّل SSE
 *   أحادي الخطوة، والتنعيم يثني النمو، والفراغ الصادق تحت حد التاريخ.
 * - خط التعلّم خلف البوابة (LearnedForecastGateway): قبول بأفق صحيح ورفض نهائي بالسبب.
 * - المبارزة التنبؤية (ForecastDuel): مقارنة دقة متجذرة (rolling-origin) بـMAE،
 *   فائز وتحسن ومزج عكسي الخطأ، وتبدّل صادق حين خط التعلّم غير متاح.
 * - كلها نقية JVM صرف — بلا أندرويد ولا شبكة ولا وقت نظام.
 */
class ForecastDuelWaveTest {

    // ── عوانس البناء ──

    private fun flat(v: Double, n: Int) = List(n) { v }

    private fun linear(step: Double, n: Int, start: Double = 0.0) =
        List(n) { start + step * it }

    /** بوابة اختبارية: يقبل دائمًا بأفق ثابت القيمة */
    private class ConstantGateway(private val value: Double) : ForecastGateway {
        override fun forecast(input: ForecastGateway.ForecastInput): ForecastGateway.GatewayResult =
            ForecastGateway.GatewayResult.Accepted(
                ForecastGateway.ForecastOutput(List(input.horizon) { value })
            )
    }

    /** بوابة اختبارية: يرفض نهائيًا دائمًا */
    private class RejectGateway : ForecastGateway {
        override fun forecast(input: ForecastGateway.ForecastInput): ForecastGateway.GatewayResult =
            ForecastGateway.GatewayResult.Rejected(ForecastGateway.REASON_MODEL_MISSING)
    }

    /** بوابة اختبارية: فشل عابر دائمًا */
    private class TransientGateway : ForecastGateway {
        override fun forecast(input: ForecastGateway.ForecastInput): ForecastGateway.GatewayResult =
            ForecastGateway.GatewayResult.TransientFailure("busy")
    }

    // ═══════════ 1) عقد البوابة النقي ═══════════

    @Test
    fun `model size cap is two megabytes`() {
        assertEquals(2_000_000L, ForecastGateway.MAX_MODEL_BYTES)
    }

    @Test
    fun `io shapes are documented constants`() {
        assertEquals(56, ForecastGateway.INPUT_WINDOW)
        assertEquals(8, ForecastGateway.DEFAULT_HORIZON)
        assertEquals(24, ForecastGateway.MAX_HORIZON)
        assertTrue(ForecastGateway.MODEL_FILE.endsWith(".tflite"))
    }

    @Test
    fun `rejected reasons are stable strings`() {
        assertEquals("MODEL_MISSING", ForecastGateway.REASON_MODEL_MISSING)
        assertEquals("MODEL_TOO_LARGE", ForecastGateway.REASON_MODEL_TOO_LARGE)
        assertEquals("MODEL_CORRUPT", ForecastGateway.REASON_MODEL_CORRUPT)
        assertEquals("WINDOW_SHORT", ForecastGateway.REASON_WINDOW_SHORT)
        assertEquals("WINDOW_NOT_FINITE", ForecastGateway.REASON_WINDOW_NOT_FINITE)
        assertEquals("HORIZON_INVALID", ForecastGateway.REASON_HORIZON_INVALID)
    }

    @Test
    fun `input carries window and horizon verbatim`() {
        val input = ForecastGateway.ForecastInput(flat(1.0, 56), 8)
        assertEquals(56, input.window.size)
        assertEquals(8, input.horizon)
    }

    // ═══════════ 2) الخط الإحصائي القائم ═══════════

    @Test
    fun `flat series yields flat horizon at window mean`() {
        val out = StatBaseline.forecast(flat(5.0, 20), 10, 3)!!
        assertEquals(listOf(5.0, 5.0, 5.0), out)
    }

    @Test
    fun `mean uses last window only not whole series`() {
        val series = flat(0.0, 20) + flat(10.0, 10)
        val out = StatBaseline.forecast(series, 10, 1)!!
        assertEquals(10.0, out[0], 1e-12)
    }

    @Test
    fun `null when series shorter than window`() {
        assertNull(StatBaseline.forecast(flat(1.0, 8), 10, 3))
    }

    @Test
    fun `null when window below minimum`() {
        assertNull(StatBaseline.forecast(flat(1.0, 20), StatBaseline.MIN_WINDOW - 1, 3))
    }

    @Test
    fun `null when non finite inside window and tolerant outside`() {
        val dirty = flat(1.0, 9) + listOf(Double.NaN)
        assertNull(StatBaseline.forecast(dirty, 10, 2))
        val early = listOf(Double.NaN) + flat(1.0, 30)
        assertNotNull(StatBaseline.forecast(early, 10, 2))
    }

    @Test
    fun `negative values preserved not clamped`() {
        val out = StatBaseline.forecast(flat(-3.0, 12), 8, 2)!!
        assertEquals(-3.0, out[0], 1e-12)
    }

    // ═══════════ 3) متعلم التنعيم المُخمَّد ═══════════

    @Test
    fun `null below minimum history`() {
        assertNull(DampedTrendLearner.train(flat(1.0, DampedTrendLearner.MIN_HISTORY - 1)))
    }

    @Test
    fun `null on non finite series`() {
        val dirty = flat(2.0, 13) + listOf(Double.POSITIVE_INFINITY)
        assertNull(DampedTrendLearner.train(dirty))
    }

    @Test
    fun `flat series levels at value with zero trend`() {
        val m = DampedTrendLearner.train(flat(5.0, 20))!!
        assertEquals(0.0, m.trend, 1e-9)
        val out = DampedTrendLearner.forecast(m, 4)!!
        out.forEach { assertEquals(5.0, it, 1e-9) }
    }

    @Test
    fun `linear growth extrapolates rising horizon`() {
        val m = DampedTrendLearner.train(linear(2.0, 28))!!
        val out = DampedTrendLearner.forecast(m, 4)!!
        assertTrue(out.zipWithNext().all { (a, b) -> b > a })
        assertTrue(out.last() > 54.0)
    }

    @Test
    fun `phi one gives equal increments and damped gives decreasing`() {
        val series = linear(2.0, 20)
        val undamped = DampedTrendLearner.fit(series, 1.0, 0.2, 1.0)!!
        val f1 = DampedTrendLearner.forecast(undamped.model, 3)!!
        assertEquals(f1[1] - f1[0], f1[2] - f1[1], 1e-9)
        val damped = DampedTrendLearner.fit(series, 1.0, 0.2, 0.5)!!
        val f2 = DampedTrendLearner.forecast(damped.model, 3)!!
        assertTrue(f2[2] - f2[1] < f2[1] - f2[0])
    }

    @Test
    fun `training is deterministic`() {
        val series = linear(1.5, 24)
        val a = DampedTrendLearner.train(series)!!
        val b = DampedTrendLearner.train(series)!!
        assertEquals(a, b)
    }

    @Test
    fun `tracking fit beats lagging fit on linear series`() {
        val series = linear(2.0, 20)
        val good = DampedTrendLearner.fit(series, 1.0, 0.3, 1.0)!!
        val bad = DampedTrendLearner.fit(series, 0.2, 0.0, 0.85)!!
        assertTrue(good.sse < bad.sse)
    }

    @Test
    fun `fit rejects invalid params and short series`() {
        assertNull(DampedTrendLearner.fit(flat(1.0, 2), 1.5, 0.2, 1.0))
        assertNull(DampedTrendLearner.fit(flat(1.0, 2), 0.5, 0.2, 0.0))
        assertNull(DampedTrendLearner.fit(flat(1.0, 1), 0.5, 0.2, 1.0))
    }

    // ═══════════ 4) خط التعلّم خلف البوابة ═══════════

    @Test
    fun `accepts valid window and returns horizon size`() {
        val g = LearnedForecastGateway()
        val r = g.forecast(ForecastGateway.ForecastInput(linear(1.0, 20), 8))
        assertTrue(r is ForecastGateway.GatewayResult.Accepted)
        assertEquals(8, (r as ForecastGateway.GatewayResult.Accepted).output.horizon.size)
    }

    @Test
    fun `rejects short window with stable reason`() {
        val g = LearnedForecastGateway()
        val r = g.forecast(ForecastGateway.ForecastInput(flat(1.0, 13), 8))
        assertTrue(r is ForecastGateway.GatewayResult.Rejected)
        assertEquals(ForecastGateway.REASON_WINDOW_SHORT, (r as ForecastGateway.GatewayResult.Rejected).reason)
    }

    @Test
    fun `rejects non finite window with stable reason`() {
        val g = LearnedForecastGateway()
        val r = g.forecast(ForecastGateway.ForecastInput(flat(1.0, 13) + listOf(Double.NaN), 8))
        assertTrue(r is ForecastGateway.GatewayResult.Rejected)
        assertEquals(ForecastGateway.REASON_WINDOW_NOT_FINITE, (r as ForecastGateway.GatewayResult.Rejected).reason)
    }

    @Test
    fun `rejects horizon out of range`() {
        val g = LearnedForecastGateway()
        val zero = g.forecast(ForecastGateway.ForecastInput(linear(1.0, 20), 0))
        val tooFar = g.forecast(ForecastGateway.ForecastInput(linear(1.0, 20), ForecastGateway.MAX_HORIZON + 1))
        assertEquals(
            ForecastGateway.REASON_HORIZON_INVALID,
            (zero as ForecastGateway.GatewayResult.Rejected).reason
        )
        assertEquals(
            ForecastGateway.REASON_HORIZON_INVALID,
            (tooFar as ForecastGateway.GatewayResult.Rejected).reason
        )
    }

    @Test
    fun `output is deterministic`() {
        val g = LearnedForecastGateway()
        val input = ForecastGateway.ForecastInput(linear(2.0, 24), 5)
        val a = (g.forecast(input) as ForecastGateway.GatewayResult.Accepted).output.horizon
        val b = (g.forecast(input) as ForecastGateway.GatewayResult.Accepted).output.horizon
        assertEquals(a, b)
    }

    // ═══════════ 5) المبارزة التنبؤية ═══════════

    @Test
    fun `null when series cannot host minimum cuts`() {
        val r = ForecastDuel.duel(flat(1.0, 17), LearnedForecastGateway(), horizon = 2, minTrain = 14)
        assertNull(r)
    }

    @Test
    fun `null when min train below baseline window`() {
        val r = ForecastDuel.duel(flat(1.0, 40), LearnedForecastGateway(), horizon = 2, minTrain = StatBaseline.MIN_WINDOW - 1)
        assertNull(r)
    }

    @Test
    fun `null on non finite series anywhere`() {
        val dirty = flat(1.0, 39) + listOf(Double.NaN)
        assertNull(ForecastDuel.duel(dirty, LearnedForecastGateway(), horizon = 2, minTrain = 14))
    }

    @Test
    fun `null on invalid horizon or cuts`() {
        val s = flat(1.0, 40)
        assertNull(ForecastDuel.duel(s, LearnedForecastGateway(), horizon = 0, minTrain = 14))
        assertNull(ForecastDuel.duel(s, LearnedForecastGateway(), horizon = 2, minTrain = 14, maxCuts = 0))
    }

    @Test
    fun `flat series ties with zero improvement and neutral blend`() {
        val r = ForecastDuel.duel(flat(7.0, 30), LearnedForecastGateway(), horizon = 2, minTrain = 14, maxCuts = 4)!!
        assertEquals(4, r.cuts)
        assertEquals(0.0, r.baselineMae, 1e-9)
        assertNotNull(r.learnedMae)
        assertEquals(0.0, r.learnedMae!!, 1e-9)
        assertEquals(ForecastDuel.WINNER_TIE, r.winner)
        assertEquals(0.0, r.improvementPct!!, 1e-12)
        assertEquals(0.5, r.blendWeightLearned!!, 1e-9)
        r.adopted.forEach { assertEquals(7.0, it, 1e-9) }
    }

    @Test
    fun `cut count is full when max cuts allows`() {
        // 30 − 2 − 14 + 1 = 15 نقطة قص متاحة
        val r = ForecastDuel.duel(flat(1.0, 30), LearnedForecastGateway(), horizon = 2, minTrain = 14, maxCuts = 99)!!
        assertEquals(15, r.cuts)
    }

    @Test
    fun `learned line wins decisively on trending series`() {
        val r = ForecastDuel.duel(linear(2.0, 40), LearnedForecastGateway(), horizon = 3, minTrain = 14, maxCuts = 6)!!
        assertEquals(ForecastDuel.WINNER_LEARNED, r.winner)
        assertTrue(r.improvementPct!! > 50.0)
    }

    @Test
    fun `rejected gateway degrades honestly to statistical line`() {
        val series = flat(7.0, 30)
        val r = ForecastDuel.duel(series, RejectGateway(), horizon = 2, minTrain = 14, maxCuts = 4)!!
        assertNull(r.learnedMae)
        assertNull(r.improvementPct)
        assertNull(r.blendWeightLearned)
        assertEquals(ForecastDuel.WINNER_NONE, r.winner)
        // الخط المعتمد = خط إحصائي من آخر نقطة قص: متوسط آخر 14 قيمة من البادئة
        val prefix = series.subList(0, series.size - 2)
        val expected = List(2) { prefix.takeLast(14).sum() / 14 }
        assertEquals(expected, r.adopted)
    }

    @Test
    fun `transient failure degrades same as rejection`() {
        val r = ForecastDuel.duel(flat(7.0, 30), TransientGateway(), horizon = 2, minTrain = 14, maxCuts = 4)!!
        assertNull(r.learnedMae)
        assertEquals(ForecastDuel.WINNER_NONE, r.winner)
    }

    @Test
    fun `blend weight follows inverse error formula and adopted blends verbatim`() {
        // [1.0]×16 ثم [3.0]×14 — الخط القائم يتأخر عن القفزة وخط الـstub ثابت 10.0
        val series = flat(1.0, 16) + flat(3.0, 14)
        val r = ForecastDuel.duel(series, ConstantGateway(10.0), horizon = 2, minTrain = 14, maxCuts = 99)!!
        assertNotNull(r.learnedMae)
        assertTrue(r.baselineMae > 0.0 && r.learnedMae!! > 0.0)
        val expectedW = r.baselineMae / (r.baselineMae + r.learnedMae!!)
        assertEquals(expectedW, r.blendWeightLearned!!, 1e-12)
        // الخط المعتمد من آخر نقطة قص: متوسط آخر 14 قيمة من البادئة (28 قيمة) = 38/14
        val baseFinal = (2 * 1.0 + 12 * 3.0) / 14
        val w = r.blendWeightLearned!!
        r.adopted.forEach { assertEquals(w * 10.0 + (1 - w) * baseFinal, it, 1e-9) }
    }
}
