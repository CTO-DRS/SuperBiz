package com.superbiz.app

import com.superbiz.app.domain.backup.BACKUP_TABLE_COUNT
import com.superbiz.app.domain.backup.BackupData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P36-BK] اختبارات عقد التغطية النقية (JUnit4 بلا Android) — رقاقة «تغطية النسخ: 23/23»:
 *
 * الرقاقة المعروضة في مركز الإعدادات تقرأ ثابت [BACKUP_TABLE_COUNT] — لو أُضيف جدول
 * جديد إلى BackupData ونسي المطوّر رفع الثابت (أو العكس) لانحرفت الواجهة عن الواقع
 * المصدَّر بصمت. الاختبار يربط الاثنين بانعكاس Java القياسي (بلا kotlin-reflect):
 * عدد حقول BackupData الناقلة للجداول (كل الحقول ناقص version/exportedAt غير
 * الجدوليتين) يجب أن يساوي الثابت المعلن حرفياً، فأي انحراف مستقبلي يفشل هنا
 * قبل أن يكذب الرقم على المستخدم.
 */
class BackupContractP36Test {

    /** حقول الجداول = كل حقول BackupData عدا الميتاداتا (version/exportedAt) والساكنات (علامة $stable لمترجم Compose) */
    private val tableFields = BackupData::class.java.declaredFields
        .filter { java.lang.reflect.Modifier.isStatic(it.modifiers).not() }
        .filter { it.name !in setOf("version", "exportedAt") }

    @Test
    fun `BACKUP_TABLE_COUNT matches BackupData table fields`() {
        assertEquals(
            "ثابت التغطية المعلن انحرف عن واقع BackupData",
            BACKUP_TABLE_COUNT, tableFields.size
        )
        assertEquals(25, BACKUP_TABLE_COUNT)
    }

    @Test
    fun `BackupData carries exactly 25 list tables`() {
        // عقد مزدوج صريح: كل حقول الجداول قوائم (انحراف النوع يكسر buildBackupJson)
        assertEquals(25, tableFields.size)
        tableFields.forEach { f ->
            assertTrue(
                "حقل الجدول ${f.name} يجب أن يكون قائمة صفوف",
                List::class.java.isAssignableFrom(f.type)
            )
        }
    }
}
