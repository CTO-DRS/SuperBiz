package com.superbiz.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.superbiz.app.R
import com.superbiz.app.data.db.Party
import com.superbiz.app.domain.algo.CsvKit
import com.superbiz.app.domain.algo.GeoMap
import com.superbiz.app.domain.algo.GeoPoint
import com.superbiz.app.domain.algo.MapLayout
import com.superbiz.app.domain.algo.PartyDistance
import com.superbiz.app.domain.algo.PartyGeo
import com.superbiz.app.domain.algo.sortPartiesByDistance
import com.superbiz.app.ui.components.BizField
import com.superbiz.app.ui.components.EmptyState
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.theme.Amber
import com.superbiz.app.ui.theme.Cyan
import com.superbiz.app.ui.theme.Vio
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.util.startIntentSafe
import com.superbiz.app.vm.FavoritesVM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [P12-a] ترويسات ملف تصدير CSV — عربية ثابتة في الشيفرة عمداً: هذه بنية ملف بيانات
 * يُفتح في Excel (وليست نص واجهة)، فتغييرها عبر الترجمة يغيّر أعمدة الملف لمن يستورده.
 * BOM يُسبق الملف عند الكتابة لكي تُفتح العربية صحيحة (راجع CsvKit.buildCsv).
 */
private val FAV_CSV_HEADERS = listOf("الاسم", "الهاتف", "النوع", "خط العرض", "خط الطول", "ملاحظة")

/** [P12-a] تسميات النوع في ملف CSV (0 عميل / 1 مورد / 2 كلاهما) — ثوابت ملف بيانات لا نصوص واجهة */
private const val CSV_TYPE_CUSTOMER = "عميل"
private const val CSV_TYPE_SUPPLIER = "مورد"
private const val CSV_TYPE_BOTH = "كلاهما"

/**
 * [P11-a] شاشة المفضّلات مع بطاقة الموقع الجغرافي — الدخول من رقاقة «المفضلات» في الذمم.
 *
 * قسمان: (1) بطاقة GeoCard لكل طرف مفضّل فيها خريطة مصغّرة مرسومة (Canvas) بدبوس
 * موضع مشتق إطراحياً من الإحداثيات نفسها + أزرار تحديد/فتح/إزالة الموقع وتسجيل زيارة،
 * و(2) قائمة «إضافة إلى المفضلة» مع بحث عربي مطبّع (نفس تطبيع جهات الاتصال).
 * [P12-a] التقاط الموقع كله عبر المحرك المشترك LocationCapture (تجربة/تقوية للأجهزة
 * الحقيقية: تمييز «خدمات الموقع معطّلة» عن الفشل العام، مؤقّت تقدم، منع الطلبات
 * المزدوجة) + تصدير المفضلات CSV عبر SAF بلا أي إذن تخزين + قسم تقرير الزيارات
 * (VisitsSection من الموجة P12) أسفل الشاشة. الإذن: ACCESS_FINE_LOCATION فقط —
 * لا شيء يُرسل خارج الجهاز.
 *
 * [P13-a] ترتيب «الأقرب أولاً»: رقاقة FilterChip أسفل الترويسة (الرأس مزدحم بالرجوع
 * والعنوان والتصدير) تُفعّل ترتيب البطاقات تصاعدياً بالمسافة عن موقع اللحظة —
 * الالتقاط عبر الـ VM (LocationCapture المشترك، والفشل عبر ErrorCenter)، وإعادة
 * الطلب تستخدم آخر موقع محفوظ بلا انتظار. كل بطاقة تعرض تسمية مسافتها
 * (PartyGeo.deltaLabel) أو «بلا موقع» للأطراف بلا إحداثيات. الإذن بنمط GeoCard
 * نفسه: فحص دقيق/تقريبي ثم طلب، وتعليل بعد أول رفض وإعدادات التطبيق بعد رفضين.
 */
