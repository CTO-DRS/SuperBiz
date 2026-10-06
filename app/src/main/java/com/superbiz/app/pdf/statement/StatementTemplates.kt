package com.superbiz.app.pdf.statement

/**
 * [P17-b] StatementTemplates — كتالوج القوالب الخمسين بالضبط (10 فئات × 5 قوالب).
 *
 * المعرفات: COR-01…COR-05, PRO-01…PRO-05, ACC-01…ACC-05, MOD-01…MOD-05,
 *           MIN-01…MIN-05, LUX-01…LUX-05, BUS-01…BUS-05, CLS-01…CLS-05,
 *           ARA-01…ARA-05, BIL-01…BIL-05.
 *
 * ═══ القاعدة الصارمة ضد «تغيير لون = قالب جديد» ═══
 * داخل كل فئة تختلف القوالب الخمسة بنيوياً لا لونياً فقط:
 *  • ≥4 أنماط ترويسة (HeaderStyle) و≥3 أنماط جدول (TableStyle) و≥3 أنماط ملخص
 *    (SummaryStyle) لكل فئة — يفحصها الاختبار (StatementLayoutTest).
 *  • أي قالبين لا يتساويان في المجموعة السداسية (header, table, summary,
 *    primary, marginDp, headingFontScale) — يفحصها الاختبار أيضاً.
 *  • اختلافات بنيوية خاصة بالفئة ينفذها المحرك:
 *      ACC → صف «رصيد أول المدة» أعلى الجدول + صف إجماليات مؤكد أسفله.
 *      MIN → هوامش ضخمة (116-132dp) وخط أصغر وجدول بلا حدود.
 *      LUX → ترويسة داكنة + خط فاصل ذهبي (secondary) + تكبير خطوط ملكي 1.3+.
 *      ARA → RTL إجباري في المحرك + عناصر كاملة تقريباً + خط أكبر للعربية
 *            + الشريط الجانبي SIDEBAR يصير يمين الصفحة (الشرطة التوكيدية).
 *      BIL → تسميات ثنائية (عربي/إنجليزي) ورأس جدول بسطرين إجباريّان.
 *
 * عقد show موثق: عنصر QR ∈ show ⟺ footer ∈ {QR_LEFT, QR_RIGHT} (يُرسم QR في
 * التذييل فقط). كل القوالب تتضمن TX_TABLE وPARTY_INFO على الأقل.
 */
object StatementTemplates {

    const val DEFAULT_ID = "COR-01"

    /** لوحة ألوان فئة واحدة (كل الفئات عشر ألوان أساسية مختلفة بعضها عن بعض) */
    private data class Pal(
        val primary: Int, val secondary: Int,
        val text: Int, val tableHeadText: Int, val rowAlt: Int
    )

    private val COR = Pal(0xFF1D4ED8.toInt(), 0xFF3B82F6.toInt(), 0xFF111827.toInt(), 0xFFFFFFFF.toInt(), 0xFFEFF6FF.toInt())
    private val PRO = Pal(0xFF334155.toInt(), 0xFF64748B.toInt(), 0xFF1E293B.toInt(), 0xFFFFFFFF.toInt(), 0xFFF1F5F9.toInt())
    private val ACC = Pal(0xFF065F46.toInt(), 0xFF0D9488.toInt(), 0xFF111827.toInt(), 0xFFFFFFFF.toInt(), 0xFFECFDF5.toInt())
    private val MOD = Pal(0xFF7C3AED.toInt(), 0xFF22D3EE.toInt(), 0xFF111827.toInt(), 0xFFFFFFFF.toInt(), 0xFFF5F3FF.toInt())
    private val MIN = Pal(0xFF111827.toInt(), 0xFF9CA3AF.toInt(), 0xFF1F2937.toInt(), 0xFFFFFFFF.toInt(), 0xFFFAFAFA.toInt())
    private val LUX = Pal(0xFF0F172A.toInt(), 0xFFB8860B.toInt(), 0xFF292524.toInt(), 0xFFFDF6E3.toInt(), 0xFFF7F1E1.toInt())
    private val BUS = Pal(0xFFB45309.toInt(), 0xFFF59E0B.toInt(), 0xFF1C1917.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFBEB.toInt())
    private val CLS = Pal(0xFF7F1D1D.toInt(), 0xFF9CA3AF.toInt(), 0xFF1F2937.toInt(), 0xFFFFFFFF.toInt(), 0xFFFEF2F2.toInt())
    private val ARA = Pal(0xFF14532D.toInt(), 0xFFC9A227.toInt(), 0xFF1F2937.toInt(), 0xFFFBF7EA.toInt(), 0xFFF0FDF4.toInt())
    private val BIL = Pal(0xFF0F766E.toInt(), 0xFF1E40AF.toInt(), 0xFF111827.toInt(), 0xFFFFFFFF.toInt(), 0xFFF0FDFA.toInt())

