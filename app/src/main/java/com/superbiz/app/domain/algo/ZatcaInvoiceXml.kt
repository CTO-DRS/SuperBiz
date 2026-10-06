package com.superbiz.app.domain.algo

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * [P38-Z4] XML قانوني كامل البنود للفاتورة — تصدير اختياري للقراءة البشرية
 * والمحاسبية، وبنية موائمة لشكل UBL المبسّط المستخدم في ZatcaStamp.canonicalXml.
 *
 * ─── عقد التصدير ───
 * - **حتمي بايتاً ببايت** لنفس المدخلات: ترتيب الحقول ثابت، بلا فراغات بين
 * العناصر، المبالغ بخانتين عشريتين (عرف money2 في ZatcaQr)، الكمية بتمثيل
 * مقصوص حتمي (صحيح بلا كسور، وإلا حتى 3 خانات بلا أصفار زائدة)، والطابع
 * الزمني ISO-8601 UTC بصيغة وسم 3 نفسها في المرحلة-1.
 * - **المبالغ مدخلات ريال Double** (عقد ZatcaQr/ZatcaStamp) — المستدعي يمرّر
 * الحدود عبر Money.fromPiasters من لقطة الفاتورة القروشية فلا تحويل متفرق.
 * - التهريب عبر ZatcaStamp.xmlEscape (الكيانات الخمسة — «&» أولاً) — لا تهريب
 * مزدوج أبداً، والعربية تمر UTF-8 كما هي.
 * - المستلم (buyer) اختياري: null أو فارغ ⇒ العنصر يُحذف كلياً (لا عنصر فارغ).
 * - [P41-L3] كل سطر يكتسب `<TaxCategory>S|Z|E</TaxCategory>` بعد
 * `<VATRate>` — فئة UBL المبسّط من LineTaxP41.categoryId (قياسية/صفرية/معفاة).
 * تغيير عقد حرفي موثق: الوثائق المصدّرة قبل v11 بلا هذا العنصر — الاختبارات
 * القائمة تحقق بـcontains فلا تنكسر، والعقد الحتمي بايتاً ببايت محفوظ لكل
 * مدخلات بعينها.
 * - الملف نقي بلا أي اعتماد Android — JUnit مجرد (نمط ZatcaQr/ZatcaStamp).
 *
 * ملاحظة صادقة: هذه وثيقة تصدير موائمة لشكل UBL المبسّط لا رسالة فاتورة-2
 * الرسمية (UBL 2.1 كاملة + شهادة CSID من منصة فاتورة) — العقد موثق في رأس
 * ZatcaStamp: الربط الرسمي بالهيئة يتطلب تكاملاً خارج نطاق تطبيق بلا خادم.
*/
object ZatcaInvoiceXml {

    /** سطر بند في XML — qty/الأسعار ريال Double كما في لقطة العرض، taxKind فئة v11 (افتراضها 0 قياسية) */
    data class XmlLine(
        val desc: String,
        val qty: Double,
        val unitPrice: Double,
        val vatRate: Double,   // بعرف الكيان: 15.0 = 15%
        val lineTotal: Double,
        val taxKind: Int = 0,  // [P41-L3] فئة السطر الضريبية — LineTaxP41.KIND_*
    )

    /** منسّق المبالغ بخانتين عشريتين — نفس عرف ZatcaQr.money2 حرفياً */
    private fun money2(v: Double): String = String.format(Locale.US, "%.2f", v)

    /** تمثيل الكمية: صحيح بلا كسور، وإلا حتى 3 خانات بلا أصفار زائدة — حتمي دائماً */
    fun qty3(v: Double): String = when {
        v == v.toLong().toDouble() -> v.toLong().toString()
        else -> String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
    }

    /** الطابع الزمني ISO-8601 UTC — يطابق حرفياً صيغة وسم 3 في ZatcaQr */
    private fun isoUtc(ms: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(ms))
    }

    /**
     * بناء وثيقة XML الكاملة — ثابتة البنية، مهربة الحقول النصية، جاهزة للحفظ
     * والمشاركة كملف مستقل لكل فاتورة.
     */
    fun invoiceXml(
        sellerName: String,
        vatNumber: String,
        timestampMs: Long,
        invoiceNumber: String,
        lines: List<XmlLine>,
        subtotal: Double,
        discount: Double,
        vatTotal: Double,
        invoiceTotal: Double,
        buyerName: String? = null,
    ): String = buildString(1024) {
        append("<Invoice>")
        append("<InvoiceNumber>").append(ZatcaStamp.xmlEscape(invoiceNumber)).append("</InvoiceNumber>")
        append("<TimeStamp>").append(isoUtc(timestampMs)).append("</TimeStamp>")
        append("<Seller><Name>").append(ZatcaStamp.xmlEscape(sellerName)).append("</Name>")
        append("<VATNumber>").append(ZatcaStamp.xmlEscape(vatNumber)).append("</VATNumber></Seller>")
        if (!buyerName.isNullOrBlank()) {
            append("<Buyer><Name>").append(ZatcaStamp.xmlEscape(buyerName)).append("</Name></Buyer>")
        }
        append("<Lines>")
        lines.forEachIndexed { i, l ->
            append("<Line>")
            append("<Index>").append(i + 1).append("</Index>")
            append("<Description>").append(ZatcaStamp.xmlEscape(l.desc)).append("</Description>")
            append("<Qty>").append(qty3(l.qty)).append("</Qty>")
            append("<UnitPrice>").append(money2(l.unitPrice)).append("</UnitPrice>")
            append("<VATRate>").append(qty3(l.vatRate)).append("</VATRate>")
            append("<TaxCategory>")
                .append(com.superbiz.app.domain.LineTaxP41.categoryId(l.taxKind))
                .append("</TaxCategory>")
            append("<LineTotal>").append(money2(l.lineTotal)).append("</LineTotal>")
            append("</Line>")
        }
        append("</Lines>")
        append("<Totals>")
        append("<Subtotal>").append(money2(subtotal)).append("</Subtotal>")
        append("<Discount>").append(money2(discount)).append("</Discount>")
        append("<VATTotal>").append(money2(vatTotal)).append("</VATTotal>")
        append("<InvoiceTotal>").append(money2(invoiceTotal)).append("</InvoiceTotal>")
        append("</Totals>")
        append("</Invoice>")
    }
}