@Composable
fun FavoritesScreen(vm: FavoritesVM, onBack: () -> Unit) {
    val g = glassColors()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val favorites by vm.favorites.collectAsState()
    val allParties by vm.allParties.collectAsState()
    var query by remember { mutableStateOf("") }

    // ─── [P13-a] ترتيب «الأقرب أولاً»: الحالة في الـ VM، والإذن بنمط GeoCard نفسه ───
    val routeSortEnabled by vm.routeSortEnabled.collectAsState()
    val routeLocation by vm.routeLocation.collectAsState()
    val capturingRoute by vm.capturingRouteLocation.collectAsState()
    var routeDeniedCount by remember { mutableStateOf(0) }

    val routePermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.toggleRouteSort(true)
        else routeDeniedCount++ // رفضان ⇒ رقاقة إعدادات التطبيق (أدناه)
    }

    // [P13-a] تفعيل الترتيب: إذن ممنوح (فحص دقيق/تقريبي — عقد LocationCapture: المستدعي
    // يطلب الإذن قبل الاستدعاء) ⇒ فعّل مباشرة، وإلا اطلب الإذن ثم أكمل
    fun requestRouteSort() {
        val fineGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (fineGranted || coarseGranted) {
            vm.toggleRouteSort(true)
        } else {
            routePermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    // ─── [P16-c] وضع «الخريطة الشاملة»: قائمة/خريطة (القائمة افتراضياً — صفر انحدار) ───
    var mapMode by rememberSaveable { mutableStateOf(false) }
    var mapSelectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val geoPoints = remember(favorites) {
        favorites.filter { PartyGeo.isValid(it.lat, it.lng) }
            .map { GeoPoint(it.id, it.lat!!, it.lng!!, it.name) }
    }
    // [P16-c] العرض الفعلي: الخريطة فقط مع مفضّلات أصلاً — وإن شُطبت كل الإحداثيات
    // أثناء وجود المستخدم في وضع الخريطة تبقى اللوحة معروضة بنص الفراغ الجديد (صدق الفراغ)
    val showMap = mapMode && favorites.isNotEmpty()

    // ─── [P12-a] تصدير المفضلات مع الإحداثيات إلى CSV — عبر SAF (CreateDocument) بلا أي إذن تخزين ───
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        // اللقطة تؤخذ لحظة التصدير (favorites قائمة StateFlow)
                        val rows = vm.favorites.value.map { p ->
                            listOf(
                                p.name,
                                p.phone,
                                when (p.type) {
                                    1 -> CSV_TYPE_SUPPLIER
                                    2 -> CSV_TYPE_BOTH
                                    else -> CSV_TYPE_CUSTOMER
                                },
                                p.lat?.toString() ?: "",
                                p.lng?.toString() ?: "",
                                p.note
                            )
                        }
                        val bytes = CsvKit.buildCsv(FAV_CSV_HEADERS, rows, bom = true)
                            .toByteArray(Charsets.UTF_8)
                        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                            out.write(bytes)
                        } != null
                    }.getOrDefault(false)
                }
                android.widget.Toast.makeText(
                    context,
                    context.getString(
                        if (ok) R.string.fav_exported_toast else R.string.fav_export_failed
                    ),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    BackHandler { onBack() }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ─── الترويسة: رجوع + عنوان + تصدير CSV ───
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                // [P11-a] AutoMirrored — نفس سهم الرجوع مع تصحيح الاتجاه في RTL (نمط SubHeader)
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResourceCompat(R.string.a11y_back), tint = g.textPrimary)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                stringResourceCompat(R.string.fav_title),
                style = MaterialTheme.typography.titleLarge,
                color = g.textPrimary
            )
            Spacer(Modifier.weight(1f))
            // [P12-a] تصدير CSV — مُحدِّد الملفات بالنظام (SAF)، صفر أذونات تخزين
            ActionPill(stringResourceCompat(R.string.fav_export_csv), Cyan) {
                csvLauncher.launch("superbiz-favorites.csv")
            }
        }

        // ─── [P13-a] صف أدوات الترتيب: رقاقة «الأقرب أولاً» + مؤقّت الالتقاط الصغير ───
        // (تجلس في صف مستقل أسفل الترويسة مباشرة — الرأس أعلاه مزدحم بالرجوع والعنوان والتصدير)
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = routeSortEnabled,
                onClick = { if (routeSortEnabled) vm.toggleRouteSort(false) else requestRouteSort() },
                label = { Text(stringResourceCompat(R.string.route_sort_chip)) }
            )
            if (capturingRoute) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Cyan
                )
                Text(
                    stringResourceCompat(R.string.fav_locating),
                    fontSize = 12.sp, color = g.textSecondary
                )
            }
        }
        // [P13-a] رفض مرتين ⇒ إعدادات التطبيق + تعليل بعد أول رفض (نمط GeoCard نفسه)
        if (routeDeniedCount >= 2) {
            ActionPill(stringResourceCompat(R.string.perm_open_settings), Cyan) {
                startIntentSafe(
                    context,
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                )
            }
        }
        if (routeDeniedCount >= 1) {
            Text(
                stringResourceCompat(R.string.fav_loc_rationale),
                fontSize = 11.sp, color = g.textSecondary, lineHeight = 16.sp
            )
        }

        // ─── [P16-c] مبدّل قائمة/خريطة — رقاقتان بنمط رقاقة «الأقرب أولاً» نفسها ───
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = !showMap,
                onClick = { mapMode = false },
                label = { Text(stringResourceCompat(R.string.map_list_toggle)) }
            )
            // الخريطة معطّلة بلا أي مفضّل بإحداثيات (تعليلها في السطر التالي)
            FilterChip(
                selected = showMap,
                enabled = geoPoints.isNotEmpty(),
                onClick = { mapMode = true; mapSelectedId = null },
                label = { Text(stringResourceCompat(R.string.map_view_btn)) }
            )
        }
        // [P16-c] تعليل رقاقة الخريطة المعطّلة — فقط إن كانت هناك مفضّلات بلا إحداثيات
        // (قائمة المفضلات فارغة كلها تغطيها حالة الفراغ في القسم 1 فلا تُكرَّر)
        if (geoPoints.isEmpty() && favorites.isNotEmpty()) {
            Text(
                stringResourceCompat(R.string.map_empty),
                fontSize = 11.sp, color = g.textSecondary, lineHeight = 16.sp
            )
        }

        // ─── القسم 1: بطاقات المفضّلين (أو الخريطة الشاملة في وضع الخريطة) ───
        if (favorites.isEmpty()) {
            EmptyState(stringResourceCompat(R.string.fav_empty), Icons.Rounded.Star)
        } else if (showMap) {
            // ─── [P16-c] وضع الخريطة: عنوان + النطاق + اللوحة + بطاقة المحدد ───
            val layout = remember(geoPoints) { GeoMap.project(geoPoints) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResourceCompat(R.string.map_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = g.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                // النطاق: «النطاق حوالي 12.3 كم» — تنسيق المسافة عبر PartyGeo.deltaLabel ذاتها
                val spanLabel = remember(layout.spanMeters) {
                    layout.spanMeters?.let { PartyGeo.deltaLabel(it) }
                }
                if (spanLabel != null) {
                    Text(
                        stringResourceCompat(R.string.map_span, spanLabel),
                        fontSize = 12.sp, color = g.textSecondary
                    )
                }
            }
            if (geoPoints.isNotEmpty()) {
                Text(
                    stringResourceCompat(R.string.map_hint),
                    fontSize = 11.sp, color = g.textSecondary
                )
            }
            // [P16-c] تحديد سارٍ فقط لنقطة ما زالت على الخريطة (إن شُطبت إحداثياتها يزول تحديدها)
            val selPoint = geoPoints.firstOrNull { it.id == mapSelectedId }
            GlassCard(Modifier.fillMaxWidth()) {
                FavoritesOverviewMap(geoPoints, layout, selPoint?.id) { mapSelectedId = it }
            }
            // بطاقة النقطة المحددة — الاسم + الإحداثيات + المسافة عن موقعي عند توفر مرجع.
            // القائمة أدناه عمود تمرير بلا آلية تمرير/تحديد لعنصر، فلا زر «عرض في القائمة»
            // ولا تنقّل مُختلق — الصدق: الاسم والمعلومات تكفي (عقد المهمة)
            val selected = selPoint?.let { sp -> favorites.firstOrNull { it.id == sp.id } }
            if (selected != null) {
                val sLat = selected.lat
                val sLng = selected.lng
                val myLoc = routeLocation
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResourceCompat(R.string.map_selected),
                            fontSize = 12.sp, color = Cyan, fontWeight = FontWeight.Bold
                        )
                        Text(
                            selected.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = g.textPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        if (sLat != null && sLng != null) {
                            Text(
                                PartyGeo.formatLatLng(sLat, sLng),
                                fontSize = 12.sp, color = g.textSecondary
                            )
                            // مسافة «الأقرب أولاً» بنفس مصدر RouteOrder (هافرساين PartyGeo)
                            if (routeSortEnabled && myLoc != null) {
                                Text(
                                    PartyGeo.deltaLabel(
                                        PartyGeo.haversineMeters(myLoc.first, myLoc.second, sLat, sLng)
                                    ),
                                    fontSize = 12.sp, color = Cyan, fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // [P13-a] عند تفعيل «الأقرب أولاً»: ترتيب تصاعدي بالمسافة (بلا إحداثيات في الذيل)؛
            // وإلا الترتيب الافتراضي كما هو دون أي تغيير — التغليف بلا نسخ (مراجع أصلية)
            val ordered = remember(favorites, routeSortEnabled, routeLocation) {
                if (routeSortEnabled) {
                    sortPartiesByDistance(favorites, routeLocation?.first, routeLocation?.second)
                } else {
                    favorites.map { PartyDistance(it, null, PartyGeo.isValid(it.lat, it.lng)) }
                }
            }
            ordered.forEach { item ->
                // تسمية المسافة تظهر فقط عند التفعيل مع مرجع صالح (صدق: بلا مرجع لا مسافة)
                val label = if (routeSortEnabled && routeLocation != null) {
                    item.distanceMeters?.let { PartyGeo.deltaLabel(it) }
                        ?: stringResourceCompat(R.string.route_no_location)
                } else null
                GeoCard(item.party, vm, label)
            }
        }

        // ─── القسم 2: إضافة إلى المفضلة (بأطراف غير مفضّلة) ───
        Text(
            stringResourceCompat(R.string.fav_add_section),
            style = MaterialTheme.typography.titleSmall,
            color = g.textPrimary
        )
        BizField(query, { query = it }, stringResourceCompat(R.string.fav_search_hint))
        Spacer(Modifier.height(2.dp))

        val candidates = remember(allParties, query) {
            val qn = normalizeArabicText(query.trim())
            val qd = normalizePhoneDigits(query.trim())
            allParties
                .filter { p ->
                    !p.favorite && !p.archived && (
                        qn.isBlank() ||
                            normalizeArabicText(p.name).contains(qn) ||
                            (qd.isNotBlank() && normalizePhoneDigits(p.phone).contains(qd))
                        )
                }
                .take(30) // [P11-a] حد عرض — الشاشة عمود قابل للتمرير لا LazyColumn
        }
        if (candidates.isEmpty()) {
            Text(
                stringResourceCompat(R.string.fav_empty),
                fontSize = 12.sp, color = g.textSecondary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                candidates.forEach { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(g.textSecondary.copy(alpha = 0.06f))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                p.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = g.textPrimary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            if (p.phone.isNotBlank()) {
                                Text(p.phone, style = MaterialTheme.typography.bodySmall, color = g.textSecondary)
                            }
                        }
                        IconButton(onClick = { vm.toggleFavorite(p.id, true) }) {
                            Icon(Icons.Rounded.StarBorder, stringResourceCompat(R.string.a11y_add_favorite), tint = Cyan)
                        }
                    }
                }
            }
        }

        // ─── [P12-a] القسم 3: تقرير زيارات العملاء (VisitsSection — موجة P12) ───
        // مكتفٍ بذاته (يحمّل بياناته عبر AppGraph داخلياً) — يُدمج أسفل كل شيء داخل العمود القابل للتمرير
        Spacer(Modifier.height(10.dp))
        VisitsSection()
    }
}

/**
 * [P11-a] بطاقة طرف مفضّل: اسم + هاتف + نجمة إزالة، خريطة مصغّرة مرسومة،
 * سطر الإحداثيات، وأزرار الموقع + تسجيل زيارة. عدّاد الرفض مثل لوحة جهات الاتصال:
 * رفضان → تظهر رقاقة «فتح الإعدادات» الوحيدة المتبقية لمنح الإذن.
 *
 * [P12-a] تجربة/تقوية الجهاز الحقيقي:
 * - كل الالتقاط عبر المحرك المشترك LocationCapture (حُذفت الدالة الخاصة captureLocation).
 * - فحص المزوّدات قبل الطلب: خدمات الموقع معطّلة → تلميح داخلي + رقاقة «فتح إعدادات
 *   الموقع» (بدل toast فشل عام)، وأي نتيجة GpsOff من المحرك تمرّ بنفس المسار.
 * - أثناء الالتقاط: مؤقّت تقدم 16dp + «جارٍ تحديد الموقع…» وتُعطَّل رقاقتا
 *   «تحديد الموقع» و«تسجيل زيارة» (منع الطلبات المزدوجة — المحرك بمهلة 20 ثانية).
 * - «تسجيل زيارة»: نفس مسار الالتقاط، وعند النجاح تُسجَّل الزيارة عبر طبقة
 *   الزيارات (AppGraph.visits — موجة P12) على Dispatchers.IO.
 *
 * [P13-a] تسمية المسافة عن موقعي (وسيط routeDistanceLabel) تظهر تحت سطر الإحداثيات
 * عند تفعيل «الأقرب أولاً» فقط — تُحسب في الشاشة الأم وتُمرَّر جاهزة (بلا حساب في كل بطاقة).
 */
@Composable
private fun GeoCard(party: Party, vm: FavoritesVM, routeDistanceLabel: String? = null) {
    val g = glassColors()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lat = party.lat
    val lng = party.lng
    val hasLoc = PartyGeo.isValid(lat, lng)
    var deniedCount by remember(party.id) { mutableStateOf(0) }
    // [P12-a] جارٍ التحديد؟ — يمنع النقر المزدوج ويعرض مؤقّت التقدم
    var locating by remember(party.id) { mutableStateOf(false) }
    // [P12-a] تلميح «خدمات الموقع معطّلة» — يُظهر فحصاً استباقياً أو من نتيجة GpsOff
    var gpsOffHint by remember(party.id) { mutableStateOf(false) }
    // [P12-a] الإجراء المعلّق حتى منح الإذن (تحديد الموقع أو تسجيل زيارة)
    var pendingAfterPermission by remember(party.id) { mutableStateOf<((Double, Double) -> Unit)?>(null) }

    fun toast(res: Int) {
        android.widget.Toast.makeText(
            context, context.getString(res), android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    // [P12-a] الالتقاط بعد تأكيد الإذن: فحص المزوّدات → تلميح الإعدادات أو المحرك المشترك
    fun proceedCapture(onFixed: (Double, Double) -> Unit) {
        if (!LocationCapture.isAnyProviderEnabled(context)) {
            gpsOffHint = true
            return
        }
        gpsOffHint = false
        locating = true
        LocationCapture.capture(context) { r ->
            // النتيجة تصل على الخيط الرئيسي (عقد LocationCapture) — تحديث الحالة آمن
            locating = false
            when (r) {
                is LocationCapture.Result.Fixed -> {
                    gpsOffHint = false
                    onFixed(r.lat, r.lng)
                }
                LocationCapture.Result.GpsOff -> gpsOffHint = true
                LocationCapture.Result.Failed -> toast(R.string.fav_location_failed)
            }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val pending = pendingAfterPermission
        pendingAfterPermission = null
        if (ok && pending != null) proceedCapture(pending)
        else if (!ok) deniedCount++ // رفض → عدّاد النمط نفسه (رفضان → رقاقة إعدادات التطبيق)
    }

    // [P12-a] نقطة الدخول الموحدة للرقاقتين: الإذن أولاً (عقد LocationCapture:
    // المستدعي يطلب الإذن قبل الاستدعاء) ثم فحص المزوّدات والالتقاط
    fun requestAndCapture(onFixed: (Double, Double) -> Unit) {
        val fineGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (fineGranted || coarseGranted) {
            proceedCapture(onFixed)
        } else {
            pendingAfterPermission = onFixed
            permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // رأس البطاقة: الاسم/الهاتف + نجمة الإزالة من المفضلة
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        party.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = g.textPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (party.phone.isNotBlank()) {
                        Text(party.phone, style = MaterialTheme.typography.bodySmall, color = g.textSecondary)
                    }
                }
                IconButton(onClick = { vm.toggleFavorite(party.id, false) }) {
                    Icon(Icons.Rounded.Star, stringResourceCompat(R.string.a11y_remove_favorite), tint = Amber)
                }
            }

            MiniMap(hasLoc, lat, lng)

            if (hasLoc && lat != null && lng != null) {
                Text(
                    PartyGeo.formatLatLng(lat, lng),
                    fontSize = 12.sp, color = g.textSecondary
                )
            }

            // [P13-a] تسمية المسافة عن موقعي عند تفعيل «الأقرب أولاً» (أو «بلا موقع»)
            if (routeDistanceLabel != null) {
                Text(
                    routeDistanceLabel,
                    fontSize = 12.sp, color = Cyan, fontWeight = FontWeight.Bold
                )
            }

            // [P12-a] مؤقّت التقدم أثناء الالتقاط (تغذية راجعة على الجهاز الحقيقي —
            // قد تستغرق قراءة GPS الثابتة ثوانٍ)
            if (locating) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Cyan
                    )
                    Text(
                        stringResourceCompat(R.string.fav_locating),
                        fontSize = 12.sp, color = g.textSecondary
                    )
                }
            }

            // [P12-a] تلميح خدمات الموقع المعطّلة + رقاقة فتح إعدادات الموقع (عنبري)
            if (gpsOffHint) {
                Text(
                    stringResourceCompat(R.string.fav_gps_off),
                    fontSize = 12.sp, color = Amber, lineHeight = 16.sp
                )
                ActionPill(stringResourceCompat(R.string.fav_open_location_settings), Amber) {
                    startIntentSafe(
                        context,
                        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    )
                }
            }

            // أزرار الموقع — صف أفقي قابل للتمرير على الشاشات الضيقة
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                // [P12-a] تحديد الموقع — عبر المحرك المشترك، معطّلة أثناء جارٍ التحديد
                ActionPill(
                    stringResourceCompat(R.string.fav_pick_location),
                    Cyan,
                    enabled = !locating
                ) {
                    requestAndCapture { la, ln ->
                        vm.setLocation(party.id, la, ln)
                        toast(R.string.fav_location_saved)
                    }
                }
                // [P12-a] تسجيل زيارة — نفس مسار الالتقاط، والنجاح يُسجَّل في طبقة الزيارات
                ActionPill(
                    stringResourceCompat(R.string.fav_log_visit),
                    Vio,
                    enabled = !locating
                ) {
                    requestAndCapture { la, ln ->
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    // طبقة الزيارات (VisitsRepo — موجة P12) على IO
                                    com.superbiz.app.AppGraph.from(context.applicationContext)
                                        .visits.recordVisit(party.id, la, ln, "")
                                }
                            }
                            toast(R.string.visit_logged_toast)
                        }
                    }
                }
                if (hasLoc && lat != null && lng != null) {
                    ActionPill(stringResourceCompat(R.string.fav_open_maps), Vio) {
                        startIntentSafe(
                            context,
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(PartyGeo.geoUri(lat, lng, party.name))
                            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        )
                    }
                    ActionPill(stringResourceCompat(R.string.fav_clear_location), g.textSecondary) {
                        vm.setLocation(party.id, null, null)
                    }
                }
                // رفض مرتين — إعدادات التطبيق هي السبيل الوحيد (نمط ContactsSheet)
                if (deniedCount >= 2) {
                    ActionPill(stringResourceCompat(R.string.perm_open_settings), Cyan) {
                        startIntentSafe(
                            context,
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)
                            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        )
                    }
                }
            }
            if (deniedCount >= 1) {
                Text(
                    stringResourceCompat(R.string.fav_loc_rationale),
                    fontSize = 11.sp, color = g.textSecondary, lineHeight = 16.sp
                )
            }
        }
    }
}

