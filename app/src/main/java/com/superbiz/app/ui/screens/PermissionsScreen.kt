package com.superbiz.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
// [P16-b] Arrangement لصفّ رقائق العتبة في منتقي البطاقة الثامنة
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Alarm
// [P16-b] أيقونة البطاقة الثامنة — التذكير اليومي
import androidx.compose.material.icons.rounded.AlarmOn
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContactPage
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.VerifiedUser
// [P16-b] منتقي الوقت والعتبة (نمط VisitReminderCard في مركز الإعدادات)
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.superbiz.app.R
// [P16-b] سياسة فرع بطاقة التذكير اليومي (منطق نقي قابل للاختبار)
import com.superbiz.app.domain.algo.ReminderUiPolicy
import com.superbiz.app.domain.algo.ReminderUiState
import com.superbiz.app.security.BiometricGate
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.components.IconChip
import com.superbiz.app.ui.theme.Amber
import com.superbiz.app.ui.theme.Blue
import com.superbiz.app.ui.theme.Cyan
import com.superbiz.app.ui.theme.Green
import com.superbiz.app.ui.theme.Pink
import com.superbiz.app.ui.theme.Vio
import com.superbiz.app.ui.theme.VioDeep
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.util.startIntentSafe
import com.superbiz.app.vm.SettingsVM
// [P16-b] ميزة التذكير اليومي — الحالة والفعّلان من prefs الميزة مباشرة
import com.superbiz.app.work.VisitReminder
import kotlinx.coroutines.delay

/** فحص إذن بشكل مباشر */
private fun granted(ctx: Context, p: String): Boolean =
    ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

/** الإشعارات مدمجة قبل API 33 (لا تحتاج إذن وقت التشغيل) */
private fun notifBuiltin(): Boolean = Build.VERSION.SDK_INT < 33

/** البلوتوث القديم مدمج قبل API 31 */
private fun btBuiltin(): Boolean = Build.VERSION.SDK_INT < 31

/** جهات الاتصال: إذن وقت تشغيل قياسي منذ API 23 — لا حالات خاصة */
private fun contactsGrantedNow(ctx: Context): Boolean =
    granted(ctx, Manifest.permission.READ_CONTACTS)

/** [P11-b] جهات الاتصال — كتابة (التصدير العكسي): إذن وقت تشغيل قياسي منذ API 23 */
private fun wContactsGrantedNow(ctx: Context): Boolean =
    granted(ctx, Manifest.permission.WRITE_CONTACTS)

/** [P11-b] الموقع: تكفي التامة أو التقريبية — بطاقة المفضلات لا تحتاج دقة أعلى */
private fun locationGrantedNow(ctx: Context): Boolean =
    granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ||
        granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)

/** [P11-b] التنبيهات الدقيقة: مدمجة قبل API 31، وبعدها من شاشة النظام */
private fun exactAlarmsAllowed(ctx: Context): Boolean =
    Build.VERSION.SDK_INT < 31 ||
        (ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).canScheduleExactAlarms()

