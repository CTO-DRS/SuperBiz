package com.superbiz.app.pdf.statement

import com.superbiz.app.domain.statement.BalanceDirection
import com.superbiz.app.domain.statement.StatementData
import com.superbiz.app.domain.statement.StatementLang
import com.superbiz.app.domain.statement.StatementTxRow
import com.superbiz.app.domain.statement.StatementTxTypes
import kotlin.math.abs

/**
 * [P17-b] StatementLayout — محرّك التخطيط الهندسي لكشف الحساب (JVM نقي، صفر اعتماد Android).
 *
 * يفصل «القرار الهندسي» عن «الرسم»: المدخل (StatementData + StatementStyle) والمخرج
 * صفحات من عناصر هندسية (نص/مستطيل/صورة) تحمل إحداثياتها النهائية بالـpt. الرسم
 * الفعلي في StatementPdfRenderer (Android Canvas) — والمحرك نفسه يُختبر على JVM
 * بالكامل (StatementLayoutTest) دون جهاز.
 *
 * ═══ قرارات موثقة ═══
 *
 * 1) الوحدات: pageW/pageH/margin بالـpt (72dpi — A4 طولي 595×842، وlandscape يمرره
 *    المستدعي معكوساً 842×595 — المحرك لا يبدّل الأبعاد أبداً). أحجام الخطوط pt
 *    (المقصود sp عند الرسم بالنسبة نفسها). margin يمرر pt من المستدعي؛ المحول
 *    المعتمد dp→pt هو DP2PT=72/160=0.45 (نفس عقد StatementStyle: «تُحوَّل عند الرسم
 *    إلى pt بدلالة 72/160») — أوردته القرار في Renderer ومرجعية واحدة هنا كي يستعمله
 *    الاختبار والراسم بالعامل نفسه.
 *
 * 2) كل العناصر داخل الهوامش: شريط BAND/SIDEBAR الممتد «كامل العرض/الطول» يُرسم داخل
 *    صندوق المحتوى [margin, pageW-margin]×[margin, pageH-margin] لا من حافة الورقة —
 *    قرار مقصود: يبقي ثابت «لا شيء خارج الهوامش» صارماً وقابلاً للفحص الآلي في
 *    الاختبار (50 قالباً × 3 لغات) دون استثناءات لكل نمط.
 *
 * 3) التوافق RTL: isRtl ⇒ عكس ترتيب الأعمدة فيزيائياً، وقلب محاذاة النصوص اللغوية
 *    (البداية تصبح اليمين)، ومرآة زوايا التوقيع/الخاتم. أعمدة المبالغ تبقى محاذاة
 *    يمينية (rightX) حتى في RTL — الأرقام لاتينية ومحاذاة الحافة اليمنى = محاذاة
 *    الخانة العشرية = محاذاة الآلاف للعمود كله (عرف محاسبي؛ انظر StatementFormat).
 *
 * 4) BILINGUAL: التسميات مزدوجة «عربي / English»، ورأس الجدول سطران إجباريان (عربي
 *    فوق إنجليزي) — مطابق لبنود فئة BIL في StatementTemplates. قالب BIL يفرض
 *    الثنائية حتى لو كانت لغة البيانات AR/EN، وقالب ARA يفرض RTL حتى لو كانت EN.
 *
 * 5) الصفوف ذرّية: صف الجدول سطر واحد لا ينقسم عبر الصفحات أبداً؛ وكل صفحة تحمل
 *    الترويسة (هندسة HeaderStyle) ورأس الجدول يُعاد في كل صفحة. الملخص المالي
 *    كتلة واحدة (ملخص+ملاحظات+حيز التوقيع) لا تنقسم — تنتقل صفحة كاملة إن ضاقت.
 *
 * 6) حيز التوقيع/الخاتم: يُحجز أسفل آخر صفحة بعد الملخص بمقدار max(التوقيع, الخاتم)
 *    + التسمية + فاصل، والملخص يسبقه دائماً ⇒ لا تداخل بنيوي (الاختبار يقفله).
 *
 * 7) QR التذييل: عنصر LBitmap(kind=QR) في كل صفحة عندما يكون footer من نوع QR_* —
 *    الحمولة عبر qrPayload(): «superbiz://verify/<verificationId>» فقط. تحریم صريح:
 *    لا أرقام مالية/أسماء في الحمولة — خصوصية عند المسح الخارجي، والتحقق المالي
 *    يجري داخل التطبيق بمطابقة بصمة المحتوى (contentHash من 17-a) لا بالـQR نفسه.
 *
 * 8) الحتمية والدفاع: لا عشوائية ولا قراءة حالة خارجية ولا استثناءات تُرمى — كل
 *    المدخلات تُقيَّد coerce، والنصوص الطويلة تُقصّ/تُلفّ بحدود صريحة، فحتى بيانات
 *    فاسدة (سالب/NaN/سلاسل عملاقة) تُنتج تخطيطاً سليماً لا انهياراً.
 *
 * امتداد العقد الوحيد المسموح: LRect يحمل color/fill بقيم افتراضية (موثق في رأس
 * StatementStyle.kt) — fill=true مستطيل معبأ (شرائط/تظليل/بطاقات)، fill=false خط
 * أو إطار (الراسم يرسم h≤1.5 كخط drawLine وإلا إطار STROKE).
 */

/** مقياس عرض النص — يستبدله الراسم بـ Paint.measureText ويستبدله الاختبار بمقياس ثابت */
interface TextMeasurer {
    fun width(text: String, sizeSp: Float, bold: Boolean): Float
}

/** مستطيل هندسي بالـpt — color/fill امتداد المحرك الوحيد المسموح (انظر رأس الملف) */
data class LRect(
    val x: Float, val y: Float,
    val w: Float, val h: Float,
    val color: Int = 0,
    val fill: Boolean = false
)

/** نص بموضعه النهائي — rect.w=عرض القياس وrect.h=سطر الكتابة؛ المحاذاة «مخبوزة» في rect.x */
data class LText(
    val text: String,
    val sizeSp: Float,
    val bold: Boolean,
    val color: Int,
    val rect: LRect
)

/** نوع الصورة المطلوب وضعها — فكّ الصورة الفعلي بيد الراسم (المستدعي يمرر Bitmap؟) */
enum class BitmapKind { LOGO, PHOTO, SIGNATURE, STAMP, QR }

/** عنصر صورة — يُصدَر حتى لو كانت الصورة المصدر null (الحيز محجوز والراسم يتخطى) */
data class LBitmap(
    val kind: BitmapKind,
    val rect: LRect,
    val opacity: Int = 255
)

/** صفحة مكتملة — index يبدأ من 0 ورقم الصفحة المعروض = index+1 */
data class LPage(
    val index: Int,
    val elements: List<LText>,
    val bitmaps: List<LBitmap>,
    val rules: List<LRect>
)

object StatementLayout {

    /** عامل تحويل dp→pt المعتمد (72/160) — مرجع واحد يستعمله الراسم والاختبار */
    const val DP2PT = 72f / 160f

    // ── ثوابت المحرك (pt) ──
    private const val BASE_HEADING = 15f      // أساس خط العناوين (1f = هذا الأساس)
    private const val BASE_BODY = 10f         // أساس خط المتن
    private const val LINE_H = 1.30f          // ارتفاع صندوق السطر كنسبة من حجم الخط
    private const val SIDEBAR_W = 88f         // عرض الشريط الجانبي (HeaderStyle.SIDEBAR)
    private const val SIDEBAR_GAP = 10f
    private const val BAND_H = 78f            // ارتفاع شريط الترويسة
    private const val BOXED_H = 70f           // ارتفاع إطار الترويسة
    private const val SPLIT_H = 64f           // ارتفاع ترويسة القسمين
    private const val CELL_PAD = 4f           // حشوة خلايا الجدول
    private const val ROW_H_BASE = 19f        // أدنى ارتفاع صف
    private const val QR_SIZE = 84f           // مربع QR في التذييل (موثق في StatementStyle)
    private const val FOOTER_PLAIN = 42f      // حجز تذييل عادي (سطرا ترقيم+تحقق)
    private const val FOOTER_CLASSIC = 50f    // حجز التذييل الكلاسيكي (خط مزدوج + سطر إضافي)
    private const val FOOTER_QR = 100f        // حجز تذييل QR (84 + هامش)
    private const val SIG_BOX = 62f           // صندوق التوقيع الأساسي
    private const val STAMP_BOX = 70f         // صندوق الخاتم الأساسي
    private const val ZONE_GAP = 12f          // فاصل حيز التوقيع عن ما قبله
    private const val EMPTY_H = 46f           // ارتفاع كتلة الحالة الفارغة
    private const val MAX_NOTE_LINES = 8      // سقف لفّ الملاحظات (دفاع ضد إدخال ضخم)
    private const val EPS = 0.01f

