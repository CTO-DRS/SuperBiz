package com.superbiz.app.repo

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbiz.app.AppGraph
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [E2.1 V 3.5.0] ترحيل 18→19 — أربعة جداول بإنشاء فقط + بذر الفرع الرئيسي
 * ═══════════════════════════════════════════════════════════════════════════
 * نمط Migration17to18Test حرفياً: قاعدة v18 من المخطط المُصدَّر، الترحيل
 * فعلياً من قائمة MIGRATIONS، ثم:
 *  1) الجداول والفهارس بأسماء Room القياسية
 *  2) لا صف فقد — الكوبونات والمخزون القائم كما هو حرفياً
 *  3) البذر: فرع «المخزن الرئيسي» وحيد، وbranch_stock للفرع الرئيسي مطابق
 *     لـproducts.stockQty لكل صنف قائم (مستوى النقل يبدأ مطابقاً للكلي)
 *  4) Room 19 يفتح القاعدة المُرحَّلة (التحقق البايتي للبنية عبر فالِد Room نفسه)
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class Migration18to19Test {

    private var dbName: String? = null

    @After
    fun tearDown() {
        dbName?.let { n ->
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            ctx.deleteDatabase(n)
        }
    }

    /** قاعدة v18 من المخطط المُصدَّر (نمط Migration17to18Test حرفياً) */
    private fun buildV18(name: String, seedSql: List<String>): SupportSQLiteDatabase {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val schema = loadSchemaSql(18)
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(18) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    schema.forEach { db.execSQL(it) }
                    seedSql.forEach { db.execSQL(it) }
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
                out += indices.getJSONObject(j).getString("createSql")
                    .replace("\${TABLE_NAME}", e.getString("tableName"))
            }
        }
        return out
    }

    private fun scalar(db: SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use { c -> c.moveToFirst(); c.getLong(0) }

    private fun scalarD(db: SupportSQLiteDatabase, sql: String): Double =
        db.query(sql).use { c -> c.moveToFirst(); c.getDouble(0) }

    private fun scalarText(db: SupportSQLiteDatabase, sql: String): String =
        db.query(sql).use { c -> c.moveToFirst(); c.getString(0) }

    @Test
    fun migration18to19_createsBranchTables_seedsMainBranch_andRoom19Validates() {
        dbName = "e21_m19.db"
        val db = buildV18(
            dbName!!,
            listOf(
                // صنفان قائمان بمخزون مختلف — البذر يجب أن يطابقهما حرفياً
                "INSERT INTO products (name, sku, barcode, unit, costPrice, salePrice, stockQty, reorderLevel, category, createdAt, archived, syncUpdatedAt, originDeviceId, originId) " +
                    "VALUES ('صنف قبل', 'PRE19-1', '', 'قطعة', 100, 200, 12.5, 0, '', 1000, 0, 0, '', 0)",
                "INSERT INTO products (name, sku, barcode, unit, costPrice, salePrice, stockQty, reorderLevel, category, createdAt, archived, syncUpdatedAt, originDeviceId, originId) " +
                    "VALUES ('صنف قبل 2', 'PRE19-2', '', 'قطعة', 100, 200, 3.0, 0, '', 1000, 0, 0, '', 0)",
                "INSERT INTO coupons (code, kind, amountPiasters, percent, expiresAt, maxUses, usedCount, active, note, createdAt, syncUpdatedAt, originDeviceId, originId) " +
                    "VALUES ('PRE19-C', 0, 0, 0.0, 0, 0, 0, 1, '', 1000, 0, '', 0)"
            )
        )
        try {
            val productCount = scalar(db, "SELECT COUNT(*) FROM products")
            val couponCount = scalar(db, "SELECT COUNT(*) FROM coupons")
            assertEquals(2L, productCount)

            val migration = AppGraph.MIGRATIONS[17]
            assertEquals(18, migration.startVersion)
            assertEquals(19, migration.endVersion)
            migration.migrate(db)
            // التشغيل اليدوي للترحيل لا يرفع user_version — بلا هذا السطر يعيد
            // Room السلسلة كاملة عند الفتح ويتصادم بذرُ 18→19 ثانيةً (بناء =
            // SQL حرفي، والترقية الفعلية للساعة من عمل Room في الفتح لا الترحيل)
            db.version = 19

            // 1) الجداول الأربعة والفهارس بأسماء Room القياسية
            for (t in listOf("branches", "branch_stock", "stock_transfers", "stock_transfer_lines")) {
                assertEquals("جدول $t مفقود", 1L,
                    scalar(db, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='$t'"))
            }
            for (idx in listOf(
                "index_branches_name", "index_branch_stock_productId",
                "index_stock_transfers_status", "index_stock_transfer_lines_transferId"
            )) {
                assertEquals("فهرس $idx مفقود", 1L,
                    scalar(db, "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='$idx'"))
            }
            assertEquals("فهرس اسم الفرع فريد", 1L, scalar(
                db, "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='index_branches_name' AND sql LIKE '%UNIQUE%'"))

            // 2) لا صف فقد من العالم القائم
            assertEquals("الكوبونات كما هي", couponCount, scalar(db, "SELECT COUNT(*) FROM coupons"))
            assertEquals("الأصناف كما هي", productCount, scalar(db, "SELECT COUNT(*) FROM products"))

            // 3) البذر: فرع رئيسي وحيد + مطابقة مستوى النقل للكلي لكل صنف
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM branches"))
            assertEquals("المخزن الرئيسي", scalarText(db, "SELECT name FROM branches LIMIT 1"))
            assertEquals(12.5, scalarD(db, "SELECT qty FROM branch_stock bs JOIN products p ON p.id = bs.productId WHERE p.sku = 'PRE19-1'"), 1e-9)
            assertEquals(3.0, scalarD(db, "SELECT qty FROM branch_stock bs JOIN products p ON p.id = bs.productId WHERE p.sku = 'PRE19-2'"), 1e-9)
            assertEquals("كل صنف بذِر صف واحد", 2L, scalar(db, "SELECT COUNT(*) FROM branch_stock"))

            // 4) فالِد Room نفسه يفتح القاعدة — تطابق البنية المُرحَّلة مع 19.json بايتياً
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            val room = androidx.room.Room.databaseBuilder(ctx, com.superbiz.app.data.db.AppDatabase::class.java, dbName!!)
                .addMigrations(*AppGraph.MIGRATIONS.toTypedArray())
                .allowMainThreadQueries()
                .build()
            try {
                room.openHelper.writableDatabase
                assertTrue(room.isOpen)
                // القراءة عبر DAO الجديد على القاعدة المُرحَّلة نفسها
                assertEquals(2, kotlinx.coroutines.runBlocking { room.branchStock().ofBranch(1).size })
            } finally {
                room.close()
            }
        } finally {
            db.close()
        }
    }
}
