package com.wanwu.nook.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wanwu.nook.LocalStore
import com.wanwu.nook.data.IconRef
import com.wanwu.nook.data.Item
import com.wanwu.nook.image.PathOp
import com.wanwu.nook.image.SvgDocument
import com.wanwu.nook.ui.theme.LocalNookColors
import com.wanwu.nook.ui.theme.hexColor

// region Icons

private val svgCache = HashMap<String, SvgDocument?>()

fun svgDocument(svg: String): SvgDocument? = synchronized(svgCache) { svgCache.getOrPut(svg) { SvgDocument.parse(svg) } }

fun PathOp.applyTo(p: Path) {
    when (this) {
        is PathOp.MoveTo -> p.moveTo(x, y)
        is PathOp.LineTo -> p.lineTo(x, y)
        is PathOp.CubicTo -> p.cubicTo(x1, y1, x2, y2, x, y)
        is PathOp.QuadTo -> p.quadraticTo(x1, y1, x, y)
        PathOp.Close -> p.close()
    }
}

/** Renders a sanitised SVG icon in [color]. */
@Composable
fun SvgIcon(svg: String, color: Color, modifier: Modifier = Modifier) {
    val doc = remember(svg) { svgDocument(svg) }
    Canvas(modifier) {
        doc ?: return@Canvas
        val s = minOf(size.width / doc.width, size.height / doc.height)
        translate((size.width - doc.width * s) / 2 - doc.minX * s, (size.height - doc.height * s) / 2 - doc.minY * s) {
            scale(s, s, pivot = Offset.Zero) {
                for (shape in doc.shapes) {
                    val path = Path().apply {
                        shape.ops.forEach { it.applyTo(this) }
                        fillType = if (shape.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero
                    }
                    if (shape.fill) drawPath(path, color, alpha = shape.opacity)
                    if (shape.stroke) drawPath(path, color, alpha = shape.opacity, style = Stroke(
                        width = shape.strokeWidth,
                        cap = when (shape.lineCap) { "round" -> StrokeCap.Round; "square" -> StrokeCap.Square; else -> StrokeCap.Butt },
                        join = when (shape.lineJoin) { "round" -> StrokeJoin.Round; "bevel" -> StrokeJoin.Bevel; else -> StrokeJoin.Miter },
                    ))
                }
            }
        }
    }
}

/** An item's icon (symbol, emoji or AI-drawn) on a tinted tile. */
@Composable
fun IconTile(icon: IconRef?, fallbackSymbol: String = "shippingbox", tint: Color = MaterialTheme.colorScheme.primary,
             size: Dp = 44.dp, modifier: Modifier = Modifier) {
    val store = LocalStore.current
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.24f)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        val svg = if (icon?.kind == "custom") store.icons[icon.value.uppercase()]?.svg else null
        when {
            icon?.kind == "emoji" -> Text(icon.value, fontSize = (size.value * 0.5f).sp)
            svg != null -> SvgIcon(svg, tint, Modifier.size(size * 0.68f))
            icon?.kind == "symbol" -> Icon(SymbolIcons.vector(icon.value), null, tint = tint, modifier = Modifier.size(size * 0.5f))
            else -> Icon(SymbolIcons.vector(fallbackSymbol), null, tint = tint, modifier = Modifier.size(size * 0.5f))
        }
    }
}

// endregion
// region Photos

/** A stored photo, loaded lazily (and fetched from the server if this device lacks it). */
@Composable
fun StoredImage(fileId: String, modifier: Modifier = Modifier, maxPixel: Int = 512, contentScale: ContentScale = ContentScale.Fit) {
    val store = LocalStore.current
    val bitmap by produceState<Bitmap?>(store.files.cached(fileId, maxPixel), fileId) {
        if (value == null) value = store.files.bitmap(fileId, maxPixel)
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) Image(remember(b) { b.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = contentScale)
        else Icon(Icons.Outlined.Image, null, tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun BitmapImage(bitmap: Bitmap, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) {
    val img: ImageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    Image(img, null, modifier, contentScale = contentScale)
}

/** Photo or icon for an item, at a given square size. */
@Composable
fun ItemThumbnail(item: Item, size: Dp = 52.dp) {
    val store = LocalStore.current
    val category = store.categoryOf(item)
    val tint = hexColor(category?.colorHex)
    val photo = item.photoId
    if (photo != null) {
        StoredImage(photo, Modifier.size(size).clip(RoundedCornerShape(size * 0.22f)).background(LocalNookColors.current.photo).padding(size * 0.06f),
            maxPixel = (size.value * 3).toInt())
    } else {
        IconTile(item.icon, category?.symbol ?: "shippingbox", tint, size)
    }
}

@Composable
fun Checkerboard(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = 10.dp.toPx()
        var y = 0f
        while (y < size.height) {
            var x = 0f
            while (x < size.width) {
                if (((x / s).toInt() + (y / s).toInt()) % 2 == 0) drawRect(Color.Gray.copy(alpha = 0.15f), Offset(x, y), Size(s, s))
                x += s
            }
            y += s
        }
    }
}

// endregion
// region Small pieces

@Composable
fun ColorDots(colors: List<String>, size: Dp = 14.dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        colors.forEach {
            Box(Modifier.size(size).clip(CircleShape).background(hexColor(it)).border(0.5.dp, Color.Black.copy(alpha = 0.15f), CircleShape))
        }
    }
}

@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.secondary, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Row(Modifier.clip(CircleShape).background(color.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = color, modifier = Modifier.size(11.dp)); Spacer(Modifier.width(3.dp)) }
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ItemBadges(item: Item) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        val days = item.expiryDays
        if (item.isExpired) Pill("已过期", Color(0xFFE53935))
        else if (item.isExpiringSoon && days != null) Pill(if (days == 0) "今天到期" else "$days 天后到期", Color(0xFFF57C00))
        if (item.isLowStock) Pill("库存不足", Color(0xFFF57C00))
        if (item.favorite) Text("♥", color = Color(0xFFE91E63), fontSize = 11.sp)
    }
}