    // ═══ الحالة الفارغة — ثوابت خاصة بهذا الملف (AR/EN/BILINGUAL حسب d.lang) ══
    private const val EMPTY_AR = "لا توجد حركات في هذه الفترة"
    private const val EMPTY_EN = "No transactions in this period"
    private const val TITLE_AR = "كشف حساب"
    private const val TITLE_EN = "Statement of Account"

    /** حمولة QR التحقق — مؤشر فقط: لا بيانات مالية فيها إطلاقاً (قرار 7 أعلاه) */
    fun qrPayload(verificationId: String): String = "superbiz://verify/" + verificationId.trim()

    // ─────────────────────────────────────────────────────────────────────────

    fun layout(
        d: StatementData,
        s: StatementStyle,
        pageW: Float,
        pageH: Float,
        margin: Float,
        measurer: TextMeasurer
    ): List<LPage> {
        val ctx = Ctx(d, s, pageW, pageH, margin, measurer)
        return ctx.run()
    }

    // ═══════════════════════ محرك البناء الداخلي ═══════════════════════

    private class Ctx(
        val d: StatementData,
        val s: StatementStyle,
        pageWIn: Float,
        pageHIn: Float,
        marginIn: Float,
        val m: TextMeasurer
    ) {
        // ── قيود مدفوعة (قرار 8: لا استثناءات أبداً) ──
        val pageW = pageWIn.coerceAtLeast(200f)
        val pageH = pageHIn.coerceAtLeast(200f)
        val margin = marginIn.coerceIn(8f, minOf(pageW, pageH) * 0.25f - 1f)

        val cat = StatementTemplates.categoryOf(s)
        val rtl = d.isRtl || cat == TemplateCategory.ARABIC        // قرار 4: ARA يفرض RTL
        val bi = d.lang == StatementLang.BILINGUAL || cat == TemplateCategory.BILINGUAL

        val headingSize = (BASE_HEADING * s.headingFontScale.coerceIn(0.8f, 2f))
        val bodySize = (BASE_BODY * s.bodyFontScale.coerceIn(0.8f, 1.5f))
        val smallSize = (8.5f * s.bodyFontScale.coerceIn(0.9f, 1.1f))
        val headCellSize = bodySize + 1.2f
        val footerSize = 8f * s.bodyFontScale.coerceIn(0.9f, 1.1f)

        val spacing = (s.spacingDp * DP2PT).coerceIn(4f, 20f)
        val rowH = maxOf(ROW_H_BASE, bodySize * LINE_H + 8f)
        val footerReserve = when (s.footer) {
            FooterStyle.QR_LEFT, FooterStyle.QR_RIGHT -> FOOTER_QR
            FooterStyle.CLASSIC -> FOOTER_CLASSIC
            else -> FOOTER_PLAIN
        }
        val footerTop = pageH - margin - footerReserve   // المحتوى يتوقف فوق هذا الخط

        // صندوق المحتوى (قرار 2: كل شيء داخل الهوامش؛ SIDEBAR يقتطع عموده من البداية)
        private val sidebarTotal = if (s.header == HeaderStyle.SIDEBAR) SIDEBAR_W + SIDEBAR_GAP else 0f
        val hx0: Float
        val hx1: Float
        val stripX: Float   // x الشريط الجانبي عند SIDEBAR وإلا 0
        init {
            if (s.header == HeaderStyle.SIDEBAR) {
                if (rtl) {
                    stripX = pageW - margin - SIDEBAR_W
                    hx1 = stripX - SIDEBAR_GAP
                    hx0 = margin
                } else {
                    stripX = margin
                    hx0 = stripX + SIDEBAR_W + SIDEBAR_GAP
                    hx1 = pageW - margin
                }
            } else {
                stripX = Float.NaN
                hx0 = margin
                hx1 = pageW - margin
            }
        }

        // ── تجميع الصفحات ──
        private class Buf {
            val texts = ArrayList<LText>()
            val bitmaps = ArrayList<LBitmap>()
            val rules = ArrayList<LRect>()
        }

        private val bufs = ArrayList<Buf>()
        private var buf = Buf()
        private var y = 0f
        private var pageIdx = 0

        // ── أعمدة الجدول (فيزيائية يسار→يمين) ──
        private var colX = FloatArray(6)
        private var colW = FloatArray(6)
        private var headerH = 26f
        private val tableW: Float get() = hx1 - hx0

        // ═══ أدوات الإضافة ═══

        private fun lineH(size: Float) = size * LINE_H

        private fun addText(text: String, x: Float, yTop: Float, w: Float, size: Float, bold: Boolean, color: Int,
                            clampToContent: Boolean = true) {
            val ww = if (w.isFinite() && w > 0f) w else m.width(text, size, bold).coerceAtLeast(1f)
            // [P17-integration] نصوص الشريط الجانبي تعيش خارج صندوق المحتوى [hx0,hx1] —
            // القيد كان يسحبها إلى حافته فيتداخل محتوى الشريط مع كتلة الميتا
            val xx = if (clampToContent) x.coerceIn(hx0 - EPS, hx1 - ww + EPS) else x
            buf.texts.add(
                LText(text, size.coerceAtLeast(4f), bold, color,
                    LRect(xx, yTop.coerceIn(margin, pageH - margin - lineH(size)), ww, lineH(size)))
            )
        }

        /** نص بطرف البداية (يسار LTR / يمين RTL) داخل [hx0,hx1] */
        private fun addStart(text: String, yTop: Float, size: Float, bold: Boolean, color: Int) {
            val w = m.width(text, size, bold)
            addText(text, if (rtl) hx1 - w else hx0, yTop, w, size, bold, color)
        }

        /** نص بطرف النهاية (يمين LTR / يسار RTL) */
        private fun addEnd(text: String, yTop: Float, size: Float, bold: Boolean, color: Int, edge: Float = hx1) {
            val w = m.width(text, size, bold)
            addText(text, edge - w, yTop, w, size, bold, color)
        }

        private fun addCentered(text: String, cx: Float, yTop: Float, size: Float, bold: Boolean, color: Int, maxW: Float = tableW,
                                clampToContent: Boolean = true) {
            val w = m.width(text, size, bold)
            val eff = if (w > maxW) ellipsize(text, maxW, size, bold) else text
            var ww = if (eff == text) w else m.width(eff, size, bold)
            var effSize = size
            // [P17-integration] الاقتطاع قد يعيد نصاً عرضه المقاس يتجاوز maxW بحرف «…» —
            // نضمن الحد بالتصغير بدل الاعتماد على القياس، وإلا تسربت الخلية لعمودها المجاور
            if (ww > maxW) {
                effSize = (size * (maxW / ww) * 0.96f).coerceAtLeast(size * 0.55f)
                ww = m.width(eff, effSize, bold)
            }
            addText(eff, cx - ww / 2f, yTop, ww, effSize, bold, color, clampToContent)
        }

        private fun addRule(x: Float, yTop: Float, w: Float, h: Float, color: Int, fill: Boolean = false) {
            buf.rules.add(LRect(x, yTop, w, h, color, fill))
        }

        private fun addBitmap(kind: BitmapKind, x: Float, yTop: Float, w: Float, h: Float, opacity: Int = 255) {
            buf.bitmaps.add(LBitmap(kind, LRect(x, yTop, w, h), opacity.coerceIn(0, 255)))
        }

        // ═══ أدوات نصية ═══

        /** تسمية بحسب اللغة/القالب: ثنائية «عربي / English» أو مفردة */
        private fun lbl(ar: String, en: String): String = when {
            bi -> "$ar / $en"
            d.lang == StatementLang.AR -> ar
            else -> en
        }

        /** قصّ بسطر واحد مع علامة حذف — بحث ثنائي حتمي (قرار 8) */
        private fun ellipsize(text: String, width: Float, size: Float, bold: Boolean): String {
            if (m.width(text, size, bold) <= width) return text
            var lo = 0
            var hi = text.length
            var fit = 0
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                if (m.width(text.substring(0, mid) + "…", size, bold) <= width) {
                    fit = mid; lo = mid + 1
                } else hi = mid - 1
            }
            return text.substring(0, fit) + "…"
        }

