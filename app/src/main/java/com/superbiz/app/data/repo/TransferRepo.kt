package com.superbiz.app.data.repo

import androidx.room.withTransaction
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.BranchEntity
import com.superbiz.app.data.db.BranchStockEntity
import com.superbiz.app.data.db.StockTransferEntity
import com.superbiz.app.data.db.StockTransferLineEntity
import kotlinx.coroutines.flow.Flow

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [E2.1 V 3.5.0] محرك النقل بين الفروع — حالة نقل صريحة، لا مخزون شبح أبداً
 * ═══════════════════════════════════════════════════════════════════════════
 * عقد خارطة الطريق E2.1: «النقل البيني بحالة صريحة: إرسال، استلام، تسوية
 * فروق — لا مخزون شبح أبداً» و«ثابت الحفظ مُختبر خصائصياً: مجموع المخزون
 * عبر الفروع محفوظ في كل مسار نقل».
 *
 * العقود المقفولة (Room حقيقي — MultiBranchCoreTest):
 *  1) **الحالة الصريحة**: نقل يبدأ IN_TRANSIT (خصم المصدر لحظة الإرسال) ولا
 *     يضاف للوجهة إلا عند الاستلام الفعلي — الفجوة الزمنية مرئية في قائمة
 *     «قيد النقل» لا مختفية في رقم.
 *  2) **الحفظ**: مجموع مستوى النقل (Σ branch_stock + Σ خطوط قيد النقل)
 *     ثابت عبر كل مسار: إرسال ينقل من فرع إلى قيد النقل، استلام ينقل من
 *     قيد النقل إلى فرع، إلغاء يرد قيد النقل كله للمصدر — لا مسار يخلق
 *     أو يفقد وحدة واحدة (فحص خصائصي مبذر يقلب التسلسلات).
 *  3) **الفروق**: receivedQty ≤ qty بالقصّ (لا زيادة بشر فوق المرسل)،
 *     والفرق (qty − receivedQty) مسجَّل بالسطر للسطور — تسوية بصرية صادقة.
 *  4) **الفشل المغلق**: مصدر بلا كمية كافية يرفض الإرسال كله (لا نقل جزئي)،
 *     نقل غير قيد-النقل لا يُستلم ولا يُلغى مرتين، فرع غير نشط لا ينقل.
 *
 * RBAC: كل الكتابات INVENTORY_MANAGE (سطر 6-ب — المالك والمدير) بنفس
 * عقد E0.4: الضمني/SYSTEM يمران حرفياً، والحرس عند حد المستودع.
 *
 * قرار مستوى V3.5 النواتي (موثق في Entities.kt): مستوى النقل يُبذر من
 * المخزون الكلي ترحيلياً ويُدار حصراً بهذا المحرك؛ تدقيق المستويين مع
 * الجرد ونقطة البيع = سطح V3.6 (نصف الإيبك الثاني بعقد خارطة الطريق).
 */
class TransferRepo(private val db: AppDatabase) {

    companion object {
        const val STATUS_IN_TRANSIT = 0
        const val STATUS_RECEIVED = 1
        const val STATUS_CANCELED = 2
        /** الفرع الرئيسي الذي يبذره الترحيل 18→19 (id الحقيقي يقرأ من القاعدة) */
        const val MAIN_BRANCH_NAME = "المخزن الرئيسي"
    }

    // ─── الفروع ───

    fun branches(): Flow<List<BranchEntity>> = db.branches().all()

    suspend fun createBranch(name: String, kind: Int = 0, today: Long = System.currentTimeMillis()): Long {
        com.superbiz.app.domain.rbac.RoleGate.require(
            com.superbiz.app.domain.rbac.SessionState.effective(),
            com.superbiz.app.domain.rbac.Op.INVENTORY_MANAGE
        )
        require(name.isNotBlank()) { "branch name required" }
        require(kind == 0 || kind == 1) { "branch kind is store or warehouse" }
        return db.branches().insert(BranchEntity(name = name.trim(), kind = kind, createdAt = today))
    }

    suspend fun setBranchActive(id: Long, active: Boolean) {
        com.superbiz.app.domain.rbac.RoleGate.require(
            com.superbiz.app.domain.rbac.SessionState.effective(),
            com.superbiz.app.domain.rbac.Op.INVENTORY_MANAGE
        )
        db.branches().setActive(id, active)
    }

    suspend fun branchQty(branchId: Long, productId: Long): Double =
        db.branchStock().qtyAt(branchId, productId) ?: 0.0

    suspend fun transferPlaneTotal(productId: Long): Double =
        db.branchStock().totalFor(productId)

    // ─── النقل ───

    fun transfers(): Flow<List<StockTransferEntity>> = db.stockTransfers().all()

    suspend fun lines(transferId: Long): List<StockTransferLineEntity> =
        db.stockTransferLines().forTransfer(transferId)

