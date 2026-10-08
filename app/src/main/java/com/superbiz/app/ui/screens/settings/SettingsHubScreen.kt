package com.superbiz.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos // [P17-c] بطاقتا التواقيع/الأختام
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.WorkspacePremium // [W1] قسم Pro
import androidx.compose.material.icons.rounded.ManageAccounts // [H1-5][v13] قسم المستخدمين
import androidx.compose.foundation.layout.size // [W1] ProSection
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superbiz.app.R
import com.superbiz.app.data.db.CouponEntity
import com.superbiz.app.data.repo.BackupRestoreRepo
import com.superbiz.app.domain.backup.BackupFormatException
import com.superbiz.app.domain.backup.ImportStats
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.components.BizPill
import com.superbiz.app.ui.components.BizField
import com.superbiz.app.ui.components.numberFieldOptions
import com.superbiz.app.ui.components.PillMode
import com.superbiz.app.ui.screens.ReAuthDialog
import com.superbiz.app.ui.theme.Cyan
import com.superbiz.app.ui.theme.VioDeep
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.util.Money
import com.superbiz.app.vm.LoyaltyVM
import com.superbiz.app.vm.SettingsVM
import kotlinx.coroutines.launch

/**
 * — مركز الإعدادات المركزي (SettingsHub)
 *
 * ثمانية أقسام، كل مفتاح فيها يُحفظ فعلياً في DataStore عبر SettingsRepo ويُطبَّق
 * في مستهلكه الحقيقي (الثيم، MainActivity، الطباعة، الويدجت، أعمال الخلفية)
 * المظهر / السلوك / الأداء / الخصوصية / الإشعارات / البيانات / متقدم / حول
 *
 * لا أزرار ديكورية — كل مفتاح يغذّي سلوكاً حقيقياً.
*/

private data class HubSection(val key: String, val icon: ImageVector)

@Composable
fun SettingsHubScreen(
    settingsVM: SettingsVM,
    onOpenErrorLog: () -> Unit,
    // مدخل فحص صحة البيانات من قسم الأدوات
    onOpenHealth: () -> Unit = {},
    // [P17-c] مدخلا إدارة التواقيع والأختام من قسم البيانات — افتراضات آمنة
    onOpenSignatures: () -> Unit = {},
    onOpenStamps: () -> Unit = {},
    // [W1] مدخل شاشة Pro من قسم الفريميوم
    onOpenPro: () -> Unit = {},
    // [H1-5][v13] مدخل إدارة المستخدمين والأدوار — يظهر للمالك حصراً
    // (وضع المالك الضمني يراه دائماً كما قبل v13؛ التعدد يقصّه عن غير المالك)
    onOpenUsers: () -> Unit = {},
    onBack: () -> Unit
) {
    val g = glassColors()
    val s by settingsVM.settings.collectAsState()
    // [H1-5][v13] هل الجلسة الحية مالك؟ — المالك الضمني (وضع أحادي) = نعم
    val sessionIsOwner = com.superbiz.app.domain.rbac.SessionState.effective().role ==
        com.superbiz.app.domain.rbac.Role.OWNER

    val sections = buildList {
        // [W1] قسم Pro أولاً — الفريميوم واجهة النمو
        add(HubSection("pro", Icons.Rounded.WorkspacePremium))
        // [H1-5][v13] المستخدمون — باب المالك وحده (يُقص بنيوياً عن غيره)
        if (sessionIsOwner) add(HubSection("users", Icons.Rounded.ManageAccounts))
        add(HubSection("appearance", Icons.Rounded.Palette))
        add(HubSection("behavior", Icons.Rounded.Tune))
        add(HubSection("performance", Icons.Rounded.Speed))
        add(HubSection("privacy", Icons.Rounded.PrivacyTip))
        add(HubSection("notifications", Icons.Rounded.Notifications))
        add(HubSection("data", Icons.Rounded.Backup))
        add(HubSection("advanced", Icons.Rounded.Build))
        add(HubSection("about", Icons.Rounded.Info))
    }
    var selected by androidx.compose.runtime.remember { mutableIntStateOf(0) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // شريط علوي بسيط
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBackIos, null, tint = g.textPrimary,
                    modifier = Modifier.padding(4.dp)
                )
            }
            // [P6-M51 إصلاح] توطين عنوان المركز
            Text(
                stringResource(R.string.set_hub_title),
                style = MaterialTheme.typography.titleLarge,
                color = g.textPrimary
            )
        }

        // رقائق الأقسام
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            sections.forEachIndexed { i, sec ->
                val active = i == selected
                Row(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (active) Brush.linearGradient(listOf(VioDeep, Cyan))
                            else Brush.linearGradient(listOf(g.surface, g.surface))
                        )
                        .clickable { selected = i }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        sec.icon, null,
                        tint = if (active) Color.White else g.textSecondary,
                        modifier = Modifier.height(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        sectionTitle(sec.key),
                        color = if (active) Color.White else g.textPrimary,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        when (sections[selected].key) {
            "pro" -> ProSection(onOpenPro)
            // [H1-5][v13] قسم المستخدمين — بطاقة مدخل واحدة إلى شاشة الإدارة
            "users" -> UsersSection(onOpenUsers)
            "appearance" -> AppearanceSection(s, settingsVM)
            "behavior" -> BehaviorSection(s, settingsVM)
            "performance" -> PerformanceSection(s, settingsVM)
            "privacy" -> PrivacySection(s, settingsVM)
            "notifications" -> NotificationsSection(s, settingsVM)
            "data" -> DataSection(s, settingsVM, onOpenSignatures, onOpenStamps)
            "advanced" -> AdvancedSection(s, settingsVM, onOpenErrorLog, onOpenHealth)
            "about" -> AboutSection()
        }

        Spacer(Modifier.height(90.dp)) // مساحة للدوك العائم
    }
}

/**
 * [W1] قسم Pro في مركز الإعدادات — الحالة + بوابة الترقية.
 * بلا VM خاص: ProVM يُنشأ هنا بمصنع الافتراض (AndroidViewModel) ويُقرأ
 * استحقاقه فقط — لا كتابة إعدادات هنا.
 */
/**
 * [H1-5][v13] قسم المستخدمين في مركز الإعدادات — بطاقة مدخل واحدة إلى
 * شاشة إدارة المستخدمين والأدوار (باب المالك حصراً؛ القسم نفسه لا يُبنى
 * أصلاً لغير المالك انظر sections أعلاه).
 */
