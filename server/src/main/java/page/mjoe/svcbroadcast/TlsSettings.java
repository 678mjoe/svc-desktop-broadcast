package page.mjoe.svcbroadcast;

import java.io.IOException;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

record TlsSettings(String bind, int port, String host, int publicPort, int maxDevices, boolean allowOfflinePairing) {
    static final String FILE_NAME = "tls.properties";
    private static final String LOOPBACK = "127.0.0.1";
    private static final int DEFAULT_PORT = 24458;

    static final class ConfigException extends IllegalArgumentException {
        ConfigException(String message) { super(message); }
    }

    static TlsSettings load(Path directory) throws IOException {
        PrivateFiles.directory(directory);
        Path file = directory.resolve(FILE_NAME);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            PrivateFiles.write(file, """
                    # Local-only until you set public_address or explicitly configure an advanced bind.
                    # Use the IP or DNS name players can reach. No scheme, brackets or port.
                    # public_address=play.example.org
                    # port=24458
                    # max_devices=3
                    # Opt in only where you trust the player identities on your offline-mode server.
                    # allow_offline_pairing=false
                    # If NAT maps a different external TCP port, set advertised_port separately.
                    """.getBytes(StandardCharsets.UTF_8));
        }

        Properties properties = new Properties();
        try {
            properties.load(new StringReader(new String(PrivateFiles.read(file, 16384), StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException error) {
            throw new ConfigException("Invalid properties syntax.");
        }
        int port = integer(properties, "port", DEFAULT_PORT, 1, 65535);
        int external = integer(properties, "advertised_port", port, 1, 65535);
        int maxDevices = integer(properties, "max_devices", 3, 1, 16);
        String pairingOption = properties.getProperty("allow_offline_pairing", "false").strip();
        boolean allowOfflinePairing = switch (pairingOption.toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new ConfigException("allow_offline_pairing must be true or false.");
        };
        String publicHost = properties.getProperty("public_address", "").strip();
        if (publicHost.isEmpty()) {
            String bind = properties.getProperty("bind_address", LOOPBACK).strip();
            String host = properties.getProperty("advertised_host", LOOPBACK).strip();
            validateHost("bind_address", bind);
            validateHost("advertised_host", host);
            TlsSettings result = new TlsSettings(bind, port, host, external, maxDevices, allowOfflinePairing);
            result.endpoint();
            return result;
        }

        if (properties.containsKey("bind_address") || properties.containsKey("advertised_host")) {
            throw new ConfigException("Use public_address alone for the host, or keep bind_address and advertised_host for advanced setup.");
        }
        validateHost("public_address", publicHost);
        InetAddress numeric = numericAddress(publicHost);
        if (isLoopback(publicHost) || numeric != null && numeric.isAnyLocalAddress()) {
            throw new ConfigException("public_address must be reachable by players, not a loopback or wildcard address.");
        }
        String bind = publicHost.contains(":") ? "::" : "0.0.0.0";
        TlsSettings result = new TlsSettings(bind, port, publicHost, external, maxDevices, allowOfflinePairing);
        result.endpoint(); // Reject invalid URL hosts before binding or issuing a certificate.
        return result;
    }

    private static int integer(Properties properties, String key, int fallback, int min, int max) {
        String raw = properties.getProperty(key);
        if (raw == null) return fallback;
        try {
            int value = Integer.parseInt(raw.strip());
            if (value >= min && value <= max) return value;
        } catch (NumberFormatException ignored) { }
        throw new ConfigException(key + " must be an integer from " + min + " to " + max + ".");
    }

    private static void validateHost(String key, String host) {
        try {
            TlsIdentity.validateHost(host);
        } catch (IllegalArgumentException error) {
            throw new ConfigException(key + " must be a DNS name or bare IP address, without a scheme, brackets, path or port.");
        }
    }

    private static InetAddress numericAddress(String host) {
        if (host.contains(":") || host.matches("[0-9.]+")) {
            try { return InetAddress.getByName(host); }
            catch (IOException ignored) { }
        }
        return null;
    }

    private static boolean isLoopback(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        InetAddress numeric = numericAddress(host);
        return lower.equals("localhost") || lower.endsWith(".localhost") || numeric != null && numeric.isLoopbackAddress();
    }

    boolean localOnly() { return isLoopback(bind) || isLoopback(host); }

    boolean allowsGamePairing(boolean onlineMode) { return onlineMode || allowOfflinePairing; }

    String endpoint() { return endpoint(publicPort); }

    String endpoint(int actualPublicPort) {
        try {
            return new URI("wss", null, host, actualPublicPort, "/v1/device/audio", null, null).toASCIIString();
        } catch (java.net.URISyntaxException error) {
            throw new ConfigException("The advertised address cannot be used in a WSS URL; check the host.");
        }
    }
}
