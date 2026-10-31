import XCTest
import UIKit

/// Opt-in UI test against the real application, native iroh transport and an isolated official Host.
@MainActor
final class HLIOSInteractionFlowTests: XCTestCase {
    private struct HLConfiguration: Decodable {
        let uri: String
        let endpointId: String
        let originalHostId: String?
        let hostNames: [String: String]
        let modes: [String]
        let restoreOnly: Bool?
    }
    private enum HLFailure: Error { case missingControl(String), invalidState(String) }
    private let app = XCUIApplication()
    private var configuration: HLConfiguration?

    private func stage(_ value: String) {
        FileHandle.standardOutput.write(Data("HL_IOS_QA:\(value)\n".utf8))
    }
    private func element(_ text: String, within parent: XCUIElement? = nil) -> XCUIElement {
        let buttons = app.buttons.matching(NSPredicate(format: "label == %@", text))
        let query = buttons.firstMatch.exists ? buttons : app.descendants(matching: .any).matching(NSPredicate(format: "label == %@ OR identifier == %@", text, text))
        guard let parent else { return query.firstMatch }
        // Compose accessibility children may be flattened; bind actions to the original heading.
        return query.allElementsBoundByIndex.first { value in
            let frame = value.frame
            if text == "电脑操作" { return abs(frame.midY - parent.frame.midY) < 60 }
            return frame.midY > parent.frame.midY && frame.midY < parent.frame.midY + 180
        } ?? app.buttons["hl.missing-control"]
    }
    private func wait(_ text: String, within parent: XCUIElement? = nil, timeout: TimeInterval = 30) throws -> XCUIElement {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            let value = element(text, within: parent)
            if value.exists && (value.elementType != .button || (value.isEnabled && value.isHittable)) { return value }
            Thread.sleep(forTimeInterval: 0.15)
        } while Date() < deadline
        throw HLFailure.missingControl(text)
    }
    private func tap(_ text: String, within parent: XCUIElement? = nil) throws {
        guard app.state == .runningForeground else { throw HLFailure.invalidState("Harness Link must stay in the foreground") }
        try wait(text, within: parent).tap()
        if ["菜单", "返回", "设备", "设置"].contains(text) { Thread.sleep(forTimeInterval: 0.65) }
    }
    private func awaitClosed() throws {
        let closed = XCTNSPredicateExpectation(predicate: NSPredicate { [weak self] _, _ in
            self?.element("稍后处理").exists == false
        }, object: nil)
        guard XCTWaiter.wait(for: [closed], timeout: 15) == .completed else { throw HLFailure.invalidState("The settled dialog did not close") }
    }
    private func capture(_ name: String) {
        guard app.state == .runningForeground else { return }
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "hl-interaction-\(name)"; attachment.lifetime = .keepAlways; add(attachment)
    }
    private func reveal(_ value: XCUIElement, container: XCUIElement? = nil) throws {
        let area = container ?? app
        for _ in 0..<7 {
            if value.exists && value.isHittable { return }
            let start = area.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.75))
            let end = area.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.3))
            start.press(forDuration: 0.05, thenDragTo: end)
        }
        throw HLFailure.invalidState("The requested control cannot be scrolled into view")
    }
    private func openDevices() throws {
        if element("稍后处理").exists { try tap("稍后处理") }
        if app.buttons.matching(NSPredicate(format: "label == %@", "取消")).firstMatch.exists { try tap("取消") }
        if element("返回").exists { try tap("返回") }
        if element("菜单").exists { try tap("菜单"); try tap("设备") }
        _ = try wait("或粘贴配对链接")
    }
    private func computer(_ id: String) throws -> XCUIElement {
        guard let name = configuration?.hostNames[id] else { throw HLFailure.invalidState("Unknown paired computer") }
        let value = app.staticTexts.matching(NSPredicate(format: "label == %@", name)).firstMatch
        let list = app.scrollViews.firstMatch
        if !(value.exists && value.isHittable) && list.exists { list.swipeDown() }
        try reveal(value, container: list.exists ? list : nil)
        return value
    }
    private func pair(_ config: HLConfiguration) throws {
        stage("pairing")
        try openDevices(); try tap("或粘贴配对链接")
        let field = app.descendants(matching: .any).matching(NSPredicate(format:
            "(elementType == %d OR elementType == %d) AND label CONTAINS %@", XCUIElement.ElementType.textField.rawValue,
            XCUIElement.ElementType.textView.rawValue, "或粘贴配对链接")).firstMatch
        guard field.waitForExistence(timeout: 10) else { throw HLFailure.missingControl("配对链接") }
        field.tap(); field.typeText(config.uri)
        stage("invitation-entered")
        let connect = element("连接并等待电脑确认")
        try reveal(connect); connect.tap()
        for owner in [app, XCUIApplication(bundleIdentifier: "com.apple.springboard")] {
            let alert = owner.alerts.firstMatch
            if alert.waitForExistence(timeout: 1) {
                let text = (alert.label + " " + alert.staticTexts.allElementsBoundByIndex.map(\.label).joined(separator: " ")).lowercased()
                if text.contains("local network") || text.contains("本地网络") {
                    for label in ["允许", "Allow"] where alert.buttons[label].exists { alert.buttons[label].tap(); break }
                }
            }
        }
        let paired = app.staticTexts.matching(NSPredicate(format: "label == %@", config.hostNames[config.endpointId]!)).firstMatch
        guard paired.waitForExistence(timeout: 40) else { throw HLFailure.invalidState("The intended QA computer was not paired") }
        let card = try computer(config.endpointId)
        _ = try wait("断开", within: card, timeout: 40)
        try tap("返回")
        stage("paired")
    }

    func testSynchronizeDesktopInteractions() throws {
        guard let raw = ProcessInfo.processInfo.environment["HL_IOS_INTERACTION_QA"], let data = raw.data(using: .utf8) else {
            throw XCTSkip("Prepare the isolated Host and private xctestrun configuration")
        }
        configuration = try JSONDecoder().decode(HLConfiguration.self, from: data)
        let config = configuration!
        continueAfterFailure = true
        app.launchArguments = ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        _ = try wait("菜单", timeout: 20)
        do {
            if config.restoreOnly == true { return }
            try pair(config)
            for mode in config.modes {
                if ["permission", "questions"].contains(mode) && element("菜单").exists { try tap("菜单"); try tap("设置") }
                stage("request:\(mode)")
                if !["questions", "skip", "timed"].contains(mode) { _ = try wait("来自：HL QA · \(mode)") }
                Thread.sleep(forTimeInterval: 0.35) // Let the previous modal finish dismissing before tapping the new request.
                switch mode {
                case "approval":
                    _ = try wait("允许这一次"); _ = try wait("来自：HL QA · approval")
                    capture("approval"); try tap("允许这一次"); try awaitClosed()
                case "reject": try tap("拒绝"); try awaitClosed()
                case "desktop", "cancel":
                    _ = try wait("允许这一次"); stage("\(mode)-visible"); try awaitClosed()
                case "reconnect":
                    _ = try wait("允许这一次"); try tap("稍后处理"); try openDevices()
                    try tap("断开", within: computer(config.endpointId))
                    try tap("连接", within: computer(config.endpointId))
                    try tap("允许这一次"); try awaitClosed(); try tap("返回")
                case "permission":
                    _ = try wait("是否允许访问测试资源？"); capture("permission")
                    try tap("始终允许"); try awaitClosed()
                case "plan":
                    _ = try wait("计划确认"); capture("plan")
                    try tap("同意测试计划"); try tap("提交决定"); try awaitClosed()
                case "questions":
                    _ = try wait("选择需要验证的范围"); try tap("消息同步"); try tap("权限确认")
                    let field = app.textViews.matching(NSPredicate(format: "label CONTAINS %@", "备注")).firstMatch
                    let body = element("待处理请求内容")
                    try reveal(field, container: body.exists ? body : nil)
                    Thread.sleep(forTimeInterval: 0.7) // A tap during scroll deceleration can only stop the scroll.
                    guard field.isEnabled else { throw HLFailure.invalidState("The question is not ready to accept an answer") }
                    let focused = app.descendants(matching: .any).matching(NSPredicate(format: "hasKeyboardFocus == true")).firstMatch
                    for _ in 0..<2 {
                        field.coordinate(withNormalizedOffset: CGVector(dx: 0.35, dy: 0.45)).press(forDuration: 0.15)
                        if focused.waitForExistence(timeout: 3) { break }
                    }
                    capture("questions-focus")
                    guard focused.exists else { throw HLFailure.invalidState("The question field did not acquire keyboard focus") }
                    field.typeText("HL iOS physical answer")
                    guard (field.value as? String)?.contains("HL iOS physical answer") == true else { throw HLFailure.invalidState("The answer field did not accept input") }
                    let title = try wait("需要你的回答")
                    guard title.isHittable && title.frame.minY >= app.frame.minY else { throw HLFailure.invalidState("The keyboard obscured the dialog header") }
                    capture("questions-keyboard"); title.tap()
                    try tap("提交回答"); try awaitClosed()
                case "skip": _ = try wait("可选测试问题"); try tap("跳过此题"); try tap("提交回答"); try awaitClosed()
                case "timed":
                    _ = try wait("请补充测试结论"); _ = try wait("等待你的回答，倒计时已暂停")
                    Thread.sleep(forTimeInterval: 4)
                    _ = try wait("请补充测试结论"); stage("timed-held")
                    try tap("稍后处理"); Thread.sleep(forTimeInterval: 4)
                    try tap("查看待处理"); _ = try wait("这条询问仍可补答，提交后会继续原会话。")
                    capture("continued"); try tap("补答已完成"); try tap("提交回答"); try awaitClosed()
                default: throw HLFailure.invalidState("Unsupported fixture mode")
                }
                stage("answered:\(mode)")
            }
            stage("complete")
        } catch { capture("failure"); throw error }
    }

    override func tearDownWithError() throws {
        guard let config = configuration else { return }
        app.activate(); try openDevices()
        let qa = app.staticTexts.matching(NSPredicate(format: "label == %@", config.hostNames[config.endpointId]!)).firstMatch
        if qa.exists {
            let card = try computer(config.endpointId)
            try tap("电脑操作", within: card); try tap("移除电脑"); try tap("移除")
        }
        if let original = config.originalHostId {
            let card = try computer(original)
            if element("连接", within: card).exists { try tap("连接", within: card) }
            _ = try wait("断开", within: card, timeout: 40)
        }
        capture("restored"); stage("restored")
        try super.tearDownWithError()
    }
}
