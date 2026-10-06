package com.superbiz.app.domain.algo

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * — عشرون خوارزمية حتمية جديدة للموجة R12، في 15 كائناً مستقلياً
 * لا يتقاطع أيّها مع R7/R9/R10/R11 ولا مع كائنات الحساب الأساسية
 *
 * AnomalyMath (1-2) : حدود EWMA الرقابية + كشف الانزياح CUSUM
 * CompareMath (3) : مقارنة فترات (شهر/سنة)
 * PaymentMath (4-5) : مزيج طرق الدفع + انتظام التوقيت
 * TurnMath (6-7) : معدل دوران المخزون + نسبة البيع من الوارد
 * QuartileMath (8) : تصنيف الأرباع
 * HourMath (9) : نافذة ساعات العمل الفعّالة
 * VoidMath (10) : اتجاه إلغاء الفواتير
 * UpliftMath (11) : تفكيك أثر تغيير السعر
 * CostMath (12) : زحف تكلفة الشراء
 * PayoffMath (13) : أشهر التسوية المتبقية
 * DunningMath (14) : سلّم مطالبة الذمم
 * AuditMath (15-16) : مدفوعات يتيمة + فواتير مسددة زيادة
 * CatalogMath (17-18) : منتجات لم تُبع أبداً + فئات بلا مبيعات
 * CartMath (19) : اتجاه حجم السلة (أسطر/فاتورة)
 * ShareMath (20) : انجراف تركّز العملاء (HHI)
 *
 * عقد الصدق نفسه: بلا بيانات كافية أو بمدخلات غير منتهية ⇒ null/قائمة فارغة،
 * ولا يُقرأ وقت النظام داخل أي خوارزمية — كلها دوال حتمية خالصة قابلة للاختبار.
*/

private fun allFinite(values: List<Double>): Boolean = values.all { it.isFinite() }

private fun meanOf(values: List<Double>): Double = values.sum() / values.size

private fun stdOf(values: List<Double>, m: Double): Double =
    sqrt(values.sumOf { (it - m) * (it - m) } / values.size)

// ═══════════════ 1-2) AnomalyMath: حدود EWMA + CUSUM ═══════════════

object AnomalyMath {

    /** نطاقات مراقبة EWMA بخط أساس من الطور الأول: النصف الأول من السلسلة يحدد خط الأساس
     *  (المتوسط والتشتت)، ثم نراقب النصف الثاني: z_t = λ·x_t + (1−λ)·z_{t−1} بدءاً من خط الأساس،
     *  والحدود baseline ± k·σ·√(λ/(2−λ)). إذا كان تشتت خط الأساس صفراً فإن أي انحراف فعلي يُعد خرقاً
     *  (العتبة تصبح 1e-9). يُرجع null إذا كانت النقاط أقل من 8 أو فيها قيمة غير منتهية.
     *  breachIndex فهرس مبني على الصفر داخل السلسلة الكاملة. */
    data class EwmaBands(
        val baseline: Double,
        val upper: Double,
        val lower: Double,
        val breachIndex: Int?,   // أول نقطة خرجت عن النطاق (فهرس مبني على الصفر)
        val verdict: String,     // STABLE / SHIFT
    )

    fun ewmaBands(series: List<Double>, lambda: Double = 0.3, kSigma: Double = 3.0): EwmaBands? {
        if (series.size < 8 || !allFinite(series)) return null
        val half = series.size / 2
        val base = series.take(half)
        val mu = meanOf(base)
        val sigma = stdOf(base, mu)
        var limit = kSigma * sigma * sqrt(lambda / (2.0 - lambda))
        if (sigma == 0.0) limit = 1e-9   // خط أساس ثابت تماماً ⇒ أي انحراف فعلي خرق موثق
        var z = mu
        var breach: Int? = null
        for (i in half until series.size) {
            z = lambda * series[i] + (1.0 - lambda) * z
            if (breach == null && abs(z - mu) > limit) breach = i
        }
        return EwmaBands(
            baseline = mu,
            upper = mu + limit,
            lower = mu - limit,
            breachIndex = breach,
            verdict = if (breach != null) "SHIFT" else "STABLE",
        )
    }

