package com.superbiz.app.domain

/**
 * [P43-D1] جولة 4 — الدفتر التفصيلي (تصدير عميق): صفوف لكل **بند** من فواتير
 * الفترة بأعمدة الضريبة الكاملة من v11 — الواجهة المفصلة التي يقف خلفها كل
 * ملخص (vatByRate والتقرير الدوري).
 *
 * ─── العقد ───
 *  - **نفس قرار التصنيف في zatcaReturn حرفياً** (عقد P41-L2): فاتورة واعية
 *    بالسطر (أي بند صريح — LineTaxP41.isLineAware) تنزل بنداً بنداً، والفاتورة
 *    التاريخية (لا بند صريح فيها) تنزل صفاً واحداً مجمّعاً من رأسها (صافي
 *    subtotal − discount وضريبة taxAmount المحجوزة) — فلا انحراف بين الدفتر
 *    والملخصات أبداً (اختبار التوافقية في DeepRegisterRepoP43Test).
 *  - ضريبة البند الواعي = LineTaxP41.lineTax على النسبة الفعالة (نفس دالة
 *    الحفظ والتقرير والXML — صندوق رياضيات واحد منذ P41) — لا إعادة اشتقاق.
 *  - المبالغ قروش Long بلا أي تقريب؛ النسبة الفعالة Double كما يصدرها
 *    effectiveRate (بما فيها 0.0 للصفرية/المعفاة والصفر الصريح على قياسي).
 *  - الترتيب مستقر وحتمي: ترتيب الفواتير كما وردت من القارئ (تاريخ) وترتيب
 *    بنود الفاتورة كما وردت من الاستعلام (invoiceId, id) — نفس عرف الاستعلامين.
 *  - الملف نقي بلا أي اعتماد Android — JUnit مجرد (نمط LineTaxP41/ZatcaReturnP38).
 */
object DeepExportP43 {

    /** لقطة فاتورة للدفتر — نقيّة (بلا كيان Room) كي يبقى المحرك مجرداً */
    data class InvView(
        val id: Long,
        val number: String,
        val party: String,
        val dateMs: Long,
        val taxRate: Double,      // نسبة الرأس (إعداد المنشأة لحظة الإصدار)
        val subtotalP: Long,      // قروش
        val discountP: Long,      // قروش
        val taxAmountP: Long,     // قروش — المحجوز في الرأس
    )

    /** لقطة بند للدفتر — lineTotalP محسوب (qty × السعر − الخصم) بعقد الكيان */
    data class ItemView(
        val invoiceId: Long,
        val desc: String,
        val qty: Double,
        val unitPriceP: Long,
        val discountP: Long,
        val lineTotalP: Long,
        val taxKind: Int,
        val taxRate: Double,
    )

    /** صف دفتر واحد — بند واعٍ أو صف مجمّع لفاتورة تاريخية (desc فارغ عندها) */
    data class RegisterLine(
        val invoiceId: Long,
        val invoiceNo: String,
        val party: String,
        val dateMs: Long,
        val desc: String,
        val qty: Double,
        val unitPriceP: Long,
        val discountP: Long,
        val lineNetP: Long,       // صافي البند (بعد خصمه) — للصف المجمع: subtotal − discount
        val taxKind: Int,         // LineTaxP41.KIND_* — الصف المجمع قياسي 0
        val effectiveRate: Double,
        val lineVatP: Long,       // للصف المجمع: taxAmount المحجوز في الرأس
    )

    /** ملخص الدفتر — العدادات التي تعرضها بطاقة الواجهة فوق التصدير */
    data class RegisterTotals(val rows: Int, val netP: Long, val vatP: Long)

    /** حزمة الدفتر الكامل — صفوف البيع والمشتريات مع ملخصَيهما (عقد القراءة الموحد) */
    data class RegisterTotalsPair(
        val sales: List<RegisterLine>,
        val purchases: List<RegisterLine>,
        val salesTotals: RegisterTotals,
        val purchasesTotals: RegisterTotals,
    )

    /**
     * بناء صفوف الدفتر الحتمي من لقطات الفواتير وبنودها — نفس قرار zatcaReturn
     * (واعية بالسطر ⇔ بنودها كلها غير فارغة وأي بند صريح فيها).
     */
    fun registerRows(
        invoices: List<InvView>,
        itemsByInvoice: Map<Long, List<ItemView>>,
    ): List<RegisterLine> = invoices.flatMap { inv ->
        val lines = itemsByInvoice[inv.id].orEmpty()
        val lineAware = lines.isNotEmpty() &&
            LineTaxP41.isLineAware(lines.map { it.taxKind to it.taxRate })
        if (!lineAware) {
            listOf(
                RegisterLine(
                    invoiceId = inv.id, invoiceNo = inv.number, party = inv.party,
                    dateMs = inv.dateMs, desc = "", qty = 0.0,
                    unitPriceP = 0L, discountP = inv.discountP,
                    lineNetP = inv.subtotalP - inv.discountP,
                    taxKind = LineTaxP41.KIND_STANDARD,
                    effectiveRate = inv.taxRate,
                    lineVatP = inv.taxAmountP,
                )
            )
        } else {
            lines.map { ln ->
                RegisterLine(
                    invoiceId = inv.id, invoiceNo = inv.number, party = inv.party,
                    dateMs = inv.dateMs, desc = ln.desc, qty = ln.qty,
                    unitPriceP = ln.unitPriceP, discountP = ln.discountP,
                    lineNetP = ln.lineTotalP,
                    taxKind = ln.taxKind,
                    effectiveRate = LineTaxP41.effectiveRate(ln.taxKind, ln.taxRate, inv.taxRate),
                    lineVatP = LineTaxP41.lineTax(ln.lineTotalP, ln.taxKind, ln.taxRate, inv.taxRate),
                )
            }
        }
    }

    /** ملخص صفوف الدفتر — جمع قروش تام بلا تقريب */
    fun summarize(rows: List<RegisterLine>): RegisterTotals = RegisterTotals(
        rows = rows.size,
        netP = rows.sumOf { it.lineNetP },
        vatP = rows.sumOf { it.lineVatP },
    )
}
