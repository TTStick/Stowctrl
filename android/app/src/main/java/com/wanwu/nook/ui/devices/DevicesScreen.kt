package com.wanwu.nook.ui.devices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Blinds
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wanwu.nook.LocalHA
import com.wanwu.nook.LocalRouter
import com.wanwu.nook.LocalStore
import com.wanwu.nook.Route
import com.wanwu.nook.data.Catalog
import com.wanwu.nook.data.Furniture
import com.wanwu.nook.net.HAControl
import com.wanwu.nook.net.HADomain
import com.wanwu.nook.net.HAState
import com.wanwu.nook.ui.components.Card
import com.wanwu.nook.ui.components.Divider
import com.wanwu.nook.ui.components.EditorDialog
import com.wanwu.nook.ui.components.EmptyState
import com.wanwu.nook.ui.components.NookTopBar
import com.wanwu.nook.ui.components.SymbolIcons
import com.wanwu.nook.ui.theme.LocalNookColors
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

fun haIcon(domain: String, on: Boolean): ImageVector = when (domain) {
    "light" -> Icons.Outlined.Lightbulb
    "switch", "input_boolean" -> Icons.Outlined.PowerSettingsNew
    "fan" -> Icons.Outlined.AcUnit
    "cover" -> Icons.Outlined.Blinds
    "lock" -> if (on) Icons.Outlined.LockOpen else Icons.Outlined.Lock
    "climate" -> Icons.Outlined.Thermostat
    "media_player" -> Icons.Outlined.Tv
    "sensor" -> Icons.Outlined.Speed
    "binary_sensor" -> Icons.Outlined.Sensors
    "camera" -> Icons.Outlined.Videocam
    "vacuum" -> Icons.Outlined.CleaningServices
    "scene", "script", "automation" -> Icons.Outlined.AutoAwesome
    "humidifier", "water_heater" -> Icons.Outlined.WaterDrop
    else -> Icons.Outlined.HelpOutline
}

