package com.superbiz.app.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.superbiz.app.AppGraph
import com.superbiz.app.data.db.CheckEntity
import com.superbiz.app.domain.algo.AllocMath
import com.superbiz.app.domain.algo.CadenceMath
import com.superbiz.app.domain.algo.CorrMath
import com.superbiz.app.domain.algo.DispersionMath
import com.superbiz.app.domain.algo.ExpenseMixMath
import com.superbiz.app.domain.algo.FloatMath
import com.superbiz.app.domain.algo.GiniMath
import com.superbiz.app.domain.algo.HalfLifeMath
import com.superbiz.app.domain.algo.LossMakerMath
import com.superbiz.app.domain.algo.PartyMatchMath
import com.superbiz.app.domain.algo.PercentileMath
import com.superbiz.app.domain.algo.RobustMath
import com.superbiz.app.domain.algo.TimeMath
import com.superbiz.app.domain.algo.WorkingMath
import com.superbiz.app.util.Money // [P33-P8] نقطة التحويل الوحيدة ريال ↔ قروش
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * — VM الرؤى الذكية للموجة R13: يحسب 20 ميزة جديدة
 * من بيانات حقيقية عبر خوارزميات R13Smart، بعقد الصدق نفسه
 * - بلا بيانات ⇒ null/قوائم فارغة تُخفى في الواجهة، لا أرقام مزيّفة.
 * - كل قسم مستقل بـ try/catch يمرر عبر ErrorCenter ولا يُسقط بقية الأقسام.
 * - استعلامات موجودة/مضافة توازياً فقط — لا تغيير في مخطط قاعدة البيانات (v4).
 *
 * توزيع الميزات على 8 شاشات
 * الرئيسية F1..F4 · نقاط البيع F5..F7 · الفواتير F8..F10 · المخزون F11..F14
 * التقارير F15..F17 · الذمم F18 · الشيكات F19 · المصروفات F20
*/
class R13InsightsVM(app: Application) : AndroidViewModel(app) {

    private val g = AppGraph.from(app)

    private val refreshKey = MutableStateFlow(0)
    fun refresh() { refreshKey.value++ }

    // [P39-M4-10] علم الجاهزية — عقد R9/R10 نفسه: false أثناء أول جولة حساب ثم true
    // إلى الأبد (التحديث اليدوي لا يعيده false — لا وميض سكيلتون عند refresh)
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    // ═══════════════ الرئيسية (F1..F4) ═══════════════

    data class EngagedUI(val name: String, val score: Double)

    data class HomeR13(
        val trend: RobustMath.TheilSen? = null,                     // F1 ميل الإيراد المتين (ثيل-سين)
        val gini: Double? = null,                                   // F2 تفاوت قيم الفواتير (جيني)
        val engaged: List<EngagedUI> = emptyList(),                 // F3 أتفاعل العملاء بعمر النصف
        val percentiles: List<Pair<Double, Double>>? = null,        // F4 مئينات قيمة الفاتورة
    )

    private val _home = MutableStateFlow(HomeR13())
    val home: StateFlow<HomeR13> = _home

    // ═══════════════ نقاط البيع (F5..F7) ═══════════════

    data class RegularityUI(val name: String, val cv: Double, val verdict: String)

    data class CutUI(val category: String, val cut: Double)

    data class BelowUI(
        val productId: Long?,
        val name: String,
        val linesBelow: Int,
        val qtyBelow: Double,
        val lost: Double,
    )

    data class PosR13(
        val regulars: List<RegularityUI> = emptyList(),   // F5 انتظام وتيرة الزبائن الدائمين
        val dry: CadenceMath.DrySpells? = null,           // F6 أيام الجفاف البيعي (30 يوماً)
        val drawer: AllocMath.CashPlan? = null,           // F7 فكّ درج النقدية لتحصيل اليوم
    )

    private val _pos = MutableStateFlow(PosR13())
    val pos: StateFlow<PosR13> = _pos

    // ═══════════════ الفواتير (F8..F10) ═══════════════

    data class TwinPhonesUI(val a: String, val b: String, val verdict: String)

    data class InvoicesR13(
        val twins: List<TwinPhonesUI> = emptyList(),      // F8 عملاء برقم جوال متطابق
        val lastRank: Double? = null,                     // F9 رأث أحدث فاتورة مئينياً
        val ccc: WorkingMath.CCC? = null,                 // F10 دورة التحول النقدي
    )

