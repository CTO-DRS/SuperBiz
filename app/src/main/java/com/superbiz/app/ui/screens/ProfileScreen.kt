package com.superbiz.app.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CurrencyExchange
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap // [P17-c] مصغّرة صورة الشركة
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavHostController
import com.superbiz.app.MainActivity
import com.superbiz.app.R
import com.superbiz.app.domain.ProfileCompletion
import com.superbiz.app.security.BiometricGate
import com.superbiz.app.ui.components.BizField
import com.superbiz.app.ui.components.EmptyState
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.components.IconChip
import com.superbiz.app.ui.components.SectionTitle
import com.superbiz.app.ui.components.SubHeader
import com.superbiz.app.ui.components.fmtNum
import com.superbiz.app.ui.components.numberFieldOptions
import com.superbiz.app.ui.components.parseNum
import com.superbiz.app.ui.nav.AvatarView
import com.superbiz.app.ui.theme.Amber
import com.superbiz.app.ui.theme.Cyan
import com.superbiz.app.ui.theme.GreenDeep
import com.superbiz.app.ui.theme.RedDeep
import com.superbiz.app.ui.theme.Vio
import com.superbiz.app.ui.theme.VioDeep
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.ui.screens.statement.StatementUiFacade // [P17-c] الملاحظة الافتراضية للكشف
import com.superbiz.app.ui.screens.statement.copyUriToDir // [P17-c] نسخ صورة الشركة SAF
import com.superbiz.app.ui.screens.statement.decodeScaled // [P17-c] مصغّرة صورة الشركة
import kotlinx.coroutines.Dispatchers // [P17-c] كتابة مفاتيح الهوية الجديدة
import kotlinx.coroutines.launch
import com.superbiz.app.util.Dates
import com.superbiz.app.util.Money
import com.superbiz.app.vm.AppVM
import com.superbiz.app.vm.SettingsVM
import java.io.File

