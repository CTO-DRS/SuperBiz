package com.superbiz.app.domain.algo

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/**
 * — عشرون خوارزمية حتمية جديدة للموجة R14، في 20 كائناً مستقلياً
 * لا يتقاطع أيّها مع R7/R9/R10/R11/R12/R13 ولا مع كائنات الحساب الأساسية
 *
 * WinsorMath (1) : المتوسط الوينسوري المتين للإيراد اليومي
 * HurstMath (2) : أس هيرست لمثابرة الاتجاه (R/S)
 * KsMath (3) : إحصاءة كولموغوروف-سميرنوف لعينتين
 * CoverageMath (4) : غطاء الالتزامات المستحقة (نقد + متوقع مقابل المستحق)
 * CampaignMath (5) : الارتفاع المطلوب في الكميات ليكسر الخصم تعادله
 * MarginMixMath (6) : هامش البيع النقدي مقابل الآجل
 * DrawdownMath (7) : أقصى تراجع في منحنى النقد التراكمي
 * FairShareMath (8) : توزيع دفعة على فواتير مفتوحة (أقدم استحقاق أولاً)
 * RunsMath (9) : اختبار الجولات لاستقرار السلسلة اليومية
 * EntropyMath (10) : عدد الفئات الفعّال (إنتروبي شانون)
 * AbcXyzMath (11) : مصفوفة ABC×XYZ التساعية الخلايا
 * ShrinkageMath (12) : تسرّب المخزون من حركات التسوية السالبة
 * CatalogHygieneMath(13) : نظافة بيانات الكتالوج (باركود/فئة/تكلفة/سعر)
 * BandMath (14) : توزيع المنتجات على أحزمة الهامش
 * BordaMath (15) : تجميع رتب Borda لعدة مقاييس
 * MoverMath (16) : صاعدو وهابطو الرتب بين فترتين
 * BlendMath (17) : تنبؤ مركّب موزون بخطأ المعاودة
 * StalenessMath (18) : عمر الذمم المرجّح (وسطى ووسين مرجّحان)
 * GapMath (19) : أفق تأجيل الشيكات (الإصدار→الاستحقاق)
 * FixedReserveMath (20) : الاحتياطي اليومي للتكاليف الثابتة
 *
 * عقد الصدق نفسه: بلا بيانات كافية أو بمدخلات غير منتهية ⇒ null/قائمة فارغة،
 * ولا يُقرأ وقت النظام داخل أي خوارزمية — كلها دوال حتمية خالصة قابلة للاختبار
 * (ما يحتاج "اليوم" يُمرر صريحاً من المستدعي).
*/

private fun r14Finite(values: Collection<Double>): Boolean = values.all { it.isFinite() }

private fun r14Mean(values: List<Double>): Double = values.sum() / values.size

private fun r14Median(values: List<Double>): Double {
    val s = values.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
}

private fun r14Std(values: List<Double>, m: Double): Double =
    sqrt(values.sumOf { (it - m) * (it - m) } / values.size)

/** مئين خطي (نمط R-7): فهرس = q·(n−1) مع استقراء خطي بين الجارين على القائمة المرتبة. */
private fun r14Pct(sorted: List<Double>, qPct: Double): Double {
    val n = sorted.size
    if (n == 1) return sorted[0]
    val idx = (qPct / 100.0) * (n - 1)
    val lo = idx.toInt().coerceIn(0, n - 1)
    val hi = (lo + 1).coerceAtMost(n - 1)
    val frac = idx - lo
    return sorted[lo] + frac * (sorted[hi] - sorted[lo])
}

// ═══════════════ 1) WinsorMath: المتوسط الوينسوري ═══════════════

object WinsorMath {

    /**
     * المتوسط الوينسوري: قصّ القيم الطرفية إلى مئيني p و(100−p) ثم حساب المتوسط —
     * متوسط يومي متين لا تنحره أسبوع شذوذ واحد بعكس المتوسط الخام.
     * عقد: قيمة واحدة على الأقل على كل جانب تُقص (قيم ≥6، كلها منتهية، 0<p<50)
     * وإلا null. effectPct أثر القصّ على المتوسط نسبةً إلى الخام.
     */
    data class Winsor(
        val rawMean: Double,
        val winsorizedMean: Double,
        val lowLimit: Double,
        val highLimit: Double,
        val changedCount: Int,
        val effectPct: Double,
    )

    fun winsorizedMean(values: List<Double>, p: Double = 5.0): Winsor? {
        if (values.size < 6 || !r14Finite(values)) return null
        if (p <= 0.0 || p >= 50.0) return null
        val sorted = values.sorted()
        val low = r14Pct(sorted, p)
        val high = r14Pct(sorted, 100.0 - p)
        val clamped = values.map { it.coerceIn(low, high) }
        val raw = r14Mean(values)
        val win = r14Mean(clamped)
        val changed = values.withIndex().count { (i, v) -> v != clamped[i] }
        val effect = if (raw != 0.0) abs(win - raw) / abs(raw) * 100.0 else 0.0
        return Winsor(
            rawMean = raw,
            winsorizedMean = win,
            lowLimit = low,
            highLimit = high,
            changedCount = changed,
            effectPct = effect,
        )
    }
}

// ═══════════════ 2) HurstMath: أس هيرست R/S ═══════════════

object HurstMath {

