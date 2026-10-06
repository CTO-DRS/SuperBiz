package com.superbiz.app.ui.screens.statement

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.superbiz.app.R
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.db.StatementRuleEntity
import com.superbiz.app.domain.statement.SmtpConfig
import com.superbiz.app.domain.statement.SmtpSecurity
import com.superbiz.app.domain.statement.StatementPeriodPreset
import com.superbiz.app.domain.statement.StatementRulesEngine
import com.superbiz.app.ui.components.BizField
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.components.SubHeader
import com.superbiz.app.ui.components.numberFieldOptions
import com.superbiz.app.ui.nav.Routes
import com.superbiz.app.ui.screens.stringResourceCompat
import com.superbiz.app.ui.theme.Amber
import com.superbiz.app.ui.theme.Cyan
import com.superbiz.app.ui.theme.GreenDeep
import com.superbiz.app.ui.theme.RedDeep
import com.superbiz.app.ui.theme.Vio
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.util.Money
import com.superbiz.app.vm.AppVM
import com.superbiz.app.vm.DebtsVM
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [P18-settings] شاشة إعدادات منظومة كشف الحساب (المسار: statement_settings).
 *
 * ثلاثة أقسام فوق النمط الزجاجي القائم (GlassCard/glassColors — الظلام تلقائي عبر
 * glassColors، وألوان الحالة الثابتة فقط استثناء معلن كما في 18-c):
 *  1) SMTP: نموذج كامل يُخزَّن عبر StatementUiFacade.saveSmtpPrefs (StatementPrefs)
 *     + بريد تجريبي حقيقي فوق smtpConfigOrNull + SmtpClient.send (عقد 18-a).
 *  2) الإرسال التلقائي: autoSendEnabled/autoRetryMax — حفظ فوري عند كل تغيير.
 *  3) القواعد: قائمة حية من facade.rules() مع تفعيل/حذف، ومحرر قاعدة كامل في
 *     BottomSheet يبني StatementRuleEntity وفق عقد المحرك حرفياً:
 *     - SINGLE يُخزَّن في partyIdsJson بصيغة [<id>] (قرار ④ في StatementRulesEngine).
 *     - CUSTOM يخزن أيامه في eventFlagsJson بصيغة {"days":N} (قرار ③).
 *     - EVENT يخزن أعلامه مصفوفة JSON يقرؤها parseEventFlags.
 *     - القاعدة الدورية تُحسب nextRunAt الأولى عبر StatementRulesEngine.nextRunAt —
 *       بلاها لا يسأل dueRules(now) عن القاعدة أبداً (استعلام العامل المجدول).
 * كل الملامسة للعقد عبر StatementUiFacade فقط — لا repo مباشرة.
 */

/** خيار قالب في محرر القاعدة — id=null يعني افتراضي النظام (يُخزن null في templateId) */
private data class StTplOption(val id: String?, val label: String)

