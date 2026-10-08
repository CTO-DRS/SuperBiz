package com.superbiz.app.domain.algo

import java.util.Calendar
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * — عشرون خوارزمية حتمية جديدة للموجة R16 «المنسّق الذكي»، في 5 كائنات مستقلة
 * لا يتقاطع أيّها مع R7/R9/R10/R11/R12/R13/R14/R15 ولا مع كائنات الحساب الأساسية:
 *
 * الفرق الجوهري عن الموجات السابقة: R16 لا يكتفي بوصف الماضي بل يتنبأ بالمستقبل
 * ويعرض أصوله — كل استنتاج يحمل أساسه الحسابي، والفراغ صادق دائماً.
 *
 * CashFlow90Math (1..4)  : تنبؤ التدفق النقدي 90 يوماً بمجالات ثقة متسعة جذرياً
 *                          + حدث «اليوم الجاف» قبل وقوعه (H3-1)
 * ReorderPointMath (5..8): نقاط إعادة الطلب بدمج σ الطلب مع σ زمن التوريد
 *                          الفعلي الملحوظ + اختبار تتبعي محاكى (H3-2)
 * EwmaAlertMath (9..12)  : تنبيهات استباقية بحالة استقرار متصدر (hysteresis)
 *                          وسياسة إشعار بلا إزعاج (H3-3)
 * QueryParseMath (13..17): محلل استعلام عربي/إنجليزي محدود الدلالة حتمي (H3-4)
 * NarrativeMath (18..20) : روايات KPI البنيوية — كل سطر يحمل أساسه الخام (H3-5)
 *
 * عقود الصدق العامة (نفس انضباط الموجات):
 * - دوال نقية: بلا وصول لقاعدة البيانات أو الشبكة — بلا مؤثرات جانبية.
 * - بلا بيانات كافية أو بمدخلات غير منتهية ⇒ null/قائمة فارغة، ولا تُخترع أرقام.
 * - لا يُقرأ وقت النظام داخل أي خوارزمية — «اليوم» يُمرر صريحاً من المستدعي
 *   (Calendar المستعمل في QueryParseMath/NarrativeMath يقوم على الطوابع الممررة فقط).
 * - المبالغ Double ريال (التحويل قروش→ريال في المستودع/VM فقط — عقد P33-P8).
 * - كل خوارزمية موثقة بعقد صريح في docs/ALGORITHMS.md §R16.
 */

private fun r16Finite(values: Collection<Double>): Boolean = values.all { it.isFinite() }

private fun r16Mean(values: List<Double>): Double = values.sum() / values.size

private fun r16Std(values: List<Double>, m: Double): Double =
    sqrt(values.sumOf { (it - m) * (it - m) } / values.size)

// ═══════════════ 1) CashFlow90Math: تنبؤ التدفق النقدي 90 يوماً (H3-1) ═══════════════

object CashFlow90Math {

    /** إحصاء الصافي اليومي داخل نافذة تاريخية — أساس التنبؤ الوحيد */
    data class NetStats(
        val meanNet: Double,      // متوسط الصافي اليومي (وارد − صادر)
        val sdNet: Double,        // تقلب الصافي اليومي (σ المجتمعية للنافذة)
        val days: Int,            // طول النافذة المقيسة
        val activeDays: Int       // أيام فيها حركة فعلية (>0)
    )

    /** حدث مجدول حتمي (شيك/قسط) — المبالغ التعاقدية تعرف لا تُخمَّن */
    data class Scheduled(
        val day: Long,            // طابع الاستحقاق (ms)
        val amount: Double,       // موجب دائماً
        val isInflow: Boolean
    )

    /** نقطة أسبوعية في المسار — التمثيل الزمني الواضح (H3-1 «تمثيل زمني») */
    data class WeekPoint(
        val weekIndex: Int,       // 1..13 (وكسر أخير جزئي)
        val startDay: Long,
        val endDay: Long,
        val scheduledIn: Double,
        val scheduledOut: Double,
        val expectedEnd: Double,  // الرصيد المتوقع نهاية الأسبوع
        val lower80: Double,      // الحد الأدنى 80% — يمتد جذرياً مع الأفق
        val upper80: Double
    )

    /** المسار الكامل مع الحكم الصادق */
    data class CashPath(
        val cashNow: Double,
        val weeks: List<WeekPoint>,
        val verdict: String       // HEALTHY / TIGHT / DRY
    )

    /** ملخص الثلاثين يوماً الأولى للبطاقة */
    data class MonthAhead(
        val expectedEnd: Double,
        val lower80: Double,
        val upper80: Double,
        val netExpected: Double
    )