    /** كشف الانزياح CUSUM: يجمّع الانحرافات المعيارية عن المتوسط ويكشف اتجاهاً صاعداً
     *  أو هابطاً مستمراً أعلى العتبة (افتراضياً 5 انحرافات معيارية متراكمة).
     *  يُرجع null إذا كانت النقاط أقل من 6 أو فيها قيمة غير منتهية أو التشتت صفري. */
    data class Cusum(val hi: Double, val lo: Double, val verdict: String) // RISING / FALLING / STABLE

    fun cusumShift(series: List<Double>, threshold: Double = 5.0): Cusum? {
        if (series.size < 6 || !allFinite(series)) return null
        val mu = meanOf(series)
        val sigma = stdOf(series, mu)
        if (sigma == 0.0) return Cusum(0.0, 0.0, "STABLE")
        var up = 0.0
        var down = 0.0
        var hi = 0.0
        var lo = 0.0
        for (x in series) {
            val z = (x - mu) / sigma
            up = max(0.0, up + z - 0.5)
            down = max(0.0, down - z - 0.5)
            hi = max(hi, up)
            lo = max(lo, down)
        }
        return Cusum(
            hi = hi,
            lo = lo,
            verdict = when {
                hi >= threshold -> "RISING"
                lo >= threshold -> "FALLING"
                else -> "STABLE"
            },
        )
    }
}

// ═══════════════ 3) CompareMath: مقارنة فترات ═══════════════

object CompareMath {

    /** مقارنة الفترة الحالية بالسابقة وبنفس فترة السنة الماضية.
     *  previous/yearAgo قد تكونان null إن لم تتوفر سِجِل — عندها الحكم NEW بدل اختلاق نسبة.
     *  current سالب أو غير منتهٍ ⇒ null. */
    data class PeriodCompare(
        val current: Double,
        val previous: Double?,
        val yearAgo: Double?,
        val momPct: Double?,     // التغير عن الفترة السابقة
        val yoyPct: Double?,     // التغير عن نفس فترة السنة الماضية
        val verdict: String,     // UP / DOWN / FLAT / NEW
    )

    fun periodCompare(current: Double, previous: Double?, yearAgo: Double?): PeriodCompare? {
        if (!current.isFinite()) return null
        fun pct(now: Double, base: Double?): Double? {
            if (base == null || !base.isFinite() || base == 0.0) return null
            return (now - base) / abs(base) * 100.0
        }
        val mom = pct(current, previous)
        val yoy = pct(current, yearAgo)
        val verdict = when {
            previous == null -> "NEW"
            mom == null -> "FLAT"
            mom > 2.0 -> "UP"
            mom < -2.0 -> "DOWN"
            else -> "FLAT"
        }
        return PeriodCompare(current, previous, yearAgo, mom, yoy, verdict)
    }
}

// ═══════════════ 4-5) PaymentMath: مزيج الدفع + التوقيت ═══════════════

object PaymentMath {

    /** مزيج طرق الدفع على المفتوح: نصيب كل طريقة من إجمالي المقبوض.
     *  total <= 0 ⇒ null. الحكم: CHECK_HEAVY إذا تجاوزت الشيكات 40%، وإلا BALANCED/CASH_ONLY. */
    data class Mix(val cashPct: Double, val checkPct: Double, val otherPct: Double, val verdict: String)

    fun mix(cash: Double, check: Double, other: Double): Mix? {
        if (listOf(cash, check, other).any { !it.isFinite() || it < 0.0 }) return null
        val total = cash + check + other
        if (total <= 0.0) return null
        val cashPct = cash / total * 100.0
        val checkPct = check / total * 100.0
        val otherPct = other / total * 100.0
        val verdict = when {
            checkPct > 40.0 -> "CHECK_HEAVY"
            cashPct >= 99.5 -> "CASH_ONLY"
            else -> "BALANCED"
        }
        return Mix(cashPct, checkPct, otherPct, verdict)
    }