/** Home Assistant devices, grouped by the furniture they're mapped onto. */
@Composable
fun DevicesScreen() {
    val store = LocalStore.current
    val ha = LocalHA.current
    val router = LocalRouter.current
    var mapping by remember { mutableStateOf<HAState?>(null) }
    var showUnmapped by remember { mutableStateOf(true) }
    DisposableEffect(ha.isConfigured) {
        if (ha.isConfigured) ha.startPolling()
        onDispose { ha.stopPolling() }
    }
    Scaffold(
        topBar = {
            NookTopBar("设备", actions = {
                if (ha.isConfigured) IconButton({ router.open(Route.HASettings) }) { Icon(Icons.Outlined.Settings, "HA 设置") }
            })
        },
        containerColor = LocalNookColors.current.canvas,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (!ha.isConfigured) {
                EmptyState("连接 Home Assistant", if (store.settings.haURL.isEmpty())
                        "填写内网 HA 地址和长期访问令牌后，可以把灯、空调、洗衣机等设备映射到家具上，直接在这里控制。"
                    else "地址已从你的其他设备同步过来。令牌只保存在各自的设备上，在这台设备上填写一次即可。",
                    Icons.Outlined.Home) { Button({ router.open(Route.HASettings) }, Modifier.testTag("ha.setup")) { Text("开始设置") } }
            } else {
                val mapped = store.furniture.values.filter { it.haEntities.isNotEmpty() }
                val mappedIds = mapped.flatMap { it.haEntities }.toSet()
                val unmapped = ha.sortedStates.filter { it.domain in HADomain.mappable && it.entity_id !in mappedIds }
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ha.error?.let { e -> item {
                        Card {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.WifiOff, null, tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(8.dp))
                                Text(e, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    } }
                    if (mapped.isEmpty()) item {
                        Text("还没有设备映射到家具。在下面的列表里点“放到家具上”，或在家具详情里绑定设备。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    store.sortedRooms.forEach { room ->
                        val pieces = mapped.filter { it.roomId == room.id }.sortedBy { it.name }
                        if (pieces.isNotEmpty()) item(key = room.id) {
                            Text(room.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.size(6.dp))
                            Card {
                                pieces.forEachIndexed { i, f ->
                                    Row(Modifier.fillMaxWidth().clickable { router.open(Route.Furniture(f.id)) }.padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Icon(SymbolIcons.vector(f.symbol ?: Catalog.kind(f.kind).symbol), null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(8.dp))
                                        Text(f.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
                                    }
                                    f.haEntities.forEach { id ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(Modifier.weight(1f)) { EntityRow(id) }
                                            IconButton({ store.save(f.copy(haEntities = f.haEntities - id)) }) { Icon(Icons.Outlined.Remove, "解除映射") }
                                        }
                                    }
                                    if (i < pieces.lastIndex) Divider()
                                }
                            }
                        }
                    }
                    item {
                        Card {
                            Row(Modifier.fillMaxWidth().clickable { showUnmapped = !showUnmapped }, verticalAlignment = Alignment.CenterVertically) {
                                Text("未映射的设备（${unmapped.size}）", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                Icon(if (showUnmapped) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                            }
                            if (showUnmapped) {
                                if (ha.states.isEmpty() && ha.isLoading) Text("正在连接…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                unmapped.forEach { s ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.weight(1f)) { EntityRow(s.entity_id, showControl = false) }
                                        OutlinedButton({ mapping = s }, Modifier.testTag("map.${s.name}")) { Text("放到家具上") }
                                    }
                                }
                                if (unmapped.isEmpty() && ha.states.isNotEmpty()) Text("所有设备都已映射", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
    mapping?.let { s ->
        FurniturePickerDialog("把「${s.name}」放到…", onDismiss = { mapping = null }) { f ->
            if (s.entity_id !in f.haEntities) store.save(f.copy(haEntities = f.haEntities + s.entity_id))
        }
    }
}

/** One entity with its live state and the right control for its domain. */
@Composable
fun EntityRow(entityId: String, showControl: Boolean = true) {
    val ha = LocalHA.current
    val scope = rememberCoroutineScope()
    val s = ha.states[entityId]
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("entity.$entityId"), verticalAlignment = Alignment.CenterVertically) {
        if (s == null) {
            Icon(Icons.Outlined.HelpOutline, null, tint = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(entityId, maxLines = 1)
                Text(if (ha.states.isEmpty()) "正在连接…" else "HA 里找不到这个设备", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Row
        }
        Icon(haIcon(s.domain, s.isOn), null, tint = if (s.isOn) Color(0xFFF9A825) else MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name, maxLines = 1)
            val text = HADomain.stateText(s)
            if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (!showControl || s.isUnavailable) return@Row
        fun act(domain: String, service: String, extra: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()) =
            scope.launch { ha.perform(domain, service, s.entity_id, extra) }
        when (HAControl.of(s.domain)) {
            HAControl.Toggle -> Switch(s.isOn, { scope.launch { ha.toggle(s) } }, Modifier.testTag("toggle.$entityId"))
            HAControl.Cover -> Row {
                val v = s.domain == "valve"
                SmallIcon(Icons.Outlined.KeyboardArrowUp) { act(s.domain, if (v) "open_valve" else "open_cover") }
                SmallIcon(Icons.Outlined.Stop) { act(s.domain, if (v) "stop_valve" else "stop_cover") }
                SmallIcon(Icons.Outlined.KeyboardArrowDown) { act(s.domain, if (v) "close_valve" else "close_cover") }
            }
            HAControl.Lock -> TextButton({ act("lock", if (s.state == "locked") "unlock" else "lock") }) { Text(if (s.state == "locked") "开锁" else "上锁") }
            HAControl.Climate -> Row(verticalAlignment = Alignment.CenterVertically) {
                val t = s.number("temperature")
                if (t != null) {
                    SmallIcon(Icons.Outlined.Remove) { act("climate", "set_temperature", mapOf("temperature" to JsonPrimitive(t - 0.5))) }
                    SmallIcon(Icons.Outlined.Add) { act("climate", "set_temperature", mapOf("temperature" to JsonPrimitive(t + 0.5))) }
                }
                Switch(s.state != "off", { on -> act("climate", if (on) "turn_on" else "turn_off") })
            }
            HAControl.Media -> Row {
                SmallIcon(if (s.state == "playing") Icons.Outlined.Pause else Icons.Outlined.PlayArrow) { act("media_player", "media_play_pause") }
                SmallIcon(Icons.Outlined.PowerSettingsNew) { act("media_player", if (s.state == "off") "turn_on" else "turn_off") }
            }
            HAControl.Activate -> TextButton({ act(s.domain, "turn_on") }) { Text("执行") }
            HAControl.Press -> TextButton({ act(s.domain, "press") }) { Text("按下") }
            HAControl.Vacuum -> Row {
                SmallIcon(Icons.Outlined.PlayArrow) { act(s.domain, "start") }
                SmallIcon(Icons.Outlined.Home) { act(s.domain, if (s.domain == "lawn_mower") "dock" else "return_to_base") }
            }
            HAControl.ReadOnly -> {}
        }
    }
}

@Composable
private fun SmallIcon(icon: ImageVector, onClick: () -> Unit) {
    FilledTonalIconButton(onClick, Modifier.size(36.dp)) { Icon(icon, null, Modifier.size(18.dp)) }
}

/** Choose Home Assistant entities to map onto one piece of furniture. */
@Composable
fun EntityPickerDialog(furnitureId: String, onDismiss: () -> Unit) {
    val store = LocalStore.current
    val ha = LocalHA.current
    val router = LocalRouter.current
    var chosen by remember { mutableStateOf(store.furniture[furnitureId]?.haEntities?.toSet() ?: emptySet()) }
    var query by remember { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { ha.refresh() }
    EditorDialog("绑定设备", onDismiss, onSave = {
        store.furniture[furnitureId]?.let { f ->
            val kept = f.haEntities.filter { it in chosen }
            store.save(f.copy(haEntities = kept + (chosen - kept.toSet()).sorted()))
        }
        onDismiss()
    }, saveLabel = "完成", saveEnabled = ha.isConfigured) {
        if (!ha.isConfigured) {
            EmptyState("还没连接 Home Assistant", null, Icons.Outlined.Home) {
                Button({ onDismiss(); router.open(Route.HASettings) }) { Text("去设置") }
            }
            return@EditorDialog
        }
        val states = ha.sortedStates.filter { s ->
            (showAll || s.domain in HADomain.mappable || s.entity_id in chosen) &&
                (query.isBlank() || s.name.contains(query.trim(), true) || s.entity_id.contains(query.trim(), true))
        }
        LazyColumn(contentPadding = PaddingValues(16.dp)) {
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("搜索设备") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("显示全部类型", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); Switch(showAll, { showAll = it })
                }
                ha.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            states.groupBy { it.domain }.toSortedMap().forEach { (domain, list) ->
                item(key = "h-$domain") { Text(HADomain.title(domain), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                items(list, key = { it.entity_id }) { s ->
                    val on = s.entity_id in chosen
                    Row(Modifier.fillMaxWidth().clickable { chosen = if (on) chosen - s.entity_id else chosen + s.entity_id }.padding(vertical = 8.dp)
                        .testTag("pickEntity.${s.name}"), verticalAlignment = Alignment.CenterVertically) {
                        Icon(haIcon(s.domain, s.isOn), null)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.name)
                            val other = ha.furnitureFor(s.entity_id)?.takeIf { it.id != furnitureId }
                            Text(s.entity_id + (other?.let { " · 已在${it.name}" } ?: ""), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(if (on) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
                            tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}

/** Pick any piece of furniture in the home. */
@Composable
fun FurniturePickerDialog(title: String, onDismiss: () -> Unit, onPick: (Furniture) -> Unit) {
    val store = LocalStore.current
    EditorDialog(title, onDismiss, onSave = null) {
        LazyColumn(contentPadding = PaddingValues(16.dp)) {
            store.sortedRooms.forEach { room ->
                val pieces = store.furnitureIn(room.id)
                if (pieces.isNotEmpty()) {
                    item(key = room.id) { Text(room.name, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                    items(pieces, key = { it.id }) { f ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(f); onDismiss() }.padding(vertical = 10.dp).testTag("pickFurniture.${f.name}"),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(SymbolIcons.vector(f.symbol ?: Catalog.kind(f.kind).symbol), null)
                            Spacer(Modifier.width(10.dp))
                            Text(f.name)
                        }
                    }
                }
            }
            if (store.furniture.isEmpty()) item { Text("还没有家具") }
        }
    }
}
