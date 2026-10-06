package com.superbiz.app.work

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.superbiz.app.AppGraph
import com.superbiz.app.R
import com.superbiz.app.SuperBizApp
import com.superbiz.app.data.repo.SettingsRepo
import com.superbiz.app.util.Money
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** منطق الأتمتة المشترك — حتمي بالكامل */
object AutomationLogic {

    // [P6-M36 إصلاح]: معرّف إشعار فشل النسخ التلقائي — بعيد عن نطاقات 100k/200k/300k/400k
    // (ذمم/شيكات/مخزون/أقساط) وعن 5000 (التقرير المجدول).
    const val BACKUP_FAIL_NOTIF_ID = 600_000

    data class Summary(
        // [P33-P8] المبلغ قروش Long (كان ريال Double) — التنسيق عبر Money.formatP
        val dueReminders: List<Triple<String, String, Long>>, // (اسم، هاتف، مبلغ)
        val checksDue: List<String>,
        val lowStock: List<String>,
        val installmentsDue: List<String>,
        val backupDone: Boolean
    )

    suspend fun run(graph: AppGraph, context: Context, notify: Boolean = true, force: Boolean = false): Summary {
        val now = System.currentTimeMillis()
        // كل دورة كل 6 ساعات كانت تعيد إرسال نفس الإشعارات لنفس المتأخرات
        // بلا نهاية (lastRun يُكتب ولا يُقرأ) — الإشعار الآن مُقيّد بنافذة 20 ساعة لكل قاعدة،
        // والتشغيل اليدوي «تشغيل الآن» يمرر force=true فيتجاوز الكابح. الحساب والملخص يبقيان كاملين.
        val THROTTLE_MS = 20L * 3_600_000L
        // رمز العملة الأساسية من قاعدة البيانات لكل التنبيهات
        val symbol = try { graph.db.currencies().base()?.symbol } catch (_: Exception) { null }
            ?: "ر.س"
        val rules = graph.db.rules().allOnce()
        val summary = Summary(mutableListOf(), mutableListOf(), mutableListOf(), mutableListOf(), false)

        // 1) تذكير الفواتير المستحقة/المتأخرة
        rules.firstOrNull { it.kind == "DUE_REMIND" }?.let { rule ->
            if (rule.enabled) {
                val mayNotify = notify && (force || now - rule.lastRun >= THROTTLE_MS)
                val horizon = now + rule.daysBefore * 86_400_000L
                val due = graph.db.invoices().allOnce()
                    // [P33-P8] open قروش Long — مقارنة تامة بدل عتبة 0.004
                    .filter { it.isSale && it.status < 3 && it.open > 0L && it.dueDate <= horizon }
                val byParty = due.groupBy { it.partyId }
                // لقطة الإعدادات خارج الحلقة — كانت تُقرأ لكل طرف
                val receivableAlerts = graph.settings.snapshot().receivableAlerts
                var surfaced = false
                for ((pid, list) in byParty) {
                    val party = graph.ledger.party(pid) ?: continue
                    val total = list.sumOf { it.open }
                    (summary.dueReminders as MutableList).add(Triple(party.name, party.phone, total))
                    // تذكير الذمم يخضع لإعداده الحقيقي من مركز الإعدادات
                    if (mayNotify && receivableAlerts) {
                        // [P6-M35 إصلاح]: الختم مربوط بنتيجة التسليم الفعلية — كان surfaced=true
                        // يُرفَع قبل الاستدعاء فيُختم lastRun حتى مع رفض صلاحية الإشعارات
                        // (notify تعيد false مبكراً) فيُسدّ كابح الـ20 ساعة بلا توصيل حقيقي.
                        // توحيد مع نمط H-20/H-14: لا ختم إلا عند إشعار وصل فعلاً.
                        surfaced = notify(
                            context,
                            // النطاقات كانت متداخلة (1000+partyId == 2000+checkId عند
                            // partyId=1001) فيستبدل إشعار الذمم إشعار الشيك — نطاقات متباعدة 100k
                            100_000 + pid.toInt(),
                            context.getString(R.string.app_name),
                            // كان النص عربياً صلباً في الكود حتى بلغة التطبيق الإنجليزية
                            // التسمية كانت من أول فاتورة في المجموعة (اعتباطية) —
                            // طرف بفواتير متأخرة وقريبة معاً قد يُعلَّم «قرب الاستحقاق» وهو عليه متأخرات
                            context.getString(
                                R.string.notif_due_remind,
                                party.name,
                                Money.formatP(total, symbol), // [P33-P8] قروش Long
                                context.getString(
                                    if (list.any { it.dueDate < now }) R.string.notif_due_overdue
                                    else R.string.notif_due_soon
                                )
                            ),
                            // [P30-A]: نقرة إشعار الذمم تفتح شاشة الديون مباشرة
                            contentIntent = routeIntent(
                                context, com.superbiz.app.ui.nav.Routes.DEBTS,
                                100_000 + pid.toInt()
                            )
                        ) || surfaced
                    }
                }
                // lastRun لا يُختم إلا عند ظهور إشعار فعلي — كان يُختم
                // كل دورة (6 ساعات) حتى بلا إشعار فيُسدّ كابح الـ20 ساعة للأبد
                if (surfaced) graph.db.rules().upsert(rule.copy(lastRun = now))
            }
        }

        // 2) تنبيه الشيكات
        rules.firstOrNull { it.kind == "CHECK_REMIND" }?.let { rule ->
            if (rule.enabled) {
                val mayNotify = notify && (force || now - rule.lastRun >= THROTTLE_MS)
                // كانت نافذة التذكير تبدأ من الآن فتستبعد كل شيك متأخر
                // لم يُحصَّل بعد — التأخير نفسه هو أهم ما يُذكَّر به. تتسع النافذة 90 يوماً للخلف.
                val soon = graph.checks.dueSoon(now - 90 * 86_400_000L, now + rule.daysBefore * 86_400_000L)
                // تتبع ظهور إشعار فعلي لقرار ختم lastRun
                var surfaced = false
                for (c in soon) {
                    val party = graph.ledger.party(c.partyId)
                    // كان النص عربياً صلباً في الكود حتى بلغة التطبيق الإنجليزية
                    val msg = context.getString(
                        R.string.notif_check_msg,
                        c.number,
                        Money.formatP(c.amount, symbol), // [P33-P8] مبلغ الشيك قروش Long
                        party?.name ?: "",
                        java.text.SimpleDateFormat("dd/MM", java.util.Locale.US).format(java.util.Date(c.dueDate))
                    )
                    (summary.checksDue as MutableList).add(msg)
                    if (mayNotify) {
                        // [P6-M35 إصلاح]: نفس الربط — surfaced من نتيجة notify نفسها لا قبلها
                        surfaced = notify(
                            context, 200_000 + c.id.toInt(),
                            context.getString(R.string.check_due_soon), msg,
                            // [P30-A]: نقرة إشعار الشيك تفتح شاشة الشيكات مباشرة
                            contentIntent = routeIntent(
                                context, com.superbiz.app.ui.nav.Routes.CHECKS,
                                200_000 + c.id.toInt()
                            )
                        ) || surfaced
                    }
                }
                if (surfaced) graph.db.rules().upsert(rule.copy(lastRun = now))
            }
        }

        // 3) تنبيه المخزون المنخفض
        rules.firstOrNull { it.kind == "LOW_STOCK" }?.let { rule ->
            if (rule.enabled) {
                val mayNotify = notify && (force || now - rule.lastRun >= THROTTLE_MS)
                val low = graph.inventory.lowStock()
                for (p in low) {
                    (summary.lowStock as MutableList).add("${p.name}: ${Money.num(p.stockQty)} ${p.unit}")
                }
                // تتبع ظهور إشعار فعلي لقرار ختم lastRun
                var surfaced = false
                // إشعار المخزون المنخفض يخضع لإعداده الحقيقي من مركز الإعدادات
                if (mayNotify && low.isNotEmpty() && graph.settings.snapshot().lowStockAlerts) {
                    // [P6-M35 إصلاح]: نفس الربط — لا ختم إلا عند نجاح النشر فعلاً
                    surfaced = notify(
                        context, 300_000,
                        context.getString(R.string.low_stock),
                        low.take(3).joinToString(" • ") { "${it.name} (${Money.num(it.stockQty)})" },
                        // [P30-A]: نقرة إشعار المخزون تفتح شاشة المخزون مباشرة
                        contentIntent = routeIntent(
                            context, com.superbiz.app.ui.nav.Routes.INVENTORY, 300_000
                        )
                    ) || surfaced
                }
                if (surfaced) graph.db.rules().upsert(rule.copy(lastRun = now))
            }
        }

        // 4) تذكير الأقساط المستحقة والمتأخرة
        rules.firstOrNull { it.kind == "INSTALLMENT_REMIND" }?.let { rule ->
            if (rule.enabled) {
                val mayNotify = notify && (force || now - rule.lastRun >= THROTTLE_MS)
                val horizon = now + rule.daysBefore * 86_400_000L
                val due = graph.installments.dueBetween(
                    // النافذة كانت 30 يوماً للخلف — القسط المتأخر 31 يوماً
                    // فأكثر يسقط من التذكيرات نهائياً رغم أنه الأهم! نفس صنف R11-C9 الذي
                    // أصلناه للشيكات بنافذة 90 يوماً — الأقساط كانت النسيان المكمل
                    now - 90L * 86_400_000L,
                    horizon
                )
                // تتبع ظهور إشعار فعلي لقرار ختم lastRun
                var surfaced = false
                for ((plan, inst, st) in due) {
                    val party = graph.ledger.party(plan.partyId) ?: continue
                    // [P33-P8] المفتوح قروش Long بمساواة تامة — حُذف round2 (أُزيل من المحرك)
                    val open = inst.amount - inst.paidAmount
                    val when_ = if (st == com.superbiz.app.domain.InstallmentEngine.St.LATE) context.getString(R.string.notif_due_overdue)
                    else java.text.SimpleDateFormat("dd/MM", java.util.Locale.US).format(java.util.Date(inst.dueDate))
                    // كان النص عربياً صلباً في الكود حتى بلغة التطبيق الإنجليزية
                    val msg = context.getString(
                        R.string.notif_installment_msg,
                        inst.seq, plan.months, plan.title,
                        Money.formatP(open, symbol), when_ // [P33-P8] قروش Long
                    )
                    (summary.installmentsDue as MutableList).add(msg)
                    if (mayNotify) {
                        // [P6-M35 إصلاح]: نفس الربط — الختم يتبع نجاح التسليم لا نية الإرسال
                        surfaced = notify(
                            context, 400_000 + inst.id.toInt(),
                            context.getString(R.string.installments_title),
                            "${party.name}: $msg",
                            // [P30-A]: نقرة إشعار القسط تفتح شاشة الأقساط مباشرة
                            contentIntent = routeIntent(
                                context, com.superbiz.app.ui.nav.Routes.INSTALLMENTS,
                                400_000 + inst.id.toInt()
                            )
                        ) || surfaced
                    }
                }
                if (surfaced) graph.db.rules().upsert(rule.copy(lastRun = now))
            }
        }

        // 5) النسخ الاحتياطي التلقائي — المدة من الإعدادات (: إيقاف/يومي/أسبوعي/شهري)
        //
        // [P6-M37 إصلاح] → [P7-L17 إصلاح] قيد filesDir/backups حُلّ عندما يُختار مجلد:
        // كانت النسخ تُكتب في filesDir/backups الداخلي فقط (تموت بحذف التطبيق أو فقدان الجهاز).
        // الآن بعد نجاح exportLocal وختمه تنسخ SafBackupMirror مرآة الملف إلى شجرة SAF المختارة
        // (backupDirUri من مركز الإعدادات) مع استبدال نسخة اليوم وتدوير الأقدم من 7 أيام —
        // وفشل المرآة لا يفسد نجاح النسخة الداخلية ولا يُختم أي نجاح إضافي: تحذير نظام فقط.
        // بلا مجلد مختار يبقى السلوك الداخلي حرفياً كما كان.
        // توحيد الفحص: كان هنا فحص صارم بلا هامش (now - last > days*DAY) بينما
        // BackupWorker يستخدم BackupAutoLogic.isDue بهامش GRACE 5 دقائق —
        // النافذة موحّدة الآن على BackupAutoLogic.isDue في كليهما فلا نسخة مزدوجة
        // ولا فجوة دلالية بين مساري الأتمتة (كل 6 ساعات) والمجدول المستقل.
        rules.firstOrNull { it.kind == "AUTO_BACKUP" }?.let { rule ->
            if (rule.enabled) {
                val s = graph.settings.snapshot()
                val days = s.autoBackupDays
                if (BackupAutoLogic.isDue(now, s.lastAutoBackup, days)) {
                    try {
                        val f = graph.backup.exportLocal()
                        // [P6-M36 إصلاح]: طابع النجاح يُكتب بعد نجاح التصدير فعلاً فقط —
                        // أي استثناء يقفز إلى catch دون ختم فتُعاد المحاولة في الدورة التالية
                        graph.settings.setLastAutoBackup(now)
                        // [P7-L17 إصلاح]: مرآة SAF — نفس عقد BackupWorker: بلا مجلد مختار يبقى
                        // السلوك الداخلي حرفياً، وفشل المرآة تحذير نظام بمفاتيح مخصصة
                        // (النسخة الداخلية نجحت وخُتمت) دون أي ختم إضافي أو مساس بالنجاح.
                        if (!SafBackupMirror.mirror(context, f, s.backupDirUri)) {
                            AutomationLogic.notify(
                                context,
                                BACKUP_FAIL_NOTIF_ID,
                                context.getString(R.string.backup_saf_failed_t),
                                context.getString(R.string.backup_saf_failed_b),
                                // [P30-B]: قناة النسخ الاحتياطي المتخصصة
                                // [P30-A]: نقرة تحذير المرآة تفتح مركز الإعدادات مباشرة
                                contentIntent = routeIntent(
                                    context, com.superbiz.app.ui.nav.Routes.SETTINGS_HUB,
                                    BACKUP_FAIL_NOTIF_ID
                                ),
                                channel = SuperBizApp.CHANNEL_BACKUP
                            )
                        } else {
                            // [P36-BK] مرآة PDFs بعد نجاح JSON فقط — best-effort (نمط BackupWorker)
                            SafBackupMirror.mirrorPdfs(context, s.backupDirUri, s.backupIncludePdfs)
                        }
                    } catch (e: Exception) {
                        // فشل النسخ التلقائي الصامت يخدع المستخدم بأمان زائف — يُسجَّل الآن
                        com.superbiz.app.core.ErrorCenter.warn("AutoBackup", "exportLocal: ${e.message}")
                        // [P6-M36 إصلاح]: الفشل لم يعد صامتاً للمستخدم — إشعار نظام عبر قناة
                        // التنبيهات الموجودة (نفس قناة كل تذكيرات الأتمتة). 600k بعيد عن
                        // نطاقات الذمم/الشيكات/المخزون/الأقساط (100k-400k).
                        AutomationLogic.notify(
                            context,
                            BACKUP_FAIL_NOTIF_ID,
                            context.getString(R.string.backup_auto_failed_t),
                            context.getString(R.string.backup_auto_failed_b),
                            // [P30-B]: قناة النسخ الاحتياطي المتخصصة
                            // [P30-A]: نقرة فشل النسخ تفتح مركز الإعدادات مباشرة
                            contentIntent = routeIntent(
                                context, com.superbiz.app.ui.nav.Routes.SETTINGS_HUB,
                                BACKUP_FAIL_NOTIF_ID
                            ),
                            channel = SuperBizApp.CHANNEL_BACKUP
                        )
                    }
                }
            }
        }
        return summary
    }

