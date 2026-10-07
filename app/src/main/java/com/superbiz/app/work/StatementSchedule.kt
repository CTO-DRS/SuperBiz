package com.superbiz.app.work

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.superbiz.app.AppGraph
import com.superbiz.app.data.db.StatementRuleEntity
import com.superbiz.app.domain.statement.StatementData
import com.superbiz.app.domain.statement.StatementLang
import com.superbiz.app.domain.statement.StatementRulesEngine
import com.superbiz.app.domain.statement.StatementRulesEngine.EventSnapshot
import com.superbiz.app.domain.statement.StatementRulesEngine.RuleSnapshot
import com.superbiz.app.domain.statement.StatementService
import com.superbiz.app.pdf.statement.StatementPdfRenderer
import com.superbiz.app.pdf.statement.StatementStyle
import com.superbiz.app.pdf.statement.StatementTemplates
import com.superbiz.app.ui.screens.statement.StatementUiFacade
import com.superbiz.app.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * [P18-b] منطق جدولة كشف الحساب الخالص — حتمي وقابل للاختبار وحداتياً.
 * الحدود: لا يستدعي أندرويد مباشرة (يستقبل كيان Room للتحويل فقط — بلا Context/WorkManager)،
 * وهو الخط نفسه المتبع في ReportScheduleLogic بنفس الملف النمطي.
 */
object StatementScheduleLogic {

    /** مدة الدورة الدورية بالدقائق — الحد الأدنى القانوني لـWorkManager (15 دقيقة) */
    const val PERIOD_MINUTES = 15L

    /**
     * كم ملّي ثانية حتى «النقطة» القادمة على شبكة periodMinutes بالتوقيت المحلي؟
     * (15 دقيقة ⇒ التالي 00/15/30/45). الموعد الصائب تماماً الآن ⇒ الدورة القادمة
     * (بعد حصرياً — نمط OverdueReminderPolicy) كي لا يُطلق العامل مرتين في الحين نفسه.
     * الثواني وأجزاء الثانية تُصفَّر دائماً: الإقلاع يلتصق بشبكة الدقائق لا بلحظة التثبيت.
     */
    fun tickAlignedDelay(now: Long, periodMinutes: Long, tz: TimeZone = TimeZone.getDefault()): Long {
        val period = periodMinutes.coerceIn(1L, 1440L).toInt()
        val c = Calendar.getInstance(tz).apply { timeInMillis = now }
        // [P18-int-fix] Calendar لا يملك MINUTE_OF_DAY — يُشتق من HOUR_OF_DAY*60+MINUTE
        val minuteOfDay = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val rem = minuteOfDay % period
        if (rem > 0) c.add(Calendar.MINUTE, period - rem)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= now) c.add(Calendar.MINUTE, period)
        return c.timeInMillis - now
    }

    /**
     * تحويل كيان Room إلى لقطة المحرك النقية — نقطة التحويل الوحيدة (قرار العقد:
     * طبقة JVM النقية لا تعرف كيانات Room). customDays يُشتق من eventFlagsJson
     * هنا كي يستهلكها nextRunAt(CUSTOM) بلا إعادة تحليل.
     */
    fun toSnapshot(e: StatementRuleEntity): RuleSnapshot = RuleSnapshot(
        id = e.id,
        partyMode = e.partyMode,
        partyIdsJson = e.partyIdsJson,
        frequency = e.frequency,
        weekday = e.weekday,
        dayOfMonth = e.dayOfMonth,
        hour = e.hour,
        minute = e.minute,
        periodPreset = e.periodPreset,
        channel = e.channel,
        eventFlagsJson = e.eventFlagsJson,
        // [P33-P8] عتبة القاعدة قروش Long في الكيان، والمحرك النقي يقارن بالريالات
        // (RuleSnapshot.threshold ما زال Double? — لم يُرحَّل في هذه الموجة) —
        // نقطة الحد هذه تحوّل قروش → ريال عبر fromPiasters فتبقى دلالة العتبة كما كانت.
        threshold = e.threshold?.let { Money.fromPiasters(it) },
        lastRunAt = e.lastRunAt,
        nextRunAt = e.nextRunAt,
        enabled = e.enabled,
        customDays = StatementRulesEngine.parseCustomDays(e.eventFlagsJson),
        // [P18-int] ربط القالب/التوقيع/الختم — يحتاجها loadImages/resolveStyle عند التوليد
        templateId = e.templateId,
        signatureId = e.signatureId,
        stampId = e.stampId
    )

    /**
     * غلاف استحقاق جاهز للعامل — كيان Room → لقطة → محرك (بالصيغة الخماسية كي
     * يُوسَّع ALL إلى الأطراف الفعلية). snapshot قابل للمرور null (قواعد دورية).
     */
    fun shouldRunRule(
        rule: StatementRuleEntity,
        now: Long,
        snapshot: EventSnapshot?,
        tz: TimeZone = TimeZone.getDefault(),
        allPartyIds: List<Long> = emptyList()
    ): Boolean = StatementRulesEngine.isDue(toSnapshot(rule), now, tz, snapshot, allPartyIds)

    /** تحليل partyIdsJson — تفويض للمحرك (نقطة واحدة للحقيقة) */
    fun parsePartyIds(json: String?): List<Long> = StatementRulesEngine.parsePartyIds(json)
}

