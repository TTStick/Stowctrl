package com.wanwu.nook.ui.items

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.wanwu.nook.LocalContainer
import com.wanwu.nook.LocalStore
import com.wanwu.nook.LocalSync
import com.wanwu.nook.data.CategoryTemplate
import com.wanwu.nook.data.Dates
import com.wanwu.nook.data.Item
import com.wanwu.nook.data.Seasons
import com.wanwu.nook.data.Units
import com.wanwu.nook.data.clean
import com.wanwu.nook.data.newId
import com.wanwu.nook.data.now
import com.wanwu.nook.image.ColorExtractor
import com.wanwu.nook.image.CutoutResult
import com.wanwu.nook.image.ImageTools
import com.wanwu.nook.ui.components.BitmapImage
import com.wanwu.nook.ui.components.Card
import com.wanwu.nook.ui.components.Checkerboard
import com.wanwu.nook.ui.components.EditorDialog
import com.wanwu.nook.ui.components.IconTile
import com.wanwu.nook.ui.components.StoredImage
import com.wanwu.nook.ui.components.TagEditor
import com.wanwu.nook.ui.theme.LocalNookColors
import com.wanwu.nook.ui.theme.hexColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.TimeZone

private class PhotoDraft(
    val original: Bitmap,
    val cutout: Bitmap? = null,
    val useCutout: Boolean = false,
    val reuseOriginalId: String? = null,
    val result: CutoutResult? = null,
    val keep: Set<Int> = emptySet(),
)

