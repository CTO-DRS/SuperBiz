package com.superbiz.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
// [P7-L12 إصلاح]: أُزيل استيرادَي MaterialTheme وIconChip الميتين في هذا الملف
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.superbiz.app.MainActivity
import com.superbiz.app.R
import com.superbiz.app.domain.PeriodCompare
import com.superbiz.app.domain.ReportSummaryText
import com.superbiz.app.export.ExportSheet
import com.superbiz.app.export.XlsxSheets
import com.superbiz.app.export.DataExport
import com.superbiz.app.export.ExportController
import com.superbiz.app.export.ExportRequest
import com.superbiz.app.ui.components.DonutChart
import com.superbiz.app.ui.components.DualBarChart
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.components.KpiCard
import com.superbiz.app.ui.components.LineChart
import com.superbiz.app.ui.components.RankBar
import com.superbiz.app.ui.components.SectionTitle
import com.superbiz.app.ui.components.QuickAction
import androidx.compose.ui.graphics.Brush
import com.superbiz.app.ui.theme.VioDeep
import com.superbiz.app.ui.theme.Green
import com.superbiz.app.ui.theme.Blue
import com.superbiz.app.ui.components.SubHeader
import com.superbiz.app.ui.theme.Amber
import com.superbiz.app.ui.theme.Cyan
import com.superbiz.app.ui.theme.GreenDeep
import com.superbiz.app.ui.theme.RedDeep
import com.superbiz.app.ui.theme.Vio
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.util.Money
import com.superbiz.app.util.startIntentSafe
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.superbiz.app.vm.AppVM
import com.superbiz.app.vm.ReportsVM
import com.superbiz.app.VMFactory
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import com.superbiz.app.core.AppPrefs
import com.superbiz.app.data.repo.SettingsRepo
import com.superbiz.app.domain.DashboardPrefsP44
import com.superbiz.app.domain.ReportSectionsP45
import com.superbiz.app.ui.insights.InsightsCustomizeDialog
import com.superbiz.app.ui.insights.InsightsGroup
import com.superbiz.app.ui.insights.insightsGroup
import kotlinx.coroutines.launch