    // [P5-H14 إصلاح]: الدالة تعيد ما إذا سُلّم الإشعار فعلاً — كان النوع Unit يُخفي
    // نتيجة التسليم على المستدعين فيضيع التذكير إلى الأبد مع رفض الإذن.
    // [P30-B]: المفوِّض الرباعي القديم أُزيل — الوسائط الافتراضية في النواة أدناه
    // تغطي استدعاءاته حرفياً (bigText=null ⇒ توسيع بالمتن نفسه، contentIntent=null
    // ⇒ هدف النقرة القديم برمز 900، channel=قناة التنبيهات) فلا غموض تضارب.

    /**
     * [P15-c] نواة الإشعار المشتركة مع عرض موسّع وهدف نقرة اختياريين:
     * - bigText != null ⇒ NotificationCompat.BigTextStyle بنص التوسيع المعطى
     *   (المتن المختصر يبقى body)؛ null ⇒ توسيع بـ body نفسه كالسلوك القديم.
     * - contentIntent != null ⇒ يُثبَّت كهدف النقرة؛ null ⇒ الافتراضي القديم
     *   (فتح MainActivity برمز الطلب 900 — انظر defaultContentIntent).
     * - نفس القناة، نفس فحص POST_NOTIFICATIONS، ونفس النتيجة Boolean للسلوك القديم.
     */
    fun notify(
        context: Context,
        id: Int,
        title: String,
        body: String,
        bigText: String? = null,
        contentIntent: PendingIntent? = null,
        // [P30-B] قناة الإشعار — الافتراضي قناة التنبيهات التاريخية حفاظاً على إعدادات
        // المستخدمين الحاليين؛ مرسلو التقارير والنسخ الاحتياطي والزيارات يمررون قنواتهم
        // المتخصصة فيتحكم المستخدم في كل صنف على حدة من إعدادات النظام
        channel: String = SuperBizApp.CHANNEL_ALERTS
    ): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        // [P20-FIX agent14]: قبل 33 كان الإغلاق من إعدادات النظام (areNotificationsEnabled=false)
        // يجعل notify() صامتاً عادياً يعيد true — تُختم lastRun/التقرير المجدول وتُكبَح دورة كاملة
        // رغم أن التذكير لم يظهر أبداً. البوابة الموحّدة تغطي 26..32 + صلاحية 33+
        if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText ?: body))
            .setContentIntent(contentIntent ?: defaultContentIntent(context))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
            return true
        } catch (e: SecurityException) {
            // تسجيل الرفض بدل الصمت — كان غياب صلاحية الإشعارات
            // يبتلع بصمت فلا يفهم المستخدم لماذا لا تظهر تذكيرات الشيكات/الأقساط
            com.superbiz.app.core.ErrorCenter.warn(
                "AutomationWorker", "notification permission denied: ${e.message}"
            )
        }
        return false
    }

    /**
     * [P15-c] هدف النقرة الافتراضي — المنطق ذاته الذي كان داخل notify القديمة
     * حرفياً (نُقل كما هو بلا أي تغيير في الرمز أو الأعلام أو الدلالة).
     */
    /**
     * [P30-A] هدف النقرة الواعي بالسياق — إشعار التذكير يفتح الشاشة المعنية مباشرة
     * (الديون/الشيكات/المخزون/الأقساط/مركز الإعدادات) بنفس آلية EXTRA_ROUTE المثبتة
     * في VisitReminder والويدجت، والقائمة البيضاء StartRouteWhitelist في Nav تحكم
     * ما يُفتح بعد تجاوز القفل.
     * requestCode يميّز الهدف إلزامياً: extras لا تدخل في filterEquals فطلب واحد
     * يعني آخر PendingIntent مُحدَّث يخدم الجميع (درس R15-F7) — نمرر معرف الإشعار
     * نفسه؛ نطاقاته 100k/200k/300k/400k/600k بعيدة عن 0/900/5001-5003 المستخدمة.
     */
    fun routeIntent(context: Context, route: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, com.superbiz.app.MainActivity::class.java)
            .putExtra(com.superbiz.app.MainActivity.EXTRA_ROUTE, route)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun defaultContentIntent(context: Context): PendingIntent {
        val intent = Intent(context, com.superbiz.app.MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            // الطلب كان 0 — نفسه نفسه requestCode لهدف فتح ويدجت الذمم
            // (نفس MainActivity ونفس filterEquals لأن الإضافات لا تدخل في المقارنة)
            // فكان آخر PendingIntent مُحدَّث يخدم الاثنين: نقرة الإشعار قد تفتح تبويب
            // الذمم، ونقرة الويدجت تفقد وجهتها. 900 بعيد عن كل الرموز المستخدمة (0-7، 5001-3)
            900, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

class AutomationWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        val graph = AppGraph.from(applicationContext)
        AutomationLogic.run(graph, applicationContext)
        Result.success()
    } catch (e: Exception) {
        // إعادة المحاولة الصامتة إلى الأبد بلا أثر — الفشل المتكرر
        // كان يصل للمستخدم كـ«التذكيرات لا تعمل» بلا أي تتبع. يُسجَّل الآن عبر ErrorCenter.
        com.superbiz.app.core.ErrorCenter.warn("AutomationWorker", "run failed: ${e::class.simpleName}: ${e.message}")
        Result.retry()
    }

    companion object {
        fun schedule(graph: AppGraph) {
            val wm = WorkManager.getInstance(graph.context)
            wm.enqueueUniquePeriodicWork(
                "superbiz_daily",
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<AutomationWorker>(6, TimeUnit.HOURS).build()
            )
        }

        suspend fun runOnceNow(graph: AppGraph): AutomationLogic.Summary =
            AutomationLogic.run(graph, graph.context, notify = false)
    }
}

