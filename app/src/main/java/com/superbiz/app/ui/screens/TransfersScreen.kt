package com.superbiz.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.superbiz.app.R
import com.superbiz.app.data.db.StockTransferEntity
import com.superbiz.app.data.db.StockTransferLineEntity
import com.superbiz.app.data.repo.TransferRepo
import com.superbiz.app.ui.components.EmptyState
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.components.SubHeader
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.vm.TransfersVM

private fun fmtDate(ts: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(ts))

private fun fmtQty(q: Double): String = if (q % 1.0 == 0.0) q.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, q)

@Composable
private fun errText(code: String): String = stringResource(
    when (code) {
        "same" -> R.string.transfer_err_same
        "qty" -> R.string.transfer_err_qty
        "stock" -> R.string.transfer_err_stock
        "denied" -> R.string.transfer_err_denied
        "product" -> R.string.transfer_err_product
        else -> R.string.transfer_err_other
    }
)

/**
 * [A2] شاشة التحويلات بين الفروع: قائمة بالحالة الصريحة (قيد النقل / مُستلم / ملغى) مع استلام وإلغاء
 * لقيد النقل، وإنشاء تحويل جديد (من/إلى/صنف/كمية). كل الكتابات تمر عبر TransferRepo (INVENTORY_MANAGE).
 */
@Composable
fun TransfersScreen(onBack: () -> Unit) {
    val g = glassColors()
    val vm: TransfersVM = viewModel()
    val transfers by vm.transfers.collectAsState()
    val branches by vm.branches.collectAsState()
    val products by vm.products.collectAsState()
    var showNew by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val bName = remember(branches) { branches.associate { it.id to it.name } }
    val pName = remember(products) { products.associate { it.id to it.name } }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        SubHeader(stringResource(R.string.transfers_title), onBack = onBack)
        Spacer(Modifier.height(8.dp))
        Button(onClick = { showNew = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.transfer_new))
        }
        error?.let {
            Spacer(Modifier.height(6.dp))
            Text(errText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))
        if (transfers.isEmpty()) {
            EmptyState(stringResource(R.string.transfers_empty))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(transfers, key = { it.id }) { t ->
                    TransferCard(t, bName, pName, vm, onError = { error = it })
                }
            }
        }
    }
    if (showNew) {
        NewTransferDialog(vm, branches.filter { it.active }, products, onDismiss = { showNew = false }, onDone = { showNew = false; error = null })
    }
}

@Composable
private fun TransferCard(
    t: StockTransferEntity,
    bName: Map<Long, String>,
    pName: Map<Long, String>,
    vm: TransfersVM,
    onError: (String?) -> Unit
) {
    val g = glassColors()
    var lines by remember(t.id, t.status) { mutableStateOf<List<StockTransferLineEntity>>(emptyList()) }
    LaunchedEffect(t.id, t.status) { lines = vm.linesOf(t.id) }
    val statusRes = when (t.status) {
        TransferRepo.STATUS_RECEIVED -> R.string.transfer_status_received
        TransferRepo.STATUS_CANCELED -> R.string.transfer_status_canceled
        TransferRepo.STATUS_PENDING_APPROVAL -> R.string.transfer_status_pending
        TransferRepo.STATUS_REJECTED -> R.string.transfer_status_rejected
        else -> R.string.transfer_status_transit
    }
    GlassCard(corner = 16.dp) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (bName[t.fromBranchId] ?: "#${t.fromBranchId}") + "  →  " + (bName[t.toBranchId] ?: "#${t.toBranchId}"),
                    color = g.textPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f)
                )
                Text(stringResource(statusRes), color = g.accent, style = MaterialTheme.typography.labelMedium)
            }
            Text(fmtDate(t.sentAt), color = g.textSecondary, style = MaterialTheme.typography.bodySmall)
            lines.forEach { l ->
                val base = (pName[l.productId] ?: "#${l.productId}") + " × " + fmtQty(l.qty)
                val tail = if (t.status == TransferRepo.STATUS_RECEIVED && kotlin.math.abs(l.qty - l.receivedQty) > 1e-9)
                    "  (" + stringResource(R.string.transfer_variance, fmtQty(l.qty - l.receivedQty)) + ")" else ""
                Text(base + tail, color = g.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
            if (t.note.isNotBlank()) {
                Text(t.note, color = g.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
            if (t.requestedBy.isNotBlank()) {
                Text(stringResource(R.string.transfer_requested_by, t.requestedBy), color = g.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
            if (t.approvedBy.isNotBlank()) {
                Text(stringResource(R.string.transfer_decided_by, t.approvedBy), color = g.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
            if (t.status == TransferRepo.STATUS_PENDING_APPROVAL && vm.canApprove()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.approve(t.id) { onError(it) } }) { Text(stringResource(R.string.transfer_approve)) }
                    OutlinedButton(onClick = { vm.reject(t.id) { onError(it) } }) { Text(stringResource(R.string.transfer_reject)) }
                }
            }
            if (t.status == TransferRepo.STATUS_IN_TRANSIT) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.receive(t.id) { onError(it) } }) { Text(stringResource(R.string.transfer_receive)) }
                    OutlinedButton(onClick = { vm.cancel(t.id) { onError(it) } }) { Text(stringResource(R.string.transfer_cancel)) }
                }
            }
        }
    }
}