/** Create (existing == null) or edit an item. */
@Composable
fun ItemEditorDialog(existing: Item?, compartmentId: String?, onDismiss: () -> Unit) {
    val store = LocalStore.current
    val sync = LocalSync.current
    val container = LocalContainer.current
    val prefs = container.appPrefs
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = LocalNookColors.current
    val initial = remember { existing ?: Item(name = "", compartmentId = compartmentId) }
    var draft by remember { mutableStateOf(initial) }
    var photo by remember { mutableStateOf<PhotoDraft?>(null) }
    var removePhoto by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
    var cutoutError by remember { mutableStateOf<String?>(null) }
    var showIcon by remember { mutableStateOf(false) }
    var showLocation by remember { mutableStateOf(false) }
    var showCategory by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var datePicking by remember { mutableStateOf<String?>(null) }
    var unitMenu by remember { mutableStateOf(false) }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    val category = draft.category?.let { store.categories[it] }
    val template = category?.tmpl ?: CategoryTemplate.general
    val dirty = draft != initial || photo != null || removePhoto
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }

    fun updateColors(b: Bitmap) { val c = ColorExtractor.palette(b); if (c.isNotEmpty()) draft = draft.copy(colors = c) }

    fun runCutout() {
        val p = photo ?: return
        scope.launch {
            processing = true
            cutoutError = null
            try {
                val server = if (sync.canServerCutout) sync.client else null
                val result = container.cutout.cutout(p.original, prefs.cutoutEngine, server)
                photo = PhotoDraft(p.original, result.image, true, p.reuseOriginalId, result, result.subjects.indices.toSet())
                withContext(Dispatchers.Default) { ColorExtractor.palette(result.image) }.takeIf { it.isNotEmpty() }?.let { draft = draft.copy(colors = it) }
            } catch (e: Exception) {
                photo = PhotoDraft(p.original, null, false, p.reuseOriginalId)
                cutoutError = (e.message ?: "抠图失败") + if (!sync.canServerCutout && prefs.cutoutEngine != "device") "。可在服务器启用抠图作为备用。" else ""
                updateColors(p.original)
            } finally {
                processing = false
            }
        }
    }

    fun setNewPhoto(b: Bitmap) {
        photo = PhotoDraft(ImageTools.scaled(b, 2048))
        removePhoto = false
        cutoutError = null
        if (prefs.autoCutout) runCutout() else updateColors(b)
    }

    fun loadUri(uri: Uri) {
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val longest = maxOf(info.size.width, info.size.height)
                        if (longest > 2048) { val s = 2048f / longest; d.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt()) }
                    }
                }.getOrNull()
            }
            bmp?.let { setNewPhoto(it) } ?: run { cutoutError = "无法读取这张照片" }
        }
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { loadUri(it) } }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile
        if (ok && f != null) loadUri(Uri.fromFile(f))
    }

    fun save() {
        var item = draft.copy(name = draft.name.trim())
        val oldIds = listOfNotNull(initial.photoId, initial.originalPhotoId)
        val p = photo
        if (p != null) {
            var originalId = p.reuseOriginalId
            if (originalId == null) {
                originalId = newId() + ".jpg"
                store.files.write(ImageTools.jpeg(p.original), originalId)
                store.registerUpload(originalId)
            }
            item = item.copy(originalPhotoId = originalId)
            val cut = p.cutout
            item = if (p.useCutout && cut != null) {
                val id = newId() + ".png"
                store.files.write(ImageTools.png(cut), id)
                store.registerUpload(id)
                item.copy(photoId = id)
            } else item.copy(photoId = originalId)
        } else if (removePhoto) {
            item = item.copy(photoId = null, originalPhotoId = null)
        }
        store.save(item)
        oldIds.filter { it != item.photoId && it != item.originalPhotoId }.forEach { store.deleteFileIfUnused(it) }
        onDismiss()
    }

    val dismiss = { if (dirty) confirmDiscard = true else onDismiss() }
    EditorDialog(if (existing == null) "添加物品" else "编辑物品", dismiss, onSave = { save() },
        saveEnabled = draft.name.isNotBlank() && !processing) {
        BackHandler { dismiss() }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Card {
                Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp)).background(colors.photo), contentAlignment = Alignment.Center) {
                    val p = photo
                    when {
                        p != null && p.useCutout && p.cutout != null -> { Checkerboard(Modifier.fillMaxSize()); BitmapImage(p.cutout, Modifier.fillMaxSize().padding(12.dp)) }
                        p != null -> BitmapImage(p.original, Modifier.fillMaxSize())
                        !removePhoto && draft.photoId != null -> StoredImage(draft.photoId!!, Modifier.fillMaxSize().padding(12.dp), 800)
                        else -> IconTile(draft.icon, category?.symbol ?: "shippingbox", hexColor(category?.colorHex), 96.dp)
                    }
                    if (processing) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color.White)
                            Text("正在抠图…", color = Color.White, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (hasCamera) MediaButton("拍照", Icons.Outlined.CameraAlt, Modifier.weight(1f)) {
                        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                        val f = File(dir, "${newId()}.jpg")
                        cameraFile = f
                        takePhoto.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", f))
                    }
                    MediaButton("相册", Icons.Outlined.PhotoLibrary, Modifier.weight(1f).testTag("item.gallery")) {
                        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    MediaButton("图标", Icons.Outlined.GridView, Modifier.weight(1f).testTag("item.icon")) { showIcon = true }
                }
                val p = photo
                if (p != null) {
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("抠图（去掉背景）", Modifier.weight(1f))
                        Switch(p.useCutout, { on ->
                            if (on && p.cutout == null) runCutout()
                            else photo = PhotoDraft(p.original, p.cutout, on, p.reuseOriginalId, p.result, p.keep)
                        }, enabled = !processing)
                    }
                    val subjects = p.result?.subjects.orEmpty()
                    if (p.useCutout && subjects.size > 1) {
                        Text("识别到 ${subjects.size} 个主体，选择要保留的：", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            subjects.indices.forEach { i ->
                                FilterChip(i in p.keep, {
                                    val keep = if (i in p.keep) (p.keep - i).ifEmpty { p.keep } else p.keep + i
                                    val img = container.cutout.combine(p.original, p.result!!, keep)
                                    photo = PhotoDraft(p.original, img, true, p.reuseOriginalId, p.result, keep)
                                }, { Text("主体 ${i + 1}") })
                            }
                        }
                    }
                }
                cutoutError?.let {
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Warning, null, tint = Color(0xFFF57C00), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFF57C00))
                    }
                }
                if (photo != null || (!removePhoto && draft.photoId != null)) {
                    if (photo == null) TextButton({
                        scope.launch {
                            val id = draft.originalPhotoId ?: draft.photoId ?: return@launch
                            val bytes = store.files.loadBytes(id)
                            val bmp = bytes?.let { withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size) } }
                            if (bmp == null) { cutoutError = "原图还没下载到这台设备"; return@launch }
                            val hadCutout = draft.originalPhotoId != null && draft.originalPhotoId != draft.photoId
                            photo = PhotoDraft(bmp, reuseOriginalId = id)
                            if (hadCutout) runCutout()
                        }
                    }) { Text("调整抠图") }
                    TextButton({ photo = null; removePhoto = true }) { Text("移除照片，改用图标", color = MaterialTheme.colorScheme.error) }
                }
                Text("照片优先在手机上抠图，不上传第三方。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Card {
                OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, Modifier.fillMaxWidth().testTag("item.name"),
                    label = { Text("名称（必填）") }, singleLine = true)
                PickerRow("分类", category?.name ?: "未分类", category?.let { hexColor(it.colorHex) }, Modifier.testTag("item.category")) { showCategory = true }
                PickerRow("位置", store.compartmentPath(draft.compartment), if (draft.compartment == null) Color(0xFFF57C00) else null,
                    Modifier.testTag("item.location")) { showLocation = true }
            }

            Card {
                Text("数量", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    var qtyText by remember(draft.quantity) { mutableStateOf(draft.quantity.clean()) }
                    IconButton({ draft = draft.copy(quantity = maxOf(0.0, draft.quantity - 1)) }) { Text("－", style = MaterialTheme.typography.titleLarge) }
                    OutlinedTextField(qtyText, { t -> qtyText = t; t.toDoubleOrNull()?.let { draft = draft.copy(quantity = maxOf(0.0, it)) } },
                        Modifier.width(96.dp), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    IconButton({ draft = draft.copy(quantity = draft.quantity + 1) }) { Text("＋", style = MaterialTheme.typography.titleLarge) }
                    Box {
                        TextButton({ unitMenu = true }) { Text(draft.unit) }
                        DropdownMenu(unitMenu, { unitMenu = false }) {
                            (if (draft.unit in Units.all) Units.all else listOf(draft.unit) + Units.all).forEach { u ->
                                DropdownMenuItem({ Text(u) }, { unitMenu = false; draft = draft.copy(unit = u) })
                            }
                        }
                    }
                }
                ToggleRow("库存提醒", draft.lowStock != null) { draft = draft.copy(lowStock = if (it) draft.lowStock ?: 1.0 else null) }
                draft.lowStock?.let { low ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("低于 ${low.clean()} ${draft.unit} 时提醒", Modifier.weight(1f))
                        IconButton({ draft = draft.copy(lowStock = maxOf(0.0, low - 1)) }) { Text("－") }
                        IconButton({ draft = draft.copy(lowStock = low + 1) }) { Text("＋") }
                    }
                }
            }

            if (template == CategoryTemplate.perishable) Card {
                Text("保质期", style = MaterialTheme.typography.titleSmall)
                ToggleRow("记录到期日", draft.expiresAt != null) { draft = draft.copy(expiresAt = if (it) draft.expiresAt ?: Dates.addMonths(now(), 1) else null) }
                draft.expiresAt?.let { DateRow("到期日", it) { datePicking = "expires" } }
            }

            if (template == CategoryTemplate.clothing) Card {
                Text("衣物", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("季节", Modifier.weight(1f))
                    Seasons.all.forEach { s ->
                        FilterChip(s in draft.seasons, { draft = draft.copy(seasons = if (s in draft.seasons) draft.seasons - s else draft.seasons + s) }, { Text(s) })
                    }
                }
                Text("颜色（点按删除，拍照后自动识别）", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    draft.colors.forEach { hex ->
                        Box(Modifier.size(28.dp).clip(CircleShape).background(hexColor(hex)).border(1.dp, Color.Black.copy(alpha = 0.15f), CircleShape)
                            .clickable { draft = draft.copy(colors = draft.colors - hex) })
                    }
                    if (draft.colors.size < 6) listOf("#222222", "#f5f5f5", "#9e9e9e", "#c62828", "#1565c0", "#2e7d32", "#f9a825", "#6d4c41", "#f8bbd0", "#283593")
                        .filter { it !in draft.colors }.forEach { hex ->
                            Box(Modifier.size(20.dp).clip(CircleShape).background(hexColor(hex)).border(1.dp, Color.Black.copy(alpha = 0.1f), CircleShape)
                                .clickable { draft = draft.copy(colors = draft.colors + hex) })
                        }
                }
            }

            Card { Text("标签", style = MaterialTheme.typography.titleSmall); Spacer(Modifier.height(6.dp)); TagEditor(draft.tags) { draft = draft.copy(tags = it) } }
            Card { OutlinedTextField(draft.notes, { draft = draft.copy(notes = it) }, Modifier.fillMaxWidth(), label = { Text("备注") }, minLines = 2) }

            Card {
                Text("更多", style = MaterialTheme.typography.titleSmall)
                ToggleRow("购买价格", draft.price != null) { draft = draft.copy(price = if (it) draft.price ?: 0.0 else null) }
                draft.price?.let { price ->
                    var priceText by remember { mutableStateOf(if (price == 0.0) "" else price.clean()) }
                    OutlinedTextField(priceText, { t -> priceText = t; draft = draft.copy(price = t.toDoubleOrNull() ?: 0.0) },
                        Modifier.fillMaxWidth(), prefix = { Text("¥") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                ToggleRow("购买日期", draft.purchasedAt != null) { draft = draft.copy(purchasedAt = if (it) draft.purchasedAt ?: now() else null) }
                draft.purchasedAt?.let { DateRow("日期", it) { datePicking = "purchased" } }
                if (template != CategoryTemplate.perishable) {
                    ToggleRow("到期日（保修、证件等）", draft.expiresAt != null) {
                        draft = draft.copy(expiresAt = if (it) draft.expiresAt ?: Dates.addMonths(now(), 12) else null)
                    }
                    draft.expiresAt?.let { DateRow("到期日", it) { datePicking = "expires" } }
                }
                ToggleRow("收藏", draft.favorite) { draft = draft.copy(favorite = it) }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (showIcon) IconPickerDialog(draft.name, onDismiss = { showIcon = false }) { draft = draft.copy(icon = it) }
    if (showLocation) LocationPickerSheet(draft.compartment, onDismiss = { showLocation = false }) { draft = draft.copy(compartmentId = it) }
    if (showCategory) CategoryPickerSheet(draft.category, onDismiss = { showCategory = false }) { draft = draft.copy(categoryId = it) }
    if (confirmDiscard) AlertDialog({ confirmDiscard = false }, title = { Text("放弃这些修改？") },
        confirmButton = { TextButton({ confirmDiscard = false; onDismiss() }) { Text("放弃修改", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmDiscard = false }) { Text("继续编辑") } })
    datePicking?.let { which ->
        val initialMs = (if (which == "expires") draft.expiresAt else draft.purchasedAt) ?: now()
        val state = rememberDatePickerState(initialSelectedDateMillis = toUtcDay(initialMs))
        DatePickerDialog({ datePicking = null }, confirmButton = {
            TextButton({
                state.selectedDateMillis?.let { utc ->
                    val local = fromUtcDay(utc)
                    draft = if (which == "expires") draft.copy(expiresAt = local) else draft.copy(purchasedAt = local)
                }
                datePicking = null
            }) { Text("确定") }
        }, dismissButton = { TextButton({ datePicking = null }) { Text("取消") } }) { DatePicker(state) }
    }
}

/** The date picker works in UTC midnights; items store local noon so the day never shifts. */
private fun toUtcDay(localMs: Long): Long {
    val l = Calendar.getInstance().apply { timeInMillis = localMs }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear(); set(l.get(Calendar.YEAR), l.get(Calendar.MONTH), l.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

private fun fromUtcDay(utcMs: Long): Long {
    val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMs }
    return Calendar.getInstance().apply {
        clear(); set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH), 12, 0)
    }.timeInMillis
}

@Composable
private fun MediaButton(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)).clickable(onClick = onClick)
        .padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun PickerRow(title: String, value: String, valueColor: Color?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Text(value, color = valueColor ?: MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun ToggleRow(title: String, on: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Switch(on, onChange)
    }
}

@Composable
private fun DateRow(title: String, ms: Long, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(title, Modifier.weight(1f))
        Text(Dates.format(ms), color = MaterialTheme.colorScheme.primary)
    }
}
