package com.wanwu.nook.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.io.File

/** A change as it travels to and from the sync server. */
@Serializable
data class SyncChange(
    val entity: String,
    val id: String,
    @Serializable(with = Millis::class) val updatedAt: Long,
    val deleted: Boolean = false,
    val data: JsonElement? = null,
)

data class ItemLocation(val room: Room?, val furniture: Furniture, val compartment: Compartment)

/**
 * Everything the household owns, held in Compose state and persisted as one
 * JSON file. Mirrors ios/Nook/Store/HomeStore.swift. Call from the main thread.
 */
class HomeStore(private val dir: File, private val scope: CoroutineScope? = null) {
    val rooms = mutableStateMapOf<String, Room>()
    val furniture = mutableStateMapOf<String, Furniture>()
    val compartments = mutableStateMapOf<String, Compartment>()
    val items = mutableStateMapOf<String, Item>()
    val categories = mutableStateMapOf<String, Category>()
    val icons = mutableStateMapOf<String, CustomIcon>()
    var settings by mutableStateOf(HomeSettings())
        private set
    var didOnboard by mutableStateOf(false)
        private set
    var pendingCount by mutableIntStateOf(0)
        private set
    var revision by mutableIntStateOf(0)
        private set

    private val pending = HashMap<RecordKey, Int>()
    private val tombstones = HashMap<RecordKey, Long>()
    var cursor: Long = 0
        set(value) { field = value; scheduleSave() }
    private val uploads = LinkedHashSet<String>()
    val pendingUploads: Set<String> get() = uploads.toSet()
    var onLocalChange: (() -> Unit)? = null
    val files = FileStore(File(dir, "files"))
    private val file = File(dir, "store.json")
    private var saveJob: Job? = null
    private var lastTimestamp = 0L

    init {
        dir.mkdirs()
        load()
    }

    // region Persistence

    @Serializable private data class PendingEntry(val entity: String, val id: String, val version: Int)
    @Serializable private data class Tomb(val entity: String, val id: String, @Serializable(with = Millis::class) val deletedAt: Long)

    @Serializable
    private data class Snapshot(
        val rooms: List<Room> = emptyList(),
        val furniture: List<Furniture> = emptyList(),
        val compartments: List<Compartment> = emptyList(),
        val items: List<Item> = emptyList(),
        val categories: List<Category> = emptyList(),
        val icons: List<CustomIcon> = emptyList(),
        val settings: HomeSettings? = null,
        val pending: List<PendingEntry> = emptyList(),
        val tombstones: List<Tomb> = emptyList(),
        val cursor: Long = 0,
        val didOnboard: Boolean = false,
        val pendingUploads: List<String> = emptyList(),
    )

    private fun load() {
        if (!file.exists()) return
        val snap = runCatching { NookJson.decodeFromString(Snapshot.serializer(), file.readText()) }.getOrNull() ?: return
        snap.rooms.forEach { rooms[it.id] = it }
        snap.furniture.forEach { furniture[it.id] = it }
        snap.compartments.forEach { compartments[it.id] = it }
        snap.items.forEach { items[it.id] = it }
        snap.categories.forEach { categories[it.id] = it }
        snap.icons.forEach { icons[it.id] = it }
        settings = snap.settings ?: HomeSettings()
        snap.pending.forEach { pending[RecordKey(it.entity, it.id)] = it.version }
        snap.tombstones.forEach { tombstones[RecordKey(it.entity, it.id)] = it.deletedAt }
        cursor = snap.cursor
        didOnboard = snap.didOnboard
        uploads.addAll(snap.pendingUploads)
        pendingCount = pending.size
    }

    private fun snapshotJson(): String = NookJson.encodeToString(Snapshot.serializer(), Snapshot(
        rooms.values.toList(), furniture.values.toList(), compartments.values.toList(), items.values.toList(),
        categories.values.toList(), icons.values.toList(), settings,
        pending.map { PendingEntry(it.key.entity, it.key.id, it.value) },
        tombstones.map { Tomb(it.key.entity, it.key.id, it.value) },
        cursor, didOnboard, uploads.toList(),
    ))

