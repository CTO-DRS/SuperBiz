package com.superbiz.app.domain.algo

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PeriodLockGuardTest {
    private val riyadh = 3 * 3_600_000L
    private val day = 86_400_000L

    @After fun reset() { PeriodLockGuard.lockedThrough = null }

    @Test fun dayConversionUsesZoneOffset() {
        // 2025-03-31 22:00 UTC is already 2025-04-01 01:00 in Riyadh
        val utc = PeriodLock.epochDay(2025, 3, 31) * day + 22 * 3_600_000L
        assertEquals(PeriodLock.epochDay(2025, 3, 31), PeriodLockGuard.dayOf(utc, 0))
        assertEquals(PeriodLock.epochDay(2025, 4, 1), PeriodLockGuard.dayOf(utc, riyadh))
        assertEquals(-1L, PeriodLockGuard.dayOf(-1L, 0)) // before 1970 floors, not truncates
    }

    @Test fun noLockMeansNothingChanges() {
        PeriodLockGuard.lockedThrough = null
        PeriodLockGuard.require(0L, 0L)
        PeriodLockGuard.require(Long.MAX_VALUE / 4, riyadh)
    }

    @Test fun lockedDayAndEarlierThrow_laterAllowed() {
        val end = PeriodLock.endOfMonthDay(2025, 3)
        PeriodLockGuard.lockedThrough = end
        try {
            PeriodLockGuard.require(end * day + 5 * 3_600_000L, 0)
            fail("expected PeriodLockedException")
        } catch (e: PeriodLockedException) {
            assertEquals(end, e.day); assertEquals(end, e.lockedThrough)
        }
        try { PeriodLockGuard.require((end - 40) * day, 0); fail() } catch (_: PeriodLockedException) {}
        PeriodLockGuard.require((end + 1) * day, 0)
    }

    @Test fun boundaryDependsOnZone() {
        val end = PeriodLock.endOfMonthDay(2025, 3)
        PeriodLockGuard.lockedThrough = end
        val t = (end + 1) * day - 2 * 3_600_000L // 22:00 UTC on the last locked day = 01:00 next day in Riyadh
        try { PeriodLockGuard.require(t, 0); fail() } catch (_: PeriodLockedException) {}
        PeriodLockGuard.require(t, riyadh)
        assertTrue(true)
    }
}
