package com.superbiz.app.domain.algo

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * — 20 خوارزمية ذكية جديدة عبر 11 كائناً نقياً حتمياً.
 *
 * العقود العامة (نفس انضباط R7Smart/R9Smart/R10Smart)
 * - دوال نقية: بلا وصول لقاعدة البيانات أو الشبكة أو الوقت الحقيقي.
 * - حتمية: نفس المدخل ⇒ نفس المخرج دائماً.
 * - بلا بيانات ⇒ null/قائمة فارغة، ولا تُخترع أرقام.
 * - كل خوارزمية موثقة بعقد صريح في docs/ALGORITHMS.md.
 *
 * قائمة الـ20
 * A1 TrendMath.linearTrend اتجاه خطي بميل OLS و R² وحكم
 * A2 TrendMath.momentumPct زخم النمو بين نصفين متتاليين
 * A3 LiquidityMath.burnAndRunway معدل الحرق اليومي وأيام الصمود
 * A4 LiquidityMath.cashGapCurve منحنى الفجوة النقدية لأحداث مجدولة
 * A5 TaxMath.vatPosition موقع ضريبة القيمة المضافة (مستحقة/مرتجعة)
 * A6 TaxMath.roundingDrift انحراف التقريب بين الأسطر والرأس
 * A7 PricingAuditMath.marginAudit تدقيق أرضية الهامش على الأسطر
 * A8 PricingAuditMath.discountLeak تسريب الخصم حسب الطرف
 * A9 InvoiceMath.aovTrend اتجاه متوسط قيمة الفاتورة أسبوعياً
 * A10 InvoiceMath.duplicateSuspects اشتباه فواتير مكررة (طرف+مبلغ+نافذة)
 * A11 LoyaltyMath.rfmSegment شريحة RFM بجدول قرار موثق
 * A12 LoyaltyMath.churnRisk خطر انصراف عميل صامت قيّم سابقاً
 * A13 ExpenseMath.budgetVsActual الموازنة الضمنية (متوسط أساس) مقابل الفعلي
 * A14 ExpenseMath.fixedVariableSplit تقسيم المصروفات ثابت/متغير بالتكرار الشهري
 * A15 ReplenishMath.reorderPlan خطة إعادة الطلب إلى حد الهدف
 * A16 ReplenishMath.gmroiByCategory المردود السنوي من الهامش لكل فئة GMROI
 * A17 CurrencyMath.fxExposure التعرض للعملات الأجنبية على المفتوح
 * A18 CheckMath.bounceStats إحصاء ارتجاع الشيكات لكل طرف
 * A19 InstallmentMath.delinquencyProfile شرائح تأخر الأقساط 1-15/16-30/31-60/60+
 * A20 RhythmMath.todayPace إيقاع اليوم مقابل معدل يوم الأسبوع
*/

// ═══════════════ 1) TrendMath — الاتجاه والزخم ═══════════════

object TrendMath {

    /**
     * A1 — اتجاه خطي بأقل المربعات على ترتيب النقاط: ميل + تقاطع + R².
     * عقد: أقل من 3 نقاط ⇒ null؛ التباين الكلي ≈0 (سلسلة ثابتة) ⇒ r2=1.0 وميل 0 وحكم STABLE؛
     * الحكم: |ميل×(ن−1)| > 2% من المتوسط ⇒ RISING/FALLING وإلا STABLE.
     */
    data class Trend(val slope: Double, val intercept: Double, val r2: Double, val verdict: String)

