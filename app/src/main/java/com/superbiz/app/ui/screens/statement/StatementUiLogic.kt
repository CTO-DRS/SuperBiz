package com.superbiz.app.ui.screens.statement

import java.util.Calendar
import java.util.concurrent.atomic.AtomicLong

/**
 * [P17-c] منطق واجهة كشف الحساب النقي — بلا أي اعتماد أندرويد ولا أنواع عقد
 * 17-a/17-b، لذا يبقى قابلاً للاختبار الجافي الصرف حتى قبل دمج الموجتين.
 *
 * كل ما يلمس العقد (StatementPeriodPreset/StatementStyle/PdfElement…) يمر عبر
 * StatementUiFacade وحدها؛ هنا نماذج واجهة محلية (Ui*) ووظائف حتمية.
 */

/** انعكاس محلي لأسماء مضاهية لـ StatementPeriodPreset من عقد 17-a (الربط بالاسم في الواجهة) */
enum class UiPeriodPreset {
    TODAY, THIS_WEEK, LAST_WEEK, THIS_MONTH, LAST_MONTH,
    LAST_3_MONTHS, LAST_6_MONTHS, THIS_YEAR, LAST_YEAR, CUSTOM;

    companion object {
        /** ربط متسامح باسم ثابت العقد — اسم غريب ⇒ THIS_MONTH (سلوك آمن) */
        fun ofName(name: String?): UiPeriodPreset =
            entries.firstOrNull { it.name == name } ?: THIS_MONTH
    }
}

/** نافذة زمنية محسومة من الطابع «الآن» — من شامل إلى شامل */
fun resolvePeriod(
    preset: UiPeriodPreset,
    now: Calendar,
    customFrom: Long = 0L,
    customTo: Long = 0L
): Pair<Long, Long> {
    fun startOfDay(c: Calendar): Calendar = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    fun endOfDay(c: Calendar): Long = startOfDay(c).timeInMillis + 86_400_000L - 1
    fun addMonths(c: Calendar, months: Int): Calendar = (c.clone() as Calendar).apply {
        set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, months)
    }
    // أسبوع أعمال يبدأ الاثنين — ثابت بغضّ النظر عن لغة الجهاز (قابلية اختبار)
    fun weekStart(c: Calendar): Calendar {
        val s = startOfDay(c)
        s.firstDayOfWeek = Calendar.MONDAY
        val dow = s.get(Calendar.DAY_OF_WEEK)
        val back = if (dow == Calendar.SUNDAY) 6 else dow - Calendar.MONDAY
        s.add(Calendar.DAY_OF_MONTH, -back)
        return s
    }

    return when (preset) {
        UiPeriodPreset.TODAY -> startOfDay(now).timeInMillis to endOfDay(now)
        UiPeriodPreset.THIS_WEEK -> weekStart(now).timeInMillis to endOfDay(now)
        UiPeriodPreset.LAST_WEEK -> {
            val s = weekStart(now).apply { add(Calendar.DAY_OF_MONTH, -7) }
            s.timeInMillis to (s.timeInMillis + 7 * 86_400_000L - 1)
        }
        UiPeriodPreset.THIS_MONTH -> {
            val s = startOfDay(now).apply { set(Calendar.DAY_OF_MONTH, 1) }
            s.timeInMillis to endOfDay(now)
        }
        UiPeriodPreset.LAST_MONTH -> {
            // [P17-integration] تقويم كامل للشهر السابق: أول يوم 00:00 حتى نهاية آخر يوم —
            // كان addMonths(-1) يُبقي وقت اليوم وساعة 10:00 وaddMonths(0)-1 يتجاوز الشهر
            val s = startOfDay(now).apply {
                set(Calendar.DAY_OF_MONTH, 1)
                add(Calendar.MONTH, -1)
            }
            val e = (s.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
            s.timeInMillis to (e.timeInMillis - 1)
        }
        UiPeriodPreset.LAST_3_MONTHS -> addMonths(now, -2).timeInMillis to endOfDay(now)
        UiPeriodPreset.LAST_6_MONTHS -> addMonths(now, -5).timeInMillis to endOfDay(now)
        UiPeriodPreset.THIS_YEAR -> {
            val s = startOfDay(now).apply { set(Calendar.DAY_OF_YEAR, 1) }
            s.timeInMillis to endOfDay(now)
        }
        UiPeriodPreset.LAST_YEAR -> {
            val jan = startOfDay(now).apply {
                set(Calendar.DAY_OF_YEAR, 1)
                add(Calendar.YEAR, -1)
            }
            val decEnd = jan.clone() as Calendar
            decEnd.add(Calendar.YEAR, 1)
            decEnd.timeInMillis -= 1
            jan.timeInMillis to decEnd.timeInMillis
        }
        // CUSTOM: النافذة المختارة يدوياً — ناقصة ⇒ هذا الشهر (لا نافذة مقلوبة أبداً)
        UiPeriodPreset.CUSTOM ->
            if (customFrom > 0 && customTo > 0)
                minOf(customFrom, customTo) to maxOf(customFrom, customTo)
            else resolvePeriod(UiPeriodPreset.THIS_MONTH, now)
    }
}

