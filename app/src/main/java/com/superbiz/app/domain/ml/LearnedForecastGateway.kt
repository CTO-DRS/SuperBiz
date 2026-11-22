package com.superbiz.app.domain.ml

/**
 * [F3 - ADR-005 D6] خط التعلّم خلف البوابة — المنافس الافتراضي في المبارزة
 * التنبؤية: يغلّف DampedTrendLearner بعقد ForecastGateway نفسه الذي سيغلف
 * لاحقًا أي جلسة LiteRT (F3.1) — فتتبادل الخطوط في المبارزة بلا تغيير فيها.
 *
 * العقد (كل بند مختبر صراحة):
 * - الأفق خارج 1..[ForecastGateway.MAX_HORIZON] ⇒ رفض نهائي [REASON_HORIZON_INVALID]
 *   — لا ذاكرة مفتوحة النهاية مهما مرّر المستدعي.
 * - تاريخ أقل من [DampedTrendLearner.MIN_HISTORY] ⇒ رفض نهائي [REASON_WINDOW_SHORT].
 * - أي قيمة غير منتهية في النافذة ⇒ رفض نهائي [REASON_WINDOW_NOT_FINITE].
 * - التدريب ينفذ هنا متزامنًا على الجهاز حصرًا — بلا شبكة ولا خلفية ولا suspend.
 * - نفس النافذة ⇒ نفس المخرج (حتمية المتعلم وراءها).
 */
class LearnedForecastGateway : ForecastGateway {

    override fun forecast(input: ForecastGateway.ForecastInput): ForecastGateway.GatewayResult {
        if (input.horizon !in 1..ForecastGateway.MAX_HORIZON) {
            return ForecastGateway.GatewayResult.Rejected(ForecastGateway.REASON_HORIZON_INVALID)
        }
        if (input.window.size < DampedTrendLearner.MIN_HISTORY) {
            return ForecastGateway.GatewayResult.Rejected(ForecastGateway.REASON_WINDOW_SHORT)
        }
        if (!input.window.all { it.isFinite() }) {
            return ForecastGateway.GatewayResult.Rejected(ForecastGateway.REASON_WINDOW_NOT_FINITE)
        }
        val model = DampedTrendLearner.train(input.window)
            ?: return ForecastGateway.GatewayResult.Rejected(ForecastGateway.REASON_WINDOW_SHORT)
        val horizon = DampedTrendLearner.forecast(model, input.horizon)
            ?: return ForecastGateway.GatewayResult.TransientFailure(
                "learned model produced a non-finite state"
            )
        return ForecastGateway.GatewayResult.Accepted(ForecastGateway.ForecastOutput(horizon))
    }
}
