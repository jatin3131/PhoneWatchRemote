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

    /// Test build: list every named Bluetooth device. Later this becomes false so only our watch shows.
    static let scanAllDevices = true

    @Published var state: CBManagerState = .unknown
    @Published var devices: [FoundDevice] = []
    @Published var isScanning = false
    @Published var connectedName: String? = nil
    @Published var statusText = ""
    @Published var linkReady = false

    /// Called with each short message the watch sends (gallery and editor).
    var onEvent: ((String) -> Void)?
    /// Called with each short message the watch sends (music).
    var onMusicEvent: ((String) -> Void)?

    private var central: CBCentralManager!
    private var connecting: CBPeripheral?
    private var eventChar: CBCharacteristic?
    private var stateChar: CBCharacteristic?
    private var scanToken = 0
    private var writeQueue: [Data] = []
    private var writing = false

    override init() {
        super.init()
        central = CBCentralManager(
            delegate: self,
            queue: nil,
            options: [CBCentralManagerOptionShowPowerAlertKey: true]
        )
    }

    func startScan() {
        guard central.state == .poweredOn else { return }
        devices.removeAll()
        statusText = ""
        isScanning = true
        scanToken += 1
        let token = scanToken
        let services: [CBUUID]? = Self.scanAllDevices ? nil : [Self.watchServiceUUID]
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

    func disconnect() {
        if let p = connecting {
            central.cancelPeripheralConnection(p)
        }
        connectedName = nil
        linkReady = false
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
    }

    // MARK: - CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        state = central.state
        if central.state == .poweredOn {
            let existing = central.retrieveConnectedPeripherals(withServices: [Self.watchServiceUUID])
            if let p = existing.first {
                connecting = p
                p.delegate = self
                central.connect(p, options: nil)
            }
        } else {
            isScanning = false
            connectedName = nil
            clearLink()
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        let name = peripheral.name ?? (advertisementData[CBAdvertisementDataLocalNameKey] as? String)
        if Self.scanAllDevices && name == nil { return }
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
        connectedName = peripheral.name ?? "Watch"
        statusText = ""
        peripheral.delegate = self
        peripheral.discoverServices([Self.watchServiceUUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        statusText = "Could not connect. Try again."
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        connectedName = nil
        clearLink()
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
        if characteristic.uuid == Self.eventUUID,
           let data = characteristic.value,
           let text = String(data: data, encoding: .utf8) {
            onEvent?(text)
            onMusicEvent?(text)
        }
    }
}
