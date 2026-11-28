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

    @Test fun yearlyOverrideWinsOverMonthlyTimes12() {
        val r = BudgetMath.annualProjection(mapOf("fuel" to 100L), mapOf("fuel" to 500L), 3, mapOf("fuel" to 5000L))
        assertEquals(5000L, r.single().annualBudget)
        assertFalse(r.single().projectedOver)
        val onlyOverride = BudgetMath.annualProjection(emptyMap(), emptyMap(), 6, mapOf("tax" to 700L))
        assertEquals(700L, onlyOverride.single().annualBudget)
        assertEquals(0L, onlyOverride.single().projected)
    }
}
