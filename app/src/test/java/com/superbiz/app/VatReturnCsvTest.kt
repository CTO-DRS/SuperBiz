package com.superbiz.app

import com.superbiz.app.domain.ZatcaReturnP38
import com.superbiz.app.domain.algo.VatReturnCsv
import org.junit.Assert.assertTrue
import org.junit.Test

class VatReturnCsvTest {
    @Test
    fun `csv has totals and net payable`() {
        val r = ZatcaReturnP38.build(
            listOf(ZatcaReturnP38.SaleRow(15.0, 100_00, 15_00)),
            listOf(ZatcaReturnP38.PurchRow(40_00, 6_00)),
        )
        val csv = VatReturnCsv.build(r, "2026-10-01", "2026-12-31")
        assertTrue(csv.contains("standard_rated,15,100.00,15.00"))
        assertTrue(csv.contains("net_vat_payable,,,9.00"))
        assertTrue(csv.startsWith("\uFEFF"))
    }
}
