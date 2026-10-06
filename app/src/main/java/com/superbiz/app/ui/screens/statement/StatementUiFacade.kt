package com.superbiz.app.ui.screens.statement

import android.content.Context
import android.graphics.Bitmap
import java.io.File
// [P36-M4-8] كل IO الثقيل (قراءة PDF/شعار، SMTP، تجميع تسليمات، لمسات DAO) نُقل
// إلى طبقة repo (StatementRepo.kt) — هذه الواجهة صارت منسّقاً نحيفاً بتوقيعاتها القديمة.

/**
 * [P17-c] الواجهة الرقيقة بين شاشات كشف الحساب وعقد الطبقات الموازية:
 *  - 17-a: domain/statement + data/repo/StatementRepo.kt (+ StatementEntity + StatementService)
 *  - 17-b: pdf/statement (StatementTemplates / StatementTemplateDef / StatementStyle /
 *          PdfElement / TemplateCategory / StatementPdfRenderer)
 *
 * ⚠️ كل ملامسة لأنواع العقد معلَّمة بـ [contract ADAPT] — أي انحراف في التفاصيل
 * النهائية لـ worklog 17-a/17-b يُصلَح هنا في سطر واحد دون لمس الشاشات.
 * لا Mock إطلاقاً: كل البيانات من repo/DAO/prefs الحقيقية.
 *
 * [P36-M4-8] موجة M4-8: أجسام IO الثقيلة كانت هنا (withContext(IO)/PdfRenderer/
 * BitmapFactory/قراءة مرفق SMTP/تجميع تسليمات N+1/لمسات db مباشرة) فنُقلت حرفياً
 * إلى StatementRepo (+ دوالّه العلوية النقية) — توقيعات الدوالّ العامة بلا أي تغيير
 * فلا يتأثر أي موقع نداء، وrenderPdf بقي تمريراً نحيفاً لأن StatementPdfRenderer.render
 * يغلّف IO بـ withContext(Dispatchers.IO) داخلياً وrepo لا يعرف حزمة pdf بعقد 17-a.
 */
object StatementUiFacade {

    // ═══════════ نماذج واجهة محلية (معزولة عن العقد) ═══════════

    /** لغة الكشف — أسماء مضاهية لـ StatementLang من العقد */
    enum class UiLang { AR, EN, BILINGUAL }

    data class RenderedPdf(val file: File, val pageCount: Int)

    /** صف سجل الكشوف الصادرة — يُبنى من StatementEntity في نقطة تكيف واحدة */
    data class HistoryUi(
        val id: Long,
        val number: String,
        val verificationId: String,
        val fromTs: Long,
        val toTs: Long,
        val filePath: String,
        val createdAt: Long
    )

    data class SignatureUi(
        val id: Long, val name: String, val jobTitle: String,
        val filePath: String, val isDefault: Boolean
    )

    data class StampUi(
        val id: Long, val name: String, val filePath: String, val isDefault: Boolean
    )

    data class NoteTemplateUi(val id: Long, val title: String, val body: String)

    /**
     * نموذج محرر النمط — تحويله من/إلى StatementStyle يحدث في نقطتَي [contract ADAPT]
     * بالأسفل فقط. الألوان ARGB int، والأحجام dp/sp، وفهارس الخيارات 0..4.
     */
    data class StyleUiModel(
        val show: Set<String>,
        val colorPrimary: Int,
        val colorAccent: Int,
        val colorText: Int,
        val colorHeaderBg: Int,
        val colorRowAlt: Int,
        val titleSizeSp: Float,
        val bodySizeSp: Float,
        val marginDp: Float,
        val spacingDp: Float,
        val landscape: Boolean,
        val headerStyle: Int,
        val tableStyle: Int,
        val summaryStyle: Int,
        val footerStyle: Int,
        val signatureCorner: Int,
        val signatureScale: Float,
        val signatureAlpha: Float
    )

    // ── العناصر الثمانية السريعة — [contract ADAPT] أسماء من تعداد PdfElement الحقيقي
    //    (StatementStyle.kt من 17-b): TX_TABLE هي جدول الحركات وFINAL_BALANCE هي بطاقة
    //    الرصيد النهائي التي كانت تُسمّى «الملخص» في المسودة الأولى ──
    val QUICK_ELEMENT_NAMES = listOf(
        "LOGO", "PARTY_INFO", "PERIOD", "TX_TABLE", "FINAL_BALANCE", "NOTES", "SIGNATURE", "STAMP"
    )

    // ═══════════ [contract ADAPT] نقاط اللمس الوحيدة مع 17-a ═══════════

