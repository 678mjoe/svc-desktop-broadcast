package page.mjoe.svcbroadcast;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ListenerTargetsTest {
    @Test void sessionOwnerIsExcludedWithoutChangingPreferenceSnapshotAndRestoredForNextSession() {
        UUID owner=UUID.randomUUID(), other=UUID.randomUUID();
        VoicechatConnection a=connection(), b=connection();
        // This snapshot already respects persistent listen on/off preferences.
        var snapshot=List.of(new VoicechatBridge.Listener(owner,a),new VoicechatBridge.Listener(other,b));
        List<VoicechatConnection> targets=new ArrayList<>();
        var channel=(StaticAudioChannel)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{StaticAudioChannel.class},
            (proxy,method,args)-> {
                switch(method.getName()) {
                    case "clearTargets" -> targets.clear();
                    case "addTarget" -> targets.add((VoicechatConnection)args[0]);
                    case "send" -> {assertEquals(1,targets.size());assertSame(b,targets.getFirst());}
                }
                return null;
            });
        VoicechatBridge.updateTargets(channel,snapshot,owner);
        channel.send(new byte[0]); // Targets are established before the first encoded frame.
        assertEquals(2,snapshot.size());
        VoicechatBridge.updateTargets(channel,snapshot,null);
        assertEquals(2,targets.size());assertSame(a,targets.getFirst());
        // A player whose persisted preference was off remains absent when a session ends.
        VoicechatBridge.updateTargets(channel,List.of(snapshot.getLast()),null);
        assertEquals(1,targets.size());assertSame(b,targets.getFirst());
    }
    private VoicechatConnection connection() {
        return (VoicechatConnection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{VoicechatConnection.class},
            (proxy,method,args)->null);
    }
}
