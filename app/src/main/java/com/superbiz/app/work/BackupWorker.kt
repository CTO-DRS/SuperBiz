package com.superbiz.app.work

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.superbiz.app.AppGraph
import com.superbiz.app.SuperBizApp
import com.superbiz.app.R
import com.superbiz.app.core.ErrorCenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * — النسخ الاحتياطي المجدول تلقائياً (وظيفة 34).
 *
 * • إعداد «نسخ تلقائي كل N أيام» (autoBackupDays: 0=معطّل، خيارات 0/1/3/7/30) في مركز
 * الإعدادات يُجدول هذا الـWorker دورياً (PeriodicWorkRequest بحسب N).
 * • الـWorker يستدعي منطق التصدير الحقيقي القائم `BackupRepo.exportLocal()` كما هو —
 * **بلا أي تعديل في BackupRepo** — فيكتب النسخة إلى المجلد الداخلي المخصص
 * filesDir/backups بلا أي Intent خارجي، ثم يختم توقيت آخر نجاح في DataStore
 * (setLastAutoBackup) ويُعرض تحت الإعداد («آخر نسخة: تاريخ أو —»).
 * • حارس isDue يمنع النسخ المزدوج مع فحص الأتمتة القائم (AutomationLogic) لأن كليهما
 * يقرأ/يكتب نفس ختم lastAutoBackup — والنافذة موحّدة الآن عبر BackupAutoLogic.isDue
 * (هامش GRACE 5 دقائق) في المسارين.
 * • [P6-M37 إصلاح] → [P7-L17 إصلاح] قيد filesDir/backups (تموت بحذف التطبيق) حُلّ عندما يُختار
 * مجلد: صف «مجلد النسخ الاحتياطي» في مركز الإعدادات يحفظ مرجع شجرة SAF (backupDirUri القائم)،
 * وبعد نجاح exportLocal تنسخ SafBackupMirror مرآة الملف إلى ذلك المجلد (استبدال نسخة اليوم
 * + تدوير الأقدم من 7 أيام). بلا مجلد مختار يبقى السلوك الداخلي حرفياً كما هو، وفشل المرآة
 * لا يفسد نجاح النسخة الداخلية ولا يُختم أي نجاح إضافي — تحذير نظام فقط (نمط M6-36).
 * • الجدولة تُستدعى من مُحدِّث الإعداد (SettingsVM.setAutoBackupDays) ومن جامع إعدادات
 * AppVM عند كل إقلاع — WorkManager يحفظ العمل الدوري عبر إعادة التشغيل تلقائياً.
