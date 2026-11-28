package com.superbiz.app

import com.superbiz.app.domain.algo.CashFlowStatement
import com.superbiz.app.domain.algo.CashFlowStatement.Cash
import org.junit.Assert.assertEquals
import org.junit.Test

class CashFlowStatementTest {
    private fun ms(y: Int, m: Int, d: Int) = java.time.LocalDate.of(y, m, d).toEpochDay() * 86_400_000L + 43_200_000L

    @Test fun monthsRollOpeningToClosing() {
        val rows = listOf(
            Cash(ms(2026, 1, 20), 1000, 0),   // قبل النافذة
            Cash(ms(2026, 3, 5), 500, 0),
            Cash(ms(2026, 3, 9), 0, 200),
            Cash(ms(2026, 5, 1), 0, 100),
        )
        val r = CashFlowStatement.build(rows, ms(2026, 5, 15), 3)
        assertEquals(listOf(3, 4, 5), r.map { it.month })
        assertEquals(1000L, r[0].opening)
        assertEquals(500L, r[0].inflow)
        assertEquals(200L, r[0].outflow)
        assertEquals(1300L, r[0].closing)
        assertEquals(0L, r[1].net)
        assertEquals(1300L, r[1].opening)
        assertEquals(1200L, r[2].closing)
    }

    @Test fun yearBoundaryAndTimezone() {
        // 2026-12-31 23:30 UTC = 2027-01-01 في +03
        val late = java.time.LocalDate.of(2026, 12, 31).toEpochDay() * 86_400_000L + 23 * 3_600_000L + 30 * 60_000L
        val r = CashFlowStatement.build(listOf(Cash(late, 100, 0)), late, 2, 3 * 3_600_000L)
        assertEquals(listOf(12, 1), r.map { it.month })
        assertEquals(0L, r[0].inflow)
        assertEquals(100L, r[1].inflow)
    }
}
