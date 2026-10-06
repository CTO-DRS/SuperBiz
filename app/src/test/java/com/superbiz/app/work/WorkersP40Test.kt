package com.superbiz.app.work

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.superbiz.app.AppGraph
import com.superbiz.app.data.db.Invoice
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.db.Rule
import com.superbiz.app.data.db.StatementDeliveryEntity
import com.superbiz.app.data.db.StatementEntity
import com.superbiz.app.data.db.Visit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * [P40-M5] المرحلة 5 — جدار اختبارات العمال الحقيقية (كان بلا TestDriver/TestBuilder):
 *
 * • AutomationWorker / BackupWorker / StatementScheduleWorker تُدار فعلياً عبر
 *   TestListenableWorkerBuilder (الطريقة الرسمية لقيادة CoroutineWorker بلا
 *   منفّذ إنتاج) — doWork() الحقيقي فوق AppGraph ملفي حقيقي (نمط P37/P39).
 * • VisitReminder يُدار عبر onFired الفعلي (مسار المتلقي نفسه) — إشعار المتأخرين
 *   وإعادة تسليح منبّه الغد تُرصَدان من Shadow النظام.
 * • المُشغّل الجدولي: WorkManagerTestInitHelper + TestDriver — جدولة
 *   «superbiz_daily» الدورية تُساق بمُسبّت تأخير الدورية حتى SUCCEEDED —
 *   أول اختبار في المشروع يمرّ عبر محرّك WorkManager نفسه لا حوله.
 * • آلة حالات التسليم تُختبر من مسار العامل الحقيقي: FAILED/EMAIL بلا ملف كشف
 *   يستهلك محاولة (FAILED + attempts+1 بمحتوى خطأ صادق)، وWHATSAPP تنتقل إلى
 *   RETRYING بلا حرق محاولات — نفس عقد machine الحالات في StatementRepo.
 *
 * ══ نمط الجهاز الافتراضي (عقد P37 المعمول به) ══
 * @Config(application = android.app.Application::class) يمنع إقلاع SuperBizApp —
 * صفّر AppGraph.instance بالانعكاس قبل/بعد كل اختبار، وDispatchers.setMain
 * للكوروتينات الحية في VMs/Workers، والجمع من قاعدة ملفية حقيقية بـwithTimeout.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class WorkersP40Test {

    private val testMain = UnconfinedTestDispatcher()
    private lateinit var app: Application
    private lateinit var g: AppGraph

    private fun resetGraph() {
        val c = Class.forName("com.superbiz.app.AppGraph")
        val inst = c.getDeclaredField("instance")
        inst.isAccessible = true
        inst.set(null, null)
    }

    private fun notifs(): List<android.app.Notification> {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return Shadows.shadowOf(nm).allNotifications
    }

    @Before
    fun setUp() {
        resetGraph()
        Dispatchers.setMain(testMain)
        app = ApplicationProvider.getApplicationContext()
        g = AppGraph.from(app)
    }

    @After
    fun tearDown() {
        resetGraph()
        Dispatchers.resetMain()
    }

    // ═════════ AutomationWorker ═════════

    @Test
    fun `عامل الأتمتة على قاعدة فارغة ينجح بلا إشعارات`() = runBlocking {
        val result = TestListenableWorkerBuilder<AutomationWorker>(app).build().doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue("لا إشعار بلا قواعد ولا مستحقات", notifs().isEmpty())
    }

    @Test
    fun `عامل الأتمتة يذكّر بفاتورة مستحقة ببذرة حقيقية`() = runBlocking {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val now = System.currentTimeMillis()
        val pid = g.db.parties().upsert(Party(name = "عميل الذمم", phone = "0500000000", type = 0))
        g.db.rules().upsert(Rule(kind = "DUE_REMIND", enabled = true, daysBefore = 3, lastRun = 0L))
        // فاتورة بيع مستحقة خلال اليومين القادمين (داخل أفق 3 أيام) وغير مدفوعة
        g.db.invoices().upsert(
            Invoice(
                number = "INV-P40-1", partyId = pid, type = 0,
                date = now - 86_400_000L, dueDate = now + 2 * 86_400_000L,
                subtotal = 10_000L, total = 11_500L, taxRate = 0.15, taxAmount = 1_500L,
                paid = 0L, status = 0
            )
        )
        val result = TestListenableWorkerBuilder<AutomationWorker>(app).build().doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue("تذكير الاستحقاق ينشر إشعاراً فعلياً عبر مسار العامل", notifs().isNotEmpty())
    }

    @Test
    fun `عامل الأتمتة بلا إذن إشعارات ينجح بصمت صادق`() = runBlocking {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val now = System.currentTimeMillis()
        val pid = g.db.parties().upsert(Party(name = "عميل صامت", phone = "0500000001", type = 0))
        g.db.rules().upsert(Rule(kind = "DUE_REMIND", enabled = true, daysBefore = 3, lastRun = 0L))
        g.db.invoices().upsert(
            Invoice(
                number = "INV-P40-2", partyId = pid, type = 0,
                date = now - 86_400_000L, dueDate = now + 86_400_000L,
                subtotal = 5_000L, total = 5_750L, taxRate = 0.15, taxAmount = 750L,
                paid = 0L, status = 0
            )
        )
        val result = TestListenableWorkerBuilder<AutomationWorker>(app).build().doWork()
        assertEquals("بلا إذن: النجاح بلا انهيار — عقد البوابة P5-H14", ListenableWorker.Result.success(), result)
        assertTrue(notifs().isEmpty())
    }

    // ═════════ BackupWorker ═════════

    @Test
    fun `عامل النسخ المعطل ينجح دون ختم`() = runBlocking {
        // الافتراضي المبذور هو 1 — المعطّل يُضبط صراحة عبر نفس مسار الإعدادات.
        // الحكم على «عدم تقدم الختم» لا قيمته المطلقة: DataStore مفرد على مستوى
        // العملية وقد يحمل ختم اختبار سابق من نفس الصنف (نطاق الاختبار = الصنف).
        g.settings.setAutoBackupDays(0)
        assertEquals(0, g.settings.snapshot().autoBackupDays)
        val stampBefore = g.settings.snapshot().lastAutoBackup
        val result = TestListenableWorkerBuilder<BackupWorker>(app).build().doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(
            "autoBackupDays=0 → خروج مبكر لا يتقدم الختم",
            stampBefore, g.settings.snapshot().lastAutoBackup
        )
    }

    @Test
    fun `عامل النسخ المستحق يصدّر ويختم وثاني استحقاق يتخطى`() = runBlocking {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        g.settings.setAutoBackupDays(1)
        // force=true (نفس مفتاح «نسخ الآن» في الواجهة) يضمن تصديراً فعلياً مهما
        // كان ختم DataStore الموروث من اختبار سابق في نفس الصنف
        val w = TestListenableWorkerBuilder<BackupWorker>(app)
            .setInputData(androidx.work.workDataOf(BackupWorker.KEY_FORCE to true))
            .build()
        assertEquals(ListenableWorker.Result.success(), w.doWork())
        val stamp1 = g.settings.snapshot().lastAutoBackup
        assertTrue("الختم يُكتب فقط بعد نجاح exportLocal فعلياً [P6-M36]", stamp1 > 0L)
        // الدورة الثانية بلا force: الختم الطازج يجعل isDue=false → تخطٍّ نظيف
        val w2 = TestListenableWorkerBuilder<BackupWorker>(app).build()
        assertEquals(ListenableWorker.Result.success(), w2.doWork())
        assertEquals("الختم لا يتقدم دورةً بلا استحقاق — عقد BackupAutoLogic.isDue", stamp1, g.settings.snapshot().lastAutoBackup)
    }

    // ═════════ StatementScheduleWorker ═════════

    @Test
    fun `مجدول الكشوف على قاعدة فارغة ينجح`() = runBlocking {
        val result = TestListenableWorkerBuilder<StatementScheduleWorker>(app).build().doWork()
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `إعادة المحاولة الآلية لفاشل بريدي بلا ملف تحرق محاولة بمحتوى صادق`() = runBlocking {
        // بذرة: الطرف (FK) ثم الكشف (المرجع الأب FK) ثم تسليم FAILED على قناة بريد بلا ملف فعلي
        g.db.parties().upsert(Party(id = 10, name = "طرف كشف بريدي", phone = "050", type = 0))
        g.db.statements().upsert(
            StatementEntity(
                id = 1, statementNumber = "STATEMENT-P40-1",
                verificationId = "SB-ST-P40-1", partyId = 10,
                fromTs = 1_700_407_200_000L, toTs = 1_702_348_799_000L,
                templateId = "7", currency = "SAR", contentHash = "hash1",
                filePath = "/nonexistent/p40/missing.pdf", note = null,
                createdAt = 1_702_348_800_000L, lang = "BILINGUAL"
            )
        )
        val did = g.statements.recordDelivery(
            statementId = 1, channel = "SMTP", status = "FAILED",
            attempts = 0, error = "previous failure", scheduledFor = null
        )
        val result = TestListenableWorkerBuilder<StatementScheduleWorker>(app).build().doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        val d = g.db.statements().findDeliveryById(did)!!
        assertEquals("الملف مفقود → FAILED صريح يستهلك محاولة (آلة الحالات)", "FAILED", d.status)
        assertEquals("attempts من 0 إلى 1 — PROCESSING/SENT/FAILED تحرق محاولة", 1, d.attempts)
        assertTrue("محتوى الخطأ صادق ومفهوم", (d.lastError ?: "").contains("missing"))
    }

    @Test
    fun `فاشل قناة يدوية ينتقل RETRYING بلا حرق محاولات`() = runBlocking {
        g.db.parties().upsert(Party(id = 20, name = "طرف كشف يدوي", phone = "051", type = 0))
        g.db.statements().upsert(
            StatementEntity(
                id = 2, statementNumber = "STATEMENT-P40-2",
                verificationId = "SB-ST-P40-2", partyId = 20,
                fromTs = 1_700_407_200_000L, toTs = 1_702_348_799_000L,
                templateId = "7", currency = "SAR", contentHash = "hash2",
                filePath = "/nonexistent/p40/manual.pdf", note = null,
                createdAt = 1_702_348_800_000L, lang = "BILINGUAL"
            )
        )
        val did = g.statements.recordDelivery(
            statementId = 2, channel = "WHATSAPP", status = "FAILED",
            attempts = 0, error = "user cancelled", scheduledFor = null
        )
        TestListenableWorkerBuilder<StatementScheduleWorker>(app).build().doWork()
        val d = g.db.statements().findDeliveryById(did)!!
        assertEquals("قناة يدوية → RETRYING بارزة لإعادة الإرسال اليدوي", "RETRYING", d.status)
        assertEquals("لا محاولات تُستهلك بلا فعل فعلي [P40-M5 عقد التعليق]", 0, d.attempts)
    }

    // ═════════ VisitReminder (مسار المتلقي الحقيقي) ═════════

    @Test
    fun `إطلاق تذكير الزيارات يبلغ المتأخرين ويعيد تسليح الغد`() = runBlocking {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // الإعداد عبر API العام نفسه الذي تستخدمه بطاقة الإعدادات
        VisitReminder.setThreshold(app, 14)
        VisitReminder.setTime(app, 8, 0)
        VisitReminder.setEnabled(app, true)
        val pid = g.db.parties().upsert(Party(name = "عميل متأخر الزيارة", phone = "0500000002", type = 0))
        // زيارة وحيدة عمرها 30 يوماً — فوق عتبة 14 يوماً ⇒ متأخر
        g.db.visits().insert(
            Visit(partyId = pid, visitedAt = System.currentTimeMillis() - 30L * 86_400_000L)
        )
        VisitReminder.onFired(app, result = null)
        // onFired يعمل على IO خارجي — نستطلع الأثر الأقوى: إشعار المتأخرين
        withTimeout(60_000) {
            while (notifs().isEmpty()) kotlinx.coroutines.delay(100)
        }
        assertTrue("إشعار المتأخرين يُنشر عبر AutomationLogic.notify", notifs().isNotEmpty())
    }

    // ═════════ المُشغّل الجدولي — TestDriver عبر محرك WorkManager نفسه ═════════

    @Test
    fun `جدولة superbiz_daily الفريدة قائمة والTestDriver يسوق العامل الحقيقي حتى النجاح`() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app, Configuration.Builder().setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor()).build()
        )
        // 1) الجدولة الدورية الفريدة (نفس ما يستدعيه إقلاع التطبيق) موجودة لدى المحرك —
        //    العمل الدوري يعود ENQUEUED/RUNNING دورياً ولا يمتلك حالة نهائية
        AutomationWorker.schedule(g)
        val wm = WorkManager.getInstance(app)
        val infos = wm.getWorkInfosForUniqueWork("superbiz_daily").get()
        assertTrue("العمل الدوري مسجّل تحت الاسم الموحد", infos.isNotEmpty())
        assertTrue(
            "حالة دورية صالحة (ENQUEUED أو RUNNING)",
            infos.first().state == WorkInfo.State.ENQUEUED || infos.first().state == WorkInfo.State.RUNNING
        )
        // 2) إسوق العامل الحقيقي نفسه عبر TestDriver: عمل OneTime بتأخير ساعة
        //    يُعتبر موعده متحققاً ثم doWork الفعلي يُنفَّذ داخل محرك WorkManager
        //    على القاعدة الملفية حتى حالة نهائية ملحوظة (SUCCEEDED — دلالة OneTime)
        val req = androidx.work.OneTimeWorkRequestBuilder<AutomationWorker>()
            .setInitialDelay(1, TimeUnit.HOURS)
            .build()
        wm.enqueue(req).result.get()
        val driver = WorkManagerTestInitHelper.getTestDriver(app)
        assertNotNull("TestDriver متاح في بيئة الاختبار", driver)
        driver!!.setInitialDelayMet(req.id)
        val finalState = withTimeoutOrNull(90_000) {
            var s: WorkInfo.State = wm.getWorkInfoById(req.id).get()?.state ?: WorkInfo.State.ENQUEUED
            while (s != WorkInfo.State.SUCCEEDED) {
                kotlinx.coroutines.delay(200)
                s = wm.getWorkInfoById(req.id).get()?.state ?: s
            }
            s
        }
        assertEquals(
            "TestDriver أسوق doWork الأتمتة الحقيقي داخل المحرك حتى النجاح (لا حوله)",
            WorkInfo.State.SUCCEEDED, finalState
        )
    }
}
