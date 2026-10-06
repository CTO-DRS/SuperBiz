package com.superbiz.app.domain.algo

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/**
 * — عشرون خوارزمية حتمية جديدة للموجة R13، في 15 كائناً مستقلياً
 * لا يتقاطع أيّها مع R7/R9/R10/R11/R12 ولا مع كائنات الحساب الأساسية
 *
 * RobustMath (1-2) : ميل ثيل-سين المتين + شواذ MAD
 * AllocMath (3-4) : توزيع البواقي الكبرى + تفكيك النقدية
 * CorrMath (5-6) : بيرسون + سبيرمان
 * CadenceMath (7-8) : فترات الجفاف البيعية + انتظام وتيرة الشراء
 * GiniMath (9) : معامل جيني لتفاوت الفواتير
 * PercentileMath(10-11): رتبة المئين + مئينات السلسلة
 * WorkingMath (12) : دورة التحول النقدي CCC
 * LoanMath (13) : المعدل الضمني للقسط بحل التنصيف
 * PartyMatchMath(14) : تطابق أرقام الجوالات
 * FxDriftMath (15) : انجراف سعر الصرف المستخدم
 * FloatMath (16) : سرعة تحصيل الشيكات
 * ExpenseMixMath(17) : الفئة المصرفية الأسرع صعوداً
 * LossMakerMath(18) : أسطر بيع تحت التكلفة
 * HalfLifeMath (19) : تفاعل العملاء بعمر نصف
 * DispersionMath(20) : تشتت أسعار المنتج الواحد
 *
 * عقد الصدق نفسه: بلا بيانات كافية أو بمدخلات غير منتهية ⇒ null/قائمة فارغة،
 * ولا يُقرأ وقت النظام داخل أي خوارزمية — كلها دوال حتمية خالصة قابلة للاختبار.
*/

private fun r13Finite(values: Collection<Double>): Boolean = values.all { it.isFinite() }

private fun r13Mean(values: List<Double>): Double = values.sum() / values.size

private fun r13Median(values: List<Double>): Double {
    val s = values.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
}

private fun r13Std(values: List<Double>, m: Double): Double =
    sqrt(values.sumOf { (it - m) * (it - m) } / values.size)

// ═══════════════ 1-2) RobustMath: ثيل-سين + شواذ MAD ═══════════════

object RobustMath {

    /** ميل ثيل-سين: وسيط ميول كل الأزواج — متين ضد الشواذ بعكس انحدار المربعات الصغرى.
     *  يُرجع null تحت 4 نقاط أو بمدخلات غير منتهية. relSlopePct نسبة الميل إلى متوسط القيم المطلق
     *  لكل خطوة؛ الحكم: FLAT تحت ±0.5٪ لكل خطوة، وإلا RISING/FALLING. */
    data class TheilSen(
        val slope: Double,
        val intercept: Double,
        val relSlopePct: Double,   // نسبة الميل لكل خطوة إلى متوسط |y| × 100
        val verdict: String,       // RISING / FALLING / FLAT
    )

    fun theilSen(series: List<Double>): TheilSen? {
        val ys = series.filter { it.isFinite() }
        if (ys.size < 4 || ys.size != series.size) return null
        val n = ys.size
        val slopes = ArrayList<Double>(n * (n - 1) / 2)
        for (i in 0 until n) for (j in i + 1 until n) {
            if (j != i) slopes += (ys[j] - ys[i]) / (j - i)
        }
        val slope = r13Median(slopes)
        val intercept = r13Median(ys.withIndex().map { (i, y) -> y - slope * i })
        val meanAbs = r13Mean(ys.map { abs(it) })
        val rel = if (meanAbs > 0.0) slope / meanAbs * 100.0 else 0.0
        return TheilSen(
            slope = slope,
            intercept = intercept,
            relSlopePct = rel,
            verdict = when {
                rel > 0.5 -> "RISING"
                rel < -0.5 -> "FALLING"
                else -> "FLAT"
            },
        )
    }

    /** شواذ المدى الوسيطي MAD: z المعدَّل = 0.6745·(x − الوسيط)/MAD، وشاذّة ما تجاوز |z| العتبة k.
     *  MAD=0 ⇒ لا معلومة تشتت ⇒ null (نفضّل الصمت على اختلاق شواذ). يُرجع null تحت 6 نقاط. */
    data class MadOutliers(
        val median: Double,
        val mad: Double,
        val outlierIndices: List<Int>,   // فهارس مبكّرة على الصفر
    )

