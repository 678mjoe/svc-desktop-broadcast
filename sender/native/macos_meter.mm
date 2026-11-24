#import <AppKit/AppKit.h>
#import <CoreAudio/CoreAudio.h>
#import <CoreAudio/AudioHardwareTapping.h>
#import <CoreAudio/CATapDescription.h>

#include <atomic>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <libproc.h>
#include <mach/mach_time.h>
#include <poll.h>
#include <sys/stat.h>
#include <unistd.h>
#include <algorithm>
#include <memory>
#include <string>
#include <thread>
#include <vector>

static constexpr size_t maxTargets = 4;
static volatile sig_atomic_t stopped;
static std::atomic<bool> controlClosed{false};
static void stop(int) { stopped = 1; }
static bool stopping() { return stopped || controlClosed.load(std::memory_order_relaxed); }

static AudioObjectPropertyAddress property(AudioObjectPropertySelector selector) {
    return {selector, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain};
}

template<typename T>
static OSStatus readProperty(AudioObjectID object, AudioObjectPropertySelector selector, T &value) {
    auto address = property(selector);
    UInt32 size = sizeof(value);
    return AudioObjectGetPropertyData(object, &address, 0, nullptr, &size, &value);
}

static int audioError(const char *step, OSStatus status) {
    fprintf(stderr, "%s failed (Core Audio %d). Check system audio permission if access was denied.\n",
            step, status);
    return 1;
}

static std::string processIdentity(pid_t pid) {
    proc_bsdinfo info{};
    if (proc_pidinfo(pid, PROC_PIDTBSDINFO, 0, &info, sizeof(info)) != sizeof(info)) return {};
    return std::to_string(info.pbi_start_tvsec) + ":" + std::to_string(info.pbi_start_tvusec);
}

static int listProcesses(bool withIdentity) {
    auto address = property(kAudioHardwarePropertyProcessObjectList);
    UInt32 size = 0;
    OSStatus status = AudioObjectGetPropertyDataSize(kAudioObjectSystemObject, &address, 0, nullptr, &size);
    if (status) return audioError("List processes", status);
    if (size == 0) return 0;
    std::vector<AudioObjectID> objects(size / sizeof(AudioObjectID));
    status = AudioObjectGetPropertyData(kAudioObjectSystemObject, &address, 0, nullptr, &size, objects.data());
    if (status) return audioError("Read processes", status);
    for (size_t index = 0; index < size / sizeof(AudioObjectID); ++index) {
        pid_t pid = 0;
        if (readProperty(objects[index], kAudioProcessPropertyPID, pid) || pid <= 0) continue;
        UInt32 output = 0;
        if (readProperty(objects[index], kAudioProcessPropertyIsRunningOutput, output) || !output) continue;
        CFStringRef bundle = nullptr;
        readProperty(objects[index], kAudioProcessPropertyBundleID, bundle);
        NSRunningApplication *app = [NSRunningApplication runningApplicationWithProcessIdentifier:pid];
        NSString *name = app.localizedName ?: @"(audio process)";
        const char *bundleName = bundle ? [(__bridge NSString *)bundle UTF8String] : "";
        if (withIdentity) {
            const auto identity = processIdentity(pid);
            if (!identity.empty()) {
                NSString *safeName = [[name componentsSeparatedByCharactersInSet:
                    [NSCharacterSet controlCharacterSet]] componentsJoinedByString:@" "];
                printf("%d:%s\t%s\t%s\n", pid, identity.c_str(), bundleName, safeName.UTF8String);
            }
        } else {
            printf("pid=%d  %s  %s\n", pid, name.UTF8String, bundleName);
        }
        if (bundle) CFRelease(bundle);
    }
    return 0;
}

struct Meter {
    std::atomic<uint64_t> samples{0};
    std::atomic<float> peak{0};
};

static_assert(std::atomic<float>::is_always_lock_free && std::atomic<uint64_t>::is_always_lock_free);

static constexpr uint32_t maxFrames = 4800;
static constexpr uint64_t maxAgeNs = 200000000;

