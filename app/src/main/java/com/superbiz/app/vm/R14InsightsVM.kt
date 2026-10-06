package com.superbiz.app.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.superbiz.app.AppGraph
import com.superbiz.app.domain.algo.AbcXyzMath
import com.superbiz.app.domain.algo.BandMath
import com.superbiz.app.domain.algo.BordaMath
import com.superbiz.app.domain.algo.BlendMath
import com.superbiz.app.domain.algo.CampaignMath
import com.superbiz.app.domain.algo.CatalogHygieneMath
import com.superbiz.app.domain.algo.CoverageMath
import com.superbiz.app.domain.algo.DrawdownMath
import com.superbiz.app.domain.algo.EntropyMath
import com.superbiz.app.domain.algo.FairShareMath
import com.superbiz.app.domain.algo.FixedReserveMath
import com.superbiz.app.domain.algo.GapMath
import com.superbiz.app.domain.algo.HurstMath
import com.superbiz.app.domain.algo.KsMath
import com.superbiz.app.domain.algo.MarginMixMath
import com.superbiz.app.domain.algo.MoverMath
import com.superbiz.app.domain.algo.RunsMath
import com.superbiz.app.domain.algo.ShrinkageMath
import com.superbiz.app.domain.algo.StalenessMath
import com.superbiz.app.domain.algo.TimeMath
import com.superbiz.app.domain.algo.WinsorMath
import com.superbiz.app.domain.algo.ExpenseMath
import com.superbiz.app.util.Money // [P33-P8] نقطة التحويل الوحيدة ريال ↔ قروش
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * — VM الرؤى الذكية للموجة R14: يحسب 20 ميزة جديدة
 * من بيانات حقيقية عبر خوارزميات R14Smart، بعقد الصدق نفسه
 * - بلا بيانات ⇒ null/قوائم فارغة تُخفى في الواجهة، لا أرقام مزيّفة.
 * - كل قسم مستقل بـ try/catch يمرر عبر ErrorCenter ولا يُسقط بقية الأقسام.
 * - استعلامات موجودة/مضافة توازياً فقط — لا تغيير في مخطط قاعدة البيانات (v4).
 * - كل الحساب على Dispatchers.Default (درس R12-C20 وR13-B16).
 *
 * توزيع الميزات على 8 شاشات
 * الرئيسية F1..F4 · نقاط البيع F5..F7 · الفواتير F8..F10 · المخزون F11..F14
 * التقارير F15..F17 · الذمم F18 · الشيكات F19 · المصروفات F20
*/
class R14InsightsVM(app: Application) : AndroidViewModel(app) {

    private val g = AppGraph.from(app)

    private val refreshKey = MutableStateFlow(0)
    fun refresh() { refreshKey.value++ }

    // [P39-M4-10] علم الجاهزية — عقد R9/R10 نفسه: false أثناء أول جولة حساب ثم true
    // إلى الأبد (التحديث اليدوي لا يعيده false — لا وميض سكيلتون عند refresh)
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    // ═══════════════ الرئيسية (F1..F4) ═══════════════

    data class HomeR14(
        val winsor: WinsorMath.Winsor? = null,   // F1 المتوسط اليومي المتين (وينسور)
        val hurst: HurstMath.Hurst? = null,      // F2 مثابرة الاتجاه (هيرست)
        val ks: KsMath.KS? = null,               // F3 هل تغيّر توزيع الفواتير؟ (ك-س)
        val cover: CoverageMath.Cover? = null,   // F4 غطاء التزامات 30 يوماً
    )

    private val _home = MutableStateFlow(HomeR14())
    val home: StateFlow<HomeR14> = _home

    // ═══════════════ نقاط البيع (F5..F7) ═══════════════

    data class PosR14(
        val campaign: CampaignMath.Req? = null,      // F5 كسر الخصم تعادله
        val marginMix: MarginMixMath.Mix? = null,    // F6 هامش المحصّل كلياً مقابل المفتوح
        val drawdown: DrawdownMath.DD? = null,       // F7 أقصى تراجع في منحنى النقد
    )

    private val _pos = MutableStateFlow(PosR14())
    val pos: StateFlow<PosR14> = _pos

    // ═══════════════ الفواتير (F8..F10) ═══════════════

    data class WaterfallUI(
        val partyName: String,
        val payment: Double,
        val rows: List<Pair<String, Double>>,   // (رقم/اسم الفاتورة، المخصّص)
        val unallocated: Double,
        val closedCount: Int,
    )

