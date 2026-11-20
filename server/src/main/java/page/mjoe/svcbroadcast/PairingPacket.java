package page.mjoe.svcbroadcast;

import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Minecraft play channel for optional sender pairing, version 1. */
record PairingPacket(UUID request, byte stage, String data) implements CustomPacketPayload {
    static final byte LOOKUP = 0, ISSUE = 1, IDENTITY = 2, OFFER = 3, UNAVAILABLE = 4;
    static final Type<PairingPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("svc_broadcast", "pairing"));
    static final StreamCodec<FriendlyByteBuf, PairingPacket> CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, PairingPacket::request,
            ByteBufCodecs.BYTE, PairingPacket::stage,
            ByteBufCodecs.stringUtf8(16384), PairingPacket::data,
            PairingPacket::new);

    @Override public Type<PairingPacket> type() { return TYPE; }

    @Override public String toString() { return "PairingPacket[stage=" + stage + "]"; }
}