    private val _invoices = MutableStateFlow(InvoicesR13())
    val invoices: StateFlow<InvoicesR13> = _invoices

    // ═══════════════ المخزون (F11..F14) ═══════════════

    data class InventoryR13(
        val mad: RobustMath.MadOutliers? = null,                      // F11 شواذ إيراد المنتجات (MAD)
        val below: List<BelowUI> = emptyList(),                       // F12 أسطر بيع تحت التكلفة
        val shares: List<Pair<String, Double>>? = null,               // F13 سهم كل فئة من قيمة المخزون
        val spread: DispersionMath.Spread? = null,                    // F14 تشتت أسعر المنتج الأكثر بيعاً
    )

    private val _inventory = MutableStateFlow(InventoryR13())
    val inventory: StateFlow<InventoryR13> = _inventory

    // ═══════════════ التقارير (F15..F17) ═══════════════

    data class ReportsR13(
        val pearson: Double? = null,        // F15 مرونة الخصم (بيرسون)
        val spearman: Double? = null,       // F16 صلابة العلاقة رتبياً (سبيرمان)
        val cuts: List<CutUI> = emptyList(), // F17 سهم كل فئة من خطة قصّ المصروفات 10٪
    )

    private val _reports = MutableStateFlow(ReportsR13())
    val reports: StateFlow<ReportsR13> = _reports

    // ═══════════════ الذمم (F18) ═══════════════

    data class DebtsR13(
        val balGini: Double? = null,   // F18 تفاوت توزيع الذمم على العملاء (جيني)
    )

    private val _debts = MutableStateFlow(DebtsR13())
    val debts: StateFlow<DebtsR13> = _debts

    // ═══════════════ الشيكات (F19) ═══════════════

    data class ChecksR13(
        val float: FloatMath.FloatStats? = null,   // F19 سرعة تحصيل الشيكات الواردة
    )

    private val _checks = MutableStateFlow(ChecksR13())
    val checks: StateFlow<ChecksR13> = _checks

    // ═══════════════ المصروفات (F20) ═══════════════

    data class ExpensesR13(
        val riser: ExpenseMixMath.Riser? = null,   // F20 الفئة المصرفية الأسرع صعوداً
    )

    private val _expenses = MutableStateFlow(ExpensesR13())
    val expenses: StateFlow<ExpensesR13> = _expenses

    // ═══════════════ التحميل ═══════════════

    init {
        viewModelScope.launch {
            loadAll()
            _ready.value = true // [P39-M4-10]: اكتملت الجولة الأولى — السكيلتون يفسح للمحتوى
            refreshKey.collect { if (it > 0) loadAll() }
        }
    }