private val st3TimeFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StatementSettingsScreen(appVM: AppVM, nav: NavHostController) {
    val context = LocalContext.current
    val appCtx = context.applicationContext
    val g = glassColors()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val ar = appVM.settings.collectAsState().value.language == "ar"

    // ── نموذج SMTP (يُحمل مرة من StatementPrefs عبر الواجهة) ──
    var form by remember { mutableStateOf(StatementUiFacade.smtpPrefs(appCtx)) }
    var portText by remember { mutableStateOf(form.port.toString()) }
    var portTouched by rememberSaveable { mutableStateOf(false) }
    var showPass by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var testTo by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    // ── القواعد: تدفق حي من القاعدة عبر الواجهة ──
    val rulesFlow = remember { StatementUiFacade.rules(appCtx) }
    val rules by rulesFlow.collectAsState(initial = emptyList())
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var deleteFor by remember { mutableStateOf<StatementRuleEntity?>(null) }

    // قائمة الأطراف لمنتقي النطاق — المسار الجاهز نفسه لشاشة الذمم (DebtsVM.parties)
    val partiesVM: DebtsVM = viewModel()
    val parties by partiesVM.parties.collectAsState()

    // خيارات القوالب (مدمجة + مخصصة) — تُحمّل مرة وتُمرر للمحرر
    val tplDefaultLabel = stringResourceCompat(R.string.st3_template_default)
    var tplOptions by remember { mutableStateOf(listOf(StTplOption(null, tplDefaultLabel))) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val opts = runCatching {
                StatementUiFacade.allTemplatesOf(appCtx).map { def ->
                    StTplOption(def.id, if (ar) def.nameAr else def.nameEn)
                }
            }.getOrDefault(emptyList())
            tplOptions = listOf(StTplOption(null, tplDefaultLabel)) + opts
        }
    }

    // ── إجراءات حقيقية ──

    fun saveSettings() {
        if (saving) return   // منع النقر المزدوج
        saving = true
        val toSave = form.copy(port = portText.toIntOrNull() ?: 0)
        scope.launch {
            withContext(Dispatchers.IO) {
                StatementUiFacade.saveSmtpPrefs(appCtx, toSave)
            }
            form = toSave
            saving = false
            snackbar.showSnackbar(appCtx.getString(R.string.st3_saved_ok))
        }
    }

    /**
     * بريد تجريبي حقيقي: يُحفظ النموذج أولاً ثم يُبنى الإعداد عبر smtpConfigOrNull
     * (null ⇒ سبب صادق) ويُرسل عبر SmtpClient داخل runCatching على IO —
     * النتيجة (نجاح أو رسالة SmtpException) في SnackBar بلا تجميل.
     */
    fun sendTestEmail() {
        if (sending) return   // منع النقر المزدوج أثناء الإرسال
        val to = testTo.trim()
        val valid = to.contains("@") && !to.contains(" ") && to.length >= 6
        if (!valid) {
            scope.launch { snackbar.showSnackbar(appCtx.getString(R.string.st3_email_invalid)) }
            return
        }
        sending = true
        val subject = appCtx.getString(R.string.st3_test_subject)
        val body = appCtx.getString(R.string.st3_test_body)
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    StatementUiFacade.saveSmtpPrefs(appCtx, form.copy(port = portText.toIntOrNull() ?: 0))
                    val cfg = StatementUiFacade.smtpConfigOrNull(appCtx)
                        ?: throw IllegalStateException(appCtx.getString(R.string.st3_smtp_not_ready))
                    com.superbiz.app.domain.statement.SmtpClient.send(
                        cfg, listOf(to), subject, body, null, null
                    )
                }
            }
            sending = false
            snackbar.showSnackbar(
                res.fold(
                    onSuccess = { appCtx.getString(R.string.st3_test_ok) },
                    onFailure = {
                        appCtx.getString(
                            R.string.st3_test_fail,
                            (it.message ?: it.javaClass.simpleName).take(120)
                        )
                    }
                )
            )
        }
    }

    /** قسم الإرسال التلقائي يُحفظ فورياً عند كل تغيير (prefs رخيصة — لا زر حفظ ثانياً) */
    fun pushAutoPrefs(f: com.superbiz.app.domain.statement.SmtpPrefsUi) {
        form = f
        scope.launch(Dispatchers.IO) { StatementUiFacade.saveSmtpPrefs(appCtx, f) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 12.dp)) {
            SubHeader(stringResourceCompat(R.string.st3_settings_title), onBack = { nav.popBackStack() })

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f).padding(bottom = 12.dp)
            ) {
                // ══════════ قسم SMTP ══════════
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                stringResourceCompat(R.string.st3_sec_smtp),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold, color = g.textPrimary
                            )
                            Text(
                                stringResourceCompat(R.string.st3_sec_smtp_sub),
                                fontSize = 11.sp, color = g.textSecondary
                            )
                            GlassSwitchRow(stringResourceCompat(R.string.st3_smtp_enabled), form.smtpEnabled) {
                                form = form.copy(smtpEnabled = it)
                            }
                            BizField(form.host, { form = form.copy(host = it) }, stringResourceCompat(R.string.st3_smtp_host))
                            BizField(
                                portText,
                                { v -> portTouched = true; portText = v },
                                stringResourceCompat(R.string.st3_smtp_port),
                                keyboard = numberFieldOptions()
                            )
                            // رقائق الأمان الثلاثة — تغيير النمط يضبط المنفذ الافتراضي تلقائياً
                            // ما لم يعدّله المستخدم يدوياً (portTouched)
                            Text(
                                stringResourceCompat(R.string.st3_smtp_security),
                                fontSize = 12.sp, color = g.textSecondary
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(SmtpSecurity.SSL_TLS, SmtpSecurity.STARTTLS, SmtpSecurity.NONE).forEach { sec ->
                                    StChip(securityLabel(sec.name), form.security == sec.name) {
                                        val np = if (portTouched) (portText.toIntOrNull() ?: defaultPortFor(sec))
                                        else defaultPortFor(sec)
                                        portText = np.toString()
                                        form = form.copy(security = sec.name, port = np)
                                    }
                                }
                            }
                            GlassSwitchRow(stringResourceCompat(R.string.st3_smtp_auth), form.authEnabled) {
                                form = form.copy(authEnabled = it)
                            }
                            if (form.authEnabled) {
                                BizField(form.user, { form = form.copy(user = it) }, stringResourceCompat(R.string.st3_smtp_user))
                                GlassPassField(
                                    value = form.pass,
                                    onValue = { form = form.copy(pass = it) },
                                    label = stringResourceCompat(R.string.st3_smtp_pass),
                                    show = showPass,
                                    onToggleShow = { showPass = !showPass }
                                )
                            }
                            BizField(form.from, { form = form.copy(from = it) }, stringResourceCompat(R.string.st3_smtp_from))
                            BizField(form.fromName, { form = form.copy(fromName = it) }, stringResourceCompat(R.string.st3_smtp_from_name))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StAction(stringResourceCompat(R.string.st3_save), GreenDeep) { saveSettings() }
                                Spacer(Modifier.width(10.dp))
                                if (saving) {
                                    CircularProgressIndicator(color = g.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResourceCompat(R.string.st3_saving), fontSize = 11.sp, color = g.textSecondary)
                                }
                            }
                        }
                    }
                }

                // ══════════ البريد التجريبي ══════════
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                stringResourceCompat(R.string.st3_test_title),
                                fontWeight = FontWeight.Bold, fontSize = 13.sp, color = g.textPrimary
                            )
                            Text(
                                stringResourceCompat(R.string.st3_test_sub),
                                fontSize = 11.sp, color = g.textSecondary
                            )
                            BizField(testTo, { testTo = it }, stringResourceCompat(R.string.st3_test_to))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // زر يختفي أثناء الإرسال — منع النقر المزدوج فعلياً لا شكلياً
                                if (!sending) {
                                    StAction(stringResourceCompat(R.string.st3_test_send), Vio) { sendTestEmail() }
                                } else {
                                    CircularProgressIndicator(color = g.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }

                // ══════════ قسم الإرسال التلقائي ══════════
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                stringResourceCompat(R.string.st3_sec_auto),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold, color = g.textPrimary
                            )
                            GlassSwitchRow(stringResourceCompat(R.string.st3_auto_enabled), form.autoSendEnabled) {
                                pushAutoPrefs(form.copy(autoSendEnabled = it))
                            }
                            Text(
                                stringResourceCompat(R.string.st3_auto_retry),
                                fontSize = 12.sp, color = g.textSecondary
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Slider(
                                    value = form.autoRetryMax.toFloat(),
                                    onValueChange = { v -> form = form.copy(autoRetryMax = v.toInt().coerceIn(1, 5)) },
                                    onValueChangeFinished = {
                                        scope.launch(Dispatchers.IO) { StatementUiFacade.saveSmtpPrefs(appCtx, form) }
                                    },
                                    valueRange = 1f..5f,
                                    steps = 3,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    stringResourceCompat(R.string.st3_auto_retry_val, form.autoRetryMax),
                                    fontSize = 12.sp, fontWeight = FontWeight.Bold, color = g.textPrimary
                                )
                            }
                            StHint(stringResourceCompat(R.string.st3_auto_note))
                            StHint(stringResourceCompat(R.string.st3_auto_tz), tint = Cyan)
                        }
                    }
                }

                // ══════════ قسم القواعد ══════════
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResourceCompat(R.string.st3_sec_rules),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold, color = g.textPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        StAction(stringResourceCompat(R.string.st3_rule_new), GreenDeep) { editorOpen = true }
                    }
                }
                if (rules.isEmpty()) {
                    item { StHint(stringResourceCompat(R.string.st3_rules_empty)) }
                }
                items(rules.size) { i ->
                    val r = rules[i]
                    RuleRowCard(
                        rule = r,
                        onToggle = {
                            scope.launch(Dispatchers.IO) {
                                StatementUiFacade.setRuleEnabled(appCtx, r.id, !r.enabled)
                            }
                        },
                        onDelete = { deleteFor = r }
                    )
                }

                // ══════════ كروت الدخول ══════════
                item {
                    EntryCard(
                        stringResourceCompat(R.string.st3_card_deliveries),
                        stringResourceCompat(R.string.st3_card_deliveries_sub),
                        Cyan
                    ) { nav.navigate(Routes.STATEMENT_DELIVERIES) }
                }
                item {
                    EntryCard(
                        stringResourceCompat(R.string.st3_card_audit),
                        stringResourceCompat(R.string.st3_card_audit_sub),
                        Vio
                    ) { nav.navigate(Routes.STATEMENT_AUDIT) }
                }
                item {
                    EntryCard(
                        stringResourceCompat(R.string.st3_card_signatures),
                        stringResourceCompat(R.string.st3_card_signatures_sub),
                        GreenDeep
                    ) { nav.navigate(Routes.SIGNATURES) }
                }
                item {
                    EntryCard(
                        stringResourceCompat(R.string.st3_card_stamps),
                        stringResourceCompat(R.string.st3_card_stamps_sub),
                        Amber
                    ) { nav.navigate(Routes.STAMPS) }
                }
                // [P19] بطاقة التحقق من الكشف — نفس الشاشة التي يفتحها QR المطبوع
                item {
                    EntryCard(
                        stringResourceCompat(R.string.st4_verify_title),
                        stringResourceCompat(R.string.st4_verify_hint),
                        Cyan
                    ) { nav.navigate(Routes.STATEMENT_VERIFY + "?vid=") }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    // ── تأكيد حذف القاعدة ──
    deleteFor?.let { target ->
        val ruleName = target.name
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            containerColor = g.surfaceStrong,
            title = { Text(stringResourceCompat(R.string.delete), color = RedDeep) },
            text = {
                Text(
                    stringResourceCompat(R.string.st3_rule_delete_confirm, ruleName),
                    color = g.textSecondary, fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = target.id
                    deleteFor = null
                    scope.launch {
                        withContext(Dispatchers.IO) { StatementUiFacade.deleteRule(appCtx, id) }
                        snackbar.showSnackbar(appCtx.getString(R.string.st3_rule_deleted))
                    }
                }) {
                    Text(stringResourceCompat(R.string.delete), color = RedDeep, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteFor = null }) {
                    Text(stringResourceCompat(R.string.cancel), color = g.textSecondary)
                }
            }
        )
    }

    // ── محرر القاعدة (BottomSheet نموذج كامل) ──
    if (editorOpen) {
        RuleEditorSheet(
            parties = parties,
            tplOptions = tplOptions,
            onDismiss = { editorOpen = false },
            onSave = { entity ->
                scope.launch {
                    withContext(Dispatchers.IO) { StatementUiFacade.saveRule(appCtx, entity) }
                    editorOpen = false
                    snackbar.showSnackbar(appCtx.getString(R.string.st3_rule_saved))
                }
            }
        )
    }
}

