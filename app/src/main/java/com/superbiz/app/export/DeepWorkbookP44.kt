package com.superbiz.app.export

import com.superbiz.app.domain.DeepExportP43

/**
 * [P44-K2] جولة 5 — مصنف الدفتر التفصيلي متعدد الأوراق: دفتر P43 كله في ملف
 * XLSX واحد بثلاث أوراق (ملخص الدفتر / المبيعات / المشتريات) يُكتب بكاتب
 * XlsxSheets القائم (نفس كاتب تقرير التقارير الكامل — R6-P4 بعروض أعمدة تلقائية
 * وإصلاحات P6-M30 وP20 تفرد الأسماء وتنظيف المحارف).
 *
 * ─── العقود ───
 *  - **نفس صفوف الدفتر حرفياً**: الأوراق التفصيلية تُبنى بـ deepRows — نفس
 *    دالة صفوف chips التصدير المنفرد (P43) بأعمدتها الإحدى عشرة وقرارها
 *    «الصف المجمع للتاريخية» — فالمصنف والتصدير المنفرد والتقرير الدوري
 *    أرقامهم واحدة بلا انحراف.
 *  - **الملخص أرقام البطاقة نفسها**: صفو الملخص (المبيعات/المشتريات) يقرآن
 *    RegisterTotals كما تعرضهما البطاقة — العدد صحيح والصافي والضريبة خلايا
 *    رقمية بالريال عبر محوّل money الممرَّر (Money.fromPiasters في الإنتاج).
 *  - **الأوراق الثلاث دائماً** حتى عند فراغ قائمة (رأس بلا صفوف) — الهيكل
 *    الصادق يظهر للمستخدم ويبقى الاختبار حتمياً.
 *  - الملف نقي بلا أي اعتماد Android (ExportSheet بيانات خالصة) — JUnit مجرد.
 */
object DeepWorkbookP44 {

    /** أسماء الأوراق وعناوين صفوف الملخص — من سلاسل الواجهة عند الاستدعاء */
    data class Labels(
        val summarySheet: String,   // اسم ورقة الملخص
        val salesSheet: String,     // اسم ورقة المبيعات
        val purchasesSheet: String, // اسم ورقة المشتريات
        val metricSales: String,    // سطر «المبيعات» في الملخص
        val metricPurchases: String,// سطر «المشتريات» في الملخص
    )

    /**
     * صفوف الدفتر التفصيلية — الأعمدة الإحدى عشرة نفسها بنمط P43 حرفياً:
     * الفاتورة/التاريخ/الطرف/الصنف/الكمية/السعر/الخصم/الصافي/الفئة/النسبة/الضريبة
     * — المبالغ خلايا رقمية بالريال عبر money، والفئة من خريطة سلاسل المنتقي.
     */
    fun deepRows(
        lines: List<DeepExportP43.RegisterLine>,
        catLabels: Map<Int, String>,
        money: (Long) -> Double,
        dateFmt: (Long) -> String,
    ): List<List<Any?>> = lines.map { ln ->
        listOf<Any?>(
            ln.invoiceNo,
            dateFmt(ln.dateMs),
            ln.party,
            ln.desc.ifBlank { "—" },
            ln.qty,
            money(ln.unitPriceP),
            money(ln.discountP),
            money(ln.lineNetP),
            catLabels[ln.taxKind] ?: "",
            ln.effectiveRate,
            money(ln.lineVatP),
        )
    }

    /**
     * بناء أوراق المصنف الكامل: [الملخص، المبيعات، المشتريات] — الترتيب ثابت
     * والأسماء تمر على تطهير/تفرد XlsxSheets عند الكتابة كي لا يُرفض المصنف.
     *
     * @param deepHeader    رأس الأوراق التفصيلية (11 عموداً — سلاسل البطاقة نفسها)
     * @param summaryHeader رأس ورقة الملخص (4 أعمدة: البند/العدد/الصافي/الضريبة)
     */
    fun sheets(
        pair: DeepExportP43.RegisterTotalsPair,
        deepHeader: List<String>,
        summaryHeader: List<String>,
        labels: Labels,
        catLabels: Map<Int, String>,
        money: (Long) -> Double,
        dateFmt: (Long) -> String,
    ): List<ExportSheet> {
        val summaryRows = listOf<List<Any?>>(
            listOf(labels.metricSales, pair.salesTotals.rows, money(pair.salesTotals.netP), money(pair.salesTotals.vatP)),
            listOf(labels.metricPurchases, pair.purchasesTotals.rows, money(pair.purchasesTotals.netP), money(pair.purchasesTotals.vatP)),
        )
        return listOf(
            ExportSheet(labels.summarySheet, summaryHeader, summaryRows),
            ExportSheet(labels.salesSheet, deepHeader, deepRows(pair.sales, catLabels, money, dateFmt)),
            ExportSheet(labels.purchasesSheet, deepHeader, deepRows(pair.purchases, catLabels, money, dateFmt)),
        )
    }
}
