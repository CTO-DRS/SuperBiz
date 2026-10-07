package com.superbiz.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.superbiz.app.ui.theme.glassColors
import kotlin.math.max

/**
 * رسم أعمدة مزدوجة (مبيعات/مصروفات) بأسلوب بطاقة "حركة آخر 7 أيام".
 *
 * [P7-L9 إصلاح] قرار الاتجاه الموثّق:
 * محور الزمن يُرسم دائماً بترتيب زمني متسق LTR (أقدم يوم يساراً) في العربية والإنجليزية
 * معاً — المدخل يصل مرتّباً تصاعدياً من المنتجين (salesExpensesSeries تعيد sortedBy key
 * تصاعدياً)، ورسم Canvas لا يُعكَس تلقائياً مع اتجاه التركيب، فالثبات هنا مقصود وليس
 * صدفة: عكس الأزواج في RTL كان سيعطي زمناً يتقدم من اليمين لليسار بخلاف بقية الرسوم.
 *
 * الوحيد القابل للانعكاس كان صفّ المفتاح (ChartLegend): Row يرتّب أبناءه حسب اتجاه
 * التركيب فينقلب في RTL فتصير تسمية «أ» في الجهة المقابلة لعمود «أ» (الأعمدة ثابتة).
 * صُبّ الصف صراحةً على LTR عبر CompositionLocalProvider(LocalLayoutDirection) لتبقى
 * كل تسمية محاذاة لجهة عمودها في اللغتين.
 */
@Composable
fun DualBarChart(
    data: List<Triple<Long, Double, Double>>, // (يوم، قيمة أ، قيمة ب)
    labelA: String,
    labelB: String,
    colorA: Color,
    colorB: Color,
    modifier: Modifier = Modifier
) {
    val g = glassColors()
    Column(modifier) {
        // [P7-L9 إصلاح]: تثبيت اتجاه المفتاح على LTR ليطابق ترتيب الأعمدة الثابت (أ يسار الزوج)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                ChartLegend(colorA, labelA)
                Spacer(Modifier.width(14.dp))
                ChartLegend(colorB, labelB)
            }
        }
        val maxV = data.fold(1e-9) { acc, (_, a, b) -> max(acc, max(a, b)) }
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            // [P7-L9 إصلاح]: x محسوب من الفهرس مباشرة بلا أي اعتبار لاتجاه التركيب —
            // الفهرس 0 (أقدم يوم) يساراً دائماً كما هو موثق في KDoc
            val n = data.size.coerceAtLeast(1)
            val slot = size.width / n
            val barW = slot * 0.24f
            data.forEachIndexed { i, (_, a, b) ->
                val cx = slot * i + slot / 2f
                val hA = (a / maxV * (size.height - 8f)).toFloat()
                val hB = (b / maxV * (size.height - 8f)).toFloat()
                drawRoundRect(
                    colorA, topLeft = Offset(cx - barW - 2.dp.toPx(), size.height - hA),
                    size = Size(barW, hA), cornerRadius = CornerRadius(barW / 2)
                )
                drawRoundRect(
                    colorB, topLeft = Offset(cx + 2.dp.toPx(), size.height - hB),
                    size = Size(barW, hB), cornerRadius = CornerRadius(barW / 2)
                )
            }
        }
    }
}

@Composable
fun ChartLegend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(8.dp).clip(RoundedCornerShape(3.dp)).background(color)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            label, style = MaterialTheme.typography.labelSmall,
            color = glassColors().textSecondary
        )
    }
}

/** خط اتجاه (تدفق نقدي / توقع) — : يُعكس في RTL حسب الإعداد (مظهر ← عكس الرسوم) */
@Composable
fun LineChart(
    values: List<Double>,
    color: Color,
    modifier: Modifier = Modifier,
    fill: Boolean = true
) {
    // الافتراضي عكس في العربي — الإعداد mirrorChartsRtl يتحكم به فعلياً
    val rtl = com.superbiz.app.core.AppPrefs.mirrorChartsRtl &&
        androidx.compose.ui.platform.LocalLayoutDirection.current ==
            androidx.compose.ui.unit.LayoutDirection.Rtl
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val maxV = values.max().coerceAtLeast(1e-9)
        val minV = values.min().coerceAtMost(0.0)
        val range = (maxV - minV).coerceAtLeast(1e-9)
        fun pt(i: Int): Offset {
            var x = size.width * i / (values.size - 1f)
            if (rtl) x = size.width - x
            val y = size.height - (((values[i] - minV) / range) * (size.height * 0.9f)).toFloat() - size.height * 0.05f
            return Offset(x, y)
        }
        val path = Path()
        path.moveTo(pt(0).x, pt(0).y)
        for (i in 1 until values.size) path.lineTo(pt(i).x, pt(i).y)
        if (fill) {
            val fillPath = Path().apply {
                addPath(path)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(fillPath, color.copy(alpha = 0.18f))
        }
        drawPath(path, color, style = Stroke(width = 3.dp.toPx()))
    }
}

/** دائرة مجزأة (أعمار الديون) */
@Composable
fun DonutChart(
    segments: List<Pair<Double, Color>>,
    modifier: Modifier = Modifier,
    stroke: Float = 40f
) {
    val total = segments.sumOf { it.first }
    // [تدقيق L-6] السماكة كانت بكسلات خام — 40px كثيفة على شاشات عالية الكثافة
    // ونحيلة على المنخفضة (تسمن الخطوط بين الأجهزة). الآن 40 = 40dp تُحوّل
    // لكثافة الجهاز مرة واحدة خارج الـCanvas — سماكة بصرية متطابقة كلّي الكثافات
    val strokePx = with(androidx.compose.ui.platform.LocalDensity.current) { stroke.dp.toPx() }
    // كل الأجزاء صفرية؟ تُرسم حلقة رمادية باهتة بدل فراغ مريب
    Canvas(modifier) {
        val diameter = minOf(size.width, size.height) - strokePx
        val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
        if (total <= 1e-9) {
            drawArc(
                color = Color.Gray.copy(alpha = 0.25f), startAngle = 0f, sweepAngle = 360f,
                useCenter = false, topLeft = topLeft, size = Size(diameter, diameter),
                style = Stroke(strokePx)
            )
            return@Canvas
        }
        var start = -90f
        segments.forEach { (v, color) ->
            val sweep = (v / total * 360).toFloat()
            drawArc(
                color = color, startAngle = start, sweepAngle = sweep, useCenter = false,
                topLeft = topLeft, size = Size(diameter, diameter),
                style = Stroke(strokePx)
            )
            start += sweep
        }
    }
}

/** صفوف متدرجة أفقية (أفضل العملاء/المنتجات) */
@Composable
fun RankBar(
    index: Int,
    title: String,
    value: String,
    fraction: Float,
    color: Color
) {
    val g = glassColors()
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "#${index + 1}", color = g.textSecondary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(30.dp)
        )
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    title, style = MaterialTheme.typography.bodyMedium,
                    color = g.textPrimary,
                    modifier = Modifier.weight(1f), maxLines = 1
                )
                Text(value, style = MaterialTheme.typography.labelMedium, color = g.textSecondary)
            }
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier.fillMaxWidth().height(7.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(g.textSecondary.copy(alpha = 0.10f))
            ) {
                Box(
                    Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(7.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(color)
                )
            }
        }
    }
}
