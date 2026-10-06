package com.superbiz.app.domain

import com.superbiz.app.data.db.CheckEntity
import com.superbiz.app.data.db.Invoice
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * — منطق نقي لموجة الذمم + الشيكات + الأقساط (الوظائف 21-30).
 *
 * كل الدوال بلا حالة وبلا Android — قابلة للاختبار على JVM (DebtsChecksP4Test).
 * المال داخل مخططات الخطة بالهللات (Long) لضمان قسمة القرش تماماً،
 * والتحويل إلى الريالات فقط عند العرض/التحصيل عبر المسارات القائمة.
 * [P6-M3 إصلاح]: كل تقريب مالي هنا عبر algo.round2 التي أصبحت تفوّض (توحيد الموجة)
 * إلى المساعد القانوني util.Money.round2 (BigDecimal HALF_UP) — لا تقريب محلي في هذا الملف.
 *
 * [P33-P8] ترحيل مالي: كل المبالغ المخزنة (فواتير/شيكات/أقساط) صارت قروش Long،
 * فصارت مقارنات «المفتوح» تامة المساواة (> 0L) بلا عتبات فاصلة عائمة، والتنسيق
 * عبر Money.numP/formatP، وخصم السداد المبكر يقسم قروشاً صحيحة. الدوال النقية
 * التي ما زالت تستقبل ريال Double (halalasOf/riyals/weights/TopDebtors) بقيت
 * بواجهتها الريالية وتحوّل عند حدودها عبر Money.toPiasters/fromPiasters حصراً.
*/

// ═══════════════════════════════════════════════════════════════
// وظيفة 21 — خطة سداد مقترحة للذمة: قسمة الرصيد قرشاً بقرش بمواعيد شهرية
// ═══════════════════════════════════════════════════════════════

object DebtPlan {

    /** الحد الأقصى لعدد الدفعات في الخطة المقترحة (2..12 حسب المواصفة، 1 مسموح كحالة حدّية) */
    const val MIN_N = 1
    const val MAX_N = 12

    data class PlanPayment(val seq: Int, val halalas: Long, val dueDate: Long)

    /** تحويل مبلغ بالريالات (كما يأتي من حالة الواجهة القديمة) إلى قروش Long — [P33-P8] تفويض لنقطة التحويل الوحيدة Money.toPiasters */
    fun halalasOf(amountRiyals: Double): Long =
        com.superbiz.app.util.Money.toPiasters(amountRiyals)

    /** تحويل قروش إلى ريالات بدقة تامة (قسمة على 100 لا تضيع قروشاً في النطاق العملي) — [P33-P8] تفويض لـMoney.fromPiasters */
    fun riyals(halalas: Long): Double = com.superbiz.app.util.Money.fromPiasters(halalas)

    /**
 * بناء خطة سداد: الرصيد يُقسَّم على N دفعة قرشاً بقرش — كل دفعة تحصل على حصة
 * floor، وقروش البقايا تُوزَّع قرشاً واحداً لكل دفعة بالترتيب حتى يستوي المجموع
 * (مجموع الحصص == الرصيد تماماً، بلا فرق تقريب).
 * المواعيد شهرية بنفس يوم اليوم مع تقصير الأشهر القصيرة (31 → 28/30)
 * عبر TimeMath.nextMonthlyDue القائمة والمختبَرة، مع الحفاظ على وقت اليوم
 * (: الدالة القائمة تطبع منتصف الليل — نضيف فرق اليوم ليبقى التوقيت متسقاً).
 * رصيد ≤ 0 → خطة فارغة (حالة فراغ صادقة)؛ n يُقيَّد إلى 1..12.
*/
    fun build(balanceHalalas: Long, n: Int, today: Long): List<PlanPayment> {
        if (balanceHalalas <= 0L) return emptyList()
        val count = n.coerceIn(MIN_N, MAX_N)
        val base = balanceHalalas / count
        var leftover = balanceHalalas - base * count
        val day = dayOfMonth(today)
        val dayOffset = today - com.superbiz.app.domain.algo.TimeMath.startOfDay(today)
        val out = ArrayList<PlanPayment>(count)
        var prev = today
        for (i in 1..count) {
            var amount = base
            if (leftover > 0) { amount += 1; leftover -= 1 }
            val due = com.superbiz.app.domain.algo.TimeMath.nextMonthlyDue(prev, day) + dayOffset
            out.add(PlanPayment(seq = i, halalas = amount, dueDate = due))
            prev = due
        }
        return out
    }

