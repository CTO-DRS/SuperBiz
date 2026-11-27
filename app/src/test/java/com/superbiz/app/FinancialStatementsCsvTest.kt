package com.superbiz.app

import com.superbiz.app.data.db.AccountSum
import com.superbiz.app.domain.algo.FinancialStatementsCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinancialStatementsCsvTest {
    // رأس مال 1000 نقداً، بيع 300 نقداً بتكلفة 100 من مخزون 200 مشترى نقداً، مصروف 50 نقداً
    private val sums = listOf(
        AccountSum("1010", 1000_00 + 300_00 - 200_00 - 50_00, 0),
        AccountSum("1200", 200_00, 100_00),
        AccountSum("3010", 0, 1000_00),
        AccountSum("4010", 0, 300_00),
        AccountSum("5010", 100_00, 0),
        AccountSum("5020", 50_00, 0),
    )

    @Test
    fun `income and balance equation hold`() {
        val inc = FinancialStatementsCsv.income(sums) { it }
        assertEquals(300_00L, inc.totalRevenue)
        assertEquals(150_00L, inc.totalCosts)
        assertEquals(150_00L, inc.net)
        val bal = FinancialStatementsCsv.balance(sums) { it }
        assertEquals(1150_00L, bal.totalAssets)
        assertEquals(1150_00L, bal.totalEquity)
        assertEquals(0L, bal.difference)
    }

    @Test
    fun `csv has net income and zero check`() {
        val csv = FinancialStatementsCsv.build(
            FinancialStatementsCsv.income(sums) { it }, FinancialStatementsCsv.balance(sums) { it })
        assertTrue(csv.startsWith("\uFEFF"))
        assertTrue(csv.contains("income,net_income,,,150.00"))
        assertTrue(csv.contains("balance,check,DIFFERENCE,,0.00"))
    }
}
