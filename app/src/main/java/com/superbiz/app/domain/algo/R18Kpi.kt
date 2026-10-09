package com.superbiz.app.domain.algo

import kotlin.math.max
import kotlin.math.min

/**
 * [H4-7][V 3.0.0] — بطاقة المؤشرات الكاملة: نبض العمل مركّباً من ستة مكوّنات
 * حتمية (0..100 لكل مكوّن) بمتوسط مرجّح معلن — بلا أي شبكة ولا AI خارجي
 * (عقد «لا AI/ML خارجي» في ROADMAP).
 *
 * العقود:
 * - كل مكوّن بلا أساس كافٍ يعود null ويُستبعد من المتوسط (لا نسبة على صفر —
 *   نفس انضباط NarrativeMath R16) — والنبض بلا أي مكوّن = null (لا نبض من لا شيء).
 * - الأوزان معلنة في [WEIGHTS] وحاصل الجمع = 100 حرفياً (عقد قابل للاختبار).
 * - الحكم النصي (verdict) مفهرس بأربع حالات ثابتة تُوطَّن في الواجهة.
 */
object R18Kpi {

    data class Input(
        val salesMonthPiasters: Long,
        val profitMonthPiasters: Long,
        val expensesMonthPiasters: Long,
        val overduePiasters: Long,
        val lowStockCount: Int,
        val totalProducts: Int,
        val cash90NetPiasters: Long?,   // من CashFlow90Math R16 — null = لا أساس للتنبؤ
        val dayOfMonth: Int,
        val daysInMonth: Int
    )

    data class Component(
        val key: String,        // مفتاح التوطين: pulse_comp_sales/profit/expenses/overdue/stock/cash
        val score: Int,         // 0..100
        val rawPiasters: Long   // القيمة الخام للعرض (للمكوّنات المالية)
    )

    data class Pulse(
        val score: Int,             // 0..100 — المتوسط المرجّح للمكوّنات المتاحة
        val components: List<Component>,
        val verdict: Int            // 0 ممتاز / 1 مستقر / 2 يحتاج انتباه / 3 خطر
    )

    /** الأوزان المعلنة — مجموعها 100 حرفياً (يُفرض اختباراً). */
    val WEIGHTS: Map<String, Int> = mapOf(
        "pulse_comp_sales" to 25,
        "pulse_comp_profit" to 25,
        "pulse_comp_expenses" to 15,
        "pulse_comp_overdue" to 15,
        "pulse_comp_stock" to 10,
        "pulse_comp_cash" to 10
    )

    private const val DAY_MS_CAP = 100

    /** كسر الشهر المنقضي — حارس صفر (آخر اليوم = اكتمال). */
    fun monthFraction(dayOfMonth: Int, daysInMonth: Int): Double {
        if (daysInMonth <= 0) return 1.0
        return min(1.0, max(0.0, dayOfMonth.toDouble() / daysInMonth))
    }

    /**
     * حساب النبض — حتمي بالكامل. المكوّنات:
     * 1) المبيعات: الوتيرة الشهرية مقابل كسر الشهر (نسبة الإنجاز المفترضة).
     * 2) الربح: هامش الربح 0..40%+ يُطبع إلى 0..100.
     * 3) المصروفات: نسبة المصروف من المبيعات — 0%=100، ≥50%=0.
     * 4) المتأخرات: نسبة المتأخر من المبيعات — 0%=100، ≥30%=0.
     * 5) المخزون: نسبة الأصناف سليمة 1 - lowStock/total.
     * 6) النقد 90: إيجابي ≥ سقف، سلبي ≤ صفر — خطي بينهما.
     */
    fun pulse(input: Input): Pulse? {
        val components = ArrayList<Component>(6)
        val frac = monthFraction(input.dayOfMonth, input.daysInMonth)

        if (input.salesMonthPiasters > 0 && frac > 0.0) {
            val pace = input.salesMonthPiasters / frac
            // الوتيرة الشهرية المسقفة — لا هدف مخزّن في المشروع فالمرجع ثابت:
            // وتيرة 60 ألف ريال/شهر = 100
            val s = clamp(pace / 60_000_000.0 * 100.0)
            components.add(Component("pulse_comp_sales", s.toInt(), input.salesMonthPiasters))
        }

        if (input.salesMonthPiasters > 0) {
            val margin = input.profitMonthPiasters.toDouble() / input.salesMonthPiasters
            components.add(Component("pulse_comp_profit", clamp(margin / 0.40 * 100.0).toInt(), input.profitMonthPiasters))
        }

        if (input.salesMonthPiasters > 0) {
            val ratio = input.expensesMonthPiasters.toDouble() / input.salesMonthPiasters
            components.add(Component("pulse_comp_expenses", clamp((1.0 - ratio / 0.50) * 100.0).toInt(), input.expensesMonthPiasters))
        }

        if (input.salesMonthPiasters > 0) {
            val ratio = input.overduePiasters.toDouble() / input.salesMonthPiasters
            components.add(Component("pulse_comp_overdue", clamp((1.0 - ratio / 0.30) * 100.0).toInt(), input.overduePiasters))
        }

        if (input.totalProducts > 0) {
            val healthy = 1.0 - input.lowStockCount.toDouble() / input.totalProducts
            components.add(Component("pulse_comp_stock", clamp(healthy * 100.0).toInt(), input.lowStockCount.toLong()))
        }

        if (input.cash90NetPiasters != null) {
            val s = clamp((input.cash90NetPiasters + 50_000_000L) / 200_000_000.0 * 100.0)
            components.add(Component("pulse_comp_cash", s.toInt(), input.cash90NetPiasters))
        }

        if (components.isEmpty()) return null

        var weighted = 0.0
        var weightSum = 0
        for (c in components) {
            val w = WEIGHTS[c.key] ?: 0
            weighted += c.score * w
            weightSum += w
        }
        val score = (weighted / max(1, weightSum)).toInt().coerceIn(0, 100)
        val verdict = when {
            score >= 75 -> 0
            score >= 55 -> 1
            score >= 35 -> 2
            else -> 3
        }
        return Pulse(score, components, verdict)
    }

    private fun clamp(v: Double): Double = min(100.0, max(0.0, v))
}
