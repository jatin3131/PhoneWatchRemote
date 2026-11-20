import SwiftUI
import Contacts
import Combine

/// Tells the watch what may be shared, and sends the phonebook when allowed and when it changed.
final class ContactsManager: ObservableObject {

    @Published var statusText = ""

    private weak var ble: BluetoothManager?
    private var settings: FeatureSettings?
    private var cancellables = Set<AnyCancellable>()

    func attach(ble: BluetoothManager, settings: FeatureSettings) {
        self.ble = ble
        self.settings = settings
        ble.onContactsEvent = { [weak self] text in
            if text == "CR" {
                DispatchQueue.main.async {
                    self?.syncIfAllowed(force: true)
                }
            }
        }
        if cancellables.isEmpty {
            ble.$linkReady
                .receive(on: DispatchQueue.main)
                .sink { [weak self] ready in
                    if ready {
                        self?.syncIfAllowed()
                    }
                }
                .store(in: &cancellables)
        }
    }

    func syncIfAllowed(force: Bool = false) {
        guard let ble = ble, ble.linkReady, let settings = settings else { return }
        let calls = settings.isOn("calls")
        let contacts = settings.isOn("contacts")
        ble.send("S \(calls ? 1 : 0) \(contacts ? 1 : 0)")
        guard contacts else {
            statusText = "not shared"
            return
        }
        let status = CNContactStore.authorizationStatus(for: .contacts)
        if status == .authorized {
            sendContacts(force: force)
            return
        }
        if status == .notDetermined {
            CNContactStore().requestAccess(for: .contacts) { granted, _ in
                DispatchQueue.main.async {
                    if granted {
                        self.sendContacts(force: true)
                    } else {
                        self.statusText = "access denied"
                    }
                }
            }
            return
        }
        if #available(iOS 18.0, *) {
            if status == .limited {
                sendContacts(force: force)
                return
            }
        }
        statusText = "denied in Settings"
    }

    private func fnv(_ data: Data) -> String {
        var h: UInt64 = 14695981039346656037
        for b in data {
            h ^= UInt64(b)
            h = h &* 1099511628211
        }
        return String(h)
    }

    private func sendContacts(force: Bool) {
        DispatchQueue.global(qos: .utility).async {
            let keys: [CNKeyDescriptor] = [
                CNContactGivenNameKey as CNKeyDescriptor,
                CNContactFamilyNameKey as CNKeyDescriptor,
                CNContactOrganizationNameKey as CNKeyDescriptor,
                CNContactPhoneNumbersKey as CNKeyDescriptor
            ]
            let request = CNContactFetchRequest(keysToFetch: keys)
            var lines: [String] = []
            do {
                try CNContactStore().enumerateContacts(with: request) { contact, stop in
                    var name = [contact.givenName, contact.familyName]
                        .filter { !$0.isEmpty }
                        .joined(separator: " ")
                    if name.isEmpty {
                        name = contact.organizationName
                    }
                    guard !name.isEmpty, let phone = contact.phoneNumbers.first?.value.stringValue else { return }
                    let number = phone.filter { $0.isNumber || $0 == "+" }
                    if number.isEmpty { return }
                    let cleanName = name
                        .replacingOccurrences(of: "\t", with: " ")
                        .replacingOccurrences(of: "\n", with: " ")
                    lines.append(String(cleanName.prefix(40)) + "\t" + number)
                    if lines.count >= 1500 {
                        stop.pointee = true
                    }
                }
            } catch {
                DispatchQueue.main.async {
                    self.statusText = "could not read contacts"
                }
                return
            }
            let payload = lines.joined(separator: "\n").data(using: .utf8) ?? Data()
            let count = lines.count
            let hash = self.fnv(payload)
            DispatchQueue.main.async {
                if !force && UserDefaults.standard.string(forKey: "contacts_hash") == hash {
                    self.statusText = "up to date (\(count))"
                    return
                }
                UserDefaults.standard.set(hash, forKey: "contacts_hash")
                self.ble?.sendMessage(type: 0x43, payload: payload)
                self.statusText = "sent \(count) contacts"
            }
        }
    }
}
