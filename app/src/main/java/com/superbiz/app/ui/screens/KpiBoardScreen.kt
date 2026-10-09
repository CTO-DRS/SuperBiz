package com.superbiz.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.superbiz.app.R
import com.superbiz.app.domain.algo.R18Kpi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.superbiz.app.domain.KpiBoardP47
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.components.parseNum
import com.superbiz.app.ui.components.SubHeader
import com.superbiz.app.ui.theme.Amber
import com.superbiz.app.ui.theme.Green
import com.superbiz.app.ui.theme.Red
import com.superbiz.app.ui.theme.Vio
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.util.Money
import com.superbiz.app.vm.AppVM
import com.superbiz.app.vm.ProVM
import java.util.Calendar

/**
 * [W1] لوحة المؤشرات (KPIs) — أول باب مدفوع في فريميوم SuperBiz:
 *
 * «الفعلي مقابل الهدف» بأرقام حقيقية 100٪ من الدفتر (مبيعات/ربح/مصروفات
 * الشهر + الذمم المتأخرة) عبر محرك KpiBoardP47 النقي: تقدم، إيقاع مقابل
 * نسبة الشهر المضيّة، وتوقع نهاية الشهر خطياً صادق.
 * الباب المقفل لا يكذب: بطاقة حديفة صريحة تشرح ما خلفه وتقود إلى شاشة Pro.
 */
@Composable
fun KpiBoardScreen(
    appVM: AppVM,
    onBack: () -> Unit,
    openPro: () -> Unit,
    proVM: ProVM = viewModel(),
    // [H3-5] VM المنسّق الذكي — يغذي بطاقة الروايات (AndroidViewModel بمصنع افتراضي كـProVM)
    smartVM: com.superbiz.app.vm.SmartCoordinatorVM = viewModel()
) {
    val g = glassColors()
    val pro by proVM.pro.collectAsState()
    val home by appVM.home.collectAsState()
    val symbol by appVM.symbol.collectAsState()
    val targets by proVM.targets.collectAsState()
    val smartHome by smartVM.home.collectAsState()
    var showEditor by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        SubHeader(
            stringResource(R.string.kpi_title),
            onBack = onBack,
            trailing = if (pro) ({
                Row(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { showEditor = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Edit, stringResourceCompat(R.string.a11y_kpi_edit), Modifier.size(16.dp), tint = Vio)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.kpi_edit_targets),
                        style = MaterialTheme.typography.labelMedium, color = Vio
                    )
                }
            }) else null
        )

        if (!pro) {
            // ══ الحديفة الصادقة — بلا بيانات وهمية خلف القفل ══
            GlassCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(24.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Rounded.Lock, stringResourceCompat(R.string.a11y_kpi_lock),
                        tint = Amber, modifier = Modifier.size(40.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.kpi_locked_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.kpi_locked_sub),
                        style = MaterialTheme.typography.bodyMedium,
                        color = g.textSecondary
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = openPro,
                        colors = ButtonDefaults.buttonColors(containerColor = Vio),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            Icons.Rounded.WorkspacePremium, stringResourceCompat(R.string.a11y_kpi_upgrade),
                            Modifier.size(18.dp), tint = Color.White
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.kpi_unlock_cta),
                            color = Color.White, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            return@Column
        }

        // ══ لوحة المؤشرات — محرك نقي على بيانات الشهر الفعلية ══
        val cal = Calendar.getInstance()
        val homeFlow = home
        val fraction = KpiBoardP47.monthFraction(
            cal.get(Calendar.DAY_OF_MONTH), cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        )
        val kpis = listOf(
            KpiBoardP47.Kpi(
                KpiBoardP47.Key.SALES, KpiBoardP47.Direction.HIGHER_BETTER,
                home.salesMonth, targets.sales, fraction
            ),
            KpiBoardP47.Kpi(
                KpiBoardP47.Key.PROFIT, KpiBoardP47.Direction.HIGHER_BETTER,
                home.profitMonth, targets.profit, fraction
            ),
            KpiBoardP47.Kpi(
                KpiBoardP47.Key.EXPENSES, KpiBoardP47.Direction.LOWER_BETTER,
                home.expensesMonth, targets.expenses, fraction
            ),
            KpiBoardP47.Kpi(
                KpiBoardP47.Key.OVERDUE, KpiBoardP47.Direction.LOWER_BETTER,
                home.overdue, targets.overdue, fraction
            )
        )

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                stringResource(R.string.kpi_month_progress),
                style = MaterialTheme.typography.bodySmall,
                color = g.textSecondary
            )
            Spacer(Modifier.height(8.dp))
            // [H4-7][V 3.0.0] بطاقة المؤشرات الكاملة — نبض العمل أعلى اللوحة
            val pulseCtx = androidx.compose.ui.platform.LocalContext.current
            val pulseState = androidx.compose.runtime.produceState<R18Kpi.Pulse?>(initialValue = null, appVM) {
                value = withContext(Dispatchers.IO) {
                    val graph = com.superbiz.app.AppGraph.from(pulseCtx.applicationContext)
                    runCatching {
                        R18Kpi.pulse(
                            R18Kpi.Input(
                                salesMonthPiasters = com.superbiz.app.util.Money.toPiasters(homeFlow.salesMonth),
                                profitMonthPiasters = com.superbiz.app.util.Money.toPiasters(homeFlow.profitMonth),
                                expensesMonthPiasters = com.superbiz.app.util.Money.toPiasters(homeFlow.expensesMonth),
                                overduePiasters = com.superbiz.app.util.Money.toPiasters(homeFlow.overdue),
                                lowStockCount = homeFlow.lowStockCount,
                                totalProducts = graph.db.products().allOnce().size,
                                cash90NetPiasters = null,
                                dayOfMonth = cal.get(Calendar.DAY_OF_MONTH),
                                daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                            )
                        )
                    }.getOrNull()
                }
            }
            pulseState.value?.let { pulse ->
                com.superbiz.app.ui.insights.FullPulseCard(pulse, symbol)
                Spacer(Modifier.height(10.dp))
            }
            kpis.forEach { k -> KpiCard(k, symbol, fraction) ; Spacer(Modifier.height(10.dp)) }
            // [H3-5] روايات الأداء — كل سطر يحمل أساسه الخام (الشفافية الكاملة)
            com.superbiz.app.ui.insights.KpiNarrativesCard(smartHome.stories, symbol)
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.kpi_realdata_note),
                style = MaterialTheme.typography.bodySmall,
                color = g.textSecondary
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showEditor) {
        KpiTargetsEditor(
            current = targets,
            onDismiss = { showEditor = false },
            onSave = { k, v ->
                proVM.setTarget(k, v)
            }
        )
    }
}

