package com.superbiz.app.print

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.superbiz.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * اتصال بطابعات الإيصالات الحرارية عبر Bluetooth SPP (الملف الشخصي التسلسلي)
 * وإرسال بايتات ESC/POS الخام. لا يحتاج أي مكتبة خارجية.
 * [P6-M32 إصلاح]: فحص isEnabled قبل الاتصال، واتصال قابل للإلغاء (إغلاق المقبض
 * يفكّ connect() الحاجب)، وحلقة انتظار على available() بسقف 3000ms بدل نوم ثابت.
 */
object BluetoothPrinter {

    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private const val PREFS = "printer_prefs"
    private const val KEY_ADDR = "printer_addr"
    private const val KEY_WIDTH = "printer_width"   // 32 حرفاً لورق 58مم، 48 لورق 80مم

    // ── [P6-M32 إصلاح] ثوابت حلقة انتظار استهلاك الطابعة ──
    /** السقف المطلق لانتظار استهلاك البايتات قبل إغلاق القناة (ms) */
    private const val DRAIN_CAP_MS = 3000L
    /** هدوء مطلوب بلا بيانات واردة بعد تجاوز الحد الأدنى ليُعتبر المخزن مُستهلكاً (ms) */
    private const val DRAIN_QUIET_MS = 150L
    /** دورة استطلاع available() (ms) */
    private const val DRAIN_POLL_MS = 20L

    fun adapter(ctx: Context): android.bluetooth.BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /**
     * [P40-M5] واجهة قناة قابلة للتزييف — تستخرج ما يستخدمه print() فعلياً من
     * BluetoothSocket (الدخل للاستنزاف/الخرج للكتابة/الإغلاق) كي تُختبر الكتابة
     * وقطع منتصف الطباعة والإلغاء على قناة مزيفة بلا جهاز حقيقي. الإنتاج يمرر
     * SocketBtChannel — سلوك حرفي مطابق للمسار السابق (نفس الترتيب: كتابة ثم
     * flush ثم drainWait ثم إغلاق في finally).
     */
    internal interface BtChannel {
        /** قناة الدخل لاستطلاع available() — null تعني قناة بلا دخل فتُتخطى الاستنزاف */
        val input: InputStream?
        val output: OutputStream
        fun close()
    }

    /** محوّل BluetoothSocket الحقيقي إلى BtChannel — نفس دلالات runCatching القديمة */
    internal class SocketBtChannel(private val s: BluetoothSocket) : BtChannel {
        override val input: InputStream? get() = runCatching { s.inputStream }.getOrNull()
        override val output: OutputStream get() = s.outputStream
        override fun close() {
            s.close()
        }
    }

    /** إذن BLUETOOTH_CONNECT مطلوب وقت التشغيل على Android 12+ فقط */
    fun hasConnectPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** الطابعات والأجهزة المقترنة مرتبةً بالاسم */
    fun bondedPrinters(ctx: Context): List<android.bluetooth.BluetoothDevice> {
        if (!hasConnectPermission(ctx)) return emptyList()
        val a = adapter(ctx) ?: return emptyList()
        return try {
            a.bondedDevices.orEmpty().sortedBy { it.name ?: it.address }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    fun lastPrinterAddress(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ADDR, "") ?: ""

    fun rememberPrinter(ctx: Context, address: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ADDR, address).apply()
    }

    fun paperWidth(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_WIDTH, 32)

    fun rememberPaperWidth(ctx: Context, chars: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_WIDTH, chars).apply()
    }

