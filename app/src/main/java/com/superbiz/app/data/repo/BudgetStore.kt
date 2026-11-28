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

    /** هدف سنوي صريح لسنة معينة (يتقدّم على الشهري × 12). المفتاح «y<سنة>|b<فرع>|<فئة>» */
    fun allYear(branchId: Long, year: Int): Map<String, Long> {
        val prefix = "y$year|b$branchId|"
        return sp.all.mapNotNull { (k, v) ->
            if (k.startsWith(prefix) && v is Long && v > 0L) k.removePrefix(prefix) to v else null
        }.toMap()
    }

    /** amount ≤ 0 يحذف الهدف السنوي (فيعود التوقع إلى الشهري × 12) */
    fun setYear(branchId: Long, year: Int, category: String, amount: Long) {
        val key = "y$year|b$branchId|$category"
        if (amount <= 0L) sp.edit().remove(key).apply() else sp.edit().putLong(key, amount).apply()
    }

    private fun isBackupKey(k: String) = ('|' in k) && (k.startsWith("b") || (k.startsWith("y") && k.count { it == '|' } >= 2))

    /** للنسخ الاحتياطي: {"b<فرع>|<فئة>": قروش} */
    fun dump(): org.json.JSONObject = org.json.JSONObject().also { o ->
        sp.all.forEach { (k, v) -> if (isBackupKey(k) && v is Long && v > 0L) o.put(k, v) }
    }

    /** استعادة تستبدل الأهداف الحالية بما في النسخة */
    fun restore(o: org.json.JSONObject) {
        val e = sp.edit().clear()
        o.keys().forEach { k -> if (isBackupKey(k)) { val v = o.optLong(k, 0L); if (v > 0L) e.putLong(k, v) } }
        e.apply()
    }
}
