package com.superbiz.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.superbiz.app.R
import com.superbiz.app.domain.SmartChat
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.components.SubHeader
import com.superbiz.app.ui.theme.Cyan
import com.superbiz.app.ui.theme.Vio
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.util.Money
import com.superbiz.app.vm.ProVM
import com.superbiz.app.vm.SmartCoordinatorVM

/**
 * [H3-6] شاشة «المنسّق الذكي» — دردشة محلية واحدة تسأل تقاريرك بالعربية:
 *
 * كل جواب يُحسب على الجهاز (QueryParseMath + SmartChat فوق دفتر المستخدم
 * الحقيقي) — لا شبكة ولا نموذج سحابي ولا انتظار. الباب Pro يُقفل بقفل صادق
 * (نمط KpiBoard): يشرح ما خلفه ويقود إلى شاشة Pro، وفتح Pro يفعّل الدردشة
 * فوراً دون إعادة تشغيل (StateFlow حي).
 * سطر المصدر تحت كل جواب يعرض الأساس الخام — الذكاء أداة ثقة لا صندوق أسود.
 */
@Composable
fun SmartScreen(
    onBack: () -> Unit,
    openPro: () -> Unit,
    vm: SmartCoordinatorVM = viewModel(),
    proVM: ProVM = viewModel()
) {
    val g = glassColors()
    val pro by proVM.pro.collectAsState()
    val symbol by vm.symbol.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        SubHeader(stringResource(R.string.smart_title), onBack = onBack)

        if (!pro) {
            // القفل الصادق — يشرح ما خلف الباب ولا يزيّف
            Spacer(Modifier.height(8.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.smart_locked_title),
                        fontSize = 16.sp, fontWeight = FontWeight.Bold, color = g.textPrimary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.smart_locked_msg),
                        fontSize = 13.sp, color = g.textSecondary
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = openPro) {
                        Text(stringResource(R.string.smart_unlock_cta))
                    }
                }
            }
            return@Column
        }

        SmartChatPanel(vm = vm, symbol = symbol, modifier = Modifier.weight(1f))
    }
}

/** لوحة الدردشة الفعلية — مفصولة لتكون قابلة للاختبار بحالة وهمية مباشرة */
@Composable
fun SmartChatPanel(vm: SmartCoordinatorVM, symbol: String, modifier: Modifier = Modifier) {
    val g = glassColors()
    val chat by vm.chat.collectAsState()
    val thinking by vm.thinking.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // التمرير لآخر رسالة عند كل إضافة
    LaunchedEffect(chat.size, thinking) {
        if (chat.isNotEmpty()) listState.animateScrollToItem(chat.size - 1)
    }

    Column(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (chat.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.smart_intro),
                        fontSize = 13.sp, color = g.textSecondary,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        vm.suggestions().forEach { s ->
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(g.accent.copy(alpha = 0.12f))
                                    .clickable { vm.ask(s) }
                                    .padding(horizontal = 14.dp, vertical = 9.dp)
                            ) {
                                Text(s, fontSize = 13.sp, color = g.accent, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            items(chat) { msg ->
                if (msg.isUser) {
                    UserBubble(msg.question ?: "")
                } else {
                    AnswerBubble(msg.answer, symbol)
                }
            }
            if (thinking) {
                item {
                    Text(
                        stringResource(R.string.smart_thinking),
                        fontSize = 12.sp, color = g.textSecondary
                    )
                }
            }
        }

        // صف الإدخال
        Row(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(stringResource(R.string.smart_input_hint), fontSize = 13.sp) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = g.surface,
                    unfocusedContainerColor = g.surface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                enabled = input.isNotBlank(),
                onClick = {
                    vm.ask(input)
                    input = ""
                }
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.Send, stringResource(R.string.smart_send),
                    tint = if (input.isNotBlank()) g.accent else g.textSecondary
                )
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    val g = glassColors()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(g.accent.copy(alpha = 0.16f))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(text, fontSize = 13.5.sp, color = g.textPrimary)
        }
    }
}