@Composable
fun ReportsScreen(appVM: AppVM, nav: NavHostController) {
    val activity = LocalContext.current as? MainActivity ?: return
    val vm: ReportsVM = viewModel(factory = remember { VMFactory(activity) })
    // الرؤى الذكية — التعادل/الاستقرار/الذروة
    // [P5-H9 إصلاح]: VMs الرؤى مشتركة على مستوى النشاط — كانت كل شاشة تنشئ نسختها وتشغل loadAll كاملاً (حتى ×8 تكلفة لكل جولة تنقل)
    val smartVM: com.superbiz.app.vm.SmartInsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    // السعر الأمثل/الحزمة/VaR/الدقة/توازن الفئات/Holt/ساعات العمل
    val r9VM: com.superbiz.app.vm.R9InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    // رؤى الموجة R10 للتقارير
    val r10VM: com.superbiz.app.vm.R10InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    // رؤى الموجة R11 للتقارير (الاتجاه/أرضية الهامش/الانصراف)
    val r11VM: com.superbiz.app.vm.R11InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    // VM الرؤى الذكية للموجة R12 — عبر R12Smart
    val r12VM: com.superbiz.app.vm.R12InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    val r13VM: com.superbiz.app.vm.R13InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    // VM الرؤى الذكية للموجة R14 — عبر R14Smart
    val r14VM: com.superbiz.app.vm.R14InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    val r15VM: com.superbiz.app.vm.R15InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    val data by vm.data.collectAsState()
    val days by vm.periodDays.collectAsState()
    val symbol by appVM.symbol.collectAsState()
    val g = glassColors()
    // [P43-D1] جولة 4 — متحكم التصدير الموحد (حوار الصيغة + SAF بلا إذن تخزين)
    val exporter = ExportController(stringResourceCompat(R.string.export_title))

    // تصدير التقرير الحالي كملف PDF جاهز للمشاركة
    var pendingPdf by remember { mutableStateOf(false) }
    LaunchedEffect(pendingPdf) {
        if (!pendingPdf) return@LaunchedEffect
        pendingPdf = false
        try {
            val d = data
            val inc = d.income ?: return@LaunchedEffect
            val graph = com.superbiz.app.AppGraph.from(activity.application)
            // بناء تقرير PDF على Dispatchers.IO — المشاركة بعده على الخيط الرئيسي
            val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.superbiz.app.pdf.DocPdf.renderReport(
                    activity,
                    graph.settings.snapshot().businessName.ifBlank { activity.getString(R.string.business_default) },
                    symbol, appVM.avatarBitmap(),
                    periodText = activity.getString(
                        if (days == 365) R.string.period_year
                        else R.string.period_days, days
                    ),
                    // [P33-P8]: مبالغ قروش القيد — DocPdf قروش كذلك فلا تحويل
                    incomeRevenue = inc.revenue, incomeOther = inc.otherIncome,
                    incomeCogs = inc.cogs, incomeExpenses = inc.expenses,
                    cash = d.cash,
                    topCustomers = d.topCustomers,
                    topProducts = d.topProducts.map { it.name to it.total },
                    aging = d.aging,
                    trial = d.trial
                )
            }
            com.superbiz.app.pdf.DocPdf.share(activity, file, activity.getString(R.string.report_pdf))
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                activity, activity.getString(R.string.pdf_error),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    // التقرير المالي الكامل — A4 عربي متعدد الصفحات بكل الصفوف بلا اقتطاع
    var pendingA4 by remember { mutableStateOf(false) }
    // التقرير الجاهز — طباعة A4 نظامية / حرارية بلوتوث / مشاركة
    var readyA4 by remember { mutableStateOf<com.superbiz.app.print.ReportReady?>(null) }
    LaunchedEffect(pendingA4) {
        if (!pendingA4) return@LaunchedEffect
        pendingA4 = false
        try {
            // الاستعلامات المحاسبية وبناء A4 على Dispatchers.IO — تعيين الحالة بعدها
            val ready = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val graph = com.superbiz.app.AppGraph.from(activity.application)
                val to = System.currentTimeMillis()
                val from = to - days * 86_400_000L
                val inc = graph.reports.incomeStatement(from, to)
                val cash = graph.reports.cashBalance()
                val periodText = activity.getString(
                    if (days == 365) R.string.period_year
                    else R.string.period_days, days
                )
                val business = graph.settings.snapshot().businessName
                    .ifBlank { activity.getString(R.string.business_default) }
                val file = com.superbiz.app.pdf.A4Report.financial(
                    activity, business, symbol, appVM.avatarBitmap(),
                    periodText = periodText,
                    // [P33-P8]: طبقة A4 قروش كذلك فلا تحويل
                    revenue = inc.revenue, otherIncome = inc.otherIncome,
                    cogs = inc.cogs, expenses = inc.expenses,
                    cash = cash,
                    topCustomers = graph.reports.topCustomers(from, to, 500),
                    topProducts = graph.reports.topProducts(from, to, 500),
                    aging = graph.reports.agingBuckets(),
                    trial = graph.reports.trialBalance()
                )
                com.superbiz.app.print.ReportReady(
                    file, activity.getString(R.string.a4_financial_title),
                    com.superbiz.app.print.ReportReceiptFactory.financial(
                        activity, business, symbol, periodText,
                        // [P33-P8]: طبقة الإيصال قروش كذلك فلا تحويل
                        inc.revenue, inc.otherIncome, inc.cogs, inc.expenses, cash
                    )
                )
            }
            readyA4 = ready
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                activity, activity.getString(R.string.pdf_error),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
    readyA4?.let { ready ->
        com.superbiz.app.print.ReportReadyDialog(ready) { readyA4 = null }
    }

    // ══ : وظيفة 19 — فلتر التصنيف + بديل الاحتياطي R1 — تصدير XLSX متعدد الأوراق ══
    val categories by vm.categories.collectAsState()
    val categoryFilter by vm.categoryFilter.collectAsState()
    // الكتابة داخل coroutine عبر LaunchedEffect — نفس نمط pendingPdf في هذا الملف
    var pendingXlsxUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val xlsxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(com.superbiz.app.export.MIME_XLSX)
    ) { uri ->
        if (uri != null) pendingXlsxUri = uri
    }
    LaunchedEffect(pendingXlsxUri) {
        val uri = pendingXlsxUri ?: return@LaunchedEffect
        pendingXlsxUri = null
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                activity.contentResolver.openOutputStream(uri)?.use { out ->
                    XlsxSheets.write(out, buildReportSheets(activity, vm.data.value, days, symbol))
                } ?: throw IllegalStateException("no output stream")
            }
            android.widget.Toast.makeText(activity, activity.getString(R.string.export_done), android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(activity, activity.getString(R.string.export_fail), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ══ [P44-K2] جولة 5 — مصنف الدفتر التفصيلي متعدد الأوراق: الملخص والمبيعات والمشتريات ══
    // في ملف XLSX واحد عبر XlsxSheets — نفس صفوف chips التصدير المنفرد (P43) حرفياً
    // فأرقام المصنف أرقام البطاقة والتقرير الدوري بلا انحراف
    var pendingDeepWorkbookUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val deepWorkbookLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(com.superbiz.app.export.MIME_XLSX)
    ) { uri ->
        if (uri != null) pendingDeepWorkbookUri = uri
    }
    LaunchedEffect(pendingDeepWorkbookUri) {
        val uri = pendingDeepWorkbookUri ?: return@LaunchedEffect
        pendingDeepWorkbookUri = null
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val dp = vm.data.value.deep ?: throw IllegalStateException("deep register unavailable")
                val catLabels = mapOf(
                    com.superbiz.app.domain.LineTaxP41.KIND_STANDARD to activity.getString(R.string.line_tax_standard),
                    com.superbiz.app.domain.LineTaxP41.KIND_ZERO to activity.getString(R.string.line_tax_zero),
                    com.superbiz.app.domain.LineTaxP41.KIND_EXEMPT to activity.getString(R.string.line_tax_exempt),
                )
                activity.contentResolver.openOutputStream(uri)?.use { out ->
                    XlsxSheets.write(
                        out,
                        com.superbiz.app.export.DeepWorkbookP44.sheets(
                            dp,
                            deepHeaderLabels(activity),
                            deepSummaryHeaderLabels(activity),
                            com.superbiz.app.export.DeepWorkbookP44.Labels(
                                summarySheet = activity.getString(R.string.deep_sheet_summary),
                                salesSheet = activity.getString(R.string.rep_deep_sales),
                                purchasesSheet = activity.getString(R.string.rep_deep_purchases),
                                metricSales = activity.getString(R.string.rep_deep_sales),
                                metricPurchases = activity.getString(R.string.rep_deep_purchases),
                            ),
                            catLabels, ::deepMoney, ::deepDate
                        )
                    )
                } ?: throw IllegalStateException("no output stream")
            }
            android.widget.Toast.makeText(activity, activity.getString(R.string.export_done), android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(activity, activity.getString(R.string.export_fail), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ══ [P45-Q1] جولة 6 — تخصيص أقسام التقارير: خزنة P44 نفسها (dashboard_layout)
    // بمفتاح شاشة مستقل rep_sections — لا مفتاح DataStore جديد ولا ترحيل، والمفاتيح
    // المجهولة محفوظة بعقد التوافق الأمامي للمحرك (هبوط→ترقية لا يفقد التخصيص) ══
    var secRaw by remember { mutableStateOf(AppPrefs.dashboardLayout) }
    var showSecCustomizer by remember { mutableStateOf(false) }
    val secScope = androidx.compose.runtime.rememberCoroutineScope()
    fun persistSections(layout: DashboardPrefsP44.Layout) {
        val json = layout.toJson()
        secRaw = json.ifEmpty { null }
        AppPrefs.dashboardLayout = secRaw
        secScope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    SettingsRepo(activity).setDashboardLayout(json.ifEmpty { null })
                }
            } catch (e: Exception) { /* الافتراضي يبقى سلوك الطوارئ الصادق */ }
        }
    }
    val secLabels = mapOf(
        "tools" to stringResourceCompat(R.string.rep_sec_tools),
        "periods" to stringResourceCompat(R.string.rep_sec_periods),
        "categories" to stringResourceCompat(R.string.rep_sec_categories),
        "kpi" to stringResourceCompat(R.string.rep_sec_kpi),
        "summary" to stringResourceCompat(R.string.rep_sec_summary),
        "compare" to stringResourceCompat(R.string.rep_compare_title),
        "sales_expenses" to stringResourceCompat(R.string.rep_sales_expenses),
        "cashflow" to stringResourceCompat(R.string.rep_cashflow),
        "runway" to stringResourceCompat(R.string.rep_runway_title),
        "aging" to stringResourceCompat(R.string.rep_aging),
        "hours" to stringResourceCompat(R.string.rep_hours_title),
        "vat_rates" to stringResourceCompat(R.string.rep_vat_title),
        "vat_return" to stringResourceCompat(R.string.rep_vatr_title),
        "deep" to stringResourceCompat(R.string.rep_deep_title),
        "customers" to stringResourceCompat(R.string.rep_top_customers),
        "products" to stringResourceCompat(R.string.rep_top_products),
        "margin" to stringResourceCompat(R.string.rep_top_margin),
        "trend" to stringResourceCompat(R.string.rep_trend),
        "smooth" to stringResourceCompat(R.string.rep_smooth_title),
        "seasonality" to stringResourceCompat(R.string.rep_seasonality),
        "concentration" to stringResourceCompat(R.string.rep_concentration),
        "breakeven" to stringResourceCompat(R.string.rep_breakeven),
        "trial" to stringResourceCompat(R.string.rep_trial),
        "insights" to stringResourceCompat(R.string.rep_sec_insights),
    )

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // [P23-FIX] بطاقات الذكاء الثماني كانت هنا أعلى الشاشة قبل الترويسة — نفس شكوى
        // «تشوه المنظر» التي نُقلت على أساسها في الرئيسية والديون (P20). نُقلت إلى نهاية
        // التمرير بعد كل أقسام التقرير — كل بطاقة تُخفى بصدق عندما لا بيانات لها.
        SubHeader(stringResourceCompat(R.string.reports_title))

        // ══ [P45-Q1] جولة 6 — رقاقة «تخصيص الأقسام»: نفس نافذة P44 المعممة بتسميات الأقسام وعنوانها — التعديل يكتب خزنة dashboard_layout المشتركة ══
        // ══ [W1] + رقاقة لوحة المؤشرات (KPIs) — الباب المدفوع الأول، الحديفة داخلها صادقة ══
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Amber.copy(alpha = 0.14f))
                    .clickable { nav.navigate(com.superbiz.app.ui.nav.Routes.KPI_BOARD) { launchSingleTop = true } }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Insights, null, tint = Amber, modifier = Modifier.height(14.dp))
                Spacer(Modifier.padding(horizontal = 3.dp))
                Text(
                    stringResourceCompat(R.string.kpi_chip),
                    color = Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
            }
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(g.accent.copy(alpha = 0.12f))
                    .clickable { showSecCustomizer = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Tune, null, tint = g.accent, modifier = Modifier.height(14.dp))
                Spacer(Modifier.padding(horizontal = 3.dp))
                Text(
                    stringResourceCompat(R.string.rep_sections_customize),
                    color = g.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
            }
        }

        // ══ [P45-Q1] الأقسام القياسية بترتيبها التاريخي — كل قسم التركيبة الموضعية نفسها
        // حرفياً (الشرطية تبقى تخفي نفسها بصدق عند فراغ بياناتها) ══
        val sections = listOf<com.superbiz.app.ui.insights.InsightsGroup>(
            com.superbiz.app.ui.insights.insightsGroup("tools") {
        // ══ [P29-TOOLS] أدوات التقارير داخل بطاقة موحدة بنمط شاشة الديون () —
        // الثلاثة نفسها بوظائفها الحرفية (تقرير A4/تصدير Excel/تقرير PDF) ══
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                QuickAction(
                    Icons.Rounded.Summarize, stringResourceCompat(R.string.tool_rep_a4),
                    Brush.linearGradient(listOf(VioDeep, Vio))
                ) { pendingA4 = true }
                QuickAction(
                    Icons.Rounded.GridOn, stringResourceCompat(R.string.tool_rep_xlsx),
                    Brush.linearGradient(listOf(GreenDeep, Green))
                ) {
                    // بديل الاحتياطي R1 — تصدير XLSX متعدد الأوراق عبر FileProvider/SAF
                    xlsxLauncher.launch(com.superbiz.app.export.DataExport.fileName("superbiz-report", true))
                }
                QuickAction(
                    Icons.Rounded.PictureAsPdf, stringResourceCompat(R.string.tool_rep_pdf),
                    Brush.linearGradient(listOf(Cyan, Blue))
                ) { pendingPdf = true }
            }
        }
            },
            com.superbiz.app.ui.insights.insightsGroup("periods") {
        // فترات
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterPill(stringResourceCompat(R.string.period_7d), days == 7) { vm.periodDays.value = 7 }
            FilterPill(stringResourceCompat(R.string.period_30d), days == 30) { vm.periodDays.value = 30 }
            FilterPill(stringResourceCompat(R.string.period_90d), days == 90) { vm.periodDays.value = 90 }
            FilterPill(stringResourceCompat(R.string.period_year), days == 365) { vm.periodDays.value = 365 }
        }
            },
            com.superbiz.app.ui.insights.insightsGroup("categories") {
        // ══ : وظيفة 19 — رقائق التصنيف (من حقل التصنيف في المنتج) ══
        // تُظهر فقط عند وجود تصنيفات فعلاً — الإخفاء الصادق عند عدم توفر بيانات
        if (categories.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterPill(stringResourceCompat(R.string.rep_cat_all), categoryFilter.isBlank()) {
                    vm.categoryFilter.value = ""
                }
                categories.forEach { c ->
                    FilterPill(c, categoryFilter == c) { vm.categoryFilter.value = c }
                }
            }
            if (categoryFilter.isNotBlank()) {
                Text(
                    stringResourceCompat(R.string.rep_cat_active, categoryFilter) + " — " +
                        stringResourceCompat(R.string.rep_cat_note),
                    fontSize = 11.sp, color = g.textSecondary
                )
            }
        }
            },
            com.superbiz.app.ui.insights.insightsGroup("kpi") {
        // KPI المالية — عند اختيار تصنيف تُحسب البطاقات من بنود منتجاته فقط (وظيفة 19)
        val catStats = data.catStats
        if (catStats != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KpiCard(
                    Icons.Rounded.Insights, Money.format(catStats.sales, symbol),
                    stringResourceCompat(R.string.rep_metric_sales), Vio, Modifier.weight(1f)
                )
                KpiCard(
                    Icons.Rounded.Insights,
                    catStats.profit?.let { Money.format(it, symbol) } ?: "—",
                    stringResourceCompat(R.string.rep_metric_profit),
                    if ((catStats.profit ?: 0.0) >= 0) GreenDeep else RedDeep, Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KpiCard(
                    Icons.Rounded.Insights, catStats.invoiceCount.toString(),
                    stringResourceCompat(R.string.rep_metric_count), Cyan, Modifier.weight(1f)
                )
                KpiCard(
                    Icons.Rounded.Insights,
                    catStats.avgInvoice?.let { Money.format(it, symbol) } ?: "—",
                    stringResourceCompat(R.string.rep_metric_avg), Amber, Modifier.weight(1f)
                )
            }
        } else {
            data.income?.let { inc ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    KpiCard(
                        Icons.Rounded.Insights, Money.formatP(inc.revenue, symbol),
                        stringResourceCompat(R.string.kpi_revenue), Vio, Modifier.weight(1f)
                    )
                    KpiCard(
                        Icons.Rounded.Insights, Money.formatP(inc.expenses, symbol),
                        stringResourceCompat(R.string.kpi_expenses), RedDeep, Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    KpiCard(
                        Icons.Rounded.Insights, Money.formatP(inc.netProfit, symbol),
                        stringResourceCompat(R.string.kpi_profit),
                        if (inc.netProfit >= 0) GreenDeep else RedDeep, Modifier.weight(1f)
                    )
                    KpiCard(
                        Icons.Rounded.Insights, Money.formatP(data.cash, symbol),  // [P33-P8] قروش
                        stringResourceCompat(R.string.kpi_cash), Cyan, Modifier.weight(1f)
                    )
                }
            }
        }
            },
            com.superbiz.app.ui.insights.insightsGroup("summary") {
        // ══ : وظيفة 18 — نسخ/مشاركة ملخص التقرير نصاً ══
        data.income?.let { inc ->
            val clipboard = LocalClipboardManager.current
            val periodLabel = stringResourceCompat(
                if (days == 365) R.string.period_year else R.string.period_days, days
            )
            // نصوص الملخص من الموارد — توطين حقيقي
            val summaryText = ReportSummaryText.build(
                // [P33-P8]: نص الملخص يُبنى من ريال Double (ReportSummaryText غير مُرحَّل) — حدود تحويل للعرض
                sales = Money.fromPiasters(inc.revenue), expenses = Money.fromPiasters(inc.expenses), profit = Money.fromPiasters(inc.netProfit),
                debts = Money.fromPiasters(data.aging.sum()), periodLabel = periodLabel, currency = symbol,  // [P33-P8] النص ريال والمديونية قروش
                labels = com.superbiz.app.domain.ReportSummaryText.Labels(
                    title = stringResourceCompat(com.superbiz.app.R.string.share_rep_title),
                    sales = stringResourceCompat(com.superbiz.app.R.string.share_rep_sales),
                    expenses = stringResourceCompat(com.superbiz.app.R.string.share_rep_expenses),
                    profit = stringResourceCompat(com.superbiz.app.R.string.share_rep_profit),
                    debts = stringResourceCompat(com.superbiz.app.R.string.share_rep_debts),
                )
            )
            GlassCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResourceCompat(R.string.rep_metric_sales) + ": " +
                            // [P33-P8]: الإيراد قروش — العرض عبر formatP
                            Money.formatP(inc.revenue, symbol),
                        fontSize = 13.sp, color = g.textSecondary, modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(summaryText))
                        android.widget.Toast.makeText(
                            activity, activity.getString(R.string.rep_summary_copied),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }) {
                        Icon(Icons.Rounded.ContentCopy, null, tint = g.accent2)
                    }
                    IconButton(onClick = {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, summaryText)
                        }
                        startIntentSafe(activity, android.content.Intent.createChooser(send, null))
                    }) {
                        Icon(Icons.Rounded.Summarize, null, tint = g.accent)
                    }
                }
            }
        }
            },
            com.superbiz.app.ui.insights.insightsGroup("compare") {
        // ══ : وظيفة 16 — مقارنة هذا الشهر × الشهر الماضي (إخفاء صادق بلا بيانات) ══
        val mc = data.monthCompare
        if (mc != null && PeriodCompare.hasData(mc.first, mc.second)) {
            SectionTitle(stringResourceCompat(R.string.rep_compare_title))
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row {
                        Text("", modifier = Modifier.weight(1f))
                        Text(
                            stringResourceCompat(R.string.rep_compare_cur),
                            fontWeight = FontWeight.Bold, color = g.textSecondary,
                            modifier = Modifier.width(92.dp), fontSize = 12.sp
                        )
                        Text(
                            stringResourceCompat(R.string.rep_compare_prev),
                            fontWeight = FontWeight.Bold, color = g.textSecondary, fontSize = 12.sp
                        )
                    }
                    val changes = PeriodCompare.compare(mc.first, mc.second)
                    val labels = listOf(
                        stringResourceCompat(R.string.rep_metric_sales),
                        stringResourceCompat(R.string.rep_metric_profit),
                        stringResourceCompat(R.string.rep_metric_count),
                        stringResourceCompat(R.string.rep_metric_avg)
                    )
                    labels.forEachIndexed { i, label ->
                        val c = changes[i]
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label, color = g.textPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(
                                formatMetric(i, c.current, symbol),
                                color = g.textPrimary, fontSize = 13.sp, modifier = Modifier.width(92.dp)
                            )
                            Text(
                                formatMetric(i, c.previous, symbol),
                                color = g.textSecondary, fontSize = 13.sp
                            )
                            Spacer(Modifier.width(8.dp))
                            // سهم الاتجاه ونسبة التغير — «—» الصادقة عندما لا تُحسب النسبة
                            Text(
                                when (c.direction) {
                                    1 -> "▲"
                                    -1 -> "▼"
                                    else -> "▬"
                                } + " " + (c.ratio?.let { String.format(java.util.Locale.US, "%.0f%%", kotlin.math.abs(it)) } ?: "—"),
                                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                color = when (c.direction) {
                                    1 -> GreenDeep
                                    -1 -> RedDeep
                                    else -> g.textSecondary
                                }
                            )
                        }
                    }
                }
            }
        }

        // المبيعات والمصروفات
            },
            com.superbiz.app.ui.insights.insightsGroup("sales_expenses") {
        SectionTitle(stringResourceCompat(R.string.rep_sales_expenses))
        GlassCard(Modifier.fillMaxWidth()) {
            Box(Modifier.padding(16.dp)) {
                if (data.salesSeries.isNotEmpty()) {
                    DualBarChart(
                        // [P33-P8] سلسلة القروش تُحوَّل ريالاً عند حد الرسم فقط
                        data = data.salesSeries.map { Triple(it.first, Money.fromPiasters(it.second), Money.fromPiasters(it.third)) },
                        labelA = stringResourceCompat(R.string.kpi_sales),
                        labelB = stringResourceCompat(R.string.expense),
                        colorA = Cyan, colorB = Vio
                    )
                }
            }
        }

        // التدفق النقدي
            },
            com.superbiz.app.ui.insights.insightsGroup("cashflow") {
        SectionTitle(stringResourceCompat(R.string.rep_cashflow))
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                if (data.cashflow.isNotEmpty()) {
                    val net = data.cashflow.map { (_, i, o) -> i - o }
                    Row {
                        Text(stringResourceCompat(R.string.income), color = g.textSecondary, modifier = Modifier.weight(1f))
                        Text(Money.formatP(data.cashflow.sumOf { it.second }, symbol), color = GreenDeep, fontWeight = FontWeight.Bold)  // [P33-P8] قروش
                    }
                    Row {
                        Text(stringResourceCompat(R.string.expense), color = g.textSecondary, modifier = Modifier.weight(1f))
                        Text("-" + Money.formatP(data.cashflow.sumOf { it.third }, symbol), color = RedDeep, fontWeight = FontWeight.Bold)  // [P33-P8] قروش
                    }
                    Spacer(Modifier.height(8.dp))
                    LineChart(values = net.map { Money.fromPiasters(it) }, color = Cyan, modifier = Modifier.fillMaxWidth().height(110.dp))  // [P33-P8] الرسم ريالي
                    // ارتباط بيرسون حقيقي — هل المصروفات تلاحق المبيعات؟
                    if (kotlin.math.abs(data.corrExpSales) >= 0.3) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResourceCompat(R.string.corr_label) + " " +
                                String.format(java.util.Locale.US, "%.2f", data.corrExpSales) + " — " +
                                stringResourceCompat(if (data.corrExpSales >= 0.3) R.string.corr_follow else R.string.corr_weak),
                            fontSize = 12.sp, color = g.textSecondary
                        )
                    }
                } else {
                    Text("—", color = g.textSecondary)
                }
            }
        }
            },
            com.superbiz.app.ui.insights.insightsGroup("runway") {
        // ══ : المدرج النقدي — معدل الحرق اليومي وأيام السيولة المتبقية ══
        SectionTitle(stringResourceCompat(R.string.rep_runway_title))
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResourceCompat(R.string.kpi_cash), color = g.textSecondary, modifier = Modifier.weight(1f))
                    Text(Money.formatP(data.cash, symbol), color = Cyan, fontWeight = FontWeight.Bold)  // [P33-P8] قروش
                }
                // معدل الحرق اليومي — يُعرض كقيمة مطلقة (سالب في البيانات = صرف أعلى من قبض)
                Text(
                    stringResourceCompat(R.string.rep_burn, Money.format(kotlin.math.abs(data.burnRate), symbol)),
                    fontSize = 13.sp, color = g.textSecondary
                )
                // runwayDays = -1 يعني لا حرق نقدي (تدفق موجب)، وإلا السيولة تغطي N يوماً
                val burning = data.runwayDays >= 0.0
                Text(
                    if (burning) stringResourceCompat(R.string.rep_runway_days, data.runwayDays.toInt())
                    else stringResourceCompat(R.string.rep_runway_ok),
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = if (burning) Amber else GreenDeep
                )
            }
        }

        // أعمار الديون
            },
            com.superbiz.app.ui.insights.insightsGroup("aging") {
        SectionTitle(stringResourceCompat(R.string.rep_aging))
        GlassCard(Modifier.fillMaxWidth()) {
            // [P7-L10 إصلاح]: كل السلات صفراً ⇒ حالة فراغ صريحة بدل الحلقة الرمادية والقيم الصفرية
            // (كان DonutChart يرسم حلقة رمادية عند total<=1e-9 مع coerceAtLeast(1.0) — تناقض
            // عقد الصدق: الدونات لا تُعرض بلا بيانات)
            val aging = data.aging
            if (aging.sum() <= 1e-9) {
                Text(
                    stringResourceCompat(R.string.rep_aging_empty),
                    fontSize = 13.sp, color = g.textSecondary,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    DonutChart(
                        // [P33-P8] دلاء الأعمار قروش — الرسم نسب فيتحول ريالاً عند الحد
                        segments = listOf(
                            Money.fromPiasters(aging.getOrElse(0) { 0L }) to GreenDeep,
                            Money.fromPiasters(aging.getOrElse(1) { 0L }) to Cyan,
                            Money.fromPiasters(aging.getOrElse(2) { 0L }) to Amber,
                            Money.fromPiasters(aging.getOrElse(3) { 0L }) to RedDeep
                        ),
                        modifier = Modifier.size(120.dp)
                    )
                    Spacer(Modifier.width(18.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val labels = listOf("< 30", "30-60", "60-90", "> 90")
                        val colors = listOf(GreenDeep, Cyan, Amber, RedDeep)
                        labels.forEachIndexed { i, lb ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                com.superbiz.app.ui.components.ChartLegend(colors[i], lb)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    Money.formatP(aging.getOrElse(i) { 0L }, symbol),  // [P33-P8] قروش
                                    fontSize = 12.sp, color = g.textPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
            },
            com.superbiz.app.ui.insights.insightsGroup("hours") {
        // ══ : ساعات الذروة — توزيع فواتير البيع على 24 ساعة + أيام الأسبوع ══
        if (data.hourProfile.isNotEmpty() || data.dowProfile.isNotEmpty()) {
            SectionTitle(stringResourceCompat(R.string.rep_hours_title))
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResourceCompat(R.string.rep_hours_sub), fontSize = 12.sp, color = g.textSecondary)
                    val hours = data.hourProfile
                    if (hours.isEmpty() || hours.all { it == 0 }) {
                        Text("—", color = g.textSecondary)
                    } else {
                        val maxH = hours.maxOf { it }.coerceAtLeast(1)
                        val peakHour = hours.indices.maxBy { hours[it] }
                        // 24 عموداً بارتفاع معياري — ساعة الذروة بلون التمييز (الترتيب كما هو بلا عكس قسري)
                        Row(
                            Modifier.fillMaxWidth().height(80.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            hours.forEachIndexed { h, c ->
                                val frac = c.toFloat() / maxH
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(4.dp + 72.dp * frac)
                                        .background(
                                            if (h == peakHour) g.accent2 else g.textSecondary.copy(alpha = 0.35f),
                                            RoundedCornerShape(2.dp)
                                        )
                                )
                            }
                        }
                        // ملخص الذروة: نطاق الساعة + عدد الفواتير فيها — بلغة الواجهة
                        val arHours = LocalConfiguration.current.locales[0].language == "ar"
                        val uiLocale = java.util.Locale(if (arHours) "ar" else "en")
                        val peakRange = String.format(uiLocale, "%02d:00–%02d:00", peakHour, peakHour + 1)
                        Text(
                            "$peakRange • " + String.format(uiLocale, "%d", hours[peakHour]),
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = g.accent2
                        )
                    }
                    // النشاط حسب أيام الأسبوع — أسماء الأيام من لغة الواجهة (0=الأحد)
                    val dow = data.dowProfile
                    if (dow.isNotEmpty() && dow.any { it > 0 }) {
                        Text(
                            stringResourceCompat(R.string.rep_dow_title),
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = g.textSecondary
                        )
                        val arDow = LocalConfiguration.current.locales[0].language == "ar"
                        val dayFmt = java.text.SimpleDateFormat("EEE", java.util.Locale(if (arDow) "ar" else "en"))
                        val dowLocale = java.util.Locale(if (arDow) "ar" else "en")
                        val peakDow = dow.indices.maxBy { dow[it] }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            dow.forEachIndexed { i, c ->
                                val cal = java.util.Calendar.getInstance()
                                cal.set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.SUNDAY + i)
                                Column(
                                    Modifier.weight(1f),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(dayFmt.format(cal.time), fontSize = 10.sp, color = g.textSecondary)
                                    Text(
                                        String.format(dowLocale, "%d", c),
                                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                        color = if (i == peakDow) g.accent2 else g.textPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
            },
            com.superbiz.app.ui.insights.insightsGroup("vat_rates") {
        // ══ : ملخص الضريبة حسب النسبة — تجميع حقيقي من فواتير الفترة ══
        SectionTitle(stringResourceCompat(R.string.rep_vat_title))
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (data.vatRows.isEmpty()) {
                    Text(stringResourceCompat(R.string.rep_vat_none), color = g.textSecondary, fontSize = 13.sp)
                } else {
                    Row {
                        Text("%", fontWeight = FontWeight.Bold, color = g.textSecondary, modifier = Modifier.width(44.dp))
                        Text(stringResourceCompat(R.string.subtotal), fontWeight = FontWeight.Bold, color = g.textSecondary, modifier = Modifier.weight(1f))
                        Text(stringResourceCompat(R.string.tax), fontWeight = FontWeight.Bold, color = g.textSecondary)
                    }
                    data.vatRows.forEach { (rate, net, tax) ->
                        Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${Math.round(rate * 100)}%",
                                color = g.textPrimary, modifier = Modifier.width(44.dp), fontSize = 13.sp
                            )
                            Text(
                                Money.formatP(net, symbol),  // [P33-P8] قروش
                                color = g.textPrimary, modifier = Modifier.weight(1f), fontSize = 13.sp
                            )
                            Text(Money.formatP(tax, symbol), color = g.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)  // [P33-P8] قروش
                        }
                    }
                }
            }
        }
            },
            com.superbiz.app.ui.insights.insightsGroup("vat_return") {
        // ══ [P38-Z3] تقرير الضريبة الدوري — أساس الإقرار (مخرجات/مدخلات/صافي) ══
        // مصدره المحجوز في الفواتير (ZatcaReturnP38 عبر ReportsVM) — تختفي البطاقة
        // كلياً عند فشل القراءة (null) فلا حالة فارغة كاذبة
        data.vatReturn?.let { vr ->
            SectionTitle(stringResourceCompat(R.string.rep_vatr_title))
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val vatRowsP38 = listOf(
                        stringResourceCompat(R.string.rep_vatr_sales) to vr.salesNet,
                        stringResourceCompat(R.string.rep_vatr_zero) to vr.salesZeroNet,
                        // [P41-L2] خانة المعفاة المستقلة — الصفوف التاريخية تبقى في الصفرية
                        stringResourceCompat(R.string.rep_vatr_exempt) to vr.salesExemptNet,
                        stringResourceCompat(R.string.rep_vatr_output) to vr.outputVat,
                        stringResourceCompat(R.string.rep_vatr_purchases) to vr.purchasesNet,
                        stringResourceCompat(R.string.rep_vatr_input) to vr.inputVat,
                        stringResourceCompat(R.string.rep_vatr_net) to vr.netVat,
                    )
                    vatRowsP38.forEachIndexed { i, (label, value) ->
                        val last = i == vatRowsP38.lastIndex
                        Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, color = g.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(
                                Money.formatP(value, symbol),  // [P33-P8] قروش
                                color = if (last) g.accent2 else g.textPrimary,
                                fontWeight = if (last) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp
                            )
                        }
                    }
                    if (vr.netVat < 0L) {
                        Text(
                            stringResourceCompat(R.string.rep_vatr_refund),
                            color = g.textSecondary, fontSize = 12.sp
                        )
                    }
                }
            }
        }
            },
            com.superbiz.app.ui.insights.insightsGroup("deep") {
        // ══ [P43-D1] جولة 4 — الدفتر التفصيلي (تصدير عميق): صفوف لكل بند بأعمدة الضريبة v11 ══
        // مصدره المحجوز في الفواتير عبر DeepExportP43 — نفس قرار zatcaReturn حرفياً:
        // الواعية بالسطر تنزل بنداً بنداً بنسبها الفعالة والتاريخية صفاً مجمعاً من رأسها،
        // فأرقام البطاقة هي نفس أرقام التقرير الدوري بلا انحراف — تختفي البطاقة كلياً
        // عند فشل القراءة (null) فلا حالة فارغة كاذبة
        data.deep?.let { dp ->
            SectionTitle(stringResourceCompat(R.string.rep_deep_title))
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (dp.sales.isEmpty() && dp.purchases.isEmpty()) {
                        Text(stringResourceCompat(R.string.rep_deep_none), color = g.textSecondary, fontSize = 13.sp)
                    } else {
                        val catLabels = mapOf(
                            com.superbiz.app.domain.LineTaxP41.KIND_STANDARD to stringResourceCompat(R.string.line_tax_standard),
                            com.superbiz.app.domain.LineTaxP41.KIND_ZERO to stringResourceCompat(R.string.line_tax_zero),
                            com.superbiz.app.domain.LineTaxP41.KIND_EXEMPT to stringResourceCompat(R.string.line_tax_exempt),
                        )
                        val header = listOf(
                            stringResourceCompat(R.string.invoice_number),
                            stringResourceCompat(R.string.date),
                            stringResourceCompat(R.string.party),
                            stringResourceCompat(R.string.item),
                            stringResourceCompat(R.string.qty),
                            stringResourceCompat(R.string.price),
                            stringResourceCompat(R.string.discount),
                            stringResourceCompat(R.string.deep_col_net),
                            stringResourceCompat(R.string.deep_col_cat),
                            stringResourceCompat(R.string.deep_col_rate),
                            stringResourceCompat(R.string.tax),
                        )
                        Row {
                            Text(stringResourceCompat(R.string.rep_deep_sales), color = g.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(
                                stringResourceCompat(R.string.rep_deep_rows_count, dp.salesTotals.rows)
                                    + " · " + Money.formatP(dp.salesTotals.netP, symbol)
                                    + " · " + Money.formatP(dp.salesTotals.vatP, symbol),
                                color = g.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold
                            )
                        }
                        Row {
                            Text(stringResourceCompat(R.string.rep_deep_purchases), color = g.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(
                                stringResourceCompat(R.string.rep_deep_rows_count, dp.purchasesTotals.rows)
                                    + " · " + Money.formatP(dp.purchasesTotals.netP, symbol)
                                    + " · " + Money.formatP(dp.purchasesTotals.vatP, symbol),
                                color = g.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold
                            )
                        }
                        Text(stringResourceCompat(R.string.rep_deep_note), color = g.textSecondary, fontSize = 11.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(g.accent.copy(alpha = 0.14f))
                                    .clickable {
                                        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                                        exporter(
                                            ExportRequest(
                                                fileName = DataExport.fileName("deep-sales", false),
                                                isXlsx = false,
                                                header = header,
                                                rows = com.superbiz.app.export.DeepWorkbookP44.deepRows(
                                                    dp.sales, catLabels, ::deepMoney
                                                ) { ts -> fmt.format(java.util.Date(ts)) }
                                            )
                                        )
                                    }
                                    .padding(10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(stringResourceCompat(R.string.rep_deep_export_sales), color = g.accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(g.accent.copy(alpha = 0.14f))
                                    .clickable {
                                        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                                        exporter(
                                            ExportRequest(
                                                fileName = DataExport.fileName("deep-purchases", false),
                                                isXlsx = false,
                                                header = header,
                                                rows = com.superbiz.app.export.DeepWorkbookP44.deepRows(
                                                    dp.purchases, catLabels, ::deepMoney
                                                ) { ts -> fmt.format(java.util.Date(ts)) }
                                            )
                                        )
                                    }
                                    .padding(10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(stringResourceCompat(R.string.rep_deep_export_purchases), color = g.accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                        // [P44-K2] جولة 5: رقاقة المصنف الكامل — أوراق الملخص والمبيعات والمشتريات في ملف XLSX واحد
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(g.accent.copy(alpha = 0.14f))
                                .clickable { deepWorkbookLauncher.launch(com.superbiz.app.export.DataExport.fileName("deep-register", true)) }
                                .padding(10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(stringResourceCompat(R.string.rep_deep_export_workbook), color = g.accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
            },
            com.superbiz.app.ui.insights.insightsGroup("customers") {
        // أفضل العملاء
        if (data.topCustomers.isNotEmpty()) {
            SectionTitle(stringResourceCompat(R.string.rep_top_customers))
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    // [P33-P8] المبالغ قروش — النسبة قسمة عائمة لا صحيحة
                    val maxV = data.topCustomers.maxOf { it.second }.coerceAtLeast(1L)
                    data.topCustomers.forEachIndexed { i, (name, v) ->
                        RankBar(i, name, Money.formatP(v, symbol), (v.toDouble() / maxV).toFloat(), Vio)
                    }
                }
            }
        }
            },
            com.superbiz.app.ui.insights.insightsGroup("products") {
        // أفضل المنتجات
        if (data.topProducts.isNotEmpty()) {
            SectionTitle(stringResourceCompat(R.string.rep_top_products))
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    // [P33-P8] المبالغ قروش — النسبة قسمة عائمة لا صحيحة
                    val maxV = data.topProducts.maxOf { it.total }.coerceAtLeast(1L)
                    data.topProducts.forEachIndexed { i, p ->
                        RankBar(i, p.name, Money.formatP(p.total, symbol), (p.total.toDouble() / maxV).toFloat(), Cyan)
                    }
                }
            }
        }
            },
            com.superbiz.app.ui.insights.insightsGroup("margin") {
        // ══ : وظيفة 17 — أعلى المنتجات ربحية (هامش لكل منتج من مبيعات الفترة) ══
        if (data.topProfit.isNotEmpty()) {
            SectionTitle(stringResourceCompat(R.string.rep_top_margin))
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // ملاحظة صدق: الأساس تكلفة المنتج الحالية وقد تختلف عن لحظة البيع
                    Text(
                        stringResourceCompat(R.string.rep_top_margin_note),
                        fontSize = 11.sp, color = g.textSecondary
                    )
                    data.topProfit.forEachIndexed { i, row ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${i + 1}. ${row.name}",
                                fontSize = 13.sp, color = g.textPrimary,
                                modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Text(
                                Money.format(row.revenue, symbol),
                                fontSize = 12.sp, color = g.textSecondary
                            )
                            Spacer(Modifier.width(8.dp))
                            // الهامش — «—» الصادقة للمنتج بلا أساس تكلفة
                            Text(
                                stringResourceCompat(R.string.rep_margin) + ": " +
                                    (row.margin?.let { Money.format(it, symbol) } ?: "—"),
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                color = when {
                                    row.margin == null -> g.textSecondary
                                    row.margin >= 0 -> GreenDeep
                                    else -> RedDeep
                                }
                            )
                        }
                    }
                }
            }
        }
            },
            com.superbiz.app.ui.insights.insightsGroup("trend") {
        // ══ : اتجاه المبيعات — متوسط متحرك + انحدار خطي وتوقع نهاية الشهر ══
        val trend = data.trend
        if (data.ma7.isNotEmpty() && trend != null) {
            SectionTitle(stringResourceCompat(R.string.rep_trend))
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResourceCompat(
                            when {
                                trend.direction > 0 -> R.string.trend_up
                                trend.direction < 0 -> R.string.trend_down
                                else -> R.string.trend_flat
                            }
                        ),
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            trend.direction > 0 -> GreenDeep
                            trend.direction < 0 -> RedDeep
                            else -> g.textPrimary
                        }
                    )
                    LineChart(values = data.ma7, color = Cyan, modifier = Modifier.fillMaxWidth().height(100.dp))
                    Text(stringResourceCompat(R.string.rep_ma_note), fontSize = 11.sp, color = g.textSecondary)
                    if (trend.projectedMonthEnd > 0) {
                        Text(
                            stringResourceCompat(R.string.trend_projection, Money.format(trend.projectedMonthEnd, symbol)),
                            fontSize = 13.sp, color = g.accent2, fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
            },
            com.superbiz.app.ui.insights.insightsGroup("smooth") {
        // ══ : التنعيم الأسي EMA + إسقاط Holt لـ7 أيام قادمة ══
        if (data.ema7.isNotEmpty() || data.holt7.isNotEmpty()) {
            SectionTitle(stringResourceCompat(R.string.rep_smooth_title))
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResourceCompat(R.string.rep_ema_hint), fontSize = 12.sp, color = g.textSecondary)
                    if (data.ema7.isNotEmpty()) {
                        LineChart(values = data.ema7, color = Vio, modifier = Modifier.fillMaxWidth().height(90.dp))
                        Text(
                            "EMA: " + Money.format(data.ema7.last(), symbol),
                            fontSize = 12.sp, color = g.textSecondary
                        )
                    }
                    if (data.wma7.isNotEmpty()) {
                        Text(
                            stringResourceCompat(R.string.rep_wma, Money.format(data.wma7.last(), symbol)),
                            fontSize = 12.sp, color = g.textSecondary
                        )
                    }
                    if (data.holt7.isNotEmpty()) {
                        Text(
                            stringResourceCompat(R.string.rep_holt_title, Money.format(data.holt7.sum(), symbol)),
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = g.accent2
                        )
                    }
                }
            }
        }
            },
            com.superbiz.app.ui.insights.insightsGroup("seasonality") {
        // ══ : الموسمية الشهرية — مؤشر 12 شهراً حول 1.0 ══
        if (data.seasonality.size == 12 && data.seasonMonthStarts.size == 12) {
            SectionTitle(stringResourceCompat(R.string.rep_seasonality))
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val hasData = data.seasonality.any { it > 0.05 }
                    if (!hasData) {
                        Text(stringResourceCompat(R.string.season_insufficient), color = g.textSecondary, fontSize = 13.sp)
                    } else {
                        val arLocale = LocalConfiguration.current.locales[0].language == "ar"
                        fun monthName(ms: Long): String = java.text.SimpleDateFormat(
                            "MMM", java.util.Locale(if (arLocale) "ar" else "en")
                        ).format(java.util.Date(ms))
                        val peakIdx = data.seasonality.indices.maxBy { data.seasonality[it] }
                        val lowIdx = data.seasonality.indices.minBy { data.seasonality[it] }
                        LineChart(values = data.seasonality, color = Amber, modifier = Modifier.fillMaxWidth().height(90.dp))
                        Text(
                            stringResourceCompat(R.string.season_peak, monthName(data.seasonMonthStarts[peakIdx])),
                            fontSize = 12.sp, color = GreenDeep, fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            stringResourceCompat(R.string.season_low, monthName(data.seasonMonthStarts[lowIdx])),
                            fontSize = 12.sp, color = g.textSecondary
                        )
                    }
                }
            }
        }
            },
            com.superbiz.app.ui.insights.insightsGroup("concentration") {
        // ══ : تركّز العملاء — جيني وحصة الأفضل ══
        if (data.giniCustomers > 0.0 || data.top3Share > 0.0) {
            SectionTitle(stringResourceCompat(R.string.rep_concentration))
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val gini = data.giniCustomers
                    Text(
                        stringResourceCompat(
                            when {
                                gini >= 0.6 -> R.string.concentration_high
                                gini >= 0.35 -> R.string.concentration_moderate
                                else -> R.string.concentration_low
                            }
                        ),
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            gini >= 0.6 -> RedDeep
                            gini >= 0.35 -> Amber
                            else -> GreenDeep
                        }
                    )
                    // باريتو 80/20 حقيقي — أقل عدد عملاء يغطي 80% من الإيراد
                    if (data.pareto80 > 0) {
                        Text(
                            stringResourceCompat(R.string.rep_pareto, data.pareto80.toString()),
                            fontSize = 12.sp, color = g.textSecondary
                        )
                    }
                    if (data.top3Share > 0) {
                        Text(
                            stringResourceCompat(R.string.top3_share, (data.top3Share * 100).toInt().toString()),
                            fontSize = 13.sp, color = g.textSecondary
                        )
                    }
                }
            }
        }
            },
            com.superbiz.app.ui.insights.insightsGroup("breakeven") {
        // ══ : نقطة التعادل للفترة المختارة ══
        val inc0 = data.income
        if (inc0 != null && data.breakEvenRevenue != 0.0) {
            SectionTitle(stringResourceCompat(R.string.rep_breakeven))
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (data.breakEvenRevenue < 0) {
                        Text(stringResourceCompat(R.string.breakeven_impossible), color = RedDeep, fontWeight = FontWeight.SemiBold)
                    } else {
                        val reached = inc0.revenue >= data.breakEvenRevenue
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResourceCompat(R.string.breakeven_value), color = g.textSecondary, modifier = Modifier.weight(1f))
                            Text(
                                Money.format(data.breakEvenRevenue, symbol),
                                fontWeight = FontWeight.Bold,
                                color = if (reached) GreenDeep else Amber
                            )
                        }
                        Text(stringResourceCompat(R.string.breakeven_note), fontSize = 11.sp, color = g.textSecondary)
                    }
                }
            }
        }
            },
            com.superbiz.app.ui.insights.insightsGroup("trial") {
        // ميزان المراجعة
        SectionTitle(stringResourceCompat(R.string.rep_trial))
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                if (data.trial.isEmpty()) {
                    Text("—", color = g.textSecondary)
                } else {
                    Row {
                        Text(stringResourceCompat(R.string.item), fontWeight = FontWeight.Bold, color = g.textSecondary, modifier = Modifier.weight(1f))
                        Text(stringResourceCompat(R.string.debit), fontWeight = FontWeight.Bold, color = g.textSecondary, modifier = Modifier.width(84.dp))
                        Text(stringResourceCompat(R.string.credit), fontWeight = FontWeight.Bold, color = g.textSecondary)
                    }
                    Spacer(Modifier.height(6.dp))
                    // ميزان المراجعة بلغة التطبيق — العربية nameAr وغيرها nameEn من الدليل الثابت
                    val arTrial = LocalConfiguration.current.locales[0].language == "ar"
                    data.trial.forEach { row ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text(
                                if (arTrial) row.nameAr else row.nameEn,
                                color = g.textPrimary, modifier = Modifier.weight(1f), fontSize = 13.sp
                            )
                            Text(
                                // [P33-P8]: أرصدة ميزان المراجعة قروش — العرض عبر numP (مدين/دائن)
                                if (row.balance >= 0) Money.numP(row.balance) else "—",
                                color = g.textSecondary, modifier = Modifier.width(84.dp), fontSize = 13.sp
                            )
                            Text(
                                if (row.balance < 0) Money.numP(-row.balance) else "—",
                                color = g.textSecondary, fontSize = 13.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    // [P33-P8]: مجاميع الميزان قروش — جمع صحيح بلا فلواط
                    val dTot = data.trial.sumOf { if (it.balance >= 0) it.balance else 0L }
                    val cTot = data.trial.sumOf { if (it.balance < 0) -it.balance else 0L }
                    Row {
                        Text("∑", fontWeight = FontWeight.Bold, color = g.textPrimary, modifier = Modifier.weight(1f))
                        Text(Money.numP(dTot), fontWeight = FontWeight.Bold, color = g.textPrimary, modifier = Modifier.width(84.dp))
                        Text(Money.numP(cTot), fontWeight = FontWeight.Bold, color = g.textPrimary)
                    }
                }
            }
        }
            },
            com.superbiz.app.ui.insights.insightsGroup("insights") {
        // ══ [P23-FIX] بطاقات الذكاء الثماني — نهاية التمرير بعد كل أقسام التقرير ══
        // [P44-K1] جولة 5: المكدس المخصص — ترتيب/إظهار المجموعات الثماني بافتضاض المستخدم (AppPrefs)
        com.superbiz.app.ui.insights.InsightsStack(
            com.superbiz.app.domain.DashboardPrefsP44.SCREEN_REPORTS,
            listOf(
                // نقطة التعادل/استقرار الأرباح/ساعات الذروة
                com.superbiz.app.ui.insights.insightsGroup("smart") { com.superbiz.app.ui.insights.ReportsSmartCard(smartVM) },
                // السعر الأمثل/الحزمة/VaR/الدقة/توازن الفئات/توقع Holt/ساعات العمل
                com.superbiz.app.ui.insights.insightsGroup("r9") { com.superbiz.app.ui.insights.ReportsR9Card(r9VM, appVM) },
                // هامش المحفظة/أزواج الرفع/DSO/شواذ الفواتير/المنتج التالي
                com.superbiz.app.ui.insights.insightsGroup("r10") { com.superbiz.app.ui.insights.ReportsR10Card(r10VM) },
                // اتجاه الإيراد/أرضية الهامش/عملاء صامتون
                com.superbiz.app.ui.insights.insightsGroup("r11") { com.superbiz.app.ui.insights.ReportsR11Card(r11VM) },
                // زحف التكلفة/أثر السعر/انجراف التركّز
                com.superbiz.app.ui.insights.insightsGroup("r12") { com.superbiz.app.ui.insights.ReportsR12Card(appVM, r12VM) },
                com.superbiz.app.ui.insights.insightsGroup("r13") { com.superbiz.app.ui.insights.ReportsR13Card(appVM, r13VM) },
                com.superbiz.app.ui.insights.insightsGroup("r14") { com.superbiz.app.ui.insights.ReportsR14Card(appVM, r14VM) },
                com.superbiz.app.ui.insights.insightsGroup("r15") { com.superbiz.app.ui.insights.ReportsR15Card(appVM, r15VM) },
            )
        )
            },
        )

        // ══ [P45-Q1] العرض بالترتيب الفعّال — المحرك يعيد مفاتيح القياسي فقط
        // والمخفي يحفظ موضعه كي يعود إليه (عقد DashboardPrefsP44) ══
        ReportSectionsP45.order(secRaw).forEach { key ->
            sections.first { it.key == key }.content()
        }

        if (showSecCustomizer) {
            InsightsCustomizeDialog(
                screenKey = ReportSectionsP45.KEY,
                canonical = ReportSectionsP45.CANONICAL,
                raw = secRaw,
                onChange = { persistSections(it) },
                onDismiss = { showSecCustomizer = false },
                labels = secLabels,
                title = stringResourceCompat(R.string.rep_sec_title),
                hint = stringResourceCompat(R.string.rep_sec_hint),
            )
        }

    }
}

