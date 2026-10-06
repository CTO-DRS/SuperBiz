package com.superbiz.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P44-K1] جولة 5 — عقد محرك اللوحات المخصصة القابلة للتكييف (نقي بلا Android):
 *
 * • التحليل المتسامح: null/فارغ/تالف/أنواع خاطئة ⇒ الحالة الفارغة بلا رمي أبداً،
 *   والمفاتيح المجهولة (شاشات ومجموعات) تُحفظ كما هي (توافق أمامي هبوط→ترقية).
 * • الحتمية: toJson→parse→toJson ينتج السلسلة نفسها حرفياً، والشاشات والمخفي
 *   أبجدياً في التسلسل أياً كان ترتيب الإدخال.
 * • الترتيب الفعّال: القياسي افتراضياً، المخزون يطبق والمخفي يُسقط، والغائب
 *   القياسي يُلحق بنهاية (موجة مستقبلية تظهر آلياً)، والمجهول المخزن لا يُعرض.
 * • التحويلات: الإخفاء/الإظهار والتحريك بحدها والاستعادة (شاشة/الكل) — والمفتاح
 *   الوهمي خارج القياسي يُرفض صامتاً.
 */
class DashboardPrefsP44Test {

    // قياسي تجريبي بخمس مجموعات (نفس شكل الشاشات الحقيقية)
    private val canon = listOf("smart", "r9", "r10", "r11", "r12")

    // ─── التحليل المتسامح ───

    @Test
    fun parse_nullAndBlankAndEmptyGiveEmptyLayout() {
        assertEquals(DashboardPrefsP44.Layout.EMPTY, DashboardPrefsP44.Layout.parse(null))
        assertEquals(DashboardPrefsP44.Layout.EMPTY, DashboardPrefsP44.Layout.parse(""))
        assertEquals(DashboardPrefsP44.Layout.EMPTY, DashboardPrefsP44.Layout.parse("   "))
    }

    @Test
    fun parse_corruptPayloadNeverThrows() {
        for (bad in listOf("{corrupt", "[]", "42", "\"نص\"", "{\"v\":1}", "{\"s\":5}", "{\"s\":\"x\"}")) {
            assertEquals("فشل على: $bad", DashboardPrefsP44.Layout.EMPTY, DashboardPrefsP44.Layout.parse(bad))
        }
    }

    @Test
    fun parse_wrongTypesToleratedAndUnknownKeysPreserved() {
        // شاشة سليمة + شاشة بأنواع خاطئة + شاشة مجهولة — المجهول يُحفظ (توافق أمامي)
        val raw = """{"v":1,"s":{
            "home":{"o":["r10","smart"],"h":["r12"]},
            "pos":{"o":5,"h":"x"},
            "future-screen":{"o":["r16"],"h":[]}
        }}"""
        val layout = DashboardPrefsP44.Layout.parse(raw)
        assertEquals(listOf("r10", "smart"), layout.screens["home"]!!.order)
        assertEquals(setOf("r12"), layout.screens["home"]!!.hidden)
        // الأنواع الخاطئة ⇒ تفضيل فارغ للشاشة (لا رمي ولا فقدان شاشة)
        assertEquals(emptyList<String>(), layout.screens["pos"]!!.order)
        // المجهول محفوظ كما هو
        assertEquals(listOf("r16"), layout.screens["future-screen"]!!.order)
    }

    @Test
    fun roundtrip_isDeterministicByteForByte() {
        val layout = DashboardPrefsP44.Layout(
            mapOf(
                "home" to DashboardPrefsP44.ScreenPref(listOf("r10", "smart", "r9", "r11", "r12"), setOf("r12", "r9")),
                "pos" to DashboardPrefsP44.ScreenPref(listOf("r15", "r11", "r12", "r13", "r14"), emptySet()),
            )
        )
        val json = layout.toJson()
        // الشاشات أبجدياً والمخفي أبجدياً مهما كان ترتيب الإدخال
        assertEquals(
            """{"v":1,"s":{"home":{"o":["r10","smart","r9","r11","r12"],"h":["r12","r9"]},""" +
                """"pos":{"o":["r15","r11","r12","r13","r14"]}}}""",
            json
        )
        // الدوران الحرفي
        assertEquals(json, DashboardPrefsP44.Layout.parse(json).toJson())
    }

    @Test
    fun serialize_emptyLayoutIsEmptyString() {
        assertEquals("", DashboardPrefsP44.Layout.EMPTY.toJson())
    }

    // ─── الترتيب الفعّال ───

    @Test
    fun effectiveOrder_defaultsToCanonicalWhenNoPref() {
        assertEquals(canon, DashboardPrefsP44.effectiveOrder("home", canon, null))
        assertEquals(canon, DashboardPrefsP44.effectiveOrder("home", canon, ""))
        assertEquals(canon, DashboardPrefsP44.effectiveOrder("home", canon, "{\"s\":{}}"))
        // شاشة لها تفضيل في شاشة أخرى لا تمس هذه
        val raw = """{"s":{"pos":{"o":["r12"]}}}"""
        assertEquals(canon, DashboardPrefsP44.effectiveOrder("home", canon, raw))
    }

    @Test
    fun effectiveOrder_appliesStoredOrderAndDropsHidden() {
        val raw = """{"s":{"home":{"o":["r10","smart","r9","r11","r12"],"h":["r11"]}}}"""
        assertEquals(listOf("r10", "smart", "r9", "r12"), DashboardPrefsP44.effectiveOrder("home", canon, raw))
    }