    /**
     * أس هيرست بتقدير المدى المُعاد قياسه R/S: نقسّم السلسلة إلى قطع حجم m
     * (أحجام 8، 16، 32... بحد n/2)، ونحسب متوسط (المدى/الانحراف) للانحرافات التراكمية
     * عن متوسط القطعة، ثم ميل log(R/S) على log(m) بالانحدار الخطي.
     * الحكم: H<0.45 ارتدادي للمتوسط، ≤0.55 عشوائي، وإلا مثابر (اتجاه حقيقي).
     * عقد: ≥16 نقطة منتهية وبتشتت غير صفري وإلا null.
     */
    data class Hurst(
        val h: Double,
        val verdict: String,   // MEAN_REVERTING / RANDOM / TRENDING
    )

    fun hurst(series: List<Double>): Hurst? {
        val ys = series.filter { it.isFinite() }
        if (ys.size < 16 || ys.size != series.size) return null
        val n = ys.size
        val m0 = r14Mean(ys)
        if (r14Std(ys, m0) == 0.0) return null

        data class RS(val m: Int, val rs: Double)
        val points = ArrayList<RS>()
        var m = 8
        while (m <= n / 2) {
            val chunks = n / m
            if (chunks >= 2) {
                val rsv = ArrayList<Double>(chunks)
                for (c in 0 until chunks) {
                    val seg = ys.subList(c * m, (c + 1) * m)
                    val mu = r14Mean(seg)
                    var cum = 0.0
                    var lo = 0.0
                    var hi = 0.0
                    for (v in seg) {
                        cum += v - mu
                        lo = min(lo, cum)
                        hi = max(hi, cum)
                    }
                    val s = r14Std(seg, mu)
                    if (s > 0.0) rsv += (hi - lo) / s
                }
                if (rsv.isNotEmpty()) points += RS(m, r14Mean(rsv))
            }
            m *= 2
        }
        if (points.size < 2) return null
        // انحدار خطي لـ log(R/S) = log(c) + H·log(m)
        val xs = points.map { ln(it.m.toDouble()) }
        val ys2 = points.map { ln(it.rs) }
        val mx = r14Mean(xs)
        val my = r14Mean(ys2)
        var num = 0.0
        var den = 0.0
        for (i in xs.indices) {
            num += (xs[i] - mx) * (ys2[i] - my)
            den += (xs[i] - mx) * (xs[i] - mx)
        }
        if (den == 0.0) return null
        val h = num / den
        val verdict = when {
            h < 0.45 -> "MEAN_REVERTING"
            h <= 0.55 -> "RANDOM"
            else -> "TRENDING"
        }
        return Hurst(h = h, verdict = verdict)
    }
}

// ═══════════════ 3) KsMath: كولموغوروف-سميرنوف لعينتين ═══════════════

object KsMath {

    /**
     * إحصاءة D لكولموغوروف-سميرنوف الثنائية العينة: أكبر فرق بين الدالتين
     * التراكميتين التجريبيتين — هل توزيع قيم الفواتير في الفترة الأخيرة اختلف
     * جوهرياً عن سابقتها؟ القيمة الحرجة عند α=0.05: 1.358·√((n1+n2)/(n1·n2)).
     * الحكم: D > الحرج SHIFTED وإلا SIMILAR. عقد: العينتان غير فارغتين ومنتهيتين وإلا null.
     */
    data class KS(
        val d: Double,
        val critical: Double,
        val verdict: String,   // SHIFTED / SIMILAR
    )

    fun ksStatistic(a: List<Double>, b: List<Double>): KS? {
        val a1 = a.filter { it.isFinite() }
        val b1 = b.filter { it.isFinite() }
        if (a1.isEmpty() || b1.isEmpty() || a1.size != a.size || b1.size != b.size) return null
        val all = (a1 + b1).distinct().sorted()
        val asort = a1.sorted()
        val bsort = b1.sorted()
        var d = 0.0
        var ia = 0
        var ib = 0
        for (x in all) {
            while (ia < asort.size && asort[ia] <= x) ia++
            while (ib < bsort.size && bsort[ib] <= x) ib++
            val fa = ia.toDouble() / a1.size
            val fb = ib.toDouble() / b1.size
            d = max(d, abs(fa - fb))
        }
        val critical = 1.358 * sqrt((a1.size + b1.size).toDouble() / (a1.size * b1.size))
        return KS(
            d = d,
            critical = critical,
            verdict = if (d > critical) "SHIFTED" else "SIMILAR",
        )
    }
}

// ═══════════════ 4) CoverageMath: غطاء الالتزامات ═══════════════

object CoverageMath {

    /**
     * غطاء الالتزامات المستحقة: (النقد المتاح + المتوقع تحصيله) ÷ مجموع الالتزامات —
     * هل ستغطي ثلاثين يوماً القادمة دون عجز؟
     * عقد: التزامات غير فارغة وموجبة والمدخلات منتهية وإلا null.
     * الحكم: ratio≥1.2 SAFE، ≥1.0 TIGHT، وإلا DEFICIT. shortfall ما ينقص من الموارد.
     */
    data class Cover(
        val totalObligations: Double,
        val available: Double,
        val expectedInflow: Double,
        val ratio: Double,
        val shortfall: Double,
        val verdict: String,   // SAFE / TIGHT / DEFICIT
    )

