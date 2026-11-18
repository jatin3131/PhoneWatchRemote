import SwiftUI
import Photos

final class PhotoLibrary: ObservableObject {
    @Published var status: PHAuthorizationStatus = PHPhotoLibrary.authorizationStatus(for: .readWrite)
    @Published var assets: [PHAsset] = []

    var isLimited: Bool { status == .limited }
    var hasAccess: Bool { status == .authorized || status == .limited }

    func requestAccess(completion: @escaping (Bool) -> Void) {
        PHPhotoLibrary.requestAuthorization(for: .readWrite) { newStatus in
            DispatchQueue.main.async {
                self.status = newStatus
                let granted = (newStatus == .authorized || newStatus == .limited)
                if granted {
                    self.load()
                }
                completion(granted)
            }
        }
    }

    func load() {
        status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        let options = PHFetchOptions()
        options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        let result = PHAsset.fetchAssets(with: .image, options: options)
        var list: [PHAsset] = []
        list.reserveCapacity(result.count)
        result.enumerateObjects { asset, _, _ in
            list.append(asset)
        }
        assets = list
    }
}
