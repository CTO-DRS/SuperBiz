package com.superbiz.app.data.repo

import com.superbiz.app.data.db.AccountSum
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.domain.AccountingEngine
import com.superbiz.app.domain.Accounts
import com.superbiz.app.domain.CategoryScope
import com.superbiz.app.domain.ForecastEngine
import com.superbiz.app.domain.ForecastResult
import com.superbiz.app.domain.IncomeStatement
import com.superbiz.app.domain.OpenInvoice
import com.superbiz.app.domain.PeriodStats
import com.superbiz.app.domain.ProfitRow
import com.superbiz.app.domain.TrialRow
import com.superbiz.app.util.Dates
import com.superbiz.app.util.Money

class ReportsRepo(private val db: AppDatabase) {

    suspend fun incomeStatement(from: Long, to: Long): IncomeStatement {
        val sums = db.journal().accountSumsBetween(from, to)
        fun creditOf(code: String) = sums.filter { it.account == code }.sumOf { it.c - it.d }
        fun debitOf(code: String) = sums.filter { it.account == code }.sumOf { it.d - it.c }
        // [P33-P8] قروش صحيحة — بلا round2 (محذوف من المحرك)
        return IncomeStatement(
            revenue = creditOf(Accounts.SALES),
            otherIncome = creditOf(Accounts.OTHER_INCOME),
            cogs = debitOf(Accounts.COGS),
            expenses = debitOf(Accounts.EXPENSE)
        )
    }

    suspend fun trialBalance(): List<TrialRow> {
        val sums = db.journal().accountSums()
        val grouped = sums.associate { it.account to (it.d to it.c) }
        return AccountingEngine.trialBalance(grouped)
    }

    /** [P33-P8] النقد في الصندوق — قروش Long */
    suspend fun cashBalance(): Long {
        val sums = db.journal().accountSums()
        val cash = sums.filter { it.account == Accounts.CASH }
        return cash.sumOf { it.d - it.c }
    }

    /** سلسلة يومية: (يوم، مبيعات، مصروفات) لآخر N يوم — [P33-P8] المبالغ قروش Long */
    suspend fun salesExpensesSeries(days: Int): List<Triple<Long, Long, Long>> {
        val today = Dates.startOfDay()
        // مفاتيح الأيام كانت تُبنى بإضافة 86,400,000ms ثابتة —
        // في مناطق التوقيت الصيفي ينزلق المفتاح عن منتصف الليل فتُسقط بيانات اليوم
        // بصمت في الـmap (؟.let). البناء الآن بمشيئة Calendar اليومية الآمنة.
        // [P33-P8] مجاميع قروش صحيحة — LongArray بدل DoubleArray
        val series = mutableMapOf<Long, LongArray>()
        var cursor = Dates.startOfDay()
        repeat(days.coerceAtLeast(1)) {
            series[cursor] = longArrayOf(0L, 0L)
            cursor = dayBefore(cursor, -1)
        }
        val from = series.keys.min()

        // [P6-M10 إصلاح]: كانت تمسح جدول الفواتير كاملاً (allOnce) رغم أن سلسلة الأيام
        // لا تعرض شيئاً قبل «from» — saleInvoicesSince(from) مكافئ (type=0, status<3, date>=from)
        val invoices = db.invoices().saleInvoicesSince(from)
        for (inv in invoices) {
            val d = Dates.startOfDay(inv.date)
            series[d]?.let { it[0] += inv.total }
        }
        val dailyExpense = db.journal()
            .accountRowsBetween(Accounts.EXPENSE, from, today + 86_399_999L)
        for (r in dailyExpense) {
            val d = Dates.startOfDay(r.date)
            series[d]?.let { it[1] += r.debit }
        }
        return series.entries.sortedBy { it.key }
            .map { Triple(it.key, it.value[0], it.value[1]) }
    }

