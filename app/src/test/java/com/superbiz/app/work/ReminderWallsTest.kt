package com.superbiz.app.work

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.R
import com.superbiz.app.SuperBizApp
import com.superbiz.app.domain.DebtPlan
import com.superbiz.app.util.Money
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * [P37-T-B] جدران اختبار طبقة work/ — التذكيرات (ExactAlarms / PaymentPlanReminders /
 * AutomationLogic / VisitReminder) — JUnit4 على Robolectric بنمط TransactionPathsTest
 * (تطبيق مجرد android.app.Application — لا SuperBizApp ولا AppGraph).
 *
 * التغطية (الجزء النقي/القابل للاختبار فقط — لا PendingIntent يُمرَّر من الاختبار):
 * • ExactAlarms.requestCode: الحتمية والثبات لنفس (partyId,seq)، عدم تصادم المجموعات
 *   المختلفة داخل نافذة 100 ألف، القيد 0..999 للقسط، والالتفاف الموثق عند 100 ألف.
 *   (ترميز دفتر JournalCodec مغطى حصراً في ExactAlarmPolicyTest — لا تكرار هنا).
 * • PaymentPlanReminders.tagFor: صيغة الوسم وتمايز الأطراف.
 * • AutomationLogic: ثابت BACKUP_FAIL_NOTIF_ID بعيداً عن كل النطاقات المسجلة،
 *   عقد صدق التسليم في notify (صلاحية ممنوحة/مرفوضة/إشعارات معطلة نظامياً —
 *   إصلاحا P5-H14 وP20-FIX agent14)، وتركيب نصوص الإشعارات من بيانات عبر نفس
 *   الموارد وصيغ Money التي يستدعيها AutomationLogic.run فعلاً.
 * • VisitReminder: الجوانب غير المغطاة في OverdueReminderPolicyTest — الثوابت
 *   المتمايزة، الافتراضات، قيود setTime/setThreshold، ودوران setEnabled
 *   (رياضيات nextTriggerAt/shouldNotify نفسها مغطاة هناك — لا تكرار).
 *
 * حتمية: المنطقة الزمنية مثبتة UTC في Before وتستعاد في After (نمط
 * OverdueReminderPolicyTest) لأن صيغة تاريخ الشيك في الإنتاج تستخدم المنطقة
 * الافتراضية، وكل الأزمنة المستخدمة ثوابت صريحة.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ReminderWallsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private val originalTz: TimeZone = TimeZone.getDefault()

    @Before
    fun pinTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTz)
    }

    // ═════════ ExactAlarms.requestCode — رموز طلب التنبيهات الدقيقة ═════════

    @Test
    fun `requestCode حتمي وثابت لنفس زوج طرف وقسط`() {
        // الصيغة الموثقة: (partyId % 100_000) * 1_000 + seq المقيَّد — نفس المدخلات
        // تعطي نفس الرمز مهما تكرر النداء (شرط إلغاء/تحديث PendingIntent بشكل صحيح)
        assertEquals(ExactAlarms.requestCode(42L, 3), ExactAlarms.requestCode(42L, 3))
        assertEquals(ExactAlarms.requestCode(42L, 3), ExactAlarms.requestCode(42L, 3))
        // قيم معروفة من الصيغة نفسها
        assertEquals(42_003, ExactAlarms.requestCode(42L, 3))
        assertEquals(0, ExactAlarms.requestCode(0L, 0))
    }

    @Test
    fun `requestCode لا تصادم بين مجموعات مختلفة داخل نافذة 100 ألف`() {
        // شبكة كاملة 30 طرفاً × 15 قسطاً = 450 رمزاً مختلفاً كلها (seq < 1000 دائماً
        // ⇒ الرمز دالة تجميع حقنة داخل نافذة الطرف) + نفس القسط عبر 100 طرفاً آخرين
        val codes = HashSet<Int>()
        for (pid in 0L..29L) for (seq in 0..14) {
            assertTrue("تصادم عند pid=$pid seq=$seq", codes.add(ExactAlarms.requestCode(pid, seq)))
        }
        for (pid in 100L..199L) {
            assertTrue("تصادم seq=3 عند pid=$pid", codes.add(ExactAlarms.requestCode(pid, 3)))
        }
        assertEquals(450 + 100, codes.size)
    }

    @Test
    fun `requestCode يلتف عند 100 ألف تصادم موثق نادر وال WorkManager صافي الأمان`() {
        // الالتفاف المعياري سلوك موثق في KDoc الكائن: التطابق يتطلب تطابق باقي
        // القسمة والقسط معاً (نادر) — حينها يبقى مسار WorkManager هو صاحب الصلاحية
        assertEquals(ExactAlarms.requestCode(7L, 5), ExactAlarms.requestCode(100_007L, 5))
        assertEquals(ExactAlarms.requestCode(0L, 0), ExactAlarms.requestCode(1_900_000L, 0))
    }

    @Test
    fun `requestCode يقيد القسط إلى 0 إلى 999`() {
        // فوق السقف والسالب كلاهما يُقيَّدان — نفس رمز الحدّ المقابل
        assertEquals(ExactAlarms.requestCode(5L, 999), ExactAlarms.requestCode(5L, 1500))
        assertEquals(5_999, ExactAlarms.requestCode(5L, 1500))
        assertEquals(ExactAlarms.requestCode(5L, 0), ExactAlarms.requestCode(5L, -5))
        assertEquals(5_000, ExactAlarms.requestCode(5L, -5))
        assertEquals(ExactAlarms.requestCode(5L, 999), ExactAlarms.requestCode(5L, 999))
    }

    @Test
    fun `requestCode يبقى داخل النطاق الموجب القصوي لأي طرف ضخم`() {
        // الحد الأقصى النظري: 99_999 * 1_000 + 999 = 99_999_999 < Int.MAX_VALUE
        val parties = listOf(0L, 1L, 99_999L, 100_000L, 123_456_789L, 9_000_000_000L, Long.MAX_VALUE)
        for (pid in parties) for (seq in intArrayOf(0, 500, 999)) {
            val code = ExactAlarms.requestCode(pid, seq)
            assertTrue("pid=$pid seq=$seq خارج النطاق: $code", code in 0..99_999_999)
        }
    }

    @Test
    fun `requestCode بطرف سالب يعطي رمزا سالبا ثغرة موثقة للأطراف الموجبة فقط`() {
        // [ملاحظة صادقة]: باقي القسمة في Kotlin يحفظ الإشارة — طرف سالب ينتج رمزاً
        // سالباً. عملياً partyId يأتي من Room autoGenerate (موجب دائماً) فالثغرة
        // نظرية؛ تُوثَّق هنا كي لا يفاجأ أحد لو مُرِّر معرف خارجي سالب.
        assertEquals(-5_000, ExactAlarms.requestCode(-5L, 0))
    }

    // ═════════ PaymentPlanReminders.tagFor — وسم أعمال خطة السداد ═════════

    @Test
    fun `tagFor بصيغة payplan ويتمايز بين الأطراف`() {
        assertEquals("payplan-42", PaymentPlanReminders.tagFor(42L))
        assertEquals("payplan-0", PaymentPlanReminders.tagFor(0L))
        assertNotEquals(PaymentPlanReminders.tagFor(42L), PaymentPlanReminders.tagFor(43L))
        // طرفان مختلفان لا يشتركان وسماً مهما تقاربا (لا اقتطاع ولا تقريب في الصيغة)
        assertNotEquals(PaymentPlanReminders.tagFor(1L), PaymentPlanReminders.tagFor(12L))
    }

    @Test
    fun `tagFor ثابت عبر الاستدعاءات وللقيم الحدية`() {
        // الثبات شرط عمل cancelAllWorkByTag: نفس الطرف ⇒ نفس الوسم دائماً
        assertEquals(PaymentPlanReminders.tagFor(Long.MAX_VALUE), PaymentPlanReminders.tagFor(Long.MAX_VALUE))
        assertEquals("payplan-${Long.MAX_VALUE}", PaymentPlanReminders.tagFor(Long.MAX_VALUE))
        // القيم الحدية تُضمَّن نصياً حرفياً (سلوك الاستيفاء الموثق)
        assertEquals("payplan--1", PaymentPlanReminders.tagFor(-1L))
    }

    // ═════════ AutomationLogic — الثوابت وعقد صدق التسليم ═════════

    @Test
    fun `BACKUP_FAIL_NOTIF_ID ثابت بعيد عن كل النطاقات المسجلة`() {
        // [P6-M36]: 600k بعيد عن نطاقات الذمم/الشيكات/المخزون/الأقساط (100k-400k)
        // وعن 5000 (التقرير المجدول) — وأسفل نطاق خطط السداد (فوق 10 ملايين R14-F5)
        assertEquals(600_000, AutomationLogic.BACKUP_FAIL_NOTIF_ID)
        assertTrue(AutomationLogic.BACKUP_FAIL_NOTIF_ID in 500_000..999_999)
        assertTrue(AutomationLogic.BACKUP_FAIL_NOTIF_ID < 10_000_000)
        // لا تصادم مع ثوابت تذكير الزيارات الثلاثة
        assertNotEquals(VisitReminder.NOTIF_ID, AutomationLogic.BACKUP_FAIL_NOTIF_ID)
        assertNotEquals(VisitReminder.REQUEST_CODE, AutomationLogic.BACKUP_FAIL_NOTIF_ID)
        assertNotEquals(VisitReminder.CONTENT_REQUEST_CODE, AutomationLogic.BACKUP_FAIL_NOTIF_ID)
        // [ملاحظة مخاطر]: نطاقات 100k/200k/400k تُبنى من معرفات صفوف غير مقيدة —
        // طرف بمعرف > 500k أو قسط بمعرف >= 200k نظرياً يصل إلى نطاق 600k (انظر التقرير)
    }

    @Test
    fun `notify بصلاحية ممنوحة ينشر ويعيد صحيح`() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val delivered = AutomationLogic.notify(app, 47_001, "عنوان تجريبي", "متن تجريبي")
        assertTrue("التسليم الناجح يجب أن يعيد true — عقد P5-H14", delivered)
    }

    @Test
    fun `notify بصلاحية مرفوضة يعيد كذب بصدق عقد P5-H14`() {
        // الإصلاح التاريخي: الرفض كان يبتلع بصمت ويُختم lastRun — الآن النتيجة صادقة
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val delivered = AutomationLogic.notify(app, 47_002, "عنوان", "متن")
        assertFalse("رفض الإذن يجب أن يعيد false", delivered)
    }

    @Test
    fun `notify مع ايقاف الإشعارات من النظام يعيد كذب بوابة P20 الموحدة`() {
        // إغلاق الإشعارات من إعدادات النظام (حتى مع منح الصلاحية) يجب أن يمنع
        // ختم lastRun/التقرير المجدول — البوابة الموحدة [P20-FIX agent14]
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        Shadows.shadowOf(nm).setNotificationsEnabled(false)
        assertFalse(AutomationLogic.notify(app, 47_003, "عنوان", "متن"))
    }

    @Test
    fun `notify مع bigText وقناة متخصصة يعمل ويعيد صحيح`() {
        // النواة الموسعة [P15-c]/[P30-B] — نفس مسار VisitReminder ومرسلي النسخ الاحتياطي
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val delivered = AutomationLogic.notify(
            app, 47_004, "عنوان", "متن مختصر",
            bigText = "سطر أول\nسطر ثانٍ", channel = SuperBizApp.CHANNEL_BACKUP
        )
        assertTrue(delivered)
    }

    // ═══AutomationLogic.run — تركيب نصوص الإشعار من بيانات (نفس موارد الإنتاج) ═══

    @Test
    fun `تركيب نص تذكير الذمم من البيانات باسم ومبلغ وحالة متاخر`() {
        // نفس تركيب AutomationLogic.run للقاعدة DUE_REMIND: الاسم + formatP + وسم الحالة.
        // 125_000 قروش = 1,250.00 ر.س (نقطة التحويل الموحدة P33-P8)
        // عقد التركيب يُدقق على الوسائط نفسها (محايد اللغة): الموارد قد تُحلّ عربياً أو
        // إنجليزياً بحسب إعدادات Robolectric — المهم أن كل موضع يُعبّأ بقيمته حرفياً.
        val symbol = "ر.س" // القيمة الاحتياطية نفسها في run عند غياب عملة أساسية
        val money = Money.formatP(125_000L, symbol)
        val tag = app.getString(R.string.notif_due_overdue) // أي فاتورة dueDate < now
        val msg = app.getString(R.string.notif_due_remind, "عميل خالد", money, tag)
        assertTrue(msg.contains("عميل خالد"))
        assertTrue(msg.contains("1,250.00"))
        assertTrue(msg.contains(money))
        assertTrue(msg.contains(tag))
        assertFalse("لا بقي لموضع تنسيق غير معبأ", msg.contains("%"))
        // وسم الحالة الآخر «قرب الاستحقاق» موجود ومتاح لنفس الصيغة
        assertTrue(app.getString(R.string.notif_due_soon).isNotEmpty())
    }

    @Test
    fun `تركيب نص الشيك من البيانات برقم ومبلغ وطرف وتاريخ استحقاق`() {
        // نفس تركيب AutomationLogic.run للقاعدة CHECK_REMIND — الصيغة dd/MM بالمنطقة
        // الافتراضية المثبتة UTC في Before فصار الحساب حتمياً — والتدقيق على الوسائط
        // نفسها (محايد اللغة) لا على النص المترجم للصيغة.
        val dueDate = 1_790_244_000_000L // 2026-09-24 10:00:00 UTC (حتمي بالمنطقة المثبتة)
        val dateTxt = SimpleDateFormat("dd/MM", Locale.US).format(Date(dueDate))
        val money = Money.formatP(50_500L, "ر.س") // 505.00 ر.س
        val msg = app.getString(R.string.notif_check_msg, "CH-12", money, "سارة", dateTxt)
        assertTrue(msg.contains("CH-12"))
        assertTrue(msg.contains("505.00"))
        assertTrue(msg.contains(money))
        assertTrue(msg.contains("سارة"))
        assertTrue("التاريخ بصيغة dd/MM", msg.contains(dateTxt))
        assertEquals("24/09", dateTxt)
        assertFalse(msg.contains("%"))
    }

    @Test
    fun `تركيب نص القسط من البيانات برقم الخطة والمبلغ والحالة`() {
        // نفس تركيب AutomationLogic.run للقاعدة INSTALLMENT_REMIND — المفتوح قروش
        // والتدقيق على الوسائط نفسها (محايد اللغة)
        val money = Money.formatP(99_999L, "ر.س") // 999.99 ر.س
        val tag = app.getString(R.string.notif_due_overdue) // حالة LATE
        val msg = app.getString(R.string.notif_installment_msg, 3, 12, "خطة تجريبية", money, tag)
        assertTrue(msg.contains("خطة تجريبية"))
        assertTrue(msg.contains("999.99"))
        assertTrue(msg.contains(money))
        assertTrue(msg.contains(tag))
        assertFalse(msg.contains("%"))
    }

    @Test
    fun `تركيب نص خطة السداد من البيانات برقم الدفعة والمجموع والمبلغ`() {
        // نفس تركيب PaymentPlanReminders.schedule: العنوان باسم الطرف والمتن
        // بseq/الإجمالي/المبلغ عبر DebtPlan.riyals ثم Money.num — والتدقيق على
        // الوسائط نفسها (محايد اللغة)
        val title = app.getString(R.string.debt_plan_notif_title, "مؤسسة النور")
        val amount = Money.num(DebtPlan.riyals(75_000L)) // 75_000 هللة = 750 ريال → "750"
        val body = app.getString(R.string.debt_plan_notif_body, 2, 5, amount)
        assertTrue(title.contains("مؤسسة النور"))
        assertTrue(body.contains(amount))
        assertEquals("750", amount)
        // عقد المواضع: seq ثم العدد الكلي حول المبلغ بالصيغة نفسها مهما كانت اللغة
        assertTrue(Regex("\\b2\\b").containsMatchIn(body))
        assertTrue(Regex("\\b5\\b").containsMatchIn(body))
        assertFalse(body.contains("%"))
    }

    // ═════════ VisitReminder — التفضيلات والثوابت (فوق ما يغطيه OverdueReminderPolicyTest) ═════════

    @Test
    fun `ثوابت تذكير الزيارات متمايزة تماما بلا تصادم`() {
        // [P15-c]: رمز المنبّه ≠ معرف الإشعار ≠ رمز هدف النقرة — لا استبدال متبادل
        assertEquals(4711, VisitReminder.REQUEST_CODE)
        assertEquals(4712, VisitReminder.NOTIF_ID)
        assertEquals(4713, VisitReminder.CONTENT_REQUEST_CODE)
        assertNotEquals(VisitReminder.REQUEST_CODE, VisitReminder.NOTIF_ID)
        assertNotEquals(VisitReminder.REQUEST_CODE, VisitReminder.CONTENT_REQUEST_CODE)
        assertNotEquals(VisitReminder.NOTIF_ID, VisitReminder.CONTENT_REQUEST_CODE)
        assertEquals(14, VisitReminder.DEFAULT_THRESHOLD_DAYS)
    }

    @Test
    fun `افتراضات تذكير الزيارات معطل والساعة 8 والعتبة 14`() {
        // الافتراضات من prefs الفارغة: معطل، 08:00 (OverdueReminderPolicy)، عتبة 14 يوماً
        assertFalse(VisitReminder.enabled(app))
        assertEquals(8, VisitReminder.hour(app))
        assertEquals(0, VisitReminder.minute(app))
        assertEquals(14, VisitReminder.threshold(app))
    }

    @Test
    fun `ضبط وقت الزيارات يقيد الساعة 0-23 والدقيقة 0-59`() {
        VisitReminder.setTime(app, 25, 70)
        assertEquals(23, VisitReminder.hour(app))
        assertEquals(59, VisitReminder.minute(app))
        VisitReminder.setTime(app, -3, -7)
        assertEquals(0, VisitReminder.hour(app))
        assertEquals(0, VisitReminder.minute(app))
        VisitReminder.setTime(app, 9, 5)
        assertEquals(9, VisitReminder.hour(app))
        assertEquals(5, VisitReminder.minute(app))
    }

    @Test
    fun `ضبط عتبة الزيارات يقيد بين 1 و365`() {
        VisitReminder.setThreshold(app, 0)
        assertEquals(1, VisitReminder.threshold(app))
        VisitReminder.setThreshold(app, 10_000)
        assertEquals(365, VisitReminder.threshold(app))
        VisitReminder.setThreshold(app, 21)
        assertEquals(21, VisitReminder.threshold(app))
    }

    @Test
    fun `تفعيل وتعطيل تذكير الزيارات يخزن الحالة ويدور ذهابا وايابا`() {
        // التفعيل يسلسل تسليح المنبّه ثم يُخزن العلم، والتعطيل يعكس الحالة
        // (فشل AlarmManager هنا مُلتهم بالتصميم — الحالة المخزنة هي العقد المرئي)
        VisitReminder.setEnabled(app, true)
        assertTrue(VisitReminder.enabled(app))
        VisitReminder.setEnabled(app, false)
        assertFalse(VisitReminder.enabled(app))
    }
}
