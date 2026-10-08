package com.superbiz.app

import com.superbiz.app.domain.ZatcaGateway
import com.superbiz.app.domain.ZatcaQueue
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات [Z2-ب V 1.5.0] — قائمة انتظار الإبلاغ/التخليص (عقد D6: JVM مجرد
 * بـ Fake gateway، ≥ 20 اختباراً — بوابة قبول ADR-001):
 * الترتيب، النافذة، التراجع الأسّي، الاستحقاق، الدفعات، انقطاع 10 ساعات ثم
 * عودة بلا فقد ولا تكرار وبالترتيب، الرفض المعياري، حاجز الدفعة، واختيار
 * الواجهة حسب النوع.
 */
class ZatcaQueueTest {

    // ─── أدوات بناء القوائم ───

    private fun q(
        id: Long, icv: Long, standard: Boolean = false,
        attempts: Int = 0, lastAttempt: Long = 0L, issuedAt: Long = 0L,
    ) = ZatcaQueue.Queued(
        invoiceId = id, uuid = "uuid-$id", icv = icv, pih = "pih-$id",
        isStandard = standard, attemptCount = attempts,
        lastAttemptElapsedMs = lastAttempt, issuedAtMs = issuedAt,
    )

    /** Fake gateway بسيط — يسجل الطلبات ويعيد ما يُبرمج به */
    private class FakeGateway(
        var behaviour: (requestKind: String, invoiceId: String) -> ZatcaGateway.GatewayResult =
            { _, _ -> ZatcaGateway.GatewayResult.Accepted() },
    ) : ZatcaGateway {
        val cleared = ArrayList<ZatcaGateway.ClearanceRequest>()
        val reported = ArrayList<ZatcaGateway.ReportingRequest>()

        override suspend fun clear(request: ZatcaGateway.ClearanceRequest): ZatcaGateway.GatewayResult {
            cleared.add(request)
            return behaviour("clear", request.invoiceXml)
        }

        override suspend fun report(request: ZatcaGateway.ReportingRequest): ZatcaGateway.GatewayResult {
            reported.add(request)
            return behaviour("report", request.invoiceXml)
        }
    }

    private class Harness(val gateway: FakeGateway = FakeGateway()) {
        var elapsed = 0L
        var wall = 0L
        val queue = ArrayList<ZatcaQueue.Queued>()
        val docs = HashMap<Long, Pair<String, String>>() // id → (xml, hash)
        val acceptedCalls = ArrayList<Pair<Long, String?>>()
        val rejectedCalls = ArrayList<Pair<Long, List<ZatcaGateway.GatewayError>>>()
        val deferredCalls = ArrayList<Triple<Long, Int, Long>>()

        val processor = ZatcaQueue.Processor(
            gateway = gateway,
            loadDue = { queue.toList() },
            loadDocument = { id -> docs[id] },
            onAccepted = { id, xml, _ ->
                acceptedCalls.add(id to xml)
                queue.removeAll { it.invoiceId == id } // الأرشفة تُخرج من القائمة (نمط الحقيقة)
            },
            onRejected = { id, errs ->
                rejectedCalls.add(id to errs)
                queue.removeAll { it.invoiceId == id } // الرفض يغلق الوثيقة أيضاً
            },
            onDeferred = { id, attempts, next -> deferredCalls.add(Triple(id, attempts, next)) },
            elapsedNow = { elapsed },
            wallNow = { wall },
        )
    }

    // ───────── 1) سياسة الترتيب ─────────

    @Test
    fun order_standardFirst_byIcv_thenSimplifiedByIcv() {
        val list = listOf(
            q(3, 3, standard = false), q(1, 1, standard = true),
            q(4, 4, standard = true), q(2, 2, standard = false),
        )
        val ordered = ZatcaQueue.orderForReporting(list)
        assertEquals(listOf(1L, 4L, 2L, 3L), ordered.map { it.invoiceId })
    }

    @Test
    fun order_emptyStaysEmpty() {
        assertTrue(ZatcaQueue.orderForReporting(emptyList()).isEmpty())
    }

