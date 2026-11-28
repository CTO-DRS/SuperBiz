package com.superbiz.app

import com.superbiz.app.domain.algo.CashFlowCsv
import com.superbiz.app.domain.algo.CashFlowStatement
import org.junit.Assert.assertTrue
import org.junit.Test

class CashFlowCsvTest {
    @Test fun rowsAndHeader() {
        val csv = CashFlowCsv.build(listOf(CashFlowStatement.Month(2026, 3, 1000, 500, 200)))
        assertTrue(csv.startsWith("\uFEFF"))
        assertTrue(csv.contains("month,opening,inflow,outflow,net,closing"))
        assertTrue(csv.contains("2026-03,10.00,5.00,2.00,3.00,13.00"))
    }
}