    private fun scheduleSave() {
        val scope = scope ?: return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(500)
            val json = snapshotJson()
            withContext(Dispatchers.IO) { writeAtomically(json) }
        }
    }

    fun saveNow() {
        saveJob?.cancel()
        writeAtomically(snapshotJson())
    }

    private fun writeAtomically(json: String) {
        val tmp = File(dir, "store.json.tmp")
        tmp.writeText(json)
        tmp.renameTo(file)
    }

    // endregion
    // region Change tracking

    /** Strictly increasing, so two quick edits never tie. */
    private fun nextTimestamp(): Long {
        var t = now()
        if (t <= lastTimestamp) t = lastTimestamp + 1
        lastTimestamp = t
        return t
    }

    private fun didChange(key: RecordKey, deleted: Boolean = false) {
        pending[key] = (pending[key] ?: 0) + 1
        if (deleted) tombstones[key] = nextTimestamp() else tombstones.remove(key)
        pendingCount = pending.size
        revision++
        scheduleSave()
        onLocalChange?.invoke()
    }

    fun save(v: Room) { val r = v.copy(updatedAt = nextTimestamp()); rooms[r.id] = r; didChange(RecordKey(Entities.ROOM, r.id)) }
    fun save(v: Furniture) { val r = v.copy(updatedAt = nextTimestamp()); furniture[r.id] = r; didChange(RecordKey(Entities.FURNITURE, r.id)) }
    fun save(v: Compartment) { val r = v.copy(updatedAt = nextTimestamp()); compartments[r.id] = r; didChange(RecordKey(Entities.COMPARTMENT, r.id)) }
    fun save(v: Item) { val r = v.copy(updatedAt = nextTimestamp()); items[r.id] = r; didChange(RecordKey(Entities.ITEM, r.id)) }
    fun save(v: Category) { val r = v.copy(updatedAt = nextTimestamp()); categories[r.id] = r; didChange(RecordKey(Entities.CATEGORY, r.id)) }
    fun save(v: CustomIcon) { val r = v.copy(updatedAt = nextTimestamp()); icons[r.id] = r; didChange(RecordKey(Entities.ICON, r.id)) }

    fun saveSettings(s: HomeSettings) {
        settings = s.copy(id = HomeSettings.SINGLETON_ID, updatedAt = nextTimestamp())
        didChange(RecordKey(Entities.SETTINGS, HomeSettings.SINGLETON_ID))
    }

    private fun <T> remove(map: MutableMap<String, T>, entity: String, id: String) {
        if (map.remove(id) != null) didChange(RecordKey(entity, id), deleted = true)
    }

    fun markOnboarded() { didOnboard = true; scheduleSave() }

    fun registerUpload(fileId: String) { uploads.add(fileId); scheduleSave(); onLocalChange?.invoke() }

    fun markUploaded(fileId: String) { uploads.remove(fileId); scheduleSave() }

    // endregion
    // region Queries

    val sortedRooms: List<Room> get() = rooms.values.sortedWith(compareBy({ it.sortOrder }, { it.name }))
    val sortedCategories: List<Category> get() = categories.values.sortedWith(compareBy({ it.sortOrder }, { it.name }))
    val sortedIcons: List<CustomIcon> get() = icons.values.sortedByDescending { it.createdAt }
    val hasAnyData get() = rooms.isNotEmpty() || furniture.isNotEmpty() || items.isNotEmpty() || icons.isNotEmpty()

    /** Furniture in drawing order: floor layer first, stacked pieces above their base. */
    fun furnitureIn(roomId: String): List<Furniture> =
        furniture.values.filter { it.roomId == roomId }.sortedWith(
            compareBy<Furniture>({ Catalog.kind(it.kind).layer.ordinal }, { stackDepth(it.id) }, { it.createdAt })
        )

    fun compartmentsOf(furnitureId: String): List<Compartment> =
        compartments.values.filter { it.furnitureId == furnitureId }.sortedWith(compareBy({ it.sortOrder }, { it.name }))

    fun itemsIn(compartmentId: String): List<Item> =
        items.values.filter { it.compartment == compartmentId }.sortedByDescending { it.updatedAt }

    fun itemsInFurniture(furnitureId: String): List<Item> {
        val ids = compartmentsOf(furnitureId).map { it.id }.toSet()
        return items.values.filter { it.compartment in ids }
    }

    fun itemCount(furnitureId: String) = itemsInFurniture(furnitureId).size

    fun itemCountsByFurniture(): Map<String, Int> {
        val counts = HashMap<String, Int>()
        for (item in items.values) {
            val c = item.compartment?.let { compartments[it] } ?: continue
            counts[c.furnitureId] = (counts[c.furnitureId] ?: 0) + 1
        }
        return counts
    }

    fun itemCountsByRoom(): Map<String, Int> {
        val counts = HashMap<String, Int>()
        for ((fid, n) in itemCountsByFurniture()) {
            val rid = furniture[fid]?.roomId ?: continue
            counts[rid] = (counts[rid] ?: 0) + n
        }
        return counts
    }

    val unsortedItems: List<Item>
        get() = items.values.filter { it.compartment == null || compartments[it.compartment] == null }
            .sortedByDescending { it.updatedAt }

    fun location(item: Item): ItemLocation? {
        val c = item.compartment?.let { compartments[it] } ?: return null
        val f = furniture[c.furnitureId] ?: return null
        return ItemLocation(rooms[f.roomId], f, c)
    }

    fun locationText(item: Item): String {
        val loc = location(item) ?: return "未归位"
        return listOfNotNull(loc.room?.name, loc.furniture.name, loc.compartment.name).joinToString(" › ")
    }

    fun compartmentPath(compartmentId: String?): String {
        val c = compartmentId?.let { compartments[it] } ?: return "未归位"
        val f = furniture[c.furnitureId] ?: return "未归位"
        return listOfNotNull(rooms[f.roomId]?.name, f.name, c.name).joinToString(" › ")
    }

    fun categoryOf(item: Item): Category? = item.category?.let { categories[it] }

    fun childrenOf(furnitureId: String): List<Furniture> =
        furniture.values.filter { it.parentId == furnitureId }.sortedBy { it.createdAt }

    fun descendantsOf(furnitureId: String): List<Furniture> {
        val result = mutableListOf<Furniture>()
        val queue = ArrayDeque(childrenOf(furnitureId))
        val seen = mutableSetOf(furnitureId)
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!seen.add(next.id)) continue
            result += next
            queue.addAll(childrenOf(next.id))
        }
        return result
    }

    fun stackDepth(furnitureId: String): Int {
        var depth = 0
        var current = furniture[furnitureId]?.parentId
        val seen = mutableSetOf(furnitureId)
        while (current != null && furniture[current] != null && seen.add(current)) {
            depth++
            current = furniture[current]?.parentId
        }
        return depth
    }

    fun isDescendant(id: String, ancestor: String): Boolean {
        var current = furniture[id]?.parentId
        val seen = mutableSetOf(id)
        while (current != null && seen.add(current)) {
            if (current == ancestor) return true
            current = furniture[current]?.parentId
        }
        return false
    }

    // endregion
    // region Rooms

    fun addRoom(type: RoomType, name: String, width: Double? = null, depth: Double? = null, withPreset: Boolean = false): Room {
        val info = type.info
        val bounds = sortedRooms.map { it.rect }.reduceOrNull { a, b -> a.union(b) }
        val room = Room(name = name, type = type.name, x = bounds?.maxX ?: 0.0, y = bounds?.y ?: 0.0,
            width = width ?: info.width, depth = depth ?: info.depth,
            sortOrder = (rooms.values.maxOfOrNull { it.sortOrder } ?: -1) + 1)
        save(room)
        if (withPreset) applyPreset(room)
        return rooms[room.id]!!
    }

    private fun applyPreset(room: Room) {
        val created = mutableListOf<String>()
        for (piece in room.roomType.preset) {
            val kind = Catalog.kind(piece.kind)
            var f = Furniture(roomId = room.id, kind = kind.id, name = kind.name, x = piece.x, y = piece.y,
                width = kind.width, depth = kind.depth, rotation = piece.rotation,
                stackedOnId = piece.stackedOn?.let { created.getOrNull(it) })
            f = clampInside(f, room)
            save(f)
            created += f.id
            kind.compartments.forEachIndexed { i, n -> save(Compartment(furnitureId = f.id, name = n, sortOrder = i)) }
        }
    }

    /** First-run setup: rooms laid out in rows, optionally furnished. */
    fun buildHome(name: String, counts: List<Pair<RoomType, Int>>, withFurniture: Boolean) {
        saveSettings(settings.copy(homeName = name.trim().ifEmpty { "我的家" }))
        seedDefaultCategoriesIfNeeded()
        var x = 0.0; var y = 0.0; var rowDepth = 0.0
        var order = (rooms.values.maxOfOrNull { it.sortOrder } ?: -1) + 1
        for ((type, count) in counts) {
            for (n in 0 until count) {
                val info = type.info
                if (x > 0 && x + info.width > 1100) { x = 0.0; y += rowDepth; rowDepth = 0.0 }
                val room = Room(name = roomName(type, n, count), type = type.name, x = x, y = y,
                    width = info.width, depth = info.depth, sortOrder = order)
                save(room)
                if (withFurniture) applyPreset(room)
                order++
                x += info.width
                rowDepth = maxOf(rowDepth, info.depth)
            }
        }
        markOnboarded()
    }

    fun seedDefaultCategoriesIfNeeded() {
        if (categories.isEmpty()) DefaultCategories.all.forEach { save(it) }
    }

    fun moveRoom(id: String, x: Double, y: Double) {
        val r = rooms[id] ?: return
        save(r.copy(x = x, y = y))
    }

    fun resizeRoom(id: String, width: Double, depth: Double) {
        val r = rooms[id] ?: return
        val room = r.copy(width = maxOf(50.0, width), depth = maxOf(50.0, depth))
        save(room)
        for (f in furniture.values.filter { it.roomId == id }) {
            val c = clampInside(f, room)
            if (c.x != f.x || c.y != f.y) save(c)
        }
    }

    fun deleteRoom(id: String, policy: DeletePolicy) {
        furniture.values.filter { it.roomId == id }.forEach { deleteFurniture(it.id, policy) }
        remove(rooms, Entities.ROOM, id)
    }

    // endregion
    // region Furniture

    fun clampInside(f: Furniture, room: Room): Furniture = f.copy(
        x = f.x.coerceIn(0.0, maxOf(0.0, room.width - f.footprintWidth)),
        y = f.y.coerceIn(0.0, maxOf(0.0, room.depth - f.footprintDepth)),
    )

    fun freeSpot(room: Room, width: Double, depth: Double): Pair<Double, Double> {
        val others = furniture.values.filter { it.roomId == room.id && Catalog.kind(it.kind).layer != FurnitureLayer.floor }
            .map { it.rect.inset(1.0) }
        var y = 0.0
        while (y + depth <= room.depth) {
            var x = 0.0
            while (x + width <= room.width) {
                val r = Rect(x, y, width, depth)
                if (others.none { it.intersects(r) }) return x to y
                x += 10
            }
            y += 10
        }
        return maxOf(0.0, (room.width - width) / 2) to maxOf(0.0, (room.depth - depth) / 2)
    }

    fun addFurniture(kind: FurnitureKind, roomId: String, name: String? = null, width: Double? = null, depth: Double? = null,
                     colorHex: String? = null, compartmentNames: List<String>? = null): Furniture? {
        val room = rooms[roomId] ?: return null
        val w = width ?: kind.width; val d = depth ?: kind.depth
        val (sx, sy) = freeSpot(room, w, d)
        val f = clampInside(Furniture(roomId = roomId, kind = kind.id, name = name ?: kind.name, x = sx, y = sy,
            width = w, depth = d, colorHex = colorHex), room)
        save(f)
        (compartmentNames ?: kind.compartments).forEachIndexed { i, n -> save(Compartment(furnitureId = f.id, name = n, sortOrder = i)) }
        return furniture[f.id]
    }

    /** Moves a piece (and whatever stands on it) to a new top-left position. */
    fun moveFurniture(id: String, x: Double, y: Double) {
        val f = furniture[id] ?: return
        val room = rooms[f.roomId] ?: return
        val moved = clampInside(f.copy(x = x, y = y), room)
        val dx = moved.x - f.x; val dy = moved.y - f.y
        if (dx == 0.0 && dy == 0.0) return
        save(moved)
        for (child in descendantsOf(id)) save(clampInside(child.copy(x = child.x + dx, y = child.y + dy), room))
    }

    /** Quarter turn clockwise around the centre; stacked pieces turn with it. */
    fun rotateFurniture(id: String) {
        val f = furniture[id] ?: return
        val room = rooms[f.roomId] ?: return
        val cx = f.centerX; val cy = f.centerY
        var turned = f.copy(rotation = (f.rot + 1) % 4)
        turned = clampInside(turned.copy(x = cx - turned.footprintWidth / 2, y = cy - turned.footprintDepth / 2), room)
        save(turned)
        val ncx = turned.centerX; val ncy = turned.centerY
        for (child in descendantsOf(id)) {
            val dx = child.centerX - cx; val dy = child.centerY - cy
            val c = child.copy(rotation = (child.rot + 1) % 4)
            save(clampInside(c.copy(x = ncx - dy - c.footprintWidth / 2, y = ncy + dx - c.footprintDepth / 2), room))
        }
    }

    /** Sets the on-screen footprint; stored width/depth follow the rotation. */
    fun resizeFurniture(id: String, footprintWidth: Double, footprintDepth: Double) {
        val f = furniture[id] ?: return
        val room = rooms[f.roomId] ?: return
        val w = maxOf(5.0, footprintWidth); val d = maxOf(5.0, footprintDepth)
        val sized = if (f.rot % 2 == 0) f.copy(width = w, depth = d) else f.copy(width = d, depth = w)
        save(clampInside(sized, room))
    }

    fun setStack(id: String, parentId: String?) {
        val f = furniture[id] ?: return
        if (parentId == null) {
            if (f.stackedOnId != null) save(f.copy(stackedOnId = null))
            return
        }
        if (parentId == id || isDescendant(parentId, id)) return
        val parent = furniture[parentId] ?: return
        if (parent.roomId != f.roomId) return
        var s = f.copy(stackedOnId = parentId)
        val pr = parent.rect
        if (!pr.contains(f.centerX, f.centerY)) s = s.copy(x = pr.midX - f.footprintWidth / 2, y = pr.midY - f.footprintDepth / 2)
        rooms[f.roomId]?.let { s = clampInside(s, it) }
        save(s)
    }

    fun moveFurnitureToRoom(id: String, roomId: String) {
        val f = furniture[id] ?: return
        if (f.roomId == roomId) return
        val room = rooms[roomId] ?: return
        val kids = descendantsOf(id)
        val (sx, sy) = freeSpot(room, f.footprintWidth, f.footprintDepth)
        val moved = clampInside(f.copy(roomId = roomId, stackedOnId = null, x = sx, y = sy), room)
        save(moved)
        for (k in kids) save(clampInside(k.copy(roomId = roomId, x = k.x + moved.x - f.x, y = k.y + moved.y - f.y), room))
    }

    fun duplicateFurniture(id: String): Furniture? {
        val src = furniture[id] ?: return null
        val room = rooms[src.roomId] ?: return null
        val copy = clampInside(src.copy(id = newId(), createdAt = now(), haEntities = emptyList(), x = src.x + 20, y = src.y + 20), room)
        save(copy)
        compartmentsOf(id).forEach { save(Compartment(furnitureId = copy.id, name = it.name, sortOrder = it.sortOrder)) }
        return furniture[copy.id]
    }

    fun deleteFurniture(id: String, policy: DeletePolicy) {
        val parent = furniture[id]?.stackedOnId
        childrenOf(id).forEach { save(it.copy(stackedOnId = parent)) }
        compartments.values.filter { it.furnitureId == id }.forEach { deleteCompartment(it.id, policy) }
        remove(furniture, Entities.FURNITURE, id)
    }

    // endregion
    // region Compartments & items

    fun addCompartment(furnitureId: String, name: String): Compartment {
        val order = (compartmentsOf(furnitureId).maxOfOrNull { it.sortOrder } ?: -1) + 1
        val c = Compartment(furnitureId = furnitureId, name = name, sortOrder = order)
        save(c)
        return c
    }

    fun reorderCompartments(ordered: List<Compartment>) {
        ordered.forEachIndexed { i, c -> if (c.sortOrder != i) save(c.copy(sortOrder = i)) }
    }

    fun deleteCompartment(id: String, policy: DeletePolicy) {
        items.values.filter { it.compartment == id }.forEach {
            when (policy) {
                DeletePolicy.KeepItems -> save(it.copy(compartmentId = null))
                DeletePolicy.DeleteItems -> deleteItem(it.id)
            }
        }
        remove(compartments, Entities.COMPARTMENT, id)
    }

    fun moveItems(ids: Collection<String>, compartmentId: String?) {
        for (id in ids) {
            val item = items[id] ?: continue
            if (item.compartment != compartmentId) save(item.copy(compartmentId = compartmentId))
        }
    }

    fun setCategory(ids: Collection<String>, categoryId: String?) {
        for (id in ids) {
            val item = items[id] ?: continue
            if (item.category != categoryId) save(item.copy(categoryId = categoryId))
        }
    }

    fun adjustQuantity(id: String, delta: Double) {
        val item = items[id] ?: return
        save(item.copy(quantity = maxOf(0.0, item.quantity + delta)))
    }

    fun duplicateItem(id: String): Item? {
        val src = items[id] ?: return null
        val copy = src.copy(id = newId(), name = src.name + " 副本", createdAt = now(), wearLog = emptyList())
        save(copy)
        return items[copy.id]
    }

    fun deleteItem(id: String) {
        val item = items[id] ?: return
        remove(items, Entities.ITEM, id)
        listOfNotNull(item.photoId, item.originalPhotoId).forEach { deleteFileIfUnused(it) }
    }

    fun deleteItems(ids: Collection<String>) = ids.toList().forEach { deleteItem(it) }

    fun deleteFileIfUnused(fileId: String) {
        val used = items.values.any { it.photoId == fileId || it.originalPhotoId == fileId }
        if (!used) { files.delete(fileId); uploads.remove(fileId) }
    }

    fun cleanUpFiles() {
        val used = items.values.flatMap { listOfNotNull(it.photoId, it.originalPhotoId) }.toSet()
        files.allIds().filter { it !in used && it !in uploads }.forEach { files.delete(it) }
    }

    fun deleteCategory(id: String) {
        setCategory(items.values.filter { it.category == id }.map { it.id }, null)
        remove(categories, Entities.CATEGORY, id)
    }

    fun deleteIcon(id: String) {
        val ref = IconRef.custom(id)
        items.values.filter { it.icon?.kind == "custom" && it.icon.value.uppercase() == ref.value }.forEach { save(it.copy(icon = null)) }
        remove(icons, Entities.ICON, id)
    }

    // endregion
    // region Sync support

    data class Outgoing(val key: RecordKey, val version: Int, val change: SyncChange)

    fun outgoingChanges(limit: Int): List<Outgoing> {
        val out = mutableListOf<Outgoing>()
        for ((key, version) in pending.entries.toList()) {
            if (out.size >= limit) break
            val deletedAt = tombstones[key]
            if (deletedAt != null) {
                out += Outgoing(key, version, SyncChange(key.entity, key.id, deletedAt, true, null))
            } else {
                val rec = encodedRecord(key)
                if (rec != null) out += Outgoing(key, version, SyncChange(key.entity, key.id, rec.first, false, rec.second))
                else pending.remove(key)
            }
        }
        pendingCount = pending.size
        return out
    }

    fun acknowledge(sent: List<Outgoing>) {
        for (s in sent) if (pending[s.key] == s.version) { pending.remove(s.key); tombstones.remove(s.key) }
        pendingCount = pending.size
        scheduleSave()
    }

    private fun encodedRecord(key: RecordKey): Pair<Long, JsonElement>? = when (key.entity) {
        Entities.ROOM -> rooms[key.id]?.let { it.updatedAt to NookJson.encodeToJsonElement(Room.serializer(), it) }
        Entities.FURNITURE -> furniture[key.id]?.let { it.updatedAt to NookJson.encodeToJsonElement(Furniture.serializer(), it) }
        Entities.COMPARTMENT -> compartments[key.id]?.let { it.updatedAt to NookJson.encodeToJsonElement(Compartment.serializer(), it) }
        Entities.ITEM -> items[key.id]?.let { it.updatedAt to NookJson.encodeToJsonElement(Item.serializer(), it) }
        Entities.CATEGORY -> categories[key.id]?.let { it.updatedAt to NookJson.encodeToJsonElement(Category.serializer(), it) }
        Entities.ICON -> icons[key.id]?.let { it.updatedAt to NookJson.encodeToJsonElement(CustomIcon.serializer(), it) }
        Entities.SETTINGS -> settings.updatedAt to NookJson.encodeToJsonElement(HomeSettings.serializer(), settings)
        else -> null
    }

    private fun localUpdatedAt(key: RecordKey): Long? = tombstones[key] ?: encodedRecord(key)?.first

    /** Applies pulled records; newer unsent local edits win and go out next round. */
    fun applyRemote(changes: List<SyncChange>) {
        var changed = false
        for (ch in changes) {
            val key = RecordKey(ch.entity, ch.id.uppercase())
            if (pending.containsKey(key)) {
                val local = localUpdatedAt(key)
                if (local != null && local > ch.updatedAt) continue
                pending.remove(key)
            }
            tombstones.remove(key)
            if (ch.deleted) removeLocal(key) else ch.data?.let { insertLocal(key.entity, it) }
            if (ch.updatedAt > lastTimestamp) lastTimestamp = ch.updatedAt
            changed = true
        }
        if (changed) { pendingCount = pending.size; revision++; scheduleSave() }
    }

    private fun insertLocal(entity: String, data: JsonElement) {
        fun <T> dec(s: kotlinx.serialization.KSerializer<T>): T? = runCatching { NookJson.decodeFromJsonElement(s, data) }.getOrNull()
        when (entity) {
            Entities.ROOM -> dec(Room.serializer())?.let { rooms[it.id] = it }
            Entities.FURNITURE -> dec(Furniture.serializer())?.let { furniture[it.id] = it }
            Entities.COMPARTMENT -> dec(Compartment.serializer())?.let { compartments[it.id] = it }
            Entities.ITEM -> dec(Item.serializer())?.let { items[it.id] = it }
            Entities.CATEGORY -> dec(Category.serializer())?.let { categories[it.id] = it }
            Entities.ICON -> dec(CustomIcon.serializer())?.let { icons[it.id] = it }
            Entities.SETTINGS -> dec(HomeSettings.serializer())?.let { settings = it.copy(id = HomeSettings.SINGLETON_ID) }
        }
    }

    private fun removeLocal(key: RecordKey) {
        when (key.entity) {
            Entities.ROOM -> rooms.remove(key.id)
            Entities.FURNITURE -> furniture.remove(key.id)
            Entities.COMPARTMENT -> compartments.remove(key.id)
            Entities.ITEM -> items.remove(key.id)
            Entities.CATEGORY -> categories.remove(key.id)
            Entities.ICON -> icons.remove(key.id)
            Entities.SETTINGS -> settings = HomeSettings()
        }
    }

    /** After a full pull: fix references broken by concurrent edits on two devices. */
    fun repairOrphans() {
        furniture.values.filter { rooms[it.roomId] == null }.forEach { deleteFurniture(it.id, DeletePolicy.KeepItems) }
        furniture.values.filter { it.parentId != null && furniture[it.parentId!!] == null }.forEach { save(it.copy(stackedOnId = null)) }
        compartments.values.filter { furniture[it.furnitureId] == null }.forEach { deleteCompartment(it.id, DeletePolicy.KeepItems) }
        for (item in items.values.toList()) {
            var fixed = item
            if (item.compartment != null && compartments[item.compartment] == null) fixed = fixed.copy(compartmentId = null)
            if (item.category != null && categories[item.category] == null) fixed = fixed.copy(categoryId = null)
            if (fixed != item) save(fixed)
        }
        if (rooms.isNotEmpty() && !didOnboard) markOnboarded()
    }

    fun markAllPending() {
        val keys = rooms.keys.map { RecordKey(Entities.ROOM, it) } +
            furniture.keys.map { RecordKey(Entities.FURNITURE, it) } +
            compartments.keys.map { RecordKey(Entities.COMPARTMENT, it) } +
            items.keys.map { RecordKey(Entities.ITEM, it) } +
            categories.keys.map { RecordKey(Entities.CATEGORY, it) } +
            icons.keys.map { RecordKey(Entities.ICON, it) } +
            (if (settings.updatedAt > 0) listOf(RecordKey(Entities.SETTINGS, settings.id)) else emptyList())
        keys.forEach { pending[it] = (pending[it] ?: 0) + 1 }
        val used = items.values.flatMap { listOfNotNull(it.photoId, it.originalPhotoId) }
        uploads.addAll(used.filter { files.exists(it) })
        cursor = 0
        pendingCount = pending.size
        scheduleSave()
    }

    fun wipeLocal() {
        rooms.clear(); furniture.clear(); compartments.clear(); items.clear(); categories.clear(); icons.clear()
        settings = HomeSettings()
        pending.clear(); tombstones.clear(); uploads.clear()
        cursor = 0
        didOnboard = false
        pendingCount = 0
        revision++
        files.deleteAll()
        saveNow()
    }

    // endregion

    // region Backup

    /** Every live record as a sync change. The Home Assistant token is left out. */
    fun backupChanges(): List<SyncChange> {
        val out = mutableListOf<SyncChange>()
        rooms.values.forEach { out += SyncChange(Entities.ROOM, it.id, it.updatedAt, false, NookJson.encodeToJsonElement(Room.serializer(), it)) }
        furniture.values.forEach { out += SyncChange(Entities.FURNITURE, it.id, it.updatedAt, false, NookJson.encodeToJsonElement(Furniture.serializer(), it)) }
        compartments.values.forEach { out += SyncChange(Entities.COMPARTMENT, it.id, it.updatedAt, false, NookJson.encodeToJsonElement(Compartment.serializer(), it)) }
        categories.values.forEach { out += SyncChange(Entities.CATEGORY, it.id, it.updatedAt, false, NookJson.encodeToJsonElement(Category.serializer(), it)) }
        icons.values.forEach { out += SyncChange(Entities.ICON, it.id, it.updatedAt, false, NookJson.encodeToJsonElement(CustomIcon.serializer(), it)) }
        items.values.forEach { out += SyncChange(Entities.ITEM, it.id, it.updatedAt, false, NookJson.encodeToJsonElement(Item.serializer(), it)) }
        if (settings.updatedAt > 0) {
            val s = settings.copy(haToken = "")
            out += SyncChange(Entities.SETTINGS, s.id, s.updatedAt, false, NookJson.encodeToJsonElement(HomeSettings.serializer(), s))
        }
        return out
    }

    data class ImportSummary(var rooms: Int = 0, var furniture: Int = 0, var items: Int = 0, var photos: Int = 0, var skipped: Int = 0)

    /**
     * Restores a backup. Merge keeps whichever side is newer per record;
     * replace makes this device match the backup (and wins on the server too).
     * Imported records are queued for upload when signed in.
     */
    fun importBackup(changes: List<SyncChange>, replace: Boolean): ImportSummary {
        val summary = ImportSummary()
        val incoming = changes.map { RecordKey(it.entity, it.id.uppercase()) }.toSet()
        if (replace) {
            fun <T> prune(map: MutableMap<String, T>, entity: String) =
                map.keys.toList().filter { RecordKey(entity, it) !in incoming }.forEach { remove(map, entity, it) }
            prune(items, Entities.ITEM); prune(compartments, Entities.COMPARTMENT); prune(furniture, Entities.FURNITURE)
            prune(rooms, Entities.ROOM); prune(icons, Entities.ICON); prune(categories, Entities.CATEGORY)
        }
        val keptHA = settings.haURL to settings.haToken
        for (ch in changes) {
            if (ch.deleted) continue
            val data = ch.data ?: continue
            val key = RecordKey(ch.entity, ch.id.uppercase())
            if (!replace) {
                val local = localUpdatedAt(key)
                if (local != null && local >= ch.updatedAt) { summary.skipped++; continue }
            }
            insertLocal(ch.entity, data)
            if (replace) restamp(key)
            pending[key] = (pending[key] ?: 0) + 1
            tombstones.remove(key)
            when (ch.entity) {
                Entities.ROOM -> summary.rooms++
                Entities.FURNITURE -> summary.furniture++
                Entities.ITEM -> summary.items++
            }
        }
        if (settings.haToken.isEmpty() && keptHA.second.isNotEmpty()) settings = settings.copy(haURL = keptHA.first, haToken = keptHA.second)
        if (rooms.isNotEmpty()) didOnboard = true
        pendingCount = pending.size
        revision++
        repairOrphans()
        scheduleSave()
        onLocalChange?.invoke()
        return summary
    }

    /** Gives a just-restored record a fresh timestamp so it beats older copies. */
    private fun restamp(key: RecordKey) {
        val t = nextTimestamp()
        when (key.entity) {
            Entities.ROOM -> rooms[key.id]?.let { rooms[key.id] = it.copy(updatedAt = t) }
            Entities.FURNITURE -> furniture[key.id]?.let { furniture[key.id] = it.copy(updatedAt = t) }
            Entities.COMPARTMENT -> compartments[key.id]?.let { compartments[key.id] = it.copy(updatedAt = t) }
            Entities.ITEM -> items[key.id]?.let { items[key.id] = it.copy(updatedAt = t) }
            Entities.CATEGORY -> categories[key.id]?.let { categories[key.id] = it.copy(updatedAt = t) }
            Entities.ICON -> icons[key.id]?.let { icons[key.id] = it.copy(updatedAt = t) }
            Entities.SETTINGS -> settings = settings.copy(updatedAt = t)
        }
    }

    // endregion

    @Serializable
    data class Export(
        val exportedAt: Long, val homeName: String, val rooms: List<Room>, val furniture: List<Furniture>,
        val compartments: List<Compartment>, val items: List<Item>, val categories: List<Category>, val icons: List<CustomIcon>,
    )

    /** JSON dump of the household (without the Home Assistant token). */
    fun exportJson(): String = kotlinx.serialization.json.Json { prettyPrint = true; encodeDefaults = true; explicitNulls = false }
        .encodeToString(Export.serializer(), Export(now(), settings.homeName, sortedRooms, furniture.values.toList(),
            compartments.values.toList(), items.values.toList(), sortedCategories, sortedIcons))

    companion object {
        fun roomName(type: RoomType, index: Int, count: Int): String {
            val base = type.info.title
            if (count <= 1) return base
            return when (type) {
                RoomType.bedroom -> listOf("主卧", "次卧", "客卧").getOrElse(index) { "卧室 ${index + 1}" }
                RoomType.bathroom -> listOf("公卫", "主卫").getOrElse(index) { "卫生间 ${index + 1}" }
                else -> if (index == 0) base else "$base ${index + 1}"
            }
        }
    }
}