        /** لفّ كلمات جشع بحد أقصى للأسطر — الكلمة الأطول من السطر تُشطر (تقدم مضمون) */
        private fun wrap(text: String, width: Float, size: Float, bold: Boolean, maxLines: Int): List<String> {
            val w = width.coerceAtLeast(8f)
            val out = ArrayList<String>()
            val words = text.replace('\n', ' ').replace('\r', ' ').trim().split(' ').filter { it.isNotEmpty() }
            var cur = ""
            for (word in words) {
                if (out.size >= maxLines) break
                val cand = if (cur.isEmpty()) word else "$cur $word"
                if (m.width(cand, size, bold) <= w) { cur = cand; continue }
                if (cur.isNotEmpty()) { out.add(cur); cur = "" }
                if (out.size >= maxLines) break
                var piece = word
                while (m.width(piece, size, bold) > w && piece.length > 1) {   // تشليط كلمة عملاقة
                    var lo = 1; var hi = piece.length - 1; var cut = 1
                    while (lo <= hi) {
                        val mid = (lo + hi) / 2
                        if (m.width(piece.substring(0, mid), size, bold) <= w) { cut = mid; lo = mid + 1 } else hi = mid - 1
                    }
                    out.add(piece.substring(0, cut))
                    if (out.size >= maxLines) return out
                    piece = piece.substring(cut)
                }
                cur = piece
            }
            if (cur.isNotEmpty() && out.size < maxLines) out.add(cur)
            return out
        }

        private fun money(v: Long) = StatementFormat.money(v, d.currency, d.lang)  // [P33-P8] قروش

        // ═══ الصفحات ═══

        private fun newPage() {
            bufs.add(buf)
            buf = Buf()
            pageIdx++
            y = margin
            y = drawHeader(y)
        }

        /** ترويسة كل صفحة — تفرّع على هندسة HeaderStyle الخمس (قرار موثق: داخل الهوامش) */
        private fun drawHeader(top: Float): Float {
            return when (s.header) {
                HeaderStyle.BAND -> bandHeader(top, filled = true)
                HeaderStyle.BOXED -> bandHeader(top, filled = false)
                HeaderStyle.SIDEBAR -> sidebarHeader()
                HeaderStyle.CENTERED -> centeredHeader(top)
                HeaderStyle.SPLIT -> splitHeader(top)
            }
        }

        private fun logoSquare(): Float = (44f * s.logoSizeScale.coerceIn(0.6f, 1.6f)).coerceIn(30f, 64f)

        /** BAND (معبأ) و BOXED (إطار) — الهندسة نفسها بتعابير مختلفة */
        private fun bandHeader(top: Float, filled: Boolean): Float {
            val h = BAND_H
            if (filled) addRule(hx0, top, tableW, h, s.primary, fill = true)
            else addRule(hx0, top, tableW, h, s.primary, fill = false)

            val onFill = filled
            val nameColor = if (onFill) s.tableHeadText else s.text
            val subColor = if (onFill) s.tableHeadText else s.secondary

            val logoW = if (PdfElement.LOGO in s.show) logoSquare() else 0f
            val logoTop = top + (h - logoW) / 2f
            // الشعار بطرف البداية والنص بعده (مرآة RTL)
            if (logoW > 0f) {
                val lx = if (rtl) hx1 - CELL_PAD - logoW else hx0 + CELL_PAD
                addBitmap(BitmapKind.LOGO, lx, logoTop, logoW, logoW)
            }
            val textEdge = if (rtl) hx1 - CELL_PAD - logoW - 8f else hx0 + CELL_PAD + logoW + 8f
            var ty = top + 12f
            if (PdfElement.COMPANY_NAME in s.show) {
                val name = ellipsize(d.company.businessName, abs(textEdge - (if (rtl) hx0 + CELL_PAD else hx1 - CELL_PAD)), headingSize * 0.9f, true)
                val w = m.width(name, headingSize * 0.9f, true)
                if (rtl) addEnd(name, ty, headingSize * 0.9f, true, nameColor, edge = textEdge)
                else addText(name, textEdge, ty, w, headingSize * 0.9f, true, nameColor)
                ty += lineH(headingSize * 0.9f) + 2f
            }
            if (PdfElement.CONTACTS in s.show) {
                val c = contactsLine()
                val maxW = abs(textEdge - (if (rtl) hx0 + CELL_PAD else hx1 - CELL_PAD))
                val cl = ellipsize(c, maxW, smallSize, false)
                if (cl.isNotEmpty()) {
                    val w = m.width(cl, smallSize, false)
                    if (rtl) addEnd(cl, ty, smallSize, false, subColor, edge = textEdge)
                    else addText(cl, textEdge, ty, w, smallSize, false, subColor)
                    ty += lineH(smallSize) + 2f
                }
            }
            val ids = idsLine()
            if (ids.isNotEmpty()) {
                val maxW = abs(textEdge - (if (rtl) hx0 + CELL_PAD else hx1 - CELL_PAD))
                val il = ellipsize(ids, maxW, smallSize, false)
                val w = m.width(il, smallSize, false)
                if (rtl) addEnd(il, ty, smallSize, false, subColor, edge = textEdge)
                else addText(il, textEdge, ty, w, smallSize, false, subColor)
            }
            return top + h
        }

        private fun contactsLine(): String {
            val parts = ArrayList<String>()
            d.company.phone?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            d.company.email?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            d.company.address?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            if (PdfElement.COMPANY_PHOTO in s.show) return parts.joinToString("  •  ")
            return parts.joinToString("  •  ")
        }

        private fun idsLine(): String {
            val parts = ArrayList<String>()
            if (PdfElement.COMPANY_NO in s.show) parts.add(lbl("رقم المنشأة", "Company No") + ": " + d.company.companyNo)
            if (PdfElement.TAX_NO in s.show && !d.company.taxNumber.isNullOrBlank()) parts.add(lbl("الرقم الضريبي", "Tax No") + ": " + d.company.taxNumber)
            if (PdfElement.CR_NO in s.show && !d.company.crNumber.isNullOrBlank()) parts.add(lbl("السجل التجاري", "CR No") + ": " + d.company.crNumber)
            return parts.joinToString("  •  ")
        }

        /** SIDEBAR — شريط ممتد من الترويسة حتى التذييل، والمحتوى كله في العرض المتبقي */
        private fun sidebarHeader(): Float {
            addRule(stripX, margin, SIDEBAR_W, footerTop - margin, s.primary, fill = true)
            var cy = margin + 10f
            val innerW = SIDEBAR_W - 16f
            val cx = stripX + SIDEBAR_W / 2f
            if (PdfElement.LOGO in s.show) {
                val lw = (36f * s.logoSizeScale.coerceIn(0.6f, 1.4f)).coerceIn(26f, 50f)
                addBitmap(BitmapKind.LOGO, cx - lw / 2f, cy, lw, lw)
                cy += lw + 8f
            }
            if (PdfElement.COMPANY_NAME in s.show) {
                for (ln in wrap(d.company.businessName, innerW, smallSize + 0.5f, true, 3)) {
                    addCentered(ln, cx, cy, smallSize + 0.5f, true, s.tableHeadText, innerW, clampToContent = false)
                    cy += lineH(smallSize + 0.5f) + 2f
                }
                cy += 4f
            }
            if (PdfElement.CONTACTS in s.show) {
                for (ln in wrap(contactsLine().replace("  •  ", "\n"), innerW, smallSize - 0.5f, false, 4)) {
                    addCentered(ln, cx, cy, smallSize - 0.5f, false, s.tableHeadText, innerW, clampToContent = false)
                    cy += lineH(smallSize - 0.5f) + 2f
                }
            }
            val ids = idsLine()
            if (ids.isNotEmpty()) {
                cy += 2f
                for (ln in wrap(ids.replace("  •  ", "\n"), innerW, smallSize - 0.5f, false, 4)) {
                    addCentered(ln, cx, cy, smallSize - 0.5f, false, s.tableHeadText, innerW, clampToContent = false)
                    cy += lineH(smallSize - 0.5f) + 2f
                }
            }
            return margin + spacing   // المحتوى يبدأ أعلى الصفحة بجانب الشريط
        }

