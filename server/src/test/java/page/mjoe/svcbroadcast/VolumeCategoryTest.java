package page.mjoe.svcbroadcast;

import de.maxhenkel.voicechat.net.AddCategoryPacket;
import de.maxhenkel.voicechat.plugins.impl.VolumeCategoryImpl;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VolumeCategoryTest {
    @Test
    void broadcastCategorySurvivesTheRealLoginPacketRoundTrip() {
        var category = (VolumeCategoryImpl) VoicechatBridge.createVolumeCategory(
                new VolumeCategoryImpl.BuilderImpl());
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new AddCategoryPacket(category).toBytes(buffer);
            var decoded = new AddCategoryPacket().fromBytes(buffer).getCategory();
            assertEquals("svc_broadcast", decoded.getId());
            assertEquals(category.getName(), decoded.getName());
            assertEquals(category.getDescription(), decoded.getDescription());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void pinnedSerializerReproducesThePreviousSeventeenCharacterFailure() {
        var category = new VolumeCategoryImpl("svc_broadcast", "Desktop Broadcast",
                null, "Independent desktop music broadcast", null, null);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            assertThrows(EncoderException.class, () -> new AddCategoryPacket(category).toBytes(buffer));
        } finally {
            buffer.release();
        }
    }
}
