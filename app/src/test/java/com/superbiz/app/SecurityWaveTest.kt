package com.superbiz.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.security.LockoutPolicy
import com.superbiz.app.security.LockoutGuard
import com.superbiz.app.security.PinManager
import com.superbiz.app.security.PinVault
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * : اختبارات موجة الأمان الصلبة —
 * سياسة القفل التصاعدي (خالصة)، تشفير الرمز (خالص + Robolectric)،
 * وخزنة Keystore مع مسار التراجع الآمن، وحارس المحاولات على DataStore حقيقي.
 *
 * [P6-M6-14 إصلاح] تحديث: أُلغي التراجع إلى v1 في الكتابة (فشل مغلق)، وأضيفت اختبارات
 * لا تُكتب v1 جديدة + الترقية الشفافة v1→ks بعد أول تحقق ناجح.
*/
class LockoutPolicyTest {

    @Test
    fun `المحاولات الخمس الأولى بلا قفل`() {
        for (n in 0..4) {
            assertEquals(0, LockoutPolicy.lockSecondsFor(n))
        }
    }

    @Test
    fun `القفل يبدأ من الخامسة بثلاثين ثانية ثم يتضاعف`() {
        assertEquals(30, LockoutPolicy.lockSecondsFor(5))
        assertEquals(60, LockoutPolicy.lockSecondsFor(6))
        assertEquals(120, LockoutPolicy.lockSecondsFor(7))
        assertEquals(240, LockoutPolicy.lockSecondsFor(8))
        assertEquals(480, LockoutPolicy.lockSecondsFor(9))
    }

    @Test
    fun `الحد الأقصى خمسة عشر دقيقة لا يتجاوزه أي تضاعف`() {
        assertEquals(900, LockoutPolicy.lockSecondsFor(10))
        assertEquals(900, LockoutPolicy.lockSecondsFor(11))
        assertEquals(900, LockoutPolicy.lockSecondsFor(100))
        assertEquals(900, LockoutPolicy.lockSecondsFor(Int.MAX_VALUE))
    }

    @Test
    fun `البصمة الصحيحة تختلف باختلاف الملوح وتتحقق بأي عدد دورات مخزن`() {
        val salt1 = PinManager.newSalt()
        val salt2 = PinManager.newSalt()
        // ملوحان مختلفان ⇒ بصمتان مختلفتان لنفس الرمز
        assertNotEquals(PinManager.hash("123456", salt1), PinManager.hash("123456", salt2))
        // التحقق ينجح بنفس عدد الدورات المخزنة وي failing برمز خاطئ
        val h600 = PinManager.hash("123456", salt1, PinManager.ITERATIONS)
        val hLegacy = PinManager.hash("123456", salt2, PinManager.LEGACY_ITERATIONS)
        assertTrue(PinManager.verify("123456", salt1, h600, PinManager.ITERATIONS))
        assertTrue(PinManager.verify("123456", salt2, hLegacy, PinManager.LEGACY_ITERATIONS))
        assertFalse(PinManager.verify("654321", salt2, hLegacy, PinManager.LEGACY_ITERATIONS))
    }

