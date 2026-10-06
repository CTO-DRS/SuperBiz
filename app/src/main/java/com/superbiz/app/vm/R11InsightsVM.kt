package com.superbiz.app.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.superbiz.app.AppGraph
import com.superbiz.app.domain.algo.CheckMath
import com.superbiz.app.domain.algo.LiquidityMath
import com.superbiz.app.domain.algo.CurrencyMath
import com.superbiz.app.domain.algo.LoyaltyMath
import com.superbiz.app.domain.algo.ExpenseMath
import com.superbiz.app.domain.algo.InvoiceMath
import com.superbiz.app.domain.algo.InstallmentMath
import com.superbiz.app.domain.algo.PricingAuditMath
import com.superbiz.app.domain.algo.RhythmMath
import com.superbiz.app.domain.algo.ReplenishMath
import com.superbiz.app.domain.algo.StockMath
import com.superbiz.app.domain.algo.TaxMath
import com.superbiz.app.domain.algo.TimeMath
import com.superbiz.app.domain.algo.TrendMath
import com.superbiz.app.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * — VM الرؤى الذكية للموجة R11: يحسب 20 ميزة جديدة
 * من بيانات حقيقية عبر خوارزميات R11Smart، بعقد الصدق نفسه
 * - بلا بيانات ⇒ null/قوائم فارغة تُخفى في الواجهة، لا أرقام مزيّفة.
 * - كل قسم مستقل بـ try/catch يمرر عبر ErrorCenter ولا يُسقط بقية الأقسام.
 * - استعلامات موجودة فقط — لا تغيير في مخطط قاعدة البيانات (v4).
 *
 * توزيع الميزات على 8 شاشات (أول مرة تشمل POS والفواتير)
 * الرئيسية B1..B4 · نقاط البيع B5..B6 · الفواتير B7..B9 · المخزون B10..B11
 * التقارير B12..B14 · الذمم B15..B16 · الشيكات B17..B18 · المصروفات B19..B20
*/
class R11InsightsVM(app: Application) : AndroidViewModel(app) {

    private val g = AppGraph.from(app)

    private val refreshKey = MutableStateFlow(0)
    fun refresh() { refreshKey.value++ }

    // [P39-M4-10] علم الجاهزية — عقد R9/R10 نفسه: false أثناء أول جولة حساب ثم true
    // إلى الأبد (التحديث اليدوي لا يعيده false — لا وميض سكيلتون عند refresh)
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    // ═══════════════ الرئيسية (B1..B4) ═══════════════

    data class HomeR11(
        val runway: LiquidityMath.Runway? = null,        // B1 معدل الحرق وأيام الصمود
        val vat: TaxMath.VatPos? = null,            // B2 موقع الضريبة لهذا الشهر
        val momentum: Double? = null,               // B3 زخم الإيراد (آخر 8 أسابيع)
        val fx: CurrencyMath.FxExposure? = null,    // B4 التعرض للعملات الأجنبية
    )

    private val _home = MutableStateFlow(HomeR11())
    val home: StateFlow<HomeR11> = _home

    // ═══════════════ نقاط البيع (B5..B6) ═══════════════

    data class PosR11(
        val pace: RhythmMath.Pace? = null,          // B5 إيقاع اليوم مقابل معدل يوم الأسبوع
        val leak: PricingAuditMath.LeakReport? = null,     // B6 تسريب الخصم حسب الطرف
    )

    private val _pos = MutableStateFlow(PosR11())
    val pos: StateFlow<PosR11> = _pos

    // ═══════════════ الفواتير (B7..B9) ═══════════════

    data class InvoicesR11(
        val aov: InvoiceMath.AovTrend? = null,          // B7 اتجاه متوسط الفاتورة
        val duplicates: List<InvoiceMath.Suspect> = emptyList(), // B8 اشتباه تكرار
        val drift: TaxMath.Drift? = null,               // B9 انحراف تقريب الرأس
    )

    private val _invoices = MutableStateFlow(InvoicesR11())
    val invoices: StateFlow<InvoicesR11> = _invoices

    // ═══════════════ المخزون (B10..B11) ═══════════════

    data class ReorderUI(val name: String, val stock: Double, val orderQty: Double, val verdict: String)
    data class GmroiUI(val category: String, val gmroiAnnual: Double)

