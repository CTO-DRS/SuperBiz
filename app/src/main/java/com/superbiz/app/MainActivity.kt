package com.superbiz.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.superbiz.app.ui.nav.SuperBizRoot
import com.superbiz.app.ui.screens.LockScreen
import com.superbiz.app.ui.screens.PermissionsScreen
import com.superbiz.app.ui.screens.WelcomeScreen
import com.superbiz.app.ui.theme.SuperBizTheme
import com.superbiz.app.ui.theme.Cyan
import com.superbiz.app.ui.theme.VioDeep
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.vm.AppVM
import com.superbiz.app.vm.SettingsVM
import kotlinx.coroutines.flow.first

class MainActivity : AppCompatActivity() {

    /** مسار مطلوب فتحه من الويدجت — يُستهلك مرة واحدة بعد تجاوز القفل */
    private val routeEvents = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    companion object {
        /** مفتاح المسار في Intent من ويدجت رصيد الذمم */
        const val EXTRA_ROUTE = "nav_route"

        // [P6-M50 إصلاح] حافظ صريح لمسار الودجة — القيمة nullable ولا يثق autoSaver فيها دائماً؛
        // listSaver يفصل null (مستهلك) عن قيمة حية بأمان عبر الفراغ الذي لا يكون مساراً صالحاً أبداً
        // [V1-B1 إصلاح] معاملتا listSaver كانتا معكوستين — Saveable يجب أن يحقق List<Any> فصار List<String>
        private val pendingRouteSaver = androidx.compose.runtime.saveable.listSaver<String?, String>(
            save = { listOf(it ?: "") },
            restore = { if (it[0] == "") null else it[0] }
        )
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent?.getStringExtra(EXTRA_ROUTE)?.let { routeEvents.value = it }
        // [P19] deep-link التحقق superbiz://verify/<vid> — يُحوَّل إلى مسار النافيغيتن
        intent?.dataString?.let { link -> parseVerifyLink(link)?.let { routeEvents.value = it } }
    }

    /**
     * [P19] تحويل رابط التحقق إلى مسار نافيغيتن — يعيد null لأي رابط آخر
     * (شبكة الأمان: الوجهة الوحيدة المرتبطة هي superbiz://verify). رقم التحقق
     * يُرمَّز بأمان المسار (URLEncoder) كي لا يكسر صيغة النافيغيتن بمحارف خاصة.
     */
    private fun parseVerifyLink(link: String): String? {
        val prefix = "superbiz://verify/"
        if (!link.startsWith(prefix)) return null
        val vid = link.removePrefix(prefix).trim()
        if (vid.isEmpty()) return "statement_verify?vid="
        return "statement_verify?vid=" + java.net.URLEncoder.encode(vid, "UTF-8")
    }

    override fun onResume() {
        super.onResume()
        // إعادة جدولة تحديث الويدجت عند العودة للتطبيق (لقطة طازجة)
        com.superbiz.app.widget.WidgetSync.push(this)
        // إعادة تطبيق حجب اللقطات فوراً حسب الإعداد الحيّ (تشغيل/إيقاف)
        if (com.superbiz.app.core.AppPrefs.flagSecure) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // حجب لقطات الشاشة أصبح إعداداً حقيقياً (الافتراضي مفعّل كالسابق) —
        // يُقرأ من الخزنة الحيّة عند الإقلاع، والتبديل الفوري يُعاد تطبيقه في Compose
        if (com.superbiz.app.core.AppPrefs.flagSecure) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }

        // تفعيل الرسم خلف أشرطة النظام (مطلوب مع targetSdk 35 — إلزامي edge-to-edge)
        enableEdgeToEdge()