    /** انتظام توقيت التحصيل: لكل دفعة فرق أيامها عن الاستحقاق (سالب = مبكر، 0 = في اليوم، موجب = متأخر).
     *  قائمة فارغة ⇒ null. الحكم: SLIPPING إذا تجاوز المتأخر 30%، PROMPT إذا كان المبكر ≥ 40%. */
    data class Timing(val earlyPct: Double, val onTimePct: Double, val latePct: Double, val verdict: String)

    fun timing(daysRelDue: List<Int>): Timing? {
        if (daysRelDue.isEmpty()) return null
        val n = daysRelDue.size.toDouble()
        val earlyPct = daysRelDue.count { it < 0 } / n * 100.0
        val onTimePct = daysRelDue.count { it == 0 } / n * 100.0
        val latePct = daysRelDue.count { it > 0 } / n * 100.0
        val verdict = when {
            latePct > 30.0 -> "SLIPPING"
            earlyPct >= 40.0 -> "PROMPT"
            else -> "NORMAL"
        }
        return Timing(earlyPct, onTimePct, latePct, verdict)
    }
}

// ═══════════════ 6-7) TurnMath: دوران المخزون ═══════════════

object TurnMath {

    /** معدل دوران المخزون مُسنَداً: (تكلفة المبيعات للفترة ÷ متوسط قيمة المخزون) × (365 ÷ أيام الفترة).
     *  أي مدخل <= 0 أو غير منتهٍ ⇒ null. الحكم: FAST ≥ 6، OK ≥ 3، وإلا SLOW. */
    data class Turnover(val turns: Double, val daysOnHand: Double, val verdict: String)

    fun turnover(cogsPeriod: Double, avgStockValue: Double, periodDays: Int = 90): Turnover? {
        if (periodDays <= 0) return null
        if (!cogsPeriod.isFinite() || !avgStockValue.isFinite()) return null
        if (cogsPeriod <= 0.0 || avgStockValue <= 0.0) return null
        val turnsAnnual = cogsPeriod / avgStockValue * (365.0 / periodDays)
        val days = 365.0 / turnsAnnual
        val verdict = when {
            turnsAnnual >= 6.0 -> "FAST"
            turnsAnnual >= 3.0 -> "OK"
            else -> "SLOW"
        }
        return Turnover(turnsAnnual, days, verdict)
    }

    /** نسبة البيع من الوارد (Sell-through): كم بِيع من الكمية التي دخلت المخزون.
     *  receivedQty <= 0 ⇒ null. الحكم: HOT ≥ 70%، NORMAL ≥ 30%، وإلا STALE. */
    data class SellThrough(val soldPct: Double, val verdict: String)

    fun sellThrough(receivedQty: Double, soldQty: Double): SellThrough? {
        if (!receivedQty.isFinite() || !soldQty.isFinite()) return null
        if (receivedQty <= 0.0 || soldQty < 0.0) return null
        val pct = (soldQty / receivedQty * 100.0).coerceIn(0.0, 100.0)
        val verdict = when {
            pct >= 70.0 -> "HOT"
            pct >= 30.0 -> "NORMAL"
            else -> "STALE"
        }
        return SellThrough(pct, verdict)
    }
}

// ═══════════════ 8) QuartileMath: تصنيف الأرباع ═══════════════

object QuartileMath {

    /** ترتيب العناصر بالقيمة وتصنيفها إلى أرباع (1 = الربع الأعلى).
     *  الحدود من المئينات بأسلوب الاستيفاء الخطي. أقل من 4 عناصر أو قيمة غير منتهية ⇒ null. */
    data class QRow(val name: String, val value: Double, val quartile: Int) // 1..4 (1 = الأعلى)

