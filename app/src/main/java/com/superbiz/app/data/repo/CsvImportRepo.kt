package com.superbiz.app.data.repo

import androidx.room.withTransaction
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.StockMove
import com.superbiz.app.domain.algo.CsvImportKit

/**
 * [H4-6 V 2.5.0] منفّذ الاستيراد CSV — الأصناف والأطراف (جولة ذهاب-إياب مع التصدير)
 *
 * عقود التنفيذ (نفس عقد CsvImportKit مع الإضافات التنفيذية):
 * 1) **معاملة واحدة** لكل ملف — نجاح جزئي حقيقي (صفوف صالحة تُدخل كلها، التالفة
 *    تُتخطى بأسبابها) ولا حالة نصف مطبّقة عند فشل بنيوي (استثناء ⇒ تراجع كامل).
 * 2) **المطابقة**: صنف برمز SKU غير فارغ يطابق بالرمز وإلا بالاسم الحرفي؛ طرف
 *    بهاتف غير فارغ يطابق بالهاتف وإلا بالاسم. الموجود يُحدَّث (هوية/أسعار/حد
 *    طلب/وحدة/باركود) والمفقود يُنشأ.
 * 3) **المخزون حركة لا كتابة**: فرق الكمية على صنف قائم يُقيَّد حركة تسوية
 *    (StockMove reason=CSV_IMPORT refType=csv_import) فيبقى سجل المخزون مكتملاً —
 *    لا كتابة صامتة فوق stockQty (نفس حسم «الرصيد لا يُدخل نصاً» للأطراف).
 * 4) **بوابة RBAC على المستدعي**: هذا المستودع نقي بلا جلسة — الواجهة تستدعيه
 *    خلف RoleGate(Op.BACKUP_RESTORE) (باب المالك — إدخال جماعي متغيّر للبيانات).
 */
class CsvImportRepo(private val db: AppDatabase) {

    data class Stats(
        val kind: String,
        val upserted: Int,
        val skipped: List<String>
    )

    suspend fun import(
        text: String,
        customerWord: String,
        supplierWord: String,
        bothWord: String
    ): Stats {
        val csv = CsvImportKit.parse(text)
            ?: return Stats("unknown", 0, listOf("الملف فارغ أو بلا رأس صالح"))
        return when (CsvImportKit.detectKind(csv.header)) {
            "products" -> importProducts(csv)
            "parties" -> importParties(csv, customerWord, supplierWord, bothWord)
            else -> Stats("unknown", 0, listOf("لم يُتعرف على نوع الملف — يلزم رأس أصناف أو أطراف"))
        }
    }

    private suspend fun importProducts(csv: CsvImportKit.ParsedCsv): Stats {
        val mapped = CsvImportKit.mapProducts(csv)
            ?: return Stats("products", 0, listOf("رأس الأصناف ناقص — عمود «الاسم» مطلوب"))
        var upserted = 0
        val skipped = ArrayList<String>(mapped.skipped)
        db.withTransaction {
            val existing = db.products().allOnceIncludingArchived()
            for (row in mapped.rows) {
                val match = if (row.sku.isNotBlank()) existing.firstOrNull { it.sku == row.sku }
                else existing.firstOrNull { it.name == row.name }
                if (match != null) {
                    // تحديث الهوية والأسعار — الكمية عبر حركة تسوية لا كتابة صامتة (بند 3)
                    db.products().upsert(
                        match.copy(
                            name = row.name, sku = row.sku, barcode = row.barcode,
                            unit = row.unit, costPrice = row.costPiasters, salePrice = row.salePiasters,
                            reorderLevel = row.reorderLevel
                        )
                    )
                    val delta = row.stockQty - match.stockQty
                    if (kotlin.math.abs(delta) > 1e-9) {
                        db.products().addQty(match.id, delta)
                        db.stockMoves().insert(
                            StockMove(
                                productId = match.id, qty = delta, reason = "ADJUST",
                                date = System.currentTimeMillis(), refType = "csv_import", refId = 0,
                                note = "تسوية استيراد CSV (${if (delta > 0) "+" else ""}${delta})"
                            )
                        )
                    }
                } else {
                    db.products().upsert(
                        com.superbiz.app.data.db.Product(
                            name = row.name, sku = row.sku, barcode = row.barcode,
                            unit = row.unit, costPrice = row.costPiasters, salePrice = row.salePiasters,
                            stockQty = row.stockQty, reorderLevel = row.reorderLevel
                        )
                    )
                }
                upserted++
            }
        }
        return Stats("products", upserted, skipped)
    }

    private suspend fun importParties(
        csv: CsvImportKit.ParsedCsv,
        customerWord: String,
        supplierWord: String,
        bothWord: String
    ): Stats {
        val mapped = CsvImportKit.mapParties(csv, customerWord, supplierWord, bothWord)
            ?: return Stats("parties", 0, listOf("رأس الأطراف ناقص — عمود «الاسم» مطلوب"))
        var upserted = 0
        val skipped = ArrayList<String>(mapped.skipped)
        db.withTransaction {
            val existing = db.parties().exportOnce()
            for (row in mapped.rows) {
                val match = if (row.phone.isNotBlank()) existing.firstOrNull { it.phone == row.phone }
                else existing.firstOrNull { it.name == row.name }
                if (match != null) {
                    db.parties().upsert(match.copy(name = row.name, phone = row.phone, type = row.type))
                } else {
                    db.parties().upsert(
                        com.superbiz.app.data.db.Party(name = row.name, phone = row.phone, type = row.type)
                    )
                }
                upserted++
            }
        }
        return Stats("parties", upserted, skipped)
    }
}
