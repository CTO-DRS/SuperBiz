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

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [H4-1 V 2.5.0] اختبار ترحيل v14→v15 — ختم الفئة الأصلية (عملات متعددة)
 * ═══════════════════════════════════════════════════════════════════════════
 * عقد القبول (H4-1): ترحيل إلحاقي خالص — 6 أعمدة ALTER ADD COLUMN NOT NULL
 * DEFAULT (3 على invoices + 3 على expenses)، بذور محايدة دلالياً:
 * origCurrency="" = «الفئة بالأساس نفسه» فتبقى كل بيانات v14 سليمة دلالتها
 * حرفياً. والتحقق الحاسم كما في ZatcaMigration14Test: القاعدة نفسها تُفتح
 * بRoom v15 (مطابقة بايتية بين SQL الترحيل ومخطط Room المُصدَّر 15.json)
 * ثم تعمل دالة DAO كاملة تقرأ وتكتب الأعمدة الجديدة على المخطط الحقيقي.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class Migration15Test {

    private var dbName: String? = null

    @After
    fun tearDown() {
        dbName?.let { n ->
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            ctx.deleteDatabase(n)
        }
    }

    /** يبني قاعدة v13 من المخطط المُصدَّر (نمط ZatcaMigration14Test حرفياً) */
    private fun buildV13(name: String): SupportSQLiteDatabase {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val schema = loadSchemaSql(13)
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(13) {
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
        // [H4-1] بذرة على مخطط v13 المُصدَّر — أعمدة ZATCA-2 (v13) كلها NOT NULL بلا
        // DEFAULT في createSql المولَّد، فتُمرَّر قيمها المحايدة صراحةً هنا
        db.execSQL(
            "INSERT INTO parties (name, phone, type, note, createdAt, archived, favorite) " +
                "VALUES ('عميل الترحيل 15', '0500000015', 0, '', 0, 0, 0)"
        )
        db.execSQL(
            "INSERT INTO invoices (number, partyId, type, date, dueDate, subtotal, discount, taxRate, taxAmount, total, paid, costTotal, status, currency, fxRate, note, uuid, icv, pih, zatcaSubtype, deliveryDate, buyerName, buyerVat, buyerAddress, zatcaStatus) " +
                "VALUES ('INV-1501', 1, 0, 1000, 1000, 10000, 0, 15.0, 1500, 11500, 0, 0, 0, 'SAR', 1.0, '', '', 0, '', '', 0, '', '', '', 0)"
        )
        db.execSQL(
            "INSERT INTO expenses (amount, category, note, date, createdAt) " +
                "VALUES (5000, 'إيجار', '', 1000, 1000)"
        )
    }

    @Test
    fun migration14to15_addsOriginStampColumnsKeepingAllData() {
        dbName = "h4_m15.db"
        val db = buildV13(dbName!!)
        try {
            // v14 أولاً (أرشيف zatca_docs) + صفوف بيانات v14 ثم الترحيل الجديد
            AppGraph.MIGRATIONS[12].migrate(db)
            db.execSQL(
                "INSERT INTO invoices (number, partyId, type, date, dueDate, subtotal, discount, taxRate, taxAmount, total, paid, costTotal, status, currency, fxRate, note, uuid, icv, pih, zatcaSubtype, deliveryDate, buyerName, buyerVat, buyerAddress, zatcaStatus) " +
                    "VALUES ('INV-1502', 1, 0, 2000, 2000, 20000, 0, 15.0, 3000, 23000, 0, 0, 0, 'SAR', 1.0, '', 'UUID-1502', 1, '', '0200000', 0, '', '', '', 0)"
            )
            db.execSQL(
                "INSERT INTO expenses (amount, category, note, date, createdAt) " +
                    "VALUES (2500, 'وقود', 'قبل الترحيل', 2000, 2000)"
            )
            val invoiceCount = scalar(db, "SELECT COUNT(*) FROM invoices")
            val expenseCount = scalar(db, "SELECT COUNT(*) FROM expenses")
            val invTotal = scalar(db, "SELECT total FROM invoices WHERE number = 'INV-1501'")

            val migration = AppGraph.MIGRATIONS[13]
            // بعدها تُستكمل السلسلة إلى 16 عند فتح Room (انظر الإغلاق أدناه)
            assertEquals(14, migration.startVersion)
            assertEquals(15, migration.endVersion)
            migration.migrate(db)
            // [H4-3 V 3.0.0] استكمال السلسلة إلى v16 (أعمدة المزامنة) — الفتحة النهائية بمخطط اليوم
            AppGraph.MIGRATIONS[14].migrate(db)

            // 1) الأعمدة الستة بألفتها المتوقعة (TEXT/INTEGER)
            for (col in listOf("origCurrency", "origTotal", "origFxMicros")) {
                assertEquals("invoices.$col", if (col == "origCurrency") "TEXT" else "INTEGER", columnType(db, "invoices", col))
                assertEquals("expenses.$col", if (col == "origCurrency") "TEXT" else "INTEGER", columnType(db, "expenses", col))
            }
            // 2) لا صف فقد — إلحاقي خالص، والقيم القديمة كما هي حرفياً
            assertEquals(invoiceCount, scalar(db, "SELECT COUNT(*) FROM invoices"))
            assertEquals(expenseCount, scalar(db, "SELECT COUNT(*) FROM expenses"))
            assertEquals(invTotal, scalar(db, "SELECT total FROM invoices WHERE number = 'INV-1501'"))
            // 3) البذور المحايدة على الصفوف التاريخية: '' و0 و0 — دلالة «بالأساس نفسه»
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM invoices WHERE origCurrency != ''"))
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM invoices WHERE origTotal != 0"))
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM expenses WHERE origFxMicros != 0"))
            assertEquals(0L, rowCount(db, "PRAGMA foreign_key_check"))

            // ═══ التحقق الحاسم: فتح القاعدة نفسها بRoom v15 ═══
            db.version = 16
            db.close()

            val ctx = ApplicationProvider.getApplicationContext<Context>()
            val room = androidx.room.Room.databaseBuilder(ctx, com.superbiz.app.data.db.AppDatabase::class.java, dbName!!)
                .allowMainThreadQueries()
                .build()
            try {
                kotlinx.coroutines.runBlocking {
                    // عدم المطابقة البايتية يرمى قبل هنا (RoomOpenHelper.checkIdentity)
                    val old = room.invoices().byId(1L)!!
                    assertEquals("", old.origCurrency)
                    assertEquals(0L, old.origTotal)
                    assertEquals(0L, old.origFxMicros)
                    assertEquals(11500L, old.total)
                    // DAO يعمل على المخطط الحقيقي: إدراج فاتورة مختومة بفئة أجنبية + مصروف أجنبي
                    val stamped = old.copy(
                        id = 0, number = "INV-1503", subtotal = 37500, taxAmount = 0, total = 37500,
                        origCurrency = "USD", origTotal = 10000, origFxMicros = 375_000_000L
                    )
                    val newId = room.invoices().upsert(stamped)
                    assertTrue(newId > 0L)
                    val read = room.invoices().byId(newId)!!
                    assertEquals("USD", read.origCurrency)
                    assertEquals(10000L, read.origTotal)
                    assertEquals(375_000_000L, read.origFxMicros)
                    assertEquals(37500L, read.total) // القروش الأساسية سليمة
                }
            } finally {
                room.close()
            }
        } finally {
            db.close()
        }
    }

    // ─── مساعدات (نفس ZatcaMigration14Test) ───

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

    /** عدّ صفوف نتيجة (لـPRAGMAs التي تعيد صفر صفوف عند النظافة) */
    private fun rowCount(db: SupportSQLiteDatabase, sql: String): Long {
        db.query(sql).use { c ->
            var n = 0L
            while (c.moveToNext()) n++
            return n
        }
    }
}
