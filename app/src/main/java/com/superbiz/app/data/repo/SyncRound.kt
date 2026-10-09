package com.superbiz.app.data.repo

import com.superbiz.app.domain.sync.SyncCrypto
import com.superbiz.app.domain.sync.SyncMerge
import com.superbiz.app.domain.sync.SyncTransport

/**
 * [H4-3][ADR-002 D2] — دورة مزامنة كاملة: دفع الدفتر المشفر + سحب ونظراء + تطبيق.
 * بلا أي شبكة هنا — النقل حقن نقي ([SyncTransport]) فيُختبر التقارب بالكامل دون اتصال
 * (بوابة اختبار ADR-002: «جهازان يحرران نفس الصنف دون اتصال ثم يتزامنان»).
 *
 * صمت كامل خارج التفعيل (D2): البوابة تُفحص قبل أي خطوة، وغياب KEK (عبارة لم تُدخل
 * بعد إعادة القفل) = دورة ساكنة مغلقة لا فشل معروض.
 */
class SyncRound(
    private val engine: SyncEngine,
    private val transport: SyncTransport,
    private val clock: SyncClock,
    private val kekProvider: suspend () -> ByteArray?
) {

    sealed class RoundResult {
        object Disabled : RoundResult()
        object Locked : RoundResult()
        data class Done(val pushedBlocks: Int, val pulledBlocks: Int, val stats: SyncEngine.ApplyStats?) : RoundResult()
        data class Failed(val reason: String) : RoundResult()
    }

    suspend fun run(): RoundResult {
        if (!clock.enabled()) return RoundResult.Disabled
        val kek = kekProvider() ?: return RoundResult.Locked
        val my = clock.deviceId()
        val prefix = SyncCrypto.fromBase64(clock.noncePrefixB64())

        // 1) الدفع — دفتر التغييرات مشفراً
        val blocks = engine.buildPushBlocks()
        var pushed = 0
        for ((seq, payload) in blocks) {
            val block = SyncCrypto.encrypt(
                kek,
                SyncCrypto.EncryptedBlock(my, seq, prefix, ByteArray(0)),
                payload.toByteArray(Charsets.UTF_8)
            )
            when (val r = transport.push(block)) {
                is SyncTransport.Result.Ok -> pushed++
                is SyncTransport.Result.Transient -> return RoundResult.Failed("push transient")
                is SyncTransport.Result.Rejected -> return RoundResult.Failed("push rejected: ${r.reason}")
            }
        }
        if (blocks.isNotEmpty()) engine.markPushed()

        // 2) السحب — كتل الأجهزة الأخرى منذ آخر مؤشرات
        val cursors = clock.pullCursors().toMutableMap()
        var pulled = 0
        var stats: SyncEngine.ApplyStats? = null
        when (val r = transport.pull(cursors)) {
            is SyncTransport.Result.Ok -> {
                for (block in r.value) {
                    if (block.deviceId == my) continue // صدا — اللوكال حكمه ساعته
                    val plain = runCatching { SyncCrypto.decrypt(kek, block) }.getOrNull()
                        ?: return RoundResult.Failed("decrypt failed (عبارة مختلفة؟)")
                    stats = engine.applyPayload(String(plain, Charsets.UTF_8), block.deviceId)
                    val prev = cursors[block.deviceId] ?: 0L
                    if (block.seq > prev) cursors[block.deviceId] = block.seq
                    pulled++
                }
                clock.setPullCursors(cursors)
            }
            is SyncTransport.Result.Transient -> return RoundResult.Failed("pull transient")
            is SyncTransport.Result.Rejected -> return RoundResult.Failed("pull rejected: ${r.reason}")
        }
        return RoundResult.Done(pushed, pulled, stats)
    }
}
