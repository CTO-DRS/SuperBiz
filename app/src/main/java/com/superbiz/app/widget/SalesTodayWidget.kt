package com.superbiz.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.superbiz.app.data.db.Invoice

/** منطق خالص لحساب مبيعات يوم محدد — قابل للاختبار وحداتياً بلا أي اعتماد على Android */
object SalesMath {

    /**
     * مبيعات يوم يبدأ من طابعه الزمني dayStart:
     * (إجمالي المبيعات، عدد الفواتير) من فواتير البيع النشطة (غير الملغاة) فقط.
     * نهاية اليوم تُحسب تلقائياً +86,399,999 ملّي ثانية.
     * [P33-P8] الإجمالي قروش Long — مجموع صحيح مباشر من الفواتير المخزنة قروش
     */
    fun todaySales(invoices: List<Invoice>, dayStart: Long): Pair<Long, Int> {
        val dayEnd = dayStart + 86_399_999L
        val todays = invoices.filter { it.isSale && it.status < 3 && it.date in dayStart..dayEnd }
        return todays.sumOf { it.total } to todays.size
    }

    /** ربح البيع الإجمالي لفواتير محددة: الإيراد ناقص تكلفة البضاعة المباعة — [P33-P8] قروش بمساواة صحيحة */
    fun grossProfit(revenue: Long, cogs: Long): Long = revenue - cogs
}

/**
 * — ويدجت «مبيعات اليوم» الشاشة الرئيسية (الويدجت الثاني)
 *
 * • يقرأ فعلياً من فواتير البيع والقيد المزدوج — بلا بيانات وهمية
 * • يعرض: إجمالي مبيعات اليوم + عدد الفواتير + صافي ربح البيع (الإيراد − تكلفة المبيعات)
 * • التحديث رباعي: كل 30 دقيقة + فور حفظ/إلغاء أي فاتورة (InvoiceRepo.onMutate → WidgetSync)
 * + عند العودة للتطبيق + زر تحديث يدوي
 * • النقر يفتح التطبيق مباشرة على نقطة البيع (بعد القفل إن كان مفعلاً)
*/
class SalesTodayWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pr = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                WidgetSync.renderSalesAll(context.applicationContext, appWidgetManager, appWidgetIds)
            } catch (_: Exception) {
            } finally {
                pr.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == WidgetSync.ACTION_REFRESH_SALES) {
            val app = context.applicationContext
            // [P6-M40 إصلاح]: زر التحديث يرسَم هذه الويدجة وحدها — لا إعادة رسم للأربعة جميعاً
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                WidgetSync.refreshOne(app, SalesTodayWidgetProvider::class.java, id)
            } else {
                WidgetSync.push(app, immediate = true)
            }
        }
    }
}
