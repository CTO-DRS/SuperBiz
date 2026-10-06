package com.superbiz.app.ui.screens.statement

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.superbiz.app.R
import com.superbiz.app.ui.components.BizField
import com.superbiz.app.ui.components.GlassCard
import com.superbiz.app.ui.components.SubHeader
import com.superbiz.app.ui.screens.stringResourceCompat
import com.superbiz.app.ui.theme.Amber
import com.superbiz.app.ui.theme.Cyan
import com.superbiz.app.ui.theme.GreenDeep
import com.superbiz.app.ui.theme.RedDeep
import com.superbiz.app.ui.theme.glassColors
import com.superbiz.app.vm.AppVM
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [P17-c] شاشة إدارة التواقيع والأختام (المساران: signatures وstamps).
 *
 * صورتان حقيقيتان عبر منتقي SAF تُنسخان إلى filesDir/identity/{signatures|stamps}
 * ثم تُسجَّلان عبر عقد 17-a (StatementRepo.saveSignature/saveStamp) — بلا أي وهم:
 * بلا صورة مختارة لا يُفعَّل زر الحفظ، والحذف حقيقي والافتراضي واحد بمعاملة Room.
 */

/** مجلدات التخزين الداخلي — موثقة هنا لأن الواجهة وحدها تكتبها قبل تسجيل المسار */
private fun assetDir(context: android.content.Context, stamp: Boolean): File =
    File(context.filesDir, if (stamp) "identity/stamps" else "identity/signatures")

@Composable
fun SignatureManagerScreen(appVM: AppVM, nav: NavHostController) {
    AssetManagerScreen(appVM = appVM, nav = nav, stamp = false)
}

@Composable
fun StampManagerScreen(appVM: AppVM, nav: NavHostController) {
    AssetManagerScreen(appVM = appVM, nav = nav, stamp = true)
}

@Composable
private fun AssetManagerScreen(appVM: AppVM, nav: NavHostController, stamp: Boolean) {
    val context = LocalContext.current
    val g = glassColors()
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf(listOf<StatementUiFacade.SignatureUi>()) }
    var stamps by remember { mutableStateOf(listOf<StatementUiFacade.StampUi>()) }
    var addOpen by remember { mutableStateOf(false) }
    var pendingPath by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var newJob by remember { mutableStateOf("") }
    var deleteFor by remember { mutableStateOf<Long?>(null) }

    fun reload() {
        scope.launch(Dispatchers.IO) {
            if (stamp) {
                stamps = runCatching { StatementUiFacade.stamps(context.applicationContext) }
                    .getOrDefault(emptyList())
            } else {
                items = runCatching { StatementUiFacade.signatures(context.applicationContext) }
                    .getOrDefault(emptyList())
            }
        }
    }
    LaunchedEffect(Unit) { reload() }

    // منتقي صورة SAF — النسخ إلى filesDir ثم عرض المسار المرشح للحفظ
    val pickImage = rememberImagePicker { uri ->
        pendingPath = copyUriToDir(
            context.applicationContext, uri, assetDir(context.applicationContext, stamp),
            if (stamp) "stamp" else "signature"
        )
        if (pendingPath == null) {
            android.widget.Toast.makeText(
                context, context.getString(R.string.st_pick_failed), android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp)
    ) {
        SubHeader(
            stringResourceCompat(if (stamp) R.string.st_stamps_title else R.string.st_signatures_title),
            onBack = { nav.popBackStack() }
        )
        Text(
            stringResourceCompat(if (stamp) R.string.st_stamps_sub else R.string.st_signatures_sub),
            fontSize = 12.sp, color = g.textSecondary
        )
        Spacer(Modifier.height(8.dp))

        StAction(stringResourceCompat(if (stamp) R.string.st_add_stamp else R.string.st_add_signature), GreenDeep) {
            pendingPath = null; newName = ""; newJob = ""; addOpen = true
        }
        Spacer(Modifier.height(8.dp))

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val empty = if (stamp) stamps.isEmpty() else items.isEmpty()
            if (empty) {
                StHint(stringResourceCompat(if (stamp) R.string.st_stamp_empty else R.string.st_signature_empty))
            }
            if (stamp) {
                stamps.forEach { s ->
                    AssetCard(
                        name = s.name + (if (s.isDefault) " ★" else ""),
                        sub = null, filePath = s.filePath,
                        isDefault = s.isDefault,
                        onSetDefault = {
                            scope.launch(Dispatchers.IO) {
                                runCatching {
                                    StatementUiFacade.setDefaultStamp(context.applicationContext, s.id)
                                }
                                withContext(Dispatchers.Main) { reload() }
                            }
                        },
                        onDelete = { deleteFor = s.id }
                    )
                }
            } else {
                items.forEach { s ->
                    AssetCard(
                        name = s.name + (if (s.isDefault) " ★" else ""),
                        sub = s.jobTitle.ifBlank { null }, filePath = s.filePath,
                        isDefault = s.isDefault,
                        onSetDefault = {
                            scope.launch(Dispatchers.IO) {
                                runCatching {
                                    StatementUiFacade.setDefaultSignature(context.applicationContext, s.id)
                                }
                                withContext(Dispatchers.Main) { reload() }
                            }
                        },
                        onDelete = { deleteFor = s.id }
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // [P32-FIX] الحواران المستخرَجَان إلى composables مستقلة — كانت الدالة الموحّدة ~200 سطر
    // تُسقط codegen المترجم بـOutOfMemoryError (CI حمراء منذ P20) فصار الحمل موزعاً
    if (addOpen) {
        AddAssetDialog(
            stamp = stamp,
            hasImage = pendingPath != null,
            newName = newName, onNewName = { newName = it },
            newJob = newJob, onNewJob = { newJob = it },
            onPickImage = pickImage,
            onDismiss = { addOpen = false },
            onSave = {
                val path = pendingPath
                if (path != null) {
                    addOpen = false
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            if (stamp) StatementUiFacade.addStamp(context.applicationContext, newName, path)
                            else StatementUiFacade.addSignature(context.applicationContext, newName, newJob, path)
                        }
                        withContext(Dispatchers.Main) { reload() }
                    }
                }
            }
        )
    }

    deleteFor?.let { id ->
        // [P17-integration] تفريق النوعين قبل firstOrNull
        val delName = if (stamp) stamps.firstOrNull { it.id == id }?.name
            else items.firstOrNull { it.id == id }?.name
        DeleteAssetDialog(
            name = delName ?: "",
            onDismiss = { deleteFor = null },
            onConfirm = {
                deleteFor = null
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        if (stamp) StatementUiFacade.deleteStamp(context.applicationContext, id)
                        else StatementUiFacade.deleteSignature(context.applicationContext, id)
                    }
                    withContext(Dispatchers.Main) { reload() }
                }
            }
        )
    }
}