/** نموذج بطاقة قالب للاستوديو — id/الفئة يأتيان من العقد عبر الواجهة */
data class TemplateUi(
    val id: String,
    val nameAr: String,
    val nameEn: String,
    val descAr: String,
    val descEn: String,
    val category: String,
    val isBuiltIn: Boolean,
    val isFavorite: Boolean,
    val isDefault: Boolean
) {
    fun label(ar: Boolean): String = if (ar) nameAr else nameEn
    fun desc(ar: Boolean): String = if (ar) descAr else descEn
}

/** فرض الاستوديو: بحث + فئة واحدة + قناعا المفضّل والمخصص */
fun filterTemplates(
    all: List<TemplateUi>,
    query: String,
    category: String?,
    favoritesOnly: Boolean,
    customOnly: Boolean
): List<TemplateUi> {
    val q = query.trim().lowercase()
    return all.filter { t ->
        if (favoritesOnly && !t.isFavorite) return@filter false
        if (customOnly && t.isBuiltIn) return@filter false
        if (category != null && t.category != category) return@filter false
        if (q.isEmpty()) return@filter true
        t.id.lowercase().contains(q) ||
            t.nameAr.lowercase().contains(q) ||
            t.nameEn.lowercase().contains(q)
    }
}

/**
 * تبديل عنصر PDF في مجموعة show — إضافة إن غاب وحذف إن وُجد.
 * تعمل على أسماء نصية لكي لا تعتمد تعداد PdfElement من 17-b.
 */
fun toggleElement(current: Set<String>, id: String): Set<String> =
    if (id in current) current - id else current + id

/** هل النافذة الزمنية صالحة لمعاينة حية؟ */
fun isPreviewPossible(fromTs: Long, toTs: Long): Boolean = fromTs > 0 && toTs > fromTs

/** تنظيف اسم ملف: يبقي العربية واللاتينية والأرقام وشرطات/نقاط، يقصّ إلى 60 محرفاً */
fun sanitizeFileName(raw: String): String {
    val cleaned = raw.replace(Regex("[^A-Za-z0-9._\\- \\u0600-\\u06FF]"), "_")
        .replace(Regex("[_ ]{2,}"), "_")
        .trim('_', ' ', '.')
        .take(60)
    return cleaned.ifBlank { "statement" }
}

/** ترميز حقول إضافية (key=value) في سطر واحد آمن — بدل إدخال تبعية JSON جديدة */
fun encodeExtras(map: Map<String, String>): String =
    map.entries.joinToString("&") { (k, v) ->
        val ek = java.net.URLEncoder.encode(k, "UTF-8")
        val ev = java.net.URLEncoder.encode(v, "UTF-8")
        "$ek=$ev"
    }

/** فك ترميز encodeExtras — يتجاهل الأزواج التالفة بصمت */
fun decodeExtras(encoded: String): Map<String, String> =
    encoded.split('&')
        .filter { it.isNotBlank() }
        .mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) return@mapNotNull null
            runCatching {
                java.net.URLDecoder.decode(pair.substring(0, i), "UTF-8") to
                    java.net.URLDecoder.decode(pair.substring(i + 1), "UTF-8")
            }.getOrNull()
        }.toMap()

