package com.superbiz.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAddAlt
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.RequestQuote
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.superbiz.app.MainActivity
import com.superbiz.app.R
import com.superbiz.app.data.db.StatementRow
import com.superbiz.app.export.DataExport
import com.superbiz.app.export.ExportController
import com.superbiz.app.export.ExportRequest
import com.superbiz.app.ui.components.Badge
import com.superbiz.app.ui.components.BizField
import com.superbiz.app.ui.components.BizPill
import com.superbiz.app.ui.components.EmptyState
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.components.IconChip
import com.superbiz.app.ui.components.PillMode
import com.superbiz.app.ui.components.QuickAction
import com.superbiz.app.ui.components.SectionLabel
import com.superbiz.app.ui.components.SubHeader
import com.superbiz.app.ui.components.numberFieldOptions
import com.superbiz.app.ui.components.parseNum
import com.superbiz.app.ui.nav.Routes
import com.superbiz.app.ui.theme.Amber
import com.superbiz.app.ui.theme.Blue
import com.superbiz.app.ui.theme.Cyan
import com.superbiz.app.ui.theme.Green
import com.superbiz.app.ui.theme.GreenDeep
import com.superbiz.app.ui.theme.Red
import com.superbiz.app.ui.theme.RedDeep
import com.superbiz.app.ui.theme.Vio
import com.superbiz.app.ui.theme.VioDeep
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.util.Dates
import com.superbiz.app.util.Money
import com.superbiz.app.util.startIntentSafe
import com.superbiz.app.vm.DebtsVM
import com.superbiz.app.vm.AppVM
import com.superbiz.app.VMFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun DebtsScreen(appVM: AppVM, nav: NavHostController) {
    val activity = LocalContext.current as? MainActivity ?: return
    val vm: DebtsVM = viewModel(factory = remember { VMFactory(activity) })
    // أولوية التحصيل الذكية
    // [P5-H9 إصلاح]: VMs الرؤى مشتركة على مستوى النشاط — كانت كل شاشة تنشئ نسختها وتشغل loadAll كاملاً (حتى ×8 تكلفة لكل جولة تنقل)
    val smartVM: com.superbiz.app.vm.SmartInsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    // المسح الذكي + الحدود الائتمانية + الإجراء التالي
    val r9VM: com.superbiz.app.vm.R9InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    // رؤى الموجة R10 للذمم
    val r10VM: com.superbiz.app.vm.R10InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    // رؤى الموجة R11 للذمم (تأخر الأقساط/شرائح RFM)
    val r11VM: com.superbiz.app.vm.R11InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    // VM الرؤى الذكية للموجة R12 — عبر R12Smart
    val r12VM: com.superbiz.app.vm.R12InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    val r13VM: com.superbiz.app.vm.R13InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    // VM الرؤى الذكية للموجة R14 — عبر R14Smart
    val r14VM: com.superbiz.app.vm.R14InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    val r15VM: com.superbiz.app.vm.R15InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    val rows by vm.rows.collectAsState()
    val parties by vm.parties.collectAsState()
    val filter by vm.filter.collectAsState()
    // صحة التحصيل — تُحسب في DebtsVM.loadHealth() وتتحدث مع كل refresh
    val health by vm.health.collectAsState()
    // وضع الفرز (وظيفة 23) + أعلى 5 مدينين (وظيفة 30)
    val sortMode by vm.sortMode.collectAsState()
    val topDebtors by vm.topDebtors.collectAsState()
    val symbol by appVM.symbol.collectAsState()
    val settings by appVM.settings.collectAsState()
    val g = glassColors()

    var showAddParty by remember { mutableStateOf(false) }
    var showContacts by remember { mutableStateOf(false) }
    var showDuplicates by remember { mutableStateOf(false) }
    // [P40-M5] حالات الحوار صارت MutableState صريحة كي تُمرّر بالمرجع إلى
    // PartyEditorDialog المستخرج — نفس الكائنات بالضبط (لا نسخ ولا تغيير سلوكي)
    var editPartyId by remember { mutableStateOf(0L) }
    val editName = remember { mutableStateOf("") }
    val editPhone = remember { mutableStateOf("") }
    val editType = remember { mutableStateOf(0) }
    val editNote = remember { mutableStateOf("") }
    // [P17-c] أعمدة الطرف الثمانية الجديدة (ترحيل 8→9) — تُحفظ عبر مسار التحديث القائم أدناه
    val editEmail = remember { mutableStateOf("") }
    val editAddress = remember { mutableStateOf("") }
    val editTaxNumber = remember { mutableStateOf("") }
    val editCity = remember { mutableStateOf("") }
    val editCountry = remember { mutableStateOf("") }
    val editWebsite = remember { mutableStateOf("") }
    val editCrNumber = remember { mutableStateOf("") }
    val editAccountNumber = remember { mutableStateOf("") }
    val editScope = rememberCoroutineScope()

    var opDialog by remember { mutableStateOf<Triple<com.superbiz.app.data.db.Party, String, Int>?>(null) } // (طرف، عملية، اتجاه)

    val exporter = ExportController(stringResourceCompat(R.string.export_title))
        // تقرير الذمم — A4 عربي كامل متعدد الصفحات (كل الأطراف + الأعمار)
        var pendingA4 by remember { mutableStateOf(false) }
        // التقرير الجاهز — طباعة A4 نظامية / حرارية بلوتوث / مشاركة
        var readyA4 by remember { mutableStateOf<com.superbiz.app.print.ReportReady?>(null) }
        LaunchedEffect(pendingA4) {
            if (!pendingA4) return@LaunchedEffect
            pendingA4 = false
            try {
                // الاستعلامات وبناء PDF على Dispatchers.IO — الواجهة الرئيسية لا تتجمّد، وتعيين الحالة بعدها
                val ready = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val graph = com.superbiz.app.AppGraph.from(activity.application)
                    val (ar, ap) = graph.ledger.arAp()
                    val a4Rows = rows.map { r ->
                        com.superbiz.app.pdf.A4Report.DebtorRow(
                            r.party.name, r.party.phone,
                            when {
                                r.party.isCustomer && r.party.isSupplier -> activity.getString(R.string.party_both)
                                r.party.isCustomer -> activity.getString(R.string.customers)
                                else -> activity.getString(R.string.suppliers)
                            },
                            r.balance
                        )
                    }
                    val debtors = rows.count { it.balance > 0.004 }
                    val overdue = graph.reports.overdueTotal()
                    val business = graph.settings.snapshot().businessName
                        .ifBlank { activity.getString(R.string.business_default) }
                    val file = com.superbiz.app.pdf.A4Report.receivables(
                        activity,
                        business,
                        symbol, appVM.avatarBitmap(),
                        rows = a4Rows, totalAr = ar, totalAp = ap,
                        debtors = debtors,
                        overdue = overdue,
                        aging = graph.reports.agingBuckets()
                    )
                    com.superbiz.app.print.ReportReady(
                        file, activity.getString(R.string.a4_receivables_title),
                        com.superbiz.app.print.ReportReceiptFactory.receivables(
                            activity, business, symbol, ar, ap, debtors, overdue
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
    // [P21-FIX] شاشة الديون كانت مكسورة بصرياً: ترويسة + 3 بطاقات + صفّا مرشحات فوق
    // LazyColumn بارتفاع الشاشة داخل Column غير قابل للتمرير — فتُضغط قائمة الأطراف إلى
    // شريط ضئيل (أو صفر) على الأجهزة الحقيقية وتختفي الديون أصلاً. الآن: كل الشاشة
    // LazyColumn واحدة وكل بطاقة عنصر مستقل يمرر مع القائمة — لا اختفاء ولا ضغط مهما بلغ طول المحتوى.
    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "debts-hdr") {
        SubHeader(stringResourceCompat(R.string.nav_debts))
        }

        // ══ [P26-TOOLS] أدوات الشاشة السبع داخل بطاقة احترافية واحدة بنمط الشاشة الرئيسية —
        // بطلب المستخدم: «اجعل الأدوات الموجودة في الأعلى داخل بطاقة». الأدوات نفسها السبع
        // (إضافة طرف، جهات الاتصال، المكررات، الشيكات، الأقساط، تقرير الذمم A4، تصدير المطالبات)
        // بوظائفها الكاملة بلا أي تغيير، لكن داخل GlassCard بصفّين ودوائر متدرجة بعناوين
        // كما QuickAction في الرئيسية تماماً. العنصر تابع للتمرير داخل LazyColumn الواحدة —
        // لا ضغط ولا اختفاء (درس P20/P23 محفوظ).
        item(key = "debts-tools") {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        QuickAction(
                            Icons.Rounded.Add, stringResourceCompat(R.string.tool_add_party),
                            Brush.linearGradient(listOf(VioDeep, Vio))
                        ) {
                            editPartyId = 0
                            editName.value = ""; editPhone.value = ""; editType.value = 0; editNote.value = ""
                            // [P17-c] تصفير أعمدة الكشف الثمانية مع بداية كل إضافة
                            editEmail.value = ""; editAddress.value = ""; editTaxNumber.value = ""; editCity.value = ""
                            editCountry.value = ""; editWebsite.value = ""; editCrNumber.value = ""; editAccountNumber.value = ""
                            showAddParty = true
                        }
                        QuickAction(
                            Icons.Rounded.PersonAddAlt, stringResourceCompat(R.string.tool_contacts),
                            Brush.linearGradient(listOf(Cyan, Blue))
                        ) { showContacts = true }
                        QuickAction(
                            Icons.Rounded.PersonSearch, stringResourceCompat(R.string.tool_duplicates),
                            Brush.linearGradient(listOf(Amber, Color(0xFFF97316)))
                        ) { vm.findDuplicates(); showDuplicates = true }
                        QuickAction(
                            Icons.Rounded.Badge, stringResourceCompat(R.string.checks_title),
                            Brush.linearGradient(listOf(GreenDeep, Green))
                        ) {
                            nav.navigate(com.superbiz.app.ui.nav.Routes.CHECKS) { launchSingleTop = true }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        QuickAction(
                            Icons.Rounded.CalendarMonth, stringResourceCompat(R.string.installments_title),
                            Brush.linearGradient(listOf(Blue, Vio))
                        ) {
                            nav.navigate(com.superbiz.app.ui.nav.Routes.INSTALLMENTS) { launchSingleTop = true }
                        }
                        QuickAction(
                            Icons.Rounded.RequestQuote, stringResourceCompat(R.string.a4_receivables_title),
                            Brush.linearGradient(listOf(Vio, VioDeep))
                        ) {
                            // تقرير الذمم A4 كامل
                            pendingA4 = true
                        }
                        QuickAction(
                            Icons.Rounded.GridOn, stringResourceCompat(R.string.tool_export),
                            Brush.linearGradient(listOf(Green, Cyan))
                        ) {
                            // تصدير المطالبات (الأطراف والأرصدة والخطر) Excel/CSV — الوظيفة كما كانت حرفياً
                            // [H5-4 V 3.2.0] الرؤوس من عقد التصدير الموحد
                            val header = com.superbiz.app.export.ExportSchemas.claimsHeaders(activity)
                            val data = rows.map { r ->
                                listOf<Any?>(
                                    r.party.name,
                                    r.party.phone,
                                    when {
                                        r.party.isCustomer && r.party.isSupplier -> activity.getString(R.string.party_both)
                                        r.party.isCustomer -> activity.getString(R.string.customers)
                                        else -> activity.getString(R.string.suppliers)
                                    },
                                    r.balance,
                                    r.risk?.score ?: "",
                                    r.risk?.let {
                                        activity.getString(
                                            when (it.level) {
                                                0 -> R.string.risk_low
                                                1 -> R.string.risk_medium
                                                else -> R.string.risk_high
                                            }
                                        )
                                    } ?: ""
                                )
                            }
                            exporter(
                                ExportRequest(
                                    DataExport.fileName("superbiz-claims", true), true, header, data
                                )
                            )
                        }
                    }
                }
            }
        }

        // مرشحات
        item(key = "debts-filters") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterPill(stringResourceCompat(R.string.all), filter == 0) { vm.filter.value = 0 }
            FilterPill(stringResourceCompat(R.string.customers), filter == 1) { vm.filter.value = 1 }
            FilterPill(stringResourceCompat(R.string.suppliers), filter == 2) { vm.filter.value = 2 }
        }
        }

        // رقائق الفرز (وظيفة 23) — الفرز في VM عبر DebtSort النقي؛
        // بلا رقاقة مختارة يبقى سلوك القائمة القائم (|الرصيد| تنازلياً). النقر مرة أخرى يعيد الافتراضي
        item(key = "debts-sort") {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            FilterPill(
                stringResourceCompat(R.string.debt_sort_oldest),
                sortMode == com.superbiz.app.domain.DebtSort.MODE_OLDEST
            ) {
                vm.sortMode.value =
                    if (sortMode == com.superbiz.app.domain.DebtSort.MODE_OLDEST)
                        com.superbiz.app.domain.DebtSort.MODE_DEFAULT
                    else com.superbiz.app.domain.DebtSort.MODE_OLDEST
            }
            FilterPill(
                stringResourceCompat(R.string.debt_sort_balance),
                sortMode == com.superbiz.app.domain.DebtSort.MODE_BALANCE
            ) {
                vm.sortMode.value =
                    if (sortMode == com.superbiz.app.domain.DebtSort.MODE_BALANCE)
                        com.superbiz.app.domain.DebtSort.MODE_DEFAULT
                    else com.superbiz.app.domain.DebtSort.MODE_BALANCE
            }
            FilterPill(
                stringResourceCompat(R.string.debt_sort_name),
                sortMode == com.superbiz.app.domain.DebtSort.MODE_NAME
            ) {
                vm.sortMode.value =
                    if (sortMode == com.superbiz.app.domain.DebtSort.MODE_NAME)
                        com.superbiz.app.domain.DebtSort.MODE_DEFAULT
                    else com.superbiz.app.domain.DebtSort.MODE_NAME
            }
            // [P11-a]: مدخل شاشة المفضّلات وبطاقة الموقع الجغرافي — في الصف القابل للتمرير
            // (إضافة رقاقة نصية إلى ترويسة الأيقونات كانت ستُفيض عن العرض على الشاشات الضيقة)
            // [P34-M4-4] كانت البادئة "⭐ " إيموجياً صلباً قبل نص موطّن — أُزيلت توحيداً مع P30
            ActionPill(stringResourceCompat(R.string.fav_open), Cyan) {
                nav.navigate(com.superbiz.app.ui.nav.Routes.FAVORITES) { launchSingleTop = true }
            }
        }
        }

        if (rows.isEmpty()) {
            item(key = "debts-empty") { EmptyState(stringResourceCompat(R.string.empty_generic), Icons.Rounded.Person) }
        } else {
                // [P20-FIX] بطاقات الذكاء نُقلت إلى نهاية القائمة بطلب المستخدم —
                // كانت قبل قائمة الأطراف فتخفيها وتشوه المنظر (8 بطاقات فوق كل طرف)
                items(rows, key = { it.party.id }) { row ->
                    val bal = row.balance
                    val risk = row.risk
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.openStatement(row.party) }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconChip(
                                Icons.Rounded.Person, Color.White,
                                when {
                                    bal > 0 -> GreenDeep
                                    bal < 0 -> RedDeep
                                    else -> Vio
                                }
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    row.party.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = g.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    row.party.phone.ifBlank { stringResourceCompat(R.string.party_statement) },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = g.textSecondary
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    Money.formatP(bal, symbol),  // [P33-P8] قروش
                                    fontWeight = FontWeight.Bold,
                                    color = when {
                                        bal > 0.004 -> GreenDeep
                                        bal < -0.004 -> RedDeep
                                        else -> g.textSecondary
                                    }
                                )
                                if (risk != null && risk.worstOverdueDays > 0) {
                                    Badge(
                                        stringResourceCompat(R.string.overdue_days, risk.worstOverdueDays),
                                        when (risk.level) {
                                            2 -> RedDeep; 1 -> Amber; else -> GreenDeep
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
        }

        // ══ [P27-REORDER] عنوان قسم التحليلات — يظهر فقط عند توفر بيانات تحليلية فلا عنوان فارغ أبداً ══
        if (health != null || topDebtors.isNotEmpty()) {
            item(key = "debts-analytics-hdr") {
                SectionLabel(stringResourceCompat(R.string.debt_analytics_title))
            }
        }

        // بطاقة صحة التحصيل — DSO وكفاءة التحصيل من مقبوضات/مبيعات آجلة حقيقية
        // تُعرض فقط عند توفر البيانات (تحميل غير متزامن) — تلوين الكفاءة: 70+ أخضر، 40..69 كهرماني، أقل من 40 أحمر
        health?.let { h ->
            item(key = "debts-health") {
            val ceColor = when {
                h.collectionEfficiency >= 70.0 -> GreenDeep
                h.collectionEfficiency >= 40.0 -> Amber
                else -> RedDeep
            }
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResourceCompat(R.string.debt_dso, h.dso.toInt()),
                            modifier = Modifier.weight(1f),
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = g.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            stringResourceCompat(R.string.debt_ce, h.collectionEfficiency.toInt()),
                            fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            color = ceColor
                        )
                    }
                    Text(
                        stringResourceCompat(R.string.debt_ce_hint),
                        fontSize = 11.sp, color = g.textSecondary
                    )
                }
            }
            }
        }

        // شريط أعمار الذمم (وظيفة 29) — دلاء حقيقية من فواتير البيع غير المسددة
        // عبر BizMath.agingBuckets القائمة — يُخفى بصدق بلا أي مديونية مفتوحة
        health?.let { h ->
            if (h.aging.any { it > 0.004 }) {
            item(key = "debts-aging") {
                val w = com.superbiz.app.domain.DebtAging.weights(h.aging)
                val bucketColors = listOf(GreenDeep, Amber, Color(0xFFF97316), RedDeep)
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResourceCompat(R.string.debt_aging_title),
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = g.textPrimary
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(g.textSecondary.copy(alpha = 0.10f))
                        ) {
                            w.forEachIndexed { i, frac ->
                                if (frac > 0f) {
                                    Box(
                                        Modifier
                                            .weight(frac)
                                            .height(8.dp)
                                            .background(bucketColors[i])
                                    )
                                }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            h.aging.forEachIndexed { i, amt ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        stringResourceCompat(
                                            when (i) {
                                                0 -> R.string.debt_aging_b0; 1 -> R.string.debt_aging_b1
                                                2 -> R.string.debt_aging_b2; else -> R.string.debt_aging_b3
                                            }
                                        ),
                                        fontSize = 10.sp, color = bucketColors[i], fontWeight = FontWeight.SemiBold
                                    )
                                    Text(Money.num(amt), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = g.textPrimary)
                                }
                            }
                        }
                    }
                }
            }
            }
        }

        // بطاقة أعلى 5 مدينين (وظيفة 30) — إخفاء صادق بلا مدينين
        if (topDebtors.isNotEmpty()) {
            item(key = "debts-top5") {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResourceCompat(R.string.debt_top_title),
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = g.textPrimary
                    )
                    topDebtors.forEachIndexed { i, d ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${i + 1}.", fontSize = 12.sp,
                                color = g.textSecondary, modifier = Modifier.width(22.dp)
                            )
                            Text(
                                d.name,
                                fontSize = 13.sp, color = g.textPrimary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                Money.format(d.balance, symbol),
                                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = GreenDeep
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResourceCompat(R.string.debt_top_share, d.sharePct),
                                fontSize = 10.sp, color = g.textSecondary
                            )
                        }
                    }
                }
            }
            }
        }


        // بطاقات الذكاء تُبقى خاتمة الشاشة كما أراد المستخدم في P20 — تظهر مع وجود أطراف فقط
        if (rows.isNotEmpty()) {
                item(key = "debts-insights-hdr") { SectionLabel(stringResourceCompat(R.string.insights_section_title)) }
                // ══ [P20-FIX] بطاقات الذكاء — بعد قائمة الأطراف (كانت قبلها فتخفيها) ══
                // قائمة أولوية التحصيل — ترتيب الاتصال الذكي
                item(key = "collection-priority") { com.superbiz.app.ui.insights.CollectionPriorityCard(appVM, smartVM) }
                // [P44-K1] جولة 5: المكدس المخصص — ترتيب/إظهار المجموعات السبع بافتضاض المستخدم
                item(key = "insights-stack-debts") {
                    com.superbiz.app.ui.insights.InsightsStack(
                        com.superbiz.app.domain.DashboardPrefsP44.SCREEN_DEBTS,
                        listOf(
                            // المسح الذكي/الحدود الائتمانية/الإجراء التالي
                            com.superbiz.app.ui.insights.insightsGroup("r9") { com.superbiz.app.ui.insights.DebtsR9Card(r9VM, appVM) },
                            // أعمار الذمم/توقع التحصيل/تركّز المديونية
                            com.superbiz.app.ui.insights.insightsGroup("r10") { com.superbiz.app.ui.insights.DebtsR10Card(appVM, r10VM) },
                            // شرائح تأخر الأقساط/شرائح العملاء RFM
                            com.superbiz.app.ui.insights.insightsGroup("r11") { com.superbiz.app.ui.insights.DebtsR11Card(r11VM) },
                            com.superbiz.app.ui.insights.insightsGroup("r12") { com.superbiz.app.ui.insights.DebtsR12Card(appVM, r12VM) },
                            com.superbiz.app.ui.insights.insightsGroup("r13") { com.superbiz.app.ui.insights.DebtsR13Card(appVM, r13VM) },
                            com.superbiz.app.ui.insights.insightsGroup("r14") { com.superbiz.app.ui.insights.DebtsR14Card(r14VM) },
                            com.superbiz.app.ui.insights.insightsGroup("r15") { com.superbiz.app.ui.insights.DebtsR15Card(r15VM) },
                        )
                    )
                }
        }
    }

    // حوار إضافة/تعديل طرف
    // [P40-M5] الجسم المستخرج حرفياً إلى PartyEditorDialog (internal) لاختباره
    // تركيبياً (PartyEditorP40Test) — نفس الكائنات بالمرجع ونفس سلوك الحفظ
    if (showAddParty) {
        PartyEditorDialog(
            editPartyId = editPartyId,
            editName = editName, editPhone = editPhone, editType = editType,
            editNote = editNote, editEmail = editEmail, editAddress = editAddress,
            editTaxNumber = editTaxNumber, editCity = editCity, editCountry = editCountry,
            editWebsite = editWebsite, editCrNumber = editCrNumber,
            editAccountNumber = editAccountNumber,
            appContext = activity.applicationContext,
            editScope = editScope,
            onSaveDone = { vm.refresh(); showAddParty = false },
            onDismiss = { showAddParty = false }
        )
    }

    // حوار دين/دفعة
    opDialog?.let { (party, op, direction) ->
        var amount by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        // [تدقيق M-6] الطرف ثنائي الدور (عميل ومورد) يختار جانب الدين صراحةً —
        // كان يُرحَّل دائماً جانب العميل فتُسجَّل ذمة مورد مدينةً عليك بدل دائنة لك
        var debtDirection by remember { mutableStateOf(if (direction == 1) 1 else 0) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { opDialog = null },
            containerColor = g.surfaceStrong,
            title = {
                Text(
                    (if (op == "debt") stringResourceCompat(R.string.new_debt) else stringResourceCompat(R.string.new_payment)) + " — " + party.name,
                    color = g.textPrimary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BizField(amount, { amount = it }, stringResourceCompat(R.string.amount), keyboard = numberFieldOptions())
                    // [تدقيق M-6] شرائح الاتجاه — فقط لدين طرفٍ ثنائي الدور
                    if (op == "debt" && party.isCustomer && party.isSupplier) {
                        Text(
                            stringResourceCompat(R.string.debt_dir_label),
                            fontSize = 12.sp, color = g.textSecondary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            BizPill(
                                stringResourceCompat(R.string.debt_dir_receivable), g.accent,
                                mode = PillMode.SELECT, selected = debtDirection == 0
                            ) { debtDirection = 0 }
                            BizPill(
                                stringResourceCompat(R.string.debt_dir_payable), g.accent,
                                mode = PillMode.SELECT, selected = debtDirection == 1
                            ) { debtDirection = 1 }
                        }
                    }
                    BizField(note, { note = it }, stringResourceCompat(R.string.note))
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val v = parseNum(amount)
                    if (v > 0) {
                        // [تدقيق M-6] الاتجاه المختار يمرّ للدفتر (الأطراف الأحادية لا ترى الشرائح
                        // فتتوجّه بنوعها داخل الدفتر كما كان)
                        if (op == "debt") vm.addDebt(party, v, note, debtDirection)
                        else vm.addPayment(party, v, direction)
                    }
                    opDialog = null
                }) { Text(stringResourceCompat(R.string.save), color = g.accent, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { opDialog = null }) {
                    Text(stringResourceCompat(R.string.cancel), color = g.textSecondary)
                }
            }
        )
    }

    // كشف الحساب
    val st by vm.statement.collectAsState()
    st?.let { statementUi ->
        PartyStatementSheet(
            vm = vm, appVM = appVM, data = statementUi,
            onDismiss = { vm.closeStatement() },
            onNewDebt = { opDialog = Triple(statementUi.party, "debt", 0) },
            // اتجاه الدفعة بحسب نوع الطرف — كان يُمرَّر 0 دائماً فتُقيَّد دفعة المورّد
            // كقبض على ذمم العميل بدل سداد للذمم الدائنة
            onPayment = {
                val p = statementUi.party
                opDialog = Triple(p, "pay", if (p.type == 1) 1 else 0)
            },
            // [P17-c] مدخل كشف الحساب الكامل (statement/{partyId}) من أعلى اللوحة
            onOpenStatement = {
                nav.navigate(com.superbiz.app.ui.nav.Routes.STATEMENT.replace("{partyId}", statementUi.party.id.toString()))
            }
        )
    }

    // لوحة استيراد جهات الاتصال — [P10] توقيت مبسط بلا vm/parties (لا يستخدمهما)
    if (showContacts) {
        ContactsSheet(
            onImported = { vm.refresh() },
            onDismiss = { showContacts = false }
        )
    }

    // لوحة الأطراف المكررة — كشف + دمج ذرّي
    if (showDuplicates) {
        DuplicatesSheet(vm) { showDuplicates = false }
    }
}

