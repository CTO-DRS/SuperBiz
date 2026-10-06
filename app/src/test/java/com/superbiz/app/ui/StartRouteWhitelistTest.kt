package com.superbiz.app.ui

import com.superbiz.app.ui.nav.StartRouteWhitelist
import org.junit.Assert.assertEquals
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
}
