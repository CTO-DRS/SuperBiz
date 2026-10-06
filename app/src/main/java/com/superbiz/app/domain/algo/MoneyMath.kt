package com.superbiz.app.domain.algo

import kotlin.math.pow

/**
 * — MoneyMath: خوارزميات مالية نقية للأعمال الحقيقية.
 *
 * كل دالة بلا حالة وبلا Android — قابلة للاختبار على JVM، وكلها مغطاة في MoneyMathTest.
 * تقريب المال: أقرب هللة للأعلى (half-up على الموجب) بنقطة واحدة موحدة round2().
*/

/**
 * [P6-M3 إصلاح]: التقريب المالي الموحد — تفويض مباشر إلى المساعد القانوني util.Money.round2
 * (BigDecimal HALF_UP على القيمة المخزّنة نفسها). كانت هنا ‎round(v*100)/100‎ بعرف kotlin.math.round
 * (ربط الأنصاف للزوجي، وضرب الفلواط في 100 يُدخل خطأ تمثيل) فاختلفت عن الكانوني عند أنصاف
 * الهللات السالبة (مثل ‎-0.125‎: كانت ‎-0.12‎ نحو الصفر وصارت ‎-0.13‎ بعيداً عنه — العرف المحاسبي).
 * كل مستدعي هذا الملف (DebtPlanP4/EarlyPay/TopDebtors والميزانيات في VMs) يصير كانونياً تلقائياً.
 */
fun round2(v: Double): Double = com.superbiz.app.util.Money.round2(v)

/** هل المبلغان متساويان ضمن هالفة هللة؟ (يقضي على ضجيج الفاصلة العائمة) */
fun moneyEquals(a: Double, b: Double): Boolean = kotlin.math.abs(a - b) <= 0.005

// ───────────────── 1) الضريبة (VAT) ─────────────────

/**
 * فك الإجمالي الشامل الضريبة إلى (صافي، ضريبة):
 * net = gross / (1 + rate/100) ثم vat = gross - net — قرشاً بقرش بلا فروق تجميع.
 * مثال: 115 برمز 15% → (100.0, 15.0).
 */
fun splitVat(gross: Double, ratePct: Double): Pair<Double, Double> {
    require(gross >= 0) { "gross >= 0" }
    require(ratePct >= 0) { "rate >= 0" }
    val net = round2(gross / (1.0 + ratePct / 100.0))
    return net to round2(gross - net)
}

/** إضافة الضريبة على الصافي → (إجمالي، ضريبة) بنفس تقريب الفواتير */
fun addVat(net: Double, ratePct: Double): Pair<Double, Double> {
    require(net >= 0) { "net >= 0" }
    require(ratePct >= 0) { "rate >= 0" }
    val vat = round2(net * ratePct / 100.0)
    return round2(net + vat) to vat
}

// ───────────────── 2) الهوامش والتسعير ─────────────────

/**
 * هامش الربح كنسبة من سعر البيع: (price-cost)/price × 100.
 * price <= 0 غير مقبول (سعر صفري يعني بيع مجاني بلا معنى هامشي).
 */
fun marginPct(cost: Double, price: Double): Double {
    require(price > 0) { "price > 0" }
    require(cost >= 0) { "cost >= 0" }
    return round2((price - cost) / price * 100.0)
}

/** العلامة كنسبة من التكلفة: (price-cost)/cost × 100 (cost > 0) */
fun markupPct(cost: Double, price: Double): Double {
    require(cost > 0) { "cost > 0" }
    require(price >= 0) { "price >= 0" }
    return round2((price - cost) / cost * 100.0)
}

/** السعر المطلوب لتحقيق هامش مستهدف من البيع: cost / (1 - margin/100) */
fun priceForMargin(cost: Double, targetMarginPct: Double): Double {
    require(cost >= 0) { "cost >= 0" }
    require(targetMarginPct in 0.0..<100.0) { "targetMargin 0..99.99" }
    return round2(cost / (1.0 - targetMarginPct / 100.0))
}

/**
 * خصم الكميات المتدرّج: tiers = (من كمية، نسبة %) — يُختار أعلى حد تنطبق عليه الكمية.
 * يعيد (الصافي بعد الخصم، نسبة الخصم المطبقة). tiers غير مرتب يُرتَّب داخلياً.
 */
fun tierDiscount(qty: Double, unitPrice: Double, tiers: List<Pair<Double, Double>>): Pair<Double, Double> {
    require(qty >= 0 && unitPrice >= 0) { "qty/price >= 0" }
    require(tiers.all { it.second in 0.0..100.0 }) { "pct 0..100" }
    val applied = tiers.filter { qty >= it.first }.maxByOrNull { it.second }?.second ?: 0.0
    val net = round2(qty * unitPrice * (1.0 - applied / 100.0))
    return net to applied
}

// ───────────────── 3) التأخير والغرامات ─────────────────

/**
 * غرامة تأخير يومية بسقف إجمالي:
 * fee = principal × dailyPct/100 × daysLate بحد أقصى principal × capPct/100.
 * daysLate <= 0 → 0. مثال: 1000 بـ 0.5%/يوم لـ 30 يوماً وسقف 10% → min(150, 100) = 100.
 */