    fun classify(named: List<Pair<String, Double>>): List<QRow>? {
        if (named.size < 4 || named.any { !it.second.isFinite() }) return null
        val sorted = named.map { it.second }.sorted()
        fun pctRank(p: Double): Double {
            val idx = p * (sorted.size - 1)
            val lo = idx.toInt()
            val hi = min(lo + 1, sorted.size - 1)
            val frac = idx - lo
            return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
        }
        val q1 = pctRank(0.25)
        val q2 = pctRank(0.50)
        val q3 = pctRank(0.75)
        return named.sortedByDescending { it.second }.map { (name, v) ->
            val q = when {
                v >= q3 -> 1
                v >= q2 -> 2
                v >= q1 -> 3
                else -> 4
            }
            QRow(name, v, q)
        }
    }
}

// ═══════════════ 9) HourMath: نافذة الساعات الفعّالة ═══════════════

object HourMath {

    /** نافذة ساعات النشاط: أطول امتداد متصل في اليوم تكون مبيعاته ≥ نسبة مئوية من ساعة الذروة
     *  (افتراضياً 20%). تُرجع null إذا كان اليوم فارغاً كلياً.
     *  الحكم: FOCUSED ≤ 4 ساعات، NORMAL ≤ 8، وإلا SPREAD. */
    data class ActiveSpan(val startHour: Int, val endHour: Int, val spanHours: Int, val verdict: String)

    fun activeSpan(hourTotals: DoubleArray, floorPct: Double = 20.0): ActiveSpan? {
        if (hourTotals.size != 24 || hourTotals.any { !it.isFinite() || it < 0.0 }) return null
        val peak = hourTotals.max()
        if (peak <= 0.0) return null
        val floor = peak * floorPct / 100.0
        var bestStart = -1
        var bestLen = 0
        var curStart = -1
        var curLen = 0
        for (h in 0 until 24) {
            if (hourTotals[h] >= floor) {
                if (curStart < 0) curStart = h
                curLen++
                if (curLen > bestLen) { bestLen = curLen; bestStart = curStart }
            } else {
                curStart = -1
                curLen = 0
            }
        }
        if (bestStart < 0) return null
        val verdict = when {
            bestLen <= 4 -> "FOCUSED"
            bestLen <= 8 -> "NORMAL"
            else -> "SPREAD"
        }
        return ActiveSpan(bestStart, bestStart + bestLen - 1, bestLen, verdict)
    }
}

// ═══════════════ 10) VoidMath: اتجاه الإلغاء ═══════════════

object VoidMath {

    /** اتجاه إلغاء الفواتير أسبوعياً: كل أسبوع (ملغاة، إجمالي). الأسابيع بلا فواتير تُهمل،
     *  وأقل من أسبوعين صالحين ⇒ null. الصعود إذا تكسّرت النسبة الأسبوع الأخير بعتبة 2 نقطة.
     *  الحكم: ALARM ≥ 10%، WATCH ≥ 4%، وإلا OK. */
    data class VoidStat(val voided: Int, val total: Int, val pct: Double)
    data class VoidReport(val recent: VoidStat, val priorPct: Double, val rising: Boolean, val verdict: String)

    fun voidTrend(weeks: List<Pair<Int, Int>>): VoidReport? {
        val valid = weeks.filter { it.second > 0 }
        if (valid.size < 2) return null
        val recent = valid.last()
        val prior = valid.dropLast(1)
        fun pctOf(w: Pair<Int, Int>): Double = w.first.toDouble() / w.second * 100.0
        val recentPct = pctOf(recent)
        val priorPct = prior.map { pctOf(it) }.average()
        val rising = recentPct > priorPct + 2.0
        val verdict = when {
            recentPct >= 10.0 -> "ALARM"
            recentPct >= 4.0 -> "WATCH"
            else -> "OK"
        }
        return VoidReport(VoidStat(recent.first, recent.second, recentPct), priorPct, rising, verdict)
    }
}

// ═══════════════ 11) UpliftMath: تفكيك أثر السعر ═══════════════

object UpliftMath {

    /** تفكيك أثر تغيير السعر على الإيراد: أثر السعر ΔP·Q1 + أثر الكمية ΔQ·P0 + الأثر التقاطعي ΔP·ΔQ.
     *  يتطلب الأسعار والكميات الأربع موجبة ومنتهية، وإلا null.
     *  الحكم مقابل 0.5% من الإيراد القديم: WIN / LOSS / NEUTRAL. */
    data class Uplift(
        val oldRevenue: Double,
        val newRevenue: Double,
        val revenueDelta: Double,
        val priceEffect: Double,
        val volumeEffect: Double,
        val crossEffect: Double,
        val verdict: String,
    )

