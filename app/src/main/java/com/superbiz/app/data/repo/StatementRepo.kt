package com.superbiz.app.data.repo

// [P36-M4-8] استيرادات IO المنقولة من StatementUiFacade (معاينة PDF/شعار/SMTP)
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.room.withTransaction
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.AuditLogEntity
import com.superbiz.app.data.db.NoteTemplateEntity
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.db.SignatureEntity
import com.superbiz.app.data.db.StatementDeliveryEntity
import com.superbiz.app.data.db.StatementEntity
import com.superbiz.app.data.db.StatementRuleEntity
import com.superbiz.app.data.db.StatementTemplateEntity
import com.superbiz.app.data.db.StampEntity
import com.superbiz.app.data.repo.LedgerRepo
import com.superbiz.app.domain.statement.StatementCompanyInfo
import com.superbiz.app.domain.statement.StatementData
import com.superbiz.app.domain.statement.StatementLang
import com.superbiz.app.domain.statement.StatementPartyInfo
import com.superbiz.app.domain.statement.StatementService
import com.superbiz.app.domain.statement.SmtpClient
import com.superbiz.app.domain.statement.SmtpConfig
import com.superbiz.app.domain.statement.StatementTxRow
import com.superbiz.app.domain.statement.StatementTxTypes
import java.io.File
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * [P17-a] مستودع كشف الحساب PDF — طبقة البيانات لتمويج 17-a (لا UI ولا رسم PDF هنا).
 *
 * البناء على نمط LedgerRepo (حقن AppDatabase + مستودعات مجاورة). قرارات العقد الموثقة:
 *
 * 1) assemble يقرأ الحركة من الدفتر المزدوج (journal/journal_lines) عبر استعلامات
 *    JournalDao الجديدة [P17-a] — لا يعيد استخدام LedgerRepo.statement() لأنه بلا
 *    نافذة زمنية وبلا رصيد افتتاحي (يقرأ التاريخ كله)، والقراءة المزدوجة (دفتر +
 *    payments جدولاً) كانت ستضاعف الحركة. payments/فواتير لا تُجمع هنا إلا
 *    للملخص (خصومات الفواتير) — سطور الدفع وحدها من الدفتر فلا ازدواج أبداً.
 *
 * 2) الترقيم: seq = MAX(الذيل الرقمي لأرقام الكشوف الموجودة) + 1 — لا count+1؛
 *    حذف كشف لا يجعل رقمه يعاد استخدامه (رقم سند مالي لا يُولد مرتين). أول كشف = 1.
 *
 * 3) عدم تكرار الإصدار (issue): statements بلا عمود dedupKey (عقد المخطط ثابت)،
 *    فالـdedup على الثلاثية (partyId, fromTs, toTs) — إعادة إصدار نفس الفترة
 *    لنفس الطرف تُعيد الكشف القائم كما هو (بلا فشل ولا صف ثانٍ). متى أراد 17-b
 *    إعادة توليد PDF فعلي لنفس الفترة (قالب آخر مثلاً) فعنده issueForced أدناه.
 *
 * 4) recordDelivery: سطر واحد لكل (كشف، قناة) — مفتاح dedupKey "delivery:<id>:<channel>"
 *    فريد؛ تكرار الإرسال يحدّث السطر القائم (حالة/attempts/خطأ) ويعيد معرّفه.
 *
 * 5) المصروفات لا تدخل كشف الطرف: كيان Expense (Entities.kt) **بلا عمود partyId** —
 *    المصروف مال الشركة لا حركة على حساب طرف، فلا مجال لاختراق ربط وهمي. إن أراد
 *    منتج لاحق ربط مصروف بطرف فعلياً فهو ترحيل مخطط لا تحايل هنا (صادق مع البنية).
 *
 * 6) العملة: عمود currency يُلقط من baseCurrency — الأسطر الدفترية تُلخص بقيمها
 *    الاسمية (نفس دلالة LedgerRepo.statement() القائمة؛ fxRate مطبق وقت الترحيل).
 */