    fun madOutliers(values: List<Double>, k: Double = 3.5): MadOutliers? {
        if (values.size < 6 || !r13Finite(values)) return null
        val med = r13Median(values)
        val dev = values.map { abs(it - med) }
        val mad = r13Median(dev)
        if (mad == 0.0) return null
        val idx = values.withIndex().filter { (_, v) ->
            abs(0.6745 * (v - med) / mad) > k
        }.map { it.index }
        return MadOutliers(median = med, mad = mad, outlierIndices = idx)
    }
}

// ═══════════════ 3-4) AllocMath: توزيع عادل + تفكيك نقدي ═══════════════

object AllocMath {

    /** توزيع مبلغ على أوزان بطريقة البواقي الكبرى: نصيب كل وزن أرضيّ إلى قرشين عشريين،
     *  ثم توزيع الفرق المتبقي على الأصحاب الأكبر كسراً — المجموع يطابق total تماماً.
     *  أوزان سالبة أو غير منتهية أو مجموعها ≤0 أو total سالب ⇒ null. */
    fun largestRemainder(total: Double, weights: List<Double>): List<Double>? {
        if (total < 0.0 || !total.isFinite() || weights.isEmpty() || !r13Finite(weights)) return null
        // الأوزان السالبة كانت تمرّ إذا كان مجموعها موجباً وتنتج نصيباً سالباً
        if (weights.any { it < 0.0 }) return null
        val wsum = weights.sum()
        if (wsum <= 0.0) return null
        val cents = (round(total * 100)).toLong()
        val raw = weights.map { it / wsum * cents }
        val floors = raw.map { it.toLong() }
        var left = cents - floors.sum()
        // ترتيب الفهارس حسب الباقي الكسري تنازلياً (تعادل: الأصغر فهرسة أولاً)
        val order = raw.withIndex().sortedWith(
            compareByDescending<IndexedValue<Double>> { (_, v) -> v - v.toLong() }.thenBy { (i, _) -> i }
        ).map { it.index }
        val out = floors.toMutableList()
        for (i in order) {
            if (left <= 0) break
            out[i] += 1
            left--
        }
        return out.map { it / 100.0 }
    }

    /** تفكيك نقدي جشع لوحدات العملة (منزل الافتتاحي): أكبر فئة أولاً.
     *  البقايا دون أصغر فئة تُرجع في leftover بصدق. amount سالب أو غير منتهٍ ⇒ null.
     *  افتراضياً فئات الريال: 500/100/50/10/5/1/0.5/0.25. */
    data class CashPlan(
        val pieces: List<Pair<Double, Int>>,   // (فئة، عددها) للفئات المستخدمة فقط
        val totalPieces: Int,
        val leftover: Double,                  // ما لم يستطع التفكيك تغطيته
    )

    fun cashBreakdown(
        amount: Double,
        denominations: List<Double> = listOf(500.0, 100.0, 50.0, 10.0, 5.0, 1.0, 0.5, 0.25),
    ): CashPlan? {
        if (amount < 0.0 || !amount.isFinite()) return null
        if (denominations.isEmpty() || !r13Finite(denominations) || denominations.any { it <= 0.0 }) return null
        var rest = round(amount * 100)
        val sorted = denominations.sortedDescending()
        val pieces = ArrayList<Pair<Double, Int>>()
        var totalPieces = 0
        for (d in sorted) {
            val dc = round(d * 100)
            if (dc <= 0) continue
            val count = (rest / dc).toInt()
            if (count > 0) {
                pieces += d to count
                totalPieces += count
                rest -= dc * count
            }
        }
        return CashPlan(
            pieces = pieces,
            totalPieces = totalPieces,
            leftover = rest / 100.0,
        )
    }
}

// ═══════════════ 5-6) CorrMath: بيرسون + سبيرمان ═══════════════

object CorrMath {

