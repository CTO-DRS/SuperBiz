package com.superbiz.app.domain.ml

/**
 * [F3 - ADR-005 D6] الواجهة النقية الثالثة التي تعبر حدود ADR-001 — بوابة التنبؤ
 * على الجهاز (نمط ZatcaGateway/GeminiGateway حرفيًا). تعمل على JVM بلا أندرويد
 * وبلا شبكة — أي تنفيذ يعيش في حزمة معزولة خارج domain.
 *
 * عقد F3 الموثق في ADR-005 D6:
 * - **التنبؤ على الجهاز حصرًا**: لا suspend ولا شبكة ولا خلفية — الاستدعاء
 *   المتزامن المحلي فقط؛ النموذج إن وُجد مضمن/محلي بلا أي تنزيل وقت التشغيل.
 * - **حد حجم النموذج** (بند قبول F3 الثاني): أي نموذج يفوق [MAX_MODEL_BYTES]
 *   يُرفض نهائيًا قبل أي تفسير — لا استهلاك ذاكرة مفتوح النهاية.
 * - **أشكال الإدخال/الإخراج معلنة**: نافذة [INPUT_WINDOW] قيمة يومية → أفق
 *   [DEFAULT_HORIZON] خطوة — أي رد بطول غير مطابق يُعد ردًا مشوهًا.
 * - **دلالة ثلاثية** (نمط البوابات نفسه): Accepted / Rejected نهائي بالسبب
 *   / TransientFailure قابل لإعادة المحاولة.
 */
interface ForecastGateway {

    companion object {
        /** طول نافذة الإدخال المعتمدة (قيم يومية صافية) — شكل الإدخال للنموذج */
        const val INPUT_WINDOW = 56

        /** أفق التنبؤ الافتراضي بالخطوات */
        const val DEFAULT_HORIZON = 8

        /** أعلى أفق مقبول — ما فوقه رفض نهائي (لا ذاكرة مفتوحة النهاية) */
        const val MAX_HORIZON = 24

        /** حد حجم النموذج بالبايت — بند قبول F3 الثاني (2 ميجابايت صارمة) */
        const val MAX_MODEL_BYTES = 2_000_000L

        /** مسار النموذج المتوقع داخل الأصول/التخزين المحلي */
        const val MODEL_FILE = "models/forecast_v1.tflite"

        const val REASON_MODEL_MISSING = "MODEL_MISSING"
        const val REASON_MODEL_TOO_LARGE = "MODEL_TOO_LARGE"
        const val REASON_MODEL_CORRUPT = "MODEL_CORRUPT"
        const val REASON_WINDOW_SHORT = "WINDOW_SHORT"
        const val REASON_WINDOW_NOT_FINITE = "WINDOW_NOT_FINITE"
        const val REASON_HORIZON_INVALID = "HORIZON_INVALID"

        const val REASON_ADOPTED_LINE_STATISTICAL = "STATISTICAL_ONLY"
    }

    /** طلب تنبؤ — نافذة تاريخية قيمها يومية + الأفق المطلوب بالخطوات */
    data class ForecastInput(val window: List<Double>, val horizon: Int)

    /** مخرج التنبؤ — قيم الأفق كما هي (قد تكون سالبة: صافي التدفق يسمح) */
    data class ForecastOutput(val horizon: List<Double>)

    sealed class GatewayResult {
        /** نجح التنبؤ — قيم الأفق بطول مطابق للطلب حصرًا */
        data class Accepted(val output: ForecastOutput) : GatewayResult()

        /** رفض نهائي لا يُعاد بمجرده — السبب أحد ثوابت [Companion] حرفيًا */
        data class Rejected(val reason: String) : GatewayResult()

        /** عابر — إعادة المحاولة مشروعة (النموذج مشغول/ذاكرة لحظية) */
        data class TransientFailure(val message: String) : GatewayResult()
    }

    /** نداء التنبؤ المتزامن — محلي حصرًا، بلا أي شبكة أو خلفية أبدًا */
    fun forecast(input: ForecastInput): GatewayResult
}