    /**
     * (1) إحصاء الصافي اليومي من تاريخي وارد/صادر يومي ‎(طابع، مبلغ)‎.
     * عقد: النافذة آخر [windowDays] يوماً حتى [today] شبكة كاملة؛ لا يُقبل
     * فراغ تام — أقل من 3 أيام نشطة ⇒ null (لا تنبؤ من لا شيء). مبالغ سالبة
     * أو غير منتهية تُهمَل صامتاً (خطأ إدخال لا دليل).
     */
    fun dailyNetStats(
        inflows: List<Pair<Long, Double>>,
        outflows: List<Pair<Long, Double>>,
        today: Long,
        windowDays: Int = 56
    ): NetStats? {
        if (windowDays < 7 || today <= 0L) return null
        val startDay = today - (windowDays - 1) * 86_400_000L
        fun dayIndex(ts: Long): Int =
            ((ts - startDay) / 86_400_000L).toInt()
        val net = DoubleArray(windowDays)
        var active = 0
        inflows.forEach { (ts, amt) ->
            val i = dayIndex(ts)
            if (i in 0 until windowDays && amt > 0.0 && amt.isFinite()) net[i] += amt
        }
        outflows.forEach { (ts, amt) ->
            val i = dayIndex(ts)
            if (i in 0 until windowDays && amt > 0.0 && amt.isFinite()) net[i] -= amt
        }
        net.forEach { if (abs(it) > 1e-9) active++ }
        if (active < 3) return null
        val m = net.sum() / windowDays
        return NetStats(
            meanNet = m,
            sdNet = r16Std(net.toList(), m),
            days = windowDays,
            activeDays = active
        )
    }

    /**
     * (2) مسار 90 يوماً: أسبوعياً، الرصيد المتوقع = النقد + Σ(صافي يومي متوقع)
     * + أحداث مجدولة؛ عرض الثقة 80% يمشي مشي الحركة البراونية: النصف‑عرض
     * = z·σ·√(الأيام المنقضية) — اتساع جذري موثق لا وهم دقة.
     * عقد: stats من (1)؛ cashNow ≥ 0 منطقي (يسمح سالب مع تجاوز)؛
     * z الافتراضي 1.28 (ثقة 80%). الأحداث خارج الأفق أو الماضية تُهمَل.
     */
    fun forecast90(
        cashNow: Double,
        stats: NetStats,
        scheduled: List<Scheduled>,
        today: Long,
        horizonDays: Int = 90,
        zPct: Double = 1.28
    ): CashPath? {
        if (horizonDays < 7 || !cashNow.isFinite()) return null
        if (stats.sdNet < 0.0 || !stats.sdNet.isFinite()) return null
        val horizon = min(horizonDays, 90)
        val weekLen = 7
        val weeks = mutableListOf<WeekPoint>()
        var balance = cashNow
        var dayCursor = today
        var w = 1
        while (w * weekLen - weekLen < horizon) {
            val n = min(weekLen, horizon - (w - 1) * weekLen)
            val windowStart = dayCursor
            val windowEnd = dayCursor + (n - 1) * 86_400_000L
            var schedIn = 0.0
            var schedOut = 0.0
            scheduled.forEach { s ->
                if (s.amount > 0.0 && s.amount.isFinite() &&
                    s.day >= windowStart && s.day <= windowEnd
                ) {
                    if (s.isInflow) schedIn += s.amount else schedOut += s.amount
                }
            }
            balance += stats.meanNet * n + schedIn - schedOut
            // σ تراكمي مشي الحركة البراونية على كامل الأيام المنقضية
            val elapsed = (w - 1) * weekLen + n
            val half = zPct * stats.sdNet * sqrt(elapsed.toDouble())
            weeks += WeekPoint(
                weekIndex = w,
                startDay = windowStart,
                endDay = windowEnd,
                scheduledIn = schedIn,
                scheduledOut = schedOut,
                expectedEnd = balance,
                lower80 = balance - half,
                upper80 = balance + half
            )
            dayCursor = windowEnd + 86_400_000L
            w++
        }
        if (weeks.isEmpty()) return null
        val final = weeks.last()
        val verdict = when {
            final.lower80 < 0.0 -> "DRY"
            final.lower80 < cashNow * 0.5 -> "TIGHT"
            else -> "HEALTHY"
        }
        return CashPath(cashNow, weeks, verdict)
    }

    /**
     * (3) «اليوم الجاف» قبل وقوعه: أول أسبوع ينزل حدُّه الأدنى 80% تحت الصفر.
     * عقد: يعيد null إذا لم يكن في المسار خطر جفاف ضمن الثقة 80% — لا إنذار
     * بلا أساس (بوابة H3-3: صفر إشعاع كاذب في العرض).
     */
    fun dryDay(path: CashPath): WeekPoint? =
        path.weeks.firstOrNull { it.lower80 < 0.0 }

