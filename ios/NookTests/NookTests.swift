import XCTest
@testable import Nook

final class SVGTests: XCTestCase {
    func testParsesServerStyleIcon() throws {
        let svg = """
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48" stroke="currentColor" fill="none" stroke-width="2.5">
          <g stroke-linecap="round"><path d="M10 10 L38 38 m-4 0 h4 v-4"/></g>
          <circle cx="24" cy="24" r="6" fill="currentColor"/>
          <rect x="4" y="4" width="40" height="40" rx="6"/>
          <polyline points="4,44 24,30 44,44"/>
          <ellipse cx="24" cy="12" rx="8" ry="4"/>
          <line x1="0" y1="0" x2="48" y2="48"/>
        </svg>
        """
        let doc = try XCTUnwrap(SVGDocument.parse(svg))
        XCTAssertEqual(doc.viewBox, CGRect(x: 0, y: 0, width: 48, height: 48))
        XCTAssertEqual(doc.shapes.count, 6)
        let path = doc.shapes[0]
        XCTAssertTrue(path.stroke)
        XCTAssertFalse(path.fill, "fill=none is inherited from the root")
        XCTAssertEqual(path.strokeWidth, 2.5)
        XCTAssertEqual(path.lineCap, .round)
        XCTAssertTrue(doc.shapes[1].fill)
    }

    func testPathCommandsAndCompactNumbers() throws {
        let p = try XCTUnwrap(SVGPathParser.parse("M1.5.5L-2-3c1,1 2,2 3,3s1 1 2 2q1 1 2 2t2 2zM0 0H10V10H0Z"))
        XCTAssertFalse(p.isEmpty)
        XCTAssertNotNil(SVGPathParser.parse("M0 0 1e1 1e-1"))
        XCTAssertNil(SVGPathParser.parse("10 10"), "numbers without a command are rejected")
    }

    func testArcEndsAtTarget() throws {
        // Compact arc flags ("0 01") are legal SVG.
        let p = try XCTUnwrap(SVGPathParser.parse("M10 20 a10 10 0 01 20 0"))
        let box = p.boundingBoxOfPath
        XCTAssertEqual(p.currentPoint.x, 30, accuracy: 0.01)
        XCTAssertEqual(p.currentPoint.y, 20, accuracy: 0.01)
        XCTAssertEqual(box.minY, 10, accuracy: 0.5, "half circle bulges up by its radius")
    }

    func testRejectsGarbage() {
        XCTAssertNil(SVGDocument.parse("not svg"))
        XCTAssertNil(SVGDocument.parse("<svg viewBox='0 0 10 10'><script>x</script></svg>"))
    }
}

@MainActor
final class StoreTests: XCTestCase {
    private var dir: URL!

