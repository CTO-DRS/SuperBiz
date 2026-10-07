package com.superbiz.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.repo.ReportsRepo.ProductSales
import com.superbiz.app.domain.IncomeStatement
import com.superbiz.app.domain.TrialRow
import com.superbiz.app.ui.screens.buildReportSheets
import com.superbiz.app.util.Money
import com.superbiz.app.vm.ReportsVM.ReportsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [تدقيق H-5] اختبار round-trip لتوحيد الوحدات في تصدير XLSX:
 * عقد P33-P8 يعلن كل خلايا XLSX المالية بالريال (Double عبر Money.fromPiasters)،
 * لكن خمس مجموعات خلايا كانت تُكتب قروشاً خاماً (100x أكبر):
 * النقد، الذمم المفتوحة، أفضل العملاء، أفضل المنتجات، دلاء أعمار الديون.
 *
 * الاختبار يبني ReportsData بقروش معلومة ويصرّح كل خلية مالية في كل الأوراق —
 * كان يفشل قبل الإصلاح (قروش خام)، والنجاح بعده = الملف متسق الوحدات حرفياً.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReportsXlsxUnitsH5Test {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun s(id: Int) = ctx.getString(id)

    private fun data() = ReportsData(
        income = IncomeStatement(
            revenue = 10_000_000L,   // 100,000.00 ريال
            otherIncome = 0L,
            cogs = 4_000_000L,
            expenses = 2_000_000L    // الصافي 40,000.00 ريال
        ),
        cash = 12_345L,              // 123.45 ريال — كانت خاماً (H-5)
        aging = listOf(100_000L, 200_000L, 50_000L, 25_000L), // 1,000/2,000/500/250 ريال
        topCustomers = listOf(
            "أحمد" to 500_000L,      // 5,000.00 ريال — كان خاماً (H-5)
            "سارة" to 300_000L       // 3,000.00 ريال
        ),
        topProducts = listOf(
            ProductSales("منتج أ", 2.0, 70_000L),   // 700.00 ريال — كان خاماً (H-5)
            ProductSales("منتج ب", 1.0, 30_000L)    // 300.00 ريال
        ),
        trial = listOf(
            TrialRow("1100", "ذمم العملاء", "Receivables", debit = 800_000L, credit = 300_000L)
            // الرصيد 500,000 قروش = 5,000.00 ريال
        )
    )

    @Test
    fun all_monetary_cells_are_riyals_across_sheets() {
        val sheets = buildReportSheets(ctx, data(), days = 30, symbol = "ر.س")
        assertTrue("خمس أوراق على الأقل", sheets.size >= 5)

        fun sheet(id: Int) = sheets.first { it.name == s(id) }

        // ── ورقة الملخص: النقد والذمم المفتوحة كانا قروشاً خاماً ──
        val summary = sheet(R.string.rep_sheet_summary)
        val cashRow = summary.rows.first { it.first() == s(R.string.kpi_cash) }
        assertEquals("النقد ريال لا قروش (H-5)", 123.45, cashRow[1])
        val debtsRow = summary.rows.first { it.first() == s(R.string.rep_debts_open) }
        assertEquals("الذمم المفتوحة = مجموع الدلاء بالريال (H-5)",
            Money.fromPiasters(375_000L), debtsRow[1])

        // ── ورقة أفضل العملاء ──
        val customers = sheet(R.string.rep_sheet_customers)
        assertEquals(5_000.00, customers.rows[0][1])
        assertEquals(3_000.00, customers.rows[1][1])

        // ── ورقة أفضل المنتجات (عمود الإجمالي؛ الكمية تبقى كمية) ──
        val products = sheet(R.string.rep_sheet_products)
        assertEquals(2.0, products.rows[0][1])
        assertEquals(700.00, products.rows[0][2])
        assertEquals(300.00, products.rows[1][2])

        // ── ورقة أعمار الديون: كل دلو ريال لا قروش ──
        val aging = sheet(R.string.rep_sheet_aging)
        assertEquals(Money.fromPiasters(100_000L), aging.rows[0][1])
        assertEquals(Money.fromPiasters(200_000L), aging.rows[1][1])
        assertEquals(Money.fromPiasters(50_000L), aging.rows[2][1])
        assertEquals(Money.fromPiasters(25_000L), aging.rows[3][1])
    }

    @Test
    fun summary_consistency_revenue_matches_profit_identity_in_riyals() {
        // اتساق داخلي بهوية قائمة ال доход: الصافي = الإيراد + الأخرى − تكلفة المبيعات − المصروفات
        // (كانت الوحدات مخلوطة فلا هوية ممكنة داخل الملف نفسه)
        val sheets = buildReportSheets(ctx, data(), days = 30, symbol = "ر.س")
        val rows = sheets.first().rows.associate { it[0] to it[1] }
        val revenue = rows[s(R.string.rep_metric_sales)] as Double
        val expenses = rows[s(R.string.kpi_expenses)] as Double
        val profit = rows[s(R.string.rep_metric_profit)] as Double
        // data(): إيراد 100,000 / تكلفة 40,000 / مصروفات 20,000 ⇒ صافي 40,000
        assertEquals(revenue - Money.fromPiasters(4_000_000L) - expenses, profit, 1e-9)
    }

    @Test
    fun trial_balance_rows_are_riyals() {
        val sheets = buildReportSheets(ctx, data(), days = 30, symbol = "ر.س")
        val trial = sheets.first { it.name == s(R.string.rep_sheet_trial) }
        val row = trial.rows.first { it[0] == "ذمم العملاء" }
        // عقد ورقة الميزان: [الاسم، الرصيد بالريال، الطرف المقابل إن كان سالباً وإلا 0]
        // مدين 800,000 − دائن 300,000 = رصيد 500,000 قروش = 5,000.00 ريال
        assertEquals(5_000.00, row[1])
        assertEquals(0.0, row[2])
    }
}