    // [contract ADAPT] المصلحة الحقيقية عند 17-a: AppGraph.statements = StatementRepo(db, ledger, settings)
    private fun repo(context: Context): com.superbiz.app.data.repo.StatementRepo =
        com.superbiz.app.AppGraph.from(context.applicationContext).statements

    /** نظرة خفيفة على الطرف — DAO قائم بلا تجميع كشف كامل */
    suspend fun partyBrief(context: Context, partyId: Long): com.superbiz.app.data.db.Party? =
        repo(context).partyById(partyId) // [P36-M4-8] قراءة DAO نُقلت إلى repo

    /** ربط UiLang ← StatementLang (الاسم مطابق؛ ADAPT عند التغيير) */
    private fun langOf(l: UiLang): com.superbiz.app.domain.statement.StatementLang =
        com.superbiz.app.domain.statement.StatementLang.valueOf(l.name)

    /** تجميع بيانات الكشف عبر 17-a */
    suspend fun assemble(
        context: Context, partyId: Long, fromTs: Long, toTs: Long,
        lang: UiLang, note: String?
    ): com.superbiz.app.domain.statement.StatementData =
        repo(context).assemble(partyId, fromTs, toTs, langOf(lang), note)

    /** إصدار الكشف رسمياً (issue) وإرجاع صف سجل جاهز للعرض */
    suspend fun issue(
        context: Context, data: com.superbiz.app.domain.statement.StatementData,
        templateId: String, finalFile: File
    ): HistoryUi {
        // [contract ADAPT] issue(data, templateId, filePath): StatementEntity — الحقل statementNumber
        val e = repo(context).issue(data, templateId, finalFile.absolutePath)
        return HistoryUi(
            id = e.id,
            number = e.statementNumber,
            verificationId = e.verificationId,
            fromTs = e.fromTs,
            toTs = e.toTs,
            filePath = e.filePath,
            createdAt = e.createdAt
        )
    }

    /** سجل كشوف الطرف */
    suspend fun historyFor(context: Context, partyId: Long): List<HistoryUi> {
        // [contract ADAPT] historyForParty(partyId): List<StatementEntity> — الحقول المقرؤة:
        // id/statementNumber/verificationId/fromTs/toTs/filePath/createdAt
        return repo(context).historyForParty(partyId).map { e ->
            HistoryUi(
                id = e.id, number = e.statementNumber, verificationId = e.verificationId,
                fromTs = e.fromTs, toTs = e.toTs, filePath = e.filePath, createdAt = e.createdAt
            )
        }
    }

    // ── التوقيعات / الأختام / قوالب الملاحظات ──
    // [contract ADAPT] أسماء CRUD الحقيقية عند 17-a (saveSignature/saveStamp + *Once) —
    // imagePath في الكيان يُعرض filePath في نماذج الواجهة المحلية
    suspend fun signatures(context: Context): List<SignatureUi> =
        repo(context).signaturesOnce().map {
            SignatureUi(it.id, it.name, it.jobTitle ?: "", it.imagePath, it.isDefault)
        }

    suspend fun addSignature(
        context: Context, name: String, jobTitle: String, filePath: String
    ): Long = repo(context).saveSignature(
        com.superbiz.app.data.db.SignatureEntity(
            name = name.trim(), jobTitle = jobTitle.trim().ifBlank { null }, imagePath = filePath
        )
    )

    suspend fun deleteSignature(context: Context, id: Long) { repo(context).deleteSignature(id) }

    suspend fun setDefaultSignature(context: Context, id: Long) { repo(context).setDefaultSignature(id) }

    suspend fun stamps(context: Context): List<StampUi> =
        repo(context).stampsOnce().map { StampUi(it.id, it.name, it.imagePath, it.isDefault) }

    suspend fun addStamp(context: Context, name: String, filePath: String): Long =
        repo(context).saveStamp(
            com.superbiz.app.data.db.StampEntity(name = name.trim(), imagePath = filePath)
        )

    suspend fun deleteStamp(context: Context, id: Long) { repo(context).deleteStamp(id) }

    suspend fun setDefaultStamp(context: Context, id: Long) { repo(context).setDefaultStamp(id) }

    suspend fun noteTemplates(context: Context): List<NoteTemplateUi> =
        repo(context).noteTemplatesOnce().map { NoteTemplateUi(it.id, it.title, it.body) }

    // ═══════════ [contract ADAPT] نقاط اللمس الوحيدة مع 17-b ═══════════

    /** القوالب المدمجة الخمسون (17-b) — المخصصات تُقرأ من القاعدة عبر customTemplates */
    fun allTemplates(): List<com.superbiz.app.pdf.statement.StatementTemplateDef> =
        com.superbiz.app.pdf.statement.StatementTemplates.ALL

