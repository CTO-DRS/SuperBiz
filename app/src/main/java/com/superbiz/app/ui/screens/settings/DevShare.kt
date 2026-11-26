package com.superbiz.app.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import androidx.core.content.FileProvider
import com.superbiz.app.util.BarcodeGen
import org.json.JSONObject
import java.io.File

/** رابط تنزيل التطبيق ومصدر التحديثات: صفحة الإصدارات العامة للمشروع */
internal const val APP_RELEASES_URL = "https://github.com/CTO-DRS/SuperBiz/releases/latest"

/** لافتة إعلانية من assets/dev_banners.json — تُحدَّث بتعديل الملف فقط */
internal data class DevBanner(
    val title: String,
    val body: String,
    val url: String,
    val from: Long,
    val to: Long
)

internal object DevShare {

    /** يختار نصاً بلغة الجهاز ثم "ar" ثم "en" ثم أي قيمة */
    private fun pick(o: JSONObject?, lang: String): String {
        if (o == null) return ""
        for (k in listOf(lang, "ar", "en")) if (o.optString(k).isNotBlank()) return o.optString(k)
        return o.keys().asSequence().map { o.optString(it) }.firstOrNull { it.isNotBlank() } ?: ""
    }

    private fun color(s: String, def: Long): Long = try {
        0xFF000000L or s.removePrefix("#").toLong(16)
    } catch (e: Exception) { def }

    /** تحليل متسامح: عنصر تالف يُتجاوز ولا يكسر البطاقة */
    fun parseBanners(json: String, lang: String): List<DevBanner> = try {
        val arr = JSONObject(json).optJSONArray("banners")
        (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            val o = arr!!.optJSONObject(i) ?: return@mapNotNull null
            val title = pick(o.optJSONObject("title"), lang)
            if (title.isBlank()) return@mapNotNull null
            val url = o.optString("url")
            DevBanner(
                title, pick(o.optJSONObject("body"), lang),
                if (url.startsWith("https://")) url else "",
                color(o.optString("from"), 0xFF6D3FC9), color(o.optString("to"), 0xFF06B6D4)
            )
        }
    } catch (e: Exception) { emptyList() }

    fun loadBanners(context: Context): List<DevBanner> = try {
        val json = context.assets.open("dev_banners.json").bufferedReader().use { it.readText() }
        parseBanners(json, context.resources.configuration.locales[0].language)
    } catch (e: Exception) { emptyList() }

    /** رسالة مشاركة التطبيق: جملة جاهزة + رابط التنزيل */
    fun appMessage(intro: String): String = intro.trim() + "\n" + APP_RELEASES_URL

    /** كل الحسابات في رسالة واحدة: عنوان ثم «التسمية: الرابط» سطراً سطراً */
    fun allAccountsMessage(header: String, entries: List<Pair<String, String>>): String =
        header.trim() + "\n" + entries.joinToString("\n") { (l, u) -> "$l: $u" }

    fun shareText(context: Context, chooserTitle: String, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
        launch(context, Intent.createChooser(send, chooserTitle))
    }

    /** QR مرسوم على الجهاز على خلفية بيضاء بهامش، يُحفظ في cache/shared ويُشارك صورة PNG */
    fun shareQrImage(context: Context, chooserTitle: String, url: String, fileTag: String) {
        try {
            val qr = BarcodeGen.qr(url, 720) ?: return
            val pad = 60
            val out = Bitmap.createBitmap(qr.width + pad * 2, qr.height + pad * 2, Bitmap.Config.ARGB_8888)
            Canvas(out).apply { drawColor(AColor.WHITE); drawBitmap(qr, pad.toFloat(), pad.toFloat(), null) }
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val f = File(dir, "qr_${fileTag.filter { it.isLetterOrDigit() }}.png")
            f.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(context, "com.superbiz.app.fileprovider", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, url)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            launch(context, Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            android.util.Log.e("DevShare", "qr share failed", e)
        }
    }

    private fun launch(context: Context, i: Intent) {
        com.superbiz.app.util.startIntentSafe(context, i)
    }
}
