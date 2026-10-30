import XCTest

/// Runs against the production app; transport and repositories are never replaced by test doubles.
final class DshMobileUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        addUIInterruptionMonitor(withDescription: "Requested camera or clipboard permission") { alert in
            for title in ["允许", "好", "OK", "Allow", "允许粘贴", "Allow Paste"] {
                let button = alert.buttons[title]
                if button.exists { button.tap(); return true }
            }
            return false
        }
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 15), "The app must remain in the foreground after launch")
        returnToTabsIfNeeded()
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

    /// Opt in after placing a short-lived invitation on this simulator's pasteboard with simctl pbcopy.
    /// The test never reads or logs the invitation and never uses typeText for credential-bearing text.
    func testOptionalPairFromPasteboardAndCreateConversation() throws {
        guard ProcessInfo.processInfo.environment["DSH_UI_PAIRING"] == "1" else {
            throw XCTSkip("Set DSH_UI_PAIRING=1 and prepare the simulator pasteboard to run live pairing")
        }
        tapLabel("设备")
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
        tapLabel("对话")
        tapLabel("新对话")
        XCTAssertTrue(element("继续你的想法").waitForExistence(timeout: 20), "The desktop must create and select a real conversation")
        XCTAssertTrue(element("发送").exists)
        attachScreenshot("paired-new-conversation")
    }

    private func element(_ label: String) -> XCUIElement {
        let buttons = app.buttons.matching(NSPredicate(format: "label == %@", label))
        if buttons.firstMatch.exists { return buttons.firstMatch }
        return app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", label)).firstMatch
    }

    private func tapLabel(_ label: String) {
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
