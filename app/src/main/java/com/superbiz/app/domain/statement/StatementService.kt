package com.superbiz.app.domain.statement

import com.superbiz.app.domain.algo.TimeMath
import com.superbiz.app.util.Money
import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale

/**
 * [P17-a] خدمة كشف الحساب — منطق نقي 100% (JVM بلا أندرويد) يغذي repo وتمويج 17-b/17-c.
 *
 * قرارات موثقة (مطلوبة صراحة في العقد):
 *
 * 1) التقويم والمنطقة: كل حدود الفترات وتاريخ رقم التحقق تُحسب بـ java.util.Calendar
 *    بالمنطقة الافتراضية للجهاز — لا java.time (minSdk 24 بلا desugaring، نفس مبرر
 *    OverdueReminderPolicy) ولا UTC. المنطق: الكشف مستند تجاري يُقرأ بتوقيت تاجر
 *    السعودية (UTC+3)، واعتماد UTC كان سيزيح بداية اليوم 3 ساعات ويكسر حدود
 *    «اليوم/الأسبوع/الشهر» لتاجر يعمل مساءً. نفس منطقة periodRange تتكرر في
 *    verificationId فيتطابق تاريخ الكشف مع فترته.
 *
 * 2) الأسبوع: بدايته هي firstDayOfWeek للمنطقة/اللغة الافتراضية (السعودية: السبت،
 *    أمريكا: الأحد) — قرار مقصود: أسبوع التاجر لا أسبوع ISO الثابت.
 *
 * 3) LAST_3_MONTHS / LAST_6_MONTHS: أشهر تقويمية متتالية **تشمل الشهر الحالي**
 *    (بدء أول الشهر قبل 2/5 أشهر حتى آخر لحظة في الشهر الحالي) — الكشف أداة
 *    «ما ذهب حتى الآن» لا نافذة منزلقة بلا حدود شهرية.
 *
 * 4) contentHash بصمة المحتوى المنطقي فقط (الحقول والسطور والأرقام) — لا علاقة له
 *    بـ StatementPdfRenderer ولا بالخطوط/الألوان/العلامة المائية/عدد الصفحات؛
 *    تعديل شكل الـPDF لا يغير البصمة، وتعديل رقم أو مبلغ يغيّرها. رقم التحقق (17-c)
 *    يقارن هذه البصمة وحدها.
 *
 * كل دوالها تُغطى في StatementServiceTest (JVM نقي).
 */
object StatementService {

    /**
     * التسامح المحاسبي التاريخي (ريال Double) — [P33-P8] بقي ثابتاً لاستهلاك
     * StatementRulesEngine (TOLERANCE = StatementService.TOLERANCE) لأن المحرك النقي
     * يقارن بالريالات ولم يُرحَّل بعد؛ أما المقارنات المحاسبية في هذا الملف فصارت
     * مساواة تامة بالقروش (Long) ولا يستخدمه شيء هنا.
     */
    const val TOLERANCE = 0.005

    private const val DAY_MS = 86_400_000L

    // ───────── نافذة الفترة ─────────