// ═══════════ صف قاعدة ═══════════

@Composable
private fun RuleRowCard(rule: StatementRuleEntity, onToggle: () -> Unit, onDelete: () -> Unit) {
    val g = glassColors()
    val freqStr = freqLabel(rule.frequency)
    val chanStr = channelLabel(rule.channel)
    val modeStr = modeLabel(rule.partyMode)
    val enabledStr = stringResourceCompat(if (rule.enabled) R.string.st3_rule_enabled else R.string.st3_rule_disabled)
    // سطر الحالة: الحدثي يُفحص كل دورة، والدوري له موعد قادم محسوب
    val scheduleLine = if (rule.frequency.equals(StatementRulesEngine.FREQ_EVENT, ignoreCase = true)) {
        stringResourceCompat(R.string.st3_rule_event_mode)
    } else {
        val ts = rule.nextRunAt
        if (ts != null && ts > 0)
            stringResourceCompat(R.string.st3_rule_next_run, runCatching { st3TimeFmt.format(Date(ts)) }.getOrDefault("—"))
        else "—"
    }

    GlassCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    rule.name,
                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    color = g.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    "$freqStr • $chanStr • $modeStr • $enabledStr",
                    fontSize = 11.sp, color = g.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(scheduleLine, fontSize = 11.sp, color = g.textSecondary)
            }
            Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, stringResourceCompat(R.string.delete), tint = RedDeep, modifier = Modifier.size(19.dp))
            }
        }
    }
}