@Composable
private fun KpiCard(k: KpiBoardP47.Kpi, symbol: String, fraction: Double) {
    val g = glassColors()
    val r = KpiBoardP47.evaluate(k)
    val label = stringResource(
        when (k.key) {
            KpiBoardP47.Key.SALES -> R.string.kb_kpi_sales
            KpiBoardP47.Key.PROFIT -> R.string.kb_kpi_profit
            KpiBoardP47.Key.EXPENSES -> R.string.kb_kpi_expenses
            KpiBoardP47.Key.OVERDUE -> R.string.kb_kpi_overdue
        }
    )
    val (statusText, statusColor) = when (r.status) {
        KpiBoardP47.Status.NO_TARGET -> stringResource(R.string.kpi_no_target) to g.textSecondary
        KpiBoardP47.Status.ACHIEVED -> stringResource(R.string.kpi_achieved) to Green
        KpiBoardP47.Status.AHEAD -> stringResource(R.string.kpi_ahead) to Amber
        KpiBoardP47.Status.BEHIND -> stringResource(R.string.kpi_behind) to Red
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(statusText, style = MaterialTheme.typography.labelMedium, color = statusColor)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                Money.format(k.actual, symbol),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (k.target > 0) {
                Spacer(Modifier.height(8.dp))
                // شريط التقدم — مقصوص 0..1 للعرض فقط (النسبة الحقيقية تبقى في المحرك)
                val shown = r.progress.coerceIn(0.0, 1.0).toFloat()
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(g.border)
                ) {
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .fillMaxWidth(shown)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                Brush.horizontalGradient(listOf(Vio, Green))
                            )
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.kpi_target_line)
                        .replace("{pct}", "${(r.progress * 100).toInt()}")
                        .replace("{target}", Money.format(k.target, symbol)),
                    style = MaterialTheme.typography.bodySmall,
                    color = g.textSecondary
                )
                Text(
                    stringResource(R.string.kpi_forecast_line)
                        .replace("{f}", Money.format(r.forecastEom, symbol)),
                    style = MaterialTheme.typography.bodySmall,
                    color = g.textSecondary
                )
            }
        }
    }
}

/** حوار أهداف اللوحة الأربعة — تحليل متسامح عبر parseNum (نمط المشروع) */
@Composable
private fun KpiTargetsEditor(
    current: com.superbiz.app.data.repo.ProStore.KpiTargets,
    onDismiss: () -> Unit,
    onSave: (KpiBoardP47.Key, Double) -> Unit
) {
    val keys = listOf(
        Triple(KpiBoardP47.Key.SALES, R.string.kb_kpi_sales, current.sales),
        Triple(KpiBoardP47.Key.PROFIT, R.string.kb_kpi_profit, current.profit),
        Triple(KpiBoardP47.Key.EXPENSES, R.string.kb_kpi_expenses, current.expenses),
        Triple(KpiBoardP47.Key.OVERDUE, R.string.kb_kpi_overdue, current.overdue)
    )
    val texts = remember {
        keys.map { mutableStateOf(if (it.third > 0) com.superbiz.app.util.Money.num(it.third) else "") }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kpi_edit_title)) },
        text = {
            Column {
                keys.forEachIndexed { i, t ->
                    OutlinedTextField(
                        value = texts[i].value,
                        onValueChange = { texts[i].value = it },
                        label = { Text(stringResource(t.second)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    )
                }
                Text(
                    stringResource(R.string.kpi_edit_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                keys.forEachIndexed { i, t ->
                    val raw = texts[i].value.trim()
                    val v = if (raw.isEmpty()) 0.0 else parseNum(raw).coerceAtLeast(0.0)
                    onSave(t.first, v)
                }
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
