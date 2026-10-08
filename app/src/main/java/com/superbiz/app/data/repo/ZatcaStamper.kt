package com.superbiz.app.data.repo

import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.Invoice
import com.superbiz.app.data.db.InvoiceItem
import com.superbiz.app.data.db.ZatcaDocEntity
import com.superbiz.app.domain.LineTaxP41
import com.superbiz.app.domain.algo.ZatcaChain
import com.superbiz.app.domain.algo.ZatcaUbl
import java.util.Locale
import java.util.UUID

/**
 * [Z2-أ V 1.5.0] خاتم هوية ZATCA-2 — يُسند للفاتورة هويتها الرسمية
 * (UUID + ICV + PIH + رمز النوع) ويبني مستند UBL الرسمي ويؤرشفه —
 * كل ذلك **داخل معاملة حفظ الفاتورة نفسها** (نمط P6-M5: قراءة maxIcv
 * وlatestHash والإدراج داخل معاملة الكتابة فلا عدّاد مكرر ولا حلقة مكسورة
 * بين مستندين متزامنين).
 *
 * ─── قرارات موثقة (عقد الختم) ───
 * - **فواتير البيع حصراً**: سلسلة الإصدار تتبع وثائق البائع؛ فواتير الشراء
 *   وثائق واصلة من موردين فلا تُختم ولا تدخل العداد.
 * - **شرط الختم الوحيد**: الرقم الضريبي للبائع غير فارغ (لقطة الإعدادات) —
 *   بلا رقم ضريبي لا هوية ZATCA أصلاً، والفاتورة تبقى كما هي بلا أي مساس.
 * - **رمز النوع**: buyerVat غير فارغ ⇒ «0100000» قياسية B2B، وإلا «0200000»
 *   مبسطة B2C — التقدير الافتراضي الموثق (يُحدَّث بقواعد توجيه لاحقاً إن لزم).
 * - **التعديل لا يعيد الختم**: هوية الفاتورة المختومة (uuid/icv/pih/subtype)
 *   تلصق بها — تعديل المحتوى لا يعيد الترقيم ولا يكسر السلسلة؛ والأرشيف
 *   الأول لا يُستبدل (archiveFirst IGNORE) فتبقى مرجعيات PIH اللاحقة صحيحة
 *   إلى الأبد. الإلغاء (void) يخرج الفاتورة من قائمة الربط (zatcaStatus=0)
 *   ولا يلمس الهوية ولا الأرشيف — تصحيح الامتثال الكامل بمستند دائن موجة قادمة.
 * - **المبالغ ريال Double** عند حدود المولد النقي (Money.fromPiasters من
 *   القروش المخزنة) — قاعدة «القروش Long تخزيناً وتحوّل عرضاً فقط» [P33-P8].
 * - الضريبة المخزنة للفاتورة هي مرجع الاتساق الحرفي للـTaxTotal — فارق
 *   التقريب بين مسار الرأس ومسار السطر يُساوى على الفئة الأكبر (عقد ZatcaUbl).
 * - الحالة بعد الختم: zatcaStatus = 1 «بالقائمة» — الإبلاغ/التخليص عمل
 *   خلفي لاحق عبر قائمة الانتظار (D3: البيع لا ينتظر الشبكة أبداً).
 */