/**
 * [P36-M4-1] مفوّض رقيق للذرة الموحدة BizPill (SELECT) — التوقيع الأصلي محفوظ
 * فلا يتأثر أي من مواضع الاستدعاء، والتنفيذ الوحيد في ui/components/Common.kt.
 */
@Composable
fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val g = glassColors()
    BizPill(label, g.accent, mode = PillMode.SELECT, selected = selected, onClick = onClick)
}

/** كشف حساب الطرف — لوحة سفلية كاملة */
@Composable
fun PartyStatementSheet(
    vm: DebtsVM,
    appVM: AppVM,
    data: DebtsVM.StatementUi,
    onDismiss: () -> Unit,
    onNewDebt: () -> Unit,
    onPayment: () -> Unit,
    // [P17-c] فتح كشف الحساب الكامل (شاشة statement/{partyId}) — افتراضي آمن لبقية المواضع
    onOpenStatement: () -> Unit = {}
) {
    val g = glassColors()
    val symbol by appVM.symbol.collectAsState()
    val context = LocalContext.current
    val party = data.party
    // بطاقة ذكاء العميل داخل كشف الحساب — LTV/Churn/RFM/موعد الشراء
    var custIntel by remember(party.id) { mutableStateOf<com.superbiz.app.vm.SmartInsightsVM.CustomerIntel?>(null) }
    LaunchedEffect(party.id) {
        custIntel = com.superbiz.app.vm.SmartInsightsVM.customerIntelOf(context.applicationContext, party.id)
    }
    val shareTitle = stringResourceCompat(R.string.share_statement)
    val pdfTitle = stringResourceCompat(R.string.statement_pdf)

    // [P11-a] تمييز الطرف كمفضّل من كشف الحساب مباشرة — كتابة عبر AppGraph (نمط
    // ContactsSheet بلا VM؛ DebtsVM لا يملك toggleFavorite وصاحب الملف FeatureVMs
    // خارج هذا التعديل). حالة موضعية للنجمة لأن بيانات الكشف لقطة لا تُعاد تركيبها
    // مع كل كتابة، وتدفق الأطراف الحي يحدّث بقية الشاشات تلقائياً
    var favStar by remember(party.id) { mutableStateOf(party.favorite) }
    val starScope = rememberCoroutineScope()

    // توليد PDF الكشف عند الطلب — : عبر حوار التقرير الجاهز (طباعة نظامية/حرارية/مشاركة)
    var pendingPdf by remember { mutableStateOf(false) }
    var readyPdf by remember { mutableStateOf<com.superbiz.app.print.ReportReady?>(null) }
    LaunchedEffect(pendingPdf) {
        if (!pendingPdf) return@LaunchedEffect
        pendingPdf = false
        val activity = context as? MainActivity ?: return@LaunchedEffect
        try {
            // بناء كشف PDF على Dispatchers.IO وتعيين الحالة بعد العودة للخيط الرئيسي
            val ready = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val graph = com.superbiz.app.AppGraph.from(activity.application)
                val business = graph.settings.snapshot().businessName
                    .ifBlank { activity.getString(R.string.business_default) }
                val file = com.superbiz.app.pdf.A4Report.statement(
                    activity, party, data.rows, data.balance,
                    business, symbol, appVM.avatarBitmap()
                )
                com.superbiz.app.print.ReportReady(
                    file, pdfTitle,
                    com.superbiz.app.print.ReportReceiptFactory.statement(
                        activity, business, symbol, party.name, data.balance, data.rows
                    )
                )
            }
            readyPdf = ready
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                activity, activity.getString(R.string.pdf_error),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
    readyPdf?.let { ready ->
        com.superbiz.app.print.ReportReadyDialog(ready) { readyPdf = null }
    }

    // خطة سداد مقترحة (وظيفة 21) — حوار تقسيم الرصيد قرشاً بقرش بمواعيد شهرية
    var showPlan by remember { mutableStateOf(false) }

    fun statementText(): String {
        val sb = StringBuilder()
        // [P6-M45 إصلاح] نص المشاركة كان عربياً صلباً غير موطن — مفاتيح debt_share_*
        // بكل الغتين عبر context.getString بنمط الملف
        sb.append(context.getString(R.string.debt_share_title, party.name)).append('\n')
        sb.append(
            context.getString(R.string.debt_share_date, Dates.short(System.currentTimeMillis()))
        ).append('\n')
        sb.append(
            context.getString(R.string.debt_share_balance, Money.formatP(data.balance, symbol))  // [P33-P8] قروش
        ).append("\n\n")
        // كان takeLast(15) يقصّ الكشف صمتاً — العميل يستلم نصاً
        // يبدو كاملاً ويخفي أقدم الحركات (وهي الأهم في الخلاف). سطر إفصاح صريح
        // بنفس روح R12-C9 في PDF الفاتورة: لا حذف صامت في مستند مالي
        val omitted = (data.rows.size - 15).coerceAtLeast(0)
        if (omitted > 0) sb.append(context.getString(R.string.debt_share_omitted, omitted)).append("\n\n")
        data.rows.takeLast(15).forEach { r ->
            sb.append(Dates.short(r.date)).append(" | ").append(r.title).append(" | ")
            // التسميات كانت مقلوبة — القيد المدين (زيادة الدين) «عليه»
            // والدائن (نقصان الدين) «له»، فكانت فاتورة بيع 1000 تُقرأ «له 1000» للعميل!
            // [P33-P8] سطور كشف الحساب قروش Long (StatementRow) — عرض عبر numP
            if (r.debit > 0) sb.append(context.getString(R.string.debt_share_debit, Money.numP(r.debit)))
            if (r.credit > 0) sb.append(context.getString(R.string.debt_share_credit, Money.numP(r.credit)))
            sb.append(" | ").append(context.getString(R.string.debt_share_row_balance, Money.numP(r.balance))).append('\n')
        }
        sb.append("\n").append(context.getString(R.string.debt_share_footer))
        return sb.toString()
    }

    fun openWhatsApp() {
        if (party.phone.isBlank()) return
        val clean = party.phone.replace(Regex("[^0-9+]"), "")
        val url = "https://wa.me/" + clean.removePrefix("+") + "?text=" + Uri.encode(statementText())
        // بلا حماية كان ActivityNotFoundException يغلق التطبيق فور الضغط على أجهزة بلا متصفح/واتساب
        startIntentSafe(
            context,
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        )
    }

    fun openSms() {
        if (party.phone.isBlank()) return
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${party.phone}"))
        intent.putExtra("sms_body", statementText())
        // الأجهزة اللوحية/بلا تطبيق رسائل كانت تنهار هنا فوراً
        startIntentSafe(context, intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }

    fun shareGeneric() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, statementText())
        }
        context.startActivity(Intent.createChooser(intent, shareTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

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
                .fillMaxHeight(0.86f)
                .imePadding()
        ) {
            // [P21-FIX] كانت اللوحة Column ثابت 560dp غير قابل للتمرير: الرأس وبطاقة ذكاء
            // العميل وزر الكشف وشريط الخطر والأزرار تستهلك الارتفاع فتُضغط قائمة الحركات
            // حتى الصفر على الشاشات الصغيرة/الخط الكبير، وكانت اللوحة نفسها تفيض أسفل الشاشة.
            // الآن ارتفاع نسبي 86٪ وقائمة واحدة تمرر الرأس والحركات معاً.
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .clickable(enabled = false) { }
                    .padding(start = 18.dp, end = 18.dp, top = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 18.dp)
            ) {
                item(key = "sheet-head") {
                Column {
                // رأس الكشف
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(party.name, style = MaterialTheme.typography.titleLarge, color = g.textPrimary)
                        // بطاقة ذكاء العميل أعلى كشف الحساب — تُخفى بلا فواتير (صدق الفراغ)
                        custIntel?.let {
                            Spacer(Modifier.height(8.dp))
                            com.superbiz.app.ui.insights.CustomerIntelCard(it, symbol)
                        }
                        Text(
                            party.phone, style = MaterialTheme.typography.bodySmall,
                            color = g.textSecondary
                        )
                    }
                    // [P11-a] نجمة المفضّل: ممتلئة = مفضّل، فارغة = عادي — تحديث موضعي عبر PartyDao
                    IconButton(onClick = {
                        val newFav = !favStar
                        favStar = newFav
                        starScope.launch(Dispatchers.IO) {
                            runCatching {
                                com.superbiz.app.AppGraph.from(context.applicationContext)
                                    .db.parties().setFavorite(party.id, newFav)
                            }
                        }
                    }) {
                        Icon(
                            if (favStar) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                            null,
                            tint = if (favStar) Amber else g.textSecondary
                        )
                    }
                    Text(
                        Money.formatP(data.balance, symbol),  // [P33-P8] قروش
                        fontWeight = FontWeight.ExtraBold, fontSize = 20.sp,
                        color = if (data.balance > 0) GreenDeep else if (data.balance < 0) RedDeep else g.textSecondary
                    )
                }
                Spacer(Modifier.height(8.dp))

                // [P17-c] الزر الرئيسي أعلى اللوحة — كشف الحساب الكامل بالقوالب والمعاينة الحية
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(g.accent)
                        .clickable { onOpenStatement() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResourceCompat(R.string.st_open_statement),
                        color = Color.White, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                Spacer(Modifier.height(8.dp))

                // شريط الخطر
                RiskBar(data.risk)

                // درجة الجدارة السلوكية — BizMath.creditScore من سلوك السداد الحقيقي
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResourceCompat(R.string.credit_score),
                        fontSize = 12.sp, color = g.textSecondary, modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${data.credit} / 100",
                        fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = when {
                            data.credit >= 80 -> GreenDeep
                            data.credit >= 55 -> Amber
                            else -> RedDeep
                        }
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    ActionPill(stringResourceCompat(R.string.new_debt), Vio) { onNewDebt() }
                    ActionPill(stringResourceCompat(R.string.new_payment), GreenDeep) { onPayment() }
                    // مدخل خطة السداد المقترحة (وظيفة 21)
                    ActionPill(stringResourceCompat(R.string.debt_plan_short), Color(0xFF7C3AED)) { showPlan = true }
                    ActionPill(stringResourceCompat(R.string.send_whatsapp), Color(0xFF25D366)) { openWhatsApp() }
                    ActionPill(stringResourceCompat(R.string.send_sms), Cyan) { openSms() }
                    ActionPill(stringResourceCompat(R.string.share), g.textSecondary) { shareGeneric() }
                    ActionPill(pdfTitle, Color(0xFFDC2626)) { pendingPdf = true }
                }
                }
                }

                if (data.rows.isEmpty()) {
                    item(key = "sheet-empty") {
                        EmptyState(stringResourceCompat(R.string.empty_generic), Icons.Rounded.History)
                    }
                } else {
                        // مفتاح موضعي آمن — نفس اليوم والمذكرة والرصيد المتساوي كانت تُسقط التطبيق بمفتاح مكرر
                        itemsIndexed(data.rows.reversed(), key = { idx, _ -> "st$idx" }) { _, r ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(g.textSecondary.copy(alpha = 0.06f))
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        r.title, style = MaterialTheme.typography.bodyMedium,
                                        color = g.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    Text(Dates.short(r.date), fontSize = 11.sp, color = g.textSecondary)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    // [P33-P8] مدين/دائن/رصيد قروش Long (StatementRow)
                                    if (r.debit > 0) Text("+" + Money.numP(r.debit), color = GreenDeep, fontWeight = FontWeight.Bold)
                                    if (r.credit > 0) Text("-" + Money.numP(r.credit), color = RedDeep, fontWeight = FontWeight.Bold)
                                    Text(Money.numP(r.balance), fontSize = 11.sp, color = g.textSecondary)
                                }
                            }
                        }
                }
            }
        }
    }

    // حوار خطة السداد المقترحة (وظيفة 21)
    if (showPlan) {
        DebtPlanDialog(vm = vm, party = party, balance = data.balance, symbol = symbol) {
            showPlan = false
        }
    }
}

