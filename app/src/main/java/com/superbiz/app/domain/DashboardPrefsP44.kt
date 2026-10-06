package com.superbiz.app.domain

import com.superbiz.app.domain.backup.escapeJson
import com.superbiz.app.domain.backup.parseJson

/**
 * [P44-K1] جولة 5 — اللوحات المخصصة القابلة للتكييف: محرك تخصيص بطاقات الرؤى.
 *
 * وحدة التخصيص هي **مجموعة بطاقات** (Smart/R9..R15) داخل شاشة واحدة — كل شاشة
 * من الثماني (home/pos/invoices/inventory/reports/debts/checks/expenses) تعلن
 * مجموعاتها القياسية (canonical) بترتيبها التاريخي، والمحرك يطبّق على ذلك:
 *  - **الترتيب**: تبديل موضع أي مجموعة (أسهم أعلى/أسفل في نافذة التخصيص).
 *  - **الإظهار**: إخفاء/إظهار أي مجموعة (الإخفاء يحفظ موضعها كي تعود مكانه).
 * والحالة كلها سلسلة JSON واحدة تُخزَّن في DataStore (dashboard_layout) وتُزامَن
 * إلى AppPrefs بنفس عقد الخزنة الحيّة — null/فارغ = الافتراضي حرفياً (الترتيب
 * التاريخي والكل ظاهر).
 *
 * ─── العقود ───
 *  - **التسامح**: أي حمولة تالفة/غير متوقعة (JSON مكسور، أنواع خاطئة، مفاتيح
 *    مجهولة شاشةً كانت أم مجموعة) لا ترمي أبداً — تُتجاهل عند العرض وتُحفظ
 *    كما هي عند التسلسل (توافق أمامي: ترقية ثم هبوط ثم ترقية لا تفقد التخصيص).
 *  - **المجموعات الجديدة مستقبلاً**: أي مفتاح قياسي (مثل r16 في موجة قادمة) غير
 *    موجود في الترتيب المخزّن يُلحق آلياً بنهاية الترتيب بموضعه القياسي النسبي —
 *    بلا ترحيل ولا هجرة إعدادات.
 *  - **الحتمية**: التسلسل مرتّب (شاشات أبجدياً والمخفي أبجدياً) والدوران
 *    toJson→parse→toJson ينتج السلسلة نفسها حرفياً.
 *  - الملف نقي بلا أي اعتماد Android — JUnit مجرد (نمط LineTaxP41/DeepExportP43).
 */
object DashboardPrefsP44 {

    /** إصدار الصيغة — للاستقبال المستقبلي إن تغيّر الشكل */
    const val VERSION = 1

    /** مفاتيح الشاشات الثماني القابلة للتخصيص — ثوابت موثقة يشاركها الواجهة والاختبارات */
    const val SCREEN_HOME = "home"
    const val SCREEN_POS = "pos"
    const val SCREEN_INVOICES = "invoices"
    const val SCREEN_INVENTORY = "inventory"
    const val SCREEN_REPORTS = "reports"
    const val SCREEN_DEBTS = "debts"
    const val SCREEN_CHECKS = "checks"
    const val SCREEN_EXPENSES = "expenses"

    /** مفتاح مجموعة داخل شاشة — smart/r9..r15 (دلالته موضعي داخل الشاشة) */
    data class ScreenPref(
        val order: List<String>,
        val hidden: Set<String>,
    )

    /** حالة التخصيص الكلية — خريطة شاشة→تفضيلها */
    data class Layout(val screens: Map<String, ScreenPref>) {

        /** تسلسل حتمي: {"v":1,"s":{"home":{"o":[...],"h":[...]}}} — الشاشات أبجدياً والمخفي أبجدياً */
        fun toJson(): String {
            if (screens.isEmpty()) return ""
            val sb = StringBuilder()
            sb.append("{\"v\":").append(VERSION).append(",\"s\":{")
            var firstScreen = true
            for ((screen, pref) in screens.toSortedMap()) {
                if (!firstScreen) sb.append(",")
                firstScreen = false
                sb.append("\"").append(escapeJson(screen)).append("\":{")
                sb.append("\"o\":[")
                sb.append(pref.order.joinToString(",") { "\"${escapeJson(it)}\"" })
                sb.append("]")
                if (pref.hidden.isNotEmpty()) {
                    sb.append(",\"h\":[")
                    sb.append(pref.hidden.toSortedSet().joinToString(",") { "\"${escapeJson(it)}\"" })
                    sb.append("]")
                }
                sb.append("}")
            }
            sb.append("}}")
            return sb.toString()
        }