        /** CENTERED — شعار بالمنتصف ثم الاسم ثم التواصل، وقاعدة فاصلة (علامة ترويسة دائمة) */
        private fun centeredHeader(top: Float): Float {
            var cy = top + 6f
            val cx = pageW / 2f
            val logoW = if (PdfElement.LOGO in s.show) logoSquare() else 0f
            if (logoW > 0f) {
                if (PdfElement.COMPANY_PHOTO in s.show) {
                    // الشعار والصورة جنباً إلى جنب حول المنتصف (مرآة RTL)
                    val pw = 38f
                    val lx = if (rtl) cx + 4f else cx - 4f - logoW
                    val px = if (rtl) cx - 4f - pw else cx + 4f
                    addBitmap(BitmapKind.LOGO, lx, cy, logoW, logoW)
                    addBitmap(BitmapKind.PHOTO, px, cy + (logoW - pw) / 2f, pw, pw)
                } else {
                    addBitmap(BitmapKind.LOGO, cx - logoW / 2f, cy, logoW, logoW)
                }
                cy += logoW + 6f
            }
            if (PdfElement.COMPANY_NAME in s.show) {
                addCentered(d.company.businessName, cx, cy, headingSize * 0.95f, true, s.text)
                cy += lineH(headingSize * 0.95f) + 2f
            }
            if (PdfElement.CONTACTS in s.show) {
                val c = contactsLine()
                if (c.isNotEmpty()) {
                    addCentered(ellipsize(c, tableW - 20f, smallSize, false), cx, cy, smallSize, false, s.secondary)
                    cy += lineH(smallSize) + 2f
                }
            }
            val ids = idsLine()
            if (ids.isNotEmpty()) {
                addCentered(ellipsize(ids, tableW - 20f, smallSize, false), cx, cy, smallSize, false, s.secondary)
                cy += lineH(smallSize) + 2f
            }
            // قاعدة فاصلة ذهبية للفئة LUX — وعلامة ترويسة دائمة للاختبار في كل الأحوال
            val ruleColor = if (cat == TemplateCategory.LUXURY) s.secondary else s.primary
            addRule(cx - 70f, cy + 2f, 140f, 1f, ruleColor)
            cy += 6f
            return cy
        }

        /** SPLIT — عمودان بفاصل عمودي: المنشأة في جهة البداية والتواصل في الأخرى */
        private fun splitHeader(top: Float): Float {
            val h = SPLIT_H
            val divX = pageW / 2f
            addRule(divX, top + 4f, 1f, h - 8f, s.secondary)
            val logoW = if (PdfElement.LOGO in s.show) (40f * s.logoSizeScale.coerceIn(0.6f, 1.4f)).coerceIn(28f, 56f) else 0f
            // جهة البداية: الشعار + الاسم + رقم المنشأة
            var sy = top + 10f
            var textStartX = if (rtl) hx1 - 14f else hx0 + 14f // [P17-integration] مرآة صحيحة: بداية RTL في النصف الأيمن
            val startDirLeft = !rtl
            if (logoW > 0f) {
                val lx = if (startDirLeft) textStartX else textStartX - logoW
                addBitmap(BitmapKind.LOGO, lx, sy, logoW, logoW)
                textStartX = if (startDirLeft) textStartX + logoW + 10f else textStartX - logoW - 10f
                sy += 2f
            }
            if (PdfElement.COMPANY_NAME in s.show) {
                // [P17-integration] عرض العمود المتاح: LTR من textStartX حتى القاطع؛
                // RTL من القاطع+14 حتى textStartX (العمود الأيمن كله)
                val availStart = if (rtl) abs(textStartX - (divX + 14f)) else abs(divX - 14f - textStartX)
                val name = ellipsize(d.company.businessName, availStart, bodySize + 1.5f, true)
                val w = m.width(name, bodySize + 1.5f, true)
                val x = if (startDirLeft) textStartX else textStartX - w
                addText(name, x, sy, w, bodySize + 1.5f, true, s.text)
                sy += lineH(bodySize + 1.5f) + 2f
            }
            if (PdfElement.COMPANY_NO in s.show) {
                val availStart2 = if (rtl) abs(textStartX - (divX + 14f)) else abs(divX - 14f - textStartX)
                val t = lbl("رقم المنشأة", "Company No") + ": " + d.company.companyNo
                val w = m.width(t, smallSize, false)
                val x = if (startDirLeft) textStartX else textStartX - w
                addText(ellipsize(t, availStart2, smallSize, false), x, sy, minOf(w, availStart2), smallSize, false, s.secondary)
            }
            // جهة النهاية: التواصل والمعرفات (محاذاة طرف النهاية)
            // [P17-integration] RTL: عمود النهاية يسار الصفحة والنص يبدأ من hx0 يميناً
            // بمحاذاة يسارية — كان يُحاذى يمين edge=hx0+14 فيُدفع عبر القاطع فيتداخل
            var ey = top + 10f
            val endEdge = if (rtl) hx0 + 14f else hx1 - 14f
            val availEnd = if (rtl) abs(divX - 14f - hx0) else abs(endEdge - (divX + 14f))
            fun addEndCol(raw: String, yTop: Float) {
                val t = ellipsize(raw, availEnd, smallSize, false)
                val w = m.width(t, smallSize, false)
                val x = if (rtl) hx0 else endEdge - w
                addText(t, x, yTop, w, smallSize, false, s.secondary)
            }
            if (PdfElement.CONTACTS in s.show) {
                val c = contactsLine()
                if (c.isNotEmpty()) {
                    addEndCol(c, ey)
                    ey += lineH(smallSize) + 2f
                }
            }
            val ids = idsLine().replace(lbl("رقم المنشأة", "Company No") + ": " + d.company.companyNo + "  •  ", "")
            if (ids.isNotEmpty()) {
                addEndCol(ids, ey)
            }
            return top + h
        }

        // ═══ كتلة الميتا (الصفحة الأولى فقط) ═══

        private fun metaBlock() {
            addStart(lbl(TITLE_AR, TITLE_EN), y, headingSize, true, s.primary)
            y += lineH(headingSize) + 2f
            addStart(
                lbl("رقم الكشف", "Statement No") + ": " + d.statementNumber,
                y, smallSize, false, s.text
            )
            y += lineH(smallSize) + 2f
            if (PdfElement.PERIOD in s.show) {
                addStart(StatementFormat.period(d.fromTs, d.toTs, d.lang), y, smallSize, true, s.text)
                y += lineH(smallSize) + 2f
            }
            if (PdfElement.PARTY_INFO in s.show) {
                y += 4f
                val name = lbl("العميل", "Customer") + ": " + d.party.name
                addStart(ellipsize(name, tableW, bodySize + 1.5f, true), y, bodySize + 1.5f, true, s.text)
                y += lineH(bodySize + 1.5f) + 2f
                val lines = ArrayList<String>()
                lines.add(lbl("رقم الطرف", "Party No") + ": " + d.party.partyNo)
                d.party.phone?.takeIf { it.isNotBlank() }?.let { lines.add(lbl("الهاتف", "Phone") + ": " + it) }
                d.party.email?.takeIf { it.isNotBlank() }?.let { lines.add(lbl("البريد", "Email") + ": " + it) }
                d.party.address?.takeIf { it.isNotBlank() }?.let {
                    for (ln in wrap(lbl("العنوان", "Address") + ": " + it, tableW, smallSize, false, 2)) lines.add(ln)
                }
                d.party.accountNumber?.takeIf { it.isNotBlank() }?.let { lines.add(lbl("الحساب", "Account") + ": " + it) }
                d.party.taxNumber?.takeIf { it.isNotBlank() }?.let { lines.add(lbl("الرقم الضريبي", "Tax No") + ": " + it) }
                for (ln in lines) {
                    addStart(ellipsize(ln, tableW, smallSize, false), y, smallSize, false, s.secondary)
                    y += lineH(smallSize) + 1.5f
                }
                y += 4f
            }
            y += spacing / 2f
        }

        // ═══ الجدول ═══

        private fun typeLabel(typeKey: String): String = when (typeKey) {
            StatementTxTypes.INVOICE -> lbl("فاتورة", "Invoice")
            StatementTxTypes.PAYMENT -> lbl("دفعة", "Payment")
            StatementTxTypes.DEBT -> lbl("دين", "Debt")
            StatementTxTypes.CHECK -> lbl("شيك", "Check")
            StatementTxTypes.INSTALLMENT -> lbl("قسط", "Installment")
            else -> lbl("تسوية", "Adjust")
        }

