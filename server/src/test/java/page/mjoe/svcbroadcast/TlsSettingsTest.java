package page.mjoe.svcbroadcast;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TlsSettingsTest {
    @TempDir Path directory;

    @Test void freshAndBlankSettingsStayLocal() throws Exception {
        TlsSettings defaults = TlsSettings.load(directory);
        assertTrue(defaults.localOnly());
        assertEquals("wss://127.0.0.1:24458/v1/device/audio", defaults.endpoint());
        assertTrue(Files.readString(directory.resolve(TlsSettings.FILE_NAME)).contains("# public_address="));
        assertEquals(defaults, load("public_address=   \n"));
    }

    @Test void onePublicHostSuppliesBindEndpointAndPortDefaults() throws Exception {
        TlsSettings direct = load("public_address=play.example.org\n");
        assertEquals("0.0.0.0", direct.bind());
        assertEquals(24458, direct.port());
        assertEquals("wss://play.example.org:24458/v1/device/audio", direct.endpoint());
        assertFalse(direct.localOnly());

        TlsSettings ipv6 = load("public_address=2001:db8::42\nport=25058\n");
        assertEquals("::", ipv6.bind());
        assertEquals("wss://[2001:db8::42]:25058/v1/device/audio", ipv6.endpoint());
    }

    @Test void explicitPortMappingAndLegacySettingsKeepTheirMeaning() throws Exception {
        TlsSettings simple = load("public_address=192.0.2.5\nport=25058\nadvertised_port=443\n");
        assertEquals(25058, simple.port());
        assertEquals("wss://192.0.2.5:443/v1/device/audio", simple.endpoint());
        String legacy = "bind_address=192.0.2.8\nport=25058\nadvertised_host=play.example.org\nadvertised_port=443\nmax_devices=5\n";
        assertEquals(new TlsSettings("192.0.2.8", 25058, "play.example.org", 443, 5, false), load(legacy));
        assertEquals(legacy, Files.readString(directory.resolve(TlsSettings.FILE_NAME)));
    }

    @Test void offlinePairingRequiresExplicitOptIn() throws Exception {
        TlsSettings defaults = load("");
        assertTrue(defaults.allowsGamePairing(true));
        assertFalse(defaults.allowsGamePairing(false));
        assertFalse(load("allow_offline_pairing=false\n").allowsGamePairing(false));
        assertTrue(load("allow_offline_pairing=true\n").allowsGamePairing(false));
        assertTrue(load("public_address=play.example.org\nallow_offline_pairing=true\n").allowsGamePairing(false));
        assertThrows(TlsSettings.ConfigException.class, () -> load("allow_offline_pairing=yes\n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "a.localhost", "127.0.0.1", "::1", "0.0.0.0", "::",
            "https://play.example.org", "play.example.org:24458", "[2001:db8::42]", "example..org"})
    void rejectsUnsafeOrMalformedDirectAddresses(String address) {
        assertThrows(TlsSettings.ConfigException.class, () -> load("public_address=" + address + "\n"));
    }

    @Test void flagsConflictingModesAndInvalidNumbersWithoutEchoingValues() {
        assertTrue(assertThrows(TlsSettings.ConfigException.class,
                () -> load("public_address=play.example.org\nbind_address=127.0.0.1\n"))
                .getMessage().contains("public_address alone"));
        String message = assertThrows(TlsSettings.ConfigException.class, () -> load("port=private-value\n")).getMessage();
        assertTrue(message.contains("port"));
        assertFalse(message.contains("private-value"));
    }

    private TlsSettings load(String properties) throws Exception {
        Files.writeString(directory.resolve(TlsSettings.FILE_NAME), properties);
        return TlsSettings.load(directory);
    }
}
