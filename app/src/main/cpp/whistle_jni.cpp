#include <jni.h>
#include <atomic>
#include <cstdio>
#include <sched.h>
#include <android/log.h>
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


// ---- Thread shaping for Whistle's pool (active only inside needle_load) ----
extern "C" FILE *__real_fopen(const char *path, const char *mode);
extern "C" unsigned __real__ZNSt6__ndk16thread20hardware_concurrencyEv();
namespace {
constexpr int kMaxCpus = 64;
std::atomic<bool> shaping{false};
long shapedCapacity[kMaxCpus];
long shapedFrequency[kMaxCpus];
unsigned shapedConcurrency = 1;
int shapedThreads = 0;

long readLong(const char *path) {
    FILE *file = __real_fopen(path, "r");
    if (!file) return -1;
    long value = -1;
    if (fscanf(file, "%ld", &value) != 1) value = -1;
    fclose(file);
    return value;
}

/** Answers Whistle's sysfs questions so its "fast cores" are the fastest cores this process may use now. */
void prepareShape(const cpu_set_t &allowed) {
    long real[kMaxCpus]; int total = 0;
    char path[96];
    const bool haveCapacity = readLong("/sys/devices/system/cpu/cpu0/cpu_capacity") >= 0;
    for (; total < kMaxCpus; ++total) {
        snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%d/%s", total, haveCapacity ? "cpu_capacity" : "cpufreq/cpuinfo_max_freq");
        const long value = readLong(path);
        if (value < 0) break;
        real[total] = value;
    }
    long best = 0; int allowedCount = 0;
    for (int cpu = 0; cpu < total; ++cpu) if (CPU_ISSET(cpu, &allowed)) { best = std::max(best, real[cpu]); ++allowedCount; }
    // Whistle's own rule: a core is "fast" at 75% (capacity) or 70% (frequency) of the fastest.
    bool fast[kMaxCpus] = {};
    int fastCount = 0;
    for (int cpu = 0; cpu < total; ++cpu) {
        if (!CPU_ISSET(cpu, &allowed)) continue;
        const bool isFast = haveCapacity ? real[cpu] * 4 >= best * 3 : real[cpu] * 10 >= best * 7;
        if (isFast) { fast[cpu] = true; ++fastCount; }
    }
    // Every allowed core counts as fast (e.g. only the little cores remain): leave one free for the
    // decoder, the binder and the rest of the phone, so no worker waits a whole time slice for a core.
    if (fastCount == allowedCount && fastCount >= 3) {
        for (int cpu = 0; cpu < total; ++cpu) if (fast[cpu]) { fast[cpu] = false; --fastCount; break; }
    }
    for (int cpu = 0; cpu < kMaxCpus; ++cpu) {
        shapedCapacity[cpu] = fast[cpu] ? 1024 : 64;
        shapedFrequency[cpu] = fast[cpu] ? 3000000 : 300000;
    }
    // Used when fewer than two cores count as fast (Whistle then sizes the pool by core count).
    shapedConcurrency = static_cast<unsigned>(std::max(1, allowedCount >= 3 ? allowedCount - 1 : allowedCount));
    shapedThreads = fastCount >= 2 && fastCount < total ? std::min(fastCount, 4) : static_cast<int>(std::min(shapedConcurrency, 12u));
    __android_log_print(ANDROID_LOG_INFO, "ReadAlong", "Whistle pool: %d of %d cores allowed, %d fast, %d threads.", allowedCount, total, fastCount, shapedThreads);
}
}

extern "C" FILE *__wrap_fopen(const char *path, const char *mode) {
    if (shaping.load(std::memory_order_acquire) && path && strncmp(path, "/sys/devices/system/cpu/cpu", 27) == 0) {
        int cpu = -1; char rest[64] = {};
        if (sscanf(path + 27, "%d/%63s", &cpu, rest) == 2 && cpu >= 0 && cpu < kMaxCpus) {
            const bool capacity = strcmp(rest, "cpu_capacity") == 0;
            if (capacity || strcmp(rest, "cpufreq/cpuinfo_max_freq") == 0) {
                FILE *real = __real_fopen(path, mode);
                if (!real) return nullptr;   // keep Whistle's end-of-CPUs detection
                fclose(real);
                if (FILE *shaped = fmemopen(nullptr, 32, "w+")) {
                    fprintf(shaped, "%ld\n", capacity ? shapedCapacity[cpu] : shapedFrequency[cpu]);
                    rewind(shaped);
                    return shaped;
                }
            }
        }
    }
    return __real_fopen(path, mode);
}

extern "C" unsigned __wrap__ZNSt6__ndk16thread20hardware_concurrencyEv() {
    return shaping.load(std::memory_order_acquire) ? shapedConcurrency : __real__ZNSt6__ndk16thread20hardware_concurrencyEv();
}

namespace {
void *weights = MAP_FAILED;
size_t weightSize = 0;
std::string loadedPath;
long long loadMs = 0;
// CPUs this thread could use when Whistle built its thread pool. Android changes them when ReadAlong
// leaves or returns to the screen. Whistle sizes its pool from sysfs alone (fast cores by
// cpu_capacity, else hardware_concurrency) and pins one worker per fast core, so in the background
// it keeps 4 spinning workers even when some of those cores are no longer allowed. Its parallel_for
// splits work statically and waits for every worker, so one starved worker stalls each step.
// While the engine loads, the sysfs reads it makes are answered from the CPUs actually allowed now
// (see ThreadShape); with every core allowed the answers equal the real ones.
cpu_set_t loadedCpus;
bool haveLoadedCpus = false;
bool currentCpus(cpu_set_t *out) { CPU_ZERO(out); return sched_getaffinity(0, sizeof(cpu_set_t), out) == 0; }
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
    cpu_set_t cpus;
    const bool haveCpus = currentCpus(&cpus);
    const bool cpusChanged = haveLoadedCpus && haveCpus && !CPU_EQUAL(&cpus, &loadedCpus);
    if (path == loadedPath && !cpusChanged) return;
    if (cpusChanged) __android_log_print(ANDROID_LOG_INFO, "ReadAlong", "CPU set changed (%d -> %d cores); rebuilding Whistle's thread pool.", CPU_COUNT(&loadedCpus), CPU_COUNT(&cpus));
    auto start = std::chrono::steady_clock::now();
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) throw std::runtime_error("Couldn't open Whistle model.");
    struct stat info{};
    if (fstat(fd, &info) != 0 || info.st_size != 16919407) { close(fd); throw std::runtime_error("Invalid Whistle model size."); }
    void *next = mmap(nullptr, info.st_size, PROT_READ, MAP_PRIVATE, fd, 0); close(fd);
    if (next == MAP_FAILED) throw std::runtime_error("Couldn't map Whistle model.");
    // Disable the upstream optional telemetry before using its local C API.
    setenv("NEEDLE_TELEMETRY", "0", 1); setenv("DO_NOT_TRACK", "1", 1);
    if (haveCpus) prepareShape(cpus);
    shaping.store(haveCpus, std::memory_order_release);
    const int loaded = needle_load(static_cast<const unsigned char*>(next), info.st_size);
    shaping.store(false, std::memory_order_release);
    if (loaded < 0) {
        const std::string error = needle_last_error() ? needle_last_error() : "Whistle load failed.";
        munmap(next, info.st_size); throw std::runtime_error(error);
    }
    if (weights != MAP_FAILED) munmap(weights, weightSize);
    weights = next; weightSize = info.st_size; loadedPath = path; loadMs = elapsed(start);
    if (haveCpus) { loadedCpus = cpus; haveLoadedCpus = true; }
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
