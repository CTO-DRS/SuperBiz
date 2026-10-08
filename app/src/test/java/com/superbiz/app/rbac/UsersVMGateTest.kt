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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [H1-4][H1-5][V 1.2.0] بوابات المستخدمين على قاعدة حقيقية — «مُختبَر بآلة»
 * ═══════════════════════════════════════════════════════════════════════════
 * عقد القبول (RBAC_V13_DESIGN §6 + بوابة H1-5 في الخارطة):
 *  • إدارة المستخدمين باب المالك وحده — جلسة كاشير تنشئ مستخدماً ⇒ رفض مغلق
 *    لا يغيّر القائمة (RoleGate داخل launchSafe يفشل بلا انهيار).
 *  • آخر مالك فعّال محمي من التعطيل — الجهاز لا يبقى بلا مالك.
 *  • دخول كاشير برمزه الصحيح يفتح جلسة كاشير ويكتب حدثَي تدقيق منسوبَين.
 *  • فشل الرمز يرفع عدّاد مستهدفه فقط.
 * قاعدة Room ملفية حقيقية عبر AppGraph (نمط DebtsInventoryVMTest حرفياً) —
 * إقلاع SuperBizApp الحقيقي ممنوع (@Config application ناضف) ولا DataStore
 * قديم يتداخل: قاعدة v13 نظيفة تُنشأ لكل اختبار.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class UsersVMGateTest {

    private val testMain = UnconfinedTestDispatcher()
    private lateinit var app: Application
    private lateinit var g: AppGraph
    private lateinit var vm: UsersVM

    private fun resetGraph() {
        val c = Class.forName("com.superbiz.app.AppGraph")
        val inst = c.getDeclaredField("instance")
        inst.isAccessible = true
        inst.set(null, null)
    }

    // Robolectric بلا AndroidKeyStore — حقن مفتاح AES للحقن في PinVault كلي المسارين
    // (المستخدمون الجدد يُغلّفون بمفتاح alias لكل مستخدم — نمط SecurityWaveTest نفسه)
    private val testKey = javax.crypto.spec.SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    @Before
    fun setUp() {
        resetGraph()
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
        com.superbiz.app.security.PinVault.keyProvider = { com.superbiz.app.security.PinVault.masterKey() }
        com.superbiz.app.security.PinVault.userKeyProvider = {
            com.superbiz.app.security.PinVault.keyForAlias(com.superbiz.app.security.PinVault.userAlias(it))
        }
    }

    /** انتظار قائمة المستخدمين حتى تطابق الحجم المستهدف */
    private suspend fun awaitUsers(size: Int) =
        withTimeout(10_000) { vm.users.first { it.size == size } }

    // ═══ المسار الأحادي: بذرة المالك + إدارة كاملة ═══

    @Test
    fun implicitOwner_canManage_addCashier_and_lastOwnerGuardHolds() = runBlocking {
        // بذرة المالك على قاعدة v13 نظيفة (نفس عقد الترحيل للأجهزة الجديدة)
        vm.refresh()
        awaitUsers(1)
        val owner = g.db.users().all().first { it.role == 0 }
        assertTrue(owner.active == 1)

        // المالك الضمني يضيف كاشيراً برمز صالح
        vm.addUser("سالم الكاشير", Role.CASHIER, "123456")
        val users = awaitUsers(2)
        val cashier = users.first { it.name == "سالم الكاشير" }
        assertEquals(3, cashier.role)
        // سر الكاشير موجود ومغلّف صيغة ks
        val secret = g.db.userSecrets().byUser(cashier.id)!!
        assertTrue(secret.pinWrapped.startsWith("ks:"))

        // حارس آخر المالك: تعطيل المالك الوحيد مرفوض (فشل مغلق برسالة)
        vm.setActive(owner.id, false)
        val stillActive = g.db.users().byId(owner.id)!!
        assertEquals(1, stillActive.active)

        // حارس الذات: المالك الضمني userId=0 لا يطابق صاحب جلسة… والمالك المزروع id≠0
        // حذف الكاشير يمر (ليس آخر مالك)
        vm.deleteUser(cashier.id)
        awaitUsers(1)
        assertEquals(null, g.db.userSecrets().byUser(cashier.id)) // CASCADE
    }

    // ═══ بوابة الإدارة: الكاشير لا يدير المستخدمين ═══

    @Test
    fun cashierSession_cannotManageUsers_deniedClosed() = runBlocking {
        vm.refresh()
        awaitUsers(1)
        // جلسة كاشير صريحة على مستخدم وهمي (لا حاجة لسر هنا — البوابة قبل المصادقة)
        SessionState.start(77L, "كاشير غريب", Role.CASHIER)

        vm.addUser("متسلل", Role.OWNER, "999999")
        // القائمة لم تتغير — الرفض مغلق بلا انهيار (launchSafe يلتقط RoleDeniedException)
        assertEquals(1, vm.users.first().size)
        assertEquals(null, g.db.users().all().firstOrNull { it.name == "متسلل" })
    }

    @Test
    fun accountantSession_cannotManageUsers_butOwnerCan() = runBlocking {
        vm.refresh()
        awaitUsers(1)
        SessionState.start(88L, "المحاسب", Role.ACCOUNTANT)
        vm.addUser("محاول", Role.MANAGER, "111222")
        assertEquals(1, vm.users.first().size)

        // عودة المالك — يمر
        SessionState.start(1L, "المالك", Role.OWNER)
        vm.addUser("مدير جديد", Role.MANAGER, "333444")
        awaitUsers(2)
    }

    // ═══ دخول الكاشير: جلسة حقيقية + إسناد تدقيق + قفل على حدة ═══

    @Test
    fun cashierLogin_ok_opensCashierSession_andWritesAttributedAudit() = runBlocking {
        vm.refresh()
        awaitUsers(1)
        vm.addUser("سالم", Role.CASHIER, "123456")
        val cashier = awaitUsers(2).first { it.name == "سالم" }

        // رمز خاطئ: يرفع عدّاد مستهدفه فقط ولا يفتح جلسة
        var ok: Boolean? = null
        vm.verifyUserPin(cashier.id, "000000") { ok = it }
        assertEquals(false, ok)
        assertEquals(null, SessionState.current)
        assertEquals(1, vm.lockout.first().fails)

        // رمز صحيح: جلسة كاشير + ختم ظهور
        vm.verifyUserPin(cashier.id, "123456") { ok = it }
        assertEquals(true, ok)
        val sess = SessionState.current!!
        assertEquals(cashier.id, sess.userId)
        assertEquals(Role.CASHIER, sess.role)

        // الإسناد: حدثا الدخول كلاهما منسوب للمستخدم المستهدف
        val events = g.db.auditLog().byAction("USER_LOGIN_OK", 5)
        val okEvent = events.first { it.details.contains("role=${cashier.role}") }
        assertEquals(cashier.id, okEvent.actorId)
        assertEquals(3, okEvent.actorRole)
        assertEquals("سالم", okEvent.actor)
        val failEvents = g.db.auditLog().byAction("USER_LOGIN_FAIL", 5)
        assertTrue(failEvents.any { it.actorId == cashier.id })

        // بوابات الدور فوق الجلسة: الكاشير محروم من التقارير والحذف
        assertFalse(sess.can(com.superbiz.app.domain.rbac.Op.FINANCIAL_REPORTS))
        assertFalse(sess.can(com.superbiz.app.domain.rbac.Op.HARD_DELETE))
        assertTrue(sess.can(com.superbiz.app.domain.rbac.Op.POS_SELL))
    }

    @Test
    fun userWithoutSecret_failsClosed_noSilentUnlock() = runBlocking {
        vm.refreshActiveUsers()
        // مستخدم بلا سر يُدرج مباشرة عبر DAO (نافذة الفشل المعلنة في addUser)
        val bareId = g.db.users().insert(
            com.superbiz.app.data.db.UserEntity(name = "بلا رمز", role = 3, active = 1)
        )
        var ok: Boolean? = null
        vm.verifyUserPin(bareId, "123456") { ok = it }
        assertEquals(false, ok)
        assertEquals(null, SessionState.current)
    }
}
