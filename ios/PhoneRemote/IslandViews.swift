import SwiftUI

struct IslandCard: View {
    @EnvironmentObject var island: IslandManager
    @EnvironmentObject var settings: FeatureSettings

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            VStack(alignment: .leading, spacing: 3) {
                Text("Dynamic Island")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundColor(.black)
                Text(island.statusText)
                    .font(.system(size: 13))
                    .foregroundColor(.gray)
                    .lineLimit(4)
            }
            Spacer()
            if settings.isOn("island") && !island.isRunning {
                Button("Start") {
                    island.restart()
                }
                .font(.system(size: 14, weight: .semibold))
                .foregroundColor(.white)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(Color.black)
                .cornerRadius(10)
            }
            Toggle("", isOn: settings.binding("island"))
                .labelsHidden()
                .tint(.black)
        }
        .padding(14)
        .background(Color(white: 0.96))
        .cornerRadius(14)
    }
}