/**
 * الملف الشخصي المتكامل — هوية النشاط + إحصاءات + معلومات + تفضيلات + حماية بيومترية
 * + نسخ احتياطي + عن التطبيق، في مركز واحد بديل عن شاشة الإعدادات القديمة.
*/
@Composable
fun ProfileScreen(appVM: AppVM, settingsVM: SettingsVM, nav: NavHostController) {
    val activity = LocalContext.current as? MainActivity
    val fragActivity = activity as? FragmentActivity
    val settings by settingsVM.settings.collectAsState()
    val currencies by settingsVM.currencies.collectAsState()
    val toast by settingsVM.toast.collectAsState()
    val stats by appVM.profileStats.collectAsState()
    val symbol by appVM.symbol.collectAsState()
    val g = glassColors()

    LaunchedEffect(Unit) { appVM.loadProfileStats() }

    var showPin by remember { mutableStateOf(false) }
    var reAuth by remember { mutableStateOf<ReAuthAction?>(null) }
    // [P5-H3 إصلاح]: وجهة الاستيراد الكامل تُحفظ مؤقتاً حتى اكتمال إعادة المصادقة
    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showRates by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }
    var showPerms by remember { mutableStateOf(false) }

    // اختيار صورة المستخدم
    val avatarScope = rememberCoroutineScope()
    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null && activity != null) {
            // [P20-FIX agent5]: كان فك الصورة كاملة الحجم + ضغطها + كتابتها على الخيط الرئيسي
            // (تجميد مرئي/ANR مع صور الكاميرا) — انتقل إلى IO، ولا يُحفظ المسار إلا عند
            // نجاح الكتابة فعلاً (كان يُحفظ حتى لو فشل فك الصورة فيبقى مساراً ميتاً)
            avatarScope.launch {
                val act = activity
                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val dir = File(act.filesDir, "avatar").apply { mkdirs() }
                        val f = File(dir, "user.jpg")
                        var wrote = false
                        act.contentResolver.openInputStream(uri)?.use { input ->
                            val raw = android.graphics.BitmapFactory.decodeStream(input) ?: return@use
                            val scaled = raw.scaleTo(720)
                            f.outputStream().use { out -> wrote = scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out) }
                        }
                        if (wrote) f.absolutePath else null
                    } catch (e: Throwable) { null }
                }
                if (ok != null) {
                    settingsVM.setAvatarPath(ok)
                    appVM.refresh()
                } else {
                    android.widget.Toast.makeText(
                        act, act.getString(R.string.pdf_error), android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // تصدير/استعادة عبر SAF
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { settingsVM.exportTo(it) } }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) {
        // [P5-H3 إصلاح]: الاستيراد الكامل = wipeAll + استبدال قاعدة البيانات بالكامل —
        // كان ينفّذ بلا إعادة مصادقة بينما حذف الـPIN نفسه محمي بـReAuth!
        // نفس نمط التصفير المدمّر: ReAuth فقط إن وُجد رمز، وإلا التنفيذ مباشرة
        if (settings.pinHash != null || settings.pinBlob != null) {
            pendingImportUri = uri
            reAuth = ReAuthAction.IMPORT_ALL
        } else {
            settingsVM.importFrom(uri)
        }
    } }

    val dirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { settingsVM.setBackupDir(it) } }

    val completion = ProfileCompletion.percent(
        ProfileCompletion.Input(
            businessName = settings.businessName,
            avatarSet = settings.avatarPath != null,
            ownerName = settings.ownerName,
            phone = settings.phone,
            address = settings.address,
            taxNumber = settings.taxNumber,
            email = settings.email
        )
    )
    val missing = ProfileCompletion.missing(
        ProfileCompletion.Input(
            businessName = settings.businessName,
            avatarSet = settings.avatarPath != null,
            ownerName = settings.ownerName,
            phone = settings.phone,
            address = settings.address,
            taxNumber = settings.taxNumber,
            email = settings.email
        )
    )
    val missingHint = if (missing.isEmpty()) {
        stringResourceCompat(R.string.profile_complete_msg)
    } else {
        // حلقة عادية ضمن سياق قابل للتركيب (ليس lambda خاطئ)
        val parts = ArrayList<String>()
        for (idx in missing.take(2)) {
            parts += stringResourceCompat(
                when (idx) {
                    0 -> R.string.business_name; 1 -> R.string.profile_photo
                    2 -> R.string.owner_name; 3 -> R.string.phone
                    4 -> R.string.profile_address; 5 -> R.string.tax_number
                    else -> R.string.profile_email
                }
            )
        }
        stringResourceCompat(R.string.profile_missing_prefix) + " " + parts.joinToString(" • ")
    }

    // فحص توفر البيومتريا
    val bioCheck = if (fragActivity != null) BiometricGate.check(fragActivity) else BiometricGate.UNKNOWN
    val bioAvailable = bioCheck == BiometricGate.OK

    // غلاف كامل لإضافة شاشة الأذونات كطبقة علوية
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SubHeader(stringResourceCompat(R.string.profile_title))

        // ─── بطاقة الهوية ───
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(contentAlignment = Alignment.BottomEnd) {
                        AvatarView(appVM, size = 84.dp)
                        // شارة كاميرا
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(VioDeep, Cyan)))
                                .border(2.dp, g.surfaceStrong, CircleShape)
                                .clickable {
                                    avatarPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.CameraAlt, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            appVM.businessLabel(),
                            style = MaterialTheme.typography.titleMedium,
                            color = g.textPrimary, maxLines = 1
                        )
                        if (settings.ownerName.isNotBlank()) {
                            Text(
                                settings.ownerName,
                                fontSize = 13.sp, color = g.textSecondary, maxLines = 1
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CompletionRing(completion, sizeDp = 34f)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    missingHint,
                                    fontSize = 11.sp, color = g.textSecondary, maxLines = 2
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionPill(stringResourceCompat(R.string.avatar_change), Cyan) {
                        avatarPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                    if (settings.avatarPath != null) {
                        ActionPill(stringResourceCompat(R.string.avatar_remove), RedDeep) {
                            settingsVM.setAvatarPath(null); appVM.refresh()
                        }
                    }
                }
            }
        }

        // ─── الإحصاءات ───
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                Icons.Rounded.ReceiptLong,
                fmtNum(stats.invoices.toDouble()),
                stringResourceCompat(R.string.stat_invoices), Vio, Modifier.weight(1f)
            )
            StatTile(
                Icons.Rounded.People,
                fmtNum(stats.parties.toDouble()),
                stringResourceCompat(R.string.stat_parties), Cyan, Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                Icons.Rounded.Widgets,
                fmtNum(stats.products.toDouble()),
                stringResourceCompat(R.string.stat_products), GreenDeep, Modifier.weight(1f)
            )
            StatTile(
                Icons.Rounded.TrendingUp,
                Money.format(stats.salesMonth, symbol),
                stringResourceCompat(R.string.stat_sales_month), Amber, Modifier.weight(1f)
            )
        }

        // ─── معلومات النشاط والملف ───
        SectionTitle(stringResourceCompat(R.string.profile_info_title))
        // بوابة مركز الإعدادات المركزي — ثمانية أقسام بكل مفاتيح التطبيق
        GlassCard(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { nav.navigate(com.superbiz.app.ui.nav.Routes.SETTINGS_HUB) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Rounded.Tune, null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Brush.linearGradient(listOf(VioDeep, Cyan)))
                        .padding(8.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        // [P20-FIX agent5/19]: كانت عربية صلبة — مُستخرجة إلى الموارد
                        stringResourceCompat(R.string.settings_hub_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = g.textPrimary
                    )
                    Text(
                        stringResourceCompat(R.string.settings_hub_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = g.textSecondary
                    )
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = g.textSecondary)
            }
        }
        Spacer(Modifier.height(10.dp))

        GlassCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                var bname by remember(settings.businessName) { mutableStateOf(settings.businessName) }
                var owner by remember(settings.ownerName) { mutableStateOf(settings.ownerName) }
                var phone by remember(settings.phone) { mutableStateOf(settings.phone) }
                var email by remember(settings.email) { mutableStateOf(settings.email) }
                var address by remember(settings.address) { mutableStateOf(settings.address) }
                var taxNo by remember(settings.taxNumber) { mutableStateOf(settings.taxNumber) }
                var taxRate by remember(settings.taxRate) { mutableStateOf(Money.num(settings.taxRate)) }
                var goal by remember(settings.monthlyGoal) { mutableStateOf(Money.num(settings.monthlyGoal)) }
                val savedMsg = stringResourceCompat(R.string.profile_saved)
                // [P17-c] حقول الهوية الجديدة (مدينة/دولة/موقع/سجل تجاري) + صورة الشركة + الملاحظة الافتراضية للكشف
                var city by remember(settings.city) { mutableStateOf(settings.city) }
                var country by remember(settings.country) { mutableStateOf(settings.country) }
                var website by remember(settings.website) { mutableStateOf(settings.website) }
                var crNo by remember(settings.crNumber) { mutableStateOf(settings.crNumber) }
                var stNote by remember { mutableStateOf(activity?.applicationContext?.let { StatementUiFacade.defaultNote(it) } ?: "") }
                val identScope = rememberCoroutineScope()
                var companyPhoto by remember(settings.companyPhotoPath) { mutableStateOf(settings.companyPhotoPath) }
                val companyPhotoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
                ) { uri ->
                    val appCtx = activity?.applicationContext ?: return@rememberLauncherForActivityResult
                    if (uri == null) return@rememberLauncherForActivityResult
                    identScope.launch(Dispatchers.IO) {
                        // [P17-c] SAF → نسخ إلى filesDir/identity/ ثم حفظ المسار في DataStore
                        val copied = copyUriToDir(appCtx, uri, java.io.File(appCtx.filesDir, "identity"), "company")
                        if (copied != null) {
                            runCatching { com.superbiz.app.AppGraph.from(appCtx).settings.setCompanyPhotoPath(copied) }
                            companyPhoto = copied
                        }
                    }
                }

                BizField(bname, { bname = it }, stringResourceCompat(R.string.business_name),
                    leading = { Icon(Icons.Rounded.Storefront, null, tint = g.textSecondary, modifier = Modifier.size(18.dp)) })
                BizField(owner, { owner = it }, stringResourceCompat(R.string.owner_name),
                    leading = { Icon(Icons.Rounded.Person, null, tint = g.textSecondary, modifier = Modifier.size(18.dp)) })
                BizField(phone, { phone = it }, stringResourceCompat(R.string.phone),
                    leading = { Icon(Icons.Rounded.People, null, tint = g.textSecondary, modifier = Modifier.size(18.dp)) })
                BizField(email, { email = it }, stringResourceCompat(R.string.profile_email),
                    leading = { Icon(Icons.Rounded.Mail, null, tint = g.textSecondary, modifier = Modifier.size(18.dp)) })
                BizField(address, { address = it }, stringResourceCompat(R.string.profile_address),
                    leading = { Icon(Icons.Rounded.Storefront, null, tint = g.textSecondary, modifier = Modifier.size(18.dp)) })
                BizField(taxNo, { taxNo = it }, stringResourceCompat(R.string.tax_number))
                // [P17-c] حقول الهوية الخمسة — نفس نمط الحقول أعلاه
                BizField(city, { city = it }, stringResourceCompat(R.string.st_field_city),
                    leading = { Icon(Icons.Rounded.Storefront, null, tint = g.textSecondary, modifier = Modifier.size(18.dp)) })
                BizField(country, { country = it }, stringResourceCompat(R.string.st_field_country),
                    leading = { Icon(Icons.Rounded.Storefront, null, tint = g.textSecondary, modifier = Modifier.size(18.dp)) })
                BizField(website, { website = it }, stringResourceCompat(R.string.st_field_website),
                    leading = { Icon(Icons.Rounded.Language, null, tint = g.textSecondary, modifier = Modifier.size(18.dp)) })
                BizField(crNo, { crNo = it }, stringResourceCompat(R.string.st_field_cr_number))
                // صورة الشركة/السجل — منتقي SAF ينسخ إلى filesDir/identity/ (حفظ فوري عند الاختيار)
                var photoBmp by remember(companyPhoto) { mutableStateOf<android.graphics.Bitmap?>(null) }
                LaunchedEffect(companyPhoto) {
                    val path = companyPhoto
                    photoBmp = if (path.isBlank()) null
                    else kotlinx.coroutines.withContext(Dispatchers.IO) { decodeScaled(path, 256) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(g.surfaceStrong)
                            .border(1.dp, g.border, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        val shownPhoto = photoBmp // [P17-integration] فك التفويض للسماح بالتحويل الذكي
                        if (shownPhoto != null) {
                            androidx.compose.foundation.Image(
                                bitmap = shownPhoto.asImageBitmap(), contentDescription = stringResource(R.string.a11y_profile_photo), // [P39-M4-2]
                                modifier = Modifier.fillMaxSize(),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                        } else {
                            Icon(Icons.Rounded.CameraAlt, null, tint = g.textSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                    Text(
                        if (companyPhoto.isBlank()) stringResourceCompat(R.string.st_company_photo)
                        else stringResourceCompat(R.string.st_company_photo_set),
                        color = g.textPrimary, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { companyPhotoPicker.launch(arrayOf("image/*")) }
                    )
                    if (companyPhoto.isNotBlank()) {
                        Text(
                            stringResourceCompat(R.string.delete),
                            color = RedDeep, fontSize = 13.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    companyPhoto = ""
                                    val appCtx = activity?.applicationContext
                                    if (appCtx != null) identScope.launch(Dispatchers.IO) {
                                        runCatching { com.superbiz.app.AppGraph.from(appCtx).settings.setCompanyPhotoPath("") }
                                    }
                                }
                        )
                    }
                }
                // الملاحظة الافتراضية لكشف الحساب — تُقرأ في شاشة الكشف كمبدئي لصندوق الملاحظات
                BizField(stNote, { stNote = it }, stringResourceCompat(R.string.st_default_note))
                BizField(taxRate, { taxRate = it }, stringResourceCompat(R.string.default_tax), keyboard = numberFieldOptions())
                BizField(goal, { goal = it }, stringResourceCompat(R.string.goal_label),
                    keyboard = numberFieldOptions())

                Text(
                    stringResourceCompat(R.string.save),
                    color = g.accent, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            settingsVM.setBusinessName(bname)
                            settingsVM.setOwnerName(owner)
                            settingsVM.setPhone(phone)
                            settingsVM.setEmail(email)
                            settingsVM.setAddress(address)
                            settingsVM.setTaxNumber(taxNo)
                            settingsVM.setTaxRate(parseNum(taxRate))
                            settingsVM.setMonthlyGoal(parseNum(goal))
                            // [P17-c] المفاتيح الجديدة الستة — الملف الشخصي للكشف: مدينة/دولة/موقع/
                            // سجل تجاري + الملاحظة الافتراضية عبر statement_prefs (الواجهة)
                            val appCtx = activity?.applicationContext
                            if (appCtx != null) identScope.launch(Dispatchers.IO) {
                                runCatching {
                                    val graph = com.superbiz.app.AppGraph.from(appCtx)
                                    graph.settings.setCity(city.trim())
                                    graph.settings.setCountry(country.trim())
                                    graph.settings.setWebsite(website.trim())
                                    graph.settings.setCrNumber(crNo.trim())
                                }
                            }
                            StatementUiFacade.setDefaultNote(appCtx ?: return@clickable, stNote)
                            appVM.refresh()
                            settingsVM.notifyToast(savedMsg)
                        }
                        .padding(vertical = 4.dp)
                )
            }
        }

        // ─── التفضيلات ───
        SectionTitle(stringResourceCompat(R.string.preferences))
        GlassCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Language, null, tint = g.accent2, modifier = Modifier.size(18.dp))
                    Text(stringResourceCompat(R.string.settings_language), color = g.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // [H5-2 V 3.2.0] اللغات الست كاملة — الأسماء بلغتها الأصلية دائماً
                    FilterPill("العربية", settings.language == "ar") { settingsVM.setLanguage("ar") }
                    FilterPill("English", settings.language == "en") { settingsVM.setLanguage("en") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill("Türkçe", settings.language == "tr") { settingsVM.setLanguage("tr") }
                    FilterPill("Français", settings.language == "fr") { settingsVM.setLanguage("fr") }
                    FilterPill("اردو", settings.language == "ur") { settingsVM.setLanguage("ur") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill("Bahasa", settings.language == "id") { settingsVM.setLanguage("id") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Brush, null, tint = g.accent2, modifier = Modifier.size(18.dp))
                    Text(stringResourceCompat(R.string.settings_theme), color = g.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill(stringResourceCompat(R.string.theme_dark), settings.theme == "dark") { settingsVM.setTheme("dark") }
                    FilterPill(stringResourceCompat(R.string.theme_light), settings.theme == "light") { settingsVM.setTheme("light") }
                    FilterPill(stringResourceCompat(R.string.theme_auto), settings.theme == "auto") { settingsVM.setTheme("auto") }
                }
            }
        }

        // ─── العملة ───
        SectionTitle(stringResourceCompat(R.string.base_currency))
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    currencies.take(4).forEach { c ->
                        FilterPill(c.code, settings.baseCurrency == c.code) { settingsVM.setBaseCurrency(c.code) }
                    }
                }
                if (currencies.size > 4) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        currencies.drop(4).forEach { c ->
                            FilterPill(c.code, settings.baseCurrency == c.code) { settingsVM.setBaseCurrency(c.code) }
                        }
                    }
                }
                Text(
                    stringResourceCompat(R.string.rates) + " ▾",
                    color = g.accent2, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { showRates = true }
                )
            }
        }

        // ─── الحماية: رمز + بيومتريا + خصوصية الويدجت () ───
        SectionTitle(stringResourceCompat(R.string.security))
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                val pinProtected = settings.pinHash != null || settings.pinBlob != null
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconChip(Icons.Rounded.Lock, Color.White, Vio)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (pinProtected) stringResourceCompat(R.string.pin_enabled)
                        else stringResourceCompat(R.string.pin_setup),
                        color = g.textPrimary, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    if (pinProtected) {
                        ActionPill(stringResourceCompat(R.string.pin_change), Cyan) {
                            // تغيير الرمز يتطلب تأكيد الرمز الحالي أولاً
                            reAuth = ReAuthAction.CHANGE_PIN
                        }
                        Spacer(Modifier.width(8.dp))
                        ActionPill(stringResourceCompat(R.string.pin_remove), RedDeep) {
                            // إزالة الحماية لم تعد بلا مصادقة
                            reAuth = ReAuthAction.REMOVE_PIN
                        }
                    } else {
                        ActionPill(stringResourceCompat(R.string.pin_setup), Cyan) { showPin = true }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(g.border.copy(alpha = 0.6f)))
                Spacer(Modifier.height(12.dp))
                // إصلاح خطأ ترجمة: stringResourceCompat (@Composable) لا يجوز
                // استدعاؤها داخل lambda غير قابلة للتركيب — تُحلّ مسبقاً في السياق الصحيح
                val pinReqBioMsg = stringResourceCompat(R.string.pin_required_bio)
                ToggleRow(
                    icon = Icons.Rounded.Fingerprint,
                    iconBg = if (bioAvailable) VioDeep else g.textSecondary,
                    title = stringResourceCompat(R.string.biometric_enable),
                    subtitle = stringResourceCompat(R.string.biometric_enable_hint),
                    checked = settings.biometric && bioAvailable,
                    enabled = bioAvailable || settings.biometric,
                    onChecked = { on ->
                        when {
                            // لا بيومتريا بلا رمز — تمنع حالة الفتح الطارئ أصلاً
                            on && !pinProtected -> settingsVM.notifyToast(pinReqBioMsg)
                            // إيقاف طبقة حماية يتطلب تأكيد الرمز
                            !on && pinProtected -> reAuth = ReAuthAction.BIO_OFF
                            else -> settingsVM.setBiometric(on)
                        }
                    }
                )
                if (!bioAvailable) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResourceCompat(
                            if (bioCheck == BiometricGate.NO_ENROLLED) R.string.biometric_no_enrolled
                            else R.string.biometric_no_hardware
                        ),
                        color = g.textSecondary, fontSize = 11.sp
                    )
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(g.border.copy(alpha = 0.6f)))
                Spacer(Modifier.height(12.dp))
                // إخفاء المبالغ في الويدجت — الأرقام تظهر كنقاط على الشاشة الرئيسية
                ToggleRow(
                    icon = Icons.Rounded.VisibilityOff,
                    iconBg = Cyan,
                    title = stringResourceCompat(R.string.widget_redact),
                    subtitle = stringResourceCompat(R.string.widget_redact_hint),
                    checked = settings.redactWidgets,
                    enabled = true,
                    onChecked = { on -> settingsVM.setRedactWidgets(on) }
                )
            }
        }

        // ─── الأذونات وإمكانية الوصول () ───
        SectionTitle(stringResourceCompat(R.string.perm_title))
        GlassCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconChip(Icons.Rounded.AdminPanelSettings, Color.White, Cyan)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResourceCompat(R.string.perm_manage),
                        color = g.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResourceCompat(R.string.perm_manage_hint),
                        color = g.textSecondary, fontSize = 11.sp
                    )
                }
                ActionPill(stringResourceCompat(R.string.perm_open), GreenDeep) { showPerms = true }
            }
        }

        // ─── النسخ الاحتياطي ───
        SectionTitle(stringResourceCompat(R.string.backup_title))
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionPill(stringResourceCompat(R.string.backup_export), Cyan) {
                        exportLauncher.launch("superbiz-backup-${System.currentTimeMillis()}.json")
                    }
                    ActionPill(stringResourceCompat(R.string.backup_import), Vio) {
                        importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ActionPill(stringResourceCompat(R.string.backup_dir), GreenDeep) { dirLauncher.launch(null) }
                    Text(
                        settings.backupDirUri?.takeLast(28) ?: "—",
                        fontSize = 11.sp, color = g.textSecondary, maxLines = 1
                    )
                }

                // تكرار النسخ الاحتياطي التلقائي
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(g.border.copy(alpha = 0.6f)))
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResourceCompat(R.string.autoback_freq),
                    color = g.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill(stringResourceCompat(R.string.autoback_off), settings.autoBackupDays == 0) { settingsVM.setAutoBackupDays(0) }
                    FilterPill(stringResourceCompat(R.string.autoback_daily), settings.autoBackupDays == 1) { settingsVM.setAutoBackupDays(1) }
                    FilterPill(stringResourceCompat(R.string.autoback_weekly), settings.autoBackupDays == 7) { settingsVM.setAutoBackupDays(7) }
                    FilterPill(stringResourceCompat(R.string.autoback_monthly), settings.autoBackupDays == 30) { settingsVM.setAutoBackupDays(30) }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResourceCompat(R.string.autoback_last) + ": " +
                        (if (settings.lastAutoBackup > 0) Dates.short(settings.lastAutoBackup) else "—"),
                    fontSize = 11.sp, color = g.textSecondary
                )
            }
        }

        // ─── عن التطبيق ───
        SectionTitle(stringResourceCompat(R.string.about))
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    stringResourceCompat(R.string.about_body),
                    color = g.textSecondary, fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "v" + appVersionName(activity) + " • Jetpack Compose • 100% Offline",
                    fontSize = 11.sp, color = g.textSecondary, fontWeight = FontWeight.Medium
                )
            }
        }

        // ─── منطقة الخطر ───
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(RedDeep.copy(alpha = 0.10f))
                .border(1.dp, RedDeep.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                .clickable { showReset = true }
                .padding(14.dp)
        ) {
            Text(
                stringResourceCompat(R.string.danger_reset),
                color = RedDeep, fontWeight = FontWeight.Bold
            )
        }
    }

        // طبقة شاشة الأذونات وإمكانية الوصول فوق الملف الشخصي
        if (showPerms) {
            PermissionsScreen(settingsVM = settingsVM, onDone = { showPerms = false })
        }
    }

    // توست
    toast?.let {
        androidx.compose.material3.Snackbar(
            action = { Text(stringResourceCompat(R.string.done), color = g.accent2, modifier = Modifier.clickable { settingsVM.clearToast() }) }
        ) {
            Text(it)
        }
    }

    // حوار PIN
    if (showPin) {
        PinSetupDialog(settingsVM) { showPin = false }
    }

    // حوار إعادة المصادقة قبل أي تغيير أمني (تغيير/إزالة الرمز، إيقاف البيومتريا)
    reAuth?.let { action ->
        ReAuthDialog(
            settingsVM = settingsVM,
            message = stringResourceCompat(
                when (action) {
                    ReAuthAction.REMOVE_PIN -> R.string.pin_remove_reauth
                    ReAuthAction.CHANGE_PIN -> R.string.pin_change_reauth
                    ReAuthAction.BIO_OFF -> R.string.bio_disable_reauth
                    ReAuthAction.WIPE_ALL -> R.string.wipe_reauth
                    ReAuthAction.IMPORT_ALL -> R.string.import_reauth
                }
            ),
            onVerified = {
                reAuth = null
                when (action) {
                    ReAuthAction.REMOVE_PIN -> settingsVM.removePin()
                    ReAuthAction.CHANGE_PIN -> showPin = true
                    ReAuthAction.BIO_OFF -> settingsVM.setBiometric(false)
                    ReAuthAction.WIPE_ALL -> settingsVM.wipeAll()
                    ReAuthAction.IMPORT_ALL -> {
                        pendingImportUri?.let { settingsVM.importFrom(it) }
                        pendingImportUri = null
                    }
                }
            },
            onDismiss = {
                reAuth = null
                pendingImportUri = null
            }
        )
    }

    // أسعار الصرف
    if (showRates) {
        PickerDialog(
            title = stringResourceCompat(R.string.rates),
            onDismiss = { showRates = false }
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                currencies.forEach { c ->
                    var rate by remember(c.code) { mutableStateOf(c.rateToBase.toString()) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(c.code + " " + c.symbol, color = g.textPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.width(72.dp))
                        BizField(rate, { rate = it }, stringResourceCompat(R.string.rate_hint), modifier = Modifier.weight(1f), keyboard = numberFieldOptions())
                        Text(
                            stringResourceCompat(R.string.save),
                            color = g.accent, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { settingsVM.updateRate(c.code, parseNum(rate)) }
                        )
                    }
                }
            }
        }
    }

    // تأكيد التصفير
    if (showReset) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showReset = false },
            containerColor = g.surfaceStrong,
            title = { Text(stringResourceCompat(R.string.danger_reset), color = RedDeep) },
            text = { Text(stringResourceCompat(R.string.reset_confirm), color = g.textPrimary) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    // التصفير المدمر يتطلب إعادة مصادقة — باب أخير أمام الوصول غير المصرّح
                    showReset = false
                    if (settings.pinHash != null || settings.pinBlob != null) {
                        reAuth = ReAuthAction.WIPE_ALL
                    } else {
                        settingsVM.wipeAll()
                    }
                }) { Text(stringResourceCompat(R.string.confirm), color = RedDeep, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showReset = false }) {
                    Text(stringResourceCompat(R.string.cancel), color = g.textSecondary)
                }
            }
        )
    }
}

