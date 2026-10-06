package com.superbiz.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.CallMade
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.superbiz.app.MainActivity
import com.superbiz.app.R
import com.superbiz.app.data.db.CheckEntity
import com.superbiz.app.ui.components.SectionLabel
import com.superbiz.app.ui.components.Badge as RiskBadge
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
import com.superbiz.app.ui.theme.Cyan
import com.superbiz.app.ui.components.SubHeader
import com.superbiz.app.ui.components.numberFieldOptions
import com.superbiz.app.ui.components.parseNum
import com.superbiz.app.ui.theme.Amber
import com.superbiz.app.ui.theme.Blue
import com.superbiz.app.ui.theme.GreenDeep
import com.superbiz.app.ui.theme.RedDeep
import com.superbiz.app.ui.theme.Vio
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.util.Dates
import com.superbiz.app.util.Money
// مشاركة تقويم الشيكات عبر المسار الآمن
import com.superbiz.app.util.startIntentSafe
import com.superbiz.app.vm.AppVM
import com.superbiz.app.vm.ChecksVM
import com.superbiz.app.VMFactory
import androidx.compose.material3.AlertDialog
// [P36-M4-5] منتقي التاريخ الموحد M3 — نفس نمط StDatePicker في كشف الحساب (النمط القياسي بالمشروع)
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
// تصدير ICS عبر SAF + كوروتينات الواجهة
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ChecksScreen(appVM: AppVM, nav: NavHostController) {
    val activity = LocalContext.current as? MainActivity ?: return
    val vm: ChecksVM = viewModel(factory = remember { VMFactory(activity) })
    // مخاطرة ارتجاع الشيكات — R7Smart
    // [P5-H9 إصلاح]: VMs الرؤى مشتركة على مستوى النشاط — كانت كل شاشة تنشئ نسختها وتشغل loadAll كاملاً (حتى ×8 تكلفة لكل جولة تنقل)
    val smartVM: com.superbiz.app.vm.SmartInsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    // تغطية المستحقات القادمة بالوارد الشكي
    val r9VM: com.superbiz.app.vm.R9InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    // رؤى الموجة R10 للشيكات
    val r10VM: com.superbiz.app.vm.R10InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    // رؤى الموجة R11 للشيكات (الارتجاع/الفجوة النقدية)
    val r11VM: com.superbiz.app.vm.R11InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    // VM الرؤى الذكية للموجة R12 — عبر R12Smart
    val r12VM: com.superbiz.app.vm.R12InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    val r13VM: com.superbiz.app.vm.R13InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    // VM الرؤى الذكية للموجة R14 — عبر R14Smart
    val r14VM: com.superbiz.app.vm.R14InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    val r15VM: com.superbiz.app.vm.R15InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    val checkRiskMap by smartVM.checkRisks.collectAsState()
    val riskById = remember(checkRiskMap) { checkRiskMap.associate { it.checkId to it.riskPct } }
    val checks by vm.filtered.collectAsState()
    // [P24-HERO] القائمة الكاملة + معرّفات الأرشيف لحساب بطاقات الرئيسية الاحترافية
    // (إحصاء عام لا يتأثر بالرقائق ولا بواجهة الأرشيف)
    val allChecks by vm.checks.collectAsState()
    val archivedIds by vm.archivedIds.collectAsState()
    val filter by vm.statusFilter.collectAsState()
    val symbol by appVM.symbol.collectAsState()
    // إعداد عطلة الأسبوع يغذّي حساب أيام العمل في صفوف الشيكات
    val settings by appVM.settings.collectAsState()
    val g = glassColors()

    // شيكات هذا الأسبوع (وظيفة 25) + الأرشفة (بديل R2) + تصدير ICS (وظيفة 24)
    val week by vm.weekStats.collectAsState()
    val weekActive by vm.weekOnly.collectAsState()
    val archiveMode = filter == vm.FILTER_ARCHIVE
    val scope = rememberCoroutineScope()

    var showAdd by remember { mutableStateOf(false) }

    // تصدير ICS عبر SAF — بلا أذونات تخزين (وظيفة 24)
    var pendingIcs by remember { mutableStateOf<String?>(null) }
    var icsBusy by remember { mutableStateOf(false) }
    val icsLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/calendar")
    ) { uri ->
        val content = pendingIcs
        pendingIcs = null
        if (uri != null && content != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        activity.contentResolver.openOutputStream(uri)?.use {
                            it.write(content.toByteArray(Charsets.UTF_8))
                        } ?: throw java.io.IOException("null output stream")
                    }
                    android.widget.Toast.makeText(
                        activity, activity.getString(R.string.checks_ics_done),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    android.widget.Toast.makeText(
                        activity, activity.getString(R.string.checks_ics_fail),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
 * : بناء تقويم الشيكات عبر ChecksIcs النقي ثم حفظ (SAF) أو مشاركة
 * عبر FileProvider + startIntentSafe — بلا شيكات مفتوحة تظهر رسالة صادقة.
*/
    fun exportIcs(save: Boolean) {
        if (icsBusy) return
        // [P20-FIX agent2]: كان العلم يُضبط داخل الكوروتين (TOCTOU) — نقرتان بنفس الإطار
        // تعبران الحارس فيفتح حاران SAF/مشاركة
        icsBusy = true
        scope.launch {
            try {
                val names = vm.partyNames()
                val unknownParty = activity.getString(com.superbiz.app.R.string.unknown_party)
                val content = com.superbiz.app.domain.ChecksIcs.build(
                    vm.checks.value,
                    partyName = { pid -> names[pid] ?: unknownParty }
                )
                if (!content.contains("BEGIN:VEVENT")) {
                    android.widget.Toast.makeText(
                        activity, activity.getString(R.string.checks_ics_empty),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                if (save) {
                    pendingIcs = content
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                        .format(java.util.Date())
                    icsLauncher.launch("superbiz-checks-$stamp.ics")
                } else {
                    withContext(Dispatchers.IO) {
                        java.io.File(
                            java.io.File(activity.cacheDir, "shared").apply { mkdirs() },
                            "superbiz-checks.ics"
                        ).writeText(content, Charsets.UTF_8)
                    }
                    val file = java.io.File(
                        java.io.File(activity.cacheDir, "shared"), "superbiz-checks.ics"
                    )
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        activity, "com.superbiz.app.fileprovider", file
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/calendar"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startIntentSafe(
                        activity,
                        Intent.createChooser(intent, activity.getString(R.string.checks_ics_share))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    activity, activity.getString(R.string.checks_ics_fail),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } finally {
                icsBusy = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp)
    ) {
        SubHeader(
            stringResourceCompat(R.string.checks_title),
            onBack = { nav.popBackStack() }
        )

        // ══ [P29-TOOLS] أدوات الشيكات داخل بطاقة موحدة بنمط شاشة الديون () —
        // الأربعة نفسها بوظائفها الحرفية (أرشفة المسدد/تصدير التقويم/مشاركة/شيك جديد) ══
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                QuickAction(
                    Icons.Rounded.Inventory2, stringResourceCompat(R.string.tool_chk_archive),
                    Brush.linearGradient(listOf(Amber, RedDeep))
                ) {
                    // أرشفة كل الشيكات المسددة — Toast بعدد ما أُرشف فعلاً
                    vm.archiveSettled { n ->
                        android.widget.Toast.makeText(
                            activity,
                            activity.getString(
                                if (n > 0) R.string.checks_archived_n else R.string.checks_archive_none,
                                n
                            ),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                QuickAction(
                    Icons.Rounded.Event, stringResourceCompat(R.string.tool_chk_ics_save),
                    Brush.linearGradient(listOf(Cyan, Blue))
                ) { exportIcs(save = true) }
                QuickAction(
                    Icons.Rounded.Share, stringResourceCompat(R.string.tool_chk_ics_share),
                    Brush.linearGradient(listOf(Green, Cyan))
                ) { exportIcs(save = false) }
                QuickAction(
                    Icons.Rounded.Add, stringResourceCompat(R.string.tool_chk_new),
                    Brush.linearGradient(listOf(VioDeep, Vio))
                ) { showAdd = true }
            }
        }

        // [P20-FIX agent2]: 6 رقائق في صف غير قابل للتمرير — العربية تفيض عن العرض الضيق
        // فتُقص آخر رقاقة (الأرشيف) — نفس نمط صف الفرز القابل للتمرير في DebtsScreen
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            FilterPill(stringResourceCompat(R.string.all), filter == -1) { vm.statusFilter.value = -1 }
            FilterPill(stringResourceCompat(R.string.st_pending), filter == 0) { vm.statusFilter.value = 0 }
            FilterPill(stringResourceCompat(R.string.st_deposited), filter == 1) { vm.statusFilter.value = 1 }
            FilterPill(stringResourceCompat(R.string.st_cleared), filter == 2) { vm.statusFilter.value = 2 }
            FilterPill(stringResourceCompat(R.string.st_bounced), filter == 3) { vm.statusFilter.value = 3 }
            // رقاقة الأرشيف (بديل R2) — عرض المؤرشفة فقط مع إمكانية الاستعادة
            FilterPill(stringResourceCompat(R.string.checks_archive_view), archiveMode) {
                vm.statusFilter.value = if (archiveMode) -1 else vm.FILTER_ARCHIVE
            }
        }
        Spacer(Modifier.height(8.dp))
        // شارة «شيكات هذا الأسبوع» (وظيفة 25) — عدّاد ملون (المتأخر أحمر) والنقر يُرشِّح القائمة في VM
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionPill(
                stringResourceCompat(R.string.checks_week_chip, week.first),
                if (weekActive) Vio else Vio.copy(alpha = 0.55f)
            ) { vm.weekOnly.value = !weekActive }
            if (week.second > 0) {
                ActionPill(
                    stringResourceCompat(R.string.checks_week_overdue, week.second),
                    RedDeep
                ) { vm.weekOnly.value = !weekActive }
            }
        }
        Spacer(Modifier.height(10.dp))

        // [P23-FIX] بطاقات الذكاء السبع كانت هنا بين الرقائق والقائمة — نفس شكوى «تشوه المنظر»
        // (نمط P20 في الرئيسية والديون وP23 في التقارير). نُقلت إلى نهاية قائمة الشيكات.

        // ══ [P24-HERO] إحصاء الشيكات النشطة الحقيقي (بلا المؤرشفة والملغاة) — لا يتأثر بالرقائق ══
        val activeChecks = allChecks.filter { it.id !in archivedIds && it.status != 4 }
        val incPending = activeChecks.filter { it.direction == 0 && it.status == 0 }
        val chkIncPendingSum = incPending.sumOf { it.amount }
        val chkOutPendingSum = activeChecks.filter { it.direction == 1 && it.status == 0 }.sumOf { it.amount }
        val chkDepositedSum = activeChecks.filter { it.status == 1 }.sumOf { it.amount }
        val chkClearedSum = activeChecks.filter { it.status == 2 }.sumOf { it.amount }
        val chkBouncedSum = activeChecks.filter { it.status == 3 }.sumOf { it.amount }

        if (checks.isEmpty()) {
            EmptyState(stringResourceCompat(R.string.empty_generic), Icons.Rounded.Badge)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // ══ [P24-HERO] بطاقة الهيرو + شبكة KPI ‏2×2 بنمط الشاشة الرئيسية — أول القائمة ══
                item(key = "chk-hero") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        HeroCard(
                            Icons.Rounded.Badge,
                            // [P33-P8] مجاميع الشيكات قروش Long (CheckEntity.amount)
                            Money.formatP(chkIncPendingSum, symbol),
                            stringResourceCompat(R.string.hero_chk_hint, incPending.size)
                        )
                        KpiGrid(
                            listOf(
                                KpiCell(
                                    Icons.Rounded.CallMade,
                                    // [P33-P8] قروش Long
                                    Money.formatP(chkOutPendingSum, symbol),
                                    stringResourceCompat(R.string.chk_kpi_outgoing), Amber
                                ),
                                KpiCell(
                                    Icons.Rounded.AccountBalance,
                                    Money.formatP(chkDepositedSum, symbol),
                                    stringResourceCompat(R.string.chk_kpi_deposited), Blue
                                ),
                                KpiCell(
                                    Icons.Rounded.TaskAlt,
                                    Money.formatP(chkClearedSum, symbol),
                                    stringResourceCompat(R.string.chk_kpi_cleared), GreenDeep
                                ),
                                KpiCell(
                                    Icons.Rounded.Undo,
                                    Money.formatP(chkBouncedSum, symbol),
                                    stringResourceCompat(R.string.chk_kpi_bounced), RedDeep
                                )
                            )
                        )
                    }
                }
                items(checks, key = { it.id }) { c ->
                    CheckCard(c, vm, symbol, settings.weekendFriSat, archived = archiveMode, riskPct = riskById[c.id])
                }
                // ══ [P23-FIX] بطاقات الذكاء — بعد قائمة الشيكات ══
                // تغطية الشيكات الواردة للمستحقات الصادرة (30 يوماً)
                item(key = "chk-insights-hdr") { SectionLabel(stringResourceCompat(R.string.insights_section_title)) }
                // [P44-K1] جولة 5: المكدس المخصص — ترتيب/إظهار المجموعات السبع بافتضاض المستخدم
                item(key = "insights-stack-checks") {
                    com.superbiz.app.ui.insights.InsightsStack(
                        com.superbiz.app.domain.DashboardPrefsP44.SCREEN_CHECKS,
                        listOf(
                            // تغطية الشيكات الواردة للمستحقات الصادرة (30 يوماً)
                            com.superbiz.app.ui.insights.insightsGroup("r9") { com.superbiz.app.ui.insights.ChecksR9Card(r9VM, appVM) },
                            // سلّم الاستحقاق/تركّز البنوك
                            com.superbiz.app.ui.insights.insightsGroup("r10") { com.superbiz.app.ui.insights.ChecksR10Card(r10VM) },
                            // إحصاء الارتجاع/الفجوة النقدية 30 يوماً
                            com.superbiz.app.ui.insights.insightsGroup("r11") { com.superbiz.app.ui.insights.ChecksR11Card(appVM, r11VM) },
                            // مدفوعات يتيمة تشير لفواتير مفقودة
                            com.superbiz.app.ui.insights.insightsGroup("r12") { com.superbiz.app.ui.insights.ChecksR12Card(appVM, r12VM) },
                            com.superbiz.app.ui.insights.insightsGroup("r13") { com.superbiz.app.ui.insights.ChecksR13Card(appVM, r13VM) },
                            com.superbiz.app.ui.insights.insightsGroup("r14") { com.superbiz.app.ui.insights.ChecksR14Card(r14VM) },
                            com.superbiz.app.ui.insights.insightsGroup("r15") { com.superbiz.app.ui.insights.ChecksR15Card(appVM, r15VM) },
                        )
                    )
                }
            }
        }
    }

    if (showAdd) {
        CheckEditor(vm) { showAdd = false }
    }
}

@Composable
private fun CheckCard(c: CheckEntity, vm: ChecksVM, symbol: String, weekendFriSat: Boolean, archived: Boolean = false, riskPct: Int? = null) {
    val g = glassColors()
    var partyName by remember { mutableStateOf("…") }
    androidx.compose.runtime.LaunchedEffect(c.id) { partyName = vm.partyName(c.partyId) }
    // [P6-M48 إصلاح] حذف فردي بتأكيد — كانت vm.delete بلا أي مستدعٍ في الواجهة إطلاقاً
    var confirmDelete by remember { mutableStateOf(false) }

    val statusColor = when (c.status) {
        2 -> GreenDeep; 3 -> RedDeep; 1 -> Amber; else -> Vio
    }
    val statusLabel = stringResourceCompat(
        when (c.status) {
            2 -> R.string.st_cleared; 3 -> R.string.st_bounced; 1 -> R.string.st_deposited
            4 -> R.string.st_cancelled; else -> R.string.st_pending
        }
    )

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconChip(
                    Icons.Rounded.Badge,
                    androidx.compose.ui.graphics.Color.White,
                    statusColor, size = 38.dp, iconSize = 17.dp
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("#" + c.number, fontWeight = FontWeight.Bold, color = g.textPrimary)
                    // شارة مخاطرة الارتجاع
                    if (riskPct != null && riskPct >= 25) com.superbiz.app.ui.insights.CheckRiskChip(riskPct)
                    Text(
                        partyName + if (c.bank.isNotBlank()) " • " + c.bank else "",
                        fontSize = 12.sp, color = g.textSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    // [P33-P8] مبلغ الشيك قروش Long
                    Text(Money.formatP(c.amount, symbol), fontWeight = FontWeight.Bold, color = g.textPrimary)
                    RiskBadge(statusLabel, statusColor)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResourceCompat(if (c.direction == 0) R.string.check_incoming else R.string.check_outgoing) +
                    " • " + Dates.short(c.dueDate),
                fontSize = 11.sp, color = g.textSecondary
            )
            // أيام العمل المتبقية للاستحقاق — TimeMath.businessDaysBetween بعطلة الإعدادات
            if (c.status == 0 || c.status == 1) {
                val weekend = if (weekendFriSat) {
                    com.superbiz.app.domain.algo.TimeMath.KSA_WEEKEND
                } else setOf(java.util.Calendar.SUNDAY)
                val bd = try {
                    com.superbiz.app.domain.algo.TimeMath.businessDaysBetween(
                        System.currentTimeMillis(), c.dueDate, weekend
                    )
                } catch (e: Exception) { 0 }
                Text(
                    if (bd >= 0) stringResourceCompat(R.string.check_bdays_left, bd.toString())
                    else stringResourceCompat(R.string.check_bdays_over, (-bd).toString()),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (bd < 0) RedDeep else if (bd <= 3) Amber else GreenDeep
                )
            }
            if (archived) {
                // في عرض الأرشيف — استعادة الشيك إلى القائمة النشطة بدل أزرار الحالة
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionPill(stringResourceCompat(R.string.checks_restore), Vio) { vm.restore(c.id) }
                    // [P6-M48 إصلاح] حذف فردي متاح في الأرشيف كذلك (بتأكيد)
                    ActionPill(stringResourceCompat(R.string.delete), RedDeep) { confirmDelete = true }
                }
            } else if (c.status == 0 || c.status == 1) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionPill(stringResourceCompat(R.string.st_deposited), Amber) { vm.setStatus(c, 1) }
                    ActionPill(stringResourceCompat(R.string.st_cleared), GreenDeep) { vm.setStatus(c, 2) }
                    ActionPill(stringResourceCompat(R.string.st_bounced), RedDeep) { vm.setStatus(c, 3) }
                    // [P6-M48 إصلاح] حذف فردي — المستودع يدعمه (معاملة تُلغي القيد وتحذف الدفعات)
                    ActionPill(stringResourceCompat(R.string.delete), RedDeep) { confirmDelete = true }
                }
            }
        }
    }

    // [P6-M48 إصلاح] حوار تأكيد الحذف الفردي — إجراء مدمّر لا يُنفّذ مباشرة
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = g.surfaceStrong,
            title = { Text(stringResourceCompat(R.string.confirm), color = g.textPrimary) },
            text = { Text(stringResourceCompat(R.string.check_delete_confirm), color = g.textPrimary) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmDelete = false
                    vm.delete(c.id)
                }) {
                    Text(stringResourceCompat(R.string.confirm_yes), color = RedDeep, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResourceCompat(R.string.confirm_no), color = g.textSecondary)
                }
            }
        )
    }
}