        companion object {

            /** الحالة الفارغة = كل شيء بالافتراضي */
            val EMPTY = Layout(emptyMap())

            /**
             * تحليل متسامح: null/فارغ/تالف/غير متوقع ⇒ EMPTY — لا يرمي أبداً.
             * المفاتيح المجهولة (شاشات أو مجموعات) تُحفظ كما هي (توافق أمامي).
             */
            fun parse(raw: String?): Layout {
                if (raw.isNullOrBlank()) return EMPTY
                val root = try { parseJson(raw) } catch (e: Exception) { return EMPTY }
                if (root !is Map<*, *>) return EMPTY
                val s = root["s"] ?: return EMPTY
                if (s !is Map<*, *>) return EMPTY
                val out = LinkedHashMap<String, ScreenPref>()
                for ((k, v) in s) {
                    val screen = k as? String ?: continue
                    if (v !is Map<*, *>) continue
                    val order = (v["o"] as? List<*>)
                        ?.mapNotNull { it as? String }
                        ?.distinct()
                        ?: emptyList()
                    val hidden = (v["h"] as? List<*>)
                        ?.mapNotNull { it as? String }
                        ?.toSet()
                        ?: emptySet()
                    out[screen] = ScreenPref(order, hidden)
                }
                return Layout(out)
            }
        }
    }

    /**
     * الترتيب الفعّال للعرض: يبدأ من الترتيب المخزّن (المعروف فقط بلا تكرار)،
     * يلحق المفاتيح القياسية الغائبة بموضعها، ثم يُسقط المخفي — الافتراضي
     * (لا تفضيل مخزّن) هو الترتيب القياسي كاملاً ظاهراً.
     */
    fun effectiveOrder(screen: String, canonical: List<String>, layout: Layout): List<String> {
        val pref = layout.screens[screen] ?: return canonical
        val known = canonical.toSet()
        val stored = pref.order.filter { it in known }
        val missing = canonical.filter { it !in stored }
        return (stored + missing).filter { it !in pref.hidden }
    }

    /** نسخة راحة على السلسلة الخام — نفس الدلالة */
    fun effectiveOrder(screen: String, canonical: List<String>, raw: String?): List<String> =
        effectiveOrder(screen, canonical, Layout.parse(raw))

    /**
     * إخفاء/إظهار مجموعة: مفتاح خارج القياسي يُرفض صامتاً (لا مفاتيح وهمية)،
     * وأول تعديل على شاشة بلا تفضيل يبدئي ترتيبها بالقياسي كاملاً.
     */
    fun withToggle(layout: Layout, screen: String, key: String, canonical: List<String>): Layout {
        if (key !in canonical.toSet()) return layout
        val pref = layout.screens[screen] ?: ScreenPref(canonical, emptySet())
        val order = pref.order.ifEmpty { canonical }
        val hidden = if (key in pref.hidden) pref.hidden - key else pref.hidden + key
        return copyWith(layout, screen, ScreenPref(order, hidden))
    }

    /**
     * تحريك مجموعة بموضعها في الترتيب الكامل (الظاهر والمخفي معاً — النافذة
     * تعرض الكل): delta = +1 نحو أعلى القائمة (الموضع 0) و −1 نحو أسفلها —
     * الخروج عن الحدود يبقي الحالة كما هي.
     */
    fun withMove(layout: Layout, screen: String, index: Int, delta: Int, canonical: List<String>): Layout {
        val pref = layout.screens[screen] ?: ScreenPref(canonical, emptySet())
        val order = pref.order.ifEmpty { canonical }
        if (index !in order.indices) return layout
        val target = if (delta >= 0) index - 1 else index + 1
        if (target !in order.indices) return layout
        val moved = order.toMutableList()
        val tmp = moved[index]
        moved[index] = moved[target]
        moved[target] = tmp
        return copyWith(layout, screen, ScreenPref(moved, pref.hidden))
    }

    /** استعادة الافتراضي: شاشة واحدة (تُحذف من الحالة) أو الكل عند screen = null */
    fun withReset(layout: Layout, screen: String?): Layout =
        if (screen == null) Layout.EMPTY
        else Layout(layout.screens - screen)

    // ── أدوات داخلية ──

    private fun copyWith(layout: Layout, screen: String, pref: ScreenPref): Layout {
        val screens = LinkedHashMap(layout.screens)
        screens[screen] = pref
        return Layout(screens)
    }
}