        // تطبيق اللغة المحفوظة مبكراً (AppCompat يتكفل بالتخزين على 33+)
        val graph = (application as SuperBizApp).graph
        val deepRoute = intent?.getStringExtra(EXTRA_ROUTE)
            // [P19] deep-link التحقق عند الإطلاق البارد (التطبيق مغلق عند مسح QR)
            ?: intent?.dataString?.let { parseVerifyLink(it) }
        setContent {
            val settingsVM: SettingsVM = viewModel(factory = VMFactory(this))
            val appVM: AppVM = viewModel(factory = VMFactory(this))
            val settings by settingsVM.settings.collectAsState()
            val ready by settingsVM.ready.collectAsState()

            // [P20-FIX] تطبيق فوري لعلم منع لقطات الشاشة عند تغيّر الإعداد — كان يُطبَّق في
            // onResume فقط، فتبديل الزر من الإعدادات لا يُغلق الحجب ولا يفتحه إلا بمغادرة
            // التطبيق والعودة (سلوك «الزر لا يعمل» الذي أبلغ عنه المستخدم). الآن: كل تغيّر
            // لقيمة flagSecure في DataStore يُطبَّق على نافذة النشاط في الحال.
            LaunchedEffect(settings.flagSecure) {
                if (settings.flagSecure) {
                    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            LaunchedEffect(Unit) {
                val saved = graph.settings.settings.first().language
                val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
                if (saved.isNotBlank() && !current.startsWith(saved)) {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(saved))
                }
            }

            SuperBizTheme(
                themeMode = settings.theme,
                // ألوان النظام الديناميكية — إعداد حقيقي
                dynamicColors = settings.dynamicColors
            ) {
                // تكبير الخط من مركز الإعدادات — يُطبّق على كل التطبيق عبر Density
                val dens = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                        density = dens.density, fontScale = dens.fontScale * settings.fontScale
                    )
                ) {
                // المراحل: 0 ترحيب → 1 أذونات وإمكانية الوصول (مرة واحدة) → 2 قفل → 3 الرئيسية
                // [P6-M50 إصلاح] stage/pendingRoute كانت remember بسيطة — التدوير (أو أي تغيير تكوين)
                // يعيد مرحلة الترحيب/القفل ويعيد إطلاق مسار الودجة من intent في كل دوران. rememberSaveable
                // يحفظ القيمة المستهلكة (null) فلا يُعاد فتح المسار بعد التدوير، ولا تُفقد مرحلة القفل/الرئيسية
                var stage by rememberSaveable { mutableIntStateOf(0) }
                // [V1-B1 إصلاح] توقيع rememberSaveable(stateSaver=) في runtime 1.7 يتطلب init يعيد
                // MutableState<T> حرفياً (RememberSaveable.kt:127) — تعليق P6-M50 القديم كان معكوساً
                var pendingRoute by rememberSaveable(stateSaver = pendingRouteSaver) { mutableStateOf(deepRoute) }
                val routeEvent by routeEvents.collectAsState()
                LaunchedEffect(routeEvent) {
                    routeEvent?.let {
                        pendingRoute = it
                        routeEvents.value = null
                    }
                }

                // إعادة القفل عند الخلفية إن وُجدت وسيلة حماية
                val credsConfigured = settings.pinHash != null || settings.pinBlob != null || settings.biometric

                // عند خروج التطبيق للخلفية (ON_STOP على مستوى العملية) تُعاد مرحلة القفل — المستخدم يجد شاشة الرمز عند العودة
// [P6-M50 إصلاح] ختم الخلفية يُحفظ كذلك كي لا تفقد مهلة إعادة القفل مرجعها عند التدوير
var bgSince by rememberSaveable { mutableStateOf(0L) }
                // مهلة إعادة القفل — 0 فوري، وإلا يُعاد القفل بعد تجاوز المهلة فقط عند ON_START
                DisposableEffect(credsConfigured, settings.lockTimeoutMin) {
                    val obs = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_STOP -> {
                                bgSince = System.currentTimeMillis()
                                if (credsConfigured && settings.lockTimeoutMin <= 0 && stage == 3) stage = 2
                            }
                            Lifecycle.Event.ON_START -> {
                                val timeoutMs = settings.lockTimeoutMin * 60_000L
                                if (credsConfigured && settings.lockTimeoutMin > 0 &&
                                    stage == 3 && bgSince > 0 &&
                                    System.currentTimeMillis() - bgSince >= timeoutMs
                                ) stage = 2
                            }
                            else -> Unit
                        }
                    }
                    ProcessLifecycleOwner.get().lifecycle.addObserver(obs)
                    onDispose { ProcessLifecycleOwner.get().lifecycle.removeObserver(obs) }
                }

                when {
                    // انتظار أول قيمة حقيقية من DataStore قبل أي قرار قفل
                    !ready -> BrandedLoading()

                    stage == 0 -> WelcomeScreen(
                        quick = settings.welcomeSeen,
                        onDone = {
                            settingsVM.markWelcomeSeen()
                            stage = if (!settings.permissionsSeen) 1
                            else if (settings.pinHash != null || settings.pinBlob != null || settings.biometric) 2 else 3
                        }
                    )

                    // شاشة طلب الأذونات — مرة واحدة فقط بعد الترحيب
                    stage == 1 -> PermissionsScreen(
                        settingsVM = settingsVM,
                        onDone = {
                            settingsVM.markPermissionsSeen()
                            stage = if (settings.pinHash != null || settings.pinBlob != null || settings.biometric) 2 else 3
                        }
                    )

                    stage == 2 -> LockScreen(
                        activity = this,
                        settingsVM = settingsVM,
                        onUnlocked = { stage = 3 }
                    )

                    else -> SuperBizRoot(
                        appVM, settingsVM,
                        startRoute = pendingRoute,
                        onRouteConsumed = { pendingRoute = null }
                    )
                }
                } // نهاية نطاق CompositionLocalProvider لتكبير الخط
            }
        }
    }

}

