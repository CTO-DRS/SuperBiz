package com.superbiz.app.domain

/**
 * [H5-4] «الأفق الخامس — العالمية»: ولايات ضريبة القيمة المضافة الخليجية الثلاث.
 *
 * عقد الولاية (موثق أيضاً في docs/ADR-002_MONEY_CURRENCY.md §3 و docs/ROADMAP.md الأفق الخامس):
 * - [P51-1] بذور رسمية بنِسَب القانون عند الإصدار: السعودية 15%، الإمارات 5%، البحرين 10%.
 * - [P51-2] تطبيق الولاية فعل صريح من المالك (زر «تطبيق الافتراضيات») — لا إعادة
 *   كتابة صامتة لنسبة/عملة قائمة؛ الفواتير المحفوظة تحمل نسبتها اللحظية أصلاً
 *   (Invoice.taxRate لقطة لكل وثيقة) فلا يتأثر التاريخ بأي تغيير ولاية.
 * - [P51-3] ZATCA مرحلة-2 سعودية حصراً: خارج SA يخرج البائع بلا رقم ضريبي عبر
 *   [zatcaSellerVat] ⇒ ZatcaStamper.stamp() يعيد null صامتاً بعقده (سطر 73)،
 *   فلا ختم ولا QR ولا UBL ولا أي socket — نفس البقاء الصامت المعمى منذ V 1.5.0.
 * - [P51-4] مجهول/فارغ ⇒ SA (فشل مغلق نحو السلوك التاريخي: الترقية التراكمية
 *   من 1.0.0 إلى 3.0.0 لا تغيّر سلوك تاجر قائم بلا قرار منه).
 * - [P51-5] البحرين: ضريبة الفاتورة تُقرَّب رسمياً إلى أقرب 10 فلس = 1/100 دينار =
 *   وحدتنا الصغرى بالضبط (عقد ADR-002 §2) — لا دقة مفقودة في الإقرار.
 */
object GulfTax {

    data class Preset(
        val code: String,          // ISO 3166-1 alpha-2
        val defaultRate: Double,   // نسبة VAT القياسية %
        val currency: String,      // عملة الولاية المقترحة
        val zatcaApplies: Boolean, // هل منصة فاتورة ZATCA ملزمة هنا؟
        val weekendFriSat: Boolean // عطلة نهاية الأسبوع لجدولة التنبيهات والتقارير
    )

    val SA = Preset("SA", 15.0, "SAR", true, true)
    // الإمارات: عطلة السبت-الأحد في القطاع الخاص منذ 2022 — مرآة setWeekendFriSat=false
    val AE = Preset("AE", 5.0, "AED", false, false)
    val BH = Preset("BH", 10.0, "BHD", false, true)

    val ALL: List<Preset> = listOf(SA, AE, BH)
    val CODES: Set<String> = ALL.map { it.code }.toSet()

    /** الولاية الحاكمة — أي كود خارج الثلاثية يعود إلى SA (فشل مغلق [P51-4]) */
    fun preset(code: String): Preset = ALL.firstOrNull { it.code == code } ?: SA

    fun isZatcaJurisdiction(code: String): Boolean = preset(code).zatcaApplies

    /**
     * [P51-3] بوابة رقم ضريبي البائع — نقية وقابلة للاختبار بمعزل عن AppGraph:
     * خارج SA يُفرَّغ الرقم ⇒ عقد ZatcaStamper يُبقي التطبيق صامتاً كلياً.
     */
    fun zatcaSellerVat(jurisdiction: String, vatNumber: String): String =
        if (isZatcaJurisdiction(jurisdiction)) vatNumber else ""
}
