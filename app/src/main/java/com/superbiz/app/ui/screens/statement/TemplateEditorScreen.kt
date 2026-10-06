package com.superbiz.app.ui.screens.statement

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.superbiz.app.R
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.components.SubHeader
import com.superbiz.app.ui.screens.stringResourceCompat
import com.superbiz.app.ui.theme.RedDeep
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.vm.AppVM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [P17-c] محرر قالب كشف الحساب (المسار: statement_template_edit/{id}).
 *
 * يحرر StatementStyle عبر نموذج StyleUiModel المعزول في الواجهة، بمعاينة حية
 * حقيقية (توليد PDF فعلي + PdfRenderer) تُحدَّث بخطى خفيفة مع كل تغيير.
 * الحفظ مسموح للقوالب المخصصة فقط (العقدة تسلسل النمط JSON عبر repo).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TemplateEditorScreen(appVM: AppVM, templateId: String, nav: NavHostController) {
    val context = LocalContext.current
    val g = glassColors()
    val scope = rememberCoroutineScope()
    val settings by appVM.settings.collectAsState()

    var def by remember { mutableStateOf<com.superbiz.app.pdf.statement.StatementTemplateDef?>(null) }
    var ui by remember {
        mutableStateOf<StatementUiFacade.StyleUiModel?>(null)
    }
    var sampleData by remember { mutableStateOf<com.superbiz.app.domain.statement.StatementData?>(null) }
    var logo by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(templateId) {
        withContext(Dispatchers.IO) {
            // [P17-c] بحث سياقي يغطي المدمجات والمخصصات (CUSTOM-*) معاً
            val d = StatementUiFacade.templateByIdOf(context.applicationContext, templateId)
            def = d
            ui = d?.let { StatementUiFacade.styleUiOf(it.style) }
            sampleData = StatementUiFacade.sampleDataForThumbnails(context.applicationContext)
            logo = StatementUiFacade.loadLogo(context.applicationContext)
        }
    }

    val cur = ui
    if (cur == null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            SubHeader(stringResourceCompat(R.string.st_editor_title), onBack = { nav.popBackStack() })
            StErrorRow(stringResourceCompat(R.string.st_template_missing))
        }
        return
    }
    val isCustom = templatesNotBuiltIn(templateId)

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        SubHeader(
            stringResourceCompat(R.string.st_editor_title),
            onBack = { nav.popBackStack() }
        )
        if (!isCustom) {
            // مدمج ⇒ النسخ عبر الاستوديو أولاً ثم تحرير النسخة
            StHint(stringResourceCompat(R.string.st_editor_builtin), tint = RedDeep)
            Spacer(Modifier.height(8.dp))
        }

        // ── الألوان الخمسة ──
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResourceCompat(R.string.st_editor_colors),
                    style = MaterialTheme.typography.titleSmall, color = g.textPrimary
                )
                ColorRow(stringResourceCompat(R.string.st_color_primary), cur.colorPrimary) {
                    ui = cur.copy(colorPrimary = it)
                }
                ColorRow(stringResourceCompat(R.string.st_color_accent), cur.colorAccent) {
                    ui = cur.copy(colorAccent = it)
                }
                ColorRow(stringResourceCompat(R.string.st_color_text), cur.colorText) {
                    ui = cur.copy(colorText = it)
                }
                ColorRow(stringResourceCompat(R.string.st_color_header_bg), cur.colorHeaderBg) {
                    ui = cur.copy(colorHeaderBg = it)
                }
                ColorRow(stringResourceCompat(R.string.st_color_row_alt), cur.colorRowAlt) {
                    ui = cur.copy(colorRowAlt = it)
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // ── الأحجام والتخطيط ──
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResourceCompat(R.string.st_editor_fonts),
                    style = MaterialTheme.typography.titleSmall, color = g.textPrimary
                )
                SliderRow(
                    stringResourceCompat(R.string.st_font_title),
                    cur.titleSizeSp, 8f..30f
                ) { ui = cur.copy(titleSizeSp = it) }
                SliderRow(
                    stringResourceCompat(R.string.st_font_body),
                    cur.bodySizeSp, 7f..22f
                ) { ui = cur.copy(bodySizeSp = it) }
                SliderRow(
                    stringResourceCompat(R.string.st_margin),
                    cur.marginDp, 0f..56f
                ) { ui = cur.copy(marginDp = it) }
                SliderRow(
                    stringResourceCompat(R.string.st_spacing),
                    cur.spacingDp, 0f..32f
                ) { ui = cur.copy(spacingDp = it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResourceCompat(R.string.st_editor_landscape),
                        color = g.textPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f)
                    )
                    Switch(checked = cur.landscape, onCheckedChange = { ui = cur.copy(landscape = it) })
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // ── أقسام النمط: رأس/جدول/ملخص/تذييل ──
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResourceCompat(R.string.st_editor_sections),
                    style = MaterialTheme.typography.titleSmall, color = g.textPrimary
                )
                OptionGroup(stringResourceCompat(R.string.st_el_header_style), cur.headerStyle, 0..4) {
                    ui = cur.copy(headerStyle = it)
                }
                OptionGroup(stringResourceCompat(R.string.st_el_table_style), cur.tableStyle, 0..4) {
                    ui = cur.copy(tableStyle = it)
                }
                OptionGroup(stringResourceCompat(R.string.st_el_summary_style), cur.summaryStyle, 0..4) {
                    ui = cur.copy(summaryStyle = it)
                }
                OptionGroup(stringResourceCompat(R.string.st_el_footer_style), cur.footerStyle, 0..4) {
                    ui = cur.copy(footerStyle = it)
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // ── التوقيع والختم ──
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResourceCompat(R.string.st_signature),
                    style = MaterialTheme.typography.titleSmall, color = g.textPrimary
                )
                // زوايا 2×2 — اختيار بصري بلا نصوص
                Text(
                    stringResourceCompat(R.string.st_corner),
                    fontSize = 12.sp, color = g.textSecondary
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CornerBox(0, cur.signatureCorner) { ui = cur.copy(signatureCorner = it) }
                        CornerBox(1, cur.signatureCorner) { ui = cur.copy(signatureCorner = it) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CornerBox(2, cur.signatureCorner) { ui = cur.copy(signatureCorner = it) }
                        CornerBox(3, cur.signatureCorner) { ui = cur.copy(signatureCorner = it) }
                    }
                }
                SliderRow(
                    stringResourceCompat(R.string.st_stamp_size),
                    cur.signatureScale, 0.2f..1f
                ) { ui = cur.copy(signatureScale = it) }
                SliderRow(
                    stringResourceCompat(R.string.st_stamp_opacity),
                    cur.signatureAlpha, 0f..1f
                ) { ui = cur.copy(signatureAlpha = it) }
            }
        }
        Spacer(Modifier.height(10.dp))

        // ── عناصر PDF العشرون ──
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResourceCompat(R.string.st_elements),
                    style = MaterialTheme.typography.titleSmall, color = g.textPrimary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StatementUiFacade.allElementNames().forEach { name ->
                        StChip(name.lowercase().replace('_', ' '), name in cur.show) {
                            ui = cur.copy(show = toggleElement(cur.show, name))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // ── المعاينة الحية ──
        Text(
            stringResourceCompat(R.string.st_preview),
            style = MaterialTheme.typography.titleSmall, color = g.textPrimary
        )
        Spacer(Modifier.height(6.dp))
        LivePdfPreview(
            key = cur,
            generate = {
                val d = def ?: throw IllegalStateException("template missing")
                withContext(Dispatchers.IO) {
                    val data = sampleData
                        ?: StatementUiFacade.sampleDataForThumbnails(context.applicationContext)
                        ?: throw IllegalStateException(context.getString(R.string.st_no_sample))
                    StatementUiFacade.renderPdf(
                        context.applicationContext, data,
                        StatementUiFacade.applyStyleUi(d.style, cur),
                        logo, null, null, null,
                        StatementUiFacade.previewDir(context.applicationContext),
                        "editor_$templateId.pdf"
                    )
                }
            }
        )
        Spacer(Modifier.height(10.dp))

        // ── الحفظ ──
        saveError?.let { StErrorRow(it) }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(if (isCustom) g.accent else g.textSecondary.copy(alpha = 0.3f))
                .clickable(enabled = isCustom && !saving) {
                    val d = def ?: return@clickable
                    saving = true; saveError = null
                    scope.launch {
                        try {
                            StatementUiFacade.saveTemplateConfig(
                                context.applicationContext, templateId,
                                d.copy(style = StatementUiFacade.applyStyleUi(d.style, cur))
                            )
                            withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(
                                    context, context.getString(R.string.st_saved_simple),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                                nav.popBackStack()
                            }
                        } catch (e: Exception) {
                            saveError = context.getString(
                                R.string.st_fail_reason, (e.message ?: e.javaClass.simpleName).take(80)
                            )
                        } finally { saving = false }
                    }
                }
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                stringResourceCompat(if (saving) R.string.st_saving else R.string.save),
                color = Color.White, fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}

/** هل المعرّف من المدمجات؟ (المخصص ما أُنشئ بنسخة) */
private fun templatesNotBuiltIn(id: String): Boolean =
    StatementUiFacade.allTemplates().none { it.id == id }

/** صف لون: لوحة ألوان جاهزة + إدخال HEX + عيّنة */
@Composable
private fun ColorRow(label: String, argb: Int, onPick: (Int) -> Unit) {
    val g = glassColors()
    var hex by remember(argb) { mutableStateOf("%06X".format(argb and 0xFFFFFF)) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color(argb))
                    .border(1.dp, g.border, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = 13.sp, color = g.textPrimary, modifier = Modifier.weight(1f))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // لوحة مسبقة — 8 ألوان من هوية التطبيق ومحايدة
            listOf(
                0xFF7C3AED, 0xFF22D3EE, 0xFF10B981, 0xFFEF4444,
                0xFFFBBF24, 0xFF3B82F6, 0xFF101733, 0xFFF3F5FB
            ).forEach { c ->
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color(c.toInt()))
                        .border(
                            1.dp,
                            if ((c.toInt() and 0xFFFFFF) == (argb and 0xFFFFFF)) g.accent else g.border,
                            CircleShape
                        )
                        .clickable { onPick(c.toInt()) }
                )
            }
            OutlinedTextField(
                value = hex,
                onValueChange = { v ->
                    hex = v
                    parseHexColor(v)?.let(onPick)
                },
                modifier = Modifier.width(110.dp).height(52.dp),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                shape = RoundedCornerShape(10.dp)
            )
        }
    }
}

