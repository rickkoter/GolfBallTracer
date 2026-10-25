import SwiftUI
import GolfTracking

/// The Shot Log, as on Android: the totals (shots, average and longest distance, recordings used), every
/// shot with its snapshot, club, golfer, date and distance, finding a past shot's ball, deleting shots,
/// sharing every recording, exporting the history as CSV, and exporting the videos of selected shots with
/// their tracers (press and hold a shot to start selecting).
struct ShotLogView: View {
    @EnvironmentObject private var store: ShotStore
    @EnvironmentObject private var router: AppRouter
    @EnvironmentObject private var tracker: TrackerModel

    @State private var selected = Set<String>()
    @State private var exportProgress: Double?
    @State private var packing = false
    @State private var exportingCsv = false
    @State private var locating: ShotRecord?
    @State private var sharing: SharedFiles?
    @State private var toast: Toast?

    private var selecting: Bool { !selected.isEmpty }

    var body: some View {
        NavigationStack(path: $router.logPath) {
            ScrollView {
                VStack(spacing: 16) {
                    stats
                    if store.shots.isEmpty {
                        VStack(spacing: 8) {
                            Image(systemName: "figure.golf").font(.system(size: 44)).foregroundStyle(Color.golfTextMuted)
                            Text("No shots recorded yet").foregroundStyle(Color.golfTextSecondary)
                            Text("Record shots in the Tracer View to trace ball flight").font(.footnote).foregroundStyle(Color.golfTextMuted)
                        }
                        .padding(.top, 60)
                    } else {
                        LazyVStack(spacing: 12) {
                            ForEach(store.shots, id: \.id) { shot in
                                ShotLogRow(shot: shot, selecting: selecting, isSelected: selected.contains(shot.id),
                                           onLocate: { locating = shot },
                                           onDelete: { store.delete(shot.id) })
                                    .contentShape(Rectangle())
                                    .onTapGesture {
                                        if selecting { if exportProgress == nil { toggle(shot) } } else { router.logPath.append(shot.id) }
                                    }
                                    .onLongPressGesture { if exportProgress == nil { toggle(shot) } }
                            }
                        }
                    }
                }
                .padding(.horizontal, 16)
                // Room to scroll clear of the tab bar.
                .padding(.bottom, 80)
            }
            .background(Color.golfDarkBg)
            .navigationTitle(selecting ? "\(selected.count) selected" : "Shot History & Stats")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(selecting ? Color.golfDarkCard : Color.golfDarkBg, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbar { toolbar }
            .navigationDestination(for: String.self) { ShotReviewView(shotId: $0) }
            .overlay(alignment: .bottom) { toastView }
        }
        .onChange(of: store.shots.map(\.id)) { _, ids in selected = selected.filter { ids.contains($0) } }
        .fileExporter(isPresented: $exportingCsv, document: CsvDocument(text: ShotHistoryCsv.build(store.shots)),
                      contentType: .commaSeparatedText, defaultFilename: ShotHistoryCsv.fileName()) { result in
            if case .success = result { toast = Toast(text: "Exported \(store.shots.count) shot\(store.shots.count == 1 ? "" : "s")", share: nil) }
            else if case .failure = result { toast = Toast(text: "Couldn't save the export", share: nil) }
        }
        .sheet(item: $locating) { shot in
            ShotBallLocator(shot: shot).environmentObject(tracker.location).environmentObject(tracker.orientation)
        }
        .sheet(item: $sharing) { ShareSheet(items: $0.urls) }
    }

    private func toggle(_ shot: ShotRecord) {
        if selected.contains(shot.id) { selected.remove(shot.id) } else { selected.insert(shot.id) }
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        if selecting {
            ToolbarItem(placement: .topBarLeading) {
                Button { selected = [] } label: { Image(systemName: "xmark") }.disabled(exportProgress != nil).accessibilityLabel("Stop selecting")
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button { selected = Set(store.shots.map(\.id)) } label: { Image(systemName: "checklist") }
                    .disabled(exportProgress != nil).accessibilityLabel("Select all")
                Button(action: exportVideos) {
                    if let p = exportProgress { ProgressView(value: p).progressViewStyle(.circular).tint(Color.golfNeonLime) }
                    else { Image(systemName: "film").foregroundStyle(selectedWithVideo > 0 ? Color.golfNeonLime : Color.golfTextMuted) }
                }
                .disabled(exportProgress != nil || selectedWithVideo == 0)
                .accessibilityLabel("Export videos with tracer")
            }
        } else {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button(action: shareRecordings) {
                    if packing { ProgressView().tint(Color.golfNeonCyan) } else { Image(systemName: "video.fill") }
                }
                .foregroundStyle(store.recordingCount > 0 ? Color.golfNeonCyan : Color.golfTextMuted)
                .disabled(store.recordingCount == 0 || packing)
                .accessibilityLabel("Share all shot recordings")
                Button { exportingCsv = true } label: { Image(systemName: "square.and.arrow.down") }
                    .foregroundStyle(store.shots.isEmpty ? Color.golfTextMuted : Color.golfNeonLime)
                    .disabled(store.shots.isEmpty)
                    .accessibilityLabel("Export shot history")
            }
        }
    }