/**
 * [P18-b] لقطة الأحداث الحقيقية فوق AppGraph — تحقيق EventSnapshot للمحرك.
 *
 * كل الاستعلامات من دوال DAO قائمة **بلا أي تعديل على Daos.kt** (قرار الموجة:
 * إضافة استعلامات جديدة من مسؤولية التكامل لا هذا الملف):
 * - hasNewTx: JournalDao.partyLinesBetween(partyId, since, MAX) — حركة بعد لحظة.
 * - unpaidInvoiceCount: InvoiceDao.forPartyBetween(partyId, 0, MAX) + فلتر
 *   type=0 (بيع) وopen > 0L ([P33-P8] مساواة تامة — كانت عتبة 0.004 ريال قبل الترحيل).
 * - overdueCount: نفس الاستعلام + dueDate < now — المقياس الحرفي لـ
 *   ReportsRepo.overdueTotal (لا يُعاد تعريف «متأخر» هنا).
 * - balanceDue: JournalDao.partyOpeningBalance(partyId, MAX) = Σ(debit-credit)
 *   التاريخ كله، موجب = العميل يدين لك (دلالة partyBalances الموثقة).
 * - lastDayOfMonth: Calendar بالمنطقة الممررة — حساب محلي لا استعلام.
 *
 * [P18-b][gap] المفاتيس غير المغطاة بقصد (متاحة للتكامل إن أراد توسيع دلالة
 * OVERDUE عبر استعلام DAO جديد): الشيكات الواردة المستحقة غير المحصلة والأقساط
 * المتأخرة لا تدخل overdueCount — المقياس المعتمد هو الفواتير فقط اتساقاً مع
 * كشف «متأخر» القائم. إن قرر التكامل إضافتهما فتعديل هنا فقط (العقد نقية).
 *
 * كل دالة تلتهم فشل الاستعلام بقيمة محافظة (false/0/0.0) — فشل قاعدة واحدة
 * لا يُسقط العامل ولا يقلب حكماً كذباً موجباً (لا كشوف وهمية).
 */
class StatementEventSnapshot(private val graph: AppGraph) : EventSnapshot {

    // [P18-int] استعلامات DAO معلّقة والعقد النقي متزامن — runBlocking هنا آمن:
    // الاستدعاء يحدث داخل عامل WorkManager على Dispatchers.Default/IO (ليس الخيط
    // الرئيسي) والاستعلامات قصيرة، وفشلها مُلتهم بقيمة محافظة كما كان مقصوداً.
    override fun hasNewTx(partyId: Long, since: Long): Boolean = try {
        kotlinx.coroutines.runBlocking {
            graph.db.journal()
                .partyLinesBetween(partyId, since.coerceAtLeast(0L), Long.MAX_VALUE)
                .isNotEmpty()
        }
    } catch (_: Exception) { false }

    override fun overdueCount(partyId: Long): Int = try {
        val now = System.currentTimeMillis()
        kotlinx.coroutines.runBlocking {
            graph.db.invoices().forPartyBetween(partyId, 0L, Long.MAX_VALUE)
                // [P33-P8] open قروش Long — مقارنة تامة بدل عتبة الفاصلة العائمة
                .count { it.type == 0 && it.status < 3 && it.open > 0L && it.dueDate < now }
        }
    } catch (_: Exception) { 0 }

