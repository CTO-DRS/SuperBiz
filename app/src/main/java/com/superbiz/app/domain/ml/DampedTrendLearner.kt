package com.superbiz.app.domain.ml

/**
 * [F3 - ADR-005 D6] خط التعلّم على الجهاز — تنعيم الاتجاه المُخمَّد (Holt damped)
 * بتدريب حتمي داخل الجهاز: بحث شبكي (α, β, φ) يقلّل مجموع مربعات الأخطاء
 * أحادية الخطوة سيرًا على الشبكة (walk-forward) — تعلّم حقيقي من بيانات المالك
 * على الجهاز حصرًا: بلا شبكة، بلا نموذج خارجي، بلا اعتماديات، JVM صرف.
 *
 * العقد (كل بند مختبر صراحة):
 * - **حتمية تامة**: ترتيب الشبكة ثابت والتحسين صارم (< بفارق 1e-12) — نفس
 *   السلسلة ⇒ نفس النموذج بايتًا ببايت (اختبار التطابق مرتين).
 * - **الفراغ الصادق**: تاريخ أقل من [MIN_HISTORY] أو أي قيمة غير منتهية ⇒ null
 *   — لا أرقام مُختلقة من لا شيء.
 * - **التنعيم يعمل**: φ=1 نمو خطي بزيادات متساوية؛ φ<1 يثني الزيادات نزولًا
 *   — أفق يتسع بلا انفلات (فلسفة CashFlow90Math نفسها بمجالات ثقة).
 * - **fit معلن للفحص**: تركيب بمعاملات محددة (اختبارات السلوك البنيوي) —
 *   يرفض معاملات خارج 0..1 وφ=0 وسلاسل أقصر من قيمتين.
 * - القيم السالبة محفوظة — صافي التدفق يسمح بالسالب (عقد F3 العام).
 */
object DampedTrendLearner {

    /** أدنى تاريخ للتدريب — دونه لا تُقدَّر المعاملات بصدق */
    const val MIN_HISTORY = 14

    /** النموذج المدرَّب — معاملاته وحالته النهائية، كلها قيم مُتعلمة */
    data class Model(
        val alpha: Double,   // سلاسة المستوى
        val beta: Double,    // سلاسة الاتجاه
        val phi: Double,     // معامل التخميد (0,1]
        val level: Double,   // المستوى النهائي بعد آخر قيمة
        val trend: Double    // الاتجاه النهائي بعد آخر قيمة
    )

    /** تركيب بمعاملات محددة — النموذج مع خطأه التربيعي أحادي الخطوة */
    data class Fitted(val model: Model, val sse: Double)

    // شبكة البحث — الترتيب جزء من العقد الحتمي (أول أفضل يفوز بالتحسين الصارم)
    private val ALPHAS = listOf(0.2, 0.4, 0.6, 0.8, 1.0)
    private val BETAS = listOf(0.0, 0.1, 0.2, 0.3)
    private val PHIS = listOf(0.85, 0.95, 1.0)

    /** التدريب: أفضل تركيب في الشبكة، أو null على تاريخ غير كافٍ/ملوث */
    fun train(series: List<Double>): Model? {
        if (series.size < MIN_HISTORY) return null
        if (!series.all { it.isFinite() }) return null
        var best: Model? = null
        var bestSse = Double.POSITIVE_INFINITY
        for (a in ALPHAS) for (b in BETAS) for (p in PHIS) {
            val f = fit(series, a, b, p) ?: continue
            if (f.sse < bestSse - 1e-12) {
                bestSse = f.sse
                best = f.model
            }
        }
        return best
    }

    /**
     * تركيب بمعاملات محددة — سير على الشبكة: كل خطوة يتنبأ ثم يُصحح بالقيمة
     * الحقيقية، ومجموع مربعات الأخطاء هو جودة التركيب (كلها قبل أي تنبؤ مستقبلي).
     */
    fun fit(series: List<Double>, alpha: Double, beta: Double, phi: Double): Fitted? {
        if (series.size < 2) return null
        if (!series.all { it.isFinite() }) return null
        if (alpha !in 0.0..1.0 || beta !in 0.0..1.0) return null
        if (phi <= 0.0 || phi > 1.0) return null
        var level = series[0]
        var trend = series[1] - series[0]
        var sse = 0.0
        for (t in 2 until series.size) {
            val predicted = level + phi * trend
            val err = series[t] - predicted
            sse += err * err
            val newLevel = alpha * series[t] + (1 - alpha) * (level + phi * trend)
            trend = beta * (newLevel - level) + (1 - beta) * phi * trend
            level = newLevel
        }
        if (!sse.isFinite()) return null
        return Fitted(Model(alpha, beta, phi, level, trend), sse)
    }

    /**
     * أفق التنبؤ المُخمَّد: المستوى + تراكم تخميد الاتجاه (φ + φ² + … + φᵇ)·trend —
     * الخطوة الأولى مطابقة لاتفاقية التنبؤ أحادي الخطوة في [fit] حرفيًا.
     */
    fun forecast(model: Model, horizon: Int): List<Double>? {
        if (horizon < 1) return null
        if (!model.level.isFinite() || !model.trend.isFinite() ||
            !model.alpha.isFinite() || !model.beta.isFinite() || !model.phi.isFinite()
        ) return null
        val out = ArrayList<Double>(horizon)
        var cumulative = 0.0
        var damp = 1.0
        repeat(horizon) {
            damp *= model.phi
            cumulative += damp
            out += model.level + cumulative * model.trend
        }
        return out
    }
}
