package com.superbiz.app.domain.algo

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import com.superbiz.app.domain.LineTaxP41

/**
 * [Z2-ب V 1.5.0] مولّد مستندات UBL 2.1 الرسمية بتنسيق ZATCA — بديل الرسمي
 * للمولّد المبسّط [ZatcaInvoiceXml] (الذي يبقى لتصدير القراءة المحاسبية)،
 * نقي تماماً بلا أي اعتماد Android (نمط ZatcaStamp)، وحتمي بايتاً ببايت.
 *
 * ─── البنية (وفق نماذج منصة «فاتورة» للمرحلة-2) ───
 * جذر المستند حسب النوع: Invoice (388) / CreditNote (381) / DebitNote (383)
 * مع name= رمز النوع الفرعي على InvoiceTypeCode («0100000» قياسية / «0200000»
 * مبسطة). المسارات بالترتيب الحرفي الثابت: ProfileID، ID، UUID، IssueDate،
 * IssueTime، InvoiceTypeCode، DocumentCurrencyCode، TaxCurrencyCode،
 * BillingReference (للدائنة/المدينة)، AdditionalDocumentReference ICV ثم PIH،
 * AccountingSupplierParty، AccountingCustomerParty (اختياري — المبسطة بلا
 * مشترٍ تُصدر دون العنصر كلياً)، Delivery (اختياري)، PaymentMeans، TaxTotal
 * بفئات فرعية لكل فئة S|Z|E، LegalMonetaryTotal، ثم أسطر الأصناف.
 *
 * ─── قرارات حتمية مُثبَّتة (تنعكس في الملفات الذهبية) ───
 * - تعريف XML ثم سطر واحد جديد، وجذر المستند سطر واحد بلا أي فراغ بين
 *   العناصر — المطابقة بايتاً ببايت هي ضمانة الجودة (G3).
 * - المبالغ ريال Double بخانتين عشريتين (عرف money2 في ZatcaQr) — المستدعي
 *   يحوّل القروش Long عبر Money.fromPiasters فلا يحفظ أي تحويل هنا.
 * - الكميات بصيغة [ZatcaInvoiceXml.qty3] الحتمية، وunitCode ثابت «PCE»
 *   (المشروع بلا نموذج وحدات — قرار موثق).
 * - الأزمنة UTC بصيغة المرحلة-1 نفسها (تطابق وسم 3) — التاريخ yyyy-MM-dd
 *   والوقت HH:mm:ss.
 * - **الخصم سطري حصراً** (عرف المشروع: discount = مجموع خصوم البنود) — كل سطر
 *   يعرض AllowanceCharge الخاص به، ولا AllowanceCharge على مستوى المستند.
 * - **فئات الضريبة**: كل سطر بفئته S|Z|E (LineTaxP41.categoryId) ونسبته،
 *   وTaxSubtotal لكل فئة موجودة؛ مجموعها الضريبي يُساوى حرفياً مع الضريبة
 *   المخزنة للفاتورة بتعيين فارق التقريب (إذا وجد) على الفئة الأكبر أساساً
 *   بترتيب S ثم Z ثم E — فيتطابق ΣTaxSubtotal.TaxAmount مع cbc:TaxAmount
 *   وΣTaxableAmount مع TaxExclusiveAmount بالميل تماماً (عقد الدقة المالية
 *   القروشي: الفارق هو هللات التقريب لا خطأ حساب).
 * - العناصر الفارغة تُحذف كلياً لا تُكتب فارغة (عنوان مفقود/مشترٍ غائب).
 * - التهريب عبر ZatcaStamp.xmlEscape (الكيانات الخمسة — «&» أولاً).
 *
 * التحقق البنيوي الموسع (XSD الرسمي) يعتمد توفر ملفات الهيئة ويُضاف في
 * دفعة Z2-ج — الحتمية والملفات الذهبية هما الضمانة هنا (قرار T29 §3).
 */
object ZatcaUbl {

    // ───────── نماذج المدخلات (كلها قيم عرض بالريال Double) ─────────

