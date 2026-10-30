package com.wanwu.nook.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.util.UUID

/*
 * Records are shared with the iOS app through the sync server, so the JSON
 * shape must match exactly: same field names, upper-case UUID strings, and
 * dates as milliseconds since 1970 (iOS writes them as fractional numbers).
 */

val NookJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
    isLenient = true
}

fun newId(): String = UUID.randomUUID().toString().uppercase()

fun now(): Long = System.currentTimeMillis()

/** Milliseconds since 1970; accepts the fractional values iOS produces. */
object Millis : KSerializer<Long> {
    override val descriptor = PrimitiveSerialDescriptor("Millis", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
    override fun deserialize(decoder: Decoder): Long = Math.round(decoder.decodeDouble())
}

/** Upper-cases ids so records match the ones iOS writes. */
object Uid : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("Uid", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value.uppercase())
    override fun deserialize(decoder: Decoder): String = decoder.decodeString().uppercase()
}

data class RecordKey(val entity: String, val id: String)

@Serializable
data class IconRef(val kind: String = "symbol", val value: String = "shippingbox") {
    companion object {
        fun symbol(name: String) = IconRef("symbol", name)
        fun emoji(e: String) = IconRef("emoji", e)
        fun custom(id: String) = IconRef("custom", id)
    }
}

enum class RoomType { living, bedroom, kitchen, bathroom, dining, study, balcony, entrance, storage, kids, other;
    companion object { fun of(raw: String) = entries.firstOrNull { it.name == raw } ?: other }
}

@Serializable
data class Room(
    @Serializable(with = Uid::class) val id: String = newId(),
    val name: String = "房间",
    val type: String = RoomType.other.name,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val width: Double = 300.0,
    val depth: Double = 300.0,
    val sortOrder: Int = 0,
    @Serializable(with = Millis::class) val updatedAt: Long = now(),
) {
    val roomType get() = RoomType.of(type)
    val rect get() = Rect(x, y, width, depth)
}

@Serializable
data class Furniture(
    @Serializable(with = Uid::class) val id: String = newId(),
    @Serializable(with = Uid::class) val roomId: String,
    val kind: String = Catalog.CUSTOM,
    val name: String = "家具",
    val x: Double = 0.0,
    val y: Double = 0.0,
    val width: Double = 60.0,
    val depth: Double = 60.0,
    /** Quarter turns clockwise. */
    val rotation: Int = 0,
    val stackedOnId: String? = null,
    val colorHex: String? = null,
    val symbol: String? = null,
    val notes: String = "",
    val haEntities: List<String> = emptyList(),
    @Serializable(with = Millis::class) val createdAt: Long = now(),
    @Serializable(with = Millis::class) val updatedAt: Long = now(),
) {
    val rot get() = ((rotation % 4) + 4) % 4
    val footprintWidth get() = if (rot % 2 == 0) width else depth
    val footprintDepth get() = if (rot % 2 == 0) depth else width
    val rect get() = Rect(x, y, footprintWidth, footprintDepth)
    val centerX get() = x + footprintWidth / 2
    val centerY get() = y + footprintDepth / 2
    val parentId get() = stackedOnId?.uppercase()
}

@Serializable
data class Compartment(
    @Serializable(with = Uid::class) val id: String = newId(),
    @Serializable(with = Uid::class) val furnitureId: String,
    val name: String = "收纳位",
    val sortOrder: Int = 0,
    @Serializable(with = Millis::class) val updatedAt: Long = now(),
)

enum class CategoryTemplate(val title: String) {
    general("通用"),
    clothing("衣物（颜色、季节、穿着记录）"),
    perishable("消耗品（保质期、库存提醒）");
    companion object { fun of(raw: String) = entries.firstOrNull { it.name == raw } ?: general }
}

@Serializable
data class Category(
    @Serializable(with = Uid::class) val id: String = newId(),
    val name: String = "分类",
    val symbol: String = "square.grid.2x2",
    val colorHex: String = "#8E8E93",
    val template: String = CategoryTemplate.general.name,
    val sortOrder: Int = 0,
    @Serializable(with = Millis::class) val updatedAt: Long = now(),
) {
    val tmpl get() = CategoryTemplate.of(template)
}