*/
class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        return try {
            val graph = AppGraph.from(applicationContext)
            val s = graph.settings.snapshot()
            if (s.autoBackupDays <= 0) return Result.success()
            val force = inputData.getBoolean(KEY_FORCE, false)
            if (!force && !BackupAutoLogic.isDue(System.currentTimeMillis(), s.lastAutoBackup, s.autoBackupDays)) {
                return Result.success()
            }
            // منطق التصدير الحقيقي القائم — استدعاء فقط (BackupRepo ملف ممنوع التعديل)
            // [P6-M36 إصلاح]: الختم (setLastAutoBackup) يقع هنا بعد نجاح exportLocal فعلاً —
            // أي فشل يقفز إلى catch دون ختم النجاح، فتعتبره isDue مستحقاً في الدورة التالية.
            val f = graph.backup.exportLocal()
            graph.settings.setLastAutoBackup(System.currentTimeMillis())
            // [P7-L17 إصلاح]: مرآة SAF بعد نجاح النسخة الداخلية وختمها — فشل المرآة يعيد false
            // فيُرسل تحذيراً نظاماً (مفاتيح backup_saf_failed_* المخصصة: النسخة نفسها نجحت
            // فلا يُستخدم backup_auto_failed الذي يعني فشل التصدير كله) دون مساس بالختم.
            if (!SafBackupMirror.mirror(applicationContext, f, s.backupDirUri)) {
                AutomationLogic.notify(
                    applicationContext,
                    AutomationLogic.BACKUP_FAIL_NOTIF_ID,
                    applicationContext.getString(R.string.backup_saf_failed_t),
                    applicationContext.getString(R.string.backup_saf_failed_b),
                    // [P30-B]: قناة النسخ الاحتياطي المتخصصة
                    // [P30-A]: نقرة تحذير المرآة تفتح مركز الإعدادات مباشرة
                    contentIntent = AutomationLogic.routeIntent(
                        applicationContext, com.superbiz.app.ui.nav.Routes.SETTINGS_HUB,
                        AutomationLogic.BACKUP_FAIL_NOTIF_ID
                    ),
                    channel = SuperBizApp.CHANNEL_BACKUP
                )
            } else {
                // [P36-BK] مرآة PDFs بعد نجاح JSON فقط — best-effort: فشلها يُسجّل ولا يُنذر
                SafBackupMirror.mirrorPdfs(applicationContext, s.backupDirUri, s.backupIncludePdfs)
            }
            Result.success()
        } catch (e: Exception) {
            // تسجيل فشل النسخ الاحتياطي الآلي قبل إعادة المحاولة (نمط R10)
            com.superbiz.app.core.ErrorCenter.warn("BackupWorker", "auto backup failed: ${e::class.simpleName}: ${e.message}")
            // [P6-M36 إصلاح]: الفشل كان صامتاً تماماً للمستخدم (ErrorCenter داخلي فقط) —
            // إشعار نظام الآن عبر قناة التنبيهات الموجودة (SuperBizApp.CHANNEL_ALERTS)
            // بنفس آلية AutomationLogic.notify (تحترم صلاحية الإشعارات وتفشل بهدوء آمن).
            // ختم النجاح لا يُكتب عند الفشل — Result.retry يعيد المحاولة بجدولة WorkManager.
            AutomationLogic.notify(
                applicationContext,
                AutomationLogic.BACKUP_FAIL_NOTIF_ID,
                applicationContext.getString(R.string.backup_auto_failed_t),
                applicationContext.getString(R.string.backup_auto_failed_b),
                // [P30-B]: قناة النسخ الاحتياطي المتخصصة
                // [P30-A]: نقرة فشل النسخ تفتح مركز الإعدادات مباشرة
                contentIntent = AutomationLogic.routeIntent(
                    applicationContext, com.superbiz.app.ui.nav.Routes.SETTINGS_HUB,
                    AutomationLogic.BACKUP_FAIL_NOTIF_ID
                ),
                channel = SuperBizApp.CHANNEL_BACKUP
            )
            Result.retry()
        }
    }

    companion object {
        const val KEY_FORCE = "force"
        private const val WORK_NAME = "superbiz_auto_backup"

        /** جدولة/إلغاء العمل الدوري بحسب N — يُستدعى من مركز الإعدادات وعند الإقلاع */
        fun schedule(context: Context, days: Int) {
            val wm = WorkManager.getInstance(context)
            if (days <= 0) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            wm.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<BackupWorker>(BackupAutoLogic.periodDays(days), TimeUnit.DAYS).build()
            )
        }
    }
}

/** منطق جدولة النسخ التلقائي — نقي وقابل للاختبار وحداتياً بلا أي اعتماد على Android */
object BackupAutoLogic {

    const val DAY_MS = 86_400_000L

    /** هامش سماوي 5 دقائق قبل اكتمال الدورة (يمنع الازدواج مع فحص الأتمتة كل 6 ساعات) */
    private const val GRACE_MS = 5L * 60_000L

    /** هل حان موعد نسخة جديدة؟ (معطّل → لا؛ بلا ختم سابق → نعم) */
    fun isDue(now: Long, last: Long, days: Int): Boolean {
        if (days <= 0) return false
        if (last <= 0) return true
        return now - last >= days * DAY_MS - GRACE_MS
    }