        private fun computeColumns() {
            val dateHdr = m.width(lbl("التاريخ", "Date"), headCellSize, true)
            var dw = dateHdr + CELL_PAD * 2
            var rw = m.width(lbl("المرجع", "Ref"), headCellSize, true) + CELL_PAD * 2
            var debitW = m.width(lbl("مدين", "Debit"), headCellSize, true) + CELL_PAD * 2
            var creditW = m.width(lbl("دائن", "Credit"), headCellSize, true) + CELL_PAD * 2
            var balW = m.width(lbl("الرصيد", "Balance"), headCellSize, true) + CELL_PAD * 2
            for (r in d.rows) {
                dw = maxOf(dw, m.width(StatementFormat.dateShort(r.ts), bodySize, false) + CELL_PAD * 2)
                val ref = r.ref.ifBlank { r.typeKey }
                rw = maxOf(rw, m.width(ellipsize(ref, 78f, bodySize, false), bodySize, false) + CELL_PAD * 2)
                // [P17-integration] قياس bold كحد أعلى: الرصيد الموجب وصفوف الإجماليات يُرسمون bold —
                // القياس non-bold كان أضيق من المرسوم فيتجاوز عموده ويتداخل مع الخلية المجاورة
                debitW = maxOf(debitW, m.width(money(r.debit), bodySize, true) + CELL_PAD * 2)
                creditW = maxOf(creditW, m.width(money(r.credit), bodySize, true) + CELL_PAD * 2)
                balW = maxOf(balW, m.width(money(r.balance), bodySize, true) + CELL_PAD * 2)
            }
            // مبالغ الملخص/الافتتاحي/الإجماليات bold أيضاً — تدخل في نفس القياس
            val su0 = d.summary
            balW = maxOf(balW, m.width(money(su0.opening), bodySize, true) + CELL_PAD * 2)
            balW = maxOf(balW, m.width(money(su0.final), bodySize, true) + CELL_PAD * 2)
            debitW = maxOf(debitW, m.width(money(su0.totalDebit), bodySize, true) + CELL_PAD * 2)
            creditW = maxOf(creditW, m.width(money(su0.totalCredit), bodySize, true) + CELL_PAD * 2)
            // سقف دفاعي للأعمدة المالية كي لا تبتلع جدولاً ضيقاً (قيم fixture الضخمة تبقى مقروءة)
            val cap = tableW * 0.28f
            debitW = debitW.coerceAtMost(cap)
            creditW = creditW.coerceAtMost(cap)
            balW = balW.coerceAtMost(cap)
            dw = dw.coerceIn(40f, 70f)
            rw = rw.coerceIn(34f, 78f)
            var descW = tableW - (dw + rw + debitW + creditW + balW)
            if (descW < 50f) {   // جدول خانق: نضغط الماليات والمرجع — التاريخ معفى (اقتطاعه غير مقبول بمستند مالي)
                val squeeze = 50f - descW
                val pool = debitW + creditW + balW + rw
                val k = if (pool > 0f) squeeze / pool else 0f
                debitW -= debitW * k; creditW -= creditW * k; balW -= balW * k
                rw -= rw * k
                descW = 50f
            }
            // الترتيب المنطقي: تاريخ، مرجع، بيان، مدين، دائن، رصيد — RTL يعكسه فيزيائياً
            val logical = floatArrayOf(dw, rw, descW, debitW, creditW, balW)
            val phys = if (rtl) logical.reversedArray() else logical.copyOf()
            var x = hx0
            for (i in 0..5) { colX[i] = x; colW[i] = phys[i]; x += phys[i] }
            // فهرس فيزيائي للعمود المنطقي (تاريخ=0 … رصيد=5)
            datePhys = if (rtl) 5 else 0
            refPhys = if (rtl) 4 else 1
            descPhys = if (rtl) 3 else 2
            debitPhys = if (rtl) 2 else 3
            creditPhys = if (rtl) 1 else 4
            balPhys = if (rtl) 0 else 5

            headerH = 6f + lineH(headCellSize) + (if (bi) lineH(headCellSize) + 1f else 0f) + 6f
        }

        private var datePhys = 0
        private var refPhys = 1
        private var descPhys = 2
        private var debitPhys = 3
        private var creditPhys = 4
        private var balPhys = 5

        private fun headerLabel(ar: String, en: String): String = if (d.lang == StatementLang.AR && !bi) ar else if (bi) ar else en
        private fun headerLabelEn(ar: String, en: String): String = en

        private fun drawTableHeader() {
            val top = y
            addRule(hx0, top, tableW, headerH, s.primary, fill = true)
            val labels = listOf(
                lbl("التاريخ", "Date"), lbl("المرجع", "Ref"), lbl("البيان", "Description"),
                lbl("مدين", "Debit"), lbl("مدين", "Debit").let { lbl("دائن", "Credit") },
                lbl("الرصيد", "Balance")
            )
            // التسميات الثنائية: سطر عربي ثم سطر إنجليزي (عقد فئة BIL)
            val line1 = if (bi)
                listOf("التاريخ", "المرجع", "البيان", "مدين", "دائن", "الرصيد")
            else
                listOf(headerLabel("التاريخ", "Date"), headerLabel("المرجع", "Ref"), headerLabel("البيان", "Description"),
                    headerLabel("مدين", "Debit"), headerLabel("دائن", "Credit"), headerLabel("الرصيد", "Balance"))
            val line2 = if (bi) listOf("Date", "Ref", "Description", "Debit", "Credit", "Balance") else null
            val single = !bi
            var ty = top + 6f
            for (i in 0..5) {
                addCentered(line1[i], colX[i] + colW[i] / 2f, ty, headCellSize, true, s.tableHeadText, colW[i] - CELL_PAD)
            }
            ty += lineH(headCellSize)
            if (line2 != null && !single) {
                ty += 1f
                for (i in 0..5) {
                    addCentered(line2[i], colX[i] + colW[i] / 2f, ty, headCellSize - 1f, false, s.tableHeadText, colW[i] - CELL_PAD)
                }
            }
            y = top + headerH
            if (s.table == TableStyle.MINIMAL || s.table == TableStyle.ZEBRA) {
                addRule(hx0, y, tableW, 1f, s.primary)
                y += 1f
            }
        }

        private fun cellStartX(phys: Int) = colX[phys] + CELL_PAD
        private fun cellRightEdge(phys: Int) = colX[phys] + colW[phys] - CELL_PAD
        private fun cellCx(phys: Int) = colX[phys] + colW[phys] / 2f

        /** نص خلية المبلغ — محاذاة يمينية دائماً (قرار 3: أعمدة الأرقام LTR حتى في RTL)
         *  [P17-integration] دفاع نهائي: نص أعرض من عموده يُصغَّر خطّه بدل تجاوز الحدود */
        private fun addMoneyCell(text: String, phys: Int, yTop: Float, size: Float, bold: Boolean, color: Int) {
            val avail = colW[phys] - CELL_PAD * 2
            var w = m.width(text, size, bold)
            var eff = size
            if (w > avail) {
                eff = (size * (avail / w) * 0.96f).coerceAtLeast(size * 0.55f)
                w = m.width(text, eff, bold)
            }
            addText(text, cellRightEdge(phys) - w, yTop, w, eff, bold, color)
        }

        private fun rowTextY(top: Float, size: Float) = top + (rowH - lineH(size)) / 2f

        private fun drawRow(r: StatementTxRow, rowIdx: Int) {
            val top = y
            // تظليل الصف البديل (STRIPED/ZEBRA)
            if ((s.table == TableStyle.STRIPED || s.table == TableStyle.ZEBRA) && rowIdx % 2 == 1) {
                addRule(hx0, top, tableW, rowH, s.rowAlt, fill = true)
            }
            val ty = rowTextY(top, bodySize)
            addCentered(StatementFormat.dateShort(r.ts), cellCx(datePhys), ty, bodySize, false, s.text, colW[datePhys] - CELL_PAD)
            val ref = r.ref.ifBlank { r.typeKey }
            addCentered(ellipsize(ref, colW[refPhys] - CELL_PAD * 2, bodySize, false), cellCx(refPhys), ty, bodySize, false, s.secondary, colW[refPhys] - CELL_PAD)
            val desc = r.desc.trim().ifBlank { typeLabel(r.typeKey) }
            val descW = colW[descPhys] - CELL_PAD * 2
            val dt = ellipsize(desc, descW, bodySize, false)
            val dw2 = m.width(dt, bodySize, false)
            // البيان بطرف القراءة (يقلب مع RTL) داخل عموده
            if (rtl) addEnd(dt, ty, bodySize, false, s.text, edge = cellRightEdge(descPhys))
            else addText(dt, cellStartX(descPhys), ty, dw2, bodySize, false, s.text)
            addMoneyCell(if (r.debit > 0.004) money(r.debit) else "—", debitPhys, ty, bodySize, false, s.text)
            addMoneyCell(if (r.credit > 0.004) money(r.credit) else "—", creditPhys, ty, bodySize, false, s.text)
            val balColor = if (r.balance < -0.004) s.primary else s.text
            addMoneyCell(money(r.balance), balPhys, ty, bodySize, r.balance > 0.004, balColor)
            // الخطوط حسب نمط الجدول
            when (s.table) {
                TableStyle.STRIPED, TableStyle.OPEN, TableStyle.BORDERED ->
                    addRule(hx0, top + rowH, tableW, 1f, s.secondary)
                else -> Unit
            }
            y = top + rowH
        }

