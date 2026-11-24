#define COBJMACROS
#define WIN32_LEAN_AND_MEAN
#define _WIN32_WINNT 0x0a00
#include <windows.h>
#include <aclapi.h>
#include <tlhelp32.h>
#include <winver.h>
#include <initguid.h>
#include <audioclient.h>
#include <audiopolicy.h>
#include <mmdeviceapi.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <wchar.h>
#include <fcntl.h>
#include <io.h>

// The MinGW headers shipped with Zig 0.16 omit audioclientactivationparams.h.
// The Windows SDK ABI is three consecutive 32-bit fields for process loopback.
typedef struct {
    DWORD activation_type;
    DWORD target_pid;
    DWORD loopback_mode;
} ProcessLoopbackParams;
enum { PROCESS_LOOPBACK_ACTIVATION = 1, INCLUDE_TARGET_TREE = 0 };
_Static_assert(sizeof(ProcessLoopbackParams) == 12, "Unexpected activation ABI");

typedef struct {
    IActivateAudioInterfaceCompletionHandler handler;
    LONG references;
    HANDLE ready;
    ProcessLoopbackParams params;
    PROPVARIANT variant;
} Completion;

enum { MAX_TARGETS = 4, CAPTURE_RATE = 44100, MAX_PACKET_FRAMES = CAPTURE_RATE / 5 };
static LONG stopped;
static HANDLE control_pipe;
static uint64_t qpc_frequency;

static BOOL WINAPI stop(DWORD event) {
    if (event != CTRL_C_EVENT && event != CTRL_BREAK_EVENT) return FALSE;
    InterlockedExchange(&stopped, 1);
    return TRUE;
}

static BOOL stopping(void) {
    return InterlockedCompareExchange(&stopped, 0, 0) != 0
        || (control_pipe && !PeekNamedPipe(control_pipe, NULL, 0, NULL, NULL, NULL));
}

static uint64_t qpcTime(void) {
    LARGE_INTEGER ticks;
    QueryPerformanceCounter(&ticks);
    uint64_t value = (uint64_t)ticks.QuadPart;
    return value / qpc_frequency * 10000000 + value % qpc_frequency * 10000000 / qpc_frequency;
}

// stdout in stream mode contains fixed LE records. A stream of zero denotes a heartbeat.
static HRESULT emitRecord(uint32_t stream, uint64_t timestamp, uint32_t frames, const BYTE *data) {
    struct { uint64_t timestamp; uint32_t stream; uint32_t frames; } header = {timestamp, stream, frames};
    size_t bytes = (size_t)frames * 4;
    static const BYTE silence[MAX_PACKET_FRAMES * 4];
    if (frames > MAX_PACKET_FRAMES || fwrite(&header, sizeof(header), 1, stdout) != 1
            || (bytes && fwrite(data ? data : silence, 1, bytes, stdout) != bytes) || fflush(stdout)) {
        return HRESULT_FROM_WIN32(ERROR_BROKEN_PIPE);
    }
    return S_OK;
}

static ULONG STDMETHODCALLTYPE retain(IActivateAudioInterfaceCompletionHandler *self) {
    return (ULONG)InterlockedIncrement(&((Completion *)self)->references);
}

static ULONG STDMETHODCALLTYPE release(IActivateAudioInterfaceCompletionHandler *self) {
    Completion *completion = (Completion *)self;
    ULONG remaining = (ULONG)InterlockedDecrement(&completion->references);
    if (!remaining) {
        CloseHandle(completion->ready);
        free(completion);
    }
    return remaining;
}

static HRESULT STDMETHODCALLTYPE query(IActivateAudioInterfaceCompletionHandler *self,
                                       REFIID iid, void **object) {
    // The callback only signals an event and uses atomic reference counting.
    if (IsEqualIID(iid, &IID_IUnknown) || IsEqualIID(iid, &IID_IAgileObject)
            || IsEqualIID(iid, &IID_IActivateAudioInterfaceCompletionHandler)) {
        *object = self;
        retain(self);
        return S_OK;
    }
    *object = NULL;
    return E_NOINTERFACE;
}

static HRESULT STDMETHODCALLTYPE complete(IActivateAudioInterfaceCompletionHandler *self,
                                          IActivateAudioInterfaceAsyncOperation *operation) {
    (void)operation;
    return SetEvent(((Completion *)self)->ready) ? S_OK : HRESULT_FROM_WIN32(GetLastError());
}

static IActivateAudioInterfaceCompletionHandlerVtbl completion_vtable = {
    query, retain, release, complete
};

