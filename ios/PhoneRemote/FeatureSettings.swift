import SwiftUI

final class FeatureSettings: ObservableObject {

    struct Feature: Identifiable {
        let id: String
        let title: String
        let detail: String
    }

    static let features: [Feature] = [
        Feature(id: "bluetooth", title: "Bluetooth link", detail: "Direct connection between iPhone and watch"),
        Feature(id: "media", title: "Media controls", detail: "What is playing in the Music app, shown and controlled from the watch"),
        Feature(id: "calls", title: "Call alerts", detail: "Show incoming calls on the watch. Answer or decline with the bezel."),
        Feature(id: "contacts", title: "Contacts sync", detail: "Send names and numbers to the watch phonebook (asks for Contacts permission)"),
        Feature(id: "proximity", title: "Proximity alert", detail: "Watch buzzes if the phone is left behind"),
        Feature(id: "gestures", title: "Watch gestures", detail: "Taps and swipes to navigate and toggle options"),
        Feature(id: "gallery", title: "Photo gallery", detail: "Browse photos with the watch touchpad"),
        Feature(id: "editor", title: "Photo editor remote", detail: "Bezel, swipes and tap to edit and save"),
        Feature(id: "notifications", title: "Watch notifications", detail: "Show phone alerts on the watch")
    ]

    @Published private(set) var values: [String: Bool] = [:]

    init() {
        let ids = Self.features.map { $0.id } + ["island"]
        for id in ids {
            let saved = UserDefaults.standard.object(forKey: "feature_" + id) as? Bool
            values[id] = saved ?? true
        }
    }

    func isOn(_ id: String) -> Bool {
        return values[id] ?? true
    }

    func binding(_ id: String) -> Binding<Bool> {
        return Binding(
            get: { self.values[id] ?? true },
            set: { newValue in
                self.values[id] = newValue
                UserDefaults.standard.set(newValue, forKey: "feature_" + id)
            }
        )
    }
}
