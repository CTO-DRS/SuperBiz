package com.superbiz.app.domain.algo

/**
 * — PaymentsE22: النواة المالية لتوسيع المدفوعات (Epic E2.2 — الموجة الثانية من المرحلة 2).
 *
 * عقد نقي بلا حالة شبكية ولا Android: يقبع فوق أي PSP مستقبلي (Moyasar/HyperPay/Tap)
 * دون لحام أحدها في الكود، ويُختبر على JVM قرشاً بقرش في PaymentsE22Test.
 *
 * القوانين الأربعة المقفلة هنا:
 *  1) تقسيم الدفع عند نقاط البيع (نقداً + بطاقة) مطابق للإجمالي هللةً هللة، والبطاقة لا تسالب أبداً.
 *  2) الاسترداد بعقد تعريف (idempotency key): المفتاح نفسه لا يخصم من البوابة مرتين — أبداً.
 *  3) تفويض البطاقة يشترط الاتصال بالسياسة: بلا اتصال يُرفض إقرارياً، وقائمة الانتظار لا تُحصّل تلقائياً.
 *  4) مصفوفة اختيار PSP مُحرَّزة وموثقة كبيانات قابلة للتدقيق (مسودة تُثبَّت بعد حفر الـsandbox).
 *
 * [RED-first]: هيكل هذه الوحدة كُتب أعور أولاً (عقود خاطئة عمداً) وفشلت اختباراتها الأربعة
 * قبل هذا التنفيذ — الدليل النصي في رسالة الـcommit وسجل gradle_red.log.
 */

// ───────────────── 1) تقسيم الدفع (نقداً + بطاقة) ─────────────────

/** حصة كل وسيلة دفع في إجمالي واحد — مجموعهما يجب أن يساوي الإجمالي ضمن نصف هللة */
data class TenderSplit(val cash: Double, val card: Double)

/**
 * تقسيم مبلغ إجمالي بين نقد مقدَّم وبطاقة: النقد لا يتجاوز الإجمالي (البقشش يُعالج
 * خارج هذا العقد عبر changeBreakdown)، والبطاقة تكمل الفرق ولا تكون سالبة أبداً.
 * التقريب الكانوني round2 (BigDecimal HALF_UP) على الشريحتين — لا فلود ولا فقد هللة.
 */
fun splitTender(total: Double, cashGiven: Double): TenderSplit {
    require(total > 0) { "total > 0" }
    require(cashGiven >= 0) { "cashGiven >= 0" }
    val cash = round2(minOf(cashGiven, total))
    val card = round2(total - cash)
    return TenderSplit(cash = cash, card = card)
}

// ───────────────── 2) عقد تعريف الاسترداد ─────────────────

/** نتيجة استرداد من بوابة الدفع — repeated=true تعني «مفتاح مكرر، لم يُخصم شيء جديد» */
data class RefundResult(val refundId: String, val amount: Double, val repeated: Boolean)

/**
 * دفتر تعريف الاسترداد: يضمن أن المفتاح الواحد يستدعي البوابة مرة واحدة بعمر الدفتر،
 * ويعيد النتيجة الأولى نفسها على كل تكرار مع raised repeated=true (دلالة at-most-once
 * التي تشترطها بوابة أي PSP و SLA التسوية). الدفتر نفسه بلا I/O — عند التوصيل بالمستودع
 * يصير جدولاً حقيقياً بمفتاح فريد؛ هنا يُقفل العقد ويُختبر نقياً.
 */
class RefundIdempotencyLedger {

    private val seen = mutableMapOf<String, RefundResult>()

    /**
     * ينفّذ [charge] مرة واحدة للمفتاح [key]، ويعيد النتيجة المخزَّنة على التكرار
     * مع raised repeated=true دون لمس البوابة إطلاقاً.
     */
    fun execute(key: String, amount: Double, charge: (String, Double) -> RefundResult): RefundResult {
        val recorded = seen[key]
        if (recorded != null) return recorded.copy(repeated = true)
        val first = charge(key, amount)
        seen[key] = first
        return first
    }
}

// ───────────────── 3) سياسة اتصال البطاقة ─────────────────

enum class ConnectivityState { ONLINE, OFFLINE }

/** قرار تفويض البطاقة: إما إقرار متصل أو رفض صريح بسبب انقطاع الاتصال */
sealed interface AuthDecision {
    object Approve : AuthDecision
    object DeclineOffline : AuthDecision
}

/**
 * سياسة الإقرار: البطاقة (حاضرة أو غير حاضرة) تشترط اتصالاً حياً — بلا اتصال يُرفض
 * القرار إقرارياً بلا أي تفويض صامت ولا تخزين لاحق يُحصَّل تلقائياً.
 */
fun cardAuthPolicy(online: Boolean): AuthDecision =
    if (online) AuthDecision.Approve else AuthDecision.DeclineOffline

/** ثابت السياسة: قائمة انتظار المدفوعات بلا اتصال لا تُحصّل بطاقة تلقائياً — إطلاقاً */
const val OFFLINE_QUEUE_AUTO_CHARGE = false

// ───────────────── 4) مصفوفة اختيار PSP (مسودة مُحرَّزة) ─────────────────

/** المرشحون الثلاثة في سوق السعودية حسب مصفوفة الخارطة (E2.2) */
enum class PspCandidate { MOYASAR, HYPERPAY, TAP }

/**
 * درجة مرشح على الأبعاد الستة القابلة للتدقيق (كل بُعد 1..5):
 * تغطية مدى، Apple Pay، STC Pay، جودة الـSDK، شروط التسوية، وأمانة الـsandbox.
 * [مسودة]: الأرقام الأولية للهيكلة والمقارنة النسبية فقط، وتُثبَّت نهائياً بعد
 * حفر الـsandbox على المرشح الأول — معيار قبول الملحم صريح بذلك.
 */
data class PspScore(
    val psp: PspCandidate,
    val madaCoverage: Int,
    val applePay: Int,
    val stcPay: Int,
    val sdkQuality: Int,
    val settlementTerms: Int,
    val sandboxFidelity: Int,
) {
    val total: Int
        get() = madaCoverage + applePay + stcPay + sdkQuality + settlementTerms + sandboxFidelity
}

/**
 * المصفوفة مرتبة تنازلياً بالدرجة الكلية — القرار يبقى قابلاً للتدقيق لا وجدانياً.
 * مسودة الدرجات: Moyasar 25 (مدى/Apple Pay أقوى تغطية)، Tap 25 (STC Pay أوسع)،
 * HyperPay 20 (مؤسسي بتسوية أقل مرونة) — التعادل الأول يُحسم بحفر الـsandbox.
 */
fun pspSelectionMatrix(): List<PspScore> = listOf(
    PspScore(
        psp = PspCandidate.MOYASAR,
        madaCoverage = 5, applePay = 5, stcPay = 3,
        sdkQuality = 4, settlementTerms = 4, sandboxFidelity = 4,
    ),
    PspScore(
        psp = PspCandidate.TAP,
        madaCoverage = 4, applePay = 4, stcPay = 5,
        sdkQuality = 4, settlementTerms = 4, sandboxFidelity = 4,
    ),
    PspScore(
        psp = PspCandidate.HYPERPAY,
        madaCoverage = 4, applePay = 4, stcPay = 3,
        sdkQuality = 3, settlementTerms = 3, sandboxFidelity = 3,
    ),
)
