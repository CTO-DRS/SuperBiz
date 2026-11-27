package com.superbiz.app.domain.algo

import com.superbiz.app.data.db.AccountSum

/**
 * [Phase 2] القوائم المالية من الدفتر: قائمة دخل (إيرادات 4xxx − تكاليف/مصروفات 5xxx) وميزانية عمومية
 * (أصول 1xxx = التزامات 2xxx + حقوق ملكية 3xxx + أرباح محتجزة محسوبة). قروش Long، نقي (JVM)، CSV بـBOM.
 */
object FinancialStatementsCsv {
    data class Line(val code: String, val name: String, val amount: Long)

    data class Income(val revenue: List<Line>, val costs: List<Line>) {
        val totalRevenue: Long get() = revenue.sumOf { it.amount }
        val totalCosts: Long get() = costs.sumOf { it.amount }
        val net: Long get() = totalRevenue - totalCosts
    }

    data class Balance(val assets: List<Line>, val liabilities: List<Line>, val equity: List<Line>, val earnings: Long) {
        val totalAssets: Long get() = assets.sumOf { it.amount }
        val totalLiabilities: Long get() = liabilities.sumOf { it.amount }
        val totalEquity: Long get() = equity.sumOf { it.amount } + earnings
        /** صفر في دفاتر متوازنة (قيد مزدوج) */
        val difference: Long get() = totalAssets - totalLiabilities - totalEquity
    }

    private fun lines(sums: List<AccountSum>, prefix: Char, name: (String) -> String, creditNormal: Boolean) =
        sums.filter { it.account.startsWith(prefix) }.sortedBy { it.account }
            .map { Line(it.account, name(it.account), if (creditNormal) it.c - it.d else it.d - it.c) }

    fun income(period: List<AccountSum>, name: (String) -> String): Income =
        Income(lines(period, '4', name, true), lines(period, '5', name, false))

    fun balance(all: List<AccountSum>, name: (String) -> String): Balance {
        val inc = income(all, name)
        return Balance(lines(all, '1', name, false), lines(all, '2', name, true), lines(all, '3', name, true), inc.net)
    }

    val HEADERS = listOf("statement", "section", "account_code", "account_name", "amount")

    fun build(inc: Income, bal: Balance): String {
        val rows = mutableListOf<List<String>>()
        fun a(v: Long) = AccountingCsv.amount(v)
        inc.revenue.forEach { rows.add(listOf("income", "revenue", it.code, it.name, a(it.amount))) }
        rows.add(listOf("income", "revenue", "TOTAL", "", a(inc.totalRevenue)))
        inc.costs.forEach { rows.add(listOf("income", "costs", it.code, it.name, a(it.amount))) }
        rows.add(listOf("income", "costs", "TOTAL", "", a(inc.totalCosts)))
        rows.add(listOf("income", "net_income", "", "", a(inc.net)))
        bal.assets.forEach { rows.add(listOf("balance", "assets", it.code, it.name, a(it.amount))) }
        rows.add(listOf("balance", "assets", "TOTAL", "", a(bal.totalAssets)))
        bal.liabilities.forEach { rows.add(listOf("balance", "liabilities", it.code, it.name, a(it.amount))) }
        rows.add(listOf("balance", "liabilities", "TOTAL", "", a(bal.totalLiabilities)))
        bal.equity.forEach { rows.add(listOf("balance", "equity", it.code, it.name, a(it.amount))) }
        rows.add(listOf("balance", "equity", "RETAINED_EARNINGS", "", a(bal.earnings)))
        rows.add(listOf("balance", "equity", "TOTAL", "", a(bal.totalEquity)))
        rows.add(listOf("balance", "check", "DIFFERENCE", "", a(bal.difference)))
        return CsvKit.buildCsv(HEADERS, rows, bom = true)
    }
}
