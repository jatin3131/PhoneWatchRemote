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
    }
}

struct ConnectView: View {
    @EnvironmentObject var ble: BluetoothManager
    let onSkip: () -> Void

    private var message: String {
        switch ble.state {
        case .poweredOn: return "Bluetooth is on. Scan to find your watch."
        case .poweredOff: return "Bluetooth is off. Turn it on, then come back to this app."
        case .unauthorized: return "Bluetooth access is blocked for this app. Allow it in Settings."
        case .unsupported: return "This iPhone does not support Bluetooth."
        default: return "Checking Bluetooth..."
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

                Button(ble.isScanning ? "Scanning..." : "Scan for watches") {
                    ble.startScan()
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(ble.state != .poweredOn || ble.isScanning)
                .padding(.horizontal, 24)

                if !ble.statusText.isEmpty {
                    Text(ble.statusText)
                        .font(.system(size: 14))
                        .foregroundColor(.gray)
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

                Button("Skip for now (testing)") {
                    onSkip()
                }
                .font(.system(size: 14))
                .foregroundColor(.gray)
                .padding(.bottom, 12)
            }
        }
    }
}

struct MainView: View {
    @EnvironmentObject var settings: FeatureSettings
    @StateObject private var library = PhotoLibrary()
    let watchName: String
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
    }
}

struct GalleryView: View {
    @ObservedObject var library: PhotoLibrary

    private let columns = [
        GridItem(.flexible(), spacing: 2),
        GridItem(.flexible(), spacing: 2),
        GridItem(.flexible(), spacing: 2)
    ]

    var body: some View {
        ScrollView {
            if library.assets.isEmpty {
                Text("No photos found")
                    .foregroundColor(.gray)
                    .padding(40)
            } else {
                LazyVGrid(columns: columns, spacing: 2) {
                    ForEach(library.assets, id: \.localIdentifier) { asset in
                        NavigationLink(destination: PhotoViewerView(asset: asset)) {
                            ThumbnailView(asset: asset)
                        }
                    }
                }
            }
        }
        .background(Color.white)
        .navigationTitle("Gallery")
        .navigationBarTitleDisplayMode(.inline)
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

struct PhotoViewerView: View {
    let asset: PHAsset
    @State private var image: UIImage?
    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let image = image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .scaleEffect(scale)
                    .gesture(
                        MagnificationGesture()
                            .onChanged { value in
                                scale = max(1, lastScale * value)
                            }
                            .onEnded { _ in
                                lastScale = scale
                            }
                    )
            } else {
                ProgressView()
                    .tint(.white)
            }
        }
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
