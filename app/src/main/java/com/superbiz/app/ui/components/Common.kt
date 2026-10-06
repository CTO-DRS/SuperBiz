package com.superbiz.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superbiz.app.ui.theme.glassColors
import java.text.NumberFormat
import java.util.Locale

/** بطاقة زجاجية موحدة */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    corner: Dp = 24.dp,
    content: @Composable () -> Unit
) {
    val g = glassColors()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(g.surface)
            .border(1.dp, g.border, RoundedCornerShape(corner))
    ) { content() }
}

/** شارة أيقونة ملونة داخل البطاقات (كما في الصورة المرجعية) */
@Composable
fun IconChip(
    icon: ImageVector,
    tint: Color,
    bg: Color,
    size: Dp = 42.dp,
    iconSize: Dp = 20.dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 2.6f))
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** بطاقة KPI موحدة بلغة التصميم المرجعية — : قابلة للنقر اختيارياً */
@Composable
fun KpiCard(
    icon: ImageVector,
    value: String,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    chipBg: Color? = null,
    onClick: (() -> Unit)? = null
) {
    val g = glassColors()
    val cardModifier = if (onClick != null)
        modifier.clickable { onClick() } else modifier
    GlassCard(modifier = cardModifier) {
        Row(Modifier.padding(14.dp)) {
            IconChip(icon, Color.White, accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    value, style = MaterialTheme.typography.titleLarge,
                    color = g.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    label, style = MaterialTheme.typography.bodySmall,
                    color = g.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * [P24-HERO] بطاقة الهيرو المتدرجة — النسخة القابلة لإعادة الاستخدام من بطاقة الرصيد
 * في الشاشة الرئيسية (فرش heroBrush + دوائر زخرفية + مبلغ بارز + وصف).
 * توحّد الطرح الاحترافي العلوّي في الفواتير/الشيكات/الأقساط/المخزون كما طلب المستخدم،
 * وهي تُمرَّر مع المحتوى (item داخل LazyColumn) فلا تُكرر مشكلة «تشوه المنظر» السابقة.
 */
@Composable
fun HeroCard(
    icon: ImageVector,
    amount: String,
    hint: String,
    modifier: Modifier = Modifier
) {
    val g = glassColors()
    GlassCard(corner = 28.dp, modifier = modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(g.heroBrush)
                .padding(20.dp)
        ) {
            // دوائر زخرفية — نفس لغة الرئيسية
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .size(90.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.10f))
            )
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f))
            )
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconChip(icon, Color.White, Color.White.copy(alpha = 0.22f))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    amount,
                    fontSize = 30.sp, fontWeight = FontWeight.ExtraBold,
                    color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    hint,
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** [P24-HERO] خلية واحدة في شبكة KPI الاحترافية */
data class KpiCell(
    val icon: ImageVector,
    val value: String,
    val label: String,
    val accent: Color,
    val onClick: (() -> Unit)? = null
)

/** [P24-HERO] شبكة KPI (صفّان × عمودان) — نفس لغة شبكة الرئيسية حرفياً */
@Composable
fun KpiGrid(cells: List<KpiCell>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        cells.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { c ->
                    KpiCard(
                        c.icon, c.value, c.label, c.accent,
                        modifier = Modifier.weight(1f), onClick = c.onClick
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** زر إجراء سريع دائري متدرج */
@Composable
fun QuickAction(
    icon: ImageVector,
    label: String,
    brush: Brush,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(brush)
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground)
    }
}

/** عنوان قسم مع سهم اختياري */
@Composable
fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title, style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

/** شارة حالة صغيرة */
@Composable
fun Badge(text: String, color: Color, bg: Color? = null) {
    val g = glassColors()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg ?: color.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * [P36-M4-1] أنماط الذرة الموحدة BizPill — كانت قبلها سبع ذرات مكررة
 * (FilterPill/ActionPill في DebtsScreen، StChip/StAction في StatementWidgets،
 * Chip في SmartCards، LockableActionPill في FavoritesScreen، AlertPill في HomeScreen)
 * برسم متقارب واختلافات هامشية؛ الآن رسم واحد بمعاملات صريحة.
 */
enum class PillMode { SELECT, ACTION, ALERT, BADGE }

/**
 * [P36-M4-1] الذرة الموحدة للرقائق/الكبسولات — التنفيذ الوحيد لسبع الذرات السابقة:
 * - [PillMode.SELECT]  رقاقة اختيار (بنمط FilterPill): خلفية حدّية عند التحديد وحدود ملونة،
 *   غير المحدد سطح زجاجي وحدود محايدة — النقر معطّل إن كان [enabled]=false.
 * - [PillMode.ACTION]  رقاقة إجراء (بنمط ActionPill/LockableActionPill): خلفية لونية 16%،
 *   المعطّل 7% بشفافية نص 45% وبلا clickable — يمنع النقر المزدوج أثناء العمليات.
 * - [PillMode.ALERT]   كبسولة تنبيه (بنمط AlertPill): خلفية 14% وحدود 40% وأيقونة اختيارية.
 * - [PillMode.BADGE]   شارة دلالية غير قابلة للنقر (بنمط Chip): كبسولة كاملة الاستدارة.
 * كل الأنماط تحترم [enabled] (إلا BADGE فهي عرضية بطبيعتها) وتُبقي مقاسات الخط
 * والحشوات الأصلية حرفياً حتى لا تتغير كثافة الشاشات القائمة.
 */
@Composable
fun BizPill(
    label: String,
    color: Color,
    mode: PillMode = PillMode.ACTION,
    selected: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    badgeAlpha: Float = 0.14f,
    onClick: (() -> Unit)? = null
) {
    val g = glassColors()
    val clickable = enabled && onClick != null && mode != PillMode.BADGE
    val shape = if (mode == PillMode.BADGE) RoundedCornerShape(999.dp) else RoundedCornerShape(12.dp)
    val bg = when (mode) {
        PillMode.SELECT -> if (selected) g.accent.copy(alpha = 0.22f) else g.surface
        PillMode.ACTION -> color.copy(alpha = if (enabled) 0.16f else 0.07f)
        PillMode.ALERT -> color.copy(alpha = 0.14f)
        PillMode.BADGE -> color.copy(alpha = badgeAlpha)
    }
    val border = when {
        mode == PillMode.SELECT -> if (selected) g.accent else g.border
        mode == PillMode.ALERT -> color.copy(alpha = 0.4f)
        else -> null
    }
    val (padH, padV) = when (mode) {
        PillMode.SELECT -> 12.dp to 7.dp
        PillMode.ACTION -> 10.dp to 8.dp
        PillMode.ALERT -> 10.dp to 6.dp
        PillMode.BADGE -> 9.dp to 3.dp
    }
    val fontSize = if (mode == PillMode.BADGE) 10.5.sp else 12.sp
    val fontWeight = when (mode) {
        PillMode.SELECT, PillMode.ALERT -> FontWeight.SemiBold
        PillMode.ACTION, PillMode.BADGE -> FontWeight.Bold
    }
    val textColor = when (mode) {
        PillMode.SELECT -> if (selected) g.textPrimary else g.textSecondary
        PillMode.ACTION -> if (enabled) color else color.copy(alpha = 0.45f)
        PillMode.ALERT, PillMode.BADGE -> color
    }
    val base = Modifier
        .clip(shape)
        .background(bg)
    val bordered = if (border != null) base.border(1.dp, border, shape) else base
    val withClick = if (clickable) bordered.clickable { onClick?.invoke() } else bordered
    Box(
        withClick
            .padding(horizontal = padH, vertical = padV)
    ) {
        if (icon != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = textColor, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text(label, color = textColor, fontSize = fontSize, fontWeight = fontWeight, maxLines = 1)
            }
        } else {
            Text(label, color = textColor, fontSize = fontSize, fontWeight = fontWeight, maxLines = 1)
        }
    }
}

/** حقل نصي بأسلوب موحد */
@Composable
fun BizField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboard: androidx.compose.foundation.text.KeyboardOptions =
        androidx.compose.foundation.text.KeyboardOptions.Default,
    leading: (@Composable () -> Unit)? = null
) {
    val g = glassColors()
    OutlinedTextField(
        value = value, onValueChange = onValue,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = keyboard,
        leadingIcon = leading,
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

fun numberFieldOptions() = androidx.compose.foundation.text.KeyboardOptions(
    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
)

/**
 * : تحليل الأرقام يقبل الأرقام العربية-الهندية (٠-٩) والفواصل العربية (٬) والفاصلة العشرية العربية (٫)
 * وأيضاً الفاصلة الغربية — كانت الأرقام العربية تُحلل إلى 0.0 بصمت فيُحفظ حقل فارغ بلا أي تغذية راجعة.
 *
 * [P7-L1 إصلاح] alias مفوض فقط: التنفيذ القياسي انتقل إلى طبقة util
 * (com.superbiz.app.util.NumText.parseNum) لأن Common.kt في ui وكان استيرادها
 * من vm خرقاً لاتجاه الطبقات. التوقيع كما هو — كل المستدعين الحاليين
 * (شاشات ui وvm/FeatureVMs) لا يتغيرون، والجديد يُستورد من util مباشرة.
 *
 * [P7-L2 إصلاح] عقد الدالة (موثق بالكامل عند NumText.parseNum): سلسلة منحلة
 * (فارغة/فراغات/حروف/صيغة غير رقمية) ⇒ 0.0 صامتاً بلا استثناء وبلا تسجيل —
 * قصداً: «حقل فارغ» يُحفظ صفراً، والمسار ساخن يُستدعى في كل recomposition
 * فالتسجيل فيه ضجيج. السالب يُقبل وتُقيَّد دلالته عند المستدعي.
*/
fun parseNum(s: String): Double = com.superbiz.app.util.NumText.parseNum(s)

fun fmtNum(v: Double): String {
    val nf = NumberFormat.getNumberInstance(Locale.US)
    nf.maximumFractionDigits = 2
    nf.minimumFractionDigits = 0
    return nf.format(v)
}

/** حالة فارغة */
@Composable
fun EmptyState(text: String, icon: ImageVector? = null) {
    val g = glassColors()
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        icon?.let {
            Icon(it, null, tint = g.textSecondary.copy(alpha = 0.5f), modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(10.dp))
        }
        Text(text, color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

/** [P28-SECTIONS] عنوان قسم داخلي موحّد — أيقونة + نص بارز؛
 * النمط نفسه الذي قُدّم في قسم تحليلات الديون () وامتد لكل الشاشات () */
@Composable
fun SectionLabel(title: String) {
    val g = glassColors()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            Icons.Rounded.Insights, null,
            tint = g.accent, modifier = Modifier.size(16.dp)
        )
        Text(
            title,
            fontSize = 13.sp, fontWeight = FontWeight.Bold, color = g.textPrimary
        )
    }
}

/** صف شريط علوي بسيط للشاشات الداخلية */
@Composable
fun SubHeader(title: String, onBack: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val g = glassColors()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                // AutoMirrored — كان السهم يشير بالاتجاه الخاطئ في الوضع العربي RTL
                Icon(Icons.AutoMirrored.Rounded.ArrowBackIos, null, tint = g.textPrimary)
            }
        }
        Text(
            title, style = MaterialTheme.typography.titleLarge,
            color = g.textPrimary, modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}
