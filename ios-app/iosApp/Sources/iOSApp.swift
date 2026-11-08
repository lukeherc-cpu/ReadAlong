import SwiftUI
import UIKit
import ReadAlongKit

@main
struct ReadAlongApp: App {
    @Environment(\.scenePhase) private var phase

    init() {
        // Before the Kotlin app starts, so its transcription queue finds whisper.cpp.
        WhisperNative.shared.engine = WhisperBridge()
    }

    var body: some Scene {
        WindowGroup {
            ComposeView().ignoresSafeArea(.all)
        }
        .onChange(of: phase) { next in
            if next != .active { MainViewControllerKt.appWillResignActive() }
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController { MainViewControllerKt.MainViewController() }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