    /** التدفق النقدي اليومي: مقبوضات - مدفوعات — [P33-P8] المبالغ قروش Long */
    suspend fun cashflowSeries(days: Int): List<Triple<Long, Long, Long>> {
        val today = Dates.startOfDay()
        // نفس إصلاح مفاتيح الأيام الآمنة للتوقيت الصيفي
        // [P33-P8] مجاميع قروش صحيحة — LongArray بدل DoubleArray
        val series = mutableMapOf<Long, LongArray>()
        var cursor = Dates.startOfDay()
        repeat(days.coerceAtLeast(1)) {
            series[cursor] = longArrayOf(0L, 0L)
            cursor = dayBefore(cursor, -1)
        }
        val from = series.keys.min()
        val pays = db.payments().since(from)
        for (p in pays) {
            val d = Dates.startOfDay(p.date)
            if (p.method == "DEBT") continue
            series[d]?.let { arr ->
                if (p.direction == 0) arr[0] += p.amount else arr[1] += p.amount
            }
        }
        // المصروفات تُقيَّد EXPENSE مدين / CASH دائن بلا صف Payment —
        // كانت «خارج اليوم» ومنحنى الحرق يتجاهلها رغم أن الرصيد يشملها (تعارض ظاهر)
        // [P6-M8 إصلاح]: كانت كل أسطر EXPENSE تُعد حرقاً نقدياً حتى غير النقدي منها —
        // عجز الجرد (EXPENSE مدين / INVENTORY دائن) وخصم السداد المبكر لخطة عميل
        // (EXPENSE مدين / RECEIVABLE دائن) لا يخرج منهما نقد، فتضخّم منحنى الحرق.
        // كيان Expense بلا حقل kind/type، لذا التمييز من الطرف الدائن في القيد نفسه:
        // يُحسب من كل سطر مصروف ما قوبل نقداً فقط (مجموع CASH الدائن في القيد) —
        // الصرف النقدي (EXPENSE/CASH) يُحسب كاملاً، وعجز الجرد والخصم المبكر
        // يُستثنيان صفراً، وأي قيد مختلط مستقبلي يُحسب منه النقدي min(debit, cashCredit).
        val expenseRows = db.journal().accountRowsBetweenCashSettled(
            Accounts.EXPENSE, Accounts.CASH, from, today + 86_399_999L
        )
        for (r in expenseRows) {
            val d = Dates.startOfDay(r.date)
            series[d]?.let { arr -> arr[1] += minOf(r.debit, r.cashCredit) }
        }
        return series.entries.sortedBy { it.key }
            .map { Triple(it.key, it.value[0], it.value[1]) }
    }

    /**إزاحة يومية آمنة للتوقيت الصيفي عبر Calendar */
    private fun dayBefore(ts: Long, days: Int): Long {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = ts
        c.add(java.util.Calendar.DAY_OF_MONTH, days)
        return c.timeInMillis
    }

    suspend fun topCustomers(from: Long, to: Long, limit: Int = 5): List<Pair<String, Long>> {
        // [P6-M10 إصلاح]: مسح كامل → نافذة saleInvoicesSince(from) مع نفس السقف to
        val invoices = db.invoices().saleInvoicesSince(from)
            .filter { it.date <= to }
        val byParty = invoices.groupBy { it.partyId }
            .mapValues { e -> e.value.sumOf { it.total } }
        val names = db.parties().allOnce().associate { it.id to it.name }
        // [P33-P8] قروش كما هي — بلا round
        return byParty.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { (names[it.key] ?: "؟") to it.value }
    }

    // [P33-P8] total قروش Long — qty كمية تبقى Double
    data class ProductSales(val name: String, val qty: Double, val total: Long)

    suspend fun topProducts(from: Long, to: Long, limit: Int = 5): List<ProductSales> =
        // استعلام واحد بدل استعلام لكل فاتورة في النافذة (N+1)
        db.invoiceItems().saleLinesBetween(from, to)
            .map { ProductSales(it.desc, it.qty, it.lineTotal) }
            .groupBy { it.name }
            .map { (name, list) ->
                ProductSales(name, list.sumOf { it.qty }, list.sumOf { it.total })
            }
            .sortedByDescending { it.total }
            .take(limit)

    /** أعمار الديون: [0..<30, 30-60, 60-90, >90] مبالغ مفتوحة للعملاء — [P33-P8] قروش Long */
    suspend fun agingBuckets(today: Long = System.currentTimeMillis()): List<Long> {
        val buckets = LongArray(4)
        // [P6-M10 إصلاح]: مسح كامل → saleInvoicesOnce (type=0, status<3 — نفس الفلتر السابق)
        // [P6-M7 إصلاح]: كانت الأعمار بـfloor عبر daysSince بينما كشف «متأخر» (overdueTotal
        // وأول 24 ساعة تأخير) بـceil عبر daysOverdue (إصلاح M-2.5) — فاتورة متأخرة بساعات
        // تدخل كشف «متأخر» وتبقى في سلة «0-30» فتتعارض سلات الأعمار مع الكشف.
        // التوحيد على ceil: نفس Dates.daysOverdue تماماً (صفر للمستقبل، تقريب لأعلى للتأخر).
        // [P33-P8] «مفتوح» بمساواة صحيحة تامة — عتبة 0.004 حُذفت
        val open = db.invoices().saleInvoicesOnce().filter { it.open > 0L }
        for (inv in open) {
            val days = Dates.daysOverdue(inv.dueDate, today)
            buckets[Dates.agingBucket(days)] += inv.open
        }
        return buckets.toList()
    }