/** شاشة تحضير مصغرة أثناء قراءة الإعدادات — بنفس الهوية البصرية */
@Composable
private fun BrandedLoading() {
    val g = glassColors()
    val infinite = rememberInfiniteTransition(label = "loading")
    val alpha by infinite.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha"
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(g.bgGradient)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(64.dp)
                    .alpha(alpha)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(listOf(VioDeep, Cyan))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Storefront, null, tint = Color.White, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "SuperBiz",
                style = MaterialTheme.typography.titleMedium,
                color = g.textPrimary,
                modifier = Modifier.alpha(alpha)
            )
        }
    }
}

/** مصنع موحد للـ ViewModels عبر AppGraph */
class VMFactory(private val context: android.content.Context) :
    androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        val app = context.applicationContext as android.app.Application
        @Suppress("UNCHECKED_CAST")
        return when {
            modelClass.isAssignableFrom(AppVM::class.java) -> AppVM(app) as T
            modelClass.isAssignableFrom(com.superbiz.app.vm.DebtsVM::class.java) -> com.superbiz.app.vm.DebtsVM(app) as T
            modelClass.isAssignableFrom(com.superbiz.app.vm.InvoicesVM::class.java) -> com.superbiz.app.vm.InvoicesVM(app) as T
            modelClass.isAssignableFrom(com.superbiz.app.vm.InventoryVM::class.java) -> com.superbiz.app.vm.InventoryVM(app) as T
            modelClass.isAssignableFrom(com.superbiz.app.vm.ChecksVM::class.java) -> com.superbiz.app.vm.ChecksVM(app) as T
            modelClass.isAssignableFrom(com.superbiz.app.vm.InstallmentsVM::class.java) -> com.superbiz.app.vm.InstallmentsVM(app) as T
            modelClass.isAssignableFrom(com.superbiz.app.vm.ReportsVM::class.java) -> com.superbiz.app.vm.ReportsVM(app) as T
            modelClass.isAssignableFrom(com.superbiz.app.vm.AutoVM::class.java) -> com.superbiz.app.vm.AutoVM(app) as T
            modelClass.isAssignableFrom(com.superbiz.app.vm.SettingsVM::class.java) -> com.superbiz.app.vm.SettingsVM(app) as T
            modelClass.isAssignableFrom(com.superbiz.app.vm.ExpensesVM::class.java) -> com.superbiz.app.vm.ExpensesVM(app) as T
            // VM الرؤى الذكية — بطاقات الذكاء عبر R7Smart
            modelClass.isAssignableFrom(com.superbiz.app.vm.SmartInsightsVM::class.java) -> com.superbiz.app.vm.SmartInsightsVM(app) as T
            // VM الرؤى الذكية للموجة R9 — عبر R9Smart/R9Adapters
            modelClass.isAssignableFrom(com.superbiz.app.vm.R9InsightsVM::class.java) -> com.superbiz.app.vm.R9InsightsVM(app) as T
            // VM الرؤى الذكية للموجة R10 — عبر R10Smart
            modelClass.isAssignableFrom(com.superbiz.app.vm.R10InsightsVM::class.java) -> com.superbiz.app.vm.R10InsightsVM(app) as T
            // VM الرؤى الذكية للموجة R11 — عبر R11Smart
            modelClass.isAssignableFrom(com.superbiz.app.vm.R11InsightsVM::class.java) -> com.superbiz.app.vm.R11InsightsVM(app) as T
            // VM الرؤى الذكية للموجة R12 — عبر R12Smart
            modelClass.isAssignableFrom(com.superbiz.app.vm.R12InsightsVM::class.java) -> com.superbiz.app.vm.R12InsightsVM(app) as T
            // VM الرؤى الذكية للموجة R13 — عبر R13Smart
            modelClass.isAssignableFrom(com.superbiz.app.vm.R13InsightsVM::class.java) -> com.superbiz.app.vm.R13InsightsVM(app) as T
            // VM الرؤى الذكية للموجة R14
            modelClass.isAssignableFrom(com.superbiz.app.vm.R14InsightsVM::class.java) -> com.superbiz.app.vm.R14InsightsVM(app) as T
            // VM الرؤى الذكية للموجة R15
            modelClass.isAssignableFrom(com.superbiz.app.vm.R15InsightsVM::class.java) -> com.superbiz.app.vm.R15InsightsVM(app) as T
            // [P46-W1] جولة 7: VM الولاء والكوبونات — إدارة الكوبونات في الإعدادات
            modelClass.isAssignableFrom(com.superbiz.app.vm.LoyaltyVM::class.java) -> com.superbiz.app.vm.LoyaltyVM(app) as T
            else -> throw IllegalArgumentException("unknown VM ${modelClass.name}")
        }
    }
}
