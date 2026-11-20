package page.mjoe.svcbroadcast.sender;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Owns the local child and its control pipe. No call from the game thread waits for a child. */
final class SenderProcess {
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "svc-sender-deadlines");
        thread.setDaemon(true);
        return thread;
    });

    private final Path config;
    private final List<String> arguments;
    private final String input;
    private final Consumer<JsonObject> onEvent;
    private final Consumer<String> onExit;
    private final Runnable onInstall;
    private volatile Process process;
    private volatile boolean stopping;
    private volatile boolean startedCapture;
    private volatile String deadlineError;

    SenderProcess(Path config, List<String> arguments, Consumer<JsonObject> onEvent, Consumer<String> onExit) {
        this(config, arguments, null, onEvent, onExit);
    }

    SenderProcess(Path config, List<String> arguments, String input, Consumer<JsonObject> onEvent, Consumer<String> onExit) {
        this(config, arguments, input, onEvent, onExit, () -> {});
    }

    SenderProcess(Path config, List<String> arguments, String input, Consumer<JsonObject> onEvent,
                  Consumer<String> onExit, Runnable onInstall) {
        this.config = config;
        this.arguments = List.copyOf(arguments);
        this.input = input;
        this.onEvent = onEvent;
        this.onExit = onExit;
        this.onInstall = onInstall;
    }

    void launch(boolean broadcast) {
        Thread.ofVirtual().name("svc-minecraft-sender").start(() -> {
            String error = null;
            try {
                error = run(broadcast);
            } catch (IOException | RuntimeException exception) {
                error = process == null ? "launcher_failed" : "bridge_protocol";
            } finally {
                if (process != null && process.isAlive()) {
                    closeControl();
                    try {
                        if (!process.waitFor(6, TimeUnit.SECONDS)) forceStop();
                    } catch (InterruptedException exception) {
                        forceStop();
                        Thread.currentThread().interrupt();
                    }
                }
                onExit.accept(deadlineError != null ? deadlineError : error);
            }
        });
    }

    private String run(boolean broadcast) throws IOException {
        if (stopping) return null;
        List<String> launcher;
        if (!Files.notExists(config)) {
            try {
                launcher = command(config);
            } catch (IOException | RuntimeException exception) {
                return "launcher_config";
            }
        } else {
            var platform = EmbeddedSender.detect(System.getProperty("os.name"), System.getProperty("os.arch"));
            if (platform == null) return "launcher_unavailable";
            try {
                launcher = EmbeddedSender.command(platform, platform.cache(), onInstall);
            } catch (IOException | RuntimeException exception) {
                return "runtime_install_failed";
            }
            if (launcher.isEmpty()) return "launcher_unavailable";
        }
        if (stopping) return null;
        var timeout = TIMER.schedule(() -> {
            if (!stopping && !startedCapture) {
                deadlineError = broadcast ? "startup_timeout" : "list_timeout";
                stop();
            }
        }, broadcast ? 35 : 12, TimeUnit.SECONDS);
        try {
            return runChild(launcher);
        } finally {
            timeout.cancel(false);
        }
    }

    private String runChild(List<String> launcher) throws IOException {
        var command = new ArrayList<>(launcher);
        command.add("mc-bridge");
        command.addAll(arguments);
        command.add("--game-pid");
        command.add(Long.toString(ProcessHandle.current().pid()));
        if (stopping) return null;
        process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if (stopping) {
            closeControl();
        } else if (input != null) {
            try (var output = process.getOutputStream()) {
                output.write(input.getBytes(StandardCharsets.US_ASCII));
            }
        }
        try (var input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            for (int count = 0; ; count++) {
                String line = line(input);
                if (line == null) break;
                if (count >= 8) return "bridge_protocol";
                JsonObject event = JsonParser.parseString(line).getAsJsonObject();
                if ("capturing".equals(string(event.get("event")))) startedCapture = true;
                onEvent.accept(event);
            }
        }
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) return "bridge_protocol";
            return process.exitValue() == 0 || stopping ? null : "launcher_failed";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "bridge_protocol";
        }
    }

    synchronized void stop() {
        if (stopping) return;
        stopping = true;
        TIMER.execute(this::closeControl);
        TIMER.schedule(this::forceStop, 6, TimeUnit.SECONDS);
    }

    private void closeControl() {
        Process child = process;
        if (child == null) return;
        try {
            child.getOutputStream().close();
        } catch (IOException ignored) {
            // The child may already have closed its end.
        }
    }

    private void forceStop() {
        Process child = process;
        if (child == null || !child.isAlive()) return;
        List<ProcessHandle> descendants = child.descendants().toList();
        child.destroyForcibly();
        descendants.forEach(ProcessHandle::destroyForcibly);
    }

    static List<String> command(Path config) throws IOException {
        try (var input = Files.newInputStream(config)) {
            byte[] bytes = input.readNBytes(8193);
            if (bytes.length > 8192) throw new IOException("Launcher config is too large.");
            JsonArray entries = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("command");
            if (entries == null || entries.isEmpty() || entries.size() > 12) {
                throw new IOException("Expected a launcher command array.");
            }
            List<String> command = new ArrayList<>();
            for (JsonElement entry : entries) {
                String argument = string(entry);
                if (argument.isBlank() || argument.codePoints().anyMatch(Character::isISOControl)) {
                    throw new IOException("Launcher arguments must be nonempty strings.");
                }
                command.add(argument);
            }
            if (!Path.of(command.getFirst()).isAbsolute()) throw new IOException("Use an absolute executable path.");
            return command;
        }
    }

    private static String line(BufferedReader input) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int character; (character = input.read()) != -1; ) {
            if (character == '\n') return line.toString();
            if (line.length() == 65536) throw new IOException("Sender message too long.");
            line.append((char) character);
        }
        return line.isEmpty() ? null : line.toString();
    }

    static String string(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Expected a string.");
        }
        return value.getAsString();
    }
}
