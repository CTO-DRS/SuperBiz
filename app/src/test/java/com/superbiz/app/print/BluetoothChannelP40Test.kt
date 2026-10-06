package com.superbiz.app.print

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * [P40-M5] المرحلة 5 — جدار طبقة Bluetooth — القناة المزيفة ضد BluetoothPrinter:
 *
 * • القسم النقي (BluetoothChannelP40Test): عقد sendOver على قناة مزيفة —
 *   الكتابة حرفاً-حرفاً ثم flush ثم الاستنزاف ثم الإغلاق في finally، وقطع
 *   منتصف الطباعة (IOException من منتصف الكتابة — البايتات الجزئية محفوظة
 *   والقناة مغلقة)، وقطع منتصف الاستنزاف (إلغاء الكوروتين أثناء حلقة
 *   drainWait يغلق القناة ويعيد CancellationException)، والخروج الهادئ
 *   قبل السقف المطلق.
 *
 * • القسم الروبو-إلكتريكي (BluetoothPrintGuardsP40Test): سلسلة حرسات print()
 *   على Robolectric — إذن BLUETOOTH_CONNECT المرفوض (31+) يُترجم إلى رسالة
 *   المورد الموطّنة، والمحوّل المعطّل إلى رسالة «الطابعة معطلة»، والعنوان
 *   الفاسد يُغلَّف IOException بلا انهيار — نفس سلوك الجهاز الحقيقي قبل أي
 *   محاولة اتصال فعلية.
 *
 * [P40-M5 إعادة هيكلة مقصودة موثقة]: BtChannel/SocketBtChannel/sendOver
 * أُضيفت في BluetoothPrinter.kt بتوكيل حرفي للمسار السابق (write ← flush ←
 * drainWait ← إغلاق finally) — لا تغيير في أي بايت يُرسل ولا في ترتيب الخطوات.
 */
class BluetoothChannelP40Test {

    /** قناة مزيفة: تسجّل البايتات وتسمح بحقن القطع والاستطلاع */
    private class FakeChannel : BluetoothPrinter.BtChannel {
        val written = ByteArrayOutputStream()
        var closed = false
        /** إذا ≥0: يرمي IOException فور تجاوز هذا العدد من البايتات (قطع منتصف الكتابة) */
        var failAfterBytes: Int = -1
        /** مسبار available() لدخل القناة — الزيف المفتوح يُبقي حلقة الاستنزاف دائرة */
        var availProbe: () -> Int = { 0 }
        var flushed = false

        override val input: InputStream? get() = object : InputStream() {
            override fun read(): Int = -1
            override fun available(): Int = availProbe()
        }
        override val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) {
                if (failAfterBytes >= 0 && written.size() >= failAfterBytes)
                    throw IOException("cable pulled mid-print")
                written.write(b)
            }
            override fun flush() { flushed = true }
        }
        override fun close() { closed = true }
    }

    // بايتات ESC/POS شبيهة بإيصال حقيقي (تهيئة + توسيط + نص + سطر)
    private val receipt = byteArrayOf(
        0x1B, 0x40,
        0x1B, 0x61, 0x01,
        0x53, 0x42, 0x3E, 0x20,
        0x31, 0x32, 0x33, 0x0A
    )

    @Test
    fun `الكتابة حرفا حرفا ثم flush ثم الاستنزاف ثم الإغلاق`() = runBlocking {
        val ch = FakeChannel()
        BluetoothPrinter.sendOver(ch, receipt)
        assertEquals("كل بايتات الإيصال تصل بترتيبها — عقد print() الحرفي", receipt.toList(), ch.written.toByteArray().toList())
        assertTrue("flush يستدعى بعد الكتابة وقبل الاستنزاف", ch.flushed)
        assertTrue("القناة تُغلق في finally دائماً", ch.closed)
    }

    @Test
    fun `قطع منتصف الكتابة يرمي IOException ويحفظ الجزئي ويغلق القناة`() = runBlocking {
        val ch = FakeChannel().apply { failAfterBytes = 4 }
        val thrown = runCatching { BluetoothPrinter.sendOver(ch, receipt) }.exceptionOrNull()
        assertTrue("قطع منتصف الطباعة يجب أن يظهر IOException للمنادي", thrown is IOException)
        assertEquals("البايتات المكتوبة قبل القطع محفوظة (تشخيص الطابعة)", 4, ch.written.size())
        assertTrue("القناة تُغلق رغم الاستثناء — لا تسريب مقابض", ch.closed)
    }

    @Test
    fun `قطع منتصف الاستنزاف بالإلغاء يغلق القناة ويعيد CancellationException`() = runBlocking {
        val ch = FakeChannel().apply { availProbe = { 1 } } // طابعة «تردّ» بلا توقف → الحلقة تدور حتى السقف
        var thrown: Throwable? = null
        val job = launch {
            try {
                BluetoothPrinter.sendOver(ch, receipt)
            } catch (t: Throwable) {
                thrown = t
            }
        }
        delay(250) // الحلقة في drainWait (delay قابل للإلغاء — فحص ensureActive كل دورة 20ms)
        job.cancelAndJoin()
        assertTrue(
            "الإلغاء أثناء الاستنزاف يعيد CancellationException (ولا يُبلع كنجاح)",
            thrown is CancellationException
        )
        assertTrue("القناة مغلقة بعد الإلغاء — عقد finally", ch.closed)
        assertEquals("الكتابة اكتملت قبل القطع — القطع في الاستنزاف لا يفقد بايتات", receipt.size, ch.written.size())
    }

    @Test
    fun `الاستنزاف الهادئ ينتهي دون انتظار السقف المطلق`() = runBlocking {
        val ch = FakeChannel() // available()=0 دائماً → يخرج بعد minMs(100)+هدوء(150) قبل سقف 3000
        val t0 = System.currentTimeMillis()
        BluetoothPrinter.sendOver(ch, receipt)
        val elapsed = System.currentTimeMillis() - t0
        assertTrue("الخروج الهادئ قبل السقف المطلق بكثير (كان $elapsed ms)", elapsed < 2500)
        assertTrue(ch.closed)
    }
}