    /** مجموع الحصص بالهللات — يفيد الاختبار والعرض */
    fun total(payments: List<PlanPayment>): Long = payments.sumOf { it.halalas }

    private fun dayOfMonth(ts: Long): Int =
        java.util.Calendar.getInstance().apply { timeInMillis = ts }.get(java.util.Calendar.DAY_OF_MONTH)
}

// ═══════════════════════════════════════════════════════════════
// بديل الاحتياطي R2 (مكان وظيفة 22 المُكافئة قائماً) — أرشفة الشيكات المسددة
// الأرشفة بلا تعديل مخطط: معرّفات محفوظة في DataStore، والمنطق هنا نقي.
// ═══════════════════════════════════════════════════════════════

object ChecksArchive {

    /** مرشحو الأرشفة: الشيكات المحصّلة فقط (status = 2) — «المسددة» حرفياً */
    fun candidates(all: List<CheckEntity>): List<CheckEntity> = all.filter { it.status == 2 }

    /**
     * تقسيم القائمة إلى (نشطة، مؤرشفة) — النشطة هي غير الموجودة في معرّفات الأرشيف.
     * الترتيب الأصلي محفوظ في القسمين، ومعرّفات الأرشيف غير المعروفة لا تضر.
     */
    fun split(all: List<CheckEntity>, archivedIds: Set<Long>): Pair<List<CheckEntity>, List<CheckEntity>> {
        val active = ArrayList<CheckEntity>()
        val archived = ArrayList<CheckEntity>()
        for (c in all) {
            if (c.id in archivedIds) archived.add(c) else active.add(c)
        }
        return active to archived
    }
}

// ═══════════════════════════════════════════════════════════════
// وظيفة 23 — فرز الذمم: أقدمية / أعلى رصيد / الاسم — مقارنات نقية في VM
// ═══════════════════════════════════════════════════════════════

object DebtSort {
    const val MODE_DEFAULT = 0   // السلوك القائم: |الرصيد| تنازلياً — بلا رقاقة مختارة
    const val MODE_OLDEST = 1    // الأقدمية: أقدم فاتورة بيع غير مسددة أولاً
    const val MODE_BALANCE = 2   // أعلى رصيد مدينة أولاً
    const val MODE_NAME = 3      // الاسم بعد التطبيع العربي

    /** صف فرز محايد يفصل المقارنات عن نماذج الواجهة — [P33-P8] الرصيد قروش (الفرز رتيب فلا يتغير السلوك) */
    data class SortRow(
        val partyId: Long,
        val name: String,
        val balance: Long,
        val oldestOpenDate: Long? // null = لا فواتير آجلة غير مسددة
    )

    /** خريطة أقدم فاتورة بيع غير مسددة لكل طرف — من الفواتير الحقيقية */
    fun oldestOpenMap(invoices: List<Invoice>): Map<Long, Long> =
        invoices.asSequence()
            // status<2 فقط — المسددة(2) والملغاة(3) ليست "آجلة مفتوحة"
            // [P33-P8]: open قروش Long — مقارنة تامة بدل عتبة 0.004
            .filter { it.isSale && it.status < 2 && it.open > 0L }
            .groupBy { it.partyId }
            .mapValues { (_, list) -> list.minOf { it.date } }

