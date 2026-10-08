package com.superbiz.app.domain

/**
 * [Z2-ب V 1.5.0] قائمة انتظار الإبلاغ/التخليص — النقي الكامل فوق [ZatcaGateway]:
 * سياسة الترتيب والنافذة القانونية والتراجع الأسّي، ومعالج يمشي القائمة
 * بلا فقد ولا تكرار — كل شيء JVM مجرد يُختبر بـ Fake gateway (عقد D6 من
 * ADR-001) ولا WorkManager هنا: الغلاف الجهازي رقيق ويستدعي هذا المعالج.
 *
 * ─── عقود موثقة ───
 * - **الترتيب**: القياسية (0100000 — تخليص قبل المشاركة) أولاً بترتيب ICV،
 *   ثم المبسطة (0200000 — إبلاغ خلال النافذة) بترتيب ICV — «أولوية القياسية»
 *   قرار تشغيلي: التخليص بوابة بيع اليوم، والإبلاغ نافذة 24 ساعة.
 * - **النافذة**: المبسطة يجب إبلاغها خلال 24 ساعة من الإصدار؛ ما مضى عليها
 *   النافذة دون إبلاغ يُعلَّم LATE (يُظهره الفاحص في لوحة ZATCA — O1).
 * - **التراجع الأسّي**: الفشل العابر يعيد المحاولة بعد base × 2^محاولة
 *   بسقف محدد — لا إغراق للخدمة، وحين يمنح الخادم retryAfter يُحترم إن
 *   كان أطول من التراجع المحسوب.
 * - **لا فقد ولا تكرار**: كل وثيقة تعالج مرة واحدة لكل دفعة؛ النتيجة
 *   (قبول/رفض/تأجيل) تحدّثها المستدعي داخل معاملته — المعالج لا يكتب شيئاً
 *   بنفسه، ويعيد ملخص الدفعة بدقة (عدد كل نتيجة + أي فشل استثنائي).
 */
object ZatcaQueue {

    /** عنصر قائمة — لقطة من صف الفاتورة + وثيقتها المؤرشفة */
    data class Queued(
        val invoiceId: Long,
        val uuid: String,                 // cbc:UUID للفاتورة — يُمرَّر كما هو للبوابة
        val icv: Long,
        val pih: String,                  // بصمة الفاتورة السابقة المخزنة مع الفاتورة
        val isStandard: Boolean,          // 0100000 تخليص / 0200000 إبلاغ
        val attemptCount: Int,            // محاولات فاشلة سابقة (عابرة)
        val lastAttemptElapsedMs: Long,   // elapsedRealtime آخر محاولة (0 = لم تُحاول)
        val issuedAtMs: Long,             // وقت الإصدار الحائطي — مرجع النافذة
    )

    // ───────── السياسة النقية ─────────

    /** ترتيب المعالجة: القياسية أولاً بترتيب ICV ثم المبسطة بترتيب ICV */
    fun orderForReporting(queue: List<Queued>): List<Queued> =
        queue.sortedWith(
            compareByDescending<Queued> { it.isStandard }.thenBy { it.icv }
        )

    enum class WindowState { ON_TIME, LATE }

    /** نافذة المبسطة القانونية — المضيق عليها يُعلَّم LATE (لا يُلغى: الإبلاغ واجب) */
    fun windowState(q: Queued, nowMs: Long, windowHours: Long = 24): WindowState {
        if (q.isStandard) return WindowState.ON_TIME
        return if (nowMs - q.issuedAtMs > windowHours * 3_600_000L) WindowState.LATE else WindowState.ON_TIME
    }

    /** التراجع الأسّي: base × 2^محاولة بسقف cap — حتمي للاختبار والجدولة */
    fun nextDelayMs(attemptCount: Int, baseMs: Long = 60_000L, capMs: Long = 6L * 3_600_000): Long {
        val exp = attemptCount.coerceIn(0, 62)
        val raw = baseMs * (1L shl exp)
        return if (raw <= 0 || raw > capMs) capMs else raw
    }

    /** هل الأحق مُستحق الآن؟ (مهلة آخر محاولة مرّت أو وثيقة أول مرة) */
    fun isDue(q: Queued, nowElapsedMs: Long): Boolean =
        q.attemptCount == 0 || nowElapsedMs - q.lastAttemptElapsedMs >= nextDelayMs(q.attemptCount)

