package page.mjoe.svcbroadcast.sender;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.Connection;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.ClientCommandHandler;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.bus.api.IEventBus;
import org.lwjgl.glfw.GLFW;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Mod(value = "svc_broadcast_sender", dist = Dist.CLIENT)
public final class SenderClient {
    private static final int PAGE_SIZE = 8;
    private final GameSender sender = new GameSender(FMLPaths.CONFIGDIR.get().resolve("svc-broadcast-sender.json"),
            () -> Minecraft.getInstance().execute(() -> say("首次使用，正在准备本机采集助手，请稍候…")));
    // Command handling and all process notifications below run on the client thread.
    private List<App> apps = List.of();
    private List<App> selected = List.of();
    private List<String> profiles = List.of();
    private GameSender.Task query;
    private GameSender.Task session;
    private PairingAttempt pairing;
    private String profile;
    private String state = "未开播";
    private String fault;
    private boolean submittedInChat;

    public SenderClient(IEventBus modBus) {
        modBus.addListener(this::registerPairing);
        NeoForge.EVENT_BUS.addListener(this::register);
        NeoForge.EVENT_BUS.addListener(this::submitFromChat);
        NeoForge.EVENT_BUS.addListener(this::pairingTick);
        NeoForge.EVENT_BUS.addListener(this::shutdown);
    }

    private void submitFromChat(ScreenEvent.KeyPressed.Pre event) {
        // An incoming RUN_COMMAND click can also reach the client dispatcher.
        // Require a visible, local chat submission for capture and source changes.
        if (event.getKeyCode() != GLFW.GLFW_KEY_ENTER && event.getKeyCode() != GLFW.GLFW_KEY_KP_ENTER) return;
        if (!(event.getScreen() instanceof ChatScreen chat) || !(chat.getFocused() instanceof EditBox input)) return;
        var game = Minecraft.getInstance();
        if (game.player == null) return;
        String command = chat.normalizeChatMessage(input.getValue());
        if (!command.equals("/svcsender") && !command.startsWith("/svcsender ")) return;
        event.setCanceled(true);
        game.gui.getChat().addRecentChat(command);
        submittedInChat = true;
        try {
            if (!ClientCommandHandler.runCommand(command.substring(1))) say("用 /svcsender 查看可用命令。");
        } finally {
            submittedInChat = false;
        }
        if (game.screen == chat) game.setScreen(null);
    }