static uint64_t captureClock(const mach_timebase_info_data_t &timebase) {
    const uint64_t ticks = mach_absolute_time();
    return ticks / timebase.denom * timebase.numer + ticks % timebase.denom * timebase.numer / timebase.denom;
}

struct AudioBlock {
    uint64_t capturedAt;
    uint64_t firstFrame;
    uint32_t frames;
    uint32_t reserved;
    float samples[maxFrames * 2];
};
static_assert(offsetof(AudioBlock, samples) == 24);

struct AudioPipe {
    static constexpr uint64_t slots = 8;
    AudioBlock blocks[slots];
    std::atomic<uint64_t> written{0};
    std::atomic<uint64_t> consumed{0};
    std::atomic<bool> invalid{false};
    uint64_t nextFrame = 0; // Only the Core Audio callback writes this.
    mach_timebase_info_data_t timebase{};
};

static OSStatus collect(AudioObjectID, const AudioTimeStamp *, const AudioBufferList *input,
                        const AudioTimeStamp *, AudioBufferList *, const AudioTimeStamp *, void *context) {
    auto &pipe = *static_cast<AudioPipe *>(context);
    if (!input || !input->mNumberBuffers || !input->mBuffers[0].mDataByteSize) return noErr;
    const auto &left = input->mBuffers[0];
    const bool interleaved = input->mNumberBuffers == 1 && left.mNumberChannels == 2;
    const bool planar = input->mNumberBuffers == 2 && left.mNumberChannels == 1
        && input->mBuffers[1].mNumberChannels == 1 && input->mBuffers[1].mDataByteSize == left.mDataByteSize;
    const uint32_t stride = sizeof(float) * (interleaved ? 2 : 1);
    const uint32_t frames = left.mDataByteSize / stride;
    if ((!interleaved && !planar) || left.mDataByteSize % stride || frames > maxFrames) {
        pipe.invalid.store(true, std::memory_order_relaxed);
        return noErr;
    }
    const uint64_t start = pipe.nextFrame;
    pipe.nextFrame += frames;
    const auto written = pipe.written.load(std::memory_order_relaxed);
    if (written - pipe.consumed.load(std::memory_order_acquire) == AudioPipe::slots) return noErr;

    // Single producer and single consumer. The real-time callback never waits or writes to a pipe.
    auto &block = pipe.blocks[written % AudioPipe::slots];
    block.capturedAt = captureClock(pipe.timebase);
    block.firstFrame = start;
    block.frames = frames;
    block.reserved = 0;
    if (interleaved && left.mData) {
        memcpy(block.samples, left.mData, left.mDataByteSize);
    } else if (interleaved) {
        memset(block.samples, 0, left.mDataByteSize);
    } else {
        const auto l = static_cast<const float *>(left.mData);
        const auto r = static_cast<const float *>(input->mBuffers[1].mData);
        for (uint32_t frame = 0; frame < frames; ++frame) {
            block.samples[frame * 2] = l ? l[frame] : 0;
            block.samples[frame * 2 + 1] = r ? r[frame] : 0;
        }
    }
    pipe.written.store(written + 1, std::memory_order_release);
    return noErr;
}

static OSStatus measure(AudioObjectID, const AudioTimeStamp *, const AudioBufferList *input,
                        const AudioTimeStamp *, AudioBufferList *, const AudioTimeStamp *, void *context) {
    if (!input) return noErr;
    auto &meter = *static_cast<Meter *>(context);
    uint64_t samples = 0;
    float peak = 0;
    for (UInt32 index = 0; index < input->mNumberBuffers; ++index) {
        const AudioBuffer &buffer = input->mBuffers[index];
        if (!buffer.mData) continue;
        auto data = static_cast<const float *>(buffer.mData);
        size_t count = buffer.mDataByteSize / sizeof(float);
        for (size_t sample = 0; sample < count; ++sample) {
            float magnitude = std::fabs(data[sample]);
            if (magnitude > peak && std::isfinite(magnitude)) peak = magnitude;
        }
        samples += count;
    }
    float previous = meter.peak.load(std::memory_order_relaxed);
    while (peak > previous && !meter.peak.compare_exchange_weak(previous, peak, std::memory_order_relaxed)) {}
    meter.samples.fetch_add(samples, std::memory_order_relaxed);
    return noErr;
}

