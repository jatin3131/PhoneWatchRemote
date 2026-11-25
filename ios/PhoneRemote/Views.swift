import SwiftUI
import Photos
import CoreBluetooth

func openBluetoothSettings() {
    if let url = URL(string: "App-Prefs:Bluetooth") {
        UIApplication.shared.open(url, options: [:]) { success in
            if !success, let fallback = URL(string: UIApplication.openSettingsURLString) {
                UIApplication.shared.open(fallback)
            }
        }
    } else if let fallback = URL(string: UIApplication.openSettingsURLString) {
        UIApplication.shared.open(fallback)
    }
}

struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 17, weight: .semibold))
            .foregroundColor(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(Color.black.opacity(isEnabled ? (configuration.isPressed ? 0.7 : 1.0) : 0.25))
            .cornerRadius(14)
    }
}

struct RootView: View {
    @EnvironmentObject var ble: BluetoothManager
    @EnvironmentObject var remote: RemoteModel
    @EnvironmentObject var music: MusicManager
    @EnvironmentObject var settings: FeatureSettings
    @EnvironmentObject var contacts: ContactsManager
    @EnvironmentObject var artwork: ArtworkManager
    @EnvironmentObject var island: IslandManager
    @State private var skipped = false

    var body: some View {
        Group {
            if ble.connectedName != nil || skipped {
                MainView(watchName: ble.connectedName ?? "Test mode (no watch)")
            } else {
                ConnectView(onSkip: { skipped = true })
            }
        }
        .preferredColorScheme(.light)
        .onAppear {
            remote.attach(ble)
            music.attach(ble: ble, settings: settings)
            contacts.attach(ble: ble, settings: settings)
            artwork.attach(ble: ble)
            island.attach(ble: ble, remote: remote, settings: settings)
        }
    }
}

struct ConnectView: View {
    @EnvironmentObject var ble: BluetoothManager
    let onSkip: () -> Void

    private var message: String {
        switch ble.state {
        case .poweredOn:
            if ble.hasSavedWatch && ble.autoConnect {
                return "Looking for \(ble.savedName). It connects by itself when the watch is nearby."
            }
            if ble.hasSavedWatch {
                return "Disconnected from \(ble.savedName). Tap Reconnect to connect again."
            }
            return "Bluetooth is on. Scan to find your watch."
        case .poweredOff:
            return "Bluetooth is off. Turn it on, then come back to this app."
        case .unauthorized:
            return "Bluetooth access is blocked for this app. Allow it in Settings."
        case .unsupported:
            return "This iPhone does not support Bluetooth."
        default:
            return "Checking Bluetooth..."
        }
    }

    var body: some View {
        ZStack {
            Color.white.ignoresSafeArea()
            VStack(spacing: 16) {
                Spacer().frame(height: 24)
                Text("Connect your watch")
                    .font(.system(size: 28, weight: .semibold))
                    .foregroundColor(.black)
                Text(message)
                    .font(.system(size: 16))
                    .foregroundColor(.gray)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 30)

                if ble.state != .poweredOn {
                    Button("Turn on Bluetooth") {
                        openBluetoothSettings()
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .padding(.horizontal, 24)
                }

                if ble.state == .poweredOn && ble.hasSavedWatch && !ble.autoConnect {
                    Button("Reconnect to \(ble.savedName)") {
                        ble.reconnectSaved()
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .padding(.horizontal, 24)
                }

                Button(ble.isScanning ? "Scanning..." : (ble.hasSavedWatch ? "Scan for a different watch" : "Scan for watches")) {
                    ble.startScan()
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(ble.state != .poweredOn || ble.isScanning)
                .padding(.horizontal, 24)

                if !ble.statusText.isEmpty {
                    Text(ble.statusText)
                        .font(.system(size: 14))
                        .foregroundColor(.gray)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 30)
                }

                ScrollView {
                    VStack(spacing: 10) {
                        ForEach(ble.devices) { device in
                            Button {
                                ble.connect(device)
                            } label: {
                                HStack {
                                    Text(device.name)
                                        .foregroundColor(.black)
                                    Spacer()
                                    Text("\(device.rssi) dBm")
                                        .foregroundColor(.gray)
                                }
                                .padding(14)
                                .background(Color(white: 0.96))
                                .cornerRadius(12)
                            }
                        }
                        if ble.isScanning && ble.devices.isEmpty {
                            ProgressView()
                        }
                    }
                    .padding(.horizontal, 24)
                }

                Button(ble.scanAll ? "Only show my watch" : "Can't see your watch? Show all Bluetooth devices") {
                    ble.scanAll.toggle()
                }
                .font(.system(size: 13))
                .foregroundColor(.gray)

                if ble.hasSavedWatch {
                    Button("Forget this watch") {
                        ble.forgetWatch()
                    }
                    .font(.system(size: 13))
                    .foregroundColor(.gray)
                }

                Button("Skip for now (testing)") {
                    onSkip()
                }
                .font(.system(size: 14))
                .foregroundColor(.gray)
                .padding(.bottom, 4)

                Text("build " + appBuildNumber)
                    .font(.system(size: 11))
                    .foregroundColor(.gray)
                    .padding(.bottom, 8)
            }
        }
    }
}

struct MainView: View {
    @EnvironmentObject var settings: FeatureSettings
    @EnvironmentObject var ble: BluetoothManager
    @EnvironmentObject var remote: RemoteModel
    @EnvironmentObject var music: MusicManager
    @StateObject private var library = PhotoLibrary()
    @EnvironmentObject var contacts: ContactsManager
    let watchName: String
    @Environment(\.scenePhase) private var scenePhase
    @State private var showGallery = false
    @State private var showDenied = false

