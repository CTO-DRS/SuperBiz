package com.superbiz.app.export

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.Context
import android.provider.ContactsContract
import com.superbiz.app.data.db.Party

/** نتيجة التصدير العكسي — مجموعها الثلاثة = عدد الأطراف المطلوبة دائماً */
data class ExportStats(
    val exported: Int,
    val skippedExisting: Int,
    val failed: Int
)

/**
 * [P11-b] التصدير العكسي — حفظ أطراف التطبيق (عملاء/موردون) في جهات اتصال الجهاز.
 *
 * وعود الصدق:
 * - ما يُكتب في جهة الاتصال: الاسم + الرقم + ملاحظة الطرف فقط. لا شيء يُرسل
 *   خارج الجهاز — كل العمليات على مزوّد جهات الاتصال المحلي.
 * - الازدواج: تُقارن أرقام الجهاز الموجودة بالأرقام المطلوبة بعد تصفير الأرقام
 *   (normalizeDigits) فلا يُكرَّر طرف رقمه مسجّل بصيغة عربية-هندية أو بفواصل.
 * - كل طرف في دفعة applyBatch مستقلة: فشل طرف لا يفسد بقية الدفعة، وفقدان
 *   الإذن في منتصف الطريق يحسب البقية كلها فاشلة بدل إخفاء أثرها.
 *
 * الكائن مكتفٍ بذاته (بلا اعتماد على دوال ui) ليُستدعى ويُختبَر مستقلاً.
 */
object DeviceContactsExporter {

    /**
     * تصفير أرقام الهاتف: الأرقام العربية-الهندية (٠-٩) والفارسية (۰-۹) إلى
     * ASCII، وحذف كل ما عداهما (فواصل، +، مسافات، حروف) — نسخة مطابقة لمنطق
     * ContactsSheet.normalizePhoneDigits ومكتملة بذاتها لأن المُصدِّر بحزمة أخرى.
     */
    fun normalizeDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when {
                c in '٠'..'٩' -> sb.append('0' + (c - '٠'))
                c in '۰'..'۹' -> sb.append('0' + (c - '۰'))
                c in '0'..'9' -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * أرقام جهات اتصال الجهاز الموجودة فعلاً والمطلوب بينها — قراءة DISPLAY_NAME/NUMBER
     * من مزوّد الهواتف مع تصفير الأرقام. مزوّد معطّل أو مؤشر فارغ ⇒ مجموعة فارغة.
     */
    fun existingDigits(resolver: ContentResolver, digits: Set<String>): Set<String> {
        if (digits.isEmpty()) return emptySet()
        val found = HashSet<String>()
        try {
            val pUri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            resolver.query(
                pUri,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC"
            )?.use { c ->
                val ni = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val pi = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext()) {
                    val num = c.getString(pi)?.trim().orEmpty()
                    val d = normalizeDigits(num)
                    if (d.isNotEmpty() && d in digits) found.add(d)
                }
            }
        } catch (_: Exception) {
            // مزوّد جهات الاتصال المقيّد/المعطّل يرمي استثناءً — نكمل وكأن الجهاز فارغ
        }
        return found
    }

    /** التصفية النقية: من يستحق التصدير؟ رقم غير فارغ وغير موجود في الجهاز */
    fun filterExisting(parties: List<Party>, existing: Set<String>): List<Party> =
        parties.filter { p ->
            val d = normalizeDigits(p.phone)
            d.isNotEmpty() && d !in existing
        }

    /**
     * دفعة عمليات إنشاء جهة اتصال واحدة: RawContact أجنب الحساب (حساب null =
     * جهة محلية) + صف StructuredName للاسم + صف Phone بنوع الهاتف المحمول
     * + صف Note للملاحظة إن كانت غير فارغة. الروابط الخلفية (withValueBackReference)
     * تربط صفوف Data بالـRawContact المُدرج أولاً في الدفعة.
     */
    fun buildOps(name: String, phone: String, note: String?): ArrayList<ContentProviderOperation> {
        val ops = ArrayList<ContentProviderOperation>()
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build()
        )
        if (!note.isNullOrBlank()) {
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Note.NOTE, note)
                    .build()
            )
        }
        return ops
    }

    /**
     * تصدير دفعة أطراف إلى جهات اتصال الجهاز — يعيد إحصاءً صادقاً:
     * exported = كُتبت فعلاً، skippedExisting = رقمها موجود مسبقاً،
     * failed = فشل إنشاؤها (بلا رقم، أو رفض المزوّد/الإذن).
     */
    fun export(context: Context, parties: List<Party>): ExportStats {
        if (parties.isEmpty()) return ExportStats(0, 0, 0)
        val resolver = context.contentResolver
        val requested = parties.map { normalizeDigits(it.phone) }.filter { it.isNotEmpty() }.toSet()
        val existing = try {
            existingDigits(resolver, requested)
        } catch (_: Exception) {
            emptySet<String>()
        }
        val todo = filterExisting(parties, existing)
        val skippedExisting = parties.count { p ->
            val d = normalizeDigits(p.phone)
            d.isNotEmpty() && d in existing
        }
        var failed = parties.size - todo.size - skippedExisting // أطراف بلا رقم مطلقاً
        var exported = 0
        for (p in todo) {
            val name = p.name.trim().ifBlank { p.phone.trim() }
            val phone = p.phone.trim()
            val note = p.note.trim().ifBlank { null }
            try {
                // نتيجة الدفعة قد تعيد مصفوفة فارغة/فارغة (حسب المزوّد) — لا استثناء = نجاح
                resolver.applyBatch(ContactsContract.AUTHORITY, buildOps(name, phone, note))
                exported++
            } catch (e: SecurityException) {
                // سُحب إذن الكتابة في منتصف الدفعة — البقية كلها ستفشل بالضرورة
                failed += todo.size - exported
                break
            } catch (_: Exception) {
                failed++
            }
        }
        return ExportStats(exported, skippedExisting, failed)
    }
}
