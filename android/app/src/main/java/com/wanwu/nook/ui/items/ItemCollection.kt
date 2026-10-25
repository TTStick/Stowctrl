package com.wanwu.nook.ui.items

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wanwu.nook.LocalContainer
import com.wanwu.nook.LocalRouter
import com.wanwu.nook.LocalStore
import com.wanwu.nook.Route
import com.wanwu.nook.data.Item
import com.wanwu.nook.data.clean
import com.wanwu.nook.ui.components.ColorDots
import com.wanwu.nook.ui.components.Divider
import com.wanwu.nook.ui.components.EmptyState
import com.wanwu.nook.ui.components.IconTile
import com.wanwu.nook.ui.components.ItemBadges
import com.wanwu.nook.ui.components.ItemThumbnail
import com.wanwu.nook.ui.components.NookTopBar
import com.wanwu.nook.ui.components.StoredImage
import com.wanwu.nook.ui.components.SymbolIcons
import com.wanwu.nook.ui.components.TextInputDialog
import com.wanwu.nook.ui.furniture.DeleteCompartmentDialog
import com.wanwu.nook.ui.settings.CategoryManagerDialog
import com.wanwu.nook.ui.theme.LocalNookColors
import com.wanwu.nook.ui.theme.hexColor

@Composable
fun ItemRow(item: Item, showLocation: Boolean = true, modifier: Modifier = Modifier) {
    val store = LocalStore.current
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ItemThumbnail(item, 46.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ItemBadges(item)
            }
            Text(if (showLocation) store.locationText(item) else store.categoryOf(item)?.name ?: "未分类",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Text("${item.quantity.clean()} ${item.unit}", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ItemCard(item: Item) {
    val store = LocalStore.current
    val colors = LocalNookColors.current
    val cat = store.categoryOf(item)
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(colors.photo), contentAlignment = Alignment.Center) {
            val photo = item.photoId
            if (photo != null) StoredImage(photo, Modifier.fillMaxSize().padding(8.dp), 400)
            else IconTile(item.icon, cat?.symbol ?: "shippingbox", hexColor(cat?.colorHex), 64.dp)
            if (item.quantity != 1.0) Text("×${item.quantity.clean()}", Modifier.align(Alignment.TopEnd).padding(6.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 6.dp),
                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.heightIn(min = 14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (item.colors.isNotEmpty()) ColorDots(item.colors, 10.dp)
            ItemBadges(item)
        }
    }
}

/** A set of items with grid/list layout and multi-select actions. */
@Composable
fun ItemCollection(items: List<Item>, title: String, subtitle: String? = null, showLocation: Boolean = true,
                   emptyTitle: String = "这里还没有东西", onAdd: (() -> Unit)?, menu: (@Composable (() -> Unit) -> Unit)? = null) {
    val store = LocalStore.current
    val router = LocalRouter.current
    val prefs = LocalContainer.current.appPrefs
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var moving by remember { mutableStateOf(false) }
    var categorizing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    fun tap(item: Item) {
        if (selecting) selected = if (item.id in selected) selected - item.id else selected + item.id
        else router.open(Route.Item(item.id))
    }

    Scaffold(
        topBar = {
            NookTopBar(title, onBack = { router.back() }, subtitle = subtitle, actions = {
                if (items.isNotEmpty()) {
                    TextButton({ selecting = !selecting; selected = emptySet() }) { Text(if (selecting) "完成" else "选择") }
                    if (!selecting) IconButton({ prefs.updateGridLayout(!prefs.gridLayout) }) {
                        Icon(if (prefs.gridLayout) Icons.Outlined.ViewList else Icons.Outlined.GridView, if (prefs.gridLayout) "列表" else "网格")
                    }
                }
                if (onAdd != null && !selecting) IconButton(onAdd, Modifier.testTag("collection.add")) { Icon(Icons.Outlined.Add, "添加物品") }
                if (menu != null) Box {
                    IconButton({ menuOpen = true }) { Icon(Icons.Outlined.MoreVert, "更多") }
                    DropdownMenu(menuOpen, { menuOpen = false }) { menu { menuOpen = false } }
                }
            })
        },
        bottomBar = {
            if (selecting) BottomAppBar {
                TextButton({ selected = if (selected.size == items.size) emptySet() else items.map { it.id }.toSet() }) {
                    Text(if (selected.size == items.size) "全不选" else "全选")
                }
                Spacer(Modifier.weight(1f))
                TextButton({ moving = true }, enabled = selected.isNotEmpty()) { Text("移动") }
                TextButton({ categorizing = true }, enabled = selected.isNotEmpty()) { Text("分类") }
                TextButton({ confirmDelete = true }, enabled = selected.isNotEmpty()) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }
        },
        containerColor = LocalNookColors.current.canvas,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                items.isEmpty() -> EmptyState(emptyTitle, icon = SymbolIcons.vector("shippingbox")) {
                    if (onAdd != null) Button(onAdd) { Text("放入物品") }
                }
                prefs.gridLayout -> LazyVerticalGrid(GridCells.Adaptive(104.dp), contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(items, key = { it.id }) { item ->
                        Box(Modifier.clickable { tap(item) }.testTag("item.${item.name}")) {
                            ItemCard(item)
                            if (selecting) SelectMark(item.id in selected)
                        }
                    }
                }
                else -> LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    items(items, key = { it.id }) { item ->
                        Box(Modifier.clickable { tap(item) }.testTag("item.${item.name}")) {
                            ItemRow(item, showLocation)
                            if (selecting) SelectMark(item.id in selected)
                        }
                        Divider()
                    }
                }
            }
        }
    }
    if (moving) LocationPickerSheet(null, onDismiss = { moving = false }) {
        store.moveItems(selected, it); selected = emptySet(); selecting = false
    }
    if (categorizing) CategoryPickerSheet(null, onDismiss = { categorizing = false }) {
        store.setCategory(selected, it); selected = emptySet(); selecting = false
    }
    if (confirmDelete) com.wanwu.nook.ui.components.ChoiceDialog("删除 ${selected.size} 件物品？", null,
        listOf(com.wanwu.nook.ui.components.Choice("删除", true) { store.deleteItems(selected); selected = emptySet(); selecting = false }),
        onDismiss = { confirmDelete = false })
}