    /** قالب مدمج بالمعرّف فقط (بلا Context) — للمخصصات استخدم templateByIdOf */
    fun templateById(id: String): com.superbiz.app.pdf.statement.StatementTemplateDef? =
        com.superbiz.app.pdf.statement.StatementTemplates.byId(id)

    val defaultTemplateId: String
        get() = com.superbiz.app.pdf.statement.StatementTemplates.DEFAULT_ID

    // ── القوالب المخصصة: كيانات statement_templates لدى 17-a تُعرض كـStatementTemplateDef ──
    // معرّف الواجهة "CUSTOM-<dbId>" يميّز النسخ المخصصة عن المدمجات (COR-01…) بلا أي التباس.
    private const val CUSTOM_PREFIX = "CUSTOM-"

    private fun customIdOf(dbId: Long): String = CUSTOM_PREFIX + dbId

    private fun customDbIdOf(id: String): Long? =
        if (id.startsWith(CUSTOM_PREFIX)) id.removePrefix(CUSTOM_PREFIX).toLongOrNull() else null

    /**
     * القوالب المخصصة المخزنة لدى 17-a — كل نسخة تُبنى على نمط قاعدتها المدمجة
     * (baseTemplateId) مع إعدادات configJson المصلونة JSON يدوياً هنا (org.json
     * من زمن التشغيل — لا تبعية تسلسل جديدة).
     */
    suspend fun customTemplates(
        context: Context
    ): List<com.superbiz.app.pdf.statement.StatementTemplateDef> =
        try {
            val defaults = com.superbiz.app.pdf.statement.StatementTemplates
            val fallback = defaults.byId(defaults.DEFAULT_ID)?.style
                ?: allTemplates().first().style
            repo(context).templatesOnce().map { e ->
                val base = defaults.byId(e.baseTemplateId)
                com.superbiz.app.pdf.statement.StatementTemplateDef(
                    id = customIdOf(e.id),
                    cat = base?.cat ?: com.superbiz.app.pdf.statement.TemplateCategory.BUSINESS,
                    nameAr = e.name, nameEn = e.name,
                    descAr = base?.nameAr ?: "", descEn = base?.nameEn ?: "",
                    style = decodeStyle(e.configJson, base?.style ?: fallback)
                )
            }
        } catch (_: Exception) { emptyList() }

    /** القائمة الكاملة (مدمجة + مخصصة) — للاستوديو ولبحث المعرّفات السياقي */
    suspend fun allTemplatesOf(
        context: Context
    ): List<com.superbiz.app.pdf.statement.StatementTemplateDef> =
        allTemplates() + customTemplates(context)

    /** بحث سياقي: مدمج ثم مخصص */
    suspend fun templateByIdOf(
        context: Context, id: String
    ): com.superbiz.app.pdf.statement.StatementTemplateDef? =
        templateById(id) ?: customTemplates(context).firstOrNull { it.id == id }

    /** TemplateUi للعرض — الفئة باسمها الثابت من العقد */
    fun templateUiOf(
        def: com.superbiz.app.pdf.statement.StatementTemplateDef,
        favorites: Set<String>, defId: String
    ): TemplateUi = TemplateUi(
        id = def.id, nameAr = def.nameAr, nameEn = def.nameEn,
        descAr = def.descAr, descEn = def.descEn,
        category = def.cat.name,
        // المدمج = معرّفه في قائمة ALL؛ المخصص ما أُنشئ بالنسخ (بادئة CUSTOM-)
        isBuiltIn = allTemplates().any { it.id == def.id },
        isFavorite = def.id in favorites, isDefault = def.id == defId
    )

    /** أسماء الفئات الثابتة من العقد (لرقائق الاستوديو) */
    fun categoryNames(): List<String> =
        allTemplates().map { it.cat.name }.distinct()

    /** تحويل آمن اسم عنصر — الاسم غير الموجود لدى 17-b يُتخطى */
    fun elementOf(name: String): com.superbiz.app.pdf.statement.PdfElement? =
        runCatching { com.superbiz.app.pdf.statement.PdfElement.valueOf(name) }.getOrNull()

    fun allElementNames(): List<String> =
        runCatching {
            com.superbiz.app.pdf.statement.PdfElement.entries.map { it.name }
        }.getOrDefault(QUICK_ELEMENT_NAMES)