static HRESULT activate(DWORD pid, HANDLE process, IAudioClient **client) {
    Completion *completion = calloc(1, sizeof(*completion));
    if (!completion) return E_OUTOFMEMORY;
    completion->handler.lpVtbl = &completion_vtable;
    completion->references = 1;
    completion->ready = CreateEventW(NULL, TRUE, FALSE, NULL);
    if (!completion->ready) {
        HRESULT hr = HRESULT_FROM_WIN32(GetLastError());
        free(completion);
        return hr;
    }

    // Windows holds a reference through the asynchronous callback, including after timeout.
    completion->params = (ProcessLoopbackParams){PROCESS_LOOPBACK_ACTIVATION, pid, INCLUDE_TARGET_TREE};
    completion->variant.vt = VT_BLOB;
    completion->variant.blob.cbSize = sizeof(completion->params);
    completion->variant.blob.pBlobData = (BYTE *)&completion->params;
    IActivateAudioInterfaceAsyncOperation *operation = NULL;
    HRESULT hr = ActivateAudioInterfaceAsync(L"VAD\\Process_Loopback", &IID_IAudioClient,
                                             &completion->variant, &completion->handler, &operation);
    if (SUCCEEDED(hr)) {
        HANDLE events[] = {process, completion->ready};
        ULONGLONG deadline = GetTickCount64() + 10000;
        DWORD result = WAIT_TIMEOUT;
        while (result == WAIT_TIMEOUT && !stopping() && GetTickCount64() < deadline) {
            result = WaitForMultipleObjects(2, events, FALSE, 100);
        }
        if (result == WAIT_OBJECT_0 + 1) {
            HRESULT activation_result = E_FAIL;
            IUnknown *audio = NULL;
            hr = IActivateAudioInterfaceAsyncOperation_GetActivateResult(operation, &activation_result, &audio);
            if (SUCCEEDED(hr)) hr = activation_result;
            if (SUCCEEDED(hr)) hr = audio ? IUnknown_QueryInterface(audio, &IID_IAudioClient, (void **)client) : E_FAIL;
            if (audio) IUnknown_Release(audio);
        } else if (result == WAIT_FAILED) {
            hr = HRESULT_FROM_WIN32(GetLastError());
        } else {
            hr = result == WAIT_TIMEOUT && !stopping() ? HRESULT_FROM_WIN32(ERROR_TIMEOUT) : E_ABORT;
        }
    }
    if (operation) IActivateAudioInterfaceAsyncOperation_Release(operation);
    release(&completion->handler);
    return hr;
}

typedef struct {
    uint64_t frames;
    uint32_t peak;
    unsigned discontinuities;
    uint64_t next_timestamp;
} Meter;

static HRESULT readPackets(IAudioCaptureClient *client, Meter *meter, ULONGLONG next_report, DWORD stream) {
    while (!stopping() && GetTickCount64() < next_report) {
        UINT32 frames;
        HRESULT hr = IAudioCaptureClient_GetNextPacketSize(client, &frames);
        if (FAILED(hr) || !frames) return hr;
        BYTE *data;
        DWORD flags;
        UINT64 timestamp = 0;
        hr = IAudioCaptureClient_GetBuffer(client, &data, &frames, &flags, NULL, &timestamp);
        if (FAILED(hr)) return hr;
        if (!frames) return S_OK;
        meter->frames += frames;
        if (flags & AUDCLNT_BUFFERFLAGS_DATA_DISCONTINUITY) ++meter->discontinuities;
        if (!(flags & AUDCLNT_BUFFERFLAGS_SILENT) && data) {
            const int16_t *samples = (const int16_t *)data;
            for (size_t index = 0; index < (size_t)frames * 2; ++index) {
                uint32_t magnitude = (uint32_t)abs(samples[index]);
                if (magnitude > meter->peak) meter->peak = magnitude;
            }
        }
        // Copy bounded packets and return the OS buffer before writing to the pipe.
        BYTE packet[MAX_PACKET_FRAMES * 4];
        const BYTE *samples = NULL;
        if (stream && frames <= MAX_PACKET_FRAMES && !(flags & AUDCLNT_BUFFERFLAGS_SILENT) && data) {
            memcpy(packet, data, (size_t)frames * 4);
            samples = packet;
        }
        hr = IAudioCaptureClient_ReleaseBuffer(client, frames);
        if (FAILED(hr)) return hr;
        if (stream) {
            if (frames > MAX_PACKET_FRAMES) return E_UNEXPECTED;
            uint64_t duration = (uint64_t)frames * 10000000 / CAPTURE_RATE;
            uint64_t now = qpcTime();
            if ((flags & AUDCLNT_BUFFERFLAGS_TIMESTAMP_ERROR) || !timestamp || timestamp > now + 200000) {
                uint64_t expected = meter->next_timestamp;
                timestamp = expected && expected <= now && now - expected < 800000 ? expected : now - duration;
            }
            meter->next_timestamp = timestamp + duration;
            hr = emitRecord(stream, timestamp, frames, samples);
            if (FAILED(hr)) return hr;
        }
    }
    return S_OK;
}

