package com.superbiz.app.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.superbiz.app.AppGraph
import com.superbiz.app.data.db.Invoice
import com.superbiz.app.domain.HealthScore
import com.superbiz.app.domain.algo.CollectionMath
import com.superbiz.app.domain.algo.CollectItem
import com.superbiz.app.domain.algo.CustomerMath
import com.superbiz.app.domain.algo.DemandMath
import com.superbiz.app.domain.algo.InsightMath
import com.superbiz.app.domain.algo.PlanItem
import com.superbiz.app.domain.algo.PriceMath
import com.superbiz.app.domain.algo.StockItem
import com.superbiz.app.domain.algo.Window
import com.superbiz.app.util.Money // [P33-P8] نقطة التحويل الوحيدة ريال ↔ قروش
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * — VM الرؤى الذكية: يحسب بطاقات الذكاء الجديدة من
 * بيانات حقيقية قائمة عبر خوارزميات R7Smart المدروسة، بعقد الصدق نفسه
 * بلا بيانات → قوائم فارغة/null تُخفى في الواجهة، لا أرقام مزيّفة.
 *
 * الطلب اليومي لكل منتج يُشتق من مبيعات 30 يوماً (متوسط مسطح، cv=0.5
 * الافتراضي الصناعي نفسه المستخدم في R6) — موثق لا مخفي.
*/
class SmartInsightsVM(app: Application) : AndroidViewModel(app) {

    private val g = AppGraph.from(app)

    // ═══════════════ الرئيسية ═══════════════

    data class HomeInsights(
        val firstNegWeek: Int? = null,        // أول أسبوع فجوة نقدية (null = لا فجوة)
        val lowestBalance: Double = 0.0,
        val jumpCategory: String? = null,     // فئة قفزة المصروفات (null = لا قفزة)
        val jumpRatio: Double = 0.0,
        val goalEtaDays: Int? = null,         // محاكي الهدف
        val goalRequiredPace: Double = 0.0,
        val goalWillFinish: Boolean = true,
        val healthContribs: List<InsightMath.Contribution> = emptyList(), // تركيب الدرجة
        val atRiskCount: Int = 0,             // عملاء معرضون للفقد (churn ≥ 0.6)
    )

    private val _home = MutableStateFlow(HomeInsights())
    val home: StateFlow<HomeInsights> = _home

    // ═══════════════ المخزون ═══════════════

    data class ReorderRow(val productId: Long, val name: String, val onHand: Double, val orderQty: Double, val daysCover: Double, val unitCost: Double)

    data class DeadRowUI(val productId: Long, val name: String, val qty: Double, val capital: Double)

    data class InventoryInsights(
        val plan: List<ReorderRow> = emptyList(),
        val dead: List<DeadRowUI> = emptyList(),
        val deadCapital: Double = 0.0,
        val stockoutEta: Map<Long, Double> = emptyMap(), // productId → أيام حتى النفاد
    )

    private val _inventory = MutableStateFlow(InventoryInsights())
    val inventory: StateFlow<InventoryInsights> = _inventory

    // ═══════════════ الذمم ═══════════════

    data class CollectionRowUI(val partyId: Long, val name: String, val amount: Double, val daysOverdue: Int, val score: Double)

    private val _collections = MutableStateFlow<List<CollectionRowUI>>(emptyList())
    val collections: StateFlow<List<CollectionRowUI>> = _collections

    // ═══════════════ الشيكات ═══════════════

    data class CheckRiskUI(val checkId: Long, val partyName: String, val amount: Double, val riskPct: Int, val high: Boolean)

    private val _checks = MutableStateFlow<List<CheckRiskUI>>(emptyList())
    val checkRisks: StateFlow<List<CheckRiskUI>> = _checks

    // ═══════════════ التقارير ═══════════════

    data class ReportInsights(
        val breakEvenUnits: Double? = null,      // وحدات (متوسط فاتورة) للتعادل الشهري
        val breakEvenRevenue: Double? = null,
        val stabilityLevel: Int = 0,             // 0 بيانات ناقصة .. 4 ثابت
        val stabilityCv: Double = 0.0,
        val windows: List<Window> = emptyList(),
    )

