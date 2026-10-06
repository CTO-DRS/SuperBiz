package com.superbiz.app.domain.algo

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * — عشرون خوارزمية حتمية جديدة للموجة R15، في 20 كائناً مستقلياً
 * لا يتقاطع أيّها مع R7/R9/R10/R11/R12/R13/R14 ولا مع كائنات الحساب الأساسية
 *
 * BayesMath (1) : تحديث بايزي للاعتقاد بلوغاريتم الأرجحيات
 * BenfordMath (2) : قانون بِنفورد للرقم الأول (رصد التلاعب بالفواتير)
 * CusumMath (3) : كشف الانحراف التصاعدي بمجاميع الجريمة التراكمية
 * ElasticityMath (4) : مرونة السعر القوسية من نقطتي سعر/كمية
 * FourierMath (5) : الدورة السائدة في سلسلة يومية (ذروة المداوي DFT)
 * GamblerMath (6) : أفق احتراق النقد ودرجة العازلة (نمط مخاطرة الإفلاس)
 * GrubbsMath (7) : اختبار جريبس للقيمة الشاذة الواحدة
 * IqrMath (8) : سياج IQR وتصنيف الشواذ كلها
 * JaccardMath (9) : تشابه السلات المشتركة (جاكراد) وأزواج الرفع
 * KappaMath (10) : توافق كابا بين المخطط والمنجز
 * LogitMath (11) : درجة احتمالية لوجستية لمخاطر التسرب
 * MarkovMath (12) : مصفوفة انتقالات اليوم صاعد/هابط وتوزيعها المستقر
 * NelsonMath (13) : قواعد نيلسون 1/2/3 على خريطة التحكم
 * OeeMath (14) : كفاءة إجمالية مركّبة (تعبئة×تصريف×هامش)
 * ParetoMath (15) : عتبة 80/20 (كم بنداً يصنع الثمانين٪)
 * RecencyMath (16) : تسجيل RFM بحدود صريحة حتمية
 * TheilMath (17) : ميل ثيل-سن المتين مع التقاطع الوسيطي
 * UtestMath (18) : مان-ويتني U لمقارنة فترتين مستقلتين
 * VaRMath (19) : القيمة المعرضة للخطر التاريخية والشرطية CVaR
 * ZipfMath (20) : تركّز الرأس قانون زيبف مقابل المتوقع
 *
 * عقد الصدق نفسه: بلا بيانات كافية أو بمدخلات غير منتهية ⇒ null/قائمة فارغة،
 * ولا يُقرأ وقت النظام داخل أي خوارزمية — كلها دوال حتمية خالصة قابلة للاختبار
 * (ما يحتاج "اليوم" يُمرر صريحاً من المستدعي).
*/

private fun r15Finite(values: Collection<Double>): Boolean = values.all { it.isFinite() }

private fun r15Mean(values: List<Double>): Double = values.sum() / values.size

private fun r15Std(values: List<Double>, m: Double): Double =
    sqrt(values.sumOf { (it - m) * (it - m) } / values.size)

/** [P6-M20 إصلاح] σ العينة (÷n−1) — جدول جريبس الحرج crit5 مبني عليها لا على σ المجتمعية. */
private fun r15StdSample(values: List<Double>, m: Double): Double =
    sqrt(values.sumOf { (it - m) * (it - m) } / (values.size - 1))

/** مئين خطي (نمط R-7 نفسه): فهرس = q·(n−1) مع استقراء خطي بين الجارين. */
private fun r15Pct(sorted: List<Double>, qPct: Double): Double {
    val n = sorted.size
    if (n == 1) return sorted[0]
    val idx = (qPct / 100.0) * (n - 1)
    val lo = idx.toInt().coerceIn(0, n - 1)
    val hi = (lo + 1).coerceAtMost(n - 1)
    val frac = idx - lo
    return sorted[lo] + frac * (sorted[hi] - sorted[lo])
}

// ═══════════════ 1) BayesMath: تحديث بايزي ═══════════════

object BayesMath {

    /** نتيجة تحديث بايزي مجمّعة (للعرض) */
    data class Posterior(
        val prior: Double,
        val likelihoodRatio: Double,
        val posterior: Double
    )