@Composable
private fun AnswerBubble(answer: SmartChat.ChatAnswer?, symbol: String) {
    val g = glassColors()
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(4.dp)) {
            when (answer?.key) {
                "ans_sales" -> {
                    BubbleTitle(stringResource(R.string.ans_sales_title), Cyan)
                    KVRow(stringResource(R.string.ans_sales_total, Money.format(answer.numbers.getOrElse(0) { 0.0 }, symbol)))
                    KVRow(stringResource(R.string.ans_sales_count, answer.numbers.getOrElse(1) { 0.0 }.toInt()))
                }
                "ans_profits" -> {
                    BubbleTitle(stringResource(R.string.ans_profits_title), if (answer.numbers.getOrElse(0) { 0.0 } >= 0) com.superbiz.app.ui.theme.Green else com.superbiz.app.ui.theme.Red)
                    KVRow(stringResource(R.string.ans_profits_net, Money.format(answer.numbers.getOrElse(0) { 0.0 }, symbol)))
                    KVRow(stringResource(R.string.ans_profits_sales, Money.format(answer.numbers.getOrElse(1) { 0.0 }, symbol)))
                    KVRow(stringResource(R.string.ans_profits_expenses, Money.format(answer.numbers.getOrElse(2) { 0.0 }, symbol)))
                }
                "ans_receivables" -> {
                    BubbleTitle(stringResource(R.string.ans_receivables_title), com.superbiz.app.ui.theme.Amber)
                    KVRow(stringResource(R.string.ans_receivables_overdue, Money.format(answer.numbers.getOrElse(0) { 0.0 }, symbol)))
                    answer.numbers.drop(1).forEachIndexed { i, v ->
                        KVRow(stringResource(when (i) {
                            0 -> R.string.ans_aging_0
                            1 -> R.string.ans_aging_1
                            2 -> R.string.ans_aging_2
                            else -> R.string.ans_aging_3
                        }, Money.format(v, symbol)))
                    }
                }
                "ans_inventory" -> {
                    BubbleTitle(stringResource(R.string.ans_inventory_title), Cyan)
                    KVRow(stringResource(R.string.ans_inventory_low, answer.numbers.getOrElse(0) { 0.0 }.toInt()))
                    KVRow(stringResource(R.string.ans_inventory_value, Money.format(answer.numbers.getOrElse(1) { 0.0 }, symbol)))
                }
                "ans_inventory_item" -> {
                    BubbleTitle(stringResource(R.string.ans_item_title), Cyan)
                    KVRow(stringResource(R.string.ans_item_stock, answer.numbers.getOrElse(0) { 0.0 }))
                    KVRow(stringResource(R.string.ans_item_avg, answer.numbers.getOrElse(1) { 0.0 }))
                    KVRow(stringResource(R.string.ans_item_sold, answer.numbers.getOrElse(2) { 0.0 }))
                }
                "ans_party" -> {
                    val bal = answer.numbers.getOrElse(0) { 0.0 }
                    BubbleTitle(stringResource(R.string.ans_party_title), if (bal > 0) com.superbiz.app.ui.theme.Amber else com.superbiz.app.ui.theme.Green)
                    KVRow(stringResource(if (bal > 0) R.string.ans_party_owes else R.string.ans_party_credit, Money.format(kotlin.math.abs(bal), symbol)))
                }
                else -> {
                    BubbleTitle(stringResource(R.string.ans_nodata_title), Vio)
                    Text(
                        stringResource(R.string.ans_nodata_msg),
                        fontSize = 12.5.sp, color = g.textSecondary
                    )
                }
            }
            // سطر المصدر — الأساس الخام خلف الجواب (الذكاء أداة ثقة)
            if (answer != null && answer.key != "ans_nodata") {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.ans_basis, answer.basis),
                    fontSize = 9.5.sp, color = g.textSecondary.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun BubbleTitle(text: String, color: Color) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun KVRow(text: String) {
    Text(
        text, fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(vertical = 1.dp)
    )
}
