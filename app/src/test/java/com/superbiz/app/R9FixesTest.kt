package com.superbiz.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.CheckEntity
import com.superbiz.app.data.db.Invoice
import com.superbiz.app.data.db.InvoiceItem
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.db.Product
import com.superbiz.app.data.repo.ChecksRepo
import com.superbiz.app.data.repo.InventoryRepo
import com.superbiz.app.data.repo.InvoiceRepo
import com.superbiz.app.data.repo.LedgerRepo
import com.superbiz.app.domain.PosCart
import com.superbiz.app.domain.PosCartLine
import com.superbiz.app.domain.ReportSummaryText
import com.superbiz.app.domain.ShoppingList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * — اختبارات انحدار للإصلاحات العشرين
 * كل إصلاح له اختبار يفشل قبل الإصلاح وينجح بعده (ما أمكن بلا Android UI).
*/
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class R9FixesTest {

    private lateinit var db: AppDatabase
    private lateinit var ledger: LedgerRepo
    private lateinit var invoices: InvoiceRepo
    private lateinit var inventory: InventoryRepo
    private lateinit var checks: ChecksRepo

    @Before
    fun setup() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        ledger = LedgerRepo(db)
        invoices = InvoiceRepo(db)
        inventory = InventoryRepo(db, ledger)
        checks = ChecksRepo(db, ledger)
    }

    @After
    fun tearDown() { db.close() }

    // [P33-P8] أسعار المنتج والفاتورة قروش Long (10.0 ريال = 1000L) — الكمية تبقى Double
    private suspend fun seedSaleInvoiceWithStock(cost: Long, price: Long, qty: Double): Invoice {
        val pid = inventory.saveProduct(Product(name = "صنف", costPrice = cost, salePrice = price, stockQty = 100.0))
        val partyId = db.parties().upsert(Party(name = "عميل"))
        val id = invoices.save(
            Invoice(partyId = partyId, number = "INV-0001", date = System.currentTimeMillis(), dueDate = System.currentTimeMillis(),
                // [P33-P8] قروش: round(كمية × سعر القروش) — نفس اشتقاق InvoiceItem.lineTotal بلا فاصلة عائمة مالية
                subtotal = Math.round(qty * price), total = Math.round(qty * price)),
            listOf(InvoiceItem(invoiceId = 0, productId = pid, desc = "صنف", qty = qty, unitPrice = price))
        )
        return db.invoices().byId(id)!!
    }

    // ————— C11: حرس إعادة الإلغاء — الإلغاء المزدوج لا يعكس المخزون مرتين —————
    @Test
    fun `double void does not double-reverse stock`() = runBlocking {
        val inv = seedSaleInvoiceWithStock(cost = 1_000L, price = 2_500L, qty = 5.0)   // [P33-P8] 10.0/25.0 ريال → قروش
        val pid = db.invoiceItems().forInvoice(inv.id).first().productId!!
        val stockBefore = db.products().byId(pid)!!.stockQty

        invoices.voidInvoice(inv)      // إلغاء أول: يعيد 5 للمخزون
        val afterFirst = db.products().byId(pid)!!.stockQty
        invoices.voidInvoice(inv)      // إلغاء ثانٍ: يجب أن يكون بلا أثر
        val afterSecond = db.products().byId(pid)!!.stockQty

        assertEquals(stockBefore + 5.0, afterFirst, 1e-9)
        assertEquals(afterFirst, afterSecond, 1e-9)
        // قيد واحد فقط للإلغاء — بلا قيد مكرر
        val moves = db.stockMoves().allMoves().count { it.refType == "void" }
        assertEquals(1, moves)
    }

    // ————— C19: حراسة مستودع الشيكات —————
    @Test
    fun `checks repo rejects invalid checks`() = runBlocking {
        val partyId = db.parties().upsert(Party(name = "طرف"))
        val now = System.currentTimeMillis()
        // [P33-P8] المبالغ قروش: 100.0 ريال = 10_000L، وسالب القروش يبقى مرفوضاً بحدّ amount > 0
        val ok = checks.save(CheckEntity(number = "1", partyId = partyId, amount = 10_000L, issueDate = now, dueDate = now + 1))
        assertTrue(ok > 0)
        var rejected = 0
        try { checks.save(CheckEntity(number = "2", partyId = partyId, amount = -500L, issueDate = now, dueDate = now + 1)) } catch (e: IllegalArgumentException) { rejected++ }
        try { checks.save(CheckEntity(number = "3", partyId = partyId, amount = 1_000L, issueDate = now + 10, dueDate = now)) } catch (e: IllegalArgumentException) { rejected++ }
        try { checks.save(CheckEntity(number = " ", partyId = partyId, amount = 1_000L, issueDate = now, dueDate = now + 1)) } catch (e: IllegalArgumentException) { rejected++ }
        assertEquals(3, rejected)
    }

    // ————— C10: استعلام مفلتر بالتاريخ يعادل الفلترة اليدوية —————
    @Test
    fun `saleInvoicesSince matches manual filter`() = runBlocking {
        val inv = seedSaleInvoiceWithStock(cost = 1_000L, price = 2_500L, qty = 2.0)   // [P33-P8] قروش
        val now = System.currentTimeMillis()
        val since = db.invoices().saleInvoicesSince(now - 60_000L)
        assertEquals(1, since.size)
        assertEquals(inv.id, since.first().id)
        assertEquals(0, db.invoices().saleInvoicesSince(now + 60_000L).size)
    }

    // ————— C16: نص قائمة التسوّج بعناوين موطَّنة —————
    @Test
    fun `shopping list localized labels`() {
        val rows = listOf(ShoppingList.Row("سكر", "كيس", 2.0, 10.0))
        val en = ShoppingList.build(rows, ShoppingList.Labels(
            title = "Shopping list", available = "Available", suggested = "Suggested to buy",
            count = "Items: %1\$d", note = "Note text",
        ))
        assertTrue(en.contains("Shopping list"))
        assertTrue(en.contains("Available: 2"))
        assertTrue(en.contains("Items: 1"))
        // الافتراضي (العربي) يبقى سليماً للتوافق
        val ar = ShoppingList.build(rows)
        assertTrue(ar.contains("قائمة التسوّج الشرائية"))
        assertTrue(ShoppingList.build(emptyList()).isEmpty())
    }

    // ————— C17: ملخص التقرير بعناوين موطَّنة —————
    @Test
    fun `report summary localized labels`() {
        val en = ReportSummaryText.build(
            sales = 100.0, expenses = 30.0, profit = 70.0, debts = 20.0,
            periodLabel = "Last month", currency = "SAR",
            labels = ReportSummaryText.Labels(
                title = "Report summary — %1\$s", sales = "Sales", expenses = "Expenses",
                profit = "Net profit", debts = "Open customer debts",
            )
        )
        assertTrue(en.contains("Report summary — Last month"))
        assertTrue(en.contains("Sales: 100 SAR"))
        assertTrue(en.contains("Net profit: 70 SAR"))
        val ar = ReportSummaryText.build(100.0, 30.0, 70.0, 20.0, "شهري", "SAR")
        assertTrue(ar.contains("ملخص التقرير — شهري"))
    }

    // ————— C18: تثبيت كميات السلة عند المتاح —————
    @Test
    fun `pos cart setQty caps at stock`() {
        // [P33-P8] السعر/التكلفة قروش (10.0/5.0 ريال → 1000/500 قروشاً) — الكمية والمخزون تبقى Double
        var lines = listOf(PosCartLine(productId = 1L, name = "صنف", unitPrice = 1_000L, costPrice = 500L, qty = 1.0, stock = 3.0))
        lines = PosCart.setQty(lines, 1L, 99.0)
        assertEquals(3.0, lines.first().qty, 1e-9)
        lines = PosCart.setQty(lines, 1L, 2.0)
        assertEquals(2.0, lines.first().qty, 1e-9)
    }
}