    /** معامل بيرسون الخطي بين سلسلتين متساويتين الطول: r ∈ [−1,1].
     *  أقل من 3 أزواج أو تشتت صفري في أيّهما أو مدخلات غير منتهية ⇒ null. */
    fun pearson(xs: List<Double>, ys: List<Double>): Double? {
        if (xs.size != ys.size || xs.size < 3 || !r13Finite(xs) || !r13Finite(ys)) return null
        val mx = r13Mean(xs)
        val my = r13Mean(ys)
        val sx = r13Std(xs, mx)
        val sy = r13Std(ys, my)
        if (sx == 0.0 || sy == 0.0) return null
        val cov = xs.withIndex().sumOf { (i, x) -> (x - mx) * (ys[i] - my) } / xs.size
        val r = cov / (sx * sy)
        return r.coerceIn(-1.0, 1.0)
    }

    /** سبيرمان الرتبي: رتّب القيم (تعادل بمتوسط الرتب) ثم بيرسون على الرتب —
     *  يلتقط أي علاقة رتيبة لا الخطية فقط. نفس شروط الصدق. */
    fun spearman(xs: List<Double>, ys: List<Double>): Double? {
        if (xs.size != ys.size || xs.size < 3 || !r13Finite(xs) || !r13Finite(ys)) return null
        return pearson(ranks(xs), ranks(ys))
    }

    private fun ranks(v: List<Double>): List<Double> {
        val order = v.withIndex().sortedBy { it.value }
        val out = DoubleArray(v.size)
        var i = 0
        while (i < order.size) {
            var j = i
            while (j + 1 < order.size && order[j + 1].value == order[i].value) j++
            val avgRank = (i + j) / 2.0 + 1.0
            for (t in i..j) out[order[t].index] = avgRank
            i = j + 1
        }
        return out.toList()
    }
}

// ═══════════════ 7-8) CadenceMath: الجفاف + الانتظام ═══════════════

object CadenceMath {

    /** فترات الجفاف البيعي: يوم "جافّ" ما كان إيراده ≤ العتبة (افتراضياً 0.005 لامتصاص أقراص التقريب).
     *  يُرجع أطول سلسلة جفاف متتالية وعدد الأيام الجافة كلياً والجفاف الجاري في نهاية السلسلة.
     *  قائمة فارغة أو غير منتهية ⇒ null. */
    data class DrySpells(
        val longestDry: Int,
        val dryDays: Int,
        val currentDry: Int,     // أيام جفاف متتالية تنتهي عند آخر يوم في السلسلة
        val dryPct: Double,      // نسبة الأيام الجافة × 100
    )

    fun drySpells(dailyRevenue: List<Double>, threshold: Double = 0.005): DrySpells? {
        if (dailyRevenue.isEmpty() || !r13Finite(dailyRevenue) || threshold < 0.0) return null
        var longest = 0
        var run = 0
        var dry = 0
        var current = 0
        for (v in dailyRevenue) {
            if (v <= threshold) {
                run++
                dry++
                if (run > longest) longest = run
            } else run = 0
        }
        current = run
        return DrySpells(
            longestDry = longest,
            dryDays = dry,
            currentDry = current,
            dryPct = dry * 100.0 / dailyRevenue.size,
        )
    }

    /** انتظام وتيرة الشراء: معامل الاختلاف CV = تشتت/متوسط لفواصل الشراء بالأيام.
     *  CV ≤ 0.33 منتظم، ≤ 0.8 عادي، وإلا متقلب. أقل من 3 فواصل أو فاصل ≤0 ⇒ null. */
    data class Regularity(
        val cv: Double,
        val meanDays: Double,
        val verdict: String,   // REGULAR / NORMAL / IRREGULAR
    )

    fun regularityCV(intervalsDays: List<Double>): Regularity? {
        if (intervalsDays.size < 3 || !r13Finite(intervalsDays)) return null
        if (intervalsDays.any { it <= 0.0 }) return null
        val m = r13Mean(intervalsDays)
        if (m <= 0.0) return null
        val cv = r13Std(intervalsDays, m) / m
        return Regularity(
            cv = cv,
            meanDays = m,
            verdict = when {
                cv <= 0.33 -> "REGULAR"
                cv <= 0.8 -> "NORMAL"
                else -> "IRREGULAR"
            },
        )
    }
}

// ═══════════════ 9) GiniMath: تفاوت الفواتير ═══════════════

object GiniMath {