// ═════════ [P36-M4-5] منتقي التاريخ الموحد M3 ═════════
/**
 * منتقي التاريخ الموحد على Material 3 — نفس النمط القياسي لـ StDatePicker في كشف الحساب
 * (material3 DatePickerDialog + rememberDatePickerState) مع تطبيع منطقة زمنية مشترك مع
 * InstallmentsScreen: القيمة الواردة/الصادرة = ظهر اليوم نفسه بالتوقيت المحلي — يرث عقد
 * dueFromFields القديم المقاوم لحواف التوقيت الصيفي، والقيمة الواردة تُعرض على اليوم الصحيح
 * حتى في المناطق عالية الإزاحة (مثل UTC+13).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class) // DatePickerDialog تجريبي
@Composable
fun BizDatePickerDialog(initial: Long, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = PickerDates.toPickerMillis(initial))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(PickerDates.fromPickerMillis(it)) }
                onDismiss()
            }) { Text(stringResourceCompat(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResourceCompat(R.string.cancel)) }
        }
    ) {
        DatePicker(state = state)
    }
}

/** [P36-M4-5] رياضيات تاريخ المنتقي النقية — مُختبَرة وحدةً (M4PickerDatesTest) بلا تبعيات Compose */
object PickerDates {

    /** طابع زمني محلي بأي وقت → ملّي ثانية منتصف النهار UTC لنفس التاريخ (صيغة منتقي M3) */
    fun toPickerMillis(ts: Long): Long {
        val local = java.util.Calendar.getInstance()
        local.timeInMillis = ts
        val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        utc.clear()
        utc.set(
            local.get(java.util.Calendar.YEAR),
            local.get(java.util.Calendar.MONTH),
            local.get(java.util.Calendar.DAY_OF_MONTH),
            0, 0, 0
        )
        return utc.timeInMillis
    }

