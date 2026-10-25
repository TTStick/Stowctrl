package com.wanwu.nook.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.wanwu.nook.LocalRouter
import com.wanwu.nook.LocalStore
import com.wanwu.nook.Route
import com.wanwu.nook.data.DeletePolicy
import com.wanwu.nook.data.Rect
import com.wanwu.nook.data.Room
import com.wanwu.nook.data.RoomType
import com.wanwu.nook.data.clean
import com.wanwu.nook.data.info
import com.wanwu.nook.data.metres
import com.wanwu.nook.data.snap
import com.wanwu.nook.ui.art.FurnitureArt.drawRoomFurniture
import com.wanwu.nook.ui.components.Card
import com.wanwu.nook.ui.components.Choice
import com.wanwu.nook.ui.components.ChoiceDialog
import com.wanwu.nook.ui.components.CmField
import com.wanwu.nook.ui.components.EditorDialog
import com.wanwu.nook.ui.components.EmptyState
import com.wanwu.nook.ui.components.NookTopBar
import com.wanwu.nook.ui.components.SymbolIcons
import com.wanwu.nook.ui.theme.LocalNookColors
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun HomeScreen() {
    val store = LocalStore.current
    val router = LocalRouter.current
    var editing by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<String?>(null) }
    var showList by remember { mutableStateOf(false) }
    var addingRoom by remember { mutableStateOf(false) }
    var editingRoom by remember { mutableStateOf<Room?>(null) }
    var deletingRoom by remember { mutableStateOf<Room?>(null) }

    Scaffold(
        topBar = {
            NookTopBar(store.settings.homeName, actions = {
                IconButton({ showList = !showList; editing = false; selection = null }, enabled = store.rooms.isNotEmpty()) {
                    Icon(if (showList) Icons.Outlined.GridView else Icons.Outlined.ViewList, if (showList) "户型图" else "房间列表")
                }
                if (!showList && store.rooms.isNotEmpty()) {
                    TextButton({ editing = !editing; selection = null }, Modifier.testTag("home.edit")) {
                        Text(if (editing) "完成" else "编辑户型", fontWeight = if (editing) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
                IconButton({ addingRoom = true }) { Icon(Icons.Outlined.Add, "添加房间") }
            })
        },
        containerColor = LocalNookColors.current.canvas,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                store.rooms.isEmpty() -> EmptyState("还没有房间", "先添加房间，再把家具摆进去。", SymbolIcons.vector("sofa")) {
                    Button({ addingRoom = true }) { Text("添加房间") }
                }
                showList -> RoomList(onEdit = { editingRoom = it }, onDelete = { deletingRoom = it })
                else -> {
                    FloorPlanCanvas(editing, selection, { selection = it }) { router.open(Route.Room(it.id)) }
                    Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 10.dp)) {
                        val sel = selection?.let { store.rooms[it] }
                        when {
                            editing && sel != null -> RoomInspector(sel, onOpen = { router.open(Route.Room(sel.id)) },
                                onEdit = { editingRoom = sel }, onDelete = { deletingRoom = sel })
                            editing -> Hint("拖动房间调整位置，拖右下角圆点改大小，点房间查看操作")
                            else -> SummaryBar()
                        }
                    }
                }
            }
        }
    }
    if (addingRoom) RoomEditorDialog(null) { addingRoom = false }
    editingRoom?.let { r -> RoomEditorDialog(r) { editingRoom = null } }
    deletingRoom?.let { r -> DeleteRoomDialog(r, onDone = { selection = null }) { deletingRoom = null } }
}

