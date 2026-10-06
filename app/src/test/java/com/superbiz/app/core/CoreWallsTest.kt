package com.superbiz.app.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [P37-T-C] جدران اختبارات نواة التطبيق core — AppPrefs وErrorCenter:
 *
 * ① AppPrefs — ملاحظة توثيقية مهمة (قرأت AppPrefs.kt كاملاً): الكائن **بلا أي
 *    SharedPreferences إطلاقاً** — هو مرآة ذاكرة حيّة من حقول @Volatile ينسخها
 *    AppVM.syncPrefs() من DataStore (SettingsRepo) عند كل تغيير، وتُعاد إلى
 *    الافتراضات الآمنة عند إعادة التشغيل. لذا فالدوران هنا على واجهته الحقيقية:
 *    كتابة كل حقل وقراءته (Boolean/Int/Float/Double) + الافتراضات الخمسة عشر +
 *    العودة إلى الافتراضات — وهذا هو العقد الفعلي الذي تستهلكه الطبقات العميقة
 *    (حوارات الطباعة/الويدجت) بقراءة متزامنة.
 *
 * ② ErrorCenter — ملاحظة توثيقية (قرأت ErrorCenter.kt كاملاً): لا توجد حالة
 *    «عرض/إخفاء/طابور» صريحة (لا show/dismiss/queue في الواجهة)؛ سطح الرسالة
 *    هو ثلاث معرّضات:
 *      - events: SharedFlow بثّ فوري بلا replay (Snackbar) — extraBufferCapacity 16 مع DROP_OLDEST
 *      - history: StateFlow سجل دائري يسقف عند 200 حدث (شاشة سجل الأخطاء)
 *      - last: StateFlow لآخر حدث فقط (شارات سريعة)
 *    والإخفاء الوحيد هو clearHistory() الذي يفرّغ history وlast معاً. درجات
 *    الخطأ ErrorLevel: INFO=0 / WARN=1 / ERROR=2، ورسالة المستخدم userMessage
 *    تُحفظ كما هي منفصلة عن الرسالة التقنية، والاستثناء بلا message يتحول إلى
 *    اسم صنفه، والمعرفات رتيبة التزايد بلا تكرار (قفل R15-F12).
 *
 * حتمية: لا أزمنة تُقرأ من الخارج في التأكيدات (ts من System.currentTimeMillis
 * داخلياً لا يُدقق قيمته — يُدقق وجوده فقط)، والحالة المشتركة للكائنين تُصفَّر
 * في @Before قبل كل اختبار لاستقلال الترتيب. Robolectric مطلوب لـandroid.util.Log
 * في ErrorCenter (وليس ApplicationProvider — السياق غير مستخدم فعلياً هنا).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class CoreWallsTest {

    @Before
    fun `تصفير الحالة المشتركة قبل كل اختبار`() {
        resetAppPrefs()
        ErrorCenter.clearHistory()
    }

    // ───────── أدوات ─────────

    /** إعادة كتابة الافتراضات الموثقة في AppPrefs.kt حرفياً — فصل الحالة بين الاختبارات */
    private fun resetAppPrefs() {
        AppPrefs.hapticsEnabled = true
        AppPrefs.confirmDestructive = true
        AppPrefs.flagSecure = true
        AppPrefs.lockTimeoutMin = 0
        AppPrefs.fontScale = 1.0f
        AppPrefs.dynamicColors = false
        AppPrefs.mirrorChartsRtl = true
        AppPrefs.animationsEnabled = true
        AppPrefs.arabicReceiptMode = 0
        AppPrefs.defaultLowStockQty = 5
        AppPrefs.lowStockAlerts = true
        AppPrefs.receivableAlerts = true
        AppPrefs.searchFuzzyThreshold = 0.45
        AppPrefs.eoqOrderCost = 25.0
        AppPrefs.privacyBlur = false
    }

    // ───────── ① AppPrefs — الافتراضات والدوران ─────────

    @Test
    fun `AppPrefs - الافتراضات الآمنة الخمسة عشر عند الإقلاع`() {
        assertTrue(AppPrefs.hapticsEnabled)
        assertTrue(AppPrefs.confirmDestructive)
        assertTrue(AppPrefs.flagSecure)
        assertEquals(0, AppPrefs.lockTimeoutMin)
        assertEquals(1.0f, AppPrefs.fontScale, 0f)
        assertFalse(AppPrefs.dynamicColors)
        assertTrue(AppPrefs.mirrorChartsRtl)
        assertTrue(AppPrefs.animationsEnabled)
        assertEquals(0, AppPrefs.arabicReceiptMode)
        assertEquals(5, AppPrefs.defaultLowStockQty)
        assertTrue(AppPrefs.lowStockAlerts)
        assertTrue(AppPrefs.receivableAlerts)
        assertEquals(0.45, AppPrefs.searchFuzzyThreshold, 0.0)
        assertEquals(25.0, AppPrefs.eoqOrderCost, 0.0)
        assertFalse(AppPrefs.privacyBlur)
    }

    @Test
    fun `AppPrefs - دوران القيم المنطقية التسع كتابة ثم قراءة`() {
        AppPrefs.hapticsEnabled = false
        AppPrefs.confirmDestructive = false
        AppPrefs.flagSecure = false
        AppPrefs.dynamicColors = true
        AppPrefs.mirrorChartsRtl = false
        AppPrefs.animationsEnabled = false
        AppPrefs.lowStockAlerts = false
        AppPrefs.receivableAlerts = false
        AppPrefs.privacyBlur = true
        assertFalse(AppPrefs.hapticsEnabled)
        assertFalse(AppPrefs.confirmDestructive)
        assertFalse(AppPrefs.flagSecure)
        assertTrue(AppPrefs.dynamicColors)
        assertFalse(AppPrefs.mirrorChartsRtl)
        assertFalse(AppPrefs.animationsEnabled)
        assertFalse(AppPrefs.lowStockAlerts)
        assertFalse(AppPrefs.receivableAlerts)
        assertTrue(AppPrefs.privacyBlur)
    }

    @Test
    fun `AppPrefs - دوران القيم العددية الصحيحة الثلاث بمقاييس مختلفة`() {
        AppPrefs.lockTimeoutMin = 5          // 0 = فوري — مهلة إعادة القفل بالدقائق
        AppPrefs.arabicReceiptMode = 1       // 0 = CP1256 ، 1 = UTF-8
        AppPrefs.defaultLowStockQty = 12     // حدّ المخزون المنخفض
        assertEquals(5, AppPrefs.lockTimeoutMin)
        assertEquals(1, AppPrefs.arabicReceiptMode)
        assertEquals(12, AppPrefs.defaultLowStockQty)
    }

    @Test
    fun `AppPrefs - دوران الأعداد العشرية الثلاث من أنواع مختلفة`() {
        AppPrefs.fontScale = 1.30f           // نطاق موثق 0.85..1.30
        AppPrefs.searchFuzzyThreshold = 0.85 // Double — قبول التطابق الضبابي
        AppPrefs.eoqOrderCost = 99.5         // Double — كلفة أمر الشراء
        assertEquals(1.30f, AppPrefs.fontScale, 0f)
        assertEquals(0.85, AppPrefs.searchFuzzyThreshold, 0.0)
        assertEquals(99.5, AppPrefs.eoqOrderCost, 0.0)
    }

    @Test
    fun `AppPrefs - كتابة كل الحقول ثم العودة الكاملة إلى الافتراضات`() {
        AppPrefs.hapticsEnabled = false
        AppPrefs.confirmDestructive = false
        AppPrefs.flagSecure = false
        AppPrefs.lockTimeoutMin = 10
        AppPrefs.fontScale = 0.85f
        AppPrefs.dynamicColors = true
        AppPrefs.mirrorChartsRtl = false
        AppPrefs.animationsEnabled = false
        AppPrefs.arabicReceiptMode = 1
        AppPrefs.defaultLowStockQty = 99
        AppPrefs.lowStockAlerts = false
        AppPrefs.receivableAlerts = false
        AppPrefs.searchFuzzyThreshold = 0.9
        AppPrefs.eoqOrderCost = 500.0
        AppPrefs.privacyBlur = true
        // «تعود إلى الافتراضات عند إعادة التشغيل» — محاكاة الإقلاع الجديد
        resetAppPrefs()
        assertTrue(AppPrefs.hapticsEnabled)
        assertTrue(AppPrefs.confirmDestructive)
        assertTrue(AppPrefs.flagSecure)
        assertEquals(0, AppPrefs.lockTimeoutMin)
        assertEquals(1.0f, AppPrefs.fontScale, 0f)
        assertFalse(AppPrefs.dynamicColors)
        assertTrue(AppPrefs.mirrorChartsRtl)
        assertTrue(AppPrefs.animationsEnabled)
        assertEquals(0, AppPrefs.arabicReceiptMode)
        assertEquals(5, AppPrefs.defaultLowStockQty)
        assertTrue(AppPrefs.lowStockAlerts)
        assertTrue(AppPrefs.receivableAlerts)
        assertEquals(0.45, AppPrefs.searchFuzzyThreshold, 0.0)
        assertEquals(25.0, AppPrefs.eoqOrderCost, 0.0)
        assertFalse(AppPrefs.privacyBlur)
    }

    // ───────── ② ErrorCenter — report/info/warn والتدفق ─────────

    @Test
    fun `ErrorCenter - report يسجل في history وlast والقيمة الأولى للسجل تطابق الفعل`() {
        assertTrue(ErrorCenter.history.value.isEmpty())
        val boom = RuntimeException("فشل توليد PDF")
        ErrorCenter.report(boom, "POS", "تعذر إنشاء الكشف", ErrorLevel.ERROR)
        // واجهة StateFlow الصحيحة: القيمة الأولى بعد الفعل = الحالة الحالية
        val firstValue = runBlocking { ErrorCenter.history.first() }
        assertEquals(ErrorCenter.history.value, firstValue)
        assertEquals(1, firstValue.size)
        val ev = firstValue.last()
        assertEquals("POS", ev.tag)
        assertEquals("فشل توليد PDF", ev.message)
        assertEquals("تعذر إنشاء الكشف", ev.userMessage)
        assertEquals(ErrorLevel.ERROR, ev.level)
        assertTrue(ev.ts > 0) // الطابع الزمني داخلي لا يُدقق قيمته — الوجود فقط
        assertEquals(ev, ErrorCenter.last.value)
    }

    @Test
    fun `ErrorCenter - استثناء بلا رسالة يتحول إلى اسم صنفه`() {
        ErrorCenter.report(Boom(), "Backup")
        val ev = ErrorCenter.history.value.single()
        assertEquals("Boom", ev.message) // t.message ?: t.javaClass.simpleName
        assertNull(ev.userMessage)
    }

    @Test
    fun `ErrorCenter - رسالة المستخدم العربية تحفظ منفصلة عن الرسالة التقنية`() {
        ErrorCenter.report(RuntimeException("SQLITE_FULL"), "Db", "الذاكرة ممتلئة — قلل البيانات")
        val ev = ErrorCenter.history.value.single()
        assertEquals("SQLITE_FULL", ev.message)
        assertEquals("الذاكرة ممتلئة — قلل البيانات", ev.userMessage)
        // ثم حدث بلا رسالة مستخدم — الحقل يبقى null ولا يُخترع
        ErrorCenter.report(RuntimeException("x"), "Db")
        assertNull(ErrorCenter.history.value.last().userMessage)
        assertEquals("x", ErrorCenter.history.value.last().message)
    }

    @Test
    fun `ErrorCenter - info بمستوى صفر وبلا رسالة مستخدم وmessage كما مررت`() {
        ErrorCenter.info("Widget", "تحديث دوري للويدجت")
        val ev = ErrorCenter.history.value.single()
        assertEquals(ErrorLevel.INFO, ev.level)
        assertEquals("تحديث دوري للويدجت", ev.message)
        assertNull(ev.userMessage)
    }

    @Test
    fun `ErrorCenter - warn بمستوى واحد ورسالة مستخدم اختيارية وmessage كما مررت`() {
        ErrorCenter.warn("Sync", "بطء الشبكة", "الشبكة بطيئة — سنعيد المحاولة")
        val ev = ErrorCenter.history.value.single()
        assertEquals(ErrorLevel.WARN, ev.level)
        assertEquals("بطء الشبكة", ev.message)
        assertEquals("الشبكة بطيئة — سنعيد المحاولة", ev.userMessage)
        ErrorCenter.warn("Sync", "بطء آخر")
        val second = ErrorCenter.history.value.last()
        assertEquals(ErrorLevel.WARN, second.level)
        assertNull(second.userMessage)
    }

    @Test
    fun `ErrorLevel - الثوابت الثلاثة بقيمها المتعاقد عليها صفر وواحد واثنان`() {
        assertEquals(0, ErrorLevel.INFO)
        assertEquals(1, ErrorLevel.WARN)
        assertEquals(2, ErrorLevel.ERROR)
    }

    @Test
    fun `ErrorCenter - معرفات متزايدة رتيبا بلا تكرار عبر report وwarn`() {
        repeat(12) { i ->
            if (i % 2 == 0) ErrorCenter.report(RuntimeException("e$i"), "T")
            else ErrorCenter.warn("T", "w$i")
        }
        val ids = ErrorCenter.history.value.map { it.id }
        assertEquals(12, ids.size)
        assertEquals(ids.size, ids.distinct().size) // لا تكرار (قفل R15-F12)
        assertTrue(ids.zipWithNext().all { (a, b) -> b == a + 1 }) // تزايد رتيب متصل
    }

    @Test
    fun `ErrorCenter - السجل الدائري يسقف عند مئتي حدث ويحفظ الأحدث ويهمل الأقدم`() {
        repeat(205) { i -> ErrorCenter.info("cap", "e$i") }
        val h = ErrorCenter.history.value
        assertEquals(200, h.size) // MAX_HISTORY = 200
        assertEquals("e5", h.first().message)   // 205 - 200 = أول 5 هُملت
        assertEquals("e204", h.last().message)  // الأحدث في الذيل
        assertEquals("e204", ErrorCenter.last.value!!.message)
    }

    @Test
    fun `ErrorCenter - clearHistory يفرغ السجل وlast معا ولا يبقي أثرا`() {
        ErrorCenter.info("T", "سأُمحى")
        ErrorCenter.warn("T", "وأنا أيضاً")
        assertTrue(ErrorCenter.history.value.isNotEmpty())
        assertNotNull(ErrorCenter.last.value)
        ErrorCenter.clearHistory()
        assertTrue(ErrorCenter.history.value.isEmpty())
        assertNull(ErrorCenter.last.value)
    }

    @Test
    fun `ErrorCenter - بث events الفوري يصل للمشترك الذي اشترك قبل الفعل`() = runBlocking {
        val wait = async(start = CoroutineStart.UNDISPATCHED) { ErrorCenter.events.first() }
        yield() // يضمن اكتمال الاشتراك قبل الفعل (بلا replay — المشترك اللاحق لا يرى القديم)
        ErrorCenter.info("POS", "بث حي")
        val ev = wait.await()
        assertEquals("POS", ev.tag)
        assertEquals("بث حي", ev.message)
        assertEquals(ErrorLevel.INFO, ev.level)
    }

    @Test
    fun `ErrorCenter - المعرّضات StateFlow صحيحة والقيمة الأولى بعد الفعل مطابقة`() {
        ErrorCenter.warn("T", "w1")
        ErrorCenter.warn("T", "w2")
        ErrorCenter.report(RuntimeException("r1"), "T", "u1")
        // last: StateFlow<ErrorEvent?> — القيمة الأولى = آخر حدث
        val lastFirst = runBlocking { ErrorCenter.last.first() }
        assertEquals(ErrorCenter.last.value, lastFirst)
        assertEquals("r1", lastFirst!!.message)
        // history: StateFlow<List<ErrorEvent>> — ثلاثة أحداث بالترتيب
        val historyFirst = runBlocking { ErrorCenter.history.first() }
        assertEquals(ErrorCenter.history.value, historyFirst)
        assertEquals(listOf("w1", "w2", "r1"), historyFirst.map { it.message })
    }

    /** صنف مسمّى بلا رسالة — اسمه البسيط يصبح رسالة الحدث (سلوك report الموثق) */
    private class Boom : RuntimeException()
}
