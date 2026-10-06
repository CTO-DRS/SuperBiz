package com.superbiz.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import com.superbiz.app.R
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.theme.Cyan
import com.superbiz.app.ui.theme.VioDeep
import com.superbiz.app.ui.theme.glassColors

/** بلاطة إحصاء للملف الشخصي */
@Composable
internal fun StatTile(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, label: String, accent: Color, modifier: Modifier = Modifier) {
    val g = glassColors()
    GlassCard(modifier = modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                value, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold,
                color = g.textPrimary, maxLines = 1
            )
            Text(
                label, fontSize = 11.sp, color = g.textSecondary, maxLines = 1
            )
        }
    }
}

/** حلقة اكتمال الملف الشخصي — قوس متحرك 0..100 فوقه النص */
@Composable
internal fun CompletionRing(percent: Int, sizeDp: Float, modifier: Modifier = Modifier) {
    val g = glassColors()
    val sweep by animateFloatAsState(
        targetValue = percent / 100f,
        animationSpec = tween(900),
        label = "completion"
    )
    Box(
        modifier.size((sizeDp * 1.6f).dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 9.dp.toPx()
            val inset = stroke / 2 + 2.dp.toPx()
            drawArc(
                color = g.border,
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawArc(
                brush = Brush.linearGradient(listOf(VioDeep, Cyan)),
                startAngle = -90f,
                sweepAngle = 360f * sweep,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "$percent%",
                fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = g.textPrimary
            )
            Text(
                stringResourceCompat(R.string.profile_completion),
                fontSize = 7.sp, color = g.textSecondary, maxLines = 1
            )
        }
    }
}

/** صف إعداد بمفتاح تبديل */
@Composable
internal fun ToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconBg: Color,
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChecked: (Boolean) -> Unit
) {
    val g = glassColors()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(iconBg.copy(alpha = if (enabled) 0.9f else 0.4f)),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = g.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            if (subtitle != null) {
                Text(subtitle, color = g.textSecondary, fontSize = 11.sp)
            }
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Cyan,
                uncheckedThumbColor = g.textSecondary,
                uncheckedTrackColor = g.border,
                disabledCheckedTrackColor = g.border,
                disabledUncheckedTrackColor = g.border
            )
        )
    }
}