    /**
     * مقارنة الفرز العامة — تُغذَّى بمستخرج SortRow ليعمل على أي نموذج قائمة.
     * الأقدمية: الأقدم أولاً وnull (بلا آجلة) أخيراً؛ التعادل بالرصيد الأعلى.
     */
    fun <T> comparator(mode: Int, rowOf: (T) -> SortRow): Comparator<T> {
        val inner: Comparator<SortRow> = when (mode) {
            MODE_OLDEST -> compareBy<SortRow> { it.oldestOpenDate ?: Long.MAX_VALUE }
                .thenByDescending { it.balance }
            MODE_BALANCE -> compareByDescending<SortRow> { it.balance }
            MODE_NAME -> compareBy<SortRow> {
                com.superbiz.app.domain.algo.TextMath.arabicNormalize(it.name)
            }
            else -> compareByDescending<SortRow> { kotlin.math.abs(it.balance) }
        }
        return Comparator<T> { a, b -> inner.compare(rowOf(a), rowOf(b)) }
    }
}

// ═══════════════════════════════════════════════════════════════
// وظيفة 25 — شيكات هذا الأسبوع: المستحقة خلال 7 أيام + المتأخرة غير المسددة
// ═══════════════════════════════════════════════════════════════

object ChecksWeek {
    private const val DAY_MS = 86_400_000L

    /** الشيك غير المسدد = قيد التحصيل أو مودع (0/1) — المحصّل والمرتجع والملغى خارج الإحصاء */
    private fun open(c: CheckEntity): Boolean = c.status == 0 || c.status == 1

    /** (عدد المستحق خلال 7 أيام قادمة، عدد المتأخرة غير المسددة) */
    fun weekStats(checks: List<CheckEntity>, now: Long): Pair<Int, Int> {
        val todayStart = com.superbiz.app.domain.algo.TimeMath.startOfDay(now)
        val horizon = todayStart + 7 * DAY_MS
        var dueSoon = 0
        var overdue = 0
        for (c in checks) {
            if (!open(c)) continue
            val due = com.superbiz.app.domain.algo.TimeMath.startOfDay(c.dueDate)
            if (due < todayStart) overdue++
            else if (due <= horizon) dueSoon++
        }
        return dueSoon to overdue
    }

