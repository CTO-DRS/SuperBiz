package com.superbiz.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.RemoveCircleOutline
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.superbiz.app.MainActivity
import com.superbiz.app.R
import com.superbiz.app.data.db.Invoice
import com.superbiz.app.data.db.InvoiceItem
import com.superbiz.app.data.db.Product
import com.superbiz.app.export.DataExport
import com.superbiz.app.export.ExportController
import com.superbiz.app.export.ExportRequest
import com.superbiz.app.pdf.InvoicePdf
import com.superbiz.app.print.EscPos
import com.superbiz.app.print.ReceiptFactory
import com.superbiz.app.print.ReceiptPrintDialog
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
import com.superbiz.app.ui.theme.Green
import com.superbiz.app.ui.components.SubHeader
import com.superbiz.app.ui.components.numberFieldOptions
import com.superbiz.app.ui.components.parseNum
import com.superbiz.app.ui.nav.Routes
import com.superbiz.app.ui.theme.Amber
import com.superbiz.app.ui.theme.Blue
import com.superbiz.app.ui.theme.Cyan
import com.superbiz.app.ui.theme.GreenDeep
import com.superbiz.app.ui.theme.RedDeep
import com.superbiz.app.ui.theme.Vio
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.util.Dates
import com.superbiz.app.util.Money
import com.superbiz.app.vm.AppVM
import com.superbiz.app.vm.InvoicesVM
import com.superbiz.app.VMFactory

