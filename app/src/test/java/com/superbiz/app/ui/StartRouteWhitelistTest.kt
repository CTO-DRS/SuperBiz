package com.superbiz.app.ui

import com.superbiz.app.ui.nav.StartRouteWhitelist
import com.superbiz.app.ui.nav.isStartRouteAllowed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P30-A] حارس انحدار لقائمة سماح فتح الشاشات من الإشعارات/الويدجت:
 * كل هدف نقرة جديد في AutomationLogic.routeIntent يجب أن يكون عضواً هنا
 * وإلا تجاهله التنقل بصمت بعد تجاوز القفل. القيم حرفية عمداً — تلتقط أي
 * تغيير عرضي في قيم ثوابت Routes نفسها.
 */
class StartRouteWhitelistTest {

    @Test
    fun p30_contextual_tap_targets_present() {
        // أهداف النقر السياقية الأربعة (P30-A) + المفضّلات (إشعار الزيارات)
        assertTrue("debts عضو", "debts" in StartRouteWhitelist)
        assertTrue("checks عضو", "checks" in StartRouteWhitelist)
        assertTrue("installments عضو", "installments" in StartRouteWhitelist)
        assertTrue("inventory عضو", "inventory" in StartRouteWhitelist)
        assertTrue("favorites عضو", "favorites" in StartRouteWhitelist)
        // [P30-A] فشل النسخ الاحتياطي يفتح مركز الإعدادات
        assertTrue("settings_hub عضو", "settings_hub" in StartRouteWhitelist)
    }

    @Test
    fun core_tab_still_whitelisted() {
        // الشاشات الأساسية تبقى قابلة للفتح من الويدجت
        assertTrue("home عضو", "home" in StartRouteWhitelist)
        assertTrue("pos عضو", "pos" in StartRouteWhitelist)
        assertTrue("invoices عضو", "invoices" in StartRouteWhitelist)
        assertTrue("reports عضو", "reports" in StartRouteWhitelist)
    }

    @Test
    fun no_blank_or_duplicate_routes() {
        assertTrue(StartRouteWhitelist.none { it.isBlank() })
        assertEquals("لا تكرار", StartRouteWhitelist.size, StartRouteWhitelist.toSet().size)
    }

    // ═══ [تدقيق H-4] حلقة QR كانت ميتة من الطرف للطرف ═══

    @Test
    fun verify_route_is_whitelisted() {
        // الوجهة نفسها عضو — الحارس التقاطي لأي حذف عرضي
        assertTrue("statement_verify عضو", "statement_verify" in StartRouteWhitelist)
    }

    @Test
    fun deepLink_verify_with_vid_passes_gate() {
        // الصيغة الحرفية التي يولدها MainActivity.parseVerifyLink من superbiz://verify/<vid>
        assertTrue(
            "رابط التحقق المعامَل يجب أن يجتاز البوابة (تدقيق H-4)",
            isStartRouteAllowed("statement_verify?vid=SB-ST-20260924-000001")
        )
        // محارف مُرمّزة (URLEncoder) بعد ? لا تكسر التطبيع
        assertTrue(isStartRouteAllowed("statement_verify?vid=SB%2DST%2D1"))
        // الشكل العاري (بلا معاملات) يجتاز أيضاً — البوابة تطبّع لاحقة الاستعلام
        assertTrue(isStartRouteAllowed("statement_verify"))
    }

    @Test
    fun gate_still_blocks_non_whitelisted_and_param_tricks() {
        // المسارات الحسّاسة تبقى محجوبة حتى مع لاحقة استعلام مزيّفة
        assertFalse(isStartRouteAllowed("error_log"))
        assertFalse(isStartRouteAllowed("pro?trial=1"))
        assertFalse(isStartRouteAllowed("kpi_board"))
        assertFalse(isStartRouteAllowed("search?q=anything"))
        // بادئة مضللة: التطبيع substringBefore(؟) لا يفتح مساراً غير معلن
        assertFalse(isStartRouteAllowed("error_log?statement_verify"))
        // المسار المعلن بلواحق مضللة لا يتحول لمسار آخر — العضوية بالمسار الأساسي فقط
        assertTrue(isStartRouteAllowed("home?x=/error_log"))
    }
}
