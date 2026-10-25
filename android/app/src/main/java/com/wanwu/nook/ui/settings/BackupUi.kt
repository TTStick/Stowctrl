package com.wanwu.nook.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import com.wanwu.nook.LocalStore
import com.wanwu.nook.LocalSync
import com.wanwu.nook.data.Backup
import com.wanwu.nook.data.Dates
import com.wanwu.nook.data.Entities
import com.wanwu.nook.ui.components.Choice
import com.wanwu.nook.ui.components.ChoiceDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "备份到文件" / "从备份恢复" buttons with their file pickers and dialogs.
 * During onboarding only restore is offered, and it replaces the (empty) home.
 */
@Composable
fun BackupButtons(onboarding: Boolean = false, onRestored: () -> Unit = {}) {
    val store = LocalStore.current
    val sync = LocalSync.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Backup.Loaded?>(null) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            working = true
            message = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { Backup.export(store, it) } ?: error("无法写入文件")
                "备份已保存"
            }.getOrElse { "备份失败：${it.message}" }
            working = false
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            working = true
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { Backup.load(it, context.cacheDir) } ?: error("无法读取文件")
                }
            }.onSuccess { pending = it }.onFailure { message = "无法读取备份：${it.message}" }
            working = false
        }
    }

    fun restore(loaded: Backup.Loaded, replace: Boolean) {
        runCatching { Backup.restore(loaded, store, replace) }
            .onSuccess { s ->
                message = "已恢复 ${s.rooms} 个房间、${s.furniture} 件家具、${s.items} 件物品、${s.photos} 张照片" +
                    (if (s.skipped > 0) "，${s.skipped} 条本机已有更新的版本未覆盖" else "") + "。"
                if (onboarding) { store.seedDefaultCategoriesIfNeeded(); store.markOnboarded() }
                onRestored()
            }
            .onFailure { message = "恢复失败：${it.message}" }
    }

    if (!onboarding) {
        TextButton({ save.launch("万物收纳备份-${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())}.zip") },
            enabled = !working, modifier = Modifier.testTag("backup.save")) { Text("备份到文件（含照片）") }
    }
    TextButton({ open.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, enabled = !working,
        modifier = Modifier.testTag("backup.restore")) { Text("从备份恢复") }
    if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

    pending?.let { loaded ->
        val m = loaded.manifest
        val text = "“${m.homeName}”，${Dates.format(m.exportedAt)} 备份：${loaded.count(Entities.ROOM)} 个房间、${loaded.count(Entities.FURNITURE)} 件家具、" +
            "${loaded.count(Entities.ITEM)} 件物品、${loaded.photoCount} 张照片。" + if (sync.isLoggedIn) "恢复的内容会同步到账号。" else ""
        ChoiceDialog("从备份恢复", text,
            if (onboarding) listOf(Choice("恢复") { restore(loaded, true) })
            else listOf(Choice("合并到本机（保留较新的内容）") { restore(loaded, false) }, Choice("用备份替换本机数据", true) { restore(loaded, true) }),
            onDismiss = { pending = null; loaded.photoDir.deleteRecursively() })
    }
}
