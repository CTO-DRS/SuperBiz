package com.superbiz.app

import com.superbiz.app.domain.algo.ExpenseYoY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExpenseYoYTest {
    @Test fun comparesAndSortsByIncrease() {
        val r = ExpenseYoY.compare(
            mapOf("fuel" to 150L, "rent" to 1000L, "new" to 40L, "" to 5L),
            mapOf("fuel" to 100L, "rent" to 1200L, "old" to 30L)
        )
        assertEquals(listOf("fuel", "new", "old", "rent"), r.rows.map { it.category })
        assertEquals(50, r.rows.first { it.category == "fuel" }.changePct)
        assertEquals(-17, r.rows.first { it.category == "rent" }.changePct)
        assertNull(r.rows.first { it.category == "new" }.changePct)
        assertEquals(1190L, r.totalCurrent)
        assertEquals(1330L, r.totalPrevious)
    }
}