    /**
     * مجموعة العناصر الافتراضية: كل ما يلزم لكشف مالي محترم، ويضاف إليها
     * التوقيع/الخاتم/QR/صورة المنشأة حسب القالب. QR يُضاف تلقائياً فقط عند
     * تذييل QR_LEFT/QR_RIGHT (قاعدة موثقة أعلاه).
     */
    private fun showSet(
        footer: FooterStyle,
        signature: Boolean = true,
        stamp: Boolean = true,
        drop: Set<PdfElement> = emptySet(),
        add: Set<PdfElement> = emptySet()
    ): Set<PdfElement> {
        val base = setOf(
            PdfElement.LOGO, PdfElement.COMPANY_NAME, PdfElement.COMPANY_NO, PdfElement.TAX_NO,
            PdfElement.CR_NO, PdfElement.CONTACTS, PdfElement.PARTY_INFO, PdfElement.PERIOD,
            PdfElement.TX_TABLE, PdfElement.OPENING, PdfElement.TOTAL_DEBIT, PdfElement.TOTAL_CREDIT,
            PdfElement.FINAL_BALANCE, PdfElement.NOTES, PdfElement.VERIFY_ID, PdfElement.GENERATED_DATE
        ) +
            (if (signature) setOf(PdfElement.SIGNATURE) else emptySet()) +
            (if (stamp) setOf(PdfElement.STAMP) else emptySet()) +
            (if (footer == FooterStyle.QR_LEFT || footer == FooterStyle.QR_RIGHT) setOf(PdfElement.QR) else emptySet()) +
            add
        return base - drop
    }

    /** مُنشئ StatementStyle مختصر — يمرر اللوحة ويترك الحقول التعاقدية كما هي */
    private fun st(
        pal: Pal,
        header: HeaderStyle, table: TableStyle, summary: SummaryStyle, footer: FooterStyle,
        hScale: Float, bScale: Float, marginDp: Int, spacingDp: Int,
        landscape: Boolean = false,
        sigPos: CornerPos = CornerPos.BOTTOM_RIGHT,
        stampPos: CornerPos = CornerPos.BOTTOM_LEFT,
        logoScale: Float = 1f, sigScale: Float = 1f, stampScale: Float = 1f,
        sigOpacity: Int = 255, stampOpacity: Int = 255,
        show: Set<PdfElement>
    ) = StatementStyle(
        header = header, table = table, summary = summary, footer = footer,
        primary = pal.primary, secondary = pal.secondary, text = pal.text,
        tableHeadText = pal.tableHeadText, rowAlt = pal.rowAlt,
        headingFontScale = hScale, bodyFontScale = bScale,
        marginDp = marginDp, spacingDp = spacingDp,
        landscape = landscape,
        signaturePos = sigPos, stampPos = stampPos,
        logoSizeScale = logoScale, stampSizeScale = stampScale, signatureSizeScale = sigScale,
        signatureOpacity = sigOpacity, stampOpacity = stampOpacity,
        show = show
    )

    private fun tpl(
        id: String, cat: TemplateCategory, nameAr: String, nameEn: String,
        descAr: String, descEn: String, style: StatementStyle
    ) = StatementTemplateDef(id, cat, nameAr, nameEn, descAr, descEn, style)

    // ═══════════════════ CORPORATE — الأزرق المؤسسي ═══════════════════