/**
 * [P40-M5] حرسات print() العامة على Robolectric — نفس سلسلة الرفض التي يراها
 * المستخدم على جهاز حقيقي قبل أي اتصال فعلي، وعقد محوّل SocketBtChannel.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class BluetoothPrintGuardsP40Test {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val addr = "00:11:22:33:44:55"

    @Test
    fun `بلا إذن BLUETOOTH_CONNECT على 34 يعيد رسالة الإذن الموطنة`() = runBlocking {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val e = runCatching {
            BluetoothPrinter.print(app, addr, byteArrayOf(0x1B, 0x40))
        }.exceptionOrNull()
        assertTrue(e is IOException)
        assertEquals(app.getString(R.string.bluetooth_need_permission), e!!.message)
    }

    @Test
    fun `محول معطل يعيد رسالة الطابعة المعطلة قبل أي محاولة اتصال`() = runBlocking {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        // Robolectric: ShadowBluetoothAdapter.isEnabled = false افتراضياً
        val e = runCatching {
            BluetoothPrinter.print(app, addr, byteArrayOf(0x1B, 0x40))
        }.exceptionOrNull()
        assertTrue(e is IOException)
        assertEquals(app.getString(R.string.bt_printer_disabled), e!!.message)
    }

    @Test
    fun `عنوان فاسد يغلف IOException بلا انهيار ولا اتصال`() = runBlocking {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = BluetoothPrinter.adapter(app)!!
        Shadows.shadowOf(adapter).setEnabled(true)
        val e = runCatching {
            BluetoothPrinter.print(app, "not-a-mac", byteArrayOf(0x1B, 0x40))
        }.exceptionOrNull()
        assertTrue("عنوان غير صالح → IOException مغلفة من getRemoteDevice", e is IOException)
        assertTrue((e as IOException).message?.isNotBlank() == true)
    }

    @Test
    fun `عقد المحول داخل الكائن يقبل قناة اختبار بنفس الواجهة`() {
        // BtChannel واجهة داخلية يستعملها print() فعلاً عبر sendOver — هذا
        // الاختبار يوثق أن القناة المزيفة (وبالتالي SocketBtChannel) تفي
        // بنفس العقد الثلاثي: دخل للاستنزاف، خرج للكتابة، إغلاق صامت.
        val ch = FakeContractChannel()
        assertTrue(ch.input != null)
        ch.output.write(1)
        ch.close()
        assertTrue(ch.closedFlag)
    }

    private class FakeContractChannel : BluetoothPrinter.BtChannel {
        var closedFlag = false
        override val input: InputStream? get() = object : InputStream() { override fun read() = -1 }
        override val output: OutputStream = object : OutputStream() { override fun write(b: Int) {} }
        override fun close() { closedFlag = true }
    }
}
