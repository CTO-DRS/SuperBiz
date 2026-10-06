package com.superbiz.app.pdf.statement

import com.superbiz.app.domain.statement.StatementLang
import java.util.Calendar
import java.util.Locale

/**
 * [P17-b] StatementFormat — تنسيق نصوص كشف الحساب (JVM نقي بلا أي اعتماد Android).
 *
 * ── قرارات موثقة (فرق عن التنفيذات الموجودة في المشروع) ──
 *
 * 1) money(): ألف-فاصل + خانتان عشريتان + رمز العملة دائماً («1,234.50 SAR»).
 * • تُحظر إعادة تنفيذ أداة تنسيق المبالغ الموجودة: قرأنا domain/algo/ZatcaQr.kt
 * (money2 = String.format(Locale.US, "%.2f") بلا فواصل — مصممة لحقول TLV
 * القانونية ولا تقبل فواصل تجميع) و domain/algo/MoneyMath.kt (خوارزميات مالية
 * للتقريب/الضريبة وليست منسّقاً للعرض) و util/Money.kt (المنسّق العام).
 * • لماذا لا نستخدم util.Money.format مباشرة؟ لأنه (أ) يُخفي الخانات العشرية
 * للأعداد الصحيحة (2,670 بدل 2,670.00) — كشف الحساب المحاسبي يثبّت الخانتين
 * في كل السطور ليتساوى عمود الرصيد عمودياً، (ب) يأخذ «رمزاً» لا «رمز ISO»،
 * (ج) لا يضمن فاصل التجميع للخانات العشرية بنفس المسار. الفرق هنا مقصود
 * ومشروح: عرض عمودي محاسبي، وليس عرض إيصال/فاتورة.
 * • التقريب موحّد مع الكانوني: نستدعي util.Money.round2 (BigDecimal HALF_UP)
 * كي لا نكرّر منطق التقريب — التنسيق فقط هو الجديد هنا.
 *
 * 2) الأرقام لاتينية (غربية) حتى في البيئة العربية — قرار موثق
 * • عرف الكشوفات المالية السعودية يحفظ الأرقام غربية في المستندات ثنائية
 * اللغة، وتطبيق SuperBiz كله يوحّد Locale.US في util.Money ( M-2.3)
 * فلو طبعت الكشوفات أرقاماً عربية-هندية لاختلفت عن بقية مستندات التطبيق
 * وأصبح عمود الرصيد غير قابل للقراءة آلياً.
 * • لذلك لا نستخدم SimpleDateFormat بلocale "ar" إطلاقاً (ICU قد يخرج أرقاماً
 * هندية ٠-٩) — أسماء الشهور العربية مصفوفة يدوية والبقية Locale.US.
 *
 * 3) pageOf(): «صفحة i من n» / "Page i of n" — تُرجع سلسلة مستقلة كي يرسم محرك
 * التخطيط عنصر الترقيم كعنصر LText منفصل قابل للفحص الهندسي بالاختبار.
 *
 * 4) محاذاة أعمدة المبالغ (المساعد المحاسبي): بما أن money() يثبّت خانتين
 * عشريتين دائماً، فمحاذاة الحافة اليمنى للمربع = محاذاة الخانة العشرية =
 * محاذاة الآحاد/الآلاف («百位对齐» بمفهوم أعمدة المبالغ). المساعدات
 * rightX/centerX تحسب x الانطلاق من عرض النص المقاس عبر TextMeasurer.
*/
object StatementFormat {

    // ───────────────────────── المبالغ ─────────────────────────

    /**
     * «1,234.50 SAR» — فاصل آلاف + خانتان عشريتان ثابتتان + رمز عملة ISO.
     * السالب ببادئة "-" (وليس أقواساً) توحيداً مع util.Money.num في المشروع كله.
     * القيم غير المنتهية (NaN/Infinity) تعرض «— CODE» بدل انفجار التنسيق.
     */
    // [P33-P8] قروش صحيحة — فاصل آلاف + خانتان عشريتان ثابتتان (عقد التنسيق المالي: 0 قرش = "0.00")
    fun money(v: Long, currency: String): String {
        val body = String.format(Locale.US, "%,.2f", kotlin.math.abs(v) / 100.0)
        return (if (v < 0) "-" else "") + body + " " + currency
    }

    /** نسخة بلغة الكشف — المخرجات واحدة لأن قرار الأرقام الغربية موحد (انظر أعلاه) */
    fun money(v: Long, currency: String, @Suppress("UNUSED_PARAMETER") lang: StatementLang): String =
        money(v, currency)

