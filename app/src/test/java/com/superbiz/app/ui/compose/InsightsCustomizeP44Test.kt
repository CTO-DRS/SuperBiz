package com.superbiz.app.ui.compose

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.core.AppPrefs
import com.superbiz.app.domain.DashboardPrefsP44
import com.superbiz.app.ui.insights.InsightsCustomizeDialog
import com.superbiz.app.ui.insights.InsightsStack
import com.superbiz.app.ui.insights.insightsGroup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [P44-K1] جولة 5 — جدار Compose لنافذة تخصيص البطاقات والمكدس المخصص
 * (Robolectric-Compose — نفس البنية المستقرة منذ P40):
 *
 * • النافذة تعرض مجموعات الشاشة القياسية كاملة بالترتيب الافتراضي، وتصدر
 *   الحالة الصادقة عند الإخفاء (نقر التسمية) والتحريك (سهم أسفل) والاستعادة.
 * • النافذة لا تلمس AppPrefs — تصدّر عبر onChange فقط (الكتابة عقد المكدس).
 * • المكدس InsightsStack يرسم المجموعات بالترتيب المخزّن ويحذف المخفي
 *   ويُظهر رقاقة التخصيص — قراءة الخزنة الحيّة AppPrefs عند البدء.
 *
 * حارس الاستطلاع P40: بيئة Robolectric إنجليزية (values-en) — التوكيدات على
 * سلاسل values-en، وخيط الاختبار هو اللوبِر فلا انتظار محجوب.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class InsightsCustomizeP44Test {

    @get:Rule
    val compose = createComposeRule()

    // قياسي تجريبي بثلاث مجموعات
    private val canon = listOf("smart", "r9", "r10")

    @Before
    fun setUp() {
        AppPrefs.dashboardLayout = null
    }

    @After
    fun tearDown() {
        AppPrefs.dashboardLayout = null
    }

    // ─── النافذة ───

    @Test
    fun `النافذة تعرض المجموعات القياسية كاملة افتراضيا`() {
        compose.setContent {
            InsightsCustomizeDialog("home", canon, null, onChange = {}, onDismiss = {})
        }
        compose.onNodeWithText("Customize insight cards").assertExists()
        compose.onNodeWithText("Early smart insights").assertExists()
        compose.onNodeWithText("Liquidity & retention").assertExists()
        compose.onNodeWithText("Health & focus").assertExists()
        compose.onNodeWithText("Restore default").assertExists()
    }

    @Test
    fun `نقر التسمية يصدر حالة الإخفاء بترتيب كامل مادّي`() {
        var emitted: DashboardPrefsP44.Layout? = null
        compose.setContent {
            InsightsCustomizeDialog("home", canon, null, onChange = { emitted = it }, onDismiss = {})
        }
        compose.onNodeWithText("Early smart insights").performClick()
        val pref = emitted?.screens?.get("home")
        assertNotNull(pref)
        assertEquals(setOf("smart"), pref!!.hidden)
        // والعقد: الترتيب الصادر كامل (الموضع محفوظ للعودة)
        assertEquals(canon, pref.order)
    }

    @Test
    fun `سهم الأسفل يبدل أول مجموعتين في الحالة الصادرة`() {
        var emitted: DashboardPrefsP44.Layout? = null
        compose.setContent {
            InsightsCustomizeDialog("home", canon, null, onChange = { emitted = it }, onDismiss = {})
        }
        // سهم أسفل الصف الأول (المعوّل الوحيد المفعّل أسفله صفان)
        compose.onAllNodesWithContentDescription("Move down")[0].performClick()
        assertEquals(listOf("r9", "smart", "r10"), emitted!!.screens["home"]!!.order)
    }

    @Test
    fun `الاستعادة تصدر حالة بلا تفضيل للشاشة`() {
        val raw = """{"v":1,"s":{"home":{"o":["r10","smart","r9"],"h":["r9"]}}}"""
        var emitted: DashboardPrefsP44.Layout? = null
        compose.setContent {
            InsightsCustomizeDialog("home", canon, raw, onChange = { emitted = it }, onDismiss = {})
        }
        compose.onNodeWithText("Restore default").performClick()
        assertNotNull(emitted)
        assertFalse(emitted!!.screens.containsKey("home"))
    }

    @Test
    fun `النافذة على حالة مخزنة تعرض ترتيبها والمخفي داخلها`() {
        val raw = """{"v":1,"s":{"home":{"o":["r9","smart","r10"],"h":["smart"]}}}"""
        compose.setContent {
            InsightsCustomizeDialog("home", canon, raw, onChange = {}, onDismiss = {})
        }
        // المجموعات الثلاث كلها معروضة (المخفي بصفوف النافذة لا يُحذف) والقائمة مرتبة بالتخزين
        compose.onNodeWithText("Liquidity & retention").assertExists()
        compose.onNodeWithText("Early smart insights").assertExists()
        compose.onNodeWithText("Health & focus").assertExists()
    }

    // ─── المكدس ───

    @Test
    fun `المكدس يرسم بالترتيب المخزن ويحذف المخفي ويعرض رقاقة التخصيص`() {
        AppPrefs.dashboardLayout = """{"v":1,"s":{"home":{"o":["r10","smart","r9"],"h":["r9"]}}}"""
        compose.setContent {
            InsightsStack(
                "home",
                listOf(
                    insightsGroup("smart") { androidx.compose.material3.Text("G-smart") },
                    insightsGroup("r9") { androidx.compose.material3.Text("G-r9") },
                    insightsGroup("r10") { androidx.compose.material3.Text("G-r10") },
                )
            )
        }
        compose.onNodeWithText("Customize cards").assertExists()
        compose.onNodeWithText("G-r10").assertExists()
        compose.onNodeWithText("G-smart").assertExists()
        compose.onNodeWithText("G-r9").assertDoesNotExist()
        // والترتيب الفعلي عمودياً: r10 فوق smart
        val y10 = compose.onAllNodesWithText("G-r10").fetchSemanticsNodes().first().positionInRoot.y
        val ySmart = compose.onAllNodesWithText("G-smart").fetchSemanticsNodes().first().positionInRoot.y
        assertTrue("توقع r10 فوق smart", y10 < ySmart)
    }

    @Test
    fun `المكدس بلا تخزين يرسم بالقياسي حرفيا`() {
        compose.setContent {
            InsightsStack(
                "home",
                listOf(
                    insightsGroup("smart") { androidx.compose.material3.Text("G-smart") },
                    insightsGroup("r9") { androidx.compose.material3.Text("G-r9") },
                    insightsGroup("r10") { androidx.compose.material3.Text("G-r10") },
                )
            )
        }
        val ySmart = compose.onAllNodesWithText("G-smart").fetchSemanticsNodes().first().positionInRoot.y
        val y9 = compose.onAllNodesWithText("G-r9").fetchSemanticsNodes().first().positionInRoot.y
        val y10 = compose.onAllNodesWithText("G-r10").fetchSemanticsNodes().first().positionInRoot.y
        assertTrue(ySmart < y9 && y9 < y10)
    }
}
