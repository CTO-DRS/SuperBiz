package com.superbiz.app.domain.algo

import com.superbiz.app.domain.algo.BankReconcileMath.BankTx
import com.superbiz.app.domain.algo.BankReconcileMath.BookTx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BankReconcileMathTest {
    @Test fun amounts() {
        assertEquals(123456L, BankReconcileMath.parseAmount("1,234.56"))
        assertEquals(-1250L, BankReconcileMath.parseAmount("(12.50)"))
        assertEquals(-1250L, BankReconcileMath.parseAmount("-12.5"))
        assertEquals(12345L, BankReconcileMath.parseAmount("١٢٣٫٤٥"))
        assertEquals(500L, BankReconcileMath.parseAmount("5"))
        assertNull(BankReconcileMath.parseAmount("abc"))
        assertNull(BankReconcileMath.parseAmount(""))
    }

    @Test fun days() {
        assertEquals(java.time.LocalDate.of(2026, 3, 15).toEpochDay(), BankReconcileMath.parseDay("2026-03-15"))
        assertEquals(java.time.LocalDate.of(2026, 3, 15).toEpochDay(), BankReconcileMath.parseDay("15/03/2026"))
        assertNull(BankReconcileMath.parseDay("garbage"))
        assertNull(BankReconcileMath.parseDay("2026-13-01"))
    }

    @Test fun statementWithAmountColumn() {
        val p = BankReconcileMath.parseStatement("\uFEFFDate,Description,Amount\r\n2026-03-01,\"Rent, March\",-5000.00\r\n2026-03-02,Sale,1200.50\r\nbad,row,x\r\n")!!
        assertEquals(2, p.txs.size)
        assertEquals(1, p.skipped)
        assertEquals(-500000L, p.txs[0].amount)
        assertEquals("Rent, March", p.txs[0].description)
        assertEquals(120050L, p.txs[1].amount)
    }

    @Test fun statementWithDebitCreditColumns() {
        val p = BankReconcileMath.parseStatement("التاريخ,البيان,مدين,دائن\n2026-03-01,إيجار,100.00,\n2026-03-02,بيع,,40.00\n")!!
        assertEquals(listOf(-10000L, 4000L), p.txs.map { it.amount })
    }

    @Test fun unknownHeaderIsNull() {
        assertNull(BankReconcileMath.parseStatement("a,b\n1,2\n"))
        assertNull(BankReconcileMath.parseStatement(""))
    }

    @Test fun matchingIsOneToOneNearestDate() {
        val bank = listOf(BankTx(0, 10, 100, "a"), BankTx(1, 12, 100, "b"), BankTx(2, 30, 777, "c"))
        val book = listOf(BookTx(1, 11, 100, "x"), BookTx(2, 50, 999, "y"))
        val r = BankReconcileMath.reconcile(bank, book)
        assertEquals(1, r.matched.size)
        assertEquals(0, r.matched[0].bank.index)
        assertEquals(listOf(1, 2), r.bankOnly.map { it.index })
        assertEquals(listOf(2L), r.bookOnly.map { it.id })
    }

    @Test fun toleranceBoundary() {
        val r = BankReconcileMath.reconcile(listOf(BankTx(0, 10, 5, "")), listOf(BookTx(1, 14, 5, "")), 3)
        assertEquals(0, r.matched.size)
        val r2 = BankReconcileMath.reconcile(listOf(BankTx(0, 10, 5, "")), listOf(BookTx(1, 13, 5, "")), 3)
        assertEquals(1, r2.matched.size)
    }

    @Test fun differenceIsZeroWhenExplained() {
        // الدفتر 1000، الكشف 1070: رسوم بنكية -30 لم تُسجَّل، وإيداع معلق +100 في الدفتر فقط، وفائدة +0 …
        // الكشف = الدفتر + (كشف فقط) − (دفتر فقط) = 1000 + (-30) − (+100)؟ لا: نبني الأرقام صراحة
        val bank = listOf(BankTx(0, 1, 500, "dep"), BankTx(1, 2, -30, "fee"))
        val book = listOf(BookTx(1, 1, 500, "dep"), BookTx(2, 3, 100, "pending deposit"))
        val r = BankReconcileMath.reconcile(bank, book)
        // رصيد الدفتر 600 (500+100)، رصيد الكشف 470 (500-30)
        assertEquals(0L, BankReconcileMath.remainingDifference(600, 470, r))
        assertNotNull(r.bankOnly.firstOrNull())
    }
}
