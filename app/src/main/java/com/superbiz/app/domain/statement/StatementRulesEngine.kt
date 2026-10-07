package com.superbiz.app.domain.statement

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * [P18-b] محرك قواعد كشف الحساب — قرار نقي 100% (JVM بلا أي استيراد أندرويد).
 *
 * يفصل «الحسم» عن «الفعل»: هذا الكائن يقرر فقط هل القاعدة مستحقة الآن ومتى موعدها
 * القادم، بينما الفعل (تجميع/رسم PDF/إرسال) بيد StatementScheduleWorker (طبقة العمل).
 * النمط نفسه المتبع في ReportScheduleLogic: منطق خالص قابل للاختبار وحداتياً + طبقة
 * أندرويد رقيقة فوقه.
 *
 * قرارات العقد الموثقة (يلتزم بها 18-c وعامل الجدولة):
 *
 * 1) التكرار الدوري (DAILY/WEEKLY/MONTHLY/QUARTERLY/YEARLY/CUSTOM): الاستحقاق يُقرأ
 *    من nextRunAt المخزن (<= now يعني مستحق) — وnextRunAt نفسها يولّدها [nextRunAt]
 *    هنا بـ java.util.Calendar بالمنطقة الممررة (لا java.time: minSdk 24 بلا
 *    desugaring — نفس مبرر OverdueReminderPolicy/TimeMath/StatementService).
 *
 * 2) التكرار الحدثي (EVENT): بلا جدولة إطلاقاً — يُفحص في كل دورة عامل عبر الأعلام،
 *    ودلالة «الحدث الواحد لا يُطلق مرتين» تتحقق بـ lastRunAt + نافذة حكم:
 *      - MONTH_END: اليوم آخر يوم في الشهر (بالمنطقة) ولم تُشغَّل القاعدة اليوم نفسه.
 *      - NEW_TX:    حركة دفتر جديدة للطرف بعد lastRunAt (نافذة زمنية صرفة).
 *      - BALANCE_DUE: رصيد الطرف موجباً (DUE_ON_CUSTOMER) وأكبر من التسامح.
 *      - THRESHOLD:   رصيد الطرف >= threshold (حدّ شامل للمساواة — عتبة «تجاوز الحد»
 *                     بلطف شامل للمساواة لأن الفرق تحت قرش لا معنى له).
 *      - UNPAID_INVOICE: توجد فاتورة بيع غير مسددة.
 *      - OVERDUE:   توجد فاتورة بيع متأخرة (نفس مقياس ReportsRepo.overdueTotal:
 *                   open > 0.004 && dueDate < now — لا يُعاد تعريف الخوارزمية).
 *    الأعلام الحالة (BALANCE_DUE/THRESHOLD/UNPAID_INVOICE/OVERDUE/MONTH_END) لها
 *    نافذة سكون «يوم محلي واحد»: لا تُطلق ثانية في اليوم المحلي نفسه الذي شُغلت فيه
 *    — وإلا كان العامل (كل 15 دقيقة) سيولّد كشفاً كل دورة ما دام الحال قائماً.
 *    markRuleRun للقاعدة الحدثية يخزن nextRunAt=null عمداً (الحدث يقوده الأعلام
 *    لا الجدول — انظر توثيق العامل).
 *
 * 3) تعاقد CUSTOM (يُلزِم 18-c): التكرار CUSTOM يقرأ عدد الأيام من eventFlagsJson
 *    نفسه بصيغة كائن JSON: {"days": N} — ويُتسامح مع المفتاح "DAYS" (أي حالة)
 *    والقيمة رقمية صحيحة 1..3650. غياب المفتاح أو فساده ⇒ سقوط آمن إلى 30 يوماً
 *    (موثق لا صامت: ثابت FALLBACK_CUSTOM_DAYS). لا حقل customDays في جدول
 *    statement_rules عمداً (تخطيط 17-a) — فكانت eventFlagsJson هي الحامل الطبيعي.
 *
 * 4) نطاق الأطراف: ALL يستلزم قائمة الأطراف من المستدعي (العامل يمررها عبر
 *    isDue بصيغتها الخماسية)؛ SINGLE/SELECTED يُقرآن من partyIdsJson. ملاحظة
 *    للتكامل: تعليق كيان 17-a يقول partyIdsJson يبقى "" لـSINGLE — لا يمكن؛
 *    **يجب على 18-c تخزين طرف SINGLE الوحيد في partyIdsJson بصيغة "[<id>]"**
 *    وإلا فالقاعدة بلا أطراف ولا تولّد شيئاً (سلوك مغلق-على-الأمان الموثق).
 *
 * كل الدوال نقية ومغطاة في StatementRulesEngineTest (JVM نقي).
 */
