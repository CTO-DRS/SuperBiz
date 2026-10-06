package com.superbiz.app.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.superbiz.app.AppGraph
import com.superbiz.app.domain.algo.CashMath
import com.superbiz.app.domain.algo.CreditMath
import com.superbiz.app.domain.algo.CustomerMath
import com.superbiz.app.domain.algo.RetentionMath
import com.superbiz.app.domain.algo.GrowthMath
import com.superbiz.app.domain.algo.OpsMath
import com.superbiz.app.domain.algo.R9Adapters
import com.superbiz.app.domain.algo.RevenueMath
import com.superbiz.app.domain.algo.StockMath
import com.superbiz.app.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * — VM الرؤى الذكية للموجة R9: يحسب 20 ميزة جديدة
 * من بيانات حقيقية عبر R9Smart/R9Adapters، بعقد الصدق نفسه
 * بلا بيانات → قوائم فارغة/null تُخفى في الواجهة، لا أرقام مزيّفة.
*/
class R9InsightsVM(app: Application) : AndroidViewModel(app) {

    private val g = AppGraph.from(app)

    // ═══════════════ الرئيسية ═══════════════

    data class HomeInsights(
        val scenarios: List<CashMath.Scenario> = emptyList(),   // B6 سيولة تحت الضغط
        val reserve: Double = 0.0,                              // B7 احتياطي موسمي
        val loyaltyIndex: Int? = null,                          // B18 مؤشر ولاء
        val trendSignal: Int = 0,                               // B2 إشارة تقاطع (+1/−1/0)
        val retention: RetentionMath.Retention? = null,          // B17 منحنى احتفاظ
    )

    private val _home = MutableStateFlow(HomeInsights())
    val home: StateFlow<HomeInsights> = _home

    // ═══════════════ المخزون ═══════════════

    data class SafetyRow(val productId: Long, val name: String, val safety: Double, val onHand: Double, val gap: Double)
    data class CrostonRow(val productId: Long, val name: String, val forecast: Double, val saleDays: Int)
    data class AgingUI(val name: String, val ageDays: Int, val capital: Double, val band: String)

    data class InventoryInsights(
        val safety: List<SafetyRow> = emptyList(),      // B10 مخزون الأمان
        val slow: List<CrostonRow> = emptyList(),       // B11 كروستون للبطيء
        val aging: List<AgingUI> = emptyList(),         // B12 تقادم الدفعات
        val agingCapital: Double = 0.0,
    )

    private val _inventory = MutableStateFlow(InventoryInsights())
    val inventory: StateFlow<InventoryInsights> = _inventory

    // ═══════════════ التقارير ═══════════════

    data class OptimalRow(val name: String, val current: Double, val optimal: Double)
    data class BundleUI(val names: String, val price: Double, val listSum: Double)
    data class CatBalanceUI(val category: String, val stockShare: Double, val salesShare: Double, val verdict: String)

    data class ReportInsights(
        val optimalPrices: List<OptimalRow> = emptyList(),   // B14 السعر الأمثل
        val bundle: BundleUI? = null,                        // B15 سعر الحزمة
        val varLoss: Double? = null,                         // B16 ربح معرض للخطر
        val accuracyGrade: Int? = null,                      // B3 دقة التنبؤ
        val catBalance: List<CatBalanceUI> = emptyList(),    // B13 توازن الفئات
        val nextMonth: Double? = null,                       // B1 Holt للشهر القادم
        val shifts: List<Pair<Int, Double>> = emptyList(),   // B20 خطة ساعات العمل
    )

    private val _reports = MutableStateFlow(ReportInsights())
    val reports: StateFlow<ReportInsights> = _reports

    // ═══════════════ الذمم ═══════════════

    data class SweepUI(val count: Int, val usedCash: Double, val remaining: Double, val numbers: List<String>)
    data class CreditUI(val partyId: Long, val name: String, val score: Int, val limit: Double, val tier: String)
    data class ActionRow(val partyId: Long, val name: String, val amount: Double, val action: String)

