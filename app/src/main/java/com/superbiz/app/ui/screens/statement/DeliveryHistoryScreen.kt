package com.superbiz.app.ui.screens.statement

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.superbiz.app.MainActivity
import com.superbiz.app.R
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.components.SubHeader
import com.superbiz.app.ui.nav.Routes
import com.superbiz.app.ui.screens.stringResourceCompat
import com.superbiz.app.ui.theme.Cyan
import com.superbiz.app.ui.theme.GreenDeep
import com.superbiz.app.ui.theme.RedDeep
import com.superbiz.app.ui.theme.Vio
import com.superbiz.app.ui.theme.glassColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [P18-c] سجل تسليم الكشوف (المسار: statement_deliveries?statementId=N).
 *
 * statementId > 0 ⇒ سجل كشف واحد، وإلا قائمة عامة مبنية من أحدث الكشوف
 * (StatementUiFacade.deliveriesAll — تجميع صادق موثق في KDoc). الأزرار كلها حقيقية:
 * إعادة المحاولة ترسل فعلاً عبر SMTP، وفتح القناة يشارك/يطبع/ينتقل لشاشة الكشف،
 * والإلغاء يمر عبر آلة حالات 17-a فقط.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeliveryHistoryScreen(statementId: Long, nav: NavHostController) {
    val context = LocalContext.current
    val g = glassColors()
    val scope = rememberCoroutineScope()

    var rows by remember { mutableStateOf(listOf<StatementUiFacade.DeliveryRow>()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf<String?>(null) } // null = الكل
    var expanded by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf<Long?>(null) }
    var refresh by remember { mutableStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val maxAttempts = remember { StatementUiFacade.smtpPrefs(context.applicationContext).autoRetryMax }

    LaunchedEffect(statementId, refresh) {
        loading = true; loadError = null
        withContext(Dispatchers.IO) {
            try {
                rows = if (statementId > 0)
                    StatementUiFacade.deliveriesFor(context.applicationContext, statementId)
                else
                    StatementUiFacade.deliveriesAll(context.applicationContext, 200)
            } catch (e: Exception) {
                loadError = (e.message ?: e.javaClass.simpleName).take(120)
            }
        }
        loading = false
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 12.dp)) {
            SubHeader(stringResourceCompat(R.string.st2_deliveries_title), onBack = { nav.popBackStack() })

            if (statementId > 0) {
                // زر العودة للقائمة العامة — نمط الرقائق نفسه
                StChip(stringResourceCompat(R.string.st2_filter_all), selected = false) {
                    nav.popBackStack()
                }
            }

            // رقائق التصفية الست + الكل
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 6.dp)
            ) {
                StChip(stringResourceCompat(R.string.st2_filter_all), filter == null) { filter = null }
                DELIVERY_STATUSES.forEach { s ->
                    StChip(statusLabel(s), filter == s) { filter = if (filter == s) null else s }
                }
            }

            if (loading) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 24.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(color = g.accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
            } else {
                loadError?.let { Spacer(Modifier.height(8.dp)) }
                loadError?.let { StErrorRow(it) }
                val filtered = if (filter == null) rows else rows.filter { it.status == filter }
                if (filtered.isEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    StHint(stringResourceCompat(R.string.st2_no_deliveries))
                }
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(top = 10.dp)
                ) {
                    items(filtered.size) { i ->
                        val row = filtered[i]
                        DeliveryRowCard(
                            row = row,
                            busy = busy == row.id,
                            expanded = expanded == row.id,
                            maxAttempts = maxAttempts,
                            onToggle = { expanded = if (expanded == row.id) null else row.id },
                            onRetry = {
                                busy = row.id
                                scope.launch(Dispatchers.IO) {
                                    val res = StatementUiFacade.retryDeliveryNow(
                                        context.applicationContext, row.id
                                    )
                                    withContext(Dispatchers.Main) {
                                        busy = null; refresh++
                                        snackbar.showSnackbar(
                                            if (res.isSuccess) context.getString(R.string.st2_retry_ok)
                                            else context.getString(
                                                R.string.st2_retry_fail,
                                                (res.exceptionOrNull()?.message ?: "").take(80)
                                            )
                                        )
                                    }
                                }
                            },
                            onCancel = {
                                busy = row.id
                                scope.launch(Dispatchers.IO) {
                                    val ok = StatementUiFacade.cancelDelivery(
                                        context.applicationContext, row.id
                                    )
                                    withContext(Dispatchers.Main) {
                                        busy = null; refresh++
                                        if (ok) snackbar.showSnackbar(context.getString(R.string.st2_cancelled_ok))
                                    }
                                }
                            },
                            onShare = { shareStatementPdf(context, row.filePath) },
                            onPrint = { printStatement(context, row.filePath, row.statementNumber) },
                            onOpenStatement = {
                                nav.navigate(Routes.STATEMENT.replace("{partyId}", row.partyId.toString()))
                            },
                            onFilterThis = {
                                nav.navigate(Routes.STATEMENT_DELIVERIES + "?statementId=" + row.statementId)
                            }
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

/** بطاقة صف تسليم — كل النصوص تُحل هنا داخل التركيب (لا composable في اللامدات) */
@Composable
private fun DeliveryRowCard(
    row: StatementUiFacade.DeliveryRow,
    busy: Boolean,
    expanded: Boolean,
    maxAttempts: Int,
    onToggle: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onShare: () -> Unit,
    onPrint: () -> Unit,
    onOpenStatement: () -> Unit,
    onFilterThis: () -> Unit
) {
    val g = glassColors()
    val decision = retryDecision(row.status, row.attempts, maxAttempts)
    val channelLabel = channelLabel(row.channel)
    val statusStr = statusLabel(row.status)
    val retryStr = stringResourceCompat(R.string.st2_retry_now)
    val cancelStr = stringResourceCompat(R.string.st2_cancel_delivery)
    val shareStr = stringResourceCompat(R.string.st_share)
    val printStr = stringResourceCompat(R.string.st_print)
    val openStr = stringResourceCompat(R.string.st2_open_channel)
    val errLabel = stringResourceCompat(R.string.st2_error_label)
    val attemptsStr = stringResourceCompat(R.string.st2_attempts, row.attempts)
    val backoff = backoffText(row.attempts)
    val scheduledStr = stringResourceCompat(R.string.st2_scheduled_wait, backoff)
    val exhaustedStr = stringResourceCompat(R.string.st2_exhausted)
    val notRetryableStr = stringResourceCompat(R.string.st2_not_retryable_channel)

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // [P18-c] استثناء الألوان المعلن: نقطة حالة صغيرة بلون ثابت من منطق نقي
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color(deliveryStatusColor(row.status)))
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "#" + row.statementNumber,
                        fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        color = g.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { onFilterThis() }
                    )
                    Text(
                        (row.partyName.ifBlank { "—" }) + " • " + channelLabel + " • " + statusStr,
                        fontSize = 11.sp, color = g.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(attemptsStr + " • " + timeLine(row), fontSize = 11.sp, color = g.textSecondary)
                }
                if (row.lastError != null) {
                    IconButton(onClick = onToggle) {
                        Icon(
                            if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = errLabel, tint = g.textSecondary
                        )
                    }
                }
            }

            if (expanded && row.lastError != null) {
                Text(errLabel, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = RedDeep)
                Text(row.lastError, fontSize = 11.sp, color = g.textSecondary, maxLines = 6)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        color = g.accent, strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp).align(Alignment.CenterVertically)
                    )
                }
                if (decision == RetryDecision.CAN_RETRY_NOW && row.channel in SMTP_RETRYABLE_CHANNELS) {
                    StAction(retryStr, GreenDeep, onClick = onRetry)
                }
                if (decision == RetryDecision.EXHAUSTED) {
                    Text(exhaustedStr, fontSize = 11.sp, color = RedDeep, modifier = Modifier.align(Alignment.CenterVertically))
                }
                if (decision == RetryDecision.AUTO_SCHEDULED) {
                    Text(scheduledStr, fontSize = 11.sp, color = g.textSecondary, modifier = Modifier.align(Alignment.CenterVertically))
                }
                if (decision == RetryDecision.NOT_RETRYABLE && row.status !in listOf("SENT")) {
                    Text(notRetryableStr, fontSize = 11.sp, color = g.textSecondary, modifier = Modifier.align(Alignment.CenterVertically))
                }
                if (row.status in listOf("PENDING", "FAILED", "RETRYING")) {
                    StAction(cancelStr, RedDeep, onClick = onCancel)
                }
                when (row.channel) {
                    "SHARE" -> StAction(shareStr, Cyan, onClick = onShare)
                    "PRINT" -> StAction(printStr, Vio, onClick = onPrint)
                    "WHATSAPP", "EMAIL", "SMTP" -> StAction(openStr, Cyan, onClick = onOpenStatement)
                }
            }
        }
    }
}

