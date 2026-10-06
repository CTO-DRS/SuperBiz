package com.superbiz.app.ui.screens.statement

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.superbiz.app.R
import com.superbiz.app.data.db.AuditLogEntity
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.components.SubHeader
import com.superbiz.app.ui.screens.stringResourceCompat
import com.superbiz.app.ui.theme.glassColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [P18-c] سجل التدقيق (المسار: statement_audit) — عرض قراءة فقط لجدول audit_log
 * الإلحاقي: أحدث 200 سطر أو بتصفية فعل، وكل سطر يفكك تفاصيله «k=v» إلى أسطر.
 * تنسيق الوقت على نمط التطبيق القائم: SimpleDateFormat بـ Locale.US (نمط Dates.short).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AuditLogScreen(nav: NavHostController) {
    val context = LocalContext.current
    val g = glassColors()
    var entries by remember { mutableStateOf(listOf<AuditLogEntity>()) }
    var loading by remember { mutableStateOf(true) }
    var filter by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(filter) {
        val f = filter
        loading = true
        withContext(Dispatchers.IO) {
            entries = try {
                if (f == null)
                    StatementUiFacade.auditRecent(context.applicationContext, 200)
                else
                    StatementUiFacade.auditByAction(context.applicationContext, f, 200)
            } catch (_: Exception) { emptyList() }
        }
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 12.dp)) {
        SubHeader(stringResourceCompat(R.string.st2_audit_title), onBack = { nav.popBackStack() })

        // بطاقة الشرح: قراءة فقط وإلحاقي — كما هي حقيقة الجدول
        StHint(stringResourceCompat(R.string.st2_audit_readonly))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 8.dp)
        ) {
            StChip(stringResourceCompat(R.string.st2_filter_all), filter == null) { filter = null }
            AUDIT_ACTIONS.forEach { a ->
                StChip(actionLabel(a), filter == a) { filter = if (filter == a) null else a }
            }
        }

        if (loading) {
            Row(
                Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(color = g.accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            }
        } else if (entries.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            StHint(stringResourceCompat(R.string.st2_audit_empty))
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 10.dp)
            ) {
                items(entries.size) { i ->
                    val e = entries[i]
                    AuditRowCard(
                        entry = e,
                        expanded = expanded == e.id,
                        onToggle = { expanded = if (expanded == e.id) null else e.id }
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** بطاقة سطر تدقيق — الفعل بتسميته، والوقت منسّقاً، والتفاصيل قابلة للفك */
@Composable
private fun AuditRowCard(entry: AuditLogEntity, expanded: Boolean, onToggle: () -> Unit) {
    val g = glassColors()
    val label = actionLabel(entry.action)
    val detailsLabel = stringResourceCompat(R.string.st2_audit_details)

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        label,
                        fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        color = g.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        formatAuditTime(entry.ts),
                        fontSize = 11.sp, color = g.textSecondary
                    )
                }
                if (entry.details.isNotBlank()) {
                    IconButton(onClick = onToggle) {
                        Icon(
                            if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = detailsLabel, tint = g.textSecondary
                        )
                    }
                }
            }
            if (expanded && entry.details.isNotBlank()) {
                val pairs = formatAuditDetails(entry.details)
                if (pairs.isEmpty()) {
                    Text(entry.details, fontSize = 11.sp, color = g.textSecondary)
                } else {
                    pairs.forEach { (k, v) ->
                        Text(
                            (if (k.isBlank()) "• " else k + " = ") + v,
                            fontSize = 11.sp, color = g.textSecondary, maxLines = 2
                        )
                    }
                }
            }
        }
    }
}

/** حل مفتاح فعل التدقيق إلى تسمية — المجهول يُعرض بنصه الأصلي (fallback) */
@Composable
private fun actionLabel(action: String): String {
    val key = auditActionLabelKey(action)
    if (key == action) return action
    return when (key) {
        "st2_audit_act_issue" -> stringResourceCompat(R.string.st2_audit_act_issue)
        "st2_audit_act_reissue" -> stringResourceCompat(R.string.st2_audit_act_reissue)
        "st2_audit_act_send" -> stringResourceCompat(R.string.st2_audit_act_send)
        "st2_audit_act_delete" -> stringResourceCompat(R.string.st2_audit_act_delete)
        "st2_audit_act_auto" -> stringResourceCompat(R.string.st2_audit_act_auto)
        "st2_audit_act_rule_save" -> stringResourceCompat(R.string.st2_audit_act_rule_save)
        "st2_audit_act_rule_del" -> stringResourceCompat(R.string.st2_audit_act_rule_del)
        "st2_audit_act_rule_run" -> stringResourceCompat(R.string.st2_audit_act_rule_run)
        "st2_audit_act_tpl" -> stringResourceCompat(R.string.st2_audit_act_tpl)
        else -> action
    }
}

/** «dd/MM/yyyy HH:mm» — نسخة Locale.US كنمط Dates القائم (محرر عرض فقط) */
private val auditTimeFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US)

private fun formatAuditTime(ts: Long): String =
    runCatching { auditTimeFmt.format(Date(ts)) }.getOrDefault("")
