package com.superbiz.app.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superbiz.app.R
import com.superbiz.app.core.AppPrefs
import com.superbiz.app.data.repo.SettingsRepo
import com.superbiz.app.domain.DashboardPrefsP44
import com.superbiz.app.ui.theme.glassColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * جولة 5 [P44-K1] — اللوحات المخصصة القابلة للتكييف:
 *
 * **InsightsStack** يعوض المكدس الموضعي لمجموعات بطاقات الرؤى في الشاشات
 * الثماني: يقرأ التخطيط المخصص من الخزنة الحيّة AppPrefs، يرتب المجموعات
 * ويخفي ما أُخفي عبر محرك DashboardPrefsP44 النقي، ويضع رقاقة «تخصيص البطاقات»
 * فوق المكدس تفتح **InsightsCustomizeDialog** (أسهم ترتيب + مربعات إظهار +
 * استعادة الافتراضي). كل تعديل يُكتب في DataStore عبر SettingsRepo ويُزامِن
 * AppPrefs فوراً كي تلتقطه الشاشات المفتوحة لاحقاً بنفس العقد.
 *
 * عقد التكامل: الشاشة تعلن مجموعاتها القياسية بترتيبها التاريخي عبر
 * insightsGroup(...) — لا يتغير أي شيء في توقيعات بطاقات R9..R15 نفسها
 * ولا في عقود السكيلتون (P39) — المحرك يعيد المفاتيح القياسية فقط.
 */

/** مجموعة بطاقات معرّفة بمفتاحها القياسي ومحتواها التركيبي */
class InsightsGroup(val key: String, val content: @Composable () -> Unit)

/** مصنع يمكّن استدلال ‎@Composable في موضع الاستدعاء بلا تحويلات صريحة */
fun insightsGroup(key: String, content: @Composable () -> Unit) = InsightsGroup(key, content)

/** تسمية المجموعة من سلاسل الواجهة — الموجات الثماني المعروفة */
@Composable
fun insightGroupLabel(key: String): String = stringResource(
    when (key) {
        "smart" -> R.string.ins_layout_group_smart
        "r9" -> R.string.ins_layout_group_r9
        "r10" -> R.string.ins_layout_group_r10
        "r11" -> R.string.ins_layout_group_r11
        "r12" -> R.string.ins_layout_group_r12
        "r13" -> R.string.ins_layout_group_r13
        "r14" -> R.string.ins_layout_group_r14
        "r15" -> R.string.ins_layout_group_r15
        "r16" -> R.string.ins_layout_group_r16
        else -> R.string.ins_layout_group_custom
    }
)

/**
 * مكدس بطاقات الرؤى المخصص — يعوض الاستدعاءات الموضعية للمجموعات في الشاشة:
 * ```
 * InsightsStack("home", listOf(
 *     insightsGroup("smart") { HomeSmartCard(appVM, smartVM) },
 *     insightsGroup("r9") { HomeR9Card(appVM, r9VM) }, ...
 * ))
 * ```
 */
@Composable
fun InsightsStack(screenKey: String, groups: List<InsightsGroup>) {
    var raw by remember { mutableStateOf(AppPrefs.dashboardLayout) }
    var showCustomizer by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val g = glassColors()

    // كتابة التخصيص: الحالة المحلية فوراً + الخزنة الحيّة + DataStore على IO
    fun persist(layout: DashboardPrefsP44.Layout) {
        val json = layout.toJson()
        raw = json.ifEmpty { null }
        AppPrefs.dashboardLayout = raw
        scope.launch {
            try {
                withContext(Dispatchers.IO) { SettingsRepo(ctx).setDashboardLayout(json.ifEmpty { null }) }
            } catch (e: Exception) { /* الافتراضي يبقى سلوك الطوارئ الصادق */ }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // رقاقة التخصيص — نهاية السطر كي لا تشغل مسار القراءة
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(g.accent.copy(alpha = 0.12f))
                    .clickable { showCustomizer = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Tune, null,
                    tint = g.accent, modifier = Modifier.height(14.dp)
                )
                Spacer(Modifier.padding(horizontal = 3.dp))
                Text(
                    stringResource(R.string.ins_layout_customize),
                    color = g.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
            }
        }

        // المجموعات بالترتيب الفعّال — المحرك يعيد مفاتيح القياسي فقط
        val ordered = DashboardPrefsP44.effectiveOrder(screenKey, groups.map { it.key }, raw)
        ordered.forEach { key ->
            groups.first { it.key == key }.content()
        }
    }

    if (showCustomizer) {
        InsightsCustomizeDialog(
            screenKey = screenKey,
            canonical = groups.map { it.key },
            raw = raw,
            onChange = { persist(it) },
            onDismiss = { showCustomizer = false },
        )
    }
}