    fun obligationCoverage(available: Double, expectedInflow: Double, obligations: List<Double>): Cover? {
        if (obligations.isEmpty() || !r14Finite(obligations)) return null
        if (obligations.any { it < 0.0 }) return null
        if (!available.isFinite() || !expectedInflow.isFinite() || available < 0.0 || expectedInflow < 0.0) return null
        val total = obligations.sum()
        if (total <= 0.0) return null
        val resources = available + expectedInflow
        val ratio = resources / total
        val verdict = when {
            ratio >= 1.2 -> "SAFE"
            ratio >= 1.0 -> "TIGHT"
            else -> "DEFICIT"
        }
        return Cover(
            totalObligations = total,
            available = available,
            expectedInflow = expectedInflow,
            ratio = ratio,
            shortfall = (total - resources).coerceAtLeast(0.0),
            verdict = verdict,
        )
    }
}

// ═══════════════ 5) CampaignMath: تعادل الخصم ═══════════════

object CampaignMath {

    /**
     * الارتفاع المطلوب في الكميات ليكسر الخصم تعادله: هامش m٪ وخصم d٪ ⇒
     * الزيادة المطلوبة في المبيعات = d/(m−d)×100٪ — أقل من ذلك فالحملة تخسر.
     * عقد: 0<m≤100 و0≤d<100 وإلا null. d≥m ⇒ NEVER (الخصم يأكل الهامش كله).
     * الحكم: ≤15٪ FEASIBLE، ≤40٪ HARD، وإلا STEEP.
     */
    data class Req(
        val requiredUpliftPct: Double?,   // null عندما d≥m
        val verdict: String,              // FEASIBLE / HARD / STEEP / NEVER
    )

    fun requiredUplift(marginPct: Double, discountPct: Double): Req? {
        if (!marginPct.isFinite() || !discountPct.isFinite()) return null
        if (marginPct <= 0.0 || marginPct > 100.0) return null
        if (discountPct < 0.0 || discountPct >= 100.0) return null
        if (discountPct >= marginPct) return Req(requiredUpliftPct = null, verdict = "NEVER")
        val required = discountPct / (marginPct - discountPct) * 100.0
        val verdict = when {
            required <= 15.0 -> "FEASIBLE"
            required <= 40.0 -> "HARD"
            else -> "STEEP"
        }
        return Req(requiredUpliftPct = required, verdict = verdict)
    }
}

// ═══════════════ 6) MarginMixMath: هامش النقد مقابل الآجل ═══════════════

object MarginMixMath {

    /**
     * هامش البيع بحسب طريقة التسوية: متوسط هامش الفواتير المحصّلة نقداً عند البيع
     * مقابل المؤجلة — أي قناة أكثر ربحية فعلاً؟ (الخصم غالباً يركّز في قناة واحدة).
     * عقد: 3 قيم على الأقل في كل جانب وكلها منتهية وإلا null.
     * الحكم: فرق أقل من 0.5 نقطة EQUAL، وإلا لصاحب الهامش الأعلى.
     */
    data class Mix(
        val cashAvgPct: Double,
        val creditAvgPct: Double,
        val deltaPct: Double,
        val verdict: String,   // CASH_BETTER / CREDIT_BETTER / EQUAL
    )

    fun marginBySettlement(cashMarginsPct: List<Double>, creditMarginsPct: List<Double>): Mix? {
        if (cashMarginsPct.size < 3 || creditMarginsPct.size < 3) return null
        if (!r14Finite(cashMarginsPct) || !r14Finite(creditMarginsPct)) return null
        val cash = r14Mean(cashMarginsPct)
        val credit = r14Mean(creditMarginsPct)
        val delta = cash - credit
        val verdict = when {
            abs(delta) < 0.5 -> "EQUAL"
            delta > 0.0 -> "CASH_BETTER"
            else -> "CREDIT_BETTER"
        }
        return Mix(cashAvgPct = cash, creditAvgPct = credit, deltaPct = delta, verdict = verdict)
    }
}

// ═══════════════ 7) DrawdownMath: أقصى تراجع في منحنى النقد ═══════════════

object DrawdownMath {

    /**
     * أقصى تراجع (Drawdown) في منحنى النقد التراكمي: أكبر هبوط من قمة تاريخية
     * إلى قاع لاحق كنسبة من القمة — عمق ضغوط السيولة خلال الفترة.
     * عقد: ≥4 قيم منتهية وإلا null؛ قمة تاريخية ≤0 (لا منحنى موجب أصلاً) ⇒ null.
     * الحكم: التراجع الحالي أقل من 0.5٪ RECOVERED وإلا UNDERWATER.
     */
    data class DD(
        val maxDrawdownPct: Double,
        val currentDrawdownPct: Double,
        val peakIndex: Int,     // فهرس قمة أعمق تراجع (على السلسلة التراكمية)
        val troughIndex: Int,   // فهرس قاع أعمق تراجع
        val state: String,      // RECOVERED / UNDERWATER
    )