    override fun unpaidInvoiceCount(partyId: Long): Int = try {
        kotlinx.coroutines.runBlocking {
            graph.db.invoices().forPartyBetween(partyId, 0L, Long.MAX_VALUE)
                // [P33-P8] open قروش Long — مقارنة تامة بدل عتبة الفاصلة العائمة
                .count { it.type == 0 && it.status < 3 && it.open > 0L }
        }
    } catch (_: Exception) { 0 }

    override fun balanceDue(partyId: Long): Double = try {
        kotlinx.coroutines.runBlocking {
            // [P33-P8] SUM(debit-credit) فوق أعمدة القروش يعيد قروش (الـDAO يُعلن Double
            // فتأتي القيمة قروشاً صحيحاً بشكل Double) — تُثبَّت إلى Long قروش ثم تُحوَّل
            // إلى ريال via fromPiasters ليظل الرصيد بالريالات كما يفترض المحرك النقي
            // (TOLERANCE وعتبة THRESHOLD ريالية) — نفس دلالة ما قبل P8 بقيمة أدق تاماً.
            Money.fromPiasters(
                graph.db.journal().partyOpeningBalance(partyId, Long.MAX_VALUE).toLong()
            )
        }
    } catch (_: Exception) { 0.0 }

    override fun lastDayOfMonth(now: Long, tz: TimeZone): Boolean {
        val c = Calendar.getInstance(tz).apply { timeInMillis = now }
        return c.get(Calendar.DAY_OF_MONTH) == c.getActualMaximum(Calendar.DAY_OF_MONTH)
    }
}

/**
 * [P18-b] إعدادات العامل الخاصة (SharedPreferences مستقلة — لا لمس SettingsRepo).
 * المفتاح الوحيد اليوم سقف محاولات إعادة الإرسال. ملاحظة تكامل: إن أراد P18-a
 * امتلاك هذا الإعداد داخل SmtpPrefs فالانتقال سطر واحد هنا.
 */
class StatementAutoPrefs(context: Context) {

    private val sp = context.getSharedPreferences("statement_auto_prefs", Context.MODE_PRIVATE)

    /** سقف محاولات الإرسال التلقائي لكل سطر تسليم — فوقه يبقى FAILED للمستخدم */
    var maxAttempts: Int
        get() = sp.getInt(KEY_MAX_ATTEMPTS, DEFAULT_MAX_ATTEMPTS).coerceIn(1, 20)
        set(value) = sp.edit().putInt(KEY_MAX_ATTEMPTS, value.coerceIn(1, 20)).apply()

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
        private const val KEY_MAX_ATTEMPTS = "max_attempts"
    }
}

/** [P18-b] نتيجة إرسال SMTP — ثلاثية صريحة بدل رمي استثناءات عبر حدود الحالة */
private sealed interface SmtpSendResult {
    /** العميل غير مربوط بعد (نقطة تكامل P18-a) — لا تحرق محاولات */
    data object NotWired : SmtpSendResult
    data object Sent : SmtpSendResult
    data class Failed(val error: String) : SmtpSendResult
}

/**
 * [P18-b] مجدول كشوف الحساب التلقائي — يفحص كل دورة (15 دقيقة) القواعد المستحقة
 * ويولّد الكشوف فعلياً (نفس خط الإنتاج: assemble → StatementPdfRenderer → issue
 * بdedup) ثم يسجل التسليم. نمط ReportScheduleWorker حرفياً: CoroutineWorker +
 * enqueueUniquePeriodicWork + initialDelay محسوب بالتقويم.
 *
 * ضمانات:
 * - عزل الفشل: كل قاعدة وكل طرف وكل تسليم في try/catch مستقل (Log.w) — فشل
 *   طرف واحد لا يمنع بقية القواعد ولا بقية الأطراف.
 * - إلغاء التكرار: repo.issue يعيد الكشف القائم لنفس (طرف، فترة) — إعادة الدورة
 *   لا تضاعف الصفوف، وrecordDelivery مفتاحه الفريد "delivery:<id>:<channel>".
 * - الإرسال الفعلي: قناة EMAIL/SMTP مع بريد طرف صالح تمر عبر بوابة SMTP أدناه،
 *   وغير ذلك يُسجل PENDING بموعد مجدول — حالة حقيقية يظهرها سجل الكشوف ويعيد
 *   المستخدم إرسالها يدوياً (لا كشف يضيع بصمت).
 * - بلا قواعد مفعلة: الدورة تنجح فوراً بلا عمل (التحقق داخل العامل كي تبقى
 *   ensure() أبسط تسجيل ثابت بلا قراءة إعدادات).
 */
class StatementScheduleWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        return try {
            val graph = AppGraph.from(app)
            val repo = graph.statements
            val now = System.currentTimeMillis()
            val tz = TimeZone.getDefault()
            val prefs = StatementAutoPrefs(app)
            val snapshot = StatementEventSnapshot(graph)
            val allPartyIds = runCatching {
                graph.db.parties().allOnce().map { it.id }
            }.getOrDefault(emptyList())

            // 1) الدوري: استعلام العقد (enabled AND nextRunAt <= now — فهرس nextRunAt)
            val periodic = runCatching { repo.dueRules(now) }.getOrDefault(emptyList())
            // 2) الحدثي: بلا nextRunAt أصلاً — تُفحص أعلامه كل دورة (قرار المحرك ②)
            val eventRules = runCatching {
                repo.rulesOnce().filter {
                    it.enabled && it.frequency.trim()
                        .equals(StatementRulesEngine.FREQ_EVENT, ignoreCase = true)
                }
            }.getOrDefault(emptyList())

            for (entity in periodic) {
                try {
                    processRule(entity, graph, repo, now, tz, snapshot, allPartyIds, isEventRun = false)
                } catch (e: Exception) {
                    Log.w(TAG, "statement rule ${entity.id} failed: ${e::class.simpleName}: ${e.message}")
                }
            }
            for (entity in eventRules) {
                try {
                    processRule(entity, graph, repo, now, tz, snapshot, allPartyIds, isEventRun = true)
                } catch (e: Exception) {
                    Log.w(TAG, "event rule ${entity.id} failed: ${e::class.simpleName}: ${e.message}")
                }
            }

