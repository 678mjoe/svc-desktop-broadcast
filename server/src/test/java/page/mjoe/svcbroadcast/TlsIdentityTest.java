package page.mjoe.svcbroadcast;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class TlsIdentityTest {
    @TempDir Path dir;
    @Test void reloadAndAutomaticLeafRenewalKeepTheTrustAnchor() throws Exception {
        Clock clock=Clock.fixed(Instant.parse("2026-11-09T00:00:00Z"),ZoneOffset.UTC);
        var first=TlsIdentity.load(dir,"127.0.0.1",clock);
        var same=TlsIdentity.load(dir,"127.0.0.1",clock);
        assertEquals(first.serverId,same.serverId);
        assertArrayEquals(first.leaf.getEncoded(),same.leaf.getEncoded());
        var renewed=TlsIdentity.load(dir,"127.0.0.1",Clock.offset(clock,Duration.ofDays(70)));
        assertEquals(first.caPem(),renewed.caPem());
        assertFalse(java.util.Arrays.equals(first.leaf.getEncoded(),renewed.leaf.getEncoded()));
        assertTrue(renewed.leaf.getNotAfter().after(first.leaf.getNotAfter()));
        var changed=TlsIdentity.load(dir,"localhost",Clock.offset(clock,Duration.ofDays(70)));
        assertEquals(first.serverId,changed.serverId);
        assertEquals("localhost",changed.leaf.getSubjectAlternativeNames().iterator().next().get(1));
    }
    @Test void corruptOrLostIdentityDoesNotSilentlyRegenerateTrust() throws Exception {
        var identity=TlsIdentity.load(dir,"localhost",Clock.systemUTC());
        Files.writeString(dir.resolve("identity.p12"),"corrupt");
        assertThrows(Exception.class,()->TlsIdentity.load(dir,"localhost",Clock.systemUTC()));
        assertEquals("corrupt",Files.readString(dir.resolve("identity.p12")));
        Files.delete(dir.resolve("identity.p12"));
        assertThrows(Exception.class,()->TlsIdentity.load(dir,"localhost",Clock.systemUTC()));
    }
    @Test void expiredCaAndFutureCertificatesAreRejected() throws Exception {
        Clock clock=Clock.fixed(Instant.parse("2026-11-09T00:00:00Z"),ZoneOffset.UTC);
        TlsIdentity.load(dir,"127.0.0.1",clock);
        assertThrows(Exception.class,()->TlsIdentity.load(dir,"127.0.0.1",Clock.offset(clock,Duration.ofDays(3651))));
        assertThrows(Exception.class,()->TlsIdentity.load(dir,"127.0.0.1",Clock.offset(clock,Duration.ofDays(-1))));
    }
    @Test void symlinksAreNeverUsedForPrivateKeys() throws Exception {
        Path other=dir.resolve("other"); Files.writeString(other,"unchanged");
        Path privateDir=dir.resolve("private"); Files.createDirectory(privateDir);
        Files.createSymbolicLink(privateDir.resolve("identity.p12"),other);
        assertThrows(Exception.class,()->TlsIdentity.load(privateDir,"localhost",Clock.systemUTC()));
        assertEquals("unchanged",Files.readString(other));
    }
}
