package com.superbiz.app.vm

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.superbiz.app.AppGraph
import com.superbiz.app.data.repo.Settings
import com.superbiz.app.domain.ForecastResult
import com.superbiz.app.util.Dates
import com.superbiz.app.util.Money // [P33-P8] نقطة التحويل الوحيدة ريال ↔ قروش
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeData(
    val ar: Double = 0.0,
    val ap: Double = 0.0,
    val salesMonth: Double = 0.0,
    val expensesMonth: Double = 0.0,
    val profitMonth: Double = 0.0,
    val cash: Double = 0.0,
    val stockValue: Double = 0.0,
    val overdue: Double = 0.0,
    val lowStockCount: Int = 0,
    val checksDueSoon: Int = 0,
    val series7: List<Triple<Long, Double, Double>> = emptyList(),
    val forecast: ForecastResult? = null,
    val monthlyGoal: Double = 0.0,          // هدف المبيعات الشهري من الإعدادات
    val goalProgress: Double = 0.0,         // نسبة تحقيق الهدف 0..1+
    val goalPace: Double = 1.0,             // إيقاع التحقيق (>1 أمام الجدول، <1 متأخر) — BizMath.goalPace
    val salesStreak: Int = 0,               // أيام البيع المتتالية — SeriesMath.streakDays على تواريخ فواتير البيع
    // درجة الصحة المالية 0..100 (وظيفة 39) — null = نقص مدخل → تُخفى البطاقة (صدق الفراغ)
    val healthScore: Int? = null
)

/** إحصاءات الملف الشخصي */
data class ProfileStats(
    val invoices: Int = 0,
    val parties: Int = 0,
    val products: Int = 0,
    val salesMonth: Double = 0.0
)

class AppVM(app: Application) : AndroidViewModel(app) {

    private val g = AppGraph.from(app)

