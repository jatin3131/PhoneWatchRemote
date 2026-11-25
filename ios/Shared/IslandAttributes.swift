import ActivityKit
import Foundation

/// Shared by the app and the Dynamic Island extension.
struct IslandAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        var connected: Bool
        var battery: Int
        var charging: Bool
        var editing: Bool
        var tool: String
        var toolValue: Int
        var watchName: String
    }

    var name: String
}
