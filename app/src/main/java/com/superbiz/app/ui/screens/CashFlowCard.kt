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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.superbiz.app.AppGraph
import com.superbiz.app.R
import com.superbiz.app.domain.Accounts
import com.superbiz.app.domain.algo.CashFlowStatement
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** قائمة التدفق النقدي: قبض وصرف وصافي ورصيد ختامي لآخر 6 أشهر من حساب النقدية في الدفتر (عرض فقط) */
@Composable
fun CashFlowCard(symbol: String) {
    val context = LocalContext.current
    var months by remember { mutableStateOf<List<CashFlowStatement.Month>?>(null) }
    LaunchedEffect(Unit) {
        months = runCatching {
            withContext(Dispatchers.IO) {
                val g = AppGraph.from(context.applicationContext)
                val rows = g.db.journal().ledgerRows(0L, Long.MAX_VALUE)
                    .filter { it.account == Accounts.CASH }
                    .map { CashFlowStatement.Cash(it.date, it.debit, it.credit) }
                val now = System.currentTimeMillis()
                CashFlowStatement.build(rows, now, 6, java.util.TimeZone.getDefault().getOffset(now).toLong())
            }
        }.getOrNull()
    }
    val list = months ?: return
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.cf_title))
            Text(stringResource(R.string.cf_hint))
            list.reversed().forEach { m ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("%04d-%02d".format(m.year, m.month))
                    Row(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cf_row, Money.formatP(m.inflow, symbol), Money.formatP(m.outflow, symbol), Money.formatP(m.net, symbol)), modifier = Modifier.weight(1f))
                    }
                    Text(stringResource(R.string.cf_closing, Money.formatP(m.closing, symbol)))
                }
            }
        }
    }
}
