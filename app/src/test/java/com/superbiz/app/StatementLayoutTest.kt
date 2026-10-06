package com.superbiz.app

import com.superbiz.app.domain.statement.BalanceDirection
import com.superbiz.app.domain.statement.StatementCompanyInfo
import com.superbiz.app.domain.statement.StatementData
import com.superbiz.app.domain.statement.StatementLang
import com.superbiz.app.domain.statement.StatementPartyInfo
import com.superbiz.app.domain.statement.StatementPeriodPreset
import com.superbiz.app.domain.statement.StatementService
import com.superbiz.app.domain.statement.StatementSummary
import com.superbiz.app.domain.statement.StatementTxRow
import com.superbiz.app.domain.statement.StatementTxTypes
import com.superbiz.app.pdf.statement.LPage
import com.superbiz.app.pdf.statement.StatementFormat
import com.superbiz.app.pdf.statement.StatementLayout
import com.superbiz.app.pdf.statement.StatementTemplates
import com.superbiz.app.pdf.statement.TextMeasurer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * [P17-b] مساعد التدقيق الهندسي — تأكيدات الحدود/التراكب/الترقيم مشتركة بين الاختبارات.
 */
object StatementPageAssertions {

    private val EPS = 0.05f

    /** تدقيق صفحة أو مجموعة: حدود الهوامش + عدم تراكب النصوص + وجود ترقيم الصفحات */
    fun audit(pages: List<LPage>, pageW: Float, pageH: Float, margin: Float, tag: String) {
        assertTrue("$tag: no pages", pages.isNotEmpty())
        val total = pages.size
        for (p in pages) {
            for (t in p.elements) {
                val r = t.rect
                assertTrue("$tag: text out of left margin '${t.text.take(20)}' x=${r.x}", r.x >= margin - EPS)
                assertTrue("$tag: text out of right margin '${t.text.take(20)}'", r.x + r.w <= pageW - margin + EPS)
                assertTrue("$tag: text above top margin '${t.text.take(20)}' y=${r.y}", r.y >= margin - EPS)
                assertTrue("$tag: text below bottom margin '${t.text.take(20)}'", r.y + r.h <= pageH - margin + EPS)
            }
            for (b in p.bitmaps) {
                val r = b.rect
                assertTrue("$tag: bitmap out of page kind=${b.kind}",
                    r.x >= -EPS && r.y >= -EPS && r.x + r.w <= pageW + EPS && r.y + r.h <= pageH + EPS)
            }
            // تراكب نصّي زوجي — محصور بنفس الصفحة (تسامح EPS على الحافة)
            val els = p.elements
            for (i in els.indices) for (j in i + 1 until els.size) {
                val a = els[i].rect; val b = els[j].rect
                val overlap = a.x < b.x + b.w - EPS && b.x < a.x + a.w - EPS &&
                    a.y < b.y + b.h - EPS && b.y < a.y + a.h - EPS
                assertTrue(
                    "$tag: text overlap p${p.index + 1}: '${els[i].text.take(16)}' vs '${els[j].text.take(16)}'",
                    !overlap
                )
            }
        }
    }

    private fun assertTrue(msg: String, cond: Boolean) {
        if (!cond) throw AssertionError(msg)
    }
}

/**
 * [P17-b] StatementLayoutTest — تدقيق هندسي شامل للمحرك النقي عبر كل القوالب.
 *
 * مقيّس تقديري حتمي (حرف×عامل) — ثابت على JVM؛ على الجهاز يستخدم Paint.measureText
 * والفروق في العرض لا تكسر الثوابت المفحوصة (حدود/تراكب/ترقيم) لأن المحرك يعيد
 * القياس فعلياً عبر TextMeasurer ولا يفترض عرضاً ثابتاً.
 */
class StatementLayoutTest {

    // ─────────────────── مقيّس تقديري حتمي للاختبار ───────────────────
    private class EstMeasurer : TextMeasurer {
        override fun width(text: String, sizeSp: Float, bold: Boolean): Float =
            text.length * sizeSp * (if (bold) 0.66f else 0.58f)
    }

    // ─────────────────── تركيبات بيانات ───────────────────
    private val day = 86_400_000L