    private func openGallery() {
        switch library.status {
        case .authorized, .limited:
            library.load()
            showGallery = true
        case .notDetermined:
            library.requestAccess { granted in
                if granted {
                    showGallery = true
                } else {
                    showDenied = true
                }
            }
        default:
            showDenied = true
        }
    }

    private func sendStatus() {
        remote.sendStatus(gallery: library.hasAccess, music: music.authorized)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    HStack(spacing: 8) {
                        Circle()
                            .fill(Color.green)
                            .frame(width: 10, height: 10)
                        Text(watchName)
                            .font(.system(size: 15))
                            .foregroundColor(.gray)
                        Spacer()
                        VStack(alignment: .trailing, spacing: 4) {
                            Button("Disconnect") {
                                ble.userDisconnect()
                            }
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundColor(.white)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(Color.black)
                            .cornerRadius(10)
                            Text((ble.linkReady ? "data link ready" : "data link not ready") + "  |  build " + appBuildNumber)
                        }
                            .font(.system(size: 12))
                            .foregroundColor(.gray)
                    }

                    ForEach(FeatureSettings.features) { feature in
                        HStack(alignment: .center) {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(feature.title)
                                    .font(.system(size: 17, weight: .semibold))
                                    .foregroundColor(.black)
                                Text(feature.detail)
                                    .font(.system(size: 13))
                                    .foregroundColor(.gray)
                            }
                            Spacer()
                            Toggle("", isOn: settings.binding(feature.id))
                                .labelsHidden()
                                .tint(.black)
                        }
                        .padding(14)
                        .background(Color(white: 0.96))
                        .cornerRadius(14)
                    }

                    MusicCard()

                    IslandCard()

                    Button("Gallery") {
                        openGallery()
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(!settings.isOn("gallery"))
                    .padding(.top, 6)
                }
                .padding(20)
            }
            .background(Color.white)
            .navigationTitle("Phone Remote")
            .navigationDestination(isPresented: $showGallery) {
                GalleryView(library: library)
            }
            .alert("Photo access is off", isPresented: $showDenied) {
                Button("Open Settings") {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Allow photo access in Settings to use the gallery.")
            }
        }
        .onAppear {
            sendStatus()
        }
        .onChange(of: ble.linkReady) { ready in
            if ready {
                sendStatus()
            }
        }
        .onChange(of: library.status) { _ in
            sendStatus()
        }
        .onChange(of: settings.isOn("contacts")) { _ in
            contacts.syncIfAllowed()
        }
        .onChange(of: settings.isOn("calls")) { _ in
            contacts.syncIfAllowed()
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active {
                library.status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
                music.updateAuthorization()
                sendStatus()
            }
        }
        .onChange(of: music.authorized) { _ in
            sendStatus()
        }
        .onChange(of: remote.openGalleryTick) { _ in
            if !showGallery {
                openGallery()
            }
        }
    }
}

struct RowOffsetKey: PreferenceKey {
    static var defaultValue: [Int: CGFloat] = [:]

    static func reduce(value: inout [Int: CGFloat], nextValue: () -> [Int: CGFloat]) {
        value.merge(nextValue()) { _, new in new }
    }
}

struct GalleryView: View {
    @ObservedObject var library: PhotoLibrary
    @EnvironmentObject var remote: RemoteModel
    @Environment(\.dismiss) private var dismiss

    private let spacing: CGFloat = 6

    private var rowCount: Int {
        return (library.assets.count + RemoteModel.columns - 1) / RemoteModel.columns
    }

