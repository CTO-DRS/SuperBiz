package com.superbiz.app.data.repo

import android.content.Context
import androidx.room.withTransaction
import com.superbiz.app.data.db.AppDatabase
import com.superbiz.app.data.db.Currency
import com.superbiz.app.data.db.CouponEntity
import com.superbiz.app.data.db.NoteTemplateEntity
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.db.Product
import com.superbiz.app.data.db.StatementTemplateEntity
import com.superbiz.app.data.db.SyncLogEntity
import com.superbiz.app.data.db.Visit
import com.superbiz.app.domain.backup.MiniJsonException
import com.superbiz.app.domain.backup.escapeJson
import com.superbiz.app.domain.backup.parseJson
import com.superbiz.app.domain.sync.SyncMerge

/**
 * [H4-3][ADR-002 D3/D4] — محرك المزامنة على قاعدة Room: بناء دفعات التغييرات
 * من الدفتر (sync_log) وتطبيق الدفعات الواردة بقرار LWW الحتمي ([SyncMerge.remoteWins]).
 *
 * عقود محفوظة:
 * - الجداول القابلة للمزامنة تطبيقياً 7 (من 9 مهيأة): signatures/stamps مستثناة —
 *   صفوفها تحمل مسارات صور محلية الجهاز، ودمجها يحتاج نقل محتوى (مؤجل موثق).
 * - products.stockQty ملك الجهاز (حركة المخزون محلية) — لا يُصدَّر ولا يُدمج؛
 *   الصف الجديد يبدأ مخزوناً صفراً في الجهاز المستقبل.
 * - visits: partyRef هوية الطرف الأصلية "od|oid" — إعادة الربط عند التطبيق،
 *   وزيارة بلا طرف موجودة تُؤجَّل (تُتخطى حتمياً وتُحصى) لا تُخمَّن.
 * - تطبيق الوارد داخل معاملة واحدة والعلم _sync_applying مرفوع — المشغّلات مكبوتة
 *   فلا تتضخم ساعة LWW ولا يعود الصف المستورد دفتراً (عقد إنهاء الترنّح — D3).
 * - الشواهد المستوردة تُثبَّت في الدفتر (imported=1) — دفعات قديمة متأخرة تُردّ.
 */
/**
 * ساعة المزامنة — هوية الجهاز وحالة الدفعات. SyncEnableStore يحققها بـDataStore،
 * والاختبارات تحققها في الذاكرة (بوابتان مستقلتان لجهازين في اختبار التقارب).
 */
interface SyncClock {
    suspend fun deviceId(): String
    suspend fun enabled(): Boolean
    suspend fun fullPushed(): Boolean
    suspend fun markFullPushed()
    suspend fun nextSeq(): Long
    /** بادئة nonce لهذا الجهاز (4 بايت — Base64) — ثابتة بعد أول توليد. */
    suspend fun noncePrefixB64(): String
    /** مؤشرات السحب deviceId→seq — تديرها دورة المزامنة. */
    suspend fun pullCursors(): Map<String, Long>
    suspend fun setPullCursors(cursors: Map<String, Long>)
}