// ═══════════ محرر القاعدة ═══════════

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RuleEditorSheet(
    parties: List<Party>,
    tplOptions: List<StTplOption>,
    onDismiss: () -> Unit,
    onSave: (StatementRuleEntity) -> Unit
) {
    val g = glassColors()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // رسائل التحقق تُحل داخل التركيب — لا composable داخل اللامدات
    val errName = stringResourceCompat(R.string.st3_rule_name_required)
    val errParty = stringResourceCompat(R.string.st3_rule_needs_party)
    val errEvent = stringResourceCompat(R.string.st3_rule_needs_event)
    val errTime = stringResourceCompat(R.string.st3_rule_time_invalid)
    val errDay = stringResourceCompat(R.string.st3_rule_day_invalid)
    val errDays = stringResourceCompat(R.string.st3_rule_days_invalid)
    val errThreshold = stringResourceCompat(R.string.st3_rule_threshold_invalid)
    val labelName = stringResourceCompat(R.string.st3_rule_name)
    val labelMode = stringResourceCompat(R.string.st3_rule_mode)
    val labelFreq = stringResourceCompat(R.string.st3_rule_freq)
    val labelWeekday = stringResourceCompat(R.string.st3_weekday)
    val labelPeriod = stringResourceCompat(R.string.st3_period)
    val labelTpl = stringResourceCompat(R.string.st3_rule_template)
    val labelChannel = stringResourceCompat(R.string.st3_rule_channel)
    val labelEvents = stringResourceCompat(R.string.st3_sec_events)
    val labelPickParties = stringResourceCompat(R.string.st3_pick_parties)
    val labelPickParty = stringResourceCompat(R.string.st3_pick_party)
    val labelNoParties = stringResourceCompat(R.string.st3_no_parties)
    val txtCancel = stringResourceCompat(R.string.cancel)
    val txtConfirm = stringResourceCompat(R.string.confirm)
    val txtSave = stringResourceCompat(R.string.st3_rule_save)
    val labelDayOfMonth = stringResourceCompat(R.string.st3_day_of_month)

    var name by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("ALL") }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var singleId by remember { mutableStateOf<Long?>(null) }
    var freq by remember { mutableStateOf(StatementRulesEngine.FREQ_MONTHLY) }
    var weekday by remember { mutableStateOf(1) }                       // 1=الأحد (Calendar.DAY_OF_WEEK)
    var dayText by remember { mutableStateOf("1") }
    var hourText by remember { mutableStateOf("8") }
    var minuteText by remember { mutableStateOf("0") }
    var period by remember { mutableStateOf(StatementPeriodPreset.THIS_MONTH.name) }
    var tplId by remember { mutableStateOf<String?>(null) }
    var channel by remember { mutableStateOf("EMAIL") }
    var events by remember { mutableStateOf(setOf<String>()) }
    var thresholdText by remember { mutableStateOf("") }
    var daysText by remember { mutableStateOf("30") }
    var pickPartiesOpen by remember { mutableStateOf(false) }
    var singleMenuOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    /**
     * بناء الكيان بعقد المحرك حرفياً — null يعني فشل تحقق وerror ضُبط برسالة صادقة.
     * القاعدة الدورية تُحسب لها nextRunAt الأولى هنا وإلا أبعدتها dueRules عن القائمة للأبد.
     */
    fun buildEntity(): StatementRuleEntity? {
        val nm = name.trim()
        if (nm.isEmpty()) { error = errName; return null }
        val idsJson: String = when (mode) {
            "ALL" -> ""
            "SELECTED" -> {
                if (selectedIds.isEmpty()) { error = errParty; return null }
                selectedIds.sorted().joinToString(",", "[", "]")
            }
            else -> {
                // قرار ④: طرف SINGLE الوحيد يُخزن في partyIdsJson بصيغة [<id>] وإلا فالقاعدة بلا أطراف
                val s = singleId
                if (s == null || s <= 0) { error = errParty; return null }
                "[$s]"
            }
        }
        val h = hourText.trim().toIntOrNull()
        val m = minuteText.trim().toIntOrNull()
        if (h == null || h !in 0..23 || m == null || m !in 0..59) { error = errTime; return null }
        val f = freq
        val wd = if (f == StatementRulesEngine.FREQ_WEEKLY) weekday else null
        val dom: Int? = if (f == StatementRulesEngine.FREQ_MONTHLY ||
            f == StatementRulesEngine.FREQ_QUARTERLY || f == StatementRulesEngine.FREQ_YEARLY
        ) {
            val d = dayText.trim().toIntOrNull()
            if (d == null || d !in 1..31) { error = errDay; return null }
            d
        } else null
        val flagsJson: String?
        // [P33-P8] عتبة القاعدة قروش Long (StatementRuleEntity.threshold: Long?) — الإدخال ريال ويُحوَّل عبر toPiasters
        val thr: Long?
        if (f == StatementRulesEngine.FREQ_EVENT) {
            if (events.isEmpty()) { error = errEvent; return null }
            flagsJson = org.json.JSONArray(events.toList()).toString()
            thr = if (StatementRulesEngine.RuleEvent.THRESHOLD.name in events) {
                val t = thresholdText.trim().toDoubleOrNull()
                if (t == null || !t.isFinite() || t < 0.0) { error = errThreshold; return null }
                Money.toPiasters(t)
            } else null
        } else {
            flagsJson = if (f == StatementRulesEngine.FREQ_CUSTOM) {
                // قرار ③ حرفياً: {"days":N} — الصيغة التي يفكها parseCustomDays
                val d = daysText.trim().toIntOrNull()
                if (d == null || d !in 1..3650) { error = errDays; return null }
                "{\"days\":$d}"
            } else null
            thr = null
        }
        val next = if (f == StatementRulesEngine.FREQ_EVENT) null else {
            val snap = StatementRulesEngine.RuleSnapshot(
                id = 0, partyMode = mode, partyIdsJson = idsJson, frequency = f,
                weekday = wd, dayOfMonth = dom, hour = h, minute = m,
                periodPreset = period, channel = channel, eventFlagsJson = flagsJson,
                // [P33-P8] محرك القواعد لم يُرحَّل بعد — عتبته ريال Double فالتحويل عند الحد
                threshold = thr?.let { Money.fromPiasters(it) }, lastRunAt = null, nextRunAt = null
            )
            StatementRulesEngine.nextRunAt(snap, System.currentTimeMillis(), TimeZone.getDefault())
                .takeIf { it > 0 }
        }
        return StatementRuleEntity(
            name = nm, enabled = true, partyMode = mode, partyIdsJson = idsJson,
            frequency = f, weekday = wd, dayOfMonth = dom, hour = h, minute = m,
            periodPreset = period, templateId = tplId, signatureId = null, stampId = null,
            // [P33-P8] العتبة تُخزَّن قروش Long
            channel = channel, eventFlagsJson = flagsJson, threshold = thr,
            lastRunAt = null, nextRunAt = next
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = g.surfaceStrong) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                stringResourceCompat(R.string.st3_rule_editor),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = g.textPrimary
            )
            BizField(name, { name = it }, labelName)

            // نطاق الأطراف
            Text(labelMode, fontSize = 12.sp, color = g.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StChip(stringResourceCompat(R.string.st3_mode_all), mode == "ALL") { mode = "ALL" }
                StChip(stringResourceCompat(R.string.st3_mode_selected), mode == "SELECTED") { mode = "SELECTED" }
                StChip(stringResourceCompat(R.string.st3_mode_single), mode == "SINGLE") { mode = "SINGLE" }
            }
            if (mode == "SELECTED") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StAction(labelPickParties, Cyan) { pickPartiesOpen = true }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResourceCompat(R.string.st3_mode_parties_label, selectedIds.size),
                        fontSize = 11.sp, color = g.textSecondary
                    )
                }
            }
            if (mode == "SINGLE") {
                if (parties.isEmpty()) {
                    StHint(labelNoParties)
                } else {
                    Box {
                        val singleName = parties.firstOrNull { it.id == singleId }?.name
                        StChip(singleName ?: labelPickParty, selected = singleName != null) { singleMenuOpen = true }
                        DropdownMenu(expanded = singleMenuOpen, onDismissRequest = { singleMenuOpen = false }) {
                            parties.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(p.name, fontSize = 13.sp, color = g.textPrimary, maxLines = 1) },
                                    onClick = { singleId = p.id; singleMenuOpen = false }
                                )
                            }
                        }
                    }
                }
            }

            // التكرار
            Text(labelFreq, fontSize = 12.sp, color = g.textSecondary)
            StDropdownChip(
                freqLabel(freq),
                listOf(
                    stringResourceCompat(R.string.st3_freq_daily) to StatementRulesEngine.FREQ_DAILY,
                    stringResourceCompat(R.string.st3_freq_weekly) to StatementRulesEngine.FREQ_WEEKLY,
                    stringResourceCompat(R.string.st3_freq_monthly) to StatementRulesEngine.FREQ_MONTHLY,
                    stringResourceCompat(R.string.st3_freq_quarterly) to StatementRulesEngine.FREQ_QUARTERLY,
                    stringResourceCompat(R.string.st3_freq_yearly) to StatementRulesEngine.FREQ_YEARLY,
                    stringResourceCompat(R.string.st3_freq_custom) to StatementRulesEngine.FREQ_CUSTOM,
                    stringResourceCompat(R.string.st3_freq_event) to StatementRulesEngine.FREQ_EVENT
                )
            ) { freq = it }
            if (freq == StatementRulesEngine.FREQ_WEEKLY) {
                Text(labelWeekday, fontSize = 12.sp, color = g.textSecondary)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    (1..7).forEach { d ->
                        // 1..7 = Calendar.DAY_OF_WEEK (الأحد..السبت) — عقد RuleSnapshot.weekday
                        StChip(dowLabel(d), weekday == d) { weekday = d }
                    }
                }
            }
            if (freq == StatementRulesEngine.FREQ_MONTHLY ||
                freq == StatementRulesEngine.FREQ_QUARTERLY || freq == StatementRulesEngine.FREQ_YEARLY
            ) {
                BizField(dayText, { dayText = it }, labelDayOfMonth, keyboard = numberFieldOptions())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BizField(
                    hourText, { hourText = it }, stringResourceCompat(R.string.st3_time_hour),
                    modifier = Modifier.weight(1f), keyboard = numberFieldOptions()
                )
                BizField(
                    minuteText, { minuteText = it }, stringResourceCompat(R.string.st3_time_minute),
                    modifier = Modifier.weight(1f), keyboard = numberFieldOptions()
                )
            }

            // فترة الكشف — CUSTOM مستبعدة عمداً (عقد المحرك: قواعد AUTO بفترة CUSTOM لا معنى لها)
            Text(labelPeriod, fontSize = 12.sp, color = g.textSecondary)
            StDropdownChip(
                periodLabel(period),
                listOf(
                    stringResourceCompat(R.string.st3_period_today) to StatementPeriodPreset.TODAY.name,
                    stringResourceCompat(R.string.st3_period_this_week) to StatementPeriodPreset.THIS_WEEK.name,
                    stringResourceCompat(R.string.st3_period_last_week) to StatementPeriodPreset.LAST_WEEK.name,
                    stringResourceCompat(R.string.st3_period_this_month) to StatementPeriodPreset.THIS_MONTH.name,
                    stringResourceCompat(R.string.st3_period_last_month) to StatementPeriodPreset.LAST_MONTH.name,
                    stringResourceCompat(R.string.st3_period_l3m) to StatementPeriodPreset.LAST_3_MONTHS.name,
                    stringResourceCompat(R.string.st3_period_l6m) to StatementPeriodPreset.LAST_6_MONTHS.name,
                    stringResourceCompat(R.string.st3_period_this_year) to StatementPeriodPreset.THIS_YEAR.name,
                    stringResourceCompat(R.string.st3_period_last_year) to StatementPeriodPreset.LAST_YEAR.name
                )
            ) { period = it }

            // القالب — null يعني افتراضي النظام (resolveStyle في العامل يحسمه)
            Text(labelTpl, fontSize = 12.sp, color = g.textSecondary)
            StDropdownChip(
                tplOptions.firstOrNull { it.id == tplId }?.label ?: stringResourceCompat(R.string.st3_template_default),
                tplOptions.map { it.label to (it.id ?: "") }
            ) { picked -> tplId = picked.ifBlank { null } }

            // قناة التسليم
            Text(labelChannel, fontSize = 12.sp, color = g.textSecondary)
            StDropdownChip(
                channelLabel(channel),
                listOf(
                    stringResourceCompat(R.string.st3_ch_email) to "EMAIL",
                    stringResourceCompat(R.string.st3_ch_smtp) to "SMTP",
                    stringResourceCompat(R.string.st3_ch_whatsapp) to "WHATSAPP",
                    stringResourceCompat(R.string.st3_ch_print) to "PRINT",
                    stringResourceCompat(R.string.st3_ch_share) to "SHARE"
                )
            ) { channel = it }

            // أعلام الأحداث + عتبة الرصيد عند THRESHOLD
            if (freq == StatementRulesEngine.FREQ_EVENT) {
                Text(labelEvents, fontSize = 12.sp, color = g.textSecondary)
                StatementRulesEngine.RuleEvent.entries.forEach { ev ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = ev.name in events,
                            onCheckedChange = { on ->
                                events = if (on) events + ev.name else events - ev.name
                            }
                        )
                        Text(
                            eventLabel(ev.name),
                            fontSize = 13.sp, color = g.textPrimary,
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    events = if (ev.name in events) events - ev.name else events + ev.name
                                }
                        )
                    }
                }
                if (StatementRulesEngine.RuleEvent.THRESHOLD.name in events) {
                    BizField(thresholdText, { thresholdText = it }, stringResourceCompat(R.string.st3_threshold_value), keyboard = numberFieldOptions())
                }
            }

            // أيام CUSTOM
            if (freq == StatementRulesEngine.FREQ_CUSTOM) {
                BizField(daysText, { daysText = it }, stringResourceCompat(R.string.st3_custom_days), keyboard = numberFieldOptions())
            }

            error?.let { StErrorRow(it) }

            Row(verticalAlignment = Alignment.CenterVertically) {
                StAction(txtSave, GreenDeep) {
                    val e = buildEntity()
                    if (e != null) {
                        error = null
                        onSave(e)
                    }
                }
                Spacer(Modifier.width(10.dp))
                StAction(txtCancel, g.textSecondary) { onDismiss() }
            }
        }
    }

    // حوار اختيار أطراف متعدد
    if (pickPartiesOpen) {
        var tmp by remember { mutableStateOf(selectedIds) }
        AlertDialog(
            onDismissRequest = { pickPartiesOpen = false },
            containerColor = g.surfaceStrong,
            title = { Text(labelPickParties, color = g.textPrimary) },
            text = {
                if (parties.isEmpty()) {
                    Text(labelNoParties, color = g.textSecondary, fontSize = 13.sp)
                } else {
                    LazyColumn(Modifier.height(320.dp)) {
                        items(parties.size) { i ->
                            val p = parties[i]
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        tmp = if (p.id in tmp) tmp - p.id else tmp + p.id
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = p.id in tmp,
                                    onCheckedChange = { on ->
                                        tmp = if (on) tmp + p.id else tmp - p.id
                                    }
                                )
                                Text(
                                    p.name,
                                    fontSize = 13.sp, color = g.textPrimary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedIds = tmp; pickPartiesOpen = false }) {
                    Text(txtConfirm, color = g.accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pickPartiesOpen = false }) {
                    Text(txtCancel, color = g.textSecondary)
                }
            }
        )
    }
}

