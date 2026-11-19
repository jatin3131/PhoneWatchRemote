import SwiftUI
import MediaPlayer
import Combine

/// Reads what the built-in Music app is playing, sends it to the watch, and runs the watch's commands.
final class MusicManager: ObservableObject {

    @Published var authorized = false
    @Published var statusText = "not asked yet"
    @Published var title = ""
    @Published var artist = ""
    @Published var isPlaying = false
    @Published var artwork: UIImage? = nil

    private let player = MPMusicPlayerController.systemMusicPlayer
    private weak var ble: BluetoothManager?
    private var settings: FeatureSettings?
    private var cancellables = Set<AnyCancellable>()
    private var observing = false
    private var timer: Timer?
    private var lastKey = ""
    private var volumeView: MPVolumeView?

    func attach(ble: BluetoothManager, settings: FeatureSettings) {
        self.ble = ble
        self.settings = settings
        ble.onMusicEvent = { [weak self] text in
            self?.handle(text)
        }
        if cancellables.isEmpty {
            ble.$linkReady
                .receive(on: DispatchQueue.main)
                .sink { [weak self] ready in
                    if ready {
                        self?.refresh(sendArtwork: true)
                    }
                }
                .store(in: &cancellables)
        }
        updateAuthorization()
        requestIfNeeded()
    }

    // MARK: - Permission

    func updateAuthorization() {
        let status = MPMediaLibrary.authorizationStatus()
        authorized = (status == .authorized)
        switch status {
        case .authorized: statusText = "allowed"
        case .denied: statusText = "denied in Settings"
        case .restricted: statusText = "restricted"
        case .notDetermined: statusText = "not asked yet"
        @unknown default: statusText = "unknown"
        }
        if authorized {
            startObserving()
            refresh(sendArtwork: true)
        }
    }

    func requestIfNeeded() {
        if MPMediaLibrary.authorizationStatus() == .notDetermined {
            MPMediaLibrary.requestAuthorization { _ in
                DispatchQueue.main.async {
                    self.updateAuthorization()
                }
            }
        }
    }

    func requestOrOpenSettings() {
        if MPMediaLibrary.authorizationStatus() == .notDetermined {
            requestIfNeeded()
        } else if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }

    // MARK: - Watching the Music app

    private func startObserving() {
        if observing { return }
        observing = true
        player.beginGeneratingPlaybackNotifications()
        let center = NotificationCenter.default
        center.addObserver(
            forName: .MPMusicPlayerControllerNowPlayingItemDidChange,
            object: player,
            queue: .main
        ) { [weak self] _ in
            self?.refresh(sendArtwork: true)
        }
        center.addObserver(
            forName: .MPMusicPlayerControllerPlaybackStateDidChange,
            object: player,
            queue: .main
        ) { [weak self] _ in
            self?.refresh(sendArtwork: false)
        }
        timer = Timer.scheduledTimer(withTimeInterval: 8, repeats: true) { [weak self] _ in
            guard let self = self, self.isPlaying else { return }
            self.refresh(sendArtwork: false)
        }
        installVolumeView()
    }

    func refresh(sendArtwork: Bool) {
        guard authorized else { return }
        let item = player.nowPlayingItem
        let newTitle = item?.title ?? ""
        let newArtist = item?.artist ?? ""
        let key = "\(item?.persistentID ?? 0)|\(newTitle)|\(newArtist)"
        let changed = (key != lastKey)

        title = newTitle
        artist = newArtist
        isPlaying = (player.playbackState == .playing)
        if changed {
            lastKey = key
            artwork = item?.artwork?.image(at: CGSize(width: 200, height: 200))
        }
        sendToWatch(item: item, withArtwork: sendArtwork || changed)
    }

    // MARK: - Sending to the watch

    private func clean(_ text: String) -> String {
        let flat = text.replacingOccurrences(of: "\n", with: " ").trimmingCharacters(in: .whitespaces)
        return String(flat.prefix(80))
    }

    private func seconds(_ value: TimeInterval) -> Int {
        return value.isFinite ? max(0, Int(value)) : 0
    }

    private func smallJPEG(_ image: UIImage) -> Data? {
        let target = CGSize(width: 100, height: 100)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: target, format: format)
        let resized = renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
        return resized.jpegData(compressionQuality: 0.5)
    }

    private func sendToWatch(item: MPMediaItem?, withArtwork: Bool) {
        guard settings?.isOn("media") ?? true else { return }
        guard let ble = ble, ble.linkReady else { return }

        let duration = seconds(item?.playbackDuration ?? 0)
        let position = seconds(player.currentPlaybackTime)
        let text = "\(isPlaying ? 1 : 0)\n\(duration)\n\(position)\n\(clean(title))\n\(clean(artist))"
        ble.sendMessage(type: 0x4D, payload: Data(text.utf8))

        if withArtwork {
            if let art = artwork, let data = smallJPEG(art) {
                ble.sendMessage(type: 0x41, payload: data)
            } else {
                ble.sendMessage(type: 0x41, payload: Data())
            }
        }
    }

    // MARK: - Commands from the watch

    private func handle(_ text: String) {
        guard authorized else { return }
        switch text {
        case "MP":
            if player.playbackState == .playing {
                player.pause()
            } else {
                player.play()
            }
        case "MN":
            player.skipToNextItem()
        case "MV":
            player.skipToPreviousItem()
        case "MR":
            refresh(sendArtwork: true)
            return
        case "VU":
            changeVolume(0.0625)
        case "VD":
            changeVolume(-0.0625)
        default:
            return
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            self?.refresh(sendArtwork: false)
        }
    }

    // MARK: - Volume (uses the standard volume slider; iOS gives apps no direct volume control)

    private func installVolumeView() {
        if volumeView != nil { return }
        let view = MPVolumeView(frame: CGRect(x: -3000, y: -3000, width: 10, height: 10))
        let window = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first
        window?.addSubview(view)
        volumeView = view
    }

    private func changeVolume(_ delta: Float) {
        installVolumeView()
        if let slider = volumeView?.subviews.compactMap({ $0 as? UISlider }).first {
            slider.value = min(1, max(0, slider.value + delta))
            slider.sendActions(for: .valueChanged)
        }
    }
}
