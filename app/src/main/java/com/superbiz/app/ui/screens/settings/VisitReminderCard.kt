package com.superbiz.app.ui.screens.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.superbiz.app.R
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.util.startIntentSafe
import com.superbiz.app.work.ExactAlarms
import com.superbiz.app.work.VisitReminder
import java.util.Locale

/**
 * [P14-a] بطاقة «تنبيه الزيارات المتأخرة» في مركز الإعدادات — قاربة ذاتية تماماً:
 * بلا معاملات ولا تبعيات مُمرَّرة (تناقض VisitsSection نفسه) كي يُدرج استدعاء
 * `VisitReminderCard()` في SettingsHubScreen.DataSection كما هو.
 *
 * - الحالة (تفعيل/وقت/عتبة) تُقرأ من prefs ميزة VisitReminder مباشرة عبر دوالّها،
 *   ولا تتخزن نسخة موازية في DataStore ولا في VM — مفتاح الحقيقة واحد.
 * - إذنا POST_NOTIFICATIONS (API 33+) وSCHEDULE_EXACT_ALARM (API 31+) يُفحصان
 *   حيّين عند كل عودة للواجهة (نمط PermissionsScreen مع LifecycleEventObserver)،
 *   ويتحول كل نقص إلى صف تنبيه داخلي بزر انتقال للإعداد المناسب.
 * - اختيار الوقت: TimePicker من M3 داخل AlertDialog — بلا مكتبات إضافية،
 *   وبنظام 24 ساعة (المعتاد في المنطقة).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisitReminderCard() {
    val g = glassColors()
    val context = LocalContext.current

    // ─── حالة الإعداد من prefs VisitReminder (قراءة أولى مباشرة، والكتابة تُحدّثها محلياً) ───
    var enabled by remember { mutableStateOf(VisitReminder.enabled(context)) }
    var hour by remember { mutableStateOf(VisitReminder.hour(context)) }
    var minute by remember { mutableStateOf(VisitReminder.minute(context)) }
    var threshold by remember { mutableStateOf(VisitReminder.threshold(context)) }
    var showTimePicker by remember { mutableStateOf(false) }

    // ─── فحص الإذنين حيّاً — إعادة الفحص عند العودة من إعدادات النظام (نمط PermissionsScreen) ───
    var notifGranted by remember { mutableStateOf(notificationsAllowedNow(context)) }
    var exactOk by remember { mutableStateOf(ExactAlarms.canSchedule(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                notifGranted = notificationsAllowedNow(context)
                exactOk = ExactAlarms.canSchedule(context)
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // التوقيع الزمني للأرقام لاتيني موحّد (نمط SimpleDateFormat(Locale.US) في الملف الأم)
    val timeLabel = String.format(Locale.US, "%02d:%02d", hour, minute)

    GlassCard(corner = 18.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {

            // العنوان + الوصف + المفتاح
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.vr_title),
                        color = g.textPrimary,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        stringResource(R.string.vr_desc),
                        color = g.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = enabled,
                    onCheckedChange = { on ->
                        // التخزين عبر ميزة VisitReminder نفسها: تفعيل يسلّح المنبّه فوراً،
                        // تعطيل يلغيه — ثم تُحدَّث الحالة المحلية للانعكاس الفوري
                        VisitReminder.setEnabled(context, on)
                        enabled = on
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (enabled) stringResource(R.string.vr_status_on, timeLabel)
                else stringResource(R.string.vr_status_off),
                color = g.textSecondary,
                style = MaterialTheme.typography.bodySmall
            )

            // صف الوقت — النقر يفتح منتقي M3
            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { showTimePicker = true }
                    .padding(horizontal = 4.dp, vertical = 6.dp)
            ) {
                Text(
                    stringResource(R.string.vr_row_time),
                    color = g.textPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    timeLabel,
                    color = g.accent,
                    style = MaterialTheme.typography.labelLarge
                )
            }

            // صف العتبة — 3 رقائق (7/14/30) تخزَّن في prefs الميزة
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.vr_threshold, threshold),
                color = g.textPrimary,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // [P20-FIX agent6]: نفس قائمة شاشة الأذونات (كانت 7/14/30 هنا و1/3/7 هناك —
                // قيمة مختارة من واحدة تُظهر بلا رقاقة فعّالة في الأخرى)
                listOf(1, 3, 7, 14, 30).forEach { d ->
                    VrChip(
                        text = stringResource(R.string.set_backup_days_short, d),
                        active = threshold == d,
                        accent = g.accent,
                        textSecondary = g.textSecondary
                    ) {
                        VisitReminder.setThreshold(context, d)
                        threshold = d
                    }
                }
            }

            // إذن الإشعارات ناقص (API 33+) — صف تنبيه + زر إعدادات التطبيق
            if (enabled && !notifGranted) {
                Spacer(Modifier.height(8.dp))
                VrHintRow(
                    text = stringResource(R.string.vr_need_notif_perm),
                    button = stringResource(R.string.perm_open_settings),
                    amber = g.amber,
                    accent = g.accent
                ) {
                    startIntentSafe(
                        context,
                        Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                }
            }
            // التنبيهات الدقيقة غير ممنوحة (API 31+) — تنبيه بتأخير محتمل + زر شاشة النظام
            if (enabled && !exactOk) {
                Spacer(Modifier.height(8.dp))
                VrHintRow(
                    text = stringResource(R.string.vr_need_exact),
                    button = stringResource(R.string.perm_open_settings),
                    amber = g.amber,
                    accent = g.accent
                ) {
                    ExactAlarms.openSettings(context)
                }
            }
        }
    }

    // ─── منتقي الوقت — TimePicker (M3) داخل AlertDialog، بنظام 24 ساعة ───
    if (showTimePicker) {
        val tpState = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = {
                Text(stringResource(R.string.vr_time_dialog_title), color = g.textPrimary)
            },
            text = { TimePicker(state = tpState) },
            confirmButton = {
                TextButton(onClick = {
                    VisitReminder.setTime(context, tpState.hour, tpState.minute)
                    hour = tpState.hour
                    minute = tpState.minute
                    showTimePicker = false
                }) { Text(stringResource(R.string.confirm), color = g.accent) }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text(stringResource(R.string.cancel), color = g.textSecondary)
                }
            }
        )
    }
}

/** [P14-a] فحص إذن الإشعارات: مدمج قبل API 33، وبعده إذن وقت تشغيل (نمط PermissionsScreen) */
private fun notificationsAllowedNow(ctx: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

/** [P14-a] رقاقة اختيار بأسلوب بطاقات المركز نفسه (DataSection) — بلا APIs تجريبية */
@Composable
private fun VrChip(
    text: String,
    active: Boolean,
    accent: Color,
    textSecondary: Color,
    onClick: () -> Unit
) {
    Text(
        text,
        color = if (active) Color.White else textSecondary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) accent else textSecondary.copy(alpha = 0.12f))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

/** [P14-a] صف تنبيه صغير داخل البطاقة + زر انتقال للإعداد المناسب */
@Composable
private fun VrHintRow(
    text: String,
    button: String,
    amber: Color,
    accent: Color,
    onClick: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            color = amber,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            button,
            color = accent,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.18f))
                .clickable { onClick() }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}