    private var selectedWithVideo: Int { store.shots.filter { selected.contains($0.id) && TracerVideoExporter.hasVideo($0) }.count }

    private var stats: some View {
        let distances = store.shots.compactMap { $0.distance?.yards }
        return HStack {
            stat("TOTAL SHOTS", "\(store.shots.count)", .white)
            Spacer()
            stat("AVG DISTANCE", distances.isEmpty ? "—" : String(format: "%.0f YDS", distances.reduce(0, +) / Double(distances.count)), .golfNeonLime)
            Spacer()
            stat("LONGEST SHOT", distances.max().map { String(format: "%.0f YDS", $0) } ?? "—", .golfNeonCyan)
            Spacer()
            stat("RECORDINGS", "\(store.recordingCount)/\(ShotStore.maxRecordings)",
                 store.recordingCount >= ShotStore.maxRecordings ? .golfNeonGold : .white)
        }
        .padding(18)
        .background(Color.golfDarkCard, in: RoundedRectangle(cornerRadius: 20))
    }

    private func stat(_ title: String, _ value: String, _ color: Color) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.system(size: 9, weight: .bold)).foregroundStyle(Color.golfTextSecondary)
            Text(value).font(.system(size: 20, weight: .bold)).foregroundStyle(color).lineLimit(1).minimumScaleFactor(0.6)
        }
    }

    /// Every shot recording as zips, for the share sheet: to Google Drive for testing and analysis later.
    private func shareRecordings() {
        packing = true
        Task {
            let urls = await ShotBundles.make(store.shots.filter { $0.hasRecording })
            packing = false
            if urls.isEmpty { toast = Toast(text: "No shot recordings to share", share: nil) } else { sharing = SharedFiles(urls: urls) }
        }
    }

    /// The selected shots' videos with their tracers, one video per shot.
    private func exportVideos() {
        let shots = store.shots.filter { selected.contains($0.id) }
        exportProgress = 0
        Task {
            let outcome = await TracerVideoExporter.export(shots) { p in Task { @MainActor in exportProgress = p } }
            exportProgress = nil
            selected = []
            toast = Toast(text: outcome.saved.isEmpty ? "No videos were made" + (outcome.withoutVideo > 0 ? " • \(outcome.withoutVideo) without a video" : "") : outcome.summary,
                          share: outcome.saved.isEmpty ? nil : outcome.saved)
        }
    }

    @ViewBuilder
    private var toastView: some View {
        if let toast {
            ToastView(toast: toast, onShare: { urls in sharing = SharedFiles(urls: urls); self.toast = nil }, onClose: { self.toast = nil })
        }
    }
}