// [P36-M4-1] LockableActionPill أُحيل: ActionPill أصبحت مفوّضاً للذرة الموحدة BizPill
// بمعامل enabled أصلاً، فلا حاجة لنسخة خاصة بعد الآن (حُذف التعريف ونقلت المواضع).
/**
 * [P11-a] الخريطة المصغّرة — Canvas نقّي بلا أي مكتبة خرائط:
 * خلفية داكنة + شبكة كل 20dp + 3 طرق زخرفية، ثم عند توفر إحداثيات صالحة:
 * دائرة دقة (18dp، سماوي شفاف) ودبوس (مثلث + رأس دائري) يُزاح إطراحياً
 * بدالة نقية من الإحداثيات نفسها — النقطة نفسها تسقط دائماً في الموضع نفسه.
 * بلا موقع: شبكة فقط + «لا موقع بعد» (صدق الفراغ).
 */
@Composable
private fun MiniMap(hasLocation: Boolean, lat: Double?, lng: Double?) {
    val g = glassColors()
    Box(
        Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(14.dp))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color(0xFF0F172A)) // خلفية خريطة داكنة (نمط ليلي)
            val grid = g.textSecondary.copy(alpha = 0.18f)
            val road = g.textSecondary.copy(alpha = 0.35f)
            val step = 20.dp.toPx()
            var x = 0f
            while (x <= size.width) {
                drawLine(grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                x += step
            }
            var y = 0f
            while (y <= size.height) {
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                y += step
            }
            // طرق زخرفية حتمية (أثخن قليلاً وشفافية أعلى من الشبكة)
            drawLine(road, Offset(0f, size.height * 0.35f), Offset(size.width, size.height * 0.35f), strokeWidth = 6.dp.toPx())
            drawLine(road, Offset(size.width * 0.62f, 0f), Offset(size.width * 0.62f, size.height), strokeWidth = 5.dp.toPx())
            drawLine(road, Offset(0f, size.height * 0.85f), Offset(size.width, size.height * 0.55f), strokeWidth = 4.dp.toPx())

            val la = lat
            val ln = lng
            if (hasLocation && PartyGeo.isValid(la, ln) && la != null && ln != null) {
                val pin = pinOffset(la, ln, size.width, size.height)
                // دائرة الدقة
                drawCircle(Cyan.copy(alpha = 0.30f), radius = 18.dp.toPx(), center = pin, style = Stroke(2.dp.toPx()))
                // مثلث ذيل الدبوس
                val tail = Path().apply {
                    moveTo(pin.x - 6.dp.toPx(), pin.y + 5.dp.toPx())
                    lineTo(pin.x + 6.dp.toPx(), pin.y + 5.dp.toPx())
                    lineTo(pin.x, pin.y + 18.dp.toPx())
                    close()
                }
                drawPath(tail, Cyan)
                // رأس الدبوس
                drawCircle(Cyan, radius = 8.dp.toPx(), center = pin)
                drawCircle(Color.White.copy(alpha = 0.85f), radius = 3.dp.toPx(), center = pin)
            }
        }
        if (!hasLocation) {
            Text(
                stringResourceCompat(R.string.fav_no_location),
                Modifier.align(Alignment.Center),
                fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f)
            )
        }
    }
}

