package com.superbiz.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.superbiz.app.AppGraph
import com.superbiz.app.R
import com.superbiz.app.data.repo.BudgetStore
import com.superbiz.app.data.repo.resolveBranchId
import com.superbiz.app.domain.algo.BudgetMath
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/** [D4] ميزانية المصروفات الشهرية مقابل الفعلي لكل فئة (للفرع الحالي) — اضغط الصف لتعديل الهدف */
@Composable
fun BudgetCard(symbol: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var report by remember { mutableStateOf<BudgetMath.Report?>(null) }
    var annual by remember { mutableStateOf<List<BudgetMath.Annual>>(emptyList()) }
    var branch by remember { mutableStateOf(0L) }
    var editing by remember { mutableStateOf<BudgetMath.Row?>(null) }
    var editingYear by remember { mutableStateOf<BudgetMath.Annual?>(null) }
    var yearInput by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }

    LaunchedEffect(tick) {
        report = runCatching {
            withContext(Dispatchers.IO) {
                val g = AppGraph.from(context.applicationContext)
                val b = g.db.resolveBranchId()
                val cal = Calendar.getInstance().apply {
                    set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                val from = cal.timeInMillis
                val actual = g.db.expenses().byCategoryBetweenBranch(from, Long.MAX_VALUE, b)
                    .associate { it.category to it.total }
                branch = b
                val ycal = Calendar.getInstance().apply {
                    set(Calendar.MONTH, Calendar.JANUARY); set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                val ytd = g.db.expenses().byCategoryBetweenBranch(ycal.timeInMillis, Long.MAX_VALUE, b)
                    .associate { it.category to it.total }
                val budgets = BudgetStore(context).all(b)
                val nowCal = Calendar.getInstance()
                annual = BudgetMath.annualProjection(budgets, ytd, nowCal.get(Calendar.MONTH) + 1, BudgetStore(context).allYear(b, nowCal.get(Calendar.YEAR)))
                    .filter { it.annualBudget > 0L }
                BudgetMath.compare(budgets, actual)
            }
        }.getOrNull()
    }

    val r = report ?: return
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.budget_title))
            Text(stringResource(R.string.budget_hint))
            if (r.rows.isEmpty()) Text(stringResource(R.string.budget_empty))
            r.rows.forEach { row ->
                Row(
                    Modifier.fillMaxWidth().clickable { editing = row; input = if (row.hasBudget) Money.fromPiasters(row.budget).toString() else "" },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(row.category.ifBlank { "-" }, modifier = Modifier.weight(1f))
                    val pct = row.usedPct
                    Text(
                        if (pct != null) Money.formatP(row.actual, symbol) + " / " + Money.formatP(row.budget, symbol) + "  (" + pct + "%" + (if (row.over) " ⛔" else if (row.warn) " ⚠" else "") + ")"
                        else Money.formatP(row.actual, symbol) + "  " + stringResource(R.string.budget_none)
                    )
                }
            }
            if (r.warnCount > 0) {
                Text(stringResource(R.string.budget_alert_summary, r.warnCount))
            }
            if (r.totalBudget > 0L) {
                Text(stringResource(R.string.budget_total, Money.formatP(r.totalBudgetedActual, symbol), Money.formatP(r.totalBudget, symbol)))
            }
            if (annual.isNotEmpty()) {
                Text(stringResource(R.string.budget_annual_title))
                annual.forEach { a ->
                    Row(
                        Modifier.fillMaxWidth().clickable { editingYear = a; yearInput = Money.fromPiasters(a.annualBudget).toString() },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(a.category.ifBlank { "-" }, modifier = Modifier.weight(1f))
                        Text(
                            stringResource(R.string.budget_annual_row, Money.formatP(a.projected, symbol), Money.formatP(a.annualBudget, symbol)) +
                                (if (a.projectedOver) " ⛔" else "")
                        )
                    }
                }
                val overs = annual.filter { it.projectedOver }
                if (overs.isNotEmpty()) Text(stringResource(R.string.budget_annual_over, overs.joinToString("، ") { it.category }))
            }
        }
    }

    editing?.let { row ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(row.category.ifBlank { "-" }) },
            text = {
                OutlinedTextField(
                    value = input, onValueChange = { input = it }, singleLine = true,
                    label = { Text(stringResource(R.string.budget_monthly_target)) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = Money.parseToPiasters(input)
                    val cat = row.category
                    editing = null
                    scope.launch(Dispatchers.IO) { BudgetStore(context).set(branch, cat, p); tick++ }
                }) { Text(stringResource(R.string.budget_save)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.budget_cancel)) } }
        )
    }

    editingYear?.let { a ->
        AlertDialog(
            onDismissRequest = { editingYear = null },
            title = { Text(a.category.ifBlank { "-" }) },
            text = {
                OutlinedTextField(
                    value = yearInput, onValueChange = { yearInput = it }, singleLine = true,
                    label = { Text(stringResource(R.string.budget_annual_target)) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = Money.parseToPiasters(yearInput)
                    val cat = a.category
                    editingYear = null
                    scope.launch(Dispatchers.IO) {
                        BudgetStore(context).setYear(branch, Calendar.getInstance().get(Calendar.YEAR), cat, p); tick++
                    }
                }) { Text(stringResource(R.string.budget_save)) }
            },
            dismissButton = { TextButton(onClick = { editingYear = null }) { Text(stringResource(R.string.budget_cancel)) } }
        )
    }
}
