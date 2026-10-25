import Foundation
import GolfTracking

/// A check run on a phone or tablet over USB: launched with `-replayAll`, the app replays every recording
/// it holds and prints what each gives, so results can be compared with the Android replays without
/// looking at the screen:
///   xcrun devicectl device process launch --console --terminate-existing --device <id> com.koterapps.golfballtracer -replayAll
enum ReplayAllCheck {
    static var requested: Bool { ProcessInfo.processInfo.arguments.contains("-replayAll") }

    static func run(_ recordings: [Recording]) {
        Task.detached(priority: .userInitiated) {
            print("REPLAY-ALL: \(recordings.count) recordings")
            for recording in recordings.sorted(by: { $0.name < $1.name }) {
                let started = Date()
                guard let data = try? Data(contentsOf: recording.url) else {
                    print("REPLAY-ALL \(recording.name): couldn't read")
                    continue
                }
                let result = AppleBytesKt.replayRecording(contents: data)
                let shots = result.shots.map { shot -> String in
                    let ball = shot.ball.map { String(format: "ball (%.3f, %.3f): ", $0.x, $0.y) } ?? ""
                    return ball + shot.summary
                }
                print(String(format: "REPLAY-ALL %@: %d frames, %d strikes, %.1f s · ", recording.name, result.frameCount, result.strikes,
                             Date().timeIntervalSince(started)) + (shots.isEmpty ? "no shots" : shots.joined(separator: " | ")))
            }
            print("REPLAY-ALL: done")
        }
    }
}
