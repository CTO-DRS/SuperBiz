package com.superbiz.app.print

import android.content.Context
import com.superbiz.app.R
import com.superbiz.app.data.db.Invoice
import com.superbiz.app.data.db.InvoiceItem
import com.superbiz.app.data.db.Party
import com.superbiz.app.util.Money
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * باني إيصالات ESC/POS للطابعات الحرارية (ورق 58/80 مم).
 * منطق خالص بلا اعتماد على Android — قابل للاختبار وحداتياً.
 * النصوص تُرسل UTF-8؛ الطابعات الحديثة تدعم العربية وتشكيلها الاتجاهي داخلياً.
 * : الوضع الافتراضي أصبح CP1256 مع عكس المقاطع العربية ليناسب الطابعات
 * الاقتصادية 58مم التي ترسم بايتات LTR ولا تفهم UTF-8 — استخدم MODE_UTF8 للطابعات الحديثة.
 *
 * [P6-M31 إصلاح] قيود الطباعة العربية (موثقة — مطبَّقة في الكود أدناه)
 * 1) المُشكّل shape() يحوّل المنطقي إلى أشكال العرض Unicode Presentation Forms-B
 * حسب موضع الحرف (بداية/وسط/نهاية/منفصل) مع مقيّدات اللام-ألف، قبل الانعكاس.
 * 2) الترتيب البصري للسطر: visualLine() تعكس ترتيب المقاطع (العربية/غير العربية)
 * فتُطبع القيمة يسار الورق والتسمية يمينها في الإيصالات العربية — يطابق العرض
 * ثنائي الاتجاه (bidi) للمعاينة النصية ReceiptPreview في التطبيق ذات الاتجاه RTL.
 * انحراف موثق عن UBA الصارم: المقاطع الرقمية البحتة (مثل «2 × 5.00») تبقى
 * بترتيبها المنطقي ولا تُقلب داخلياً — لقراءة أوضح على الورق.
 * 3) جدولة CP1256 لا تحمل أشكال العرض (FE70–FEFF) — لذا يُطوى كل شكل إلى حرفه
 * الأساس عند الترميز (cp1256Bytes) فتبقى البايتات حروفاً أساسية بالترتيب
 * البصري، وهو أعلى توافق ممكن: الطابعات التي توصل حروفها ذاتياً تنتج نصاً
 * متصلاً، والطابعات الغبية تنتج حروفاً منفصلة مقروءة (لا بديل بايتي واحد لذلك).
 * مقيّد اللام-ألف يُطوى إلى زوجيه بالترتيب البصري (ألف ثم لام) كي تُرسم اللام
 * يمين الألف كما تُقرأ.
 * 4) لا كشف تلقائي لنوع firmware: وضع CP1256 يفترض طابعة ترسم بايتات LTR بلا
 * bidi؛ لو كانت الطابعة ترتب الأحرف ذاتياً (bidi داخلي) فالانعكاس هنا سيعكس
 * النص مرتين — استخدم MODE_UTF8 حينئذٍ (إعداد مركز الإعدادات).
 * 5) MODE_UTF8 يرسل النص المنطقي كما هو (الطابعة الحديثة تشكّل وترتّب داخلياً).
 * قيد متبقٍ: طابعة تفهم UTF-8 ولا تشكّل العربية داخلياً لا يغطيها الوضعان
 * الحاليان (تحتاج إرسال أشكال العرض FE70–FEFF معكوسة — غير مفعّل افتراضياً).
 * 6) الحروف العربية خارج جدول الأشكال (بعض الممدّدة نادراً) تُمرَّر بلا تشكيل،
 * والشفافات (الحركات) بين حرفين لا تمنع اتصالهما لكنها لا تحمل شكلاً.
*/
object EscPos {

    /** سطر صنف في الإيصال */
    data class ItemLine(val desc: String, val qty: String, val price: String, val total: String)