object StatementRulesEngine {

    // ───────── ثوابت العقد ─────────

    const val FREQ_DAILY = "DAILY"
    const val FREQ_WEEKLY = "WEEKLY"
    const val FREQ_MONTHLY = "MONTHLY"
    const val FREQ_QUARTERLY = "QUARTERLY"
    const val FREQ_YEARLY = "YEARLY"
    const val FREQ_CUSTOM = "CUSTOM"
    const val FREQ_EVENT = "EVENT"

    /** مفتاح عدد أيام CUSTOM داخل eventFlagsJson — عقد JSON مع 18-c (قرار ③) */
    const val CUSTOM_DAYS_KEY = "days"

    /** سقوط CUSTOM الآمن حين يغيب {"days":N} — موثق لا صامت */
    const val FALLBACK_CUSTOM_DAYS = 30

    /** التسامح المحاسبي الموحد — نفس عتبة StatementService/AccountingEngine */
    val TOLERANCE: Double = StatementService.TOLERANCE

    /** أعلام الأحداث الممكنة لقاعدة EVENT — تُخزن أسماؤها في eventFlagsJson */
    enum class RuleEvent { MONTH_END, NEW_TX, BALANCE_DUE, THRESHOLD, UNPAID_INVOICE, OVERDUE }

    /**
     * لقطة قاعدة نقية — يبنيها العامل من StatementRuleEntity في طبقة العمل
     * ([StatementScheduleLogic.toSnapshot]) كي يبقى هذا الملف خالياً من Room.
     */
    data class RuleSnapshot(
        val id: Long,
        val partyMode: String,          // ALL / SELECTED / SINGLE
        val partyIdsJson: String,       // "[1,2,3]" — انظر قرار ④ لـSINGLE
        val frequency: String,          // ثوابت FREQ_* أعلاه
        val weekday: Int?,              // 1..7 (Calendar.DAY_OF_WEEK) لـWEEKLY
        val dayOfMonth: Int?,           // 1..31 يُقص لطول الشهر — MONTHLY/QUARTERLY/YEARLY
        val hour: Int,                  // 0..23
        val minute: Int,                // 0..59
        val periodPreset: String,       // اسم StatementPeriodPreset
        val channel: String,            // قناة التسليم (EMAIL/SMTP/WHATSAPP/PRINT/SHARE)
        val eventFlagsJson: String?,    // أعلام EVENT و/أو {"days":N} لـCUSTOM
        val threshold: Double?,         // عتبة الرصيد لـTHRESHOLD
        val lastRunAt: Long?,           // آخر تشغيل فعلي (نافذة الأعلام تعتمد عليه)
        val nextRunAt: Long?,           // الموعد المجدول للدوري (null للحدثي)
        val enabled: Boolean = true,
        val customDays: Int? = null,    // يُشتق من eventFlagsJson عند التحويل (اختياري هنا)
        // [P18-int] ربط القالب/التوقيع/الختم — يحتاجها العامل عند التوليد (اختيارية هنا
        // كي لا تكسر الاختبارات النقية التي تبني اللقطة بلا هذه الحقول)
        val templateId: String? = null,
        val signatureId: Long? = null,
        val stampId: Long? = null
    )

    /**
     * لقطة الأحداث التي يفحص بها المحرك علم الحدث — يحققها العامل فوق AppGraph
     * ([com.superbiz.app.work.StatementEventSnapshot]). كل استعلام طرفي منفصل،
     * والنقيء يبقى في المحرك. الإخفاق في استعلام واحد يعيد false/0 في الطبقة
     * المحققة (مواقف محافظة لا انفجار).
     */
    interface EventSnapshot {
        /** هل توجد حركة دفتر للطرف بعد اللحظة since؟ (since=0 ⇒ التاريخ كله) */
        fun hasNewTx(partyId: Long, since: Long): Boolean