    /** مدة الدورة الدورية بالأيام (WorkManager: يوم فأكثر؛ الخيارات 1/3/7/30) */
    fun periodDays(days: Int): Long = days.coerceIn(1, 30).toLong()
}

/**
 * [P7-L17 إصلاح] منطق نقي لمرآة النسخ الاحتياطي في شجرة SAF — بلا أي اعتماد على Android
 * (نمط BackupAutoLogic في هذا الملف نفسه) يُختبر وحداتياً في BackupTreeP7Test.
 *
 * أسماء نسخ المرآة: superbiz-backup-YYYY-MM-DD.json (نسخة واحدة لليوم المحلي — استبدال يومي)
 * مع تدوير بسيط: ما أقدم من 7 أيام يُحذف قبل الكتابة، مع حماية الأحدث [KEEP_DEFAULT] نسخة.
 */
object BackupTreeP7 {

    const val NAME_PREFIX = "superbiz-backup-"
    const val NAME_SUFFIX = ".json"
    const val DAY_MS = 86_400_000L

    /** سعة التدوير: أحدث النسخ المحفوظة دائماً (وحدها المستثناة من حذف «الأقدم من 7 أيام») */
    const val KEEP_DEFAULT = 7

    /** اسم ملف نسخة اليوم: superbiz-backup-YYYY-MM-DD.json */
    fun backupName(date: String): String = "$NAME_PREFIX$date$NAME_SUFFIX"

    /** تاريخ YYYY-MM-DD من الاسم، أو null إن لم يطابق بنية أسماء نسخ المرآة (ملفات أخرى أمانة) */
    fun dateFromName(name: String): String? {
        if (!name.startsWith(NAME_PREFIX) || !name.endsWith(NAME_SUFFIX)) return null
        val mid = name.removePrefix(NAME_PREFIX).removeSuffix(NAME_SUFFIX)
        if (mid.length != 10 || mid[4] != '-' || mid[7] != '-') return null
        // كل مواضع الأرقام الثمانية يجب أن تكون أرقاماً (0..3 السنة، 5..6 الشهر، 8..9 اليوم)
        val digitAt = intArrayOf(0, 1, 2, 3, 5, 6, 8, 9)
        return if (digitAt.all { mid[it].isDigit() }) mid else null
    }

    /** تاريخ الاسم إلى ميلي‌ثانية منتصف ليله بالتوقيت المحلي — أو null للاسم غير المطابق */
    fun dayMsFromName(name: String): Long? {
        val d = dateFromName(name) ?: return null
        return try {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d)?.time
        } catch (_: Exception) {
            null
        }
    }

    /**
     * أسماء النسخ الواجب حذفها (التدوير البسيط):
     * • يُحذف ما زمنه أقدم من [maxAgeDays] يوماً عن [now] (بزمن آخر تعديل أو تاريخ الاسم).
     * • عدا الأحدث [keep] نسخة ككل (حماية من محو الأرشيف كله لو تأخرت الدورات أو قُفز زمن النظام).
     * • أي اسم لا يطابق بنية النسخ يُتجاهل تماماً — لا يُمس ملف المستخدم الأخرى في المجلد.
     * • زمن مجهول (0 أو سالب) يُعدّ الأقدم — يُحذف أولاً ما لم تحمِه سعة [keep].
     */
    fun pruneOld(
        nameToDate: List<Pair<String, Long>>,
        now: Long,
        keep: Int = KEEP_DEFAULT,
        maxAgeDays: Int = KEEP_DEFAULT
    ): List<String> {
        val horizon = now - maxAgeDays.coerceAtLeast(0) * DAY_MS
        // الأحدث keep بالأصالة محمية ولو تجاوزت الأفق الزمني
        val protectedNames = nameToDate.asSequence()
            .sortedByDescending { it.second }
            .take(keep.coerceAtLeast(0))
            .map { it.first }
            .toSet()
        return nameToDate
            .filter { (name, ts) ->
                dateFromName(name) != null && ts < horizon && name !in protectedNames
            }
            .map { it.first }
    }
}

