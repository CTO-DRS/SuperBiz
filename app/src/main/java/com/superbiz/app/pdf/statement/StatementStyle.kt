package com.superbiz.app.pdf.statement

/**
 * [P17-b] StatementStyle — عقد القوالب (CONTRACT).
 *
 * ⚠️ أسماء الحقول والأنواع هنا ثابتة تعاقدياً — 17-c (UI اختيار القوالب) و17-a
 * (البيانات) يستهلكونها كما هي. لا تُعدَّل الأسماء ولا ترتيب الحقول الموجودة.
 * امتداد واحد مسموح فقط في ملف المحرك: LRect يحمل color/fill بقيم افتراضية
 * (انظر توثيق StatementLayout) — هذا الملف مطابق حرفياً لكتلة العقد.
 */

/** فئة القالب — 10 فئات × 5 قوالب = 50 قالباً في StatementTemplates */
enum class TemplateCategory { CORPORATE, PROFESSIONAL, ACCOUNTING, MODERN, MINIMAL, LUXURY, BUSINESS, CLASSIC, ARABIC, BILINGUAL }

/** نمط ترويسة كل صفحة — يعاد رسمه بنفس الهندسة في كل صفحات الكشف */
enum class HeaderStyle {
    /** شريط ملوّن كامل العرض يضم الشعار وبيانات المنشأة */
    BAND,
    /** إطار مستطيل حول بيانات الترويسة (خط فقط بلا تعبئة) */
    BOXED,
    /** شريط عمودي ممتاز على طول الصفحة في جهة البداية (يمين في RTL = الشريط الجانبي العربي) */
    SIDEBAR,
    /** توسيط كامل: شعار بالمنتصف ثم الاسم ثم التواصل */
    CENTERED,
    /** عمودان بفاصل عمودي: الشعار/الطرف في جهة وبيانات المنشأة في الأخرى */
    SPLIT
}

/** نمط جدول الحركات */
enum class TableStyle {
    /** خط أفقي بين الصفوف + تظليل الصف البديل */
    STRIPED,
    /** شبكة كاملة: أفقي وعمودي */
    BORDERED,
    /** تظليل الصف البديل بلا خطوط أفقية (إلا تحت الرأس) */
    ZEBRA,
    /** مفتوح: خط سفلي لكل صف فقط، بلا إطار */
    OPEN,
    /** أدنى: خط تحت رأس الجدول فقط */
    MINIMAL
}

/** نمط كتلة الملخص المالي — ثابتة في آخر صفحة بعد نهاية الجدول */
enum class SummaryStyle {
    /** بطاقات شبكية 3×2 (البطاقة الأخيرة = الرصيد النهائي مميزة) */
    CARDS,
    /** صندوق إطار واحد بصفوف تسمية:قيمة */
    BOXED,
    /** قضيب عمودي جانبي + صفوف (نمط عمود السكك) */
    RAIL,
    /** شريط معبأ بأسفل المنطقة يعرض 4 أزواج صفّاً واحداً */
    BOTTOM_BAND,
    /** بلا صندوق: صفوف تسمية:قيمة مباشرة على الخلفية */
    INLINE
}

/** نمط تذييل كل صفحة (يرقم الصفحات + رقم التحقق + تاريخ الإصدار + QR اختياري) */
enum class FooterStyle {
    /** خط رفيع + ترقيم بالمنتصف */
    THIN,
    /** إطار صندوق حول سطر التذييل */
    BOXED,
    /** مربع QR 84pt في جهة البداية والترقيم في الجهة الأخرى */
    QR_LEFT,
    /** مربع QR 84pt في جهة النهاية والترقيم في الجهة الأخرى */
    QR_RIGHT,
    /** خط مزدوج كلاسيكي + ترقيم + رقم تحقق بسطر ثانٍ */
    CLASSIC
}

/** زاوية فيزيائية (لا تتأثر باتجاه الصفحة) لتوضيع التوقيع/الخاتم داخل شريط التوقيع */
enum class CornerPos { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/** مفاتيح عناصر المستند القابلة للإظهار/الإخفاء لكل قالب (show: Set<PdfElement>) */
enum class PdfElement {
    LOGO, COMPANY_PHOTO, COMPANY_NAME, COMPANY_NO, TAX_NO, CR_NO, CONTACTS,
    PARTY_INFO, PERIOD, TX_TABLE, OPENING, TOTAL_DEBIT, TOTAL_CREDIT,
    FINAL_BALANCE, NOTES, SIGNATURE, STAMP, QR, VERIFY_ID, GENERATED_DATE
}

/**
 * وصف الطباعة الكامل لقالب واحد — كل هندسة StatementLayout تتفرع من هنا.
 * الوحدات: الألوان ARGB ints، المقاسات النسبية مضاعفات على أساس المحرك،
 * marginDp/spacingDp بوحدة dp تُحوَّل عند الرسم إلى pt بدلالة 72/160.
 */
data class StatementStyle(
    val header: HeaderStyle,
    val table: TableStyle,
    val summary: SummaryStyle,
    val footer: FooterStyle,
    val primary: Int,          // اللون الأساسي: الشرائط/رأس الجدول/التوكيدات
    val secondary: Int,        // اللون الثانوي: الفواصل الذهبية/القضبان/التأكيدات الرقيقة
    val text: Int,             // لون النص الأساسي
    val tableHeadText: Int,    // لون نص رأس الجدول/النص فوق التعبئة الأساسية
    val rowAlt: Int,           // لون تظليل الصف البديل (STRIPED/ZEBRA)
    val headingFontScale: Float,  // مضاعف خط العناوين (1f = أساس المحرك 15pt)
    val bodyFontScale: Float,     // مضاعف خط المتن (1f = أساس المحرك 10pt)
    val marginDp: Int,            // هوامش الصفحة (dp → pt بدلالة 72/160 عند الرسم)
    val spacingDp: Int,           // التباعد العام بين الكتل (dp → pt)
    val landscape: Boolean = false,
    val signaturePos: CornerPos = CornerPos.BOTTOM_RIGHT,
    val stampPos: CornerPos = CornerPos.BOTTOM_LEFT,
    val logoSizeScale: Float = 1f,
    val stampSizeScale: Float = 1f,
    val signatureSizeScale: Float = 1f,
    val signatureOpacity: Int = 255,
    val stampOpacity: Int = 255,
    val show: Set<PdfElement>
)

/** تعريف قالب كامل: هوية + فئة + أسماء وأوصاف ثنائية اللغة + الطباعة */
data class StatementTemplateDef(
    val id: String,               // COR-01 … BIL-05 (لا يتكرر، 50 معرفاً بالضبط)
    val cat: TemplateCategory,
    val nameAr: String,
    val nameEn: String,
    val descAr: String,
    val descEn: String,
    val style: StatementStyle
)
