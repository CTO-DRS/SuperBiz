package com.superbiz.app.domain.telemetry

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [E1.3 V 3.4.0] عقد القياس البعيد — خصوصية أولاً، إجماليات حصراً، موافقة قبل كل شيء
 * ═══════════════════════════════════════════════════════════════════════════
 * عقد خارطة الطريق E1.3: «تحليلات المنتج بأحداث إجمالية حصراً — عدّادات
 * ومدد، لا معرفات، لا محتويات؛ وخريطة بيانات PDPL تسجل كل حدث».
 *
 * الأرضية هنا (قبل أي مزود خارجي — إدماج مزود الأعطال باب مفاتيح خارجية
 * موثق في السجل): قائمة أحداث مغلقة بالاسم، كل حدث معه وحدته ومدة حفظه
 * وغرضه في خريطة بيانات PDPL، وبوابة موافقة صامتة-صفر: بلا تسجيلٍ صريح
 * لا يُكتب ولا يخرج أي حدث أبداً، وحتى بعد الموافقة كل ما يمكن أن يغادر
 * الجهاز هو Map<String,Long> — النوع نفسه يجعل تسريب محتوى مستحيلاً.
 *
 * الفشل مغلق: أي اسم حدث خارج القائمة يرمي — حدث جديد يبدأ بقرار موثق
 * يعدّل القائمة واختبارها معاً (عقد «الافتراض مغلق» نفسه).
 */
object TelemetryContract {

    /** مواصفة الحدث — العدّاد وحده، بلا أي حقل حر */
    data class EventSpec(
        val unit: String,        // "count" أو "ms" — عدّاد أو مدة، لا شيء ثالث
        val retentionDays: Int,  // مدة الحفظ القصوى (عقد PDPL لكل حدث)
        val purpose: String      // الغرض المعلن في خريطة بيانات PDPL
    )

    /**
     * القائمة المغلقة — أحداث المزامنة (مبدأ رؤية RVW-27 المعمَّم) وحجم
     * طابور الإصلاح. لا معرفات ولا أرقام عملاء ولا نصوص حرة — النوع Long.
     */
    val ALLOWED: Map<String, EventSpec> = mapOf(
        "sync_round_ok" to EventSpec("count", 90, "مراقبة صحة المزامنة (إجماليات)"),
        "sync_round_fail" to EventSpec("count", 90, "مراقبة صحة المزامنة (إجماليات)"),
        "sync_backlog_size" to EventSpec("count", 90, "مراقبة حجم طابور المزامنة"),
        "sync_poison_docs" to EventSpec("count", 180, "رؤية الوثائق السامة (RVW-27)"),
        "repair_retry_class_a" to EventSpec("count", 90, "فئات إعادة المحاولة (إجماليات)"),
        "repair_retry_class_b" to EventSpec("count", 90, "فئات إعادة المحاولة (إجماليات)")
    )

    /** مواصفة حدث بالاسم — اسم غريب يرمي (فشل مغلق لا حدث صامت) */
    fun requireAllowed(name: String): EventSpec =
        ALLOWED[name] ?: throw IllegalArgumentException(
            "telemetry event not in the closed allowlist: $name"
        )
}

/**
 * حالة القياس — عدّادات محلية صرفة. الجمع مستقبلي (مزود خارجي = باب مفاتيح
 * منفصل): كل ما يمسه هذه الوحدة لا يغادر الجهاز، و[egressPayload] هو العقد
 * النوعي لما يجوز أن يغادره يوماً.
 */
class TelemetryState(private var consented: Boolean) {

    private val counters = LinkedHashMap<String, Long>()

    /** تسجيل دفعة عدّاد — بلا موافقة = لا شيء يُكتب حرفياً (الصمت الصفر الموعود) */
    fun record(name: String, delta: Long = 1L) {
        TelemetryContract.requireAllowed(name)
        if (!consented) return
        counters[name] = (counters[name] ?: 0L) + delta
    }

    /** لقطة قراءة للعرض المحلي (شاشة المزامنة) — بلا موافقة دائماً فارغة */
    fun snapshot(): Map<String, Long> = counters.toMap()

    /**
     * ما يجوز أن يغادر الجهاز — القائمة فرعية من العدّادات فقط، والقيم Long.
     * بلا موافقة: فارغ حرفياً حتى لو وُجدت بيانات محلية سابقة.
     */
    val egressPayload: Map<String, Long>
        get() = if (consented) counters.toMap() else emptyMap()

    /** الموافقة قرار حي — تُمنح أو تُسحب، والسحب لا يمحو المحلي (قرار عرض) */
    fun setConsent(value: Boolean) {
        consented = value
    }

    val hasConsent: Boolean get() = consented
}
