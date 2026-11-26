package com.superbiz.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.superbiz.app.R
import com.superbiz.app.domain.rbac.Op
import com.superbiz.app.domain.rbac.Role
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.components.SubHeader
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.vm.RolePermsVM

/** [D2] مصفوفة (عملية × دور): مفتاح لكل دور غير المالك. المالك ثابت. التعديلات تُدوَّن في سجل التدقيق. */
@Composable
fun RolePermissionsScreen(onBack: () -> Unit) {
    val g = glassColors()
    val vm: RolePermsVM = viewModel()
    @Suppress("UNUSED_VARIABLE") val v by vm.version.collectAsState()
    val roles = listOf(Role.MANAGER, Role.ACCOUNTANT, Role.CASHIER)
    val roleNames = mapOf(
        Role.MANAGER to stringResource(R.string.role_manager),
        Role.ACCOUNTANT to stringResource(R.string.role_accountant),
        Role.CASHIER to stringResource(R.string.role_cashier)
    )
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        SubHeader(stringResource(R.string.rp_title), onBack = onBack)
        Text(stringResource(R.string.rp_hint), color = g.textSecondary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Op.entries.toList(), key = { it.name }) { op ->
                GlassCard(corner = 16.dp) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(op.name, color = g.textPrimary, style = MaterialTheme.typography.labelLarge)
                        roles.forEach { role ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(roleNames[role] ?: "", color = g.textSecondary, modifier = Modifier.weight(1f))
                                Switch(checked = vm.allowed(role, op), onCheckedChange = { vm.toggle(role, op) })
                            }
                        }
                    }
                }
            }
        }
    }
}
