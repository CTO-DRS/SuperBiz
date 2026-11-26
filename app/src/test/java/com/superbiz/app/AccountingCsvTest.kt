package com.superbiz.app

import com.superbiz.app.data.db.AccountSum
import com.superbiz.app.data.db.LedgerExportRow
import com.superbiz.app.domain.algo.AccountingCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountingCsvTest {
    @Test fun amountFormatsPiasters() {
        assertEquals("0.05", AccountingCsv.amount(5))
        assertEquals("-0.05", AccountingCsv.amount(-5))
        assertEquals("1234.50", AccountingCsv.amount(123450))
    }

    @Test fun ledgerHasBomGuardsInjectionAndQuotes() {
        val rows = listOf(LedgerExportRow(0L, 1, "=HYPERLINK(\"x\"), a", "invoice", 7, "1010", 100, 0, null))
        val csv = AccountingCsv.ledger(rows, { "نقد" }, { "2026-01-01" })
        assertTrue(csv.startsWith("\uFEFFdate,entry_id"))
        val line = csv.split("\r\n")[1]
        assertTrue(line, line.contains("\"'=HYPERLINK(\"\"x\"\"), a\""))
        assertTrue(line.endsWith(",1010,نقد,1.00,0.00,"))
    }

    @Test fun trialBalanceTotalsBalance() {
        val csv = AccountingCsv.trialBalance(listOf(AccountSum("4010", 0, 500), AccountSum("1010", 500, 0))) { it }
        val lines = csv.split("\r\n")
        assertEquals("1010,1010,5.00,0.00,5.00", lines[1])
        assertEquals("TOTAL,,5.00,5.00,0.00", lines[3])
    }
}
