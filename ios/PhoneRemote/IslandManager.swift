import SwiftUI
import ActivityKit
import Combine

/// Starts the Dynamic Island activity and keeps it up to date with the watch.
final class IslandManager: ObservableObject {

    @Published var statusText = "not started"
    @Published var isRunning = false

    private var activity: Activity<IslandAttributes>?
    private var cancellables = Set<AnyCancellable>()
    private weak var ble: BluetoothManager?
    private weak var remote: RemoteModel?
    private var settings: FeatureSettings?
    private var lastSent: IslandAttributes.ContentState?
    private var pending = false
    private var timer: Timer?

    func attach(ble: BluetoothManager, remote: RemoteModel, settings: FeatureSettings) {
        if !cancellables.isEmpty { return }
        self.ble = ble
        self.remote = remote
        self.settings = settings

        let triggers: [AnyPublisher<Void, Never>] = [
            ble.$connectedName.map { _ in () }.eraseToAnyPublisher(),
            remote.$watchBattery.map { _ in () }.eraseToAnyPublisher(),
            remote.$watchCharging.map { _ in () }.eraseToAnyPublisher(),
            remote.$mode.map { _ in () }.eraseToAnyPublisher(),
            remote.$lastEditIndex.map { _ in () }.eraseToAnyPublisher(),
            remote.$editValues.map { _ in () }.eraseToAnyPublisher()
        ]
        Publishers.MergeMany(triggers)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.scheduleUpdate() }
            .store(in: &cancellables)

        settings.$values
            .map { $0["island"] ?? true }
            .removeDuplicates()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in self?.push(force: true) }
            .store(in: &cancellables)

        NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.push(force: true)
        }
        timer = Timer.scheduledTimer(withTimeInterval: 240, repeats: true) { [weak self] _ in
            self?.push(force: true)
        }
        DispatchQueue.main.async { [weak self] in
            self?.push(force: true)
        }
    }

    func restart() {
        statusText = "starting..."
        push(force: true)
    }

    private func scheduleUpdate() {
        if pending { return }
        pending = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { [weak self] in
            self?.pending = false
            self?.push(force: false)
        }
    }

    private func currentState() -> IslandAttributes.ContentState {
        let connected = ble?.connectedName != nil
        var editing = false
        var tool = ""
        var value = 0
        if let r = remote, r.mode == .edit {
            editing = true
            let idx = r.lastEditIndex
            tool = EditAttribute(rawValue: idx)?.title ?? ""
            if idx >= 0 && idx < r.editValues.count {
                value = Int(r.editValues[idx])
            }
        }
        return IslandAttributes.ContentState(
            connected: connected,
            battery: remote?.watchBattery ?? -1,
            charging: remote?.watchCharging ?? false,
            editing: editing && connected,
            tool: tool,
            toolValue: value,
            watchName: ble?.connectedName ?? "Watch"
        )
    }

    private func alive(_ a: Activity<IslandAttributes>) -> Bool {
        return a.activityState != .ended && a.activityState != .dismissed
    }

    private func ensureStarted() {
        if let a = activity, alive(a) {
            isRunning = true
            return
        }
        activity = nil
        if let existing = Activity<IslandAttributes>.activities.first(where: { alive($0) }) {
            activity = existing
            isRunning = true
            statusText = "running"
            return
        }
        isRunning = false
        guard ActivityAuthorizationInfo().areActivitiesEnabled else {
            statusText = "Live Activities are off. Turn them on in Settings > Phone Remote > Live Activities."
            return
        }
        guard UIApplication.shared.applicationState == .active else {
            statusText = "Open the app to start the island."
            return
        }
        do {
            let content = ActivityContent(state: currentState(), staleDate: Date().addingTimeInterval(25 * 60))
            activity = try Activity.request(
                attributes: IslandAttributes(name: "Watch"),
                content: content,
                pushType: nil
            )
            isRunning = true
            statusText = "running"
        } catch {
            statusText = "could not start: \(error.localizedDescription)"
        }
    }

    private func endActivity() {
        isRunning = false
        statusText = "off"
        let list = Activity<IslandAttributes>.activities
        activity = nil
        lastSent = nil
        for a in list {
            Task {
                await a.end(nil, dismissalPolicy: .immediate)
            }
        }
    }

    private func push(force: Bool) {
        guard settings?.isOn("island") ?? true else {
            endActivity()
            return
        }
        ensureStarted()
        guard let activity = activity else { return }
        let state = currentState()
        if !force && state == lastSent { return }
        lastSent = state
        let content = ActivityContent(state: state, staleDate: Date().addingTimeInterval(25 * 60))
        Task {
            await activity.update(content)
        }
    }
}
