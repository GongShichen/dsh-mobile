import SwiftUI
import DshMobile
import Network

@main
struct DshMobileApp: App {
    @UIApplicationDelegateAdaptor(IOSPushService.self) private var pushService
    @Environment(\.scenePhase) private var scenePhase
    private let uiBridge: IOSPlatformServices
    private let transport: IrohBridge
    private let runtime: IosAppRuntime
    private let networkMonitor: NWPathMonitor

    init() {
        NativeGlassButtonsKt.installGlassButtonFactory(factory: IOSGlassButtons())
        let ui = IOSPlatformServices()
        let transport = IrohBridge()
        self.uiBridge = ui
        self.transport = transport
        let runtime = IosAppRuntimeKt.installIosApp(transportBridge: transport, uiBridge: ui, remotePushEnabled: IOSPushCapability.isEnabled)
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
            SharedScreen().ignoresSafeArea()
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

private struct SharedScreen: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
