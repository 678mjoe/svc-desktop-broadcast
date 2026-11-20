package page.mjoe.svcbroadcast;

import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;

@ForgeVoicechatPlugin
public final class BroadcastPlugin implements VoicechatPlugin {
    @Override
    public String getPluginId() {
        return BroadcastMod.ID;
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class,
                event -> BroadcastMod.BROADCAST.attach(event.getVoicechat()));
        registration.registerEvent(VoicechatServerStoppedEvent.class,
                event -> BroadcastMod.BROADCAST.close());
    }
}