/**
 * [P7-L17 إصلاح] مرآة النسخ الاحتياطي إلى مجلد يختاره المستخدم (شجرة SAF) — الحل الموثق لقيد
 * P6-M37 (النسخ الداخلية تموت بحذف التطبيق).
 *
 * العقد الصارم مع المسارين (BackupWorker + AutomationWorker/AUTO_BACKUP):
 * • بلا [com.superbiz.app.data.repo.Settings.backupDirUri] ⇒ لا شيء يُنفَّذ (true) — السلوك
 *   الافتراضي الداخلي حرفياً كما كان قبل هذه الموجة.
 * • الإذن المستمر سُحب من النظام ⇒ فشل نظير (false) بلا أي محاولة كتابة.
 * • كل عمليات SAF داخل try/IO ⇒ أي استثناء مزوّد مستندات يصير فشل نظير لا انهيار مسار النسخ.
 * • فشل المرآة لا يفسد نجاح النسخة الداخلية (الملف المحلي مكتوب والختم مكتوب قبل الاستدعاء)
 *   ولا يُختم أي نجاح إضافي — المستدعي يرسل تحذيراً نظاماً فقط (نمط M6-36 بمفاتيح مخصصة).
 * • الاستبدال الذري بقدر ما يسمح SAF: حذف نسخة اليوم بنفس الاسم ثم إنشاء مستند جديد وكتابته
 *   (createDocument بـ mime application/json) — لا كتابة فوق مستند مفتوح.
 */
object SafBackupMirror {

    private const val MIME_JSON = "application/json"

    /** مستند فرعي في شجرة المجلد المختار */
    private data class TreeDoc(val uri: Uri, val name: String, val lastModified: Long?)

    /**
     * نسخ [file] إلى شجرة [dirUriStr] إن وُجدت وما زال الإذن ممنوحاً.
     * يُستدعى من سياق suspend (CoroutineWorker) — عمليات SAF/القراءة على Dispatchers.IO.
     * يعيد true عند النجاح أو عدم الحاجة، وfalse عند فشل المرآة (تحذير لدى المستدعي).
     */
    suspend fun mirror(
        context: Context,
        file: File,
        dirUriStr: String?,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        if (dirUriStr.isNullOrBlank()) return true // بلا مجلد مختار — المسار الداخلي فقط كما هو
        return try {
            val resolver = context.contentResolver
            // الإذن ما زال ممنوحاً؟ (قد يُسحب من إعدادات النظام فيختفي المنح المستمر)
            val stillGranted = resolver.persistedUriPermissions.any {
                it.uri.toString() == dirUriStr && it.isWritePermission
            }
            if (!stillGranted) {
                ErrorCenter.warn("BackupWorker", "SAF mirror: permission not granted for $dirUriStr")
                false
            } else {
                withContext(Dispatchers.IO) { copyToTree(resolver, file, dirUriStr, now) }
            }
        } catch (e: Exception) {
            // مزوّد المستندات متقلب (FilesApp ميت، جهاز MTP، استثناءات مزوّد) — فشل نظير لا يُنهار
            ErrorCenter.warn("BackupWorker", "SAF mirror: ${e::class.simpleName}: ${e.message}")
            false
        }
    }