    /** فئة المستند — جذر المستند ورمزه ورقم سطره */
    enum class DocKind(val root: String, val typeCode: Int, val lineElement: String, val qtyElement: String) {
        INVOICE("Invoice", 388, "InvoiceLine", "InvoicedQuantity"),
        CREDIT_NOTE("CreditNote", 381, "CreditNoteLine", "CreditedQuantity"),
        DEBIT_NOTE("DebitNote", 383, "DebitNoteLine", "DebitedQuantity"),
    }

    data class Address(
        val buildingNumber: String = "",
        val street: String = "",
        val district: String = "",
        val city: String = "",
        val postalCode: String = "",
        val country: String = "SA",
    )

    data class Party(
        val name: String,
        val vatNumber: String = "",
        val crn: String = "",          // السجل التجاري — اختياري
        val address: Address? = null,
    )

    /** سطر صنف — lineNet بعد خصم السطر، base = lineNet + lineDiscount بالضبط */
    data class UblLine(
        val desc: String,
        val qty: Double,
        val unitPrice: Double,
        val lineDiscount: Double = 0.0,
        val lineNet: Double,           // ريال Double من القروش المخزنة (لا يُحسب هنا)
        val rate: Double,              // 15.0 = 15%
        val taxKind: Int = 0,          // LineTaxP41: 0=S / 1=Z / 2=E
        val lineTax: Double,           // round2(lineNet × rate/100) — حتمي من المستدعي
    )

    /** إجماليات الفاتورة المخزنة (ريال Double من القروش) — مرجع اتساق المجاميع */
    data class Totals(
        val subtotal: Double,     // Σ lineNet = Σ base − Σ discounts
        val discount: Double,     // Σ خصوم السطور
        val taxAmount: Double,    // الضريبة المخزنة (مرجع المساواة الحرفية)
        val total: Double,        // net + tax
        val prepaid: Double = 0.0, // المدفوع سلفاً (التحصيل الفوري)
    )

    /** الطلب الكامل — حقل واحد مستند */
    data class UblRequest(
        val kind: DocKind = DocKind.INVOICE,
        val number: String,             // cbc:ID
        val uuid: String,               // cbc:UUID
        val issueTimeMs: Long,
        val subtype: String,            // «0100000» / «0200000»
        val profile: String = "reporting:1.0",
        val currency: String = "SAR",
        val paymentMeansCode: String = "10", // 10 نقداً / 30 تحويل / 48 بطاقة / 42 حساب
        val seller: Party,
        val buyer: Party? = null,
        val deliveryDateMs: Long = 0,   // 0 = بلا عنصر Delivery
        val originalInvoiceNumber: String = "", // BillingReference للدائنة/المدينة
        val icv: Long,
        val pih: String,
        val lines: List<UblLine>,
        val totals: Totals,
    )

    // ───────── البناء ─────────

