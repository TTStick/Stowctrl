package com.wanwu.nook.ui.items

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.RemoveCircle
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wanwu.nook.LocalRouter
import com.wanwu.nook.LocalStore
import com.wanwu.nook.Route
import com.wanwu.nook.data.CategoryTemplate
import com.wanwu.nook.data.Dates
import com.wanwu.nook.data.clean
import com.wanwu.nook.data.now
import com.wanwu.nook.ui.components.Card
import com.wanwu.nook.ui.components.Choice
import com.wanwu.nook.ui.components.ChoiceDialog
import com.wanwu.nook.ui.components.ColorDots
import com.wanwu.nook.ui.components.Divider
import com.wanwu.nook.ui.components.EmptyState
import com.wanwu.nook.ui.components.IconTile
import com.wanwu.nook.ui.components.ItemBadges
import com.wanwu.nook.ui.components.NookTopBar
import com.wanwu.nook.ui.components.Pill
import com.wanwu.nook.ui.components.StoredImage
import com.wanwu.nook.ui.components.SymbolIcons
import com.wanwu.nook.ui.theme.LocalNookColors
import com.wanwu.nook.ui.theme.hexColor

@Composable
fun ItemDetailScreen(itemId: String) {
    val store = LocalStore.current
    val router = LocalRouter.current
    val colors = LocalNookColors.current
    val item = store.items[itemId]
    if (item == null) {
        Scaffold(topBar = { NookTopBar("物品", onBack = { router.back() }) }) { p ->
            Box(Modifier.padding(p)) { EmptyState("物品已删除", "这件物品可能在另一台设备上被删除了。") }
        }
        return
    }
    val category = store.categoryOf(item)
    val template = category?.tmpl ?: CategoryTemplate.general
    var editing by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            NookTopBar(item.name, onBack = { router.back() }, actions = {
                IconButton({ store.save(item.copy(favorite = !item.favorite)) }) {
                    Icon(if (item.favorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, if (item.favorite) "取消收藏" else "收藏",
                        tint = if (item.favorite) Color(0xFFE91E63) else MaterialTheme.colorScheme.onSurface)
                }
                TextButton({ editing = true }, Modifier.testTag("item.edit")) { Text("编辑") }
            })
        },
        containerColor = colors.canvas,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Hero
            Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(18.dp)).background(colors.photo), contentAlignment = Alignment.Center) {
                val photo = if (showOriginal) item.originalPhotoId ?: item.photoId else item.photoId
                if (photo != null) StoredImage(photo, Modifier.fillMaxSize().padding(16.dp).clickable { fullscreen = true }, 1024)
                else IconTile(item.icon, category?.symbol ?: "shippingbox", hexColor(category?.colorHex), 120.dp)
                if (item.originalPhotoId != null && item.originalPhotoId != item.photoId) {
                    OutlinedButton({ showOriginal = !showOriginal }, Modifier.align(Alignment.BottomEnd).padding(10.dp)) {
                        Text(if (showOriginal) "看抠图" else "看原图")
                    }
                }
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(item.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    category?.let { Pill(it.name, hexColor(it.colorHex), SymbolIcons.vector(it.symbol)) }
                    ItemBadges(item)
                }
            }
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("数量", Modifier.weight(1f))
                    IconButton({ store.adjustQuantity(item.id, -1.0) }, enabled = item.quantity > 0, modifier = Modifier.testTag("qty.minus")) {
                        Icon(Icons.Outlined.RemoveCircle, "减少", tint = MaterialTheme.colorScheme.primary)
                    }
                    Text("${item.quantity.clean()} ${item.unit}", Modifier.testTag("qty.value"), style = MaterialTheme.typography.titleMedium)
                    IconButton({ store.adjustQuantity(item.id, 1.0) }, Modifier.testTag("qty.plus")) {
                        Icon(Icons.Outlined.AddCircle, "增加", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                if (item.isLowStock) Text("库存不足（提醒阈值 ${item.lowStock!!.clean()} ${item.unit}）", color = Color(0xFFF57C00),
                    style = MaterialTheme.typography.bodySmall)
                Divider()
                val loc = store.location(item)
                if (loc != null) {
                    Text("存放位置", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().clickable { router.open(Route.Furniture(loc.furniture.id)) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Place, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(store.locationText(item), Modifier.weight(1f))
                        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
                    }
                    TextButton({ router.showOnPlan(loc.furniture) }) {
                        Icon(Icons.Outlined.Map, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("在户型图中定位")
                    }
                } else {
                    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Inbox, null, tint = Color(0xFFF57C00)); Spacer(Modifier.width(8.dp))
                        Text("未归位", color = Color(0xFFF57C00))
                    }
                }
                TextButton({ moving = true }) { Text("移动到其他位置") }
            }
            item.expiresAt?.let { exp ->
                Card {
                    Text("保质期", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.padding(top = 6.dp)) { Text("到期日", Modifier.weight(1f)); Text(Dates.format(exp)) }
                    val d = Dates.daysFromToday(exp)
                    Text(if (d < 0) "已过期 ${-d} 天" else if (d == 0) "今天到期" else "还有 $d 天", Modifier.padding(top = 4.dp),
                        color = if (d < 0) Color(0xFFE53935) else if (d <= 7) Color(0xFFF57C00) else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (template == CategoryTemplate.clothing || item.colors.isNotEmpty() || item.seasons.isNotEmpty() || item.wearLog.isNotEmpty()) {
                Card {
                    Text("穿着", style = MaterialTheme.typography.titleSmall)
                    if (item.colors.isNotEmpty()) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("颜色", Modifier.weight(1f)); ColorDots(item.colors, 18.dp)
                    }
                    if (item.seasons.isNotEmpty()) Row(Modifier.padding(top = 8.dp)) { Text("季节", Modifier.weight(1f)); Text(item.seasons.joinToString(" · ")) }
                    val wornToday = item.wearLog.any { Dates.sameDay(it, now()) }
                    FilledTonalButton({ store.save(item.copy(wearLog = listOf(now()) + item.wearLog)) }, Modifier.padding(top = 8.dp), enabled = !wornToday) {
                        Text(if (wornToday) "今天已记录" else "今天穿了")
                    }
                    if (item.wearLog.isNotEmpty()) {
                        Text("穿过 ${item.wearLog.size} 次", Modifier.padding(top = 6.dp))
                        item.wearLog.sortedDescending().take(10).forEach { d ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(Dates.format(d), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                IconButton({ store.save(item.copy(wearLog = item.wearLog - d)) }) { Icon(Icons.Outlined.Close, "删除记录", Modifier.size(16.dp)) }
                            }
                        }
                    }
                }
            }
            if (item.price != null || item.purchasedAt != null) {
                Card {
                    Text("购买", style = MaterialTheme.typography.titleSmall)
                    item.price?.let { Row(Modifier.padding(top = 6.dp)) { Text("价格", Modifier.weight(1f)); Text("¥${it.clean()}") } }
                    item.purchasedAt?.let { Row(Modifier.padding(top = 6.dp)) { Text("日期", Modifier.weight(1f)); Text(Dates.format(it)) } }
                    if (item.price != null && item.wearLog.isNotEmpty()) Row(Modifier.padding(top = 6.dp)) {
                        Text("每次穿着成本", Modifier.weight(1f)); Text("¥${(item.price / item.wearLog.size).clean()}")
                    }
                }
            }
            if (item.tags.isNotEmpty()) Card {
                Text("标签", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.tags.forEach { Text("#$it", Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp)) }
                }
            }
            if (item.notes.isNotEmpty()) Card { Text("备注", style = MaterialTheme.typography.titleSmall); Text(item.notes, Modifier.padding(top = 6.dp)) }
            Card {
                TextButton({ store.duplicateItem(item.id)?.let { router.open(Route.Item(it.id)) } }) { Text("复制一份") }
                TextButton({ deleting = true }, Modifier.testTag("item.delete")) { Text("删除物品", color = MaterialTheme.colorScheme.error) }
                Text("添加于 ${Dates.format(item.createdAt)} · 更新于 ${Dates.format(item.updatedAt)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (editing) ItemEditorDialog(item, item.compartment) { editing = false }
    if (moving) LocationPickerSheet(item.compartment, onDismiss = { moving = false }) { store.moveItems(listOf(item.id), it) }
    if (deleting) ChoiceDialog("删除「${item.name}」？", null, listOf(Choice("删除", true) { store.deleteItem(item.id); router.back() }),
        onDismiss = { deleting = false })
    if (fullscreen) {
        val photo = if (showOriginal) item.originalPhotoId ?: item.photoId else item.photoId
        Dialog({ fullscreen = false }, DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { fullscreen = false }) {
                photo?.let { StoredImage(it, Modifier.fillMaxSize(), 2048) }
            }
        }
    }
}
