package com.superbiz.app.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.superbiz.app.domain.algo.ExactAlarmPolicy
import com.superbiz.app.util.startIntentSafe
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * [P11-b] التنبيهات الدقيقة لتذكيرات الأقساط — طبقة AlarmManager فوق مسار WorkManager القائم.
 *
 * علاقة الصدق بالمستخدم: بدون إذن SCHEDULE_EXACT_ALARM (من API 31) لا يُسلَّح أي تنبيه
 * دقيق ويبقى مسار WorkManager وحده (السلوك القائم نفسه، بتأخير محتمل دقائق). مع الإذن
 * يُسلَّح لكل دفعة مستقبلية تنبيه setExactAndAllowWhileIdle يطلق في الدقيقة المحددة تماماً.
 *
 * دفتر اليومية (journal):
 * - كل تذكير مسلّح = صف واحد في SharedPreferences("exact_alarm_journal") مفتاح "rows".
 * - الصف = «partyId:dueAt|seq|notifId|title-mُرمَّز|body-mُرمَّز» حيث البادئة
 *   «partyId:dueAt» هي مفتاح ExactAlarmPolicy.encodeJournal القياسي، وtitle/body
 *   مُرمَّزان بـURLEncoder (UTF-8) — خرج المرمِّز من الحروف [A-Za-z0-9.-*_] و«+»
 *   و«%XX» فقط فلا يظهر فيه «|» ولا «;» ولا «,» ولا سطر جديد، بينما الجديد
 *   داخل النص يصير %0A/%7C/%3B... لذا التقسيم على «\n» (بين الصفوف) و«|» (بين الحقول)
 *   آمن والمفكوك مطابق تماماً حتى مع عربية وشرَطات وتواريخ داخل العنوان/المتن.
 * - الحقول العددية (seq/notifId) تُقرأ بتسامح: أي صف مشوّه يُتخطى بلا انهيار.
 *
 * requestCode للتنبيه = (partyId % 100000) * 1000 + seq — يقلّد نفس صيغة تمييز
 * الدفعات في مسار WorkManager؛ تصادم بين طرفين لا يحدث إلا بتطابق باقي القسمة
 * والقسط معاً (نادر)، ومسار WorkManager يبقى صافي الأمان حينها.
 */
object ExactAlarms {

    private const val PREFS = "exact_alarm_journal"
    private const val KEY_ROWS = "rows"

    const val EXTRA_PARTY_ID = "partyId"
    const val EXTRA_SEQ = "seq"
    const val EXTRA_DUE_AT = "dueAt"
    const val EXTRA_NOTIF_ID = "notifId"
    const val EXTRA_TITLE = "title"
    const val EXTRA_BODY = "body"

    /** صف دفتر يومية واحد — تذكير دقيق مسلّح (بيانات كافية لإعادة تسليحه بعد الإقلاع) */
    data class Row(
        val partyId: Long,
        val seq: Int,
        val dueAt: Long,
        val notifId: Int,
        val title: String,
        val body: String
    )

    /** ترميز/فك الصفوف — نقي بلا Context لاختباره JVM مباشرة (راجع ExactAlarmPolicyTest) */
    object JournalCodec {

        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

        private fun dec(s: String): String? = try {
            URLDecoder.decode(s, "UTF-8")
        } catch (e: Exception) {
            null
        }

        fun encodeRow(row: Row): String =
            ExactAlarmPolicy.encodeJournal(listOf(row.partyId to row.dueAt)) +
                "|" + row.seq +
                "|" + row.notifId +
                "|" + enc(row.title) +
                "|" + enc(row.body)

        fun decodeRow(row: String): Row? {
            val parts = row.split('|', limit = 5)
            if (parts.size != 5) return null
            val key = ExactAlarmPolicy.decodeJournal(parts[0])
            if (key.size != 1) return null
            val seq = parts[1].trim().toIntOrNull() ?: return null
            val notifId = parts[2].trim().toIntOrNull() ?: return null
            val title = dec(parts[3]) ?: return null
            val body = dec(parts[4]) ?: return null
            return Row(key[0].first, seq, key[0].second, notifId, title, body)
        }

        /** كل الصفوف سطوراً — يخزَّن كما هو في مفتاح واحد */
        fun encode(rows: List<Row>): String = rows.joinToString("\n") { encodeRow(it) }

        /** فك متسامح: السطور المشوّهة تُتخطى بلا انهيار */
        fun decode(s: String): List<Row> =
            s.split('\n').mapNotNull { if (it.isBlank()) null else decodeRow(it) }
    }

