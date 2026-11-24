package page.mjoe.svcbroadcast.sender;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/** Launches the platform capture helper without touching the game or system audio settings. */
final class NativeCapture {
    private static final int MAX_APPS = 128;
    private static final int MAX_LIST_BYTES = 256 * 1024;
    private static final int MAX_READY_FRAMES = 4;
    private static final long READ_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(900);
    private static final long CLOCK_CHECK_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final long WINDOWS_MAX_PID = 0xffff_ffffL;
    private static final Pattern MAC_SELECTOR = Pattern.compile("([0-9]{1,10}):([0-9]{1,20}):([0-9]{1,6})");
    private static final Pattern WINDOWS_SELECTOR = Pattern.compile("([0-9]{1,10}):([0-9]{1,20})");
    private static final Pattern UNSAFE_LABEL = Pattern.compile("[\\p{C}\\p{Zl}\\p{Zp}§]");

    record AppSelector(String id, String label) {}

    private record AppEntry(AppSelector app, boolean active) {}

    private NativeCapture() {}

    static List<AppSelector> list(EmbeddedSender.Platform platform, Path helper, long gamePid)
            throws IOException, InterruptedException {
        var args = new ArrayList<>(List.of(helper.toString()));
        boolean windows = platform == EmbeddedSender.Platform.WINDOWS_X64;
        if (windows) args.addAll(List.of("apps", Long.toString(gamePid)));
        else args.add("list-identities");
        String fault = windows ? "wasapi_apps" : "coreaudio_apps";
        Process child = null;
        try {
            child = new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            child.getOutputStream().close();
            InputStream stdout = child.getInputStream();
            var reader = new FutureTask<>(() -> {
                try (stdout) {
                    return stdout.readNBytes(MAX_LIST_BYTES + 1);
                }
            });
            Thread.ofPlatform().daemon().name("svc-app-enumerator").start(reader);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(windows ? 8 : 5);
            byte[] bytes = reader.get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (bytes.length > MAX_LIST_BYTES || !child.waitFor(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)
                    || child.exitValue() != 0) {
                throw new IOException("Audio helper did not return a bounded list");
            }
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            return parseApps(windows, text, gamePid);
        } catch (ExecutionException | TimeoutException | IOException | IllegalArgumentException e) {
            throw new JavaUpload.Failure(fault);
        } finally {
            if (child != null && child.isAlive()) child.destroyForcibly();
        }
    }

    static List<AppSelector> parseApps(boolean windows, String output, long gamePid) {
        var apps = new LinkedHashMap<String, AppEntry>();
        for (String line : output.split("\n")) {
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            if (line.isEmpty()) continue;
            String id;
            String label;
            boolean active = false;
            if (windows) {
                String[] parts = line.split("\t", 4);
                if (parts.length != 4 || (!parts[2].equals("A") && !parts[2].equals("W"))) {
                    throw new IllegalArgumentException("Invalid WASAPI app listing");
                }
                id = parts[0] + ":" + parts[1];
                active = parts[2].equals("A");
                label = parts[3];
            } else {
                String[] parts = line.split("\t", 3);
                if (parts.length != 3) throw new IllegalArgumentException("Invalid Core Audio app listing");
                id = parts[0];
                label = parts[2] + (parts[1].isBlank() ? "" : " · " + parts[1]);
            }
            long pid = selectorPid(windows, id);
            if (pid == gamePid) continue;
            label = UNSAFE_LABEL.matcher(label).replaceAll("").strip();
            if (label.isBlank()) label = "PID " + pid;
            if (windows) label += active ? " · 活动音频" : " · 窗口";
            int end = label.offsetByCodePoints(0, Math.min(100, label.codePointCount(0, label.length())));
            var entry = new AppEntry(new AppSelector(id, label.substring(0, end)), active);
            apps.merge(id, entry, (previous, next) -> previous.active() && !next.active() ? previous : next);
        }
        return apps.values().stream()
                .sorted(Comparator.comparing((AppEntry entry) -> !entry.active())
                        .thenComparing(entry -> entry.app().label().toLowerCase(Locale.ROOT))
                        .thenComparing(entry -> entry.app().id()))
                .map(AppEntry::app)
                .limit(MAX_APPS)
                .toList();
    }

    static JavaUpload.Source open(EmbeddedSender.Platform platform, Path helper, List<String> selectors, long gamePid)
            throws IOException {
        boolean windows = platform == EmbeddedSender.Platform.WINDOWS_X64;
        if (selectors.size() < 1 || selectors.size() > 4) throw new JavaUpload.Failure("app_selection");
        try {
            var pids = new HashSet<Long>();
            for (String selector : selectors) {
                long pid = selectorPid(windows, selector);
                if (pid == gamePid || !pids.add(pid)) throw new IllegalArgumentException("Unsafe app selection");
            }
        } catch (IllegalArgumentException e) {
            throw new JavaUpload.Failure(windows ? "wasapi_selection" : "coreaudio_selection");
        }
        String command = windows ? "stream" : "stream-checked-controlled";
        var args = new ArrayList<>(List.of(helper.toString(), command, String.join(",", selectors)));
        if (windows) args.add(Long.toString(gamePid));
        try {
            Process child = new ProcessBuilder(args).start();
            return new PipeSource(child, windows, selectors.size());
        } catch (IOException e) {
            throw new JavaUpload.Failure(windows ? "wasapi_start" : "coreaudio_start");
        }
    }

    private static long selectorPid(boolean windows, String selector) {
        var match = (windows ? WINDOWS_SELECTOR : MAC_SELECTOR).matcher(selector);
        if (!match.matches()) throw new IllegalArgumentException("Invalid capture selector");
        long pid = Long.parseLong(match.group(1));
        long maxPid = windows ? WINDOWS_MAX_PID : Integer.MAX_VALUE;
        if (pid <= (windows ? 4 : 1) || pid > maxPid || Long.parseUnsignedLong(match.group(2)) == 0) {
            throw new IllegalArgumentException("Invalid capture identity");
        }
        return pid;
    }

