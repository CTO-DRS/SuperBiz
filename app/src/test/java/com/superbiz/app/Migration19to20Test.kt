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
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [E2.2 V3.6.0] اختبار ترحيل v19→v20 — جدولا المدفوعات (سجل المعاملات + دفتر الاسترداد)
 * ═══════════════════════════════════════════════════════════════════════════
 * عقد القبول: ترحيل إلحاقي خالص — CREATE TABLE/INDEX فقط، لا جدول قائم يُلمس
 * ولا صف يُعاد كتابته. التحقق الحاسم بنمط Migration17to18Test حرفياً:
 * القاعدة تُبنى من المخطط المُصدَّر 19.json، يُشغَّل الترحيل فعلياً، ثم تُفتح
 * القاعدة نفسها بRoom v20 — عدم المطابقة البايتية بين SQL الترحيل ومخطط Room
 * المُصدَّر 20.json يرمى في RoomOpenHelper.checkIdentity قبل أي تأكيد.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class Migration19to20Test {

    private var dbName: String? = null

    @After
    fun tearDown() {
        dbName?.let { n ->
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            ctx.deleteDatabase(n)
        }
    }

    /** يبني قاعدة v19 من المخطط المُصدَّر (نمط Migration17to18Test حرفياً) */
    private fun buildV19(name: String): SupportSQLiteDatabase {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val schema = loadSchemaSql(19)
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(19) {
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
        // فرع قائم على v19 — يجب أن ينجو بحرفيته (إلحاقي خالص: لا صف يُمسّ)
        db.execSQL(
            "INSERT INTO branches (name, kind, active, createdAt) VALUES ('المخزن الرئيسي', 0, 1, 1000)"
        )
    }

    private fun scalar(db: SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use { c -> c.moveToFirst(); c.getLong(0) }

    @Test
    fun migration19to20_createsPaymentsTables_additiveOnly_andRoomValidatesByteIdentity() {
        dbName = "e22_m20.db"
        val db = buildV19(dbName!!)
        try {
            val branchCount = scalar(db, "SELECT COUNT(*) FROM branches")
            val branchName = db.query("SELECT name FROM branches LIMIT 1").use { c ->
                c.moveToFirst(); c.getString(0)
            }

            val migration = AppGraph.MIGRATIONS.first { it.startVersion == 19 && it.endVersion == 20 }
            assertEquals(19, migration.startVersion)
            assertEquals(20, migration.endVersion)
            migration.migrate(db)

            // 1) الجدولان والأفهرسة الأربعة وُجدت بأسماء Room القياسية
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'payment_tx'"))
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'refund_ledger'"))
            for (idx in listOf(
                "index_payment_tx_invoiceId",
                "index_payment_tx_createdAt",
                "index_refund_ledger_idempotencyKey",
                "index_refund_ledger_paymentTxId"
            )) {
                assertEquals("فهرس $idx مفقود بعد 19→20", 1L,
                    scalar(db, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = '$idx'"))
            }
            // فهرس المفتاح فريد فعلاً — عقد at-most-once على مستوى القاعدة
            assertEquals(1L, scalar(
                db, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_refund_ledger_idempotencyKey' AND sql LIKE '%UNIQUE%'"))

            // 2) لا صف فقد — الفرع القائم بحرفيته (إلحاقي خالص)
            assertEquals("لا صف من الفروع فُقد", branchCount, scalar(db, "SELECT COUNT(*) FROM branches"))
            assertEquals("المخزن الرئيسي", branchName)

            // ═══ التحقق الحاسم: فتح القاعدة نفسها بRoom v20 (عدم المطابقة البايتية يرمى) ═══
            db.version = 20
            db.close()

            val ctx = ApplicationProvider.getApplicationContext<Context>()
            val room = androidx.room.Room.databaseBuilder(ctx, com.superbiz.app.data.db.AppDatabase::class.java, dbName!!)
                .allowMainThreadQueries()
                .addMigrations(*AppGraph.MIGRATIONS.toTypedArray())
                .build()
            try {
                kotlinx.coroutines.runBlocking {
                    // عدم المطابقة البايتية يرمى قبل هنا (RoomOpenHelper.checkIdentity)
                    // DAO كامل يقرأ الجدولين الجديدين ويكتبهما على المخطط الحقيقي
                    val dao = room.paymentsE22()
                    val txId = dao.insertTx(
                        com.superbiz.app.data.db.PaymentTxEntity(
                            invoiceId = 7L, amountPiasters = 15_250L, method = "CARD",
                            status = "CAPTURED", psp = "MOYASAR", authRef = "auth-x", createdAt = 2000L,
                        )
                    )
                    assertTrue(txId > 0L)
                    assertTrue(dao.insertRefund(
                        com.superbiz.app.data.db.RefundLedgerEntity(
                            idempotencyKey = "inv-7|refund|1", paymentTxId = txId,
                            amountPiasters = 5_000L, refundRef = "rf-1", createdAt = 3000L,
                        )
                    ) > 0L)
                    // المفتاح نفسه لا يُدرج مرتين — القيد الفريد يرمى (عقد at-most-once)
                    try {
                        dao.insertRefund(
                            com.superbiz.app.data.db.RefundLedgerEntity(
                                idempotencyKey = "inv-7|refund|1", paymentTxId = txId,
                                amountPiasters = 5_000L, refundRef = "rf-2", createdAt = 4000L,
                            )
                        )
                        fail("الفهرس الفريد على idempotencyKey يجب أن يرمي على التكرار")
                    } catch (e: android.database.sqlite.SQLiteConstraintException) {
                        // مقصود — العقد يعمل على مستوى القاعدة لا الكود
                    }
                    assertEquals(5_000L, dao.refundedSumPiasters(txId))
                    assertEquals("الفرع المزروع نجا عبر الترحيل والفتح بRoom", "المخزن الرئيسي", room.branches().byId(1L)!!.name)
                }
            } finally {
                room.close()
            }
        } finally {
            if (db.isOpen) db.close()
        }
    }
}