    /**
     * تحديث احتمال مسبق بافتراض جديد بنسبة أرجحية LR:
     * posterior = LR·p / (LR·p + (1−p)). باتجاه لوغاريتمي مستقر.
     * يعيد null إذا كان المسبق خارج (0,1) أو LR غير منتهٍ/غير موجب.
     */
    fun posterior(prior: Double, likelihoodRatio: Double): Double? {
        if (!prior.isFinite() || !likelihoodRatio.isFinite()) return null
        if (prior <= 0.0 || prior >= 1.0 || likelihoodRatio <= 0.0) return null
        val logOdds = ln(prior / (1.0 - prior)) + ln(likelihoodRatio)
        return 1.0 / (1.0 + exp(-logOdds))
    }

    /** تعدد أدلة متتالية — null عند أول دليل غير صالح. */
    fun posteriorChain(prior: Double, ratios: List<Double>): Double? =
        ratios.fold(prior as Double?) { acc, lr -> acc?.let { posterior(it, lr) } }
}

// ═══════════════ 2) BenfordMath: قانون الرقم الأول ═══════════════

object BenfordMath {

    data class Benford(
        val counts: IntArray,          // عدّاد الرقم 1..9
        val observed: List<Double>,    // الحصة الملحوظة لكل رقم (تجميعها 1)
        val expected: List<Double>,    // log10(1 + 1/d) النظرية
        val deviation: Double          // نصف مسافة التباين الكلية 0..1 (0 = مطابقة تامة)
    )

    /** الحد الأدنى 5 مبالغ وإلا null؛ المبالغ ≤0 تُهمل (لا رقم أول لها). */
    fun firstDigitShares(amounts: List<Double>): Benford? {
        val vals = amounts.filter { it.isFinite() && it > 0.0 }
        if (vals.size < 5) return null
        val counts = IntArray(9)
        for (v in vals) {
            // [P20-FIX agent12]: أول محرف في 1..9 مباشرة — كان trimStart('0') يُنتج '.05' لعناصر <0.1
            // فتُسقط من العدّ (الشريط الأول الرقم '0') رغم أن الوثيقة تُهمل ≤0 فقط
            val d = abs(v).toString().firstOrNull { it in '1'..'9' } ?: continue
            counts[d - '1']++
        }
        val total = counts.sum()
        if (total == 0) return null
        val observed = counts.map { it.toDouble() / total }
        val expected = (1..9).map { log10(1.0 + 1.0 / it) }
        val tvd = observed.zip(expected).sumOf { (o, e) -> abs(o - e) } / 2.0
        return Benford(counts, observed, expected, tvd)
    }
}

// ═══════════════ 3) CusumMath: كشف الانحراف ═══════════════

object CusumMath {

    data class Cusum(
        val mean: Double,
        val sigma: Double,
        val firstAlarmIndex: Int?,   // أول فهرس تجاوز العتبة (بعد التطبيع)
        val alarmValue: Double?      // قيمة S+ عند الإنذار
    )

    /**
     * كشف انحراف تصاعدي: يطبّع السلسلة (x−μ)/σ ويجمع المجاميع التراكمية
     * S+ = max(0, S+ + z − k) بسماح k=0.5؛ إنذار عند S+ > threshold.
     * يعيد null إذا n<8 أو σ=0 أو threshold غير موجب.
     */
    fun detect(series: List<Double>, threshold: Double = 5.0): Cusum? {
        if (series.size < 8 || !threshold.isFinite() || threshold <= 0.0) return null
        if (!r15Finite(series)) return null
        val m = r15Mean(series)
        val s = r15Std(series, m)
        if (s <= 1e-12) return null
        var sup = 0.0
        for ((i, x) in series.withIndex()) {
            val z = (x - m) / s
            sup = max(0.0, sup + z - 0.5)
            if (sup > threshold) return Cusum(m, s, i, sup)
        }
        return Cusum(m, s, null, null)
    }
}

// ═══════════════ 4) ElasticityMath: المرونة القوسية ═══════════════

object ElasticityMath {

    data class Elasticity(
        val value: Double,       // سالب عادة (سعر أعلى ⇒ كمية أقل)
        val verdict: String      // INELASTIC / UNIT / ELASTIC
    )

