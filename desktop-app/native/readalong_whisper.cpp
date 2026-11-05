// ReadAlong for Windows: whisper.cpp for the transcriber process (see transcription/Worker.kt).
// Emits the same recognition JSON as the phone's whisper_jni.cpp, so TimedRecognitionResult reads both.
#include <jni.h>
#include <whisper.h>
#include <ggml-backend.h>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

static std::atomic<bool> cancelled{false};
static std::atomic<int> recognition_progress{-1};
static void report_progress(whisper_context *, whisper_state *, int value, void *) { recognition_progress.store(value); }
static bool abort_inference(void *) { return cancelled.load(); }
// Never log audiobook text: only warnings and errors (what failed and why) reach stderr, for the crash log.
static void quiet_log(enum ggml_log_level level, const char * text, void *) {
    if ((level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN) && text) { fputs(text, stderr); fflush(stderr); }
}

static std::string quoted(const char * text) {
    std::ostringstream out; out << '"';
    for (const unsigned char * p = reinterpret_cast<const unsigned char *>(text ? text : ""); *p; ++p) {
        if (*p == '"' || *p == '\\') out << '\\' << *p;
        else if (*p < 32) { const char * hex = "0123456789abcdef"; out << "\\u00" << hex[*p >> 4] << hex[*p & 15]; }
        else out << *p;
    }
    out << '"'; return out.str();
}
static std::string string_of(JNIEnv * env, jstring value) {
    if (!value) return "";
    const char * chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars); env->ReleaseStringUTFChars(value, chars); return result;
}
/**
 * Real UTF-8 into a Java string. NewStringUTF wants Java's "modified UTF-8": a stray byte (a token that ends half way
 * through a character like ’ or —) makes it swallow the quote after it, and the JSON can't be read. Decoding the
 * bytes with String(byte[], "UTF-8") turns anything malformed into U+FFFD and never touches the bytes around it.
 */
static jstring java_string(JNIEnv * env, const std::string & value) {
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(value.size()));
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(value.size()), reinterpret_cast<const jbyte *>(value.data()));
    jclass string_class = env->FindClass("java/lang/String");
    jmethodID init = env->GetMethodID(string_class, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    auto result = static_cast<jstring>(env->NewObject(string_class, init, bytes, charset));
    env->DeleteLocalRef(charset); env->DeleteLocalRef(bytes); env->DeleteLocalRef(string_class);
    return result;
}

/** How many bytes at the end of [text] start a UTF-8 character that isn't finished yet (0 when it ends cleanly). */
static size_t unfinished_tail(const std::string & text) {
    const size_t n = text.size();
    for (size_t back = 1; back <= 3 && back <= n; ++back) {
        const unsigned char c = static_cast<unsigned char>(text[n - back]);
        if ((c & 0xC0) == 0x80) continue;                 // continuation byte: keep looking for the lead
        const size_t need = c >= 0xF0 ? 4 : c >= 0xE0 ? 3 : c >= 0xC0 ? 2 : 1;
        return need > back ? back : 0;
    }
    return 0;
}
static void throw_io(JNIEnv * env, const char * message) { env->ThrowNew(env->FindClass("java/io/IOException"), message); }

#define NATIVE(name) Java_com_example_readalong_desktop_transcription_WhisperNative_##name

/** Loads the CPU variants and the GPU backends (Vulkan) that this computer's drivers can run. */
extern "C" JNIEXPORT void JNICALL NATIVE(loadBackends)(JNIEnv * env, jobject, jstring directory, jboolean verbose) {
    if (!verbose) whisper_log_set(quiet_log, nullptr);
    const std::string dir = string_of(env, directory);
    if (dir.empty()) ggml_backend_load_all(); else ggml_backend_load_all_from_path(dir.c_str());
}

/** Every device ggml can use, as JSON: name, description, type (cpu, gpu, igpu), memory in bytes. */
extern "C" JNIEXPORT jstring JNICALL NATIVE(devices)(JNIEnv * env, jobject) {
    std::ostringstream out; out << '[';
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t device = ggml_backend_dev_get(i);
        ggml_backend_dev_props props{};
        ggml_backend_dev_get_props(device, &props);
        const char * type = props.type == GGML_BACKEND_DEVICE_TYPE_GPU ? "gpu" : props.type == GGML_BACKEND_DEVICE_TYPE_IGPU ? "igpu"
            : props.type == GGML_BACKEND_DEVICE_TYPE_CPU ? "cpu" : "accel";
        if (i) out << ',';
        out << "{\"name\":" << quoted(props.name) << ",\"description\":" << quoted(props.description)
            << ",\"backend\":" << quoted(ggml_backend_reg_name(ggml_backend_dev_backend_reg(device)))
            << ",\"type\":\"" << type << "\",\"memoryTotal\":" << props.memory_total << ",\"memoryFree\":" << props.memory_free << '}';
    }
    out << ']';
    return java_string(env, out.str());
}

extern "C" JNIEXPORT jstring JNICALL NATIVE(systemInfo)(JNIEnv * env, jobject) { return java_string(env, whisper_print_system_info()); }

