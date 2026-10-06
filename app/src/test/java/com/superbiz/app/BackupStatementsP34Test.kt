package com.superbiz.app

import com.superbiz.app.data.db.AuditLogEntity
import com.superbiz.app.data.db.NoteTemplateEntity
import com.superbiz.app.data.db.SignatureEntity
import com.superbiz.app.data.db.StatementDeliveryEntity
import com.superbiz.app.data.db.StatementEntity
import com.superbiz.app.data.db.StatementRuleEntity
import com.superbiz.app.data.db.StatementTemplateEntity
import com.superbiz.app.data.db.StampEntity
import com.superbiz.app.domain.backup.BackupData
import com.superbiz.app.domain.backup.BackupTables
import com.superbiz.app.domain.backup.buildBackupJson
import com.superbiz.app.domain.backup.parseBackup
import com.superbiz.app.domain.backup.planImport
import com.superbiz.app.domain.backup.totalRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P34-M1] اختبارات نطاق v4 النقية (JUnit4 بلا Android) — اكتمال النسخ الاحتياطي 23/23
 * • دوران كامل build→parse لجداول منظومة الكشوف الثمانية بمقارنة كل كيان حقلاً حقلاً
 * (بما فيها الحقول القابلة للإفراغ وحدود أنصاف القيم).
 * • خطة الدمج سلسلة اليتيم البنيوية (كشف بطرف غائب يتخطى، وتسليمه يتخطى بالتسلسل)
 * وقواعد الموجود/المكرر/التالف، وانتقال الخطة إلى التنفيذ بنفس العدّادات.
 * • [P33-P8] عقد القروش في الحقل المالي الوحيد بالجدولين الجديدين (rule.threshold)
 * ملف قديم بديناريّاته الريالية يُحوَّل بقرش، وكسرٌ عشريٌّ في ملف قروش يُرفض صراحة.
*/
class BackupStatementsP34Test {

    // ─── بذور ثابتة حتمية — حقول null وطرفية متعمدة لتغطية القارئات ───

    private fun template(id: Long = 1) = StatementTemplateEntity(
        id = id, name = "قالب ذهبي «حصري» 50%", baseTemplateId = "CLASSIC",
        configJson = "{\"cols\":[\"date\",\"amount\"],\"rtl\":true}",
        isDefault = true, favorite = true, createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_001_000L
    )

    private fun signature(id: Long = 2) = SignatureEntity(
        id = id, name = "عبدالله المالك", jobTitle = null, imagePath = "filesDir/sign.png",
        isDefault = true, active = false, createdAt = 1_700_000_002_000L
    )

    private fun stamp(id: Long = 3) = StampEntity(
        id = id, name = "ختم المنشأة", imagePath = "filesDir/stamp.png",
        isDefault = false, active = true, createdAt = 1_700_000_003_000L
    )

    private fun noteTemplate(id: Long = 4) = NoteTemplateEntity(
        id = id, title = "تحية طيبة", body = "يرجى السداد خلال 30 يوماً،\nشاكرين تعاونكم.",
        isDefault = true
    )

    private fun statement(id: Long = 5, partyId: Long = 10) = StatementEntity(
        id = id, statementNumber = "STATEMENT-2026-000001",
        verificationId = "SB-ST-20260924-000001", partyId = partyId,
        fromTs = 1_700_407_200_000L, toTs = 1_702_348_799_000L,
        templateId = "7", currency = "SAR", contentHash = "ab12cd34ef56",
        filePath = "filesDir/pdfs/STATEMENT-2026-000001.pdf", note = null,
        createdAt = 1_702_348_800_000L, lang = "BILINGUAL"
    )

    private fun delivery(id: Long = 6, statementId: Long = 5) = StatementDeliveryEntity(
        id = id, statementId = statementId, channel = "SMTP", status = "RETRYING",
        attempts = 3, lastError = "smtp: connection reset", sentAt = null,
        scheduledFor = 1_702_400_000_000L, dedupKey = "delivery:5:SMTP", lastAttemptAt = 1_702_399_000_000L
    )

    private fun rule(id: Long = 7) = StatementRuleEntity(
        id = id, name = "كشف شهري أول الشهر", enabled = true,
        partyMode = "SELECTED", partyIdsJson = "[10,20]",
        frequency = "MONTHLY", weekday = null, dayOfMonth = 31,
        hour = 23, minute = 59, periodPreset = "PREVIOUS_MONTH",
        templateId = null, signatureId = 2L, stampId = 3L, channel = "EMAIL",
        eventFlagsJson = null, threshold = 50_000L, // 500 ريال قروش
        lastRunAt = 1_702_000_000_000L, nextRunAt = 1_702_560_000_000L
    )

    private fun audit(id: Long = 8) = AuditLogEntity(
        id = id, actor = "owner", action = "STATEMENT_ISSUE",
        details = "statement=STATEMENT-2026-000001 hash=ab12cd34ef56",
        ts = 1_702_348_800_100L
    )

