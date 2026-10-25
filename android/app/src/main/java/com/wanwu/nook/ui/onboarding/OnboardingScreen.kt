package com.wanwu.nook.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.DoorSliding
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.wanwu.nook.LocalStore
import com.wanwu.nook.data.Catalog
import com.wanwu.nook.data.Furniture
import com.wanwu.nook.data.RoomType
import com.wanwu.nook.data.info
import com.wanwu.nook.ui.art.FurnitureArt.drawFurniture
import com.wanwu.nook.ui.components.NookTopBar
import com.wanwu.nook.ui.components.SymbolIcons
import com.wanwu.nook.ui.settings.AccountDialog
import com.wanwu.nook.ui.settings.BackupButtons
import com.wanwu.nook.ui.theme.LocalNookColors

/** First launch: build the home or sign in to pull an existing one. */
@Composable
fun OnboardingScreen() {
    val store = LocalStore.current
    var step by remember { mutableStateOf(0) }
    var signingIn by remember { mutableStateOf(false) }
    if (step == 0) Welcome(onStart = { step = 1 }, onSignIn = { signingIn = true }) else HomeWizard(onBack = { step = 0 })
    if (signingIn) AccountDialog(onFinished = { if (store.rooms.isNotEmpty()) store.markOnboarded() else step = 1 }) { signingIn = false }
}