@Composable
private fun UsersSection(onOpenUsers: () -> Unit) {
    val g = glassColors()
    GlassCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth().clickable { onOpenUsers() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.ManageAccounts, null,
                tint = com.superbiz.app.ui.theme.Vio,
                modifier = Modifier.size(30.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.users_section_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = g.textPrimary
                )
                Text(
                    stringResource(R.string.users_section_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = g.textSecondary
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBackIos, null,
                tint = g.textSecondary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun ProSection(onOpenPro: () -> Unit) {
    val g = glassColors()
    val proVM: com.superbiz.app.vm.ProVM = androidx.lifecycle.viewmodel.compose.viewModel()
    val pro by proVM.pro.collectAsState()
    GlassCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth().clickable { onOpenPro() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.WorkspacePremium, null,
                tint = if (pro) com.superbiz.app.ui.theme.Amber else com.superbiz.app.ui.theme.Vio,
                modifier = Modifier.size(30.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.pro_section_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = g.textPrimary
                )
                Text(
                    if (pro) stringResource(R.string.pro_status_active)
                    else stringResource(R.string.pro_section_cta),
                    style = MaterialTheme.typography.bodySmall,
                    color = g.textSecondary
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBackIos, null,
                tint = g.textSecondary, modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun sectionTitle(key: String): String = when (key) {
    // [P6-M51 إصلاح] توطين عناوين الأقسام الثمانية
    "pro" -> stringResource(R.string.pro_section_title)
    "appearance" -> stringResource(R.string.set_section_appearance)
    "behavior" -> stringResource(R.string.set_section_behavior)
    "performance" -> stringResource(R.string.set_section_performance)
    "privacy" -> stringResource(R.string.set_section_privacy)
    "notifications" -> stringResource(R.string.set_section_notifications)
    "data" -> stringResource(R.string.set_section_data)
    "advanced" -> stringResource(R.string.set_section_advanced)
    else -> stringResource(R.string.set_section_about)
}

/** صف إعداد موحّد: عنوان + وصف + مفاتيح */
@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit
) {
    val g = glassColors()
    GlassCard(corner = 18.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = g.textPrimary, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        color = g.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            trailing()
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun GlassSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val g = glassColors()
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedTrackColor = g.accent,
            checkedThumbColor = Color.White,
            uncheckedTrackColor = g.textSecondary.copy(alpha = 0.25f)
        )
    )
}

// ─────────────────────────── الأقسام ───────────────────────────

@Composable
private fun AppearanceSection(s: com.superbiz.app.data.repo.Settings, vm: SettingsVM) {
    val g = glassColors()
    // [P6-M51 إصلاح] توطين صفوف قسم المظهر
    SettingRow(
        stringResource(R.string.set_row_dynamic_colors),
        stringResource(R.string.set_row_dynamic_colors_hint)
    ) {
        GlassSwitch(s.dynamicColors) { vm.setDynamicColors(it) }
    }
    SettingRow(
        stringResource(R.string.set_row_mirror_charts),
        stringResource(R.string.set_row_mirror_charts_hint)
    ) {
        GlassSwitch(s.mirrorChartsRtl) { vm.setMirrorChartsRtl(it) }
    }
    GlassCard(corner = 18.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.set_row_font_scale), color = g.textPrimary, style = MaterialTheme.typography.bodyLarge)
                    // [P6-M51 إصلاح] نسبة التكبير قيمة ديناميكية تُمرَّر لسلسلة منسقة
                    Text(
                        stringResource(R.string.set_row_font_scale_hint, (s.fontScale * 100).toInt()),
                        color = g.textSecondary, style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            var local by remember(s.fontScale) { androidx.compose.runtime.mutableFloatStateOf(s.fontScale) }
            Slider(
                value = local,
                onValueChange = { local = it },
                onValueChangeFinished = { vm.setFontScale(local) },
                valueRange = 0.85f..1.30f,
                // [P20-FIX agent6]: steps=9 ⇒ خطوات 0.05 (85/90/…/130%) — كانت 8 خطوات × 0.045
                // فتعرض نسباً مشوهة (89%، 94%، 103%…)
                steps = 9
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    // [P6-M51 إصلاح] توطين صف الثيم وتسميات داكن/فاتح/تلقائي
    SettingRow(
        stringResource(R.string.set_row_theme),
        stringResource(
            R.string.set_theme_current,
            when (s.theme) {
                "dark" -> stringResource(R.string.set_theme_dark)
                "light" -> stringResource(R.string.set_theme_light)
                else -> stringResource(R.string.set_theme_auto)
            }
        )
    ) {
        Row {
            listOf(
                "dark" to stringResource(R.string.set_theme_dark),
                "light" to stringResource(R.string.set_theme_light),
                "auto" to stringResource(R.string.set_theme_auto)
            ).forEach { (k, label) ->
                val active = s.theme == k
                Text(
                    label,
                    color = if (active) Color.White else g.textSecondary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (active) g.accent else g.textSecondary.copy(alpha = 0.12f))
                        .clickable { vm.setTheme(k) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun BehaviorSection(s: com.superbiz.app.data.repo.Settings, vm: SettingsVM) {
    // [P6-M51 إصلاح] توطين صفوف قسم السلوك
    SettingRow(
        stringResource(R.string.set_row_haptics),
        stringResource(R.string.set_row_haptics_hint)
    ) {
        GlassSwitch(s.hapticsEnabled) { vm.setHapticsEnabled(it) }
    }
    SettingRow(
        stringResource(R.string.set_row_confirm_destructive),
        stringResource(R.string.set_row_confirm_destructive_hint)
    ) {
        GlassSwitch(s.confirmDestructive) { vm.setConfirmDestructive(it) }
    }
}

@Composable
private fun PerformanceSection(s: com.superbiz.app.data.repo.Settings, vm: SettingsVM) {
    // [P6-M51 إصلاح] توطين صفوف قسم الأداء
    SettingRow(
        stringResource(R.string.set_row_animations),
        stringResource(R.string.set_row_animations_hint)
    ) {
        GlassSwitch(s.animationsEnabled) { vm.setAnimationsEnabled(it) }
    }
}

@Composable
private fun PrivacySection(s: com.superbiz.app.data.repo.Settings, vm: SettingsVM) {
    val g = glassColors()
    // [P6-M51 إصلاح] توطين صفوف قسم الخصوصية
    SettingRow(
        stringResource(R.string.set_row_flag_secure),
        stringResource(R.string.set_row_flag_secure_hint)
    ) {
        GlassSwitch(s.flagSecure) { vm.setFlagSecure(it) }
    }
    SettingRow(
        stringResource(R.string.set_row_redact_widgets),
        stringResource(R.string.set_row_redact_widgets_hint)
    ) {
        GlassSwitch(s.redactWidgets) { vm.setRedactWidgets(it) }
    }
    // وظيفة 38 — وضع الخصوصية: طمس مبالغ بطاقات الرئيسية كـ «•••» مع كشف مؤقت بنقرة طويلة
    SettingRow(
        stringResource(R.string.set_privacy_blur),
        stringResource(R.string.set_privacy_blur_hint)
    ) {
        GlassSwitch(s.privacyBlur) { vm.setPrivacyBlur(it) }
    }
    // [P5-H4 إصلاح]: لا بيومتريا بلا رمز — كان التبديل هنا يُمكّن البصمة بلا PIN
    // فينتج حالة «متابعة (طارئ)» في شاشة القفل (تجاوز كامل بالبصمة أو بنقرة)
    val pinReqBioMsg = stringResource(R.string.pin_required_bio)
    SettingRow(
        stringResource(R.string.set_row_biometric),
        stringResource(R.string.set_row_biometric_hint)
    ) {
        GlassSwitch(s.biometric) { on ->
            if (on && s.pinHash == null && s.pinBlob == null) vm.notifyToast(pinReqBioMsg)
            else vm.setBiometric(on)
        }
    }
    GlassCard(corner = 18.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(stringResource(R.string.set_row_lock_timeout), color = g.textPrimary, style = MaterialTheme.typography.bodyLarge)
            // [P6-M51 إصلاح] توطين وصف مهلة القفل (فوري/عدد الدقائق)
            Text(
                if (s.lockTimeoutMin == 0) stringResource(R.string.set_lock_timeout_immediate)
                else stringResource(R.string.set_lock_timeout_after, s.lockTimeoutMin),
                color = g.textSecondary, style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 1, 5, 15).forEach { m ->
                    val active = s.lockTimeoutMin == m
                    Text(
                        if (m == 0) stringResource(R.string.set_lock_now) else stringResource(R.string.set_lock_minutes, m),
                        color = if (active) Color.White else g.textSecondary,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (active) g.accent else g.textSecondary.copy(alpha = 0.12f))
                            .clickable { vm.setLockTimeoutMin(m) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun NotificationsSection(s: com.superbiz.app.data.repo.Settings, vm: SettingsVM) {
    // [P6-M51 إصلاح] توطين صفوف قسم الإشعارات
    SettingRow(
        stringResource(R.string.set_row_low_stock_alerts),
        stringResource(R.string.set_row_low_stock_alerts_hint)
    ) {
        GlassSwitch(s.lowStockAlerts) { vm.setLowStockAlerts(it) }
    }
    SettingRow(
        stringResource(R.string.set_row_receivable_alerts),
        stringResource(R.string.set_row_receivable_alerts_hint)
    ) {
        GlassSwitch(s.receivableAlerts) { vm.setReceivableAlerts(it) }
    }
}

/** [P17-c] بطاقة صف موحّدة لمداخل إدارة التواقيع/الأختام — نمط GlassCard + سهم مثل بقية البطاقات */
@Composable
private fun StatementAssetCard(title: String, subtitle: String, onClick: () -> Unit) {
    val g = glassColors()
    GlassCard(corner = 18.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = g.textPrimary, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, color = g.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Icon(
                Icons.AutoMirrored.Rounded.ArrowForwardIos, null,
                tint = g.textSecondary, modifier = Modifier.height(14.dp)
            )
        }
    }
}

@Composable
private fun DataSection(
    s: com.superbiz.app.data.repo.Settings,
    vm: SettingsVM,
    // [P17-c] مدخلا إدارة التواقيع والأختام
    onOpenSignatures: () -> Unit = {},
    onOpenStamps: () -> Unit = {}
) {
    val g = glassColors()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    // وظيفة 33 — تصدير/استيراد إعدادات JSON عبر SAF (مسار مستقل عن النسخة الكاملة،
    // عبر القائمة البيضاء في SettingsCodec: بلا رمز/أسرار/بيانات أعمال، وتحقق صارم عند الاستيراد)
    val settingsExportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let {
            vm.exportSettingsTo(it) { ok ->
                android.widget.Toast.makeText(
                    ctx,
                    ctx.getString(if (ok) R.string.set_settings_export_ok else R.string.set_settings_export_fail),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    val settingsImportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let {
            vm.importSettingsFrom(it) { n, err ->
                val msg = if (err != null)
                    ctx.getString(R.string.set_settings_import_fail, err)
                else
                    ctx.getString(R.string.set_settings_import_ok, n)
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ═══ [P14-b] بطاقة تنبيه الزيارات (من 14-a) — تُدرج هنا أعلى قسم البيانات، بنفس إيقاع البطاقات ═══
    // composable بلا معاملات من نفس الحزمة (ui.screens.settings) — 14-a ينشئها بالتوازي
    VisitReminderCard()
    Spacer(Modifier.height(8.dp))

    // [P15-b] بطاقة بصمة ZATCA (من 15-a) — بجانب بطاقة تنبيه الزيارات بنفس الإيقاع،
    // composable بلا معاملات من نفس الحزمة (ui.screens.settings)
    ZatcaCard()
    Spacer(Modifier.height(8.dp))

    // [Z2-ب V 1.5.0] بطاقة الربط الضريبي (فاتورة) — بوابة الميزة D2 + لوحة حالات
    // الإبلاغ/التخليص — إيقاع البطاقات الذاتية نفسه بلا معاملات
    ZatcaLinkCard()
    Spacer(Modifier.height(8.dp))

    // [P17-c] بطاقتا إدارة التواقيع والأختام — بجانب ZatcaCard بنفس الإيقاع،
    // تفتحان شاشتي signatures/stamps عبر المسارات المسجلة في Nav
    StatementAssetCard(
        title = stringResource(R.string.st_hub_signatures),
        subtitle = stringResource(R.string.st_hub_signatures_sub),
        onClick = onOpenSignatures
    )
    Spacer(Modifier.height(8.dp))
    StatementAssetCard(
        title = stringResource(R.string.st_hub_stamps),
        subtitle = stringResource(R.string.st_hub_stamps_sub),
        onClick = onOpenStamps
    )
    Spacer(Modifier.height(8.dp))

    GlassCard(corner = 18.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(stringResource(R.string.set_row_backup_reminder), color = g.textPrimary, style = MaterialTheme.typography.bodyLarge)
            // [P6-M51 إصلاح] توطين وصف تكرار النسخ (موقوف/كل N يوماً)
            Text(
                if (s.autoBackupDays == 0) stringResource(R.string.set_backup_paused)
                else stringResource(R.string.set_backup_every_days, s.autoBackupDays),
                color = g.textSecondary, style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            // وظيفة 34 — الخيارات 0/1/3/7/30 ويوم والمجدول الدوري حقيقي بحسب N
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 1, 3, 7, 30).forEach { d ->
                    val active = s.autoBackupDays == d
                    Text(
                        // [P6-M51 إصلاح] إعادة استخدام autoback_off الموجود لتسمية «إيقاف»
                        if (d == 0) stringResource(R.string.autoback_off) else stringResource(R.string.set_backup_days_short, d),
                        color = if (active) Color.White else g.textSecondary,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (active) g.accent else g.textSecondary.copy(alpha = 0.12f))
                            .clickable { vm.setAutoBackupDays(d) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
            // وظيفة 34 — توقيت آخر نسخة ناجحة يُعرض تحت الإعداد («آخر نسخة: تاريخ أو —»)
            Spacer(Modifier.height(8.dp))
            Text(
                if (s.lastAutoBackup > 0)
                    stringResource(
                        R.string.set_backup_last,
                        java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.US)
                            .format(java.util.Date(s.lastAutoBackup))
                    )
                else
                    stringResource(R.string.set_backup_last_none),
                color = g.textSecondary, style = MaterialTheme.typography.bodySmall
            )
            // [P36-BK] رقاقة التغطية — الجداول الثمانية في exportJson جعلت 23/23 صادقة للمسار التلقائي أيضاً
            Spacer(Modifier.height(8.dp))
            val cov = com.superbiz.app.domain.backup.BACKUP_TABLE_COUNT
            BizPill(
                stringResource(R.string.p36_bk_coverage, cov, cov),
                g.accent, mode = PillMode.BADGE
            )
            // [P36-BK] تضمين PDFs الكشوف في مرآة النسخ — يُنفّذ في SafBackupMirror.mirrorPdfs
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.p36_bk_pdfs_title),
                color = g.textPrimary, style = MaterialTheme.typography.bodyLarge
            )
            Text(
                stringResource(R.string.p36_bk_pdfs_sub),
                color = g.textSecondary, style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    false to stringResource(R.string.p36_bk_pdfs_off),
                    true to stringResource(R.string.p36_bk_pdfs_on)
                ).forEach { (v, label) ->
                    val active = s.backupIncludePdfs == v
                    Text(
                        label,
                        color = if (active) Color.White else g.textSecondary,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (active) g.accent else g.textSecondary.copy(alpha = 0.12f))
                            .clickable { vm.setBackupIncludePdfs(v) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    // [P7-L17 إصلاح] صف «مجلد النسخ الاحتياطي» — يحل قيد P6-M37 (النسخ الداخلية تموت بحذف التطبيق):
    // منتقي شجرة SAF تُنسخ إليها مرآة كل نسخة تلقائية (SafBackupMirror في BackupWorker +
    // مسار AUTO_BACKUP في AutomationWorker)، وبلا اختيار يبقى المسار الداخلي الافتراضي كما هو.
    val backupFolderPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        treeUri?.let {
            // تثبيت الإذن المستمر (يصمد عبر إعادة التشغيل) قبل حفظ المرجع — READ+WRITE معاً
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            vm.setBackupDir(it.toString())
        }
    }
    // [P7-L17 إصلاح] مسح المجلد: إفلات الإذن المستمر بأمان (قد لا يكون قائماً أصلاً)
    // ثم إزالة المرجع فيعود النسخ التلقائي للمسار الداخلي حرفياً
    val clearBackupFolder: () -> Unit = {
        s.backupDirUri?.let { saved ->
            runCatching {
                ctx.contentResolver.releasePersistableUriPermission(
                    android.net.Uri.parse(saved),
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        vm.setBackupDir(null as String?)
    }
    // الاسم القصير المعروض للمجلد المختار: آخر مقطع من معرّف شجرة المستندات (primary:X/Y → Y)
    val backupDirLabel: String? = s.backupDirUri?.let { saved ->
        runCatching {
            val docId = android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(saved))
            docId.substringAfter(':', docId).trimEnd('/').substringAfterLast('/')
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
    SettingRow(
        stringResource(R.string.set_backup_folder_title),
        stringResource(R.string.set_backup_folder_sub)
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                backupDirLabel ?: stringResource(R.string.set_backup_folder_default),
                color = g.textSecondary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(g.textSecondary.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
            Text(
                stringResource(R.string.set_backup_folder_pick),
                color = g.accent,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(g.accent.copy(alpha = 0.18f))
                    .clickable { backupFolderPicker.launch(null) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
            if (s.backupDirUri != null) {
                Text(
                    stringResource(R.string.set_backup_folder_clear),
                    color = g.textSecondary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(g.textSecondary.copy(alpha = 0.12f))
                        .clickable { clearBackupFolder() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
    // وظيفة 33 — تصدير الإعدادات كـ JSON مشارَك (قائمة بيضاء بلا PIN/أسرار/بيانات أعمال)
    SettingRow(
        stringResource(R.string.set_settings_export),
        stringResource(R.string.set_settings_export_hint)
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(g.accent.copy(alpha = 0.18f))
                .clickable { settingsExportLauncher.launch("superbiz-settings.json") }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(stringResource(R.string.set_settings_export_action), color = g.accent, style = MaterialTheme.typography.labelLarge)
        }
    }
    // وظيفة 33 — استيراد بتحقق صارم (نوع/نطاق/مفاتيح محظورة ومجهولة/JSON تالف)
    SettingRow(
        stringResource(R.string.set_settings_import),
        stringResource(R.string.set_settings_import_hint)
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(g.accent.copy(alpha = 0.18f))
                .clickable { settingsImportLauncher.launch(arrayOf("application/json")) }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(stringResource(R.string.set_settings_import_action), color = g.accent, style = MaterialTheme.typography.labelLarge)
        }
    }
    Spacer(Modifier.height(8.dp))
    // ═══ [P13-b] بطاقة النسخ الاحتياطي والاستعادة () — تصدير JSON كامل واستعادة بوضعين صريحين ═══
    // [P36-BK] توحيد المسارين في واجهة واحدة: دمجية (BackupRestoreRepo) أو استبدال كامل
    // (BackupRepo عبر ReAuth) — ورقاقة التغطية 23/23 صارت صادقة في المسارين معاً
    BackupCard(vm)
    Spacer(Modifier.height(8.dp))
    // [P6-M51 إصلاح] توطين صف هدف المبيعات
    SettingRow(
        stringResource(R.string.set_row_monthly_goal),
        if (s.monthlyGoal <= 0.0) stringResource(R.string.set_goal_disabled)
        else stringResource(R.string.set_goal_current, s.monthlyGoal.toInt())
    ) {
        Unit
    }
}

@Composable
private fun AdvancedSection(
    s: com.superbiz.app.data.repo.Settings,
    vm: SettingsVM,
    onOpenErrorLog: () -> Unit,
    onOpenHealth: () -> Unit
) {
    val g = glassColors()
    GlassCard(corner = 18.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(stringResource(R.string.set_row_arabic_print), color = g.textPrimary, style = MaterialTheme.typography.bodyLarge)
            // [P6-M51 إصلاح] توطين وصف أسلوب الطباعة العربية
            Text(
                if (s.arabicReceiptMode == 0)
                    stringResource(R.string.set_arabic_print_cp1256)
                else
                    stringResource(R.string.set_arabic_print_utf8),
                color = g.textSecondary, style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0 to "CP1256", 1 to "UTF-8").forEach { (v, label) ->
                    val active = s.arabicReceiptMode == v
                    Text(
                        label,
                        color = if (active) Color.White else g.textSecondary,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (active) g.accent else g.textSecondary.copy(alpha = 0.12f))
                            .clickable { vm.setArabicReceiptMode(v) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    // [P6-M51 إصلاح] توطين صفوف قسم متقدم
    SettingRow(
        stringResource(R.string.set_row_default_low_stock),
        stringResource(R.string.set_row_default_low_stock_hint, s.defaultLowStockQty)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(3, 5, 10).forEach { q ->
                val active = s.defaultLowStockQty == q
                Text(
                    "$q",
                    color = if (active) Color.White else g.textSecondary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (active) g.accent else g.textSecondary.copy(alpha = 0.12f))
                        .clickable { vm.setDefaultLowStockQty(q) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    // الهامش المستهدف — يغذّي مستشار التسعير في محرر المنتج
    SettingRow(
        stringResource(R.string.set_row_target_margin),
        stringResource(R.string.set_row_target_margin_hint, s.defaultTargetMargin.toInt())
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(20.0, 30.0, 40.0, 50.0).forEach { m ->
                val active = s.defaultTargetMargin == m
                Text(
                    "${m.toInt()}%",
                    color = if (active) Color.White else g.textSecondary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (active) g.accent else g.textSecondary.copy(alpha = 0.12f))
                        .clickable { vm.setDefaultTargetMargin(m) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    // غرامة تأخير الأقساط — تظهر كم estimate على الأقساط المتأخرة
    SettingRow(
        stringResource(R.string.set_row_late_fee),
        if (s.lateFeeDailyPct <= 0.0) stringResource(R.string.set_late_fee_disabled)
        else stringResource(
            R.string.set_late_fee_summary,
            s.lateFeeDailyPct.toString(), s.lateFeeCapPct.toString()
        )
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.0, 0.25, 0.5, 1.0).forEach { d ->
                val active = s.lateFeeDailyPct == d
                Text(
                    // [P6-M51 إصلاح] إعادة استخدام autoback_off ومفتاح ٪ مشترك للرقاقات
                    if (d == 0.0) stringResource(R.string.autoback_off) else stringResource(R.string.set_pct_chip, d.toString()),
                    color = if (active) Color.White else g.textSecondary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (active) g.accent else g.textSecondary.copy(alpha = 0.12f))
                        .clickable { vm.setLateFeeDailyPct(d) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    if (s.lateFeeDailyPct > 0.0) {
        SettingRow(
            stringResource(R.string.set_row_late_fee_cap),
            stringResource(R.string.set_row_late_fee_cap_hint)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(5.0, 10.0, 15.0, 25.0).forEach { c ->
                    val active = s.lateFeeCapPct == c
                    Text(
                        stringResource(R.string.set_pct_chip, c.toString()),
                        color = if (active) Color.White else g.textSecondary,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (active) g.accent else g.textSecondary.copy(alpha = 0.12f))
                            .clickable { vm.setLateFeeCapPct(c) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    // عطلة نهاية الأسبوع — تغذّي حساب أيام العمل في الشيكات
    SettingRow(
        stringResource(R.string.set_row_weekend),
        stringResource(R.string.set_row_weekend_hint)
    ) {
        GlassSwitch(s.weekendFriSat) { vm.setWeekendFriSat(it) }
    }
    Spacer(Modifier.height(8.dp))
    // حساسية البحث الضبابي — تغذّي حدّ القبول في GlobalSearchVM
    // (TextMath.fuzzyScore على المنتجات والأطراف والفواتير والشيكات)
    SettingRow(
        stringResource(R.string.set_search_sens),
        stringResource(R.string.set_search_sens_hint)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.30 to "0.30", 0.45 to "0.45", 0.60 to "0.60").forEach { (v, label) ->
                val active = kotlin.math.round(s.searchFuzzyThreshold * 100.0) / 100.0 == v
                Text(
                    label,
                    color = if (active) Color.White else g.textSecondary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (active) g.accent else g.textSecondary.copy(alpha = 0.12f))
                        .clickable { vm.setSearchFuzzyThreshold(v) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    // كلفة أمر الشراء — تُدخل في معادلة الكمية الاقتصادية EOQ
    // في لوحة رؤى المخزون (FinMath.eoq داخل InventoryVM)
    SettingRow(
        stringResource(R.string.set_eoq_cost),
        stringResource(R.string.set_eoq_cost_hint)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(10, 25, 50, 100).forEach { v ->
                val active = s.eoqOrderCost == v.toDouble()
                Text(
                    "$v",
                    color = if (active) Color.White else g.textSecondary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (active) g.accent else g.textSecondary.copy(alpha = 0.12f))
                        .clickable { vm.setEoqOrderCostSetting(v.toDouble()) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    SettingRow(
        stringResource(R.string.set_row_error_log),
        stringResource(R.string.set_row_error_log_hint)
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(g.accent.copy(alpha = 0.18f))
                .clickable { onOpenErrorLog() }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(stringResource(R.string.set_btn_view), color = g.accent, style = MaterialTheme.typography.labelLarge)
        }
    }
    Spacer(Modifier.height(8.dp))
    // فحص صحة البيانات — شقيق صف سجل الأخطاء (نفس النمط تماماً)
    SettingRow(
        stringResource(R.string.health_title),
        stringResource(R.string.health_idle)
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(g.accent.copy(alpha = 0.18f))
                .clickable { onOpenHealth() }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(stringResource(R.string.set_btn_view), color = g.accent, style = MaterialTheme.typography.labelLarge)
        }
    }
    Spacer(Modifier.height(8.dp))

    // ══ [P46-W1] جولة 7 — بطاقة الولاء والكوبونات ══
    var showCoupons by remember { mutableStateOf(false) }
    GlassCard(corner = 18.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(stringResource(R.string.loyalty_title), color = g.textPrimary, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.loyalty_settings_hint),
                color = g.textSecondary, style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.loyalty_enable),
                    color = g.textPrimary, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = s.loyaltyEnabled,
                    onCheckedChange = { vm.setLoyaltyEnabled(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = VioDeep)
                )
            }
            if (s.loyaltyEnabled) {
                Spacer(Modifier.height(6.dp))
                // الكسب: قروش صافية لكل نقطة — 500/1000/2000 (كل 5/10/20 ريال نقطة)
                SettingRow(
                    stringResource(R.string.loyalty_earn_label),
                    stringResource(R.string.loyalty_earn_hint, s.loyaltyEarnDivisor)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(500L, 1000L, 2000L).forEach { d ->
                            val active = s.loyaltyEarnDivisor == d
                            Text(
                                "$d",
                                color = if (active) Color.White else g.textSecondary,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (active) g.accent else g.textSecondary.copy(alpha = 0.12f))
                                    .clickable { vm.setLoyaltyEarnDivisor(d) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // الاستبدال: قروش قيمة النقطة — 5/10/25 (جوهر 0.5%/1%/2.5%)
                SettingRow(
                    stringResource(R.string.loyalty_value_label),
                    stringResource(R.string.loyalty_value_hint, s.loyaltyPointValue)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(5L, 10L, 25L).forEach { v ->
                            val active = s.loyaltyPointValue == v
                            Text(
                                "$v",
                                color = if (active) Color.White else g.textSecondary,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (active) g.accent else g.textSecondary.copy(alpha = 0.12f))
                                    .clickable { vm.setLoyaltyPointValue(v) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // إدارة الكوبونات — حوار القائمة والإضافة
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(g.accent.copy(alpha = 0.18f))
                        .clickable { showCoupons = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(stringResource(R.string.coupons_manage), color = g.accent, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
    if (showCoupons) {
        val loyaltyCtx = androidx.compose.ui.platform.LocalContext.current
        val loyaltyVM: LoyaltyVM = androidx.lifecycle.viewmodel.compose.viewModel(
            viewModelStoreOwner = loyaltyCtx as androidx.lifecycle.ViewModelStoreOwner,
            factory = remember { com.superbiz.app.VMFactory(loyaltyCtx) }
        )
        CouponManagerDialog(loyaltyVM) { showCoupons = false }
    }
    Spacer(Modifier.height(8.dp))
}

/**
 * [P46-W1] جولة 7 — حوار إدارة الكوبونات: قائمة حية (تفعيل/حذف) + نموذج إضافة
 * بحرس القيم (كود فارغ/قيمة صفرية تُرفض في LoyaltyVM قبل القاعدة).
 */
@Composable
private fun CouponManagerDialog(vm: LoyaltyVM, onDismiss: () -> Unit) {
    val g = glassColors()
    val coupons by vm.coupons.collectAsState()
    var code by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(com.superbiz.app.domain.LoyaltyP46.KIND_FIXED) }
    var valueText by remember { mutableStateOf("") }
    var maxUsesText by remember { mutableStateOf("") }
    var daysText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val toast by vm.toast.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(toast) {
        toast?.let { res ->
            android.widget.Toast.makeText(ctx, ctx.getString(res), android.widget.Toast.LENGTH_SHORT).show()
            vm.consumeToast()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.coupons_manage), color = g.textPrimary) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // القائمة الحية
                coupons.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                c.code + if (c.active) "" else " (" + stringResource(R.string.coupon_inactive_tag) + ")",
                                color = if (c.active) g.textPrimary else g.textSecondary,
                                fontWeight = FontWeight.Bold, fontSize = 13.sp
                            )
                            Text(
                                couponValueLabel(c),
                                color = g.textSecondary, fontSize = 11.sp
                            )
                        }
                        Text(
                            if (c.active) stringResource(R.string.coupon_stop) else stringResource(R.string.coupon_start),
                            color = g.accent, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { vm.toggleActive(c) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Rounded.Delete, stringResource(R.string.coupon_delete),
                            tint = com.superbiz.app.ui.theme.RedDeep,
                            modifier = Modifier
                                .padding(4.dp)
                                .clickable { vm.deleteCoupon(c.id) }
                        )
                    }
                }
                if (coupons.isEmpty()) {
                    Text(stringResource(R.string.coupons_empty), color = g.textSecondary, fontSize = 12.sp)
                }
                Spacer(Modifier.height(10.dp))
                BizField(code, { code = it }, stringResource(R.string.coupon_code))
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        com.superbiz.app.domain.LoyaltyP46.KIND_FIXED to stringResource(R.string.coupon_kind_fixed),
                        com.superbiz.app.domain.LoyaltyP46.KIND_PERCENT to stringResource(R.string.coupon_kind_percent)
                    ).forEach { (k, label) ->
                        val active = kind == k
                        Text(
                            label,
                            color = if (active) Color.White else g.textSecondary,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (active) g.accent else g.textSecondary.copy(alpha = 0.12f))
                                .clickable { kind = k }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                BizField(
                    valueText, { valueText = it },
                    if (kind == com.superbiz.app.domain.LoyaltyP46.KIND_FIXED)
                        stringResource(R.string.coupon_value_fixed) else stringResource(R.string.coupon_value_percent),
                    keyboard = numberFieldOptions()
                )
                Spacer(Modifier.height(6.dp))
                BizField(maxUsesText, { maxUsesText = it }, stringResource(R.string.coupon_max_uses), keyboard = numberFieldOptions())
                Spacer(Modifier.height(6.dp))
                BizField(daysText, { daysText = it }, stringResource(R.string.coupon_days), keyboard = numberFieldOptions())
                Spacer(Modifier.height(6.dp))
                BizField(note, { note = it }, stringResource(R.string.coupon_note))
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(g.accent.copy(alpha = 0.18f))
                        .clickable {
                            vm.addCoupon(code, kind, valueText, valueText, maxUsesText, daysText, note)
                            code = ""; valueText = ""; maxUsesText = ""; daysText = ""; note = ""
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(stringResource(R.string.coupon_add), color = g.accent, style = MaterialTheme.typography.labelLarge)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.coupons_done), color = g.accent) }
        }
    )
}

/** وصف قيمة الكوبون بسطر واحد — الثابت بالريال والنسبة بالمئة (عرض فقط) */
private fun couponValueLabel(c: CouponEntity): String {
    val kindLabel = if (c.kind == com.superbiz.app.domain.LoyaltyP46.KIND_PERCENT)
        com.superbiz.app.util.NumText.parseNum(c.percent.toString()).toString() + "%"
    else Money.formatP(c.amountPiasters, "ر.س")
    val uses = if (c.maxUses > 0) "${c.usedCount}/${c.maxUses}"
        else c.usedCount.toString() + "∞"
    val expiry = if (c.expiresAt > 0L)
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(c.expiresAt))
    else null
    return buildString {
        append(kindLabel).append(" · ").append(uses)
        expiry?.let { append(" · ").append(it) }
    }
}

@Composable
private fun AboutSection() {
    val g = glassColors()
    GlassCard(corner = 18.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            // [P6-M51 إصلاح] توطين قسم «حول» — الإصدار من BuildConfig بدل النص الصلب الخاطئ
            Text(stringResource(R.string.set_about_title), style = MaterialTheme.typography.titleMedium, color = g.textPrimary)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(
                    R.string.set_about_version,
                    com.superbiz.app.BuildConfig.VERSION_NAME,
                    com.superbiz.app.BuildConfig.VERSION_CODE
                ),
                style = MaterialTheme.typography.bodySmall, color = g.textSecondary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.set_about_desc),
                style = MaterialTheme.typography.bodySmall, color = g.textSecondary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.set_about_components),
                style = MaterialTheme.typography.bodySmall, color = g.textSecondary
            )
        }
    }
}

// ═══════════════════ [P13-b] النسخ الاحتياطي والاستعادة () ═══════════════════
// تصدير كل الجداول إلى ملف JSON عبر SAF (CreateDocument — بلا أي إذن تخزين)، واستعادة
// بالدمج: الأطراف والمنتجات غير الموجودة فقط (نفس id يُتخطى، والفواتير/القيود لا تُمسّ
// عمداً حفاظاً على اتساق القيد المزدوج — انظر BackupKit).
// الاعتماد يُجلب مباشرة عبر AppGraph (نمط VisitsSection) — بلا قنوات جديدة عبر الـVM.

@Composable
private fun BackupCard(
    // [P36-BK] VM مالك العمل — لنمط الاستعادة الصريح (دمج/استبدال كامل عبر ReAuth)
    settingsVM: SettingsVM
) {
    val g = glassColors()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember {
        BackupRestoreRepo(
            com.superbiz.app.AppGraph.from(context.applicationContext).db,
            context.applicationContext
        )
    }
    // busy: "export" | "import" | null — يجمّد الزرين طوال العملية ويعرض مؤشر التقدم
    var busy by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var importStats by remember { mutableStateOf<ImportStats?>(null) }
    // [P36-BK] نمط الاستعادة الصريح: دمجية (افتراضي آمن — الأطراف/المنتجات/الزيارات/المصروفات/
    // الشيكات/الخطط/الأقساط/الكشوف غير الموجودة فقط) أو استبدال كامل (مسح وإعادة إدخال —
    // نفس مسار ProfileScreen المحمي بـ ReAuth حارس H-3)
    var replaceMode by remember { mutableStateOf(false) }
    var pendingReplaceUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var reAuthForReplace by remember { mutableStateOf(false) }

    // قارب التصدير — تسجيل غير شرطي في أول التركيب (نفس موضع/دورة قوارب FavoritesScreen)
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let {
        busy = "export"
        scope.launch {
            val r = repo.exportTo(it)
            busy = null
            r.fold(
                onSuccess = { n ->
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.bk_export_ok, n),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                },
                onFailure = { e -> errorMsg = e.backupDisplayMessage(context) }
            )
        }
    } }
    // قارب الاستعادة — mime json + octet-stream: مديرو الملفات كثيراً ما يخلطون التسمية
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let {
        // [P36-BK] الوضع الدمجي عبر BackupRestoreRepo (سلوك البطاقة الأصلي)؛ الوضع
        // الاستبدالي عبر SettingsVM.importFrom (BackupRepo) — ببوابة ReAuth نفسها:
        // مع رمز ⇒ حوار إعادة مصادقة أولاً، بلا رمز ⇒ تنفيذ مباشر (نمط ProfileScreen H-3 حرفياً)
        if (replaceMode) {
            val st = settingsVM.settings.value
            if (st.pinHash != null || st.pinBlob != null) {
                pendingReplaceUri = uri
                reAuthForReplace = true
            } else {
                settingsVM.importFrom(uri)
            }
        } else {
            busy = "import"
            scope.launch {
                val r = repo.importFrom(it)
                busy = null
                r.fold(
                    onSuccess = { stats -> importStats = stats },
                    onFailure = { e -> errorMsg = e.backupDisplayMessage(context) }
                )
            }
        }
    } }

    GlassCard(corner = 18.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                stringResource(R.string.bk_title),
                color = g.textPrimary,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                // [P15-b] bk_desc3: الوصف يذكر الشيكات وخطط الأقساط أيضاً — نطاق v3 (bk_desc2 متروك)
                stringResource(R.string.bk_desc3),
                color = g.textSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(6.dp))
            // [P36-BK] رقاقة التغطية — صادقة للمسارين معاً بعد توحيد exportJson
            val cov = com.superbiz.app.domain.backup.BACKUP_TABLE_COUNT
            BizPill(
                stringResource(R.string.p36_bk_coverage, cov, cov),
                g.accent, mode = PillMode.BADGE
            )
            Spacer(Modifier.height(6.dp))
            // [P36-BK] نمط الاستعادة الصريح — الخيار الافتراضي الدمجي يبقى محدداً
            Text(
                stringResource(R.string.p36_bk_mode_title),
                color = g.textPrimary, style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(false to R.string.p36_bk_mode_merge, true to R.string.p36_bk_mode_replace).forEach { (v, res) ->
                    val active = replaceMode == v
                    Text(
                        stringResource(res),
                        color = if (active) Color.White else g.textSecondary,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (active) g.accent else g.textSecondary.copy(alpha = 0.12f))
                            .clickable { replaceMode = v }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
            if (replaceMode) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.p36_bk_replace_sub),
                    color = g.textSecondary, style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BackupPill(
                    text = stringResource(R.string.bk_export_btn),
                    enabled = busy == null,
                    accent = g.accent
                ) {
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                        .format(java.util.Date())
                    exportLauncher.launch("superbiz-backup-$stamp.json")
                }
                BackupPill(
                    text = stringResource(R.string.bk_import_btn),
                    enabled = busy == null,
                    accent = g.accent
                ) {
                    importLauncher.launch(arrayOf("application/json", "application/octet-stream"))
                }
            }
            if (busy != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(14.dp).width(14.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (busy == "export") stringResource(R.string.bk_busy_export)
                        else stringResource(R.string.bk_busy_import),
                        color = g.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            // تحذير دائم قبل الاستعادة — [P15-b] bk_warn3 يذكر الجداول السبعة (bk_warn2 القديم متروك)
            Text(
                stringResource(R.string.bk_warn3),
                color = g.textSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    // حوار نجاح الاستعادة: إحصاءات الدمج + الجداول المكتشفة
    importStats?.let { st ->
        AlertDialog(
            onDismissRequest = { importStats = null },
            title = { Text(stringResource(R.string.bk_import_ok_title), color = g.textPrimary) },
            text = {
                Column {
                    Text(
                        stringResource(
                            R.string.bk_import_ok_line,
                            st.importedParties, st.skippedParties,
                            st.importedProducts, st.skippedProducts
                        ),
                        color = g.textPrimary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    // [P14-b] نطاق سطرا الزيارات والمصروفات — لا يظهران إلا إن وُجدت صفوف مناسبة في الملف
                    if (st.importedVisits > 0 || st.skippedVisits > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.bk_import_visits_line, st.importedVisits, st.skippedVisits),
                            color = g.textSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (st.importedExpenses > 0 || st.skippedExpenses > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.bk_import_expenses_line, st.importedExpenses, st.skippedExpenses),
                            color = g.textSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    // [P15-b] نطاق أسطر الشيكات وخطط الأقساط والأقساط — لا تظهر إلا إن وُجدت صفوف مناسبة
                    if (st.importedChecks > 0 || st.skippedChecks > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.bk_import_checks_line, st.importedChecks, st.skippedChecks),
                            color = g.textSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (st.importedPlans > 0 || st.skippedPlans > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.bk_import_plans_line, st.importedPlans, st.skippedPlans),
                            color = g.textSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (st.importedInstallments > 0 || st.skippedInstallments > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.bk_import_installments_line, st.importedInstallments, st.skippedInstallments),
                            color = g.textSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (st.failed > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.bk_import_failed_rows, st.failed),
                            color = g.textSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // ملخص الجداول المكتشفة (غير الصفرية فقط كي لا يزاحم الصفر الرسالة)
                    val tablesSummary = st.tablesFound.filterValues { it > 0 }
                        .entries.joinToString(" · ") { "${it.key} ${it.value}" }
                        .ifEmpty { "—" }
                    Text(
                        stringResource(R.string.bk_tables_line, tablesSummary),
                        color = g.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { importStats = null }) {
                    Text(stringResource(R.string.bk_ok), color = g.accent)
                }
            }
        )
    }
    // حوار الفشل: رسالة عربية واضحة (تلف/إصدار أحدث/حجم/وصول) لا رمي استثناء صامت
    errorMsg?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorMsg = null },
            title = { Text(stringResource(R.string.bk_fail_title), color = g.textPrimary) },
            text = { Text(msg, color = g.textPrimary, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { errorMsg = null }) {
                    Text(stringResource(R.string.bk_ok), color = g.accent)
                }
            }
        )
    }
    // [P36-BK] بوابة ReAuth للاستبدال الكامل — نفس حوار ProfileScreen ونفس رسالة import_reauth
    if (reAuthForReplace) {
        ReAuthDialog(
            settingsVM = settingsVM,
            message = stringResource(R.string.import_reauth),
            onVerified = {
                reAuthForReplace = false
                pendingReplaceUri?.let { settingsVM.importFrom(it) }
                pendingReplaceUri = null
            },
            onDismiss = {
                reAuthForReplace = false
                pendingReplaceUri = null
            }
        )
    }
}

/** [P13-b] زر كبسولة بنمط الشاشة نفسها — يتعطّل بصرياً وفعلياً أثناء العملية الجارية */
@Composable
private fun BackupPill(
    text: String,
    enabled: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    Text(
        text,
        color = if (enabled) accent else accent.copy(alpha = 0.45f),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) accent.copy(alpha = 0.18f) else accent.copy(alpha = 0.06f))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

/** [P13-b] رسالة عرض للخطأ: أخطاء النسخة تجلب نصاً عربياً جاهزاً، والبقية تُغلَّف برسالة عامة */
private fun Throwable.backupDisplayMessage(context: android.content.Context): String = when (this) {
    is BackupFormatException -> message ?: context.getString(R.string.bk_err_unsupported)
    is java.io.IOException -> context.getString(R.string.bk_err_io, message ?: "")
    else -> context.getString(R.string.bk_err_generic, message ?: this::class.java.simpleName)
}
