package com.wanwu.nook.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wanwu.nook.LocalContainer
import com.wanwu.nook.LocalAI
import com.wanwu.nook.LocalHA
import com.wanwu.nook.LocalRouter
import com.wanwu.nook.LocalStore
import com.wanwu.nook.LocalSync
import com.wanwu.nook.Route
import com.wanwu.nook.data.Category
import com.wanwu.nook.data.CategoryTemplate
import com.wanwu.nook.data.DefaultCategories
import com.wanwu.nook.data.info
import com.wanwu.nook.net.AIService
import com.wanwu.nook.net.ApiClient
import com.wanwu.nook.net.HAClient
import com.wanwu.nook.net.ServerInfo
import com.wanwu.nook.net.SyncManager
import com.wanwu.nook.ui.components.Card
import com.wanwu.nook.ui.components.Choice
import com.wanwu.nook.ui.components.ChoiceDialog
import com.wanwu.nook.ui.components.Divider
import com.wanwu.nook.ui.components.EditorDialog
import com.wanwu.nook.ui.components.NookTopBar
import com.wanwu.nook.ui.components.SectionTitle
import com.wanwu.nook.ui.components.SettingRow
import com.wanwu.nook.ui.components.SymbolIcons
import com.wanwu.nook.ui.components.TextInputDialog
import com.wanwu.nook.ui.home.DeleteRoomDialog
import com.wanwu.nook.ui.home.RoomEditorDialog
import com.wanwu.nook.ui.items.AIIconGenerator
import com.wanwu.nook.ui.items.CustomIconGrid
import com.wanwu.nook.ui.items.SymbolGrid
import com.wanwu.nook.ui.theme.LocalNookColors
import com.wanwu.nook.ui.theme.hexColor
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val store = LocalStore.current
    val sync = LocalSync.current
    val ha = LocalHA.current
    val ai = LocalAI.current
    val router = LocalRouter.current
    val prefs = LocalContainer.current.appPrefs
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var signingIn by remember { mutableStateOf(false) }
    var renamingHome by remember { mutableStateOf(false) }
    var changingPassword by remember { mutableStateOf(false) }
    var deletingAccount by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }
    var engineMenu by remember { mutableStateOf(false) }
    val engines = mapOf("auto" to "端侧优先，失败用服务器", "device" to "只在手机上", "server" to "只用服务器")

    Scaffold(topBar = { NookTopBar("我的") }, containerColor = LocalNookColors.current.canvas) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SectionTitle("账号与同步")
            Card {
                if (sync.isLoggedIn) {
                    SettingRow("账号", sync.username ?: "", Modifier.testTag("settings.account"))
                    SettingRow("服务器", sync.serverUrl)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("离线模式")
                            Text("完全不连服务器，改动先存在本机，关闭后自动同步", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(sync.offline, { sync.updateOffline(it) }, Modifier.testTag("settings.offline"))
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("同步", Modifier.weight(1f))
                        if (sync.offline) Text(if (store.pendingCount > 0) "已暂停 · ${store.pendingCount} 项改动待同步" else "已暂停",
                            Modifier.testTag("settings.paused"), color = Color(0xFFF57C00))
                        else when (val st = sync.status) {
                            SyncManager.Status.Syncing -> { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Text(" 同步中") }
                            is SyncManager.Status.Failed -> Text(st.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            SyncManager.Status.Idle -> when {
                                store.pendingCount > 0 -> Text("${store.pendingCount} 项待同步", color = Color(0xFFF57C00))
                                sync.lastSyncAt != null -> Text("已同步 · " + relativeTime(sync.lastSyncAt!!), Modifier.testTag("settings.synced"),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Divider()
                    TextButton({ scope.launch { sync.syncNow() } }, enabled = sync.status != SyncManager.Status.Syncing && !sync.offline) { Text("立即同步") }
                    TextButton({ changingPassword = true }) { Text("修改密码") }
                    TextButton({ confirmSignOut = true }) { Text("退出登录") }
                    TextButton({ deletingAccount = true }, Modifier.testTag("settings.deleteAccount")) { Text("注销账号", color = MaterialTheme.colorScheme.error) }
                } else {
                    Text("本机模式", style = MaterialTheme.typography.titleSmall)
                    Text("现在不连任何服务器，数据只保存在这台设备上，所有功能都能用。想在多台设备间同步时，再登录自己部署的服务器；也可以用下面的“备份”在设备之间搬家。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton({ signingIn = true }, Modifier.testTag("settings.signin")) { Text("登录或注册") }
                    (sync.status as? SyncManager.Status.Failed)?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
                }
            }
            SectionTitle("家")
            Card {
                SettingRow("名称", store.settings.homeName, onClick = { renamingHome = true })
                Divider()
                SettingRow("房间", "${store.rooms.size} 个", onClick = { router.open(Route.RoomsManager) })
                Divider()
                SettingRow("物品分类", "${store.categories.size} 个", onClick = { router.open(Route.Categories) })
                Divider()
                SettingRow("我的图标", "${store.icons.size} 个", onClick = { router.open(Route.CustomIcons) })
            }
            SectionTitle("智能家居与 AI")
            Card {
                SettingRow("Home Assistant", if (ha.isConfigured) "已连接" else "未设置", onClick = { router.open(Route.HASettings) })
                Divider()
                SettingRow("AI 绘制图标", if (ai.source == AIService.Source.direct) (if (ai.hasKey) "本机直连" else "未填 Key") else "通过服务器",
                    Modifier.testTag("settings.ai"), onClick = { router.open(Route.AISettings) })
            }
            SectionTitle("照片")
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("拍照后自动抠图", Modifier.weight(1f)); Switch(prefs.autoCutout, { prefs.updateAutoCutout(it) }) }
                Box {
                    SettingRow("抠图方式", engines[prefs.cutoutEngine], onClick = { engineMenu = true })
                    DropdownMenu(engineMenu, { engineMenu = false }) {
                        engines.forEach { (k, v) -> DropdownMenuItem({ Text(v) }, { engineMenu = false; prefs.updateCutoutEngine(k) }) }
                    }
                }
                Text(if (sync.canServerCutout) "你的服务器已启用抠图。" else "手机上先用 ML Kit（需要 Google 服务），不可用时用内置的离线模型；服务器抠图需要在部署时设置 WITH_CUTOUT=true。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SectionTitle("备份")
            Card {
                BackupButtons()
                TextButton({ confirmWipe = true }) { Text("清除本机数据", color = MaterialTheme.colorScheme.error) }
                Text("备份是一个 zip 文件，不需要服务器；在安卓手机和 iPhone 之间都能恢复。备份里不含 Home Assistant 令牌。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SectionTitle("关于")
            Card {
                SettingRow("版本", context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0")
                Text("万物收纳 · 照片和数据只存放在你的设备和你自己的服务器上。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (signingIn) AccountDialog { signingIn = false }
    if (changingPassword) ChangePasswordDialog { changingPassword = false }
    if (deletingAccount) DeleteAccountDialog { deletingAccount = false }
    if (renamingHome) TextInputDialog("家的名称", store.settings.homeName, onConfirm = { store.saveSettings(store.settings.copy(homeName = it.ifBlank { "我的家" })) },
        onDismiss = { renamingHome = false })
    if (confirmSignOut) ChoiceDialog("退出登录", "账号里的数据会保留在服务器上。", listOf(
        Choice("退出，保留本机数据") { scope.launch { sync.signOut(false) } },
        Choice("退出并清除本机数据", true) { scope.launch { sync.signOut(true) } },
    ), onDismiss = { confirmSignOut = false })
    if (confirmWipe) ChoiceDialog("清除本机数据？",
        if (sync.isLoggedIn) "会退出登录并删除本机的所有房间、家具、物品和照片。服务器上的数据不受影响。"
        else "本机的所有房间、家具、物品和照片都会被删除，且没有登录同步，无法恢复。",
        listOf(Choice("清除", true) { scope.launch { if (sync.isLoggedIn) sync.signOut(true) else store.wipeLocal() } }),
        onDismiss = { confirmWipe = false })
}

private fun relativeTime(ms: Long): String {
    val sec = (System.currentTimeMillis() - ms) / 1000
    return when {
        sec < 60 -> "刚刚"
        sec < 3600 -> "${sec / 60} 分钟前"
        sec < 86400 -> "${sec / 3600} 小时前"
        else -> com.wanwu.nook.data.Dates.format(ms)
    }
}

// region Account

@Composable
fun AccountDialog(onFinished: () -> Unit = {}, onDismiss: () -> Unit) {
    val sync = LocalSync.current
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(sync.serverUrl) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(false) }
    var inviteCode by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<ServerInfo?>(null) }
    var needsMerge by remember { mutableStateOf(false) }
    fun finish() { onFinished(); onDismiss() }
    val passwordMin = info?.passwordMin ?: 8
    val askCode = register && info?.registration != "open"
    // Accounts made before the 8-character rule can still sign in.
    val canSubmit = !working && server.isNotBlank() && username.trim().length >= 3 &&
        (if (register) password.length >= passwordMin else password.isNotEmpty())

    EditorDialog("同步账号", { if (!working && !needsMerge) onDismiss() }, onSave = null) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(server, { server = it; info = null }, Modifier.fillMaxWidth().testTag("account.server"), label = { Text("服务器地址") },
                placeholder = { Text("例如 192.168.1.10:5858") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = { Text("就是你用 Docker 部署的服务端：家里局域网填 IP:5858，公网部署填 https://你的域名。") })
            if (ApiClient.normalize(server)?.let(ApiClient::isUnencryptedPublic) == true)
                Text("未加密连接（http）。如果服务器在公网上，密码和数据可能被截获，请改用 https。", color = Color(0xFFF57C00),
                    style = MaterialTheme.typography.bodySmall)
            info?.let { Text("已连接 ${it.name} ${it.version}" + if (it.features.icons) " · AI 图标可用" else "", color = Color(0xFF2E7D32),
                style = MaterialTheme.typography.bodySmall) }
                ?: TextButton({
                    scope.launch {
                        working = true
                        try { info = sync.checkServer(server); error = null } catch (e: Exception) { error = e.message } finally { working = false }
                    }
                }, enabled = server.isNotBlank() && !working) { Text("检测服务器") }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(!register, { register = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("登录") }
                SegmentedButton(register, { register = true }, SegmentedButtonDefaults.itemShape(1, 2), Modifier.testTag("account.registerTab")) { Text("注册新账号") }
            }
            OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth().testTag("account.username"), label = { Text("用户名") }, singleLine = true)
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth().testTag("account.password"), label = { Text(if (register) "密码（至少 $passwordMin 位）" else "密码") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            if (askCode) OutlinedTextField(inviteCode, { inviteCode = it }, Modifier.fillMaxWidth().testTag("account.invite"), label = { Text("注册码") },
                singleLine = true, supportingText = { Text("向服务器管理员要注册码。刚部署的服务器，第一个账号的注册码在服务器日志里（docker logs nook-server）。") })
            if (register && info?.registrationOpen == false) Text("这台服务器已关闭注册，请使用已有账号登录。", color = Color(0xFFF57C00))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button({
                scope.launch {
                    working = true; error = null
                    try {
                        when (sync.signIn(server, username, password, register, inviteCode)) {
                            SyncManager.LoginOutcome.Done -> finish()
                            SyncManager.LoginOutcome.NeedsMergeDecision -> needsMerge = true
                        }
                    } catch (e: Exception) { error = e.message } finally { working = false }
                }
            }, Modifier.fillMaxWidth().testTag("account.submit"), enabled = canSubmit) {
                if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White) else Text(if (register) "注册并登录" else "登录")
            }
        }
    }
    if (needsMerge) AlertDialog({}, title = { Text("这台设备和账号里都有数据") },
        text = {
            Column {
                Text("合并会保留本机已有的房间和物品，并下载账号里的内容。")
                TextButton({ needsMerge = false; scope.launch { sync.resolveMerge(true); finish() } }) { Text("合并两边的数据（推荐）") }
                TextButton({ needsMerge = false; scope.launch { sync.resolveMerge(false); finish() } }) { Text("只用账号里的数据，清除本机", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {}, dismissButton = { TextButton({ needsMerge = false; scope.launch { sync.abandonSignIn() } }) { Text("取消登录") } })
}

@Composable
private fun ChangePasswordDialog(onDismiss: () -> Unit) {
    val sync = LocalSync.current
    val scope = rememberCoroutineScope()
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    EditorDialog("修改密码", onDismiss, onSave = {
        scope.launch { try { sync.changePassword(old, new); onDismiss() } catch (e: Exception) { error = e.message } }
    }, saveEnabled = old.isNotEmpty() && new.length >= 8 && new == confirm) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(Triple("原密码", old) { v: String -> old = v }, Triple("新密码（至少 8 位）", new) { v: String -> new = v },
                Triple("再输入一次", confirm) { v: String -> confirm = v }).forEach { (label, value, set) ->
                OutlinedTextField(value, set, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            }
            if (confirm.isNotEmpty() && confirm != new) Text("两次输入不一致", color = Color(0xFFF57C00))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("修改后，其他设备需要重新登录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DeleteAccountDialog(onDismiss: () -> Unit) {
    val sync = LocalSync.current
    val store = LocalStore.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var keepLocal by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    EditorDialog("注销账号", onDismiss, onSave = null) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("注销后，服务器上这个账号的所有数据和照片都会被永久删除，所有设备都会退出登录。")
            Row(verticalAlignment = Alignment.CenterVertically) { Text("在这台设备上保留一份数据", Modifier.weight(1f)); Switch(keepLocal, { keepLocal = it }) }
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth().testTag("delete.password"), label = { Text("输入密码确认") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button({
                scope.launch {
                    working = true
                    try { sync.deleteAccount(password); if (!keepLocal) store.wipeLocal(); onDismiss() }
                    catch (e: Exception) { error = e.message } finally { working = false }
                }
            }, Modifier.fillMaxWidth().testTag("delete.confirm"), enabled = password.isNotEmpty() && !working,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("永久注销账号") }
        }
    }
}

// endregion
// region AI

/** Where AI icons come from: the sync server, or straight from this device. */
@Composable
fun AISettingsScreen() {
    val ai = LocalAI.current
    val router = LocalRouter.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    Scaffold(topBar = { NookTopBar("AI 绘制图标", onBack = { router.back() }) }, containerColor = LocalNookColors.current.canvas) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card {
                listOf(AIService.Source.server to "通过同步服务器", AIService.Source.direct to "本机直连（自己的 API Key）").forEach { (src, label) ->
                    Row(Modifier.fillMaxWidth().clickable { ai.updateSource(src) }.testTag("ai.source.${src.name}"), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(ai.source == src, { ai.updateSource(src) }); Text(label)
                    }
                }
                Text(if (ai.source == AIService.Source.server) "大模型 Key 放在你自己部署的服务器上，手机里不保存。需要登录并且不在离线模式。"
                    else "不需要服务器：用你自己的 Claude API Key 直接请求。Key 加密保存在这台设备上，不会同步到其他设备或服务器。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (ai.source == AIService.Source.direct) {
                OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth().testTag("ai.key"), singleLine = true,
                    label = { Text(if (ai.hasKey) "已保存，输入新的可替换" else "API Key（sk-ant-…）") }, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(ai.baseUrl, { ai.updateBaseUrl(it) }, Modifier.fillMaxWidth().testTag("ai.baseUrl"), singleLine = true,
                    label = { Text("API 地址（可选，默认官方）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(ai.model, { ai.updateModel(it) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("模型") })
                Button({
                    scope.launch {
                        checking = true
                        val candidate = key.trim()
                        result = runCatching {
                            if (candidate.isEmpty()) ai.checkKey(ai.savedKey()) else { ai.checkKey(candidate); ai.updateKey(candidate); key = "" }
                            true to "可以使用，已保存"
                        }.getOrElse { false to (it.message ?: "检查失败") }
                        checking = false
                    }
                }, Modifier.fillMaxWidth().testTag("ai.check"), enabled = (key.isNotBlank() || ai.hasKey) && !checking) {
                    if (checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White) else Text("检查并保存")
                }
                result?.let { (ok, msg) -> Text(msg, Modifier.testTag("ai.result"), color = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error) }
                if (ai.hasKey) TextButton({ ai.updateKey(null); key = ""; result = null }) { Text("删除本机保存的 Key", color = MaterialTheme.colorScheme.error) }
                Text("检查只读取模型列表，不消耗额度。使用代理或中转时把地址填成它的根地址（不含 /v1）。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// endregion
// region Home Assistant

@Composable
fun HASettingsScreen() {
    val store = LocalStore.current
    val ha = LocalHA.current
    val router = LocalRouter.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(store.settings.haURL) }
    var token by remember { mutableStateOf(store.haToken) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    Scaffold(topBar = { NookTopBar("Home Assistant", onBack = { router.back() }) }, containerColor = LocalNookColors.current.canvas) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth().testTag("ha.url"), label = { Text("地址") },
                placeholder = { Text("http://192.168.1.20:8123") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth().testTag("ha.token"), label = { Text("长期访问令牌") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation())
            Text("令牌在 HA 网页里生成：左下角头像 › 安全 › 长期访问令牌 › 创建令牌。地址会随账号同步；令牌只加密保存在这台设备上，不会上传到服务器，其他设备需各自填写一次。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button({
                val base = ApiClient.normalize(url)
                if (base == null) { result = false to "地址格式不正确"; return@Button }
                scope.launch {
                    testing = true
                    try {
                        val c = HAClient(base, token.trim())
                        c.check()
                        val n = c.states().size
                        store.saveSettings(store.settings.copy(haURL = base))
                        store.updateHAToken(token)
                        result = true to "连接成功，共 $n 个实体，已保存"
                        ha.refresh()
                    } catch (e: Exception) {
                        result = false to (e.message ?: "连接失败")
                    } finally { testing = false }
                }
            }, Modifier.fillMaxWidth().testTag("ha.test"), enabled = url.isNotBlank() && token.isNotBlank() && !testing) {
                if (testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White) else Text("测试并保存")
            }
            result?.let { (ok, msg) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.Error, null, tint = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text(msg, Modifier.testTag("ha.result"), color = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error)
                }
            }
            if (ha.isConfigured) TextButton({ confirmDisconnect = true }) { Text("断开 Home Assistant", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (confirmDisconnect) ChoiceDialog("断开 Home Assistant？", "家具上的设备映射会保留，重新连接即可恢复。", listOf(Choice("断开", true) {
        store.saveSettings(store.settings.copy(haURL = "")); store.updateHAToken(""); url = ""; token = ""; result = null
        scope.launch { ha.refresh() }
    }), onDismiss = { confirmDisconnect = false })
}

// endregion
// region Rooms, categories, icons

@Composable
fun RoomsManagerScreen() {
    val store = LocalStore.current
    val router = LocalRouter.current
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<com.wanwu.nook.data.Room?>(null) }
    var deleting by remember { mutableStateOf<com.wanwu.nook.data.Room?>(null) }
    val counts = store.itemCountsByRoom()
    val rooms = store.sortedRooms
    Scaffold(topBar = { NookTopBar("房间", onBack = { router.back() }, actions = { TextButton({ adding = true }) { Text("添加") } }) },
        containerColor = LocalNookColors.current.canvas) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp)) {
            itemsIndexed(rooms, key = { _, r -> r.id }) { i, r ->
                Row(Modifier.fillMaxWidth().clickable { editing = r }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(SymbolIcons.vector(r.roomType.info.symbol), null)
                    Spacer(Modifier.width(10.dp))
                    Text(r.name, Modifier.weight(1f))
                    Text("${counts[r.id] ?: 0} 件", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton({ com.wanwu.nook.ui.home.swap(store, rooms, i, i - 1) }, enabled = i > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "上移") }
                    IconButton({ com.wanwu.nook.ui.home.swap(store, rooms, i, i + 1) }, enabled = i < rooms.lastIndex) { Icon(Icons.Outlined.KeyboardArrowDown, "下移") }
                    IconButton({ deleting = r }) { Icon(Icons.Outlined.Delete, "删除", tint = MaterialTheme.colorScheme.error) }
                }
                Divider()
            }
        }
    }
    if (adding) RoomEditorDialog(null) { adding = false }
    editing?.let { RoomEditorDialog(it) { editing = null } }
    deleting?.let { DeleteRoomDialog(it) { deleting = null } }
}

@Composable
private fun CategoryList(onEdit: (Category?) -> Unit, onDelete: (Category) -> Unit) {
    val store = LocalStore.current
    val list = store.sortedCategories
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        itemsIndexed(list, key = { _, c -> c.id }) { i, c ->
            Row(Modifier.fillMaxWidth().clickable { onEdit(c) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(SymbolIcons.vector(c.symbol), null, tint = hexColor(c.colorHex))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name)
                    if (c.tmpl != CategoryTemplate.general) Text(if (c.tmpl == CategoryTemplate.clothing) "衣物" else "消耗品",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton({ reorder(store, list, i, i - 1) }, enabled = i > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "上移") }
                IconButton({ reorder(store, list, i, i + 1) }, enabled = i < list.lastIndex) { Icon(Icons.Outlined.KeyboardArrowDown, "下移") }
                IconButton({ onDelete(c) }) { Icon(Icons.Outlined.Delete, "删除", tint = MaterialTheme.colorScheme.error) }
            }
            Divider()
        }
        item {
            TextButton({ onEdit(null) }) { Text("＋ 新建分类") }
            if (list.isEmpty()) TextButton({ store.seedDefaultCategoriesIfNeeded() }) { Text("恢复默认分类") }
        }
    }
}

private fun reorder(store: com.wanwu.nook.data.HomeStore, list: List<Category>, a: Int, b: Int) {
    if (b !in list.indices) return
    val l = list.toMutableList(); java.util.Collections.swap(l, a, b)
    l.forEachIndexed { i, c -> if (c.sortOrder != i) store.save(c.copy(sortOrder = i)) }
}

@Composable
private fun CategoryDialogs(editing: Category?, creating: Boolean, deleting: Category?, close: () -> Unit) {
    val store = LocalStore.current
    if (creating || editing != null) CategoryEditorDialog(editing, close)
    deleting?.let { d ->
        ChoiceDialog("删除分类「${d.name}」？", "这个分类下的物品会变成“未分类”，物品本身不会删除。", listOf(Choice("删除", true) { store.deleteCategory(d.id) }), close)
    }
}

@Composable
fun CategoryManagerScreen() {
    val router = LocalRouter.current
    var editing by remember { mutableStateOf<Category?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Category?>(null) }
    Scaffold(topBar = { NookTopBar("物品分类", onBack = { router.back() }) }, containerColor = LocalNookColors.current.canvas) { padding ->
        Box(Modifier.padding(padding)) { CategoryList({ if (it == null) creating = true else editing = it }, { deleting = it }) }
    }
    CategoryDialogs(editing, creating, deleting) { editing = null; creating = false; deleting = null }
}

/** Same manager, as a dialog (opened from the category picker). */
@Composable
fun CategoryManagerDialog(onDismiss: () -> Unit) {
    var editing by remember { mutableStateOf<Category?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Category?>(null) }
    EditorDialog("管理分类", onDismiss, onSave = onDismiss, saveLabel = "完成") {
        CategoryList({ if (it == null) creating = true else editing = it }, { deleting = it })
    }
    CategoryDialogs(editing, creating, deleting) { editing = null; creating = false; deleting = null }
}

@Composable
private fun CategoryEditorDialog(category: Category?, onDismiss: () -> Unit) {
    val store = LocalStore.current
    var name by remember { mutableStateOf(category?.name ?: "") }
    var symbol by remember { mutableStateOf(category?.symbol ?: "square.grid.2x2") }
    var color by remember { mutableStateOf(category?.colorHex ?: "#8E8E93") }
    var template by remember { mutableStateOf(category?.tmpl ?: CategoryTemplate.general) }
    var pickingSymbol by remember { mutableStateOf(false) }
    EditorDialog(if (category == null) "新建分类" else "编辑分类", onDismiss, onSave = {
        val base = category ?: Category(sortOrder = (store.categories.values.maxOfOrNull { it.sortOrder } ?: -1) + 1)
        store.save(base.copy(name = name.trim(), symbol = symbol, colorHex = color, template = template.name))
        onDismiss()
    }, saveEnabled = name.isNotBlank()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("名称") }, placeholder = { Text("例如：宠物用品") }, singleLine = true)
            Row(Modifier.clickable { pickingSymbol = true }, verticalAlignment = Alignment.CenterVertically) {
                Icon(SymbolIcons.vector(symbol), null, Modifier.size(32.dp), tint = hexColor(color))
                Spacer(Modifier.width(10.dp))
                Text("选择图标", color = MaterialTheme.colorScheme.primary)
            }
            Text("颜色", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DefaultCategories.colors.forEach { hex ->
                    Box(Modifier.size(32.dp).clip(CircleShape).background(hexColor(hex))
                        .border(2.dp, if (color == hex) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape).clickable { color = hex })
                }
            }
            Text("记录哪些信息", style = MaterialTheme.typography.labelLarge)
            CategoryTemplate.entries.forEach { t ->
                Row(Modifier.fillMaxWidth().clickable { template = t }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(template == t, { template = t }); Text(t.title)
                }
            }
        }
    }
    if (pickingSymbol) EditorDialog("选择图标", { pickingSymbol = false }, onSave = null) {
        SymbolGrid { symbol = it; pickingSymbol = false }
    }
}

@Composable
fun CustomIconsScreen() {
    val router = LocalRouter.current
    var drawing by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        NookTopBar("我的图标", onBack = { router.back() }, actions = { IconButton({ drawing = true }) { Icon(Icons.Outlined.AutoAwesome, "AI 绘制") } })
    }, containerColor = LocalNookColors.current.canvas) { padding ->
        Box(Modifier.padding(padding)) { CustomIconGrid({}, { drawing = true }) }
    }
    if (drawing) EditorDialog("AI 绘制图标", { drawing = false }, onSave = null) { AIIconGenerator("") { drawing = false } }
}

// endregion