class StatementRepo(
    private val db: AppDatabase,
    private val ledger: LedgerRepo,      // قراءة الطرف عبر المسار القائم (party) + اتساق الطبقة
    private val settings: SettingsRepo
) {

    // ═══════════ التجميع ═══════════

    /**
     * تجميع كشف دون إصدار: بطاقة الطرف (بأعمدته الجديدة) + لقطة الإعدادات (هوية
     * الشركة) + الافتتاحي + سطور الفترة بتراكم الرصيد + ملخص + ترقيم ورقم تحقق.
     * contentHash يبقى فارغاً هنا عمداً — يُحسب عند issue (العقد: «يُلحق بعد الإصدار»)
     * لأن الترقيم الذي يدخل البصمة يثبت لحظة الإصدار لا التجميع.
     */
    suspend fun assemble(
        partyId: Long,
        fromTs: Long,
        toTs: Long,
        lang: StatementLang,
        note: String?
    ): StatementData {
        require(fromTs <= toTs) { "statement period inverted: $fromTs > $toTs" }
        val party = ledger.party(partyId)
            ?: throw IllegalArgumentException("statement party not found: $partyId")
        val s = settings.snapshot()

        // [P33-P8] الافتتاحي قروش Long (COALESCE(SUM(debit-credit),0) على أعمدة INTEGER)
        val opening = db.journal().partyOpeningBalance(partyId, fromTs)
        var running = opening
        val rows = db.journal().partyRowsBetween(partyId, fromTs, toTs).map { r ->
            running += r.debit - r.credit
            StatementTxRow(
                ts = r.ts,
                ref = if (r.refId == null) (r.refType ?: "") else "${r.refType ?: ""}#${r.refId}",
                typeKey = typeKeyOf(r.refType),
                desc = r.memo,
                debit = r.debit,
                credit = r.credit,
                balance = running
            )
        }
        // [P33-P8] مجموع الخصومات قروش Long (COALESCE(SUM(discount),0)) — بلا round2
        val discounts = db.invoices().discountSumForPartyBetween(partyId, fromTs, toTs)
        val summary = StatementService.summarize(opening, rows, discounts)

        val now = System.currentTimeMillis()
        val seq = nextStatementSeq()
        val year = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR)
        val number = StatementService.statementNumber(seq, year)

        return StatementData(
            party = StatementPartyInfo(
                id = party.id, name = party.name, phone = party.phone.ifBlank { null },
                email = party.email, address = party.address, taxNumber = party.taxNumber,
                crNumber = party.crNumber, city = party.city, country = party.country,
                website = party.website, accountNumber = party.accountNumber,
                partyNo = StatementService.partyNumber(party.id)
            ),
            company = StatementCompanyInfo(
                businessName = s.businessName, ownerName = s.ownerName,
                phone = s.phone.ifBlank { null }, email = s.email.ifBlank { null },
                address = s.address.ifBlank { null }, city = s.city.ifBlank { null },
                country = s.country.ifBlank { null }, website = s.website.ifBlank { null },
                taxNumber = s.taxNumber.ifBlank { null }, crNumber = s.crNumber.ifBlank { null },
                companyNo = "SB-COMPANY-001",   // شركة واحدة — معرّف عرض ثابت (17-scan)
                logoPath = s.avatarPath?.ifBlank { null },
                photoPath = s.companyPhotoPath.ifBlank { null }
            ),
            fromTs = fromTs,
            toTs = toTs,
            currency = s.baseCurrency,
            rows = rows,
            summary = summary,
            statementNumber = number,
            verificationId = StatementService.verificationId(number, now),
            createdAt = now,
            note = note?.takeIf { it.isNotBlank() },
            lang = lang
        )
    }

    /**
     * إصدار كشف: حساب البصمة + إدراج statements + سطر تدقيق.
     * الـdedup (قرار ③ أعلاه): نفس الطرف والفترة ⇒ يعيد الكشف القائم بلا إدراج
     * (templateId/filePath الممرران يتجاهلان في هذه الحالة — موثق).
     */
    suspend fun issue(data: StatementData, templateId: String, filePath: String): StatementEntity {
        val existing = db.statements().findByPartyPeriod(data.party.id, data.fromTs, data.toTs)
        if (existing != null) return existing
        return insertStatement(data, templateId, filePath, "STATEMENT_ISSUE")
    }

    /**
     * إصدار قسري بلا dedup — لإعادة توليد PDF لنفس الفترة (قالب آخر/تصحيح مسار ملف).
     * إضافة صغيرة فوق العقد (لا تغير توقيع issue) — رقم جديد دائماً لأن UNIQUE
     * على statementNumber/verificationId يمنع صفّين بنفس الرقم.
     */
    suspend fun issueForced(data: StatementData, templateId: String, filePath: String): StatementEntity =
        insertStatement(data, templateId, filePath, "STATEMENT_REISSUE")

    private suspend fun insertStatement(
        data: StatementData, templateId: String, filePath: String, auditAction: String
    ): StatementEntity {
        val hash = StatementService.contentHash(data)
        val id = db.withTransaction {
            val sid = db.statements().insert(
                StatementEntity(
                    statementNumber = data.statementNumber,
                    verificationId = data.verificationId,
                    partyId = data.party.id,
                    fromTs = data.fromTs,
                    toTs = data.toTs,
                    templateId = templateId,
                    currency = data.currency,
                    contentHash = hash,
                    filePath = filePath,
                    note = data.note,
                    createdAt = data.createdAt,
                    lang = data.lang.name
                )
            )
            db.auditLog().insert(
                AuditLogEntity(
                    actor = "owner", action = auditAction,
                    details = "${data.statementNumber} party=${data.party.id} " +
                        "${data.fromTs}..${data.toTs} hash=$hash",
                    ts = System.currentTimeMillis()
                )
            )
            sid
        }
        return db.statements().findById(id)!!
    }

    /**
     * التسلسل التالي للترقيم — قرار ② أعلاه: MAX(الذيل الرقمي) + 1، وجدول فارغ ⇒ 1.
     * الذيل بعد آخر '-' فقط؛ أرقام أجنبية النسق تُتخطى بصمت (لا تفشل الترقيم كله).
     */
    suspend fun nextStatementSeq(): Long {
        var max = 0L
        for (n in db.statements().allStatementNumbers()) {
            val v = n.substringAfterLast('-', "").toLongOrNull() ?: continue
            if (v > max) max = v
        }
        return max + 1
    }

    // ═══════════ التسليم ═══════════

    /**
     * تسجيل تسليم كشف — سطر لكل (كشف، قناة) (قرار ④): التكرار يحدّث الموجود
     * (الحالة والمحاولات والخطأ والموعد المجدول) ويعيد معرّفه، فلا صفوف متضخمة
     * عند إعادة الجدولة. lastAttemptAt يُختم الآن دائماً.
     * + تدقيق STATEMENT_SEND.
     */
    suspend fun recordDelivery(
        statementId: Long,
        channel: String,
        status: String,
        attempts: Int,
        error: String? = null,
        scheduledFor: Long? = null
    ): Long = db.withTransaction {
        val now = System.currentTimeMillis()
        val dedup = "delivery:$statementId:$channel"
        val existing = db.statements().findByDedupKey(dedup)
        val id = if (existing != null) {
            db.statements().updateDelivery(
                id = existing.id, status = status, error = error,
                attempts = maxOf(existing.attempts, attempts),
                sentAt = existing.sentAt ?: if (status == "SENT") now else null,
                lastAttemptAt = now
            )
            existing.id
        } else {
            db.statements().insertDelivery(
                StatementDeliveryEntity(
                    statementId = statementId, channel = channel, status = status,
                    attempts = attempts, lastError = error, sentAt = if (status == "SENT") now else null,
                    scheduledFor = scheduledFor, dedupKey = dedup, lastAttemptAt = now
                )
            )
        }
        db.auditLog().insert(
            AuditLogEntity(
                actor = "owner", action = "STATEMENT_SEND",
                details = "statement=$statementId channel=$channel status=$status attempts=$attempts",
                ts = now
            )
        )
        id
    }

    /**
     * انتقال حالة تسليم — آلة الحالات الموثقة عند StatementDeliveryEntity:
     *   PENDING → PROCESSING → SENT | FAILED
     *   FAILED → RETRYING → PROCESSING (حلقة إعادة المحاولة)
     *   CANCELLED نهائية (لا تُقبل بعدها انتقالات منطقية هنا — الواجهة تمنعها)
     * قاعدة المحاولات: PROCESSING/SENT/FAILED تمثل محاولة فعلية ⇒ attempts+1؛
     * PENDING/RETRYING/CANCELLED مجدولة أو إدارية ⇒ بلا زيادة.
     * status=SENT يختم sentAt بالآن (مرة واحدة — لا يُعاد كتمه في كل قراءة).
     */
    suspend fun markDelivery(deliveryId: Long, status: String, error: String? = null) {
        val d = db.statements().findDeliveryById(deliveryId) ?: return
        val now = System.currentTimeMillis()
        val attempts = when (status) {
            "PROCESSING", "SENT", "FAILED" -> d.attempts + 1
            else -> d.attempts
        }
        val sentAt = if (status == "SENT") (d.sentAt ?: now) else d.sentAt
        db.statements().updateDelivery(deliveryId, status, error, attempts, sentAt, now)
    }

    // ═══════════ القوالب والتواقيع والأختام والملاحظات والقواعد ═══════════

    // ── قوالب الكشف ──
    fun templates(): Flow<List<StatementTemplateEntity>> = db.statementTemplates().all()
    suspend fun templatesOnce(): List<StatementTemplateEntity> = db.statementTemplates().allOnce()
    suspend fun template(id: Long): StatementTemplateEntity? = db.statementTemplates().byId(id)
    suspend fun saveTemplate(t: StatementTemplateEntity): Long = db.statementTemplates().upsert(t)
    suspend fun deleteTemplate(id: Long) = db.statementTemplates().delete(id)
    suspend fun setTemplateFavorite(id: Long, fav: Boolean) =
        db.statementTemplates().setFavorite(id, fav)

    /** الافتراضي الواحد — معاملة Room: مسح الكل ثم تعيين واحد ذرياً (لا لحظة بلا افتراضي ولا اثنين) */
    suspend fun setDefaultTemplate(id: Long) = db.withTransaction {
        db.statementTemplates().clearDefault()
        db.statementTemplates().markDefault(id)
    }

    /**
     * نسخ قالب باسم جديد — يحفظ baseTemplateId الأصلي (النسخة تنحدر من المدمج نفسه
     * لا من النسخة) وconfigJson كما هو، والافتراضي/المفضل لا يُورَّثان.
     */
    suspend fun duplicateTemplate(sourceId: Long, newName: String): Long {
        val src = db.statementTemplates().byId(sourceId)
            ?: throw IllegalArgumentException("template not found: $sourceId")
        return db.statementTemplates().upsert(
            src.copy(id = 0, name = newName, isDefault = false, favorite = false,
                createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
        )
    }

    // ── التواقيع ──
    fun signatures(): Flow<List<SignatureEntity>> = db.signatures().all()
    suspend fun signaturesOnce(): List<SignatureEntity> = db.signatures().allOnce()
    suspend fun signature(id: Long): SignatureEntity? = db.signatures().byId(id)
    suspend fun defaultSignature(): SignatureEntity? = db.signatures().defaultActive()
    suspend fun saveSignature(s: SignatureEntity): Long = db.signatures().upsert(s)
    suspend fun deleteSignature(id: Long) = db.signatures().delete(id)
    suspend fun setSignatureActive(id: Long, active: Boolean) = db.signatures().setActive(id, active)
    suspend fun setDefaultSignature(id: Long) = db.withTransaction {
        db.signatures().clearDefault(); db.signatures().markDefault(id)
    }

    // ── الأختام ──
    fun stamps(): Flow<List<StampEntity>> = db.stamps().all()
    suspend fun stampsOnce(): List<StampEntity> = db.stamps().allOnce()
    suspend fun stamp(id: Long): StampEntity? = db.stamps().byId(id)
    suspend fun defaultStamp(): StampEntity? = db.stamps().defaultActive()
    suspend fun saveStamp(s: StampEntity): Long = db.stamps().upsert(s)
    suspend fun deleteStamp(id: Long) = db.stamps().delete(id)
    suspend fun setStampActive(id: Long, active: Boolean) = db.stamps().setActive(id, active)
    suspend fun setDefaultStamp(id: Long) = db.withTransaction {
        db.stamps().clearDefault(); db.stamps().markDefault(id)
    }

    // ── قوالب الملاحظات ──
    fun noteTemplates(): Flow<List<NoteTemplateEntity>> = db.noteTemplates().all()
    suspend fun noteTemplatesOnce(): List<NoteTemplateEntity> = db.noteTemplates().allOnce()
    suspend fun noteTemplate(id: Long): NoteTemplateEntity? = db.noteTemplates().byId(id)
    suspend fun saveNoteTemplate(t: NoteTemplateEntity): Long = db.noteTemplates().upsert(t)
    suspend fun deleteNoteTemplate(id: Long) = db.noteTemplates().delete(id)
    suspend fun setDefaultNoteTemplate(id: Long) = db.withTransaction {
        db.noteTemplates().clearDefault(); db.noteTemplates().markDefault(id)
    }

    // ── قواعد التوليد التلقائي ──
    fun rules(): Flow<List<StatementRuleEntity>> = db.statementRules().all()
    suspend fun rulesOnce(): List<StatementRuleEntity> = db.statementRules().allOnce()
    suspend fun rule(id: Long): StatementRuleEntity? = db.statementRules().byId(id)
    suspend fun saveRule(r: StatementRuleEntity): Long = db.statementRules().upsert(r)
    suspend fun deleteRule(id: Long) = db.statementRules().delete(id)
    suspend fun setRuleEnabled(id: Long, enabled: Boolean) = db.statementRules().setEnabled(id, enabled)
    suspend fun markRuleRun(id: Long, lastRunAt: Long, nextRunAt: Long?) =
        db.statementRules().markRun(id, lastRunAt, nextRunAt)
    suspend fun dueRules(now: Long): List<StatementRuleEntity> = db.statementRules().dueRules(now)

    // ── الكشوف: قراءة تاريخية ──
    suspend fun statement(id: Long): StatementEntity? = db.statements().findById(id)
    suspend fun byVerificationId(v: String): StatementEntity? = db.statements().findByVerificationId(v)
    suspend fun byStatementNumber(n: String): StatementEntity? = db.statements().findByStatementNumber(n)
    suspend fun historyForParty(partyId: Long): List<StatementEntity> =
        db.statements().historyForParty(partyId)
    suspend fun listStatements(limit: Int): List<StatementEntity> = db.statements().listAll(limit)
    suspend fun deliveriesFor(statementId: Long): List<StatementDeliveryEntity> =
        db.statements().deliveriesFor(statementId)
    suspend fun deliveriesByStatus(status: String): List<StatementDeliveryEntity> =
        db.statements().deliveriesByStatus(status)

    /** حذف كشف — CASCADE يمحو سطور تسليمه؛ رقمه لا يعاد (قرار الترقيم ②) */
    suspend fun deleteStatement(id: Long) = db.withTransaction {
        db.statements().delete(id)
        db.auditLog().insert(
            AuditLogEntity(actor = "owner", action = "STATEMENT_DELETE",
                details = "id=$id", ts = System.currentTimeMillis())
        )
    }

    // ═══════════ التدقيق ═══════════

    /** سطر تدقيق — actor=owner دائماً اليوم (تطبيق مفرد المالك) مع بقاء الحقل للتوسع */
    suspend fun audit(action: String, details: String, actor: String = "owner") {
        db.auditLog().insert(
            AuditLogEntity(actor = actor, action = action, details = details,
                ts = System.currentTimeMillis())
        )
    }

    suspend fun auditRecent(limit: Int): List<AuditLogEntity> = db.auditLog().recent(limit)
    suspend fun auditByAction(action: String, limit: Int): List<AuditLogEntity> =
        db.auditLog().byAction(action, limit)

    // ═══════════ [P36-M4-8] IO الثقيل المنقول من StatementUiFacade ═══════════
    // موجة M4-8: الواجهة الرقيقة تُصبح منسّقاً نحيفاً — أجسام file/DB/SMTP IO هنا حصراً.
    // ملاحظة على عقد 17-a أعلاه («لا رسم PDF هنا»): الرسم يبقى في pdf/statement حصراً؛
    // ما أُضيف هنا قراءة ملفات PDF مولَّدة (معاينة/عدّ صفحات) وفك الشعار — IO ملفات لا رسم.

    /** [P36-M4-8] نظرة خفيفة على الطرف — كانت تُقرأ في الواجهة مباشرة عبر db.parties() */
    suspend fun partyById(partyId: Long): Party? = db.parties().byId(partyId)

    /** [P36-M4-8] أول طرف في القاعدة — لعيّنة مصغّرات الاستوديو (صدق فراغ: بلا أطراف ⇒ null) */
    suspend fun firstParty(): Party? = db.parties().allOnce().firstOrNull()

    /** [P36-M4-8] شعار الشركة من avatarPath — نقل حرفي لجسم الواجهة (قراءة حقيقية واحدة) */
    suspend fun loadLogo(): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val path = settings.snapshot().avatarPath
            path?.let { BitmapFactory.decodeFile(it) }
        } catch (_: Exception) { null }
    }

    // ── [P36-M4-8] صفوف التسليم: تجميع DB كان في الواجهة (N+1 مع مخزن أسماء) ──

    /** [P36-M4-8] تسليمات كشف واحد كصفوف بيانات — كان deliveriesFor بالواجهة يفعل هذا عبر rowsOf */
    suspend fun deliveryRowsForStatement(statementId: Long): List<StatementDeliveryRow> {
        val s = statement(statementId) ?: return emptyList()
        return assembleStatementDeliveryRows(
            listOf(s),
            deliveriesOf = { db.statements().deliveriesFor(it) },
            partyNameOf = { db.parties().byId(it)?.name }
        )
    }

    /**
     * [P36-M4-8] كل التسليمات الحديثة — الصدق المعماري الموثق محفوظ حرفياً:
     * statement_deliveries بلا استعلام «الكل» في مسار الواجهة، فتُجمَّع من أحدث
     * الكشوف (listStatements) ثم deliveriesFor لكل كشف. كشوف بلا تسليمات لا
     * تُنتج صفوفاً — وهذه الحقيقة تُعرض في الشاشة بصدق.
     */
    suspend fun deliveryRowsRecent(limit: Int): List<StatementDeliveryRow> =
        assembleStatementDeliveryRows(
            listStatements(limit),
            deliveriesOf = { db.statements().deliveriesFor(it) },
            partyNameOf = { db.parties().byId(it)?.name }
        )

    // ── [P36-M4-8] الإجراءات الحقيقية على التسليمات (منقولة حرفياً من الواجهة) ──

    /**
     * إعادة إرسال فوري عبر SMTP — العقد مع 18-a:
     * SmtpClient.send(config, to, subject, body, attachmentName, attachmentBytes)
     * حاجب يرمي SmtpException، يُغلّف بـ runCatching على Dispatchers.IO.
     * المسار بترتيبه الأصلي نفسه: قراءة السطر → فحص الملف → فحص القناة → فحص الإعداد
     * → فحص البريد → قراءة المرفق → RETRYING → إرسال → SENT|FAILED + تدقيق.
     * الإعداد (config) والقنوات القابلة لإعادة المحاولة تُمرَّران من الواجهة (جسر prefs
     * ولغة القنوات ملك للواجهة — لا يعرف repo حزمة ui) — قراءة prefs مبكرة بلا أثر جانبي.
     */
    suspend fun retryDeliveryNow(
        deliveryId: Long,
        config: SmtpConfig?,
        retryableChannels: Collection<String>
    ): Result<Unit> {
        val d = db.statements().findDeliveryById(deliveryId)
            ?: return Result.failure(IllegalStateException("delivery not found: $deliveryId"))
        val s = statement(d.statementId)
            ?: return Result.failure(IllegalStateException("statement not found: " + d.statementId))
        val file = File(s.filePath)
        if (!file.exists()) {
            markDelivery(deliveryId, "FAILED", "statement pdf file missing")
            return Result.failure(IllegalStateException("statement pdf file missing"))
        }
        if (d.channel !in retryableChannels) {
            return Result.failure(IllegalStateException("channel " + d.channel + " opens from the statement screen"))
        }
        if (config == null) return Result.failure(IllegalStateException("smtp not configured"))
        val to = runCatching {
            db.parties().byId(s.partyId)?.email?.trim()
        }.getOrNull().orEmpty()
        if (to.isBlank()) return Result.failure(IllegalStateException("party has no email"))
        val attachments = withContext(Dispatchers.IO) {
            runCatching { file.readBytes() }.getOrDefault(ByteArray(0))
        }
        markDelivery(deliveryId, "RETRYING", null)
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                SmtpClient.send(
                    config,
                    listOf(to),
                    "Statement " + s.statementNumber,
                    "Account statement attached.\nتم إرفاق كشف الحساب.\nVerification: " +
                        s.verificationId,
                    file.name,
                    attachments
                )
            }
        }
        return outcome.fold(
            onSuccess = {
                markDelivery(deliveryId, "SENT", null)
                audit(
                    "STATEMENT_SEND",
                    "delivery=$deliveryId statement=${s.id} channel=${d.channel} result=MANUAL_RETRY_SENT"
                )
                Result.success(Unit)
            },
            onFailure = { e ->
                val msg = (e.message ?: e.javaClass.simpleName).take(200)
                markDelivery(deliveryId, "FAILED", msg)
                audit(
                    "STATEMENT_SEND",
                    "delivery=$deliveryId statement=${s.id} channel=${d.channel} result=FAILED error=$msg"
                )
                Result.failure(e)
            }
        )
    }

    /**
     * إلغاء تسليم — مسموح فقط من PENDING/FAILED/RETRYING (آلة حالات:
     * CANCELLED نهائية). يعيد false مع بقاء الحالة كما هي عند رفض الانتقال.
     */
    suspend fun cancelDelivery(deliveryId: Long): Boolean {
        val d = db.statements().findDeliveryById(deliveryId) ?: return false
        if (d.status !in listOf("PENDING", "FAILED", "RETRYING")) return false
        markDelivery(deliveryId, "CANCELLED", null)
        audit("STATEMENT_SEND", "delivery=$deliveryId statement=${d.statementId} result=CANCELLED")
        return true
    }

    // ═══════════ أدوات داخلية ═══════════

    /**
     * خريطة refType الدفتري → typeKey الكشف (القيم موثقة عند StatementTxTypes).
     * journal بلا عمود type (اكتشاف موثق في Daos.kt) — refType هو الوسم الوحيد،
     * وكل ما هو غير معروف ("tax"/"cash"/"expense"/"stock"/"edit"/"void"/null) = ADJUST:
     * سطر على حساب الطرف يظهر بماله ونظيره لكنه ليس فاتورة/دفع/دين/شيك/قسطاً.
     */
    private fun typeKeyOf(refType: String?): String = when (refType) {
        "invoice" -> StatementTxTypes.INVOICE
        "payment" -> StatementTxTypes.PAYMENT
        "debt" -> StatementTxTypes.DEBT
        "check" -> StatementTxTypes.CHECK
        "plan" -> StatementTxTypes.INSTALLMENT
        else -> StatementTxTypes.ADJUST
    }
}

