package com.superbiz.app.domain

import com.superbiz.app.domain.sync.SyncConflictPolicy
import com.superbiz.app.domain.telemetry.TelemetryContract
import com.superbiz.app.domain.telemetry.TelemetryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [E1.3+E1.4 V 3.4.0] عقود القياس البعيد وسياسة التعارض — مقفولة من أول تشغيل
 * ═══════════════════════════════════════════════════════════════════════════
 * نمط WebhookKitTest (R10): عقود إمكانية جديدة تُقفل بجولة خضراء أولى
 * موثقة — لا مسرحية حمراء لخلية بلا سلوك سابق.
 *
 * Telemetry: القائمة المغلقة، الفشل المغلق على اسم غريب، صمت صفر بلا موافقة،
 * النوع الطارد للمحتوى (Map<String,Long> حصراً)، وسحب الموافقة يفرغ الخروج.
 * SyncConflictPolicy: جدول الحسم، الفشل المغلق على كيان غريب، ملكية الحقول
 * المحلية (ADR-002 D3)، الأولوية الزمنية + تعادل-يرجّح-المحلي، والتماثل.
 * فحص الخصائص: 500 سيناريو مبذر (Random(42)) يقلب الدمج ويقفل الثوابت.
 */
class TelemetryAndConflictPolicyTest {

    // ═══ E1.3 — TelemetryContract ═══

    @Test
    fun `allowlist is closed and every event carries unit retention and purpose`() {
        assertTrue(TelemetryContract.ALLOWED.isNotEmpty())
        TelemetryContract.ALLOWED.forEach { (name, spec) ->
            assertTrue("unit إجمالي حصراً: $name", spec.unit == "count" || spec.unit == "ms")
            assertTrue("مدة حفظ موجبة: $name", spec.retentionDays > 0)
            assertTrue("غرض PDPL معلن: $name", spec.purpose.isNotBlank())
        }
    }

    @Test
    fun `unknown event name fails closed - no silent new event`() {
        assertThrows(IllegalArgumentException::class.java) {
            TelemetryContract.requireAllowed("user_names_list")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TelemetryState(false).record("not_in_list")
        }
    }

    @Test
    fun `zero silence - nothing recorded and nothing egresses without consent`() {
        val t = TelemetryState(consented = false)
        t.record("sync_round_ok")
        t.record("sync_round_ok")
        assertEquals(0, t.snapshot().size)
        assertEquals(0, t.egressPayload.size)
        // حتى لو سبقت البيانات الموافقة: سحب الموافقة يفرغ الخروج حرفياً
        t.setConsent(true)
        t.record("sync_backlog_size", 5)
        assertEquals(1, t.egressPayload.size)
        t.setConsent(false)
        assertEquals(0, t.egressPayload.size)
    }

    @Test
    fun `with consent counters accumulate and payload type is Long-only`() {
        val t = TelemetryState(consented = true)
        t.record("sync_round_ok", 3)
        t.record("sync_round_ok")
        t.record("sync_poison_docs", 7)
        assertEquals(4L, t.snapshot()["sync_round_ok"])
        assertEquals(7L, t.snapshot()["sync_poison_docs"])
        t.egressPayload.forEach { (k, v) ->
            assertTrue("مفتاح ضمن القائمة: $k", TelemetryContract.ALLOWED.containsKey(k))
            assertTrue("قيمة Long لا نص: $k", v is Long)
        }
        assertEquals(t.snapshot(), t.egressPayload)
    }

    // ═══ E1.4 — SyncConflictPolicy ═══

    @Test
    fun `every synced entity has exactly one named row - closed table`() {
        val expected = setOf(
            "parties", "products", "currencies", "visits", "statementTemplates",
            "noteTemplates", "coupons", "couponRedemptions", "expenses"
        )
        assertEquals(expected, SyncConflictPolicy.RULES.keys)
    }

    @Test
    fun `unknown entity fails closed`() {
        assertThrows(IllegalArgumentException::class.java) {
            SyncConflictPolicy.ruleFor("ghostEntity")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyncConflictPolicy.merge("ghostEntity", emptyMap(), emptyMap(), 1L, 1L)
        }
    }