        /** عدد فواتير البيع المتأخرة غير المسددة للطرف (مقياس ReportsRepo.overdueTotal) */
        fun overdueCount(partyId: Long): Int

        /** عدد فواتير البيع غير المسددة للطرف (open > 0.004) */
        fun unpaidInvoiceCount(partyId: Long): Int

        /**
         * رصيد الطرف الحالي بدلالة الدفتر: موجب = العميل يدين لك (DUE_ON_CUSTOMER)،
         * سالب = لك رصيداً في حسابه — نفس دلالة JournalDao.partyBalances.
         */
        fun balanceDue(partyId: Long): Double

        /** هل اللحظة now تقع في آخر يوم من شهرها بالمنطقة الممررة؟ */
        fun lastDayOfMonth(now: Long, tz: TimeZone): Boolean
    }

    // ───────── أعلام الأحداث ─────────

    /**
     * تفسير eventFlagsJson — متسامح: يقبل مصفوفة أعلام ["MONTH_END","THRESHOLD"]
     * وكائن أعلام {"MONTH_END":true,"days":5} وقائمة عارية بلا اقتباس
     * "MONTH_END,THRESHOLD". المفتاح/القيمة غير المعروفة **تُتجاهل بصمت عمداً**
     * (توافق أمامي مع أعلام مستقبلية) — ولا يرمي استثناء أبداً.
     */
    fun parseEventFlags(json: String?): Set<RuleEvent> {
        if (json.isNullOrBlank()) return emptySet()
        val known = RuleEvent.entries.associateBy { it.name }
        val found = LinkedHashSet<RuleEvent>()
        // 1) التوكنات المقتبسة (JSON سليم — مصفوفة أو مفاتيح كائن)
        var i = 0
        while (i < json.length) {
            val open = json.indexOf('"', i)
            if (open == -1) break
            val close = json.indexOf('"', open + 1)
            if (close == -1) break
            known[json.substring(open + 1, close).trim().uppercase(Locale.US)]
                ?.let { found.add(it) }
            i = close + 1
        }
        // 2) احتياط: نص عاري بلا اقتباس
        if (found.isEmpty()) {
            for (tok in json.split(Regex("[^A-Za-z_]+"))) {
                if (tok.isBlank()) continue
                known[tok.trim().uppercase(Locale.US)]?.let { found.add(it) }
            }
        }
        return found
    }

    /**
     * قراءة {"days":N} من eventFlagsJson لتكرار CUSTOM — عقد قرار ③ أعلاه.
     * يُتسامح مع حالة المفتاح والاقتباس، والقيمة خارج 1..3650 تُعد فساداً ⇒ null.
     */
    fun parseCustomDays(json: String?): Int? {
        if (json.isNullOrBlank()) return null
        val m = DAYS_RX.find(json) ?: return null
        val v = m.groupValues[1].toLongOrNull() ?: return null
        return if (v in 1..3650) v.toInt() else null
    }

    private val DAYS_RX =
        Regex("[\"']?(?:days|DAYS)[\"']?\\s*:\\s*([0-9]+)")

    // ───────── الاستحقاق ─────────

    /**
     * هل القاعدة مستحقة الآن؟ الصيغة الرباعية تفحص أطراف القاعدة المصرّح بها في
     * partyIdsJson فقط — استخدمها للاختبارات والقواعد ذات الأطراف المحددة.
     */
    fun isDue(
        rule: RuleSnapshot,
        now: Long,
        tz: TimeZone = TimeZone.getDefault(),
        snapshot: EventSnapshot?
    ): Boolean = isDue(rule, now, tz, snapshot, emptyList())

