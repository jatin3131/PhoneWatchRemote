import Foundation
import CoreBluetooth

struct FoundDevice: Identifiable {
    let id: UUID
    let name: String
    var rssi: Int
    let peripheral: CBPeripheral
}

final class BluetoothManager: NSObject, ObservableObject, CBCentralManagerDelegate, CBPeripheralDelegate {

    /// These must match the UUIDs in the watch app.
    static let watchServiceUUID = CBUUID(string: "6E5A0001-B5A3-F393-E0A9-E50E24DCCA9E")
    static let eventUUID = CBUUID(string: "6E5A0002-B5A3-F393-E0A9-E50E24DCCA9E")
    static let stateUUID = CBUUID(string: "6E5A0003-B5A3-F393-E0A9-E50E24DCCA9E")
    static let restoreID = "phoneremote.central"

    /// When true, the list shows every named Bluetooth device. Otherwise only watches running our app.
    @Published var scanAll = false
    @Published var state: CBManagerState = .unknown
    @Published var devices: [FoundDevice] = []
    @Published var isScanning = false
    @Published var connectedName: String? = nil
    @Published var statusText = ""
    @Published var linkReady = false
    @Published var savedName: String
    @Published var hasSavedWatch: Bool
    @Published var autoConnect: Bool

    /// Called with each short message the watch sends (gallery and editor).
    var onEvent: ((String) -> Void)?
    /// Called with each short message the watch sends (music).
    var onMusicEvent: ((String) -> Void)?
    /// Called with each short message the watch sends (contacts).
    var onContactsEvent: ((String) -> Void)?
    /// Called with each longer message the watch sends (type, payload).
    var onFrame: ((UInt8, Data) -> Void)?

    private var frameBuffer = Data()
    private var frameTotal = 0
    private var frameType: UInt8 = 0

    private var central: CBCentralManager!
    private var connecting: CBPeripheral?
    private var eventChar: CBCharacteristic?
    private var stateChar: CBCharacteristic?
    private var scanToken = 0
    private var scanningForSaved = false
    private var writeQueue: [Data] = []
    private var writing = false

    override init() {
        let d = UserDefaults.standard
        savedName = d.string(forKey: "saved_watch_name") ?? "your watch"
        hasSavedWatch = d.string(forKey: "saved_watch_id") != nil
        autoConnect = (d.object(forKey: "auto_connect") as? Bool) ?? true
        super.init()
        central = CBCentralManager(
            delegate: self,
            queue: nil,
            options: [
                CBCentralManagerOptionShowPowerAlertKey: true,
                CBCentralManagerOptionRestoreIdentifierKey: Self.restoreID
            ]
        )
    }

    // MARK: - Remembered watch

    private var savedID: UUID? {
        guard let s = UserDefaults.standard.string(forKey: "saved_watch_id") else { return nil }
        return UUID(uuidString: s)
    }

    private func rememberWatch(_ p: CBPeripheral, name: String) {
        let d = UserDefaults.standard
        d.set(p.identifier.uuidString, forKey: "saved_watch_id")
        d.set(name, forKey: "saved_watch_name")
        d.set(true, forKey: "auto_connect")
        savedName = name
        hasSavedWatch = true
        autoConnect = true
    }

    /// Connects to the remembered watch and keeps trying until it is nearby.
    func attemptReconnect() {
        guard central.state == .poweredOn, autoConnect, let id = savedID else { return }
        if connectedName != nil { return }
        statusText = "Looking for \(savedName)..."
        if let p = central.retrievePeripherals(withIdentifiers: [id]).first {
            scanningForSaved = false
            connecting = p
            p.delegate = self
            if p.state == .connected {
                adopt(p)
            } else {
                central.connect(p, options: nil)
            }
        } else {
            scanningForSaved = true
            central.scanForPeripherals(withServices: [Self.watchServiceUUID], options: nil)
        }
    }

    private func adopt(_ p: CBPeripheral) {
        connecting = p
        p.delegate = self
        let name = p.name ?? savedName
        rememberWatch(p, name: name)
        connectedName = name
        statusText = ""
        p.discoverServices([Self.watchServiceUUID])
    }

    func reconnectSaved() {
        UserDefaults.standard.set(true, forKey: "auto_connect")
        autoConnect = true
        attemptReconnect()
    }

    /// The only way to disconnect: stops auto-connecting until you tap Reconnect.
    func userDisconnect() {
        UserDefaults.standard.set(false, forKey: "auto_connect")
        autoConnect = false
        scanningForSaved = false
        if let p = connecting {
            central.cancelPeripheralConnection(p)
        }
        connectedName = nil
        clearLink()
        statusText = "Disconnected. Tap Reconnect to connect again."
    }

    func forgetWatch() {
        scanningForSaved = false
        if let p = connecting {
            central.cancelPeripheralConnection(p)
        }
        connecting = nil
        let d = UserDefaults.standard
        d.removeObject(forKey: "saved_watch_id")
        d.removeObject(forKey: "saved_watch_name")
        d.set(true, forKey: "auto_connect")
        hasSavedWatch = false
        savedName = "your watch"
        autoConnect = true
        connectedName = nil
        clearLink()
        statusText = ""
    }

    // MARK: - Scanning (first time only)

    func startScan() {
        guard central.state == .poweredOn else { return }
        scanningForSaved = false
        central.stopScan()
        devices.removeAll()
        statusText = ""
        isScanning = true
        scanToken += 1
        let token = scanToken
        let services: [CBUUID]? = scanAll ? nil : [Self.watchServiceUUID]
        central.scanForPeripherals(withServices: services, options: nil)
        DispatchQueue.main.asyncAfter(deadline: .now() + 15) { [weak self] in
            guard let self = self, self.scanToken == token else { return }
            self.stopScan()
        }
    }

