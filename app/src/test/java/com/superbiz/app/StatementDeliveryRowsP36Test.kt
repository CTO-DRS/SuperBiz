package com.superbiz.app

import com.superbiz.app.data.db.StatementDeliveryEntity
import com.superbiz.app.data.db.StatementEntity
import com.superbiz.app.data.repo.assembleStatementDeliveryRows
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [P36-M4-8] اختبارات النقية المُستخرجة من موجة M4-8 (JUnit4 بلا Android):
 * تجميع صفوف تسليم الكشوف — assembleStatementDeliveryRows — الذي نُقل حرفياً
 * من StatementUiFacade (rowsOf/partyNameOf) إلى طبقة repo:
 * • الترتيب التنازلي بآخر محاولة ثم آخر إرسال (المقارن الأصلي نفسه).
 * • مخزن أسماء الأطراف لكل نداء: الاسم يُقرأ مرة واحدة لكل طرف مهما تعددت الكشوف.
 * • فشل/غياب اسم الطرف ⇒ سلسلة فارغة (لا انفجار) — دلالة runCatching الأصلية.
 * • خرائط الحقول حرفياً (id/statementNumber/partyName/channel/status/…).
 */
class StatementDeliveryRowsP36Test {

    // ─── بذور ثابتة حتمية — على نمط BackupStatementsP34Test ───

    private fun statement(id: Long, partyId: Long, number: String = "STATEMENT-2026-00000$id") =
        StatementEntity(
            id = id, statementNumber = number,
            verificationId = "SB-ST-20260924-00000$id", partyId = partyId,
            fromTs = 1_700_407_200_000L, toTs = 1_702_348_799_000L,
            templateId = "7", currency = "SAR", contentHash = "hash$id",
            filePath = "filesDir/pdfs/$number.pdf", note = null,
            createdAt = 1_702_348_800_000L, lang = "BILINGUAL"
        )

    private fun delivery(
        id: Long, statementId: Long, channel: String = "SMTP",
        sentAt: Long? = null, lastAttemptAt: Long? = null
    ) = StatementDeliveryEntity(
        id = id, statementId = statementId, channel = channel, status = "SENT",
        attempts = 1, lastError = null, sentAt = sentAt,
        scheduledFor = null, dedupKey = "delivery:$statementId:$channel",
        lastAttemptAt = lastAttemptAt
    )

    @Test
    fun rows_sortedBy_lastAttemptAt_descending_fallingBackTo_sentAt() = runBlocking {
        val s = statement(1, partyId = 10)
        // ترتيب الإدخال عشوائي عمداً — المقارن: lastAttemptAt ثم sentAt ثم 0
        val rows = assembleStatementDeliveryRows(
            statements = listOf(s),
            deliveriesOf = {
                listOf(
                    delivery(6, s.id, sentAt = 500L, lastAttemptAt = null),      // 500
                    delivery(7, s.id, sentAt = null, lastAttemptAt = 900L),      // 900
                    delivery(8, s.id, sentAt = 300L, lastAttemptAt = 100L),      // 100
                    delivery(9, s.id, sentAt = null, lastAttemptAt = null)       // 0
                )
            },
            partyNameOf = { "طرف" }
        )
        assertEquals(listOf(7L, 6L, 8L, 9L), rows.map { it.id })
    }

    @Test
    fun party_name_lookedUp_once_per_party_across_statements() = runBlocking {
        val s1 = statement(1, partyId = 10)
        val s2 = statement(2, partyId = 10) // نفس الطرف — المخزن يمنع النداء الثاني
        val s3 = statement(3, partyId = 20)
        var lookups = 0
        val rows = assembleStatementDeliveryRows(
            statements = listOf(s1, s2, s3),
            deliveriesOf = { sid -> listOf(delivery(60 + sid, sid)) },
            partyNameOf = { lookups++; "اسم-$it" }
        )
        assertEquals(2, lookups) // طرف 10 مرة واحدة + طرف 20 مرة واحدة
        assertEquals(listOf("اسم-10", "اسم-10", "اسم-20"), rows.map { it.partyName })
    }

    @Test
    fun party_name_failure_or_null_yields_empty_string_without_throwing() = runBlocking {
        val s1 = statement(1, partyId = 10)
        val s2 = statement(2, partyId = 20)
        val rows = assembleStatementDeliveryRows(
            statements = listOf(s1, s2),
            deliveriesOf = { sid -> listOf(delivery(60 + sid, sid)) },
            partyNameOf = { partyId ->
                if (partyId == 10L) throw IllegalStateException("db glitch") else null
            }
        )
        assertEquals("", rows.first { it.partyId == 10L }.partyName)
        assertEquals("", rows.first { it.partyId == 20L }.partyName)
    }

    @Test
    fun statements_without_deliveries_produce_no_rows_and_fields_map_verbatim() = runBlocking {
        val withDelivery = statement(1, partyId = 10)
        val silent = statement(2, partyId = 20) // كشف بلا تسليمات ⇒ لا صفوف (الصدق الموثق)
        val rows = assembleStatementDeliveryRows(
            statements = listOf(withDelivery, silent),
            deliveriesOf = { sid -> if (sid == 1L) listOf(delivery(6, 1L, channel = "EMAIL")) else emptyList() },
            partyNameOf = { "مؤسسة النور" }
        )
        assertEquals(1, rows.size)
        val r = rows.single()
        assertEquals(6L, r.id)
        assertEquals(1L, r.statementId)
        assertEquals("STATEMENT-2026-000001", r.statementNumber)
        assertEquals(10L, r.partyId)
        assertEquals("مؤسسة النور", r.partyName)
        assertEquals("filesDir/pdfs/STATEMENT-2026-000001.pdf", r.filePath)
        assertEquals("EMAIL", r.channel)
        assertEquals("SENT", r.status)
    }

    @Test
    fun empty_input_yields_empty_rows() = runBlocking {
        val rows = assembleStatementDeliveryRows(
            statements = emptyList(),
            deliveriesOf = { emptyList() },
            partyNameOf = { "لا يُستدعى" }
        )
        assertEquals(emptyList<com.superbiz.app.data.repo.StatementDeliveryRow>(), rows)
    }
}