    /** نموذج إيصال جاهز للطباعة — التسميات تُحلّ من الموارد قبل البناء */
    data class Receipt(
        val businessName: String,
        val title: String,               // مثال: «فاتورة بيع • INV-12»
        val dateText: String,
        val partyName: String,
        val lines: List<ItemLine>,
        val totals: List<Pair<String, String>>, // (تسمية، قيمة) — الأخير يُطبع عريضاً
        val statusText: String,
        val footer: String,
        // [P38-Z2] حمولة QR الضريبي (Base64/TLV من ZatcaPayload الموحد) — null = لا QR
        // (منشأة غير مسجلة أو فشل آمن) — الافتراضي null يحفظ كل الاستدعاءات القائمة
        val qrPayload: String? = null
    )

    // ── أوامر ESC/POS القياسية ──
    private val C_INIT = byteArrayOf(0x1B, 0x40)              // تهيئة
    private val C_LEFT = byteArrayOf(0x1B, 0x61, 0x00)        // محاذاة يسار
    private val C_CENTER = byteArrayOf(0x1B, 0x61, 0x01)      // محاذاة وسط
    private val C_RIGHT = byteArrayOf(0x1B, 0x61, 0x02)       // محاذاة يمين
    private val C_BOLD_ON = byteArrayOf(0x1B, 0x45, 0x01)     // عريض
    private val C_BOLD_OFF = byteArrayOf(0x1B, 0x45, 0x00)
    private val C_BIG = byteArrayOf(0x1D, 0x21, 0x11)         // مضاعف الحجم
    private val C_NORMAL = byteArrayOf(0x1D, 0x21, 0x00)
    private val C_CUT = byteArrayOf(0x1D, 0x56, 0x42, 0x00)   // قص جزئي

    // ── [P38-Z2] أوامر QR القياسية GS ( k — نموذج 2 الصيغة الشائعة للطابعات الحرارية ──
    /** اختيار نموذج QR 2: GS ( k 04 00 31 41 32 00 */
    private val C_QR_MODEL = byteArrayOf(0x1D, 0x28, 0x6B, 0x04, 0x00, 0x31, 0x41, 0x32, 0x00)
    /** حجم الوحدة 6 نقاط: GS ( k 03 00 31 43 06 */
    private val C_QR_SIZE = byteArrayOf(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x43, 0x06)
    /** مستوى تصحيح الخطأ M: GS ( k 03 00 31 45 31 */
    private val C_QR_ERROR = byteArrayOf(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x45, 0x31)

    // ── : دعم العربية على الطابعات الاقتصادية ──
    /**وضع الترميز الافتراضي — CP1256 (بايت واحد لكل حرف) لمعظم طابعات 58مم */
    const val MODE_CP1256 = 0
    /**مسار UTF-8 القديم — للطابعات الحديثة التي تشكّل العربية داخلياً */
    const val MODE_UTF8 = 1
    /**أمر اختيار جدولة الرموز ESC t 32 — قيمة CP1256 الشائعة في firmware الأجهزة المستنسخة */
    private val C_CODEPAGE_CP1256 = byteArrayOf(0x1B, 0x74, 0x20)

    /**هل المحرف حرف/ترقيم عربي (يُعكس ويُرمَّز CP1256)؟ */
    private fun isArabicChar(c: Char): Boolean =
        c == '\u060C' || c == '\u061F' || c == '\u0640' ||
            c in '\u0621'..'\u0652' || c in '\u066E'..'\u06D3' ||
            c in '\uFB50'..'\uFDFF' || c in '\uFE70'..'\uFEFF'

    /**هل يحوي النص عربية أصلاً؟ (يُستخدم لإصدار أمر ESC t عند الحاجة فقط) */
    private fun hasArabic(s: String): Boolean = s.any { isArabicChar(it) }

