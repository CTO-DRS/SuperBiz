package com.superbiz.app.domain.sync

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [E1.4 V 3.4.0] سياسة التعارض الصريحة لكل كيان — من أعمال داخلية صامتة إلى عقد مسمّى
 * ═══════════════════════════════════════════════════════════════════════════
 * عقد خارطة الطريق E1.4: «سياسة التعارض صريحة لكل كيان (آخر-كاتب-يفوز مقابل
 * دمج) وتُعرض في الواجهة حيث المال أو المخزون على المحك».
 *
 * الواقع الحالي (مقروء من SyncEngine حرفياً — لا سياسة جديدة تُبتدع):
 *  • الأطراف والأصناف والزيارات وقوالب الكشوف والملاحظات والكوبونات:
 *    آخر-كاتب-يفوز بطابع syncUpdatedAt (الأنظف أول يفوز، والمتطابق يترك المحلي)
 *    — نمط `db.x().upsert((local ?: incoming).copy(syncUpdatedAt = at))`.
 *  • العملات: القادم يفوز دوماً (الأسعار المرجعية مصدر بعيد واحد).
 *  • stockQty للأصناف: **ملك الجهاز المحلي** (ADR-002 D3) — مستثنى من الدمج
 *    كلياً، وحركات المخزون هي ناقل التغيير لا الكتابة المباشرة.
 *  • دفتر استهلاك الكوبونات: إلحاقي بشاهد فريد — لا تعارض أصلاً (RVW-17).
 *
 * هذه الوحدة تجعل القرار **مسمّى وقابلاً للاختبار** بدل متناثر في أعمال
 * upsert — محرك صرف صغير يطبق نفس الأحكام حرفياً، وفحص خصائص عشوائي
 * (500 سيناريو مبذر) يقفل ثوابته الأربع: الأولوية الزمنية، ملكية الحقل
 * المحلي، التماثل (الدمج مرتين = مرة)، والفشل المغلق على كيان غريب.
 */
object SyncConflictPolicy {

    /** حسم التعارض المحتمل — قرار واحد صريح لكل خلية */
    enum class Resolution {
        /** آخر-كاتب-يفوز بطابع المزامنة */
        LWW,
        /** القادم من القرين يفوز دوماً (مصدر بعيد واحد للحقيقة) */
        REMOTE_WINS,
        /** الحقل ملك الجهاز المحلي — القادم لا يلمسه أبداً (ADR-002 D3) */
        LOCAL_OWNED,
        /** إلحاقي بشاهد فريد — لا تعارض في أصله (دفتر RVW-17) */
        APPEND_ONLY
    }

    data class Rule(val resolution: Resolution, val rationale: String)

    /** جدول الحسم — كل كيان مُزامَن له صف واحد بالاسم (المصدر: SyncEngine + ADR-002) */
    val RULES: Map<String, Rule> = mapOf(
        "parties" to Rule(Resolution.LWW, "آخر تعديل بطابع المزامنة يفوز — نمط upsert الحالي حرفياً"),
        "products" to Rule(Resolution.LWW, "آخر تعديل يفوز؛ stockQty حقل ملك محلي بالجدول أدناه"),
        "currencies" to Rule(Resolution.REMOTE_WINS, "الأسعار المرجعية مصدر بعيد واحد (ADR-002 D4)"),
        "visits" to Rule(Resolution.LWW, "زيارات الحقل آخر-كاتب-يفوز"),
        "statementTemplates" to Rule(Resolution.LWW, "قوالب الكشوف آخر-كاتب-يفوز"),
        "noteTemplates" to Rule(Resolution.LWW, "قوالب الملاحظات آخر-كاتب-يفوز"),
        "coupons" to Rule(Resolution.LWW, "تعريف الكوبون آخر-كاتب-يفوز (عدّاد الاستهلاك بلا دمج)"),
        "couponRedemptions" to Rule(Resolution.APPEND_ONLY, "دفتر إلحاقي بشاهد فريد — لا تعارض (RVW-17)"),
        "expenses" to Rule(Resolution.LWW, "المصروفات المُزامنة آخر-كاتب-يفوز")
    )

    /** حقول ملك الجهاز المحلي — القادم لا يكتبها أبداً (ADR-002 D3 + ملكية المخزون) */
    private val LOCAL_OWNED_FIELDS: Map<String, Set<String>> = mapOf(
        "products" to setOf("stockQty"),
        "parties" to setOf("favorite", "lat", "lng")
    )

    /** قاعدة كيان بالاسم — كيان غريب يرمي (فشل مغلق: مزامنة كيان غير موثق بولي بلا قرار) */
    fun ruleFor(entity: String): Rule =
        RULES[entity] ?: throw IllegalArgumentException("no conflict policy for entity: $entity")

    /** هل هذا الحقل ملك محلي لهذا الكيان؟ */
    fun isLocalOwned(entity: String, field: String): Boolean =
        LOCAL_OWNED_FIELDS[entity]?.contains(field) == true

    /** الحقول المحلية المملوكة لكيان (فارغ إن لا شيء) */
    fun localOwnedFields(entity: String): Set<String> =
        LOCAL_OWNED_FIELDS[entity] ?: emptySet()

    /** أي جهة تفوز في خلية تعارض دمج — قرار واحد صريح */
    enum class Side { LOCAL, REMOTE }

    /** القرار بين نسختين بتوقيتيهما — التعادل يرجّح المحلي (افتراض محافظ موثق) */
    fun resolveSide(entity: String, localUpdatedAt: Long, remoteUpdatedAt: Long): Side =
        when (ruleFor(entity).resolution) {
            Resolution.LWW ->
                if (remoteUpdatedAt > localUpdatedAt) Side.REMOTE else Side.LOCAL
            Resolution.REMOTE_WINS -> Side.REMOTE
            // LOCAL_OWNED/APPEND_ONLY لا يدخلان دمج حقلٍ إلى حقل أصلاً —
            // الأول ملك محلي كلي والثاني إلحاقي بشاهد — الطلب عليهما خطأ برمجي
            else -> throw IllegalStateException(
                "field merge not applicable to ${ruleFor(entity).resolution} entity $entity"
            )
        }

    /**
     * تنفيذ الدمج الصرف — حقول القادم تُطبق وفق القاعدة، وحقول الملك المحلي
     * تنجو من الكتابة دائماً، وطابع المزامنة يأخذ وقت الفائز.
     * الربط: local/incoming خرائط حقول صرفة (لا كيانات Room) كي تبقى الوحدة
     * JVM خالصة قابلة للفحص العشوائي بلا قاعدة.
     */
    fun merge(
        entity: String,
        local: Map<String, Any?>,
        incoming: Map<String, Any?>,
        remoteUpdatedAt: Long,
        localUpdatedAt: Long
    ): Map<String, Any?> {
        val protected = localOwnedFields(entity)
        val winRemote = resolveSide(entity, localUpdatedAt, remoteUpdatedAt) == Side.REMOTE
        val base = if (winRemote) incoming else local
        val out = base.toMutableMap()
        // حقول الملك المحلي تنجو بقيمتها المحلية حصراً — مهما فاز ومنهو خسر
        // (المخزون والتفضيلات ملك الجهاز؛ القادم لا يلمسهما حتى وهو الفائز)
        for (f in protected) {
            if (local.containsKey(f)) out[f] = local[f]
        }
        out["syncUpdatedAt"] = if (winRemote) remoteUpdatedAt else localUpdatedAt
        return out
    }
}