@Composable
private fun SelectMark(on: Boolean) {
    Icon(if (on) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, if (on) "已选" else "未选",
        Modifier.padding(4.dp).clip(CircleShape).background(LocalNookColors.current.card),
        tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
}

@Composable
fun CompartmentScreen(compartmentId: String) {
    val store = LocalStore.current
    val router = LocalRouter.current
    val c = store.compartments[compartmentId]
    if (c == null) {
        Scaffold(topBar = { NookTopBar("收纳位置", onBack = { router.back() }) }) { p -> Box(Modifier.padding(p)) { EmptyState("收纳位置已删除") } }
        return
    }
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val f = store.furniture[c.furnitureId]
    val path = listOfNotNull(f?.let { store.rooms[it.roomId]?.name }, f?.name).joinToString(" › ")
    ItemCollection(store.itemsIn(c.id), c.name, path, showLocation = false, emptyTitle = "「${c.name}」是空的", onAdd = { adding = true }) { close ->
        DropdownMenuItem({ Text("重命名") }, { close(); renaming = true })
        DropdownMenuItem({ Text("删除收纳位置", color = MaterialTheme.colorScheme.error) }, { close(); deleting = true })
    }
    if (adding) ItemEditorDialog(null, c.id) { adding = false }
    if (renaming) TextInputDialog("重命名", c.name, onConfirm = { store.save(c.copy(name = it)) }, onDismiss = { renaming = false })
    if (deleting) DeleteCompartmentDialog(c, onDone = { router.back() }) { deleting = false }
}

@Composable
fun UnsortedScreen() {
    val store = LocalStore.current
    var adding by remember { mutableStateOf(false) }
    ItemCollection(store.unsortedItems, "未归位", showLocation = false, emptyTitle = "所有物品都已归位", onAdd = { adding = true })
    if (adding) ItemEditorDialog(null, null) { adding = false }
}

// region Pickers

/** Room › furniture › compartment, or "未归位". */
@Composable
fun LocationPickerSheet(current: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val store = LocalStore.current
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var addingTo by remember { mutableStateOf<String?>(null) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun pick(id: String?) { onPick(id); onDismiss() }
    ModalBottomSheet(onDismiss, sheetState = state) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("放到哪里", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("搜索房间、家具或位置") }, singleLine = true)
        }
        LazyColumn(Modifier.fillMaxWidth().height(520.dp).testTag("loc.list"), contentPadding = PaddingValues(16.dp)) {
            if (query.isBlank()) {
                item { PickRow("未归位", "暂时不放进具体位置", current == null) { pick(null) } }
                store.sortedRooms.forEach { room ->
                    val pieces = store.furnitureIn(room.id)
                    if (pieces.isNotEmpty()) {
                        item { Text(room.name, Modifier.padding(top = 14.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                        pieces.forEach { f ->
                            item(key = f.id) {
                                val open = f.id in expanded
                                Row(Modifier.fillMaxWidth().clickable { expanded = if (open) expanded - f.id else expanded + f.id }
                                    .padding(vertical = 10.dp).testTag("loc.${f.name}"), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(SymbolIcons.vector(f.symbol ?: com.wanwu.nook.data.Catalog.kind(f.kind).symbol), null, Modifier.size(20.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text(f.name, Modifier.weight(1f))
                                    Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                                }
                            }
                            if (f.id in expanded) {
                                store.compartmentsOf(f.id).forEach { c ->
                                    item(key = c.id) {
                                        Box(Modifier.padding(start = 30.dp)) { PickRow(c.name, "${store.itemsIn(c.id).size} 件", c.id == current) { pick(c.id) } }
                                    }
                                }
                                item(key = "add-${f.id}") {
                                    TextButton({ addingTo = f.id }, Modifier.padding(start = 22.dp)) { Text("＋ 新建收纳位置") }
                                }
                            }
                        }
                    }
                }
                if (store.furniture.isEmpty()) item { Text("还没有家具，先去“家”里添加家具吧", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                val matches = store.compartments.values.filter { store.compartmentPath(it.id).contains(query.trim()) }
                    .sortedBy { store.compartmentPath(it.id) }
                items(matches, key = { it.id }) { c -> PickRow(c.name, store.compartmentPath(c.id), c.id == current) { pick(c.id) } }
            }
        }
    }
    addingTo?.let { fid ->
        TextInputDialog("新的收纳位置", placeholder = "例如：第 2 层", confirm = "添加并选择",
            onConfirm = { pick(store.addCompartment(fid, it).id) }, onDismiss = { addingTo = null })
    }
}

@Composable
private fun PickRow(title: String, subtitle: String, checked: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp).testTag("pick.$title"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (checked) Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun CategoryPickerSheet(current: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val store = LocalStore.current
    var managing by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().height(480.dp), contentPadding = PaddingValues(16.dp)) {
            item { Text("选择分类", style = MaterialTheme.typography.titleMedium) }
            item { PickRow("未分类", "", current == null) { onPick(null); onDismiss() } }
            items(store.sortedCategories, key = { it.id }) { c ->
                Row(Modifier.fillMaxWidth().clickable { onPick(c.id); onDismiss() }.padding(vertical = 10.dp).testTag("cat.${c.name}"),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(SymbolIcons.vector(c.symbol), null, tint = hexColor(c.colorHex))
                    Spacer(Modifier.width(12.dp))
                    Text(c.name, Modifier.weight(1f))
                    if (c.id == current) Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            item { TextButton({ managing = true }) { Text("管理分类") } }
        }
    }
    if (managing) CategoryManagerDialog { managing = false }
}

// endregion
