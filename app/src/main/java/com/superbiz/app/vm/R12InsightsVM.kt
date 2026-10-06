package com.superbiz.app.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.superbiz.app.AppGraph
import com.superbiz.app.domain.algo.AnomalyMath
import com.superbiz.app.domain.algo.AuditMath
import com.superbiz.app.domain.algo.CartMath
import com.superbiz.app.domain.algo.CatalogMath
import com.superbiz.app.domain.algo.CompareMath
import com.superbiz.app.domain.algo.CostMath
import com.superbiz.app.domain.algo.DunningMath
import com.superbiz.app.domain.algo.HourMath
import com.superbiz.app.domain.algo.PayoffMath
import com.superbiz.app.domain.algo.PaymentMath
import com.superbiz.app.domain.algo.QuartileMath
import com.superbiz.app.domain.algo.ShareMath
import com.superbiz.app.domain.algo.TimeMath
import com.superbiz.app.domain.algo.TurnMath
import com.superbiz.app.domain.algo.UpliftMath
import com.superbiz.app.domain.algo.VoidMath
import com.superbiz.app.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * — VM الرؤى الذكية للموجة R12: يحسب 20 ميزة جديدة
 * من بيانات حقيقية عبر خوارزميات R12Smart، بعقد الصدق نفسه
 * - بلا بيانات ⇒ null/قوائم فارغة تُخفى في الواجهة، لا أرقام مزيّفة.
 * - كل قسم مستقل بـ try/catch يمرر عبر ErrorCenter ولا يُسقط بقية الأقسام.
 * - استعلامات موجودة/مضافة توازياً فقط — لا تغيير في مخطط قاعدة البيانات (v4).
 *
 * توزيع الميزات على 8 شاشات
 * الرئيسية F1..F4 · نقاط البيع F5..F6 · الفواتير F7..F9 · المخزون F10..F13
 * التقارير F14..F16 · الذمم F17..F18 · الشيكات F19 · المصروفات F20
*/
class R12InsightsVM(app: Application) : AndroidViewModel(app) {

    private val g = AppGraph.from(app)

    private val refreshKey = MutableStateFlow(0)
    fun refresh() { refreshKey.value++ }

    // [P39-M4-10] علم الجاهزية — عقد R9/R10 نفسه: false أثناء أول جولة حساب ثم true
    // إلى الأبد (التحديث اليدوي لا يعيده false — لا وميض سكيلتون عند refresh)
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    // ═══════════════ الرئيسية (F1..F4) ═══════════════

    data class HomeR12(
        val compare: CompareMath.PeriodCompare? = null,  // F1 مقارنة الشهر بالسابق وبالسنة الماضية
        val mix: PaymentMath.Mix? = null,                // F2 مزيج طرق التحصيل (90 يوماً)
        val ewma: AnomalyMath.EwmaBands? = null,         // F3 استقرار الإيراد الأسبوعي (EWMA)
        val turn: TurnMath.Turnover? = null,             // F4 دوران المخزون المُسنَد
    )

    private val _home = MutableStateFlow(HomeR12())
    val home: StateFlow<HomeR12> = _home

    // ═══════════════ نقاط البيع (F5..F6) ═══════════════

    data class PosR12(
        val span: HourMath.ActiveSpan? = null,   // F5 نافذة ساعات النشاط
        val cart: CartMath.CartTrend? = null,    // F6 اتجاه حجم السلة
    )

    private val _pos = MutableStateFlow(PosR12())
    val pos: StateFlow<PosR12> = _pos

    // ═══════════════ الفواتير (F7..F9) ═══════════════

    data class InvoicesR12(
        val voids: VoidMath.VoidReport? = null,                 // F7 اتجاه إلغاء الفواتير
        val overpaid: List<AuditMath.Overpaid> = emptyList(),   // F8 فواتير مسددة زيادة
        val timing: PaymentMath.Timing? = null,                 // F9 انتظام التحصيل مقابل الاستحقاق
    )