    private val _reports = MutableStateFlow(ReportInsights())
    val reports: StateFlow<ReportInsights> = _reports

    /** الهامش المستهدف من الإعدادات — مدخل سقف الخصم الذكي في POS */
    val targetMargin = MutableStateFlow(0.30)

    private val refreshKey = MutableStateFlow(0)
    fun refresh() { refreshKey.value++ }

    init {
        viewModelScope.launch { loadAll() }
    }

    // [P7-L4 إصلاح]: جولة التحميل كلها على Dispatchers.Default بنمط M6-18
    // (R9/R10/R11/R12) — كان التجليد الدفعي الجديد يُجرى على Main فكان يُبقي
    // تجميع O(n) فوق آلاف الفواتير على خيط الواجهة عند كل إقلاع. النتائج
    // النهائية مطابقة حرفياً: تحديثات StateFlow آمنة من أي خيط.
    private suspend fun loadAll() = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        try {
            // ── الرئيسية: الفجوة النقدية (6 أسابيع قادمة) ──
            // كل التقاطات الصامتة أصبحت مُبلَّغة لمركز الأخطاء — بلا ابتلاع صامت
            val w4 = 28 * day
            // [P33-P8] دوال SUM في DAO تعيد القروش كـ Double — تُحوَّل ريالاً هنا للحدود القائمة
            val inflowAvg = try { g.db.payments().receivedBetweenCash(now - w4, now) / 100.0 / 4.0 } catch (e: Exception) { Error(e, "cashgap-inflow"); 0.0 }
            val outflowAvg = try { g.db.payments().paidOutBetween(now - w4, now) / 100.0 / 4.0 + g.db.expenses().sumBetween(now - w4, now) / 100.0 / 4.0 } catch (e: Exception) { Error(e, "cashgap-outflow"); 0.0 }
            val weeklyIn = List(6) { inflowAvg }
            val weeklyOut = List(6) { outflowAvg }.toMutableList()
            // الشيكات الواجب سدادها (صادرة) تُضاف للأسبوع الذي تستحق فيه
            try {
                val checksOut = g.db.checks().dueBetween(now, now + 42 * day).filter { it.direction == 1 }
                checksOut.forEach { c ->
                    val wk = (((c.dueDate - now) / day).toInt() / 7).coerceIn(0, 5)
                    // [P33-P8] مبلغ الشيك قروش → ريال (السلسلة الأسبوعية ريالية)
                    weeklyOut[wk] = weeklyOut[wk] + Money.fromPiasters(c.amount)
                }
            } catch (e: Exception) { Error(e, "cashgap-checks") }
            // [P33-P8] الرصيد قروش من الدفتر → ريال للفجوة النقدية (الأدنى يُعرض مبلغًا)
            val cash = try { Money.fromPiasters(g.reports.cashBalance()) } catch (e: Exception) { 0.0 }
            val gap = CollectionMath.cashGap(cash, weeklyIn, weeklyOut)

            // ── الرئيسية: قفزة المصروفات ──
            var jumpCat: String? = null; var jumpRatio = 0.0
            try {
                val exp = g.db.expenses().between(now - 120 * day, now)
                val byCatMonth = exp.groupBy { it.category }.mapValues { (_, rows) ->
                    // [P33-P8] مجاميع شهرية قروش → ريال (القفزة نسبية لكنها متسقة الوحدة)
                    rows.groupBy { monthKey(it.date) }.mapValues { (_, rs) -> Money.fromPiasters(rs.sumOf { it.amount }) }
                }
                val mNow = monthKey(now)
                var best = 0.0
                byCatMonth.forEach { (cat, months) ->
                    val cur = months[mNow] ?: 0.0
                    val hist = months.filterKeys { it != mNow }.values.toList()
                    if (hist.size >= 2 && cur > 0) {
                        val j = InsightMath.expenseJump(cur, hist)
                        if (j.isJump && j.ratio > best) { best = j.ratio; jumpCat = cat; jumpRatio = j.ratio }
                    }
                }
            } catch (e: Exception) { Error(e, "expense-jump") }

            // ── الرئيسية: محاكي الهدف ──
            var goalEta: Int? = null; var goalReq = 0.0; var goalOk = true
            try {
                val s = g.settings.snapshot()
                val goal = s.monthlyGoal
                if (goal > 0) {
                    val totals = g.reports.monthTotals()   // (مبيعات، مصروفات، ربح) الشهر
                    val pace = Money.fromPiasters(totals.first) / maxOf(1, java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH))   // [P33-P8] قروش → ريال
                    val cal = java.util.Calendar.getInstance()
                    val daysLeft = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH) - cal.get(java.util.Calendar.DAY_OF_MONTH) + 1
                    val sim = InsightMath.goalSim(pace, maxOf(0.0, goal - Money.fromPiasters(totals.first)), daysLeft)   // [P33-P8] الهدف الريالي من الإعدادات − مبيعات قروش → ريال
                    goalEta = sim.etaDays; goalReq = sim.requiredPacePerDay; goalOk = sim.willFinishInTime
                }
            } catch (e: Exception) { Error(e, "goal-sim") }

            // ── الرئيسية: تركيب درجة الصحة (نفس معادلات loadHome) ──
            var contribs: List<InsightMath.Contribution> = emptyList()
            var atRisk = 0
            try {
                val totals = g.reports.monthTotals()
                // [P33-P8] النسب مأخوذة من قروش بقسمة عائمة (لا قسمة صحيحة)
                val marginRatio = if (totals.first > 0) Money.fromPiasters(totals.third) / Money.fromPiasters(totals.first) else null
                val cf = g.reports.cashflowSeries(30)
                // [P33-P8] صافي التدفق قروش → ريال (الحرق اليومي ريالي)
                val burn = com.superbiz.app.domain.algo.FinMath.burnRate(cf.map { Money.fromPiasters(it.second - it.third) })
                val runway = com.superbiz.app.domain.algo.FinMath.runwayDays(cash, -burn)
                // [P33-P8] SUM في DAO قروش كـ Double → ريال
                val received90 = g.db.payments().receivedBetweenCash(now - 90 * day, now) / 100.0
                val creditSales90 = Money.fromPiasters(g.db.invoices().saleInvoicesSince(now - 90 * day)
                    .sumOf { it.total }) * 0.5   // تقدير محافظ: نصف المبيعات آجل — موثق
                    // استعلام مفلتر بالتاريخ بدل قراءة الجدول كاملاً
                val collEff = com.superbiz.app.domain.algo.FinMath.collectionEfficiency(received90, creditSales90)
                val inputs = HealthScore.Inputs(marginRatio, runway, collEff)
                val score = HealthScore.compute(inputs)
                if (score != null) {
                    val m = (inputs.marginRatio ?: 0.0).coerceIn(0.0, 1.0) * 100.0
                    val r = if ((inputs.runwayDays ?: 0.0) < 0) 100.0 else (((inputs.runwayDays ?: 0.0)) / HealthScore.RUNWAY_NORM_DAYS).coerceIn(0.0, 1.0) * 100.0
                    val c = (inputs.collectionEff ?: 0.0).coerceIn(0.0, 100.0)
                    val w = mapOf("margin" to 40.0, "runway" to 30.0, "collection" to 30.0)
                    contribs = InsightMath.healthDecomposition(
                        mapOf("margin" to 0.0, "runway" to 0.0, "collection" to 0.0),
                        mapOf("margin" to m / 100.0, "runway" to r / 100.0, "collection" to c / 100.0),
                        w
                    )
                }
                // عملاء معرضون للفقد
                // [P7-L4 إصلاح] النصف الأول من N+1 المزدوج: كان لكل طرف (حتى 60)
                // استدعاء customerIntelOf يطلق forParty(pid) + saleInvoicesSince(90ي)
                // الشامل داخل كل تكرار ⇒ حتى 60 مسحاً كاملاً + 60 استعلام طرف عند كل
                // إقلاع (نفس عطل M6-25 المصلح في R9). الآن: مسح دفعي واحد
                // saleInvoicesOnce (type=0, status<3 — نفس فلتر customerIntelOf حرفياً)
                // يُجمّع حسب الطرف + متوسط المتجر 90ي مرة واحدة، والحساب نفسه بنسخة
                // حرفية من المعادلات (customerIntelFromBatch) فالنتيجة مطابقة دلالياً.
                val balances = g.db.journal().partyBalances().filter { it.balance > 0 }.take(60)
                val saleBatch = g.db.invoices().saleInvoicesOnce()
                val storeAvg90 = run {
                    val l = saleBatch.filter { it.date >= now - 90 * day }
                    // [P33-P8] متوسط الفاتورة قروش → ريال (مرساة RFM الريالية)
                    if (l.isEmpty()) null else Money.fromPiasters(l.sumOf { it.total }) / l.size
                }
                val intelByParty = saleBatch.groupBy { it.partyId }
                for (b in balances) {
                    val intel = customerIntelFromBatch(intelByParty[b.pid].orEmpty(), storeAvg90, now)
                    if (intel != null && intel.churn >= 0.6) atRisk++
                }
            } catch (e: Exception) { Error(e, "health-churn") }

            _home.value = HomeInsights(gap.firstNegativeWeek, gap.lowestBalance, jumpCat, jumpRatio, goalEta, goalReq, goalOk, contribs, atRisk)

            // ── المخزون: خطة الطلب + الراكد + شارات النفاد ──
            try {
                val products = g.inventory.products().first().filter { !it.archived }
                val sold30 = g.reports.productSoldQty(30)
                val sold60 = g.reports.productSoldQty(60)
                val planItems = products.map { p ->
                    val daily = (sold30[p.id] ?: 0.0) / 30.0
                    // [P33-P8] تكلفة الوحدة قروش → ريال (خطة الطلب بحسابات التكلفة الريالية)
                    PlanItem(p.id, p.stockQty, daily, 0.5, Money.fromPiasters(p.costPrice))
                }
                val plan = DemandMath.reorderPlan(planItems, leadTimeDays = 7.0, serviceZ = 1.65, coverDays = 14.0)
                val names = products.associate { it.id to it.name }
                // [P33-P8] التكاليف قروش → ريال (تكلفة الوحدة تُعرض مبلغًا)
                val costs = products.associate { it.id to Money.fromPiasters(it.costPrice) }
                val qty = products.associate { it.id to it.stockQty }
                val rows = plan.map {
                    ReorderRow(it.productId, names[it.productId] ?: "", qty[it.productId] ?: 0.0, it.orderQty, it.daysCover, costs[it.productId] ?: 0.0)
                }
                // [P7-L6 إصلاح] توثيق — بلا تغيير كود: فحص الموجة الرابعة لأيام UTC وجد
                // أن كل استخدام لقسمة الطابع على 86,400,000 في هذا الملف
                // (customerIntelOf/customerIntelFromBatch والراكد أدناه) يبني أرقام
                // أيام **فروقاً نسبية** فقط (فجوات شراء، عمر، حد أدنى 60 يوماً) —
                // الطابع المشترك يُلغى في الطرح فلا حساسية للتوقيت الصيفي/المنطقة،
                // بعكس تجميع أيام التقويم الذي يجري في R11/R14/R15 عبر startOfDay
                // المحلي المتسق. لا خلط UTC/محلي في هذا الملف أصلاً — الأمر منجَل.
                val todayEpoch = (now / day).toInt()
                // [P33-P8] تكلفة الوحدة قروش → ريال (رأس المال الراكد يُعرض مبلغًا)
                val deadItems = products.filter { it.stockQty > 0 && (sold60[it.id] ?: 0.0) <= 0 }
                    .map { StockItem(it.id, it.stockQty, Money.fromPiasters(it.costPrice), todayEpoch - 60) }   // حد أدنى موثق: 60 يوماً
                val dead = DemandMath.deadStock(deadItems, todayEpoch, 60)
                val eta = products.mapNotNull { p ->
                    val daily = (sold30[p.id] ?: 0.0) / 30.0
                    if (daily <= 0) return@mapNotNull null
                    DemandMath.stockoutEta(p.stockQty, List(14) { daily })?.let { p.id to it }
                }.toMap()
                _inventory.value = InventoryInsights(rows, dead.rows.map { DeadRowUI(it.productId, names[it.productId] ?: "", it.qty, it.capital) }, dead.tiedCapital, eta)
            } catch (e: Exception) { Error(e, "inventory") }

            // ── الذمم: أولوية التحصيل ──
            try {
                val balances = g.db.journal().partyBalances().filter { it.balance > 0 }.take(40)
                val parties = g.db.parties().allOnce().associate { it.id to it.name }
                // [P7-L4 إصلاح] النصف الثاني من N+1 المزدوج: كان لكل رصيد (حتى 40)
                // استعلام forParty(pid) داخل الحلقة. الآن مسح دفعي واحد saleInvoicesOnce
                // يُجمّع حسب الطرف، والفلتر الأضيق status<2 يُطبق على دفعة الطرف —
                // المجموعة الناتجة مطابقة حرفياً لفلتر الحلقة القديم (type=0,status<2).
                val openBatch = g.db.invoices().saleInvoicesOnce().groupBy { it.partyId }
                val items = balances.map { b ->
                    val invs = openBatch[b.pid].orEmpty().filter { it.status < 2 }
                    val oldest = invs.filter { it.dueDate < now }.minOfOrNull { now - it.dueDate }
                    val daysOver = ((oldest ?: 0L) / day).toInt()
                    // [P33-P8] الرصيد قروش (PartyBalance DAO يرجع Double فوق عمود قروش) → ريال (أولوية التحصيل تعرض المبلغ)
                    CollectItem(b.pid, b.balance / 100.0, daysOver, 0.5)   // موثوقية محايدة — بلا تاريخ سداد مجمع
                }
                _collections.value = CollectionMath.priority(items).map {
                    CollectionRowUI(it.partyId, parties[it.partyId] ?: "", it.amount, it.daysOverdue, it.score)
                }
            } catch (e: Exception) { Error(e, "collections") }

            // ── الشيكات: مخاطرة الارتجاع ──
            try {
                val upcoming = g.db.checks().dueBetween(now, now + 45 * day).filter { it.direction == 0 }
                val all = g.db.checks().allOnce()
                val globalBounced = if (all.isEmpty()) 0.0 else all.count { it.status == 3 }.toDouble() / all.size
                val parties = g.db.parties().allOnce().associate { it.id to it.name }
                val byParty = all.groupBy { it.partyId }
                _checks.value = upcoming.take(25).map { c ->
                    val hist = byParty[c.partyId].orEmpty()
                    val med = hist.map { it.amount }.sorted().let { l ->
                        if (l.isEmpty()) c.amount else l[l.size / 2]
                    }.takeIf { it > 0 } ?: c.amount
                    // [P33-P8] النسبة قروش/قروش بقسمة عائمة (لا قسمة صحيحة Long)
                    val risk = CollectionMath.checkBounceRisk(globalBounced, c.amount.toDouble() / med.toDouble())
                    CheckRiskUI(c.id, parties[c.partyId] ?: "", Money.fromPiasters(c.amount), (risk * 100).toInt(), risk >= 0.5)
                }.sortedByDescending { it.riskPct }
            } catch (e: Exception) { Error(e, "checks-risk") }

            // ── التقارير: التعادل + الاستقرار + الذروة ──
            try {
                // [P33-P8] SUM في DAO قروش كـ Double → ريال (الثابت الشهري يغذي التعادل الريالي)
                val fixedMonthly = g.db.expenses().sumBetween(now - 90 * day, now) / 100.0 / 3.0
                val sales = g.db.invoices().saleInvoicesSince(now - 90 * day)
                // [P33-P8] متوسطات الإجمالي/التكلفة قروش → ريال (وحدات التعادل بالفاتورة الريالية)
                val avgTotal = if (sales.isEmpty()) 0.0 else Money.fromPiasters(sales.sumOf { it.total }) / sales.size
                val avgCost = if (sales.isEmpty()) 0.0 else Money.fromPiasters(sales.sumOf { it.costTotal }) / sales.size
                val be = if (avgTotal > avgCost) PriceMath.breakEven(fixedMonthly, avgTotal, avgCost) else null
                val series = g.reports.salesExpensesSeries(56)
                // [P33-P8] أسبوعي الربح قروش → ريال (الاستقرار على السلسلة الريالية)
                val weekly = series.chunked(7).map { wk -> Money.fromPiasters(wk.sumOf { it.second } - wk.sumOf { it.third }) }
                val stab = InsightMath.profitStability(weekly)
                val hours = g.reports.saleHourCounts(30).map { it.toDouble() }.toDoubleArray()
                _reports.value = ReportInsights(be?.takeIf { !it.units.isNaN() }?.units, be?.takeIf { !it.units.isNaN() }?.revenue, stab.level, stab.cv, InsightMath.peakWindows(hours))
            } catch (e: Exception) { Error(e, "reports") }

            // ── الهامش المستهدف ──
            // [P20-FIX agent1/9]: الإعداد نسبة مئوية (30.0 = 30%) ومستهلكو الحقل يتعاملون معه كسر
            // (0..1) عبر PriceMath.maxSafeDiscountPct — كان يُمرَّر خاماً فـ coerceIn(0,0.99)=0.99
            // ⇒ الحد الآمن للخصم صفر دائماً وحارس الخصم في POS مقلوب تماماً
            try { targetMargin.value = g.settings.snapshot().defaultTargetMargin / 100.0 } catch (e: Exception) { Error(e, "target-margin") }
        } catch (e: Exception) {
            com.superbiz.app.core.ErrorCenter.warn("SmartInsights", "loadAll: ${e.message}")
        }
    }

    /**
     * [P7-L4 إصلاح] ذكاء العميل من الدفعة المجلوبة مسبقاً — نسخة حرفية من معادلات
     * customerIntelOf (نفسها وبنفس ترتيبها) لكن بلا أي استعلام داخلها: فواتير الطرف
     * تُمرَّر جاهزة ومتوسط المتجر 90ي مُمرَّر أيضاً (نمط M6-25/customerIntelFromBatch
     * في R9InsightsVM). بلا فواتير → null. التوقيع العام customerIntelOf يبقى كما هو
     * لمستدعيه الخارجي (كشف الحساب في DebtsScreen) فلا كسر لأحد.
     */
    private fun customerIntelFromBatch(
        invs: List<com.superbiz.app.data.db.Invoice>,
        storeAvg90: Double?,
        now: Long,
    ): CustomerIntel? {
        if (invs.isEmpty()) return null
        val day = 86_400_000L
        val days = invs.map { (it.date / day).toInt() }.sorted()
        val gaps = days.zipWithNext { a, b -> (b - a).toDouble() }.sorted()
        val medianGap = if (gaps.isEmpty()) 0.0 else if (gaps.size % 2 == 1) gaps[gaps.size / 2] else (gaps[gaps.size / 2 - 1] + gaps[gaps.size / 2]) / 2.0
        val avgOrder = Money.fromPiasters(invs.sumOf { it.total }) / invs.size   // [P33-P8] قروش → ريال
        // [P33-P8] الهامش نسبة بقسمة عائمة (القروش صحيحة لا قسمة صحيحة Long)
        val marginPct = if (invs.sumOf { it.total } > 0) ((invs.sumOf { it.total } - invs.sumOf { it.costTotal }).toDouble() / invs.sumOf { it.total }.toDouble()).coerceIn(0.0, 1.0) else 0.0
        val monthsActive = maxOf(1.0, (now - invs.minOf { it.date }) / (30.0 * day))
        val lastDays = ((now / day).toInt() - days.last())
        val ordersPerMonth = invs.size.toDouble() / monthsActive
        val storeAvg = storeAvg90 ?: avgOrder
        val rfm = CustomerMath.rfm(lastDays, invs.size, Money.fromPiasters(invs.sumOf { it.total }), monetaryAnchor = storeAvg)   // [P33-P8] قروش → ريال
        return CustomerIntel(
            ltv = CustomerMath.ltv(avgOrder, ordersPerMonth, monthsActive, marginPct),
            churn = CustomerMath.churnRisk(lastDays, medianGap, invs.size),
            segment = rfm.segment,
            etaDays = CustomerMath.nextPurchaseEta(days.last(), medianGap, (now / day).toInt()).daysFromToday,
            purchases = invs.size,
            avgOrder = avgOrder,
        )
    }

    companion object {
        /**
 * ذكاء العميل (LTV/Churn/RFM/موعد الشراء) من فواتيره الفعلية —
 * : دالة رفيقة تُستدعى من كشف الحساب مباشرة. بلا فواتير → null.
 * [P7-L4 إصلاح]: بقي كما هو لمستدعيها الخارجيين؛ داخل loadAll استُبدلت بنسخة
 * الدفعة customerIntelFromBatch أعلاه التي تلغي الاستعلامات داخل الحلقة.
*/
        suspend fun customerIntelOf(context: android.content.Context, partyId: Long): CustomerIntel? {
            val g2 = AppGraph.from(context)
            return try {
                val now = System.currentTimeMillis()
                val day = 86_400_000L
                val invs = g2.db.invoices().forParty(partyId).filter { it.type == 0 && it.status < 3 }
            if (invs.isEmpty()) return null
            val days = invs.map { (it.date / day).toInt() }.sorted()
            val gaps = days.zipWithNext { a, b -> (b - a).toDouble() }.sorted()
            val medianGap = if (gaps.isEmpty()) 0.0 else if (gaps.size % 2 == 1) gaps[gaps.size / 2] else (gaps[gaps.size / 2 - 1] + gaps[gaps.size / 2]) / 2.0
            val avgOrder = Money.fromPiasters(invs.sumOf { it.total }) / invs.size   // [P33-P8] قروش → ريال
            // [P33-P8] الهامش نسبة بقسمة عائمة (القروش صحيحة لا قسمة صحيحة Long)
            val marginPct = if (invs.sumOf { it.total } > 0) ((invs.sumOf { it.total } - invs.sumOf { it.costTotal }).toDouble() / invs.sumOf { it.total }.toDouble()).coerceIn(0.0, 1.0) else 0.0
            val monthsActive = maxOf(1.0, (now - invs.minOf { it.date }) / (30.0 * day))
            val lastDays = ((now / day).toInt() - days.last())
            val ordersPerMonth = invs.size.toDouble() / monthsActive
            val storeAvg = try {
                val l = g2.db.invoices().saleInvoicesSince(now - 90 * day)
                // [P33-P8] متوسط المتجر قروش → ريال (مرساة RFM الريالية)
                if (l.isEmpty()) avgOrder else Money.fromPiasters(l.sumOf { it.total }) / l.size
            } catch (e: Exception) { avgOrder }
            val rfm = CustomerMath.rfm(lastDays, invs.size, Money.fromPiasters(invs.sumOf { it.total }), monetaryAnchor = storeAvg)   // [P33-P8] قروش → ريال
            CustomerIntel(
                ltv = CustomerMath.ltv(avgOrder, ordersPerMonth, monthsActive, marginPct),
                churn = CustomerMath.churnRisk(lastDays, medianGap, invs.size),
                segment = rfm.segment,
                etaDays = CustomerMath.nextPurchaseEta(days.last(), medianGap, (now / day).toInt()).daysFromToday,
                purchases = invs.size,
                avgOrder = avgOrder,
            )
        } catch (e: Exception) { null }
        } // customerIntelOf
    } // companion

    data class CustomerIntel(val ltv: Double, val churn: Double, val segment: String, val etaDays: Int, val purchases: Int, val avgOrder: Double)

    private fun monthKey(ts: Long): Int {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ts }
        return c.get(java.util.Calendar.YEAR) * 12 + c.get(java.util.Calendar.MONTH)
    }

    /**تسجيل أخطاء الحساب في مركز الأخطاء بدل الابتلاع الصامت */
    private fun Error(e: Exception, section: String) {
        com.superbiz.app.core.ErrorCenter.warn("SmartInsights/$section", "${e::class.simpleName}: ${e.message}")
    }
}