    fun linearTrend(values: List<Double>): Trend? {
        if (values.size < 3) return null
        val n = values.size
        val xs = (0 until n).map { it.toDouble() }
        val mx = xs.average(); val my = values.average()
        var num = 0.0; var den = 0.0
        for (i in 0 until n) {
            num += (xs[i] - mx) * (values[i] - my)
            den += (xs[i] - mx) * (xs[i] - mx)
        }
        val slope = if (den > 0.0) num / den else 0.0
        val intercept = my - slope * mx
        var ssTot = 0.0; var ssRes = 0.0
        for (i in 0 until n) {
            val fitted = intercept + slope * xs[i]
            ssTot += (values[i] - my) * (values[i] - my)
            ssRes += (values[i] - fitted) * (values[i] - fitted)
        }
        val r2 = if (ssTot <= 1e-12) {
            if (ssRes <= 1e-12) 1.0 else 0.0
        } else (1.0 - ssRes / ssTot).coerceIn(0.0, 1.0)
        val swing = abs(slope) * (n - 1)
        val verdict = when {
            my <= 0.0 -> "STABLE"   // بلا مرجع نسبة معقول
            swing > 0.02 * abs(my) -> if (slope > 0) "RISING" else "FALLING"
            else -> "STABLE"
        }
        return Trend(round2(slope), round2(intercept), round2(r2), verdict)
    }

    /**
     * A2 — زخم النمو: نسبة تغير متوسط النصف الأحدث مقابل الأقدم.
     * عقد: أقل من نقطتين ⇒ null؛ متوسط النصف الأقدم ≤ 1e-9 ⇒ null (لا نسبة من صفر).
     * النصف الغريب (عدد فردي) يذهب للنصف الأحدث.
     */
    fun momentumPct(series: List<Double>): Double? {
        if (series.size < 2) return null
        val cut = series.size / 2
        val older = series.subList(0, cut)
        val newer = series.subList(cut, series.size)
        if (older.isEmpty() || newer.isEmpty()) return null
        val m1 = older.average()
        val m2 = newer.average()
        if (m1 <= 1e-9) return null
        return round2((m2 - m1) / m1 * 100.0)
    }
}

// ═══════════════ 2) CashMath — الحرق والصمود والفجوة ═══════════════

object LiquidityMath {

    /**
     * A3 — معدل الحرق وأيام الصمود: netBurn = (خارج−داخل)/أيام النافذة.
     * عقد: حرق ≤0 ⇒ صمود null (فائض — لا صمود محدود)؛ حرق >0 والنقد ≤0 ⇒ صمود 0؛
     * الحكم: صمود <30 CRITICAL، <60 TIGHT، وإلا OK.
     */
    data class Runway(val dailyBurn: Double, val runwayDays: Double?, val verdict: String)

    fun burnAndRunway(cash: Double, inflowWindow: Double, outflowWindow: Double, windowDays: Int = 30): Runway {
        require(windowDays > 0) { "windowDays > 0" }
        val burn = round2((outflowWindow - inflowWindow) / windowDays)
        return if (burn <= 0.0) {
            Runway(burn, null, "SURPLUS")
        } else {
            val days = if (cash <= 0.0) 0.0 else round2(cash / burn)
            val verdict = when {
                days < 30.0 -> "CRITICAL"
                days < 60.0 -> "TIGHT"
                else -> "OK"
            }
            Runway(burn, days, verdict)
        }
    }

    /**
     * A4 — منحنى الفجوة النقدية: رصيد متحرك عبر أحداث مجدولة مرتبة زمنياً.
     * عقد: الأحداث تُرتَّب داخلياً باليوم ثم بالترتيب الوارد؛ الأيام نفسها تُجمَّع؛
     * يُعاد أدنى رصيد ويومه وعدد الأيام التي كان فيها الرصيد سالباً ورصيد النهاية.
     * قائمة فارغة ⇒ minBalance=endBalance=البداية وdeficitDays=0.
     */
    data class GapEvent(val epochDay: Long, val inflow: Double, val outflow: Double)
    data class GapReport(val minBalance: Double, val minDay: Long, val endBalance: Double, val deficitDays: Int)

