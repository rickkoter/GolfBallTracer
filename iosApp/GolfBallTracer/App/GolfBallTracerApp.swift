import SwiftUI

/// GolfBallTracer for iPhone and iPad, as on Android: the Tracer View traces shots live from the camera and
/// the sound of the strike, and the Shot Log keeps every shot with its video, tracer and distance. The
/// tracking is the Android app's own code, shared from Kotlin.
@main
struct GolfBallTracerApp: App {
    @StateObject private var store: ShotStore
    @StateObject private var tracker: TrackerModel
    @StateObject private var router = AppRouter()
    @StateObject private var recordings = RecordingStore()

    init() {
        let store = ShotStore()
        _store = StateObject(wrappedValue: store)
        _tracker = StateObject(wrappedValue: TrackerModel(store: store))
    }

    var body: some Scene {
        WindowGroup {
            TabView(selection: $router.tab) {
                TrackerView()
                    .tabItem { Label("Tracer View", systemImage: "video.fill") }
                    .tag(AppRouter.Tab.tracer)
                ShotLogView()
                    .tabItem { Label("Shot Log", systemImage: "clock.arrow.circlepath") }
                    .tag(AppRouter.Tab.log)
            }
            .tint(.golfNeonLime)
            .preferredColorScheme(.dark)
            .environmentObject(store)
            .environmentObject(tracker)
            .environmentObject(router)
            .environmentObject(recordings)
            // A recording opened from another app (Files, Drive, Mail) is copied in, to replay it.
            .onOpenURL { url in
                recordings.importRecordings([url])
                router.showRecordings = true
            }
            .sheet(isPresented: $router.showRecordings) { RecordingsView().environmentObject(recordings) }
            .task { if ReplayAllCheck.requested { ReplayAllCheck.run(recordings.recordings) } }
        }
    }
}

/// Which tab is showing, and the shot being reviewed in the Shot Log.
@MainActor
final class AppRouter: ObservableObject {
    enum Tab: Hashable { case tracer, log }
    @Published var tab: Tab = .tracer
    /// The Shot Log's navigation: a shot's id opens its review.
    @Published var logPath: [String] = []
    @Published var showRecordings = false

    /// Opens a shot's review in the Shot Log, from wherever.
    func review(_ shotId: String) {
        logPath = [shotId]
        tab = .log
    }
}
