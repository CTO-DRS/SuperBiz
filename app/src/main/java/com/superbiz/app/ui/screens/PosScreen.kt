package com.superbiz.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PointOfSale
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.journeyapps.barcodescanner.ScanContract
import com.superbiz.app.MainActivity
import com.superbiz.app.R
import com.superbiz.app.data.db.Product
import com.superbiz.app.domain.PosCart
import com.superbiz.app.domain.algo.changeBreakdown
import com.superbiz.app.print.EscPos
import com.superbiz.app.print.ReceiptFactory
import com.superbiz.app.print.ReceiptPrintDialog
import com.superbiz.app.ui.components.Badge
import com.superbiz.app.ui.components.BizField
import com.superbiz.app.ui.components.EmptyState
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.components.SubHeader
import com.superbiz.app.ui.components.numberFieldOptions
import com.superbiz.app.ui.components.parseNum
import com.superbiz.app.ui.nav.AvatarView
import com.superbiz.app.ui.theme.Cyan
import com.superbiz.app.ui.theme.GreenDeep
import com.superbiz.app.ui.theme.RedDeep
import com.superbiz.app.ui.theme.Vio
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.util.Money
import com.superbiz.app.vm.AppVM
import com.superbiz.app.vm.PosVM
import com.superbiz.app.VMFactory

// فئات الفكّ النقدي لحاسبة الباقي — من فئة 500 حتى ربع العملة (0.25)
private val POS_CHANGE_DENOMS = listOf(500.0, 100.0, 50.0, 20.0, 10.0, 5.0, 1.0, 0.5, 0.25)

/**
 * نقطة البيع السريعة: شبكة منتجات تُلمس لإضافتها للسلة + مسح باركود مباشر،
 * ثم تحصيل نقدي (زبون نقدي تلقائي) أو بالذمم (اختيار عميل).
 * البيع النقدي يُرحَّل مدفوعاً بالكامل، وبيع الذمم يبقى مفتوحاً في دفتر الديون.
 */
