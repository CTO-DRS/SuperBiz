package com.superbiz.app.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
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
import com.superbiz.app.R
import com.superbiz.app.data.db.Installment
import com.superbiz.app.data.db.InstallmentPlan
import com.superbiz.app.domain.EarlyPay
import com.superbiz.app.domain.InstallmentEngine
import com.superbiz.app.ui.components.Badge
import com.superbiz.app.ui.components.BizField
import com.superbiz.app.ui.components.EmptyState
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.components.HeroCard
import com.superbiz.app.ui.components.IconChip
import com.superbiz.app.ui.components.KpiCell
import com.superbiz.app.ui.components.KpiGrid
import com.superbiz.app.ui.components.SubHeader
import com.superbiz.app.ui.components.numberFieldOptions
import com.superbiz.app.ui.components.parseNum
import com.superbiz.app.ui.theme.Amber
import com.superbiz.app.ui.theme.Blue
import com.superbiz.app.ui.theme.Cyan
import com.superbiz.app.ui.theme.GreenDeep
import com.superbiz.app.ui.theme.RedDeep
import com.superbiz.app.ui.theme.Vio
import com.superbiz.app.ui.theme.VioDeep
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.util.Dates
import com.superbiz.app.util.Money
import com.superbiz.app.util.startIntentSafe
import com.superbiz.app.vm.AppVM
import com.superbiz.app.vm.InstallmentsVM
import com.superbiz.app.vm.InstallmentsVM.PlanUi
import com.superbiz.app.VMFactory
import kotlinx.coroutines.launch

@Composable
fun InstallmentsScreen(appVM: AppVM, nav: NavHostController) {
    val activity = LocalContext.current as? com.superbiz.app.MainActivity ?: return
    val vm: InstallmentsVM = viewModel(factory = remember { VMFactory(activity) })
    val cards by vm.cards.collectAsState()
    val totals by vm.totals.collectAsState()
    // [P24-HERO] الخطط الحقيقية لبطاقات الرئيسية (عدد النشطة + إجمالي الاتفاقات)
    val plansList by vm.plans.collectAsState()
    val plansActive = plansList.count { !it.archived }
    val agreementsTotal = plansList.filter { !it.archived }.sumOf { it.total }
    val filter by vm.statusFilter.collectAsState()
    val symbol by appVM.symbol.collectAsState()
    val g = glassColors()

    var showAdd by remember { mutableStateOf(false) }
    var detailFor by remember { mutableStateOf<PlanUi?>(null) }
    var shareFor by remember { mutableStateOf<PlanUi?>(null) }
    // عارض جدول الإطفاء (وظيفة 26) + حوار التأجيل (وظيفة 28)
    var amortFor by remember { mutableStateOf<PlanUi?>(null) }
    var postponeFor by remember { mutableStateOf<Installment?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            // [P20-FIX agent2]: كانت ترويسة النظام تغطي أزرار آخر بطاقة (edge-to-edge والشاشات
            // الشقيقة تضيف navigationBarsPadding — ChecksScreen:204)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp)
    ) {
        SubHeader(
            stringResourceCompat(R.string.installments_title),
            onBack = { nav.popBackStack() }
        ) {
            TextButton(onClick = { showAdd = true }) {
                Text("+ " + stringResourceCompat(R.string.add), color = g.accent, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterPill(stringResourceCompat(R.string.all), filter == -1) { vm.statusFilter.value = -1 }
            FilterPill(stringResourceCompat(R.string.plan_dir_in), filter == 0) { vm.statusFilter.value = 0 }
            FilterPill(stringResourceCompat(R.string.plan_dir_out), filter == 1) { vm.statusFilter.value = 1 }
            FilterPill(stringResourceCompat(R.string.st_late), filter == 2) { vm.statusFilter.value = 2 }
            FilterPill(stringResourceCompat(R.string.plan_done), filter == 3) { vm.statusFilter.value = 3 }
        }
        Spacer(Modifier.height(10.dp))

        if (cards.isEmpty()) {
            EmptyState(stringResourceCompat(R.string.plan_none), Icons.Rounded.CalendarMonth)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // ══ [P24-HERO] بطاقة الهيرو + شبكة KPI ‏2×2 بنمط الرئيسية — أُلغيت بدلها
                // شريط MiniStat الثابت البسيط (إحصاءات نفسها بلغة تصميم احترافية تُمرّ مع القائمة) ══
                item(key = "inst-hero") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        HeroCard(
                            Icons.Rounded.CalendarMonth,
                            Money.formatP(totals.first, symbol),  // [P33-P8] قروش
                            stringResourceCompat(R.string.hero_inst_hint, plansActive)
                        )
                        KpiGrid(
                            listOf(
                                KpiCell(
                                    Icons.Rounded.Warning,
                                    Money.formatP(totals.second, symbol),  // [P33-P8] قروش
                                    stringResourceCompat(R.string.inst_late_amount), RedDeep
                                ),
                                KpiCell(
                                    Icons.Rounded.EventBusy,
                                    totals.third.toString(),
                                    stringResourceCompat(R.string.inst_late_count), Amber
                                ),
                                KpiCell(
                                    Icons.Rounded.Handshake,
                                    plansActive.toString(),
                                    stringResourceCompat(R.string.inst_kpi_active), Cyan
                                ),
                                KpiCell(
                                    Icons.Rounded.AccountBalanceWallet,
                                    // [P33-P8] مجموع اتفاقات الخطط قروش Long — عرض عبر formatP
                                    Money.formatP(agreementsTotal, symbol),
                                    stringResourceCompat(R.string.inst_kpi_agreements), Vio
                                )
                            )
                        )
                    }
                }
                items(cards, key = { it.plan.id }) { ui ->
                    PlanCard(
                        ui = ui, vm = vm, symbol = symbol,
                        onDetail = { detailFor = ui },
                        onShare = { shareFor = ui },
                        // وظيفة 26 — الزر يُخفى بصدق بلا بيانات كافية
                        onAmort = { amortFor = ui }
                    )
                }
            }
        }
    }

    if (showAdd) {
        PlanEditor(vm) { showAdd = false }
    }
    detailFor?.let { ui ->
        DetailDialog(vm, ui, symbol, onPostpone = { postponeFor = it }) { detailFor = null }
    }
    shareFor?.let { ui ->
        ShareDialog(vm, ui, symbol) { shareFor = null }
    }
    // وظيفة 26 — جدول الإطفاء من MoneyMath.amortize القائمة
    amortFor?.let { ui ->
        AmortDialog(ui, symbol) { amortFor = null }
    }
    // وظيفة 28 — تأجيل قسط متأخر
    postponeFor?.let { inst ->
        PostponeDialog(vm, inst) { postponeFor = null }
    }
}