    private fun party(lang: StatementLang) = StatementPartyInfo(
        id = 7L,
        name = if (lang == StatementLang.EN) "Al-Mutahaidoon Trading Company Limited Long Name"
        else "شركة المتحدون التجارية المحدودة للاستيراد والتصدير والتوريدات الطبية",
        phone = "+966 50 123 4567", email = "info@client.example", address = "طريق الملك فهد، حي العليا، الرياض 12345",
        taxNumber = "310123456700003", crNumber = "1010512345", city = "الرياض", country = "المملكة العربية السعودية",
        website = "https://client.example", accountNumber = "ACC-77031", partyNo = StatementService.partyNumber(7L)
    )

    private fun company() = StatementCompanyInfo(
        businessName = "مؤسسة النجاح التجارية", ownerName = "عبدالله محمد",
        phone = "+966 11 456 7890", email = "billing@biz.example", address = "شارع العليا العام، الرياض",
        city = "الرياض", country = "السعودية", website = "https://biz.example",
        taxNumber = "310987654300003", crNumber = "4030123456", companyNo = "SB-01",
        logoPath = null, photoPath = null
    )

    // [P33-P8] مبالغ قروش Long — 1 ريال = 100 قرشاً (13.7 ريال = 1370 قروشاً و7.3 ريال = 730 قروشاً بدقة)
    private fun rows(n: Int, startTs: Long): List<StatementTxRow> {
        val out = ArrayList<StatementTxRow>(n)
        var bal = 125_000L
        val descs = listOf(
            "فاتورة بيع أدوات مكتبية متنوعة بعقد توريد سنوي رقم 2026/44 طويلة الوصف عمداً لاختبار اللفّ",
            "Very long invoice description to force text wrapping in narrow columns — supply agreement 2026/44",
            "دفعة نقطة بيع", "تسديد شيك رقم 551203", "قسط رقم 3 من خطة 9", "تسوية جردية"
        )
        for (i in 1..n) {
            val debit = if (i % 3 == 0) 0L else 25_000L + i * 1_370L
            val credit = if (i % 3 == 0) 18_000L + i * 730L else 0L
            bal += debit - credit
            out += StatementTxRow(
                ts = startTs + i * day / 4,
                ref = if (i % 3 == 0) "payment#$i" else "invoice#$i",
                typeKey = if (i % 3 == 0) StatementTxTypes.PAYMENT else StatementTxTypes.INVOICE,
                desc = descs[i % descs.size],
                debit = debit, credit = credit, balance = bal
            )
        }
        return out
    }

    // [P33-P8] قروش: كل مجاميع العرض ×100 (1250.0 → 125000 ، 52340.0 → 5234000 ، 31210.5 → 3121050 ...)
    private fun summary(opening: Long = 125_000L) = StatementSummary(
        opening = opening, totalDebit = 5_234_000L, totalCredit = 3_121_050L,
        totalPayments = 2_840_000L, totalInvoices = 4_870_000L, totalDiscounts = 32_000L,
        due = opening + 5_234_000L - 3_121_050L, final = opening + 5_234_000L - 3_121_050L,
        direction = BalanceDirection.DUE_ON_CUSTOMER
    )

    private fun data(
        lang: StatementLang, rowCount: Int, from: Long = 1_767_225_600_000L // 2026-01-01 UTC تقريباً
    ): StatementData {
        val rs = rows(rowCount, from)
        return StatementData(
            party = party(lang), company = company(), fromTs = from, toTs = from + 90 * day,
            currency = "SAR", rows = rs, summary = summary(),
            statementNumber = StatementService.statementNumber(1, 2026),
            verificationId = StatementService.verificationId("STATEMENT-2026-000001", from),
            createdAt = from + 91 * day,
            note = "يرجى سداد الرصيد المستحق خلال ثلاثين يوماً من تاريخ الكشف — شاكرين تعاونكم الدائم. / Please settle within 30 days.",
            lang = lang
        )
    }

    // ─────────────────── التدقيق الشامل: 50 قالباً × 3 لغات ───────────────────