    @Test
    fun order_stableForEqualIcv() {
        val a = q(10, 1, standard = true)
        val b = q(11, 1, standard = true)
        val ordered = ZatcaQueue.orderForReporting(listOf(a, b))
        assertEquals(listOf(10L, 11L), ordered.map { it.invoiceId }) // الاستقرار: ترتيب الإدخال محفوظ
    }

    // ───────── 2) النافذة القانونية ─────────

    @Test
    fun window_onTime_within24h() {
        val item = q(1, 1, issuedAt = 1_000_000L)
        assertEquals(ZatcaQueue.WindowState.ON_TIME, ZatcaQueue.windowState(item, 1_000_000L + 23 * 3_600_000L))
    }

    @Test
    fun window_late_after24h() {
        val item = q(1, 1, issuedAt = 0L)
        assertEquals(ZatcaQueue.WindowState.LATE, ZatcaQueue.windowState(item, 24 * 3_600_000L + 1))
    }

    @Test
    fun window_boundary_exact24h_isOnTime() {
        val item = q(1, 1, issuedAt = 0L)
        assertEquals(ZatcaQueue.WindowState.ON_TIME, ZatcaQueue.windowState(item, 24 * 3_600_000L))
    }

    @Test
    fun window_doesNotApplyToStandard() {
        // القياسية بلا نافذة 24 ساعة — التخلّص قبل المشاركة عقدها مختلف
        val item = q(1, 1, standard = true, issuedAt = 0L)
        assertEquals(ZatcaQueue.WindowState.ON_TIME, ZatcaQueue.windowState(item, 100 * 3_600_000L))
    }

    // ───────── 3) التراجع الأسّي ─────────

    @Test
    fun backoff_exponentialSequence() {
        assertEquals(60_000L, ZatcaQueue.nextDelayMs(0))
        assertEquals(120_000L, ZatcaQueue.nextDelayMs(1))
        assertEquals(240_000L, ZatcaQueue.nextDelayMs(2))
        assertEquals(480_000L, ZatcaQueue.nextDelayMs(3))
    }

    @Test
    fun backoff_cappedAtSixHours() {
        assertEquals(6L * 3_600_000, ZatcaQueue.nextDelayMs(10))
        assertEquals(6L * 3_600_000, ZatcaQueue.nextDelayMs(100))
    }

    @Test
    fun backoff_zeroAttempts_isBase() {
        assertEquals(60_000L, ZatcaQueue.nextDelayMs(0))
    }

    // ───────── 4) الاستحقاق ─────────

    @Test
    fun isDue_firstItem_immediatelyDue() {
        assertTrue(ZatcaQueue.isDue(q(1, 1), nowElapsedMs = 0L))
    }

    @Test
    fun isDue_afterBackoffElapsed_true_before_false() {
        val item = q(1, 1, attempts = 2, lastAttempt = 1_000_000L) // مهلة 240 ثانية
        assertFalse(ZatcaQueue.isDue(item, 1_000_000L + 239_999L))
        assertTrue(ZatcaQueue.isDue(item, 1_000_000L + 240_000L))
    }

    @Test
    fun dueQueue_filtersAndOrders() {
        val h = Harness()
        h.queue.addAll(
            listOf(
                q(1, 1, standard = true, attempts = 10, lastAttempt = 0L), // سقف 6س — لم تنته
                q(2, 2, standard = false),                                 // مستحقة
                q(3, 3, standard = true),                                  // مستحقة
            )
        )
        h.elapsed = 3_600_000L // ساعة — الأولى (سقف 6س) ليست مستحقة
        val due = ZatcaQueue.dueQueue(h.queue, h.elapsed)
        assertEquals(listOf(3L, 2L), due.map { it.invoiceId }) // قياسية أولاً
    }

    // ───────── 5) المعالج — الدفعات والنتائج ─────────