    /** بناء المستند كاملاً — حتمي بايتاً ببايت لنفس الطلب (عقد الرأس) */
    fun build(r: UblRequest): String = buildString(4096) {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<").append(r.kind.root).append(' ')
            .append("xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:")
            .append(r.kind.root).append("-2\" ")
            .append("xmlns:cac=\"urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2\" ")
            .append("xmlns:cbc=\"urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2\"")
            .append('>')
        cbc(this, "ProfileID", r.profile)
        cbc(this, "ID", r.number, escape = true)
        cbc(this, "UUID", r.uuid, escape = true)
        cbc(this, "IssueDate", isoDate(r.issueTimeMs))
        cbc(this, "IssueTime", isoTime(r.issueTimeMs))
        append("<cbc:InvoiceTypeCode name=\"").append(esc(r.subtype)).append("\">")
            .append(r.kind.typeCode).append("</cbc:InvoiceTypeCode>")
        cbc(this, "DocumentCurrencyCode", r.currency)
        cbc(this, "TaxCurrencyCode", r.currency)
        if (r.originalInvoiceNumber.isNotBlank()) {
            append("<cac:BillingReference><cac:InvoiceDocumentReference>")
            cbc(this, "ID", r.originalInvoiceNumber, escape = true)
            append("</cac:InvoiceDocumentReference></cac:BillingReference>")
        }
        // ICV — قيمة العداد داخل عنصر UUID للمرجع الإضافي (مواصفة الهيئة)
        append("<cac:AdditionalDocumentReference>")
        cbc(this, "ID", "ICV")
        cbc(this, "UUID", r.icv.toString())
        append("</cac:AdditionalDocumentReference>")
        // PIH — بصمة الفاتورة السابقة
        append("<cac:AdditionalDocumentReference>")
        cbc(this, "ID", "PIH")
        append("<cbc:EmbeddedDocumentBinaryObject mimeCode=\"text/plain\">")
            .append(esc(r.pih)).append("</cbc:EmbeddedDocumentBinaryObject>")
        append("</cac:AdditionalDocumentReference>")
        // البائع — إلزامي دائماً
        append("<cac:AccountingSupplierParty><cac:Party>")
        append(partyXml(r.seller, isSeller = true))
        append("</cac:Party></cac:AccountingSupplierParty>")
        // المشتري — اختياري (المبسطة بلا مشترٍ تُحذف كلياً)
        if (r.buyer != null) {
            append("<cac:AccountingCustomerParty><cac:Party>")
            append(partyXml(r.buyer, isSeller = false))
            append("</cac:Party></cac:AccountingCustomerParty>")
        }
        if (r.deliveryDateMs > 0L) {
            append("<cac:Delivery>")
            cbc(this, "ActualDeliveryDate", isoDate(r.deliveryDateMs))
            append("</cac:Delivery>")
        }
        append("<cac:PaymentMeans>")
        cbc(this, "PaymentMeansCode", r.paymentMeansCode)
        append("</cac:PaymentMeans>")
        // الضريبة: الإجمالي + فئة فرعية لكل فئة — مساواة حرفية مع المخزّن
        val subtotals = taxSubtotals(r)
        append("<cac:TaxTotal>")
        append("<cbc:TaxAmount currencyID=\"").append(esc(r.currency)).append("\">")
            .append(money2(r.totals.taxAmount)).append("</cbc:TaxAmount>")
        for (st in subtotals) {
            append("<cac:TaxSubtotal>")
            append("<cbc:TaxableAmount currencyID=\"").append(esc(r.currency)).append("\">")
                .append(money2(st.taxable)).append("</cbc:TaxableAmount>")
            append("<cbc:TaxAmount currencyID=\"").append(esc(r.currency)).append("\">")
                .append(money2(st.tax)).append("</cbc:TaxAmount>")
            append("<cac:TaxCategory>")
            cbc(this, "ID", st.category)
            append("<cbc:Percent>").append(money2(st.rate)).append("</cbc:Percent>")
            append("<cac:TaxScheme>")
            cbc(this, "ID", "VAT")
            append("</cac:TaxScheme>")
            append("</cac:TaxCategory>")
            append("</cac:TaxSubtotal>")
        }
        append("</cac:TaxTotal>")
        // المجاميع القانونية
        append("<cac:LegalMonetaryTotal>")
        amount(this, r.currency, "LineExtensionAmount", r.totals.subtotal)
        amount(this, r.currency, "TaxExclusiveAmount", r.totals.subtotal)
        amount(this, r.currency, "TaxInclusiveAmount", r.totals.total)
        if (r.totals.discount > 0.0) {
            amount(this, r.currency, "AllowanceTotalAmount", r.totals.discount)
        }
        amount(this, r.currency, "PrepaidAmount", r.totals.prepaid)
        amount(this, r.currency, "PayableAmount", r.totals.total)
        append("</cac:LegalMonetaryTotal>")
        // أسطر الأصناف
        r.lines.forEachIndexed { i, l ->
            val lineTax = money2(l.lineTax)
            append("<cac:").append(r.kind.lineElement).append('>')
            cbc(this, "ID", (i + 1).toString())
            append("<cbc:").append(r.kind.qtyElement)
                .append(" unitCode=\"PCE\">").append(ZatcaInvoiceXml.qty3(l.qty))
                .append("</cbc:").append(r.kind.qtyElement).append('>')
            if (l.lineDiscount > 0.0) {
                append("<cac:AllowanceCharge>")
                cbc(this, "ChargeIndicator", "false")
                amount(this, r.currency, "Amount", l.lineDiscount)
                append("</cac:AllowanceCharge>")
            }
            amount(this, r.currency, "LineExtensionAmount", l.lineNet)
            append("<cac:TaxTotal>")
            append("<cbc:TaxAmount currencyID=\"").append(esc(r.currency)).append("\">")
                .append(lineTax).append("</cbc:TaxAmount>")
            append("<cbc:RoundingAmount currencyID=\"").append(esc(r.currency)).append("\">")
                .append(money2(l.lineNet + l.lineTax)).append("</cbc:RoundingAmount>")
            append("</cac:TaxTotal>")
            append("<cac:Item>")
            cbc(this, "Name", l.desc, escape = true)
            append("<cac:ClassifiedTaxCategory>")
            cbc(this, "ID", LineTaxP41.categoryId(l.taxKind))
            append("<cbc:Percent>").append(money2(l.rate)).append("</cbc:Percent>")
            append("<cac:TaxScheme>")
            cbc(this, "ID", "VAT")
            append("</cac:TaxScheme>")
            append("</cac:ClassifiedTaxCategory>")
            append("</cac:Item>")
            append("<cac:Price>")
            append("<cbc:PriceAmount currencyID=\"").append(esc(r.currency)).append("\">")
                .append(money2(l.unitPrice)).append("</cbc:PriceAmount>")
            append("</cac:Price>")
            append("</cac:").append(r.kind.lineElement).append('>')
        }
        append("</").append(r.kind.root).append('>')
    }