    /**
     * الصيغة الكاملة التي يستخدمها العامل: نطاق الأطراف = [targetParties] على
     * القائمة الكاملة الممررة (ALL تُوسَّع هنا). علم حدثي طرفي (NEW_TX مثلاً)
     * يُحقق على «أي طرف» من النطاق، والعلم العام (MONTH_END) لا يعتمد على الأطراف.
     * القواعد الدورية: مستحقة إذا كان nextRunAt المخزن غير فارغ و<= now (قرار ①)
     * — snapshot لا يؤثر فيها إطلاقاً (يقبل null).
     */
    fun isDue(
        rule: RuleSnapshot,
        now: Long,
        tz: TimeZone,
        snapshot: EventSnapshot?,
        allPartyIds: List<Long>
    ): Boolean {
        val freq = rule.frequency.trim().uppercase(Locale.US)
        if (freq == FREQ_EVENT) {
            if (snapshot == null) return false          // لا لقطة ⇒ لا حكم حدثي (قرار محافظ)
            val flags = parseEventFlags(rule.eventFlagsJson)
            if (flags.isEmpty()) return false
            val parties = targetParties(rule, allPartyIds)
            val ranToday = rule.lastRunAt != null && isSameLocalDay(rule.lastRunAt, now, tz)
            for (f in flags) {
                val hit = when (f) {
                    RuleEvent.MONTH_END ->
                        !ranToday && snapshot.lastDayOfMonth(now, tz)
                    RuleEvent.NEW_TX ->
                        parties.any { snapshot.hasNewTx(it, rule.lastRunAt ?: 0L) }
                    RuleEvent.BALANCE_DUE ->
                        !ranToday && parties.any { snapshot.balanceDue(it) > TOLERANCE }
                    RuleEvent.THRESHOLD ->
                        rule.threshold != null && !ranToday &&
                            parties.any { snapshot.balanceDue(it) >= rule.threshold }
                    RuleEvent.UNPAID_INVOICE ->
                        !ranToday && parties.any { snapshot.unpaidInvoiceCount(it) > 0 }
                    RuleEvent.OVERDUE ->
                        !ranToday && parties.any { snapshot.overdueCount(it) > 0 }
                }
                if (hit) return true
            }
            return false
        }
        // دوري: جدول nextRunAt هو الحاكم (قيمته يولّدها nextRunAt أدناه)
        val nr = rule.nextRunAt
        return nr != null && nr <= now
    }