    /**
     * (4) ملخص الثلاثين يوماً الأولى (بطاقة الرئيسية): نهاية الشهر المتوقعة
     * وحدها المجال، وصافي التغير المتوقع.
     * عقد: مسار بأقل من 4 أسابيع ⇒ null (الأفق أقصر من المعنى).
     */
    fun monthAhead(path: CashPath): MonthAhead? {
        val first4 = path.weeks.filter { it.weekIndex <= 4 }
        if (first4.size < 4) return null
        val end = first4.last()
        return MonthAhead(
            expectedEnd = end.expectedEnd,
            lower80 = end.lower80,
            upper80 = end.upper80,
            netExpected = end.expectedEnd - path.cashNow
        )
    }
}

// ═══════════════ 2) ReorderPointMath: نقاط إعادة الطلب الذكية (H3-2) ═══════════════

object ReorderPointMath {

    /** إحصاء زمن التوريد الملحوظ لصنف واحد (فجوات إعادة الشراء بالأيام) */
    data class LeadStats(
        val mean: Double,
        val sd: Double,
        val n: Int,
        val verdict: String   // OBSERVED (n≥5) / SMALL (2≤n<5)
    )

    /** نقطة إعادة الطلب المدمجة */
    data class Rop(
        val point: Double,    // نقطة الطلب: د̄·ل̄ + مكون الأمان
        val safety: Double,   // مكون الأمان z·√(ل̄·σd² + د̄²·σل²)
        val serviceZ: Double
    )

    /** أثر اختبار تتبعي محاكى — الدقة القابلة للقياس (بوابة H3-2) */
    data class Trace(
        val reorderDay: Int?,  // يوم بلوغ النقطة (1-based) — null إن لم تُبلَغ
        val zeroDay: Int?,     // يوم بلوغ الصفر — null إن لم يُبلَغ
        val daysGranted: Int?, // الأيام التي منحها الإنذار قبل النفاد
        val verdict: String    // EARLY (≥3) / ONTIME (1..2) / LATE (0)
    )

    /**
     * (5) إحصاء زمن التوريد الفعلي من فجوات إعادة الشراء الملحوظة بالأيام.
     * عقد: n<2 ⇒ null (لا σ من نقطة)؛ n≥5 ⇒ OBSERVED، وإلا SMALL
     * (المستدعي يستبدل بالمتعارف 7.0 ويعلن ذلك في التلميح — صدق المصدر).
     */
    fun leadStats(observedLeadDays: List<Double>): LeadStats? {
        val xs = observedLeadDays.filter { it.isFinite() && it >= 0.0 }
        if (xs.size < 2) return null
        val m = r16Mean(xs)
        return LeadStats(mean = m, sd = r16Std(xs, m), n = xs.size,
            verdict = if (xs.size >= 5) "OBSERVED" else "SMALL")
    }

    /**
     * (6) نقطة إعادة الطلب بدمج تقلب الطلب مع تقلب التوريد — صيغة التباين
     * المركب: ROP = د̄·ل̄ + z·√(ل̄·σd² + د̄²·σل²).
     * الفرق عن R7/R9/R11: σ التوريد ليست افتراض صفر — الصنف بطيء التوريد
     * يرفع نقطة طلبه ولو كان طلبه هادئاً.
     * عقد: د̄>0 ول̄>0 ومنتهية، وإلا null؛ σ دائماً ≥0؛ z>0.
     */
    fun reorderPoint(
        avgDaily: Double,
        demandSd: Double,
        leadMean: Double,
        leadSd: Double,
        serviceZ: Double = 1.65
    ): Rop? {
        if (!avgDaily.isFinite() || !demandSd.isFinite() ||
            !leadMean.isFinite() || !leadSd.isFinite() || !serviceZ.isFinite()
        ) return null
        if (avgDaily <= 0.0 || leadMean <= 0.0 || serviceZ <= 0.0) return null
        if (demandSd < 0.0 || leadSd < 0.0) return null
        val safety = serviceZ * sqrt(
            leadMean * demandSd * demandSd + avgDaily * avgDaily * leadSd * leadSd
        )
        return Rop(point = avgDaily * leadMean + safety, safety = safety, serviceZ = serviceZ)
    }

    /**
     * (7) أيام الأمان المتبقية: كم يوماً قبل بلوغ نقطة الطلب بالوتيرة الحالية.
     * عقد: د̄>0 وإلا null؛ سالب النتيجة مشروع (تجاوزنا النقطة فعلاً — إنذار).
     */
    fun daysOfSafety(stockQty: Double, rop: Double, avgDaily: Double): Double? {
        if (!stockQty.isFinite() || !rop.isFinite() || !avgDaily.isFinite()) return null
        if (avgDaily <= 0.0) return null
        return (stockQty - rop) / avgDaily
    }

