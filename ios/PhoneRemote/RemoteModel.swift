import SwiftUI

/// Turns the short messages from the watch into actions in the gallery.
final class RemoteModel: ObservableObject {

    enum Mode {
        case idle
        case gallery
        case viewer
        case zoom
    }

    static let columns = 3
    static let rows = 4

    @Published var mode: Mode = .idle
    @Published var topRow = 0
    @Published var highlighted: Int? = nil
    @Published var openIndex: Int? = nil
    @Published var zoom: CGFloat = 1.0
    @Published var notice = ""

    var pinchBase: CGFloat = 1.0

    private weak var ble: BluetoothManager?
    private var assetCount = 0

    func attach(_ manager: BluetoothManager) {
        ble = manager
        manager.onEvent = { [weak self] text in
            self?.handle(text)
        }
    }

    // MARK: - Called by the gallery screen

    func start(assetCount: Int) {
        self.assetCount = assetCount
        topRow = 0
        highlighted = nil
        openIndex = nil
        zoom = 1.0
        pinchBase = 1.0
        mode = .gallery
        ble?.send("G")
    }

    func stop() {
        mode = .idle
        openIndex = nil
        highlighted = nil
        ble?.send("I")
    }

    func open(_ absoluteIndex: Int) {
        guard absoluteIndex >= 0, absoluteIndex < assetCount else { return }
        openIndex = absoluteIndex
        zoom = 1.0
        pinchBase = 1.0
        mode = .viewer
        ble?.send("V")
    }

    func closeViewer() {
        openIndex = nil
        zoom = 1.0
        pinchBase = 1.0
        mode = .gallery
        ble?.send("G")
    }

    // MARK: - Messages from the watch

    private func handle(_ text: String) {
        let parts = text.split(separator: " ").map { String($0) }
        guard let cmd = parts.first else { return }

        switch cmd {
        case "M":
            guard mode == .gallery, parts.count >= 3, let x = Int(parts[1]), let y = Int(parts[2]) else { return }
            highlighted = cellIndex(x: x, y: y)
        case "T":
            guard mode == .gallery, parts.count >= 3, let x = Int(parts[1]), let y = Int(parts[2]) else { return }
            if let cell = cellIndex(x: x, y: y) {
                highlighted = cell
                open(topRow * RemoteModel.columns + cell)
            }
        case "B":
            guard parts.count >= 2, let d = Int(parts[1]) else { return }
            if mode == .gallery {
                scroll(by: d)
            } else if mode == .zoom {
                changeZoom(factor: d > 0 ? 1.2 : 1.0 / 1.2)
            }
        case "Z":
            guard mode == .zoom, parts.count >= 2, let d = Int(parts[1]) else { return }
            changeZoom(factor: 1.0 + CGFloat(d) / 150.0)
        case "ZM":
            if mode == .viewer {
                mode = .zoom
                ble?.send("Z")
            }
        case "ZD":
            if mode == .zoom {
                mode = .viewer
                ble?.send("V")
            }
        case "ED":
            if mode == .viewer {
                showNotice("The editor comes in the next build")
            }
        case "BK":
            if mode == .viewer {
                closeViewer()
            }
        default:
            break
        }
    }

    private func cellIndex(x: Int, y: Int) -> Int? {
        let col = min(RemoteModel.columns - 1, max(0, x * RemoteModel.columns / 1000))
        let row = min(RemoteModel.rows - 1, max(0, y * RemoteModel.rows / 1000))
        let index = row * RemoteModel.columns + col
        if topRow * RemoteModel.columns + index < assetCount {
            return index
        }
        return nil
    }

    private func scroll(by d: Int) {
        let totalRows = (assetCount + RemoteModel.columns - 1) / RemoteModel.columns
        let maxTop = max(0, totalRows - RemoteModel.rows)
        topRow = min(maxTop, max(0, topRow + (d > 0 ? 1 : -1)))
        highlighted = nil
    }

    private func changeZoom(factor: CGFloat) {
        zoom = min(6.0, max(1.0, zoom * factor))
        pinchBase = zoom
    }

    private func showNotice(_ text: String) {
        notice = text
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in
            self?.notice = ""
        }
    }
}
