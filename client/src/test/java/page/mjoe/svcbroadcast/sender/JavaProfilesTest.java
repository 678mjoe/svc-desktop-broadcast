package page.mjoe.svcbroadcast.sender;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JavaProfilesTest {
    private static final Gson GSON = new Gson();
    private static final UUID SERVER = UUID.fromString("2dea8d88-218b-46ac-9569-84b4ec41c00f");
    private static final UUID PLAYER = UUID.fromString("463d8f80-19c0-4650-853f-67a98b97d309");
    private static final UUID DEVICE = UUID.fromString("38c6e163-a8a5-4998-86c2-d0daf4cad068");
    private static final String TOKEN = "a".repeat(64);
    private static final String CODE = "b".repeat(64);

    @TempDir
    Path work;

    @Test
    void reusesV1ProfileByIdentityAndPlayer() throws Exception {
        Path directory = profileDirectory();
        Files.createDirectory(directory);
        var profiles = new JavaProfiles(directory, (path, dir) -> {});
        String ca = fixtureCa();
        String endpoint = "wss://broadcast.example:24458/v1/device/audio";
        Path existing = directory.resolve("legacy.json");
        Files.writeString(existing, GSON.toJson(profile(endpoint, ca, PLAYER)));
        var identity = JavaProfiles.parseIdentity(blob("svcid1:", identity(endpoint, ca)));

        assertEquals(List.of("legacy"), profiles.list());
        assertEquals("legacy", profiles.inspect("mc-next", PLAYER, identity).orElseThrow());
        assertEquals("legacy", profiles.inspect("legacy", PLAYER, identity).orElseThrow());
        assertTrue(profiles.inspect("mc-next", UUID.randomUUID(), identity).isEmpty());
        assertEquals("profile_identity_changed",
                assertThrows(JavaProfiles.Failure.class,
                        () -> profiles.inspect("legacy", UUID.randomUUID(), identity)).code());
        var changed = JavaProfiles.parseIdentity(blob("svcid1:",
                identity("wss://broadcast.example:24459/v1/device/audio", ca)));
        assertEquals("profile_identity_changed",
                assertThrows(JavaProfiles.Failure.class, () -> profiles.inspect("mc-next", PLAYER, changed)).code());
        assertEquals(Long.MAX_VALUE, profiles.load("legacy").expiresAt());
        assertEquals(TOKEN, profiles.load("legacy").token());
        assertFalse(profiles.load("legacy").toString().contains(TOKEN));
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(existing));
        }
    }

    @Test
    void rejectsDuplicateFieldsAndWrongEndpoint() throws Exception {
        String endpoint = "wss://broadcast.example:24458/v1/device/audio";
        String ca = fixtureCa();
        String duplicate = "{\"version\":1," + GSON.toJson(identity(endpoint, ca)).substring(1);
        assertEquals("pairing_identity", assertThrows(JavaProfiles.Failure.class,
                () -> JavaProfiles.parseIdentity(encoded("svcid1:", duplicate))).code());
        for (String url : List.of(endpoint + "?", endpoint + "#", endpoint.replace("wss:", "ws:"),
                endpoint.replace("broadcast", "name@broadcast"), endpoint.replace("24458", "65536"))) {
            assertEquals("pairing_identity", assertThrows(JavaProfiles.Failure.class,
                    () -> JavaProfiles.parseIdentity(blob("svcid1:", identity(url, ca)))).code());
        }
        Path directory = profileDirectory();
        Files.createDirectory(directory);
        String repeated = "{\"token\":\"" + TOKEN + "\"," + GSON.toJson(profile(endpoint, ca, PLAYER)).substring(1);
        Files.writeString(directory.resolve("legacy.json"), repeated);
        var profiles = new JavaProfiles(directory, (path, dir) -> {});
        assertEquals("profile_file", assertThrows(JavaProfiles.Failure.class, () -> profiles.load("legacy")).code());
        assertEquals("profile_name", assertThrows(JavaProfiles.Failure.class, () -> profiles.load("../legacy")).code());
    }

    @Test
    void rejectsSymlinksAndExpiredOffersBeforeRedemption() throws Exception {
        Path directory = profileDirectory();
        var profiles = new JavaProfiles(directory, (path, dir) -> {});
        String endpoint = "wss://broadcast.example:24458/v1/device/audio";
        String ca = fixtureCa();
        var identity = JavaProfiles.parseIdentity(blob("svcid1:", identity(endpoint, ca)));
        var offer = offer(endpoint, ca);
        offer.put("expires_at", 1L);
        assertEquals("pairing_info", assertThrows(JavaProfiles.Failure.class,
                () -> profiles.pair("mc-next", PLAYER, identity, blob("svcb1:", offer))).code());
        assertFalse(Files.exists(directory));

        if (Files.getFileStore(work).supportsFileAttributeView("posix")) {
            Files.createDirectory(directory);
            Path target = Files.writeString(work.resolve("do-not-touch"), GSON.toJson(profile(endpoint, ca, PLAYER)));
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r--r--"));
            Files.createSymbolicLink(directory.resolve("linked.json"), target);
            assertEquals("profile_file", assertThrows(JavaProfiles.Failure.class,
                    () -> profiles.load("linked")).code());
            assertEquals("profile_exists", assertThrows(JavaProfiles.Failure.class,
                    () -> profiles.pair("linked", PLAYER, identity, blob("svcb1:", offer))).code());
            assertEquals(PosixFilePermissions.fromString("rw-r--r--"), Files.getPosixFilePermissions(target));
        }
    }

    @Test
    void pairsOverPinnedHttpsAndRefusesOtherHostOrPlayer() throws Exception {
        var calls = new AtomicInteger();
        try (var server = endpoint(exchange -> {
            calls.incrementAndGet();
            try {
                String requestBody = new String(exchange.getRequestBody().readNBytes(2_049), StandardCharsets.UTF_8);
                if (!"POST".equals(exchange.getRequestMethod()) || !requestBody.contains(CODE)
                        || !requestBody.contains(SERVER.toString())) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
                byte[] result = GSON.toJson(reply(PLAYER)).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, result.length);
                exchange.getResponseBody().write(result);
            } catch (IOException error) {
                throw new AssertionError(error);
            } finally {
                exchange.close();
            }
        })) {
            String url = server.url("localhost");
            String ca = server.ca();
            var profiles = new JavaProfiles(profileDirectory(), (path, dir) -> {});
            var identity = JavaProfiles.parseIdentity(blob("svcid1:", identity(url, ca)));
            String offer = blob("svcb1:", offer(url, ca));

            assertEquals("paired", profiles.pair("paired", PLAYER, identity, offer).name());
            assertEquals(PLAYER, profiles.load("paired").playerId());
            assertEquals(1, calls.get());
            assertEquals("profile_exists", assertThrows(JavaProfiles.Failure.class,
                    () -> profiles.pair("paired", PLAYER, identity, offer)).code());
            assertEquals(1, calls.get());

            assertEquals("pairing_player", assertThrows(JavaProfiles.Failure.class,
                    () -> profiles.pair("other", UUID.randomUUID(), identity, offer)).code());
            assertEquals(List.of("paired"), profiles.list());
            assertEquals(2, calls.get());

            String wrongHost = server.url("127.0.0.1");
            var wrongName = JavaProfiles.parseIdentity(blob("svcid1:", identity(wrongHost, ca)));
            assertEquals("tls_verification", assertThrows(JavaProfiles.Failure.class,
                    () -> profiles.pair("wrong-host", PLAYER, wrongName, blob("svcb1:", offer(wrongHost, ca)))).code());
            assertEquals(2, calls.get());

            var wrongCa = fixtureCa();
            var wrongTrust = JavaProfiles.parseIdentity(blob("svcid1:", identity(url, wrongCa)));
            assertEquals("tls_verification", assertThrows(JavaProfiles.Failure.class,
                    () -> profiles.pair("wrong-ca", PLAYER, wrongTrust, blob("svcb1:", offer(url, wrongCa)))).code());
            assertEquals(2, calls.get());
        }
    }

    @Test
    void concurrentPairingsPublishExactlyOneProfile() throws Exception {
        var arrivals = new CountDownLatch(2);
        var requests = new AtomicInteger();
        try (var server = endpoint(exchange -> {
            int request = requests.incrementAndGet();
            try {
                arrivals.countDown();
                assertTrue(arrivals.await(4, TimeUnit.SECONDS), "Both redemptions must reach the server before publication");
                var issued = new LinkedHashMap<>(reply(PLAYER));
                issued.put("token", request == 1 ? TOKEN : CODE);
                respond(exchange, issued);
            } catch (IOException | InterruptedException error) {
                throw new AssertionError(error);
            } finally {
                exchange.close();
            }
        }); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = new JavaProfiles(profileDirectory(), (path, dir) -> {});
            var second = new JavaProfiles(profileDirectory(), (path, dir) -> {});
            String url = server.url("localhost");
            var identity = JavaProfiles.parseIdentity(blob("svcid1:", identity(url, server.ca())));
            var updated = offer(url, server.ca());
            updated.put("code", TOKEN);
            var attempts = List.of(
                    workers.submit(() -> first.pair("same", PLAYER, identity, blob("svcb1:", offer(url, server.ca())))),
                    workers.submit(() -> second.pair("same", PLAYER, identity, blob("svcb1:", updated))));

            JavaProfiles.Profile winner = null;
            int conflicts = 0;
            for (var attempt : attempts) {
                try {
                    var issued = attempt.get(8, TimeUnit.SECONDS);
                    assertNull(winner, "Concurrent pairing must not replace a published profile");
                    winner = issued;
                } catch (ExecutionException error) {
                    var failure = assertInstanceOf(JavaProfiles.Failure.class, error.getCause());
                    assertEquals("profile_exists", failure.code());
                    conflicts++;
                }
            }
            assertNotNull(winner);
            assertEquals(1, conflicts);
            assertEquals(2, requests.get());
            assertTrue(winner.token().equals(first.load("same").token()), "Stored credential must belong to the winner");
            try (var files = Files.list(profileDirectory())) {
                assertEquals(List.of("same.json"), files.map(path -> path.getFileName().toString()).toList());
            }
        }
    }

    @Test
    void existingPythonAndJavaProfilesAreMutuallyReadable() throws Exception {
        Path sender = senderDirectory();
        Path python = sender.resolve(System.getProperty("os.name").startsWith("Windows")
                ? ".venv/Scripts/python.exe" : ".venv/bin/python");
        assumeTrue(Files.isExecutable(python), "Set up the standalone sender virtual environment for interop tests");
        try (var server = endpoint(exchange -> {
            try {
                respond(exchange, reply(PLAYER));
            } catch (IOException error) {
                throw new AssertionError(error);
            } finally {
                exchange.close();
            }
        })) {
            String url = server.url("localhost");
            var data = profile(url, server.ca(), PLAYER);
            var identity = JavaProfiles.parseIdentity(blob("svcid1:", identity(url, server.ca())));
            var profiles = new JavaProfiles(profileDirectory(), (path, dir) -> {});

            runPythonProfile(python, sender, "save", "python-v1", data);
            assertEquals("python-v1", profiles.inspect("mc-next", PLAYER, identity).orElseThrow());
            var existing = profiles.load("python-v1");
            assertTrue(TOKEN.equals(existing.token()), "Legacy credential differs");
            assertEquals(Long.MAX_VALUE, existing.expiresAt());

            profiles.pair("java-v1", PLAYER, identity, blob("svcb1:", offer(url, server.ca())));
            runPythonProfile(python, sender, "load", "java-v1", data);
        }
    }

    private void runPythonProfile(Path python, Path sender, String operation, String name,
                                  Map<String, Object> data) throws Exception {
        String program = """
                import json
                import sys
                sys.path.insert(0, sys.argv[1])
                from svc_broadcast_sender.profiles import load_profile, save_profile
                operation, name, directory = sys.argv[2:]
                expected = json.load(sys.stdin)
                if operation == "save":
                    save_profile(name, expected, directory)
                elif load_profile(name, directory) != expected:
                    raise AssertionError("Profile mismatch")
                """;
        Process child = new ProcessBuilder(python.toString(), "-I", "-B", "-c", program,
                sender.resolve("src").toString(), operation, name, profileDirectory().toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            try (var input = child.getOutputStream()) {
                input.write(GSON.toJson(data).getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(child.waitFor(8, TimeUnit.SECONDS), "Python profile interop timed out");
            assertEquals(0, child.exitValue(), "Python profile interop failed");
        } finally {
            child.destroyForcibly();
        }
    }

    private static void respond(HttpsExchange exchange, Map<String, Object> issued) throws IOException {
        exchange.getRequestBody().readNBytes(2_049);
        byte[] result = GSON.toJson(issued).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, result.length);
        exchange.getResponseBody().write(result);
    }

    private Path profileDirectory() {
        return work.resolve("profiles");
    }

    private PairEndpoint endpoint(Consumer<HttpsExchange> handler) throws Exception {
        Path storeFile = work.resolve("pair-test.p12");
        char[] password = "temporary-test".toCharArray();
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        Process keygen = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "pair-test",
                "-dname", "CN=localhost", "-keyalg", "EC", "-validity", "1",
                "-ext", "SAN=dns:localhost", "-storetype", "PKCS12",
                "-storepass", new String(password), "-keystore", storeFile.toString(), "-noprompt")
                .redirectErrorStream(true).start();
        try {
            assertTrue(keygen.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, keygen.exitValue(), new String(keygen.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            keygen.destroyForcibly();
        }

        var keys = KeyStore.getInstance("PKCS12");
        try (var contents = Files.newInputStream(storeFile)) {
            keys.load(contents, password);
        }
        var keyManager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManager.init(keys, password);
        var context = SSLContext.getInstance("TLS");
        context.init(keyManager.getKeyManagers(), null, null);
        HttpsServer https = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = Executors.newVirtualThreadPerTaskExecutor();
        https.setExecutor(requests);
        https.setHttpsConfigurator(new HttpsConfigurator(context));
        https.createContext("/v1/pair", exchange -> handler.accept((HttpsExchange) exchange));
        https.start();
        var pem = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(keys.getCertificate("pair-test").getEncoded());
        return new PairEndpoint(https, requests, "-----BEGIN CERTIFICATE-----\n" + pem + "\n-----END CERTIFICATE-----\n");
    }

    private record PairEndpoint(HttpsServer https, ExecutorService requests, String ca) implements AutoCloseable {
        String url(String host) {
            return "wss://" + host + ":" + https.getAddress().getPort() + "/v1/device/audio";
        }

        @Override
        public void close() {
            https.stop(0);
            requests.shutdownNow();
        }
    }

    private static Path senderDirectory() {
        Path sender = Path.of("sender");
        if (!Files.isDirectory(sender)) sender = Path.of("..").resolve(sender);
        return sender.toAbsolutePath().normalize();
    }

    private static String fixtureCa() throws IOException {
        return Files.readString(senderDirectory().resolve("tests/fixtures/test-ca.pem"));
    }

    private static Map<String, Object> identity(String url, String ca) {
        return new LinkedHashMap<>(Map.of("version", 1, "server_id", SERVER.toString(), "url", url, "ca_pem", ca));
    }

    private static Map<String, Object> offer(String url, String ca) {
        var offer = identity(url, ca);
        offer.put("code", CODE);
        offer.put("expires_at", System.currentTimeMillis() + 60_000);
        return offer;
    }

    private static Map<String, Object> reply(UUID player) {
        return Map.of("version", 1, "server_id", SERVER.toString(),
                "device_id", DEVICE.toString(), "player_id", player.toString(), "token", TOKEN,
                "expires_at", Long.MAX_VALUE);
    }

    private static Map<String, Object> profile(String url, String ca, UUID player) {
        var profile = identity(url, ca);
        profile.putAll(reply(player));
        return profile;
    }

    private static String blob(String prefix, Map<String, Object> payload) {
        return encoded(prefix, GSON.toJson(payload));
    }

    private static String encoded(String prefix, String json) {
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
