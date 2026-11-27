package com.superbiz.app.domain.algo

import java.util.TimeZone

/** يُرمى عند محاولة ترحيل أو تعديل قيد داخل فترة مقفلة. */
class PeriodLockedException(val day: Long, val lockedThrough: Long) :
    IllegalStateException("period locked: day $day <= lockedThrough $lockedThrough")

/**
 * حارس الإقفال داخل العملية: قيمة lockedThrough (epoch day) تُحمَّل عند الإقلاع وتُحدَّث عند الإقفال/إعادة الفتح.
 * الافتراضي null = لا إقفال ⇒ لا يتغير أي سلوك قائم. التاريخ في الدفتر epoch ملّي ثانية؛ يُحوَّل ليوم بتوقيت الجهاز.
 */
object PeriodLockGuard {
    @Volatile var lockedThrough: Long? = null

    /** epoch day لزمن ملّي ثانية في منطقة بإزاحة [offsetMillis] عن UTC. */
    fun dayOf(millis: Long, offsetMillis: Long): Long = Math.floorDiv(millis + offsetMillis, 86_400_000L)

    fun require(dateMillis: Long, offsetMillis: Long = TimeZone.getDefault().getOffset(dateMillis).toLong()) {
        val c = PeriodLock.check(dayOf(dateMillis, offsetMillis), lockedThrough)
        if (c is PeriodLock.Check.Blocked) throw PeriodLockedException(c.day, c.lockedThrough)
    }
}