    /**
     * (8) اختبار تتبعي محاكى على بيانات تاريخية مقيسة: استنزاف المخزون بلا
     * تعويض — متى نبهت النقطة ومتى نفقدا فعلاً؟ الدقة التتبعية = الأيام
     * الممنوحة بين الإنذار والنفاد.
     * عقد: stockStart>0 وsales منتهية غير سالبة وإلا null؛ لا نفاد ضمن
     * السلسلة ⇒ daysGranted=null وverdict="NO_RUNOUT" (صدق: لا حادثة لقياسها).
     */
    fun backtest(stockStart: Double, dailySales: List<Double>, rop: Double): Trace? {
        if (!stockStart.isFinite() || !rop.isFinite()) return null
        if (stockStart <= 0.0) return null
        if (dailySales.any { !it.isFinite() || it < 0.0 }) return null
        var stock = stockStart
        var reorderDay: Int? = null
        var zeroDay: Int? = null
        for (i in dailySales.indices) {
            if (reorderDay == null && stock <= rop) reorderDay = i + 1
            stock -= dailySales[i]
            if (stock <= 0.0) {
                zeroDay = i + 1
                break // أول نفاد هو الحادثة القياسية — لا تجاوز لاحق
            }
        }
        return Trace(
            reorderDay = reorderDay,
            zeroDay = zeroDay,
            daysGranted = if (reorderDay != null && zeroDay != null) zeroDay!! - reorderDay!! + 1 else null,
            verdict = when {
                reorderDay == null && zeroDay == null -> "NO_RUNOUT"
                reorderDay == null -> "LATE"       // نفد قبل أن تنبّه النقطة أصلاً
                zeroDay == null -> "EARLY"         // نبهت ولم ينفد ضمن السلسلة
                else -> (zeroDay!! - reorderDay!! + 1).let { granted ->
                    if (granted >= 3) "EARLY" else if (granted >= 1) "ONTIME" else "LATE"
                }
            }
        )
    }
}

// ═══════════════ 3) EwmaAlertMath: تنبيهات استباقية بلا إزعاج (H3-3) ═══════════════

object EwmaAlertMath {

    /** تقييم تيار واحد (مبيعات/تحصيل/مدفوعات صادرة) */
    data class Eval(
        val level: Double,      // مستوى EWMA الأخير
        val sd: Double,         // σ أخطاء التنبؤ التفاضلية
        val dev: Double,        // |آخر نقطة − المستوى| / σ
        val severity: Int,      // 0 صامت / 1 مراقبة / 2 تحذير / 3 حرج
        val verdict: String     // NORMAL / WATCH / ALERT
    )

    /** تيار مسمى مع مسار الإجراء — وحدة الهضم */
    data class StreamEval(
        val streamKey: String,  // sales / collections / outflows
        val actionKey: String,  // مسار الشاشة المقترح (روت Nav)
        val eval: Eval
    )

    /**
     * (9) تقييم EWMA لتيار يومي: المستوى الأسّي ثم انحراف آخر نقطة عنه
     * بوحدة σ أخطاء التنبؤ (الفرق بين النقطة والمستوى السابق).
     * عقد: n<10 أو σ=0 (تيار ميت/ثابت تماماً) ⇒ null — لا تنبيه من لا تقلب.
     * أرضية القدرة الإحصائية: σ أخطاء أقل من 2% من المستوى ⇒ null صادقة
     * (تيار شبه ساكن لا يملك تبايناً كافياً للحكم — منع الإنذار الكاذب البنيوي).
     * الحِدّ مقيّس بـkSigma: dev ≥ kSigma+0.5 حرج، ≥ kSigma تحذير، ≥ 0.6·kSigma مراقبة
     * (بمفترضيات 2.5: 3.0/2.5/1.5). lambda الافتراضي 0.3 (عقد R12 نفسه).
     */
    fun evaluate(series: List<Double>, lambda: Double = 0.3, kSigma: Double = 2.5): Eval? {
        if (series.size < 10) return null
        if (series.any { !it.isFinite() }) return null
        if (lambda <= 0.0 || lambda >= 1.0 || kSigma <= 0.0) return null
        var level = series.first()
        val errors = mutableListOf<Double>()
        for (i in 1 until series.size) {
            errors += series[i] - level
            level = lambda * series[i] + (1 - lambda) * level
        }
        val m = errors.sum() / errors.size
        val sd = r16Std(errors, m)
        if (sd <= 1e-9) return null
        if (sd < 0.02 * abs(level)) return null // أرضية القدرة: تباين شبه معدوم لا يُحاكم
        val last = series.last()
        val dev = abs(last - level) / sd
        val severity = when {
            dev >= kSigma + 0.5 -> 3
            dev >= kSigma -> 2
            dev >= 0.6 * kSigma -> 1
            else -> 0
        }
        return Eval(
            level = level, sd = sd, dev = dev, severity = severity,
            verdict = if (severity >= 2) "ALERT" else if (severity == 1) "WATCH" else "NORMAL"
        )
    }

