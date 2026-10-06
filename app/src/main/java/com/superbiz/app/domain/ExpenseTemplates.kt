package com.superbiz.app.domain

/**
 * : منطق نقي لموجة المصروفات + الأتمتة (R6-P4-4a) —
 * 31) كاشف قوالب المصروفات المتكررة: نفس الوصف المنظَّف ≥2 مرة خلال آخر 60 يوماً
 * → قالب إضافة سريعة (وصف/تصنيف/مبلغ) بترتيب التكرار.
 * 32) حالات إنذار حد المصروف الشهري: <80% عادي، ≥80% برتقالي، ≥100% أحمر،
 * وبلا حد محدد → null (إخفاء صادق بلا أرقام مزيّفة).
 * خالص تماماً من Android — قابل للاختبار وحداتياً.
*/
object ExpenseTemplates {

    /** نافذة التكرار بالأيام (شاملة الحد) */
    const val WINDOW_DAYS = 60

    /** أقل عدد مرات لاعتماد الوصف قالباً */
    const val MIN_REPEATS = 2

    private const val DAY_MS = 86_400_000L

    /** لقطة خفيفة لمصروف — تفصل المنطق النقي عن كيان Room */
    data class ExpenseLike(
        val note: String,
        val category: String,
        val amount: Double,
        val date: Long
    )

    /** قالب مصروف متكرر جاهز للإضافة السريعة */
    data class Template(
        val description: String,   // الوصف المعروض (منظَّف من آخر استخدام)
        val category: String,      // الفئة الأكثر تكراراً (عند التعادل: الأحدث)
        val amount: Double,        // مبلغ آخر استخدام
        val count: Int,            // عدد التكرارات داخل النافذة
        val lastUsed: Long,        // تاريخ آخر استخدام
        val note: String           // ما يُعبَّأ في حقل الملاحظة (فارغ إن كان الوصف من الفئة)
    )

    /** تنظيف الوصف: إزالة الأطراف + تقليص المسافات المتتالية */
    fun cleanDescription(raw: String): String =
        raw.trim().replace(Regex("\\s+"), " ")

    /** مفتاح التجميع: منظَّف + حالة أحرف موحّدة (لاتيني غير حساس للحالة، العربي لا يتأثر) */
    private fun keyOf(cleaned: String): String = cleaned.lowercase()

    /**
     * كشف القوالب داخل نافذة [windowDays] يوماً منتهيةً بـ [now]:
     * - التجميع بالوصف المنظَّف (trim + مسافات + حالة أحرف)؛
     *   والمصروف بلا ملاحظة يُجمَّع على فئته كي لا تضيع المصروفات الدورية بلا وصف.
     * - ما دون [minRepeats] مرات لا يصبح قالباً.
     * - المبلغ والوصف المعروض من آخر استخدام، والفئة الأكثر تكراراً (التعادل → الأحدث).
     * - الترتيب: بالتكرار تنازلياً ثم بالأحدث تنازلياً.
     */
    fun detect(
        expenses: List<ExpenseLike>,
        now: Long,
        windowDays: Int = WINDOW_DAYS,
        minRepeats: Int = MIN_REPEATS
    ): List<Template> {
        val from = now - windowDays.toLong() * DAY_MS
        val groups = LinkedHashMap<String, MutableList<ExpenseLike>>()
        for (e in expenses) {
            if (e.date < from) continue                       // خارج نافذة الـ60 يوماً
            val desc = cleanDescription(if (e.note.isBlank()) e.category else e.note)
            if (desc.isEmpty()) continue                      // لا وصف ولا فئة → لا قالب
            groups.getOrPut(keyOf(desc)) { mutableListOf() }.add(e)
        }
        return groups.values.mapNotNull { items ->
            if (items.size < minRepeats) return@mapNotNull null
            val latest = items.maxByOrNull { it.date } ?: return@mapNotNull null
            val fromNote = latest.note.isNotBlank()
            val description = cleanDescription(if (fromNote) latest.note else latest.category)
            val category = items.groupBy { it.category.trim() }
                .maxWithOrNull(
                    compareBy({ it.value.size }, { it.value.maxOf { x -> x.date } })
                )?.key.orEmpty()
            Template(
                description = description,
                category = category,
                amount = latest.amount,
                count = items.size,
                lastUsed = latest.date,
                note = if (fromNote) description else ""
            )
        }.sortedWith(
            compareByDescending<Template> { it.count }.thenByDescending { it.lastUsed }
        )
    }
}

/**
 * : وظيفة 32 — حساب حالة إنذار حد المصروف الشهري.
 * monthTotal ≥ limit → OVER (أحمر) · pct ≥ 80 → WARN (برتقالي) · ما دون ذلك OK.
 * monthlyLimit ≤ 0 أو غير سالم → null: إخفاء صادق، لا إنذار بلا حد محدد.
*/
object ExpenseAlert {

    enum class Level { OK, WARN, OVER }

    data class State(
        val level: Level,
        val pct: Int          // نسبة الاستهلاك مقيَّدة لأسفل (79.9% → 79)
    )

    fun state(monthTotal: Double, monthlyLimit: Double): State? {
        if (monthlyLimit <= 0.0 || monthlyLimit.isNaN() || monthlyLimit.isInfinite()) return null
        val total = if (monthTotal.isNaN() || monthTotal < 0.0) 0.0 else monthTotal
        val pct = Math.floor(total / monthlyLimit * 100.0).toInt()
        val level = when {
            total >= monthlyLimit -> Level.OVER      // ≥100% أحمر
            pct >= 80 -> Level.WARN                  // ≥80% برتقالي
            else -> Level.OK
        }
        return State(level, pct)
    }
}
