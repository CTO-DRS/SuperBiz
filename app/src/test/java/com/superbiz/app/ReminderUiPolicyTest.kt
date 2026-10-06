package com.superbiz.app

import com.superbiz.app.domain.algo.ReminderUiPolicy
import com.superbiz.app.domain.algo.ReminderUiState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [P16-b] اختبارات منطق عرض بطاقة «التذكير اليومي» في شاشة الأذونات — JVM نقي
 * بلا أندرويد (نمط OverdueReminderPolicyTest).
 *
 * يغلق قاعدتين حيّتين في البطاقة الثامنة:
 * 1. صفّ التحذير الكهرماني يظهر فقط عند (مفعّل + بلا إذن دقيق) — لا قبلها ولا بعدها.
 * 2. صيغة الوقت 24 ساعة لا تنتج نصاً مكسوراً مهما كانت القيم المخزّنة.
 */
class ReminderUiPolicyTest {

    // ═══ deriveReminderState — فرع البطاقة الثامنة ═══

    @Test
    fun off_whenReminderDisabled_regardlessOfExactAlarm() {
        // التذكير موقوف ⇒ OFF حتى لو كان الإذن ممنوحاً — التحذير بلا تذكير مجدول بلا معنى
        assertEquals(ReminderUiState.OFF, ReminderUiPolicy.deriveReminderState(false, true))
        assertEquals(ReminderUiState.OFF, ReminderUiPolicy.deriveReminderState(false, false))
    }

    @Test
    fun onOk_whenEnabledAndExactAlarmGranted() {
        assertEquals(ReminderUiState.ON_OK, ReminderUiPolicy.deriveReminderState(true, true))
    }

    @Test
    fun onExactWarn_whenEnabledWithoutExactAlarm() {
        // الحالة الصادقة الوحيدة للتحذير: مجدول فعلاً لكن بلا إذن دقيق
        assertEquals(ReminderUiState.ON_EXACT_WARN, ReminderUiPolicy.deriveReminderState(true, false))
    }

    @Test
    fun branchesAreExactlyThree_andCoverAllInputs() {
        // الجدول الكامل 2×2 — لا مسار رابع ولا قيمة افتراضية خفية
        val all = setOf(
            ReminderUiPolicy.deriveReminderState(false, false),
            ReminderUiPolicy.deriveReminderState(false, true),
            ReminderUiPolicy.deriveReminderState(true, false),
            ReminderUiPolicy.deriveReminderState(true, true)
        )
        assertEquals(setOf(ReminderUiState.OFF, ReminderUiState.ON_OK, ReminderUiState.ON_EXACT_WARN), all)
    }

    // ═══ timeLabel — 24 ساعة بصيغة لاتينية ثابتة ═══

    @Test
    fun timeLabel_padsBothFields() {
        assertEquals("08:00", ReminderUiPolicy.timeLabel(8, 0))
        assertEquals("23:59", ReminderUiPolicy.timeLabel(23, 59))
        assertEquals("08:05", ReminderUiPolicy.timeLabel(8, 5))
    }

    @Test
    fun timeLabel_midnightAndNoon() {
        assertEquals("00:00", ReminderUiPolicy.timeLabel(0, 0))
        assertEquals("12:30", ReminderUiPolicy.timeLabel(12, 30))
    }

    @Test
    fun timeLabel_coercesOutOfRangeValues_insteadOfBrokenFormat() {
        // prefs فاسدة أو قيم يدوية لا تكسر العرض: 25:61 → 23:59 والسوالب → منتصف الليل
        assertEquals("23:59", ReminderUiPolicy.timeLabel(25, 61))
        assertEquals("00:00", ReminderUiPolicy.timeLabel(-3, -7))
    }
}