    /**
     * (10) حالة استقرار متصدر (hysteresis): الدخول في ALERT يتطلب dev≥kUp
     * والخروج يتطلب انخفاضاً حقيقياً إلى dev<kDown — لا رفرفة إنذار عند
     * العتبة (درس الإشعاعات الكاذبة: القفز حول 2.5 لا يطفئ ويشعل).
     * عقد: حالات الدخل "NORMAL"/"WATCH"/"ALERT" فقط وإلا null.
     */
    fun hysteresis(previous: String, dev: Double, kUp: Double = 2.5, kDown: Double = 1.5): String? {
        if (!dev.isFinite() || dev < 0.0) return null
        if (kUp <= kDown) return null
        return when (previous) {
            "ALERT" -> if (dev >= kDown) "ALERT" else if (dev >= kDown * 0.75) "WATCH" else "NORMAL"
            "WATCH" -> when {
                dev >= kUp -> "ALERT"
                dev >= kDown -> "WATCH"
                else -> "NORMAL"
            }
            "NORMAL" -> when {
                dev >= kUp -> "ALERT"
                dev >= kDown -> "WATCH"
                else -> "NORMAL"
            }
            else -> null
        }
    }

    /**
     * (11) سياسة الإشعار بلا إزعاج: يُعلَن فقط عند حِدّ≥2 مع انقضاء فترة
     * التهدئة (24س افتراضياً) وخارج ساعات الهدوء [22:00 → 07:00).
     * عقد: الطوابع صريحة (بلا قراءة ساعة النظام)؛ أول تنبيه (null سابق)
     * مسموح دائماً عند استيفاء الحِدّ؛ الساعة من [nowMs] بتوقيت الجهاز.
     */
    fun shouldNotify(
        severity: Int,
        lastNotifiedAt: Long?,
        nowMs: Long,
        cooldownHours: Int = 24,
        quietFrom: Int = 22,
        quietTo: Int = 7
    ): Boolean {
        if (severity < 2) return false
        if (lastNotifiedAt != null && lastNotifiedAt > nowMs) return false // طابع مستقبلي معطوب
        if (lastNotifiedAt != null &&
            nowMs - lastNotifiedAt < cooldownHours * 3_600_000L
        ) return false
        val hour = Calendar.getInstance().apply { timeInMillis = nowMs }.get(Calendar.HOUR_OF_DAY)
        val quiet = if (quietFrom > quietTo) hour >= quietFrom || hour < quietTo
                    else hour in quietFrom until quietTo
        return !quiet
    }

    /**
     * (12) هضم التنبيهات: يفلتر الحِدّ≥2 ويرتب بالحِدّ ثم شدة الانحراف
     * ويقص عند [max] — بطاقة واحدة صامتة إن لم يوجد ما يُقال (صدق الفراغ).
     * عقد: max≥1 وإلا قائمة فارغة؛ الترتيب حتمي كامل (تسوية بالمفتاح).
     */
    fun digest(items: List<StreamEval>, max: Int = 3): List<StreamEval> {
        if (max < 1) return emptyList()
        return items.filter { it.eval.severity >= 2 }
            .sortedWith(
                compareByDescending<StreamEval> { it.eval.severity }
                    .thenByDescending { it.eval.dev }
                    .thenBy { it.streamKey }
            )
            .take(max)
    }
}

// ═══════════════ 4) QueryParseMath: استعلام عربي/إنجليزي محدود الدلالة (H3-4) ═══════════════

object QueryParseMath {

    /** النوايا الأربعة المعلنة — خارجها فشل صادق مهذّب */
    enum class Intent { SALES, PROFITS, RECEIVABLES, INVENTORY }

    /** الفترات الزمنية المعلنة */
    enum class Range { TODAY, YESTERDAY, THIS_WEEK, THIS_MONTH, LAST_MONTH, LAST_N_DAYS }

    /** فترة ملتقطة مع n للأيام الحرة («آخر ١٤ يوم») */
    data class RangeHit(val range: Range, val n: Int = 0)

    /** موضوع الاستعلام — صنف أو طرف باسمه الملتحم */
    data class Subject(val name: String, val kind: Kind) {
        enum class Kind { PRODUCT, PARTY }
    }

