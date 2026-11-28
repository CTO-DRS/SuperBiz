package com.superbiz.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.superbiz.app.AppGraph
import com.superbiz.app.R
import com.superbiz.app.domain.Accounts
import com.superbiz.app.domain.algo.AccountingCsv
import com.superbiz.app.domain.algo.BankReconcileMath
import com.superbiz.app.ui.components.GlassCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** [Phase 2] تسوية بنكية: استيراد كشف CSV ومطابقته مع حركات النقدية (1010) — عرض فقط، لا يكتب في الدفتر */
@Composable
fun BankReconcileCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<BankReconcileMath.Result?>(null) }
    var skipped by remember { mutableStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    var txs by remember { mutableStateOf<List<BankReconcileMath.BankTx>>(emptyList()) }
    var confirm by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            val out = runCatching {
                withContext(Dispatchers.IO) {
                    val text = context.contentResolver.openInputStream(uri)!!.use { String(it.readBytes(), Charsets.UTF_8) }
                    val parsed = BankReconcileMath.parseStatement(text) ?: return@withContext null
                    val g = AppGraph.from(context.applicationContext)
                    val book = g.db.journal().ledgerRows(0L, Long.MAX_VALUE)
                        .filter { it.account == Accounts.CASH }
                        .map { BankReconcileMath.BookTx(it.entryId, Math.floorDiv(it.date, 86_400_000L), it.debit - it.credit, it.memo) }
                    parsed to BankReconcileMath.reconcile(parsed.txs, book)
                }
            }.getOrNull()
            failed = out == null
            result = out?.second
            txs = out?.first?.txs ?: emptyList()
            note = null
            skipped = out?.first?.skipped ?: 0
        }
    }

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.bank_rec_title))
            OutlinedButton(
                onClick = { picker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel", "*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.bank_rec_import), maxLines = 1) }
            if (failed) Text(stringResource(R.string.bank_rec_bad_file))
            result?.let { r ->
                Text(stringResource(R.string.bank_rec_summary, r.matched.size, r.bankOnly.size, r.bookOnly.size))
                Text(stringResource(R.string.bank_rec_bank_only_total, AccountingCsv.amount(r.bankOnlyTotal)))
                Text(stringResource(R.string.bank_rec_book_only_total, AccountingCsv.amount(r.bookOnlyTotal)))
                if (skipped > 0) Text(stringResource(R.string.bank_rec_skipped, skipped))
                val plan = BankReconcileMath.importPlan(r.bankOnly)
                if (plan.expenses.isNotEmpty()) {
                    OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.bank_rec_add_expenses, plan.expenses.size), maxLines = 1)
                    }
                }
                if (plan.inflowsSkipped > 0) Text(stringResource(R.string.bank_rec_inflows_skipped, plan.inflowsSkipped))
                note?.let { Text(it) }
                r.bankOnly.take(5).forEach {
                    Text(
                        "${java.time.LocalDate.ofEpochDay(it.day)}  ${AccountingCsv.amount(it.amount)}  ${it.description}".trim(),
                        maxLines = 1
                    )
                }
            }
        }
    }

    if (confirm) {
        val plan = BankReconcileMath.importPlan(result?.bankOnly ?: emptyList())
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.bank_rec_add_confirm_title)) },
            text = { Text(stringResource(R.string.bank_rec_add_confirm_body, plan.expenses.size, AccountingCsv.amount(plan.expenses.sumOf { it.amount }))) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirm = false
                    scope.launch {
                        var added = 0
                        var blocked = 0
                        var failedRows = 0
                        val res = withContext(Dispatchers.IO) {
                            val g = AppGraph.from(context.applicationContext)
                            for (e in plan.expenses) {
                                try {
                                    g.expenses.add(e.amount, "", e.note, e.day * 86_400_000L + 43_200_000L)
                                    added++
                                } catch (x: com.superbiz.app.domain.algo.PeriodLockedException) {
                                    blocked++
                                } catch (x: Exception) {
                                    failedRows++
                                }
                            }
                            val book = g.db.journal().ledgerRows(0L, Long.MAX_VALUE)
                                .filter { it.account == Accounts.CASH }
                                .map { BankReconcileMath.BookTx(it.entryId, Math.floorDiv(it.date, 86_400_000L), it.debit - it.credit, it.memo) }
                            BankReconcileMath.reconcile(txs, book)
                        }
                        result = res
                        note = context.getString(R.string.bank_rec_add_done, added, blocked + failedRows)
                    }
                }) { Text(stringResource(R.string.bank_rec_add_confirm)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.budget_cancel)) }
            }
        )
    }
}
