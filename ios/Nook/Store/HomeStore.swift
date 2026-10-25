import Foundation
import Observation
import CoreGraphics

/// A change as it travels to and from the sync server.
struct SyncChange: Codable, Hashable {
    var entity: String
    var id: String
    var updatedAt: Int64
    var deleted: Bool
    var data: JSONValue?
}

struct ItemLocation {
    var room: Room?
    var furniture: Furniture
    var compartment: Compartment
}

/// Everything the household owns, kept in memory and persisted as one
/// JSON file. Every mutation is recorded so the sync engine can push it.
@MainActor
@Observable
final class HomeStore {
    private(set) var rooms: [UUID: Room] = [:]
    private(set) var furniture: [UUID: Furniture] = [:]
    private(set) var compartments: [UUID: Compartment] = [:]
    private(set) var items: [UUID: Item] = [:]
    private(set) var categories: [UUID: Category] = [:]
    private(set) var icons: [UUID: CustomIcon] = [:]
    private(set) var settings = HomeSettings()
    private(set) var didOnboard = false
    private(set) var pendingCount = 0
    /// Bumped on every change, local or remote.
    private(set) var revision = 0

    @ObservationIgnored private(set) var pending: [RecordKey: Int] = [:]
    @ObservationIgnored private(set) var tombstones: [RecordKey: Date] = [:]
    @ObservationIgnored var cursor: Int64 = 0 { didSet { scheduleSave() } }
    @ObservationIgnored private(set) var pendingUploads: Set<String> = []
    /// Called after every local edit (the sync engine debounces on it).
    @ObservationIgnored var onLocalChange: (() -> Void)?
    @ObservationIgnored let files: FileStore
    @ObservationIgnored private let fileURL: URL
    @ObservationIgnored private var saveTask: Task<Void, Never>?
    @ObservationIgnored private var lastTimestamp = Date(timeIntervalSince1970: 0)

    init(directory: URL? = nil) {
        let base = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Nook", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        fileURL = base.appendingPathComponent("store.json")
        files = FileStore(directory: base.appendingPathComponent("files", isDirectory: true))
        load()
    }

    // MARK: Persistence

    private struct Snapshot: Codable {
        struct Pending: Codable { var key: RecordKey; var version: Int }
        struct Tomb: Codable { var key: RecordKey; var deletedAt: Date }
        var rooms: [Room] = []
        var furniture: [Furniture] = []
        var compartments: [Compartment] = []
        var items: [Item] = []
        var categories: [Category] = []
        var icons: [CustomIcon] = []
        var settings: HomeSettings?
        var pending: [Pending] = []
        var tombstones: [Tomb] = []
        var cursor: Int64 = 0
        var didOnboard = false
        var pendingUploads: [String] = []

