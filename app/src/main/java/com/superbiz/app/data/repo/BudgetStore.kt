package com.superbiz.app.data.repo

import android.content.Context

/**
 * [D4] أهداف المصروفات الشهرية لكل فرع وفئة (قروش). تخزين خفيف بـSharedPreferences — بلا ترحيل قاعدة.
 * المفتاح «b<فرع>|<فئة>». الفرع 0 = كل الفروع/بلا فروع.
 */
class BudgetStore(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("budgets_d4", Context.MODE_PRIVATE)

    fun all(branchId: Long): Map<String, Long> {
        val prefix = "b$branchId|"
        return sp.all.mapNotNull { (k, v) ->
            if (k.startsWith(prefix) && v is Long && v > 0L) k.removePrefix(prefix) to v else null
        }.toMap()
    }

    /** amount ≤ 0 يحذف الهدف */
    fun set(branchId: Long, category: String, amount: Long) {
        val key = "b$branchId|$category"
        if (amount <= 0L) sp.edit().remove(key).apply() else sp.edit().putLong(key, amount).apply()
    }
}