/** إعادة جدولة المهام بعد إعادة التشغيل */
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val pr = goAsync()
            CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    val graph = AppGraph.from(context.applicationContext as android.app.Application)
                    // [P20-FIX agent14]: كانت خطوة الفشل الواحدة (مثلاً IOException من snapshot)
                    // تقفز إلى catch العام فتُسقط إعادة تسليح المنبّهات والزيارات حتى الإقلاع التالي —
                    // كل خطوة مستقلة الآن بـ runCatching خاص
                    runCatching { AutomationWorker.schedule(graph) }
                    runCatching {
                        val s = graph.settings.snapshot()
                        ReportScheduleWorker.schedule(context.applicationContext, s.reportScheduleDays, s.reportScheduleHour)
                    }
                    // [P11-b] إعادة تسليح التنبيهات الدقيقة من دفتر ExactAlarms —
                    // AlarmManager يموت مع إعادة التشغيل والدفتر في SharedPreferences يبقى
                    runCatching { ExactAlarms.reattachAll(context.applicationContext) }
                    // [P14-a] إعادة جدولة تذكير «العملاء الذين لم يُزاروا» — force=true:
                    // المنبّه حتماً مات مع الإطفاء حتى لو كان موعده المسجّل مستقبلياً
                    runCatching { VisitReminder.reattach(context.applicationContext, force = true) }
                } catch (_: Exception) {
                } finally {
                    pr.finish()
                }
            }
        }
    }
}