    private fun fullData() = BackupData(
        version = 1, exportedAt = 1_702_400_000_000L,
        statementTemplates = listOf(template()), signatures = listOf(signature()),
        stamps = listOf(stamp()), noteTemplates = listOf(noteTemplate()),
        statements = listOf(statement()), statementDeliveries = listOf(delivery()),
        statementRules = listOf(rule()), auditLog = listOf(audit())
    )

    // ─── دوران كامل حقلاً حقلاً ───

    @Test
    fun `roundtrip all eight statement tables field by field`() {
        val json = buildBackupJson(fullData())
        val parsed = parseBackup(json)
        assertNull("النسخة الصحيحة يجب أن تُحلَّل بلا خطأ", parsed.error)
        val d = parsed.data!!
        assertEquals(listOf(template()), d.statementTemplates)
        assertEquals(listOf(signature()), d.signatures)
        assertEquals(listOf(stamp()), d.stamps)
        assertEquals(listOf(noteTemplate()), d.noteTemplates)
        assertEquals(listOf(statement()), d.statements)
        assertEquals(listOf(delivery()), d.statementDeliveries)
        assertEquals(listOf(rule()), d.statementRules)
        assertEquals(listOf(audit()), d.auditLog)
        // totalRows يحتسب الجداول الثمانية الجديدة في المجموع
        assertEquals(8, totalRows(fullData()))
    }

    @Test
    fun `tablesFound map covers all twenty five tables`() {
        val stats = planImport(fullData(), emptySet(), emptySet())
        assertEquals(25, stats.tablesFound.size)
        // الأسماء الثمانية الجديدة حاضرة بأحجامها الحقيقية
        assertEquals(1, stats.tablesFound[BackupTables.STATEMENT_TEMPLATES])
        assertEquals(1, stats.tablesFound[BackupTables.SIGNATURES])
        assertEquals(1, stats.tablesFound[BackupTables.STAMPS])
        assertEquals(1, stats.tablesFound[BackupTables.NOTE_TEMPLATES])
        assertEquals(1, stats.tablesFound[BackupTables.STATEMENTS])
        assertEquals(1, stats.tablesFound[BackupTables.STATEMENT_DELIVERIES])
        assertEquals(1, stats.tablesFound[BackupTables.STATEMENT_RULES])
        assertEquals(1, stats.tablesFound[BackupTables.AUDIT_LOG])
    }

    // ─── خطة الدمج سلسلة اليتيم البنيوية ───

    @Test
    fun `valid chain imports end to end and counters match`() {
        val stats = planImport(
            fullData(), existingPartyIds = setOf(10L), existingProductIds = emptySet() // الطرف موجود أصلاً → الكشف غير يتيم
        )
        assertEquals(1, stats.importedStatementTemplates)
        assertEquals(1, stats.importedSignatures)
        assertEquals(1, stats.importedStamps)
        assertEquals(1, stats.importedNoteTemplates)
        assertEquals(1, stats.importedStatements)
        assertEquals(1, stats.importedStatementDeliveries)
        assertEquals(1, stats.importedStatementRules)
        assertEquals(1, stats.importedAuditLog)
        assertEquals(0, stats.failed)
    }

    @Test
    fun `orphan statement skipped and its delivery skipped by chain`() {
        val stats = planImport(
            fullData(),
            existingPartyIds = emptySet(), existingProductIds = emptySet() // الطرف غائب → الكشف يتيم بنيوياً
        )
        assertEquals(0, stats.importedStatements)
        assertEquals(1, stats.skippedStatements)
        // التسليم يتخطى بالتسلسل: أبّه (الكشف) لن يُستورد فعلاً — CASCADE عقلياً
        assertEquals(0, stats.importedStatementDeliveries)
        assertEquals(1, stats.skippedStatementDeliveries)
        // المستقلات لا تتأثر بسلسلة اليتيم (بلا مفاتيح أجنبية)
        assertEquals(1, stats.importedStatementTemplates)
        assertEquals(1, stats.importedAuditLog)
        assertEquals(0, stats.failed) // يتيم بنيوي — لا صف تالف
    }

    @Test
    fun `existing statements in database are skipped not duplicated`() {
        val stats = planImport(
            fullData(),
            existingPartyIds = setOf(10L), existingProductIds = emptySet(),
            existingStatementIds = setOf(5L),
            existingStatementDeliveryIds = setOf(6L),
            existingStatementTemplateIds = setOf(1L),
            existingSignatureIds = setOf(2L),
            existingStampIds = setOf(3L),
            existingNoteTemplateIds = setOf(4L),
            existingStatementRuleIds = setOf(7L),
            existingAuditLogIds = setOf(8L)
        )
        // كل جداول الموجود أصلاً يُتخطى — لا يُعاد كتابة بيانات المستخدم
        assertEquals(1, stats.skippedStatementTemplates)
        assertEquals(1, stats.skippedSignatures)
        assertEquals(1, stats.skippedStamps)
        assertEquals(1, stats.skippedNoteTemplates)
        assertEquals(1, stats.skippedStatements)
        assertEquals(1, stats.skippedStatementDeliveries)
        assertEquals(1, stats.skippedStatementRules)
        assertEquals(1, stats.skippedAuditLog)
        assertEquals(0, stats.importedStatements)
    }