    /** معامل جيني [0..1]: 0 يساوي تام (كل فاتورة بقيمة واحدة) و1 أقصى تركّز (فاتورة واحدة تحمل كل شيء).
     *  قيم سالبة ⇒ null؛ كل القيم صفر ⇒ 0.0؛ أقل من قيمتين ⇒ null. */
    fun gini(values: List<Double>): Double? {
        if (values.size < 2 || !r13Finite(values)) return null
        if (values.any { it < 0.0 }) return null
        val sum = values.sum()
        if (sum <= 0.0) return 0.0
        val n = values.size
        val sorted = values.sorted()
        var cum = 0.0
        for ((i, v) in sorted.withIndex()) cum += (i + 1) * v
        val g = 2.0 * cum / (n * sum) - (n + 1.0) / n
        return g.coerceIn(0.0, 1.0)
    }
}

// ═══════════════ 10-11) PercentileMath: الرتب والمئينات ═══════════════

object PercentileMath {

    /** رتبة مئينية لقيمة x داخل عيّنة (طريقة المنتصف): (أقلّ من x + نصف المساوي)/n × 100.
     *  عيّنة فارغة أو x غير منتهٍ ⇒ null. */
    fun percentileRank(sample: List<Double>, x: Double): Double? {
        if (sample.isEmpty() || !x.isFinite() || !r13Finite(sample)) return null
        val below = sample.count { it < x }
        val equal = sample.count { it == x }
        return (below + equal / 2.0) / sample.size * 100.0
    }

    /** مئينات السلسلة باستيفاء خطي (الطريقة R-7 الشائعة): p ∈ [0,100].
     *  أقل من قيمتين أو p خارج المدى أو مدخلات غير منتهية ⇒ null. */
    fun percentiles(values: List<Double>, ps: List<Double> = listOf(25.0, 50.0, 75.0, 90.0)): List<Pair<Double, Double>>? {
        if (values.size < 2 || !r13Finite(values)) return null
        if (ps.isEmpty() || ps.any { it < 0.0 || it > 100.0 || !it.isFinite() }) return null
        val s = values.sorted()
        val n = s.size
        return ps.map { p ->
            val h = (n - 1) * p / 100.0
            val lo = h.toInt()
            val hi = minOf(lo + 1, n - 1)
            val frac = h - lo
            p to (s[lo] + frac * (s[hi] - s[lo]))
        }
    }
}

// ═══════════════ 12) WorkingMath: دورة التحول النقدي ═══════════════

object WorkingMath {

    /** دورة التحول النقدي CCC = DSO + DIO − DPO: كم يوماً يبقى المال محبوساً في التشغيل.
     *  أي مقام صفر أو سالب ⇒ مكوّنه null (لا بيانات كافية لذلك الضلع) وCCC تُرجع null عندها.
     *  الحكم: ≤15 يوماً سريعة، ≤45 معتادة، وإلا ثقيلة. */
    data class CCC(
        val dso: Double?,
        val dio: Double?,
        val dpo: Double?,
        val ccc: Double?,
        val verdict: String,   // FAST / OK / HEAVY
    )

    fun cashConversionCycle(
        receivableAvg: Double,
        inventoryAvg: Double,
        payableAvg: Double,
        dailyCreditSales: Double,
        dailyCogs: Double,
        dailyPurchases: Double,
    ): CCC {
        fun div(a: Double, d: Double): Double? =
            if (d > 0.0 && a.isFinite() && d.isFinite()) a / d else null
        val dso = div(receivableAvg, dailyCreditSales)
        val dio = div(inventoryAvg, dailyCogs)
        val dpo = div(payableAvg, dailyPurchases)
        val ccc = if (dso != null && dio != null && dpo != null) dso + dio - dpo else null
        return CCC(
            dso = dso,
            dio = dio,
            dpo = dpo,
            ccc = ccc,
            verdict = if (ccc == null) "UNKNOWN" else when {
                ccc <= 15.0 -> "FAST"
                ccc <= 45.0 -> "OK"
                else -> "HEAVY"
            },
        )
    }
}

// ═══════════════ 13) LoanMath: المعدل الضمني ═══════════════

object LoanMath {