// ── مساعدات محلية ──

@Composable
private fun statusLabel(s: String): String = when (s) {
    "SENT" -> stringResourceCompat(R.string.st2_status_sent)
    "FAILED" -> stringResourceCompat(R.string.st2_status_failed)
    "RETRYING" -> stringResourceCompat(R.string.st2_status_retrying)
    "PROCESSING" -> stringResourceCompat(R.string.st2_status_processing)
    "PENDING" -> stringResourceCompat(R.string.st2_status_pending)
    "CANCELLED" -> stringResourceCompat(R.string.st2_status_cancelled)
    else -> s
}

@Composable
private fun channelLabel(c: String): String = when (c) {
    "EMAIL" -> stringResourceCompat(R.string.st2_channel_email)
    "WHATSAPP" -> stringResourceCompat(R.string.st2_channel_whatsapp)
    "SHARE" -> stringResourceCompat(R.string.st2_channel_share)
    "PRINT" -> stringResourceCompat(R.string.st2_channel_print)
    "SMTP" -> stringResourceCompat(R.string.st2_channel_smtp)
    else -> c
}

/** حل مفتاح فترة التهدئة إلى نص — نفس نمط statusLabel */
@Composable
private fun backoffText(attempts: Int): String = when (backoffLabel(attempts)) {
    KEY_BACKOFF_15M -> stringResourceCompat(R.string.st2_backoff_15m)
    KEY_BACKOFF_1H -> stringResourceCompat(R.string.st2_backoff_1h)
    KEY_BACKOFF_6H -> stringResourceCompat(R.string.st2_backoff_6h)
    else -> stringResourceCompat(R.string.st2_backoff_24h)
}

