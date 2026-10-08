package com.superbiz.app

import com.superbiz.app.domain.algo.ZatcaUbl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات [Z2-ب V 1.5.0] — مولّد UBL 2.1 الرسمي بتنسيق ZATCA:
 * ستة مستندات ذهبية تُقارن **بايتاً ببايت** (عقد G1/G3) — الملفات الذهبية
 * في test/resources/zatca/ مكتوبة يدوياً كمصدر حقيقة مستقل عن المولّد،
 * وأي تعديل بالبايتات يمس الاختبار ويتطلب قراراً موثقاً (تطور المواصفة).
 *
 * المدخلات ثابتة تماماً (طوابع زمنية/UUID/هاشات حرفية) فلا فرضية زمنية —
 * والمقارنة على بايتات UTF-8 لا النص.
 */
class ZatcaUblGoldenTest {

    /** 2026-10-08T12:30:45Z — الطابع الحرفي لكل الوثائق الذهبية */
    private val issueMs = 1_791_462_645_000L
    private val deliveryMs = 1_791_158_400_000L // 2026-10-05

    private val seller = ZatcaUbl.Party(
        name = "متجر الأمل",
        vatNumber = "310122393500003",
        address = ZatcaUbl.Address(
            buildingNumber = "1234", street = "شارع الملك فهد",
            district = "حي العليا", city = "الرياض", postalCode = "12345",
        ),
    )

    private val buyer = ZatcaUbl.Party(
        name = "شركة المؤسسة للتجارة",
        vatNumber = "300055556600003",
        crn = "1010555566",
        address = ZatcaUbl.Address(
            buildingNumber = "8888", street = "طريق الأمير سلطان",
            district = "حي الملز", city = "الرياض",
        ),
    )

    private val buyerMinimal = ZatcaUbl.Party(
        name = "شركة المؤسسة للتجارة", vatNumber = "300055556600003",
    )

    private val firstPih = "X+zrZv/IbzjZUnhsbWlsecLbwjndTpG0ZynXOif7V+k=" // Base64(SHA256("0"))
    private val nextPih = "AAABBBCCC111222333444555666777888999000aaa="

    private fun readGolden(name: String): ByteArray {
        val stream = javaClass.classLoader!!.getResourceAsStream("zatca/$name")
            ?: throw AssertionError("golden not found: zatca/$name")
        var bytes = stream.readBytes()
        // ملفات التأليف تنتهي بسطر جديد واحد — العقد الحرفي هو بايتات XML دونها
        if (bytes.isNotEmpty() && bytes[bytes.size - 1] == '\n'.code.toByte()) {
            bytes = bytes.copyOfRange(0, bytes.size - 1)
        }
        return bytes
    }

    private fun assertGolden(name: String, xml: String) {
        val expected = readGolden(name)
        val actual = xml.toByteArray(Charsets.UTF_8)
        // أول فرق يُعرض بموضعه لفشل مفيد (لا سلسلة كاملة 5KB)
        if (!expected.contentEquals(actual)) {
            val n = minOf(expected.size, actual.size)
            var i = 0
            while (i < n && expected[i] == actual[i]) i++
            throw AssertionError(
                "golden mismatch: zatca/$name\n" +
                    "expected ${expected.size}B, actual ${actual.size}B, first diff at byte $i\n" +
                    "expected: ${expected.copyOfRange(i, minOf(i + 80, expected.size)).toString(Charsets.UTF_8)}\n" +
                    "actual  : ${actual.copyOfRange(i, minOf(i + 80, actual.size)).toString(Charsets.UTF_8)}"
            )
        }
    }

    // ───────── 1) مبسطة أدنى: بلا مشترٍ ولا توريد ولا خصم ─────────