    data class InventoryR11(
        val reorder: List<ReorderUI> = emptyList(),  // B10 خطة إعادة الطلب
        val gmroi: List<GmroiUI> = emptyList(),      // B11 مردود الفئات GMROI
    )

    private val _inventory = MutableStateFlow(InventoryR11())
    val inventory: StateFlow<InventoryR11> = _inventory

    // ═══════════════ التقارير (B12..B14) ═══════════════

    data class ReportsR11(
        val trend: TrendMath.Trend? = null,              // B12 اتجاه الإيراد الشهري + R²
        val marginAudit: PricingAuditMath.MarginAudit? = null,  // B13 تدقيق أرضية الهامش
        val churn: List<ChurnUI> = emptyList(),          // B14 عملاء صامتون معرضون للانصراف
    )

    data class ChurnUI(val name: String, val silenceDays: Long, val score: Int, val verdict: String)

    private val _reports = MutableStateFlow(ReportsR11())
    val reports: StateFlow<ReportsR11> = _reports

    // ═══════════════ الذمم (B15..B16) ═══════════════

    data class SegmentUI(val segment: String, val count: Int)

    data class DebtsR11(
        val delinquency: InstallmentMath.Delinquency? = null, // B15 شرائح تأخر الأقساط
        val segments: List<SegmentUI> = emptyList(),          // B16 شرائح RFM للعملاء
    )

    private val _debts = MutableStateFlow(DebtsR11())
    val debts: StateFlow<DebtsR11> = _debts

    // ═══════════════ الشيكات (B17..B18) ═══════════════

    data class ChecksR11(
        val bounce: CheckMath.BounceReport? = null,  // B17 إحصاء الارتجاع لكل طرف
        val gap: LiquidityMath.GapReport? = null,         // B18 فجوة نقدية 30 يوماً (شيكات+أقساط)
    )

    private val _checks = MutableStateFlow(ChecksR11())
    val checks: StateFlow<ChecksR11> = _checks

    // ═══════════════ المصروفات (B19..B20) ═══════════════

    data class ExpensesR11(
        val budget: ExpenseMath.BudgetReport? = null, // B19 الموازنة الضمنية مقابل الشهر الحالي
        val split: ExpenseMath.Split? = null,         // B20 ثابت/متغير
    )

    private val _expenses = MutableStateFlow(ExpensesR11())
    val expenses: StateFlow<ExpensesR11> = _expenses

    // ═══════════════ التحميل ═══════════════

    init {
        viewModelScope.launch {
            loadAll()
            _ready.value = true // [P39-M4-10]: اكتملت الجولة الأولى — السكيلتون يفسح للمحتوى
            refreshKey.collect { if (it > 0) loadAll() }
        }
    }

