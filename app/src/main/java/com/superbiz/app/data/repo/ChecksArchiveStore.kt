package com.superbiz.app.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// مخزن أرشفة الشيكات — بديل الاحتياطي R2 عن وظيفة 22 (مشاركة الكشف النصي
// مكافئ قائم في PartyStatementSheet: واتساب/رسائل/مشاركة عامة). الأرشفة بلا أي تعديل
// على مخطط Room: معرّفات الشيكات المؤرشفة في DataStore مستقل، والمنطق النقي في
// domain/ChecksArchive (الترشيح والتقسيم مختبَران وحداتياً).

private val Context.checksArchiveStore: DataStore<Preferences> by preferencesDataStore(name = "checks_archive")

class ChecksArchiveStore(private val context: Context) {

    private val KEY = stringSetPreferencesKey("archived_check_ids")

    /** معرّفات الشيكات المؤرشفة — تدفق حي يغذّي ترشيح القائمة في ChecksVM */
    val ids: Flow<Set<Long>> = context.checksArchiveStore.data.map { p ->
        p[KEY]?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
    }

    /** أرشفة دفعة معرّفات (زر «أرشفة المسدد») */
    suspend fun archiveAll(newIds: Collection<Long>) {
        if (newIds.isEmpty()) return
        context.checksArchiveStore.edit { p ->
            val cur = p[KEY]?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
            p[KEY] = (cur + newIds).map { it.toString() }.toSet()
        }
    }

    /**تعيين المجموعة كاملة (استبدال) — استعادة النسخة الاحتياطية
 * تحتاج استبدال الأرشيف بما في الملف لا دمجاً مع بقايا الجهاز القديم */
    suspend fun replaceAll(newIds: Collection<Long>) {
        context.checksArchiveStore.edit { p ->
            if (newIds.isEmpty()) p.remove(KEY)
            else p[KEY] = newIds.map { it.toString() }.toSet()
        }
    }

    /** استعادة شيك مؤرشف إلى القائمة النشطة */
    suspend fun restore(id: Long) {
        context.checksArchiveStore.edit { p ->
            val cur = p[KEY]?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
            val next = cur - id
            if (next.isEmpty()) p.remove(KEY) else p[KEY] = next.map { it.toString() }.toSet()
        }
    }
}