    fun cashGapCurve(start: Double, events: List<GapEvent>): GapReport {
        if (events.isEmpty()) return GapReport(round2(start), 0L, round2(start), 0)
        val grouped = events.groupBy { it.epochDay }.toSortedMap()
        var running = start
        var minBal = start
        var minDay = grouped.firstKey()
        var deficit = 0
        for ((day, es) in grouped) {
            running = round2(running + es.sumOf { it.inflow } - es.sumOf { it.outflow })
            if (running < minBal) { minBal = running; minDay = day }
            if (running < 0.0) deficit += 1
        }
        return GapReport(round2(minBal), minDay, round2(running), deficit)
    }
}

// ═══════════════ 3) TaxMath — موقع الضريبة وانحراف التقريب ═══════════════

object TaxMath {

    /**
     * A5 — موقع الضريبة: net = ضريبة مخرجات − ضريبة مدخلات.
     * عقد: net > 0.005 ⇒ PAYABLE، net < −0.005 ⇒ REFUND، وإلا NEUTRAL؛
     * المعدل الفعلي = net ÷ صافي المخرجات ×100 وصافي المخرجات ≤0 ⇒ null.
     */
    data class VatPos(val net: Double, val position: String, val effectiveRatePct: Double?)

    fun vatPosition(outputTax: Double, inputTax: Double, outputNet: Double): VatPos {
        val net = round2(outputTax - inputTax)
        val position = when {
            net > 0.005 -> "PAYABLE"
            net < -0.005 -> "REFUND"
            else -> "NEUTRAL"
        }
        val eff = if (outputNet > 0.0) round2(net / outputNet * 100.0) else null
        return VatPos(net, position, eff)
    }

    /**
     * A6 — انحراف التقريب: لكل صف (متوقع من الأسطر، فعلي في الرأس) يُحسب الفرق.
     * عقد: قائمة فارغة ⇒ انحراف 0 وبدون أسوأ صف؛ worstIndex فهرس الصف بأكبر |فرق|؛
     * صفوف الفرق > 0.005 تُعدّ "منحرفة".
     */
    data class Drift(val totalDrift: Double, val skewedRows: Int, val worstIndex: Int?, val worstDrift: Double)

    fun roundingDrift(rows: List<Pair<Double, Double>>): Drift {
        if (rows.isEmpty()) return Drift(0.0, 0, null, 0.0)
        var total = 0.0
        var skewed = 0
        var worstIdx: Int? = null
        var worst = 0.0
        rows.forEachIndexed { i, (expected, actual) ->
            val d = round2(actual - expected)
            total += d
            if (abs(d) > 0.005) {
                skewed += 1
                if (abs(d) > abs(worst)) { worst = d; worstIdx = i }
            }
        }
        return Drift(round2(total), skewed, worstIdx, round2(worst))
    }
}

// ═══════════════ 4) PriceMath — أرضية الهامش وتسريب الخصم ═══════════════

object PricingAuditMath {

    /**
     * A7 — تدقيق أرضية الهامش: سطر تحت الأرض إذا هامشه < floorPct.
     * عقد: سعر ≤0 يُستبعد (لا هامش معرّف)؛ الحصة من عدد الأسطر الصالحة؛
     * offenders مرتبة تصاعدياً بالهامش (الأسوأ أولاً) بحد أقصى 5.
     */
    data class MarginLine(val name: String, val price: Double, val cost: Double)
    data class MarginRow(val name: String, val marginPct: Double)
    data class MarginAudit(val belowCount: Int, val validCount: Int, val belowSharePct: Double, val offenders: List<MarginRow>)

    fun marginAudit(items: List<MarginLine>, floorPct: Double): MarginAudit {
        val valid = items.filter { it.price > 0.0 }
        if (valid.isEmpty()) return MarginAudit(0, 0, 0.0, emptyList())
        val margins = valid.map { round2((it.price - it.cost) / it.price * 100.0) }
        val below = valid.mapIndexedNotNull { i, l -> if (margins[i] < floorPct) MarginRow(l.name, margins[i]) else null }
        return MarginAudit(
            belowCount = below.size,
            validCount = valid.size,
            belowSharePct = round2(below.size.toDouble() / valid.size * 100.0),
            offenders = below.sortedBy { it.marginPct }.take(5),
        )
    }