    /**
     * إرسال نقل: فحص كفاية المصدر داخل المعاملة، خصم فوري، حالة IN_TRANSIT.
     * أي فشل يتراجع عن كل شيء — لا نقل جزئي ولا خصم بلا سجل.
     */
    suspend fun sendTransfer(
        fromBranchId: Long,
        toBranchId: Long,
        lines: List<Pair<Long, Double>>, // (productId, qty)
        today: Long,
        note: String = ""
    ): Long {
        com.superbiz.app.domain.rbac.RoleGate.require(
            com.superbiz.app.domain.rbac.SessionState.effective(),
            com.superbiz.app.domain.rbac.Op.INVENTORY_MANAGE
        )
        require(fromBranchId != toBranchId) { "source and destination must differ" }
        require(lines.isNotEmpty()) { "no lines" }
        require(lines.all { it.second > 0.0 && it.second.isFinite() }) { "qty must be finite positive" }
        require(note.length <= 500) { "note too long" }
        return db.withTransaction {
            val from = db.branches().byId(fromBranchId) ?: throw IllegalArgumentException("source branch missing")
            val to = db.branches().byId(toBranchId) ?: throw IllegalArgumentException("destination branch missing")
            require(from.active && to.active) { "inactive branch cannot transfer" }
            // فحص الكفاية داخل المعاملة (قراءة حديثة لا نسخة متصل)
            for ((pid, q) in lines) {
                val have = db.branchStock().qtyAt(fromBranchId, pid) ?: 0.0
                require(have >= q) { "insufficient transfer-plane stock: product $pid has $have, needs $q" }
            }
            for ((pid, q) in lines) db.branchStock().addQty(fromBranchId, pid, -q)
            val tid = db.stockTransfers().insert(
                StockTransferEntity(
                    fromBranchId = fromBranchId, toBranchId = toBranchId,
                    status = STATUS_IN_TRANSIT, sentAt = today, note = note
                )
            )
            db.stockTransferLines().insertAll(
                lines.map { StockTransferLineEntity(transferId = tid, productId = it.first, qty = it.second) }
            )
            tid
        }
    }

    /**
     * استلام: القيم الغائبة من الخريطة = استلام كامل (الافتراض العملي)، والقيم
     * تُقصّ على [0, qty] — لا زيادة بشر فوق المُرسل. المستلم يضاف للوجهة
     * والفروق تبقى مسجلة بالسطور (تسوية بصرية صادقة لا اختفاء).
     */
    suspend fun receiveTransfer(
        transferId: Long,
        receipts: Map<Long, Double> = emptyMap(),
        today: Long
    ): List<StockTransferLineEntity> {
        com.superbiz.app.domain.rbac.RoleGate.require(
            com.superbiz.app.domain.rbac.SessionState.effective(),
            com.superbiz.app.domain.rbac.Op.INVENTORY_MANAGE
        )
        return db.withTransaction {
            val t = db.stockTransfers().byId(transferId)
                ?: throw IllegalArgumentException("transfer missing")
            require(t.status == STATUS_IN_TRANSIT) { "transfer is not in transit" }
            val lines = db.stockTransferLines().forTransfer(transferId)
            for (l in lines) {
                val requested = receipts[l.productId] ?: l.qty
                require(requested.isFinite() && requested >= 0.0) {
                    "receipt for product ${l.productId} must be finite non-negative"
                }
                val rec = requested.coerceIn(0.0, l.qty)
                db.branchStock().addQty(t.toBranchId, l.productId, rec)
                db.stockTransferLines().update(l.copy(receivedQty = rec))
            }
            db.stockTransfers().update(t.copy(status = STATUS_RECEIVED, receivedAt = today))
            db.stockTransferLines().forTransfer(transferId)
        }
    }

    /**
     * إلغاء نقل قيد النقل — يرد كل ما خُصم للمصدر حرفياً (لا شبح: الإلغاء
     * يعيد بالضبط ما أُرسل، ولا يمس ما استُلم سابقاً — لا يوجد مسار استلام جزئي
     * قبل الإقفال بحكم الحالة الواحدة).
     */
    suspend fun cancelTransfer(transferId: Long): Unit {
        com.superbiz.app.domain.rbac.RoleGate.require(
            com.superbiz.app.domain.rbac.SessionState.effective(),
            com.superbiz.app.domain.rbac.Op.INVENTORY_MANAGE
        )
        db.withTransaction {
            val t = db.stockTransfers().byId(transferId)
                ?: throw IllegalArgumentException("transfer missing")
            require(t.status == STATUS_IN_TRANSIT) { "only in-transit transfers can be canceled" }
            for (l in db.stockTransferLines().forTransfer(transferId)) {
                db.branchStock().addQty(t.fromBranchId, l.productId, l.qty)
            }
            db.stockTransfers().update(t.copy(status = STATUS_CANCELED))
        }
    }

    /**
     * تقرير الفروق — للنقل **المُستلم** حصراً: السطور التي لم يُستلم منها
     * كامل المرسل (قيد النقل بلا فروق بعد —receivedQty=0 ليس تفاوتاً).
     * نقل غير مستلم يعيد قائمة فارغة (فشل صامت محافظ؟ لا — الدلالة: بلا
     * استلام لا تعارف فروقاً؛ العرض يقرأ الحالة صريحةً أصلاً).
     */
    suspend fun varianceOf(transferId: Long): List<StockTransferLineEntity> {
        val t = db.stockTransfers().byId(transferId) ?: return emptyList()
        if (t.status != STATUS_RECEIVED) return emptyList()
        return db.stockTransferLines().forTransfer(transferId)
            .filter { kotlin.math.abs(it.qty - it.receivedQty) > 1e-9 }
    }

    /** مجموع قيد النقل لصنف — ثابت الحفظ يقرأ به في الاختبارات والواجهة */
    suspend fun inTransitTotal(productId: Long): Double {
        val transfers = db.stockTransfers().inTransitOnce().map { it.id }.toSet()
        var total = 0.0
        for (tid in transfers) {
            for (l in db.stockTransferLines().forTransfer(tid)) {
                if (l.productId == productId) total += l.qty
            }
        }
        return total
    }
}