    /** كل عمليات SAF الفعلية — أي استثناء هنا يلتقطه mirror فيصير فشل نظير */
    private fun copyToTree(resolver: ContentResolver, file: File, dirUriStr: String, now: Long): Boolean {
        val treeUri = Uri.parse(dirUriStr)
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        // تاريخ اليوم المحلي — نسخة واحدة لكل يوم تُستبدل
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
        val name = BackupTreeP7.backupName(today)

        val children = listChildren(resolver, rootDoc)

        // 1) تدوير: النسخ الأقدم من 7 أيام بنفس البادئة تُحذف قبل الكتابة —
        //    فشل حذف نسخة قديمة لا يمنع نسخة اليوم، يُسجَّل ويُتابع
        val stale = BackupTreeP7.pruneOld(
            children.map { doc ->
                doc.name to (doc.lastModified ?: BackupTreeP7.dayMsFromName(doc.name) ?: 0L)
            },
            now
        )
        for (doc in children) {
            if (doc.name in stale) {
                runCatching { DocumentsContract.deleteDocument(resolver, doc.uri) }
                    .onFailure { ErrorCenter.warn("BackupWorker", "SAF prune ${doc.name}: ${it.message}") }
            }
        }

        // 2) استبدال نسخة اليوم بنفس الاسم إن وُجدت: حذف ثم إنشاء جديد (لا كتابة فوق مستند قائم)
        children.firstOrNull { it.name == name }?.let { same ->
            if (!DocumentsContract.deleteDocument(resolver, same.uri)) {
                // فشل الحذف قد يجعل createDocument يولّد اسماً بلاحقة (1) — نفضّل فشلاً صريحاً
                ErrorCenter.warn("BackupWorker", "SAF: deleteDocument($name) returned false")
                return false
            }
        }

        // 3) إنشاء مستند اليوم وكتابة محتوى النسخة الداخلية الناجحة
        val docUri = DocumentsContract.createDocument(resolver, rootDoc, MIME_JSON, name) ?: run {
            ErrorCenter.warn("BackupWorker", "SAF: createDocument($name) returned null")
            return false
        }
        resolver.openOutputStream(docUri, "wt")?.use { os ->
            file.inputStream().use { it.copyTo(os) }
        } ?: run {
            ErrorCenter.warn("BackupWorker", "SAF: openOutputStream($name) returned null")
            return false
        }
        return true
    }

