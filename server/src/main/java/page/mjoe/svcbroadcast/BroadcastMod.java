package page.mjoe.svcbroadcast;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.network.chat.ClickEvent;
import java.util.UUID;
import java.util.WeakHashMap;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

@Mod(value = BroadcastMod.ID, dist = Dist.DEDICATED_SERVER)
public final class BroadcastMod {
    public static final String ID = "svc_broadcast";
    private static final String OPT_OUT_KEY = ID + ".opt_out";
    static final VoicechatBridge BROADCAST = new VoicechatBridge();

    private final WeakHashMap<ServerPlayer, long[]> pairingLastRequest = new WeakHashMap<>();

    public BroadcastMod(IEventBus modBus) {
        modBus.addListener(this::registerPairing);
        NeoForge.EVENT_BUS.addListener(this::registerCommands);
        NeoForge.EVENT_BUS.addListener(this::serverTick);
        NeoForge.EVENT_BUS.addListener(this::serverStopping);
    }

    private void registerPairing(RegisterPayloadHandlersEvent event) {
        event.registrar("1").optional().playBidirectional(PairingPacket.TYPE, PairingPacket.CODEC, this::handlePairing);
    }

    private void handlePairing(PairingPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !packet.data().isEmpty()
                || (packet.stage() != PairingPacket.LOOKUP && packet.stage() != PairingPacket.ISSUE)) return;
        var server = player.getServer();
        if (server == null) {
            context.reply(new PairingPacket(packet.request(), PairingPacket.UNAVAILABLE, ""));
            return;
        }
        int stage = packet.stage();
        long now = System.nanoTime();
        long[] last = pairingLastRequest.computeIfAbsent(player, ignored -> new long[2]);
        if (last[stage] != 0 && now - last[stage] < 1_000_000_000L) return;
        last[stage] = now;
        boolean issue = stage == PairingPacket.ISSUE;
        var connection = context.connection();
        if (issue) BROADCAST.refreshListeners(server);
        BROADCAST.pairing(player.getUUID(), issue, server.usesAuthentication()).whenComplete((info, error) -> server.execute(() -> {
            if (connection.isConnected() && server.getPlayerList().getPlayer(player.getUUID()) == player) {
                context.reply(error == null ? new PairingPacket(packet.request(),
                        issue ? PairingPacket.OFFER : PairingPacket.IDENTITY, info)
                        : new PairingPacket(packet.request(), PairingPacket.UNAVAILABLE, ""));
            }
        }));
    }

    private void registerCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("svcbroadcast")
                .then(Commands.literal("pair").executes(context -> {
                    var source=context.getSource();
                    var player=source.getPlayerOrException();
                    var server = source.getServer();
                    BROADCAST.refreshListeners(server);
                    BROADCAST.pairing(player.getUUID(), true, server.usesAuthentication()).whenComplete((info,error)->server.execute(()-> {
                        if (error instanceof AudioIngress.OfflinePairingDisabled) source.sendFailure(Component.literal(error.getMessage()));
                        else if (error!=null) source.sendFailure(Component.literal("Pairing unavailable: check your grant, expiry and device limit."));
                        else source.sendSuccess(()->Component.literal("Click to copy private pairing information (valid 5 minutes). Paste into sender pair; do not share it.")
                            .withStyle(style->style.withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD,info))),false);
                    }));
                    return 1;
                }))
                .then(authCommands())
                .then(Commands.literal("setup")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> {
                            var source = context.getSource();
                            var report = BROADCAST.setupReport(source.getServer().usesAuthentication());
                            source.sendSuccess(() -> Component.literal(report.display()), false);
                            return report.ready() ? 1 : 0;
                        }))
                .then(Commands.literal("status").executes(context -> {
                    var source = context.getSource();
                    BROADCAST.refreshListeners(source.getServer());
                    source.sendSuccess(() -> Component.literal(BROADCAST.status()), false);
                    return 1;
                }))
                .then(Commands.literal("test")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> start(context.getSource(), 5))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, TestTone.MAX_SECONDS))
                                .executes(context -> start(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "seconds")))))
                .then(Commands.literal("stop")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> {
                            boolean stopped = BROADCAST.stopBroadcast();
                            context.getSource().sendSuccess(() -> Component.literal(stopped
                                    ? "Stopping the broadcast."
                                    : "No broadcast is running."), true);
                            return stopped ? 1 : 0;
                        }))
                .then(Commands.literal("token")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("rotate").executes(context -> {
                            boolean requested = BROADCAST.rotateToken();
                            if (requested) {
                                context.getSource().sendSuccess(() -> Component.literal(
                                        "Token rotation requested. Current uploads will be disconnected; read the new token from the server token file."), true);
                            } else {
                                context.getSource().sendFailure(Component.literal("Upload service is not available."));
                            }
                            return requested ? 1 : 0;
                        })))
                .then(Commands.literal("listen")
                        .then(Commands.literal("on").executes(context -> listen(context.getSource(), true)))
                        .then(Commands.literal("off").executes(context -> listen(context.getSource(), false)))));
    }

    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> authCommands() {
        var root=Commands.literal("auth").requires(source->source.hasPermission(2));
        for (String command:new String[]{"grant","expiry"}) {
            root.then(Commands.literal(command).then(Commands.argument("player",StringArgumentType.word())
                .then(Commands.literal("permanent").executes(ctx->grant(ctx.getSource(),StringArgumentType.getString(ctx,"player"),0)))
                .then(Commands.argument("days",IntegerArgumentType.integer(1,36500)).executes(ctx->grant(ctx.getSource(),
                    StringArgumentType.getString(ctx,"player"),Instant.now().plus(IntegerArgumentType.getInteger(ctx,"days"),ChronoUnit.DAYS).toEpochMilli())))));
        }
        root.then(Commands.literal("revoke").then(Commands.argument("player",StringArgumentType.word()).executes(ctx-> {
            var source=ctx.getSource(); String target=target(source,StringArgumentType.getString(ctx,"player"));
            return authorization(source,auth->{auth.revoke(resolve(auth,target));return "Broadcast qualification and its devices revoked.";});
        })));
        root.then(Commands.literal("devices").then(Commands.argument("player",StringArgumentType.word()).executes(ctx-> {
            var source=ctx.getSource(); String target=target(source,StringArgumentType.getString(ctx,"player"));
            return authorization(source,auth->auth.devices(resolve(auth,target)).stream()
                .map(d->d.id()+" "+new com.google.gson.Gson().toJson(d.label())).collect(java.util.stream.Collectors.joining("\n")));
        })));
        root.then(Commands.literal("revoke-device").then(Commands.argument("player",StringArgumentType.word())
            .then(Commands.argument("device",StringArgumentType.word()).executes(ctx-> {
                var source=ctx.getSource(); String target=target(source,StringArgumentType.getString(ctx,"player"));
                String device=StringArgumentType.getString(ctx,"device");
                return authorization(source,auth->{auth.revokeDevice(resolve(auth,target),UUID.fromString(device));return "Device authorization revoked.";});
            }))));
        var policy=Commands.argument("player",StringArgumentType.word());
        for (var value:BroadcastAuthorization.Logout.values()) {
            policy.then(Commands.literal(value.name().toLowerCase(java.util.Locale.ROOT)).executes(ctx-> {
                var source=ctx.getSource(); String target=target(source,StringArgumentType.getString(ctx,"player"));
                return authorization(source,auth->{auth.logoutPolicy(resolve(auth,target),value);return "Broadcaster logout policy updated.";});
            }));
        }
        root.then(Commands.literal("policy").then(policy));
        var defaults=Commands.literal("default-logout");
        for (boolean stop:new boolean[]{true,false}) defaults.then(Commands.literal(stop?"stop":"allow").executes(ctx->
            authorization(ctx.getSource(),auth->{auth.defaultLogout(stop);return "Default logout policy updated.";})));
        root.then(defaults);
        return root;
    }
    private static String target(CommandSourceStack source,String value) {
        var online=source.getServer().getPlayerList().getPlayerByName(value);
        return online==null ? value : online.getUUID().toString();
    }
    private static UUID resolve(BroadcastAuthorization auth,String value) {
        try {return UUID.fromString(value);} catch (IllegalArgumentException ignored) { }
        for (UUID id:auth.players()) if (auth.playerName(id).equalsIgnoreCase(value)) return id;
        throw new IllegalArgumentException("Unknown player: join once or use UUID");
    }
    private int grant(CommandSourceStack source,String name,long expiry) {
        String value=target(source,name);
        return authorization(source,auth->{auth.grant(resolve(auth,value),name,expiry);return "Broadcast qualification/expiry updated. Pair in game with /svcbroadcast pair.";});
    }
    private int authorization(CommandSourceStack source,BroadcastAuthorization.Action<String> action) {
        BROADCAST.refreshListeners(source.getServer());
        BROADCAST.authorize(action).whenComplete((text,error)->source.getServer().execute(()-> {
            if (error!=null) source.sendFailure(Component.literal("Authorization operation failed; check player/UUID, expiry and private store health."));
            else source.sendSuccess(()->Component.literal(text.isEmpty()?"No devices.":text),false);
        }));
        return 1;
    }

    private int start(CommandSourceStack source, int seconds) {
        BROADCAST.refreshListeners(source.getServer());
        String error = BROADCAST.startTest(seconds);
        if (error != null) {
            source.sendFailure(Component.literal(error));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Starting quiet 440 Hz test broadcast for " + seconds
                + " seconds. Stop: /svcbroadcast stop; opt out: /svcbroadcast listen off."), true);
        return 1;
    }

    private int listen(CommandSourceStack source, boolean enabled) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        CompoundTag persistent = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        persistent.putBoolean(OPT_OUT_KEY, !enabled);
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG, persistent);
        BROADCAST.refreshListeners(source.getServer());
        source.sendSuccess(() -> Component.literal(enabled
                ? "Broadcast listening enabled. Normal voice chat is unchanged."
                : "Broadcast listening disabled. Normal voice chat is unchanged."), false);
        return 1;
    }

    static boolean wantsBroadcast(ServerPlayer player) {
        return !player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG).getBoolean(OPT_OUT_KEY);
    }

    private void serverTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 5 == 0) {
            BROADCAST.refreshListeners(event.getServer());
        }
    }

    private void serverStopping(ServerStoppingEvent event) {
        BROADCAST.close();
    }
}
