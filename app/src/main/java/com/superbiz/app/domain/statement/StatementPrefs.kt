package com.superbiz.app.domain.statement

import android.content.Context
import com.superbiz.app.security.PinVault

/**
 * [P18-c][P18-int] تفضيلات منظومة الكشف «statement_prefs» — الملف نفسه الذي تستخدمه
 * StatementUiFacade منذ 17-c (default_template/favorites/…) فتُجمع كل مفاتيح الكشف
 * في مكان واحد. قراءة/كتابة متزامنة بلا Flow — نموذج ZatcaPrefs القائم.
 *
 * [دمج P18-int] تعريفا SmtpConfig/SmtpSecurity المكرران هنا حُذفا لمصلحة نسخة
 * 18-a الوحيدة في StatementEmail.kt (enum + تحقق init + defaultPort) — نقطة
 * حقيقة واحدة: كل استهلاك البريد يمر عبر نوع 18-a.
 */
data class SmtpPrefsUi(
    val smtpEnabled: Boolean = false,
    val host: String = "",
    val port: Int = SmtpConfig.defaultPort(SmtpSecurity.SSL_TLS),
    val security: String = SmtpSecurity.SSL_TLS.name,   // SSL_TLS | STARTTLS | NONE
    val authEnabled: Boolean = true,
    val user: String = "",
    val pass: String = "",
    val from: String = "",
    val fromName: String = "",
    val autoSendEnabled: Boolean = false,
    val autoRetryMax: Int = 3
) {
    companion object {
        /** تحويل نص المخزن إلى قيمة enum — أي قيمة غريبة ⇒ SSL_TLS (افتراضي آمن) */
        fun securityEnum(raw: String?): SmtpSecurity =
            runCatching { SmtpSecurity.valueOf((raw ?: "").trim().uppercase()) }
                .getOrDefault(SmtpSecurity.SSL_TLS)
    }
}

object StatementPrefs {

    private const val FILE = "statement_prefs"

    // ── مفاتيح SMTP ──
    private const val K_SMTP_ENABLED = "smtpEnabled"
    private const val K_SMTP_HOST = "smtpHost"
    private const val K_SMTP_PORT = "smtpPort"
    private const val K_SMTP_SECURITY = "smtpSecurity"
    private const val K_SMTP_AUTH = "smtpAuth"
    private const val K_SMTP_USER = "smtpUser"
    private const val K_SMTP_PASS = "smtpPass"               // [تدقيق H-6] مفتاح قديم — قراءة للترحيل فقط ثم يُحذف
    private const val K_SMTP_PASS_VAULT = "smtpPassVault"    // صيغة ks: من PinVault — الوحيدة التي تُكتب منذ الإصلاح
    private const val K_SMTP_FROM = "smtpFrom"
    private const val K_SMTP_FROM_NAME = "smtpFromName"

    // ── مفاتيح الإرسال التلقائي ──
    private const val K_AUTO_SEND = "autoSendEnabled"
    private const val K_AUTO_RETRY_MAX = "autoRetryMax"

    private fun p(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): SmtpPrefsUi {
        val p = p(context)
        val security = SmtpPrefsUi.securityEnum(p.getString(K_SMTP_SECURITY, null)).name
        val savedPort = p.getInt(K_SMTP_PORT, 0)
        return SmtpPrefsUi(
            smtpEnabled = p.getBoolean(K_SMTP_ENABLED, false),
            host = p.getString(K_SMTP_HOST, "") ?: "",
            port = if (savedPort in 1..65535) savedPort
            else SmtpConfig.defaultPort(SmtpPrefsUi.securityEnum(security)),
            security = security,
            authEnabled = p.getBoolean(K_SMTP_AUTH, true),
            user = p.getString(K_SMTP_USER, "") ?: "",
            pass = readPassword(p),
            from = p.getString(K_SMTP_FROM, "") ?: "",
            fromName = p.getString(K_SMTP_FROM_NAME, "") ?: "",
            autoSendEnabled = p.getBoolean(K_AUTO_SEND, false),
            autoRetryMax = p.getInt(K_AUTO_RETRY_MAX, 3).coerceIn(1, 5)
        )
    }

