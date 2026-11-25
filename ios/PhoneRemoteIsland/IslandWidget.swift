import ActivityKit
import WidgetKit
import SwiftUI

@main
struct IslandBundle: WidgetBundle {
    var body: some Widget {
        IslandLiveActivity()
    }
}

private func isLive(_ s: IslandAttributes.ContentState, _ stale: Bool) -> Bool {
    return s.connected && !stale
}

private func ringColor(_ s: IslandAttributes.ContentState, _ stale: Bool) -> Color {
    if !isLive(s, stale) { return Color.gray }
    if s.editing { return Color.orange }
    if s.charging { return Color.green }
    if s.battery >= 50 { return Color.green }
    if s.battery >= 20 { return Color.yellow }
    return Color.red
}

private func valueText(_ s: IslandAttributes.ContentState, _ stale: Bool) -> String {
    if !isLive(s, stale) { return "--" }
    if s.editing { return s.toolValue > 0 ? "+\(s.toolValue)" : "\(s.toolValue)" }
    return s.battery >= 0 ? "\(s.battery)%" : "--"
}

private func statusLine(_ s: IslandAttributes.ContentState, _ stale: Bool) -> String {
    if !s.connected { return "Watch not connected" }
    if stale { return "No recent update" }
    if s.editing { return "Editing: \(s.tool)" }
    let bolt = s.charging ? "  charging" : ""
    return "\(s.watchName)  \(s.battery >= 0 ? "\(s.battery)%" : "--")\(bolt)"
}

/// A vertical 2D watch: strap above, face in the middle, strap below.
struct WatchGlyph: View {
    let state: IslandAttributes.ContentState
    let stale: Bool
    let scale: CGFloat
    let showText: Bool

    private var progress: Double {
        if state.editing && isLive(state, stale) {
            return Double(state.toolValue + 100) / 200.0
        }
        return state.battery >= 0 ? Double(state.battery) / 100.0 : 0
    }

    private var strap: Color {
        return isLive(state, stale) ? Color(red: 0.2, green: 0.78, blue: 0.35) : Color(white: 0.4)
    }

    var body: some View {
        let w = 50 * scale
        let h = 62 * scale
        let ring = CGFloat(max(0.03, min(1.0, progress)))
        VStack(spacing: 2 * scale) {
            RoundedRectangle(cornerRadius: 4 * scale)
                .fill(strap)
                .frame(width: w * 0.62, height: 15 * scale)
            ZStack {
                RoundedRectangle(cornerRadius: 13 * scale)
                    .fill(Color.black)
                RoundedRectangle(cornerRadius: 13 * scale)
                    .stroke(Color.white.opacity(0.3), lineWidth: 2 * scale)
                Circle()
                    .stroke(Color.white.opacity(0.15), lineWidth: 3.5 * scale)
                    .padding(8 * scale)
                Circle()
                    .trim(from: 0, to: ring)
                    .stroke(ringColor(state, stale), style: StrokeStyle(lineWidth: 3.5 * scale, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .padding(8 * scale)
                if showText {
                    Text(valueText(state, stale))
                        .font(.system(size: 13 * scale, weight: .bold))
                        .foregroundColor(.white)
                        .minimumScaleFactor(0.5)
                        .lineLimit(1)
                }
            }
            .frame(width: w, height: h)
            RoundedRectangle(cornerRadius: 4 * scale)
                .fill(strap)
                .frame(width: w * 0.62, height: 15 * scale)
        }
    }
}

struct LockScreenView: View {
    let state: IslandAttributes.ContentState
    let stale: Bool

    var body: some View {
        HStack(spacing: 14) {
            WatchGlyph(state: state, stale: stale, scale: 0.75, showText: true)
            VStack(alignment: .leading, spacing: 4) {
                Text(valueText(state, stale))
                    .font(.system(size: 28, weight: .bold))
                    .foregroundColor(.white)
                Text(statusLine(state, stale))
                    .font(.system(size: 14))
                    .foregroundColor(Color(white: 0.8))
            }
            Spacer()
        }
        .padding(14)
    }
}

struct IslandLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: IslandAttributes.self) { context in
            LockScreenView(state: context.state, stale: context.isStale)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    WatchGlyph(state: context.state, stale: context.isStale, scale: 0.62, showText: true)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    VStack(alignment: .trailing, spacing: 2) {
                        Text(valueText(context.state, context.isStale))
                            .font(.system(size: 26, weight: .bold))
                            .foregroundColor(ringColor(context.state, context.isStale))
                        Text(context.state.editing ? "edit" : "battery")
                            .font(.system(size: 11))
                            .foregroundColor(.gray)
                    }
                }
                DynamicIslandExpandedRegion(.bottom) {
                    Text(statusLine(context.state, context.isStale))
                        .font(.system(size: 13))
                        .foregroundColor(Color(white: 0.85))
                }
            } compactLeading: {
                WatchGlyph(state: context.state, stale: context.isStale, scale: 0.34, showText: false)
            } compactTrailing: {
                Text(valueText(context.state, context.isStale))
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(ringColor(context.state, context.isStale))
            } minimal: {
                WatchGlyph(state: context.state, stale: context.isStale, scale: 0.3, showText: false)
            }
            .keylineTint(Color.green)
        }
    }
}
