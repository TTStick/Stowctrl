import XCTest

/// Walks the main flows end to end. Screenshots go to $SHOT_DIR when set
/// (pass TEST_RUNNER_SHOT_DIR to xcodebuild) and are attached to the result.
final class NookUITests: XCTestCase {
    private var app: XCUIApplication!
    private let shotDir = ProcessInfo.processInfo.environment["SHOT_DIR"]
    private let server = ProcessInfo.processInfo.environment["NOOK_SERVER"] ?? "127.0.0.1:5858"

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-uitest"]
        app.launch()
    }

    // MARK: Helpers

    private func shot(_ name: String) {
        let s = XCUIScreen.main.screenshot()
        let a = XCTAttachment(screenshot: s)
        a.name = name
        a.lifetime = .keepAlways
        add(a)
        if let dir = shotDir {
            try? s.pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent("\(name).png"))
        }
    }

    private func el(_ id: String) -> XCUIElement { app.descendants(matching: .any)[id].firstMatch }

    private func labelled(_ text: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@ OR value CONTAINS %@", text, text)).firstMatch
    }

    @discardableResult
    private func wait(_ e: XCUIElement, _ timeout: TimeInterval = 6, file: StaticString = #filePath, line: UInt = #line) -> XCUIElement {
        let found = e.waitForExistence(timeout: timeout)
        if !found { shot("FAIL-line\(line)") }
        XCTAssertTrue(found, "missing \(e)", file: file, line: line)
        return e
    }

    private func tap(_ e: XCUIElement, file: StaticString = #filePath, line: UInt = #line) {
        // Lists and forms only build rows near the screen; scroll until it appears.
        var scrolls = 0
        while !e.waitForExistence(timeout: scrolls == 0 ? 4 : 1) && scrolls < 8 { app.swipeUp(); scrolls += 1 }
        wait(e, 1, file: file, line: line)
        var tries = 0
        while !e.isHittable && tries < 8 { app.swipeUp(); tries += 1 }
        e.tap()
    }

    private func type(_ text: String, into e: XCUIElement, replacing: Bool = false) {
        tap(e)
        if replacing, let current = e.value as? String, !current.isEmpty {
            e.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count))
        }
        e.typeText(text)
    }

    private func back() { app.navigationBars.buttons.element(boundBy: 0).tap() }

    /// Signed builds get iOS's "保存密码？" prompt after signing in; answer "以后".
    private func dismissSavePassword(timeout: TimeInterval = 3) {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let candidates = [app.buttons["以后"], springboard.buttons["以后"], app.buttons["Not Now"], springboard.buttons["Not Now"]]
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            if let hit = candidates.first(where: { $0.exists }) { hit.tap(); return }
            usleep(300_000)
        } while Date() < deadline
    }

    private func button(containing text: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    /// Taps the confirmation-dialog button (the last one with this title).
    private func confirm(_ title: String) {
        sleep(1)
        let matches = app.buttons.matching(identifier: title).allElementsBoundByIndex.filter { $0.isHittable }
        XCTAssertFalse(matches.isEmpty, "no \(title) button to confirm")
        matches.last?.tap()
    }

    // MARK: Flows

    func testFullFlowAndSyncToSecondDevice() throws {
        // Onboarding → wizard → furnished home
        shot("01-welcome")
        tap(app.buttons["开始搭建我的家"])
        wait(app.navigationBars["搭建你的家"])
        shot("02-wizard")
        tap(el("wizard.create"))
        wait(el("room.厨房"), 10)
        shot("03-floorplan")

        // Open the kitchen, then the fridge
        tap(el("room.厨房"))
        wait(el("furniture.冰箱"))
        shot("04-kitchen")
        tap(el("furniture.冰箱"))
        wait(app.staticTexts["冷藏上层"])
        sleep(1)
        shot("05-fridge-open")

        // Add milk to the top shelf with a library icon and a category
        tap(app.buttons["放入物品到冷藏上层"])
        type("牛奶", into: wait(el("item.name")))
        tap(el("item.icon"))
        tap(app.buttons["马克杯 杯子"])
        tap(el("item.category"))
        tap(app.buttons["饮品"])
        shot("06-item-editor")
        tap(el("item.save"))
        wait(app.staticTexts["牛奶"])
        shot("07-fridge-with-milk")

        // Detail: quantity, edit, delete
        tap(app.staticTexts["牛奶"])
        wait(app.staticTexts["1 个"])
        tap(app.buttons["增加"])
        wait(app.staticTexts["2 个"])
        shot("08-item-detail")
        tap(app.buttons["编辑"])
        type("鲜牛奶", into: wait(el("item.name")), replacing: true)
        tap(el("item.save"))
        wait(app.navigationBars["鲜牛奶"])
        tap(button(containing: "删除物品"))
        confirm("删除")
        wait(app.staticTexts["冷藏上层"])
        XCTAssertFalse(app.staticTexts["鲜牛奶"].exists)

        // Edit the room layout: rotate, duplicate, delete a piece
        back()
        wait(el("furniture.冰箱"))
        tap(app.navigationBars.buttons["编辑"])
        tap(el("furniture.水槽"))
        tap(button(containing: "旋转"))
        tap(button(containing: "复制"))
        shot("09-room-editing")
        tap(button(containing: "删除"))
        confirm("删除")
        tap(app.navigationBars.buttons["完成"])
        XCTAssertTrue(el("furniture.水槽").waitForExistence(timeout: 3), "one sink remains after deleting the copy")

        // Master list: add an item with a location, then find it by search
        tap(app.tabBars.buttons["物品"])
        tap(app.navigationBars.buttons["添加物品"])
        type("螺丝刀", into: wait(el("item.name")))
        tap(el("item.location"))
        tap(button(containing: "地柜"))
        tap(button(containing: "抽屉"))
        tap(el("item.save"))
        wait(app.staticTexts["螺丝刀"])
        let search = app.searchFields.firstMatch
        if !search.exists { app.swipeDown() }
        type("螺丝", into: wait(app.searchFields.firstMatch))
        wait(labelled("厨房 › 地柜 › 抽屉"))
        shot("10-items-search")
        // Leave search mode (the button is "取消" on older iOS, a "关闭" ✕ on newer).
        for name in ["取消", "关闭", "Cancel", "Close"] where app.buttons[name].exists {
            app.buttons[name].firstMatch.tap()
            break
        }

        // Devices tab without Home Assistant shows the setup path
        tap(app.tabBars.buttons["设备"])
        wait(app.buttons["开始设置"])
        shot("11-devices")

        // Register on the sync server and push everything
        let user = "ui\(Int(Date().timeIntervalSince1970))"
        tap(app.tabBars.buttons["我的"])
        tap(button(containing: "登录或注册"))
        type(server, into: wait(el("account.server")), replacing: true)
        tap(app.buttons["注册新账号"])
        type(user, into: el("account.username"))
        type("secret123", into: el("account.password"))
        tap(el("account.submit"))
        dismissSavePassword()
        wait(labelled(user), 15)
        wait(labelled("已同步"), 15)
        shot("12-settings-synced")

        // Offline mode: edits queue up locally, then sync once it's turned off.
        let offlineSwitch = app.switches["离线模式"].firstMatch
        func flipOffline() {
            wait(offlineSwitch)
            let before = offlineSwitch.value as? String
            let inner = offlineSwitch.switches.firstMatch
            if inner.exists { inner.tap() } else { offlineSwitch.tap() }
            expectation(for: NSPredicate(format: "value != %@", before ?? ""), evaluatedWith: offlineSwitch)
            waitForExpectations(timeout: 5)
        }
        flipOffline()
        tap(app.tabBars.buttons["物品"])
        tap(app.navigationBars.buttons["添加物品"])
        type("离线时加的电池", into: wait(el("item.name")))
        tap(el("item.save"))
        tap(app.tabBars.buttons["我的"])
        wait(labelled("已暂停"), 5)
        shot("12b-offline-paused")
        flipOffline()
        wait(labelled("已同步"), 15)

        // A "second device": wipe local state, sign in, home comes back
        app.terminate()
        app.launchArguments = ["-uitest"]
        app.launch()
        tap(app.buttons["已有账号？登录同步"])
        type(server, into: wait(el("account.server")), replacing: true)
        type(user, into: el("account.username"))
        type("secret123", into: el("account.password"))
        tap(el("account.submit"))
        dismissSavePassword()
        wait(el("room.厨房"), 20)
        dismissSavePassword(timeout: 4)  // it can appear after the sheet closes
        tap(app.tabBars.buttons["物品"])
        wait(app.staticTexts["螺丝刀"], 10)
        wait(app.staticTexts["离线时加的电池"], 10)
        shot("13-second-device")

        // Clean up the test account
        tap(app.tabBars.buttons["我的"])
        tap(button(containing: "注销账号"))
        type("secret123", into: wait(app.secureTextFields.firstMatch))
        tap(app.buttons["永久注销账号"])
        wait(button(containing: "登录或注册"), 10)
    }

    func testHomeAssistantMappingAndControl() throws {
        let ha = ProcessInfo.processInfo.environment["FAKE_HA"] ?? "127.0.0.1:18123"
        // Needs tools/fake_ha.py running (python3 tools/fake_ha.py 18123).
        var probe = URLRequest(url: URL(string: "http://\(ha)/api/")!, timeoutInterval: 2)
        probe.setValue("Bearer test-token", forHTTPHeaderField: "Authorization")
        let reachable = expectation(description: "fake HA")
        var up = false
        URLSession.shared.dataTask(with: probe) { _, r, _ in up = (r as? HTTPURLResponse)?.statusCode == 200; reachable.fulfill() }.resume()
        wait(for: [reachable], timeout: 4)
        guard up else { throw XCTSkip("fake Home Assistant not running at \(ha)") }
        tap(app.buttons["开始搭建我的家"])
        tap(el("wizard.create"))
        wait(el("room.客厅"), 10)

        tap(app.tabBars.buttons["设备"])
        tap(app.buttons["开始设置"])
        type("http://\(ha)", into: wait(el("ha.url")))
        type("test-token", into: el("ha.token"))
        tap(button(containing: "测试并保存"))
        wait(labelled("连接成功"), 10)
        shot("20-ha-settings")
        tap(app.buttons["关闭"])

        let lightCell = app.cells.containing(.staticText, identifier: "客厅灯").firstMatch
        wait(lightCell, 10)
        tap(lightCell.buttons["放到家具上"])
        tap(button(containing: "沙发"))
        // Now mapped: it shows under 客厅 › 沙发 with a live switch.
        wait(app.buttons.matching(NSPredicate(format: "label CONTAINS '沙发'")).firstMatch, 8)
        let mapped = app.cells.containing(.staticText, identifier: "客厅灯").firstMatch
        let toggle = mapped.switches.firstMatch
        wait(toggle)
        let before = toggle.value as? String ?? "0"
        toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.5)).tap()
        let flipped = NSPredicate(format: "value == %@", before == "1" ? "0" : "1")
        expectation(for: flipped, evaluatedWith: toggle)
        waitForExpectations(timeout: 10)
        shot("21-ha-devices")

        // The service call reached Home Assistant.
        var req = URLRequest(url: URL(string: "http://\(ha)/calls")!)
        req.setValue("Bearer test-token", forHTTPHeaderField: "Authorization")
        let exp = expectation(description: "calls")
        var body = Data()
        URLSession.shared.dataTask(with: req) { d, _, _ in body = d ?? Data(); exp.fulfill() }.resume()
        wait(for: [exp], timeout: 5)
        let calls = try JSONSerialization.jsonObject(with: body) as? [[Any]]
        let expected = before == "1" ? "turn_off" : "turn_on"
        XCTAssertTrue(calls?.contains { ($0[0] as? String) == "light" && ($0[1] as? String) == expected } ?? false)

        // The sofa glows on the plan and its detail page lists the light.
        tap(app.tabBars.buttons["家"])
        tap(el("room.客厅"))
        wait(el("furniture.沙发"))
        shot("22-ha-room")
        tap(el("furniture.沙发"))
        wait(labelled("智能设备"))
        shot("23-ha-furniture")
    }
}
