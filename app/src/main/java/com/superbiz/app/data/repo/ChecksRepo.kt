package com.superbiz.app.data.repo

import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.CheckEntity
import com.superbiz.app.domain.AccountingEngine
import kotlinx.coroutines.flow.Flow
import androidx.room.withTransaction

/**
 * مستودع الشيكات — : تغيير الحالة مع قيده المحاسبي وسطر الدفعة ذرّياً
 * لا شيك «محصّل» بلا قيده ولا قيد بلا دفعة — كل شيء معاً أو لا شيء.
*/
class ChecksRepo(private val db: AppDatabase, private val ledger: LedgerRepo) {

    fun checks(): Flow<List<CheckEntity>> = db.checks().all()
    suspend fun check(id: Long): CheckEntity? = db.checks().byId(id)
    suspend fun save(c: CheckEntity): Long {
        // حراسة طبقة المستودع — مبلغ موجب واستحقاق بعد الإصدار ورقم غير فارغ
        // فحص isFinite — كان Infinity يعبر فحص amount > 0 ثم يُرحّل قيداً لانهائياً عند التحصيل
        // [P33-P8] amount قروش Long — isFinite ساق (Long لا يكون NaN) ومقارنة صحيحة تامة
        require(c.amount > 0L) { "check amount must be positive" }
        require(c.dueDate >= c.issueDate) { "check due date must be on/after issue date" }
        require(c.number.isNotBlank()) { "check number required" }
        // @Upsert في مسار التحديث يعيد -1 — نجيب معرّف الكيان الصريح عند التعديل
        val id = db.checks().upsert(c)
        return if (id == -1L && c.id != 0L) c.id else id
    }

    /**
 * : حذف الشيك ينظّف قيوده («check») ودفعاته (CHECK / CHECK_BOUNCE) —
 * كانت تبقى أشباحاً في الدفتر بعد الحذف. الإلغاء غير مشروط بالحالة: قيد الصفاء
 * وارتداده معاً صافي أثرهما صفر دائماً، فحذف كل قيود «check» لهذا الشيك آمن
 * في كل الحالات. إلغاء الترحيل وحذف الدفعات قبل حذف صف الشيك نفسه.
*/
    suspend fun delete(id: Long) {
        db.withTransaction {
            db.checks().byId(id) ?: return@withTransaction
            ledger.unpostInternal("check", id)
            db.payments().deleteByCheckId(id)
            db.checks().delete(id)
        }
        ledger.onMutate?.invoke()
    }

    /**
     * تغيير حالة شيك مع القيد المحاسبي المناسب:
     * وارد + محصّل  → نقد / ذمم العميل
     * وارد + مرتجع  → عكس القيد
     * صادر + محصّل  → ذمم المورد / نقد
     */
    suspend fun setStatus(c: CheckEntity, newStatus: Int, today: Long = System.currentTimeMillis()) {
        if (c.status == newStatus) return
        val party = ledger.party(c.partyId) ?: return
        db.withTransaction {
            // fix: القرار الآن من حالة الشيك المقروءة داخل المعاملة نفسها لا من
            // كائن المتصل المتقادم — كانت النقرة المزدوجة بنسخة قديمة تُرحّل القيد
            // مرتين وتدرج دفعتين لشيك واحد
            val fresh = db.checks().byId(c.id) ?: return@withTransaction
            val old = fresh.status
            if (old == newStatus) return@withTransaction
            // الترحيل كان يستخدم كائن المتصل (قديماً) بدل النسخة الحديثة —
            // تعديل الشيك بين عرض القائمة والنقر كان يرحّل مبلغاً/طرفاً قديمين مع صف حديث.
            // كل القيم أدناه من fresh حصراً.
            if (ledger.party(fresh.partyId) == null) return@withTransaction
            // قائمة بيضاء للتحولات — كل تحول غير مذكور يُرفض بلا تغيير حالة
            var accepted = false
            when {
                // صفاء من (معلّق/مودع/مرتجع): قيد الصفاء + سطر الدفعة
                newStatus == 2 && (old == 0 || old == 1 || old == 3) -> {
                    if (fresh.direction == 0) {
                        ledger.postInternal(AccountingEngine.checkClearedIn(fresh.partyId, fresh.amount, today, c.id))
                        db.payments().insert(
                            com.superbiz.app.data.db.Payment(
                                partyId = fresh.partyId, checkId = c.id, amount = fresh.amount,
                                date = today, direction = 0, method = "CHECK", note = "تحصيل شيك ${fresh.number}")
                        )
                    } else {
                        ledger.postInternal(AccountingEngine.checkClearedOut(fresh.partyId, fresh.amount, today, c.id))
                        db.payments().insert(
                            com.superbiz.app.data.db.Payment(
                                partyId = fresh.partyId, checkId = c.id, amount = fresh.amount,
                                date = today, direction = 1, method = "CHECK", note = "صرف شيك ${fresh.number}")
                        )
                    }
                    accepted = true
                }
                // ارتجاع بعد صفاء: عكس القيد + سطر الدفعة — الصادر كان يرتدّ
                // بلا عكس فيبقى خروج النقد معلقاً؛ سُدّ الثقب بـ checkBouncedOut
                newStatus == 3 && old == 2 -> {
                    if (fresh.direction == 0) {
                        ledger.postInternal(AccountingEngine.checkBouncedIn(fresh.partyId, fresh.amount, today, c.id))
                        db.payments().insert(
                            com.superbiz.app.data.db.Payment(
                                partyId = fresh.partyId, checkId = c.id, amount = fresh.amount,
                                date = today, direction = 1, method = "CHECK_BOUNCE", note = "ارتجاع شيك ${fresh.number}")
                        )
                    } else {
                        ledger.postInternal(AccountingEngine.checkBouncedOut(fresh.partyId, fresh.amount, today, c.id))
                        db.payments().insert(
                            com.superbiz.app.data.db.Payment(
                                partyId = fresh.partyId, checkId = c.id, amount = fresh.amount,
                                date = today, direction = 0, method = "CHECK_BOUNCE", note = "ارتجاع شيك صادر ${fresh.number}")
                        )
                    }
                    accepted = true
                }
                // ارتجاع قبل أي صفاء: لم يُرحّل شيء بعد — تغيير حالة فقط
                newStatus == 3 && (old == 0 || old == 1) -> accepted = true
                // إيداع/إلغاء الإيداع (0↔1): لا قيد محاسبي في هاتين الحالتين قط —
                // تغيير حالة فقط (زر «إيداع» في شاشة الشيكات يعتمد عليها)
                (newStatus == 0 || newStatus == 1) && (old == 0 || old == 1) -> accepted = true
                // إعادة فتح بعد ارتجاع (تصحيح خطأ إدخال): لا شيء مُرحّل — تغيير حالة فقط
                (newStatus == 0 || newStatus == 1) && old == 3 -> accepted = true
                // باقي التحولات مرفوضة — خصوصاً 2 → 0/1 الذي يتطلب إلغاء ترحيل
                // لم تتيح الواجهة تنفيذه قط: لا قيد ولا دفعة ولا تغيير حالة
                else -> Unit
            }
            // تحديث صف الحالة في الفروع المقبولة فقط
            if (accepted) db.checks().setStatus(c.id, newStatus)
        }
        ledger.onMutate?.invoke()
    }

    suspend fun dueSoon(from: Long, to: Long): List<CheckEntity> = db.checks().dueBetween(from, to)
}