/**
 * : حوار خطة السداد المقترحة للذمة (وظيفة 21).
 * البناء عبر DebtPlan النقي (قسمة الرصيد قرشاً بقرش بمواعيد شهرية مع تقصير
 * الأشهر القصيرة)، وزر «إنشاء التذكيرات» يُنشئ تذكيرات حقيقية عبر المسار
 * القائم (أعمال WorkManager مؤجلة بموعد كل دفعة — المخطط لا يحتوي ReminderEntity
 * أصلاً وهذا أقرب مسار تذكيرات قائم) مع Toast بعدد ما أُنشئ فعلاً.
*/
@Composable
private fun DebtPlanDialog(
    vm: DebtsVM,
    party: com.superbiz.app.data.db.Party,
    balance: Long,  // [P33-P8] قروش
    symbol: String,
    onDismiss: () -> Unit
) {
    val g = glassColors()
    val context = LocalContext.current
    var n by remember { mutableStateOf(3) }
    val halalas = balance  // [P33-P8] الرصيد قروش أصلاً — halalasOf المباشر يستغني عن تحويل Double
    val payments = remember(halalas, n) {
        com.superbiz.app.domain.DebtPlan.build(halalas, n, System.currentTimeMillis())
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = g.surfaceStrong,
        title = { Text(stringResourceCompat(R.string.debt_plan_title), color = g.textPrimary) },
        text = {
            // [P22-FIX] تمرير رأسي — جدول 12 قسطاً + رقائق العدد كانا قد يفيضان عن ارتفاع الحوار
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (halalas <= 0L) {
                    // حالة فراغ صادقة: لا رصيد مدين لجدولته
                    Text(stringResourceCompat(R.string.debt_plan_no_balance), color = g.textSecondary)
                } else {
                    Text(
                        stringResourceCompat(R.string.debt_plan_payments),
                        fontSize = 12.sp, color = g.textSecondary
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        (2..12).forEach { k ->
                            FilterPill("$k", n == k) { n = k }
                        }
                    }
                    Text(
                        stringResourceCompat(R.string.debt_plan_hint),
                        fontSize = 11.sp, color = g.textSecondary
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        payments.forEach { p ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "#${p.seq}", fontSize = 12.sp,
                                    color = g.textSecondary, modifier = Modifier.width(30.dp)
                                )
                                Text(
                                    Money.format(com.superbiz.app.domain.DebtPlan.riyals(p.halalas), symbol),
                                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                    color = g.textPrimary, modifier = Modifier.weight(1f)
                                )
                                Text(Dates.short(p.dueDate), fontSize = 11.sp, color = g.textSecondary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (halalas > 0L && payments.isNotEmpty()) {
                androidx.compose.material3.TextButton(onClick = {
                    vm.createPlanReminders(party, payments) { created ->
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.debt_plan_created, created),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    onDismiss()
                }) {
                    Text(
                        stringResourceCompat(R.string.debt_plan_create),
                        color = g.accent, fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResourceCompat(R.string.cancel), color = g.textSecondary)
            }
        }
    )
}

@Composable
fun RiskBar(risk: com.superbiz.app.domain.RiskResult?) {
    val g = glassColors()
    if (risk == null) return
    val color = when (risk.level) {
        2 -> RedDeep; 1 -> Amber; else -> GreenDeep
    }
    val label = when (risk.level) {
        2 -> stringResourceCompat(R.string.risk_high); 1 -> stringResourceCompat(R.string.risk_medium)
        else -> stringResourceCompat(R.string.risk_low)
    }
    Column {
        Row {
            Text(stringResourceCompat(R.string.risk_score), fontSize = 12.sp, color = g.textSecondary)
            Spacer(Modifier.weight(1f))
            Text("$label • ${risk.score}/100", fontSize = 12.sp, color = color, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(g.textSecondary.copy(alpha = 0.12f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth((risk.score / 100f).coerceIn(0.03f, 1f))
                    .height(7.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
        }
    }
}

/**
 * [P36-M4-1] مفوّض رقيق للذرة الموحدة BizPill (ACTION) — اكتسب معامل [enabled]
 * (كانت FavoritesScreen اضطرت لنسخة خاصة LockableActionPill لهذا الغرض) والحذف
 * آمن لأن القيمة الافتراضية true تحفظ سلوك كل المواضع القائمة.
 * ترتيب الباراميترات مقصود: [onClick] أخيراً كي يلتقط trailing lambda في كل
 * مواضع الاستدعاء القائمة (قاعدة Kotlin)، و[enabled] يُمرَّر بالاسم دائماً.
 */
@Composable
fun ActionPill(label: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    BizPill(label, color, mode = PillMode.ACTION, enabled = enabled, onClick = onClick)
}

// [P26-TOOLS] زر قفزة الترويسة HeaderJump أُحيل بعد نقل أدواته إلى بطاقة الأدوات الاحترافية

// لوحة الأطراف المكررة — كشف isProbableDuplicate بعد التطبيع العربي
// ثم دمج ذرّي عبر LedgerRepo.mergeParties (فواتير + دفعات + أسطر دفتر) بلا فقد رصيد.
@Composable
private fun DuplicatesSheet(vm: DebtsVM, onDismiss: () -> Unit) {
    val g = glassColors()
    val context = LocalContext.current
    val pairs by vm.duplicates.collectAsState()
    val scanning by vm.duplicatesScanning.collectAsState()

    // تأكيد الدمج — إجراء مدمّر لا يُنفَّذ مباشرة
    var confirmPair by remember { mutableStateOf<DebtsVM.DuplicatePair?>(null) }
    confirmPair?.let { pair ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmPair = null },
            containerColor = g.surfaceStrong,
            title = { Text(stringResourceCompat(R.string.dup_confirm_title), color = g.textPrimary) },
            text = {
                Text(
                    stringResourceCompat(R.string.dup_confirm_text, pair.dup.name, pair.keep.name),
                    color = g.textSecondary
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val p = pair
                    confirmPair = null
                    vm.mergeDuplicate(p) { ok ->
                        android.widget.Toast.makeText(
                            context,
                            // [P6-M46 إصلاح] فشل الدمج كان يعرض R.string.pdf_error — رسالة خاطئة الدلالة
                            // تماماً؛ الآن مفتاح صحيح الدلالة party_merge_failed
                            context.getString(if (ok) R.string.dup_done else R.string.party_merge_failed),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }) {
                    Text(stringResourceCompat(R.string.dup_merge), color = RedDeep, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmPair = null }) {
                    Text(stringResourceCompat(R.string.cancel), color = g.textSecondary)
                }
            }
        )
    }

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
                .fillMaxHeight(0.78f)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .clickable(enabled = false) { }
                    .padding(18.dp)
            ) {
                Text(stringResourceCompat(R.string.dup_title), style = MaterialTheme.typography.titleLarge, color = g.textPrimary)
                Text(stringResourceCompat(R.string.dup_note), fontSize = 12.sp, color = g.textSecondary)
                Spacer(Modifier.height(10.dp))
                when {
                    scanning -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.CircularProgressIndicator(color = g.accent)
                    }
                    pairs.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResourceCompat(R.string.dup_empty), color = g.textSecondary)
                    }
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(pairs, key = { i, _ -> "dup$i" }) { _, pair ->
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            pair.keep.name, style = MaterialTheme.typography.titleSmall,
                                            color = g.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            stringResourceCompat(R.string.dup_with, pair.dup.name),
                                            fontSize = 12.sp, color = g.textSecondary,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        stringResourceCompat(R.string.dup_merge),
                                        color = RedDeep, fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable { confirmPair = pair }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
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

/**
 * [P40-M5] حوار إضافة/تعديل الطرف (12 حقلاً) — مستخرج حرفياً من DebtsScreen
 * (كان inline داخل جسم الشاشة) كي يُختبر تركيبياً بـRobolectric-Compose
 * الحالات الـ13 تُمرّر بالمرجع (نفس كائنات MutableState في الشاشة فلا تُنسخ)،
 * وحفظ التأكيد بالشرط والترتيب الأصليين (الاسم غير فارغ ← كتابة IO عبر
 * LedgerRepo.saveParty ثم onSaveDone) — لا تغيير سلوكياً إطلاقاً.
 * [P22-FIX] تمرير رأسي — الحوار يضم 12 حقلاً (P17-c أضاف 8 أعمدة كشف) وهو
 * قابل للتمرير بالكامل منذ إصلاح.
*/
@Composable
internal fun PartyEditorDialog(
    editPartyId: Long,
    editName: MutableState<String>,
    editPhone: MutableState<String>,
    editType: MutableState<Int>,
    editNote: MutableState<String>,
    editEmail: MutableState<String>,
    editAddress: MutableState<String>,
    editTaxNumber: MutableState<String>,
    editCity: MutableState<String>,
    editCountry: MutableState<String>,
    editWebsite: MutableState<String>,
    editCrNumber: MutableState<String>,
    editAccountNumber: MutableState<String>,
    appContext: android.content.Context,
    editScope: kotlinx.coroutines.CoroutineScope,
    onSaveDone: () -> Unit,
    onDismiss: () -> Unit
) {
    val g = glassColors()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = g.surfaceStrong,
        title = { Text(stringResourceCompat(R.string.add), color = g.textPrimary) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                BizField(editName.value, { editName.value = it }, stringResourceCompat(R.string.name))
                BizField(editPhone.value, { editPhone.value = it }, stringResourceCompat(R.string.phone))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill(stringResourceCompat(R.string.customers), editType.value == 0) { editType.value = 0 }
                    FilterPill(stringResourceCompat(R.string.suppliers), editType.value == 1) { editType.value = 1 }
                    FilterPill(stringResourceCompat(R.string.party_both), editType.value == 2) { editType.value = 2 } // [P34-M4-4] كانت "+C" صلبة غامضة — رقاقة النوع الثالث بلغة التطبيق
                }
                BizField(editNote.value, { editNote.value = it }, stringResourceCompat(R.string.note))
                // [P17-c] أعمدة كشف الحساب الثمانية — نفس نمط الحقول القائمة في هذا الحوار
                BizField(editEmail.value, { editEmail.value = it }, stringResourceCompat(R.string.st_field_email))
                BizField(editAddress.value, { editAddress.value = it }, stringResourceCompat(R.string.st_field_address))
                BizField(editTaxNumber.value, { editTaxNumber.value = it }, stringResourceCompat(R.string.st_field_tax_number))
                BizField(editCity.value, { editCity.value = it }, stringResourceCompat(R.string.st_field_city))
                BizField(editCountry.value, { editCountry.value = it }, stringResourceCompat(R.string.st_field_country))
                BizField(editWebsite.value, { editWebsite.value = it }, stringResourceCompat(R.string.st_field_website))
                BizField(editCrNumber.value, { editCrNumber.value = it }, stringResourceCompat(R.string.st_field_cr_number))
                BizField(editAccountNumber.value, { editAccountNumber.value = it }, stringResourceCompat(R.string.st_field_account_number))
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                if (editName.value.isNotBlank()) {
                    // [P17-c] حفظ عبر مسار تحديث الأطراف القائم في المستودع (LedgerRepo.saveParty —
                    // upsert يمرر أعمدة 17-a تلقائياً). DebtsVM.saveParty يبني Party بأعمدة
                    // الكشف الافتراضية فارغة وملفه خارج ملكية هذه الموجة، فالكتابة المباشرة
                    // بـupsert واحد هنا تمنع سباق حفظين وتحفظ الـ8 أعمدة فعلاً.
                    editScope.launch(Dispatchers.IO) {
                        runCatching {
                            val graph = com.superbiz.app.AppGraph.from(appContext)
                            val existing = if (editPartyId != 0L)
                                graph.db.parties().byId(editPartyId) else null
                            val base = existing
                                ?: com.superbiz.app.data.db.Party(name = "", phone = "", type = editType.value, note = editNote.value)
                            graph.ledger.saveParty(
                                base.copy(
                                    id = if (editPartyId != 0L) editPartyId else base.id,
                                    name = editName.value.trim(), phone = editPhone.value.trim(),
                                    type = editType.value, note = editNote.value,
                                    email = editEmail.value.trim().ifBlank { null },
                                    address = editAddress.value.trim().ifBlank { null },
                                    taxNumber = editTaxNumber.value.trim().ifBlank { null },
                                    city = editCity.value.trim().ifBlank { null },
                                    country = editCountry.value.trim().ifBlank { null },
                                    website = editWebsite.value.trim().ifBlank { null },
                                    crNumber = editCrNumber.value.trim().ifBlank { null },
                                    accountNumber = editAccountNumber.value.trim().ifBlank { null }
                                )
                            )
                        }
                    }
                    onSaveDone()
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
