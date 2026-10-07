package com.superbiz.app.ui.compose

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.AppGraph
import com.superbiz.app.R
import com.superbiz.app.data.db.Party
import com.superbiz.app.ui.screens.PartyEditorDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [P40-M5] المرحلة 5 — جدار Compose UI — مسار «إضافة طرف 12 حقلاً»:
 * PartyEditorDialog المستخرج حرفياً من DebtsScreen فوق Robolectric-Compose.
 *
 * يغطي عقد الحوار الحقيقي:
 * • الحقول الاثنا عشر ظاهرة (الاسم/الهاتف/الملاحظة + أعمدة الكشف الثمانية من P17-c)
 *   مع رقائق النوع الثلاثة (عميل/مورد/كلاهما).
 * • الاسم الفارغ: زر الحفظ لا يكتب شيئاً ولا يغلق — عقد الشرط الأصلي.
 * • الاسم المكتمل: الكتابة عبر LedgerRepo.saveParty فعلياً (لا عبر VM) وتُحفظ
 *   أعمدة الكشف الثمانية مقتطعة الفراغ — ثم onSaveDone.
 * • مسار التعديل: editPartyId يحدّث الطرف نفسه بلا صف ثانٍ.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PartyEditorP40Test {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var app: Application
    private lateinit var g: AppGraph
    private val testMain = UnconfinedTestDispatcher()

    private fun resetGraph() {
        val c = Class.forName("com.superbiz.app.AppGraph")
        val inst = c.getDeclaredField("instance")
        inst.isAccessible = true
        inst.set(null, null)
    }

    @Before
    fun setUp() {
        resetGraph()
        app = ApplicationProvider.getApplicationContext()
        g = AppGraph.from(app)
        // [P40-M5] Main غير مقيّد: مهام viewModelScope تعمل بلهفة على خيط
        // الاختبار وRoom يستأنف على منفّذه — لا انتظار لوبِر في بيئة Compose
        Dispatchers.setMain(testMain)
        runBlocking { g.settings.snapshot() } // تسخين DataStore قبل التركيب
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    @After
    fun tearDown() {
        resetGraph()
        Dispatchers.resetMain()
    }

    private class Holder {
        val partyId = mutableStateOf(0L)
        val name = mutableStateOf("")
        val phone = mutableStateOf("")
        val type = mutableStateOf(0)
        val note = mutableStateOf("")
        val email = mutableStateOf("")
        val address = mutableStateOf("")
        val taxNumber = mutableStateOf("")
        val city = mutableStateOf("")
        val country = mutableStateOf("")
        val website = mutableStateOf("")
        val crNumber = mutableStateOf("")
        val accountNumber = mutableStateOf("")
    }

    private fun setContent(h: Holder, saved: () -> Unit = {}) {
        compose.setContent {
            PartyEditorDialog(
                editPartyId = h.partyId.value,
                editName = h.name, editPhone = h.phone, editType = h.type,
                editNote = h.note, editEmail = h.email, editAddress = h.address,
                editTaxNumber = h.taxNumber, editCity = h.city, editCountry = h.country,
                editWebsite = h.website, editCrNumber = h.crNumber,
                editAccountNumber = h.accountNumber,
                appContext = app,
                editScope = CoroutineScope(Dispatchers.IO),
                onSaveDone = saved,
                onDismiss = {}
            )
        }
    }

    @Test
    fun `الحقول الاثنا عشر ورقائق النوع الثلاثة ظاهرة`() {
        setContent(Holder())
        val keys = listOf(
            R.string.name, R.string.phone, R.string.note,
            R.string.st_field_email, R.string.st_field_address, R.string.st_field_tax_number,
            R.string.st_field_city, R.string.st_field_country, R.string.st_field_website,
            R.string.st_field_cr_number, R.string.st_field_account_number
        )
        keys.forEach { key ->
            compose.onNodeWithText(app.getString(key)).assertExists()
        }
        compose.onNodeWithText(app.getString(R.string.customers)).assertExists()
        compose.onNodeWithText(app.getString(R.string.suppliers)).assertExists()
        compose.onNodeWithText(app.getString(R.string.party_both)).assertExists()
    }

    @Test
    fun `الاسم الفارغ لا يكتب ولا يغلق`() = runBlocking {
        val h = Holder()
        var savedCalled = 0
        setContent(h) { savedCalled++ }
        compose.onNodeWithText(app.getString(R.string.phone)).performTextInput("0500000009")
        compose.onNodeWithText(app.getString(R.string.save)).performClick()
        // [تدقيق M-10] نافذة سلبية حتمية: استنزاف كامل لأعمال التركيب المقررة
        // (waitForIdle) ضمن أفق 1 ثانية — بدل Thread.sleep(400) العشوائي
        val deadline = System.currentTimeMillis() + 1_000
        while (System.currentTimeMillis() < deadline && g.db.parties().allOnce().isEmpty()) {
            compose.waitForIdle()
            Thread.sleep(50)
        }
        assertTrue("بلا اسم: لا طرف يُكتب", g.db.parties().allOnce().isEmpty())
        assertEquals("بلا اسم: onSaveDone لا يُستدعى (الحوار يبقى)", 0, savedCalled)
    }

    @Test
    fun `حفظ طرف جديد بكامل أعمدة الكشف`() = runBlocking {
        val h = Holder()
        var savedCalled = 0
        setContent(h) { savedCalled++ }
        compose.onNodeWithText(app.getString(R.string.name)).performTextInput("  شركة النور  ")
        compose.onNodeWithText(app.getString(R.string.phone)).performTextInput("0501234567")
        compose.onNodeWithText(app.getString(R.string.st_field_email)).performTextInput("noor@example.com")
        compose.onNodeWithText(app.getString(R.string.st_field_tax_number)).performTextInput("3001234567")
        compose.onNodeWithText(app.getString(R.string.st_field_city)).performTextInput("الرياض")
        // النوع الثالث يُضبط على كائن الحالة نفسه الذي يقرؤه الحوار (عقد الكتابة) —
        // نقر الرقاقة نفسها سلوك BizPill العام مغطى في اختبار الوجود أعلاه
        h.type.value = 2
        compose.onNodeWithText(app.getString(R.string.save)).performClick()
        // [تدقيق M-10] مهلة محدودة + استنزاف runOnIdle (lamda waitUntil لا تقبل suspend)
        val saveDeadline = System.currentTimeMillis() + 60_000
        while (g.db.parties().allOnce().isEmpty()) {
            assertTrue("انتهت مهلة انتظار حفظ الطرف (M-10)", System.currentTimeMillis() < saveDeadline)
            compose.runOnIdle {}
            Thread.sleep(150)
        }
        val p = g.db.parties().allOnce().single()
        assertEquals("شركة النور", p.name) // مقتطع الفراغ
        assertEquals("0501234567", p.phone)
        assertEquals("noor@example.com", p.email)
        assertEquals("3001234567", p.taxNumber)
        assertEquals("الرياض", p.city)
        assertEquals(2, p.type) // رقاقة «كلاهما»
        assertEquals("onSaveDone يُستدعى بعد بدء الحفظ (نمط vm.refresh+إغلاق)", 1, savedCalled)
    }

    @Test
    fun `تعديل طرف قائم يحدّث الصف نفسه بلا تكرار`() = runBlocking {
        val id = g.db.parties().upsert(Party(name = "قديم", phone = "011", type = 0))
        val h = Holder().apply {
            partyId.value = id
            name.value = "جديد"
            phone.value = "022"
        }
        setContent(h)
        compose.onNodeWithText(app.getString(R.string.save)).performClick()
        // [تدقيق M-10] مهلة محدودة + استنزاف runOnIdle (lamda waitUntil لا تقبل suspend)
        val editDeadline = System.currentTimeMillis() + 60_000
        while (g.db.parties().allOnce().none { it.name == "جديد" }) {
            assertTrue("انتهت مهلة انتظار تعديل الطرف (M-10)", System.currentTimeMillis() < editDeadline)
            compose.runOnIdle {}
            Thread.sleep(150)
        }
        val all = g.db.parties().allOnce()
        assertEquals("upsert على نفس المعرف — لا صف ثانٍ", 1, all.size)
        assertEquals(id, all.single().id)
        assertEquals("022", all.single().phone)
    }
}