// ═══════════════════════════════════════════════════════════════
// — أدوات مساعدة لموجة التقارير
// ═══════════════════════════════════════════════════════════════
/** تنسيق قيمة مؤشر المقارنة: الفهارس 0،1،3 مبالغ مالية و2 عدد فواتير */
private fun formatMetric(metricIndex: Int, value: Double, symbol: String): String =
    if (metricIndex == 2) value.toInt().toString() else Money.format(value, symbol)

/** أوراق تصدير التقرير متعدد الأوراق (بديل الاحتياطي R1) — تُبنى من بيانات الشاشة الحالية */
/**
 * [P44-K2] جولة 5 — محولات خلايا الدفتر: صفوف الدفتر التفصيلي جاهزة التصدير
 * (CSV/XLSX عبر ExportController والمصنف الكامل عبر XlsxSheets) انتقلت إلى
 * المحرك النقي DeepWorkbookP44.deepRows — هنا محولاتها فقط: المبالغ ريال
 * Double (خلية رقمية حقيقية في Excel وليست نصاً)، والتاريخ ISO قصير محدد
 * الإعداد كي يبقى الملف مستقراً عبر المناطق، وفئة الضريبة من سلاسل المنتقي
 * نفسها (قياسية/صفرية/معفاة) — والصف المجمّع للتاريخية بلا وصف بند يظهر
 * وصفه «—». حرس الحِقن في DataExport يمرّ على كل الحقول عند الكتابة (نمط P20)
 * فلا تُنفَّذ قيم تبدأ بـ = + @ كصيغ.
 */
