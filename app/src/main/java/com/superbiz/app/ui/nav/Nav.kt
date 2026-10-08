package com.superbiz.app.ui.nav

import com.superbiz.app.MainActivity
import com.superbiz.app.R
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material.icons.rounded.Lock // [H1-4][v13] شاشة الرفض
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.superbiz.app.ui.screens.statement.AuditLogScreen
import com.superbiz.app.ui.screens.statement.DeliveryHistoryScreen
import com.superbiz.app.ui.screens.statement.SignatureManagerScreen
import com.superbiz.app.ui.screens.statement.StampManagerScreen
import com.superbiz.app.ui.screens.statement.StatementScreen
import com.superbiz.app.ui.screens.statement.VerifyStatementScreen
import com.superbiz.app.ui.screens.statement.StatementSettingsScreen
import com.superbiz.app.ui.screens.statement.TemplateEditorScreen
import com.superbiz.app.ui.screens.statement.TemplateStudioScreen
import com.superbiz.app.core.ErrorCenter
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.screens.AutomationScreen
import com.superbiz.app.ui.screens.ChecksScreen
import com.superbiz.app.ui.screens.DebtsScreen
import com.superbiz.app.ui.screens.ExpensesScreen
import com.superbiz.app.ui.screens.FavoritesScreen
import com.superbiz.app.ui.screens.HomeScreen
import com.superbiz.app.ui.screens.InstallmentsScreen
import com.superbiz.app.ui.screens.InvoicesScreen
import com.superbiz.app.ui.screens.InventoryScreen
import com.superbiz.app.ui.screens.settings.ErrorLogScreen
import com.superbiz.app.ui.screens.settings.SettingsHubScreen
import com.superbiz.app.ui.screens.SearchScreen
import com.superbiz.app.ui.screens.DataHealthScreen
import com.superbiz.app.ui.screens.PosScreen
import com.superbiz.app.ui.screens.ProfileScreen
import com.superbiz.app.ui.screens.ReportsScreen
import com.superbiz.app.ui.screens.ProScreen
import com.superbiz.app.ui.screens.KpiBoardScreen
import com.superbiz.app.ui.theme.Cyan
import com.superbiz.app.ui.theme.VioDeep
import com.superbiz.app.ui.theme.glassColors
import androidx.lifecycle.viewmodel.compose.viewModel
import com.superbiz.app.VMFactory
import com.superbiz.app.vm.AppVM
// [P36-M4-7] VMs الفواتير والمخزون — لضبط حقول البحث الداخلية عند قفزة نتيجة بحث موجّهة
import com.superbiz.app.vm.InvoicesVM
import com.superbiz.app.vm.InventoryVM
import com.superbiz.app.vm.SettingsVM

/**
 * [P30-A] الوجهات المسموح فتحها مباشرة من إشعار أو ويدجت بعد تجاوز القفل —
 * مستخرجة هنا لتُختبر وحدوياً: كل هدف نقرة جديد (AutomationLogic.routeIntent /
 * VisitReminder / الويدجت) يجب أن يكون عضواً هنا وإلا تجاهله التنقل بصمت.
 * الثوابت كلها const فتُضمَّن زمن الترجمة بلا أي قلق ترتيب تهيئة.
 */
val StartRouteWhitelist: Set<String> = setOf(
    Routes.HOME, Routes.POS, Routes.DEBTS, Routes.INVOICES,
    Routes.INVENTORY, Routes.REPORTS, Routes.AUTO, Routes.SETTINGS,
    Routes.CHECKS, Routes.INSTALLMENTS, Routes.EXPENSES,
    // [P15-c] المفضّلات وجهة وصول عميق مشروعة — إشعار تذكير الزيارات يفتحها مباشرة
    Routes.FAVORITES,
    // [P30-A] مركز الإعدادات — نقرة إشعار فشل النسخ الاحتياطي تفتحه مباشرة
    Routes.SETTINGS_HUB,
    // [تدقيق H-4] شاشة التحقق من الكشف — كانت الوجهة مسجّلة وفلتر superbiz://verify
    // في الـmanifest يعمل، لكن هذه القائمة أسقطت المسار فكانت حلقة QR ميتة من الطرف
    // للطرف (يُستهلك المسار بلا تنقل). العضوية تُكتتب بالمسار الأساسي —
    // لاحقة الاستعلام تُطبَّع عند الفحص في isStartRouteAllowed أدناه.
    Routes.STATEMENT_VERIFY,
    // [H3-6] المنسّق الذكي — وجهة إجراء مشروعة لتنبيهات EWMA المستقبلية
    Routes.SMART
)

