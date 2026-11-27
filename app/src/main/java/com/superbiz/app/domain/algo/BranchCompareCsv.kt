package com.superbiz.app.domain.algo

/** [Phase 2] مقارنة الفروع CSV: مبيعات، تكلفة، مصروفات، ربح، هامش %، قيمة المخزون — مرتبة بالربح تنازلياً. نقي (JVM). */
object BranchCompareCsv {
    data class Row(val id: Long, val name: String, val sales: Long, val cogs: Long, val expenses: Long, val stockValue: Long) {
        val profit: Long get() = sales - cogs - expenses
        /** هامش الربح بعشر خانة مئوية صحيحة (بلا Double): 123 = 12.3% */
        val marginTenthsPct: Long? get() = if (sales > 0L) Math.floorDiv(profit * 1000L, sales) else null
    }

    val HEADERS = listOf("branch_id", "branch", "sales_net", "cogs", "expenses", "profit", "margin_percent", "stock_value")

    fun marginText(t: Long?): String = if (t == null) "" else (if (t < 0 && t > -10) "-" else "") + (t / 10).toString() + "." + Math.abs(t % 10).toString()

    fun build(rows: List<Row>): String {
        val sorted = rows.sortedWith(compareByDescending<Row> { it.profit }.thenBy { it.id })
        val body = sorted.map {
            listOf(it.id.toString(), it.name, AccountingCsv.amount(it.sales), AccountingCsv.amount(it.cogs),
                AccountingCsv.amount(it.expenses), AccountingCsv.amount(it.profit), marginText(it.marginTenthsPct),
                AccountingCsv.amount(it.stockValue))
        }
        return CsvKit.buildCsv(HEADERS, body, bom = true)
    }
}