@Composable
fun InvoicesScreen(appVM: AppVM, nav: NavHostController) {
    val activity = LocalContext.current as? MainActivity ?: return
    val vm: InvoicesVM = viewModel(factory = remember { VMFactory(activity) })
    val invoices by vm.filtered.collectAsState()
    // [P24-HERO] القائمة الكاملة الحقيقية (بلا ترشيح) لحساب بطاقات الرئيسية الاحترافية
    val allInvoices by vm.invoices.collectAsState()
    val filter by vm.statusFilter.collectAsState()
    // النطاق الزمني + البحث + المنتجات (لتكلفة الربح المحقق)
    val range by vm.rangeFilter.collectAsState()
    val query by vm.searchQuery.collectAsState()
    val productsList by vm.products.collectAsState()
    val symbol by appVM.symbol.collectAsState()
    val editorOpen by vm.editorOpen.collectAsState()
    val g = glassColors()
    // رؤى الموجة R11 للفواتير (متوسط الفاتورة/تكرار/انحراف تقريب)
    // [P5-H9 إصلاح]: VMs الرؤى مشتركة على مستوى النشاط — كانت كل شاشة تنشئ نسختها وتشغل loadAll كاملاً (حتى ×8 تكلفة لكل جولة تنقل)
    val r11VM: com.superbiz.app.vm.R11InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    // VM الرؤى الذكية للموجة R12 — عبر R12Smart
    val r12VM: com.superbiz.app.vm.R12InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    val r13VM: com.superbiz.app.vm.R13InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    // VM الرؤى الذكية للموجة R14 — عبر R14Smart
    val r14VM: com.superbiz.app.vm.R14InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    val r15VM: com.superbiz.app.vm.R15InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp)
    ) {
        val exporter = ExportController(stringResourceCompat(R.string.export_title))
        val partiesList by vm.parties.collectAsState()
        SubHeader(stringResourceCompat(R.string.nav_invoices))

        // ══ [P29-TOOLS] أدوات الشاشة داخل بطاقة موحدة بنمط شاشة الديون () —
        // الإجراءان نفسهما بأيقونتيهما ووظيفتهما حرفاً لكن ظاهران بعناوين واضحة ══
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                QuickAction(
                    Icons.Rounded.Add, stringResourceCompat(R.string.tool_inv_new),
                    Brush.linearGradient(listOf(VioDeep, Vio))
                ) { vm.openEditor(0) }
                QuickAction(
                    Icons.Rounded.GridOn, stringResourceCompat(R.string.tool_inv_export),
                    Brush.linearGradient(listOf(GreenDeep, Green))
                ) {
                    val pmap = partiesList.associateBy { it.id }
                    val header = listOf(
                        activity.getString(R.string.invoice_number),
                        activity.getString(R.string.transaction_type),
                        activity.getString(R.string.party),
                        activity.getString(R.string.date),
                        activity.getString(R.string.due_date),
                        activity.getString(R.string.subtotal),
                        activity.getString(R.string.discount),
                        activity.getString(R.string.tax),
                        activity.getString(R.string.total),
                        activity.getString(R.string.paid_amount),
                        activity.getString(R.string.inst_remaining),
                        activity.getString(R.string.status),
                        activity.getString(R.string.base_currency)
                    )
                    val rows = invoices.map { inv ->
                        listOf<Any?>(
                            inv.number,
                            activity.getString(if (inv.isSale) R.string.type_sale else R.string.type_purchase),
                            pmap[inv.partyId]?.name ?: "",
                            Dates.short(inv.date),
                            Dates.short(inv.dueDate),
                            inv.subtotal, inv.discount, inv.taxAmount, inv.total, inv.paid, inv.open,
                            activity.getString(
                                when (inv.status) {
                                    2 -> R.string.status_paid
                                    1 -> R.string.status_partial
                                    3 -> R.string.status_void
                                    else -> R.string.status_unpaid
                                }
                            ),
                            inv.currency
                        )
                    }
                    exporter(
                        ExportRequest(
                            DataExport.fileName("superbiz-invoices", true), true, header, rows
                        )
                    )
                }
            }
        }

        // بحث داخل الفواتير — رقم الفاتورة أو اسم الطرف بتطبيع عربي (وظيفة P4-1 رقم 9)
        BizField(query, { vm.searchQuery.value = it }, stringResourceCompat(R.string.inv_search_hint))
        Spacer(Modifier.height(8.dp))

        // رقائق الحالة — تشمل «ملغاة» الآن لأن القائمة تُجلب بكل الحالات
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterPill(stringResourceCompat(R.string.all), filter == -1) { vm.statusFilter.value = -1 }
            FilterPill(stringResourceCompat(R.string.status_unpaid), filter == 0) { vm.statusFilter.value = 0 }
            FilterPill(stringResourceCompat(R.string.status_partial), filter == 1) { vm.statusFilter.value = 1 }
            FilterPill(stringResourceCompat(R.string.status_paid), filter == 2) { vm.statusFilter.value = 2 }
            FilterPill(stringResourceCompat(R.string.status_void), filter == 3) { vm.statusFilter.value = 3 }
        }
        Spacer(Modifier.height(6.dp))

        // نطاق زمني سريع + عدّاد النتائج — وظيفة P4-1 رقم 6
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            FilterPill(stringResourceCompat(R.string.range_today), range == 1) { vm.rangeFilter.value = 1 }
            FilterPill(stringResourceCompat(R.string.range_week), range == 2) { vm.rangeFilter.value = 2 }
            FilterPill(stringResourceCompat(R.string.range_month), range == 3) { vm.rangeFilter.value = 3 }
            FilterPill(stringResourceCompat(R.string.all), range == 0) { vm.rangeFilter.value = 0 }
            Spacer(Modifier.width(8.dp))
            Text(
                stringResourceCompat(R.string.inv_results_count, invoices.size),
                fontSize = 12.sp, color = g.textSecondary, fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(10.dp))

        // ══ [P24-HERO] إحصاءات الشهر من القائمة الكاملة الحقيقية (فواتير البيع غير الملغاة فقط) ══
        val monthStart = com.superbiz.app.util.Dates.monthStart()
        val prevStart = com.superbiz.app.util.Dates.monthStart(monthStart - 86_400_000L)
        val nowTs = System.currentTimeMillis()
        val salesAll = allInvoices.filter { it.isSale && it.status != 3 }
        val mSales = salesAll.filter { it.date >= monthStart && it.date < nowTs }
        val mTotal = mSales.sumOf { it.total }
        val mCount = mSales.size
        // [P33-P8]: المجاميع قروش — الحصر والأوسط صحيحان بلا فلواط (الأوسط HALF_UP لقرش)
        val mOutstanding = mSales.filter { it.status == 0 || it.status == 1 }
            .sumOf { (it.total - it.paid).coerceAtLeast(0L) }
        val mCollected = mSales.sumOf { it.paid }
        val mAvg = if (mCount > 0) Math.round(mTotal.toDouble() / mCount) else 0L
        val prevTotal = salesAll.filter { it.date >= prevStart && it.date < monthStart }.sumOf { it.total }

        if (invoices.isEmpty()) {
            EmptyState(stringResourceCompat(R.string.empty_generic), Icons.Rounded.Receipt)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // ══ [P24-HERO] بطاقة الهيرو + شبكة KPI ‏2×2 بنمط الشاشة الرئيسية — أول القائمة ══
                item(key = "inv-hero") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        HeroCard(
                            Icons.Rounded.Receipt,
                            // [P33-P8]: بطاقات KPI بالقروش — العرض عبر formatP
                            Money.formatP(mTotal, symbol),
                            stringResourceCompat(R.string.hero_inv_hint, mCount)
                        )
                        KpiGrid(
                            listOf(
                                KpiCell(
                                    Icons.Rounded.AccountBalanceWallet,
                                    Money.formatP(mOutstanding, symbol),
                                    stringResourceCompat(R.string.inv_kpi_outstanding), Vio
                                ),
                                KpiCell(
                                    Icons.Rounded.Payments,
                                    Money.formatP(mCollected, symbol),
                                    stringResourceCompat(R.string.inv_kpi_collected), GreenDeep
                                ),
                                KpiCell(
                                    Icons.Rounded.Insights,
                                    Money.formatP(mAvg, symbol),
                                    stringResourceCompat(R.string.rep_metric_avg), Amber
                                ),
                                KpiCell(
                                    Icons.Rounded.BarChart,
                                    Money.formatP(prevTotal, symbol),
                                    stringResourceCompat(R.string.inv_kpi_prev), Cyan
                                )
                            )
                        )
                    }
                }
                items(invoices, key = { it.id }) { inv ->
                    InvoiceCard(inv, vm, symbol, appVM, productsList, nav)
                }
                // ══ [P24-HERO] بطاقات الذكاء نُقلت إلى نهاية القائمة — المحتوى أولاً
                // (نفس النمط الموحّد: الرئيسية/الديون/التقارير/الشيكات/المصروفات) ══
                item(key = "inv-insights-hdr") { SectionLabel(stringResourceCompat(R.string.insights_section_title)) }
                // [P44-K1] جولة 5: المكدس المخصص — ترتيب/إظهار المجموعات الخمس بافتضاض المستخدم
                item(key = "insights-stack-invoices") {
                    com.superbiz.app.ui.insights.InsightsStack(
                        com.superbiz.app.domain.DashboardPrefsP44.SCREEN_INVOICES,
                        listOf(
                            com.superbiz.app.ui.insights.insightsGroup("r11") { com.superbiz.app.ui.insights.InvoicesR11Card(appVM, r11VM) },
                            com.superbiz.app.ui.insights.insightsGroup("r12") { com.superbiz.app.ui.insights.InvoicesR12Card(appVM, r12VM) },
                            com.superbiz.app.ui.insights.insightsGroup("r13") { com.superbiz.app.ui.insights.InvoicesR13Card(r13VM) },
                            com.superbiz.app.ui.insights.insightsGroup("r14") { com.superbiz.app.ui.insights.InvoicesR14Card(appVM, r14VM) },
                            com.superbiz.app.ui.insights.insightsGroup("r15") { com.superbiz.app.ui.insights.InvoicesR15Card(appVM, r15VM) },
                        )
                    )
                }
            }
        }
    }

    if (editorOpen) {
        InvoiceEditor(vm, appVM)
    }
}