    @Test
    fun `ADR-002 D3 - stockQty is locally owned and survives every remote merge`() {
        assertTrue(SyncConflictPolicy.isLocalOwned("products", "stockQty"))
        val local = mapOf<String, Any?>("name" to "صنف", "stockQty" to 42.0, "syncUpdatedAt" to 100L)
        val incoming = mapOf<String, Any?>("name" to "صنف بعيد", "stockQty" to 0.0, "syncUpdatedAt" to 200L)
        val out = SyncConflictPolicy.merge("products", local, incoming, 200L, 100L)
        assertEquals("القادم الأحدث يفوز في الاسم", "صنف بعيد", out["name"])
        assertEquals("المخزون ملك الجهاز — لا يُكتب من بعيد أبداً", 42.0, out["stockQty"] as Double, 0.0)
        assertEquals(200L, out["syncUpdatedAt"])
    }

    @Test
    fun `party favorite and geo are locally owned`() {
        assertTrue(SyncConflictPolicy.isLocalOwned("parties", "favorite"))
        assertTrue(SyncConflictPolicy.isLocalOwned("parties", "lat"))
        assertTrue(SyncConflictPolicy.isLocalOwned("parties", "lng"))
        assertFalse(SyncConflictPolicy.isLocalOwned("parties", "name"))
        assertFalse(SyncConflictPolicy.isLocalOwned("currencies", "rateToBase"))
    }

    @Test
    fun `currencies are remote-wins even when local is newer`() {
        assertEquals(
            SyncConflictPolicy.Side.REMOTE,
            SyncConflictPolicy.resolveSide("currencies", 500L, 100L)
        )
    }

    @Test
    fun `LWW - newer remote wins and ties favor local`() {
        assertEquals(
            SyncConflictPolicy.Side.REMOTE,
            SyncConflictPolicy.resolveSide("parties", 100L, 200L)
        )
        assertEquals(
            SyncConflictPolicy.Side.LOCAL,
            SyncConflictPolicy.resolveSide("parties", 200L, 100L)
        )
        // التعادل المحافظ: المحلي يفوز ولا يُكتب فوقه طابع أقدم
        assertEquals(
            SyncConflictPolicy.Side.LOCAL,
            SyncConflictPolicy.resolveSide("parties", 300L, 300L)
        )
        val out = SyncConflictPolicy.merge("parties", mapOf("n" to "ل", "syncUpdatedAt" to 300L),
            mapOf("n" to "ر", "syncUpdatedAt" to 300L), 300L, 300L)
        assertEquals("ل", out["n"])
        assertEquals(300L, out["syncUpdatedAt"])
    }

    @Test
    fun `fuzz 500 seeded scenarios - timestamps monotonic, local-owned survive, merge is idempotent`() {
        val rnd = Random(42)
        val entities = listOf("parties", "products", "currencies", "visits", "expenses")
        var protectedChecks = 0
        repeat(500) {
            val entity = entities[rnd.nextInt(entities.size)]
            val protectedFields = SyncConflictPolicy.localOwnedFields(entity)
            val localTs = rnd.nextLong(0L, 10_000L)
            val remoteTs = rnd.nextLong(0L, 10_000L)
            val local = buildMap<String, Any?> {
                put("f0", "local-f0")
                put("f1", "local-f1")
                put("syncUpdatedAt", localTs)
                protectedFields.forEach { put(it, "local-owned") }
            }
            val incoming = buildMap<String, Any?> {
                put("f0", "remote-f0")
                put("f1", "remote-f1")
                put("syncUpdatedAt", remoteTs)
                protectedFields.forEach { put(it, "remote-owned") }
            }
            val out = SyncConflictPolicy.merge(entity, local, incoming, remoteTs, localTs)

            // ثابت 1: طابع الناتج = توقيت الجهة الفائزة حرفياً (لا زمن مستقبل مختلق)
            val expectedTs = if (SyncConflictPolicy.resolveSide(entity, localTs, remoteTs) ==
                SyncConflictPolicy.Side.REMOTE
            ) remoteTs else localTs
            assertEquals(expectedTs, out["syncUpdatedAt"])

            // ثابت 2: حقول الملك المحلي تنجو دائماً مهما فاز (صفر فقدان مخزون/تفضيل)
            protectedFields.forEach { f ->
                assertEquals("ملكية $entity.$f تنجو", "local-owned", out[f])
                protectedChecks++
            }

            // ثابت 3: التماثل — دمج الناتج مع نفس القادم مرة أخرى لا يغيّره
            val twice = SyncConflictPolicy.merge(entity, out, incoming, remoteTs, out["syncUpdatedAt"] as Long)
            assertEquals(out, twice)

            // ثابت 4: لا مفتاح جديد يبتلع الناتج خارج اتحاد الطرفين
            assertTrue(out.keys.all { it in local || it in incoming })
        }
        assertTrue("الفحص لمس حقول الملك فعلاً", protectedChecks > 0)
    }
}
