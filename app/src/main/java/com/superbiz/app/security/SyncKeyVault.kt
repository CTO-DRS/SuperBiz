package com.superbiz.app.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.superbiz.app.data.repo.SyncEnableStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [H4-3][ADR-002 D1] — غلاف KEK بمفتاح جهاز Android Keystore.
 *
 * العقد المعلن في شاشة التفعيل نصاً لا تحذيراً صغيراً: **عبارة المرور لا تُخزَّن
 * إطلاقاً** — تُشتق KEK بـArgon2id وتُغلَّف بمفتاح Keystore ويبقى الغلاف وحده؛
 * نسيان العبارة = فقدان وصول المزامنة نهائياً (البيانات المحلية سليمة).
 * الجلسة تحتفظ بـKEK في الذاكرة فقط ([cached] يُمحى عند [lock]).
 */
object SyncKeyVault {

    private const val ALIAS = "superbiz_sync_kek"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    @Volatile
    private var cached: ByteArray? = null

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val builder = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setUnlockedDeviceRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setInvalidatedByBiometricEnrollment(false)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(builder.build())
        return generator.generateKey()
    }

    /** يغلف KEK بمفتاح Keystore ويخزن الغلاف — يُستدعى مرة عند تفعيل المزامنة. */
    fun wrapAndStore(kek: ByteArray, store: SyncEnableStore) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val wrapped = cipher.doFinal(kek)
        val blob = ByteArray(12 + wrapped.size)
        System.arraycopy(cipher.iv, 0, blob, 0, 12)
        System.arraycopy(wrapped, 0, blob, 12, wrapped.size)
        kotlinx.coroutines.runBlocking { store.setWrappedKek(com.superbiz.app.domain.sync.SyncCrypto.toBase64(blob)) }
        cached = kek.copyOf()
    }

    /** يستعيد KEK من الغلاف — يتطلب عبارة المرور إن لم يوجد غلاف. */
    fun unwrap(store: SyncEnableStore): ByteArray? {
        cached?.let { return it }
        val b64 = kotlinx.coroutines.runBlocking { store.wrappedKekOnce() }
        if (b64.isEmpty()) return null
        return runCatching {
            val blob = com.superbiz.app.domain.sync.SyncCrypto.fromBase64(b64)
            val iv = blob.copyOfRange(0, 12)
            val wrapped = blob.copyOfRange(12, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, iv))
            val kek = cipher.doFinal(wrapped)
            cached = kek
            kek
        }.getOrNull()
    }

    /** إبقاء KEK في الذاكرة أثناء الجلسة فقط — يُستدعى عند إعادة القفل. */
    fun lock() {
        cached?.fill(0)
        cached = null
    }
}
