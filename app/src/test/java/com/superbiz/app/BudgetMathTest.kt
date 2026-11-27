package com.superbiz.app

import com.superbiz.app.domain.algo.BudgetMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetMathTest {
    @Test
    fun `over budget rows sort first and percentages use integers`() {
        val r = BudgetMath.compare(
            mapOf("rent" to 100_000L, "fuel" to 20_000L),
            mapOf("rent" to 50_000L, "fuel" to 30_000L, "misc" to 5_000L),
        )
        assertEquals("fuel", r.rows[0].category)
        assertTrue(r.rows[0].over)
        assertEquals(150, r.rows[0].usedPct)
        assertEquals(50, r.rows.first { it.category == "rent" }.usedPct)
        assertFalse(r.rows.first { it.category == "misc" }.hasBudget)
        assertEquals(null, r.rows.first { it.category == "misc" }.usedPct)
        assertEquals(120_000L, r.totalBudget)
        assertEquals(85_000L, r.totalActual)
        assertEquals(80_000L, r.totalBudgetedActual)
        assertEquals(1, r.overCount)
        assertEquals(1, r.warnCount)
        assertTrue(BudgetMath.Row("x", 100L, 80L).warn)
        assertFalse(BudgetMath.Row("x", 100L, 79L).warn)
    }
}
