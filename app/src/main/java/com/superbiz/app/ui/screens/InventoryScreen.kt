package com.superbiz.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
// [P7-L12 إصلاح]: أُزيل استيرادا Archive وRemove الميتان — أيقونة Delete أُحييت بزر الحذف في [P7-L8]
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Warning
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.superbiz.app.MainActivity
import com.superbiz.app.R
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.superbiz.app.data.db.Product
import com.superbiz.app.domain.PriceLabel
import com.superbiz.app.domain.ShoppingList
import com.superbiz.app.export.ExportController
import com.superbiz.app.export.ExportRequest
import com.superbiz.app.ui.components.SectionLabel
import com.superbiz.app.ui.components.Badge
import com.superbiz.app.ui.components.BizField
import com.superbiz.app.ui.components.EmptyState
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.components.HeroCard
import com.superbiz.app.ui.components.IconChip
import com.superbiz.app.ui.components.KpiCell
import com.superbiz.app.ui.components.KpiGrid
import com.superbiz.app.ui.components.QuickAction
import androidx.compose.ui.graphics.Brush
import com.superbiz.app.ui.theme.VioDeep
import com.superbiz.app.ui.theme.Vio
import com.superbiz.app.ui.theme.Green
import com.superbiz.app.ui.theme.Blue
import com.superbiz.app.ui.components.SubHeader
import com.superbiz.app.ui.components.numberFieldOptions
import com.superbiz.app.ui.components.parseNum
import com.superbiz.app.ui.theme.Amber
import com.superbiz.app.ui.theme.Cyan
import com.superbiz.app.ui.theme.GreenDeep
import com.superbiz.app.ui.theme.RedDeep
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.util.BarcodeGen
import com.superbiz.app.util.Money
import com.superbiz.app.util.startIntentSafe
import kotlinx.coroutines.launch
import com.superbiz.app.vm.AppVM
import com.superbiz.app.vm.InventoryVM
import com.superbiz.app.VMFactory

/** إعدادات قارئ الباركود المشترك */
private fun scanOptions(context: android.content.Context) = ScanOptions().apply {
    setDesiredBarcodeFormats(ScanOptions.ALL_CODE_TYPES)
    setPrompt(context.getString(R.string.scan_prompt))
    setBeepEnabled(true)
    setOrientationLocked(true)
}

