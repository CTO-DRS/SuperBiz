package com.superbiz.app.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.superbiz.app.AppGraph
import com.superbiz.app.domain.algo.BasketMath
import com.superbiz.app.domain.algo.FlowMath
import com.superbiz.app.domain.algo.GoalMath
import com.superbiz.app.domain.algo.MarginMath
import com.superbiz.app.domain.algo.PricingMath
import com.superbiz.app.domain.algo.QualityMath
import com.superbiz.app.domain.algo.RiskMath
import com.superbiz.app.domain.algo.SeasonMath
import com.superbiz.app.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * — VM الرؤى الذكية للموجة R10: يحسب 20 ميزة جديدة
 * من بيانات حقيقية عبر خوارزميات R10Smart، بعقد الصدق نفسه
 * - بلا بيانات ⇒ null/قوائم فارغة تُخفى في الواجهة، لا أرقام مزيّفة.
 * - كل قسم مستقل بـ try/catch يمرر عبر ErrorCenter ولا يُسقط بقية الأقسام.
 * - استعلامات DAO إضافية فقط — لا تغيير في مخطط قاعدة البيانات (v4).
*/
class R10InsightsVM(app: Application) : AndroidViewModel(app) {

    private val g = AppGraph.from(app)

    private val refreshKey = MutableStateFlow(0)
    fun refresh() { refreshKey.value++ }

    // ═══════════════ الرئيسية (B1..B5) ═══════════════

    data class HomeR10(
        val health: RiskMath.HealthScore? = null,          // B1 درجة صحة 0..100
        val concentration: RiskMath.Concentration? = null, // B2 تركّز العملاء
        val bestWeekday: SeasonMath.DayProfile? = null,    // B3 أفضل يوم أسبوع
        val pace: GoalMath.Pace? = null,                   // B4 وتيرة إنقاذ الهدف
        val expenseIqrCount: Int = 0,                      // B5 شواذ المصروفات IQR
        val expenseIqrUpper: Double? = null,
    )

    private val _home = MutableStateFlow(HomeR10())
    val home: StateFlow<HomeR10> = _home

    // ═══════════════ المخزون (B6..B9) ═══════════════

    data class MarkdownUI(val name: String, val current: Double, val suggested: Double, val ageDays: Int)
    data class DupUI(val a: String, val b: String, val score: Int)

    data class InventoryR10(
        val abcRows: List<MarginMath.AbcRow> = emptyList(), // B6 تصنيف ABC
        val efficiency: List<MarginMath.EfficiencyRow> = emptyList(), // B7 كفاءة رأس المال
        val markdown: List<MarkdownUI> = emptyList(),       // B8 سلّم التخفيض
        val dups: List<DupUI> = emptyList(),                // B9 أسماء متشابهة
    )

    private val _inventory = MutableStateFlow(InventoryR10())
    val inventory: StateFlow<InventoryR10> = _inventory

    // ═══════════════ التقارير (B10..B14) ═══════════════

    data class LiftUI(val aName: String, val bName: String, val lift: Double, val confidencePct: Double)
    data class NextUI(val fromName: String, val nextName: String, val count: Int)

    data class ReportsR10(
        val portfolio: MarginMath.Portfolio? = null,        // B10 هامش المحفظة + HHI
        val lift: List<LiftUI> = emptyList(),               // B11 أقوى أزواج الرفع
        val dso: FlowMath.DsoTrendResult? = null,           // B12 اتجاه فترة التحصيل
        val invoiceOutliers: Int = 0,                       // B13 فواتير قيم شاذة IQR
        val next: List<NextUI> = emptyList(),               // B14 «المنتج التالي»
    )

    private val _reports = MutableStateFlow(ReportsR10())
    val reports: StateFlow<ReportsR10> = _reports

    // ═══════════════ الذمم (B15..B17) ═══════════════

