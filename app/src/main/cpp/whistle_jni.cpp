#include <jni.h>
#include <needle.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <cmath>
#include <algorithm>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
void *weights = MAP_FAILED;
size_t weightSize = 0;
std::string loadedPath;
long long loadMs = 0;
std::string utf8(JNIEnv *env, jstring input) {
    const char *value = env->GetStringUTFChars(input, nullptr);
    if (!value) throw std::runtime_error("Couldn't read recognition path.");
    std::string result(value); env->ReleaseStringUTFChars(input, value); return result;
}
long long elapsed(std::chrono::steady_clock::time_point start) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-start).count();
}
void load(const std::string &path) {
    loadMs = 0;
    if (path == loadedPath) return;
    auto start = std::chrono::steady_clock::now();
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) throw std::runtime_error("Couldn't open Whistle model.");
    struct stat info{};
    if (fstat(fd, &info) != 0 || info.st_size != 16919407) { close(fd); throw std::runtime_error("Invalid Whistle model size."); }
    void *next = mmap(nullptr, info.st_size, PROT_READ, MAP_PRIVATE, fd, 0); close(fd);
    if (next == MAP_FAILED) throw std::runtime_error("Couldn't map Whistle model.");
    // Disable the upstream optional telemetry before using its local C API.
    setenv("NEEDLE_TELEMETRY", "0", 1); setenv("DO_NOT_TRACK", "1", 1);
    if (needle_load(static_cast<const unsigned char*>(next), info.st_size) < 0) {
        const std::string error = needle_last_error() ? needle_last_error() : "Whistle load failed.";
        munmap(next, info.st_size); throw std::runtime_error(error);
    }
    if (weights != MAP_FAILED) munmap(weights, weightSize);
    weights = next; weightSize = info.st_size; loadedPath = path; loadMs = elapsed(start);
}
}

// Calls are serialized by RecognitionService. Process termination is the cancellation boundary
// because the upstream C API has no abort callback or model-unload function.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_readalong_data_transcription_WhistleNative_transcribe(JNIEnv *env, jobject, jstring model, jstring pcm) {
    try {
        load(utf8(env, model));
        std::ifstream source(utf8(env, pcm), std::ios::binary | std::ios::ate);
        if (!source) throw std::runtime_error("Couldn't open decoded audio.");
        const auto bytes = source.tellg();
        if (bytes < 4 || bytes > 30*16000*4 || bytes % 4 != 0) throw std::runtime_error("Invalid decoded audio length.");
        std::vector<float> samples(static_cast<size_t>(bytes)/4);
        source.seekg(0); source.read(reinterpret_cast<char*>(samples.data()), bytes);
        if (!source) throw std::runtime_error("Couldn't read decoded audio.");
        for (auto &sample : samples) {
            if (!std::isfinite(sample)) throw std::runtime_error("Invalid PCM sample.");
            // Float PCM can carry valid headroom above 0 dBFS. Whistle requires [-1,1].
            sample = std::clamp(sample, -1.0f, 1.0f);
        }
        std::vector<char> output(512*1024, 0);
        // Full decoder, upstream beam search defaults, multilingual detection, real attention times.
        if (needle_transcribe(samples.data(), samples.size(), nullptr, nullptr, 1, output.data(), output.size()) < 0)
            throw std::runtime_error(needle_last_error() ? needle_last_error() : "Whistle inference failed.");
        const auto length = strnlen(output.data(), output.size());
        if (length == output.size()) throw std::runtime_error("Whistle output exceeded its buffer.");
        jbyteArray result = env->NewByteArray(length);
        if (result) env->SetByteArrayRegion(result, 0, length, reinterpret_cast<const jbyte*>(output.data()));
        return result;
    } catch (const std::exception &error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what()); return nullptr;
    }
}
