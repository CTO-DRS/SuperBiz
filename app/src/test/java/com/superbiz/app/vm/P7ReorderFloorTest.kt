package com.superbiz.app.vm

import com.superbiz.app.domain.algo.ReplenishMath
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [P7-L7 إصلاح] — اختبار تمييزي لسياسة أرضية حد الطلب (reorderLevel) الموحدة
 * في طبقة الاستهلاك (R11InsightsVM/B10)، مع بقاء الخوارزمية النقية
 * ReplenishMath.reorderPlan في R11Smart كما هي حرفياً (ملف غير مملوك للموجة).
 *
 * التمييزي: منتج مخزونه 10 وحد الطلبه 20 وبلا مبيعات (متوسط الطلب=0) — كان B10
 * لا يقترح له شيئاً أبداً (هدف الخطة 0 ⇒ OK) بينما LowStockWidget ينبّه عليه
 * عبر isLow؛ الآن يقترح تغطية الفجوة حتى الأرضية (10 وحدات، SOON).
 */
class P7ReorderFloorTest {

    // ─── بدون حد طلب: سلوك الخطة القديم حرفياً ───

    @Test
    fun `no reorder level keeps plan untouched`() {
        val plan = ReplenishMath.reorderPlan(stockQty = 7.0, avgDaily = 5.0, leadDays = 4.0, safetyQty = 8.0)
        val eff = ReorderFloorPolicy.withFloor(7.0, 0.0, plan)
        assertEquals(plan.target, eff.target, 1e-9)
        assertEquals(plan.orderQty, eff.orderQty, 1e-9)
        assertEquals(plan.verdict, eff.verdict)
        // وحد سالب (حقل غير مُدخل) يعامل كعدم حد
        val eff2 = ReorderFloorPolicy.withFloor(7.0, -5.0, plan)
        assertEquals(plan.verdict, eff2.verdict)
    }

    // ─── الحالة التمييزية: أرضية فوق هدف الخطة على منتج بلا طلب ───

    @Test
    fun `floor above plan target suggests gap even without demand`() {
        // خطة طلبية صفرية: dAvg=0 ⇒ target=0, qty=0, OK
        val plan = ReplenishMath.reorderPlan(stockQty = 10.0, avgDaily = 0.0, leadDays = 7.0, safetyQty = 0.0)
        assertEquals("OK", plan.verdict)
        // الويدجت يعتبره منخفضاً: isLow = 20>0 && 10<=20
        val eff = ReorderFloorPolicy.withFloor(stockQty = 10.0, reorderLevel = 20.0, plan = plan)
        assertEquals(20.0, eff.target, 1e-9)     // الهدف الفعّال = الأرضية
        assertEquals(10.0, eff.orderQty, 1e-9)   // سقف(20−10) = 10
        assertEquals("SOON", eff.verdict)        // خرج من OK لأن الجرد عند/تحت الأرضية
    }

    @Test
    fun `floor below plan target never lowers target or qty`() {
        // خطة طلبية: target = 5×4+3 = 23, qty = 16, SOON (7>3 و7<23)
        val plan = ReplenishMath.reorderPlan(stockQty = 7.0, avgDaily = 5.0, leadDays = 4.0, safetyQty = 3.0)
        assertEquals("SOON", plan.verdict)
        val eff = ReorderFloorPolicy.withFloor(7.0, 20.0, plan)
        assertEquals(23.0, eff.target, 1e-9)     // الأرضية 20 أدنى من الهدف — لا تُخفض
        assertEquals(16.0, eff.orderQty, 1e-9)   // والكمية كما هي
        assertEquals("SOON", eff.verdict)
    }

    @Test
    fun `floor raises qty when gap to floor is wider than plan gap`() {
        // خطة: target=19, qty=12 (مخزون 7)؛ أرضية 25 ⇒ الهدف 25 والكمية 18
        val plan = ReplenishMath.reorderPlan(stockQty = 7.0, avgDaily = 2.0, leadDays = 7.0, safetyQty = 5.0)
        assertEquals(19.0, plan.target, 1e-9)
        assertEquals(12.0, plan.orderQty, 1e-9)
        val eff = ReorderFloorPolicy.withFloor(7.0, 25.0, plan)
        assertEquals(25.0, eff.target, 1e-9)
        assertEquals(18.0, eff.orderQty, 1e-9)   // max(12, سقف(25−7))
        assertEquals("SOON", eff.verdict)
    }

    // ─── أحكام الخطة الأقوى تبقى كما هي ───

    @Test
    fun `urgent and out verdicts survive the floor`() {
        // URGENT: الجرد ≤ مخزون الأمان — أقوى من SOON الأرضية
        val urgent = ReplenishMath.reorderPlan(stockQty = 7.0, avgDaily = 5.0, leadDays = 4.0, safetyQty = 8.0)
        assertEquals("URGENT", urgent.verdict)
        val effU = ReorderFloorPolicy.withFloor(7.0, 9.0, urgent)
        assertEquals("URGENT", effU.verdict)
        assertEquals(28.0, effU.target, 1e-9)
        // OUT: نفاد مع هدف طلب موجب — الأرضية لا تقلبه ولا تخفض الكمية
        val out = ReplenishMath.reorderPlan(stockQty = 0.0, avgDaily = 5.0, leadDays = 4.0, safetyQty = 8.0)
        assertEquals("OUT", out.verdict)
        val effO = ReorderFloorPolicy.withFloor(0.0, 10.0, out)
        assertEquals("OUT", effO.verdict)
        assertEquals(28.0, effO.orderQty, 1e-9)  // max(28, سقف(28−0)) = 28
    }

    @Test
    fun `stock above floor with healthy plan stays OK`() {
        // بلا طلب ومخزون فوق الأرضية ⇒ لا اقتراح (متوافق مع الويدجت: isLow=false)
        val plan = ReplenishMath.reorderPlan(stockQty = 30.0, avgDaily = 0.0, leadDays = 7.0, safetyQty = 0.0)
        val eff = ReorderFloorPolicy.withFloor(30.0, 20.0, plan)
        assertEquals("OK", eff.verdict)
        assertEquals(0.0, eff.orderQty, 1e-9)
        // ومع طلب حيّ فوق الأرضية: الهدف الطلبي هو الحاكم ولا يتغير
        val plan2 = ReplenishMath.reorderPlan(stockQty = 25.0, avgDaily = 1.0, leadDays = 7.0, safetyQty = 0.0)
        val eff2 = ReorderFloorPolicy.withFloor(25.0, 5.0, plan2)
        assertEquals(plan2.verdict, eff2.verdict)
        assertEquals(plan2.orderQty, eff2.orderQty, 1e-9)
    }

    @Test
    fun `boundary exactly at floor yields zero-order non-negative result`() {
        // الجرد عند الأرضية تماماً بلا طلب: فجوة صفر — صف بكمية 0 لا يُعرض
        // في بطاقة الاقتراح (عقد موثق) لكنه ليس سالباً ولا OK
        val plan = ReplenishMath.reorderPlan(stockQty = 20.0, avgDaily = 0.0, leadDays = 7.0, safetyQty = 0.0)
        val eff = ReorderFloorPolicy.withFloor(20.0, 20.0, plan)
        assertEquals(20.0, eff.target, 1e-9)
        assertEquals(0.0, eff.orderQty, 1e-9)
        assertEquals("SOON", eff.verdict)
    }
}
