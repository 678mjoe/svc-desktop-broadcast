package page.mjoe.svcbroadcast;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BroadcastAuthorizationTest {
    @TempDir Path dir;
    static class Time extends Clock {
        long ms=1_800_000_000_000L;
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return Instant.ofEpochMilli(ms);}
    }
    @Test void deviceLimitsPoliciesPersistenceAndRevocation() throws Exception {
        Time clock=new Time(); UUID user=UUID.randomUUID();
        var auth=new BroadcastAuthorization(dir,clock,3);
        auth.grant(user,"Player",clock.ms+60_000);
        var first=auth.redeem(auth.pairingCode(user),"Mac");
        assertNull(auth.authenticate("Bearer "+first.token())); // Default requires MC presence.
        auth.presence(Set.of(user));
        assertEquals(first.principal(),auth.authenticate("Bearer "+first.token()));
        auth.presence(Set.of()); assertFalse(auth.permitted(first.principal()));
        auth.logoutPolicy(user,BroadcastAuthorization.Logout.ALLOW);
        assertTrue(auth.permitted(first.principal()));
        auth.redeem(auth.pairingCode(user),"Windows");
        auth.redeem(auth.pairingCode(user),"Third");
        assertThrows(BroadcastAuthorization.Denied.class,()->auth.pairingCode(user));
        String disk=Files.readString(dir.resolve("authorization.json"));
        assertFalse(disk.contains(first.token()));
        var reopened=new BroadcastAuthorization(dir,clock,3);
        assertTrue(reopened.permitted(first.principal()));
        auth.revokeDevice(user,first.principal().deviceId());
        assertNull(auth.authenticate("Bearer "+first.token()));
        var last=auth.redeem(auth.pairingCode(user),"replacement");
        clock.ms+=61_000; assertFalse(auth.permitted(last.principal()));
        auth.grant(user,"Player",0); assertTrue(auth.permitted(last.principal()));
        auth.revoke(user); auth.grant(user,"Player",0);
        assertNull(auth.authenticate("Bearer "+last.token())); // No credential resurrection.
    }
    @Test void pairCodesAreSingleUseReplacedAndExpireAcrossRestart() throws Exception {
        Time clock=new Time(); UUID user=UUID.randomUUID();
        var auth=new BroadcastAuthorization(dir,clock,3);
        auth.grant(user,"Player",0);
        String replaced=auth.pairingCode(user), code=auth.pairingCode(user);
        assertThrows(BroadcastAuthorization.Denied.class,()->auth.redeem(replaced,"test"));
        auth.redeem(code,"test");
        assertThrows(BroadcastAuthorization.Denied.class,()->auth.redeem(code,"test"));
        String expired=auth.pairingCode(user); clock.ms+=300_001;
        assertThrows(BroadcastAuthorization.Denied.class,()->auth.redeem(expired,"test"));
        String lost=auth.pairingCode(user);
        var reopened=new BroadcastAuthorization(dir,clock,3);
        assertThrows(BroadcastAuthorization.Denied.class,()->reopened.redeem(lost,"test"));
    }
    @Test void globalPolicyAndPerPlayerOverrideAreIndependentOfExpiry() throws Exception {
        Time clock=new Time(); UUID user=UUID.randomUUID();
        var auth=new BroadcastAuthorization(dir,clock,3); auth.grant(user,"Player",0);
        var issued=auth.redeem(auth.pairingCode(user),"test");
        auth.defaultLogout(false); assertTrue(auth.permitted(issued.principal()));
        auth.logoutPolicy(user,BroadcastAuthorization.Logout.STOP); assertFalse(auth.permitted(issued.principal()));
        auth.presence(Set.of(user)); assertTrue(auth.permitted(issued.principal()));
        auth.logoutPolicy(user,BroadcastAuthorization.Logout.INHERIT); auth.presence(Set.of());
        assertTrue(auth.permitted(issued.principal()));
    }
    @Test void malformedStoreDoesNotResetOrAuthorize() throws Exception {
        Files.writeString(dir.resolve("authorization.json"),"broken");
        assertThrows(java.io.IOException.class,()->new BroadcastAuthorization(dir,new Time(),3));
        assertEquals("broken",Files.readString(dir.resolve("authorization.json")));
    }
    @Test void missingOrAmbiguousPolicyAndExpiryFieldsCannotWeakenAuthorization() throws Exception {
        Time clock=new Time();UUID player=UUID.randomUUID();
        var store=new BroadcastAuthorization(dir,clock,3);store.grant(player,"Player",clock.ms+60000);
        String valid=Files.readString(dir.resolve("authorization.json"));
        for(String broken:List.of(valid.replace("\"stopOnLogout\":true,",""),
                valid.replace("\"version\":1,","\"version\":1.5,"),
                valid.replace("\"stopOnLogout\":true","\"stopOnLogout\":true,\"stopOnLogout\":false"),
                valid.replace("\"expiresAt\":"+(clock.ms+60000)+",",""))) {
            Files.writeString(dir.resolve("authorization.json"),broken);
            assertThrows(java.io.IOException.class,()->new BroadcastAuthorization(dir,clock,3));
        }
    }
    @Test void ownMusicExclusionDoesNotChangeOtherListenersOrPersistentPreferences() {
        UUID owner=UUID.randomUUID(),other=UUID.randomUUID();
        assertFalse(VoicechatBridge.eligible(owner,owner));
        assertTrue(VoicechatBridge.eligible(other,owner));
        assertTrue(VoicechatBridge.eligible(owner,null));
    }
}