typedef struct {
    DWORD pid;
    uint64_t created;
    HANDLE process;
    IAudioClient *client;
    IAudioCaptureClient *capture;
    HANDLE ready;
    BOOL running;
    Meter meter;
} CaptureTarget;

static uint64_t processCreated(HANDLE process) {
    FILETIME created, exited, kernel, user;
    if (!GetProcessTimes(process, &created, &exited, &kernel, &user)) return 0;
    return ((uint64_t)created.dwHighDateTime << 32) | created.dwLowDateTime;
}

static HRESULT measure(CaptureTarget *targets, DWORD count, DWORD seconds, BOOL streaming) {
    HANDLE events[MAX_TARGETS * 2];
    for (DWORD index = 0; index < count; ++index) {
        events[index] = targets[index].process;
        events[count + index] = targets[index].ready;
    }
    ULONGLONG next_report = streaming ? qpcTime() / 10000 : GetTickCount64();
    if (!streaming) next_report += 1000;
    for (DWORD second = 1; (streaming || second <= seconds) && !stopping();) {
        ULONGLONG timestamp = streaming ? qpcTime() : 0;
        ULONGLONG now = streaming ? timestamp / 10000 : GetTickCount64();
        if (now >= next_report) {
            if (streaming) {
                HRESULT hr = emitRecord(0, timestamp, 0, NULL);
                if (FAILED(hr)) return hr;
                // Keep the original phase even when the wait wakes a little late.
                next_report += ((now - next_report) / 20 + 1) * 20;
                continue;
            }
            for (DWORD index = 0; index < count; ++index) {
                CaptureTarget *target = &targets[index];
                Meter *meter = &target->meter;
                wprintf(L"%3luds pid=%lu frames=%llu peak=%.6f discontinuities=%u %ls\n",
                        (unsigned long)second, (unsigned long)target->pid,
                        (unsigned long long)meter->frames, meter->peak / 32768.0, meter->discontinuities,
                        !meter->frames ? L"NO_SAMPLES" : (meter->peak ? L"SIGNAL" : L"SILENT"));
                *meter = (Meter){0};
            }
            fflush(stdout);
            ++second;
            next_report = now + 1000;
            continue;
        }

        ULONGLONG remaining = next_report - now;
        DWORD result = WaitForMultipleObjects(count * 2, events, FALSE, remaining < 100 ? (DWORD)remaining : 100);
        if (result == WAIT_TIMEOUT) continue;
        if (result == WAIT_FAILED) return HRESULT_FROM_WIN32(GetLastError());
        DWORD signaled = result - WAIT_OBJECT_0;
        if (signaled < count) {
            fwprintf(stderr, L"Selected PID %lu exited.\n", (unsigned long)targets[signaled].pid);
            return E_ABORT;
        }
        for (DWORD index = 0; index < count; ++index) {
            CaptureTarget *target = &targets[index];
            // WaitForMultipleObjects already consumed the signaled auto-reset event.
            if (signaled != count + index && WaitForSingleObject(target->ready, 0) != WAIT_OBJECT_0) continue;
            HRESULT hr = readPackets(target->capture, &target->meter, GetTickCount64() + 10, streaming ? index + 1 : 0);
            if (FAILED(hr)) {
                fwprintf(stderr, L"Read audio failed for PID %lu.\n", (unsigned long)target->pid);
                return hr;
            }
        }
    }
    return S_OK;
}

static HRESULT prepareCapture(CaptureTarget *target) {
    HRESULT hr = activate(target->pid, target->process, &target->client);
    if (FAILED(hr)) return hr;

    // Stereo 44.1 kHz / s16 with the system converter, as in the SDK sample.
    WAVEFORMATEX format = {WAVE_FORMAT_PCM, 2, CAPTURE_RATE, CAPTURE_RATE * 4, 4, 16, 0};
    hr = IAudioClient_Initialize(target->client, AUDCLNT_SHAREMODE_SHARED,
                                AUDCLNT_STREAMFLAGS_LOOPBACK | AUDCLNT_STREAMFLAGS_EVENTCALLBACK
                                | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM,
                                0, 0, &format, NULL);
    if (FAILED(hr)) return hr;
    hr = IAudioClient_GetService(target->client, &IID_IAudioCaptureClient, (void **)&target->capture);
    if (FAILED(hr)) return hr;
    target->ready = CreateEventW(NULL, FALSE, FALSE, NULL);
    return target->ready ? IAudioClient_SetEventHandle(target->client, target->ready) : HRESULT_FROM_WIN32(GetLastError());
}

