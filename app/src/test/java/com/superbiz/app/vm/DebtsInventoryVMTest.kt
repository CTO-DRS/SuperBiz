package com.superbiz.app.vm

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.AppGraph
import com.superbiz.app.core.ErrorCenter
import com.superbiz.app.core.ErrorLevel
import com.superbiz.app.data.db.Invoice
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.db.Product
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [P37-T-E] جدران اختبارات طبقة VM (Robolectric) — DebtsVM (FeatureVMs.kt:45-313)
 * وInventoryVM (FeatureVMs.kt:549-792) على قاعدة Room ملفية حقيقية تُنشأ لكل اختبار
 * Robolectric على حدة، وبيانات مُبذَّرة عبر AppGraph.from(app) مباشرة (DAOs/مستودعات
 * نفس التي يستخدمها الـVM).
 *
 * ═══ نمط الجهاز الافتراضي (إلزامي لتجنب تمهيد SuperBizApp وتسريب الـsingleton) ═══
 * - @Config(application = android.app.Application::class) يمنع إقلاع SuperBizApp الحقيقي
 *   (قنوات الإشعارات/بذر الإقلاع/appScope) — التطبيق هنا مجرد Application ناضف.
 * - صفّر AppGraph.instance بانهكاس في @Before قبل أي استخدام: الحقل `instance` في
 *   companion هو حقل نسخة على صنف AppGraph$Companion الوحيد (وليس ثابتاً ساكنة)،
 *   فالإغلاق الصحيح Field.set(comp, null) على كائن الـcompanion نفسه — محاولة
 *   set(null, …) على حقل نسخة ترمي NullPointerException بعقد java.lang.reflect.Field.
 *   (ملف رئيسي SuperBizApp.kt:613 — وثّقنا الفرق هنا ولم نلمسه.)
 * - Dispatchers.setMain(UnconfinedTestDispatcher()) يجعل viewModelScope.launch يعمل
 *   بإدخال متحمس على خيط الاختبار (launchSafe يبدأ فوراً حتى أول تعليق) —
 *   وresetMain في @After كي لا يتسرب المبدّل لاختبارات أخرى.
 * - الجمع من StateFlow بـ runBlocking + withTimeout + first{مسند}: مسند يطابق الحالة
 *   النهائية فقط (لا وسيطة) فيتقارب دائماً بغضّ النظر عن توقيت انبعاثات Room/الدفعات،
 *   وفشله = TimeoutCancellationException نظيف لا تعليق.
 * - WidgetSync.push (يستدعيه onMutate بعد كل قيد) ينشر على mainLooper الموقوف في
 *   Robolectric فلا يُنفَّذ خلال الاختبار — لا رسم ودجات ولا تداخل.
 *
 * ═══ ملاحظات تواقيع موثقة من الكود الفعلي (بلا تعديل أي ملف رئيسي) ═══
 * - [P33-P8] كل المبالغ قروش Long: الرصيد/الكشف/الصحة كلها Long ومساواة تامة —
 *   لا Double في أي تأكيد مالي هنا.
 * - DebtsVM.addDebt/addPayment تستقبل ريالاً Double وتحوّل عبر Money.toPiasters على
 *   الحدود الوحيدة — لذا نمرر قيماً ريالية قابلة للتحويل القشري تماماً (500.0 → 50000).
 * - InventoryVM.search بلا debounce (combine مباشر) — الـdebounce الوحيد في المشروع
 *   داخل GlobalSearchVM وثيقته في SearchHealthVMTest.
 * - InventoryVM.filtered يستخدم تطبيع/تشابه BizMath (domain.analytics) لا TextMath.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class DebtsInventoryVMTest {

    // أزمنة صريحة حتمية — T0 ثابت (2023-11) والأعمار تُشتق منه بضربات يوم ثابتة
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

    // ═════════ بذر البيانات عبر نفس الرسم الذي يستخدمه الـVM ═════════

    private suspend fun newParty(name: String, type: Int = 0): Party {
        val id = g.ledger.saveParty(Party(name = name, type = type))
        return g.ledger.party(id)!!
    }

    // [P33-P8] cost/sale قروش Long — stock/reorder كميات Double
    private suspend fun newProduct(
        name: String, sku: String = "", barcode: String = "",
        stock: Double = 10.0, reorder: Double = 0.0,
        cost: Long = 2000L, sale: Long = 3000L, archived: Boolean = false
    ): Long = g.db.products().upsert(
        Product(name = name, sku = sku, barcode = barcode, stockQty = stock,
            reorderLevel = reorder, costPrice = cost, salePrice = sale, archived = archived)
    )

    private suspend fun newInvoice(number: String, partyId: Long, total: Long, date: Long): Long =
        g.db.invoices().upsert(
            Invoice(number = number, partyId = partyId, type = 0, date = date,
                dueDate = date + 14L * DAY, subtotal = total, total = total)
        )

    // ═════════ أنتظار الحالة النهائية — مسند يطابق النهاية فقط ═════════

    // [P37-متينة]: كل اشتراك جديد يجبر Room flow على استعلام حالي — لذا دورة
    // الاشتراك القصيرة المتكررة تُغلق سباق فقدان إشعار InvalidationTracker على
    // مستضيفي CI (فشل ظرفي ظهر على debug وأخفق، ونجح على JDK17-release لنفس
    // الاختبار — عرَق بيئي لا عقد سلوكي). المهلة الكلية 60ث بمحاولات 10ث.
    private suspend fun awaitRows(
        vm: DebtsVM, timeoutMs: Long = 60_000L,
        predicate: (List<DebtsVM.RowUi>) -> Boolean
    ): List<DebtsVM.RowUi> = withTimeout(timeoutMs) {
        while (true) {
            val hit = withTimeoutOrNull(10_000L) { vm.rows.first { predicate(it) } }
            if (hit != null) return@withTimeout hit
            // انتهت المحاولة بلا مطابقة — الاشتراك التالي يعيد استعلام Room الحالي
        }
        @Suppress("UNREACHABLE_CODE") emptyList<DebtsVM.RowUi>()
    }

    private suspend fun awaitFiltered(
        vm: InventoryVM, timeoutMs: Long = 60_000L,
        predicate: (List<Product>) -> Boolean
    ): List<Product> = withTimeout(timeoutMs) {
        while (true) {
            val hit = withTimeoutOrNull(10_000L) { vm.filtered.first { predicate(it) } }
            if (hit != null) return@withTimeout hit
            // مهلة المحاولة انتهت بلا مطابقة — الاشتراك التالي يعيد استعلام Room الحالي
        }
        @Suppress("UNREACHABLE_CODE") emptyList<Product>()
    }

    private fun idsOf(rows: List<DebtsVM.RowUi>): List<Long> = rows.map { it.party.id }

    // ═══════════════════ DebtsVM — الفلاتر (0/1/2) ═══════════════════

    // FeatureVMs.kt:62-69 — filter 0 الكل يعرض العملاء والموردين معاً (ومن نوع 2)
    @Test
    fun `فلتر الصفر الكل يعرض كل الأطراف`() = runBlocking {
        val vm = DebtsVM(app)
        val a = newParty("عميل أ", type = 0)
        val s = newParty("مورد س", type = 1)
        val m = newParty("شريك م", type = 2)
        vm.refresh()
        val rows = awaitRows(vm) { idsOf(it).toSet() == setOf(a.id, s.id, m.id) }
        assertEquals(3, rows.size)
    }

    // filter 1: isCustomer = type 0 أو 2 — المورد الصرف (type 1) يستبعد
    @Test
    fun `فلتر الواحد يعرض العملاء فقط ويستبعد المورد`() = runBlocking {
        val vm = DebtsVM(app)
        val a = newParty("عميل أ", type = 0)
        val s = newParty("مورد س", type = 1)
        val m = newParty("شريك م", type = 2)
        vm.filter.value = 1
        vm.refresh()
        val rows = awaitRows(vm) { it.size == 2 && it.all { r -> r.party.isCustomer } }
        assertEquals(setOf(a.id, m.id), idsOf(rows).toSet())
        assertTrue(rows.none { it.party.id == s.id })
    }

    // filter 2: isSupplier = type 1 أو 2 — العميل الصرف (type 0) يستبعد
    @Test
    fun `فلتر الاثنين يعرض الموردين فقط ويستبعد العميل`() = runBlocking {
        val vm = DebtsVM(app)
        val a = newParty("عميل أ", type = 0)
        val s = newParty("مورد س", type = 1)
        val m = newParty("شريك م", type = 2)
        vm.filter.value = 2
        vm.refresh()
        val rows = awaitRows(vm) { it.size == 2 && it.all { r -> r.party.isSupplier } }
        assertEquals(setOf(s.id, m.id), idsOf(rows).toSet())
        assertTrue(rows.none { it.party.id == a.id })
    }

    // ═══════════════════ DebtsVM — أوضاع الفرز الأربعة (DebtSort) ═══════════════════
    // بيانات مرجعية موحدة: ب=30000 ، د=-25000 (عميل سدد زائداً) ، ج=20000 ، أ=10000
    // الترتيبات الأربعة مختلفة زوجياً فكل اختبار يميّز وضعَه فعلاً:
    //   0 المطلق: [ب, د, ج, أ] | 2 الرصيد: [ب, ج, أ, د] | 1 الأقدمية: [أ, ج, ب, د] | 3 الاسم: أبجدي

    private suspend fun seedSortParties(): Map<String, Party> {
        val a = newParty("عميل أ")
        val b = newParty("عميل ب")
        val j = newParty("عميل ج")
        val d = newParty("عميل د")
        val now = System.currentTimeMillis()
        g.ledger.addDebt(a, 10000L, now, "")
        g.ledger.addDebt(b, 30000L, now, "")
        g.ledger.addDebt(j, 20000L, now, "")
        g.ledger.addDebt(d, 10000L, now, "")
        // د سدد زائداً 350 ليصبح رصيده -25000 (دائن) — فرق الرصيد المطلق عن الرصيد الموجب
        g.ledger.addPayment(d, 35000L, now, 0, "CASH")
        return mapOf("أ" to a, "ب" to b, "ج" to j, "د" to d)
    }

    // MODE_DEFAULT (0): |الرصيد| تنازلياً — الدائن ذو الرصيد المطلق الكبير يتقدم
    @Test
    fun `الفرز الافتراضي يرتب بأعلى رصيد مطلق فيتقدم الدائن الكبير`() = runBlocking {
        val vm = DebtsVM(app)
        val p = seedSortParties()
        vm.sortMode.value = com.superbiz.app.domain.DebtSort.MODE_DEFAULT
        vm.refresh()
        val rows = awaitRows(vm) {
            idsOf(it) == listOf(p["ب"]!!.id, p["د"]!!.id, p["ج"]!!.id, p["أ"]!!.id)
        }
        assertEquals(30000L, rows[0].balance)
        assertEquals(-25000L, rows[1].balance)
    }

    // MODE_BALANCE (2): الرصيد الموجب تنازلياً — الدائن (-25000) يهبط للذيل
    @Test
    fun `فرز أعلى رصيد يضع المدين الأكبر أولا والدائن آخرا`() = runBlocking {
        val vm = DebtsVM(app)
        val p = seedSortParties()
        vm.sortMode.value = com.superbiz.app.domain.DebtSort.MODE_BALANCE
        vm.refresh()
        val rows = awaitRows(vm) {
            idsOf(it) == listOf(p["ب"]!!.id, p["ج"]!!.id, p["أ"]!!.id, p["د"]!!.id)
        }
        assertTrue(rows.last().balance < 0L)
    }

    // MODE_NAME (3): الاسم بعد التطبيع العربي — ؤ(0624) قبل ع(0639) خاماً لكن التطبيع
    // يحول ؤ→و فيصير «سعاد» قبل «سؤال» — عكس ترتيب قاعدة البيانات الخام
    @Test
    fun `فرز الاسم يرتب بعد التطبيع العربي فيتقدم سعاد على سؤال`() = runBlocking {
        val vm = DebtsVM(app)
        val soal = newParty("سؤال")
        val saad = newParty("سعاد")
        vm.sortMode.value = com.superbiz.app.domain.DebtSort.MODE_NAME
        vm.refresh()
        val rows = awaitRows(vm) { idsOf(it) == listOf(saad.id, soal.id) }
        assertEquals(2, rows.size)
    }

    // MODE_OLDEST (1): أقدم فاتورة بيع مفتوحة أولاً؛ بلا آجلة → ذيل بترتيب الرصيد تنازلياً
    @Test
    fun `فرز الأقدمية يقدم صاحب أقدم فاتورة مفتوحة ويؤخر من لا فواتير له`() = runBlocking {
        val vm = DebtsVM(app)
        val p = seedSortParties()
        // أ: فاتورة مفتوحة عمرها 10 أيام ، ج: فاتورة مفتوحة عمرها يومان
        newInvoice("INV-A", p["أ"]!!.id, 10000L, T0 - 10 * DAY)
        newInvoice("INV-J", p["ج"]!!.id, 10000L, T0 - 2 * DAY)
        vm.sortMode.value = com.superbiz.app.domain.DebtSort.MODE_OLDEST
        vm.refresh()
        // ب و د بلا فواتير مفتوحة (null → MAX_VALUE) والتعادل بينهما بالرصيد: ب(30000) قبل د(-25000)
        awaitRows(vm) { idsOf(it) == listOf(p["أ"]!!.id, p["ج"]!!.id, p["ب"]!!.id, p["د"]!!.id) }
        Unit
    }

    // ═══════════════════ DebtsVM — أرصدة حقيقية من الدفتر (قروش) ═══════════════════

    // دين 500.0 ريال = 50000 قرشاً ثم سداد 200.0 = 20000 قرشاً → الرصيد 30000 قرشاً بالضبط
    @Test
    fun `السداد الجزئي يحدث الرصيد بالقروش من الدفتر`() = runBlocking {
        val vm = DebtsVM(app)
        val p = newParty("عميل سداد")
        vm.addDebt(p, 500.0, "دين")
        vm.addPayment(p, 200.0, 0)
        val rows = awaitRows(vm) { rs -> rs.any { it.party.id == p.id && it.balance == 30000L } }
        assertEquals(30000L, rows.first { it.party.id == p.id }.balance)
    }

    // 123.45 ريال → 12345 قرشاً — Money.toPiasters عبر BigDecimal بلا انزلاق عائم
    @Test
    fun `الدين يخزن قروشاً صحيحة تامة بلا كسور عائمة`() = runBlocking {
        val vm = DebtsVM(app)
        val p = newParty("عميل كسور")
        vm.addDebt(p, 123.45, "")
        val rows = awaitRows(vm) { rs -> rs.any { it.party.id == p.id && it.balance == 12345L } }
        assertEquals(12345L, rows.first { it.party.id == p.id }.balance)
    }

    // risk() محسوب من الفواتير والدفعات الحقيقية — لا يعدم للطرف المدين الحي
    @Test
    fun `تقييم الخطر غير معدوم للطرف المدين وقيمه داخل المدى`() = runBlocking {
        val vm = DebtsVM(app)
        val p = newParty("عميل خطر")
        vm.addDebt(p, 300.0, "")
        val rows = awaitRows(vm) { rs ->
            rs.any { it.party.id == p.id && it.balance == 30000L && it.risk != null }
        }
        val risk = rows.first { it.party.id == p.id }.risk!!
        assertTrue(risk.score in 0..100)
        assertTrue(risk.level in 0..2)
    }

    // فاتورة مستحقة منذ 5 أيام → worstOverdueDays ≥ 1 ودرجة خطر تتخطى عتبة الاستخدام
    @Test
    fun `تقييم الخطر يكشف الفاتورة المتأخرة عن استحقاقها`() = runBlocking {
        val vm = DebtsVM(app)
        val p = newParty("عميل متأخر")
        newInvoice("INV-LATE", p.id, 10000L, T0 - 5 * DAY)
        vm.refresh()
        val rows = awaitRows(vm) { rs ->
            rs.any { it.party.id == p.id && it.risk != null && it.risk!!.worstOverdueDays >= 1 }
        }
        val risk = rows.first { it.party.id == p.id }.risk!!
        assertTrue(risk.score >= 40)   // استخدام 30 + تأخير + انضباط — خطر حقيقي لا صفري
        assertTrue(risk.level >= 1)
    }

    // refresh() يعيد الحساب من الدفتر: دين خارجي مباشر على المستودع لا يراه الـVM
    // إلا بعد refresh (دفتر اليومية لا يبث في تدفق الأطراف)
    @Test
    fun `refresh يعيد الحساب من الدفتر بعد تعديل خارجي مباشر`() = runBlocking {
        val vm = DebtsVM(app)
        val p = newParty("عميل تحديث")
        g.ledger.addDebt(p, 10000L, System.currentTimeMillis(), "")
        vm.refresh()
        awaitRows(vm) { rs -> rs.any { it.party.id == p.id && it.balance == 10000L } }
        // تعديل خارجي (بلا مرور بالـVM) ثم refresh يدوي
        g.ledger.addDebt(p, 20000L, System.currentTimeMillis(), "")
        vm.refresh()
        awaitRows(vm) { rs -> rs.any { it.party.id == p.id && it.balance == 30000L } }
        Unit
    }

    // كشف الحساب: رصيد قروش من الدفتر + سطوره (قيد الدين + قيد التحصيل) ثم إغلاق يصفّر
    @Test
    fun `كشف الحساب يعرض رصيدا قروشا وسطور الدفتر ثم يغلق`() = runBlocking {
        val vm = DebtsVM(app)
        val p = newParty("عميل كشف")
        vm.addDebt(p, 500.0, "دين كشف")
        vm.addPayment(p, 200.0, 0)
        awaitRows(vm) { rs -> rs.any { it.party.id == p.id && it.balance == 30000L } }
        vm.openStatement(p)
        val st = withTimeout(20_000) {
            vm.statement.first { it != null && it.party.id == p.id && it.balance == 30000L }
        }!!
        assertTrue("سطرا الدين والتحصيل غائبان عن الكشف", st.rows.size >= 2)
        vm.closeStatement()
        assertNull(vm.statement.value)
    }

    // أعلى المدينين: موجبة الأرصدة فقط تنازلياً — الدائن (-25000) مستبعد بلا رقاقة،
    // والمدينون الثلاثة (ب 30000، ج 20000، أ 10000) كلهم يظهرون بالترتيب
    @Test
    fun `أعلى المدينين يرتب تنازليا ويستبعد الطرف الدائن`() = runBlocking {
        val vm = DebtsVM(app)
        val p = seedSortParties()
        vm.refresh()
        val top = withTimeout(20_000) {
            vm.topDebtors.first {
                it.map { d -> d.name } == listOf(p["ب"]!!.name, p["ج"]!!.name, p["أ"]!!.name)
            }
        }
        assertEquals(3, top.size)
        assertTrue(top.none { it.name == p["د"]!!.name })
    }

    // صحة التحصيل: الذمم المدينة من الفواتير المفتوحة الحقيقية — قروش Long تامة
    @Test
    fun `صحة التحصيل تحسب ذمم الفواتير المفتوحة قروشا`() = runBlocking {
        val vm = DebtsVM(app)
        val p = newParty("عميل فاتورة")
        newInvoice("INV-H", p.id, 10000L, T0)
        vm.refresh()
        val health = withTimeout(20_000) {
            vm.health.first { it != null && it.receivables == 10000L }
        }!!
        assertEquals(10000L, health.receivables)
    }

    // حفظ طرف باسم فارغ (أو فراغات) يُرفض قبل لمس قاعدة البيانات — وينتجه تحذير موحد
    @Test
    fun `حفظ طرف باسم فارغ يرفض ولا يضاف لقاعدة البيانات`() = runBlocking {
        val vm = DebtsVM(app)
        newParty("عميل قائم")
        ErrorCenter.clearHistory()
        vm.saveParty("   ", "", 0, "")
        withTimeout(20_000) {
            ErrorCenter.history.first { it.any { e -> e.tag == "DebtsVM" && e.level == ErrorLevel.WARN } }
        }
        assertEquals(1, g.db.parties().count())
    }

    // ═══════════════════ InventoryVM — القوائم والبحث ═══════════════════

    // القائمة الرئيسية (showArchived=false افتراضياً) تُخفي المؤرشف
    @Test
    fun `القائمة الرئيسية تعرض غير المؤرشف فقط`() = runBlocking {
        val vm = InventoryVM(app)
        val p1 = newProduct("بروتين واي")
        val p2 = newProduct("مروحة")
        newProduct("سكر", archived = true)
        // لا refresh في InventoryVM — filtered يجمع مباشرة من تدفق القاعة الحي
        val rows = awaitFiltered(vm) {
            it.size == 2 && it.map { p -> p.id }.toSet() == setOf(p1, p2) && it.none { p -> p.archived }
        }
        assertEquals(2, rows.size)
    }

    // بحث بالاسم (contains غير حساس للحالة) — بلا debounce: انبعاث مباشر
    @Test
    fun `بحث المخزون بالاسم يجد المنتج وحده`() = runBlocking {
        val vm = InventoryVM(app)
        val p1 = newProduct("بروتين واي")
        newProduct("مروحة")
        newProduct("ثلاجة صغيرة")
        vm.search.value = "بروتين"
        val rows = awaitFiltered(vm) { it.size == 1 && it.first().id == p1 }
        assertEquals("بروتين واي", rows.first().name)
    }

    // بحث بالرمز SKU — contains بتجاهل الحالة: "sku-88" يجد "SKU-88"
    @Test
    fun `بحث المخزون بالرمز غير حساس لحالة الأحرف`() = runBlocking {
        val vm = InventoryVM(app)
        val p1 = newProduct("مكواة", sku = "SKU-88")
        newProduct("مروحة", sku = "SKU-99")
        vm.search.value = "sku-88"
        val rows = awaitFiltered(vm) { it.size == 1 && it.first().id == p1 }
        assertEquals("SKU-88", rows.first().sku)
    }

    // بحث بالباركود — مطابقة جزئية داخل الرمز (contains حساس لكن أرقام)
    @Test
    fun `بحث المخزون بالباركود يطابق جزءا من الرمز`() = runBlocking {
        val vm = InventoryVM(app)
        val p1 = newProduct("زيت زيتون", barcode = "6281000123456")
        newProduct("مروحة", barcode = "6299998888777")
        vm.search.value = "123456"
        val rows = awaitFiltered(vm) { it.size == 1 && it.first().id == p1 }
        assertEquals("6281000123456", rows.first().barcode)
    }

    // بحث متسامح مطبعياً: فشل المطابقة الحرفية للاستعلام ≥3 أحرف يفعّل تطبيع الهمزات
    // (BizMath.arabicNormalize) — «اقراص» تجد «أقراص» بدرجة تشابه 1.0
    @Test
    fun `البحث المتسامح يطبع الهمزات عند فشل المطابقة الحرفية`() = runBlocking {
        val vm = InventoryVM(app)
        val p1 = newProduct("أقراص")
        newProduct("ثلاجة")
        newProduct("مروحة")
        vm.search.value = "اقراص"
        val rows = awaitFiltered(vm) { it.size == 1 && it.first().id == p1 }
        assertEquals("أقراص", rows.first().name)
    }

    // بحث متسامح بخطأ إملائي: Levenshtein("لابتوب","لابتبوب")=1 → تشابه 6/7 ≈ 0.857 > 0.6
    @Test
    fun `البحث المتسامح يجد المنتج رغم الخطأ الإملائي`() = runBlocking {
        val vm = InventoryVM(app)
        val p1 = newProduct("لابتوب")
        newProduct("بروتين واي")
        vm.search.value = "لابتبوب"
        val rows = awaitFiltered(vm) { it.size == 1 && it.first().id == p1 }
        assertEquals("لابتوب", rows.first().name)
    }

    // بلا مطابقة حرفية ولا ضبابية (كل التشابهات < 0.6) → قائمة فارغة صادقة
    @Test
    fun `بحث بلا مطابقة يفرغ القائمة`() = runBlocking {
        val vm = InventoryVM(app)
        newProduct("بروتين واي")
        newProduct("مروحة")
        newProduct("ثلاجة")
        awaitFiltered(vm) { it.size == 3 }   // القائمة ممتلئة قبل البحث
        vm.search.value = "شاشة قديمة"
        awaitFiltered(vm) { it.isEmpty() }   // انتقال حقيقي: من 3 إلى صفر
        Unit
    }

    // ═══════════════════ InventoryVM — منخفض المخزون والأرشفة ═══════════════════

    // isLow = reorderLevel > 0 && stockQty <= reorderLevel — الفلتر يعرض المنخفض وحده
    @Test
    fun `فلتر منخفض المخزون يعرض المنتجات عند الحد فقط`() = runBlocking {
        val vm = InventoryVM(app)
        val low = newProduct("منخفض", stock = 2.0, reorder = 5.0)
        newProduct("سليم", stock = 10.0, reorder = 5.0)
        vm.showLowOnly.value = true
        val rows = awaitFiltered(vm) { it.size == 1 && it.first().id == low }
        assertTrue(rows.first().isLow)
    }

    // إلغاء رقاقة «منخفض المخزون» يعيد القائمة كاملة
    @Test
    fun `إلغاء فلتر المخزون المنخفض يعيد كل المنتجات`() = runBlocking {
        val vm = InventoryVM(app)
        newProduct("منخفض", stock = 2.0, reorder = 5.0)
        newProduct("سليم", stock = 10.0, reorder = 5.0)
        vm.showLowOnly.value = true
        awaitFiltered(vm) { it.size == 1 }
        vm.showLowOnly.value = false
        awaitFiltered(vm) { it.size == 2 }
        Unit
    }

    // findByBarcode: مسار الماسح — باركود كامل يوجد المنتج عبر الاستدعاء الختامي
    @Test
    fun `البحث بالباركود الكامل يوجد المنتج`() = runBlocking {
        val vm = InventoryVM(app)
        val p1 = newProduct("زيت زيتون", barcode = "6289001234567")
        val slot = CompletableDeferred<Product?>()
        vm.findByBarcode("6289001234567") { slot.complete(it) }
        val found = withTimeout(20_000) { slot.await() }
        assertNotNull(found)
        assertEquals(p1, found!!.id)
    }

    // باركود فارغ → null صريح بلا استعلام
    @Test
    fun `البحث بباركود فارغ يعيد معدوما`() = runBlocking {
        val vm = InventoryVM(app)
        newProduct("زيت زيتون", barcode = "6289001234567")
        val slot = CompletableDeferred<Product?>()
        vm.findByBarcode("") { slot.complete(it) }
        val found = withTimeout(20_000) { slot.await() }
        assertNull(found)
    }

    // الأرشفة تُخفي من القائمة الرئيسية وتراه رقاقة «المؤرشفة» وحدها
    @Test
    fun `الأرشفة تخفي من الرئيسية وتظهر في رقاقة المؤرشفة`() = runBlocking {
        val vm = InventoryVM(app)
        val p1 = newProduct("بروتين واي")
        val p2 = newProduct("مروحة")
        awaitFiltered(vm) { it.size == 2 }
        vm.setArchived(p1, true)
        awaitFiltered(vm) { it.none { p -> p.id == p1 } && it.size == 1 }
        vm.showArchived.value = true
        val archived = awaitFiltered(vm) { it.size == 1 && it.first().id == p1 }
        assertTrue(archived.first().archived)
        // البطاقة الكاملة (productsAll) لم تفقد الصف أبداً
        assertEquals(p2, vm.productsAll.value.first { it.id == p2 }.id)
    }

    // الاسترجاع من الأرشفة يعيد المنتج إلى القائمة الرئيسية
    @Test
    fun `الاسترجاع من الأرشفة يعيد المنتج للقائمة`() = runBlocking {
        val vm = InventoryVM(app)
        val p1 = newProduct("بروتين واي")
        // استقرار أولي: اشتراك حي قبل أي فعل يضمن أن الانبعاثات التالية انتقالات
        // حية تُلاحظ لحظة وقوعها لا قيماً كاشية قديمة (إغلاق سباق WhileSubscribed)
        awaitFiltered(vm) { it.size == 1 && it.first().id == p1 }
        vm.setArchived(p1, true)
        awaitFiltered(vm) { it.isEmpty() }
        vm.setArchived(p1, false)
        awaitFiltered(vm) { it.size == 1 && it.first().id == p1 }
        Unit
    }
}
