package com.superbiz.app.work

import com.superbiz.app.data.db.StatementRuleEntity
import com.superbiz.app.domain.statement.StatementRulesEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * [P37-T-B] جدران اختبار طبقة work/ — StatementScheduleLogic (جدولة كشوف الحساب) —
 * JUnit4 نقي بلا أي اعتماد Android (الكائن المستهدف نقية بقرار P18-b).
 *
 * التغطية:
 * • tickAlignedDelay: المحاذاة على شبكة periodMinutes عبر مناطق زمنية صريحة
 *   (UTC / Asia/Riyadh / Pacific/Kiritimati / America/New_York حول انتقاليَّي DST
 *   في مارس ونوفمبر 2026) — العقد: 0 < التأخير <= periodMinutes*60_000 دائماً،
 *   الثواني/أجزاء الثانية مصفَّرة دائماً، واللحظة الصائبة تماماً تدفع دورة كاملة
 *   (بعد-حصرياً — نمط OverdueReminderPolicy) كي لا يُطلق العامل مرتين في الحين نفسه.
 * • toSnapshot: كل حقول StatementRuleEntity تُنسخ حرفياً + نقطة حدّ القروش→ريالات
 *   (Money.fromPiasters — قرار P33-P8) + اشتقاق customDays من eventFlagsJson.
 * • shouldRunRule: كل أنواع القواعد (يومي/أسبوعي/شهري/ربع/سنوي/مخصص/حدثي) في
 *   الحالات: قبل الأوان / في الوقت / بعد فوات طويل / قاعدة معطلة.
 * • parsePartyIds: JSON سليم، فارغ، مشوّه، null (التحليل المتسامح الموثق في المحرك).
 *
 * حتمية كاملة: كل الاستدعاءات تمرر المنطقة الزمنية صراحة (لا TimeZone.getDefault
 * إطلاقاً) وكل اللحظات تُبنى بحقول تقويم صريحة داخل المنطقة المعلنة نفسها.
 *
 * ملاحظتا توثيق (اكتشفتا أثناء التغطية — مفصلتان في تقرير P37-T-B):
 * 1) isDue/shouldRunRule لا يفحصان enabled إطلاقاً — القاعدة المعطلة بموعد مضٍ
 *    تُعاد «مستحقة» من الدالة النقية؛ الحرس الفعلي في العامل (dueRules/rulesOnce
 *    يرشّحان enabled). حارس طبقة-علوية موثق — يُختبر السلوك الفعلي هنا مع تعليق.
 * 2) Pacific/Kiritimati هو UTC+14 فعلياً (التعميد قال +13) — كل حسابات الاختبار
 *    نسبية للمنطقة الممررة فتصح بأي إزاحة.
 */
class StatementScheduleLogicTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")
    private val riyadh: TimeZone = TimeZone.getTimeZone("Asia/Riyadh")
    private val kiritimati: TimeZone = TimeZone.getTimeZone("Pacific/Kiritimati")
    private val newYork: TimeZone = TimeZone.getTimeZone("America/New_York")

    /** بناء لحظة صريحة داخل المنطقة المعطاة — الحقول هي مصدر الحقيقة لا الطرح الخام */
    private fun at(tz: TimeZone, y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0, ms: Int = 0): Long =
        Calendar.getInstance(tz).apply {
            clear()
            set(y, mo - 1, d, h, mi, s)
            set(Calendar.MILLISECOND, ms)
        }.timeInMillis

    /** دقيقة اليوم بالمنطقة المعطاة — [P18-int-fix] نفس الاشتقاق داخل tickAlignedDelay */
    private fun minuteOfDay(epoch: Long, tz: TimeZone): Int =
        Calendar.getInstance(tz).apply { timeInMillis = epoch }.let {
            it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
        }

    /** كيان قاعدة بأقل ما يلزم — القيم الاختيارية تُمرَّر صراحة عند الحاجة */
    private fun entity(
        frequency: String,
        nextRunAt: Long? = null,
        lastRunAt: Long? = null,
        enabled: Boolean = true,
        hour: Int = 9,
        minute: Int = 0,
        weekday: Int? = null,
        dayOfMonth: Int? = null,
        partyMode: String = "ALL",
        partyIdsJson: String = "",
        eventFlagsJson: String? = null,
        threshold: Long? = null
    ): StatementRuleEntity = StatementRuleEntity(
        id = 1L, name = "قاعدة اختبار", enabled = enabled,
        partyMode = partyMode, partyIdsJson = partyIdsJson, frequency = frequency,
        weekday = weekday, dayOfMonth = dayOfMonth, hour = hour, minute = minute,
        periodPreset = "THIS_MONTH", channel = "SMTP",
        eventFlagsJson = eventFlagsJson, threshold = threshold,
        lastRunAt = lastRunAt, nextRunAt = nextRunAt
    )

    /**
     * لقطة أحداث مصطنعة يديرها الاختبار — النافذة الزمنية صادقة (txAt يقارن بـsince)
     * وآخر-يوم-شهر حقيقي من التقويم بالمنطقة الممررة (لا علم ثابت) — نمط
     * StatementRulesEngineTest مع دقة أعلى كي تُختبر الحالات الزمنية فعلاً.
     */
    private class FakeSnapshot(
        var txAt: Long = 0L,       // لحظة «آخر حركة دفتر» وهمية — 0 = لا حركة
        var overdue: Int = 0,
        var unpaid: Int = 0,
        var balance: Double = 0.0
    ) : StatementRulesEngine.EventSnapshot {
        override fun hasNewTx(partyId: Long, since: Long): Boolean = txAt > since
        override fun overdueCount(partyId: Long): Int = overdue
        override fun unpaidInvoiceCount(partyId: Long): Int = unpaid
        override fun balanceDue(partyId: Long): Double = balance
        override fun lastDayOfMonth(now: Long, tz: TimeZone): Boolean {
            val c = Calendar.getInstance(tz).apply { timeInMillis = now }
            return c.get(Calendar.DAY_OF_MONTH) == c.getActualMaximum(Calendar.DAY_OF_MONTH)
        }
    }

    /** لقطة تحقق موحدة من عقد المحاذاة على نتيجة أي نداء */
    private fun assertTickContract(now: Long, delay: Long, periodMinutes: Long, tz: TimeZone) {
        assertTrue("التأخير يجب أن يكون موجباً", delay > 0)
        // سقف الدورة + ساعة: انتقال التوقيت الصيفي (فجوة الربيع) يجعل الفارق الحقيقي
        // بين نقطتي شبكة الجدار متجاوزاً للدورة بحجم الفجوة (حتى ساعة في نيويورك) —
        // المحاذاة عقد الجدار (الدقيقة/الشبكة) لا عقد المدة المطلقة
        assertTrue(
            "التأخير لا يتجاوز دورة كاملة + هامش فجوة DST",
            delay <= periodMinutes * 60_000L + 3_600_000L
        )
        val c = Calendar.getInstance(tz).apply { timeInMillis = now + delay }
        assertEquals("الثواني مصفَّرة", 0, c.get(Calendar.SECOND))
        assertEquals("أجزاء الثانية مصفَّرة", 0, c.get(Calendar.MILLISECOND))
        assertEquals(
            "النتيجة على شبكة مضاعفات الدورة",
            0L, minuteOfDay(now + delay, tz) % periodMinutes
        )
    }

    // ═════════ tickAlignedDelay — المحاذاة عبر المناطق الزمنية ═════════

    @Test
    fun `تأخير 15 دقيقة منتصف الدقيقة يقصف لأقرب نقطة قادمة في UTC`() {
        // الآن 10:07:23.456 — الدقيقة ليست على شبكة الـ15 ⇒ 10:15:00.000
        val now = at(utc, 2026, 9, 24, 10, 7, 23, 456)
        val delay = StatementScheduleLogic.tickAlignedDelay(now, 15L, utc)
        assertEquals(456_544L, delay) // 8 دقائق − 23.456 ثانية
        assertTickContract(now, delay, 15L, utc)
    }

    @Test
    fun `اللحظة على النقطة تماما تدفع دورة كاملة بعد حصريا`() {
        // 10:15:00.000 على النقطة تماماً — الحد صارم: الدورة القادمة كاملة (لا صفر)
        val now = at(utc, 2026, 9, 24, 10, 15)
        val delay = StatementScheduleLogic.tickAlignedDelay(now, 15L, utc)
        assertEquals(900_000L, delay)
        assertTickContract(now, delay, 15L, utc)
    }

    @Test
    fun `اللحظة على الدقيقة الصحيحة بثواني زائدة تقصف أولا ثم تدفع دورة كاملة`() {
        // 10:15:59.999: الدقيقة متصلة بالشبكة لكن الثواني تجبر القصف لأسفل فتصير
        // «الآن أو قبله» ⇒ دفع 15 دقيقة — التأخير يبقى ضمن العقد (<= 900_000)
        val now = at(utc, 2026, 9, 24, 10, 15, 59, 999)
        val delay = StatementScheduleLogic.tickAlignedDelay(now, 15L, utc)
        assertEquals(840_001L, delay)
        assertTickContract(now, delay, 15L, utc)
    }

    @Test
    fun `دورة 60 دقيقة تصفر الثواني وأجزاء الثانية وتحاذي الساعة`() {
        val now = at(utc, 2026, 1, 1, 10, 7, 23, 456)
        val delay = StatementScheduleLogic.tickAlignedDelay(now, 60L, utc)
        assertEquals(3_156_544L, delay) // 11:00:00.000 − 10:07:23.456
        assertTickContract(now, delay, 60L, utc)
    }

    @Test
    fun `محاذاة Asia-Riyadh بلا توقيت صيفي تنتج نفس قيم الشبكة`() {
        // 21:07:23.456 محلياً (+3 بلا DST) ⇒ 21:15:00.000 محلياً
        val now = at(riyadh, 2026, 5, 20, 21, 7, 23, 456)
        val delay = StatementScheduleLogic.tickAlignedDelay(now, 15L, riyadh)
        assertEquals(456_544L, delay)
        assertTickContract(now, delay, 15L, riyadh)
    }

    @Test
    fun `نفس اللحظة يعطي نفس التأخير عبر مناطق كاملة الساعة`() {
        // إزاحة كاملة الساعة (UTC+3) لا تنزلق شبكة الـ15 دقيقة: 18:07Z == 21:07 رياض
        val instant = at(utc, 2026, 5, 20, 18, 7, 23, 456)
        val viaUtc = StatementScheduleLogic.tickAlignedDelay(instant, 15L, utc)
        val viaRiyadh = StatementScheduleLogic.tickAlignedDelay(instant, 15L, riyadh)
        assertEquals(viaUtc, viaRiyadh)
        assertEquals(456_544L, viaUtc)
    }

    @Test
    fun `محاذاة Pacific-Kiritimati عالية الإزاحة تبقى صحيحة`() {
        // ملاحظة: كيريتيماتي UTC+14 فعلياً (التعميد قال +13) — الحساب نسبي للمنطقة
        // فتصح النتيجة بأي إزاحة: 13:30 محلياً بدورة ساعية ⇒ 14:00 محلياً
        val now = at(kiritimati, 2026, 7, 1, 13, 30)
        val delay = StatementScheduleLogic.tickAlignedDelay(now, 60L, kiritimati)
        assertEquals(1_800_000L, delay)
        assertTickContract(now, delay, 60L, kiritimati)
    }

    @Test
    fun `نيويورك الربيع 2026 الفجوة تتخطى إلى الساعة 3 صباحا`() {
        // 2026-03-08: 02:00 EST تقفز 03:00 EDT. الآن 01:50 ⇒ المرشح 02:00 محلياً
        // غير موجود — التقويم يتقدم على اللحظة الفعلية فتظهر 03:00 EDT والتأخير 10 دقائق
        val now = at(newYork, 2026, 3, 8, 1, 50)
        val delay = StatementScheduleLogic.tickAlignedDelay(now, 15L, newYork)
        assertEquals(600_000L, delay)
        assertEquals(
            3, Calendar.getInstance(newYork).apply { timeInMillis = now + delay }
                .get(Calendar.HOUR_OF_DAY)
        )
        assertTickContract(now, delay, 15L, newYork)
    }

    @Test
    fun `نيويورك الربيع 2026 مع ثوان يظل التأخير دقيقا حتى ميلي الثانية`() {
        // الآن 01:52:30.250 EST (06:52:30.250Z) — المرشح بعد الفجوة 07:00:00.000Z
        val now = at(newYork, 2026, 3, 8, 1, 52, 30, 250)
        val delay = StatementScheduleLogic.tickAlignedDelay(now, 15L, newYork)
        assertEquals(449_750L, delay)
        assertTickContract(now, delay, 15L, newYork)
    }

    @Test
    fun `نيويورك الخريف 2026 قبل الرجوع يعيد 1 صباحا بالحسم القياسي`() {
        // 2026-11-01: 02:00 EDT ترجع 01:00 EST. الآن 00:52:30 EDT ⇒ شبكة الجدار 01:00.
        // java.util.Calendar يحسم اللحظة الجدلية (01:00 المكررة) للوقت القياسي —
        // أي الورود الثاني 01:00 EST — فالفارق الحقيقي 67.5 دقيقة = 4_050_000ms.
        // سلوك JDK الموثق — حتمي ومقبول للمجدول (لا إطلاق مكرر لأن lastRunAt يحرس)
        val now = at(newYork, 2026, 11, 1, 0, 52, 30)
        val delay = StatementScheduleLogic.tickAlignedDelay(now, 15L, newYork)
        assertEquals(4_050_000L, delay)
        assertTickContract(now, delay, 15L, newYork)
    }

    @Test
    fun `نيويورك الخريف 2026 في الساعة المكررة الثانية يحاذي أيضا`() {
        // 06:20Z = 01:20 EST (الورود الثاني للساعة المكررة) ⇒ 01:30 EST بعد 10 دقائق
        val now = at(utc, 2026, 11, 1, 6, 20)
        val delay = StatementScheduleLogic.tickAlignedDelay(now, 15L, newYork)
        assertEquals(600_000L, delay)
        assertTickContract(now, delay, 15L, newYork)
    }

    @Test
    fun `العقد صفر اقل من التأخير وحده الدورة كاملة عبر DST وسواه`() {
        // مسح حتمي كثيف حول انتقاليَّي نيويورك 2026 + نافذة UTC: أي لحظة ودورة
        // 15 أو 60 دقيقة ⇒ 0 < التأخير <= الدورة، والنتيجة مصفَّرة وعلى الشبكة
        val periods = longArrayOf(15L, 60L)
        val windows = listOf(
            newYork to at(newYork, 2026, 3, 7, 0, 0),      // الربيع (فجوة 8 مارس)
            newYork to at(newYork, 2026, 10, 31, 12, 0),   // الخريف (رجوع 1 نوفمبر)
            utc to at(utc, 2026, 6, 30, 0, 0)              // بلا DST — مرجع
        )
        for ((tz, start) in windows) {
            for (p in periods) {
                var i = 0
                var now = start + 47_251L   // إزاحة ثابتة تُدخل ثوانٍ/أجزاء غير صفيرة
                while (i < 170) {           // ~48 ساعة بخطوة ~16.7 دقيقة
                    val delay = StatementScheduleLogic.tickAlignedDelay(now, p, tz)
                    assertTickContract(now, delay, p, tz)
                    now += 1_000_000L
                    i++
                }
            }
        }
    }

    @Test
    fun `دورة صفر أو سالبة تقيد إلى دقيقة واحدة`() {
        // coerceIn(1, 1440): الصفر يصير دقيقة — الآن 10:30:45 ⇒ 10:31:00
        val now = at(utc, 2026, 9, 24, 10, 30, 45)
        assertEquals(15_000L, StatementScheduleLogic.tickAlignedDelay(now, 0L, utc))
        assertEquals(15_000L, StatementScheduleLogic.tickAlignedDelay(now, -30L, utc))
    }

    @Test
    fun `دورة فوق 1440 تقيد إلى شبكة منتصف الليل`() {
        // 10_000 دقيقة تُقيَّد إلى 1440: 12:30 ⇒ منتصف الليل القادم = 11.5 ساعة
        val noon = at(utc, 2026, 9, 24, 12, 30)
        assertEquals(41_400_000L, StatementScheduleLogic.tickAlignedDelay(noon, 10_000L, utc))
        // منتصف الليل تماماً ⇒ بعد-حصرياً: دورة كاملة 24 ساعة
        val midnight = at(utc, 2026, 9, 24, 0, 0)
        assertEquals(86_400_000L, StatementScheduleLogic.tickAlignedDelay(midnight, 10_000L, utc))
    }

    // ═════════ toSnapshot — نسخ الكيان ولقطة التحويل ═════════

    @Test
    fun `toSnapshot ينسخ كل الحقول حرفيا من الكيان`() {
        val e = StatementRuleEntity(
            id = 9L, name = "قاعدة أسبوعية", enabled = false,
            partyMode = "SELECTED", partyIdsJson = "[3,8]", frequency = "WEEKLY",
            weekday = Calendar.SUNDAY, dayOfMonth = 15, hour = 14, minute = 45,
            periodPreset = "LAST_MONTH", templateId = "COR-03",
            signatureId = 21L, stampId = 33L, channel = "SMTP",
            eventFlagsJson = "{\"days\":14}", threshold = 15_000L,
            lastRunAt = 111_222_333L, nextRunAt = 444_555_666L
        )
        val s = StatementScheduleLogic.toSnapshot(e)
        assertEquals(9L, s.id)
        assertEquals("SELECTED", s.partyMode) // partyMode يدخل اللقطة — name لا (عقد اللقطة)
        assertEquals("[3,8]", s.partyIdsJson)
        assertEquals("WEEKLY", s.frequency)
        assertEquals(Calendar.SUNDAY, s.weekday)
        assertEquals(15, s.dayOfMonth)
        assertEquals(14, s.hour)
        assertEquals(45, s.minute)
        assertEquals("LAST_MONTH", s.periodPreset)
        assertEquals("SMTP", s.channel)
        assertEquals("{\"days\":14}", s.eventFlagsJson)
        assertEquals(111_222_333L, s.lastRunAt)
        assertEquals(444_555_666L, s.nextRunAt)
        assertEquals(false, s.enabled)
        assertEquals(14, s.customDays) // اشتقاق مدمج من eventFlagsJson
        assertEquals("COR-03", s.templateId)
        assertEquals(21L, s.signatureId)
        assertEquals(33L, s.stampId)
        assertEquals(150.0, s.threshold!!, 0.0) // 15_000 قروش → 150 ريال (نقطة الحد)
    }

    @Test
    fun `toSnapshot يحول العتبة من قروش إلى ريالات عبر fromPiasters`() {
        // [P33-P8]: الكيان قروش Long والمحرك يقارن بالريالات — 15_000 قروش = 150.0 ريال
        val with = StatementScheduleLogic.toSnapshot(entity("EVENT", threshold = 15_000L))
        assertEquals(150.0, with.threshold!!, 0.0)
        // قرش واحد = 0.01 ريال — نقطة الحد تمر عبر fromPiasters حصراً
        val one = StatementScheduleLogic.toSnapshot(entity("EVENT", threshold = 1L))
        assertEquals(0.01, one.threshold!!, 0.0)
        // عتبة غائبة تبقى غائبة
        val none = StatementScheduleLogic.toSnapshot(entity("EVENT", threshold = null))
        assertNull(none.threshold)
    }

    @Test
    fun `toSnapshot يشتق customDays من eventFlagsJson بالنطاق الموثق`() {
        assertEquals(
            14, StatementScheduleLogic.toSnapshot(entity("CUSTOM", eventFlagsJson = "{\"days\":14}")).customDays
        )
        assertEquals(
            3650, StatementScheduleLogic.toSnapshot(entity("CUSTOM", eventFlagsJson = "{\"days\":3650}")).customDays
        )
        // خارج 1..3650 يُعدّ فساداً ⇒ null (سقوط CUSTOM لاحقاً إلى 30 موثق في المحرك)
        assertNull(
            StatementScheduleLogic.toSnapshot(entity("CUSTOM", eventFlagsJson = "{\"days\":0}")).customDays
        )
        assertNull(
            StatementScheduleLogic.toSnapshot(entity("CUSTOM", eventFlagsJson = "{\"days\":5000}")).customDays
        )
        assertNull(StatementScheduleLogic.toSnapshot(entity("CUSTOM", eventFlagsJson = null)).customDays)
    }

    @Test
    fun `toSnapshot للكيان الأدنى يعيد الافتراضات كاملة`() {
        val s = StatementScheduleLogic.toSnapshot(entity("DAILY"))
        assertEquals(true, s.enabled)
        assertNull(s.weekday)
        assertNull(s.dayOfMonth)
        assertNull(s.eventFlagsJson)
        assertNull(s.threshold)
        assertNull(s.lastRunAt)
        assertNull(s.nextRunAt)
        assertNull(s.customDays)
        assertNull(s.templateId)
        assertNull(s.signatureId)
        assertNull(s.stampId)
    }

    // ═════════ shouldRunRule — كل الأنواع في كل الحالات ═════════

    /** الحالات الثلاث الزمنية للدوري: قبل الأوان / في الوقت / بعد فوات طويل */
    private fun assertPeriodicTiming(
        frequency: String,
        weekday: Int? = null,
        dayOfMonth: Int? = null,
        eventFlagsJson: String? = null
    ) {
        val now = at(utc, 2026, 9, 24, 10, 0)
        val base = entity(frequency, nextRunAt = now + 60_000L, weekday = weekday, dayOfMonth = dayOfMonth, eventFlagsJson = eventFlagsJson)
        // قبل الأوان: الموعد القادم بعد دقيقة ⇒ لا
        assertFalse(frequency, StatementScheduleLogic.shouldRunRule(base, now, null, utc, emptyList()))
        // في الوقت: الموعد الآن تماماً (<= now) ⇒ نعم — الحد شامل للمساواة
        assertTrue(
            frequency,
            StatementScheduleLogic.shouldRunRule(base.copy(nextRunAt = now), now, null, utc, emptyList())
        )
        // بعد فوات طويل: 40 يوماً مضت ⇒ نعم (المحرك يحكم بالموعد لا بقربه)
        assertTrue(
            frequency,
            StatementScheduleLogic.shouldRunRule(
                base.copy(nextRunAt = now - 40L * 86_400_000L), now, null, utc, emptyList()
            )
        )
    }

    @Test
    fun `يومي قبل الأوان وفي الوقت وبعد فوات طويل`() = assertPeriodicTiming("DAILY")

    @Test
    fun `أسبوعي قبل الأوان وفي الوقت وبعد فوات طويل`() =
        assertPeriodicTiming("WEEKLY", weekday = Calendar.THURSDAY)

    @Test
    fun `شهري قبل الأوان وفي الوقت وبعد فوات طويل`() =
        assertPeriodicTiming("MONTHLY", dayOfMonth = 24)

    @Test
    fun `ربع سنوي قبل الأوان وفي الوقت وبعد فوات طويل`() =
        assertPeriodicTiming("QUARTERLY", dayOfMonth = 24)

    @Test
    fun `سنوي قبل الأوان وفي الوقت وبعد فوات طويل`() =
        assertPeriodicTiming("YEARLY", dayOfMonth = 24)

    @Test
    fun `مخصص قبل الأوان وفي الوقت وبعد فوات طويل وبلا موعد`() {
        assertPeriodicTiming("CUSTOM", eventFlagsJson = "{\"days\":5}")
        // CUSTOM دوري أيضاً: بلا nextRunAt لا استحقاق أبداً (الجدول هو الحاكم)
        val now = at(utc, 2026, 9, 24, 10, 0)
        val never = entity("CUSTOM", nextRunAt = null, eventFlagsJson = "{\"days\":5}")
        assertFalse(StatementScheduleLogic.shouldRunRule(never, now, null, utc, emptyList()))
    }

    @Test
    fun `حدثي نهاية الشهر قبل الأوان وفي الوقت وبعد فوات طويل وبشغله اليوم`() {
        val now = at(utc, 2026, 9, 30, 10, 0) // آخر يوم في أيلول
        val rule = entity("EVENT", eventFlagsJson = "[\"MONTH_END\"]")
        // قبل الأوان: لسنا في آخر يوم ⇒ لا
        assertFalse(
            StatementScheduleLogic.shouldRunRule(rule, at(utc, 2026, 9, 29, 10, 0), FakeSnapshot(), utc)
        )
        // في الوقت: آخر يوم ولم تُشغَّل اليوم ⇒ نعم
        assertTrue(StatementScheduleLogic.shouldRunRule(rule, now, FakeSnapshot(), utc))
        // شُغلت اليوم نفسه ⇒ نافذة السكون اليومية تكبح إعادة الإطلاق
        val ranToday = entity("EVENT", lastRunAt = at(utc, 2026, 9, 30, 6, 0), eventFlagsJson = "[\"MONTH_END\"]")
        assertFalse(StatementScheduleLogic.shouldRunRule(ranToday, now, FakeSnapshot(), utc))
        // بعد فوات طويل: آخر تشغيل أمس ⇒ مؤهلة من جديد
        val ranYesterday = entity("EVENT", lastRunAt = at(utc, 2026, 9, 29, 6, 0), eventFlagsJson = "[\"MONTH_END\"]")
        assertTrue(StatementScheduleLogic.shouldRunRule(ranYesterday, now, FakeSnapshot(), utc))
    }

    @Test
    fun `حدثي حركة جديدة قبل الأوان وفي الوقت وبعد فوات طويل مع نافذة الصفر`() {
        val now = at(utc, 2026, 9, 24, 10, 0)
        val scoped = entity(
            "EVENT", lastRunAt = now - 3_600_000L, partyMode = "SELECTED",
            partyIdsJson = "[7]", eventFlagsJson = "[\"NEW_TX\"]"
        )
        // قبل الأوان: لا حركة بعد لحظة آخر تشغيل (آخر حركة أقدم من النافذة) ⇒ لا
        assertFalse(
            StatementScheduleLogic.shouldRunRule(scoped, now, FakeSnapshot(txAt = now - 2 * 3_600_000L), utc)
        )
        // في الوقت: حركة جديدة بعد آخر تشغيل ⇒ نعم (على الطرف المصرّح به)
        assertTrue(StatementScheduleLogic.shouldRunRule(scoped, now, FakeSnapshot(txAt = now), utc))
        // بلا لقطة ⇒ قرار محافظ: لا حكم حدثي
        assertFalse(StatementScheduleLogic.shouldRunRule(scoped, now, null, utc))
        // بعد فوات طويل: lastRunAt=null ⇒ النافذة منذ الصفر — أي حركة في التاريخ تكفي
        val neverRan = entity(
            "EVENT", lastRunAt = null, partyMode = "SELECTED",
            partyIdsJson = "[7]", eventFlagsJson = "[\"NEW_TX\"]"
        )
        assertTrue(StatementScheduleLogic.shouldRunRule(neverRan, now, FakeSnapshot(txAt = now), utc))
    }

    @Test
    fun `حدثي عتبة القروش تساوي الحد شامل والمحت التحويل من الكيان`() {
        val now = at(utc, 2026, 9, 24, 10, 0)
        // الكيان قروش: 25_000 قروش = 250.0 ريال — المساواة شاملة (قرار المحرك).
        // الأعلام الطرفية تُحقق على نطاق الأطراف — نمرر القائمة الكاملة كالعامل (ALL)
        val rule = entity("EVENT", eventFlagsJson = "[\"THRESHOLD\"]", threshold = 25_000L)
        assertTrue(
            StatementScheduleLogic.shouldRunRule(rule, now, FakeSnapshot(balance = 250.0), utc, listOf(1L, 2L))
        )
        assertFalse(
            StatementScheduleLogic.shouldRunRule(rule, now, FakeSnapshot(balance = 249.99), utc, listOf(1L, 2L))
        )
        // عتبة غائبة ⇒ العلم لا يُطلق أبداً (قرار محافظ موثق في المحرك)
        val noThreshold = entity("EVENT", eventFlagsJson = "[\"THRESHOLD\"]", threshold = null)
        assertFalse(
            StatementScheduleLogic.shouldRunRule(noThreshold, now, FakeSnapshot(balance = 999.0), utc, listOf(1L))
        )
    }

    @Test
    fun `حدثي أعلام متعددة رصيد وغير مسددة ومتأخرة`() {
        val now = at(utc, 2026, 9, 24, 10, 0)
        val multi = entity("EVENT", eventFlagsJson = "[\"BALANCE_DUE\",\"UNPAID_INVOICE\",\"OVERDUE\"]")
        // نطاق الأطراف = القائمة الكاملة كتمرير العامل (ALL تُوسّع من المستدعي)
        val all = listOf(1L, 2L)
        // كل الأصفار ⇒ لا إشعار وهمي
        assertFalse(StatementScheduleLogic.shouldRunRule(multi, now, FakeSnapshot(), utc, all))
        // علم واحد يكفي (متأخرة=1) ⇒ نعم
        assertTrue(StatementScheduleLogic.shouldRunRule(multi, now, FakeSnapshot(overdue = 1), utc, all))
        // غير مسددة=2 بلا متأخرة ⇒ نعم عبر العلم الثاني
        assertTrue(StatementScheduleLogic.shouldRunRule(multi, now, FakeSnapshot(unpaid = 2), utc, all))
        // رصيد موجب فوق التسامح ⇒ نعم عبر العلم الأول
        assertTrue(StatementScheduleLogic.shouldRunRule(multi, now, FakeSnapshot(balance = 0.05), utc, all))
    }

    @Test
    fun `حدثي كابح اليوم المحلي يعتمد على المنطقة الممررة صراحة`() {
        // نفس اللحظتين: الآن 23:00 وآخر تشغيل 01:00 بتوقيت الرياض — اليوم المحلي
        // نفسه في الرياض (تكبح) لكن يومان محليان مختلفان في UTC (تُطلق)
        val now = at(riyadh, 2026, 9, 30, 23, 0)
        val lastRun = at(riyadh, 2026, 9, 30, 1, 0)
        val rule = entity("EVENT", lastRunAt = lastRun, eventFlagsJson = "[\"MONTH_END\"]")
        val snap = FakeSnapshot()
        assertFalse(StatementScheduleLogic.shouldRunRule(rule, now, snap, riyadh))
        assertTrue(StatementScheduleLogic.shouldRunRule(rule, now, snap, utc))
    }

    @Test
    fun `قاعدة دورية معطلة يقررها الجدول لا المحرك توثيق حارس الطبقة العليا`() {
        // [ملاحظة صادقة]: shouldRunRule لا يفحص enabled — الحرس الفعلي في العامل
        // (dueRules يفهرس enabled AND nextRunAt<=now، وrulesOnce يرشّح it.enabled).
        // الدالة النقية بوابة توقيت فقط — يوثَّق السلوك الفعلي هنا؛ أي مستدعٍ جديد
        // يجب أن يمر بالمرشِّح وإلا شغّل قاعدة معطلة (خطر دفاعية-متعددة-طبقات موثق).
        val now = at(utc, 2026, 9, 24, 10, 0)
        val disabled = entity("DAILY", nextRunAt = now, enabled = false)
        assertTrue(StatementScheduleLogic.shouldRunRule(disabled, now, null, utc, emptyList()))
        // ومعطلة ولم يحن موعدها ⇒ تبقى كاذبة كما في المفعلة (التوقيت هو الحاكم وحده)
        val disabledEarly = entity("DAILY", nextRunAt = now + 60_000L, enabled = false)
        assertFalse(StatementScheduleLogic.shouldRunRule(disabledEarly, now, null, utc, emptyList()))
    }

    @Test
    fun `قاعدة حدثية معطلة يقررها العلم لا المحرك توثيق حارس الطبقة العليا`() {
        // نفس العقد أعلاه للحدثي: العامل يرشّح enabled قبل الفحص — النقي يقرر بالأعلام
        val now = at(utc, 2026, 9, 24, 10, 0)
        val disabled = entity(
            "EVENT", enabled = false, partyMode = "SELECTED", partyIdsJson = "[1]",
            eventFlagsJson = "[\"OVERDUE\"]"
        )
        assertTrue(StatementScheduleLogic.shouldRunRule(disabled, now, FakeSnapshot(overdue = 3), utc))
    }

    // ═════════ parsePartyIds — التحليل المتسامح الموثق ═════════

    @Test
    fun `parsePartyIds مصفوفة JSON سليمة تحفظ الترتيب`() {
        assertEquals(listOf(1L, 2L, 3L), StatementScheduleLogic.parsePartyIds("[1,2,3]"))
        assertEquals(listOf(1L, 2L, 3L), StatementScheduleLogic.parsePartyIds("[1, 2, 3]"))
        assertEquals(listOf(5L), StatementScheduleLogic.parsePartyIds("[5]"))
    }

    @Test
    fun `parsePartyIds نص فارغ أو فراغات يعيد قائمة فارغة`() {
        assertEquals(emptyList<Long>(), StatementScheduleLogic.parsePartyIds(""))
        assertEquals(emptyList<Long>(), StatementScheduleLogic.parsePartyIds("   "))
    }

    @Test
    fun `parsePartyIds null يعيد قائمة فارغة`() {
        assertEquals(emptyList<Long>(), StatementScheduleLogic.parsePartyIds(null))
    }

    @Test
    fun `parsePartyIds JSON مشوه بلا أرقام يعيد قائمة فارغة بلا انفجار`() {
        assertEquals(emptyList<Long>(), StatementScheduleLogic.parsePartyIds("abc][x"))
        assertEquals(emptyList<Long>(), StatementScheduleLogic.parsePartyIds("{\"a\":}"))
        assertEquals(emptyList<Long>(), StatementScheduleLogic.parsePartyIds("لا شيء هنا"))
    }

    @Test
    fun `parsePartyIds JSON مشوه بأرقام يستخرجها تسامحا حسب العقد`() {
        // التحليل regex لا JSON صارم — الأرقام تُستخرج من نص فاسد (موثق لا صامت)
        assertEquals(listOf(12L, 9L), StatementScheduleLogic.parsePartyIds("not json {12,,x9"))
        assertEquals(listOf(7L), StatementScheduleLogic.parsePartyIds("[\"7\"]"))
        assertEquals(listOf(4L), StatementScheduleLogic.parsePartyIds("4"))
    }

    @Test
    fun `parsePartyIds يزيل التكرار والصفر ويحفظ الترتيب والسالبة تفقد إشارتها`() {
        // الاستخراج المتسامح الموثق: regex [0-9]+ يلتقط الأرقام بلا إشارة —
        // فالسالبة تفقد إشارتها (‏-2 ⇒ 2) وتُقبل بوصفها معرفاً موجباً، والصفر مستبعد
        // (فلتر it > 0)، والتكرار يزال بأول ظهور (LinkedHashSet)
        assertEquals(listOf(7L, 2L, 15L), StatementScheduleLogic.parsePartyIds("[7,7,0,-2,15]"))
        // إزالة التكرار تحفظ أول ظهور
        assertEquals(listOf(3L, 1L), StatementScheduleLogic.parsePartyIds("[3,1,3,1,3]"))
    }

    @Test
    fun `parsePartyIds اعداد ضخمة تمر سليمة`() {
        assertEquals(
            listOf(Long.MAX_VALUE),
            StatementScheduleLogic.parsePartyIds("[9223372036854775807]")
        )
        // فوق نطاق Long يُتخطى بلا انفجار (toLongOrNull=null ⇒ لا يُضاف)
        assertEquals(emptyList<Long>(), StatementScheduleLogic.parsePartyIds("[99999999999999999999]"))
    }
}