@Composable
fun PosScreen(appVM: AppVM, nav: NavHostController) {
    val activity = LocalContext.current as? MainActivity ?: return
    val vm: PosVM = viewModel(factory = remember { VMFactory(activity) })
    val products by vm.filtered.collectAsState()
    val search by vm.search.collectAsState()
    val cart by vm.cart.collectAsState()
    val toastRes by vm.toast.collectAsState()
    val lastSale by vm.lastSale.collectAsState()
    // السلة المعلّقة المحفوظة في DataStore — تظهر شريحة استئناف عند وجودها
    val heldCart by vm.heldCart.collectAsState()
    // صفوف الأطراف الأخيرة + اختيار الطرف + وضع الخصم
    val recentParties by vm.recentParties.collectAsState()
    val selectedParty by vm.party.collectAsState()
    val symbol by appVM.symbol.collectAsState()
    val g = glassColors()
    // رؤى الموجة R11 لنقاط البيع (إيقاع اليوم/تسريب الخصم) — تظهر بسلة فارغة فقط
    // [P5-H9 إصلاح]: VMs الرؤى مشتركة على مستوى النشاط — كانت كل شاشة تنشئ نسختها وتشغل loadAll كاملاً (حتى ×8 تكلفة لكل جولة تنقل)
    val r11VM: com.superbiz.app.vm.R11InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    // VM الرؤى الذكية للموجة R12 — عبر R12Smart
    val r12VM: com.superbiz.app.vm.R12InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    val r13VM: com.superbiz.app.vm.R13InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    // VM الرؤى الذكية للموجة R14 — عبر R14Smart
    val r14VM: com.superbiz.app.vm.R14InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })
    val r15VM: com.superbiz.app.vm.R15InsightsVM = viewModel(viewModelStoreOwner = activity, factory = remember { VMFactory(activity) })

    var showCheckout by remember { mutableStateOf(false) }
    // حوار البيع السريع بمبلغ حر
    var showFreeSale by remember { mutableStateOf(false) }
    // تأكيد استبدال سلة غير فارغة بالبيع السريع
    var confirmFreeSale by remember { mutableStateOf(false) }
    // تأكيد قبل إفراغ السلة — إجراء مدمّر بلا رجعة
    var confirmClearCart by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // استهلاك إحالة إعادة الطلب القادمة من شاشة الفواتير (مرة واحدة لكل فتح للشاشة)
    LaunchedEffect(Unit) { vm.consumeReorder() }

    // مسح باركود → إضافة مباشرة للسلة
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { vm.addByBarcode(it) }
    }

    // تنبيهات عابرة
    toastRes?.let { res ->
        LaunchedEffect(res, cart) {
            android.widget.Toast.makeText(context, context.getString(res), android.widget.Toast.LENGTH_SHORT).show()
            vm.consumeToast()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp)
        ) {
            SubHeader(stringResourceCompat(R.string.pos_title)) {
                // البيع السريع بمبلغ حر — فاتورة نقدية فورية بسطر وحيد
                // كان الزر يُفرغ السلة الحالية بصمت — سلة بعشرة أصناف
                // تُستبدل بسطر بيع حر بلا أي تأكيد. سلة غير فارغة تطلب تأكيداً صريحاً أولاً
                FilterPill(stringResourceCompat(R.string.pos_free_sale), selected = false) {
                    if (cart.isNotEmpty()) confirmFreeSale = true else showFreeSale = true
                }
                Spacer(Modifier.width(4.dp))
                // تعليق السلة — تُحفظ فعلياً في DataStore ثم تُفرَّغ لخدمة الزبون التالي
                FilterPill(stringResourceCompat(R.string.pos_hold_cart), selected = false) {
                    vm.holdCart { ok ->
                        if (ok) android.widget.Toast.makeText(
                            context, context.getString(R.string.pos_cart_held),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = { confirmClearCart = true }) {
                    Icon(Icons.Rounded.DeleteSweep, stringResourceCompat(R.string.a11y_clear_cart), tint = g.textSecondary)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    BizField(search, { vm.search.value = it }, stringResourceCompat(R.string.search))
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { scanLauncher.launch(posScanOptions(context)) }) {
                    Icon(Icons.Rounded.QrCodeScanner, stringResourceCompat(R.string.a11y_scan_barcode), tint = g.accent2)
                }
            }
            Spacer(Modifier.height(8.dp))

            // بطاقتا الذكاء في POS — تُخفَيان عند بدء البيع كي لا تشاغلا عن السلة
            // [P44-K1] جولة 5: المكدس المخصص — ترتيب/إظهار المجموعات الخمس بافتضاض المستخدم
            if (cart.isEmpty()) {
                com.superbiz.app.ui.insights.InsightsStack(
                    com.superbiz.app.domain.DashboardPrefsP44.SCREEN_POS,
                    listOf(
                        com.superbiz.app.ui.insights.insightsGroup("r11") { com.superbiz.app.ui.insights.PosR11Card(appVM, r11VM) },
                        com.superbiz.app.ui.insights.insightsGroup("r12") { com.superbiz.app.ui.insights.PosR12Card(r12VM) },
                        com.superbiz.app.ui.insights.insightsGroup("r13") { com.superbiz.app.ui.insights.PosR13Card(appVM, r13VM) },
                        com.superbiz.app.ui.insights.insightsGroup("r14") { com.superbiz.app.ui.insights.PosR14Card(r14VM) },
                        com.superbiz.app.ui.insights.insightsGroup("r15") { com.superbiz.app.ui.insights.PosR15Card(r15VM) },
                    )
                )
                Spacer(Modifier.height(8.dp))
            }

            if (products.isEmpty()) {
                EmptyState(stringResourceCompat(R.string.pos_no_products), Icons.Rounded.PointOfSale)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(products, key = { it.id }) { p -> ProductTile(p) { vm.addToCart(p) } }
                }
            }

            // استئناف السلة المعلّقة — لا تظهر إلا عند وجود سلة محفوظة فعلياً
            heldCart?.let { h ->
                Spacer(Modifier.height(8.dp))
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.resumeHeld { } }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null, tint = GreenDeep, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            // [P33-P8]: heldCart.total يبقى ريال Double من DataStore (توافق النسخ الاحتياطي) — format كما هو
                            stringResourceCompat(R.string.pos_resume_cart, h.lineCount, Money.format(h.total, symbol)),
                            color = g.textPrimary, fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // صفوف الأطراف الأخيرة — آخر 5 أطراف تفاعل من الفواتير/التحصيلات،
            // النقر يختار الطرف للسلة (والنقر عليه مرة ثانية يُلغي الاختيار)
            if (recentParties.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    Text(
                        stringResourceCompat(R.string.pos_recent_parties),
                        fontSize = 11.sp, color = g.textSecondary
                    )
                    Spacer(Modifier.width(6.dp))
                    recentParties.forEach { p ->
                        val isSelected = selectedParty?.id == p.id
                        Text(
                            p.name,
                            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (isSelected) Color.White else g.textPrimary,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .background(
                                    if (isSelected) g.accent else g.surface,
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    val willSelect = selectedParty?.id != p.id
                                    vm.selectParty(p)
                                    android.widget.Toast.makeText(
                                        context,
                                        context.getString(
                                            if (willSelect) R.string.pos_party_selected else R.string.pos_party_cleared,
                                            p.name
                                        ),
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            // شريط السلة الثابت أسفل الشبكة
            CartBar(
                units = PosCart.units(cart),
                // [P33-P8]: المجموع قروش — العرض عبر formatP
                total = Money.formatP(PosCart.subtotal(cart), symbol),
                enabled = cart.isNotEmpty(),
                color = g.accent
            ) { showCheckout = true }
            Spacer(Modifier.height(96.dp)) // مساحة الدوك العائم
        }

        if (showCheckout) {
            CheckoutSheet(
                vm = vm, appVM = appVM, symbol = symbol,
                onDismiss = { showCheckout = false }
            )
        }

        // تأكيد إفراغ السلة — نفس نمط حوارات الملف
        if (confirmClearCart) {
            AlertDialog(
                onDismissRequest = { confirmClearCart = false },
                containerColor = g.surfaceStrong,
                title = { Text(stringResourceCompat(R.string.confirm), color = g.textPrimary) },
                text = { Text(stringResourceCompat(R.string.confirm_clear_cart), color = g.textPrimary) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmClearCart = false
                        vm.clearCart()
                    }) {
                        Text(stringResourceCompat(R.string.confirm_yes), color = g.accent, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClearCart = false }) {
                        Text(stringResourceCompat(R.string.confirm_no), color = g.textSecondary)
                    }
                }
            )
        }

        // تأكيد استبدال سلة غير فارغة بالبيع السريع — كان الاستبدال صامتاً
        if (confirmFreeSale) {
            AlertDialog(
                onDismissRequest = { confirmFreeSale = false },
                containerColor = g.surfaceStrong,
                title = { Text(stringResourceCompat(R.string.confirm), color = g.textPrimary) },
                text = { Text(stringResourceCompat(R.string.pos_free_sale_confirm), color = g.textPrimary) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmFreeSale = false
                        showFreeSale = true
                    }) {
                        Text(stringResourceCompat(R.string.confirm_yes), color = g.accent, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmFreeSale = false }) {
                        Text(stringResourceCompat(R.string.confirm_no), color = g.textSecondary)
                    }
                }
            )
        }

        // حوار البيع السريع بمبلغ حر — وظيفة P4-1 رقم 2
        // [P40-M5] الجسم المستخرج حرفياً إلى FreeSaleDialog (internal) لاختباره
        // تركيبياً (FreeSaleP40Test) — نفس الحالة والترتيب وسلوك التأكيد
        if (showFreeSale) {
            FreeSaleDialog(
                onDismiss = { showFreeSale = false },
                onSale = { amount, desc -> vm.quickSale(amount, desc) }
            )
        }

        // نافذة نجاح العملية
        if (lastSale > 0) {
            SaleSuccessDialog(
                vm = vm, appVM = appVM, invoiceId = lastSale,
                onDismiss = { vm.closeSuccess() }
            )
        }
    }
}

