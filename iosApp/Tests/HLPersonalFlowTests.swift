import XCTest

/// Production UI and native P2P, against the disposable HL Personal QA Host only.
@MainActor
final class HLPersonalFlowTests: XCTestCase {
    private struct HLConfiguration: Decodable { let uri: String }
    private let app = XCUIApplication()
    private enum HLFailure: Error { case missing(String) }
    private func node(_ label: String) -> XCUIElement {
        let query = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@ OR identifier == %@ OR label BEGINSWITH %@", label, label, label + ","))
        return query.allElementsBoundByIndex.first { $0.isHittable } ?? query.firstMatch
    }
    private func tap(_ label: String) throws {
        let deadline = Date().addingTimeInterval(25)
        repeat {
            let value = node(label)
            if value.exists && value.isHittable {
                value.tap()
                if ["菜单", "设备", "返回"].contains(label) { Thread.sleep(forTimeInterval: 0.65) }
                return
            }
            Thread.sleep(forTimeInterval: 0.2)
        } while Date() < deadline
        throw HLFailure.missing(label)
    }
    private func field(_ label: String) throws -> XCUIElement {
        let value = app.textViews.matching(NSPredicate(format: "label CONTAINS %@", label)).firstMatch
        let other = app.textFields.matching(NSPredicate(format: "label CONTAINS %@", label)).firstMatch
        if value.waitForExistence(timeout: 3) { return value }
        guard other.waitForExistence(timeout: 5) else { throw HLFailure.missing(label) }
        return other
    }
    private func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot()); attachment.name = "hl-personal-\(name)"; attachment.lifetime = .keepAlways; add(attachment)
    }
    func testModesAndTaskManagement() throws {
        guard let raw = ProcessInfo.processInfo.environment["HL_IOS_PERSONAL_QA"], let data = raw.data(using: .utf8) else { throw XCTSkip("Prepare the isolated Personal QA Host") }
        let config = try JSONDecoder().decode(HLConfiguration.self, from: data)
        continueAfterFailure = false
        app.launchArguments = ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]; app.launch()
        do {
            try tap("菜单"); try tap("设备")
            for _ in 0..<5 {
                if node("或粘贴配对链接").exists && node("或粘贴配对链接").isHittable { break }
                app.swipeUp()
            }
            try tap("或粘贴配对链接")
            let name = try field("手机名称"); name.tap()
            if let old = name.value as? String, !old.isEmpty { name.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: old.count)) }
            name.typeText("HL Personal iOS QA")
            let link = try field("或粘贴配对链接"); link.tap(); link.typeText(config.uri)
            try tap("连接并等待电脑确认")
            for owner in [app, XCUIApplication(bundleIdentifier: "com.apple.springboard")] {
                let alert = owner.alerts.firstMatch
                if alert.waitForExistence(timeout: 2) {
                    for label in ["允许", "Allow"] where alert.buttons[label].exists { alert.buttons[label].tap(); break }
                }
            }
            XCTAssertTrue(node("Harness Link Personal QA").waitForExistence(timeout: 40))
            XCTAssertTrue(node("断开").waitForExistence(timeout: 40)); try tap("返回")
            try tap("私人助理模式")
            let composer = try field("消息输入"); composer.tap(); composer.typeText("HL private iOS draft")
            capture("keyboard")
            try tap("菜单"); try tap("设备")
            for _ in 0..<8 {
                if node("断开").exists && node("断开").isHittable { break }
                app.swipeUp()
            }
            try tap("断开"); try tap("返回")
            let offline = try field("消息输入"); offline.tap(); offline.typeText(" offline")
            XCTAssertTrue((offline.value as? String)?.contains(" offline") == true)
            capture("offline"); try tap("重新连接")
            let connected = XCTNSPredicateExpectation(predicate: NSPredicate { [weak self] _, _ in self?.node("重新连接").exists == false }, object: nil)
            XCTAssertEqual(XCTWaiter.wait(for: [connected], timeout: 35), .completed)
            try tap("会话模式"); try tap("私人助理模式")
            let restored = try field("消息输入")
            XCTAssertEqual(restored.value as? String, "HL private iOS draft offline")
            restored.tap(); restored.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: "HL private iOS draft offline".count))
            restored.typeText("HL_QA:personal:\(UUID().uuidString.lowercased())")
            try tap("发送")
            try tap("事务")
            XCTAssertTrue(node("HL QA 对话交办").waitForExistence(timeout: 40)); capture("tasks")
            try tap("目标"); XCTAssertTrue(node("阅读与学习").waitForExistence(timeout: 10)); XCTAssertTrue(node("产品迭代").exists)
            capture("goals"); try tap("完成")
            try tap("会话模式"); try tap("私人助理模式")
            XCTAssertTrue((try field("消息输入")).exists)
            capture("assistant")
        } catch { capture("failure"); throw error }
    }
}
