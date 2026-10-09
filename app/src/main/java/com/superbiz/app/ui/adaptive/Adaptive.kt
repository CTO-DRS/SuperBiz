package com.superbiz.app.ui.adaptive

/**
 * [H4-4][V 3.0.0] — حسابات التخطيط التكيفي النقية (فئات نافذة Material 3):
 * compact < 600dp، medium 600..839dp، expanded ≥ 840dp.
 * كل الدوال خالصة Int→Int لتُختبر JVM بلا Compose ولا Robolectric.
 */
object AdaptiveMath {

    const val MEDIUM_MIN = 600
    const val EXPANDED_MIN = 840

    /** أعمدة شبكة أصناف نقطة البيع: 2 / 3 / 5 */
    fun posColumns(widthDp: Int): Int = when {
        widthDp >= EXPANDED_MIN -> 5
        widthDp >= MEDIUM_MIN -> 3
        else -> 2
    }

    /** أعمدة شبكة لوحة KPI: عمود واحد هاتفي، بطاقتان جنباً لجنب من medium */
    fun kpiColumns(widthDp: Int): Int = if (widthDp >= MEDIUM_MIN) 2 else 1

    /** هل يُعرض المحتوى بلوحتين جنباً لجنب (قائمة + تفاصيل)؟ */
    fun isSideBySide(widthDp: Int): Boolean = widthDp >= EXPANDED_MIN

    /** أقصى عرض للمحتوى القرائي — expanded يتمركز بدل التمدد الكامل. */
    fun maxContentWidth(widthDp: Int): Int? = if (widthDp >= EXPANDED_MIN) 840 else null
}