static HRESULT check(CaptureTarget *targets, DWORD count, DWORD seconds, BOOL streaming) {
    for (DWORD index = 0; index < count; ++index) {
        CaptureTarget *target = &targets[index];
        HRESULT hr = prepareCapture(target);
        if (FAILED(hr)) {
            if (stopping()) return S_OK;
            fwprintf(stderr, L"Prepare process loopback failed for PID %lu (HRESULT 0x%08lX).\n",
                     (unsigned long)target->pid, (unsigned long)hr);
            return hr;
        }
    }
    for (DWORD index = 0; index < count; ++index) {
        CaptureTarget *target = &targets[index];
        if (stopping()) return S_OK;
        if (WaitForSingleObject(target->process, 0) != WAIT_TIMEOUT) {
            fwprintf(stderr, L"Selected PID %lu is unavailable.\n", (unsigned long)target->pid);
            return E_ABORT;
        }
        HRESULT hr = IAudioClient_Start(target->client);
        if (FAILED(hr)) {
            fwprintf(stderr, L"Start process loopback failed for PID %lu (HRESULT 0x%08lX).\n",
                     (unsigned long)target->pid, (unsigned long)hr);
            return hr;
        }
        target->running = TRUE;
    }
    if (!streaming) {
        wprintf(L"Checking %lu process tree(s), rate=44100 channels=2 (Ctrl+C to stop)\n", (unsigned long)count);
        fflush(stdout);
    }
    HRESULT hr = measure(targets, count, seconds, streaming);
    if (FAILED(hr)) fwprintf(stderr, L"Capture ended (HRESULT 0x%08lX).\n", (unsigned long)hr);
    return hr;
}

static void closeTarget(CaptureTarget *target) {
    if (target->running) IAudioClient_Stop(target->client);
    if (target->capture) IAudioCaptureClient_Release(target->capture);
    if (target->client) IAudioClient_Release(target->client);
    if (target->ready) CloseHandle(target->ready);
    if (target->process) CloseHandle(target->process);
}

static BOOL openTarget(CaptureTarget *target, BOOL streaming) {
    target->process = OpenProcess(SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION, FALSE, target->pid);
    if (!target->process) {
        DWORD error = GetLastError();
        if (target->created && error == ERROR_INVALID_PARAMETER) {
            fwprintf(stderr, L"APP_CHANGED (PID %lu)\n", (unsigned long)target->pid);
        } else {
            fwprintf(stderr, L"Cannot open PID %lu (Win32 error %lu).\n", (unsigned long)target->pid, error);
        }
        return FALSE;
    }
    wchar_t image[32768];
    DWORD length = (DWORD)(sizeof(image) / sizeof(*image));
    if (!QueryFullProcessImageNameW(target->process, 0, image, &length)
            || WaitForSingleObject(target->process, 0) != WAIT_TIMEOUT) {
        fwprintf(stderr, L"Selected PID %lu is unavailable.\n", (unsigned long)target->pid);
        return FALSE;
    }
    if (target->created && processCreated(target->process) != target->created) {
        fwprintf(stderr, L"APP_CHANGED (PID %lu)\n", (unsigned long)target->pid);
        return FALSE;
    }
    const wchar_t *basename = wcsrchr(image, L'\\');
    if (!streaming) {
        wprintf(L"Target: %ls (pid=%lu)\n", basename ? basename + 1 : image, (unsigned long)target->pid);
        fflush(stdout);
    }
    return TRUE;
}

static DWORD parentPid(HANDLE snapshot, DWORD pid) {
    PROCESSENTRY32W entry = {.dwSize = sizeof(entry)};
    for (BOOL found = Process32FirstW(snapshot, &entry); found; found = Process32NextW(snapshot, &entry)) {
        if (entry.th32ProcessID == pid) return entry.th32ParentProcessID;
    }
    return 0;
}

static BOOL containsProcess(HANDLE snapshot, DWORD root, DWORD pid) {
    for (unsigned depth = 0; pid && depth < 128; ++depth) {
        if (pid == root) return TRUE;
        pid = parentPid(snapshot, pid);
    }
    return pid != 0;
}