/** [gpuDevice] counts GPUs only (whisper.cpp's numbering); -1 runs on the CPU. Returns 0 if the model can't load. */
extern "C" JNIEXPORT jlong JNICALL NATIVE(load)(JNIEnv * env, jobject, jstring modelPath, jint gpuDevice, jint alignmentHeads) {
    auto params = whisper_context_default_params();
    params.use_gpu = gpuDevice >= 0;
    params.gpu_device = gpuDevice >= 0 ? gpuDevice : 0;
    // Attention-based word times avoid stretching words across music and pauses. DTW needs the
    // cross-attention matrices, which flash attention doesn't keep.
    params.flash_attn = false;
    params.dtw_token_timestamps = alignmentHeads > 0;
    params.dtw_aheads_preset = static_cast<whisper_alignment_heads_preset>(alignmentHeads > 0 ? alignmentHeads : WHISPER_AHEADS_NONE);
    return reinterpret_cast<jlong>(whisper_init_from_file_with_params(string_of(env, modelPath).c_str(), params));
}

extern "C" JNIEXPORT void JNICALL NATIVE(free)(JNIEnv *, jobject, jlong handle) {
    if (handle) whisper_free(reinterpret_cast<whisper_context *>(handle));
}
extern "C" JNIEXPORT void JNICALL NATIVE(cancel)(JNIEnv *, jobject) { cancelled.store(true); }
extern "C" JNIEXPORT jint JNICALL NATIVE(progress)(JNIEnv *, jobject) { return recognition_progress.load(); }

/** 16 kHz mono samples in, recognition JSON out: [{startMs, endMs, text, noSpeech, tokens: [...]}]. */
extern "C" JNIEXPORT jstring JNICALL NATIVE(transcribe)(JNIEnv * env, jobject, jlong handle, jfloatArray audio,
        jstring language, jint threads, jint beams) {
    auto * context = reinterpret_cast<whisper_context *>(handle);
    if (!context) { throw_io(env, "The speech model isn't loaded"); return nullptr; }
    cancelled.store(false); recognition_progress.store(0);
    const jsize count = env->GetArrayLength(audio);
    std::vector<float> samples(static_cast<size_t>(count));
    env->GetFloatArrayRegion(audio, 0, count, samples.data());
    double power = 0;
    for (float value : samples) { if (!std::isfinite(value)) { throw_io(env, "Invalid decoded samples"); return nullptr; } power += value * value; }
    // Whisper invents phrases on digital silence; there is nothing to recognize.
    if (samples.empty() || power / samples.size() <= 0.000001) return java_string(env, "{\"language\":null,\"segments\":[]}");
    const std::string lang = string_of(env, language);
    auto params = whisper_full_default_params(beams > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads > 0 ? threads : 4;
    params.beam_search.beam_size = beams > 1 ? beams : 1;
    params.language = lang.empty() ? "auto" : lang.c_str();
    params.detect_language = false;
    params.translate = false; params.no_context = true;
    params.print_progress = false; params.print_realtime = false; params.print_timestamps = false; params.print_special = false;
    params.token_timestamps = true; params.suppress_nst = true;
    params.abort_callback = abort_inference;
    params.progress_callback = report_progress;
    if (whisper_full(context, params, samples.data(), static_cast<int>(samples.size())) != 0) {
        throw_io(env, cancelled.load() ? "Recognition cancelled" : "Speech recognition failed"); return nullptr;
    }
    std::ostringstream output;
    const int detected = whisper_full_lang_id(context);
    output << "{\"language\":" << (detected >= 0 ? quoted(whisper_lang_str(detected)) : "null") << ",\"segments\":[";
    for (int s = 0; s < whisper_full_n_segments(context); ++s) {
        if (s) output << ',';
        output << "{\"startMs\":" << whisper_full_get_segment_t0(context, s) * 10
               << ",\"endMs\":" << whisper_full_get_segment_t1(context, s) * 10
               << ",\"text\":" << quoted(whisper_full_get_segment_text(context, s))
               << ",\"noSpeech\":" << whisper_full_get_segment_no_speech_prob(context, s) << ",\"tokens\":[";
        bool first = true;
        // Whisper's tokens can split one character across two tokens; carry the unfinished bytes to the next one.
        std::string carry;
        for (int t = 0; t < whisper_full_n_tokens(context, s); ++t) {
            if (whisper_full_get_token_id(context, s, t) >= whisper_token_eot(context)) continue;
            auto token = whisper_full_get_token_data(context, s, t);
            const char * piece = whisper_full_get_token_text(context, s, t);
            std::string text = carry + (piece ? piece : "");
            const size_t tail = unfinished_tail(text);
            carry = text.substr(text.size() - tail); text.resize(text.size() - tail);
            if (text.empty()) continue;
            if (!first) output << ','; first = false;
            output << "{\"text\":" << quoted(text.c_str())
                   << ",\"startMs\":" << token.t0 * 10
                   << ",\"endMs\":" << token.t1 * 10
                   << ",\"dtwMs\":" << (token.t_dtw >= 0 ? token.t_dtw * 10 : -1)
                   << ",\"confidence\":" << token.p << '}';
        }
        output << "]}";
    }
    output << "]}";
    recognition_progress.store(100);
    return java_string(env, output.str());
}