    fun maxDrawdown(series: List<Double>): DD? {
        if (series.size < 4 || !r14Finite(series)) return null
        var cum = 0.0
        var peak = 0.0
        var peakIdx = -1
        var bestDd = 0.0
        var bestPeakIdx = -1
        var bestTroughIdx = -1
        var anyPositive = false
        for (i in series.indices) {
            cum += series[i]
            if (cum > peak) {
                peak = cum
                peakIdx = i
            }
            if (peak > 0.0) {
                anyPositive = true
                val dd = (peak - cum) / peak * 100.0
                if (dd > bestDd) {
                    bestDd = dd
                    bestPeakIdx = peakIdx
                    bestTroughIdx = i
                }
            }
        }
        if (!anyPositive) return null
        val currentDd = if (peak > 0.0) (peak - cum) / peak * 100.0 else 0.0
        return DD(
            maxDrawdownPct = bestDd,
            currentDrawdownPct = currentDd,
            peakIndex = bestPeakIdx,
            troughIndex = bestTroughIdx,
            state = if (currentDd < 0.5) "RECOVERED" else "UNDERWATER",
        )
    }
}

// ═══════════════ 8) FairShareMath: توزيع دفعة على فواتير مفتوحة ═══════════════

object FairShareMath {

    /**
     * توزيع دفعة على الفواتير المفتوحة بطريقة "أقدم استحقاق أولاً": تحصيل دقيق
     * بالقروش (حساب بالهللات Long) لا يترك كسور تقريب، مع إغلاق الأقدم أولاً
     * وبقايا الدفعة غير المخصصة تُعاد للمستدعي صراحة.
     * عقد: payment منتهٍ و≥0، وopen لكل فاتورة ≥0 وإلا null.
     * closedCount عدد الفواتير التي أغلقتها الدفعة كلياً (فتحها كان موجباً).
     */
    data class OpenInv(val id: Long, val open: Double, val dueDate: Long)

    data class Alloc(val id: Long, val amount: Double)

    data class AllocResult(
        val allocations: List<Alloc>,
        val unallocated: Double,
        val closedCount: Int,
    )

    fun applyPayment(open: List<OpenInv>, payment: Double): AllocResult? {
        if (!payment.isFinite() || payment < 0.0) return null
        if (open.any { !it.open.isFinite() || it.open < 0.0 }) return null
        var remaining = (round(payment * 100)).toLong()
        val sorted = open.sortedWith(compareBy<OpenInv> { it.dueDate }.thenBy { it.id })
        val out = ArrayList<Alloc>(sorted.size)
        var closed = 0
        for (inv in sorted) {
            val openCents = (round(inv.open * 100)).toLong()
            if (remaining <= 0) {
                if (open.isNotEmpty()) out += Alloc(inv.id, 0.0)
                continue
            }
            val take = min(openCents, remaining)
            remaining -= take
            if (openCents > 0 && take == openCents) closed++
            out += Alloc(inv.id, take / 100.0)
        }
        return AllocResult(
            allocations = out,
            unallocated = remaining / 100.0,
            closedCount = closed,
        )
    }
}

// ═══════════════ 9) RunsMath: اختبار الجولات ═══════════════

object RunsMath {

    /**
     * اختبار الجولات (Wald–Wolfowitz) حول الوسيط: هل الإيراد اليومي متقطع عناقيد
     * (أيام سيئة متتالية) أم متناوب عشوائياً؟ القيم المساوية للوسيط تُستبعد (عقد موثق).
     * عقد: بعد الاستبعاد يجب أن تكون كل مجموعة ≥5، وإلا null؛ تشتت صفري ⇒ null.
     * الحكم: |z|<1.96 RANDOM، z≤−1.96 CLUSTERED، وإلا ALTERNATING.
     */
    data class Runs(
        val runs: Int,
        val expectedRuns: Double,
        val z: Double,
        val verdict: String,   // RANDOM / CLUSTERED / ALTERNATING
    )

    fun runsTest(values: List<Double>): Runs? {
        if (values.size < 10 || !r14Finite(values)) return null
        val med = r14Median(values)
        val flags = values.filter { it != med }.map { it > med }
        val n1 = flags.count { !it }
        val n2 = flags.count { it }
        if (n1 < 5 || n2 < 5) return null
        var runs = 1
        for (i in 1 until flags.size) if (flags[i] != flags[i - 1]) runs++
        val n = (n1 + n2).toDouble()
        val expected = 2.0 * n1 * n2 / n + 1.0
        val variance = 2.0 * n1 * n2 * (2.0 * n1 * n2 - n1 - n2) / (n * n * (n - 1))
        if (variance <= 0.0) return null
        val z = (runs - expected) / sqrt(variance)
        val verdict = when {
            z <= -1.96 -> "CLUSTERED"
            z >= 1.96 -> "ALTERNATING"
            else -> "RANDOM"
        }
        return Runs(runs = runs, expectedRuns = expected, z = z, verdict = verdict)
    }
}

// ═══════════════ 10) EntropyMath: عدد الفئات الفعّال ═══════════════

object EntropyMath {

    /**
     * عدد الفئات الفعّال: exp(إنتروبي شانون) للحصص — لو توزّع الإيراد بالتساوي
     * على N فئات لكان العدد الفعّال N؛ تركّز على فئتين يُنزله قريباً من 2
     * حتى لو كانت لديك عشرين فئة مسجلة.
     * عقد: قيم موجبة بعد إسقاط الأصفار وإلا null.
     * الحكم: فعّال<3 CONCENTRATED، <6 BALANCED، وإلا DIVERSE.
     */
    data class Ent(
        val entropy: Double,
        val effectiveCount: Double,
        val verdict: String,   // CONCENTRATED / BALANCED / DIVERSE
    )