    // ── StatementStyle ↔ StyleUiModel ──
    // [contract ADAPT] الحقول الحقيقية لـStatementStyle من 17-b:
    //   header/table/summary/footer: تعدادات خماسية، primary/secondary/text/tableHeadText/rowAlt: Int،
    //   headingFontScale/bodyFontScale: Float (مضاعفا 15pt/10pt الأساسيين)، marginDp/spacingDp: Int،
    //   landscape: Boolean، signaturePos/stampPos: CornerPos، logoSizeScale/stampSizeScale/
    //   signatureSizeScale: Float، signatureOpacity/stampOpacity: Int (0..255)، show: Set<PdfElement>
    // نموذج الواجهة StyleUiModel يبقى كما صُمم للشاشة (ألوان/أحجام sp/فهارس 0..4) والتحويل هنا حصراً.
    fun styleUiOf(s: com.superbiz.app.pdf.statement.StatementStyle): StyleUiModel = StyleUiModel(
        show = s.show.map { it.name }.toSet(),
        colorPrimary = s.primary, colorAccent = s.secondary, colorText = s.text,
        colorHeaderBg = s.tableHeadText, colorRowAlt = s.rowAlt,
        // مضاعفات المحرك ← أحجام sp على الأساسيين 15pt للعناوين و10pt للمتن
        titleSizeSp = s.headingFontScale * 15f, bodySizeSp = s.bodyFontScale * 10f,
        marginDp = s.marginDp.toFloat(), spacingDp = s.spacingDp.toFloat(),
        landscape = s.landscape,
        headerStyle = s.header.ordinal, tableStyle = s.table.ordinal,
        summaryStyle = s.summary.ordinal, footerStyle = s.footer.ordinal,
        signatureCorner = s.signaturePos.ordinal,
        signatureScale = s.signatureSizeScale, signatureAlpha = s.signatureOpacity / 255f
    )

    fun applyStyleUi(
        base: com.superbiz.app.pdf.statement.StatementStyle, ui: StyleUiModel
    ): com.superbiz.app.pdf.statement.StatementStyle = base.copy(
        show = ui.show.mapNotNull { elementOf(it) }.toSet(),
        primary = ui.colorPrimary, secondary = ui.colorAccent, text = ui.colorText,
        tableHeadText = ui.colorHeaderBg, rowAlt = ui.colorRowAlt,
        headingFontScale = ui.titleSizeSp / 15f, bodyFontScale = ui.bodySizeSp / 10f,
        marginDp = ui.marginDp.toInt().coerceIn(0, 120), spacingDp = ui.spacingDp.toInt().coerceIn(0, 96),
        landscape = ui.landscape,
        header = enumAt<com.superbiz.app.pdf.statement.HeaderStyle>(ui.headerStyle, base.header),
        table = enumAt<com.superbiz.app.pdf.statement.TableStyle>(ui.tableStyle, base.table),
        summary = enumAt<com.superbiz.app.pdf.statement.SummaryStyle>(ui.summaryStyle, base.summary),
        footer = enumAt<com.superbiz.app.pdf.statement.FooterStyle>(ui.footerStyle, base.footer),
        signaturePos = enumAt<com.superbiz.app.pdf.statement.CornerPos>(ui.signatureCorner, base.signaturePos),
        signatureSizeScale = ui.signatureScale.coerceIn(0.1f, 2f),
        signatureOpacity = (ui.signatureAlpha.coerceIn(0f, 1f) * 255f).toInt()
    )

    /** فهرس آمن لتعداد — خارج المدى يعيد القيمة الأساسية بلا انفجار */
    private inline fun <reified T : Enum<T>> enumAt(index: Int, fallback: T): T =
        runCatching { enumValues<T>()[index] }.getOrDefault(fallback)

    // ═══════════ تسلسل StatementStyle JSON (لعمود configJson لدى 17-a) ═══════════

    /** ترميز النمط إلى configJson — أسماء الحقول تعكس العقد حرفياً */
    fun encodeStyle(s: com.superbiz.app.pdf.statement.StatementStyle): String =
        org.json.JSONObject().apply {
            put("header", s.header.name); put("table", s.table.name)
            put("summary", s.summary.name); put("footer", s.footer.name)
            put("primary", s.primary); put("secondary", s.secondary)
            put("text", s.text); put("tableHeadText", s.tableHeadText); put("rowAlt", s.rowAlt)
            put("headingFontScale", s.headingFontScale.toDouble())
            put("bodyFontScale", s.bodyFontScale.toDouble())
            put("marginDp", s.marginDp); put("spacingDp", s.spacingDp)
            put("landscape", s.landscape)
            put("signaturePos", s.signaturePos.name); put("stampPos", s.stampPos.name)
            put("logoSizeScale", s.logoSizeScale.toDouble())
            put("stampSizeScale", s.stampSizeScale.toDouble())
            put("signatureSizeScale", s.signatureSizeScale.toDouble())
            put("signatureOpacity", s.signatureOpacity); put("stampOpacity", s.stampOpacity)
            put("show", org.json.JSONArray(s.show.map { it.name }))
        }.toString()