    /**
     * المرونة القوسية (منتصف النقطة): Δq% ÷ Δp% بالقسمة على المتوسطين.
     * يعيد null إذا p1 أو p2 ≤ 0 أو تساوي السعران (قسمة على صفر).
     */
    fun arc(p1: Double, q1: Double, p2: Double, q2: Double): Elasticity? {
        if (!(p1.isFinite() && p2.isFinite() && q1.isFinite() && q2.isFinite())) return null
        if (p1 <= 0.0 || p2 <= 0.0) return null
        if (abs(p2 - p1) < 1e-12) return null
        // [P20-FIX agent12]: منتصف الكمية صفر (q2 = −q1) ⇒ قسمة 0/0 = NaN كانت تُصنّف ELASTIC — null أصدق
        if (q1 + q2 == 0.0) return null
        val dq = (q2 - q1) / ((q1 + q2) / 2.0)
        val dp = (p2 - p1) / ((p1 + p2) / 2.0)
        if (abs(dp) < 1e-12) return null
        val e = dq / dp
        if (!e.isFinite()) return null
        val v = when {
            abs(e) < 1.0 - 1e-9 -> "INELASTIC"
            abs(e) <= 1.0 + 1e-9 -> "UNIT"
            else -> "ELASTIC"
        }
        return Elasticity(e, v)
    }
}

// ═══════════════ 5) FourierMath: الدورة السائدة ═══════════════

object FourierMath {

    data class Cycle(
        val period: Int,        // أفضل دورة (بالأيام)
        val strength: Double    // المدى ×2 / n للمداوي المرشحة الأقوى
    )

    /**
     * ذروة المداوي: لكل دورة مرشحة p في 2..n/2 يُحسب مدى جيب التردد
     |Σ x_t·e^{−2πi t/p}|×2/n؛ الأقوى هي الدورة السائدة.
     * يعيد null إذا n<16 أو التباين صفر.
     */
    fun dominantCycle(values: List<Double>): Cycle? {
        val n = values.size
        if (n < 16 || !r15Finite(values)) return null
        val m = r15Mean(values)
        if (r15Std(values, m) <= 1e-12) return null
        var bestP = 0
        var bestAmp = 0.0
        for (p in 2..n / 2) {
            var re = 0.0
            var im = 0.0
            for (t in 0 until n) {
                val ang = 2.0 * PI * t / p
                re += values[t] * kotlin.math.cos(ang)
                im -= values[t] * kotlin.math.sin(ang)
            }
            val amp = 2.0 * sqrt(re * re + im * im) / n
            if (amp > bestAmp) { bestAmp = amp; bestP = p }
        }
        if (bestP == 0) return null
        return Cycle(bestP, bestAmp)
    }
}

// ═══════════════ 6) GamblerMath: أفق الاحتراق ═══════════════

object GamblerMath {

    data class Runway(
        val burnDays: Int,        // أيام حتى نفاد النقد بمعدل الاحتراق الحالي
        val bufferSigma: Double   // النقد ÷ الانحراف اليومي (كم يوم-سيجما من عازل)
    )

    /**
     * نمط مخاطرة الإفلاس: إذا كان صافي اليوم متوسطه سالباً (احتراق)،
     * كم يوماً حتى النفاد؟ وكم «سيجما يومية» يشكل النقد عازلاً؟
     * يعيد null إذا المتوسط ≥ 0 (لا احتراق) أو cash ≤ 0.
     */
    fun ruinHorizon(cash: Double, dailyNetMean: Double, dailyNetStd: Double): Runway? {
        if (!cash.isFinite() || !dailyNetMean.isFinite() || !dailyNetStd.isFinite()) return null
        if (cash <= 0.0 || dailyNetMean >= 0.0) return null
        val burn = ceil(cash / (-dailyNetMean)).toInt().coerceAtLeast(1)
        val sigma = if (dailyNetStd > 0.0) cash / dailyNetStd else 0.0
        return Runway(burn, sigma)
    }
}

// ═══════════════ 7) GrubbsMath: القيمة الشاذة الواحدة ═══════════════

object GrubbsMath {

    data class Grubbs(
        val g: Double,          // الإحصاءة G = max|x−μ|/σ
        val index: Int,         // فهرس القيمة الأبعد
        val critical: Double,   // الحد الحرج التقريبي 5%
        val isOutlier: Boolean
    )