    /**
     * A8 — تسريب الخصم: نسبة الخصم من الإجمالي الكلي لكل طرف.
     * عقد: مجموع إجمالي ≤0 ⇒ null؛ الأطراف التي إجماليها < minGross تُستبعد من الترتيب؛
     * الناتج تنازلي بنسبة الخصم ثم بالاسم للحتمية بحد أقصى 5.
     */
    data class DiscountRow(val party: String, val gross: Double, val discount: Double)
    data class LeakRow(val party: String, val discountPct: Double, val discountAmount: Double)
    data class LeakReport(val overallPct: Double?, val rows: List<LeakRow>)

    fun discountLeak(rows: List<DiscountRow>, minGross: Double = 100.0, topK: Int = 5): LeakReport {
        val gross = rows.sumOf { it.gross }
        val disc = rows.sumOf { it.discount }
        val overall = if (gross > 0.0) round2(disc / gross * 100.0) else null
        val per = rows.filter { it.gross >= minGross && it.gross > 0.0 }
            .map { LeakRow(it.party, round2(it.discount / it.gross * 100.0), round2(it.discount)) }
            .sortedWith(compareByDescending<LeakRow> { it.discountPct }.thenBy { it.party })
            .take(max(0, topK))
        return LeakReport(overall, per)
    }
}

// ═══════════════ 5) InvoiceMath — متوسط الفاتورة والمكررات ═══════════════

object InvoiceMath {

    /**
     * A9 — اتجاه متوسط قيمة الفاتورة أسبوعياً: aov = المجموع ÷ العدد لكل أسبوع.
     * عقد: الأسابيع بعددها 0 تُهمل؛ أقل من أسبوعين صالحين ⇒ null؛
     * الاتجاه والزخم من TrendMath (تركيب لا تكرار)؛ latest آخر أسبوع صالح.
     */
    data class AovTrend(val latest: Double, val momentumPct: Double?, val direction: String)

    fun aovTrend(weeks: List<Pair<Double, Int>>): AovTrend? {
        val aovs = weeks.mapNotNull { (sum, count) ->
            if (count > 0 && sum >= 0.0) round2(sum / count) else null
        }
        if (aovs.size < 2) return null
        val dir = TrendMath.linearTrend(aovs)?.verdict ?: "STABLE"
        return AovTrend(aovs.last(), TrendMath.momentumPct(aovs), dir)
    }

    /**
     * A10 — اشتباه تكرار: نفس الطرف ومبلغ متطابق (ضمن هالفة) خلال نافذة أيام.
     * عقد: الصفوف تُرتَّب داخلياً (طرف، يوم، مبلغ)؛ كل صف يدخل في اقتران واحد كحد أقصى
     * مع أقرب سابق له (لا انفجار تربيعي على المجموعات)؛ الناتج بحد أقصى 5
     * مرتبة بفارق الأيام تصاعدياً ثم بالمبلغ.
     */
    data class TxRow(val partyId: Long, val partyName: String, val total: Double, val epochDay: Long)
    data class Suspect(val partyName: String, val total: Double, val dayGap: Long)

    fun duplicateSuspects(rows: List<TxRow>, windowDays: Int = 7): List<Suspect> {
        if (rows.isEmpty() || windowDays <= 0) return emptyList()
        val sorted = rows.sortedWith(compareBy({ it.partyId }, { it.epochDay }, { it.total }))
        val out = ArrayList<Suspect>()
        var i = 0
        while (i < sorted.size) {
            var j = i + 1
            var matched = false
            while (j < sorted.size && sorted[j].partyId == sorted[i].partyId && !matched) {
                val a = sorted[i]; val b = sorted[j]
                val gap = abs(b.epochDay - a.epochDay)
                if (gap <= windowDays && abs(b.total - a.total) <= 0.005) {
                    out += Suspect(b.partyName, round2(b.total), gap)
                    matched = true   // الصف المستهلك لا يشارك في اشتباه آخر
                    i = j
                }
                j += 1
            }
            if (!matched) i += 1
        }
        return out.sortedWith(compareBy<Suspect> { it.dayGap }.thenByDescending { it.total }).take(5)
    }
}

