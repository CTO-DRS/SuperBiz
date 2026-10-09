package com.superbiz.app.print

/**
 * [H4-6][V 3.0.0] — قوالب الفاتورة المطبوعة: ثلاثة أصناف بمواصفات معلنة تُطبَّق
 * على الحراري وA4 معاً (قرار المنتج: قالب واحد للنظامين كي لا يقرأ التاجر
 * فاتورتين مختلفتي الشكل لنفس العملية).
 *
 * - CLASSIC: التخطيط القائم حرفياً (الافتراض — كل القوائم القائمة سلوكها كما كان).
 * - DETAILED: كلاسيكي + الرقم الضريبي للطرف على الفاتورة + سطر وصف موسع.
 * - COMPACT: حراري-صديق — بلا التفقيط وبلا صف الخصم وبلا سطر الرقم الضريبي.
 *
 * المصدر الوحيد للاختيار: AppPrefs.invoiceTemplate (0..2) عبر SettingsRepo.
 */
enum class InvoiceTemplate(
    val id: Int,
    val showDiscountRow: Boolean,
    val showTafqit: Boolean,
    val showPartyVat: Boolean
) {
    CLASSIC(0, true, true, false),
    DETAILED(1, true, true, true),
    COMPACT(2, false, false, false);

    companion object {
        fun fromId(id: Int): InvoiceTemplate = entries.firstOrNull { it.id == id } ?: CLASSIC
    }
}