    data class DebtsR10(
        val buckets: List<FlowMath.Bucket> = emptyList(),   // B15 أعمار الذمم
        val forecast14: Double? = null,                     // B16 توقع تحصيل 14 يوماً
        val exposure: RiskMath.Concentration? = null,       // B17 تركّز المديونين
    )

    private val _debts = MutableStateFlow(DebtsR10())
    val debts: StateFlow<DebtsR10> = _debts

    // ═══════════════ الشيكات (B18..B19) ═══════════════

    data class ChecksR10(
        val ladder: List<RiskMath.WeekBucket> = emptyList(), // B18 سلّم الاستحقاق
        val bankConc: RiskMath.Concentration? = null,        // B19 تركّز البنوك
    )

    private val _checks = MutableStateFlow(ChecksR10())
    val checks: StateFlow<ChecksR10> = _checks

    // ═══════════════ المصروفات (B20) ═══════════════

    data class MadUI(val category: String, val amount: Double, val z: Double)

    data class ExpensesR10(val mad: List<MadUI> = emptyList()) // B20 شواذ MAD المنيعة

    private val _expenses = MutableStateFlow(ExpensesR10())
    val expenses: StateFlow<ExpensesR10> = _expenses

    // ═══════════════ التحميل ═══════════════

    // [P36-M4-10]: إشارة اكتمال أول جولة حساب — تُمكّن البطاقات من رسم سكيلتون زجاجي
    // أثناء التحميل الأول بدل الغياب الصامت، وتبقى true بعدها فلا يومض السكيلتون عند
    // التحديث اليدوي. عقد الصدق بعد الجاهزية كما هو: بلا بيانات ⇐ null/قوائم فارغة.
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    init {
        viewModelScope.launch {
            loadAll()
            // [P36-M4-10]: أول جولة اكتملت — السكيلتون يستبدل بالمحتوى الحقيقي
            _ready.value = true
            refreshKey.collect { if (it > 0) loadAll() }
        }
    }