/** حوار إضافة أصل (توقيع/ختم): صورة مختارة + اسم (ومسمى وظيفي للتوقيع) — الحفظ عبر 17-a */
@Composable
private fun AddAssetDialog(
    stamp: Boolean,
    hasImage: Boolean,
    newName: String, onNewName: (String) -> Unit,
    newJob: String, onNewJob: (String) -> Unit,
    onPickImage: () -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val g = glassColors()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = g.surfaceStrong,
        title = {
            Text(
                stringResourceCompat(if (stamp) R.string.st_add_stamp else R.string.st_add_signature),
                color = g.textPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StAction(
                    if (!hasImage) stringResourceCompat(R.string.st_pick_image)
                    else stringResourceCompat(R.string.st_image_ready),
                    Cyan
                ) { onPickImage() }
                if (!stamp) {
                    BizField(newJob, onNewJob, stringResourceCompat(R.string.st_job_title))
                }
                BizField(newName, onNewName, stringResourceCompat(R.string.name))
            }
        },
        confirmButton = {
            TextButton(
                enabled = hasImage && newName.isNotBlank(),
                onClick = onSave
            ) {
                Text(
                    stringResourceCompat(R.string.save),
                    color = g.accent, fontWeight = FontWeight.Bold
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

/** تأكيد الحذف — حذف حقيقي عبر 17-a (الصورة تُترك على القرص احتياطاً: سجل مالي) */
@Composable
private fun DeleteAssetDialog(
    name: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val g = glassColors()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = g.surfaceStrong,
        title = { Text(stringResourceCompat(R.string.st_delete), color = RedDeep) },
        text = {
            Text(
                stringResourceCompat(R.string.st_delete_confirm, name),
                color = g.textSecondary, fontSize = 13.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResourceCompat(R.string.delete), color = RedDeep, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResourceCompat(R.string.cancel), color = g.textSecondary)
            }
        }
    )
}

/** بطاقة أصل (توقيع/ختم): مصغّرة حقيقية من الملف + إجراءات افتراضي/حذف */
@Composable
private fun AssetCard(
    name: String,
    sub: String?,
    filePath: String,
    isDefault: Boolean,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit
) {
    val g = glassColors()
    var bmp by remember(filePath) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(filePath) {
        bmp = withContext(Dispatchers.IO) { decodeScaled(filePath, 512) }
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(g.surfaceStrong)
                    .border(1.dp, g.border, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                val b = bmp
                if (b != null) {
                    Image(
                        bitmap = b.asImageBitmap(), contentDescription = stringResource(R.string.a11y_signature_stamp_preview), // [P39-M4-2]
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text("—", color = g.textSecondary)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    color = g.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (sub != null) {
                    Text(sub, fontSize = 12.sp, color = g.textSecondary, maxLines = 1)
                }
            }
            if (!isDefault) {
                IconButton(onClick = onSetDefault) {
                    Icon(
                        Icons.Rounded.StarBorder,
                        stringResourceCompat(R.string.st_set_default),
                        tint = g.textSecondary
                    )
                }
            } else {
                Icon(
                    Icons.Rounded.Star,
                    stringResourceCompat(R.string.st_default),
                    tint = Amber
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, stringResourceCompat(R.string.delete), tint = RedDeep)
            }
        }
    }
}
