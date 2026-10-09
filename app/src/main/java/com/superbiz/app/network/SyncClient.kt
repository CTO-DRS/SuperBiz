package com.superbiz.app.network

import com.superbiz.app.data.repo.SyncEnableStore
import com.superbiz.app.domain.sync.SyncCrypto
import com.superbiz.app.domain.sync.SyncTransport
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * [H4-3][ADR-002 D2] — عميل نقل المزامنة خلف الحائط (نمط ZatcaFatooraGateway حرفياً):
 * HttpsURLConnection صفر تبعيات، HTTPS حصراً (HTTP مرفوض بنيوياً — كتلة مشفرة
 * عبر قناة نصية عبث بعقد D5)، بوابة مزدوجة قبل أي socket (enabled + endpoint)،
 * 5xx/429/408 عابر، 4xx مرفوض.
 *
 * عقد الخادم (ترحيل عمياء يملكه المالك — D5):
 *   POST {base}/push  جسد: JSON كتلة {deviceId,seq,noncePrefix,ciphertext}
 *   GET  {base}/pull?after={deviceId:seq,...}  جواب: {"blocks":[...]}
 * التخزين فقط — لا يعتمد الخادم صيغة داخلية ولا يفك أي شيء.
 */
class SyncClient(
    private val store: SyncEnableStore,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000
) : SyncTransport {

    private suspend fun gate(): String? {
        if (!store.enabledOnce()) return "sync disabled (gate)"
        val ep = store.endpointOnce()
        if (ep.isEmpty()) return "sync endpoint unset"
        if (!ep.startsWith("https://")) return "sync endpoint must be https"
        return null
    }

    private fun open(url: URL): HttpsURLConnection {
        val conn = url.openConnection() as HttpsURLConnection
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.setRequestProperty("Accept", "application/json")
        return conn
    }

    override suspend fun push(block: SyncCrypto.EncryptedBlock): SyncTransport.Result<Unit> {
        val why = gate()
        if (why != null) return SyncTransport.Result.Rejected(why)
        val base = store.endpointOnce().trimEnd('/')
        val body = blockJson(block)
        return try {
            val conn = open(URL("$base/push"))
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setFixedLengthStreamingMode(body.toByteArray(Charsets.UTF_8).size)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            when {
                code in 200..299 -> SyncTransport.Result.Ok(Unit)
                code == 429 || code == 408 || code >= 500 -> SyncTransport.Result.Transient(
                    conn.getHeaderField("Retry-After")?.toLongOrNull()?.times(1000)
                )
                else -> SyncTransport.Result.Rejected("push http $code")
            }
        } catch (e: Exception) {
            SyncTransport.Result.Transient(null) // انقطاع النقل عابر — عقد D2
        }
    }

    override suspend fun pull(cursors: Map<String, Long>): SyncTransport.Result<List<SyncCrypto.EncryptedBlock>> {
        val why = gate()
        if (why != null) return SyncTransport.Result.Rejected(why)
        val base = store.endpointOnce().trimEnd('/')
        val after = cursors.entries.joinToString(",", prefix = "{", postfix = "}") {
            "\"" + it.key.replace("\"", "") + "\":" + it.value
        }
        return try {
            val conn = open(URL("$base/pull?after=" + urlEncode(after)))
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code !in 200..299) {
                return if (code == 429 || code == 408 || code >= 500) SyncTransport.Result.Transient(null)
                else SyncTransport.Result.Rejected("pull http $code")
            }
            val text = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            SyncTransport.Result.Ok(parseBlocks(text))
        } catch (e: Exception) {
            SyncTransport.Result.Transient(null)
        }
    }

    private fun blockJson(b: SyncCrypto.EncryptedBlock): String =
        "{\"deviceId\":\"" + b.deviceId.replace("\"", "") + "\",\"seq\":" + b.seq +
            ",\"noncePrefix\":\"" + SyncCrypto.toBase64(b.noncePrefix) + "\"" +
            ",\"ciphertext\":\"" + SyncCrypto.toBase64(b.ciphertext) + "\"}"

    private fun parseBlocks(text: String): List<SyncCrypto.EncryptedBlock> {
        // محلل مصغّر حتمي — الحمولة صيغة معلومة من طرفنا الآخر، لا حاجة لمحلل عام خلف الحائط
        val blocks = ArrayList<SyncCrypto.EncryptedBlock>()
        val rowsStart = rootBlocksIndex(text)
        if (rowsStart < 0) return blocks
        var i = text.indexOf('[', rowsStart)
        if (i < 0) return blocks
        i++
        val root = text
        while (i < root.length) {
            if (root[i] == ']') break
            if (root[i] != '{') { i++; continue }
            val end = root.indexOf('}', i)
            if (end < 0) break
            val obj = root.substring(i, end + 1)
            val deviceId = field(obj, "deviceId") ?: ""
            val seq = field(obj, "seq")?.toLongOrNull() ?: 0L
            val noncePrefix = field(obj, "noncePrefix")?.let { SyncCrypto.fromBase64(it) } ?: ByteArray(4)
            val ciphertext = field(obj, "ciphertext")?.let { SyncCrypto.fromBase64(it) } ?: ByteArray(0)
            if (deviceId.isNotEmpty() && ciphertext.isNotEmpty()) {
                blocks.add(SyncCrypto.EncryptedBlock(deviceId, seq, noncePrefix, ciphertext))
            }
            i = end + 1
        }
        return blocks
    }

    private fun rootBlocksIndex(text: String): Int = text.indexOf("\"blocks\"")

    private fun field(obj: String, name: String): String? {
        val key = "\"$name\""
        val k = obj.indexOf(key)
        if (k < 0) return null
        val colon = obj.indexOf(':', k + key.length)
        if (colon < 0) return null
        if (obj.getOrNull(colon + 1) == '"') {
            val start = colon + 2
            val endQuote = obj.indexOf('"', start)
            if (endQuote < 0) return null
            return obj.substring(start, endQuote)
        }
        var end = -1
        for (i in colon + 1 until obj.length) {
            if (obj[i] == ',' || obj[i] == '}') { end = i; break }
        }
        if (end < 0) return null
        return obj.substring(colon + 1, end).trim()
    }

    private fun urlEncode(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch.isLetterOrDigit() || ch == '-' || ch == '.' || ch == '_' || ch == '~' || ch == '{' || ch == '}' || ch == '"' || ch == ':') {
                sb.append(ch)
            } else {
                sb.append('%').append(String.format("%02X", c))
            }
        }
        return sb.toString()
    }
}
