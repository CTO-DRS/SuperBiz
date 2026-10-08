package com.superbiz.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.CallMade
import androidx.compose.material.icons.rounded.MoveToInbox
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PointOfSale
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.superbiz.app.MainActivity
import com.superbiz.app.R
import com.superbiz.app.data.db.Party
import com.superbiz.app.ui.components.BizField
import com.superbiz.app.ui.components.BizPill
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.components.IconChip
import com.superbiz.app.ui.components.KpiCard
import com.superbiz.app.ui.components.DualBarChart
import com.superbiz.app.ui.components.PillMode
import com.superbiz.app.ui.components.QuickAction
import com.superbiz.app.ui.components.SectionTitle
import com.superbiz.app.ui.components.numberFieldOptions
import com.superbiz.app.ui.components.parseNum
import com.superbiz.app.ui.nav.AppHeader
import com.superbiz.app.ui.nav.Routes
import com.superbiz.app.ui.theme.Amber
import com.superbiz.app.ui.theme.Blue
import com.superbiz.app.ui.theme.Cyan
import com.superbiz.app.ui.theme.Green
import com.superbiz.app.ui.theme.GreenDeep
import com.superbiz.app.ui.theme.Pink
import com.superbiz.app.ui.theme.Red
import com.superbiz.app.ui.theme.RedDeep
import com.superbiz.app.ui.theme.Vio
import com.superbiz.app.ui.theme.VioDeep
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.util.Money
import com.superbiz.app.vm.AppVM
import com.superbiz.app.vm.DebtsVM
import com.superbiz.app.vm.InvoicesVM
import com.superbiz.app.VMFactory