/**
 * [P16-c] الخريطة الشاملة للمفضّلات — Canvas واحد بلا أي مكتبة خرائط يعرض كل
 * الأطراف ذات الإحداثيات بمواضع GeoMap.project (إسقاط إيزومتري بنكماش cos(lat0)،
 * الشمال أعلى والشرق يمين): خلفية ليلية بنمط MiniMap نفسه + شبكة خفيفة، وكل نقطة
 * دائرة سماوية مع الحرف الأول من اسم الطرف فوقها (drawText عبر TextMeasurer —
 * تداخل التسميات مقبول عمداً: خريطة نظرة عامة لا مرجع عناوين).
 *
 * - النقطة المحددة: هالة عنبرية + فقاعة الاسم الكامل فوقها (تُقصّ داخل حدود اللوحة).
 * - النقر: أقرب موضع إسقاط بالبكسل ضمن عتبة 48dp (عتبة مسكة شاشة لا مسألة جغرافيا —
 *   لذا لا يُستخدم GeoMap.nearest المتري هنا؛ ذاك لترتيب الموقع مستقبلاً).
 *   النقر على فراغ يلغي التحديد (onSelect(null)).
 * - لا نقاط: الشبكة فقط + نص الفراغ (صدق الفراغ) — لا تُستدعى أصلاً بلا نقاط
 *   من الشاشة الأم (showMap)، والفرع دفاعي إضافي.
 */