    data class InvoicesR14(
        val waterfall: WaterfallUI? = null,     // F8 توزيع أحدث دفعة على المفتوح
        val runs: RunsMath.Runs? = null,        // F9 اختبار الجولات للإيراد اليومي
        val entropy: EntropyMath.Ent? = null,   // F10 عدد الفئات الفعّال
    )

    private val _invoices = MutableStateFlow(InvoicesR14())
    val invoices: StateFlow<InvoicesR14> = _invoices

    // ═══════════════ المخزون (F11..F14) ═══════════════

    data class MatrixUI(
        val counts: Map<String, Int>,        // خلية → عدد
        val treasures: List<String>,         // أسماء خلايا AX (حد أقصى 3)
        val burdens: List<String>,           // أسماء خلايا CZ/ CY (حد أقصى 3)
    )

    data class ShrinkUI(
        val lostValue: Double,
        val pctOfCogs: Double?,
        val worstNames: List<String>,
    )

    data class BandUI(
        val bands: BandMath.Bands,
        val worstNames: List<String>,        // أسوأ الحزمة الهزيلة (حد أقصى 3)
    )

    data class InventoryR14(
        val matrix: MatrixUI? = null,            // F11 مصفوفة ABC×XYZ
        val shrink: ShrinkUI? = null,            // F12 تسرّب المخزون
        val hygiene: CatalogHygieneMath.Hygiene? = null,   // F13 نظافة الكتالوج
        val bands: BandUI? = null,               // F14 أحزمة الهامش
    )

    private val _inventory = MutableStateFlow(InventoryR14())
    val inventory: StateFlow<InventoryR14> = _inventory

    // ═══════════════ التقارير (F15..F17) ═══════════════

    data class BordaUI(val names: List<Pair<String, Int>>)   // (اسم، نقاط)

    data class MoverUI(val name: String, val delta: Int)

    data class ReportsR14(
        val borda: BordaUI? = null,          // F15 الترتيب المركّب (بورا)
        val movers: Pair<List<MoverUI>, List<MoverUI>>? = null,   // F16 صاعدو/هابطو الرتب
        val blend: BlendMath.Blend? = null,  // F17 تنبؤ أسبوعين قادمين موزون بالخطأ
    )

    private val _reports = MutableStateFlow(ReportsR14())
    val reports: StateFlow<ReportsR14> = _reports

    // ═══════════════ الذمم (F18) ═══════════════

    data class DebtsR14(
        val staleness: StalenessMath.Age? = null,   // F18 عمر الذمم المرجّح
    )

    private val _debts = MutableStateFlow(DebtsR14())
    val debts: StateFlow<DebtsR14> = _debts

    // ═══════════════ الشيكات (F19) ═══════════════

    data class ChecksR14(
        val terms: GapMath.Terms? = null,   // F19 أفق تأجيل الشيكات
    )

    private val _checks = MutableStateFlow(ChecksR14())
    val checks: StateFlow<ChecksR14> = _checks

    // ═══════════════ المصروفات (F20) ═══════════════

    data class ExpensesR14(
        val reserve: FixedReserveMath.Reserve? = null,   // F20 احتياطي الثوابت اليومي
    )

