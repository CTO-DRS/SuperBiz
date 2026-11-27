package com.superbiz.app.domain.algo

/**
 * [Phase 2] إقفال الفترات المحاسبية (نقي JVM). التواريخ أيام منذ 1970 (epoch day).
 * lockedThrough = آخر يوم مقفل شاملاً (null = لا إقفال). أي قيد بتاريخ ≤ lockedThrough مرفوض.
 * الإقفال يتقدم للأمام فقط؛ التراجع (إعادة فتح) عملية صريحة للمالك وحده ويُسجَّل سببها.
 */
object PeriodLock {
    sealed class Check {
        object Allowed : Check()
        data class Blocked(val day: Long, val lockedThrough: Long) : Check()
    }

    /** هل يُسمح بترحيل/تعديل قيد بهذا اليوم؟ */
    fun check(day: Long, lockedThrough: Long?): Check =
        if (lockedThrough != null && day <= lockedThrough) Check.Blocked(day, lockedThrough) else Check.Allowed

    sealed class Change {
        data class Ok(val lockedThrough: Long?) : Change()
        data class Rejected(val reason: String) : Change()
    }

    /** إقفال حتى [newThrough] شاملاً: لا يتراجع ولا يتجاوز [today] (لا إقفال لفترة لم تنتهِ). */
    fun lock(current: Long?, newThrough: Long, today: Long): Change = when {
        newThrough >= today -> Change.Rejected("FUTURE_OR_OPEN_PERIOD")
        current != null && newThrough <= current -> Change.Rejected("NOT_FORWARD")
        else -> Change.Ok(newThrough)
    }

    /** إعادة فتح حتى [newThrough] (أو null لفتح الكل): للمالك فقط، بسبب مكتوب، وللخلف فقط. */
    fun reopen(current: Long?, newThrough: Long?, isOwner: Boolean, reason: String): Change = when {
        !isOwner -> Change.Rejected("OWNER_ONLY")
        reason.isBlank() -> Change.Rejected("REASON_REQUIRED")
        current == null -> Change.Rejected("NOT_LOCKED")
        newThrough != null && newThrough >= current -> Change.Rejected("NOT_BACKWARD")
        else -> Change.Ok(newThrough)
    }

    /** آخر يوم في الشهر (yearMonth: 1..12) كـ epoch day — لإقفال نهاية الشهر. */
    fun endOfMonthDay(year: Int, month: Int): Long {
        require(month in 1..12)
        val y = if (month == 12) year + 1 else year
        val m = if (month == 12) 1 else month + 1
        return epochDay(y, m, 1) - 1
    }

    // خوارزمية civil-to-days (Howard Hinnant)
    fun epochDay(y: Int, m: Int, d: Int): Long {
        val yy = if (m <= 2) y - 1 else y
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }
}
