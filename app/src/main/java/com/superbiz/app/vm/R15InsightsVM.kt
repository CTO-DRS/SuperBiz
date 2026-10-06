package com.superbiz.app.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.superbiz.app.AppGraph
import com.superbiz.app.core.ErrorCenter
import com.superbiz.app.domain.algo.BayesMath
import com.superbiz.app.domain.algo.BenfordMath
import com.superbiz.app.domain.algo.CusumMath
import com.superbiz.app.domain.algo.ElasticityMath
import com.superbiz.app.domain.algo.FourierMath
import com.superbiz.app.domain.algo.GamblerMath
import com.superbiz.app.domain.algo.GrubbsMath
import com.superbiz.app.domain.algo.IqrMath
import com.superbiz.app.domain.algo.JaccardMath
import com.superbiz.app.domain.algo.KappaMath
import com.superbiz.app.domain.algo.LogitMath
import com.superbiz.app.domain.algo.MarkovMath
import com.superbiz.app.domain.algo.NelsonMath
import com.superbiz.app.domain.algo.OeeMath
import com.superbiz.app.domain.algo.ParetoMath
import com.superbiz.app.domain.algo.RecencyMath
import com.superbiz.app.domain.algo.TheilMath
import com.superbiz.app.domain.algo.UtestMath
import com.superbiz.app.domain.algo.VaRMath
import com.superbiz.app.domain.algo.ZipfMath
import com.superbiz.app.util.Money // [P33-P8] نقطة التحويل الوحيدة ريال ↔ قروش
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * — VM الرؤى الذكية للموجة R15: يحسب 20 ميزة جديدة
 * من بيانات حقيقية عبر خوارزميات R15Smart، بعقد الصدق نفسه
 * - بلا بيانات ⇒ null/قوائم فارغة تُخفى في الواجهة، لا أرقام مزيّفة.
 * - كل قسم مستقل بـ try/catch يمرر عبر ErrorCenter ولا يُسقط بقية الأقسام.
 * - استعلامات موجودة فقط — لا تغيير في مخطط قاعدة البيانات (v4).
 * - كل الحساب على Dispatchers.Default (درس R12-C20 وR13-B16 وR14).
 *
 * توزيع الميزات على 8 شاشات
 * الرئيسية F1..F3 · نقاط البيع F4..F6 · الفواتير F7..F9 · المخزون F10..F12
 * التقارير F13..F15 · الذمم F16..F18 · الشيكات F19 · المصروفات F20
*/
class R15InsightsVM(app: Application) : AndroidViewModel(app) {

    private val g = AppGraph.from(app)

    private val refreshKey = MutableStateFlow(0)
    fun refresh() { refreshKey.value++ }

    // [P39-M4-10] علم الجاهزية — عقد R9/R10 نفسه: false أثناء أول جولة حساب ثم true
    // إلى الأبد (التحديث اليدوي لا يعيده false — لا وميض سكيلتون عند refresh)
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    // ═══════════════ الرئيسية (F1..F3) ═══════════════

    data class HomeR15(
        val bayes: BayesMath.Posterior? = null,        // F1 احتمال عودة المشتري (بايز)
        val benford: BenfordMath.Benford? = null,      // F2 قانون بِنفورد للرقم الأول
        val cusum: CusumMath.Cusum? = null,            // F3 كشف انحراف المبيعات (كوزوم)
    )

    private val _home = MutableStateFlow(HomeR15())
    val home: StateFlow<HomeR15> = _home

    // ═══════════════ نقاط البيع (F4..F6) ═══════════════

    data class ElasticUI(
        val productName: String,
        val elasticity: ElasticityMath.Elasticity,
    )

    data class PosR15(
        val elasticity: ElasticUI? = null,          // F4 مرونة السعر القوسية
        val markov: MarkovMath.Chain? = null,       // F5 زخم الأيام صاعد/هابط
        val runway: GamblerMath.Runway? = null,     // F6 أفق احتراق النقد
    )

    private val _pos = MutableStateFlow(PosR15())
    val pos: StateFlow<PosR15> = _pos

    // ═══════════════ الفواتير (F7..F9) ═══════════════