// ═══════════ عناصر واجهة صغيرة مشتركة ═══════════

@Composable
private fun GlassSwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 13.sp, color = glassColors().textPrimary, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** حقل كلمة مرور زجاجي — BizField لا يدعم visualTransformation فتُبنى نسخة محلية بنفس الألوان */
@Composable
private fun GlassPassField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    show: Boolean,
    onToggleShow: () -> Unit
) {
    val g = glassColors()
    OutlinedTextField(
        value = value, onValueChange = onValue,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onToggleShow) {
                Icon(
                    if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = stringResourceCompat(
                        if (show) R.string.st3_hide_pass else R.string.st3_show_pass
                    ),
                    tint = g.textSecondary
                )
            }
        },
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = g.accent,
            unfocusedBorderColor = g.border,
            focusedTextColor = g.textPrimary,
            unfocusedTextColor = g.textPrimary,
            cursorColor = g.accent,
            focusedLabelColor = g.accent,
            unfocusedLabelColor = g.textSecondary
        )
    )
}

/** قائمة منسدلة فوق رقاقة — نفس نمط DropdownMenu في StatementScreen (17-c) */
@Composable
private fun StDropdownChip(current: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val g = glassColors()
    Box {
        StChip(current, selected = true) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (text, value) ->
                DropdownMenuItem(
                    text = { Text(text, fontSize = 13.sp, color = g.textPrimary, maxLines = 1) },
                    onClick = { onPick(value); open = false }
                )
            }
        }
    }
}

