import SwiftUI
import HLShared
import Network

@main
struct HLApp: App {
    @UIApplicationDelegateAdaptor(HLIOSPushService.self) private var pushService
    @Environment(\.scenePhase) private var scenePhase
    private let uiBridge: HLIOSPlatformServices
    private let transport: HLIrohBridge
    private let runtime: HLIOSAppRuntime
    private let networkMonitor: NWPathMonitor

    init() {
        HLNativeGlassButtonsKt.installGlassButtonFactory(factory: HLIOSGlassButtons())
        let ui = HLIOSPlatformServices()
        let transport = HLIrohBridge()
        self.uiBridge = ui
        self.transport = transport
        let runtime = HLIOSAppRuntimeKt.installIosApp(transportBridge: transport, uiBridge: ui, remotePushEnabled: HLIOSPushCapability.isEnabled)
        self.runtime = runtime
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { path in
            if path.status == .satisfied { DispatchQueue.main.async { runtime.networkAvailable() } }
        }
        monitor.start(queue: DispatchQueue(label: "io.dsh.mobile.network"))
        self.networkMonitor = monitor
    }

    var body: some Scene {
        WindowGroup {
            HLSharedScreen().ignoresSafeArea()
                .onAppear {
                    pushService.attach(runtime: runtime)
                    runtime.onForeground()
                }
                .onChange(of: scenePhase) { _, phase in
                    if phase == .active { runtime.onForeground() }
                    else if phase == .background { runtime.onBackground() }
                }
        }
    }
}

private struct HLSharedScreen: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        HLMainViewControllerKt.HLMainViewController()
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