    /**
     * [تدقيق H-6] قراءة كلمة مرور SMTP — النقطة الوحيدة التي تلمس المادة:
     * ① صيغة ks: (PinVault — مفتاح Keystore غير قابل للتصدير): فك التشفير،
     *   والفشل يعيد "" صادقاً (لا إخفاء ولا بديل) — المستخدم يعيد الإدخال.
     * ② الترحيل عند القراءة: تثبيت قديم بنص عاري في smtpPass يُرقّى شفافياً:
     *   تُشفَّر وتُخزَّن في smtpPassVault ويُحذف المفتاح العاري من القرص فوراً —
     *   بلا أي إدخال من المستخدم (نمط rewrap الشفاف لخزنة PIN).
     */
    private fun readPassword(p: android.content.SharedPreferences): String {
        val blob = p.getString(K_SMTP_PASS_VAULT, null)
        if (blob != null) return PinVault.decrypt(blob) ?: ""
        val legacy = p.getString(K_SMTP_PASS, null)
        if (legacy.isNullOrEmpty()) return ""
        return try {
            val encrypted = PinVault.encrypt(legacy)
            p.edit().putString(K_SMTP_PASS_VAULT, encrypted).remove(K_SMTP_PASS).apply()
            legacy
        } catch (_: Exception) {
            // فشل مغلق في الترقية: لا تُترك النص العاري ولا تُخزَّن صيغة بديلة ضعيفة —
            // نجاح فارغ يُجبر إعادة الإدخال وترقية نظيفة في الحفظ التالي
            ""
        }
    }

    fun save(context: Context, ui: SmtpPrefsUi) {
        val sec = SmtpPrefsUi.securityEnum(ui.security)
        // [تدقيق H-6] كلمة المرور لا تُلمس القرص إلا مشفّرة بصيغة ks: من PinVault —
        // فشل Keystore يرمي PinVaultException (فشل مغلق — لا نص عاري ولا بديل قابل للكسر)،
        // والمفتاح العاري القديم يُحذف مع كل حفظ ناجح (طهارة نهائية لأي تثبيت قديم)
        val vaultBlob = if (ui.pass.isEmpty()) null else PinVault.encrypt(ui.pass)
        val editor = p(context).edit()
            .putBoolean(K_SMTP_ENABLED, ui.smtpEnabled)
            .putString(K_SMTP_HOST, ui.host.trim())
            .putInt(K_SMTP_PORT, if (ui.port in 1..65535) ui.port else SmtpConfig.defaultPort(sec))
            .putString(K_SMTP_SECURITY, sec.name)
            .putBoolean(K_SMTP_AUTH, ui.authEnabled)
            .putString(K_SMTP_USER, ui.user.trim())
            .putString(K_SMTP_FROM, ui.from.trim())
            .putString(K_SMTP_FROM_NAME, ui.fromName.trim())
            .putBoolean(K_AUTO_SEND, ui.autoSendEnabled)
            .putInt(K_AUTO_RETRY_MAX, ui.autoRetryMax.coerceIn(1, 5))
        if (vaultBlob != null) editor.putString(K_SMTP_PASS_VAULT, vaultBlob)
        else editor.remove(K_SMTP_PASS_VAULT)   // كلمة مرور مُصفّاة = لا مخزن مشفّر يبقى
        editor.remove(K_SMTP_PASS).apply()      // حذف نص التثبيتات القديمة العاري دائماً
    }

    /**
     * بناء عقد 18-a النهائي لاستهلاك SmtpClient — الإرسال اليدوي (إعادة المحاولة)
     * والاختبار والمجدول كلها من هنا. null في الحالتين الصادقتين:
     * ① غير مفعّل أو المضيف فارغ ② قيم غير صالحة ترفضها مصادقة SmtpConfig
     * (مضيف/منفذ/مرسل/أوراق اعتماد) — المتصل يعرض سبباً صادقاً ولا يخترع قيماً.
     */
    fun smtpConfig(context: Context): SmtpConfig? {
        val ui = load(context)
        if (!ui.smtpEnabled || ui.host.isBlank()) return null
        return runCatching {
            SmtpConfig(
                host = ui.host.trim(),
                port = if (ui.port in 1..65535) ui.port
                else SmtpConfig.defaultPort(SmtpPrefsUi.securityEnum(ui.security)),
                security = SmtpPrefsUi.securityEnum(ui.security),
                username = ui.user.trim(),
                password = ui.pass,
                fromAddress = (ui.from.ifBlank { ui.user }).trim(),
                fromName = ui.fromName.trim(),
                authEnabled = ui.authEnabled,
                maxAttempts = ui.autoRetryMax.coerceIn(1, 5),
                timeoutMs = 15_000
            )
        }.getOrNull()
    }
}