    val settings: StateFlow<Settings> = g.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Settings())

    val symbol: StateFlow<String> = g.db.currencies().all()
        .map { list -> list.firstOrNull { it.isBase }?.symbol ?: "ر.س" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "ر.س")

    private val refreshKey = MutableStateFlow(0)
    fun refresh() {
        refreshKey.value++
        loadProfileStats()
    }

    /** تنفيذ عمل مؤجل في نطاق الـ VM — : بلا خطر إغلاق التطبيق عند أي استثناء */
    fun launch(work: suspend () -> Unit) {
        launchSafe { work() }
    }

    val home: StateFlow<HomeData> = refreshKey.flatMapLatest {
        kotlinx.coroutines.flow.flow { emit(loadHome()) }
            // فشل تحميل الرئيسية يعيد البيانات الافتراضية بدل موت جامع stateIn وإغلاق التطبيق
            .catch { e ->
                android.util.Log.e("SuperBizVM", "home data failed", e)
                emit(HomeData())
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeData())

    // ─── إحصاءات الملف الشخصي ───
    private val _profileStats = MutableStateFlow(ProfileStats())
    val profileStats: StateFlow<ProfileStats> = _profileStats

    fun loadProfileStats() = viewModelScope.launch {
        try {
            // [P33-P8] مبيعات الشهر قروش من التقارير — ريال لحالة العرض
            val sales = Money.fromPiasters(g.reports.monthTotals().first)
            _profileStats.value = ProfileStats(
                invoices = g.db.invoices().count(),
                parties = g.db.parties().count(),
                products = g.db.products().allOnce().size,
                salesMonth = sales
            )
        } catch (e: Exception) { /* يبقى الافتراضي */ }
    }

    private suspend fun loadHome(): HomeData = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        // [P20-FIX agent7]: كانت المادة الثقيلة (مسح فواتير ×2 + سلاسل + خرائط الأرصدة) تنفّذ على
        // Main عبر viewModelScope — و refresh() يُستدعى بعد كل قبض/دين/دفعة فتتهنيج الواجهة مع كبر السجلات
        val now = System.currentTimeMillis()
        // [P33-P8] كل مصادر المبالغ قروش Long من الدفتر/التقارير — تُحوَّل ريالاً هنا فقط
        // لتبقى حالة العرض HomeData بواجهتها الريالية القائمة (شاشة الرئيسية والودجات بلا تغيير)
        val (arP, apP) = g.ledger.arAp()
        val ar = Money.fromPiasters(arP)
        val ap = Money.fromPiasters(apP)
        val (salesP, expensesP, profitP) = g.reports.monthTotals()
        val sales = Money.fromPiasters(salesP)
        val expenses = Money.fromPiasters(expensesP)
        val profit = Money.fromPiasters(profitP)
        val cash = Money.fromPiasters(g.reports.cashBalance())
        val stock = Money.fromPiasters(g.inventory.stockValue())
        val overdue = Money.fromPiasters(g.reports.overdueTotal(now))
        val low = g.inventory.lowStock().size
        val soon = g.checks.dueSoon(now, now + 7 * 86_400_000L).size
        // [P33-P8] سلسلة المبيعات/المصروفات قروش — تُحوَّل ريالاً لنموذج الرسم القائم
        val series = g.reports.salesExpensesSeries(7)
            .map { Triple(it.first, Money.fromPiasters(it.second), Money.fromPiasters(it.third)) }
        val forecast = try {
            g.reports.forecast(g.reports.openSaleInvoices())
        } catch (e: Exception) { null }
        val goal = try { g.settings.snapshot().monthlyGoal } catch (e: Exception) { 0.0 }
        val progress = if (goal > 0) (sales / goal) else 0.0
        // إيقاع الهدف الحقيقي — مقارنة الإنجاز بالجدول اليومي للشهر
        val pace = try {
            val (dayOfMonth, daysInMonth) = com.superbiz.app.domain.algo.TimeMath.monthProgress(now)
            com.superbiz.app.domain.analytics.goalPace(sales, goal, dayOfMonth, daysInMonth)
        } catch (e: Exception) { 1.0 }
        // سلسلة أيام البيع المتتالية — من تواريخ فواتير البيع الحقيقية
        // [P20-FIX agent13]: كانت الشرائح بأيام UTC (date / 86400000) بينما كل شرائح الشاشة الأخرى
        // بأيام محلية عبر Dates.startOfDay — في المناطق UTC+3 كانت كل بيوع ما بعد منتصف الليل المحلي
        // تهبط إلى اليوم السابق فتنهار سلسلة يومين في يوم واحد و«اليوم» يبقى فارغاً حتى 03:00 صباحاً
        val streak = try {
            fun localDayNum(ts: Long): Int {
                val c = java.util.Calendar.getInstance()
                c.timeInMillis = ts
                return c.get(java.util.Calendar.YEAR) * 1000 + c.get(java.util.Calendar.DAY_OF_YEAR)
            }
            val days = g.db.invoices().allOnce()
                .filter { it.isSale && it.status < 3 }
                .map { localDayNum(it.date) }
                .toSet()
            com.superbiz.app.domain.algo.SeriesMath.streakDays(days, localDayNum(now))
        } catch (e: Exception) { 0 }
        // درجة الصحة المالية (وظيفة 39) — مدخلاتها كلها من بيانات حقيقية قائمة
        // الهامش = ربح الشهر ÷ مبيعات الشهر (بلا مبيعات → null)،
        // السيولة = FinMath.runwayDays على الرصيد النقدي ومعدل الحرق من سلسلة التدفق (نمط ReportsVM القائم)،
        // التحصيل = FinMath.collectionEfficiency على مقبوضات/مبيعات 90 يوماً (نمط DebtsVM القائم)
        val healthScore = try {
            val margin = if (sales > 0) profit / sales else null
            val cashSeries = g.reports.cashflowSeries(30)
            // [P33-P8] صافي التدفق اليومي قروش — يُحوَّل ريالاً لحدود FinMath القائمة
            val burn = com.superbiz.app.domain.algo.FinMath.burnRate(
                cashSeries.map { Money.fromPiasters(it.second - it.third) }, 30)
            val runway = com.superbiz.app.domain.algo.FinMath.runwayDays(cash, -burn) // -1 = لا حرق
            val from90 = now - 90 * 86_400_000L
            val creditSales90 = g.db.invoices().allOnce()
                .filter { it.isSale && it.status < 3 && it.date in from90..now }
                .sumOf { it.total }
            val collected90 = try {
                g.db.payments().since(from90)
                    // نفس تصحيح DebtsVM — استرداد الشيكات ليس تحصيلاً
                    .filter { it.direction == 0 && it.method != "DEBT" && it.method != "CHECK_BOUNCE" && it.date in from90..now }
                    .sumOf { it.amount }
            } catch (e: Exception) { 0L }
            val coll = if (creditSales90 > 0L)
                // [P33-P8] كفاءة التحصيل نسبة — تُحسب على القيم الريالية عبر حدود FinMath القائمة
                com.superbiz.app.domain.algo.FinMath.collectionEfficiency(
                    Money.fromPiasters(collected90), Money.fromPiasters(creditSales90))
            else null
            com.superbiz.app.domain.HealthScore.compute(
                com.superbiz.app.domain.HealthScore.Inputs(
                    marginRatio = margin,
                    runwayDays = runway,
                    collectionEff = coll
                )
            )
        } catch (e: Exception) { null }
        HomeData(ar, ap, sales, expenses, profit, cash, stock, overdue, low, soon, series, forecast, goal, progress, pace, streak, healthScore)
    }

    // ─── : قبض/صرف نقدي حقيقي — عمليات قاعدة البيانات من الـ VM لا من الواجهة ───
    fun cashIn(amount: Double, note: String) = launchSafe {
        // كان Infinity يعبر فحص الواجهة (v > 0) فيُرحّل قيداً لانهائياً يفسد الصندوق
        // [P33-P8] المبلغ يدخل ريالاً من الحوار → قروش عند الحدود الوحيدة (Money)؛
        // القيم غير المنتهية تُحوَّل إلى 0 فتُرفض بمقارنة صحيحة تامة
        val amountP = Money.toPiasters(amount)
        require(amountP > 0L) { "amount must be positive finite" }
        g.ledger.post(
            com.superbiz.app.domain.AccountingEngine.cashIn(
                amountP, System.currentTimeMillis(), note.ifBlank { "قبض نقدي" }
            )
        )
        refresh()
    }

    fun cashOut(amount: Double, note: String) = launchSafe {
        // نفس الحرارة للصرف النقدي — [P33-P8] ريال → قروش عبر Money
        val amountP = Money.toPiasters(amount)
        require(amountP > 0L) { "amount must be positive finite" }
        g.ledger.post(
            com.superbiz.app.domain.AccountingEngine.cashOut(
                amountP, System.currentTimeMillis(), note.ifBlank { "صرف نقدي" }
            )
        )
        refresh()
    }

    // ─── صورة المستخدم ───
    private var avatarCache: Pair<String?, Bitmap?>? = null

    // فك الشعار ينتقل إلى Dispatcher.IO — كان avatarBitmap() يقرأ الملف من
    // القرص متزامناً داخل التركيب Composition (خيط الواجهة) فسبب تهنيجًا مع الصور الكبيرة
    private val _avatar = MutableStateFlow<Bitmap?>(null)
    val avatar: StateFlow<Bitmap?> = _avatar

    init {
        viewModelScope.launch {
            settings.collect { s ->
                // مزامنة الخزنة الحيّة AppPrefs — تقرأ منها الطبقات العميقة متزامناً
                com.superbiz.app.core.AppPrefs.hapticsEnabled = s.hapticsEnabled
                com.superbiz.app.core.AppPrefs.confirmDestructive = s.confirmDestructive
                com.superbiz.app.core.AppPrefs.flagSecure = s.flagSecure
                com.superbiz.app.core.AppPrefs.lockTimeoutMin = s.lockTimeoutMin
                com.superbiz.app.core.AppPrefs.fontScale = s.fontScale
                com.superbiz.app.core.AppPrefs.dynamicColors = s.dynamicColors
                com.superbiz.app.core.AppPrefs.mirrorChartsRtl = s.mirrorChartsRtl
                com.superbiz.app.core.AppPrefs.animationsEnabled = s.animationsEnabled
                com.superbiz.app.core.AppPrefs.arabicReceiptMode = s.arabicReceiptMode
                com.superbiz.app.core.AppPrefs.defaultLowStockQty = s.defaultLowStockQty
                com.superbiz.app.core.AppPrefs.lowStockAlerts = s.lowStockAlerts
                com.superbiz.app.core.AppPrefs.receivableAlerts = s.receivableAlerts
                // مزامنة مفاتيح المرحلة الثالثة
                com.superbiz.app.core.AppPrefs.searchFuzzyThreshold = s.searchFuzzyThreshold
                com.superbiz.app.core.AppPrefs.eoqOrderCost = s.eoqOrderCost
                // مزامنة وضع الخصوصية (وظيفة 38)
                com.superbiz.app.core.AppPrefs.privacyBlur = s.privacyBlur
                // جولة 5 [P44-K1]: مزامنة تخطيط بطاقات الرؤى المخصص
                com.superbiz.app.core.AppPrefs.dashboardLayout = s.dashboardLayout
                // وظيفة 34 — ضمان جدولة النسخ التلقائي الدوري بحسب الإعداد الحالي
                // (يعاد مع كل تغيير إعدادات وعند كل إقلاع — UPDATE أمرية لا تكرار، وتُلغى عند 0)
                com.superbiz.app.work.BackupWorker.schedule(getApplication(), s.autoBackupDays)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val bmp = s.avatarPath?.let {
                        try { BitmapFactory.decodeFile(it) } catch (e: Exception) { null }
                    }
                    avatarCache = s.avatarPath to bmp
                    _avatar.value = bmp
                }
            }
        }
    }

    /** قراءة متزامنة رخيصة من الذاكرة فقط — لا قراءة قرص بعد الإقلاع */
    fun avatarBitmap(): Bitmap? = avatarCache?.second

    fun businessLabel(): String =
        settings.value.businessName.ifBlank { getApplication<Application>().getString(com.superbiz.app.R.string.business_default) }

    fun greeting(ar: Boolean): String = if (Dates.isMorning())
        getApplication<Application>().getString(com.superbiz.app.R.string.greeting_morning)
    else getApplication<Application>().getString(com.superbiz.app.R.string.greeting_evening)
}
