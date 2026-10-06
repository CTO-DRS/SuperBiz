package com.superbiz.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import com.superbiz.app.MainActivity
import com.superbiz.app.R
import com.superbiz.app.ui.nav.Routes
import com.superbiz.app.util.Dates
import com.superbiz.app.util.Money
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * — ويدجت الشاشة الرئيسية لرصيد الذمم المدينة
 *
 * • يقرأ البيانات الحقيقية من دفتر الأستاذ (نفس مصدر بطاقة الرئيسية) — بلا بيانات وهمية
 * • التحديث: تلقائي كل 30 دقيقة + فور أي قيد محاسبي جديد (WidgetSync) + زر تحديث يدوي
 * • النقر على الويدجت يفتح التطبيق مباشرة على تبويب الديون (بعد القفل إن كان مفعلاً)
*/
class ReceivablesWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pr = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                WidgetSync.renderAll(context.applicationContext, appWidgetManager, appWidgetIds)
            } catch (_: Exception) {
            } finally {
                pr.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == WidgetSync.ACTION_REFRESH) {
            val app = context.applicationContext
            // [P6-M40 إصلاح]: زر التحديث يرسل هوية الويدجة المستهدفة فيُرسَم هذه الويدجة وحدها
            // — كان أي زر تحديث في أي ويدجة يعيد رسم الأربعة جميعاً (renderAsync كاملاً).
            // بلا معرّف (نداء قديم) يبقى السلوك الشامل التجميعي عبر push.
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                WidgetSync.refreshOne(app, ReceivablesWidgetProvider::class.java, id)
            } else {
                WidgetSync.push(app, immediate = true)
            }
        }
    }
}

/** مُنسّق تحديث الويدجت: تجميع (debounce) للتحديثات المتلاحقة + رسم فعلي */
object WidgetSync {

    const val ACTION_REFRESH = "com.superbiz.app.WIDGET_REFRESH"
    const val ACTION_REFRESH_SALES = "com.superbiz.app.WIDGET_REFRESH_SALES"
    const val ACTION_REFRESH_CASH = "com.superbiz.app.WIDGET_REFRESH_CASH"
    const val ACTION_REFRESH_STOCK = "com.superbiz.app.WIDGET_REFRESH_STOCK"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var pendingRun: Runnable? = null

    /** بيانات لقطة الويدجت */
    private data class Snap(  // [P33-P8] قروش
        val ar: Long, val overdue: Long, val debtors: Int, val symbol: String
    )

    /**
     * طلب تحديث الويدجت — يستدعى بعد كل قيد محاسبي (LedgerRepo.onMutate) ومن onResume.
     * immediate = true يتجاوز التجميع (زر التحديث اليدوي).
     *
     * [P6-M40 إصلاح]: يبقى مسار «تغير البيانات الجامع» شاملاً كل الويدجات (عملية حفظ تهم الجميع)
     * — أما زر التحديث اليدوي فيمر عبر refreshOne للويدجة المستهدفة وحدها.
     */
    fun push(context: Context, immediate: Boolean = false) {
        val app = context.applicationContext
        handler.post {
            // إلغاء أي تحديث مجدول سابقاً — نجمع المتلاحق في مرسلة واحدة بعد 1.2 ثانية
            pendingRun?.let { handler.removeCallbacks(it) }
            pendingRun = null
            if (immediate) {
                renderAsync(app)
            } else {
                val r = Runnable { renderAsync(app) }
                pendingRun = r
                handler.postDelayed(r, 1200)
            }
        }
    }

    // [P6-M40 إصلاح]: قاعدة رموز الطلب لأزرار التحديث لكل نسخة ويدجة — معرّف فريد لكل
    // appWidgetId كي لا تستبدل نسخة ويدجة PendingIntent نسخة أخرى (extras لا تدخل في
    // filterEquals). بعيد عن كل الرموز المستخدمة في التطبيق (0-7، 900، 5001-5003).
    private const val REFRESH_RC_BASE = 1_000_000