    @Test
    fun effectiveOrder_appendsMissingCanonicalKeysAtEnd() {
        // موجة مستقبلية تضيف مجموعات جديدة — تظهر آلياً بنهاية الترتيب بلا هجرة
        val raw = """{"s":{"home":{"o":["smart","r10"]}}}"""
        assertEquals(
            listOf("smart", "r10", "r9", "r11", "r12"),
            DashboardPrefsP44.effectiveOrder("home", canon, raw)
        )
    }

    @Test
    fun effectiveOrder_ignoresUnknownAndDuplicateStoredKeys() {
        // مفتاح مجهول (هبوط عن نسخة أحدث) ومكرر — يُرشّحان ولا يكسران العرض
        val raw = """{"s":{"home":{"o":["r16","smart","r16","r10"]}}}"""
        assertEquals(
            listOf("smart", "r10", "r9", "r11", "r12"),
            DashboardPrefsP44.effectiveOrder("home", canon, raw)
        )
    }

    // ─── التحويلات ───

    @Test
    fun toggle_hidesThenUnhidesAndMaterializesFullOrder() {
        val l1 = DashboardPrefsP44.withToggle(DashboardPrefsP44.Layout.EMPTY, "home", "r10", canon)
        // أول تعديل يبدئي الترتيب بالقياسي كاملاً ثم يخفي
        assertEquals(canon, l1.screens["home"]!!.order)
        assertEquals(setOf("r10"), l1.screens["home"]!!.hidden)
        assertEquals(listOf("smart", "r9", "r11", "r12"), DashboardPrefsP44.effectiveOrder("home", canon, l1))
        // الإظهار يعيدها دون فقدان الترتيب المخصص
        val l2 = DashboardPrefsP44.withToggle(l1, "home", "r10", canon)
        assertEquals(emptySet<String>(), l2.screens["home"]!!.hidden)
        assertEquals(canon, DashboardPrefsP44.effectiveOrder("home", canon, l2))
    }

    @Test
    fun toggle_rejectsPhantomKeySilently() {
        val l = DashboardPrefsP44.withToggle(DashboardPrefsP44.Layout.EMPTY, "home", "xxx", canon)
        assertEquals(DashboardPrefsP44.Layout.EMPTY, l)
    }

    @Test
    fun move_swapsNeighborAndRespectsBounds() {
        val start = DashboardPrefsP44.Layout(
            mapOf("home" to DashboardPrefsP44.ScreenPref(canon, emptySet()))
        )
        // تحريك العنصر 1 لأعلى (+1) يبدله مع العنصر 0
        val up = DashboardPrefsP44.withMove(start, "home", 1, +1, canon)
        assertEquals(listOf("r9", "smart", "r10", "r11", "r12"), up.screens["home"]!!.order)
        // تحريكه لأسفل يعيد الأصل
        val back = DashboardPrefsP44.withMove(up, "home", 0, -1, canon)
        assertEquals(canon, back.screens["home"]!!.order)
        // الحدود: أعلى القائمة لأعلى لا يغيّر، وآخرها لأسفل لا يغيّر
        assertEquals(
            start.screens["home"]!!.order,
            DashboardPrefsP44.withMove(start, "home", 0, +1, canon).screens["home"]!!.order
        )
        assertEquals(
            start.screens["home"]!!.order,
            DashboardPrefsP44.withMove(start, "home", canon.size - 1, -1, canon).screens["home"]!!.order
        )
        // فهرس خارج الحدود لا يغيّر
        assertEquals(start, DashboardPrefsP44.withMove(start, "home", 99, +1, canon))
    }

    @Test
    fun move_onEmptyLayoutMaterializesCanonicalFirst() {
        val moved = DashboardPrefsP44.withMove(DashboardPrefsP44.Layout.EMPTY, "home", 0, -1, canon)
        assertEquals(listOf("r9", "smart", "r10", "r11", "r12"), moved.screens["home"]!!.order)
        // والمخفي الذي لم يُمَس يبقى فارغاً
        assertTrue(moved.screens["home"]!!.hidden.isEmpty())
    }

    @Test
    fun reset_clearsOneScreenOrAll() {
        val layout = DashboardPrefsP44.Layout(
            mapOf(
                "home" to DashboardPrefsP44.ScreenPref(listOf("r10", "smart"), setOf("r9")),
                "pos" to DashboardPrefsP44.ScreenPref(listOf("r15"), emptySet()),
            )
        )
        val one = DashboardPrefsP44.withReset(layout, "home")
        assertEquals(setOf("pos"), one.screens.keys)
        val all = DashboardPrefsP44.withReset(layout, null)
        assertEquals(DashboardPrefsP44.Layout.EMPTY, all)
    }

    @Test
    fun fullScenario_toggleMoveRoundtripSurvivesString() {
        // سيناريو كامل: إخفاء + تحريك ثم دوران سلسلة حرفي والترتيب الفعّال ثابت
        var l = DashboardPrefsP44.withToggle(DashboardPrefsP44.Layout.EMPTY, "home", "smart", canon)
        l = DashboardPrefsP44.withMove(l, "home", 3, +1, canon) // r11 صعد فوق r10
        val json = l.toJson()
        val parsed = DashboardPrefsP44.Layout.parse(json)
        assertEquals(json, parsed.toJson())
        assertEquals(
            listOf("r9", "r11", "r10", "r12"),
            DashboardPrefsP44.effectiveOrder("home", canon, parsed)
        )
    }
}
