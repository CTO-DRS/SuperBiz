package com.superbiz.app

import com.superbiz.app.domain.algo.BudgetMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetAnnualTest {
    @Test fun projectsAndFlagsOver() {
        val r = BudgetMath.annualProjection(mapOf("rent" to 1000L, "fuel" to 100L), mapOf("rent" to 3000L, "fuel" to 500L), 3)
        val fuel = r.first { it.category == "fuel" }
        assertEquals(1200L, fuel.annualBudget)
        assertEquals(2000L, fuel.projected)
        assertTrue(fuel.projectedOver)
        assertFalse(r.first { it.category == "rent" }.projectedOver)
        assertEquals("fuel", r.first().category)
    }

    @Test fun clampsMonthsAndHandlesNoBudget() {
        val r = BudgetMath.annualProjection(emptyMap(), mapOf("x" to 120L), 0)
        assertEquals(1440L, r.single().projected)
        assertFalse(r.single().projectedOver)
    }
}