    /**
 * : تحويل النص إلى بايتات CP1256 مع ترتيب بصري كامل للمقاطع العربية.
 * [P6-M31 إصلاح] المسار الآن: visualLine (تشكيل أشكال العرض + عكس ترتيب
 * المقاطع) ثم طيّ الأشكال إلى حروفها الأساسية عند ترميز windows-1256 —
 * لطابعة ترسم بايتات LTR. الأرقام واللاتينية تبقى بترتيبها الطبيعي،
 * والفواصل المحايدة (. ,) المحصورة بين حروف عربية تُعامل كجزء من المقطع
 * (مثل رمز العملة «ر.س») وفق قاعدة المحايد في الاتجاهية ثنائية الاتجاه.
 * ملاحظة: طابعات firmware بنمط CP720 قد تحتاج ضبط قيمة ESC t يدوياً (32 هنا).
*/
    fun arabicText(s: String): ByteArray = cp1256Bytes(visualLine(s))

    // ── [P6-M31 إصلاح] المُشكّل وأشكال العرض (Presentation Forms-B) ──

    /** أنواع الاتصال: ثنائي / لليمين فقط (يسبق) / غير متصل / شفاف (حركة) */
    private const val JT_D = 0
    private const val JT_R = 1
    private const val JT_N = 2
    private const val JT_T = 3

    private fun joinType(c: Char): Int = when (c) {
        '\u0621' -> JT_N                                   // الهمزة لا تتصل
        '\u0622', '\u0623', '\u0624', '\u0625', '\u0627', '\u0629',
        '\u062F', '\u0630', '\u0631', '\u0632', '\u0648', '\u0649',
        '\u0671', '\u06D2' -> JT_R                         // تتصل بالسابق فقط
        in '\u0610'..'\u061A', in '\u064B'..'\u065F', '\u0670' -> JT_T  // حركات شفافة
        else -> JT_D                                       // التطويل وبقية الحروف ثنائية الاتصال
    }

    /** حرف عربي له مواضع اتصال (لا يشمل الحركات) */
    private fun isArabicLetter(c: Char): Boolean =
        c in '\u0621'..'\u064A' || c in '\u066E'..'\u06D3'

    private fun isTransparent(c: Char): Boolean = joinType(c) == JT_T