/** قراءة اسم إصدار التطبيق ديناميكياً */
internal fun appVersionName(activity: MainActivity?): String = try {
    activity?.packageManager?.getPackageInfo(activity.packageName, 0)?.versionName ?: "3.9.0"
} catch (e: Exception) { "3.7.0" }

/**
 * حوار تعيين/تغيير رمز الحماية — الحد الأدنى 6 أرقام (سياسة أقوى)
 * مع مطابقة الإدخالين.
 * وظيفة 37: زر عين يبدّل الإظهار/الإخفاء + مقياس قوة نصي/ملون
 * (PinStrength.level — عرض إرشادي فقط لا يمس منطق الحفظ/التحقق إطلاقاً).
*/
@Composable
private fun PinSetupDialog(settingsVM: SettingsVM, onDone: () -> Unit) {
    val g = glassColors()
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    var errRes by remember { mutableStateOf<Int?>(null) }
    var showPin by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDone,
        containerColor = g.surfaceStrong,
        title = { Text(stringResourceCompat(R.string.pin_setup), color = g.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = p1,
                    onValueChange = { p1 = it.filter { c -> c.isDigit() }.take(8) },
                    label = { Text(stringResourceCompat(R.string.pin_enter)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showPin) androidx.compose.ui.text.input.VisualTransformation.None
                    else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                    ),
                    trailingIcon = {
                        androidx.compose.material3.IconButton(onClick = { showPin = !showPin }) {
                            Icon(
                                if (showPin) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = stringResourceCompat(
                                    if (showPin) R.string.pin_hide else R.string.pin_show
                                ),
                                tint = g.textSecondary
                            )
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = g.accent,
                        unfocusedBorderColor = g.border,
                        focusedTextColor = g.textPrimary,
                        unfocusedTextColor = g.textPrimary,
                        cursorColor = g.accent
                    )
                )
                BizField(p2, { p2 = it.filter { c -> c.isDigit() }.take(8) }, stringResourceCompat(R.string.pin_confirm))
                // وظيفة 37 — مقياس قوة الرمز: 4 مقاطع ملونة + نص المستوى (يظهر مع أول محرف)
                if (p1.isNotEmpty()) {
                    val lvl = com.superbiz.app.domain.PinStrength.level(p1)
                    val strengthColor = when (lvl) {
                        3 -> GreenDeep; 2 -> Amber; 1 -> RedDeep; else -> RedDeep
                    }
                    val strengthLabel = stringResourceCompat(
                        when (lvl) {
                            3 -> R.string.pin_strength_3; 2 -> R.string.pin_strength_2
                            1 -> R.string.pin_strength_1; else -> R.string.pin_strength_0
                        }
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        repeat(4) { i ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(
                                        if (i < lvl || (lvl == 0 && i == 0)) strengthColor
                                        else g.textSecondary.copy(alpha = 0.18f)
                                    )
                            )
                            if (i < 3) Spacer(Modifier.width(4.dp))
                        }
                    }
                    Text(
                        stringResourceCompat(R.string.pin_strength_hint, strengthLabel),
                        color = strengthColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                    )
                }
                errRes?.let { Text(stringResourceCompat(it), color = RedDeep, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                when {
                    // سياسة أقوى — 6 أرقام على الأقل
                    p1.length < 6 -> errRes = R.string.pin_min_length
                    p1 != p2 -> errRes = R.string.pin_mismatch
                    else -> {
                        settingsVM.setPin(p1)
                        onDone()
                    }
                }
            }) { Text(stringResourceCompat(R.string.save), color = g.accent, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDone) {
                Text(stringResourceCompat(R.string.cancel), color = g.textSecondary)
            }
        }
    )
}

