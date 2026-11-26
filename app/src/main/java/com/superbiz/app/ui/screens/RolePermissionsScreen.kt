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
                        Text(stringResource(opLabel(op)), color = g.textPrimary, style = MaterialTheme.typography.labelLarge)
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


/** تسمية مترجمة لكل عملية — مفتاح الموارد op_<الاسم بحروف صغيرة> عبر ثوابت R (مفحوص وقت الترجمة) */
private fun opLabel(op: Op): Int = when (op) {
    Op.POS_SELL -> R.string.op_pos_sell
    Op.INVOICE_SHARE -> R.string.op_invoice_share
    Op.INVOICE_CANCEL -> R.string.op_invoice_cancel
    Op.PARTY_PRODUCT_EDIT -> R.string.op_party_product_edit
    Op.HARD_DELETE -> R.string.op_hard_delete
    Op.INVENTORY_READ -> R.string.op_inventory_read
    Op.INVENTORY_MANAGE -> R.string.op_inventory_manage
    Op.FINANCIAL_REPORTS -> R.string.op_financial_reports
    Op.TAX_RETURN_VIEW -> R.string.op_tax_return_view
    Op.TAX_OWNER_DATA -> R.string.op_tax_owner_data
    Op.EXPENSES_CHECKS_INSTALLMENTS -> R.string.op_expenses_checks_installments
    Op.GENERAL_SETTINGS -> R.string.op_general_settings
    Op.USERS_MANAGE -> R.string.op_users_manage
    Op.BACKUP_RESTORE -> R.string.op_backup_restore
    Op.PRO_MANAGE -> R.string.op_pro_manage
    Op.TRANSFER_APPROVE -> R.string.op_transfer_approve
    Op.COLLECTION -> R.string.op_collection
    Op.DEBT_WRITE -> R.string.op_debt_write
    Op.LOYALTY_APPLY -> R.string.op_loyalty_apply
    Op.LOYALTY_MANAGE -> R.string.op_loyalty_manage
}