    /** حدود جريبس التقريبية 5% لـ n=3..30 (جدول مضغوط، خارج المدى استقراء متحفظ). */
    private fun crit5(n: Int): Double {
        val table = mapOf(
            3 to 1.153, 4 to 1.463, 5 to 1.672, 6 to 1.822, 7 to 1.938, 8 to 2.032,
            9 to 2.110, 10 to 2.176, 11 to 2.234, 12 to 2.285, 13 to 2.331, 14 to 2.371,
            15 to 2.409, 16 to 2.443, 17 to 2.475, 18 to 2.504, 19 to 2.532, 20 to 2.557,
            21 to 2.580, 22 to 2.603, 23 to 2.624, 24 to 2.644, 25 to 2.663, 26 to 2.681,
            27 to 2.698, 28 to 2.714, 29 to 2.730, 30 to 2.745
        )
        return when {
            n < 3 -> Double.MAX_VALUE
            n <= 30 -> table.getValue(n)
            else -> 3.0
        }
    }

    /**
     * اختبار جريبس للقيمة الشاذة الواحدة (ثنائي الجانب 5%).
     * [P6-M20 إصلاح]: كان G يُقام على σ المجتمعية (÷n) بينما جدول crit5 مبني على
     * σ العينة (÷n−1) — أقصى G ممكن بσ المجتمعية هو √(n−1) فيتجاوز الجدول كثيراً
     * فتُعلن شواذ كاذبة بنسبة 5-22%. جريبس المعياري يستخدم σ العينة.
     * يعيد null إذا n<3 أو σ=0 أو مدخلات غير منتهية.
     */
    fun extreme(series: List<Double>): Grubbs? {
        if (series.size < 3 || !r15Finite(series)) return null
        val m = r15Mean(series)
        val s = r15StdSample(series, m)   // σ العينة (÷n−1) توحيداً مع جدول crit5
        if (s <= 1e-12) return null
        var idx = 0
        var g = 0.0
        for ((i, x) in series.withIndex()) {
            val cand = abs(x - m) / s
            if (cand > g) { g = cand; idx = i }
        }
        val c = crit5(series.size)
        return Grubbs(g, idx, c, g > c)
    }
}

// ═══════════════ 8) IqrMath: سياج الربعين ═══════════════

object IqrMath {

    data class Fences(
        val q1: Double, val q3: Double, val iqr: Double,
        val lowFence: Double, val highFence: Double,
        val outliers: List<Pair<Int, Double>>   // (فهرس، قيمة) خارج السياجين
    )

    /**
     * سياج توكي 1.5×IQR وتصنيف كل النقاط الخارجية.
     * يعيد null إذا القائمة فارغة أو غير منتهية.
     */
    fun fences(series: List<Double>): Fences? {
        if (series.isEmpty() || !r15Finite(series)) return null
        val sorted = series.sorted()
        val q1 = r15Pct(sorted, 25.0)
        val q3 = r15Pct(sorted, 75.0)
        val iqr = q3 - q1
        val low = q1 - 1.5 * iqr
        val high = q3 + 1.5 * iqr
        val out = series.withIndex().filter { it.value < low || it.value > high }
            .map { it.index to it.value }
        return Fences(q1, q3, iqr, low, high, out)
    }
}

// ═══════════════ 9) JaccardMath: تشابه السلات ═══════════════

object JaccardMath {

    /** تشابه جاكراد |A∩B|/|A∪B| — سالبان فارغتان معاً تعطيان 1.0 (متطابقتان في الفراغ). */
    fun similarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val union = a.union(b)
        if (union.isEmpty()) return 1.0
        return a.intersect(b).size.toDouble() / union.size.toDouble()
    }

    /**
     * أزواج الرفع المشترك: سلال متعددة ⇒ كل زوج صنفين ظهروا معاً
     * في نسخة السلات أكثر من minSupport مرة.
     */
    fun coPurchase(baskets: List<Set<String>>, minSupport: Int = 2): List<Triple<String, String, Int>> {
        if (minSupport < 2 || baskets.size < 2) return emptyList()
        val counts = HashMap<Pair<String, String>, Int>()
        for (b in baskets) {
            val items = b.filter { it.isNotBlank() }.sorted()
            for (i in items.indices) for (j in i + 1 until items.size) {
                counts.merge(items[i] to items[j], 1, Int::plus)
            }
        }
        return counts.filter { it.value >= minSupport }
            .map { (pair, c) -> Triple(pair.first, pair.second, c) }
            .sortedByDescending { it.third }
    }
}

