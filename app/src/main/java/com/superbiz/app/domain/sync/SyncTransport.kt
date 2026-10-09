package com.superbiz.app.domain.sync

/**
 * [H4-3][ADR-002 D2/D5] — عقد النقل للكتل المشفرة (نقي بلا أنواع شبكية —
 * الحائط في network/ هو من يفتح sockets وفق ADR-001).
 *
 * الخادم ترحيل عمياء يملكه المالك (D5): يخزن كتلاً مشفرة ويعيدها — لا يفك anything.
 * العقد: POST {base}/push بجسد JSON للكتلة → 200 أي جسد؛
 * GET {base}/pull?after=<json cursors> → {"blocks":[{deviceId,seq,noncePrefix,ciphertext}...]}
 * cursors: خريطة deviceId → آخر seq سُحب منه (مناعة انحراف الساعات).
 */
interface SyncTransport {

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Transient(val retryAfterMs: Long?) : Result<Nothing>()
        data class Rejected(val reason: String) : Result<Nothing>()
    }

    suspend fun push(block: SyncCrypto.EncryptedBlock): Result<Unit>
    suspend fun pull(cursors: Map<String, Long>): Result<List<SyncCrypto.EncryptedBlock>>
}
