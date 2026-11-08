import Foundation
import Metal
import ReadAlongKit

/// whisper.cpp for the Kotlin side (WhisperNative.kt): each call goes straight to the C functions in
/// Whisper/readalong_whisper.cpp. Handles and the sample buffer cross as plain addresses.
final class WhisperBridge: NSObject, WhisperEngine {
    override init() {
        super.init()
        rw_init(0)
    }

    private func take(_ text: UnsafeMutablePointer<CChar>?) -> String? {
        guard let text else { return nil }
        defer { rw_free_string(text) }
        return String(cString: text)
    }

    func devices() -> String { take(rw_devices()) ?? "[]" }
    func systemInfo() -> String { take(rw_system_info()) ?? "" }

    /// ggml's Metal kernels need SIMD-group reductions (A13 Bionic and later). Older iPhones, such as the
    /// iPhone X (A11), run whisper on the CPU, which tiny.en and base.en handle in reasonable time.
    private let metalWorks: Bool = MTLCreateSystemDefaultDevice()?.supportsFamily(.apple6) ?? false

    func load(model: String, gpuDevice: Int32, alignmentHeads: Int32) -> Int64 {
        guard let handle = rw_load(model, metalWorks ? gpuDevice : -1, alignmentHeads) else { return 0 }
        return Int64(Int(bitPattern: handle))
    }

    private func pointer(_ handle: Int64) -> UnsafeMutableRawPointer? { UnsafeMutableRawPointer(bitPattern: Int(handle)) }

    func free(handle: Int64) { rw_free(pointer(handle)) }
    func cancel(handle: Int64) { rw_cancel(pointer(handle)) }
    func progress(handle: Int64) -> Int32 { rw_progress(pointer(handle)) }

    func transcribe(handle: Int64, samples: Int64, count: Int32, language: String, threads: Int32, beams: Int32) -> String? {
        let audio = UnsafePointer<Float>(bitPattern: Int(samples))
        return take(rw_transcribe(pointer(handle), audio, count, language, threads, beams))
    }
}