// ═══════════════ 10) KappaMath: توافق كابا ═══════════════

object KappaMath {

    data class Kappa(
        val observed: Double,   // po
        val expected: Double,   // pe
        val kappa: Double,      // (po−pe)/(1−pe)
        val verdict: String     // STRONG / MODERATE / WEAK
    )

    /**
     * كابا كوهين بين تقييمين متساويي الطول (المخطط مقابل المنجز مثلاً).
     * يعيد null إذا القائمتان فارغتان أو بطولين مختلفين أو pe=1.
     */
    fun agreement(planned: List<String>, actual: List<String>): Kappa? {
        if (planned.isEmpty() || planned.size != actual.size) return null
        val cats = planned.union(actual).sorted()
        val n = planned.size
        var agree = 0
        for (i in 0 until n) if (planned[i] == actual[i]) agree++
        val po = agree.toDouble() / n
        var pe = 0.0
        for (c in cats) {
            pe += (planned.count { it == c }.toDouble() / n) * (actual.count { it == c }.toDouble() / n)
        }
        if (pe >= 1.0 - 1e-12) return null
        val k = (po - pe) / (1.0 - pe)
        val v = when {
            k >= 0.8 -> "STRONG"
            k >= 0.4 -> "MODERATE"
            else -> "WEAK"
        }
        return Kappa(po, pe, k, v)
    }
}

// ═══════════════ 11) LogitMath: درجة التسرب اللوجستية ═══════════════

object LogitMath {

    /**
     * احتمال لوجستي من ميزات جاهزة: p = σ(b0 + b1·x1 + b2·x2).
     * الأوزان تُمرر من المستدعي (قابلة للمعايرة) — الخوارزمية نفسها حتمية.
     * يعيد null إذا أي مدخل غير منتهٍ.
     */
    fun probability(b0: Double, b1: Double, x1: Double, b2: Double, x2: Double): Double? {
        if (!(b0.isFinite() && b1.isFinite() && b2.isFinite() && x1.isFinite() && x2.isFinite())) return null
        val z = b0 + b1 * x1 + b2 * x2
        // تثبيت زائد الحماية من الفيض الأسّي عند |z| الكبير
        val clamped = z.coerceIn(-40.0, 40.0)
        return 1.0 / (1.0 + exp(-clamped))
    }

    /** تسمية صادقة للحافة: LOW <30، MEDIUM <60، HIGH من 60 فأكثر. */
    fun verdict(p: Double): String = when {
        p < 0.30 -> "LOW"
        p < 0.60 -> "MEDIUM"
        else -> "HIGH"
    }
}

// ═══════════════ 12) MarkovMath: انتقالات اليوم ═══════════════

object MarkovMath {

    data class Chain(
        val upToUp: Double?,     // P(U→U)
        val upToDown: Double?,   // P(U→D)
        val downToUp: Double?,   // P(D→U)
        val downToDown: Double?, // P(D→D)
        val stationaryUp: Double // πU = p(D→U)/(p(D→U)+p(U→D)) — الحصيلة طويلة الأمد
    )

    /**
     * مصفوفة الانتقال 2×2 من سلسلة أعلام صاعد/هابط (حقيقة اليوم مقابل أمسه).
     * يعيد null إذا انتقالات أقل من 2 أو لا يمكن تقدير π (مقام صفر).
     */
    fun transitions(ups: List<Boolean>): Chain? {
        if (ups.size < 3) return null
        var uu = 0; var ud = 0; var du = 0; var dd = 0
        for (i in 1 until ups.size) {
            val from = ups[i - 1]; val to = ups[i]
            when {
                from && to -> uu++
                from && !to -> ud++
                !from && to -> du++
                else -> dd++
            }
        }
        if (uu + ud + du + dd < 2) return null
        val pUU = if (uu + ud > 0) uu.toDouble() / (uu + ud) else null
        val pUD = if (uu + ud > 0) ud.toDouble() / (uu + ud) else null
        val pDU = if (du + dd > 0) du.toDouble() / (du + dd) else null
        val pDD = if (du + dd > 0) dd.toDouble() / (du + dd) else null
        // [P20-FIX agent12]: التوزيع الثابت πU = p(D→U)/(p(D→U)+p(U→D)) — نسبة الاحتمالات لا العدّادات
        // (كان du/(du+ud) يساوي πU فقط عند تساوي مجاميع الصفوف — [T,T,F,T,F] كان يعطي 0.33 بدل 0.6)
        val pUDv = ud.toDouble() / (uu + ud)
        val pDUv = if (du + dd > 0) du.toDouble() / (du + dd) else 0.0
        val denom = (du + ud).toDouble()
        if (denom <= 0.0) return null
        return Chain(pUU, pUD, pDU, pDD, pDUv / (pDUv + pUDv))
    }
}