static BOOL processIdentity(DWORD pid, wchar_t *path, DWORD capacity, uint64_t *created) {
    HANDLE process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | SYNCHRONIZE, FALSE, pid);
    if (!process) return FALSE;
    BOOL found = QueryFullProcessImageNameW(process, 0, path, &capacity)
              && (*created = processCreated(process)) != 0
              && WaitForSingleObject(process, 0) == WAIT_TIMEOUT;
    CloseHandle(process);
    return found;
}

static const wchar_t *imageName(const wchar_t *path) {
    const wchar_t *slash = wcsrchr(path, L'\\');
    return slash ? slash + 1 : path;
}

static BOOL excludedImage(const wchar_t *name) {
    static const wchar_t *excluded[] = {
        L"ApplicationFrameHost.exe", L"explorer.exe", L"ShellExperienceHost.exe",
        L"StartMenuExperienceHost.exe", L"SearchHost.exe", L"TextInputHost.exe",
        L"WindowsTerminal.exe", L"cmd.exe", L"powershell.exe", L"pwsh.exe", L"conhost.exe",
        L"java.exe", L"javaw.exe", L"Minecraft.Windows.exe", L"MinecraftLauncher.exe",
        L"obs64.exe", L"obs32.exe", L"svc-wasapi-check.exe", L"audiodg.exe", L"svchost.exe"
    };
    for (size_t index = 0; index < sizeof(excluded) / sizeof(excluded[0]); ++index) {
        if (!_wcsicmp(name, excluded[index])) return TRUE;
    }
    return FALSE;
}

static void displayName(const wchar_t *path, wchar_t *output, size_t capacity) {
    const wchar_t *image = imageName(path);
    if (!_wcsicmp(image, L"cloudmusic.exe") || !_wcsicmp(image, L"NeteaseMusic.exe")) {
        wcsncpy(output, L"\u7f51\u6613\u4e91\u97f3\u4e50", capacity - 1);
        return;
    }
    wcsncpy(output, image, capacity - 1);
    DWORD unused = 0;
    DWORD size = GetFileVersionInfoSizeW(path, &unused);
    if (!size || size > 1024 * 1024) return;
    BYTE *version = malloc(size);
    if (!version) return;
    WORD *translation;
    UINT bytes;
    if (GetFileVersionInfoW(path, 0, size, version)
            && VerQueryValueW(version, L"\\VarFileInfo\\Translation", (void **)&translation, &bytes)
            && bytes >= 2 * sizeof(WORD)) {
        wchar_t key[64];
        swprintf(key, sizeof(key) / sizeof(*key), L"\\StringFileInfo\\%04x%04x\\FileDescription",
                 (unsigned)translation[0], (unsigned)translation[1]);
        wchar_t *description;
        UINT length;
        if (VerQueryValueW(version, key, (void **)&description, &length) && length > 1) {
            size_t count = length - 1 < capacity - 1 ? length - 1 : capacity - 1;
            wmemcpy(output, description, count);
            output[count] = 0;
        }
    }
    free(version);
}

typedef struct {
    DWORD host;
    DWORD app;
    BOOL ambiguous;
} HostedWindow;

static BOOL CALLBACK hostedApp(HWND window, LPARAM context) {
    HostedWindow *hosted = (HostedWindow *)context;
    wchar_t name[64];
    if (!GetClassNameW(window, name, 64) || wcscmp(name, L"Windows.UI.Core.CoreWindow")) return TRUE;
    DWORD pid = 0;
    GetWindowThreadProcessId(window, &pid);
    if (!pid || pid == hosted->host) return TRUE;
    if (hosted->app && hosted->app != pid) hosted->ambiguous = TRUE;
    hosted->app = pid;
    return TRUE;
}

enum { MAX_APPS = 128, PATH_CAPACITY = 32768 };
typedef struct {
    HANDLE snapshot;
    DWORD excluded_pid;
    DWORD seen[MAX_APPS];
    size_t count;
} AppList;

static void emitApp(AppList *list, DWORD pid, uint64_t created, const wchar_t *path, char kind) {
    if (list->count == MAX_APPS || excludedImage(imageName(path))) return;
    if (list->excluded_pid && containsProcess(list->snapshot, pid, list->excluded_pid)) return;
    for (size_t index = 0; index < list->count; ++index) {
        if (list->seen[index] == pid) return;
    }
    wchar_t label[256] = {0};
    displayName(path, label, 256);
    for (wchar_t *ch = label; *ch; ++ch) {
        if (*ch < 0x20 || *ch == 0x7f) *ch = L' ';
    }
    char utf8[1024];
    if (!WideCharToMultiByte(CP_UTF8, 0, label, -1, utf8, sizeof(utf8), NULL, NULL)) return;
    printf("%lu\t%llu\t%c\t%s\n", (unsigned long)pid, (unsigned long long)created, kind, utf8);
    list->seen[list->count++] = pid;
}

