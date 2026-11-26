package com.superbiz.app.domain.algo

import com.superbiz.app.domain.ZatcaReturnP38

/**
 * تصدير الإقرار الضريبي الدوري CSV جاهزاً للتقديم/التسليم للمحاسب: صف لكل خانة (مبيعات خاضعة لكل نسبة،
 * صفرية، معفاة، مشتريات، صافي الضريبة). نقي (JVM)، UTF-8 مع BOM، مبالغ عشرية من القروش.
 */
object VatReturnCsv {
    val HEADERS = listOf("section", "line", "rate_percent", "net_amount", "vat_amount")

    fun build(r: ZatcaReturnP38.Report, fromDate: String, toDate: String): String {
        fun a(v: Long) = AccountingCsv.amount(v)
        val rows = mutableListOf<List<String>>()
        rows.add(listOf("period", "from", "", fromDate, ""))
        rows.add(listOf("period", "to", "", toDate, ""))
        r.byRate.forEach {
            rows.add(listOf("sales", "standard_rated", rateText(it.rate), a(it.net), a(it.vat)))
        }
        rows.add(listOf("sales", "zero_rated", "0", a(r.salesZeroNet), a(0)))
        rows.add(listOf("sales", "exempt", "", a(r.salesExemptNet), a(0)))
        rows.add(listOf("sales", "total_sales", "", a(r.salesNet), a(r.outputVat)))
        rows.add(listOf("purchases", "taxable_purchases", "", a(r.purchasesNet), a(r.inputVat)))
        rows.add(listOf("summary", "output_vat", "", "", a(r.outputVat)))
        rows.add(listOf("summary", "input_vat", "", "", a(r.inputVat)))
        rows.add(listOf("summary", if (r.netVat < 0) "net_vat_refundable" else "net_vat_payable", "", "", a(r.netVat)))
        return CsvKit.buildCsv(HEADERS, rows, bom = true)
    }

    private fun rateText(rate: Double): String =
        if (rate == Math.floor(rate)) rate.toLong().toString() else rate.toString()
}