    // [P33-P8] قروش Long
    suspend fun overdueTotal(today: Long = System.currentTimeMillis()): Long {
        val now = today
        return (
            // [P6-M10 إصلاح]: مسح كامل → saleInvoicesOnce (type=0, status<3 — نفس الفلتر السابق)
            db.invoices().saleInvoicesOnce()
                .filter { it.open > 0L && it.dueDate < now }
                .sumOf { it.open }
        )
    }

    /** مقبوضات آخر 8 أسابيع للتوقع — [P33-P8] المبلغ قروش Long */
    suspend fun recentReceipts(days: Int = 56): List<Pair<Long, Long>> =
        // نافذة بالتقويم الآمن للتوقيت الصيفي بدل الإزاحة الثابتة
        db.payments().since(Dates.startOfDayDaysAgo(days))
            .filter { it.direction == 0 && it.method != "DEBT" }
            .map { it.date to it.amount }

    suspend fun forecast(openInvoices: List<OpenInvoice>): ForecastResult {
        val receipts = recentReceipts()
        // استبعاد الفواتير الملغاة من التوقع — كانت تدخل بالقيمة الكاملة فتضخم التوقع
        // [P6-M10 إصلاح]: مسح كامل → saleInvoicesOnce (type=0, status<3 — نفس الفلتر السابق)
        val invoiced = db.invoices().saleInvoicesOnce().sumOf { it.total }
        val paid = db.payments().totalReceived()
        // [P33-P8] حدود الاستهلاك العرضي: ForecastEngine محرك نسب Double —
        // التحويل قروش → ريال عبر fromPiasters هنا فقط (لا يُخزَّن)
        return ForecastEngine.forecast(
            openInvoices,
            receipts.map { it.first to Money.fromPiasters(it.second) },
            Money.fromPiasters(invoiced), Money.fromPiasters(paid),
            System.currentTimeMillis()
        )
    }

    suspend fun openSaleInvoices(): List<OpenInvoice> =
        // [P6-M10 إصلاح]: مسح كامل → saleInvoicesOnce (type=0, status<3 — نفس الفلتر السابق)
        db.invoices().saleInvoicesOnce()
            // [P33-P8] «مفتوح» بمساواة صحيحة تامة — عتبة 0.004 حُذفت
            .filter { it.open > 0L }
            // [P33-P8] حدود الاستهلاك العرضي: OpenInvoice ما زال Double — fromPiasters هنا فقط
            .map { OpenInvoice(it.id, it.partyId, Money.fromPiasters(it.open), it.date, it.dueDate, true) }

    // [P33-P8] (مبيعات، مصروفات، صافي ربح) — قروش Long (netProfit من قائمة الدخل القروش)
    suspend fun monthTotals(): Triple<Long, Long, Long> {
        val from = Dates.monthStart()
        val to = System.currentTimeMillis()
        // [P6-M10 إصلاح]: مسح كامل → نافذة saleInvoicesSince(from) مع نفس السقف to
        val invoices = db.invoices().saleInvoicesSince(from)
            .filter { it.date <= to }
        val sales = invoices.sumOf { it.total }
        val ist = incomeStatement(from, to)
        return Triple(sales, ist.expenses, ist.netProfit)
    }

    // ═══ : تحليلات ذكية من BizMath/StatsMath — كلها من الفواتير الحقيقية ═══