/**
 * [تدقيق H-4] بوابة مسار البدء — تطبّع لاحقة الاستعلام ("statement_verify?vid=…"
 * من رابط التحقق → "statement_verify") قبل فحص العضوية، فتصلح للمسارين:
 * الثوابت العادية من الإشعارات/الويدجت والروابط العميقة المعامَلة من MainActivity.
 * القائمة نفسها هي حاجز الأمان (المسارات الحسّاسة ليست أعضاء) — التطبيع لا يوسّعها
 * لوجهة واحدة غير معلنة فيها.
 */
fun isStartRouteAllowed(route: String): Boolean =
    route.substringBefore('?') in StartRouteWhitelist

object Routes {
    const val HOME = "home"
    const val POS = "pos"
    const val DEBTS = "debts"
    const val INVOICES = "invoices"
    const val INVENTORY = "inventory"
    const val REPORTS = "reports"
    const val AUTO = "auto"
    const val SETTINGS = "settings"
    const val CHECKS = "checks"
    const val INSTALLMENTS = "installments"
    const val EXPENSES = "expenses"
    // [H1-5][v13] إدارة المستخدمين والأدوار — باب المالك وحده
    const val USERS = "users"
    // مركز الإعدادات المركزي + سجل الأخطاء
    const val SETTINGS_HUB = "settings_hub"
    const val ERROR_LOG = "error_log"
    // البحث الشامل + فحص صحة البيانات
    const val SEARCH = "search"
    const val DATA_HEALTH = "data_health"
    // [P11-a]: المفضّلات مع بطاقة الموقع الجغرافي — الدخول من رقاقة الذمم
    const val FAVORITES = "favorites"
    // [P17-c]: منظومة كشف الحساب — المسارات السبعة الجديدة
    // STATEMENT نمط مع معامل — يُفتح من PartyStatementSheet عبر "statement/$partyId"
    const val STATEMENT = "statement/{partyId}"
    // القاعدتان التاليتان بلا معاملات في الثابت: الشاشات تلحق "?select=.." و"/<id>" مباشرة
    const val STATEMENT_TEMPLATES = "statement_templates"
    const val STATEMENT_TEMPLATE_EDIT = "statement_template_edit"
    const val SIGNATURES = "signatures"
    const val STAMPS = "stamps"
    // [P18-settings]: إعدادات منظومة الكشف + المساران الناقصان لشاشتي 18-c
    // (سجل التسليمات بمعامل اختياري statementId — 0 يعني القائمة العامة — وسجل التدقيق)
    const val STATEMENT_SETTINGS = "statement_settings"
    const val STATEMENT_DELIVERIES = "statement_deliveries"
    const val STATEMENT_AUDIT = "statement_audit"
    // [P19] شاشة التحقق من الكشف عبر رقم التحقق أو QR deep-link (superbiz://verify/<id>)
    const val STATEMENT_VERIFY = "statement_verify"

    // [W1] الفريميوم: شاشة Pro + لوحة المؤشرات (الباب المدفوع الأول)
    const val PRO = "pro"
    const val KPI_BOARD = "kpi_board"
    // [H3-6] المنسّق الذكي — دردشة محلية والتنبؤ على الجهاز
    const val SMART = "smart"
}

private data class DockItem(val route: String, val icon: ImageVector, val labelRes: Int)

private val dockItems = listOf(
    DockItem(Routes.HOME, Icons.Rounded.Home, com.superbiz.app.R.string.nav_home),
    DockItem(Routes.DEBTS, Icons.Rounded.People, com.superbiz.app.R.string.nav_debts),
    DockItem(Routes.INVOICES, Icons.AutoMirrored.Rounded.ReceiptLong, com.superbiz.app.R.string.nav_invoices),
    DockItem(Routes.INVENTORY, Icons.Rounded.Widgets, com.superbiz.app.R.string.nav_inventory),
    DockItem(Routes.REPORTS, Icons.Rounded.Insights, com.superbiz.app.R.string.nav_reports)
)

