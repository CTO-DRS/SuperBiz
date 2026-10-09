package com.superbiz.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [H5-5 V 3.0.0] «الأفق الخامس — العالمية»: عقد التصدير العام الموثق
 * ═══════════════════════════════════════════════════════════════════════════
 * عقد القبول (docs/EXPORT_API.md §Schemas):
 * - كل مخطط قائمة أعمدة مرتبة غير فارغة بمفاتيح آلة فريدة وأنواع من TYPES فقط.
 * - الملصقات كلها قابلة للحل من الموارد في اللغة الحالية (لا res ميت).
 * - عقد [P53-2]: أعمدة MONEY_RIYAL هي خلايا رقمية بالأساس — يُوثَّق اسمها هنا
 *   كي لا يُصنَّف عمود قروش نصياً في أي نسخة قادمة (حارس انحدار H-5).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class ExportSchemasTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `schemas have unique keys and valid types`() {
        val schemas = mapOf(
            "INVOICES" to com.superbiz.app.export.ExportSchemas.INVOICES,
            "INVENTORY" to com.superbiz.app.export.ExportSchemas.INVENTORY,
            "CLAIMS" to com.superbiz.app.export.ExportSchemas.CLAIMS
        )
        schemas.forEach { (name, schema) ->
            assertTrue("$name فارغ", schema.isNotEmpty())
            assertEquals(
                "$name مفاتيح مكررة", schema.size, schema.map { it.key }.distinct().size
            )
            schema.forEach { col ->
                assertTrue(
                    "$name.${col.key} نوع غير معروف: ${col.type}",
                    col.type in com.superbiz.app.export.ExportSchemas.TYPES
                )
            }
        }
    }

    @Test
    fun `column counts match shipped files`() {
        // [P53-1] أعداد الأعمدة هي العقد — انحرافها يكسر المستوردين الآليين
        assertEquals(13, com.superbiz.app.export.ExportSchemas.INVOICES.size)
        assertEquals(9, com.superbiz.app.export.ExportSchemas.INVENTORY.size)
        assertEquals(6, com.superbiz.app.export.ExportSchemas.CLAIMS.size)
    }

    @Test
    fun `labels resolve from resources in current locale`() {
        // [P53-5] الرؤوس تُحل من الموارد لحظة التصدير — المستورد الآلي يربط بالمفاتيح
        val invoices = com.superbiz.app.export.ExportSchemas.invoicesHeaders(ctx)
        val inventory = com.superbiz.app.export.ExportSchemas.inventoryHeaders(ctx)
        val claims = com.superbiz.app.export.ExportSchemas.claimsHeaders(ctx)
        assertEquals(com.superbiz.app.export.ExportSchemas.INVOICES.size, invoices.size)
        assertEquals(com.superbiz.app.export.ExportSchemas.INVENTORY.size, inventory.size)
        assertEquals(com.superbiz.app.export.ExportSchemas.CLAIMS.size, claims.size)
        assertTrue(invoices.all { it.isNotBlank() })
        assertTrue(inventory.all { it.isNotBlank() })
        assertTrue(claims.all { it.isNotBlank() })
    }

    @Test
    fun `money columns are typed as base-unit numerics`() {
        // [P53-2] حارس H-5: لا عمود مالي يُصدَّر نصاً — النوع MONEY_RIYAL موثق دائماً
        val moneyKeys = com.superbiz.app.export.ExportSchemas.INVOICES
            .filter { it.type == "MONEY_RIYAL" }.map { it.key }
        assertTrue(
            "الفواتير يجب أن تحمل أعمدة money", moneyKeys.containsAll(
                listOf("subtotal", "discount", "tax", "total", "paid", "remaining")
            )
        )
        assertEquals(
            listOf("cost", "sale", "stock_value"),
            com.superbiz.app.export.ExportSchemas.INVENTORY
                .filter { it.type == "MONEY_RIYAL" }.map { it.key }
        )
    }

    @Test
    fun `schema version is positive`() {
        assertTrue(com.superbiz.app.export.ExportSchemas.VERSION >= 1)
    }
}