        /** صف رصيد أول المدة (فئة ACC) — أعلى الجدول في أول صفحة جدول */
        private fun drawOpeningRow() {
            val top = y
            addRule(hx0, top, tableW, rowH, s.rowAlt, fill = true)
            val ty = rowTextY(top, bodySize)
            // [P17-integration] اقتطاع التسمية لعرض عمود البيان — التسمية الثنائية الطويلة
            // كانت تفيض على عمود المدين وتتداخل مع مبلغه
            val lblTxt = ellipsize(lbl("رصيد أول المدة", "Opening Balance"), colW[descPhys] - CELL_PAD * 2, bodySize, true)
            if (rtl) addEnd(lblTxt, ty, bodySize, true, s.text, edge = cellRightEdge(descPhys))
            else addText(lblTxt, cellStartX(descPhys), ty, m.width(lblTxt, bodySize, true), bodySize, true, s.text)
            addMoneyCell(money(d.summary.opening), balPhys, ty, bodySize, true, s.primary)
            addRule(hx0, top + rowH, tableW, 1f, s.secondary)
            y = top + rowH
        }

        /** صف الإجماليات (فئة ACC) — أسفل الجدول في آخر صفحة صفوف */
        private fun drawTotalsRow() {
            val top = y
            val ty = rowTextY(top, bodySize)
            val lblTxt = ellipsize(lbl("الإجماليات", "Totals"), colW[descPhys] - CELL_PAD * 2, bodySize, true) // [P17-integration] اقتطاع مثل الافتتاحي
            if (rtl) addEnd(lblTxt, ty, bodySize, true, s.primary, edge = cellRightEdge(descPhys))
            else addText(lblTxt, cellStartX(descPhys), ty, m.width(lblTxt, bodySize, true), bodySize, true, s.primary)
            addMoneyCell(money(d.summary.totalDebit), debitPhys, ty, bodySize, true, s.primary)
            addMoneyCell(money(d.summary.totalCredit), creditPhys, ty, bodySize, true, s.primary)
            addMoneyCell(money(d.summary.final), balPhys, ty, bodySize, true, s.primary)
            addRule(hx0, top + rowH, tableW, 1f, s.primary)
            y = top + rowH
        }

        /** إطار الجدول الكامل (BORDERED) — رأسيّات وخارجي */
        private fun drawGrid(verticalTop: Float, verticalBottom: Float) {
            for (i in 0..5) {
                val x = colX[i]
                addRule(x, verticalTop, 1f, verticalBottom - verticalTop, s.secondary)
            }
            addRule(hx1 - 1f, verticalTop, 1f, verticalBottom - verticalTop, s.secondary)
            addRule(hx0, verticalTop - headerH, tableW, 1f, s.primary)
        }

        // ═══ الحالة الفارغة ═══

        private fun emptyStateBlock() {
            addRule(hx0, y, tableW, EMPTY_H, s.secondary, fill = false)
            var ty = y + 8f
            val line = when {
                bi -> EMPTY_AR
                d.lang == StatementLang.AR -> EMPTY_AR
                else -> EMPTY_EN
            }
            addCentered(line, hx0 + tableW / 2f, ty, bodySize + 1f, true, s.secondary, tableW - 16f)
            ty += lineH(bodySize + 1f) + 3f
            if (bi || d.lang == StatementLang.AR) {
                addCentered(EMPTY_EN, hx0 + tableW / 2f, ty, smallSize, false, s.secondary, tableW - 16f)
            }
            y += EMPTY_H
        }

        // ═══ الملخص المالي (آخر صفحة فقط) ═══

        private data class SumRow(val label: String, val value: String, val keyFinal: Boolean)

        private fun summaryRows(): List<SumRow> {
            val su = d.summary
            val rows = ArrayList<SumRow>()
            if (PdfElement.OPENING in s.show) rows.add(SumRow(lbl("الافتتاحي", "Opening"), money(su.opening), false))
            if (PdfElement.TOTAL_DEBIT in s.show) rows.add(SumRow(lbl("إجمالي المدين", "Total Debit"), money(su.totalDebit), false))
            if (PdfElement.TOTAL_CREDIT in s.show) rows.add(SumRow(lbl("إجمالي الدائن", "Total Credit"), money(su.totalCredit), false))
            rows.add(SumRow(lbl("الدفعات", "Payments"), money(su.totalPayments), false))
            rows.add(SumRow(lbl("الفواتير", "Invoices"), money(su.totalInvoices), false))
            rows.add(SumRow(lbl("الخصومات", "Discounts"), money(su.totalDiscounts), false))
            if (PdfElement.FINAL_BALANCE in s.show) rows.add(SumRow(lbl("الرصيد النهائي", "Final Balance"), money(su.final), true))
            return rows
        }

        private fun directionLine(): String = when (d.summary.direction) {
            BalanceDirection.DUE_ON_CUSTOMER -> lbl("المبلغ المستحق على العميل", "Amount due on customer")
            BalanceDirection.IN_FAVOR_OF_CUSTOMER -> lbl("رصيد له لدى المنشأة", "Credit in customer's favor")
            BalanceDirection.BALANCED -> lbl("متوازن — لا مديونية", "Balanced — no dues")
        }

        /** ارتفاع كتلة الملخص الكاملة (ملخص+اتجاه) قبل الرسم — لضمان كتلة غير منقسمة */
        private fun summaryBlockHeight(rows: List<SumRow>): Float = when (s.summary) {
            SummaryStyle.CARDS -> 2f * 42f + 6f + lineH(smallSize) + 4f
            SummaryStyle.BOTTOM_BAND -> 46f + lineH(smallSize) + 4f
            else -> rows.size * (lineH(bodySize) + 5f) + lineH(smallSize) + 10f + 16f
        }

