package com.wanwu.nook.ui.items

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wanwu.nook.LocalAI
import com.wanwu.nook.LocalRouter
import com.wanwu.nook.net.AIService
import com.wanwu.nook.LocalStore
import com.wanwu.nook.LocalSync
import com.wanwu.nook.Tab as AppTab
import com.wanwu.nook.data.CustomIcon
import com.wanwu.nook.data.IconRef
import com.wanwu.nook.data.SymbolEntry
import com.wanwu.nook.data.SymbolLibrary
import com.wanwu.nook.image.SvgDocument
import com.wanwu.nook.ui.components.Choice
import com.wanwu.nook.ui.components.ChoiceDialog
import com.wanwu.nook.ui.components.EditorDialog
import com.wanwu.nook.ui.components.SvgIcon
import com.wanwu.nook.ui.components.SymbolIcons
import com.wanwu.nook.ui.components.TextInputDialog
import kotlinx.coroutines.launch

@Composable
fun IconPickerDialog(suggestion: String, onDismiss: () -> Unit, onPick: (IconRef) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    fun pick(r: IconRef) { onPick(r); onDismiss() }
    EditorDialog("选择图标", onDismiss, onSave = null) {
        Column(Modifier.fillMaxSize()) {
            PrimaryTabRow(tab) {
                listOf("图标库", "我的图标", "AI 绘制", "Emoji").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
            }
            when (tab) {
                0 -> SymbolGrid(suggestion) { pick(IconRef.symbol(it)) }
                1 -> CustomIconGrid({ pick(IconRef.custom(it)) }) { tab = 2 }
                2 -> AIIconGenerator(suggestion) { pick(IconRef.custom(it)) }
                else -> EmojiPicker { pick(IconRef.emoji(it)) }
            }
        }
    }
}