    /**
     * جدول الأشكال: الحرف الأساسي → [منفصل، نهائي، ابتدائي، وسطي] (ما لا ينطبق يُحذف).
     * تشمل الأساسية المشتركة + الممدّدة الشائعة في أسماء المنتجات (پ چ ژ ک گ ی).
     */
    private val formTable: Map<Char, IntArray> = mapOf(
        '\u0621' to intArrayOf(0xFE80),
        '\u0622' to intArrayOf(0xFE81, 0xFE82),
        '\u0623' to intArrayOf(0xFE83, 0xFE84),
        '\u0624' to intArrayOf(0xFE85, 0xFE86),
        '\u0625' to intArrayOf(0xFE87, 0xFE88),
        '\u0626' to intArrayOf(0xFE89, 0xFE8A, 0xFE8B, 0xFE8C),
        '\u0627' to intArrayOf(0xFE8D, 0xFE8E),
        '\u0628' to intArrayOf(0xFE8F, 0xFE90, 0xFE91, 0xFE92),
        '\u0629' to intArrayOf(0xFE93, 0xFE94),
        '\u062A' to intArrayOf(0xFE95, 0xFE96, 0xFE97, 0xFE98),
        '\u062B' to intArrayOf(0xFE99, 0xFE9A, 0xFE9B, 0xFE9C),
        '\u062C' to intArrayOf(0xFE9D, 0xFE9E, 0xFE9F, 0xFEA0),
        '\u062D' to intArrayOf(0xFEA1, 0xFEA2, 0xFEA3, 0xFEA4),
        '\u062E' to intArrayOf(0xFEA5, 0xFEA6, 0xFEA7, 0xFEA8),
        '\u062F' to intArrayOf(0xFEA9, 0xFEAA),
        '\u0630' to intArrayOf(0xFEAB, 0xFEAC),
        '\u0631' to intArrayOf(0xFEAD, 0xFEAE),
        '\u0632' to intArrayOf(0xFEAF, 0xFEB0),
        '\u0633' to intArrayOf(0xFEB1, 0xFEB2, 0xFEB3, 0xFEB4),
        '\u0634' to intArrayOf(0xFEB5, 0xFEB6, 0xFEB7, 0xFEB8),
        '\u0635' to intArrayOf(0xFEB9, 0xFEBA, 0xFEBB, 0xFEBC),
        '\u0636' to intArrayOf(0xFEBD, 0xFEBE, 0xFEBF, 0xFEC0),
        '\u0637' to intArrayOf(0xFEC1, 0xFEC2, 0xFEC3, 0xFEC4),
        '\u0638' to intArrayOf(0xFEC5, 0xFEC6, 0xFEC7, 0xFEC8),
        '\u0639' to intArrayOf(0xFEC9, 0xFECA, 0xFECB, 0xFECC),
        '\u063A' to intArrayOf(0xFECD, 0xFECE, 0xFECF, 0xFED0),
        '\u0641' to intArrayOf(0xFED1, 0xFED2, 0xFED3, 0xFED4),
        '\u0642' to intArrayOf(0xFED5, 0xFED6, 0xFED7, 0xFED8),
        '\u0643' to intArrayOf(0xFED9, 0xFEDA, 0xFEDB, 0xFEDC),
        '\u0644' to intArrayOf(0xFEDD, 0xFEDE, 0xFEDF, 0xFEE0),
        '\u0645' to intArrayOf(0xFEE1, 0xFEE2, 0xFEE3, 0xFEE4),
        '\u0646' to intArrayOf(0xFEE5, 0xFEE6, 0xFEE7, 0xFEE8),
        '\u0647' to intArrayOf(0xFEE9, 0xFEEA, 0xFEEB, 0xFEEC),
        '\u0648' to intArrayOf(0xFEED, 0xFEEE),
        '\u0649' to intArrayOf(0xFEEF, 0xFEF0),
        '\u064A' to intArrayOf(0xFEF1, 0xFEF2, 0xFEF3, 0xFEF4),
        '\u0671' to intArrayOf(0xFB50, 0xFB51),
        '\u067E' to intArrayOf(0xFB56, 0xFB57, 0xFB58, 0xFB59),
        '\u0686' to intArrayOf(0xFB7A, 0xFB7B, 0xFB7C, 0xFB7D),
        '\u0698' to intArrayOf(0xFB8A, 0xFB8B),
        '\u06A9' to intArrayOf(0xFB8E, 0xFB8F, 0xFB90, 0xFB91),
        '\u06AF' to intArrayOf(0xFB92, 0xFB93, 0xFB94, 0xFB95),
        '\u06CC' to intArrayOf(0xFBFC, 0xFBFD, 0xFBFE, 0xFBFF),
        '\u06D2' to intArrayOf(0xFBAE, 0xFBAF)
    )

    /** مقيّدات اللام-ألف: (الحرف الألفي → مقيّد منفصل/نهائي) */
    private fun lamAlefForm(alef: Char?, joinsPrev: Boolean): Char? = when (alef) {
        '\u0627' -> if (joinsPrev) '\uFEFC' else '\uFEFB'
        '\u0623' -> if (joinsPrev) '\uFEF8' else '\uFEF7'
        '\u0625' -> if (joinsPrev) '\uFEFA' else '\uFEF9'
        '\u0622' -> if (joinsPrev) '\uFEF4' else '\uFEF3'
        else -> null
    }

    /** هل يتصل الحرف في الموضع i بما قبله؟ (بتخطي الشفافات) */
    private fun prevJoins(s: String, i: Int): Boolean {
        var k = i - 1
        while (k >= 0 && isTransparent(s[k])) k--
        return k >= 0 && isArabicLetter(s[k]) && joinType(s[k]) == JT_D && joinType(s[i]) != JT_N
    }

    /** هل يتصل الحرف في الموضع i بما بعده؟ (بتخطي الشفافات) */
    private fun nextJoins(s: String, i: Int): Boolean {
        if (joinType(s[i]) != JT_D) return false
        var k = i + 1
        while (k < s.length && isTransparent(s[k])) k++
        return k < s.length && isArabicLetter(s[k]) && joinType(s[k]) != JT_N
    }

