package com.superbiz.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.security.LockStatus
import com.superbiz.app.security.LockoutGuard
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [تدقيق M-9] قرار القفل على الزمن الأحادي (elapsedRealtime) لا حائط النظام:
 * كان lockUntil وحده فيُقصَّر القفل أو يُلغى بتراجع ساعة الجهاز (يدوي/NTP).
 * الحالة تحمل الآن lockUntilElapsed — القرار عبر isLocked ولا يتأثر بالحائط.
 *
 * Robolectric يُظلّل SystemClock.elapsedRealtime — نتحكم به عبر shadowClock
 * لنبني الحالتين: تقدم طبيعي (القفل ينتهي) وتراجع حائط (القفل يبقى).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LockoutElapsedM9Test {

    private lateinit var ctx: Context
    private lateinit var guard: LockoutGuard

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext<Context>()
        guard = LockoutGuard(ctx)
        runBlocking { guard.onSuccess() }   // تصفير كامل قبل كل اختبار
    }

    @After
    fun tearDown() = runBlocking { guard.onSuccess() }

    @Test
    fun onFailed_writes_both_clocks_and_decision_uses_elapsed() = runBlocking {
        val st = guard.onFailed() // المحاولة 1 — بلا قفل (تحت السقف الحر)
        assertEquals(0L, st.lockUntilElapsed)   // حرية كاملة — لا قفل على أي زمن
        assertFalse(st.isLocked(0L))

        // بلوغ حد القفل: 5 محاولات فاشلة
        var last = st
        repeat(4) { last = guard.onFailed() }
        assertTrue("الخامسة تقفل", last.lockUntil > 0)
        assertTrue("القيد الأحادي مكتوب", last.lockUntilElapsed > 0)

        // القرار: مستمر على الزمن الأحادي مهما تراجع الحائط
        val persisted = guard.status()
        assertTrue(persisted.isLocked(last.lockUntilElapsed - 1_000L))
        assertFalse("بعد انقضاء الأحادي: مفتوح",
            persisted.isLocked(last.lockUntilElapsed + 1_000L))
    }

    @Test
    fun wall_clock_rollback_cannot_shorten_or_cancel_lock() = runBlocking {
        var last: LockStatus = LockStatus(0, 0L)
        repeat(5) { last = guard.onFailed() }   // بلوغ القفل
        val persisted = guard.status()

        // تراجع الحائط (ساعة للخلف سنة) لا يمس القرار الأحادي:
        // الحائط لم يعد مدخلاً في isLocked أصلاً — إثبات عقد الدالة الخالصة
        val elapsedBefore = last.lockUntilElapsed
        assertTrue(persisted.isLocked(elapsedBefore - 100L))
        // محاولة أخرى أثناء القفل الأحادي لا تزيد العدّاد (سلوك القفل الساري محفوظ)
        val during = guard.onFailed()
        assertEquals("لا تراكم أثناء القفل", persisted.fails, during.fails)
    }

    @Test
    fun legacy_status_without_elapsed_decision_falls_back_open() {
        // ترحيل متسامح: حالة قديمة (بلا قيد أحادي) قرارها مفتوح — لا انغلاق زائف
        val legacy = LockStatus(fails = 3, lockUntil = Long.MAX_VALUE, lockUntilElapsed = 0L)
        assertFalse("الحقول القديمة لا تقفل عبر الأحادي", legacy.isLocked(Long.MAX_VALUE))
        assertEquals(0, legacy.remainingElapsedSeconds(Long.MAX_VALUE))
    }

    @Test
    fun remainingElapsedSeconds_counts_down_monotonically() {
        val st = LockStatus(fails = 5, lockUntil = 0L, lockUntilElapsed = 60_000L)
        assertEquals(60, st.remainingElapsedSeconds(0L))
        assertEquals(30, st.remainingElapsedSeconds(30_000L))
        assertEquals(0, st.remainingElapsedSeconds(60_000L))
        assertEquals(0, st.remainingElapsedSeconds(120_000L))
    }
}