/** بطاقة دخول لشاشة أخرى — سطر عنوان + وصف + سهم للأمام (RTL: يسار) */
@Composable
private fun EntryCard(title: String, sub: String, tint: Color, onClick: () -> Unit) {
    val g = glassColors()
    GlassCard(Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // استثناء ألوان معلن: نقطة تعريف ثابتة بلون القسم (نمط نقاط حالة 18-c)
            Box(Modifier.size(10.dp).clip(CircleShape).background(tint))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = g.textPrimary)
                Text(sub, fontSize = 11.sp, color = g.textSecondary)
            }
            Icon(Icons.Rounded.ChevronLeft, null, tint = g.textSecondary, modifier = Modifier.size(18.dp))
        }
    }
}

// ═══════════ مساعدات التسمية — مفاتيح تُحل داخل التركيب ═══════════

/** المنفذ الافتراضي لنمط أمان — عقد 18-a: 465/587/25 */
private fun defaultPortFor(sec: SmtpSecurity): Int =
    runCatching { SmtpConfig.defaultPort(sec) }.getOrDefault(465)

@Composable
private fun securityLabel(s: String): String = when (s) {
    SmtpSecurity.SSL_TLS.name -> stringResourceCompat(R.string.st3_sec_ssl_tls)
    SmtpSecurity.STARTTLS.name -> stringResourceCompat(R.string.st3_sec_starttls)
    SmtpSecurity.NONE.name -> stringResourceCompat(R.string.st3_sec_none)
    else -> s
}