    /**
     * فك configJson فوق نمط قاعدة — أي مفتاح غائب/تالف يبقى بقيمة القاعدة،
     * فلا يفشل فتح قالب قديم مهما كان JSON ناقصاً.
     */
    fun decodeStyle(
        json: String, base: com.superbiz.app.pdf.statement.StatementStyle
    ): com.superbiz.app.pdf.statement.StatementStyle = try {
        val o = org.json.JSONObject(json)
        fun en(name: String, cur: Enum<*>): String = o.optString(name, cur.name)
        fun enSet(key: String, cur: Set<com.superbiz.app.pdf.statement.PdfElement>) =
            o.optJSONArray(key)?.let { arr ->
                (0 until arr.length()).mapNotNull { elementOf(arr.optString(it)) }.toSet()
            }?.ifEmpty { cur } ?: cur
        base.copy(
            header = com.superbiz.app.pdf.statement.HeaderStyle.valueOf(en("header", base.header)),
            table = com.superbiz.app.pdf.statement.TableStyle.valueOf(en("table", base.table)),
            summary = com.superbiz.app.pdf.statement.SummaryStyle.valueOf(en("summary", base.summary)),
            footer = com.superbiz.app.pdf.statement.FooterStyle.valueOf(en("footer", base.footer)),
            primary = o.optInt("primary", base.primary),
            secondary = o.optInt("secondary", base.secondary),
            text = o.optInt("text", base.text),
            tableHeadText = o.optInt("tableHeadText", base.tableHeadText),
            rowAlt = o.optInt("rowAlt", base.rowAlt),
            headingFontScale = o.optDouble("headingFontScale", base.headingFontScale.toDouble()).toFloat(),
            bodyFontScale = o.optDouble("bodyFontScale", base.bodyFontScale.toDouble()).toFloat(),
            marginDp = o.optInt("marginDp", base.marginDp),
            spacingDp = o.optInt("spacingDp", base.spacingDp),
            landscape = o.optBoolean("landscape", base.landscape),
            signaturePos = com.superbiz.app.pdf.statement.CornerPos.valueOf(en("signaturePos", base.signaturePos)),
            stampPos = com.superbiz.app.pdf.statement.CornerPos.valueOf(en("stampPos", base.stampPos)),
            logoSizeScale = o.optDouble("logoSizeScale", base.logoSizeScale.toDouble()).toFloat(),
            stampSizeScale = o.optDouble("stampSizeScale", base.stampSizeScale.toDouble()).toFloat(),
            signatureSizeScale = o.optDouble("signatureSizeScale", base.signatureSizeScale.toDouble()).toFloat(),
            signatureOpacity = o.optInt("signatureOpacity", base.signatureOpacity),
            stampOpacity = o.optInt("stampOpacity", base.stampOpacity),
            show = enSet("show", base.show)
        )
    } catch (_: Exception) { base }

    // ═══════════ عمليات القوالب المخصصة عبر 17-a ═══════════

    /**
     * نسخة مخصصة جديدة من قالب (مدمج أو مخصص) — يعيد معرّف الواجهة "CUSTOM-<dbId>".
     * نسخ المخصص يمرر sourceId الرقمي للـrepo كي يحفظ baseTemplateId الأصلي (17-a).
     */
    suspend fun duplicateTemplate(context: Context, sourceId: String, newName: String): String {
        val r = repo(context)
        val newDbId = customDbIdOf(sourceId)?.let { dbId ->
            // [contract ADAPT] duplicateTemplate(sourceId: Long, newName): Long — يحفظ baseTemplateId
            r.duplicateTemplate(dbId, newName)
        } ?: run {
            val src = templateById(sourceId)
                ?: throw IllegalArgumentException("statement template not found: $sourceId")
            r.saveTemplate(
                com.superbiz.app.data.db.StatementTemplateEntity(
                    name = newName, baseTemplateId = sourceId, configJson = encodeStyle(src.style)
                )
            )
        }
        return customIdOf(newDbId)
    }

    /** حذف قالب مخصص — المدمجات محميّة (لا حذف لمعرّف بلا بادئة CUSTOM-) */
    suspend fun deleteTemplate(context: Context, id: String) {
        val dbId = customDbIdOf(id) ?: return
        try { repo(context).deleteTemplate(dbId) } catch (_: Exception) { }
    }