struct Capture {
    Meter meter;
    AudioObjectID tap = kAudioObjectUnknown;
    AudioObjectID device = kAudioObjectUnknown;
    AudioDeviceIOProcID io = nullptr;
    bool running = false;

    ~Capture() {
        if (running) AudioDeviceStop(device, io);
        if (io) AudioDeviceDestroyIOProcID(device, io);
        if (device != kAudioObjectUnknown) AudioHardwareDestroyAggregateDevice(device);
        if (tap != kAudioObjectUnknown) AudioHardwareDestroyProcessTap(tap);
    }
};

static bool targetsAlive(const std::vector<pid_t> &pids, NSArray<NSNumber *> *processes) {
    for (size_t index = 0; index < pids.size(); ++index) {
        pid_t current = 0;
        if (readProperty(processes[index].unsignedIntValue, kAudioProcessPropertyPID, current)
                || current != pids[index]) {
            fprintf(stderr, "Selected Core Audio process %d is no longer available.\n", pids[index]);
            return false;
        }
    }
    return true;
}

static int streamAudio(AudioPipe &pipe, uint32_t rate, const std::vector<pid_t> &pids,
                       NSArray<NSNumber *> *processes) {
    if (fwrite("SVCT", 1, 4, stdout) != 4 || fwrite(&rate, sizeof(rate), 1, stdout) != 1 || fflush(stdout)) return 1;
    using namespace std::chrono;
    auto nextCheck = steady_clock::now() + std::chrono::seconds(1);
    while (!stopping()) {
        if (steady_clock::now() >= nextCheck) {
            if (!targetsAlive(pids, processes)) return 1;
            nextCheck = steady_clock::now() + std::chrono::seconds(1);
        }
        if (pipe.invalid.load(std::memory_order_relaxed)) {
            fprintf(stderr, "Unexpected Core Audio callback format.\n");
            return 1;
        }
        auto consumed = pipe.consumed.load(std::memory_order_relaxed);
        if (consumed == pipe.written.load(std::memory_order_acquire)) {
            usleep(2000);
            continue;
        }
        const auto &block = pipe.blocks[consumed % AudioPipe::slots];
        if (captureClock(pipe.timebase) - block.capturedAt < maxAgeNs) {
            const size_t size = offsetof(AudioBlock, samples) + block.frames * sizeof(float) * 2;
            if (fwrite(&block, 1, size, stdout) != size || fflush(stdout)) return 1;
        }
        pipe.consumed.store(consumed + 1, std::memory_order_release);
    }
    return 0;
}

