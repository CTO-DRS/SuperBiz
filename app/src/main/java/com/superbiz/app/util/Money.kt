package com.superbiz.app.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Money {
    // (M-2.3 توحيد): منسّقات صريحة اللغة — أرقام لاتينية ثابتة في كل لغات التطبيق
    // (كانت Default-locale تطبع أرقاماً عربية-هندية تحت اللغة العربية فلا يستطيع parse قراءة مخرجات format)
    // [P20-FIX agent13/15]: DecimalFormat غير آمن خيطياً (JDK موثّق) — كان يُستدعى من Main وIO
    // معاً (PDF/تصدير/ودجات) فقيل محتمل لإخراج رقم مشوّه في مستند مالي. ThreadLocal لكل خيط نسخته.
    private val fmt0 = object : ThreadLocal<DecimalFormat>() {
        override fun initialValue() = DecimalFormat("#,##0", DecimalFormatSymbols(Locale.US))
    }
    private val fmt2 = object : ThreadLocal<DecimalFormat>() {
        override fun initialValue() = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US))
    }

    // (M-2.3 توحيد): فاصل التجميع العربي U+066C وفاصلة العشرية U+066B
    private const val AR_GROUP = '\u066C'
    private const val AR_DECIMAL = '\u066B'

    /** (M-7.6/M-2.3 توحيد): تطبيع الأرقام — يحوّل الأرقام العربية-الهندية ٠-٩ والمفصولات العربية إلى الصيغة اللاتينية */
    fun normalizeDigits(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch in '\u0660'..'\u0669' -> sb.append(('0' + (ch - '\u0660')))
                ch in '\u06F0'..'\u06F9' -> sb.append(('0' + (ch - '\u06F0')))
                ch == AR_GROUP -> sb.append(',')
                ch == AR_DECIMAL -> sb.append('.')
                ch == '\u066A' -> sb.append('%') // علامة النسبة العربية تُحوَّل إلى %
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** 2,670 ر.س — [P33-P8] نسخة القروش: المدخل قروش صحيحة (Long) — التحويل من Double يمر عبر toPiasters فقط */
    fun formatP(piasters: Long, symbol: String, showDecimals: Boolean = true): String {
        val neg = piasters < 0
        val abs = kotlin.math.abs(piasters)
        val whole = abs / 100
        val frac = abs % 100
        val body = if (showDecimals || frac != 0L) {
            fmt0.get().format(whole) + "." + frac.toString().padStart(2, '0')
        } else fmt0.get().format(whole)
        return (if (neg) "-" else "") + body + " " + symbol
    }

    /** [P33-P8] نسخة القروش من num — بدون رمز العملة */
    fun numP(piasters: Long): String {
        val neg = piasters < 0
        val abs = kotlin.math.abs(piasters)
        val whole = abs / 100
        val frac = abs % 100
        val body = if (frac != 0L) {
            fmt0.get().format(whole) + "." + frac.toString().padStart(2, '0')
        } else fmt0.get().format(whole)
        return (if (neg) "-" else "") + body
    }

    fun format(amount: Double, symbol: String, showDecimals: Boolean = false): String {
        // قيمة غير منتهية تعرض «—» بدل «NaN ر.س» على الإيصالات والـPDF
        if (!amount.isFinite()) return "— $symbol"
        val v = round2(amount)
        val neg = v < 0
        val abs = Math.abs(v)
        val body = if (showDecimals || (v - v.toLong().toDouble()) != 0.0) fmt2.get().format(abs) else fmt0.get().format(Math.round(abs))
        return (if (neg) "-" else "") + body + " " + symbol
    }

    fun num(amount: Double): String {
        // نفس حارس القيم غير المنتهية
        if (!amount.isFinite()) return "—"
        val v = round2(amount)
        val neg = v < 0
        val abs = Math.abs(v)
        val body = if ((v - v.toLong().toDouble()) != 0.0) fmt2.get().format(abs) else fmt0.get().format(Math.round(abs))
        return (if (neg) "-" else "") + body
    }

    // (M-2.2 توحيد): المساعد القانوني الوحيد للتقريب — BigDecimal HALF_UP (نصف بعيداً عن الصفر)
    // على القيمة المخزّنة نفسها (بدلاً من ضرب الفلواط في 100 — 2.675*100=267.4999… كان يقرّب 2.67
    // بينما القيمة العشرية الحقيقية 2.675 تقرَّب 2.68، فاختلفت سطور السلة عن الضريبة/الإجمالي سنتاً)
    // [P20-FIX agent10/13]: BigDecimal(v) يغلّف التمثيل الثنائي الدقيق (2.675 = 2.67499999…) فكان يُخرِب
    // نصف السنت إلى الأسفل — BigDecimal(v.toString()) يلتقط الحرف العشري المكتوب فتتعامل HALF_UP
    // مع 2.675 فعلاً ⇒ 2.68 (المختبِنات القائمة 0.125/0.135 ثنائياً آمنة وتجتاز كما هي)
    fun round2(v: Double): Double =
        if (v.isNaN() || v.isInfinite()) 0.0
        else BigDecimal(v.toString()).setScale(2, RoundingMode.HALF_UP).toDouble()

    // ── [P33-P8] نقطة التحويل الوحيدة بين عالم الريال Double وعالم القروش Long ──
    // كل تحويل مالي يمر هنا حصراً — لا ضرب يدوي بـ100 متناثر في الشجرة.

    /** Double ريال → Long قروش — HALF_UP على التمثيل العشري المكتوب (2.675 → 268 قرشاً) */
    fun toPiasters(v: Double): Long =
        if (v.isNaN() || v.isInfinite()) 0L
        else BigDecimal(v.toString()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()

    /** Long قروش → Double ريال — للاستهلاك العرضي القديم فقط (لا يُخزَّن مطلقاً) */
    fun fromPiasters(p: Long): Double = p / 100.0

    /** [P33-P8] تحليل متسامح لحقل إدخال ريال → قروش — نفس تطبيع parse */
    fun parseToPiasters(text: String): Long {
        val v = parse(text) ?: return 0L
        return toPiasters(v)
    }

    // ── [H4-1 V 2.5.0] نقطة التحويل الوحيدة بين «وحدة أجنبية 2dp» و«قروش الأساس» ──
    // امتداد لعقد P33-P8 نفسه: أي تحويل عملة أجنبية→أساس يمر هنا حصراً عبر
    // CurrencyMath (R17) — لا ضرب يدوي بالسعر متناثراً في الشجرة، وصفر Double
    // في الحساب (السعر الكتالوجي Double يُستهلك داخل rateMicrosFromCatalog وحده
    // عبر BigDecimal(toString) ثم يفنى).
    // الإخفاق مغلَق: null يعني «ارفض الحفظ برسالة» — لا صفر مالي زائف.

    /** مبلغ أجنبي 2dp → قروش أساس بسعر micros مختوم — يفوّض FxStampMath.toBasePiasters */
    fun foreignToBasePiasters(foreignMinor: Long, minorUnits: Int, rateMicros: Long): Long? =
        com.superbiz.app.domain.algo.FxStampMath.toBasePiasters(foreignMinor, minorUnits, rateMicros)

    /** قروش أساس → مبلغ أجنبي 2dp للعرض فقط — يفوّض FxStampMath.fromBasePiasters */
    fun baseToForeignMinor(basePiasters: Long, minorUnits: Int, rateMicros: Long): Long? =
        com.superbiz.app.domain.algo.FxStampMath.fromBasePiasters(basePiasters, minorUnits, rateMicros)

    // (M-2.3 توحيد): parse يقرأ مخرجات format — يطبّع الأرقام العربية والمفصولات العربية أولاً
    fun parse(text: String): Double? {
        val cleaned = normalizeDigits(text)
            .replace(",", "")
            .replace("٫", ".")
            .replace("،", ".")
            .trim()
        return cleaned.toDoubleOrNull()
    }

    /** تحويل بين العملات — [P20-FIX agent10] rateToBase = وحدات العملة لكل 1 من الأساسية
     *  (1 ريال = 0.2665 دولار) ⇒ من العملة إلى الأساسية = قسمة، والعكس ضرب */
    fun toBase(amount: Double, rate: Double): Double =
        if (rate == 0.0) 0.0 else round2(amount / rate)
    fun fromBase(amountBase: Double, rate: Double): Double = round2(amountBase * rate)

    /** (M-7.6 توحيد): تحليل متسامح لحقول الإدخال — نفس مسار parse مع قيمة افتراضية 0.0 */
    fun parseLenient(text: String): Double = parse(text) ?: 0.0

    /** (M-2.10 توحيد): تقريب 4 خانات عشرية HALF_UP — لنِسب مثل هامش الربح
 * [P20-FIX] نفس إصلاح round2 — BigDecimal(v.toString()) بدل المُغلّف الثنائي */
    fun round4(v: Double): Double =
        if (v.isNaN() || v.isInfinite()) 0.0
        else BigDecimal(v.toString()).setScale(4, RoundingMode.HALF_UP).toDouble()
}

object Dates {
    private val dayAr = arrayOf("الأحد","الاثنين","الثلاثاء","الأربعاء","الخميس","الجمعة","السبت")
    private val dayEn = arrayOf("Sun","Mon","Tue","Wed","Thu","Fri","Sat")

    // مُنسّقات التاريخ — [P20-FIX agent13] كانت مخزّنة كحقل مشترك و SimpleDateFormat
    // غير آمن خيطياً (InstallmentRepo يُنسّق داخل معاملة Room على خيط مختلف عن Main) ⇒ تواريخ فاسدة/
    // ArrayIndexOutOfBoundsException. صارت خاصية لكل استدعاء — كلفة الإنشاء مهملة مقابل الصوابية.
    private val fmtDayAr: SimpleDateFormat get() = SimpleDateFormat("d MMMM", Locale("ar"))
    private val fmtDayEn: SimpleDateFormat get() = SimpleDateFormat("d MMMM", Locale("en"))
    private val fmtIso: SimpleDateFormat get() = SimpleDateFormat("dd/MM/yyyy", Locale.US)

    fun startOfDay(ts: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ts
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /**
 * : بداية اليوم قبل N يوماً عبر التقويم — كانت النوافذ تُبنى
 * بإضافة 86,400,000ms ثابتة (نفس صنف R11-C13 المُصلَح في سلاسل المخططات) ففي
 * مناطق التوقيت الصيفي تنزلق منتصف الليل ساعة وتخلخل حدود النافذة.
*/
    fun startOfDayDaysAgo(daysBack: Int, now: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = startOfDay(now)
        c.add(Calendar.DAY_OF_MONTH, -daysBack.coerceAtLeast(0))
        return c.timeInMillis
    }

    fun addDays(ts: Long, days: Int): Long = ts + days * 86_400_000L

    fun monthStart(ts: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ts
        c.set(Calendar.DAY_OF_MONTH, 1)
        return startOfDay(c.timeInMillis)
    }

    fun yearStart(ts: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ts
        c.set(Calendar.MONTH, 0); c.set(Calendar.DAY_OF_MONTH, 1)
        return startOfDay(c.timeInMillis)
    }

    /** "12 سبتمبر" / "12 September" */
    fun dayMonth(ts: Long, ar: Boolean): String =
        (if (ar) fmtDayAr else fmtDayEn).format(Date(ts))

    /** "12/09/2026" */
    fun short(ts: Long): String =
        fmtIso.format(Date(ts))

    fun isMorning(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return h in 4..11
    }

    fun weekdayName(ts: Long, ar: Boolean): String {
        val c = Calendar.getInstance()
        c.timeInMillis = ts
        return if (ar) dayAr[c.get(Calendar.DAY_OF_WEEK) - 1] else dayEn[c.get(Calendar.DAY_OF_WEEK) - 1]
    }

    /** أيام التأخير عن تاريخ معين */
    fun daysSince(ts: Long, now: Long = System.currentTimeMillis()): Int =
        ((now - ts) / 86_400_000.0).toInt().coerceAtLeast(0)

    fun daysUntil(ts: Long, now: Long = System.currentTimeMillis()): Int =
        ((ts - now) / 86_400_000.0).let { if (it < 0) 0 else it.toInt() }

    /**
 * (M-2.5 توحيد): أيام التأخير بالتقريب لأعلى — أي تجاوز للاستحقاق حتى لو دقائق
 * يُعد يوماً واحداً (كان floor يجعل أول 24 ساعة تأخير = 0 أيام فتتعارض سلات
 * الأعمار مع كشف «متأخر» الذي يطابق أي تجاوز). صفر للتاريخ المستقبلي.
*/
    fun daysOverdue(due: Long, now: Long = System.currentTimeMillis()): Int {
        val d = (now - due) / 86_400_000.0
        if (d <= 0.0) return 0
        return kotlin.math.ceil(d).toInt()
    }

    /** أعداد الأيام لكل فاتورة (للأعمار): <30، 30-60، 60-90، >90 */
    fun agingBucket(days: Int): Int = when {
        days <= 30 -> 0
        days <= 60 -> 1
        days <= 90 -> 2
        else -> 3
    }
}