/** أنواع التغييرات الأمنية التي تتطلب إعادة مصادقة بالرمز الحالي () */
private enum class ReAuthAction { REMOVE_PIN, CHANGE_PIN, BIO_OFF, WIPE_ALL, IMPORT_ALL }

/**
 * حوار إعادة المصادقة — يطلب الرمز الحالي قبل تطبيق أي تغيير أمني.
 * يمرّ عبر نفس verifyPin (بحدّ المحاولات نفسه) — الفشل المتكرر يقفل الحوار كشاشة القفل.
*/
@Composable
// [P36-BK] internal: بُسرقت من private لتُستخدم في مركز الإعدادات (BackupCard — خيار
// «الاستبدال الكامل» يمر عبر ReAuth نفسه بحارس H-3) — نفس الحزمة، لا تغيير في السلوك
internal fun ReAuthDialog(
    settingsVM: SettingsVM,
    message: String,
    onVerified: () -> Unit,
    onDismiss: () -> Unit
) {
    val g = glassColors()
    var pin by remember { mutableStateOf("") }
    var err by remember { mutableStateOf(false) }
    // [P20-FIX agent5] حارس التحقق الجاري
    var verifying by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = g.surfaceStrong,
        title = { Text(stringResourceCompat(R.string.reauth_title), color = g.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(message, color = g.textSecondary, fontSize = 13.sp)
                BizField(pin, { pin = it.filter { c -> c.isDigit() }.take(8) }, stringResourceCompat(R.string.pin_enter))
                if (err) Text(stringResourceCompat(R.string.pin_wrong), color = RedDeep, fontSize = 12.sp)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = !verifying, onClick = {
                // [P20-FIX agent5]: حارس التحقق المزدوج — نقرتان سريعتان كانتان تشغلان PBKDF2
                // مرتين وتُحسبان محاولتَي فشل وتستدعيان onVerified مرتين (نفس إصلاح LockScreen )
                verifying = true
                settingsVM.verifyPin(pin) { ok ->
                    verifying = false
                    if (ok) onVerified() else err = true
                }
            }) { Text(stringResourceCompat(R.string.confirm), color = g.accent, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResourceCompat(R.string.cancel), color = g.textSecondary)
            }
        }
    )
}

private fun android.graphics.Bitmap.scaleTo(maxSide: Int): android.graphics.Bitmap {
    val max = maxOf(width, height)
    if (max <= maxSide) return this
    val ratio = maxSide.toFloat() / max
    return android.graphics.Bitmap.createScaledBitmap(
        this, (width * ratio).toInt(), (height * ratio).toInt(), true
    )
}