/**
 * نافذة تخصيص بطاقات الشاشة: كل المجموعات القياسية بترتيبها الكامل (الظاهر
 * والمخفي معاً — المخفي بلون خافت)، أسهم الترتيب تعمل على القائمة الكاملة
 * كي يبقى موضع العودة للمخفي محفوظاً، ومربع الإظهار يقلب hidden، وزر
 * الاستعادة يحذف تفضيل الشاشة كلياً (عودة حرفية للترتيب التاريخي).
 */
@Composable
fun InsightsCustomizeDialog(
    screenKey: String,
    canonical: List<String>,
    raw: String?,
    onChange: (DashboardPrefsP44.Layout) -> Unit,
    onDismiss: () -> Unit,
    // [P45-Q1] جولة 6 — وسائط التعميم الاختيارية: نفس النافذة تخدم أقسام التقارير
    // بتسمياتها وعنوانها (الافتراضي null = سلوك P44 حرفياً — تسميات بطاقات الرؤى)
    labels: Map<String, String>? = null,
    title: String? = null,
    hint: String? = null,
) {
    val g = glassColors()
    val layout = DashboardPrefsP44.Layout.parse(raw)
    val pref = layout.screens[screenKey]
        ?: DashboardPrefsP44.ScreenPref(canonical, emptySet())
    val order = pref.order.ifEmpty { canonical }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = g.surfaceStrong,
        title = { Text(title ?: stringResource(R.string.ins_layout_title), color = g.textPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    hint ?: stringResource(R.string.ins_layout_hint),
                    color = g.textSecondary, fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                order.forEachIndexed { i, key ->
                    val visible = key !in pref.hidden
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = visible,
                            onCheckedChange = {
                                onChange(DashboardPrefsP44.withToggle(layout, screenKey, key, canonical))
                            }
                        )
                        Text(
                            labels?.get(key) ?: insightGroupLabel(key),
                            color = if (visible) g.textPrimary else g.textSecondary,
                            fontSize = 14.sp,
                            fontWeight = if (visible) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier
                                .weight(1f)
                                // النقر على التسمية يقلب الإظهار — نفس فعل المربع (وضع استخدام أوسع)
                                .clickable {
                                    onChange(DashboardPrefsP44.withToggle(layout, screenKey, key, canonical))
                                }
                        )
                        IconButton(
                            enabled = i > 0,
                            onClick = {
                                onChange(DashboardPrefsP44.withMove(layout, screenKey, i, +1, canonical))
                            }
                        ) {
                            Icon(
                                Icons.Rounded.KeyboardArrowUp,
                                stringResource(R.string.a11y_move_up),
                                tint = if (i > 0) g.accent else Color.Gray
                            )
                        }
                        IconButton(
                            enabled = i < order.size - 1,
                            onClick = {
                                onChange(DashboardPrefsP44.withMove(layout, screenKey, i, -1, canonical))
                            }
                        ) {
                            Icon(
                                Icons.Rounded.KeyboardArrowDown,
                                stringResource(R.string.a11y_move_down),
                                tint = if (i < order.size - 1) g.accent else Color.Gray
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ins_layout_done), color = g.accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = { onChange(DashboardPrefsP44.withReset(layout, screenKey)) }) {
                Text(stringResource(R.string.ins_layout_reset), color = g.textSecondary)
            }
        }
    )
}