@Composable
private fun freqLabel(f: String): String = when (f.trim().uppercase(Locale.US)) {
    StatementRulesEngine.FREQ_DAILY -> stringResourceCompat(R.string.st3_freq_daily)
    StatementRulesEngine.FREQ_WEEKLY -> stringResourceCompat(R.string.st3_freq_weekly)
    StatementRulesEngine.FREQ_MONTHLY -> stringResourceCompat(R.string.st3_freq_monthly)
    StatementRulesEngine.FREQ_QUARTERLY -> stringResourceCompat(R.string.st3_freq_quarterly)
    StatementRulesEngine.FREQ_YEARLY -> stringResourceCompat(R.string.st3_freq_yearly)
    StatementRulesEngine.FREQ_CUSTOM -> stringResourceCompat(R.string.st3_freq_custom)
    StatementRulesEngine.FREQ_EVENT -> stringResourceCompat(R.string.st3_freq_event)
    else -> f
}

@Composable
private fun channelLabel(c: String): String = when (c.trim().uppercase(Locale.US)) {
    "EMAIL" -> stringResourceCompat(R.string.st3_ch_email)
    "SMTP" -> stringResourceCompat(R.string.st3_ch_smtp)
    "WHATSAPP" -> stringResourceCompat(R.string.st3_ch_whatsapp)
    "PRINT" -> stringResourceCompat(R.string.st3_ch_print)
    "SHARE" -> stringResourceCompat(R.string.st3_ch_share)
    else -> c
}

