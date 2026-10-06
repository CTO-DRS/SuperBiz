package com.superbiz.app.work

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.superbiz.app.AppGraph
import com.superbiz.app.R
import com.superbiz.app.SuperBizApp
import java.io.File
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** منطق الجدولة الخالص — حتمي وقابل للاختبار وحداتياً بلا أي اعتماد على Android */
object ReportScheduleLogic {

    /** الفاصل السماحي: 5 دقائق قبل دورة الاكتمال الكاملة (يمنع الازدواج عند إطلاق النظام المبكر) */
    private const val GRACE_MS = 5L * 60_000L

    /**
     * كم ملّي ثانية حتى أقرب موعد «الساعة hour:00» بتوقيت الجهاز؟
     * إذا كان الموعد قد مضى اليوم فالموعد التالي غداً (آمن مع التوقيت الصيفي عبر Calendar).
     */
    fun nextDelayMillis(now: Long, hour: Int, tz: TimeZone = TimeZone.getDefault()): Long {
        val cal = Calendar.getInstance(tz).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis - now
    }

    /** هل حان موعد التقرير المجدول؟ (لم يُشغَّل قط، أو مضت المدة كاملة) */
    fun isDue(now: Long, last: Long, days: Int): Boolean {
        if (days <= 0) return false
        if (last <= 0) return true
        return now - last >= days * 86_400_000L - GRACE_MS
    }

    /** مدة الدورة الدورية بالأيام (WorkManager يقبل 1 يوم فأكثر هنا) */
    fun periodDays(days: Int): Long = days.coerceIn(1, 365).toLong()
}

/**
 * [P6-M41 إصلاح] سياسة احتفاظ ملفات PDF في files/pdfs — كانت تتراكم بلا أي حذف.
 *
 * القاعدة عند كل توليد: يُحذف ما أقدم من 30 يوماً، ثم يُبقى من غير المنتهي أحدث
 * KEEP_NEWEST ملفاً (20) وما بعدها الأقدم يُحذف — فلا يتجاوز المخزن 20 ملفاً حديثاً.
 *
 * نقطة الاستدعاء الحالية: buildFinancialReport (نقطة التوليد المشتركة للتقارير
 * المجدولة وزر «تجهيز وإرسال الآن»). ملاحظة صراحةً: نقاط كتابة PDF الأخرى
 * (InvoicePdf لكل فاتورة، DocPdf.renderReport/renderStatement، بقية A4Report)
 * تقع في ملفات غير مملوكة لهذه الموجة فلم تُربط بها السياسة — الدالة هنا
 * قابلة لإعادة الاستخدام من تلك النقاط لاحقاً بلا أي تغيير.
 */
object PdfRetention {

    const val KEEP_NEWEST = 20
    const val MAX_AGE_MS = 30L * 86_400_000L

    /** نقية وقابلة للاختبار: ترجع قائمة الملفات المرشَّحة للحذف (ملفات pdf فقط) */
    fun candidatesToDelete(
        files: List<File>,
        now: Long,
        keepNewest: Int = KEEP_NEWEST,
        maxAgeMs: Long = MAX_AGE_MS
    ): List<File> {
        val pdfs = files.filter { it.isFile && it.name.endsWith(".pdf", ignoreCase = true) }
        // 1) المنتهي عمراً (أقدم من maxAgeMs) يُحذف أولاً أياً كان العدد
        val expired = pdfs.filter { now - it.lastModified() > maxAgeMs }
        // 2) من غير المنتهي: الأحدث keepNewest تبقى وما فوقها من الأقدم يُحذف
        val surplus = pdfs.filter { now - it.lastModified() <= maxAgeMs }
            .sortedByDescending { it.lastModified() }
            .drop(keepNewest.coerceAtLeast(0))
        return expired + surplus
    }

    /** تنفيذ الحذف على مجلد files/pdfs الفعلي — يتجاهل أخطاء الملفات الفردية ويُرجع عدد المحذوف */
    fun prune(context: Context, now: Long = System.currentTimeMillis()): Int {
        return try {
            val dir = File(context.filesDir, "pdfs")
            var deleted = 0
            for (f in candidatesToDelete(dir.listFiles()?.toList() ?: emptyList(), now)) {
                if (f.delete()) deleted++
            }
            deleted
        } catch (_: Exception) {
            0
        }
    }
}

/**
 * — منطق المستلم الافتراضي (بريد العميل/المالك المحفوظ) — حتمي وقابل للاختبار وحداتياً
 *
 * • normalize: تنقية وتحقق من صيغة البريد (محارف مسموحة + نطاق بنقطة) — null إن كان غير صالح
 * • pick: البريد المخصص للتقارير أولاً، وإن لم يُضبط يسقط إلى البريد المحفوظ في الملف الشخصي،
 * و"" إن لم يوجد بريد صالح (يبقى الإرسال اليدوي الحر بلا عنوان)
*/
object EmailPolicy {

