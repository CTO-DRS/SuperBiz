package com.superbiz.app.domain

import kotlin.math.max
import kotlin.math.roundToLong

/**
 * [W1] محرك لوحة المؤشرات (KPIs) النقي — الموجة 1، البند 8:
 *
 * صندوق رياضيات «الفعلي مقابل الهدف» الوحيد — يُغذّى بأرقام حقيقية 100٪ من
 * الدفتر (مبيعات/ربح/مصروفات الشهر + الذمم المتأخرة) ولا يخترع رقماً قط.
 * بلا أي تبعية أندرويد — قابل للاختبار حرفاً-بحرف.
 *
 * العقود الحاسمة:
 * - الاتجاهان مدعومان: **أعلى-أفضل** (مبيعات/ربح) و**أقل-أفضل** (مصروفات/متأخرات
 *   كسقف لا يتجاوزه). بلا هدف (0) = بطاقة بلا تقدم تُعرض بحالة «بلا هدف»
 *   صادقة — لا نسبة وهمية 100٪.
 * - الإيقاع (pace): قياس الفعلي مقابل المتوقع عند نفس نسبة مضيّ الشهر —
 *   الشهر مقسوم بالأيام (يوماً-يوماً) بلا تحيّز لبداية الشهر.
 * - توقّع نهاية الشهر: خطي (الفعلي ÷ نسبة الشهر المضيّة) — توقّع صادق بلا
 *   تعقيد، ومقصوص بلا سقف زائف للأعلى-أفضل؛ للأقل-أفضل يبقى كما هو ليُنذر.
 */
object KpiBoardP47 {

    /** اتجاه المؤشر */
    enum class Direction { HIGHER_BETTER, LOWER_BETTER }

    /** حالة المؤشر عند عتبة معينة من الشهر */
    enum class Status { NO_TARGET, ACHIEVED, AHEAD, BEHIND }

    /** مفتاح المؤشرات الأربعة — ترتيب العرض في الشاشة */
    enum class Key { SALES, PROFIT, EXPENSES, OVERDUE }

    /** مدخل مؤشر واحد — كل الأرقام بالريال (لا قروش هنا: الحدود عند الاستدعاء) */
    data class Kpi(
        val key: Key,
        val direction: Direction,
        val actual: Double,
        val target: Double,          // 0 = بلا هدف
        val monthFraction: Double    // 0..1 نسبة الشهر المضيّة
    )

    /** نتيجة مؤشر واحد جاهزة للعرض */
    data class KpiResult(
        val key: Key,
        val progress: Double,        // 0..1 (قد يتجاوز 1 عند تجاوز الهدف — تُقصه الواجهة)
        val pace: Double,            // >1 أمام الجدول، <1 متأخر، 1.0 في الموعد تماماً
        val forecastEom: Double,     // توقع نهاية الشهر خطياً
        val status: Status
    )

    /** نسبة الشهر المضيّة من يوم الشهر وعدد أيامه — حارس قسمة صفر يخدمني دائماً */
    fun monthFraction(dayOfMonth: Int, daysInMonth: Int): Double {
        if (daysInMonth <= 0) return 1.0
        val d = max(0, dayOfMonth)
        return (d.toDouble() / daysInMonth).coerceIn(0.0, 1.0)
    }

    /** التقدم: أعلى-أفضل = فعلي÷هدف، أقل-أفضل = الهدف مقطوع إذا تجاوزه الفعلي */
    fun progress(k: Kpi): Double {
        if (k.target <= 0.0) return 0.0
        return when (k.direction) {
            Direction.HIGHER_BETTER -> k.actual / k.target
            Direction.LOWER_BETTER ->
                if (k.actual <= k.target) 1.0 else k.target / k.actual
        }
    }

    /** الإيقاع: الفعلي ÷ المتوقع عند هذه اللحظة من الشهر (أقل-أفضل معكوس بعقده) */
    fun pace(k: Kpi): Double {
        if (k.target <= 0.0 || k.monthFraction <= 0.0) return 1.0
        val expected = k.target * k.monthFraction
        return when (k.direction) {
            Direction.HIGHER_BETTER ->
                if (expected <= 0.0) 1.0 else k.actual / expected
            Direction.LOWER_BETTER ->
                if (k.actual <= 0.0) 2.0  // لا شيء منهَلَك حتى الآن = أمام الجدول بقوة
                else expected / k.actual
        }
    }

    /** توقع نهاية الشهر خطياً — حارس النسبة الصفرية (أول الشهر = التوقع هو الفعلي) */
    fun forecastEom(k: Kpi): Double {
        if (k.monthFraction <= 0.0) return k.actual
        return k.actual / k.monthFraction
    }

    /** الحالة النهائية بعقود العتبات: تحقق ≥100٪، أمام ≥110٪ من الإيقاع، خلف <90٪ */
    fun status(k: Kpi): Status {
        if (k.target <= 0.0) return Status.NO_TARGET
        val p = progress(k)
        return when (k.direction) {
            Direction.HIGHER_BETTER ->
                when {
                    p >= 1.0 -> Status.ACHIEVED
                    pace(k) >= 1.1 -> Status.AHEAD
                    pace(k) < 0.9 -> Status.BEHIND
                    else -> Status.AHEAD  // في نطاق الإيقاع الطبيعي (0.9..1.1) = على المسار
                }
            Direction.LOWER_BETTER ->
                when {
                    k.actual <= k.target -> Status.ACHIEVED
                    pace(k) > 1.1 -> Status.AHEAD
                    pace(k) < 0.9 -> Status.BEHIND
                    else -> Status.AHEAD
                }
        }
    }

    /** حوسبة مؤشر واحد كاملة */
    fun evaluate(k: Kpi): KpiResult = KpiResult(
        key = k.key,
        progress = progress(k),
        pace = pace(k),
        forecastEom = forecastEom(k),
        status = status(k)
    )

    /**
     * التحويل إلى قروش (Long) عند العرض — عقد المال في المشروع: Long قروش.
     * [تدقيق L-2] كان التحويل (value * 100).roundToLong() على عائم ثنائي —
     * قيم مثل 2.675 تنتج 267 بدل 268 (انحراف هللة) عن التحويل القانوني
     * Money.toPiasters (BigDecimal على نص القيمة بقاعدة HALF_UP).
     * التوحيد: كل حدود التحويل في المشروع عبر Money وحدها.
     */
    fun toPiasters(value: Double): Long = com.superbiz.app.util.Money.toPiasters(value)
}
