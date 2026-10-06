package com.superbiz.app.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * — إصلاح أعطال "إغلاق التطبيق فور الضغط"
 *
 * فتح Intent بأمان كامل. سابقاً كان startActivity يُستدعى مباشرة لأزرار
 * واتساب/رسائل SMS/فتح روابط، وعلى الأجهزة التي لا يوجد بها تطبيق رسائل
 * (أجهزة لوحية/أجهزة معطلة تطبيقاتها) أو متصفح، كان النظام يرمي
 * ActivityNotFoundException فتُغلق العملية فوراً مع إشعار الأعطال.
 *
 * ملاحظة: startActivity معفى من قيود ظهور الحزم (Package Visibility) في
 * Android 11+ لذا نعتمد try/catch فقط ولا نستخدم resolveActivity (يعيد
 * null خطأً للتطبيقات غير المعلنة في <queries>).
*/
fun startIntentSafe(
    context: Context,
    intent: Intent,
    errorRes: Int = com.superbiz.app.R.string.no_app_found
): Boolean = try {
    context.startActivity(intent)
    true
} catch (e: ActivityNotFoundException) {
    Toast.makeText(context, context.getString(errorRes), Toast.LENGTH_SHORT).show()
    false
} catch (e: Exception) {
    // أي فشل آخر (SecurityException من إعدادات الأجهزة المقيدة مثلاً) لا يغلق التطبيق
    Toast.makeText(context, context.getString(errorRes), Toast.LENGTH_SHORT).show()
    false
}