    /**
     * إجماليات مبيعات آخر 12 شهراً تقويمياً (الأقدم أولاً؛ الشهر الحالي جزئي).
     * غذاء مباشر لخوارزمية الموسمية seasonalMonthlyIndex.
     */
    suspend fun monthlySalesTotals12(): List<Pair<Long, Long>> {
        val c = java.util.Calendar.getInstance().apply {
            timeInMillis = Dates.monthStart()
            add(java.util.Calendar.MONTH, -11)
        }
        val starts = ArrayList<Long>(12)
        repeat(12) { starts.add(c.timeInMillis); c.add(java.util.Calendar.MONTH, 1) }
        // [P6-M10 إصلاح]: مسح كامل → saleInvoicesSince(starts[0]) — الفواتير الأقدم من
        // starts[0] كانت تسقط من كل السلال أصلاً (شرط date >= s) فالنافذة مكافئة حسابياً
        val invoices = db.invoices().saleInvoicesSince(starts[0])
        // [P33-P8] قروش كما هي — بلا round
        return (0 until 12).map { i ->
            val s = starts[i]
            val e = if (i == 11) Long.MAX_VALUE else starts[i + 1]
            starts[i] to invoices.filter { it.date >= s && it.date < e }.sumOf { it.total }
        }
    }

    /** إيرادات كل العملاء في فترة (قيم فقط بلا أسماء) — غذاء جيني وحصة الأفضل — [P33-P8] قروش Long */
    suspend fun customerRevenueTotals(from: Long, to: Long): List<Long> =
        // [P6-M10 إصلاح]: مسح كامل → نافذة saleInvoicesSince(from) مع نفس السقف to
        db.invoices().saleInvoicesSince(from)
            .filter { it.date <= to }
            .groupBy { it.partyId }
            .mapValues { e -> e.value.sumOf { it.total } }
            .values.toList()

    /** كمية المبيعات لكل منتج خلال N يوماً (id → كمية) — غذاء ABC وأيام التغطية وإعادة الطلب */
    suspend fun productSoldQty(days: Int): Map<Long, Double> {
        // نافذة بالتقويم الآمن للتوقيت الصيفي بدل الإزاحة الثابتة
        val from = Dates.startOfDayDaysAgo(days - 1)
        // saleLinesSince الموجود يغطي الطلب نفسه — استعلام واحد بدل N+1
        return db.invoiceItems().saleLinesSince(from)
            .groupBy { it.productId }
            .mapValues { (_, ls) -> ls.sumOf { it.qty } }
    }

    // ═══ : تغذية بطاقات المدرج النقدي وساعات الذروة وملخص الضريبة ═══

    /** طوابع زمن فواتير البيع في آخر N يوماً — غذاء بروفايل أيام الأسبوع (SeriesMath.dayOfWeekProfile) */
    suspend fun saleTimestamps(days: Int): List<Long> {
        // نافذة بالتقويم الآمن للتوقيت الصيفي بدل الإزاحة الثابتة
        val from = Dates.startOfDayDaysAgo(days - 1)
        // [P6-M10 إصلاح]: مسح كامل → نافذة saleInvoicesSince(from) (type=0, status<3, date>=from)
        return db.invoices().saleInvoicesSince(from)
            .map { it.date }
    }

    /** توزيع ساعات البيع على 24 خانة (0..23) من فواتير حقيقية — غذاء بطاقة ساعات الذروة */
    suspend fun saleHourCounts(days: Int): IntArray {
        val counts = IntArray(24)
        val cal = java.util.Calendar.getInstance()
        for (ts in saleTimestamps(days)) {
            cal.timeInMillis = ts
            counts[cal.get(java.util.Calendar.HOUR_OF_DAY)]++
        }
        return counts
    }

    /**
     * تجميع الصافي والضريبة حسب نسبة الضريبة (Triple: النسبة ككسر Double، مجموع الصافي قروش، مجموع الضريبة قروش)
     * عبر FinMath.vatByRate — من حقول الفواتير المحفوظة لا إعادة اشتقاق.
     * [P33-P8] النسبة تبقى Double والمبالغ قروش Long.
     */
    suspend fun vatByRate(from: Long, to: Long): List<Triple<Double, Long, Long>> {
        // كانت الضريبة تُشتق مجدداً Σ(net×rate) بينما المحجوز فعلاً
        // لكل فاتورة taxAmount = round2(net×rate) — فرق قرش لكل فاتورة يتراكم إلى
        // انحراف مرئي عن دفتر الضريبة ومجاميع الفواتير. تجميع المحجوز نفسه حسب النسبة.
        // [P6-M10 إصلاح]: مسح كامل → نافذة saleInvoicesSince(from) مع نفس السقف to
        val rows = db.invoices().saleInvoicesSince(from)
            .filter { it.date <= to && it.taxRate > 0.0 }
        val byRate = rows.groupBy { it.taxRate / 100.0 }
        // [P33-P8] مجاميع قروش صحيحة — بلا round
        return byRate.keys.sortedDescending().map { rate ->
            Triple(
                rate,
                byRate.getValue(rate).sumOf { it.subtotal - it.discount },
                byRate.getValue(rate).sumOf { it.taxAmount },
            )
        }
    }

