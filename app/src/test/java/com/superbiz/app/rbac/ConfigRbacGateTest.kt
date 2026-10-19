package com.superbiz.app.rbac

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.AppGraph
import com.superbiz.app.data.repo.WebhookStore
import com.superbiz.app.domain.SettingsCodec
import com.superbiz.app.domain.rbac.Role
import com.superbiz.app.domain.rbac.RoleDeniedException
import com.superbiz.app.domain.rbac.SessionState
import com.superbiz.app.domain.statement.SmtpPrefsUi
import com.superbiz.app.domain.statement.StatementPrefs
import com.superbiz.app.vm.SettingsVM
import kotlinx.coroutines.CompletableDeferred
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
import java.io.File

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [RVW-13 V 3.2.4] بوابات تهيئة القنوات والهوية على مسارات بديلة — «التطبيق عند الحدّ»
 * ═══════════════════════════════════════════════════════════════════════════
 * الثغرة التي يسدها هذا الموج: مُحدّثات حسّاسة كانت بلا بوابة على مستوى التنفيذ
 * رغم أن المُحدّثات المباشرة لنفس المفاتيح مُسوّرة — أي «مسار بديل يلتف على المصفوفة»:
 *
 * ① استيراد إعدادات JSON (مُغيّر مجمّع: ضريبة/عملة/ولاية/قفل/خصوصية/مستلم تقارير)
 *    ⇒ GENERAL_SETTINGS (المالك والمدير) — كان يلتف على بوابات المفاتيح نفسها.
 * ② ويب هوك: التفعيل كان مُسوّراً في الواجهة فقط، وإعادة توجيه العنوان والسر كانت
 *    مكشوفتين تماماً — الأحداث الموقّعة بسر المالك لا يجوز أن تُوجَّه لطرف آخر
 *    ⇒ BACKUP_RESTORE (المالك وحده) في طبقة البيانات.
 * ③ تهيئة SMTP للكشوف (قناة الصادر المالي + بيانات الاعتماد) ⇒ GENERAL_SETTINGS
 *    في طبقة prefs — كل مسارات الحفظ في الشاشة تمر من نقطة واحدة.
 * ④ تهيئة توصيل التقارير (مستلم/قناة/توقيت/هدف شهري/أيام النسخ التلقائي)
 *    ⇒ GENERAL_SETTINGS — جلسة أدنى لا توجّه تقارير مالية إلى عنوان خارجي.
 * ⑤ مجلد النسخ الاحتياطي ⇒ BACKUP_RESTORE — وجهة أرشيف الأعمال كاملها.
 * ⑥ الهوية الضريبية للمالك (العنوان/الرقم الضريبي) ⇒ TAX_OWNER_DATA (المالك وحده).
 *
 * عقود عدم الانحدار (نمط BackupRbacGateTest حرفياً):
 *  • الوضع الأحادي بلا جلسة ⇒ IMPLICIT_OWNER ⇒ يمر كله (تصميم §4.3 حرفي).
 *  • وضع التعدد بلا جلسة ⇒ SYSTEM (OWNER) ⇒ يمر كله (عقد الأتمتة — العملاء الخلفيون).
 *  • الرفض ظاهر: طبقة البيانات ترمي RoleDeniedException قبل أي كتابة، ومسار الـVM
 *    يبتلعها في launchSafe فيبقى الحالة على قيمها (لا كتابة صامتة ولا انهيار).
 *  • المدير يمر على GENERAL_SETTINGS ويرفض على BACKUP_RESTORE/TAX_OWNER_DATA —
 *    المصفوفة حرفياً لا تعميم فضفاض.
 *
 * نمط الاختبار من UsersVMGateTest حرفياً: Robolectric بلا تطبيق حقيقي، مفاتيح PinVault
 * محقونة، Main مُستبدل بـUnconfined ليصبح launchSafe متزامن القراءة.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class ConfigRbacGateTest {

    private val testMain = UnconfinedTestDispatcher()
    private lateinit var app: Application
    private lateinit var g: AppGraph
    private lateinit var vm: SettingsVM
    private lateinit var webhook: WebhookStore

    private val testKey = javax.crypto.spec.SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    private fun resetGraph() {
        val c = Class.forName("com.superbiz.app.AppGraph")
        val inst = c.getDeclaredField("instance")
        inst.isAccessible = true
        inst.set(null, null)
    }

    @Before
    fun setUp() {
        resetGraph()
        SessionState.clear()
        SessionState.multiUserMode = false
        Dispatchers.setMain(testMain)
        com.superbiz.app.security.PinVault.keyProvider = { testKey }
        app = ApplicationProvider.getApplicationContext()
        g = AppGraph.from(app)
        vm = SettingsVM(app)
        webhook = WebhookStore(app)
    }

    @After
    fun tearDown() {
        SessionState.clear()
        SessionState.multiUserMode = false
        Dispatchers.resetMain()
        com.superbiz.app.security.PinVault.keyProvider =
            { com.superbiz.app.security.PinVault.masterKey() }
    }

    // ─────────────────── مساعدات ───────────────────

    /** ملف استيراد إعدادات صالح (المفاتيح عبر المُبني الرسمي — لا JSON يدوي) */
    private fun settingsImportFile(): Uri {
        val json = SettingsCodec.build(
            listOf(SettingsCodec.Entry("taxRate", SettingsCodec.Val.N(17.5)))
        )
        val f = File(app.cacheDir, "rbac_settings_import.json")
        f.writeText(json)
        return Uri.fromFile(f)
    }

    private fun assertWebhookWriteDenied() = runBlocking {
        // قراءة قبل/بعد — استقلالية عن ترتيب الاختبارات (DataStore مشترك داخل الصنف)
        val beforeEnabled = webhook.enabledOnce()
        val beforeUrl = webhook.urlOnce()
        val beforeSecret = webhook.secretOnce()
        var thrown = 0
        try { webhook.setEnabled(true) } catch (e: RoleDeniedException) { thrown++ }
        try { webhook.setUrl("https://attacker.example/hook") } catch (e: RoleDeniedException) { thrown++ }
        try { webhook.setSecret("leaked-secret") } catch (e: RoleDeniedException) { thrown++ }
        assertEquals(3, thrown)
        // لا كتابة صامتة: الحالة الثلاثية باقية كما كانت حرفياً
        assertEquals(beforeEnabled, webhook.enabledOnce())
        assertEquals(beforeUrl, webhook.urlOnce())
        assertEquals(beforeSecret, webhook.secretOnce())
    }

    private fun assertStatementSmtpWriteDenied() {
        val beforeHost = StatementPrefs.load(app).host
        var thrown = false
        try {
            StatementPrefs.save(
                app, SmtpPrefsUi(smtpEnabled = true, host = "attacker.example", user = "x@y.z")
            )
        } catch (e: RoleDeniedException) { thrown = true }
        assertTrue(thrown)
        // لا كتابة: المضيف المخزّن باقٍ كما كان
        assertEquals(beforeHost, StatementPrefs.load(app).host)
    }

    // ─────────────────── ① الكاشير: الرفض على كل الأبواب الجديدة ───────────────────

    @Test
    fun `cashier session denied on settings import, webhook, smtp, delivery, backup dir and owner identity`() = runBlocking {
        SessionState.multiUserMode = true
        SessionState.start(3L, "cashier", Role.CASHIER)

        // استيراد الإعدادات — الضريبة باقية على قيمتها السابقة مهما كانت (الرفض بلا كتابة)
        val beforeTax = g.settings.snapshot().taxRate
        vm.importSettingsFrom(settingsImportFile()) { _, _ -> }
        assertEquals(beforeTax, g.settings.snapshot().taxRate, 0.0001)

        assertWebhookWriteDenied()
        assertStatementSmtpWriteDenied()

        // تهيئة توصيل التقارير — الرفض بلا كتابة (قبل/بعد حرفياً)
        val beforeRecipient = g.settings.snapshot().reportRecipient
        vm.setReportRecipient("attacker@example.com")
        assertEquals(beforeRecipient, g.settings.snapshot().reportRecipient)
        val beforeGoal = g.settings.snapshot().monthlyGoal
        vm.setMonthlyGoal(99_999.0)
        assertEquals(beforeGoal, g.settings.snapshot().monthlyGoal, 0.0001)

        // مجلد النسخ الاحتياطي والهوية الضريبية — رفض بلا كتابة
        val beforeDir = g.settings.snapshot().backupDirUri
        vm.setBackupDir("/sdcard/evil" as String?)
        assertEquals(beforeDir, g.settings.snapshot().backupDirUri)
        val beforeTaxNo = g.settings.snapshot().taxNumber
        vm.setTaxNumber("FAKE-9001")
        assertEquals(beforeTaxNo, g.settings.snapshot().taxNumber)
    }

    // ─────────────────── ② المدير: GENERAL_SETTINGS يمر ومالك-العناصر تُرفض ───────────────────

    @Test
    fun `manager session passes general settings gates but denied owner-only channels`() = runBlocking {
        SessionState.multiUserMode = true
        SessionState.start(1L, "manager", Role.MANAGER)

        // الاستيراد يمر (المدير داخل GENERAL_SETTINGS) — الضريبة تُطبق فعلًا.
        // [RVW-14] النتيجة تصل عبر callback داخل launchSafe (إطلاق غير متزامن) —
        // الانتظار الحتمي بديل السباق: كانت القيمة الأولية ‎-1 تُقرأ قبل وصول callback
        // على بيئات أبطأ (فشل زائف لا علاقة له بالبوابة). فشل حقيقي = مهلة واضحة
        // لا قيمة مُلتبسة، والعقد نفسه كما هو: مدير يُدخِل، ولا كتابة صامتة للرفض.
        val applied = withTimeout(15_000) {
            CompletableDeferred<Int>().also { d ->
                vm.importSettingsFrom(settingsImportFile()) { n, _ -> d.complete(n) }
            }.await()
        }
        assertEquals(1, applied)
        assertEquals(17.5, withTimeout(15_000) { g.settings.settings.first { it.taxRate == 17.5 } }.taxRate, 0.0001)

        // تهيئة التوصيل تمر — قراءة بالانتظار الحتمي (كتابة DataStore غير متزامنة)
        vm.setReportRecipient("owner@example.com")
        assertEquals("owner@example.com", withTimeout(15_000) { g.settings.settings.first { it.reportRecipient == "owner@example.com" } }.reportRecipient)
        vm.setMonthlyGoal(12_000.0)
        assertEquals(12_000.0, withTimeout(15_000) { g.settings.settings.first { it.monthlyGoal == 12_000.0 } }.monthlyGoal, 0.0001)
        vm.setReportScheduleChannel("email")
        assertEquals("email", withTimeout(15_000) { g.settings.settings.first { it.reportScheduleChannel == "email" } }.reportScheduleChannel)

        // قنوات المالك وحده: الويب هوك ومجلد النسخ والهوية الضريبية — رفض بلا كتابة
        assertWebhookWriteDenied()
        val beforeDir = g.settings.snapshot().backupDirUri
        vm.setBackupDir("/sdcard/mgr" as String?) // عبر launchSafe: الرفض يُبتلع فلا استثناء يمر للمستدعي
        assertEquals(beforeDir, g.settings.snapshot().backupDirUri)
        val beforeTaxNo = g.settings.snapshot().taxNumber
        vm.setTaxNumber("MGR-1")
        assertEquals(beforeTaxNo, g.settings.snapshot().taxNumber)
    }

    // ─────────────────── ③ المالك الصريح: كل الأبواب تمر ───────────────────

    @Test
    fun `owner explicit session passes all new gates`() = runBlocking {
        SessionState.multiUserMode = true
        SessionState.start(0L, "owner", Role.OWNER)

        webhook.setEnabled(true)
        webhook.setUrl("https://hooks.owner.example/sb")
        webhook.setSecret("owner-secret")
        assertTrue(webhook.enabledOnce())
        assertEquals("https://hooks.owner.example/sb", webhook.urlOnce())
        assertEquals("owner-secret", webhook.secretOnce())

        StatementPrefs.save(app, SmtpPrefsUi(smtpEnabled = true, host = "smtp.owner.example"))
        assertEquals("smtp.owner.example", StatementPrefs.load(app).host)

        vm.setBackupDir("/sdcard/owner-bk" as String?)
        assertEquals("/sdcard/owner-bk", withTimeout(15_000) { g.settings.settings.first { it.backupDirUri == "/sdcard/owner-bk" } }.backupDirUri)
        vm.setTaxNumber("OWN-77")
        assertEquals("OWN-77", withTimeout(15_000) { g.settings.settings.first { it.taxNumber == "OWN-77" } }.taxNumber)
        vm.setReportRecipient("reports@owner.example")
        assertEquals("reports@owner.example", withTimeout(15_000) { g.settings.settings.first { it.reportRecipient == "reports@owner.example" } }.reportRecipient)
    }

    // ─────────────────── ④ عقد الوضع الأحادي §4.3 حرفياً ───────────────────

    @Test
    fun `implicit owner contract single-user no session passes - design 4-3`() = runBlocking {
        SessionState.clear()
        SessionState.multiUserMode = false
        vm.setTaxNumber("LEGACY-1")
        assertEquals("LEGACY-1", withTimeout(15_000) { g.settings.settings.first { it.taxNumber == "LEGACY-1" } }.taxNumber)
        webhook.setUrl("https://legacy.example/h")
        assertEquals("https://legacy.example/h", webhook.urlOnce())
        StatementPrefs.save(app, SmtpPrefsUi(host = "legacy.smtp"))
        assertEquals("legacy.smtp", StatementPrefs.load(app).host)
        // [RVW-14] انتظار اكتمال الاستيراد نفسه قبل قراءة الضريبة — لا افتراض سباق
        withTimeout(15_000) {
            CompletableDeferred<Unit>().also { d ->
                vm.importSettingsFrom(settingsImportFile()) { _, _ -> d.complete(Unit) }
            }.await()
        }
        assertEquals(17.5, withTimeout(15_000) { g.settings.settings.first { it.taxRate == 17.5 } }.taxRate, 0.0001)
    }

    // ─────────────────── ⑤ عقد الأتمتة: SYSTEM يمر ───────────────────

    @Test
    fun `SYSTEM identity in multi-user no session passes - automation contract`() = runBlocking {
        SessionState.clear()
        SessionState.multiUserMode = true
        webhook.setUrl("https://automation.example/h")
        assertEquals("https://automation.example/h", webhook.urlOnce())
        StatementPrefs.save(app, SmtpPrefsUi(host = "auto.smtp"))
        assertEquals("auto.smtp", StatementPrefs.load(app).host)
        vm.setBackupDir("/sdcard/auto" as String?)
        assertEquals("/sdcard/auto", withTimeout(15_000) { g.settings.settings.first { it.backupDirUri == "/sdcard/auto" } }.backupDirUri)
    }

    // ─────────────────── ⑥ رفض طبقة البيانات يسبق أي كتابة (فشل ظاهر) ───────────────────

    @Test
    fun `data layer denial precedes any write - visible failure not silent corruption`() = runBlocking {
        // مالك يضبط عنواناً شرعياً أولاً
        SessionState.start(0L, "owner", Role.OWNER)
        webhook.setUrl("https://legitimate.example/h")
        StatementPrefs.save(app, SmtpPrefsUi(host = "legit.smtp"))
        val legitUrl = webhook.urlOnce()
        val legitHost = StatementPrefs.load(app).host

        // ثم جلسة كاشير — كل الكتابات مرفوضة
        SessionState.start(3L, "cashier", Role.CASHIER)
        assertWebhookWriteDenied()
        assertStatementSmtpWriteDenied()

        // القيم الشرعية باقية حرفياً — لا مس ولا كتابة جزئية
        assertEquals(legitUrl, webhook.urlOnce())
        assertEquals(legitHost, StatementPrefs.load(app).host)
    }
}
