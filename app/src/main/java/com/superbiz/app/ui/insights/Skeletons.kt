package com.superbiz.app.ui.insights

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.superbiz.app.ui.theme.glassColors

/**
 * [P36-M4-10] — سكيلتون زجاجي مشترك لحالات التحميل في بطاقات الرؤى.
 *
 * العقد:
 * - بلا نصوص صلبة ولا «…»/«—» — أشكال زجاجية بلغة التصميم نفسها (glassColors()).
 * - نبض ألفا خفيف (0.10↔0.22) بدورة 900ms عكسيّة — شدة معتدلة تراعي الحساسية
 *   للحركة ولا تشتت، بدل اللمعان (shimmer) الصارخ.
 * - الشكل يطابق SmartCardShell (زاوية 18dp وحشو 14dp) فيستبدل المحتوى الحقيقي
 *   بلا قفزة بصرية عند اكتمال الحساب.
 * - هذا ليس حالة فراغ: بعد الجاهزية (VM.ready) تُخفى البطاقات بلا بيانات كعقدها الصادق.
 */

/** نبض ألفا مشترك للسكيلتون — انتقال لانهائي واحد لكل مكوّن يستدعيه. */
@Composable
private fun skeletonAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "p36Skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.22f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "p36SkeletonAlpha",
    )
    return alpha
}

/** سطر سكيلتون مفرد — صندوق زجاجي مستدير بنبض خفيف (بديل «…»/«—» في مواضع القيم). */
@Composable
fun SkeletonLine(width: Dp, height: Dp = 11.dp, modifier: Modifier = Modifier) {
    val g = glassColors()
    val a = skeletonAlpha()
    Box(
        modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .background(g.textSecondary.copy(alpha = a))
    )
}

/** صندوق سكيلتون مفرد (كتلة أكبر — للرسوم/المساحات). */
@Composable
fun SkeletonBlock(width: Dp, height: Dp = 64.dp, corner: Dp = 12.dp, modifier: Modifier = Modifier) {
    val g = glassColors()
    val a = skeletonAlpha()
    Box(
        modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(corner))
            .background(g.textSecondary.copy(alpha = a * 0.7f))
    )
}

/**
 * بطاقة رؤى بوضع التحميل — تُرسم مكان الموجة كاملة بينما يُحسب أول إصدار في الـVM،
 * ثم تستبدلها البطاقات الحقيقية (أو لا شيء إن كانت بلا بيانات — عقد الصدق).
 */
@Composable
fun InsightCardSkeleton(lines: Int = 2, modifier: Modifier = Modifier) {
    val g = glassColors()
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(g.surface)
            .border(1.dp, g.border, RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            SkeletonLine(96.dp, height = 13.dp)
            repeat(lines.coerceAtLeast(1)) {
                SkeletonLine(180.dp)
            }
        }
    }
}
