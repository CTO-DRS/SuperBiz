package com.superbiz.app.ui.compose

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.core.AppPrefs
import com.superbiz.app.domain.DashboardPrefsP44
import com.superbiz.app.domain.ReportSectionsP45
import com.superbiz.app.ui.insights.InsightsCustomizeDialog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [P45-Q1] جولة 6 — جدار Compose للنافذة المعممة على أقسام التقارير
 * (Robolectric-Compose — نفس البنية المستقرة منذ P40/P44):
 *
 * • الوسائط الجديدة (labels/title/hint) تعرض تسميات الأقسام وعنوانها الصادق.
 * • النافذة تصدّر الإخفاء والتحريك بنفس عقود P44 (ترتيب كامل مادّي — المخفي
 *   يحفظ موضعه) ولا تلمس AppPrefs.
 * • حارس انحدار: بلا الوسائط الجديدة يبقى سلوك P44 حرفياً (تسميات البطاقات).
 *
 * حارس الاستطلاع P40: بيئة Robolectric إنجليزية (values-en) — التوكيدات على
 * سلاسل values-en، وخيط الاختبار هو اللوبِر فلا انتظار محجوب.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ReportSectionsP45Test {

    @get:Rule
    val compose = createComposeRule()

    private val canon = ReportSectionsP45.CANONICAL

    @Before
    fun setUp() {
        AppPrefs.dashboardLayout = null
    }

    @After
    fun tearDown() {
        AppPrefs.dashboardLayout = null
    }

    private fun labelsFor(): Map<String, String> = mapOf(
        "tools" to "Export tools",
        "periods" to "Periods",
        "kpi" to "Financial KPIs",
        "summary" to "Text summary",
        "insights" to "Insight cards",
    )

    // ─── النافذة المعممة ───

    @Test
    fun `النافذة تعرض عنوان الأقسام وتسمياتها القياسية كاملة افتراضيا`() {
        compose.setContent {
            InsightsCustomizeDialog(
                ReportSectionsP45.KEY, canon, null,
                onChange = {}, onDismiss = {},
                labels = labelsFor(),
                title = "Customize report sections",
                hint = "Order sections with arrows",
            )
        }
        compose.onNodeWithText("Customize report sections").assertExists()
        compose.onNodeWithText("Order sections with arrows").assertExists()
        compose.onNodeWithText("Export tools").assertExists()
        compose.onNodeWithText("Financial KPIs").assertExists()
        compose.onNodeWithText("Insight cards").assertExists()
        compose.onNodeWithText("Restore default").assertExists()
    }

    @Test
    fun `نقر تسمية قسم يصدر الإخفاء بترتيب كامل مادي`() {
        var emitted: DashboardPrefsP44.Layout? = null
        compose.setContent {
            InsightsCustomizeDialog(
                ReportSectionsP45.KEY, canon, null,
                onChange = { emitted = it }, onDismiss = {},
                labels = labelsFor(),
            )
        }
        compose.onNodeWithText("Export tools").performClick()
        val pref = emitted!!.screens.getValue(ReportSectionsP45.KEY)
        assertTrue("tools في المخفي", "tools" in pref.hidden)
        assertEquals("الترتيب المادي كامل (المخفي يحفظ موضعه)", canon, pref.order)
    }

    @Test
    fun `سهم التحريك يبدل موضعي أول قسماين ويصدّر الترتيب الكامل`() {
        var emitted: DashboardPrefsP44.Layout? = null
        compose.setContent {
            InsightsCustomizeDialog(
                ReportSectionsP45.KEY, canon, null,
                onChange = { emitted = it }, onDismiss = {},
                labels = labelsFor(),
            )
        }
        // سهم أسفل السطر الأول (tools تهبط موضعاً وperiods تصعد للرأس)
        compose.onAllNodesWithContentDescription("Move down")[0].performClick()
        val pref = emitted!!.screens.getValue(ReportSectionsP45.KEY)
        assertEquals("periods", pref.order.first())
        assertEquals("tools", pref.order[1])
        assertEquals("الترتيب المادي كامل (نفس العناصر بلا فقد)",
            canon.sorted(), pref.order.sorted())
        assertTrue("لا إخفاء من التحريك", pref.hidden.isEmpty())
    }

    // ─── حارس انحدار P44 ───

    @Test
    fun `بلا وسائط التعميم يبقى سلوك بطاقات الرؤى حرفيا`() {
        compose.setContent {
            InsightsCustomizeDialog("home", listOf("smart", "r9"), null, onChange = {}, onDismiss = {})
        }
        compose.onNodeWithText("Customize insight cards").assertExists()
        compose.onNodeWithText("Early smart insights").assertExists()
        compose.onNodeWithText("Liquidity & retention").assertExists()
    }
}