    fun effectiveCategories(values: List<Double>): Ent? {
        val pos = values.filter { it > 0.0 }
        if (pos.isEmpty() || !r14Finite(pos)) return null
        val total = pos.sum()
        val h = -pos.sumOf { v ->
            val p = v / total
            p * ln(p)
        }
        val eff = exp(h)
        val verdict = when {
            eff < 3.0 -> "CONCENTRATED"
            eff < 6.0 -> "BALANCED"
            else -> "DIVERSE"
        }
        return Ent(entropy = h, effectiveCount = eff, verdict = verdict)
    }
}

// ═══════════════ 11) AbcXyzMath: مصفوفة ABC×XYZ ═══════════════

object AbcXyzMath {

    /**
     * مصفوفة ABC×XYZ التسعة: A/B/C بحصة الإيراد التراكمية (≤80٪ / ≤95٪ / الباقي،
     * والعنصر العابر للحد ينضم للفئة الأعلى) وX/Y/Z بمعامل تغير الكمية الدورية
     * (cv≤0.5 مستقر / ≤1.0 متوسط / أكثر اضطراباً). الخلية AX كنز، CZ عبء ميت محتمل.
     * عقد: عناصر بإيراد >0 وإلا تُسقط؛ لا عناصر ⇒ null؛ cv بمتوسط صفري ⇒ Z.
     * getQtySeries سلسلة كميات الفترة لكل عنصر (أسابيع مثلاً).
     */
    data class Item(val id: Long, val revenue: Double, val periodQtys: List<Double>)

    data class Row(val id: Long, val cell: String, val revenue: Double)

    data class MatrixResult(
        val rows: List<Row>,          // مرتبة بالإيراد تنازلياً
        val cellCounts: Map<String, Int>,
    )

    fun matrix(items: List<Item>): MatrixResult? {
        val valid = items.filter { it.revenue.isFinite() && it.revenue > 0.0 }
        if (valid.isEmpty()) return null
        if (valid.any { it.periodQtys.any { q -> !q.isFinite() } }) return null
        val total = valid.sumOf { it.revenue }
        val sorted = valid.sortedByDescending { it.revenue }
        var cum = 0.0
        val rows = ArrayList<Row>(sorted.size)
        val counts = LinkedHashMap<String, Int>()
        for (it in sorted) {
            val abc = when {
                cum < total * 0.80 -> "A"
                cum < total * 0.95 -> "B"
                else -> "C"
            }
            val mu = r14Mean(it.periodQtys)
            val xyz = if (mu <= 0.0) {
                "Z"
            } else {
                val cv = r14Std(it.periodQtys, mu) / mu
                when {
                    cv <= 0.5 -> "X"
                    cv <= 1.0 -> "Y"
                    else -> "Z"
                }
            }
            val cell = "$abc$xyz"
            counts[cell] = (counts[cell] ?: 0) + 1
            rows += Row(it.id, cell, it.revenue)
            cum += it.revenue
        }
        return MatrixResult(rows = rows, cellCounts = counts)
    }
}

// ═══════════════ 12) ShrinkageMath: تسرّب المخزون ═══════════════

object ShrinkageMath {

    /**
     * تسرّب المخزون: حركات التسوية السالبة (ADJUST بكمية سالبة) هي بصمة النقص
     * الفعلي المسجل — قيمتها بتكلفة الوحدة مقابل تكلفة المبيعات للفترة نفسها
     * يعطي نسبة التسرّب. المدخلات الأخرى (موجبة/صفرية) تُسقط.
     * عقد: لا حركة سالبة واحدة ⇒ null؛ cogs≤0 ⇒ pctOfCogs=null (لا بأسطورة قسمة).
     * worst أعلى ثلاثة منتجات بقيمة الفقد (تعادل: الأصغر معرفاً).
     */
    data class Move(val productId: Long, val qty: Double, val unitCost: Double)

    data class Worst(val productId: Long, val lostValue: Double)

    data class Shrink(
        val lostQty: Double,
        val lostValue: Double,
        val pctOfCogs: Double?,
        val worst: List<Worst>,
    )

    fun shrinkage(moves: List<Move>, cogsPeriod: Double): Shrink? {
        val neg = moves.filter { it.qty < 0.0 && it.qty.isFinite() && it.unitCost.isFinite() && it.unitCost >= 0.0 }
        if (neg.isEmpty()) return null
        val lostQty = neg.sumOf { -it.qty }
        val lostValue = neg.sumOf { -it.qty * it.unitCost }
        if (lostValue <= 0.0) return null
        val pct = if (cogsPeriod.isFinite() && cogsPeriod > 0.0) lostValue / cogsPeriod * 100.0 else null
        val byProduct = neg.groupBy { it.productId }
            .map { (pid, ms) -> Worst(pid, ms.sumOf { -it.qty * it.unitCost }) }
            .sortedWith(compareByDescending<Worst> { it.lostValue }.thenBy { it.productId })
            .take(3)
        return Shrink(lostQty = lostQty, lostValue = lostValue, pctOfCogs = pct, worst = byProduct)
    }
}

// ═══════════════ 13) CatalogHygieneMath: نظافة الكتالوج ═══════════════

object CatalogHygieneMath {

    /**
     * نظافة بيانات الكتالوج: خمس خصائص لكل منتج (باركود، فئة، تكلفة، سعر بيع،
     * مخزون غير سالب) — درجة من 100 تعني كتالوجاً جاهزاً للفلاتر والطباعة والتقارير.
     * عقد: قائمة فارغة ⇒ null. missing* عدّاد كل نوع عيب، worst أسوأ ثلاثة معرفات.
     */
    data class P(
        val id: Long,
        val barcode: String,
        val category: String,
        val costPrice: Double,
        val salePrice: Double,
        val stockQty: Double,
    )

