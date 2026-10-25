package com.wanwu.nook.ui.furniture

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wanwu.nook.LocalHA
import com.wanwu.nook.LocalRouter
import com.wanwu.nook.LocalStore
import com.wanwu.nook.Route
import com.wanwu.nook.data.Compartment
import com.wanwu.nook.data.DeletePolicy
import com.wanwu.nook.data.clean
import com.wanwu.nook.ui.art.FurnitureArt
import com.wanwu.nook.ui.components.Card
import com.wanwu.nook.ui.components.Choice
import com.wanwu.nook.ui.components.ChoiceDialog
import com.wanwu.nook.ui.components.Divider
import com.wanwu.nook.ui.components.EditorDialog
import com.wanwu.nook.ui.components.EmptyState
import com.wanwu.nook.ui.components.ItemThumbnail
import com.wanwu.nook.ui.components.NookTopBar
import com.wanwu.nook.ui.components.TextInputDialog
import com.wanwu.nook.ui.devices.EntityPickerDialog
import com.wanwu.nook.ui.devices.EntityRow
import com.wanwu.nook.ui.home.DeleteFurnitureDialog
import com.wanwu.nook.ui.home.FurnitureEditorDialog
import com.wanwu.nook.ui.items.ItemEditorDialog
import com.wanwu.nook.ui.theme.LocalNookColors