// ═══════════════ 6) CustomerMath — شرائح RFM وخطر الانصراف ═══════════════

object LoyaltyMath {

    /**
     * A11 — شريحة RFM بجدول قرار موثق (بلا أوزان مخفية):
     *  بلا طلبات (تكرار 0 أو قيمة 0)              ⇒ NO_ORDERS
     *  recency ≤ 7  وتكرار ≥ 5                    ⇒ CHAMPION
     *  recency ≤ 30 وتكرار ≥ 3                    ⇒ LOYAL
     *  recency ≤ 30                               ⇒ NEW
     *  recency ≤ 60                               ⇒ PROMISING
     *  recency ≤ 90                               ⇒ AT_RISK
     *  recency ≤ 180                              ⇒ SLEEPING
     *  وإلا                                        ⇒ LOST
     * recencyDays null (بلا طلبات مؤرخة) ⇒ NO_ORDERS.
     */
    fun rfmSegment(recencyDays: Int?, frequency: Int, monetary: Double): String {
        if (recencyDays == null || frequency <= 0 || monetary <= 0.0) return "NO_ORDERS"
        return when {
            recencyDays <= 7 && frequency >= 5 -> "CHAMPION"
            recencyDays <= 30 && frequency >= 3 -> "LOYAL"
            recencyDays <= 30 -> "NEW"
            recencyDays <= 60 -> "PROMISING"
            recencyDays <= 90 -> "AT_RISK"
            recencyDays <= 180 -> "SLEEPING"
            else -> "LOST"
        }
    }

    /**
     * A12 — خطر انصراف عميل صامت: صمت ≥ 1.5× فجوته الاعتيادية.
     * عقد: avgGapDays ≤0 ⇒ null (لا معيار صمت)؛ ratio < 1.5 ⇒ null (ليس معرضاً)؛
     * الدرجة = min(100, ratio×25) مقرّبة؛ الحكم: ≥75 HIGH، ≥50 MED، وإلا WATCH.
     */
    data class ChurnRisk(val silenceDays: Long, val score: Int, val verdict: String)

    fun churnRisk(lastOrderEpochDay: Long, todayEpochDay: Long, avgGapDays: Int): ChurnRisk? {
        if (avgGapDays <= 0) return null
        val silence = todayEpochDay - lastOrderEpochDay
        if (silence < 0) return null
        val ratio = silence.toDouble() / avgGapDays
        if (ratio < 1.5) return null
        val score = round(min(100.0, ratio * 25.0)).toInt()
        val verdict = when {
            score >= 75 -> "HIGH"
            score >= 50 -> "MED"
            else -> "WATCH"
        }
        return ChurnRisk(silence, score, verdict)
    }
}

// ═══════════════ 7) ExpenseMath — الموازنة الضمنية وثابت/متغير ═══════════════

object ExpenseMath {

    /**
     * A13 — الموازنة الضمنية: أساس = متوسط الأشهر السابقة لكل فئة، والفعلي لهذا الشهر.
     * عقد: فئات الأساس والفعلي تُوحَّد بمفاتيحها؛ أساس ≤0 مع فعلي >0 ⇒ صف "جديد بلا أساس"
     * بأساس 0؛ الترتيب: تجاوز تنازلياً ثم الاسم؛ over = فعلي > أساس×(1+tolerance/100).
     */
    data class BudgetRow(val category: String, val baseline: Double, val actual: Double, val overPct: Double?, val over: Boolean)
    data class BudgetReport(val rows: List<BudgetRow>, val totalBaseline: Double, val totalActual: Double)