// ═══════════════ 13) NelsonMath: قواعد التحكم ═══════════════

object NelsonMath {

    data class Nelson(
        val beyond3Sigma: List<Int>,   // قاعدة 1: نقطة خارج ±3σ
        val run9SameSide: List<Int>,   // قاعدة 2: 9 نقاط متتالية بنفس جهة الوسط
        val trend6: List<Int>          // قاعدة 3: 6 نقاط متصاعدة أو متناقصة توالياً
    )

    /**
     * ثلاث قواعد نيلسون مبسطة على سلسلة قياس يومية (نهاية فهرس كل انتهاك).
     * يعيد null إذا n<9 أو σ=0.
     */
    fun violations(series: List<Double>): Nelson? {
        if (series.size < 9 || !r15Finite(series)) return null
        val m = r15Mean(series)
        val s = r15Std(series, m)
        if (s <= 1e-12) return null
        val r1 = series.withIndex().filter { abs(it.value - m) > 3.0 * s }.map { it.index }

        val r2 = ArrayList<Int>()
        var run = 1
        var side = 0
        // [P20-FIX agent12]: الحلقة من 0 — كانت تبدأ من 1 فالنقطة الأولى لا تدخل أي سلسلة أبداً
        // وسلسلة 9 نقاط تبدأ من أول العينة لا تُكتشف أبداً (كانت تحتاج 10)
        for (i in 0 until series.size) {
            val s2 = when {
                series[i] > m -> 1
                series[i] < m -> -1
                else -> 0
            }
            if (s2 != 0 && s2 == side) { run++; if (run >= 9) r2.add(i) } else { side = s2; run = 1 }
        }

        val r3 = ArrayList<Int>()
        var up = 1
        var down = 1
        for (i in 1 until series.size) {
            up = if (series[i] > series[i - 1]) up + 1 else 1
            down = if (series[i] < series[i - 1]) down + 1 else 1
            if (up >= 6 || down >= 6) r3.add(i)
        }
        return Nelson(r1, r2, r3)
    }
}

// ═══════════════ 14) OeeMath: الكفاءة الإجمالية المركّبة ═══════════════

object OeeMath {

    data class Oee(
        val fillRate: Double,     // توفر الصنف (نسبة الطلبات الملباة)
        val sellThrough: Double,  // تصريف المخزون
        val marginPct: Double,    // الهامش كنسبة 0..1
        val oee: Double           // حاصل الضرب 0..1
    )

    /**
     * نمط OEE الصناعي المُكيَّف للتجارة: توفر×أداء×جودة → تعبئة×تصريف×هامش.
     * يعيد null إذا أي مكوّن خارج 0..1 أو غير منتهٍ.
     */
    fun composite(fillRate: Double, sellThrough: Double, marginPct: Double): Oee? {
        val parts = listOf(fillRate, sellThrough, marginPct)
        if (parts.any { !it.isFinite() || it < 0.0 || it > 1.0 }) return null
        return Oee(fillRate, sellThrough, marginPct, fillRate * sellThrough * marginPct)
    }
}

// ═══════════════ 15) ParetoMath: عتبة 80/20 ═══════════════

object ParetoMath {

    data class Pareto(
        val headCount: Int,     // عدد البنود التي تصنع ≥80% من الإجمالي
        val headShare: Double,  // حصتها الفعلية 0..1
        val total: Double
    )

    /**
     * قص پاريتو: يرتب تنازلياً ويعده تراكمياً حتى بلوغ 80%.
     * يعيد null إذا القائمة فارغة أو الإجمالي ≤ 0.
     */
    fun cut(values: List<Double>): Pareto? {
        if (values.isEmpty() || values.any { !it.isFinite() }) return null
        val total = values.sum()
        if (total <= 0.0) return null
        val desc = values.sortedDescending()
        var acc = 0.0
        var count = 0
        for (v in desc) {
            acc += v; count++
            if (acc / total >= 0.8) break
        }
        return Pareto(count, acc / total, total)
    }
}