@Composable
fun InventoryScreen(appVM: AppVM, nav: NavHostController) {
    val activity = LocalContext.current as? MainActivity ?: return
    val vm: InventoryVM = viewModel(factory = remember { VMFactory(activity) })
    // الرؤى الذكية — خطة إعادة الطلب + الراكد + شارات النفاد
    // [P5-H9 إصلاح]: VMs الرؤى مشتركة على مستوى النشاط — كانت كل شاشة تنشئ نسختها وتشغل loadAll كاملاً (حتى ×8 تكلفة لكل جولة تنقل)
    val smartVM: com.superbiz.app.vm.SmartInsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    // مخزون الأمان + الطلب المتقطع + تقادم الدفعات
    val r9VM: com.superbiz.app.vm.R9InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    // رؤى الموجة R10 للمخزون
    val r10VM: com.superbiz.app.vm.R10InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    // رؤى الموجة R11 للمخزون (إعادة الطلب/مردود الفئات)
    val r11VM: com.superbiz.app.vm.R11InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    // VM الرؤى الذكية للموجة R12 — عبر R12Smart
    val r12VM: com.superbiz.app.vm.R12InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    val r13VM: com.superbiz.app.vm.R13InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    // VM الرؤى الذكية للموجة R14 — عبر R14Smart
    val r14VM: com.superbiz.app.vm.R14InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    val r15VM: com.superbiz.app.vm.R15InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    val products by vm.filtered.collectAsState()
    val search by vm.search.collectAsState()
    val lowOnly by vm.showLowOnly.collectAsState()
    // [P5-H10 إصلاح]: رقاقة «المؤرشفة» — عرض المنتجات المؤرشفة واسترجاعها
    val showArchived by vm.showArchived.collectAsState()
    val symbol by appVM.symbol.collectAsState()
    val g = glassColors()
    // القائمة الكاملة (لتقييم المخزون) + التحليل الذكي (لفلتر الراكد)
    val allProducts by vm.products.collectAsState()
    val insights by vm.insights.collectAsState()

    var editor by remember { mutableStateOf<Product?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var barcodeView by remember { mutableStateOf<Product?>(null) }
    var showInsights by remember { mutableStateOf(false) }
    // فلتر "راكد" — تصفية محلية حقيقية على insight كل منتج
    var showDeadOnly by remember { mutableStateOf(false) }
    // وظيفة 15 — قائمة التسوّج (قابلة للطي) + وظيفة 20 — لم تُبع قط
    var shopOpen by remember { mutableStateOf(false) }
    var neverSoldOpen by remember { mutableStateOf(false) }
    // وظيفة 12 — ملصق السعر الحراري من بطاقة المنتج
    var labelFor by remember { mutableStateOf<Product?>(null) }
    // وظيفة 15 — نص المشاركة المعلّق (يُنفَّذ عبر LaunchedEffect الآمن)
    var pendingShopShare by remember { mutableStateOf(false) }
    // [P7-L8 إصلاح]: حوار تأكيد الحذف — كان vm.deleteProduct بلا أي واجهة في القائمة
    var confirmDelete by remember { mutableStateOf<Product?>(null) }

    val context = LocalContext.current

    // عند تفعيل الفلتر والتحليل غير محمّل بعد — حمّله مرة واحدة (الشريحة تعمل حتى قبل اكتمال التحميل)
    LaunchedEffect(showDeadOnly) {
        if (showDeadOnly && insights.isEmpty()) vm.loadInsights()
    }

    // وظيفة 15 — توسيع بطاقة التسوّج يحمل التحليل (نفس بيانات نقطة إعادة الطلب)
    LaunchedEffect(shopOpen) {
        if (shopOpen && insights.isEmpty()) vm.loadInsights()
    }
    // وظيفة 20 — توسيع بطاقة «لم تُبع قط» يحمل الاستعلام الحقيقي
    LaunchedEffect(neverSoldOpen) {
        if (neverSoldOpen) vm.loadNeverSold()
    }

    // وظيفة 15 — مشاركة نص قائمة التسوّج عبر startIntentSafe (نمط مشاركة الفواتير)
    // نصوص المشاركة من الموارد — توطين حقيقي بدل العربية المضمّنة
    val shopLabels = com.superbiz.app.domain.ShoppingList.Labels(
        title = stringResourceCompat(com.superbiz.app.R.string.share_shop_title),
        available = stringResourceCompat(com.superbiz.app.R.string.share_shop_available),
        suggested = stringResourceCompat(com.superbiz.app.R.string.share_shop_suggested),
        count = stringResourceCompat(com.superbiz.app.R.string.share_shop_count),
        note = stringResourceCompat(com.superbiz.app.R.string.share_shop_note),
    )
    LaunchedEffect(pendingShopShare) {
        if (!pendingShopShare) return@LaunchedEffect
        pendingShopShare = false
        val rows = insights.filter { it.reorderQty > 0 }
        if (rows.isEmpty()) return@LaunchedEffect
        val text = ShoppingList.build(rows.map {
            ShoppingList.Row(it.product.name, it.product.unit, it.product.stockQty, it.reorderQty)
        }, shopLabels)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, text)
        }
        startIntentSafe(context, android.content.Intent.createChooser(send, null))
    }

    val insightById = remember(insights) { insights.associateBy { it.product.id } }
    // الراكد فقط: يظهر ما تطابق insight فيه dead=true — وقبل وصول التحليل لا يُصفّي شيئاً
    val visibleProducts = if (showDeadOnly && insights.isNotEmpty())
        products.filter { insightById[it.id]?.dead == true } else products

    // وظيفة 13 — متحكم التصدير الآمن (حوار الصيغة ثم نافذة حفظ SAF بلا أذونات تخزين)
    val exporter = ExportController(stringResourceCompat(R.string.export_title))

    // مسح باركود للبحث عن منتج: يفتح المحرر إن وُجد، وإلا يعرض تنبيهاً
    val searchScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val code = result.contents ?: return@rememberLauncherForActivityResult
        vm.findByBarcode(code) { found ->
            if (found != null) {
                editor = found
                showEditor = true
            } else {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.scan_not_found),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp)
    ) {
        SubHeader(stringResourceCompat(R.string.nav_inventory))

        // ══ [P29-TOOLS] أدوات المخزون داخل بطاقة موحدة بنمط شاشة الديون () —
        // الأربعة نفسها بوظائفها الحرفية (تحليل ذكي/مسح باركود/تصدير/منتج جديد) ══
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                QuickAction(
                    Icons.Rounded.QueryStats, stringResourceCompat(R.string.tool_stock_insights),
                    Brush.linearGradient(listOf(Amber, RedDeep))
                ) { vm.loadInsights(); showInsights = true }
                QuickAction(
                    Icons.Rounded.QrCodeScanner, stringResourceCompat(R.string.tool_stock_scan),
                    Brush.linearGradient(listOf(Cyan, Blue))
                ) { searchScanner.launch(scanOptions(context)) }
                QuickAction(
                    Icons.Rounded.GridOn, stringResourceCompat(R.string.tool_stock_export),
                    Brush.linearGradient(listOf(GreenDeep, Green))
                ) {
                    // وظيفة 13 — تصدير المخزون المرشَّح كما هو عبر مسار FileProvider الآمن
                    val header = listOf(
                        activity.getString(R.string.name),
                        activity.getString(R.string.sku),
                        activity.getString(R.string.barcode),
                        activity.getString(R.string.unit),
                        activity.getString(R.string.cost_price),
                        activity.getString(R.string.sale_price),
                        activity.getString(R.string.stock_qty),
                        activity.getString(R.string.reorder_level),
                        activity.getString(R.string.inv_stock_value)
                    )
                    val rows = visibleProducts.map { p ->
                        listOf<Any?>(
                            p.name, p.sku, p.barcode, p.unit,
                            // [P33-P8] الأسعار قروش Long — الخلايا الرقمية للتصدير قيمة عددية ريال (fromPiasters)
                            Money.fromPiasters(p.costPrice), Money.fromPiasters(p.salePrice),
                            p.stockQty, p.reorderLevel,
                            // [P33-P8] قيمة المخزون قرشاً: (كمية × سعر قرش) مقرّبة لكل صنف ثم عرضها ريالاً
                            Money.fromPiasters(Math.round(p.stockQty * p.costPrice))
                        )
                    }
                    exporter(
                        ExportRequest(
                            com.superbiz.app.export.DataExport.fileName("superbiz-inventory", true),
                            true, header, rows
                        )
                    )
                }
                QuickAction(
                    Icons.Rounded.Add, stringResourceCompat(R.string.tool_stock_new),
                    Brush.linearGradient(listOf(VioDeep, Vio))
                ) { editor = null; showEditor = true }
            }
        }

        BizField(search, { vm.search.value = it }, stringResourceCompat(R.string.search))
        Spacer(Modifier.height(8.dp))
        // ثلاثي حصري — الكل / الناقص / الراكد
        // [P5-H10 إصلاح]: رقاقة رابعة «المؤرشفة» — كان المؤرشف غير مرئي في أي واجهة
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterPill(stringResourceCompat(R.string.all), !lowOnly && !showDeadOnly && !showArchived) {
                vm.showLowOnly.value = false; showDeadOnly = false; vm.showArchived.value = false
            }
            FilterPill(stringResourceCompat(R.string.low_stock), lowOnly && !showDeadOnly && !showArchived) {
                vm.showLowOnly.value = true; showDeadOnly = false; vm.showArchived.value = false
            }
            FilterPill(stringResourceCompat(R.string.inv_dead_chip), showDeadOnly && !showArchived) {
                showDeadOnly = true; vm.showLowOnly.value = false; vm.showArchived.value = false
            }
            FilterPill(stringResourceCompat(R.string.inv_archived_chip), showArchived) {
                vm.showArchived.value = true; showDeadOnly = false; vm.showLowOnly.value = false
            }
        }
        Spacer(Modifier.height(10.dp))

        // تقييم المخزون — يُحسب من القائمة الكاملة لا المُصفّاة
        // [P24-HERO]: بطاقة التقييم النصية القديمة أُزيلت — بياناتها صارت بطاقة الهيرو + شبكة KPI
        // الاحترافية بنمط الرئيسية (أول عنصر في القائمة أدناه)
        // [P33-P8] قيمة المخزون قروش Long: (كمية Double × سعر قرش) تُقرَّب لكل صنف ثم تُجمع صحيحة
        val valCost = allProducts.sumOf { Math.round(it.stockQty * it.costPrice) }
        val valRetail = allProducts.sumOf { Math.round(it.stockQty * it.salePrice) }
        // الهامش نسبة مئوية — يبقى حساباً عشرياً (قسمة Long تقتطع)
        val valMarginPct = if (valRetail > 0) (((valRetail - valCost).toDouble() / valRetail) * 100.0).toInt() else 0
        val stockActiveCount = allProducts.count { !it.archived }
        val stockLowCount = allProducts.count { !it.archived && it.isLow }

        // ══ : وظيفة 15 — قائمة التسوّج الشرائية (تحت نقطة إعادة الطلب) ══
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable { shopOpen = !shopOpen },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.ShoppingCart, null, tint = Amber,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResourceCompat(R.string.inv_shop_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = g.textPrimary, modifier = Modifier.weight(1f)
                    )
                    Icon(
                        if (shopOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        null, tint = g.textSecondary
                    )
                }
                if (shopOpen) {
                    Spacer(Modifier.height(8.dp))
                    val insightsLoading by vm.insightsLoading.collectAsState()
                    val shopRows = insights.filter { it.reorderQty > 0 }
                    when {
                        insightsLoading -> Text(
                            "…", fontSize = 13.sp, color = g.textSecondary
                        )
                        shopRows.isEmpty() -> Text(
                            stringResourceCompat(R.string.inv_shop_empty),
                            fontSize = 13.sp, color = g.textSecondary
                        )
                        else -> {
                            shopRows.forEach { it0 ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            it0.product.name,
                                            fontSize = 13.sp, color = g.textPrimary,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            stringResourceCompat(R.string.inv_shop_stock, Money.num(it0.product.stockQty), it0.product.unit),
                                            fontSize = 11.sp, color = g.textSecondary
                                        )
                                    }
                                    Text(
                                        stringResourceCompat(
                                            R.string.ins_reorder_hint,
                                            Money.num(it0.reorderQty), it0.product.unit
                                        ),
                                        fontSize = 12.sp, color = Amber, fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResourceCompat(R.string.inv_shop_count, shopRows.size),
                                    fontSize = 12.sp, color = g.textSecondary
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    stringResourceCompat(R.string.inv_shop_share),
                                    fontSize = 12.sp, color = g.accent2, fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { pendingShopShare = true }
                                        .padding(6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // ══ : وظيفة 20 — منتجات بلا بيع إطلاقاً (مختلفة عن «الراكد» 30 يوماً) ══
        val neverSoldList by vm.neverSold.collectAsState()
        val neverSoldLoading by vm.neverSoldLoading.collectAsState()
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable { neverSoldOpen = !neverSoldOpen },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Inventory2, null, tint = g.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResourceCompat(R.string.inv_never_sold, neverSoldList.size),
                        style = MaterialTheme.typography.titleSmall,
                        color = g.textPrimary, modifier = Modifier.weight(1f)
                    )
                    Icon(
                        if (neverSoldOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        null, tint = g.textSecondary
                    )
                }
                Text(
                    stringResourceCompat(R.string.inv_never_sold_hint),
                    fontSize = 11.sp, color = g.textSecondary
                )
                if (neverSoldOpen) {
                    Spacer(Modifier.height(8.dp))
                    when {
                        neverSoldLoading -> Text(
                            "…", fontSize = 13.sp, color = g.textSecondary
                        )
                        neverSoldList.isEmpty() -> Text(
                            stringResourceCompat(R.string.inv_never_sold_empty),
                            fontSize = 13.sp, color = g.textSecondary
                        )
                        else -> neverSoldList.forEach { p ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    p.name, fontSize = 13.sp, color = g.textPrimary,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    Money.num(p.stockQty) + " " + p.unit,
                                    fontSize = 12.sp, color = g.textSecondary
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        if (visibleProducts.isEmpty()) {
            EmptyState(stringResourceCompat(R.string.empty_generic), Icons.Rounded.Inventory2)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // ══ [P24-HERO] بطاقة الهيرو + شبكة KPI ‏2×2 بنمط الشاشة الرئيسية — أول القائمة
                // (بيانات تقييم المخزون الحقيقية نفسها التي كانت في البطاقة النصية القديمة) ══
                item(key = "stock-hero") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        HeroCard(
                            Icons.Rounded.Inventory2,
                            // [P33-P8] قيمة المخزون بالتكلفة قروش Long
                            Money.formatP(valCost, symbol),
                            stringResourceCompat(R.string.hero_stock_hint, stockActiveCount)
                        )
                        KpiGrid(
                            listOf(
                                KpiCell(
                                    Icons.Rounded.Storefront,
                                    // [P33-P8] قيمة المخزون بالتجزئة قروش Long
                                    Money.formatP(valRetail, symbol),
                                    stringResourceCompat(R.string.stock_kpi_retail), GreenDeep
                                ),
                                KpiCell(
                                    Icons.Rounded.TrendingUp,
                                    // [P33-P8] فرق القيمتين قروش Long
                                    Money.formatP(valRetail - valCost, symbol) + " ($valMarginPct%)",
                                    stringResourceCompat(R.string.stock_kpi_margin), Amber
                                ),
                                KpiCell(
                                    Icons.Rounded.Warning,
                                    stockLowCount.toString(),
                                    stringResourceCompat(R.string.stock_kpi_low), RedDeep
                                ),
                                KpiCell(
                                    Icons.Rounded.Sell,
                                    stockActiveCount.toString(),
                                    stringResourceCompat(R.string.stock_kpi_products), Cyan
                                )
                            )
                        )
                    }
                }
                items(visibleProducts, key = { it.id }) { p ->
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { editor = p; showEditor = true }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconChip(
                                Icons.Rounded.Inventory2, Color.White,
                                if (p.isLow) RedDeep else GreenDeep, size = 40.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    p.name, style = MaterialTheme.typography.titleSmall,
                                    color = g.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    // [P33-P8] سعرا المنتج قروش Long
                                    stringResourceCompat(R.string.sale_price) + ": " + Money.formatP(p.salePrice, symbol) +
                                        " • " + stringResourceCompat(R.string.cost_price) + ": " + Money.numP(p.costPrice),
                                    fontSize = 11.sp, color = g.textSecondary
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    Money.num(p.stockQty) + " " + p.unit,
                                    fontWeight = FontWeight.Bold,
                                    color = if (p.isLow) RedDeep else g.textPrimary
                                )
                                Row {
                                    if (p.barcode.isNotBlank()) {
                                        IconButton(onClick = { barcodeView = p }, modifier = Modifier.size(30.dp)) {
                                            Icon(Icons.Rounded.QrCode2, stringResourceCompat(R.string.a11y_show_barcode), tint = g.accent2, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                    // [P7-L8 إصلاح]: زر حذف بصف المنتج — تمرير الحذف عبر vm.deleteProduct
                                    // القائم وحده (بلا أي سلوك حذف جديد) مع تأكيد بنمط الموجة أدناه
                                    IconButton(
                                        onClick = { confirmDelete = p },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(Icons.Rounded.Delete, stringResourceCompat(R.string.a11y_delete_product), tint = RedDeep, modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                // ══ [P24-HERO] بطاقات الذكاء نُقلت إلى نهاية القائمة — المحتوى أولاً
                // (نفس النمط الموحّد: الرئيسية/الديون/التقارير/الشيكات/المصروفات/الفواتير) ══
                // خطة إعادة الطلب والمخزون الراكد
                item(key = "stock-insights-hdr") { SectionLabel(stringResourceCompat(R.string.insights_section_title)) }
                // [P44-K1] جولة 5: المكدس المخصص — ترتيب/إظهار المجموعات الثماني بافتضاض المستخدم
                item(key = "insights-stack-inventory") {
                    com.superbiz.app.ui.insights.InsightsStack(
                        com.superbiz.app.domain.DashboardPrefsP44.SCREEN_INVENTORY,
                        listOf(
                            // خطة إعادة الطلب والمخزون الراكد
                            com.superbiz.app.ui.insights.insightsGroup("smart") { com.superbiz.app.ui.insights.InventorySmartCard(smartVM) },
                            // مخزون الأمان/الطلب المتقطع/تقادم الدفعات
                            com.superbiz.app.ui.insights.insightsGroup("r9") { com.superbiz.app.ui.insights.InventoryR9Card(r9VM, appVM) },
                            // ABC/كفاءة رأس المال/سلّم التخفيض/أسماء متشابهة
                            com.superbiz.app.ui.insights.insightsGroup("r10") { com.superbiz.app.ui.insights.InventoryR10Card(r10VM) },
                            // خطة إعادة الطلب/مردود الفئات GMROI
                            com.superbiz.app.ui.insights.insightsGroup("r11") { com.superbiz.app.ui.insights.InventoryR11Card(r11VM) },
                            com.superbiz.app.ui.insights.insightsGroup("r12") { com.superbiz.app.ui.insights.InventoryR12Card(r12VM) },
                            com.superbiz.app.ui.insights.insightsGroup("r13") { com.superbiz.app.ui.insights.InventoryR13Card(appVM, r13VM) },
                            com.superbiz.app.ui.insights.insightsGroup("r14") { com.superbiz.app.ui.insights.InventoryR14Card(appVM, r14VM) },
                            com.superbiz.app.ui.insights.insightsGroup("r15") { com.superbiz.app.ui.insights.InventoryR15Card(appVM, r15VM) },
                        )
                    )
                }
            }
        }
    }

    // محرر المنتج
    if (showEditor) {
        ProductEditor(vm, editor) { showEditor = false }
    }

    // [P7-L8 إصلاح]: حوار تأكيد حذف المنتج بنمط الموجة (confirm/confirm_yes/confirm_no)
    // الحذف يمر عبر vm.deleteProduct القائم (أرشفة عبر المستودع) — أي فشل (قيود/حركات)
    // يُعرض عبر ErrorCenter كما يعيده launchSafe، بلا أي سلوك حذف جديد هنا
    confirmDelete?.let { p ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = g.surfaceStrong,
            title = { Text(stringResourceCompat(R.string.confirm), color = g.textPrimary) },
            text = {
                Text(
                    stringResourceCompat(R.string.inv_delete_confirm, p.name),
                    color = g.textPrimary
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmDelete = null
                    vm.deleteProduct(p.id)
                }) {
                    Text(stringResourceCompat(R.string.confirm_yes), color = RedDeep, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDelete = null }) {
                    Text(stringResourceCompat(R.string.confirm_no), color = g.textSecondary)
                }
            }
        )
    }

    // لوحة التحليل الذكي — من مبيعات 30 يوماً الحقيقية
    if (showInsights) {
        InsightsSheet(vm, onOpenProduct = { p ->
            showInsights = false
            editor = p
            showEditor = true
        }) { showInsights = false }
    }

    // عرض الباركود
    barcodeView?.let { p ->
        PickerDialog(title = p.name + " • " + p.barcode, onDismiss = { barcodeView = null }) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val bmp = remember(p.id) { BarcodeGen.ean13(p.barcode) ?: BarcodeGen.code128(p.barcode) }
                if (bmp != null) {
                    Image(bitmap = bmp.asImageBitmap(), contentDescription = p.barcode)
                }
                Text(p.barcode, color = g.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                // وظيفة 12 — ملصق سعر 58مم من بطاقة المنتج (اسم + سعر + باركود CODE128)
                Text(
                    stringResourceCompat(R.string.inv_label_open),
                    color = g.accent, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            barcodeView = null
                            labelFor = p
                        }
                        .padding(8.dp)
                )
            }
        }
    }

    // وظيفة 12 — حوار ملصق السعر: معاينة نصية + طباعة عبر BluetoothPrinter
    labelFor?.let { p ->
        PriceLabelPrintDialog(product = p) { labelFor = null }
    }
}

// لوحة التحليل الذكي — نفس أسلوب الزجاج في بقية اللوحات
@Composable
private fun InsightsSheet(
    vm: InventoryVM,
    onOpenProduct: (Product) -> Unit,
    onDismiss: () -> Unit
) {
    val g = glassColors()
    val list by vm.insights.collectAsState()
    val loading by vm.insightsLoading.collectAsState()

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable { onDismiss() }
    ) {
        GlassCard(
            corner = 28.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(560.dp)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .clickable(enabled = false) { }
                    .padding(18.dp)
            ) {
                Text(stringResourceCompat(R.string.inv_insights), style = MaterialTheme.typography.titleLarge, color = g.textPrimary)
                Text(
                    stringResourceCompat(R.string.ins_abc_note),
                    fontSize = 12.sp, color = g.textSecondary
                )
                Spacer(Modifier.height(10.dp))
                if (loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.CircularProgressIndicator(color = g.accent)
                    }
                } else if (list.isEmpty()) {
                    EmptyState(stringResourceCompat(R.string.empty_generic), Icons.Rounded.Inventory2)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(list, key = { i, _ -> "ins$i" }) { _, it ->
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpenProduct(it.product) }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // شارة ABC بلون دلالي
                                    Box(
                                        Modifier
                                            .size(34.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(
                                                when (it.abc) {
                                                    'A' -> GreenDeep
                                                    'B' -> Amber
                                                    else -> g.textSecondary.copy(alpha = 0.35f)
                                                }
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "${it.abc}", color = Color.White,
                                            fontWeight = FontWeight.Bold, fontSize = 16.sp
                                        )
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            it.product.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = g.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            stringResourceCompat(R.string.ins_sold30, Money.num(it.sold30), it.product.unit) +
                                                " • " + stringResourceCompat(R.string.ins_cover, "${it.coverDays}"),
                                            fontSize = 11.sp, color = g.textSecondary
                                        )
                                        if (it.reorderQty > 0) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    stringResourceCompat(
                                                        R.string.ins_reorder_hint,
                                                        Money.num(it.reorderQty), it.product.unit
                                                    ),
                                                    fontSize = 12.sp, color = Amber, fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                // وظيفة 14 — تطبيق الاقتراح بضغطة: حركة شراء ذرّية حقيقية
                                                val applyCtx = LocalContext.current
                                                Text(
                                                    "＋ " + stringResourceCompat(R.string.inv_suggest_apply),
                                                    fontSize = 12.sp, color = g.accent, fontWeight = FontWeight.Bold,
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .clickable {
                                                            vm.applyReorderSuggestion(it) { ok, qty ->
                                                                android.widget.Toast.makeText(
                                                                    applyCtx,
                                                                    applyCtx.getString(
                                                                        if (ok) R.string.inv_suggest_applied
                                                                        else R.string.inv_suggest_failed,
                                                                        Money.num(qty), it.product.unit
                                                                    ),
                                                                    android.widget.Toast.LENGTH_SHORT
                                                                ).show()
                                                            }
                                                        }
                                                        .padding(6.dp)
                                                )
                                            }
                                        }
                                        // الطلب الاقتصادي ومخزون الأمان — FinMath على مبيعات حقيقية
                                        if (it.eoq > 0) {
                                            Text(
                                                stringResourceCompat(R.string.inv_eoq, Money.num(it.eoq)),
                                                fontSize = 12.sp, color = g.textSecondary
                                            )
                                        }
                                        if (it.safetyStock > 0) {
                                            Text(
                                                stringResourceCompat(R.string.inv_safety, Money.num(it.safetyStock)),
                                                fontSize = 12.sp, color = g.textSecondary
                                            )
                                        }
                                        // شارة "راكد" مع تلميح توضيحي
                                        if (it.dead) {
                                            Spacer(Modifier.height(4.dp))
                                            Badge(stringResourceCompat(R.string.inv_dead_chip), RedDeep)
                                            Text(
                                                stringResourceCompat(R.string.inv_dead_hint),
                                                fontSize = 10.sp, color = g.textSecondary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductEditor(vm: InventoryVM, existing: Product?, onDismiss: () -> Unit) {
    val g = glassColors()
    // الهامش المستهدف من الإعدادات — يُبرَز في صف الاقتراحات
    val targetMargin by vm.targetMargin.collectAsState()
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var sku by remember { mutableStateOf(existing?.sku ?: "") }
    var barcode by remember { mutableStateOf(existing?.barcode ?: "") }
    // [P30-F]: الوحدة الافتراضية من الموارد — كانت «قطعة» عربية ثابتة تكتب في البيانات
    // حتى بلغة التطبيق الإنجليزية (القراءة في نطاق Composable ثم الالتقاط في remember)
    val defaultUnit = stringResourceCompat(R.string.unit_piece)
    var unit by remember { mutableStateOf(existing?.unit ?: defaultUnit) }
    // [P33-P8] التعبئة المسبقة قروش → نص ريال عبر numP (الكميات تبقى Double عبر num)
    var cost by remember { mutableStateOf(if (existing != null) Money.numP(existing.costPrice) else "") }
    var price by remember { mutableStateOf(if (existing != null) Money.numP(existing.salePrice) else "") }
    var stock by remember { mutableStateOf(if (existing != null) Money.num(existing.stockQty) else "") }
    var reorder by remember { mutableStateOf(if (existing != null) Money.num(existing.reorderLevel) else "5") }
    var category by remember { mutableStateOf(existing?.category ?: "") }
    // [P5-H10 إصلاح]: حالة الأرشفة صارت قابلة للتعديل من المحرر — كانت بلا أي واجهة
    var archived by remember { mutableStateOf(existing?.archived ?: false) }
    val context = LocalContext.current
    // [P7-L11 إصلاح]: رسالة تحقق داخل المحرر بدل الفشل الصامت (نمط M6-47/M6-48)
    var errRes by remember { mutableStateOf<Int?>(null) }
    // مسح باركود لتعبئة الحقل مباشرة
    val editorScan = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { barcode = it }
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = g.surfaceStrong,
        title = {
            Text(
                if (existing == null) stringResourceCompat(R.string.qa_product) + " — " + stringResourceCompat(R.string.add)
                else stringResourceCompat(R.string.edit),
                color = g.textPrimary
            )
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BizField(name, { name = it }, stringResourceCompat(R.string.name))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BizField(sku, { sku = it }, stringResourceCompat(R.string.sku), modifier = Modifier.weight(1f))
                    BizField(unit, { unit = it }, stringResourceCompat(R.string.unit), modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BizField(barcode, { barcode = it }, stringResourceCompat(R.string.barcode), modifier = Modifier.weight(1f))
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        // [P30-E]: بلا إيموجي — نص موطَّن نظيف
                        stringResourceCompat(R.string.scan_fill),
                        color = g.accent2, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { editorScan.launch(scanOptions(context)) }
                            .padding(6.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        // [P30-E]: بلا إيموجي — نص موطَّن نظيف
                        stringResourceCompat(R.string.gen_barcode),
                        color = g.accent2, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { vm.launchGen { barcode = it } }
                            .padding(6.dp)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BizField(cost, { cost = it }, stringResourceCompat(R.string.cost_price), modifier = Modifier.weight(1f), keyboard = numberFieldOptions())
                    BizField(price, { price = it }, stringResourceCompat(R.string.sale_price), modifier = Modifier.weight(1f), keyboard = numberFieldOptions())
                }
                // مستشار الهامش الحي — marginPct/priceForMargin من MoneyMath
                val costV = parseNum(cost)
                val priceV = parseNum(price)
                if (costV > 0 && priceV > 0) {
                    val m = com.superbiz.app.domain.algo.marginPct(costV, priceV)
                    Text(
                        stringResourceCompat(R.string.margin_live, Money.num(m)),
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        color = when {
                            m < 10 -> RedDeep
                            m < 25 -> Amber
                            else -> GreenDeep
                        }
                    )
                }
                if (costV > 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResourceCompat(R.string.margin_suggest),
                            fontSize = 12.sp, color = g.textSecondary
                        )
                        listOf(20.0, 30.0, 40.0, 50.0).forEach { t ->
                            FilterPill("${t.toInt()}%", targetMargin == t) {
                                vm.setDefaultTargetMargin(t)
                                price = Money.num(com.superbiz.app.domain.algo.priceForMargin(costV, t))
                            }

                        // اقتراح السعر النفسي Charm (ينتهي بـ0.95) من FinMath — عميل حقيقي للخوارزمية
                        }
                        // اقتراح السعر النفسي Charm (ينتهي بـ0.95) من FinMath — عميل حقيقي للخوارزمية
                        if (priceV > 0) {
                            val charmP = com.superbiz.app.domain.algo.FinMath.charmPrice(priceV)
                            if (charmP > priceV) {
                                Text(
                                    stringResourceCompat(R.string.inv_charm, Money.num(charmP)),
                                    fontSize = 12.sp,
                                    color = g.accent2
                                )
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (existing == null) {
                        BizField(stock, { stock = it }, stringResourceCompat(R.string.stock_qty), modifier = Modifier.weight(1f), keyboard = numberFieldOptions())
                    } else {
                        Text(
                            stringResourceCompat(R.string.stock_qty) + ": " + Money.num(existing.stockQty),
                            color = g.textSecondary, modifier = Modifier.weight(1f)
                        )
                    }
                    BizField(reorder, { reorder = it }, stringResourceCompat(R.string.reorder_level), modifier = Modifier.weight(1f), keyboard = numberFieldOptions())
                }
                BizField(category, { category = it }, stringResourceCompat(R.string.category))
                // [P5-H10 إصلاح]: صف أرشفة المنتج — التفعيل يخفيه من البيع والتحليلات
                // والودجات، ويمكن استرجاعه من رقاقة «المؤرشفة» في شاشة المخزون
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { archived = !archived }
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = archived,
                        onCheckedChange = { archived = it }
                    )
                    Text(
                        stringResourceCompat(R.string.inv_archived_label),
                        fontSize = 13.sp, color = g.textSecondary
                    )
                }
                // [P7-L11 إصلاح] عرض خطأ الإدخال بدل الفشل الصامت
                errRes?.let { res ->
                    Text(
                        stringResourceCompat(res),
                        color = RedDeep, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                // [P7-L11 إصلاح]: تحقق كامل قبل الحفظ برسالة داخل المحرر — كان:
                // (1) الاسم الفارغ يتجاهل الضغطة بلا أي تغذية راجعة
                // (2) القيم السالبة تُرفض في saveProduct عبر require بلا رسالة للمستخدم
                //    (launchSafe يسجّلها فقط) بينما كان الحوار يُغلق كأن الحفظ نجح
                val stockV = if (existing == null) parseNum(stock) else existing.stockQty
                val e = when {
                    name.isBlank() -> R.string.err_product_name_blank
                    parseNum(cost) < 0.0 || parseNum(price) < 0.0 ||
                        parseNum(reorder) < 0.0 || stockV < 0.0 -> R.string.err_values_non_negative
                    else -> null
                }
                errRes = e
                if (e == null) {
                    vm.saveProduct(
                        existing?.id ?: 0, name, sku, barcode, unit,
                        parseNum(cost), parseNum(price),
                        existing?.stockQty ?: parseNum(stock),
                        parseNum(reorder), category,
                        archived = archived
                    )
                    onDismiss()
                }
            }) { Text(stringResourceCompat(R.string.save), color = g.accent, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResourceCompat(R.string.cancel), color = g.textSecondary)
            }
        }
    )
}

// ═══════════════════════════════════════════════════════════════
// وظيفة 12 — حوار ملصق السعر الحراري 58مم
// معاينة نصية صادقة (من البنّاء النقي نفسه الذي يُرسل للطابعة)
// ثم اختيار الطابعة المقترنة والطباعة عبر مسار BluetoothPrinter القائم.
// ═══════════════════════════════════════════════════════════════
@Composable
private fun PriceLabelPrintDialog(product: Product, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val g = glassColors()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var printers by remember { mutableStateOf(listOf<android.bluetooth.BluetoothDevice>()) }
    var selected by remember { mutableStateOf<android.bluetooth.BluetoothDevice?>(null) }
    var printing by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    val permLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            msg = ctx.getString(R.string.bluetooth_need_permission)
        } else {
            val list = com.superbiz.app.print.BluetoothPrinter.bondedPrinters(ctx)
            printers = list
            val saved = com.superbiz.app.print.BluetoothPrinter.lastPrinterAddress(ctx)
            selected = list.firstOrNull { it.address == saved } ?: list.firstOrNull()
        }
    }

    LaunchedEffect(Unit) {
        if (com.superbiz.app.print.BluetoothPrinter.hasConnectPermission(ctx)) {
            val list = com.superbiz.app.print.BluetoothPrinter.bondedPrinters(ctx)
            printers = list
            val saved = com.superbiz.app.print.BluetoothPrinter.lastPrinterAddress(ctx)
            selected = list.firstOrNull { it.address == saved } ?: list.firstOrNull()
        } else {
            permLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    fun doPrint() {
        val dev = selected ?: return
        printing = true
        msg = ctx.getString(R.string.printing)
        scope.launch {
            try {
                com.superbiz.app.print.BluetoothPrinter.rememberPrinter(ctx, dev.address)
                // عرض 58مم ثابت = 32 حرفاً (مواصفة الملصق) — نفس البنّاء النقي المستخدم في المعاينة
                com.superbiz.app.print.BluetoothPrinter.print(
                    ctx, dev.address,
                    // [P33-P8] سعر الملصق قروش Long
                    PriceLabel.buildBytes(product.name, Money.numP(product.salePrice), product.barcode)
                )
                msg = ctx.getString(R.string.inv_label_done)
            } catch (e: Exception) {
                msg = ctx.getString(R.string.inv_label_fail, e.message ?: e.javaClass.simpleName)
            } finally {
                printing = false
            }
        }
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!printing) onDismiss() },
        containerColor = g.surfaceStrong,
        title = { Text(stringResourceCompat(R.string.inv_label_title), color = g.textPrimary) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    stringResourceCompat(R.string.inv_label_preview),
                    fontSize = 12.sp, color = g.textSecondary
                )
                // المعاينة من البنّاء النقي — نفس العرض المُرسل (58مم = 32 حرفاً)
                Text(
                    // [P33-P8] سعر الملصق قروش Long
                    PriceLabel.textPreview(product.name, Money.numP(product.salePrice), product.barcode),
                    fontSize = 13.sp, color = g.textPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.05f))
                        .padding(10.dp)
                )
                if (printers.isEmpty()) {
                    Text(
                        stringResourceCompat(R.string.printer_none),
                        color = g.textSecondary, fontSize = 13.sp
                    )
                } else {
                    Text(
                        stringResourceCompat(R.string.printer_select),
                        fontSize = 12.sp, color = g.textSecondary
                    )
                    printers.forEach { dev ->
                        Text(
                            (dev.name ?: dev.address),
                            fontSize = 14.sp,
                            color = if (selected?.address == dev.address) g.accent else g.textPrimary,
                            fontWeight = if (selected?.address == dev.address) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selected = dev }
                                .padding(vertical = 6.dp, horizontal = 4.dp)
                        )
                    }
                }
                if (msg.isNotBlank()) Text(msg, fontSize = 12.sp, color = g.textSecondary)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = selected != null && !printing,
                onClick = { doPrint() }
            ) {
                Text(
                    stringResourceCompat(R.string.inv_label_print),
                    color = if (selected != null && !printing) g.accent else g.textSecondary,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResourceCompat(R.string.close), color = g.textSecondary)
            }
        }
    )
}