@Serializable
data class Item(
    @Serializable(with = Uid::class) val id: String = newId(),
    /** null = not put away yet ("未归位"). */
    val compartmentId: String? = null,
    val name: String = "物品",
    val categoryId: String? = null,
    val quantity: Double = 1.0,
    val unit: String = "个",
    val icon: IconRef? = null,
    val photoId: String? = null,
    val originalPhotoId: String? = null,
    val colors: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val notes: String = "",
    val price: Double? = null,
    @Serializable(with = Millis::class) val purchasedAt: Long? = null,
    @Serializable(with = Millis::class) val expiresAt: Long? = null,
    val lowStock: Double? = null,
    val seasons: List<String> = emptyList(),
    val wearLog: List<@Serializable(with = Millis::class) Long> = emptyList(),
    val favorite: Boolean = false,
    @Serializable(with = Millis::class) val createdAt: Long = now(),
    @Serializable(with = Millis::class) val updatedAt: Long = now(),
) {
    val compartment get() = compartmentId?.uppercase()
    val category get() = categoryId?.uppercase()
    val isLowStock get() = lowStock != null && quantity <= lowStock
    val expiryDays: Int? get() = expiresAt?.let { Dates.daysFromToday(it) }
    val isExpired get() = (expiryDays ?: 1) < 0
    val isExpiringSoon get() = expiryDays?.let { it in 0..7 } ?: false
}

@Serializable
data class CustomIcon(
    @Serializable(with = Uid::class) val id: String = newId(),
    val name: String = "图标",
    val svg: String = "",
    @Serializable(with = Millis::class) val createdAt: Long = now(),
    @Serializable(with = Millis::class) val updatedAt: Long = now(),
)

/** Household-wide settings. The Home Assistant token is not among them: it stays on each device. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class HomeSettings(
    @Serializable(with = Uid::class) val id: String = SINGLETON_ID,
    val homeName: String = "我的家",
    val haURL: String = "",
    /** Only read: older versions synced the token. Always blank, so never written. */
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val haToken: String = "",
    @Serializable(with = Millis::class) val updatedAt: Long = 0,
) {
    companion object { const val SINGLETON_ID = "00000000-0000-0000-0000-00000000A001" }
}

object Entities {
    const val ROOM = "room"
    const val FURNITURE = "furniture"
    const val COMPARTMENT = "compartment"
    const val ITEM = "item"
    const val CATEGORY = "category"
    const val ICON = "icon"
    const val SETTINGS = "settings"
}

enum class DeletePolicy { KeepItems, DeleteItems }

/** Plain rectangle in centimetres. */
data class Rect(val x: Double, val y: Double, val w: Double, val h: Double) {
    val maxX get() = x + w
    val maxY get() = y + h
    val midX get() = x + w / 2
    val midY get() = y + h / 2
    fun contains(px: Double, py: Double) = px >= x && px <= maxX && py >= y && py <= maxY
    fun intersects(o: Rect) = x < o.maxX && o.x < maxX && y < o.maxY && o.y < maxY
    fun inset(d: Double) = Rect(x + d, y + d, w - 2 * d, h - 2 * d)
    fun union(o: Rect): Rect {
        val nx = minOf(x, o.x); val ny = minOf(y, o.y)
        return Rect(nx, ny, maxOf(maxX, o.maxX) - nx, maxOf(maxY, o.maxY) - ny)
    }
}

object Dates {
    private fun startOfDay(ms: Long): Long {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = ms
        c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun daysFromToday(ms: Long): Int =
        Math.round((startOfDay(ms) - startOfDay(now())) / 86_400_000.0).toInt()

    fun sameDay(a: Long, b: Long) = startOfDay(a) == startOfDay(b)

    fun format(ms: Long): String =
        java.text.SimpleDateFormat("yyyy年M月d日", java.util.Locale.CHINA).format(java.util.Date(ms))

    fun addMonths(ms: Long, months: Int): Long {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = ms
        c.add(java.util.Calendar.MONTH, months)
        return c.timeInMillis
    }
}

fun Double.clean(): String =
    if (this == Math.rint(this) && kotlin.math.abs(this) < 1e12) this.toLong().toString()
    else String.format(java.util.Locale.US, "%.2f", this).trimEnd('0').trimEnd('.')

fun Double.metres(): String = String.format(java.util.Locale.US, "%.1f", this / 100)

fun snap(v: Double, step: Double) = Math.round(v / step) * step
