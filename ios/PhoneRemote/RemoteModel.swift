import SwiftUI
import Photos
import CoreImage

enum EditAttribute: Int, CaseIterable {
    case exposure, brightness, contrast, saturation, vibrance, highlights, shadows, whites, blacks, gamma, brilliance

    var title: String {
        switch self {
        case .exposure: return "Exposure"
        case .brightness: return "Brightness"
        case .contrast: return "Contrast"
        case .saturation: return "Saturation"
        case .vibrance: return "Vibrance"
        case .highlights: return "Highlights"
        case .shadows: return "Shadows"
        case .whites: return "Whites"
        case .blacks: return "Blacks"
        case .gamma: return "Gamma"
        case .brilliance: return "Brilliance"
        }
    }
}

/// Applies the edit sliders (each -100...100) to an image using Core Image.
enum PhotoEditor {
    static let context = CIContext(options: nil)

    static func upright(_ image: UIImage, maxSide: CGFloat) -> UIImage {
        let pw = image.size.width * image.scale
        let ph = image.size.height * image.scale
        guard pw > 0, ph > 0 else { return image }
        let factor = min(1, maxSide / max(pw, ph))
        let target = CGSize(width: floor(pw * factor), height: floor(ph * factor))
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: target, format: format)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
    }

    static func apply(_ values: [Double], to input: CIImage) -> CIImage {
        if values.allSatisfy({ $0 == 0 }) { return input }
        func v(_ a: EditAttribute) -> Double { return values[a.rawValue] / 100.0 }
        func clamp(_ x: Double) -> CGFloat { return CGFloat(min(1.0, max(0.0, x))) }

        var image = input

        if v(.exposure) != 0 {
            image = image.applyingFilter("CIExposureAdjust", parameters: ["inputEV": v(.exposure) * 2.0])
        }
        if v(.gamma) != 0 {
            image = image.applyingFilter("CIGammaAdjust", parameters: ["inputPower": pow(2.0, -v(.gamma))])
        }
        if v(.brightness) != 0 || v(.contrast) != 0 || v(.saturation) != 0 {
            image = image.applyingFilter("CIColorControls", parameters: [
                "inputBrightness": v(.brightness) * 0.3,
                "inputContrast": 1.0 + v(.contrast) * 0.4,
                "inputSaturation": 1.0 + v(.saturation)
            ])
        }
        if v(.vibrance) != 0 {
            image = image.applyingFilter("CIVibrance", parameters: ["inputAmount": v(.vibrance)])
        }

        let hl = v(.highlights)
        let sh = v(.shadows)
        if hl < 0 || sh != 0 {
            image = image.applyingFilter("CIHighlightShadowAdjust", parameters: [
                "inputHighlightAmount": 1.0 + min(0.0, hl),
                "inputShadowAmount": sh
            ])
        }

        let wh = v(.whites)
        let bl = v(.blacks)
        let br = v(.brilliance)
        let hlUp = max(0.0, hl)
        if wh != 0 || bl != 0 || br != 0 || hlUp != 0 {
            let p0 = clamp(max(0.0, bl) * 0.12)
            let p1 = clamp(0.25 + min(0.0, bl) * 0.05 + br * 0.07)
            let p2 = clamp(0.5 + br * 0.06)
            let p3 = clamp(0.75 + hlUp * 0.08 + max(0.0, wh) * 0.05 - br * 0.03)
            let p4 = clamp(1.0 + min(0.0, wh) * 0.15)
            image = image.applyingFilter("CIToneCurve", parameters: [
                "inputPoint0": CIVector(x: 0.0, y: p0),
                "inputPoint1": CIVector(x: 0.25, y: p1),
                "inputPoint2": CIVector(x: 0.5, y: p2),
                "inputPoint3": CIVector(x: 0.75, y: p3),
                "inputPoint4": CIVector(x: 1.0, y: p4)
            ])
        }
        return image
    }

    static func render(_ image: CIImage, extent: CGRect) -> UIImage? {
        guard let cg = context.createCGImage(image, from: extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

/// Turns the short messages from the watch into actions on the phone.
final class RemoteModel: ObservableObject {

    enum Mode {
        case idle
        case gallery
        case viewer
        case zoom
        case edit
    }

    static let columns = 3

    @Published var mode: Mode = .idle
    @Published var topRow = 0
    @Published var visibleRows = 4
    @Published var highlighted: Int? = nil
    @Published var openIndex: Int? = nil
    @Published var zoom: CGFloat = 1.0
    @Published var notice = ""
    @Published var scrollTick = 0
    @Published var exitGalleryTick = 0
    @Published var openGalleryTick = 0
    @Published var editValues: [Double] = Array(repeating: 0, count: 11)
    @Published var editRendered: UIImage? = nil
    @Published var lastEditIndex = 0

    var pinchBase: CGFloat = 1.0

    private weak var ble: BluetoothManager?
    private var assets: [PHAsset] = []
    private var currentAsset: PHAsset?
    private var editBase: CIImage?
    private let renderQueue = DispatchQueue(label: "phoneremote.render", qos: .userInitiated)
    private let genLock = NSLock()
    private var latestGen = 0

    func attach(_ manager: BluetoothManager) {
        ble = manager
        manager.onEvent = { [weak self] text in
            self?.handle(text)
        }
    }

    func sendStatus(gallery: Bool, music: Bool) {
        ble?.send("P \(gallery ? 1 : 0) \(music ? 1 : 0)")
    }

    // MARK: - Called by the gallery screen

    func start(assets: [PHAsset]) {
        self.assets = assets
        topRow = 0
        highlighted = nil
        openIndex = nil
        currentAsset = nil
        zoom = 1.0
        pinchBase = 1.0
        clearEdit()
        mode = .gallery
        ble?.send("G")
    }

    func stop() {
        mode = .idle
        openIndex = nil
        highlighted = nil
        currentAsset = nil
        clearEdit()
        ble?.send("I")
    }

    func open(_ index: Int) {
        guard index >= 0, index < assets.count else { return }
        currentAsset = assets[index]
        openIndex = index
        zoom = 1.0
        pinchBase = 1.0
        mode = .viewer
        ble?.send("V")
    }

    func closeViewer() {
        clearEdit()
        openIndex = nil
        currentAsset = nil
        zoom = 1.0
        pinchBase = 1.0
        mode = .gallery
        ble?.send("G")
    }

    func syncTopRow(_ row: Int) {
        if row != topRow {
            topRow = row
        }
    }

    // MARK: - Messages from the watch

    private func handle(_ text: String) {
        let parts = text.split(separator: " ").map { String($0) }
        guard let cmd = parts.first else { return }

        switch cmd {
        case "APP":
            if parts.count >= 2 && parts[1] == "G" {
                openGalleryTick += 1
            }
        case "XG":
            if mode != .idle {
                exitGalleryTick += 1
            }
        case "M":
            guard mode == .gallery, parts.count >= 3, let x = Int(parts[1]), let y = Int(parts[2]) else { return }
            highlighted = cellIndex(x: x, y: y)
        case "T":
            guard mode == .gallery, parts.count >= 3, let x = Int(parts[1]), let y = Int(parts[2]) else { return }
            if let cell = cellIndex(x: x, y: y) {
                highlighted = cell
                open(cell)
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
        case "BK":
            if mode == .viewer {
                closeViewer()
            }
        case "ED":
            if mode == .viewer {
                startEdit()
            }
        case "E":
            guard mode == .edit, parts.count >= 3, let i = Int(parts[1]), let value = Double(parts[2]),
                  i >= 0, i < editValues.count else { return }
            editValues[i] = max(-100, min(100, value))
            lastEditIndex = i
            scheduleRender()
        case "SV":
            if mode == .edit {
                saveEdit()
            }
        case "EX":
            if mode == .edit {
                clearEdit()
                mode = .viewer
                ble?.send("V")
            }
        default:
            break
        }
    }

    private func cellIndex(x: Int, y: Int) -> Int? {
        let col = min(RemoteModel.columns - 1, max(0, x * RemoteModel.columns / 1000))
        let rowCount = max(1, visibleRows)
        let row = min(rowCount - 1, max(0, y * rowCount / 1000))
        let index = (topRow + row) * RemoteModel.columns + col
        return index < assets.count ? index : nil
    }

    private func scroll(by d: Int) {
        let totalRows = (assets.count + RemoteModel.columns - 1) / RemoteModel.columns
        let maxTop = max(0, totalRows - 1)
        topRow = min(maxTop, max(0, topRow + (d > 0 ? 1 : -1)))
        highlighted = nil
        scrollTick += 1
    }

    private func changeZoom(factor: CGFloat) {
        zoom = min(6.0, max(1.0, zoom * factor))
        pinchBase = zoom
    }

    // MARK: - Editing

    private func clearEdit() {
        editValues = Array(repeating: 0, count: 11)
        editRendered = nil
        editBase = nil
        lastEditIndex = 0
    }

    private func startEdit() {
        guard let asset = currentAsset else { return }
        clearEdit()
        mode = .edit
        ble?.send("X")

        let options = PHImageRequestOptions()
        options.deliveryMode = .highQualityFormat
        options.isNetworkAccessAllowed = true
        PHImageManager.default().requestImage(
            for: asset,
            targetSize: CGSize(width: 1600, height: 1600),
            contentMode: .aspectFit,
            options: options
        ) { [weak self] image, _ in
            guard let self = self, let image = image else { return }
            self.renderQueue.async {
                let up = PhotoEditor.upright(image, maxSide: 1600)
                guard let ci = CIImage(image: up) else { return }
                DispatchQueue.main.async {
                    self.editBase = ci
                    self.scheduleRender()
                }
            }
        }
    }

    private func scheduleRender() {
        guard let base = editBase else { return }
        let values = editValues
        genLock.lock()
        latestGen += 1
        let gen = latestGen
        genLock.unlock()
        renderQueue.async { [weak self] in
            guard let self = self else { return }
            self.genLock.lock()
            let current = self.latestGen
            self.genLock.unlock()
            if gen != current { return }
            let out = PhotoEditor.apply(values, to: base)
            let image = PhotoEditor.render(out, extent: base.extent)
            DispatchQueue.main.async {
                if self.mode == .edit {
                    self.editRendered = image
                }
            }
        }
    }

    private func saveEdit() {
        guard let asset = currentAsset else { return }
        let values = editValues
        showNotice("Saving...")

        let options = PHImageRequestOptions()
        options.deliveryMode = .highQualityFormat
        options.isNetworkAccessAllowed = true
        PHImageManager.default().requestImage(
            for: asset,
            targetSize: CGSize(width: 4096, height: 4096),
            contentMode: .aspectFit,
            options: options
        ) { [weak self] image, _ in
            guard let self = self else { return }
            guard let image = image else {
                DispatchQueue.main.async { self.showNotice("Could not load the photo") }
                return
            }
            self.renderQueue.async {
                let up = PhotoEditor.upright(image, maxSide: 4096)
                guard let ci = CIImage(image: up) else {
                    DispatchQueue.main.async { self.showNotice("Could not save") }
                    return
                }
                let out = PhotoEditor.apply(values, to: ci)
                guard let finished = PhotoEditor.render(out, extent: ci.extent),
                      let data = finished.jpegData(compressionQuality: 0.92) else {
                    DispatchQueue.main.async { self.showNotice("Could not save") }
                    return
                }
                PHPhotoLibrary.shared().performChanges({
                    let request = PHAssetCreationRequest.forAsset()
                    request.addResource(with: .photo, data: data, options: nil)
                }) { ok, _ in
                    DispatchQueue.main.async {
                        if ok {
                            self.ble?.send("SD")
                            self.showNotice("Saved to Photos")
                        } else {
                            self.showNotice("Could not save")
                        }
                    }
                }
            }
        }
    }

    private func showNotice(_ text: String) {
        notice = text
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in
            self?.notice = ""
        }
    }
}