    /** المعدل الضمني لخطة أقساط: نحلّ بالتنصيف معادلة القيمة الحالية للقسط الثابت
     *  PV = pmt·(1−(1+i)^−m)/i حتى تطابق أصل الدين. إجمالي الأقساط < الأصل ⇒ null
     *  (لا يوجد معدل موجب يفسّره). تساوي تماماً ⇒ معدل 0.
     *  الحكم على المعدل السنوي الاسمي: ≤5٪ متساهل، ≤15٪ معتاد، ≤40٪ ثقيل، وإلا فاحش. */
    data class ImpliedRate(
        val monthlyRate: Double,
        val annualPct: Double,   // معدل سنوي اسمي = شهري × 12 × 100
        val verdict: String,     // LENIENT / NORMAL / HEAVY / EXTREME
    )

    fun impliedRate(principal: Double, monthlyPayment: Double, months: Int): ImpliedRate? {
        if (months < 2 || !principal.isFinite() || !monthlyPayment.isFinite()) return null
        if (principal <= 0.0 || monthlyPayment <= 0.0) return null
        val total = monthlyPayment * months
        if (total < principal - 1e-9) return null
        if (total <= principal + 1e-9) {
            return ImpliedRate(0.0, 0.0, "LENIENT")
        }
        fun pv(i: Double): Double =
            if (i <= 0.0) monthlyPayment * months
            else monthlyPayment * (1.0 - (1.0 + i).pow(-months)) / i
        var lo = 0.0          // pv(lo) ≥ principal
        var hi = 1.0          // pv(hi) < principal دائماً عملياً عند هذا السقف
        // لو حتى 100٪ شهرياً لا يكفي (قسط ضخم جداً) نُوسّع السقف ×4 حتى يتحقق
        while (pv(hi) > principal && hi < 16.0) hi *= 4.0
        if (pv(hi) > principal) return null
        repeat(200) {
            val mid = (lo + hi) / 2.0
            if (pv(mid) > principal) lo = mid else hi = mid
        }
        val i = (lo + hi) / 2.0
        val annual = i * 12.0 * 100.0
        return ImpliedRate(
            monthlyRate = i,
            annualPct = annual,
            verdict = when {
                annual <= 5.0 -> "LENIENT"
                annual <= 15.0 -> "NORMAL"
                annual <= 40.0 -> "HEAVY"
                else -> "EXTREME"
            },
        )
    }
}

// ═══════════════ 14) PartyMatchMath: تطابق الجوالات ═══════════════

object PartyMatchMath {

    /** تطابق رقمي جوالين بعد التطبيع: يُبقي الأرقام فقط، يُسقط 00 و966 كبادئة،
     *  ويقارن نواة 9 أرقام (جوال سعودي يبدأ بـ5). تطابق تام بالكامل ⇒ EXACT،
     *  تطابق النواة فقط ⇒ LIKELY، وإلا NO. أيّهما فارغ بعد التطبيع ⇒ NO. */
    data class Match(val verdict: String, val core: String)   // EXACT / LIKELY / NO

    fun phoneMatch(a: String, b: String): Match {
        fun core(s: String): String {
            var d = s.filter { it.isDigit() }
            while (d.startsWith("00") || d.startsWith("966")) {
                d = if (d.startsWith("00")) d.drop(2) else d.drop(3)
            }
            return if (d.length > 9) d.takeLast(9) else d
        }
        val ca = core(a)
        val cb = core(b)
        if (ca.isEmpty() || cb.isEmpty()) return Match("NO", "")
        return if (ca != cb) Match("NO", "")
        else if (a.filter { it.isDigit() } == b.filter { it.isDigit() }) Match("EXACT", ca)
        else Match("LIKELY", ca)
    }
}

// ═══════════════ 15) FxDriftMath: انجراف سعر الصرف ═══════════════

object FxDriftMath {

    /** انجراف سعر الصرف المستخدم فعلياً في الفواتير مقارنة بالسعر الحالي المضبوط:
     *  وسيط الأسعار التاريخية مقابل currentRate، والانجراف نسبةً منه.
     *  أقل من 3 أسعار أو قيمة ≤0 أو غير منتهية ⇒ null.
     *  الحكم: <2٪ مستقر، <8٪ للمراقبة، وإلا منحرف. */
    data class Drift(
        val medianRate: Double,
        val currentRate: Double,
        val driftPct: Double,
        val verdict: String,   // STABLE / WATCH / DRIFT
    )