    @Test
    fun golden_simplifiedMinimal_byteForByte() {
        val xml = ZatcaUbl.build(
            ZatcaUbl.UblRequest(
                number = "INV-0001", uuid = "550e8400-e29b-41d4-a716-446655440000",
                issueTimeMs = issueMs, subtype = "0200000", seller = seller,
                icv = 1, pih = firstPih,
                lines = listOf(
                    ZatcaUbl.UblLine(
                        desc = "قطعة أ", qty = 2.0, unitPrice = 50.0,
                        lineNet = 100.0, rate = 15.0, lineTax = 15.0,
                    ),
                    ZatcaUbl.UblLine(
                        desc = "قطعة ب", qty = 1.0, unitPrice = 25.5,
                        lineNet = 25.5, rate = 15.0, lineTax = 3.83,
                    ),
                ),
                totals = ZatcaUbl.Totals(
                    subtotal = 125.5, discount = 0.0,
                    taxAmount = 18.83, total = 144.33,
                ),
            )
        )
        assertGolden("golden_simplified_minimal.xml", xml)
    }

    // ───────── 2) قياسية B2B: مشترٍ بالرقم والسجل وعنوان + فئتا S وE + توريد + تحويل ─────────

    @Test
    fun golden_standardB2b_byteForByte() {
        val xml = ZatcaUbl.build(
            ZatcaUbl.UblRequest(
                number = "INV-0007", uuid = "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                issueTimeMs = issueMs, subtype = "0100000", seller = seller,
                buyer = buyer, deliveryDateMs = deliveryMs, paymentMeansCode = "30",
                icv = 7, pih = nextPih,
                lines = listOf(
                    ZatcaUbl.UblLine(
                        desc = "خدمة قياسية", qty = 1.0, unitPrice = 100.0,
                        lineNet = 100.0, rate = 15.0, lineTax = 15.0,
                    ),
                    ZatcaUbl.UblLine(
                        desc = "سلعة معفاة", qty = 1.0, unitPrice = 50.0,
                        lineNet = 50.0, rate = 0.0, taxKind = 2, lineTax = 0.0,
                    ),
                ),
                totals = ZatcaUbl.Totals(
                    subtotal = 150.0, discount = 0.0,
                    taxAmount = 15.0, total = 165.0, prepaid = 50.0,
                ),
            )
        )
        assertGolden("golden_standard_b2b.xml", xml)
    }

    // ───────── 3) دائنة: جذر CreditNote و381 وBillingReference وسطر CreditedQuantity ─────────

    @Test
    fun golden_creditNote_byteForByte() {
        val xml = ZatcaUbl.build(
            ZatcaUbl.UblRequest(
                kind = ZatcaUbl.DocKind.CREDIT_NOTE,
                number = "CN-0002", uuid = "2b5c1f9e-8a1d-4f0b-9c2d-3e4f5a6b7c8d",
                issueTimeMs = issueMs, subtype = "0200000", seller = seller,
                originalInvoiceNumber = "INV-0001",
                icv = 9, pih = nextPih,
                lines = listOf(
                    ZatcaUbl.UblLine(
                        desc = "مرتجع قطعة أ", qty = 1.0, unitPrice = 25.0,
                        lineNet = 25.0, rate = 15.0, lineTax = 3.75,
                    ),
                ),
                totals = ZatcaUbl.Totals(
                    subtotal = 25.0, discount = 0.0,
                    taxAmount = 3.75, total = 28.75,
                ),
            )
        )
        assertGolden("golden_credit_note.xml", xml)
    }

    // ───────── 4) مدينة: جذر DebitNote و383 وDebitedQuantity ومشترٍ بلا عنوان ─────────

    @Test
    fun golden_debitNote_byteForByte() {
        val xml = ZatcaUbl.build(
            ZatcaUbl.UblRequest(
                kind = ZatcaUbl.DocKind.DEBIT_NOTE,
                number = "DN-0001", uuid = "aa1b2c3d-4e5f-4a0b-8c9d-0e1f2a3b4c5d",
                issueTimeMs = issueMs, subtype = "0100000", seller = seller,
                buyer = buyerMinimal, originalInvoiceNumber = "INV-0007",
                paymentMeansCode = "30",
                icv = 11, pih = nextPih,
                lines = listOf(
                    ZatcaUbl.UblLine(
                        desc = "فرق سعر", qty = 1.0, unitPrice = 10.0,
                        lineNet = 10.0, rate = 15.0, lineTax = 1.5,
                    ),
                ),
                totals = ZatcaUbl.Totals(
                    subtotal = 10.0, discount = 0.0,
                    taxAmount = 1.5, total = 11.5,
                ),
            )
        )
        assertGolden("golden_debit_note.xml", xml)
    }