private fun deepMoney(p: Long): Double = Money.fromPiasters(p)

/** [P44-K2] تاريخ ISO قصير لخلايا الدفتر — نمط P43 حرفياً */
private fun deepDate(ts: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(ts))

/** [P44-K2] رأس الأوراق التفصيلية (11 عموداً — سلاسل البطاقة نفسها) خارج التركيب */
private fun deepHeaderLabels(ctx: android.content.Context): List<String> = listOf(
    ctx.getString(R.string.invoice_number),
    ctx.getString(R.string.date),
    ctx.getString(R.string.party),
    ctx.getString(R.string.item),
    ctx.getString(R.string.qty),
    ctx.getString(R.string.price),
    ctx.getString(R.string.discount),
    ctx.getString(R.string.deep_col_net),
    ctx.getString(R.string.deep_col_cat),
    ctx.getString(R.string.deep_col_rate),
    ctx.getString(R.string.tax),
)

/** [P44-K2] رأس ورقة ملخص الدفتر (4 أعمدة: البند/العدد/الصافي/الضريبة) */
private fun deepSummaryHeaderLabels(ctx: android.content.Context): List<String> = listOf(
    ctx.getString(R.string.item),
    ctx.getString(R.string.deep_sum_count),
    ctx.getString(R.string.deep_col_net),
    ctx.getString(R.string.tax),
)