    fun budgetVsActual(baselineByCat: Map<String, Double>, actualByCat: Map<String, Double>, tolerancePct: Double = 15.0): BudgetReport {
        val keys = (baselineByCat.keys + actualByCat.keys).filter { it.isNotBlank() }.sorted()
        val rows = keys.map { k ->
            val base = round2(baselineByCat[k] ?: 0.0)
            val act = round2(actualByCat[k] ?: 0.0)
            val overPct = if (base > 0.0) round2((act - base) / base * 100.0) else null
            val over = if (base > 0.0) act > base * (1.0 + tolerancePct / 100.0) else act > 0.0
            BudgetRow(k, base, act, overPct, over)
        }.sortedWith(compareByDescending<BudgetRow> { it.actual - it.baseline }.thenBy { it.category })
        return BudgetReport(rows, round2(baselineByCat.values.sum()), round2(actualByCat.values.sum()))
    }

    /**
     * A14 — ثابت/متغير: فئة "ثابتة" إذا ظهرت في ≥ recurringThreshold من الأشهر المميزة.
     * عقد: مدخل كل فئة (المبلغ الكلي، عدد الأشهر المميزة)؛ مجموع ≤0 ⇒ null؛
     * الحصص من المجموع الكلي مقرَّبة لخانتين وقائمة الفئات الثابتة مرتبة بالاسم.
     */
    data class Split(val fixedSharePct: Double, val variableSharePct: Double, val fixedCats: List<String>)

    fun fixedVariableSplit(amountsMonthsByCat: Map<String, Pair<Double, Int>>, recurringThreshold: Int = 3): Split? {
        val total = amountsMonthsByCat.values.sumOf { it.first }
        if (total <= 0.0) return null
        val fixedCats = amountsMonthsByCat.filter { it.value.second >= recurringThreshold }
            .keys.filter { it.isNotBlank() }.sorted()
        val fixed = fixedCats.sumOf { amountsMonthsByCat[it]?.first ?: 0.0 }
        val fixedPct = round2(fixed / total * 100.0)
        return Split(fixedPct, round2(100.0 - fixedPct), fixedCats)
    }
}

// ═══════════════ 8) StockMath — إعادة الطلب ومردود الفئات ═══════════════

object ReplenishMath {

    /**
     * A15 — خطة إعادة الطلب إلى حد الهدف: target = طلب مهلة البيع + مخزون أمان.
     * عقد: المدخلات السالبة تُعامل كصفر (تخفيف لا اختراع)؛ orderQty = max(0, سقف(target−stock))؛
     * الحكم: stock ≤0 مع هدف >0 ⇒ OUT، stock ≤ أمان ⇒ URGENT، stock < هدف ⇒ SOON، وإلا OK.
     */
    data class OrderPlan(val target: Double, val orderQty: Double, val verdict: String)

    fun reorderPlan(stockQty: Double, avgDaily: Double, leadDays: Double, safetyQty: Double): OrderPlan {
        val ad = avgDaily.coerceAtLeast(0.0)
        val ld = leadDays.coerceAtLeast(0.0)
        val sq = safetyQty.coerceAtLeast(0.0)
        val stock = stockQty.coerceAtLeast(0.0)
        val target = round2(ad * ld + sq)
        val orderQty = max(0.0, ceil(target - stock))
        val verdict = when {
            target <= 0.0 -> "OK"
            stock <= 0.0 -> "OUT"
            stock <= sq -> "URGENT"
            stock < target -> "SOON"
            else -> "OK"
        }
        return OrderPlan(target, round2(orderQty), verdict)
    }

    /**
     * A16 — المردود من الهامش لكل فئة GMROI: هامش الفترة ÷ قيمة مخزون الفئة، مع تحويل سنوي.
     * عقد: مخزون ≤0 يُستبعد؛ annualize = ×(12÷periodMonths)؛ periodMonths ≤0 ⇒ بلا تحويل (×1)؛
     * الترتيب تنازلي بالمردود ثم الاسم.
     */
    data class GmroiRow(val category: String, val margin: Double, val stockValue: Double, val gmroiAnnual: Double)