    /** حفظ نمط قالب مخصص — التسلسل JSON بيد هذه الواجهة عبر encodeStyle */
    suspend fun saveTemplateConfig(
        context: Context, id: String,
        def: com.superbiz.app.pdf.statement.StatementTemplateDef
    ) {
        val dbId = customDbIdOf(id) ?: throw IllegalArgumentException("not a custom template: $id")
        val r = repo(context)
        val e = r.template(dbId) ?: throw IllegalArgumentException("template row missing: $id")
        r.saveTemplate(e.copy(configJson = encodeStyle(def.style), updatedAt = System.currentTimeMillis()))
    }

    // ═══════════ توليد PDF والمعاينة الحية (17-b) ═══════════

    /** مجلد الإخراج الرسمي — مساره مغطى في file_paths.xml للمشاركة عبر FileProvider */
    fun statementsDir(context: Context): File =
        File(context.filesDir, "pdfs/statements").apply { mkdirs() }

    /** مجلد المعاينة المؤقت — cache وليس files (لا مشاركة مباشرة منه) */
    fun previewDir(context: Context): File =
        File(context.cacheDir, "statement_preview").apply { mkdirs() }

    /** اسم ملف نهائي آمن — [contract ADAPT] StatementService.safeFileName لدى 17-a بتوقيع
     *  (partyName, fromTs, toTs) بلا رقم، فيُبنى الاسم هنا: طرف_رقم_طابع-زمني.pdf */
    fun fileNameFor(partyName: String, number: String, ts: Long): String =
        sanitizeFileName("${partyName}_$number") + "_" + ts + ".pdf"

    /** توليد PDF فعلي عبر StatementPdfRenderer */
    suspend fun renderPdf(
        context: Context,
        data: com.superbiz.app.domain.statement.StatementData,
        style: com.superbiz.app.pdf.statement.StatementStyle,
        logo: Bitmap?, signature: Bitmap?, stamp: Bitmap?, companyPhoto: Bitmap?,
        outDir: File, fileName: String
    ): RenderedPdf {
        // [contract ADAPT] ترتيب معاملات render كما في عقد 17-b
        val r = com.superbiz.app.pdf.statement.StatementPdfRenderer.render(
            context, data, style, logo, signature, stamp, companyPhoto, outDir, fileName
        )
        return RenderedPdf(r.file, r.pageCount)
    }

    /**
     * المعاينة الحية = صفحة حقيقية من الملف المولَّد عبر android.graphics.pdf.PdfRenderer
     * (ما تراه هو ما سيُشارك/يُطبع فعلاً). يُستدعى على Dispatchers.IO فقط.
     * [P36-M4-8] جسم IO نُقل إلى الدالة العلوية statementPreviewPage في طبقة repo
     * (التوقيع الأصلي بلا Context فلا يصل لمثيل repo) — سلوك مطابق حرفياً.
     */
    suspend fun previewPage(file: File, pageIndex: Int, targetWidthPx: Int): Bitmap? =
        com.superbiz.app.data.repo.statementPreviewPage(file, pageIndex, targetWidthPx)

    /** عدد صفحات ملف PDF — 0 عند الفشل — [P36-M4-8] الجسم نُقل إلى statementPageCountOf */
    suspend fun pageCountOf(file: File): Int =
        com.superbiz.app.data.repo.statementPageCountOf(file)

    /** شعار الشركة من صورة الملف الشخصي (avatarPath) — قراءة حقيقية واحدة — [P36-M4-8] الجسم نُقل إلى repo */
    suspend fun loadLogo(context: Context): Bitmap? =
        repo(context).loadLogo()

    /** بيانات عيّنة حقيقية لمصغّرات الاستوديو — أول طرف فعلي في القاعدة؛ بلا أطراف ⇒ null (صدق فراغ) */
    suspend fun sampleDataForThumbnails(
        context: Context
    ): com.superbiz.app.domain.statement.StatementData? {
        return try {
            // [P36-M4-8] قراءة DAO نُقلت إلى repo — منطق الفترة الزمنية يبقى منطق واجهة هنا
            val party = repo(context).firstParty() ?: return null
            val (from, to) = resolvePeriod(
                UiPeriodPreset.LAST_3_MONTHS,
                java.util.Calendar.getInstance()
            )
            assemble(context, party.id, from, to, UiLang.BILINGUAL, null)
        } catch (_: Exception) { null }
    }

    // ═══════════ prefs «statement_prefs» (خاصة بالواجهة — نقطة توثيق للتكامل) ═══════════
    // مفاتيح مشتركة مع 17-a إن اعتمدها: default_template / default_note / identity_* / stamp_*