    override func setUp() async throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: dir)
    }

    private func makeStore() -> HomeStore { HomeStore(directory: dir) }

    func testBuildHomeCreatesFurnishedRoomsWithStacking() {
        let store = makeStore()
        store.buildHome(name: "测试之家", rooms: [(.kitchen, 1), (.bedroom, 2), (.balcony, 1)], withFurniture: true)
        XCTAssertTrue(store.didOnboard)
        XCTAssertEqual(store.settings.homeName, "测试之家")
        XCTAssertEqual(Set(store.rooms.values.map(\.name)), ["厨房", "主卧", "次卧", "阳台"])
        XCTAssertEqual(store.categories.count, DefaultCategories.all.count)
        let microwave = store.furniture.values.first { $0.kind == "microwave" }!
        XCTAssertEqual(store.furniture[microwave.stackedOnId!]?.kind, "base_cabinet")
        let dryer = store.furniture.values.first { $0.kind == "dryer" }!
        XCTAssertEqual(store.furniture[dryer.stackedOnId!]?.kind, "washer")
        XCTAssertFalse(store.compartments(of: microwave.id).isEmpty)
        for f in store.furniture.values {
            let room = store.rooms[f.roomId]!
            XCTAssertTrue(CGRect(x: 0, y: 0, width: room.width, height: room.depth).contains(f.rect), "\(f.name) fits in \(room.name)")
        }
    }

    func testMovingAndRotatingCarriesStackedPieces() {
        let store = makeStore()
        let room = store.addRoom(type: .kitchen, name: "厨房", width: 400, depth: 300)
        let cabinet = store.addFurniture(kind: Catalog.kind("base_cabinet"), to: room.id)!
        let microwave = store.addFurniture(kind: Catalog.kind("microwave"), to: room.id)!
        store.setStack(microwave.id, on: cabinet.id)
        let before = store.furniture[microwave.id]!
        XCTAssertTrue(store.furniture[cabinet.id]!.rect.contains(before.center))

        store.moveFurniture(cabinet.id, to: CGPoint(x: cabinet.x + 100, y: cabinet.y + 50))
        let after = store.furniture[microwave.id]!
        XCTAssertEqual(after.x - before.x, 100, accuracy: 0.001)
        XCTAssertEqual(after.y - before.y, 50, accuracy: 0.001)

        store.rotateFurniture(cabinet.id)
        XCTAssertEqual(store.furniture[cabinet.id]!.rotation, 1)
        XCTAssertEqual(store.furniture[microwave.id]!.rotation, 1)
        XCTAssertTrue(store.furniture[cabinet.id]!.rect.insetBy(dx: -1, dy: -1).contains(store.furniture[microwave.id]!.center))

        // No cycles: the cabinet can't go on top of its own microwave.
        store.setStack(cabinet.id, on: microwave.id)
        XCTAssertNil(store.furniture[cabinet.id]!.stackedOnId)
    }

    func testDeletePoliciesKeepOrRemoveItems() {
        let store = makeStore()
        let room = store.addRoom(type: .bedroom, name: "卧室")
        let wardrobe = store.addFurniture(kind: Catalog.kind("wardrobe"), to: room.id)!
        let rail = store.compartments(of: wardrobe.id)[0]
        let shirt = Item(name: "衬衫", compartmentId: rail.id)
        let coat = Item(name: "大衣", compartmentId: rail.id)
        store.save(shirt)
        store.save(coat)
        XCTAssertEqual(store.itemCount(furniture: wardrobe.id), 2)
        XCTAssertEqual(store.locationText(shirt), "卧室 › 衣柜 › 挂衣区")

        store.deleteCompartment(rail.id, policy: .keepItems)
        XCTAssertEqual(store.items.count, 2)
        XCTAssertEqual(store.unsortedItems.count, 2)

        let drawer = store.compartments(of: wardrobe.id)[0]
        store.moveItems([shirt.id, coat.id], to: drawer.id)
        store.deleteRoom(room.id, policy: .deleteItems)
        XCTAssertTrue(store.items.isEmpty)
        XCTAssertTrue(store.furniture.isEmpty)
        XCTAssertTrue(store.compartments.isEmpty)
        XCTAssertTrue(store.rooms.isEmpty)
    }

    func testDeletingBaseKeepsStackedPiece() {
        let store = makeStore()
        let room = store.addRoom(type: .balcony, name: "阳台")
        let washer = store.addFurniture(kind: Catalog.kind("washer"), to: room.id)!
        let dryer = store.addFurniture(kind: Catalog.kind("dryer"), to: room.id)!
        store.setStack(dryer.id, on: washer.id)
        store.deleteFurniture(washer.id, policy: .keepItems)
        XCTAssertNotNil(store.furniture[dryer.id])
        XCTAssertNil(store.furniture[dryer.id]!.stackedOnId)
    }

    func testPersistenceRoundTrip() {
        let room: Room
        do {
            let store = makeStore()
            room = store.addRoom(type: .study, name: "书房")
            var item = Item(name: "台灯")
            item.icon = .symbol("lamp.desk")
            item.tags = ["电器"]
            store.save(item)
            store.saveNow()
        }
        let reopened = makeStore()
        XCTAssertEqual(reopened.rooms[room.id]?.name, "书房")
        XCTAssertEqual(reopened.items.values.first?.icon, .symbol("lamp.desk"))
        XCTAssertGreaterThan(reopened.pendingCount, 0, "unsynced edits survive a restart")
    }

    func testRemoteApplyRespectsNewerLocalEdits() throws {
        let store = makeStore()
        var item = Item(name: "本地名字")
        store.save(item)
        item = store.items[item.id]!
        let localMs = HomeStore.ms(item.updatedAt)

        var remote = item
        remote.name = "旧的远端名字"
        let older = SyncChange(entity: "item", id: item.id.uuidString, updatedAt: localMs - 1000, deleted: false,
                               data: try JSONCoding.toJSONValue(remote))
        store.applyRemote([older])
        XCTAssertEqual(store.items[item.id]?.name, "本地名字", "pending local edit is newer")

        remote.name = "新的远端名字"
        let newer = SyncChange(entity: "item", id: item.id.uuidString, updatedAt: localMs + 1000, deleted: false,
                               data: try JSONCoding.toJSONValue(remote))
        store.applyRemote([newer])
        XCTAssertEqual(store.items[item.id]?.name, "新的远端名字")
        XCTAssertEqual(store.pendingCount, store.outgoingChanges(limit: 100).count)

        let tomb = SyncChange(entity: "item", id: item.id.uuidString, updatedAt: localMs + 2000, deleted: true, data: nil)
        store.applyRemote([tomb])
        XCTAssertNil(store.items[item.id])
    }

    func testDecodesRecordsWithMissingAndUnknownFields() throws {
        let id = UUID()
        let json: JSONValue = .object(["id": .string(id.uuidString), "name": .string("杯子"),
                                       "futureField": .string("x"), "quantity": .number(3)])
        let item = try JSONCoding.fromJSONValue(Item.self, json)
        XCTAssertEqual(item.name, "杯子")
        XCTAssertEqual(item.quantity, 3)
        XCTAssertEqual(item.unit, "个")
        XCTAssertNil(item.compartmentId)
    }

    func testRepairOrphansAfterConcurrentDeletes() throws {
        let store = makeStore()
        let room = store.addRoom(type: .living, name: "客厅")
        let shelf = store.addFurniture(kind: Catalog.kind("bookshelf"), to: room.id)!
        let layer = store.compartments(of: shelf.id)[0]
        store.save(Item(name: "书", compartmentId: layer.id))
        // Another device deleted the shelf (but this device never saw its compartments go).
        store.applyRemote([SyncChange(entity: "furniture", id: shelf.id.uuidString,
                                      updatedAt: HomeStore.ms(Date()) + 5000, deleted: true, data: nil)])
        store.repairOrphans()
        XCTAssertTrue(store.compartments.isEmpty)
        XCTAssertEqual(store.unsortedItems.map(\.name), ["书"])
    }
}

