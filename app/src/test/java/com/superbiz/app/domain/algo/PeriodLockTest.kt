package com.superbiz.app.domain.algo

import com.superbiz.app.domain.algo.PeriodLock.Change
import com.superbiz.app.domain.algo.PeriodLock.Check
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodLockTest {
    @Test fun epochDays() {
        assertEquals(0L, PeriodLock.epochDay(1970, 1, 1))
        assertEquals(19723L, PeriodLock.epochDay(2024, 1, 1))
        assertEquals(20444L, PeriodLock.epochDay(2025, 12, 22))
        assertEquals(-1L, PeriodLock.epochDay(1969, 12, 31))
    }

    @Test fun endOfMonth() {
        assertEquals(PeriodLock.epochDay(2024, 2, 29), PeriodLock.endOfMonthDay(2024, 2)) // leap
        assertEquals(PeriodLock.epochDay(2025, 2, 28), PeriodLock.endOfMonthDay(2025, 2))
        assertEquals(PeriodLock.epochDay(2025, 12, 31), PeriodLock.endOfMonthDay(2025, 12))
        assertEquals(PeriodLock.epochDay(2025, 4, 30), PeriodLock.endOfMonthDay(2025, 4))
    }

    @Test fun checkBoundary() {
        assertEquals(Check.Allowed, PeriodLock.check(100, null))
        assertEquals(Check.Allowed, PeriodLock.check(101, 100))
        assertEquals(Check.Blocked(100, 100), PeriodLock.check(100, 100)) // locked day itself is blocked
        assertEquals(Check.Blocked(5, 100), PeriodLock.check(5, 100))
    }

    @Test fun lockRules() {
        assertEquals(Change.Ok(50L), PeriodLock.lock(null, 50, today = 60))
        assertEquals(Change.Ok(55L), PeriodLock.lock(50, 55, today = 60))
        assertEquals(Change.Rejected("NOT_FORWARD"), PeriodLock.lock(50, 50, today = 60))
        assertEquals(Change.Rejected("NOT_FORWARD"), PeriodLock.lock(50, 40, today = 60))
        assertEquals(Change.Rejected("FUTURE_OR_OPEN_PERIOD"), PeriodLock.lock(null, 60, today = 60))
        assertEquals(Change.Rejected("FUTURE_OR_OPEN_PERIOD"), PeriodLock.lock(null, 99, today = 60))
    }

    @Test fun reopenRules() {
        assertEquals(Change.Ok(40L), PeriodLock.reopen(50, 40, true, "تصحيح فاتورة"))
        assertEquals(Change.Ok(null), PeriodLock.reopen(50, null, true, "x"))
        assertEquals(Change.Rejected("OWNER_ONLY"), PeriodLock.reopen(50, 40, false, "x"))
        assertEquals(Change.Rejected("REASON_REQUIRED"), PeriodLock.reopen(50, 40, true, "  "))
        assertEquals(Change.Rejected("NOT_LOCKED"), PeriodLock.reopen(null, 40, true, "x"))
        assertEquals(Change.Rejected("NOT_BACKWARD"), PeriodLock.reopen(50, 50, true, "x"))
        assertEquals(Change.Rejected("NOT_BACKWARD"), PeriodLock.reopen(50, 60, true, "x"))
    }

    @Test fun lockThenCheckFlow() {
        val end = PeriodLock.endOfMonthDay(2025, 3)
        val c = (PeriodLock.lock(null, end, today = end + 5) as Change.Ok).lockedThrough
        assertTrue(PeriodLock.check(end - 10, c) is Check.Blocked)
        assertEquals(Check.Allowed, PeriodLock.check(end + 1, c))
    }
}