    private fun prefs(context: Context) =
        context.getSharedPreferences("statement_prefs", Context.MODE_PRIVATE)

    fun defaultTemplateIdPref(context: Context): String? =
        prefs(context).getString("default_template", null)

    fun setDefaultTemplateIdPref(context: Context, id: String) {
        prefs(context).edit().putString("default_template", id).apply()
    }

    fun favorites(context: Context): Set<String> =
        prefs(context).getStringSet("favorites", emptySet()) ?: emptySet()

    fun toggleFavorite(context: Context, id: String): Set<String> {
        val cur = favorites(context).toMutableSet()
        if (!cur.add(id)) cur.remove(id)
        prefs(context).edit().putStringSet("favorites", cur).apply()
        return cur
    }

    /** الملاحظة الافتراضية (initial لصندوق الملاحظات في الشاشة الرئيسية) */
    fun defaultNote(context: Context): String = prefs(context).getString("default_note", "") ?: ""

    fun setDefaultNote(context: Context, note: String) {
        prefs(context).edit().putString("default_note", note).apply()
    }

    /** حقول هوية الشركة الإضافية (city/country/website/crNumber/companyPhoto) —
     *  تقرأها شاشات الواجهة، ويستطيع 17-a في assemble قراءتها بنفس المفاتيح */
    fun identityExtras(context: Context): Map<String, String> =
        decodeExtras(prefs(context).getString("identity_extras", "") ?: "")

    fun setIdentityExtras(context: Context, map: Map<String, String>) {
        prefs(context).edit().putString("identity_extras", encodeExtras(map)).apply()
    }

    /** حقول الطرف الإضافية (email/address/taxNumber/crNumber/city/country/website/accountNumber) */
    fun partyExtras(context: Context, partyId: Long): Map<String, String> =
        decodeExtras(prefs(context).getString("party_extra_$partyId", "") ?: "")

    fun setPartyExtras(context: Context, partyId: Long, map: Map<String, String>) {
        prefs(context).edit().putString("party_extra_$partyId", encodeExtras(map)).apply()
    }

    /** قيم الختم الافتراضية (نسبة الحجم 0.2..1.0 والشفافية 0..1) */
    data class StampDefaults(val size: Float, val alpha: Float)

    fun stampDefaults(context: Context): StampDefaults {
        val p = prefs(context)
        return StampDefaults(
            size = p.getFloat("stamp_size", 0.5f),
            alpha = p.getFloat("stamp_alpha", 0.85f)
        )
    }

    fun setStampDefaults(context: Context, size: Float, alpha: Float) {
        prefs(context).edit().putFloat("stamp_size", size).putFloat("stamp_alpha", alpha).apply()
    }

    // ═══════════ [P18-c] التسليمات + إعادة المحاولة + القواعد + التدقيق + prefs ═══════════
    // كل ملامسة للعقد هنا حصراً — الشاشات الجديدة الثلاث لا تلمس repo/SmtpClient مباشرة
    // إلا عبر هذا الجسم (لغة 17-c نفسها).

