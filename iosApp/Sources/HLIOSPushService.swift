import Foundation
import UIKit
import UserNotifications
import HLShared

/// APNs registration belongs to the signed iOS application, never to Firebase or the desktop's signing team.
final class HLIOSPushService: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    private var runtime: HLIOSAppRuntime?
    private var deviceToken: Data?
    private var pendingNotification: [AnyHashable: Any]?

    func attach(runtime: HLIOSAppRuntime) {
        self.runtime = runtime
        publishToken()
        if let pendingNotification { self.pendingNotification = nil; open(pendingNotification) }
    }

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        guard HLIOSPushCapability.isEnabled else { return true }
        UNUserNotificationCenter.current().delegate = self
        if let notification = launchOptions?[.remoteNotification] as? [AnyHashable: Any] { pendingNotification = notification }
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            if settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional || settings.authorizationStatus == .ephemeral {
                DispatchQueue.main.async { application.registerForRemoteNotifications() }
            }
        }
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        guard HLIOSPushCapability.isEnabled else { return }
        self.deviceToken = deviceToken
        publishToken()
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        // A missing push entitlement (including an unsigned simulator build) leaves push unconfigured.
        // The foreground P2P client remains usable and the settings flow does not report a token as registered.
        deviceToken = nil
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound, .list])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        DispatchQueue.main.async { [self] in
            open(response.notification.request.content.userInfo)
            completionHandler()
        }
    }

    private func open(_ userInfo: [AnyHashable: Any]) {
        guard HLIOSPushCapability.isEnabled else { return }
        guard let host = userInfo["hostId"] as? String, let event = userInfo["eventId"] as? String else { return }
        guard let runtime else { pendingNotification = userInfo; return }
        runtime.receivePush(hostId: host, eventId: event, sessionId: userInfo["sessionId"] as? String)
    }

    private func publishToken() {
        guard HLIOSPushCapability.isEnabled else { return }
        guard let deviceToken, let runtime, let bundleID = Bundle.main.bundleIdentifier else { return }
        guard let entitlementEnvironment = Bundle.main.object(forInfoDictionaryKey: "DSHAPNsEnvironment") as? String,
              ["development", "production"].contains(entitlementEnvironment) else { return }
        let token = deviceToken.map { String(format: "%02x", $0) }.joined()
        runtime.updatePushToken(token: token,
            environment: entitlementEnvironment == "development" ? "sandbox" : "production",
            applicationId: bundleID)
    }
}
