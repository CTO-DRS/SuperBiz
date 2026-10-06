package com.superbiz.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.superbiz.app.data.db.Product
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** منطق خالص لحساب حالة المخزون المنخفض — قابل للاختبار وحداتياً بلا أي اعتماد على Android */
object StockMath {

    /** المنتجات التي وصلت حد الطلب أو تحته (isLow: حد الطلب > 0 والرصيد ≤ الحد) — بغير المؤرشفة */
    fun low(products: List<Product>): List<Product> =
        products.filter { !it.archived && it.isLow }

    /** الأصناف النافدة تماماً (رصيد صفر أو أقل مع وجود حد طلب محدد) — بغير المؤرشفة */
    fun out(products: List<Product>): List<Product> =
        products.filter { !it.archived && it.reorderLevel > 0 && it.stockQty <= 0.004 }

    /** حالة الخطورة: 0 = المخزون سليم، 1 = تنبيه (منخفض)، 2 = حرج (نفد صنف واحد على الأقل) */
    fun severity(lowCount: Int, outCount: Int): Int = when {
        outCount > 0 -> 2
        lowCount > 0 -> 1
        else -> 0
    }

    /**
     * أسماء الأصناف الأكثر إلحاحاً: الأقرب للنفاد أولاً حسب نسبة الرصيد إلى حد الطلب
     * (النافدة بنسبة صفرية أو سالبة تتقدم تلقائياً)، وبسقف أقصى للعرض.
     */
    fun worstNames(products: List<Product>, max: Int): List<String> =
        low(products)
            .sortedBy { if (it.reorderLevel > 0) it.stockQty / it.reorderLevel else 0.0 }
            .take(max.coerceAtLeast(1))
            .map { it.name }
}

/**
 * — الويدجت الرابع «المخزون المنخفض»
 *
 * • يقرأ فعلياً من جدول المنتجات: الأصناف التي وصلت حد الطلب (isLow) والنافدة (رصيد ≤ 0)
 * — بلا بيانات وهمية — ويعرض العدد بصيغة الجمع العربية الصحيحة (صفر/واحد/اثنان/قليل/جمع)
 * • يعرض: العدد الكبير بلون حسب الخطورة + شريحتا «نفد» و«منخفض» + أسماء أكثر 3 أصناف إلحاحاً
 * • التحديث رباعي: كل 30 دقيقة + فور أي حركة مخزون أو تعديل منتج (InventoryRepo.onMutate → WidgetSync)
 * + عند العودة للتطبيق + زر تحديث يدوي
 * • النقر يفتح التطبيق مباشرة على تبويب المخزون (بعد القفل إن كان مفعلاً)
*/
class LowStockWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pr = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                WidgetSync.renderStockAll(context.applicationContext, appWidgetManager, appWidgetIds)
            } catch (_: Exception) {
            } finally {
                pr.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == WidgetSync.ACTION_REFRESH_STOCK) {
            val app = context.applicationContext
            // [P6-M40 إصلاح]: زر التحديث يرسَم هذه الويدجة وحدها — لا إعادة رسم للأربعة جميعاً
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                WidgetSync.refreshOne(app, LowStockWidgetProvider::class.java, id)
            } else {
                WidgetSync.push(app, immediate = true)
            }
        }
    }
}
