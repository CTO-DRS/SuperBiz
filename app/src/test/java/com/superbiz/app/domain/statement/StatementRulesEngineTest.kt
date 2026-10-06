package com.superbiz.app.domain.statement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * [P18-tests] اختبارات محرك قواعد كشف الحساب — قرارات نقيّة (nextRunAt/isDue/
 * targetParties/parseCustomDays/parseEventFlags/RetryPolicy) بلا أي اعتماد Android.
 * كل المواعيد تُبنى بمنطقة زمنية صريحة (UTC غالباً) لضمان الحتمية، وقضايا DST
 * تُختبر بـ America/New_York مع تأكيد ثبات الساعة المحلية لا الفارق المطلق.
 */
class StatementRulesEngineTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    /** بناء لحظة UTC صريحة */
    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(y, mo - 1, d, h, mi, 0)
        }.timeInMillis

    /** لقطة قاعدة مع أقل ما يلزم — القيم الاختيارية تُمرر صراحة عند الحاجة */
    private fun rule(
        frequency: String,
        hour: Int = 9,
        minute: Int = 0,
        weekday: Int? = null,
        dayOfMonth: Int? = null,
        eventFlagsJson: String? = null,
        threshold: Double? = null,
        lastRunAt: Long? = null,
        nextRunAt: Long? = null,
        partyMode: String = "ALL",
        partyIdsJson: String = "",
        customDays: Int? = null,
        enabled: Boolean = true
    ) = StatementRulesEngine.RuleSnapshot(
        id = 1L, partyMode = partyMode, partyIdsJson = partyIdsJson,
        frequency = frequency, weekday = weekday, dayOfMonth = dayOfMonth,
        hour = hour, minute = minute, periodPreset = "THIS_MONTH",
        channel = "SMTP", eventFlagsJson = eventFlagsJson, threshold = threshold,
        lastRunAt = lastRunAt, nextRunAt = nextRunAt, enabled = enabled,
        customDays = customDays
    )

    /** لقطة أحداث مصطنعة تُدار من الاختبار */
    private class FakeSnapshot(
        var newTx: Boolean = false,
        var overdue: Int = 0,
        var unpaid: Int = 0,
        var balance: Double = 0.0,
        var monthEnd: Boolean = false
    ) : StatementRulesEngine.EventSnapshot {
        override fun hasNewTx(partyId: Long, since: Long): Boolean = newTx
        override fun overdueCount(partyId: Long): Int = overdue
        override fun unpaidInvoiceCount(partyId: Long): Int = unpaid
        override fun balanceDue(partyId: Long): Double = balance
        override fun lastDayOfMonth(now: Long, tz: TimeZone): Boolean = monthEnd
    }

    // ───── nextRunAt: الدوري ─────

    @Test fun `daily - later today when time not passed`() {
        val after = utc(2026, 9, 24, 10)
        val next = StatementRulesEngine.nextRunAt(rule("DAILY", hour = 15), after, utc)
        assertEquals(utc(2026, 9, 24, 15), next)
    }

    @Test fun `daily - tomorrow when time passed (after-exclusively)`() {
        val after = utc(2026, 9, 24, 10)
        val next = StatementRulesEngine.nextRunAt(rule("DAILY", hour = 10), after, utc)
        assertEquals(utc(2026, 9, 25, 10), next)
    }

    @Test fun `weekly - aligns to target weekday`() {
        // 2026-09-24 خميس — الاثنين القادم 2026-09-28
        val after = utc(2026, 9, 24, 10)
        val next = StatementRulesEngine.nextRunAt(
            rule("WEEKLY", hour = 9, weekday = Calendar.MONDAY), after, utc
        )
        assertEquals(utc(2026, 9, 28, 9), next)
    }

    @Test fun `weekly - same day past time pushes a full week`() {
        // 2026-09-24 خميس؛ الساعة مضت — الخميس القادم
        val after = utc(2026, 9, 24, 10)
        val next = StatementRulesEngine.nextRunAt(
            rule("WEEKLY", hour = 9, weekday = Calendar.THURSDAY), after, utc
        )
        assertEquals(utc(2026, 10, 1, 9), next)
    }

    @Test fun `monthly - day 31 clamped to Feb 28 in a non-leap year`() {
        val after = utc(2026, 1, 31, 10)
        val next = StatementRulesEngine.nextRunAt(
            rule("MONTHLY", hour = 9, dayOfMonth = 31), after, utc
        )
        assertEquals(utc(2026, 2, 28, 9), next)
    }

    @Test fun `monthly - day 31 clamped to Feb 29 in a leap year`() {
        val after = utc(2024, 1, 31, 10)
        val next = StatementRulesEngine.nextRunAt(
            rule("MONTHLY", hour = 9, dayOfMonth = 31), after, utc
        )
        assertEquals(utc(2024, 2, 29, 9), next)
    }

    @Test fun `quarterly - May 31 lands Aug 31`() {
        val after = utc(2026, 5, 31, 10)
        val next = StatementRulesEngine.nextRunAt(
            rule("QUARTERLY", hour = 9, dayOfMonth = 31), after, utc
        )
        assertEquals(utc(2026, 8, 31, 9), next)
    }

    @Test fun `yearly - Feb 29 becomes Feb 28 next year`() {
        val after = utc(2024, 2, 29, 10)
        val next = StatementRulesEngine.nextRunAt(
            rule("YEARLY", hour = 9, dayOfMonth = 29), after, utc
        )
        assertEquals(utc(2025, 2, 28, 9), next)
    }

    @Test fun `custom - every N days from eventFlagsJson`() {
        // الدلالة الموثقة: الموعد الحالي بالساعة المحددة إن لم تكن قد مضت، وإلا
        // دفعة N يوماً كاملة (نمط بعد-حصرياً) — هنا الساعة 9 مضت عند 10:00
        val after = utc(2026, 9, 24, 10)
        val next = StatementRulesEngine.nextRunAt(
            rule("CUSTOM", hour = 9, eventFlagsJson = "{\"days\":7}"), after, utc
        )
        assertEquals(utc(2026, 10, 1, 9), next)
    }

    @Test fun `custom - explicit customDays wins over json`() {
        val after = utc(2026, 9, 24, 10)
        val next = StatementRulesEngine.nextRunAt(
            rule("CUSTOM", hour = 9, eventFlagsJson = "{\"days\":7}", customDays = 2), after, utc
        )
        assertEquals(utc(2026, 9, 26, 9), next)
    }

    @Test fun `custom - same day fires when the time has not passed yet`() {
        val after = utc(2026, 9, 24, 10)
        val next = StatementRulesEngine.nextRunAt(
            rule("CUSTOM", hour = 12, eventFlagsJson = "{\"days\":7}"), after, utc
        )
        assertEquals(utc(2026, 9, 24, 12), next)
    }

    @Test fun `event frequency has no schedule (zero)`() {
        assertEquals(
            0L,
            StatementRulesEngine.nextRunAt(rule("EVENT", eventFlagsJson = "[\"MONTH_END\"]"), utc(2026, 9, 24, 10), utc)
        )
    }

    @Test fun `unknown frequency has no schedule (zero)`() {
        assertEquals(0L, StatementRulesEngine.nextRunAt(rule("WAT"), utc(2026, 9, 24, 10), utc))
    }

    @Test fun `daily keeps local hour across DST spring-forward`() {
        val ny = TimeZone.getTimeZone("America/New_York")
        // 2026-03-07 15:00Z = 10:00 EST (قبل التبديل بيوم) — القادم 2026-03-08 10:00 محلي
        val after = utc(2026, 3, 7, 15)
        val next = StatementRulesEngine.nextRunAt(rule("DAILY", hour = 10), after, ny)
        val c = Calendar.getInstance(ny).apply { timeInMillis = next }
        assertEquals(10, c.get(Calendar.HOUR_OF_DAY))
        // الفارق الحقيقي 23 ساعة وليس 24 — دليل احترام DST
        assertEquals(23L * 3_600_000L, next - after)
    }

    // ───── isDue ─────

    @Test fun `periodic due when nextRunAt passed`() {
        val now = utc(2026, 9, 24, 10)
        assertTrue(StatementRulesEngine.isDue(rule("DAILY", nextRunAt = now - 1), now, utc, null))
    }

    @Test fun `periodic not due when nextRunAt future`() {
        val now = utc(2026, 9, 24, 10)
        assertFalse(StatementRulesEngine.isDue(rule("DAILY", nextRunAt = now + 1), now, utc, null))
    }

    @Test fun `periodic never scheduled is not due`() {
        assertFalse(StatementRulesEngine.isDue(rule("DAILY", nextRunAt = null), utc(2026, 9, 24, 10), utc, null))
    }

    @Test fun `event month_end fires only on the last day`() {
        val now = utc(2026, 9, 30, 10) // آخر يوم في أيلول
        val r = rule("EVENT", eventFlagsJson = "[\"MONTH_END\"]")
        assertTrue(StatementRulesEngine.isDue(r, now, utc, FakeSnapshot(monthEnd = true)))
        assertFalse(StatementRulesEngine.isDue(r, utc(2026, 9, 29, 10), utc, FakeSnapshot(monthEnd = false)))
    }

    @Test fun `event threshold fires at equality and above only`() {
        val now = utc(2026, 9, 24, 10)
        val r = rule("EVENT", eventFlagsJson = "[\"THRESHOLD\"]", threshold = 100.0)
        assertTrue(StatementRulesEngine.isDue(r, now, utc, FakeSnapshot(balance = 100.0), listOf(7L)))
        assertFalse(StatementRulesEngine.isDue(r, now, utc, FakeSnapshot(balance = 99.99), listOf(7L)))
    }

    @Test fun `event with null snapshot is conservatively not due`() {
        val r = rule("EVENT", eventFlagsJson = "[\"NEW_TX\"]")
        assertFalse(StatementRulesEngine.isDue(r, utc(2026, 9, 24, 10), utc, null))
    }

    @Test fun `event with no flags is not due`() {
        assertFalse(
            StatementRulesEngine.isDue(
                rule("EVENT", eventFlagsJson = null),
                utc(2026, 9, 24, 10), utc, FakeSnapshot(newTx = true)
            )
        )
    }

    @Test fun `event ran-today guard suppresses re-fire on the same local day`() {
        val now = utc(2026, 9, 30, 10)
        val r = rule("EVENT", eventFlagsJson = "[\"MONTH_END\"]", lastRunAt = utc(2026, 9, 30, 6))
        assertFalse(StatementRulesEngine.isDue(r, now, utc, FakeSnapshot(monthEnd = true)))
        // أمس لا يساوي اليوم — يعود مؤهلاً
        val r2 = rule("EVENT", eventFlagsJson = "[\"MONTH_END\"]", lastRunAt = utc(2026, 9, 29, 6))
        assertTrue(StatementRulesEngine.isDue(r2, now, utc, FakeSnapshot(monthEnd = true)))
    }

    // ───── نطاق الأطراف والتحليل ─────

    @Test fun `targetParties - ALL expands, SELECTED and SINGLE read json`() {
        val all = StatementRulesEngine.targetParties(rule("DAILY"), listOf(1L, 2L, 3L))
        assertEquals(listOf(1L, 2L, 3L), all)
        assertEquals(
            listOf(1L, 3L),
            StatementRulesEngine.targetParties(rule("DAILY", partyMode = "SELECTED", partyIdsJson = "[1,3]"), emptyList())
        )
        assertEquals(
            listOf(5L),
            StatementRulesEngine.targetParties(rule("DAILY", partyMode = "SINGLE", partyIdsJson = "[5]"), emptyList())
        )
    }

    @Test fun `targetParties - corrupt mode yields empty (fail-closed)`() {
        assertTrue(
            StatementRulesEngine.targetParties(rule("DAILY", partyMode = "WEIRD", partyIdsJson = "[1]"), listOf(1L))
                .isEmpty()
        )
    }

    @Test fun `parsePartyIds extracts positive numbers with dedupe in order`() {
        assertEquals(listOf(1L, 2L, 3L), StatementRulesEngine.parsePartyIds("[1, 2,3]"))
        assertEquals(listOf(9L), StatementRulesEngine.parsePartyIds("9"))
        assertEquals(emptyList<Long>(), StatementRulesEngine.parsePartyIds("abc"))
        assertEquals(listOf(4L), StatementRulesEngine.parsePartyIds("[4,4,-4]"))
    }

    @Test fun `parseEventFlags - quoted array, bare text, unknown keys ignored`() {
        assertEquals(2, StatementRulesEngine.parseEventFlags("[\"MONTH_END\",\"THRESHOLD\"]").size)
        assertEquals(1, StatementRulesEngine.parseEventFlags("[\"BOGUS\",\"NEW_TX\"]").size)
        assertEquals(setOf(StatementRulesEngine.RuleEvent.MONTH_END), StatementRulesEngine.parseEventFlags("month_end"))
        assertTrue(StatementRulesEngine.parseEventFlags("").isEmpty())
        assertTrue(StatementRulesEngine.parseEventFlags(null).isEmpty())
    }

    @Test fun `parseCustomDays - range 1 to 3650 only`() {
        assertEquals(30, StatementRulesEngine.parseCustomDays("{\"days\":30}"))
        assertEquals(null, StatementRulesEngine.parseCustomDays("{\"days\":0}"))
        assertEquals(null, StatementRulesEngine.parseCustomDays("{\"days\":5000}"))
        assertEquals(null, StatementRulesEngine.parseCustomDays(null))
    }

    // ───── RetryPolicy ─────

    @Test fun `retry policy - only FAILED or RETRYING under the cap`() {
        val p = RetryPolicy
        assertTrue(p.shouldAutoRetry("FAILED", 2, 3))
        assertTrue(p.shouldAutoRetry("RETRYING", 1, 3))
        assertFalse(p.shouldAutoRetry("FAILED", 3, 3))
        assertFalse(p.shouldAutoRetry("SENT", 0, 3))
        assertFalse(p.shouldAutoRetry("PENDING", 0, 3))
        assertFalse(p.shouldAutoRetry("PROCESSING", 0, 3))
        assertFalse(p.shouldAutoRetry("CANCELLED", 0, 3))
        assertFalse(p.shouldAutoRetry("FAILED", 0, 0))
        assertFalse(p.shouldAutoRetry("WAT", 0, 3))
    }

    @Test fun `retry backoff - exponential 5min doubling capped at 6h`() {
        val p = RetryPolicy
        assertEquals(5L * 60_000L, p.nextDelayMs(0))
        assertEquals(10L * 60_000L, p.nextDelayMs(1))
        assertEquals(80L * 60_000L, p.nextDelayMs(4))
        assertEquals(320L * 60_000L, p.nextDelayMs(6))
        assertEquals(6L * 3_600_000L, p.nextDelayMs(7))          // 640 دقيقة — سقف 6 ساعات
        assertEquals(6L * 3_600_000L, p.nextDelayMs(50))
        assertEquals(5L * 60_000L, p.nextDelayMs(-3))            // سالبة تصير صفراً
    }
}