    // ───────── 5) مبسطة صفرية: فئة Z بالكامل + تهريب < & في وصف الصنف ─────────

    @Test
    fun golden_simplifiedZeroTax_byteForByte() {
        val xml = ZatcaUbl.build(
            ZatcaUbl.UblRequest(
                number = "INV-0003", uuid = "0f1e2d3c-4b5a-4988-8776-665544332211",
                issueTimeMs = issueMs, subtype = "0200000", seller = seller,
                paymentMeansCode = "48",
                icv = 3, pih = nextPih,
                lines = listOf(
                    ZatcaUbl.UblLine(
                        desc = "سلعة صفرية <تصدير>", qty = 4.0, unitPrice = 20.0,
                        lineNet = 80.0, rate = 0.0, taxKind = 1, lineTax = 0.0,
                    ),
                ),
                totals = ZatcaUbl.Totals(
                    subtotal = 80.0, discount = 0.0,
                    taxAmount = 0.0, total = 80.0,
                ),
            )
        )
        assertGolden("golden_simplified_zero.xml", xml)
    }

    // ───────── 6) خصم سطري + كمية كسرية 0.5 + AllowanceTotalAmount ─────────

    @Test
    fun golden_discountLines_byteForByte() {
        val xml = ZatcaUbl.build(
            ZatcaUbl.UblRequest(
                number = "INV-0005", uuid = "11aa22bb-33cc-44dd-9988-776655443322",
                issueTimeMs = issueMs, subtype = "0200000", seller = seller,
                icv = 5, pih = nextPih,
                lines = listOf(
                    ZatcaUbl.UblLine(
                        desc = "قطعة أ", qty = 2.0, unitPrice = 50.0,
                        lineDiscount = 10.0, lineNet = 90.0, rate = 15.0, lineTax = 13.5,
                    ),
                    ZatcaUbl.UblLine(
                        desc = "قطعة ب", qty = 0.5, unitPrice = 51.0,
                        lineNet = 25.5, rate = 15.0, lineTax = 3.83,
                    ),
                ),
                totals = ZatcaUbl.Totals(
                    subtotal = 115.5, discount = 10.0,
                    taxAmount = 17.33, total = 132.83,
                ),
            )
        )
        assertGolden("golden_discount_lines.xml", xml)
    }

    // ───────── 7) حتمية: نفس الطلب ⇒ بايتات نفسها ─────────

    @Test
    fun build_isByteDeterministic_sameRequestSameBytes() {
        val req = ZatcaUbl.UblRequest(
            number = "INV-9", uuid = "u-9", issueTimeMs = issueMs, subtype = "0200000",
            seller = seller, icv = 2, pih = firstPih,
            lines = listOf(
                ZatcaUbl.UblLine(
                    desc = "صنف", qty = 1.0, unitPrice = 10.0,
                    lineNet = 10.0, rate = 15.0, lineTax = 1.5,
                ),
            ),
            totals = ZatcaUbl.Totals(10.0, 0.0, 1.5, 11.5),
        )
        assertTrue(ZatcaUbl.buildBytes(req).contentEquals(ZatcaUbl.buildBytes(req)))
    }

    // ───────── 8) ترتيب الأسطر لا يغيّر ترتيب الفئات الفرعية ─────────