/**
 * [P36-M4-8] صف تسليم على مستوى البيانات — يدمج سطر التسليم مع بطاقة الكشف واسم الطرف.
 * يبنيه assembleStatementDeliveryRows وتحوّله واجهة الاستخدام إلى نموذجها المحلي
 * StatementUiFacade.DeliveryRow (خرائط حقول نقية بلا IO).
 */
data class StatementDeliveryRow(
    val id: Long,
    val statementId: Long,
    val statementNumber: String,
    val partyId: Long,
    val partyName: String,
    val filePath: String,
    val channel: String,
    val status: String,
    val attempts: Int,
    val lastError: String?,
    val sentAt: Long?,
    val scheduledFor: Long?,
    val lastAttemptAt: Long?
)

/**
 * [P36-M4-8] صفحة معاينة حقيقية من ملف PDF عبر android.graphics.pdf.PdfRenderer
 * (ما تراه هو ما سيُشارك/يُطبع فعلاً) — نقل حرفي لجسم StatementUiFacade.previewPage.
 * دالة علوية في طبقة repo (لا تحتاج قاعدة ولا إعدادات، وتوقيع الواجهة الأصلي بلا
 * Context فلا يستطيع الوصول لمثيل repo) — تعمل على Dispatchers.IO.
 */
suspend fun statementPreviewPage(file: File, pageIndex: Int, targetWidthPx: Int): Bitmap? =
    withContext(Dispatchers.IO) {
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@use null
                    renderer.openPage(pageIndex).use { page ->
                        val w = targetWidthPx.coerceAtLeast(64)
                        val h = (page.height.toLong() * w / page.width.toLong().coerceAtLeast(1)).toInt()
                            .coerceAtLeast(64)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

/** [P36-M4-8] عدد صفحات ملف PDF — 0 عند الفشل (نقل حرفي لجسم StatementUiFacade.pageCountOf) */
suspend fun statementPageCountOf(file: File): Int =
    withContext(Dispatchers.IO) {
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { it.pageCount }
            }
        } catch (_: Exception) { 0 }
    }