    /** رمز طلب التنبيه لزوج (طرف، قسط) — دالة نقية موثقة في KDoc للكائن */
    fun requestCode(partyId: Long, seq: Int): Int =
        ((partyId % 100_000L).toInt() * 1_000) + seq.coerceIn(0, 999)

    /** هل يُسمح بجدولة تنبيهات دقيقة في هذا الجهاز الآن؟ */
    fun canSchedule(context: Context): Boolean {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val systemAllows = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        return ExactAlarmPolicy.canSchedule(Build.VERSION.SDK_INT, systemAllows)
    }

    /** فتح شاشة النظام لمنح الإذن — تعيد false إن لم يتوفر لها نشاط */
    fun openSettings(context: Context): Boolean = startIntentSafe(
        context,
        Intent(
            android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            android.net.Uri.parse("package:" + context.packageName)
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    )

    /** PendingIntent مطابق للمسلَّح بلا extras — يخدم الإلغاء فقط (لا ينشئ جديداً) */
    private fun pendingIntentForCancel(context: Context, partyId: Long, seq: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            requestCode(partyId, seq),
            Intent(context, ExactReminderReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * تسليح تنبيه دقيق لدفعة واحدة + تسجيله في دفتر اليومية.
     * بلا إذن أو لتاريخ ماضٍ: لا شيء يُفعل (مسار WorkManager يغطيها) — لا استثناء يُرمى.
     */
    fun arm(
        context: Context,
        partyId: Long,
        seq: Int,
        dueAt: Long,
        notifId: Int,
        title: String,
        body: String
    ) {
        if (dueAt <= System.currentTimeMillis()) return
        if (!canSchedule(context)) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val armed = PendingIntent.getBroadcast(
            context,
            requestCode(partyId, seq),
            Intent(context, ExactReminderReceiver::class.java)
                .putExtra(EXTRA_PARTY_ID, partyId)
                .putExtra(EXTRA_SEQ, seq)
                .putExtra(EXTRA_DUE_AT, dueAt)
                .putExtra(EXTRA_NOTIF_ID, notifId)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_BODY, body),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueAt, armed)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, dueAt, armed)
            }
        } catch (e: Exception) {
            // SecurityException إن سُحب الإذن في هذه اللحظة — نسكت: WorkManager يتكفل بالتذكير
            return
        }
        journalAppend(context, Row(partyId, seq, dueAt, notifId, title, body))
    }

    /** إلغاء كل تنبيهات دقيقة لطرف واحد + محو صفوفه من الدفتر (عند حذف/إعادة خطة) */
    fun cancelFor(context: Context, partyId: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (row in journalLoad(context)) {
            if (row.partyId != partyId) continue
            try {
                pendingIntentForCancel(context, partyId, row.seq)?.let { am.cancel(it) }
            } catch (_: Exception) {
            }
        }
        journalRemove(context) { it.partyId == partyId }
    }

    /**
     * إعادة تسليح كل التنبيهات المستقبلية بعد إعادة تشغيل الجهاز —
     * AlarmManager يموت مع الإطفاء والدفتر في SharedPreferences يبقى.
     * الصفوف المنتهية تُحذف؛ المستقبلية تمر عبر arm فتُحدَّث في الدفتر بلا تكرار.
     */
    fun reattachAll(context: Context) {
        val now = System.currentTimeMillis()
        val rows = journalLoad(context)
        for (row in rows) {
            if (row.dueAt <= now) {
                journalRemove(context) { it.partyId == row.partyId && it.dueAt == row.dueAt }
            } else {
                arm(context, row.partyId, row.seq, row.dueAt, row.notifId, row.title, row.body)
            }
        }
    }

    /** بعد إطلاق التذكير من المتلقي: يُحذف صفه من الدفتر فلا يُعاد تسليحه على الفاضي */
    fun journalDone(context: Context, partyId: Long, dueAt: Long) {
        journalRemove(context) { it.partyId == partyId && it.dueAt == dueAt }
    }

    // ═════════ تخزين الدفتر — SharedPreferences سطوراً ═════════

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun journalLoad(context: Context): List<Row> =
        JournalCodec.decode(prefs(context).getString(KEY_ROWS, "") ?: "")

    private fun journalSave(context: Context, rows: List<Row>) {
        prefs(context).edit().putString(KEY_ROWS, JournalCodec.encode(rows)).apply()
    }

    private fun journalAppend(context: Context, row: Row) {
        val rest = journalLoad(context).filterNot {
            it.partyId == row.partyId && it.dueAt == row.dueAt
        }
        journalSave(context, rest + row)
    }

    private fun journalRemove(context: Context, predicate: (Row) -> Boolean) {
        val rows = journalLoad(context)
        if (rows.none(predicate)) return
        journalSave(context, rows.filterNot(predicate))
    }
}
