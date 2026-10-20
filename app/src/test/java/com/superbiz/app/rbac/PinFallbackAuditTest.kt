package com.superbiz.app.rbac

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.AppGraph
import com.superbiz.app.vm.SettingsVM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [E0.4 V 3.3.0] مسار ارتداد الرمز — حدّ المحاولات موثق في دفتر التدقيق
 * ═══════════════════════════════════════════════════════════════════════════
 * الخلفية (خارطة الطريق E0.4): «سياسة ارتداد الرمز (عند غياب البصمة) منفذة
 * وفق القرار الموثق، مع اختبارات حدّ المحاولات ودفتر التدقيق».
 *
 * القائم قبل هذه الموجة: حدّ المحاولات التصاعدي (LockoutGuard) محكوم
 * اختبارياً (LockoutElapsedM9Test/UserAuthLockoutTest) — لكن مسار رمز
 * الجهاز (SettingsVM.verifyPin — وضع المالك الأحادي حيث البصمة غير متاحة
 * أو غير مفعّلة) كان يفشل **بصمت**: لا صف تدقيق للفشل، بعكس مسار التعدد
 * (UsersVM يكتب USER_LOGIN_FAIL منسوباً للاسم — عقد التصميم §6-4).
 *
 * العقد المقفول هنا (Robolectric + قاعدة حقيقية + مفاتيح محقونة — نمط
 * UsersVMGateTest حرفياً):
 *  1) فشل رمز الجهاز يكتب صف DEVICE_PIN_FAIL بإسناد ما قبل المصادقة الصادق
 *     (actor="device"، actorId/actorRole فارغان — الجلسة لم تولد بعد).
 *  2) النجاح لا يكتب صف فشل (نفس اتفاق المسار المتعدد — النجاح جلسة لا حدث).
 *  3) القفل الساري يرفض مباشرة دون زيادة العدّاد ودون صف جديد (الفشل
 *     المحصور بقفل قائم ليس محاولة تحقق).
 *
 * ملاحظة تنفيذية: verifyPin يتحقق على Dispatchers.Default (PBKDF2 600k)
 * ويرجع النتيجة عبر callback — كل خلية تنتظر اكتمال الـcallback فعلياً
 * (نمط awaitUsers في UsersVMGateTest) ولا تقرأ أعلاماً قبل الإقلاع.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class PinFallbackAuditTest {

    private val testMain = UnconfinedTestDispatcher()
    private lateinit var app: Application
    private lateinit var g: AppGraph
    private lateinit var vm: SettingsVM

    private fun resetGraph() {
        val c = Class.forName("com.superbiz.app.AppGraph")
        val inst = c.getDeclaredField("instance")
        inst.isAccessible = true
        inst.set(null, null)
    }

    // Robolectric بلا AndroidKeyStore — حقن مفتاح AES في PinVault (نمط UsersVMGateTest)
    private val testKey = javax.crypto.spec.SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    @Before
    fun setUp() {
        resetGraph()
        clearSession()
        Dispatchers.setMain(testMain)
        com.superbiz.app.security.PinVault.keyProvider = { testKey }
        com.superbiz.app.security.PinVault.userKeyProvider = { testKey }
        app = ApplicationProvider.getApplicationContext<Application>()
        g = AppGraph.from(app)
        vm = SettingsVM(app)
        // عدّاد القفل مخزن عملي مشترك — تصفيره يثبت استقلال الخلايا عن ترتيبها
        runBlocking { com.superbiz.app.security.LockoutGuard(app).onSuccess() }
    }

    private fun clearSession() {
        com.superbiz.app.domain.rbac.SessionState.clear()
        com.superbiz.app.domain.rbac.SessionState.multiUserMode = false
    }

    @After
    fun tearDown() {
        clearSession()
        Dispatchers.resetMain()
        com.superbiz.app.security.PinVault.keyProvider = { com.superbiz.app.security.PinVault.masterKey() }
        com.superbiz.app.security.PinVault.userKeyProvider = {
            com.superbiz.app.security.PinVault.keyForAlias(com.superbiz.app.security.PinVault.userAlias(it))
        }
    }

    private suspend fun failRows(): List<com.superbiz.app.data.db.AuditLogEntity> =
        g.db.auditLog().byAction("DEVICE_PIN_FAIL", 100)

    /** verifyPin غير متزامن عمداً (PBKDF2 على Default) — النتيجة تُنتظر فعلياً */
    private suspend fun verifyPinAwait(pin: String): Boolean {
        var done = false
        var result = false
        vm.verifyPin(pin) {
            result = it
            done = true
        }
        withTimeout(20_000) { while (!done) delay(20) }
        return result
    }

    /** انتظار خزنة الرمز حتى تُكتَب فعلاً (setPin غير متزامن كذلك) */
    private suspend fun awaitPinStored() {
        withTimeout(20_000) {
            while (g.settings.snapshot().pinBlob == null && g.settings.snapshot().pinHash == null) delay(20)
        }
    }

    @Test
    fun `device pin failure is audited with pre-auth attribution and success is not`() = runBlocking {
        vm.setPin("654321")
        awaitPinStored()
        val before = failRows().size

        assertFalse("رمز خاطئ يُرفض", verifyPinAwait("000000"))
        withTimeout(20_000) { while (failRows().size == before) delay(20) }
        assertEquals("كل فشل يكتب صفاً واحداً منسوباً للجهاز", before + 1, failRows().size)
        val row = failRows().first()
        assertEquals("device", row.actor)
        assertEquals(null, row.actorId)
        assertEquals(null, row.actorRole)

        // نجاح لا يضيف صف فشل (النجاح جلسة لا حدث — اتفاق USER_LOGIN نفسه)
        assertTrue("رمز صحيح يفتح", verifyPinAwait("654321"))
        assertEquals(before + 1, failRows().size)
    }

    @Test
    fun `verification while locked refuses without a new audit row or counter growth`() = runBlocking {
        vm.setPin("111222")
        awaitPinStored()
        // خمس محاولات خاطئة → القفل التصاعدي يبدأ من السادسة
        repeat(5) {
            assertFalse("المحاولة الخاطئة رقم ${it + 1} تُرفض", verifyPinAwait("999999"))
        }
        val rowsWhileLockedStart = failRows().size
        assertFalse("المرفوض مباشرة أثناء القفل يُرفض حتى لو صح الرمز", verifyPinAwait("111222"))
        assertEquals("الرفض أثناء القفل ليس محاولة تحقق — لا صف جديد",
            rowsWhileLockedStart, failRows().size)
        val st = com.superbiz.app.security.LockoutGuard(app).status()
        assertEquals("العدّاد لا يزيد وهو مقفول", 5, st.fails)
    }
}