    data class DebtsInsights(
        val sweep: SweepUI? = null,                 // B5 المسح الذكي
        val credits: List<CreditUI> = emptyList(),  // B8+B9 سلوك وحدود
        val actions: List<ActionRow> = emptyList(), // B19 الإجراء التالي
    )

    private val _debts = MutableStateFlow(DebtsInsights())
    val debts: StateFlow<DebtsInsights> = _debts

    // ═══════════════ الشيكات ═══════════════

    /** تغطية المقبوضات الشيكية للمستحقات القادمة (ميزة 20). */
    data class Coverage(val incoming: Double, val outgoing: Double, val ratioPct: Int)

    private val _checks = MutableStateFlow<Coverage?>(null)
    val checkCoverage: StateFlow<Coverage?> = _checks

    // ═══════════════ المصروفات ═══════════════

    /** توقع مصروفات بقية الشهر (B1 Holt على اليومي). */
    data class ExpenseOutlook(val projectedRest: Double, val monthSoFar: Double)

    private val _expenses = MutableStateFlow<ExpenseOutlook?>(null)
    val expenseOutlook: StateFlow<ExpenseOutlook?> = _expenses

    /** زر التحديث اليدوي — يُعيد الحساب كاملاً */
    private val refreshKey = MutableStateFlow(0)
    fun refresh() { refreshKey.value++ }

    // [P36-M4-10]: إشارة اكتمال أول جولة حساب — تُمكّن البطاقات من رسم سكيلتون زجاجي
    // أثناء التحميل الأول بدل الغياب الصامت، وتبقى true بعدها فلا يومض السكيلتون عند
    // التحديث اليدوي. عقد الصدق بعد الجاهزية كما هو: بلا بيانات ⇐ لا بطاقة.
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    init {
        viewModelScope.launch {
            loadAll()
            // [P36-M4-10]: أول جولة اكتملت — السكيلتون يستبدل بالمحتوى الحقيقي
            _ready.value = true
            // [P5-H6 إصلاح]: كان refreshKey ميتاً مزدوجاً — لا جامع يراقبه ولا مستدعٍ للدالة،
            // فبطاقات R9 لقطة جامدة لا تتجدد أبداً حتى مع زر التحديث اليدوي.
            // الآن كل زيادة تُعيد الحساب كاملاً (بعد اكتمال التحميل الأول، والتوالي
            // عبر نفس الكوروتين يمنع تزامن loadAll المزدوج، والدمج يمنع التكدس)
            refreshKey.drop(1).collect { loadAll() }
        }
    }

