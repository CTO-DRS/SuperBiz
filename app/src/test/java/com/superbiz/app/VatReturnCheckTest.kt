package com.superbiz.app

import com.superbiz.app.domain.ZatcaReturnP38
import com.superbiz.app.domain.algo.VatReturnCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VatReturnCheckTest {
    @Test fun builtReportIsConsistent() {
        val r = ZatcaReturnP38.build(
            listOf(ZatcaReturnP38.SaleRow(15.0, 100_00, 15_00)),
            listOf(ZatcaReturnP38.PurchRow(40_00, 6_00)),
        )
        assertEquals(emptyList<String>(), VatReturnCheck.issues(r))
    }

    @Test fun detectsBrokenTotals() {
        val r = ZatcaReturnP38.build(
            listOf(ZatcaReturnP38.SaleRow(15.0, 100_00, 15_00)),
            listOf(ZatcaReturnP38.PurchRow(40_00, 6_00)),
        )
        val bad = r.copy(salesNet = r.salesNet + 1, netVat = r.netVat + 5)
        val i = VatReturnCheck.issues(bad)
        assertTrue("SALES_SPLIT" in i)
        assertTrue("NET_VAT" in i)
    }

    @Test fun detectsWrongRateVat() {
        val r = ZatcaReturnP38.build(listOf(ZatcaReturnP38.SaleRow(15.0, 100_00, 30_00)), emptyList())
        assertTrue("RATE_MISMATCH" in VatReturnCheck.issues(r))
    }
}
