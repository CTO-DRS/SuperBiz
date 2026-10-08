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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superbiz.app.R
import com.superbiz.app.data.db.UserEntity
import com.superbiz.app.domain.rbac.Role
import com.superbiz.app.ui.components.BizField
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.theme.Cyan
import com.superbiz.app.ui.theme.RedDeep
import com.superbiz.app.ui.theme.VioDeep
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.vm.UsersVM

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * [H1-5][V 1.2.0] شاشة إدارة المستخدمين والأدوار — باب المالك حصراً
 * ═══════════════════════════════════════════════════════════════════════════
 * عقد التصميم (RBAC_V13_DESIGN §2/§3): أربعة أدوار ثابتة بلا صلاحيات حرة،
 * والإنشاء/التعطيل/الحذف/إعادة الرمز كلها عمليات RoleGate.require(USERS_MANAGE)
 * داخل UsersVM — هذه الشاشة عرضٌ وتجميعُ مدخلات فقط. الشاشة نفسها لا تُفتح
 * أصلاً لغير المالك (بوابة المسار USERS في Nav + إخفاء القسم في مركز الإعدادات).
 *
 * حمايات فشل مغلقة معروضة من VM: آخر مالك فعّال لا يُعطَّل ولا يُحذف،
 * والمالك لا يحذف جلسته الحية، والبصمة للمالك فقط في الموجة الأولى.
 */
@Composable
fun UsersScreen(usersVM: UsersVM, onBack: () -> Unit) {
    val g = glassColors()
    val users by usersVM.users.collectAsState()
    val busy by usersVM.busy.collectAsState()
    val toast by usersVM.toast.collectAsState()

    androidx.compose.runtime.LaunchedEffect(Unit) { usersVM.refresh() }
    // [H1-5] الرسائل الإدارية (أُضيف/أُعيد الرمز/رسائل الرفض) تُعرض سطراً صريحاً
    // تحت العنوان — نمط بسيط بدل ربط Snackbar إضافي

    var showAdd by remember { mutableStateOf(false) }
    var resetTarget by remember { mutableStateOf<UserEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<UserEntity?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // شريط علوي
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBackIos, null, tint = g.textPrimary)
                }
                Text(
                    stringResource(R.string.users_screen_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = g.textPrimary
                )
            }

            // شرح موجز للنموذج
            Text(
                stringResource(R.string.users_screen_hint),
                style = MaterialTheme.typography.bodySmall,
                color = g.textSecondary,
                textAlign = TextAlign.Start
            )
            toast?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Text(msg, fontSize = 13.sp, color = g.accent, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))

            // بطاقة إضافة مستخدم
            GlassCard(Modifier.fillMaxWidth().clickable { showAdd = true }) {
                Row(
                    Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(VioDeep, Cyan))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Add, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.users_add),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = g.textPrimary
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            // بطاقات المستخدمين
            users.forEach { u ->
                val role = Role.fromId(u.role)
                GlassCard(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Person, null,
                                tint = if (u.active == 1) g.accent else g.textSecondary,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    u.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = g.textPrimary
                                )
                                Text(
                                    roleLabel(role) +
                                        if (u.active == 1) "" else " · " +
                                        stringResource(R.string.users_inactive),
                                    fontSize = 12.sp,
                                    color = if (u.active == 1) g.textSecondary else RedDeep
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // تعطيل/تفعيل
                            UserActionChip(
                                text = stringResource(
                                    if (u.active == 1) R.string.users_deactivate else R.string.users_activate
                                ),
                                onClick = { usersVM.setActive(u.id, u.active != 1) }
                            )
                            // إعادة الرمز
                            UserActionChip(
                                text = stringResource(R.string.users_reset_pin),
                                onClick = { resetTarget = u }
                            )
                            // حذف
                            UserActionChip(
                                text = stringResource(R.string.users_delete),
                                danger = true,
                                onClick = { deleteTarget = u }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(90.dp)) // مساحة الدوك العائم
        }

        if (busy) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(16.dp))
                    .background(g.surface)
                    .padding(14.dp)
            ) {
                Text(stringResource(R.string.users_working), color = g.textSecondary)
            }
        }
    }

    // حوار الإضافة
    if (showAdd) {
        AddUserDialog(
            usersVM = usersVM,
            onDismiss = { showAdd = false }
        )
    }
    // حوار إعادة الرمز
    resetTarget?.let { target ->
        ResetPinDialog(
            userName = target.name,
            onConfirm = { pin ->
                usersVM.resetPin(target.id, pin)
                resetTarget = null
            },
            onDismiss = { resetTarget = null }
        )
    }
    // حوار تأكيد الحذف (إجراء دمّار — يتبع إعداد confirmDestructive بظهور صريح دائماً هنا)
    deleteTarget?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.users_delete_confirm_title),
            message = stringResource(R.string.users_delete_confirm_msg, target.name),
            confirmText = stringResource(R.string.users_delete),
            onConfirm = {
                usersVM.deleteUser(target.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null }
        )
    }
}

@Composable
private fun UserActionChip(text: String, danger: Boolean = false, onClick: () -> Unit) {
    val g = glassColors()
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (danger) RedDeep else g.textPrimary,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(g.surface)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

@Composable
internal fun roleLabel(role: Role): String = stringResource(
    when (role) {
        Role.OWNER -> R.string.role_owner
        Role.MANAGER -> R.string.role_manager
        Role.ACCOUNTANT -> R.string.role_accountant
        Role.CASHIER -> R.string.role_cashier
    }
)

/** حوار إضافة مستخدم — اسم + دور + رمز 6..8 (عقد UserAuth نفسه) */
@Composable
private fun AddUserDialog(usersVM: UsersVM, onDismiss: () -> Unit) {
    val g = glassColors()
    var name by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(Role.CASHIER) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.users_add_title), color = g.textPrimary) },
        text = {
            Column {
                BizField(
                    value = name,
                    onValue = { name = it },
                    label = stringResource(R.string.users_field_name)
                )
                Spacer(Modifier.height(8.dp))
                BizField(
                    value = pin,
                    onValue = { if (it.length <= 8 && it.all { c -> c.isDigit() }) pin = it },
                    label = stringResource(R.string.users_field_pin)
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.users_field_role),
                    style = MaterialTheme.typography.labelMedium,
                    color = g.textSecondary
                )
                Spacer(Modifier.height(6.dp))
                Role.entries.forEach { r ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { role = r }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = role == r,
                            onClick = { role = r }
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(roleLabel(r), color = g.textPrimary, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    usersVM.addUser(name, role, pin)
                    onDismiss()
                }
            ) { Text(stringResource(R.string.users_save), color = g.accent) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = g.textSecondary)
            }
        },
        containerColor = g.surface
    )
}

/** حوار إعادة تعيين الرمز */
@Composable
private fun ResetPinDialog(userName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val g = glassColors()
    var pin by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.users_reset_pin_title, userName), color = g.textPrimary) },
        text = {
            Column {
                BizField(
                    value = pin,
                    onValue = { if (it.length <= 8 && it.all { c -> c.isDigit() }) pin = it },
                    label = stringResource(R.string.users_field_pin)
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onConfirm(pin) }) {
                Text(stringResource(R.string.users_save), color = g.accent)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = g.textSecondary)
            }
        },
        containerColor = g.surface
    )
}

/** حوار تأكيد عام (نمط موحّد بسيط) */
@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val g = glassColors()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = g.textPrimary) },
        text = { Text(message, color = g.textSecondary) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text(confirmText, color = RedDeep, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = g.textSecondary)
            }
        },
        containerColor = g.surface
    )
}