    @Test
    fun `invalid ids count as failed not skipped`() {
        val broken = BackupData(
            version = 1, exportedAt = 1L,
            statements = listOf(statement(id = 0L)), // معرف غير شرعي (العُرف: failed)
            statementDeliveries = listOf(delivery(id = -1L))
        )
        val stats = planImport(broken, emptySet(), emptySet())
        assertEquals(2, stats.failed)
        assertEquals(0, stats.importedStatements)
        assertEquals(0, stats.skippedStatements)
    }

    @Test
    fun `duplicate ids inside file win first`() {
        val dup = BackupData(
            version = 1, exportedAt = 1L,
            statementTemplates = listOf(template(id = 1), template(id = 1).copy(name = "نسخة ثانية"))
        )
        val stats = planImport(dup, emptySet(), emptySet())
        assertEquals(1, stats.importedStatementTemplates) // الأول يفوز
        assertEquals(1, stats.skippedStatementTemplates)  // الثاني مكرر داخل الملف
    }

    // ─── [P33-P8] عقد القروش في rule.threshold ───

    @Test
    fun `legacy file threshold in riyals converts to piasters`() {
        val legacy = """
            {"format":"superbiz-backup","version":1,"exportedAt":1,
             "tables":{"statement_rules":[
               {"id":7,"name":"قاعدة","enabled":true,"partyMode":"ALL","partyIdsJson":"",
                "frequency":"MONTHLY","weekday":null,"dayOfMonth":31,"hour":8,"minute":0,
                "periodPreset":"PREVIOUS_MONTH","templateId":null,"signatureId":null,
                "stampId":null,"channel":"EMAIL","eventFlagsJson":null,"threshold":100.0,
                "lastRunAt":null,"nextRunAt":null}]}}
        """.trimIndent()
        val parsed = parseBackup(legacy)
        assertNull(parsed.error)
        // 100 ريال (ملف ≤ بلا علامة p8) → 10,000 قروش — التحويل بقرش واحد
        assertEquals(10_000L, parsed.data!!.statementRules.single().threshold)
    }

    @Test
    fun `p8 file with fractional piasters rejected as corrupt row`() {
        val corrupt = """
            {"format":"superbiz-backup","version":1,"exportedAt":1,"p8":true,
             "tables":{"statement_rules":[
               {"id":7,"name":"قاعدة","enabled":true,"partyMode":"ALL","partyIdsJson":"",
                "frequency":"MONTHLY","weekday":null,"dayOfMonth":31,"hour":8,"minute":0,
                "periodPreset":"PREVIOUS_MONTH","templateId":null,"signatureId":null,
                "stampId":null,"channel":"EMAIL","eventFlagsJson":null,"threshold":100.5,
                "lastRunAt":null,"nextRunAt":null}]}}
        """.trimIndent()
        val parsed = parseBackup(corrupt)
        // كسر عشري في ملف قروش = صف تالف صريح — لا قَطّ صامت يخسر قروشاً
        assertNotNull("كسر عشري في ملف p8 يجب أن يُرفض", parsed.error)
        assertNull(parsed.data)
    }

    @Test
    fun `missing statement tables in old file parse to empty lists`() {
        // ملف v1.. لا يحوي جداول الكشوف أصلاً — تُقرأ قوائم فارغة بلا انهيار
        val old = """
            {"format":"superbiz-backup","version":1,"exportedAt":1,
             "tables":{"parties":[{"id":1,"name":"طرف","phone":"","type":0,"note":"",
             "createdAt":0,"archived":false,"favorite":false,"lat":null,"lng":null}]}}
        """.trimIndent()
        val parsed = parseBackup(old)
        assertNull(parsed.error)
        assertTrue(parsed.data!!.statementTemplates.isEmpty())
        assertTrue(parsed.data!!.statements.isEmpty())
        assertTrue(parsed.data!!.auditLog.isEmpty())
        // خطة الدمج على ملف قديم: جداول v4 كلها صفرية — لا اكتشاف ولا إخفاق
        val stats = planImport(parsed.data!!, emptySet(), emptySet())
        assertEquals(0, stats.importedStatements)
        assertEquals(0, stats.tablesFound[BackupTables.STATEMENTS]) // حاضرة بحجم صفر (العقد: الجدول يُذكر دائماً)
    }
}