@Composable
private fun modeLabel(m: String): String = when (m.trim().uppercase(Locale.US)) {
    "ALL" -> stringResourceCompat(R.string.st3_mode_all)
    "SELECTED" -> stringResourceCompat(R.string.st3_mode_selected)
    "SINGLE" -> stringResourceCompat(R.string.st3_mode_single)
    else -> m
}

@Composable
private fun periodLabel(p: String): String = when (p.trim().uppercase(Locale.US)) {
    StatementPeriodPreset.TODAY.name -> stringResourceCompat(R.string.st3_period_today)
    StatementPeriodPreset.THIS_WEEK.name -> stringResourceCompat(R.string.st3_period_this_week)
    StatementPeriodPreset.LAST_WEEK.name -> stringResourceCompat(R.string.st3_period_last_week)
    StatementPeriodPreset.THIS_MONTH.name -> stringResourceCompat(R.string.st3_period_this_month)
    StatementPeriodPreset.LAST_MONTH.name -> stringResourceCompat(R.string.st3_period_last_month)
    StatementPeriodPreset.LAST_3_MONTHS.name -> stringResourceCompat(R.string.st3_period_l3m)
    StatementPeriodPreset.LAST_6_MONTHS.name -> stringResourceCompat(R.string.st3_period_l6m)
    StatementPeriodPreset.THIS_YEAR.name -> stringResourceCompat(R.string.st3_period_this_year)
    StatementPeriodPreset.LAST_YEAR.name -> stringResourceCompat(R.string.st3_period_last_year)
    StatementPeriodPreset.CUSTOM.name -> stringResourceCompat(R.string.st3_freq_custom)
    else -> p
}

@Composable
private fun eventLabel(name: String): String = when (name.trim().uppercase(Locale.US)) {
    StatementRulesEngine.RuleEvent.MONTH_END.name -> stringResourceCompat(R.string.st3_ev_month_end)
    StatementRulesEngine.RuleEvent.NEW_TX.name -> stringResourceCompat(R.string.st3_ev_new_tx)
    StatementRulesEngine.RuleEvent.BALANCE_DUE.name -> stringResourceCompat(R.string.st3_ev_balance_due)
    StatementRulesEngine.RuleEvent.THRESHOLD.name -> stringResourceCompat(R.string.st3_ev_threshold)
    StatementRulesEngine.RuleEvent.UNPAID_INVOICE.name -> stringResourceCompat(R.string.st3_ev_unpaid)
    StatementRulesEngine.RuleEvent.OVERDUE.name -> stringResourceCompat(R.string.st3_ev_overdue)
    else -> name
}

/** أسماء الأيام 1..7 بدلالة Calendar.DAY_OF_WEEK (الأحد=1 … السبت=7) — عقد weekday */
@Composable
private fun dowLabel(d: Int): String = when (d) {
    1 -> stringResourceCompat(R.string.st3_dow_1)
    2 -> stringResourceCompat(R.string.st3_dow_2)
    3 -> stringResourceCompat(R.string.st3_dow_3)
    4 -> stringResourceCompat(R.string.st3_dow_4)
    5 -> stringResourceCompat(R.string.st3_dow_5)
    6 -> stringResourceCompat(R.string.st3_dow_6)
    7 -> stringResourceCompat(R.string.st3_dow_7)
    else -> d.toString()
}