    @Test
    fun `المقارنة الزمنية الثابتة تشبه تماماً وتفشل بأي اختلاف`() {
        val a = "a1b2c3d4e5"
        assertTrue(PinManager.secureCompare(a, "a1b2c3d4e5"))
        assertFalse(PinManager.secureCompare(a, "a1b2c3d4e6"))
        assertFalse(PinManager.secureCompare(a, ""))
        assertFalse(PinManager.secureCompare("", "x"))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PinVaultAndLockoutTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    // [P6-M6-14 إصلاح] مفتاح AES حقيقي يُحقن بديلاً عن AndroidKeyStore المفقود في Robolectric —
    // يسمح باختبار مسار ks الكامل (تشفير/فك/ترقية) دون تعديل أي سلوك إنتاجي
    private val testKey = javax.crypto.spec.SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    private fun useTestKey() { PinVault.keyProvider = { testKey } }

    /** إعادة مزوّد المفتاح الإنتاجي (masterKey) بعد كل اختبار حُقن فيه مفتاح */
    private fun restoreProductionKeyProvider() { PinVault.keyProvider = { PinVault.masterKey() } }

    @Test
    fun `خزنة الرمز تدور بشكل آمن وتفشل مغلقة على بيانات تالفة`() {
        useTestKey()
        try {
            val hash = "deadbeef" + "0".repeat(56)
            val blob = PinVault.encrypt(hash)
            // [P6-M6-14 إصلاح] الصيغة ks هي الوحيدة التي يكتبها التشفير — لا v1 جديدة أبداً
            assertTrue(blob.startsWith("ks:"))
            assertFalse(blob.startsWith("v1:"))
            assertEquals(hash, PinVault.decrypt(blob))
            // بيانات تالفة/مزوّرة ⇒ null (يبقى القفل مقفلاً — لا فتح طارئ)
            assertNull(PinVault.decrypt("ks:zz:zz"))
            assertNull(PinVault.decrypt("bogus-format"))
            assertNull(PinVault.decrypt("v1:"))
        } finally {
            restoreProductionKeyProvider()
        }
    }

    @Test
    fun `فشل keystore عند التشفير يفشل مغلقا ولا يكتب v1 جديدة`() {
        // [P6-M6-14 إصلاح] محاكاة غياب Keystore: كان التراجع الصامت يخزن "v1:<hash>" عارياً —
        // الآن فشل مغلق باستثناء واضح ولا مادة جديدة تُكتب
        PinVault.keyProvider = { throw java.security.KeyStoreException("محاكاة غياب Keystore") }
        try {
            try {
                PinVault.encrypt("deadbeef".repeat(8))
                fail("التشفير يجب أن يرمي PinVaultException بدل التراجع الصامت إلى v1")
            } catch (e: PinVault.PinVaultException) {
                assertTrue(e.message!!.contains("Keystore"))
            }
        } finally {
            restoreProductionKeyProvider()
        }
    }

    @Test
    fun `ترقية شفافة v1 إلى ks بعد أول تحقق ناجح مع بقاء قراءة الصيغة القديمة`() {
        useTestKey()
        try {
            val salt = PinManager.newSalt()
            val hash = PinManager.hash("246813", salt, PinManager.ITERATIONS)
            val legacyBlob = "v1:$hash" // مخزون قديم من إصدار سابق
            // توافق الترحيل: قراءة v1 القديمة مدعومة
            assertTrue(PinVault.needsUpgrade(legacyBlob))
            assertEquals(hash, PinVault.decrypt(legacyBlob))
            // «التحقق الناجح» نفسه: فك v1 ثم مقارنة زمنية ثابتة (نفس مسار verifyPin)
            val unwrapped = PinVault.decrypt(legacyBlob)
            assertTrue(unwrapped != null && PinManager.verify("246813", salt, unwrapped!!, PinManager.ITERATIONS))
            // بعد نجاح التحقق: إعادة تغليف البصمة نفسها بصيغة ks الحالية
            val upgraded = PinVault.rewrap(legacyBlob)
            assertNotNull(upgraded)
            assertTrue(upgraded!!.startsWith("ks:"))
            assertFalse(upgraded.startsWith("v1:"))
            assertEquals(hash, PinVault.decrypt(upgraded))
            // الصيغة القديمة لم تُدمّر وتبقى مقروءة (فشل ترقية مستقبلي لا يقفل المستخدم)
            assertEquals(hash, PinVault.decrypt(legacyBlob))
            // مخزون غير قديم/فارغ: الترقية بلا أثر
            assertNull(PinVault.rewrap(upgraded))
            assertNull(PinVault.rewrap(null))
        } finally {
            restoreProductionKeyProvider()
        }
    }

    @Test
    fun `الترقية بلا keystore تفشل بهدوء ويبقى المخزون القديم مقروءا`() {
        PinVault.keyProvider = { throw java.security.KeyStoreException("محاكاة غياب Keystore") }
        try {
            // فشل مغلق هادئ: null والمخزون القديم يبقى كما هو — نتيجة التحقق لا تتأثر
            assertNull(PinVault.rewrap("v1:cafebabe"))
            assertEquals("cafebabe", PinVault.decrypt("v1:cafebabe"))
        } finally {
            restoreProductionKeyProvider()
        }
    }

    @Test
    fun `حارس المحاولات يقفل عند الخامسة ويصفّر عند النجاح`() = runBlocking {
        val guard = LockoutGuard(ctx)
        guard.onSuccess()
        // أربع محاولات: لا قفل
        repeat(4) {
            val st = guard.onFailed()
            assertEquals(0, st.remainingSeconds())
        }
        // الخامسة: قفل فعلي
        val fifth = guard.onFailed()
        assertTrue(fifth.fails >= 5)
        assertTrue(fifth.remainingSeconds() > 0)
        // الحالة المخزنة تصمد (DataStore)
        assertTrue(guard.status().remainingSeconds() > 0)
        // نجاح ⇒ تصفير كامل
        guard.onSuccess()
        val cleared = guard.status()
        assertEquals(0, cleared.fails)
        assertEquals(0, cleared.remainingSeconds())
    }

    @Test
    fun `الإعدادات تخزن الصيغة المؤمّنة الجديدة وتستعيدها`() = runBlocking {
        useTestKey()
        try {
            val repo = com.superbiz.app.data.repo.SettingsRepo(ctx)
            repo.clearPin()
            val salt = PinManager.newSalt()
            val blob = PinVault.encrypt("ab" .repeat(32))
            repo.setPinSecured(salt, blob, PinManager.ITERATIONS, 6)
            val s = repo.snapshot()
            assertEquals(salt, s.pinSalt)
            assertEquals(blob, s.pinBlob)
            assertEquals(PinManager.ITERATIONS, s.pinIters)
            // الصيغة الجديدة تمحو المفقودات القديمة
            assertNull(s.pinHash)
            // [P6-M6-14 إصلاح] مسار الترقية على مستوى الإعدادات: مخزون v1 قديم بعد تحقق ناجح
            // يُعاد تغليفه ويُخزَّن بصيغة ks مع الحفاظ على طول الرمز ومحو المفقودات
            val legacyBlob = "v1:" + "ff".repeat(32)
            val rewrapped = PinVault.rewrap(legacyBlob)
            assertNotNull(rewrapped)
            assertTrue(rewrapped!!.startsWith("ks:"))
            repo.setPinSecured(salt, rewrapped, PinManager.ITERATIONS, 6)
            val s2 = repo.snapshot()
            assertTrue(s2.pinBlob!!.startsWith("ks:"))
            assertEquals(PinManager.ITERATIONS, s2.pinIters)
            assertEquals(6, s2.pinLength)
            assertNull(s2.pinHash)
            // الإخفاء يخزن ويستعاد
            repo.setRedactWidgets(true)
            assertTrue(repo.snapshot().redactWidgets)
            repo.setRedactWidgets(false)
            assertFalse(repo.snapshot().redactWidgets)
            repo.clearPin()
            assertNull(repo.snapshot().pinBlob)
        } finally {
            restoreProductionKeyProvider()
        }
    }
}
