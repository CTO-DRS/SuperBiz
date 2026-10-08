package com.superbiz.app.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.superbiz.app.AppGraph
import com.superbiz.app.domain.SmartChat
import com.superbiz.app.domain.algo.CashFlow90Math
import com.superbiz.app.domain.algo.EwmaAlertMath
import com.superbiz.app.domain.algo.NarrativeMath
import com.superbiz.app.domain.algo.QueryParseMath
import com.superbiz.app.domain.algo.ReorderPointMath
import com.superbiz.app.util.Money // [P33-P8] نقطة التحويل الوحيدة ريال ↔ قروش
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * — VM «المنسّق الذكي» — الأفق الثالث من خارطة الطريق (H3-6):
 *
 * يجمع محركات R16 النقية فوق بيانات المستودع الحقيقية ويقدم:
 * 1) بطاقات التوصية للرئيسية: إنذار الجفاف (CashFlow90 — Pro) + إعادة الطلب
 *    الذكية (ReorderPoint — مجاني) + التنبيهات الاستباقية (EwmaAlert — مجاني).
 * 2) الدردشة المحلية: استعلام عربي/إنجليزي محدود الدلالة تُجاب بالكامل
 *    على الجهاز — لا شبكة ولا نموذج سحابي (مبدأ الخارطة غير القابل للتفاوض).
 * 3) روايات KPI (NarrativeMath — Pro) للوحة المؤشرات.
 *
 * عقد الصدق نفسه: بلا بيانات ⇒ null/قوائم فارغة تُخفى في الواجهة؛ كل قسم
 * مستقل بـtry/catch عبر ErrorCenter؛ كل الحساب على Dispatchers.Default؛
 * «اليوم» يُثبَّت مرة واحدة لكل جولة ويُمرر صريحاً للمحركات النقية.
 */
class SmartCoordinatorVM(app: Application) : AndroidViewModel(app) {

    private val g = AppGraph.from(app)

    private val refreshKey = MutableStateFlow(0)
    fun refresh() { refreshKey.value++ }

    // [P39-M4-10] علم الجاهزية — عقد R9..R15 نفسه
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    // ═══════════ بطاقات الرئيسية ═══════════

    /** بند إعادة طلب جاهز للعرض */
    data class ReorderItem(
        val productId: Long,
        val productName: String,
        val stockQty: Double,
        val rop: Double,          // نقطة الطلب المدمجة
        val daysOfSafety: Double, // أيام قبل بلوغ النقطة (سالب = متجاوزة)
        val leadVerdict: String   // OBSERVED / SMALL — صدق مصدر زمن التوريد
    )

    /** تنبيه جاهز للعرض مع مسار إجرائه (بوابة H3-3: كل تنبيه يفتح مساراً) */
    data class AlertItem(
        val streamKey: String,    // sales / collections / outflows
        val actionRoute: String,  // روت Nav المفتوح عند الضغط
        val severity: Int,        // 2 تحذير / 3 حرج
        val dev: Double
    )

    data class HomeCards(
        val dryDayWeek: Int? = null,          // أسبوع الجفاف المتوقع (Pro)
        val monthEnd: Double? = null,         // نهاية 30 يوماً المتوقعة (Pro)
        val monthLower: Double? = null,       // الحد الأدنى 80% (Pro)
        val monthNet: Double? = null,         // صافي التغير المتوقع (Pro)
        val cashVerdict: String? = null,      // HEALTHY / TIGHT / DRY (Pro)
        val reorders: List<ReorderItem> = emptyList(),   // مجاني
        val alerts: List<AlertItem> = emptyList(),       // مجاني
        val stories: List<NarrativeMath.Story> = emptyList(), // Pro (لوحة KPI)
        val storyProvenance: List<String> = emptyList()       // Pro
    )

    private val _home = MutableStateFlow(HomeCards())
    val home: StateFlow<HomeCards> = _home

    // ═══════════ الدردشة المحلية ═══════════

    /** رسالة دردشة — سؤال المستخدم نصه، وجواب المنسّق إجابته البنيوية */
    data class ChatMessage(
        val isUser: Boolean,
        val question: String? = null,
        val answer: SmartChat.ChatAnswer? = null
    )

    private val _chat = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chat: StateFlow<List<ChatMessage>> = _chat

    private val _thinking = MutableStateFlow(false)
    val thinking: StateFlow<Boolean> = _thinking