/** A piece of furniture "opened": doors swing aside to show each compartment as a shelf. */
@Composable
fun FurnitureDetailScreen(furnitureId: String) {
    val store = LocalStore.current
    val router = LocalRouter.current
    val ha = LocalHA.current
    val f = store.furniture[furnitureId]
    if (f == null) {
        Scaffold(topBar = { NookTopBar("家具", onBack = { router.back() }) }) { p ->
            Box(Modifier.padding(p)) { EmptyState("家具已删除", "这件家具可能在另一台设备上被删除了。") }
        }
        return
    }
    val comps = store.compartmentsOf(f.id)
    var addingTo by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf(false) }
    var binding by remember { mutableStateOf(false) }
    var newCompartment by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val colors = LocalNookColors.current
    val palette = FurnitureArt.palette(f, colors.dark)
    val door = remember { Animatable(0f) }
    var cabinetHeight by remember { mutableIntStateOf(0) }
    LaunchedEffect(furnitureId) { kotlinx.coroutines.delay(150); door.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
    LaunchedEffect(f.haEntities) { if (f.haEntities.isNotEmpty()) ha.refresh() }

    Scaffold(
        topBar = {
            NookTopBar(f.name, onBack = { router.back() }, actions = {
                IconButton({ addingTo = comps.firstOrNull()?.id; adding = true }) { Icon(Icons.Outlined.Add, "添加物品") }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Outlined.MoreVert, "更多") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("家具属性") }, { menu = false; editing = true })
                        DropdownMenuItem({ Text("管理收纳位置") }, { menu = false; managing = true })
                        DropdownMenuItem({ Text("绑定智能设备") }, { menu = false; binding = true })
                        DropdownMenuItem({ Text("在户型图中查看") }, { menu = false; router.showOnPlan(f) })
                        DropdownMenuItem({ Text("删除家具", color = MaterialTheme.colorScheme.error) }, { menu = false; deleting = true })
                    }
                }
            })
        },
        containerColor = colors.canvas,
    ) { padding ->
      Box(Modifier.padding(padding).fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // The cabinet: compartments as shelves, behind two doors that swing open.
            Box(Modifier.fillMaxWidth().onGloballyPositioned { cabinetHeight = it.size.height }
                .clip(RoundedCornerShape(12.dp)).background(palette.fill)
                .border(1.5.dp, palette.stroke, RoundedCornerShape(12.dp)).padding(8.dp)) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(colors.card)) {
                    if (comps.isEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.CropSquare, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.outline)
                            Text("还没有收纳位置", style = MaterialTheme.typography.titleSmall)
                            Text("给这件家具加上层、抽屉或格子，就能往里放东西了。", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(10.dp))
                            Button({ newCompartment = true }) { Text("添加收纳位置") }
                        }
                    } else {
                        comps.forEachIndexed { i, c ->
                            ShelfRow(c) { addingTo = c.id; adding = true }
                            if (i < comps.lastIndex) Box(Modifier.fillMaxWidth().height(6.dp).background(palette.fill))
                        }
                    }
                }
            }
            if (comps.isNotEmpty()) {
                TextButton({ newCompartment = true }, Modifier.align(Alignment.CenterHorizontally)) { Text("＋ 添加收纳位置") }
            }
            if (f.haEntities.isNotEmpty()) {
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("智能设备", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        TextButton({ binding = true }) { Text("管理") }
                    }
                    ha.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    f.haEntities.forEachIndexed { i, id ->
                        EntityRow(id)
                        if (i < f.haEntities.lastIndex) Divider()
                    }
                }
            }
            val parent = f.parentId?.let { store.furniture[it] }
            val kids = store.childrenOf(f.id)
            if (parent != null || kids.isNotEmpty()) {
                Card {
                    Text("叠放", style = MaterialTheme.typography.titleSmall)
                    parent?.let { p -> LinkRow("放在", p.name) { router.open(Route.Furniture(p.id)) } }
                    kids.forEach { k -> LinkRow("上面放着", k.name) { router.open(Route.Furniture(k.id)) } }
                }
            }
            Card {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("位置", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(store.rooms[f.roomId]?.name ?: "", Modifier.clickable { router.showOnPlan(f) }, color = MaterialTheme.colorScheme.primary)
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("尺寸", color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.weight(1f))
                    Text("${f.width.toInt()} × ${f.depth.toInt()} cm")
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("物品", color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.weight(1f))
                    Text("${store.itemCount(f.id)} 件")
                }
                if (f.notes.isNotEmpty()) { Divider(); Text(f.notes, Modifier.padding(top = 6.dp)) }
                if (f.haEntities.isEmpty()) {
                    Divider()
                    TextButton({ binding = true }) { Text("绑定 Home Assistant 设备") }
                }
            }
        }
        // Swinging doors overlay the cabinet at the top of the page.
        if (door.value < 0.99f && cabinetHeight > 0) {
            Row(Modifier.padding(16.dp).fillMaxWidth().height(with(LocalDensity.current) { cabinetHeight.toDp() })) {
                listOf(true, false).forEach { left ->
                    Box(Modifier.weight(1f).fillMaxSize().graphicsLayer {
                        rotationY = (if (left) -100f else 100f) * door.value
                        transformOrigin = TransformOrigin(if (left) 0f else 1f, 0.5f)
                        cameraDistance = 12 * density
                        alpha = 1f - door.value
                    }.clip(RoundedCornerShape(10.dp)).background(palette.fill).border(1.5.dp, palette.stroke, RoundedCornerShape(10.dp))) {
                        Box(Modifier.align(if (left) Alignment.CenterEnd else Alignment.CenterStart).padding(10.dp).size(4.dp, 34.dp)
                            .clip(CircleShape).background(palette.stroke))
                    }
                }
            }
        }
      }
    }
    if (adding) ItemEditorDialog(null, addingTo, onDismiss = { adding = false })
    if (editing) FurnitureEditorDialog(f, null, onDismiss = { editing = false })
    if (deleting) DeleteFurnitureDialog(f, onDone = { router.back() }) { deleting = false }
    if (managing) CompartmentManagerDialog(f.id) { managing = false }
    if (binding) EntityPickerDialog(f.id) { binding = false }
    if (newCompartment) TextInputDialog("新的收纳位置", placeholder = "例如：第 2 层", confirm = "添加",
        onConfirm = { store.addCompartment(f.id, it) }, onDismiss = { newCompartment = false })
}

@Composable
private fun LinkRow(label: String, name: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(name, Modifier.weight(1f))
        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}

