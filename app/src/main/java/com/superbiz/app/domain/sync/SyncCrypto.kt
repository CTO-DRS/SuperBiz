package com.superbiz.app.domain.sync

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * [H4-3][ADR-002 D1/D4] — تعمية كتلة المزامنة: AES-256-GCM بمفتاح KEK مشتق
 * من عبارة المرور (Argon2id 64MiB/3/1 — [Argon2id]).
 *
 * العقد:
 * - Nonce 12 بايت = بادئة عشوائية 4 بايت ثابتة لكل جهاز + الرقم التسلسلي
 *   بترميز big-endian 8 بايت — (المفتاح، nonce) لا يتكرران قط لأن seq أحادي التصاعد.
 * - AAD = deviceId|seq يمنع نقل كتلة بين هويتين.
 * - فشل فك التعمية فشل مغلق (استثناء) — لا كتلة مشوهة تُطبَّق على قاعدة مالية.
 *
 * غلاف Keystore (D1: KEK يُغلَّف بمفتاح جهاز ويبقى في الذاكرة أثناء الجلسة فقط)
 * في [com.superbiz.app.security.SyncKeyVault] — هنا المنطق النقي الجافي القابل للاختبار.
 */
object SyncCrypto {

    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val NONCE_LEN = 12

    data class EncryptedBlock(
        val deviceId: String,
        val seq: Long,
        val noncePrefix: ByteArray,   // 4 بايت — ثابتة لكل جهاز
        val ciphertext: ByteArray
    ) {
        override fun equals(other: Any?): Boolean = other is EncryptedBlock &&
            other.deviceId == deviceId && other.seq == seq &&
            other.noncePrefix.contentEquals(noncePrefix) && other.ciphertext.contentEquals(ciphertext)
        override fun hashCode(): Int = 31 * (deviceId.hashCode() + seq.hashCode()) +
            31 * noncePrefix.contentHashCode() + ciphertext.contentHashCode()
    }

    /** بادئة nonce عشوائية تُولَّد مرة عند تفعيل المزامنة وتُخزَّن مع البوابة. */
    fun newNoncePrefix(): ByteArray = ByteArray(4).also { SecureRandom().nextBytes(it) }

    private fun nonce(prefix: ByteArray, seq: Long): ByteArray {
        require(prefix.size == 4) { "nonce prefix 4B" }
        val n = ByteArray(NONCE_LEN)
        System.arraycopy(prefix, 0, n, 0, 4)
        for (i in 0 until 8) n[4 + i] = (seq ushr (8 * (7 - i))).toByte()
        return n
    }

    private fun aad(deviceId: String, seq: Long): ByteArray = "$deviceId|$seq".toByteArray(Charsets.UTF_8)

    fun encrypt(kek: ByteArray, block: EncryptedBlock, plaintext: ByteArray): EncryptedBlock {
        require(kek.size == KEY_BITS / 8) { "KEK 32B" }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(TAG_BITS, nonce(block.noncePrefix, block.seq)))
        cipher.updateAAD(aad(block.deviceId, block.seq))
        return block.copy(ciphertext = cipher.doFinal(plaintext))
    }

    /** فك التعمية — أي عبث (tag/AAD/nonce) يرمي استثناء مغلقاً. */
    fun decrypt(kek: ByteArray, block: EncryptedBlock): ByteArray {
        require(kek.size == KEY_BITS / 8) { "KEK 32B" }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(TAG_BITS, nonce(block.noncePrefix, block.seq)))
        cipher.updateAAD(aad(block.deviceId, block.seq))
        return cipher.doFinal(block.ciphertext)
    }

    /** Base64 نقي للأجرئة (minSdk 24 — لا java.util.Base64 — نمط ZatcaQr حرفياً). */
    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun toBase64(data: ByteArray): String {
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else 0
            val triple = (b0 shl 16) or (b1 shl 8) or b2
            sb.append(B64[(triple shr 18) and 63]).append(B64[(triple shr 12) and 63])
            sb.append(if (i + 1 < data.size) B64[(triple shr 6) and 63] else '=')
            sb.append(if (i + 2 < data.size) B64[triple and 63] else '=')
            i += 3
        }
        return sb.toString()
    }

    fun fromBase64(text: String): ByteArray {
        val clean = text.trim()
        require(clean.length % 4 == 0) { "base64 length" }
        val out = ArrayList<Byte>(clean.length * 3 / 4)
        var buf = 0
        var bits = 0
        for (ch in clean) {
            if (ch == '=') break
            val idx = B64.indexOf(ch)
            require(idx >= 0) { "base64 char" }
            buf = (buf shl 6) or idx
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((buf shr bits) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }
}