    /**
     * [P6-M40 إصلاح] رسم ويدجة واحدة فقط حسب مزوّدها ومعرّفها — يُستهلك من onReceive
     * لزر التحديث اليدوي بدل renderAsync الشاملة. الاستعلامات تُنفَّذ لاحتياجات هذه الويدجة.
     */
    suspend fun renderTarget(context: Context, provider: Class<out AppWidgetProvider>, appWidgetId: Int) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val app = context.applicationContext
        val mgr = AppWidgetManager.getInstance(app)
        when (provider) {
            ReceivablesWidgetProvider::class.java -> renderAll(app, mgr, intArrayOf(appWidgetId))
            SalesTodayWidgetProvider::class.java -> renderSalesAll(app, mgr, intArrayOf(appWidgetId))
            CashWidgetProvider::class.java -> renderCashAll(app, mgr, intArrayOf(appWidgetId))
            LowStockWidgetProvider::class.java -> renderStockAll(app, mgr, intArrayOf(appWidgetId))
        }
    }

    /** نسخة غير معلّقة (non-suspend) من renderTarget للاستدعاء من onReceive — على نطاق IO المشترك */
    fun refreshOne(context: Context, provider: Class<out AppWidgetProvider>, appWidgetId: Int) {
        val app = context.applicationContext
        scope.launch {
            try {
                renderTarget(app, provider, appWidgetId)
            } catch (_: Exception) {
            }
        }
    }

    private fun renderAsync(app: Context) {
        scope.launch {
            try {
                val mgr = AppWidgetManager.getInstance(app)
                // ويدجت الذمم
                val rIds = mgr.getAppWidgetIds(
                    ComponentName(app, ReceivablesWidgetProvider::class.java)
                )
                if (rIds.isNotEmpty()) renderAll(app, mgr, rIds)
                // ويدجت مبيعات اليوم
                val sIds = mgr.getAppWidgetIds(
                    ComponentName(app, SalesTodayWidgetProvider::class.java)
                )
                if (sIds.isNotEmpty()) renderSalesAll(app, mgr, sIds)
                // ويدجت النقد في الصندوق
                val cIds = mgr.getAppWidgetIds(
                    ComponentName(app, CashWidgetProvider::class.java)
                )
                if (cIds.isNotEmpty()) renderCashAll(app, mgr, cIds)
                // ويدجت المخزون المنخفض
                val stIds = mgr.getAppWidgetIds(
                    ComponentName(app, LowStockWidgetProvider::class.java)
                )
                if (stIds.isNotEmpty()) renderStockAll(app, mgr, stIds)
            } catch (_: Exception) {
            }
        }
    }

    /**
 * : خصوصية الويدجت — حين يفعّل المالك «إخفاء الأرقام في الويدجت»
 * تُعرض نقاط بدل المبالغ على الشاشة الرئيسية (الويدجت متاح للناظرين بلا قفل).
*/
    private suspend fun redactOn(ctx: Context): Boolean = try {
        com.superbiz.app.AppGraph.from(ctx).settings.snapshot().redactWidgets
    } catch (_: Exception) {
        false
    }

    /** قناع الإخفاء الموحد */
    internal const val REDACTED = "•••"

    /** يجلب البيانات الحقيقية ثم يرسم كل نسخ الويدجت المضافة */
    suspend fun renderAll(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val snap = collect(context)
        val redact = redactOn(context)
        for (id in ids) mgr.updateAppWidget(id, views(context, snap, redact, id))
    }

    private suspend fun collect(ctx: Context): Snap {
        val g = com.superbiz.app.AppGraph.from(ctx)
        val ar = try { g.ledger.arAp().first } catch (_: Exception) { 0L }  // [P33-P8] قروش
        val overdue = try { g.reports.overdueTotal() } catch (_: Exception) { 0L }  // [P33-P8] قروش
        var debtors = 0
        try {
            // كان العدّ يستعلم رصيد كل طرف على حدة (N+1 بمسح جدول
            // كامل لكل طرف) داخل ميزانية goAsync الضيقة — استعلام تجميعي واحد الآن.
            debtors = g.ledger.balances().count { it.value > 0L }  // [P33-P8] مساواة تامة
        } catch (_: Exception) { }
        val symbol = try {
            g.db.currencies().allOnce().firstOrNull { it.isBase }?.symbol ?: "ر.س"
        } catch (_: Exception) { "ر.س" }
        return Snap(ar, overdue, debtors, symbol)
    }

    private fun views(ctx: Context, snap: Snap, redact: Boolean, appWidgetId: Int): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_receivables)

        // المبلغ الرئيسي — أو رسالة لا ذمم
        v.setTextViewText(
            R.id.widget_amount,
            if (redact) REDACTED
            else if (snap.ar > 0L) Money.formatP(snap.ar, snap.symbol)  // [P33-P8] قروش
            else ctx.getString(R.string.widget_none)
        )
        v.setTextViewText(
            R.id.widget_overdue,
            ctx.getString(
                R.string.widget_overdue,
                if (redact) REDACTED else Money.formatP(snap.overdue, snap.symbol)  // [P33-P8] قروش
            )
        )
        v.setTextViewText(
            R.id.widget_debtors,
            if (redact) REDACTED else ctx.getString(R.string.widget_debtors, snap.debtors)
        )
        val time = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        v.setTextViewText(R.id.widget_updated, ctx.getString(R.string.widget_updated, time))

        // النقر على الجسم: فتح التطبيق على تبويب الديون
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_ROUTE, Routes.DEBTS)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        v.setOnClickPendingIntent(R.id.widget_root, open)

        // زر التحديث: بث تحديث فوري — [P6-M40 إصلاح]: يحمّل هوية هذه الويدجة كي يُرسَم
        // هي وحدها عند النقر (ومع رمز طلب فريد لكل appWidgetId كي لا تتصادم النسخ)
        val refresh = PendingIntent.getBroadcast(
            ctx, REFRESH_RC_BASE + appWidgetId,
            Intent(ctx, ReceivablesWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        v.setOnClickPendingIntent(R.id.widget_refresh, refresh)

        return v
    }

    // ═══════════ : ويدجت «مبيعات اليوم» ═══════════

    // [P33-P8] مبالغ الودجة قروش Long — تُعرض عبر formatP بلا تحويل فاصل
    private data class SalesSnap(
        val total: Long, val count: Int, val profit: Long, val symbol: String
    )

    /** يجلب البيانات الحقيقية ثم يرسم كل نسخ ويدجت المبيعات المضافة */
    suspend fun renderSalesAll(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val snap = collectSales(context)
        val redact = redactOn(context)
        for (id in ids) mgr.updateAppWidget(id, salesViews(context, snap, redact, id))
    }

    private suspend fun collectSales(ctx: Context): SalesSnap {
        val g = com.superbiz.app.AppGraph.from(ctx)
        val today = Dates.startOfDay()
        // [P6-M40 إصلاح]: كان الاستعلام يمسح كل الفواتير منذ التأسيس (allOnce) عند كل حفظ
        // — الودجة تحتاج اليوم فقط: saleInvoicesSince(startOfDay) استعلام محدود في SQL
        // (type=0 AND status<3 AND date>=since) بدل مسح الجدول كاملاً وفلترته في الذاكرة.
        // الرقم المعروض كما هو: todaySales يطبّق نطاق dayStart..dayEnd فوق النتيجة فلا تغيّر
        // في الدلالة (فواتير مستقبلية التاريخ تبقى مستثناة كما كانت).
        // أما الربح فيُحسب من incomeStatement(اليوم، الآن) المحدود زمنياً أصلاً عبر
        // accountSumsBetween — لا يمسح سوى قيود اليوم.
        val (total, count) = try {
            SalesMath.todaySales(g.db.invoices().saleInvoicesSince(today), today)
        } catch (_: Exception) { 0L to 0 }
        // ربح البيع الإجمالي لليوم: الإيراد − تكلفة البضاعة المباعة (من القيد المزدوج)
        val profit = try {
            val ist = g.reports.incomeStatement(today, System.currentTimeMillis())
            // [P33-P8] قائمة الدخل قروش Long — طرح صحيح مباشر
            SalesMath.grossProfit(ist.revenue, ist.cogs)
        } catch (_: Exception) { 0L }
        val symbol = try {
            g.db.currencies().allOnce().firstOrNull { it.isBase }?.symbol ?: "ر.س"
        } catch (_: Exception) { "ر.س" }
        return SalesSnap(total, count, profit, symbol)
    }

    private fun salesViews(ctx: Context, snap: SalesSnap, redact: Boolean, appWidgetId: Int): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_sales_today)

        // المبلغ الرئيسي — أو رسالة لا مبيعات بعد
        v.setTextViewText(
            R.id.widget_sales_amount,
            if (redact) REDACTED
            // [P33-P8] قروش Long — مساواة صحيحة بدل عتبة 0.004 + عرض formatP
            else if (snap.total > 0L) Money.formatP(snap.total, snap.symbol)
            else ctx.getString(R.string.widget_sales_none)
        )
        v.setTextViewText(
            R.id.widget_sales_count,
            if (redact) REDACTED else ctx.getString(R.string.widget_sales_invoices, snap.count)
        )
        v.setTextViewText(
            R.id.widget_sales_profit,
            ctx.getString(
                R.string.widget_sales_profit,
                // [P33-P8] الربح قروش Long — عرض formatP
                if (redact) REDACTED else Money.formatP(snap.profit, snap.symbol)
            )
        )
        val time = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        v.setTextViewText(R.id.widget_sales_updated, ctx.getString(R.string.widget_updated, time))

        // النقر على الجسم: فتح التطبيق على نقطة البيع
        val open = PendingIntent.getActivity(
            ctx, 2,
            Intent(ctx, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_ROUTE, Routes.POS)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        v.setOnClickPendingIntent(R.id.widget_sales_root, open)

        // زر التحديث: بث تحديث فوري — [P6-M40 إصلاح]: هوية الويدجة + رمز طلب فريد
        val refresh = PendingIntent.getBroadcast(
            ctx, REFRESH_RC_BASE + appWidgetId,
            Intent(ctx, SalesTodayWidgetProvider::class.java).apply {
                action = ACTION_REFRESH_SALES
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        v.setOnClickPendingIntent(R.id.widget_sales_refresh, refresh)

        return v
    }

    // ═══════════ : ويدجت «النقد في الصندوق» ═══════════

    private data class CashSnap(  // [P33-P8] قروش
        val balance: Long, val income: Long, val outcome: Long, val symbol: String
    )

    /** يجلب البيانات الحقيقية ثم يرسم كل نسخ ويدجت النقد المضافة */
    suspend fun renderCashAll(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val snap = collectCash(context)
        val redact = redactOn(context)
        for (id in ids) mgr.updateAppWidget(id, cashViews(context, snap, redact, id))
    }

    private suspend fun collectCash(ctx: Context): CashSnap {
        val g = com.superbiz.app.AppGraph.from(ctx)
        // الرصيد الفعلي لحساب «النقد في الصندوق» (1010) من دفتر الأستاذ
        val balance = try { g.reports.cashBalance() } catch (_: Exception) { 0L }  // [P33-P8] قروش
        // حركة اليوم من سلسلة التدفق النقدي (اليوم الأول = اليوم الحالي)
        var income = 0L   // [P33-P8] قروش
        var outcome = 0L
        try {
            g.reports.cashflowSeries(1).firstOrNull()?.let {
                income = it.second
                outcome = it.third
            }
        } catch (_: Exception) { }
        val symbol = try {
            g.db.currencies().allOnce().firstOrNull { it.isBase }?.symbol ?: "ر.س"
        } catch (_: Exception) { "ر.س" }
        return CashSnap(balance, income, outcome, symbol)
    }

    private fun cashViews(ctx: Context, snap: CashSnap, redact: Boolean, appWidgetId: Int): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_cash)

        // الرصيد الكبير — بلون حسب الحالة: نقد أبيض، عجز وردي، فارغ رمادي
        val st = CashMath.state(snap.balance)
        v.setTextViewText(
            R.id.widget_cash_amount,
            when {
                redact -> REDACTED
                st == 0 -> ctx.getString(R.string.widget_cash_empty)
                else -> Money.formatP(snap.balance, snap.symbol)  // [P33-P8] قروش
            }
        )
        v.setTextColor(
            R.id.widget_cash_amount,
            when (st) {
                1 -> 0xFFFFFFFF.toInt()
                -1 -> 0xFFFF5C7A.toInt()
                else -> 0xFF94A3B8.toInt()
            }
        )
        // شرائح حركة اليوم — تُعرض فقط إذا وُجدت حركة فعلية
        val moved = CashMath.hasMovement(snap.income, snap.outcome)
        if (redact) {
            v.setTextViewText(R.id.widget_cash_in, ctx.getString(R.string.widget_cash_in, REDACTED))
            v.setTextViewText(R.id.widget_cash_out, ctx.getString(R.string.widget_cash_out, REDACTED))
        } else if (moved) {
            v.setTextViewText(
                R.id.widget_cash_in,
                ctx.getString(R.string.widget_cash_in, Money.formatP(snap.income, snap.symbol))  // [P33-P8] قروش
            )
            v.setTextViewText(
                R.id.widget_cash_out,
                ctx.getString(R.string.widget_cash_out, Money.formatP(snap.outcome, snap.symbol))  // [P33-P8] قروش
            )
        } else {
            v.setTextViewText(R.id.widget_cash_in, ctx.getString(R.string.widget_cash_quiet))
            v.setTextViewText(R.id.widget_cash_out, CashMath.netToday(snap.income, snap.outcome).let {
                ctx.getString(R.string.widget_cash_net, Money.formatP(it, snap.symbol))  // [P33-P8] قروش
            })
        }
        val time = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        v.setTextViewText(R.id.widget_cash_updated, ctx.getString(R.string.widget_updated, time))

        // النقر على الجسم: فتح التطبيق على تبويب التقارير
        val open = PendingIntent.getActivity(
            ctx, 4,
            Intent(ctx, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_ROUTE, Routes.REPORTS)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        v.setOnClickPendingIntent(R.id.widget_cash_root, open)

        // زر التحديث: بث تحديث فوري — [P6-M40 إصلاح]: هوية الويدجة + رمز طلب فريد
        val refresh = PendingIntent.getBroadcast(
            ctx, REFRESH_RC_BASE + appWidgetId,
            Intent(ctx, CashWidgetProvider::class.java).apply {
                action = ACTION_REFRESH_CASH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        v.setOnClickPendingIntent(R.id.widget_cash_refresh, refresh)

        return v
    }

    // ═══════════ : ويدجت «المخزون المنخفض» ═══════════

    private data class StockSnap(
        val lowCount: Int, val outCount: Int, val severity: Int, val names: List<String>
    )

    /** يجلب البيانات الحقيقية ثم يرسم كل نسخ ويدجت المخزون المضافة */
    suspend fun renderStockAll(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val snap = collectStock(context)
        val redact = redactOn(context)
        for (id in ids) mgr.updateAppWidget(id, stockViews(context, snap, redact, id))
    }

    private suspend fun collectStock(ctx: Context): StockSnap {
        val g = com.superbiz.app.AppGraph.from(ctx)
        val all = try { g.db.products().allOnce() } catch (_: Exception) { emptyList() }
        val low = StockMath.low(all)
        val out = StockMath.out(all)
        return StockSnap(
            lowCount = low.size,
            outCount = out.size,
            severity = StockMath.severity(low.size, out.size),
            names = StockMath.worstNames(all, 3)
        )
    }

    private fun stockViews(ctx: Context, snap: StockSnap, redact: Boolean, appWidgetId: Int): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_lowstock)

        // العدد الكبير بصيغة الجمع العربية الصحيحة — بلون حسب الخطورة:
        // سليم أبيض/رمادي، تنبيه كهرماني، حرج وردي
        v.setTextViewText(
            R.id.widget_stock_amount,
            when {
                redact -> REDACTED
                snap.severity == 0 -> ctx.getString(R.string.widget_stock_allgood)
                else -> ctx.resources.getQuantityString(
                    R.plurals.widget_stock_count, snap.lowCount, snap.lowCount
                )
            }
        )
        v.setTextColor(
            R.id.widget_stock_amount,
            when (snap.severity) {
                2 -> 0xFFFF5C7A.toInt()
                1 -> 0xFFFFC46B.toInt()
                else -> 0xFF94A3B8.toInt()
            }
        )
        v.setTextViewText(
            R.id.widget_stock_out,
            if (redact) REDACTED else ctx.getString(R.string.widget_stock_out, snap.outCount)
        )
        v.setTextViewText(
            R.id.widget_stock_low,
            if (redact) REDACTED else ctx.getString(R.string.widget_stock_low, snap.lowCount)
        )
        v.setTextViewText(
            R.id.widget_stock_names,
            when {
                redact -> REDACTED
                snap.severity == 0 -> ctx.getString(R.string.widget_stock_hint)
                // [P31-B]: الفاصل حسب لغة التطبيق — «،» للعربية و«, » للإنجليزية
                else -> snap.names.joinToString(
                    if (androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
                            .toLanguageTags().startsWith("ar")
                    ) "، " else ", "
                )
            }
        )
        val time = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        v.setTextViewText(R.id.widget_stock_updated, ctx.getString(R.string.widget_updated, time))

        // النقر على الجسم: فتح التطبيق على تبويب المخزون
        val open = PendingIntent.getActivity(
            ctx, 6,
            Intent(ctx, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_ROUTE, Routes.INVENTORY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        v.setOnClickPendingIntent(R.id.widget_stock_root, open)

        // زر التحديث: بث تحديث فوري — [P6-M40 إصلاح]: هوية الويدجة + رمز طلب فريد
        val refresh = PendingIntent.getBroadcast(
            ctx, REFRESH_RC_BASE + appWidgetId,
            Intent(ctx, LowStockWidgetProvider::class.java).apply {
                action = ACTION_REFRESH_STOCK
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        v.setOnClickPendingIntent(R.id.widget_stock_refresh, refresh)

        return v
    }
}