    /** مسند ترشيح القائمة بالنقر على الرقاقة: مفتوحة وتستحق خلال الأسبوع أو متأخرة */
    fun predicate(now: Long): (CheckEntity) -> Boolean {
        val todayStart = com.superbiz.app.domain.algo.TimeMath.startOfDay(now)
        val horizon = todayStart + 7 * DAY_MS
        return { c ->
            open(c) && com.superbiz.app.domain.algo.TimeMath.startOfDay(c.dueDate) <= horizon
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// وظيفة 24 — تصدير تقويم الشيكات ICS (VCALENDAR حقيقي وفق RFC 5545)
// ═══════════════════════════════════════════════════════════════

object ChecksIcs {

    /**
     * بناء تقويم VEVENT لكل شيك غير مسدد (0/1):
     * BEGIN:VCALENDAR / VERSION:2.0 / PRODID / … / BEGIN:VEVENT / UID /
     * DTSTAMP / DTSTART;VALUE=DATE:YYYYMMDD / SUMMARY / DESCRIPTION / END:VEVENT / END:VCALENDAR
     * الأسطر تنتهي بـ CRLF، والحقول مهربة وفق RFC 5545 (فاصلة/فاصلة منقوطة/سطر جديد/مائل).
     * stamp توقيت DTSTAMP بصيغة UTC الأساسية.
     */
    fun build(
        checks: List<CheckEntity>,
        partyName: (Long) -> String,
        stamp: Long = System.currentTimeMillis()
    ): String {
        val sb = StringBuilder()
        sb.append("BEGIN:VCALENDAR\r\n")
        sb.append("VERSION:2.0\r\n")
        sb.append("PRODID:-//SuperBiz//Checks Calendar//AR\r\n")
        sb.append("CALSCALE:GREGORIAN\r\n")
        for (c in checks) {
            if (c.status != 0 && c.status != 1) continue
            val party = partyName(c.partyId)
            sb.append("BEGIN:VEVENT\r\n")
            sb.append("UID:check-").append(c.id).append("@superbiz.app\r\n")
            sb.append("DTSTAMP:").append(utcBasic(stamp)).append("\r\n")
            sb.append("DTSTART;VALUE=DATE:").append(localDate(c.dueDate)).append("\r\n")
            sb.append("SUMMARY:").append(escape("شيك #${c.number} — $party — ${com.superbiz.app.util.Money.numP(c.amount)}")).append("\r\n")  // [P33-P8] قروش Long
            val desc = buildString {
                append("طرف: ").append(party)
                if (c.bank.isNotBlank()) append(" | بنك: ").append(c.bank)
                append(" | مبلغ: ").append(com.superbiz.app.util.Money.numP(c.amount))  // [P33-P8] قروش Long
                append(" | استحقاق: ").append(com.superbiz.app.util.Dates.short(c.dueDate))
            }
            sb.append("DESCRIPTION:").append(escape(desc)).append("\r\n")
            sb.append("END:VEVENT\r\n")
        }
        sb.append("END:VCALENDAR\r\n")
        return sb.toString()
    }

    /** تاريخ محلي بصيغة iCalendar الأساسية YYYYMMDD (DTSTART;VALUE=DATE) */
    fun localDate(ts: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(ts))

    /** طابع UTC بصيغة iCalendar الأساسية yyyyMMdd'T'HHmmss'Z' */
    fun utcBasic(ts: Long): String {
        val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return fmt.format(Date(ts))
    }

    /** تهريب نص iCalendar: المائل ثم الفاصلة المنقوطة ثم الفاصلة ثم السطر الجديد */
    fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\r\n", "\\n")
        .replace("\n", "\\n")
        .replace("\r", "\\n")
}

// ═══════════════════════════════════════════════════════════════
// وظيفة 27 — خصم السداد المبكر للأقساط (نسبة ثابتة معلنة — لا يوجد مفتاح
// DataStore مناسب والملف المالك للإعدادات لموجة أخرى، فالمعدل معلن هنا
// بثابت واحد واضح ويُحسب بدوال نقية قبل الاستحقاق فقط)
// ═══════════════════════════════════════════════════════════════

object EarlyPay {
    /** نسبة خصم السداد المبكر المعلنة — ثابت داخل الكود لغياب مفتاح إعدادات قابل للاستهلاك */
    const val RATE_PCT = 2.0

    /**
     * عرض الخصم: يعيد (المبلغ المدفوع اليوم، ما يُوفَّر) أو null إذا المبلغ المفتوح
     * غير كافٍ أو النسبة خارج النطاق.
     * [P33-P8] الحساب قروش Long: المفتوح قروش صحيحة، والنسبة تبقى Double —
     * المدخرات = Math.round(open × rate%) (قاعدة النسب — لا round2 على مبالغ مخزنة)،
     * والمدفوع = المفتوح − المدخرات (يغلق بالضبط بلا كسور). EPS حُذف — المقارنات تامة.
     */
    fun quote(openAmount: Long, ratePct: Double = RATE_PCT): Pair<Long, Long>? {
        if (openAmount <= 0L) return null
        if (!ratePct.isFinite() || ratePct <= 0.0 || ratePct >= 100.0) return null
        val saved = Math.round(openAmount * ratePct / 100.0)
        val payable = openAmount - saved
        if (payable <= 0L) return null
        return payable to saved
    }

    /** أهلية العرض: غير مسدد كلياً وما زال قبل تاريخ الاستحقاق (سداد «مبكر») — [P33-P8] بقروش ومساواة تامة */
    fun eligible(amount: Long, paid: Long, dueDate: Long, today: Long): Boolean {
        val open = amount - paid        // [P33-P8] مساواة تامة — كانت open <= EPS (0.005)
        if (open <= 0L) return false    // مسدد كلياً أو زائد ⇒ ليس «مبكراً»
        return com.superbiz.app.domain.algo.TimeMath.startOfDay(today) <
            com.superbiz.app.domain.algo.TimeMath.startOfDay(dueDate)
    }
}

// ═══════════════════════════════════════════════════════════════
// وظيفة 29 — أعمار الذمم: استهلاك خوارزمية agingBuckets القائمة (BizMath)
// لا إعادة اختراع — المباني الجديد هو تحويل الفواتير الحقيقية إلى مدخلاتها.
// ═══════════════════════════════════════════════════════════════

object DebtAging {

    /**
     * الدلاء [0-30، 31-60، 61-90، 90+] من فواتير البيع غير المسددة،
     * بعمر كل فاتورة من تاريخ استحقاقها (نفس أساس التقارير القائمة) —
     * الحساب نفسه من BizMath.agingBuckets القائمة والمختبَرة.
     */
    fun bucketsFromInvoices(invoices: List<Invoice>, today: Long): List<Double> {
        // status<2 فقط (0 غير مدفوعة، 1 جزئية) — المسددة(2) والملغاة(3) مستبعدة
        // حتى لو خالف الرصيد المفتوح الحالة (بيانات قديمة/شاذة) — الحالة هي المرجع.
        // [P33-P8]: open قروش Long — مقارنة تامة (> 0L)، ويُحوَّل إلى ريال via
        // Money.fromPiasters عند حدود analytics.agingBuckets القائمة (تبقى بواجهتها الريالية).
        val open = invoices.filter { it.isSale && it.status < 2 && it.open > 0L }
        return com.superbiz.app.domain.analytics.agingBuckets(
            open.map { com.superbiz.app.util.Money.fromPiasters(it.open) to it.dueDate }, today
        )
    }

    /**
     * أوزان شرائط العرض (0..1) من الدلاء — نسبة كل دلو من إجمالي المديونية.
     * إجمالي ≤ 0 → أوزان صفرية (يُخفى الشريط في الواجهة — صدق الفراغ).
     */
    fun weights(buckets: List<Double>): List<Float> {
        if (buckets.isEmpty()) return emptyList()
        val total = buckets.sum()
        if (total <= 0.004) return List(buckets.size) { 0f }
        return buckets.map { (it / total).toFloat().coerceIn(0f, 1f) }
    }
}

// ═══════════════════════════════════════════════════════════════
// وظيفة 30 — أعلى 5 مدينين: أكبر الأرصدة المدينة وحصتها من الإجمالي
// ═══════════════════════════════════════════════════════════════

object TopDebtors {

    data class Debtor(val name: String, val balance: Double, val sharePct: Double)

    /**
     * أكبر limit أرصدة مدينة (موجبة فقط — الدائنة والموازنة لا تُعد مديونية)،
     * والحصة = الرصيد ÷ إجمالي المديونية الكلي × 100 (ليست من أعلى 5 فقط).
     * بلا مدينين → قائمة فارغة (تُخفى البطاقة — إخفاء صادق).
     */
    fun top(rows: List<Pair<String, Double>>, limit: Int = 5): List<Debtor> {
        val positive = rows
            .map { (name, bal) -> name to com.superbiz.app.domain.algo.round2(bal) }
            .filter { it.second > 0.004 }
        if (positive.isEmpty()) return emptyList()
        val total = positive.sumOf { it.second }
        if (total <= 0.004) return emptyList()
        return positive
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
            .take(limit.coerceAtLeast(0))
            .map { (name, bal) ->
                Debtor(name, bal, com.superbiz.app.domain.algo.round2(bal / total * 100.0))
            }
    }
}