    data class InvoicesR15(
        val grubbs: GrubbsMath.Grubbs? = null,   // F7 الفاتورة الشاذة (جريبس)
        val iqr: IqrMath.Fences? = null,         // F8 سياج IQR وفواتير الحواف
        val utest: UtestMath.UTest? = null,      // F9 مقارنة الشهرين (مان-ويتني)
    )

    private val _invoices = MutableStateFlow(InvoicesR15())
    val invoices: StateFlow<InvoicesR15> = _invoices

    // ═══════════════ المخزون (F10..F12) ═══════════════

    data class InventoryR15(
        val coPurchase: List<Triple<String, String, Int>>? = null,   // F10 أزواج الشراء المشترك
        val pareto: ParetoMath.Pareto? = null,                       // F11 عتبة 80/20
        val zipf: ZipfMath.Zipf? = null,                             // F12 تركّز الرأس (زيبف)
    )

    private val _inventory = MutableStateFlow(InventoryR15())
    val inventory: StateFlow<InventoryR15> = _inventory

    // ═══════════════ التقارير (F13..F15) ═══════════════

    data class ReportsR15(
        val cycle: FourierMath.Cycle? = null,    // F13 الدورة السائدة
        val nelson: NelsonMath.Nelson? = null,   // F14 قواعد نيلسون
        val theil: TheilMath.Theil? = null,      // F15 ميل ثيل-سن المتين
    )

    private val _reports = MutableStateFlow(ReportsR15())
    val reports: StateFlow<ReportsR15> = _reports

    // ═══════════════ الذمم (F16..F18) ═══════════════

    data class ChurnUI(val partyName: String, val probability: Double, val verdict: String)

    data class DebtsR15(
        val kappa: KappaMath.Kappa? = null,   // F16 توافق الالتزام (كابا)
        val churn: ChurnUI? = null,           // F17 احتمالية التسرب (لوجيت)
        val rfm: RecencyMath.Rfm? = null,     // F18 تسجيل RFM لأفضل عميل
    )

    private val _debts = MutableStateFlow(DebtsR15())
    val debts: StateFlow<DebtsR15> = _debts

    // ═══════════════ الشيكات (F19) ═══════════════

    data class ChecksR15(
        val var95: VaRMath.Var? = null,   // F19 أسوأ خسارة يومية تاريخية
    )

    private val _checks = MutableStateFlow(ChecksR15())
    val checks: StateFlow<ChecksR15> = _checks

    // ═══════════════ المصروفات (F20) ═══════════════

    data class ExpensesR15(
        val oee: OeeMath.Oee? = null,   // F20 الكفاءة الإجمالية المركّبة
    )

    private val _expenses = MutableStateFlow(ExpensesR15())
    val expenses: StateFlow<ExpensesR15> = _expenses

    // ═══════════════ التحميل ═══════════════

    init {
        viewModelScope.launch {
            loadAll()
            _ready.value = true // [P39-M4-10]: اكتملت الجولة الأولى — السكيلتون يفسح للمحتوى
            refreshKey.collect { if (it > 0) loadAll() }
        }
    }