    private var limitedBanner: some View {
        Button {
            if let url = URL(string: UIApplication.openSettingsURLString) {
                UIApplication.shared.open(url)
            }
        } label: {
            Text("Only some photos are shared with this app. Tap to allow all photos.")
                .font(.system(size: 13))
                .foregroundColor(.black)
                .multilineTextAlignment(.center)
                .padding(10)
                .frame(maxWidth: .infinity)
                .background(Color(white: 0.94))
                .cornerRadius(10)
        }
    }

    @ViewBuilder
    private func cellView(index: Int, size: CGFloat) -> some View {
        if index < library.assets.count {
            let asset = library.assets[index]
            let isHighlighted = remote.highlighted == index
            ThumbnailView(asset: asset)
                .frame(width: size, height: size)
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .overlay(
                    RoundedRectangle(cornerRadius: 10)
                        .stroke(Color.black, lineWidth: isHighlighted ? 3 : 0)
                )
                .scaleEffect(isHighlighted ? 1.18 : 1.0)
                .shadow(color: Color.black.opacity(isHighlighted ? 0.35 : 0), radius: 10, x: 0, y: 6)
                .zIndex(isHighlighted ? 1 : 0)
                .animation(.spring(response: 0.28, dampingFraction: 0.55), value: isHighlighted)
                .onTapGesture {
                    remote.open(index)
                }
        } else {
            Color.clear.frame(width: size, height: size)
        }
    }

    var body: some View {
        ZStack {
            Color.white.ignoresSafeArea()
            GeometryReader { geo in
                let cell = (geo.size.width - spacing * 4) / 3
                let rowHeight = cell + spacing
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(spacing: spacing) {
                            if library.isLimited {
                                limitedBanner
                            }
                            if library.assets.isEmpty {
                                Text("No photos found")
                                    .foregroundColor(.gray)
                                    .padding(40)
                            }
                            ForEach(0..<rowCount, id: \.self) { row in
                                HStack(spacing: spacing) {
                                    ForEach(0..<3, id: \.self) { col in
                                        cellView(index: row * 3 + col, size: cell)
                                    }
                                }
                                .id("row-\(row)")
                                .background(
                                    GeometryReader { g in
                                        Color.clear.preference(
                                            key: RowOffsetKey.self,
                                            value: [row: g.frame(in: .named("grid")).minY]
                                        )
                                    }
                                )
                            }
                        }
                        .padding(spacing)
                    }
                    .coordinateSpace(name: "grid")
                    .onPreferenceChange(RowOffsetKey.self) { offsets in
                        let visible = offsets.filter { $0.value > -rowHeight * 0.5 }
                        if let top = visible.keys.min() {
                            DispatchQueue.main.async {
                                remote.syncTopRow(top)
                            }
                        }
                    }
                    .onChange(of: remote.scrollTick) { _ in
                        withAnimation(.easeInOut(duration: 0.2)) {
                            proxy.scrollTo("row-\(remote.topRow)", anchor: .top)
                        }
                    }
                    .onAppear {
                        remote.visibleRows = max(2, Int(geo.size.height / rowHeight))
                    }
                    .onChange(of: geo.size.height) { _ in
                        remote.visibleRows = max(2, Int(geo.size.height / rowHeight))
                    }
                }
            }
            if let index = remote.openIndex, index < library.assets.count {
                ViewerOverlay(asset: library.assets[index])
                    .id(library.assets[index].localIdentifier)
            }
        }
        .navigationTitle("Gallery")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(remote.openIndex != nil ? Visibility.hidden : Visibility.visible, for: .navigationBar)
        .onAppear {
            remote.start(assets: library.assets)
        }
        .onDisappear {
            remote.stop()
        }
        .onChange(of: remote.exitGalleryTick) { _ in
            dismiss()
        }
    }
}

struct ThumbnailView: View {
    let asset: PHAsset
    @State private var image: UIImage?

    var body: some View {
        Color.gray.opacity(0.15)
            .aspectRatio(1, contentMode: .fit)
            .overlay(
                Group {
                    if let image = image {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFill()
                    }
                }
            )
            .clipped()
            .onAppear {
                load()
            }
    }

    private func load() {
        if image != nil { return }
        let options = PHImageRequestOptions()
        options.deliveryMode = .opportunistic
        options.isNetworkAccessAllowed = true
        PHImageManager.default().requestImage(
            for: asset,
            targetSize: CGSize(width: 300, height: 300),
            contentMode: .aspectFill,
            options: options
        ) { result, _ in
            if let result = result {
                DispatchQueue.main.async {
                    self.image = result
                }
            }
        }
    }
}