static int check(const std::vector<pid_t> &pids, int seconds, bool streaming = false,
                 const std::vector<std::string> &identities = {}) {
    auto address = property(kAudioHardwarePropertyTranslatePIDToProcessObject);
    NSMutableArray<NSNumber *> *processes = [NSMutableArray arrayWithCapacity:pids.size()];
    for (pid_t pid : pids) {
        AudioObjectID process = kAudioObjectUnknown;
        UInt32 size = sizeof(process);
        OSStatus status = AudioObjectGetPropertyData(kAudioObjectSystemObject, &address, sizeof(pid), &pid, &size, &process);
        if (status || process == kAudioObjectUnknown) {
            fprintf(stderr, "PID %d has no Core Audio object. Start the player and run list again.\n", pid);
            return 1;
        }
        if (!identities.empty() && processIdentity(pid) != identities[processes.count]) {
            fprintf(stderr, "Selected Core Audio process changed. Select the player again.\n");
            return 1;
        }
        if ([processes containsObject:@(process)]) {
            fprintf(stderr, "PID %d resolves to an already selected Core Audio object.\n", pid);
            return 1;
        }
        [processes addObject:@(process)];
    }

    // The ring outlives Capture so it remains valid until the IO callback is stopped.
    std::unique_ptr<AudioPipe> pipe;
    Capture capture;
    if (stopping()) return 0;
    CATapDescription *description = [[CATapDescription alloc] initStereoMixdownOfProcesses:processes];
    description.privateTap = YES;
    description.muteBehavior = CATapUnmuted;
    if (@available(macOS 26, *)) description.processRestoreEnabled = NO;
    OSStatus status = AudioHardwareCreateProcessTap(description, &capture.tap);
    if (status) return audioError("Create process tap", status);
    if (stopping()) return 0;
    AudioStreamBasicDescription format = {};
    status = readProperty(capture.tap, kAudioTapPropertyFormat, format);
    if (status) return audioError("Read tap format", status);
    if (format.mFormatID != kAudioFormatLinearPCM || !(format.mFormatFlags & kAudioFormatFlagIsFloat)
            || (format.mFormatFlags & kAudioFormatFlagIsBigEndian) || format.mBitsPerChannel != 32
            || format.mChannelsPerFrame != 2 || format.mSampleRate <= 0) {
        fprintf(stderr, "Unsupported Core Audio tap format.\n");
        return 1;
    }
    if (streaming) {
        if (format.mSampleRate != 44100 && format.mSampleRate != 48000) {
            fprintf(stderr, "Core Audio stream requires a 44.1 or 48 kHz tap.\n");
            return 1;
        }
        pipe = std::make_unique<AudioPipe>();
        if (mach_timebase_info(&pipe->timebase) != KERN_SUCCESS || !pipe->timebase.denom) return 1;
    }

    NSDictionary *composition = @{
        @kAudioAggregateDeviceUIDKey: NSUUID.UUID.UUIDString,
        @kAudioAggregateDeviceNameKey: @"SVC Audio Check",
        @kAudioAggregateDeviceIsPrivateKey: @YES,
        @kAudioAggregateDeviceTapListKey: @[@{@kAudioSubTapUIDKey: description.UUID.UUIDString,
                                              @kAudioSubTapDriftCompensationKey: @YES}]
    };
    status = AudioHardwareCreateAggregateDevice((__bridge CFDictionaryRef)composition, &capture.device);
    if (status) return audioError("Create private aggregate device", status);
    status = AudioDeviceCreateIOProcID(capture.device, streaming ? collect : measure,
                                       streaming ? static_cast<void *>(pipe.get()) : &capture.meter, &capture.io);
    if (status) return audioError("Register tap callback", status);
    if (stopping()) return 0;
    status = AudioDeviceStart(capture.device, capture.io);
    if (status) return audioError("Start tap", status);
    capture.running = true;
    if (streaming) return streamAudio(*pipe, static_cast<uint32_t>(format.mSampleRate), pids, processes);
    printf("Checking pid%s=", pids.size() == 1 ? "" : "s");
    for (size_t index = 0; index < pids.size(); ++index) printf("%s%d", index ? "," : "", pids[index]);
    printf(" rate=%.0f channels=%u (Ctrl+C to stop)\n", format.mSampleRate, format.mChannelsPerFrame);
    fflush(stdout);

    using namespace std::chrono;
    auto next = steady_clock::now() + std::chrono::seconds(1);
    for (int second = 1; second <= seconds && !stopped; ++second) {
        while (!stopped && steady_clock::now() < next) usleep(25000);
        if (stopped) break;
        if (!targetsAlive(pids, processes)) return 1;
        const auto count = capture.meter.samples.exchange(0, std::memory_order_relaxed);
        const float peak = capture.meter.peak.exchange(0, std::memory_order_relaxed);
        printf("%3ds samples=%llu peak=%.6f %s\n", second, (unsigned long long)count, peak,
               !count ? "NO_SAMPLES" : (peak > 0 ? "SIGNAL" : "SILENT"));
        fflush(stdout);
        next += std::chrono::seconds(1);
    }
    return 0;
}