    fun priceUplift(oldPrice: Double, oldQty: Double, newPrice: Double, newQty: Double): Uplift? {
        val inputs = listOf(oldPrice, oldQty, newPrice, newQty)
        if (inputs.any { !it.isFinite() || it <= 0.0 }) return null
        val oldRev = oldPrice * oldQty
        val newRev = newPrice * newQty
        val delta = newRev - oldRev
        val priceEffect = (newPrice - oldPrice) * newQty
        val volumeEffect = (newQty - oldQty) * oldPrice
        val cross = (newPrice - oldPrice) * (newQty - oldQty)
        val eps = oldRev * 0.005
        val verdict = when {
            delta > eps -> "WIN"
            delta < -eps -> "LOSS"
            else -> "NEUTRAL"
        }
        return Uplift(oldRev, newRev, delta, priceEffect, volumeEffect, cross, verdict)
    }
}

// ═══════════════ 12) CostMath: زحف تكلفة الشراء ═══════════════

object CostMath {

    /** زحف تكلفة الشراء الشهرية: انحدار خطي على متوسط تكلفة الوحدة لكل شهر.
     *  يتطلب ≥ 4 أشهر بكل قيمتها موجبة ومنتهية، وإلا null.
     *  الحكم على نسبة الميل الشهرية من المتوسط: SPIKE > 8%، CREEPING > 2%،
     *  FALLING إذا كان الارتفاع الكلي أقل من −5%، وإلا STABLE. */
    data class Creep(val first: Double, val last: Double, val slopePctPerMonth: Double, val risePct: Double, val verdict: String)

    fun costCreep(unitCostByMonth: List<Double>): Creep? {
        if (unitCostByMonth.size < 4 || unitCostByMonth.any { !it.isFinite() || it <= 0.0 }) return null
        val n = unitCostByMonth.size
        val xs = (0 until n).map { it.toDouble() }
        val mx = meanOf(xs)
        val my = meanOf(unitCostByMonth)
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            num += (xs[i] - mx) * (unitCostByMonth[i] - my)
            den += (xs[i] - mx) * (xs[i] - mx)
        }
        val slope = num / den
        val slopePct = slope / my * 100.0
        val risePct = (unitCostByMonth.last() - unitCostByMonth.first()) / unitCostByMonth.first() * 100.0
        val verdict = when {
            slopePct > 8.0 -> "SPIKE"
            slopePct > 2.0 -> "CREEPING"
            risePct < -5.0 -> "FALLING"
            else -> "STABLE"
        }
        return Creep(unitCostByMonth.first(), unitCostByMonth.last(), slopePct, risePct, verdict)
    }
}

// ═══════════════ 13) PayoffMath: أشهر التسوية ═══════════════

object PayoffMath {

    /** أشهر التسوية المتبقية: كم شهراً يحتاج سداد المفتوح بمتوسط الدفعة الشهرية الحالية.
     *  المفتوح <= 0 (لا دين) أو الدفعة <= 0 ⇒ null.
     *  الحكم: SHORT ≤ 3 أشهر، MEDIUM ≤ 8، وإلا LONG. */
    data class Payoff(val months: Int, val totalOpen: Double, val avgMonthlyPay: Double, val verdict: String)

    fun monthsToClear(totalOpen: Double, avgMonthlyPay: Double): Payoff? {
        if (!totalOpen.isFinite() || !avgMonthlyPay.isFinite()) return null
        if (totalOpen <= 0.0 || avgMonthlyPay <= 0.0) return null
        val months = ceil(totalOpen / avgMonthlyPay).toInt()
        val verdict = when {
            months <= 3 -> "SHORT"
            months <= 8 -> "MEDIUM"
            else -> "LONG"
        }
        return Payoff(months, totalOpen, avgMonthlyPay, verdict)
    }
}

