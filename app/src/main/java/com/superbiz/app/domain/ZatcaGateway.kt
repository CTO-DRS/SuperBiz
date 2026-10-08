package com.superbiz.app.domain

/**
 * [Z2-أ V 1.5.0] الواجهة النقية الوحيدة التي تعبر حدود ADR-001 نحو طبقة
 * الشبكة المعزولة — بلا أي نوع HTTP أو كائن منفّذ شبكي: هنا عقد النتيجة فقط.
 *
 * (D1 من ADR-001): كل كود الشبكة يعيش في حزمة `network` المكونة الواحدة،
 * ولا يلمسها أي مكان آخر في التطبيق إلا عبر هذه الواجهة المعرفة في `domain/`.
 * الفاحص الآلي في CI يمنع أي استيراد لـ`com.superbiz.app.network` خارج حزمته
 * وسلك التوصيل المصرَّح به.
 *
 * ─── الواجهتان الرسميتان لمنصة «فاتورة» ───
 * - **Clearance**: للفواتير الضريبية القياسية (subtype 0100000) — تُرسل قبل
 *   مشاركتها وتُعيد نسخة مُخلَّصَة موقعة من الهيئة يجب أرشفتها.
 * - **Reporting**: للفواتير المبسطة (subtype 0200000) — تُبلَّغ خلال نافذتها
 *   القانونية (24 ساعة) دون انتظار موافقة.
 *
 * ─── دلالة النتائج الثلاث (عقد قائمة الانتظار) ───
 * - [GatewayResult.Accepted]: نجح — مع النسخة المخلَّصة إن كانت تخليصاً
 *   والتحذيرات المعيارية إن وُجدت. تُصعَّد الحالة إلى «مُبلَّغَة/مُخلَّصَة» وتُغلق.
 * - [GatewayResult.Rejected]: رفض معياري من الهيئة (أخطاء تحقق/بنية) —
 *   إعادة المحاولة الحرفية بلا إصلاح عبثية؛ تُعلَّم الوثيقة «مرفوضة» بالسبب
 *   وتُعرض في لوحة ZATCA. الإصلاح بمستند تصحيحي (دائنة/مدينة) لاحقاً.
 * - [GatewayResult.TransientFailure]: عطل شبكي/خادم/معدل — يُعاد بمجدول
 *   التراجع الأسّي عبر WorkManager بلا فقد ولا تكرار (D3: البيع لا ينتظر
 *   الشبكة، والقائمة تحفظ الترتيب).
 *
 * النقية كاملة: الواجهة والدوال المعلقة تعمل على JVM بلا أندرويد — القائمة
 * كاملة تُختبر بـ Fake gateway (عقد D6).
 */
interface ZatcaGateway {

    /** طلب تخليص فاتورة قياسية (0100000) — كل الحقول كما تطلبها الواجهة الرسمية */
    data class ClearanceRequest(
        val invoiceXml: String,   // مستند UBL 2.1 كامل (Base64 عند النقل — طبقة الشبكة)
        val uuid: String,         // cbc:UUID للفاتورة
        val invoiceHash: String,  // Base64(SHA256(بايتات XML)) — وسم 6
        val icv: Long,            // عدّاد الإصدار
        val pih: String,          // بصمة الفاتورة السابقة
    )

    /** طلب إبلاغ عن فاتورة مبسطة (0200000) — نفس الحقول (النقل يوحّد الشكل) */
    data class ReportingRequest(
        val invoiceXml: String,
        val uuid: String,
        val invoiceHash: String,
        val icv: Long,
        val pih: String,
    )

    /** خطأ معياري من الهيئة — code/category كما تعيدها الواجهة الرسمية حرفياً */
    data class GatewayError(
        val code: String,      // مثل «2015» هاش غير مطابق
        val category: String,  // مثل «VALIDATION»
        val message: String,   // نص الخطأ (قد يكون عربياً — يُعرض كما هو)
        val status: Int,       // HTTP status المرافق إن وُجد
    )

    sealed class GatewayResult {
        data class Accepted(
            val clearedXml: String? = null,      // النسخة المخلَّصة (Clearance فقط)
            val warnings: List<String> = emptyList(),
        ) : GatewayResult()

        data class Rejected(val errors: List<GatewayError>) : GatewayResult()

        data class TransientFailure(
            val retryAfterSeconds: Long? = null, // تلميح الخادم إن أعطاه
            val message: String = "",
        ) : GatewayResult()
    }

    /** تخليص فاتورة قياسية — يستدعى من قائمة الانتظار حصراً (لا مسار بيع يلمسه) */
    suspend fun clear(request: ClearanceRequest): GatewayResult

    /** إبلاغ عن فاتورة مبسطة — يستدعى من قائمة الانتظار حصراً */
    suspend fun report(request: ReportingRequest): GatewayResult
}
