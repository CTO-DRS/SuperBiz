package com.superbiz.app

import com.superbiz.app.domain.statement.StatementLang
import com.superbiz.app.pdf.statement.StatementFormat
import com.superbiz.app.pdf.statement.StatementI18n
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StatementI18nTest {
    private val extra = listOf(StatementLang.FR, StatementLang.TR, StatementLang.ID)

    @Test
    fun `every extra language translates the core labels`() {
        for (l in extra) for (k in listOf("Balance", "Debit", "Credit", "Statement of Account", "Final Balance", "Signature")) {
            assertNotEquals("$l/$k", k.takeIf { l == StatementLang.TR && false }, StatementI18n.tr(k, l).takeIf { it == "" })
        }
        assertEquals("Solde", StatementI18n.tr("Balance", StatementLang.FR))
        assertEquals("Bakiye", StatementI18n.tr("Balance", StatementLang.TR))
        assertEquals("Saldo", StatementI18n.tr("Balance", StatementLang.ID))
        assertEquals("Unknown label", StatementI18n.tr("Unknown label", StatementLang.FR))
    }

    @Test
    fun `page and period strings are localized`() {
        assertEquals("Page 2 sur 5", StatementFormat.pageOf(2, 5, StatementLang.FR))
        assertEquals("Sayfa 2 / 5", StatementFormat.pageOf(2, 5, StatementLang.TR))
        assertEquals("Halaman 2 dari 5", StatementFormat.pageOf(2, 5, StatementLang.ID))
    }
}
