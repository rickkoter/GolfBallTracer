import SwiftUI

/// GolfBallTracer for iPhone and iPad: traces shots live from the camera and the sound of the strike,
/// with the same tracking code as the Android app (shared from Kotlin), and plays back recordings saved
/// by either app.
@main
struct GolfBallTracerApp: App {
    @StateObject private var store = RecordingStore()
    @State private var tab = ReplayAllCheck.requested ? 1 : 0

    var body: some Scene {
        WindowGroup {
            TabView(selection: $tab) {
                TrackerView()
                    .tabItem { Label("Tracer", systemImage: "scope") }
                    .tag(0)
                RecordingsView()
                    .tabItem { Label("Recordings", systemImage: "film.stack") }
                    .tag(1)
            }
            .environmentObject(store)
            // A recording opened from another app (Files, Drive, Mail) is copied in and listed.
            .onOpenURL { url in
                store.importRecordings([url])
                tab = 1
            }
            .task { if ReplayAllCheck.requested { ReplayAllCheck.run(store.recordings) } }
        }
    }
}