@Composable
fun HomeScreen(appVM: AppVM, settingsVM: com.superbiz.app.vm.SettingsVM, nav: NavHostController) {
    val activity = androidx.compose.ui.platform.LocalContext.current as? MainActivity ?: return
    val factory = remember { VMFactory(activity) }

    val home by appVM.home.collectAsState()
    val symbol by appVM.symbol.collectAsState()
    val g = glassColors()

    // وظيفة 38 — وضع الخصوصية: طمس المبالغ الحساسة في بطاقات الرئيسية كـ «•••»
    // مع نقرة طويلة تكشفها مؤقتاً (لحظة ثم تعود). الأسلم: الطمس هنا مباشرة من flow الإعدادات
    // (settingsVM) دون تمديد مسار AppVM.home — لا تكسر شيئاً من مسار الحالة القائم.
    val hubSettings by settingsVM.settings.collectAsState()
    val privacyOn = hubSettings.privacyBlur
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(revealed) {
        if (revealed) {
            kotlinx.coroutines.delay(2500)
            revealed = false
        }
    }
    val maskAmounts = privacyOn && !revealed
    fun amt(v: Double): String = if (maskAmounts) "•••" else Money.format(v, symbol)

    // حوارات الإجراءات السريعة
    var showCashIn by remember { mutableStateOf(false) }
    var showDebt by remember { mutableStateOf(false) }

    val debtsVM: DebtsVM = viewModel(factory = factory)
    // الرؤى الذكية للرئيسية
    // [P5-H9 إصلاح]: VMs الرؤى مشتركة على مستوى النشاط — كانت كل شاشة تنشئ نسختها وتشغل loadAll كاملاً (حتى ×8 تكلفة لكل جولة تنقل)
    val smartVM: com.superbiz.app.vm.SmartInsightsVM = viewModel(viewModelStoreOwner = activity, factory = factory)
    // رؤى الموجة R9 للرئيسية
    val r9VM: com.superbiz.app.vm.R9InsightsVM = viewModel(viewModelStoreOwner = activity, factory = factory)
    // رؤى الموجة R10 للرئيسية
    val r10VM: com.superbiz.app.vm.R10InsightsVM = viewModel(viewModelStoreOwner = activity, factory = factory)
    // رؤى الموجة R11 للرئيسية
    val r11VM: com.superbiz.app.vm.R11InsightsVM = viewModel(viewModelStoreOwner = activity, factory = factory)
    // VM الرؤى الذكية للموجة R12 — عبر R12Smart
    val r12VM: com.superbiz.app.vm.R12InsightsVM = viewModel(viewModelStoreOwner = activity, factory = factory)
    val r13VM: com.superbiz.app.vm.R13InsightsVM = viewModel(viewModelStoreOwner = activity, factory = factory)
    // VM الرؤى الذكية للموجة R14 — عبر R14Smart
    val r14VM: com.superbiz.app.vm.R14InsightsVM = viewModel(viewModelStoreOwner = activity, factory = factory)
    val r15VM: com.superbiz.app.vm.R15InsightsVM = viewModel(viewModelStoreOwner = activity, factory = factory)
    val r16VM: com.superbiz.app.vm.SmartCoordinatorVM = viewModel(viewModelStoreOwner = activity, factory = factory)
    // [P6-M42 إصلاح] زر «فاتورة» كان ميت الوظيفة: كان يفتح المحرر على نسخة InvoicesVM معلّقة
    // بمدخل HOME بينما InvoicesScreen تنشئ نسختها الخاصة بمدخل INVOICES — فلا يُفتح المحرر أبداً.
    // الحل ضمن ملكية هذا الملف (بلا لمس Nav.kt أو InvoicesScreen/FeatureVMs): نسخة على مستوى
    // النشاط (نمط P5-H9 نفسه) يُفتح عليها محرر الفاتورة ويُركَّب فوق الرئيسية مباشرة —
    // الحفظ يمر عبر المسار الحقيقي InvoicesVM.saveInvoice فتظهر الفاتورة في قائمة الفواتير
    val invoicesVM: InvoicesVM = viewModel(viewModelStoreOwner = activity, factory = factory)
    val homeEditorOpen by invoicesVM.editorOpen.collectAsState()

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ══ الترويسة الزجاجية ══
            AppHeader(appVM, settingsVM, nav)

            // ══ [P20-FIX] بطاقات الذكاء نُقلت إلى أسفل الشاشة بطلب المستخدم —
            // كانت تحجب الرصيد والمؤشرات والإجراءات فتشوه المنظر (9 بطاقات فوق كل شيء)

            // ══ : مدخل البحث الشامل — يفتح شاشة البحث الموحّدة ══
            // (منتجات + أطراف + فواتير + شيكات بترتيب ضبابي حقيقي عبر TextMath.fuzzyScore)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(g.surface)
                    .border(1.dp, g.border, RoundedCornerShape(16.dp))
                    .clickable { nav.navigate(Routes.SEARCH) { launchSingleTop = true } }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconChip(Icons.Rounded.Search, Color.White, Blue, size = 34.dp, iconSize = 18.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResourceCompat(R.string.home_search),
                    color = g.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack, null,
                    tint = g.textSecondary, modifier = Modifier.size(14.dp)
                )
            }

            // ══ بطاقة الرصيد المتدرجة ══
            Box {
                GlassCard(corner = 28.dp, modifier = Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(g.heroBrush)
                            .padding(20.dp)
                    ) {
                        // دوائر زخرفية
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
                        Column(
                            Modifier
                                .fillMaxWidth()
                                // وظيفة 38 — نقرة طويلة على البطاقة تكشف المبالغ مؤقتاً
                                .pointerInput(privacyOn) {
                                    detectTapGestures(onLongPress = { if (privacyOn) revealed = true })
                                }
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                IconChip(
                                    Icons.Rounded.AccountBalanceWallet,
                                    Color.White, Color.White.copy(alpha = 0.22f)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                amt(home.ar),
                                fontSize = 30.sp, fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                            Text(
                                stringResourceCompat(R.string.hero_receivable_hint),
                                color = Color.White.copy(alpha = 0.9f),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            // ══ شبكة KPI ‏2×2 ══
            // وظيفة 38 — نقر طويل على الشبكة يكشف المبالغ المطمسة مؤقتاً
            Column(
                Modifier.pointerInput(privacyOn) {
                    detectTapGestures(onLongPress = { if (privacyOn) revealed = true })
                },
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    KpiCard(
                        Icons.Rounded.MoveToInbox, amt(home.ap),
                        stringResourceCompat(R.string.kpi_payable), GreenDeep,
                        modifier = Modifier.weight(1f)
                    )
                    KpiCard(
                        Icons.Rounded.ArrowUpward, amt(home.salesMonth),
                        stringResourceCompat(R.string.kpi_sales), Amber,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    KpiCard(
                        Icons.Rounded.ArrowDownward,
                        amt(home.profitMonth),
                        stringResourceCompat(R.string.kpi_profit),
                        if (home.profitMonth >= 0) GreenDeep else RedDeep,
                        modifier = Modifier.weight(1f)
                    )
                    KpiCard(
                        Icons.Rounded.BarChart, amt(home.expensesMonth),
                        stringResourceCompat(R.string.kpi_expenses), Vio,
                        modifier = Modifier.weight(1f),
                        onClick = { nav.navigate(Routes.EXPENSES) { launchSingleTop = true } }
                    )
                }
            }

            // ══ : وظيفة 39 — درجة الصحة المالية: هامش 40% + سيولة 30% + تحصيل 30% ══
            // null = نقص مدخل (بلا مبيعات/لا بيانات تدفق/لا مبيعات آجلة) → تُخفى البطاقة كلياً (صدق الفراغ)
            home.healthScore?.let { hs ->
                val lvl = com.superbiz.app.domain.HealthScore.levelOf(hs)
                val hc = when (lvl) { 3 -> GreenDeep; 2 -> Cyan; 1 -> Amber; else -> RedDeep }
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconChip(Icons.Rounded.HealthAndSafety, Color.White, hc, size = 34.dp, iconSize = 16.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResourceCompat(R.string.home_health_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = g.textPrimary
                                )
                                Text(
                                    stringResourceCompat(R.string.home_health_of, hs),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = g.textSecondary
                                )
                            }
                            Text(
                                "$hs",
                                fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = hc
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResourceCompat(
                                when (lvl) {
                                    3 -> R.string.home_health_3; 2 -> R.string.home_health_2
                                    1 -> R.string.home_health_1; else -> R.string.home_health_0
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = g.textSecondary
                        )
                    }
                }
            }

            // ══ : تقدم هدف المبيعات الشهري — حقيقي من الإعدادات ومبيعات الشهر الفعلية ══
            if (home.monthlyGoal > 0) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResourceCompat(R.string.goal_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = g.textPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                amt(home.salesMonth) + " / " + amt(home.monthlyGoal),
                                style = MaterialTheme.typography.bodySmall,
                                color = g.accent2, fontWeight = FontWeight.SemiBold
                            )
                        }
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { home.goalProgress.toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(6.dp)),
                            color = if (home.goalProgress >= 1f) GreenDeep else Cyan,
                            trackColor = g.border.copy(alpha = 0.4f)
                        )
                        Text(
                            stringResourceCompat(R.string.goal_progress) + ": " +
                                (home.goalProgress * 100).toInt() + "%",
                            style = MaterialTheme.typography.bodySmall,
                            color = g.textSecondary
                        )
                        // إيقاع الهدف — goalPace يقارن الإنجاز بالجدول اليومي
                        if (home.monthlyGoal > 0) {
                            Text(
                                if (home.goalPace >= 1.0)
                                    stringResourceCompat(R.string.pace_ahead)
                                else
                                    stringResourceCompat(R.string.pace_behind, ((1.0 - home.goalPace) * 100).toInt().toString()),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (home.goalPace >= 1.0) GreenDeep else Amber
                            )
                        }
                    }
                }
            }

            // شريحة سلسلة البيع المتتالية — SeriesMath.streakDays على
            // أيام فواتير البيع الفعلية؛ تظهر بجوار ملخص الهدف/الإيقاع ولا تُعرض إلا عند ≥ يومين
            if (home.salesStreak >= 2) {
                Row(Modifier.fillMaxWidth()) {
                    AlertPill(stringResourceCompat(R.string.home_streak, home.salesStreak), Amber)
                }
            }

            // شرائح تنبيه حقيقية
            if (home.overdue > 0 || home.lowStockCount > 0 || home.checksDueSoon > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // [P30-E]: أيقونات Material بدل بادئات إيموجي — عرض متسق عبر كل الأجهزة
                    if (home.overdue > 0) AlertPill(
                        stringResourceCompat(R.string.kpi_overdue) + ": " + amt(home.overdue), Red,
                        icon = Icons.Rounded.Warning
                    )
                    if (home.checksDueSoon > 0) AlertPill(
                        stringResourceCompat(R.string.kpi_checks_due) + ": ${home.checksDueSoon}", Amber,
                        icon = Icons.Rounded.Receipt
                    )
                    if (home.lowStockCount > 0) AlertPill(
                        stringResourceCompat(R.string.low_stock) + ": ${home.lowStockCount}", Cyan,
                        icon = Icons.Rounded.Inventory2
                    )
                }
            }

            // ══ إجراءات سريعة ══
            SectionTitle(stringResourceCompat(R.string.quick_actions))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                QuickAction(
                    Icons.Rounded.PointOfSale, stringResourceCompat(R.string.pos_title),
                    Brush.linearGradient(listOf(RedDeep, Color(0xFFF97316)))
                ) { nav.navigate(Routes.POS) { launchSingleTop = true } }
                QuickAction(
                    Icons.Rounded.MoveToInbox, stringResourceCompat(R.string.qa_cash_in),
                    Brush.linearGradient(listOf(GreenDeep, Green))
                ) { showCashIn = true }
                QuickAction(
                    Icons.Rounded.CallMade, stringResourceCompat(R.string.qa_cash_out),
                    Brush.linearGradient(listOf(Color(0xFFDB2777), Pink))
                ) { nav.navigate(Routes.EXPENSES) { launchSingleTop = true } }
                QuickAction(
                    Icons.Rounded.Receipt, stringResourceCompat(R.string.qa_invoice),
                    Brush.linearGradient(listOf(Blue, Cyan))
                ) {
                    // [P6-M42 إصلاح] يفتح محرر الفاتورة فعلياً — بلا تنقل: التنقل إلى INVOICES
                    // كان يُخرج الرئيسية من التركيب فيُفقد المحرر المفتوح هنا
                    invoicesVM.openEditor(0)
                }
                QuickAction(
                    Icons.Rounded.PersonAdd, stringResourceCompat(R.string.qa_new_debt),
                    Brush.linearGradient(listOf(VioDeep, Vio))
                ) { showDebt = true }
            }

            // ══ حركة آخر 7 أيام ══
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(
                    stringResourceCompat(R.string.chart_7days),
                    modifier = Modifier.weight(1f)
                )
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { nav.navigate(Routes.REPORTS) { launchSingleTop = true } }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResourceCompat(R.string.nav_reports), color = g.accent2, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack, null,
                        tint = g.accent2, modifier = Modifier.size(14.dp)
                    )
                }
            }
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.padding(16.dp)) {
                    // رصيفة كشف الشذوذ — خوارزمية z-Score على مبيعات آخر 7 أيام
                    // تنبّه التاجر لنشاط غير معتاد (ارتفاع مفاجئ أو انخفاض مقلق) تلقائياً
                    val anomalyZ = com.superbiz.app.domain.analytics.latestAnomalyZ(
                        home.series7.map { it.second }
                    )
                    if (anomalyZ != null && kotlin.math.abs(anomalyZ) >= 2.0) {
                        // كان النص عربياً صلباً في الواجهة حتى بلغة الإنجليزية
                        val txt = if (anomalyZ > 0) stringResourceCompat(R.string.home_anomaly_up)
                                  else stringResourceCompat(R.string.home_anomaly_down)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (anomalyZ > 0) Green.copy(alpha = 0.14f) else g.amber.copy(alpha = 0.16f))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(txt, color = if (anomalyZ > 0) Green else g.amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (home.series7.isNotEmpty()) {
                        DualBarChart(
                            data = home.series7,
                            labelA = stringResourceCompat(R.string.income),
                            labelB = stringResourceCompat(R.string.expense),
                            colorA = Cyan, colorB = Vio
                        )
                    } else {
                        // [P36-M4-10]: كانت «—» نصاً صلباً بلا معنى بوضع الفراغ —
                        // الآن حالة فراغ صادقة بمورد موجود (empty_generic) بدل رمز صامت
                        Text(
                            stringResourceCompat(R.string.empty_generic),
                            color = g.textSecondary,
                            fontSize = 12.sp
                        )
                    }
                    // زر + عائم داخل البطاقة
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(VioDeep, Vio)))
                            .border(3.dp, g.bgBase, CircleShape)
                            .clickable { showCashIn = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Add, null, tint = Color.White)
                    }
                }
            }

            // ══ توقع التحصيل ══
            home.forecast?.let { fc ->
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier
                            .padding(16.dp)
                            // وظيفة 38 — نقرة طويلة تكشف مبلغ التوقع مؤقتاً
                            .pointerInput(privacyOn) {
                                detectTapGestures(onLongPress = { if (privacyOn) revealed = true })
                            }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconChip(Icons.Rounded.Payments, Color.White, Vio, size = 34.dp, iconSize = 16.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResourceCompat(R.string.forecast_next7),
                                style = MaterialTheme.typography.titleSmall,
                                color = g.textPrimary
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                amt(fc.next7),
                                style = MaterialTheme.typography.titleMedium,
                                color = g.accent2
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResourceCompat(R.string.forecast_based) + " • " + (fc.fillRate * 100).toInt() + "%",
                            style = MaterialTheme.typography.bodySmall,
                            color = g.textSecondary
                        )
                    }
                }
            }

            // ══ [P20-FIX] بطاقات الذكاء — أسفل الرئيسية (كانت أعلى الشاشة فتشوه المنظر) ══
            // [P44-K1] جولة 5: المكدس المخصص — ترتيب/إظهار المجموعات الثماني بافتضاض المستخدم (AppPrefs)
            com.superbiz.app.ui.insights.InsightsStack(
                com.superbiz.app.domain.DashboardPrefsP44.SCREEN_HOME,
                listOf(
                    // فجوة نقدية/قفزة مصروفات/محاكي الهدف/تركيب الصحة/عملاء معرضون للفقد
                    com.superbiz.app.ui.insights.insightsGroup("smart") { com.superbiz.app.ui.insights.HomeSmartCard(appVM, smartVM) },
                    // السيولة تحت الضغط/الاحتياطي الموسمي/الاتجاه/الولاء/الاحتفاظ
                    com.superbiz.app.ui.insights.insightsGroup("r9") { com.superbiz.app.ui.insights.HomeR9Card(appVM, r9VM) },
                    // درجة الصحة/تركّز العملاء/أفضل يوم/وتيرة الهدف/شواذ المصروفات
                    com.superbiz.app.ui.insights.insightsGroup("r10") { com.superbiz.app.ui.insights.HomeR10Card(appVM, r10VM) },
                    // أيام الصمود/موقع الضريبة/زخم الإيراد/التعرض للعملات
                    com.superbiz.app.ui.insights.insightsGroup("r11") { com.superbiz.app.ui.insights.HomeR11Card(appVM, r11VM) },
                    // مقارنة الفترات/مزيج التحصيل/استقرار الإيراد/دوران المخزون
                    com.superbiz.app.ui.insights.insightsGroup("r12") { com.superbiz.app.ui.insights.HomeR12Card(appVM, r12VM) },
                    com.superbiz.app.ui.insights.insightsGroup("r13") { com.superbiz.app.ui.insights.HomeR13Card(appVM, r13VM) },
                    // وينسور/هيرست/ك-س/غطاء الالتزامات
                    com.superbiz.app.ui.insights.insightsGroup("r14") { com.superbiz.app.ui.insights.HomeR14Card(appVM, r14VM) },
                    com.superbiz.app.ui.insights.insightsGroup("r15") { com.superbiz.app.ui.insights.HomeR15Card(appVM, r15VM) },
                    // [H3-6] المنسّق الذكي — تنبيهات استباقية + إعادة طلب + نقد 90 يوم + مدخل الدردشة
                    com.superbiz.app.ui.insights.insightsGroup("r16") { com.superbiz.app.ui.insights.HomeR16Card(appVM, r16VM, nav) },
                )
            )
        }

        // الحوارات
        if (showCashIn) {
            AmountDialog(
                title = stringResourceCompat(R.string.qa_cash_in),
                onDismiss = { showCashIn = false }
            ) { amount, note ->
                // العملية من الـ VM — قيد حقيقي ذري وتحديث كامل للشاشة
                appVM.cashIn(amount, note)
                showCashIn = false
            }
        }
        if (showDebt) {
            PartyAmountDialog(
                title = stringResourceCompat(R.string.qa_new_debt),
                debtsVM = debtsVM,
                onDismiss = { showDebt = false }
            ) { party, amount, note ->
                debtsVM.addDebt(party, amount, note)
                appVM.refresh()
                showDebt = false
            }
        }

        // [P6-M42 إصلاح] محرر الفاتورة نفسه (InvoiceEditor العام من InvoicesScreen) يُركَّب
        // فوق الرئيسية حين يفتح — آخر عنصر في الـBox ليظهر فوق كل المحتوى والحوارات
        if (homeEditorOpen) {
            InvoiceEditor(invoicesVM, appVM)
        }
    }
}

