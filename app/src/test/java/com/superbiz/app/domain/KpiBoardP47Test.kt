package com.superbiz.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [W1] اختبارات محرك لوحة المؤشرات النقي — الموجة 1، البند 8.
 *
 * العقود الحاسمة المغطاة:
 * - بلا هدف (0) = بلا نسبة وهمية — حالة NO_TARGET صادقة.
 * - أعلى-أفضل: التقدم فعلي÷هدف. أقل-أفضل: تجاوز السقف = تقدم مقصوص منطقياً.
 * - الإيقاع مقابل نسبة الشهر المضيّة يومياً — بلا تحيّز لبداية الشهر.
 * - التوقع الخطي مع حارس النسبة الصفرية.
 */
class KpiBoardP47Test {

    private fun kpi(
        actual: Double, target: Double, fraction: Double = 0.5,
        direction: KpiBoardP47.Direction = KpiBoardP47.Direction.HIGHER_BETTER,
        key: KpiBoardP47.Key = KpiBoardP47.Key.SALES
    ) = KpiBoardP47.Kpi(key, direction, actual, target, fraction)

    // ══ نسبة الشهر المضيّة ══

    @Test
    fun monthFraction_basic_and_bounds() {
        assertEquals(0.5, KpiBoardP47.monthFraction(15, 30), 1e-9)
        assertEquals(1.0, KpiBoardP47.monthFraction(31, 30), 1e-9)  // فوق الحد يُقص
        assertEquals(0.0, KpiBoardP47.monthFraction(0, 30), 1e-9)
    }

    @Test
    fun monthFraction_guards_zero_days() {
        // حارس القسمة الصفرية: شهر بلا أيام لا معنى له = الشهر انتهى
        assertEquals(1.0, KpiBoardP47.monthFraction(10, 0), 1e-9)
    }

    // ══ التقدم ══

    @Test
    fun progress_no_target_is_zero_not_fake_100() {
        // بلا هدف = صفر صادق (الواجهة تعرض «بلا هدف» لا 100٪ وهمية)
        assertEquals(0.0, KpiBoardP47.progress(kpi(5000.0, 0.0)), 1e-9)
    }

    @Test
    fun progress_higher_better_direct_ratio() {
        assertEquals(0.6, KpiBoardP47.progress(kpi(6000.0, 10000.0)), 1e-9)
        assertEquals(1.2, KpiBoardP47.progress(kpi(12000.0, 10000.0)), 1e-9) // تجاوز مسموح
    }

    @Test
    fun progress_lower_better_caps_at_one_when_under_cap() {
        // المصروفات: سقف 5000 والفعلي 3000 = التقدم تام
        assertEquals(
            1.0,
            KpiBoardP47.progress(
                kpi(3000.0, 5000.0, direction = KpiBoardP47.Direction.LOWER_BETTER, key = KpiBoardP47.Key.EXPENSES)
            ),
            1e-9
        )
    }

    @Test
    fun progress_lower_better_shrinks_as_actual_explodes() {
        // سقف 5000 والفعلي 10000 = نصف التقدم (نسبة الهدف للفعلي)
        assertEquals(
            0.5,
            KpiBoardP47.progress(
                kpi(10000.0, 5000.0, direction = KpiBoardP47.Direction.LOWER_BETTER, key = KpiBoardP47.Key.OVERDUE)
            ),
            1e-9
        )
    }

    // ══ الإيقاع ══

    @Test
    fun pace_higher_better_ahead_and_behind() {
        // منتصف الشهر، متوقع 5000، الفعلي 6000 = أمام الجدول
        assertEquals(1.2, KpiBoardP47.pace(kpi(6000.0, 10000.0, fraction = 0.5)), 1e-9)
        // الفعلي 3000 = متأخر
        assertEquals(0.6, KpiBoardP47.pace(kpi(3000.0, 10000.0, fraction = 0.5)), 1e-9)
    }

    @Test
    fun pace_lower_better_is_inverted() {
        // منتصف الشهر، السقف يسمح حتى 5000 متوقعة، الفعلي 1250 = أمام الجدول (2500÷1250)
        assertEquals(
            2.0,
            KpiBoardP47.pace(
                kpi(1250.0, 5000.0, fraction = 0.5, direction = KpiBoardP47.Direction.LOWER_BETTER, key = KpiBoardP47.Key.EXPENSES)
            ),
            1e-9
        )
        // عند المنتصف تماماً (الفعلي = المتوقع 2500) = الإيقاع 1.0
        assertEquals(
            1.0,
            KpiBoardP47.pace(
                kpi(2500.0, 5000.0, fraction = 0.5, direction = KpiBoardP47.Direction.LOWER_BETTER, key = KpiBoardP47.Key.EXPENSES)
            ),
            1e-9
        )
    }