    fun rateDrift(rates: List<Double>, currentRate: Double): Drift? {
        if (rates.size < 3 || !r13Finite(rates)) return null
        if (rates.any { it <= 0.0 }) return null
        if (!currentRate.isFinite() || currentRate <= 0.0) return null
        val med = r13Median(rates)
        val drift = (currentRate - med) / med * 100.0
        return Drift(
            medianRate = med,
            currentRate = currentRate,
            driftPct = drift,
            verdict = when {
                abs(drift) < 2.0 -> "STABLE"
                abs(drift) < 8.0 -> "WATCH"
                else -> "DRIFT"
            },
        )
    }
}

// ═══════════════ 16) FloatMath: سرعة تحصيل الشيكات ═══════════════

object FloatMath {

    /** إحصاء أيام الطفو (من إصدار الشيك إلى قيد التحصيل الفعلي):
     *  الوسيط والمئين 90 والأقصى، وحكم على الوسيط: ≤7 أيام سريع، ≤21 عادي، وإلا بطيء.
     *  أقل من 3 قيم أو قيمة سالبة أو غير منتهية ⇒ null. */
    data class FloatStats(
        val medianDays: Double,
        val p90Days: Double,
        val maxDays: Double,
        val verdict: String,   // FAST / NORMAL / SLOW
    )

    fun floatDays(floatsDays: List<Double>): FloatStats? {
        if (floatsDays.size < 3 || !r13Finite(floatsDays)) return null
        if (floatsDays.any { it < 0.0 }) return null
        val med = r13Median(floatsDays)
        val p90 = PercentileMath.percentiles(floatsDays, listOf(90.0))?.firstOrNull()?.second ?: med
        return FloatStats(
            medianDays = med,
            p90Days = p90,
            maxDays = floatsDays.max(),
            verdict = when {
                med <= 7.0 -> "FAST"
                med <= 21.0 -> "NORMAL"
                else -> "SLOW"
            },
        )
    }
}

// ═══════════════ 17) ExpenseMixMath: الفئة الأسرع صعوداً ═══════════════

object ExpenseMixMath {

    /** الفئة المصرفية التي تضخّم حصتها من الإنفاق بين نافذتين:
     *  حصة كل فئة قبل/بعد، وأكبر زيادة بالنقاط المئوية هي الرائد.
     *  مجموعتا النافذتين يجب أن تتجاوزان الصفر وإلا null.
     *  الحكم: ≥5 نقاط تحوّل، ≥2 تسلّق، وإلا مستقر. */
    data class Riser(
        val category: String,
        val shareBeforePct: Double,
        val shareAfterPct: Double,
        val deltaPctPoints: Double,
        val verdict: String,   // SHIFT / CLIMBING / STABLE
    )

    fun topRiser(before: Map<String, Double>, after: Map<String, Double>): Riser? {
        if (before.isEmpty() || after.isEmpty()) return null
        val beforeVals = before.values
        val afterVals = after.values
        if (!r13Finite(beforeVals) || !r13Finite(afterVals)) return null
        val tb = beforeVals.sum()
        val ta = afterVals.sum()
        if (tb <= 0.0 || ta <= 0.0) return null
        var best: Riser? = null
        for ((cat, afterAmt) in after) {
            if (afterAmt <= 0.0) continue
            val beforeAmt = before[cat] ?: 0.0
            if (beforeAmt < 0.0) continue
            val sb = beforeAmt / tb * 100.0
            val sa = afterAmt / ta * 100.0
            val delta = sa - sb
            if (best == null || delta > best!!.deltaPctPoints) {
                best = Riser(
                    category = cat,
                    shareBeforePct = sb,
                    shareAfterPct = sa,
                    deltaPctPoints = delta,
                    verdict = when {
                        delta >= 5.0 -> "SHIFT"
                        delta >= 2.0 -> "CLIMBING"
                        else -> "STABLE"
                    },
                )
            }
        }
        return best
    }
}

// ═══════════════ 18) LossMakerMath: البيع تحت التكلفة ═══════════════

object LossMakerMath {

