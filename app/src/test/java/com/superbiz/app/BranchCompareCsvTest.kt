package com.superbiz.app

import com.superbiz.app.domain.algo.BranchCompareCsv
import com.superbiz.app.domain.algo.BranchCompareCsv.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchCompareCsvTest {
    @Test
    fun `sorted by profit with integer margin`() {
        val a = Row(1, "A", 1000_00, 600_00, 100_00, 50_00)
        val b = Row(2, "B", 500_00, 200_00, 50_00, 0)
        assertEquals(300_00L, a.profit)
        assertEquals(300L, a.marginTenthsPct)
        assertEquals(500L, b.marginTenthsPct)
        val csv = BranchCompareCsv.build(listOf(a, b))
        val lines = csv.removePrefix("\uFEFF").trim().lines()
        assertTrue(lines[1].startsWith("1,A,1000.00,600.00,100.00,300.00,30.0,"))
        assertTrue(lines[2].startsWith("2,B,500.00,200.00,50.00,250.00,50.0,"))
    }

    @Test
    fun `zero sales has blank margin and negative margin text`() {
        assertEquals("", BranchCompareCsv.marginText(Row(1, "x", 0, 0, 5, 0).marginTenthsPct))
        assertEquals("-0.5", BranchCompareCsv.marginText(-5))
        assertEquals("-12.3", BranchCompareCsv.marginText(-123))
    }
}
