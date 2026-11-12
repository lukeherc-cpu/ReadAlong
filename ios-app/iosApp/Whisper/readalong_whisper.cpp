// ReadAlong for iPhone: whisper.cpp for the in-app transcriber (see ios-app/.../transcription/WhisperNative.kt).
// The desktop's readalong_whisper.cpp without JNI: the same recognition JSON, so TimedRecognitionResult reads it,
// and a cancel flag and progress per loaded model, since iOS runs every transcriber in the app's own process.
#include "readalong_whisper.h"
#include <whisper.h>
#include <ggml-backend.h>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <sstream>
#include <string>
#include <vector>

struct rw_model {
    whisper_context * context;
    std::atomic<bool> cancelled{false};
    std::atomic<int> progress{-1};
};

static void report_progress(whisper_context *, whisper_state *, int value, void * user) { static_cast<rw_model *>(user)->progress.store(value); }
static bool abort_inference(void * user) { return static_cast<rw_model *>(user)->cancelled.load(); }
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

static char * copy_out(const std::string & text) {
    char * result = static_cast<char *>(std::malloc(text.size() + 1));
    std::memcpy(result, text.c_str(), text.size() + 1);
    return result;
}

extern "C" void rw_init(int verbose) {
    if (!verbose) whisper_log_set(quiet_log, nullptr);
    ggml_backend_load_all();
}

extern "C" char * rw_devices(void) {
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
    return copy_out(out.str());
}

extern "C" char * rw_system_info(void) { return copy_out(whisper_print_system_info()); }

extern "C" void rw_free_string(char * text) { std::free(text); }

extern "C" void * rw_load(const char * model_path, int gpu_device, int alignment_heads) {
    auto params = whisper_context_default_params();
    params.use_gpu = gpu_device >= 0;
    params.gpu_device = gpu_device >= 0 ? gpu_device : 0;
    // Attention-based word times avoid stretching words across music and pauses. DTW needs the
    // cross-attention matrices, which flash attention doesn't keep.
    params.flash_attn = false;
    params.dtw_token_timestamps = alignment_heads > 0;
    params.dtw_aheads_preset = static_cast<whisper_alignment_heads_preset>(alignment_heads > 0 ? alignment_heads : WHISPER_AHEADS_NONE);
    whisper_context * context = whisper_init_from_file_with_params(model_path, params);
    if (!context) return nullptr;
    auto * model = new rw_model();
    model->context = context;
    return model;
}

extern "C" void rw_free(void * handle) {
    auto * model = static_cast<rw_model *>(handle);
    if (!model) return;
    whisper_free(model->context);
    delete model;
}

extern "C" void rw_cancel(void * handle) { if (handle) static_cast<rw_model *>(handle)->cancelled.store(true); }
extern "C" int rw_progress(void * handle) { return handle ? static_cast<rw_model *>(handle)->progress.load() : -1; }

/** 16 kHz mono samples in, recognition JSON out ({language, segments: [...]}); null when recognition failed. */
extern "C" char * rw_transcribe(void * handle, const float * audio, int count, const char * language, int threads, int beams) {
    auto * model = static_cast<rw_model *>(handle);
    if (!model) return nullptr;
    whisper_context * context = model->context;
    model->cancelled.store(false); model->progress.store(0);
    std::vector<float> samples(audio, audio + (count > 0 ? count : 0));
    double power = 0;
    for (float value : samples) { if (!std::isfinite(value)) return nullptr; power += value * value; }
    // Whisper invents phrases on digital silence; there is nothing to recognize.
    if (samples.empty() || power / samples.size() <= 0.000001) return copy_out("{\"language\":null,\"segments\":[]}");
    const std::string lang = language ? language : "";
    auto params = whisper_full_default_params(beams > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads > 0 ? threads : 4;
    params.beam_search.beam_size = beams > 1 ? beams : 1;
    params.language = lang.empty() ? "auto" : lang.c_str();
    params.detect_language = false;
    params.translate = false; params.no_context = true;
    params.print_progress = false; params.print_realtime = false; params.print_timestamps = false; params.print_special = false;
    params.token_timestamps = true; params.suppress_nst = true;
    params.abort_callback = abort_inference; params.abort_callback_user_data = model;
    params.progress_callback = report_progress; params.progress_callback_user_data = model;
    if (whisper_full(context, params, samples.data(), static_cast<int>(samples.size())) != 0) return nullptr;
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
    model->progress.store(100);
    return copy_out(output.str());
}