    private void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("svcsender")
                .executes(context -> say("/svcsender apps | select <应用序号…> | pair | profiles | start [配对档] | status | stop"))
                .then(Commands.literal("apps")
                        .executes(context -> query("apps"))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1, 16))
                                .executes(context -> showApps(IntegerArgumentType.getInteger(context, "page")))))
                .then(Commands.literal("select")
                        .then(Commands.argument("apps", StringArgumentType.greedyString())
                                .executes(context -> select(StringArgumentType.getString(context, "apps")))))
                .then(Commands.literal("profiles").executes(context -> query("profiles")))
                .then(Commands.literal("pair").executes(context -> beginPairing(false)))
                .then(Commands.literal("start")
                        .executes(context -> beginPairing(true))
                        .then(Commands.argument("profile", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(profiles, builder))
                                .executes(context -> start(StringArgumentType.getString(context, "profile")))))
                .then(Commands.literal("status").executes(context -> status()))
                .then(Commands.literal("stop").executes(context -> stop())));
    }

    private int query(String action) {
        if (!submittedInChat) return askForChatInput();
        if (query != null) return say("正在读取本机列表，请稍候。");
        if (action.equals("apps")) {
            if (pairing != null) return say("正在配对。用 /svcsender stop 取消。");
            if (session != null) return say("请先停止当前广播，再刷新音源。");
            apps = selected = List.of();
        } else {
            profiles = List.of();
        }
        boolean[] received = {false};
        query = sender.query(action, dispatchEvent(event -> {
            if (received[0]) return;
            received[0] = true;
            String kind = field(event, "event");
            if ("error".equals(kind)) {
                say(errorMessage(field(event, "code")));
            } else if (action.equals(kind)) {
                if (action.equals("apps")) {
                    apps = event.getAsJsonArray("items").asList().stream().limit(128).map(item -> {
                        JsonObject app = item.getAsJsonObject();
                        String id = field(app, "id");
                        if (!id.matches("[0-9]{1,10}:[0-9]{1,20}(:[0-9]{1,6})?")) {
                            throw new IllegalArgumentException("Invalid app identity.");
                        }
                        return new App(id, cleanLabel(field(app, "label")));
                    }).toList();
                    showApps(1);
                } else {
                    profiles = event.getAsJsonArray("items").asList().stream().limit(128)
                            .map(SenderProcess::string).filter(SenderClient::profileName).toList();
                    say(profiles.isEmpty() ? "没有本机配对档。用 /svcsender pair 配对当前服务器。"
                            : "本机配对档 · " + String.join("、", profiles));
                }
            } else {
                say(errorMessage("bridge_protocol"));
            }
        }), dispatch(error -> {
            query = null;
            if (!received[0]) say(errorMessage(error == null ? "bridge_protocol" : error));
        }));
        query.launch(false);
        return say("正在读取本机" + (action.equals("apps") ? "音频应用…" : "配对档…"));
    }

    private int showApps(int page) {
        if (apps.isEmpty()) return say("没有可选音源。请先播放音乐，再运行 /svcsender apps。");
        int pages = (apps.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        if (page > pages) return say("音源列表共有 " + pages + " 页。");
        for (int index = (page - 1) * PAGE_SIZE; index < Math.min(apps.size(), page * PAGE_SIZE); index++) {
            say((index + 1) + "  " + apps.get(index).label());
        }
        return say("用 /svcsender select <序号> 选择 1–4 个应用。"
                + (pages > 1 ? " 翻页用 /svcsender apps <页码>（" + page + "/" + pages + "）。" : ""));
    }

    private int select(String input) {
        if (!submittedInChat) return askForChatInput();
        if (pairing != null) return say("正在配对，请等候或用 /svcsender stop 取消。");
        if (session != null) return say("请先停止当前广播，再更换音源。");
        if (apps.isEmpty()) return say("请先运行 /svcsender apps。");
        String[] indices = input.strip().split("\\s+");
        var choices = new LinkedHashSet<App>();
        if (indices.length <= 4) {
            for (String index : indices) {
                if (!index.matches("[1-9][0-9]{0,2}") || Integer.parseInt(index) > apps.size()
                        || !choices.add(apps.get(Integer.parseInt(index) - 1))) {
                    return say("请选择列表中 1–4 个不同的应用序号。");
                }
            }
        }
        if (choices.isEmpty()) return say("请选择列表中 1–4 个不同的应用序号。");
        selected = List.copyOf(choices);
        return say("已选 · " + labels() + "。用 /svcsender start 开播。");
    }

    private int start(String requestedProfile) {
        if (!submittedInChat) return askForChatInput();
        if (pairing != null) return say("正在配对，请等候或用 /svcsender stop 取消。");
        if (session != null) return say("已有广播正在连接、采集或停止。用 /svcsender status 查看。");
        if (selected.isEmpty()) return say("请先用 /svcsender select 选择音源。");
        if (!profileName(requestedProfile)) return say("配对档名需为 1–32 个英文字母、数字、下划线或连字符。");
        return launchBroadcast(requestedProfile);
    }

    private int launchBroadcast(String requestedProfile) {
        profile = requestedProfile;
        fault = null;
        state = "正在连接";
        List<String> selectors = selected.stream().map(App::id).toList();
        session = sender.broadcast(profile, selectors, dispatchEvent(this::broadcastEvent), dispatch(error -> {
            session = null;
            if (fault == null && error != null) fault = error;
            state = "未开播";
            say(fault == null ? "广播已停止。" : errorMessage(fault));
        }));
        session.launch(true);
        return status();
    }

    private void broadcastEvent(JsonObject event) {
        String kind = field(event, "event");
        if ("error".equals(kind)) {
            if (!state.equals("正在停止")) fault = field(event, "code");
        } else if (!state.equals("正在停止")) {
            switch (kind) {
                case "capture_starting" -> {
                    state = "已连接，正在打开采集";
                    status();
                }
                case "capturing" -> {
                    state = "采集中，上传已启动";
                    status();
                }
                case "stopped" -> state = "正在停止";
                default -> fault = "bridge_protocol";
            }
        }
    }

    private int stop() {
        if (!submittedInChat) return askForChatInput();
        if (pairing != null) {
            cancelPairing();
            return say("已取消这次操作。");
        }
        if (session == null) return say("广播已停止。");
        if (!state.equals("正在停止")) {
            state = "正在停止";
            session.stop();
        }
        return status();
    }

    private int status() {
        if (pairing != null) return say("正在为当前服务器确认广播配对。");
        if (session != null) return say(state + " · 配对档 " + profile + " · " + labels());
        if (fault != null) say(errorMessage(fault));
        return say(selected.isEmpty() ? state : state + " · 已选 " + labels());
    }

    private void shutdown(GameShuttingDownEvent event) {
        cancelPairing();
        if (query != null) query.stop();
        if (session != null) session.stop();
    }

    private static <T> Consumer<T> dispatch(Consumer<T> callback) {
        return value -> Minecraft.getInstance().execute(() -> callback.accept(value));
    }

    private static Consumer<JsonObject> dispatchEvent(Consumer<JsonObject> events) {
        return dispatch(event -> {
            try {
                events.accept(event);
            } catch (RuntimeException exception) {
                say(errorMessage("bridge_protocol"));
            }
        });
    }

    private void registerPairing(RegisterPayloadHandlersEvent event) {
        event.registrar("1").optional().playBidirectional(PairingPacket.TYPE, PairingPacket.CODEC, this::receivePairing);
    }

    private int beginPairing(boolean startAfter) {
        if (!submittedInChat) return askForChatInput();
        if (session != null || pairing != null) return say("已有广播或配对操作。用 /svcsender status 查看。");
        if (startAfter && selected.isEmpty()) return say("请先用 /svcsender select 选择音源。");
        var game = Minecraft.getInstance();
        ClientPacketListener listener = game.getConnection();
        if (game.player == null || listener == null || listener.getServerData() == null
                || !NetworkRegistry.hasChannel(listener, PairingPacket.TYPE.id())) {
            return say("当前服务器未提供游戏内配对。已有档用 /svcsender start <配对档>，首次可用 /svcbroadcast pair。");
        }
        String address = listener.getServerData().ip.strip().toLowerCase(Locale.ROOT);
        UUID player = game.player.getUUID();
        String key = UUID.nameUUIDFromBytes((address + "\n" + player).getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");
        fault = null;
        pairing = new PairingAttempt(listener, player, "mc-" + key.substring(0, 24), startAfter);
        say("正在为当前服务器确认广播配对…");
        requestPairing(pairing, PairingPacket.LOOKUP, PairingPacket.IDENTITY);
        return 1;
    }

    private void requestPairing(PairingAttempt attempt, byte stage, byte expected) {
        attempt.expected = expected;
        attempt.deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            attempt.listener.send(new PairingPacket(attempt.request, stage, ""));
        } catch (RuntimeException exception) {
            cancelPairing();
            say(errorMessage("pairing_unavailable"));
        }
    }

    private void receivePairing(PairingPacket packet, IPayloadContext context) {
        PairingAttempt attempt = pairing;
        if (attempt == null || !onCurrentConnection(attempt) || attempt.expected == -1
                || System.nanoTime() - attempt.deadline >= 0
                || !attempt.request.equals(packet.request()) || context.connection() != attempt.connection) return;
        if (packet.stage() == PairingPacket.UNAVAILABLE) {
            cancelPairing();
            say(errorMessage("pairing_unavailable"));
            return;
        }
        if (packet.stage() != attempt.expected) return;
        boolean lookup = packet.stage() == PairingPacket.IDENTITY;
        String prefix = lookup ? "svcid1:" : "svcb1:";
        if (!packet.data().startsWith(prefix) || !packet.data().substring(prefix.length()).matches("[A-Za-z0-9_-]+")) {
            cancelPairing();
            say(errorMessage("pairing_identity"));
            return;
        }
        if (lookup) attempt.identity = packet.data();
        attempt.expected = -1;
        JsonObject[] result = {null};
        attempt.worker = sender.pair(attempt.profile, attempt.player, attempt.identity, lookup ? null : packet.data(),
                dispatchEvent(event -> result[0] = event), dispatch(error -> finishPairingStep(attempt, lookup, result[0], error)));
        attempt.worker.launch(false);
    }

    private void finishPairingStep(PairingAttempt attempt, boolean lookup, JsonObject result, String error) {
        if (pairing != attempt || !onCurrentConnection(attempt)) return;
        attempt.worker = null;
        if (result != null) {
            try {
                String event = field(result, "event");
                if (event.equals("pair_required") && lookup && error == null) {
                    requestPairing(attempt, PairingPacket.ISSUE, PairingPacket.OFFER);
                    return;
                }
                if (event.equals("paired") && error == null) {
                    String name = field(result, "name");
                    if (!profileName(name)) throw new IllegalArgumentException();
                    pairing = null;
                    say("配对已就绪 · " + name);
                    if (attempt.startAfter) launchBroadcast(name);
                    return;
                }
                if (event.equals("error")) error = field(result, "code");
            } catch (RuntimeException exception) {
                error = "bridge_protocol";
            }
        }
        cancelPairing();
        say(errorMessage(error == null ? "bridge_protocol" : error));
    }

    private static boolean onCurrentConnection(PairingAttempt attempt) {
        return Minecraft.getInstance().getConnection() == attempt.listener && attempt.connection.isConnected();
    }

    private void pairingTick(ClientTickEvent.Post event) {
        if (pairing == null) return;
        if (!onCurrentConnection(pairing)) {
            cancelPairing();
        } else if (pairing.worker == null && System.nanoTime() - pairing.deadline >= 0) {
            cancelPairing();
            say(errorMessage("pairing_timeout"));
        }
    }

    private void cancelPairing() {
        PairingAttempt attempt = pairing;
        pairing = null;
        if (attempt != null && attempt.worker != null) attempt.worker.stop();
    }

    private static final class PairingAttempt {
        final UUID request = UUID.randomUUID();
        final ClientPacketListener listener;
        final Connection connection;
        final UUID player;
        final String profile;
        final boolean startAfter;
        byte expected;
        long deadline;
        String identity;
        GameSender.Task worker;

        PairingAttempt(ClientPacketListener listener, UUID player, String profile, boolean startAfter) {
            this.listener = listener;
            this.connection = listener.getConnection();
            this.player = player;
            this.profile = profile;
            this.startAfter = startAfter;
        }
    }

    private static int say(String text) {
        Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[SVC 广播] " + text));
        return 1;
    }

    private static int askForChatInput() {
        return say("请在游戏聊天栏输入这条命令并按回车。");
    }

    private String labels() {
        return String.join("、", selected.stream().map(App::label).toList());
    }

    private static String field(JsonObject object, String name) {
        return SenderProcess.string(object.get(name));
    }

    private static boolean profileName(String name) {
        return name.matches("[A-Za-z0-9_-]{1,32}");
    }

    private static String cleanLabel(String label) {
        return label.replaceAll("[\\p{C}\\p{Zl}\\p{Zp}§]", "").strip()
                .codePoints().limit(100).collect(StringBuilder::new, StringBuilder::appendCodePoint,
                        StringBuilder::append).toString();
    }

    private static String errorMessage(String code) {
        if (!code.matches("[a-z0-9_]{1,48}")) code = "bridge_protocol";
        String message = switch (code) {
            case "launcher_config" ->
                    "无法启动发送器。请检查 config/svc-broadcast-sender.json 的路径和发送器版本。";
            case "launcher_unavailable", "unsupported_platform" ->
                    "此平台或 JAR 未提供内置采集助手。请使用对应平台的包或配置独立发送器。";
            case "runtime_install_failed" -> "无法验证或安装采集助手。请检查本机磁盘空间和用户目录写入权限。";
            case "launcher_failed" -> "无法运行本机发送器。请确认系统允许运行包内程序。";
            case "bridge_protocol" -> "客户端与发送器通信失败。请核对客户端和发送器版本。";
            case "authentication" -> "服务器拒绝鉴权。请检查广播资格、在线策略及所选配对档。";
            case "tls_verification" -> "服务器身份验证失败。请核对配对档与服务器配置。";
            case "connection", "handshake", "ready_timeout" -> "无法连接广播入口。请检查网络和服务器状态。";
            case "send_timeout" -> "上传超时，已结束广播。请检查网络后重新开播。";
            case "redirect_refused", "endpoint" -> "广播地址验证失败。请与服主核对配对档。";
            case "network_logging" -> "当前 Java 开启了详细网络日志。关闭该选项后才能发送设备凭证。";
            case "server_stopped" -> "服务器结束了广播。请检查资格、在线状态和广播占用。";
            case "app_selection", "coreaudio_selection", "wasapi_selection", "wasapi_app_changed" ->
                    "选中的应用已变化。请让播放器发声，再刷新 /svcsender apps 并重选。";
            case "coreaudio_apps", "coreaudio_unavailable", "coreaudio_start", "coreaudio_stopped",
                    "coreaudio_pipe", "coreaudio_start_timeout", "wasapi_apps", "wasapi_start",
                    "wasapi_stopped", "wasapi_start_timeout", "wasapi_frame_timeout", "startup_timeout",
                    "wasapi_timeout", "wasapi_clock", "wasapi_pipe", "coreaudio_timeout", "source_frame" ->
                    "未能持续采集。请检查播放器、系统音频权限和发送器助手。";
            case "profile_file", "profile_permissions", "profile_invalid" ->
                    "无法读取配对档。请用现有发送器检查配对档及本机权限。";
            case "profile_exists" -> "已有同名配对档。请确认现有记录后再重新配对。";
            case "pairing_unavailable", "pairing_rejected" ->
                    "服务器无法配对。请检查广播资格、有效期、设备上限和 WSS 配置。";
            case "pairing_timeout", "list_timeout", "pairing_connection" ->
                    "配对未完成。请检查游戏连接和广播入口后再试。";
            case "profile_identity_changed" ->
                    "当前广播身份和本机已保存的身份不一致。请向服主确认后重新配对。";
            case "pairing_player", "pairing_identity", "pairing_info" -> "无法验证本次游戏内配对信息。";
            default -> "发送器操作失败。可用独立发送器检查本机音源或连接。";
        };
        return message + "（" + code + "）";
    }

    private record App(String id, String label) {}
}