/**
 * شاشة طلب الأذونات وإمكانية الوصول —، مطوّرة في P10.
 * تظهر مرة واحدة بعد شاشة الترحيب، وتُعاد من الملف الشخصي ← الأذونات.
 * - بطاقات حية: الكاميرا / الإشعارات / البلوتوث / جهات الاتصال مع حالة لحظية وزر تفعيل.
 * - P10: بطاقة جهات الاتصال (استيراد العملاء والموردين) + بطاقة التخزين التوضيحية (SAF مدمج بلا إذن).
 * - [P11-b] بطاقات إضافية: الموقع الجغرافي (المفضلات) + كتابة جهات الاتصال (التصدير
 * العكسي) + التنبيهات الدقيقة (تذكيرات الأقساط في وقتها) — التقدم صار /7.
 * - [P16-b] بطاقة «التذكير اليومي» — الثامنة: إعدادٌ لا إذن وقت تشغيل (مفتاح Switch
 * + وقت + عتبة من ميزة work/VisitReminder)، والتحذير الكهرماني بلا حجب — التقدم صار /8.
 * - بطاقة «إمكانية الوصول الكاملة»: تفتح صفحة التطبيق في إعدادات النظام.
 * - إعادة فحص تلقائية عند العودة من الإعدادات (ON_RESUME).
 * - كل الأذونات اختيارية: زر المتابعة متاح دائماً — التطبيق يعمل بلا إنترنت أولاً.
*/
@Composable
fun PermissionsScreen(settingsVM: SettingsVM? = null, onDone: () -> Unit) {
    val g = glassColors()
    val context = LocalContext.current

    // منع النداء المزدوج
    var finished by remember { mutableStateOf(false) }
    fun finish() { if (!finished) { finished = true; onDone() } }

    // الرجوع = إتمام (الأذونات اختيارية ولا نحبس المستخدم)
    BackHandler { finish() }

    // ─── حالة البيومتريا (: تفعيل سريع للتأمين) ───
    val activity = context as? FragmentActivity
    val settings = settingsVM?.settings?.collectAsState()?.value
    // [P20-FIX agent6]: استدعاء binder IPC (canAuthenticate) كان على كل إطار — حركات النبض
    // اللانهائية في هذه الشاشة تعيد التركيب ~60×/ث. تُقرأ مرة وتُحدَّث مع ON_RESUME
    val bioCheck = remember { if (activity != null) BiometricGate.check(activity) else BiometricGate.UNKNOWN }
    val bioAvailable = bioCheck == BiometricGate.OK
    val bioOn = settings?.biometric == true
    var bioBusy by remember { mutableStateOf(false) }

    // ─── الحالات اللحظية للأذونات ───
    var camGranted by remember { mutableStateOf(granted(context, Manifest.permission.CAMERA)) }
    var notifGranted by remember {
        mutableStateOf(notifBuiltin() || granted(context, Manifest.permission.POST_NOTIFICATIONS))
    }
    var btGranted by remember {
        mutableStateOf(btBuiltin() || granted(context, Manifest.permission.BLUETOOTH_CONNECT))
    }
    // [P10] جهات الاتصال — بطاقة رابعة حية
    var contactsGranted by remember { mutableStateOf(contactsGrantedNow(context)) }
    // [P11-b] الموقع + كتابة جهات الاتصال + التنبيهات الدقيقة — البطاقات 5/6/7
    var locGranted by remember { mutableStateOf(locationGrantedNow(context)) }
    var wContactsGranted by remember { mutableStateOf(wContactsGrantedNow(context)) }
    var exactGranted by remember { mutableStateOf(exactAlarmsAllowed(context)) }
    // [P16-b] بطاقة التذكير اليومي — الثامنة (إعداد لا إذن): الحالة تُقرأ من prefs
    // ميزة VisitReminder مباشرة، مفتاح حقيقةٍ واحد ولا نسخة موازية في DataStore/VM.
    // الوقت والعتبة تُمرَّر للبطاقة وتُحدَّث هنا عند ON_RESUME كي ينعكس ما يُعدَّل
    // من مركز الإعدادات على هذه الشاشة دون إعادة تركيب.
    var reminderOn by remember { mutableStateOf(VisitReminder.enabled(context)) }
    var reminderHour by remember { mutableStateOf(VisitReminder.hour(context)) }
    var reminderMinute by remember { mutableStateOf(VisitReminder.minute(context)) }
    var reminderThreshold by remember { mutableStateOf(VisitReminder.threshold(context)) }

    val camLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        camGranted = it
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifGranted = it
    }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        btGranted = it
    }
    val contactsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        contactsGranted = it
    }
    // [P11-b] مزجّدا الموقع والكتابة — إذنا وقت تشغيل قياسيان
    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        locGranted = it
    }
    val wContactsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        wContactsGranted = it
    }

    // إعادة الفحص الحي عند العودة من إعدادات النظام
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                camGranted = granted(context, Manifest.permission.CAMERA)
                notifGranted = notifBuiltin() || granted(context, Manifest.permission.POST_NOTIFICATIONS)
                btGranted = btBuiltin() || granted(context, Manifest.permission.BLUETOOTH_CONNECT)
                contactsGranted = contactsGrantedNow(context)
                // [P11-b] إعادة فحص البطاقات الجديدة عند العودة من إعدادات النظام
                locGranted = locationGrantedNow(context)
                wContactsGranted = wContactsGrantedNow(context)
                exactGranted = exactAlarmsAllowed(context)
                // [P16-b] إعادة قراءة إعداد التذكير عند العودة (قد يتغير من مركز الإعدادات)
                reminderOn = VisitReminder.enabled(context)
                reminderHour = VisitReminder.hour(context)
                reminderMinute = VisitReminder.minute(context)
                reminderThreshold = VisitReminder.threshold(context)
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // ─── مراحل الظهور المتدرج ───
    var s1 by remember { mutableStateOf(false) }  // الدرع
    var s2 by remember { mutableStateOf(false) }  // العنوان والوصف
    var s3a by remember { mutableStateOf(false) } // الكاميرا
    var s3b by remember { mutableStateOf(false) } // الإشعارات
    var s3c by remember { mutableStateOf(false) } // البلوتوث
    var s3f by remember { mutableStateOf(false) } // [P10] جهات الاتصال
    var s3g by remember { mutableStateOf(false) } // [P10] التخزين التوضيحية
    var s3h by remember { mutableStateOf(false) } // [P11-b] الموقع الجغرافي
    var s3i by remember { mutableStateOf(false) } // [P11-b] كتابة جهات الاتصال
    var s3j by remember { mutableStateOf(false) } // [P11-b] التنبيهات الدقيقة
    var s3k by remember { mutableStateOf(false) } // [P16-b] التذكير اليومي
    var s3d by remember { mutableStateOf(false) } // الوصول الكامل
    var s3e by remember { mutableStateOf(false) } // تأمين التطبيق
    var s4 by remember { mutableStateOf(false) }  // التقدم والمتابعة

    LaunchedEffect(Unit) {
        delay(80);  s1 = true
        delay(240); s2 = true
        delay(420); s3a = true
        delay(540); s3b = true
        delay(660); s3c = true
        delay(780); s3f = true
        delay(900); s3g = true
        delay(1020); s3h = true
        delay(1140); s3i = true
        delay(1260); s3j = true
        delay(1380); s3k = true // [P16-b] البطاقة الثامنة — ما بعد الدقيقة مباشرة
        delay(1500); s3d = true
        delay(1620); s3e = true
        delay(1760); s4 = true
    }

    // نبض الدرع + لمعان مستعرض
    val infinite = rememberInfiniteTransition(label = "perm")
    val pulse by infinite.animateFloat(
        initialValue = 1f, targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "pulse"
    )
    val sweep by infinite.animateFloat(
        initialValue = -220f, targetValue = 220f,
        animationSpec = infiniteRepeatable(tween(2300, easing = LinearEasing)), label = "sweep"
    )
    val orb by infinite.animateFloat(
        initialValue = -24f, targetValue = 26f,
        animationSpec = infiniteRepeatable(tween(3600), RepeatMode.Reverse), label = "orb"
    )

    // دخول الدرع بارتداد نابضي
    val shieldScale by animateFloatAsState(
        targetValue = if (s1) 1f else 0.3f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "shieldScale"
    )

    // ─── النصوص تُحل في السياق القابل للتركيب ───
    val grantedTotal = listOf(
        camGranted, notifGranted, btGranted, contactsGranted,
        locGranted, wContactsGranted, exactGranted,
        reminderOn // [P16-b] البطاقة الثامنة: التذكير اليومي — إعداد يدخل في التقدم
    ).count { it }
    // [P16-b] القسمة على 8 بدل 7 — ثماني بطاقات في العدّاد
    val frac by animateFloatAsState(targetValue = grantedTotal / 8f, label = "permFrac")

    val titleStr = stringResourceCompat(R.string.perm_title)
    val subStr = stringResourceCompat(R.string.perm_subtitle)
    val camT = stringResourceCompat(R.string.perm_camera_t)
    val camD = stringResourceCompat(R.string.perm_camera_d)
    val notifT = stringResourceCompat(R.string.perm_notif_t)
    val notifD = stringResourceCompat(R.string.perm_notif_d)
    val btT = stringResourceCompat(R.string.perm_bt_t)
    val btD = stringResourceCompat(R.string.perm_bt_d)
    val contactsT = stringResourceCompat(R.string.perm_contacts_t)
    val contactsD = stringResourceCompat(R.string.perm_contacts_d)
    // [P11-b] نصوص البطاقات الجديدة
    val locT = stringResourceCompat(R.string.perm_location_t)
    val locD = stringResourceCompat(R.string.perm_location_d)
    val wContactsT = stringResourceCompat(R.string.perm_wcontacts_t)
    val wContactsD = stringResourceCompat(R.string.perm_wcontacts_d)
    val exactT = stringResourceCompat(R.string.perm_exact_t)
    val exactD = stringResourceCompat(R.string.perm_exact_d)
    val storageT = stringResourceCompat(R.string.perm_storage_t)
    val storageD = stringResourceCompat(R.string.perm_storage_d)
    val fullT = stringResourceCompat(R.string.perm_full_t)
    val fullD = stringResourceCompat(R.string.perm_full_d)
    val grantedStr = stringResourceCompat(R.string.perm_granted)
    val enableStr = stringResourceCompat(R.string.perm_enable)
    val builtinStr = stringResourceCompat(R.string.perm_builtin)
    val openSettingsStr = stringResourceCompat(R.string.perm_open_settings)
    // [P16-b] اللاحقة الثانية صارت 8 — «اكتمل N من 8»
    val progressStr = stringResourceCompat(R.string.perm_progress, grantedTotal, 8)
    val continueStr = stringResourceCompat(R.string.perm_continue)
    val noteStr = stringResourceCompat(R.string.perm_note)

    // نصوص بطاقة التأمين السريع
    val secT = stringResourceCompat(R.string.perm_sec_t)
    val secD = stringResourceCompat(R.string.perm_sec_d)
    val secOn = stringResourceCompat(R.string.perm_sec_on)
    val secEnable = stringResourceCompat(R.string.perm_sec_enable)
    val cancelStr = stringResourceCompat(R.string.cancel)
    val bioReason = when (bioCheck) {
        BiometricGate.NO_ENROLLED -> stringResourceCompat(R.string.biometric_no_enrolled)
        else -> stringResourceCompat(R.string.biometric_no_hardware)
    }
    // [P5-H4 إصلاح]: رسالة الحارس — تُحلّ مسبقاً لأن enableBio() دالة عادية لا سياق تركيبي
    val pinReqBioMsg = stringResourceCompat(R.string.pin_required_bio)

    fun enableBio() {
        // [P5-H4 إصلاح]: لا بيومتريا بلا رمز — كان الزر هنا يُمكّن البصمة بلا PIN
        // فينتج حالة «متابعة (طارئ)» في شاشة القفل (تجاوز كامل). نمط ProfileScreen نفسه
        if (settings?.pinHash == null && settings?.pinBlob == null) {
            android.widget.Toast.makeText(context, pinReqBioMsg, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val a = activity ?: return
        if (bioBusy) return
        bioBusy = true
        BiometricGate.authenticate(
            activity = a,
            title = secT,
            subtitle = secD,
            negativeText = cancelStr,
            onSuccess = { bioBusy = false; settingsVM?.setBiometric(true) },
            onError = { bioBusy = false }
        )
    }

    fun openAppSettings() {
        try {
            context.startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)
                )
            )
        } catch (e: Exception) {
            // المستخدم يبقى عالقاً بلا تفسير — إشعار فوري
            android.widget.Toast.makeText(context, R.string.perm_open_settings_failed, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(g.bgGradient))
    ) {
        // كتل ضوء عائمة — نفس لغة الترحيب
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .graphicsLayer { translationY = orb }
                .padding(top = 50.dp, end = 20.dp)
                .size(220.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Vio.copy(alpha = if (g.isLight) 0.16f else 0.26f), Color.Transparent)))
        )
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .graphicsLayer { translationY = -orb * 1.3f }
                .padding(bottom = 70.dp, start = 16.dp)
                .size(240.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Cyan.copy(alpha = if (g.isLight) 0.13f else 0.20f), Color.Transparent)))
        )

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(34.dp))

            // ─── الدرع المتدرج مع لمعان ───
            Box(
                Modifier
                    .size(92.dp)
                    .scale(shieldScale * pulse)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Brush.linearGradient(listOf(VioDeep, Cyan))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.VerifiedUser, null, tint = Color.White, modifier = Modifier.size(44.dp))
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(26.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color.Transparent, Color.White.copy(alpha = 0.26f), Color.Transparent),
                                start = Offset(sweep - 110f, 0f),
                                end = Offset(sweep + 110f, 0f)
                            )
                        )
                )
            }

            Spacer(Modifier.height(18.dp))

            // ─── العنوان والوصف ───
            AnimatedVisibility(s2, enter = fadeIn(tween(420)) + slideInVertically(tween(420)) { it / 3 }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        titleStr,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = g.textPrimary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        subStr,
                        style = MaterialTheme.typography.bodyMedium,
                        color = g.textSecondary,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // ─── بطاقة الكاميرا ───
            AnimatedVisibility(s3a, enter = fadeIn(tween(340)) + slideInVertically(tween(340)) { it / 2 }) {
                PermissionCard(
                    icon = Icons.Rounded.QrCodeScanner, accent = Vio,
                    title = camT, desc = camD,
                    granted = camGranted, builtin = false,
                    grantedLabel = grantedStr, enableLabel = enableStr, builtinLabel = builtinStr,
                    onEnable = { camLauncher.launch(Manifest.permission.CAMERA) }
                )
            }
            Spacer(Modifier.height(10.dp))

            // ─── بطاقة الإشعارات ───
            AnimatedVisibility(s3b, enter = fadeIn(tween(340)) + slideInVertically(tween(340)) { it / 2 }) {
                PermissionCard(
                    icon = Icons.Rounded.NotificationsActive, accent = Cyan,
                    title = notifT, desc = notifD,
                    granted = notifGranted, builtin = notifBuiltin(),
                    grantedLabel = grantedStr, enableLabel = enableStr, builtinLabel = builtinStr,
                    onEnable = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                )
            }
            Spacer(Modifier.height(10.dp))

            // ─── بطاقة البلوتوث ───
            AnimatedVisibility(s3c, enter = fadeIn(tween(340)) + slideInVertically(tween(340)) { it / 2 }) {
                PermissionCard(
                    icon = Icons.Rounded.Print, accent = Green,
                    title = btT, desc = btD,
                    granted = btGranted, builtin = btBuiltin(),
                    grantedLabel = grantedStr, enableLabel = enableStr, builtinLabel = builtinStr,
                    onEnable = { btLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) }
                )
            }
            Spacer(Modifier.height(10.dp))

            // ─── بطاقة جهات الاتصال (P10) — استيراد العملاء والموردين ───
            AnimatedVisibility(s3f, enter = fadeIn(tween(340)) + slideInVertically(tween(340)) { it / 2 }) {
                PermissionCard(
                    icon = Icons.Rounded.Contacts, accent = Blue,
                    title = contactsT, desc = contactsD,
                    granted = contactsGranted, builtin = false,
                    grantedLabel = grantedStr, enableLabel = enableStr, builtinLabel = builtinStr,
                    onEnable = { contactsLauncher.launch(Manifest.permission.READ_CONTACTS) }
                )
            }
            Spacer(Modifier.height(10.dp))

            // ─── [P11-b] بطاقة الموقع الجغرافي — بطاقات العملاء المفضلين ───
            AnimatedVisibility(s3h, enter = fadeIn(tween(340)) + slideInVertically(tween(340)) { it / 2 }) {
                PermissionCard(
                    icon = Icons.Rounded.LocationOn, accent = Pink,
                    title = locT, desc = locD,
                    granted = locGranted, builtin = false,
                    grantedLabel = grantedStr, enableLabel = enableStr, builtinLabel = builtinStr,
                    onEnable = { locLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
                )
            }
            Spacer(Modifier.height(10.dp))

            // ─── [P11-b] بطاقة كتابة جهات الاتصال — التصدير العكسي ───
            AnimatedVisibility(s3i, enter = fadeIn(tween(340)) + slideInVertically(tween(340)) { it / 2 }) {
                PermissionCard(
                    icon = Icons.Rounded.ContactPage, accent = VioDeep,
                    title = wContactsT, desc = wContactsD,
                    granted = wContactsGranted, builtin = false,
                    grantedLabel = grantedStr, enableLabel = enableStr, builtinLabel = builtinStr,
                    onEnable = { wContactsLauncher.launch(Manifest.permission.WRITE_CONTACTS) }
                )
            }
            Spacer(Modifier.height(10.dp))

            // ─── [P11-b] بطاقة التنبيهات الدقيقة — تذكيرات الأقساط في وقتها ───
            AnimatedVisibility(s3j, enter = fadeIn(tween(340)) + slideInVertically(tween(340)) { it / 2 }) {
                PermissionCard(
                    icon = Icons.Rounded.Alarm, accent = Amber,
                    title = exactT, desc = exactD,
                    granted = exactGranted, builtin = Build.VERSION.SDK_INT < 31,
                    grantedLabel = grantedStr, enableLabel = enableStr, builtinLabel = builtinStr,
                    onEnable = {
                        // شاشة النظام هي السبيل الوحيد — لا إذن وقت تشغيل لهذه الصلاحية
                        startIntentSafe(
                            context,
                            Intent(
                                android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:" + context.packageName)
                            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        )
                    }
                )
            }
            Spacer(Modifier.height(10.dp))

            // ─── [P16-b] بطاقة التذكير اليومي — الثامنة (إعداد لا إذن): مفتاح Switch بدل زر تفعيل،
            //      والنقر على جسم البطاقة يفتح منتقي الوقت والعتبة ───
            AnimatedVisibility(s3k, enter = fadeIn(tween(340)) + slideInVertically(tween(340)) { it / 2 }) {
                ReminderPermissionCard(
                    accent = Amber,
                    enabled = reminderOn,
                    exactGranted = exactGranted,
                    hour = reminderHour,
                    minute = reminderMinute,
                    threshold = reminderThreshold,
                    onToggle = { on ->
                        // الحقيقة في prefs الميزة: setEnabled يبتلع أي فشل داخلياً بلا قيمة
                        // راجعة، فالقراءة العكسية بعد الكتابة هي التراجع الوحيد الممكن —
                        // إن فشلت الكتابة يعود المفتاح لحالته الحقيقية فوراً.
                        // إذن التنبيهات الدقيقة ليس شرطاً: بلا تذكّر يهبط scheduleNext إلى
                        // منبّه غير دقيق (setAndAllowWhileIdle) يعمل بتأخير محتمل — يكفيه
                        // صفّ التحذير الكهرماني في البطاقة، ولا حجب ولا إعادة جدولة فاشلة.
                        VisitReminder.setEnabled(context, on)
                        reminderOn = VisitReminder.enabled(context)
                    },
                    onTimePicked = { h, m ->
                        // setTime يعيد التسليح داخلياً إن كان المفعّل (setTime → scheduleNext)
                        VisitReminder.setTime(context, h, m)
                        reminderHour = VisitReminder.hour(context)
                        reminderMinute = VisitReminder.minute(context)
                    },
                    onThresholdPicked = { d ->
                        // setThreshold لا يعيد الجدولة — العتبة تُقرأ لحظة الإطلاق (موثّق في الميزة)
                        VisitReminder.setThreshold(context, d)
                        reminderThreshold = VisitReminder.threshold(context)
                    }
                )
            }
            Spacer(Modifier.height(10.dp))

            // ─── بطاقة التخزين التوضيحية (P10) — SAF مدمج بلا إذن وقت التشغيل ───
            AnimatedVisibility(s3g, enter = fadeIn(tween(340)) + slideInVertically(tween(340)) { it / 2 }) {
                PermissionCard(
                    icon = Icons.Rounded.Backup, accent = Amber,
                    title = storageT, desc = storageD,
                    granted = true, builtin = true,
                    grantedLabel = grantedStr, enableLabel = enableStr, builtinLabel = builtinStr,
                    onEnable = { }
                )
            }
            Spacer(Modifier.height(10.dp))

            // ─── بطاقة إمكانية الوصول الكاملة ───
            AnimatedVisibility(s3d, enter = fadeIn(tween(340)) + slideInVertically(tween(340)) { it / 2 }) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconChip(Icons.Rounded.AdminPanelSettings, Color.White, Pink)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(fullT, color = g.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(2.dp))
                            Text(fullD, color = g.textSecondary, fontSize = 11.sp, lineHeight = 15.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        ActionPill(openSettingsStr, Blue) { openAppSettings() }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // ─── بطاقة تأمين التطبيق () ───
            AnimatedVisibility(s3e, enter = fadeIn(tween(340)) + slideInVertically(tween(340)) { it / 2 }) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconChip(Icons.Rounded.Fingerprint, Color.White, VioDeep)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(secT, color = g.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(2.dp))
                                Text(secD, color = g.textSecondary, fontSize = 11.sp, lineHeight = 15.sp)
                            }
                            Spacer(Modifier.width(10.dp))
                            if (bioOn) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.CheckCircle, null, tint = Green, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(secOn, color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                            } else if (bioAvailable) {
                                ActionPill(secEnable, VioDeep) { enableBio() }
                            }
                        }
                        if (!bioOn && !bioAvailable) {
                            Spacer(Modifier.height(6.dp))
                            Text(bioReason, color = g.textSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ─── شريط التقدم ───
            AnimatedVisibility(s4, enter = fadeIn(tween(360))) {
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(progressStr, color = g.textSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text(
                            "$grantedTotal/8",
                            color = g.accent2, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Spacer(Modifier.height(7.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(g.border.copy(alpha = 0.7f))
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(frac)
                                .height(6.dp)
                                .clip(CircleShape)
                                .background(Brush.horizontalGradient(listOf(Vio, Cyan)))
                        )
                    }
                }
            }

            Spacer(Modifier.height(26.dp))

            // ─── زر المتابعة ───
            AnimatedVisibility(s4, enter = fadeIn(tween(380)) + slideInVertically(tween(380)) { it / 2 }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .scale(pulse)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Brush.linearGradient(listOf(VioDeep, Cyan)))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { finish() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(continueStr, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        noteStr,
                        color = g.textSecondary,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

/**
 * بطاقة إذن واحدة — حالة حية (ممنوح/مدمج) أو زر تفعيل يطلق إذن النظام.
 */
@Composable
private fun PermissionCard(
    icon: ImageVector,
    accent: Color,
    title: String,
    desc: String,
    granted: Boolean,
    builtin: Boolean,
    grantedLabel: String,
    enableLabel: String,
    builtinLabel: String,
    onEnable: () -> Unit
) {
    val g = glassColors()
    GlassCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconChip(icon, Color.White, accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = g.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(desc, color = g.textSecondary, fontSize = 11.sp, lineHeight = 15.sp)
            }
            Spacer(Modifier.width(10.dp))
            if (granted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = Green, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (builtin) builtinLabel else grantedLabel,
                        color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            } else {
                ActionPill(enableLabel, accent, onClick = onEnable)
            }
        }
    }
}

/**
 * [P16-b] بطاقة «التذكير اليومي» — الثامنة في شاشة الأذونات. إعدادٌ لا إذن وقت
 * تشغيل: نفس لغة بطاقات الأذونات (IconChip + عنوان/وصف 14/11sp داخل GlassCard)
 * لكن منطقة الفعل مفتاح Switch بدل زر تفعيل — والنقر على جسم البطاقة يفتح
 * منتقي الوقت والعتبة (نمط VisitReminderCard في مركز الإعدادات: TimePicker M3
 * بنظام 24 ساعة داخل AlertDialog + رقائق العتبة).
 *
 * - الحالة تُمرَّر من الأعلى: صاحب الشاشة يقرؤها من prefs الميزة ويحدّثها عند
 *   ON_RESUME، والفعّالان تعيد قراءة الحقيقة من prefs بعد كل كتابة.
 * - استدعاء واحد لا يُعاد استخدامها، لذا النصوص تُحلّ هنا لا في الأعلى
 *   (stringResourceCompat من الحزمة نفسها — HomeScreen.kt).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderPermissionCard(
    accent: Color,
    enabled: Boolean,
    exactGranted: Boolean,
    hour: Int,
    minute: Int,
    threshold: Int,
    onToggle: (Boolean) -> Unit,
    onTimePicked: (Int, Int) -> Unit,
    onThresholdPicked: (Int) -> Unit
) {
    val g = glassColors()
    val remT = stringResourceCompat(R.string.perm_reminder_t)
    val remD = stringResourceCompat(R.string.perm_reminder_d)
    val remOffNote = stringResourceCompat(R.string.perm_reminder_off_note)
    val remExactWarn = stringResourceCompat(R.string.perm_reminder_exact_warn)
    val remPick = stringResourceCompat(R.string.perm_reminder_pick)
    val remTime = stringResourceCompat(R.string.perm_reminder_time)
    val remThreshold = stringResourceCompat(R.string.perm_reminder_threshold)
    val remDays = stringResourceCompat(R.string.perm_reminder_days, threshold)
    val confirmStr = stringResourceCompat(R.string.confirm)
    val cancelStr = stringResourceCompat(R.string.cancel)
    var showPicker by remember { mutableStateOf(false) }

    // [P16-b] الفرع الحي للبطاقة: OFF / ON_OK / ON_EXACT_WARN — منطق نقي مُختبَر
    val uiState = ReminderUiPolicy.deriveReminderState(enabled, exactGranted)
    val timeLabel = ReminderUiPolicy.timeLabel(hour, minute)

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { showPicker = true },
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconChip(Icons.Rounded.AlarmOn, Color.White, accent)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(remT, color = g.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(remD, color = g.textSecondary, fontSize = 11.sp, lineHeight = 15.sp)
                }
                Spacer(Modifier.width(10.dp))
                // المفتاح يستهلك نقرته بنفسه — نقرة الجسم وحدها تفتح المنتقي
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(8.dp))
            when (uiState) {
                ReminderUiState.OFF ->
                    Text(remOffNote, color = g.textSecondary, fontSize = 11.sp)
                ReminderUiState.ON_OK, ReminderUiState.ON_EXACT_WARN -> {
                    // مفعّل: الوقت الحالي (24 ساعة) + العتبة بالأيام — عدّاد التقدم يحسِب هذه البطاقة
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(timeLabel, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        Text("·", color = g.textSecondary, fontSize = 11.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(remDays, color = g.textSecondary, fontSize = 11.sp)
                    }
                }
            }
            // [P16-b] تحذير كهرماني صادق بلا حجب: التذكير مجدول فعلاً (منبّه غير دقيق
            // يعبر Doze) لكن بعض الأجهزة المقيدة لا توصله في وقته إلا بإذن التنبيهات
            // الدقيقة — البطاقة 7 أعلاه هي طريق منح ذلك الإذن.
            if (uiState == ReminderUiState.ON_EXACT_WARN) {
                Spacer(Modifier.height(4.dp))
                Text(remExactWarn, color = g.amber, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
    }

    // ─── منتقي الوقت والعتبة — نمط VisitReminderCard: TimePicker M3 داخل AlertDialog ───
    if (showPicker) {
        val tpState = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(remPick, color = g.textPrimary) },
            text = {
                Column {
                    Text(remTime, color = g.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    TimePicker(state = tpState)
                    Spacer(Modifier.height(8.dp))
                    Text(remThreshold, color = g.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // [P16-b] رقائق العتبة — كل نقرة تُخزَّن فوراً في prefs الميزة
                        // [P20-FIX agent6]: نفس قائمة بطاقة الإعدادات (1/3/7 كانت هنا و7/14/30 هناك)
                        listOf(1, 3, 7, 14, 30).forEach { d ->
                            ReminderDayChip(
                                label = stringResourceCompat(R.string.perm_reminder_days, d),
                                active = threshold == d,
                                accent = accent,
                                textSecondary = g.textSecondary
                            ) { onThresholdPicked(d) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // setTime يخزّن ويعيد التسليح داخلياً إن كان التذكير مفعّلاً
                    onTimePicked(tpState.hour, tpState.minute)
                    showPicker = false
                }) { Text(confirmStr, color = g.accent) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text(cancelStr, color = g.textSecondary)
                }
            }
        )
    }
}

/** [P16-b] رقاقة يوم العتبة — نمط VrChip في بطاقة الإعدادات (بلا APIs تجريبية) */
@Composable
private fun ReminderDayChip(
    label: String,
    active: Boolean,
    accent: Color,
    textSecondary: Color,
    onClick: () -> Unit
) {
    Text(
        label,
        color = if (active) Color.White else textSecondary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) accent else textSecondary.copy(alpha = 0.12f))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}