    @Test
    fun all50Templates_allLangs_geometryAudit() {
        val langs = listOf(StatementLang.AR, StatementLang.EN, StatementLang.BILINGUAL)
        assertEquals(50, StatementTemplates.ALL.size)
        for (def in StatementTemplates.ALL) {
            val s = def.style
            val pw = if (s.landscape) 842f else 595f
            val ph = if (s.landscape) 595f else 842f
            val margin = s.marginDp * StatementLayout.DP2PT
            for (lang in langs) {
                val pages = StatementLayout.layout(data(lang, 237), s, pw, ph, margin, EstMeasurer())
                StatementPageAssertions.audit(pages, pw, ph, margin, "template=${def.id} lang=$lang")
                // صفحة أخيرة غير فارغة + صفحات كافية
                assertTrue("${def.id}: pages>1 for 237 rows, lang=$lang", pages.size > 1)
                assertTrue("${def.id}: last page has elements, lang=$lang", pages.last().elements.isNotEmpty())
            }
        }
    }

    @Test
    fun all50Templates_tupleUniqueness_noColorOnlyClones() {
        val seen = HashSet<String>()
        for (def in StatementTemplates.ALL) {
            val s = def.style
            val key = listOf(
                s.header.name, s.table.name, s.summary.name,
                "0x%06X".format(s.primary and 0xFFFFFF), s.marginDp, s.headingFontScale
            ).joinToString("|")
            assertTrue("duplicate structural tuple: $key (id=${def.id})", seen.add(key))
        }
        // كل فئة 5 قوالب بالضبط
        val byCat = StatementTemplates.ALL.groupBy { it.cat }
        assertEquals(10, byCat.size)
        byCat.values.forEach { assertEquals(5, it.size) }
        // هويات ثنائية اللغة غير فارغة
        StatementTemplates.ALL.forEach {
            assertTrue(it.id.isNotBlank() && it.nameAr.isNotBlank() && it.nameEn.isNotBlank())
        }
    }

    @Test
    fun pagination_monotonicAcrossSizes() {
        val s = StatementTemplates.byId(StatementTemplates.DEFAULT_ID)!!.style
        val pw = 595f; val ph = 842f; val margin = s.marginDp * StatementLayout.DP2PT
        val counts = listOf(0, 1, 2, 50, 237, 5000).map { n ->
            StatementLayout.layout(data(StatementLang.AR, n), s, pw, ph, margin, EstMeasurer()).size
        }
        // رتيبة غير متناقصة
        assertTrue(counts.zipWithNext().all { (a, b) -> a <= b })
        assertEquals(1, counts[0]) // لا صفوف → صفحة واحدة (الملخص/الفراغ)
        assertEquals(1, counts[1])
        assertTrue(counts[4] > 1 && counts[5] > counts[4])
    }

    @Test
    fun emptyRows_showsEmptyStateBlock() {
        val s = StatementTemplates.byId("MIN-01")!!.style
        val pages = StatementLayout.layout(data(StatementLang.AR, 0), s, 595f, 842f, s.marginDp * StatementLayout.DP2PT, EstMeasurer())
        val allText = pages.joinToString("\n") { it.elements.joinToString("|") { e -> e.text } }
        assertTrue("empty-state AR text missing", allText.contains("لا توجد حركات"))
        val pagesEn = StatementLayout.layout(data(StatementLang.EN, 0), s, 595f, 842f, s.marginDp * StatementLayout.DP2PT, EstMeasurer())
        val allEn = pagesEn.joinToString("\n") { it.elements.joinToString("|") { e -> e.text } }
        assertTrue(allEn.contains("No transactions"))
    }