    private val _invoices = MutableStateFlow(InvoicesR12())
    val invoices: StateFlow<InvoicesR12> = _invoices

    // ═══════════════ المخزون (F10..F13) ═══════════════

    data class SellThroughUI(val name: String, val soldPct: Double, val verdict: String)

    data class InventoryR12(
        val quartiles: List<QuartileMath.QRow> = emptyList(),      // F10 أرباع إيراد المنتجات
        val neverSold: List<CatalogMath.CatItem> = emptyList(),    // F11 منتجات لم تُبع أبداً
        val stale: List<SellThroughUI> = emptyList(),              // F12 أبطأ الواردات بيعاً
        val gaps: List<CatalogMath.CatGap> = emptyList(),          // F13 فئات بلا مبيعات
    )

    private val _inventory = MutableStateFlow(InventoryR12())
    val inventory: StateFlow<InventoryR12> = _inventory

    // ═══════════════ التقارير (F14..F16) ═══════════════

    data class UpliftUI(val name: String, val verdict: String, val delta: Double)

    data class ReportsR12(
        val creep: CostMath.Creep? = null,                       // F14 زحف تكلفة الشراء
        val uplifts: List<UpliftUI> = emptyList(),               // F15 تفكيك أثر تغيير السعر
        val drift: ShareMath.ShareDrift? = null,                 // F16 انجراف تركّز العملاء
    )

    private val _reports = MutableStateFlow(ReportsR12())
    val reports: StateFlow<ReportsR12> = _reports

    // ═══════════════ الذمم (F17..F18) ═══════════════

    data class DebtsR12(
        val payoff: PayoffMath.Payoff? = null,      // F17 أشهر تسوية الأقساط المتبقية
        val dunning: DunningMath.Dunning? = null,   // F18 سلّم مطالبة الذمم
    )

    private val _debts = MutableStateFlow(DebtsR12())
    val debts: StateFlow<DebtsR12> = _debts

    // ═══════════════ الشيكات (F19) ═══════════════

    data class ChecksR12(
        val orphans: List<AuditMath.OrphanRow> = emptyList(), // F19 مدفوعات تشير لفواتير مفقودة
    )

    private val _checks = MutableStateFlow(ChecksR12())
    val checks: StateFlow<ChecksR12> = _checks

    // ═══════════════ المصروفات (F20) ═══════════════

    data class ExpensesR12(
        val cusum: AnomalyMath.Cusum? = null,   // F20 انزياح منحنى المصروفات (CUSUM)
    )

    private val _expenses = MutableStateFlow(ExpensesR12())
    val expenses: StateFlow<ExpensesR12> = _expenses

    // ═══════════════ التحميل ═══════════════

    init {
        viewModelScope.launch {
            loadAll()
            _ready.value = true // [P39-M4-10]: اكتملت الجولة الأولى — السكيلتون يفسح للمحتوى
            refreshKey.collect { if (it > 0) loadAll() }
        }
    }

