import SwiftUI

@main
struct PhoneRemoteApp: App {
    @StateObject private var ble = BluetoothManager()
    @StateObject private var settings = FeatureSettings()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(ble)
                .environmentObject(settings)
        }
    }
}