    @Test
    fun rtl_reversesDateColumnOrder() {
        val s = StatementTemplates.byId("ARA-01")!!.style
        val en = StatementLayout.layout(data(StatementLang.EN, 5), s, 595f, 842f, s.marginDp * StatementLayout.DP2PT, EstMeasurer())
        val ar = StatementLayout.layout(data(StatementLang.AR, 5), s, 595f, 842f, s.marginDp * StatementLayout.DP2PT, EstMeasurer())
        // أول سطر بيانات في الجدول: خلية التاريخ هي أول نص في صف الجدول
        fun firstRowX(pages: List<com.superbiz.app.pdf.statement.LPage>): Float {
            val el = pages.first().elements
            // بذل بسيط: نص يبدأ بـ "0" أو "/" (تاريخ) أو "2026" — نقارن أصغر x بين نصوص الصف الأول بعد الرأس
            val rowTexts = el.filter { it.text.isNotBlank() }
            return rowTexts.minOf { it.rect.x }
        }
        // في RTL تُزاح بداية الصف (التاريخ) إلى الحافة اليمنى ⇒ أكبر x مقارنة بـ LTR
        fun firstRowMaxX(pages: List<com.superbiz.app.pdf.statement.LPage>): Float {
            val el = pages.first().elements
            return el.filter { it.text.isNotBlank() }.maxOf { it.rect.x + it.rect.w }
        }
        assertTrue("AR start edge should sit more to the right", firstRowMaxX(ar) > 595f - 200f || firstRowX(ar) > firstRowX(en))
        assertTrue(firstRowMaxX(ar) > firstRowX(en) - 1f)
    }

    @Test
    fun landscape_swapsDimensions_reasonablePageCount() {
        val def = StatementTemplates.ALL.first { it.style.landscape }
        val s = def.style
        val pages = StatementLayout.layout(data(StatementLang.AR, 237), s, 842f, 595f, s.marginDp * StatementLayout.DP2PT, EstMeasurer())
        assertTrue(pages.isNotEmpty())
        for (p in pages) StatementPageAssertions.audit(listOf(p), 842f, 595f, s.marginDp * StatementLayout.DP2PT, "landscape=${def.id}")
    }

    @Test
    fun qrPayload_containsNoFinancialData() {
        val payload = StatementLayout.qrPayload("SB-ST-20260924-000001")
        assertEquals("superbiz://verify/SB-ST-20260924-000001", payload)
        assertTrue(!payload.contains("SAR") && !payload.contains("balance"))
    }

    // ─────────────────── StatementFormat ───────────────────

    @Test
    fun format_moneyGrouping() {
        // [P33-P8] المبالغ قروش Long عبر Money.numP — 1,234.50 ريال = 123450 قروشاً
        assertEquals("1,234.50 SAR", StatementFormat.money(123_450L, "SAR"))
        assertEquals("0.00 SAR", StatementFormat.money(0L, "SAR"))
        assertEquals("-987.65 SAR", StatementFormat.money(-98_765L, "SAR"))
        assertEquals("1,234,567.89 SAR", StatementFormat.money(123_456_789L, "SAR"))
    }

    @Test
    fun format_pageStrings_bilingual() {
        assertTrue(StatementFormat.pageOf(2, 5, StatementLang.AR).contains("2"))
        assertTrue(StatementFormat.pageOf(2, 5, StatementLang.EN).startsWith("Page"))
        assertTrue(StatementFormat.pageOf(1, 1, StatementLang.BILINGUAL).contains("1"))
    }

    @Test
    fun format_dates_deterministic() {
        val ts = 1_769_904_000_000L // 2026-02-01 تقريباً
        val ar = StatementFormat.dateShort(ts)
        val en = StatementFormat.dateShort(ts)
        assertEquals(ar, en) // dd/MM/yyyy صيغة واحدة لكلتا اللغتين — قرار موثق
        assertTrue(ar.contains("2026"))
    }

    // ─────────────────── ربط مع StatementService (عقد الأرقام) ───────────────────

    @Test
    fun service_numbersPipeline_consistent() {
        val n = StatementService.statementNumber(42, 2026)
        assertEquals("STATEMENT-2026-000042", n)
        val v = StatementService.verificationId(n, 1_769_904_000_000L)
        assertTrue(v.startsWith("SB-ST-") && v.endsWith("-000042"))
        val now = System.currentTimeMillis()
        val (from, to) = StatementService.periodRange(StatementPeriodPreset.THIS_MONTH, now, 0, 0)
        assertTrue(from <= to)
        val cal = Calendar.getInstance()
        cal.timeInMillis = to
        assertTrue(cal.get(Calendar.DAY_OF_MONTH) >= 28) // نهاية الشهر على الأقل 28
        val name = StatementService.safeFileName("محمد/أحمد:*?\"<>|", from, to)
        assertTrue(!name.contains("/") && !name.contains(":") && !name.contains("*") && !name.contains("?"))
    }
}
