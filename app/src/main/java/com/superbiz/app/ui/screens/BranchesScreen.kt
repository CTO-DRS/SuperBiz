package com.superbiz.app.ui.screens

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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.superbiz.app.ui.components.EmptyState
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.components.SubHeader
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.vm.BranchesVM

/**
 * [A1] شاشة الفروع — عرض/إنشاء/أرشفة فرع أو مخزن. الرفض الصلاحي يحدث في TransferRepo (INVENTORY_MANAGE)
 * والمسار نفسه مسوَّر بـRbacGated؛ الواجهة تعرض الخطأ ولا تكتب بصمت.
 */
@Composable
fun BranchesScreen(onBack: () -> Unit, onOpenTransfers: () -> Unit = {}) {
    val g = glassColors()
    val vm: BranchesVM = viewModel()
    val branches by vm.branches.collectAsState()
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(0) }
    var errorKey by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        SubHeader(stringResource(R.string.branches_title), onBack = onBack)
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.OutlinedButton(onClick = onOpenTransfers, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.transfers_open))
        }
        Spacer(Modifier.height(8.dp))
        GlassCard(corner = 18.dp) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; errorKey = null },
                    label = { Text(stringResource(R.string.branch_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = kind == 0, onClick = { kind = 0 },
                        label = { Text(stringResource(R.string.branch_kind_store)) }
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = kind == 1, onClick = { kind = 1 },
                        label = { Text(stringResource(R.string.branch_kind_warehouse)) }
                    )
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        vm.create(name, kind) { err ->
                            if (err == null) { name = ""; errorKey = null } else errorKey = err
                        }
                    }) { Text(stringResource(R.string.branch_add)) }
                }
                errorKey?.let { key ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(
                            when (key) {
                                "name_required" -> R.string.branch_err_name
                                "duplicate" -> R.string.branch_err_dup
                                else -> R.string.branch_err_denied
                            }
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (branches.isEmpty()) {
            EmptyState(stringResource(R.string.branches_empty))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(branches, key = { it.id }) { b ->
                    GlassCard(corner = 16.dp) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(b.name, color = g.textPrimary, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(if (b.kind == 1) R.string.branch_kind_warehouse else R.string.branch_kind_store) +
                                        " · " + stringResource(if (b.active) R.string.branch_active else R.string.branch_inactive),
                                    color = g.textSecondary, style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(checked = b.active, onCheckedChange = { vm.setActive(b.id, it) })
                        }
                    }
                }
            }
        }
    }
}