    /**
     * [P6-M31 إصلاح] المُشكّل: النص المنطقي → أشكال العرض Presentation Forms-B
     * حسب موضع كل حرف (منفصل/نهائي/ابتدائي/وسطي) مع مقيّدات اللام-ألف. الحركات شفافة
     * لا تمنع الاتصال، والحروف خارج الجدول تُمرَّر كما هي.
     */
    fun shape(s: String): String {
        if (!s.any { isArabicLetter(it) }) return s
        val out = StringBuilder(s.length + 4)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                !isArabicLetter(c) -> { out.append(c); i++ }
                c == '\u0644' -> {
                    // اللام-ألف: ل + ألفي (تخطي الشفافات بينهما) → مقيّد واحد
                    var k = i + 1
                    while (k < s.length && isTransparent(s[k])) k++
                    val lig = lamAlefForm(s.getOrNull(k), prevJoins(s, i))
                    if (lig != null) {
                        out.append(lig)
                        for (m in i + 1 until k) out.append(s[m]) // الشفافات تُمرَّر بعد المقيّد
                        i = k + 1
                    } else {
                        appendForm(out, s, i); i++
                    }
                }
                else -> { appendForm(out, s, i); i++ }
            }
        }
        return out.toString()
    }

    /** اختيار شكل الحرف حسب اتصاله بالجانبين */
    private fun appendForm(out: StringBuilder, s: String, i: Int) {
        val forms = formTable[s[i]]
        if (forms == null) { out.append(s[i]); return }
        val jp = prevJoins(s, i)
        val jn = nextJoins(s, i)
        val form = when {
            jp && jn && forms.size > 3 -> forms[3]
            jp -> forms[1]
            jn && forms.size > 2 -> forms[2]
            else -> forms[0]
        }
        out.append(form.toChar())
    }

    /**
     * [P6-M31 إصلاح] الترتيب البصري للسطر: تقسيم إلى مقاطع عربية (حرف + ملاصقاته
     * من مسافة/نقطة/فاصلة) ومقاطع غير عربية، ثم عكس ترتيب المقاطع وعكس حروف
     * المقاطع العربية بعد تشكيلها — فيُطبع أول المقطع المنطقي أقصى يمين الورق
     * (تسمية الإجمالي يميناً وقيمتها يساراً) مطابقاً لعرض bidi للمعاينة.
     * السطر بلا عربية يعيد كما هو (مسار لاتيني سليم).
     */
    fun visualLine(s: String): String {
        if (!hasArabic(s)) return s
        val segIsAr = ArrayList<Boolean>()
        val segText = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            if (isArabicChar(s[i])) {
                var j = i
                while (j < s.length && (isArabicChar(s[j]) || s[j] == ' ' || s[j] == '.' || s[j] == ',')) j++
                var end = j
                while (end > i && s[end - 1] == ' ') end--   // المسافات الطرفية خارج النافذة
                segIsAr.add(true); segText.add(s.substring(i, end))
                if (j > end) { segIsAr.add(false); segText.add(" ".repeat(j - end)) }
                i = j
            } else {
                var j = i
                while (j < s.length && !isArabicChar(s[j])) j++
                segIsAr.add(false); segText.add(s.substring(i, j))
                i = j
            }
        }
        val out = StringBuilder(s.length)
        for (k in segText.indices.reversed()) {
            out.append(if (segIsAr[k]) shape(segText[k]).reversed() else segText[k])
        }
        return out.toString()
    }

    /** طيّ أشكال العرض إلى الأساس (المقيّد اللام-ألف → زوجيه بالترتيب البصري) ثم ترميز windows-1256 */
    private fun cp1256Bytes(visual: String): ByteArray {
        val needsFold = visual.any { it in '\uFB50'..'\uFEFF' }
        val toEncode = if (!needsFold) visual else StringBuilder(visual.length + 4).apply {
            for (c in visual) append(foldFormToBase[c] ?: c)
        }.toString()
        // المحارف غير القابلة للتحويل تُستبدل بـ '?' تلقائياً بواسطة المرمِّز
        return toEncode.toByteArray(charset("windows-1256"))
    }

    /** خريطة الطي: شكل العرض → الحرف/الزوج الأساسي المكافئ بصرياً */
    private val foldFormToBase: Map<Char, String> by lazy {
        val m = HashMap<Char, String>()
        formTable.forEach { (base, forms) -> forms.forEach { f -> m[f.toChar()] = base.toString() } }
        // اللام-ألف: الألف يُرسم يسار اللام فيرسم أول السطر البصري
        m['\uFEFB'] = "ال"; m['\uFEFC'] = "ال"
        m['\uFEF7'] = "أل"; m['\uFEF8'] = "أل"
        m['\uFEF9'] = "إل"; m['\uFEFA'] = "إل"
        m['\uFEF3'] = "آل"; m['\uFEF4'] = "آل"
        m
    }

    /**
     * سطر «تسمية ..... قيمة» مبطّن إلى عرض الورق بعدّ الأحرف (وليس البايتات)
     * حتى تتطابق الأعمدة مع النص العربي متعدد البايتات.
     */
    fun row(label: String, value: String, width: Int): String {
        val gap = width - label.length - value.length
        return if (gap >= 1) label + " ".repeat(gap) + value else label + " " + value
    }

    /**
     * [P38-Z2] كتلة QR الضريبي كاملة — دالة نقية بايتاً ببايت (نمط اختبار حرفي
     * مثل InventoryReportsP4WallsTest لبايتات ملصق السعر):
     *  1) أوامر الضبط الثابتة الثلاثة (نموذج 2 / وحدة 6 / تصحيح M)
     *  2) تخزين البيانات: GS ( k pL pH 49 80 30 d1..dk — pL/pH (صيغة صغيرة أولاً)
     *     تحسب 3 + m(2 بايت) + طول البيانات
     *  3) أمر الطباعة: GS ( k 03 00 49 51 30
     * الحمولة ASCII/Base64 من ZatcaPayload فلا تمر بمسار العربية/CP1256 إطلاقاً —
     * وهي مستقلة عن width وarabicMode فتطبع نفسها على 58مم و80مم.
     */
    fun qrBlock(payload: String): ByteArray {
        val d = payload.toByteArray(Charsets.US_ASCII)
        val storeLen = d.size + 3
        val store = byteArrayOf(
            0x1D, 0x28, 0x6B,
            (storeLen and 0xFF).toByte(), ((storeLen shr 8) and 0xFF).toByte(),
            0x31, 0x50, 0x30
        ) + d
        val print = byteArrayOf(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x51, 0x30)
        return C_QR_MODEL + C_QR_SIZE + C_QR_ERROR + store + print
    }

    /**
 * بناء بايتات الإيصال كاملة — width = 32 لورق 58مم أو 48 لورق 80مم
 * : arabicMode يحدد ترميز النص — الافتراضي CP1256 المعكوس للطابعات الاقتصادية،
 * وMODE_UTF8 يعيد بايتات المسار القديم نفسها حرفياً.
 * [P6-M31 إصلاح] الإيصال الحاوي على العربية يُبنى بترتيب بصري RTL: سطر
 * «تسمية..... قيمة» يُطبع قيمته أقصى اليسار وتسميته أقصى اليمين، ووصف الصنف
 * يُحاذى يميناً — مطابق للتوزيع المكاني الذي يعرضه bidi في المعاينة النصية.
*/
    fun build(r: Receipt, width: Int = 32, arabicMode: Int = MODE_CP1256): ByteArray {
        val out = ByteArrayOutputStream(2048)
        fun cmd(b: ByteArray) = out.write(b)
        // كتابة النص حسب وضع الترميز المطلوب (CP1256 يشمل التشكيل والترتيب البصري)
        fun t(s: String) = when (arabicMode) {
            MODE_UTF8 -> out.write(s.toByteArray(Charsets.UTF_8))
            else -> out.write(arabicText(s))
        }
        // [P6-M31 إصلاح] هل الإيصال عربي؟ — يقرر ترتيب الأسطر ومحاذاتها وإصدار ESC t
        val rtlReceipt = hasArabic(r.businessName) || hasArabic(r.title) || hasArabic(r.dateText) ||
            hasArabic(r.partyName) || hasArabic(r.statusText) || hasArabic(r.footer) ||
            r.lines.any { hasArabic(it.desc) || hasArabic(it.total) } ||
            r.totals.any { hasArabic(it.first) || hasArabic(it.second) }
        /**
         * سطر «تسمية ..... قيمة» — CP1256 مع إيصال عربي: تُبنى البايتات من طرفي
         * السطر بعد الترتيب البصري لكل طرف على حدة (فلا تلتقط نافذة العربية مسافات
         * الطرف الآخر) ثم القيمة يساراً والتسمية يميناً. MODE_UTF8: المنطقي كما هو
         * (الطابعة الذكية ترتّب داخلياً)، وإيصال بلا عربية: المسار القديم حرفياً.
         */
        fun rowBytes(label: String, value: String): ByteArray = when {
            arabicMode == MODE_UTF8 -> row(label, value, width).toByteArray(Charsets.UTF_8)
            rtlReceipt -> {
                val gap = width - label.length - value.length
                val pad = if (gap >= 1) " ".repeat(gap) else " "
                cp1256Bytes(visualLine(value)) + pad.toByteArray(Charsets.US_ASCII) +
                    cp1256Bytes(visualLine(label))
            }
            else -> arabicText(row(label, value, width))
        }
        fun nl(n: Int = 1) = repeat(n) { out.write(0x0A) }
        fun centered(s: String, big: Boolean = false, bold: Boolean = false) {
            cmd(C_CENTER)
            if (big) cmd(C_BIG)
            if (bold) cmd(C_BOLD_ON)
            t(s); nl()
            if (bold) cmd(C_BOLD_OFF)
            if (big) cmd(C_NORMAL)
        }
        val sep = "-".repeat(width.coerceIn(20, 64))

        cmd(C_INIT)
        // إصدار ESC t 32 (CP1256) عند وجود عربية في المحتوى — firmware CP720 قد يحتاج ضبطاً
        if (arabicMode == MODE_CP1256 && rtlReceipt) cmd(C_CODEPAGE_CP1256)
        centered(r.businessName, big = true, bold = true)
        centered(r.title, bold = true)
        centered(r.dateText)
        if (r.partyName.isNotBlank()) centered(r.partyName)
        t(sep); nl()

        cmd(C_LEFT)
        r.lines.forEach { l ->
            if (l.qty.isBlank() && l.price.isBlank()) {
                // سطر مفرد «بيان..... قيمة» — يُستخدم في ملخصات التقارير الحرارية
                out.write(rowBytes(l.desc, l.total)); nl()
            } else {
                // [P6-M31 إصلاح] وصف الصنف على يمين الورق في الإيصالات العربية كالمعاينة
                cmd(if (rtlReceipt) C_RIGHT else C_LEFT)
                t(l.desc); nl()
                cmd(C_LEFT)
                out.write(rowBytes(l.qty + " × " + l.price, l.total)); nl()
            }
        }
        t(sep); nl()

        r.totals.forEachIndexed { i, (label, value) ->
            val last = i == r.totals.lastIndex
            if (last) cmd(C_BOLD_ON)
            out.write(rowBytes(label, value)); nl()
            if (last) cmd(C_BOLD_OFF)
        }
        if (r.statusText.isNotBlank()) {
            cmd(C_CENTER); cmd(C_BOLD_ON); t(r.statusText); nl(); cmd(C_BOLD_OFF)
        }
        // [P38-Z2] رمز QR الضريبي بعد سطر الحالة وقبل الفاصل الختامي — وسط الورق
        // دائماً (محاذاة C_CENTER تنطبق على GS ( k في الطابعات القياسية)، وبايتاته
        // ASCII خالصة فلا تمر بدوال النص (t/arabicText) — يُتخطى كلياً عند null
        // فتبقى بايتات الإيصال بلا QR مطابقة للمسار القديم حرفياً (عقد الاختبار)
        if (r.qrPayload != null) {
            cmd(C_CENTER)
            out.write(qrBlock(r.qrPayload))
            nl(2)
            cmd(C_LEFT)
        }
        t(sep); nl()
        centered(r.footer)
        nl(3)
        out.write(C_CUT)
        return out.toByteArray()
    }
}