@Composable
private fun Welcome(onStart: () -> Unit, onSignIn: () -> Unit) {
    val colors = LocalNookColors.current
    Column(Modifier.fillMaxSize().background(colors.canvas).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        WelcomeArt(Modifier.size(260.dp, 200.dp))
        Spacer(Modifier.height(24.dp))
        Text("万物收纳", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("把家搬进手机：搭好房间和家具，\n打开柜子就知道里面放了什么。", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Feature(Icons.Outlined.GridView, "像搭积木一样画出你的家", "客厅、厨房、卫生间，家具随意摆放、叠放")
            Feature(Icons.Outlined.DoorSliding, "每个柜子都能打开", "衣柜、冰箱、洗碗机……放了什么一目了然")
            Feature(Icons.Outlined.CameraAlt, "拍照自动抠图", "在手机上处理，也可以用图标或 AI 画图标")
            Feature(Icons.Outlined.Home, "接入 Home Assistant", "把灯和电器放到对应的家具上")
        }
        Spacer(Modifier.height(32.dp))
        Button(onStart, Modifier.fillMaxWidth().height(52.dp).testTag("onboarding.start")) { Text("开始搭建我的家") }
        Text("不需要服务器，数据只保存在本机；以后随时可以登录同步。", Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onSignIn, Modifier.testTag("onboarding.signin")) { Text("已有账号？登录同步") }
            Column(horizontalAlignment = Alignment.CenterHorizontally) { BackupButtons(onboarding = true) }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun Feature(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String) {
    Row(Modifier.fillMaxWidth()) {
        Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A tiny furnished floor plan drawn with the app's own furniture art. */
@Composable
private fun WelcomeArt(modifier: Modifier) {
    val colors = LocalNookColors.current
    Canvas(modifier) {
        val s = size.width / 900f
        val rooms = listOf(
            Triple(RoomType.living, floatArrayOf(0f, 0f, 500f, 400f), listOf(Triple("rug", 150f to 150f, 0), Triple("sofa", 140f to 300f, 2),
                Triple("coffee_table", 190f to 190f, 0), Triple("tv_stand", 150f to 10f, 0), Triple("plant", 440f to 10f, 0))),
            Triple(RoomType.bedroom, floatArrayOf(500f, 0f, 400f, 400f), listOf(Triple("bed_double", 110f to 10f, 0), Triple("nightstand", 60f to 10f, 0),
                Triple("nightstand", 295f to 10f, 0), Triple("wardrobe", 120f to 335f, 2))),
            Triple(RoomType.kitchen, floatArrayOf(0f, 400f, 500f, 280f), listOf(Triple("fridge", 5f to 5f, 0), Triple("base_cabinet", 80f to 5f, 0),
                Triple("kitchen_sink", 205f to 5f, 0), Triple("stove", 290f to 5f, 0), Triple("dining_set", 300f to 120f, 0))),
            Triple(RoomType.bathroom, floatArrayOf(500f, 400f, 400f, 280f), listOf(Triple("toilet", 10f to 5f, 0), Triple("bath_vanity", 70f to 5f, 0),
                Triple("bathtub", 220f to 5f, 0), Triple("washer", 330f to 210f, 2))),
        )
        for ((type, r, pieces) in rooms) {
            val o = Offset(r[0] * s, r[1] * s); val sz = Size(r[2] * s, r[3] * s)
            drawRect(colors.floor(type), o, sz)
            for ((kindId, pos, rot) in pieces) {
                val k = Catalog.kind(kindId)
                val f = Furniture(roomId = "X", kind = kindId, name = k.name, x = pos.first.toDouble(), y = pos.second.toDouble(),
                    width = k.width, depth = k.depth, rotation = rot)
                translate(o.x + f.x.toFloat() * s, o.y + f.y.toFloat() * s) {
                    drawFurniture(f, Size(f.footprintWidth.toFloat() * s, f.footprintDepth.toFloat() * s), colors.dark)
                }
            }
            drawRect(colors.wall, o, sz, style = Stroke(3f))
        }
    }
}

@Composable
private fun HomeWizard(onBack: () -> Unit) {
    val store = LocalStore.current
    var homeName by remember { mutableStateOf("我的家") }
    val counts = remember {
        mutableStateMapOf(RoomType.living to 1, RoomType.bedroom to 2, RoomType.kitchen to 1, RoomType.bathroom to 1, RoomType.dining to 0,
            RoomType.study to 0, RoomType.balcony to 1, RoomType.entrance to 1, RoomType.storage to 0, RoomType.kids to 0, RoomType.other to 0)
    }
    var withFurniture by remember { mutableStateOf(true) }
    val total = counts.values.sum()
    val colors = LocalNookColors.current
    BackHandler(onBack = onBack)
    Scaffold(topBar = { NookTopBar("搭建你的家", onBack = onBack) }, containerColor = colors.canvas) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                OutlinedTextField(homeName, { homeName = it }, Modifier.fillMaxWidth(), label = { Text("家的名字") }, singleLine = true)
                Text("有哪些房间", Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge)
            }
            items(RoomType.entries) { type ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.card).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(colors.floor(type)), contentAlignment = Alignment.Center) {
                        Icon(SymbolIcons.vector(type.info.symbol), null, Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(type.info.title, Modifier.weight(1f))
                    IconButton({ counts[type] = maxOf(0, (counts[type] ?: 0) - 1) }) { Text("－", style = MaterialTheme.typography.titleLarge) }
                    Text("${counts[type] ?: 0}", Modifier.testTag("count.${type.name}"))
                    IconButton({ counts[type] = minOf(6, (counts[type] ?: 0) + 1) }) { Text("＋", style = MaterialTheme.typography.titleLarge) }
                }
            }
            item {
                Text("先大概选一下，之后可以在户型图里拖动、改大小、增删房间。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("自动摆放常见家具")
                        Text("比如卧室放床、床头柜和衣柜，厨房放冰箱、橱柜、灶台。都可以再改。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(withFurniture, { withFurniture = it })
                }
                Button({ store.buildHome(homeName, RoomType.entries.map { it to (counts[it] ?: 0) }, withFurniture) },
                    Modifier.fillMaxWidth().padding(top = 16.dp).height(52.dp).testTag("wizard.create")) {
                    Text(if (total == 0) "先不添加房间" else "创建 $total 个房间")
                }
            }
        }
    }
}