    /** أسطر البيع تحت تكلفة الوحدة: يجمعها لكل منتج ويحسب الخسارة (تكلفة − سعر) × الكمية.
     *  يُرجع المنتجات المتأثرة فقط مرتبةً بالخسارة تنازلياً؛ بلا أسطر كهذه ⇒ قائمة فارغة.
     *  أسطر بكمية ≤0 أو سعر/تكلفة غير منتهية تُهمَل بصدق. */
    data class Line(val productId: Long?, val qty: Double, val unitPrice: Double, val unitCost: Double)
    data class BelowRow(
        val productId: Long?,
        val linesBelow: Int,
        val qtyBelow: Double,
        val lost: Double,
    )

    fun belowCost(lines: List<Line>): List<BelowRow> {
        val agg = HashMap<Long?, DoubleArray>()   // [خطوط، كمية، خسارة]
        for (l in lines) {
            if (!l.qty.isFinite() || !l.unitPrice.isFinite() || !l.unitCost.isFinite()) continue
            if (l.qty <= 0.0 || l.unitPrice >= l.unitCost) continue
            val a = agg.getOrPut(l.productId) { DoubleArray(3) }
            a[0] += 1.0
            a[1] += l.qty
            a[2] += (l.unitCost - l.unitPrice) * l.qty
        }
        return agg.map { (pid, a) ->
            BelowRow(productId = pid, linesBelow = a[0].toInt(), qtyBelow = a[1], lost = a[2])
        }.sortedByDescending { it.lost }
    }
}

// ═══════════════ 19) HalfLifeMath: تفاعل العملاء ═══════════════

object HalfLifeMath {

    /** تفاعل العملاء بعمر النصف: كل حدّ بيع يزن amount × 0.5^(عمره بالأيام / عمر النصف)،
     *  وتجمع أوزان كل عميل — الملفات القديمة تتبخر بسلاسة بدل القطيعة الحادة في RFM.
     *  صفوف بكمية ≤0 أو عمر سالب تُهمَل؛ عمر نصف ≤0 ⇒ null. يُرجع النتائج مرتبةً تنازلياً. */
    data class Engaged(val partyId: Long, val score: Double)

    fun engagement(
        events: List<Triple<Long, Double, Long>>,   // (partyId, amount, ageDays)
        halfLifeDays: Double = 90.0,
    ): List<Engaged>? {
        if (halfLifeDays <= 0.0 || !halfLifeDays.isFinite()) return null
        if (events.isEmpty()) return emptyList()
        val decay = -ln(0.5) / halfLifeDays
        val scores = HashMap<Long, Double>()
        for ((pid, amount, ageDays) in events) {
            if (!amount.isFinite() || amount <= 0.0) continue
            if (ageDays < 0) continue
            scores[pid] = (scores[pid] ?: 0.0) + amount * kotlin.math.exp(-decay * ageDays)
        }
        return scores.map { (pid, s) -> Engaged(pid, s) }.sortedByDescending { it.score }
    }
}

// ═══════════════ 20) DispersionMath: تشتت سعر المنتج الواحد ═══════════════

object DispersionMath {

    /** كم سعراً مختلفاً (لأقرب قرش) يُباع به المنتج نفسه؟
     *  spreadPct = (الأعلى − الأدنى)/الأدنى × 100 على الأسعار الموجبة فقط.
     *  أقل من سعرين ⇒ null. الحكم: سعر واحد موحّد، ≤5٪ متقيد، ≤15٪ متباعد، وإلا فوضى. */
    data class Spread(
        val distinctPrices: Int,
        val min: Double,
        val max: Double,
        val spreadPct: Double,
        val verdict: String,   // UNIFORM / TIGHT / LOOSE / WILD
    )

    fun priceSpread(prices: List<Double>): Spread? {
        val clean = prices.filter { it.isFinite() && it > 0.0 }.map { round(it * 100) / 100.0 }
        if (clean.size < 2) return null
        val distinct = clean.distinct()
        val mn = distinct.min()
        val mx = distinct.max()
        val spread = if (mn > 0.0) (mx - mn) / mn * 100.0 else 0.0
        return Spread(
            distinctPrices = distinct.size,
            min = mn,
            max = mx,
            spreadPct = spread,
            verdict = when {
                distinct.size == 1 -> "UNIFORM"
                spread <= 5.0 -> "TIGHT"
                spread <= 15.0 -> "LOOSE"
                else -> "WILD"
            },
        )
    }
}