    /** بايتات المستند — مدخل التهشيم لوسم 6 وPIH التالي (عقد G3: الهاش على الناتج نفسه) */
    fun buildBytes(r: UblRequest): ByteArray = build(r).toByteArray(Charsets.UTF_8)

    // ───────── الفئات الفرعية للضريبة ─────────

    data class TaxSubtotalView(
        val category: String,
        val rate: Double,
        val taxable: Double,
        val tax: Double,
    )

    /**
     * تجميع الأسطر حسب الفئة ثم ضبط الاتساق الحرفي مع المخزّن:
     * الفارق (المخزن − المجموع المحسوب) يُعيَّن على الفئة الأكبر أساساً
     * (المساواة ⇒ الأسبق: S ثم Z ثم E) — فيبقى المجموع الكلي مطابقاً
     * للضريبة المخزنة بالميل (هللات تقريب لا أخطاء حساب — قرار الرأس).
     */
    internal fun taxSubtotals(r: UblRequest): List<TaxSubtotalView> {
        if (r.lines.isEmpty()) return emptyList()
        // ترتيب الفئات ثابت: S ثم Z ثم E — حتمي مهما كان ترتيب الأسطر
        val groups = LinkedHashMap<Int, MutableList<UblLine>>()
        for (l in r.lines.sortedBy { LineTaxP41.order(it.taxKind) }) {
            groups.getOrPut(l.taxKind) { ArrayList() }.add(l)
        }
        val views = ArrayList<TaxSubtotalView>()
        for ((kind, lines) in groups) {
            views.add(
                TaxSubtotalView(
                    category = LineTaxP41.categoryId(kind),
                    rate = lines.first().rate,
                    taxable = lines.sumOf { it.lineNet },
                    tax = lines.sumOf { it.lineTax },
                )
            )
        }
        // الفارق الرأسّي: المخزن − المجموع المحسوب (هللات تقريب) — يذهب للأكبر
        val drift = Math.round(r.totals.taxAmount * 100.0) -
            Math.round(views.sumOf { it.tax } * 100.0)
        if (drift != 0L && views.isNotEmpty()) {
            var idx = 0
            var maxTaxable = Double.NEGATIVE_INFINITY
            views.forEachIndexed { i, v -> if (v.taxable > maxTaxable) { maxTaxable = v.taxable; idx = i } }
            views[idx] = views[idx].copy(tax = views[idx].tax + drift / 100.0)
        }
        return views
    }

    // ───────── التحقق من اكتمال المتطلبات (يستهلكه الختم والواجهة) ─────────

