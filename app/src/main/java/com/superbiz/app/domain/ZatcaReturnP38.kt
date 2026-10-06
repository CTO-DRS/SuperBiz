package com.superbiz.app.domain

/**
 * [P38-Z3] تقرير الضريبة الدوري — أساس الإقرار الضريبي (جولة ZATCA التشغيلية).
 *
 * يلخّص الفترة الزمنية إلى الأعمدة التي يطلبها الإقرار المبسّط لضريبة القيمة
 * المضافة: مبيعات خاضعة (بالتوفيق حسب النسبة)، مبيعات صفرية، مبيعات معفاة،
 * ضريبة المخرجات المحصّلة، مشتريات خاضعة وضريبة المدخلات القابلة للخصم،
 * ثم الصافي المستحق (أو القابل للاسترداد حين تكون المدخلات أكبر).
 *
 * ─── مصادر الحقيقة والقيود الموثقة ───
 * - كل الحقول تُبنى من **المحجوز فعلاً في الفواتير** (subtotal/discount/taxRate/
 * taxAmount بقروش Long) — لا إعادة اشتقاق (نفس عقد R14-F19 الذي أصلح انحراف
 * دفتر الضريبة في vatByRate).
 * - الفواتير الملغاة (status = 3) مستبعدة عند القارئ (نفس عرف saleInvoicesSince).
 * - [P41-L2] التمييز الذي وثّقت P38 تأجيله صار واقعاً — SaleRow اكتسبت
 * kind (0 قياسية/1 صفرية/2 معفاة من LineTaxP41) فصارت الخانتان منفصلتين
 * الصفوف القديمة (rate = 0.0 بلا فئة) تبقى في خانة **الصفرية** كما كانت
 * موحّدة منذ P38 (السلوك التاريخي محفوظ)، والمعفاة لا تُملأ إلا بفئة صريحة
 * من سطر v11 — تقارير P38 القائمة تعطي نفس أرقامها حرفياً.
 * - الصافي قابل للسالب (مدخلات أكبر من مخرجات = استرداد مستحق) — سلوك شرعي
 * يُعرض كما هو ولا يُقص إلى صفر.
 * - المبالغ Long قروش — كل الجمع مساواة صحيحة تامة بلا أي تقريب.
 *
 * الملف نقي بلا أي اعتماد Android — JUnit مجرد (نمط DebtPlanP4/ReportsP4).
*/
object ZatcaReturnP38 {

    /** سطر بيع — net صافي بعد الخصم، vat الضريبة المحجوزة، kind فئة v11 (افتراضها 0 قياسية لدلالة الصفوف التاريخية) */
    data class SaleRow(val rate: Double, val net: Long, val vat: Long, val kind: Int = 0)

    /** سطر شراء خاضع — net صافي بعد الخصم، vat ضريبة المدخلات المحجوزة */
    data class PurchRow(val net: Long, val vat: Long)

    /** صف التفصيل حسب النسبة للعرض — rate بعرف الكيان نفسه (15.0 = 15%) ليقرأه */
    data class RateRow(val rate: Double, val net: Long, val vat: Long)

    /**
     * التقرير الكامل للفترة — كل المبالغ قروش Long:
     * @property salesNet        إجمالي صافي المبيعات (خاضعة + صفرية + معفاة)
     * @property salesZeroNet    صافي المبيعات الصفرية (والصفوف التاريخية الموحدة منذ P38)
     * @property salesExemptNet  صافي المبيعات المعفاة — [P41-L2] خانة مستقلة منذ v11
     * @property outputVat       ضريبة المخرجات المحصّلة على المبيعات الخاضعة
     * @property purchasesNet    صافي المشتريات الخاضعة
     * @property inputVat        ضريبة المدخلات القابلة للخصم
     * @property netVat          الصافي المستحق = outputVat − inputVat (سالب = استرداد)
     * @property byRate          تفصيل المبيعات الخاضعة حسب النسبة (تنازلياً)
     */
    data class Report(
        val salesNet: Long,
        val salesZeroNet: Long,
        val salesExemptNet: Long,
        val outputVat: Long,
        val purchasesNet: Long,
        val inputVat: Long,
        val netVat: Long,
        val byRate: List<RateRow>,
    )

    /**
     * البناء الحتمي من صفوف اللقطة — لا أي فحص حالة/تاريخ هنا؛ القارئ
     * (ReportsRepo) يمارس فلاتر النافذة والإلغاء بعقده الموثق قبل التمرير.
     * التفريق: kind=1 صفرية، kind=2 معفاة، والقياسية rate=0.0 (الصفوف التاريخية)
     * تبقى في خانة الصفرية كما كانت موحّدة منذ P38.
     */
    fun build(sales: List<SaleRow>, purchases: List<PurchRow>): Report {
        val zeroNet = sales
            .filter { it.kind == LineTaxP41.KIND_ZERO || (it.kind == LineTaxP41.KIND_STANDARD && it.rate == 0.0) }
            .sumOf { it.net }
        val exemptNet = sales
            .filter { it.kind == LineTaxP41.KIND_EXEMPT }
            .sumOf { it.net }
        val taxable = sales.filter { it.kind == LineTaxP41.KIND_STANDARD && it.rate != 0.0 }
        val byRate = taxable
            .groupBy { it.rate }
            .map { (rate, rows) ->
                RateRow(rate, rows.sumOf { it.net }, rows.sumOf { it.vat })
            }
            .sortedByDescending { it.rate }
        val salesNet = sales.sumOf { it.net }
        val outputVat = taxable.sumOf { it.vat }
        val purchasesNet = purchases.sumOf { it.net }
        val inputVat = purchases.sumOf { it.vat }
        return Report(
            salesNet = salesNet,
            salesZeroNet = zeroNet,
            salesExemptNet = exemptNet,
            outputVat = outputVat,
            purchasesNet = purchasesNet,
            inputVat = inputVat,
            netVat = outputVat - inputVat,
            byRate = byRate,
        )
    }
}
