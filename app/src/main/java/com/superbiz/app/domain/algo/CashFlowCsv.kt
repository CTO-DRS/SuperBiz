package com.superbiz.app.domain.algo

/** تصدير قائمة التدفق النقدي الشهري CSV للمحاسب: صف لكل شهر (افتتاحي، قبض، صرف، صافي، ختامي). نقي، UTF-8 مع BOM. */
object CashFlowCsv {
    val HEADERS = listOf("month", "opening", "inflow", "outflow", "net", "closing")

    fun build(months: List<CashFlowStatement.Month>): String {
        fun a(v: Long) = AccountingCsv.amount(v)
        val rows = months.map {
            listOf("%04d-%02d".format(it.year, it.month), a(it.opening), a(it.inflow), a(it.outflow), a(it.net), a(it.closing))
        }
        return CsvKit.buildCsv(HEADERS, rows, bom = true)
    }
}