/** سطر الوقت: أُرسل/مجدول/آخر محاولة — الوقت النسبي من المنطق النقي */
@Composable
private fun timeLine(row: StatementUiFacade.DeliveryRow): String {
    val now = System.currentTimeMillis()
    val ts = row.sentAt ?: row.scheduledFor ?: row.lastAttemptAt ?: return ""
    val rt = relativeTime(ts, now)
    return when (rt.key) {
        KEY_REL_NOW -> stringResourceCompat(R.string.st2_rel_now)
        KEY_REL_MIN -> stringResourceCompat(R.string.st2_rel_min, rt.value)
        KEY_REL_HOUR -> stringResourceCompat(R.string.st2_rel_hour, rt.value)
        KEY_REL_DAY -> stringResourceCompat(R.string.st2_rel_day, rt.value)
        KEY_REL_FUTURE -> stringResourceCompat(R.string.st2_rel_future, rt.value)
        else -> stringResourceCompat(R.string.st2_rel_never)
    }
}

/** مشاركة ملف الكشف عبر FileProvider — نفس نمط StatementScreen.shareFile */
private fun shareStatementPdf(context: android.content.Context, path: String) {
    val file = java.io.File(path)
    if (!file.exists()) {
        Toast.makeText(context, context.getString(R.string.st_file_missing), Toast.LENGTH_SHORT).show()
        return
    }
    val uri: Uri = androidx.core.content.FileProvider.getUriForFile(
        context, "com.superbiz.app.fileprovider", file
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    com.superbiz.app.util.startIntentSafe(
        context, Intent.createChooser(intent, context.getString(R.string.st_share)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )
}

/** طباعة حقيقية عبر A4Print — بلا نشاط لا طباعة (صدق المنصة) */
private fun printStatement(context: android.content.Context, path: String, number: String) {
    val activity = context as? MainActivity
    val file = java.io.File(path)
    if (activity != null && file.exists()) {
        com.superbiz.app.print.A4Print.print(activity, file, number + ".pdf")
    } else {
        Toast.makeText(context, context.getString(R.string.st_file_missing), Toast.LENGTH_SHORT).show()
    }
}