fun lateFee(principal: Double, daysLate: Int, dailyPct: Double, capPct: Double): Double {
    require(principal >= 0 && dailyPct >= 0 && capPct >= 0) { "values >= 0" }
    if (principal <= 0 || daysLate <= 0 || dailyPct <= 0) return 0.0
    val raw = principal * dailyPct / 100.0 * daysLate
    val cap = principal * capPct / 100.0
    return round2(if (cap > 0) minOf(raw, cap) else raw)
}

/** النمو المركب: present × (1 + rate/100)^periods */
fun compoundGrowth(present: Double, ratePctPerPeriod: Double, periods: Int): Double {
    require(present >= 0 && periods >= 0) { "present/periods >= 0" }
    return round2(present * (1.0 + ratePctPerPeriod / 100.0).pow(periods))
}

// ───────────────── 4) القروض والأقساط ─────────────────

/**
 * قسط القرض الثابت (PMT) بفائدة سنوية موزعة شهرياً.
 * annualPct = 0 → تقسيم بسيط principal/months.
 */
fun loanPayment(principal: Double, annualPct: Double, months: Int): Double {
    require(principal > 0 && months > 0) { "principal/months > 0" }
    require(annualPct >= 0) { "annual >= 0" }
    val r = annualPct / 100.0 / 12.0
    if (r <= 1e-12) return round2(principal / months)
    return round2(principal * r / (1.0 - (1.0 + r).pow(-months)))
}

data class AmortRow(val seq: Int, val payment: Double, val interest: Double, val principalPart: Double, val remaining: Double)

/**
 * جدول إطفاء القرض: كل شهر (فائدة على المتبقي + أصل) مع ضبط القسط الأخير
 * ليغلق الرصيد بالضبط — مجموع principalPart = principal بلا كسور ضائعة.
 */
fun amortize(principal: Double, annualPct: Double, months: Int): List<AmortRow> {
    require(principal > 0 && months > 0) { "principal/months > 0" }
    val pay = loanPayment(principal, annualPct, months)
    val r = annualPct / 100.0 / 12.0
    var bal = principal
    val out = ArrayList<AmortRow>(months)
    for (i in 1..months) {
        val interest = round2(bal * r)
        var princ = round2(if (i == months) bal else pay - interest)
        if (princ < 0) princ = 0.0
        bal = round2(bal - princ)
        if (i == months) bal = 0.0 // القسط الأخير يغلق الجدول
        out.add(AmortRow(i, round2(interest + princ), interest, princ, bal))
    }
    return out
}

// ───────────────── 5) التعادل والتوزيع ─────────────────

/**
 * نقطة التعادل بوحدات مبيعة: fixed / (price - varCost).
 * يعيد -1.0 إذا كان السعر لا يغطي التكلفة المتغيرة (لا تعادل ممكن)
 * و0.0 إذا لا مصاريف ثابتة (كل وحدة ربحية فوراً).
 */
fun breakEvenUnits(fixedCost: Double, unitPrice: Double, unitVarCost: Double): Double {
    require(fixedCost >= 0 && unitPrice >= 0 && unitVarCost >= 0) { "values >= 0" }
    val contribution = unitPrice - unitVarCost
    if (fixedCost <= 1e-9) return 0.0
    if (contribution <= 1e-9) return -1.0
    return kotlin.math.ceil(fixedCost / contribution)
}

/** المتوسط المرجّح: Σ(x·w)/Σw — أوزان صفرية/سالبة تُرفض؛ مجموع أوزان صفري يعيد 0 */
fun weightedAverage(pairs: List<Pair<Double, Double>>): Double {
    if (pairs.isEmpty()) return 0.0
    require(pairs.all { it.second >= 0 }) { "weights >= 0" }
    val wSum = pairs.sumOf { it.second }
    if (wSum <= 1e-12) return 0.0
    return round2(pairs.sumOf { it.first * it.second } / wSum)
}

data class ChangeBreakdown(val amount: Double, val pieces: List<Pair<Double, Int>>) {
    val totalGiven: Double get() = pieces.sumOf { it.first * it.second }
}

/**
 * آلة حاسبة للفكّ النقدي عند الصندوق: يعيد توزيع المبلغ على الفئات المتاحة
 * بجشع (أكبر فئة أولاً). amount سالب أو فئات غير مرتبة يُعالج بأمان.
 * فئة 0.5/0.25 مدعومة (أنصاف وأرباع عملات).
 */
fun changeBreakdown(amount: Double, denominations: List<Double>): ChangeBreakdown {
    require(amount >= 0) { "amount >= 0" }
    require(denominations.all { it > 0 }) { "denominations > 0" }
    if (amount <= 0.004 || denominations.isEmpty()) return ChangeBreakdown(round2(amount), emptyList())
    var left = round2(amount)
    val out = ArrayList<Pair<Double, Int>>()
    for (d in denominations.sortedDescending()) {
        if (left <= 0.004) break
        val count = (left / d).toInt()
        if (count >= 1) {
            out.add(d to count)
            left = round2(left - count * d)
        }
    }
    // ما تبقى دون أصغر فئة يعيد معه كسراً دقيقاً بدل إخفائه
    return if (left > 0.004) ChangeBreakdown(round2(amount), out + (left to 1))
    else ChangeBreakdown(round2(amount), out)
}
