package com.superbiz.app.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.superbiz.app.R
import com.superbiz.app.security.BiometricGate
import com.superbiz.app.ui.components.BizField
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.theme.Cyan
import com.superbiz.app.ui.theme.RedDeep
import com.superbiz.app.ui.theme.VioDeep
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.vm.SettingsVM
import kotlinx.coroutines.delay

/**
 * شاشة القفل — رمز PIN + بصمة الإصبع/الوجه (BiometricPrompt النظامية).
 * جديد: حدّ محاولات تصاعدي (قفل مؤقت مع عدّاد معروض) + إلغاء الفتح الطارئ الصامت
 * (البيومتريا المعطلة بلا رمز تطلب نقراً صريحاً مع تحذير واضح).
*/
@Composable
fun LockScreen(
    activity: FragmentActivity?,
    settingsVM: SettingsVM,
    onUnlocked: () -> Unit
) {
    val g = glassColors()
    val settings by settingsVM.settings.collectAsState()
    val lockout by settingsVM.lockout.collectAsState()
    var pin by remember { mutableStateOf("") }
    var err by remember { mutableStateOf(false) }
    var bioMsg by remember { mutableStateOf<String?>(null) }
    // وظيفة 37 — زر عين يبدّل إظهار حروف الرمز بدل النقاط (عرض فقط — لا يمس التحقق)
    var showPin by remember { mutableStateOf(false) }
    // لا تحقق ثانٍ متزامن — كانت كل ضغطة مفتاح تُشعل تحققاً جديداً يتداخل مع سابقه
    var verifying by remember { mutableStateOf(false) }
    // الطول المخزّن عند التعيين (6..8) — الإرسال عند بلوغه بالضبط فقط
    // والمفقودات القديمة (0) تفترض حدّ السياسة الأدنى 6
    // [P5-H15 إصلاح]: مستخدمو الترحيل برمز 7–8 أرقام بلا pinLength مخزّن كانوا
    // محبوسين — الافتراض 6 يرسل الحقل تلقائياً عند 6 محارف فيفشل الرمز الصحيح
    // أبداً ويزداد القفل. الآن: الطول المعروف يُرسل عند بلوغه بالضبط كما كان،
    // والغير معروف يُرسل عند الحد الأقصى 8 مع زر «تم» بلوحة المفاتيح للتحقق
    // اليدوي عند أي طول (6/7/8) — لا حبس ذاتي ولا محاولات خاطئة تلقائية
    val pinLengthKnown = settings.pinLength in 6..8
    val pinTargetLen = if (pinLengthKnown) settings.pinLength else 8

    fun submitPin(v: String) {
        if (verifying || v.isEmpty()) return
        verifying = true
        settingsVM.verifyPin(v) { ok ->
            verifying = false
            if (ok) onUnlocked() else { err = true; pin = "" }
        }
    }

    val hasPin = settings.pinHash != null || settings.pinBlob != null
    val biometricOn = settings.biometric && activity != null
    val bioReady = biometricOn && BiometricGate.check(activity) == BiometricGate.OK

    // نبض الثواني لتحديث العدّاد المعروض أثناء القفل
    // [تدقيق M-9] العرض والقرار على الزمن الأحادي (elapsedRealtime) — تراجع الحائط
    // لا يقصّر العدّاد ولا يفتح القفل (القرار الصارم في verifyPin بنفس المصدر)
    var nowElapsedMs by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(lockout.lockUntilElapsed) {
        settingsVM.refreshLockout()
        while (lockout.remainingElapsedSeconds(nowElapsedMs) > 0) {
            delay(500)
            nowElapsedMs = android.os.SystemClock.elapsedRealtime()
        }
    }
    val lockLeft = lockout.remainingElapsedSeconds(nowElapsedMs)
    val locked = lockLeft > 0

    // تُحلّ في السياق القابل للتركيب ثم تُستخدم داخل الدوال العادية
    val bioTitle = stringResourceCompat(R.string.biometric_title)
    val bioSubtitle = stringResourceCompat(R.string.biometric_subtitle)
    val bioNegative = stringResourceCompat(R.string.cancel)
    // [P20-FIX agent19] نص حاجز القفل للبصمة — كان عربياً صلباً في الوضع الإنجليزي
    val bioLockedMsg = stringResourceCompat(R.string.lock_countdown, lockLeft)

    fun showBio() {
        // H-19: البصمة تخضع لنفس حاجز القفل المؤقت — كانت تفتح فوراً أثناء عدّاد
        // القفل فتجعل حدّ المحاولات بلا معنى لمن يملك بصمة صاحب الجهاز
        // [P20-FIX agent19]: نص عربي صلب — استخدم lock_countdown الموجود باللغتين (نفس نمط 126–128)
        if (locked) {
            bioMsg = bioLockedMsg
            return
        }
        val a = activity ?: return
        BiometricGate.authenticate(
            activity = a,
            title = bioTitle,
            subtitle = bioSubtitle,
            negativeText = bioNegative,
            // H-19: نجاح البصمة يصفّر عدّاد المحاولات الفاشلة كمسار الرمز تماماً
            onSuccess = {
                settingsVM.onBiometricUnlocked()
                onUnlocked()
            },
            onError = { msg -> if (msg.isNotBlank()) bioMsg = msg }
        )
    }

    // طلب البصمة تلقائياً عند ظهور الشاشة — : لا تلقائي أثناء القفل المؤقت
    LaunchedEffect(bioReady, locked) {
        if (bioReady && !locked) { delay(500); showBio() }
    }

    // لا فتح صامت أبداً — حالة «بيومتريا معطلة بلا رمز» تعرض تحذيراً وزر متابعة صريحاً

    val infinite = rememberInfiniteTransition(label = "lock")
    val pulse by infinite.animateFloat(
        initialValue = 1f, targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "lockPulse"
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(g.bgGradient))
    ) {
        Column(
            Modifier
                .align(Alignment.Center)
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(84.dp)
                    .scale(pulse)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(VioDeep, Cyan))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Lock, null, tint = Color.White, modifier = Modifier.size(38.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text("SuperBiz", style = MaterialTheme.typography.headlineMedium, color = g.textPrimary)
            Spacer(Modifier.height(24.dp))

            // ─── قفل المحاولات: عدّاد معروض وحجب كامل للإدخال ───
            if (locked) {
                GlassCard(corner = 22.dp) {
                    Column(
                        Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            stringResourceCompat(R.string.lock_blocked),
                            color = RedDeep, fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResourceCompat(R.string.lock_countdown, lockLeft),
                            color = g.textSecondary, fontSize = 13.sp
                        )
                    }
                }
                return@Column
            }

            // ─── تحذير الفتح الطارئ: بيومتريا معطلة ولا رمز — نقر صريح مطلوب ───
            if (biometricOn && !bioReady && !hasPin) {
                GlassCard(corner = 22.dp) {
                    Column(Modifier.padding(18.dp)) {
                        Text(
                            stringResourceCompat(R.string.emergency_title),
                            color = RedDeep, fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResourceCompat(R.string.emergency_hint),
                            color = g.textSecondary, fontSize = 12.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        androidx.compose.material3.TextButton(onClick = onUnlocked) {
                            Text(
                                stringResourceCompat(R.string.emergency_continue),
                                color = RedDeep, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                return@Column
            }

            // ─── الرمز (إن وجد) ───
            if (hasPin) {
                GlassCard(corner = 22.dp) {
                    Column(
                        Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(stringResourceCompat(R.string.pin_enter), color = g.textSecondary)
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = pin,
                            onValueChange = { v ->
                                if (v.length <= 8 && v.all { it.isDigit() }) {
                                    pin = v
                                    err = false
                                    // الإرسال عند الطول المستهدف فقط وبلا تداخل — كان الإرسال
                                    // عند ≥4 يفشل لرمز ناقص فيُحتسب فشلاً ويُمسح الحقل فيستحيل
                                    // إدخال رمز من 6 أرقام كاملاً
                                    if (!verifying && v.length == pinTargetLen) submitPin(v)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            // وظيفة 37 — الإظهار/الإخفاء بديل عرض فقط ولا يغيّر منطق التحقق
                            visualTransformation = if (showPin) VisualTransformation.None
                            else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submitPin(pin) }),
                            trailingIcon = {
                                IconButton(onClick = { showPin = !showPin }) {
                                    Icon(
                                        if (showPin) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                        contentDescription = stringResourceCompat(
                                            if (showPin) R.string.pin_hide else R.string.pin_show
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
                                cursorColor = g.accent
                            )
                        )
                        if (err) {
                            Spacer(Modifier.height(8.dp))
                            Text(stringResourceCompat(R.string.pin_wrong), color = RedDeep, fontSize = 13.sp)
                        }
                    }
                }
                if (bioReady) Spacer(Modifier.height(18.dp))
            }

            // ─── زر البيومتريا: بصمة الإصبع أو الوجه ───
            if (bioReady) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(74.dp)
                            .scale(pulse)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(VioDeep, Cyan)))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { showBio() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Fingerprint, null,
                            tint = Color.White, modifier = Modifier.size(44.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResourceCompat(R.string.biometric_unlock),
                        color = g.textSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }
            }

            bioMsg?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = g.textSecondary, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        }
    }
}