    /**
     * [P38-Z3] تقرير الضريبة الدوري — أساس الإقرار (مخرجات/مدخلات/صافي/صفرية/معفاة).
     * مصدر الحقيقة المحجوز في الفواتير نفسها (عقد R14-F19): net = subtotal − discount
     * وvat = taxAmount المحجوز — لا إعادة اشتقاق. النافذة [from, to] مع استبعاد
     * الملغاة (status = 3) بنفس عرف saleInvoicesSince/purchaseInvoicesSince،
     * والبناء الحسابي كله في المحرك النقي ZatcaReturnP38 (مختبر مجرداً).
     *
     * [P41-L2] v11 — الفواتير **الواعية بالسطر** (أي سطر يحمل فئة/نسبة صريحة)
     * تُبنى صفوفها من بنودها عبر saleItemsBetween: سطر قياسي ⇒ صف خاضع بنسبته
     * الفعالة وضريبة LineTaxP41 (نفس دالة الحفظ فلا انحراف عن المحجوز)، وسطر
     * صفرية/معفاة ⇒ صف بخانته المستقلة بضريبة صفر. الفواتير التاريخية (لا سطر
     * صريح فيها) تبقى على المسار القديم حرفياً: صف واحد من الرأس — تقارير P38
     * القائمة تعطي نفس أرقامها بالضبط.
     */
    suspend fun zatcaReturn(from: Long, to: Long): com.superbiz.app.domain.ZatcaReturnP38.Report {
        val saleInvoices = db.invoices().saleInvoicesSince(from)
            .filter { it.date <= to }
        val itemsByInvoice = db.invoices().saleItemsBetween(from, to).groupBy { it.invoiceId }
        val sales = saleInvoices.flatMap { inv ->
            val lines = itemsByInvoice[inv.id].orEmpty()
            val lineAware = lines.isNotEmpty() &&
                com.superbiz.app.domain.LineTaxP41.isLineAware(lines.map { it.taxKind to it.taxRate })
            if (!lineAware) {
                listOf(
                    com.superbiz.app.domain.ZatcaReturnP38.SaleRow(
                        rate = inv.taxRate,
                        net = inv.subtotal - inv.discount,
                        vat = inv.taxAmount,
                    )
                )
            } else {
                lines.map { ln ->
                    val kind = ln.taxKind
                    val vat = com.superbiz.app.domain.LineTaxP41.lineTax(
                        ln.lineTotal, kind, ln.taxRate, inv.taxRate
                    )
                    com.superbiz.app.domain.ZatcaReturnP38.SaleRow(
                        rate = com.superbiz.app.domain.LineTaxP41.effectiveRate(kind, ln.taxRate, inv.taxRate),
                        net = ln.lineTotal,
                        vat = vat,
                        kind = kind,
                    )
                }
            }
        }
        val purchases = db.invoices().purchaseInvoicesSince(from)
            .filter { it.date <= to }
            .map {
                com.superbiz.app.domain.ZatcaReturnP38.PurchRow(
                    net = it.subtotal - it.discount,
                    vat = it.taxAmount,
                )
            }
        return com.superbiz.app.domain.ZatcaReturnP38.build(sales, purchases)
    }

