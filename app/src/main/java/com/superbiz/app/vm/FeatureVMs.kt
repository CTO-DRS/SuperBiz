package com.superbiz.app.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.superbiz.app.AppGraph
import com.superbiz.app.core.ErrorCenter
import com.superbiz.app.R
import com.superbiz.app.data.db.CheckEntity
import com.superbiz.app.data.db.CouponEntity
import com.superbiz.app.data.db.Invoice
import com.superbiz.app.data.db.InvoiceItem
import com.superbiz.app.data.db.Party
import com.superbiz.app.data.db.Product
import com.superbiz.app.data.db.Rule
import com.superbiz.app.data.db.StatementRow
import com.superbiz.app.data.repo.toSpec
import com.superbiz.app.domain.ForecastResult
import com.superbiz.app.domain.LoyaltyP46
import com.superbiz.app.domain.RiskResult
import com.superbiz.app.domain.SettingsCodec
import com.superbiz.app.domain.TrialRow
import com.superbiz.app.ui.components.parseNum
import com.superbiz.app.ui.screens.LocationCapture
import com.superbiz.app.util.BarcodeGen
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.room.withTransaction

// ═════════ الديون ═════════
class DebtsVM(app: Application) : AndroidViewModel(app) {
    private val g = AppGraph.from(app)

    val parties = g.ledger.parties()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filter = MutableStateFlow(0) // 0 الكل 1 عملاء 2 موردين

    data class RowUi(val party: Party, val balance: Long, val risk: RiskResult?)  // [P33-P8] قروش

    private val refreshKey = MutableStateFlow(0)
    // كل تحديث يعيد حساب صحة التحصيل أيضاً
    fun refresh() { refreshKey.value++; loadHealth() }

    // وضع الفرز (وظيفة 23) — 0 السلوك القائم بلا رقاقة، 1 الأقدمية، 2 أعلى رصيد، 3 الاسم
    val sortMode = MutableStateFlow(com.superbiz.app.domain.DebtSort.MODE_DEFAULT)

