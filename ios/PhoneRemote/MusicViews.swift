import SwiftUI

struct MusicCard: View {
    @EnvironmentObject var music: MusicManager

    private var headline: String {
        if !music.authorized {
            return "Music access is off"
        }
        return music.title.isEmpty ? "Nothing playing" : music.title
    }

    private var subline: String {
        if !music.authorized {
            return "Status: \(music.statusText). Tap Allow so the watch can show and control your music."
        }
        if music.title.isEmpty {
            return "Start a song in the Music app. Other music apps are not visible to this app."
        }
        return music.artist
    }

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 10)
                    .fill(Color(white: 0.88))
                if let art = music.artwork {
                    Image(uiImage: art)
                        .resizable()
                        .scaledToFill()
                } else {
                    Image(systemName: "music.note")
                        .foregroundColor(.gray)
                }
            }
            .frame(width: 56, height: 56)
            .clipShape(RoundedRectangle(cornerRadius: 10))

            VStack(alignment: .leading, spacing: 3) {
                Text(headline)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundColor(.black)
                    .lineLimit(1)
                Text(subline)
                    .font(.system(size: 13))
                    .foregroundColor(.gray)
                    .lineLimit(3)
            }
            Spacer()
            if !music.authorized {
                Button("Allow") {
                    music.requestOrOpenSettings()
                }
                .font(.system(size: 14, weight: .semibold))
                .foregroundColor(.white)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(Color.black)
                .cornerRadius(10)
            } else if !music.title.isEmpty {
                Image(systemName: music.isPlaying ? "waveform" : "pause.fill")
                    .foregroundColor(.gray)
            }
        }
        .padding(14)
        .background(Color(white: 0.96))
        .cornerRadius(14)
    }
}