    /**
     * [P43-D1] جولة 4 — الدفتر التفصيلي (تصدير عميق): صفوف لكل بند من فواتير
     * البيع والمشتريات النشطة داخل النافذة بأعمدة الضريبة الكاملة من v11.
     *
     * البناء في المحرك النقي DeepExportP43 (مختبر مجرداً) — القراء هنا يمارسون
     * فلاتر النافذة والإلغاء بعقودها الموثقة (نمط saleInvoicesSince ونظيريه)
     * ويحلّون أسماء الأطراف مرة واحدة (خريطة id→name) كي يبقى كل صف قائماً
     * بذاته في التصدير. نفس قرار التصنيف في zatcaReturn حرفياً: فاتورة واعية
     * بالسطر تنزل بنداً بنداً والتاريخية صفاً مجمعاً من رأسها — فلا انحراف بين
     * الدفتر والملخصات أبداً (اختبار التوافقية على Room حقيقية في
     * DeepRegisterRepoP43Test).
     */
    suspend fun deepRegister(from: Long, to: Long): com.superbiz.app.domain.DeepExportP43.RegisterTotalsPair {
        val partyNames = db.parties().allOnce().associate { it.id to it.name }
        val saleInvoices = db.invoices().saleInvoicesSince(from).filter { it.date <= to }
        val saleItems = db.invoices().saleItemsBetween(from, to).groupBy { it.invoiceId }
        val purchaseInvoices = db.invoices().purchaseInvoicesSince(from).filter { it.date <= to }
        val purchaseItems = db.invoices().purchaseItemsBetween(from, to).groupBy { it.invoiceId }

        fun views(
            invoices: List<com.superbiz.app.data.db.Invoice>,
            itemsByInvoice: Map<Long, List<com.superbiz.app.data.db.InvoiceItem>>,
        ): List<com.superbiz.app.domain.DeepExportP43.InvView> = invoices.map { inv ->
            com.superbiz.app.domain.DeepExportP43.InvView(
                id = inv.id, number = inv.number,
                party = partyNames[inv.partyId] ?: "",
                dateMs = inv.date, taxRate = inv.taxRate,
                subtotalP = inv.subtotal, discountP = inv.discount, taxAmountP = inv.taxAmount,
            )
        }

        fun itemViews(
            itemsByInvoice: Map<Long, List<com.superbiz.app.data.db.InvoiceItem>>,
        ): Map<Long, List<com.superbiz.app.domain.DeepExportP43.ItemView>> =
            itemsByInvoice.mapValues { (_, items) ->
                items.map { ln ->
                    com.superbiz.app.domain.DeepExportP43.ItemView(
                        invoiceId = ln.invoiceId, desc = ln.desc, qty = ln.qty,
                        unitPriceP = ln.unitPrice, discountP = ln.discount,
                        lineTotalP = ln.lineTotal,
                        taxKind = ln.taxKind, taxRate = ln.taxRate,
                    )
                }
            }

        val sales = com.superbiz.app.domain.DeepExportP43.registerRows(
            views(saleInvoices, saleItems), itemViews(saleItems)
        )
        val purchases = com.superbiz.app.domain.DeepExportP43.registerRows(
            views(purchaseInvoices, purchaseItems), itemViews(purchaseItems)
        )
        return com.superbiz.app.domain.DeepExportP43.RegisterTotalsPair(
            sales = sales,
            purchases = purchases,
            salesTotals = com.superbiz.app.domain.DeepExportP43.summarize(sales),
            purchasesTotals = com.superbiz.app.domain.DeepExportP43.summarize(purchases),
        )
    }

    // ═══ : مقارنة الشهرين وأعلى الربحية وترشيح التصنيف (وظائف 16، 17، 19) ═══

    /**
     * إحصاء فترة عام (وظيفة 16): المبيعات من إجمالي فواتير البيع غير الملغاة،
     * والربح من قائمة الدخل (قيود دفتر الأستاذ)، وعدد الفواتير.
     */
    suspend fun periodSnapshot(from: Long, to: Long): PeriodStats {
        // [P6-M10 إصلاح]: مسح كامل → نافذة saleInvoicesSince(from) مع نفس السقف to
        val invoices = db.invoices().saleInvoicesSince(from)
            .filter { it.date <= to }
        // [P33-P8] حدود الاستهلاك العرضي: PeriodStats (ReportsP4) ما زال Double —
        // التحويل قروش → ريال عبر fromPiasters هنا فقط
        return PeriodStats(
            sales = Money.fromPiasters(invoices.sumOf { it.total }),
            profit = Money.fromPiasters(incomeStatement(from, to).netProfit),
            invoiceCount = invoices.size
        )
    }

    /**
     * مقارنة هذا الشهر × الماضي (وظيفة 16) — حدود شهرية حقيقية من التقويم.
     * بتصنيف مختار تُحسب الفترتان على أساس البنود (وظيفة 19) لتكون المقارنة متسقة.
     */
    suspend fun thisAndLastMonthStats(category: String? = null): Pair<PeriodStats, PeriodStats> {
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        val monthStart = Dates.startOfDay(cal.timeInMillis)
        cal.add(java.util.Calendar.MONTH, -1)
        val prevStart = Dates.startOfDay(cal.timeInMillis)
        return if (category.isNullOrBlank())
            periodSnapshot(prevStart, monthStart - 1) to periodSnapshot(monthStart, now)
        else
            categorySalesStats(prevStart, monthStart - 1, category) to
                categorySalesStats(monthStart, now, category)
    }

