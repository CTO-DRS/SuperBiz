package com.superbiz.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.superbiz.app.domain.algo.PeriodLock
import com.superbiz.app.domain.rbac.Op
import com.superbiz.app.domain.rbac.SessionState
import com.superbiz.app.ui.components.GlassCard
import java.time.LocalDate
import kotlinx.coroutines.launch

/** [Phase 2] إقفال الفترات: إقفال حتى نهاية الشهر الماضي، وإعادة فتح شهر للمالك فقط بسبب إلزامي. */
@Composable
fun PeriodLockCard() {
    val context = LocalContext.current
    val store = remember { AppGraph.from(context.applicationContext).periodLock }
    val scope = rememberCoroutineScope()
    var through by remember { mutableStateOf<Long?>(null) }
    var message by remember { mutableStateOf<Int?>(null) }
    var askReason by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    val isOwner = SessionState.effective().can(Op.HARD_DELETE)

    LaunchedEffect(Unit) { through = store.lockedThrough() }

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.pl_title))
            Text(
                through?.let { stringResource(R.string.pl_locked_through, LocalDate.ofEpochDay(it).toString()) }
                    ?: stringResource(R.string.pl_not_locked)
            )
            OutlinedButton(
                onClick = {
                    if (!isOwner) { message = R.string.pl_owner_only; return@OutlinedButton }
                    scope.launch {
                        val today = LocalDate.now()
                        val end = today.withDayOfMonth(1).minusDays(1).toEpochDay()
                        val r = store.lock(end, today.toEpochDay())
                        message = if (r is PeriodLock.Change.Ok) R.string.pl_done else R.string.pl_rejected
                        through = store.lockedThrough()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.pl_close_prev_month), maxLines = 1) }
            if (through != null) OutlinedButton(
                onClick = { if (isOwner) askReason = true else message = R.string.pl_owner_only },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.pl_reopen), maxLines = 1) }
            message?.let { Text(stringResource(it)) }
        }
    }

    if (askReason) AlertDialog(
        onDismissRequest = { askReason = false },
        title = { Text(stringResource(R.string.pl_reopen)) },
        text = {
            OutlinedTextField(
                value = reason, onValueChange = { reason = it },
                label = { Text(stringResource(R.string.pl_reason_hint)) }
            )
        },
        confirmButton = {
            TextButton(
                enabled = reason.isNotBlank(),
                onClick = {
                    askReason = false
                    scope.launch {
                        val cur = store.lockedThrough() ?: return@launch
                        val back = LocalDate.ofEpochDay(cur).withDayOfMonth(1).minusDays(1).toEpochDay()
                        val r = store.reopen(back, isOwner, reason.trim())
                        message = if (r is PeriodLock.Change.Ok) R.string.pl_done else R.string.pl_rejected
                        through = store.lockedThrough()
                        reason = ""
                    }
                }
            ) { Text(stringResource(R.string.pl_confirm)) }
        },
        dismissButton = { TextButton(onClick = { askReason = false }) { Text(stringResource(R.string.pl_cancel)) } }
    )
}