    func stopScan() {
        if central.isScanning {
            central.stopScan()
        }
        isScanning = false
    }

    func connect(_ device: FoundDevice) {
        stopScan()
        connecting = device.peripheral
        device.peripheral.delegate = self
        statusText = "Connecting to \(device.name)..."
        central.connect(device.peripheral, options: nil)
    }

    // MARK: - Sending to the watch (one write at a time, in order)

    /// Sends a short text message to the watch.
    func send(_ text: String) {
        guard let data = text.data(using: .utf8) else { return }
        enqueue(data)
    }

    /// Sends a longer message (for example a title or a picture) in numbered pieces.
    func sendMessage(type: UInt8, payload: Data) {
        guard let p = connecting, stateChar != nil else { return }
        let size = max(20, p.maximumWriteValueLength(for: .withoutResponse))

        var first = Data([0x01, type])
        var total = UInt32(payload.count).bigEndian
        withUnsafeBytes(of: &total) { first.append(contentsOf: $0) }
        let room = size - first.count
        let firstCount = min(room, payload.count)
        if firstCount > 0 {
            first.append(payload.prefix(firstCount))
        }
        enqueue(first)

        var offset = firstCount
        while offset < payload.count {
            let n = min(size - 1, payload.count - offset)
            var packet = Data([0x02])
            packet.append(payload.subdata(in: offset..<(offset + n)))
            enqueue(packet)
            offset += n
        }
    }

    private func enqueue(_ data: Data) {
        guard stateChar != nil else { return }
        writeQueue.append(data)
        pump()
    }

    private func pump() {
        guard !writing, let p = connecting, let c = stateChar, !writeQueue.isEmpty else { return }
        writing = true
        let data = writeQueue.removeFirst()
        p.writeValue(data, for: c, type: .withResponse)
    }

    private func clearLink() {
        eventChar = nil
        stateChar = nil
        linkReady = false
        writeQueue.removeAll()
        writing = false
        frameBuffer = Data()
        frameTotal = 0
    }

    private func handleFrame(_ raw: Data) {
        guard let first = raw.first else { return }
        let b = raw.startIndex
        if first == 1 {
            guard raw.count >= 6 else { return }
            frameType = raw[b + 1]
            frameTotal = (Int(raw[b + 2]) << 24) | (Int(raw[b + 3]) << 16) | (Int(raw[b + 4]) << 8) | Int(raw[b + 5])
            frameBuffer = Data(raw.dropFirst(6))
        } else if first == 2 {
            frameBuffer.append(raw.dropFirst(1))
        }
        if frameTotal > 0 && frameBuffer.count >= frameTotal {
            let payload = Data(frameBuffer.prefix(frameTotal))
            let t = frameType
            frameBuffer = Data()
            frameTotal = 0
            onFrame?(t, payload)
        }
    }

    // MARK: - CBCentralManagerDelegate

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        if let list = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral], let p = list.first {
            connecting = p
            p.delegate = self
            if p.state == .connected {
                connectedName = p.name ?? savedName
                p.discoverServices([Self.watchServiceUUID])
            }
        }
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        state = central.state
        if central.state == .poweredOn {
            attemptReconnect()
        } else {
            isScanning = false
            scanningForSaved = false
            connectedName = nil
            clearLink()
            statusText = ""
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        if scanningForSaved {
            scanningForSaved = false
            central.stopScan()
            connecting = peripheral
            peripheral.delegate = self
            central.connect(peripheral, options: nil)
            return
        }
        let name = peripheral.name ?? (advertisementData[CBAdvertisementDataLocalNameKey] as? String)
        if scanAll && name == nil { return }
        let label = name ?? "Watch"
        let value = RSSI.intValue
        if let index = devices.firstIndex(where: { $0.id == peripheral.identifier }) {
            devices[index].rssi = value
        } else {
            devices.append(FoundDevice(id: peripheral.identifier, name: label, rssi: value, peripheral: peripheral))
        }
        devices.sort { $0.rssi > $1.rssi }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        adopt(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        statusText = "Could not connect. Retrying..."
        if autoConnect {
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in
                self?.attemptReconnect()
            }
        }
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        connectedName = nil
        clearLink()
        if autoConnect, central.state == .poweredOn, peripheral.identifier == savedID {
            statusText = "Reconnecting to \(savedName)..."
            central.connect(peripheral, options: nil)
        }
    }

    // MARK: - CBPeripheralDelegate

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let services = peripheral.services else { return }
        for service in services where service.uuid == Self.watchServiceUUID {
            peripheral.discoverCharacteristics([Self.eventUUID, Self.stateUUID], for: service)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let chars = service.characteristics else { return }
        for c in chars {
            if c.uuid == Self.eventUUID {
                eventChar = c
                peripheral.setNotifyValue(true, for: c)
            }
            if c.uuid == Self.stateUUID {
                stateChar = c
            }
        }
        linkReady = (eventChar != nil && stateChar != nil)
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        writing = false
        pump()
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        if characteristic.uuid == Self.eventUUID, let raw = characteristic.value, let lead = raw.first, lead == 1 || lead == 2 {
            handleFrame(raw)
            return
        }
        if characteristic.uuid == Self.eventUUID,
           let data = characteristic.value,
           let text = String(data: data, encoding: .utf8) {
            onEvent?(text)
            onMusicEvent?(text)
            onContactsEvent?(text)
        }
    }
}
