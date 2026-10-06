package com.superbiz.app.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.superbiz.app.AppGraph
import com.superbiz.app.MainActivity
import com.superbiz.app.R
import com.superbiz.app.print.ReportReady
import com.superbiz.app.print.ReportReadyDialog
import com.superbiz.app.print.ReportReceiptFactory
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.components.IconChip
import com.superbiz.app.ui.components.LineChart
import com.superbiz.app.ui.components.SectionTitle
import com.superbiz.app.ui.components.SubHeader
import com.superbiz.app.ui.theme.Amber
import com.superbiz.app.ui.theme.Cyan
import com.superbiz.app.ui.theme.GreenDeep
import com.superbiz.app.ui.theme.Vio
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.util.Dates
import com.superbiz.app.util.Money
import com.superbiz.app.vm.AppVM
import com.superbiz.app.vm.AutoVM
import com.superbiz.app.vm.SettingsVM
import com.superbiz.app.VMFactory
import kotlinx.coroutines.launch

@Composable
fun AutomationScreen(appVM: AppVM, settingsVM: SettingsVM, nav: NavHostController) {
    val activity = LocalContext.current as? MainActivity ?: return
    val vm: AutoVM = viewModel(factory = remember { VMFactory(activity) })
    val data by vm.data.collectAsState()
    val rules by vm.rules.collectAsState()
    // وظيفة 35 — نتيجة التشغيل الفوري (عدّاد التنبيهات المستحقة)
    val runRes by vm.runResult.collectAsState()
    val symbol by appVM.symbol.collectAsState()
    val settings by appVM.settings.collectAsState()
    val g = glassColors()
    val scope = rememberCoroutineScope()
    // التقرير الجاهز من زر «تجهيز وإرسال الآن» — نفس حوار الطباعة الثلاثي
    var readyA4 by remember { mutableStateOf<ReportReady?>(null) }

    // وظيفة 35 — Toast فوري بعدّاد نتيجة التشغيل (مرة واحدة لكل تشغيل)
    LaunchedEffect(runRes?.runId) {
        val r = runRes ?: return@LaunchedEffect
        val msg = if (r.total == 0) activity.getString(R.string.auto_run_none)
        else activity.getString(R.string.auto_run_toast, r.total)
        android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SubHeader(stringResourceCompat(R.string.automation_title))
        Text(
            stringResourceCompat(R.string.automation_desc),
            color = g.textSecondary, style = MaterialTheme.typography.bodySmall
        )

        // ── توقع التحصيل ──
        SectionTitle(stringResourceCompat(R.string.forecast_title))
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                val fc = data.forecast
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconChip(Icons.Rounded.Savings, androidx.compose.ui.graphics.Color.White, GreenDeep)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            stringResourceCompat(R.string.forecast_next7) + ": " +
                                Money.format(fc?.next7 ?: 0.0, symbol),
                            fontWeight = FontWeight.Bold, color = g.textPrimary
                        )
                        Text(
                            stringResourceCompat(R.string.forecast_next30) + ": " +
                                Money.format(fc?.next30 ?: 0.0, symbol),
                            fontWeight = FontWeight.SemiBold, color = g.accent2, fontSize = 13.sp
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                fc?.let {
                    LineChart(
                        values = it.dailyBuckets.map { p -> p.second },
                        color = Cyan,
                        modifier = Modifier.fillMaxWidth().height(100.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResourceCompat(R.string.forecast_based) +
                        " • " + ((fc?.fillRate ?: 0.0) * 100).toInt() + "%" +
                        " • trend: " + Money.num(fc?.trendPerWeek ?: 0.0) + "/w",
                    fontSize = 11.sp, color = g.textSecondary
                )
            }
        }

        // ── القواعد ──
        SectionTitle(stringResourceCompat(R.string.rules))
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                rules.forEach { rule ->
                    val title = stringResourceCompat(
                        when (rule.kind) {
                            "DUE_REMIND" -> R.string.rule_due_remind
                            "CHECK_REMIND" -> R.string.rule_check_remind
                            "INSTALLMENT_REMIND" -> R.string.rule_installment_remind
                            "LOW_STOCK" -> R.string.rule_low_stock
                            else -> R.string.rule_auto_backup
                        }
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(title, color = g.textPrimary, fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResourceCompat(R.string.last_run) + ": " +
                                    if (rule.lastRun > 0) Dates.short(rule.lastRun) else stringResourceCompat(R.string.never),
                                fontSize = 11.sp, color = g.textSecondary
                            )
                        }
                        if (rule.kind == "DUE_REMIND" || rule.kind == "CHECK_REMIND" || rule.kind == "INSTALLMENT_REMIND") {
                            Text(
                                "-" + rule.daysBefore,
                                color = g.accent2, fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable {
                                        vm.setDaysBefore(rule, (rule.daysBefore % 14) + 1)
                                    }
                                    .padding(horizontal = 8.dp),
                                fontSize = 15.sp
                            )
                        }
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = { vm.toggleRule(rule, it) },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = Vio,
                                checkedThumbColor = androidx.compose.ui.graphics.Color.White
                            )
                        )
                    }
                }
            }
        }

        // ── مركز الإرسال ──
        SectionTitle(stringResourceCompat(R.string.send_center))
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconChip(Icons.Rounded.Bolt, androidx.compose.ui.graphics.Color.White, Vio)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResourceCompat(R.string.queued_reminders) + " (${data.dueSoon.size})",
                        color = g.textPrimary, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    ActionPill(stringResourceCompat(R.string.run_now), GreenDeep) { vm.runNow() }
                }
                if (data.dueSoon.isEmpty()) {
                    Text(stringResourceCompat(R.string.empty_generic), color = g.textSecondary, fontSize = 13.sp)
                } else {
                    val context = LocalContext.current
                    data.dueSoon.take(6).forEach { (party, amt) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(party.name, color = g.textPrimary, modifier = Modifier.weight(1f))
                            Text(Money.formatP(amt, symbol), color = Amber, fontWeight = FontWeight.Bold)  // [P33-P8] قروش
                            if (party.phone.isNotBlank()) {
                                Spacer(Modifier.width(8.dp))
                                // قالب التذكير من موارد اللغة — يُحلّ بلغة التطبيق وقت الإرسال بدل النص العربي المكتوب
                                val msg = stringResourceCompat(
                                    R.string.reminder_template,
                                    party.name, Money.formatP(amt, symbol), appVM.businessLabel()  // [P33-P8] قروش
                                )
                                Text(
                                    "WhatsApp",
                                    color = Color(0xFF25D366),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    modifier = Modifier.clickable {
                                        val url = "https://wa.me/" + party.phone.replace(Regex("[^0-9+]"), "").removePrefix("+") +
                                            "?text=" + Uri.encode(msg)
                                        try {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                        } catch (e: Exception) {
                                            // التذكير الصامت الفاشل يفوّت التحصيل — إشعار فوري
                                            android.widget.Toast.makeText(context, R.string.whatsapp_open_failed, android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                // وظيفة 35 — بطاقة حالة نتيجة التشغيل الفوري (من Summary الحقيقية)
                runRes?.let { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResourceCompat(R.string.auto_run_result),
                            fontWeight = FontWeight.Bold, color = g.accent2, fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            stringResourceCompat(R.string.auto_run_total, r.total),
                            color = g.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp
                        )
                    }
                    Text(
                        stringResourceCompat(R.string.auto_run_counts, r.dueDebts, r.checks, r.installments, r.lowStock),
                        fontSize = 12.sp, color = g.textSecondary
                    )
                }
            }
        }

        // ── جدولة التقرير A4 () ──
        SectionTitle(stringResourceCompat(R.string.sched_title))
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconChip(Icons.Rounded.Schedule, androidx.compose.ui.graphics.Color.White, Cyan)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResourceCompat(R.string.sched_desc),
                        color = g.textSecondary, fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    stringResourceCompat(R.string.sched_freq),
                    color = g.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill(stringResourceCompat(R.string.autoback_off), settings.reportScheduleDays == 0) { settingsVM.setReportScheduleDays(0) }
                    FilterPill(stringResourceCompat(R.string.autoback_daily), settings.reportScheduleDays == 1) { settingsVM.setReportScheduleDays(1) }
                    FilterPill(stringResourceCompat(R.string.autoback_weekly), settings.reportScheduleDays == 7) { settingsVM.setReportScheduleDays(7) }
                }
                Text(
                    stringResourceCompat(R.string.sched_hour),
                    color = g.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill("8:00", settings.reportScheduleHour == 8) { settingsVM.setReportScheduleHour(8) }
                    FilterPill("12:00", settings.reportScheduleHour == 12) { settingsVM.setReportScheduleHour(12) }
                    FilterPill("16:00", settings.reportScheduleHour == 16) { settingsVM.setReportScheduleHour(16) }
                    FilterPill("20:00", settings.reportScheduleHour == 20) { settingsVM.setReportScheduleHour(20) }
                }
                Text(
                    stringResourceCompat(R.string.sched_channel),
                    color = g.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill(stringResourceCompat(R.string.sched_whatsapp), settings.reportScheduleChannel == "whatsapp") { settingsVM.setReportScheduleChannel("whatsapp") }
                    FilterPill(stringResourceCompat(R.string.sched_email), settings.reportScheduleChannel == "email") { settingsVM.setReportScheduleChannel("email") }
                }
                // ── : المستلم الافتراضي (بريد العميل/المالك المحفوظ) ──
                Text(
                    stringResourceCompat(R.string.sched_recipient),
                    color = g.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp
                )
                val savedRcpt = settings.reportRecipient
                var rcptText by remember(savedRcpt) { mutableStateOf(savedRcpt) }
                var rcptBad by remember { mutableStateOf(false) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = rcptText,
                        onValueChange = {
                            rcptText = it
                            rcptBad = false
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text(stringResourceCompat(R.string.sched_recipient_hint), fontSize = 12.sp) },
                        isError = rcptBad,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        textStyle = LocalTextStyle.current.copy(color = g.textPrimary, fontSize = 13.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cyan,
                            unfocusedBorderColor = g.textSecondary.copy(alpha = 0.35f),
                            cursorColor = Cyan
                        )
                    )
                    ActionPill(stringResourceCompat(R.string.sched_save), GreenDeep) {
                        val t = rcptText.trim()
                        if (t.isEmpty() || com.superbiz.app.work.EmailPolicy.normalize(t) != null) {
                            settingsVM.setReportRecipient(t)
                        } else {
                            rcptBad = true
                        }
                    }
                }
                Text(
                    when {
                        rcptBad -> stringResourceCompat(R.string.sched_recipient_bad)
                        savedRcpt.isNotBlank() -> stringResourceCompat(R.string.sched_recipient_saved, savedRcpt)
                        settings.email.isNotBlank() -> stringResourceCompat(R.string.sched_recipient_fallback, settings.email)
                        else -> stringResourceCompat(R.string.sched_recipient_note)
                    },
                    fontSize = 11.sp,
                    color = when {
                        rcptBad -> Amber
                        savedRcpt.isNotBlank() -> g.accent2
                        else -> g.textSecondary
                    }
                )
                if (settings.email.isNotBlank() && savedRcpt != settings.email) {
                    FilterPill(
                        stringResourceCompat(R.string.sched_use_profile, settings.email),
                        selected = false
                    ) {
                        settingsVM.setReportRecipient(settings.email)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResourceCompat(
                            R.string.sched_last,
                            if (settings.lastScheduledReport > 0) Dates.short(settings.lastScheduledReport) else "—"
                        ),
                        fontSize = 11.sp, color = g.textSecondary, modifier = Modifier.weight(1f)
                    )
                    ActionPill(stringResourceCompat(R.string.sched_now), GreenDeep) {
                        scope.launch {
                            try {
                                // تجهيز التقرير A4 على Dispatchers.IO — الحوار يظهر بعد العودة للخيط الرئيسي
                                val ready = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    val graph = AppGraph.from(activity.application)
                                    val to = System.currentTimeMillis()
                                    val from = to - 30L * 86_400_000L
                                    val inc = graph.reports.incomeStatement(from, to)
                                    val cash = graph.reports.cashBalance()
                                    val s2 = graph.settings.snapshot()
                                    val business = s2.businessName.ifBlank { activity.getString(R.string.business_default) }
                                    val periodText = activity.getString(R.string.period_days, 30)
                                    val file = com.superbiz.app.pdf.A4Report.financial(
                                        activity, business, symbol, appVM.avatarBitmap(),
                                        periodText = periodText,
                                        // [P33-P8] قائمة الدخل قروش — واجهة PDF قروش كذلك
                                        revenue = inc.revenue,
                                        otherIncome = inc.otherIncome,
                                        cogs = inc.cogs,
                                        expenses = inc.expenses,
                                        cash = cash,
                                        topCustomers = graph.reports.topCustomers(from, to, 500),
                                        topProducts = graph.reports.topProducts(from, to, 500),
                                        aging = graph.reports.agingBuckets(),
                                        trial = graph.reports.trialBalance()
                                    )
                                    ReportReady(
                                        file, activity.getString(R.string.a4_financial_title),
                                        ReportReceiptFactory.financial(
                                            activity, business, symbol, periodText,
                                            // [P33-P8] قائمة الدخل قروش — الإيصال الحراري قروش كذلك
                                            inc.revenue, inc.otherIncome, inc.cogs, inc.expenses, cash
                                        )
                                    )
                                }
                                readyA4 = ready
                            } catch (e: Exception) {
                                // تسجيل فشل تجهيز التقرير بدل الصمت
                                com.superbiz.app.core.ErrorCenter.warn(
                                    "AutomationScreen", "A4 report prep failed: ${e::class.simpleName}: ${e.message}"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    readyA4?.let { ready ->
        ReportReadyDialog(ready) { readyA4 = null }
    }
}
