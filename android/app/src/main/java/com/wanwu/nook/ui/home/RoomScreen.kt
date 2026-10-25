package com.wanwu.nook.ui.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoorFront
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.SubdirectoryArrowRight
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wanwu.nook.LocalHA
import com.wanwu.nook.LocalRouter
import com.wanwu.nook.LocalStore
import com.wanwu.nook.Route
import com.wanwu.nook.data.Catalog
import com.wanwu.nook.data.DeletePolicy
import com.wanwu.nook.data.Furniture
import com.wanwu.nook.data.FurnitureGroup
import com.wanwu.nook.data.FurnitureKind
import com.wanwu.nook.data.FurnitureLayer
import com.wanwu.nook.data.FurnitureStyle
import com.wanwu.nook.data.Room
import com.wanwu.nook.data.info
import com.wanwu.nook.data.metres
import com.wanwu.nook.data.snap
import com.wanwu.nook.ui.art.FurnitureArt.drawFurniture
import com.wanwu.nook.ui.components.Card
import com.wanwu.nook.ui.components.Choice
import com.wanwu.nook.ui.components.ChoiceDialog
import com.wanwu.nook.ui.components.CmField
import com.wanwu.nook.ui.components.EditorDialog
import com.wanwu.nook.ui.components.EmptyState
import com.wanwu.nook.ui.components.NookTopBar
import com.wanwu.nook.ui.components.Pill
import com.wanwu.nook.ui.components.SymbolIcons
import com.wanwu.nook.ui.theme.LocalNookColors
import com.wanwu.nook.ui.theme.hexColor
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun RoomScreen(roomId: String) {
    val store = LocalStore.current
    val router = LocalRouter.current
    val room = store.rooms[roomId]
    if (room == null) {
        Scaffold(topBar = { NookTopBar("房间", onBack = { router.back() }) }) { p ->
            Box(Modifier.padding(p)) { EmptyState("房间已删除", "这个房间可能在另一台设备上被删除了。") }
        }
        return
    }
    var editing by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<String?>(null) }
    var showList by remember { mutableStateOf(false) }
    var showPalette by remember { mutableStateOf(false) }
    var editingFurniture by remember { mutableStateOf<Furniture?>(null) }
    var deletingFurniture by remember { mutableStateOf<Furniture?>(null) }
    var stackProposal by remember { mutableStateOf<Pair<Furniture, Furniture>?>(null) }
    var editingRoom by remember { mutableStateOf(false) }
    var deletingRoom by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var highlight by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(roomId) {
        val h = router.highlight
        if (h != null && store.furniture[h]?.roomId == roomId) {
            highlight = h
            router.highlight = null
            delay(3000)
            highlight = null
        }
    }

    Scaffold(
        topBar = {
            NookTopBar(room.name, onBack = { router.back() }, actions = {
                if (!showList) TextButton({ editing = !editing; selection = null }, Modifier.testTag("room.edit")) {
                    Text(if (editing) "完成" else "编辑", fontWeight = if (editing) FontWeight.SemiBold else FontWeight.Normal)
                }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Outlined.MoreVert, "更多") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("添加家具") }, { menu = false; showPalette = true })
                        DropdownMenuItem({ Text(if (showList) "平面图" else "家具列表") }, { menu = false; showList = !showList; editing = false; selection = null })
                        DropdownMenuItem({ Text("编辑房间") }, { menu = false; editingRoom = true })
                        DropdownMenuItem({ Text("删除房间", color = MaterialTheme.colorScheme.error) }, { menu = false; deletingRoom = true })
                    }
                }
            })
        },
        containerColor = LocalNookColors.current.canvas,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (showList) {
                FurnitureList(room, onEdit = { editingFurniture = it }, onDelete = { deletingFurniture = it })
            } else {
                RoomCanvas(room, editing, selection, { selection = it }, highlight,
                    onOpen = { router.open(Route.Furniture(it.id)) },
                    onLongPress = { editing = true; selection = it.id },
                    onDropOnto = { a, b -> stackProposal = a to b })
                Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 10.dp)) {
                    val sel = selection?.let { store.furniture[it] }
                    when {
                        editing && sel != null -> FurnitureInspector(sel, onOpen = { router.open(Route.Furniture(sel.id)) },
                            onEdit = { editingFurniture = sel }, onDelete = { deletingFurniture = sel })
                        editing -> Button({ showPalette = true }, Modifier.fillMaxWidth().testTag("room.addFurniture")) { Text("添加家具") }
                        store.furnitureIn(room.id).isEmpty() -> Card {
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("房间是空的", style = MaterialTheme.typography.titleSmall)
                                Text("添加家具后，点开家具就能收纳物品", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                Button({ showPalette = true }) { Text("添加家具") }
                            }
                        }
                        else -> Hint("点家具打开收纳 · 长按进入编辑")
                    }
                }
            }
        }
    }
    if (showPalette) FurniturePaletteSheet(room, onDismiss = { showPalette = false }) { added -> editing = true; selection = added.id }
    editingFurniture?.let { f -> FurnitureEditorDialog(f, null, onDismiss = { editingFurniture = null }) }
    deletingFurniture?.let { f -> DeleteFurnitureDialog(f, onDone = { selection = null }) { deletingFurniture = null } }
    if (editingRoom) RoomEditorDialog(room) { editingRoom = false }
    if (deletingRoom) DeleteRoomDialog(room, onDone = { router.back() }) { deletingRoom = false }
    stackProposal?.let { (moved, target) ->
        ChoiceDialog("叠放", "「${moved.name}」拖到了「${target.name}」上面。叠放后它会跟着下面的家具一起移动。",
            listOf(Choice("把「${moved.name}」放在「${target.name}」上") { store.setStack(moved.id, target.id) }),
            onDismiss = { stackProposal = null })
    }
}