    @Test
    fun processor_acceptsAndCallsOnAccepted_withClearedXml() = runTest {
        val h = Harness(FakeGateway { _, _ -> ZatcaGateway.GatewayResult.Accepted(clearedXml = "CLEARED-XML") })
        h.docs[1] = "<Invoice/>" to "HASH1"
        h.queue.add(q(1, 1, standard = true))
        val s = h.processor.processOnce()
        assertEquals(1, s.accepted)
        assertEquals("CLEARED-XML", h.acceptedCalls.single().second)
    }

    @Test
    fun processor_routesStandardToClear_simplifiedToReport() = runTest {
        val h = Harness()
        h.docs[1] = "X" to "H1"
        h.docs[2] = "Y" to "H2"
        h.queue.add(q(1, 1, standard = true))
        h.queue.add(q(2, 2, standard = false))
        h.processor.processOnce()
        assertEquals(1, h.gateway.cleared.size)
        assertEquals(1, h.gateway.reported.size)
    }

    @Test
    fun processor_passesUuidIcvPih_hashFromArchive() = runTest {
        val h = Harness()
        h.docs[7] = "<xml-7/>" to "HASH-7"
        h.queue.add(q(7, 41, standard = false))
        h.processor.processOnce()
        val req = h.gateway.reported.single()
        assertEquals("uuid-7", req.uuid)
        assertEquals("HASH-7", req.invoiceHash)
        assertEquals(41L, req.icv)
        assertEquals("pih-7", req.pih)
        assertEquals("<xml-7/>", req.invoiceXml)
    }

    @Test
    fun processor_rejected_callsOnRejectedWithErrors() = runTest {
        val errs = listOf(ZatcaGateway.GatewayError("2015", "VALIDATION", "hash mismatch", 400))
        val h = Harness(FakeGateway { _, _ -> ZatcaGateway.GatewayResult.Rejected(errs) })
        h.docs[1] = "X" to "H"
        h.queue.add(q(1, 1))
        val s = h.processor.processOnce()
        assertEquals(1, s.rejected)
        assertEquals(errs, h.rejectedCalls.single().second)
    }

    @Test
    fun processor_transient_defersWithExponentialAttempts() = runTest {
        val h = Harness(FakeGateway { _, _ -> ZatcaGateway.GatewayResult.TransientFailure() })
        h.docs[1] = "X" to "H"
        h.queue.add(q(1, 1, attempts = 0))
        h.elapsed = 500L
        val s = h.processor.processOnce()
        assertEquals(1, s.deferred)
        val (id, attempts, next) = h.deferredCalls.single()
        assertEquals(1L, id)
        assertEquals(1, attempts)
        assertEquals(500L + 120_000L, next) // base × 2^1
    }

    @Test
    fun processor_retryAfterHint_winsWhenLonger() = runTest {
        val h = Harness(FakeGateway { _, _ ->
            ZatcaGateway.GatewayResult.TransientFailure(retryAfterSeconds = 3600)
        })
        h.docs[1] = "X" to "H"
        h.queue.add(q(1, 1))
        h.processor.processOnce()
        val (_, _, next) = h.deferredCalls.single()
        assertEquals(3_600_000L, next) // elapsed=0 + تلميح الخادم (أطول من التراجع 120ث)
    }

    @Test
    fun processor_transportException_treatedAsTransient() = runTest {
        val h = Harness(FakeGateway { _, _ -> throw java.io.IOException("offline") })
        h.docs[1] = "X" to "H"
        h.queue.add(q(1, 1))
        val s = h.processor.processOnce()
        assertEquals(1, s.deferred)
        assertEquals(0, s.accepted)
    }

    @Test
    fun processor_missingDocument_skippedWithoutCrash() = runTest {
        val h = Harness()
        h.queue.add(q(1, 1)) // لا وثيقة مؤرشفة
        val s = h.processor.processOnce()
        assertEquals(0, s.attempted) // لا تُعدّ محاولة بلا وثيقة
        assertTrue(h.gateway.reported.isEmpty())
    }

    @Test
    fun processor_batchCap_respected() = runTest {
        val h = Harness()
        repeat(30) { i ->
            h.docs[(i + 1).toLong()] = "X" to "H"
            h.queue.add(q((i + 1).toLong(), (i + 1).toLong()))
        }
        val s = h.processor.processOnce(maxBatch = 10)
        assertEquals(10, s.attempted)
    }