// ═══════════════ 16) RecencyMath: تسجيل RFM ═══════════════

object RecencyMath {

    data class Rfm(
        val r: Int, val f: Int, val m: Int,   // كل بُعد 1..5
        val composite: Double                  // 0..100 بوزن 0.2/0.3/0.5
    )

    /** نقاط بُعد واحد 1..5: عدد الحدود التي تجاوزتها القيمة + 1 (حدود تصاعدية). */
    private fun score(value: Double, ascendingCuts: List<Double>): Int =
        ascendingCuts.count { value > it } + 1

    /**
     * RFM بحدود صريحة يحددها المستدعي من توزيع بياناته (حتمية لا إعتماد على ترتيب عالمي):
     * الحدّية للأحدث: recencyDays أصغر أفضل فتُقاس عكسياً عبر (5 − نقاط الحدود الصاعدة).
     * يعيد null إذا أي مدخل سالب/غير منتهٍ أو حدود غير منتهية.
     */
    fun rfm(
        recencyDays: Double, frequency: Double, monetary: Double,
        fCuts: List<Double>, mCuts: List<Double>, rCuts: List<Double>
    ): Rfm? {
        val inputs = listOf(recencyDays, frequency, monetary)
        if (inputs.any { !it.isFinite() || it < 0.0 }) return null
        val cuts = fCuts + mCuts + rCuts
        if (cuts.any { !it.isFinite() }) return null
        val f = score(frequency, fCuts).coerceIn(1, 5)
        val m = score(monetary, mCuts).coerceIn(1, 5)
        // الأحدث (recency أصغر) ⇒ نقاط أعلى: نقاط الحدود الصاعدة تقيس القِدم
        val r = (6 - score(recencyDays, rCuts)).coerceIn(1, 5)
        val composite = (0.2 * r + 0.3 * f + 0.5 * m) / 5.0 * 100.0
        return Rfm(r, f, m, composite)
    }
}

// ═══════════════ 17) TheilMath: ميل ثيل-سن ═══════════════

object TheilMath {

    data class Theil(
        val slope: Double,       // وسيط ميول الأزواج
        val intercept: Double    // وسيط (y − slope·x)
    )

    /**
     * تقدير ثيل-سن المتين للاتجاه (x = 0..n−1): وسيط كل ميول الأزواج،
     * ثم التقاطع كوسيط البواقي. لا تتأثر بالشواذ كما OLS.
     * يعيد null إذا n<2.
     */
    fun fit(y: List<Double>): Theil? {
        val n = y.size
        if (n < 2 || y.any { !it.isFinite() }) return null
        val slopes = ArrayList<Double>(n * (n - 1) / 2)
        for (i in 0 until n) for (j in i + 1 until n) {
            slopes.add((y[j] - y[i]) / (j - i).toDouble())
        }
        val slope = slopes.sorted().let { s ->
            if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
        }
        val residuals = y.withIndex().map { (i, v) -> v - slope * i }.sorted()
        val intercept = if (residuals.size % 2 == 1) residuals[residuals.size / 2]
        else (residuals[residuals.size / 2 - 1] + residuals[residuals.size / 2]) / 2.0
        return Theil(slope, intercept)
    }
}

// ═══════════════ 18) UtestMath: مان-ويتني ═══════════════

object UtestMath {

    data class UTest(
        val u: Double,          // إحصاءة U (أصغر U1,U2)
        val z: Double,          // التقريب الطبيعي
        val verdict: String     // SAME / DIFFERENT عند |z|≥1.96
    )