    private suspend fun loadAll() = withContext(Dispatchers.Default) {
        // كان الحساب كله على الخيط الرئيسي — نفس علاج R12-C20 لـDataHealthVM
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val today = TimeMath.startOfDay(now) / day
        try {
            val saleInvoices = g.db.invoices().allOnce().filter { it.type == 0 && it.status < 3 }
            val products = g.inventory.products().first().filter { !it.archived }
            val prodName = products.associate { it.id to it.name }
            val parties = g.db.parties().allOnce()
            val partyName = parties.associate { it.id to it.name }

            // ═══ الرئيسية ═══
            try {
                // F1 ميل الإيراد المتين: 12 أسبوعاً عبر ثيل-سين (متين ضد أسبوع الشذوذ)
                val weekly = weeklyRevenue(saleInvoices, now, 12)
                _home.value = _home.value.copy(trend = RobustMath.theilSen(weekly))
            } catch (e: Exception) { Error(e, "theil") }

            try {
                // F2 تفاوت الفواتير: جيني على قيم فواتير البيع (90 يوماً)
                // [P33-P8] إجماليات الفواتير قروش — تُحوَّل ريالاً عند حدود الخوارزمية القائمة (الجيني نسبي فلا يتغيّر)
                val vals = saleInvoices.filter { it.date >= now - 90 * day }.map { Money.fromPiasters(it.total) }
                _home.value = _home.value.copy(gini = GiniMath.gini(vals))
            } catch (e: Exception) { Error(e, "gini") }

            try {
                // F3 تفاعل العملاء: أوزان بعمر نصف 90 يوماً على مبيعات 180 يوماً
                val events = saleInvoices.filter { it.date >= now - 180 * day }.map { inv ->
                    // [P33-P8] المبلغ قروش — يُحوَّل ريالاً (الوزن المبلغي للتفاعل يُعرض مبلغًا)
                    Triple(inv.partyId, Money.fromPiasters(inv.total), ((today - TimeMath.startOfDay(inv.date) / day)).toLong())
                }
                _home.value = _home.value.copy(
                    engaged = (HalfLifeMath.engagement(events, 90.0) ?: emptyList())
                        .mapNotNull { e ->
                            val nm = partyName[e.partyId]
                            if (nm.isNullOrBlank()) null else EngagedUI(nm, e.score)
                        }
                        .take(3),
                )
            } catch (e: Exception) { Error(e, "engaged") }

            try {
                // F4 مئينات الفاتورة: أين تقف قيمة الفاتورة النموذجية؟ (90 يوماً)
                // [P33-P8] قروش → ريال: قيم المئينات تُعرض مبلغًا عبر Money.format
                val vals90 = saleInvoices.filter { it.date >= now - 90 * day }.map { Money.fromPiasters(it.total) }
                _home.value = _home.value.copy(percentiles = PercentileMath.percentiles(vals90))
            } catch (e: Exception) { Error(e, "percentiles") }

            // ═══ نقاط البيع ═══
            try {
                // F5 انتظام الزبائن الدائمين: فواصل شراء كل عميل (90 يوماً، عميلان على الأقل 3 زيارات)
                val byParty = saleInvoices.filter { it.date >= now - 90 * day }
                    .groupBy { it.partyId }
                val rows = ArrayList<RegularityUI>()
                for ((pid, invs) in byParty) {
                    val nm = partyName[pid] ?: continue
                    if (nm.isBlank()) continue
                    if (invs.size < 4) continue
                    val days = invs.map { TimeMath.startOfDay(it.date) / day }.distinct().sorted()
                    if (days.size < 4) continue
                    val gaps = (1 until days.size).map { (days[it] - days[it - 1]).toDouble() }
                    val rg = CadenceMath.regularityCV(gaps) ?: continue
                    rows += RegularityUI(nm, rg.cv, rg.verdict)
                }
                // كان القص قبل الترتيب — عميل منتظم رابع يُسقط لصالح ثلاثة متقلبين
                _pos.value = _pos.value.copy(regulars = rows.sortedBy { it.cv }.take(3))
            } catch (e: Exception) { Error(e, "regulars") }

            try {
                // F6 أيام الجفاف: إيراد يومي لآخر 30 يوماً عبر drySpells
                val daily = (29 downTo 0).map { back ->
                    val d = today - back
                    // [P33-P8] مجموع الإيراد اليومي قروش → ريال (عتبة اليوم الجاف ريالية كما كانت)
                    Money.fromPiasters(saleInvoices.filter { TimeMath.startOfDay(it.date) / day == d }.sumOf { it.total })
                }
                _pos.value = _pos.value.copy(dry = CadenceMath.drySpells(daily))
            } catch (e: Exception) { Error(e, "dry") }

            try {
                // F7 فكّ درج النقدية: مجموع التحصيل النقدي لليوم الواحد
                val cashToday = g.db.payments().since(TimeMath.startOfDay(now))
                    .filter { it.direction == 0 && it.method == "CASH" }.sumOf { it.amount }
                // [P33-P8] التحصيل قروش — فئات الدج ريالية فالتفكيك يُمرَّر ريالاً
                _pos.value = _pos.value.copy(drawer = AllocMath.cashBreakdown(Money.fromPiasters(cashToday)))
            } catch (e: Exception) { Error(e, "drawer") }

            // ═══ الفواتير ═══
            try {
                // F8 توأم الجوالات: عملاء مختلفون بأرقام تتطابق بعد التطبيع
                val phones = parties.filter { !it.archived && it.phone.isNotBlank() }
                val twins = ArrayList<TwinPhonesUI>()
                outer@ for (i in phones.indices) {
                    for (j in i + 1 until phones.size) {
                        val m = PartyMatchMath.phoneMatch(phones[i].phone, phones[j].phone)
                        if (m.verdict != "NO") {
                            twins += TwinPhonesUI(phones[i].name, phones[j].name, m.verdict)
                            if (twins.size >= 3) break@outer
                        }
                    }
                }
                _invoices.value = _invoices.value.copy(twins = twins)
            } catch (e: Exception) { Error(e, "twins") }

            try {
                // F9 رتبة أحدث فاتورة مئينياً ضمن تاريخ الفواتير السابق
                if (saleInvoices.isNotEmpty()) {
                    val latest = saleInvoices.maxByOrNull { it.date }!!
                    // [P33-P8] قروش → ريال عند حدود الخوارزمية (الرتبة المئينية نسبية)
                    val history = saleInvoices.filter { it.id != latest.id }.map { Money.fromPiasters(it.total) }
                    _invoices.value = _invoices.value.copy(
                        lastRank = PercentileMath.percentileRank(history, Money.fromPiasters(latest.total)),
                    )
                }
            } catch (e: Exception) { Error(e, "lastRank") }

            try {
                // F10 دورة التحول النقدي: DSO + DIO − DPO من متوسطات 90 يوماً
                // بسط DSO يجب أن يكون البيع الآجل لا كامل المبيعات —
                // المحصّل عند البيع لا يمرّ بالذمم أصلاً (تقريب موثّق: المفتوحة الآن نيابةً عن الآجل)
                val w = now - 90 * day
                // [P33-P8] المبالغ قروش: عتبة المفتوح مساواة تامة > 0L بدل 0.004،
                // والمجاميع تُحوَّل ريالاً عند حدود CCC (المكوّنات أيام نسبيّة لا تتأثر بالوحدة)
                val creditSales = Money.fromPiasters(saleInvoices.filter { it.date in w..now && it.open > 0L }.sumOf { it.total }) / 90.0
                val cogs = Money.fromPiasters(saleInvoices.filter { it.date in w..now }.sumOf { it.costTotal }) / 90.0
                val purchases = Money.fromPiasters(g.db.invoices().allOnce()
                    .filter { it.type == 1 && it.date in w..now }.sumOf { it.total }) / 90.0
                val receivable = Money.fromPiasters(saleInvoices.sumOf { it.open }.coerceAtLeast(0L))
                val payable = Money.fromPiasters(g.db.invoices().allOnce()
                    .filter { it.type == 1 && it.status < 3 }.sumOf { it.open }.coerceAtLeast(0L))
                _invoices.value = _invoices.value.copy(
                    ccc = WorkingMath.cashConversionCycle(
                        receivable, Money.fromPiasters(products.sumOf { it.stockValue }), payable,
                        creditSales, cogs, purchases,
                    ),
                )
            } catch (e: Exception) { Error(e, "ccc") }

            // ═══ المخزون ═══
            try {
                val lines = g.db.invoiceItems().saleLinesSince(now - 60 * day)

                // F11 شواذ إيراد المنتجات: توزيع إيراد 60 يوماً عبر MAD
                // mapKeys كان يدمج منتجين يتشاركان الاسم فيمدّ إيراد أحدهما
                val revByProduct = lines.groupBy { it.productId }
                    .mapValues { (_, ls) -> ls.sumOf { it.qty * it.unitPrice } }
                _inventory.value = _inventory.value.copy(mad = RobustMath.madOutliers(revByProduct.values.toList()))

                // F12 أسطر تحت التكلفة: أسطر بيع 90 يوماً مقابل تكلفة المنتج الحالية
                val costOf = products.associate { it.id to it.costPrice }
                val bl = g.db.invoiceItems().saleLinesSince(now - 90 * day).mapNotNull { l ->
                    val pid = l.productId ?: return@mapNotNull null
                    val cost = costOf[pid] ?: return@mapNotNull null
                    // [P33-P8] السعر/التكلفة قروش (صف DAO يرجع Double فوق عمود قروش؛ تكلفة الكيان Long) → ريال
                    LossMakerMath.Line(pid, l.qty, l.unitPrice / 100.0, Money.fromPiasters(cost))
                }
                _inventory.value = _inventory.value.copy(
                    // كانت البطاقة تعرض #معرّف خاماً والاسم متوفر لدينا
                    below = LossMakerMath.belowCost(bl).take(4).map { r ->
                        BelowUI(
                            r.productId,
                            r.productId?.let { pid -> prodName[pid]?.takeIf { nm -> nm.isNotBlank() } ?: "#$pid" } ?: "—",
                            r.linesBelow, r.qtyBelow, r.lost,
                        )
                    },
                )

                // F13 سهم الفئات: توزيع قيمة المخزون على الفئات بمجاميع تطابق 100٪
                val byCat = products.groupBy { it.category.ifBlank { "—" } }
                    .mapValues { (_, ps) -> ps.sumOf { it.stockValue } }
                    .filterValues { it > 0L }   // [P33-P8] مساواة صحيحة تامة بدل 0.0
                _inventory.value = _inventory.value.copy(
                    shares = byCat.keys.sorted().let { cats ->
                        // [P33-P8] أوزان قروش → ريال (الحصص نسبية ومجموعها 100٪ حرفياً)
                        AllocMath.largestRemainder(100.0, cats.map { Money.fromPiasters(byCat[it]!!) })?.let { alloc ->
                            cats.zip(alloc)
                        }
                    },
                )

                // F14 تشتت السعر: المنتج الأكثر بيعاً بكم عدد الأسعار يُباع؟ (90 يوماً)
                val topProduct = lines.groupBy { it.productId }
                    .maxByOrNull { (_, ls) -> ls.sumOf { it.qty } }?.key
                if (topProduct != null && prodName[topProduct]?.isBlank() == false) {
                    _inventory.value = _inventory.value.copy(
                        spread = DispersionMath.priceSpread(
                            // [P33-P8] أسعار صف DAO قروش كـDouble → ريال (التشتت نسبي)
                            lines.filter { it.productId == topProduct }.map { it.unitPrice / 100.0 },
                        ),
                    )
                    spreadProduct = prodName[topProduct] ?: ""
                }
            } catch (e: Exception) { Error(e, "inventory") }

            // ═══ التقارير ═══
            try {
                // F15+F16 مرونة الخصم: هل الخصم يرفع الكمية فعلاً؟ بيرسون وسبيرمان معاً
                // [P6-M24 إصلاح]: كان بيرسون يُحسب على كل الأسطر المخصومة مجتمعة عبر منتجات
                // مختلفة — مفارقة سيمبسون: علاقة داخل كل منتج قد تنعكس بعد الخلط.
                // الآن: بيرسون لكل منتج على حدة (3+ أسطر بتباين) ثم تجميع بمتوسط موزون
                // بحجم الكمية، وnull إن لم تكفِ البيانات لأي منتج. سبيرمان يبقى كلياً
                // (رتب عامة عبر المنتجات — دلالته موثقة كما هي).
                val sLines = g.db.invoiceItems().discountedSaleLinesSince(now - 90 * day)
                var wSum = 0.0
                var acc = 0.0
                for ((_, ls) in sLines.groupBy { it.productId }) {
                    if (ls.size < 3) continue
                    // [P33-P8] الخصم قروش (صف DAO Double فوق عمود قروش) → ريال (الارتباط نسبي)
                    val r = CorrMath.pearson(ls.map { it.discount / 100.0 }, ls.map { it.qty }) ?: continue
                    val w = ls.sumOf { it.qty }
                    if (w <= 0.0) continue
                    acc += r * w
                    wSum += w
                }
                _reports.value = _reports.value.copy(
                    pearson = if (wSum > 0.0) acc / wSum else null,
                    spearman = if (sLines.size >= 6) CorrMath.spearman(sLines.map { it.discount / 100.0 }, sLines.map { it.qty }) else null, // [P33-P8] قروش صف DAO → ريال
                )
            } catch (e: Exception) { Error(e, "corr") }

            try {
                // F17 خطة قصّ المصروفات: لو قصصت 10٪ من إنفاق آخر 120 يوماً — سهم كل فئة من القصّ
                // بتوزيع البواقي الكبرى (المجاميع تطابق القصّ الإجمالي تماماً)
                // (بديل R13-B13: انجراف الصرف كان على بيانات لا يُنتجها التطبيق إطلاقاً —
                //  لا مسار يُنشئ فاتورة بعملة غير الأساس، فالبطاقة كانت ميتة)
                val exps = g.db.expenses().between(now - 120 * day, now)
                // [P33-P8] المصروفات قروش → ريال (القصّ يُعرض مبلغًا)
                val expTotal = Money.fromPiasters(exps.sumOf { it.amount })
                val byCatExp = exps.groupBy { it.category.ifBlank { "—" } }
                    .mapValues { (_, es) -> Money.fromPiasters(es.sumOf { it.amount }) }
                    .filterValues { it > 0.0 }
                val expCats = byCatExp.keys.sorted()
                _reports.value = _reports.value.copy(
                    cuts = AllocMath.largestRemainder(expTotal * 0.10, expCats.map { byCatExp[it]!! })
                        ?.zip(expCats) { cut, cat -> CutUI(cat, cut) }
                        ?.sortedByDescending { it.cut }
                        ?.take(4)
                        ?: emptyList(),
                )
            } catch (e: Exception) { Error(e, "cuts") }

            // ═══ الذمم ═══
            try {
                // F18 تفاوت الذمم: جيني على أرصدة العملاء المفتوحة — هل ذممك موزعة أم محبوسة في قلة؟
                // (بديل R13-B4: المعدل الضمني كان صفراً بالضرورة لأن القسط = المُجدَّد/الشهور بالبناء)
                // [P33-P8] المفتوح قروش: > 0L بمساواة تامة بدل 0.004؛ الجيني نسبي (التحويل ×1/100 لا يغيّره)
                val byPartyOpen = saleInvoices.filter { it.open > 0L }
                    .groupBy { it.partyId }.mapValues { (_, invs) -> Money.fromPiasters(invs.sumOf { it.open }) }
                _debts.value = _debts.value.copy(balGini = GiniMath.gini(byPartyOpen.values.toList()))
            } catch (e: Exception) { Error(e, "balgini") }

            // ═══ الشيكات ═══
            try {
                // F19 سرعة التحصيل: أيام الطفو للشيكات الواردة المحصّلة (تاريخ قيد التحصيل − الإصدار)
                val checks = g.db.checks().allOnce().filter { it.direction == 0 && it.status == 2 }
                val paidAt = g.db.payments().since(0)
                    .filter { it.checkId != null }
                    .associateBy { it.checkId!! }
                    .mapValues { (_, p) -> p.date }
                val floats = checks.mapNotNull { c: CheckEntity ->
                    val paid = paidAt[c.id] ?: return@mapNotNull null
                    val f = (TimeMath.startOfDay(paid) - TimeMath.startOfDay(c.issueDate)) / day
                    if (f >= 0) f.toDouble() else null
                }
                _checks.value = _checks.value.copy(float = FloatMath.floatDays(floats))
            } catch (e: Exception) { Error(e, "float") }

            // ═══ المصروفات ═══
            try {
                // F20 الفئة الأسرع صعوداً: حصة كل فئة في النصف الأخير مقابل السابق (120 يوماً)
                val mid = now - 60 * day
                val before = g.db.expenses().between(now - 120 * day, mid)
                    // [P33-P8] مجاميع قروش → ريال (حصص نسبية قبل/بعد)
                    .groupBy { it.category.ifBlank { "—" } }.mapValues { (_, es) -> Money.fromPiasters(es.sumOf { it.amount }) }
                val after = g.db.expenses().between(mid + 1, now)
                    // between شامل الطرفين — مصروف يوم منتصف النافذة كان يُحسب في النصفين
                    .groupBy { it.category.ifBlank { "—" } }.mapValues { (_, es) -> Money.fromPiasters(es.sumOf { it.amount }) }
                _expenses.value = _expenses.value.copy(riser = ExpenseMixMath.topRiser(before, after))
            } catch (e: Exception) { Error(e, "riser") }
        } catch (e: Exception) {
            com.superbiz.app.core.ErrorCenter.warn("R13Insights", "loadAll: ${e.message}")
        }
    }

    // اسم المنتج الأكثر بيعاً لبطاقة تشتت السعر (يُضبط أثناء التحميل)
    var spreadProduct: String = ""
        private set

    // ═══════════════ أدوات زمنية محلية (حتمية، بلا وقت حقيقي داخل الخوارزميات) ═══════════════

    private fun weeklyRevenue(sales: List<com.superbiz.app.data.db.Invoice>, now: Long, weeks: Int): List<Double> =
        (0 until weeks).map { w ->
            val from = now - (weeks - w) * 7 * 86_400_000L
            val to = from + 7 * 86_400_000L
            // [P33-P8] مجاميع أسبوعية قروش → ريال (الميل يُعرض بنفس مقياس ما قبل الترحيل)
            Money.fromPiasters(sales.filter { it.date in from until to }.sumOf { it.total })
        }

    private fun Error(e: Exception, tag: String) {
        com.superbiz.app.core.ErrorCenter.warn("R13Insights/$tag", "${e::class.simpleName}: ${e.message}")
    }
}
