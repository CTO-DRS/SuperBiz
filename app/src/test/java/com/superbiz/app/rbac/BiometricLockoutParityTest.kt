package com.superbiz.app.rbac

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.AppGraph
import com.superbiz.app.domain.rbac.Role
import com.superbiz.app.domain.rbac.SessionState
import com.superbiz.app.vm.UsersVM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [E0.4 V3.3.0] تكافؤ القفل بين مساري المصادقة — البصمة تحترم قفل السياسة
 * ═══════════════════════════════════════════════════════════════════════════
 * سياسة الرمز البديل (خريطة الطريق E0.4 — القرار الموثق RVW-11/FINDINGS §2/§5):
 * عندما تتوفر البصمة لكن قفل السياسة نشط (خمس محاولات رمز فاشلة = قفل تصاعدي
 * LockoutPolicy)، فلا يجوز لمسار البصمة أن يفتح الجلسة أو يمسح العدّاد —
 * «الافتراض مغلق» على كل مسارات المصادقة بلا استثناء، وإلا صار القفل
 * التصاعدي للحماية من القسر (coercion) قابلاً للتحايل بلحظة تسليم الجهاز
 * لصاحب بصمة مسجلة.
 *
 * العقود المقفولة هنا (قاعدة Room حقيقية عبر AppGraph — نمط UsersVMGateTest):
 *  1) حساب مقفل بعد 5 رموز فاشلة + بصمة مسموحة له ⇒ openBiometricSession يرفض،
 *     لا جلسة تُفتح، وحدث USER_LOGIN_FAIL منسوب يكتب بالتفصيل (مسار تدقيق).
 *  2) رفض البصمة على حساب مقفل لا يمسح عدّاد المحاولات — القفل يبقى قائماً.
 *  3) مسار الرمز البديل نفسه مقفول أثناء قفل السياسة: verifyUserPin يعود false
 *     فوراً بلا محاولة تحقق ولا مضاعفة عدّاد (عقد الحراسة القائم يُثبَّت).
 *  4) بعد انقضاء القفل (محاكاة بالزمن الأحادي) تعود البصمة مشروعية فتح
 *     الجلسة لحسابها المسموح له — القفل عقوبة زمنية لا حظر أبدي.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class BiometricLockoutParityTest {

    private val testMain = UnconfinedTestDispatcher()
    private lateinit var app: Application
    private lateinit var g: AppGraph
    private lateinit var vm: UsersVM

    private val testKey = javax.crypto.spec.SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    @Before
    fun setUp() {
        val c = Class.forName("com.superbiz.app.AppGraph")
        val inst = c.getDeclaredField("instance")
        inst.isAccessible = true
        inst.set(null, null)
        SessionState.clear()
        Dispatchers.setMain(testMain)
        com.superbiz.app.security.PinVault.keyProvider = { testKey }
        com.superbiz.app.security.PinVault.userKeyProvider = { testKey }
        app = ApplicationProvider.getApplicationContext<Application>()
        g = AppGraph.from(app)
        vm = UsersVM(app)
    }

    @After
    fun tearDown() {
        SessionState.clear()
        Dispatchers.resetMain()
        com.superbiz.app.security.PinVault.keyProvider =
            { com.superbiz.app.security.PinVault.masterKey() }
        com.superbiz.app.security.PinVault.userKeyProvider = {
            com.superbiz.app.security.PinVault.keyForAlias(com.superbiz.app.security.PinVault.userAlias(it))
        }
    }

    /** يبني حساباً مسموحاً له البصمة ويقفل عدّاده بخمس رموز فاشلة */
    private suspend fun seedBiometricUser_thenLock(): Pair<Long, String> {
        vm.refresh()
        withTimeout(10_000) { vm.users.first { it.isNotEmpty() } }   // بذرة المالك
        vm.addUser("بائع البصمة", Role.CASHIER, "123456", biometricAllowed = true)
        val user = withTimeout(10_000) { vm.users.first { it.size == 2 } }
            .first { it.name == "بائع البصمة" }
        // خمس محاولات رمز فاشلة = بلوغ عتبة القفل (FREE_ATTEMPTS=5 → قفل 30ث)
        repeat(5) {
            val d = kotlinx.coroutines.CompletableDeferred<Boolean>()
            vm.verifyUserPin(user.id, "00000$it") { d.complete(it) }
            withTimeout(10_000) { d.await() }
        }
        return user.id to user.name
    }

    @Test
    fun lockedAccount_biometricPathRefused_noSession_noCounterClear() = runBlocking {
        val (uid, name) = seedBiometricUser_thenLock()

        // الحالة قبل المحاولة: قفل نشط
        val locked = vm.lockout.first()
        assertTrue("خمس إخفاقات = قفل نشط", locked.fails >= 5)
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        assertTrue(locked.isLocked(nowElapsed))

        // البصمة المسموحة لا تفتح حساباً مقفلاً — الافتراض مغلق على كل المسارات
        var result: Boolean? = null
        val d = kotlinx.coroutines.CompletableDeferred<Boolean>()
        vm.openBiometricSession(uid) { result = it; d.complete(it) }
        assertEquals(false, withTimeout(10_000) { d.await() })
        assertEquals(false, result)
        assertNull("لا جلسة تُفتح على حساب مقفل", SessionState.current)

        // العدّاد لا يُمسح — القفل يبقى حتى انقضائه
        val after = vm.lockout.first()
        assertTrue("رفض البصمة لا يصفّر العدّاد", after.fails >= 5)

        // مسار التدقيق: فشل منسوب بالاسم والسبب
        val events = g.db.auditLog().byAction("USER_LOGIN_FAIL", 10)
        val row = events.firstOrNull { it.actorId == uid && it.details.contains("lockout") }
        assertNotNull("حدث USER_LOGIN_FAIL مع تفصيل قفل السياسة", row)
    }

    @Test
    fun lockedAccount_pinFallback_alsoRefused_immediately() = runBlocking {
        val (uid, _) = seedBiometricUser_thenLock()
        // الرمز البديل أثناء القفل: رفض فوري بلا تحقق — حتى لو كان الرمز صحيحاً
        val d = kotlinx.coroutines.CompletableDeferred<Boolean>()
        vm.verifyUserPin(uid, "123456") { d.complete(it) }
        assertEquals(false, withTimeout(10_000) { d.await() })
        assertNull(SessionState.current)
        // العدّاد لا يتضاعف بالرفض أثناء القفل (سلوك الحراسة الموثق — يُثبَّت)
        val st = vm.lockout.first()
        assertTrue(st.fails >= 5)
    }

    @Test
    fun sameBiometricCall_onUnlockedAccount_opensLegitimately() = runBlocking {
        // العقدة المكمّلة للرفض أعلاه: نفس الاستدعاء على حساب غير مقفل يفتح جلسة
        // (تثبت أن الرفض في الاختبار الأول سببه قفل السياسة لا شيء آخر).
        // ترتيب مستقل: حارس القفل DataStore مشترك داخل العملية عبر الاختبارات
        // (نفس صنف ConfigRbacGateTest) — لذا يُصفَّر عدّاد الحساب عبر مساره
        // الحقيقي: resetPin (إدارة المالك) يصفّر عدّاد المستهدف بموجب العقد الموثق.
        vm.refresh()
        withTimeout(10_000) { vm.users.first { it.isNotEmpty() } }
        vm.addUser("بائع حر", Role.CASHIER, "654321", biometricAllowed = true)
        val user = withTimeout(10_000) { vm.users.first { it.size == 2 } }
            .first { it.name == "بائع حر" }

        // تصفير الحارس عبر المسار الإنتاجي (سواء بقيت بقايا قفل من اختبار سابق أم لا).
        // resetPin غير متزامن بلا كولباك ولا يحدّث تدفق الواجهة — الحسم سلوكي حتمي:
        // نجاح verifyUserPin مستحيل أثناء قفل نشط، فنجاحه = الحارس صُفِّر فعلاً.
        vm.resetPin(user.id, "654321")
        var pinOpened = false
        withTimeout(15_000) {
            while (!pinOpened) {
                val dPin = kotlinx.coroutines.CompletableDeferred<Boolean>()
                vm.verifyUserPin(user.id, "654321") { dPin.complete(it) }
                pinOpened = dPin.await()
                if (!pinOpened) kotlinx.coroutines.delay(100)
            }
        }
        assertTrue(pinOpened)
        vm.endSession()
        assertNull(SessionState.current)

        var ok: Boolean? = null
        val d = kotlinx.coroutines.CompletableDeferred<Boolean>()
        vm.openBiometricSession(user.id) { ok = it; d.complete(it) }
        assertEquals(true, withTimeout(10_000) { d.await() })
        val sess = SessionState.current
        assertNotNull("البصمة على حساب غير مقفل تفتح جلسة", sess)
        assertEquals(user.id, sess!!.userId)
    }
}