/** إعدادات قارئ نقطة البيع */
private fun posScanOptions(context: android.content.Context) =
    com.journeyapps.barcodescanner.ScanOptions().apply {
        setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.ALL_CODE_TYPES)
        setPrompt(context.getString(R.string.scan_prompt))
        setBeepEnabled(true)
        setOrientationLocked(true)
    }

/**
 * [P40-M5] حوار البيع السريع بمبلغ حر — مستخرج حرفياً من PosScreen (كان inline
 * داخل جسم الشاشة) كي يُختبر تركيبياً بـRobolectric-Compose: نفس الحالتين
 * المحليتين، نفس التحقق (parseToPiasters > 0)، نفس نص الفشل الصادق، ونفس
 * ترتيب التأكيد (إغلاق الحوار ثم quickSale). لا تغيير سلوكياً إطلاقاً.
 */
@Composable
internal fun FreeSaleDialog(
    onDismiss: () -> Unit,
    onSale: (amount: String, desc: String) -> Unit
) {
    val g = glassColors()
    var amountText by remember { mutableStateOf("") }
    var descText by remember { mutableStateOf("") }
    // [P33-P8]: حقل مبلغ → قروش عبر parseToPiasters (كان parseNum ريال Double) — نفس عتبة الـVM
    val amount = Money.parseToPiasters(amountText)
    val amountValid = amount > 0L
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = g.surfaceStrong,
        title = { Text(stringResourceCompat(R.string.pos_free_sale_title), color = g.textPrimary) },
        text = {
            Column {
                BizField(
                    amountText, { amountText = it },
                    stringResourceCompat(R.string.pos_free_amount),
                    keyboard = numberFieldOptions()
                )
                Spacer(Modifier.height(8.dp))
                BizField(
                    descText, { descText = it },
                    stringResourceCompat(R.string.pos_free_sale_desc)
                )
                // حالة فشل صادقة: مبلغ غير صالح يُبيَّن فوراً ولا يُقبل الحفظ
                if (amountText.isNotBlank() && !amountValid) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResourceCompat(R.string.pos_free_amount_invalid),
                        color = RedDeep, fontSize = 11.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = amountValid,
                onClick = {
                    onDismiss()
                    onSale(amountText, descText)
                }
            ) {
                Text(
                    stringResourceCompat(R.string.confirm),
                    color = if (amountValid) g.accent else g.textSecondary,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResourceCompat(R.string.cancel), color = g.textSecondary)
            }
        }
    )
}