    /** نتيجة التحليل الناجحة — null تعني «خارج النطاق» الصادق */
    data class Parsed(
        val intent: Intent,
        val range: RangeHit?,
        val subject: Subject?
    )

    /** مفاتيح النوايا — التطابق أول ظهور بالنص المطبَّع (حتمي) */
    private val INTENT_WORDS: List<Pair<Intent, List<String>>> = listOf(
        Intent.SALES to listOf("مبيعات", "بيع", "sales", "revenue", "مبيع", "بيعت"),
        Intent.PROFITS to listOf("ارباح", "ربح", "profits", "profit", "هامش", "margin"),
        Intent.RECEIVABLES to listOf("ذمم", "مديونيات", "متاخرات", "مستحق", "ديون", "debts", "receivables", "owed"),
        Intent.INVENTORY to listOf("مخزون", "بضاعه", "اصناف", "inventory", "stock", "مخزني")
    )

    /** مفاتيح الفترات الثابتة — «آخر N يوم» يُلتقط بجوار رقمه حصراً (انظر parseRange) */
    private val RANGE_WORDS: List<Pair<Range, List<String>>> = listOf(
        Range.TODAY to listOf("اليوم", "today"),
        Range.YESTERDAY to listOf("امس", "البارحه", "yesterday"),
        Range.THIS_WEEK to listOf("هذا الاسبوع", "الاسبوع", "this week", "week"),
        Range.THIS_MONTH to listOf("هذا الشهر", "الشهر", "this month", "month"),
        Range.LAST_MONTH to listOf("الشهر الماضي", "last month")
    )

    /**
     * (13) تطبيع الاستعلام: تشكيل/تطويل/همزات/تاء مربوطة/أرقام هندية/ترقيم —
     * ثم طبّع عربي موحد. حتمي كامل ونفسه على العربي والإنجليزي.
     */
    fun normalize(q: String): String {
        val digits = q.map { c ->
            when (c) {
                in '٠'..'٩' -> ('0' + (c - '٠'))
                in '۰'..'۹' -> ('0' + (c - '۰'))
                else -> c
            }
        }.joinToString("")
        val bare = digits.replace(Regex("[\\p{Punct}؟?،,؛;:!«»\"'()\\[\\]{}]+"), " ")
        return TextMath.arabicNormalize(bare).replace(Regex("\\s+"), " ").trim()
    }

    /**
     * (14) التقط الفترة من نص مطبَّع — الأولوية للأطول تطابقاً (الشهر الماضي
     * قبل الشهر) ثم لأول مفتاح مرتّب. «آخر N يوم» يُلتقط بجوار رقمه حصراً
     * (اخر|last + رقم + يوم/days) كي لا يخطف «المتأخرة» زوراً — لا رقم ⇒ لا التقاط.
     */
    fun parseRange(normalized: String): RangeHit? {
        // «آخر N يوم» أولاً — نمط مقيّد برقم صريح
        Regex("(?:اخر|last)\\s*(\\d{1,3})\\s*(?:يوم|days?)").find(normalized)?.let {
            return RangeHit(Range.LAST_N_DAYS, it.groupValues[1].toIntOrNull() ?: 0)
        }
        var best: RangeHit? = null
        var bestLen = 0
        RANGE_WORDS.forEach { (range, words) ->
            words.forEach { w ->
                if (normalized.contains(w) && w.length > bestLen) {
                    bestLen = w.length
                    best = RangeHit(range)
                }
            }
        }
        return best
    }

    /**
     * (15) التقط الموضوع: أطول اسم موجود نصّه المطبَّع داخل الاستعلام —
     * مطابقة حرفية بعد التطبيع فقط (لا سحر ضبابي — فشل حتمي قابل للاختبار).
     */
    fun parseSubject(normalized: String, productNames: List<String>, partyNames: List<String>): Subject? {
        var best: Subject? = null
        var bestLen = 0
        productNames.forEach { p ->
            val np = normalize(p)
            if (np.length > bestLen && np.isNotEmpty() && normalized.contains(np)) {
                bestLen = np.length
                best = Subject(p, Subject.Kind.PRODUCT)
            }
        }
        partyNames.forEach { p ->
            val np = normalize(p)
            if (np.length > bestLen && np.isNotEmpty() && normalized.contains(np)) {
                bestLen = np.length
                best = Subject(p, Subject.Kind.PARTY)
            }
        }
        return best
    }