    /**
     * إرسال بايتات الإيصال إلى الطابعة — يرمي IOException برسالة قابلة للعرض.
     * يجرب القناة الآمنة أولاً ثم الاحتياطية غير الآمنة لبعض الطابعات القديمة.
     * [P6-M32 إصلاح]: يفحص isEnabled قبل أي محاولة (كان الفشل يظهر كخطأ connect
     * غامض)، والاتصال قابل للإلغاء بقدر المتاح (إلغاء الكوروتين يغلق المقبض فيُفكّ
     * connect() الحاجب — تعطيل خيط الحجب نفسه غير ممكن على SPP)، وبعد الكتابة
     * حلقة انتظار على available() بسقف 3000ms بدل النوم الثابت 150ms الذي كان
     * يقطع الإيصالات الطويلة.
     */
    suspend fun print(ctx: Context, address: String, data: ByteArray) = withContext(Dispatchers.IO) {
        val needPerm = ctx.getString(R.string.bluetooth_need_permission)
        val a = adapter(ctx) ?: throw IOException(ctx.getString(R.string.bt_unavailable))
        if (!hasConnectPermission(ctx)) throw IOException(needPerm)
        // [P6-M32 إصلاح] isEnabled يحتاج BLUETOOTH_CONNECT على 12+ — SecurityException تعامل كتعطيل
        val enabled = try {
            a.isEnabled
        } catch (e: SecurityException) {
            false
        }
        if (!enabled) throw IOException(ctx.getString(R.string.bt_printer_disabled))
        val device = try {
            a.getRemoteDevice(address)
        } catch (e: Exception) {
            throw IOException(e.message ?: "bad address")
        }
        try { a.cancelDiscovery() } catch (e: Exception) { /* غير حرج */ }

        // [P6-M32 إصلاح] اتصال معلّق قابل للإلغاء: عند إلغاء الكوروتين يُغلق المقبض
        // من خارج الخيط الحاجب فترمي connect() فوراً بدل البقاء معلقاً بلا نهاية
        // [V1-B3 إصلاح] resume/resumeWithException امتدادات أُزيلت من coroutines الحديثة — أعضاء الواجهة resume(value, onCancellation)/resumeWith
        suspend fun connect(socket: BluetoothSocket) = suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { runCatching { socket.close() } }
            try {
                socket.connect()
                if (cont.isActive) cont.resume(Unit, onCancellation = null)
            } catch (e: Throwable) {
                if (cont.isActive) cont.resumeWith(Result.failure(e))
            }
        }

        var socket: BluetoothSocket? = null
        try {
            socket = try {
                device.createRfcommSocketToServiceRecord(SPP_UUID)
            } catch (e: SecurityException) {
                throw IOException(needPerm)
            }
            try {
                connect(socket)
            } catch (e: IOException) {
                coroutineContext.ensureActive() // مُلغى؟ لا تُعَد المحاولة — ينتهي بالإلغاء
                runCatching { socket.close() }
                socket = try {
                    device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                } catch (e: SecurityException) {
                    throw IOException(needPerm)
                }
                connect(socket)
            }
            // [P40-M5] المسار نفسه على قناة مجردة: كتابة ← flush ← استنزاف ← إغلاق
            // (الإغلاق في finally داخل sendOver يطابق use{}+finally السابق — إغلاق
            // المقبس يغلق مجراييه ولا يغيّر أي ترتيب بايت)
            sendOver(SocketBtChannel(socket), data)
        } finally {
            runCatching { socket?.close() }
        }
        Unit
    }

    /**
     * [P40-M5] الكتابة والاستنزاف على قناة مجردة — سلوك حرفي من print():
     * write ثم flush ثم حلقة الاستنزاف، وإغلاق القناة في finally مهما كان
     * الخروج (نجاح/IOException/إلغاء) — عقود اختبار BluetoothChannelP40Test.
     */
    internal suspend fun sendOver(channel: BtChannel, data: ByteArray) {
        try {
            channel.output.write(data)
            channel.output.flush()
            drainWait(channel, data.size)
        } finally {
            runCatching { channel.close() }
        }
    }

    /**
     * [P6-M32 إصلاح] حلقة انتظار استهلاك الطابعة للبايتات قبل إغلاق القناة:
     * - تراقب available() لقناة الإدخال — أي ردّ من الطابعة يعيد ضبط ساعة الهدوء؛
     * - حد أدنى مرتبط بحجم البيانات (تقدير محافظ 10 بايت/ms لمحركات 58مم) بحدود 100–2500ms؛
     * - تنتهي بعد تجاوز الحد الأدنى + 150ms هدوء بلا بيانات واردة؛
     * - سقف مطلق 3000ms كي لا تعلق المهنة أبداً؛
     * - فحص إلغاء في كل دورة (delay نفسه قابل للإلغاء).
     */
    private suspend fun drainWait(channel: BtChannel, bytes: Int) {
        val input = channel.input ?: return
        val start = System.currentTimeMillis()
        val minMs = (bytes / 10L).coerceIn(100L, 2500L)
        var quietSince = start
        while (true) {
            currentCoroutineContext().ensureActive()
            val now = System.currentTimeMillis()
            if (now - start >= DRAIN_CAP_MS) return
            // قناة مغلقة/مقطوعة → لا شيء ننتظره
            val avail = try { input.available() } catch (e: Exception) { return }
            if (avail > 0) quietSince = now
            else if (now - start >= minMs && now - quietSince >= DRAIN_QUIET_MS) return
            delay(DRAIN_POLL_MS)
        }
    }
}