    private val RX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    fun normalize(raw: String): String? {
        val v = raw.trim().lowercase()
        if (v.length < 6 || v.length > 254 || v.contains(" ")) return null
        return if (RX.matches(v)) v else null
    }

    fun pick(primary: String, fallback: String): String =
        normalize(primary) ?: normalize(fallback) ?: ""
}

/**
 * — مجدول إرسال التقرير A4 تلقائياً (واتساب/بريد)
 *
 * • يعمل دورياً (يومي/أسبوعي) في الساعة التي اختارها المستخدم بتوقيت جهازه
 * • يجهّز التقرير المالي A4 فعلياً (نفس مسار شاشة التقارير) من القيد المزدوج — بلا بيانات وهمية
 * • الإرسال الفعلي يتطلب تفاعل المستخدم (سياسة أندرويد للواتساب/البريد) — لذلك يصلك إشعار
 * بزرَّي «إرسال عبر واتساب» و«إرسال بالبريد» يفتحان المشاركة مع الملف مرفقاً عبر FileProvider
 * • إذا لم يكن واتساب مثبتاً يفتح محدد المشاركة العام (أي منصة: تلغرام، بريد، حفظ…) — لا أزرار ميتة
*/
class ReportScheduleWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        return try {
            val app = applicationContext
            val graph = AppGraph.from(app)
            val s = graph.settings.snapshot()
            if (s.reportScheduleDays <= 0) return Result.success()
            val force = inputData.getBoolean(KEY_FORCE, false)
            if (!force && !ReportScheduleLogic.isDue(System.currentTimeMillis(), s.lastScheduledReport, s.reportScheduleDays)) {
                return Result.success()
            }
            // [P6-M39 إصلاح]: بوابة صلاحية الإشعارات قبل التوليد — كانت حلقة بلا نهاية عند
            // رفض الإشعارات: يُولَّد الـPDF كاملاً (ميزانية الدخل + كبار الزبائن/الأصناف +
            // أعمار الديون + ميزان المراجعة) ثم يُرفض الإشعار فيلا يُختم lastScheduledReport
            // وتُعاد الدورة بتوليد PDF كامل جديد بلا نهاية بلا أن يراه أحد. الآن:
            // إن رُفضت الصلاحية لا توليد ولا ختم — فقط انتظار الدورة التالية (نمط H-14/H-20)،
            // وإن مُنحت يُولَّد ويُختم الزمن عند النشر الفعلي أدناه.
            // (زر «تجهيز وإرسال الآن» في الواجهة يستدعي buildFinancialReport مباشرة ولا يمر من هنا.)
            if (!notificationsEnabled(app)) {
                // [P31-A]: تقني إنجليزي للسجل + رسالة مستخدم موطّنة للـSnackbar
                com.superbiz.app.core.ErrorCenter.warn(
                    "ReportSchedule",
                    "notification permission denied — skipping scheduled report generation (no PDF, no stamp)",
                    app.getString(com.superbiz.app.R.string.err_report_needs_notifications)
                )
                return Result.success()
            }
            val file = buildFinancialReport(app, graph, s.reportScheduleDays)
            // المستلم الافتراضي — المخصص للتقارير أو البريد المحفوظ في الملف الشخصي
            val recipient = EmailPolicy.pick(s.reportRecipient, s.email)
            // H-20: لا ختم «تم التسليم» إلا إذا وصل الإشعار فعلاً —
            // كانت الختم تتم قبل الإشعار فمع رفض صلاحية الإشعارات (API 33+) تُنتج
            // دورات تقارير لا يراها أحد والنظام يدّعي أنها سُلّمت. الآن بلا إشعار
            // لا يُختم الزمن، فيُعاد التجهيز في الدورة التالية بدل ضياع التقرير بصمت.
            val surfaced = notifyReportReady(app, file, s.reportScheduleChannel, recipient)
            if (surfaced) graph.settings.setLastScheduledReport(System.currentTimeMillis())
            else com.superbiz.app.core.ErrorCenter.warn(
                "ReportSchedule",
                "couldn't surface scheduled report (notification permission denied) — will retry next cycle",
                app.getString(com.superbiz.app.R.string.err_report_not_surfaced)
            )
            Result.success()
        } catch (e: Exception) {
            // تسجيل الفشل قبل إعادة المحاولة — كان يبتلع السبب فيصعب
            // تشخيص «لماذا لا يصل التقرير المجدول؟»
            com.superbiz.app.core.ErrorCenter.warn(
                "ReportSchedule", "scheduled report failed: ${e::class.simpleName}: ${e.message}"
            )
            Result.retry()
        }
    }

    companion object {
        const val KEY_FORCE = "force"
        private const val WORK_NAME = "superbiz_report_schedule"

        /**
         * بناء التقرير المالي A4 فعلياً — نفس بيانات ومسار شاشة التقارير (آخر N يوماً).
         * يُستخدم في المجدول وزر «تجهيز وإرسال الآن» معاً لضمان تقرير واحد متطابق.
         */
        suspend fun buildFinancialReport(context: Context, graph: AppGraph, periodDays: Int): File {
            val to = System.currentTimeMillis()
            val from = to - periodDays.coerceIn(1, 365).toLong() * 86_400_000L
            val inc = graph.reports.incomeStatement(from, to)
            val cash = graph.reports.cashBalance()
            val business = graph.settings.snapshot().businessName
                .ifBlank { context.getString(R.string.business_default) }
            val symbol = try {
                graph.db.currencies().allOnce().firstOrNull { it.isBase }?.symbol ?: "ر.س"
            } catch (_: Exception) { "ر.س" }
            val avatar = try {
                graph.settings.snapshot().avatarPath?.let { BitmapFactory.decodeFile(it) }
            } catch (_: Exception) { null }
            val periodText = context.getString(R.string.period_days, periodDays.coerceIn(1, 365))
            // [P33-P8] مبالغ التقرير قروش Long الآن (IncomeStatement.revenue/otherIncome/
            // cogs/expenses وcashBalance من المحرك/المستودع المُرحَّلَين) — تُمرَّر كما هي
            // (Long) إلى A4Report.financial بصيغتها القروشية، والتنسيق عبر Money.formatP
            // داخل مولّد الـPDF (ترحيل موجة P8 لملفات pdf/).
            val file = com.superbiz.app.pdf.A4Report.financial(
                context, business, symbol, avatar,
                periodText = periodText,
                revenue = inc.revenue, otherIncome = inc.otherIncome,
                cogs = inc.cogs, expenses = inc.expenses,
                cash = cash,
                topCustomers = graph.reports.topCustomers(from, to, 500),
                topProducts = graph.reports.topProducts(from, to, 500),
                aging = graph.reports.agingBuckets(),
                trial = graph.reports.trialBalance()
            )
            // [P6-M41 إصلاح]: سياسة الاحتفاظ تُطبَّق عند كل توليد — files/pdfs كانت تتراكم
            // بلا حذف. أبقِ الأحدث 20 ملفاً واحذف ما أقدم من 30 يوماً (التفاصيل في PdfRetention).
            try { PdfRetention.prune(context) } catch (_: Exception) { }
            return file
        }

        /** نية المشاركة مع مرفق الـPDF حسب القناة — مع سقوط آمن لمحدد المشاركة العام.
 * : recipient يُعبّأ حقل «إلى» في البريد تلقائياً إن وُجد مستلم محفوظ */
        fun shareIntent(context: Context, file: File, channel: String, recipient: String = ""): Intent {
            val uri = FileProvider.getUriForFile(context, "com.superbiz.app.fileprovider", file)
            val base = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.a4_financial_title))
                putExtra(Intent.EXTRA_TITLE, context.getString(R.string.a4_financial_title))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                clipData = android.content.ClipData.newRawUri(context.getString(R.string.a4_financial_title), uri)
            }
            return if (channel == "email") {
                // البريد: Gmail/Outlook وأي تطبيق بريد يقرأ ACTION_SEND مع المرفق —
                // والمستلم الافتراضي يُعبّأ تلقائياً () بدل الحقول الفارغة
                base.apply {
                    putExtra(Intent.EXTRA_EMAIL, if (recipient.isBlank()) arrayOf<String>() else arrayOf(recipient))
                }
            } else {
                // واتساب إن وُجد — وإلا محدد المشاركة العام (أي منصة) — لا فشل ولا زر ميت
                val hasWa = try {
                    context.packageManager.getPackageInfo("com.whatsapp", 0) != null
                } catch (_: Exception) { false }
                if (hasWa) base.apply { setPackage("com.whatsapp") } else base
            }
        }

        /** [P6-M39 إصلاح] هل يمكن نشر إشعار؟ البوابة نفسها التي يفحصها notifyReportReady —
         *  تُستدعى قبل التوليد لكسر حلقة PDF عند رفض الإشعارات */
        // [P20-FIX agent14]: areNotificationsEnabled يغطي الإغلاق من إعدادات النظام قبل 33
        fun notificationsEnabled(context: Context): Boolean =
            androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()

        /** إشعار «تقريرك جاهز» بمرفق جاهز للإرسال + زرّا واتساب/بريد (بريد مُعبّأ بالمستلم ) */
        /** H-20: ترجع true فقط إذا نُشر الإشعار فعلاً */
        fun notifyReportReady(context: Context, file: File, channel: String, recipient: String = ""): Boolean {
            // [P6-M39 إصلاح]: الفحص المبكر صار عبر المساعد المشترك notificationsEnabled
            if (!notificationsEnabled(context)) return false
            val viaName = context.getString(
                if (channel == "email") R.string.sched_email else R.string.sched_whatsapp
            )
            val content = Intent(context, com.superbiz.app.MainActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(context, "com.superbiz.app.fileprovider", file))
                if (channel == "email" && recipient.isNotBlank()) {
                    // نقرة المحتوى مع القناة البريدية تُعبّئ المستلم المحفوظ أيضاً
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                clipData = android.content.ClipData.newRawUri(
                    context.getString(R.string.a4_financial_title),
                    FileProvider.getUriForFile(context, "com.superbiz.app.fileprovider", file)
                )
                if (channel != "email") {
                    val hasWa = try {
                        context.packageManager.getPackageInfo("com.whatsapp", 0) != null
                    } catch (_: Exception) { false }
                    if (hasWa) setPackage("com.whatsapp")
                }
            }
            val contentPi = PendingIntent.getActivity(
                context, 5001, content,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val waPi = PendingIntent.getActivity(
                context, 5002, shareIntent(context, file, "whatsapp"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val mailPi = PendingIntent.getActivity(
                context, 5003, shareIntent(context, file, "email", recipient),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // [P30-B]: قناة التقارير المتخصصة — كتم التقارير لا يكتم تنبيهات السداد
            val n = NotificationCompat.Builder(context, SuperBizApp.CHANNEL_REPORTS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(context.getString(R.string.notif_ready_title))
                .setContentText(context.getString(R.string.notif_ready_body, viaName))
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.notif_ready_body, viaName)))
                .setContentIntent(contentPi)
                .addAction(0, context.getString(R.string.act_send_wa), waPi)
                .addAction(0, context.getString(R.string.act_send_mail), mailPi)
                .setAutoCancel(true)
                .build()
            return try {
                NotificationManagerCompat.from(context).notify(5000, n)
                true
            } catch (e: SecurityException) {
                // H-20: رفض الأمان = لم يُسلَّم
                false
            }
        }

        /**
         * جدولة/إلغاء العمل الدوري — يُستدعى من الإعدادات وعند إعادة التشغيل
         *
         * [P6-M38 إصلاح]: كانت السياسة UPDATE تحافظ على إيقاع الدورة الأصلية (زمن الإنشاء)
         * ولا تطبّق initialDelay الجديد — فتغيير «وقت التجهيز» كان شكلياً بعد أول تشغيل
         * ويستمر العمل بالدوران على ساعة القِدم. الآن:
         * • initialDelay يُحسب دائماً (دالة نقية مختبرة ReportScheduleLogic.nextDelayMillis)
         *   من الساعة المختارة حتى أقرب موعد قادم — المرساة تصبح ساعة الحائط لا زمن الإنشاء.
         * • سياسة REPLACE تُطبّق التأخير الجديد فعلاً، مع بقاء الدورية N يوماً كما هي
         *   (الحفاظ على الدورة = طول الفترة، لا إيقاع الإنشاء القديم).
         * • توقع الانحراف: WorkManager يطبّق jitter على التشغيل الدوري (انحراف بدقائق) وتتأثر
         *   الدقة بـ Doze/تحسينات البطارية — الموعد الفعلي قد ينحرف عن :00 بدقائق وهذا متوقع.
         * • أثر REPLACE الجانبي المقبول: يلغي تشغيلاً جارياً إن وُجد ويعيد backoff — مقبول
         *   لأن الاستدعاء يحدث عند الإقلاع/تغيير الإعداد فقط، وإعادة التثبيت على ساعة الحائط
         *   تحمي من الانزلاق التراكمي بدل أن تزيده.
         */
        fun schedule(context: Context, days: Int, hour: Int) {
            val wm = WorkManager.getInstance(context)
            if (days <= 0) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val delay = ReportScheduleLogic.nextDelayMillis(System.currentTimeMillis(), hour)
            wm.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                PeriodicWorkRequestBuilder<ReportScheduleWorker>(ReportScheduleLogic.periodDays(days), TimeUnit.DAYS)
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .build()
            )
        }
    }
}