    // ───────────────────────── التواريخ ─────────────────────────

    /** أسماء الشهور العربية يدوياً — ضمان أرقام غربية (انظر القرار 2 أعلاه) */
    private val AR_MONTHS = arrayOf(
        "يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو",
        "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"
    )

    private val EN_MONTHS = arrayOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )

    private fun cal(ts: Long): Calendar = Calendar.getInstance().apply { timeInMillis = ts }

    /** «05/03/2025» — dd/MM/yyyy بأرقام غربية (عرف المستندات المالية) */
    fun dateShort(ts: Long): String {
        val c = cal(ts)
        return String.format(
            Locale.US, "%02d/%02d/%04d",
            c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR)
        )
    }

    /**
     * التاريخ الطويل ثنائي اللغة:
     * AR  → «5 مارس 2025» (d MMMM yyyy — شهور عربية يدوية + أرقام غربية)
     * EN / BILINGUAL → "Mar 5, 2025" (MMM d, yyyy بـ Locale.US)
     */
    fun dateLong(ts: Long, lang: StatementLang): String {
        val c = cal(ts)
        val d = c.get(Calendar.DAY_OF_MONTH)
        val m = c.get(Calendar.MONTH)
        val y = c.get(Calendar.YEAR)
        return when (lang) {
            StatementLang.AR -> "$d ${AR_MONTHS[m]} $y"
            StatementLang.EN, StatementLang.BILINGUAL ->
                String.format(Locale.US, "%s %d, %d", EN_MONTHS[m], d, y)
        }
    }

    // ───────────────────────── ترقيم الصفحات ─────────────────────────

    /** «صفحة 2 من 9» / "Page 2 of 9" — سلسلة ترقيم مستقلة للفحص الهندسي */
    fun pageOf(page: Int, total: Int, lang: StatementLang): String = when (lang) {
        StatementLang.AR -> "صفحة $page من $total"
        StatementLang.EN, StatementLang.BILINGUAL -> "Page $page of $total"
    }

    /** تاريخ الإصدار كما يُعرض في التذييل: «أُصدر في dd/MM/yyyy» / "Generated: dd/MM/yyyy" */
    fun generatedOn(ts: Long, lang: StatementLang): String = when (lang) {
        StatementLang.AR -> "أُصدر في ${dateShort(ts)}"
        StatementLang.EN, StatementLang.BILINGUAL -> "Generated: ${dateShort(ts)}"
    }

    /** فترة الكشف: «من X إلى Y» / "From X to Y" */
    fun period(fromTs: Long, toTs: Long, lang: StatementLang): String = when (lang) {
        StatementLang.AR -> "من ${dateShort(fromTs)} إلى ${dateShort(toTs)}"
        StatementLang.EN, StatementLang.BILINGUAL ->
            "From ${dateShort(fromTs)} to ${dateShort(toTs)}"
    }

    // ───────────────────────── مساعدات المحاذاة المحاسبية ─────────────────────────

    /**
     * x عمود المبالغ: الحافة اليمنى للمربع ناقص عرض النص المقاس.
     * بما أن money() يثبّت خانتين عشريتين فمحاذاة اليمين = محاذاة الخانة العشرية =
     * محاذاة الآحاد/الآلاف للعمود كله («百位对齐» بمفهوم أعمدة المبالغ).
     * تنبيه محاسبي موثق: هذه المحاذاة تبقى يمينية حتى داخل صفحة RTL —
     * عمود الأرقام يحافظ على اتجاه LTR مهما انعكس ترتيب الأعمدة (عرف محاسبي).
     */
    fun rightX(rightEdge: Float, text: String, sizeSp: Float, m: TextMeasurer, bold: Boolean = false): Float =
        rightEdge - m.width(text, sizeSp, bold)

    /** x توسيط النص داخل صندوق بعرض boxW يبدأ عند boxX */
    fun centerX(boxX: Float, boxW: Float, text: String, sizeSp: Float, m: TextMeasurer, bold: Boolean = false): Float =
        boxX + (boxW - m.width(text, sizeSp, bold)) / 2f

    /** مرادف دلالي لـ rightX عند المحاذاة نهاية منطقة (يقروء أسهل في المحرك) */
    fun endX(endEdge: Float, text: String, sizeSp: Float, m: TextMeasurer, bold: Boolean = false): Float =
        rightX(endEdge, text, sizeSp, m, bold)
}