@Composable
fun RoomCanvas(room: Room, editing: Boolean, selection: String?, onSelect: (String?) -> Unit, highlight: String?,
               onOpen: (Furniture) -> Unit, onLongPress: (Furniture) -> Unit, onDropOnto: (Furniture, Furniture) -> Unit) {
    val store = LocalStore.current
    val ha = LocalHA.current
    val colors = LocalNookColors.current
    val density = LocalDensity.current
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var movingId by remember { mutableStateOf<String?>(null) }
    var moveDelta by remember { mutableStateOf(Offset.Zero) }
    var resizeDelta by remember { mutableStateOf(Offset.Zero) }
    val select by rememberUpdatedState(onSelect)
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0.2f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "a")

    BoxWithConstraints(
        Modifier.fillMaxSize().clipToBounds()
            .pointerInput(Unit) { detectTransformGestures { _, p, z, _ -> zoom = (zoom * z).coerceIn(0.5f, 8f); pan += p } }
            .pointerInput(Unit) { detectTapGestures { select(null) } }
    ) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        val pad = with(density) { 28.dp.toPx() }
        val usableH = h - with(density) { 110.dp.toPx() }
        val fit = minOf((w - pad * 2) / room.width.toFloat(), (usableH - pad * 2) / room.depth.toFloat())
        val s = maxOf(0.05f, fit) * zoom
        val ox = w / 2 + pan.x - room.width.toFloat() / 2 * s
        val oy = usableH / 2 + pan.y - room.depth.toFloat() / 2 * s
        val pieces = store.furnitureIn(room.id)
        val counts = store.itemCountsByFurniture()
        val bases = pieces.mapNotNull { it.parentId }.toSet()
        val movingGroup = movingId?.let { id -> setOf(id) + store.descendantsOf(id).map { it.id } } ?: emptySet()

        // Floor, 50 cm grid, walls, dimensions.
        Canvas(Modifier.fillMaxSize()) {
            val rw = room.width.toFloat() * s; val rh = room.depth.toFloat() * s
            drawRect(colors.floor(room.roomType), Offset(ox, oy), Size(rw, rh))
            var gx = 50.0
            while (gx < room.width) { drawLine(Color.Gray.copy(alpha = 0.12f), Offset(ox + gx.toFloat() * s, oy), Offset(ox + gx.toFloat() * s, oy + rh), 1f); gx += 50 }
            var gy = 50.0
            while (gy < room.depth) { drawLine(Color.Gray.copy(alpha = 0.12f), Offset(ox, oy + gy.toFloat() * s), Offset(ox + rw, oy + gy.toFloat() * s), 1f); gy += 50 }
            val wall = (s * 12).coerceIn(4f, 9f) * density.density / 2
            drawRect(colors.wall, Offset(ox - wall / 2, oy - wall / 2), Size(rw + wall, rh + wall), style = Stroke(wall))
        }
        Text("${room.width.metres()} m", Modifier.offset { IntOffset((ox + room.width.toFloat() * s / 2 - 20).roundToInt(), (oy + room.depth.toFloat() * s + 14).roundToInt()) },
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${room.depth.metres()} m", Modifier.offset { IntOffset((ox - 64).roundToInt().coerceAtLeast(0), (oy + room.depth.toFloat() * s / 2).roundToInt()) },
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        pieces.forEach { f ->
            androidx.compose.runtime.key(f.id) {
                val moving = f.id in movingGroup
                val resizing = editing && selection == f.id
                val fw = (maxOf(5.0, f.footprintWidth + if (resizing) resizeDelta.x / s else 0f) * s).toFloat()
                val fh = (maxOf(5.0, f.footprintDepth + if (resizing) resizeDelta.y / s else 0f) * s).toFloat()
                val px = ox + f.x.toFloat() * s + if (moving) moveDelta.x else 0f
                val py = oy + f.y.toFloat() * s + if (moving) moveDelta.y else 0f
                val fState by rememberUpdatedState(f)
                val sState by rememberUpdatedState(s)
                val editingState by rememberUpdatedState(editing)
                Box(
                    Modifier.offset { IntOffset(px.roundToInt(), py.roundToInt()) }
                        .size(with(density) { fw.toDp() }, with(density) { fh.toDp() })
                        .testTag("furniture.${f.name}")
                        .semantics { contentDescription = f.name + (counts[f.id]?.let { "，$it 件物品" } ?: "") }
                        .pointerInput(f.id) {
                            detectTapGestures(
                                onTap = { if (editingState) select(fState.id) else onOpen(fState) },
                                onLongPress = { if (!editingState) onLongPress(fState) },
                            )
                        }
                        .then(if (editing) Modifier.pointerInput(f.id) {
                            detectDragGestures(
                                onDragStart = { movingId = fState.id; select(fState.id) },
                                onDrag = { c, d -> c.consume(); moveDelta += d },
                                onDragEnd = {
                                    val cur = fState; val sc = sState
                                    store.moveFurniture(cur.id, snap(cur.x + moveDelta.x / sc, 5.0), snap(cur.y + moveDelta.y / sc, 5.0))
                                    movingId = null; moveDelta = Offset.Zero
                                    val moved = store.furniture[cur.id] ?: return@detectDragGestures
                                    val excluded = setOf(moved.id) + store.descendantsOf(moved.id).map { it.id }
                                    val target = store.furnitureIn(room.id).reversed().firstOrNull { o ->
                                        o.id !in excluded && o.rect.contains(moved.centerX, moved.centerY) &&
                                            Catalog.kind(o.kind).layer != FurnitureLayer.floor && Catalog.kind(o.kind).style != FurnitureStyle.door
                                    }
                                    if (target != null && moved.parentId != target.id) onDropOnto(moved, target)
                                    else moved.parentId?.let { pid -> store.furniture[pid] }?.let { parent ->
                                        if (!parent.rect.contains(moved.centerX, moved.centerY)) store.setStack(moved.id, null)
                                    }
                                },
                                onDragCancel = { movingId = null; moveDelta = Offset.Zero },
                            )
                        } else Modifier)
                ) {
                    FurnitureNode(f, Size(fw, fh), counts[f.id] ?: 0, editing && selection == f.id, ha.isPowered(f),
                        f.parentId != null, f.id in bases, editing, if (highlight == f.id) pulse else null)
                }
            }
        }

        val sel = selection?.let { store.furniture[it] }
        if (editing && sel != null && sel.roomId == room.id) {
            val cx = ox + (sel.x + sel.footprintWidth).toFloat() * s + resizeDelta.x
            val cy = oy + (sel.y + sel.footprintDepth).toFloat() * s + resizeDelta.y
            val half = with(density) { 14.dp.toPx() }
            val selState by rememberUpdatedState(sel)
            val sState by rememberUpdatedState(s)
            Box(Modifier.offset { IntOffset((cx - half).roundToInt(), (cy - half).roundToInt()) }.size(28.dp).shadow(3.dp, CircleShape)
                .clip(CircleShape).background(MaterialTheme.colorScheme.primary).testTag("furniture.resize")
                .pointerInput(sel.id) {
                    detectDragGestures(
                        onDrag = { c, d -> c.consume(); resizeDelta += d },
                        onDragEnd = {
                            val f = selState
                            store.resizeFurniture(f.id, snap(f.footprintWidth + resizeDelta.x / sState, 5.0), snap(f.footprintDepth + resizeDelta.y / sState, 5.0))
                            resizeDelta = Offset.Zero
                        },
                        onDragCancel = { resizeDelta = Offset.Zero },
                    )
                }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.OpenInFull, "调整家具大小", tint = Color.White, modifier = Modifier.size(13.dp))
            }
        }
        if (zoom != 1f || pan != Offset.Zero) {
            FilledTonalIconButton({ zoom = 1f; pan = Offset.Zero }, Modifier.align(Alignment.TopEnd).padding(10.dp)) {
                Icon(Icons.Outlined.ZoomOutMap, "缩放到整个房间")
            }
        }
    }
}