static void listEndpointApps(IMMDevice *device, AppList *list) {
    IAudioSessionManager2 *manager = NULL;
    if (FAILED(IMMDevice_Activate(device, &IID_IAudioSessionManager2, CLSCTX_ALL, NULL, (void **)&manager))) return;
    IAudioSessionEnumerator *sessions = NULL;
    HRESULT result = IAudioSessionManager2_GetSessionEnumerator(manager, &sessions);
    IAudioSessionManager2_Release(manager);
    if (FAILED(result)) return;
    int count = 0;
    if (SUCCEEDED(IAudioSessionEnumerator_GetCount(sessions, &count))) {
        for (int index = 0; index < count && list->count < MAX_APPS; ++index) {
            IAudioSessionControl *control = NULL;
            if (FAILED(IAudioSessionEnumerator_GetSession(sessions, index, &control))) continue;
            AudioSessionState state;
            IAudioSessionControl2 *control2 = NULL;
            if (SUCCEEDED(IAudioSessionControl_GetState(control, &state)) && state == AudioSessionStateActive
                    && SUCCEEDED(IAudioSessionControl_QueryInterface(control, &IID_IAudioSessionControl2, (void **)&control2))) {
                DWORD pid = 0;
                uint64_t created;
                wchar_t path[PATH_CAPACITY];
                // S_OK identifies a single process. Shared sessions return the
                // creator PID, which may not be the process producing audio.
                if (IAudioSessionControl2_GetProcessId(control2, &pid) == S_OK
                        && pid > 4 && processIdentity(pid, path, PATH_CAPACITY, &created)) {
                    emitApp(list, pid, created, path, 'A');
                }
                IAudioSessionControl2_Release(control2);
            }
            IAudioSessionControl_Release(control);
        }
    }
    IAudioSessionEnumerator_Release(sessions);
}

static void listAudioApps(AppList *list) {
    if (FAILED(CoInitializeEx(NULL, COINIT_MULTITHREADED))) return;
    IMMDeviceEnumerator *enumerator = NULL;
    if (SUCCEEDED(CoCreateInstance(&CLSID_MMDeviceEnumerator, NULL, CLSCTX_ALL,
                                   &IID_IMMDeviceEnumerator, (void **)&enumerator))) {
        IMMDeviceCollection *devices = NULL;
        if (SUCCEEDED(IMMDeviceEnumerator_EnumAudioEndpoints(enumerator, eRender, DEVICE_STATE_ACTIVE, &devices))) {
            UINT count = 0;
            if (SUCCEEDED(IMMDeviceCollection_GetCount(devices, &count))) {
                for (UINT index = 0; index < count && list->count < MAX_APPS; ++index) {
                    IMMDevice *device = NULL;
                    if (SUCCEEDED(IMMDeviceCollection_Item(devices, index, &device))) {
                        listEndpointApps(device, list);
                        IMMDevice_Release(device);
                    }
                }
            }
            IMMDeviceCollection_Release(devices);
        }
        IMMDeviceEnumerator_Release(enumerator);
    }
    CoUninitialize();
}

static BOOL CALLBACK listWindow(HWND window, LPARAM context) {
    AppList *list = (AppList *)context;
    if (list->count == MAX_APPS || !IsWindowVisible(window) || window == GetShellWindow()
            || (GetWindowLongPtrW(window, GWL_EXSTYLE) & WS_EX_TOOLWINDOW)
            || !GetWindowTextLengthW(window)) return TRUE;

    DWORD pid = 0;
    GetWindowThreadProcessId(window, &pid);
    wchar_t path[PATH_CAPACITY], parent_path[PATH_CAPACITY];
    uint64_t created;
    if (!pid || !processIdentity(pid, path, PATH_CAPACITY, &created)) return TRUE;
    if (!_wcsicmp(imageName(path), L"ApplicationFrameHost.exe")) {
        HostedWindow hosted = {pid, 0, FALSE};
        EnumChildWindows(window, hostedApp, (LPARAM)&hosted);
        pid = hosted.app;
        if (!pid || hosted.ambiguous || !processIdentity(pid, path, PATH_CAPACITY, &created)) return TRUE;
    }
    if (excludedImage(imageName(path))) return TRUE;
    // Follow same-image parents only; a shell/launcher must never become the target.
    for (unsigned depth = 0; depth < 32; ++depth) {
        DWORD parent = parentPid(list->snapshot, pid);
        uint64_t parent_created;
        if (!parent || parent == pid || !processIdentity(parent, parent_path, PATH_CAPACITY, &parent_created)
                || parent_created > created || _wcsicmp(path, parent_path)) break;
        created = parent_created;
        pid = parent;
    }
    // Keep distinct window and audio-session PIDs available as alternatives.
    emitApp(list, pid, created, path, 'W');
    return TRUE;
}

