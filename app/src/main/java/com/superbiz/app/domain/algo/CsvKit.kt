package com.superbiz.app.domain.algo

/**
 * [P12-a] باني CSV نقي (بلا أي استيراد Android — Kotlin فقط، قابل للاختبار على JVM):
 *
 * - escape: يلفّ الحقل بعلامتي اقتباس إذا احتوى فاصلة أو اقتباساً أو سطراً جديداً
 *   (\n أو \r — الأخير احتياط لاتساق RFC 4180 لأن نهايات أسطرنا CRLF)، ويضاعف
 *   الاقتباسات الداخلية (" → ""). هذا ما يجعل ملاحظة مثل «سعر 50, ريال» لا تكسر الأعمدة.
 * - buildCsv: يبني الملف كاملاً بنهايات أسطر CRLF (\r\n — المعيار الذي يفتحه Excel
 *   والتطبيقات العربية بشكل صحيح) ويرودّ كل الحقول عبر escape بما فيها الترويسات.
 *   الصفوف الأقصر من الترويسات تُكمَّل بحقول فارغة ""، والأطول تُقتطع عند طول الترويسات
 *   (عدد أعمدة ثابت ومتوقّع).
 * - bom=true يسبق الملف بـ \uFEFF (UTF-8 BOM): بدونها يفتح Excel العربية بترميز مشوّه
 *   (MojiBake) لأنه لا يستنتج UTF-8 من المحتوى وحده — الـBOM إعلان صريح يقضي على
 *   الخطوة اليدوية «Data → From Text/CSV». تكلفته 3 بايتات مقابل فتح صحيح من أول ضغطة.
 *
 * النصوص تُبنى بـ String عادية؛ الترميز إلى بايتات UTF-8 مسؤولية المستدعي
 * (toByteArray(Charsets.UTF_8)) ليبقى الكائن نقياً بلا اعتماديات.
 */
object CsvKit {

    /** [P12-a] حروف تفرض التغليف بعلامات اقتباس: فاصلة، اقتباس، سطر جديد (مبسوط أو مُرجَع) */
    private val NEEDS_QUOTES = charArrayOf(',', '"', '\n', '\r')

    /**
     * [P12-a] تهيئة حقل CSV واحد: bare إن لم يحتوي حروفاً خاصة، وإلا ملفوّف بعلامات
     * اقتباس مع مضاعفة الاقتباسات الداخلية. مثال: «أ,ب» → "أ,ب"، قال "مرحبا" → "قال ""مرحبا""".
     * [P20-FIX agent13]: حماية من حِقن الصيغ (OWASP CSV Injection) — حقل يبدأ بـ = + @
     * (أو TAB/CR) كان يُكتب خاماً فيُنفّذه Excel/Sheets كصيغة (WEBSERVICE/HYPERLINK…).
     * الاقتباس لا يمنع التنفيذ — البادئة ' تجعل Excel يعامله نصاً (ويخفيها).
     *
     * [تدقيق L-5] توحيد الحرس: كان '-' ضمن المجموعة بينما مسار DataExport (H-21)
     * أزاله — «-46.6» في تصدير المفضّلات كانت تُكتب «'-46.6» فتقرأ Sheets إحداثيات
     * النصَّ لا رقم. السالب لا يبدأ صيغة في Excel (قيمة عددية) — المجموعة الآن
     * = + @ TAB CR في المسارين، وهذه الدالة هي المصدر الوحيد (DataExport تفوّض لها).
     *
     * [RVW-25][Wave R11] فجوة L-5 الجزئية تُغلق بحرس شكل-واعٍ: ادّعاء «السالب لا يبدأ
     * صيغة» صحيح للأرقام الخالصة فقط — إكسل يقيّم ‎-SUM(A1)‎ و‎-1+WEBSERVICE(…)‎ صيغةً
     * (حروفاً = + @ كانت تحرسها والسالب كان الثقب الوحيد). البادئة ' تُضاف الآن حين
     * يبدأ الحقل بـ '-' ويليه حرفٌ (دالة/مرجع خلية/نص) — والأرقام الخالصة (‎-46.6،
     * ‎-2+3) تبقى عارية كعقد L-5 حرفياً فلا تُكسر إحداثيات ولا أرصدة.
     */
    fun escape(field: String): String {
        val guarded = when {
            field.isEmpty() -> field
            field[0] in "=@+\t\r" -> "'$field"
            field[0] == '-' && field.length > 1 && field.substring(1).any { it.isLetter() } -> "'$field"
            else -> field
        }
        if (guarded.indexOfAny(NEEDS_QUOTES) < 0) return guarded
        return "\"" + guarded.replace("\"", "\"\"") + "\""
    }

    /**
     * [P12-a] بناء ملف CSV كامل: ترويسة + صفوف بنهايات أسطر CRLF وBOM اختياري.
     * الصفوف الأقصر من الترويسات تُكمَّل بـ ""، والأطول تُقتطع (أعمدة ثابتة).
     */
    fun buildCsv(headers: List<String>, rows: List<List<String>>, bom: Boolean): String {
        val sb = StringBuilder()
        if (bom) sb.append('\uFEFF') // UTF-8 BOM — Excel يفتح العربية صحيحة بلا خطوات يدوية
        sb.append(headers.joinToString(",") { escape(it) })
        val width = headers.size
        for (row in rows) {
            sb.append("\r\n")
            val padded = if (row.size < width) row + List(width - row.size) { "" } else row
            sb.append(padded.take(width).joinToString(",") { escape(it) })
        }
        return sb.toString()
    }
}