/** تحليل لون HEX (#RGB/#RRGGBB/#AARRGGBB) — null عند غير الصالح */
fun parseHexColor(input: String): Int? {
    var s = input.trim().removePrefix("#").lowercase()
    if (s.isEmpty()) return null
    when (s.length) {
        // [P17-integration] التوسيع الثلاثي يضيف البادئة ff فوراً — كان يفوتها فيعود اللون بشفافية 0
        3 -> s = "ff" + s.map { "$it$it" }.joinToString("")
        6 -> s = "ff$s"
        8 -> {}
        else -> return null
    }
    if (!s.all { it in '0'..'9' || it in 'a'..'f' }) return null
    return runCatching { s.toLong(16).toInt() }.getOrNull()
}

/** تسمية صفحة المعاينة «1 / N» — أرقام لاتينية دائماً (محايدة الاتجاه تحت RTL) */
fun pageLabel(current: Int, total: Int): String {
    val c = current.coerceIn(1, total.coerceAtLeast(1))
    val t = total.coerceAtLeast(1)
    return "$c / $t"
}

/**
 * حكم «الأحدث يفوز» للمعاينة الحية: تغيير سريع للمعاملات يطلق أكثر من توليد
 * متوازٍ، والنتائج الناضجة القديمة يجب أن تُرمى لا أن تطغى على الأخيرة.
 */
class LatestWins {
    private val gen = AtomicLong(0)

    /** احجز ترتيباً قبل إطلاق العمل */
    fun next(): Long = gen.incrementAndGet()

    /** هل هذا الترتيب ما زال الأحدث؟ */
    fun isLatest(token: Long): Boolean = token == gen.get()
}

// ═══════════════════════════════════════════════════════════════════
// [P18-c] منطق التسليمات وسجل التدقيق — نقِيّ كما فوق: بلا أي استيراد أندرويد،
// وتُرجع «مفاتيح نصية» تحلّها طبقة الواجهة عبر when إلى R.string (نمط 17-c).
// ═══════════════════════════════════════════════════════════════════

/** آلة حالات التسليم — مطابقة لتوثيق StatementDeliveryEntity عند 17-a */
val DELIVERY_STATUSES = listOf("PENDING", "PROCESSING", "SENT", "FAILED", "RETRYING", "CANCELLED")

/** القنوات التي يمكن إعادة إرسالها فعلياً عبر SMTP — البقية «فتح قناة» لا وهم */
val SMTP_RETRYABLE_CHANNELS = listOf("EMAIL", "SMTP")

/**
 * لون حالة التسليم ARGB — استثناء الألوان المسموح في الواجهة (نقطة صغيرة داخل رقاقة)،
 * تُستهلك بـ Color(deliveryStatusColor(status)).
 */
fun deliveryStatusColor(status: String): Long = when (status) {
    "SENT" -> 0xFF2E7D32L        // أخضر
    "FAILED" -> 0xFFC62828L      // أحمر
    "RETRYING" -> 0xFFB26A00L    // كهرماني
    "PROCESSING" -> 0xFF1565C0L  // أزرق
    "PENDING" -> 0xFF616161L     // رمادي
    "CANCELLED" -> 0xFF424242L   // رمادي داكن
    else -> 0xFF616161L
}

/** قرار إعادة المحاولة — حدود المحاولات تُقرأ من autoRetryMax (1..5) */
enum class RetryDecision { CAN_RETRY_NOW, AUTO_SCHEDULED, EXHAUSTED, NOT_RETRYABLE }

fun retryDecision(status: String, attempts: Int, maxAttempts: Int): RetryDecision = when (status) {
    "FAILED" -> if (attempts >= maxAttempts.coerceAtLeast(1)) RetryDecision.EXHAUSTED
    else RetryDecision.CAN_RETRY_NOW
    "RETRYING" -> RetryDecision.AUTO_SCHEDULED
    // SENT/PENDING/PROCESSING/CANCELLED وأي حالة مجهولة: بلا إعادة
    else -> RetryDecision.NOT_RETRYABLE
}

// ── مفاتيح الموارد المشتركة بين المنطق والواجهة ──
const val KEY_BACKOFF_15M = "st2_backoff_15m"
const val KEY_BACKOFF_1H = "st2_backoff_1h"
const val KEY_BACKOFF_6H = "st2_backoff_6h"
const val KEY_BACKOFF_24H = "st2_backoff_24h"