    /**
     * (16) التحليل الكامل: نية + فترة + موضوع. الفشل الصادق: لا نية ظاهرة
     * (ولا موضوع وحيد يدل عليها) ⇒ null — رسالة «خارج النطاق» مهذبة في الواجهة.
     * عقد: موضوع بلا نية يستنتج نيته (صنف ⇒ مخزون، طرف ⇒ ذمم) — قاعدة معلنة.
     */
    fun parse(
        q: String,
        productNames: List<String> = emptyList(),
        partyNames: List<String> = emptyList()
    ): Parsed? {
        val n = normalize(q)
        if (n.isEmpty()) return null
        val intent = INTENT_WORDS.firstOrNull { (intent, words) ->
            words.any { n.contains(it) }
        }?.first
        val subject = parseSubject(n, productNames, partyNames)
        val resolved = intent ?: when (subject?.kind) {
            Subject.Kind.PRODUCT -> Intent.INVENTORY
            Subject.Kind.PARTY -> Intent.RECEIVABLES
            null -> return null
        } ?: return null
        return Parsed(resolved, parseRange(n), subject)
    }

    /**
     * (17) حل الفترة إلى مجال ‎(من، إلى)‎ بطوابع صريحة — بلا قراءة ساعة النظام.
     * عقد: الافتراضي الشهر الحالي حين لا فترة مذكورة؛ أسبوع يبدأ السبت
     * (عرف KSA_WEEKEND)؛ «اخر N يوم» بلا رقم صحيح (1..365) ⇒ الشهر الحالي.
     */
    fun resolveRange(parsed: Parsed, today: Long): Pair<Long, Long> {
        val hit = parsed.range ?: RangeHit(Range.THIS_MONTH)
        val startToday = TimeMath.startOfDay(today)
        return when (hit.range) {
            Range.TODAY -> startToday to today
            Range.YESTERDAY -> {
                val s = startToday - 86_400_000L
                s to s + 86_400_000L - 1
            }
            Range.THIS_WEEK -> {
                val cal = Calendar.getInstance().apply { timeInMillis = startToday }
                var back = 0
                // الأسبوع يبدأ السبت: نرجع حتى السبت
                while (true) {
                    val dow = cal.get(Calendar.DAY_OF_WEEK)
                    if (dow == Calendar.SATURDAY) break
                    back++
                    cal.add(Calendar.DAY_OF_MONTH, -1)
                    if (back > 7) break
                }
                val from = TimeMath.startOfDay(cal.timeInMillis)
                from to today
            }
            Range.THIS_MONTH -> {
                val (f, _) = TimeMath.monthBounds(today)
                f to today
            }
            Range.LAST_MONTH -> {
                val cal = Calendar.getInstance().apply {
                    timeInMillis = today
                    add(Calendar.MONTH, -1)
                }
                val (f, t) = TimeMath.monthBounds(cal.timeInMillis)
                f to t
            }
            Range.LAST_N_DAYS -> {
                val n = hit.n
                if (n < 1 || n > 365) {
                    val (f, _) = TimeMath.monthBounds(today)
                    f to today
                } else {
                    (startToday - (n - 1) * 86_400_000L) to today
                }
            }
        }
    }
}

// ═══════════════ 5) NarrativeMath: روايات KPI بأصول معلنة (H3-5) ═══════════════

object NarrativeMath {

    /** وقائع المقارنة الشهرية — كلها ريال Double بعد التحويل في المستودع */
    data class KpiFacts(
        val salesNow: Double,
        val salesPrev: Double,
        val profitNow: Double,
        val profitPrev: Double,
        val expensesNow: Double,
        val expensesPrev: Double,
        val overdueNow: Double,
        val overduePrev: Double,
        val salesTarget: Double? = null
    )

    /** سطر رواية بنيوي: مفتاح تتمة + أرقامه + أساسه الخام (شفافية كاملة) */
    data class Story(
        val key: String,       // مفتاح سلسلة الواجهة (تُترجم ar/en هناك)
        val args: List<Double>,// وسائط التنسيق بالترتيب
        val basis: String      // الأصل الخام — يُعرض «كيف تُحسب؟» حرفياً
    )

    /**
     * (18) بناء الوقائع: لا يُقبل غير منتهٍ؛ سالب المبيعات/المصروفات خطأ
     * إدخال ⇒ null. الهدف السالب يُهمَل (بلا هدف).
     */
    fun facts(
        salesNow: Double, salesPrev: Double,
        profitNow: Double, profitPrev: Double,
        expensesNow: Double, expensesPrev: Double,
        overdueNow: Double, overduePrev: Double,
        salesTarget: Double? = null
    ): KpiFacts? {
        val all = listOf(salesNow, salesPrev, profitNow, profitPrev,
            expensesNow, expensesPrev, overdueNow, overduePrev)
        if (!r16Finite(all)) return null
        if (salesNow < 0 || salesPrev < 0 || expensesNow < 0 || expensesPrev < 0 ||
            overdueNow < 0 || overduePrev < 0) return null
        val target = salesTarget?.takeIf { it.isFinite() && it > 0.0 }
        return KpiFacts(salesNow, salesPrev, profitNow, profitPrev,
            expensesNow, expensesPrev, overdueNow, overduePrev, target)
    }