    /**
     * حدود فترة الكشف [from, to] شاملة الطرفين (to = آخر ميلي ثانية في اليوم الأخير).
     * CUSTOM: يعيد النافذة الممررة مع فرض from <= to (معكوسة إن لزمت) — لا يرمي استثناء
     * كي لا تنكسر شاشة 17-b على تاريخ منزل بصمت.
     */
    fun periodRange(
        preset: StatementPeriodPreset,
        now: Long,
        customFrom: Long = 0,
        customTo: Long = 0
    ): Pair<Long, Long> = when (preset) {
        StatementPeriodPreset.TODAY -> TimeMath.startOfDay(now) to TimeMath.endOfDay(now)

        StatementPeriodPreset.THIS_WEEK -> weekBounds(now)
        StatementPeriodPreset.LAST_WEEK -> {
            val (s, e) = weekBounds(now)
            (s - 7 * DAY_MS) to (e - 7 * DAY_MS)
        }

        StatementPeriodPreset.THIS_MONTH -> TimeMath.monthBounds(now)
        StatementPeriodPreset.LAST_MONTH -> {
            val c = Calendar.getInstance().apply { timeInMillis = now }
            c.add(Calendar.MONTH, -1)
            TimeMath.monthBounds(c.timeInMillis)
        }

        StatementPeriodPreset.LAST_3_MONTHS -> trailingMonths(now, 2)
        StatementPeriodPreset.LAST_6_MONTHS -> trailingMonths(now, 5)

        StatementPeriodPreset.THIS_YEAR -> yearBounds(now)
        StatementPeriodPreset.LAST_YEAR -> {
            val c = Calendar.getInstance().apply { timeInMillis = now }
            c.add(Calendar.YEAR, -1)
            yearBounds(c.timeInMillis)
        }

        StatementPeriodPreset.CUSTOM ->
            if (customFrom <= customTo) customFrom to customTo else customTo to customFrom
    }

    /** حدود الأسبوع المحتوي لـnow — بداية الأسبوع من إعدادات المنطقة (قرار موثق أعلاه) */
    private fun weekBounds(now: Long): Pair<Long, Long> {
        val start = Calendar.getInstance().apply {
            timeInMillis = TimeMath.startOfDay(now)
            set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
        }.timeInMillis
        return start to (start + 7 * DAY_MS - 1)
    }