    fun gmroiByCategory(
        marginByCat: Map<String, Double>,
        stockValueByCat: Map<String, Double>,
        periodMonths: Double = 1.0,
    ): List<GmroiRow> {
        val factor = if (periodMonths > 0.0) 12.0 / periodMonths else 1.0
        return marginByCat.mapNotNull { (cat, margin) ->
            val stock = stockValueByCat[cat] ?: 0.0
            if (cat.isBlank() || stock <= 0.0) null
            else GmroiRow(cat, round2(margin), round2(stock), round2(margin / stock * factor))
        }.sortedWith(compareByDescending<GmroiRow> { it.gmroiAnnual }.thenBy { it.category })
    }
}

// ═══════════════ 9) CurrencyMath — التعرض للعملات ═══════════════

object CurrencyMath {

    /**
     * A17 — التعرض للعملات: المفتوح لكل عملة غير أساسية بمقابله بالأساسية.
     * عقد: العملة الأساسية تُستبعد؛ ما لا سعر معتمد له (rate ≤0) يُستبعد ولا يُخترع سعر؛
     * مجموع مقابلات ≤0 ⇒ null؛ الحكم: حصة أكبر عملة ≥50% ⇒ CONCENTRATED وإلا DIVERSIFIED.
     */
    data class FxRow(val currency: String, val open: Double, val baseEquivalent: Double, val sharePct: Double)
    data class FxExposure(val totalBase: Double, val rows: List<FxRow>, val verdict: String)

    fun fxExposure(openByCurrency: Map<String, Double>, rateToBase: Map<String, Double>, base: String): FxExposure? {
        val rows = openByCurrency.mapNotNull { (cur, open) ->
            if (cur == base || open <= 0.0) return@mapNotNull null
            val rate = rateToBase[cur] ?: return@mapNotNull null
            if (rate <= 0.0) return@mapNotNull null
            // [P20-FIX agent10]: rateToBase اتفاقية «وحدات العملة لكل 1 من الأساسية» (1 ريال = 0.2665
            // دولار حسب SeedDefaults، و setBaseCurrency new=rate/target صحيح فقط تحت هذه الاتجاهية) —
            // الضرب كان يُضخّم المعادل ~مربع المعدل (EGP ×167، YER ×441000). المعادل الأساسي = open ÷ rate.
            Triple(cur, open, round2(open / rate))
        }
        val total = rows.sumOf { it.third }
        if (rows.isEmpty() || total <= 0.0) return null
        val fxRows = rows.map { (cur, open, baseEq) ->
            FxRow(cur, round2(open), baseEq, round2(baseEq / total * 100.0))
        }.sortedByDescending { it.baseEquivalent }
        val verdict = if (fxRows.first().sharePct >= 50.0) "CONCENTRATED" else "DIVERSIFIED"
        return FxExposure(round2(total), fxRows, verdict)
    }
}

// ═══════════════ 10) CheckMath — ارتجاع الشيكات ═══════════════

object CheckMath {

    /**
     * A18 — إحصاء الارتجاع: نسبة مرتجعات الشيكات لكل طرف مقابل كل شيكاته.
     * عقد: الأطراف التي عدد شيكاتها < minIssued تُستبعد؛ مجموع صادر 0 ⇒ نسبة كلية null؛
     * الترتيب: النسبة تنازلياً ثم عدد الصادر تنازلياً ثم الاسم بحد أقصى 5.
     */
    data class BounceRow(val party: String, val issued: Int, val bounced: Int, val bouncePct: Double)
    data class BounceReport(val overallPct: Double?, val rows: List<BounceRow>)