    /** رمز العملة الأساسية — عقد AppVM.symbol نفسه للعرض الموحد */
    val symbol: StateFlow<String> = g.db.currencies().all()
        .map { list -> list.firstOrNull { it.isBase }?.symbol ?: "ر.س" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "ر.س")

    init {
        viewModelScope.launch {
            loadAll()
            _ready.value = true
            refreshKey.drop(1).collect { loadAll() }
        }
    }

    private fun Error(e: Exception) {
        com.superbiz.app.core.ErrorCenter.warn("SmartCoordinator", "${e::class.simpleName}: ${e.message}")
    }

    /** شبكة يومية صادقة: كل يوم في النافذة له قيمة (الأصفار جزء من الحقيقة) */
    private fun dailySeries(
        events: List<Pair<Long, Double>>, startDay: Long, days: Int
    ): List<Double> {
        val grid = DoubleArray(days)
        events.forEach { (ts, amt) ->
            if (amt.isFinite() && amt > 0.0) {
                val i = ((ts - startDay) / 86_400_000L).toInt()
                if (i in 0 until days) grid[i] += amt
            }
        }
        return grid.toList()
    }

    private suspend fun loadAll() = withContext(Dispatchers.Default) {
        try {
            val today = System.currentTimeMillis()
            val day = 86_400_000L
            val start56 = com.superbiz.app.domain.algo.TimeMath.startOfDay(today) - 55 * day

            // ═══ (أ) التدفق النقدي 90 يوماً — Pro ═══
            try {
                val payments = g.db.payments().since(start56)
                val inflowEvents = payments.filter { it.direction == 0 }
                    .map { it.date to Money.fromPiasters(it.amount) }
                val outflowEvents = payments.filter { it.direction == 1 }
                    .map { it.date to Money.fromPiasters(it.amount) } +
                    g.db.expenses().between(start56, today)
                        .map { it.date to Money.fromPiasters(it.amount) }
                val stats = CashFlow90Math.dailyNetStats(inflowEvents, outflowEvents, today, 56)
                if (stats != null) {
                    val cashNow = Money.fromPiasters(g.reports.cashBalance())
                    // أحداث مجدولة حتمية: شيكات + أقساط غير مسددة ضمن 90 يوماً
                    val horizonEnd = today + 90 * day
                    val scheduled = mutableListOf<CashFlow90Math.Scheduled>()
                    g.db.checks().dueBetween(today, horizonEnd).forEach { c ->
                        scheduled += CashFlow90Math.Scheduled(
                            c.dueDate, Money.fromPiasters(c.amount), isInflow = c.direction == 0)
                    }
                    val plans = g.db.installments().plansOnce().associateBy { it.id }
                    g.db.installments().allInstallments().forEach { inst ->
                        if (inst.open > 0 && inst.dueDate in today..horizonEnd) {
                            plans[inst.planId]?.let { plan ->
                                scheduled += CashFlow90Math.Scheduled(
                                    inst.dueDate, Money.fromPiasters(inst.open), isInflow = plan.direction == 0)
                            }
                        }
                    }
                    val path = CashFlow90Math.forecast90(cashNow, stats, scheduled, today, 90)
                    if (path != null) {
                        val dry = CashFlow90Math.dryDay(path)
                        val month = CashFlow90Math.monthAhead(path)
                        _home.value = _home.value.copy(
                            dryDayWeek = dry?.weekIndex,
                            monthEnd = month?.expectedEnd,
                            monthLower = month?.lower80,
                            monthNet = month?.netExpected,
                            cashVerdict = path.verdict
                        )
                    }
                }
            } catch (e: Exception) { Error(e) }

            // ═══ (ب) إعادة الطلب الذكية — مجاني ═══
            try {
                val products = g.db.products().allOnce().filter { !it.archived }
                val saleLines = g.db.invoiceItems().saleLinesSince(start56)
                val purchaseLines = g.db.invoiceItems().purchaseLinesSince(today - 90 * day)
                val days = 56
                val byProduct = saleLines.groupBy { it.productId }
                val leadsByProduct = purchaseLines
                    .groupBy { it.productId }
                    .mapValues { (_, rows) ->
                        rows.map { it.date }.distinct().sorted()
                            .zipWithNext { a, b -> (b - a).toDouble() / day }
                    }
                val items = mutableListOf<ReorderItem>()
                products.forEach { p ->
                    val rows = byProduct[p.id] ?: return@forEach
                    val series = dailySeries(rows.map { it.date to it.qty }, start56, days)
                    val avgDaily = series.sum() / days
                    if (avgDaily <= 0.0) return@forEach
                    val m = series.sum() / days
                    val sd = kotlin.math.sqrt(series.sumOf { (it - m) * (it - m) } / days)
                    val lead = leadsByProduct[p.id]?.let { ReorderPointMath.leadStats(it) }
                    // صدق المصدر: بلا ملاحظات توريد كافية ⇒ المتعارف 7.0 يُعلَن في التلميح
                    val leadMean = lead?.mean ?: 7.0
                    val leadSd = lead?.sd ?: 0.0
                    val rop = ReorderPointMath.reorderPoint(avgDaily, sd, leadMean, leadSd) ?: return@forEach
                    val dos = ReorderPointMath.daysOfSafety(p.stockQty, rop.point, avgDaily) ?: return@forEach
                    if (dos <= 7.0) { // ضمن أسبوع من نقطة الطلب — يستحق بطاقة
                        items += ReorderItem(
                            productId = p.id,
                            productName = p.name,
                            stockQty = p.stockQty,
                            rop = rop.point,
                            daysOfSafety = dos,
                            leadVerdict = lead?.verdict ?: "DEFAULTED"
                        )
                    }
                }
                _home.value = _home.value.copy(
                    reorders = items.sortedBy { it.daysOfSafety }.take(3))
            } catch (e: Exception) { Error(e) }

            // ═══ (ج) التنبيهات الاستباقية EWMA — مجاني ═══
            try {
                val invoices = g.db.invoices().saleInvoicesSince(start56)
                val payments = g.db.payments().since(start56)
                val expenses = g.db.expenses().between(start56, today)
                val salesDaily = dailySeries(
                    invoices.map { it.date to Money.fromPiasters(it.total) }, start56, 56)
                val collectionsDaily = dailySeries(
                    payments.filter { it.direction == 0 }
                        .map { it.date to Money.fromPiasters(it.amount) }, start56, 56)
                val outflowsDaily = dailySeries(
                    payments.filter { it.direction == 1 }
                        .map { it.date to Money.fromPiasters(it.amount) } +
                        expenses.map { it.date to Money.fromPiasters(it.amount) }, start56, 56)
                val streams = listOf(
                    Triple("sales", salesDaily, com.superbiz.app.ui.nav.Routes.REPORTS),
                    Triple("collections", collectionsDaily, com.superbiz.app.ui.nav.Routes.DEBTS),
                    Triple("outflows", outflowsDaily, com.superbiz.app.ui.nav.Routes.EXPENSES)
                )
                val evals = streams.mapNotNull { (key, series, route) ->
                    EwmaAlertMath.evaluate(series)?.let {
                        EwmaAlertMath.StreamEval(key, route, it)
                    }
                }
                _home.value = _home.value.copy(
                    alerts = EwmaAlertMath.digest(evals).map {
                        AlertItem(it.streamKey, it.actionKey, it.eval.severity, it.eval.dev)
                    })
            } catch (e: Exception) { Error(e) }

            // ═══ (د) روايات KPI — Pro (لوحة المؤشرات) ═══
            try {
                val (salesNow, expensesNow, profitNow) = g.reports.monthTotals()
                val cal = java.util.Calendar.getInstance().apply {
                    timeInMillis = today
                    add(java.util.Calendar.MONTH, -1)
                }
                val prev = g.reports.incomeStatement(
                    com.superbiz.app.domain.algo.TimeMath.monthBounds(cal.timeInMillis).first,
                    com.superbiz.app.domain.algo.TimeMath.monthBounds(cal.timeInMillis).second)
                val salesPrev = Money.fromPiasters(prev.revenue + prev.otherIncome)
                val expensesPrev = Money.fromPiasters(prev.expenses)
                val profitPrev = Money.fromPiasters(prev.revenue + prev.otherIncome - prev.cogs - prev.expenses)
                val target = runCatching { g.proStore.targetsOnce().sales }
                    .getOrDefault(0.0).takeIf { it > 0.0 }
                val f = NarrativeMath.facts(
                    salesNow = Money.fromPiasters(salesNow),
                    salesPrev = salesPrev,
                    profitNow = Money.fromPiasters(profitNow),
                    profitPrev = profitPrev,
                    expensesNow = Money.fromPiasters(expensesNow),
                    expensesPrev = expensesPrev,
                    // لا لقطة تاريخية للذمم ⇒ 0/0 = لا سطر (صدق: لا تاريخ لا رواية)
                    overdueNow = 0.0, overduePrev = 0.0,
                    salesTarget = target
                )
                if (f != null) {
                    _home.value = _home.value.copy(
                        stories = NarrativeMath.narrate(f),
                        storyProvenance = NarrativeMath.provenance(f))
                }
            } catch (e: Exception) { Error(e) }
        } catch (e: Exception) {
            com.superbiz.app.core.ErrorCenter.warn("SmartCoordinator", "loadAll: ${e.message}")
        }
    }