    /**
     * الحد الأدنى للامتثال الشكلي قبل التوقيع: القياسية (0100000) تشترط مشترياً
     * برقم ضريبي وعنواناً مقنناً للبائع والمشتري، والمبسطة (0200000) تشترط
     * عنوان البائع — القائمة الناقصة تعيد أسماء الحقول الناقصة (فارغة = سليم).
     */
    fun validate(r: UblRequest): List<String> {
        val missing = ArrayList<String>()
        if (r.uuid.isBlank()) missing.add("uuid")
        if (r.seller.vatNumber.isBlank()) missing.add("seller.vat")
        val sAddr = r.seller.address
        if (sAddr == null || sAddr.buildingNumber.isBlank() || sAddr.street.isBlank() ||
            sAddr.city.isBlank() || sAddr.district.isBlank()
        ) missing.add("seller.address")
        if (r.subtype == "0100000") {
            val b = r.buyer
            if (b == null || b.vatNumber.isBlank()) missing.add("buyer.vat")
        }
        if (r.icv <= 0L) missing.add("icv")
        if (r.pih.isBlank()) missing.add("pih")
        return missing
    }

    // ───────── مساعدات البناء ─────────

    /** بلوك طرف (بائع/مشترٍ) — Identification للرقم الضريبي ثم العنوان ثم السجلات */
    private fun partyXml(p: Party, isSeller: Boolean): String = buildString(512) {
        if (p.vatNumber.isNotBlank()) {
            append("<cac:PartyIdentification>")
            append("<cbc:ID schemeID=\"TIN\">").append(esc(p.vatNumber)).append("</cbc:ID>")
            append("</cac:PartyIdentification>")
        }
        if (p.address != null) {
            val a = p.address
            append("<cac:PostalAddress>")
            if (a.buildingNumber.isNotBlank()) cbc(this, "BuildingNumber", a.buildingNumber, escape = true)
            if (a.street.isNotBlank()) cbc(this, "StreetName", a.street, escape = true)
            if (a.district.isNotBlank()) cbc(this, "CitySubdivisionName", a.district, escape = true)
            if (a.city.isNotBlank()) cbc(this, "CityName", a.city, escape = true)
            if (a.postalCode.isNotBlank()) cbc(this, "PostalZone", a.postalCode, escape = true)
            append("<cac:Country>")
            cbc(this, "IdentificationCode", a.country)
            append("</cac:Country>")
            append("</cac:PostalAddress>")
        }
        if (p.vatNumber.isNotBlank()) {
            append("<cac:PartyTaxScheme>")
            append("<cbc:CompanyID>").append(esc(p.vatNumber)).append("</cbc:CompanyID>")
            append("<cac:TaxScheme>")
            cbc(this, "ID", "VAT")
            append("</cac:TaxScheme>")
            append("</cac:PartyTaxScheme>")
        }
        if (p.name.isNotBlank()) {
            append("<cac:PartyLegalEntity>")
            cbc(this, "RegistrationName", p.name, escape = true)
            append("</cac:PartyLegalEntity>")
        }
        if (!isSeller && p.crn.isNotBlank()) {
            append("<cac:PartyLegalEntity>")
            append("<cbc:CompanyID schemeID=\"CRN\">").append(esc(p.crn)).append("</cbc:CompanyID>")
            append("</cac:PartyLegalEntity>")
        }
    }

    private fun amount(sb: StringBuilder, currency: String, element: String, value: Double) {
        sb.append("<cbc:").append(element).append(" currencyID=\"").append(esc(currency))
            .append("\">").append(money2(value)).append("</cbc:").append(element).append('>')
    }

    private fun cbc(sb: StringBuilder, name: String, value: String, escape: Boolean = false) {
        sb.append("<cbc:").append(name).append('>')
        sb.append(if (escape) esc(value) else value)
        sb.append("</cbc:").append(name).append('>')
    }

    /** تهريب XML للكيانات الخمسة — منفّذ ZatcaStamp نفسه (مصدر حقيقة واحد) */
    private fun esc(s: String): String = ZatcaStamp.xmlEscape(s)

    private fun money2(v: Double): String = String.format(Locale.US, "%.2f", v)

    private fun isoDate(ms: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(ms))
    }

    private fun isoTime(ms: Long): String {
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(ms))
    }
}