class SyncEngine(
    private val db: AppDatabase,
    private val clock: SyncClock
) {

    data class ApplyStats(val applied: Int, val skipped: Int, val tombstones: Int, val deferred: Int)

    // ───────────────────────── أدوات JSON ─────────────────────────

    private fun w(s: String?): String = if (s == null) "null" else "\"" + escapeJson(s) + "\""
    private fun w(v: Long): String = v.toString()
    private fun w(v: Int): String = v.toString()
    private fun w(v: Boolean): String = if (v) "1" else "0"
    private fun w(v: Double): String = v.toString()
    private fun w(v: Long?): String = if (v == null) "null" else v.toString()
    private fun w(v: Double?): String = if (v == null) "null" else v.toString()

    private fun Map<*, *>.s(k: String, d: String = ""): String = (this[k] as? String) ?: d
    private fun Map<*, *>.lg(k: String, d: Long = 0L): Long = (this[k] as? Long) ?: ((this[k] as? Double)?.toLong() ?: d)
    private fun Map<*, *>.d(k: String, d: Double = 0.0): Double = (this[k] as? Double) ?: ((this[k] as? Long)?.toDouble() ?: d)
    private fun Map<*, *>.b(k: String, d: Boolean = false): Boolean = when (val v = this[k]) {
        is Boolean -> v; is Long -> v != 0L; is Double -> v != 0.0; else -> d
    }
    private fun Map<*, *>.dd(k: String): Double? = when (val v = this[k]) { is Double -> v; is Long -> v.toDouble(); else -> null }

    /** هوية "od|oid" أو "" للصف الأصلي — التحليل يفصل الجهاز عن الرقم. */
    private fun splitKey(key: String): Pair<String, Long> {
        val sep = key.indexOf('|')
        if (sep < 0) return key to 0L
        return key.substring(0, sep) to (key.substring(sep + 1).toLongOrNull() ?: 0L)
    }

    private fun keyOf(od: String, oid: Long, my: String, localId: Long): String =
        if (od.isEmpty()) "$my|$localId" else "$od|$oid"

    // ───────────────────────── محولات الجداول ─────────────────────────

    private abstract class Adapter<T>(val table: String) {
        abstract suspend fun allOnce(): List<T>
        abstract fun clockOf(row: T): Long
        /** (originDeviceId للدفتر، originId للدفتر، مفتاح المظروف) */
        abstract fun identityOf(row: T, my: String): Triple<String, Long, String>
        abstract suspend fun findByKey(key: String, my: String): T?
        abstract suspend fun applyIncoming(incoming: T, key: String, at: Long, my: String): Boolean
        abstract suspend fun deleteByKey(key: String, my: String): Boolean
        abstract fun bodyOf(row: T, my: String): String
        abstract fun fromBody(m: Map<*, *>, key: String): T
    }

    private fun adapterFor(table: String): Adapter<*>? = when (table) {
        "parties" -> PartyAdapter()
        "products" -> ProductAdapter()
        "currencies" -> CurrencyAdapter()
        "visits" -> VisitAdapter()
        "coupons" -> CouponAdapter()
        "statement_templates" -> StatementTemplateAdapter()
        "note_templates" -> NoteTemplateAdapter()
        else -> null
    }

    private fun allAdapters(): List<Adapter<*>> = listOf(
        PartyAdapter(), ProductAdapter(), CurrencyAdapter(), VisitAdapter(),
        CouponAdapter(), StatementTemplateAdapter(), NoteTemplateAdapter()
    )

    private fun originPair(key: String, my: String, localId: Long): Pair<String, Long> {
        val (od, oid) = splitKey(key)
        return if (od == my) "" to localId else od to oid
    }

    private inner class PartyAdapter : Adapter<Party>("parties") {
        override suspend fun allOnce() = db.parties().allOnce()
        override fun clockOf(row: Party) = row.syncUpdatedAt
        override fun identityOf(row: Party, my: String) =
            if (row.originDeviceId.isEmpty()) Triple("", row.id, "$my|${row.id}")
            else Triple(row.originDeviceId, row.originId, "${row.originDeviceId}|${row.originId}")
        override suspend fun findByKey(key: String, my: String): Party? {
            val (od, oid) = splitKey(key)
            return if (od == my) db.parties().byId(oid) else db.parties().byOrigin(od, oid)
        }
        override suspend fun applyIncoming(incoming: Party, key: String, at: Long, my: String): Boolean {
            val local = findByKey(key, my)
            val (pod, poid) = originPair(key, my, local?.id ?: 0L)
            db.parties().upsert((local ?: incoming).copy(
                id = local?.id ?: 0,
                name = incoming.name, phone = incoming.phone, type = incoming.type,
                note = incoming.note, createdAt = incoming.createdAt, archived = incoming.archived,
                favorite = incoming.favorite, lat = incoming.lat, lng = incoming.lng,
                email = incoming.email, address = incoming.address, taxNumber = incoming.taxNumber,
                crNumber = incoming.crNumber, city = incoming.city, country = incoming.country,
                website = incoming.website, accountNumber = incoming.accountNumber,
                syncUpdatedAt = at, originDeviceId = pod, originId = poid
            )); return true
        }
        override suspend fun deleteByKey(key: String, my: String): Boolean {
            val local = findByKey(key, my) ?: return false
            db.parties().deleteRow(local.id); return true
        }
        override fun bodyOf(row: Party, my: String) = "{\"name\":" + w(row.name) + ",\"phone\":" + w(row.phone) +
            ",\"type\":" + w(row.type) + ",\"note\":" + w(row.note) + ",\"createdAt\":" + w(row.createdAt) +
            ",\"archived\":" + w(row.archived) + ",\"favorite\":" + w(row.favorite) +
            ",\"lat\":" + w(row.lat) + ",\"lng\":" + w(row.lng) +
            ",\"email\":" + w(row.email) + ",\"address\":" + w(row.address) +
            ",\"taxNumber\":" + w(row.taxNumber) + ",\"crNumber\":" + w(row.crNumber) +
            ",\"city\":" + w(row.city) + ",\"country\":" + w(row.country) +
            ",\"website\":" + w(row.website) + ",\"accountNumber\":" + w(row.accountNumber) + "}"
        override fun fromBody(m: Map<*, *>, key: String) = Party(
            name = m.s("name", " "), phone = m.s("phone"), type = m.lg("type").toInt(),
            note = m.s("note"), createdAt = m.lg("createdAt"), archived = m.b("archived"),
            favorite = m.b("favorite"), lat = m.dd("lat"), lng = m.dd("lng"),
            email = m.s("email").ifEmpty { null }, address = m.s("address").ifEmpty { null },
            taxNumber = m.s("taxNumber").ifEmpty { null }, crNumber = m.s("crNumber").ifEmpty { null },
            city = m.s("city").ifEmpty { null }, country = m.s("country").ifEmpty { null },
            website = m.s("website").ifEmpty { null }, accountNumber = m.s("accountNumber").ifEmpty { null }
        )
    }

    private inner class ProductAdapter : Adapter<Product>("products") {
        override suspend fun allOnce() = db.products().allOnce()
        override fun clockOf(row: Product) = row.syncUpdatedAt
        override fun identityOf(row: Product, my: String) =
            if (row.originDeviceId.isEmpty()) Triple("", row.id, "$my|${row.id}")
            else Triple(row.originDeviceId, row.originId, "${row.originDeviceId}|${row.originId}")
        override suspend fun findByKey(key: String, my: String): Product? {
            val (od, oid) = splitKey(key)
            return if (od == my) db.products().byId(oid) else db.products().byOrigin(od, oid)
        }
        override suspend fun applyIncoming(incoming: Product, key: String, at: Long, my: String): Boolean {
            val local = findByKey(key, my)
            val (pod, poid) = originPair(key, my, local?.id ?: 0L)
            db.products().upsert((local ?: incoming).copy(
                id = local?.id ?: 0,
                name = incoming.name, sku = incoming.sku, barcode = incoming.barcode,
                unit = incoming.unit, costPrice = incoming.costPrice, salePrice = incoming.salePrice,
                reorderLevel = incoming.reorderLevel, category = incoming.category,
                createdAt = incoming.createdAt, archived = incoming.archived,
                // stockQty ملك الجهاز — يُحفظ المحلي ولا يُستورد (D3)
                stockQty = local?.stockQty ?: 0.0,
                syncUpdatedAt = at, originDeviceId = pod, originId = poid
            )); return true
        }
        override suspend fun deleteByKey(key: String, my: String): Boolean {
            val local = findByKey(key, my) ?: return false
            db.products().delete(local.id); return true
        }
        override fun bodyOf(row: Product, my: String) = "{\"name\":" + w(row.name) + ",\"sku\":" + w(row.sku) +
            ",\"barcode\":" + w(row.barcode) + ",\"unit\":" + w(row.unit) +
            ",\"costPrice\":" + w(row.costPrice) + ",\"salePrice\":" + w(row.salePrice) +
            ",\"reorderLevel\":" + w(row.reorderLevel) + ",\"category\":" + w(row.category) +
            ",\"createdAt\":" + w(row.createdAt) + ",\"archived\":" + w(row.archived) + "}"
        override fun fromBody(m: Map<*, *>, key: String) = Product(
            name = m.s("name", " "), sku = m.s("sku"), barcode = m.s("barcode"),
            unit = m.s("unit", "قطعة"), costPrice = m.lg("costPrice"), salePrice = m.lg("salePrice"),
            reorderLevel = m.d("reorderLevel"), category = m.s("category"),
            createdAt = m.lg("createdAt"), archived = m.b("archived")
        )
    }

    private inner class CurrencyAdapter : Adapter<Currency>("currencies") {
        override suspend fun allOnce() = db.currencies().allOnce()
        override fun clockOf(row: Currency) = row.syncUpdatedAt
        override fun identityOf(row: Currency, my: String) = Triple(row.code, 0L, row.code)
        override suspend fun findByKey(key: String, my: String): Currency? = db.currencies().byCodeOnce(key)
        override suspend fun applyIncoming(incoming: Currency, key: String, at: Long, my: String): Boolean {
            db.currencies().upsert(incoming.copy(code = key, syncUpdatedAt = at)); return true
        }
        override suspend fun deleteByKey(key: String, my: String): Boolean {
            val local = findByKey(key, my) ?: return false
            db.currencies().delete(local.code); return true
        }
        override fun bodyOf(row: Currency, my: String) = "{\"nameAr\":" + w(row.nameAr) + ",\"nameEn\":" + w(row.nameEn) +
            ",\"symbol\":" + w(row.symbol) + ",\"rateToBase\":" + w(row.rateToBase) +
            ",\"isBase\":" + w(row.isBase) + "}"
        override fun fromBody(m: Map<*, *>, key: String) = Currency(
            code = key, nameAr = m.s("nameAr", " "), nameEn = m.s("nameEn", " "),
            symbol = m.s("symbol", " "), rateToBase = m.d("rateToBase", 1.0), isBase = m.b("isBase")
        )
    }

    private inner class VisitAdapter : Adapter<Visit>("visits") {
        override suspend fun allOnce() = db.visits().allOnce()
        override fun clockOf(row: Visit) = row.syncUpdatedAt
        override fun identityOf(row: Visit, my: String) =
            if (row.originDeviceId.isEmpty()) Triple("", row.id, "$my|${row.id}")
            else Triple(row.originDeviceId, row.originId, "${row.originDeviceId}|${row.originId}")
        override suspend fun findByKey(key: String, my: String): Visit? {
            val (od, oid) = splitKey(key)
            return if (od == my) db.visits().byIdOnce(oid) else db.visits().byOrigin(od, oid)
        }
        override suspend fun applyIncoming(incoming: Visit, key: String, at: Long, my: String): Boolean {
            val local = findByKey(key, my)
            // إعادة ربط الطرف عبر هويتها الأصلية — زيارة بلا طرف موجودة تُؤجَّل
            val (pod, poid) = splitKey(incoming.partyRef)
            val party = if (pod == my) db.parties().byId(poid) else db.parties().byOrigin(pod, poid)
            val localPartyId = party?.id ?: local?.partyId
            if (localPartyId == null) return false
            val (sod, soid) = originPair(key, my, local?.id ?: 0L)
            db.visits().upsert((local ?: incoming).copy(
                id = local?.id ?: 0,
                partyId = localPartyId,
                visitedAt = incoming.visitedAt, lat = incoming.lat, lng = incoming.lng,
                note = incoming.note, partyRef = incoming.partyRef,
                syncUpdatedAt = at, originDeviceId = sod, originId = soid
            )); return true
        }
        override suspend fun deleteByKey(key: String, my: String): Boolean {
            val local = findByKey(key, my) ?: return false
            db.visits().deleteVisit(local.id); return true
        }
        override fun bodyOf(row: Visit, my: String) = "{\"partyRef\":" +
            w(row.partyRef.ifEmpty { "$my|${row.partyId}" }) +
            ",\"visitedAt\":" + w(row.visitedAt) + ",\"lat\":" + w(row.lat) +
            ",\"lng\":" + w(row.lng) + ",\"note\":" + w(row.note) + "}"
        override fun fromBody(m: Map<*, *>, key: String) = Visit(
            partyId = 0, partyRef = m.s("partyRef"), visitedAt = m.lg("visitedAt"),
            lat = m.dd("lat"), lng = m.dd("lng"), note = m.s("note")
        )
    }

    private inner class CouponAdapter : Adapter<CouponEntity>("coupons") {
        override suspend fun allOnce() = db.coupons().allOnce()
        override fun clockOf(row: CouponEntity) = row.syncUpdatedAt
        override fun identityOf(row: CouponEntity, my: String) =
            if (row.originDeviceId.isEmpty()) Triple("", row.id, "$my|${row.id}")
            else Triple(row.originDeviceId, row.originId, "${row.originDeviceId}|${row.originId}")
        override suspend fun findByKey(key: String, my: String): CouponEntity? {
            val (od, oid) = splitKey(key)
            return if (od == my) db.coupons().byId(oid) else db.coupons().byOrigin(od, oid)
        }
        override suspend fun applyIncoming(incoming: CouponEntity, key: String, at: Long, my: String): Boolean {
            val local = findByKey(key, my)
            val (pod, poid) = originPair(key, my, local?.id ?: 0L)
            db.coupons().upsert((local ?: incoming).copy(
                id = local?.id ?: 0,
                code = incoming.code, kind = incoming.kind, amountPiasters = incoming.amountPiasters,
                percent = incoming.percent, expiresAt = incoming.expiresAt, maxUses = incoming.maxUses,
                usedCount = incoming.usedCount, active = incoming.active, note = incoming.note,
                createdAt = incoming.createdAt,
                syncUpdatedAt = at, originDeviceId = pod, originId = poid
            )); return true
        }
        override suspend fun deleteByKey(key: String, my: String): Boolean {
            val local = findByKey(key, my) ?: return false
            db.coupons().delete(local.id); return true
        }
        override fun bodyOf(row: CouponEntity, my: String) = "{\"code\":" + w(row.code) + ",\"kind\":" + w(row.kind) +
            ",\"amountPiasters\":" + w(row.amountPiasters) + ",\"percent\":" + w(row.percent) +
            ",\"expiresAt\":" + w(row.expiresAt) + ",\"maxUses\":" + w(row.maxUses) +
            ",\"usedCount\":" + w(row.usedCount) + ",\"active\":" + w(row.active) +
            ",\"note\":" + w(row.note) + ",\"createdAt\":" + w(row.createdAt) + "}"
        override fun fromBody(m: Map<*, *>, key: String) = CouponEntity(
            code = m.s("code", " "), kind = m.lg("kind").toInt(), amountPiasters = m.lg("amountPiasters"),
            percent = m.d("percent"), expiresAt = m.lg("expiresAt"), maxUses = m.lg("maxUses").toInt(),
            usedCount = m.lg("usedCount").toInt(), active = m.b("active", true),
            note = m.s("note"), createdAt = m.lg("createdAt")
        )
    }

    private inner class StatementTemplateAdapter : Adapter<StatementTemplateEntity>("statement_templates") {
        override suspend fun allOnce() = db.statementTemplates().allOnce()
        override fun clockOf(row: StatementTemplateEntity) = row.syncUpdatedAt
        override fun identityOf(row: StatementTemplateEntity, my: String) =
            if (row.originDeviceId.isEmpty()) Triple("", row.id, "$my|${row.id}")
            else Triple(row.originDeviceId, row.originId, "${row.originDeviceId}|${row.originId}")
        override suspend fun findByKey(key: String, my: String): StatementTemplateEntity? {
            val (od, oid) = splitKey(key)
            return if (od == my) db.statementTemplates().byId(oid) else db.statementTemplates().byOrigin(od, oid)
        }
        override suspend fun applyIncoming(incoming: StatementTemplateEntity, key: String, at: Long, my: String): Boolean {
            val local = findByKey(key, my)
            val (pod, poid) = originPair(key, my, local?.id ?: 0L)
            db.statementTemplates().upsert((local ?: incoming).copy(
                id = local?.id ?: 0,
                name = incoming.name, baseTemplateId = incoming.baseTemplateId,
                configJson = incoming.configJson, isDefault = incoming.isDefault,
                favorite = incoming.favorite, createdAt = incoming.createdAt, updatedAt = incoming.updatedAt,
                syncUpdatedAt = at, originDeviceId = pod, originId = poid
            )); return true
        }
        override suspend fun deleteByKey(key: String, my: String): Boolean {
            val local = findByKey(key, my) ?: return false
            db.statementTemplates().delete(local.id); return true
        }
        override fun bodyOf(row: StatementTemplateEntity, my: String) = "{\"name\":" + w(row.name) +
            ",\"baseTemplateId\":" + w(row.baseTemplateId) + ",\"configJson\":" + w(row.configJson) +
            ",\"isDefault\":" + w(row.isDefault) + ",\"favorite\":" + w(row.favorite) +
            ",\"createdAt\":" + w(row.createdAt) + ",\"updatedAt\":" + w(row.updatedAt) + "}"
        override fun fromBody(m: Map<*, *>, key: String) = StatementTemplateEntity(
            name = m.s("name", " "), baseTemplateId = m.s("baseTemplateId", "CUSTOM"),
            configJson = m.s("configJson", "{}"), isDefault = m.b("isDefault"),
            favorite = m.b("favorite"), createdAt = m.lg("createdAt"), updatedAt = m.lg("updatedAt")
        )
    }

    private inner class NoteTemplateAdapter : Adapter<NoteTemplateEntity>("note_templates") {
        override suspend fun allOnce() = db.noteTemplates().allOnce()
        override fun clockOf(row: NoteTemplateEntity) = row.syncUpdatedAt
        override fun identityOf(row: NoteTemplateEntity, my: String) =
            if (row.originDeviceId.isEmpty()) Triple("", row.id, "$my|${row.id}")
            else Triple(row.originDeviceId, row.originId, "${row.originDeviceId}|${row.originId}")
        override suspend fun findByKey(key: String, my: String): NoteTemplateEntity? {
            val (od, oid) = splitKey(key)
            return if (od == my) db.noteTemplates().byId(oid) else db.noteTemplates().byOrigin(od, oid)
        }
        override suspend fun applyIncoming(incoming: NoteTemplateEntity, key: String, at: Long, my: String): Boolean {
            val local = findByKey(key, my)
            val (pod, poid) = originPair(key, my, local?.id ?: 0L)
            db.noteTemplates().upsert((local ?: incoming).copy(
                id = local?.id ?: 0,
                title = incoming.title, body = incoming.body, isDefault = incoming.isDefault,
                syncUpdatedAt = at, originDeviceId = pod, originId = poid
            )); return true
        }
        override suspend fun deleteByKey(key: String, my: String): Boolean {
            val local = findByKey(key, my) ?: return false
            db.noteTemplates().delete(local.id); return true
        }
        override fun bodyOf(row: NoteTemplateEntity, my: String) = "{\"title\":" + w(row.title) +
            ",\"body\":" + w(row.body) + ",\"isDefault\":" + w(row.isDefault) + "}"
        override fun fromBody(m: Map<*, *>, key: String) = NoteTemplateEntity(
            title = m.s("title", " "), body = m.s("body"), isDefault = m.b("isDefault")
        )
    }

    // ───────────────────────── بناء الدفعات ─────────────────────────

    /**
     * يبني حمولات JSON جاهزة للتعمية. الدفعة الأولى: مسح كامل + كل الشواهد؛
     * وما بعدها: دفتر التغييرات المحلية فقط. صفوف كل هوية تُدمَج محتفظةً بأحدث ساعة.
     */
    suspend fun buildPushBlocks(): List<Pair<Long, String>> {
        val my = clock.deviceId()
        val full = !clock.fullPushed()

        data class Out(val table: String, val key: String, val at: Long, val deleted: Boolean, val body: String?)
        val byIdentity = LinkedHashMap<String, Out>()
        fun put(o: Out) {
            val existing = byIdentity[o.table + "§" + o.key]
            if (existing == null || o.at >= existing.at) byIdentity[o.table + "§" + o.key] = o
        }

        if (full) {
            for (a in allAdapters()) {
                @Suppress("UNCHECKED_CAST")
                val adapter = a as Adapter<Any>
                for (row in adapter.allOnce()) {
                    val (_, _, key) = adapter.identityOf(row, my)
                    put(Out(adapter.table, key, adapter.clockOf(row), false, adapter.bodyOf(row, my)))
                }
            }
        } else {
            for (e in db.syncLog().pendingLocal()) {
                if (e.deleted) continue // الشواهد تُعالج أدناه
                val a = adapterFor(e.tableName) ?: continue
                @Suppress("UNCHECKED_CAST")
                val adapter = a as Adapter<Any>
                val live = adapter.allOnce().asSequence().filterNotNull().firstOrNull { candidate ->
                    val (od, oid, _) = adapter.identityOf(candidate, my)
                    if (e.originDeviceId.isEmpty()) od.isEmpty() && oid == e.originId
                    else od == e.originDeviceId && oid == e.originId
                } ?: continue
                val (_, _, key) = adapter.identityOf(live, my)
                put(Out(adapter.table, key, adapter.clockOf(live), false, adapter.bodyOf(live, my)))
            }
        }
        // الشواهد — دائماً (ذاكرة قرار الحذف للأجهزة الجديدة والمتأخرة)
        for (t in db.syncLog().allTombstones()) {
            val key = if (t.tableName == "currencies") t.originDeviceId
            else if (t.originDeviceId.isEmpty()) "$my|${t.originId}" else "${t.originDeviceId}|${t.originId}"
            put(Out(t.tableName, key, t.updatedAt, true, null))
        }

        val rows = byIdentity.values
            .sortedWith(compareBy({ it.table }, { it.key }))
            .map { SyncMerge.rowJson(it.table, it.key, it.at, it.deleted, it.body) }

        val weights = rows.map { it.length }
        val batches = SyncMerge.chunkWeights(weights, MAX_BLOCK_BYTES)
        val blocks = ArrayList<Pair<Long, String>>(batches.size)
        for (batch in batches) {
            val seq = clock.nextSeq()
            val selected = batch.map { rows[it] }
            blocks.add(seq to SyncMerge.envelopeJson(my, seq, System.currentTimeMillis(), selected))
        }
        return blocks
    }

    /** يُستدعى بعد نجاح دفع كل الكتل — يعلّم اكتمال المسح الكامل ويقطع الدفتر. */
    suspend fun markPushed() {
        db.syncLog().prunePushed()
        if (!clock.fullPushed()) clock.markFullPushed()
    }

    // ───────────────────────── تطبيق الوارد ─────────────────────────

    suspend fun applyPayload(payload: String, fromDevice: String): ApplyStats {
        val my = clock.deviceId()
        val root = parseJson(payload) as? Map<*, *> ?: throw MiniJsonException("sync envelope: root")
        if (root["format"] != SyncMerge.SYNC_FORMAT) throw MiniJsonException("sync envelope: format")
        val version = (root["version"] as? Long) ?: 0L
        if (version > SyncMerge.SYNC_VERSION) throw MiniJsonException("sync envelope: version")
        val rows = root["rows"] as? List<*> ?: throw MiniJsonException("sync envelope: rows")

        var applied = 0; var skipped = 0; var tombs = 0; var deferred = 0
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("UPDATE `_sync_applying` SET `v` = 1")
            try {
                for (r in rows) {
                    val m = r as? Map<*, *> ?: continue
                    val table = m.s("t")
                    val key = m.s("k")
                    val at = m.lg("at")
                    val deleted = m.lg("d") == 1L
                    val a = adapterFor(table)
                    if (a == null || key.isEmpty()) { skipped++; continue }

                    @Suppress("UNCHECKED_CAST")
                    val adapter = a as Adapter<Any>
                    val local = adapter.findByKey(key, my)
                    val localClock = local?.let { adapter.clockOf(it) } ?: 0L
                    val (od, _) = splitKey(key)
                    // كسر التعادل بمعرّف الجهاز المرسِل (المحرر الفعلي) — لا الأصل:
                    // تحرير B لصف أصله A ليس صداً على A
                    if (!SyncMerge.remoteWins(at, localClock, fromDevice, my)) {
                        if (deleted) recordTombstone(table, key, at)
                        skipped++; continue
                    }
                    if (deleted) {
                        val ok = runCatching { adapter.deleteByKey(key, my) }.getOrDefault(false)
                        if (ok) applied++ else deferred++
                        tombs++
                        recordTombstone(table, key, at)
                        continue
                    }
                    val body = m["r"] as? Map<*, *>
                    if (body == null) { skipped++; continue }
                    val incoming = adapter.fromBody(body, key)
                    val ok = runCatching { adapter.applyIncoming(incoming, key, at, my) }.getOrDefault(false)
                    if (ok) applied++ else deferred++
                }
            } finally {
                db.openHelper.writableDatabase.execSQL("UPDATE `_sync_applying` SET `v` = 0")
            }
        }
        return ApplyStats(applied, skipped, tombs, deferred)
    }

    private suspend fun recordTombstone(table: String, key: String, at: Long) {
        val (od, oid) = if (table == "currencies") key to 0L else splitKey(key)
        val existing = db.syncLog().allTombstones().firstOrNull {
            it.tableName == table && it.originDeviceId == od && it.originId == oid
        }
        if (existing != null && existing.updatedAt >= at) return
        db.syncLog().upsert(
            SyncLogEntity(tableName = table, originDeviceId = od, originId = oid, updatedAt = at, deleted = true, imported = true)
        )
    }

    suspend fun pendingCount(): Int = db.syncLog().pendingCount()

    companion object {
        /** سقف كتلة الحمولة قبل التعمية — أقل من سقف D4 (8MB) بمساحة الترويسات والتوسيع. */
        const val MAX_BLOCK_BYTES: Int = 4 * 1024 * 1024
    }
}
