package com.superbiz.app.domain

// ═══════════════════════════════════════════════════════════════
// منطق نقي لموجة التقارير (وظائف 16، 17، 18، 19) —
// بلا أندرويد وقابل للاختبار وحداتياً في InventoryReportsP4Test.
// ═══════════════════════════════════════════════════════════════

/**
 * وظيفة 16/19 — إحصاء فترة موحّد للمقارنة والترشيح.
 * profit: Double? — null يعني «لا أساس تكلفة» (لا ربح قابل للعرض بصدق).
 */
data class PeriodStats(
    val sales: Double,
    val profit: Double?,
    val invoiceCount: Int
) {
    val avgInvoice: Double?
        get() = if (invoiceCount > 0) sales / invoiceCount else null
}

/** صف أعلى المنتجات ربحية (وظيفة 17): margin = null عندما لا توجد تكلفة أساس */
data class ProfitRow(
    val productId: Long,
    val name: String,
    val revenue: Double,
    val margin: Double?
)

/**
 * وظيفة 16 — مقارنة فترتين: نسبة تغير وسهم اتجاه لكل مؤشر.
 * القاعدة الصادقة: سابق ≤ 0 وحالي > 0 → لا نسبة (null يُعرض «—»)؛
 * كلاهما ≤ 0 → نسبة 0 واتجاه ثابت؛ الربح بلا أساس تكلفة في الفترتين → غير قابل للقياس.
 */
object PeriodCompare {

    data class MetricChange(
        val current: Double,
        val previous: Double,
        val ratio: Double?,   // نسبة التغير % أو null = لا أساس
        val direction: Int    // 1 صاعد / -1 نازل / 0 ثابت
    )

    /** نسبة التغير المئوية بين قيمتين — سابق ≤ 0 وحالي > 0 → null (قسمة على صفر صادقة) */
    fun ratioChange(current: Double, previous: Double): Double? = when {
        previous <= 0.0 && current <= 0.0 -> 0.0
        previous <= 0.0 -> null
        else -> (current - previous) / previous * 100.0
    }

    /** سهم الاتجاه: 1 صاعد، -1 نازل، 0 ثابت */
    fun direction(current: Double, previous: Double): Int = when {
        current > previous -> 1
        current < previous -> -1
        else -> 0
    }

    fun metric(current: Double, previous: Double): MetricChange =
        MetricChange(current, previous, ratioChange(current, previous), direction(current, previous))

    /** مؤشر الربح: كلا الفترتين بلا أساس تكلفة → غير قابل للقياس (ratio=null, اتجاه 0) */
    fun profitMetric(current: Double?, previous: Double?): MetricChange =
        if (current == null && previous == null) MetricChange(0.0, 0.0, null, 0)
        else metric(current ?: 0.0, previous ?: 0.0)

    /** أربعة مؤشرات: المبيعات، الربح، عدد الفواتير، متوسط الفاتورة */
    fun compare(current: PeriodStats, previous: PeriodStats): List<MetricChange> = listOf(
        metric(current.sales, previous.sales),
        profitMetric(current.profit, previous.profit),
        metric(current.invoiceCount.toDouble(), previous.invoiceCount.toDouble()),
        metric(current.avgInvoice ?: 0.0, previous.avgInvoice ?: 0.0)
    )

    /** الإخفاء الصادق: لا فواتير في الشهرين → لا مقارنة تُعرض */
    fun hasData(current: PeriodStats, previous: PeriodStats): Boolean =
        current.invoiceCount > 0 || previous.invoiceCount > 0
}

/**
 * وظيفة 18 — بنّاء نص ملخص التقرير (مبيعات/مصروفات/ربح/ذمم) للنسخ والمشاركة.
 */
object ReportSummaryText {

    /**
 * : عناوين قابلة للتوطين — الافتراضي عربي لتوافق
 * الاستدعاءات والاختبارات، والواجهة تمرر نصوص الموارد.
*/
    data class Labels(
        val title: String = "📊 ملخص التقرير — %1\$s",
        val sales: String = "المبيعات",
        val expenses: String = "المصروفات",
        val profit: String = "صافي الربح",
        val debts: String = "ذمم العملاء المفتوحة"
    )

    fun build(
        sales: Double,
        expenses: Double,
        profit: Double,
        debts: Double,
        periodLabel: String,
        currency: String,
        labels: Labels = Labels()
    ): String {
        val sb = StringBuilder()
        sb.appendLine(labels.title.replace("%1\$s", periodLabel))
        sb.appendLine("——————————")
        sb.appendLine("${labels.sales}: ${com.superbiz.app.util.Money.num(sales)} $currency")
        sb.appendLine("${labels.expenses}: ${com.superbiz.app.util.Money.num(expenses)} $currency")
        sb.appendLine("${labels.profit}: ${com.superbiz.app.util.Money.num(profit)} $currency")
        sb.appendLine("${labels.debts}: ${com.superbiz.app.util.Money.num(debts)} $currency")
        return sb.toString().trimEnd()
    }
}

/**
 * وظيفة 19 — ترشيح التصنيف: التصنيفات على مستوى المنتج (Product.category) فقط،
 * فالترشيح يُطبَّق على بنود الفواتير ذات productId ضمن تصنيف مختار.
 */
object CategoryScope {

    /** مرجع بند خفيف قابل للاختبار — يُبنى من InvoiceItem في المستودع */
    data class ItemRef(
        val productId: Long?,
        val qty: Double,
        val unitPrice: Double,
        val lineTotal: Double
    )

    /** بنود التصنيف المختار فقط؛ null productId (بيع حر) يُستبعد دائماً */
    fun select(ids: Set<Long>, items: List<ItemRef>): List<ItemRef> =
        if (ids.isEmpty()) emptyList()
        else items.filter { it.productId != null && it.productId in ids }

    /**
     * إحصاء الفترة من بنود مُرشّحة: المبيعات = Σ lineTotal،
     * [P6-M29 إصلاح] الربح = Σ (lineTotal − تكلفة الكمية) — كان يُحسب من
     * unitPrice×qty بينما المبيعات من lineTotal، فأي خصم على السطر كان يضخّم
     * الربح (الربح المعلن أعلى من الواقع). الآن الأساس موحّد على lineTotal،
     * وبلا أي تكلفة معروفة → profit = null (صدق فوق تجميل).
     */
    fun aggregate(
        items: List<ItemRef>,
        invoiceCount: Int,
        costOf: (Long) -> Double?
    ): PeriodStats {
        val sales = items.sumOf { it.lineTotal }
        var margin = 0.0
        var hasCost = false
        for (it in items) {
            val pid = it.productId ?: continue
            val c = costOf(pid)
            if (c != null) {
                margin += it.lineTotal - c * it.qty
                hasCost = true
            }
        }
        return PeriodStats(
            sales = com.superbiz.app.util.Money.round2(sales),
            profit = if (hasCost) com.superbiz.app.util.Money.round2(margin) else null,
            invoiceCount = invoiceCount
        )
    }
}
