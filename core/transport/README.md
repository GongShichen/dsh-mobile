# Native iroh transport

The Android and iOS implementations use the real official iroh 1.2.0 bindings
(which pin the Rust core to 1.4.1). `HLPeerTransport` is the shared coroutine
interface. It carries the framing defined in the plugin repository's
`protocol/PROTOCOL.md` over ALPN `dsh-mobile/1`.

## Android

Run `scripts/HLBuildIrohAndroid.sh` from the repository root before packaging.
The script builds the pinned `third_party/iroh-ffi` Git submodule with a locked
Cargo dependency graph, copies generated Kotlin bindings, and stages real
`arm64-v8a` and `x86_64` libraries. Set `ANDROID_NDK_HOME` to a complete NDK r27+
installation when it differs from the default. Libraries use 16 KiB ELF alignment.
The app must call `initializeAndroidPlatform(applicationContext)` before using
the secure store. Construct `HLAndroidIrohTransport(applicationContext)` to initialize
iroh's Android DNS/JNI context before binding an endpoint.

`./gradlew :core:transport:connectedAndroidDeviceTest` runs the native loopback
tests on a connected emulator/device. They exercise JNI loading, QUIC bidirectional
streams in both directions, cancellation, and rejecting a ticket for a different
desktop. These tests do not establish cross-network reachability.

For the opt-in **real Mac plugin → Android JNI** check, start the isolated official
DSH integration profile and run `node scripts/HLSmokeMacAndroid.mjs`. It reads the
private `.local/integration/auth.json` from the sibling plugin repository
(`DSH_SMOKE_AUTH_FILE` can override the path). It obtains a fresh invitation, sends
it over stdin into the instrumentation package's private storage, approves only
its uniquely named test device, and exercises session creation, rename, state,
follow, and reconnect using the same native identity. The invitation and browser
cookie never appear in command arguments or logs. The test archives its session
and the script revokes the test device. Results go to
`.local/mac-android-smoke-result.json`, including the observed direct/relay paths.
This check requires no model API key and does not generate model responses.

## iOS

The app links the official `IrohLib` SwiftPM package, pinned to 1.2.0, through
`iosApp/project.yml`. `iosApp/Sources/HLIrohBridge.swift` implements the callback
interfaces exported by the shared module. Inject
`HLBridgePeerTransport(bridge: HLIrohBridge())` into the application graph.
Callbacks are converted to cancellable suspending operations in shared Kotlin;
native cancellation and late-result cleanup close the underlying streams and
endpoint. iOS 17.5 or newer is required by the official binary package.

## Ownership and security

Each successful connection owns its endpoint. `HLPeerConnection.close()` releases
watchers, streams, and the endpoint; the caller must invoke it when a session is
replaced or the app suspends. The persistent 32-byte identity seed comes from the
platform secure store. A ticket's identity and the TLS-authenticated remote ID
must both match the paired desktop before the connection is returned.

The standard N0 preset enables public discovery and encrypted relay fallback.
`HLPeerConnection.path` reports the selected direct/relay path. It is connection
diagnostics, not a claim of guaranteed direct connectivity or background uptime.
