package com.superbiz.app

import com.superbiz.app.domain.algo.OverdueReminderPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * [P14-a] اختبارات منطق تذكير «العملاء الذين لم يُزاروا» — JVM نقي بلا أندرويد
 * (نمط ExactAlarmPolicyTest تماماً).
 *
 * المنطقة الزمنية مثبَّتة على America/New_York كي تكون الحسابات حتمية عبر أي
 * جهاز بناء، وكي تُختبر عتبة التوقيت الصيفي فعلياً (يوم الربيع ذو الـ23 ساعة).
 * المنطقة الأصلية تُستعاد في tearDown احتراماً لبقية الاختبارات في نفس الـJVM.
 */
class OverdueReminderPolicyTest {

    private val originalTz: TimeZone = TimeZone.getDefault()

    @Before
    fun pinTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTz)
    }

    // ═══ أدوات بناء لحظات (بمنطقة المثبَّتة) ═══

    /** لحظة محددة بالحقول — الثواني/الأجزاء صفر افتراضاً */
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, sec: Int = 0, ms: Int = 0): Long {
        val c = Calendar.getInstance()
        c.clear()
        c.set(year, month, day, hour, minute, sec)
        c.set(Calendar.MILLISECOND, ms)
        return c.timeInMillis
    }

    /** نفس يوم اللحظة المعطاة عند الساعة hour:minute */
    private fun sameDayAt(now: Long, hour: Int, minute: Int): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = now
        return at(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH), hour, minute)
    }

    /** بعد days يوماً (بحساب Calendar — يمرّ عبر فجوات التوقيت الصيفي بأمان) عند hour:minute */
    private fun daysLaterAt(now: Long, days: Int, hour: Int, minute: Int): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = now
        c.add(Calendar.DAY_OF_YEAR, days)
        return at(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH), hour, minute)
    }

    // ═══ nextTriggerAt — الحالات الست المطلوبة ═══

    @Test
    fun nextTrigger_today_whenTargetStillAhead() {
        // الآن 07:00 والموعد 08:00 — لم يحِن بعد ⇒ اليوم الثامنة
        val now = at(2025, Calendar.MARCH, 8, 7, 0)
        val trigger = OverdueReminderPolicy.nextTriggerAt(now, 8, 0)
        assertEquals(sameDayAt(now, 8, 0), trigger)
        assertTrue(trigger > now)
    }

    @Test
    fun nextTrigger_tomorrow_whenTargetPassed() {
        // الآن 09:30 والموعد 08:00 — حاز ⇒ غداً الثامنة
        val now = at(2025, Calendar.MARCH, 8, 9, 30)
        val trigger = OverdueReminderPolicy.nextTriggerAt(now, 8, 0)
        assertEquals(daysLaterAt(now, 1, 8, 0), trigger)
        assertTrue(trigger > now)
    }

    @Test
    fun nextTrigger_exactlyNow_isTomorrow_strictGreaterThan() {
        // الآن الثامنة تماماً — الحد صارم: اللحظة نفسها تعني غداً (وإلا عاد المنبّه
        // ليطلق في الحين نفسه دورةً بعد دورة — نمط ExactAlarmPolicy.schedulable)
        val now = sameDayAt(at(2025, Calendar.MARCH, 8, 5, 0), 8, 0)
        val trigger = OverdueReminderPolicy.nextTriggerAt(now, 8, 0)
        assertEquals(daysLaterAt(now, 1, 8, 0), trigger)
        assertTrue(trigger > now)
    }

    @Test
    fun nextTrigger_crossesMonth_jan31ToFeb1() {
        // 31 يناير 08:30 — شهر يناير 31 يوماً ⇒ الغد 1 فبراير (Calendar يُتولى اختلاف الأطوال)
        val now = at(2025, Calendar.JANUARY, 31, 8, 30)
        assertEquals(at(2025, Calendar.FEBRUARY, 1, 8, 0), OverdueReminderPolicy.nextTriggerAt(now, 8, 0))
    }

    @Test
    fun nextTrigger_crossesYear_dec31LateNightToJan1Midnight() {
        // 31 ديسمبر 23:59 وموعد 00:00 — حاز ⇒ أول دقيقة من السنة الجديدة
        val now = at(2025, Calendar.DECEMBER, 31, 23, 59)
        assertEquals(at(2026, Calendar.JANUARY, 1, 0, 0), OverdueReminderPolicy.nextTriggerAt(now, 0, 0))
    }

    @Test
    fun nextTrigger_midnightEdge_justAfterMidnightMeansTomorrow() {
        // منتصف الليل 00:00:00.000 تماماً: الحد الصارم ⇒ الغد 00:00
        val midnight = at(2025, Calendar.JUNE, 15, 0, 0)
        assertEquals(daysLaterAt(midnight, 1, 0, 0), OverdueReminderPolicy.nextTriggerAt(midnight, 0, 0))
        // أجزاء ثانية بعد منتصف الليل: توقيت اليوم حاز ⇒ الغد 00:00 كذلك
        val justAfter = midnight + 1
        assertEquals(daysLaterAt(midnight, 1, 0, 0), OverdueReminderPolicy.nextTriggerAt(justAfter, 0, 0))
    }

    // ═══ nextTriggerAt — إضافات صلابة ═══

    @Test
    fun nextTrigger_dstSpringForward_dayIs23Hours() {
        // نيويورك 2025-03-09: الساعة تقفز 02:00→03:00. الآن 09:00 بالأمس (EST, -5)
        // والموعد 08:00 اليوم (EDT, -4): على الساعة 23 ساعة، لكن بالـepoch 22 ساعة
        // فقط لأن الفجوة تُتخطى — nextTriggerAt يعيد لحظة مطلقة والفرق يعكسها صادقاً
        val now = at(2025, Calendar.MARCH, 8, 9, 0) // قبل الفجوة (EST, -5)
        val trigger = OverdueReminderPolicy.nextTriggerAt(now, 8, 0)
        assertEquals(at(2025, Calendar.MARCH, 9, 8, 0), trigger) // بعد الفجوة (EDT, -4)
        assertEquals(22L * 3_600_000L, trigger - now)
    }

    @Test
    fun nextTrigger_secondsAndMillisAlwaysZeroed() {
        val now = at(2025, Calendar.MARCH, 8, 7, 15, sec = 33, ms = 456)
        val c = Calendar.getInstance()
        c.timeInMillis = OverdueReminderPolicy.nextTriggerAt(now, 9, 5)
        assertEquals(9, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(5, c.get(Calendar.MINUTE))
        assertEquals(0, c.get(Calendar.SECOND))
        assertEquals(0, c.get(Calendar.MILLISECOND))
    }

    @Test
    fun nextTrigger_alwaysStrictlyFuture_forAnyTimeOfDay() {
        // أيّة ساعة/دقيقة: النتيجة صارماً في المستقبل — عقد الجدولة كله
        val now = at(2025, Calendar.MARCH, 8, 12, 0)
        for (h in 0..23) for (m in intArrayOf(0, 30)) {
            val trigger = OverdueReminderPolicy.nextTriggerAt(now, h, m)
            assertTrue("h=$h m=$m must be future", trigger > now)
        }
    }

    // ═══ shouldNotify ═══

    @Test
    fun shouldNotify_disabled_neverNotifies_evenWithOverdue() {
        assertFalse(OverdueReminderPolicy.shouldNotify(enabled = false, overdueCount = 5))
    }

    @Test
    fun shouldNotify_disabled_andZeroOverdue() {
        assertFalse(OverdueReminderPolicy.shouldNotify(enabled = false, overdueCount = 0))
    }

    @Test
    fun shouldNotify_enabled_butNoOverdue_noNoise() {
        assertFalse(OverdueReminderPolicy.shouldNotify(enabled = true, overdueCount = 0))
    }

    @Test
    fun shouldNotify_enabled_withOverdue_notifies() {
        assertTrue(OverdueReminderPolicy.shouldNotify(enabled = true, overdueCount = 3))
    }

    // ═══ الافتراضات ═══

    @Test
    fun defaults_are8AM_andMatchVisitReportDefaultThresholdContext() {
        assertEquals(8, OverdueReminderPolicy.DEFAULT_HOUR)
        assertEquals(0, OverdueReminderPolicy.DEFAULT_MINUTE)
    }

    // ═══ [P15-c] topOverdueNames — أسماء متن الإشعار الموسّع ═══

    @Test
    fun topOverdueNames_truncatesToMax_keepingFirstInOrder() {
        // 7 أسماء وسقف 5 — الأولى خمسة فقط بالترتيب نفسه
        val out = OverdueReminderPolicy.topOverdueNames(
            listOf("أحمد", "بدر", "جاسم", "دلال", "إيمان", "فهد", "غادة"), max = 5
        )
        assertEquals(listOf("أحمد", "بدر", "جاسم", "دلال", "إيمان"), out)
    }

    @Test
    fun topOverdueNames_fewerThanMax_allReturned() {
        // أقل من السقف — الكل يعود بلا قصّ
        val out = OverdueReminderPolicy.topOverdueNames(listOf("سالم", "نورة"), max = 5)
        assertEquals(listOf("سالم", "نورة"), out)
    }

    @Test
    fun topOverdueNames_dedupesKeepingFirstOccurrenceAndOrder() {
        // المكرر يُسقَط مهما تكرر — أول ظهور هو الباقي، والترتيب محفوظ
        val out = OverdueReminderPolicy.topOverdueNames(
            listOf("خالد", "سارة", "خالد", "ماجد", "سارة", "خالد"), max = 5
        )
        assertEquals(listOf("خالد", "سارة", "ماجد"), out)
    }

    @Test
    fun topOverdueNames_trimsWhitespace_dropsBlankNames() {
        // الفراغات الطرفية تُقصّ، والأسماء الفارغة بعد القص تُسقَط،
        // و«عمر» بعد القص يكرر الأول فيُسقَط هو الآخر
        val out = OverdueReminderPolicy.topOverdueNames(
            listOf("  عمر  ", "\t", "ليان\n", "   ", "عمر", " فهد "), max = 5
        )
        assertEquals(listOf("عمر", "ليان", "فهد"), out)
    }

    @Test
    fun topOverdueNames_emptyInput_emptyOutput() {
        assertTrue(OverdueReminderPolicy.topOverdueNames(emptyList(), max = 5).isEmpty())
    }

    @Test
    fun topOverdueNames_zeroOrNegativeMax_emptyOutput() {
        // سقف غير موجب ⇒ لا أسماء تُعرض إطلاقاً (حارس بلا استثناءات)
        assertTrue(OverdueReminderPolicy.topOverdueNames(listOf("أحمد"), max = 0).isEmpty())
        assertTrue(OverdueReminderPolicy.topOverdueNames(listOf("أحمد"), max = -3).isEmpty())
    }

    @Test
    fun topOverdueNames_defaultMaxIsFive() {
        // الافتراضي 5 — نفس سقف العرض في الإشعار (VisitReminder.onFired)
        val out = OverdueReminderPolicy.topOverdueNames(listOf("ن1", "ن2", "ن3", "ن4", "ن5", "ن6", "ن7"))
        assertEquals(listOf("ن1", "ن2", "ن3", "ن4", "ن5"), out)
    }
}