    fun bounceStats(rows: List<Triple<String, Int, Int>>, minIssued: Int = 3, topK: Int = 5): BounceReport {
        val issued = rows.sumOf { it.second }
        val bounced = rows.sumOf { it.third }
        val overall = if (issued > 0) round2(bounced.toDouble() / issued * 100.0) else null
        val per = rows.mapNotNull { (party, iss, bnc) ->
            if (party.isBlank() || iss < minIssued || bnc < 0 || iss < 0) null
            else BounceRow(party, iss, bnc, round2(bnc.toDouble() / iss * 100.0))
        }.sortedWith(compareByDescending<BounceRow> { it.bouncePct }.thenByDescending { it.issued }.thenBy { it.party })
            .take(max(0, topK))
        return BounceReport(overall, per)
    }
}

// ═══════════════ 11) InstallmentMath — تأخر الأقساط ═══════════════

object InstallmentMath {

    /**
     * A19 — شرائح تأخر الأقساط: أقساط مفتوحة تجاوز موعد استحقاقها.
     * عقد: الشرائح 1-15/16-30/31-60/60+ من تاريخ الاستحقاق؛ القسط المستحق اليوم (فرق 0)
     * ليس متأخراً؛ المعدل = مفتوح متأخر ÷ كل مفتوح؛ بلا مفتوح ⇒ null.
     */
    data class LateBucket(val label: String, val amount: Double, val count: Int)
    data class Delinquency(val buckets: List<LateBucket>, val lateOpen: Double, val totalOpen: Double, val ratePct: Double)

    fun delinquencyProfile(openRows: List<Pair<Long, Double>>, todayEpochDay: Long): Delinquency? {
        val totalOpen = openRows.sumOf { it.second }
        if (openRows.isEmpty() || totalOpen <= 0.0) return null
        val labels = listOf("1-15", "16-30", "31-60", "60+")
        val amounts = DoubleArray(4)
        val counts = IntArray(4)
        var lateOpen = 0.0
        for ((due, open) in openRows) {
            if (open <= 0.0) continue
            val late = todayEpochDay - due
            if (late <= 0) continue          // غير مستحق أو مستحق اليوم
            lateOpen += open
            val idx = when {
                late <= 15 -> 0
                late <= 30 -> 1
                late <= 60 -> 2
                else -> 3
            }
            amounts[idx] += open
            counts[idx] += 1
        }
        return Delinquency(
            buckets = labels.mapIndexed { i, l -> LateBucket(l, round2(amounts[i]), counts[i]) }.filter { it.count > 0 },
            lateOpen = round2(lateOpen),
            totalOpen = round2(totalOpen),
            ratePct = round2(lateOpen / totalOpen * 100.0),
        )
    }
}

// ═══════════════ 12) RhythmMath — إيقاع اليوم ═══════════════

object RhythmMath {

    /**
     * A20 — إيقاع اليوم: مبيعات اليوم مقابل المعدل الاعتيادي ليوم الأسبوع نفسه
     * مضروباً بنسبة تقدم اليوم. عقد: weekdayAvg ≤0 أو تقدم خارج (0..100) ⇒ null؛
     * pacePct = اليوم ÷ المتوقع ×100؛ الحكم: ≥115 AHEAD، ≥85 ON_TREND، ≥50 SLOW، وإلا CRITICAL.
     */
    data class Pace(val expectedSoFar: Double, val pacePct: Int, val verdict: String)

    fun todayPace(todayTotal: Double, weekdayAvg: Double, dayProgressPct: Double): Pace? {
        if (weekdayAvg <= 0.0) return null
        val prog = dayProgressPct
        if (prog <= 0.0 || prog > 100.0) return null
        val expected = round2(weekdayAvg * prog / 100.0)
        if (expected <= 0.004) return null
        val pacePct = round(todayTotal / expected * 100.0).toInt()
        val verdict = when {
            pacePct >= 115 -> "AHEAD"
            pacePct >= 85 -> "ON_TREND"
            pacePct >= 50 -> "SLOW"
            else -> "CRITICAL"
        }
        return Pace(expected, pacePct, verdict)
    }
}