/**
 * [P36-M4-8] تجميع صفوف التسليم — نقل حرفي لـ rowsOf/partyNameOf من الواجهة،
 * كدالة علوية نقية قابلة للاختبار (بلا Room/Android): المزوّدات تُمرَّر lambdas،
 * مخزن أسماء الأطراف لكل نداء (كما كان)، والترتيب تنازلياً بآخر محاولة ثم آخر إرسال.
 */
internal suspend fun assembleStatementDeliveryRows(
    statements: List<StatementEntity>,
    deliveriesOf: suspend (Long) -> List<StatementDeliveryEntity>,
    partyNameOf: suspend (Long) -> String?
): List<StatementDeliveryRow> {
    val names = HashMap<Long, String>()
    val out = mutableListOf<StatementDeliveryRow>()
    for (s in statements) {
        val partyName = names.getOrPut(s.partyId) {
            runCatching { partyNameOf(s.partyId) }.getOrNull() ?: ""
        }
        deliveriesOf(s.id).forEach { d ->
            out += StatementDeliveryRow(
                id = d.id, statementId = s.id, statementNumber = s.statementNumber,
                partyId = s.partyId, partyName = partyName, filePath = s.filePath,
                channel = d.channel, status = d.status, attempts = d.attempts,
                lastError = d.lastError, sentAt = d.sentAt,
                scheduledFor = d.scheduledFor, lastAttemptAt = d.lastAttemptAt
            )
        }
    }
    return out.sortedByDescending { it.lastAttemptAt ?: it.sentAt ?: 0L }
}