/** خلفية Aurora: كتل ضوء بنفسجية وسماوية كالتصميم المرجعي */
@Composable
fun AuroraBackground(content: @Composable () -> Unit) {
    val g = glassColors()
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(g.bgGradient))
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(320.dp)
        ) {
            Box(
                Modifier.size(280.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(g.accent.copy(alpha = if (g.isLight) 0.14f else 0.28f), Color.Transparent)
                        )
                    )
            )
            Box(
                Modifier.align(Alignment.BottomEnd)
                    .size(220.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(g.accent2.copy(alpha = if (g.isLight) 0.12f else 0.22f), Color.Transparent)
                        )
                    )
            )
        }
        content()
    }
}

@Composable
fun SuperBizRoot(
    appVM: AppVM,
    settingsVM: SettingsVM,
    // [H1-4][H1-5][v13] جلسات المستخدمين — الجلسة صدرت من شاشة القفل،
    // والمسارات المحمية تقرأها لحظة التركيب عبر SessionState.effective()
    usersVM: com.superbiz.app.vm.UsersVM,
    startRoute: String? = null,
    onRouteConsumed: () -> Unit = {}
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: Routes.HOME
    // [P36-M4-7] destination.route يعيد نمط المسار كاملاً بما فيه معاملات الاستعلام الاختيارية
    // (مثال: "invoices?focusInvoice={focusInvoice}") — بعد إضافة معاملات التركيز لنتائج البحث
    // نقارن بالمسار الأساسي قبل "؟" حتى يبقى الدوك العائم وتمييز التبويب الحالي يعملان على
    // كل الوجهات. المسارات القائمة بلا معاملات لا يتغير تطبيعها إطلاقاً (substringBefore
    // هويةٌ حين لا يوجد "؟") — تصرف قديم غير ملموس.
    val baseRoute = route.substringBefore('?')
    val g = glassColors()
    val settings by appVM.settings.collectAsState()

    // مسار وارد من ويدجت الذمم — يُفتح مرة واحدة بعد تجاوز القفل
    LaunchedEffect(startRoute) {
        if (startRoute != null) {
            // [P30-A]: القائمة البيضاء مُخرَّجة إلى StartRouteWhitelist لتُختبر وحدوياً
            // [تدقيق H-4]: الفحص عبر isStartRouteAllowed (تطبيع لاحقة الاستعلام) —
            // كان الفحص بالمطابقة الحرفية فيُسقط "statement_verify?vid=…" القادم من
            // رابط superbiz://verify ويُستهلك بلا تنقل (حلقة QR ميتة).
            if (isStartRouteAllowed(startRoute)) nav.navigate(startRoute) { launchSingleTop = true }
            onRouteConsumed()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AuroraBackground {
            NavHost(
                navController = nav,
                startDestination = Routes.HOME,
                // edge-to-edge (targetSdk 35) — محتوى أسفل شريط الحالة بشفافية
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.statusBars
                ),
                enterTransition = { fadeIn(tween(180)) },
                exitTransition = { fadeOut(tween(140)) },
                popEnterTransition = { fadeIn(tween(180)) },
                popExitTransition = { fadeOut(tween(140)) }
            ) {
                composable(Routes.HOME) { HomeScreen(appVM, settingsVM, nav) }
                // [H1-4][v13] المسارات المحمية بأدوارها — البوابة في التركيب وVM الداخل
                // (عقد التصميم §3: الحسم في VM، والإخفاء هنا عونٌ لا ضمانة)
                composable(Routes.POS) { RbacGated(com.superbiz.app.domain.rbac.Op.POS_SELL) { PosScreen(appVM, nav) } }
                composable(Routes.DEBTS) { DebtsScreen(appVM, nav) }
                // [P36-M4-7] تعميق تنقل نتائج البحث (M4-7) — الفواتير تقبل معامِل تركيز اختيارياً
                // focusInvoice = رقم الفاتورة: يُضبط حقل البحث الداخلي للشاشة (vm.searchQuery)
                // فتُرشَّح القائمة إلى الفاتورة المستهدفة لحظة الوصول. غياب المعامِل (كل
                // المستدعين القائمين + الويدجت/الإشعارات عبر EXTRA_ROUTE) = السلوك القائم
                // حرفياً — معامِل اختياري بقيمة افتراضية "" على نمط STATEMENT_DELIVERIES.
                // قيد موثّق: محرر الفاتورة إنشاء-فقط في InvoicesVM (editorInvoiceId لا يدعم
                // تعديل فاتورة قائمة بعدُ) فالوصول للسجل يكون بترشيح القائمة إليه لا بمحرر موجّه.
                composable(
                    Routes.INVOICES + "?focusInvoice={focusInvoice}",
                    arguments = listOf(navArgument("focusInvoice") { type = NavType.StringType; defaultValue = "" })
                ) { entry ->
                    val focus = entry.arguments?.getString("focusInvoice").orEmpty().trim()
                    val activity = LocalContext.current as? MainActivity
                    if (activity != null && focus.isNotEmpty()) {
                        // نفس مفتاح viewModel الافتراضي الذي تحلّ به InvoicesScreen داخل هذه
                        // الوجهة (LocalViewModelStoreOwner = NavBackStackEntry) → النسخة نفسها؛
                        // يُنشأ هنا فقط عند وجود تركيز حتى لا يتغير مسار الإنشاء في بقية الحالات
                        val vm: InvoicesVM = viewModel(factory = remember { VMFactory(activity) })
                        LaunchedEffect(focus) { vm.searchQuery.value = focus }
                    }
                    InvoicesScreen(appVM, nav)
                }
                // [P36-M4-7] المخزون بالنمط نفسه — focusProduct = اسم المنتج: يُضبط vm.search
                // مسبقاً فتُرشَّح القائمة إلى المنتج، وبطاقته تفتح ProductEditor للتعديل بلمسة.
                // غياب المعامِل = السلوك القائم تماماً (بلا إنشاء مبكر للـVM).
                composable(
                    Routes.INVENTORY + "?focusProduct={focusProduct}",
                    arguments = listOf(navArgument("focusProduct") { type = NavType.StringType; defaultValue = "" })
                ) { entry ->
                    val focus = entry.arguments?.getString("focusProduct").orEmpty().trim()
                    val activity = LocalContext.current as? MainActivity
                    if (activity != null && focus.isNotEmpty()) {
                        val vm: InventoryVM = viewModel(factory = remember { VMFactory(activity) })
                        LaunchedEffect(focus) { vm.search.value = focus }
                    }
                    InventoryScreen(appVM, nav)
                }
                composable(Routes.REPORTS) { RbacGated(com.superbiz.app.domain.rbac.Op.FINANCIAL_REPORTS) { ReportsScreen(appVM, nav) } }
                composable(Routes.AUTO) { RbacGated(com.superbiz.app.domain.rbac.Op.GENERAL_SETTINGS) { AutomationScreen(appVM, settingsVM, nav) } }
                composable(Routes.SETTINGS) { ProfileScreen(appVM, settingsVM, nav) }
                composable(Routes.CHECKS) { RbacGated(com.superbiz.app.domain.rbac.Op.EXPENSES_CHECKS_INSTALLMENTS) { ChecksScreen(appVM, nav) } }
                composable(Routes.INSTALLMENTS) { RbacGated(com.superbiz.app.domain.rbac.Op.EXPENSES_CHECKS_INSTALLMENTS) { InstallmentsScreen(appVM, nav) } }
                composable(Routes.EXPENSES) { RbacGated(com.superbiz.app.domain.rbac.Op.EXPENSES_CHECKS_INSTALLMENTS) { ExpensesScreen(appVM, nav) } }
                // [H1-5][v13] إدارة المستخدمين والأدوار — باب المالك وحده
                composable(Routes.USERS) {
                    RbacGated(com.superbiz.app.domain.rbac.Op.USERS_MANAGE) {
                        com.superbiz.app.ui.screens.UsersScreen(
                            usersVM = usersVM,
                            onBack = { nav.popBackStack() }
                        )
                    }
                }
                // إصلاح CRITICAL — كانت كتلتا SETTINGS_HUB/ERROR_LOG
                // مكررتين هنا (نسختان متطابقتان من رقعة وكيل سابق) وNavController يرمي
                // IllegalArgumentException("Duplicate destination") فور الإقلاع → انهيار
                // التطبيق قبل أي شاشة. صار لكل مسار تسجيل واحد فقط + مساران جديدان.
                composable(Routes.SETTINGS_HUB) {
                    SettingsHubScreen(
                        settingsVM = settingsVM,
                        onOpenErrorLog = { nav.navigate(Routes.ERROR_LOG) },
                        onOpenHealth = { nav.navigate(Routes.DATA_HEALTH) },
                        // [P17-c] مدخلا إدارة التواقيع والأختام من قسم البيانات
                        onOpenSignatures = { nav.navigate(Routes.SIGNATURES) },
                        onOpenStamps = { nav.navigate(Routes.STAMPS) },
                        // [W1] قسم Pro — شاشة الترقية
                        onOpenPro = { nav.navigate(Routes.PRO) { launchSingleTop = true } },
                        // [H1-5][v13] إدارة المستخدمين والأدوار — باب المالك وحده
                        onOpenUsers = { nav.navigate(Routes.USERS) { launchSingleTop = true } },
                        onBack = { nav.popBackStack() }
                    )
                }
                composable(Routes.ERROR_LOG) {
                    ErrorLogScreen(onBack = { nav.popBackStack() })
                }
                // البحث الشامل + فحص صحة البيانات
                composable(Routes.SEARCH) {
                    SearchScreen(appVM, nav, onBack = { nav.popBackStack() })
                }
                composable(Routes.DATA_HEALTH) {
                    DataHealthScreen(onBack = { nav.popBackStack() })
                }
                // [P11-a]: شاشة المفضّلات — VM عبر المصنع الافتراضي (AndroidViewModel(Application)
                // يدعمه، كما تفعل بطاقات Insights مع AppVM بـ viewModel(key=…)) لأن VMFactory
                // في MainActivity خارج ملكية هذه الموجة
                composable(Routes.FAVORITES) {
                    FavoritesScreen(
                        vm = viewModel(),
                        onBack = { nav.popBackStack() }
                    )
                }
                // [P17-c] كشف الحساب الكامل — partyId من المسار (Long)
                composable(
                    Routes.STATEMENT,
                    arguments = listOf(navArgument("partyId") { type = NavType.LongType })
                ) { entry ->
                    StatementScreen(
                        appVM = appVM,
                        partyId = entry.arguments?.getLong("partyId") ?: 0L,
                        nav = nav
                    )
                }
                // [P17-c] استوديو القوالب — select=1 وضع الاختيار لصالح شاشة الكشف
                composable(
                    Routes.STATEMENT_TEMPLATES + "?select={select}",
                    arguments = listOf(
                        navArgument("select") { type = NavType.StringType; defaultValue = "0" }
                    )
                ) { entry ->
                    TemplateStudioScreen(
                        appVM = appVM,
                        nav = nav,
                        selectMode = entry.arguments?.getString("select") == "1",
                        onOpenSettings = { nav.navigate(Routes.STATEMENT_SETTINGS) { launchSingleTop = true } } // [P18-settings]
                    )
                }
                // [P17-c] محرر قالب مخصص — templateId نصي ("COR-01" أو "CUSTOM-3")
                composable(
                    Routes.STATEMENT_TEMPLATE_EDIT + "/{templateId}",
                    arguments = listOf(
                        navArgument("templateId") { type = NavType.StringType }
                    )
                ) { entry ->
                    TemplateEditorScreen(
                        appVM = appVM,
                        templateId = entry.arguments?.getString("templateId").orEmpty(),
                        nav = nav
                    )
                }
                // [P17-c] إدارة التواقيع والأختام
                composable(Routes.SIGNATURES) { SignatureManagerScreen(appVM, nav) }
                composable(Routes.STAMPS) { StampManagerScreen(appVM, nav) }
                // [P18-c → P18-settings] سجل تسليم الكشوف — statementId اختياري (0 = القائمة العامة)
                composable(
                    Routes.STATEMENT_DELIVERIES + "?statementId={statementId}",
                    arguments = listOf(
                        navArgument("statementId") { type = NavType.LongType; defaultValue = 0L }
                    )
                ) { entry ->
                    DeliveryHistoryScreen(
                        statementId = entry.arguments?.getLong("statementId") ?: 0L,
                        nav = nav
                    )
                }
                // [P18-c → P18-settings] سجل التدقيق
                composable(Routes.STATEMENT_AUDIT) { AuditLogScreen(nav) }
                // [P18-settings] إعدادات منظومة الكشف
                composable(Routes.STATEMENT_SETTINGS) {
                    StatementSettingsScreen(appVM = appVM, nav = nav)
                }
                // [P19] التحقق من الكشف — vid اختياري (فارغ = إدخال يدوي، من QR يأتي معبأ)
                composable(
                    Routes.STATEMENT_VERIFY + "?vid={vid}",
                    arguments = listOf(navArgument("vid") { type = NavType.StringType; defaultValue = "" })
                ) { entry ->
                    VerifyStatementScreen(
                        initialVid = entry.arguments?.getString("vid").orEmpty(),
                        nav = nav
                    )
                }
                // [W1] شاشة SuperBiz Pro — شراء لمرة واحدة بلا خادم عبر Play Billing v7
                composable(Routes.PRO) {
                    ProScreen(onBack = { nav.popBackStack() })
                }
                // [W1] لوحة المؤشرات — الباب المدفوع الأول (الحديفة تقود إلى شاشة Pro)
                // [H1-4][v13] — ومؤهلات الأرباح: بلا الكاشير (مصفوفة §3 سطر 7)
                composable(Routes.KPI_BOARD) {
                    RbacGated(com.superbiz.app.domain.rbac.Op.FINANCIAL_REPORTS) {
                        KpiBoardScreen(
                            appVM = appVM,
                            onBack = { nav.popBackStack() },
                            openPro = { nav.navigate(Routes.PRO) { launchSingleTop = true } }
                        )
                    }
                }
                // [H3-6] المنسّق الذكي — دردشة مالية محلية (بوابة RBAC المالية + باب Pro داخلياً)
                composable(Routes.SMART) {
                    RbacGated(com.superbiz.app.domain.rbac.Op.FINANCIAL_REPORTS) {
                        com.superbiz.app.ui.screens.SmartScreen(
                            onBack = { nav.popBackStack() },
                            openPro = { nav.navigate(Routes.PRO) { launchSingleTop = true } }
                        )
                    }
                }
            }
        }

        // Dock العائم — يخفى في الإعدادات والمركز الآلي (شاشات داخلية)
        // [P36-M4-7] المقارنة على baseRoute (بعد "؟") — انظر التعليق عند تعريفه
        // [H1-4][v13] عناصر الدوك تُرشَّح بدور الجلسة الفعالة (وضع المالك الضمني
        // يرى الكل كما كان — التعدد فقط يقصّ التبويبات المحرَّمة على صاحب الدور)
        val dockForRole = dockItems.filter {
            when (it.route) {
                Routes.REPORTS -> com.superbiz.app.domain.rbac.SessionState.effective()
                    .can(com.superbiz.app.domain.rbac.Op.FINANCIAL_REPORTS)
                Routes.INVENTORY -> com.superbiz.app.domain.rbac.SessionState.effective()
                    .can(com.superbiz.app.domain.rbac.Op.INVENTORY_READ)
                else -> true
            }
        }
        val showDock = baseRoute in dockForRole.map { it.route }
        if (showDock) {
            FloatingDock(
                nav = nav, current = baseRoute,
                items = dockForRole,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 10.dp)
            )
        }

        // مضيف Snackbar لمركز الأخطاء الموحّد — كل خطأ مُبلَّغ عنه برسالة
        // مفهومة يظهر إشعاراً قصيراً أسفل الشاشة (كانت الأخطاء تُبتلع بلا أي تغذية راجعة)
        val errorHost = remember { SnackbarHostState() }
        LaunchedEffect(Unit) {
            // [P31-A]: الـSnackbar يعرض userMessage الموطّن من الموارد فقط — كانت الرسائل
            // التقنية العربية تُبثّ للجميع عبر كشف الأحرف العربية بينما تُكتم الإنجليزية
            // (تمييز لغوي عكسي). الآن: الرسالة التقنية في السجل التشخيصي فقط ورسالة
            // المستخدم تأتي من Resources بلغة التطبيق.
            ErrorCenter.events.collect { ev ->
                val msg = ev.userMessage
                if (msg != null) errorHost.showSnackbar(msg.take(120), duration = SnackbarDuration.Short)
            }
        }
        SnackbarHost(
            errorHost,
            // [P20-FIX agent7]: كان يتراكب فوق شريط الأيقونات العائم على شاشات الـdock فيغطيه ويلتقط نقراته
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(bottom = if (showDock) 92.dp else 0.dp)
        )
    }
}

