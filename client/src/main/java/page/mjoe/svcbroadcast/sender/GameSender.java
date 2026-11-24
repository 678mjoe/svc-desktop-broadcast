package page.mjoe.svcbroadcast.sender;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Dispatches game commands to the in-process sender or an explicitly configured external sender. */
final class GameSender {
    private static final long GAME_PID = ProcessHandle.current().pid();
    private final Path config;
    private final Runnable onInstall;

    GameSender(Path config, Runnable onInstall) {
        this.config = config;
        this.onInstall = onInstall;
    }

    Task query(String action, Consumer<JsonObject> events, Consumer<String> exit) {
        return new Task(List.of(action), null, task -> {
            JsonArray items = new JsonArray();
            if (action.equals("apps")) {
                var platform = platform();
                for (var app : NativeCapture.list(platform, helper(platform), GAME_PID).stream().limit(128).toList()) {
                    var item = new JsonObject();
                    item.addProperty("id", app.id());
                    item.addProperty("label", app.label());
                    items.add(item);
                }
            } else {
                profiles().list().stream().limit(128).forEach(items::add);
            }
            JsonObject event = event(action);
            event.add("items", items);
            task.emit(event);
        }, events, exit);
    }

    Task pair(String name, UUID player, String identity, String offer,
              Consumer<JsonObject> events, Consumer<String> exit) {
        String input = identity + "\n" + (offer == null ? "" : offer + "\n");
        var arguments = List.of(offer == null ? "inspect" : "pair", "--profile=" + name, "--player=" + player);
        return new Task(arguments, input, task -> {
            var expected = JavaProfiles.parseIdentity(identity);
            var store = profiles();
            if (offer == null) {
                var existing = store.inspect(name, player, expected);
                if (existing.isEmpty()) task.emit(event("pair_required"));
                else task.emit(paired(existing.get()));
            } else {
                task.emit(paired(store.pair(name, player, expected, offer).name()));
            }
        }, events, exit);
    }

    Task broadcast(String name, List<String> selectors, Consumer<JsonObject> events, Consumer<String> exit) {
        var arguments = new ArrayList<>(List.of("start", "--profile=" + name));
        selectors.forEach(selector -> arguments.add("--app=" + selector));
        return new Task(arguments, null, task -> {
            var platform = platform();
            var profile = profiles().load(name);
            // Extract and verify before the grant so installation cannot consume the server's capture window.
            var executable = helper(platform);
            var upload = new JavaUpload(profile.endpoint(), profile.trust(), profile.token(),
                    () -> NativeCapture.open(platform, executable, selectors, GAME_PID), state -> {
                switch (state) {
                    case READY -> task.emit(event("capture_starting"));
                    case CAPTURING -> task.emit(event("capturing"));
                    case CONNECTING -> { }
                }
            });
            task.upload = upload;
            if (task.stopping) upload.close();
            upload.run();
            task.emit(event("stopped"));
        }, events, exit);
    }

    private JavaProfiles profiles() throws IOException {
        var platform = platform();
        if (platform != EmbeddedSender.Platform.WINDOWS_X64) {
            return new JavaProfiles(JavaProfiles.defaultDirectory(), null);
        }
        Path executable = helper(platform);
        return new JavaProfiles(JavaProfiles.defaultDirectory(), (path, directory) -> {
            Process helper = new ProcessBuilder(executable.toString(), "profile-acl",
                    directory ? "dir" : "file", path.toAbsolutePath().toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                helper.getOutputStream().close();
                if (!helper.waitFor(5, TimeUnit.SECONDS) || helper.exitValue() != 0) {
                    throw new IOException("Windows profile permissions could not be verified.");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Profile permission check was canceled.");
            } finally {
                if (helper.isAlive()) helper.destroyForcibly();
            }
        });
    }

    private static EmbeddedSender.Platform platform() throws SetupFailure {
        var platform = EmbeddedSender.detect(System.getProperty("os.name"), System.getProperty("os.arch"));
        if (platform == null) throw new SetupFailure("unsupported_platform");
        return platform;
    }

    private Path helper(EmbeddedSender.Platform platform) throws SetupFailure {
        Path result;
        try {
            result = EmbeddedSender.executable(platform, platform.cache(), onInstall);
        } catch (IOException exception) {
            throw new SetupFailure("runtime_install_failed");
        }
        if (result == null) throw new SetupFailure("launcher_unavailable");
        return result;
    }

    private static JsonObject event(String name) {
        var event = new JsonObject();
        event.addProperty("event", name);
        return event;
    }

    private static JsonObject paired(String name) {
        var event = event("paired");
        event.addProperty("name", name);
        return event;
    }

    @FunctionalInterface
    private interface Operation {
        void run(Task task) throws IOException, InterruptedException;
    }

    final class Task {
        private final List<String> arguments;
        private final String input;
        private final Operation builtin;
        private final Consumer<JsonObject> events;
        private final Consumer<String> exit;
        private volatile boolean stopping;
        private volatile SenderProcess external;
        private volatile JavaUpload upload;
        private volatile Thread worker;

        private Task(List<String> arguments, String input, Operation builtin,
                     Consumer<JsonObject> events, Consumer<String> exit) {
            this.arguments = arguments;
            this.input = input;
            this.builtin = builtin;
            this.events = events;
            this.exit = exit;
        }

        void launch(boolean broadcast) {
            worker = Thread.ofVirtual().name("svc-java-sender").unstarted(() -> run(broadcast));
            worker.start();
        }

        private void run(boolean broadcast) {
            String fault = null;
            boolean delegated = false;
            try {
                if (stopping) return;
                // An existing override, even an unreadable one, should report its own error.
                if (!Files.notExists(config)) {
                    var child = new SenderProcess(config, arguments, input, this::emit, exit);
                    external = child;
                    if (stopping) child.stop();
                    child.launch(broadcast);
                    delegated = true;
                    return;
                }
                builtin.run(this);
            } catch (SetupFailure e) {
                fault = e.getMessage();
            } catch (JavaProfiles.Failure e) {
                fault = e.code();
            } catch (JavaUpload.Failure e) {
                fault = e.code();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fault = "sender_interrupted";
            } catch (IOException | RuntimeException e) {
                fault = "sender_failed";
            } finally {
                if (!delegated) exit.accept(stopping ? null : fault);
            }
        }

        private void emit(JsonObject event) {
            if (!stopping) events.accept(event);
        }

        void stop() {
            stopping = true;
            JavaUpload currentUpload = upload;
            if (currentUpload != null) currentUpload.close();
            else if (worker != null) worker.interrupt();
            SenderProcess child = external;
            if (child != null) child.stop();
        }
    }

    private static final class SetupFailure extends IOException {
        private SetupFailure(String code) {
            super(code);
        }
    }
}
