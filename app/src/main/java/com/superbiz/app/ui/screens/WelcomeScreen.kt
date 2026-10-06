package com.superbiz.app.ui.screens

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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.PointOfSale
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superbiz.app.R
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.components.IconChip
import com.superbiz.app.ui.theme.Cyan
import com.superbiz.app.ui.theme.Green
import com.superbiz.app.ui.theme.Pink
import com.superbiz.app.ui.theme.Vio
import com.superbiz.app.ui.theme.VioDeep
import com.superbiz.app.ui.theme.glassColors
import kotlinx.coroutines.delay

/**
 * شاشة البداية الترحيبية — متحركة وتفاعلية بالكامل.
 * - أول تشغيل: كشف تدريجي للعناصر + زر «ابدأ الآن» وتخطّي.
 * - التشغيلات التالية (quick=true): تقدم تلقائي بعد 2.6 ثانية أو لمسة واحدة.
*/
@Composable
fun WelcomeScreen(
    quick: Boolean,
    onDone: () -> Unit
) {
    val g = glassColors()

    // منع النداء المزدوج
    var finished by remember { mutableStateOf(false) }
    fun finish() { if (!finished) { finished = true; onDone() } }

    // مراحل الظهور المتدرج
    var s1 by remember { mutableStateOf(false) }  // الشعار
    var s2 by remember { mutableStateOf(false) }  // الاسم
    var s3 by remember { mutableStateOf(false) }  // الشعار النصي
    var s4 by remember { mutableStateOf(false) }  // المزايا
    var s5 by remember { mutableStateOf(false) }  // الزر

    LaunchedEffect(Unit) {
        delay(80);  s1 = true
        delay(320); s2 = true
        delay(520); s3 = true
        delay(700); s4 = true
        delay(950); s5 = true
    }

    // التقدم التلقائي في الوضع السريع
    if (quick) {
        LaunchedEffect(Unit) {
            delay(2600)
            finish()
        }
    }

    // نبض الشعار + كتل الضوء العائمة + شريط اللمعان
    val infinite = rememberInfiniteTransition(label = "welcome")
    val pulse by infinite.animateFloat(
        initialValue = 1f, targetValue = 1.045f,
        animationSpec = infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "pulse"
    )
    val glow by infinite.animateFloat(
        initialValue = 0.35f, targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "glow"
    )
    val orb1 by infinite.animateFloat(
        initialValue = -28f, targetValue = 30f,
        animationSpec = infiniteRepeatable(tween(3800), RepeatMode.Reverse), label = "orb1"
    )
    val orb2 by infinite.animateFloat(
        initialValue = 24f, targetValue = -26f,
        animationSpec = infiniteRepeatable(tween(3200), RepeatMode.Reverse), label = "orb2"
    )
    val sweep by infinite.animateFloat(
        initialValue = -260f, targetValue = 260f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "sweep"
    )
    val dot by infinite.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "dot"
    )

    // دخول الشعار بارتداد نابضي
    val logoScale by animateFloatAsState(
        targetValue = if (s1) 1f else 0.3f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "logoScale"
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(g.bgGradient))
            .pointerInput(Unit) { if (quick) detectTapGestures { finish() } }
    ) {
        // كتل ضوء عائمة (Aurora حية)
        Box(
            Modifier
                .align(Alignment.TopStart)
                .graphicsLayer { translationX = orb1; translationY = orb2 }
                .padding(top = 60.dp, start = 24.dp)
                .size(230.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Vio.copy(alpha = if (g.isLight) 0.18f else 0.30f), Color.Transparent)))
        )
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .graphicsLayer { translationY = orb1 * 1.4f }
                .padding(bottom = 90.dp, end = 18.dp)
                .size(260.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Cyan.copy(alpha = if (g.isLight) 0.15f else 0.24f), Color.Transparent)))
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .graphicsLayer { translationY = orb2 * 2f }
                .padding(start = 130.dp)
                .size(170.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Pink.copy(alpha = if (g.isLight) 0.10f else 0.16f), Color.Transparent)))
        )

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(1.15f))

            // ─── الشعار مع لمعان مستعرض ───
            Box(
                Modifier
                    .size(104.dp)
                    .scale(logoScale * pulse)
                    .clip(RoundedCornerShape(30.dp))
                    .background(Brush.linearGradient(listOf(VioDeep, Cyan))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Storefront, null, tint = Color.White, modifier = Modifier.size(52.dp))
                // شريط لمعان يمر فوق الشعار
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(30.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color.Transparent, Color.White.copy(alpha = 0.28f), Color.Transparent),
                                start = Offset(sweep - 120f, 0f),
                                end = Offset(sweep + 120f, 0f)
                            )
                        )
                )
                // هالة خلف الشعار
                Box(
                    Modifier
                        .size(104.dp)
                        .clip(RoundedCornerShape(30.dp))
                        .background(Brush.linearGradient(listOf(Color.White.copy(alpha = glow * 0.12f), Color.Transparent)))
                )
            }

            Spacer(Modifier.height(22.dp))

            // ─── الاسم ───
            AnimatedVisibility(s2, enter = fadeIn(tween(420)) + slideInVertically(tween(420)) { it / 3 }) {
                Text(
                    "SuperBiz",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = g.textPrimary,
                    letterSpacing = 1.5.sp
                )
            }

            Spacer(Modifier.height(8.dp))

            // ─── الشعار النصي ───
            AnimatedVisibility(s3, enter = fadeIn(tween(420))) {
                Text(
                    stringResourceCompat(R.string.welcome_tagline),
                    style = MaterialTheme.typography.bodyLarge,
                    color = g.textSecondary,
                    modifier = Modifier.padding(horizontal = 10.dp)
                )
            }

            Spacer(Modifier.height(30.dp))

            // ─── المزايا الثلاث — ظهور متدرج ───
            AnimatedVisibility(s4, enter = fadeIn(tween(360)) + slideInVertically(tween(360)) { it / 2 }) {
                GlassCard(corner = 22.dp) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        WelcomeFeature(Icons.Rounded.PointOfSale, stringResourceCompat(R.string.welcome_f1), Vio)
                        WelcomeFeature(Icons.Rounded.ReceiptLong, stringResourceCompat(R.string.welcome_f2), Cyan)
                        WelcomeFeature(Icons.Rounded.Insights, stringResourceCompat(R.string.welcome_f3), Green)
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // ─── زر البدء ───
            AnimatedVisibility(s5, enter = fadeIn(tween(380)) + slideInVertically(tween(380)) { it / 2 }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!quick) {
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
                            Text(
                                stringResourceCompat(R.string.welcome_cta),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResourceCompat(R.string.welcome_skip),
                            color = g.textSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { finish() }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    } else {
                        // ثلاث نقاط نبضية أثناء التحميل
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeat(3) { i ->
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .alpha(if (i == 0) dot else if (i == 1) 1f - (dot * 0.4f) else dot * 0.5f)
                                        .clip(CircleShape)
                                        .background(g.accent)
                                )
                            }
                        }
                        Spacer(Modifier.height(26.dp))
                    }
                }
            }
            Spacer(Modifier.height(34.dp))
        }
    }
}

@Composable
private fun WelcomeFeature(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, accent: Color) {
    val g = glassColors()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconChip(icon, Color.White, accent)
        Spacer(Modifier.width(12.dp))
        Text(label, color = g.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
