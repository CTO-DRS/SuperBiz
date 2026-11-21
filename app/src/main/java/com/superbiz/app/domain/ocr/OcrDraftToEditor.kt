package com.superbiz.app.domain.ocr

/**
 * [F2 - ADR-005 D4/تعبئة المحرر] محرك التحويل مسودة OCR → تعبئة محرر الفاتورة —
 * منطق نقي صفر اعتماديات أندرويد (JVM صرف، مختبر في OcrDraftToEditorTest).
 *
 * العقد (كل بند مختبر صراحة):
 * - **الكتالوج حكمًا**: السطر المطابق لمنتج (فوق حد القرار في OcrProductMatcher)
 *   يهبط للمحرر بمعرف المنتج واسمه الكتالوجي الحقيقي — حفاظًا على اتساق القوائم
 *   والتقارير والمخزون؛ السطر غير المطابق يبقى نصًا حرًا بوصف OCR الأصلي
 *   (productId=null — نفس عقد السطر اليدوي في المحرر منذ V3.5.2).
 * - **حدود OcrInvoiceParser نفسها**: كمية (0, MAX_QTY] وسعر (0, MAX_LINE_QIRUSH]
 *   — ما خرج عنها يُسقط بإعلان (skippedLines + warnings) لا بصمت ولا استثناء.
 * - **المسودة الفقيرة لا تفتح محررًا**: لا بنود بعد الإسقاط ⇒ usable=false —
 *   الواجهة لا تفتح محررًا فارغًا زائفًا ولا تنشئ فاتورة بلا بنود (عقد save).
 * - **الضريبة في 0..100 حصرًا** — خارجها/غائبة ⇒ null والمحرر يأخذ قيمته الافتراضية
 *   من إعدادات المالك (سلوك openEditor القائم لا يُلمس).
 * - **الجهة نص حر مقصوص**: اسم الجهة من المسودة يُقَص لحده [MAX_PARTY_CHARS] —
 *   إنشاء الطرف فعليًا (أو مطابقته بالاسم) قرار طبقة VM، ليس هنا.
 * - **النوع مُحصَّر على 0/1**: غير الصالح يعود لشراء (1) — اتجاه المسح الطبيعي
 *   (إيصال مورد يُدخل كفاتورة شراء)؛ البيع يبقى مسار نقطة البيع/المحرر اليدوي.
 * - المال قروش Long كما خرج من المحلل — لا أي تحويل هنا (عقد P33-P8).
 */
object OcrDraftToEditor {

    /** حدود السطر — نفس عقد OcrInvoiceParser حرفيًا (مصدر واحد للحقيقة) */
    const val MAX_QTY = OcrInvoiceParser.MAX_QTY
    const val MAX_LINE_QIRUSH = OcrInvoiceParser.MAX_LINE_QIRUSH

    /** حد اسم الجهة المنقول للمحرر */
    const val MAX_PARTY_CHARS = 60

    /** حد وصف السطر — نفس قص المحلل (دفاع ثاني بلا تكلفة) */
    const val MAX_DESC_CHARS = 80

    /** بند جاهز للتعبئة — المال قروش، الكمية Double بعقد EditorItem نفسه */
    data class ItemSpec(
        val productId: Long?,   // null = نص حر (سطر يدوي)
        val desc: String,
        val qty: Double,
        val priceQirush: Long
    )

    /** حزمة تعبئة المحرر — تُستهلك في InvoicesVM.prefillFromOcr ثم تُلغى (Bus one-shot) */
    data class OcrEditorPrefill(
        val type: Int,              // 0 بيع / 1 شراء (مُحصَّر)
        val partyName: String?,     // null = بلا طرف — المالك يختار من المحرر
        val items: List<ItemSpec>,
        val taxRate: Double?,       // null = القيمة الافتراضية للمحرر
        val usable: Boolean,        // بنود صالحة بعد الإسقاط؟
        val skippedLines: Int,      // عدد الأسطر المُسقطة بإعلان
        val warnings: List<String>  // أسباب الإسقاط/الملاحظات — تُعرض في الواجهة
    )

    /**
     * التحويل الرئيسي — لا استثناءات تهرَّب، كل شذوذ إسقاط معلن.
     * [matches] أقصر من البنود أو أطول ⇒ دفاع getOrNull بلا انهيار.
     */
    fun toPrefill(
        draft: OcrInvoiceParser.OcrInvoiceDraft,
        matches: List<OcrProductMatcher.Match?>,
        type: Int = 1
    ): OcrEditorPrefill {
        val warnings = mutableListOf<String>()
        var skipped = 0
        val items = mutableListOf<ItemSpec>()

        draft.lines.forEachIndexed { i, l ->
            val m = matches.getOrNull(i)
            // الكمية: نطاق مفتوح-مغلق (0, MAX] — NaN يفشل كلا المقارنتين فيُسقط تلقائيًا
            if (!(l.qty > 0.0 && l.qty <= MAX_QTY)) {
                skipped++
                warnings.add("السطر ${i + 1}: كمية خارج النطاق — أُسقط من التعبئة")
                return@forEachIndexed
            }
            if (l.unitPriceQirush <= 0L || l.unitPriceQirush > MAX_LINE_QIRUSH) {
                skipped++
                warnings.add("السطر ${i + 1}: سعر خارج النطاق — أُسقط من التعبئة")
                return@forEachIndexed
            }
            val desc = (m?.productName ?: l.desc).take(MAX_DESC_CHARS)
            items.add(ItemSpec(productId = m?.productId, desc = desc, qty = l.qty, priceQirush = l.unitPriceQirush))
        }

        val partyName = draft.merchant?.trim()?.take(MAX_PARTY_CHARS)?.ifBlank { null }

        return OcrEditorPrefill(
            type = if (type == 0) 0 else 1,
            partyName = partyName,
            items = items.toList(),
            taxRate = draft.vatRate?.takeIf { it in 0.0..100.0 },
            usable = items.isNotEmpty(),
            skippedLines = skipped,
            warnings = warnings.toList()
        )
    }
}
