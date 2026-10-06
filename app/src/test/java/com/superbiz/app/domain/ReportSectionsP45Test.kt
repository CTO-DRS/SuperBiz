package com.superbiz.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P45-Q1] جولة 6 — اختبارات أقسام التقارير المخصصة (نمط DashboardPrefsP44Test).
 *
 * العقد المجرَّب:
 *  - القائمة القياسية الثابتة: 24 قسماً فريداً — الأدوات أول والذكاء آخر
 *    (الترتيب التاريخي الذي تعلنه الواجهة).
 *  - الافتراضي: بلا تفضيل مخزّن = القائمة القياسية كاملة ظاهرة — الشاشة كما
 *    كانت حرفياً قبل الجولة.
 *  - التسامح: المفاتيح المجهولة المخزّنة تُتجاهل عند العرض (وتُحفظ كما هي
 *    بعقد المحرك)، والقيم القياسية الغائبة تُلحق آلياً بموضعها.
 *  - الإخفاء والتحريك عبر المحرك المشترك نفسه — لا مسار ثانٍ للتخصيص.
 *  - الاستعادة تحذف مفتاح الأقسام فقط ولا تمس تخصيص بطاقات الشاشات الأخرى.
 */
class ReportSectionsP45Test {

    private val canon = ReportSectionsP45.CANONICAL

    // ── عقد القائمة القياسية ──
    @Test
    fun canonicalList_isStableDistinctAndOrdered() {
        assertEquals(24, canon.size)
        assertEquals("لا تكرار في المفاتيح القياسية", canon.size, canon.toSet().size)
        assertEquals("tools", canon.first())
        assertEquals("insights", canon.last())
    }

    // ── الافتراضي: القياسي كاملاً ظاهراً ──
    @Test
    fun defaultOrder_isCanonicalEntirely() {
        assertEquals(canon, ReportSectionsP45.order(null))
        assertEquals(canon, ReportSectionsP45.order(""))
        assertEquals("الحمولة التالفة لا تكسر الشاشة", canon, ReportSectionsP45.order("{{{not json"))
    }

    // ── التسامح: المجهول يُتجاهل والغائب يُلحق بموضعه ──
    @Test
    fun unknownStoredKeys_toleratedAndMissingCanonicalAppended() {
        val raw = """{"v":1,"s":{"rep_sections":{"o":["trial","ghost_key","tools"],"h":[]}}}"""
        val order = ReportSectionsP45.order(raw)
        assertEquals("المجهول يُسقط والمعروفان يبقيان ترتيبهما",
            listOf("trial", "tools"), order.take(2))
        assertEquals("كل القياسي يظهر (الغائب يُلحق)", canon.sorted(), order.sorted())
        assertEquals("بلا إخفاء", canon.size, order.size)
    }

    // ── الإخفاء والتحريك عبر المحرك المشترك ──
    @Test
    fun hideAndMove_throughSharedEngine_reflectedInOrder() {
        var layout = DashboardPrefsP44.Layout.EMPTY
        layout = DashboardPrefsP44.withToggle(layout, ReportSectionsP45.KEY, "kpi", canon)
        assertEquals("المخفي يختفي والبقية بالترتيب القياسي",
            canon.filter { it != "kpi" }, ReportSectionsP45.order(layout.toJson()))
        // تحريك أول قسماً (tools يهبط موضعاً واحداً: index 0 delta -1)
        layout = DashboardPrefsP44.withMove(layout, ReportSectionsP45.KEY, 0, -1, canon)
        val moved = ReportSectionsP45.order(layout.toJson())
        assertEquals("periods", moved.first())
        assertEquals("tools", moved[1])
        assertTrue("المخفي يبقى مخفياً بعد التحريك", "kpi" !in moved)
    }

    // ── الاستعادة تحذف الأقسام فقط ولا تمس تخصيص الشاشات الأخرى ──
    @Test
    fun reset_clearsOnlySectionsKey_keepsOtherScreens() {
        val raw = """{"v":1,"s":{"home":{"o":["r9","smart"],"h":[]},"rep_sections":{"o":["trial"],"h":["kpi"]}}}"""
        val layout = DashboardPrefsP44.Layout.parse(raw)
        val reset = DashboardPrefsP44.withReset(layout, ReportSectionsP45.KEY)
        assertEquals("تخصيص الأقسام عاد للافتراضي", canon, ReportSectionsP45.order(reset.toJson()))
        assertEquals("تخصيص بطاقات الرئيسية محفوظ", listOf("r9", "smart"),
            DashboardPrefsP44.effectiveOrder("home", listOf("smart", "r9"), reset.toJson()))
    }

    // ── حتمية الدوران على مفتاح الأقسام ──
    @Test
    fun roundTrip_isDeterministic_onSectionsKey() {
        var layout = DashboardPrefsP44.Layout.EMPTY
        layout = DashboardPrefsP44.withToggle(layout, ReportSectionsP45.KEY, "trial", canon)
        layout = DashboardPrefsP44.withMove(layout, ReportSectionsP45.KEY, 1, +1, canon)
        val once = layout.toJson()
        assertEquals(once, DashboardPrefsP44.Layout.parse(once).toJson())
    }
}