/** شريط الترويسة العلوي: تحية + صورة المستخدم + رقائق (إعدادات/ثيم/تنبيه/إرسال) */
@Composable
fun AppHeader(
    appVM: AppVM,
    settingsVM: SettingsVM,
    nav: NavHostController
) {
    val g = glassColors()
    val context = LocalContext.current
    val settings by appVM.settings.collectAsState()
    val ar = settings.language == "ar"
    // شارة تنبيه حقيقية — كانت "1" مزيّفة دائمة؛ الآن عدّاد الشيكات المستحقة قريباً
    val homeData by appVM.home.collectAsState()
    val dueSoon = homeData.checksDueSoon

    GlassCard(corner = 26.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // تحية + التاريخ (جهة البداية)
            Column(Modifier.weight(1f)) {
                Text(
                    // [P30-E]: بلا إيموجي — يُعرض متسقاً على كل الأجهزة
                    appVM.greeting(ar),
                    style = MaterialTheme.typography.titleSmall,
                    color = g.textPrimary
                )
                Text(
                    com.superbiz.app.util.Dates.dayMonth(System.currentTimeMillis(), ar),
                    style = MaterialTheme.typography.bodySmall,
                    color = g.textSecondary, maxLines = 1
                )
            }
            // صورة المستخدم بحلقة متدرجة — النقر يفتح الملف الشخصي المتكامل
            // [P20-FIX agent7]: launchSingleTop — نفس صنف R10-C19 كان مفقوداً هنا
            Box(Modifier.clickable { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } }) {
                AvatarView(appVM, size = 46.dp, modifier = Modifier.padding(horizontal = 6.dp))
            }
            Spacer(Modifier.width(4.dp))
            // الرقائق
            // launchSingleTop — كان النقر المزدوج يدفع نسختين من الإعدادات
            // فيصبح «رجوع» مكرراً بلا فائدة
            HeaderChip(Icons.Rounded.Settings, onClick = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } })
            HeaderChip(
                if (settings.theme == "light") Icons.Rounded.DarkMode else Icons.Rounded.WbSunny,
                onClick = {
                    settingsVM.setTheme(
                        when (settings.theme) { "dark" -> "light"; "light" -> "auto"; else -> "dark" }
                    )
                }
            )
            HeaderChip(
                Icons.Rounded.Notifications,
                badge = if (dueSoon > 0) dueSoon.toString() else null,
                onClick = { nav.navigate(Routes.AUTO) { launchSingleTop = true } } 
            )
            // [P34-M4-3 إصلاح] كانت رقاقة الإرسال تكرّر هدف رقاقة الإشعارات حرفياً
            // (كلاهما Routes.AUTO) فصار هدفها المركز المخصص للإرسال: إعدادات منظومة
            // الكشف (SMTP + تجربة إرسال + سجل التسليمات) — وأُضيف وصف وصول من الموارد
            // (كانت null — بند M4-2 للأيقونات الأيقونية-فقط)
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(VioDeep, Cyan)))
                    .clickable { nav.navigate(Routes.STATEMENT_SETTINGS) { launchSingleTop = true } },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Send,
                    androidx.compose.ui.res.stringResource(R.string.st3_settings_title),
                    tint = Color.White, modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun AvatarView(appVM: AppVM, size: Dp = 46.dp, modifier: Modifier = Modifier) {
    val g = glassColors()
    val bmp = appVM.avatarBitmap()
    Box(
        modifier
            .size(size + 4.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(VioDeep, Cyan)))
            .padding(2.dp)
            .clip(CircleShape)
            .background(g.surfaceStrong),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            androidx.compose.foundation.Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = stringResource(R.string.a11y_avatar_photo), // [P39-M4-2]
                modifier = Modifier.fillMaxSize().clip(CircleShape),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        } else {
            Icon(
                Icons.Rounded.People, null,
                tint = g.textSecondary,
                modifier = Modifier.size(size * 0.5f)
            )
        }
    }
}