/// Two devices syncing through a real server. Start one with
/// `docker compose up` in server/ (or set NOOK_TEST_SERVER); skipped otherwise.
@MainActor
final class LiveSyncTests: XCTestCase {
    func testTwoDevicesConverge() async throws {
        let base = ProcessInfo.processInfo.environment["NOOK_TEST_SERVER"] ?? "http://127.0.0.1:5858"
        let url = try XCTUnwrap(APIClient.normalize(base))
        do { _ = try await APIClient(baseURL: url).info() } catch { throw XCTSkip("no server at \(base)") }

        let user = "t\(UUID().uuidString.prefix(8).lowercased())"
        let auth = try await APIClient(baseURL: url).login(username: user, password: "secret123", device: "test", register: true)
        let api = APIClient(baseURL: url, token: auth.token)

        func dirs() -> URL { FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString) }
        let phone = HomeStore(directory: dirs())
        let pad = HomeStore(directory: dirs())

        func sync(_ store: HomeStore) async throws {
            while true {
                let out = store.outgoingChanges(limit: 400)
                let r = try await api.sync(cursor: store.cursor, changes: out.map(\.change))
                store.acknowledge(out)
                store.applyRemote(r.changes)
                store.cursor = r.cursor
                if !r.hasMore && store.pendingCount == 0 { break }
            }
            store.repairOrphans()
        }

        phone.buildHome(name: "同步测试", rooms: [(.kitchen, 1)], withFurniture: true)
        let fridge = phone.furniture.values.first { $0.kind == "fridge" }!
        let shelf = phone.compartments(of: fridge.id)[0]
        let milk = Item(name: "牛奶", compartmentId: shelf.id)
        phone.save(milk)
        try await sync(phone)
        XCTAssertEqual(phone.pendingCount, 0)

        try await sync(pad)
        XCTAssertEqual(pad.rooms.count, 1)
        XCTAssertEqual(pad.furniture.count, phone.furniture.count)
        XCTAssertEqual(pad.locationText(pad.items[milk.id]!), "厨房 › 冰箱 › 冷藏上层")
        XCTAssertTrue(pad.didOnboard)

        // The pad edits, the phone deletes something else; both converge.
        pad.adjustQuantity(milk.id, by: 2)
        let stove = phone.furniture.values.first { $0.kind == "stove" }!
        phone.deleteFurniture(stove.id, policy: .keepItems)
        try await sync(pad)
        try await sync(phone)
        try await sync(pad)
        XCTAssertEqual(phone.items[milk.id]?.quantity, 3)
        XCTAssertNil(pad.furniture[stove.id])
        XCTAssertEqual(Set(pad.furniture.keys), Set(phone.furniture.keys))