    /**
     * (19) الروايات: قاعدة واحدة لكل سطر — لا نسبة على صفر (سطر بلا أساس
     * يُسقَط كلياً لا يُعْلَن «صفر» مزيّفاً). الترتيب حتمي: مبيعات، هامش،
     * مصروفات، ذمم، هدف.
     */
    fun narrate(f: KpiFacts): List<Story> {
        val out = mutableListOf<Story>()

        // 1) اتجاه المبيعات
        if (f.salesPrev > 0.0) {
            val pct = (f.salesNow - f.salesPrev) / f.salesPrev * 100.0
            val key = when {
                pct > 5.0 -> "r16_story_sales_up"
                pct < -5.0 -> "r16_story_sales_down"
                else -> "r16_story_sales_flat"
            }
            out += Story(key, listOf(abs(pct), f.salesNow, f.salesPrev),
                basis = "sales ${r16Trim(f.salesPrev)} → ${r16Trim(f.salesNow)}")
        }

        // 2) الهامش (نسبة الربح من المبيعات)
        if (f.salesNow > 0.0) {
            val margin = f.profitNow / f.salesNow * 100.0
            out += Story("r16_story_margin", listOf(margin, f.profitNow),
                basis = "profit ${r16Trim(f.profitNow)} / sales ${r16Trim(f.salesNow)}")
        }

        // 3) حصة المصروفات من المبيعات — صعودها إنذار هادئ
        if (f.salesNow > 0.0 && f.salesPrev > 0.0) {
            val shareNow = f.expensesNow / f.salesNow * 100.0
            val sharePrev = f.expensesPrev / f.salesPrev * 100.0
            if (abs(shareNow - sharePrev) >= 1.0) {
                out += Story(
                    if (shareNow > sharePrev) "r16_story_expenses_up" else "r16_story_expenses_down",
                    listOf(sharePrev, shareNow),
                    basis = "expenses/sales ${r16Trim(f.expensesPrev)}/${r16Trim(f.salesPrev)} → ${r16Trim(f.expensesNow)}/${r16Trim(f.salesNow)}"
                )
            }
        }

        // 4) الذمم المتأخرة
        if (f.overduePrev > 0.0) {
            val pct = (f.overdueNow - f.overduePrev) / f.overduePrev * 100.0
            out += Story(
                if (pct > 5.0) "r16_story_overdue_up" else if (pct < -5.0) "r16_story_overdue_down" else "r16_story_overdue_flat",
                listOf(abs(pct), f.overdueNow),
                basis = "overdue ${r16Trim(f.overduePrev)} → ${r16Trim(f.overdueNow)}"
            )
        } else if (f.overduePrev <= 0.0 && f.overdueNow > 0.0) {
            out += Story("r16_story_overdue_new", listOf(f.overdueNow),
                basis = "overdue 0 → ${r16Trim(f.overdueNow)}")
        }

        // 5) وتيرة الهدف
        f.salesTarget?.let { t ->
            if (t > 0.0 && f.salesNow >= 0.0) {
                val pct = f.salesNow / t * 100.0
                out += Story("r16_story_target", listOf(pct, t),
                    basis = "sales ${r16Trim(f.salesNow)} / target ${r16Trim(t)}")
            }
        }
        return out
    }

    /**
     * (20) سطر الإسناد الكامل: كل مدخلات الوقائع بأرقامها الخام — السطر
     * الذي يظهر تحت الروايات «الأرقام الخام خلف أي رواية على بعد لمسة».
     */
    fun provenance(f: KpiFacts): List<String> = listOf(
        "sales ${r16Trim(f.salesPrev)} → ${r16Trim(f.salesNow)}",
        "profit ${r16Trim(f.profitPrev)} → ${r16Trim(f.profitNow)}",
        "expenses ${r16Trim(f.expensesPrev)} → ${r16Trim(f.expensesNow)}",
        "overdue ${r16Trim(f.overduePrev)} → ${r16Trim(f.overdueNow)}"
    )

    /** تقليم العرض: صحيح بلا كسور إن طابق، وإلا خانتان — بلا أصفار وهمية */
    private fun r16Trim(v: Double): String {
        val r2 = Math.round(v * 100.0) / 100.0
        return if (r2 == Math.round(r2).toDouble()) Math.round(r2).toString()
        else String.format(java.util.Locale.US, "%.2f", r2)
    }
}