@Composable
private fun ProductTile(p: Product, onClick: () -> Unit) {
    val g = glassColors()
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                p.name, style = MaterialTheme.typography.bodyMedium,
                color = g.textPrimary, fontWeight = FontWeight.Bold,
                maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                // [P33-P8]: سعر البيع قروش — العرض عبر formatP
                Money.formatP(p.salePrice, ""), fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold, color = g.accent
            )
            Text(
                Money.num(p.stockQty) + " " + p.unit, fontSize = 10.sp,
                color = if (p.isLow) RedDeep else g.textSecondary
            )
        }
    }
}

@Composable
private fun CartBar(units: Double, total: String, enabled: Boolean, color: Color, onCheckout: () -> Unit) {
    val g = glassColors()
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.PointOfSale, null, tint = g.accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResourceCompat(R.string.pos_cart_units, Money.num(units)),
                    fontSize = 12.sp, color = g.textSecondary)
                Text(total, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = g.textPrimary)
            }
            Box(
                Modifier
                    .background(
                        Brush.linearGradient(listOf(g.accent, g.accent2)),
                        RoundedCornerShape(14.dp)
                    )
                    .clickable(enabled) { onCheckout() }
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text(
                    stringResourceCompat(R.string.pos_checkout), color = Color.White,
                    fontWeight = FontWeight.Bold, fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
// [P40-M5] private→internal: حوار الدفع الحرجة مسار POS يُختبر تركيبياً
// (CheckoutSheetP40Test) — لا تغيير سلوكياً ولا في التواقيع
internal fun CheckoutSheet(vm: PosVM, appVM: AppVM, symbol: String, onDismiss: () -> Unit) {
    val g = glassColors()
    val cart by vm.cart.collectAsState()
    val discount by vm.discount.collectAsState()
    // وضع الخصم (مبلغ/نسبة) + رفض غير الصالح صراحة
    val discountMode by vm.discountMode.collectAsState()
    val payMode by vm.payMode.collectAsState()
    val party by vm.party.collectAsState()
    val customers by vm.customers.collectAsState()
    // المبلغ المستلم نقداً — يُحسب منه الباقي وفكّ الفئات
    val tendered by vm.tendered.collectAsState()
    var totals by remember { mutableStateOf<com.superbiz.app.domain.PosTotals?>(null) }
    var pickParty by remember { mutableStateOf(false) }
    // [P10] استيراد العملاء من جهات الاتصال من داخل منتقي نقطة البيع
    var showContacts by remember { mutableStateOf(false) }
    // [P46-W1] جولة 7 — حالة الولاء في الورقة
    val loyaltyOn by vm.loyaltyEnabled.collectAsState()
    val loyaltyBalance by vm.loyaltyBalance.collectAsState()
    val couponCode by vm.couponCode.collectAsState()
    val appliedCoupon by vm.appliedCoupon.collectAsState()
    val redeemText by vm.redeemText.collectAsState()

    LaunchedEffect(cart, discount, discountMode, couponCode, redeemText, appliedCoupon, party, loyaltyBalance) { totals = vm.totals() }
    // [P46-W1] رصيد النقاط يُعاد تحميله عند تغيّر الطرف — الورقة تبقى صادقة بلا refresh يدوي
    LaunchedEffect(party?.id) { vm.refreshLoyaltyBalance() }
    // حارس هامش الخصم — أقصى خصم آمن من تكلفة السلة الفعلية (R7Smart)
    val smartCtx = LocalContext.current
    // [P5-H9 إصلاح]: مشاركة نسخة SmartInsightsVM على مستوى النشاط — كانت نسخة حوار الدفع
    // مستقلة بتحميل كامل إضافي (السياق هنا هو MainActivity نفسه، مالك مخزن النماذج)
    val smartVM: com.superbiz.app.vm.SmartInsightsVM = viewModel(
        viewModelStoreOwner = smartCtx as androidx.lifecycle.ViewModelStoreOwner,
        factory = remember { VMFactory(smartCtx) }
    )
    val smartTargetMargin by smartVM.targetMargin.collectAsState()
    // [P20-FIX agent1]: totals أُضيف للمفاتيح — كان يُحسب من أصفار عند أول تركيب (totals=null)
    // ولا يُعاد حسابه حين تصل المجاميع، ويبقى متأخراً خطوة بعد كل تعديل كميات
    val discountSafePct: Int? = remember(cart, discount, discountMode, smartTargetMargin, totals) {
        val d = parseNum(discount)
        // [P33-P8]: المجموع وأساس التكلفة قروش — يُحوَّلان إلى ريال لحساب النسبة فقط (الهامش نسبة Double)
        if (d <= 0.0) null else com.superbiz.app.domain.algo.PriceMath.maxSafeDiscountPct(
            com.superbiz.app.util.Money.fromPiasters(totals?.subtotal ?: 0L),
            com.superbiz.app.util.Money.fromPiasters(totals?.let { it.subtotal - (it.profit ?: 0L) } ?: 0L),
            smartTargetMargin.coerceIn(0.0, 0.99)
        ).let { (it + 0.5).toInt() }
    }
    val discountExceeded: Int? = remember(discount, discountMode, discountSafePct, totals) {
        val d = parseNum(discount)
        // قيمة محلية بدل !! — نفس الدلالة بلا مسار انهيار
        val sub = totals?.subtotal
        // [P33-P8]: المجموع قروش — نسبة الخصم تُحسب قروشاً على قروش (كان يمزج ريال الإدخال بقروش المجموع)
        if (d <= 0.0 || discountSafePct == null || sub == null || sub <= 0L) null
        else {
            val pct = if (discountMode == 1) d else Money.toPiasters(d) / sub.toDouble() * 100.0
            if (pct > discountSafePct) pct.toInt() else null
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable { onDismiss() }
    ) {
        GlassCard(
            corner = 28.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .imePadding()
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    // [P20-FIX agent1]: كان clickable(enabled=false){} رميّة ميتة — enabled=false
                    // لا يضيف معالج لمس فتصعد النقرة للجدر وتُغلق شيت الدفع أثناء الكتابة.
                    // ابتلاع فعلي: interactionSource + indication=null
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) { }
                    .padding(18.dp)
            ) {
                Text(stringResourceCompat(R.string.pos_checkout),
                    style = MaterialTheme.typography.titleLarge, color = g.textPrimary)
                Spacer(Modifier.height(12.dp))

                // سطور السلة مع تحكم بالكمية
                LazyColumn(
                    modifier = Modifier.height((cart.size.coerceAtLeast(1) * 54).dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // المفتاح كان productId ?: -1L — سطران بلا منتج (بيع حر/
                    // إعادة طلب) يتشاركان -1 فيُسقطان التركيب بـ IllegalArgumentException.
                    // مفاتيح مركّبة: p<id> للمنتجات الفريدة (الإضافة تدمجها) وf<فهرس> لسطور البيع الحر
                    itemsIndexed(cart, key = { idx, l -> l.productId?.let { "p$it" } ?: "f$idx" }) { idx, l ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(l.name, color = g.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                // [P33-P8]: سعر الوحدة قروش (formatP) والكمية تبقى Double (num)
                                Text(Money.formatP(l.unitPrice, symbol) + " × " + Money.num(l.qty),
                                    fontSize = 11.sp, color = g.textSecondary)
                            }
                            // عمليات بالمؤشر — كان setQty(null) يغيّر كل سطور
                            // البيع الحر دفعة وحدة، وremove(null) زراً ميتاً عليها
                            IconButton(onClick = { vm.setQtyAt(idx, l.qty - 1) }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Rounded.Remove, stringResourceCompat(R.string.a11y_qty_decrease), tint = g.textSecondary, modifier = Modifier.size(16.dp))
                            }
                            Text(Money.num(l.qty), color = g.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            IconButton(onClick = { vm.setQtyAt(idx, l.qty + 1) }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Rounded.Add, stringResourceCompat(R.string.a11y_qty_increase), tint = g.accent, modifier = Modifier.size(16.dp))
                            }
                            IconButton(onClick = { vm.removeLineAt(idx) }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Rounded.DeleteSweep, stringResourceCompat(R.string.a11y_remove_line), tint = RedDeep, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                // معاينة الربح الحي للسلة — يُخفى صادقاً إذا كل البنود بلا تكلفة
                totals?.profit?.let { pf ->
                    Text(
                        // [P33-P8]: الربح قروش — العرض عبر formatP
                        stringResourceCompat(R.string.pos_cart_profit, Money.formatP(pf, symbol)),
                        color = if (pf >= 0) GreenDeep else RedDeep,
                        fontWeight = FontWeight.Bold, fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // أسعار الفصل
                totals?.let { t ->
                    Row {
                        Text(stringResourceCompat(R.string.subtotal), color = g.textSecondary, modifier = Modifier.weight(1f))
                        // [P33-P8]: كل مبالغ PosTotals قروش — العرض عبر formatP
                        Text(Money.formatP(t.subtotal, symbol), color = g.textPrimary, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    // حقل الخصم + رقاقتا الوضع (مبلغ/نسبة) — وظيفة P4-1 رقم 1
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(110.dp)) {
                            BizField(discount, { vm.discount.value = it },
                                stringResourceCompat(R.string.pos_discount),
                                keyboard = numberFieldOptions())
                        }
                        Spacer(Modifier.width(6.dp))
                        FilterPill(stringResourceCompat(R.string.pos_disc_mode_amount), discountMode == 0) {
                            vm.discountMode.value = 0
                        }
                        Spacer(Modifier.width(4.dp))
                        FilterPill(stringResourceCompat(R.string.pos_disc_mode_percent), discountMode == 1) {
                            vm.discountMode.value = 1
                        }
                    }
                    // حارس هامش الخصم — تحذير قبل رفض الصلاحية
                    com.superbiz.app.ui.insights.PosDiscountGuard(discountExceeded)
                    // رفض صريح للخصم غير الصالح (سالب أو أكبر من الإجمالي أو نسبة > 100)
                    if (discount.isNotBlank() &&
                        com.superbiz.app.domain.InvoiceDiscount.resolve(
                            parseNum(discount), discountMode, t.subtotal
                        ) == null
                    ) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResourceCompat(R.string.pos_discount_rejected),
                            color = RedDeep, fontWeight = FontWeight.Bold, fontSize = 11.sp
                        )
                    }
                    // سطر الخصم المُطبَّق فعلاً — يظهر فقط عند وجود خصم صالح
                    // [P33-P8]: مساواة صحيحة بدل عتبة الفلواط (القروش صحيحة)
                    if (t.discount > 0L) {
                        Spacer(Modifier.height(4.dp))
                        Row {
                            Text(stringResourceCompat(R.string.pos_discount), color = g.textSecondary, modifier = Modifier.weight(1f))
                            Text("-" + Money.formatP(t.discount, symbol), color = RedDeep, fontWeight = FontWeight.Bold) // [P33-P8]
                        }
                    }
                    // ══ [P46-W1] جولة 7 — الولاء والكوبونات: يظهران فقط عند التفعيل ووجود طرف ══
                    if (loyaltyOn && party != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResourceCompat(R.string.loyalty_points_chip, loyaltyBalance.toString()),
                            color = g.accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                BizField(couponCode, { vm.couponCode.value = it },
                                    stringResourceCompat(R.string.loyalty_coupon_hint))
                            }
                            Spacer(Modifier.width(6.dp))
                            FilterPill(stringResourceCompat(R.string.loyalty_coupon_apply), false) {
                                val cartBase = com.superbiz.app.domain.InvoiceDiscount.resolve(
                                    parseNum(discount), discountMode, t.subtotal
                                )?.coerceAtLeast(0L) ?: 0L
                                vm.applyCoupon((t.subtotal - cartBase).coerceAtLeast(0L))
                            }
                        }
                        if (appliedCoupon != null) {
                            Spacer(Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResourceCompat(R.string.loyalty_coupon_applied, couponCode),
                                    color = GreenDeep, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                FilterPill(stringResourceCompat(R.string.loyalty_coupon_remove), false) { vm.clearCoupon() }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        BizField(redeemText, { vm.redeemText.value = it },
                            stringResourceCompat(R.string.loyalty_redeem_hint), keyboard = numberFieldOptions())
                    }
                    Spacer(Modifier.height(6.dp))
                    Row {
                        Text(stringResourceCompat(R.string.tax) + " (" + Money.num(t.taxRate) + "%)", color = g.textSecondary, modifier = Modifier.weight(1f))
                        // [P33-P8]: الضريبة قروش — taxRate يبقى Double (num)
                        Text(Money.formatP(t.tax, symbol), color = g.textPrimary)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResourceCompat(R.string.total), color = g.textSecondary, modifier = Modifier.weight(1f))
                        Text(
                            // [P33-P8]: الإجمالي قروش
                            Money.formatP(t.total, symbol),
                            fontWeight = FontWeight.ExtraBold, fontSize = 20.sp,
                            color = g.accent
                        )
                    }

                    // حاسبة الباقي وفكّ الفئات — وضع الدفع النقدي فقط
                    if (payMode == 0) {
                        Spacer(Modifier.height(8.dp))
                        BizField(
                            tendered, { vm.tendered.value = it },
                            stringResourceCompat(R.string.pos_tendered),
                            keyboard = numberFieldOptions()
                        )
                        // [P33-P8]: المقبوض والباقي بالقروش — الحساب صحيح كلياً بلا كسور
                        val received = Money.parseToPiasters(tendered)
                        if (received > 0L) {
                            val change = received - t.total
                            Spacer(Modifier.height(6.dp))
                            if (change >= 0L) {
                                Text(
                                    stringResourceCompat(R.string.pos_change, Money.formatP(change, symbol)),
                                    color = GreenDeep, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp
                                )
                            } else {
                                Text(
                                    stringResourceCompat(R.string.pos_insufficient),
                                    color = RedDeep, fontWeight = FontWeight.Bold, fontSize = 13.sp
                                )
                            }
                            if (change > 0L) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    stringResourceCompat(R.string.pos_change_breakdown),
                                    fontSize = 11.sp, color = g.textSecondary
                                )
                                Spacer(Modifier.height(4.dp))
                                // [P33-P8]: فكّ الفئات يبقى بفئات الريال Double — الباقي قروش يُحوَّل للعرض فقط
                                val pieces = remember(change) { changeBreakdown(Money.fromPiasters(change), POS_CHANGE_DENOMS) }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.horizontalScroll(rememberScrollState())
                                ) {
                                    for (pc in pieces.pieces) {
                                        Text(
                                            "×" + pc.second.toString() + " " + Money.num(pc.first),
                                            fontSize = 12.sp, color = g.textPrimary, fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier
                                                .background(g.accent.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                // نمط الدفع
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill(stringResourceCompat(R.string.pos_cash), payMode == 0) { vm.payMode.value = 0 }
                    FilterPill(stringResourceCompat(R.string.pos_debt), payMode == 1) { vm.payMode.value = 1 }
                }
                if (payMode == 1) {
                    Spacer(Modifier.height(8.dp))
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { pickParty = true }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Person, null, tint = g.accent, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                party?.name ?: stringResourceCompat(R.string.pos_pick_party),
                                color = if (party == null) g.textSecondary else g.textPrimary,
                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(listOf(GreenDeep, Cyan)),
                            RoundedCornerShape(14.dp)
                        )
                        // كان نقص المقبوض النقدي تحذيراً بصرياً فقط
                        // والبيع يُرحَّل مدفوعاً بالكامل — التأكيد الآن معطّل حتى اكتمال المبلغ
                        .clickable(
                            cart.isNotEmpty() && (payMode == 0 || party != null) &&
                                // [P33-P8]: مقارنة قروش بقروش — كان يمزج ريال الإدخال بقروش الإجمالي
                                !(payMode == 0 && Money.parseToPiasters(tendered) > 0L && Money.parseToPiasters(tendered) < (totals?.total ?: 0L))
                        ) {
                            onDismiss()
                            vm.checkout()
                        }
                        .padding(vertical = 13.dp)
                ) {
                    Text(
                        stringResourceCompat(R.string.pos_confirm),
                        color = Color.White, fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    if (pickParty) {
        PickerDialog(title = stringResourceCompat(R.string.pos_pick_party), onDismiss = { pickParty = false }) {
            Column(Modifier.fillMaxWidth()) {
                // [P10] استيراد من جهات الاتصال — يغلق المنتقي مؤقتاً ويفتح لوحة الاستيراد
                ActionPill(stringResourceCompat(R.string.contacts_pick_import), Vio) {
                    pickParty = false
                    showContacts = true
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn {
                    items(customers, key = { it.id }) { c ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.party.value = c; pickParty = false }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(c.name, color = g.textPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // [P10] لوحة استيراد جهات الاتصال — تدفق Room التفاعلي يحدّث العملاء تلقائياً،
    // وعند الإغلاق يعاد فتح المنتقي لاختيار الطرف المستورد فوراً
    if (showContacts) {
        ContactsSheet(
            onImported = { },
            onDismiss = { showContacts = false; pickParty = true }
        )
    }
}

@Composable
private fun SaleSuccessDialog(vm: PosVM, appVM: AppVM, invoiceId: Long, onDismiss: () -> Unit) {
    val g = glassColors()
    // اهتزاز تأكيد البيع — يخضع لإعداد «اهتزاز التأكيد» من مركز الإعدادات
    val ctxForHaptic = LocalContext.current
    LaunchedEffect(Unit) {
        if (com.superbiz.app.core.AppPrefs.hapticsEnabled) {
            // Vibrator أُهمل لصالح VibratorManager منذ API 31 — مساران موثقان
            val vb: android.os.Vibrator? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val mgr = androidx.core.content.ContextCompat.getSystemService(
                    ctxForHaptic, android.os.VibratorManager::class.java
                )
                mgr?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                androidx.core.content.ContextCompat.getSystemService(ctxForHaptic, android.os.Vibrator::class.java)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                vb?.vibrate(android.os.VibrationEffect.createOneShot(40, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") vb?.vibrate(40)
        }
    }
    var pendingPdf by remember { mutableStateOf(false) }
    var pendingPrint by remember { mutableStateOf(false) }
    var printReceipt by remember { mutableStateOf<EscPos.Receipt?>(null) }
    val activity = LocalContext.current as? MainActivity

    // تفاصيل الفاتورة عند الحاجة
    var number by remember { mutableStateOf("") }
    var total by remember { mutableStateOf("") }
    val symbol by appVM.symbol.collectAsState()
    LaunchedEffect(invoiceId) {
        val inv = vm.invoiceOf(invoiceId)
        number = inv?.number ?: ""
        // [P33-P8]: إجمالي الفاتورة قروش
        total = inv?.let { Money.formatP(it.total, symbol) } ?: ""
    }

    LaunchedEffect(pendingPdf) {
        if (!pendingPdf) return@LaunchedEffect
        pendingPdf = false
        val act = activity ?: return@LaunchedEffect
        try {
            val full = vm.fullInvoice(invoiceId) ?: return@LaunchedEffect
            val graph = com.superbiz.app.AppGraph.from(act.application)
            // بناء PDF على Dispatchers.IO — المشاركة بعده على الخيط الرئيسي
            val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // [P9-9a-ZATCA] الرقم الضريبي من نفس اللقطة لرسم QR الفاتورة الإلكترونية
                val st = graph.settings.snapshot()
                com.superbiz.app.pdf.InvoicePdf.render(
                    act, full.first, full.second,
                    vm.partyOf(full.first.partyId),
                    st.businessName.ifBlank { act.getString(R.string.business_default) },
                    symbol, appVM.avatarBitmap(), st.taxNumber
                )
            }
            com.superbiz.app.pdf.InvoicePdf.share(act, file)
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                act, act.getString(R.string.pdf_error),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    // تجهيز إيصال حراري عند الطلب
    LaunchedEffect(pendingPrint) {
        if (!pendingPrint) return@LaunchedEffect
        pendingPrint = false
        val act = activity ?: return@LaunchedEffect
        try {
            val full = vm.fullInvoice(invoiceId) ?: return@LaunchedEffect
            val graph = com.superbiz.app.AppGraph.from(act.application)
            // [P38-Z2] لقطة واحدة تُغذّي الاسم والرقم الضريبي — QR الإيصال الحراري
            // يُبنى من مصدر الحقيقة نفسه الذي يُبنى منه QR فاتورة PDF
            val st = graph.settings.snapshot()
            printReceipt = ReceiptFactory.fromInvoice(
                act, full.first, full.second,
                vm.partyOf(full.first.partyId),
                st.businessName, symbol, st.taxNumber
            )
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                act, act.getString(R.string.print_fail, e.message ?: ""),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
    printReceipt?.let { rc ->
        ReceiptPrintDialog(receipt = rc, onDismiss = { printReceipt = null })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = g.surfaceStrong,
        title = { Text(stringResourceCompat(R.string.pos_done), color = GreenDeep) },
        text = {
            Text(
                stringResourceCompat(R.string.pos_done_details, number, total),
                color = g.textPrimary
            )
        },
        confirmButton = {
            Row {
                TextButton(onClick = { pendingPrint = true }) {
                    Text(stringResourceCompat(R.string.print_receipt), color = GreenDeep, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { pendingPdf = true }) {
                    Text(stringResourceCompat(R.string.pos_share_pdf), color = g.accent, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResourceCompat(R.string.close), color = g.textSecondary)
            }
        }
    )
}
