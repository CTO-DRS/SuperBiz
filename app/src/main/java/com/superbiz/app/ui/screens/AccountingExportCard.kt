package com.superbiz.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.superbiz.app.AppGraph
import com.superbiz.app.R
import com.superbiz.app.domain.Accounts
import com.superbiz.app.domain.algo.AccountingCsv
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.util.startIntentSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** [D5] تصدير دفتر الأستاذ وميزان المراجعة CSV (UTF-8 BOM) عبر نافذة المشاركة — بلا إذن تخزين */
@Composable
fun AccountingExportCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.acc_export_title)
    val failed = stringResource(R.string.acc_export_failed)

    fun export(fileName: String, build: suspend (AppGraph) -> String) {
        scope.launch {
            val ok = runCatching {
                val csv = withContext(Dispatchers.IO) { build(AppGraph.from(context.applicationContext)) }
                val f = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                    File(dir, fileName).apply { writeBytes(csv.toByteArray(Charsets.UTF_8)) }
                }
                val uri = FileProvider.getUriForFile(context, "com.superbiz.app.fileprovider", f)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startIntentSafe(context, Intent.createChooser(send, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }.isSuccess
            if (!ok) android.widget.Toast.makeText(context, failed, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun nameOf(code: String): String = Accounts.names[code]?.first ?: code
    val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        export("ledger-$stamp.csv") { g ->
                            AccountingCsv.ledger(g.db.journal().ledgerRows(0L, Long.MAX_VALUE), ::nameOf) { day.format(Date(it)) }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.acc_export_ledger), maxLines = 1) }
                OutlinedButton(
                    onClick = {
                        export("trial-balance-$stamp.csv") { g ->
                            AccountingCsv.trialBalance(g.db.journal().accountSums(), ::nameOf)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.acc_export_trial), maxLines = 1) }
            }
        }
    }
}
