package com.superbiz.app

import com.superbiz.app.domain.algo.BankReconcileMath
import com.superbiz.app.domain.algo.BankReconcileMath.BankTx
import com.superbiz.app.domain.algo.BankReconcileMath.BookTx
import org.junit.Assert.assertEquals
import org.junit.Test

class BankImportPlanTest {
    @Test fun outflowsBecomeExpensesInflowsSkipped() {
        val plan = BankReconcileMath.importPlan(listOf(
            BankTx(2, 20000, -5000, " Rent "),
            BankTx(1, 19999, -300, "Fee"),
            BankTx(3, 20001, 9900, "Deposit"),
        ))
        assertEquals(listOf(19999L, 20000L), plan.expenses.map { it.day })
        assertEquals(listOf(300L, 5000L), plan.expenses.map { it.amount })
        assertEquals("Rent", plan.expenses[1].note)
        assertEquals(1, plan.inflowsSkipped)
    }

    @Test fun importedExpenseMatchesOnRerun() {
        val bank = listOf(BankTx(1, 20000, -5000, "Rent"))
        val first = BankReconcileMath.reconcile(bank, emptyList())
        val plan = BankReconcileMath.importPlan(first.bankOnly)
        val book = plan.expenses.mapIndexed { i, e -> BookTx(i + 1L, e.day, -e.amount, e.note) }
        val again = BankReconcileMath.reconcile(bank, book)
        assertEquals(1, again.matched.size)
        assertEquals(0, BankReconcileMath.importPlan(again.bankOnly).expenses.size)
    }
}