    // [P6-M18 إصلاح]: كان loadAll كله على الخيط الرئيسي بلا withContext(Default) —
    // 8 نسخ × ~20 ميزة × مسوحات غير محدودة تعني تجميداً مرئياً مع نمو البيانات.
    // النقل الكامل إلى withContext(Dispatchers.Default) بنمط R13/R14InsightsVM
    // (نفس علاج R12-C20 لـDataHealthVM)؛ والتحديثات على StateFlow آمنة من أي خيط.
    private suspend fun loadAll() = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val today = TimeMath.startOfDay(now) / day
        try {
            val saleInvoices = g.db.invoices().allOnce().filter { it.type == 0 && it.status < 3 }
            val allInvoices = g.db.invoices().allOnce()
            val products = g.inventory.products().first().filter { !it.archived }
            val prodName = products.associate { it.id to it.name }
            val prodCat = products.associate { it.id to it.category }

            // ═══ الرئيسية ═══
            try {
                // F1 مقارنة الفترات: الشهر الحالي مقابل السابق ونفس شهر السنة الماضية
                // [P6-M23 إصلاح]: كان شهراً جزئياً (من أول الشهر حتى الآن) يُقاس ضد شهرين
                // كاملين — DOWN منهجي في أوائل كل شهر. الإقصاء الأصدق: لا مقارنة إلا
                // إذا اكتمل الشهر الحالي (اليوم آخر يوم)، وإلا null فتُخفى البطاقة
                // (مسار null المعتمد في R12Cards عبر compare?.let).
                val (cS, cE) = monthBounds(now)
                val (pS, pE) = monthBounds(shiftMonths(now, -1))
                val (yS, yE) = monthBounds(shiftMonths(now, -12))
                val calNow = Calendar.getInstance().apply { timeInMillis = now }
                val monthComplete = calNow.get(Calendar.DAY_OF_MONTH) == calNow.getActualMaximum(Calendar.DAY_OF_MONTH)
                val compare = if (monthComplete) {
                    val cur = saleInvoices.filter { it.date in cS..cE }.sumOf { it.total }
                    val prev = saleInvoices.filter { it.date in pS..pE }.sumOf { it.total }
                    val year = saleInvoices.filter { it.date in yS..yE }.sumOf { it.total }
                    // [P33-P8] مجاميع الأشهر قروش Long — ريال لحد periodCompare الريالي (نسبه من متجانسات)
                    CompareMath.periodCompare(Money.fromPiasters(cur), Money.fromPiasters(prev), Money.fromPiasters(year))
                } else null
                _home.value = _home.value.copy(compare = compare)
            } catch (e: Exception) { Error(e, "compare") }

            try {
                // F2 مزيج طرق التحصيل: دفعات واردة آخر 90 يوماً حسب الطريقة
                // DEBT ليس تحصيلاً (ولادة ذمة جديدة) وCHECK_BOUNCE استرداد
                // وليس قبضاً — كانا يمرران في «أخرى» فيشوّهان المزيج
                val rec = g.db.payments().since(now - 90 * day)
                    .filter { it.direction == 0 && it.method != "DEBT" && it.method != "CHECK_BOUNCE" }
                // [P33-P8] مبالغ الدفعات قروش Long — ريال لحد mix (حصص مئوية من متجانسات)
                val cash = Money.fromPiasters(rec.filter { it.method == "CASH" }.sumOf { it.amount })
                val check = Money.fromPiasters(rec.filter { it.method == "CHECK" }.sumOf { it.amount })
                val other = Money.fromPiasters(rec.filter { it.method != "CASH" && it.method != "CHECK" }.sumOf { it.amount })
                _home.value = _home.value.copy(mix = PaymentMath.mix(cash, check, other))
            } catch (e: Exception) { Error(e, "mix") }

            try {
                // F3 استقرار الإيراد: 12 أسبوعاً عبر حدود EWMA بخط أساس النصف الأول
                val weekly = weeklyRevenue(saleInvoices, now, 12)
                // [P33-P8] سلسلة أسبوعية قروش Long — مساواة تامة 0L (كانت 0.0) وريال لحد ewmaBands
                // (الإصلاح [P6-M22] محفوظ: أصفار كاملة ⇒ null تُخفى البطاقة)
                _home.value = _home.value.copy(
                    ewma = if (weekly.all { it == 0L }) null else AnomalyMath.ewmaBands(weekly.map { Money.fromPiasters(it) }),
                )
            } catch (e: Exception) { Error(e, "ewma") }

            try {
                // F4 دوران المخزون: تكلفة مبيعات 90 يوماً مقابل قيمة المخزون الحالية (تقريب موثق)
                val cogs90 = saleInvoices.filter { it.date >= now - 90 * day }.sumOf { it.costTotal }
                val stockValue = products.sumOf { it.stockValue }
                // [P33-P8] التكلفة وقيمة المخزون قروش Long — ريال لحد turnover (نسبة دوران متجانسة)
                _home.value = _home.value.copy(turn = TurnMath.turnover(Money.fromPiasters(cogs90), Money.fromPiasters(stockValue), 90))
            } catch (e: Exception) { Error(e, "turnover") }

            // ═══ نقاط البيع ═══
            try {
                // F5 نافذة النشاط: مبيعات 30 يوماً موزعة على ساعات اليوم
                val hours = DoubleArray(24)
                val cal = Calendar.getInstance()
                for (inv in saleInvoices.filter { it.date >= now - 30 * day }) {
                    cal.timeInMillis = inv.date
                    // [P33-P8] إجمالي الفاتورة قروش Long — ريال في نافذة الساعات (حصص نسبية متجانسة)
                    hours[cal.get(Calendar.HOUR_OF_DAY)] += Money.fromPiasters(inv.total)
                }
                _pos.value = _pos.value.copy(span = HourMath.activeSpan(hours))
            } catch (e: Exception) { Error(e, "span") }

            try {
                // F6 حجم السلة: أسطر بيع 8 أسابيع (عدد الأسطر، عدد الفواتير المميزة) أسبوعياً
                val lines = g.db.invoiceItems().saleLinesSince(now - 56 * day)
                val weeks = (0 until 8).map { w ->
                    val from = now - (8 - w) * 7 * day
                    val to = from + 7 * day
                    val wk = lines.filter { it.date in from until to }
                    wk.size.toDouble() to wk.map { it.invoiceId }.distinct().size
                }
                _pos.value = _pos.value.copy(cart = CartMath.cartSizeTrend(weeks))
            } catch (e: Exception) { Error(e, "cart") }

            // ═══ الفواتير ═══
            try {
                // F7 اتجاه الإلغاء: 6 أسابيع (ملغاة، إجمالي) من كل الفواتير بلا استثناء
                val weeks = (0 until 6).map { w ->
                    val from = now - (6 - w) * 7 * day
                    val to = from + 7 * day
                    val wk = allInvoices.filter { it.date in from until to }
                    wk.count { it.status == 3 } to wk.size
                }
                _invoices.value = _invoices.value.copy(voids = VoidMath.voidTrend(weeks))
            } catch (e: Exception) { Error(e, "voids") }

            try {
                // F8 فواتير مسددة زيادة: مدفوع > إجمالي + قرش (فواتير بيع غير الملغاة)
                // [P33-P8] الإجمالي والمسدد قروش Long — ريال لحد overpaidInvoices (عتبة excess ريالية)
                val rows = saleInvoices.map { Triple(it.id, Money.fromPiasters(it.total), Money.fromPiasters(it.paid)) }
                _invoices.value = _invoices.value.copy(overpaid = AuditMath.overpaidInvoices(rows).take(4))
            } catch (e: Exception) { Error(e, "overpaid") }

            try {
                // F9 انتظام التحصيل: دفعات فواتير 90 يوماً مقابل تاريخ استحقاق فاتورتها
                val dueById = allInvoices.associate { it.id to it.dueDate }
                val rel = g.db.payments().since(now - 90 * day).mapNotNull { p ->
                    val due = dueById[p.invoiceId] ?: return@mapNotNull null
                    if (p.direction != 0) return@mapNotNull null
                    ((TimeMath.startOfDay(p.date) / day) - (TimeMath.startOfDay(due) / day)).toInt()
                }
                _invoices.value = _invoices.value.copy(timing = PaymentMath.timing(rel))
            } catch (e: Exception) { Error(e, "timing") }

            // ═══ المخزون ═══
            try {
                val lines = g.db.invoiceItems().saleLinesSince(now - 60 * day)

                // F10 أرباع إيراد المنتجات (60 يوماً)
                val revByProduct = lines.groupBy { it.productId }.mapValues { (_, ls) -> ls.sumOf { it.qty * it.unitPrice } }
                val named = revByProduct.mapNotNull { (pid, rev) ->
                    val name = prodName[pid] ?: return@mapNotNull null
                    if (name.isBlank()) null else name to rev
                }
                _inventory.value = _inventory.value.copy(
                    quartiles = QuartileMath.classify(named) ?: emptyList(),
                )

                // F11 منتجات لم تُبع أبداً
                val soldIds = g.db.invoiceItems().distinctSoldProductIds().toSet()
                // [P33-P8] قيمة المخزون قروش Long — ريال في CatItem (ترتيب رأس المال الراكد متجانس)
                val cats = products.map { CatalogMath.CatItem(it.id, it.name, it.stockQty, Money.fromPiasters(it.stockValue)) }
                _inventory.value = _inventory.value.copy(
                    neverSold = CatalogMath.neverSold(cats, soldIds).take(5),
                )

                // F12 أبطأ الواردات بيعاً: وارد 90 يوماً مقابل مبيعاتها (شركات بطيئة)
                val moves = g.db.stockMoves().allMoves().filter { it.date >= now - 90 * day }
                val received = HashMap<Long, Double>()
                val sold = HashMap<Long, Double>()
                for (m in moves) {
                    when (m.reason) {
                        "PURCHASE" -> received[m.productId] = (received[m.productId] ?: 0.0) + m.qty
                        "SALE" -> sold[m.productId] = (sold[m.productId] ?: 0.0) + (-m.qty)
                    }
                }
                val staleRows = received.mapNotNull { (pid, rec) ->
                    if (rec <= 0.0) return@mapNotNull null
                    val name = prodName[pid] ?: return@mapNotNull null
                    if (name.isBlank()) return@mapNotNull null
                    val st = TurnMath.sellThrough(rec, sold[pid] ?: 0.0) ?: return@mapNotNull null
                    if (st.verdict != "STALE") return@mapNotNull null
                    SellThroughUI(name, st.soldPct, st.verdict)
                }.sortedBy { it.soldPct }.take(3)
                _inventory.value = _inventory.value.copy(stale = staleRows)

                // F13 فئات بلا مبيعات: فئات كتالوج صفر مبيعات داخلها
                val productsByCat = products.groupBy { it.category }.mapValues { (_, ps) -> ps.size }
                val soldByCat = products.filter { it.id in soldIds }.groupBy { it.category }.mapValues { (_, ps) -> ps.size }
                _inventory.value = _inventory.value.copy(
                    gaps = CatalogMath.categoryGaps(productsByCat, soldByCat).take(4),
                )
            } catch (e: Exception) { Error(e, "inventory") }

            // ═══ التقارير ═══
            try {
                // F14 زحف تكلفة الشراء: متوسط تكلفة الوحدة شهرياً من أسطر الشراء (5 أشهر)
                val plines = g.db.invoiceItems().purchaseLinesSince(shiftMonths(now, -5))
                val byMonth = plines.groupBy { monthKey(it.date) }.map { (mk, ls) ->
                    val qty = ls.sumOf { it.qty }
                    if (qty > 0.0) mk to (ls.sumOf { it.qty * it.unitPrice } / qty) else null
                }.filterNotNull().sortedBy { it.first }
                _reports.value = _reports.value.copy(
                    creep = CostMath.costCreep(byMonth.map { it.second }),
                )
            } catch (e: Exception) { Error(e, "creep") }

            try {
                // F15 أثر تغيير السعر: مقارنة نافذتي بيع (120-60 يوم) مقابل (60-0 يوم) لكل منتج
                val lines = g.db.invoiceItems().saleLinesSince(now - 120 * day)
                val old = lines.filter { it.date < now - 60 * day }.groupBy { it.productId }
                val new = lines.filter { it.date >= now - 60 * day }.groupBy { it.productId }
                val rows = ArrayList<UpliftUI>()
                for ((pid, ns) in new) {
                    val os = old[pid] ?: continue
                    val name = prodName[pid] ?: continue
                    if (name.isBlank()) continue
                    val oQty = os.sumOf { it.qty }
                    val nQty = ns.sumOf { it.qty }
                    if (oQty <= 0.0 || nQty <= 0.0) continue
                    val up = UpliftMath.priceUplift(
                        os.sumOf { it.qty * it.unitPrice } / oQty, oQty,
                        ns.sumOf { it.qty * it.unitPrice } / nQty, nQty,
                    ) ?: continue
                    rows += UpliftUI(name, up.verdict, up.revenueDelta)
                }
                _reports.value = _reports.value.copy(
                    uplifts = rows.sortedByDescending { kotlin.math.abs(it.delta) }.take(3),
                )
            } catch (e: Exception) { Error(e, "uplift") }

            try {
                // F16 انجراف التركّز: إيراد لكل طرف في نصف النافذة الأخير مقابل السابق (90 يوماً)
                // [P6-M23 إصلاح]: الزبون النقدي (walk-in) ليس عميلاً — كان يُحتسب طرفاً واحداً
                // فيضخم HHI ويخفي التركّز الحقيقي في النافذتين. يُستبعد صراحةً.
                val walkIn = g.settings.snapshot().walkInPartyId
                val byNow = saleInvoices.filter { it.date >= now - 45 * day && it.partyId != walkIn }
                    .groupBy { it.partyId }
                    // [P33-P8] إيراد الطرف قروش Long — ريال لحد concentrationDrift الريالي
                    .mapValues { (_, invs) -> Money.fromPiasters(invs.sumOf { it.total }) }
                val byBefore = saleInvoices.filter { it.date < now - 45 * day && it.date >= now - 90 * day && it.partyId != walkIn }
                    .groupBy { it.partyId }.mapValues { (_, invs) -> Money.fromPiasters(invs.sumOf { it.total }) }
                _reports.value = _reports.value.copy(
                    drift = ShareMath.concentrationDrift(
                        byNow.mapKeys { (k, _) -> k.toString() },
                        byBefore.mapKeys { (k, _) -> k.toString() },
                    ),
                )
            } catch (e: Exception) { Error(e, "drift") }

            // ═══ الذمم ═══
            try {
                // F17 أشهر التسوية: مفتوح الأقساط الحالي ÷ متوسط سداد شهري فعلي (90 يوماً ÷ 3)
                // [P6-M23 إصلاح]: أقساط الموردين (اتجاه الخطة 1) كانت تدخل المفتوح، والدفعات
                // تُجمع بلا فلتر اتجاه — خلط ما لك بما عليك. مطالبات الزبائن فقط (اتجاه 0)
                // في البسط والمقام.
                val insDirection = g.db.installments().plansOnce().associate { it.id to it.direction }
                // [P33-P8] المفتوح القسطي قروش Long — عتبة تامة 0L (كانت 0.004) ومجموع صحيح
                val open = g.db.installments().allInstallments()
                    .filter { it.open > 0L && insDirection[it.planId] == 0 }
                    .sumOf { it.open }
                val paid90 = g.db.payments().since(now - 90 * day)
                    .filter { it.direction == 0 && it.method == "INSTALLMENT" }.sumOf { it.amount }
                _debts.value = _debts.value.copy(
                    // [P33-P8] البسط والمقام قروش Long — ريال لحد monthsToClear الريالي
                    payoff = PayoffMath.monthsToClear(Money.fromPiasters(open), if (paid90 > 0L) Money.fromPiasters(paid90) / 3.0 else 0.0),
                )
            } catch (e: Exception) { Error(e, "payoff") }

            try {
                // F18 سلّم المطالبة: أقساط مفتوحة + فواتير بيع مفتوحة حسب أيام التأخر
                // [P6-M23 إصلاح]: أقساط الموردين (اتجاه الخطة 1) كانت تدخل سلّم المطالبة
                // بلا join بالاتجاه — ما هو عليك لا يُطالَب به كذمة زبون. أقساط الزبائن
                // (اتجاه 0) فقط، وفواتير البيع مطلبات زبائن بطبيعتها فتبقى.
                val insDirection = g.db.installments().plansOnce().associate { it.id to it.direction }
                // [P33-P8] المفتوح قروش Long — عتبة تامة 0L والصفوف بقروش ثم ريال عند حد DunningMath
                val rows = ArrayList<Pair<Long, Int>>()
                for (ins in g.db.installments().allInstallments().filter { it.open > 0L && insDirection[it.planId] == 0 }) {
                    val days = ((today - TimeMath.startOfDay(ins.dueDate) / day).toInt()).coerceAtLeast(0)
                    rows += ins.open to days
                }
                for (inv in saleInvoices.filter { it.open > 0L }) {
                    val days = ((today - TimeMath.startOfDay(inv.dueDate) / day).toInt()).coerceAtLeast(0)
                    rows += inv.open to days
                }
                _debts.value = _debts.value.copy(dunning = DunningMath.stages(rows.map { Money.fromPiasters(it.first) to it.second }))
            } catch (e: Exception) { Error(e, "dunning") }

            // ═══ الشيكات ═══
            try {
                // F19 مدفوعات يتيمة: دفعات تشير إلى فواتير غير موجودة (بقايا مسارات قديمة)
                val validIds = allInvoices.map { it.id }.toSet()
                // [P33-P8] مبلغ الدفعة قروش Long — ريال لحد orphanPayments الريالي
                val rows = g.db.payments().since(0).map { Triple(it.id, it.invoiceId, Money.fromPiasters(it.amount)) }
                _checks.value = _checks.value.copy(
                    orphans = AuditMath.orphanPayments(rows, validIds).take(5),
                )
            } catch (e: Exception) { Error(e, "orphans") }

            // ═══ المصروفات ═══
            try {
                // F20 انزياح المصروفات: 12 شهراً كاملاً عبر CUSUM (كشف صعود/هبوط مستمر)
                val months = (0 until 12).map { i ->
                    val (s, e) = monthBounds(shiftMonths(now, -(11 - i)))
                    g.db.expenses().between(s, e).sumOf { it.amount }
                }
                // [P33-P8] مجاميع شهرية قروش Long — مساواة تامة 0L (كانت 0.0) وريال لحد cusumShift
                // (الإصلاح [P6-M22] محفوظ: أصفار كاملة ⇒ null تُخفى البطاقة)
                _expenses.value = _expenses.value.copy(
                    cusum = if (months.all { it == 0L }) null else AnomalyMath.cusumShift(months.map { Money.fromPiasters(it) }, 5.0),
                )
            } catch (e: Exception) { Error(e, "cusum") }
        } catch (e: Exception) {
            com.superbiz.app.core.ErrorCenter.warn("R12Insights", "loadAll: ${e.message}")
        }
    }

    // ═══════════════ أدوات زمنية محلية (حتمية، بلا وقت حقيقي داخل الخوارزميات) ═══════════════

    private fun monthBounds(ts: Long): Pair<Long, Long> {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        val s = c.cloneCalendar().apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val e = c.cloneCalendar().apply {
            set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH)); set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
        }.timeInMillis
        return s to e
    }

    private fun shiftMonths(ts: Long, months: Int): Long {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        c.add(Calendar.MONTH, months)
        return c.timeInMillis
    }

    private fun monthKey(ts: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        return c.get(Calendar.YEAR) * 12 + c.get(Calendar.MONTH)
    }

    // [P33-P8] تعيد قروش Long (كانت ريال Double) — تُستهلك عبر fromPiasters عند حدود الاستهلاك
    private fun weeklyRevenue(sales: List<com.superbiz.app.data.db.Invoice>, now: Long, weeks: Int): List<Long> =
        (0 until weeks).map { w ->
            val from = now - (weeks - w) * 7 * 86_400_000L
            val to = from + 7 * 86_400_000L
            sales.filter { it.date in from until to }.sumOf { it.total }
        }

    private fun Calendar.cloneCalendar(): Calendar = (this.clone() as Calendar)

    private fun Error(e: Exception, tag: String) {
        com.superbiz.app.core.ErrorCenter.warn("R12Insights/$tag", "${e::class.simpleName}: ${e.message}")
    }
}