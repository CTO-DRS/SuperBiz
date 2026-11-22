package com.superbiz.app.domain.ml

/**
 * [F3 - ADR-005 D6] الخط الإحصائي القائم — الأساس الذي تُقارن عليه المبارزة.
 *
 * هذا هو نفس بداية التنبؤ الإحصائي في CashFlow90Math (R16-H3-1): متوسط صافي
 * آخر نافذة — مرفوعًا هنا إلى عقد تنبؤ عام فوق سلاسل R7–R18 حتى تُقارن كل
 * خطوط التعلّم الجهازية دائمًا بالخط الإحصائي القائم لا بأوهام.
 *
 * العقد (كل بند مختبر صراحة):
 * - النافذة آخر [window] قيمة حصرًا — ما قبلها لا يؤثر (بداية R16 نفسها).
 * - قيمة غير منتهية داخل النافذة ⇒ null — لا تنبؤ من قيمة ملوثة؛ غير المنتهي
 *   خارج النافذة لا يفسد (المستدعي يمرر شبكة نظيفة والصدق داخل النافذة المعنية).
 * - window أقل من [MIN_WINDOW] أو أفق أقل من 1 ⇒ null — لا تنبؤ من لا شيء.
 * - القيم السالبة محفوظة كما هي — صافي التدفق النقدي سالب حقيقة لا يُقصّ.
 * - نقي JVM صرف — بلا أندرويد ولا شبكة ولا وقت نظام.
 */
object StatBaseline {

    /** أدنى نافذة مقبولة — أسفلها لا معنى إحصائيًا */
    const val MIN_WINDOW = 7

    /** توقع أفقي بقيمة متوسط آخر [window] قيمة — الخط الإحصائي القائم حرفيًا */
    fun forecast(series: List<Double>, window: Int, horizon: Int): List<Double>? {
        if (window < MIN_WINDOW || horizon < 1) return null
        if (series.size < window) return null
        val last = series.subList(series.size - window, series.size)
        if (!last.all { it.isFinite() }) return null
        val mean = last.sum() / window
        return List(horizon) { mean }
    }
}