/** فترة التهدئة التقديرية بعد المحاولة رقم N — مفتاح نصي تحلّه الواجهة */
fun backoffLabel(attempts: Int): String = when {
    attempts <= 1 -> KEY_BACKOFF_15M
    attempts == 2 -> KEY_BACKOFF_1H
    attempts == 3 -> KEY_BACKOFF_6H
    else -> KEY_BACKOFF_24H
}

/** أفعال التدقيق المعروفة — الغائب يُعرض بنصه الأصلي (fallback صادق) */
val AUDIT_ACTIONS = listOf(
    "STATEMENT_ISSUE", "STATEMENT_REISSUE", "STATEMENT_SEND", "STATEMENT_DELETE",
    "STATEMENT_AUTO_ISSUE", "STATEMENT_RULE_SAVE", "STATEMENT_RULE_DELETE",
    "STATEMENT_RULE_RUN", "STATEMENT_TEMPLATE_SAVE"
)

/** مفتاح التسمية لفعل تدقيق — المجهول يعود بنصه الأصلي كي تعرضه الواجهة كما هو */
fun auditActionLabelKey(action: String): String = when (action) {
    "STATEMENT_ISSUE" -> "st2_audit_act_issue"
    "STATEMENT_REISSUE" -> "st2_audit_act_reissue"
    "STATEMENT_SEND" -> "st2_audit_act_send"
    "STATEMENT_DELETE" -> "st2_audit_act_delete"
    "STATEMENT_AUTO_ISSUE" -> "st2_audit_act_auto"
    "STATEMENT_RULE_SAVE" -> "st2_audit_act_rule_save"
    "STATEMENT_RULE_DELETE" -> "st2_audit_act_rule_del"
    "STATEMENT_RULE_RUN" -> "st2_audit_act_rule_run"
    "STATEMENT_TEMPLATE_SAVE" -> "st2_audit_act_tpl"
    else -> action
}

/**
 * تفكيك سطر تفاصيل التدقيق «k=v k2=v2» إلى أزواج — تسامحي عمداً:
 * قيمة فارغة («a=») تبقى زوجاً بقيمة فارغة، وكلمة بلا = تلحق بقيمة السابق
 * (قيم بمسافات بلا اقتباس)، وكل ما قبل أول = يصبح زوجاً بمفتاح فارغ.
 */
fun formatAuditDetails(details: String): List<Pair<String, String>> {
    if (details.isBlank()) return emptyList()
    val out = mutableListOf<Pair<String, String>>()
    for (tok in details.trim().split(Regex("\\s+"))) {
        val i = tok.indexOf('=')
        if (i > 0) {
            out += tok.substring(0, i) to tok.substring(i + 1)
        } else {
            val last = out.lastOrNull()
            if (last != null) out[out.lastIndex] = last.first to (last.second + " " + tok).trim()
            else out += "" to tok
        }
    }
    return out
}

// ── الوقت النسبي — مفاتيح تُحل في الواجهة بقيم %1$d ──
const val KEY_REL_NOW = "st2_rel_now"
const val KEY_REL_MIN = "st2_rel_min"
const val KEY_REL_HOUR = "st2_rel_hour"
const val KEY_REL_DAY = "st2_rel_day"
const val KEY_REL_FUTURE = "st2_rel_future"
const val KEY_REL_NEVER = "st2_rel_never"

data class RelativeTime(val key: String, val value: Int)

/** الوقت النسبي لطابع زمني — طابع ≤ 0 يعني «لم يحدث بعد» (sentAt/scheduledFor اختياريان) */
fun relativeTime(ts: Long, now: Long): RelativeTime {
    if (ts <= 0) return RelativeTime(KEY_REL_NEVER, 0)
    val diff = now - ts
    return when {
        diff < 0 -> RelativeTime(KEY_REL_FUTURE, ((-diff) / 60_000L).toInt())
        diff < 60_000L -> RelativeTime(KEY_REL_NOW, 0)
        diff < 3_600_000L -> RelativeTime(KEY_REL_MIN, (diff / 60_000L).toInt())
        diff < 86_400_000L -> RelativeTime(KEY_REL_HOUR, (diff / 3_600_000L).toInt())
        else -> RelativeTime(KEY_REL_DAY, (diff / 86_400_000L).toInt())
    }
}
