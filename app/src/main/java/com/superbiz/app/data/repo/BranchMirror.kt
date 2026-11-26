package com.superbiz.app.data.repo

import com.superbiz.app.data.db.AppDatabase

/**
 * [A3] جسر المستويين: كل تغيير في products.stockQty (عقد v18) ينعكس بنفس المقدار على
 * branch_stock للفرع النشط — فلا «مخزون شبح» بين المستوى الكلي ومستوى الفروع.
 *
 * يُستدعى حصراً داخل معاملة المستدعي (ذرّية مع الحركة والقيد). الفرع النشط الآن = أول فرع نشط
 * بالمعرّف (المخزن الرئيسي المبذور) إلى أن يصل مبدّل «الفرع الحالي» (A4). بلا فروع نشطة = لا شيء
 * (سلوك v18 حرفياً). الكمية قد تصير سالبة عند بيع ما فوق رصيد الفرع — صدق محاسبي: التحويل يرفضها
 * أصلاً (have >= q) والفرق يظهر ولا يُخفى بقصّ صامت.
 */
internal suspend fun AppDatabase.mirrorToActiveBranch(productId: Long, delta: Double) {
    if (!delta.isFinite() || delta == 0.0) return
    val id = resolveBranchId()
    if (id == 0L) return
    branchStock().addQty(id, productId, delta)
}

/** [A4] «الفرع الحالي» — يُحمَّل من الإعدادات عند الإقلاع ويحدَّث عند التبديل (0 = تلقائي: أول فرع نشط) */
object CurrentBranch {
    @Volatile var id: Long = 0L
}

/** الفرع الفعّال للكتابة: المختار إن كان نشطاً، وإلا أول فرع نشط، وإلا 0 (بلا فروع = سلوك v18) */
internal suspend fun AppDatabase.resolveBranchId(): Long {
    val active = branches().activeOnce()
    val want = CurrentBranch.id
    return active.firstOrNull { it.id == want }?.id ?: active.firstOrNull()?.id ?: 0L
}