/**
 * [P36-M4-1] مفوّض رقيق للذرة الموحدة BizPill (ALERT) — الأيقونة الاختيارية نفسها
 * (P30-E) مدعومة داخل BizPill نفسه، والتنفيذ الوحيد في Common.kt.
 */
@Composable
fun AlertPill(text: String, color: Color, icon: ImageVector? = null) {
    BizPill(text, color, mode = PillMode.ALERT, icon = icon)
}

/** حوار مبلغ + ملاحظة */
@Composable
fun AmountDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (Double, String) -> Unit
) {
    val g = glassColors()
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = g.surfaceStrong,
        title = { Text(title, color = g.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BizField(amount, { amount = it }, stringResourceCompat(R.string.amount), keyboard = numberFieldOptions())
                BizField(note, { note = it }, stringResourceCompat(R.string.note))
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    val v = parseNum(amount)
                    if (v > 0) onConfirm(v, note)
                }
            ) { Text(stringResourceCompat(R.string.save), color = g.accent, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResourceCompat(R.string.cancel), color = g.textSecondary)
            }
        }
    )
}

/** حوار طرف + مبلغ (ل لدين جديد) */
@Composable
fun PartyAmountDialog(
    title: String,
    debtsVM: DebtsVM,
    onDismiss: () -> Unit,
    onConfirm: (Party, Double, String) -> Unit
) {
    val g = glassColors()
    val parties by debtsVM.parties.collectAsState()
    var selected by remember { mutableStateOf<Party?>(null) }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = g.surfaceStrong,
        title = { Text(title, color = g.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (parties.isEmpty() || creating) {
                    BizField(newName, { newName = it }, stringResourceCompat(R.string.name))
                    Text(
                        stringResourceCompat(R.string.add) + " +",
                        color = g.accent2, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
                            if (newName.isNotBlank()) {
                                debtsVM.saveParty(newName, "", 0, "")
                                creating = false
                                newName = ""
                            }
                        }
                    )
                } else {
                    Text(
                        selected?.name ?: stringResourceCompat(R.string.party) + " ▾",
                        color = if (selected != null) g.textPrimary else g.textSecondary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, g.border, RoundedCornerShape(12.dp))
                            .clickable { creating = false }
                            .padding(12.dp)
                    )
                    Column(
                        Modifier
                            .height(160.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        parties.forEach { p ->
                            Text(
                                p.name,
                                color = g.textPrimary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selected = p }
                                    .padding(vertical = 8.dp, horizontal = 4.dp)
                            )
                        }
                        Text(
                            "+ " + stringResourceCompat(R.string.add),
                            color = g.accent2, fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { creating = true }
                                .padding(vertical = 8.dp, horizontal = 4.dp)
                        )
                    }
                }
                BizField(amount, { amount = it }, stringResourceCompat(R.string.amount), keyboard = numberFieldOptions())
                BizField(note, { note = it }, stringResourceCompat(R.string.note))
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    val p = selected
                    val v = parseNum(amount)
                    if (p != null && v > 0) onConfirm(p, v, note)
                }
            ) { Text(stringResourceCompat(R.string.save), color = g.accent, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResourceCompat(R.string.cancel), color = g.textSecondary)
            }
        }
    )
}

/** مساعد آمن لاستخدام الموارد النصية داخل أي ملف */
@Composable
fun stringResourceCompat(id: Int): String = androidx.compose.ui.res.stringResource(id)

@Composable
fun stringResourceCompat(id: Int, vararg args: Any): String =
    androidx.compose.ui.res.stringResource(id, *args)
