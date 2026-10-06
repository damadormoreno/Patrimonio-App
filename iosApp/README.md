# iosApp

SwiftUI wrapper around the `composeApp` Kotlin Multiplatform framework (standard JetBrains KMP
wizard layout). `ContentView.swift` bridges to the shared UI via
`MainViewControllerKt.MainViewController()` from the `ComposeApp` framework.

- Bundle id: `com.denebapps.patrimonio`. Set your Team in *Signing & Capabilities* before
  running on a physical device.
- Kotlin/Native caches are disabled for `iosArm64` in `gradle.properties`
  (`kotlin.native.cacheKind.iosArm64=none`) — required for physical-device builds.
- App data (Room DB + DataStore) lives under Application Support, which is included in iCloud
  device backups.