    private val _expenses = MutableStateFlow(ExpensesR14())
    val expenses: StateFlow<ExpensesR14> = _expenses

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
        val today = TimeMath.startOfDay(now) / day
        try {
            val saleInvoices = g.db.invoices().allOnce().filter { it.type == 0 && it.status < 3 }
            val products = g.inventory.products().first().filter { !it.archived }
            val prodName = products.associate { it.id to it.name }
            val prodCat = products.associate { it.id to it.category }
            val prodCost = products.associate { it.id to it.costPrice }
            val parties = g.db.parties().allOnce()
            val partyName = parties.associate { it.id to it.name }
            val saleLines90 = g.db.invoiceItems().saleLinesSince(now - 90 * day)
            // التحصيل النقدي يستثني DEBT وCHECK_BOUNCE دائماً
            val cashIn90 = g.db.payments().since(now - 90 * day)
                .filter { it.direction == 0 && it.method != "DEBT" && it.method != "CHECK_BOUNCE" }

            fun marginPctOf(inv: com.superbiz.app.data.db.Invoice): Double? {
                val net = inv.subtotal - inv.discount
                if (net <= 0L) return null   // [P33-P8] مساواة صحيحة تامة بدل 0.0
                // [P33-P8] القروش صحيحة — الهامش نسبة بقسمة عائمة (لا قسمة صحيحة)
                return (net - inv.costTotal).toDouble() / net.toDouble() * 100.0
            }

            // [P7-L5 إصلاح]: كان إيراد 60 يوماً يُمسح مرتين في كل جولة loadAll —
            // مرة للوينسور (F1) ومرة لاختبار الجولات (F9) بنفس التعبير الحرفي
            // (O(60×n) فوق saleInvoices كاملة في كل مرة). الآن تُحسب مرة واحدة
            // عند أول استهلاك وتُعاد نفس القائمة حرفياً في الموضع الثاني،
            // وبلا أي تغيير في دلالة النافذة (today مثبت أعلى جولة loadAll).
            // الطريقة lazily-computed-once تحفظ عزل الأقسام: لو فشل القسم الأول
            // قبل التخزين يعيد الثاني الحساب كما كان يفعل سابقاً.
            var daily60Cache: List<Double>? = null
            fun daily60Once(): List<Double> {
                daily60Cache?.let { return it }
                // [P33-P8] إيراد يومي قروش → ريال (المتوسط الوينسوري يُعرض مبلغًا)
                val d60 = (59 downTo 0).map { back ->
                    val d = today - back
                    Money.fromPiasters(saleInvoices.filter { TimeMath.startOfDay(it.date) / day == d }.sumOf { it.total })
                }
                daily60Cache = d60
                return d60
            }

            // ═══ الرئيسية ═══
            try {
                // F1 المتوسط اليومي المتين: إيراد آخر 60 يوماً عبر قصّ وينسور
                // [P7-L5 إصلاح]: الحساب عبر daily60Once (مسح واحد لكل جولة — انظر أعلاه)
                val daily60 = daily60Once()
                _home.value = _home.value.copy(winsor = WinsorMath.winsorizedMean(daily60, 5.0))
            } catch (e: Exception) { Error(e, "winsor") }

            try {
                // F2 مثابرة الاتجاه: أس هيرست على إيراد 16 أسبوعاً
                val weekly = weeklyRevenue(saleInvoices, now, 16)
                _home.value = _home.value.copy(hurst = HurstMath.hurst(weekly))
            } catch (e: Exception) { Error(e, "hurst") }

            try {
                // F3 هل تغيّر التوزيع؟ فواتير آخر 30 يوماً مقابل سابقتها (ك-س)
                // [P33-P8] قروش → ريال عند حدود الخوارزمية (إحصاءة ك-س نسبية)
                val a = saleInvoices.filter { it.date >= now - 30 * day }.map { Money.fromPiasters(it.total) }
                val b = saleInvoices.filter { it.date in (now - 60 * day) until (now - 30 * day) }.map { Money.fromPiasters(it.total) }
                _home.value = _home.value.copy(ks = KsMath.ksStatistic(a, b))
            } catch (e: Exception) { Error(e, "ks") }

            try {
                // F4 غطاء التزامات 30 يوماً: نقد + متوقع مقابل أقساط وشيكات وإنفاق مستحق
                // [P20-FIX agent9]: الاتجاه كان مقلوباً — direction 0 = شيك وارد (تحصيل) لا التزام،
                // والأقساط بلا join باتجاه الخطة تحسب مطالبات الزبائن كالتزامات (نفس صنف P6-M23
                // المُصلَح في R12-F17/F18). التزامات = شيكات صادرة (1) + أقساط خطط موردين (اتجاه 1).
                val horizon = (today + 30) * day
                val planDirection = g.db.installments().plansOnce().associate { it.id to it.direction }
                // [P33-P8] المبالغ قروش: عتبة المفتوح مساواة تامة > 0L بدل 0.004،
                // والمجاميع تُحوَّل ريالاً (الإيجاب والنقص يُعرضان مبلغًا)
                val instDue = Money.fromPiasters(g.db.installments().allInstallments()
                    .filter { it.open > 0L && it.dueDate >= today * day && it.dueDate <= horizon && planDirection[it.planId] == 1 }
                    .sumOf { it.open })
                val checksDue = Money.fromPiasters(g.db.checks().dueBetween(today * day, horizon)
                    .filter { it.direction == 1 }.sumOf { it.amount })
                val spendNext = Money.fromPiasters(g.db.expenses().between(now - 30 * day, now).sumOf { it.amount })
                val expected = Money.fromPiasters(cashIn90.sumOf { it.amount }) / 90.0 * 30.0
                _home.value = _home.value.copy(
                    cover = CoverageMath.obligationCoverage(
                        Money.fromPiasters(g.reports.cashBalance()), expected, listOf(instDue, checksDue, spendNext),
                    ),
                )
            } catch (e: Exception) { Error(e, "cover") }

            // ═══ نقاط البيع ═══
            try {
                val invs90 = saleInvoices.filter { it.date >= now - 90 * day }
                val margins = invs90.mapNotNull { marginPctOf(it) }
                // F5 كسر الخصم تعادله: متوسط الهامش الفعلي مقابل متوسط خصم الأسطر المخصومة
                val dLines = g.db.invoiceItems().discountedSaleLinesSince(now - 90 * day)
                val discPcts = dLines.mapNotNull { l ->
                    val base = l.qty * l.unitPrice
                    if (base > 0.0) l.discount / base * 100.0 else null
                }
                if (margins.size >= 3 && discPcts.isNotEmpty()) {
                    val m = margins.sum() / margins.size
                    val d = discPcts.sum() / discPcts.size
                    _pos.value = _pos.value.copy(campaign = CampaignMath.requiredUplift(m, d))
                }
            } catch (e: Exception) { Error(e, "campaign") }

            try {
                // F6 هامش القناتين: الفواتير المحصّلة كلياً مقابل ما زال عليها مفتوح (90 يوماً)
                val invs90 = saleInvoices.filter { it.date >= now - 90 * day }
                // [P33-P8] المفتوح قروش — مساواة صحيحة تامة بدل عتبة 0.004
                val settled = invs90.filter { it.open <= 0L }.mapNotNull { marginPctOf(it) }
                val open = invs90.filter { it.open > 0L }.mapNotNull { marginPctOf(it) }
                _pos.value = _pos.value.copy(marginMix = MarginMixMath.marginBySettlement(settled, open))
            } catch (e: Exception) { Error(e, "marginMix") }

            try {
                // F7 تراجع منحنى النقد: صافي التدفق اليومي (تحصيل − مصروفات − مدفوعات صادرة) 60 يوماً
                val cashOut = g.db.payments().since(now - 60 * day)
                    .filter { it.direction == 1 && it.method != "DEBT" }
                val exps60 = g.db.expenses().between(now - 60 * day, now)
                // [P33-P8] التدفقات قروش → ريال يومياً (التراجع نسبي لكن السلسلة بمقياس الخوارزمية)
                val net60 = (59 downTo 0).map { back ->
                    val d = today - back
                    val din = Money.fromPiasters(cashIn90.filter { TimeMath.startOfDay(it.date) / day == d }.sumOf { it.amount })
                    val dout = Money.fromPiasters(
                        cashOut.filter { TimeMath.startOfDay(it.date) / day == d }.sumOf { it.amount } +
                            exps60.filter { TimeMath.startOfDay(it.date) / day == d }.sumOf { it.amount },
                    )
                    din - dout
                }
                _pos.value = _pos.value.copy(drawdown = DrawdownMath.maxDrawdown(net60))
            } catch (e: Exception) { Error(e, "drawdown") }

            // ═══ الفواتير ═══
            try {
                // F8 توزيع أحدث دفعة نقدية على فواتير المفتوح (أقدم استحقاق أولاً)
                val latest = g.db.payments().since(now - 30 * day)
                    .filter { it.direction == 0 && it.method == "CASH" && it.partyId != null }
                    .maxByOrNull { it.date }
                // [P33-P8] المبالغ قروش: عتبات مساواة تامة (> 0L) والتحويل ريالاً عند حدود
                // الخوارزمية القائمة — التوزيع يُعرض مبلغًا (الدفع/المخصّص/غير المخصّص)
                if (latest != null && latest.amount > 0L) {
                    val openInvs = saleInvoices
                        .filter { it.partyId == latest.partyId && it.open > 0L }
                    if (openInvs.isNotEmpty()) {
                        val alloc = FairShareMath.applyPayment(
                            openInvs.map { FairShareMath.OpenInv(it.id, Money.fromPiasters(it.open), it.dueDate) },
                            Money.fromPiasters(latest.amount),
                        )
                        if (alloc != null) {
                            val numById = openInvs.associate { it.id to it.number }
                            _invoices.value = _invoices.value.copy(
                                waterfall = WaterfallUI(
                                    partyName = partyName[latest.partyId] ?: "—",
                                    payment = Money.fromPiasters(latest.amount),   // [P33-P8] قروش → ريال (تُعرض مبلغًا)
                                    rows = alloc.allocations.mapNotNull { a ->
                                        val nm = numById[a.id] ?: return@mapNotNull null
                                        nm to a.amount
                                    },
                                    unallocated = alloc.unallocated,
                                    closedCount = alloc.closedCount,
                                ),
                            )
                        }
                    }
                }
            } catch (e: Exception) { Error(e, "waterfall") }

            try {
                // F9 اختبار الجولات: هل الإيراد اليومي (60 يوماً) عناقيد أم متناوب؟
                // [P7-L5 إصلاح]: نفس سلسلة F1 معاد استخدامها بدل المسح الثاني
                _invoices.value = _invoices.value.copy(runs = RunsMath.runsTest(daily60Once()))
            } catch (e: Exception) { Error(e, "runs") }

            try {
                // F10 عدد الفئات الفعّال: إنتروبي حصص الفئات من إيراد 90 يوماً
                val byCat = saleLines90.groupBy { l -> prodCat[l.productId]?.ifBlank { "—" } ?: "—" }
                    .mapValues { (_, ls) -> ls.sumOf { it.qty * it.unitPrice } }
                    .filterValues { it > 0.0 }
                _invoices.value = _invoices.value.copy(entropy = EntropyMath.effectiveCategories(byCat.values.toList()))
            } catch (e: Exception) { Error(e, "entropy") }

            // ═══ المخزون ═══
            try {
                // F11 مصفوفة ABC×XYZ: إيراد 90 يوماً × تقلب الكميات الأسبوعية (12 أسبوعاً)
                val revByProduct = saleLines90.groupBy { it.productId }
                    .mapValues { (_, ls) -> ls.sumOf { it.qty * it.unitPrice } }
                    .filterValues { it > 0.0 }
                val weekOf = { t: Long -> ((now - t) / (7 * day)).toInt().coerceIn(0, 11) }
                val items = revByProduct.map { (pid, rev) ->
                    val qtys = List(12) { w -> 0.0 }.toMutableList()
                    saleLines90.filter { it.productId == pid }.forEach { l ->
                        val w = weekOf(l.date)
                        qtys[11 - w] += l.qty
                    }
                    AbcXyzMath.Item(pid, rev, qtys)
                }
                val mx = AbcXyzMath.matrix(items)
                if (mx != null) {
                    fun namesOf(cells: Set<String>) = mx.rows
                        .filter { it.cell in cells }
                        .mapNotNull { r -> prodName[r.id]?.takeIf { n -> n.isNotBlank() } }
                        .take(3)
                    _inventory.value = _inventory.value.copy(
                        matrix = MatrixUI(
                            counts = mx.cellCounts,
                            treasures = namesOf(setOf("AX")),
                            burdens = namesOf(setOf("CZ", "CY")),
                        ),
                    )
                }
            } catch (e: Exception) { Error(e, "abcxyz") }

            try {
                // F12 تسرّب المخزون: حركات ADJUST السالبة 90 يوماً مقابل تكلفة المبيعات
                val moves = g.db.stockMoves().allMoves()
                    .filter { it.reason == "ADJUST" && it.qty < 0.0 && it.date >= now - 90 * day }
                // [P33-P8] التكلفة/COGS قروش → ريال (قيمة التسرّب تُعرض مبلغًا)
                val cogs90 = saleInvoices.filter { it.date >= now - 90 * day }.sumOf { it.costTotal }
                val sh = ShrinkageMath.shrinkage(
                    moves.mapNotNull { m ->
                        val cost = prodCost[m.productId] ?: return@mapNotNull null
                        ShrinkageMath.Move(m.productId, m.qty, Money.fromPiasters(cost))
                    },
                    Money.fromPiasters(cogs90),
                )
                if (sh != null) {
                    _inventory.value = _inventory.value.copy(
                        shrink = ShrinkUI(
                            lostValue = sh.lostValue,
                            pctOfCogs = sh.pctOfCogs,
                            worstNames = sh.worst.mapNotNull { w ->
                                prodName[w.productId]?.takeIf { n -> n.isNotBlank() }
                            },
                        ),
                    )
                }
            } catch (e: Exception) { Error(e, "shrink") }

            try {
                // F13 نظافة الكتالوج: باركود/فئة/تكلفة/سعر/مخزون سالب
                // [P33-P8] التكلفة/السعر قروش → ريال عند حدود الفحص القائم
                val h = CatalogHygieneMath.hygiene(
                    products.map { CatalogHygieneMath.P(it.id, it.barcode, it.category, Money.fromPiasters(it.costPrice), Money.fromPiasters(it.salePrice), it.stockQty) },
                )
                _inventory.value = _inventory.value.copy(hygiene = h)
            } catch (e: Exception) { Error(e, "hygiene") }

            try {
                // F14 أحزمة الهامش: متوسط هامش السطر لكل منتج مبيوع (90 يوماً)
                val marginsByProduct = saleLines90.groupBy { it.productId }.mapNotNull { (pid, ls) ->
                    val pid1 = pid ?: return@mapNotNull null
                    val cost = prodCost[pid1] ?: return@mapNotNull null
                    val pcts = ls.mapNotNull { l ->
                        // [P33-P8] السعر/التكلفة قروش — الهامش نسبة بقسمة عائمة لا صحيحة
                        if (l.unitPrice > 0L) (l.unitPrice - cost).toDouble() / l.unitPrice.toDouble() * 100.0 else null
                    }
                    if (pcts.isEmpty()) null else BandMath.M(pid1, pcts.sum() / pcts.size)
                }
                val bd = BandMath.marginBands(marginsByProduct)
                if (bd != null) {
                    val nm = { id: Long -> prodName[id]?.takeIf { n -> n.isNotBlank() } }
                    _inventory.value = _inventory.value.copy(
                        bands = BandUI(bd, bd.belowThin.mapNotNull(nm).take(3)),
                    )
                }
            } catch (e: Exception) { Error(e, "bands") }

            // ═══ التقارير ═══
            try {
                // F15 الترتيب المركّب (بورا): أفضل 6 منتجات بالإيراد مرتبة أيضاً بالكمية وبقيمة الهامش
                val byProd = saleLines90.groupBy { it.productId }
                val rev = byProd.mapValues { (_, ls) -> ls.sumOf { it.qty * it.unitPrice } }
                val qty = byProd.mapValues { (_, ls) -> ls.sumOf { it.qty } }
                val mval = byProd.mapValues { (pid, ls) ->
                    // [P33-P8] قروش — قيمة الهامش للترتيب النسبي فقط (Long−Double→Double بلا قسمة صحيحة)
                    val cost = (prodCost[pid] ?: 0L).toDouble()
                    ls.sumOf { (it.unitPrice - cost) * it.qty }
                }
                val top6 = rev.entries.filter { it.value > 0.0 }
                    .sortedByDescending { it.value }.take(6).map { it.key }
                if (top6.size >= 3) {
                    val sortIds = { m: Map<Long, Double> -> top6.sortedByDescending { m[it] ?: 0.0 } }
                    val br = BordaMath.bordaRank(listOf(top6, sortIds(qty), sortIds(mval)))
                    if (br != null) {
                        _reports.value = _reports.value.copy(
                            borda = BordaUI(
                                br.order.mapNotNull { b ->
                                    val nm = prodName[b.id] ?: return@mapNotNull null
                                    nm to b.score
                                },
                            ),
                        )
                    }
                }
            } catch (e: Exception) { Error(e, "borda") }

            try {
                // F16 صاعدو وهابطو الرتب: إيراد المنتج 30 يوماً مقابل السابقة
                val revOf = { from: Long, to: Long ->
                    saleLines90.filter { it.date in from until to }
                        .groupBy { it.productId }
                        .mapValues { (_, ls) -> ls.sumOf { it.qty * it.unitPrice } }
                        .filterValues { it > 0.0 }
                }
                val mv = MoverMath.rankMovers(revOf(now - 60 * day, now - 30 * day), revOf(now - 30 * day, now))
                if (mv != null) {
                    val nm = { id: Long -> prodName[id]?.takeIf { n -> n.isNotBlank() } }
                    _reports.value = _reports.value.copy(
                        movers = Pair(
                            mv.climbers.mapNotNull { c -> nm(c.id)?.let { MoverUI(it, c.delta) } },
                            mv.fallers.mapNotNull { f -> nm(f.id)?.let { MoverUI(it, f.delta) } },
                        ),
                    )
                }
            } catch (e: Exception) { Error(e, "movers") }

            try {
                // F17 تنبؤ مركّب موزون بالخطأ: 16 أسبوعاً → الأسابيع الأربعة القادمة
                val weekly = weeklyRevenue(saleInvoices, now, 16)
                _reports.value = _reports.value.copy(blend = BlendMath.errorWeightedForecast(weekly, 4))
            } catch (e: Exception) { Error(e, "blend") }

            // ═══ الذمم ═══
            try {
                // F18 عمر الذمم المرجّح: (المفتوح، العمر) لكل فاتورة مفتوحة
                // [P33-P8] المفتوح قروش → ريال (وزن نسبي) والعتبة مساواة تامة > 0L
                val ages = saleInvoices.filter { it.open > 0L }
                    .map { Money.fromPiasters(it.open) to (today - TimeMath.startOfDay(it.date) / day) }
                _debts.value = _debts.value.copy(staleness = StalenessMath.weightedAge(ages))
            } catch (e: Exception) { Error(e, "staleness") }

            // ═══ الشيكات ═══
            try {
                // F19 أفق التأجيل: (استحقاق − إصدار) للشيكات الواردة غير الملغاة/المرتجعة
                val gaps = g.db.checks().allOnce()
                    .filter { it.direction == 0 && it.status in 0..2 }
                    .map { ((it.dueDate - it.issueDate) / day).toDouble() }
                _checks.value = _checks.value.copy(terms = GapMath.checkTermsGap(gaps))
            } catch (e: Exception) { Error(e, "terms") }

            // ═══ المصروفات ═══
            try {
                // F20 احتياطي الثوابت اليومي: الفئات الثابتة (تكرار ≥3 أشهر) متوسطاً شهرياً
                val exps90 = g.db.expenses().between(now - 90 * day, now)
                // [P33-P8] المصروفات قروش → ريال (الاحتياطي اليومي يُعرض مبلغًا)
                val total90 = Money.fromPiasters(exps90.sumOf { it.amount })
                val byCat = exps90.groupBy { it.category.ifBlank { "—" } }
                    .mapValues { (_, es) ->
                        Money.fromPiasters(es.sumOf { it.amount }) to
                            es.map { TimeMath.startOfDay(it.date) / (30 * day) }.distinct().size
                    }
                val split = ExpenseMath.fixedVariableSplit(byCat, 3)
                if (split != null && total90 > 0.0) {
                    val fixedMonthly = split.fixedSharePct / 100.0 * total90 / 3.0
                    _expenses.value = _expenses.value.copy(
                        reserve = FixedReserveMath.dailyFixedReserve(
                            fixedMonthly, 24, Money.fromPiasters(cashIn90.sumOf { it.amount }) / 90.0,
                        ),
                    )
                }
            } catch (e: Exception) { Error(e, "reserve") }
        } catch (e: Exception) {
            com.superbiz.app.core.ErrorCenter.warn("R14Insights", "loadAll: ${e.message}")
        }
    }

    // ═══════════════ أدوات زمنية محلية (حتمية، بلا وقت حقيقي داخل الخوارزميات) ═══════════════

    private fun weeklyRevenue(sales: List<com.superbiz.app.data.db.Invoice>, now: Long, weeks: Int): List<Double> =
        (0 until weeks).map { w ->
            val from = now - (weeks - w) * 7 * 86_400_000L
            val to = from + 7 * 86_400_000L
            // [P33-P8] مجاميع أسبوعية قروش → ريال (التنبؤ يُعرض أرقامًا ريالية كما كان)
            Money.fromPiasters(sales.filter { it.date in from until to }.sumOf { it.total })
        }

    // [P7-L6 إصلاح] توثيق — بلا تغيير كود: فحص الموجة الرابعة لأيام UTC في هذا الملف
    // وجد أن فهارس الأيام/الأسابيع كلها تُبنى عبر TimeMath.startOfDay(ts) / 86400000
    // (بداية اليوم **المحلي** ثم قسمة) وبنفس الأسلوب على طرفي المقارنة
    // (today وكل فاتورة/دفعة/مصروف) — فلا أيام UTC مبنية من الطابع الخام ولا خلط
    // بين الفهرسين. الأمر منجَل هنا ولا يحتاج محاذاة.

    private fun Error(e: Exception, tag: String) {
        com.superbiz.app.core.ErrorCenter.warn("R14Insights/$tag", "${e::class.simpleName}: ${e.message}")
    }
}
