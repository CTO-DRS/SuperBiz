package com.superbiz.app.rbac

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.db.UserSecretEntity
import com.superbiz.app.security.LockoutPolicy
import com.superbiz.app.security.LockoutGuard
import com.superbiz.app.security.PinManager
import com.superbiz.app.security.PinVault
import com.superbiz.app.security.UserAuth
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [H1-4][H1-5][V 1.2.0] مصادقة المستخدم + القفل التصاعدي لكل مستخدم
 * ═══════════════════════════════════════════════════════════════════════════
 * عقد القبول (RBAC_V13_DESIGN §6-4/§6-6):
 *  • إنشاء سر مستخدم والتحقق به يعملان بنفس عقد PinManager/PinVault حرفياً
 *    (600k PBKDF2 + مغلف ks بمفتاح alias لكل مستخدم).
 *  • القفل التصاعدي يعمل لكل مستخدم على حدة — فشل مستخدم لا يقفل غيره.
 *  • فشل فك المغلف = رفض مغلق لا فتح طارئ.
 * مفتاح AES يُحقن بديل AndroidKeyStore المفقود في Robolectric (نمط
 * SecurityWaveTest نفسه — بلا تعديل أي سلوك إنتاجي).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UserAuthLockoutTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private val testKey = javax.crypto.spec.SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    private fun useTestKeys() {
        PinVault.keyProvider = { testKey }
        PinVault.userKeyProvider = { testKey }
    }

    @After
    fun tearDown() {
        PinVault.keyProvider = { PinVault.masterKey() }
        PinVault.userKeyProvider = { PinVault.keyForAlias(PinVault.userAlias(it)) }
    }

    // ═══ UserAuth — إنشاء السر والتحقق به ═══

    @Test
    fun `newSecret wraps with ks envelope and verifies the right pin only`() {
        useTestKeys()
        val secret = UserAuth.newSecret(7L, "123456", biometricAllowed = false)
        assertTrue("envelope contract: ks:<iv>:<ct>", secret.pinWrapped.startsWith("ks:"))
        assertEquals(7L, secret.userId)
        assertEquals(0, secret.biometricAllowed)
        assertEquals(PinManager.ITERATIONS, secret.pinIters)
        assertTrue(UserAuth.verify(secret, "123456"))
        assertFalse(UserAuth.verify(secret, "123457"))
        assertFalse(UserAuth.verify(secret, ""))
        assertFalse(UserAuth.verify(secret, "12345678901234"))
    }

    @Test
    fun `two users with same pin get different salts and wrapped blobs`() {
        useTestKeys()
        val a = UserAuth.newSecret(1L, "987654", false)
        val b = UserAuth.newSecret(2L, "987654", false)
        assertNotEquals(a.pinSalt, b.pinSalt)
        assertNotEquals(a.pinWrapped, b.pinWrapped)
        assertTrue(UserAuth.verify(a, "987654"))
        assertTrue(UserAuth.verify(b, "987654"))
    }

    @Test
    fun `verify fails closed on tampered envelope — no silent unlock`() {
        useTestKeys()
        val secret = UserAuth.newSecret(3L, "654321", false)
        val tampered = UserSecretEntity(
            userId = 3L,
            pinWrapped = "ks:0000:1111",   // مغلّف مزوّر
            pinSalt = secret.pinSalt,
            pinIters = secret.pinIters,
            biometricAllowed = 0
        )
        assertFalse(UserAuth.verify(tampered, "654321"))
        assertFalse(UserAuth.verify(tampered.copy(pinWrapped = "bogus"), "654321"))
        assertFalse(UserAuth.verify(tampered.copy(pinWrapped = ""), "654321"))
    }

    @Test
    fun `biometric flag contract — allowed only when stored as 1`() {
        useTestKeys()
        assertTrue(UserAuth.biometricAllowed(UserAuth.newSecret(1L, "111111", true)))
        assertFalse(UserAuth.biometricAllowed(UserAuth.newSecret(1L, "111111", false)))
        assertFalse(UserAuth.biometricAllowed(null))
    }

    // ═══ القفل التصاعدي لكل مستخدم على حدة (عقد §6-4) ═══

    @Test
    fun `per-user lockout — failing user1 does not lock user2`() = runBlocking {
        val g1 = LockoutGuard(ctx, "u1")
        val g2 = LockoutGuard(ctx, "u2")

        // المستخدم 1 يستنفد الحرية ويدخل منطقة القفل
        repeat(LockoutPolicy.FREE_ATTEMPTS) { g1.onFailed() }
        val st1 = g1.onFailed()
        assertTrue("user1 must be locked after threshold", st1.isLocked(0L))
        assertEquals(
            LockoutPolicy.BASE_LOCK_SECONDS,
            st1.remainingElapsedSeconds(st1.lockUntilElapsed - LockoutPolicy.BASE_LOCK_SECONDS * 1000L)
        )

        // المستخدم 2 حر تماماً — لا تسريب للقفل بين المستخدمين
        val st2 = g2.status()
        assertEquals(0, st2.fails)
        assertFalse(st2.isLocked(0L))
    }

    @Test
    fun `per-user lockout — success resets only that user's counter`() = runBlocking {
        val g1 = LockoutGuard(ctx, "u1")
        val g2 = LockoutGuard(ctx, "u2")
        repeat(LockoutPolicy.FREE_ATTEMPTS - 1) { g1.onFailed() }
        g2.onFailed()
        g1.onSuccess()
        assertEquals(0, g1.status().fails)
        assertEquals(1, g2.status().fails)
    }

    @Test
    fun `per-user lockout — escalation matches the shared policy`() = runBlocking {
        val g = LockoutGuard(ctx, "u9")
        repeat(LockoutPolicy.FREE_ATTEMPTS) { g.onFailed() }
        val st5 = g.onFailed()   // الخامسة: 30ث
        assertEquals(LockoutPolicy.BASE_LOCK_SECONDS, LockoutPolicy.lockSecondsFor(st5.fails))
        val st6 = g.onFailed()   // السادسة: 60ث
        assertEquals(LockoutPolicy.BASE_LOCK_SECONDS * 2, LockoutPolicy.lockSecondsFor(st6.fails))
        // السقف الأعلى 900ث
        assertEquals(LockoutPolicy.MAX_LOCK_SECONDS, LockoutPolicy.lockSecondsFor(st6.fails + 20))
    }

    @Test
    fun `legacy scope keeps historical datastore keys — upgrade does not reset counters`() = runBlocking {
        // النطاق الافتراضي "" يكتب المفاتيح التاريخية حرفياً (توافق بايتي مع ما قبل v13) —
        // نفس العدّاد يُقرأ ويُصفّر عبر نفس الحارس بلا أي لاحقة
        val legacy = LockoutGuard(ctx)
        legacy.onFailed()
        assertEquals(1, legacy.status().fails)
        legacy.onSuccess()
        assertEquals(0, legacy.status().fails)
    }

    @Test
    fun `PinVault user alias naming follows the v13 contract`() {
        assertEquals("superbiz_pin_u1", PinVault.userAlias(1L))
        assertEquals("superbiz_pin_u42", PinVault.userAlias(42L))
    }

    @Test
    fun `decryptFor falls back to master-wrapped migrated owner seed`() {
        useTestKeys()
        // بذرة المالك المُرحّلة تُغلّف بالمفتاح "الرئيسي" (keyProvider المحقون هنا) —
        // decryptFor يجب أن يقرأها حتى لو سُئلت بمعرف المستخدم
        val hash = "ab".repeat(32)
        val migratedBlob = PinVault.encrypt(hash)   // المسار الرئيسي — كما وصلت البذرة في الترحيل
        assertEquals(hash, PinVault.decryptFor(1L, migratedBlob))
        // وسر المستخدم الجديد بمفتاحه الخاص يُقرأ كذلك (userKeyProvider محقون بنفس المفتاح)
        val userBlob = PinVault.encryptFor(2L, hash)
        assertEquals(hash, PinVault.decryptFor(2L, userBlob))
        // مزوّر = null دائماً
        assertNull(PinVault.decryptFor(2L, "ks:ff:ee"))
    }
}
