package com.superbiz.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.superbiz.app.AppGraph
import com.superbiz.app.R
import com.superbiz.app.data.repo.resolveBranchId
import com.superbiz.app.domain.algo.ExpenseYoY
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/** مقارنة المصروفات سنة بسنة: من 1 يناير حتى اليوم نفسه، لكل فئة (للفرع الحالي) — عرض فقط */
@Composable
fun ExpenseYoYCard(symbol: String) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<ExpenseYoY.Report?>(null) }
    LaunchedEffect(Unit) {
        report = runCatching {
            withContext(Dispatchers.IO) {
                val g = AppGraph.from(context.applicationContext)
                val b = g.db.resolveBranchId()
                val now = Calendar.getInstance()
                fun startOfYear(c: Calendar) = (c.clone() as Calendar).apply {
                    set(Calendar.MONTH, Calendar.JANUARY); set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                val prev = (now.clone() as Calendar).apply { add(Calendar.YEAR, -1) }
                val cur = g.db.expenses().byCategoryBetweenBranch(startOfYear(now), now.timeInMillis, b).associate { it.category to it.total }
                val old = g.db.expenses().byCategoryBetweenBranch(startOfYear(prev), prev.timeInMillis, b).associate { it.category to it.total }
                ExpenseYoY.compare(cur, old)
            }
        }.getOrNull()
    }
    val r = report ?: return
    if (r.rows.isEmpty()) return
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.yoy_title))
            Text(stringResource(R.string.yoy_hint))
            r.rows.forEach { row ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.category, modifier = Modifier.weight(1f))
                    val pct = row.changePct
                    Text(
                        Money.formatP(row.current, symbol) + " / " + Money.formatP(row.previous, symbol) +
                            (if (pct != null) "  (" + (if (pct > 0) "+" else "") + pct + "%)" else "")
                    )
                }
            }
            Text(stringResource(R.string.yoy_total, Money.formatP(r.totalCurrent, symbol), Money.formatP(r.totalPrevious, symbol)))
        }
    }
}
