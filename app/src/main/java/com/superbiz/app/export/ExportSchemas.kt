package com.superbiz.app.export

import android.content.Context
import androidx.annotation.StringRes
import com.superbiz.app.R

/**
 * [H5-4 V 3.2.0] «الأفق الخامس — العالمية»: عقد التصدير العام الموثق — مصدر الحقيقة
 * الوحيد لأعمدة ملفات التصدير القياسية (docs/EXPORT_API.md §Schemas).
 *
 * العقد:
 * - [P53-1] كل مخطط قائمة أعمدة مرتبة (الترتيب هو العقد — لا إعادة ترتيب دون
 *   رفع [VERSION])، وكل عمود: مفتاح آلة ثابت `key` + ملصق موطّن `labelRes`
 *   + نوع `type` من {TEXT, NUMBER, MONEY_RIYAL, QTY, CODE}.
 * - [P53-2] MONEY_RIYAL = خلية رقمية بالريال/الوحدة الأساسية عبر Money.fromPiasters
 *   (عقد H-5 الموروث) — لا قروش نصية في ملفات التصدير القياسية أبداً.
 * - [P53-3] رؤوس CSV تمر بحرس حقن الصيغ CsvKit.escape (H-21)؛ خلايا XLSX inlineStr/
 *   رقمية فقط فلا متجه حقن فيها (موثق في EXPORT_API.md §Safety).
 * - [P53-4] الملفات UTF-8 مع BOM، أسماؤها superbiz-<entity>-<yyyyMMdd-HHmmss>.
 * - [P53-5] الترجمات تُحل من الموارد لحظة التصدير — المستورد الآلي يربط بالمفاتيح
 *   `key` (صف توثيق ثابت) لا بالملصقات المتغيرة بين اللغات.
 */
object ExportSchemas {

    /** إصدار عقد الأعمدة — أي تغيير ترتيب/حذف عمود يرفعه ويتحدث به EXPORT_API.md */
    const val VERSION = 1

    /** أنواع الأعمدة المسموحة — مفهرسة للاختبار */
    val TYPES = setOf("TEXT", "NUMBER", "MONEY_RIYAL", "QTY", "CODE")

    data class Col(val key: String, @StringRes val labelRes: Int, val type: String)

    private fun col(key: String, @StringRes labelRes: Int, type: String) = Col(key, labelRes, type)

    /** superbiz-invoices-*.xlsx — 13 عموداً (InvoicesScreen) */
    val INVOICES: List<Col> = listOf(
        col("invoice_number", R.string.invoice_number, "TEXT"),
        col("type", R.string.transaction_type, "TEXT"),
        col("party", R.string.party, "TEXT"),
        col("date", R.string.date, "TEXT"),
        col("due_date", R.string.due_date, "TEXT"),
        col("subtotal", R.string.subtotal, "MONEY_RIYAL"),
        col("discount", R.string.discount, "MONEY_RIYAL"),
        col("tax", R.string.tax, "MONEY_RIYAL"),
        col("total", R.string.total, "MONEY_RIYAL"),
        col("paid", R.string.paid_amount, "MONEY_RIYAL"),
        col("remaining", R.string.inst_remaining, "MONEY_RIYAL"),
        col("status", R.string.status, "TEXT"),
        col("currency", R.string.base_currency, "CODE")
    )

    /** superbiz-inventory-*.xlsx — 9 أعمدة (InventoryScreen) */
    val INVENTORY: List<Col> = listOf(
        col("name", R.string.name, "TEXT"),
        col("sku", R.string.sku, "TEXT"),
        col("barcode", R.string.barcode, "TEXT"),
        col("unit", R.string.unit, "TEXT"),
        col("cost", R.string.cost_price, "MONEY_RIYAL"),
        col("sale", R.string.sale_price, "MONEY_RIYAL"),
        col("qty", R.string.stock_qty, "QTY"),
        col("reorder_level", R.string.reorder_level, "QTY"),
        col("stock_value", R.string.inv_stock_value, "MONEY_RIYAL")
    )

    /** superbiz-claims-*.xlsx — 6 أعمدة (DebtsScreen) */
    val CLAIMS: List<Col> = listOf(
        col("name", R.string.name, "TEXT"),
        col("phone", R.string.phone, "TEXT"),
        col("type", R.string.transaction_type, "TEXT"),
        col("balance", R.string.balance, "MONEY_RIYAL"),
        col("risk_score", R.string.risk_score, "NUMBER"),
        col("status", R.string.status, "TEXT")
    )

    /** الرؤوس الموطّنة لمخطط — المستدعى الوحيد للشاشات (مصدر واحد لا نسخ) */
    fun headers(ctx: Context, schema: List<Col>): List<String> =
        schema.map { ctx.getString(it.labelRes) }

    fun invoicesHeaders(ctx: Context) = headers(ctx, INVOICES)
    fun inventoryHeaders(ctx: Context) = headers(ctx, INVENTORY)
    fun claimsHeaders(ctx: Context) = headers(ctx, CLAIMS)

    /** مفاتيح الآلة لمخطط — للتوثيق والمستوردين الآليين */
    fun keys(schema: List<Col>): List<String> = schema.map { it.key }
}
