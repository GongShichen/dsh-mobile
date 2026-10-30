# Third-party components

This project's MIT license applies to its original source. Dependencies retain their own licenses.

- **iroh / iroh-ffi / IrohLib**, n0-computer: MIT OR Apache-2.0. The exact source is pinned by `third_party/iroh-ffi`; Android bindings are copied from that source, not authored here. License texts are retained in the submodule and packaged with the mobile applications.
- **Kotlin, Compose Multiplatform, Coroutines, kotlinx.serialization**, JetBrains and contributors: Apache-2.0.
- **AndroidX, Firebase Android SDK**, Google and contributors: Apache-2.0.
- **SQLDelight**, SQLDelight contributors: Apache-2.0.
- **Multiplatform Markdown Renderer**, Mike Penz, and **JetBrains Markdown**: Apache-2.0. Native GFM rendering and parsing; pinned renderer 0.46.0.
- **JNA**, JNA contributors: LGPL-2.1-or-later OR Apache-2.0; used under Apache-2.0.
- **ZXing Android Embedded**, JourneyApps: Apache-2.0.

The iroh native dependency closure is recorded in the pinned upstream `Cargo.lock`; the Gradle version catalog and resolved dependencies identify the mobile JVM/Kotlin closure. Rebuilding or upgrading dependencies requires retaining their attribution and license files.