    @Test
    fun taxSubtotals_categoryOrder_stableRegardlessOfLineOrder() {
        fun req(lines: List<ZatcaUbl.UblLine>) = ZatcaUbl.UblRequest(
            number = "X", uuid = "u", issueTimeMs = issueMs, subtype = "0100000",
            seller = seller, buyer = buyerMinimal, icv = 1, pih = firstPih,
            lines = lines,
            totals = ZatcaUbl.Totals(
                subtotal = lines.sumOf { it.lineNet },
                discount = 0.0,
                taxAmount = lines.sumOf { it.lineTax },
                total = lines.sumOf { it.lineNet + it.lineTax },
            ),
        )
        val s = ZatcaUbl.UblLine("s", 1.0, 10.0, lineNet = 10.0, rate = 15.0, lineTax = 1.5)
        val e = ZatcaUbl.UblLine("e", 1.0, 10.0, lineNet = 10.0, rate = 0.0, taxKind = 2, lineTax = 0.0)
        val z = ZatcaUbl.UblLine("z", 1.0, 10.0, lineNet = 10.0, rate = 0.0, taxKind = 1, lineTax = 0.0)
        val a = ZatcaUbl.taxSubtotals(req(listOf(s, e, z))).map { it.category }
        val b = ZatcaUbl.taxSubtotals(req(listOf(z, s, e))).map { it.category }
        assertEquals(listOf("S", "Z", "E"), a)
        assertEquals(a, b) // ترتيب الفئات ثابت مهما كان ترتيب الإدخال
    }

    // ───────── 9) انحراف التقريب يُساوى حرفياً مع المخزّن ─────────

    @Test
    fun taxSubtotals_driftAlignedToStoredTaxAmount_onLargestTaxable() {
        // فئتان: الأكبر أساساً هو S — الفارق 0.01 يذهب إليه
        val lines = listOf(
            ZatcaUbl.UblLine("big", 1.0, 100.0, lineNet = 100.0, rate = 15.0, lineTax = 15.0),
            ZatcaUbl.UblLine("small", 1.0, 10.0, lineNet = 10.0, rate = 0.0, taxKind = 2, lineTax = 0.0),
        )
        val r = ZatcaUbl.UblRequest(
            number = "X", uuid = "u", issueTimeMs = issueMs, subtype = "0200000",
            seller = seller, icv = 1, pih = firstPih, lines = lines,
            totals = ZatcaUbl.Totals(110.0, 0.0, taxAmount = 15.01, total = 125.01),
            // المخزّن 15.01 والسطور تحسب 15.00 ⇒ انحراف +0.01
        )
        val subs = ZatcaUbl.taxSubtotals(r)
        assertEquals(2, subs.size)
        assertEquals(15.01, subs[0].tax, 1e-9)  // الأكبر (S 100) يأخذ الانحراف
        assertEquals(0.0, subs[1].tax, 1e-9)
        // وفي المستند نفسه: مجموع الفرعية = الرأس حرفياً
        val xml = ZatcaUbl.build(r)
        assertTrue(xml.contains("<cbc:TaxAmount currencyID=\"SAR\">15.01</cbc:TaxAmount>"))
        assertTrue(xml.contains("15.01"))
    }

    // ───────── 10) التحقق الشكلي validate ─────────

    @Test
    fun validate_reportsMissingFields_perSubtype() {
        // قياسية بلا مشترٍ ⇒ buyer.vat ناقص
        val std = ZatcaUbl.UblRequest(
            number = "X", uuid = "u", issueTimeMs = issueMs, subtype = "0100000",
            seller = seller, icv = 1, pih = firstPih, lines = emptyList(),
            totals = ZatcaUbl.Totals(0.0, 0.0, 0.0, 0.0),
        )
        assertEquals(listOf("buyer.vat"), ZatcaUbl.validate(std))
        // قياسية بمشترٍ مكتمل ⇒ سليم
        val okStd = std.copy(buyer = buyer)
        assertTrue(ZatcaUbl.validate(okStd).isEmpty())
        // بائع بلا عنوان مقنن ⇒ seller.address
        val noAddr = okStd.copy(seller = seller.copy(address = null))
        assertTrue(ZatcaUbl.validate(noAddr).contains("seller.address"))
        // بلا UUID ⇒ uuid
        val noUuid = okStd.copy(uuid = "")
        assertTrue(ZatcaUbl.validate(noUuid).contains("uuid"))
    }
}