    /** ملّي ثانية منتقي M3 (منتصف نهار UTC) → ظهر اليوم نفسه بالتوقيت المحلي (مقاوم لحواف التوقيت الصيفي) */
    fun fromPickerMillis(utcMillis: Long): Long {
        val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        utc.timeInMillis = utcMillis
        val local = java.util.Calendar.getInstance()
        local.clear()
        local.set(
            utc.get(java.util.Calendar.YEAR),
            utc.get(java.util.Calendar.MONTH),
            utc.get(java.util.Calendar.DAY_OF_MONTH),
            12, 0, 0
        )
        return local.timeInMillis
    }
}

@Composable
private fun CheckEditor(vm: ChecksVM, onDismiss: () -> Unit) {
    val g = glassColors()
    val parties by vm.parties.collectAsState()
    var number by remember { mutableStateOf("") }
    var bank by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var party by remember { mutableStateOf<com.superbiz.app.data.db.Party?>(null) }
    var direction by remember { mutableStateOf(0) }
    var due by remember { mutableStateOf(System.currentTimeMillis() + 30L * 86_400_000L) }

    // [P36-M4-5] إدخال تاريخ موحّد M3: رقاقة فتح المنتقي بدل حقول يوم/شهر/سنة اليدوية —
    // القيمة تبقى ظهر اليوم المحلي (عقد dueFromFields القديم المقاوم لحواف التوقيت الصيفي)
    var showDuePicker by remember { mutableStateOf(false) }
    // [P6-M48 إصلاح] رسالة خطأ داخل المحرر — كان الحفظ يفشل صامتاً (طرف/مبلغ/رقم/تاريخ)
    var errRes by remember { mutableStateOf<Int?>(null) }

    val defaultDue = System.currentTimeMillis() + 30L * 86_400_000L

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = g.surfaceStrong,
        title = { Text(stringResourceCompat(R.string.qa_check), color = g.textPrimary) },
        text = {
            // حماية حقول الحوار من تغطية لوحة المفاتيح
            Column(
                Modifier.imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BizField(number, { number = it }, stringResourceCompat(R.string.check_number))
                BizField(bank, { bank = it }, stringResourceCompat(R.string.bank))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill(stringResourceCompat(R.string.check_incoming), direction == 0) { direction = 0 }
                    FilterPill(stringResourceCompat(R.string.check_outgoing), direction == 1) { direction = 1 }
                }
                Text(
                    party?.name ?: stringResourceCompat(R.string.party) + " ▾",
                    color = g.textPrimary, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth()
                )
                Column(
                    Modifier.height(120.dp)
                ) {
                    LazyColumn {
                        items(parties, key = { it.id }) { p ->
                            Text(
                                p.name,
                                color = if (party?.id == p.id) g.accent else g.textPrimary,
                                fontWeight = if (party?.id == p.id) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { party = p }
                                    .padding(vertical = 6.dp)
                            )
                        }
                    }
                }
                BizField(amount, { amount = it }, stringResourceCompat(R.string.amount), keyboard = numberFieldOptions())
                // [P36-M4-5] حقل التاريخ: تسمية + رقاقة منتقي M3 الموحّد مع معاينة الاستحقاق الحية
                Text(
                    stringResourceCompat(R.string.check_due_label),
                    fontSize = 12.sp, color = g.textSecondary, fontWeight = FontWeight.SemiBold
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ActionPill(stringResourceCompat(R.string.p36_pick_date), Vio) { showDuePicker = true }
                    Text(Dates.short(due), color = g.textSecondary, fontSize = 12.sp)
                }
                // [P6-M48 إصلاح] الرقائق أصبحت مطلقة من بداية اليوم (لا تراكمية): +30 بعد +15
                // تعطي 30 يوماً من اليوم لا 45، والنقر على الرقاقة النشطة يعيد الافتراضي (بعد 30 يوماً)
                // [P36-M4-5] رقائق +N السريعة كما هي — سقط فقط تأثير مزامنة حقول النص في applyDue
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val todayBase = com.superbiz.app.domain.algo.TimeMath.startOfDay(System.currentTimeMillis())
                    listOf(15L, 30L, 60L).forEach { d ->
                        val target = todayBase + d * 86_400_000L
                        FilterPill("+$d", due == target) {
                            due = if (due == target) defaultDue else target
                        }
                    }
                }
                // [P6-M48 إصلاح] عرض خطأ الإدخال بدل فشل صامت
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
                val p = party
                val v = parseNum(amount)
                // [P6-M48 إصلاح] تاريخ الإصدار بداية اليوم كي يقبل منتقي التاريخ استحقاق اليوم نفسه،
                // والتحقق كامل قبل الحفظ مع رسالة داخل المحرر — كان الفشل صامتاً
                val issue = com.superbiz.app.domain.algo.TimeMath.startOfDay(System.currentTimeMillis())
                val e = when {
                    p == null -> R.string.err_party_required
                    number.isBlank() -> R.string.err_check_number_blank
                    v <= 0.0 -> R.string.err_amount_positive
                    due < issue -> R.string.err_check_due_before_issue
                    else -> null
                }
                errRes = e
                if (e == null && p != null) {
                    vm.save(number, p, bank, v, issue, due, direction)
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

    // [P36-M4-5] منتقي التاريخ الموحد M3 — يُركَّب خارج حوار المحرر (نمط StDatePicker)
    if (showDuePicker) {
        BizDatePickerDialog(
            initial = due,
            onPick = { due = it },
            onDismiss = { showDuePicker = false }
        )
    }
}
