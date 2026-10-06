package com.superbiz.app.ui.screens.statement

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.superbiz.app.R
import com.superbiz.app.ui.components.BizField
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.components.SubHeader
import com.superbiz.app.ui.nav.Routes
import com.superbiz.app.ui.screens.stringResourceCompat
import com.superbiz.app.ui.theme.Amber
import com.superbiz.app.ui.theme.Cyan
import com.superbiz.app.ui.theme.GreenDeep
import com.superbiz.app.ui.theme.RedDeep
import com.superbiz.app.ui.theme.Vio
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.vm.AppVM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [P17-c] استوديو قوالب كشف الحساب (المسار: statement_templates?select={select}).
 *
 * select=1 ⇒ وضع الاختيار: «استخدام» يعيد معرّف القالب عبر savedStateHandle
 * إلى StatementScreen (مفتاح st_selected_template) ثم يعود.
 * بطاقات الشبكة تعرض مصغّرة PDF حقيقية (StatementPdfRenderer + PdfRenderer) مع
 * تخزين مؤقت في الذاكرة والقرص — بلا عيّنة (قاعدة بلا أطراف) يظهر هيكل عظمي صادق.
 */
@Composable
fun TemplateStudioScreen(appVM: AppVM, nav: NavHostController, selectMode: Boolean, onOpenSettings: () -> Unit = {}) {
    val context = LocalContext.current
    val g = glassColors()
    val scope = rememberCoroutineScope()
    val settings by appVM.settings.collectAsState()
    val ar = settings.language == "ar"

    var customs by remember { mutableStateOf(listOf<com.superbiz.app.pdf.statement.StatementTemplateDef>()) }
    var templates by remember { mutableStateOf(listOf<TemplateUi>()) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var favOnly by remember { mutableStateOf(false) }
    var customOnly by remember { mutableStateOf(false) }
    var favs by remember { mutableStateOf(setOf<String>()) }
    var defaultId by remember { mutableStateOf(StatementUiFacade.defaultTemplateId) }
    var dupFor by remember { mutableStateOf<String?>(null) }
    var deleteFor by remember { mutableStateOf<TemplateUi?>(null) }
    var sampleData by remember { mutableStateOf<com.superbiz.app.domain.statement.StatementData?>(null) }
    var logo by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    // تركيب القائمة: مدمجة + مخصصة — بلا استدعاءات تعليق داخل التركيب
    fun rebuild() {
        templates = (StatementUiFacade.allTemplates() + customs)
            .distinctBy { it.id }
            .map { StatementUiFacade.templateUiOf(it, favs, defaultId) }
    }

    fun refreshMeta() {
        favs = StatementUiFacade.favorites(context.applicationContext)
        defaultId = StatementUiFacade.defaultTemplateIdPref(context.applicationContext)
            ?: StatementUiFacade.defaultTemplateId
        rebuild()
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val ctx = context.applicationContext
            // [contract ADAPT] القوالب المخصصة من repo إن توفرت لدى 17-a
            customs = runCatching { StatementUiFacade.customTemplates(ctx) }.getOrDefault(emptyList())
            sampleData = StatementUiFacade.sampleDataForThumbnails(ctx)
            logo = StatementUiFacade.loadLogo(ctx)
        }
        refreshMeta()
    }

    val visible = filterTemplates(templates, query, category, favOnly, customOnly)

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 12.dp)) {
        SubHeader(stringResourceCompat(R.string.st_studio), onBack = { nav.popBackStack() },
            // [P18-c] مدخل إعدادات منظومة الكشف — أيقونة واحدة في الشريط العلوي
            trailing = { IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, stringResourceCompat(R.string.st3_settings_title)) } }
        )

        // البحث بالاسم أو المعرّف
        BizField(query, { query = it }, stringResourceCompat(R.string.st_search))
        Spacer(Modifier.height(8.dp))

        // الفئات: الكل/المفضّل/المخصص + فئات العقد
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            StChip(
                stringResourceCompat(R.string.st_category_all),
                category == null && !favOnly && !customOnly
            ) {
                category = null; favOnly = false; customOnly = false
            }
            StChip(stringResourceCompat(R.string.st_favorites), favOnly) {
                favOnly = !favOnly; if (favOnly) { category = null; customOnly = false }
            }
            StChip(stringResourceCompat(R.string.st_custom), customOnly) {
                customOnly = !customOnly; if (customOnly) { category = null; favOnly = false }
            }
            StatementUiFacade.categoryNames().forEach { c ->
                StChip(categoryLabel(c), category == c) {
                    category = if (category == c) null else c
                    favOnly = false; customOnly = false
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // إنشاء قالب مخصص — نقطة البداية نسخة من الافتراضي الحالي
        Row(verticalAlignment = Alignment.CenterVertically) {
            StAction(stringResourceCompat(R.string.st_new_custom), GreenDeep) { dupFor = defaultId }
            Spacer(Modifier.width(8.dp))
            Text(
                stringResourceCompat(R.string.st_studio_count, visible.size),
                fontSize = 11.sp, color = g.textSecondary
            )
        }
        Spacer(Modifier.height(8.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f).padding(bottom = 12.dp)
        ) {
            items(visible, key = { it.id }) { t ->
                TemplateCard(
                    t = t, ar = ar, selectMode = selectMode,
                    // [P17-c] تعريف القالب يُمرَّر من القائمة المركّبة — المخصصات تُبنى من الكيانات
                    def = customs.firstOrNull { it.id == t.id }
                        ?: StatementUiFacade.templateById(t.id),
                    sampleData = sampleData, logo = logo, context = context,
                    onSelect = {
                        // [P17-c] إرجاع الاختيار إلى StatementScreen عبر savedStateHandle
                        nav.previousBackStackEntry?.savedStateHandle?.set("st_selected_template", t.id)
                        nav.popBackStack()
                    },
                    onFav = {
                        favs = StatementUiFacade.toggleFavorite(context.applicationContext, t.id)
                        rebuild()
                    },
                    onDefault = {
                        StatementUiFacade.setDefaultTemplateIdPref(context.applicationContext, t.id)
                        defaultId = t.id
                        rebuild()
                    },
                    onDuplicate = { dupFor = t.id },
                    onEdit = { nav.navigate(Routes.STATEMENT_TEMPLATE_EDIT + "/" + t.id) },
                    onDelete = { deleteFor = t }
                )
            }
        }
    }

    // حوار تسمية النسخة (复制/إنشاء مخصص)
    if (dupFor != null) {
        // [P30-D]: القراءة في نطاق Composable ثم الاستخدام داخل onClick
        val customLabel = stringResourceCompat(R.string.stmt_template_custom)
        var newName by remember(dupFor) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { dupFor = null },
            containerColor = g.surfaceStrong,
            title = { Text(stringResourceCompat(R.string.st_duplicate), color = g.textPrimary) },
            text = { BizField(newName, { newName = it }, stringResourceCompat(R.string.name)) },
            confirmButton = {
                TextButton(onClick = {
                    val src = dupFor ?: return@TextButton
                    val name = newName.ifBlank {
                        customLabel + " " + (templates.size + 1)
                    }
                    dupFor = null
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            StatementUiFacade.duplicateTemplate(context.applicationContext, src, name)
                            customs = runCatching {
                                StatementUiFacade.customTemplates(context.applicationContext)
                            }.getOrDefault(customs)
                        }
                        withContext(Dispatchers.Main) { refreshMeta() }
                    }
                }) {
                    Text(stringResourceCompat(R.string.confirm), color = g.accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { dupFor = null }) {
                    Text(stringResourceCompat(R.string.cancel), color = g.textSecondary)
                }
            }
        )
    }

    // تأكيد حذف قالب مخصص
    deleteFor?.let { t ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            containerColor = g.surfaceStrong,
            title = { Text(stringResourceCompat(R.string.st_delete), color = RedDeep) },
            text = {
                Text(
                    stringResourceCompat(R.string.st_delete_confirm, t.label(ar)),
                    color = g.textSecondary, fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = t.id
                    deleteFor = null
                    scope.launch(Dispatchers.IO) {
                        runCatching { StatementUiFacade.deleteTemplate(context.applicationContext, id) }
                        customs = runCatching {
                            StatementUiFacade.customTemplates(context.applicationContext)
                        }.getOrDefault(emptyList())
                        withContext(Dispatchers.Main) { refreshMeta() }
                    }
                }) {
                    Text(stringResourceCompat(R.string.delete), color = RedDeep, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteFor = null }) {
                    Text(stringResourceCompat(R.string.cancel), color = g.textSecondary)
                }
            }
        )
    }
}

