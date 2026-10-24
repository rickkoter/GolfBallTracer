import SwiftUI

/// GolfBallTracer for iPhone. This first version plays back recordings saved by the Android app
/// (shots and debug recordings) through the same tracking code, shared from Kotlin.
@main
struct GolfBallTracerApp: App {
    @StateObject private var store = RecordingStore()

    var body: some Scene {
        WindowGroup {
            RecordingsView()
                .environmentObject(store)
                // A recording opened from another app (Files, Drive, Mail) is copied in and listed.
                .onOpenURL { url in store.importRecordings([url]) }
                .task { if ReplayAllCheck.requested { ReplayAllCheck.run(store.recordings) } }
        }
    }
}
