package com.superbiz.app.domain.ocr

/**
 * [F2 - ADR-005 D4/تعبئة المحرر] حالة معلقة لإحالة «تعبئة المحرر من مسودة OCR»
 * من شاشة المسح إلى شاشة الفواتير — على نمط ReorderBus حرفيًا (أبسط آلية
 * متوافقة مع المعمارية: بلا تعديل تنقل ولا VM مشترك بين الوجهتين).
 *
 * - تُحجز قبل التنقل وتُستهلك مرة واحدة عند العودة لشاشة الفواتير (one-shot).
 * - بلا سلوك مالي ولا شبكة — نقل حالة نقي بين شاشتين.
 */
object OcrPrefillBus {
    @Volatile
    var pending: OcrDraftToEditor.OcrEditorPrefill? = null
        private set

    /** حجز تعبئة معلقة — تُستهلك مرة واحدة عند فتح شاشة الفواتير */
    fun post(prefill: OcrDraftToEditor.OcrEditorPrefill) { pending = prefill }

    /** الاستهلاك الآحادي — يعيد الحزمة ويمسحها معًا؛ null = لا شيء معلق */
    fun consume(): OcrDraftToEditor.OcrEditorPrefill? {
        val p = pending
        pending = null
        return p
    }

    /** الإخلاء اليدوي (إبطال طلب قديم غير مستهلك) */
    fun clear() { pending = null }
}