    data class Hygiene(
        val scorePct: Double,
        val missingBarcode: Int,
        val missingCategory: Int,
        val missingCost: Int,
        val missingPrice: Int,
        val negativeStock: Int,
        val worst: List<Long>,
    )

    fun hygiene(products: List<P>): Hygiene? {
        if (products.isEmpty()) return null
        var noBar = 0
        var noCat = 0
        var noCost = 0
        var noPrice = 0
        var negQty = 0
        val defect = ArrayList<Pair<Long, Int>>(products.size)
        for (p in products) {
            var d = 0
            if (p.barcode.isBlank()) { noBar++; d++ }
            if (p.category.isBlank()) { noCat++; d++ }
            if (!(p.costPrice.isFinite() && p.costPrice > 0.0)) { noCost++; d++ }
            if (!(p.salePrice.isFinite() && p.salePrice > 0.0)) { noPrice++; d++ }
            if (p.stockQty.isFinite() && p.stockQty < 0.0) { negQty++; d++ }
            if (d > 0) defect += p.id to d
        }
        val issues = noBar + noCat + noCost + noPrice + negQty
        val score = 100.0 * (1.0 - issues.toDouble() / (products.size * 5.0))
        return Hygiene(
            scorePct = score,
            missingBarcode = noBar,
            missingCategory = noCat,
            missingCost = noCost,
            missingPrice = noPrice,
            negativeStock = negQty,
            worst = defect.sortedWith(compareByDescending<Pair<Long, Int>> { it.second }.thenBy { it.first })
                .take(3).map { it.first },
        )
    }
}

// ═══════════════ 14) BandMath: أحزمة الهامش ═══════════════

object BandMath {

    /**
     * توزيع المنتجات المبيعة على أحزمة هامش ثابتة: سلبي، هزيل <10٪، منخفض <20٪،
     * مقبول <35٪، جيد <50٪، وممتاز ≥50٪ — أين يتركّز كتالوجك فعلاً؟
     * عقد: قائمة فارغة أو قيم غير منتهية ⇒ null. belowThin معرفات الحزمتين
     * السلبي/الهزيل مرتبة بالهامش تصاعدياً (أسوأ ما يجب معالجته أولاً).
     */
    data class M(val id: Long, val marginPct: Double)

    data class Bands(
        val negative: Int,
        val thin: Int,
        val low: Int,
        val ok: Int,
        val good: Int,
        val top: Int,
        val belowThin: List<Long>,
    )

    fun marginBands(margins: List<M>): Bands? {
        if (margins.isEmpty() || margins.any { !it.marginPct.isFinite() }) return null
        var neg = 0
        var thin = 0
        var low = 0
        var ok = 0
        var good = 0
        var top = 0
        val thinIds = ArrayList<Pair<Long, Double>>()
        for (m in margins) {
            val v = m.marginPct
            when {
                v < 0.0 -> { neg++; thinIds += m.id to v }
                v < 10.0 -> { thin++; thinIds += m.id to v }
                v < 20.0 -> low++
                v < 35.0 -> ok++
                v < 50.0 -> good++
                else -> top++
            }
        }
        return Bands(
            negative = neg,
            thin = thin,
            low = low,
            ok = ok,
            good = good,
            top = top,
            belowThin = thinIds.sortedBy { it.second }.map { it.first },
        )
    }
}

// ═══════════════ 15) BordaMath: تجميع الرتب ═══════════════

object BordaMath {

    /**
     * عدّ بورا: دمج k قائمة رتب (الأفضل أولاً) لمجموعة المعرفات نفسها في ترتيب
     * واحد متين — أفضل من أي مقياس منفرد لأنه لا يُسحق بمنتج واحد شاذّ.
     * النقاط لكل قائمة: (n−1−المرتبة). عقد: قائمتان على الأقل، والمجموعات متطابقة
     * وبلا تكرار داخل القائمة الواحدة، وإلا null. التعادل النهائي: الأصغر معرفاً.
     */
    data class BR(val id: Long, val score: Int, val avgRank: Double)

    data class BordaResult(val order: List<BR>)

    fun bordaRank(lists: List<List<Long>>): BordaResult? {
        if (lists.size < 2) return null
        val first = lists[0].toSet()
        if (first.size != lists[0].size) return null
        for (l in lists) {
            if (l.toSet() != first || l.size != lists[0].size) return null
        }
        val n = lists[0].size
        val scores = HashMap<Long, Int>()
        val ranks = HashMap<Long, MutableList<Int>>()
        for (id in first) {
            scores[id] = 0
            ranks[id] = ArrayList()
        }
        for (l in lists) {
            for ((pos, id) in l.withIndex()) {
                scores[id] = scores[id]!! + (n - 1 - pos)
                ranks[id]!!.add(pos + 1)
            }
        }
        val order = scores.keys
            .sortedWith(compareByDescending<Long> { scores[it]!! }.thenBy { it })
            .map { id -> BR(id, scores[id]!!, r14Mean(ranks[id]!!.map { it.toDouble() })) }
        return BordaResult(order)
    }
}

// ═══════════════ 16) MoverMath: صاعدو وهابطو الرتب ═══════════════

