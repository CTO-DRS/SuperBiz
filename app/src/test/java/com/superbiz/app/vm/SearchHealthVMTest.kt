package com.superbiz.app.vm

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.AppGraph
import com.superbiz.app.R
import com.superbiz.app.data.db.CheckEntity
import com.superbiz.app.data.db.Expense
import com.superbiz.app.data.db.Invoice
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.db.Product
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [P37-T-E] جدران اختبارات طبقة VM (Robolectric) — GlobalSearchVM
 * (FeatureVMs.kt:2164-2255) وDataHealthVM (FeatureVMs.kt:2262-2409)
 * وFavoritesVM (FeatureVMs.kt:2420-2507) على قاعدة Room ملفية حقيقية لكل اختبار.
 *
 * ═══ نمط الجهاز الافتراضي (إلزامي) ═══
 * - @Config(application = android.app.Application::class) يمنع إقلاع SuperBizApp
 *   الحقيقي وقنواته وبذره — وتجنب تسريب AppGraph singleton عبر إعادة الصفّر بالانعكاس.
 * - حقل AppGraph.instance (SuperBizApp.kt:613) حقل نسخة على صنف الـcompanion الوحيد —
 *   يُصفَّر على كائن الـcompanion نفسه (set(null,…) على حقل نسخة يرمي NPE بعقد Field.set).
 * - Dispatchers.setMain(UnconfinedTestDispatcher()) في @Before — launchSafe يبدأ إدخالاً
 *   متحمساً — وresetMain في @After.
 * - الجمع بـ runBlocking + withTimeout + first{مسند يطابق الحالة النهائية فقط}.
 *
 * ═══ ملاحظات تواقيع موثقة من الكود الفعلي (بلا تعديل أي ملف رئيسي) ═══
 * - GlobalSearchVM.results عليه debounce(250) ثم flowOn(Dispatchers.Default) — فمؤقّت
 *   الـdebounce يعمل على مُجدّول Default الحقيقي (خارج زمن الاختبار الافتراضي) أي
 *   250ms حقيقية؛ ننتظرها بمسند + withTimeout بدل advanceUntilIdle.
 * - الدرجات الثابتة في الكود: باركود 0.99، هاتف 0.98، رقم فاتورة 0.97، رمز SKU 0.96،
 *   واحتواء نصي 0.95 — والحد الافتراضي searchFuzzyThreshold = 0.45 من الإعدادات.
 * - FavoritesVM: ترتيب «الأقرب أولاً» بحد ذاته يُنفَّذ في الشاشة عبر خوارزمية RouteOrder
 *   النقية (مغطاة في RouteOrderTest) — الـVM يحمل أعلام الحالة فقط
 *   (routeSortEnabled/capturingRouteLocation) ومسار Toggling. وفي Robolectric بلا
 *   أذونات، LocationCapture يسلّم Failed متزامناً على خيط الاختبار فلا يرتفع العلم.
 * - DataStore (الإعدادات): ملفه يلتقط مرة واحدة لكل عملية JVM (تفويض Context.dataStore
 *   الساكن)، ومجلدات Robolectric المؤقتة تبقى حتى خروج الـJVM (خطاف TempDirectory
 *   shutdown-hook) — فالكتابات متسقة طوال التشغيل؛ اختبار السجل يبدأ بمسح تحسّبياً
 *   لأي تلوث من اختبارات سابقة في نفس الـJVM.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class SearchHealthVMTest {

    // أزمنة صريحة حتمية
    private val T0 = 1_700_000_000_000L
    private val DAY = 86_400_000L

    private val testMain = UnconfinedTestDispatcher()
    private lateinit var app: Application
    private lateinit var g: AppGraph

    // ─── صفّر الـsingleton الرسمي قبل كل اختبار (انعكاس على الحقل الساكن) ───
    // Kotlin يجمّع var خاص في companion إلى حقل ساكن على الصنف الخارجي
    // (javap: private static volatile AppGraph instance — على AppGraph نفسها)
    private fun resetGraph() {
        val c = Class.forName("com.superbiz.app.AppGraph")
        val inst = c.getDeclaredField("instance")
        inst.isAccessible = true
        inst.set(null, null)
    }

    @Before
    fun setUp() {
        resetGraph()
        Dispatchers.setMain(testMain)
        app = ApplicationProvider.getApplicationContext<Application>()
        g = AppGraph.from(app)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ═════════ بذر مباشر عبر DAOs من نفس رسم الـVM ═════════

    private suspend fun seedParty(name: String, phone: String = "", type: Int = 0): Long =
        g.db.parties().upsert(Party(name = name, phone = phone, type = type))

    private suspend fun seedProduct(
        name: String, sku: String = "", barcode: String = "", sale: Long = 2500L
    ): Long = g.db.products().upsert(Product(name = name, sku = sku, barcode = barcode, salePrice = sale))

    private suspend fun seedInvoice(number: String, partyId: Long, total: Long = 10000L): Long =
        g.db.invoices().upsert(
            Invoice(number = number, partyId = partyId, type = 0, date = T0,
                dueDate = T0 + 14L * DAY, subtotal = total, total = total)
        )

    // ═════════ أنتظار الحالة النهائية ═════════

    // [P37-متينة]: كل اشتراك جديد يجبر Room flow على استعلام حالي — دورة الاشتراك
    // القصيرة المتكررة تُغلق سباق فقدان إشعار InvalidationTracker على مستضيفي CI
    // (عرَق بيئي لا عقد سلوكي). المهلة الكلية 60ث بمحاولات 10ث.
    private suspend fun awaitResults(
        vm: GlobalSearchVM, timeoutMs: Long = 60_000L,
        predicate: (List<GlobalSearchVM.Hit>) -> Boolean
    ): List<GlobalSearchVM.Hit> = withTimeout(timeoutMs) {
        while (true) {
            val hit = withTimeoutOrNull(10_000L) { vm.results.first { predicate(it) } }
            if (hit != null) return@withTimeout hit
        }
        @Suppress("UNREACHABLE_CODE") emptyList<GlobalSearchVM.Hit>()
    }

    private suspend fun scanAndWait(vm: DataHealthVM): DataHealthVM.HealthReport {
        vm.scan()
        withTimeout(30_000) { vm.scanning.first { !it } }
        return vm.report.value ?: error("انتهى المسح بلا تقرير — راجع ErrorCenter")
    }

    // ═══════════════════ GlobalSearchVM (FeatureVMs.kt:2164-2255) ═══════════════════

    // استعلام فارغ أو قصير جداً (< 2 بعد trim) لا نتائج له — حتى مع بيانات مطابقة موجودة
    @Test
    fun `بحث فارغ أو قصير جدا لا نتائج له`() = runBlocking {
        val vm = GlobalSearchVM(app)
        val pid = seedParty("مؤسسة النور")
        val prd = seedProduct("بروتين واي")
        seedInvoice("INV-1024", pid)
        vm.query.value = "بروتين"
        awaitResults(vm) { it.isNotEmpty() }              // البحث الحي يعمل
        vm.query.value = ""
        awaitResults(vm) { it.isEmpty() }                 // انتقال حقيقي إلى الفراغ
        vm.query.value = "ب"
        awaitResults(vm) { it.isEmpty() }                 // حرف واحد < الحد الأدنى
        Unit
    }

    // بحث منتج بالاسم — kind 0 وعنوانه اسم المنتج
    @Test
    fun `بحث منتج بالاسم يعطي ضربة من نوع منتج`() = runBlocking {
        val vm = GlobalSearchVM(app)
        val prd = seedProduct("بروتين واي")
        vm.query.value = "بروتين"
        val hits = awaitResults(vm) { it.any { h -> h.kind == 0 && h.id == prd } }
        val hit = hits.first { h -> h.kind == 0 && h.id == prd }
        assertEquals("بروتين واي", hit.title)
    }

    // مطابقة رمز SKU تمنح 0.96 حرفياً (FeatureVMs.kt:2213)
    @Test
    fun `مطابقة رمز SKU تمنح الدرجة 0_96`() = runBlocking {
        val vm = GlobalSearchVM(app)
        val prd = seedProduct("مكواة", sku = "SKU-77")
        vm.query.value = "SKU-77"
        val hits = awaitResults(vm) { it.any { h -> h.kind == 0 && h.id == prd } }
        assertEquals(0.96, hits.first { h -> h.id == prd }.score, 0.0)
    }

    // الباركود (0.99) يتصدّر على SKU (0.96) — الأولوية بالدرجة تنازلياً
    @Test
    fun `الباركود يتصد على مطابقة الرمز في الترتيب`() = runBlocking {
        val vm = GlobalSearchVM(app)
        val pBar = seedProduct("زيت", barcode = "6280000123456")
        val pSku = seedProduct("سكر", sku = "6280000123456A")
        vm.query.value = "6280000123456"
        val hits = awaitResults(vm) { it.size == 2 }
        assertEquals(pBar, hits[0].id)
        assertEquals(0.99, hits[0].score, 0.0)
        assertEquals(pSku, hits[1].id)
        assertEquals(0.96, hits[1].score, 0.0)
    }

    // بحث طرف بالاسم — kind 1 وشارة النوع «عميل» حين يخلو الهاتف
    @Test
    fun `بحث طرف بالاسم يعطي ضربة من نوع طرف بشاره عميل`() = runBlocking {
        val vm = GlobalSearchVM(app)
        val pid = seedParty("مؤسسة النور")
        vm.query.value = "النور"
        val hits = awaitResults(vm) { it.any { h -> h.kind == 1 && h.id == pid } }
        val hit = hits.first { h -> h.kind == 1 && h.id == pid }
        assertEquals("عميل", hit.subtitle)
    }

    // بحث بالهاتف: أرقام الاستعلام داخل أرقام الطرف (بعد digitsOnly) → 0.98
    @Test
    fun `بحث طرف بالهاتف اللاتيني يعطي الدرجة 0_98`() = runBlocking {
        val vm = GlobalSearchVM(app)
        val pid = seedParty("خالد", phone = "0501234567")
        vm.query.value = "0501234567"
        val hits = awaitResults(vm) { it.any { h -> h.kind == 1 && h.id == pid } }
        assertEquals(0.98, hits.first { h -> h.id == pid }.score, 0.0)
    }

    // الأرقام العربية-الهندية ٠-٩ تُطبَّع عبر digitsOnly قبل المطابقة (TextMath:236)
    @Test
    fun `بحث طرف بالأرقام العربية الهندية يطابق الهاتف`() = runBlocking {
        val vm = GlobalSearchVM(app)
        val pid = seedParty("خالد", phone = "0501234567")
        vm.query.value = "٠٥٠١٢٣٤٥٦٧"
        val hits = awaitResults(vm) { it.any { h -> h.kind == 1 && h.id == pid } }
        assertEquals(0.98, hits.first { h -> h.id == pid }.score, 0.0)
    }

    // بحث فاتورة برقمها — رقم يحوي الاستعلام → 0.97 (FeatureVMs.kt:2233)
    @Test
    fun `بحث فاتورة برقمها يعطي الدرجة 0_97`() = runBlocking {
        val vm = GlobalSearchVM(app)
        val pid = seedParty("مؤسسة النور")
        val inv = seedInvoice("INV-1024", pid)
        vm.query.value = "1024"
        val hits = awaitResults(vm) { it.any { h -> h.kind == 2 && h.id == inv } }
        val hit = hits.first { h -> h.kind == 2 && h.id == inv }
        assertEquals(0.97, hit.score, 0.0)
        assertEquals("INV-1024", hit.title)
    }

    // بحث فاتورة باسم طرفها — قش «الرقم + اسم الطرف» يطابق ويُدرج الفاتورة
    @Test
    fun `بحث فاتورة باسم الطرف يجدها مع الطرف معا`() = runBlocking {
        val vm = GlobalSearchVM(app)
        val pid = seedParty("مؤسسة النور")
        val inv = seedInvoice("INV-1024", pid)
        vm.query.value = "النور"
        val hits = awaitResults(vm) {
            it.any { h -> h.kind == 1 && h.id == pid } && it.any { h -> h.kind == 2 && h.id == inv }
        }
        assertTrue(hits.size >= 2)
    }

    // التطبيع العربي: الهمزات (أ/إ/آ→ا، ؤ/ئ) والتشكيل (U+064B..0652) والألف المقصورة
    // تُطابَق بدرجة 1.0 حين يتطابق المطبَّعان (TextMath.arabicNormalize:213)
    @Test
    fun `التطبيع العربي يطابق الهمزات والتشكيل والألف المقصورة`() = runBlocking {
        val vm = GlobalSearchVM(app)
        val prd = seedProduct("أقراص")
        val p1 = seedParty("أحمد المُهَنّد")
        val p2 = seedParty("علي حسن")
        vm.query.value = "اقراص"
        awaitResults(vm) { it.any { h -> h.kind == 0 && h.id == prd } }
        vm.query.value = "احمد المهند"
        awaitResults(vm) { it.any { h -> h.kind == 1 && h.id == p1 } }
        vm.query.value = "على حسن"
        awaitResults(vm) { it.any { h -> h.kind == 1 && h.id == p2 } }
        Unit
    }

    // سجل آخر عمليات البحث: توثيق الاستعلام، إحلال المكرر ورفعه للأمام، ثم المسح
    // (SettingsRepo.addRecentSearch:352 — الأحدث أولاً بسقف 8)
    @Test
    fun `سجل البحث يوثق الاستعلامات ويحل المكرر ثم يمحو`() = runBlocking {
        val vm = GlobalSearchVM(app)
        vm.clearRecent()                                   // تحصين من أي تلوث سابق بالـJVM
        withTimeout(20_000) { vm.recent.first { it.isEmpty() } }
        vm.record("بروتين")
        withTimeout(20_000) { vm.recent.first { it.isNotEmpty() } }
        vm.record("مؤسسة")
        withTimeout(20_000) { vm.recent.first { it.firstOrNull() == "مؤسسة" && it.size == 2 } }
        vm.record("بروتين")                                // مكرر → إحلال محل التكرار بالرأس
        withTimeout(20_000) { vm.recent.first { it.firstOrNull() == "بروتين" && it.size == 2 } }
        vm.clearRecent()
        withTimeout(20_000) { vm.recent.first { it.isEmpty() } }
        Unit
    }

    // ═══════════════════ DataHealthVM (FeatureVMs.kt:2262-2409) ═══════════════════

    // قاعدة نظيفة: بلا أي نتيجة خطيرة (severity 2) — بنفورد بعينة صغيرة يبقى للانتباه فقط
    @Test
    fun `مسح قاعدة نظيفة بلا أخطار خطيرة`() = runBlocking {
        val vm = DataHealthVM(app)
        val pid = seedParty("عميل نشط")
        seedProduct("منتج سليم")
        seedInvoice("INV-1", pid)
        val report = scanAndWait(vm)
        assertTrue(report.findings.none { it.severity == 2 })
        assertEquals(1, report.findings.first { it.kind == "benford" }.severity) // عينة < 30
        assertEquals(0, report.findings.first { it.kind == "gaps" }.severity)
    }

    // عدد السجلات المفحوصة = فواتير + أطراف + منتجات + مصروفات + شيكات + دفعات + خطط
    @Test
    fun `المسح يعد السجلات المفحوصة بدقة`() = runBlocking {
        val vm = DataHealthVM(app)
        val p1 = seedParty("عميل أول")
        seedParty("عميل ثان")
        seedProduct("منتج أ")
        seedProduct("منتج ب")
        seedInvoice("INV-1", p1, 10000L)
        seedInvoice("INV-2", p1, 12000L)
        seedInvoice("INV-3", p1, 15000L)
        g.db.expenses().insert(Expense(amount = 5000L, category = "إيجار", date = T0))
        g.db.checks().upsert(
            CheckEntity(number = "CH-1", partyId = p1, amount = 5000L,
                issueDate = T0, dueDate = T0 + 7 * DAY)
        )
        // 2 أطراف + 2 منتجات + 3 فواتير + 1 مصروف + 1 شيك = 9
        val report = scanAndWait(vm)
        assertEquals(9, report.scannedRecords)
    }

    // فجوة ترقيم فواتير بيع: 1001 ثم 1003 → فجوة [1002] بنوع gaps ودرجة انتباه
    @Test
    fun `فجوة ترقيم الفواتير تكتشف بنوع gaps`() = runBlocking {
        val vm = DataHealthVM(app)
        val pid = seedParty("عميل ترقيم")
        seedInvoice("INV-1001", pid)
        seedInvoice("INV-1003", pid)
        val report = scanAndWait(vm)
        val gaps = report.findings.first { it.kind == "gaps" }
        assertEquals(1, gaps.count)
        assertEquals(1, gaps.severity)
        assertTrue(gaps.detail.contains("1002"))
    }

    // قيم شاذة (مصروف صفر) → نتيجة بنوع anomaly بدرجة خطر 2
    @Test
    fun `القيم الشاذة ترفع خطر اثنين`() = runBlocking {
        val vm = DataHealthVM(app)
        val pid = seedParty("عميل شاذ")
        seedInvoice("INV-1", pid)
        g.db.expenses().insert(Expense(amount = 0L, category = "خطأ", date = T0))
        val report = scanAndWait(vm)
        val anomaly = report.findings.first { it.kind == "anomaly" }
        assertEquals(1, anomaly.count)
        assertEquals(2, anomaly.severity)
    }

    // علم scanning يرتفع فور بدء المسح (إدخال متحمس) ثم يهبط بالضبط مرة
    @Test
    fun `علم المسح يرتفع أثناء الفحص ويهبط بعده`() = runBlocking {
        val vm = DataHealthVM(app)
        seedParty("عميل علم")
        vm.scan()
        assertTrue("لم يرتفع علم المسح فور الاستدعاء", vm.scanning.value)
        withTimeout(30_000) { vm.scanning.first { !it } }
        assertFalse(vm.scanning.value)
    }

    // نص المشاركة يعكس النتائج الفعلية بعلامات ✗/!/✓ وبيانات المفحوص
    @Test
    fun `نص المشاركة يعرض علامة الخطر واسم النتيجة`() = runBlocking {
        val vm = DataHealthVM(app)
        val pid = seedParty("عميل مشاركة")
        seedInvoice("INV-1", pid)
        g.db.expenses().insert(Expense(amount = 0L, category = "خطأ", date = T0))
        scanAndWait(vm)
        val text = vm.shareText()
        assertTrue(text.isNotBlank())
        assertTrue("علامة الخطر ✗ غائبة عن نص المشاركة", text.contains("✗"))
        assertTrue(text.contains(app.getString(R.string.dh_share_anomaly)))
    }

    // أطراف متشابهة بعد التطبيع (ة↔ه) تُعد أزواج مكررة محتملة بدرجة انتباه
    @Test
    fun `الأطراف المتشابهة بعد التطبيع تكتشف`() = runBlocking {
        val vm = DataHealthVM(app)
        seedParty("فاطمة الزهراء")
        seedParty("فاطمه الزهراء")
        val report = scanAndWait(vm)
        val dup = report.findings.first { it.kind == "dup_parties" }
        assertEquals(1, dup.count)
        assertEquals(1, dup.severity)
    }

    // منتج مؤرشف وبمخزون → مخزون غير مرئي في البيع: arch_stock بدرجة انتباه
    @Test
    fun `المؤرشف بمخزون يكتشف بدرجة انتباه`() = runBlocking {
        val vm = DataHealthVM(app)
        seedParty("عميل أرشيف")
        g.db.products().upsert(
            Product(name = "مخفي بالجرد", stockQty = 5.0, archived = true)
        )
        val report = scanAndWait(vm)
        val arch = report.findings.first { it.kind == "arch_stock" }
        assertEquals(1, arch.count)
        assertEquals(1, arch.severity)
    }

    // ═══════════════════ FavoritesVM (FeatureVMs.kt:2420-2507) ═══════════════════

    // المفضلة تبدأ فارغة ويملؤها toggleFavorite — تدفق حي بلا refresh يدوي
    @Test
    fun `تفضيل طرف يظهر في قائمة المفضلة`() = runBlocking {
        val vm = FavoritesVM(app)
        seedParty("زينب")
        val p2 = seedParty("أمجد")
        seedParty("بدر")
        withTimeout(20_000) { vm.favorites.first { it.isEmpty() } }
        vm.toggleFavorite(p2, true)
        val favs = withTimeout(20_000) {
            vm.favorites.first { it.size == 1 && it.first().id == p2 }
        }
        assertEquals(p2, favs.first().id)
    }

    // إزالة التفضيل تُخرج الطرف من المفضلة (انتقال حقيقي من غير فارغة إلى فارغة)
    @Test
    fun `إزالة التفضيل تفرغ قائمة المفضلة`() = runBlocking {
        val vm = FavoritesVM(app)
        val p1 = seedParty("زينب")
        vm.toggleFavorite(p1, true)
        withTimeout(20_000) { vm.favorites.first { it.size == 1 } }
        vm.toggleFavorite(p1, false)
        withTimeout(20_000) { vm.favorites.first { it.isEmpty() } }
        Unit
    }

    // المفضلة مرتّبة بالاسم (استعلام DAO ORDER BY name) — «أمجد» قبل «زينب»
    @Test
    fun `قائمة المفضلة مرتبة بالاسم`() = runBlocking {
        val vm = FavoritesVM(app)
        val zainab = seedParty("زينب")
        val amjad = seedParty("أمجد")
        seedParty("بدر")
        vm.toggleFavorite(zainab, true)
        vm.toggleFavorite(amjad, true)
        val favs = withTimeout(20_000) {
            vm.favorites.first { it.size == 2 && it[0].name == "أمجد" && it[1].name == "زينب" }
        }
        assertEquals(2, favs.size)
    }

    // كل الأطراف (قسم «إضافة إلى المفضلة») تشمل المفضل وغير المفضل
    @Test
    fun `قائمة كل الأطراف تشمل الجميع`() = runBlocking {
        val vm = FavoritesVM(app)
        seedParty("زينب")
        seedParty("أمجد")
        seedParty("بدر")
        withTimeout(20_000) { vm.allParties.first { it.size == 3 } }
        Unit
    }

    // حفظ الإحداثيات — بطاقة الموقع الجغرافي تتحدث موضعياً عبر PartyDao
    @Test
    fun `حفظ الإحداثيات يخزن خطي الطول والعرض`() = runBlocking {
        val vm = FavoritesVM(app)
        val pid = seedParty("عميل موقع")
        vm.setLocation(pid, 24.7136, 46.6753)
        withTimeout(20_000) {
            vm.allParties.first {
                it.any { p -> p.id == pid && p.lat == 24.7136 && p.lng == 46.6753 }
            }
        }
        Unit
    }

    // تمرير null للإحداثيين يزيل الموقع من البطاقة
    @Test
    fun `تمرير معدوم يزيل الموقع`() = runBlocking {
        val vm = FavoritesVM(app)
        val pid = seedParty("عميل موقع")
        vm.setLocation(pid, 24.7136, 46.6753)
        withTimeout(20_000) { vm.allParties.first { it.any { p -> p.id == pid && p.lat != null } } }
        vm.setLocation(pid, null, null)
        withTimeout(20_000) {
            vm.allParties.first {
                it.any { p -> p.id == pid && p.lat == null && p.lng == null }
            }
        }
        Unit
    }

    // ترتيب «الأقرب أولاً»: الافتراضي معطل، والتعطيل الصريح آمن ومتكرر — الـVM أعلام فقط
    // (الترتيب الفعلي RouteOrder نقي في الشاشة ومغطى في RouteOrderTest)
    @Test
    fun `ترتيب الأقرب أولا معطل افتراضيا والتعطيل الصريح آمن`() = runBlocking {
        val vm = FavoritesVM(app)
        assertFalse(vm.routeSortEnabled.value)
        assertFalse(vm.capturingRouteLocation.value)
        vm.toggleRouteSort(false)
        vm.toggleRouteSort(false)   // متكرر بلا أثر
        assertFalse(vm.routeSortEnabled.value)
        assertFalse(vm.capturingRouteLocation.value)
    }

    // التفعيل بلا أذونات موقع: LocationCapture يسلّم Failed فوراً ومتزامناً (عقده:
    // لا يرمي أبداً) — العلم لا يرتفع ولا يبقى التقاط معلقاً (ErrorCenter يستقبل التحذير)
    @Test
    fun `تفعيل الترتيب بلا إذن موقع يفشل بأمان`() = runBlocking {
        val vm = FavoritesVM(app)
        seedParty("عميل بلا موقع")
        vm.toggleRouteSort(true)
        assertFalse(vm.routeSortEnabled.value)
        assertFalse(vm.capturingRouteLocation.value)
        vm.toggleRouteSort(false)   // تعطيل بعد الفشل — بلا انهيار
        assertFalse(vm.routeSortEnabled.value)
    }
}