    /** سرد أبناء شجرة المجلد — فشل الاستعلام (مزوّد ميت) يعيد قائمة فارغة فيفشل المسار اللاحق نظيفاً */
    private fun listChildren(resolver: ContentResolver, rootDoc: Uri): List<TreeDoc> {
        val out = ArrayList<TreeDoc>()
        runCatching {
            val kidsUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                rootDoc, DocumentsContract.getDocumentId(rootDoc)
            )
            resolver.query(
                kidsUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val docName = c.getString(1) ?: continue
                    val lm = if (c.isNull(2)) null else c.getLong(2)
                    out.add(TreeDoc(DocumentsContract.buildDocumentUriUsingTree(rootDoc, id), docName, lm))
                }
            }
        }
        return out
    }

    // ── [P36-BK] مرآة ملفات PDFs الكشوف إلى شجرة SAF ──

    /** اسم المجلد الفرعي الثابت لمرآة PDFs داخل الشجرة المختارة */
    private const val PDF_DIR_NAME = "statements-pdf"
    private const val PDF_MIME = "application/pdf"

    /** سقف المرآة = نفس سياسة الاحتفاظ الداخلية لملفات PDFs (P6-M41: 30 يوماً/أحدث 20) */
    private const val PDF_MIRROR_KEEP = 20

    /**
     * [P36-BK] مرآة PDFs الكشوف — تُستدعى بعد نجاح مرآة JSON فقط عندما فعّل
     * المستخدم «تضمين ملفات PDF» في مركز الإعدادات ([com.superbiz.app.data.repo.Settings.backupIncludePdfs]):
     * • بلا مجلد مختار أو بلا ملفات مصدر ⇒ true (لا شيء مطلوب — غياب PDFs ليس فشلاً).
     * • مجلد فرعي ثابت `statements-pdf` (يُنشأ إن غاب) بأحدث 20 ملفاً
     *   (نفس سياسة الاحتفاظ الداخلية P6-M41) مع استبدال نفس الاسم حذفاً ثم إنشاءً،
     *   وتقليم ما بقي في المرآة ولم يعد موجوداً محلياً.
     * • فشل نظير best-effort: يُسجّل في ErrorCenter ولا يفسد نجاح النسخة JSON —
     *   فشلها لا يرسل تحذيراً منفصلاً للمستخدم (النسخة الأساسية هي JSON).
     */
    suspend fun mirrorPdfs(context: Context, dirUriStr: String?, include: Boolean): Boolean {
        if (!include || dirUriStr.isNullOrBlank()) return true
        return try {
            val resolver = context.contentResolver
            val stillGranted = resolver.persistedUriPermissions.any {
                it.uri.toString() == dirUriStr && it.isWritePermission
            }
            if (!stillGranted) {
                ErrorCenter.warn("BackupWorker", "PDF mirror: permission not granted for $dirUriStr")
                false
            } else {
                withContext(Dispatchers.IO) { copyPdfsToTree(context, resolver, dirUriStr) }
            }
        } catch (e: Exception) {
            ErrorCenter.warn("BackupWorker", "PDF mirror: ${e::class.simpleName}: ${e.message}")
            false
        }
    }

    /** كل عمليات SAF الفعلية لمرآة PDFs — أي استثناء يلتقطه mirrorPdfs فيصير فشل نظير */
    private fun copyPdfsToTree(context: Context, resolver: ContentResolver, dirUriStr: String): Boolean {
        val treeUri = Uri.parse(dirUriStr)
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        val sourceDir = File(context.filesDir, "pdfs/statements")
        val pdfs = sourceDir.listFiles { f -> f.isFile && f.name.endsWith(".pdf") }
            ?.sortedByDescending { it.lastModified() }?.take(PDF_MIRROR_KEEP)
            ?: return true
        // مجلد statements-pdf الفرعي: إيجاد أو إنشاء (idempotent)
        val children = listChildren(resolver, rootDoc)
        val subDoc = children.firstOrNull { it.name == PDF_DIR_NAME }?.uri
            ?: DocumentsContract.createDocument(
                resolver, rootDoc, DocumentsContract.Document.MIME_TYPE_DIR, PDF_DIR_NAME
            ) ?: run {
                ErrorCenter.warn("BackupWorker", "PDF mirror: createDocument($PDF_DIR_NAME) returned null")
                return false
            }
        val subChildren = listChildren(resolver, subDoc).associateBy { it.name }
        var ok = true
        val copiedNames = HashSet<String>()
        for (pdf in pdfs) {
            // استبدال نسخة الملف القائم بنفس الاسم: حذف ثم إنشاء (لا كتابة فوق مستند قائم)
            subChildren[pdf.name]?.let { existing ->
                runCatching { DocumentsContract.deleteDocument(resolver, existing.uri) }
                    .onFailure { ErrorCenter.warn("BackupWorker", "PDF mirror delete ${pdf.name}: ${it.message}") }
            }
            val docUri = DocumentsContract.createDocument(resolver, subDoc, PDF_MIME, pdf.name)
            if (docUri == null) {
                ErrorCenter.warn("BackupWorker", "PDF mirror: createDocument(${pdf.name}) returned null")
                ok = false
                continue
            }
            try {
                resolver.openOutputStream(docUri, "wt")?.use { os ->
                    pdf.inputStream().use { it.copyTo(os) }
                } ?: run {
                    ErrorCenter.warn("BackupWorker", "PDF mirror: openOutputStream(${pdf.name}) null")
                    ok = false
                }
            } catch (e: Exception) {
                ErrorCenter.warn("BackupWorker", "PDF mirror write ${pdf.name}: ${e.message}")
                ok = false
            }
            copiedNames += pdf.name
        }
        // تقليم: ما بقي في المرآة ولم يُعد يوجد محلياً (خارج أحدث 20 أو حُذف) يُحذف
        for (doc in subChildren.values) {
            if (doc.name !in copiedNames) {
                runCatching { DocumentsContract.deleteDocument(resolver, doc.uri) }
                    .onFailure { ErrorCenter.warn("BackupWorker", "PDF prune ${doc.name}: ${it.message}") }
            }
        }
        return ok
    }
}