/// One shot in the log: snapshot, club and golfer tags, date, recording, distance, and its buttons.
private struct ShotLogRow: View {
    let shot: ShotRecord
    let selecting: Bool
    let isSelected: Bool
    let onLocate: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HStack(spacing: 14) {
            ZStack {
                Color.golfDarkSurface
                if let url = shot.snapshotURL, let image = UIImage(contentsOfFile: url.path) {
                    Image(uiImage: image).resizable().scaledToFill()
                } else {
                    Image(systemName: "figure.golf").foregroundStyle(Color.golfNeonLime)
                }
                if isSelected {
                    Color.black.opacity(0.45)
                    Image(systemName: "checkmark.circle.fill").font(.system(size: 30)).foregroundStyle(Color.golfNeonLime)
                }
            }
            .frame(width: 70, height: 70)
            .clipShape(RoundedRectangle(cornerRadius: 12))

            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    tag(shot.club.uppercased(), .golfNeonLime)
                    if let golfer = shot.golferNumber { tag("GOLFER \(golfer)", .golfNeonCyan) }
                    if shot.hasRecording { Image(systemName: "video.fill").font(.system(size: 11)).foregroundStyle(Color.golfNeonCyan) }
                }
                Text(shot.date.formatted(.dateTime.month(.abbreviated).day().year().hour().minute()))
                    .font(.system(size: 11)).foregroundStyle(Color.golfTextMuted).lineLimit(1)
                HStack(alignment: .lastTextBaseline, spacing: 6) {
                    let distance = shot.distance
                    Text(distance?.label ?? "No distance").font(.system(size: 20, weight: .bold))
                        .foregroundStyle(distance != nil ? .white : Color.golfTextMuted)
                        .lineLimit(1).minimumScaleFactor(0.7)
                    if let distance { Text(distance.estimated ? "estimated" : "GPS").font(.system(size: 12)).foregroundStyle(Color.golfTextSecondary) }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            // While picking shots, the card's own buttons step aside.
            if !selecting {
                Button(action: onLocate) {
                    Image(systemName: "location.fill").foregroundStyle(shot.isLocatable ? Color.golfNeonCyan : Color.golfTextMuted.opacity(0.4))
                }
                .disabled(!shot.isLocatable)
                .buttonStyle(.borderless)
                .accessibilityLabel("Locate ball")
                Button(action: onDelete) { Image(systemName: "trash").foregroundStyle(Color.golfTextMuted) }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("Delete")
            }
        }
        .padding(12)
        .background(Color.golfDarkCard, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(isSelected ? Color.golfNeonLime : .clear, lineWidth: 2))
    }

    private func tag(_ text: String, _ color: Color) -> some View {
        Text(text).font(.system(size: 11, weight: .bold)).foregroundStyle(color).lineLimit(1).fixedSize()
            .padding(.horizontal, 8).padding(.vertical, 2)
            .background(color.opacity(0.2), in: RoundedRectangle(cornerRadius: 6))
    }
}

/// A message at the bottom of the screen, with Share when there's something to send on.
struct Toast: Identifiable {
    let id = UUID()
    let text: String
    let share: [URL]?
}

struct ToastView: View {
    let toast: Toast
    let onShare: ([URL]) -> Void
    let onClose: () -> Void

    var body: some View {
        HStack {
            Text(toast.text).font(.callout).foregroundStyle(.white)
            Spacer()
            if let urls = toast.share { Button("Share") { onShare(urls) }.foregroundStyle(Color.golfNeonLime).bold() }
            Button { onClose() } label: { Image(systemName: "xmark").foregroundStyle(Color.golfTextSecondary) }
        }
        .padding(14)
        .background(Color.golfDarkSurface, in: RoundedRectangle(cornerRadius: 12))
        .padding()
        .task(id: toast.id) {
            try? await Task.sleep(nanoseconds: 8_000_000_000)
            onClose()
        }
    }
}