    @Test
    fun processor_emptyQueue_noop() = runTest {
        val h = Harness()
        val s = h.processor.processOnce()
        assertEquals(0, s.attempted)
    }

    @Test
    fun processor_notDue_skippedInSameBatch() = runTest {
        val h = Harness()
        h.docs[1] = "X" to "H"
        h.queue.add(q(1, 1, attempts = 5, lastAttempt = 0L)) // سقف 6س لم ينته
        h.elapsed = 1_000L
        val s = h.processor.processOnce()
        assertEquals(0, s.attempted)
    }

    @Test
    fun processor_lateWindow_countedInSummary() = runTest {
        val h = Harness()
        h.docs[1] = "X" to "H"
        h.queue.add(q(1, 1, standard = false, issuedAt = 0L))
        h.wall = 25 * 3_600_000L // مضت النافذة
        val s = h.processor.processOnce()
        assertEquals(1, s.windowLate)
    }

    // ───────── 6) معيار القبول: 100 وثيقة، انقطاع 10 ساعات، عودة بلا فقد ولا تكرار ─────────

    @Test
    fun processor_hundredInvoices_tenHourOutage_noLossNoDuplicates_inOrder() = runTest {
        val h = Harness()
        // 60 قياسية + 40 مبسطة بترتيب ICV مختلط في القائمة
        val all = (1..100).map {
            q(it.toLong(), it.toLong(), standard = it <= 60)
        }.shuffled(java.util.Random(42))
        h.queue.addAll(all)
        repeat(100) { h.docs[it.toLong() + 1] = "<x/>" to "H${it + 1}" }

        // طور الانقطاع: كل دفعة تفشل عابراً — 10 ساعات بأكملها
        val offline = Harness(h.gateway).also { it.copyStateFrom(h) }
        var elapsed = 0L
        var totalAttempts = 0
        offline.gateway.behaviour = { _, _ -> ZatcaGateway.GatewayResult.TransientFailure() }
        while (elapsed < 10L * 3_600_000) {
            val s = offline.processor.processOnce(maxBatch = 20)
            totalAttempts += s.attempted
            elapsed = offline.elapsed
            offline.elapsed += 30 * 60_000L // كل دفعة بعد 30 دقيقة
        }
        // لا شيء قُبل أثناء الانقطاع
        assertTrue(offline.acceptedCalls.isEmpty())
        // الأهم: لا استدعاء ناجح ولا فوف — الإبلاغات عابرة حصراً
        assertEquals(totalAttempts, offline.deferredCalls.size)

        // طور العودة: كل المحاولات تجمع — القائمة كما هي، الستب يكفي
        offline.gateway.behaviour = { _, _ -> ZatcaGateway.GatewayResult.Accepted() }
        var grand = 0
        while (offline.acceptedCalls.size < 100) {
            val s = offline.processor.processOnce(maxBatch = 50)
            grand += s.attempted
            // حماية انسداد: أفق زمني سخي يضمن استحقاق الجميع
            offline.elapsed += 6L * 3_600_000 + 1
            if (grand > 100_000) throw AssertionError("queue not draining — loss suspected")
        }
        // لا فقد ولا تكرار: 100 وثيقة قُبلت كلها مرة واحدة
        val ids = offline.acceptedCalls.map { it.first }.sorted()
        assertEquals((1L..100L).toList(), ids)
        // والترتيب عند العودة: القياسية أولاً بترتيب ICV ثم المبسطة
        val firstBatchOrder = offline.acceptedCalls.take(60).map { it.first }
        assertEquals((1L..60L).toList(), firstBatchOrder)
        val secondBatchOrder = offline.acceptedCalls.drop(60).map { it.first }
        assertEquals((61L..100L).toList(), secondBatchOrder)
    }

    private fun Harness.copyStateFrom(other: Harness) {
        elapsed = other.elapsed
        wall = other.wall
        queue.addAll(other.queue)
        docs.putAll(other.docs)
    }
}