        private fun drawSummary(rows: List<SumRow>): Float {
            val top = y
            when (s.summary) {
                SummaryStyle.CARDS -> {
                    // شبكة 3×2 — البطاقة الأخيرة = الرصيد النهائي مميزة (عقد النمط)
                    val cols = 3
                    val gap = 6f
                    val cardW = (tableW - gap * (cols - 1)) / cols
                    val cardH = 42f
                    val picked = rows.take(6)
                    for ((i, r) in picked.withIndex()) {
                        val rr = i / cols
                        val cc = i % cols
                        val x = hx0 + cc * (cardW + gap)
                        val yy = top + rr * (cardH + gap)
                        val isFinal = r.keyFinal
                        addRule(x, yy, cardW, cardH, if (isFinal) s.primary else s.rowAlt, fill = true)
                        addCentered(r.label, x + cardW / 2f, yy + 5f, smallSize, false, if (isFinal) s.tableHeadText else s.secondary, cardW - 8f)
                        addCentered(r.value, x + cardW / 2f, yy + 5f + lineH(smallSize) + 2f, bodySize, true, if (isFinal) s.tableHeadText else s.primary, cardW - 8f)
                    }
                    y = top + 2 * cardH + gap + 4f
                }
                SummaryStyle.BOTTOM_BAND -> {
                    // شريط معبأ: 4 أزواج (افتتاحي/مدين/دائن/نهائي) في صف واحد
                    val bandH = 46f
                    addRule(hx0, top, tableW, bandH, s.rowAlt, fill = true)
                    addRule(hx0, top, tableW, 1.5f, s.secondary)
                    val picked = rows.take(4)
                    val colW4 = tableW / picked.size.coerceAtLeast(1)
                    for ((i, r) in picked.withIndex()) {
                        val cx = hx0 + colW4 * i + colW4 / 2f
                        addCentered(r.label, cx, top + 6f, smallSize, false, s.secondary, colW4 - 8f)
                        addCentered(r.value, cx, top + 6f + lineH(smallSize) + 2f, bodySize, true,
                            if (r.keyFinal) s.primary else s.text, colW4 - 8f)
                    }
                    y = top + bandH + 4f
                }
                SummaryStyle.BOXED -> {
                    val inner = rows.size * (lineH(bodySize) + 5f) + 10f
                    addRule(hx0, top, tableW, inner + 12f, s.primary, fill = false)
                    var yy = top + 10f
                    for (r in rows) { drawSummaryLine(r, yy, inset = 10f); yy += lineH(bodySize) + 5f } // [P17-integration] تقدّم السطور
                    y = top + inner + 12f
                }
                SummaryStyle.RAIL -> {
                    val inner = rows.size * (lineH(bodySize) + 5f) + 10f
                    // قضيب عمودي جانبي (نمط عمود السكك) — بلون التمييز
                    addRule(hx0 + 1f, top, 3.5f, inner, s.secondary, fill = true)
                    var yy = top + 6f
                    for (r in rows) { drawSummaryLine(r, yy, inset = 12f); yy += lineH(bodySize) + 5f } // [P17-integration] تقدّم السطور
                    y = top + inner + 4f
                }
                SummaryStyle.INLINE -> {
                    var yy = top
                    for (r in rows) { drawSummaryLine(r, yy, inset = 0f); yy += lineH(bodySize) + 5f }
                    y = yy + 2f
                }
            }
            // سطر الاتجاه — توضيح دلالة الرصيد النهائي (مدين له/متوازن)
            addStart(directionLine(), y, smallSize, false, s.secondary)
            y += lineH(smallSize) + 4f
            return y - top
        }

        /** سطر تسمية:قيمة — التسمية بطرف القراءة والقيمة بطرف النهاية المعاكس
         *  [P17-integration] RTL: التسمية يمين (hx1) والقيمة يسار (hx0) — كان الاثنان
         *  على نفس حافة hx1 فيتطابقان فوق بعضهما */
        private fun drawSummaryLine(r: SumRow, yy: Float, inset: Float) {
            val lSize = if (r.keyFinal) bodySize + 0.5f else bodySize
            val vSize = if (r.keyFinal) bodySize + 1f else bodySize
            val lx = if (rtl) hx1 - inset else hx0 + inset
            val lw = m.width(r.label, lSize, r.keyFinal)
            if (rtl) addEnd(r.label, yy, lSize, r.keyFinal, if (r.keyFinal) s.primary else s.text, edge = lx)
            else addText(r.label, lx, yy, lw, lSize, r.keyFinal, if (r.keyFinal) s.primary else s.text)
            val vEdge = if (rtl) hx0 + inset else hx1 - inset
            val vw = m.width(r.value, vSize, true)
            val guard = 8f
            // المسافة بين نهاية التسمية وحافة القيمة يجب أن تستوعب القيمة — وإلا تنزل سطراً
            val span = if (rtl) (hx1 - inset - lw) - (hx0 + inset) else (hx1 - inset) - (hx0 + inset) - lw
            if (span > vw + guard) {
                addEnd(r.value, yy, vSize, true, if (r.keyFinal) s.primary else s.text, edge = vEdge)
            } else {
                // سطر ضيق: القيمة تحت التسمية (دفاع بلا تداخل)
                addEnd(r.value, yy + lineH(lSize) + 1f, vSize, true, if (r.keyFinal) s.primary else s.text, edge = vEdge)
            }
        }

        // ═══ الملاحظات ═══

        private fun notesHeight(): Float {
            if (PdfElement.NOTES !in s.show) return 0f
            val note = d.note?.trim().orEmpty()
            if (note.isEmpty()) return 0f
            val lines = wrap(note, tableW - 16f, smallSize, false, MAX_NOTE_LINES)
            return lineH(smallSize) + 2f + lines.size * (lineH(smallSize) + 1.5f) + 6f
        }

        private fun drawNotes() {
            if (PdfElement.NOTES !in s.show) return
            val note = d.note?.trim().orEmpty()
            if (note.isEmpty()) return
            addStart(lbl("ملاحظات", "Notes") + ":", y, smallSize, true, s.secondary)
            y += lineH(smallSize) + 2f
            for (ln in wrap(note, tableW - 16f, smallSize, false, MAX_NOTE_LINES)) {
                addStart(ln, y, smallSize, false, s.text)
                y += lineH(smallSize) + 1.5f
            }
            y += 6f
        }

        // ═══ حيز التوقيع/الخاتم ═══

        private fun mirrorCorner(c: CornerPos): CornerPos = when (c) {
            CornerPos.TOP_LEFT -> CornerPos.TOP_RIGHT
            CornerPos.TOP_RIGHT -> CornerPos.TOP_LEFT
            CornerPos.BOTTOM_LEFT -> CornerPos.BOTTOM_RIGHT
            CornerPos.BOTTOM_RIGHT -> CornerPos.BOTTOM_LEFT
        }

        private fun signZoneHeights(): Pair<Float, Float> {
            val sigH = if (PdfElement.SIGNATURE in s.show) SIG_BOX * s.signatureSizeScale.coerceIn(0.7f, 1.6f) else 0f
            val stampH = if (PdfElement.STAMP in s.show) STAMP_BOX * s.stampSizeScale.coerceIn(0.7f, 1.6f) else 0f
            val capH = if (sigH > 0f) lineH(smallSize) + 2f else 0f
            val inner = maxOf(sigH, stampH) + capH
            return Pair(inner, inner + ZONE_GAP)
        }

        /** الحيز المحجوز أسفل آخر صفحة — يرسم بعد الملخص فلا يداخله شيء (قرار 6) */
        private fun drawSignZone() {
            if (PdfElement.SIGNATURE !in s.show && PdfElement.STAMP !in s.show) return
            val (inner, _) = signZoneHeights()
            val zoneTop = maxOf(y + 4f, footerTop - inner - 4f)
            // زوايا فيزيائية معكوسة بالمرآة عند RTL (قرار 3)
            val sigCorner = if (rtl) mirrorCorner(s.signaturePos) else s.signaturePos
            var stampCorner = if (rtl) mirrorCorner(s.stampPos) else s.stampPos
            val sigSide = sideOf(sigCorner)
            if (PdfElement.SIGNATURE in s.show && PdfElement.STAMP in s.show && sideOf(stampCorner) == sigSide) {
                stampCorner = if (sigSide == -1) CornerPos.BOTTOM_RIGHT else CornerPos.BOTTOM_LEFT
            }
            if (PdfElement.SIGNATURE in s.show) {
                val box = SIG_BOX * s.signatureSizeScale.coerceIn(0.7f, 1.6f)
                // [P17-integration] التسمية داخل الحيز المحجوز: الصندوق يقصر بمقدار capH
                // حتى لا تخرج تسمية «التوقيع» تحت footerTop وتتداخل مع ترقيم الصفحة
                val capH = lineH(smallSize) + 2f
                val by = if (isTop(sigCorner)) zoneTop + 2f else zoneTop + inner - capH - box
                val bx = if (sigSide == -1) hx0 + 2f else hx1 - 2f - box
                addBitmap(BitmapKind.SIGNATURE, bx, by, box, box, s.signatureOpacity)
                // تسمية تحت الصندوق — خارج rect الصورة فلا تداخل نص/صورة
                val cap = lbl("التوقيع", "Signature")
                addCentered(cap, bx + box / 2f, by + box + 2f, smallSize, false, s.secondary, box + 40f)
            }
            if (PdfElement.STAMP in s.show) {
                val box = STAMP_BOX * s.stampSizeScale.coerceIn(0.7f, 1.6f)
                val by = if (isTop(stampCorner)) zoneTop + 2f else zoneTop + inner - box
                val bx = if (sideOf(stampCorner) == -1) hx0 + 2f else hx1 - 2f - box
                addBitmap(BitmapKind.STAMP, bx, by, box, box, s.stampOpacity)
            }
            y = zoneTop + inner + 2f
        }

        private fun sideOf(c: CornerPos) = if (c == CornerPos.TOP_LEFT || c == CornerPos.BOTTOM_LEFT) -1 else 1
        private fun isTop(c: CornerPos) = c == CornerPos.TOP_LEFT || c == CornerPos.TOP_RIGHT