        init() {}

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            rooms = c.value(.rooms, [])
            furniture = c.value(.furniture, [])
            compartments = c.value(.compartments, [])
            items = c.value(.items, [])
            categories = c.value(.categories, [])
            icons = c.value(.icons, [])
            settings = c.optional(.settings)
            pending = c.value(.pending, [])
            tombstones = c.value(.tombstones, [])
            cursor = c.value(.cursor, 0)
            didOnboard = c.value(.didOnboard, false)
            pendingUploads = c.value(.pendingUploads, [])
        }
    }

    private func load() {
        guard let data = try? Data(contentsOf: fileURL),
              let snap = try? JSONCoding.decoder.decode(Snapshot.self, from: data) else { return }
        rooms = Dictionary(snap.rooms.map { ($0.id, $0) }, uniquingKeysWith: { $1 })
        furniture = Dictionary(snap.furniture.map { ($0.id, $0) }, uniquingKeysWith: { $1 })
        compartments = Dictionary(snap.compartments.map { ($0.id, $0) }, uniquingKeysWith: { $1 })
        items = Dictionary(snap.items.map { ($0.id, $0) }, uniquingKeysWith: { $1 })
        categories = Dictionary(snap.categories.map { ($0.id, $0) }, uniquingKeysWith: { $1 })
        icons = Dictionary(snap.icons.map { ($0.id, $0) }, uniquingKeysWith: { $1 })
        settings = snap.settings ?? HomeSettings()
        pending = Dictionary(snap.pending.map { ($0.key, $0.version) }, uniquingKeysWith: { $1 })
        tombstones = Dictionary(snap.tombstones.map { ($0.key, $0.deletedAt) }, uniquingKeysWith: { $1 })
        cursor = snap.cursor
        didOnboard = snap.didOnboard
        pendingUploads = Set(snap.pendingUploads)
        pendingCount = pending.count
    }

    private func snapshot() -> Snapshot {
        var s = Snapshot()
        s.rooms = Array(rooms.values)
        s.furniture = Array(furniture.values)
        s.compartments = Array(compartments.values)
        s.items = Array(items.values)
        s.categories = Array(categories.values)
        s.icons = Array(icons.values)
        s.settings = settings
        s.pending = pending.map { .init(key: $0.key, version: $0.value) }
        s.tombstones = tombstones.map { .init(key: $0.key, deletedAt: $0.value) }
        s.cursor = cursor
        s.didOnboard = didOnboard
        s.pendingUploads = Array(pendingUploads)
        return s
    }

    private func scheduleSave() {
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(500))
            guard !Task.isCancelled else { return }
            self?.saveNow()
        }
    }

    func saveNow() {
        saveTask?.cancel()
        do {
            let data = try JSONCoding.encoder.encode(snapshot())
            try data.write(to: fileURL, options: .atomic)
        } catch {
            print("HomeStore save failed: \(error)")
        }
    }

    // MARK: Change tracking

    /// Strictly increasing timestamps, so two quick edits never tie.
    private func nextTimestamp() -> Date {
        var now = Date()
        if now <= lastTimestamp { now = lastTimestamp.addingTimeInterval(0.001) }
        lastTimestamp = now
        return now
    }

    private func didChange(_ key: RecordKey, deleted: Bool = false, at date: Date? = nil) {
        pending[key, default: 0] += 1
        if deleted { tombstones[key] = date ?? nextTimestamp() } else { tombstones[key] = nil }
        pendingCount = pending.count
        revision += 1
        scheduleSave()
        onLocalChange?()
    }

    private func put<T: Entity>(_ value: T, into kp: ReferenceWritableKeyPath<HomeStore, [UUID: T]>) {
        var v = value
        v.updatedAt = nextTimestamp()
        self[keyPath: kp][v.id] = v
        didChange(RecordKey(entity: T.entityName, id: v.id))
    }

    private func remove<T: Entity>(_ id: UUID, from kp: ReferenceWritableKeyPath<HomeStore, [UUID: T]>) {
        guard self[keyPath: kp].removeValue(forKey: id) != nil else { return }
        didChange(RecordKey(entity: T.entityName, id: id), deleted: true)
    }

    // MARK: Saving

    func save(_ v: Room) { put(v, into: \.rooms) }
    func save(_ v: Furniture) { put(v, into: \.furniture) }
    func save(_ v: Compartment) { put(v, into: \.compartments) }
    func save(_ v: Item) { put(v, into: \.items) }
    func save(_ v: Category) { put(v, into: \.categories) }
    func save(_ v: CustomIcon) { put(v, into: \.icons) }

    func saveSettings(_ s: HomeSettings) {
        var s = s
        s.id = HomeSettings.singletonId
        s.updatedAt = nextTimestamp()
        settings = s
        didChange(RecordKey(entity: HomeSettings.entityName, id: s.id))
    }

    func markOnboarded() {
        didOnboard = true
        scheduleSave()
    }

    func registerUpload(_ fileId: String) {
        pendingUploads.insert(fileId)
        scheduleSave()
        onLocalChange?()
    }

    func markUploaded(_ fileId: String) {
        pendingUploads.remove(fileId)
        scheduleSave()
    }

    // MARK: Queries

    var sortedRooms: [Room] {
        rooms.values.sorted { ($0.sortOrder, $0.name) < ($1.sortOrder, $1.name) }
    }

    var sortedCategories: [Category] {
        categories.values.sorted { ($0.sortOrder, $0.name) < ($1.sortOrder, $1.name) }
    }

    var sortedIcons: [CustomIcon] {
        icons.values.sorted { $0.createdAt > $1.createdAt }
    }

    var hasAnyData: Bool {
        !rooms.isEmpty || !furniture.isEmpty || !items.isEmpty || !icons.isEmpty
    }

    /// Furniture in a room, in drawing order (floor layer first, stacked pieces
    /// above what they stand on).
    func furniture(in roomId: UUID) -> [Furniture] {
        furniture.values.filter { $0.roomId == roomId }.sorted { a, b in
            let la = Catalog.kind(a.kind).layer, lb = Catalog.kind(b.kind).layer
            if la != lb { return la < lb }
            let da = stackDepth(a.id), db = stackDepth(b.id)
            if da != db { return da < db }
            return a.createdAt < b.createdAt
        }
    }

    func compartments(of furnitureId: UUID) -> [Compartment] {
        compartments.values.filter { $0.furnitureId == furnitureId }
            .sorted { ($0.sortOrder, $0.name) < ($1.sortOrder, $1.name) }
    }

    func items(in compartmentId: UUID) -> [Item] {
        items.values.filter { $0.compartmentId == compartmentId }.sorted { $0.updatedAt > $1.updatedAt }
    }

    func items(inFurniture furnitureId: UUID) -> [Item] {
        let ids = Set(compartments(of: furnitureId).map(\.id))
        return items.values.filter { $0.compartmentId.map(ids.contains) ?? false }
    }

    func itemCount(furniture furnitureId: UUID) -> Int { items(inFurniture: furnitureId).count }

    func itemCount(room roomId: UUID) -> Int {
        let fids = Set(furniture.values.filter { $0.roomId == roomId }.map(\.id))
        let cids = Set(compartments.values.filter { fids.contains($0.furnitureId) }.map(\.id))
        return items.values.filter { $0.compartmentId.map(cids.contains) ?? false }.count
    }

    /// Item counts for every piece of furniture, computed in one pass.
    func itemCountsByFurniture() -> [UUID: Int] {
        var counts: [UUID: Int] = [:]
        for item in items.values {
            guard let cid = item.compartmentId, let c = compartments[cid] else { continue }
            counts[c.furnitureId, default: 0] += 1
        }
        return counts
    }

    func itemCountsByRoom() -> [UUID: Int] {
        var counts: [UUID: Int] = [:]
        for (fid, n) in itemCountsByFurniture() {
            if let rid = furniture[fid]?.roomId { counts[rid, default: 0] += n }
        }
        return counts
    }

    /// Items not put away, including those whose compartment disappeared.
    var unsortedItems: [Item] {
        items.values.filter { item in
            guard let cid = item.compartmentId else { return true }
            return compartments[cid] == nil
        }.sorted { $0.updatedAt > $1.updatedAt }
    }

    func location(of item: Item) -> ItemLocation? {
        guard let cid = item.compartmentId, let c = compartments[cid], let f = furniture[c.furnitureId] else { return nil }
        return ItemLocation(room: rooms[f.roomId], furniture: f, compartment: c)
    }

    func locationText(_ item: Item) -> String {
        guard let loc = location(of: item) else { return "未归位" }
        return [loc.room?.name, loc.furniture.name, loc.compartment.name].compactMap { $0 }.joined(separator: " › ")
    }

    func compartmentPath(_ compartmentId: UUID?) -> String {
        guard let cid = compartmentId, let c = compartments[cid], let f = furniture[c.furnitureId] else { return "未归位" }
        return [rooms[f.roomId]?.name, f.name, c.name].compactMap { $0 }.joined(separator: " › ")
    }

    func category(of item: Item) -> Category? { item.categoryId.flatMap { categories[$0] } }

    func children(of furnitureId: UUID) -> [Furniture] {
        furniture.values.filter { $0.stackedOnId == furnitureId }.sorted { $0.createdAt < $1.createdAt }
    }

    func descendants(of furnitureId: UUID) -> [Furniture] {
        var result: [Furniture] = []
        var queue = children(of: furnitureId)
        var seen: Set<UUID> = [furnitureId]
        while let next = queue.first {
            queue.removeFirst()
            guard seen.insert(next.id).inserted else { continue }
            result.append(next)
            queue += children(of: next.id)
        }
        return result
    }

    func stackDepth(_ furnitureId: UUID) -> Int {
        var depth = 0
        var current = furniture[furnitureId]?.stackedOnId
        var seen: Set<UUID> = [furnitureId]
        while let id = current, let parent = furniture[id], seen.insert(id).inserted {
            depth += 1
            current = parent.stackedOnId
        }
        return depth
    }

    func isDescendant(_ id: UUID, of ancestor: UUID) -> Bool {
        var current = furniture[id]?.stackedOnId
        var seen: Set<UUID> = [id]
        while let c = current, seen.insert(c).inserted {
            if c == ancestor { return true }
            current = furniture[c]?.stackedOnId
        }
        return false
    }

    // MARK: Rooms

    @discardableResult
    func addRoom(type: RoomType, name: String, width: Double? = nil, depth: Double? = nil,
                 withPreset: Bool = false) -> Room {
        let info = type.info
        let w = width ?? info.width, d = depth ?? info.depth
        let bounds = sortedRooms.reduce(CGRect.null) { $0.union($1.rect) }
        let x = bounds.isNull ? 0 : bounds.maxX
        let y = bounds.isNull ? 0 : bounds.minY
        let room = Room(name: name, type: type, x: x, y: y, width: w, depth: d,
                        sortOrder: (rooms.values.map(\.sortOrder).max() ?? -1) + 1)
        save(room)
        if withPreset { applyPreset(to: room) }
        return room
    }

    private func applyPreset(to room: Room) {
        var created: [UUID] = []
        for piece in room.type.preset {
            let kind = Catalog.kind(piece.kind)
            var f = Furniture(roomId: room.id, kind: kind.id, name: kind.name, x: piece.x, y: piece.y,
                              width: kind.width, depth: kind.depth, rotation: piece.rotation)
            if let idx = piece.stackedOn, created.indices.contains(idx) { f.stackedOnId = created[idx] }
            clampInside(&f, room: room)
            save(f)
            created.append(f.id)
            for (i, name) in kind.compartments.enumerated() {
                save(Compartment(furnitureId: f.id, name: name, sortOrder: i))
            }
        }
    }

    /// First-run setup: rooms laid out in rows, optionally furnished.
    func buildHome(name: String, rooms counts: [(RoomType, Int)], withFurniture: Bool) {
        var s = settings
        s.homeName = name.trimmed.isEmpty ? "我的家" : name.trimmed
        saveSettings(s)
        seedDefaultCategoriesIfNeeded()

        var x = 0.0, y = 0.0, rowDepth = 0.0
        var order = (self.rooms.values.map(\.sortOrder).max() ?? -1) + 1
        let maxRowWidth = 1100.0
        for (type, count) in counts where count > 0 {
            for n in 0..<count {
                let info = type.info
                if x > 0 && x + info.width > maxRowWidth {
                    x = 0; y += rowDepth; rowDepth = 0
                }
                let room = Room(name: Self.roomName(type, index: n, count: count), type: type,
                                x: x, y: y, width: info.width, depth: info.depth, sortOrder: order)
                save(room)
                if withFurniture { applyPreset(to: room) }
                order += 1
                x += info.width
                rowDepth = max(rowDepth, info.depth)
            }
        }
        markOnboarded()
    }

    static func roomName(_ type: RoomType, index: Int, count: Int) -> String {
        let base = type.info.title
        if count <= 1 { return base }
        switch type {
        case .bedroom:
            let names = ["主卧", "次卧", "客卧"]
            return index < names.count ? names[index] : "卧室 \(index + 1)"
        case .bathroom:
            let names = ["公卫", "主卫"]
            return index < names.count ? names[index] : "卫生间 \(index + 1)"
        default:
            return index == 0 ? base : "\(base) \(index + 1)"
        }
    }

    func seedDefaultCategoriesIfNeeded() {
        guard categories.isEmpty else { return }
        for c in DefaultCategories.all { save(c) }
    }

    func moveRoom(_ id: UUID, to point: CGPoint) {
        guard var room = rooms[id] else { return }
        room.x = point.x
        room.y = point.y
        save(room)
    }

    /// Resizes a room and pulls any furniture that would end up outside back in.
    func resizeRoom(_ id: UUID, width: Double, depth: Double) {
        guard var room = rooms[id] else { return }
        room.width = max(50, width)
        room.depth = max(50, depth)
        save(room)
        for var f in furniture.values where f.roomId == id {
            let before = f
            clampInside(&f, room: room)
            if f.x != before.x || f.y != before.y { save(f) }
        }
    }

    func deleteRoom(_ id: UUID, policy: DeletePolicy) {
        for f in furniture.values where f.roomId == id {
            deleteFurniture(f.id, policy: policy)
        }
        remove(id, from: \.rooms)
    }

    // MARK: Furniture

    func clampInside(_ f: inout Furniture, room: Room) {
        f.x = clamp(f.x, 0, max(0, room.width - f.footprintWidth))
        f.y = clamp(f.y, 0, max(0, room.depth - f.footprintDepth))
    }

    /// Scans the room for a spot where a piece of this size overlaps nothing.
    func freeSpot(in room: Room, width: Double, depth: Double) -> CGPoint {
        let others = furniture.values.filter { $0.roomId == room.id && Catalog.kind($0.kind).layer != .floor }
            .map { $0.rect.insetBy(dx: 1, dy: 1) }
        let step = 10.0
        var y = 0.0
        while y + depth <= room.depth {
            var x = 0.0
            while x + width <= room.width {
                let r = CGRect(x: x, y: y, width: width, height: depth)
                if !others.contains(where: { $0.intersects(r) }) { return CGPoint(x: x, y: y) }
                x += step
            }
            y += step
        }
        return CGPoint(x: max(0, (room.width - width) / 2), y: max(0, (room.depth - depth) / 2))
    }

    @discardableResult
    func addFurniture(kind: FurnitureKind, to roomId: UUID, name: String? = nil, width: Double? = nil,
                      depth: Double? = nil, colorHex: String? = nil, symbol: String? = nil,
                      compartmentNames: [String]? = nil) -> Furniture? {
        guard let room = rooms[roomId] else { return nil }
        let w = width ?? kind.width, d = depth ?? kind.depth
        let spot = freeSpot(in: room, width: w, depth: d)
        var f = Furniture(roomId: roomId, kind: kind.id, name: name ?? kind.name, x: spot.x, y: spot.y,
                          width: w, depth: d)
        f.colorHex = colorHex
        f.symbol = symbol
        clampInside(&f, room: room)
        save(f)
        for (i, n) in (compartmentNames ?? kind.compartments).enumerated() {
            save(Compartment(furnitureId: f.id, name: n, sortOrder: i))
        }
        return f
    }

    /// Moves a piece (and whatever stands on it) to a new top-left position.
    func moveFurniture(_ id: UUID, to point: CGPoint) {
        guard var f = furniture[id], let room = rooms[f.roomId] else { return }
        let old = CGPoint(x: f.x, y: f.y)
        f.x = point.x
        f.y = point.y
        clampInside(&f, room: room)
        let dx = f.x - old.x, dy = f.y - old.y
        guard dx != 0 || dy != 0 else { return }
        save(f)
        for var child in descendants(of: id) {
            child.x += dx
            child.y += dy
            clampInside(&child, room: room)
            save(child)
        }
    }

    /// Quarter turn clockwise around the centre; stacked pieces turn with it.
    func rotateFurniture(_ id: UUID) {
        guard var f = furniture[id], let room = rooms[f.roomId] else { return }
        let c = f.center
        f.rotation = (f.rotation + 1) % 4
        f.x = c.x - f.footprintWidth / 2
        f.y = c.y - f.footprintDepth / 2
        clampInside(&f, room: room)
        let newCenter = f.center
        save(f)
        for var child in descendants(of: id) {
            let cc = child.center
            let dx = cc.x - c.x, dy = cc.y - c.y
            child.rotation = (child.rotation + 1) % 4
            let rotated = CGPoint(x: newCenter.x - dy, y: newCenter.y + dx)
            child.x = rotated.x - child.footprintWidth / 2
            child.y = rotated.y - child.footprintDepth / 2
            clampInside(&child, room: room)
            save(child)
        }
    }

    /// Sets the on-screen footprint; stored width/depth follow the rotation.
    func resizeFurniture(_ id: UUID, footprintWidth fw: Double, footprintDepth fd: Double) {
        guard var f = furniture[id], let room = rooms[f.roomId] else { return }
        let w = max(5, fw), d = max(5, fd)
        if f.rotation % 2 == 0 { f.width = w; f.depth = d } else { f.width = d; f.depth = w }
        clampInside(&f, room: room)
        save(f)
    }

    /// Puts `id` on top of `parentId` (or back on the floor when nil).
    func setStack(_ id: UUID, on parentId: UUID?) {
        guard var f = furniture[id] else { return }
        guard let parentId else {
            if f.stackedOnId != nil { f.stackedOnId = nil; save(f) }
            return
        }
        guard parentId != id, !isDescendant(parentId, of: id), let parent = furniture[parentId],
              parent.roomId == f.roomId else { return }
        f.stackedOnId = parentId
        let pr = parent.rect
        if !pr.contains(f.center) {
            f.x = pr.midX - f.footprintWidth / 2
            f.y = pr.midY - f.footprintDepth / 2
        }
        if let room = rooms[f.roomId] { clampInside(&f, room: room) }
        save(f)
    }

    /// Moves a piece and everything on it into another room.
    func moveFurniture(_ id: UUID, toRoom roomId: UUID) {
        guard var f = furniture[id], f.roomId != roomId, let room = rooms[roomId] else { return }
        let old = CGPoint(x: f.x, y: f.y)
        let spot = freeSpot(in: room, width: f.footprintWidth, depth: f.footprintDepth)
        let kids = descendants(of: id)
        f.roomId = roomId
        f.stackedOnId = nil
        f.x = spot.x; f.y = spot.y
        clampInside(&f, room: room)
        save(f)
        for var k in kids {
            k.roomId = roomId
            k.x += f.x - old.x
            k.y += f.y - old.y
            clampInside(&k, room: room)
            save(k)
        }
    }

    @discardableResult
    func duplicateFurniture(_ id: UUID) -> Furniture? {
        guard let src = furniture[id], let room = rooms[src.roomId] else { return nil }
        var copy = src
        copy.id = UUID()
        copy.name = src.name
        copy.createdAt = Date()
        copy.haEntities = []
        copy.x += 20; copy.y += 20
        clampInside(&copy, room: room)
        save(copy)
        for c in compartments(of: id) {
            save(Compartment(furnitureId: copy.id, name: c.name, sortOrder: c.sortOrder))
        }
        return copy
    }

    func deleteFurniture(_ id: UUID, policy: DeletePolicy) {
        for var child in children(of: id) {
            child.stackedOnId = furniture[id]?.stackedOnId
            save(child)
        }
        for c in compartments.values where c.furnitureId == id {
            deleteCompartment(c.id, policy: policy)
        }
        remove(id, from: \.furniture)
    }

    // MARK: Compartments

    @discardableResult
    func addCompartment(to furnitureId: UUID, name: String) -> Compartment {
        let order = (compartments(of: furnitureId).map(\.sortOrder).max() ?? -1) + 1
        let c = Compartment(furnitureId: furnitureId, name: name, sortOrder: order)
        save(c)
        return c
    }

    func reorderCompartments(_ ordered: [Compartment]) {
        for (i, var c) in ordered.enumerated() where c.sortOrder != i {
            c.sortOrder = i
            save(c)
        }
    }

    func deleteCompartment(_ id: UUID, policy: DeletePolicy) {
        for item in items.values where item.compartmentId == id {
            switch policy {
            case .keepItems:
                var i = item
                i.compartmentId = nil
                save(i)
            case .deleteItems:
                deleteItem(item.id)
            }
        }
        remove(id, from: \.compartments)
    }

    // MARK: Items

    func moveItems(_ ids: [UUID], to compartmentId: UUID?) {
        for id in ids {
            guard var item = items[id], item.compartmentId != compartmentId else { continue }
            item.compartmentId = compartmentId
            save(item)
        }
    }

    func setCategory(_ ids: [UUID], to categoryId: UUID?) {
        for id in ids {
            guard var item = items[id], item.categoryId != categoryId else { continue }
            item.categoryId = categoryId
            save(item)
        }
    }

    func adjustQuantity(_ id: UUID, by delta: Double) {
        guard var item = items[id] else { return }
        item.quantity = max(0, item.quantity + delta)
        save(item)
    }

    @discardableResult
    func duplicateItem(_ id: UUID) -> Item? {
        guard let src = items[id] else { return nil }
        var copy = src
        copy.id = UUID()
        copy.name = src.name + " 副本"
        copy.createdAt = Date()
        copy.wearLog = []
        save(copy)
        return copy
    }

    func deleteItem(_ id: UUID) {
        guard let item = items[id] else { return }
        remove(id, from: \.items)
        for fileId in [item.photoId, item.originalPhotoId].compactMap({ $0 }) {
            deleteFileIfUnused(fileId)
        }
    }

    func deleteItems(_ ids: [UUID]) { ids.forEach(deleteItem) }

    func deleteFileIfUnused(_ fileId: String) {
        let used = items.values.contains { $0.photoId == fileId || $0.originalPhotoId == fileId }
        if !used {
            files.delete(fileId)
            pendingUploads.remove(fileId)
        }
    }

    /// Removes cached photo files no item points at any more.
    func cleanUpFiles() {
        let used = Set(items.values.flatMap { [$0.photoId, $0.originalPhotoId].compactMap { $0 } })
        for id in files.allIds() where !used.contains(id) && !pendingUploads.contains(id) {
            files.delete(id)
        }
    }

    // MARK: Categories & icons

    func deleteCategory(_ id: UUID) {
        setCategory(items.values.filter { $0.categoryId == id }.map(\.id), to: nil)
        remove(id, from: \.categories)
    }

    func deleteIcon(_ id: UUID) {
        let ref = IconRef.custom(id)
        for var item in items.values where item.icon == ref {
            item.icon = nil
            save(item)
        }
        remove(id, from: \.icons)
    }

    // MARK: Sync support

    struct Outgoing {
        var key: RecordKey
        var version: Int
        var change: SyncChange
    }

    func outgoingChanges(limit: Int) -> [Outgoing] {
        var out: [Outgoing] = []
        for (key, version) in pending {
            guard out.count < limit else { break }
            if let deletedAt = tombstones[key] {
                out.append(Outgoing(key: key, version: version, change: SyncChange(
                    entity: key.entity, id: key.id.uuidString, updatedAt: Self.ms(deletedAt), deleted: true, data: nil)))
            } else if let record = encodedRecord(key) {
                out.append(Outgoing(key: key, version: version, change: SyncChange(
                    entity: key.entity, id: key.id.uuidString, updatedAt: Self.ms(record.0), deleted: false,
                    data: record.1)))
            } else {
                // Nothing to send any more (e.g. a record removed without a tombstone).
                pending[key] = nil
            }
        }
        pendingCount = pending.count
        return out
    }

    /// Clears what the server accepted, unless it was edited again meanwhile.
    func acknowledge(_ sent: [Outgoing]) {
        for s in sent where pending[s.key] == s.version {
            pending[s.key] = nil
            tombstones[s.key] = nil
        }
        pendingCount = pending.count
        scheduleSave()
    }

    static func ms(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }

    private func encodedRecord(_ key: RecordKey) -> (Date, JSONValue)? {
        func enc<T: Entity>(_ v: T?) -> (Date, JSONValue)? {
            guard let v, let json = try? JSONCoding.toJSONValue(v) else { return nil }
            return (v.updatedAt, json)
        }
        switch key.entity {
        case Room.entityName: return enc(rooms[key.id])
        case Furniture.entityName: return enc(furniture[key.id])
        case Compartment.entityName: return enc(compartments[key.id])
        case Item.entityName: return enc(items[key.id])
        case Category.entityName: return enc(categories[key.id])
        case CustomIcon.entityName: return enc(icons[key.id])
        case HomeSettings.entityName: return enc(settings)
        default: return nil
        }
    }

    private func localUpdatedAt(_ key: RecordKey) -> Date? {
        if let t = tombstones[key] { return t }
        return encodedRecord(key)?.0
    }

    /// Applies records pulled from the server. Local edits that are newer
    /// than the incoming version win and are pushed on the next round.
    func applyRemote(_ changes: [SyncChange]) {
        var changed = false
        for ch in changes {
            guard let id = UUID(uuidString: ch.id) else { continue }
            let key = RecordKey(entity: ch.entity, id: id)
            let remoteDate = Date(timeIntervalSince1970: Double(ch.updatedAt) / 1000)
            if pending[key] != nil {
                if let local = localUpdatedAt(key), Self.ms(local) > ch.updatedAt { continue }
                pending[key] = nil
            }
            tombstones[key] = nil
            if ch.deleted {
                removeLocal(key)
            } else if let data = ch.data {
                insertLocal(key.entity, data)
            }
            if remoteDate > lastTimestamp { lastTimestamp = remoteDate }
            changed = true
        }
        if changed {
            pendingCount = pending.count
            revision += 1
            scheduleSave()
        }
    }

    private func insertLocal(_ entity: String, _ data: JSONValue) {
        func dec<T: Entity>(_ type: T.Type) -> T? { try? JSONCoding.fromJSONValue(type, data) }
        switch entity {
        case Room.entityName: if let v = dec(Room.self) { rooms[v.id] = v }
        case Furniture.entityName: if let v = dec(Furniture.self) { furniture[v.id] = v }
        case Compartment.entityName: if let v = dec(Compartment.self) { compartments[v.id] = v }
        case Item.entityName: if let v = dec(Item.self) { items[v.id] = v }
        case Category.entityName: if let v = dec(Category.self) { categories[v.id] = v }
        case CustomIcon.entityName: if let v = dec(CustomIcon.self) { icons[v.id] = v }
        case HomeSettings.entityName: if let v = dec(HomeSettings.self) { settings = v }
        default: break
        }
    }

    private func removeLocal(_ key: RecordKey) {
        switch key.entity {
        case Room.entityName: rooms[key.id] = nil
        case Furniture.entityName: furniture[key.id] = nil
        case Compartment.entityName: compartments[key.id] = nil
        case Item.entityName: items[key.id] = nil
        case Category.entityName: categories[key.id] = nil
        case CustomIcon.entityName: icons[key.id] = nil
        case HomeSettings.entityName: settings = HomeSettings()
        default: break
        }
    }

    /// After a full pull: fix references broken by concurrent edits on two
    /// devices (e.g. an item added to a drawer another device deleted).
    func repairOrphans() {
        for f in furniture.values where rooms[f.roomId] == nil {
            deleteFurniture(f.id, policy: .keepItems)
        }
        for var f in furniture.values {
            if let p = f.stackedOnId, furniture[p] == nil {
                f.stackedOnId = nil
                save(f)
            }
        }
        for c in compartments.values where furniture[c.furnitureId] == nil {
            deleteCompartment(c.id, policy: .keepItems)
        }
        for var item in items.values {
            var dirty = false
            if let cid = item.compartmentId, compartments[cid] == nil { item.compartmentId = nil; dirty = true }
            if let cat = item.categoryId, categories[cat] == nil { item.categoryId = nil; dirty = true }
            if dirty { save(item) }
        }
        if !rooms.isEmpty && !didOnboard { markOnboarded() }
    }

    /// Marks every local record for upload (merging into an account).
    func markAllPending() {
        let keys: [RecordKey] =
            rooms.keys.map { RecordKey(entity: Room.entityName, id: $0) } +
            furniture.keys.map { RecordKey(entity: Furniture.entityName, id: $0) } +
            compartments.keys.map { RecordKey(entity: Compartment.entityName, id: $0) } +
            items.keys.map { RecordKey(entity: Item.entityName, id: $0) } +
            categories.keys.map { RecordKey(entity: Category.entityName, id: $0) } +
            icons.keys.map { RecordKey(entity: CustomIcon.entityName, id: $0) } +
            (settings.updatedAt.timeIntervalSince1970 > 0 ? [RecordKey(entity: HomeSettings.entityName, id: settings.id)] : [])
        for k in keys { pending[k, default: 0] += 1 }
        let used = Set(items.values.flatMap { [$0.photoId, $0.originalPhotoId].compactMap { $0 } })
        pendingUploads.formUnion(used.filter { files.exists($0) })
        cursor = 0
        pendingCount = pending.count
        scheduleSave()
    }

    /// Forgets everything on this device.
    func wipeLocal() {
        rooms = [:]; furniture = [:]; compartments = [:]; items = [:]
        categories = [:]; icons = [:]; settings = HomeSettings()
        pending = [:]; tombstones = [:]; pendingUploads = []
        cursor = 0
        didOnboard = false
        pendingCount = 0
        revision += 1
        files.deleteAll()
        saveNow()
    }

    // MARK: Backup

    /// Every live record as a sync change. The Home Assistant token is left out.
    func backupChanges() -> [SyncChange] {
        var out: [SyncChange] = []
        func add<T: Entity>(_ v: T) {
            guard let json = try? JSONCoding.toJSONValue(v) else { return }
            out.append(SyncChange(entity: T.entityName, id: v.id.uuidString, updatedAt: Self.ms(v.updatedAt), deleted: false, data: json))
        }
        rooms.values.forEach(add)
        furniture.values.forEach(add)
        compartments.values.forEach(add)
        categories.values.forEach(add)
        icons.values.forEach(add)
        items.values.forEach(add)
        if settings.updatedAt.timeIntervalSince1970 > 0 {
            var s = settings
            s.haToken = ""
            add(s)
        }
        return out
    }

    struct ImportSummary {
        var rooms = 0, furniture = 0, items = 0, photos = 0, skipped = 0
    }

    /// Restores a backup. Merge keeps whichever side is newer per record;
    /// replace makes this device match the backup (and wins on the server
    /// too). Imported records are queued for upload when signed in.
    func importBackup(_ changes: [SyncChange], replace: Bool) -> ImportSummary {
        var summary = ImportSummary()
        let incoming = Set(changes.compactMap { c in UUID(uuidString: c.id).map { RecordKey(entity: c.entity, id: $0) } })
        if replace {
            func prune<T: Entity>(_ kp: ReferenceWritableKeyPath<HomeStore, [UUID: T]>) {
                for id in self[keyPath: kp].keys where !incoming.contains(RecordKey(entity: T.entityName, id: id)) {
                    remove(id, from: kp)
                }
            }
            prune(\.items); prune(\.compartments); prune(\.furniture); prune(\.rooms); prune(\.icons); prune(\.categories)
        }
        let keptHA = (settings.haURL, settings.haToken)
        for ch in changes where !ch.deleted {
            guard let id = UUID(uuidString: ch.id), let data = ch.data else { continue }
            let key = RecordKey(entity: ch.entity, id: id)
            if !replace, let local = localUpdatedAt(key), Self.ms(local) >= ch.updatedAt {
                summary.skipped += 1
                continue
            }
            insertLocal(ch.entity, data)
            if replace { restamp(key) }
            pending[key, default: 0] += 1
            tombstones[key] = nil
            switch ch.entity {
            case Room.entityName: summary.rooms += 1
            case Furniture.entityName: summary.furniture += 1
            case Item.entityName: summary.items += 1
            default: break
            }
        }
        // Backups carry no Home Assistant token; keep this device's connection.
        if settings.haToken.isEmpty && !keptHA.1.isEmpty {
            settings.haURL = keptHA.0
            settings.haToken = keptHA.1
        }
        if !rooms.isEmpty { didOnboard = true }
        pendingCount = pending.count
        revision += 1
        repairOrphans()
        scheduleSave()
        onLocalChange?()
        return summary
    }

    /// Gives a just-restored record a fresh timestamp so it beats older copies.
    private func restamp(_ key: RecordKey) {
        let t = nextTimestamp()
        switch key.entity {
        case Room.entityName: rooms[key.id]?.updatedAt = t
        case Furniture.entityName: furniture[key.id]?.updatedAt = t
        case Compartment.entityName: compartments[key.id]?.updatedAt = t
        case Item.entityName: items[key.id]?.updatedAt = t
        case Category.entityName: categories[key.id]?.updatedAt = t
        case CustomIcon.entityName: icons[key.id]?.updatedAt = t
        case HomeSettings.entityName: settings.updatedAt = t
        default: break
        }
    }

    // MARK: Export

    struct Export: Codable {
        var exportedAt: Date
        var homeName: String
        var rooms: [Room]
        var furniture: [Furniture]
        var compartments: [Compartment]
        var items: [Item]
        var categories: [Category]
        var icons: [CustomIcon]
    }

    /// JSON dump of the household (without the Home Assistant token).
    func exportJSON() throws -> URL {
        let export = Export(exportedAt: Date(), homeName: settings.homeName,
                            rooms: sortedRooms, furniture: Array(furniture.values),
                            compartments: Array(compartments.values), items: Array(items.values),
                            categories: sortedCategories, icons: sortedIcons)
        let enc = JSONEncoder()
        enc.outputFormatting = [.prettyPrinted, .sortedKeys]
        enc.dateEncodingStrategy = .iso8601
        let data = try enc.encode(export)
        let name = "万物收纳-\(Date().formatted(.iso8601.year().month().day())).json"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        try data.write(to: url, options: .atomic)
        return url
    }
}