@Composable
fun SymbolGrid(initialQuery: String = "", onPick: (String) -> Unit) {
    var query by remember { mutableStateOf(if (initialQuery.isNotBlank() && SymbolLibrary.search(initialQuery).isNotEmpty()) initialQuery else "") }
    val primary = MaterialTheme.colorScheme.primary
    Column {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(16.dp), placeholder = { Text("搜索：杯子、螺丝刀…") }, singleLine = true)
        LazyVerticalGrid(GridCells.Adaptive(60.dp), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val sections: List<Pair<String, List<SymbolEntry>>> =
                if (query.isBlank()) SymbolLibrary.groups.map { it.title to it.entries } else listOf("" to SymbolLibrary.search(query))
            sections.forEach { (title, entries) ->
                if (title.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (entries.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("图标库里没有“$query”，可以试试 AI 绘制", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(entries, key = { "$title-${it.name}" }) { e ->
                    Column(Modifier.clip(RoundedCornerShape(10.dp)).background(Color.Gray.copy(alpha = 0.08f)).clickable { onPick(e.name) }
                        .padding(vertical = 8.dp).semantics { contentDescription = e.keywords }.testTag("symbol.${e.name}"),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(SymbolIcons.vector(e.name), null, Modifier.size(26.dp), tint = primary)
                        Text(e.keywords.split(" ").first(), style = MaterialTheme.typography.labelSmall, maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun CustomIconGrid(onPick: (String) -> Unit, onDrawNew: () -> Unit) {
    val store = LocalStore.current
    var renaming by remember { mutableStateOf<CustomIcon?>(null) }
    var deleting by remember { mutableStateOf<CustomIcon?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    val icons = store.sortedIcons
    val primary = MaterialTheme.colorScheme.primary
    if (icons.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("还没有自己的图标", style = MaterialTheme.typography.titleMedium)
            Text("用 AI 画出图标库里没有的东西，会保存在这里，所有设备共享。", textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Button(onDrawNew) { Text("AI 绘制") }
        }
    } else {
        LazyVerticalGrid(GridCells.Adaptive(80.dp), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(icons, key = { it.id }) { icon ->
                Box {
                    Column(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.Gray.copy(alpha = 0.08f))
                        .combinedClickable(onClick = { onPick(icon.id) }, onLongClick = { menuFor = icon.id }).padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        SvgIcon(icon.svg, primary, Modifier.size(44.dp))
                        Text(icon.name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                    DropdownMenu(menuFor == icon.id, { menuFor = null }) {
                        DropdownMenuItem({ Text("重命名") }, { menuFor = null; renaming = icon })
                        DropdownMenuItem({ Text("删除", color = MaterialTheme.colorScheme.error) }, { menuFor = null; deleting = icon })
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("长按图标可以重命名或删除", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    renaming?.let { i -> TextInputDialog("重命名图标", i.name, onConfirm = { store.save(i.copy(name = it)) }, onDismiss = { renaming = null }) }
    deleting?.let { i ->
        ChoiceDialog("删除图标「${i.name}」？", "用到这个图标的物品会改回分类图标。", listOf(Choice("删除", true) { store.deleteIcon(i.id) }),
            onDismiss = { deleting = null })
    }
}

@Composable
fun AIIconGenerator(initialPrompt: String, onUse: (String) -> Unit) {
    val store = LocalStore.current
    val sync = LocalSync.current
    val ai = LocalAI.current
    val router = LocalRouter.current
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf(initialPrompt) }
    var svg by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val primary = MaterialTheme.colorScheme.primary

    fun generate() {
        if (prompt.isBlank()) return
        scope.launch {
            working = true; error = null
            try {
                svg = ai.generate(prompt.trim(), sync)
            } catch (e: Exception) {
                error = e.message
            } finally {
                working = false
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val openSettings = { router.tab = AppTab.Me; router.current.clear(); router.open(com.wanwu.nook.Route.AISettings) }
        when (ai.availability(sync)) {
            AIService.Availability.NeedsKey -> Requirement("还没有填写 API Key", "本机直连模式会用你自己的 Claude API Key 直接请求大模型，不经过服务器。Key 只加密保存在这台设备上。") {
                Button(openSettings) { Text("AI 绘制设置") }
            }
            AIService.Availability.Offline -> Requirement("离线模式下不连服务器", "可以关闭离线模式，或者改用本机直连（填自己的 API Key）。") {
                Button(openSettings) { Text("AI 绘制设置") }
            }
            AIService.Availability.NeedsLogin -> Requirement("没有连接同步服务器", "可以登录自己部署的服务器使用它的大模型，或者改用本机直连（填自己的 API Key），不需要服务器。") {
                Button(openSettings) { Text("AI 绘制设置") }
                OutlinedButton({ router.tab = AppTab.Me }) { Text("去登录") }
            }
            AIService.Availability.ServerWithoutLLM -> Requirement("服务器还没有配置大模型", "在服务器的 .env 里填写 ANTHROPIC_API_KEY 并重启容器；或者改用本机直连。") {
                OutlinedButton({ scope.launch { sync.refreshServerInfo() } }) { Text("重新检查") }
                Button(openSettings) { Text("AI 绘制设置") }
            }
            AIService.Availability.Ready -> {
                OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), placeholder = { Text("要画什么？例如：汤勺、电动牙刷") }, singleLine = true)
                Box(Modifier.size(200.dp).clip(RoundedCornerShape(20.dp)).background(Color.Gray.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                    when {
                        working -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text("AI 正在画…（约 10-30 秒）", style = MaterialTheme.typography.bodySmall)
                        }
                        svg != null -> SvgIcon(svg!!, primary, Modifier.size(140.dp))
                        else -> Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.outline)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton({ generate() }, enabled = prompt.isNotBlank() && !working) { Text(if (svg == null) "开始绘制" else "重新绘制") }
                    if (svg != null) Button({
                        val icon = CustomIcon(name = prompt.trim(), svg = svg!!)
                        store.save(icon)
                        onUse(icon.id)
                    }, enabled = !working) { Text("使用这个图标") }
                }
                Text(if (ai.source == AIService.Source.direct) "本机直连 · 画好的图标会保存到“我的图标”" else "通过同步服务器 · 画好的图标会保存到“我的图标”，所有设备都能用",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun Requirement(title: String, detail: String, action: @Composable () -> Unit) {
    Column(Modifier.padding(top = 30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.Dns, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.outline)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action()
    }
}

private val commonEmoji = listOf(
    "厨房" to "🥄 🍴 🥢 🍽️ 🥣 🍳 🫕 🥡 🍶 🫖 ☕ 🍵 🥛 🧂 🔪 🧊",
    "食物" to "🍚 🍜 🥫 🍪 🍫 🍬 🥜 🍞 🧀 🥚 🍎 🍌 🥕 🥔 🧅 🧄 🌶️ 🍯 🧃 🍷 🍺 🥤",
    "日用" to "🧺 🧴 🧻 🪥 🧽 🧹 🪣 🧼 🪒 💡 🔋 🔌 🕯️ 🧯 🪮 🧷 🧵 🪡",
    "衣物" to "👕 👖 👗 🧥 🧦 🧤 🧣 👟 👠 🥾 👜 🎒 👓 🕶️ 👒 🧢 💍 ⌚",
    "工具" to "🧰 🪛 🔧 🔨 🪚 📏 ✂️ 🪜 🔦 🧲 🪝 🔑 🔒",
    "其他" to "💊 🩹 🌡️ 📚 📎 ✏️ 🖊️ 📦 🎁 🧸 🎮 🎲 🪴 ☂️ 🧳 🐶 🐱",
)

@Composable
fun EmojiPicker(onPick: (String) -> Unit) {
    var custom by remember { mutableStateOf("") }
    LazyVerticalGrid(GridCells.Adaptive(52.dp), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(custom, { custom = it }, Modifier.weight(1f), placeholder = { Text("输入任意 Emoji") }, singleLine = true)
                Button({ firstGrapheme(custom)?.let(onPick) }, Modifier.padding(start = 8.dp), enabled = custom.isNotBlank()) { Text("使用") }
            }
        }
        commonEmoji.forEach { (title, list) ->
            item(span = { GridItemSpan(maxLineSpan) }) { Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(list.split(" "), key = { "$title$it" }) { e ->
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Color.Gray.copy(alpha = 0.08f)).clickable { onPick(e) },
                    contentAlignment = Alignment.Center) { Text(e, fontSize = 26.sp) }
            }
        }
    }
}

private fun firstGrapheme(s: String): String? {
    val t = s.trim()
    if (t.isEmpty()) return null
    val it = java.text.BreakIterator.getCharacterInstance()
    it.setText(t)
    return t.substring(0, it.next())
}