        // ═══ التذييل (يُختم بعد معرفة العدد الكلي n) ═══

        private fun stampFooter(b: Buf, pageDisplay: Int, total: Int) {
            val fy = footerTop
            val pLine = when (s.footer) {
                FooterStyle.THIN -> listOf(LRect(hx0, fy, tableW, 1f, s.secondary))
                FooterStyle.CLASSIC -> listOf(
                    LRect(hx0, fy, tableW, 1.5f, s.primary),
                    LRect(hx0, fy + 3f, tableW, 0.5f, s.secondary)
                )
                FooterStyle.BOXED -> listOf(LRect(hx0, fy, tableW, footerReserve, s.secondary))
                else -> emptyList()
            }
            b.rules.addAll(pLine)
            val lineHf = lineH(footerSize)
            var ty = fy + 6f
            when (s.footer) {
                FooterStyle.QR_LEFT, FooterStyle.QR_RIGHT -> {
                    // QR بطرف البداية (يُعكس بالمرآة عند RTL — عقد QR_LEFT/QR_RIGHT)
                    if (PdfElement.QR in s.show) {
                        val qx = if (!rtl) hx0 else hx1 - QR_SIZE
                        b.bitmaps.add(LBitmap(BitmapKind.QR, LRect(qx, fy, QR_SIZE, QR_SIZE)))
                    }
                    // النصوص في الطرف الآخر، متراصة عمودياً
                    // [P17-integration] RTL: النص يبدأ من hx0 يساراً (لا يُطرح عرضه من hx0 فتخرج الصفحة)
                    val textEdge = if (!rtl) hx1 else hx0
                    val pw = m.width(StatementFormat.pageOf(pageDisplay, total, d.lang), footerSize, true)
                    b.texts.add(LText(StatementFormat.pageOf(pageDisplay, total, d.lang), footerSize, true, s.text,
                        LRect(if (!rtl) textEdge - pw else textEdge, ty, pw, lineHf)))
                    ty += lineHf + 2f
                    if (PdfElement.VERIFY_ID in s.show) {
                        val vw = m.width(d.verificationId, footerSize, false)
                        b.texts.add(LText(d.verificationId, footerSize, false, s.secondary,
                            LRect(if (!rtl) textEdge - vw else textEdge, ty, vw, lineHf)))
                        ty += lineHf + 1f
                    }
                    if (PdfElement.GENERATED_DATE in s.show) {
                        val g = StatementFormat.generatedOn(d.createdAt, d.lang)
                        val gw = m.width(g, footerSize, false)
                        b.texts.add(LText(g, footerSize, false, s.secondary,
                            LRect(if (!rtl) textEdge - gw else textEdge, ty, gw, lineHf)))
                    }
                }
                else -> {
                    // السطر الأول: ترقيم بالمنتصف دائماً (عنصر مستقل قابل للفحص)
                    val p = StatementFormat.pageOf(pageDisplay, total, d.lang)
                    val pw = m.width(p, footerSize, true)
                    b.texts.add(LText(p, footerSize, true, s.text, LRect(hx0 + (tableW - pw) / 2f, ty, pw, lineHf)))
                    ty += lineHf + 2f
                    // السطر الثاني: رقم التحقق بطرف البداية وتاريخ الإصدار بطرف النهاية
                    // [P17-integration] مرآة RTL: التحقق يمين وتاريخ الإصدار يسار
                    val vTxt = if (PdfElement.VERIFY_ID in s.show) d.verificationId else ""
                    val gTxt = if (PdfElement.GENERATED_DATE in s.show) StatementFormat.generatedOn(d.createdAt, d.lang) else ""
                    val vW = if (vTxt.isEmpty()) 0f else m.width(vTxt, footerSize, false)
                    val gW = if (gTxt.isEmpty()) 0f else m.width(gTxt, footerSize, false)
                    if (vTxt.isNotEmpty() && gTxt.isNotEmpty() && vW + gW + 12f > tableW) {
                        // صفحة ضيقة: سطر ثالث دفاعي بدل التداخل (الحجز يستوعب 3 أسطر)
                        b.texts.add(LText(vTxt, footerSize, false, s.secondary, LRect(if (!rtl) hx0 else hx1 - vW, ty, vW, lineHf)))
                        ty += lineHf + 1f
                        b.texts.add(LText(gTxt, footerSize, false, s.secondary, LRect(if (!rtl) hx1 - gW else hx0, ty, gW, lineHf)))
                    } else {
                        if (vTxt.isNotEmpty()) b.texts.add(LText(vTxt, footerSize, false, s.secondary, LRect(if (!rtl) hx0 else hx1 - vW, ty, vW, lineHf)))
                        if (gTxt.isNotEmpty()) b.texts.add(LText(gTxt, footerSize, false, s.secondary, LRect(if (!rtl) hx1 - gW else hx0, ty, gW, lineHf)))
                    }
                }
            }
        }

        // ═══ التدفق الرئيسي ═══

        fun run(): List<LPage> {
            // الصفحة الأولى: ترويسة + ميتا
            y = drawHeader(margin)
            metaBlock()

            computeColumns()
            val su = d.summary
            val (_, zoneTotal) = signZoneHeights()
            val notesH = notesHeight()
            val rows = summaryRows()
            val summaryH = summaryBlockHeight(rows)
            val isAcc = cat == TemplateCategory.ACCOUNTING
            val showOpeningRow = isAcc && PdfElement.OPENING in s.show && d.rows.isNotEmpty()
            val showTotalsRow = isAcc && d.rows.isNotEmpty()

            val blockNeed = summaryH + notesH + zoneTotal + 8f

            if (d.rows.isEmpty()) {
                // الحالة الفارغة: كتلة بديلة عن جدول الحركات (AR/EN حسب d.lang)
                if (y + EMPTY_H + blockNeed > footerTop) newPage()
                emptyStateBlock()
            } else {
                // صف رصيد أول المدة أعلى الجدول (فئة ACC) — ثم الرأس ثم الصفوف
                var firstRowDone = false
                for (r in d.rows) {
                    val preRow = if (!firstRowDone) {
                        var need = headerH + rowH
                        if (showOpeningRow && y < hx1) need += rowH   // صف الافتتاحي يرسم مرة مع الرأس
                        need
                    } else rowH
                    if (y + preRow > footerTop) {
                        newPage()
                        // كل صفحة جديدة تبدأ برأس الجدول قبل أي صف (قرار 5)
                        if (y + headerH + rowH > footerTop) {
                            // لا يتسع حتى الرأس+صف: نترك الترويسة والرأس يتناسبان — حجز دفاعي
                        }
                        drawTableHeader()
                        if (showOpeningRow && !firstRowDone) drawOpeningRow()
                    } else if (!firstRowDone) {
                        drawTableHeader()
                        if (showOpeningRow) drawOpeningRow()
                    }
                    drawRow(r, rowIndexFor(firstRowDone))
                    firstRowDone = true
                }
                if (showTotalsRow) {
                    if (y + rowH > footerTop) { newPage(); drawTableHeader() }
                    drawTotalsRow()
                }
                if (s.table == TableStyle.BORDERED && d.rows.isNotEmpty()) {
                    drawGrid(verticalTop = y - gridRowsHeight(), verticalBottom = y)
                }
            }

            // الملخص + الملاحظات + حيز التوقيع: كتلة واحدة لا تنقسم (قرار 5)
            if (y + blockNeed > footerTop) newPage()
            drawSummary(rows)
            drawNotes()
            drawSignZone()

            bufs.add(buf)
            // ختم التذييل بعد معرفة العدد الكلي — «صفحة i من n» بلسان StatementFormat
            val out = ArrayList<LPage>(bufs.size)
            for ((i, b) in bufs.withIndex()) {
                stampFooter(b, i + 1, bufs.size)
                out.add(LPage(index = i, elements = b.texts.toList(), bitmaps = b.bitmaps.toList(), rules = b.rules.toList()))
            }
            return out
        }

        private var runningIdx = 0
        private fun rowIndexFor(firstRowDone: Boolean): Int {
            if (!firstRowDone) runningIdx = 0 else runningIdx++
            return runningIdx
        }

        private fun gridRowsHeight(): Float {
            // ارتفاع منطقة الصفوف منذ آخر رأس جدول — تقريب دفاعي يكفي لإطار BORDERED
            return minOf(y - (footerTop - tableW), y - margin - headerH).coerceAtLeast(rowH)
        }
    }
}