static int listApps(DWORD excluded_pid) {
    AppList list = {.excluded_pid = excluded_pid};
    list.snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (list.snapshot == INVALID_HANDLE_VALUE) return 1;
    listAudioApps(&list);
    BOOL ok = EnumWindows(listWindow, (LPARAM)&list);
    CloseHandle(list.snapshot);
    return ok && !ferror(stdout) ? 0 : 1;
}

static BOOL distinctTrees(const CaptureTarget *targets, DWORD count, DWORD excluded_pid) {
    if (count == 1 && !excluded_pid) return TRUE;
    HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snapshot == INVALID_HANDLE_VALUE) {
        fwprintf(stderr, L"Cannot check whether the selected process trees overlap.\n");
        return FALSE;
    }
    BOOL valid = TRUE;
    for (DWORD index = 0; index < count && valid; ++index) {
        if (excluded_pid && containsProcess(snapshot, targets[index].pid, excluded_pid)) {
            fwprintf(stderr, L"The selected process tree includes Minecraft. Select the player again.\n");
            valid = FALSE;
            break;
        }
        DWORD ancestor = targets[index].pid;
        for (unsigned depth = 0; ancestor && valid; ++depth) {
            if (depth == 128) {
                fwprintf(stderr, L"Cannot resolve the process ancestry.\n");
                valid = FALSE;
                break;
            }
            ancestor = parentPid(snapshot, ancestor);
            for (DWORD other = 0; other < count && ancestor; ++other) {
                if (ancestor == targets[other].pid) {
                    fwprintf(stderr, L"PID %lu is already included in the process tree for PID %lu.\n",
                             (unsigned long)targets[index].pid, (unsigned long)ancestor);
                    valid = FALSE;
                    break;
                }
            }
        }
    }
    CloseHandle(snapshot);
    return valid;
}

static DWORD number(const wchar_t *text, DWORD maximum) {
    if (*text < L'0' || *text > L'9') return 0;
    wchar_t *end;
    errno = 0;
    unsigned long value = wcstoul(text, &end, 10);
    return !errno && !*end && value > 0 && value <= maximum ? (DWORD)value : 0;
}

static DWORD parseTargets(const wchar_t *text, CaptureTarget *targets) {
    DWORD count = 0;
    while (count < MAX_TARGETS && *text >= L'0' && *text <= L'9') {
        wchar_t *end;
        errno = 0;
        unsigned long pid = wcstoul(text, &end, 10);
        if (errno || pid <= 4 || pid == GetCurrentProcessId()) return 0;
        uint64_t created = 0;
        if (*end == L':') {
            const wchar_t *value = end + 1;
            if (*value < L'0' || *value > L'9') return 0;
            errno = 0;
            created = wcstoull(value, &end, 10);
            if (errno || !created) return 0;
        }
        if (*end && *end != L',') return 0;
        for (DWORD index = 0; index < count; ++index) {
            if (targets[index].pid == pid) return 0;
        }
        targets[count].pid = (DWORD)pid;
        targets[count++].created = created;
        if (!*end) return count;
        text = end + 1;
    }
    return 0;
}

