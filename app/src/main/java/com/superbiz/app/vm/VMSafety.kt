package com.superbiz.app.vm

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.superbiz.app.core.ErrorCenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * — إصلاح الصنف الأكبر من الأعطال
 *
 * قبل هذا الإصلاح كانت كل كتل `viewModelScope.launch {... }` بلا try/catch،
 * وأي استثناء من طبقة المستودعات (Room IOException، SQLiteException،
 * IllegalArgumentException من require()، امتلاء التخزين...) كان يصل بلا معالجة
 * إلى المعالج الافتراضي للكوروتينات فيُغلق التطبيق فوراً عند الضغط على زر
 * (حفظ/حذف/دفعة/بيع/سداد...).
 *
 * launchSafe يحافظ على نفس السلوك الظاهر للمستخدم (العملية تنجح أو تُهمل)
 * ويمنع إغلاق العملية نهائياً. إعادة رمي CancellationException ضرورية كي لا
 * نكسر آلية إلغاء الكوروتينات عند إتلاف الـ ViewModel.
 *
 * — خرطة خطأ مسجلة + استدعاء ختامي بأسلوب finally
 * - كان الالتقاط الصامت بلا أي تسجيل يخفي الأعطال (لا أثر في Logcat يُذكر) —
 * الآن كل استثناء يُبتلَع يُسجَّل بعنوان واضح.
 * - onDone اختياري يُستدعى دائماً عند خروج الكتلة (نجاحاً أو فشلاً أو إلغاءً)
 * مثل finally — مفيد لمحو أعلام «جارٍ الحفظ» حتى عند فشل غير متوقع.
*/
fun ViewModel.launchSafe(
    onDone: (() -> Unit)? = null,
    block: suspend () -> Unit
) {
    viewModelScope.launch {
        try {
            block()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            // تُبتلع بوعي لمنع انهيار التطبيق — لكن بأثر مسجَّل في Logcat
            // (نفس سياسة بقية مسارات المشروع: Widgets / BackupRepo / ExportDialog)
            // يُبلَّغ مركز الأخطاء الموحّد — يظهر في سجل الإعدادات←متقدم
            // ويُبَثّ لواجهة المستخدم عند وجود رسالة مفهومة للمستخدم
            ErrorCenter.report(e, "VM/${this::class.simpleName}")
            Log.e("SuperBizVM", "launchSafe swallowed an exception", e)
        } finally {
            onDone?.invoke()
        }
    }
}