class ZatcaStamper(
    private val db: AppDatabase,
    private val sellerProvider: suspend () -> Seller?,
    private val uuidGen: () -> String = {
        UUID.randomUUID().toString().uppercase(Locale.US)
    },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /** لقطة بيانات البائع الضريبية — من الإعدادات لحظة الإصدار */
    data class Seller(
        val name: String,
        val vatNumber: String,
        val crn: String = "",
        val street: String = "",
        val buildingNumber: String = "",
        val district: String = "",
        val city: String = "",
        val postalCode: String = "",
        val country: String = "SA",
    )

    /** ناتج الختم: نسخة الفاتورة المختومة + الأرشيف الجاهز للإدراج بعد معرفة المعرّف */
    data class Stamped(val invoice: Invoice, val doc: ZatcaDocEntity?)

    /**
     * ختم داخل معاملة الحفظ — يُستدعى حصراً من InvoiceRepo.save داخل
     * withTransaction. يعيد null بلا أي مساس إذا لم تنطبق شروط الختم.
     */
    suspend fun stamp(invoice: Invoice, items: List<InvoiceItem>, prev: Invoice?): Stamped? {
        // 1) فواتير البيع حصراً — الشراء وثيقة واصلة لا تدخل سلسلة الإصدار
        if (!invoice.isSale) return null
        // 2) بيانات البائع شرط الختم الوحيد
        val seller = sellerProvider() ?: return null
        if (seller.vatNumber.isBlank()) return null
        // 3) التعديل: الهوية الملصوقة تحفظ — لا إعادة ختم ولا إعادة ترقيم
        val isRestamp = prev != null && prev.uuid.isNotBlank()
        if (invoice.uuid.isNotBlank() && !isRestamp) return null // مختومة مسبقاً (دفاعي)

        val uuid: String
        val icv: Long
        val pih: String
        if (isRestamp && prev != null) {
            // التعديل: الهوية الملصوقة — قيم prev حرفياً
            uuid = prev.uuid
            icv = prev.icv
            pih = prev.pih
        } else {
            // الإصدار الجديد: الحلقة التالية من السلسلة — القراءة والإسناد داخل
            // معاملة الحفظ نفسها (نمط P6-M5)
            val next = ZatcaChain.nextStamp(
                ZatcaChain.ChainState(
                    lastIcv = db.invoices().maxIcv() ?: 0L,
                    lastHash = db.zatcaDocs().latestHash(),
                )
            )
            uuid = uuidGen()
            icv = next.icv
            pih = next.pih
        }
        val subtype = if (isRestamp && prev != null) prev.zatcaSubtype
        else if (invoice.buyerVat.isNotBlank()) "0100000" else "0200000"

        // 4) بناء مستند UBL الرسمي — حتمي من اللقطة (المحتوى الحالي في مسار التعديل،
        //    والأرشيف الأول لا يُستبدل فلا تتأثر سلسلة الهاش)
        val doc = buildDoc(invoice, items, seller, uuid, icv, pih, subtype)

        val stampedInvoice = invoice.copy(
            uuid = uuid,
            icv = icv,
            pih = pih,
            zatcaSubtype = subtype,
            zatcaStatus = 1,
        )
        return Stamped(stampedInvoice, doc)
    }

    /** بناء مستند الأرشيف — null عند استحالة البناء (بلا بنود مثلاً) */
    private fun buildDoc(
        invoice: Invoice,
        items: List<InvoiceItem>,
        seller: Seller,
        uuid: String,
        icv: Long,
        pih: String,
        subtype: String,
    ): ZatcaDocEntity? {
        if (items.isEmpty()) return null
        val lines = items.map { it ->
            val net = Math.round(it.qty * it.unitPrice) - it.discount // عرف computeTotals
            val tax = LineTaxP41.lineTax(net, it.taxKind, it.taxRate, invoice.taxRate)
            ZatcaUbl.UblLine(
                desc = it.desc,
                qty = it.qty,
                unitPrice = it.unitPrice / 100.0,
                lineDiscount = it.discount / 100.0,
                lineNet = net / 100.0,
                rate = LineTaxP41.effectiveRate(it.taxKind, it.taxRate, invoice.taxRate),
                taxKind = it.taxKind,
                lineTax = tax / 100.0,
            )
        }
        val request = ZatcaUbl.UblRequest(
            number = invoice.number,
            uuid = uuid,
            issueTimeMs = invoice.date,
            subtype = subtype,
            currency = invoice.currency.ifBlank { "SAR" },
            paymentMeansCode = "10", // الافتراض الموثق — اشتقاق رمز الدفع من قنوات السداد لاحقاً
            seller = ZatcaUbl.Party(
                name = seller.name,
                vatNumber = seller.vatNumber,
                crn = seller.crn,
                address = ZatcaUbl.Address(
                    buildingNumber = seller.buildingNumber,
                    street = seller.street,
                    district = seller.district,
                    city = seller.city,
                    postalCode = seller.postalCode,
                    country = seller.country.ifBlank { "SA" },
                ),
            ),
            buyer = if (invoice.buyerVat.isNotBlank() || invoice.buyerName.isNotBlank()) {
                ZatcaUbl.Party(
                    name = invoice.buyerName,
                    vatNumber = invoice.buyerVat,
                    address = null, // العنوان المقنن للمشتري يحتاج حقول v15 — الطرح الصادق
                )
            } else null,
            deliveryDateMs = invoice.deliveryDate,
            icv = icv,
            pih = pih,
            lines = lines,
            totals = ZatcaUbl.Totals(
                subtotal = invoice.subtotal / 100.0,
                discount = invoice.discount / 100.0,
                taxAmount = invoice.taxAmount / 100.0,
                total = invoice.total / 100.0,
                prepaid = invoice.paid / 100.0,
            ),
        )
        val xml = ZatcaUbl.build(request)
        return ZatcaDocEntity(
            invoiceId = 0L, // يُستبدل بمعرّف الفاتورة بعد الإدراج (نمط البنود)
            xml = xml,
            xmlHash = ZatcaChain.documentHash(xml.toByteArray(Charsets.UTF_8)),
            subtype = subtype,
            issuedAt = invoice.date,
        )
    }
}
