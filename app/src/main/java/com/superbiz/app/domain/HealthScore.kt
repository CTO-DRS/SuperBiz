package com.superbiz.app.domain

import kotlin.math.roundToInt

/**
 * — درجة الصحة المالية (وظيفة 39) — بنّاء نقي خالص بلا أي اعتماديات.
 *
 * الدرجة 0..100 = 40% هامش الفترة + 30% أيام السيولة المتبقية + 30% كفاءة التحصيل
 * • marginRatio: هامش الفترة كنسبة 0..1 (الربح ÷ المبيعات) — يُقصّ إلى [0,1].
 * • runwayDays: أيام السيولة المتبقية بمقياس طبيعي 90 يوماً — يُقصّ إلى [0,90] ثم يُطبَّع.
 * القيمة السالبة (سلوك FinMath.runwayDays: -1 = لا حرق نقدي) تعني سيولة لا تستنزف
 * فيُمنح المكون درجة كاملة — وهذا موثّق لا تقدير زور.
 * • collectionEff: كفاءة التحصيل 0..100 (FinMath.collectionEfficiency) — تُقصّ إلى [0,100].
 *
 * صدق الفراغ: أي مدخل ناقص (null) أو غير سالم (NaN/Infinity) يُرجع null —
 * وتُخفى بطاقة الرئيسية كلياً حينئذٍ بدل عرض رقم مزيّف.
*/
object HealthScore {

    /** المقياس الطبيعي لأيام السيولة: 90 يوماً = الدرجة الكاملة للمكون */
    const val RUNWAY_NORM_DAYS = 90.0

    data class Inputs(
        val marginRatio: Double?,      // الربح ÷ المبيعات (0..1) أو null إن بلا أساس
        val runwayDays: Double?,       // أيام السيولة (سالب = لا حرق) أو null إن بلا أساس
        val collectionEff: Double?     // 0..100 أو null إن بلا أساس
    )

    /** الدرجة المركبة 0..100، أو null عند نقص/فساد أي مدخل */
    fun compute(i: Inputs): Int? {
        val m = i.marginRatio ?: return null
        val r = i.runwayDays ?: return null
        val c = i.collectionEff ?: return null
        if (!m.isFinite() || !r.isFinite() || !c.isFinite()) return null

        val marginNorm = m.coerceIn(0.0, 1.0) * 100.0
        val runwayNorm = if (r < 0.0) 100.0
        else (r / RUNWAY_NORM_DAYS).coerceIn(0.0, 1.0) * 100.0
        val collNorm = c.coerceIn(0.0, 100.0)

        val score = 0.40 * marginNorm + 0.30 * runwayNorm + 0.30 * collNorm
        return score.coerceIn(0.0, 100.0).roundToInt()
    }

    /** مستوى العرض 0..3: ≥80 ممتاز، ≥60 جيد، ≥40 مقبول، وإلا ضعيف */
    fun levelOf(score: Int): Int = when {
        score >= 80 -> 3
        score >= 60 -> 2
        score >= 40 -> 1
        else -> 0
    }
}
