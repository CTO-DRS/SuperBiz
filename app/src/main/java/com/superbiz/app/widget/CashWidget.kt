package com.superbiz.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.superbiz.app.util.Money
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** منطق خالص لحساب حالة النقد في الصندوق — قابل للاختبار وحداتياً بلا أي اعتماد على Android
 *  [P33-P8] قروش Long — المقارنات تامة بلا عتبات 0.004 والصافي طرح صحيح */
object CashMath {

    /** حالة الرصيد: 1 = فيه نقد، 0 = صندوق فارغ، -1 = عجز (سحب أكثر من الإيداع) */
    fun state(balance: Long): Int = when {
        balance > 0L -> 1
        balance < 0L -> -1
        else -> 0
    }

    /** صافي حركة النقد لليوم: الدخل ناقص الخرج (طرح صحيح تام) */
    fun netToday(income: Long, outcome: Long): Long = income - outcome

    /** هل وقعت أي حركة نقدية في اليوم؟ (لإخفاء الشرائح في اليوم الساكن) */
    fun hasMovement(income: Long, outcome: Long): Boolean =
        income != 0L || outcome != 0L
}

/**
 * — الويدجت الثالث «النقد في الصندوق»
 *
 * • يقرأ الرصيد فعلياً من حساب «النقد في الصندوق» (1010) في دفتر الأستاذ
 * عبر ReportsRepo.cashBalance() — مجموع (مدين − دائن) من القيد المزدوج — بلا بيانات وهمية
 * • يعرض: الرصيد الكبير + شريحتا «دخل اليوم» و«خرج اليوم» من سلسلة التدفق النقدي الفعلية
 * • التحديث رباعي: كل 30 دقيقة + فور أي قيد محاسبي (WidgetSync) + عند العودة للتطبيق + زر تحديث يدوي
 * • النقر يفتح التطبيق على تبويب التقارير (بعد القفل إن كان مفعلاً)
*/
class CashWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pr = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                WidgetSync.renderCashAll(context.applicationContext, appWidgetManager, appWidgetIds)
            } catch (_: Exception) {
            } finally {
                pr.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == WidgetSync.ACTION_REFRESH_CASH) {
            val app = context.applicationContext
            // [P6-M40 إصلاح]: زر التحديث يرسَم هذه الويدجة وحدها — لا إعادة رسم للأربعة جميعاً
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                WidgetSync.refreshOne(app, CashWidgetProvider::class.java, id)
            } else {
                WidgetSync.push(app, immediate = true)
            }
        }
    }
}