@Composable
private fun NewTransferDialog(
    vm: TransfersVM,
    branches: List<com.superbiz.app.data.db.BranchEntity>,
    products: List<com.superbiz.app.data.db.Product>,
    onDismiss: () -> Unit,
    onDone: () -> Unit
) {
    val g = glassColors()
    var from by remember { mutableStateOf<Long?>(null) }
    var to by remember { mutableStateOf<Long?>(null) }
    var query by remember { mutableStateOf("") }
    var productId by remember { mutableStateOf<Long?>(null) }
    var qty by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var avail by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(from, productId) {
        val f = from; val p = productId
        avail = if (f != null && p != null) vm.qtyAt(f, p) else null
    }
    val matches = remember(query, products) {
        val q = query.trim()
        if (q.isEmpty()) products.take(5) else products.filter { it.name.contains(q, ignoreCase = true) || it.sku.contains(q, ignoreCase = true) }.take(5)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.transfer_new)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.transfer_from), color = g.textSecondary, style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    branches.forEach { b ->
                        FilterChip(selected = from == b.id, onClick = { from = b.id; err = null }, label = { Text(b.name) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.transfer_to), color = g.textSecondary, style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    branches.forEach { b ->
                        FilterChip(selected = to == b.id, onClick = { to = b.id; err = null }, label = { Text(b.name) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text(stringResource(R.string.transfer_product_search)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                matches.forEach { p ->
                    Text(
                        p.name,
                        color = if (productId == p.id) g.accent else g.textPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().clickable { productId = p.id; err = null }.padding(vertical = 8.dp)
                    )
                }
                avail?.let {
                    Text(stringResource(R.string.transfer_available, fmtQty(it)), color = g.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = qty, onValueChange = { qty = it; err = null },
                    label = { Text(stringResource(R.string.transfer_qty)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note, onValueChange = { note = it.take(500) },
                    label = { Text(stringResource(R.string.transfer_note)) },
                    modifier = Modifier.fillMaxWidth()
                )
                err?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(errText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val f = from; val t = to; val p = productId
                val q = qty.trim().replace(',', '.').toDoubleOrNull()
                when {
                    f == null || t == null -> err = "same"
                    f == t -> err = "same"
                    p == null -> err = "product"
                    q == null || q <= 0.0 || !q.isFinite() -> err = "qty"
                    vm.isOwner() -> vm.send(f, t, p, q, note) { r -> if (r == null) onDone() else err = r }
                    else -> vm.request(f, t, p, q, note) { r -> if (r == null) onDone() else err = r }
                }
            }) { Text(stringResource(if (vm.isOwner()) R.string.transfer_send else R.string.transfer_request)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.transfer_close)) } }
    )
}
