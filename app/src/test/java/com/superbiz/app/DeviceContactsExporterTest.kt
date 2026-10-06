package com.superbiz.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.data.db.Party
import com.superbiz.app.export.DeviceContactsExporter
import com.superbiz.app.export.ExportStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import android.content.pm.ProviderInfo
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.annotation.Config

/**
 * [P11-b] اختبارات التصدير العكسي (Robolectric):
 * - التصفير النقي (normalizeDigits) والتصفية النقية (filterExisting).
 * - هيكل دفعة buildOps (عدد العمليات والـURI لكل منها).
 * - export() الكامل عبر مزوّد جهات اتصال وهمي مسجّل في ShadowContentResolver —
 *   يسجل الإدراجات ويعيد مؤشر استعلام مضبوطاً، فلا اعتماد على مزوّد حقيقي.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DeviceContactsExporterTest {

    private lateinit var context: Context
    private lateinit var provider: RecordingContactsProvider

    /** مزوّد وهمي: يسجل (uri, values) لكل إدراج ويعيد مؤشراً مضبوطاً من الاختبار */
    class RecordingContactsProvider : ContentProvider() {
        val inserted = mutableListOf<Pair<Uri, ContentValues>>()
        var queryCursor: MatrixCursor? = null

        override fun onCreate(): Boolean = true
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?
        ): Cursor? = queryCursor
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            if (values == null) return null
            inserted.add(uri to values)
            return Uri.withAppendedPath(uri, "1")
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(
            uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
        ): Int = 0
    }

    /** مزوّد يرفض كل إدراج — لمسار الفشل الصادق */
    class ThrowingContactsProvider : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?
        ): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? =
            throw IllegalStateException("المزوّد المعطّل يرمي دائماً")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(
            uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
        ): Int = 0
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        provider = RecordingContactsProvider()
        // [P11-تكامل] Robolectric 4.13: التسجيل عبر registerProviderInternal مع attachInfo يدوي —
        // الدالة القديمة registerProvider(String, ContentProvider) غير موجودة في هذا الإصدار
        provider.attachInfo(
            context,
            ProviderInfo().apply { authority = ContactsContract.AUTHORITY }
        )
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, provider)
    }

    private fun party(name: String, phone: String, note: String = "") =
        Party(id = 0, name = name, phone = phone, note = note)

    // ═══ normalizeDigits — نقي ═══

    @Test
    fun normalizeDigits_mapsArabicIndicAndPersianToAscii() {
        assertEquals("012", DeviceContactsExporter.normalizeDigits("٠١٢"))
        assertEquals("09", DeviceContactsExporter.normalizeDigits("۰۹"))
        assertEquals("0559999999", DeviceContactsExporter.normalizeDigits("٠٥٥٩٩٩٩٩٩٩"))
    }

    @Test
    fun normalizeDigits_dropsEverythingButDigits() {
        // +966 55-123 abc45 ← فواصل وحروف وعلامة + تُحذف والمرقمن يبقى
        assertEquals("9665512345", DeviceContactsExporter.normalizeDigits("+٩٦٦ ٥٥-١٢٣ abc45"))
        assertEquals("", DeviceContactsExporter.normalizeDigits("بلا أرقام"))
    }

    // ═══ filterExisting — نقي ═══

    @Test
    fun filterExisting_keepsOnlyPartiesWithFreshNonBlankDigits() {
        val existing = setOf("05511112222")
        val out = DeviceContactsExporter.filterExisting(
            listOf(
                party("مكرر", "٠٥٥١١١١٢٢٢٢"), // نفس الرقم بصيغة عربية — يُستبعد
                party("جديد", "0501234567"),   // رقم طازج — يُبقى
                party("بلا رقم", "")            // بلا رقم — يُستبعد
            ),
            existing
        )
        assertEquals(listOf("جديد"), out.map { it.name })
    }

    // ═══ buildOps — هيكل الدفعة ═══

    @Test
    fun buildOps_returns3OpsWithoutNoteAnd4WithNote() {
        val plain = DeviceContactsExporter.buildOps("أحمد", "05511112222", null)
        assertEquals(3, plain.size)
        assertEquals(ContactsContract.RawContacts.CONTENT_URI, plain[0].uri)
        assertEquals(ContactsContract.Data.CONTENT_URI, plain[1].uri)
        assertEquals(ContactsContract.Data.CONTENT_URI, plain[2].uri)
        plain.forEach { assertTrue(it.isWriteOperation()) }

        val noted = DeviceContactsExporter.buildOps("أحمد", "05511112222", "عميل مهم")
        assertEquals(4, noted.size)
        assertEquals(ContactsContract.Data.CONTENT_URI, noted[3].uri)
    }

    @Test
    fun buildOps_blankNoteEqualsNoNote() {
        assertEquals(3, DeviceContactsExporter.buildOps("سالم", "0500000000", "").size)
        assertEquals(3, DeviceContactsExporter.buildOps("سالم", "0500000000", "   ").size)
    }

    // ═══ export — المسار الكامل عبر المزوّد الوهمي ═══

    @Test
    fun export_writesNewPartyAndSkipsExistingWithNormalizedDigits() {
        // الجهاز لديه «موجود» برقم 0559999999 — والطرف الثاني رقمه نفسه بأرقام عربية
        provider.queryCursor = MatrixCursor(arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)).apply {
            addRow(arrayOf("موجود", "0559999999"))
        }
        val stats = DeviceContactsExporter.export(
            context,
            listOf(
                party("عميل جديد", "05511112222", note = "عميل مهم"),
                party("موجود", "٠٥٥٩٩٩٩٩٩٩") // نفس الرقم بصيغة عربية-هندية → يُتخطى
            )
        )
        assertEquals(ExportStats(1, 1, 0), stats)
        assertEquals(2, stats.exported + stats.skippedExisting + stats.failed)

        // ما وصل للمزوّد: دفعة الطرف الجديد فقط = RawContact + اسم + هاتف + ملاحظة
        val raws = provider.inserted.filter { it.first == ContactsContract.RawContacts.CONTENT_URI }
        assertEquals(1, raws.size)
        val data = provider.inserted.filter { it.first == ContactsContract.Data.CONTENT_URI }
        val names = data.filter { it.second.getAsString(ContactsContract.Data.MIMETYPE) == ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE }
        val phones = data.filter { it.second.getAsString(ContactsContract.Data.MIMETYPE) == ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE }
        val notes = data.filter { it.second.getAsString(ContactsContract.Data.MIMETYPE) == ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE }
        assertEquals(1, names.size)
        assertEquals("عميل جديد", names[0].second.getAsString(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME))
        assertEquals(1, phones.size)
        assertEquals("05511112222", phones[0].second.getAsString(ContactsContract.CommonDataKinds.Phone.NUMBER))
        assertEquals(
            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
            phones[0].second.getAsInteger(ContactsContract.CommonDataKinds.Phone.TYPE)
        )
        assertEquals(1, notes.size)
        assertEquals("عميل مهم", notes[0].second.getAsString(ContactsContract.CommonDataKinds.Note.NOTE))
    }

    @Test
    fun export_allExportedWhenDeviceHasNoContacts() {
        provider.queryCursor = MatrixCursor(arrayOf("n", "p")) // مؤشر فارغ — الجهاز بلا جهات
        val parties = listOf(party("أ", "05511112222"), party("ب", "٠٥٠١٢٣٤٥٦٧"))
        val stats = DeviceContactsExporter.export(context, parties)
        assertEquals(ExportStats(2, 0, 0), stats)
        assertEquals(parties.size, stats.exported + stats.skippedExisting + stats.failed)
    }

    @Test
    fun export_emptyInputIsHonestZeroes() {
        assertEquals(ExportStats(0, 0, 0), DeviceContactsExporter.export(context, emptyList()))
    }

    @Test
    fun export_countsFailedWhenProviderRefuses() {
        ShadowContentResolver.registerProviderInternal(
            ContactsContract.AUTHORITY,
            ThrowingContactsProvider().apply {
                attachInfo(
                    context,
                    ProviderInfo().apply { authority = ContactsContract.AUTHORITY }
                )
            }
        )
        val stats = DeviceContactsExporter.export(
            context,
            listOf(party("أ", "05511112222"), party("ب", "0501234567"))
        )
        assertEquals(ExportStats(0, 0, 2), stats)
    }
}