private fun buildReportSheets(
    ctx: android.content.Context,
    d: com.superbiz.app.vm.ReportsVM.ReportsData,
    days: Int,
    symbol: String
): List<ExportSheet> {
    fun s(id: Int) = ctx.getString(id)
    val periodLabel = ctx.getString(
        if (days == 365) R.string.period_year else R.string.period_days, days
    )
    val sheets = mutableListOf<ExportSheet>()

    // ورقة الملخص
    val income = d.income
    val summaryRows = mutableListOf<List<Any?>>()
    if (income != null) {
        // [P33-P8]: خلايا XLSX رقمية بالريال — حقول قروش تُحوَّل عبر fromPiasters (قاعدة 7 من ورقة الترحيل)
        summaryRows += listOf(s(R.string.rep_metric_sales), Money.fromPiasters(income.revenue))
        summaryRows += listOf(s(R.string.kpi_expenses), Money.fromPiasters(income.expenses))
        summaryRows += listOf(s(R.string.rep_metric_profit), Money.fromPiasters(income.netProfit))
        summaryRows += listOf(s(R.string.kpi_cash), d.cash)
        summaryRows += listOf(s(R.string.rep_debts_open), d.aging.sum())
    }
    sheets += ExportSheet(s(R.string.rep_sheet_summary), listOf(s(R.string.item), periodLabel), summaryRows)

    // ورقة ميزان المراجعة
    sheets += ExportSheet(
        s(R.string.rep_sheet_trial),
        listOf(s(R.string.item), s(R.string.debit), s(R.string.credit)),
        // [P33-P8]: أرصدة القيد قروش — خلايا XLSX بالريال عبر fromPiasters
        d.trial.map { listOf<Any?>(it.nameAr, Money.fromPiasters(it.balance), if (it.balance < 0) Money.fromPiasters(-it.balance) else 0.0) }
    )

    // ورقة أفضل العملاء
    if (d.topCustomers.isNotEmpty()) {
        sheets += ExportSheet(
            s(R.string.rep_sheet_customers),
            listOf(s(R.string.party), s(R.string.total)),
            d.topCustomers.map { listOf<Any?>(it.first, it.second) }
        )
    }

    // ورقة أفضل المنتجات
    if (d.topProducts.isNotEmpty()) {
        sheets += ExportSheet(
            s(R.string.rep_sheet_products),
            listOf(s(R.string.item), s(R.string.stock_qty), s(R.string.total)),
            d.topProducts.map { listOf<Any?>(it.name, it.qty, it.total) }
        )
    }

    // ورقة أعمار الديون
    if (d.aging.isNotEmpty()) {
        val bucketLabels = listOf("< 30", "30-60", "60-90", "> 90")
        sheets += ExportSheet(
            s(R.string.rep_sheet_aging),
            listOf(s(R.string.item), s(R.string.total)),
            bucketLabels.mapIndexed { i, lb -> listOf<Any?>(lb, d.aging.getOrElse(i) { 0.0 }) }
        )
    }
    return sheets
}