@Composable
fun TagEditor(tags: List<String>, onChange: (List<String>) -> Unit) {
    var draft by remember { mutableStateOf("") }
    fun add() {
        val parts = draft.split(',', '，', '#', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        onChange(tags + parts.filter { it !in tags })
        draft = ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { tag ->
                    Row(Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .clickable { onChange(tags - tag) }.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("#$tag", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Outlined.Close, "删除标签 $tag", Modifier.size(12.dp))
                    }
                }
            }
        }
        OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth(), placeholder = { Text("添加标签，回车确认") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }),
            trailingIcon = { if (draft.isNotBlank()) TextButton({ add() }) { Text("添加") } })
    }
}

/** Number field for centimetre sizes. */
@Composable
fun CmField(label: String, value: Double, range: ClosedFloatingPointRange<Double> = 5.0..5000.0, onChange: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toLong().toString()) }
    OutlinedTextField(text, { t ->
        text = t.filter { it.isDigit() }.take(5)
        text.toDoubleOrNull()?.let { onChange(it.coerceIn(range)) }
    }, Modifier.fillMaxWidth(), label = { Text(label) }, suffix = { Text("cm") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

/** Rounded card section. */
@Composable
fun Card(modifier: Modifier = Modifier, padding: Dp = 14.dp, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(LocalNookColors.current.card).padding(padding)) { content() }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp), style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun EmptyState(title: String, message: String? = null, icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Outlined.Image,
               action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.size(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (message != null) {
            Spacer(Modifier.size(6.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        if (action != null) { Spacer(Modifier.size(16.dp)); action() }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
}

// endregion
// region Screens & dialogs

@Composable
fun NookTopBar(title: String, onBack: (() -> Unit)? = null, subtitle: String? = null,
               actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}) {
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) Text(subtitle, maxLines = 1, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        navigationIcon = { if (onBack != null) IconButton(onBack, Modifier.testTag("nav.back")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

/** Full-screen editor with 取消 / 保存. */
@Composable
fun EditorDialog(title: String, onDismiss: () -> Unit, onSave: (() -> Unit)?, saveEnabled: Boolean = true, saveLabel: String = "保存",
                 content: @Composable () -> Unit) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title, style = MaterialTheme.typography.titleMedium) },
                    navigationIcon = { TextButton(onDismiss) { Text("取消") } },
                    actions = { if (onSave != null) TextButton(onSave, enabled = saveEnabled, modifier = Modifier.testTag("editor.save")) { Text(saveLabel, fontWeight = FontWeight.SemiBold) } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            Box(Modifier.padding(padding).imePadding().fillMaxSize()) { content() }
        }
    }
}

data class Choice(val label: String, val destructive: Boolean = false, val action: () -> Unit)

/** A dialog offering several actions (e.g. delete-keep-items / delete-everything). */
@Composable
fun ChoiceDialog(title: String, message: String?, choices: List<Choice>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (message != null) Text(message)
                Spacer(Modifier.size(8.dp))
                choices.forEach { c ->
                    TextButton({ onDismiss(); c.action() }, Modifier.fillMaxWidth().testTag("choice.${c.label}")) {
                        Text(c.label, color = if (c.destructive) MaterialTheme.colorScheme.error else LocalContentColor.current,
                            modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}

@Composable
fun TextInputDialog(title: String, initial: String = "", placeholder: String = "", confirm: String = "保存",
                    onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, Modifier.testTag("prompt.field"), placeholder = { Text(placeholder) }, singleLine = true) },
        confirmButton = {
            TextButton({ onDismiss(); onConfirm(text.trim()) }, enabled = text.isNotBlank(), modifier = Modifier.testTag("prompt.confirm")) { Text(confirm) }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}

@Composable
fun SettingRow(title: String, value: String? = null, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null,
               trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        if (value != null) Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        trailing?.invoke()
    }
}

@Composable
fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

@Composable
fun dpToPx(dp: Dp): Float = with(LocalDensity.current) { dp.toPx() }

// endregion
