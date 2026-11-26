package com.superbiz.app.vision

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * [F1 - ADR-005] جسر ML Kit للتعرف على النص — الطبقة الوحيدة التي تلمس واجهة ML Kit.
 *
 * العقد:
 * - التعرف **على الجهاز بالكامل** (النموذج مضمن في الحزمة text-recognition) — صفر طلب شبكي، صفر مفاتيح API، يتطابق مع التزام
 *   التطبيق «يعمل دون اتصال» (ADR-001 يبقى ساريًا: لا socket هنا إطلاقًا).
 * - الإقراء العربي أولًا (التطبيق عربي أولًا) — إن لم يخرج نص عربي يُجرَّب المحرك
 *   اللاتيني ويُعتمد الأغنى محتوى، بترخيص أي محرك استُخدم.
 * - يعيد نصًا خامًا فقط — كل التحليل المالي بعده في OcrInvoiceParser النقي (قلب مال قروش).
 */
object MlKitOcr {

    /** نتيجة التعرف — النص الخام + أي محرك استُخدم فعليًا (للتشخيص والسجل) */
    data class OcrResult(val text: String, val usedArabicEngine: Boolean)

    /** التعرف على نص صورة (فاتورة/إيصال) — يرمي IOException عند تعذر قراءة الصورة فقط */
    suspend fun recognize(context: Context, uri: Uri): OcrResult = withContext(Dispatchers.IO) {
        // [إصلاح بناء 2026-11-26] لا يوجد artifact «text-recognition-arabic» في ML Kit —
        // المتاح لاتيني/صيني/ديفاناغاري/ياباني/كوري فقط، فكان البناء ينكسر. المحرك اللاتيني
        // يقرأ الأرقام والنص اللاتيني؛ القراءة العربية مؤجلة لمحرك مدمج بديل (انظر الخارطة).
        val latinText = recognizeWith(context, uri) {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
        OcrResult(latinText, usedArabicEngine = false)
    }

    /** تشغيل محرك واحد على الصورة مع إغلاق العميل في كل الحالات */
    private suspend fun recognizeWith(
        context: Context, uri: Uri, clientFactory: () -> TextRecognizer
    ): String {
        val client = clientFactory()
        try {
            val image = InputImage.fromFilePath(context, uri)
            return client.process(image).await().text
        } finally {
            client.close()
        }
    }
}