    val ALL: List<StatementTemplateDef> = listOf(

        // ── CORPORATE ──
        tpl(
            "COR-01", TemplateCategory.CORPORATE,
            "الكلاسيكي الأزرق", "Classic Blue",
            "شريط ترويسة أزرق مع بطاقات ملخص وQR يمين التذييل — القالب الافتراضي",
            "Blue header band with summary cards and right QR footer — the default",
            st(COR, HeaderStyle.BAND, TableStyle.STRIPED, SummaryStyle.CARDS, FooterStyle.QR_RIGHT,
                hScale = 1.15f, bScale = 1.00f, marginDp = 88, spacingDp = 10,
                logoScale = 1.15f, show = showSet(FooterStyle.QR_RIGHT))
        ),
        tpl(
            "COR-02", TemplateCategory.CORPORATE,
            "الإطار الرسمي", "Formal Frame",
            "ترويسة مؤطرة وجدول بشبكة كاملة وصندوق إجماليات — طابع رسمي",
            "Boxed header, full-grid table and boxed totals — formal character",
            st(COR, HeaderStyle.BOXED, TableStyle.BORDERED, SummaryStyle.BOXED, FooterStyle.THIN,
                hScale = 1.20f, bScale = 1.05f, marginDp = 92, spacingDp = 12,
                show = showSet(FooterStyle.THIN))
        ),
        tpl(
            "COR-03", TemplateCategory.CORPORATE,
            "القسمان", "Two-Column",
            "ترويسة مقسومة بفاصل عمودي وتظليل صفوف وشريط إجماليات وتذييل كلاسيكي",
            "Split header with divider, zebra rows, rail summary, classic footer",
            st(COR, HeaderStyle.SPLIT, TableStyle.ZEBRA, SummaryStyle.RAIL, FooterStyle.CLASSIC,
                hScale = 1.10f, bScale = 0.95f, marginDp = 84, spacingDp = 10,
                show = showSet(FooterStyle.CLASSIC))
        ),
        tpl(
            "COR-04", TemplateCategory.CORPORATE,
            "الوسط الموحد", "Centered Unified",
            "ترويسة توسيطية مع صورة المنشأة وجدول مفتوح وملخص سطري وQR يسار",
            "Centered header with company photo, open table, inline summary, left QR",
            st(COR, HeaderStyle.CENTERED, TableStyle.OPEN, SummaryStyle.INLINE, FooterStyle.QR_LEFT,
                hScale = 1.25f, bScale = 1.00f, marginDp = 96, spacingDp = 14,
                show = showSet(FooterStyle.QR_LEFT, add = setOf(PdfElement.COMPANY_PHOTO)))
        ),
        tpl(
            "COR-05", TemplateCategory.CORPORATE,
            "الشريط الجانبي", "Side Strip",
            "شريط جانبي ممتاز بطول الصفحة وجدول أدنى وشريط ملخص سفلي",
            "Full-height side strip, minimal table and bottom summary band",
            st(COR, HeaderStyle.SIDEBAR, TableStyle.MINIMAL, SummaryStyle.BOTTOM_BAND, FooterStyle.THIN,
                hScale = 1.05f, bScale = 0.95f, marginDp = 80, spacingDp = 8,
                show = showSet(FooterStyle.THIN))
        ),

        // ── PROFESSIONAL ──
        tpl(
            "PRO-01", TemplateCategory.PROFESSIONAL,
            "الاستشاري", "Consultant",
            "قسمان بترويسة سليت وتظليل صفوف وصندوق إجماليات",
            "Slate split header, striped rows, boxed summary",
            st(PRO, HeaderStyle.SPLIT, TableStyle.STRIPED, SummaryStyle.BOXED, FooterStyle.THIN,
                hScale = 1.10f, bScale = 1.00f, marginDp = 84, spacingDp = 10,
                show = showSet(FooterStyle.THIN))
        ),
        tpl(
            "PRO-02", TemplateCategory.PROFESSIONAL,
            "الشريط السليت", "Slate Band",
            "شريط سليت وجدول مفتوح وشريط إجماليات جانبي وQR يمين",
            "Slate band, open table, rail summary, right QR",
            st(PRO, HeaderStyle.BAND, TableStyle.OPEN, SummaryStyle.RAIL, FooterStyle.QR_RIGHT,
                hScale = 1.15f, bScale = 1.00f, marginDp = 88, spacingDp = 10,
                show = showSet(FooterStyle.QR_RIGHT))
        ),
        tpl(
            "PRO-03", TemplateCategory.PROFESSIONAL,
            "المكتب الهادئ", "Quiet Office",
            "إطار ترويسة وتظليل صفوف وملخص سطري وتوقيع بالزاوية العليا",
            "Boxed header, zebra rows, inline summary, top-corner signature",
            st(PRO, HeaderStyle.BOXED, TableStyle.ZEBRA, SummaryStyle.INLINE, FooterStyle.CLASSIC,
                hScale = 1.00f, bScale = 0.95f, marginDp = 90, spacingDp = 10,
                sigPos = CornerPos.TOP_RIGHT, stampPos = CornerPos.TOP_LEFT,
                show = showSet(FooterStyle.CLASSIC))
        ),
        tpl(
            "PRO-04", TemplateCategory.PROFESSIONAL,
            "الأعمدة الرسمية", "Formal Columns",
            "شريط جانبي وجدول بشبكة كاملة وبطاقات ملخص وتوقيع مكبّر",
            "Side strip, bordered grid, summary cards, enlarged signature",
            st(PRO, HeaderStyle.SIDEBAR, TableStyle.BORDERED, SummaryStyle.CARDS, FooterStyle.THIN,
                hScale = 1.20f, bScale = 1.05f, marginDp = 82, spacingDp = 10,
                sigScale = 1.10f, show = showSet(FooterStyle.THIN))
        ),
        tpl(
            "PRO-05", TemplateCategory.PROFESSIONAL,
            "المحترف الموسّط", "Centered Pro",
            "توسيط كامل وجدول أدنى وشريط سفلي وQR يسار — أقصى تقريب للخط",
            "Fully centered, minimal table, bottom band, left QR, largest heading",
            st(PRO, HeaderStyle.CENTERED, TableStyle.MINIMAL, SummaryStyle.BOTTOM_BAND, FooterStyle.QR_LEFT,
                hScale = 1.30f, bScale = 1.10f, marginDp = 94, spacingDp = 12,
                show = showSet(FooterStyle.QR_LEFT))
        ),

        // ── ACCOUNTING — صف Opening وصف الإجماليات مؤكدَان بنيوياً ──
        tpl(
            "ACC-01", TemplateCategory.ACCOUNTING,
            "دفتر الأستاذ", "Ledger Book",
            "ترويسة زمردية وشبكة كاملة وصندوق إجماليات — مع صف رصيد أول المدة وصف الإجماليات",
            "Emerald band, full grid, boxed totals — with opening and totals rows",
            st(ACC, HeaderStyle.BAND, TableStyle.BORDERED, SummaryStyle.BOXED, FooterStyle.CLASSIC,
                hScale = 1.10f, bScale = 1.00f, marginDp = 86, spacingDp = 10,
                show = showSet(FooterStyle.CLASSIC))
        ),
        tpl(
            "ACC-02", TemplateCategory.ACCOUNTING,
            "المحاسب الأنيق", "Neat Accountant",
            "إطار ترويسة وتظليل صفوف وبطاقات ملخص",
            "Boxed header, striped rows, summary cards",
            st(ACC, HeaderStyle.BOXED, TableStyle.STRIPED, SummaryStyle.CARDS, FooterStyle.THIN,
                hScale = 1.15f, bScale = 1.00f, marginDp = 84, spacingDp = 10,
                show = showSet(FooterStyle.THIN))
        ),
        tpl(
            "ACC-03", TemplateCategory.ACCOUNTING,
            "اليومية المفتوحة", "Open Journal",
            "قسمان وجدول مفتوح وشريط إجماليات جانبي وQR يمين",
            "Split header, open table, rail summary, right QR",
            st(ACC, HeaderStyle.SPLIT, TableStyle.OPEN, SummaryStyle.RAIL, FooterStyle.QR_RIGHT,
                hScale = 1.00f, bScale = 0.95f, marginDp = 88, spacingDp = 10,
                show = showSet(FooterStyle.QR_RIGHT))
        ),
        tpl(
            "ACC-04", TemplateCategory.ACCOUNTING,
            "ميزان المراجعة", "Trial Balance",
            "شريط جانبي وتظليل صفوف وملخص سطري",
            "Side strip, zebra rows, inline summary",
            st(ACC, HeaderStyle.SIDEBAR, TableStyle.ZEBRA, SummaryStyle.INLINE, FooterStyle.THIN,
                hScale = 1.20f, bScale = 1.05f, marginDp = 82, spacingDp = 10,
                show = showSet(FooterStyle.THIN))
        ),
        tpl(
            "ACC-05", TemplateCategory.ACCOUNTING,
            "الحسابات الختامية", "Final Accounts",
            "توسيط وشبكة كاملة وشريط سفلي وQR يسار",
            "Centered header, full grid, bottom band, left QR",
            st(ACC, HeaderStyle.CENTERED, TableStyle.BORDERED, SummaryStyle.BOTTOM_BAND, FooterStyle.QR_LEFT,
                hScale = 1.25f, bScale = 1.10f, marginDp = 92, spacingDp = 12,
                show = showSet(FooterStyle.QR_LEFT))
        ),

        // ── MODERN — هوية SuperBiz البنفسجية ──
        tpl(
            "MOD-01", TemplateCategory.MODERN,
            "البنفسجي الحيوي", "Vivid Violet",
            "شريط بنفسجي وتظليل صفوف وبطاقات ملخص وتوقيع شبه شفاف",
            "Violet band, zebra rows, summary cards, semi-transparent signature",
            st(MOD, HeaderStyle.BAND, TableStyle.ZEBRA, SummaryStyle.CARDS, FooterStyle.QR_RIGHT,
                hScale = 1.20f, bScale = 1.00f, marginDp = 84, spacingDp = 10,
                sigOpacity = 235, show = showSet(FooterStyle.QR_RIGHT))
        ),
        tpl(
            "MOD-02", TemplateCategory.MODERN,
            "الفيوزي الموسّط", "Centered Fusion",
            "توسيط مع صورة المنشأة وجدول مفتوح وملخص سطري وQR يسار",
            "Centered with company photo, open table, inline summary, left QR",
            st(MOD, HeaderStyle.CENTERED, TableStyle.OPEN, SummaryStyle.INLINE, FooterStyle.QR_LEFT,
                hScale = 1.30f, bScale = 1.05f, marginDp = 88, spacingDp = 12,
                show = showSet(FooterStyle.QR_LEFT, add = setOf(PdfElement.COMPANY_PHOTO)))
        ),
        tpl(
            "MOD-03", TemplateCategory.MODERN,
            "العرض الأفقي", "Wide Screen",
            "قسمان بصفحة عرضية أفقية وتظليل صفوف وشريط جانبي للإجماليات",
            "Split header on landscape page, striped rows, rail summary",
            st(MOD, HeaderStyle.SPLIT, TableStyle.STRIPED, SummaryStyle.RAIL, FooterStyle.THIN,
                hScale = 1.10f, bScale = 0.95f, marginDp = 80, spacingDp = 8,
                landscape = true, show = showSet(FooterStyle.THIN))
        ),
        tpl(
            "MOD-04", TemplateCategory.MODERN,
            "النيون الأدنى", "Soft Neon",
            "إطار ترويسة وجدول أدنى وشريط سفلي",
            "Boxed header, minimal table, bottom band",
            st(MOD, HeaderStyle.BOXED, TableStyle.MINIMAL, SummaryStyle.BOTTOM_BAND, FooterStyle.THIN,
                hScale = 1.15f, bScale = 1.00f, marginDp = 90, spacingDp = 10,
                show = showSet(FooterStyle.THIN))
        ),
        tpl(
            "MOD-05", TemplateCategory.MODERN,
            "التدرج الرسمي", "Formal Gradient",
            "شريط جانبي وشبكة كاملة وصندوق إجماليات وتذييل كلاسيكي",
            "Side strip, full grid, boxed summary, classic footer",
            st(MOD, HeaderStyle.SIDEBAR, TableStyle.BORDERED, SummaryStyle.BOXED, FooterStyle.CLASSIC,
                hScale = 1.25f, bScale = 1.10f, marginDp = 86, spacingDp = 10,
                show = showSet(FooterStyle.CLASSIC))
        ),

        // ── MINIMAL — بيضاء كبيرة وبلا زخرفة ──
        tpl(
            "MIN-01", TemplateCategory.MINIMAL,
            "الورقة البيضاء", "Blank Page",
            "توسيط صامت وجدول بلا حدود وملخص سطري — بلا خاتم",
            "Silent centered header, borderless table, inline summary — no stamp",
            st(MIN, HeaderStyle.CENTERED, TableStyle.MINIMAL, SummaryStyle.INLINE, FooterStyle.THIN,
                hScale = 1.00f, bScale = 0.95f, marginDp = 124, spacingDp = 14,
                show = showSet(FooterStyle.THIN, stamp = false))
        ),
        tpl(
            "MIN-02", TemplateCategory.MINIMAL,
            "الشريط الرقيق", "Thin Band",
            "شريط علوي رقيق وجدول مفتوح وملخص سطري",
            "Thin top band, open table, inline summary",
            st(MIN, HeaderStyle.BAND, TableStyle.OPEN, SummaryStyle.INLINE, FooterStyle.THIN,
                hScale = 1.05f, bScale = 0.95f, marginDp = 120, spacingDp = 14,
                show = showSet(FooterStyle.THIN))
        ),
        tpl(
            "MIN-03", TemplateCategory.MINIMAL,
            "الضباب", "Fog",
            "قسمان وجدول بلا حدود وشريط إجماليات جانبي — بلا خاتم ولا ضريبي",
            "Split header, borderless table, rail summary — no stamp, no tax line",
            st(MIN, HeaderStyle.SPLIT, TableStyle.MINIMAL, SummaryStyle.RAIL, FooterStyle.THIN,
                hScale = 0.95f, bScale = 0.90f, marginDp = 128, spacingDp = 16,
                show = showSet(FooterStyle.THIN, stamp = false, drop = setOf(PdfElement.TAX_NO)))
        ),
        tpl(
            "MIN-04", TemplateCategory.MINIMAL,
            "الخط الوحيد", "Single Line",
            "شريط جانبي رقيق وتظليل خافت وشريط سفلي وتوقيع خفيف الحبر",
            "Thin side strip, faint zebra, bottom band, light-ink signature",
            st(MIN, HeaderStyle.SIDEBAR, TableStyle.ZEBRA, SummaryStyle.BOTTOM_BAND, FooterStyle.THIN,
                hScale = 1.10f, bScale = 1.00f, marginDp = 116, spacingDp = 12,
                sigOpacity = 220, show = showSet(FooterStyle.THIN))
        ),
        tpl(
            "MIN-05", TemplateCategory.MINIMAL,
            "الهامش الواسع", "Wide Margin",
            "إطار ترويسة وجدول بلا حدود وشريط سفلي — أوسع هوامش الكتالوج",
            "Boxed header, borderless table, bottom band — widest margins",
            st(MIN, HeaderStyle.BOXED, TableStyle.OPEN, SummaryStyle.BOTTOM_BAND, FooterStyle.THIN,
                hScale = 1.00f, bScale = 0.90f, marginDp = 132, spacingDp = 18,
                show = showSet(FooterStyle.THIN, stamp = false))
        ),

        // ── LUXURY — كحلي داكن وذهب وخط ملكي ──
        tpl(
            "LUX-01", TemplateCategory.LUXURY,
            "الذهب الكحلي", "Navy & Gold",
            "ترويسة كحلية داكنة بخط ذهبي وجدول مفتوح وبطاقات ملخص",
            "Dark navy header with gold rule, open table, summary cards",
            st(LUX, HeaderStyle.BAND, TableStyle.OPEN, SummaryStyle.CARDS, FooterStyle.QR_RIGHT,
                hScale = 1.35f, bScale = 1.05f, marginDp = 92, spacingDp = 12,
                show = showSet(FooterStyle.QR_RIGHT))
        ),
        tpl(
            "LUX-02", TemplateCategory.LUXURY,
            "المخمل الملكي", "Royal Velvet",
            "ترويسة داكنة وتظليل صفوف وصندوق إجماليات وتذييل كلاسيكي",
            "Dark band, striped rows, boxed summary, classic footer",
            st(LUX, HeaderStyle.BAND, TableStyle.STRIPED, SummaryStyle.BOXED, FooterStyle.CLASSIC,
                hScale = 1.45f, bScale = 1.10f, marginDp = 96, spacingDp = 14,
                show = showSet(FooterStyle.CLASSIC))
        ),
        tpl(
            "LUX-03", TemplateCategory.LUXURY,
            "السطر الذهبي", "Golden Line",
            "إطار ترويسة وشبكة كاملة وشريط إجماليات جانبي بخط ذهبي",
            "Boxed header, full grid, rail summary with golden bar",
            st(LUX, HeaderStyle.BOXED, TableStyle.BORDERED, SummaryStyle.RAIL, FooterStyle.CLASSIC,
                hScale = 1.40f, bScale = 1.05f, marginDp = 88, spacingDp = 12,
                sigPos = CornerPos.BOTTOM_LEFT, stampPos = CornerPos.BOTTOM_RIGHT,
                stampOpacity = 230, show = showSet(FooterStyle.CLASSIC))
        ),
        tpl(
            "LUX-04", TemplateCategory.LUXURY,
            "التاج الموسّط", "Centered Crown",
            "توسيط مع صورة المنشأة وتظليل صفوف وبطاقات ملخص وشعار مكبّر",
            "Centered with company photo, zebra rows, summary cards, large logo",
            st(LUX, HeaderStyle.CENTERED, TableStyle.ZEBRA, SummaryStyle.CARDS, FooterStyle.QR_LEFT,
                hScale = 1.50f, bScale = 1.15f, marginDp = 100, spacingDp = 14,
                logoScale = 1.30f, show = showSet(FooterStyle.QR_LEFT, add = setOf(PdfElement.COMPANY_PHOTO)))
        ),
        tpl(
            "LUX-05", TemplateCategory.LUXURY,
            "الجار الملكي", "Royal Side",
            "شريط جانبي داكن وجدول مفتوح وشريط سفلي",
            "Dark side strip, open table, bottom band",
            st(LUX, HeaderStyle.SIDEBAR, TableStyle.OPEN, SummaryStyle.BOTTOM_BAND, FooterStyle.THIN,
                hScale = 1.30f, bScale = 1.00f, marginDp = 90, spacingDp = 12,
                show = showSet(FooterStyle.THIN))
        ),

        // ── BUSINESS — عنبري عملي ──
        tpl(
            "BUS-01", TemplateCategory.BUSINESS,
            "التاجر العملي", "Practical Trader",
            "إطار ترويسة وتظليل صفوف وصندوق إجماليات وQR يمين",
            "Boxed header, striped rows, boxed summary, right QR",
            st(BUS, HeaderStyle.BOXED, TableStyle.STRIPED, SummaryStyle.BOXED, FooterStyle.QR_RIGHT,
                hScale = 1.10f, bScale = 1.00f, marginDp = 84, spacingDp = 10,
                show = showSet(FooterStyle.QR_RIGHT))
        ),
        tpl(
            "BUS-02", TemplateCategory.BUSINESS,
            "العنبري", "Amber Band",
            "شريط عنبري وشبكة كاملة وملخص سطري",
            "Amber band, full grid, inline summary",
            st(BUS, HeaderStyle.BAND, TableStyle.BORDERED, SummaryStyle.INLINE, FooterStyle.THIN,
                hScale = 1.15f, bScale = 1.00f, marginDp = 82, spacingDp = 10,
                show = showSet(FooterStyle.THIN))
        ),
        tpl(
            "BUS-03", TemplateCategory.BUSINESS,
            "الشراكة", "Partnership",
            "قسمان وتظليل صفوف وبطاقات ملخص وتذييل كلاسيكي",
            "Split header, zebra rows, summary cards, classic footer",
            st(BUS, HeaderStyle.SPLIT, TableStyle.ZEBRA, SummaryStyle.CARDS, FooterStyle.CLASSIC,
                hScale = 1.05f, bScale = 0.95f, marginDp = 86, spacingDp = 10,
                show = showSet(FooterStyle.CLASSIC))
        ),
        tpl(
            "BUS-04", TemplateCategory.BUSINESS,
            "الواجهة الموسّطة", "Centered Facade",
            "توسيط وجدول مفتوح وشريط إجماليات جانبي وQR يسار",
            "Centered header, open table, rail summary, left QR",
            st(BUS, HeaderStyle.CENTERED, TableStyle.OPEN, SummaryStyle.RAIL, FooterStyle.QR_LEFT,
                hScale = 1.20f, bScale = 1.05f, marginDp = 90, spacingDp = 12,
                show = showSet(FooterStyle.QR_LEFT))
        ),
        tpl(
            "BUS-05", TemplateCategory.BUSINESS,
            "المستودع الأفقي", "Wide Warehouse",
            "شريط جانبي بصفحة عرضية وتظليل صفوف وشريط سفلي",
            "Side strip on landscape page, striped rows, bottom band",
            st(BUS, HeaderStyle.SIDEBAR, TableStyle.STRIPED, SummaryStyle.BOTTOM_BAND, FooterStyle.THIN,
                hScale = 1.00f, bScale = 0.95f, marginDp = 80, spacingDp = 8,
                landscape = true, show = showSet(FooterStyle.THIN))
        ),

        // ── CLASSIC — عنابي تقليدي ──
        tpl(
            "CLS-01", TemplateCategory.CLASSIC,
            "التقليدي", "Traditional",
            "شريط عنابي وشبكة كاملة وصندوق إجماليات وتذييل كلاسيكي",
            "Maroon band, full grid, boxed summary, classic footer",
            st(CLS, HeaderStyle.BAND, TableStyle.BORDERED, SummaryStyle.BOXED, FooterStyle.CLASSIC,
                hScale = 1.15f, bScale = 1.00f, marginDp = 86, spacingDp = 10,
                show = showSet(FooterStyle.CLASSIC))
        ),
        tpl(
            "CLS-02", TemplateCategory.CLASSIC,
            "السجل القديم", "Old Ledger",
            "إطار ترويسة وتظليل صفوف وشريط إجماليات جانبي",
            "Boxed header, striped rows, rail summary",
            st(CLS, HeaderStyle.BOXED, TableStyle.STRIPED, SummaryStyle.RAIL, FooterStyle.CLASSIC,
                hScale = 1.10f, bScale = 0.95f, marginDp = 84, spacingDp = 10,
                show = showSet(FooterStyle.CLASSIC))
        ),
        tpl(
            "CLS-03", TemplateCategory.CLASSIC,
            "الوثيقة الرسمية", "Formal Document",
            "توسيط وتظليل صفوف وصندوق إجماليات",
            "Centered header, zebra rows, boxed summary",
            st(CLS, HeaderStyle.CENTERED, TableStyle.ZEBRA, SummaryStyle.BOXED, FooterStyle.THIN,
                hScale = 1.20f, bScale = 1.00f, marginDp = 88, spacingDp = 12,
                show = showSet(FooterStyle.THIN))
        ),
        tpl(
            "CLS-04", TemplateCategory.CLASSIC,
            "البسيط القديم", "Plain Old",
            "قسمان وجدول مفتوح وملخص سطري",
            "Split header, open table, inline summary",
            st(CLS, HeaderStyle.SPLIT, TableStyle.OPEN, SummaryStyle.INLINE, FooterStyle.CLASSIC,
                hScale = 1.05f, bScale = 0.95f, marginDp = 82, spacingDp = 10,
                show = showSet(FooterStyle.CLASSIC))
        ),
        tpl(
            "CLS-05", TemplateCategory.CLASSIC,
            "العقود الموقعة", "Signed Contracts",
            "شريط عنابي وشبكة كاملة وبطاقات ملخص وQR يمين — بديل عقود للتقليدي",
            "Maroon band, full grid, summary cards, right QR — cards twin of Traditional",
            st(CLS, HeaderStyle.BAND, TableStyle.BORDERED, SummaryStyle.CARDS, FooterStyle.QR_RIGHT,
                hScale = 1.25f, bScale = 1.05f, marginDp = 90, spacingDp = 12,
                show = showSet(FooterStyle.QR_RIGHT))
        ),

        // ── ARABIC — RTL إجباري وعناصر شبه كاملة وخط عربي أكبر ──
        tpl(
            "ARA-01", TemplateCategory.ARABIC,
            "اليمين الأخضر", "Green Right",
            "شريط جانبي يمين الصفحة (RTL) وتظليل صفوف وصندوق إجماليات وصورة المنشأة",
            "Right side strip (RTL), striped rows, boxed summary, company photo",
            st(ARA, HeaderStyle.SIDEBAR, TableStyle.STRIPED, SummaryStyle.BOXED, FooterStyle.QR_RIGHT,
                hScale = 1.25f, bScale = 1.10f, marginDp = 84, spacingDp = 10,
                stampOpacity = 215,
                show = showSet(FooterStyle.QR_RIGHT, add = setOf(PdfElement.COMPANY_PHOTO)))
        ),
        tpl(
            "ARA-02", TemplateCategory.ARABIC,
            "البيان الرسمي", "Official Bayan",
            "شريط أخضر وشبكة كاملة وشريط إجماليات جانبي بخط ذهبي وخاتم مكبّر",
            "Green band, full grid, rail summary with gold bar, large stamp",
            st(ARA, HeaderStyle.BAND, TableStyle.BORDERED, SummaryStyle.RAIL, FooterStyle.CLASSIC,
                hScale = 1.30f, bScale = 1.15f, marginDp = 86, spacingDp = 10,
                stampScale = 1.15f, show = showSet(FooterStyle.CLASSIC))
        ),
        tpl(
            "ARA-03", TemplateCategory.ARABIC,
            "الدفتر العربي", "Arabic Ledger",
            "إطار ترويسة وتظليل صفوف وبطاقات ملخص وQR يسار",
            "Boxed header, zebra rows, summary cards, left QR",
            st(ARA, HeaderStyle.BOXED, TableStyle.ZEBRA, SummaryStyle.CARDS, FooterStyle.QR_LEFT,
                hScale = 1.20f, bScale = 1.10f, marginDp = 82, spacingDp = 10,
                show = showSet(FooterStyle.QR_LEFT))
        ),
        tpl(
            "ARA-04", TemplateCategory.ARABIC,
            "القسمين العربي", "Arabic Split",
            "قسمان وجدول مفتوح وشريط سفلي وتوقيع يسار وخاتم يمين (معكوس)",
            "Split header, open table, bottom band, signature left and stamp right",
            st(ARA, HeaderStyle.SPLIT, TableStyle.OPEN, SummaryStyle.BOTTOM_BAND, FooterStyle.THIN,
                hScale = 1.35f, bScale = 1.20f, marginDp = 88, spacingDp = 12,
                sigPos = CornerPos.BOTTOM_LEFT, stampPos = CornerPos.BOTTOM_RIGHT,
                show = showSet(FooterStyle.THIN))
        ),
        tpl(
            "ARA-05", TemplateCategory.ARABIC,
            "الكشف الأصيل", "Authentic Statement",
            "توسيط وتظليل صفوف وملخص سطري وتذييل كلاسيكي",
            "Centered header, striped rows, inline summary, classic footer",
            st(ARA, HeaderStyle.CENTERED, TableStyle.STRIPED, SummaryStyle.INLINE, FooterStyle.CLASSIC,
                hScale = 1.28f, bScale = 1.12f, marginDp = 84, spacingDp = 10,
                show = showSet(FooterStyle.CLASSIC))
        ),

        // ── BILINGUAL — تسميات مزدوجة إجبارية ورأس جدول بسطرين ──
        tpl(
            "BIL-01", TemplateCategory.BILINGUAL,
            "اللغتان المتقابلتان", "Twin Languages",
            "قسمان وتظليل صفوف وصندوق إجماليات بذيل ثنائي اللغة",
            "Split header, striped rows, boxed summary, bilingual labels",
            st(BIL, HeaderStyle.SPLIT, TableStyle.STRIPED, SummaryStyle.BOXED, FooterStyle.QR_RIGHT,
                hScale = 1.15f, bScale = 1.00f, marginDp = 84, spacingDp = 10,
                show = showSet(FooterStyle.QR_RIGHT))
        ),
        tpl(
            "BIL-02", TemplateCategory.BILINGUAL,
            "الشريط الثنائي", "Dual Band",
            "شريط مزدوج اللون وجدول بشبكة كاملة وشريط إجماليات جانبي",
            "Two-tone band, full grid, rail summary",
            st(BIL, HeaderStyle.BAND, TableStyle.BORDERED, SummaryStyle.RAIL, FooterStyle.THIN,
                hScale = 1.20f, bScale = 1.05f, marginDp = 86, spacingDp = 10,
                show = showSet(FooterStyle.THIN))
        ),
        tpl(
            "BIL-03", TemplateCategory.BILINGUAL,
            "الجناحان", "Two Wings",
            "توسيط مع صورة المنشأة وتظليل صفوف وبطاقات ملخص",
            "Centered with company photo, zebra rows, summary cards",
            st(BIL, HeaderStyle.CENTERED, TableStyle.ZEBRA, SummaryStyle.CARDS, FooterStyle.QR_LEFT,
                hScale = 1.10f, bScale = 0.95f, marginDp = 88, spacingDp = 12,
                show = showSet(FooterStyle.QR_LEFT, add = setOf(PdfElement.COMPANY_PHOTO)))
        ),
        tpl(
            "BIL-04", TemplateCategory.BILINGUAL,
            "المرآة", "Mirror",
            "إطار ترويسة وجدول مفتوح وملخص سطري",
            "Boxed header, open table, inline summary",
            st(BIL, HeaderStyle.BOXED, TableStyle.OPEN, SummaryStyle.INLINE, FooterStyle.CLASSIC,
                hScale = 1.25f, bScale = 1.10f, marginDp = 82, spacingDp = 10,
                show = showSet(FooterStyle.CLASSIC))
        ),
        tpl(
            "BIL-05", TemplateCategory.BILINGUAL,
            "العرض الثنائي", "Bilingual Wide",
            "شريط جانبي بصفحة عرضية وتظليل صفوف وشريط سفلي",
            "Side strip on landscape page, striped rows, bottom band",
            st(BIL, HeaderStyle.SIDEBAR, TableStyle.STRIPED, SummaryStyle.BOTTOM_BAND, FooterStyle.THIN,
                hScale = 1.18f, bScale = 1.02f, marginDp = 84, spacingDp = 8,
                landscape = true, show = showSet(FooterStyle.THIN))
        )
    )

    private val byIdMap: Map<String, StatementTemplateDef> = ALL.associateBy { it.id }

    /** جلب قالب بمعرفه — null للمعرفات غير المعروفة (لا استثناءات في مسار العرض) */
    fun byId(id: String): StatementTemplateDef? = byIdMap[id]

    /** تجميع القوالب بالفئة — لعرضها كمجموعات في واجهة الاختيار (17-c) */
    val byCategory: Map<TemplateCategory, List<StatementTemplateDef>> = ALL.groupBy { it.cat }

    /**
     * استنتاج فئة قالب من نسخة StatementStyle (حتى لو عبر copy()) بمطابقة
     * البنية القيمية — يستعمله Renderer لتمرير الاختلافات البنيوية للمحرك
     * (ACC صف Opening/الإجماليات، LUX الفاصل الذهبي، ARA إجبار RTL، BIL الثنائية).
     * يعيد null إن كانت الطباعة معدلة يدوياً وغير مطابقة لأي قالب — والمحرك
     * عندئذ يسلك السلوك الموجه بالبيانات الافتراضي.
     */
    fun categoryOf(style: StatementStyle): TemplateCategory? =
        ALL.firstOrNull { it.style == style }?.cat
}
