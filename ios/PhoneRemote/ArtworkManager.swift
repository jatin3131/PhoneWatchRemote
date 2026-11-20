import SwiftUI

/// Looks up album covers online for whatever the watch says is playing, and sends them back.
final class ArtworkManager: ObservableObject {

    private weak var ble: BluetoothManager?
    private var cache: [String: Data] = [:]
    private var inFlight = Set<String>()

    func attach(ble: BluetoothManager) {
        self.ble = ble
        ble.onFrame = { [weak self] type, payload in
            self?.handle(type: type, payload: payload)
        }
    }

    private func handle(type: UInt8, payload: Data) {
        guard type == 0x51, let text = String(data: payload, encoding: .utf8) else { return }
        let parts = text.components(separatedBy: "\n")
        let title = parts.first ?? ""
        let artist = parts.count > 1 ? parts[1] : ""
        if title.isEmpty { return }
        let key = (title + "|" + artist).lowercased()
        if let cached = cache[key] {
            reply(title: title, jpeg: cached)
            return
        }
        if inFlight.contains(key) { return }
        inFlight.insert(key)
        lookup(title: title, artist: artist) { [weak self] data in
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.inFlight.remove(key)
                if let data = data {
                    self.cache[key] = data
                }
                self.reply(title: title, jpeg: data ?? Data())
            }
        }
    }

    private func reply(title: String, jpeg: Data) {
        var payload = Data(title.utf8)
        payload.append(10)
        payload.append(jpeg)
        ble?.sendMessage(type: 0x42, payload: payload)
    }

    private func normalize(_ s: String) -> String {
        return s.lowercased().filter { $0.isLetter || $0.isNumber }
    }

    private func smallJPEG(_ image: UIImage) -> Data? {
        let target = CGSize(width: 120, height: 120)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: target, format: format)
        let resized = renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
        return resized.jpegData(compressionQuality: 0.6)
    }

    private func lookup(title: String, artist: String, completion: @escaping (Data?) -> Void) {
        guard var comps = URLComponents(string: "https://itunes.apple.com/search") else {
            completion(nil)
            return
        }
        comps.queryItems = [
            URLQueryItem(name: "term", value: artist.isEmpty ? title : "\(artist) \(title)"),
            URLQueryItem(name: "media", value: "music"),
            URLQueryItem(name: "entity", value: "song"),
            URLQueryItem(name: "limit", value: "8")
        ]
        guard let url = comps.url else {
            completion(nil)
            return
        }
        URLSession.shared.dataTask(with: url) { data, _, _ in
            guard let data = data,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let results = json["results"] as? [[String: Any]] else {
                completion(nil)
                return
            }
            let wantTitle = self.normalize(title)
            let wantArtist = self.normalize(artist)
            var best: String? = nil
            for item in results {
                let name = self.normalize(item["trackName"] as? String ?? "")
                let by = self.normalize(item["artistName"] as? String ?? "")
                guard let art = item["artworkUrl100"] as? String else { continue }
                let titleOK = !name.isEmpty && (name == wantTitle || name.contains(wantTitle) || wantTitle.contains(name))
                let artistOK = wantArtist.isEmpty || (!by.isEmpty && (by.contains(wantArtist) || wantArtist.contains(by)))
                if titleOK && artistOK {
                    best = art
                    break
                }
            }
            guard let found = best,
                  let artURL = URL(string: found.replacingOccurrences(of: "100x100", with: "300x300")) else {
                completion(nil)
                return
            }
            URLSession.shared.dataTask(with: artURL) { imgData, _, _ in
                guard let imgData = imgData, let image = UIImage(data: imgData) else {
                    completion(nil)
                    return
                }
                completion(self.smallJPEG(image))
            }.resume()
        }.resume()
    }
}