@Composable
fun Hint(text: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f)) {
        Text(text, Modifier.padding(horizontal = 14.dp, vertical = 9.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SummaryBar() {
    val store = LocalStore.current
    val router = LocalRouter.current
    val unsorted = store.unsortedItems.size
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("${store.rooms.size} 个房间", style = MaterialTheme.typography.bodySmall)
            Text("${store.items.size} 件物品", style = MaterialTheme.typography.bodySmall)
            if (unsorted > 0) Text("$unsorted 件未归位", Modifier.clickable { router.open(Route.Unsorted) },
                style = MaterialTheme.typography.bodySmall, color = Color(0xFFF57C00))
        }
    }
}

@Composable
private fun RoomInspector(room: Room, onOpen: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.shadow(8.dp, RoundedCornerShape(16.dp))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(SymbolIcons.vector(room.roomType.info.symbol), null)
            Spacer(Modifier.width(8.dp))
            Text(room.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text("${room.width.metres()} × ${room.depth.metres()} m", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onOpen, Modifier.weight(1f)) { Text("布置家具") }
            FilledTonalIconButton(onEdit) { Icon(Icons.Outlined.Edit, "编辑房间") }
            FilledTonalIconButton(onDelete) { Icon(Icons.Outlined.Delete, "删除房间", tint = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun RoomList(onEdit: (Room) -> Unit, onDelete: (Room) -> Unit) {
    val store = LocalStore.current
    val router = LocalRouter.current
    val counts = store.itemCountsByRoom()
    val colors = LocalNookColors.current
    val rooms = store.sortedRooms
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(rooms, key = { it.id }) { room ->
            Card(padding = 10.dp) {
                Row(Modifier.clickable { router.open(Route.Room(room.id)) }, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)).background(colors.floor(room.roomType)), contentAlignment = Alignment.Center) {
                        Icon(SymbolIcons.vector(room.roomType.info.symbol), null, Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(room.name)
                        Text("${store.furnitureIn(room.id).size} 件家具 · ${counts[room.id] ?: 0} 件物品",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val idx = rooms.indexOf(room)
                    IconButton({ swap(store, rooms, idx, idx - 1) }, enabled = idx > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "上移") }
                    IconButton({ swap(store, rooms, idx, idx + 1) }, enabled = idx < rooms.lastIndex) { Icon(Icons.Outlined.KeyboardArrowDown, "下移") }
                    IconButton({ onEdit(room) }) { Icon(Icons.Outlined.Edit, "编辑") }
                    IconButton({ onDelete(room) }) { Icon(Icons.Outlined.Delete, "删除", tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
        if (store.unsortedItems.isNotEmpty()) item {
            Card(padding = 12.dp) {
                Row(Modifier.clickable { router.open(Route.Unsorted) }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Inbox, null)
                    Spacer(Modifier.width(10.dp))
                    Text("未归位物品（${store.unsortedItems.size}）", Modifier.weight(1f))
                    Icon(Icons.Outlined.ChevronRight, null)
                }
            }
        }
    }
}

fun swap(store: com.wanwu.nook.data.HomeStore, rooms: List<Room>, a: Int, b: Int) {
    if (b !in rooms.indices) return
    val list = rooms.toMutableList()
    java.util.Collections.swap(list, a, b)
    list.forEachIndexed { i, r -> if (r.sortOrder != i) store.save(r.copy(sortOrder = i)) }
}

// region Floor plan

private fun worldOf(rooms: List<Room>): Rect {
    val u = rooms.map { it.rect }.reduceOrNull { a, b -> a.union(b) } ?: return Rect(0.0, 0.0, 600.0, 600.0)
    return Rect(u.x - 60, u.y - 60, u.w + 120, u.h + 120)
}

@Composable
fun FloorPlanCanvas(editing: Boolean, selection: String?, onSelect: (String?) -> Unit, onOpen: (Room) -> Unit) {
    val store = LocalStore.current
    val density = LocalDensity.current
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val rooms = store.sortedRooms
    var frozen by remember { mutableStateOf<Rect?>(null) }
    LaunchedEffect(editing, rooms.size) { frozen = if (editing) worldOf(store.sortedRooms) else null }
    val world = frozen ?: worldOf(rooms)
    val counts = store.itemCountsByRoom()
    val select by rememberUpdatedState(onSelect)

    BoxWithConstraints(
        Modifier.fillMaxSize().clipToBounds()
            .pointerInput(Unit) { detectTransformGestures { _, p, z, _ -> zoom = (zoom * z).coerceIn(0.4f, 8f); pan += p } }
            .pointerInput(Unit) { detectTapGestures { select(null) } }
    ) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        val pad = with(density) { 20.dp.toPx() }; val reserve = with(density) { 60.dp.toPx() }
        val fit = minOf((w - pad * 2) / world.w.toFloat(), (h - pad * 2 - reserve) / world.h.toFloat())
        val s = maxOf(0.02f, fit) * zoom
        val ox = w / 2 + pan.x - world.midX.toFloat() * s
        val oy = (h - reserve) / 2 + pan.y - world.midY.toFloat() * s
        rooms.forEach { room ->
            androidx.compose.runtime.key(room.id) {
                PlanRoom(room, s, ox, oy, counts[room.id] ?: 0, editing, editing && selection == room.id,
                    onTap = { if (editing) onSelect(room.id) else onOpen(room) },
                    onSelect = { onSelect(room.id) })
            }
        }
        if (zoom != 1f || pan != Offset.Zero) {
            FilledTonalIconButton({ zoom = 1f; pan = Offset.Zero }, Modifier.align(Alignment.TopEnd).padding(10.dp)) {
                Icon(Icons.Outlined.ZoomOutMap, "缩放到全屋")
            }
        }
    }
}

@Composable
private fun PlanRoom(room: Room, scale: Float, ox: Float, oy: Float, count: Int, editing: Boolean, selected: Boolean,
                     onTap: () -> Unit, onSelect: () -> Unit) {
    val store = LocalStore.current
    val colors = LocalNookColors.current
    val density = LocalDensity.current
    var drag by remember { mutableStateOf(Offset.Zero) }
    var resize by remember { mutableStateOf(Offset.Zero) }
    val s by rememberUpdatedState(scale)
    val r by rememberUpdatedState(room)
    val tap by rememberUpdatedState(onTap)
    val sel by rememberUpdatedState(onSelect)
    val wPx = (maxOf(50.0, room.width + resize.x / scale) * scale).toFloat()
    val hPx = (maxOf(50.0, room.depth + resize.y / scale) * scale).toFloat()
    val pieces = store.furnitureIn(room.id)
    Box(
        Modifier
            .offset { IntOffset((ox + room.x.toFloat() * scale + drag.x).roundToInt(), (oy + room.y.toFloat() * scale + drag.y).roundToInt()) }
            .size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
            .testTag("room.${room.name}")
            .semantics { contentDescription = "${room.name}，$count 件物品" }
            .pointerInput(room.id) { detectTapGestures { tap() } }
            .then(if (editing) Modifier.pointerInput(room.id) {
                detectDragGestures(
                    onDragStart = { sel() },
                    onDrag = { c, d -> c.consume(); drag += d },
                    onDragEnd = {
                        val target = snapRoom(store.rooms.values.toList(), r, r.x + drag.x / s, r.y + drag.y / s)
                        store.moveRoom(r.id, target.first, target.second)
                        drag = Offset.Zero
                    },
                    onDragCancel = { drag = Offset.Zero },
                )
            } else Modifier)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(colors.floor(room.roomType))
            drawRoomFurniture(pieces, scale, colors.dark)
            val wall = (scale * 8).coerceIn(2f, 5f) * density.density
            drawRect(colors.wall, topLeft = Offset(wall / 2, wall / 2),
                size = androidx.compose.ui.geometry.Size(size.width - wall, size.height - wall), style = Stroke(wall))
            if (selected) drawRect(Color(0xFF2A7F76), style = Stroke(3 * density.density))
        }
        Column(Modifier.padding(6.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
            .padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text(room.name, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            if (count > 0) Text("$count 件", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) {
            Box(Modifier.align(Alignment.BottomEnd).offset(12.dp, 12.dp).size(30.dp).shadow(3.dp, CircleShape).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .pointerInput(room.id) {
                    detectDragGestures(
                        onDrag = { c, d -> c.consume(); resize += d },
                        onDragEnd = {
                            store.resizeRoom(r.id, snap(r.width + resize.x / s, 10.0), snap(r.depth + resize.y / s, 10.0))
                            resize = Offset.Zero
                        },
                        onDragCancel = { resize = Offset.Zero },
                    )
                }.testTag("room.resize"), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.OpenInFull, "调整房间大小", tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** Aligns with neighbouring rooms' walls when close, otherwise a 10 cm grid. */
private fun snapRoom(all: List<Room>, room: Room, x: Double, y: Double): Pair<Double, Double> {
    val others = all.filter { it.id != room.id }
    fun best(v: Double, cands: List<Double>) = cands.map { it to abs(it - v) }.filter { it.second < 30 }.minByOrNull { it.second }?.first ?: snap(v, 10.0)
    val xs = others.flatMap { listOf(it.x, it.x + it.width, it.x - room.width, it.x + it.width - room.width) }
    val ys = others.flatMap { listOf(it.y, it.y + it.depth, it.y - room.depth, it.y + it.depth - room.depth) }
    return best(x, xs) to best(y, ys)
}

// endregion
// region Room editor

@Composable
fun RoomEditorDialog(room: Room?, onDismiss: () -> Unit) {
    val store = LocalStore.current
    var type by remember { mutableStateOf(room?.roomType ?: RoomType.living) }
    var name by remember { mutableStateOf(room?.name ?: RoomType.living.info.title) }
    var nameEdited by remember { mutableStateOf(room != null) }
    var width by remember { mutableStateOf(room?.width ?: RoomType.living.info.width) }
    var depth by remember { mutableStateOf(room?.depth ?: RoomType.living.info.depth) }
    var withPreset by remember { mutableStateOf(true) }
    EditorDialog(if (room == null) "添加房间" else "编辑房间", onDismiss, onSave = {
        if (room != null) {
            store.save(room.copy(name = name.trim(), type = type.name))
            if (room.width != width || room.depth != depth) store.resizeRoom(room.id, width, depth)
        } else {
            store.addRoom(type, name.trim(), width, depth, withPreset)
        }
        onDismiss()
    }, saveEnabled = name.isNotBlank()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("房间类型", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RoomType.entries.forEach { t ->
                    val on = t == type
                    Column(Modifier.width(76.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Gray.copy(alpha = 0.08f))
                        .border(1.5.dp, if (on) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable {
                            type = t
                            if (room == null) {
                                width = t.info.width; depth = t.info.depth
                                if (!nameEdited) name = t.info.title
                            }
                        }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(SymbolIcons.vector(t.info.symbol), null)
                        Text(t.info.title, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            OutlinedTextField(name, { name = it; nameEdited = true }, Modifier.fillMaxWidth().testTag("room.name"), label = { Text("名称") }, singleLine = true)
            CmField("宽", width, 50.0..3000.0) { width = it }
            CmField("深", depth, 50.0..3000.0) { depth = it }
            Text("约 ${(width * depth / 10000).clean()} ㎡。之后也可以在户型图里拖动调整。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (room == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("放入推荐家具")
                        Text("按房间类型摆好常见家具，之后可以随意修改或删除。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(withPreset, { withPreset = it })
                }
            }
        }
    }
}

@Composable
fun DeleteRoomDialog(room: Room, onDone: () -> Unit = {}, onDismiss: () -> Unit) {
    val store = LocalStore.current
    val count = store.itemCountsByRoom()[room.id] ?: 0
    ChoiceDialog("删除「${room.name}」？", if (count > 0) "房间里的家具会一起删除，里面有 $count 件物品。" else "房间里的家具会一起删除。",
        if (count > 0) listOf(
            Choice("删除房间，物品移到未归位") { store.deleteRoom(room.id, DeletePolicy.KeepItems); onDone() },
            Choice("连同 $count 件物品一起删除", true) { store.deleteRoom(room.id, DeletePolicy.DeleteItems); onDone() },
        ) else listOf(Choice("删除房间", true) { store.deleteRoom(room.id, DeletePolicy.KeepItems); onDone() }),
        onDismiss)
}

// endregion