    /** [back months] + الحالي: بدء أول الشهر قبل back أشهر حتى آخر لحظة بالشهر الحالي */
    private fun trailingMonths(now: Long, back: Int): Pair<Long, Long> {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.MONTH, -back)
        }
        val from = TimeMath.monthBounds(c.timeInMillis).first
        return from to TimeMath.monthBounds(now).second
    }

    private fun yearBounds(now: Long): Pair<Long, Long> {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        c.set(Calendar.DAY_OF_YEAR, 1)
        val start = TimeMath.startOfDay(c.timeInMillis)
        val endCal = (c.clone() as Calendar).apply { add(Calendar.YEAR, 1) }
        return start to (TimeMath.startOfDay(endCal.timeInMillis) - 1)
    }

    // ───────── الملخص ─────────

    /**
     * تلخيص سطور الكشف — انظر علاقة الأرقام الموثقة عند StatementSummary.
     * [P33-P8] كل المبالغ قروش Long: المجاميع جمع صحيح تام بلا تقريب، والاتجاه
     * بمساواة تامة (موجب تام = مديونية على العميل، سالب تام = لمصلحته، صفر تام =
     * متوازن) — عتبة TOLERANCE الفاصلة العائمة لم يعد لها معنى هنا.
     * totalDiscounts تمرر كما هي (يحسبها repo من فواتير الفترة — ليست من السطور).
     */
    fun summarize(
        opening: Long,
        rows: List<StatementTxRow>,
        totalDiscounts: Long
    ): StatementSummary {
        val totalDebit = rows.sumOf { it.debit }
        val totalCredit = rows.sumOf { it.credit }
        val totalInvoices =
            rows.filter { it.typeKey == StatementTxTypes.INVOICE }.sumOf { it.debit }
        val totalPayments =
            rows.filter { it.typeKey == StatementTxTypes.PAYMENT }.sumOf { it.credit }
        // due وfinal متساويان عمداً اليوم (الفتح + حركة الفترة) — الفرق المستقبلي
        // بينهما (مثلًا إغلاق مقابل مستحق) سيكون قرار 17-c لا تغيير صامت هنا.
        val due = opening + totalDebit - totalCredit
        val direction = when {
            due > 0L -> BalanceDirection.DUE_ON_CUSTOMER      // [P33-P8] مساواة تامة
            due < 0L -> BalanceDirection.IN_FAVOR_OF_CUSTOMER // [P33-P8] مساواة تامة
            else -> BalanceDirection.BALANCED
        }
        return StatementSummary(
            opening = opening,
            totalDebit = totalDebit,
            totalCredit = totalCredit,
            totalPayments = totalPayments,
            totalInvoices = totalInvoices,
            totalDiscounts = totalDiscounts,
            due = due,
            final = due,
            direction = direction
        )
    }

    // ───────── الترقيم ─────────

    /**
     * رقم الكشف "STATEMENT-<year>-<seq 6 أرقام>" — seq يبدأ من 1.
     * seq أكبر من 999999 يُطبَع بسبعة أرقام (padStart لا يقتطع) فيبقى التسلسل صحيحاً
     * ولا يتصادم أبداً — قرار صادق بدل التفاف modulo.
     */
    fun statementNumber(seq: Long, year: Int): String =
        "STATEMENT-$year-${seq.toString().padStart(6, '0')}"

    /**
     * رقم التحقق "SB-ST-<yyyyMMdd>-<seq 6 أرقام>" — تاريخ لحظة الإصدار بالمنطقة
     * الافتراضية (قرار موثق ① أعلاه) وseq مستخرج من ذيل statementNumber بعد آخر '-'.
     * statementNumber تالفاً غير رقمي (مستحيل عملياً لأنه يولد بstatementNumber)
     * يعطي "000000" بدل الاستثناء — تحقق نقي لا يفشل.
     */
    fun verificationId(statementNumber: String, at: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = at }
        val ymd = String.format(
            Locale.US, "%04d%02d%02d",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)
        )
        val seq = statementNumber.substringAfterLast('-', "").takeWhile { it.isDigit() }
        val seqPadded = (if (seq.isEmpty()) "0" else seq).padStart(6, '0')
        return "SB-ST-$ymd-$seqPadded"
    }

    /** رقم عرض للطرف "P-000001" — ثابت لا يتغير بغياب أعمدة ترقيم في parties */
    fun partyNumber(partyId: Long): String =
        String.format(Locale.US, "P-%06d", partyId)

    // ───────── أسماء ملفات آمنة ─────────

    /**
     * اسم ملف PDF آمن للأنظمة كلها: يزيل محارف التحكم، ويستبدل المحارف الممنوعة
     * /\ : * ? " < > | بشرطة سفلية، ويقصّ النقاط والفراغات من الطرفين، ثم يلحق
     * الفترة "yyyyMMdd-yyyyMMdd" ويقيد الكل بـ80 محرفاً.
     * العربية تُحفظ كما هي (اسم «محمد أحمد» يبقى عربياً في اسم الملف) — القصظ فقط
     * على المحارف غير القانونية فعلاً في أنظمة الملفات الشائعة.
     */
    fun safeFileName(partyName: String, fromTs: Long, toTs: Long): String {
        val dates = "${ymd(fromTs)}-${ymd(toTs)}"   // 17 محرفاً دائماً
        val sb = StringBuilder()
        for (ch in partyName) {
            val code = ch.code
            when {
                code < 32 || code == 127 -> Unit                 // محارف تحكم: تُسقط
                ch in ILLEGAL_FILE_CHARS -> sb.append('_')       // ممنوعات Windows/Unix
                else -> sb.append(ch)
            }
        }
        var name = sb.toString().trim { it == '.' || it == ' ' }
        val maxName = (MAX_FILE_NAME - dates.length - 1).coerceAtLeast(1)
        if (name.length > maxName) {
            name = name.take(maxName).trim { it == '.' || it == ' ' }
        }
        if (name.isEmpty()) name = "statement"
        return "${name}_$dates"
    }

    /** المحارف الممنوعة في أسماء الملفات (Windows أساساً + الفواصل على كل الأنظمة) */
    private val ILLEGAL_FILE_CHARS = "/\\:*?\"<>|".toSet()

    private const val MAX_FILE_NAME = 80

    // ───────── مفاتيح عدم التكرار ─────────

    /**
     * مفتاح عدم تكرار توليد كشف لطرف/فترة: "party:<id>:<from>:<to>" ويُلحق ":<ruleId>"
     * إن صدر عن قاعدة مجدولة. المواضع ثابتة دائماً (id:from:to:rule) فلا لبس بين
     * الرقم الأخير كـtoTs أو كـruleId — القارئ يعرف عدد الحقول المتوقع.
     * ruleId = null يعني إصداراً يدوياً (لا يتصادم مع إصدار قاعدة لنفس الفترة).
     */
    fun dedupKey(partyId: Long, fromTs: Long, toTs: Long, ruleId: Long?): String =
        if (ruleId == null) "party:$partyId:$fromTs:$toTs"
        else "party:$partyId:$fromTs:$toTs:$ruleId"

    // ───────── بصمة المحتوى ─────────

    /**
     * SHA-256 hex للمحتوى المنطقي للكشف — القاعدة: سلسلة canonical بحقول ثابتة
     * الترتيب مفصولة بـ\n، والمبالغ [P33-P8] بقروش Long عبر Money.numP (تجميع آلاف
     * بفواصل وDecimalFormatSymbols(Locale.US) داخلياً ⇒ حتمية تامة عبر الأجهزة
     * واللغات، بديل %.2f على ريال Double). تعليق الملف يوثق أن البصمة لا تعني
     * شكل الـPDF إطلاقاً (قرار ④). ينضم بحقل V1 مقدماً لأي تطوير مستقبلي للقاعدة.
     *
     * [P33-P8] ملاحظة صريحة: بصمة الكشف الجديد تختلف عن بصمة ما قبل P8 لنفس المحتوى
     * المنطقي (تغيّرت صيغة المبالغ في السلسلة القياسية) — مقبول ومتوقع للكشوف الجديدة.
     */
    fun contentHash(d: StatementData): String {
        // [P33-P8] صيغة المبالغ الحتمية: قروش Long عبر numP — نفس القيمة دائماً
        // لنفس القروش مهما تغيّرت اللغة أو الجهاز (شمولية الواجهة الجديدة)
        val fP = { v: Long -> Money.numP(v) }
        val lines = mutableListOf<String>()
        lines += "SB-STMT-HASH-V1"
        lines += listOf(
            d.statementNumber, d.verificationId, d.lang.name,
            d.currency, d.fromTs.toString(), d.toTs.toString(), d.createdAt.toString()
        ).joinToString("|")
        // أحادية السطر: الوصف/الملاحظة قد تحمل أسطراً جديدة من إدخال المستخدم —
        // تُطبع كمسافات كي يبقى كل حقل سطراً واحداً في السلسلة القياسية
        val oneLine = { s: String -> s.replace("\r", " ").replace("\n", " ") }
        lines += listOf(
            "P", d.party.id.toString(), oneLine(d.party.name), d.party.partyNo
        ).joinToString("|")
        lines += listOf(
            "C", oneLine(d.company.businessName), d.company.companyNo
        ).joinToString("|")
        lines += listOf("N", oneLine(d.note ?: "")).joinToString("|")
        for (r in d.rows) {
            lines += listOf(
                "R", r.ts.toString(), oneLine(r.ref), r.typeKey, oneLine(r.desc),
                fP(r.debit), fP(r.credit), fP(r.balance)   // [P33-P8] قروش Long
            ).joinToString("|")
        }
        val s = d.summary
        lines += listOf(
            "S", fP(s.opening), fP(s.totalDebit), fP(s.totalCredit),
            fP(s.totalPayments), fP(s.totalInvoices), fP(s.totalDiscounts),
            fP(s.due), fP(s.final), s.direction.name
        ).joinToString("|")
        return sha256Hex(lines.joinToString("\n"))
    }

    /**
     * SHA-256 lowercase hex — مكشوفة internal للاختبار فقط (المتجه القياسي للسلسلة
     * الفارغة مقفول في StatementServiceTest كي لا تتبدل طريقة التلبيد صامتاً).
     */
    internal fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    // ───────── أدوات داخلية ─────────

    /** yyyyMMdd بالمنطقة الافتراضية */
    private fun ymd(ms: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return String.format(
            Locale.US, "%04d%02d%02d",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)
        )
    }
}