// ═══════════════ 14) DunningMath: سلّم المطالبة ═══════════════

object DunningMath {

    /** سلّم مطالبة الذمم: توزيع المديونية المفتوحة على مراحل حسب أيام التأخر:
     *  CURRENT (لم تستحق) / REMIND (1-15) / URGE (16-45) / FINAL (46-90) / COLLECT (> 90).
     *  قائمة فارغة أو مجموعها <= 0 ⇒ null. worst = المرحلة الأثقل بعد CURRENT. */
    data class StageRow(val stage: String, val count: Int, val amount: Double)
    data class Dunning(val rows: List<StageRow>, val totalOpen: Double, val worst: String?)

    fun stages(rows: List<Pair<Double, Int>>): Dunning? {
        if (rows.isEmpty() || rows.any { !it.first.isFinite() || it.first < 0.0 }) return null
        val total = rows.sumOf { it.first }
        if (total <= 0.0) return null
        val order = listOf("CURRENT", "REMIND", "URGE", "FINAL", "COLLECT")
        fun bucket(days: Int): String = when {
            days <= 0 -> "CURRENT"
            days <= 15 -> "REMIND"
            days <= 45 -> "URGE"
            days <= 90 -> "FINAL"
            else -> "COLLECT"
        }
        val agg = rows.groupBy { bucket(it.second) }
            .map { (stage, list) -> StageRow(stage, list.size, list.sumOf { it.first }) }
            .sortedBy { order.indexOf(it.stage) }
        val worst = agg.filter { it.stage != "CURRENT" && it.amount > 0.0 }
            .maxByOrNull { it.amount }?.stage
        return Dunning(agg, total, worst)
    }
}

// ═══════════════ 15-16) AuditMath: نظافة البيانات المالية ═══════════════

object AuditMath {

    /** مدفوعات يتيمة: صفوف دفعات ما زالت تشير إلى فواتير غير موجودة (مُلغاة الحذف من مسار قديم).
     *  payments: (معرف الدفعة، معرف الفاتورة أو null، المبلغ). القيمة التي لا فاتورة لها ⇒ يتيمة. */
    data class OrphanRow(val paymentId: Long, val invoiceId: Long, val amount: Double)

    fun orphanPayments(payments: List<Triple<Long, Long?, Double>>, validInvoiceIds: Set<Long>): List<OrphanRow> {
        return payments.mapNotNull { (pid, iid, amount) ->
            val inv = iid ?: return@mapNotNull null
            if (inv !in validInvoiceIds) OrphanRow(pid, inv, amount) else null
        }
    }

    /** فواتير مسددة زيادة: المدفوع يتجاوز الإجمالي بأكثر من قرش واحد (خطأ إدخال أو تسوية مكررة).
     *  rows: (المعرف، الإجمالي، المدفوع). المراتب حسب حجم الزيادة تنازلياً. */
    data class Overpaid(val invoiceId: Long, val total: Double, val paid: Double, val excess: Double)

    fun overpaidInvoices(rows: List<Triple<Long, Double, Double>>): List<Overpaid> {
        return rows.mapNotNull { (id, total, paid) ->
            if (!total.isFinite() || !paid.isFinite()) return@mapNotNull null
            if (paid - total > 0.01) Overpaid(id, total, paid, paid - total) else null
        }.sortedByDescending { it.excess }
    }
}

// ═══════════════ 17-18) CatalogMath: جودة الكتالوج ═══════════════

object CatalogMath {

    data class CatItem(val id: Long, val name: String, val stockQty: Double, val stockValue: Double)

    /** منتجات لم تُبع أبداً: من الكتالوج النشط ما لا يظهر في مجموعة معرفات المبيعات.
     *  الترتيب حسب رأس المال الراكد تنازلياً — يكشف أخطاء الكتالوج لا المخزون فقط. */
    fun neverSold(items: List<CatItem>, soldProductIds: Set<Long>): List<CatItem> {
        return items.filter { it.id !in soldProductIds }
            .sortedByDescending { it.stockValue }
    }

