package com.superbiz.app.data.repo

import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.Visit
import kotlinx.coroutines.flow.Flow

/**
 * [P12-b] مستودع زيارات العملاء — طبقة رقيقة فوق VisitDao (نمط بقية المستودعات).
 *
 * الزيارة سجل GPS مؤرَّخ لطرف: تُضاف فقط ولا تُعدَّل ولا تُحذف (دفتر تاريخي صادق —
 * التقرير يُبنى فوقها دائماً من السجل الكامل). الإحداثيات اختيارية: زيارة سُجّلت
 * بلا موقع GPS صالح تُخزَّن بـ lat/lng = null ولا تُستخدم في حسابات المسافة.
 *
 * عقد ثابت مع الواجهة (VisitsSection وغيرها): التوقيعات الأربعة أدناه —
 * recordVisit يعيد id الصف المدرج، وكل القراءات ترتيبها visitedAt تنازلياً.
 *
 * بلا مفاتيح أجنبية بين visits وparties عمداً (توثيق القرار عند كيان Visit):
 * حذف طرف لا يمس سجل زياراته، وأطراف محذوفة تُتخطى في طبقة التقرير فقط.
 */
class VisitsRepo(private val db: AppDatabase) {

    /**
     * تسجيل زيارة الآن لطرف معرفاً بـ partyId — الإحداثيات اختيارية (null = بلا موقع)،
     * والملاحظة افتراضها فارغة. يعيد id الصف المدرج (مفيد للتتبع/التصدير لاحقاً).
     */
    suspend fun recordVisit(partyId: Long, lat: Double?, lng: Double?, note: String = ""): Long =
        db.visits().insert(Visit(partyId = partyId, lat = lat, lng = lng, note = note))

    /** تدفق حي بكل الزيارات الأحدث أولاً — يحدّث تقرير الواجهة مع كل تسجيل */
    fun all(): Flow<List<Visit>> = db.visits().all()

    /** لقطة واحدة لكل الزيارات الأحدث أولاً */
    suspend fun allOnce(): List<Visit> = db.visits().allOnce()

    /** زيارات طرف واحد الأحدث أولاً — كشف تاريخ زيارات عميل محدد */
    suspend fun forParty(partyId: Long): List<Visit> = db.visits().forParty(partyId)

    /**
     * [P13-a] حذف سجل زيارة واحد نهائياً — الاستثناء الوحيد على قاعدة «الزيارات لا تُحذف»:
     * حذف صريح بموافقة المستخدم (تأكيد الواجهة في VisitsSection) لتصحيح تسجيل خاطئ،
     * والقراءات كلها تدفقات حية فالتقرير يتحدّث تلقائياً بعد الحذف.
     */
    suspend fun deleteVisit(visitId: Long) = db.visits().deleteVisit(visitId)
}
