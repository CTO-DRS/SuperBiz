package com.superbiz.app.ui.screens

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.TrendingDown
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.superbiz.app.MainActivity
import com.superbiz.app.R
import com.superbiz.app.data.db.Expense
import com.superbiz.app.domain.ExpenseAlert
import com.superbiz.app.domain.ExpenseTemplates
import com.superbiz.app.ui.components.SectionLabel
import com.superbiz.app.ui.components.BizField
import com.superbiz.app.ui.components.EmptyState
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.components.IconChip
import com.superbiz.app.ui.components.KpiCard
import com.superbiz.app.ui.components.SubHeader
import com.superbiz.app.ui.components.numberFieldOptions
import com.superbiz.app.ui.components.parseNum
import com.superbiz.app.ui.theme.Amber
import com.superbiz.app.ui.theme.GreenDeep
import com.superbiz.app.ui.theme.Pink
import com.superbiz.app.ui.theme.RedDeep
import com.superbiz.app.ui.theme.Vio
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.util.Dates
import com.superbiz.app.util.Money
import com.superbiz.app.vm.AppVM
import com.superbiz.app.vm.ExpensesVM
import com.superbiz.app.VMFactory

/**
 * : شاشة المصروفات الحقيقية — قائمة حية من جدول expenses في قاعدة البيانات،
 * إضافة بقيد مزدوج تلقائي، حذف يعكس القيد، وإجماليات SQL مباشرة.
*/
@Composable
fun ExpensesScreen(appVM: AppVM, nav: NavHostController) {
    val activity = LocalContext.current as? MainActivity ?: return
    val vm: ExpensesVM = viewModel(factory = remember { VMFactory(activity) })
    val expenses by vm.expenses.collectAsState()
    val filter by vm.categoryFilter.collectAsState()
    val monthTotal by vm.monthTotal.collectAsState()
    val todayTotal by vm.todayTotal.collectAsState()
    val byCategory by vm.byCategory.collectAsState()
    // توقع مصروفات بقية الشهر
    // [P5-H9 إصلاح]: VMs الرؤى مشتركة على مستوى النشاط — كانت كل شاشة تنشئ نسختها وتشغل loadAll كاملاً (حتى ×8 تكلفة لكل جولة تنقل)
    val r9VM: com.superbiz.app.vm.R9InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    // رؤى الموجة R10 للمصروفات
    val r10VM: com.superbiz.app.vm.R10InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    // رؤى الموجة R11 للمصروفات (الموازنة الضمنية/ثابت-متغير)
    val r11VM: com.superbiz.app.vm.R11InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    // VM الرؤى الذكية للموجة R12 — عبر R12Smart
    val r12VM: com.superbiz.app.vm.R12InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    val r13VM: com.superbiz.app.vm.R13InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    // VM الرؤى الذكية للموجة R14 — عبر R14Smart
    val r14VM: com.superbiz.app.vm.R14InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    val r15VM: com.superbiz.app.vm.R15InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    val symbol by appVM.symbol.collectAsState()
    val g = glassColors()

    var showAdd by remember { mutableStateOf(false) }

    // وظيفة 31 — القوالب المتكررة + القالب المختار لإضافة سريعة
    val templates by vm.templates.collectAsState()
    var quickTemplate by remember { mutableStateOf<ExpenseTemplates.Template?>(null) }
    // وظيفة 32 — حد المصروف الشهري + حوار تعيينه
    val expenseLimit by vm.expenseLimit.collectAsState()
    var limitDialog by remember { mutableStateOf(false) }

    // تحديث الإجماليات عند العودة للشاشة
    LaunchedEffect(Unit) { vm.refresh() }

    Column(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp)
    ) {
        SubHeader(
            stringResourceCompat(R.string.expenses_title),
            onBack = { nav.popBackStack() }
        ) {
            TextButton(onClick = { showAdd = true }) {
                Text("+ " + stringResourceCompat(R.string.add), color = g.accent, fontWeight = FontWeight.Bold)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KpiCard(
                Icons.Rounded.TrendingDown, Money.formatP(monthTotal, symbol),  // [P33-P8] قروش
                stringResourceCompat(R.string.exp_month_total), RedDeep,
                modifier = Modifier.weight(1f)
            )
            KpiCard(
                Icons.Rounded.Today, Money.formatP(todayTotal, symbol),  // [P33-P8] قروش
                stringResourceCompat(R.string.exp_today), Vio,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(12.dp))

        // وظيفة 32 — بطاقة حد المصروف + الإنذار المبكر (إخفاء صادق بلا حد)
        LimitCard(limit = expenseLimit, monthTotal = Money.fromPiasters(monthTotal), symbol = symbol, onEdit = { limitDialog = true })  // [P33-P8] الحد إعداد ريال والمجموع قروش
        Spacer(Modifier.height(12.dp))

        // [P23-FIX] بطاقات الذكاء السبع كانت هنا بعد بطاقة الحد وقبل القائمة — نفس شكوى
        // «تشوه المنظر» (نمط P20 في الرئيسية والديون وP23 في التقارير). نُقلت إلى نهاية القائمة.
        Spacer(Modifier.height(12.dp))

        if (byCategory.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterPill(stringResourceCompat(R.string.all), filter.isBlank()) { vm.categoryFilter.value = "" }
                // [P6-M47 إصلاح] رقاقة «أخرى» كانت غير قابلة للوصول عملياً: take(3) قد يخفيها
                // والفلتر لا يطابق الفئة الفارغة أصلاً — نُظهرها دائماً إن وُجدت في التوزيع
                val top = byCategory.take(3)
                top.forEach { (cat, _) ->
                    FilterPill(cat, filter == cat) { vm.categoryFilter.value = cat }
                }
                val otherEntry = byCategory.firstOrNull { it.first == com.superbiz.app.vm.ExpensesVM.OTHER_LABEL }
                if (otherEntry != null && top.none { it.first == com.superbiz.app.vm.ExpensesVM.OTHER_LABEL }) {
                    FilterPill(otherEntry.first, filter == otherEntry.first) {
                        vm.categoryFilter.value = otherEntry.first
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // وظيفة 31 — صف «إضافة سريعة» من المصروفات المتكررة (إخفاء صادق بلا قوالب)
        if (templates.isNotEmpty()) {
            Text(
                stringResourceCompat(R.string.exp_quick_add),
                fontWeight = FontWeight.Bold, color = g.accent, fontSize = 13.sp
            )
            Text(
                stringResourceCompat(R.string.exp_quick_hint),
                fontSize = 11.sp, color = g.textSecondary
            )
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                templates.take(6).forEach { t ->
                    FilterPill(
                        t.description + " " + stringResourceCompat(R.string.exp_quick_times, t.count),
                        selected = false
                    ) { quickTemplate = t }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        if (expenses.isEmpty()) {
            EmptyState(stringResourceCompat(R.string.exp_empty), Icons.Rounded.Receipt)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(expenses, key = { it.id }) { e ->
                    ExpenseCard(e, vm, symbol)
                }
                // ══ [P23-FIX] بطاقات الذكاء — بعد قائمة المصروفات ══
                // توقع مصروفات بقية الشهر (Holt على اليومي)
                item(key = "exp-insights-hdr") { SectionLabel(stringResourceCompat(R.string.insights_section_title)) }
                // [P44-K1] جولة 5: المكدس المخصص — ترتيب/إظهار المجموعات السبع بافتضاض المستخدم
                item(key = "insights-stack-expenses") {
                    com.superbiz.app.ui.insights.InsightsStack(
                        com.superbiz.app.domain.DashboardPrefsP44.SCREEN_EXPENSES,
                        listOf(
                            // توقع مصروفات بقية الشهر (Holt على اليومي)
                            com.superbiz.app.ui.insights.insightsGroup("r9") { com.superbiz.app.ui.insights.ExpensesR9Card(r9VM, appVM) },
                            // شواذ المصروفات بطريقة MAD المنيعة
                            com.superbiz.app.ui.insights.insightsGroup("r10") { com.superbiz.app.ui.insights.ExpensesR10Card(appVM, r10VM) },
                            // الموازنة الضمنية مقابل الشهر/تقسيم ثابت-متغير
                            com.superbiz.app.ui.insights.insightsGroup("r11") { com.superbiz.app.ui.insights.ExpensesR11Card(appVM, r11VM) },
                            // انزياح منحنى المصروفات CUSUM
                            com.superbiz.app.ui.insights.insightsGroup("r12") { com.superbiz.app.ui.insights.ExpensesR12Card(appVM, r12VM) },
                            com.superbiz.app.ui.insights.insightsGroup("r13") { com.superbiz.app.ui.insights.ExpensesR13Card(r13VM) },
                            com.superbiz.app.ui.insights.insightsGroup("r14") { com.superbiz.app.ui.insights.ExpensesR14Card(appVM, r14VM) },
                            com.superbiz.app.ui.insights.insightsGroup("r15") { com.superbiz.app.ui.insights.ExpensesR15Card(appVM, r15VM) },
                        )
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddExpenseDialog(vm, onDismiss = { showAdd = false })
    }

    // وظيفة 31 — حوار الإضافة السريعة مسبق التعبئة (الحقول كلها قابلة للتعديل)
    quickTemplate?.let { t ->
        AddExpenseDialog(
            vm,
            initialAmount = Money.num(t.amount),
            initialCategory = t.category,
            initialNote = t.note,
            onDismiss = { quickTemplate = null }
        )
    }

    // وظيفة 32 — حوار تعيين حد المصروف الشهري (0 = مسح)
    if (limitDialog) {
        LimitDialog(
            current = expenseLimit,
            onSave = {
                vm.setExpenseLimit(it)
                limitDialog = false
            },
            onDismiss = { limitDialog = false }
        )
    }
}

@Composable
private fun ExpenseCard(e: Expense, vm: ExpensesVM, symbol: String) {
    val g = glassColors()
    // [P6-M47 إصلاح] الحذف كان بلمسة واحدة بلا تأكيد وهو يعكس قيداً محاسبياً — حوار تأكيد
    // بنفس نمط حوارات التأكيد في المشروع
    var confirmDelete by remember { mutableStateOf(false) }
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconChip(
                Icons.Rounded.Receipt,
                androidx.compose.ui.graphics.Color.White,
                Pink, size = 38.dp, iconSize = 17.dp
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                if (e.category.isNotBlank()) {
                    Text(e.category, fontWeight = FontWeight.Bold, color = g.textPrimary)
                }
                if (e.note.isNotBlank()) {
                    Text(
                        e.note, fontSize = 12.sp, color = g.textSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Text(Dates.short(e.date), fontSize = 11.sp, color = g.textSecondary)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    // [P33-P8] مبلغ المصروف قروش Long (Expense.amount)
                    "-" + Money.formatP(e.amount, symbol),
                    fontWeight = FontWeight.Bold,
                    color = RedDeep
                )
                Text(
                    stringResourceCompat(R.string.delete),
                    fontSize = 12.sp, color = g.textSecondary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { confirmDelete = true }
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }
    }

    // [P6-M47 إصلاح] حوار تأكيد حذف المصروف
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = g.surfaceStrong,
            title = { Text(stringResourceCompat(R.string.confirm), color = g.textPrimary) },
            text = { Text(stringResourceCompat(R.string.exp_delete_confirm), color = g.textPrimary) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete(e)
                }) {
                    Text(stringResourceCompat(R.string.confirm_yes), color = RedDeep, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResourceCompat(R.string.confirm_no), color = g.textSecondary)
                }
            }
        )
    }
}

// وظيفة 31 — حقول مسبقة التعبئة لقالب الإضافة السريعة (كلها قابلة للتعديل)
@Composable
private fun AddExpenseDialog(
    vm: ExpensesVM,
    initialAmount: String = "",
    initialCategory: String = "",
    initialNote: String = "",
    onDismiss: () -> Unit
) {
    val g = glassColors()
    var amount by remember { mutableStateOf(initialAmount) }
    var category by remember { mutableStateOf(initialCategory) }
    var customCat by remember { mutableStateOf(initialCategory) }
    var note by remember { mutableStateOf(initialNote) }
    val suggestions = remember { vm.suggestedCategories() }
    // [P6-M47 إصلاح] كان زر الحفظ يفشل صامتاً على المبلغ غير الصالح — رسالة خطأ داخل الحوار
    var errRes by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = g.surfaceStrong,
        title = { Text(stringResourceCompat(R.string.exp_add_title), color = g.textPrimary) },
        text = {
            // حماية حقول الحوار من تغطية لوحة المفاتيح
            Column(
                Modifier.imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BizField(amount, { amount = it }, stringResourceCompat(R.string.amount), keyboard = numberFieldOptions())
                Text(
                    stringResourceCompat(R.string.exp_category),
                    color = g.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                )
                suggestions.take(5).forEach { cat ->
                    Text(
                        (if (category == cat) "● " else "○ ") + cat,
                        color = if (category == cat) g.accent else g.textPrimary,
                        fontWeight = if (category == cat) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { category = cat; customCat = "" }
                            .padding(vertical = 5.dp)
                    )
                }
                BizField(customCat, {
                    customCat = it
                    if (it.isNotBlank()) category = it.trim()
                }, stringResourceCompat(R.string.exp_custom))
                BizField(note, { note = it }, stringResourceCompat(R.string.note))
                // [P6-M47 إصلاح] عرض خطأ الإدخال بدل الصمت
                errRes?.let { res ->
                    Text(
                        stringResourceCompat(res),
                        color = RedDeep, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val v = parseNum(amount)
                if (v > 0) {
                    errRes = null
                    vm.add(v, category, note)
                    onDismiss()
                } else {
                    // [P6-M47 إصلاح] مفتاح قائم يعاد استخدامه — رسالة واضحة داخل الحوار
                    errRes = R.string.err_amount_positive
                }
            }) { Text(stringResourceCompat(R.string.save), color = g.accent, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResourceCompat(R.string.cancel), color = g.textSecondary)
            }
        }
    )
}

// ═══ : وظيفة 32 — بطاقة حد المصروف + الإنذار المبكر (برتقالي ≥80%، أحمر ≥100%) ═══
@Composable
private fun LimitCard(limit: Double, monthTotal: Double, symbol: String, onEdit: () -> Unit) {
    val g = glassColors()
    val st = ExpenseAlert.state(monthTotal, limit)

    // إخفاء صادق: بلا حد محدد لا بطاقة ولا إنذار — صف تعيين صغير فقط
    if (st == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResourceCompat(R.string.exp_limit_hint),
                fontSize = 11.sp, color = g.textSecondary,
                modifier = Modifier.weight(1f)
            )
            ActionPill(stringResourceCompat(R.string.exp_limit_set), Vio) { onEdit() }
        }
        return
    }

    val statusColor = when (st.level) {
        ExpenseAlert.Level.OK -> GreenDeep
        ExpenseAlert.Level.WARN -> Amber
        ExpenseAlert.Level.OVER -> RedDeep
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconChip(Icons.Rounded.TrendingDown, Color.White, statusColor)
                Spacer(Modifier.width(10.dp))
                Text(
                    when (st.level) {
                        ExpenseAlert.Level.OK -> stringResourceCompat(
                            R.string.exp_limit_of,
                            Money.format(monthTotal, symbol), Money.format(limit, symbol), st.pct
                        )
                        ExpenseAlert.Level.WARN -> stringResourceCompat(R.string.exp_limit_warn, st.pct)
                        ExpenseAlert.Level.OVER -> stringResourceCompat(R.string.exp_limit_over, st.pct)
                    },
                    fontWeight = FontWeight.Bold, color = statusColor, fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                ActionPill(stringResourceCompat(R.string.exp_limit_edit), Vio) { onEdit() }
            }
            // صف تنبيه واضح إضافي عند الاقتراب من الحد أو تجاوزه
            if (st.level != ExpenseAlert.Level.OK) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        // أيقونة التنبيه لها وصف TalkBack — كانت null
                        Icons.Rounded.Warning, contentDescription = stringResource(R.string.cd_expense_alert),
                        tint = statusColor, modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResourceCompat(
                            if (st.level == ExpenseAlert.Level.OVER) R.string.exp_limit_over else R.string.exp_limit_warn,
                            st.pct
                        ),
                        color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                    )
                }
            }
            // شريط التقدم بنسبة الاستهلاك الحقيقية من الحد
            val frac = if (monthTotal.isNaN() || limit <= 0.0) 0f
            else ((monthTotal / limit).coerceIn(0.0, 1.0)).toFloat()
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(g.textSecondary.copy(alpha = 0.15f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(frac)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(statusColor)
                )
            }
        }
    }
}

// ═══ : وظيفة 32 — حوار تعيين حد المصروف الشهري (0 = مسح) ═══
@Composable
private fun LimitDialog(current: Double, onSave: (Double) -> Unit, onDismiss: () -> Unit) {
    val g = glassColors()
    var text by remember { mutableStateOf(if (current > 0.0) Money.num(current) else "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = g.surfaceStrong,
        title = { Text(stringResourceCompat(R.string.exp_limit_dialog), color = g.textPrimary) },
        text = {
            Column(Modifier.imePadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BizField(text, { text = it }, stringResourceCompat(R.string.amount), keyboard = numberFieldOptions())
                Text(stringResourceCompat(R.string.exp_limit_hint), fontSize = 11.sp, color = g.textSecondary)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(parseNum(text)) }) {
                Text(stringResourceCompat(R.string.save), color = g.accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResourceCompat(R.string.cancel), color = g.textSecondary)
            }
        }
    )
}