    /** فئات لها منتجات لكن صفر مبيعات: سداد فجوة العرض/الطلب على مستوى الفئة.
     *  أسماء الفئات الفارغة تُهمل. الترتيب حسب عدد المنتجات تنازلياً. */
    data class CatGap(val category: String, val products: Int, val soldProducts: Int)

    fun categoryGaps(productsByCat: Map<String, Int>, soldByCat: Map<String, Int>): List<CatGap> {
        return productsByCat.mapNotNull { (cat, count) ->
            if (cat.isBlank()) return@mapNotNull null
            val sold = soldByCat[cat] ?: 0
            if (sold == 0 && count > 0) CatGap(cat, count, 0) else null
        }.sortedByDescending { it.products }
    }
}

// ═══════════════ 19) CartMath: اتجاه حجم السلة ═══════════════

object CartMath {

    /** اتجاه حجم السلة أسبوعياً: متوسط عدد أسطر الفاتورة لكل فاتورة.
     *  weeks: (مجموع الأسطر، عدد الفواتير) لكل أسبوع؛ الأسابيع بلا فواتير تُهمل،
     *  وأقل من 4 أسابيع صالحة ⇒ null. الحكم على الزخم: GROWING > +5%، SHRINKING < −5%، وإلا FLAT. */
    data class CartTrend(val latest: Double, val momentumPct: Double?, val direction: String)

    fun cartSizeTrend(weeks: List<Pair<Double, Int>>): CartTrend? {
        val valid = weeks.filter { it.second > 0 && it.first.isFinite() && it.first >= 0.0 }
        if (valid.size < 4) return null
        val avgs = valid.map { (lines, invs) -> lines / invs }
        val first = avgs.first()
        val last = avgs.last()
        val momentum = if (first > 0.0) (last - first) / first * 100.0 else null
        val direction = when {
            momentum == null -> "FLAT"
            momentum > 5.0 -> "GROWING"
            momentum < -5.0 -> "SHRINKING"
            else -> "FLAT"
        }
        return CartTrend(last, momentum, direction)
    }
}

// ═══════════════ 20) ShareMath: انجراف تركّز العملاء ═══════════════

object ShareMath {

    /** انجراف تركّز العملاء: مؤشر HHI (مجموع مربعات الحصص، مقياس 0..1) للفترة الحالية مقابل
     *  الفترة السابقة، مع نصيب العميل الأكبر في كل فترة. فترة بلا إيراد ⇒ null.
     *  الحكم على فرق HHI بعتبة 0.01: CONCENTRATING / DIVERSIFYING / STABLE. */
    data class ShareDrift(
        val hhiNow: Double,
        val hhiBefore: Double,
        val topShareNowPct: Double,
        val topShareBeforePct: Double,
        val verdict: String,
    )

    fun concentrationDrift(revenueByPartyNow: Map<String, Double>, revenueByPartyBefore: Map<String, Double>): ShareDrift? {
        fun hhi(m: Map<String, Double>): Double? {
            val vals = m.values.filter { it.isFinite() && it > 0.0 }
            if (vals.isEmpty()) return null
            val total = vals.sum()
            if (total <= 0.0) return null
            return vals.sumOf { (it / total) * (it / total) }
        }
        fun topPct(m: Map<String, Double>): Double? {
            val vals = m.values.filter { it.isFinite() && it > 0.0 }
            val total = vals.sum()
            if (total <= 0.0) return null
            return (vals.max() / total) * 100.0
        }
        val hNow = hhi(revenueByPartyNow) ?: return null
        val hBefore = hhi(revenueByPartyBefore) ?: return null
        val tNow = topPct(revenueByPartyNow) ?: return null
        val tBefore = topPct(revenueByPartyBefore) ?: return null
        val verdict = when {
            hNow - hBefore > 0.01 -> "CONCENTRATING"
            hNow - hBefore < -0.01 -> "DIVERSIFYING"
            else -> "STABLE"
        }
        return ShareDrift(hNow, hBefore, tNow, tBefore, verdict)
    }
}