    @Test
    fun pace_lower_better_zero_actual_is_strongly_ahead() {
        // لا شيء منهمك حتى الآن = أمام الجدول بقوة (عقد القيمة 2.0)
        assertEquals(
            2.0,
            KpiBoardP47.pace(
                kpi(0.0, 5000.0, fraction = 0.5, direction = KpiBoardP47.Direction.LOWER_BETTER, key = KpiBoardP47.Key.OVERDUE)
            ),
            1e-9
        )
    }

    @Test
    fun pace_neutral_without_target_or_elapsed_days() {
        assertEquals(1.0, KpiBoardP47.pace(kpi(6000.0, 0.0)), 1e-9)
        assertEquals(1.0, KpiBoardP47.pace(kpi(6000.0, 10000.0, fraction = 0.0)), 1e-9)
    }

    // ══ التوقع الخطي ══

    @Test
    fun forecast_linear_scales_by_fraction() {
        assertEquals(12000.0, KpiBoardP47.forecastEom(kpi(6000.0, 10000.0, fraction = 0.5)), 1e-9)
    }

    @Test
    fun forecast_guards_zero_fraction() {
        // أول الشهر: التوقع هو الفعلي نفسه بلا تضخيم
        assertEquals(700.0, KpiBoardP47.forecastEom(kpi(700.0, 10000.0, fraction = 0.0)), 1e-9)
    }

    // ══ الحالة ══

    @Test
    fun status_no_target_when_target_zero() {
        assertEquals(KpiBoardP47.Status.NO_TARGET, KpiBoardP47.status(kpi(5000.0, 0.0)))
    }

    @Test
    fun status_achieved_when_progress_complete() {
        assertEquals(KpiBoardP47.Status.ACHIEVED, KpiBoardP47.status(kpi(10000.0, 10000.0, fraction = 0.5)))
    }

    @Test
    fun status_ahead_when_pace_strong() {
        // منتصف الشهر والفعلي 60٪ من الهدف = إيقاع 1.2 ≥ 1.1
        assertEquals(KpiBoardP47.Status.AHEAD, KpiBoardP47.status(kpi(6000.0, 10000.0, fraction = 0.5)))
    }

    @Test
    fun status_behind_when_pace_weak() {
        // منتصف الشهر والفعلي 30٪ من الهدف = إيقاع 0.6 < 0.9
        assertEquals(KpiBoardP47.Status.BEHIND, KpiBoardP47.status(kpi(3000.0, 10000.0, fraction = 0.5)))
    }

    @Test
    fun status_lower_better_achieved_under_cap() {
        assertEquals(
            KpiBoardP47.Status.ACHIEVED,
            KpiBoardP47.status(
                kpi(3000.0, 5000.0, fraction = 0.5, direction = KpiBoardP47.Direction.LOWER_BETTER, key = KpiBoardP47.Key.EXPENSES)
            )
        )
    }

    // ══ التحويل للقروش (عقد المال) ══

    @Test
    fun toPiasters_rounds_halves() {
        assertEquals(123456L, KpiBoardP47.toPiasters(1234.56))
        assertEquals(123457L, KpiBoardP47.toPiasters(1234.565))
    }

    // ══ [تدقيق L-2] التوحيد مع Money — القيم التي انحرف عنها الضرب العائم بهللة ══

    @Test
    fun toPiasters_matches_canonical_Money_on_float_drift_cases() {
        // 2.675 كعائم ثنائي: 2.675*100 = 267.49999999999994 — الضرب القديم أعطى 267،
        // والتحويل القانوني (BigDecimal على النص) يعطي 268 — انحراف هللة كان ملموساً
        assertEquals(268L, KpiBoardP47.toPiasters(2.675))
        assertEquals(com.superbiz.app.util.Money.toPiasters(2.675), KpiBoardP47.toPiasters(2.675))
        // حماية القيم غير المنطقية — Money تعيد 0
        assertEquals(0L, KpiBoardP47.toPiasters(Double.NaN))
        assertEquals(0L, KpiBoardP47.toPiasters(Double.POSITIVE_INFINITY))
        // تطابق شامل على شبكة قيم نموذجية
        for (v in listOf(0.0, 0.01, 9.99, 12.34, 125.50, 1999.99, 12345.675)) {
            assertEquals("v=$v", com.superbiz.app.util.Money.toPiasters(v), KpiBoardP47.toPiasters(v))
        }
    }

    // ══ الحوسبة الكاملة ══

    @Test
    fun evaluate_bundles_all_fields() {
        val r = KpiBoardP47.evaluate(kpi(6000.0, 10000.0, fraction = 0.5))
        assertEquals(0.6, r.progress, 1e-9)
        assertEquals(1.2, r.pace, 1e-9)
        assertEquals(12000.0, r.forecastEom, 1e-9)
        assertEquals(KpiBoardP47.Status.AHEAD, r.status)
        assertEquals(KpiBoardP47.Key.SALES, r.key)
    }
}
