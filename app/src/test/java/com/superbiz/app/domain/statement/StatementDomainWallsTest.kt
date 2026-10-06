package com.superbiz.app.domain.statement

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.domain.algo.TimeMath
import com.superbiz.app.pdf.statement.CornerPos
import com.superbiz.app.pdf.statement.FooterStyle
import com.superbiz.app.pdf.statement.HeaderStyle
import com.superbiz.app.pdf.statement.PdfElement
import com.superbiz.app.pdf.statement.StatementStyle
import com.superbiz.app.pdf.statement.SummaryStyle
import com.superbiz.app.pdf.statement.TableStyle
import com.superbiz.app.pdf.statement.TemplateCategory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * [P37-T-C] جدران اختبارات منظومة الكشوف — نطاق domain + العقد النقي:
 *
 * ① StatementPeriodPreset + StatementService.periodRange: كل القيم المسبقة تحسب
 *    نافذة [from, to] شاملة الطرفين عند تمرير now صريح. ملاحظة توثيقية: الـenum
 *    يسكن StatementModels.kt بينما دالة حساب النافذة periodRange تسكن
 *    StatementService.kt (object نقي JVM) — الجدران هنا تغطيهما معاً لأنهما عقد واحد.
 * ② BalanceDirection / StatementLang / StatementTxTypes: كل القيم وثوابت النصوص
 *    الحرفية. خريطة refType→typeKey (invoice→INVOICE، payment→PAYMENT، debt→DEBT،
 *    check→CHECK، plan→INSTALLMENT، وإلا ADJUST) دالة خاصة في StatementRepo
 *    (typeKeyOf) لا يمكن لمسها بلا قاعدة بيانات — ثُبّتت قيمها الحرفية هنا كسور عقد.
 * ③ StatementTxRow / StatementSummary / StatementData: بناء حرفي بكل الحقول —
 *    ملاحظة موثقة: لا قيم افتراضية إطلاقاً في هذه النماذج (كل الحقول إلزامية)،
 *    وisRtl محسوبة على StatementData: AR/BILINGUAL = true، EN = false.
 * ④ StatementStyle (pdf/statement): القيم الافتراضية والألوان ومجموعة show —
 *    ملاحظة موثقة: لا يوجد أي علم RTL في StatementStyle نفسه؛ أعلام الاتجاه
 *    تعيش على StatementData.isRtl فقط (مغطاة أعلاه).
 * ⑤ StatementPrefs.load/save دوران كامل عبر SharedPreferences (Robolectric)
 *    + smtpConfig: null عند غير مفعّل/مضيف فارغ/كلمة سر ناقصة، وعقد 18-a صحيح
 *    عند الاكتمال (مع مرسل بديل من المستخدم وقصّ إعادة المحاولة 1..5).
 * ⑥ SmtpPrefsUi: الافتراضات + securityEnum (null/فارغ/غريب ⇒ SSL_TLS، تشذيب وcase-insensitive).
 *
 * حتمية: المنطقة الزمنية مثبتة على Asia/Riyadh (UTC+3 بلا توقيت صيفي) واللغة
 * على en-US (بداية الأسبوع = الأحد — قرار periodRange الموثق: أسبوع المنطقة
 * الافتراضية لا ISO)، وكل now مبني بحقول تقويم صريحة — لا System.currentTimeMillis.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class StatementDomainWallsTest {

    private val originalTz: TimeZone = TimeZone.getDefault()
    private val originalLocale: Locale = Locale.getDefault()

    private companion object {
        const val DAY_MS = 86_400_000L
    }

    @Before
    fun `تثبيت المنطقة الزمنية واللغة لضمان الحتمية`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Riyadh"))
        Locale.setDefault(Locale.US)
    }

    @After
    fun `استعادة المنطقة الزمنية واللغة الأصليتين`() {
        TimeZone.setDefault(originalTz)
        Locale.setDefault(originalLocale)
    }

    // ───────── أدوات ─────────

    /** لحظة محلية صريحة بحقول تقويم كاملة — 2026-03-15 يوم أحد (متحقق خارجياً) */
    private fun at(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0, s: Int = 0, ms: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(y, mo - 1, d, h, mi, s)
            if (ms != 0) set(Calendar.MILLISECOND, ms)
        }.timeInMillis

    private fun ctx(): Context = ApplicationProvider.getApplicationContext<Context>()

    /** كتابة خام بمفاتيح statement_prefs المتعاقد عليها — لاختبار مسار إصلاح load */
    private fun rawPut(body: (SharedPreferences.Editor) -> Unit) {
        val p = ctx().getSharedPreferences("statement_prefs", Context.MODE_PRIVATE)
        val e = p.edit()
        body(e)
        e.commit()
    }

    /** حزمة كشف مصغّرة بلغة محددة — لاختبار isRtl المحسوبة */
    private fun sampleData(lang: StatementLang): StatementData = StatementData(
        party = StatementPartyInfo(
            id = 1, name = "عميل تجريبي", phone = null, email = null, address = null,
            taxNumber = null, crNumber = null, city = null, country = null,
            website = null, accountNumber = null, partyNo = "P-000001"
        ),
        company = StatementCompanyInfo(
            businessName = "متجر النور", ownerName = "المالك", phone = null, email = null,
            address = null, city = null, country = null, website = null,
            taxNumber = null, crNumber = null, companyNo = "C-1", logoPath = null, photoPath = null
        ),
        fromTs = 0L, toTs = 0L, currency = "SAR",
        rows = listOf(
            StatementTxRow(ts = 0L, ref = "", typeKey = StatementTxTypes.ADJUST, desc = "",
                debit = 0L, credit = 0L, balance = 0L)
        ),
        summary = StatementSummary(
            opening = 0L, totalDebit = 0L, totalCredit = 0L, totalPayments = 0L,
            totalInvoices = 0L, totalDiscounts = 0L, due = 0L, final = 0L,
            direction = BalanceDirection.BALANCED
        ),
        statementNumber = "STATEMENT-2026-000001", verificationId = "V-1",
        createdAt = 0L, note = null, lang = lang
    )

    // ───────── ① فترات الكشف الجاهزة (periodRange بnow صريح) ─────────

    @Test
    fun `اليوم - من بداية اليوم حتى آخر ميلي ثانية فيه شاملة الطرفين`() {
        val now = at(2026, 3, 15, 15, 42, 30, 500)
        val (from, to) = StatementService.periodRange(StatementPeriodPreset.TODAY, now)
        assertEquals(at(2026, 3, 15), from)
        assertEquals(at(2026, 3, 16) - 1, to)
        assertEquals(DAY_MS, to - from + 1)
        assertTrue(now in from..to)
    }

    @Test
    fun `هذا الأسبوع - الأحد بداية الأسبوع والنافذة سبعة أيام كاملة`() {
        val now = at(2026, 3, 15, 9, 0, 0) // أحد
        val (from, to) = StatementService.periodRange(StatementPeriodPreset.THIS_WEEK, now)
        assertEquals(at(2026, 3, 15), from)
        assertEquals(at(2026, 3, 22) - 1, to)
        assertEquals(7 * DAY_MS, to - from + 1)
        assertTrue(now in from..to)
    }

    @Test
    fun `هذا الأسبوع - منتصف الأسبوع يعيد نافذة الأحد نفسها`() {
        val now = at(2026, 3, 18, 20, 15) // أربعاء بنفس الأسبوع
        val (from, to) = StatementService.periodRange(StatementPeriodPreset.THIS_WEEK, now)
        assertEquals(at(2026, 3, 15), from)
        assertEquals(at(2026, 3, 22) - 1, to)
    }

    @Test
    fun `الأسبوع الماضي - إزاحة سبعة أيام كاملة عن نافذة الأسبوع الحالي`() {
        val now = at(2026, 3, 15, 23, 59)
        val (from, to) = StatementService.periodRange(StatementPeriodPreset.LAST_WEEK, now)
        assertEquals(at(2026, 3, 8), from)
        assertEquals(at(2026, 3, 15) - 1, to)
        assertEquals(7 * DAY_MS, to - from + 1)
        assertTrue(now > to) // الأسبوع الماضي لا يحوي now
    }

    @Test
    fun `هذا الشهر - من أول الشهر حتى آخر لحظة فيه`() {
        val now = at(2026, 3, 15, 12, 0)
        val (from, to) = StatementService.periodRange(StatementPeriodPreset.THIS_MONTH, now)
        assertEquals(at(2026, 3, 1), from)
        assertEquals(at(2026, 4, 1) - 1, to)
        assertEquals(31 * DAY_MS, to - from + 1) // مارس 31 يوماً
        assertTrue(now in from..to)
    }

    @Test
    fun `الشهر الماضي - فبراير ثمانية وعشرين يوماً في سنة غير كبيسة`() {
        val now = at(2026, 3, 15, 8, 0)
        val (from, to) = StatementService.periodRange(StatementPeriodPreset.LAST_MONTH, now)
        assertEquals(at(2026, 2, 1), from)
        assertEquals(at(2026, 3, 1) - 1, to)
        assertEquals(28 * DAY_MS, to - from + 1) // 2026 ليست كبيسة
        assertTrue(now > to)
    }

    @Test
    fun `آخر ثلاثة أشهر - تشمل الشهر الحالي واثنين سابقين حتى أول الشهر الثالث`() {
        val now = at(2026, 3, 15, 12, 0)
        val (from, to) = StatementService.periodRange(StatementPeriodPreset.LAST_3_MONTHS, now)
        assertEquals(at(2026, 1, 1), from)
        assertEquals(at(2026, 4, 1) - 1, to)
        assertEquals(90 * DAY_MS, to - from + 1) // 31+28+31
        assertTrue(now in from..to)
    }

    @Test
    fun `آخر ستة أشهر - تعبر حدود السنة إلى أكتوبر`() {
        val now = at(2026, 3, 15, 12, 0)
        val (from, to) = StatementService.periodRange(StatementPeriodPreset.LAST_6_MONTHS, now)
        assertEquals(at(2025, 10, 1), from)
        assertEquals(at(2026, 4, 1) - 1, to)
        assertEquals(182 * DAY_MS, to - from + 1) // 31+30+31+31+28+31
        assertTrue(now in from..to)
    }

    @Test
    fun `هذه السنة - من أول يناير حتى آخر لحظة في ديسمبر`() {
        val now = at(2026, 3, 15, 6, 30)
        val (from, to) = StatementService.periodRange(StatementPeriodPreset.THIS_YEAR, now)
        assertEquals(at(2026, 1, 1), from)
        assertEquals(at(2027, 1, 1) - 1, to)
        assertEquals(365 * DAY_MS, to - from + 1) // 2026 غير كبيسة
        assertTrue(now in from..to)
    }

    @Test
    fun `السنة الماضية - ثلاثمئة وخمسة وستون يوماً كاملة بلا نقص`() {
        val now = at(2026, 3, 15, 6, 30)
        val (from, to) = StatementService.periodRange(StatementPeriodPreset.LAST_YEAR, now)
        assertEquals(at(2025, 1, 1), from)
        assertEquals(at(2026, 1, 1) - 1, to)
        assertEquals(365 * DAY_MS, to - from + 1)
        assertTrue(now > to)
    }

    @Test
    fun `مخصص - النافذة الممررة كما هي والمعكوسة تقلب فرضا من إلى`() {
        val now = at(2026, 3, 15, 10, 0)
        assertEquals(100L to 500L,
            StatementService.periodRange(StatementPeriodPreset.CUSTOM, now, 100L, 500L))
        // نافذة منزلقة بصمت: المعكوسة تُقلب ولا يرمى استثناء (قرار موثق في periodRange)
        assertEquals(100L to 500L,
            StatementService.periodRange(StatementPeriodPreset.CUSTOM, now, 500L, 100L))
    }

    @Test
    fun `مخصص - الصفر عند الفارغ أي الافتراضات 0 و0 تعيد صفرا إلى صفر`() {
        val now = at(2026, 3, 15, 10, 0)
        assertEquals(0L to 0L, StatementService.periodRange(StatementPeriodPreset.CUSTOM, now))
    }

    @Test
    fun `كل الفترات الجاهزة - ترتيب صحيح وحدود منتصف ليل واحتواء now للفترات الجارية`() {
        val now = at(2026, 3, 15, 12, 0)
        val includesNow = setOf(
            StatementPeriodPreset.TODAY, StatementPeriodPreset.THIS_WEEK,
            StatementPeriodPreset.THIS_MONTH, StatementPeriodPreset.LAST_3_MONTHS,
            StatementPeriodPreset.LAST_6_MONTHS, StatementPeriodPreset.THIS_YEAR
        )
        for (p in StatementPeriodPreset.values().filter { it != StatementPeriodPreset.CUSTOM }) {
            val (f, t) = StatementService.periodRange(p, now)
            assertTrue("$p: from<to", f < t)
            assertEquals("$p: بداية النافذة منتصف ليل", f, TimeMath.startOfDay(f))
            assertEquals("$p: ما بعد النهاية منتصف ليل", t + 1, TimeMath.startOfDay(t + 1))
            if (p in includesNow) assertTrue("$p: now داخل النافذة", now in f..t)
            else assertTrue("$p: النافذة كلها قبل now", t < now)
        }
    }

    // ───────── ② القيم المعدودة والثوابت ─────────

    @Test
    fun `فترات الكشف - عشر قيم بالأسماء والترتيب المتعاقد عليه`() {
        assertEquals(
            listOf(
                "TODAY", "THIS_WEEK", "LAST_WEEK", "THIS_MONTH", "LAST_MONTH",
                "LAST_3_MONTHS", "LAST_6_MONTHS", "THIS_YEAR", "LAST_YEAR", "CUSTOM"
            ),
            StatementPeriodPreset.values().map { it.name }
        )
    }

    @Test
    fun `اتجاه الرصيد - ثلاث قيم مدين عليه ولمصلحته ومتوازن`() {
        assertEquals(
            listOf("DUE_ON_CUSTOMER", "IN_FAVOR_OF_CUSTOMER", "BALANCED"),
            BalanceDirection.values().map { it.name }
        )
    }

    @Test
    fun `أنواع الحركات - ستة مفاتيح نصية ثابتة القيمة حرفيا`() {
        assertEquals("INVOICE", StatementTxTypes.INVOICE)
        assertEquals("PAYMENT", StatementTxTypes.PAYMENT)
        assertEquals("DEBT", StatementTxTypes.DEBT)
        assertEquals("CHECK", StatementTxTypes.CHECK)
        assertEquals("INSTALLMENT", StatementTxTypes.INSTALLMENT)
        assertEquals("ADJUST", StatementTxTypes.ADJUST)
        // سور عقد الخريطة الموثقة (typeKeyOf الخاصة في StatementRepo):
        // invoice→INVOICE payment→PAYMENT debt→DEBT check→CHECK plan→INSTALLMENT
        // وكل ما عداه (tax/cash/expense/stock/edit/void/null) → ADJUST
    }

    // ───────── ③ نماذج السطور والملخص والحزمة ─────────

    @Test
    fun `لغة الكشف - ثلاث قيم وisRtl محسوبة للعربي والثنائي لا للإنجليزي`() {
        assertEquals(listOf("AR", "EN", "BILINGUAL"), StatementLang.values().map { it.name })
        assertTrue(sampleData(StatementLang.AR).isRtl)
        assertTrue(sampleData(StatementLang.BILINGUAL).isRtl)
        assertFalse(sampleData(StatementLang.EN).isRtl)
    }

    @Test
    fun `سطر الحركة - البناء الحرفي للحقول السبعة كما مررت`() {
        val ts = 1_770_000_000_000L
        val row = StatementTxRow(
            ts = ts, ref = "invoice#12", typeKey = StatementTxTypes.INVOICE,
            desc = "فاتورة مبيعات نقدية", debit = 15_000L, credit = 0L, balance = 15_000L
        )
        assertEquals(ts, row.ts)
        assertEquals("invoice#12", row.ref)
        assertEquals(StatementTxTypes.INVOICE, row.typeKey)
        assertEquals("فاتورة مبيعات نقدية", row.desc)
        assertEquals(15_000L, row.debit)
        assertEquals(0L, row.credit)
        assertEquals(15_000L, row.balance)
        // المرجع قد يكون نصا فارغا حسب العقد (refType+refId قد يغيبان)
        assertEquals("", row.copy(ref = "").ref)
    }

    @Test
    fun `الملخص - البناء بكامل حقوله التسعة وعلاقة الأرقام تفرضها summarize`() {
        val s = StatementSummary(
            opening = 100L, totalDebit = 900L, totalCredit = 500L, totalPayments = 400L,
            totalInvoices = 800L, totalDiscounts = 25L, due = 500L, final = 500L,
            direction = BalanceDirection.DUE_ON_CUSTOMER
        )
        assertEquals(100L, s.opening)
        assertEquals(900L, s.totalDebit)
        assertEquals(500L, s.totalCredit)
        assertEquals(400L, s.totalPayments)
        assertEquals(800L, s.totalInvoices)
        assertEquals(25L, s.totalDiscounts)
        assertEquals(500L, s.due)
        assertEquals(500L, s.final)
        assertEquals(BalanceDirection.DUE_ON_CUSTOMER, s.direction)
        // علاقة الأرقام الموثقة عند StatementSummary تفرضها StatementService.summarize:
        // totalInvoices = Σ debit لسطور INVOICE فقط ، totalPayments = Σ credit لسطور PAYMENT فقط
        // due = final = opening + totalDebit - totalCredit ، والاتجاه بإشارة الصافي التامة
        val rows = listOf(
            StatementTxRow(1L, "invoice#1", StatementTxTypes.INVOICE, "ف", 800L, 0L, 800L),
            StatementTxRow(2L, "invoice#2", StatementTxTypes.INVOICE, "ف", 200L, 0L, 1000L),
            StatementTxRow(3L, "payment#1", StatementTxTypes.PAYMENT, "س", 0L, 500L, 500L),
            StatementTxRow(4L, "debt#1", StatementTxTypes.DEBT, "د", 100L, 0L, 600L),
            StatementTxRow(5L, "adjust", StatementTxTypes.ADJUST, "ت", 0L, 40L, 560L)
        )
        val sum = StatementService.summarize(opening = 0L, rows = rows, totalDiscounts = 25L)
        assertEquals(1100L, sum.totalDebit)     // 800+200+100 (كل المدين)
        assertEquals(540L, sum.totalCredit)     // 500+40 (كل الدائن)
        assertEquals(1000L, sum.totalInvoices)  // INVOICE فقط
        assertEquals(500L, sum.totalPayments)   // PAYMENT فقط
        assertEquals(25L, sum.totalDiscounts)   // تمرر كما هي
        assertEquals(560L, sum.due)             // 0+1100-540
        assertEquals(560L, sum.final)
        assertEquals(BalanceDirection.DUE_ON_CUSTOMER, sum.direction) // صافٍ موجب تام
        assertEquals(BalanceDirection.IN_FAVOR_OF_CUSTOMER,
            StatementService.summarize(-1L, emptyList(), 0L).direction) // صافٍ سالب تام
        assertEquals(BalanceDirection.BALANCED,
            StatementService.summarize(0L, emptyList(), 0L).direction)  // توازن تام
    }

    @Test
    fun `بطاقتا الطرف والمنشأة - الحقول الاختيارية تقبل null وتضبط بالقيم عند الوجود`() {
        val party = StatementPartyInfo(
            id = 7L, name = "شركة الأفق", phone = "0500000007", email = "ofoq@x.com",
            address = "الرياض - حي العليا", taxNumber = "300000000000003",
            crNumber = "1010000007", city = "الرياض", country = "السعودية",
            website = "https://ofoq.example", accountNumber = "SA0000000000000000000000",
            partyNo = "P-000007"
        )
        assertEquals(7L, party.id)
        assertEquals("شركة الأفق", party.name)
        assertEquals("P-000007", party.partyNo)
        assertEquals("300000000000003", party.taxNumber)

        val minimal = StatementPartyInfo(
            id = 1L, name = "عميل", phone = null, email = null, address = null,
            taxNumber = null, crNumber = null, city = null, country = null,
            website = null, accountNumber = null, partyNo = "P-000001"
        )
        assertNull(minimal.phone)
        assertNull(minimal.email)
        assertNull(minimal.taxNumber)

        val company = StatementCompanyInfo(
            businessName = "متجر النور", ownerName = "أحمد", phone = "0500000000",
            email = "noor@x.com", address = "جدة", city = "جدة", country = "السعودية",
            website = null, taxNumber = null, crNumber = null, companyNo = "C-1",
            logoPath = "/data/logo.png", photoPath = null
        )
        assertEquals("متجر النور", company.businessName)
        assertEquals("C-1", company.companyNo)
        assertEquals("/data/logo.png", company.logoPath)
        assertNull(company.photoPath)
        assertNull(company.website)
    }

    @Test
    fun `حزمة البيانات - البناء الكامل والنسخ لا تمس الأصل وisRtl يتبع اللغة`() {
        val data = sampleData(StatementLang.AR)
        assertEquals("STATEMENT-2026-000001", data.statementNumber)
        assertEquals("V-1", data.verificationId)
        assertEquals("SAR", data.currency)
        assertEquals(1, data.rows.size)
        assertEquals(StatementLang.AR, data.lang)
        assertTrue(data.isRtl)

        val en = data.copy(lang = StatementLang.EN, note = "ملاحظة")
        assertFalse(en.isRtl)
        assertEquals("ملاحظة", en.note)
        assertNull(data.note) // الأصل لم يتأثر بالنسخ
        assertEquals("P-000001", en.party.partyNo) // الأعماق مشتركة كمرجع
    }

    // ───────── ⑤ StatementPrefs — دوران وsmtpConfig (Robolectric) ─────────

    @Test
    fun `SmtpPrefsUi - القيم الافتراضية منشأة مغلقة ومنفذ 465 وتوثيق مفعل وثلاث محاولات`() {
        val ui = SmtpPrefsUi()
        assertFalse(ui.smtpEnabled)
        assertEquals("", ui.host)
        assertEquals(465, ui.port) // defaultPort(SSL_TLS)
        assertEquals("SSL_TLS", ui.security)
        assertTrue(ui.authEnabled)
        assertEquals("", ui.user)
        assertEquals("", ui.pass)
        assertEquals("", ui.from)
        assertEquals("", ui.fromName)
        assertFalse(ui.autoSendEnabled)
        assertEquals(3, ui.autoRetryMax)
    }

    @Test
    fun `securityEnum - الصحيحة والمكتوبة بأحرف صغيرة والمشذبة والغريبة والفارغة والnull`() {
        assertEquals(SmtpSecurity.SSL_TLS, SmtpPrefsUi.securityEnum("SSL_TLS"))
        assertEquals(SmtpSecurity.STARTTLS, SmtpPrefsUi.securityEnum("starttls"))
        assertEquals(SmtpSecurity.NONE, SmtpPrefsUi.securityEnum("  NONE  "))
        assertEquals(SmtpSecurity.SSL_TLS, SmtpPrefsUi.securityEnum(null))
        assertEquals(SmtpSecurity.SSL_TLS, SmtpPrefsUi.securityEnum(""))
        assertEquals(SmtpSecurity.SSL_TLS, SmtpPrefsUi.securityEnum("bogus"))
    }

    @Test
    fun `StatementPrefs - دوران كامل لكل حقول SmtpPrefsUi عبر SharedPreferences مع تشذيب النصوص`() {
        val context = ctx()
        val ui = SmtpPrefsUi(
            smtpEnabled = true,
            host = "  mail.example.com  ",
            port = 2525,
            security = SmtpSecurity.STARTTLS.name,
            authEnabled = true,
            user = " user@x.com ",
            pass = " s3cret ", // كلمة السر لا تُشذَّب عمداً — سلوك موثق
            from = " from@x.com ",
            fromName = " متجر النور ",
            autoSendEnabled = true,
            autoRetryMax = 4
        )
        StatementPrefs.save(context, ui)
        assertEquals(
            SmtpPrefsUi(
                smtpEnabled = true, host = "mail.example.com", port = 2525,
                security = "STARTTLS", authEnabled = true, user = "user@x.com",
                pass = " s3cret ", from = "from@x.com", fromName = "متجر النور",
                autoSendEnabled = true, autoRetryMax = 4
            ),
            StatementPrefs.load(context)
        )
    }

    @Test
    fun `StatementPrefs - الافتراضات عند ملف فارغ والمنفذ يشتق من نمط الأمان`() {
        assertEquals(SmtpPrefsUi(), StatementPrefs.load(ctx()))
    }

    @Test
    fun `StatementPrefs - المنفذ خارج المدى يعود للافتراضي حسب الأمان في الحفظ والقراءة`() {
        val context = ctx()
        // مسار الحفظ: منفذ غير صالح يُستبدل بمنفذ النمط المحفوظ
        StatementPrefs.save(context, SmtpPrefsUi(port = 70_000))
        assertEquals(465, StatementPrefs.load(context).port) // SSL_TLS
        StatementPrefs.save(context, SmtpPrefsUi(port = 0, security = "STARTTLS"))
        assertEquals(587, StatementPrefs.load(context).port) // STARTTLS
        // مسار القراءة: قيمة خام فاسدة في المخزن تُصلح عند load حسب الأمان المخزن
        rawPut { e ->
            e.putString("smtpSecurity", "NONE")
            e.putInt("smtpPort", 999_999)
        }
        assertEquals(25, StatementPrefs.load(context).port) // NONE ⇒ 25
    }

    @Test
    fun `StatementPrefs - سقف إعادة المحاولة يقص بين 1 و5 في الحفظ والقراءة`() {
        val context = ctx()
        StatementPrefs.save(context, SmtpPrefsUi(autoRetryMax = 99))
        assertEquals(5, StatementPrefs.load(context).autoRetryMax)
        StatementPrefs.save(context, SmtpPrefsUi(autoRetryMax = 0))
        assertEquals(1, StatementPrefs.load(context).autoRetryMax)
        // قيمة خام داخل المدى تعبر كما هي؛ وخارج المدى تُقص عند load
        rawPut { it.putInt("autoRetryMax", 2) }
        assertEquals(2, StatementPrefs.load(context).autoRetryMax)
        rawPut { it.putInt("autoRetryMax", -7) }
        assertEquals(1, StatementPrefs.load(context).autoRetryMax)
    }

    @Test
    fun `StatementPrefs - المفاتيح بأسمائها المتعاقد عليها في ملف statement_prefs`() {
        val context = ctx()
        StatementPrefs.save(
            context,
            SmtpPrefsUi(
                smtpEnabled = true, host = "h", port = 1234, security = "NONE",
                authEnabled = false, user = "u", pass = "p", from = "f",
                fromName = "n", autoSendEnabled = true, autoRetryMax = 2
            )
        )
        val p = context.getSharedPreferences("statement_prefs", Context.MODE_PRIVATE)
        assertTrue(p.getBoolean("smtpEnabled", false))
        assertEquals("h", p.getString("smtpHost", null))
        assertEquals(1234, p.getInt("smtpPort", 0))
        assertEquals("NONE", p.getString("smtpSecurity", null))
        assertFalse(p.getBoolean("smtpAuth", true))
        assertEquals("u", p.getString("smtpUser", null))
        assertEquals("p", p.getString("smtpPass", null))
        assertEquals("f", p.getString("smtpFrom", null))
        assertEquals("n", p.getString("smtpFromName", null))
        assertTrue(p.getBoolean("autoSendEnabled", false))
        assertEquals(2, p.getInt("autoRetryMax", 0))
    }

    @Test
    fun `smtpConfig - null عند غير مفعّل وعند مضيف فارغ حتى مع التوثيق مكتمل`() {
        val context = ctx()
        StatementPrefs.save(
            context,
            SmtpPrefsUi(smtpEnabled = false, host = "mail.example.com",
                user = "u@x.com", pass = "p", from = "me@x.com")
        )
        assertNull(StatementPrefs.smtpConfig(context))
        StatementPrefs.save(
            context,
            SmtpPrefsUi(smtpEnabled = true, host = "   ",
                user = "u@x.com", pass = "p", from = "me@x.com")
        )
        assertNull(StatementPrefs.smtpConfig(context))
    }

    @Test
    fun `smtpConfig - null عند كلمة سر ناقصة أو مستخدم فارغ مع توثيق مفعل`() {
        val context = ctx()
        StatementPrefs.save(
            context,
            SmtpPrefsUi(smtpEnabled = true, host = "mail.example.com",
                user = "u@x.com", pass = "", from = "me@x.com")
        )
        assertNull(StatementPrefs.smtpConfig(context)) // مصادقة SmtpConfig ترفض كلمة سر فارغة
        StatementPrefs.save(
            context,
            SmtpPrefsUi(smtpEnabled = true, host = "mail.example.com",
                user = "", pass = "p", from = "me@x.com")
        )
        assertNull(StatementPrefs.smtpConfig(context)) // وترفض اسم مستخدم فارغاً
    }

    @Test
    fun `smtpConfig - عقد صحيح عند الاكتمال ومرسل بديل من المستخدم عند غياب from`() {
        val context = ctx()
        StatementPrefs.save(
            context,
            SmtpPrefsUi(
                smtpEnabled = true, host = " mail.example.com ", port = 465,
                security = "SSL_TLS", authEnabled = true, user = " u@x.com ",
                pass = "p", from = "me@x.com", fromName = " X ", autoRetryMax = 7
            )
        )
        val c = checkNotNull(StatementPrefs.smtpConfig(context)) { "عقد مكتمل يجب أن يبنى" }
        assertEquals("mail.example.com", c.host)      // مشذّب
        assertEquals(465, c.port)
        assertEquals(SmtpSecurity.SSL_TLS, c.security)
        assertEquals("u@x.com", c.username)           // مشذّب
        assertEquals("p", c.password)
        assertEquals("me@x.com", c.fromAddress)
        assertEquals("X", c.fromName)                 // مشذّب
        assertEquals(true, c.authEnabled)
        assertEquals(5, c.maxAttempts)                // 7 مقصوصة إلى السقف 5
        assertEquals(15_000, c.timeoutMs)             // ثابت العقد

        StatementPrefs.save(
            context,
            SmtpPrefsUi(smtpEnabled = true, host = "h.example",
                user = "u@x.com", pass = "p", from = "")
        )
        assertEquals(
            "u@x.com",
            checkNotNull(StatementPrefs.smtpConfig(context)) { "مرسل بديل يجب أن يبنى" }.fromAddress
        )
    }

    @Test
    fun `smtpConfig - يبنى بلا كلمة سر ولا مستخدم حين يكون التوثيق معطلا`() {
        val context = ctx()
        StatementPrefs.save(
            context,
            SmtpPrefsUi(
                smtpEnabled = true, host = "mail.example.com", port = 25,
                security = "NONE", authEnabled = false, user = "", pass = "",
                from = "me@x.com", autoSendEnabled = true, autoRetryMax = 1
            )
        )
        val c = checkNotNull(StatementPrefs.smtpConfig(context)) { "بلا توثيق يجب أن يبنى" }
        assertEquals(false, c.authEnabled)
        assertEquals("", c.username)
        assertEquals("", c.password)
        assertEquals(25, c.port)
        assertEquals(SmtpSecurity.NONE, c.security)
        assertEquals(1, c.maxAttempts)
    }

    // ───────── ④ StatementStyle — العقد النقي للقوالب ─────────

    @Test
    fun `StatementStyle - القيم الافتراضية للطباعة والزوايا والمقاييس والعتامة`() {
        val s = StatementStyle(
            header = HeaderStyle.BOXED, table = TableStyle.ZEBRA, summary = SummaryStyle.BOXED,
            footer = FooterStyle.CLASSIC, primary = 0xFF1976D2.toInt(),
            secondary = 0xFFFFC107.toInt(), text = 0xFF212121.toInt(),
            tableHeadText = 0xFFFFFFFF.toInt(), rowAlt = 0xFFEEEEEE.toInt(),
            headingFontScale = 1.2f, bodyFontScale = 1f, marginDp = 36, spacingDp = 8,
            show = emptySet()
        )
        assertFalse(s.landscape)
        assertEquals(CornerPos.BOTTOM_RIGHT, s.signaturePos)
        assertEquals(CornerPos.BOTTOM_LEFT, s.stampPos)
        assertEquals(1f, s.logoSizeScale, 0f)
        assertEquals(1f, s.stampSizeScale, 0f)
        assertEquals(1f, s.signatureSizeScale, 0f)
        assertEquals(255, s.signatureOpacity)
        assertEquals(255, s.stampOpacity)
        // ملاحظة توثيقية: لا يوجد علم RTL في هذا العقد — الاتجاه على StatementData.isRtl
    }

    @Test
    fun `StatementStyle - الألوان ومجموعة العناصر تضبط وتقرأ كما هي`() {
        val show = setOf(
            PdfElement.LOGO, PdfElement.COMPANY_NAME, PdfElement.TX_TABLE,
            PdfElement.FINAL_BALANCE, PdfElement.QR
        )
        val s = StatementStyle(
            header = HeaderStyle.BAND, table = TableStyle.STRIPED, summary = SummaryStyle.CARDS,
            footer = FooterStyle.QR_RIGHT, primary = 0xFF0D47A1.toInt(),
            secondary = 0xFFD4AF37.toInt(), text = 0xFF111111.toInt(),
            tableHeadText = 0xFFF0F0F0.toInt(), rowAlt = 0xFFF7F7F7.toInt(),
            headingFontScale = 1.1f, bodyFontScale = 0.95f, marginDp = 40, spacingDp = 10,
            landscape = true, show = show
        )
        assertEquals(0xFF0D47A1.toInt(), s.primary)
        assertEquals(0xFFD4AF37.toInt(), s.secondary)
        assertEquals(0xFF111111.toInt(), s.text)
        assertEquals(0xFFF0F0F0.toInt(), s.tableHeadText)
        assertEquals(0xFFF7F7F7.toInt(), s.rowAlt)
        assertEquals(show, s.show)
        assertTrue(s.landscape)
        assertEquals(HeaderStyle.BAND, s.header)
        assertEquals(TableStyle.STRIPED, s.table)
        assertEquals(SummaryStyle.CARDS, s.summary)
        assertEquals(FooterStyle.QR_RIGHT, s.footer)
    }

    @Test
    fun `StatementStyle - النسخ والتعديل لا يمس الأصل عقد بيانات نقي`() {
        val s = StatementStyle(
            header = HeaderStyle.CENTERED, table = TableStyle.OPEN, summary = SummaryStyle.INLINE,
            footer = FooterStyle.THIN, primary = 1, secondary = 2, text = 3,
            tableHeadText = 4, rowAlt = 5, headingFontScale = 1f, bodyFontScale = 1f,
            marginDp = 36, spacingDp = 8, show = setOf(PdfElement.PERIOD)
        )
        val s2 = s.copy(header = HeaderStyle.SIDEBAR, landscape = true,
            show = s.show + PdfElement.QR)
        assertEquals(HeaderStyle.SIDEBAR, s2.header)
        assertTrue(s2.landscape)
        assertEquals(setOf(PdfElement.PERIOD, PdfElement.QR), s2.show)
        assertEquals(HeaderStyle.CENTERED, s.header)
        assertFalse(s.landscape)
        assertEquals(setOf(PdfElement.PERIOD), s.show)
    }

    @Test
    fun `فئات وأنماط القوالب - أعداد القيم الثابتة تعاقديا`() {
        assertEquals(10, TemplateCategory.values().size)
        assertEquals(5, HeaderStyle.values().size)
        assertEquals(5, TableStyle.values().size)
        assertEquals(5, SummaryStyle.values().size)
        assertEquals(5, FooterStyle.values().size)
        assertEquals(4, CornerPos.values().size)
        // سور أسماء الفئات العشر (COR-01..BIL-05 في StatementTemplates مبنية عليها)
        assertEquals(
            setOf(
                "CORPORATE", "PROFESSIONAL", "ACCOUNTING", "MODERN", "MINIMAL",
                "LUXURY", "BUSINESS", "CLASSIC", "ARABIC", "BILINGUAL"
            ),
            TemplateCategory.values().map { it.name }.toSet()
        )
    }

    @Test
    fun `عناصر المستند - عشرون عنصرا بالمفاتيح المتعاقد عليها`() {
        assertEquals(20, PdfElement.values().size)
        val names = PdfElement.values().map { it.name }.toSet()
        for (key in listOf(
            "LOGO", "COMPANY_PHOTO", "COMPANY_NAME", "COMPANY_NO", "TAX_NO", "CR_NO",
            "CONTACTS", "PARTY_INFO", "PERIOD", "TX_TABLE", "OPENING", "TOTAL_DEBIT",
            "TOTAL_CREDIT", "FINAL_BALANCE", "NOTES", "SIGNATURE", "STAMP", "QR",
            "VERIFY_ID", "GENERATED_DATE"
        )) {
            assertTrue("مفتاح غائب: $key", key in names)
        }
    }
}
