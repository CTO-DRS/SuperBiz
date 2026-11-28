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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
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
        val opLabels = Op.entries.associateWith { stringResource(opLabel(it)) }
        var query by remember { mutableStateOf("") }
        var pending by remember { mutableStateOf<Pair<Role, com.superbiz.app.domain.rbac.RoleTemplates.Template>?>(null) }
        val tplNames = mapOf(
            com.superbiz.app.domain.rbac.RoleTemplates.Template.DEFAULT to stringResource(R.string.rp_tpl_default),
            com.superbiz.app.domain.rbac.RoleTemplates.Template.SALES_ONLY to stringResource(R.string.rp_tpl_sales),
            com.superbiz.app.domain.rbac.RoleTemplates.Template.FINANCE_VIEW to stringResource(R.string.rp_tpl_finance_view)
        )
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.rp_search)) }
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "tpl") {
                GlassCard(corner = 16.dp) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.rp_tpl_title), color = g.textPrimary, style = MaterialTheme.typography.labelLarge)
                        roles.forEach { role ->
                            Text(roleNames[role] ?: "", color = g.textSecondary)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                tplNames.forEach { (t, name) ->
                                    OutlinedButton(onClick = { pending = role to t }, modifier = Modifier.weight(1f)) { Text(name, maxLines = 1) }
                                }
                            }
                        }
                    }
                }
            }
            permGroups.forEach { (titleRes, allOps) ->
              val ops = allOps.filter { op -> query.isBlank() || (opLabels[op] ?: "").contains(query.trim(), ignoreCase = true) }
              if (ops.isEmpty()) return@forEach
              item(key = "h_" + titleRes) {
                Text(stringResource(titleRes), color = g.accent, style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp))
              }
              items(ops, key = { it.name }) { op ->
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
        pending?.let { (role, t) ->
            AlertDialog(
                onDismissRequest = { pending = null },
                title = { Text(stringResource(R.string.rp_tpl_title)) },
                text = { Text(stringResource(R.string.rp_tpl_confirm, roleNames[role] ?: "", tplNames[t] ?: "")) },
                confirmButton = { TextButton(onClick = { vm.applyTemplate(role, t); pending = null }) { Text(stringResource(R.string.rp_tpl_apply)) } },
                dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.budget_cancel)) } }
            )
        }
    }
}

/** [D2] تجميع العمليات في أقسام للقراءة؛ أي عملية جديدة غير مدرجة تقع تلقائياً في «الإدارة» */
private val permGroups: List<Pair<Int, List<Op>>> = run {
    val sales = listOf(Op.POS_SELL, Op.INVOICE_SHARE, Op.INVOICE_CANCEL, Op.PARTY_PRODUCT_EDIT, Op.COLLECTION, Op.LOYALTY_APPLY, Op.LOYALTY_MANAGE)
    val inventory = listOf(Op.INVENTORY_READ, Op.INVENTORY_MANAGE, Op.TRANSFER_APPROVE)
    val finance = listOf(Op.FINANCIAL_REPORTS, Op.TAX_RETURN_VIEW, Op.TAX_OWNER_DATA, Op.EXPENSES_CHECKS_INSTALLMENTS, Op.DEBT_WRITE)
    val admin = Op.entries.filter { it !in sales && it !in inventory && it !in finance }
    listOf(
        R.string.rp_group_sales to sales,
        R.string.rp_group_inventory to inventory,
        R.string.rp_group_finance to finance,
        R.string.rp_group_admin to admin,
    )
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