/** بطاقة قالب بمصغّرة حقيقية + صف إجراءات */
@Composable
private fun TemplateCard(
    t: TemplateUi,
    ar: Boolean,
    selectMode: Boolean,
    def: com.superbiz.app.pdf.statement.StatementTemplateDef?,
    sampleData: com.superbiz.app.domain.statement.StatementData?,
    logo: android.graphics.Bitmap?,
    context: android.content.Context,
    onSelect: () -> Unit,
    onFav: () -> Unit,
    onDefault: () -> Unit,
    onDuplicate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val g = glassColors()
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (def != null && sampleData != null) {
                TemplateThumbnail(
                    templateId = t.id,
                    style = def.style,
                    sampleData = sampleData,
                    logo = logo,
                    context = context
                )
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(g.surfaceStrong),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResourceCompat(R.string.st_no_sample),
                        fontSize = 11.sp, color = g.textSecondary
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t.label(ar), style = MaterialTheme.typography.titleSmall,
                    color = g.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onFav, modifier = Modifier.width(30.dp).height(30.dp)) {
                    Icon(
                        if (t.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        stringResourceCompat(R.string.st_favorites),
                        tint = if (t.isFavorite) Amber else g.textSecondary,
                        modifier = Modifier.width(18.dp).height(18.dp)
                    )
                }
            }
            Text(
                t.desc(ar), fontSize = 11.sp, color = g.textSecondary,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (t.isDefault) {
                    Text(
                        stringResourceCompat(R.string.st_default),
                        color = GreenDeep, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Text(
                        stringResourceCompat(R.string.st_set_default),
                        color = g.textSecondary, fontSize = 11.sp,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onDefault() }
                    )
                }
                // [P34-M4-6 إصلاح] كان الشرطي ميتاً (فرعا if كلاهما st_use) فتُسمّى
                // السلوكان باسم واحد: وضع الاختيار «استخدام» يعيد القالب، وغيره
                // «تطبيق» يطبّق القالب بتعيينه افتراضياً — سلوكان باسمين صريحين
                StAction(
                    stringResourceCompat(if (selectMode) R.string.st_use else R.string.st_apply),
                    Vio
                ) { if (selectMode) onSelect() else onDefault() }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StAction(stringResourceCompat(R.string.st_duplicate), Cyan) { onDuplicate() }
                Spacer(Modifier.width(6.dp))
                if (!t.isBuiltIn) {
                    StAction(stringResourceCompat(R.string.st_edit), g.accent) { onEdit() }
                    Spacer(Modifier.width(6.dp))
                    StAction(stringResourceCompat(R.string.st_delete), RedDeep) { onDelete() }
                }
            }
        }
    }
}

/** تسمية فئة — مفاتيح st_category_* المتوقعة، والاسم الغريب يُجمَّل نصاً */
@Composable
fun categoryLabel(name: String): String {
    val key = when (name.uppercase()) {
        "CLASSIC" -> R.string.st_category_classic
        "MODERN" -> R.string.st_category_modern
        "MINIMAL" -> R.string.st_category_minimal
        "BUSINESS" -> R.string.st_category_business
        "ELEGANT" -> R.string.st_category_elegant
        "BOLD" -> R.string.st_category_bold
        "COMPACT" -> R.string.st_category_compact
        "FORMAL" -> R.string.st_category_formal
        "COLORFUL" -> R.string.st_category_colorful
        "TABLE" -> R.string.st_category_table
        else -> return name.lowercase().replace('_', ' ')
    }
    return stringResourceCompat(key)
}