/** تجميع إيصال من فاتورة محفوظة — يحلّ كل التسميات من موارد اللغة الحالية */
object ReceiptFactory {

    fun fromInvoice(
        ctx: Context, inv: Invoice, items: List<InvoiceItem>, party: Party?,
        businessName: String, symbol: String,
        // [P38-Z2] الرقم الضريبي من نفس اللقطة (settings.snapshot().taxNumber) التي
        // يُبنى بها QR فاتورة PDF — الافتراضي "" يبقي كل المستدعين القائمين صحيحين
        vatNumber: String = ""
    ): EscPos.Receipt {
        // [P38-Z2] حمولة QR موحّدة مع PDF (ZatcaPayload) — حتمية من لقطة الفاتورة
        // (inv.date/total/taxAmount/number) فتطابق فاتورة واحدة QR في مساري الطباعة.
        // null عند رقم ضريبي فارغ، وأي فشل غير متوقع لا يسقط طباعة الإيصال أبداً
        // (قاعدة P9-9a نفسها — طباعة صالحة أولاً)
        val zatcaQr = try {
            com.superbiz.app.security.ZatcaPayload.qr(
                ctx, businessName, vatNumber, inv.date,
                Money.fromPiasters(inv.total), Money.fromPiasters(inv.taxAmount),
                items.size, inv.number
            )
        } catch (_: Exception) { null }
        val typeText = ctx.getString(if (inv.isSale) R.string.type_sale else R.string.type_purchase)
        val title = ctx.getString(R.string.receipt_invoice) + " " + typeText + " • " + inv.number
        val dateText = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(Date(inv.date))

        // [P33-P8] العتبات مساواة صحيحة بلا عتبة فاصلة عائمة (المبالغ قروش Long)
        val statusText = when {
            inv.status == 3 -> ctx.getString(R.string.status_void)
            inv.open <= 0L -> ctx.getString(R.string.status_paid)
            inv.paid > 0L -> ctx.getString(R.string.status_partial)
            else -> ctx.getString(R.string.status_unpaid)
        }

        // [P33-P8] المبالغ قروش — العرض عبر numP/formatP (التعبئة والأعمدة سلاسل كما هي)
        val totals = mutableListOf<Pair<String, String>>(
            ctx.getString(R.string.subtotal) to Money.numP(inv.subtotal)
        )
        if (inv.discount > 0L) totals += ctx.getString(R.string.discount) to Money.numP(inv.discount)
        if (inv.taxAmount > 0L) totals += ctx.getString(R.string.tax) to Money.numP(inv.taxAmount)
        totals += ctx.getString(R.string.total) to Money.formatP(inv.total, symbol)
        if (inv.paid > 0L) {
            totals += ctx.getString(R.string.paid_amount) to Money.numP(inv.paid)
            totals += ctx.getString(R.string.inst_remaining) to Money.numP(inv.open)
        }

        return EscPos.Receipt(
            businessName = businessName.ifBlank { ctx.getString(R.string.business_default) },
            title = title,
            dateText = dateText,
            partyName = party?.name ?: "",
            lines = items.map {
                EscPos.ItemLine(
                    desc = it.desc.ifBlank { ctx.getString(R.string.item) },
                    // [P33-P8] الكمية Double تبقى num؛ السعر وإجمالي السطر قروش عبر numP
                    qty = Money.num(it.qty),
                    price = Money.numP(it.unitPrice),
                    total = Money.numP(it.lineTotal)
                )
            },
            totals = totals,
            statusText = statusText,
            footer = ctx.getString(R.string.receipt_thanks),
            qrPayload = zatcaQr
        )
    }
}
