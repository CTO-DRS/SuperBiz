package com.superbiz.app.domain.ml

/**
 * [F3 - ADR-005 D6 — بند القبول الأول] المبارزة التنبؤية: مقارنة دقة متجذرة
 * (rolling-origin backtest) بين الخط الإحصائي القائم (StatBaseline — بداية
 * CashFlow90Math نفسها) وأي خط تعلّم خلف ForecastGateway — فوق محركات R7–R18
 * بلا لمسها: السلاسل التي تُبنى في الـVM من نفس أحداث R16 هي ساحة المبارزة.
 *
 * العقد (كل بند مختبر صراحة):
 * - **متجذرة حقيقية**: كل نقطة قص تُدرّب/تتنبأ بما قبلها فقط وتُسجل أخطاؤها
 *   على القيم الفعلية بعدها — لا تسريب مستقبلي إطلاقًا.
 * - **نقاط القص الأحدث أولًا**: آخر [maxCuts] نقاط صالحة (end من minTrain حتى
 *   size−horizon)؛ العدد الكامل حين يسمح maxCuts.
 * - **MAE واحدة للساحة**: متوسط القيم المطلقة للأخطاء على كل نقاط القص × الأفق.
 * - **الفائز بالفارق الصارم**: |فرق| ≤ [TIE_EPS] ⇒ تعادل؛ وإلا الأصغر فوزًا.
 * - **التحسن النسبي**: (B−L)/B×100 — موجب = خط التعلّم أفضل؛ B صفرية ⇒ 0.0
 *   (الخط القائم مثالي — لا مجال تحسن ولا قسمة على صفر).
 * - **المزج عكسي الخطأ**: w = B/(B+L) لخط التعلّم (كلاهما صفر ⇒ 0.5 محايد) —
 *   والخط المعتمد من آخر نقطة قص: (1−w)·إحصائي + w·تعلّم لكل خطوة أفق.
 * - **التبدّل الصادق**: رفض/فشل البوابة في أي نقطة قص (أو رد مشوه بطول غير
 *   مطابق) ⇒ خط التعلّم لا يُقارن في هذه المبارزة إطلاقًا: learnedMae=null،
 *   winner=[WINNER_NONE]، لا مزج — والمعتمد هو الخط الإحصائي من آخر نقطة قص
 *   (لا أرقام تعلّم مختلقة ولا مقارنة جزئية غير عادلة).
 * - **الفراغ الصادق**: سلسلة غير كافية لأقل من [MIN_CUTS] نقاط، أو minTrain
 *   أقل من نافذة الخط الإحصائي، أو أي قيمة غير منتهية، أو وسيطات غير سليمة ⇒ null.
 * - نقي JVM صرف — بلا أندرويد ولا شبكة ولا وقت نظام.
 */
object ForecastDuel {

    /** أدنى عدد نقاط قص للمقارنة — دونه لا إحصاء موثوق */
    const val MIN_CUTS = 3

    /** نافذة الخط الإحصائي في المبارزة — نصف نافذة R56 المتعارفة، حادة على القفزات */
    const val BASELINE_WINDOW = 14

    const val WINNER_BASELINE = 0
    const val WINNER_LEARNED = 1
    const val WINNER_TIE = 2
    const val WINNER_NONE = -1

    /** حد التعادل المطلق — دونه تظل المقارنة ترفع فائزًا من ضجيج الفاصلة العائمة */
    const val TIE_EPS = 1e-9

    /** تقرير المبارزة — كل الحقول مفسرة في التوثيق أعلاه حرفيًا */
    data class DuelReport(
        val cuts: Int,                    // نقاط القص المُسجَّلة فعليًا
        val baselineMae: Double,          // دقة الخط الإحصائي القائم
        val learnedMae: Double?,          // دقة خط التعلّم — null إن لم يُقيَّم
        val winner: Int,                  // 0 إحصائي / 1 تعلّم / 2 تعادل / -1 لا مقارنة
        val improvementPct: Double?,      // موجب = التعلّم أفضل — null بلا مقارنة
        val blendWeightLearned: Double?,  // وزن خط التعلّم في المزج — null بلا مزج
        val adopted: List<Double>         // الخط المعتمد بعد المبارزة من آخر نقطة قص
    )