static long number(const char *text, long maximum) {
    if (*text < '0' || *text > '9') return 0;
    char *end = nullptr;
    errno = 0;
    long value = strtol(text, &end, 10);
    return !errno && end != text && !*end && value > 0 && value <= maximum ? value : 0;
}

static std::vector<pid_t> parseTargets(const std::string &selection, std::vector<std::string> *identities = nullptr) {
    std::vector<pid_t> pids;
    size_t start = 0;
    do {
        size_t comma = selection.find(',', start);
        auto entry = selection.substr(start, comma - start);
        if (identities) {
            const auto colon = entry.find(':');
            if (colon == std::string::npos || colon + 1 == entry.size()) return {};
            identities->push_back(entry.substr(colon + 1));
            entry.resize(colon);
        }
        auto pid = static_cast<pid_t>(number(entry.c_str(), INT32_MAX));
        if (pid <= 1 || pid == getpid() || pids.size() == maxTargets
                || std::find(pids.begin(), pids.end(), pid) != pids.end()) return {};
        pids.push_back(pid);
        if (comma == std::string::npos) break;
        start = comma + 1;
    } while (true);
    return pids;
}

static int controlledStream(const std::vector<pid_t> &pids, const std::vector<std::string> &identities) {
    struct stat input{};
    if (fstat(STDIN_FILENO, &input) || !S_ISFIFO(input.st_mode)) return 2;

    std::atomic<bool> finished{false};
    std::thread control([&] {
        // No input messages are expected. A readable, closed or invalid pipe means stop.
        while (!finished.load(std::memory_order_acquire)) {
            struct pollfd pipe{STDIN_FILENO, POLLIN, 0};
            const int state = poll(&pipe, 1, 50);
            if (!state || (state < 0 && errno == EINTR)) continue;
            controlClosed.store(true, std::memory_order_relaxed);
            // This also handles an abrupt parent exit during a blocked OS audio call.
            for (int attempt = 0; attempt < 40; ++attempt) {
                if (finished.load(std::memory_order_acquire)) return;
                usleep(50000);
            }
            if (!finished.load(std::memory_order_acquire)) _exit(1);
            return;
        }
    });
    const int result = check(pids, 0, true, identities);
    finished.store(true, std::memory_order_release);
    control.join();
    return result;
}

int main(int argc, const char **argv) {
    @autoreleasepool {
        if (argc == 2 && !strcmp(argv[1], "list")) return listProcesses(false);
        if (argc == 2 && !strcmp(argv[1], "list-identities")) return listProcesses(true);
        const bool controlled = argc == 3 && !strcmp(argv[1], "stream-checked-controlled");
        if (argc == 3 && (controlled || !strcmp(argv[1], "stream") || !strcmp(argv[1], "stream-checked"))) {
            std::vector<std::string> identities;
            const auto pids = parseTargets(argv[2], strcmp(argv[1], "stream") ? &identities : nullptr);
            struct stat output{};
            if (!pids.empty() && !fstat(STDOUT_FILENO, &output) && S_ISFIFO(output.st_mode)) {
                std::signal(SIGINT, stop);
                std::signal(SIGTERM, stop);
                std::signal(SIGPIPE, SIG_IGN);
                return controlled ? controlledStream(pids, identities) : check(pids, 0, true, identities);
            }
        }
        if (argc == 4 && !strcmp(argv[1], "check")) {
            const auto pids = parseTargets(argv[2]);
            int seconds = static_cast<int>(number(argv[3], 120));
            if (!pids.empty() && seconds) {
                std::signal(SIGINT, stop);
                std::signal(SIGTERM, stop);
                return check(pids, seconds);
            }
        }
        fprintf(stderr, "Usage: %s list | check PID[,PID...] SECONDS (1-120, up to 4 distinct PIDs)\n", argv[0]);
        return argc == 2 && !strcmp(argv[1], "--help") ? 0 : 2;
    }
}
