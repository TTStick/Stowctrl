package com.wanwu.nook.ui.items

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wanwu.nook.LocalContainer
import com.wanwu.nook.LocalRouter
import com.wanwu.nook.LocalStore
import com.wanwu.nook.Route
import com.wanwu.nook.data.Item
import com.wanwu.nook.ui.components.Choice
import com.wanwu.nook.ui.components.ChoiceDialog
import com.wanwu.nook.ui.components.Divider
import com.wanwu.nook.ui.components.EmptyState
import com.wanwu.nook.ui.components.NookTopBar
import com.wanwu.nook.ui.components.SymbolIcons
import com.wanwu.nook.ui.theme.LocalNookColors

private enum class Filter(val title: String) { All("全部"), Low("库存不足"), Expiring("过期/临期"), Favorite("收藏"), Unsorted("未归位") }

/** The master list: every item, searchable, grouped by where it is or what it is. */
@Composable
fun ItemsScreen() {
    val store = LocalStore.current
    val router = LocalRouter.current
    val prefs = LocalContainer.current.appPrefs
    var filter by remember { mutableStateOf(Filter.All) }
    var query by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var groupMenu by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<Item?>(null) }
    var moving by remember { mutableStateOf<Item?>(null) }
    var deleting by remember { mutableStateOf<Item?>(null) }

    val q = query.trim()
    val items = store.items.values.filter { item ->
        val pass = when (filter) {
            Filter.All -> true
            Filter.Low -> item.isLowStock
            Filter.Expiring -> item.isExpired || item.isExpiringSoon
            Filter.Favorite -> item.favorite
            Filter.Unsorted -> store.location(item) == null
        }
        pass && (q.isEmpty() || item.name.contains(q, true) || item.tags.any { it.contains(q, true) } || item.notes.contains(q, true)
            || store.categoryOf(item)?.name?.contains(q) == true || store.locationText(item).contains(q))
    }
    val groupTitle = mapOf("location" to "按位置", "category" to "按分类", "recent" to "最近更新")

    Scaffold(
        topBar = {
            NookTopBar("物品", actions = {
                Box {
                    IconButton({ groupMenu = true }) { Icon(Icons.Outlined.ViewAgenda, "分组：${groupTitle[prefs.grouping]}") }
                    DropdownMenu(groupMenu, { groupMenu = false }) {
                        groupTitle.forEach { (k, v) -> DropdownMenuItem({ Text((if (prefs.grouping == k) "✓ " else "    ") + v) }, { groupMenu = false; prefs.updateGrouping(k) }) }
                    }
                }
                IconButton({ adding = true }, Modifier.testTag("items.add")) { Icon(Icons.Outlined.Add, "添加物品") }
            })
        },
        containerColor = LocalNookColors.current.canvas,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("items.search"),
                placeholder = { Text("搜索名称、标签、位置") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Filter.entries.forEach { f ->
                    val n = when (f) {
                        Filter.All -> store.items.size
                        Filter.Low -> store.items.values.count { it.isLowStock }
                        Filter.Expiring -> store.items.values.count { it.isExpired || it.isExpiringSoon }
                        Filter.Favorite -> store.items.values.count { it.favorite }
                        Filter.Unsorted -> store.unsortedItems.size
                    }
                    FilterChip(filter == f, { filter = f }, { Text("${f.title} $n") })
                }
            }
            when {
                store.items.isEmpty() -> EmptyState("还没有物品", "在“家”里打开家具放入物品，或点右上角 + 直接添加。", SymbolIcons.vector("shippingbox")) {
                    Button({ adding = true }) { Text("添加物品") }
                }
                items.isEmpty() -> EmptyState("没有找到“$q”", "换个关键词试试")
                else -> LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                    sections(store, items, prefs.grouping).forEach { (title, list) ->
                        item(key = "h-$title") {
                            Text("$title · ${list.size}", Modifier.padding(top = 14.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(list, key = { "$title-${it.id}" }) { item ->
                            Box(Modifier.clickable { router.open(Route.Item(item.id)) }.testTag("row.${item.name}")) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.weight(1f)) { ItemRow(item) }
                                    IconButton({ store.adjustQuantity(item.id, -1.0) }) { Text("－") }
                                    IconButton({ actionsFor = item }) { Text("⋯") }
                                }
                            }
                            Divider()
                        }
                    }
                }
            }
        }
    }
    if (adding) ItemEditorDialog(null, null) { adding = false }
    actionsFor?.let { item ->
        val loc = store.location(item)
        ChoiceDialog(item.name, null, listOfNotNull(
            loc?.let { Choice("在户型图中定位") { router.showOnPlan(it.furniture) } },
            Choice("加 1") { store.adjustQuantity(item.id, 1.0) },
            Choice("移动") { moving = item },
            Choice("删除", true) { deleting = item },
        ), onDismiss = { actionsFor = null })
    }
    moving?.let { item -> LocationPickerSheet(item.compartment, onDismiss = { moving = null }) { store.moveItems(listOf(item.id), it) } }
    deleting?.let { item -> ChoiceDialog("删除「${item.name}」？", null, listOf(Choice("删除", true) { store.deleteItem(item.id) }), onDismiss = { deleting = null }) }
}

private fun sections(store: com.wanwu.nook.data.HomeStore, items: List<Item>, grouping: String): List<Pair<String, List<Item>>> = when (grouping) {
    "category" -> {
        val by = items.groupBy { it.category?.let { c -> store.categories[c] }?.id }
        store.sortedCategories.mapNotNull { c -> by[c.id]?.let { c.name to it.sortedBy { i -> i.name } } } +
            listOfNotNull(by[null]?.let { "未分类" to it.sortedBy { i -> i.name } })
    }
    "recent" -> listOf("全部" to items.sortedByDescending { it.updatedAt })
    else -> {
        val by = items.groupBy { store.location(it)?.room?.id }
        store.sortedRooms.mapNotNull { r -> by[r.id]?.let { r.name to it.sortedBy { i -> store.locationText(i) } } } +
            listOfNotNull(by[null]?.let { "未归位" to it.sortedByDescending { i -> i.updatedAt } })
    }
}
