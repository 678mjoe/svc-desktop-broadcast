package page.mjoe.svcbroadcast.sender;

import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/** Reads and issues the same per-user v1 profiles as the independent sender. */
final class JavaProfiles {
    private static final int MAX_BLOB = 16_384;
    private static final int MAX_PROFILE = 32_768;
    private static final int MAX_REPLY = 4_096;
    private static final Duration PAIR_TIMEOUT = Duration.ofSeconds(5);
    private static final String CERT_HEADER = "-----BEGIN CERTIFICATE-----";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> IDENTITY_FIELDS = Set.of("version", "server_id", "url", "ca_pem");
    private static final Set<String> OFFER_FIELDS = Set.of("version", "server_id", "url", "ca_pem", "code", "expires_at");
    private static final Set<String> REPLY_FIELDS = Set.of("version", "server_id", "device_id", "player_id", "token", "expires_at");
    private static final Set<String> PROFILE_FIELDS = Set.of("version", "server_id", "url", "ca_pem",
            "device_id", "player_id", "token", "expires_at");

    private final Path directory;
    private final WindowsAcl windowsAcl;

    @FunctionalInterface
    interface WindowsAcl {
        void enforce(Path path, boolean directory) throws IOException;
    }

    record Identity(UUID serverId, URI endpoint, String caPem, SSLContext trust) {
        boolean matches(Identity other) {
            return serverId.equals(other.serverId) && endpoint.toString().equals(other.endpoint.toString())
                    && caPem.equals(other.caPem);
        }
    }

    record Profile(String name, Identity identity, UUID deviceId, UUID playerId, String token, long expiresAt) {
        URI endpoint() { return identity.endpoint(); }
        SSLContext trust() { return identity.trust(); }

        // Keep credential material out of diagnostics if this value is logged.
        @Override
        public String toString() { return "Profile[" + name + "]"; }
    }

    static final class Failure extends IOException {
        private final String code;

        Failure(String code) {
            super(code);
            this.code = code;
        }

        String code() { return code; }
    }

    JavaProfiles(Path directory, WindowsAcl windowsAcl) {
        this.directory = directory.toAbsolutePath().normalize();
        this.windowsAcl = windowsAcl;
    }

    static Path defaultDirectory() {
        Path home = Path.of(System.getProperty("user.home"));
        String system = System.getProperty("os.name");
        if (system.startsWith("Mac")) return home.resolve("Library/Application Support/svc-broadcast/profiles");
        if (system.startsWith("Windows")) {
            String local = System.getenv("LOCALAPPDATA");
            return (local == null || local.isBlank() ? home.resolve("AppData/Local") : Path.of(local))
                    .resolve("svc-broadcast/profiles");
        }
        return home.resolve(".config/svc-broadcast/profiles");
    }

    List<String> list() throws Failure {
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        var names = new ArrayList<String>();
        try {
            privatePath(directory, true);
            try (var files = Files.newDirectoryStream(directory, "*.json")) {
                for (Path file : files) {
                    String filename = file.getFileName().toString();
                    String name = filename.substring(0, filename.length() - 5);
                    path(name);
                    names.add(name);
                }
            }
            names.sort(String::compareTo);
            return List.copyOf(names);
        } catch (Failure failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new Failure("profile_permissions");
        }
    }

