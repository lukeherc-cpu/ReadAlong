#include <jni.h>
#include <whisper.h>
#include <atomic>
#include <fstream>
#include <sstream>
#include <vector>
#include <stdexcept>
#include <cmath>
#include <chrono>
#include <sys/stat.h>

// Access is serialized by RecognitionService. Retain one model only while its bound session lives.
static whisper_context * cached_context = nullptr;
static std::string cached_model;
static jlong cached_session = 0;
static struct stat cached_stat{};
static jlong last_metrics[3] = {0, 0, 0}; // load ms, inference ms, reused model
static void release_context() {
    if (cached_context) whisper_free(cached_context);
    cached_context = nullptr; cached_model.clear(); cached_session = 0;
}
extern "C" JNIEXPORT void JNICALL
Java_com_example_readalong_data_transcription_WhisperNative_release(JNIEnv *, jobject, jlong session) {
    if (cached_session == session) release_context();
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_example_readalong_data_transcription_WhisperNative_metrics(JNIEnv * env, jobject) {
    auto result = env->NewLongArray(3);
    if (result) env->SetLongArrayRegion(result, 0, 3, last_metrics);
    return result;
}

static std::atomic<bool> cancelled{false};
static std::atomic<int> recognition_progress{-2};
static void report_progress(whisper_context *, whisper_state *, int value, void *) { recognition_progress.store(value); }
extern "C" JNIEXPORT jint JNICALL
Java_com_example_readalong_data_transcription_WhisperNative_progress(JNIEnv *, jobject) { return recognition_progress.load(); }
static bool abort_inference(void *) { return cancelled.load(); }
extern "C" JNIEXPORT void JNICALL
Java_com_example_readalong_data_transcription_WhisperNative_reset(JNIEnv *, jobject) { cancelled.store(false); recognition_progress.store(-2); }
static void quiet_log(enum ggml_log_level, const char *, void *) {} // Never log audiobook text.
static std::string quoted(const char * text) {
    std::ostringstream out; out << '"';
    for (const unsigned char * p = reinterpret_cast<const unsigned char *>(text); *p; ++p) {
        if (*p == '"' || *p == '\\') out << '\\' << *p;
        else if (*p < 32) { const char * hex = "0123456789abcdef"; out << "\\u00" << hex[*p >> 4] << hex[*p & 15]; }
        else out << *p;
    }
    out << '"'; return out.str();
}
extern "C" JNIEXPORT void JNICALL
Java_com_example_readalong_data_transcription_WhisperNative_cancel(JNIEnv *, jobject) { cancelled.store(true); }

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_readalong_data_transcription_WhisperNative_transcribe(JNIEnv * env, jobject, jstring modelPath, jstring pcmPath, jint threads, jlong session) {
    recognition_progress.store(-2);
    whisper_context * context = nullptr;
    last_metrics[0] = last_metrics[1] = last_metrics[2] = 0;
    const char * model = env->GetStringUTFChars(modelPath, nullptr);
    const char * pcm = env->GetStringUTFChars(pcmPath, nullptr);
    try {
        whisper_log_set(quiet_log, nullptr);
        if (cancelled.load()) throw std::runtime_error("Recognition cancelled");
        std::ifstream audio(pcm, std::ios::binary | std::ios::ate);
        auto size = audio.tellg();
        if (!audio || size <= 0 || size > 30 * 16000 * 4 || size % 4 != 0) throw std::runtime_error("Invalid decoded audio chunk");
        std::vector<float> samples(static_cast<size_t>(size) / 4);
        audio.seekg(0); audio.read(reinterpret_cast<char *>(samples.data()), size);
        if (!audio) throw std::runtime_error("Couldn't read decoded audio");
        double power = 0;
        for (float value : samples) { if (!std::isfinite(value)) throw std::runtime_error("Invalid decoded samples"); power += value * value; }
        std::string json = "[]";
        // Avoid running Whisper on digital silence, which can produce hallucinated phrases.
        if (power / samples.size() > 0.000001) {
            auto cp = whisper_context_default_params(); cp.use_gpu = false;
            // Attention-based alignment avoids stretching early words across music/silence.
            // DTW needs the cross-attention matrices, which flash attention does not retain.
            cp.flash_attn = false;
            cp.dtw_token_timestamps = true;
            const std::string model_name(model);
            cp.dtw_aheads_preset = model_name.find("tiny.en") != std::string::npos ? WHISPER_AHEADS_TINY_EN
                : model_name.find("small.en") != std::string::npos ? WHISPER_AHEADS_SMALL_EN : WHISPER_AHEADS_BASE_EN;
            struct stat identity{};
            if (stat(model, &identity) != 0) throw std::runtime_error("Couldn't open the speech model");
            const bool reusable = cached_context && cached_session == session && cached_model == model &&
                cached_stat.st_ino == identity.st_ino && cached_stat.st_size == identity.st_size &&
                cached_stat.st_mtim.tv_sec == identity.st_mtim.tv_sec && cached_stat.st_mtim.tv_nsec == identity.st_mtim.tv_nsec;
            const auto load_start = std::chrono::steady_clock::now();
            if (!reusable) {
                release_context();
                cached_context = whisper_init_from_file_with_params(model, cp);
                cached_session = session; cached_model = model; cached_stat = identity;
            }
            context = cached_context;
            last_metrics[0] = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - load_start).count();
            last_metrics[2] = reusable ? 1 : 0;
            if (!context) throw std::runtime_error("Couldn't load the speech model");
            if (cancelled.load()) throw std::runtime_error("Recognition cancelled");
            recognition_progress.store(0);
            auto params = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
            params.n_threads = threads > 0 ? threads : 2;
            params.beam_search.beam_size = 5;
            params.language = "en"; params.translate = false; params.no_context = true;
            params.print_progress = false; params.print_realtime = false; params.print_timestamps = false; params.print_special = false;
            params.token_timestamps = true; params.suppress_nst = true;
            params.abort_callback = abort_inference;
            params.progress_callback = report_progress;
            const auto inference_start = std::chrono::steady_clock::now();
            int result = whisper_full(context, params, samples.data(), samples.size());
            last_metrics[1] = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - inference_start).count();
            if (cancelled.load()) throw std::runtime_error("Recognition cancelled");
            if (result != 0) throw std::runtime_error("Speech recognition failed");
            std::ostringstream output; output << '[';
            for (int s = 0; s < whisper_full_n_segments(context); ++s) {
                if (s) output << ',';
                output << "{\"startMs\":" << whisper_full_get_segment_t0(context, s) * 10
                       << ",\"endMs\":" << whisper_full_get_segment_t1(context, s) * 10
                       << ",\"text\":" << quoted(whisper_full_get_segment_text(context, s))
                       << ",\"noSpeech\":" << whisper_full_get_segment_no_speech_prob(context, s) << ",\"tokens\":[";
                bool first = true;
                for (int t = 0; t < whisper_full_n_tokens(context, s); ++t) {
                    if (whisper_full_get_token_id(context, s, t) >= whisper_token_eot(context)) continue;
                    auto token = whisper_full_get_token_data(context, s, t);
                    if (!first) output << ','; first = false;
                    output << "{\"text\":" << quoted(whisper_full_get_token_text(context, s, t))
                           << ",\"startMs\":" << whisper_full_get_token_t0(context, s, t) * 10
                           << ",\"endMs\":" << whisper_full_get_token_t1(context, s, t) * 10
                           << ",\"dtwMs\":" << (token.t_dtw >= 0 ? token.t_dtw * 10 : -1)
                           << ",\"confidence\":" << token.p << '}';
                }
                output << "]}";
            }
            output << ']'; json = output.str();
        }
        env->ReleaseStringUTFChars(modelPath, model); env->ReleaseStringUTFChars(pcmPath, pcm);
        auto bytes = env->NewByteArray(json.size());
        env->SetByteArrayRegion(bytes, 0, json.size(), reinterpret_cast<const jbyte *>(json.data()));
        return bytes;
    } catch (const std::exception & failure) {
        release_context();
        env->ReleaseStringUTFChars(modelPath, model); env->ReleaseStringUTFChars(pcmPath, pcm);
        env->ThrowNew(env->FindClass("java/io/IOException"), failure.what()); return nullptr;
    }
}