    val rows: StateFlow<List<RowUi>> = kotlinx.coroutines.flow.combine(
        parties, filter, refreshKey, sortMode
    ) { list, f, _, sm ->
        val filtered = when (f) {
            1 -> list.filter { it.isCustomer }
            2 -> list.filter { it.isSupplier }
            else -> list
        }
        // [P5-H8 إصلاح]: كان الجسم كله يعمل على Main — استدعاء risk() لكل طرف في كل
        // انبعاث + مسح كل الفواتير عند الفرز يجمّد الواجهة مع آلاف الأطراف. النقل
        // الكامل إلى Default يجعل Main يستقبل النتيجة النهائية فقط
        kotlinx.coroutines.withContext(Dispatchers.Default) {
            // كل الأرصدة في استعلام SQL واحد — حقيقية من دفتر الأستاذ بلا N+1
            // [P33-P8] balances() قروش Long — تُحوَّل ريالاً لنموذج العرض (الرصيد يُعرض عبر Money.format)
            val balances = try { g.ledger.balances() } catch (e: Exception) { emptyMap<Long, Long>() }
            // أقدمية الفرز (وظيفة 23) — أقدم فاتورة بيع غير مسددة لكل طرف من الفواتير الحقيقية
            val oldestOpen = if (sm == com.superbiz.app.domain.DebtSort.MODE_OLDEST) {
                try { com.superbiz.app.domain.DebtSort.oldestOpenMap(g.db.invoices().allOnce()) }
                catch (e: Exception) { emptyMap<Long, Long>() }
            } else emptyMap()
            val mapped = filtered.map { p ->
                val risk = try { g.ledger.risk(p.id) } catch (e: Exception) { null }
                // [P33-P8] الرصيد قروش من الدفتر — مخزَّن ومُعرَض بالقروش
                RowUi(p, balances[p.id] ?: 0L, risk)
            }
            // الفرز في VM عبر مقارن نقية (DebtSort) — الوضع 0 يحافظ على سلوك القائمة القائم تماماً
            when (sm) {
                com.superbiz.app.domain.DebtSort.MODE_OLDEST,
                com.superbiz.app.domain.DebtSort.MODE_BALANCE,
                com.superbiz.app.domain.DebtSort.MODE_NAME -> mapped.sortedWith(
                    com.superbiz.app.domain.DebtSort.comparator(sm) { r ->
                        com.superbiz.app.domain.DebtSort.SortRow(
                            r.party.id, r.party.name, r.balance, oldestOpen[r.party.id]
                        )
                    }
                )
                else -> mapped.sortedByDescending { kotlin.math.abs(it.balance) }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun saveParty(name: String, phone: String, type: Int, note: String, id: Long = 0) =
        launchSafe {
            // رسالة محلية واضحة بدل اعتماد الانفجار في المستودع —
            // الاسم فارغ بعد التقليم يُرفض قبل لمس قاعدة البيانات
            if (name.isBlank()) {
                com.superbiz.app.core.ErrorCenter.warn(
                    "DebtsVM", "rejected party save",
                    getApplication<Application>().getString(com.superbiz.app.R.string.err_name_blank)
                )
                return@launchSafe
            }
            g.ledger.saveParty(Party(id = id, name = name.trim(), phone = phone.trim(), type = type, note = note))
            refresh()
        }

    fun deleteParty(id: Long) = launchSafe {
        // [H1-4][v13] حذف نهائي لطرف — باب المالك وحده (مصفوفة §3 سطر 5)
        com.superbiz.app.domain.rbac.RoleGate.require(
            com.superbiz.app.domain.rbac.SessionState.effective(),
            com.superbiz.app.domain.rbac.Op.HARD_DELETE
        )
        g.ledger.deleteParty(id); refresh()
    }

    // [P33-P8] المبلغ يدخل ريالاً من الواجهة ويُحوَّل قروشاً عند الحدود الوحيدة (Money) قبل الدفتر
    // [تدقيق M-6] direction يمرّ للدفتر — الطرف ثنائي الدور يختار جانب الدين من الواجهة
    fun addDebt(party: Party, amount: Double, note: String, direction: Int = 0, date: Long = System.currentTimeMillis()) =
        launchSafe { g.ledger.addDebt(party, com.superbiz.app.util.Money.toPiasters(amount), date, note, direction); refresh() }

    fun addPayment(party: Party, amount: Double, direction: Int, method: String = "CASH") =
        launchSafe {
            g.ledger.addPayment(party, com.superbiz.app.util.Money.toPiasters(amount), System.currentTimeMillis(), direction, method)
            refresh()
        }

    // كشف حساب
    data class StatementUi(
        val party: Party, val rows: List<StatementRow>, val balance: Long, val risk: RiskResult,  // [P33-P8] قروش
        // درجة الجدارة السلوكية من BizMath.creditScore + مدخلاتها
        val credit: Int = 70,
        val creditInputs: com.superbiz.app.data.repo.LedgerRepo.CreditInputs? = null
    )

    private val _statement = MutableStateFlow<StatementUi?>(null)
    val statement: StateFlow<StatementUi?> = _statement

    fun openStatement(party: Party) = launchSafe {
        val rows = g.ledger.statement(party.id)
        // [P33-P8] رصيد الكشف قروش من الدفتر — مخزَّن ومُعرَض بالقروش
        val bal = g.ledger.partyBalance(party.id)
        val risk = g.ledger.risk(party.id)
        // درجة جدارية حقيقية من سلوك السداد — تُعرض في الكشف
        val ci = try { g.ledger.creditInputs(party.id) } catch (e: Exception) { null }
        val score = ci?.let {
            // [P33-P8] openTotal/overdueTotal قروش من CreditInputs — البحوث النقية BizMath
            // ما زالت بواجهة ريالية (خارج الشرائح) — التحويل عبر fromPiasters هنا فقط
            com.superbiz.app.domain.analytics.creditScore(
                com.superbiz.app.util.Money.fromPiasters(it.openTotal),
                com.superbiz.app.util.Money.fromPiasters(it.overdueTotal),
                it.avgDelayDays,
                it.invoiceCount, it.largestOpenShare, it.paidRatio
            )
        } ?: 70
        _statement.value = StatementUi(party, rows, bal, risk, score, ci)
    }

    fun closeStatement() { _statement.value = null }

    // ══ : كشف ودمج الأطراف المكررة — isProbableDuplicate بعد التطبيع العربي ══

    data class DuplicatePair(val keep: Party, val dup: Party)

    private val _duplicates = MutableStateFlow<List<DuplicatePair>>(emptyList())
    val duplicates: StateFlow<List<DuplicatePair>> = _duplicates

    private val _duplicatesScanning = MutableStateFlow(false)
    val duplicatesScanning: StateFlow<Boolean> = _duplicatesScanning

    fun findDuplicates() = launchSafe {
        _duplicatesScanning.value = true
        try {
            // [P20-FIX agent8]: كان يقرأ StateFlow قبل أول انبعاث Room (بداية باردة) فيفيد
            // «لا مكررات» من قائمة فارغة — أول() ينتظر القيمة الحقيقية (نمط ChecksVM:1186)
            val list = g.ledger.parties().first()
            // الحلقة الثنائية O(n²·len) كانت على Main — 2000 طرف ≈
            // مليونا تقريب تشابه = تجميد مرئي. نفس علاج DataHealthVM (R12-C20)
            val out = withContext(Dispatchers.Default) {
                val res = ArrayList<DuplicatePair>()
                // إضافة إشارة التشابه الضبابي jaroWinkler≥0.87 بعد التطبيع العربي —
                // كانت القاعدة تلتقط التطابق التام/الرقمي فقط وتفوّت «محمد أحمد»/«محمدAhmed» أو الأخطاء المطبعية
                for (i in list.indices) {
                    for (j in i + 1 until list.size) {
                        val a = list[i]; val b = list[j]
                        val exact = com.superbiz.app.domain.analytics.isProbableDuplicate(a.name, b.name, a.phone, b.phone)
                        val fuzzy = !exact && run {
                            val na = com.superbiz.app.domain.algo.TextMath.arabicNormalize(a.name)
                            val nb = com.superbiz.app.domain.algo.TextMath.arabicNormalize(b.name)
                            na.length >= 3 && nb.length >= 3 &&
                                maxOf(
                                    com.superbiz.app.domain.algo.TextMath.jaroWinkler(na, nb),
                                    com.superbiz.app.domain.algo.TextMath.similarityRatio(na, nb)
                                ) >= 0.87 &&
                                (a.phone.isBlank() || b.phone.isBlank() ||
                                    com.superbiz.app.domain.algo.TextMath.digitsOnly(a.phone) ==
                                    com.superbiz.app.domain.algo.TextMath.digitsOnly(b.phone))
                        }
                        if (exact || fuzzy) {
                            res.add(DuplicatePair(if (a.createdAt <= b.createdAt) a else b,
                                if (a.createdAt <= b.createdAt) b else a))
                        }
                    }
                }
                res
            }
            _duplicates.value = out
        } finally {
            _duplicatesScanning.value = false
        }
    }

    /** دمج زوج مكرر فعلياً — معاملة ذرّية في المستودع ثم تحديث القائمة */
    fun mergeDuplicate(pair: DuplicatePair, onDone: (Boolean) -> Unit) = launchSafe {
        val ok = try { g.ledger.mergeParties(pair.keep.id, pair.dup.id) } catch (e: Exception) { false }
        if (ok) {
            refresh()
            _duplicates.value = _duplicates.value.filterNot {
                it.dup.id == pair.dup.id || it.keep.id == pair.dup.id
            }
        }
        onDone(ok)
    }

    // ══ : صحة التحصيل — DSO + كفاءة التحصيل من FinMath على بيانات حقيقية ══

    data class DebtHealth(
        val dso: Double,                  // متوسط أيام التحصيل (كلما قلّ كان أفضل)
        // التسمية الصادقة — المقام كامل المبيعات (النقدية عند البيع
        // لا تمر بالذمم أصلاً) فالنسبة «مقبوضات ÷ كل المبيعات» لا «كفاءة تحصيل الآجل»
        val collectionEfficiency: Double, // 0..100 — مقبوضات 90 يوماً مقابل كامل المبيعات
        // [P33-P8] المبالغ قروش Long — تُستهلك في النسب عبر fromPiasters عند حدود FinMath
        val receivables: Long,            // إجمالي المديونية المفتوحة (قروش)
        val creditSales90: Long,          // مبيعات آجلة آخر 90 يوماً (قروش)
        val collected90: Long,            // مقبوضات آخر 90 يوماً بلا ديون/شيكات مرتدة (قروش)
        // دلاء أعمار الذمم (وظيفة 29) — من نفس مصدر الفواتير الحقيقي [0-30، 31-60، 61-90، 90+]
        // (تبقى ريالاً بواجهة analytics القائمة — التحويل داخل DebtAging)
        val aging: List<Double> = emptyList()
    )

    private val _health = MutableStateFlow<DebtHealth?>(null)
    val health: StateFlow<DebtHealth?> = _health

    /** حساب صحة التحصيل من الفواتير والدفعات الحقيقية — يُستدعى مع كل refresh وعند البدء */
    fun loadHealth() = launchSafe {
        // [P5-H8 إصلاح]: مسح جدولي كامل (فواتير + دفعات + أعمار) كان على Main — نُقله إلى Default
        kotlinx.coroutines.withContext(Dispatchers.Default) {
            try {
            val now = System.currentTimeMillis()
            // نافذة 90 يوماً بحضن التقويم الآمن بدل الإزاحة الثابتة
            val from = com.superbiz.app.util.Dates.startOfDayDaysAgo(90, now)
            val sales = g.db.invoices().allOnce()
            // [P33-P8] مساواة صحيحة تامة بدل عتبة 0.004 العائمة
            val receivables = sales.filter { it.isSale && it.status < 3 && it.open > 0L }.sumOf { it.open }
            val creditSales90 = sales.filter { it.isSale && it.status < 3 && it.date in from..now }.sumOf { it.total }
            val collected90 = try {
                g.db.payments().since(from)
                    // استرداد شيك صادر (CHECK_BOUNCE اتجاه 0) كان يُحسب
                    // تحصيلاً فيتضخم كفاءة التحصيل بلا قبض فعلي من أي عميل
                    .filter { it.direction == 0 && it.method != "DEBT" && it.method != "CHECK_BOUNCE" && it.date in from..now }
                    .sumOf { it.amount }
            } catch (e: Exception) { 0L }
            _health.value = DebtHealth(
                // [P33-P8] النسب (dso/كفاءة) تُحسب على القيم الريالية عبر حدود FinMath القائمة
                dso = com.superbiz.app.domain.algo.FinMath.dso(
                    com.superbiz.app.util.Money.fromPiasters(receivables),
                    com.superbiz.app.util.Money.fromPiasters(creditSales90), 90),
                collectionEfficiency = com.superbiz.app.domain.algo.FinMath.collectionEfficiency(
                    com.superbiz.app.util.Money.fromPiasters(collected90),
                    com.superbiz.app.util.Money.fromPiasters(creditSales90)),
                receivables = receivables,
                creditSales90 = creditSales90,
                collected90 = collected90,
                // وظيفة 29 — استهلاك BizMath.agingBuckets القائمة على الفواتير المفتوحة نفسها
                aging = try { com.superbiz.app.domain.DebtAging.bucketsFromInvoices(sales, now) }
                catch (e: Exception) { emptyList() }
            )
        } catch (e: Exception) {
            android.util.Log.e("SuperBizVM", "debt health failed", e)
        }
        }
    }

    init { loadHealth() }

    // ══ : أعلى 5 مدينين (وظيفة 30) — أرصدة حقيقية من دفتر الأستاذ، مستقلة عن فلتر النوع ══

    val topDebtors: StateFlow<List<com.superbiz.app.domain.TopDebtors.Debtor>> =
        kotlinx.coroutines.flow.combine(parties, refreshKey) { list, _ ->
            // [P33-P8] الأرصدة قروش — تُحوَّل ريالاً عند حدود TopDebtors القائمة (بواجهة ريالية)
            val balances = try { g.ledger.balances() } catch (e: Exception) { emptyMap<Long, Long>() }
            com.superbiz.app.domain.TopDebtors.top(
                list.map { it.name to com.superbiz.app.util.Money.fromPiasters(balances[it.id] ?: 0L) }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ══ : تذكيرات خطة السداد المقترحة (وظيفة 21) — أعمال WorkManager مؤجلة بموعد كل دفعة ══

    fun createPlanReminders(
        party: Party,
        payments: List<com.superbiz.app.domain.DebtPlan.PlanPayment>,
        onDone: (Int) -> Unit
    ) = launchSafe {
        val n = com.superbiz.app.work.PaymentPlanReminders.schedule(
            getApplication(), party.id, party.name, payments
        )
        onDone(n)
    }
}

// ═════════ الفواتير ═════════
class InvoicesVM(app: Application) : AndroidViewModel(app) {
    private val g = AppGraph.from(app)

    // القائمة تشمل الملغاة (status = 3) لرقاقة فلتر «ملغاة» الصادقة — بلا تغيير مخطط
    val invoices = g.invoices.invoicesIncludingVoid()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val parties = g.ledger.parties()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val products = g.inventory.products()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val statusFilter = MutableStateFlow(-1) // -1 الكل

    // نطاق زمني سريع (0 الكل، 1 اليوم، 2 الأسبوع، 3 الشهر) + بحث برقم الفاتورة أو الطرف
    val rangeFilter = MutableStateFlow(0)
    val searchQuery = MutableStateFlow("")

    // الترشيح كله في الـ VM عبر InvoiceFilters النقي (حالة + نطاق + بحث مطبّع)
    val filtered = kotlinx.coroutines.flow.combine(
        invoices, statusFilter, rangeFilter, searchQuery, parties
    ) { list, st, rg, q, pts ->
        com.superbiz.app.domain.InvoiceFilters.apply(
            list = list,
            partyNames = pts.associate { it.id to it.name },
            status = st,
            range = rg,
            query = q,
            now = System.currentTimeMillis()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // محرر الفاتورة
    // [P41-L1] فئة ضريبة السطر (0 قياسية/1 صفرية/2 معفاة) — المنتقي في بطاقة كل سطر
    // [P42-R3] جولة 3 — النسبة الصريحة للسطر تتعرض للمحرر أخيراً (القرار المؤجل الموثق
    // في P41): taxRateText نص الحقل المعروض وtaxRatePct مشتقه الرقمية المحصورة، ويُكتبان
    // معاً من مسار واحد setLineTaxRate فلا انفكاك بين المعروض والمحفوظ. النص الفارغ
    // يعني RATE_INHERIT (وراثة نسبة الرأس — السلوك التاريخي الحرفي للصفوف القديمة).
    data class EditorItem(val productId: Long?, val desc: String, val qty: String, val price: String, val discount: String = "0", val taxKind: Int = 0, val taxRatePct: Double = -1.0, val taxRateText: String = "")

    val editorOpen = MutableStateFlow(false)
    val editorType = MutableStateFlow(0)          // 0 بيع 1 شراء
    val editorParty = MutableStateFlow<Party?>(null)
    val editorItems = MutableStateFlow(listOf(EditorItem(null, "", "", "")))
    val editorTaxRate = MutableStateFlow(15.0)
    val editorDueDays = MutableStateFlow(14)
    val editorInvoiceId = MutableStateFlow<Long?>(null) // تعديل؟ حالياً إنشاء فقط

    // [H4-1 V 2.5.0] عملة الفاتورة — الأفق الرابع (عملات متعددة):
    // كتالوج العملات لمنتقي المحرر + عملة المحرر الحالية ("‏" تُتبع الأساس) —
    // الأسعار تُدخل بعملة الفاتورة وتُحوَّل قروش أساس عند الحفظ عبر Money.foreignToBasePiasters
    // (نقطة التحويل الوحيدة — عقد R17)، والفئة الأصلية تُختَم على الصف (origCurrency/origTotal/origFxMicros)
    val currencies = g.db.currencies().all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val editorCurrency = MutableStateFlow("")

    /** micros سعر العملة من مخزون الكتالوج المعروض — للمعاينة في الواجهة حصراً (الحفظ يعيد الاشتقاق من allOnce) */
    fun rateMicrosFor(code: String): Long? {
        if (code.isBlank()) return null
        val entry = currencies.value.firstOrNull { it.code == code } ?: return null
        return com.superbiz.app.domain.algo.FxStampMath.rateMicrosFromCatalog(entry.rateToBase)
    }

    // [P33-P8] الأسعار/الخصومات نصوص ريالية → parseToPiasters قروش؛ الكمية تبقى Double؛
    // (المجموع الفرعي، الضريبة، الإجمالي) قروش Long: الضريبة Math.round(الصافي × النسبة/100)
    fun totals(items: List<EditorItem>, taxRate: Double): Triple<Long, Long, Long> {
        val sub = items.sumOf { Math.round(parseNum(it.qty) * com.superbiz.app.util.Money.parseToPiasters(it.price)) }
        // حصر الخصم ضمن 0..المجموع — كان خصم سالب (الإدخال يقبل "-50") يضخّم الإجمالي
        val disc = items.sumOf { com.superbiz.app.util.Money.parseToPiasters(it.discount) }.coerceIn(0L, sub)
        val net = sub - disc
        val tax = Math.round(net * taxRate / 100.0)
        return Triple(sub, tax, net + tax)
    }

    // [P41-L1] v11 — مسار السطر الصريح: أي سطر يحمل فئة غير قياسية يفتح حساب السطر
    // والضريبة = مجموع ضرائب الأسطر عبر LineTaxP41 (نفس صندوق التقرير والXML فلا
    // انحراف بين المحجوز والمعلن) — null = لا سطر صريح ⇒ يستدعي totals() التاريخي
    // فالسلوك القديم لا يُلمس إطلاقاً (الصفوف القديمة كلها افتراضياً بلا سطر صريح).
    // خصم كل سطر محصور 0..قيمة السطر (نفس حرس badDisc الذي يرفض الحفظ قبله،
    // والمعاينة السابقة للحفظ تبقى سليمة بالحصر نفسه)
    fun totalsLineAware(items: List<EditorItem>, taxRate: Double): Triple<Long, Long, Long>? {
        val lineGross = items.map { Math.round(parseNum(it.qty) * com.superbiz.app.util.Money.parseToPiasters(it.price)) }
        val nets = items.mapIndexed { i, it ->
            lineGross[i] - com.superbiz.app.util.Money.parseToPiasters(it.discount).coerceIn(0L, lineGross[i])
        }
        val tax = com.superbiz.app.domain.LineTaxP41.invoiceTaxFromLines(
            nets, items.map { it.taxKind }, items.map { it.taxRatePct }, taxRate
        ) ?: return null
        val sub = lineGross.sumOf { it }
        val disc = lineGross.indices.sumOf { i -> lineGross[i] - nets[i] }
        return Triple(sub, tax, sub - disc + tax)
    }

    // [P42-R3] جولة 3 — المسار الوحيد لكتابة نسبة السطر من الواجهة: النص ومشتقه
    // الرقمية معاً من دالة واحدة فلا انفكاك أبداً. التحليل بعقد LineTaxP41
    // .parseExplicitRate نفسه: فارغ/غير رقمي/سالب ⇒ RATE_INHERIT (وراثة الرأس)،
    // ومعلن ⇒ محصور 0..100 (الصفر صريح ويفتح مسار السطر بعقد isExplicit).
    fun setLineTaxRate(index: Int, text: String) {
        val rate = com.superbiz.app.domain.LineTaxP41.parseExplicitRate(text)
            ?: com.superbiz.app.domain.LineTaxP41.RATE_INHERIT
        editorItems.value = editorItems.value.mapIndexed { idx, e ->
            if (idx == index) e.copy(taxRateText = text, taxRatePct = rate) else e
        }
    }

    fun openEditor(type: Int, presetParty: Party? = null) {
        editorType.value = type
        editorParty.value = presetParty
        editorItems.value = listOf(EditorItem(null, "", "", ""))
        launchSafe {
            editorTaxRate.value = g.settings.snapshot().taxRate
            // [H4-1] عملة المحرر تتبع الأساس عند كل فتح — الاختيار الأجنبي قرار واعٍ لكل فاتورة
            editorCurrency.value = g.settings.snapshot().baseCurrency
        }
        editorOpen.value = true
    }

    fun closeEditor() { editorOpen.value = false }

    // علم انشغال يمنع النقر المزدوج على الحفظ فيُنشئ فاتورتين برقمين متتاليين
    private var saveBusy = false

    fun saveInvoice(onDone: (Long) -> Unit = {}) = launchSafe(onDone = { saveBusy = false }) {
        if (saveBusy) return@launchSafe
        saveBusy = true
        val party = editorParty.value ?: return@launchSafe
        val items = editorItems.value.filter { it.desc.isNotBlank() && parseNum(it.qty) > 0 }
        if (items.isEmpty()) return@launchSafe
        // [P5-H11 إصلاح]: توحيد الدلالة مع POS — رفض صريح بدل الصمت:
        // (1) خصم بند سالب أو أكبر من قيمة بند كان يُحصر بصمت coerceIn فيُخفي الخطأ
        // (2) فاتورة بيع بكمية تتجاوز المخزون كانت تُرحَّل مخزوناً سالباً صامتاً
        // [P33-P8] قيمة البند وخصمه قروش — مقارنة صحيحة تامة بلا عتبة 0.004
        val badDisc = items.firstOrNull {
            val d = com.superbiz.app.util.Money.parseToPiasters(it.discount)
            val line = Math.round(parseNum(it.qty) * com.superbiz.app.util.Money.parseToPiasters(it.price))
            d < 0L || d > line
        }
        if (badDisc != null) {
            com.superbiz.app.core.ErrorCenter.warn(
                "InvoicesVM", "rejected invalid line discount",
                getApplication<Application>().getString(com.superbiz.app.R.string.inv_discount_invalid)
            )
            return@launchSafe
        }
        if (editorType.value == 0) {
            val over = items.firstOrNull { ei ->
                val pid = ei.productId ?: return@firstOrNull false
                val p = products.value.firstOrNull { it.id == pid } ?: return@firstOrNull false
                parseNum(ei.qty) > p.stockQty + 0.004
            }
            if (over != null) {
                com.superbiz.app.core.ErrorCenter.warn(
                    "InvoicesVM", "rejected qty over stock",
                    getApplication<Application>().getString(
                        com.superbiz.app.R.string.inv_over_stock, over.desc.trim()
                    )
                )
                return@launchSafe
            }
        }
        val taxRate = editorTaxRate.value
        // ── [H4-1 V 2.5.0] عملة الفاتورة وتحويل الحدود الوحيد ──
        // الأسعار المدخلة بعملة المحرر (أجنبية عند اختيارها). الأجنبية: تُحسب
        // الفئة الأصلية من النصوص المدخلة نفسها (origTotal)، ثم يُحوَّل «كل سطر مرة
        // واحدة» عبر Money.foreignToBasePiasters (عقد R17 — نقطة التحويل الوحيدة)،
        // وتُعاد كتابة نصوص السعر/الخصم بقروش الأساس فيُستأنف خط الأساس الحرفي
        // القديم كاملاً (totals/cost/قيد) بلا أي منطق تقريب جديد. الإخفاق مغلَق:
        // سعر غير صالح أو تحويل فاشل يرفض الحفظ برسالة — لا صفر مالي زائف.
        val baseCode = g.settings.snapshot().baseCurrency
        val curCode = editorCurrency.value.ifBlank { baseCode }
        // [H4-1] الأساس لا يحتاج كتالوجاً — هوية التحويل بحكم التعريف (rate 1.0) وختمه فارغ،
        // فلا يعتمد الحفظ الأساسي على بذور الكتالوج إطلاقاً. الكتالوج يُطلب للعملات الأجنبية حصراً.
        val isForeign = curCode != baseCode
        val curEntry = if (isForeign) g.db.currencies().allOnce().firstOrNull { it.code == curCode } else null
        if (isForeign && curEntry == null) {
            com.superbiz.app.core.ErrorCenter.warn(
                "InvoicesVM", "rejected unknown currency $curCode",
                getApplication<Application>().getString(com.superbiz.app.R.string.cur_rate_invalid)
            )
            return@launchSafe
        }
        var origMicros = 0L
        var origTotalF = 0L
        var effItems = items
        if (isForeign) {
            // الحرس أعلاه يضمن عدم الـnull هنا — القيمة المحلية للذكاء النمطي
            val entry = curEntry ?: return@launchSafe
            val micros = com.superbiz.app.domain.algo.FxStampMath.rateMicrosFromCatalog(entry.rateToBase)
            if (micros == null) {
                com.superbiz.app.core.ErrorCenter.warn(
                    "InvoicesVM", "rejected invalid fx rate for $curCode (${entry.rateToBase})",
                    getApplication<Application>().getString(com.superbiz.app.R.string.cur_rate_invalid)
                )
                return@launchSafe
            }
            origMicros = micros
            val (subF, _, totalF) = totalsLineAware(items, taxRate) ?: totals(items, taxRate)
            origTotalF = totalF
            val converted = items.map { ei ->
                val pB = com.superbiz.app.util.Money.foreignToBasePiasters(
                    com.superbiz.app.util.Money.parseToPiasters(ei.price), 2, micros)
                val dB = com.superbiz.app.util.Money.foreignToBasePiasters(
                    com.superbiz.app.util.Money.parseToPiasters(ei.discount), 2, micros)
                Triple(ei, pB, dB)
            }
            if (converted.any { it.second == null || it.third == null }) {
                com.superbiz.app.core.ErrorCenter.warn(
                    "InvoicesVM", "rejected unconvertible line for $curCode",
                    getApplication<Application>().getString(com.superbiz.app.R.string.cur_rate_invalid)
                )
                return@launchSafe
            }
            effItems = converted.map { (ei, pB, dB) ->
                ei.copy(price = com.superbiz.app.util.Money.numP(pB!!), discount = com.superbiz.app.util.Money.numP(dB!!))
            }
        }
        // [P41-L1]: مسار السطر الصريح إن وُجد وإلا المسار التاريخي الحرفي — لا تقريب مزدوج
        val (sub, tax, total) = totalsLineAware(effItems, taxRate) ?: totals(effItems, taxRate)
        // خصم الفاتورة كان يُحسب من البنود ولا يُحفظ في الرأس —
        // فيُبنى قيد غير متوازن (صافي بلا خصم مقابل إجمالي به خصم) ويفشل الحفظ صامتاً.
        // [P33-P8] الخصم مشتق بالقروش مطروحاً تاماً (sub − (total − tax)) — بلا تقريب إطلاقاً
        val disc = sub - (total - tax)
        val now = System.currentTimeMillis()
        // [P33-P8] تكلفة البند قروش × كمية Double → تقريب لقرش لكل بند
        val costTotal = items.sumOf { ei ->
            val p = ei.productId?.let { pid -> products.value.firstOrNull { it.id == pid } }
            Math.round(parseNum(ei.qty) * (p?.costPrice ?: 0L))
        }
        val inv = Invoice(
            number = g.invoices.nextNumber(editorType.value == 0),
            partyId = party.id, type = editorType.value,
            date = now, dueDate = now + editorDueDays.value * 86_400_000L,
            subtotal = sub, discount = disc, taxRate = taxRate, taxAmount = tax, total = total,
            costTotal = costTotal,
            // [H4-1] العملة المعروضة للفاتورة + ختم الفئة الأصلية (فارغ = بالأساس نفسه)
            currency = curCode,
            origCurrency = if (isForeign) curCode else "",
            origTotal = origTotalF,
            origFxMicros = origMicros
        )
        val itemEntities = effItems.map {
            InvoiceItem(
                invoiceId = 0, productId = it.productId, desc = it.desc.trim(),
                // [P33-P8] الكمية Double والسعر/الخصم قروش Long
                qty = parseNum(it.qty), unitPrice = com.superbiz.app.util.Money.parseToPiasters(it.price),
                // خصم البند محصور ضمن 0..قيمة البند
                discount = com.superbiz.app.util.Money.parseToPiasters(it.discount)
                    .coerceIn(0L, Math.round(parseNum(it.qty) * com.superbiz.app.util.Money.parseToPiasters(it.price))),
                // [P41-L1] فئة السطر ونسبته المعلنة (افتراضهما محايد للصفوف التاريخية)
                taxKind = it.taxKind, taxRate = it.taxRatePct
            )
        }
        val id = g.invoices.save(inv, itemEntities)
        editorOpen.value = false
        onDone(id)
    }

    fun markPaid(inv: Invoice) = launchSafe {
        val party = g.ledger.party(inv.partyId) ?: return@launchSafe
        // [P33-P8] المفتوح قروش — مساواة صحيحة تامة بدل عتبة 0.004
        if (inv.status < 3 && inv.open > 0L) {
            g.ledger.addPayment(party, inv.open, System.currentTimeMillis(), if (inv.isSale) 0 else 1, "CASH", inv.id)
        }
    }

    fun voidInvoice(inv: Invoice) = launchSafe {
        // [H1-4][v13] إلغاء فاتورة — بلا الكاشير (مصفوفة §3 سطر 3)
        com.superbiz.app.domain.rbac.RoleGate.require(
            com.superbiz.app.domain.rbac.SessionState.effective(),
            com.superbiz.app.domain.rbac.Op.INVOICE_CANCEL
        )
        g.invoices.voidInvoice(inv)
    }

    // ══ : تكرار فاتورة كمسودة (وظيفة P4-1 رقم 7) ══

    // pattern: علم انشغال يمنع النقر المزدوج فيُنشئ فاتورتين متطابقتين
    private var dupBusy = false

    /**
     * تكرار فاتورة كمسودة غير مسددة: نفس الطرف والبنود بتاريخ اليوم، عبر مسار الحفظ الحقيقي
     * InvoiceRepo.save (معاملة ذرّية: بنود + مخزون + قيد مزدوج بلا تحصيل).
     * يعيد رقم الفاتورة الجديدة عبر onDone، أو null عند الفشل/الفاتورة الملغاة.
     */
    fun duplicateAsDraft(inv: Invoice, onDone: (String?) -> Unit = {}) =
        launchSafe(onDone = { dupBusy = false }) {
            if (dupBusy) return@launchSafe
            if (inv.status == 3) { onDone(null); return@launchSafe }
            dupBusy = true
            val orig = g.invoices.fullInvoice(inv.id)
            if (orig == null || orig.second.isEmpty()) { onDone(null); return@launchSafe }
            val now = System.currentTimeMillis()
            // مدة السداد الأصلية تُحفظ: الاستحقاق = اليوم + (الاستحقاق القديم − تاريخه)
            val creditDays = ((orig.first.dueDate - orig.first.date).coerceAtLeast(0)) / 86_400_000L
            val copy = orig.first.copy(
                id = 0,
                number = g.invoices.nextNumber(orig.first.isSale),
                date = now,
                dueDate = now + creditDays * 86_400_000L,
                paid = 0L, // [P33-P8] قروش
                status = 0
            )
            // [P20-FIX agent8]: كان التكرار يتجاوز فحص المخزون (save بـ moveStock=true بلا تحقق)
            // فيُكرّر خصم المخزون وقد يجعله سالباً — نفس سياسة P5-H11 في saveInvoice بالضبط
            if (orig.first.isSale) {
                val perProduct = HashMap<Long, Double>()
                for (it2 in orig.second) {
                    val pid = it2.productId ?: continue
                    perProduct[pid] = (perProduct[pid] ?: 0.0) + it2.qty
                }
                val over = perProduct.entries.firstOrNull { (pid, qty) ->
                    val p = pid.takeIf { it > 0 }?.let { g.inventory.product(it) }
                    p != null && p.stockQty > 0 && qty > p.stockQty + 0.004
                }
                if (over != null) {
                    val p = g.inventory.product(over.key)
                    com.superbiz.app.core.ErrorCenter.warn(
                        "InvoicesVM", "duplicate rejected qty over stock",
                        getApplication<Application>().getString(
                            com.superbiz.app.R.string.inv_over_stock, p?.name?.trim() ?: ""
                        )
                    )
                    onDone(null); return@launchSafe
                }
            }
            val newId = g.invoices.save(copy, orig.second)
            onDone(g.invoices.invoice(newId)?.number ?: copy.number)
        }

    // ══ : إعادة طلب فاتورة بيع (وظيفة P4-1 رقم 5) ══

    /**
     * تجهيز سلة إعادة الطلب من بنود فاتورة بيع: الكميات من الفاتورة والأسعار من المنتجات الحالية.
     * تُحال عبر ReorderBus (حالة معلقة) ثم تُستهلك في PosScreen — بلا كسر التنقل.
     */
    fun prepareReorder(inv: Invoice, onDone: (Boolean) -> Unit = {}) = launchSafe {
        if (!inv.isSale || inv.status == 3) { onDone(false); return@launchSafe }
        val items = g.invoices.items(inv.id)
        // خريطة المنتجات: من القائمة المحمّلة + جلب المعلّق للمنتجات الناقصة (مؤرشفة مثلاً)
        val pmap = HashMap<Long, Product>(products.value.size)
        for (p in products.value) pmap[p.id] = p
        for (it in items) {
            val pid = it.productId ?: continue
            if (pid !in pmap) g.inventory.product(pid)?.let { p -> pmap[pid] = p }
        }
        val lines = com.superbiz.app.domain.PosCart.linesFromInvoiceItems(items) { pid -> pmap[pid] }
        if (lines.isEmpty()) { onDone(false); return@launchSafe }
        com.superbiz.app.domain.ReorderBus.post(lines)
        onDone(true)
    }

    suspend fun partyOf(id: Long): Party? = g.ledger.party(id)
    suspend fun itemsOf(id: Long): List<InvoiceItem> = g.invoices.items(id)
}

// ═════════ المخزون ═════════
class InventoryVM(app: Application) : AndroidViewModel(app) {
    private val g = AppGraph.from(app)

    val products = g.inventory.products()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val search = MutableStateFlow("")
    val showLowOnly = MutableStateFlow(false)
    // [P5-H10 إصلاح]: رقاقة «المؤرشفة» — عند تفعيلها تعرض القائمة المؤرشف فقط
    // (كان المؤرشف غير مرئي في أي واجهة ولا سبيل لاسترجاعه)
    val showArchived = MutableStateFlow(false)

    val productsAll = g.inventory.productsIncludingArchived()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filtered = kotlinx.coroutines.flow.combine(productsAll, search, showLowOnly, showArchived) { list, q, low, arch ->
        var r = if (arch) list.filter { it.archived } else list.filter { !it.archived }
        if (q.isNotBlank()) {
            r = r.filter { it.name.contains(q, true) || it.sku.contains(q, true) || it.barcode.contains(q) }
            // بحث متسامح مطبعياً — عند فشل المطابقة الحرفية: تطبيع عربي + تشابه Levenshtein
            if (r.isEmpty() && q.length >= 3) {
                val nq = com.superbiz.app.domain.analytics.arabicNormalize(q)
                r = list.map { p -> p to com.superbiz.app.domain.analytics.similarity(com.superbiz.app.domain.analytics.arabicNormalize(p.name), nq) }
                    .filter { it.second > 0.6 }.sortedByDescending { it.second }.take(8).map { it.first }
            }
        }
        if (low && !arch) r = r.filter { it.isLow }
        r
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** بحث منتج بالباركود الممسوح كاميراً */
    fun findByBarcode(code: String, onResult: (Product?) -> Unit) = launchSafe {
        onResult(if (code.isBlank()) null else g.inventory.byBarcode(code))
    }

    fun saveProduct(
        id: Long, name: String, sku: String, barcode: String, unit: String,
        cost: Double, price: Double, stock: Double, reorder: Double, category: String,
        // [P5-H10 إصلاح]: null = حفظ الحالة القائمة (توافق كامل مع المستدعين الحاليين)
        archived: Boolean? = null
    ) = launchSafe {
        // رفض جرد غير منتهٍ أو سالب من المحرر قبل لمس قاعدة البيانات
        require(stock.isFinite() && stock >= 0.0) { "stock must be finite >= 0" }
        // التكلفة والسعر كانت تمر كما هما — منتج بسعر سالب/لانهائي يكسر
        // السلة في نقاط البيع ويفسد قيمة المخزون وتحليلات الهامش
        require(cost.isFinite() && cost >= 0.0) { "cost must be finite >= 0" }
        require(price.isFinite() && price >= 0.0) { "price must be finite >= 0" }
        require(reorder.isFinite() && reorder >= 0.0) { "reorder level must be finite >= 0" }
        val existing = if (id > 0) g.inventory.product(id) else null
        // المنتج الجديد يُدرَج بجرد 0 ثم تُسجَّل الحركة الافتتاحية مرة واحدة —
        // كان الرصيد يُخزَّن في صف المنتج ثم تضيفه moveStock مرة ثانية فيُضاعَف المخزون
        val p = Product(
            id = id, name = name.trim(), sku = sku.trim(), barcode = barcode.trim(),
            unit = unit.ifBlank { "قطعة" },
            // [P33-P8] التكلفة/السعر تدخلان ريالاً من المحرر → قروش عند الحدود الوحيدة (Money)
            costPrice = com.superbiz.app.util.Money.toPiasters(cost),
            salePrice = com.superbiz.app.util.Money.toPiasters(price),
            // التعديل لم يعد يكتب الجرد بصمت — فرق الجرد يمر عبر ADJUST موثق
            stockQty = if (existing == null) 0.0 else existing.stockQty,
            // [P20-FIX agent6]: كان إعداد «حدّ المخزون الافتراضي» ميتاً (يُكتب ولا يُقرأ أبداً) —
            // منتج جديد بلا حدّ صريح يُبذَّر من الإعداد (منتج قائم بحدّ 0 = بلا حدّ، كما هو)
            reorderLevel = if (reorder > 0) reorder
                else if (existing == null) com.superbiz.app.core.AppPrefs.defaultLowStockQty.toDouble()
                else 0.0,
            category = category.trim(),
            // حفظ بيانات التعديل — كان التعديل يصفّر تاريخ الإنشاء ويلغي الأرشفة بلا قصد
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            // [P5-H10 إصلاح]: الحالة تُمرّر من المحرر عند تغييرها، وإلا حُفظت القائمة
            archived = archived ?: existing?.archived ?: false
        )
        val newId = g.inventory.saveProduct(p)
        if (existing == null && stock > 0) {
            // المخزون الافتتاحي يُقيَّد الآن (مخزون/رأس مال) — كان بلا
            // قيد فيتناقض قيمة المخزون الفعلية مع دفتر الحساب 1200
            g.inventory.moveStock(p.copy(id = newId), stock, "ADJUST", System.currentTimeMillis(), "مخزون افتتاحي", postJournal = true)
        } else if (existing != null) {
            // أي فرق جرد يدخل من المحرر يُسجَّل حركة تسوية حقيقية بدل
            // الكتابة الصامتة على stockQty (كانت تفسد تدقيق الحركات وسقف POS)
            // [P33-P8] فرق الجرد كمية لا مبلغ — التقريب لخانتين مقبول للكميات فقط
            val delta = com.superbiz.app.util.Money.round2(stock - existing.stockQty)
            if (delta != 0.0) {
                // تسوية الجرد تُقيَّد (رأس مال/عجز جرد) حتى يتطابق الدفتر
                g.inventory.moveStock(p.copy(id = newId), delta, "ADJUST", System.currentTimeMillis(), "تسوية جرد من المحرر", postJournal = true)
            }
        }
    }

    fun deleteProduct(id: Long) = launchSafe {
        // [H1-4][v13] حذف نهائي لمنتج — باب المالك وحده (مصفوفة §3 سطر 5)
        com.superbiz.app.domain.rbac.RoleGate.require(
            com.superbiz.app.domain.rbac.SessionState.effective(),
            com.superbiz.app.domain.rbac.Op.HARD_DELETE
        )
        g.inventory.deleteProduct(id)
    }

    // [P5-H10 إصلاح]: أرشفة/استرجاع صريح — كان العلم بلا أي واجهة تعيّنه أو تلغيه
    fun setArchived(id: Long, value: Boolean) = launchSafe { g.inventory.setArchived(id, value) }

    fun move(pid: Long, qty: Double, reason: String) = launchSafe {
        val p = g.inventory.product(pid) ?: return@launchSafe
        // البيع اليدوي والتسويات تُقيَّد أيضاً — لا حركة مخزون بلا مقابل دفتري
        g.inventory.moveStock(p, qty, reason, System.currentTimeMillis(), reason, postJournal = true)
    }

    suspend fun nextBarcode(): String = BarcodeGen.nextInternal(products.value.size + 1L + System.currentTimeMillis() % 1000)

    /** توليد باركود داخلي جديد وإرجاعه للواجهة */
    fun launchGen(onResult: (String) -> Unit) = launchSafe {
        onResult(nextBarcode())
    }

    suspend fun movesOf(pid: Long) = try {
        g.inventory.moves(pid).first()
    } catch (e: Exception) { emptyList<com.superbiz.app.data.db.StockMove>() }

    // ══ : التحليل الذكي — ABC + أيام التغطية + اقتراح إعادة الطلب ══

    /** الهامش المستهدف من الإعدادات — يُبرَز في مستشار التسعير */
    val targetMargin = MutableStateFlow(30.0)

    init {
        launchSafe {
            targetMargin.value = try { g.settings.snapshot().defaultTargetMargin } catch (e: Exception) { 30.0 }
        }
    }

    /** تعيين الهامش المستهدف من مستشار التسعير — يُحفظ في الإعدادات ويُطبّق فوراً */
    fun setDefaultTargetMargin(v: Double) = launchSafe {
        g.settings.setDefaultTargetMargin(v)
        targetMargin.value = v
    }

    // كلفة أمر الشراء من الإعدادات — مدخل EOQ الحقيقي الوحيد الخارجي
    val eoqOrderCost = MutableStateFlow(25.0)

    init {
        launchSafe {
            eoqOrderCost.value = try { g.settings.snapshot().eoqOrderCost } catch (e: Exception) { 25.0 }
        }
    }

    fun setEoqOrderCost(v: Double) = launchSafe {
        g.settings.setEoqOrderCost(v)
        eoqOrderCost.value = v
    }

    data class ProductInsight(
        val product: Product,
        val sold30: Double,        // كمية مبيعة في 30 يوماً (من الفواتير الحقيقية)
        val revenue30: Double,     // قيمة المبيعات — أساس تصنيف ABC
        val abc: Char,             // A أهم 80% · B حتى 95% · C الباقي
        val coverDays: Int,        // أيام التغطية بالمخزون الحالي
        val reorderQty: Double,    // كمية مقترحة للشراء (0 = لا حاجة)
        // الطلب الاقتصادي ومخزون الأمان وراية الراكد — خوارزميات FinMath
        val eoq: Double,           // كمية الطلب الاقتصادية (0 = لا شراء مجدي)
        val safetyStock: Double,   // مخزون الأمان بمعامل خدمة 95%
        val dead: Boolean          // راكد: بلا مبيعات 30 يوماً وبه مخزون
    )

    private val _insights = MutableStateFlow<List<ProductInsight>>(emptyList())
    val insights: StateFlow<List<ProductInsight>> = _insights

    private val _insightsLoading = MutableStateFlow(false)
    val insightsLoading: StateFlow<Boolean> = _insightsLoading

    /** تحميل التحليل: مبيعات 30 يوماً من قاعدة البيانات ثم خوارزميات BizMath + FinMath */
    fun loadInsights() = launchSafe {
        _insightsLoading.value = true
        try {
            val sold = g.reports.productSoldQty(30)
            val list = products.value
            // [P33-P8] الإيراد = كمية × سعر القروش → قروش ثم ريال لعرض/تصنيف ABC (التصنيف نسبي)
            val revenues = list.map { com.superbiz.app.util.Money.fromPiasters(Math.round((sold[it.id] ?: 0.0) * it.salePrice)) }
            val classes = com.superbiz.app.domain.analytics.abcClassify(revenues)
            val orderCost = eoqOrderCost.value
            val now = System.currentTimeMillis()
            _insights.value = list.mapIndexed { i, p ->
                val s = sold[p.id] ?: 0.0
                val daily = s / 30.0
                // نقطة إعادة الطلب: متوسط 7 يوماً توريد + مخزون أمان بمعامل تغيّر 0.5
                val rp = if (daily > 0) com.superbiz.app.domain.analytics.reorderPoint(daily, 7.0, daily * 0.5) else 0.0
                val suggest = if (daily > 0 && p.stockQty < rp)
                    com.superbiz.app.domain.algo.round2(rp + daily * 14 - p.stockQty) // تغطية أسبوعين فوق النقطة
                else 0.0
                // EOQ بمعدل حمل سنوي 20% من كلفة الوحدة — تقريب صناعي شائع بغياب بيانات حمل حقيقية
                // [P33-P8] كلفة الوحدة قروش — كلفة الطلب تُحوَّل قروشاً كي تكون النسبة S/H بلا وحدة كما كانت
                val holdingCost = p.costPrice * 0.20
                val eoq = if (daily > 0 && holdingCost > 0)
                    com.superbiz.app.domain.algo.FinMath.eoq(daily * 365.0, com.superbiz.app.util.Money.toPiasters(orderCost).toDouble(), holdingCost) else 0.0
                val safety = if (daily > 0)
                    com.superbiz.app.domain.algo.FinMath.safetyStock(daily, daily * 0.5, 7.0) else 0.0
                val dead = s <= 0.0 && p.stockQty > 0 && (now - p.createdAt) > 60L * 86_400_000L
                ProductInsight(
                    product = p,
                    sold30 = com.superbiz.app.domain.algo.round2(s),
                    revenue30 = com.superbiz.app.domain.algo.round2(revenues[i]), // [P33-P8] ريال (مُحوَّل من قروش)
                    abc = classes.getOrElse(i) { 'C' },
                    coverDays = com.superbiz.app.domain.analytics.daysOfCover(p.stockQty, daily),
                    reorderQty = suggest,
                    eoq = com.superbiz.app.domain.algo.round2(eoq),
                    safetyStock = com.superbiz.app.domain.algo.round2(safety),
                    dead = dead
                )
            }.sortedByDescending { it.revenue30 }
        } catch (e: Exception) {
            android.util.Log.e("SuperBizVM", "insights failed", e)
            _insights.value = emptyList()
        } finally {
            _insightsLoading.value = false
        }
    }

    // ══ : وظيفة 14 — تطبيق اقتراح إعادة الطلب بضغطة ══

    /**
     * حركة إدخال شراء ذرّية بالكمية المقترحة (بحد أدنى 1) عبر InventoryRepo الحقيقي:
     * تحديث المخزون + سجل الحركة + قيد التوريد المحاسبي في معاملة واحدة.
     * onResult(نجاح، الكمية) — الفشل صادق (لا اقتراح / منتج محذوف).
     */
    fun applyReorderSuggestion(insight: ProductInsight, onResult: (Boolean, Double) -> Unit) = launchSafe {
        val qty = com.superbiz.app.domain.ReorderApply.purchaseQty(insight.reorderQty)
        if (qty == null) { onResult(false, 0.0); return@launchSafe }
        val p = g.inventory.product(insight.product.id)
        if (p == null) { onResult(false, 0.0); return@launchSafe }
        g.inventory.moveStock(
            p, qty, "PURCHASE", System.currentTimeMillis(),
            "طلب مقترح من التحليل الذكي", postJournal = true
        )
        onResult(true, qty)
    }

    // ══ : وظيفة 20 — منتجات بلا بيع إطلاقاً ══

    private val _neverSold = MutableStateFlow<List<Product>>(emptyList())
    val neverSold: StateFlow<List<Product>> = _neverSold

    private val _neverSoldLoading = MutableStateFlow(false)
    val neverSoldLoading: StateFlow<Boolean> = _neverSoldLoading

    /** تحميل منتجات بلا أي سطر فاتورة في التاريخ كله (عند توسيع البطاقة) */
    fun loadNeverSold() {
        if (_neverSoldLoading.value) return
        _neverSoldLoading.value = true
        launchSafe(onDone = { _neverSoldLoading.value = false }) {
            _neverSold.value = g.inventory.neverSoldProducts()
        }
    }
}

// ═════════ نقطة البيع ═════════
class PosVM(app: Application) : AndroidViewModel(app) {
    private val g = AppGraph.from(app)

    val products = g.inventory.products()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val search = MutableStateFlow("")
    val cart = MutableStateFlow<List<com.superbiz.app.domain.PosCartLine>>(emptyList())
    val discount = MutableStateFlow("")
    val payMode = MutableStateFlow(0)          // 0 نقد، 1 ذمم
    val party = MutableStateFlow<Party?>(null)
    val toast = MutableStateFlow<Int?>(null)   // معرّف نص للتنبيه
    val lastSale = MutableStateFlow<Long>(0)   // معرّف فاتورة آخر عملية للبيع (لنافذة النجاح)

    // المبلغ المستلم نقديًا — تُحسب منه الباقي وفكّ الفئات في الواجهة
    val tendered = MutableStateFlow("")

    // وضع مدخل الخصم — 0 مبلغ ثابت، 1 نسبة مئوية (وظيفة P4-1 رقم 1)
    val discountMode = MutableStateFlow(com.superbiz.app.domain.InvoiceDiscount.MODE_AMOUNT)

    // ══ [P46-W1] جولة 7 — الولاء والكوبونات في ورقة الدفع ══

    /** كود الكوبون المُدخل — يُطبّق بزر صريح فيُفحص عبر بوابة LoyaltyP46 ويُخزّن مواصفته */
    val couponCode = MutableStateFlow("")

    /** الكوبون المطبّق (مواصفته لحظة القبول) — null = بلا كوبون؛ القيم الحية تُحسب بالمحرك عند كل totals */
    val appliedCoupon = MutableStateFlow<LoyaltyP46.CouponSpec?>(null)

    /** نقاط الاستبدال المطلوبة (نص متسامح) — تُقص بالمحرك على الرصيد وسقف الصافي */
    val redeemText = MutableStateFlow("")

    /** رصيد نقاط الطرف الحي (0 = بلا ولاء/زبون نقدي) — يُحمّل عند اختيار الطرف وبعد كل بيع */
    val loyaltyBalance = MutableStateFlow(0L)

    /** علم تفعيل الولاء من الإعدادات — الورقة تخفي القسم كله حين يكون معطلاً */
    val loyaltyEnabled = g.settings.settings
        .map { it.loyaltyEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** معلومات السلة المعلّقة المحفوظة في DataStore: (عدد الأصناف، المبلغ الإجمالي) أو null */
    data class HeldCartInfo(val lineCount: Int, val total: Double, val payMode: Int)

    val heldCart = g.settings.settings
        .map { s -> s.posHeldCart?.let { parseHeldCart(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null as HeldCartInfo?)

    /**
     * فك JSON السلة المعلّقة — بلا اعتماد على مكتبات خارجية (org.json مدمجة في أندرويد)
     * [P33-P8] أسعار السطر قروش Long في JSON الجديد (amountUnit=1)؛ سلات ما قبل P8 المخزّنة
     * ريالياً (amountUnit=0) تُقرأ ريالاً كما هي — توافق النسخ الاحتياطي، والمجموع يُعرض ريالاً Double
     */
    private fun parseHeldCart(json: String): HeldCartInfo? {
        return try {
            val obj = org.json.JSONObject(json)
            val lines = obj.optJSONArray("lines") ?: return null
            if (lines.length() == 0) return null
            val piasters = obj.optInt("amountUnit", 0) == 1
            var total = 0.0 // ريال عرض
            for (i in 0 until lines.length()) {
                val l = lines.getJSONObject(i)
                val qty = l.optDouble("qty", 0.0)
                total += if (piasters)
                    com.superbiz.app.util.Money.fromPiasters(Math.round(qty * l.optLong("price", 0L)))
                else qty * l.optDouble("price", 0.0)
            }
            HeldCartInfo(lines.length(), com.superbiz.app.domain.algo.round2(total), obj.optInt("payMode", 0))
        } catch (e: Exception) {
            android.util.Log.e("SuperBizVM", "held cart parse failed", e)
            null
        }
    }

    val filtered = kotlinx.coroutines.flow.combine(products, search) { list, q ->
        if (q.isBlank()) list
        else list.filter { it.name.contains(q, true) || it.barcode.contains(q) || it.sku.contains(q, true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val customers = g.ledger.parties()
        .map { list -> list.filter { it.isCustomer } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ══ : صفوف الأطراف الأخيرة (وظيفة P4-1 رقم 3) ══

    /**
     * آخر 5 أطراف تفاعل حقيقية: مشتقة من الفواتير (مرتبة نزولاً بالتاريخ من الـ DAO)
     * ثم التحصيلات/المدفوعات الأخيرة — بلا تكرار وبترتيب الأحدث أولاً.
     */
    val recentParties: StateFlow<List<Party>> = kotlinx.coroutines.flow.combine(
        g.invoices.invoices(), g.db.payments().recent(), g.ledger.parties()
    ) { invoices, payments, parties ->
        val ids = LinkedHashSet<Long>()
        for (inv in invoices) ids.add(inv.partyId)
        for (p in payments) p.partyId?.let { ids.add(it) }
        ids.mapNotNull { id -> parties.firstOrNull { it.id == id } }.take(5)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** اختيار طرف من الصف الأخير للسلة — النقر مرة ثانية على المختار يُلغي الاختيار */
    fun selectParty(p: Party) {
        party.value = if (party.value?.id == p.id) null else p
    }

    /** إضافة منتج للسلة — تحذير عند تجاوز المخزون المتاح */
    fun addToCart(p: Product) {
        // اللقطة الحيّة للمنتج لا اللقطة المُمرّرة من الواجهة —
        // البيع من محرر الفواتير بين عرض القائمة والنقر كان يترك stock قديماً في السطر
        val live = products.value.firstOrNull { it.id == p.id } ?: p
        val line = com.superbiz.app.domain.PosCartLine(
            productId = live.id, name = live.name,
            unitPrice = live.salePrice, costPrice = live.costPrice,
            qty = 1.0, stock = live.stockQty
        )
        cart.value = com.superbiz.app.domain.PosCart.add(cart.value, line)
        // إجراء حقيقي لا تحذير فقط — الكمية تُثبَّت عند المتاح
        // فلا يمكن أن يصبح المخزون سالباً من السلة مهما ضغط المستخدم
        // تسوية الانعكاس مع عقد PosCart الموثق والمختبَر —
        // المخزون 0 يعني «غير معروف/خدمة» ولا يُقيَّد؛ كان VM يحذف السطر فوراً برسالة
        // مضللة بينما المنتج السالب المخزون كان يُباع بحرية. السقف عند stockQty > 0 فقط.
        val inCart = cart.value.firstOrNull { it.productId == live.id }?.qty ?: 0.0
        if (live.stockQty > 0 && inCart > live.stockQty) {
            cart.value = com.superbiz.app.domain.PosCart.setQty(cart.value, live.id, live.stockQty)
            toast.value = R.string.pos_over_stock
        }
    }

    /** مسح باركود: يبحث عن المنتج ويضيفه للسلة مباشرة */
    fun addByBarcode(code: String) = launchSafe {
        if (code.isBlank()) return@launchSafe
        val found = g.inventory.byBarcode(code)
        if (found == null) toast.value = R.string.scan_not_found
        // [P20-FIX agent1]: كان toast.value = null بعد addToCart يبتلع تنبيه pos_over_stock
        // الذي يضبطه addToCart عند تثبيت الكمية على المتاح — المستخدم يمسح بلا أي تغذية راجعة
        else addToCart(found)
    }

    fun setQty(productId: Long?, qty: Double) {
        // تحديث السقوف من حالة المنتجات الحيّة قبل التثبيت —
        // سقف على لقطة مجمدة كان يسمح بمخزون سالب فعلي عند الدفع
        val stockOf: (Long) -> Double? = { pid -> products.value.firstOrNull { it.id == pid }?.stockQty }
        cart.value = com.superbiz.app.domain.PosCart.setQty(
            com.superbiz.app.domain.PosCart.refreshStock(cart.value, stockOf), productId, qty)
    }

    /**تعديل كمية سطر بمؤشره — يعمل مع سطور البيع الحر أيضاً */
    fun setQtyAt(index: Int, qty: Double) {
        val stockOf: (Long) -> Double? = { pid -> products.value.firstOrNull { it.id == pid }?.stockQty }
        cart.value = com.superbiz.app.domain.PosCart.setQtyAt(
            com.superbiz.app.domain.PosCart.refreshStock(cart.value, stockOf), index, qty)
    }

    /**إزالة سطر بمؤشره — كان زر الحذف ميتاً على سطور البيع الحر */
    fun removeLineAt(index: Int) {
        cart.value = com.superbiz.app.domain.PosCart.removeAt(cart.value, index)
    }

    fun removeLine(productId: Long?) {
        cart.value = com.superbiz.app.domain.PosCart.remove(cart.value, productId)
    }

    fun clearCart() {
        cart.value = emptyList()
        discount.value = ""
        party.value = null
        payMode.value = 0
        tendered.value = ""
    }

    // ══ : تعليق السلة واستئنافها — تُحفظ فعلياً في DataStore فتصمد أمام إغلاق التطبيق ══

    /** تعليق السلة الحالية: تُسلسَل JSON وتُخزَّن ثم تُفرَّغ السلة للزبون التالي */
    fun holdCart(onDone: (Boolean) -> Unit = {}) = launchSafe {
        val lines = cart.value
        if (lines.isEmpty()) { onDone(false); return@launchSafe }
        // [P5-H12 إصلاح]: الخانة واحدة — كان التعليق الجديد يطمس السلة المعلّقة السابقة
        // بصمت فيضيع أصنافها. الآن يُرفض بتنبيه صريح: استأنف المعلّقة أو أفرغها أولاً
        if (!g.settings.snapshot().posHeldCart.isNullOrBlank()) {
            toast.value = R.string.pos_hold_exists
            onDone(false); return@launchSafe
        }
        val arr = org.json.JSONArray()
        for (l in lines) arr.put(org.json.JSONObject()
            .put("pid", l.productId ?: 0L)
            .put("name", l.name)
            // [P33-P8] الأسعار قروش Long — علامة amountUnit تميّز سلات ما قبل P8 الريالية
            .put("price", l.unitPrice)
            .put("cost", l.costPrice)
            .put("qty", l.qty)
            .put("stock", l.stock))
        val obj = org.json.JSONObject()
            .put("lines", arr)
            .put("amountUnit", 1) // [P33-P8] 1 = الأسعار قروش
            .put("discount", discount.value)
            // وضع الخصم (مبلغ/نسبة) لم يكن يُحفظ — كانت سلة معلّقة بخصم 10%
            // تُستأنف كخصم 10 ريال (على سلة 100 ريال فرق 90 ريال في الإجمالي المحفوظ!)
            .put("discountMode", discountMode.value)
            .put("payMode", payMode.value)
            .put("partyId", party.value?.id ?: 0L)
        g.settings.setPosHeldCart(obj.toString())
        clearCart()
        onDone(true)
    }

    /** استئناف السلة المعلّقة: تُبنى الأسطر من JSON ويُسترجع الخصم ووضع الدفع والطرف */
    fun resumeHeld(onDone: (Boolean) -> Unit = {}) = launchSafe {
        val json = g.settings.snapshot().posHeldCart
        if (json.isNullOrBlank()) { onDone(false); return@launchSafe }
        // [P5-H12 إصلاح]: كان الاستئناف يستبدل سلة حيّة غير فارغة بلا تأكيد — فقدان
        // صامت لأصنافها. الآن يُرفض بتنبيه صريح: أفرغ السلة الحالية أولاً
        if (cart.value.isNotEmpty()) {
            toast.value = R.string.pos_resume_blocked
            onDone(false); return@launchSafe
        }
        try {
            val obj = org.json.JSONObject(json)
            val arr = obj.optJSONArray("lines")
            if (arr == null || arr.length() == 0) {
                g.settings.setPosHeldCart(null); onDone(false); return@launchSafe
            }
            val restored = ArrayList<com.superbiz.app.domain.PosCartLine>()
            // [P33-P8] سلات ما قبل P8 (بلا amountUnit) مخزّنة ريالياً → toPiasters عند الاستئناف؛
            // سلات P8+ أسعارها قروش تُقرأ كما هي
            val piastersJson = obj.optInt("amountUnit", 0) == 1
            fun priceOf(l: org.json.JSONObject): Long =
                if (piastersJson) l.optLong("price", 0L)
                else com.superbiz.app.util.Money.toPiasters(l.optDouble("price", 0.0))
            fun costOf(l: org.json.JSONObject): Long =
                if (piastersJson) l.optLong("cost", 0L)
                else com.superbiz.app.util.Money.toPiasters(l.optDouble("cost", 0.0))
            for (i in 0 until arr.length()) {
                val l = arr.getJSONObject(i)
                restored.add(com.superbiz.app.domain.PosCartLine(
                    productId = l.optLong("pid", 0L).takeIf { it > 0 },
                    name = l.optString("name", "؟"),
                    unitPrice = priceOf(l),
                    costPrice = costOf(l),
                    qty = l.optDouble("qty", 1.0),
                    stock = l.optDouble("stock", 0.0)
                ))
            }
            // تطهير السطور المستعادة — JSON تالف/قديم (سعر NaN، كمية ≤ 0،
            // اسم فارغ) كان يتسرب إلى السلة الحية كما هو؛ التالف يُهمل والسليم يُثبَّت
            // تطهير السطور المستعادة — JSON تالف/قديم يُهمل والسليم يُثبّت
            val sanitized = com.superbiz.app.domain.PosCart.sanitizeRestored(restored)
            // سقوف المخزون من الحيّ — سلة معلقة منذ أيام كانت تُستأنف بسقوف قديمة
            val stockOf: (Long) -> Double? = { pid -> products.value.firstOrNull { it.id == pid }?.stockQty }
            cart.value = com.superbiz.app.domain.PosCart.refreshStock(sanitized, stockOf)
            if (cart.value.isEmpty()) { g.settings.setPosHeldCart(null); onDone(false); return@launchSafe }
            discount.value = obj.optString("discount", "")
            // استعادة وضع الخصم المحفوظ (0 مبلغ / 1 نسبة) — الافتراضي مبلغ للتوافق مع السلات القديمة
            discountMode.value = obj.optInt("discountMode", com.superbiz.app.domain.InvoiceDiscount.MODE_AMOUNT)
            payMode.value = obj.optInt("payMode", 0)
            val pid = obj.optLong("partyId", 0L)
            party.value = if (pid > 0) g.ledger.party(pid) else null
            g.settings.setPosHeldCart(null)
            onDone(true)
        } catch (e: Exception) {
            android.util.Log.e("SuperBizVM", "resume held cart failed", e)
            ErrorCenter.report(e, "PosVM")
            onDone(false)
        }
    }

    fun consumeToast() { toast.value = null }

    // ══ [P46-W1] جولة 7 — دوال الولاء في نقطة البيع ══

    /** إعادة تحميل رصيد نقاط الطرف المختار — تُستدعى من الورقة عند تغيّر الطرف وبعد البيع */
    fun refreshLoyaltyBalance() = viewModelScope.launch {
        val p = party.value
        loyaltyBalance.value = if (p == null) 0L else g.loyalty.balance(p.id)
    }

    /**
     * تطبيق كوبون بالكود على أساس الصافي الحالي — البوابة الوحيدة LoyaltyP46.checkCoupon،
     * والقبول يخزّن مواصفة الكوبون (القيم الحية تُحسب بالمحرك في totals عند كل تحديث)
     * والرفض يصدر تنبيهاً موطناً صادقاً بسببه.
     */
    fun applyCoupon(baseNet: Long) = viewModelScope.launch {
        val s = g.settings.snapshot()
        if (!s.loyaltyEnabled) { toast.value = R.string.loyalty_disabled; return@launch }
        val now = System.currentTimeMillis()
        when (val chk = g.loyalty.checkCoupon(couponCode.value, now, baseNet)) {
            is LoyaltyP46.CouponCheck.Ok -> {
                appliedCoupon.value = g.loyalty.coupon(chk.couponId)?.toSpec()
                toast.value = R.string.loyalty_coupon_applied
            }
            is LoyaltyP46.CouponCheck.Rejected -> {
                appliedCoupon.value = null
                toast.value = when (chk.reason) {
                    LoyaltyP46.CouponReject.EMPTY -> R.string.coupon_err_empty
                    LoyaltyP46.CouponReject.NOT_FOUND -> R.string.coupon_err_not_found
                    LoyaltyP46.CouponReject.INACTIVE -> R.string.coupon_err_inactive
                    LoyaltyP46.CouponReject.EXPIRED -> R.string.coupon_err_expired
                    LoyaltyP46.CouponReject.EXHAUSTED -> R.string.coupon_err_exhausted
                }
            }
        }
    }

    fun clearCoupon() { appliedCoupon.value = null; couponCode.value = "" }

    /** تصفير مدخلات الولاء بعد بيع ناجح — الكوبون استُهلك والنقاط استُبدلت */
    private fun resetLoyaltyInputs() {
        appliedCoupon.value = null
        couponCode.value = ""
        redeemText.value = ""
    }

    // ══ : البيع السريع بمبلغ حر (وظيفة P4-1 رقم 2) ══

    /**
     * فاتورة نقدية فورية بلا أصناف: مبلغ + وصف حري بسطر وحيد «بيع حر»،
     * عبر نفس مسار الحفظ الحقيقي checkout() فتدخل الإحصاءات والخزنة كأي فاتورة.
     * المبلغ غير الصالح يُرفض (onResult(false)) بلا أي تسجيل.
     */
    fun quickSale(amountText: String, description: String, onResult: (Boolean) -> Unit = {}) = launchSafe {
        // [P33-P8] المبلغ النصي ريالي → قروش (نقطة التحويل الوحيدة) — parseToPiasters يتسامح مع الأرقام العربية
        val amount = com.superbiz.app.util.Money.parseToPiasters(amountText)
        if (amount <= 0L) { onResult(false); return@launchSafe }
        val label = getApplication<Application>().getString(R.string.pos_free_sale)
        cart.value = listOf(com.superbiz.app.domain.PosCart.freeSaleLine(amount, label, description.trim()))
        payMode.value = 0
        party.value = null
        discount.value = ""
        discountMode.value = com.superbiz.app.domain.InvoiceDiscount.MODE_AMOUNT
        tendered.value = ""
        // كانت onResult(true) تُطلق فوراً قبل معرفة مصير الحفظ —
        // الآن تنتظر نتيجة doCheckout الفعلية (id > 0 نجاح، وإلا فشل/رفض)
        val id = try { doCheckout() } catch (e: Exception) {
            com.superbiz.app.core.ErrorCenter.report(e, "quickSale")
            null
        }
        if (id != null && id > 0) lastSale.value = id
        onResult(id != null && id > 0)
    }

    // ══ : استهلاك إحالة «إعادة الطلب» (وظيفة P4-1 رقم 5) ══

    /** عند فتح نقطة البيع: دمج بنود الطلب المعلق في السلة الحالية ثم إخلاء الحالة */
    fun consumeReorder() {
        val pending = com.superbiz.app.domain.ReorderBus.pendingLines ?: return
        com.superbiz.app.domain.ReorderBus.clear()
        var c = cart.value
        for (l in pending) c = com.superbiz.app.domain.PosCart.add(c, l)
        // سقوف المخزون من الحيّ لا من بقايا الفاتورة المعاد طلبها
        val stockOf: (Long) -> Double? = { pid -> products.value.firstOrNull { it.id == pid }?.stockQty }
        cart.value = com.superbiz.app.domain.PosCart.refreshStock(c, stockOf)
        toast.value = R.string.pos_reorder_ready
    }

    /** (المجموع، الصافي بعد الخصم، الضريبة، الإجمالي) وفق نسبة الضريبة من الإعدادات — [P33-P8] المبالغ قروش Long */
    suspend fun totals(): com.superbiz.app.domain.PosTotals {
        val lines = cart.value
        val sub = com.superbiz.app.domain.PosCart.subtotal(lines)
        // الخصم يُفسَّر (مبلغ/نسبة) ويُرفض إن كان سالباً أو أكبر من الإجمالي
        // — بدل القبول الصامت القديم بالحصر (وظيفة P4-1 رقم 1)
        // [P33-P8] raw يبقى ريالاً Double من حقل الإدخال — الوضع المبلغ يُحوَّل داخلياً عبر Money
        val resolved = com.superbiz.app.domain.InvoiceDiscount.resolve(
            parseNum(discount.value), discountMode.value, sub
        )
        val cartDisc = resolved ?: 0L
        // [P46-W1] جولة 7 — تركيب الخصم بمحرك LoyaltyP46 الواحد: السلة + الكوبون + قيمة النقاط
        // (الاستبدال مقصوص بالرصيد وسقف الصافي) — المعاينة تطابق المحفوظ دائماً لأن
        // doCheckout يكتب أرقام totals نفسها (عقد P42)
        var couponDisc = 0L
        var couponIdUsed = 0L
        var redeemPts = 0L
        val s = g.settings.snapshot()
        if (s.loyaltyEnabled && party.value != null) {
            appliedCoupon.value?.let { spec ->
                couponIdUsed = spec.couponId
                couponDisc = LoyaltyP46.couponDiscount(
                    spec.kind, spec.amountPiasters, spec.percent,
                    (sub - cartDisc).coerceAtLeast(0L)
                )
            }
            redeemPts = com.superbiz.app.util.NumText.parseNum(redeemText.value).toLong().coerceAtLeast(0L)
            redeemPts = redeemPts.coerceAtMost(loyaltyBalance.value.coerceAtLeast(0L))
            redeemPts = redeemPts.coerceAtMost(
                LoyaltyP46.maxRedeemablePoints((sub - cartDisc - couponDisc).coerceAtLeast(0L), s.loyaltyPointValue)
            )
        }
        val redeemVal = LoyaltyP46.redeemValue(redeemPts, s.loyaltyPointValue)
        val disc = LoyaltyP46.totalDiscount(cartDisc, couponDisc, redeemVal, sub)
        val net = com.superbiz.app.domain.PosCart.netTotal(lines, disc)
        val taxRate = s.taxRate
        // [P33-P8] الضريبة قروش: Math.round(الصافي × النسبة / 100) — والإجمالي جمع صحيح تام
        val tax = Math.round(net * taxRate / 100.0)
        val total = net + tax
        // معاينة الربح الحي (null = كل البنود بلا تكلفة — يُخفى السطر)
        val profit = com.superbiz.app.domain.PosCart.cartProfit(lines, disc)
        return com.superbiz.app.domain.PosTotals(
            subtotal = sub, discount = disc, net = net,
            taxRate = taxRate, tax = tax, total = total, profit = profit,
            loyaltyRedeemPoints = redeemPts, couponId = couponIdUsed
        )
    }

    /** إنشاء/جلب طرف «زبون نقدي» وإبقاء معرّفه في الإعدادات */
    private suspend fun ensureWalkIn(): Party {
        val savedId = g.settings.snapshot().walkInPartyId
        if (savedId > 0) g.ledger.party(savedId)?.let { return it }
        val created = Party(name = getApplication<Application>().getString(R.string.pos_walk_in))
        val id = g.ledger.saveParty(created)
        g.settings.setWalkInPartyId(id)
        return created.copy(id = id)
    }

    /**
     * إتمام البيع: فاتورة بيع + حركات مخزون + قيد مزدوج تلقائي عبر InvoiceRepo.save.
     * البيع النقدي يُسجَّل مدفوعاً بالكامل عبر LedgerRepo.addPayment؛ بيع الذمم يبقى مفتوحاً على حساب العميل.
     */
    // علم انشغال يمنع النقر المزدوج على «بيع» فيُنشئ فاتورتين ويخصم المخزون مرتين
    private var checkoutBusy = false

    fun checkout(onDone: (Long) -> Unit = {}) = launchSafe(onDone = { checkoutBusy = false }) {
        // المتن مستخرج إلى doCheckout() ليسهّل على quickSale انتظار النتيجة الحقيقية
        val id = doCheckout() ?: return@launchSafe
        lastSale.value = id
        onDone(id)
    }

    /** جوهر إتمام البيع — يعيد معرّف الفاتورة أو null عند الرفض (سلة فارغة/انشغال/لا طرف) */
    private suspend fun doCheckout(): Long? {
        if (checkoutBusy) return null
        checkoutBusy = true
        // العلم كان يبقى مرفوعاً إذا أعاد doCheckout مبكراً (سلة فارغة/لا طرف)
        // أو رمى استثناءً في مسار quickSale المباشر — فيموت نقاط البيع بصمت حتى إعادة إنشاء الـVM
        try {
            val lines = cart.value
            if (lines.isEmpty()) return null
            val mode = payMode.value
            if (mode == 1 && party.value == null) { toast.value = R.string.pos_pick_party_first; return null }

            val t = totals()
            val now = System.currentTimeMillis()
            val s = g.settings.snapshot()
            // الطرف المختار من صف الأطراف الأخيرة يُستخدم للبيع النقدي أيضاً
            // (بدل زبون نقدي) — وإلا يبقى سلوك زبون نقدي كما كان
            // إزالة !! — الوصول لطرف محذوف أثناء الجلسة يسقط إلى زبون نقدي بدل انهيار البيع
            val target: Party = party.value ?: ensureWalkIn()
            // [P20-FIX agent8]: بيع آجل من POS كان dueDate = الآن ⇒ مستحق فوراً — في اليوم التالي
            // يظهر متأخراً فيفخم أعمار الذمم ودرجة الخطر و DSO لكل بيع آجل (محرر الفواتير يعطي 14 يوماً)
            val dueDate = if (mode == 1) now + 14L * 86_400_000L else now
            val inv = Invoice(
                number = g.invoices.nextNumber(true),
                partyId = target.id, type = 0,
                date = now, dueDate = dueDate,
                subtotal = t.subtotal, discount = t.discount,
                taxRate = t.taxRate, taxAmount = t.tax, total = t.total,
                costTotal = com.superbiz.app.domain.PosCart.cost(lines),
                currency = s.baseCurrency,
                note = "POS"
            )
            // خصم الفاتورة يُوزَّع قرشاً بقرش على بنودها فيُحفظ داخل كل بند
            val shares = com.superbiz.app.domain.InvoiceDiscount.distribute(lines, t.discount)
            val items = lines.mapIndexed { idx, it ->
                InvoiceItem(
                    invoiceId = 0, productId = it.productId, desc = it.name,
                    // [P33-P8] الكمية Double والسعر/الخصم قروش Long
                    qty = it.qty, unitPrice = it.unitPrice,
                    discount = shares?.getOrNull(idx) ?: 0L
                )
            }
            val id = g.invoices.save(inv, items,
                // البيع النقدي يُحصَّل داخل نفس معاملة الحفظ — كانت معاملتان
                // منفصلتان وموت العملية بينهما يحوّل البيع النقدي إلى ذمّة معلّقة
                collectCashDirection = if (mode == 0) 0 else null,
                // [P46-W1] الولاء داخل نفس المعاملة الذرّية — أرقام totals نفسها:
                // كسب على الصافي بعقد المحرك، واستبدال بالنقاط المقصوصة، واستهلاك الكوبون
                // (التحقق الحي للرصيد داخل المعاملة فلا استبدال يتجاوز الدفتر أبداً)
                loyaltyEarn = if (s.loyaltyEnabled && target.id != s.walkInPartyId)
                    com.superbiz.app.domain.LoyaltyP46.earnPoints(t.net, s.loyaltyEarnDivisor) else 0L,
                loyaltyRedeem = t.loyaltyRedeemPoints,
                couponId = t.couponId)
            if (mode == 1) {
                // بيع الآجل: يبقى مفتوحاً على حساب العميل (سلوك مقصود)
                Unit
            }
            resetLoyaltyInputs()
            refreshLoyaltyBalance()
            clearCart()
            return id
        } finally {
            checkoutBusy = false
        }
    }

    fun closeSuccess() { lastSale.value = 0 }

    // قراءات لنافذة النجاح والمشاركة
    suspend fun invoiceOf(id: Long): Invoice? = g.invoices.invoice(id)
    suspend fun fullInvoice(id: Long) = g.invoices.fullInvoice(id)
    suspend fun partyOf(id: Long): Party? = g.ledger.party(id)
}

// ═════════ الشيكات ═════════
class ChecksVM(app: Application) : AndroidViewModel(app) {
    private val g = AppGraph.from(app)

    val checks = g.checks.checks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val parties = g.ledger.parties()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val statusFilter = MutableStateFlow(-1)

    // رقاقة «الأرشيف» (بديل R2) + رقاقة «هذا الأسبوع» (وظيفة 25) — الترشيح في VM
    val FILTER_ARCHIVE = -2

    private val archive = com.superbiz.app.data.repo.ChecksArchiveStore(app)
    val archivedIds: StateFlow<Set<Long>> = archive.ids
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val weekOnly = MutableStateFlow(false)

    val filtered = kotlinx.coroutines.flow.combine(
        checks, statusFilter, weekOnly, archivedIds
    ) { list, f, w, ar ->
        val pool = if (f == FILTER_ARCHIVE) {
            // عرض الأرشيف: المؤرشفة فقط (القائمة النشطة تُعرض بلاها في بقية الأوضاع)
            list.filter { it.id in ar }
        } else {
            val active = list.filterNot { it.id in ar }
            if (f < 0) active else active.filter { it.status == f }
        }
        if (w && f != FILTER_ARCHIVE) pool.filter { com.superbiz.app.domain.ChecksWeek.predicate(System.currentTimeMillis())(it) }
        else pool
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // عدّادا «هذا الأسبوع» (وظيفة 25) — (مستحق خلال 7 أيام، متأخر غير مسدد) من الشيكات الحقيقية
    val weekStats: StateFlow<Pair<Int, Int>> = checks
        .map { list -> com.superbiz.app.domain.ChecksWeek.weekStats(list, System.currentTimeMillis()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0 to 0)

    /**
 * : بديل الاحتياطي R2 (مكان وظيفة 22 المكافئة قائماً) —
 * أرشفة كل الشيكات المحصّلة (status = 2) دفعة واحدة في DataStore،
 * بلا أي تعديل مخطط ولا حذف. يعيد عبر onDone عدد ما أُرشِف فعلاً (0 = لا شيء).
*/
    fun archiveSettled(onDone: (Int) -> Unit) = launchSafe {
        val targets = checks.value
            .filter { it.status == 2 && it.id !in archivedIds.value }
            .map { it.id }
        if (targets.isEmpty()) { onDone(0); return@launchSafe }
        archive.archiveAll(targets)
        onDone(targets.size)
    }

    /** إعادة شيك مؤرشف إلى القائمة النشطة */
    fun restore(id: Long) = launchSafe { archive.restore(id) }

    fun save(number: String, party: Party, bank: String, amount: Double, issue: Long, due: Long, direction: Int) =
        launchSafe {
            // تحقق حقيقي قبل الحفظ — كان شيك بمبلغ صفر/سالب أو
            // باستحقاق سابق للإصدار يُقبل ويُشوّه تقارير الاستحقاق
            // [P33-P8] المبلغ يدخل ريالاً من المحرر → قروش عند الحدود الوحيدة (Money)؛
            // القيم غير المنتهية تُحوَّل إلى 0 فتُرفض بمقارنة صحيحة تامة
            val amountP = com.superbiz.app.util.Money.toPiasters(amount)
            val errMsg = when {
                amountP <= 0L -> getApplication<Application>().getString(com.superbiz.app.R.string.err_amount_positive)
                due < issue -> getApplication<Application>().getString(com.superbiz.app.R.string.err_check_due_before_issue)
                number.isBlank() -> getApplication<Application>().getString(com.superbiz.app.R.string.err_check_number_blank)
                else -> null
            }
            if (errMsg != null) {
                com.superbiz.app.core.ErrorCenter.warn("ChecksVM", "rejected check save", errMsg)
                return@launchSafe
            }
            g.checks.save(CheckEntity(number = number.trim(), partyId = party.id, bank = bank.trim(),
                amount = amountP, issueDate = issue, dueDate = due, direction = direction))
        }

    fun setStatus(c: CheckEntity, st: Int) = launchSafe { g.checks.setStatus(c, st) }
    fun delete(id: Long) = launchSafe { g.checks.delete(id) }

    // بديل موطَّن من الموارد بدل "؟" المضمّنة
    suspend fun partyName(pid: Long): String =
        g.ledger.party(pid)?.name ?: getApplication<Application>().getString(com.superbiz.app.R.string.unknown_party)

    /**خريطة أسماء الأطراف لبنّاء ICS والمشاركة (وظيفة 24) */
    suspend fun partyNames(): Map<Long, String> = try {
        g.ledger.parties().first().associate { it.id to it.name }
    } catch (e: Exception) { emptyMap() }
}

// ═════════ التقارير ═════════
class ReportsVM(app: Application) : AndroidViewModel(app) {
    private val g = AppGraph.from(app)

    val periodDays = MutableStateFlow(30)

    /**ملخص الاتجاه — اتجاه السلسلة وقوة التفسير وتوقع نهاية الشهر الحالي */
    data class TrendInfo(
        val direction: Int,             // -1 نازل / 0 مستقر / 1 صاعد
        val r2: Double,
        val projectedMonthEnd: Double   // 0 = لا توقع كافٍ
    )

    data class ReportsData(
        val income: com.superbiz.app.domain.IncomeStatement? = null,
        // [P33-P8] سلاسل/مجاميع المبالغ قروش Long — النسب والعروض المشتقة تبقى Double
        val salesSeries: List<Triple<Long, Long, Long>> = emptyList(),
        val cashflow: List<Triple<Long, Long, Long>> = emptyList(),
        val topCustomers: List<Pair<String, Long>> = emptyList(),
        val topProducts: List<com.superbiz.app.data.repo.ReportsRepo.ProductSales> = emptyList(),
        val aging: List<Long> = emptyList(),
        val trial: List<TrialRow> = emptyList(),
        val cash: Long = 0L,
        // تحليلات خوارزمية حقيقية
        val ma7: List<Double> = emptyList(),           // متوسط متحرك 7 أيام للمبيعات
        val trend: TrendInfo? = null,                  // اتجاه المبيعات + توقع نهاية الشهر
        val seasonality: List<Double> = emptyList(),   // 12 مؤشراً موسمياً (حول 1.0)
        val seasonMonthStarts: List<Long> = emptyList(), // بداية كل شهر لأسماء الشهور
        val corrExpSales: Double = 0.0,                // بيرسون: مصروفات مقابل مبيعات
        val giniCustomers: Double = 0.0,               // تركّز الإيراد بين العملاء (0..1)
        val top3Share: Double = 0.0,                   // حصة أفضل 3 عملاء (0..1)
        val breakEvenRevenue: Double = 0.0,            // إيراد التعادل للفترة (-1 = مستحيل)
        // المدرج النقدي + ساعات الذروة + الضريبة + التنعيم والإسقاط
        val burnRate: Double = 0.0,                    // معدل الحرق اليومي (سالب = حرق)
        val runwayDays: Double = -1.0,                 // أيام السيولة المتبقية (-1 = لا حرق)
        val hourProfile: IntArray = IntArray(0),       // توزيع البيع على 24 ساعة
        val dowProfile: IntArray = IntArray(0),        // توزيع البيع على أيام الأسبوع (0=الأحد)
        val vatRows: List<Triple<Double, Long, Long>> = emptyList(), // (نسبة، صافي، ضريبة) [P33-P8]
        // [P38-Z3] تقرير الضريبة الدوري — أساس الإقرار (مخرجات/مدخلات/صافي/صفرية)
        val vatReturn: com.superbiz.app.domain.ZatcaReturnP38.Report? = null,
        // [P43-D1] جولة 4 — الدفتر التفصيلي (تصدير عميق): صفوف البنود بأعمدة الضريبة v11
        // — null = فشل القراءة فتختفي البطاقة (نمط vatReturn الصادق: لا حالة فارغة كاذبة)
        val deep: com.superbiz.app.domain.DeepExportP43.RegisterTotalsPair? = null,
        val ema7: List<Double> = emptyList(),          // تنعيم أسي α=0.3 لمبيعات الفترة
        val holt7: List<Double> = emptyList(),         // إسقاط Holt لـ7 أيام قادمة (فارغ = بيانات غير كافية)
        val wma7: List<Double> = emptyList(),          // متوسط متحرك مرجّح 7 أيام (نافذة خطية)
        val pareto80: Int = 0,                         // عدد العملاء الذين يغطّون 80% من الإيراد (باريتو)
        // مقارنة الشهرين + أعلى الربحية + ترشيح التصنيف (وظائف 16، 17، 19)
        val monthCompare: Pair<com.superbiz.app.domain.PeriodStats, com.superbiz.app.domain.PeriodStats>? = null,
        val topProfit: List<com.superbiz.app.domain.ProfitRow> = emptyList(),
        val catStats: com.superbiz.app.domain.PeriodStats? = null, // غير null = تصنيف مختار
        val catName: String? = null
    )

    private val refreshKey = MutableStateFlow(0)
    fun refresh() { refreshKey.value++ }

    // ══ : وظيفة 19 — فلتر التصنيف في التقارير ══
    // التصنيفات على مستوى المنتج فقط (Product.category) — الترشيح على بنود الفواتير ذات منتجات التصنيف
    val categoryFilter = MutableStateFlow("")

    val categories: StateFlow<List<String>> = g.inventory.products()
        .map { list -> list.map { it.category }.filter { it.isNotBlank() }.distinct().sorted() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val data: StateFlow<ReportsData> = kotlinx.coroutines.flow.combine(periodDays, refreshKey, categoryFilter) { d, _, cat -> d to cat }
        .flatMapLatest { (days, cat) ->
            kotlinx.coroutines.flow.flow {
                val from = System.currentTimeMillis() - days * 86_400_000L
                val to = System.currentTimeMillis()
                val inc = g.reports.incomeStatement(from, to)
                val salesSeries = g.reports.salesExpensesSeries(days)
                // [P33-P8] خوارزميات السلاسل القائمة (انحدار/تنعيم/ارتباط) تبقى بواجهة ريالية —
                // تُغذّى بقيم ريالية محوّلة عبر Money من سلسلة القروش
                val salesVals = salesSeries.map { com.superbiz.app.util.Money.fromPiasters(it.second) }
                val expVals = salesSeries.map { com.superbiz.app.util.Money.fromPiasters(it.third) }

                // اتجاه المبيعات + توقع نهاية الشهر (ميل الانحدار × أيام متبقية)
                val trend = if (salesVals.size >= 5) {
                    val x = List(salesVals.size) { it + 1.0 }
                    val (slope, _, r2) = com.superbiz.app.domain.analytics.linearRegression(x, salesVals)
                    val (dayOfMonth, daysInMonth) = com.superbiz.app.domain.algo.TimeMath.monthProgress(to)
                    // [P33-P8] مبيعات الشهر قروش — تُحوَّل ريالاً قبل الجمع مع ميل الانحدار الريالي
                    val salesSoFar = try { com.superbiz.app.util.Money.fromPiasters(g.reports.monthTotals().first) } catch (e: Exception) { 0.0 }
                    val daysLeft = (daysInMonth - dayOfMonth).coerceAtLeast(0)
                    val projected = if (r2 >= 0.2 && slope > 0)
                        com.superbiz.app.domain.algo.round2(salesSoFar + slope * daysLeft) else 0.0
                    TrendInfo(com.superbiz.app.domain.algo.trendDirection(salesVals), r2, projected)
                } else null

                // الموسمية — 12 شهراً مع إسقاط الشهر الحالي الجزئي بنسبة التقدم
                // [P33-P8] إجماليات الشهور قروش — الإسقاط بالقروش ثم ريال (قسمة عائمة لا صحيحة)
                val months12 = try { g.reports.monthlySalesTotals12() } catch (e: Exception) { emptyList<Pair<Long, Long>>() }
                val (dayNow, daysNow) = com.superbiz.app.domain.algo.TimeMath.monthProgress(to)
                val projected12 = months12.mapIndexed { i, (start, totalP) ->
                    if (i == 11 && dayNow > 0)
                        com.superbiz.app.util.Money.fromPiasters(Math.round(totalP * daysNow.toDouble() / dayNow))
                    else com.superbiz.app.util.Money.fromPiasters(totalP)
                }
                val seasonality = try {
                    com.superbiz.app.domain.analytics.seasonalMonthlyIndex(projected12)
                } catch (e: Exception) { emptyList() }

                // تركّز العملاء + ارتباط المصروفات بالمبيعات + التعادل
                // [P33-P8] إيرادات العملاء قروش — تُحوَّل ريالاً لخوارزميات التركّز القائمة
                val custTotals = try { g.reports.customerRevenueTotals(from, to) } catch (e: Exception) { emptyList<Long>() }
                val custTotalsRiyal = custTotals.map { com.superbiz.app.util.Money.fromPiasters(it) }
                val beRev = inc?.let {
                    // [P33-P8] الإيراد/التكلفة/المصروفات قروش — الهامش نسبة من القسمة العائمة
                    val marginRatio = if (it.revenue > 0L) 1.0 - it.cogs.toDouble() / it.revenue.toDouble() else 0.0
                    if (marginRatio <= 1e-9) -1.0
                    else com.superbiz.app.domain.algo.round2(com.superbiz.app.util.Money.fromPiasters(it.expenses) / marginRatio)
                } ?: 0.0

                // المدرج النقدي — حرق يومي من سلسلة التدفق + أيام السيولة المتبقية
                val cashSeries = g.reports.cashflowSeries(days)
                val cashNow = g.reports.cashBalance()
                // [P33-P8] صافي التدفق اليومي قروش — يُحوَّل ريالاً لحدود FinMath القائمة
                val burn = com.superbiz.app.domain.algo.FinMath.burnRate(
                    cashSeries.map { com.superbiz.app.util.Money.fromPiasters(it.second - it.third) }, 30)
                val runway = com.superbiz.app.domain.algo.FinMath.runwayDays(
                    com.superbiz.app.util.Money.fromPiasters(cashNow), -burn) // burn سالب = صرف أعلى من قبض
                // ساعات الذروة وأيام الأسبوع وملخص الضريبة والتنعيم والإسقاط
                val hours = try { g.reports.saleHourCounts(days) } catch (e: Exception) { IntArray(0) }
                val dow = try {
                    com.superbiz.app.domain.algo.SeriesMath.dayOfWeekProfile(g.reports.saleTimestamps(days))
                } catch (e: Exception) { IntArray(0) }
                // [P33-P8] vatByRate أعادت (نسبة Double، صافي قروش، ضريبة قروش) — نفس النوع في fallback
                val vat = try { g.reports.vatByRate(from, to) } catch (e: Exception) { emptyList<Triple<Double, Long, Long>>() }
                // [P38-Z3] تقرير الضريبة الدوري — فشل القراءة يتركه null فتُختفي البطاقة (لا حالة فارغة كاذبة)
                val vatReturn = try { g.reports.zatcaReturn(from, to) } catch (e: Exception) { null }
                // [P43-D1] الدفتر التفصيلي — نفس النافذة ونمط الصدق نفسه
                val deep = try { g.reports.deepRegister(from, to) } catch (e: Exception) { null }
                val ema = com.superbiz.app.domain.algo.SeriesMath.ema(salesVals, 0.3)
                val wma7 = com.superbiz.app.domain.algo.SeriesMath.wma(salesVals, 7)
                val holt = if (salesVals.size >= 5)
                    com.superbiz.app.domain.algo.SeriesMath.holtForecast(salesVals.takeLast(30), horizon = 7)
                else emptyList()
                // باريتو 80/20 حقيقي: أقل عدد من العملاء يغطي 80% من إيراد الفترة
                val pareto80 = com.superbiz.app.domain.algo.SeriesMath.paretoCount(custTotalsRiyal, 0.8)

                // ══ : وظيفة 16 — مقارنة الشهرين، ووظيفة 17 — أعلى الربحية ══
                // بتصنيف مختار تُحسب على أساس بنود منتجاته (وظيفة 19) لتبقى المقارنة متسقة
                val catName = cat.takeIf { it.isNotBlank() }
                val months = try { g.reports.thisAndLastMonthStats(catName) } catch (e: Exception) { null }
                val topProfit = try { g.reports.topProfitableProducts(from, to, catName) } catch (e: Exception) { emptyList<com.superbiz.app.domain.ProfitRow>() }
                val catStats = if (catName != null)
                    try { g.reports.categorySalesStats(from, to, catName) } catch (e: Exception) { null }
                else null

                emit(ReportsData(
                    income = inc,
                    salesSeries = salesSeries,
                    cashflow = cashSeries,
                    topCustomers = g.reports.topCustomers(from, to),
                    topProducts = g.reports.topProducts(from, to),
                    aging = g.reports.agingBuckets(),
                    trial = g.reports.trialBalance(),
                    cash = cashNow,
                    ma7 = com.superbiz.app.domain.analytics.movingAverage(salesVals, 7),
                    trend = trend,
                    seasonality = seasonality,
                    seasonMonthStarts = months12.map { it.first },
                    corrExpSales = try {
                        com.superbiz.app.domain.algo.pearson(salesVals, expVals)
                    } catch (e: Exception) { 0.0 },
                    giniCustomers = com.superbiz.app.domain.algo.gini(custTotalsRiyal),
                    top3Share = com.superbiz.app.domain.algo.topShare(custTotalsRiyal, 3),
                    breakEvenRevenue = beRev,
                    burnRate = com.superbiz.app.domain.algo.round2(burn),
                    runwayDays = runway,
                    hourProfile = hours,
                    dowProfile = dow,
                    vatRows = vat,
                    vatReturn = vatReturn,
                    deep = deep,
                    ema7 = ema,
                    holt7 = holt,
                    wma7 = wma7,
                    pareto80 = pareto80,
                    monthCompare = months,
                    topProfit = topProfit,
                    catStats = catStats,
                    catName = catName
                ))
            }
                // فشل طبقة التقارير يعيد حالة فارغة بدل موت جامع stateIn وإغلاق التطبيق
                .catch { e ->
                    android.util.Log.e("SuperBizVM", "reports data failed", e)
                    emit(ReportsData())
                }
                // أثقل خط أنابيب في التطبيق كان يعمل على جامع Main —
                // نفس علاج DataHealthVM (R12-C20): flowOn يرفع كل البناء فوق Default
                .flowOn(kotlinx.coroutines.Dispatchers.Default)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReportsData())
}

// ═════════ المركز الآلي ═════════
class AutoVM(app: Application) : AndroidViewModel(app) {
    private val g = AppGraph.from(app)

    val rules = g.db.rules().all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    data class AutoData(
        val forecast: ForecastResult? = null,
        val dueSoon: List<Pair<Party, Long>> = emptyList(),  // أطراف مستحقة قريباً — [P33-P8] قروش
        val queued: List<String> = emptyList()
    )

    private val refreshKey = MutableStateFlow(0)
    fun refresh() { refreshKey.value++ }

    val data: StateFlow<AutoData> = refreshKey.flatMapLatest {
        kotlinx.coroutines.flow.flow { emit(load()) }
            // فشل التحميل يعيد حالة فارغة بدل موت جامع stateIn وإغلاق التطبيق
            .catch { e ->
                android.util.Log.e("SuperBizVM", "auto data failed", e)
                emit(AutoData())
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AutoData())

    private suspend fun load(): AutoData = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        // [P20-FIX agent9]: مسح فواتير كامل + تنبؤ CPU + حلقة N+1 للأطراف كانت على Main — نفس
        // صنف R14-F10/R12-C20 المُصلَح في كل مكان آخر وأُغفل هنا
        val now = System.currentTimeMillis()
        val forecast = try { g.reports.forecast(g.reports.openSaleInvoices()) } catch (e: Exception) { null }
        // فواتير مستحقة خلال أيام القاعدة أو متأخرة
        val dueRule = g.db.rules().byKind("DUE_REMIND")
        val days = dueRule?.daysBefore ?: 3
        // [P33-P8] المفتوح قروش — مساواة صحيحة تامة بدل عتبة 0.004
        val open = g.db.invoices().allOnce().filter { it.isSale && it.status < 3 && it.open > 0L && it.dueDate - days * 86_400_000L <= now }
        val byParty = open.groupBy { it.partyId }.map { (pid, list) ->
            // [P20-FIX agent9/agent6]: "؟" صلبة — استخدم نص الطرف غير المعروف الموحّد
            (g.ledger.party(pid) ?: Party(name = g.context.getString(com.superbiz.app.R.string.unknown_party))) to list.sumOf { it.open }
        }
        // رمز العملة الأساسية من قاعدة البيانات — لا عملة صلبة في الكود
        // إصلاح خطأ ترجمة: try بلا catch/finally غير صالح نحوياً في Kotlin
        val symbol = runCatching { g.db.currencies().allOnce().firstOrNull { it.isBase }?.symbol }
            .getOrNull().orEmpty().ifBlank { "ر.س" }
        // [P33-P8] المبالغ قروش — العرض عبر formatP
        val queued = byParty.map { (p, amt) -> "${p.name}: ${com.superbiz.app.util.Money.formatP(amt, symbol, false)}" }
        AutoData(forecast, byParty, queued)
    }

    fun toggleRule(rule: Rule, enabled: Boolean) = launchSafe {
        g.db.rules().upsert(rule.copy(enabled = enabled))
        com.superbiz.app.work.AutomationWorker.schedule(g)
    }

    fun setDaysBefore(rule: Rule, days: Int) = launchSafe {
        g.db.rules().upsert(rule.copy(daysBefore = days.coerceIn(0, 60)))
    }

    // ═══ : وظيفة 35 — نتيجة التشغيل الفوري لمسار الفحص الحقيقي ═══
    /** عدّاد ما وجده الفحص الفوري من التذكيرات المستحقة (ذمم/شيكات/أقساط/مخزون منخفض) */
    data class RunNowResult(
        val dueDebts: Int,
        val checks: Int,
        val installments: Int,
        val lowStock: Int,
        val runId: Long
    ) {
        val total: Int get() = dueDebts + checks + installments + lowStock
    }

    val runResult = MutableStateFlow<RunNowResult?>(null)

    fun runNow() = launchSafe {
        // استدعاء مباشر لدالة الفحص (AutomationLogic.run) مع الإشعارات الحقيقية
        // — العدّاد من Summary المعادة بدل إهمالها، وكل إشعار يخضع لأذونات/إعدادات المسار القائم
        // [P20-FIX agent9]: الفحص مسح كامل لجداول الذمم/الشيكات/الأقساط/المخزون — كان على Main
        val s = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            com.superbiz.app.work.AutomationLogic.run(g, g.context, notify = true, force = true)
        }
        runResult.value = RunNowResult(
            s.dueReminders.size, s.checksDue.size, s.installmentsDue.size, s.lowStock.size,
            System.currentTimeMillis()
        )
        refreshKey.value++
    }
}

// ═════════ الإعدادات ═════════
class SettingsVM(app: Application) : AndroidViewModel(app) {
    private val g = AppGraph.from(app)

    val settings = g.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.superbiz.app.data.repo.Settings())

    /** هل صدرت القيمة الأولى الحقيقية من DataStore؟ (لإصلاح بوابة القفل) */
    val ready = g.settings.settings
        .map { true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val currencies = g.db.currencies().all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val toast = MutableStateFlow<String?>(null)
    fun clearToast() { toast.value = null }
    fun notifyToast(msg: String) { toast.value = msg }

    // ═══ [P46-W1] جولة 7: مفوّضات الولاء والكوبونات ═══
    fun setLoyaltyEnabled(v: Boolean) = launchSafe { g.settings.setLoyaltyEnabled(v) }
    fun setLoyaltyEarnDivisor(v: Long) = launchSafe { g.settings.setLoyaltyEarnDivisor(v) }
    fun setLoyaltyPointValue(v: Long) = launchSafe { g.settings.setLoyaltyPointValue(v) }

    fun setBusinessName(v: String) = launchSafe { g.settings.setBusinessName(v) }
    fun setTheme(v: String) = launchSafe { g.settings.setTheme(v) }
    fun setLanguage(v: String) = launchSafe {
        g.settings.setLanguage(v)
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
            androidx.core.os.LocaleListCompat.forLanguageTags(v)
        )
    }
    fun setTaxRate(v: Double) = launchSafe {
        // [H1-4][v13] الإعدادات العامة — المالك والمدير (مصفوفة §3 سطر 10)
        com.superbiz.app.domain.rbac.RoleGate.require(
            com.superbiz.app.domain.rbac.SessionState.effective(),
            com.superbiz.app.domain.rbac.Op.GENERAL_SETTINGS
        )
        g.settings.setTaxRate(v)
    }
    fun setAvatarPath(p: String?) = launchSafe { g.settings.setAvatar(p) }

    fun setBaseCurrency(code: String) = launchSafe {
        // [H4-1][v15] العملة إعداد عام — المالك والمدير (مصفوفة §3 سطر 10، نفس عقد الضريبة)
        com.superbiz.app.domain.rbac.RoleGate.require(
            com.superbiz.app.domain.rbac.SessionState.effective(),
            com.superbiz.app.domain.rbac.Op.GENERAL_SETTINGS
        )
        // اجعل العملة المختارة أساساً وأعد ضبط المعدلات النسبية
        val all = g.db.currencies().allOnce()
        val target = all.firstOrNull { it.code == code } ?: return@launchSafe
        // رفض معدل أساس غير صالح — كانت القيمة صفر (مقبولة سابقاً من updateRate)
        // تُعيد كتابة كل العملات بـ Infinity/NaN فتُفسد كل الحسابات المالية
        require(target.rateToBase.isFinite() && target.rateToBase > 0.0) {
            "لا يمكن جعل ${target.code} أساساً: معدل غير صالح (${target.rateToBase})"
        }
        val newRates = all.map {
            val r = if (it.code == code) 1.0
            else {
                val curBase = all.firstOrNull { x -> x.isBase }?.rateToBase ?: 1.0
                (it.rateToBase / (target.rateToBase)) * if (target.isBase) curBase else 1.0
            }
            it.copy(rateToBase = com.superbiz.app.util.Money.round2(r * 10000) / 10000, isBase = it.code == code)
        }
        g.db.currencies().upsertAll(newRates)
        g.settings.setBaseCurrency(code)
    }

    fun updateRate(code: String, rate: Double) = launchSafe {
        // [H4-1][v15] نفس بوابة الإعدادات العامة — سعر العملة مدخل لكل أختام R17 التاريخية
        com.superbiz.app.domain.rbac.RoleGate.require(
            com.superbiz.app.domain.rbac.SessionState.effective(),
            com.superbiz.app.domain.rbac.Op.GENERAL_SETTINGS
        )
        currencies.value.firstOrNull { it.code == code }?.let {
            // رفض معدل غير موجب/غير نهائي — كان الصفر يُقبل ثم يُفجّر setBaseCurrency لاحقاً
            if (!rate.isFinite() || rate <= 0.0) {
                // [P31-A]: تقني إنجليزي للسجل + رسالة مستخدم موطّنة للـSnackbar
                ErrorCenter.warn(
                    "Currency", "rejected invalid rate for $code: $rate",
                    getApplication<Application>().getString(
                        com.superbiz.app.R.string.err_currency_invalid_rate,
                        code, rate.toString()
                    )
                )
                return@launchSafe
            }
            g.db.currencies().upsert(it.copy(rateToBase = rate))
        }
    }

    /**
     * [H4-6 V 2.5.0] استيراد CSV (أصناف/أطراف) — باب المالك وحده (BACKUP_RESTORE:
     * إدخال جماعي متغيّر للبيانات، نفس باب الاستعادة) والتنفيذ عبر CsvImportRepo
     * (معاملة واحدة، صف تالف يُتخطى بسبب مسمّى). النتيجة توست موطّن عبر notifyToast.
     */
    fun importCsv(uri: android.net.Uri) = launchSafe {
        val app = getApplication<Application>()
        try {
            com.superbiz.app.domain.rbac.RoleGate.require(
                com.superbiz.app.domain.rbac.SessionState.effective(),
                com.superbiz.app.domain.rbac.Op.BACKUP_RESTORE
            )
        } catch (e: Exception) {
            com.superbiz.app.core.ErrorCenter.warn(
                "CsvImport", "denied: ${e.message}",
                app.getString(com.superbiz.app.R.string.csv_import_denied)
            )
            return@launchSafe
        }
        try {
            val text = app.contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (text.isBlank()) {
                notifyToast(app.getString(com.superbiz.app.R.string.csv_import_unknown))
                return@launchSafe
            }
            val res = com.superbiz.app.data.repo.CsvImportRepo(g.db).import(
                text,
                app.getString(com.superbiz.app.R.string.customers),
                app.getString(com.superbiz.app.R.string.suppliers),
                app.getString(com.superbiz.app.R.string.party_both)
            )
            if (res.kind == "unknown") {
                notifyToast(app.getString(com.superbiz.app.R.string.csv_import_unknown))
            } else {
                notifyToast(app.getString(com.superbiz.app.R.string.csv_import_done, res.upserted, res.skipped.size))
            }
        } catch (e: Exception) {
            com.superbiz.app.core.ErrorCenter.warn(
                "CsvImport", "failed: ${e.message}",
                app.getString(com.superbiz.app.R.string.csv_import_unknown)
            )
        }
    }

    // ─── PIN : خزنة Keystore + ترقية شفافة + حدّ محاولات تصاعدي ───
    val lockout = MutableStateFlow(com.superbiz.app.security.LockStatus(0, 0L))
    private val lockoutGuard = com.superbiz.app.security.LockoutGuard(app)

    /** تحديث حالة القفل المعروضة (يُستدعى عند دخول شاشة القفل) */
    fun refreshLockout() = launchSafe {
        lockout.value = lockoutGuard.status()
    }

    /** H-19: نجاح البصمة يصفّر عدّاد المحاولات الفاشلة — كان مسار البصمة لا يلمس الحارس */
    fun onBiometricUnlocked() = launchSafe { lockoutGuard.onSuccess() }

    // مُحدِّثات الإعدادات المركزية — تغذية مباشرة من SettingsHub
    fun setHapticsEnabled(v: Boolean) = launchSafe { g.settings.setHapticsEnabled(v) }
    fun setConfirmDestructive(v: Boolean) = launchSafe { g.settings.setConfirmDestructive(v) }
    fun setFlagSecure(v: Boolean) = launchSafe { g.settings.setFlagSecure(v) }
    fun setLockTimeoutMin(v: Int) = launchSafe { g.settings.setLockTimeoutMin(v) }
    fun setFontScale(v: Float) = launchSafe { g.settings.setFontScale(v) }
    fun setDynamicColors(v: Boolean) = launchSafe { g.settings.setDynamicColors(v) }
    fun setMirrorChartsRtl(v: Boolean) = launchSafe { g.settings.setMirrorChartsRtl(v) }
    fun setAnimationsEnabled(v: Boolean) = launchSafe { g.settings.setAnimationsEnabled(v) }
    fun setArabicReceiptMode(v: Int) = launchSafe { g.settings.setArabicReceiptMode(v) }
    fun setDefaultLowStockQty(v: Int) = launchSafe { g.settings.setDefaultLowStockQty(v) }
    fun setLowStockAlerts(v: Boolean) = launchSafe { g.settings.setLowStockAlerts(v) }
    fun setReceivableAlerts(v: Boolean) = launchSafe { g.settings.setReceivableAlerts(v) }

    // مُحدِّثات التحليلات الذكية
    fun setDefaultTargetMargin(v: Double) = launchSafe { g.settings.setDefaultTargetMargin(v) }
    fun setLateFeeDailyPct(v: Double) = launchSafe { g.settings.setLateFeeDailyPct(v) }
    fun setLateFeeCapPct(v: Double) = launchSafe { g.settings.setLateFeeCapPct(v) }
    fun setWeekendFriSat(v: Boolean) = launchSafe { g.settings.setWeekendFriSat(v) }

    // حساسية البحث الضبابي + كلفة أمر الشراء — مُستهلكان حقيقيان في البحث الشامل ومستشار المخزون
    fun setSearchFuzzyThreshold(v: Double) = launchSafe { g.settings.setSearchFuzzyThreshold(v) }
    fun setEoqOrderCostSetting(v: Double) = launchSafe { g.settings.setEoqOrderCost(v) }

    // وضع الخصوصية — طمس مبالغ الرئيسية (وظيفة 38)
    fun setPrivacyBlur(v: Boolean) = launchSafe { g.settings.setPrivacyBlur(v) }

    fun setPin(pin: String) = launchSafe {
        val salt = com.superbiz.app.security.PinManager.newSalt()
        // PBKDF2 بـ600k دورة على Default — كان يُحسب على Main فيجمّد الواجهة
        val hash = withContext(Dispatchers.Default) {
            com.superbiz.app.security.PinManager.hash(
                pin, salt, com.superbiz.app.security.PinManager.ITERATIONS
            )
        }
        g.settings.setPinSecured(
            salt,
            com.superbiz.app.security.PinVault.encrypt(hash),
            com.superbiz.app.security.PinManager.ITERATIONS,
            pin.length
        )
    }

    fun removePin() = launchSafe {
        g.settings.clearPin()
        lockoutGuard.onSuccess()
        // البيومتريا بلا رمز = ثغرة فتح طارئ — تُطفأ مع الرمز
        g.settings.setBiometric(false)
    }

    /**
     * التحقق من الرمز — نفس المسار لشاشة القفل وحوارات إعادة المصادقة،
     * مع حدّ المحاولات: مرفوض مباشرة أثناء قفل ساري، وكل فشل يزيد المدة.
     */
    fun verifyPin(pin: String, onResult: (Boolean) -> Unit) = launchSafe {
        val st = lockoutGuard.status()
        // [تدقيق M-9] القرار بالزمن الأحادي — تراجع ساعة الجهاز لا يقصّر القفل
        if (st.isLocked(android.os.SystemClock.elapsedRealtime())) {
            lockout.value = st
            onResult(false)
            return@launchSafe
        }
        val s = g.settings.snapshot()
        // التحقق (PBKDF2 600k + فك Keystore) على Default لا Main — كانت كل ضغطة
        // مفتاح تُجمّد الواجهة أثناء حساب البصمة، وكان الفشل غير المتوقع يترك الواجهة معلّقة
        val ok = try {
            withContext(Dispatchers.Default) {
                when {
                // بصمة مغلّفة بـ Keystore — التحقق مستحيل خارج الجهاز
                s.pinBlob != null && s.pinSalt != null -> {
                    val iters = s.pinIters.takeIf { it > 0 } ?: com.superbiz.app.security.PinManager.ITERATIONS
                    val unwrapped = com.superbiz.app.security.PinVault.decrypt(s.pinBlob)
                    val verified = unwrapped != null && com.superbiz.app.security.PinManager.verify(
                        pin, s.pinSalt, unwrapped, iters
                    )
                    // [P6-M6-14 إصلاح] ترقية شفافة: أول تحقق ناجح لرمز مخزون بصيغة v1 القديمة
                    // (بصمة عارية قابلة للكسر دون اتصال) يُعاد تغليفه بصيغة ks الحالية دون أي
                    // إدخال من المستخدم — فشل الترقية (بلا Keystore مثلاً) لا يؤثر على نتيجة
                    // التحقق ويبقى المخزون القديم مقروءاً وتُعاد المحاولة عند التحقق التالي
                    if (verified) {
                        com.superbiz.app.security.PinVault.rewrap(s.pinBlob)?.let { rewrapped ->
                            g.settings.setPinSecured(s.pinSalt, rewrapped, iters, s.pinLength)
                        }
                    }
                    verified
                }
                // مفقودات -: بصمة صريحة 60k — تُرقّى تلقائياً إلى Keystore+600k عند أول نجاح
                s.pinHash != null && s.pinSalt != null -> {
                    val legacyOk = com.superbiz.app.security.PinManager.verify(
                        pin, s.pinSalt, s.pinHash, com.superbiz.app.security.PinManager.LEGACY_ITERATIONS
                    )
                    if (legacyOk) {
                        val upgraded = com.superbiz.app.security.PinManager.hash(
                            pin, s.pinSalt, com.superbiz.app.security.PinManager.ITERATIONS
                        )
                        // [P6-M6-14 إصلاح] التشفير صار فاشل مغلق (يرمي عند غياب Keystore بدل
                        // كتابة v1 عارية) — تُعزل ترقية المفقودات هنا كي لا يُرفض رمز صحيح
                        // وتُقفل شاشة الدخول؛ يبقى المخزون القديم كما هو وتُعاد المحاولة لاحقاً
                        try {
                            g.settings.setPinSecured(
                                s.pinSalt,
                                com.superbiz.app.security.PinVault.encrypt(upgraded),
                                com.superbiz.app.security.PinManager.ITERATIONS
                            )
                        } catch (e: com.superbiz.app.security.PinVault.PinVaultException) {
                            // [P31-A]: تقني إنجليزي للسجل + رسالة مستخدم موطّنة للـSnackbar
                            ErrorCenter.warn(
                                "PinVault", "legacy pin digest upgrade to Keystore failed: ${e.message}",
                                getApplication<Application>().getString(
                                    com.superbiz.app.R.string.err_pinvault_upgrade_failed
                                )
                            )
                        }
                    }
                    legacyOk
                }
                else -> false
                }
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            false
        }
        if (ok) lockoutGuard.onSuccess() else lockoutGuard.onFailed()
        lockout.value = lockoutGuard.status()
        onResult(ok)
    }

    // ─── الملف الشخصي المتكامل ───
    fun setOwnerName(v: String) = launchSafe { g.settings.setOwnerName(v) }
    fun setPhone(v: String) = launchSafe { g.settings.setPhone(v) }
    fun setEmail(v: String) = launchSafe { g.settings.setEmail(v) }
    fun setAddress(v: String) = launchSafe { g.settings.setAddress(v) }
    fun setTaxNumber(v: String) = launchSafe { g.settings.setTaxNumber(v) }

    fun setBiometric(v: Boolean) = launchSafe { g.settings.setBiometric(v) }

    /**إخفاء المبالغ في الويدجت (خصوصية) */
    fun setRedactWidgets(v: Boolean) = launchSafe { g.settings.setRedactWidgets(v) }
    fun markWelcomeSeen() = launchSafe { g.settings.setWelcomeSeen() }
    fun markPermissionsSeen() = launchSafe { g.settings.setPermissionsSeen() }
    // وظيفة 34: كل تغيير لعدد أيام النسخ التلقائي يعيد جدولة الـWorker الدوري فوراً
    fun setAutoBackupDays(v: Int) = launchSafe {
        g.settings.setAutoBackupDays(v)
        com.superbiz.app.work.BackupWorker.schedule(getApplication(), v)
    }

    // جدولة إرسال التقرير A4 — كل تغيير يعيد الجدولة فوراً في WorkManager
    fun setReportScheduleDays(v: Int) = launchSafe {
        g.settings.setReportScheduleDays(v)
        com.superbiz.app.work.ReportScheduleWorker.schedule(
            getApplication(), v, g.settings.snapshot().reportScheduleHour
        )
    }
    fun setReportScheduleHour(v: Int) = launchSafe {
        g.settings.setReportScheduleHour(v)
        com.superbiz.app.work.ReportScheduleWorker.schedule(
            getApplication(), g.settings.snapshot().reportScheduleDays, v
        )
    }
    fun setReportScheduleChannel(v: String) = launchSafe { g.settings.setReportScheduleChannel(v) }

    // المستلم الافتراضي (بريد العميل/المالك المحفوظ) في جدولة التقرير
    fun setReportRecipient(v: String) = launchSafe { g.settings.setReportRecipient(v) }

    // هدف المبيعات الشهري
    fun setMonthlyGoal(v: Double) = launchSafe { g.settings.setMonthlyGoal(v) }

    // ─── النسخ الاحتياطي ───
    fun exportTo(uri: Uri) = launchSafe {
        // [H1-4][v13] النسخ الاحتياطي — باب المالك وحده (مصفوفة §3 سطر 12)
        com.superbiz.app.domain.rbac.RoleGate.require(
            com.superbiz.app.domain.rbac.SessionState.effective(),
            com.superbiz.app.domain.rbac.Op.BACKUP_RESTORE
        )
        val ok = g.backup.exportTo(uri)
        toast.value = if (ok) getApplication<Application>().getString(com.superbiz.app.R.string.backup_ok)
        else getApplication<Application>().getString(com.superbiz.app.R.string.backup_fail)
    }

    fun importFrom(uri: Uri) = launchSafe {
        // [H1-4][v13] الاستعادة — باب المالك وحده (مصفوفة §3 سطر 12)
        com.superbiz.app.domain.rbac.RoleGate.require(
            com.superbiz.app.domain.rbac.SessionState.effective(),
            com.superbiz.app.domain.rbac.Op.BACKUP_RESTORE
        )
        val ok = g.backup.importFrom(uri)
        toast.value = if (ok) getApplication<Application>().getString(com.superbiz.app.R.string.backup_ok)
        else getApplication<Application>().getString(com.superbiz.app.R.string.backup_fail)
    }

    fun setBackupDir(uri: Uri?) = launchSafe {
        g.settings.setBackupDir(uri?.toString())
        uri?.let {
            try {
                getApplication<Application>().contentResolver
                    .takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                // فشل تثبيت إذن المجلد يُسجَّل — كان يُبتلع صامتاً فيعجز التصدير لاحقاً بلا أثر
                com.superbiz.app.core.ErrorCenter.warn("Backup", "takePersistableUriPermission: ${e.message}")
            }
        }
    }

    // [P36-BK] تضمين PDFs الكشوف في مرآة النسخ الاحتياطي — مفاتيح الإعداد فقط،
    // والتنفيذ الفعلي في SafBackupMirror.mirrorPdfs داخل BackupWorker/AutomationWorker
    fun setBackupIncludePdfs(v: Boolean) = launchSafe { g.settings.setBackupIncludePdfs(v) }

    // [P7-L17 إصلاح] مجلد النسخ الاحتياطي (SAF): تفويض مباشر إلى setter المستودع القائم
    // (SettingsRepo.K.backupDir موجود منذ v4.x — لا مفتاح DataStore جديد). نسخة String لمنتقي
    // OpenDocumentTree في مركز الإعدادات: إذن المجلد takePersistableUriPermission يُثبَّت في طبقة
    // العرض قبل الاستدعاء، وتمرير null (المسح) يزيل المفتاح فيعود النسخ التلقائي للمسار الداخلي.
    // يستمر استهلاك backupDirUri من الـWorker الجديد SafBackupMirror (نسخ مرآة SAF بعد كل exportLocal).
    fun setBackupDir(uri: String?) = launchSafe { g.settings.setBackupDir(uri) }

    fun wipeAll() = launchSafe {
        // مسح ذرّي آمن داخل معاملة ثم إعادة بذر العملات والقواعد فوراً
        g.db.withTransaction { g.db.maintenance().wipeAll() }
        g.seedIfFirstRun()
    }

    fun scheduleAutoBackup() {
        com.superbiz.app.work.AutomationWorker.schedule(g)
    }

    // ═══ : تصدير/استيراد إعدادات JSON (وظيفة 33) — مسار مستقل تماماً عن BackupRepo ═══

    /** بناء حِمل التصدير من اللقطة الحالية — عبر القائمة البيضاء في SettingsCodec فقط */
    private suspend fun settingsExportEntries(): List<SettingsCodec.Entry> {
        val s = g.settings.snapshot()
        return listOf(
            SettingsCodec.Entry("language", SettingsCodec.Val.S(s.language)),
            SettingsCodec.Entry("theme", SettingsCodec.Val.S(s.theme)),
            SettingsCodec.Entry("baseCurrency", SettingsCodec.Val.S(s.baseCurrency)),
            SettingsCodec.Entry("taxRate", SettingsCodec.Val.N(s.taxRate)),
            SettingsCodec.Entry("fontScale", SettingsCodec.Val.N(s.fontScale.toDouble())),
            SettingsCodec.Entry("dynamicColors", SettingsCodec.Val.B(s.dynamicColors)),
            SettingsCodec.Entry("mirrorChartsRtl", SettingsCodec.Val.B(s.mirrorChartsRtl)),
            SettingsCodec.Entry("hapticsEnabled", SettingsCodec.Val.B(s.hapticsEnabled)),
            SettingsCodec.Entry("confirmDestructive", SettingsCodec.Val.B(s.confirmDestructive)),
            SettingsCodec.Entry("animationsEnabled", SettingsCodec.Val.B(s.animationsEnabled)),
            SettingsCodec.Entry("flagSecure", SettingsCodec.Val.B(s.flagSecure)),
            SettingsCodec.Entry("redactWidgets", SettingsCodec.Val.B(s.redactWidgets)),
            SettingsCodec.Entry("privacyBlur", SettingsCodec.Val.B(s.privacyBlur)),
            SettingsCodec.Entry("lockTimeoutMin", SettingsCodec.Val.N(s.lockTimeoutMin.toDouble())),
            SettingsCodec.Entry("lowStockAlerts", SettingsCodec.Val.B(s.lowStockAlerts)),
            SettingsCodec.Entry("receivableAlerts", SettingsCodec.Val.B(s.receivableAlerts)),
            SettingsCodec.Entry("autoBackupDays", SettingsCodec.Val.N(s.autoBackupDays.toDouble())),
            SettingsCodec.Entry("reportScheduleDays", SettingsCodec.Val.N(s.reportScheduleDays.toDouble())),
            SettingsCodec.Entry("reportScheduleHour", SettingsCodec.Val.N(s.reportScheduleHour.toDouble())),
            SettingsCodec.Entry("reportScheduleChannel", SettingsCodec.Val.S(s.reportScheduleChannel)),
            SettingsCodec.Entry("reportRecipient", SettingsCodec.Val.S(s.reportRecipient)),
            SettingsCodec.Entry("arabicReceiptMode", SettingsCodec.Val.N(s.arabicReceiptMode.toDouble())),
            SettingsCodec.Entry("defaultLowStockQty", SettingsCodec.Val.N(s.defaultLowStockQty.toDouble())),
            SettingsCodec.Entry("defaultTargetMargin", SettingsCodec.Val.N(s.defaultTargetMargin)),
            SettingsCodec.Entry("lateFeeDailyPct", SettingsCodec.Val.N(s.lateFeeDailyPct)),
            SettingsCodec.Entry("lateFeeCapPct", SettingsCodec.Val.N(s.lateFeeCapPct)),
            SettingsCodec.Entry("weekendFriSat", SettingsCodec.Val.B(s.weekendFriSat)),
            SettingsCodec.Entry("searchFuzzyThreshold", SettingsCodec.Val.N(s.searchFuzzyThreshold)),
            SettingsCodec.Entry("eoqOrderCost", SettingsCodec.Val.N(s.eoqOrderCost)),
            SettingsCodec.Entry("expenseMonthlyLimit", SettingsCodec.Val.N(s.expenseMonthlyLimit))
        )
    }

    /** تصدير الإعدادات (JSON) عبر SAF — بلا رمز/أسرار/بيانات أعمال */
    fun exportSettingsTo(uri: Uri, onResult: (Boolean) -> Unit) = launchSafe {
        val ok = try {
            val json = com.superbiz.app.domain.SettingsCodec.build(settingsExportEntries())
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                    out.flush()
                } != null
            }
        } catch (e: Exception) { false }
        onResult(ok)
    }

    /** استيراد بتحقق صارم: قائمة بيضاء + نوع + نطاق — الرفض كلي عند أي مفتاح/قيمة غير صالحة */
    fun importSettingsFrom(uri: Uri, onResult: (Int, String?) -> Unit) = launchSafe {
        val text = try {
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { inp ->
                    inp.bufferedReader(Charsets.UTF_8).readText()
                }
            }
        } catch (e: Exception) { null }
        when (val r = com.superbiz.app.domain.SettingsCodec.parse(text)) {
            is com.superbiz.app.domain.SettingsCodec.ParseResult.Ok -> {
                val n = try { applyImport(r.values) } catch (e: Exception) { -1 }
                if (n >= 0) onResult(n, null) else onResult(0, "apply_failed")
            }
            is com.superbiz.app.domain.SettingsCodec.ParseResult.Err -> onResult(0, r.reason)
        }
    }

    /** تطبيق القيم المستوردة عبر المُحدِّثات الحقيقية في SettingsRepo */
    private suspend fun applyImport(entries: List<SettingsCodec.Entry>): Int {
        fun sv(e: SettingsCodec.Entry) = (e.value as SettingsCodec.Val.S).v
        fun nv(e: SettingsCodec.Entry) = (e.value as SettingsCodec.Val.N).v
        fun bv(e: SettingsCodec.Entry) = (e.value as SettingsCodec.Val.B).v
        for (e in entries) {
            when (e.key) {
                "language" -> {
                    val v = sv(e)
                    g.settings.setLanguage(v)
                    androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                        androidx.core.os.LocaleListCompat.forLanguageTags(v)
                    )
                }
                "theme" -> g.settings.setTheme(sv(e))
                // [P6-M6-16 إصلاح] العملة المستوردة من ملف الإعدادات تمر بنفس تحقق الـVM
                // (وجودها في قائمة العملات) — غير الموجودة تُرفض مع تسجيل وتبقى العملة الحالية
                "baseCurrency" -> {
                    val code = sv(e)
                    if (g.db.currencies().allOnce().any { it.code == code }) {
                        g.settings.setBaseCurrency(code)
                    } else {
                        // [P31-A]: تقني إنجليزي للسجل + رسالة مستخدم موطّنة للـSnackbar
                        ErrorCenter.warn(
                            "SettingsImport",
                            "rejected unknown base currency from settings file: $code — kept ${g.settings.snapshot().baseCurrency}",
                            getApplication<Application>().getString(
                                com.superbiz.app.R.string.err_currency_base_ignored,
                                code, g.settings.snapshot().baseCurrency
                            )
                        )
                    }
                }
                "taxRate" -> g.settings.setTaxRate(nv(e))
                "fontScale" -> g.settings.setFontScale(nv(e).toFloat())
                "dynamicColors" -> g.settings.setDynamicColors(bv(e))
                "mirrorChartsRtl" -> g.settings.setMirrorChartsRtl(bv(e))
                "hapticsEnabled" -> g.settings.setHapticsEnabled(bv(e))
                "confirmDestructive" -> g.settings.setConfirmDestructive(bv(e))
                "animationsEnabled" -> g.settings.setAnimationsEnabled(bv(e))
                "flagSecure" -> g.settings.setFlagSecure(bv(e))
                "redactWidgets" -> g.settings.setRedactWidgets(bv(e))
                "privacyBlur" -> g.settings.setPrivacyBlur(bv(e))
                "lockTimeoutMin" -> g.settings.setLockTimeoutMin(nv(e).toInt())
                "lowStockAlerts" -> g.settings.setLowStockAlerts(bv(e))
                "receivableAlerts" -> g.settings.setReceivableAlerts(bv(e))
                "autoBackupDays" -> {
                    val d = nv(e).toInt()
                    g.settings.setAutoBackupDays(d)
                    com.superbiz.app.work.BackupWorker.schedule(getApplication(), d)
                }
                "reportScheduleDays" -> {
                    val d = nv(e).toInt()
                    g.settings.setReportScheduleDays(d)
                    com.superbiz.app.work.ReportScheduleWorker.schedule(
                        getApplication(), d, g.settings.snapshot().reportScheduleHour
                    )
                }
                "reportScheduleHour" -> {
                    val h = nv(e).toInt()
                    g.settings.setReportScheduleHour(h)
                    com.superbiz.app.work.ReportScheduleWorker.schedule(
                        getApplication(), g.settings.snapshot().reportScheduleDays, h
                    )
                }
                "reportScheduleChannel" -> g.settings.setReportScheduleChannel(sv(e))
                "reportRecipient" -> g.settings.setReportRecipient(sv(e))
                "arabicReceiptMode" -> g.settings.setArabicReceiptMode(nv(e).toInt())
                "defaultLowStockQty" -> g.settings.setDefaultLowStockQty(nv(e).toInt())
                "defaultTargetMargin" -> g.settings.setDefaultTargetMargin(nv(e))
                "lateFeeDailyPct" -> g.settings.setLateFeeDailyPct(nv(e))
                "lateFeeCapPct" -> g.settings.setLateFeeCapPct(nv(e))
                "weekendFriSat" -> g.settings.setWeekendFriSat(bv(e))
                "searchFuzzyThreshold" -> g.settings.setSearchFuzzyThreshold(nv(e))
                "eoqOrderCost" -> g.settings.setEoqOrderCost(nv(e))
                "expenseMonthlyLimit" -> g.settings.setExpenseMonthlyLimit(nv(e))
            }
        }
        return entries.size
    }
}

// ═════════ [P46-W1] جولة 7 — الولاء والكوبونات ═════════
class LoyaltyVM(app: Application) : AndroidViewModel(app) {
    private val g = AppGraph.from(app)

    /** كل الكوبونات — شاشة الإدارة في مركز الإعدادات */
    val coupons = g.loyalty.coupons()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val toast = MutableStateFlow<Int?>(null)
    fun consumeToast() { toast.value = null }

    /**
     * حفظ كوبون (جديد) — الحارس هنا بوابة صادقة: كود فارغ أو قيم سالبة تُرفض
     * قبل القاعدة، والتعارض الفريد للكود (فهرس v12) يرمي فيلتقطه launchSafe.
     */
    fun addCoupon(
        code: String, kind: Int, amountText: String, percentText: String,
        maxUsesText: String, daysText: String, note: String
    ) = launchSafe {
        val c = code.trim()
        if (c.isEmpty()) { toast.value = R.string.coupon_err_empty; return@launchSafe }
        val amount = com.superbiz.app.util.Money.parseToPiasters(amountText).coerceAtLeast(0L)
        val percent = com.superbiz.app.util.NumText.parseNum(percentText).coerceIn(0.0, 100.0)
        val maxUses = com.superbiz.app.util.NumText.parseNum(maxUsesText).toLong().coerceAtLeast(0L).toInt()
        val days = com.superbiz.app.util.NumText.parseNum(daysText).toLong().coerceAtLeast(0L)
        if (kind == com.superbiz.app.domain.LoyaltyP46.KIND_FIXED && amount <= 0L) {
            toast.value = R.string.coupon_err_no_value; return@launchSafe
        }
        if (kind == com.superbiz.app.domain.LoyaltyP46.KIND_PERCENT && percent <= 0.0) {
            toast.value = R.string.coupon_err_no_value; return@launchSafe
        }
        g.loyalty.saveCoupon(
            CouponEntity(
                code = c, kind = kind,
                amountPiasters = if (kind == com.superbiz.app.domain.LoyaltyP46.KIND_FIXED) amount else 0L,
                percent = if (kind == com.superbiz.app.domain.LoyaltyP46.KIND_PERCENT) percent else 0.0,
                expiresAt = if (days > 0L) System.currentTimeMillis() + days * 86_400_000L else 0L,
                maxUses = maxUses, note = note.trim()
            )
        )
        toast.value = R.string.coupon_saved
    }

    fun deleteCoupon(id: Long) = launchSafe {
        // [H1-4][v13] إدارة الكوبونات — المالك والمدير (مصفوفة §3 سطر 14)
        com.superbiz.app.domain.rbac.RoleGate.require(
            com.superbiz.app.domain.rbac.SessionState.effective(),
            com.superbiz.app.domain.rbac.Op.LOYALTY_MANAGE
        )
        g.loyalty.deleteCoupon(id)
    }

    /** إيقاف/تفعيل — الكوبون الموقوف ترفضه بوابة checkSpec وحرس الاستهلاك الذرّي معاً */
    fun toggleActive(c: CouponEntity) = launchSafe { g.loyalty.saveCoupon(c.copy(active = !c.active)) }
}

// ═════════ الأقساط ═════════
class InstallmentsVM(app: Application) : AndroidViewModel(app) {
    private val g = AppGraph.from(app)

    /**سياق التطبيق للنوافذ التي تحتاج لقطة إعدادات (الغرامة) بلا تسريب نشاط */
    val appContext: android.content.Context get() = getApplication<Application>()

    val plans = g.installments.plans()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val parties = g.ledger.parties()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** -1 الكل، 0 قائمة، 2 متأخرة، 1 مكتملة */
    val statusFilter = MutableStateFlow(-1)

    data class PlanUi(val plan: com.superbiz.app.data.db.InstallmentPlan,
                      val stats: com.superbiz.app.domain.InstallmentEngine.PlanStats)

    /** بطاقات جاهزة: الخطة + إحصاءاتها المحسوبة */
    private val reload = MutableStateFlow(0)
    val cards: StateFlow<List<PlanUi>> = kotlinx.coroutines.flow.combine(
        plans, reload, statusFilter
    ) { list, _, f -> list to f }
        .flatMapLatest { (list, f) ->
            kotlinx.coroutines.flow.flow<kotlin.collections.List<PlanUi>> {
                // استعلام واحد لكل الخطط على Default بدل استعلام
                // لكل خطة على Main في كل انبعاث للقائمة أو تغيير مرشّح
                val stats = withContext(Dispatchers.Default) { g.installments.statsForPlans(list.map { it.id }) }
                val out = ArrayList<PlanUi>()
                for (p in list) {
                    val st = stats[p.id] ?: g.installments.statsOf(p.id)
                    val ok = when (f) {
                        0 -> p.direction == 0
                        1 -> p.direction == 1
                        2 -> st.lateCount > 0
                        3 -> st.done
                        else -> true
                    }
                    if (ok) out += PlanUi(p, st)
                }
                emit(out)
            }
                // فشل حساب الإحصاءات يعيد قائمة فارغة بدل موت جامع stateIn وإغلاق التطبيق
                .catch { e ->
                    android.util.Log.e("SuperBizVM", "installment cards failed", e)
                    emit(emptyList())
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** إجماليات الشريط العلوي — [P33-P8] المتبقي/المتأخر قروش Long */
    val totals: StateFlow<Triple<Long, Long, Int>> =
        kotlinx.coroutines.flow.combine(plans, reload) { list, _ -> list }
            .flatMapLatest { list ->
                kotlinx.coroutines.flow.flow {
                    // دفعة واحدة بدل استعلام لكل خطة
                    val stats = withContext(Dispatchers.Default) { g.installments.statsForPlans(list.map { it.id }) }
                    var rem = 0L; var late = 0L; var lateN = 0
                    for (p in list) {
                        val s = stats[p.id] ?: g.installments.statsOf(p.id)
                        rem += s.remaining
                        late += s.lateAmount; lateN += s.lateCount
                    }
                    // [P33-P8] جمع قروش صحيح تام — بلا round2 (حُذف من المحرك)
                    emit(Triple(rem, late, lateN))
                }
            }
            // فشل حساب الإجماليات يعيد أصفاراً بدل موت جامع stateIn وإغلاق التطبيق
            .catch { e ->
                android.util.Log.e("SuperBizVM", "installment totals failed", e)
                emit(Triple(0L, 0L, 0))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Triple(0L, 0L, 0))

    fun save(title: String, party: Party, direction: Int, total: Double,
             down: Double, months: Int, startDate: Long, note: String) =
        launchSafe {
            // [P33-P8] المبلغان يدخلان ريالاً من المحرر → قروش عند الحدود الوحيدة (Money)
            val totalP = com.superbiz.app.util.Money.toPiasters(total)
            val downP = com.superbiz.app.util.Money.toPiasters(down)
            // تحقق محلي بمزامنة مع حراسة المستودع (C7) — رسالة واضحة
            // بدل انفجار require صامت: عنوان فارغ، أقساط خارج 1..120، مبلغ غير منطقي
            // [P33-P8] المقارنات صحيحة تامة (Long لا يكون NaN — حراسة isFinite ضمن toPiasters)
            val errMsg = when {
                title.isBlank() -> getApplication<Application>().getString(com.superbiz.app.R.string.err_title_blank)
                months !in 1..120 -> getApplication<Application>().getString(com.superbiz.app.R.string.err_months_range)
                totalP <= 0L -> getApplication<Application>().getString(com.superbiz.app.R.string.err_amount_positive)
                // مزامنة حراسة الدفعة المقدمة مع المستودع — سالبة أو ≥ الإجمالي
                downP < 0L -> getApplication<Application>().getString(com.superbiz.app.R.string.err_amount_positive)
                downP >= totalP -> getApplication<Application>().getString(com.superbiz.app.R.string.err_down_below_total)
                else -> null
            }
            if (errMsg != null) {
                com.superbiz.app.core.ErrorCenter.warn("InstallmentsVM", "rejected plan save", errMsg)
                return@launchSafe
            }
            g.installments.createPlan(
                title, party.id, direction, totalP, downP, months, startDate,
                currency = g.settings.snapshot().baseCurrency, note = note
            )
            reload.value++
        }

    // amount اختياري — سداد مبكر بالمبلغ المخفَّض (وظيفة 27) عبر المسار الحقيقي نفسه
    fun pay(inst: com.superbiz.app.data.db.Installment, amount: Double? = null) = launchSafe {
        // [P33-P8] المبلغ الاختياري ريال → قروش عند الحدود الوحيدة (Money)
        g.installments.pay(inst, amount?.let { com.superbiz.app.util.Money.toPiasters(it) })
        reload.value++
    }

    // تسوية السداد المبكر المخفَّضة — تغلق القسط كلياً بقيد إعداب
    // بدل تركه عالقاً بمفتوح = قيمة الخصم للأبد
    fun payEarly(inst: com.superbiz.app.data.db.Installment) = launchSafe {
        g.installments.payEarlySettlement(inst)
        reload.value++
    }

    /** سداد القسط التالي غير المسدد في الخطة — [P33-P8] مساواة صحيحة تامة بدل عتبة 0.005 */
    fun payNext(planId: Long) = launchSafe {
        val next = g.installments.installmentsOf(planId).firstOrNull {
            it.paidAmount < it.amount
        } ?: return@launchSafe
        g.installments.pay(next)
        reload.value++
    }

    fun delete(plan: com.superbiz.app.data.db.InstallmentPlan) = launchSafe {
        g.installments.deletePlan(plan)
        // تذكيرات الخطة المحذوفة كانت تبقى في WorkManager تطلق
        // إشعارات لدفعات لم تعد موجودة — إلغاء شامل بوسم الطرف
        com.superbiz.app.work.PaymentPlanReminders.cancelFor(getApplication(), plan.partyId)
        reload.value++
    }

    // إعادة جدولة قسط متأخر (وظيفة 28) — عبر InstallmentRepo الحقيقي داخل معاملة + سطر تدقيق
    fun reschedule(
        inst: com.superbiz.app.data.db.Installment,
        newDueDate: Long,
        onDone: (Boolean) -> Unit = {}
    ) = launchSafe {
        val ok = g.installments.reschedule(inst, newDueDate)
        if (ok) reload.value++
        onDone(ok)
    }

    suspend fun installmentsOf(planId: Long) = g.installments.installmentsOf(planId)
    // بديل موطَّن من الموارد بدل "؟" المضمّنة
    suspend fun partyName(pid: Long): String =
        g.ledger.party(pid)?.name ?: getApplication<Application>().getString(com.superbiz.app.R.string.unknown_party)
    suspend fun partyOf(pid: Long): Party? = g.ledger.party(pid)
}

// ═════════ البحث الشامل ═════════
/**
 * بحث موحّد رتبيّ عبر المنتجات والأطراف والفواتير والشيكات:
 * الترتيب عبر TextMath.fuzzyScore (تطبيع عربي + بادئات + جارو-وينكلر)
 * وحدّ القبول من إعدادات المستخدم searchFuzzyThreshold — بلا نصوص صلبة.
 */
@kotlinx.coroutines.FlowPreview
class GlobalSearchVM(app: Application) : AndroidViewModel(app) {
    private val g = AppGraph.from(app)

    data class Hit(
        val kind: Int,        // 0 منتج، 1 طرف، 2 فاتورة، 3 شيك
        val id: Long,
        val title: String,
        val subtitle: String,
        val score: Double
    )

    private data class Sources(
        val products: List<Product>,
        val parties: List<Party>,
        val invoices: List<Invoice>,
        val checks: List<CheckEntity>
    )

    val query = MutableStateFlow("")

    /** آخر 8 استعلامات من الإعدادات — إعادة سريعة بنقرة */
    val recent: StateFlow<List<String>> = g.settings.settings.map { s ->
        try {
            val a = org.json.JSONArray(s.recentSearches ?: "[]")
            (0 until a.length()).mapNotNull { i -> a.optString(i).takeIf { it.isNotEmpty() } }
        } catch (e: Exception) { emptyList() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val sources = kotlinx.coroutines.flow.combine(
        g.inventory.products(), g.ledger.parties(), g.invoices.invoices(), g.checks.checks()
    ) { p, pt, inv, ch -> Sources(p, pt, inv, ch) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Sources(emptyList(), emptyList(), emptyList(), emptyList()))

    private val threshold = g.settings.settings
        .map { it.searchFuzzyThreshold }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.45)

    // مهلة 250ms على الاستعلام — كانت كل ضغطة مفتاح تطلق إعادة
    // ترتيب ضبابي كامل على كل المنتجات والأطراف والفواتير والشيكات
    // كان التقييم الضبابي O(len²) لكل صنف يعمل في سياق جامع Main —
    // نفس صنف R14-F10/F11/F12 الذي أصلناه في أشقائه — flowOn(Default) الآن
    val results: StateFlow<List<Hit>> = kotlinx.coroutines.flow.combine(
        query.debounce(250), sources, threshold
    ) { q, src, thr ->
        if (q.trim().length < 2) return@combine emptyList<Hit>()
        val out = ArrayList<Hit>(32)
        val queryNorm = q.trim()
        for (p in src.products) {
            var sc = com.superbiz.app.domain.algo.TextMath.fuzzyScore(queryNorm, p.name)
            if (p.sku.isNotEmpty() && p.sku.contains(queryNorm, true)) sc = maxOf(sc, 0.96)
            if (p.barcode.isNotEmpty() && p.barcode.contains(queryNorm)) sc = 0.99
            if (sc >= thr) out.add(Hit(0, p.id, p.name,
                "SKU ${p.sku.ifBlank { "—" }} · ${com.superbiz.app.util.Money.numP(p.salePrice)}", sc)) // [P33-P8] قروش
        }
        val partyNames = src.parties.associate { it.id to it.name }
        for (pt in src.parties) {
            var sc = com.superbiz.app.domain.algo.TextMath.fuzzyScore(queryNorm, pt.name)
            // بحث بالأحرف الأولى «ن ج م» يطابق «نجم للخدمات» — إشارة حقيقية للمستخدم
            if (sc < thr && com.superbiz.app.domain.algo.TextMath.initialsMatch(queryNorm, pt.name)) sc = 0.75
            if (pt.phone.isNotBlank() && com.superbiz.app.domain.algo.TextMath.digitsOnly(pt.phone)
                    .contains(com.superbiz.app.domain.algo.TextMath.digitsOnly(queryNorm)) && queryNorm.any { it.isDigit() })
                sc = 0.98
            if (sc >= thr) out.add(Hit(1, pt.id, pt.name,
                pt.phone.ifBlank { if (pt.isCustomer) "عميل" else "مورد" }, sc))
        }
        // ترتيب دفعي عبر rankByFuzzy (نفس الخوارزمية المستخدمة في ترتيب القوائم الضبابية)
        val invHays = src.invoices.map { it.number + " " + (partyNames[it.partyId] ?: "") }
        for ((idx, sc) in com.superbiz.app.domain.algo.TextMath.rankByFuzzy(queryNorm, invHays, limit = 24, threshold = thr)) {
            val inv = src.invoices[idx]
            val sc2 = if (inv.number.contains(queryNorm)) 0.97 else sc
            out.add(Hit(2, inv.id, inv.number,
                "${partyNames[inv.partyId] ?: "؟"} · ${com.superbiz.app.util.Money.numP(inv.total)}", sc2)) // [P33-P8] قروش
        }
        for (c in src.checks) {
            val hay = c.number + " " + c.bank + " " + (partyNames[c.partyId] ?: "")
            val sc = maxOf(
                com.superbiz.app.domain.algo.TextMath.fuzzyScore(queryNorm, hay),
                if (c.number.contains(queryNorm)) 0.97 else 0.0
            )
            if (sc >= thr) out.add(Hit(3, c.id, c.number,
                "${partyNames[c.partyId] ?: "؟"} · ${com.superbiz.app.util.Money.numP(c.amount)}", sc)) // [P33-P8] قروش
        }
        out.sortedByDescending { it.score }.take(24)
    }
        // التقييم الضبابي كله خارج الخيط الرئيسي — كان على Main لكل ضغطة
        .flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** توثيق الاستعلام في آخر عمليات البحث عند اختيار نتيجة */
    fun record(term: String) = launchSafe { g.settings.addRecentSearch(term) }
    fun clearRecent() = launchSafe { g.settings.clearRecentSearches() }
}

// ═════════ فحص صحة البيانات ═════════
/**
 * ماسح سلامة البيانات: بنفورد (كشف شذوذ الأرقام)، فجوات الترقيم، الفواتير المكررة،
 * الأطراف اليتيمة، القيم الشاذة، المؤرشف بمخزون، وتشابه الأطراف — كل شيء من القاعدة الحقيقية.
 */
class DataHealthVM(app: Application) : AndroidViewModel(app) {
    private val g = AppGraph.from(app)

    data class Finding(
        val kind: String,     // معرّف النوع — الشاشة تربطه بعنوان مترجم
        val count: Int,       // عدد السجلات المتأثرة (أو حجم العينة لبنفورد)
        val detail: String,   // تفصيل قابل للعرض والمشاركة
        val severity: Int     // 0 سليم، 1 للانتباه، 2 خطر
    )

    data class HealthReport(
        val scannedRecords: Int,
        val findings: List<Finding>,
        val benfordDev: Double
    )

    private val _report = MutableStateFlow<HealthReport?>(null)
    val report: StateFlow<HealthReport?> = _report

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    fun scan() = launchSafe {
        _scanning.value = true
        // [P6-M6-17 إصلاح] رسالة «مدى الترقيم الواسع» كانت عربية صلبة وتتسرب إلى نص المشاركة
        // وصفّ البند — تُقرأ من الموارد على Main قبل نزول الفحص إلى Default
        val wideRangeMsg = getApplication<Application>().getString(R.string.dh_share_gaps_wide)
        try {
            // الفحص (بنفورد + مقارنات ثنائية O(n²) + تجميعات كاملة)
            // كان كله على الخيط الرئيسي فيجمّد الواجهة ثوانياً مع آلاف السجلات —
            // الحساب كله الآن على Dispatchers.Default (قراءات Room آمنة من أي خيط)
            withContext(Dispatchers.Default) {
            val invoices = g.db.invoices().allOnce()
            val parties = g.db.parties().allOnce()
            // [P37-فحص-الصحة]: يشمل المؤرشف — فحص arch_stock كان ميتاً على allOnce
            // (الذي يستثني المؤرشف) فيصفّر عداده دائماً بلا كشف فعلي
            val products = g.db.products().allOnceIncludingArchived()
            val expenses = g.db.expenses().between(0, System.currentTimeMillis())
            val checks = g.checks.checks().first()
            val payments = g.db.payments().since(0)
            val plans = g.db.installments().plansOnce()
            val scanned = invoices.size + parties.size + products.size + expenses.size + checks.size + payments.size + plans.size

            val findings = ArrayList<Finding>(8)

            // 1) انحراف بنفورد على مبالغ الفواتير والمصروفات — دلالة شذوذ إحصائي (عينة ≥ 30)
            // [P33-P8] المبالغ قروش — تُحوَّل ريالاً (نفس الأرقام الدالة) لحدود SeriesMath القائمة
            val amounts = (invoices.map { it.total } + expenses.map { it.amount })
                .filter { it > 0L }
                .map { com.superbiz.app.util.Money.fromPiasters(it) }
            val dev = com.superbiz.app.domain.algo.SeriesMath.benfordDeviation(amounts)
            findings += Finding("benford", amounts.size, "%.3f".format(java.util.Locale.US, dev),
                if (amounts.size < 30) 1 else if (dev > 0.10) 2 else 0)

            // 2) فجوات ترقيم فواتير البيع — رقم مفقود قد يعني فاتورة محذوفة
            val numbers = invoices.filter { it.isSale }
                .mapNotNull { it.number.filter { c -> c.isDigit() }.toIntOrNull() }
            val gaps = com.superbiz.app.domain.algo.SeriesMath.numberingGaps(numbers)
            // الفراغ بعد الرفض قد يعني «مدى واسع مرفوض» لا «لا فجوات» —
            // تُميَّز الحالة صراحةً كي لا يطمئن المستخدم زوراً
            val wideRange = numbers.filter { it > 0 }.let { n ->
                n.size >= 2 && (n.max().toLong() - n.min().toLong()) > 100_000L
            }
            // [P6-M6-17 إصلاح] العبارة من الموارد (wideRangeMsg) — انظر أعلى scan
            findings += Finding("gaps", gaps.size,
                when {
                    wideRange -> wideRangeMsg
                    gaps.isEmpty() -> "—"
                    else -> gaps.sorted().take(12).joinToString(", ")
                },
                if (gaps.isEmpty() && !wideRange) 0 else 1)

            // 3) فواتير مكررة محتملة: نفس الطرف + نفس المبلغ + نفس اليوم
            // كان التقسيم لليوم بـUTC (قسمة ثابتة) فيفصل مساءً
            // ويضم فجراً — التقسيم الآن ببداية اليوم المحلي عبر TimeMath
            // [P33-P8] المبلغ قروش — مطابقة صحيحة تامة بلا round2
            val dupInv = invoices.filter { it.status < 3 }
                .groupBy { Triple(it.partyId, it.total,
                    com.superbiz.app.domain.algo.TimeMath.startOfDay(it.date) / 86_400_000L) }
                .values.count { it.size > 1 }
            findings += Finding("dup_inv", dupInv, "", if (dupInv > 0) 1 else 0)

            // 4) أطراف بلا أي حركة (لا فواتير ولا دفعات ولا شيكات ولا خطط)
            val active = invoices.map { it.partyId }.toSet() + payments.mapNotNull { it.partyId } +
                checks.map { it.partyId } + plans.map { it.partyId }
            val orphans = parties.count { it.id !in active }
            findings += Finding("orphans", orphans, "", if (orphans > 0) 1 else 0)

            // 5) قيم شاذة: فواتير/مصروفات صفرية أو سالبة — [P33-P8] مقارنات صحيحة تامة
            val anomaly = invoices.count { it.total <= 0L || it.discount < 0L } + expenses.count { it.amount <= 0L }
            findings += Finding("anomaly", anomaly, "", if (anomaly > 0) 2 else 0)

            // 6) منتجات مؤرشفة وبهما مخزون — مخزون غير مرئي في البيع
            val archStock = products.count { it.archived && it.stockQty > 0.0 }
            findings += Finding("arch_stock", archStock, "", if (archStock > 0) 1 else 0)

            // 7) أطراف متشابهة قد تكون سجلات مكررة (جارو-وينكلر بعد التطبيع)
            val dupParties = countSimilarParties(parties)
            findings += Finding("dup_parties", dupParties, "", if (dupParties > 0) 1 else 0)

            _report.value = HealthReport(scanned, findings, dev)
            } // withContext(Dispatchers.Default)
        } catch (e: Exception) {
            com.superbiz.app.core.ErrorCenter.report(e, "DataHealth")
        } finally {
            _scanning.value = false
        }
    }

    /** عدّ أزواج الأطراف المتشابهة — نفس منطق DebtsVM الضبابي (عينات حقيقية) */
    private fun countSimilarParties(list: List<Party>): Int {
        var n = 0
        for (i in list.indices) for (j in i + 1 until list.size) {
            val na = com.superbiz.app.domain.algo.TextMath.arabicNormalize(list[i].name)
            val nb = com.superbiz.app.domain.algo.TextMath.arabicNormalize(list[j].name)
            if (na.length >= 3 && nb.length >= 3 &&
                maxOf(
                    com.superbiz.app.domain.algo.TextMath.jaroWinkler(na, nb),
                    com.superbiz.app.domain.algo.TextMath.similarityRatio(na, nb)
                ) >= 0.87) n++
        }
        return n
    }

    /** نص تقرير قابل للمشاركة (واتساب/بريد) — يُبنى من النتائج الفعلية */
    fun shareText(): String {
        val r = _report.value ?: return ""
        // [P6-M6-17 إصلاح] كانت نصوص المشاركة عربية صلبة تتجاهل لغة التطبيق — كلها الآن من
        // الموارد (dh_share_*) بأسلوب الملف: getApplication + getString بلا تسريب نشاط
        val ctx = getApplication<Application>()
        val sb = StringBuilder()
        sb.appendLine(ctx.getString(R.string.dh_share_title))
        sb.appendLine(ctx.getString(R.string.dh_share_records, r.scannedRecords))
        for (f in r.findings) {
            val title = when (f.kind) {
                "benford" -> ctx.getString(R.string.dh_share_benford)
                "gaps" -> ctx.getString(R.string.dh_share_gaps)
                "dup_inv" -> ctx.getString(R.string.dh_share_dup_inv)
                "orphans" -> ctx.getString(R.string.dh_share_orphans)
                "anomaly" -> ctx.getString(R.string.dh_share_anomaly)
                "arch_stock" -> ctx.getString(R.string.dh_share_arch_stock)
                "dup_parties" -> ctx.getString(R.string.dh_share_dup_parties)
                else -> f.kind
            }
            val mark = when (f.severity) { 2 -> "✗"; 1 -> "!"; else -> "✓" }
            sb.appendLine("$mark $title: ${f.count}${if (f.detail.isNotBlank()) " (${f.detail})" else ""}")
        }
        return sb.toString()
    }
}

// ═════════ [P11-a] المفضّلات وبطاقة الموقع الجغرافي ═════════

/**
 * [P11-a] VM شاشة المفضّلات — يبني قائمتين من تدفق الأطراف الحي
 * favorites: المفضّلون فقط (الجديد في عمود favorite)، وallParties: كل
 * الأطراف غير المؤرشفة لقسم «إضافة إلى المفضلة». الكتابة (تفضيل/إزالة تفضيل،
 * حفظ/إزالة الإحداثيات) تحديثات موضعية عبر PartyDao مباشرة — تدفق Room
 * يُحدّث الواجهة تلقائياً بلا أي refresh يدوي (نمط ContactsSheet).
*/
class FavoritesVM(app: Application) : AndroidViewModel(app) {
    private val g = AppGraph.from(app)

    /** [P11-a] الأطراف المفضّلة (غير المؤرشفة — الاستعلام نفسه يرشّح) مرتّبة بالاسم */
    val favorites = g.ledger.parties().map { list -> list.filter { it.favorite } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** [P11-a] كل الأطراف النشطة — مصدر قسم «إضافة إلى المفضلة» (الترشيح في الشاشة بالبحث) */
    val allParties = g.ledger.parties()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** [P11-a] تفعيل/إلغاء التمييز كمفضّل — كتابة موضعية على IO */
    // [P20-FIX agent9]: launch(IO) خام بلا try/catch — فشل Room غير متوقع (قرص ممتلئ…)
    // كان يهرب غير معالج فيُسقط العملية؛ كل مسارات الكتابة الأخرى عبر launchSafe
    fun toggleFavorite(id: Long, fav: Boolean) = launchSafe {
        kotlinx.coroutines.withContext(Dispatchers.IO) { g.db.parties().setFavorite(id, fav) }
    }

    /** [P11-a] حفظ الإحداثيات — تمرير null للاثنين يزيل الموقع من البطاقة */
    fun setLocation(id: Long, lat: Double?, lng: Double?) = launchSafe {
        kotlinx.coroutines.withContext(Dispatchers.IO) { g.db.parties().setLocation(id, lat, lng) }
    }

    // ═══ [P13-a] ترتيب «الأقرب أولاً» ═══

    /** [P13-a] هل ترتيب المسافة مفعّل؟ — افتراضه معطّل */
    private val _routeSortEnabled = MutableStateFlow(false)
    val routeSortEnabled: StateFlow<Boolean> = _routeSortEnabled.asStateFlow()

    /** [P13-a] آخر موقع حالي التُقط بنجاح — يُعاد استخدامه عند إعادة التفعيل بلا التقاط جديد */
    private val _routeLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    val routeLocation: StateFlow<Pair<Double, Double>?> = _routeLocation.asStateFlow()

    /** [P13-a] التقاط جارٍ — تُظهره الواجهة كمؤقّت تقدم صغير بجانب رقاقة «الأقرب أولاً» */
    private val _capturingRouteLocation = MutableStateFlow(false)
    val capturingRouteLocation: StateFlow<Boolean> = _capturingRouteLocation.asStateFlow()

    /** [P13-a] توليد الطلب — يُبطل نتائج التقاط قديم إن عطّل المستخدم الترتيب أثناء الانتظار */
    private var routeRequestGen = 0

    /**
     * [P13-a] تفعيل/تعطيل ترتيب «الأقرب أولاً».
     *
     * التفعيل: موقع محفوظ من التقاط سابق ⇒ تفعيل فوري بلا التقاط جديد؛ وإلا التقاط
     * عبر المحرك المشترك LocationCapture (نمط بطاقة الموقع نفسه — العقد: المستدعي
     * يطلب الإذن قبل الاستدعاء، والمحرك لا يرمي أبداً ويسلّم النتيجة مرة واحدة على
     * الخيط الرئيسي). الفشل (خدمات معطّلة/مهلة 20 ث/بلا إذن) يُبقي الترتيب معطّلاً
     * ويرسل رسالة المستخدم عبر ErrorCenter — قناة الأخطاء الموحّدة (Snackbar من Nav.kt).
     *
     * التعطيل: إيقاف فوري + إبطال أي التقاط معلّق (توليد الطلب يمنع تسليمه).
     */
    fun toggleRouteSort(enable: Boolean) {
        if (!enable) {
            routeRequestGen++
            _capturingRouteLocation.value = false
            _routeSortEnabled.value = false
            return
        }
        if (_routeSortEnabled.value) return // مفعّل أصلاً
        _routeLocation.value?.let {
            _routeSortEnabled.value = true // موقع جاهز — بلا انتظار
            return
        }
        if (_capturingRouteLocation.value) return // منع الطلبات المزدوجة (مهلة المحرك 20 ثانية)
        val gen = ++routeRequestGen
        _capturingRouteLocation.value = true
        val app = getApplication<Application>()
        LocationCapture.capture(app) { res ->
            // المحرك يسلّم على الخيط الرئيسي دائماً (عقده) — تحديث الحالة هنا آمن
            if (gen != routeRequestGen) return@capture // أُبطل الطلب (عُطّل الترتيب أثناء الانتظار)
            _capturingRouteLocation.value = false
            when (res) {
                is LocationCapture.Result.Fixed -> {
                    _routeLocation.value = res.lat to res.lng
                    _routeSortEnabled.value = true
                }
                LocationCapture.Result.GpsOff -> ErrorCenter.warn(
                    "FavoritesVM", "route sort: location services off",
                    app.getString(R.string.fav_gps_off)
                )
                LocationCapture.Result.Failed -> ErrorCenter.warn(
                    "FavoritesVM", "route sort: capture failed",
                    app.getString(R.string.fav_location_failed)
                )
            }
        }
    }
}