        // Photos travel separately.
        let photoId = UUID().uuidString + ".jpg"
        try await api.upload(fileId: photoId, data: Data([0xFF, 0xD8, 0xFF, 0x00]))
        let back = try await api.download(fileId: photoId)
        XCTAssertEqual(back, Data([0xFF, 0xD8, 0xFF, 0x00]))

        try await api.deleteAccount(password: "secret123")
    }
}

@MainActor
final class CutoutTests: XCTestCase {
    /// Real photo through Vision's foreground mask. Simulators without the
    /// model skip rather than fail; the code path is the same on device.
    func testCutsOutAGarmentPhoto() async throws {
        let path = ProcessInfo.processInfo.environment["CUTOUT_SAMPLE"]
            ?? "/Users/xmwy/Desktop/FavoutiteBpx/The-thereal-Trousseau/Trousseau-Ethereal/readme.assets/image-20260117210139193.png"
        guard let image = UIImage(contentsOfFile: path) else { throw XCTSkip("sample photo not found at \(path)") }
        let session: CutoutSession
        do {
            session = try await CutoutSession.make(image)
        } catch CutoutSession.Failure.unsupported {
            throw XCTSkip("Vision foreground masks are unavailable on this simulator")
        }
        XCTAssertGreaterThan(session.instanceCount, 0)
        let cut = try await session.render()
        let png = try XCTUnwrap(ImageEncoding.png(cut))
        XCTAssertLessThan(cut.size.width * cut.size.height, image.size.width * image.size.height * 1.1)
        // Corners of the cut-out are transparent.
        let cg = try XCTUnwrap(cut.cgImage)
        var px = [UInt8](repeating: 0, count: 4)
        let ctx = CGContext(data: &px, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: cg.width, height: cg.height))
        XCTAssertEqual(px[3], 0, "bottom-left corner should be transparent")
        XCTAssertFalse(ColorExtractor.palette(of: cut).isEmpty)
        if let dir = ProcessInfo.processInfo.environment["SHOT_DIR"] {
            try png.write(to: URL(fileURLWithPath: dir).appendingPathComponent("30-cutout.png"))
        }
    }
}

/// Reads what the Android app wrote and adds an item of its own (see
/// android/app/src/test/.../CrossPlatformTest.kt). Needs XPLAT_USER.
@MainActor
final class CrossPlatformTests: XCTestCase {
    func testReadAndroidDataAndReply() async throws {
        guard let user = ProcessInfo.processInfo.environment["XPLAT_USER"], !user.isEmpty else { throw XCTSkip("XPLAT_USER not set") }
        let url = try XCTUnwrap(APIClient.normalize(ProcessInfo.processInfo.environment["NOOK_SERVER"] ?? "127.0.0.1:5858"))
        let auth = try await APIClient(baseURL: url).login(username: user, password: "secret123", device: "ios-test", register: false)
        let api = APIClient(baseURL: url, token: auth.token)
        let store = HomeStore(directory: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString))
        func syncAll() async throws {
            while true {
                let out = store.outgoingChanges(limit: 400)
                let r = try await api.sync(cursor: store.cursor, changes: out.map(\.change))
                store.acknowledge(out)
                store.applyRemote(r.changes)
                store.cursor = r.cursor
                if !r.hasMore && (store.pendingCount == 0 || out.isEmpty) { break }
            }
            store.repairOrphans()
        }
        try await syncAll()

        // Everything Android wrote decodes and links up on iOS.
        XCTAssertEqual(store.settings.homeName, "跨平台之家")
        XCTAssertEqual(Set(store.rooms.values.map(\.name)), ["厨房", "卧室"])
        let cup = try XCTUnwrap(store.items.values.first { $0.name == "安卓杯子" })
        XCTAssertEqual(store.locationText(cup), "厨房 › 冰箱 › 冷藏上层")
        XCTAssertEqual(cup.icon, .symbol("mug"))
        XCTAssertEqual(store.category(of: cup)?.name, "饮品")
        XCTAssertEqual(cup.quantity, 3)
        let spoon = try XCTUnwrap(store.items.values.first { $0.name == "安卓汤勺" })
        guard case .custom = spoon.icon?.kind, let iconId = spoon.icon.flatMap({ UUID(uuidString: $0.value) }) else { return XCTFail("custom icon ref") }
        XCTAssertNotNil(SVGDocument.parse(try XCTUnwrap(store.icons[iconId]).svg))
        let box = try XCTUnwrap(store.furniture.values.first { $0.kind == "storage_box" })
        XCTAssertEqual(store.furniture[try XCTUnwrap(box.stackedOnId)]?.kind, "wardrobe")
        XCTAssertTrue(store.didOnboard)