@Composable
private fun InvoiceCard(
    inv: Invoice,
    vm: InvoicesVM,
    symbol: String,
    appVM: AppVM,
    products: List<Product>,
    nav: NavHostController
) {
    val g = glassColors()
    val cardCtx = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var partyName by remember { mutableStateOf("…") }
    var pendingPdf by remember { mutableStateOf<Invoice?>(null) }
    var pendingPrint by remember { mutableStateOf<Invoice?>(null) }
    // مشاركة النص + بنود التوسيع
    var pendingShare by remember { mutableStateOf<Invoice?>(null) }
    // [P38-Z4] تصدير XML الضريبي — فلو مستقل بنمط pendingPdf نفسه
    var pendingXml by remember { mutableStateOf<Invoice?>(null) }
    var cardItems by remember { mutableStateOf<List<InvoiceItem>?>(null) }
    var printReceipt by remember { mutableStateOf<EscPos.Receipt?>(null) }
    // تأكيد الإجراءات المدمّرة — "paid" تحصيل كامل، "void" إلغاء فاتورة
    var confirmOp by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(inv.id) {
        // [P20-FIX agent1]: «؟» صلبة — استخدم مورد الطرف غير المعروف الموحّد
        partyName = vm.partyOf(inv.partyId)?.name ?: cardCtx.getString(R.string.unknown_party)
    }
    // تحميل بنود الفاتورة عند التوسيع — لعرضها وحساب الربح المحقق
    LaunchedEffect(expanded, inv.id) {
        cardItems = if (expanded) try { vm.itemsOf(inv.id) } catch (e: Exception) { null } else null
    }
    // تصدير PDF عند الطلب (خارج نطاق النقر)
    val activityCtx = LocalContext.current as? MainActivity
    // إصلاح عطل حرج: هذا الزر كان يستدعي InvoicePdf.render + share بلا أي
    // try/catch (عكس زر الطباعة المجاور)، وأي فشل في توليد PDF أو كتابة الملف
    // (امتلاء التخزين، OOM، ملف باسم غير صالح من استيراد قديم) كان يُغلق التطبيق
    // فوراً مع إشعار «تعطل التطبيق». الآن: معالجة كاملة + توليد على IO thread.
    LaunchedEffect(pendingPdf) {
        val target = pendingPdf ?: return@LaunchedEffect
        val activity = activityCtx ?: return@LaunchedEffect
        pendingPdf = null
        try {
            val graph = com.superbiz.app.AppGraph.from(activity.application)
            val items = vm.itemsOf(target.id)
            val party = vm.partyOf(target.partyId)
            val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // [P9-9a-ZATCA] الرقم الضريبي من نفس اللقطة لرسم QR الفاتورة الإلكترونية
                val st = graph.settings.snapshot()
                InvoicePdf.render(
                    activity, target, items, party,
                    st.businessName.ifBlank { activity.getString(R.string.business_default) },
                    symbol, appVM.avatarBitmap(), st.taxNumber
                )
            }
            InvoicePdf.share(activity, file)
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                activity, activity.getString(R.string.pdf_error),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    // تجهيز إيصال حراري عند الطلب (خارج نطاق النقر)
    LaunchedEffect(pendingPrint) {
        val target = pendingPrint ?: return@LaunchedEffect
        val activity = activityCtx ?: return@LaunchedEffect
        pendingPrint = null
        try {
            val graph = com.superbiz.app.AppGraph.from(activity.application)
            val items = vm.itemsOf(target.id)
            val party = vm.partyOf(target.partyId)
            // [P38-Z2] لقطة واحدة تُغذّي الاسم والرقم الضريبي — QR الإيصال الحراري
            // يُبنى من مصدر الحقيقة نفسه الذي يُبنى منه QR فاتورة PDF
            val st = graph.settings.snapshot()
            printReceipt = ReceiptFactory.fromInvoice(
                activity, target, items, party,
                st.businessName, symbol, st.taxNumber
            )
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                activity, activity.getString(R.string.print_fail, e.message ?: ""),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
    printReceipt?.let { rc ->
        ReceiptPrintDialog(receipt = rc, onDismiss = { printReceipt = null })
    }

    // مشاركة ملخص الفاتورة نصاً — وظيفة P4-1 رقم 8
    LaunchedEffect(pendingShare) {
        val target = pendingShare ?: return@LaunchedEffect
        val ctx = activityCtx ?: return@LaunchedEffect
        pendingShare = null
        try {
            val items = vm.itemsOf(target.id)
            val party = vm.partyOf(target.partyId)
            // [P30-C]: نص الفاتورة المُشارَك يتبع لغة التطبيق — كانت عربية دائماً
            val text = com.superbiz.app.domain.InvoiceText.summary(
                target, items, party?.name,
                com.superbiz.app.domain.InvoiceText.Labels(
                    invoice = ctx.getString(R.string.inv_text_invoice),
                    date = ctx.getString(R.string.inv_text_date),
                    party = ctx.getString(R.string.inv_text_party),
                    type = ctx.getString(R.string.inv_text_type),
                    sale = ctx.getString(R.string.inv_text_sale),
                    purchase = ctx.getString(R.string.inv_text_purchase),
                    item = ctx.getString(R.string.inv_text_item),
                    subtotal = ctx.getString(R.string.inv_text_subtotal),
                    discount = ctx.getString(R.string.inv_text_discount),
                    tax = ctx.getString(R.string.inv_text_tax),
                    total = ctx.getString(R.string.inv_text_total),
                    remaining = ctx.getString(R.string.inv_text_remaining),
                    originalAmount = ctx.getString(R.string.cur_orig_amount)
                )
            )
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, text)
            }
            com.superbiz.app.util.startIntentSafe(ctx, android.content.Intent.createChooser(send, null))
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                ctx, ctx.getString(R.string.inv_share_failed),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    // [P38-Z4] تصدير XML الضريبي للفاتورة — بناء على Dispatchers.IO ثم مشاركة ملف
    // عبر FileProvider (نمط InvoicePdf.share نفسه) — فشل التصدير لا يُغلق التطبيق
    LaunchedEffect(pendingXml) {
        val target = pendingXml ?: return@LaunchedEffect
        val ctx = activityCtx ?: return@LaunchedEffect
        pendingXml = null
        try {
            val graph = com.superbiz.app.AppGraph.from(ctx.applicationContext)
            val st = graph.settings.snapshot()
            val xml = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val items = vm.itemsOf(target.id)
                val party = vm.partyOf(target.partyId)
                val doc = com.superbiz.app.domain.algo.ZatcaInvoiceXml.invoiceXml(
                    sellerName = st.businessName.ifBlank { ctx.getString(R.string.business_default) },
                    vatNumber = st.taxNumber,
                    timestampMs = target.date,
                    invoiceNumber = target.number,
                    lines = items.map {
                        com.superbiz.app.domain.algo.ZatcaInvoiceXml.XmlLine(
                            desc = it.desc,
                            qty = it.qty,
                            unitPrice = com.superbiz.app.util.Money.fromPiasters(it.unitPrice),
                            // [P41-L3]: نسبة السطر الفعالة وفئته من السطر نفسه (v11) —
                            // الصفوف التاريخية تعطي نسبة الرأس وS كما كانت
                            vatRate = com.superbiz.app.domain.LineTaxP41.effectiveRate(
                                it.taxKind, it.taxRate, target.taxRate
                            ),
                            lineTotal = com.superbiz.app.util.Money.fromPiasters(it.lineTotal),
                            taxKind = it.taxKind
                        )
                    },
                    subtotal = com.superbiz.app.util.Money.fromPiasters(target.subtotal),
                    discount = com.superbiz.app.util.Money.fromPiasters(target.discount),
                    vatTotal = com.superbiz.app.util.Money.fromPiasters(target.taxAmount),
                    invoiceTotal = com.superbiz.app.util.Money.fromPiasters(target.total),
                    buyerName = party?.name
                )
                // نفس عرف التسمية الآمن في InvoicePdf.render — رقم تالف لا يكسر المسار
                val safeNumber = target.number.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val dir = java.io.File(ctx.filesDir, "xmls")
                dir.mkdirs()
                val f = java.io.File(dir, "invoice-$safeNumber.xml")
                f.writeText(doc, Charsets.UTF_8)
                f
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                ctx, "com.superbiz.app.fileprovider", xml
            )
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/xml"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            com.superbiz.app.util.startIntentSafe(
                ctx, android.content.Intent.createChooser(send, ctx.getString(R.string.share_invoice))
            )
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                ctx, ctx.getString(R.string.inv_share_failed),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    // تأكيد قبل تحصيل كامل أو إلغاء الفاتورة — نفس نمط حوارات الملف
    confirmOp?.let { op ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmOp = null },
            containerColor = g.surfaceStrong,
            title = { Text(stringResourceCompat(R.string.confirm), color = g.textPrimary) },
            text = {
                Text(
                    stringResourceCompat(
                        if (op == "paid") R.string.confirm_mark_paid else R.string.confirm_void_invoice
                    ),
                    color = g.textPrimary
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    if (op == "paid") vm.markPaid(inv) else vm.voidInvoice(inv)
                    confirmOp = null
                }) {
                    Text(stringResourceCompat(R.string.confirm_yes), color = g.accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmOp = null }) {
                    Text(stringResourceCompat(R.string.confirm_no), color = g.textSecondary)
                }
            }
        )
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconChip(
                    Icons.Rounded.Receipt, Color.White,
                    if (inv.isSale) Blue else Vio, size = 38.dp, iconSize = 17.dp
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        inv.number, fontWeight = FontWeight.Bold,
                        color = g.textPrimary
                    )
                    Text(
                        partyName + " • " + Dates.short(inv.date),
                        fontSize = 12.sp, color = g.textSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        // [P33-P8]: إجمالي الفاتورة قروش
                        Money.formatP(inv.total, symbol),
                        fontWeight = FontWeight.Bold, color = g.textPrimary
                    )
                    Badge(
                        when (inv.status) {
                            2 -> stringResourceCompat(R.string.status_paid)
                            1 -> stringResourceCompat(R.string.status_partial)
                            3 -> stringResourceCompat(R.string.status_void)
                            else -> stringResourceCompat(R.string.status_unpaid)
                        },
                        when (inv.status) {
                            2 -> GreenDeep; 1 -> Amber; 3 -> RedDeep; else -> RedDeep
                        }
                    )
                }
            }
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                // بنود الفاتورة داخل التوسيع + الربح المحقق (وظيفتا P4-1 رقم 9 و10)
                val loaded = cardItems
                if (loaded != null && loaded.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (it in loaded) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    it.desc.ifBlank { stringResourceCompat(R.string.item) },
                                    fontSize = 12.sp, color = g.textPrimary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    // [P33-P8]: سعر الوحدة و lineTotal قروش (numP) والكمية تبقى Double (num)
                                    Money.num(it.qty) + " × " + Money.numP(it.unitPrice),
                                    fontSize = 11.sp, color = g.textSecondary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    Money.numP(it.lineTotal),
                                    fontSize = 12.sp, color = g.textPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        // الربح المحقق من تكلفة المنتجات الحالية — يُخفى صادقاً إذا لا تكلفة لأي بند
                        val pmap = remember(products) { products.associateBy { it.id } }
                        val profit = remember(loaded, pmap, inv.discount) {
                            com.superbiz.app.domain.PosCart.invoiceProfit(loaded, inv.discount) { pid ->
                                pmap[pid]?.costPrice
                            }
                        }
                        profit?.let { pf ->
                            Spacer(Modifier.height(2.dp))
                            Text(
                                // [P33-P8]: الربح المحقق قروش — costOf يعيد Long؟ الآن (تكلفة المنتج قروش)
                                stringResourceCompat(R.string.inv_profit, Money.formatP(pf, symbol)),
                                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                color = if (pf >= 0) GreenDeep else RedDeep
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                } else if (loaded != null) {
                    Text(
                        stringResourceCompat(R.string.inv_items_empty),
                        fontSize = 11.sp, color = g.textSecondary
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    // [P33-P8]: المفتوح قروش — مساواة صحيحة بدل عتبة الفلواط
                    if (inv.open > 0L && inv.status != 3) {
                        ActionPill(stringResourceCompat(R.string.mark_paid), GreenDeep) {
                            confirmOp = "paid"
                        }
                    }
                    // إعادة طلب فاتورة بيع — يفتح نقطة البيع بسلة معدّة (وظيفة P4-1 رقم 5)
                    if (inv.isSale && inv.status != 3) {
                        ActionPill(stringResourceCompat(R.string.inv_reorder), Cyan) {
                            val ctx = activityCtx
                            vm.prepareReorder(inv) { ok ->
                                if (ok) {
                                    nav.navigate(com.superbiz.app.ui.nav.Routes.POS) { launchSingleTop = true }
                                } else ctx?.let { c ->
                                    android.widget.Toast.makeText(
                                        c, c.getString(R.string.inv_reorder_failed),
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }
                    // تكرار الفاتورة كمسودة غير مسددة بتاريخ اليوم (وظيفة P4-1 رقم 7)
                    if (inv.status != 3) {
                        ActionPill(stringResourceCompat(R.string.inv_duplicate), Blue) {
                            val ctx = activityCtx
                            vm.duplicateAsDraft(inv) { number ->
                                ctx?.let { c ->
                                    android.widget.Toast.makeText(
                                        c,
                                        c.getString(
                                            if (number != null) R.string.inv_duplicated else R.string.inv_dup_failed,
                                            number ?: ""
                                        ),
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }
                    ActionPill(stringResourceCompat(R.string.inv_share_text), GreenDeep) {
                        pendingShare = inv
                    }
                    ActionPill(stringResourceCompat(R.string.export_pdf), Vio) {
                        pendingPdf = inv
                    }
                    // [P38-Z4] مشاركة XML الضريبي — رقاقة جديدة بجانب PDF والطباعة
                    ActionPill(stringResourceCompat(R.string.inv_share_xml), Amber) {
                        pendingXml = inv
                    }
                    ActionPill(stringResourceCompat(R.string.print_receipt), Blue) {
                        pendingPrint = inv
                    }
                    if (inv.status != 3) {
                        ActionPill(stringResourceCompat(R.string.status_void), RedDeep) {
                            confirmOp = "void"
                        }
                    }
                }
            }
        }
    }
}

/** محرر الفاتورة — لوحة كاملة */
@Composable
fun InvoiceEditor(vm: InvoicesVM, appVM: AppVM) {
    val g = glassColors()
    val parties by vm.parties.collectAsState()
    val products by vm.products.collectAsState()
    val items by vm.editorItems.collectAsState()
    val taxRate by vm.editorTaxRate.collectAsState()
    val type by vm.editorType.collectAsState()
    val symbol by appVM.symbol.collectAsState()
    // كانت المعاينة تحسب كل الأسطر بينما الحفظ يُسقط الأسطر بلا وصف أو بكمية ≤0 —
    // الإجمالي المعروض كان أكبر من المحفوظ. نفس مرشّح الحفظ الآن في المعاينة
    // [P41-L1]: المعاينة نفسها تدخل مسار السطر الصريح (v11) كي تطابق الحفظ دائماً
    // [P42-R3]: علم الوعي بالسطر يُحفظ من الحساب نفسه كي يعرض الصف عنوان الضريبة
    // الصادق (حسب السطور) بدل نسبة الرأس المضللة حين تختلف نسب السطر عنها
    val validPreview = items.filter { it.desc.isNotBlank() && parseNum(it.qty) > 0.0 }
    val lineAwareTotals = vm.totalsLineAware(validPreview, taxRate)
    val (sub, tax, total) = lineAwareTotals ?: vm.totals(validPreview, taxRate)
    val lineAware = lineAwareTotals != null

    var partyPicker by remember { mutableStateOf(false) }
    // [P10] استيراد الأطراف من جهات الاتصال من داخل منتقي محرر الفواتير
    var showContacts by remember { mutableStateOf(false) }
    var productPicker by remember { mutableStateOf(false) }
    var pickerFor by remember { mutableStateOf(0) }
    // [P7-L11 إصلاح]: رسالة تحقق داخل المحرر بدل الفشل الصامت — كان saveInvoice يعود
    // بصمت عند عدم اختيار الطرف أو خلو البنود من أي سطر صالح (return@launchSafe بلا رسالة)
    var saveErrRes by remember { mutableStateOf<Int?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
    ) {
        GlassCard(
            corner = 28.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .imePadding()
                // [P20-FIX agent1]: ارتفاع ثابت 640dp كان يدفع العنوان ومحدد الطرف خارج الشاشة
                // على الأجهزة الأقصر/الأفقية ومع لوحة المفاتيح — نسبة من الشاشة مع سقف
                .heightIn(max = 640.dp)
                .fillMaxHeight(0.92f)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (type == 0) stringResourceCompat(R.string.invoice_new) else stringResourceCompat(R.string.type_purchase),
                        style = MaterialTheme.typography.titleLarge, color = g.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        // [P20-FIX agent1]: الرقاقة تعرض النوع الهدف (الموضع الحالي في العنوان) —
                        // كانت تطبع النوع الحالي فتظهر «شراء | شراء» والنقر يحوّل بلا دلالة نصية
                        stringResourceCompat(if (type == 0) R.string.type_purchase else R.string.type_sale),
                        color = g.accent2, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { vm.editorType.value = if (type == 0) 1 else 0 }
                            .padding(6.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))

                // اختيار الطرف
                val editorParty by vm.editorParty.collectAsState()
                Text(
                    editorParty?.name
                        ?: stringResourceCompat(R.string.party) + " ▾",
                    color = g.textPrimary, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(g.textSecondary.copy(alpha = 0.08f))
                        .clickable { partyPicker = true }
                        .padding(12.dp)
                )
                Spacer(Modifier.height(10.dp))

                // [H4-1 V 2.5.0] منتقي عملة الفاتورة — الأسعار تُدخل بهذه العملة،
                // والحفظ يحوّل قروش أساس عبر R17 ويختَم الفئة الأصلية بسعرها التاريخي
                val currencies by vm.currencies.collectAsState()
                val editorCur by vm.editorCurrency.collectAsState()
                if (currencies.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        currencies.forEach { c ->
                            val selected = c.code == editorCur
                            Text(
                                c.code + " " + c.symbol,
                                color = if (selected) Color.White else g.textPrimary,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) g.accent else g.textSecondary.copy(alpha = 0.08f))
                                    .clickable { vm.editorCurrency.value = c.code }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                // الأصناف
                Text(stringResourceCompat(R.string.invoice_items), style = MaterialTheme.typography.titleSmall, color = g.textSecondary)
                Spacer(Modifier.height(6.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items.forEachIndexed { i, item ->
                        GlassCard(corner = 14.dp) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        item.desc.ifBlank { stringResourceCompat(R.string.item) + " ${i + 1}" },
                                        color = g.textPrimary, fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { productPicker = true; pickerFor = i },
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    if (items.size > 1) {
                                        IconButton(onClick = {
                                            vm.editorItems.value = items.filterIndexed { idx, _ -> idx != i }
                                        }) {
                                            Icon(Icons.Rounded.RemoveCircleOutline, stringResourceCompat(R.string.a11y_remove_invoice_line), tint = RedDeep)
                                        }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    BizField(
                                        item.qty, { v ->
                                            vm.editorItems.value = items.mapIndexed { idx, e ->
                                                if (idx == i) e.copy(qty = v) else e
                                            }
                                        }, stringResourceCompat(R.string.qty),
                                        modifier = Modifier.weight(1f), keyboard = numberFieldOptions()
                                    )
                                    BizField(
                                        item.price, { v ->
                                            vm.editorItems.value = items.mapIndexed { idx, e ->
                                                if (idx == i) e.copy(price = v) else e
                                            }
                                        }, stringResourceCompat(R.string.price),
                                        modifier = Modifier.weight(1f), keyboard = numberFieldOptions()
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    BizField(
                                        item.discount, { v ->
                                            vm.editorItems.value = items.mapIndexed { idx, e ->
                                                if (idx == i) e.copy(discount = v) else e
                                            }
                                        }, stringResourceCompat(R.string.discount),
                                        modifier = Modifier.weight(1f), keyboard = numberFieldOptions()
                                    )
                                    Box(
                                        Modifier.weight(1f),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        // صافي البند لا ينزل تحت الصفر — الخصم محصور في 0..قيمة البند مثل نقطة البيع
                                        // [P33-P8]: معاينة البند بالقروش — السعر/الخصم نصان رياليان → parseToPiasters، الكمية تبقى Double
                                        val itemSub = Math.round(parseNum(item.qty) * Money.parseToPiasters(item.price)).coerceAtLeast(0L)
                                        val itemNet = itemSub - Money.parseToPiasters(item.discount).coerceIn(0L, itemSub)
                                        Text(
                                            Money.formatP(itemNet, symbol),
                                            color = g.accent2, fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                // [P41-L1] v11 — منتقي فئة ضريبة السطر (قياسية/صفرية/معفاة):
                                // القياسية وراثة نسبة الرأس (السلوك التاريخي)، والصفرية/المعفاة
                                // ضريبة صفر بخانتين إقرار مستقلتين (LineTaxP41/ZatcaReturnP38)
                                // [P42-R3] جولة 3 — النسبة الصريحة للسطر: حقل يظهر للفئة القياسية
                                // فقط (الصفرية/المعفاة ضريبتهما صفر بعقد effectiveRate فلا مجال
                                // لنسبة عليهما): فارغ = وراثة نسبة الفاتورة كما منذ، ومملوء
                                // = نسبة معلنة 0..100 تفتح مسار السطر عبر isExplicit — بمحرك واحد
                                // للحفظ والتقرير والXML فلا انحراف بين المحجوز والمعلن أبداً
                                if (item.taxKind == com.superbiz.app.domain.LineTaxP41.KIND_STANDARD) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        BizField(
                                            item.taxRateText,
                                            { v -> vm.setLineTaxRate(i, v) },
                                            stringResourceCompat(R.string.line_rate_label),
                                            modifier = Modifier.weight(1f),
                                            keyboard = numberFieldOptions()
                                        )
                                        Text(
                                            stringResourceCompat(R.string.line_rate_hint),
                                            color = g.textSecondary, fontSize = 11.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        stringResourceCompat(R.string.line_tax_label),
                                        color = g.textSecondary, fontSize = 12.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    listOf(
                                        com.superbiz.app.domain.LineTaxP41.KIND_STANDARD to R.string.line_tax_standard,
                                        com.superbiz.app.domain.LineTaxP41.KIND_ZERO to R.string.line_tax_zero,
                                        com.superbiz.app.domain.LineTaxP41.KIND_EXEMPT to R.string.line_tax_exempt,
                                    ).forEach { (kind, labelRes) ->
                                        val selected = item.taxKind == kind
                                        Text(
                                            stringResourceCompat(labelRes),
                                            color = if (selected) Color.White else g.textSecondary,
                                            fontSize = 12.sp,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (selected) g.accent else g.textSecondary.copy(alpha = 0.10f))
                                                .clickable {
                                                    vm.editorItems.value = items.mapIndexed { idx, e ->
                                                        if (idx == i) e.copy(taxKind = kind) else e
                                                    }
                                                }
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(g.accent.copy(alpha = 0.12f))
                            .clickable {
                                vm.editorItems.value = items + InvoicesVM.EditorItem(null, "", "", "")
                            }
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text("+ " + stringResourceCompat(R.string.add_item), color = g.accent, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(8.dp))
                // الإجماليات
                // [H4-1] العملة الفعالة للمعاينة: الأجنبية تعرض فئتها + سطر «المعادل بالأساس»
                val foreignCur = currencies.firstOrNull { it.code == editorCur && !it.isBase }
                val displaySymbol = foreignCur?.symbol ?: symbol
                val baseEquivalent = foreignCur?.let { fc ->
                    vm.rateMicrosFor(fc.code)?.let { m ->
                        com.superbiz.app.domain.algo.FxStampMath.toBasePiasters(total, 2, m)
                    }
                }
                Row {
                    Text(stringResourceCompat(R.string.subtotal), color = g.textSecondary, modifier = Modifier.weight(1f))
                    Text(Money.formatP(sub, displaySymbol), color = g.textPrimary)  // [P33-P8] قروش
                }
                Row {
                    // [P42-R3] الصدق الإقراري: عند الوعي بالسطر قد تختلف نسبة كل سطر عن نسبة
                    // الرأس — «حسب السطور» بدل عرض نسبة الرأس المضللة (المسار التاريخي كما هو)
                    Text(
                        if (lineAware) stringResourceCompat(R.string.tax_by_line)
                        else stringResourceCompat(R.string.tax) + " (${Money.num(taxRate)}%)",
                        color = g.textSecondary, modifier = Modifier.weight(1f)
                    )
                    Text(Money.formatP(tax, displaySymbol), color = g.textPrimary)  // [P33-P8] قروش
                }
                Row {
                    Text(stringResourceCompat(R.string.total), fontWeight = FontWeight.Bold, color = g.textPrimary, modifier = Modifier.weight(1f))
                    Text(Money.formatP(total, displaySymbol), fontWeight = FontWeight.ExtraBold, color = g.accent, fontSize = 17.sp)  // [P33-P8] قروش
                }
                if (baseEquivalent != null) {
                    // المعادل التقريبي بقروش الأساس — نفس دالة الحفظ (R17) فلا مفاجأة عند التخزين
                    Row {
                        Text(stringResourceCompat(R.string.cur_base_equivalent), color = g.textSecondary, modifier = Modifier.weight(1f))
                        Text(Money.formatP(baseEquivalent, symbol), color = g.textSecondary)
                    }
                }
                Spacer(Modifier.height(10.dp))
                // [P7-L11 إصلاح] عرض خطأ الإدخال بدل الفشل الصامت (نمط M6-47/M6-48)
                saveErrRes?.let { res ->
                    Text(
                        stringResourceCompat(res),
                        color = RedDeep, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(g.textSecondary.copy(alpha = 0.12f))
                            .clickable { vm.closeEditor() }
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(stringResourceCompat(R.string.cancel), color = g.textSecondary, fontWeight = FontWeight.Bold) }
                    Box(
                        Modifier
                            .weight(2f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(g.heroBrush)
                            .clickable {
                                // [P7-L11 إصلاح]: تحقق قبل الحفظ — الطرف مطلوب وبند صالح واحد على الأقل
                                // (نفس شرطي return@launchSafe الصامتَين في InvoicesVM.saveInvoice)
                                val e = when {
                                    editorParty == null -> R.string.err_party_required
                                    items.none { it.desc.isNotBlank() && parseNum(it.qty) > 0.0 } -> R.string.err_items_required
                                    else -> null
                                }
                                saveErrRes = e
                                if (e == null) vm.saveInvoice()
                            }
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(stringResourceCompat(R.string.save), color = Color.White, fontWeight = FontWeight.Bold) }
                }
            }
        }

        // اختيار الطرف
        if (partyPicker) {
            PickerDialog(title = stringResourceCompat(R.string.party), onDismiss = { partyPicker = false }) {
                Column(Modifier.fillMaxWidth()) {
                    // [P10] استيراد من جهات الاتصال — يغلق المنتقي مؤقتاً ويفتح لوحة الاستيراد
                    ActionPill(stringResourceCompat(R.string.contacts_pick_import), Vio) {
                        partyPicker = false
                        showContacts = true
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn {
                        items(parties, key = { it.id }) { p ->
                            Text(
                                p.name + if (p.isCustomer && p.isSupplier) " (↕)" else "",
                                color = g.textPrimary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.editorParty.value = p; partyPicker = false }
                                    .padding(vertical = 10.dp, horizontal = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        // [P10] لوحة استيراد جهات الاتصال — تدفق Room التفاعلي يحدّث الأطراف تلقائياً،
        // وعند الإغلاق يعاد فتح المنتقي لاختيار الطرف المستورد فوراً
        if (showContacts) {
            ContactsSheet(
                onImported = { },
                onDismiss = { showContacts = false; partyPicker = true }
            )
        }

        // اختيار منتج لصنف معين
        if (productPicker) {
            PickerDialog(title = stringResourceCompat(R.string.product), onDismiss = { productPicker = false }) {
                LazyColumn {
                    items(products, key = { it.id }) { p ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val i = pickerFor
                                    val cur = vm.editorItems.value.getOrNull(i) ?: return@clickable
                                    vm.editorItems.value = vm.editorItems.value.mapIndexed { idx, e ->
                                        if (idx == i) e.copy(
                                            productId = p.id, desc = p.name,
                                            // [P33-P8]: الأسعار قروش — نص الحقل ريال (numP) لا toString للقروش مباشرة
                                            price = if (vm.editorType.value == 0) Money.numP(p.salePrice) else Money.numP(p.costPrice)
                                        ) else e
                                    }
                                    productPicker = false
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp)
                        ) {
                            Text(p.name, color = g.textPrimary, fontWeight = FontWeight.SemiBold)
                            Text(
                                // [P33-P8]: سعر البيع قروش (numP) والكمية تبقى Double (num)
                                stringResourceCompat(R.string.sale_price) + ": " + Money.numP(p.salePrice) +
                                    " • " + stringResourceCompat(R.string.stock_qty) + ": " + Money.num(p.stockQty),
                                fontSize = 11.sp, color = g.textSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

/** نافذة اختيار عامة */
@Composable
fun PickerDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val g = glassColors()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = g.surfaceStrong,
        title = { Text(title, color = g.textPrimary) },
        text = { Box(Modifier.height(320.dp)) { content() } },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResourceCompat(R.string.close), color = g.accent)
            }
        }
    )
}