    Profile load(String name) throws Failure {
        Path file = path(name);
        Map<String, Object> values;
        try {
            privatePath(directory, true);
            privatePath(file, false);
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] raw = input.readNBytes(MAX_PROFILE + 1);
                if (raw.length > MAX_PROFILE) throw new IllegalArgumentException();
                values = object(raw);
            }
        } catch (IOException | RuntimeException failure) {
            throw new Failure("profile_file");
        }
        try {
            if (!values.keySet().equals(PROFILE_FIELDS)) throw new IllegalArgumentException();
            return profile(name, values, identity(values));
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            throw new Failure("profile_invalid");
        }
    }

    Optional<String> inspect(String name, UUID player, Identity expected) throws Failure {
        if (Files.exists(path(name), LinkOption.NOFOLLOW_LINKS)) {
            Profile saved = load(name);
            if (!saved.playerId().equals(player) || !saved.identity().matches(expected)) {
                throw new Failure("profile_identity_changed");
            }
            return Optional.of(name);
        }

        boolean conflict = false;
        for (String candidate : list()) {
            Profile saved;
            try {
                saved = load(candidate);
            } catch (Failure ignored) {
                continue;
            }
            if (!saved.playerId().equals(player)) continue;
            if (saved.identity().matches(expected)) return Optional.of(candidate);
            if (saved.identity().serverId().equals(expected.serverId())
                    || saved.endpoint().toString().equals(expected.endpoint().toString())) {
                conflict = true;
            }
        }
        if (conflict) throw new Failure("profile_identity_changed");
        return Optional.empty();
    }

    static Identity parseIdentity(String blob) throws Failure {
        try {
            var values = decode(blob, "svcid1:");
            if (!values.keySet().equals(IDENTITY_FIELDS)) throw new IllegalArgumentException();
            return identity(values);
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            throw new Failure("pairing_identity");
        }
    }

    Profile pair(String name, UUID player, Identity expected, String blob) throws Failure {
        Path destination = path(name);
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw new Failure("profile_exists");
        String code = pairingCode(expected, blob);
        // Check storage before redeeming a one-use code. The temporary file has no secret yet.
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            privatePath(directory, true);
            temporary = Files.createTempFile(directory, ".profile-", ".tmp");
            privatePath(temporary, false);
            Profile issued = redeem(name, player, expected, code);
            if (Thread.currentThread().isInterrupted()) throw new Failure("pairing_connection");
            publish(temporary, destination, issued);
            return issued;
        } catch (Failure failure) {
            throw failure;
        } catch (FileAlreadyExistsException failure) {
            throw new Failure("profile_exists");
        } catch (IOException | RuntimeException failure) {
            throw new Failure("profile_permissions");
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The temporary file stays private. A published profile remains usable.
                }
            }
        }
    }

    private static String pairingCode(Identity expected, String blob) throws Failure {
        try {
            var values = decode(blob, "svcb1:");
            if (!values.keySet().equals(OFFER_FIELDS)) throw new IllegalArgumentException();
            Identity offer = identity(values);
            if (!expected.matches(offer)) throw new Failure("profile_identity_changed");
            long now = System.currentTimeMillis();
            long expiry = number(values, "expires_at");
            String code = string(values, "code");
            if (expiry <= now || expiry > now + 600_000 || !TOKEN.matcher(code).matches()) {
                throw new IllegalArgumentException();
            }
            return code;
        } catch (Failure failure) {
            throw failure;
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            throw new Failure("pairing_info");
        }
    }

    private static Profile redeem(String name, UUID player, Identity identity, String code) throws Failure {
        var body = new JsonObject();
        body.addProperty("code", code);
        body.addProperty("label", "Minecraft sender");
        body.addProperty("server_id", identity.serverId().toString());
        var request = HttpRequest.newBuilder(URI.create("https://" + identity.endpoint().getRawAuthority() + "/v1/pair"))
                .header("Content-Type", "application/json")
                .timeout(PAIR_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        try {
            var client = JavaUpload.newClient(identity.trust());
            try {
                var response = client.sendAsync(request, info -> boundedReply())
                        .get(PAIR_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                if (response.statusCode() != 200) throw new Failure("pairing_rejected");
                var values = object(response.body());
                if (!values.keySet().equals(REPLY_FIELDS) || !uuid(values, "server_id").equals(identity.serverId())) {
                    throw new IllegalArgumentException();
                }
                Profile issued = profile(name, values, identity);
                if (!issued.playerId().equals(player)) throw new Failure("pairing_player");
                return issued;
            } finally {
                client.shutdownNow();
            }
        } catch (Failure failure) {
            throw failure;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new Failure("pairing_connection");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
                if (cause instanceof SSLHandshakeException || cause instanceof SSLPeerUnverifiedException) {
                    throw new Failure("tls_verification");
                }
            }
            throw new Failure("pairing_connection");
        } catch (TimeoutException | IOException | RuntimeException failure) {
            throw new Failure("pairing_connection");
        }
    }

    private static HttpResponse.BodySubscriber<byte[]> boundedReply() {
        return new HttpResponse.BodySubscriber<>() {
            private final HttpResponse.BodySubscriber<byte[]> buffer = HttpResponse.BodySubscribers.ofByteArray();
            private Flow.Subscription subscription;
            private int remaining = MAX_REPLY;

            @Override
            public java.util.concurrent.CompletionStage<byte[]> getBody() { return buffer.getBody(); }

            @Override
            public void onSubscribe(Flow.Subscription value) {
                subscription = value;
                buffer.onSubscribe(value);
            }

            @Override
            public void onNext(List<ByteBuffer> chunk) {
                for (ByteBuffer bytes : chunk) {
                    if (bytes.remaining() > remaining) {
                        subscription.cancel();
                        buffer.onError(new IOException("Pairing reply too large"));
                        return;
                    }
                    remaining -= bytes.remaining();
                }
                buffer.onNext(chunk);
            }

            @Override
            public void onError(Throwable failure) { buffer.onError(failure); }

            @Override
            public void onComplete() { buffer.onComplete(); }
        };
    }

    private static void publish(Path temporary, Path destination, Profile profile) throws IOException {
        var json = new JsonObject();
        json.addProperty("version", 1);
        json.addProperty("server_id", profile.identity().serverId().toString());
        json.addProperty("url", profile.endpoint().toString());
        json.addProperty("ca_pem", profile.identity().caPem());
        json.addProperty("device_id", profile.deviceId().toString());
        json.addProperty("player_id", profile.playerId().toString());
        json.addProperty("token", profile.token());
        json.addProperty("expires_at", profile.expiresAt());
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(json.toString());
        try (var output = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            while (bytes.hasRemaining()) output.write(bytes);
            output.force(true);
        }
        Files.createLink(destination, temporary);
    }

    private void privatePath(Path path, boolean isDirectory) throws IOException {
        var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (isDirectory ? !attrs.isDirectory() : !attrs.isRegularFile()) throw new IOException();
        if (System.getProperty("os.name").startsWith("Windows")) {
            if (windowsAcl == null) throw new IOException();
            windowsAcl.enforce(path, isDirectory);
        } else {
            var view = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) throw new IOException();
            var owner = path.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"));
            if (!view.readAttributes().owner().equals(owner)) throw new IOException();
            view.setPermissions(PosixFilePermissions.fromString(isDirectory ? "rwx------" : "rw-------"));
        }
    }

    private Path path(String name) throws Failure {
        if (name == null || !NAME.matcher(name).matches()) throw new Failure("profile_name");
        return directory.resolve(name + ".json");
    }

    private static Identity identity(Map<String, Object> fields) throws GeneralSecurityException, IOException {
        if (number(fields, "version") != 1) throw new IllegalArgumentException();
        UUID server = uuid(fields, "server_id");
        URI endpoint = URI.create(string(fields, "url"));
        if (!"wss".equals(endpoint.getScheme()) || endpoint.getHost() == null || endpoint.getHost().contains("%")
                || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                || endpoint.getRawAuthority().endsWith(":") || endpoint.getPort() == 0 || endpoint.getPort() > 65535
                || !"/v1/device/audio".equals(endpoint.getRawPath())) {
            throw new IllegalArgumentException();
        }
        String ca = string(fields, "ca_pem");
        return new Identity(server, endpoint, ca, trust(ca));
    }

    private static SSLContext trust(String pem) throws GeneralSecurityException, IOException {
        int start = pem.indexOf(CERT_HEADER);
        if (pem.length() > 8_192 || start < 0 || start != pem.lastIndexOf(CERT_HEADER)) {
            throw new IllegalArgumentException();
        }
        var factory = CertificateFactory.getInstance("X.509");
        var stream = new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII));
        var certs = factory.generateCertificates(stream);
        if (certs.size() != 1 || !(certs.iterator().next() instanceof X509Certificate certificate)) {
            throw new IllegalArgumentException();
        }
        certificate.checkValidity();
        KeyStore roots = KeyStore.getInstance("PKCS12");
        roots.load(null, null);
        roots.setCertificateEntry("minecraft-broadcast", certificate);
        var manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        manager.init(roots);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, manager.getTrustManagers(), null);
        return context;
    }

    private static Profile profile(String name, Map<String, Object> fields, Identity identity) {
        if (number(fields, "version") != 1) throw new IllegalArgumentException();
        String token = string(fields, "token");
        if (!TOKEN.matcher(token).matches()) throw new IllegalArgumentException();
        return new Profile(name, identity, uuid(fields, "device_id"), uuid(fields, "player_id"),
                token, number(fields, "expires_at"));
    }

    private static Map<String, Object> decode(String blob, String prefix) throws IOException {
        if (blob == null || blob.length() > MAX_BLOB || !blob.startsWith(prefix)) throw new IllegalArgumentException();
        String encoded = blob.substring(prefix.length());
        if (!encoded.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
        return object(Base64.getUrlDecoder().decode(encoded));
    }

    private static Map<String, Object> object(byte[] bytes) throws IOException {
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        try (var reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            var values = new HashMap<String, Object>();
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                JsonToken type = reader.peek();
                Object value;
                if (type == JsonToken.STRING) {
                    value = reader.nextString();
                } else if (type == JsonToken.NUMBER) {
                    String numeric = reader.nextString();
                    if (!numeric.matches("0|[1-9][0-9]*")) throw new IllegalArgumentException();
                    value = Long.parseLong(numeric);
                } else {
                    throw new IllegalArgumentException();
                }
                if (values.putIfAbsent(key, value) != null) throw new IllegalArgumentException();
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException();
            return values;
        }
    }

    private static String string(Map<String, Object> fields, String key) {
        if (fields.get(key) instanceof String value) return value;
        throw new IllegalArgumentException();
    }

    private static long number(Map<String, Object> fields, String key) {
        if (fields.get(key) instanceof Long value) return value;
        throw new IllegalArgumentException();
    }

    private static UUID uuid(Map<String, Object> fields, String key) {
        String raw = string(fields, key);
        UUID parsed = UUID.fromString(raw);
        if (!parsed.toString().equals(raw)) throw new IllegalArgumentException();
        return parsed;
    }
}
