package com.superbiz.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
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
import com.superbiz.app.util.startIntentSafe
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
            val shareLabel = stringResource(R.string.cf_export)
            OutlinedButton(
                onClick = {
                    runCatching {
                        val csv = com.superbiz.app.domain.algo.CashFlowCsv.build(list)
                        val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
                        val f = java.io.File(dir, "cash-flow-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".csv")
                        f.writeBytes(csv.toByteArray(Charsets.UTF_8))
                        val uri = androidx.core.content.FileProvider.getUriForFile(context, "com.superbiz.app.fileprovider", f)
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/csv"
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startIntentSafe(context, android.content.Intent.createChooser(send, shareLabel).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(shareLabel, maxLines = 1) }
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