// ═════════ بطاقة خطة ═════════
@Composable
private fun PlanCard(
    ui: PlanUi, vm: InstallmentsVM, symbol: String,
    onDetail: () -> Unit, onShare: () -> Unit,
    onAmort: () -> Unit = {}
) {
    val g = glassColors()
    val plan = ui.plan
    val st = ui.stats
    var partyName by remember { mutableStateOf("…") }
    LaunchedEffect(plan.id) { partyName = vm.partyName(plan.partyId) }

    val accent = when {
        st.done -> GreenDeep
        st.lateCount > 0 -> RedDeep
        else -> Vio
    }

    // [P6-M44 إصلاح] حالة تأكيد السداد + علم التنفيذ — التعطيل يتحرر حين تصل بيانات الخطة
    // المحدثة (reload في VM يعيد إصدار cards) مع قاطع أمان زمني إن فشل السداد
    var confirmPay by remember { mutableStateOf(false) }
    var paying by remember { mutableStateOf(false) }
    LaunchedEffect(ui.stats) { paying = false }
    LaunchedEffect(paying) {
        if (paying) {
            kotlinx.coroutines.delay(6000)
            paying = false
        }
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconChip(
                    Icons.Rounded.CalendarMonth, Color.White, accent,
                    size = 38.dp, iconSize = 17.dp
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        plan.title, fontWeight = FontWeight.Bold, color = g.textPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        partyName + " • " + stringResourceCompat(
                            if (plan.direction == 0) R.string.plan_dir_in else R.string.plan_dir_out
                        ),
                        fontSize = 12.sp, color = g.textSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        // [P33-P8] إجمالي الخطة قروش Long
                        Money.formatP(plan.total, symbol),
                        fontWeight = FontWeight.Bold, color = g.textPrimary
                    )
                    if (st.lateCount > 0) {
                        Badge(
                            stringResourceCompat(R.string.st_late) + " ×${st.lateCount}", RedDeep
                        )
                    } else if (st.done) {
                        Badge(stringResourceCompat(R.string.plan_done), GreenDeep)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            GlassProgress(st.progress.toFloat(), accent)
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // [P33-P8] المتبقي قروش Long (PlanStats)
                    stringResourceCompat(R.string.inst_remaining) + ": " +
                        Money.formatP(st.remaining, symbol),
                    fontSize = 12.sp, color = g.textSecondary, modifier = Modifier.weight(1f)
                )
                st.nextDue?.let { nx ->
                    Text(
                        // [P33-P8] قسط الجدول قروش Long (ScheduleItem.amount)
                        stringResourceCompat(R.string.inst_next) + " #${nx.seq}: " +
                            Money.numP(nx.amount) + " • " + Dates.short(nx.dueDate),
                        fontSize = 11.sp, color = if (st.lateCount > 0) RedDeep else g.textSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!st.done) {
                    // [P6-M44 إصلاح] السداد كان ينفّذ فوراً بلا تأكيد — الآن حوار تأكيد + تعطيل الزر أثناء التنفيذ
                    if (paying) {
                        // الزر معطل أثناء التنفيذ — مظهر الرقاقة نفسها بلا نقرة
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(GreenDeep.copy(alpha = 0.08f))
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Text(
                                stringResourceCompat(R.string.inst_paying),
                                color = GreenDeep.copy(alpha = 0.55f), fontSize = 12.sp,
                                fontWeight = FontWeight.Bold, maxLines = 1
                            )
                        }
                    } else {
                        ActionPill(stringResourceCompat(R.string.inst_pay_next), GreenDeep) {
                            confirmPay = true
                        }
                    }
                }
                ActionPill(stringResourceCompat(R.string.inst_details), VioDeep) { onDetail() } // [P36-M4-1] enabled أصبح باراميتراً افتراضياً قبل onClick — الإجراء يمر trailing
                ActionPill(stringResourceCompat(R.string.inst_share_remind), Blue) { onShare() } // [P36-M4-1]
                // وظيفة 26 — جدول الإطفاء؛ يُخفى بصدق بلا بيانات كافية (مُجدَّل ≤ 0 أو أشهر < 1)
                // [P33-P8] العتبة العشرية 0.004 صارت مساواة صحيحة: المُجدَّل قروش Long
                if (plan.financed > 0L && plan.months >= 1) {
                    ActionPill(stringResourceCompat(R.string.inst_amort), Cyan) { onAmort() } // [P36-M4-1]
                }
            }
        }
    }

    // [P6-M44 إصلاح] حوار تأكيد سداد القسط التالي — نفس نمط حوارات التأكيد في المشروع
    if (confirmPay) {
        AlertDialog(
            onDismissRequest = { confirmPay = false },
            containerColor = g.surfaceStrong,
            title = { Text(stringResourceCompat(R.string.confirm), color = g.textPrimary) },
            text = { Text(stringResourceCompat(R.string.inst_pay_confirm), color = g.textPrimary) },
            confirmButton = {
                TextButton(onClick = {
                    confirmPay = false
                    paying = true
                    vm.payNext(plan.id)
                }) {
                    Text(stringResourceCompat(R.string.confirm_yes), color = g.accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmPay = false }) {
                    Text(stringResourceCompat(R.string.confirm_no), color = g.textSecondary)
                }
            }
        )
    }
}