            // 3) إعادة المحاولة التلقائية للتسليمات الفاشلة — مسار منفصل معزول
            try { processFailedDeliveries(graph, repo, prefs) } catch (e: Exception) {
                Log.w(TAG, "retry pass failed: ${e::class.simpleName}: ${e.message}")
            }
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "statement scheduler crashed: ${t::class.simpleName}: ${t.message}")
            Result.retry()
        }
    }

    /** معالجة قاعدة واحدة كاملة — الاستحقاق والأطراف والفترة ثم الإصدار والتسليم والختم */
    private suspend fun processRule(
        entity: StatementRuleEntity,
        graph: AppGraph,
        repo: com.superbiz.app.data.repo.StatementRepo,
        now: Long,
        tz: TimeZone,
        snapshot: EventSnapshot,
        allPartyIds: List<Long>,
        isEventRun: Boolean
    ) {
        val rule = StatementScheduleLogic.toSnapshot(entity)
        // الدوري سبق أن استُوثق استحقاقه من dueRules(now) — الحدثي يُحسم بالمحرك
        if (isEventRun && !StatementRulesEngine.isDue(rule, now, tz, snapshot, allPartyIds)) return

        val parties = StatementRulesEngine.targetParties(rule, allPartyIds)
        if (parties.isEmpty()) {
            // [P18-b] قاعدة بلا أطراف (SINGLE فاسد/SELECTED فارغ) — تدقيق صادق لا صمت
            repo.audit("STATEMENT_RULE_SKIPPED", "rule=${rule.id} reason=no-parties mode=${rule.partyMode}")
        } else {
            val period = StatementRulesEngine.periodFor(rule.periodPreset, now)
            if (period == null) {
                // CUSTOM أو اسم غير معروف: قواعد AUTO بفترة CUSTOM لا معنى لها (عقد المحرك ④)
                repo.audit("STATEMENT_RULE_SKIPPED", "rule=${rule.id} reason=period-preset:${rule.periodPreset}")
            } else {
                val note = runCatching { graph.settings.snapshot().statementDefaultNote }
                    .getOrNull().orEmpty()
                for (partyId in parties) {
                    try {
                        issueAndDeliverForParty(graph, repo, rule, partyId, period, note, now)
                    } catch (e: Exception) {
                        Log.w(TAG, "rule ${rule.id} party $partyId failed: ${e::class.simpleName}: ${e.message}")
                    }
                }
            }
        }
        // ختم التشغيل: الدوري يُجدول دوره التالي؛ الحدثي nextRunAt=null عمداً —
        // (العقد: الحدثي يقوده الأعلام عبر lastRunAt لا جدول زمني)
        val next = if (isEventRun) null
        else StatementRulesEngine.nextRunAt(rule, now, tz).takeIf { it > 0 }
        runCatching { repo.markRuleRun(entity.id, now, next) }
            .onFailure { Log.w(TAG, "markRuleRun ${entity.id} failed: ${it.message}") }
    }

    /** مسار طرف واحد: تجميع → رسم PDF إلى filesDir/pdfs → إصدار (dedup) → تسجيل تسليم */
    private suspend fun issueAndDeliverForParty(
        graph: AppGraph,
        repo: com.superbiz.app.data.repo.StatementRepo,
        rule: RuleSnapshot,
        partyId: Long,
        period: Pair<Long, Long>,
        note: String,
        now: Long
    ) {
        // اللغة: BILINGUAL افتراضياً للقواعد (لا حقل لغة في القاعدة — قرار الموجة)
        val data = repo.assemble(partyId, period.first, period.second, StatementLang.BILINGUAL, note)
        val (style, storedTemplateId) = resolveStyle(graph, repo, rule.templateId)
        val (logo, signature, stamp, companyPhoto) = loadImages(graph, repo, rule)
        val outDir = File(graph.context.filesDir, "pdfs")
        val fileName = StatementService.safeFileName(data.party.name, period.first, period.second) + ".pdf"
        val rendered = StatementPdfRenderer.render(
            graph.context, data, style, logo, signature, stamp, companyPhoto, outDir, fileName
        )
        // dedup على مستوى الكشف: نفس الطرف+الفترة يعيد الصف القائم — تكرار الدورة آمن
        val stmt = repo.issue(data, storedTemplateId, rendered.file.absolutePath)
        deliverStatement(graph, repo, stmt, data, rule, now)
        repo.audit(
            "STATEMENT_AUTO_ISSUE",
            "rule=${rule.id} statement=${stmt.statementNumber} party=$partyId file=${rendered.file.name}"
        )
    }

    /**
     * تسجيل التسليم بحسب القناة — حالة صادقة لكل مسار:
     * EMAIL/SMTP + بريد طرف + بوابة مربوطة ⇒ SENT/FAILED (محاولة فعلية attempts=1)،
     * وغير ذلك (قناة يدوية أو لا بريد أو البوابة غير مربوطة) ⇒ PENDING بموعد مجدول
     * يبقى في سجل الكشوف لإعادة الإرسال اليدوي (لا كشف يضيع بصمت).
     */
    private suspend fun deliverStatement(
        graph: AppGraph,
        repo: com.superbiz.app.data.repo.StatementRepo,
        stmt: com.superbiz.app.data.db.StatementEntity,
        data: StatementData,
        rule: RuleSnapshot,
        now: Long
    ) {
        val channel = rule.channel.trim().uppercase(Locale.US)
        val to = data.party.email?.trim().orEmpty()
        val scheduledFor = StatementRulesEngine.nextRunAt(rule, now, TimeZone.getDefault())
            .takeIf { it > 0 }
        if ((channel == "EMAIL" || channel == "SMTP") && to.isNotBlank()) {
            val subject = "${data.party.name} - ${stmt.statementNumber}"
            when (val r = sendViaSmtp(graph.context, to, File(stmt.filePath), subject)) {
                SmtpSendResult.NotWired ->
                    repo.recordDelivery(stmt.id, channel, "PENDING", 0, null, scheduledFor)
                SmtpSendResult.Sent ->
                    repo.recordDelivery(stmt.id, channel, "SENT", 1)
                is SmtpSendResult.Failed ->
                    repo.recordDelivery(stmt.id, channel, "FAILED", 1, r.error)
            }
        } else {
            repo.recordDelivery(stmt.id, channel, "PENDING", 0, null, scheduledFor)
        }
    }

    /**
     * [P18-b][P18-int] بوابة SMTP الوحيدة في هذا الملف — مدمجة مع عميل 18-a
     * (StatementEmail.SmtpClient عبر StatementPrefs.smtpConfig كنقطة بناء العقد
     * الوحيدة). لا تكوين مفعّل ⇒ NotWired (التسليم PENDING لقناة المستخدم)،
     * وفشل حقيقي ⇒ Failed برسالة صادقة مختصرة (تُدوَّن في lastError).
     * كل شيء داخل Dispatchers.IO — القراءة والشبكة بعيدان عن الخيط الرئيسي.
     */
    private suspend fun sendViaSmtp(
        context: Context,
        to: String,
        attachment: File,
        subject: String
    ): SmtpSendResult = withContext(Dispatchers.IO) {
        val config = com.superbiz.app.domain.statement.StatementPrefs.smtpConfig(context)
            ?: return@withContext SmtpSendResult.NotWired
        val bytes = runCatching { attachment.readBytes() }.getOrNull()
            ?: return@withContext SmtpSendResult.Failed("cannot read statement pdf: ${attachment.name}")
        runCatching {
            com.superbiz.app.domain.statement.SmtpClient.send(
                config,
                listOf(to),
                subject,
                "Account statement attached.\nتم إرفاق كشف الحساب.\nSuperBiz",
                attachment.name,
                bytes
            )
        }.fold(
            onSuccess = { SmtpSendResult.Sent },
            onFailure = { e ->
                SmtpSendResult.Failed((e.message ?: e.javaClass.simpleName).take(200))
            }
        )
    }

    /**
     * إعادة المحاولة التلقائية — FAILED/RETRYING تحت سقف المحاولات فقط (RetryPolicy):
     * - قناة EMAIL/SMTP: تُعاد عبر بوابة SMTP نفسها؛ NotWired ⇒ RETRYING بلا حرق
     *   محاولات (قبل التكامل لا نستنزف السقف بلا سبب)، وFAILED ⇒ markDelivery
     *   يزيدها (آلة حالات العقد)، وSENT يختم.
     * - قنوات يدوية (WHATSAPP/PRINT/SHARE): لا إرسال آلي ممكن → RETRYING تبقى
     *   بارزة في السجل لإعادة الإرسال اليدوي (لا محاولات تُستهلك بلا فعل).
     * - كشف/ملف مفقود: FAILED صريح بمحتوى الخطأ — يستهلك محاولة كي يتوقف عن
     *   الدوران عند السقف بدل محاولة أبدية على ملف لا وجود له.
     */
    private suspend fun processFailedDeliveries(
        graph: AppGraph,
        repo: com.superbiz.app.data.repo.StatementRepo,
        prefs: StatementAutoPrefs
    ) {
        val max = prefs.maxAttempts
        val candidates = runCatching {
            repo.deliveriesByStatus("FAILED") + repo.deliveriesByStatus("RETRYING")
        }.getOrDefault(emptyList())
        val now = System.currentTimeMillis()
        for (d in candidates) {
            try {
                // [تدقيق M-5] البوابة الكاملة: الأهلية + مهلة التراجع الأسّي —
                // كان shouldAutoRetry وحدها تعيد كل فاشل في كل دورة (15 دقيقة)
                // فتضرب مضيفاً ساقطاً بعاصفة؛ nextDelayMs أصبحت مستهلكة فعلاً
                if (!com.superbiz.app.domain.statement.RetryPolicy.isRetryDue(
                        d.status, d.attempts, max, d.lastAttemptAt, now)
                ) {
                    continue   // CANCELLED/SENT/استُنفد السقف/مهلتها لم تنتهِ — لا عاصفة على المضيف
                }
                val channel = d.channel.trim().uppercase(Locale.US)
                if (channel != "EMAIL" && channel != "SMTP") {
                    repo.markDelivery(d.id, "RETRYING")
                    continue
                }
                val stmt = runCatching { repo.statement(d.statementId) }.getOrNull()
                val file = stmt?.filePath?.let { File(it) }
                if (stmt == null || file == null || !file.exists()) {
                    repo.markDelivery(d.id, "FAILED", "statement file missing")
                    continue
                }
                val to = runCatching {
                    graph.ledger.party(stmt.partyId)?.email?.trim().orEmpty()
                }.getOrNull().orEmpty()
                if (to.isEmpty()) {
                    repo.markDelivery(d.id, "RETRYING")
                    continue
                }
                when (val r = sendViaSmtp(graph.context, to, file, stmt.statementNumber)) {
                    SmtpSendResult.NotWired -> repo.markDelivery(d.id, "RETRYING")
                    SmtpSendResult.Sent -> repo.markDelivery(d.id, "SENT")
                    is SmtpSendResult.Failed -> repo.markDelivery(d.id, "FAILED", r.error)
                }
            } catch (e: Exception) {
                Log.w(TAG, "retry delivery ${d.id} failed: ${e::class.simpleName}: ${e.message}")
            }
        }
    }

    // ───────── دعم الرسم ─────────

    /**
     * حل النمط من معرف القالب — نفس فضاء المعرفات الذي تستخدمه واجهة 17-c:
     * معرف مدمج ("COR-01"…) أو "CUSTOM-<dbRow>" (configJson يفك عبر
     * StatementUiFacade.decodeStyle فوق نمط القاعدة). الترتيب: قاعدة القاعدة →
     * المفضل المخزن في statement_prefs → المدمج الافتراضي. أي فشل ⇒ الافتراضي
     * (لا كشف يُعدم بسبب قالب محذوف).
     */
    private suspend fun resolveStyle(
        graph: AppGraph,
        repo: com.superbiz.app.data.repo.StatementRepo,
        templateId: String?
    ): Pair<StatementStyle, String> {
        val fallback = StatementTemplates.byId(StatementTemplates.DEFAULT_ID)?.style
            ?: StatementTemplates.ALL.first().style
        val id = templateId?.trim().orEmpty().ifBlank {
            StatementUiFacade.defaultTemplateIdPref(graph.context) ?: StatementTemplates.DEFAULT_ID
        }
        val style = if (id.startsWith("CUSTOM-")) {
            val dbId = id.removePrefix("CUSTOM-").toLongOrNull()
            val row = dbId?.let { runCatching { repo.template(it) }.getOrNull() }
            val base = row?.let { StatementTemplates.byId(it.baseTemplateId)?.style } ?: fallback
            row?.let { StatementUiFacade.decodeStyle(it.configJson, base) } ?: fallback
        } else {
            StatementTemplates.byId(id)?.style ?: fallback
        }
        return style to id
    }

    /** تحميل صور الشعار/التوقيع/الخاتم/صورة الشركة — أي فشل صورة ⇒ null بهدوء (المحرك يحجز الحيّز) */
    private suspend fun loadImages(
        graph: AppGraph,
        repo: com.superbiz.app.data.repo.StatementRepo,
        rule: RuleSnapshot
    ): Array<Bitmap?> = withContext(Dispatchers.IO) {
        val s = runCatching { graph.settings.snapshot() }.getOrNull()
        val sigPath = runCatching {
            (rule.signatureId?.let { repo.signature(it) } ?: repo.defaultSignature())?.imagePath
        }.getOrNull()
        val stampPath = runCatching {
            (rule.stampId?.let { repo.stamp(it) } ?: repo.defaultStamp())?.imagePath
        }.getOrNull()
        val decode = { path: String? ->
            path?.takeIf { it.isNotBlank() }?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
        }
        arrayOf(
            decode(s?.avatarPath),
            decode(sigPath),
            decode(stampPath),
            decode(s?.companyPhotoPath)
        )
    }

    companion object {
        private const val TAG = "StatementSchedule"
        const val WORK_NAME = "statement-scheduler"

        /**
         * تسجيل العمل الدوري — يُستدعى من التكامل (موضع مقترح: إقلاع التطبيق
         * إلى جانب ReportSchedule.schedule، أو بعد أول حفظ قاعدة). سياسة KEEP
         * لا REPLACE: الدورة ثابتة 15 دقيقة ولا إعدادات تغيّرها، فلا سبب لإعادة
         * ضبط الإيقاع عند كل إقلاع (أثر REPLACE الجانبي يلغي تشغيلاً جارياً بلا داعٍ).
         * يُسجَّل دائماً بغض النظر عن أي مفاتيح — العامل نفسه ينجح فوراً إذا لم
         * توجد قواعد مفعلة (تبسيط مقصود: نقطة تسجيل واحدة بلا فروع).
         */
        fun ensure(context: Context) {
            val delay = StatementScheduleLogic.tickAlignedDelay(
                System.currentTimeMillis(), StatementScheduleLogic.PERIOD_MINUTES, TimeZone.getDefault()
            )
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<StatementScheduleWorker>(
                    StatementScheduleLogic.PERIOD_MINUTES, TimeUnit.MINUTES
                )
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .build()
            )
        }
    }
}