    // [P6-M18 إصلاح]: كان loadAll كله على الخيط الرئيسي — من بينه dupScore O(n²)
    // لمقارنة أسماء المنتجات (200×200 زوجاً) وتجميع 90 يوماً من الأسطر، كلها
    // تجمّد الواجهة. النقل الكامل إلى withContext(Dispatchers.Default) بنمط
    // R13/R14InsightsVM؛ والتحديثات على StateFlow آمنة من أي خيط.
    private suspend fun loadAll() = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val today = now / day
        try {
            val saleInvoices = g.db.invoices().allOnce().filter { it.type == 0 && it.status < 3 }
            val products = g.inventory.products().first().filter { !it.archived }
            val parties = g.db.parties().allOnce()
            val partyName = parties.associate { it.id to it.name }
            val prodName = products.associate { it.id to it.name }
            val prodCost = products.associate { it.id to it.costPrice }
            // خريطة أسماء أحادية — التفريغ باسم المنتج كان يدمج منتجين
            // يتشاركان الاسم فيمدّ إيراد أحدهما ويُسقط الآخر (نفس فئة R13-B12)؛
            // الاسم المكرر يُميَّز بلاحقة #id فيبقى لكل منتج صفه
            val prodNameUnique = products.groupBy { it.name }.flatMap { (nm, ps) ->
                if (ps.size == 1) listOf(ps[0].id to nm) else ps.map { p -> p.id to "$nm #${p.id}" }
            }.toMap()

            // ═══ الرئيسية ═══
            try {
                // B1 درجة الصحة: سيولة + اتجاه هامش + مديونية + مصروفات
                // [P5-H5 إصلاح]: كانت الدرجة تُخترع على قاعدة فارغة — outflow=0 ⇒ liquidity=2.0
                // وrev=0 ⇒ هامش 0 و88 «بصحة جيدة» مختلقة، مخالفةً عقد الصدق (بلا بيانات ⇒ null).
                // الدرجة الآن لا تُحسب إلا إذا كانت كل مكوناتها الأربعة قابلة للحساب فعلاً:
                // السيولة تحتاج outflow>0، والهامش يحتاج rev30>0، والنسبتان تحتاجان monthlyRev>0.
                // خلاف ذلك health=null فتُخفى البطاقة كما في بقية البطاقات (st.health?.let في R10Cards).
                val cash = g.reports.cashBalance()
                val m30 = now - 30 * day
                // [P33-P8] مجاميع الدفعات/المصروفات DAO لم تُرحّل بعد (ريال Double فوق أعمدة قروش) —
                // تُثبّت إلى Long قروش؛ والنسب تُبنى من ريالات متجانسة عبر fromPiasters (لا خلط وحدات)
                val outflow = (g.db.payments().paidOutBetween(m30, now) + g.db.expenses().sumBetween(m30, now)).toLong()
                val openRec = saleInvoices.filter { it.open > 0 }.sumOf { it.open }
                val rev30 = saleInvoices.filter { it.date in m30..now }.sumOf { it.total }
                val profit30 = saleInvoices.filter { it.date in m30..now }.sumOf { it.total - it.costTotal }
                val prev30a = now - 60 * day; val prev30b = now - 30 * day
                val revPrev = saleInvoices.filter { it.date in prev30a..prev30b }.sumOf { it.total }
                val profitPrev = saleInvoices.filter { it.date in prev30a..prev30b }.sumOf { it.total - it.costTotal }
                // [P33-P8] الهامش نسبة Double من قروش — قسمة صريحة لا قسمة صحيحة
                val marginNow = if (rev30 > 0L) profit30.toDouble() / rev30 * 100.0 else 0.0
                val marginPrev = if (revPrev > 0L) profitPrev.toDouble() / revPrev * 100.0 else marginNow
                val monthlyRev = g.reports.monthTotals().first
                _home.value = _home.value.copy(
                    health = if (outflow > 0L && rev30 > 0L && monthlyRev > 0L) {
                        RiskMath.healthScore(
                            liquidityRatio = Money.fromPiasters(cash + openRec) / Money.fromPiasters(outflow),
                            marginTrendPct = marginNow - marginPrev,
                            debtRatio = openRec.toDouble() / monthlyRev,
                            expenseRatio = Money.fromPiasters(g.db.expenses().sumBetween(monthStart(now), now).toLong()) / Money.fromPiasters(monthlyRev),
                        )
                    } else null,
                )
            } catch (e: Exception) { Error(e, "health") }

            try {
                // B2 تركّز العملاء (90 يوماً)
                val since = now - 90 * day
                val byParty = saleInvoices.filter { it.date >= since && it.total > 0 }
                    .groupBy { it.partyId }.mapKeys { (pid, _) -> partyName[pid] ?: "" }
                    .filterKeys { it.isNotBlank() }
                    // [P33-P8] إيراد لكل طرف قروش Long — ريال لحد customerConcentration الريالي
                    .mapValues { (_, invs) -> Money.fromPiasters(invs.sumOf { it.total }) }
                _home.value = _home.value.copy(concentration = RiskMath.customerConcentration(byParty, 3))
            } catch (e: Exception) { Error(e, "concentration") }

            try {
                // B3 أفضل يوم أسبوع (آخر 56 يوماً — أيام ISO: الاثنين=1..الأحد=7)
                val since = now - 56 * day
                val pts = saleInvoices.filter { it.date >= since }
                    // [P33-P8] مبيعات قروش Long — ريال لحد weekdayProfile الريالي
                    .map { isoWeekday(it.date) to Money.fromPiasters(it.total) }
                _home.value = _home.value.copy(bestWeekday = SeasonMath.weekdayProfile(pts).firstOrNull())
            } catch (e: Exception) { Error(e, "weekday") }

            try {
                // B4 وتيرة إنقاذ هدف الشهر (قدرة = متوسط البيع اليومي آخر 30 يوماً)
                val s = g.settings.snapshot()
                if (s.monthlyGoal > 0) {
                    val mtd = g.reports.monthTotals().first
                    val cal = Calendar.getInstance()
                    val daysLeft = cal.getActualMaximum(Calendar.DAY_OF_MONTH) - cal.get(Calendar.DAY_OF_MONTH) + 1
                    // [P33-P8] الهدف إعداد ريال والمُنجز قروش Long — يُقاسان ريالاً عبر fromPiasters
                    val capacity = Money.fromPiasters(saleInvoices.filter { it.date >= now - 30 * day }.sumOf { it.total }) / 30.0
                    _home.value = _home.value.copy(
                        pace = GoalMath.requiredPace((s.monthlyGoal - Money.fromPiasters(mtd)).coerceAtLeast(0.0), daysLeft, capacity),
                    )
                }
            } catch (e: Exception) { Error(e, "pace") }

            try {
                // B5 شواذ المصروفات IQR (60 يوماً)
                // [P33-P8] مبالغ المصروفات قروش Long — عتبة تامة 0L ثم ريال لحد outliersIQR
                val exps = g.db.expenses().between(now - 60 * day, now).map { Money.fromPiasters(it.amount) }.filter { it > 0.0 }
                val iqr = QualityMath.outliersIQR(exps)
                _home.value = _home.value.copy(
                    expenseIqrCount = iqr?.outlierIndices?.size ?: 0,
                    expenseIqrUpper = iqr?.upperFence,
                )
            } catch (e: Exception) { Error(e, "expense-iqr") }

            // ═══ المخزون ═══
            try {
                val lines = g.db.invoiceItems().saleLinesSince(now - 90 * day)
                // B6 تصنيف ABC على إيراد 90 يوماً
                val revByProduct = lines.groupBy { it.productId }
                    .mapValues { (_, ls) -> ls.sumOf { it.qty * it.unitPrice } }
                    .mapKeys { (pid, _) -> prodNameUnique[pid] ?: "" }
                    .filterKeys { it.isNotBlank() }
                val abc = MarginMath.paretoABC(revByProduct)
                _inventory.value = _inventory.value.copy(abcRows = abc.take(5))

                // B7 كفاءة رأس المال: ربح 90 يوماً ÷ قيمة المخزون الحالية
                // [P33-P8] تكلفة المنتج قروش Long — ريال لتجانس الوحدة مع سعر السطر الريالي
                val profitByProduct = lines.groupBy { it.productId }
                    .mapValues { (_, ls) -> ls.sumOf { l -> l.qty * (l.unitPrice - Money.fromPiasters(prodCost[l.productId] ?: 0L)) } }
                val capital = products.associate { it.name to it.stockValue }
                    .filterKeys { it.isNotBlank() }
                    // [P33-P8] قيمة المخزون قروش Long — ريال لحد capitalEfficiency
                    .mapValues { (_, v) -> Money.fromPiasters(v) }
                _inventory.value = _inventory.value.copy(
                    efficiency = MarginMath.capitalEfficiency(
                        profitByProduct.mapKeys { (pid, _) -> prodNameUnique[pid] ?: "" }.filterKeys { it.isNotBlank() },
                        capital,
                    ).take(3),
                )

                // B8 سلّم تخفيض للمخزون الراكد (بلا بيع 60 يوماً؛ العمر من createdAt — موثق)
                val sellingIds = lines.filter { it.date >= now - 60 * day }.map { it.productId }.toSet()
                val md = products.filter { it.stockQty > 0 && it.id !in sellingIds && it.salePrice > 0 }
                    .mapNotNull { p ->
                        val age = ((now - p.createdAt) / day).toInt()
                        // [P33-P8] سعرا المنتج قروش Long — ريال لحد markdownLadder الريالي
                        val saleR = Money.fromPiasters(p.salePrice)
                        val suggested = PricingMath.markdownLadder(saleR, Money.fromPiasters(p.costPrice), age)
                        if (suggested < saleR) MarkdownUI(p.name, saleR, suggested, age) else null
                    }.sortedByDescending { it.current - it.suggested }.take(4)
                _inventory.value = _inventory.value.copy(markdown = md)

                // B9 أسماء منتجات متشابهة (حد 200 منتج لأداء معقول)
                val names = products.map { it.name }.filter { it.isNotBlank() }.distinct().take(200)
                val dups = ArrayList<DupUI>()
                for (i in names.indices) for (j in i + 1 until names.size) {
                    val sc = QualityMath.dupScore(names[i], names[j])
                    if (sc >= 85) dups += DupUI(names[i], names[j], sc)
                }
                _inventory.value = _inventory.value.copy(dups = dups.sortedByDescending { it.score }.take(3))
            } catch (e: Exception) { Error(e, "inventory") }

            // ═══ التقارير ═══
            try {
                val lines = g.db.invoiceItems().saleLinesSince(now - 90 * day)
                // إزالة تعريف prodCost الظلّي — نفس خريطة النطاق الخارجي
                // B10 هامش المحفظة + HHI
                val revByProduct = lines.groupBy { it.productId }
                    .mapValues { (_, ls) -> ls.sumOf { it.qty * it.unitPrice } }
                val costByProduct = lines.groupBy { it.productId }
                    // [P33-P8] تكلفة المنتج قروش Long — ريال لتجانس الوحدة مع الإيراد الريالي
                    .mapValues { (_, ls) -> ls.sumOf { l -> l.qty * Money.fromPiasters(prodCost[l.productId] ?: 0L) } }
                _reports.value = _reports.value.copy(
                    portfolio = MarginMath.portfolioMargin(
                        revByProduct.mapKeys { (pid, _) -> prodNameUnique[pid] ?: "" }.filterKeys { it.isNotBlank() },
                        costByProduct.mapKeys { (pid, _) -> prodNameUnique[pid] ?: "" }.filterKeys { it.isNotBlank() },
                    ),
                )

                // B11 أزواج الرفع + B14 «المنتج التالي» — السلال بترتيب الزمن
                val baskets = lines.sortedWith(compareBy({ it.date }, { it.invoiceId }))
                    .groupBy { it.invoiceId }
                    .map { (_, ls) -> ls.map { it.productId }.filter { it > 0 } }
                    .filter { it.size >= 2 }
                val lift = BasketMath.marketBasketLift(baskets, minSupport = 2, topK = 3)
                _reports.value = _reports.value.copy(
                    lift = lift.mapNotNull { r ->
                        val a = prodName[r.a] ?: return@mapNotNull null
                        val b = prodName[r.b] ?: return@mapNotNull null
                        LiftUI(a, b, r.lift, r.confidencePct)
                    },
                )
                // المنتج التالي لأكثر منتج بيعاً
                val topProduct = lines.groupBy { it.productId }.maxByOrNull { (_, ls) -> ls.sumOf { it.qty } }?.key
                if (topProduct != null) {
                    _reports.value = _reports.value.copy(
                        next = BasketMath.nextProduct(baskets, topProduct, 2).mapNotNull { r ->
                            val name = prodName[r.nextId] ?: return@mapNotNull null
                            NextUI(prodName[topProduct] ?: "", name, r.count)
                        },
                    )
                }

                // B12 اتجاه DSO: أسبوعياً (المفتوح ما زال غير مسدد اليوم ÷ مبيعات الأسبوع) — لقطة حالية موثقة
                val weeks = 8
                val pairs = (0 until weeks).map { w ->
                    val from = now - (weeks - w) * 7 * day
                    val to = from + 7 * day
                    val wk = saleInvoices.filter { it.date in from until to }
                    // [P33-P8] المفتوح والمبيعات قروش Long — ريال لحد dsoTrend الريالي
                    Money.fromPiasters(wk.filter { it.open > 0 }.sumOf { it.open }) to Money.fromPiasters(wk.sumOf { it.total })
                }
                _reports.value = _reports.value.copy(dso = FlowMath.dsoTrend(pairs))

                // B13 قيم فواتير شاذة IQR (90 يوماً)
                // [P33-P8] إجماليات الفواتير قروش Long — ريال لحد outliersIQR
                val totals = saleInvoices.filter { it.date >= now - 90 * day && it.total > 0 }.map { Money.fromPiasters(it.total) }
                _reports.value = _reports.value.copy(
                    invoiceOutliers = QualityMath.outliersIQR(totals)?.outlierIndices?.size ?: 0,
                )
            } catch (e: Exception) { Error(e, "reports") }

            // ═══ الذمم ═══
            try {
                val open = saleInvoices.filter { it.open > 0 }
                    // [P33-P8] المفتوح قروش Long — ريال لحدود agingBuckets/collectionForecast الريالية
                    .map { (it.dueDate / day) to Money.fromPiasters(it.open) }
                _debts.value = _debts.value.copy(
                    buckets = FlowMath.agingBuckets(open, today),
                    forecast14 = FlowMath.collectionForecast(open, today, 14),
                )
                // B17 تركّز المديونين على المفتوح الحالي
                val byParty = saleInvoices.filter { it.open > 0 }
                    .groupBy { it.partyId }
                    .mapKeys { (pid, _) -> partyName[pid] ?: "" }
                    .filterKeys { it.isNotBlank() }
                    // [P33-P8] المفتوح قروش Long — ريال لحد customerConcentration الريالي
                    .mapValues { (_, invs) -> Money.fromPiasters(invs.sumOf { it.open }) }
                _debts.value = _debts.value.copy(exposure = RiskMath.customerConcentration(byParty, 3))
            } catch (e: Exception) { Error(e, "debts") }

            // ═══ الشيكات ═══
            try {
                val pending = g.db.checks().allOnce().filter { it.status == 0 || it.status == 1 }
                _checks.value = _checks.value.copy(
                    ladder = RiskMath.maturityLadder(pending.map { it.dueDate / day }, today, 8),
                    bankConc = RiskMath.customerConcentration(
                        pending.groupBy { c -> c.bank.ifBlank { "—" } }
                            // [P33-P8] مبالغ الشيكات قروش Long — ريال لحد customerConcentration
                            .mapValues { (_, cs) -> Money.fromPiasters(cs.sumOf { it.amount }) },
                        3,
                    ),
                )
            } catch (e: Exception) { Error(e, "checks") }

            // ═══ المصروفات ═══
            try {
                // B20 شواذ MAD المنيعة (90 يوماً) — تكملة لـ IQR في الرئيسية بطريقة أشد مناعة
                // [P33-P8] مبالغ المصروفات قروش Long — ريال لحد zScoreOutliers وعرض MadUI
                val exps = g.db.expenses().between(now - 90 * day, now).filter { it.amount > 0L }
                val anomalies = PricingMath.zScoreOutliers(exps.map { Money.fromPiasters(it.amount) })
                    .sortedByDescending { kotlin.math.abs(it.z) }.take(3)
                _expenses.value = ExpensesR10(
                    anomalies.mapNotNull { a ->
                        val e = exps.getOrNull(a.index) ?: return@mapNotNull null
                        MadUI(e.category, Money.fromPiasters(e.amount), a.z)
                    },
                )
            } catch (e: Exception) { Error(e, "expenses") }
        } catch (e: Exception) {
            com.superbiz.app.core.ErrorCenter.warn("R10Insights", "loadAll: ${e.message}")
        }
    }

    /** أيام ISO: الاثنين=1..الأحد=7 (تحويل من Calendar: الأحد=1..السبت=7) */
    private fun isoWeekday(ts: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        return ((c.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
    }

    private fun monthStart(ts: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        c.set(Calendar.DAY_OF_MONTH, 1); c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun Error(e: Exception, tag: String) {
        com.superbiz.app.core.ErrorCenter.warn("R10Insights/$tag", "${e::class.simpleName}: ${e.message}")
    }
}