    // [P6-M18 إصلاح]: كان loadAll كله على الخيط الرئيسي — التقويم لكل فاتورة في B5/B2،
    // ومسوحات كل الفواتير في عدة أقسام، كلها تجمّد الواجهة مع نمو البيانات.
    // النقل الكامل إلى withContext(Dispatchers.Default) بنمط R13/R14InsightsVM؛
    // والتحديثات على StateFlow آمنة من أي خيط ولا تُحجب الموديلات الحية.
    private suspend fun loadAll() = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        // [P7-L6 إصلاح] توثيق — بلا تغيير كود: فحص الموجة الرابعة لأيام UTC في هذا
        // الملف وجد أن فهرس اليوم الأساس `today` يُبنى ببداية اليوم **المحلي**
        // (TimeMath.startOfDay) ثم قسمة 86,400,000، وكل فهارس الفواتير/الأقساط/
        // الشيكات المقارنة به تُبنى بالأسلوب نفسه — فلا أيام UTC مبنية من الطابع
        // الخام ولا خلط بين الفهرسين. الأمر منجَل هنا ولا يحتاج محاذاة.
        val today = TimeMath.startOfDay(now) / day
        try {
            val saleInvoices = g.db.invoices().allOnce().filter { it.type == 0 && it.status < 3 }
            val partyName = g.db.parties().allOnce().associate { it.id to it.name }
            val settings = g.settings.snapshot()
            val products = g.inventory.products().first().filter { !it.archived }
            val prodName = products.associate { it.id to it.name }
            val prodCost = products.associate { it.id to it.costPrice }
            val prodCat = products.associate { it.id to it.category }

            // ═══ الرئيسية ═══
            try {
                // B1 معدل الحرق وأيام الصمود (نافذة 30 يوماً)
                val m30 = now - 30 * day
                // [P33-P8] مجاميع الدفعات/المصروفات DAO لم تُرحّل بعد (ريال Double فوق أعمدة قروش) —
                // تُثبّت إلى Long قروش ثم ريال عبر fromPiasters لحد burnAndRunway الريالي
                val inflow30 = g.db.payments().receivedBetweenCash(m30, now).toLong()
                val outflow30 = (g.db.payments().paidOutBetween(m30, now) + g.db.expenses().sumBetween(m30, now)).toLong()
                val cash = g.reports.cashBalance()
                _home.value = _home.value.copy(runway = LiquidityMath.burnAndRunway(
                    Money.fromPiasters(cash), Money.fromPiasters(inflow30), Money.fromPiasters(outflow30), 30,
                ))
            } catch (e: Exception) { Error(e, "runway") }

            try {
                // B2 موقع الضريبة لهذا الشهر: ضريبة المخرجات من المبيعات مقابل ضريبة المدخلات من المشتريات
                // (المصروفات بلا ضريبة مستخلصة لا تدخل الحساب — قيد موثق)
                val (mStart, _) = monthBounds(now)
                val salesM = saleInvoices.filter { it.date >= mStart }
                val buysM = g.db.invoices().allOnce().filter { it.type == 1 && it.status < 3 && it.date >= mStart }
                val vat = TaxMath.vatPosition(
                    // [P33-P8] ضرائب/صافي الفواتير قروش Long — ريال لحد vatPosition الريالي (عتباته ريالية)
                    outputTax = Money.fromPiasters(salesM.sumOf { it.taxAmount }),
                    inputTax = Money.fromPiasters(buysM.sumOf { it.taxAmount }),
                    outputNet = Money.fromPiasters(salesM.sumOf { it.subtotal }),
                )
                _home.value = _home.value.copy(vat = vat)
            } catch (e: Exception) { Error(e, "vat") }

            try {
                // B3 زخم الإيراد: مجاميع أسبوعية لآخر 8 أسابيع
                val weekly = weeklyRevenue(saleInvoices, now, 8)
                // [P33-P8] مجاميع أسبوعية قروش Long — ريال لحد momentumPct (نسبة محصّنة من الوحدة)
                _home.value = _home.value.copy(momentum = TrendMath.momentumPct(weekly.map { Money.fromPiasters(it) }))
            } catch (e: Exception) { Error(e, "momentum") }

            try {
                // B4 التعرض للعملات على المفتوح
                val rates = g.db.currencies().allOnce().associate { it.code to it.rateToBase }
                val base = settings.baseCurrency
                val openByCur = saleInvoices.filter { it.open > 0 }
                    .groupBy { if (it.currency.isNotBlank()) it.currency else base }
                    // [P33-P8] المفتوح قروش Long — ريال لحد fxExposure (يقسم على rateToBase)
                    .mapValues { (_, invs) -> Money.fromPiasters(invs.sumOf { it.open }) }
                _home.value = _home.value.copy(fx = CurrencyMath.fxExposure(openByCur, rates, base))
            } catch (e: Exception) { Error(e, "fx") }

            // ═══ نقاط البيع ═══
            try {
                // B5 إيقاع اليوم: مبيعات اليوم مقابل معدل يوم الأسبوع نفسه × نسبة تقدم اليوم
                val dayStart = TimeMath.startOfDay(now)
                val todayTotal = saleInvoices.filter { it.date >= dayStart }.sumOf { it.total }
                val wd = ((Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
                val byDay = saleInvoices.filter { it.date >= now - 56 * day }
                    .groupBy { ((Calendar.getInstance().apply { timeInMillis = it.date }.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1 }
                // [P6-M23 إصلاح]: كان المقام «أيام البيع» ليوم الأسبوع (الأيام التي بيع فيها فقط)
                // — يوم أسبوع صامت يُهمل فيضخم المعدل المتوقع فتظهر وتيرة اليوم بطيئة كذباً.
                // الصادق: القسمة على عدد تكرارات يوم الأسبوع نفسه داخل الأفق الزمني
                // (56 يوماً — الأيام الصفرية تدخل صفراً في المتوسط).
                val horizonStart = TimeMath.startOfDay(now - 56 * day)
                val wdOccurrences = (0L..56L).count { off ->
                    ((Calendar.getInstance().apply { timeInMillis = horizonStart + off * day }.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1 == wd
                }
                val wdAvg = if (wdOccurrences > 0) Money.fromPiasters(byDay[wd]?.sumOf { it.total } ?: 0L) / wdOccurrences else 0.0
                val progress = ((now - dayStart).toDouble() / day * 100.0)
                // [P33-P8] مبيعات اليوم قروش Long — ريال لحد todayPace الريالي
                _pos.value = _pos.value.copy(pace = RhythmMath.todayPace(Money.fromPiasters(todayTotal), wdAvg, progress))
            } catch (e: Exception) { Error(e, "pace") }

            try {
                // B6 تسريب الخصم: نسبة الخصم لكل طرف (90 يوماً) من رؤوس الفواتير
                val since = now - 90 * day
                val rows = saleInvoices.filter { it.date >= since && it.total > 0 && (it.discount > 0 || it.subtotal > 0) }
                    .groupBy { it.partyId }
                    .mapNotNull { (pid, invs) ->
                        val name = partyName[pid] ?: return@mapNotNull null
                        // [P33-P8] الإجمالي والخصم قروش Long — ريال لحد discountLeak (minGross=500 ريال)
                        val gross = Money.fromPiasters(invs.sumOf { it.subtotal })
                        val disc = Money.fromPiasters(invs.sumOf { it.discount })
                        if (name.isBlank()) null else PricingAuditMath.DiscountRow(name, gross, disc)
                    }
                _pos.value = _pos.value.copy(leak = PricingAuditMath.discountLeak(rows, minGross = 500.0, topK = 3))
            } catch (e: Exception) { Error(e, "leak") }

            // ═══ الفواتير ═══
            try {
                // B7 اتجاه متوسط قيمة الفاتورة (8 أسابيع: مجموع، عدد)
                val weeks = (0 until 8).map { w ->
                    val from = now - (8 - w) * 7 * day
                    val to = from + 7 * day
                    val wk = saleInvoices.filter { it.date in from until to }
                    // [P33-P8] مجموع الأسبوع قروش Long — ريال لحد aovTrend الريالي
                    Money.fromPiasters(wk.sumOf { it.total }) to wk.size
                }
                _invoices.value = _invoices.value.copy(aov = InvoiceMath.aovTrend(weeks))
            } catch (e: Exception) { Error(e, "aov") }

            try {
                // B8 اشتباه فواتير مكررة (30 يوماً، نافذة 7 أيام)
                val since = now - 30 * day
                val rows = saleInvoices.filter { it.date >= since && it.total > 0 }
                    // [P33-P8] إجمالي الفاتورة قروش Long — ريال لحد duplicateSuspects (مطابقة 0.005 ريال)
                    .map { InvoiceMath.TxRow(it.partyId, partyName[it.partyId] ?: "", Money.fromPiasters(it.total), TimeMath.startOfDay(it.date) / day) }
                    .filter { it.partyName.isNotBlank() }
                _invoices.value = _invoices.value.copy(duplicates = InvoiceMath.duplicateSuspects(rows, 7))
            } catch (e: Exception) { Error(e, "duplicates") }

            try {
                // B9 انحراف التقريب: (subtotal+tax) مقابل total في رؤوس آخر 60 يوماً
                val since = now - 60 * day
                val rows = saleInvoices.filter { it.date >= since }
                    // [P33-P8] subtotal+tax وtotal قروش Long — الفرق بينهما تام؛ يُطعَمان ريالاً لحد roundingDrift
                    .map { Money.fromPiasters(it.subtotal + it.taxAmount) to Money.fromPiasters(it.total) }
                _invoices.value = _invoices.value.copy(drift = TaxMath.roundingDrift(rows))
            } catch (e: Exception) { Error(e, "drift") }

            // ═══ المخزون ═══
            try {
                val lines = g.db.invoiceItems().saleLinesSince(now - 60 * day)
                val byProduct = lines.groupBy { it.productId }

                // B10 خطة إعادة الطلب: مخزون أمان R9 من تباين الطلب الفعلي + مهلة 7 أيام موثقة
                val plans = ArrayList<ReorderUI>()
                for (p in products) {
                    if (p.stockQty <= 0.0 && p.reorderLevel <= 0.0) continue
                    val daily = byProduct[p.id]?.map { it.qty } ?: emptyList()
                    val dAvg = if (daily.isNotEmpty()) daily.sum() / 60.0 else 0.0
                    val dSd = if (daily.size >= 2) {
                        val m = daily.sum() / daily.size
                        kotlin.math.sqrt(daily.sumOf { (it - m) * (it - m) } / daily.size)
                    } else 0.0
                    val safety = StockMath.safetyStock(dAvg, dSd, leadDays = 7.0)
                    val plan = ReplenishMath.reorderPlan(p.stockQty, dAvg, 7.0, safety)
                    // [P7-L7 إصلاح] أرضية حد الطلب: كان الاقتراح يتجاهل p.reorderLevel
                    // تماماً بينما LowStockWidget يعتمده عبر Product.isLow — منتج
                    // مخزونه 10 وحدده 20 وبلا مبيعات 60 يوماً كان الويدجت ينبه عليه
                    // والبطاقة لا تقترح له شيئاً أبداً. الآن حد الطلب أرضية فوق هدف
                    // الخطة (انظر ReorderFloorPolicy أدناه) — الخوارزمية النقية
                    // ReplenishMath.reorderPlan في R11Smart بقيت كما هي حرفياً
                    // (ملف غير مملوك للموجة) والسياسة الموحدة تُطبق في طبقة
                    // الاستهلاك هذه فقط.
                    val eff = ReorderFloorPolicy.withFloor(p.stockQty, p.reorderLevel, plan)
                    if (eff.verdict != "OK" && eff.orderQty > 0.0) {
                        plans += ReorderUI(p.name, p.stockQty, eff.orderQty, eff.verdict)
                    }
                }
                _inventory.value = _inventory.value.copy(reorder = plans.sortedByDescending { it.orderQty }.take(5))

                // B11 مردود الفئات: هامش 60 يوماً ÷ قيمة المخزون الحالي لكل فئة (سنوي)
                // [P33-P8] تكلفة المنتج قروش Long — ريال لتجانس الوحدة مع سعر السطر الريالي
                val marginByCat = lines.groupBy { prodCat[it.productId] ?: "" }
                    .mapValues { (_, ls) -> ls.sumOf { l -> l.qty * (l.unitPrice - Money.fromPiasters(prodCost[l.productId] ?: 0L)) } }
                    .filterKeys { it.isNotBlank() }
                val stockByCat = products.groupBy { it.category }
                    // [P33-P8] قيمة المخزون قروش Long — ريال لحد gmroiByCategory
                    .mapValues { (_, ps) -> Money.fromPiasters(ps.sumOf { it.stockValue }) }
                    .filterKeys { it.isNotBlank() }
                _inventory.value = _inventory.value.copy(
                    gmroi = ReplenishMath.gmroiByCategory(marginByCat, stockByCat, 2.0) // نافذة 60 يوماً = شهران
                        .take(4).map { GmroiUI(it.category, it.gmroiAnnual) },
                )
            } catch (e: Exception) { Error(e, "inventory") }

            // ═══ التقارير ═══
            try {
                // B12 اتجاه الإيراد الشهري (آخر 6 أشهر كاملة)
                val months = (0 until 6).map { i ->
                    val bounds = monthBounds(shiftMonths(now, -(5 - i)))
                    saleInvoices.filter { it.date in bounds.first..bounds.second }.sumOf { it.total }
                }
                // [P6-M22 إصلاح]: سلسلة بلا تباين (أصفار أو أي قيمة ثابتة) لا اتجاه لها —
                // كانت تعيد STABLE بقوة R²=100% مصطنعة (ssTot=0 ⇒ r2=1 بالبناء) فتُعرض
                // بطاقة اتجاه كاذبة على قاعدة فارغة. الحارس: بلا تباين ⇒ null فتُخفى
                // البطاقة (نفس مسار null المعتمد في R11Cards عبر trend?.let).
                _reports.value = _reports.value.copy(
                    // [P33-P8] مجاميع شهرية قروش Long — ريال لحد linearTrend فيبقى الميل ريالاً/شهر
                    trend = if (months.distinct().size == 1) null else TrendMath.linearTrend(months.map { Money.fromPiasters(it) }),
                )
            } catch (e: Exception) { Error(e, "trend") }

            try {
                // B13 تدقيق أرضية الهامش: أسطر بيع 60 يوماً مقابل تكلفة المنتج (أرضية 10%)
                val lines = g.db.invoiceItems().saleLinesSince(now - 60 * day)
                val items = lines.mapNotNull { l ->
                    val name = prodName[l.productId] ?: return@mapNotNull null
                    // [P33-P8] سعر السطر قروش (DAO) والتكلفة قروش — الخوارزمية ريالية فتحويل عند الحد
                    PricingAuditMath.MarginLine(name, Money.fromPiasters(l.unitPrice), Money.fromPiasters(prodCost[l.productId] ?: 0L))
                }
                _reports.value = _reports.value.copy(marginAudit = PricingAuditMath.marginAudit(items, 10.0))
            } catch (e: Exception) { Error(e, "margin-audit") }

            try {
                // B14 عملاء صامتون: الفجوة الاعتيادية من فترات أوامرهم السابقة (كل التاريخ)
                val today = TimeMath.startOfDay(now) / day
                val churns = ArrayList<ChurnUI>()
                val byParty = saleInvoices.filter { it.total > 0 }.groupBy { it.partyId }
                for ((pid, invs) in byParty) {
                    val name = partyName[pid] ?: continue
                    if (name.isBlank() || invs.size < 2) continue
                    val days = invs.map { TimeMath.startOfDay(it.date) / day }.distinct().sorted()
                    if (days.size < 2) continue
                    var gaps = 0.0
                    for (i in 1 until days.size) gaps += (days[i] - days[i - 1])
                    val avgGap = (gaps / (days.size - 1)).toInt()
                    val risk = LoyaltyMath.churnRisk(days.last(), today, avgGap) ?: continue
                    churns += ChurnUI(name, risk.silenceDays, risk.score, risk.verdict)
                }
                _reports.value = _reports.value.copy(
                    churn = churns.sortedByDescending { it.score }.take(4),
                )
            } catch (e: Exception) { Error(e, "churn") }

            // ═══ الذمم ═══
            try {
                // B15 شرائح تأخر الأقساط (كل الأقساط المفتوحة)
                // [P33-P8] المفتوح قروش Long — عتبة تامة 0L (كانت 0.004) وريال لحد delinquencyProfile
                val openRows = g.db.installments().allInstallments().filter { it.open > 0L }
                    .map { TimeMath.startOfDay(it.dueDate) / day to Money.fromPiasters(it.open) }
                _debts.value = _debts.value.copy(delinquency = InstallmentMath.delinquencyProfile(openRows, today))
            } catch (e: Exception) { Error(e, "delinquency") }

            try {
                // B16 شرائح RFM: آخر طلب، التكرار (عدد أيام شراء)، القيمة الكلية
                val today = TimeMath.startOfDay(now) / day
                val byParty = saleInvoices.filter { it.total > 0 }.groupBy { it.partyId }
                val counts = HashMap<String, Int>()
                for ((pid, invs) in byParty) {
                    val name = partyName[pid] ?: continue
                    if (name.isBlank()) continue
                    val days = invs.map { TimeMath.startOfDay(it.date) / day }.distinct()
                    val recency = (today - days.max()).toInt()
                    val seg = LoyaltyMath.rfmSegment(recency, days.size, Money.fromPiasters(invs.sumOf { it.total }))
                    if (seg != "NO_ORDERS") counts[seg] = (counts[seg] ?: 0) + 1
                }
                _debts.value = _debts.value.copy(
                    segments = counts.entries
                        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                        .map { SegmentUI(it.key, it.value) },
                )
            } catch (e: Exception) { Error(e, "rfm") }

            // ═══ الشيكات ═══
            try {
                // B17 إحصاء الارتجاع لكل طرف (كل التاريخ، شيكات كلا الاتجاهين)
                val all = g.db.checks().allOnce()
                val per = all.groupBy { it.partyId }.mapNotNull { (pid, cs) ->
                    val name = partyName[pid] ?: return@mapNotNull null
                    if (name.isBlank()) null else Triple(name, cs.size, cs.count { it.status == 3 })
                }
                _checks.value = _checks.value.copy(bounce = CheckMath.bounceStats(per, minIssued = 3, topK = 3))
            } catch (e: Exception) { Error(e, "bounce") }

            try {
                // B18 فجوة نقدية 30 يوماً: شيكات معلقة + أقساط مستحقة (وارد/صادر حسب الاتجاه)
                val horizon = now + 30 * day
                val events = ArrayList<LiquidityMath.GapEvent>()
                val pending = g.db.checks().allOnce().filter { (it.status == 0 || it.status == 1) && it.dueDate <= horizon }
                for (c in pending) {
                    val d = TimeMath.startOfDay(c.dueDate) / day
                    // [P33-P8] مبلغ الشيك قروش Long — ريال لحد cashGapCurve الريالي
                    if (c.direction == 0) events += LiquidityMath.GapEvent(d, Money.fromPiasters(c.amount), 0.0)
                    else events += LiquidityMath.GapEvent(d, 0.0, Money.fromPiasters(c.amount))
                }
                val plans = g.db.installments().plansOnce()
                val openByPlan = g.db.installments().allInstallments().filter { it.open > 0L }.groupBy { it.planId }
                for (p in plans) {
                    for (ins in openByPlan[p.id] ?: emptyList()) {
                        if (ins.dueDate > horizon) continue
                        val d = TimeMath.startOfDay(ins.dueDate) / day
                        // [P33-P8] المفتوح القسطي قروش Long — ريال للحدث الريالي
                        if (p.direction == 0) events += LiquidityMath.GapEvent(d, Money.fromPiasters(ins.open), 0.0)
                        else events += LiquidityMath.GapEvent(d, 0.0, Money.fromPiasters(ins.open))
                    }
                }
                _checks.value = _checks.value.copy(
                    // [P33-P8] النقد قروش Long — ريال لبداية المنحنى
                    gap = LiquidityMath.cashGapCurve(Money.fromPiasters(g.reports.cashBalance()), events),
                )
            } catch (e: Exception) { Error(e, "gap") }

            // ═══ المصروفات ═══
            try {
                // B19 الموازنة الضمنية: متوسط 3 أشهر كاملة سابقة لكل فئة مقابل الشهر الحالي
                val (mStart, _) = monthBounds(now)
                val baseline = HashMap<String, MutableList<Double>>()
                for (i in 1..3) {
                    val (s, e) = monthBounds(shiftMonths(now, -i))
                    val byCat = g.db.expenses().between(s, e).groupBy { it.category }
                    for ((cat, list) in byCat) {
                        if (cat.isBlank()) continue
                        // [P33-P8] مبالغ المصروفات قروش Long — ريال في أساس الموازنة الريالي
                        baseline.getOrPut(cat) { mutableListOf() }.add(Money.fromPiasters(list.sumOf { it.amount }))
                    }
                }
                val baselineMap = baseline.mapValues { (_, v) -> v.sum() / v.size.coerceAtLeast(1) }
                val actual = g.db.expenses().between(mStart, now).groupBy { it.category }
                    // [P33-P8] فعلي الشهر قروش Long — ريال لحد budgetVsActual الريالي
                    .mapValues { (_, list) -> Money.fromPiasters(list.sumOf { it.amount }) }
                    .filterKeys { it.isNotBlank() }
                _expenses.value = _expenses.value.copy(
                    budget = ExpenseMath.budgetVsActual(baselineMap, actual, 15.0),
                )
            } catch (e: Exception) { Error(e, "budget") }

            try {
                // B20 ثابت/متغير: عدد الأشهر المميزة لكل فئة خلال 90 يوماً
                val byCat = g.db.expenses().between(now - 90 * day, now).groupBy { it.category }
                val data = byCat.mapNotNull { (cat, list) ->
                    if (cat.isBlank()) null
                    // [P33-P8] مجاميع الفئات قروش Long — ريال لحد fixedVariableSplit
                    else cat to (Money.fromPiasters(list.sumOf { it.amount }) to list.map { monthKey(it.date) }.distinct().size)
                }.toMap()
                _expenses.value = _expenses.value.copy(split = ExpenseMath.fixedVariableSplit(data, 3))
            } catch (e: Exception) { Error(e, "split") }
        } catch (e: Exception) {
            com.superbiz.app.core.ErrorCenter.warn("R11Insights", "loadAll: ${e.message}")
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

    // ═══════════════ أدوات مساعدة ═══════════════

    private fun round2k(v: Double): Double = kotlin.math.round(v * 100) / 100.0

    private fun Calendar.cloneCalendar(): Calendar = (this.clone() as Calendar)

    private fun Error(e: Exception, tag: String) {
        com.superbiz.app.core.ErrorCenter.warn("R11Insights/$tag", "${e::class.simpleName}: ${e.message}")
    }
}

/**
 * [P7-L7 إصلاح] سياسة أرضية حد الطلب (reorderLevel) الموحدة — طبقة الاستهلاك فقط:
 * الخوارزمية النقية ReplenishMath.reorderPlan في R11Smart (ملف غير مملوك لموجة P7)
 * بقيت كما هي حرفياً، والأرضية تُطبَّق هنا فوق نتيجتها.
 *
 * الازدواج المصلح: اقتراح B10 كان يتجاهل reorderLevel تماماً (خطة من الطلب الفعلي
 * وحده) بينما LowStockWidget يبني تنبيهه على Product.isLow (reorderLevel > 0 و
 * stockQty ≤ reorderLevel) — منتج بمخزون 10 وحدده 20 وبلا مبيعات 60 يوماً كان
 * الويدجت يعتبره منخفضاً والبطاقة لا تقترح عليه شيئاً أبداً.
 *
 * الدلالة الموحدة (عند reorderLevel > 0):
 * - الهدف الفعّال = max(هدف الخطة الطلبية، حد الطلب) بالتقريب القانوني round2
 *   (نفس مساعد P6-M3 الموحد المستخدم في ReplenishMath نفسها).
 * - الكمية المقترحة = max(كمية الخطة، سقف(الهدف الفعّال − الجرد)) — تغطي الفجوة
 *   حتى الأرضية كحد أدنى، فلا يقترح الاقتراح أقل من حد الطلب الذي التزم به المستخدم.
 * - الحكم لا يعود OK ما دام الجرد تحت أو عند حد الطلب (SOON)؛ OUT/URGENT/SOON
 *   من الخطة الطلبية أقوى فتبقى كما هي — فتصير البطاقة متوافقة اتجاهاً مع الويدجت
 *   (سلسلة الحكم نفسها OUT/URGENT/SOON/OK التي يعرضها R11Cards أصلاً).
 * - صف بكمية 0 لا يُعرض في البطاقة (اقتراح شراء: جرد عند الأرضية بلا فجوة لا يقترح
 *   شيئاً) بينما يظل هذا الصنف مرئياً في الويدجت — فرق سؤال لا تناقض، موثق عمداً.
 *
 * دالة نقية بلا أندرويد — مختبرة في P7ReorderFloorTest على JVM.
 */
internal object ReorderFloorPolicy {

    data class Suggestion(val target: Double, val orderQty: Double, val verdict: String)

    fun withFloor(stockQty: Double, reorderLevel: Double, plan: ReplenishMath.OrderPlan): Suggestion {
        if (reorderLevel <= 0.0) return Suggestion(plan.target, plan.orderQty, plan.verdict)
        val stock = stockQty.coerceAtLeast(0.0)
        val floor = com.superbiz.app.domain.algo.round2(reorderLevel)   // نفس التقريب القانوني للخطة
        val target = maxOf(plan.target, floor)
        val orderQty = maxOf(plan.orderQty, kotlin.math.ceil((target - stock).coerceAtLeast(0.0)))
        val verdict = if (plan.verdict == "OK" && stock <= floor) "SOON" else plan.verdict
        return Suggestion(com.superbiz.app.domain.algo.round2(target), orderQty, verdict)
    }
}