object MoverMath {

    /**
     * حركة الرتب بين فترتين: رتبة المنتج بالإيراد في الفترة السابقة مقابل الحالية
     * (رتب تنافسية: المتساوون يتشاركون أعلى رتبة) — من تسلق ومن انزلق؟
     * عقد: 4 منتجات مشتركة بإيراد موجب على الأقل وإلا null.
     * climbers/fallers أفضل ثلاثة (تعادل الدلتا: الأعلى إيراداً حالياً).
     */
    data class Mv(val id: Long, val delta: Int, val prevRank: Int, val currRank: Int)

    data class Movers(val climbers: List<Mv>, val fallers: List<Mv>)

    private fun ranks(rev: Map<Long, Double>): Map<Long, Int> {
        val sorted = rev.entries.sortedByDescending { it.value }
        val out = HashMap<Long, Int>(rev.size)
        var i = 0
        while (i < sorted.size) {
            var j = i
            while (j + 1 < sorted.size && sorted[j + 1].value == sorted[i].value) j++
            val rank = i + 1
            for (k in i..j) out[sorted[k].key] = rank
            i = j + 1
        }
        return out
    }

    fun rankMovers(prev: Map<Long, Double>, curr: Map<Long, Double>): Movers? {
        val p = prev.filterValues { it.isFinite() && it > 0.0 }
        val c = curr.filterValues { it.isFinite() && it > 0.0 }
        val common = p.keys intersect c.keys
        if (common.size < 4) return null
        val pr = ranks(p)
        val cr = ranks(c)
        val mv = common.map { id -> Mv(id, pr[id]!! - cr[id]!!, pr[id]!!, cr[id]!!) }
        val curRev = { id: Long -> c[id] ?: 0.0 }
        val climbers = mv.filter { it.delta > 0 }
            .sortedWith(compareByDescending<Mv> { it.delta }.thenByDescending { curRev(it.id) })
            .take(3)
        val fallers = mv.filter { it.delta < 0 }
            .sortedWith(compareBy<Mv> { it.delta }.thenByDescending { curRev(it.id) })
            .take(3)
        return Movers(climbers = climbers, fallers = fallers)
    }
}

// ═══════════════ 17) BlendMath: تنبؤ مركّب موزون بالخطأ ═══════════════

object BlendMath {

    /**
     * تنبؤ مركّب: مكوّنان حتميان — تجانس مزدوج (Holt: α=0.5، β=0.25) ومتوسط متحرك
     * لآخر 4 نقاط — يُوزَّن كل منهما بعكس متوسط خطأه المطلق في المعاودة الخلفية
     * خطوة-أماماً على التاريخ المتاح (من النقطة السادسة). الأعلى دقة يأخذ الوزن الأكبر.
     * عقد: ≥12 نقطة منتهية وhorizon≥1 وإلا null. خطأ أحد المكوّنين صفراً ⇒ وزنه 1.
     */
    data class Blend(
        val forecast: List<Double>,   // horizon قيم قادمة
        val wHolt: Double,
        val wSma: Double,
        val maeHolt: Double,
        val maeSma: Double,
    )

    private fun holtStep(series: List<Double>): Double {
        // مستوى وميل بنهاية السلسلة (تحديث تسلسلي بعقد ثابتة موثقة)
        var level = series[0]
        var trend = series[1] - series[0]
        for (i in 1 until series.size) {
            val prevLevel = level
            level = 0.5 * series[i] + 0.5 * (level + trend)
            trend = 0.25 * (level - prevLevel) + 0.75 * trend
        }
        return level + trend
    }

    private fun smaStep(series: List<Double>): Double =
        series.takeLast(4).sum() / 4.0

    fun errorWeightedForecast(series: List<Double>, horizon: Int = 4): Blend? {
        if (series.size < 12 || !r14Finite(series) || horizon < 1) return null
        val n = series.size
        var errH = 0.0
        var errS = 0.0
        var cnt = 0
        // معاودة خلفية خطوة-أماماً: نبدأ من 6 نقاط تاريخ على الأقل لكل توقع
        for (t in 6 until n) {
            val hist = series.subList(0, t)
            val actual = series[t]
            errH += abs(holtStep(hist) - actual)
            errS += abs(smaStep(hist) - actual)
            cnt++
        }
        if (cnt == 0) return null
        val maeH = errH / cnt
        val maeS = errS / cnt
        val wH: Double = when {
            maeH <= 0.0 && maeS <= 0.0 -> 0.5
            maeH <= 0.0 -> 1.0
            maeS <= 0.0 -> 0.0
            else -> (1.0 / maeH) / ((1.0 / maeH) + (1.0 / maeS))
        }
        val wS = 1.0 - wH
        val hist = series.subList(0, n)
        val fS = smaStep(hist)
        val out = (1..horizon).map { h ->
            // Holt خطي بالميل: التوقع يتقدم h خطوات؛ SMA مستوٍ
            val holtH = holtLinear(hist, h)
            wH * holtH + wS * fS
        }
        return Blend(forecast = out, wHolt = wH, wSma = wS, maeHolt = maeH, maeSma = maeS)
    }

    private fun holtLinear(series: List<Double>, h: Int): Double {
        var level = series[0]
        var trend = series[1] - series[0]
        for (i in 1 until series.size) {
            val prevLevel = level
            level = 0.5 * series[i] + 0.5 * (level + trend)
            trend = 0.25 * (level - prevLevel) + 0.75 * trend
        }
        return level + h * trend
    }
}