/** One compartment as a shelf: name, count, a strip of thumbnails. */
@Composable
fun ShelfRow(c: Compartment, onAdd: () -> Unit) {
    val store = LocalStore.current
    val router = LocalRouter.current
    val items = store.itemsIn(c.id)
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Row(Modifier.fillMaxWidth().clickable { router.open(Route.Compartment(c.id)) }.testTag("shelf.${c.name}"),
            verticalAlignment = Alignment.CenterVertically) {
            Text(c.name, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(6.dp))
            Text("${items.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items.take(20).forEach { item ->
                Column(Modifier.width(60.dp).clickable { router.open(Route.Item(item.id)) }.testTag("thumb.${item.name}"),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box {
                        ItemThumbnail(item, 56.dp)
                        if (item.quantity != 1.0) Text(item.quantity.clean(), Modifier.align(Alignment.TopEnd).offset(4.dp, (-4).dp)
                            .clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 4.dp),
                            fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(item.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(Modifier.width(60.dp).clickable(onClick = onAdd).semantics { contentDescription = "放入物品到${c.name}" }
                .testTag("add.${c.name}"), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp))
                    .border(BorderStroke(1.2.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Add, null, tint = MaterialTheme.colorScheme.outline)
                }
                Text("放入", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Add, rename, reorder and delete compartments. */
@Composable
fun CompartmentManagerDialog(furnitureId: String, onDismiss: () -> Unit) {
    val store = LocalStore.current
    var renaming by remember { mutableStateOf<Compartment?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Compartment?>(null) }
    val list = store.compartmentsOf(furnitureId)
    EditorDialog("收纳位置", onDismiss, onSave = onDismiss, saveLabel = "完成") {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            itemsIndexed(list, key = { _, c -> c.id }) { i, c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { renaming = c }) {
                        Text(c.name)
                        Text("${store.itemsIn(c.id).size} 件 · 点按改名", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton({ move(store, list, i, i - 1) }, enabled = i > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "上移") }
                    IconButton({ move(store, list, i, i + 1) }, enabled = i < list.lastIndex) { Icon(Icons.Outlined.KeyboardArrowDown, "下移") }
                    IconButton({ renaming = c }) { Icon(Icons.Outlined.Edit, "重命名") }
                    IconButton({ deleting = c }) { Icon(Icons.Outlined.Delete, "删除", tint = MaterialTheme.colorScheme.error) }
                }
                Divider()
            }
            item { TextButton({ adding = true }) { Text("＋ 添加收纳位置") } }
        }
    }
    renaming?.let { c -> TextInputDialog("重命名", c.name, onConfirm = { store.save(c.copy(name = it)) }, onDismiss = { renaming = null }) }
    if (adding) TextInputDialog("新的收纳位置", placeholder = "例如：抽屉 2", confirm = "添加", onConfirm = { store.addCompartment(furnitureId, it) }, onDismiss = { adding = false })
    deleting?.let { c -> DeleteCompartmentDialog(c) { deleting = null } }
}

private fun move(store: com.wanwu.nook.data.HomeStore, list: List<Compartment>, a: Int, b: Int) {
    if (b !in list.indices) return
    val l = list.toMutableList()
    java.util.Collections.swap(l, a, b)
    store.reorderCompartments(l)
}

@Composable
fun DeleteCompartmentDialog(c: Compartment, onDone: () -> Unit = {}, onDismiss: () -> Unit) {
    val store = LocalStore.current
    val count = store.itemsIn(c.id).size
    ChoiceDialog("删除「${c.name}」？", if (count > 0) "这里有 $count 件物品。" else "此操作无法撤销。",
        if (count > 0) listOf(
            Choice("删除，物品移到未归位") { store.deleteCompartment(c.id, DeletePolicy.KeepItems); onDone() },
            Choice("连同 $count 件物品一起删除", true) { store.deleteCompartment(c.id, DeletePolicy.DeleteItems); onDone() },
        ) else listOf(Choice("删除", true) { store.deleteCompartment(c.id, DeletePolicy.KeepItems); onDone() }),
        onDismiss)
}
