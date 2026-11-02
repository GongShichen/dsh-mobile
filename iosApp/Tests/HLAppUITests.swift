import XCTest

/// Runs against the production app; transport and repositories are never replaced by test doubles.
final class HLAppUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        addUIInterruptionMonitor(withDescription: "Requested camera or clipboard permission") { alert in
            for title in ["允许", "好", "OK", "Allow", "允许粘贴", "Allow Paste"] {
                let buttons = alert.buttons.matching(NSPredicate(format: "label == %@", title))
                if let button = buttons.allElementsBoundByIndex.first(where: { $0.isHittable }) { button.tap(); return true }
            }
            return false
        }
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 15), "The app must remain in the foreground after launch")
        returnToTabsIfNeeded()
        let ordinary = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "会话模式")).firstMatch
        if ordinary.exists && ordinary.isHittable { ordinary.tap() }
    }

    func testLaunchDevicesAndSettings() {
        tapLabel("设备")
        XCTAssertTrue(element("连接一台电脑").waitForExistence(timeout: 10))
        XCTAssertTrue(element("扫描配对二维码").exists)
        XCTAssertTrue(element("或粘贴配对链接").exists)
        attachScreenshot("devices")

        tapLabel("设置")
        XCTAssertTrue(element("消息通知").waitForExistence(timeout: 10))
        XCTAssertTrue(element("开启通知").exists)
        attachScreenshot("settings")

        tapLabel("工作区")
        XCTAssertTrue(element("添加电脑上的工作区").waitForExistence(timeout: 10))
        tapLabel("设备")
        XCTAssertTrue(element("连接一台电脑").waitForExistence(timeout: 10))
    }

    func testScannerUnavailableReturnsToDevices() {
        tapLabel("设备")
        let scan = element("扫描配对二维码")
        XCTAssertTrue(scan.waitForExistence(timeout: 10))
        scan.tap()
        // Driving a second interaction lets XCTest handle a system authorization dialog, if present.
        app.tap()
        let returnsToDevices = NSPredicate { [weak self] _, _ in
            guard let self else { return false }
            return self.element("扫描配对二维码").isHittable && self.element("设备").isHittable
        }
        let result = XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: returnsToDevices, object: nil)], timeout: 20)
        XCTAssertEqual(result, .completed, "A simulator without a camera must dismiss the scanner instead of trapping the user")
        XCTAssertTrue(app.state == .runningForeground)
        tapLabel("设置")
        XCTAssertTrue(element("消息通知").waitForExistence(timeout: 10))
        attachScreenshot("scanner-returned")
    }

    func testInputAcceptsTouchTypingAndOutsideDismissal() {
        let input = app.textViews.matching(NSPredicate(format: "label CONTAINS %@", "消息输入")).firstMatch
        XCTAssertTrue(input.waitForExistence(timeout: 10))
        let restingY = input.frame.minY
        input.coordinate(withNormalizedOffset: CGVector(dx: 0.35, dy: 0.45)).tap()
        // Third-party iPhone keyboards can be visible without an XCUIElementTypeKeyboard node.
        let raised = XCTNSPredicateExpectation(predicate: NSPredicate { [weak self] _, _ in
            self?.app.keyboards.firstMatch.exists == true || input.frame.minY < restingY - 100
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [raised], timeout: 8), .completed, "Touching the composer must open the keyboard and move the input above it")
        input.typeText("keyboard regression")
        XCTAssertTrue((input.value as? String)?.contains("keyboard regression") == true, "The focused composer must accept typed text")
        attachScreenshot("composer-keyboard-open")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.2)).tap()
        let hidden = XCTNSPredicateExpectation(predicate: NSPredicate { [weak self] _, _ in self?.app.keyboards.count == 0 && input.frame.minY >= restingY - 20 }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [hidden], timeout: 5), .completed)
    }

    func testManualPairingInputsAcceptTouchTyping() {
        tapLabel("设备")
        tapLabel("或粘贴配对链接")
        let name = app.descendants(matching: .any).matching(NSPredicate(format: "(elementType == %d OR elementType == %d) AND label CONTAINS %@", XCUIElement.ElementType.textField.rawValue, XCUIElement.ElementType.textView.rawValue, "手机名称")).firstMatch
        XCTAssertTrue(name.waitForExistence(timeout: 10))
        name.coordinate(withNormalizedOffset: CGVector(dx: 0.4, dy: 0.5)).tap()
        name.typeText(" input check")
        XCTAssertTrue((name.value as? String)?.contains("input check") == true)
        attachScreenshot("pairing-keyboard-open")
        tapLabel("取消")
    }

    func testScannerReturnRestoresComposerInput() {
        tapLabel("设备")
        tapLabel("扫描配对二维码")
        app.tap()
        let cancelScanner = app.buttons.matching(NSPredicate(format: "label == %@", "取消")).firstMatch
        if cancelScanner.waitForExistence(timeout: 3) && cancelScanner.isHittable { cancelScanner.tap() }
        let returned = XCTNSPredicateExpectation(predicate: NSPredicate { [weak self] _, _ in
            self?.element("扫描配对二维码").isHittable == true
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [returned], timeout: 20), .completed)
        tapLabel("返回")
        let input = app.textViews.matching(NSPredicate(format: "label CONTAINS %@", "消息输入")).firstMatch
        XCTAssertTrue(input.waitForExistence(timeout: 10))
        let restingY = input.frame.minY
        input.coordinate(withNormalizedOffset: CGVector(dx: 0.35, dy: 0.45)).press(forDuration: 0.15)
        let raised = XCTNSPredicateExpectation(predicate: NSPredicate { [weak self] _, _ in
            self?.app.keyboards.firstMatch.exists == true || input.frame.minY < restingY - 100
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [raised], timeout: 8), .completed)
        input.typeText("scanner return input")
        XCTAssertTrue((input.value as? String)?.contains("scanner return input") == true)
        attachScreenshot("scanner-return-composer-keyboard")
    }

    /// Uses the real deterministic DSH fixture session on the paired, isolated QA host.
    func testOptionalReasoningDisclosure() throws {
        guard ProcessInfo.processInfo.environment["DSH_UI_PRESENTATION"] == "1" else {
            throw XCTSkip("Prepare the isolated DSH fixture and set DSH_UI_PRESENTATION=1")
        }
        tapLabel("菜单")
        let search = app.descendants(matching: .any).matching(NSPredicate(format: "(elementType == %d OR elementType == %d) AND label CONTAINS %@", XCUIElement.ElementType.textField.rawValue, XCUIElement.ElementType.textView.rawValue, "搜索对话")).firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        search.tap()
        search.typeText("Mobile smoke session")
        let session = app.staticTexts.matching(NSPredicate(format: "label == %@", "Mobile smoke session")).firstMatch
        XCTAssertTrue(session.waitForExistence(timeout: 20))
        session.tap()
        let answer = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Mobile round trip verified.")).firstMatch
        XCTAssertTrue(answer.waitForExistence(timeout: 20))
        let firstThought = app.buttons.matching(NSPredicate(format: "label CONTAINS %@ AND value == %@", "Checking the mobile approval", "已收起")).firstMatch
        for _ in 0..<4 { if firstThought.exists && firstThought.isHittable { break }; app.swipeDown() }
        XCTAssertTrue(firstThought.waitForExistence(timeout: 10))
        attachScreenshot("conversation-collapsed")
        firstThought.tap()
        let body = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Checking the mobile approval and tool flow.")).firstMatch
        XCTAssertTrue(body.waitForExistence(timeout: 5))
        let openedThought = app.buttons.matching(NSPredicate(format: "label == %@ AND value == %@", "思考", "已展开")).firstMatch
        XCTAssertTrue(openedThought.waitForExistence(timeout: 5))
        attachScreenshot("conversation-expanded")
        openedThought.tap()
        XCTAssertTrue(firstThought.waitForExistence(timeout: 5))
        XCTAssertTrue(answer.exists, "Reasoning disclosure must not remove the final answer")
    }

    func testOptionalRichConversationAndEffort() throws {
        guard ProcessInfo.processInfo.environment["DSH_UI_PRESENTATION"] == "1" else { throw XCTSkip("Prepare the isolated rich conversation fixture") }
        tapLabel("菜单")
        let session = app.staticTexts["对话排版验证"]
        XCTAssertTrue(session.waitForExistence(timeout: 20))
        session.tap()
        XCTAssertTrue(element("复制代码").waitForExistence(timeout: 20))
        attachScreenshot("conversation-rich-text")
        app.swipeDown()
        attachScreenshot("conversation-process")
        let effortPicker = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "思考强度")).firstMatch
        XCTAssertTrue(effortPicker.waitForExistence(timeout: 10))
        effortPicker.tap()
        attachScreenshot("model-reasoning-before-selection")
        let maximum = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Max")).firstMatch
        XCTAssertTrue(maximum.waitForExistence(timeout: 10))
        maximum.tap()
        let accepted = app.buttons.matching(NSPredicate(format: "label CONTAINS %@ AND label CONTAINS %@", "思考强度", "Max")).firstMatch
        XCTAssertTrue(accepted.waitForExistence(timeout: 10), "Accepted effort must come back through the desktop projection")
        attachScreenshot("horizontal-model-and-effort")

    }

    func testOptionalSidebarWorkspaceAndPhotos() throws {
        guard ProcessInfo.processInfo.environment["DSH_UI_PRESENTATION"] == "1" else { throw XCTSkip("Requires the isolated paired host") }
        tapLabel("菜单")
        let opened = XCTNSPredicateExpectation(predicate: NSPredicate(format: "hittable == true"), object: element("收起菜单"))
        XCTAssertEqual(XCTWaiter.wait(for: [opened], timeout: 5), .completed)
        attachScreenshot("sidebar-final")
        let session = app.staticTexts["对话排版验证"]
        XCTAssertTrue(session.waitForExistence(timeout: 10))
        session.tap()
        XCTAssertTrue(app.buttons["菜单"].waitForExistence(timeout: 5))
        let closeButton = element("收起菜单")
        let closed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !closeButton.exists || closeButton.frame.maxX <= 0 }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [closed], timeout: 5), .completed, "Selecting a conversation must dismiss the drawer")
        tapLabel("工作区")
        XCTAssertTrue(element("添加电脑上的工作区").waitForExistence(timeout: 10))
        attachScreenshot("workspaces-final")
        tapLabel("文件")
        XCTAssertTrue(element("hello.txt").waitForExistence(timeout: 10))
        attachScreenshot("files-final")
        tapLabel("返回")
        tapLabel("附件")
        XCTAssertTrue(element("照片图库").waitForExistence(timeout: 5))
        XCTAssertTrue(element("选择文件").exists)
        attachScreenshot("attachment-menu")
        tapLabel("照片图库")
        let photo = app.images.matching(NSPredicate(format: "identifier == %@ AND label BEGINSWITH %@", "PXGGridLayout-Info", "截屏")).firstMatch
        XCTAssertTrue(photo.waitForExistence(timeout: 15), "The system Photos picker must open with the simulator fixture image")
        attachScreenshot("system-photo-picker")
        // Photos uses a custom grid whose image AX nodes have no activation point.
        photo.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        let add = app.buttons.matching(NSPredicate(format: "label IN %@", ["完成", "Done", "添加", "Add"])).firstMatch
        XCTAssertTrue(add.waitForExistence(timeout: 5))
        add.tap()
        XCTAssertTrue(element("移除").waitForExistence(timeout: 30), "Selected photo must upload and become a removable attachment")
        attachScreenshot("photo-attached")
        tapLabel("移除")
    }

    func testOptionalKeyboardDismissalAndWorkspaceHeader() throws {
        guard ProcessInfo.processInfo.environment["DSH_UI_PRESENTATION"] == "1" else { throw XCTSkip("Requires the isolated paired host") }
        tapLabel("菜单")
        let session = app.staticTexts["对话排版验证"]
        XCTAssertTrue(session.waitForExistence(timeout: 10))
        session.tap()
        XCTAssertTrue(app.staticTexts["workspace"].waitForExistence(timeout: 10))
        let connectionSubtitle = app.staticTexts.matching(NSPredicate(format: "label == %@", "Mac · 直连"))
        XCTAssertEqual(connectionSubtitle.count, 0)
        let input = app.textViews.matching(NSPredicate(format: "label CONTAINS %@", "消息输入")).firstMatch
        XCTAssertTrue(input.waitForExistence(timeout: 10))
        input.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        app.staticTexts["对话排版验证"].tap()
        let hidden = XCTNSPredicateExpectation(predicate: NSPredicate { [weak self] _, _ in self?.app.keyboards.count == 0 }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [hidden], timeout: 5), .completed)
        attachScreenshot("workspace-header-keyboard-dismissed")
    }

    func testOptionalDesktopTurnMetadataAndTrace() throws {
        guard ProcessInfo.processInfo.environment["DSH_UI_PRESENTATION"] == "1" else { throw XCTSkip("Requires the desktop presentation fixture") }
        tapLabel("菜单")
        let session = app.staticTexts["同步状态验证"]
        XCTAssertTrue(session.waitForExistence(timeout: 20))
        session.tap()
        let usage = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "用量 44 tok")).firstMatch
        XCTAssertTrue(usage.waitForExistence(timeout: 20), "Usage must arrive from the desktop presentation payload")
        usage.tap()
        XCTAssertTrue(element("本轮用量").waitForExistence(timeout: 5))
        XCTAssertTrue(element("44").exists)
        attachScreenshot("desktop-token-usage")
        tapLabel("关闭")
        let like = element("好的回答")
        XCTAssertTrue(like.waitForExistence(timeout: 10))
        let expected = (like.value as? String) == "已赞" ? "未评价" : "已赞"
        like.tap()
        let selected = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in (like.value as? String) == expected }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [selected], timeout: 10), .completed)
        attachScreenshot("desktop-feedback-synced")
        like.tap()
        tapLabel("查看对话轨迹")
        XCTAssertTrue(element("对话轨迹").waitForExistence(timeout: 10))
        XCTAssertTrue(element("搜索轨迹").exists)
        attachScreenshot("trajectory-dialog")
        tapLabel("关闭轨迹")
        XCTAssertTrue(app.buttons["菜单"].waitForExistence(timeout: 5))
    }

    /// Opt in after placing a short-lived invitation on this simulator's pasteboard with simctl pbcopy.
    /// The test never reads or logs the invitation and never uses typeText for credential-bearing text.
    func testOptionalPairFromPasteboardAndCreateConversation() throws {
        guard ProcessInfo.processInfo.environment["DSH_UI_PAIRING"] == "1" else {
            throw XCTSkip("Set DSH_UI_PAIRING=1 and prepare the simulator pasteboard to run live pairing")
        }
        tapLabel("设备")
        tapLabel("或粘贴配对链接")
        let field = app.descendants(matching: .any).matching(
            NSPredicate(format: "(elementType == %d OR elementType == %d) AND label CONTAINS %@",
                XCUIElement.ElementType.textField.rawValue, XCUIElement.ElementType.textView.rawValue,
                "或粘贴配对链接")
        ).firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10), "Pairing input must be accessible")
        field.tap()
        field.press(forDuration: 1.2)
        let paste = app.menuItems.matching(NSPredicate(format: "label IN %@", ["粘贴", "Paste"])).firstMatch
        XCTAssertTrue(paste.waitForExistence(timeout: 5), "Prepare the invitation with simctl pbcopy before this test")
        paste.tap()
        let connect = app.buttons.matching(NSPredicate(format: "label == %@", "连接并等待电脑确认")).firstMatch
        // The multiline invitation grows inside a LazyColumn while the keyboard is visible.
        // Scroll the card's outside edge, rather than scrolling the text inside the input.
        for _ in 0..<3 {
            if connect.exists && connect.isHittable { break }
            let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.05, dy: 0.48))
            let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.05, dy: 0.25))
            start.press(forDuration: 0.05, thenDragTo: end)
        }
        XCTAssertTrue(connect.waitForExistence(timeout: 5), "Scroll the pairing form to its connect button")
        let enabled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "enabled == true AND hittable == true"), object: connect)
        XCTAssertEqual(XCTWaiter.wait(for: [enabled], timeout: 10), .completed)
        connect.tap() // The app immediately clears its invitation input before starting the handshake.
        attachScreenshot("pairing-requested")
        let connected = app.staticTexts.matching(NSPredicate(format:
            "label CONTAINS %@ OR label CONTAINS %@ OR label CONTAINS %@", "· 直连", "· 加密中继", "已连接 ·")).firstMatch
        let errorTerms = ["Keychain", "failed", "error", "invalid", "expired", "rejected", "denied", "unsupported", "timeout",
            "失败", "错误", "过期", "拒绝", "无效", "不兼容", "超时", "无法", "未授权", "异常"]
        let errorPredicate = NSCompoundPredicate(orPredicateWithSubpredicates: errorTerms.map {
            NSPredicate(format: "label CONTAINS[c] %@", $0)
        })
        let errors = app.staticTexts.matching(errorPredicate)
        var pairingError: String?
        let connectedOrError = NSPredicate { _, _ in
            if errors.firstMatch.exists {
                pairingError = errors.firstMatch.label
                return true
            }
            return connected.exists
        }
        let outcome = XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: connectedOrError, object: nil)], timeout: 120)
        if let pairingError {
            attachScreenshot("pairing-error")
            let redacted = pairingError
                .replacingOccurrences(of: #"dsh-mobile://\S+"#, with: "<redacted invitation>", options: .regularExpression)
                .replacingOccurrences(of: #"[A-Za-z0-9_+/=-]{48,}"#, with: "<redacted token>", options: .regularExpression)
            XCTFail("Pairing reported an error: \(redacted.prefix(300))")
            return
        }
        XCTAssertEqual(outcome, .completed, "Approve the displayed pairing request on the desktop")
        XCTAssertTrue(connected.exists, "The desktop must confirm the authenticated connection")
        returnToTabsIfNeeded()
        tapLabel("新对话")
        XCTAssertTrue(element("继续你的想法").waitForExistence(timeout: 20), "The desktop must create and select a real conversation")
        XCTAssertTrue(element("发送").exists)
        attachScreenshot("paired-new-conversation")
        let input = app.textViews.matching(NSPredicate(format: "label CONTAINS %@", "消息输入")).firstMatch
        input.coordinate(withNormalizedOffset: CGVector(dx: 0.35, dy: 0.45)).press(forDuration: 0.15)
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 8), "Pairing dialogs must restore text input in the underlying screen")
        input.typeText("paired input regression")
        XCTAssertTrue((input.value as? String)?.contains("paired input regression") == true)
        attachScreenshot("paired-composer-keyboard-open")
    }

    private func element(_ label: String) -> XCUIElement {
        let buttons = app.buttons.matching(NSPredicate(format: "label == %@", label))
        if buttons.firstMatch.exists { return buttons.firstMatch }
        return app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", label)).firstMatch
    }

    private func tapLabel(_ label: String) {
        if ["设备", "设置", "工作区"].contains(label), !element(label).isHittable {
            if app.buttons["返回"].exists { app.buttons["返回"].tap() }
            let menu = app.buttons["菜单"]
            XCTAssertTrue(menu.waitForExistence(timeout: 10))
            menu.tap()
        }
        let target = element(label)
        XCTAssertTrue(target.waitForExistence(timeout: 10), "Missing UI control: \(label)")
        target.tap()
    }

    private func returnToTabsIfNeeded() {
        let back = app.buttons["返回"]
        if back.waitForExistence(timeout: 2) { back.tap() }
    }

    private func attachScreenshot(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
