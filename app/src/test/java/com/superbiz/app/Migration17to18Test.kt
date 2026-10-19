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
 * [RVW-17 V 3.2.6] اختبار ترحيل v17→v18 — دفتر استهلاك الكوبونات (تصميم §7-ب)
 * ═══════════════════════════════════════════════════════════════════════════
 * عقد القبول: ترحيل إلحاقي خالص — CREATE TABLE/INDEX/مشغّلات فقط، لا جدول قائم
 * يُلمس ولا صف يُعاد كتابته. التحقق الحاسم بنمط Migration15Test حرفياً:
 * القاعدة نفسها تُفتح بRoom v18 (مطابقة بايتية بين SQL الترحيل ومخطط Room
 * المُصدَّر 18.json) ثم تعمل دالة DAO كاملة تقرأ وتكتب الجدول الجديد،
 * وصف الكوبون القائم ينجو بحسابه حرفياً، ومشغّل مزامنة الجدول الجديد يحيا.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class Migration17to18Test {

    private var dbName: String? = null

    @After
    fun tearDown() {
        dbName?.let { n ->
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            ctx.deleteDatabase(n)
        }
    }

    /** يبني قاعدة v17 من المخطط المُصدَّر (نمط Migration15Test حرفياً) */
    private fun buildV17(name: String): SupportSQLiteDatabase {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val schema = loadSchemaSql(17)
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(17) {
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
        // كوبون قائم على v17 — يجب أن ينجو بحسابه حرفياً (لا صف يُمسّ).
        // أعمدة المزامنة NOT NULL بلا DEFAULT في createSql Room المُصدَّر —
        // تُمرَّر محايدة صراحةً (نمط Migration15Test في بذر v13 حرفياً)
        db.execSQL(
            "INSERT INTO coupons (code, kind, amountPiasters, percent, expiresAt, maxUses, usedCount, active, note, createdAt, syncUpdatedAt, originDeviceId, originId) " +
                "VALUES ('PRE18-1', 0, 500, 0.0, 0, 10, 3, 1, 'كوبون ما قبل v18', 1000, 0, '', 0)"
        )
    }

    private fun scalar(db: SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use { c -> c.moveToFirst(); c.getLong(0) }

    @Test
    fun migration17to18_createsLedgerTableKeepingCouponData_andRoomValidatesByteIdentity() {
        dbName = "rvw17_m18.db"
        val db = buildV17(dbName!!)
        try {
            val couponCount = scalar(db, "SELECT COUNT(*) FROM coupons")
            val usedCount = scalar(db, "SELECT usedCount FROM coupons WHERE code = 'PRE18-1'")

            val migration = AppGraph.MIGRATIONS[16]
            assertEquals(17, migration.startVersion)
            assertEquals(18, migration.endVersion)
            migration.migrate(db)

            // 1) الجدول والفهارس الثلاثة وُجدت بأسماء Room القياسية
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'coupon_redemptions'"))
            for (idx in listOf(
                "index_coupon_redemptions_eventId",
                "index_coupon_redemptions_code",
                "index_coupon_redemptions_originDeviceId_originId"
            )) {
                assertEquals("فهرس $idx مفقود بعد 17→18", 1L,
                    scalar(db, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = '$idx'"))
            }
            // فهرس eventId فريد فعلاً
            assertEquals(1L, scalar(
                db, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_coupon_redemptions_eventId' AND sql LIKE '%UNIQUE%'"))

            // 2) لا صف فقد — الكوبون القائم بحسابه حرفياً (إلحاقي خالص)
            assertEquals(couponCount, scalar(db, "SELECT COUNT(*) FROM coupons"))
            assertEquals(usedCount, scalar(db, "SELECT usedCount FROM coupons WHERE code = 'PRE18-1'"))
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM coupon_redemptions"))

            // 3) مشغّل مزامنة الجدول الجديد يحيا: إدراج بساعة صفِر تُمثَّل وتُدوَّن في الدفتر
            db.execSQL("INSERT INTO coupon_redemptions (eventId, code, invoiceId, qty, at, origin, syncUpdatedAt, originDeviceId, originId) VALUES ('dev|1|PRE18-1', 'PRE18-1', 1, 1, 1000, 'dev', 0, '', 0)")
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM coupon_redemptions"))
            assertTrue("مشغّل اللمس ضبط ساعة الصف", scalar(db, "SELECT syncUpdatedAt FROM coupon_redemptions") > 0L)
            assertEquals("مشغّل اللمس دوّن الحدث في دفتر الدفع دلتا", 1L,
                scalar(db, "SELECT COUNT(*) FROM sync_log WHERE tableName = 'coupon_redemptions'"))

            // ═══ التحقق الحاسم: فتح القاعدة نفسها بRoom v18 (عدم المطابقة البايتية يرمى) ═══
            db.version = 18
            db.close()

            val ctx = ApplicationProvider.getApplicationContext<Context>()
            val room = androidx.room.Room.databaseBuilder(ctx, com.superbiz.app.data.db.AppDatabase::class.java, dbName!!)
                .allowMainThreadQueries()
                .addMigrations(*AppGraph.MIGRATIONS.toTypedArray())
                .build()
            try {
                kotlinx.coroutines.runBlocking {
                    // عدم المطابقة البايتية يرمى قبل هنا (RoomOpenHelper.checkIdentity)
                    val coupon = room.coupons().byCode("PRE18-1")!!
                    assertEquals(3, coupon.usedCount)
                    assertEquals(10, coupon.maxUses)
                    // DAO كامل يقرأ الجدول الجديد ويكتبه على المخطط الحقيقي
                    assertEquals(1, room.couponRedemptions().countByCode("PRE18-1"))
                    val inserted = room.couponRedemptions().insertOrIgnore(
                        com.superbiz.app.data.db.CouponRedemptionEntity(
                            eventId = "dev|2|PRE18-1", code = "PRE18-1",
                            invoiceId = 2L, qty = 1, at = 2000L, origin = "dev"
                        )
                    )
                    assertTrue(inserted > 0L)
                    // إعادة الحدث نفسه تُتجاهل بالفهرس الفريد — عقد الاتحاد على المخطط الحقيقي
                    val again = room.couponRedemptions().insertOrIgnore(
                        com.superbiz.app.data.db.CouponRedemptionEntity(
                            eventId = "dev|2|PRE18-1", code = "PRE18-1",
                            invoiceId = 2L, qty = 1, at = 2000L, origin = "dev"
                        )
                    )
                    assertEquals("إعادة الحدث نفسه لا تُدرج", -1L, again)
                    assertEquals(2, room.couponRedemptions().countByCode("PRE18-1"))
                }
            } finally {
                room.close()
            }
        } finally {
            if (db.isOpen) db.close()
        }
    }
}