    /** هل اللحظتان في اليوم المحلي نفسه بالمنطقة الممررة؟ (نافذة سكون الأعلام الحالة) */
    fun isSameLocalDay(a: Long, b: Long, tz: TimeZone): Boolean {
        val ca = Calendar.getInstance(tz).apply { timeInMillis = a }
        val cb = Calendar.getInstance(tz).apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    // ───────── الجدولة التالية ─────────

    /**
     * الموعد التالي للقاعدة بعد اللحظة after — كل الفروع بدقة hour:minute:00.000
     * بالمنطقة الممررة، وإن تصادف المرشح مع after أو قبله فَيَدفع دورة كاملة
     * إلى الأمام (دلالة «بعد حصرياً») — القرار نفسه في OverdueReminderPolicy.
     *
     * - DAILY:      اليوم إن لم تحن الساعة بعد وإلا غداً.
     * - WEEKLY:     أول يوم هذا الأسبوع (أو بعده) يطابق weekday؛ weekday غائب/تالف
     *               ⇒ يتصرف DAILY (موثق).
     * - MONTHLY:    dayOfMonth مقصوص لطول الشهر (31 في فبراير ⇒ 28/29 حسب السنة
     *               والكبسة تلقائياً من getActualMaximum).
     * - QUARTERLY:  نفس القص مع خطوة 3 أشهر من شهر after (5/31 ⇒ 8/31).
     * - YEARLY:     شهر المرساة = شهر after نفسه (القاعدة تعيد إطلاقها في شهر آخر
     *               تشغيل — لا عمود شهر في المخطط عمداً)، واليوم كـMONTHLY بالقص،
     *               وغياب dayOfMonth يعني يوم after نفسه. 2/29 ⇒ 2/28 في غير الكبسة.
     * - CUSTOM:     كل N أيام (N من {"days":N} أو customDays أو السقوط 30) —
     *               التقدم عبر Calendar.add(DAY_OF_MONTH) فيحفظ الساعة المحلية عبر DST.
     * - EVENT/غير معروف: تعيد 0L = «لا جدولة» (العامل يخزن nextRunAt=null).
     */
    fun nextRunAt(rule: RuleSnapshot, after: Long, tz: TimeZone): Long {
        val freq = rule.frequency.trim().uppercase(Locale.US)
        val hour = rule.hour.coerceIn(0, 23)
        val minute = rule.minute.coerceIn(0, 59)
        return when (freq) {
            FREQ_DAILY -> {
                val c = atTime(after, hour, minute, tz)
                if (c.timeInMillis <= after) c.add(Calendar.DAY_OF_MONTH, 1)
                c.timeInMillis
            }
            FREQ_WEEKLY -> {
                val c = atTime(after, hour, minute, tz)
                val target = rule.weekday?.takeIf { it in 1..7 }
                if (target != null) {
                    val delta = (target - c.get(Calendar.DAY_OF_WEEK) + 7) % 7
                    if (delta > 0) c.add(Calendar.DAY_OF_MONTH, delta)
                    if (c.timeInMillis <= after) c.add(Calendar.DAY_OF_MONTH, 7)
                } else if (c.timeInMillis <= after) {
                    c.add(Calendar.DAY_OF_MONTH, 1)
                }
                c.timeInMillis
            }
            FREQ_MONTHLY, FREQ_QUARTERLY, FREQ_YEARLY -> {
                val step = when (freq) {
                    FREQ_QUARTERLY -> 3
                    FREQ_YEARLY -> 12
                    else -> 1
                }
                val base = Calendar.getInstance(tz).apply {
                    timeInMillis = after
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    set(Calendar.DAY_OF_MONTH, 1)
                }
                val anchorDay = rule.dayOfMonth
                    ?: Calendar.getInstance(tz)
                        .apply { timeInMillis = after }.get(Calendar.DAY_OF_MONTH)
                var k = 0
                while (true) {
                    val cand = (base.clone() as Calendar).apply { add(Calendar.MONTH, k) }
                    val maxDay = cand.getActualMaximum(Calendar.DAY_OF_MONTH)
                    cand.set(Calendar.DAY_OF_MONTH, anchorDay.coerceIn(1, maxDay))
                    if (cand.timeInMillis > after) return cand.timeInMillis
                    k += step
                }
                @Suppress("UNREACHABLE_CODE") 0L
            }
            FREQ_CUSTOM -> {
                val days = (rule.customDays
                    ?: parseCustomDays(rule.eventFlagsJson)
                    ?: FALLBACK_CUSTOM_DAYS).coerceAtLeast(1)
                val c = atTime(after, hour, minute, tz)
                while (c.timeInMillis <= after) c.add(Calendar.DAY_OF_MONTH, days)
                c.timeInMillis
            }
            else -> 0L
        }
    }

    /** تقويم لحظة of مضبوطاً على hour:minute:00.000 بالمنطقة الممررة */
    private fun atTime(of: Long, hour: Int, minute: Int, tz: TimeZone): Calendar =
        Calendar.getInstance(tz).apply {
            timeInMillis = of
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

    // ───────── نطاق الأطراف والفترة ─────────

    /**
     * أطراف القاعدة: ALL ⇒ القائمة الكاملة؛ SELECTED/SINGLE ⇒ تحليل partyIdsJson
     * (متسامح). نمط غير معروف/فارغ ⇒ قائمة فارغة (مغلق على الأمان: قاعدة فاسدة
     * لا تولّد كشوفاً بلا تصريح — انظر قرار ④ حول تخزين SINGLE في partyIdsJson).
     */
    fun targetParties(rule: RuleSnapshot, allPartyIds: List<Long>): List<Long> {
        val mode = rule.partyMode.trim().uppercase(Locale.US)
        return when (mode) {
            "ALL" -> allPartyIds
            "SELECTED", "SINGLE" -> parsePartyIds(rule.partyIdsJson)
            else -> emptyList()
        }
    }

    /**
     * تحليل partyIdsJson متسامحاً: يقبل "[1,2,3]" و"5" و"[\"7\"]" وأي خليط أرقام —
     * يستخرج الأرقام الموجبة فقط ويحفظ الترتيب ويزيل التكرار. النص الفاسد ⇒ قائمة
     * فارغة (لا استثناء أبداً).
     */
    fun parsePartyIds(json: String?): List<Long> {
        if (json.isNullOrBlank()) return emptyList()
        val out = LinkedHashSet<Long>()
        for (m in Regex("[0-9]+").findAll(json)) {
            m.value.toLongOrNull()?.let { if (it > 0) out.add(it) }
        }
        return out.toList()
    }

    /**
     * نافذة الفترة للقاعدة — **تفويض مباشر** إلى StatementService.periodRange
     * (نفس الدلالة الحرفية، لا نسخ منطق). يعيد null إن كان الاسم غير معروف أو
     * كان CUSTOM (لا توجد نافذة مخزنة في القاعدة — قرار 17-a: بلا حقول
     * customFrom/customTo في statement_rules؛ قواعد AUTO بفترة CUSTOM لا معنى
     * لها فيجب ألا يعرضها 18-c في محرر القواعد).
     * ملاحظة صادقة: periodRange داخلياً بمنطقة الجهاز الافتراضية (قرار 17-a ①)
     * فلا معامل tz هنا — الكشف مستند تجاري يُقرأ بتوقيت التاجر.
     */
    fun periodFor(preset: String, now: Long): Pair<Long, Long>? {
        val p = runCatching {
            StatementPeriodPreset.valueOf(preset.trim().uppercase(Locale.US))
        }.getOrNull() ?: return null
        if (p == StatementPeriodPreset.CUSTOM) return null
        return StatementService.periodRange(p, now)
    }
}

/**
 * [P18-b] سياسة إعادة محاولة تسليم الكشوف — قرار نقي فوق أعمدة statement_deliveries
 * (status/attempts) بلا لمس مخطط. الحالات: PENDING → PROCESSING → SENT | FAILED؛
 * FAILED → RETRYING → PROCESSING؛ CANCELLED نهائية (توثيق StatementDeliveryEntity).
 */
object RetryPolicy {

    /**
     * هل يجري إعادة إرسال تلقائية؟ فقط FAILED/RETRYING مع محاولات تحت السقف —
     * CANCELLED نهائية وSENT منجزة وPENDING/PROCESSING ليست فشلاً يُعاد، وكلها
     * تُترك لحالة المستخدم اليدوية. المقارنة متسامحة للحالة والمحاولات السالبة
     * تُعد صفراً.
     */
    fun shouldAutoRetry(status: String, attempts: Int, maxAttempts: Int): Boolean {
        if (maxAttempts <= 0) return false
        if (attempts.coerceAtLeast(0) >= maxAttempts) return false
        return when (status.trim().uppercase(Locale.US)) {
            "FAILED", "RETRYING" -> true
            else -> false   // CANCELLED / SENT / PENDING / PROCESSING / غير معروف
        }
    }

    /**
     * مهلة ما قبل إعادة المحاولة القادمة — تراجع أُسّي 5د × 2^n بسقف 6 ساعات:
     * 5د، 10د، 20د، 40د، 80د، 160د، 320د، ثم 6 ساعات (ومنها فصاعداً 6 ساعات).
     * المحاولات السالبة تُعد صفراً. القيم ميلّي ثانية.
     */
    fun nextDelayMs(attempts: Int): Long {
        val base = 5L * 60_000L
        val n = attempts.coerceAtLeast(0).coerceAtMost(10)   // يمنع فائض الإزاحة قبل السقف
        return minOf(base * (1L shl n), 6L * 3_600_000L)
    }

    /**
     * [تدقيق M-5] هل حان موعد إعادة المحاولة؟ — البوابة الخلفية التي كانت ناقصة:
     * nextDelayMs كانت معرّفة بلا مستهلك، فالمجدول (مسح كل 15 دقيقة) كان يعيد
     * كل فاشل مؤهل في كل دورة ويضرب مضيف SMTP ساقط بعاصفة محاولات بلا مهلة.
     * الآن: فاشل مؤهل يعاد فقط إذا مضت منذ آخر محاولة مهلته الأسّية كاملة
     * (5د×2^n بسقف 6 ساعات فوق lastAttemptAt) — أو لم تُسجّل له محاولة أبداً.
     * قرار نقي فوق الأعمدة القائمة (لا لمس مخطط) — قابل للاختبار بلا Android.
     */
    fun isRetryDue(
        status: String,
        attempts: Int,
        maxAttempts: Int,
        lastAttemptAt: Long?,
        now: Long
    ): Boolean {
        if (!shouldAutoRetry(status, attempts, maxAttempts)) return false
        if (lastAttemptAt == null) return true
        return (now - lastAttemptAt) >= nextDelayMs(attempts)
    }
}
