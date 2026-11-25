import SwiftUI

@main
struct PhoneRemoteApp: App {
    @StateObject private var ble = BluetoothManager()
    @StateObject private var settings = FeatureSettings()
    @StateObject private var remote = RemoteModel()
    @StateObject private var music = MusicManager()
    @StateObject private var contacts = ContactsManager()
    @StateObject private var artwork = ArtworkManager()
    @StateObject private var island = IslandManager()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(ble)
                .environmentObject(settings)
                .environmentObject(remote)
                .environmentObject(music)
                .environmentObject(contacts)
                .environmentObject(artwork)
                .environmentObject(island)
        }
    }
}
