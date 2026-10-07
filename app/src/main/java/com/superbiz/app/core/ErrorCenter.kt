package com.superbiz.app.core

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * — مركز الأخطاء الموحّد (ErrorCenter)
 *
 * نقطة واحدة يمرّ منها كل خطأ ملتقط في التطبيق (launchSafe، المستودعات، الطباعة،
 * النسخ الاحتياطي، الويدجت...) ليحقق ثلاثة أهداف
 * 1) تسجيل موحّد في Logcat بعنوان واضح (بدلاً من تسجيل مبعثر).
 * 2) بثّ حدث فوري لواجهة المستخدم (Snackbar) برسالة عربية مفهومة إن وُجدت.
 * 3) سجلّ تاريخي دائري (آخر 200 حدث) قابل للعرض من: الإعدادات ← متقدم ← سجل الأخطاء.
 *
 * ملاحظة تصميمية: كائن وحيد بلا اعتماديات Android سوى Log — قابل للاستخدام من
 * الكوروتينات والمستودعات والويدجت دون تمرير سياق.
*/

/** مستويات الخطأ: معلومة / تحذير / خطأ */
object ErrorLevel {
    const val INFO = 0
    const val WARN = 1
    const val ERROR = 2
}

data class ErrorEvent(
    val id: Long,
    val tag: String,          // مصدر الخطأ: "POS", "Backup", "InvoiceRepo"...
    val message: String,      // الرسالة التقنية (exception.message)
    val userMessage: String?, // رسالة عربية للمستخدم إن وُجدت
    val ts: Long,             // زمن الحدث (System.currentTimeMillis)
    val level: Int            // ErrorLevel
)

object ErrorCenter {

    private const val MAX_HISTORY = 200
    // كان nextId++ وتحديث السجل read-modify-write غير متزامنين —
    // report/warn يُستدعيان من Main وDefault وIO (launchSafe، العاملون، المستودعات)،
    // فكان انفجار أخطاء يضيّع أحداثاً من السجل (أداة التشخيص الوحيدة) ويكرّر المعرّفات
    private val lock = Any()
    private var nextId = 1L

    private val _events = MutableSharedFlow<ErrorEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    /** بثّ فوري للأحداث — تلتقطه واجهة المستخدم لعرض Snackbar */
    val events: SharedFlow<ErrorEvent> = _events.asSharedFlow()

    private val _history = MutableStateFlow<List<ErrorEvent>>(emptyList())
    /** السجل التاريخي الدائري — يُعرض في شاشة الإعدادات المتقدمة */
    val history: StateFlow<List<ErrorEvent>> = _history.asStateFlow()

    /** آخر حدث فقط — للاستخدام السريع في شارات صغيرة */
    private val _last = MutableStateFlow<ErrorEvent?>(null)
    val last: StateFlow<ErrorEvent?> = _last.asStateFlow()

    fun report(
        t: Throwable,
        tag: String,
        userMessage: String? = null,
        level: Int = ErrorLevel.ERROR
    ) {
        val ev = ErrorEvent(
            id = synchronized(lock) { nextId++ },
            tag = tag,
            message = t.message ?: t.javaClass.simpleName,
            userMessage = userMessage,
            ts = System.currentTimeMillis(),
            level = level
        )
        Log.e("SuperBiz/$tag", "[$level] ${ev.message}", t)
        synchronized(lock) {
            _history.value = (_history.value + ev).takeLast(MAX_HISTORY)
            _last.value = ev
        }
        _events.tryEmit(ev)
    }

    /**
     * حدث معلوماتي (غير خطأ) — يظهر في السجل فقط.
     * [تدقيق L-3] كان يصنع RuntimeException(message) ويمرره لreport فيُلتقط
     * أثر المكدس الكامل عند الإنشاء داخل المعاملات بلا أي قيمة تشخيصية
     * (الأثر يصف إنشاء الاستثناء لا موقع المعلومة). الآن لا استثناء مزيّفاً —
     * نفس بنية warn مباشرة بمستوى INFO وبلا وهم في Logcat.
     */
    fun info(tag: String, message: String) {
        val ev = ErrorEvent(
            id = synchronized(lock) { nextId++ },
            tag = tag,
            message = message,
            userMessage = null,
            ts = System.currentTimeMillis(),
            level = ErrorLevel.INFO
        )
        Log.i("SuperBiz/$tag", "[${ErrorLevel.INFO}] $message")
        synchronized(lock) {
            _history.value = (_history.value + ev).takeLast(MAX_HISTORY)
            _last.value = ev
        }
        _events.tryEmit(ev)
    }

    /** تحذير — يظهر في السجل وLogcat بمستوى تحذير */
    fun warn(tag: String, message: String, userMessage: String? = null) {
        val ev = ErrorEvent(
            id = synchronized(lock) { nextId++ },
            tag = tag,
            message = message,
            userMessage = userMessage,
            ts = System.currentTimeMillis(),
            level = ErrorLevel.WARN
        )
        Log.w("SuperBiz/$tag", message)
        synchronized(lock) {
            _history.value = (_history.value + ev).takeLast(MAX_HISTORY)
            _last.value = ev
        }
        _events.tryEmit(ev)
    }

    /** تفريغ السجل (زر في شاشة سجل الأخطاء) */
    fun clearHistory() {
        synchronized(lock) {
            _history.value = emptyList()
            _last.value = null
        }
    }
}