    /** أحقّ العناصر المستحقة — البوابة بين القائمة والمعالج */
    fun dueQueue(queue: List<Queued>, nowElapsedMs: Long): List<Queued> =
        orderForReporting(queue.filter { isDue(it, nowElapsedMs) })

    // ───────── المعالج ─────────

    /** ملخص دفعة معالجة — تُستهلك في التدقيق والاختبارات والسجل */
    data class ProcessSummary(
        val attempted: Int = 0,
        val accepted: Int = 0,
        val rejected: Int = 0,
        val deferred: Int = 0,   // فشل عابر — يعود بمجدوله
        val windowLate: Int = 0, // مبسطة مضت نافذتها عند معالجتها
    )

    /**
     * معالج قائمة — كل كتابة حالة عبر مستدعيات مررة فبقى نقياً وقابلاً
     * للاختبار بلا قاعدة بيانات ولا أندرويد (عقد D6).
     */
    class Processor(
        private val gateway: ZatcaGateway,
        private val loadDue: suspend () -> List<Queued>,
        private val loadDocument: suspend (invoiceId: Long) -> Pair<String, String>?, // (xml, hash)
        private val onAccepted: suspend (invoiceId: Long, clearedXml: String?, warnings: List<String>) -> Unit,
        private val onRejected: suspend (invoiceId: Long, errors: List<ZatcaGateway.GatewayError>) -> Unit,
        private val onDeferred: suspend (invoiceId: Long, newAttemptCount: Int, nextDueElapsedMs: Long) -> Unit,
        private val elapsedNow: () -> Long = { 0L },
        private val wallNow: () -> Long = { 0L },
    ) {

        /**
         * دفعة واحدة بترتيب الأولوية: كل وثيقة مستحقة تُرسل مرة واحدة،
         * النتيجة تُفعَّل عبر المستدعي، وأي استثناء من النقل يُعامَل فشلاً
         * عابراً (الشبكة لا تُسقط المعالجة أبداً — D3).
         */
        suspend fun processOnce(maxBatch: Int = 50): ProcessSummary {
            val nowElapsed = elapsedNow()
            val nowWall = wallNow()
            val due = dueQueue(loadDue(), nowElapsed).take(maxBatch)
            var sent = 0
            var accepted = 0; var rejected = 0; var deferred = 0; var late = 0
            for (q in due) {
                val doc = loadDocument(q.invoiceId) ?: continue // وثيقة مفقودة: أرشفة أولاً
                sent++
                val result = try {
                    if (q.isStandard) {
                        gateway.clear(
                            ZatcaGateway.ClearanceRequest(
                                invoiceXml = doc.first, uuid = q.uuid,
                                invoiceHash = doc.second, icv = q.icv, pih = q.pih,
                            )
                        )
                    } else {
                        gateway.report(
                            ZatcaGateway.ReportingRequest(
                                invoiceXml = doc.first, uuid = q.uuid,
                                invoiceHash = doc.second, icv = q.icv, pih = q.pih,
                            )
                        )
                    }
                } catch (_: Exception) {
                    ZatcaGateway.GatewayResult.TransientFailure(message = "transport exception")
                }
                when (result) {
                    is ZatcaGateway.GatewayResult.Accepted -> {
                        accepted++
                        onAccepted(q.invoiceId, result.clearedXml, result.warnings)
                    }
                    is ZatcaGateway.GatewayResult.Rejected -> {
                        rejected++
                        onRejected(q.invoiceId, result.errors)
                    }
                    is ZatcaGateway.GatewayResult.TransientFailure -> {
                        deferred++
                        val newAttempts = q.attemptCount + 1
                        var delay = nextDelayMs(newAttempts)
                        result.retryAfterSeconds?.let { hint ->
                            val hintMs = hint * 1000L
                            if (hintMs > delay) delay = hintMs
                        }
                        onDeferred(q.invoiceId, newAttempts, nowElapsed + delay)
                    }
                }
                if (!q.isStandard && windowState(q, nowWall) == WindowState.LATE) late++
            }
            return ProcessSummary(sent, accepted, rejected, deferred, late)
        }
    }
}