    private static final class PipeSource implements JavaUpload.Source, CapturePipes.Sink {
        private final Process child;
        private final boolean windows;
        private final long startedAt = System.nanoTime();
        private final long startupTimeout;
        private final ArrayDeque<PcmFrameConverter.Frame> frames = new ArrayDeque<>(MAX_READY_FRAMES);
        private final Thread statusReader;
        private volatile boolean appChanged;
        private volatile boolean ready;
        private boolean closed;
        private boolean hasPulse;
        private long lastPulse;
        private long lastProgress;
        private long lastFrame;
        private long lastIndex = -1;
        private String errorCode;

        PipeSource(Process child, boolean windows, int tracks) {
            this.child = child;
            this.windows = windows;
            startupTimeout = TimeUnit.SECONDS.toNanos(windows ? 45 : 15);
            // Process pipes use blocking reads under monitors in JDK 21.
            statusReader = Thread.ofPlatform().daemon().name("svc-capture-status").start(this::readStatus);
            Thread.ofPlatform().daemon().name("svc-native-capture").start(() -> readPcm(tracks));
        }

        @Override
        public synchronized byte[] readFrame(long index) throws IOException, InterruptedException {
            if (index <= lastIndex || (lastIndex == -1 && index != 0)) {
                throw new JavaUpload.Failure("capture_sequence");
            }
            lastIndex = index;
            while (true) {
                if (closed) throw new JavaUpload.Failure("capture_closed");
                if (errorCode != null) throw new JavaUpload.Failure(errorCode);
                long now = System.nanoTime();
                if (!hasPulse) {
                    if (now - startedAt >= startupTimeout) {
                        throw new JavaUpload.Failure(windows ? "wasapi_start_timeout" : "coreaudio_start_timeout");
                    }
                    return new byte[1920];
                }
                if (now - lastPulse >= READ_TIMEOUT_NANOS) {
                    throw new JavaUpload.Failure(windows ? "wasapi_timeout" : "coreaudio_timeout");
                }
                if (now - lastProgress >= READ_TIMEOUT_NANOS) {
                    throw new JavaUpload.Failure(windows ? "wasapi_clock" : "coreaudio_timeout");
                }
                discardExpired(now);
                if (!frames.isEmpty()) {
                    ready = true;
                    return frames.removeFirst().pcm();
                }
                if (now - lastFrame >= READ_TIMEOUT_NANOS) {
                    throw new JavaUpload.Failure(windows ? "wasapi_frame_timeout" : "coreaudio_timeout");
                }
                if (!ready) return new byte[1920];
                TimeUnit.NANOSECONDS.timedWait(this, CLOCK_CHECK_NANOS);
            }
        }

        @Override
        public boolean ready() {
            return ready;
        }

        @Override
        public synchronized void pulse(long observedAt, boolean advanced) {
            if (closed) return;
            if (!hasPulse) lastFrame = lastProgress = observedAt;
            hasPulse = true;
            lastPulse = observedAt;
            if (advanced) lastProgress = observedAt;
            notifyAll();
        }

        @Override
        public synchronized void frame(PcmFrameConverter.Frame frame) {
            if (closed) return;
            long now = System.nanoTime();
            if (now - frame.capturedAt() >= CapturePipes.MAX_AGE_NANOS) return;
            discardExpired(now);
            if (frames.size() == MAX_READY_FRAMES) frames.removeFirst();
            frames.addLast(frame);
            lastFrame = now;
            notifyAll();
        }

        private void discardExpired(long now) {
            while (!frames.isEmpty() && now - frames.peekFirst().capturedAt() >= CapturePipes.MAX_AGE_NANOS) {
                frames.removeFirst();
            }
        }

        private void readPcm(int tracks) {
            String error = windows ? "wasapi_stopped" : "coreaudio_stopped";
            try (var stdout = child.getInputStream()) {
                if (windows) CapturePipes.readWindows(stdout, tracks, System::nanoTime, this);
                else CapturePipes.readMac(stdout, tracks, System::nanoTime, this);
            } catch (IOException | RuntimeException e) {
                error = windows ? "wasapi_pipe" : "coreaudio_pipe";
            }
            try {
                statusReader.join(Duration.ofMillis(100));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            synchronized (this) {
                if (closed) return;
                errorCode = appChanged ? "wasapi_app_changed" : error;
                frames.clear();
                notifyAll();
            }
        }

        private void readStatus() {
            // Never forward untrusted stderr text into the client chat or logs.
            byte[] prefix = "APP_CHANGED ".getBytes(StandardCharsets.US_ASCII);
            try (var stderr = child.getErrorStream()) {
                int matched = 0;
                boolean mismatch = false;
                for (int next; (next = stderr.read()) >= 0; ) {
                    if (next == '\n') {
                        matched = 0;
                        mismatch = false;
                    } else if (!mismatch) {
                        if (next == prefix[matched]) {
                            if (++matched == prefix.length) {
                                appChanged = windows;
                                mismatch = true;
                            }
                        } else {
                            mismatch = true;
                        }
                    }
                }
            } catch (IOException ignored) {
                // Process exit is reported by the PCM reader.
            }
        }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) return;
                closed = true;
                frames.clear();
                notifyAll();
            }
            try {
                child.getOutputStream().close();
            } catch (IOException ignored) {
                // The helper may already have exited.
            }
            try {
                if (!child.waitFor(1, TimeUnit.SECONDS)) {
                    child.destroyForcibly();
                    child.waitFor(1, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                child.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }
}
