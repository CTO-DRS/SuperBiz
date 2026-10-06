package com.superbiz.app

import com.superbiz.app.domain.AccountingEngine
import com.superbiz.app.domain.Accounts
import com.superbiz.app.domain.ForecastEngine
import com.superbiz.app.domain.OpenInvoice
import com.superbiz.app.domain.RiskEngine
import com.superbiz.app.util.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AccountingEngineTest {

    private fun dayOf(y: Int, m: Int, d: Int): Long {
        val c = Calendar.getInstance()
        c.set(y, m - 1, d, 12, 0, 0)
        return c.timeInMillis
    }

    // [P33-P8] كل مبالغ الاختبار كانت ريال Double وصارت قروش Long (×100) —
    // والتوازن مساواة تامة (==) بلا أي عتبة فاصلة عائمة (0.01/1e-9/0.005 حُذفت)

    @Test
    fun saleInvoice_draftIsBalanced() {
        val draft = AccountingEngine.saleInvoice(
            subtotal = 100_000L, discount = 5_000L, taxAmount = 14_250L, total = 109_250L,
            cogs = 40_000L, partyId = 1, date = 0L, invoiceId = 1
        ) // [P33-P8] كانت 1000/50/142.5/1092.5/400 ريالاً
        assertTrue(draft.balanced)
        // مدين: ذمم 1092.50 + تكلفة 400 — دائن: مبيعات 950 + ضريبة 142.50 + مخزون 400
        assertEquals(149_250L, draft.totalDebit)
        assertEquals(draft.totalDebit, draft.totalCredit) // [P33-P8] مساواة تامة بلا عتبة
    }

    @Test
    fun saleInvoice_cogsEntriesPresent() {
        val draft = AccountingEngine.saleInvoice(100_000L, 0L, 0L, 100_000L, 40_000L, 1, 0L, 1)
        val hasCogs = draft.lines.any { it.account == Accounts.COGS && it.debit == 40_000L }
        val hasInventory = draft.lines.any { it.account == Accounts.INVENTORY && it.credit == 40_000L }
        assertTrue(hasCogs && hasInventory)
    }

    @Test
    fun purchaseInvoice_inventoryAtNet_inputVatSeparate() {
        // [P6-M2 إصلاح]: ضريبة المدخلات لم تعد ترسمل في المخزون — المخزون بالصافي فقط
        // والضريبة بحساب INPUT_VAT المستقل، والقيد يبقى متوازناً ودين الطرف = الإجمالي
        val draft = AccountingEngine.purchaseInvoice(
            subtotal = 100_000L, discount = 0L, taxAmount = 15_000L, total = 115_000L,
            partyId = 2, date = 0L, invoiceId = 3
        ) // [P33-P8] كانت 1000/0/150/1150 ريالاً
        assertTrue(draft.balanced)
        assertEquals(100_000L, draft.lines.first { it.account == Accounts.INVENTORY }.debit)
        assertEquals(15_000L, draft.lines.first { it.account == Accounts.INPUT_VAT }.debit)
        assertEquals(115_000L, draft.lines.first { it.account == Accounts.PAYABLE }.credit)
        assertEquals(115_000L, draft.totalDebit)
        assertEquals(draft.totalDebit, draft.totalCredit) // [P33-P8] مساواة تامة بلا 1e-9
    }

    @Test
    fun purchaseInvoice_zeroTaxHasNoInputVatLine() {
        val draft = AccountingEngine.purchaseInvoice(50_000L, 0L, 0L, 50_000L, 2, 0L, 4)
        assertTrue(draft.balanced)
        assertTrue(draft.lines.none { it.account == Accounts.INPUT_VAT })
        assertEquals(50_000L, draft.lines.first { it.account == Accounts.INVENTORY }.debit)
    }

    @Test
    fun settleTaxPayable_balancedDoubleEntryWithinBalance() {
        // [P6-M1 إصلاح]: مسار سداد الضريبة المستحقة — مدين TAX_PAYABLE / دائن النقد
        val d = AccountingEngine.settleTaxPayable(amount = 30_000L, date = 0L, taxPayableBalance = 45_000L)
        assertTrue(d.balanced)
        assertEquals(Accounts.TAX_PAYABLE, d.lines[0].account)
        assertEquals(30_000L, d.lines[0].debit)
        assertEquals(Accounts.CASH, d.lines[1].account)
        assertEquals(30_000L, d.lines[1].credit)
    }

    @Test(expected = IllegalArgumentException::class)
    fun settleTaxPayable_rejectsAmountAboveBalance() {
        AccountingEngine.settleTaxPayable(50_000L, 0L, taxPayableBalance = 45_000L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun settleTaxPayable_rejectsNonPositiveAmount() {
        AccountingEngine.settleTaxPayable(0L, 0L, taxPayableBalance = 45_000L)
    }

    @Test
    fun receipt_balances() {
        val d = AccountingEngine.receipt(1, 50_000L, 0L)
        assertTrue(d.balanced)
        assertEquals(Accounts.CASH, d.lines[0].account)
        assertEquals(50_000L, d.lines[0].debit)
    }

    @Test
    fun trialBalance_groupsCorrectly() {
        val grouped = mapOf(
            Accounts.CASH to (50_000L to 10_000L),
            Accounts.RECEIVABLE to (100_000L to 0L)
        )
        val rows = AccountingEngine.trialBalance(grouped)
        assertEquals(2, rows.size)
        val cash = rows.first { it.account == Accounts.CASH }
        assertEquals(40_000L, cash.balance)
        assertTrue(cash.isDebitNature)
    }

    @Test
    fun incomeStatement_math() {
        val ist = com.superbiz.app.domain.IncomeStatement(
            revenue = 1_000_000L, otherIncome = 20_000L, cogs = 400_000L, expenses = 250_000L
        ) // [P33-P8] كانت 10000/200/4000/2500 ريالاً — الحقول صارت Long
        assertEquals(600_000L, ist.grossProfit)
        assertEquals(370_000L, ist.netProfit)
        // margin نسبة مئوية — تبقى Double على القروش
        assertEquals(370_000.0 / 1_020_000.0, ist.margin, 0.0001)
    }

    @Test
    fun checkBounce_reverses() {
        val cleared = AccountingEngine.checkClearedIn(1, 30_000L, 0L, 1)
        val bounced = AccountingEngine.checkBouncedIn(1, 30_000L, 0L, 1)
        assertTrue(cleared.balanced && bounced.balanced)
        // الارتجاع عكس تماماً
        assertEquals(cleared.lines[0].account, bounced.lines[1].account)
        assertEquals(cleared.lines[1].account, bounced.lines[0].account)
    }
}

class RiskEngineTest {

    private val today: Long = 1_700_000_000_000L

    @Test
    fun noOverdue_isLowRisk() {
        val open = listOf(
            OpenInvoice(1, 1, 500.0, today - 5L * 86_400_000, today + 10L * 86_400_000, true)
        )
        val r = RiskEngine.score(open, totalInvoiced = 2000.0, totalPaid = 1500.0, avgPayGapDays = 0.0, today = today)
        assertTrue(r.score < 35)
        assertEquals(RiskEngine.LEVEL_LOW, r.level)
    }

    @Test
    fun longOverdue_isHighRisk() {
        val open = listOf(
            OpenInvoice(1, 1, 1900.0, today - 150L * 86_400_000, today - 90L * 86_400_000, true)
        )
        val r = RiskEngine.score(open, totalInvoiced = 2000.0, totalPaid = 100.0, avgPayGapDays = 60.0, today = today)
        assertTrue("score=${r.score}", r.score >= 65)
        assertEquals(RiskEngine.LEVEL_HIGH, r.level)
        assertEquals(90, r.worstOverdueDays)
    }

    @Test
    fun emptyIsSafe() {
        val r = RiskEngine.score(emptyList(), 0.0, 0.0, null, today)
        assertEquals(0, r.score)
    }

    @Test
    fun oneHourOverdueCountsAsOneDay() {
        // [P6-M6 إصلاح]: توحيد دلالة «متأخر» مع daysOverdue — تأخير ساعة = يوم واحد (ceil)
        // بينما كان floor يجعله صفراً فتتناقض نقاط الخطر مع كشف «متأخر» وسلات الأعمار
        val open = listOf(
            OpenInvoice(1, 1, 500.0, today - 5L * 86_400_000, today - 3_600_000L, true)
        )
        val r = RiskEngine.score(open, totalInvoiced = 1000.0, totalPaid = 0.0, avgPayGapDays = null, today = today)
        assertEquals(1, r.worstOverdueDays)
    }
}

class ForecastEngineTest {

    private val today: Long = 1_700_000_000_000L
    private val day = 86_400_000L

    @Test
    fun forecast_distributesWithinHorizon() {
        val open = listOf(
            OpenInvoice(1, 1, 1000.0, today - 10 * day, today + 3 * day, true),
            OpenInvoice(2, 1, 2000.0, today - 5 * day, today + 10 * day, true)
        )
        val receipts = (1..30).map { (today - it * day) to 100.0 }
        val fc = ForecastEngine.forecast(open, receipts, invoicedTotal = 5000.0, paidTotal = 3000.0, today = today)
        // كل التوزيع داخل 30 يوماً
        assertTrue(fc.dailyBuckets.size == 30)
        assertTrue(fc.next30 >= fc.next7)
        assertTrue(fc.next30 > 0)
        // fill rate بين 0.05 و1
        assertTrue(fc.fillRate in 0.05..1.0)
    }

    @Test
    fun trend_upward() {
        // متزايد: كل أسبوع أكبر
        val receipts = mutableListOf<Pair<Long, Double>>()
        for (w in 0 until 8) {
            for (d in 0 until 7) {
                receipts.add((today - (w * 7 + d) * day) to ((8 - w) * 100.0))
            }
        }
        val trend = ForecastEngine.linearTrendPerWeek(receipts, today)
        assertTrue("trend=$trend", trend > 0)
    }

    @Test
    fun overdueInvoice_discounted() {
        val open = listOf(OpenInvoice(1, 1, 1000.0, today - 20 * day, today - 5 * day, true))
        val fc = ForecastEngine.forecast(open, emptyList(), 1000.0, 100.0, today)
        // المتأخرة تُحصّل بنسبة 0.6 × fillRate
        assertTrue(fc.next30 <= 1000.0)
    }
}

class MoneyTest {

    @Test
    fun format_basic() {
        assertEquals("2,670 ر.س", Money.format(2670.0, "ر.س"))
        assertEquals("-1,230 ر.س", Money.format(-1230.0, "ر.س"))
        assertEquals("1,440.50 ر.س", Money.format(1440.5, "ر.س"))
    }

    @Test
    fun parse_handlesSeparators() {
        assertEquals(1234.5, Money.parse("1,234.5")!!, 0.001)
        assertEquals(99.0, Money.parse("99")!!, 0.001)
        assertEquals(null, Money.parse("abc"))
    }

    @Test
    fun round2() {
        // (M-2.2 توحيد): العقد الموثق تقريب القيمة العشرية الحقيقية (2.675 → 2.68)
        // [P20-FIX agent10/13]: BigDecimal(v.toString()) ينفّذ العقد فعلاً — كان BigDecimal(v)
        // يغلّف التمثيل الثنائي (10.555 = 10.554999…) فيخالف الوثيقة ويعطي 10.55
        assertEquals(10.56, Money.round2(10.555), 0.0001)
        assertEquals(2.68, Money.round2(2.675), 0.0001)
        assertEquals(0.3, Money.round2(0.1 + 0.2), 0.0001)
    }

    @Test
    fun ean13Checksum() {
        // 4006381333931 معروف
        assertEquals(1, com.superbiz.app.util.BarcodeGen.ean13Checksum("400638133393"))
    }
}
