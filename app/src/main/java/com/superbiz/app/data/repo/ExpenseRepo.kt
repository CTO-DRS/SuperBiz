package com.superbiz.app.data.repo

import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.Expense
import com.superbiz.app.util.Dates
import kotlinx.coroutines.flow.Flow
import androidx.room.withTransaction

/**
 * : مستودع المصروفات الحقيقي — كل مصروف يسجَّل في جدوله الخاص
 * ويُقيَّد مزدوجاً (مصروفات / نقد) في المعاملة نفسها. حذف المصروف يعكس قيده.
*/
class ExpenseRepo(private val db: AppDatabase, private val ledger: LedgerRepo) {

    companion object {
        const val REF = "expense"

        /** فئات مقترحة — قابلة للتوسع بكتابة فئة جديدة */
        val DEFAULT_CATEGORIES = listOf(
            "إيجار", "رواتب", "كهرباء وماء", "نقل وشحن", "تسويق",
            "صيانة", "مستلزمات", "اتصالات وإنترنت", "ضرائب", "أخرى"
        )
    }

    fun all(): Flow<List<Expense>> = db.expenses().all()

    /** إضافة مصروف حقيقي: سجل + قيد مزدوج ذرّي — [P33-P8] amount قروش Long */
    suspend fun add(amount: Long, category: String, note: String, date: Long = System.currentTimeMillis()): Long {
        // [P33-P8] عتبة 0.005/isFinite حُذفتا — مقارنة صحيحة تامة (Long لا يكون NaN)
        require(amount > 0L) { "amount must be positive" }
        var id = 0L
        db.withTransaction {
            id = db.expenses().insert(
                Expense(amount = amount, category = category.trim(), note = note.trim(), date = date)
            )
            ledger.postInternal(
                com.superbiz.app.domain.AccountingEngine.cashOut(
                    amount, date, note.ifBlank { if (category.isBlank()) "مصروف" else "مصروف: ${category.trim()}" }
                ).copy(refType = REF, refId = id)
            )
        }
        ledger.onMutate?.invoke()
        return id
    }

    /** حذف مصروف مع عكس قيده — ذرّي */
    suspend fun delete(expense: Expense) {
        db.withTransaction {
            ledger.unpostInternal(REF, expense.id)
            db.expenses().delete(expense.id)
        }
        ledger.onMutate?.invoke()
    }

    /** إجمالي مصروفات فترة — [P33-P8] قروش Long (COALESCE(SUM(amount),0) على عمود INTEGER) */
    suspend fun totalBetween(from: Long, to: Long): Long =
        db.expenses().sumBetween(from, to)

    /** إجمالي مصروفات الشهر الحالي — [P33-P8] قروش Long */
    suspend fun monthTotal(now: Long = System.currentTimeMillis()): Long =
        totalBetween(Dates.monthStart(now), now)

    /** توزيع مصروفات الشهر على الفئات (من قاعدة البيانات مباشرة) — [P33-P8] قروش Long */
    suspend fun byCategorySince(from: Long): List<Pair<String, Long>> =
        db.expenses().byCategorySince(from).map { it.category.ifBlank { "أخرى" } to it.total }

    /** مصروفات اليوم — للويدجت والتقارير — [P33-P8] قروش Long */
    suspend fun todayTotal(now: Long = System.currentTimeMillis()): Long =
        totalBetween(Dates.startOfDay(now), now)
}