    // ═══════════ الدردشة: سؤال → إجابة محلية حتمية ═══════════

    /**
     * يُجاب على الجهاز فوراً: تحليل → لقطات من المستودع → SmartChat.
     * الاستعلام خارج النطاق يعطي إجابة «لا بيانات كافية» مهذبة (فشل صادق).
     */
    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty()) return
        _chat.value = _chat.value + ChatMessage(isUser = true, question = q)
        _thinking.value = true
        viewModelScope.launch {
            val ans = withContext(Dispatchers.Default) { runCatching { answerLocally(q) } }
            _thinking.value = false
            ans.onSuccess { a ->
                _chat.value = _chat.value + ChatMessage(isUser = false, answer = a)
            }.onFailure { e ->
                Error(e)
                _chat.value = _chat.value + ChatMessage(
                    isUser = false,
                    answer = SmartChat.ChatAnswer("ans_nodata", emptyList(), basis = "error: ${e.message}"))
            }
        }
    }

    private suspend fun answerLocally(q: String): SmartChat.ChatAnswer {
        val today = System.currentTimeMillis()
        val products = g.db.products().allOnce()
        val parties = g.db.parties().allOnce()
        val parsed = QueryParseMath.parse(
            q,
            productNames = products.map { it.name },
            partyNames = parties.map { it.name }
        ) ?: return SmartChat.ChatAnswer("ans_nodata", emptyList(), basis = "out of scope")
        val (from, to) = QueryParseMath.resolveRange(parsed, today)
        val day = 86_400_000L
        val days = (((to - from) / day) + 1).coerceAtLeast(1)

        // لقطة الفترة
        val salesInRange = g.db.invoices().saleInvoicesSince(from)
            .filter { it.date <= to }
        val ranged = SmartChat.RangedFacts(
            salesTotal = Money.fromPiasters(salesInRange.sumOf { it.total }),
            profitTotal = run {
                val inc = g.reports.incomeStatement(from, to)
                Money.fromPiasters(inc.revenue + inc.otherIncome - inc.cogs - inc.expenses)
            },
            expensesTotal = Money.fromPiasters(g.db.expenses().sumBetween(from, to)),
            invoicesCount = salesInRange.size
        )
        // اللقطة العامة
        val all = products.filter { !it.archived }
        val global = SmartChat.GlobalFacts(
            cashNow = Money.fromPiasters(g.reports.cashBalance()),
            overdueTotal = Money.fromPiasters(g.reports.overdueTotal(today)),
            aging = g.reports.agingBuckets(today).map { Money.fromPiasters(it) },
            lowStockCount = all.count { it.isLow },
            stockValue = Money.fromPiasters(
                all.sumOf { Math.round(it.stockQty * it.costPrice) })
        )
        // لقطة الموضوع
        val productFacts = parsed.subject?.takeIf { it.kind == QueryParseMath.Subject.Kind.PRODUCT }?.let { s ->
            val p = products.firstOrNull { it.name == s.name }
            if (p == null) null
            else {
                val sold = g.db.invoiceItems().saleLinesSince(from)
                    .filter { it.productId == p.id && it.date <= to }.sumOf { it.qty }
                SmartChat.ProductFacts(p.name, p.stockQty, sold, sold / days)
            }
        }
        val partyFacts = parsed.subject?.takeIf { it.kind == QueryParseMath.Subject.Kind.PARTY }?.let { s ->
            parties.firstOrNull { it.name == s.name }?.let { p ->
                val bal = g.db.journal().partyBalances()
                    .firstOrNull { it.pid == p.id }?.balance ?: 0L
                SmartChat.PartyFacts(p.name, Money.fromPiasters(bal))
            }
        }
        return SmartChat.answer(parsed, ranged, global, productFacts, partyFacts)
    }

    /** أسئلة مقترحة للبداية — حتمية وموطّنة في الواجهة */
    fun suggestions(): List<String> = listOf(
        "كم مبيعات هذا الشهر؟",
        "ارباح الشهر الماضي",
        "الذمم المتأخرة",
        "مخزون"
    )
}