        // Reply: a new item with fractional dates, a custom icon, a quantity bump and a rename.
        let fridge = try XCTUnwrap(store.furniture.values.first { $0.kind == "fridge" })
        let middle = try XCTUnwrap(store.compartments(of: fridge.id).first { $0.name == "冷藏中层" })
        var milk = Item(name: "苹果手机记的牛奶", compartmentId: middle.id)
        milk.icon = .symbol("cup.and.saucer")
        milk.wearLog = [Date(), Date().addingTimeInterval(-86_400.5)]
        milk.expiresAt = Date().addingTimeInterval(7 * 86_400)
        store.save(milk)
        store.adjustQuantity(cup.id, by: 2)
        store.save(CustomIcon(name: "苹果画的杯子", svg: "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 48 48\"><rect x=\"10\" y=\"12\" width=\"22\" height=\"26\" rx=\"4\" stroke=\"currentColor\" fill=\"none\"/><path d=\"M32 18 h4 a4 4 0 0 1 0 12 h-4\" stroke=\"currentColor\" fill=\"none\"/></svg>"))
        var s = store.settings
        s.homeName = "苹果改的名字"
        store.saveSettings(s)
        try await syncAll()
        XCTAssertEqual(store.pendingCount, 0)
    }
}

/// Offline mode: backups (no server) and direct AI drawing.
@MainActor
final class OfflineTests: XCTestCase {
    private func tempStore() -> HomeStore {
        HomeStore(directory: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString))
    }

    private func sampleHome(_ store: HomeStore, itemName: String) throws -> Item {
        store.buildHome(name: "离线之家", rooms: [(.kitchen, 1)], withFurniture: true)
        let fridge = try XCTUnwrap(store.furniture.values.first { $0.kind == "fridge" })
        let shelf = try XCTUnwrap(store.compartments(of: fridge.id).first { $0.name == "冷藏上层" })
        let photo = UUID().uuidString + ".jpg"
        try store.files.write(Data([0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3]), id: photo)
        let icon = CustomIcon(name: "备份图标", svg: "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 48 48\"><circle cx=\"24\" cy=\"24\" r=\"10\" stroke=\"currentColor\" fill=\"none\"/></svg>")
        store.save(icon)
        var item = Item(name: itemName, compartmentId: shelf.id)
        item.photoId = photo
        item.originalPhotoId = photo
        item.icon = .custom(icon.id)
        store.save(item)
        var s = store.settings
        s.haURL = "http://ha.local:8123"
        s.haToken = "secret-ha-token"
        store.saveSettings(s)
        return store.items[item.id]!
    }

    func testBackupRoundTripMergeAndReplace() async throws {
        let a = tempStore()
        let item = try sampleHome(a, itemName: "备份测试牛奶")
        let url = try await Backup.export(store: a)
        let loaded = try Backup.load(url)
        XCTAssertEqual(loaded.photoCount, 1)
        XCTAssertEqual(loaded.count(Item.entityName), 1)

        let b = tempStore()
        let summary = try Backup.restore(loaded, into: b, replace: false)
        XCTAssertEqual(summary.items, 1)
        XCTAssertEqual(summary.photos, 1)
        let restored = try XCTUnwrap(b.items[item.id])
        XCTAssertEqual(b.locationText(restored), "厨房 › 冰箱 › 冷藏上层")
        XCTAssertEqual(b.files.data(restored.photoId!), a.files.data(item.photoId!))
        XCTAssertNotNil(b.icons[UUID(uuidString: restored.icon!.value)!])
        XCTAssertEqual(b.settings.homeName, "离线之家")
        XCTAssertEqual(b.settings.haToken, "", "backups never carry the HA token")
        XCTAssertTrue(b.didOnboard)

        // A newer local edit survives a merge, but not a replace.
        var renamed = restored
        renamed.name = "本机改过的名字"
        b.save(renamed)
        b.save(Item(name: "只在本机的东西"))
        let merged = try Backup.restore(loaded, into: b, replace: false)
        XCTAssertGreaterThan(merged.skipped, 0)
        XCTAssertEqual(b.items[item.id]?.name, "本机改过的名字")
        _ = try Backup.restore(loaded, into: b, replace: true)
        XCTAssertEqual(b.items[item.id]?.name, "备份测试牛奶")
        XCTAssertNil(b.items.values.first { $0.name == "只在本机的东西" })
        XCTAssertEqual(b.items.count, 1)
    }

    func testRestoreIgnoresUnsafeFileNames() throws {
        let manifest = Backup.Manifest(exportedAt: Date(), platform: "test", homeName: "x", records: [])
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).zip")
        let w = try ZipWriter(url: url)
        try w.add(Backup.manifestName, JSONCoding.encoder.encode(manifest))
        try w.add("files/../../evil.jpg", Data([1, 2, 3]))
        try w.add("files/\(UUID().uuidString).png", Data([4, 5, 6]))
        try w.finish()
        let store = tempStore()
        let s = try Backup.restore(try Backup.load(url), into: store, replace: false)
        XCTAssertEqual(s.photos, 1)
        XCTAssertFalse(Backup.isSafeFileId("../../evil.jpg"))
        XCTAssertEqual(store.files.allIds().count, 1)
        XCTAssertThrowsError(try Backup.load(FileManager.default.temporaryDirectory.appendingPathComponent("nope.zip")))
    }

    /// Needs tools/fake_claude.py on 127.0.0.1:18200; skipped otherwise.
    func testDirectAIDrawingWithoutServer() async throws {
        let base = "http://127.0.0.1:18200"
        guard (try? await URLSession.shared.data(from: URL(string: base + "/calls")!)) != nil else { throw XCTSkip("fake Claude not running") }
        let ai = AIService()
        let saved = (ai.source, ai.baseURL, ai.model)
        defer { ai.source = saved.0; ai.baseURL = saved.1; ai.model = saved.2; ai.setKey(nil) }
        ai.source = .direct
        ai.baseURL = base
        ai.model = AIService.defaultModel
        let sync = SyncManager(store: tempStore())
        XCTAssertEqual(ai.availability(sync), .needsKey)
        do { try await ai.checkKey("wrong-key"); XCTFail("bad key accepted") } catch {}
        try await ai.checkKey("test-key")
        ai.setKey("test-key")
        XCTAssertEqual(ai.availability(sync), .ready)
        let svg = try await ai.generate("汤勺", sync: sync)
        XCTAssertNotNil(SVGDocument.parse(svg))

        let (data, _) = try await URLSession.shared.data(from: URL(string: base + "/calls")!)
        let calls = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [[String: Any]])
        let last = try XCTUnwrap(calls.last)
        let headers = try XCTUnwrap(last["headers"] as? [String: String])
        let body = try XCTUnwrap(last["body"] as? [String: Any])
        XCTAssertEqual(headers["anthropic-version"], "2026-10-21")
        XCTAssertEqual(headers["anthropic-beta"], "server-side-fallback-2026-07-01")
        XCTAssertEqual(body["model"] as? String, "claude-opus-5-5")
        XCTAssertEqual(body["fallbacks"] as? String, "default")
        XCTAssertEqual((body["output_config"] as? [String: Any])?["effort"] as? String, "medium")
        XCTAssertTrue(((body["messages"] as? [[String: Any]])?.first?["content"] as? String ?? "").contains("汤勺"))
    }

    /// Backups made by Android restore here and vice versa. Set XPLAT_DIR; see README.
    func testCrossPlatformBackup() async throws {
        guard let dir = ProcessInfo.processInfo.environment["XPLAT_DIR"], !dir.isEmpty else { throw XCTSkip("XPLAT_DIR not set") }
        let folder = URL(fileURLWithPath: dir)
        let android = folder.appendingPathComponent("android-backup.zip")
        if FileManager.default.fileExists(atPath: android.path) {
            let store = tempStore()
            let s = try Backup.restore(try Backup.load(android), into: store, replace: true)
            XCTAssertEqual(s.photos, 1)
            let cup = try XCTUnwrap(store.items.values.first { $0.name == "安卓备份的杯子" })
            XCTAssertEqual(store.locationText(cup), "厨房 › 冰箱 › 冷藏上层")
            XCTAssertEqual(store.files.data(try XCTUnwrap(cup.photoId)), Data([0xFF, 0xD8, 0xFF, 0xE0, 9, 9, 9]))
            XCTAssertEqual(store.settings.homeName, "安卓之家")
        }
        let mine = tempStore()
        _ = try sampleHome(mine, itemName: "苹果备份的碗")
        let url = try await Backup.export(store: mine)
        let out = folder.appendingPathComponent("ios-backup.zip")
        try? FileManager.default.removeItem(at: out)
        try FileManager.default.copyItem(at: url, to: out)
    }
}
