package com.superbiz.app

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.json.JSONObject

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [Z2-أ V 1.5.0] اختبار ترحيل v13→v14 — أرشيف zatca_docs (إلحاقي خالص)
 * ═══════════════════════════════════════════════════════════════════════════
 * عقد القبول (ZATCA2_WAVE2_PLAN §6-1): ترحيل إلحاقي خالص — CREATE فقط،
 * صفر صف يُعاد كتابته، وكل بيانات v13 حية، والتحقق الحاسم: القاعدة نفسها
 * تُفتح بRoom v14 (مطابقة بايتية بين SQL الترحيل ومخطط Room) ثم تعمل
 * دالة DAO كاملة (أرشفة IGNORE + latestHash) على المخطط الحقيقي.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class ZatcaMigration14Test {

    private var dbName: String? = null

    @After
    fun tearDown() {
        dbName?.let { n ->
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            ctx.deleteDatabase(n)
        }
    }

    /** يبني قاعدة v12 من المخطط المُصدَّر (نمط RbacMigration13Test) */
    private fun buildV12(name: String): SupportSQLiteDatabase {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val schema = loadSchemaSql(12)
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(12) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    schema.forEach { db.execSQL(it) }
                    seedMinimal(db)
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    private fun loadSchemaSql(v: Int): List<String> {
        val candidates = listOf(
            "schemas/com.superbiz.app.data.db.AppDatabase/$v.json",
            "app/schemas/com.superbiz.app.data.db.AppDatabase/$v.json"
        )
        val f = candidates.map { java.io.File(it) }.firstOrNull { it.exists() }
            ?: error("$v.json schema not found — run from module dir")
        val root = org.json.JSONObject(f.readText())
        val out = ArrayList<String>()
        val entities = root.getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            out += e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName"))
            val indices = e.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                val idx = indices.getJSONObject(j)
                out += idx.getString("createSql")
                    .replace("\${TABLE_NAME}", e.getString("tableName"))
                    .replace("\${INDEX_NAME}", idx.getString("name"))
            }
        }
        return out
    }

    private fun seedMinimal(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO parties (name, phone, type, note, createdAt, archived, favorite) " +
                "VALUES ('عميل الترحيل', '0500000000', 0, '', 0, 0, 0)"
        )
        db.execSQL(
            "INSERT INTO invoices (number, partyId, type, date, dueDate, subtotal, discount, taxRate, taxAmount, total, paid, costTotal, status, currency, fxRate, note) " +
                "VALUES ('INV-9001', 1, 0, 1000, 1000, 10000, 0, 15.0, 1500, 11500, 0, 0, 0, 'SAR', 1.0, '')"
        )
    }

    @Test
    fun migration13to14_createsArchiveTableKeepingAllData() {
        dbName = "zatca_m14.db"
        val db = buildV12(dbName!!)
        try {
            // v13 أولاً (RBAC+ZATCA هوية) ثم إدراج فاتورة بيانات v13 ثم v14
            AppGraph.MIGRATIONS[11].migrate(db)
            db.execSQL(
                "INSERT INTO invoices (number, partyId, type, date, dueDate, subtotal, discount, taxRate, taxAmount, total, paid, costTotal, status, currency, fxRate, note, uuid, icv, pih, zatcaSubtype, deliveryDate, buyerName, buyerVat, buyerAddress, zatcaStatus) " +
                    "VALUES ('INV-9002', 1, 0, 2000, 2000, 20000, 0, 15.0, 3000, 23000, 0, 0, 0, 'SAR', 1.0, '', 'UUID-9002', 1, 'PIH-FIRST', '0200000', 0, '', '', '', 1)"
            )
            val invoiceCount = scalar(db, "SELECT COUNT(*) FROM invoices")
            val stampedIcv = scalar(db, "SELECT icv FROM invoices WHERE number = 'INV-9002'")

            val migration = AppGraph.MIGRATIONS[12]
            assertEquals(13, migration.startVersion)
            assertEquals(14, migration.endVersion)
            migration.migrate(db)

            // 1) الجدول ببنية Room المتوقعة — كل أعمدته بألفتها
            assertEquals("invoiceId INTEGER", "INTEGER", columnType(db, "zatca_docs", "invoiceId"))
            assertEquals("xml TEXT", "TEXT", columnType(db, "zatca_docs", "xml"))
            assertEquals("xmlHash TEXT", "TEXT", columnType(db, "zatca_docs", "xmlHash"))
            assertEquals("subtype TEXT", "TEXT", columnType(db, "zatca_docs", "subtype"))
            assertEquals("issuedAt INTEGER", "INTEGER", columnType(db, "zatca_docs", "issuedAt"))
            assertEquals("reportedAt INTEGER", "INTEGER", columnType(db, "zatca_docs", "reportedAt"))
            assertEquals("rejectReason TEXT", "TEXT", columnType(db, "zatca_docs", "rejectReason"))
            assertEquals("attemptCount INTEGER", "INTEGER", columnType(db, "zatca_docs", "attemptCount"))
            assertEquals("clearedXml TEXT", "TEXT", columnType(db, "zatca_docs", "clearedXml"))
            // الفهرس حاضر
            db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='zatca_docs'").use { c ->
                val names = HashSet<String>()
                while (c.moveToNext()) names += c.getString(0)
                assertTrue("فهرس invoiceId حاضر", names.contains("index_zatca_docs_invoiceId"))
            }
            // 2) لا صف فقد في القديمة — إلحاقي خالص
            assertEquals(invoiceCount, scalar(db, "SELECT COUNT(*) FROM invoices"))
            assertEquals(stampedIcv, scalar(db, "SELECT icv FROM invoices WHERE number = 'INV-9002'"))
            // 3) المفتاح الأجنبي CASCADE حاضر وسلامة المراجع نظيفة
            assertEquals(1L, fkCount(db, "zatca_docs"))
            assertEquals(0L, rowCount(db, "PRAGMA foreign_key_check"))
            // 4) الجدول يبدأ فارغاً — الأرشيف يُملأ من الإصدارات الجديدة فقط
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM zatca_docs"))

            // ═══ التحقق الحاسم: فتح القاعدة نفسها بRoom ═══
            // [H4-1 V 2.5.0]: إكمال السلسلة إلى v15 (ختم الفئة الأصلية) — المخطط الحالي 15
            AppGraph.MIGRATIONS[13].migrate(db)
            AppGraph.MIGRATIONS[14].migrate(db)
            db.version = 16
            db.close()

            val ctx = ApplicationProvider.getApplicationContext<Context>()
            val room = androidx.room.Room.databaseBuilder(ctx, com.superbiz.app.data.db.AppDatabase::class.java, dbName!!)
                .allowMainThreadQueries()
                .build()
            try {
                kotlinx.coroutines.runBlocking {
                    // عدم المطابقة البايتية يرمى قبل هنا (RoomOpenHelper.checkIdentity)
                    val inv = room.invoices().byId(1L)!!
                    assertEquals("", inv.uuid)
                    // DAO يعمل على المخطط الحقيقي: أرشفة أول + الأحدث + IGNORE
                    val doc = com.superbiz.app.data.db.ZatcaDocEntity(
                        invoiceId = 2L, xml = "<Invoice/>", xmlHash = "HASH-2",
                        subtype = "0200000", issuedAt = 2000,
                    )
                    assertTrue(room.zatcaDocs().archiveFirst(doc) != -1L)
                    // الإعادة بـIGNORE: لا استبدال — صفّ واحد فقط
                    assertTrue(room.zatcaDocs().archiveFirst(doc.copy(xml = "OTHER")) == -1L)
                    assertEquals(1, room.zatcaDocs().count())
                    assertEquals("HASH-2", room.zatcaDocs().latestHash())
                    assertEquals("<Invoice/>", room.zatcaDocs().byInvoice(2L)!!.xml)
                }
            } finally {
                room.close()
            }
        } finally {
            db.close()
        }
    }

    // ─── مساعدات ───

    private fun scalar(db: SupportSQLiteDatabase, sql: String): Long {
        db.query(sql).use { c ->
            c.moveToFirst()
            return c.getLong(0)
        }
    }

    private fun columnType(db: SupportSQLiteDatabase, table: String, column: String): String =
        db.query("PRAGMA table_info(`$table`)").use { c ->
            val nameIdx = c.getColumnIndex("name")
            val typeIdx = c.getColumnIndex("type")
            while (c.moveToNext()) {
                if (c.getString(nameIdx) == column) return c.getString(typeIdx)
            }
            throw AssertionError("column not found: $table.$column")
        }

    private fun fkCount(db: SupportSQLiteDatabase, table: String): Long =
        scalar(db, "SELECT COUNT(*) FROM pragma_foreign_key_list('$table')")

    /** عدّ صفوف نتيجة (لـPRAGMAs التي تعيد صفر صفوف عند النظافة) */
    private fun rowCount(db: SupportSQLiteDatabase, sql: String): Long {
        db.query(sql).use { c ->
            var n = 0L
            while (c.moveToNext()) n++
            return n
        }
    }
}
