import XCTest

/// Live DSH and its configured model. This test never installs a model fixture.
@MainActor
final class HLPersonalLiveTests: XCTestCase {
    private struct HLConfiguration: Decodable { let uri: String; let name: String }
    private let app = XCUIApplication()
    private enum HLFailure: Error { case missing(String) }
    private func node(_ label: String) -> XCUIElement {
        let query = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@ OR identifier == %@ OR label BEGINSWITH %@", label, label, label + ","))
        return query.allElementsBoundByIndex.first { $0.isHittable } ?? query.firstMatch
    }
    private func tap(_ label: String) throws {
        let deadline = Date().addingTimeInterval(120)
        repeat { let value = node(label); if value.exists && value.isHittable { value.tap(); if ["菜单", "设备", "返回"].contains(label) { Thread.sleep(forTimeInterval: 0.65) }; return }; Thread.sleep(forTimeInterval: 0.2) } while Date() < deadline
        throw HLFailure.missing(label)
    }
    private func field(_ label: String) throws -> XCUIElement {
        for type in [XCUIElement.ElementType.textView, .textField] {
            let value = app.descendants(matching: type).matching(NSPredicate(format: "label CONTAINS %@", label)).firstMatch
            if value.waitForExistence(timeout: 5) { return value }
        }
        throw HLFailure.missing(label)
    }
    private func capture(_ name: String) {
        let image = XCTAttachment(screenshot: app.screenshot()); image.name = "hl-live-\(name)"; image.lifetime = .keepAlways; add(image)
    }
    private func send(_ text: String) throws { let input = try field("消息输入"); input.tap(); input.typeText(text); try tap("发送") }
    func testRealGoogleRequestAndPersistentOptOut() throws {
        guard let raw = ProcessInfo.processInfo.environment["HL_IOS_PERSONAL_LIVE"], let data = raw.data(using: .utf8) else { throw XCTSkip("Provide the live DSH pairing configuration") }
        let config = try JSONDecoder().decode(HLConfiguration.self, from: data)
        continueAfterFailure = false
        app.launchArguments = ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]; app.launch()
        do {
            try tap("菜单"); try tap("设备")
            for _ in 0..<6 { if node("或粘贴配对链接").exists && node("或粘贴配对链接").isHittable { break }; app.swipeUp() }
            try tap("或粘贴配对链接")
            let name = try field("手机名称"); name.tap()
            if let prior = name.value as? String { name.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: prior.count)) }
            name.typeText(config.name)
            let link = try field("或粘贴配对链接"); link.tap(); link.typeText(config.uri); try tap("连接并等待电脑确认")
            for owner in [app, XCUIApplication(bundleIdentifier: "com.apple.springboard")] {
                let alert = owner.alerts.firstMatch
                if alert.waitForExistence(timeout: 2) {
                    for label in ["允许", "Allow"] where alert.buttons[label].exists { alert.buttons[label].tap(); break }
                }
            }
            XCTAssertTrue(node("断开").waitForExistence(timeout: 50)); try tap("返回"); try tap("私人助理模式")
            XCTAssertFalse(node("不再提示").exists)
            try send("我现在要连接 Gmail。请实际调用 hl_personal_request_google，service 选择 gmail，在手机显示账户连接提示；不要只回复操作步骤，也不要发送邮件。")
            XCTAssertTrue(node("不再提示").waitForExistence(timeout: 120)); capture("google-dialog")
            try tap("不再提示"); app.terminate(); app.launch(); try tap("私人助理模式")
            try send("再查询明天的 Google 日历安排，只查看时间和标题，不要修改日程。")
            let result = node("连接 Google").waitForExistence(timeout: 35)
            _ = result
            XCTAssertFalse(node("不再提示").exists); capture("no-repeat")
            try tap("菜单"); try tap("账户与授权")
            XCTAssertTrue(node("恢复配置提醒").waitForExistence(timeout: 10)); capture("persistent-preference")
            try tap("完成")
        } catch { capture("failure"); throw error }
    }
}