@Composable
private fun FavoritesOverviewMap(
    points: List<GeoPoint>,
    layout: MapLayout,
    selectedId: Long?,
    onSelect: (Long?) -> Unit
) {
    val g = glassColors()
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    // قياسات النص مسبقاً خارج الرسم (لا قياس داخل كل إطار) — القياس بالبكسل نفسه
    // (نفس كثافة التكوين)، وإعادة القياس عند تغيّر النقاط أو الكثافة فقط
    val labelLayouts = remember(points, density) {
        val s = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.9f))
        points.associate { it.id to textMeasurer.measure(it.label.take(1), s) }
    }
    val nameLayouts = remember(points, density) {
        val s = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
        points.associate { it.id to textMeasurer.measure(it.label, s) }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(340.dp)
            .clip(RoundedCornerShape(16.dp))
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(layout) {
                    detectTapGestures { tap ->
                        // أقرب نقطة إسقاط بالبكسل ضمن عتبة 48dp — «<» الصارمة تبقي الأول عند التعادل
                        val thresholdPx = 48.dp.toPx()
                        var bestId: Long? = null
                        var bestD2 = thresholdPx * thresholdPx
                        for ((id, pos) in layout.positions) {
                            val dx = pos.x * size.width - tap.x
                            val dy = pos.y * size.height - tap.y
                            val d2 = dx * dx + dy * dy
                            if (d2 < bestD2) {
                                bestD2 = d2
                                bestId = id
                            }
                        }
                        onSelect(bestId)
                    }
                }
        ) {
            drawRect(Color(0xFF0F172A)) // خلفية خريطة ليلية — نمط MiniMap نفسه
            // شبكة خفيفة بلون النص الثانوي شبه الشفاف (جمالية GlassCard نفسها)
            val grid = g.textSecondary.copy(alpha = 0.15f)
            val step = 24.dp.toPx()
            var gx = 0f
            while (gx <= size.width) {
                drawLine(grid, Offset(gx, 0f), Offset(gx, size.height), strokeWidth = 1.dp.toPx())
                gx += step
            }
            var gy = 0f
            while (gy <= size.height) {
                drawLine(grid, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1.dp.toPx())
                gy += step
            }

            val dot = 5.dp.toPx()
            for ((id, pos) in layout.positions) {
                val c = Offset(pos.x * size.width, pos.y * size.height)
                if (id == selectedId) {
                    // المحددة: هالة عنبرية + نقطة
                    drawCircle(Amber.copy(alpha = 0.35f), radius = dot * 2.6f, center = c)
                    drawCircle(Amber, radius = dot, center = c)
                    drawCircle(Color.White.copy(alpha = 0.9f), radius = dot * 0.45f, center = c)
                    // فقاعة الاسم الكامل فوق النقطة (تُقصّ داخل حدود اللوحة بلا خروج)
                    val name = nameLayouts[id]
                    if (name != null && name.size.width > 0) {
                        val padH = 8.dp.toPx()
                        val padV = 4.dp.toPx()
                        val bw = name.size.width + padH * 2f
                        val bh = name.size.height + padV * 2f
                        val topLeft = Offset(
                            (c.x - bw / 2f).coerceIn(0f, (size.width - bw).coerceAtLeast(0f)),
                            (c.y - dot * 2.6f - bh - 6.dp.toPx()).coerceIn(0f, (size.height - bh).coerceAtLeast(0f))
                        )
                        drawRoundRect(
                            Color(0xE6161F3D), // سطح داكن شبه معتم — مقروء فوق أي خلفية ليلية
                            topLeft = topLeft,
                            size = Size(bw, bh),
                            cornerRadius = CornerRadius(10.dp.toPx())
                        )
                        drawRoundRect(
                            g.border,
                            topLeft = topLeft,
                            size = Size(bw, bh),
                            cornerRadius = CornerRadius(10.dp.toPx()),
                            style = Stroke(1.dp.toPx())
                        )
                        drawText(name, topLeft = topLeft + Offset(padH, padV))
                    }
                } else {
                    drawCircle(Cyan, radius = dot, center = c)
                    drawCircle(Color.White.copy(alpha = 0.85f), radius = dot * 0.4f, center = c)
                    val ch = labelLayouts[id]
                    if (ch != null && ch.size.width > 0) {
                        drawText(
                            ch,
                            topLeft = Offset(
                                c.x - ch.size.width / 2f,
                                c.y - dot - ch.size.height - 2.dp.toPx()
                            )
                        )
                    }
                }
            }
        }
        if (points.isEmpty()) {
            Text(
                stringResourceCompat(R.string.map_empty),
                Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f),
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * [P11-a] موضع الدبوس داخل الخريطة المصغّرة — مشتق إطراحياً (حتمي) من الإحداثيات:
 * الكسر العشري من (خط العرض × 1000) يحدد الأفق ضمن 20%..80% من العرض،
 * وخط الطول بالمثل ضمن 25%..75% من الارتفاع. دالة نقية قابلة للاختبار بالحساب.
 */
private fun pinOffset(lat: Double, lng: Double, w: Float, h: Float): Offset {
    val fx = (((lat * 1000.0).mod(1.0)).takeUnless { it.isNaN() } ?: 0.5).toFloat().coerceIn(0f, 1f)
    val fy = (((lng * 1000.0).mod(1.0)).takeUnless { it.isNaN() } ?: 0.5).toFloat().coerceIn(0f, 1f)
    return Offset(w * (0.2f + 0.6f * fx), h * (0.25f + 0.5f * fy))
}