@Composable
private fun HeaderChip(
    icon: ImageVector,
    badge: String? = null,
    onClick: () -> Unit
) {
    val g = glassColors()
    Box(Modifier.padding(horizontal = 3.dp)) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(g.surface)
                .border(1.dp, g.border, CircleShape)
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = g.textPrimary, modifier = Modifier.size(19.dp))
        }
        if (badge != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFEF4444)),
                contentAlignment = Alignment.Center
            ) {
                Text(badge, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** الدوك العائم الزجاجي */
@Composable
private fun FloatingDock(
    nav: NavHostController, current: String,
    // [H1-4][v13] عناصر الدوك مفلترة بالدور من المستدعي — الكاشير لا يرى تبويب التقارير
    // والمحاسب لا يرى المخزون (مصفوفة §3)، والقيمة الافتراضية تحفظ الاستدعاء القائم
    items: List<DockItem> = dockItems,
    modifier: Modifier = Modifier
) {
    val g = glassColors()
    GlassCard(corner = 26.dp, modifier = modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { item ->
                val selected = item.route == current
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable {
                            // نمط التنقّل القياسي — popUpTo مع حفظ/استعادة حالة كل تبويب
                            // كان الرجوع يدور بين التبويبات المزارة والسطح يُعاد بناؤه في كل مرة
                            nav.navigate(item.route) {
                                launchSingleTop = true
                                popUpTo(Routes.HOME) { saveState = true }
                                restoreState = true
                            }
                        }
                        .padding(vertical = 4.dp)
                ) {
                    Box(
                        Modifier
                            .size(if (selected) 46.dp else 40.dp)
                            .clip(RoundedCornerShape(if (selected) 16.dp else 999.dp))
                            .background(
                                when {
                                    selected -> Brush.linearGradient(listOf(VioDeep, g.accent))
                                    else -> Brush.linearGradient(listOf(g.surface, g.surface))
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            item.icon, null,
                            tint = if (selected) Color.White else g.textSecondary,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        androidx.compose.ui.res.stringResource(item.labelRes),
                        fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) g.textPrimary else g.textSecondary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// [H1-4][v13] بوابة الدور في التركيب — عونٌ للإخفاء لا ضمانة الحسم
// (عقد التصميم §3: الحسم في RoleGate داخل VMs؛ هذه البوابة تعطّل الوصول
// المباشر للمسار وتعرض شاشة رفض موطّنة بدل محتوى لا يخصّ صاحب الدور)
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun RbacGated(op: com.superbiz.app.domain.rbac.Op, content: @Composable () -> Unit) {
    if (com.superbiz.app.domain.rbac.SessionState.effective().can(op)) content()
    else RbacDeniedScreen()
}

/** شاشة الرفض — نفس الهوية البصرية، بلا أي بيانات تخصّ عمليات محرَمة */
@Composable
private fun RbacDeniedScreen() {
    val g = glassColors()
    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        GlassCard(corner = 24.dp) {
            Column(
                Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Rounded.Lock, null,
                    tint = g.textSecondary,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(com.superbiz.app.R.string.rbac_denied_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = g.textPrimary
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(com.superbiz.app.R.string.rbac_denied_msg),
                    style = MaterialTheme.typography.bodySmall,
                    color = g.textSecondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}