    /**
     * إجراء المبارزة كاملة على سلسلة واحدة — تعيد تقريرها الصادق أو null.
     * البوابة تُستدعى مرة لكل نقطة قص بنافذة البادئة والأفق المطلوب.
     */
    fun duel(
        series: List<Double>,
        gateway: ForecastGateway,
        horizon: Int = ForecastGateway.DEFAULT_HORIZON,
        minTrain: Int = 28,
        maxCuts: Int = 6,
        baselineWindow: Int = BASELINE_WINDOW
    ): DuelReport? {
        if (horizon < 1 || horizon > ForecastGateway.MAX_HORIZON) return null
        if (maxCuts < 1) return null
        if (baselineWindow < StatBaseline.MIN_WINDOW) return null
        if (minTrain < baselineWindow) return null
        if (series.any { !it.isFinite() }) return null
        val totalCuts = series.size - horizon - minTrain + 1
        if (totalCuts < MIN_CUTS) return null
        val cuts = minOf(maxCuts, totalCuts)
        val firstEnd = series.size - horizon - cuts + 1

        var baselineAbs = 0.0
        var learnedAbs = 0.0
        var samples = 0
        var learnedSamples = 0
        var learnedUnavailable = false
        var lastBaseline: List<Double>? = null
        var lastLearned: List<Double>? = null

        for (end in firstEnd..(series.size - horizon)) {
            val prefix = series.subList(0, end)
            val actuals = series.subList(end, end + horizon)
            val baseline = StatBaseline.forecast(prefix, baselineWindow, horizon)
                ?: return null
            var learned: List<Double>? = null
            if (!learnedUnavailable) {
                when (val r = gateway.forecast(ForecastGateway.ForecastInput(prefix, horizon))) {
                    is ForecastGateway.GatewayResult.Accepted ->
                        if (r.output.horizon.size == horizon) learned = r.output.horizon
                        else learnedUnavailable = true
                    else -> learnedUnavailable = true
                }
            }
            baselineAbs += actuals.zip(baseline).sumOf { (a, b) -> kotlin.math.abs(a - b) }
            lastBaseline = baseline
            if (learned != null) {
                learnedAbs += actuals.zip(learned).sumOf { (a, b) -> kotlin.math.abs(a - b) }
                lastLearned = learned
                learnedSamples += horizon
            }
            samples += horizon
        }

        val baselineMae = baselineAbs / samples
        val learnedMae: Double? =
            if (learnedUnavailable || learnedSamples == 0) null else learnedAbs / learnedSamples

        val winner: Int
        val improvementPct: Double?
        val blendWeight: Double?
        if (learnedMae == null) {
            winner = WINNER_NONE
            improvementPct = null
            blendWeight = null
        } else {
            winner = when {
                kotlin.math.abs(baselineMae - learnedMae) <= TIE_EPS -> WINNER_TIE
                learnedMae < baselineMae -> WINNER_LEARNED
                else -> WINNER_BASELINE
            }
            improvementPct =
                if (baselineMae <= 1e-12) 0.0 else (baselineMae - learnedMae) / baselineMae * 100.0
            blendWeight =
                if (baselineMae + learnedMae <= 1e-12) 0.5 else baselineMae / (baselineMae + learnedMae)
        }

        val adopted: List<Double> = if (learnedMae != null && blendWeight != null && lastLearned != null) {
            lastBaseline!!.zip(lastLearned).map { (b, l) -> (1 - blendWeight) * b + blendWeight * l }
        } else {
            lastBaseline!!
        }

        return DuelReport(cuts, baselineMae, learnedMae, winner, improvementPct, blendWeight, adopted)
    }
}