struct ValueBar: View {
    let value: Int

    var body: some View {
        GeometryReader { geo in
            let half = geo.size.width / 2
            let w = half * CGFloat(abs(value)) / 100
            ZStack(alignment: .leading) {
                Capsule().fill(Color.white.opacity(0.2))
                Rectangle()
                    .fill(value >= 0 ? Color.blue : Color.orange)
                    .frame(width: w, height: geo.size.height)
                    .offset(x: value >= 0 ? half : half - w)
                Rectangle()
                    .fill(Color.white.opacity(0.6))
                    .frame(width: 2, height: geo.size.height)
                    .offset(x: half - 1)
            }
            .clipShape(Capsule())
        }
    }
}

struct EditPanel: View {
    @EnvironmentObject var remote: RemoteModel

    private var index: Int { remote.lastEditIndex }
    private var value: Int { Int(remote.editValues[remote.lastEditIndex]) }
    private var title: String { EditAttribute(rawValue: remote.lastEditIndex)?.title ?? "" }

    private func valueText(_ v: Int) -> String {
        return v > 0 ? "+\(v)" : "\(v)"
    }

    private func chip(_ attr: EditAttribute) -> some View {
        let active = attr.rawValue == remote.lastEditIndex
        let v = Int(remote.editValues[attr.rawValue])
        return VStack(spacing: 2) {
            Text(attr.title)
                .font(.system(size: 12, weight: .semibold))
            Text(valueText(v))
                .font(.system(size: 12))
        }
        .foregroundColor(active ? Color.black : Color.white)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(active ? Color.white : Color.white.opacity(0.15))
        .cornerRadius(12)
    }

    var body: some View {
        VStack(spacing: 10) {
            HStack {
                Text(title)
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundColor(.white)
                Spacer()
                Text(valueText(value))
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundColor(.white)
            }
            ValueBar(value: value)
                .frame(height: 8)
            ScrollViewReader { proxy in
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(EditAttribute.allCases, id: \.rawValue) { attr in
                            chip(attr)
                                .id(attr.rawValue)
                        }
                    }
                    .padding(.horizontal, 2)
                }
                .onChange(of: remote.lastEditIndex) { newIndex in
                    withAnimation {
                        proxy.scrollTo(newIndex, anchor: .center)
                    }
                }
            }
            Text("Turn the bezel to change it. Swipe the watch for the next tool. Press Save on the watch.")
                .font(.system(size: 12))
                .foregroundColor(.gray)
                .multilineTextAlignment(.center)
        }
        .padding(16)
        .background(Color(white: 0.1).ignoresSafeArea(edges: .bottom))
    }
}

struct ViewerOverlay: View {
    let asset: PHAsset
    @EnvironmentObject var remote: RemoteModel
    @State private var image: UIImage?

    private var displayed: UIImage? {
        if remote.mode == .edit, let edited = remote.editRendered {
            return edited
        }
        return image
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 0) {
                ZStack {
                    if let shown = displayed {
                        Image(uiImage: shown)
                            .resizable()
                            .scaledToFit()
                            .scaleEffect(remote.zoom)
                            .animation(.easeOut(duration: 0.12), value: remote.zoom)
                            .gesture(
                                MagnificationGesture()
                                    .onChanged { value in
                                        remote.zoom = min(6, max(1, remote.pinchBase * value))
                                    }
                                    .onEnded { _ in
                                        remote.pinchBase = remote.zoom
                                    }
                            )
                    } else {
                        ProgressView()
                            .tint(.white)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .clipped()

                if remote.mode == .edit {
                    EditPanel()
                }
            }
            VStack {
                HStack {
                    Spacer()
                    Button {
                        remote.closeViewer()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 30))
                            .foregroundColor(.white)
                            .padding(14)
                    }
                }
                Spacer()
            }
            if !remote.notice.isEmpty {
                VStack {
                    Text(remote.notice)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(.white)
                        .multilineTextAlignment(.center)
                        .padding(12)
                        .background(Color.white.opacity(0.28))
                        .cornerRadius(12)
                        .padding(.horizontal, 24)
                        .padding(.top, 60)
                    Spacer()
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onAppear {
            load()
        }
    }

    private func load() {
        let options = PHImageRequestOptions()
        options.deliveryMode = .highQualityFormat
        options.isNetworkAccessAllowed = true
        PHImageManager.default().requestImage(
            for: asset,
            targetSize: CGSize(width: 2000, height: 2000),
            contentMode: .aspectFit,
            options: options
        ) { result, _ in
            if let result = result {
                DispatchQueue.main.async {
                    self.image = result
                }
            }
        }
    }
}