/** سداد القسط التالي من بطاقة الخطة — يُنفَّذ عبر الكوروتين الخاص بالـ VM */

// ═════════ شريط التقدم الزجاجي ═════════
@Composable
fun GlassProgress(fraction: Float, accent: Color) {
    val g = glassColors()
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(g.surfaceStrong)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(8.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Brush.linearGradient(listOf(accent, Cyan)))
        )
    }
}

// ═════════ رقاقة إحصاء مصغرة ═════════
@Composable
private fun MiniStat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    val g = glassColors()
    GlassCard(modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, fontSize = 11.sp, color = g.textSecondary, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        }
    }
}

// ═════════ نافذة تفاصيل الجدول ═════════
@Composable
private fun DetailDialog(
    vm: InstallmentsVM, ui: PlanUi, symbol: String,
    onPostpone: (Installment) -> Unit = {},
    onDismiss: () -> Unit
) {
    val g = glassColors()
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf(listOf<Installment>()) }
    // إعدادات الغرامة تُحمَّل مرة واحدة للنافذة — لا استدعاء معلّق داخل الصفوف
    var lateSettings by remember { mutableStateOf<com.superbiz.app.data.repo.Settings?>(null) }
    // قراءة قاعدة البيانات محمية — أي فشل يُظهر قائمة فارغة بدل إغلاق التطبيق
    val refresh = {
        scope.launch {
            rows = runCatching { vm.installmentsOf(ui.plan.id) }.getOrDefault(emptyList())
        }
        Unit
    }
    LaunchedEffect(ui.plan.id) {
        rows = runCatching { vm.installmentsOf(ui.plan.id) }.getOrDefault(emptyList())
        lateSettings = try {
            com.superbiz.app.AppGraph.from(vm.appContext).settings.snapshot()
        } catch (e: Exception) { null }
    }

    // [P6-M44 إصلاح] سداد القسط كان ينفّذ فوراً بضغطة واحدة — تأكيد قبل التنفيذ
    // + تعطيل زر الصف الجاري سداده حتى ينعكس السداد على البيانات (بحد أقصى 4 ثوانٍ)
    var payConfirmFor by remember { mutableStateOf<Installment?>(null) }
    var payingId by remember { mutableStateOf<Long?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = g.surfaceStrong,
        title = {
            Column {
                Text(ui.plan.title, color = g.textPrimary)
                Text(
                    stringResourceCompat(R.string.inst_progress) + " " +
                        (ui.stats.progress * 100).toInt() + "%",
                    fontSize = 12.sp, color = g.textSecondary
                )
            }
        },
        text = {
            Column(Modifier.height(340.dp)) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(rows, key = { it.id }) { inst ->
                        val st = InstallmentEngine.statusOf(
                            inst.amount, inst.paidAmount, inst.dueDate,
                            System.currentTimeMillis()
                        )
                        // غرامة تأخير تقديرية + مستوى تصعيد — من الإعدادات وخوارزميتي MoneyMath/TimeMath
                        // [P20-FIX agent2]: daysOverdue يُقرّب لأعلى (M-2.5) — floor كان يعطي 0 يوم
                        // لتأخير ساعات فيعطي غرامة صفر مع شارة «متأخر» حمراء
                        val ls = lateSettings
                        val lateInfo = if (st == InstallmentEngine.St.LATE && ls != null && ls.lateFeeDailyPct > 0) {
                            // [P33-P8] المفتوح قروش Long (كان coerceAtLeast(0.0))
                            val remaining = (inst.amount - inst.paidAmount).coerceAtLeast(0L)
                            val daysLate = com.superbiz.app.util.Dates.daysOverdue(inst.dueDate).coerceAtLeast(0)
                            Triple(
                                // [P33-P8] lateFee خوارزمية قديمة بواجهة ريال Double — التحويل عند الحد فقط
                                com.superbiz.app.domain.algo.lateFee(Money.fromPiasters(remaining), daysLate, ls.lateFeeDailyPct, ls.lateFeeCapPct),
                                daysLate,
                                com.superbiz.app.domain.algo.TimeMath.escalationLevel(daysLate)
                            )
                        } else null
                        val (label, color) = when (st) {
                            InstallmentEngine.St.PAID -> stringResourceCompat(R.string.st_paid) to GreenDeep
                            InstallmentEngine.St.LATE -> stringResourceCompat(R.string.st_late) to RedDeep
                            InstallmentEngine.St.PARTIAL -> stringResourceCompat(R.string.st_partial) to Amber
                            else -> stringResourceCompat(R.string.st_due) to Blue
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    // [P33-P8] مبلغ القسط قروش Long
                                    "#${inst.seq} • ${Money.numP(inst.amount)} $symbol",
                                    fontWeight = FontWeight.Bold,
                                    color = g.textPrimary, fontSize = 14.sp
                                )
                                Text(
                                    Dates.short(inst.dueDate) +
                                        // [P33-P8] المدفوع قروش Long
                                        if (inst.paidAmount > 0) " • " + Money.numP(inst.paidAmount) else "",
                                    fontSize = 11.sp, color = g.textSecondary
                                )
                                // سطر الغرامة التقديرية للمتأخر — يوضح أثر التأخير قبل الدفع
                                if (lateInfo != null && lateInfo.first > 0) {
                                    Text(
                                        stringResourceCompat(R.string.inst_late_fee, Money.num(lateInfo.first), lateInfo.second.toString(), lateInfo.third.toString()),
                                        fontSize = 11.sp, color = RedDeep, fontWeight = FontWeight.SemiBold
                                    )
                                }
                                // وظيفة 27 — شريط السداد المبكر بمعدل ثابت معلن (EarlyPay.RATE_PCT = 2%)
                                // لا يوجد مفتاح DataStore مناسب والملف المالك للإعدادات لموجة أخرى — القرار موثق في الكود
                                // [P33-P8] المفتوح قروش Long — وEarlyPay خوارزمية قديمة بواجهة ريال Double
                                // (لم تُرحَّل بعد) فالتحويل عند الحد عبر fromPiasters حصراً
                                val openAmt = inst.amount - inst.paidAmount
                                val early = if (EarlyPay.eligible(
                                        inst.amount, inst.paidAmount,
                                        inst.dueDate, System.currentTimeMillis()
                                    ))
                                    EarlyPay.quote(openAmt) else null  // [P33-P8] EarlyPay قروش أصلاً
                                if (early != null) {
                                    Text(
                                        stringResourceCompat(
                                            R.string.inst_early_title,
                                            Money.numP(early.first), Money.numP(openAmt)
                                        ),
                                        fontSize = 11.sp, color = GreenDeep, fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        stringResourceCompat(R.string.inst_early_note),
                                        fontSize = 10.sp, color = g.textSecondary
                                    )
                                    ActionPill(stringResourceCompat(R.string.inst_early_now), GreenDeep) {
                                        // المسار المخصص للتسوية المخفَّضة — كان يستدعي
                                        // pay() فيبقى المفتوح = قيمة الخصم والقسط عالقاً للأبد
                                        scope.launch { vm.payEarly(inst); refresh() }
                                    }
                                }
                            }
                            Badge(label, color)
                            if (st != InstallmentEngine.St.PAID) {
                                Spacer(Modifier.width(6.dp))
                                if (payingId == inst.id) {
                                    // [P6-M44 إصلاح] زر الصف معطل أثناء التنفيذ — مظهر الرقاقة بلا نقرة
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(GreenDeep.copy(alpha = 0.08f))
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            stringResourceCompat(R.string.inst_paying),
                                            color = GreenDeep.copy(alpha = 0.55f), fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                } else {
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(GreenDeep.copy(alpha = 0.16f))
                                            .clickable { payConfirmFor = inst }
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            stringResourceCompat(R.string.inst_pay_all),
                                            color = GreenDeep, fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                // وظيفة 28 — تأجيل القسط المتأخر فقط
                                if (st == InstallmentEngine.St.LATE) {
                                    Spacer(Modifier.width(6.dp))
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(Amber.copy(alpha = 0.16f))
                                            .clickable { onPostpone(inst) }
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            stringResourceCompat(R.string.inst_postpone),
                                            color = Amber, fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResourceCompat(R.string.close), color = g.accent, fontWeight = FontWeight.Bold)
            }
        }
    )

    // [P6-M44 إصلاح] حوار تأكيد سداد القسط المحدد — التنفيذ بعد الموافقة فقط،
    // والزر معطل حتى ينعكس السداد على الصف أو تنقضي مهلة الأمان
    payConfirmFor?.let { target ->
        AlertDialog(
            onDismissRequest = { payConfirmFor = null },
            containerColor = g.surfaceStrong,
            title = { Text(stringResourceCompat(R.string.confirm), color = g.textPrimary) },
            text = { Text(stringResourceCompat(R.string.inst_pay_inst_confirm, target.seq), color = g.textPrimary) },
            confirmButton = {
                TextButton(onClick = {
                    payConfirmFor = null
                    payingId = target.id
                    scope.launch {
                        val before = target.paidAmount
                        vm.pay(target)
                        val deadline = System.currentTimeMillis() + 4000
                        while (System.currentTimeMillis() < deadline) {
                            kotlinx.coroutines.delay(150)
                            val cur = runCatching { vm.installmentsOf(ui.plan.id) }
                                .getOrDefault(emptyList())
                                .firstOrNull { it.id == target.id }
                            if (cur == null || cur.paidAmount != before) break
                        }
                        refresh()
                        payingId = null
                    }
                }) {
                    Text(stringResourceCompat(R.string.confirm_yes), color = g.accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { payConfirmFor = null }) {
                    Text(stringResourceCompat(R.string.confirm_no), color = g.textSecondary)
                }
            }
        )
    }
}

// ═════════ : نافذة جدول الإطفاء (وظيفة 26) ═════════
// استهلاك حقيقي لخوارزمية MoneyMath.amortize القائمة (دفعة/فائدة/أصل/متبقي).
// الخطة مخزنة بلا فائدة — المعدل الافتراضي 0 يعرض أقساطاً متساوية من البنّاء نفسه،
// وحقل النسبة اختياري (0..100) لمعاينة جدول بفائدة دون أي تخزين.
@Composable
private fun AmortDialog(ui: PlanUi, symbol: String, onDismiss: () -> Unit) {
    val g = glassColors()
    var rateText by remember { mutableStateOf("0") }
    val financed = ui.plan.financed
    val months = ui.plan.months.coerceAtLeast(1)
    val rows = remember(financed, months, rateText) {
        try {
            val rate = parseNum(rateText).coerceIn(0.0, 100.0)
            // [P33-P8] amortize خوارزمية قديمة بواجهة ريال Double — المُجدَّل قروش يُحوَّل عند الحد
            com.superbiz.app.domain.algo.amortize(Money.fromPiasters(financed), rate, months)
        } catch (e: Exception) { emptyList<com.superbiz.app.domain.algo.AmortRow>() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = g.surfaceStrong,
        title = {
            Column {
                Text(stringResourceCompat(R.string.inst_amort), color = g.textPrimary)
                Text(
                    // [P33-P8] المُجدَّل قروش Long
                    ui.plan.title + " • " + Money.formatP(financed, symbol),
                    fontSize = 12.sp, color = g.textSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BizField(
                    rateText, { rateText = it },
                    stringResourceCompat(R.string.inst_amort_rate),
                    Modifier.fillMaxWidth(), numberFieldOptions()
                )
                if (rows.isEmpty()) {
                    Text(stringResourceCompat(R.string.inst_amort_empty), color = g.textSecondary)
                } else {
                    // ترويسة الأعمدة
                    Row(Modifier.fillMaxWidth()) {
                        Text("#", Modifier.weight(0.5f), fontSize = 11.sp, color = g.textSecondary, fontWeight = FontWeight.Bold)
                        Text(stringResourceCompat(R.string.inst_amort_pay), Modifier.weight(1.1f), fontSize = 11.sp, color = g.textSecondary, fontWeight = FontWeight.Bold)
                        Text(stringResourceCompat(R.string.inst_amort_int), Modifier.weight(1f), fontSize = 11.sp, color = g.textSecondary, fontWeight = FontWeight.Bold)
                        Text(stringResourceCompat(R.string.inst_amort_princ), Modifier.weight(1f), fontSize = 11.sp, color = g.textSecondary, fontWeight = FontWeight.Bold)
                        Text(stringResourceCompat(R.string.inst_amort_rem), Modifier.weight(1.2f), fontSize = 11.sp, color = g.textSecondary, fontWeight = FontWeight.Bold)
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.height(300.dp)) {
                        items(rows, key = { it.seq }) { r ->
                            Row(Modifier.fillMaxWidth()) {
                                Text("${r.seq}", Modifier.weight(0.5f), fontSize = 12.sp, color = g.textSecondary)
                                Text(Money.num(r.payment), Modifier.weight(1.1f), fontSize = 12.sp, color = g.textPrimary, fontWeight = FontWeight.SemiBold)
                                Text(Money.num(r.interest), Modifier.weight(1f), fontSize = 12.sp, color = RedDeep)
                                Text(Money.num(r.principalPart), Modifier.weight(1f), fontSize = 12.sp, color = GreenDeep)
                                Text(Money.num(r.remaining) + " " + symbol, Modifier.weight(1.2f), fontSize = 12.sp, color = g.textSecondary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResourceCompat(R.string.close), color = g.accent, fontWeight = FontWeight.Bold)
            }
        }
    )
}

// ═════════ : نافذة تأجيل القسط المتأخر (وظيفة 28) ═════════
// اختيار موعد جديد برقائق إزاحة (+7/+14/+30/+60 يوماً) أو [P36-M4-5] منتقي M3 الموحّد — لا يسبق اليوم أبداً،
// والتنفيذ عبر InstallmentRepo.reschedule الحقيقي داخل معاملة مع سطر تدقيق في سجل الأحداث.
@Composable
private fun PostponeDialog(vm: InstallmentsVM, inst: Installment, onDismiss: () -> Unit) {
    val g = glassColors()
    val context = LocalContext.current
    var offsetDays by remember { mutableStateOf(7L) }
    // [P36-M4-5] منتقي M3 الموحّد بجانب الرقائق السريعة — القيمة المختارة من التقويم تتقدم على الرقائق
    var pickedDate by remember { mutableStateOf<Long?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    val today = com.superbiz.app.domain.algo.TimeMath.startOfDay(System.currentTimeMillis())
    // [P36-M4-5] القيمة المُختارة (أو رقائق +N) — لا تسبق اليوم أبداً (عقد الحوار الأصلي)
    val newDate = (pickedDate ?: (today + offsetDays * 86_400_000L)).coerceAtLeast(today)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = g.surfaceStrong,
        title = {
            Text(stringResourceCompat(R.string.inst_postpone_title, inst.seq), color = g.textPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResourceCompat(R.string.inst_postpone_to, Dates.short(newDate)),
                    fontSize = 13.sp, color = g.textPrimary, fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResourceCompat(R.string.inst_postpone_note),
                    fontSize = 11.sp, color = g.textSecondary
                )
                // [P36-M4-5] رقائق +N السريعة كما هي — اختيار من التقويم يُصفّر توهّجها
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(7L, 14L, 30L, 60L).forEach { d ->
                        FilterPill("+$d", pickedDate == null && offsetDays == d) {
                            pickedDate = null
                            offsetDays = d
                        }
                    }
                }
                // [P36-M4-5] رقاقة فتح منتقي M3 الموحّد بجانب الرقائق السريعة
                ActionPill(stringResourceCompat(R.string.p36_pick_date), Blue) { showPicker = true }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.reschedule(inst, newDate) { ok ->
                    android.widget.Toast.makeText(
                        context,
                        context.getString(
                            if (ok) R.string.inst_postponed else R.string.inst_postpone_failed,
                            inst.seq
                        ),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                onDismiss()
            }) {
                Text(stringResourceCompat(R.string.save), color = g.accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResourceCompat(R.string.cancel), color = g.textSecondary)
            }
        }
    )

    // [P36-M4-5] منتقي التاريخ الموحد M3 — يُركَّب خارج حوار التأجيل (نمط StDatePicker)
    if (showPicker) {
        BizDatePickerDialog(
            initial = newDate,
            onPick = { pickedDate = it.coerceAtLeast(today) },
            onDismiss = { showPicker = false }
        )
    }
}

// ═════════ نافذة مشاركة التذكير ═════════
@Composable
private fun ShareDialog(vm: InstallmentsVM, ui: PlanUi, symbol: String, onDismiss: () -> Unit) {
    val g = glassColors()
    val context = LocalContext.current
    val shareTitle = stringResourceCompat(R.string.inst_share_remind)
    var phone by remember { mutableStateOf("") }
    LaunchedEffect(ui.plan.id) {
        phone = vm.partyOf(ui.plan.partyId)?.phone ?: ""
    }

    fun message(): String {
        val st = ui.stats
        val sb = StringBuilder()
        // [P6-M45 إصلاح] رسالة التذكير كانت عربية صلبة غير موطنة — مفاتيح inst_remind_*
        // بكل الغتين عبر context.getString بنمط الملف
        sb.append(context.getString(R.string.inst_remind_title, ui.plan.title)).append('\n')
        sb.append(
            // [P33-P8] مبالغ رسالة التذكير قروش Long — عرض عبر numP
            context.getString(R.string.inst_remind_total, Money.numP(ui.plan.total), symbol)
        ).append('\n')
        sb.append(
            context.getString(R.string.inst_remind_paid, Money.numP(st.paid), Money.numP(ui.plan.financed))
        ).append('\n')
        sb.append(
            context.getString(R.string.inst_remind_remaining, Money.numP(st.remaining), symbol)
        ).append('\n')
        st.nextDue?.let {
            sb.append(
                context.getString(R.string.inst_remind_next, it.seq, Money.numP(it.amount), Dates.short(it.dueDate))
            ).append('\n')
        }
        if (st.lateCount > 0) {
            sb.append(context.getString(R.string.inst_remind_late, Money.numP(st.lateAmount))).append('\n')
        }
        sb.append('\n').append(context.getString(R.string.inst_remind_footer))
        return sb.toString()
    }

    fun openWhatsApp() {
        // [P20-FIX agent2]: كان return صامتاً — زر ميت بلا أي تغذية راجعة حين بلا هاتف
        if (phone.isBlank()) {
            android.widget.Toast.makeText(
                context, context.getString(R.string.no_phone), android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        val clean = phone.replace(Regex("[^0-9+]"), "")
        val url = "https://wa.me/" + clean.removePrefix("+") + "?text=" + Uri.encode(message())
        // بلا حماية كان ActivityNotFoundException يغلق التطبيق فور الضغط
        startIntentSafe(
            context,
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun openSms() {
        if (phone.isBlank()) {
            android.widget.Toast.makeText(
                context, context.getString(R.string.no_phone), android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone"))
        intent.putExtra("sms_body", message())
        // الأجهزة بلا تطبيق رسائل كانت تنهار هنا فوراً
        startIntentSafe(context, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareGeneric() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message())
        }
        context.startActivity(
            Intent.createChooser(intent, shareTitle)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = g.surfaceStrong,
        title = { Text(stringResourceCompat(R.string.inst_share_remind), color = g.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    message(),
                    fontSize = 12.sp, color = g.textSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(g.surface)
                        .padding(10.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionPill("WhatsApp", Color(0xFF25D366)) { openWhatsApp() }
                    ActionPill("SMS", Blue) { openSms() }
                    ActionPill(stringResourceCompat(R.string.share), VioDeep) { shareGeneric() }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResourceCompat(R.string.close), color = g.accent, fontWeight = FontWeight.Bold)
            }
        }
    )
}

// ═════════ محرر خطة جديدة ═════════
@Composable
private fun PlanEditor(vm: InstallmentsVM, onDismiss: () -> Unit) {
    val g = glassColors()
    val parties by vm.parties.collectAsState()
    var title by remember { mutableStateOf("") }
    var total by remember { mutableStateOf("") }
    var down by remember { mutableStateOf("") }
    var months by remember { mutableStateOf("6") }
    var party by remember { mutableStateOf<com.superbiz.app.data.db.Party?>(null) }
    var direction by remember { mutableStateOf(0) }
    // [P6-M43 إصلاح] الرقائق +15/+30/+60 كانت تراكمية (start += N) مع توهج يوحي بأثر مطلق
    // ولا سبيل للتراجع: +30 بعد +15 كانت تعطي 45 يوماً والتوهج على +30. أصبحت مطلقة من
    // تاريخ القاعدة الافتراضي (baseStart): الضغط على +30 بعد +15 يعطي 30 يوماً من القاعدة
    // لا 45، والضغط على الرقاقة النشطة يعيد تاريخ القاعدة الأصلي (toggle)، والتوهج يتبع الحالة الفعلية
    val baseStart = remember { System.currentTimeMillis() + 15L * 86_400_000L }
    var start by remember { mutableStateOf(baseStart) }
    var startOffsetDays by remember { mutableStateOf(0L) }
    // [P36-M4-5] منتقي M3 الموحّد بجانب الرقائق السريعة — اختيار من التقويم يُصفّر توهّج الرقائق
    var customStart by remember { mutableStateOf(false) }
    var showStartPicker by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    val financedPreview = remember(total, down) {
        val f = parseNum(total) - parseNum(down)
        if (f > 0) f else 0.0
    }
    val perMonth = remember(financedPreview, months) {
        val m = months.toIntOrNull() ?: 0
        if (m >= 1 && financedPreview > 0) financedPreview / m else 0.0
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = g.surfaceStrong,
        title = { Text(stringResourceCompat(R.string.plan_new), color = g.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BizField(title, { title = it }, stringResourceCompat(R.string.plan_title))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill(stringResourceCompat(R.string.plan_dir_in), direction == 0) { direction = 0 }
                    FilterPill(stringResourceCompat(R.string.plan_dir_out), direction == 1) { direction = 1 }
                }
                Text(
                    party?.name ?: (stringResourceCompat(R.string.party) + " ▾"),
                    color = if (party == null) g.textSecondary else g.textPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth()
                )
                Column(Modifier.height(110.dp)) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BizField(
                        total, { total = it }, stringResourceCompat(R.string.plan_total),
                        Modifier.weight(1f), numberFieldOptions()
                    )
                    BizField(
                        down, { down = it }, stringResourceCompat(R.string.plan_down),
                        Modifier.weight(1f), numberFieldOptions()
                    )
                }
                BizField(
                    months, { months = it }, stringResourceCompat(R.string.plan_months),
                    Modifier.fillMaxWidth(), numberFieldOptions()
                )
                if (perMonth > 0) {
                    Text(
                        // [P33-P8] كان AccountingEngine.round2 — حُذف من المحرك؛ المُدخل نص ريال والعرض Double عبر num
                        stringResourceCompat(R.string.plan_per_month) + ": " +
                            Money.num(perMonth) + " " +
                            stringResourceCompat(R.string.per_month_suffix),
                        fontSize = 12.sp, color = g.accent, fontWeight = FontWeight.Bold
                    )
                }
                // [P36-M4-5] معاينة تاريخ البدء + رقاقة فتح منتقي M3 الموحّد بجانب الرقائق السريعة
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(Dates.short(start), color = g.textSecondary, fontSize = 12.sp)
                    ActionPill(stringResourceCompat(R.string.p36_pick_date), Blue) { showStartPicker = true }
                }
                // [P6-M43 إصلاح] الإزاحة تُحسب من تاريخ القاعدة دائماً لا من القيمة الحالية
                // [P36-M4-5] رقائق +N السريعة كما هي — سلوك التبديل/التراجع محفوظ حرفياً بلا اختيار تقويم
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15L, 30L, 60L).forEach { d ->
                        FilterPill("+$d", !customStart && startOffsetDays == d) {
                            if (!customStart && startOffsetDays == d) {
                                // النقر على الرقاقة النشطة يعيد تاريخ القاعدة (تراجع)
                                customStart = false
                                startOffsetDays = 0L
                                start = baseStart
                            } else {
                                customStart = false
                                startOffsetDays = d
                                start = baseStart + d * 86_400_000L
                            }
                        }
                    }
                }
                BizField(note, { note = it }, stringResourceCompat(R.string.note))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = party ?: return@TextButton
                val v = parseNum(total)
                val m = (months.toIntOrNull() ?: 0)
                if (title.isNotBlank() && v > 0 && m >= 1) {
                    vm.save(title, p, direction, v, parseNum(down), m, start, note)
                    onDismiss()
                }
            }) { Text(stringResourceCompat(R.string.save), color = g.accent, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResourceCompat(R.string.cancel), color = g.textSecondary)
            }
        }
    )

    // [P36-M4-5] منتقي التاريخ الموحد M3 — يُركَّب خارج حوار المحرر (نمط StDatePicker)
    if (showStartPicker) {
        BizDatePickerDialog(
            initial = start,
            onPick = {
                start = it
                customStart = true
            },
            onDismiss = { showStartPicker = false }
        )
    }
}