    // [P6-M18 إصلاح]: كان loadAll كله على الخيط الرئيسي (viewModelScope) — مسوحات
    // غير محدودة وحسابات ثقيلة (تجميع كل الفواتير، التقويم لكل فاتورة، أزواج الرفع)
    // كانت تجمّد الواجهة مع نمو البيانات. النقل الكامل إلى withContext(Dispatchers.Default)
    // بنمط R13/R14InsightsVM، والتحديثات على StateFlow آمنة من أي خيط — ولا يُحجب
    // الموديل الحي لأن الاستعلامات suspend تُنفَّذ على منفّذ Room نفسه.
    private suspend fun loadAll() = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val today = now / day
        try {
            // ═══ الرئيسية ═══
            try {
                val cash = g.reports.cashBalance()
                val m30 = now - 30 * day
                // [P33-P8] cashBalance قروش Long؛ مجاميع الدفعات/المصروفات DAO لم تُرحَّل بعد (ريال Double
                // فوق أعمدة قروش) — تُثبَّت إلى Long قروش ثم ريال عبر fromPiasters لحد CashMath الريالي
                val inflow = g.db.payments().receivedBetweenCash(m30, now).toLong()
                val outflow = (g.db.payments().paidOutBetween(m30, now) + g.db.expenses().sumBetween(m30, now)).toLong()
                _home.value = _home.value.copy(
                    scenarios = CashMath.runwayUnderStress(
                        Money.fromPiasters(cash), Money.fromPiasters(inflow), Money.fromPiasters(outflow),
                    ),
                    reserve = monthlyExpenses(6).let { CashMath.seasonalReserve(it.map { p -> Money.fromPiasters(p) }, 2) },
                )
            } catch (e: Exception) { Error(e) }

            try {
                val invs = g.db.invoices().all().first().filter { it.status < 3 }
                // مؤشر الولاء: المتكررون مقابل الكل (لا مرتجعات مسجّلة في هذا المخطط — موثق)
                val saleByParty = invs.filter { it.type == 0 }.groupBy { it.partyId }
                val repeat = saleByParty.count { it.value.size >= 2 }
                val oneTime = saleByParty.count { it.value.size == 1 }
                _home.value = _home.value.copy(loyaltyIndex = RetentionMath.npsProxy(repeat, oneTime, 0))
            } catch (e: Exception) { Error(e) }

            try {
                // [P33-P8] سلسلة المبيعات صارت قروش Long — ريال لحد smaCross الريالي
                val series = g.reports.salesExpensesSeries(30).map { Money.fromPiasters(it.second) }
                _home.value = _home.value.copy(trendSignal = GrowthMath.smaCross(series))
            } catch (e: Exception) { Error(e) }

            try {
                val m0 = monthIndex(now)
                val rows = g.db.invoices().allOnce().filter { it.type == 0 && it.status < 3 }
                    .map { it.partyId to monthIndex(it.date) }
                _home.value = _home.value.copy(retention = RetentionMath.cohortRetention(rows, m0))
            } catch (e: Exception) { Error(e) }

            // ═══ المخزون ═══
            try {
                val products = g.inventory.products().first().filter { !it.archived }
                val byId = products.associateBy { it.id }
                val lines = g.db.invoiceItems().saleLinesSince(now - 30 * day)
                val names = products.associate { it.id to it.name }

                // B10 مخزون الأمان من تذبذب الطلب اليومي الحقيقي
                val fromDay = (today - 29).toInt()
                val daySums = HashMap<Pair<Long, Int>, Double>()
                lines.forEach { l ->
                    val d = (l.date / day).toInt()
                    if (d >= fromDay) {
                        val k = l.productId to (d - fromDay).coerceIn(0, 29)
                        daySums[k] = (daySums[k] ?: 0.0) + l.qty
                    }
                }
                val perProductDays = daySums.entries.groupBy { it.key.first }.mapValues { (_, es) ->
                    val idx = es.associate { it.key.second to it.value }
                    List(30) { i -> idx[i] ?: 0.0 }
                }
                val safetyRows = perProductDays.mapNotNull { (pid, series) ->
                    val (avg, sd) = R9Adapters.dailyDemandStats(series)
                    if (avg <= 0) return@mapNotNull null
                    val p = byId[pid] ?: return@mapNotNull null
                    val safety = StockMath.safetyStock(avg, sd, 7.0)
                    val gap = p.stockQty - safety
                    if (gap >= 0) return@mapNotNull null
                    SafetyRow(pid, names[pid] ?: "", safety, p.stockQty, gap)
                }.sortedBy { it.gap }.take(5)
                _inventory.value = _inventory.value.copy(safety = safetyRows)

                // B11 كروستون للأصناف المتقطعة (≤6 أيام بيع في الشهر)
                val saleDaysByProduct = lines.groupBy { it.productId }.mapValues { (_, ls) -> ls.map { (it.date / day).toInt() }.distinct().sorted() }
                val slow = saleDaysByProduct.filter { it.value.size in 1..6 }.mapNotNull { (pid, days) ->
                    val p = byId[pid] ?: return@mapNotNull null
                    val daily = List(30) { i -> if (days.contains(fromDay + i)) 1.0 else 0.0 }  // حضور البيع
                    val cr = StockMath.crostonIntermittent(daily)
                    if (cr.forecastPerPeriod <= 0) return@mapNotNull null
                    CrostonRow(pid, names[pid] ?: "", cr.forecastPerPeriod, days.size)
                }.sortedByDescending { it.forecast }.take(5)
                _inventory.value = _inventory.value.copy(slow = slow)

                // B12 تقادم الدفعات: آخر إدخال شراء لكل منتج موعد تقديري لامتلاك الرصيد الحالي
                val lastPurchase = HashMap<Long, Int>()
                g.db.stockMoves().allMoves().filter { it.reason == "PURCHASE" }.forEach { m ->
                    val d = (m.date / day).toInt()
                    if (d > (lastPurchase[m.productId] ?: 0)) lastPurchase[m.productId] = d
                }
                val agingItems = products.filter { it.stockQty > 0 }.mapNotNull { p ->
                    val d = lastPurchase[p.id] ?: return@mapNotNull null
                    // [P33-P8] تكلفة المنتج قروش Long — ريال لحد StockMath.Batch الريالي
                    StockMath.Batch(p.id, p.stockQty, Money.fromPiasters(p.costPrice), d)
                }
                val aging = StockMath.batchAging(agingItems, today.toInt(), 30, 90)
                _inventory.value = _inventory.value.copy(
                    aging = aging.rows.filter { it.band != "OK" }.take(6)
                        .map { AgingUI(names[it.batchId] ?: "", it.ageDays, it.capital, it.band) },
                    agingCapital = aging.capitalAtRisk,
                )
            } catch (e: Exception) { Error(e) }

            // ═══ التقارير ═══
            try {
                val lines = g.db.invoiceItems().saleLinesSince(now - 60 * day)
                val products60 = g.inventory.products().first().filter { !it.archived }
                val names = products60.associate { it.id to it.name }
                val costs = products60.associate { it.id to it.costPrice }

                // B14 السعر الأمثل لكل منتج له 4+ أسعار مختلفة
                val optimal = lines.groupBy { it.productId }.mapNotNull { (pid, ls) ->
                    val prices = ls.map { it.unitPrice }.distinct()
                    if (prices.size < 4) return@mapNotNull null
                    val p = RevenueMath.optimalPrice(ls.map { Money.fromPiasters(it.unitPrice) to it.qty }) ?: return@mapNotNull null  // [P33-P8] السعر قروش والخوارزمية ريالية — تحويل عند الحد
                    OptimalRow(names[pid] ?: "", Money.fromPiasters(prices.sorted().last()), p)  // [P33-P8] عرض ريالي
                }.take(4)
                _reports.value = _reports.value.copy(optimalPrices = optimal)

                // B15 سعر حزمة من أكثر زوج يُشترى معاً
                val baskets = lines.groupBy { it.invoiceId }.values
                    .map { ls -> ls.map { it.productId }.distinct() }
                    .filter { it.size >= 2 }
                val pair = com.superbiz.app.domain.algo.InsightMath.bundlePairs(baskets).firstOrNull()
                if (pair != null) {
                    val a = names[pair.a]; val b = names[pair.b]
                    val pa = byPriceAndCost(lines, pair.a, costs); val pb = byPriceAndCost(lines, pair.b, costs)
                    if (a != null && b != null && pa != null && pb != null) {
                        val margin = (g.settings.snapshot().defaultTargetMargin) / 100.0
                        RevenueMath.bundlePrice(listOf(pa.second, pb.second), listOf(pa.first, pb.first), margin.coerceIn(0.0, 0.9))?.let {
                            _reports.value = _reports.value.copy(bundle = BundleUI("$a + $b", it, pa.first + pb.first))
                        }
                    }
                }

                // B16 الربح المعرض للخطر من أرباح 60 يوماً
                // [P33-P8] أرباح يومية قروش Long — ريال لحد profitAtRisk الريالي
                val prof = g.reports.salesExpensesSeries(60).map { Money.fromPiasters(it.second - it.third) }
                _reports.value = _reports.value.copy(varLoss = RevenueMath.profitAtRisk(prof))

                // B3 دقة التنبؤ الأسبوع الماضي
                // [P33-P8] سلسلة يومية قروش — ريال لحد ewmaBacktest الريالي
                val daily30 = g.reports.salesExpensesSeries(30).map { Money.fromPiasters(it.second) }
                R9Adapters.ewmaBacktest(daily30)?.let { (act, pred) ->
                    _reports.value = _reports.value.copy(accuracyGrade = GrowthMath.forecastAccuracy(act, pred)?.grade)
                }

                // B13 توازن الفئات
                val products = g.inventory.products().first().filter { !it.archived }
                val catOf = products.associate { it.id to it.category }
                // [P33-P8] قيمة المخزون قروش Long — ريال لتجانس الوحدة مع مبيعات الريال في categoryBalance
                val stockByCat = products.groupBy { it.category }.filterKeys { it.isNotBlank() }
                    .mapValues { (_, ps) -> Money.fromPiasters(ps.sumOf { it.stockValue }) }
                val salesByCat = lines.groupBy { catOf[it.productId] ?: "" }.filterKeys { it.isNotBlank() }
                    .mapValues { (_, ls) -> ls.sumOf { it.qty * it.unitPrice } }
                _reports.value = _reports.value.copy(
                    catBalance = StockMath.categoryBalance(stockByCat, salesByCat).take(5)
                        .map { CatBalanceUI(it.category, it.stockSharePct, it.salesSharePct, it.verdict) }
                )

                // B1 توقع الشهر القادم من 12 شهراً
                // [P33-P8] مجاميع 12 شهراً قروش Long — ريال لحد holtForecast فيبقى التوقع ريالاً
                val monthly = g.reports.monthlySalesTotals12().map { Money.fromPiasters(it.second) }
                _reports.value = _reports.value.copy(nextMonth = GrowthMath.holtForecast(monthly, horizon = 1).firstOrNull()?.takeIf { monthly.any { v -> v > 0.0 } })

                // B20 خطة ساعات العمل
                val hours = g.reports.saleHourCounts(30).map { it.toDouble() }.toDoubleArray()
                _reports.value = _reports.value.copy(shifts = R9Adapters.shiftsToSchedule(OpsMath.staffingPlan(hours, 40)))
            } catch (e: Exception) { Error(e) }

            // ═══ الذمم ═══
            try {
                val open = g.reports.openSaleInvoices()
                val cash = g.reports.cashBalance()
                val numbers = HashMap<Long, String>()
                g.db.invoices().allOnce().forEach { numbers[it.id] = it.number }
                // [P33-P8] النقد قروش Long — ريال لحد billSweep (الذرمة تعمل على ريالات OpenInvoice)
                val sweep = CashMath.billSweep(
                    Money.fromPiasters(cash),
                    open.map { com.superbiz.app.domain.algo.Bill(it.invoiceId, it.amountOpen) },
                )
                if (sweep.settledIds.isNotEmpty()) _debts.value = _debts.value.copy(
                    sweep = SweepUI(sweep.settledIds.size, sweep.usedCash, sweep.remainingCash,
                        sweep.settledIds.mapNotNull { numbers[it] })
                )
            } catch (e: Exception) { Error(e) }

            try {
                // كانت حلقة على أطراف الرصيد الموجب تستعلم فواتير ودفعات
                // كل طرف على حدة (2N+2 استعلام لكل تحديث، N حتى 25) — نفس صنف R14-F8/F9.
                // استعلامان شاملان + تجميع في الذاكرة الآن.
                val balances = g.db.journal().partyBalances().filter { it.balance > 0 }.take(25)
                val parties = g.db.parties().allOnce().associate { it.id to it.name }
                val allInvoices = g.db.invoices().allOnce()
                val payByPartyInv = g.db.payments().since(0)
                    .filter { it.invoiceId != null }
                    .groupBy { it.partyId to it.invoiceId!! }
                    .mapValues { (_, ps) -> ps.maxOf { it.date } }   // آخر دفعة لكل (طرف، فاتورة)
                val credits = balances.mapNotNull { b ->
                    val paidInvs = allInvoices.filter { it.partyId == b.pid && it.status == 2 }
                    if (paidInvs.isEmpty()) return@mapNotNull null
                    val pairs = paidInvs.mapNotNull { inv ->
                        payByPartyInv[b.pid to inv.id]?.let { inv.dueDate to it }
                    }
                    val (onT, lateT, avgD) = R9Adapters.paymentPairsToBehavior(pairs)
                    val score = CreditMath.behaviorScore(onT, lateT, 0, avgD)
                    // [P33-P8] متوسط الطلب من فواتير قروش Long — HALF_UP ثم ريال لحد creditLimit الريالي
                    val avgOrder = paidInvs.map { it.total }.average().takeIf { it > 0 }
                        ?.let { Money.fromPiasters(Math.round(it)) } ?: return@mapNotNull null
                    val cl = CreditMath.creditLimit(avgOrder, paidInvs.size, score)
                    CreditUI(b.pid, parties[b.pid] ?: "", score, cl.amount, cl.tier)
                }.sortedByDescending { it.score }.take(5)
                _debts.value = _debts.value.copy(credits = credits)
            } catch (e: Exception) { Error(e) }

            try {
                // نفس صنف N+1 في كتلة الإجراء التالي — استعلام شامل واحد
                // [P6-M25 إصلاح]: كانت الحلقة تستدعي SmartInsightsVM.customerIntelOf لكل طرف،
                // وداخلها saleInvoicesSince(90ي) الشامل يتكرر لكل طرف (مسح كامل × N في كل تحديث).
                // الدفعة الشاملة (فواتير البيع النشطة) تُجلب مرة واحدة قبل الحلقة ويُمرَّر
                // متوسط المتجر المحسوب منها، بنفس حسابات CustomerMath حرفياً حفظاً للدلالة.
                val balances = g.db.journal().partyBalances().filter { it.balance > 0 }.take(20)
                val parties = g.db.parties().allOnce().associate { it.id to it.name }
                val day = 86_400_000L
                val now2 = System.currentTimeMillis()
                val allSales = g.db.invoices().allOnce().filter { it.type == 0 && it.status < 3 }
                val byPartyAll = allSales.groupBy { it.partyId }
                val lines90 = allSales.filter { it.date >= now2 - 90 * day }
                // [P33-P8] متوسط المتجر قروش Long (HALF_UP) — كان قسمة Double ريالية
                val storeAvg90 = if (lines90.isEmpty()) null else Math.round(lines90.sumOf { it.total }.toDouble() / lines90.size)
                val rows = balances.map { b ->
                    val partyAll = byPartyAll[b.pid] ?: emptyList()
                    val invs = partyAll.filter { it.status < 2 }
                    val oldest = invs.filter { it.dueDate < now2 }.minOfOrNull { now2 - it.dueDate }
                    val daysOver = ((oldest ?: 0L) / day).toInt()
                    val intel = customerIntelFromBatch(partyAll, storeAvg90, now2)
                    val action = RetentionMath.nextBestAction(intel?.segment ?: "", intel?.churn ?: 0.5, daysOver)
                    // [P33-P8] رصيد الطرف قروش في حقل DAO لم يُرحَّل — تثبيت Long ثم ريال للعرض
                    ActionRow(b.pid, parties[b.pid] ?: "", Money.fromPiasters(b.balance.toLong()), action)
                }.sortedByDescending { it.amount }
                _debts.value = _debts.value.copy(actions = rows)
            } catch (e: Exception) { Error(e) }

            // ═══ الشيكات: تغطية 30 يوماً ═══
            try {
                val upcoming = g.db.checks().dueBetween(now, now + 30 * day)
                val incoming = upcoming.filter { it.direction == 0 }.sumOf { it.amount }
                val outgoing = upcoming.filter { it.direction == 1 }.sumOf { it.amount }
                // [P33-P8] مجاميع الشيكات قروش Long — مقارنة تامة وقسمة Double للنسبة، وريال للعرض
                if (outgoing > 0L || incoming > 0L) {
                    val ratio = if (outgoing <= 0L) 100 else (incoming.toDouble() / outgoing * 100.0).toInt().coerceIn(0, 100)
                    _checks.value = Coverage(Money.fromPiasters(incoming), Money.fromPiasters(outgoing), ratio)
                }
            } catch (e: Exception) { Error(e) }

            // ═══ المصروفات: توقع بقية الشهر ═══
            try {
                // [P33-P8] سلسلة المصروفات اليومية قروش — ريال لحد holtForecast فيبقى التوقع ريالاً
                val daily = g.reports.salesExpensesSeries(21).map { Money.fromPiasters(it.third) }
                // [P6-M23 إصلاح]: كان الأفق ثابتاً 15 يوماً أياً كان موقع اليوم من الشهر —
                // أوائل الشهر يتجاهل معظم الباقي وأواخره يتوقع أياماً من الشهر التالي.
                // الأفق الصادق = الأيام المتبقية فعلاً من الشهر (بما فيها اليوم،
                // نفس اصطلاح أيام pace في R10).
                val cal = Calendar.getInstance().apply { timeInMillis = now }
                val daysLeft = cal.getActualMaximum(Calendar.DAY_OF_MONTH) - cal.get(Calendar.DAY_OF_MONTH) + 1
                val forecasts = GrowthMath.holtForecast(daily, horizon = daysLeft)
                // [P33-P8] مجموع الشهر DAO لم يُرحَّل (ريال فوق عمود قروش) — تثبيت Long ثم ريال للعرض
                val monthSoFar = Money.fromPiasters(g.db.expenses().sumBetween(monthStart(now), now).toLong())
                _expenses.value = ExpenseOutlook(forecasts.sum(), monthSoFar)
            } catch (e: Exception) { Error(e) }
        } catch (e: Exception) {
            com.superbiz.app.core.ErrorCenter.warn("R9Insights", "loadAll: ${e.message}")
        }
    }

    /**
     * [P6-M25 إصلاح] ذكاء العميل من الدفعة المجلوبة مسبقاً — نسخة حرفية من حساب
     * SmartInsightsVM.customerIntelOf (نفس المعادلات وبنفس ترتيبها) لكن بلا أي
     * استعلام داخلها: فواتير الطرف تُمرَّر جاهزة ومتوسط المتجر 90ي مُمرَّر أيضاً،
     * فيبقى الحساب كما هو وتُلغى مسوحات saleInvoicesSince المتكررة. بلا فواتير → null.
     *
     * [P33-P8] مبالغ الفواتير قروش Long — المتوسطات بـHALF_UP، والحدود الدخالية الريالية
     * (CustomerMath.rfm/ltv وCustomerIntel) تُطعَم عبر Money.fromPiasters.
     */
    private fun customerIntelFromBatch(invs: List<com.superbiz.app.data.db.Invoice>, storeAvg90: Long?, now: Long): SmartInsightsVM.CustomerIntel? {
        if (invs.isEmpty()) return null
        val day = 86_400_000L
        val days = invs.map { (it.date / day).toInt() }.sorted()
        val gaps = days.zipWithNext { a, b -> (b - a).toDouble() }.sorted()
        val medianGap = if (gaps.isEmpty()) 0.0 else if (gaps.size % 2 == 1) gaps[gaps.size / 2] else (gaps[gaps.size / 2 - 1] + gaps[gaps.size / 2]) / 2.0
        // [P33-P8] متوسط الطلب قروش (HALF_UP) والهامش نسبة Double من فرق قروش تام (لا قسمة صحيحة)
        val totalSum = invs.sumOf { it.total }
        val costSum = invs.sumOf { it.costTotal }
        val avgOrder = Math.round(totalSum.toDouble() / invs.size)
        val marginPct = if (totalSum > 0L) ((totalSum - costSum).toDouble() / totalSum).coerceIn(0.0, 1.0) else 0.0
        val monthsActive = maxOf(1.0, (now - invs.minOf { it.date }) / (30.0 * day))
        val lastDays = ((now / day).toInt() - days.last())
        val ordersPerMonth = invs.size.toDouble() / monthsActive
        val storeAvg = storeAvg90 ?: avgOrder
        // [P33-P8] القيمة النقدية للعميل ونقاط RFM تُطعَم ريالاً (fromPiasters) لحدود R7Smart الريالية
        val rfm = CustomerMath.rfm(lastDays, invs.size, Money.fromPiasters(totalSum), monetaryAnchor = Money.fromPiasters(storeAvg))
        return SmartInsightsVM.CustomerIntel(
            ltv = CustomerMath.ltv(Money.fromPiasters(avgOrder), ordersPerMonth, monthsActive, marginPct),
            churn = CustomerMath.churnRisk(lastDays, medianGap, invs.size),
            segment = rfm.segment,
            etaDays = CustomerMath.nextPurchaseEta(days.last(), medianGap, (now / day).toInt()).daysFromToday,
            purchases = invs.size,
            avgOrder = Money.fromPiasters(avgOrder),
        )
    }

    // [P33-P8] تكلفة المنتج قروش Long في الخريطة — تُحوَّل ريالاً عند الحد (وحدة موحدة مع سعر السطر)
    private fun byPriceAndCost(lines: List<com.superbiz.app.data.db.SaleLineRow>, pid: Long, costs: Map<Long, Long>): Pair<Double, Double>? {
        val ls = lines.filter { it.productId == pid }
        val cost = costs[pid] ?: return null
        if (ls.isEmpty()) return null
        // [P33-P8] كلا القيمتين قروش تُعرض ريالاً — تحويل موحد عند الحد
        return Money.fromPiasters(ls.maxOf { it.unitPrice }) to Money.fromPiasters(cost)
    }

    // [P33-P8] تعيد قروش Long (كانت ريال Double) — مجموع DAO لم يُرحَّل بعد فيُثبَّت toLong
    private suspend fun monthlyExpenses(months: Int): List<Long> {
        val day = 86_400_000L
        val now = System.currentTimeMillis()
        return (months - 1 downTo 0).map { back ->
            val cal = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.MONTH, -back) }
            val from = monthStart(cal.timeInMillis)
            // الحد الأعلى = بداية الشهر التالي − 1ms بدل ‎+31 يوماً —
            // كان يلتقط 3-4 أيام من الشهر التالي فيضاعف حواف الشهور في سلسلة المصروفات
            val next = Calendar.getInstance().apply { timeInMillis = from; add(Calendar.MONTH, 1) }
            val to = next.timeInMillis - 1
            g.db.expenses().sumBetween(from, to).toLong()
        }
    }

    private fun monthIndex(ts: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        return c.get(Calendar.YEAR) * 12 + c.get(Calendar.MONTH)
    }

    private fun monthStart(ts: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        c.set(Calendar.DAY_OF_MONTH, 1); c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun Error(e: Exception) {
        com.superbiz.app.core.ErrorCenter.warn("R9Insights", "${e::class.simpleName}: ${e.message}")
    }
}