@Composable
fun FurnitureNode(f: Furniture, size: Size, itemCount: Int, selected: Boolean, powered: Boolean, stacked: Boolean,
                  carries: Boolean, editing: Boolean, pulse: Float?) {
    val colors = LocalNookColors.current
    val primary = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val glow = if (powered) Modifier.shadow(10.dp, RoundedCornerShape(4.dp), ambientColor = Color.Yellow, spotColor = Color.Yellow)
    else if (stacked) Modifier.shadow(3.dp, RoundedCornerShape(3.dp)) else Modifier
    Box(Modifier.fillMaxSize().then(glow).drawWithContent {
        drawContent()
        if (selected) drawRect(primary, Offset(-3f, -3f), Size(this.size.width + 6, this.size.height + 6),
            style = Stroke(2 * density.density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))))
        if (pulse != null) drawRect(Color(0xFFFF9800).copy(alpha = pulse), Offset(-6f, -6f), Size(this.size.width + 12, this.size.height + 12),
            style = Stroke(3 * density.density))
    }) {
        Canvas(Modifier.fillMaxSize()) { drawFurniture(f, this.size, colors.dark) }
        val wDp = with(density) { size.width.toDp() }; val hDp = with(density) { size.height.toDp() }
        if (wDp > 34.dp && hDp > 16.dp) {
            Text(f.name, Modifier.align(if (carries) Alignment.BottomEnd else Alignment.Center).padding(2.dp)
                .clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)).padding(horizontal = 4.dp),
                fontSize = (minOf(wDp.value / 6, hDp.value / 2.6f)).coerceIn(8f, 12f).sp, maxLines = 1)
        }
        Row(Modifier.align(Alignment.TopEnd).offset(6.dp, (-6).dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (powered) Box(Modifier.size(14.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Bolt, null, tint = Color.Yellow, modifier = Modifier.size(10.dp))
            }
            if (itemCount > 0 && !editing) Text("$itemCount", Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 4.dp), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun FurnitureInspector(f: Furniture, onOpen: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val store = LocalStore.current
    var stackMenu by remember { mutableStateOf(false) }
    val candidates = store.furnitureIn(f.roomId).filter { it.id != f.id && !store.isDescendant(it.id, f.id) && Catalog.kind(it.kind).layer != FurnitureLayer.floor }
    Card(Modifier.shadow(8.dp, RoundedCornerShape(16.dp))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.name, style = MaterialTheme.typography.titleMedium)
            f.parentId?.let { store.furniture[it] }?.let { Spacer(Modifier.width(6.dp)); Pill("在${it.name}上", MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.weight(1f))
            Text("${f.footprintWidth.toInt()} × ${f.footprintDepth.toInt()} cm", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tool("旋转", Icons.AutoMirrored.Outlined.RotateRight, Modifier.weight(1f)) { store.rotateFurniture(f.id) }
            Tool("复制", Icons.Outlined.ContentCopy, Modifier.weight(1f)) { store.duplicateFurniture(f.id) }
            Box(Modifier.weight(1f)) {
                Tool("叠放", Icons.Outlined.Layers, Modifier.fillMaxWidth()) { stackMenu = true }
                DropdownMenu(stackMenu, { stackMenu = false }) {
                    if (f.stackedOnId != null) DropdownMenuItem({ Text("放回地面") }, { stackMenu = false; store.setStack(f.id, null) })
                    candidates.forEach { c -> DropdownMenuItem({ Text("放在「${c.name}」上") }, { stackMenu = false; store.setStack(f.id, c.id) }) }
                    if (candidates.isEmpty() && f.stackedOnId == null) DropdownMenuItem({ Text("房间里没有可以叠放的家具") }, { stackMenu = false }, enabled = false)
                }
            }
            Tool("属性", Icons.Outlined.Tune, Modifier.weight(1f), onClick = onEdit)
            Tool("打开", Icons.Outlined.DoorFront, Modifier.weight(1f), onClick = onOpen)
            Tool("删除", Icons.Outlined.Delete, Modifier.weight(1f), MaterialTheme.colorScheme.error, onDelete)
        }
    }
}

@Composable
private fun Tool(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier,
                 tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(Color.Gray.copy(alpha = 0.1f)).clickable(onClick = onClick)
        .padding(vertical = 8.dp).testTag("tool.$title"), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Text(title, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

@Composable
private fun FurnitureList(room: Room, onEdit: (Furniture) -> Unit, onDelete: (Furniture) -> Unit) {
    val store = LocalStore.current
    val router = LocalRouter.current
    val colors = LocalNookColors.current
    val pieces = store.furnitureIn(room.id)
    val counts = store.itemCountsByFurniture()
    val roots = pieces.filter { it.parentId == null || store.furniture[it.parentId!!] == null }
    val ordered = roots.flatMap { listOf(it) + store.descendantsOf(it.id) }
    if (pieces.isEmpty()) { EmptyState("还没有家具"); return }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(ordered, key = { it.id }) { f ->
            val depth = store.stackDepth(f.id)
            Card(padding = 10.dp) {
                Row(Modifier.clickable { router.open(Route.Furniture(f.id)) }, verticalAlignment = Alignment.CenterVertically) {
                    if (depth > 0) {
                        Spacer(Modifier.width(16.dp * (depth - 1)))
                        Icon(Icons.Outlined.SubdirectoryArrowRight, null, tint = MaterialTheme.colorScheme.outline)
                    }
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        val ratio = (f.footprintDepth / maxOf(f.footprintWidth, 1.0)).coerceIn(0.3, 2.0).toFloat()
                        Canvas(Modifier.size(if (ratio > 1) (40 / ratio).dp else 40.dp, if (ratio > 1) 40.dp else (40 * ratio).dp)) {
                            drawFurniture(f, this.size, colors.dark)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name)
                        Text("${store.compartmentsOf(f.id).size} 个收纳位 · ${counts[f.id] ?: 0} 件物品", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton({ onEdit(f) }) { Icon(Icons.Outlined.Edit, "编辑") }
                    IconButton({ onDelete(f) }) { Icon(Icons.Outlined.Delete, "删除", tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

// region Palette & editor

@Composable
fun FurniturePaletteSheet(room: Room, onDismiss: () -> Unit, onAdded: (Furniture) -> Unit) {
    val store = LocalStore.current
    var query by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf(false) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val groups: List<Pair<String, List<FurnitureKind>>> = if (query.isNotBlank()) {
        listOf("搜索结果" to Catalog.kinds.filter { it.name.contains(query.trim()) || it.group.title.contains(query.trim()) })
    } else {
        listOf("适合${room.roomType.info.title}" to room.roomType.info.suggested.map { Catalog.kind(it) }.filter { it.id != Catalog.CUSTOM }) +
            FurnitureGroup.entries.map { it.title to Catalog.kinds(it) }
    }
    ModalBottomSheet(onDismiss, sheetState = state) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("添加家具", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("搜索家具") }, singleLine = true)
            Spacer(Modifier.height(8.dp))
            TextButton({ custom = true }, Modifier.fillMaxWidth()) { Text("＋ 自定义家具（任意名称、尺寸、颜色）") }
        }
        LazyVerticalGrid(GridCells.Adaptive(96.dp), Modifier.fillMaxWidth().height(520.dp), contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            groups.filter { it.second.isNotEmpty() }.forEach { (title, kinds) ->
                item(span = { GridItemSpan(maxLineSpan) }) { Text(title, style = MaterialTheme.typography.titleSmall) }
                items(kinds, key = { "$title-${it.id}" }) { kind ->
                    KindTile(kind) {
                        store.addFurniture(kind, room.id)?.let(onAdded)
                        onDismiss()
                    }
                }
            }
        }
    }
    if (custom) FurnitureEditorDialog(null, room.id, onDismiss = { custom = false }) { onAdded(it); onDismiss() }
}

@Composable
private fun KindTile(kind: FurnitureKind, onClick: () -> Unit) {
    val colors = LocalNookColors.current
    val sample = remember(kind.id) { Furniture(roomId = "X", kind = kind.id, name = kind.name, width = kind.width, depth = kind.depth) }
    val ratio = (kind.depth / kind.width).toFloat()
    Column(Modifier.clip(RoundedCornerShape(12.dp)).background(colors.card).clickable(onClick = onClick).padding(vertical = 10.dp)
        .testTag("kind.${kind.name}"), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(60.dp, 56.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(if (ratio > 1) (52 / ratio).dp else 52.dp, if (ratio > 1) 52.dp else maxOf(8f, 52 * ratio).dp)) {
                drawFurniture(sample, this.size, colors.dark)
            }
        }
        Text(kind.name, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        Text("${kind.width.toInt()}×${kind.depth.toInt()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Create a custom piece (roomId set) or edit any piece's properties (existing set). */
@Composable
fun FurnitureEditorDialog(existing: Furniture?, roomId: String?, onDismiss: () -> Unit, onCreated: (Furniture) -> Unit = {}) {
    val store = LocalStore.current
    val colors = LocalNookColors.current
    val current = existing?.let { store.furniture[it.id] ?: it }
    var name by remember { mutableStateOf(current?.name ?: "") }
    var kindId by remember { mutableStateOf(current?.kind ?: Catalog.CUSTOM) }
    var width by remember { mutableStateOf(current?.width ?: Catalog.custom.width) }
    var depth by remember { mutableStateOf(current?.depth ?: Catalog.custom.depth) }
    var colorHex by remember { mutableStateOf(current?.colorHex) }
    var notes by remember { mutableStateOf(current?.notes ?: "") }
    var targetRoom by remember { mutableStateOf(current?.roomId) }
    var compartments by remember { mutableStateOf(Catalog.custom.compartments.joinToString("、")) }
    var kindMenu by remember { mutableStateOf(false) }
    var roomMenu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    EditorDialog(if (current == null) "自定义家具" else "家具属性", onDismiss, onSave = {
        if (current != null) {
            var f = current.copy(name = name.trim(), kind = kindId, notes = notes, colorHex = colorHex, width = width, depth = depth)
            store.rooms[f.roomId]?.let { f = store.clampInside(f, it) }
            store.save(f)
            targetRoom?.let { if (it != f.roomId) store.moveFurnitureToRoom(f.id, it) }
        } else if (roomId != null) {
            val names = compartments.split('、', ',', '，', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            store.addFurniture(Catalog.kind(kindId), roomId, name.trim(), width, depth, colorHex, names)?.let { f ->
                if (notes.isNotEmpty()) store.save(f.copy(notes = notes))
                onCreated(f)
            }
        }
        onDismiss()
    }, saveEnabled = name.isNotBlank()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().testTag("furniture.name"), label = { Text("名称") }, singleLine = true)
            Box {
                OutlinedTextField(if (kindId == Catalog.CUSTOM) "自定义" else Catalog.kind(kindId).name, {}, Modifier.fillMaxWidth(),
                    label = { Text("外观") }, readOnly = true, enabled = false)
                Box(Modifier.matchParentSize().clickable { kindMenu = true })
                DropdownMenu(kindMenu, { kindMenu = false }, Modifier.height(400.dp)) {
                    DropdownMenuItem({ Text("自定义") }, { kindMenu = false; kindId = Catalog.CUSTOM })
                    FurnitureGroup.entries.forEach { g ->
                        DropdownMenuItem({ Text(g.title, color = MaterialTheme.colorScheme.primary) }, {}, enabled = false)
                        Catalog.kinds(g).forEach { k ->
                            DropdownMenuItem({ Text("  " + k.name) }, {
                                kindMenu = false
                                if (current == null) {
                                    width = k.width; depth = k.depth
                                    if (name.isBlank() || Catalog.kinds.any { it.name == name }) name = k.name
                                    compartments = k.compartments.joinToString("、")
                                }
                                kindId = k.id
                            })
                        }
                    }
                }
            }
            val sample = Furniture(roomId = "X", kind = kindId, name = name, width = width, depth = depth, colorHex = colorHex)
            val ratio = (depth / maxOf(width, 1.0)).toFloat()
            Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(if (ratio > 1) (90 / ratio).dp else 90.dp, if (ratio > 1) 90.dp else maxOf(8f, 90 * ratio).dp)) {
                    drawFurniture(sample, this.size, colors.dark)
                }
            }
            Text("尺寸（未旋转时）", style = MaterialTheme.typography.labelLarge)
            CmField("宽", width, 5.0..2000.0) { width = it }
            CmField("深", depth, 5.0..2000.0) { depth = it }
            Text("颜色", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(32.dp).clip(CircleShape).border(2.dp, if (colorHex == null) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f), CircleShape)
                    .clickable { colorHex = null }, contentAlignment = Alignment.Center) { Text("默", fontSize = 11.sp) }
                Catalog.customColors.forEach { hex ->
                    Box(Modifier.size(32.dp).clip(CircleShape).background(hexColor(hex))
                        .border(2.dp, if (colorHex == hex) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.1f), CircleShape)
                        .clickable { colorHex = hex })
                }
            }
            if (current == null) {
                OutlinedTextField(compartments, { compartments = it }, Modifier.fillMaxWidth(), label = { Text("收纳位置（用顿号分隔）") },
                    supportingText = { Text("家具里能放东西的地方。创建后可以随时增删、改名。") })
            } else {
                Box {
                    OutlinedTextField(store.rooms[targetRoom]?.name ?: "", {}, Modifier.fillMaxWidth(), label = { Text("所在房间") }, readOnly = true, enabled = false)
                    Box(Modifier.matchParentSize().clickable { roomMenu = true })
                    DropdownMenu(roomMenu, { roomMenu = false }) {
                        store.sortedRooms.forEach { r -> DropdownMenuItem({ Text(r.name) }, { roomMenu = false; targetRoom = r.id }) }
                    }
                }
            }
            OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("备注") })
            if (current != null) TextButton({ deleting = true }) { Text("删除家具", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (deleting && current != null) DeleteFurnitureDialog(current, onDone = onDismiss) { deleting = false }
}

@Composable
fun DeleteFurnitureDialog(f: Furniture, onDone: () -> Unit = {}, onDismiss: () -> Unit) {
    val store = LocalStore.current
    val count = store.itemCount(f.id)
    val onTop = store.childrenOf(f.id).size
    val msg = listOfNotNull(if (count > 0) "里面有 $count 件物品" else null, if (onTop > 0) "上面叠放的 $onTop 件家具会保留" else null)
        .joinToString("，").ifEmpty { "此操作无法撤销" } + "。"
    ChoiceDialog("删除「${f.name}」？", msg,
        if (count > 0) listOf(
            Choice("删除家具，物品移到未归位") { store.deleteFurniture(f.id, DeletePolicy.KeepItems); onDone() },
            Choice("连同 $count 件物品一起删除", true) { store.deleteFurniture(f.id, DeletePolicy.DeleteItems); onDone() },
        ) else listOf(Choice("删除", true) { store.deleteFurniture(f.id, DeletePolicy.KeepItems); onDone() }),
        onDismiss)
}

// endregion