/** صف منزلق بتسمية وقيمة */
@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val g = glassColors()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 12.sp, color = g.textSecondary, modifier = Modifier.weight(1f))
            Text(
                "%.1f".format(value),
                fontSize = 12.sp, color = g.textPrimary, fontWeight = FontWeight.Bold
            )
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}

/** مجموعة خيارات نمط (5 خيارات بمسميات مشتركة) */
@Composable
private fun OptionGroup(label: String, current: Int, range: IntRange, onPick: (Int) -> Unit) {
    val g = glassColors() // [P17-integration] النطاق لا يرث g الدالة المستدعية
    val opts = listOf(
        stringResourceCompat(R.string.st_opt_classic),
        stringResourceCompat(R.string.st_opt_modern),
        stringResourceCompat(R.string.st_opt_compact),
        stringResourceCompat(R.string.st_opt_gradient),
        stringResourceCompat(R.string.st_opt_minimal)
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, color = g.textSecondary)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            range.forEach { i ->
                StChip(opts[i], current == i) { onPick(i) }
            }
        }
    }
}

/** مربع زاوية 2×2 لموضع التوقيع */
@Composable
private fun CornerBox(index: Int, current: Int, onPick: (Int) -> Unit) {
    val g = glassColors()
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (current == index) g.accent else g.surface)
            .border(1.dp, if (current == index) g.accent else g.border, RoundedCornerShape(8.dp))
            .clickable { onPick(index) }
    )
}
