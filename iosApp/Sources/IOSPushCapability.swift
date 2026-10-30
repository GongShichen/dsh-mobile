import Foundation

/// Set by the selected signing variant; a Personal Team build keeps P2P but has no APNs entitlement.
enum IOSPushCapability {
    static let unavailableMessage = "当前签名不支持远程推送。电脑连接、会话和文件功能仍可正常使用。"

    static var isEnabled: Bool {
        guard let value = Bundle.main.object(forInfoDictionaryKey: "DSHPushEnabled") else { return true }
        if let number = value as? NSNumber { return number.boolValue }
        guard let text = value as? String else { return false }
        return ["YES", "TRUE", "1"].contains(text.uppercased())
    }
}
