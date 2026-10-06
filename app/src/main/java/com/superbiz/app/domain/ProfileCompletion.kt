package com.superbiz.app.domain

/**
 * حساب نسبة اكتمال الملف الشخصي — منطق خالص قابل للاختبار ().
 * الأوزان: اسم النشاط 25، الصورة 20، المالك 15، الهاتف 15، العنوان 10، الرقم الضريبي 10، البريد 5 = 100
*/
object ProfileCompletion {

    const val W_BUSINESS = 25
    const val W_AVATAR = 20
    const val W_OWNER = 15
    const val W_PHONE = 15
    const val W_ADDRESS = 10
    const val W_TAX_NUMBER = 10
    const val W_EMAIL = 5

    data class Input(
        val businessName: String = "",
        val avatarSet: Boolean = false,
        val ownerName: String = "",
        val phone: String = "",
        val address: String = "",
        val taxNumber: String = "",
        val email: String = ""
    )

    private fun filled(s: String): Boolean = s.trim().isNotEmpty()

    /** النسبة النهائية 0..100 */
    fun percent(i: Input): Int {
        var p = 0
        if (filled(i.businessName)) p += W_BUSINESS
        if (i.avatarSet) p += W_AVATAR
        if (filled(i.ownerName)) p += W_OWNER
        if (filled(i.phone)) p += W_PHONE
        if (filled(i.address)) p += W_ADDRESS
        if (filled(i.taxNumber)) p += W_TAX_NUMBER
        if (filled(i.email)) p += W_EMAIL
        return p.coerceIn(0, 100)
    }

    /** الحقول الناقصة مرتبة بالوزن الأكبر أولاً — لتلميح «أكمل ملفك» */
    fun missing(i: Input): List<Int> {
        val pairs = mutableListOf<Pair<Int, Int>>() // weight, index
        if (!filled(i.businessName)) pairs += W_BUSINESS to 0
        if (!i.avatarSet) pairs += W_AVATAR to 1
        if (!filled(i.ownerName)) pairs += W_OWNER to 2
        if (!filled(i.phone)) pairs += W_PHONE to 3
        if (!filled(i.address)) pairs += W_ADDRESS to 4
        if (!filled(i.taxNumber)) pairs += W_TAX_NUMBER to 5
        if (!filled(i.email)) pairs += W_EMAIL to 6
        return pairs.sortedByDescending { it.first }.map { it.second }
    }
}