    private suspend fun loadAll() = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val today = com.superbiz.app.domain.algo.TimeMath.startOfDay(now) / day
        try {
            val saleInvoices = g.db.invoices().allOnce().filter { it.type == 0 && it.status < 3 }
            val products = g.inventory.products().first().filter { !it.archived }
            val prodName = products.associate { it.id to it.name }
            val saleLines90 = g.db.invoiceItems().saleLinesSince(now - 90 * day)

            /** إيراد يومي لآخر nDay يوم (أقدم أولاً) — نفس أداة R14 */
            // [P7-L5 إصلاح]: كانت dailyRevenue تُمسح من جديد عند كل استدعاء —
            // و(30) تُستدعى ثلاث مرات (F3/F14/F15) و(21) و(42) مرة لكل منهما،
            // أي 5 مسوحات O(nDay×n) في كل جولة loadAll لثلاث نوافذ فعلية فقط.
            // الآن ذاكرة لكل نافذة: نفس القيم حرفياً لكل مستدعٍ وبلا تغيير
            // دلالة النوافذ (today مثبت أعلى جولة loadAll).
            val dailyRevCache = HashMap<Int, List<Double>>()
            fun dailyRevenue(nDay: Int): List<Double> =
                dailyRevCache.getOrPut(nDay) {
                    // [P33-P8] إيراد يومي قروش → ريال (متوسط كوزوم يُعرض بالريال كما كان)
                    (nDay - 1 downTo 0).map { back ->
                        val d = today - back
                        Money.fromPiasters(
                            saleInvoices.filter { com.superbiz.app.domain.algo.TimeMath.startOfDay(it.date) / day == d }
                                .sumOf { it.total },
                        )
                    }
                }

            // [P7-L5 إصلاح]: كان صافي التدفق اليومي 60 يوماً (استعلامَي المدفوعات
            // والمصروفات + بناء السلسلة O(60×n)) يُمسح مرتين في كل جولة — مرة
            // لأفق الاحتراق (F6) ومرة لقياس أسوأ خسارة تاريخية (F19) بنفس التعبير
            // الحرفي. الآن يُحسب مرة واحدة عند أول استهلاك وتُعاد نفس القائمة،
            // وبلا أي تغيير في دلالة النوافذ الزمنية. الطريقة lazily-computed-once
            // تحفظ عزل الأقسام: لو فشل القسم الأول قبل التخزين يعيد الثاني الحساب.
            var net60Cache: List<Double>? = null
            // [V1-B4 إصلاح] الدالة المحلية تستدعي DAOs معلّقة فصارت suspend (خطأ ترجمة كامن من P7-L5)
            suspend fun net60Once(): List<Double> {
                net60Cache?.let { return it }
                val cashIn = g.db.payments().since(now - 60 * day)
                    .filter { it.direction == 0 && it.method != "DEBT" && it.method != "CHECK_BOUNCE" }
                val cashOut = g.db.payments().since(now - 60 * day)
                    .filter { it.direction == 1 && it.method != "DEBT" }
                val exps60 = g.db.expenses().between(now - 60 * day, now)
                val series = (59 downTo 0).map { back ->
                    val d = today - back
                    // [P33-P8] التدفقات قروش → ريال يومياً (الاحتراق و VaR يُعرضان مبلغًا)
                    val din = Money.fromPiasters(
                        cashIn.filter { com.superbiz.app.domain.algo.TimeMath.startOfDay(it.date) / day == d }.sumOf { it.amount },
                    )
                    val dout = Money.fromPiasters(
                        cashOut.filter { com.superbiz.app.domain.algo.TimeMath.startOfDay(it.date) / day == d }.sumOf { it.amount } +
                            exps60.filter { com.superbiz.app.domain.algo.TimeMath.startOfDay(it.date) / day == d }.sumOf { it.amount },
                    )
                    din - dout
                }
                net60Cache = series
                return series
            }

            // ═══ الرئيسية ═══
            try {
                // F1 احتمال عودة المشتري: المسبق نسبة العملاء المكررين (180 يوماً)،
                // ونسبة الأرجحية قوة إشارة التكرار (تكرار الشراء المتوسط مقابل العابر)
                val cut180 = now - 180 * day
                val recent = saleInvoices.filter { it.date >= cut180 && it.partyId > 0 }
                val byParty = recent.groupBy { it.partyId }
                val repeaters = byParty.values.count { it.size >= 2 }
                val singles = byParty.values.count { it.size == 1 }
                val total = repeaters + singles
                if (total >= 8 && repeaters > 0 && singles > 0) {
                    val prior = repeaters.toDouble() / total
                    val repFreq = byParty.values.filter { it.size >= 2 }
                        .map { it.size.toDouble() }.average()
                    val lr = (repFreq / 2.0).coerceIn(0.2, 6.0)   // إشارة محصورة معقولة
                    _home.value = _home.value.copy(bayes = BayesMath.Posterior(prior, lr, BayesMath.posterior(prior, lr)!!))
                }
            } catch (e: Exception) { Error(e, "bayes") }

            try {
                // F2 قانون بِنفورد: الرقم الأول لإجماليات الفواتير كلها (حد أدنى داخلي 5)
                // [P33-P8] قروش → ريال (الرقم الأول غير متأثر بالقسمة على 100)
                _home.value = _home.value.copy(
                    benford = BenfordMath.firstDigitShares(saleInvoices.map { Money.fromPiasters(it.total) })
                )
            } catch (e: Exception) { Error(e, "benford") }

            try {
                // F3 كشف الانحراف التصاعدي على إيراد 30 يوماً
                _home.value = _home.value.copy(cusum = CusumMath.detect(dailyRevenue(30), 4.0))
            } catch (e: Exception) { Error(e, "cusum") }

            // ═══ نقاط البيع ═══
            try {
                // F4 مرونة قوسية لنفس المنتج بين أرخص وأغلى سعر بيع فعلي (بنود 90 يوماً)
                val byProduct = saleLines90.groupBy { it.productId }
                var best: Triple<String, ElasticityMath.Elasticity, Int>? = null
                for ((pid, lines) in byProduct) {
                    val byPrice = lines.groupBy { it.unitPrice }
                    if (byPrice.size < 2) continue
                    val priceQty = byPrice.map { (p, ls) -> Triple(p, ls.sumOf { it.qty }, ls.size) }
                    val lo = priceQty.minByOrNull { it.first } ?: continue
                    val hi = priceQty.maxByOrNull { it.first } ?: continue
                    if (lo.first >= hi.first || lo.second <= 0.0 || hi.second <= 0.0) continue
                    // [P33-P8] أسعار صف DAO قروش كـDouble → ريال (المرونة القوسية نسبية لا تتأثر بالوحدة)
                    val e = ElasticityMath.arc(lo.first / 100.0, lo.second, hi.first / 100.0, hi.second) ?: continue
                    val votes = lo.third + hi.third
                    val name = prodName[pid] ?: continue
                    if (best == null || votes > best.third) best = Triple(name, e, votes)
                }
                best?.let { _pos.value = _pos.value.copy(elasticity = ElasticUI(it.first, it.second)) }
            } catch (e: Exception) { Error(e, "elasticity") }

            try {
                // F5 زخم الأيام: انتقالات صاعد/هابط على إيراد 21 يوماً
                val rev21 = dailyRevenue(21)
                val ups = (1 until rev21.size).map { rev21[it] >= rev21[it - 1] }
                _pos.value = _pos.value.copy(markov = MarkovMath.transitions(ups))
            } catch (e: Exception) { Error(e, "markov") }

            try {
                // F6 أفق الاحتراق: صافي التدفق اليومي 60 يوماً مقابل الرصيد النقدي
                // [P7-L5 إصلاح]: عبر net60Once (مسح واحد لكل جولة — انظر أعلاه)
                val net60 = net60Once()
                val mean = net60.sum() / net60.size
                val std = kotlin.math.sqrt(net60.sumOf { (it - mean) * (it - mean) } / net60.size)
                _pos.value = _pos.value.copy(
                    // [P33-P8] الرصيد قروش → ريال ليطابق مقياس المتوسط/الانحراف المحسوبين ريالياً
                    runway = GamblerMath.ruinHorizon(Money.fromPiasters(g.reports.cashBalance()), mean, std)
                )
            } catch (e: Exception) { Error(e, "runway") }

            // ═══ الفواتير ═══
            try {
                // F7 الفاتورة الشاذة: جريبس على إجماليات 30 يوماً — [P33-P8] قروش → ريال
                val totals30 = saleInvoices.filter { it.date >= now - 30 * day }.map { Money.fromPiasters(it.total) }
                _invoices.value = _invoices.value.copy(grubbs = GrubbsMath.extreme(totals30))
            } catch (e: Exception) { Error(e, "grubbs") }

            try {
                // F8 سياج IQR على إجماليات 90 يوماً — [P33-P8] قروش → ريال (السياج يُعرض مبلغًا)
                val totals90 = saleInvoices.filter { it.date >= now - 90 * day }.map { Money.fromPiasters(it.total) }
                _invoices.value = _invoices.value.copy(iqr = IqrMath.fences(totals90))
            } catch (e: Exception) { Error(e, "iqr") }

            try {
                // F9 هل تغيّر توزيع إجماليات الفواتير بين الشهرين؟ (مان-ويتني)
                // [P33-P8] قروش → ريال (الرتب النسبية)
                val cur = saleInvoices.filter { it.date >= now - 30 * day }.map { Money.fromPiasters(it.total) }
                val prev = saleInvoices.filter { it.date in (now - 60 * day) until (now - 30 * day) }.map { Money.fromPiasters(it.total) }
                _invoices.value = _invoices.value.copy(utest = UtestMath.mannWhitney(cur, prev))
            } catch (e: Exception) { Error(e, "utest") }

            // ═══ المخزون ═══
            try {
                // F10 أزواج الشراء المشترك: سلات = بنود كل فاتورة (90 يوماً)
                val baskets = saleLines90.groupBy { it.invoiceId }.values.map { lines ->
                    lines.mapNotNull { prodName[it.productId] }.toSet().filter { it.isNotBlank() }.toSet()
                }.filter { it.size >= 2 }
                _inventory.value = _inventory.value.copy(
                    coPurchase = JaccardMath.coPurchase(baskets, 2).take(3)
                )
            } catch (e: Exception) { Error(e, "copurchase") }

            try {
                // F11 عتبة 80/20: إيراد 90 يوماً لكل منتج
                val revenueByProduct = saleLines90.groupBy { it.productId }
                    .mapValues { (_, ls) -> ls.sumOf { it.qty * it.unitPrice } }
                    .filter { it.value > 0.0 }
                    .map { (prodName[it.key] ?: "") to it.value }
                    .filter { it.first.isNotBlank() }
                    .map { it.second }
                _inventory.value = _inventory.value.copy(pareto = ParetoMath.cut(revenueByProduct))
            } catch (e: Exception) { Error(e, "pareto") }

            try {
                // F12 تركّز الرأس مقابل زيبف المثالي
                val revenueByProduct = saleLines90.groupBy { it.productId }
                    .mapValues { (_, ls) -> ls.sumOf { it.qty * it.unitPrice } }
                    .filter { it.value > 0.0 }
                _inventory.value = _inventory.value.copy(zipf = ZipfMath.headShare(revenueByProduct.values.toList()))
            } catch (e: Exception) { Error(e, "zipf") }

            // ═══ التقارير ═══
            try {
                // F13 الدورة السائدة في إيراد 42 يوماً
                _reports.value = _reports.value.copy(cycle = FourierMath.dominantCycle(dailyRevenue(42)))
            } catch (e: Exception) { Error(e, "fourier") }

            try {
                // F14 قواعد نيلسون على إيراد 30 يوماً
                _reports.value = _reports.value.copy(nelson = NelsonMath.violations(dailyRevenue(30)))
            } catch (e: Exception) { Error(e, "nelson") }

            try {
                // F15 ميل ثيل-سن المتين على إيراد 30 يوماً
                _reports.value = _reports.value.copy(theil = TheilMath.fit(dailyRevenue(30)))
            } catch (e: Exception) { Error(e, "theil") }

            // ═══ الذمم ═══
            try {
                // F16 توافق كابا بين ترتيب الجدولة والتزام الدفع للأقساط المسددة
                val paidInst = g.db.installments().allInstallments()
                    .filter { it.status == 1 && it.paidDate != null }
                if (paidInst.size >= 5) {
                    val midDate = paidInst.map { it.dueDate }.sorted()
                        .let { it[it.size / 2] }
                    val planned = paidInst.map { if (it.dueDate <= midDate) "EARLY" else "LATE" }
                    val actual = paidInst.map {
                        if ((it.paidDate ?: it.dueDate) <= it.dueDate) "EARLY" else "LATE"
                    }
                    _debts.value = _debts.value.copy(kappa = KappaMath.agreement(planned, actual))
                }
            } catch (e: Exception) { Error(e, "kappa") }

            try {
                // F17 احتمالية التسرب لأكبر رصيد مفتوح: لوجيت على القدم والتكرار (أوزان مثبتة)
                // [P33-P8] الرصيد قروش — مساواة صحيحة تامة بدل عتبة 0.004
                val balances = g.db.journal().partyBalances().filter { it.balance > 0L }
                val top = balances.maxByOrNull { it.balance }
                if (top != null && top.pid > 0) {
                    val invsOf = saleInvoices.filter { it.partyId == top.pid }
                    val recencyDays = invsOf.maxOfOrNull { it.date }?.let { (now - it) / day.toDouble() } ?: 999.0
                    val freq180 = invsOf.count { it.date >= now - 180 * day }.toDouble()
                    val p = LogitMath.probability(-0.8, 0.015, recencyDays, -0.20, freq180)
                    if (p != null) {
                        val name = g.db.parties().allOnce().firstOrNull { it.id == top.pid }?.name ?: ""
                        _debts.value = _debts.value.copy(churn = ChurnUI(name, p, LogitMath.verdict(p)))
                    }
                }
            } catch (e: Exception) { Error(e, "churn") }

            try {
                // F18 تسجيل RFM لأفضل عميل (أعلى إيراد 180 يوماً) بحدود افتراضية معلنة
                val cut180 = now - 180 * day
                val byParty = saleInvoices.filter { it.date >= cut180 && it.partyId > 0 }.groupBy { it.partyId }
                val bestParty = byParty.entries.maxByOrNull { it.value.sumOf { v -> v.total } }
                if (bestParty != null) {
                    val recency = (now - (bestParty.value.maxOfOrNull { it.date } ?: now)) / day.toDouble()
                    val freq = bestParty.value.size.toDouble()
                    // [P33-P8] المبلغ قروش → ريال ليقارن بحدود mCuts الريالية (500/1500/3000/6000)
                    val monetary = Money.fromPiasters(bestParty.value.sumOf { it.total })
                    _debts.value = _debts.value.copy(
                        rfm = RecencyMath.rfm(
                            recency, freq, monetary,
                            fCuts = listOf(2.0, 4.0, 6.0, 8.0),
                            mCuts = listOf(500.0, 1500.0, 3000.0, 6000.0),
                            rCuts = listOf(7.0, 14.0, 30.0, 60.0),
                        )
                    )
                }
            } catch (e: Exception) { Error(e, "rfm") }

            // ═══ الشيكات ═══
            try {
                // F19 أسوأ خسارة يومية تاريخية على صافي التدفق 60 يوماً
                // [P7-L5 إصلاح]: نفس سلسلة F6 معاد استخدامها بدل المسح الثاني
                _checks.value = _checks.value.copy(var95 = VaRMath.historical(net60Once(), 0.95))
            } catch (e: Exception) { Error(e, "var") }

            // ═══ المصروفات ═══
            try {
                // F20 الكفاءة الإجمالية المركّبة: تعبئة (نفاد قليل) × تصريف (بيع أكثر الأصناف) × هامش
                val active = products.size
                val lowCount = products.count { it.isLow }
                val fill = if (active > 0) (active - lowCount).toDouble() / active else 0.0
                val soldIds = saleLines90.map { it.productId }.toSet()
                val sellThrough = if (active > 0) soldIds.size.toDouble() / active else 0.0
                val withMargin = saleInvoices.filter { it.date >= now - 90 * day }
                    .mapNotNull { inv ->
                        val net = inv.subtotal - inv.discount
                        // [P33-P8] القروش صحيحة: العتبة تامة 0L والهامش نسبة بقسمة عائمة لا صحيحة
                        if (net <= 0L) null else (net - inv.costTotal).toDouble() / net.toDouble()
                    }
                val margin = if (withMargin.size >= 3) withMargin.sum() / withMargin.size else 0.0
                _expenses.value = _expenses.value.copy(
                    oee = OeeMath.composite(fill, sellThrough, margin.coerceIn(0.0, 1.0))
                )
            } catch (e: Exception) { Error(e, "oee") }
        } catch (e: Exception) {
            ErrorCenter.warn("R15Insights", "loadAll: ${e.message}")
        }
    }

    private fun Error(e: Exception, section: String) =
        ErrorCenter.warn("R15Insights/$section", "${e::class.simpleName}: ${e.message}")

    // [P7-L6 إصلاح] توثيق — بلا تغيير كود: فحص الموجة الرابعة لأيام UTC في هذا الملف
    // وجد أن فهارس الأيام كلها تُبنى عبر TimeMath.startOfDay(ts) / 86400000
    // (بداية اليوم **المحلي** ثم قسمة) وبنفس الأسلوب على طرفي المقارنة
    // (today وكل فاتورة/دفعة/مصروف) — فلا أيام UTC مبنية من الطابع الخام ولا خلط
    // بين الفهرسين. الأمر منجَل هنا ولا يحتاج محاذاة.
}
