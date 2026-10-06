package com.superbiz.app.domain

import com.superbiz.app.data.db.Product
import com.superbiz.app.print.EscPos
import java.io.ByteArrayOutputStream

// ═══════════════════════════════════════════════════════════════
// منطق نقي لموجة المخزون (وظائف 12، 14، 15، 20) —
// بلا أندرويد وقابل للاختبار وحداتياً في InventoryReportsP4Test.
// ═══════════════════════════════════════════════════════════════

/**
 * وظيفة 12 — بنّاء ملصق سعر حراري 58مم (اسم + سعر + باركود CODE128).
 * دالة نقية تُنتج بايتات ESC/POS جاهزة للإرسال عبر BluetoothPrinter،
 * ومعاينة نصية للحوار. عرض الورق 32 حرفاً لورق 58مم.
 */
object PriceLabel {

    /**
     * معاينة نصية كما ستُطبع — كل سطر مقيّد بعرض الورق.
     * اسم المنتج أولاً ثم السعر ثم الباركود (إن وُجد) بين خطين فاصلين.
     */
    fun textPreview(name: String, priceText: String, barcode: String, width: Int = 32): String {
        val sep = "-".repeat(width.coerceIn(20, 64))
        val sb = StringBuilder()
        sb.appendLine(sep)
        sb.appendLine(fit(name, width))
        sb.appendLine(fit(priceText, width))
        if (barcode.isNotBlank()) sb.appendLine(fit(barcode, width))
        sb.append(sep)
        return sb.toString()
    }

    /**
     * بايتات الملصق: تهيئة، جدولة CP1256، توسيط، الاسم عريضاً، السعر بحجم مضاعف،
     * باركود CODE128 عبر الأمر القياسي GS k 73 (بيانات {B + القيمة)، ثم قص جزئي.
     * لا باركود → يُبنى الملصق بName والسعر فقط (سلوك موثق بالاختبار).
     */
    fun buildBytes(name: String, priceText: String, barcode: String, width: Int = 32): ByteArray {
        val out = ByteArrayOutputStream(512)
        fun cmd(vararg b: Int) = b.forEach { out.write(it) }
        fun text(s: String) = out.write(EscPos.arabicText(s))
        fun nl() = out.write(0x0A)

        cmd(0x1B, 0x40)                 // ESC @ تهيئة
        cmd(0x1B, 0x74, 0x20)           // ESC t 32 — جدولة CP1256 للعربية
        cmd(0x1B, 0x61, 0x01)           // ESC a 1 توسيط
        cmd(0x1B, 0x45, 0x01)           // عريض
        text(fit(name, width)); nl()
        cmd(0x1B, 0x45, 0x00)
        cmd(0x1D, 0x21, 0x11)           // GS ! 17 — حجم مضاعف للسعر
        text(fit(priceText, width)); nl()
        cmd(0x1D, 0x21, 0x00)
        if (barcode.isNotBlank()) {
            cmd(0x1D, 0x68, 0x50)       // GS h 80 — ارتفاع الباركود
            cmd(0x1D, 0x77, 0x02)       // GS w 2 — عرض الوحدة
            val data = "{B".toByteArray(Charsets.US_ASCII) +
                barcode.toByteArray(Charsets.US_ASCII)
            cmd(0x1D, 0x6B, 0x49, data.size) // GS k 73 — CODE128 بعدد بايتات
            out.write(data)
            nl(); nl()
        }
        out.write(byteArrayOf(0x1D, 0x56, 0x42, 0x00)) // GS V B 0 قص جزئي
        return out.toByteArray()
    }

    /** قيد السطر بعرض الورق — اقتطاع صادق بدل التفاف الطابعة غير المتوقع */
    private fun fit(s: String, width: Int): String =
        if (s.length <= width) s else s.take(width)
}

/**
 * وظيفة 15 — بنّاء نص قائمة التسوّج الشرائية للمشاركة:
 * كل منتج تحت نقطة إعادة الطلب مع الكمية المقترحة.
 */
object ShoppingList {

    data class Row(
        val name: String,
        val unit: String,
        val stockQty: Double,
        val suggestedQty: Double
    )

    /**
 * : عناوين قابلة للتوطين — كانت نصوص المشاركة عربية
 * مضمّة في الكود لكل اللغات. الافتراضي يبقى العربية لتوافق الاستدعاءات
 * والاختبارات القائمة، والواجهة تمرر النصوص من الموارد.
*/
    data class Labels(
        val title: String = "🛒 قائمة التسوّج الشرائية",
        val available: String = "المتوفر",
        val suggested: String = "المقترح شراء",
        val count: String = "عدد الأصناف: %1\$d",
        val note: String = "الكميات المقترحة من خوارزمية إعادة الطلب (مبيعات 30 يوماً)"
    )

    /** نص منسق؛ قائمة فارغة → نص فارغ (الزر يُخفى أصلاً في الواجهة) */
    fun build(rows: List<Row>, labels: Labels = Labels()): String {
        if (rows.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine(labels.title)
        sb.appendLine("——————————")
        for (r in rows) {
            sb.appendLine(
                "• ${r.name} — ${labels.available}: ${com.superbiz.app.util.Money.num(r.stockQty)} ${r.unit}" +
                    " | ${labels.suggested}: ${com.superbiz.app.util.Money.num(r.suggestedQty)} ${r.unit}"
            )
        }
        sb.appendLine("——————————")
        sb.appendLine(labels.count.replace("%1\$d", rows.size.toString()))
        sb.append(labels.note)
        return sb.toString()
    }
}

/**
 * وظيفة 14 — كمية الشراء المقترحة بضغطة واحدة:
 * الاقتراح المحسوب (نقطة إعادة الطلب + تغطية أسبوعين − المخزون) بحد أدنى 1.
 * اقتراح ≤ 0 أو غير منطقي (NaN) → null (لا حركة).
 */
object ReorderApply {

    fun purchaseQty(suggested: Double, minimum: Double = 1.0): Double? {
        if (suggested.isNaN() || suggested.isInfinite()) return null
        if (suggested <= 0.0) return null
        return maxOf(minimum, com.superbiz.app.util.Money.round2(suggested))
    }
}

/**
 * وظيفة 20 — منتجات بلا بيع إطلاقاً (مختلفة عن «الراكد» 30 يوماً):
 * منتجات بلا أي سطر فاتورة في التاريخ كله.
 */
object NeverSold {

    /** يبقي المنتجات التي معرّفها ليس ضمن معرّفات مبيعة قط (null في البند = بيع حر → يُتجاهل) */
    fun filter(products: List<Product>, soldProductIds: Set<Long>): List<Product> =
        products.filter { it.id !in soldProductIds }
}