    /**
     * إحصاء فترة على منتجات تصنيف واحد (وظيفة 19): الترشيح من حقل التصنيف في المنتج
     * (التصنيفات على مستوى المنتج فقط في المخطط — لا تصنيف على مستوى الفاتورة).
     * الربح من التكلفة الحالية للمنتج بأساس البنود، وبلا أي تكلفة → null (صدق).
     */
    suspend fun categorySalesStats(from: Long, to: Long, category: String): PeriodStats {
        val products = db.products().allOnce().filter { it.category == category }
        if (products.isEmpty()) return PeriodStats(0.0, null, 0)
        val byId = products.associateBy { it.id }
        val ids = byId.keys
        // استعلام واحد بدل استعلام لكل فاتورة (N+1)
        val lines = db.invoiceItems().saleLinesBetween(from, to)
        val refs = mutableListOf<CategoryScope.ItemRef>()
        var hitInvoices = 0
        for ((_, items) in lines.groupBy { it.invoiceId }) {
            // [P33-P8] حدود الاستهلاك العرضي: CategoryScope.ItemRef (ReportsP4) ما زال Double —
            // unitPrice/lineTotal قروش تُحوَّل ريالاً عبر fromPiasters هنا فقط (الكمية تبقى كما هي)
            val selected = CategoryScope.select(ids, items.map {
                CategoryScope.ItemRef(
                    it.productId, it.qty,
                    Money.fromPiasters(it.unitPrice), Money.fromPiasters(it.lineTotal)
                )
            })
            if (selected.isNotEmpty()) {
                hitInvoices++
                refs += selected
            }
        }
        // [P33-P8] أساس التكلفة قروش → ريال عبر fromPiasters (costOf يستهلك Double)
        return CategoryScope.aggregate(refs, hitInvoices) { pid ->
            byId[pid]?.costPrice?.takeIf { it > 0L }?.let { Money.fromPiasters(it) }
        }
    }

    /**
     * أعلى المنتجات ربحية (وظيفة 17): هامش كل منتج من بنود مبيعات الفترة
     * بتكلفة المنتج الحالية كأساس (قد تختلف عن لحظة البيع — تُذكر الملاحظة في البطاقة).
     * المنتجات بلا أساس تكلفة تُستبعد من الترتيب (margin = null محفوظة للصدق).
     */
    suspend fun topProfitableProducts(
        from: Long, to: Long, category: String? = null, limit: Int = 5
    ): List<ProfitRow> {
        val products = db.products().allOnce()
        val byId = products.associateBy { it.id }
        val ids = category?.takeIf { it.isNotBlank() }
            ?.let { c -> products.filter { it.category == c }.map { it.id }.toHashSet() }
        // استعلام واحد بدل استعلام لكل فاتورة (N+1)
        val lines = db.invoiceItems().saleLinesBetween(from, to)
        // [P33-P8] تراكم قروش صحيح — revenue/margin Long ثم تحويل عرضي واحد عند الحدود
        data class Acc(var revenue: Long, var margin: Long, var hasCost: Boolean)
        val acc = HashMap<Long, Acc>()
        for (item in lines) {
            val pid = item.productId ?: continue
            if (ids != null && pid !in ids) continue
            val a = acc.getOrPut(pid) { Acc(0L, 0L, false) }
            a.revenue += item.lineTotal
            val p = byId[pid]
            if (p != null && p.costPrice > 0L) {
                a.margin += Math.round((item.unitPrice - p.costPrice) * item.qty)
                a.hasCost = true
            }
        }
        // [P33-P8] حدود الاستهلاك العرضي: ProfitRow (ReportsP4) ما زال Double — fromPiasters هنا فقط
        return acc.entries.map { (pid, a) ->
            ProfitRow(
                pid, byId[pid]?.name ?: "؟",
                Money.fromPiasters(a.revenue),
                if (a.hasCost) Money.fromPiasters(a.margin) else null
            )
        }
            .sortedWith(compareByDescending<ProfitRow> { it.margin ?: Double.NEGATIVE_INFINITY })
            .take(limit)
    }
}