// Prepare the same protected, current-user-only DACL as the standalone sender.
// Work through a handle so a symlink or junction cannot redirect the operation.
static int profileAcl(const wchar_t *path, BOOL directory) {
    int result = 1;
    HANDLE token = NULL;
    PTOKEN_USER user = NULL;
    PACL acl = NULL;
    PSECURITY_DESCRIPTOR descriptor = NULL;
    HANDLE file = CreateFileW(path, READ_CONTROL | WRITE_DAC | FILE_READ_ATTRIBUTES,
                              FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, OPEN_EXISTING,
                              FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS, NULL);
    if (file == INVALID_HANDLE_VALUE) return result;

    FILE_ATTRIBUTE_TAG_INFO attributes;
    if (GetFileType(file) != FILE_TYPE_DISK
            || !GetFileInformationByHandleEx(file, FileAttributeTagInfo, &attributes, sizeof(attributes))
            || (attributes.FileAttributes & FILE_ATTRIBUTE_REPARSE_POINT)
            || ((attributes.FileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0) != directory) goto cleanup;

    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token)) goto cleanup;
    DWORD bytes = 0;
    GetTokenInformation(token, TokenUser, NULL, 0, &bytes);
    if (!bytes || bytes > 64 * 1024) goto cleanup;
    user = malloc(bytes);
    if (!user || !GetTokenInformation(token, TokenUser, user, bytes, &bytes)) goto cleanup;

    PSID owner = NULL;
    if (GetSecurityInfo(file, SE_FILE_OBJECT, OWNER_SECURITY_INFORMATION, &owner, NULL, NULL, NULL,
                        &descriptor) != ERROR_SUCCESS
            || !owner || !EqualSid(owner, user->User.Sid)) goto cleanup;

    DWORD acl_bytes = sizeof(ACL) + sizeof(ACCESS_ALLOWED_ACE) - sizeof(DWORD) + GetLengthSid(user->User.Sid);
    acl = malloc(acl_bytes);
    DWORD inherit = directory ? OBJECT_INHERIT_ACE | CONTAINER_INHERIT_ACE : 0;
    if (!acl || !InitializeAcl(acl, acl_bytes, ACL_REVISION)
            || !AddAccessAllowedAceEx(acl, ACL_REVISION, inherit, FILE_ALL_ACCESS, user->User.Sid)) goto cleanup;
    if (SetSecurityInfo(file, SE_FILE_OBJECT, DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION,
                        NULL, NULL, acl, NULL) == ERROR_SUCCESS) result = 0;

cleanup:
    free(acl);
    if (descriptor) LocalFree(descriptor);
    free(user);
    if (token) CloseHandle(token);
    CloseHandle(file);
    return result;
}

int wmain(int argc, wchar_t **argv) {
    if (argc == 4 && !wcscmp(argv[1], L"profile-acl")) {
        if (!wcscmp(argv[2], L"dir")) return profileAcl(argv[3], TRUE);
        if (!wcscmp(argv[2], L"file")) return profileAcl(argv[3], FALSE);
        return 2;
    }
    if ((argc == 2 || argc == 3) && !wcscmp(argv[1], L"apps")) {
        DWORD excluded_pid = argc == 3 ? number(argv[2], MAXDWORD) : 0;
        return argc == 3 && !excluded_pid ? 2 : listApps(excluded_pid);
    }
    CaptureTarget targets[MAX_TARGETS] = {0};
    BOOL streaming = (argc == 3 || argc == 4) && !wcscmp(argv[1], L"stream");
    BOOL checking = argc == 4 && !wcscmp(argv[1], L"check");
    DWORD count = streaming || checking ? parseTargets(argv[2], targets) : 0;
    DWORD seconds = checking ? number(argv[3], 120) : 0;
    DWORD excluded_pid = streaming && argc == 4 ? number(argv[3], MAXDWORD) : 0;
    if (!count || (checking && !seconds) || (streaming && argc == 4 && !excluded_pid)) {
        fwprintf(stderr, L"Usage: svc-wasapi-check.exe apps | check PID[,PID...] SECONDS (1-120, up to 4 distinct PIDs)\n");
        return argc == 2 && !wcscmp(argv[1], L"--help") ? 0 : 2;
    }
    if (streaming) {
        LARGE_INTEGER frequency;
        control_pipe = GetStdHandle(STD_INPUT_HANDLE);
        if (GetFileType(control_pipe) != FILE_TYPE_PIPE || GetFileType(GetStdHandle(STD_OUTPUT_HANDLE)) != FILE_TYPE_PIPE
                || !QueryPerformanceFrequency(&frequency) || _setmode(_fileno(stdout), _O_BINARY) < 0) return 2;
        qpc_frequency = (uint64_t)frequency.QuadPart;
    }
    HRESULT hr = CoInitializeEx(NULL, COINIT_MULTITHREADED);
    if (FAILED(hr)) {
        fwprintf(stderr, L"Could not initialize COM (HRESULT 0x%08lX).\n", (unsigned long)hr);
        return 1;
    }

    int exit_code = 1;
    for (DWORD index = 0; index < count; ++index) {
        if (!openTarget(&targets[index], streaming)) goto cleanup;
    }
    if (!distinctTrees(targets, count, excluded_pid)) goto cleanup;
    if (!streaming && !SetConsoleCtrlHandler(stop, TRUE)) {
        fwprintf(stderr, L"Could not register Ctrl+C (Win32 error %lu).\n", GetLastError());
        goto cleanup;
    }
    exit_code = FAILED(check(targets, count, seconds, streaming)) ? 1 : 0;
    if (!streaming) SetConsoleCtrlHandler(stop, FALSE);

cleanup:
    for (DWORD index = 0; index < count; ++index) closeTarget(&targets[index]);
    CoUninitialize();
    return exit_code;
}