// ═══════════════ 18) StalenessMath: عمر الذمم المرجّح ═══════════════

object StalenessMath {

    /**
     * عمر الذمم المرجّح بالمبلغ: متوسط ووسين عمر الفواتير المفتوحة حيث وزن كل
     * فاتورة مفتوحها — رصيد 10,000 عمره 90 يوماً أخطر من خمسة أرصدة صغيرة جديدة،
     * والوسطى البسيطة تخفي ذلك. عقد: مجموع المفتوح >0 وإلا null.
     * الحكم: الوسطى المرجّحة <30 يوماً FRESH، ≤60 AGING، وإلا STALE.
     */
    data class Age(
        val weightedMeanDays: Double,
        val weightedMedianDays: Double,
        val verdict: String,   // FRESH / AGING / STALE
    )

    fun weightedAge(items: List<Pair<Double, Long>>): Age? {
        // كل عنصر: (المفتوح، العمر بالأيام) — الزوج موثق لتوافق ترتيب البارامترات
        val valid = items.filter { it.first.isFinite() && it.first > 0.0 && it.second >= 0 }
        val total = valid.sumOf { it.first }
        if (valid.isEmpty() || total <= 0.0) return null
        val mean = valid.sumOf { it.first * it.second } / total
        val sorted = valid.sortedBy { it.second }
        val half = total / 2.0
        var acc = 0.0
        var med = sorted.last().second.toDouble()
        for ((open, age) in sorted) {
            acc += open
            if (acc >= half) {
                med = age.toDouble()
                break
            }
        }
        // [P6-M21 إصلاح]: الحكم كان على الوسطى المرجّحة بينما الوثيقة (سطر التوثيق
        // أعلاه) تقول الوسيط المرجّح — رصيد ثقيل قديم كان يُخفى خلف أغلبية فواتير
        // حديثة (الوسطى البسيطة تتقاذفه فوق 60 يوماً فيبدو الحكم أحدث من الحقيقة).
        // الحكم الآن على الوسيط المرجّح كما في التوثيق، والوسطى تبقى معروضة.
        val verdict = when {
            med < 30.0 -> "FRESH"
            med <= 60.0 -> "AGING"
            else -> "STALE"
        }
        return Age(weightedMeanDays = mean, weightedMedianDays = med, verdict = verdict)
    }
}

// ═══════════════ 19) GapMath: أفق تأجيل الشيكات ═══════════════

object GapMath {

    /**
     * أفق تأجيل الشيكات: الفرق بالأيام بين الإصدار والاستحقاق لشيكاتك الواردة —
     * كم يقدّم عملاؤك الشيكات عادة؟ وسطى يحدد سياستك، والحد الأقصى يكشف الاستثناءات.
     * عقد: ≥3 فجوات منتهية و≥0 وإلا null.
     * الحكم: الوسطى ≤10 أيام SHORT، ≤30 MEDIUM، وإلا LONG.
     */
    data class Terms(
        val medianDays: Double,
        val p90Days: Double,
        val maxDays: Double,
        val verdict: String,   // SHORT / MEDIUM / LONG
    )

    fun checkTermsGap(gapDays: List<Double>): Terms? {
        if (gapDays.size < 3 || !r14Finite(gapDays) || gapDays.any { it < 0.0 }) return null
        val sorted = gapDays.sorted()
        val med = r14Median(sorted)
        val p90 = r14Pct(sorted, 90.0)
        val verdict = when {
            med <= 10.0 -> "SHORT"
            med <= 30.0 -> "MEDIUM"
            else -> "LONG"
        }
        return Terms(medianDays = med, p90Days = p90, maxDays = sorted.last(), verdict = verdict)
    }
}

// ═══════════════ 20) FixedReserveMath: احتياطي الثوابت اليومي ═══════════════

object FixedReserveMath {

    /**
     * الاحتياطي اليومي للتكاليف الثابتة: الإيجار والرواتب لا تنتظر نهاية الشهر —
     * كم يجب أن يدّخر كل يوم عمل من التغطية النقدية؟ ومقارنته بمتوسط التحصيل
     * اليومي تُظهر إن كان النقد يواكب الثوابت أم يلحق بهم.
     * عقد: fixedMonthly منتهٍ و>0 وworkingDays≥1 وavgDailyCashIn≥0 وإلا null.
     * الحكم: تغطية≥1.2 SAFE، ≥1.0 TIGHT، وإلا STRAINED.
     */
    data class Reserve(
        val perWorkingDay: Double,
        val coverage: Double,
        val verdict: String,   // SAFE / TIGHT / STRAINED
    )

    fun dailyFixedReserve(fixedMonthly: Double, workingDays: Int, avgDailyCashIn: Double): Reserve? {
        if (!fixedMonthly.isFinite() || fixedMonthly <= 0.0) return null
        if (workingDays < 1) return null
        if (!avgDailyCashIn.isFinite() || avgDailyCashIn < 0.0) return null
        val perDay = fixedMonthly / workingDays
        val coverage = avgDailyCashIn / perDay
        val verdict = when {
            coverage >= 1.2 -> "SAFE"
            coverage >= 1.0 -> "TIGHT"
            else -> "STRAINED"
        }
        return Reserve(perWorkingDay = perDay, coverage = coverage, verdict = verdict)
    }
}