    /** صف تسليم معروض — يدمج سطر التسليم مع بطاقة الكشف والطرف في صف واحد */
    data class DeliveryRow(
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

    // [P36-M4-8] تجميع DB (rowsOf/partyNameOf مع مخزن الأسماء) نُقل إلى repo —
    // (assembleStatementDeliveryRows + StatementDeliveryRow) — الواجهة تحوّل الصف فقط.
    private fun com.superbiz.app.data.repo.StatementDeliveryRow.toUi(): DeliveryRow = DeliveryRow(
        id = id, statementId = statementId, statementNumber = statementNumber,
        partyId = partyId, partyName = partyName, filePath = filePath,
        channel = channel, status = status, attempts = attempts,
        lastError = lastError, sentAt = sentAt,
        scheduledFor = scheduledFor, lastAttemptAt = lastAttemptAt
    )

    /** تسليمات كشف واحد */
    suspend fun deliveriesFor(context: Context, statementId: Long): List<DeliveryRow> =
        repo(context).deliveryRowsForStatement(statementId).map { it.toUi() } // [P36-M4-8]

    /**
     * كل التسليمات الحديثة — صدق معماري: statement_deliveries بلا استعلام «الكل»
     * لدى 17-a، فتُجمَّع من أحدث الكشوف (listStatements) ثم deliveriesFor لكل كشف.
     * كشوف بلا تسليمات لا تُنتج صفوفاً — وهذه الحقيقة تُعرض في الشاشة بصدق.
     * [P36-M4-8] جسم التجميع N+1 نُقل حرفياً إلى repo (deliveryRowsRecent).
     */
    suspend fun deliveriesAll(context: Context, limit: Int = 200): List<DeliveryRow> =
        repo(context).deliveryRowsRecent(limit).map { it.toUi() }

    // ── القواعد والتدقيق: تفويض مباشر (نقطة لمس واحدة للعقد) ──

    fun rules(context: Context): kotlinx.coroutines.flow.Flow<List<com.superbiz.app.data.db.StatementRuleEntity>> =
        repo(context).rules()

    suspend fun rulesOnce(context: Context): List<com.superbiz.app.data.db.StatementRuleEntity> =
        repo(context).rulesOnce()

    suspend fun saveRule(context: Context, rule: com.superbiz.app.data.db.StatementRuleEntity): Long =
        repo(context).saveRule(rule).also {
            repo(context).audit(
                "STATEMENT_RULE_SAVE",
                "rule=$it name=${rule.name.take(60)} mode=${rule.partyMode} freq=${rule.frequency}"
            )
        }

    suspend fun deleteRule(context: Context, id: Long) {
        repo(context).deleteRule(id)
        repo(context).audit("STATEMENT_RULE_DELETE", "rule=$id")
    }

    suspend fun setRuleEnabled(context: Context, id: Long, enabled: Boolean) =
        repo(context).setRuleEnabled(id, enabled)

    suspend fun auditRecent(context: Context, limit: Int): List<com.superbiz.app.data.db.AuditLogEntity> =
        repo(context).auditRecent(limit)

    suspend fun auditByAction(
        context: Context, action: String, limit: Int
    ): List<com.superbiz.app.data.db.AuditLogEntity> =
        repo(context).auditByAction(action, limit)

    /** صفوف القوالب المخزنة (لقائمة اختيار القالب في القواعد) — المخصصات بمعرّف CUSTOM-<dbId> */
    suspend fun templateRowsOnce(context: Context): List<com.superbiz.app.data.db.StatementTemplateEntity> =
        runCatching { repo(context).templatesOnce() }.getOrDefault(emptyList())

    // ── جسر prefs (StatementPrefs من domain.statement — لمسة وحيدة من الواجهة) ──

    fun smtpPrefs(context: Context): com.superbiz.app.domain.statement.SmtpPrefsUi =
        com.superbiz.app.domain.statement.StatementPrefs.load(context)

    fun saveSmtpPrefs(
        context: Context, ui: com.superbiz.app.domain.statement.SmtpPrefsUi
    ) = com.superbiz.app.domain.statement.StatementPrefs.save(context, ui)

    /** إعداد SMTP جاهز للإرسال أو null — سبب null يفحصه المتصل بصدق */
    fun smtpConfigOrNull(context: Context): com.superbiz.app.domain.statement.SmtpConfig? =
        com.superbiz.app.domain.statement.StatementPrefs.smtpConfig(context)

    // ── الإجراءات الحقيقية ──

    /**
     * إعادة إرسال فوري عبر SMTP — العقد مع 18-a:
     * SmtpClient.send(config, to, subject, body, attachmentName, attachmentBytes)
     * حاجب يرمي SmtpException — يُغلّف بـ runCatching على Dispatchers.IO.
     * [P36-M4-8] جسم IO (DB/ملف/SMTP/تدقيق) نُقل حرفياً إلى StatementRepo.retryDeliveryNow؛
     * الواجهة تمرر إعداد prefs (جسرها الوحيد أعلاه) وقنوات إعادة المحاولة (لغة واجهة
     * في StatementUiLogic) — فلا يعرف repo حزمة ui — وترتيب الفحوص ورسائل الفشل كما هي.
     * القنوات غير البريدية: فشل صادق بلا إرسال وهمي — الواجهة تفتح قناتها الأصلية.
     */
    suspend fun retryDeliveryNow(context: Context, deliveryId: Long): Result<Unit> =
        repo(context).retryDeliveryNow(
            deliveryId,
            config = smtpConfigOrNull(context),
            retryableChannels = SMTP_RETRYABLE_CHANNELS
        )

    /**
     * إلغاء تسليم — مسموح فقط من PENDING/FAILED/RETRYING (آلة حالات 17-a:
     * CANCELLED نهائية). يعيد false مع بقاء الحالة كما هي عند رفض الانتقال.
     * [P36-M4-8] الجسم (قراءة DAO + markDelivery + تدقيق) نُقل حرفياً إلى repo.
     */
    suspend fun cancelDelivery(context: Context, deliveryId: Long): Boolean =
        repo(context).cancelDelivery(deliveryId)
}