    /**
     * مقارنة عينتين مستقلتين بمان-ويتني مع تصحيح الرتب المكررة.
     * يعيد null إذا أي عينة فارغة أو كلاهما ثابتة القيمة (σ=0).
     */
    fun mannWhitney(a: List<Double>, b: List<Double>): UTest? {
        if (a.isEmpty() || b.isEmpty()) return null
        if (!(r15Finite(a) && r15Finite(b))) return null
        val all = a + b
        val n1 = a.size.toDouble()
        val n2 = b.size.toDouble()
        val n = all.size.toDouble()
        // رتب مع معالجة التعادل (متوسط الرتبة)
        val sortedIdx = all.withIndex().sortedBy { it.value }
        val ranks = DoubleArray(all.size)
        var i = 0
        while (i < sortedIdx.size) {
            var j = i
            while (j + 1 < sortedIdx.size && sortedIdx[j + 1].value == sortedIdx[i].value) j++
            val avgRank = ((i + 1) + (j + 1)) / 2.0
            for (k in i..j) ranks[sortedIdx[k].index] = avgRank
            i = j + 1
        }
        val r1 = (0 until a.size).sumOf { ranks[it] }
        val u1 = r1 - n1 * (n1 + 1) / 2.0
        val u2 = n1 * n2 - u1
        val u = min(u1, u2)
        val mu = n1 * n2 / 2.0
        // تصحيح التعادل
        var tieTerm = 0.0
        var k = 0
        while (k < sortedIdx.size) {
            var j = k
            while (j + 1 < sortedIdx.size && sortedIdx[j + 1].value == sortedIdx[k].value) j++
            val t = (j - k + 1).toDouble()
            tieTerm += t * t * t - t
            k = j + 1
        }
        val sigma = sqrt((n1 * n2 / 12.0) * ((n + 1) - tieTerm / (n * (n - 1))))
        if (sigma <= 1e-12) return null
        val z = (u - mu) / sigma
        return UTest(u, z, if (abs(z) >= 1.96) "DIFFERENT" else "SAME")
    }
}

// ═══════════════ 19) VaRMath: القيمة المعرضة للخطر ═══════════════

object VaRMath {

    data class Var(
        val level: Double,       // مستوى الثقة 0.90/0.95/0.99
        val varValue: Double,    // أسوأ خسارة يومية عند المستوى (قيمة سالبة = خسارة)
        val cvar: Double         // متوسط الخسائر عند تجاوز الحد (أسوأ)
    )

    /**
     * VaR تاريخي + CVaR (القصور المتوقع) على صافي الأرباح اليومية.
     * المئين هنا قريب-الرتبة (تجريبي) لا خطي — لأن الاستقراء الخطي بين خسارة
     * شاذة وأول يوم ربح يعطي "أسوأ خسارة" موجبة لا معنى لها عند الحدود المتقطعة.
     * يعيد null إذا n<10 أو مستوى خارج (0.5,1) أو مدخلات غير منتهية.
     */
    fun historical(dailyNet: List<Double>, level: Double = 0.95): Var? {
        if (dailyNet.size < 10 || !r15Finite(dailyNet)) return null
        if (!level.isFinite() || level <= 0.5 || level >= 1.0) return null
        val sorted = dailyNet.sorted()
        // 1e-9 يحمي من تمثيل 0.05 ثنائياً (0.05000...0027 × 100 = 5.0000...0028 → ceil يعطي 6!)
        val idx = (kotlin.math.ceil((1.0 - level) * sorted.size - 1e-9).toInt() - 1).coerceIn(0, sorted.size - 1)
        val v = sorted[idx]
        val tail = sorted.filter { it <= v }
        val cvar = if (tail.isEmpty()) v else tail.sum() / tail.size
        return Var(level, v, cvar)
    }
}

// ═══════════════ 20) ZipfMath: تركّز الرأس ═══════════════

object ZipfMath {

    data class Zipf(
        val headCount: Int,       // k = ceil(n × 20%)
        val headShare: Double,    // حصة الرأس الفعلية 0..1
        val expectedShare: Double // حصة الرأس المتوقعة بقانون زيبف Σ1..k(1/i)/H_n
    )

    /**
     * قانون زيبف: يقارن حصة أعلى 20% من الأصناف بالمكسب المتوقع
     * تحت توزيع زيبف المثالي لنفس n. حصة أعلى بكثير من المتوقع = رأس مفرط التركّز.
     * يعيد null إذا القائمة فارغة أو الإجمالي ≤ 0.
     */
    fun headShare(values: List<Double>): Zipf? {
        if (values.isEmpty() || values.any { !it.isFinite() }) return null
        val total = values.sum()
        if (total <= 0.0) return null
        val n = values.size
        val k = ceil(n * 0.2).toInt().coerceIn(1, n)
        val head = values.sortedDescending().take(k).sum() / total
        var h = 0.0
        for (i in 1..n) h += 1.0 / i
        var hk = 0.0
        for (i in 1..k) hk += 1.0 / i
        return Zipf(k, head, hk / h)
    }
}
