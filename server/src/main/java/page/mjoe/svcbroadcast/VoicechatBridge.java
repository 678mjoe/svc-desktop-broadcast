package page.mjoe.svcbroadcast;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.VolumeCategory;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.LockSupport;

/** One exclusive broadcast; all native encoding and audio sends belong to its worker. */
final class VoicechatBridge implements AutoCloseable, AudioIngress.Host {
    static final String CATEGORY = "svc_broadcast";
    private static final Logger LOGGER = LoggerFactory.getLogger(VoicechatBridge.class);
    private static final long FRAME_NANOS = 20_000_000L;
    private volatile VoicechatServerApi api;
    record Listener(UUID playerId, VoicechatConnection connection) {}
    private volatile List<Listener> listeners = List.of();
    private ExecutorService executor;
    private AudioIngress ingress;
    private Session active;
    private volatile String lastResult = "No broadcast has run.";

    synchronized void attach(VoicechatServerApi serverApi) {
        AudioIngress previousIngress=ingress;
        close();
        serverApi.registerVolumeCategory(createVolumeCategory(serverApi.volumeCategoryBuilder()));
        executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "svc-broadcast-audio");
            thread.setDaemon(true);
            return thread;
        });
        api = serverApi;
        lastResult = "No broadcast has run.";
        ingress = new AudioIngress(FMLPaths.CONFIGDIR.get().resolve("svc-broadcast"), 24456, this, true);
        AudioIngress nextIngress=ingress;
        if (previousIngress==null) nextIngress.start();
        else previousIngress.closeCompletion().whenComplete((done,error)->nextIngress.start());
        LOGGER.info("Desktop broadcast ready: independent category {}, mono 48 kHz, idle", CATEGORY);
    }

    static VolumeCategory createVolumeCategory(VolumeCategory.Builder builder) {
        return builder
                .setId(CATEGORY)
                // SVC 2.6.20 encodes the display name with writeUtf(name, 16).
                .setName("Desktop Music")
                .setDescription("Independent desktop music broadcast")
                .build();
    }

    /** Called only from the Minecraft server thread. Never inspect game state on the audio worker. */
    void refreshListeners(MinecraftServer server) {
        VoicechatServerApi current = api;
        if (current == null) {
            listeners = List.of();
            return;
        }
        Set<UUID> online=new java.util.HashSet<>();
        for (var player:server.getPlayerList().getPlayers()) online.add(player.getUUID());
        AudioIngress currentIngress=ingress;
        if (currentIngress!=null) currentIngress.presence(online);
        List<Listener> next = new ArrayList<>();
        for (var player : server.getPlayerList().getPlayers()) {
            if (!BroadcastMod.wantsBroadcast(player)) {
                continue;
            }
            VoicechatConnection connection = current.getConnectionOf(player.getUUID());
            if (connection != null && connection.isConnected() && !connection.isDisabled()) {
                next.add(new Listener(player.getUUID(),connection));
            }
        }
        listeners = next;
    }

    synchronized String startTest(int seconds) {
        if (api == null || executor == null) {
            return "SVC is not ready. Check the voice chat server log.";
        }
        if (active != null) {
            return "A broadcast is already running or stopping. Stop it before starting another.";
        }
        Session session = new Session(api, new TestTone(seconds), seconds);
        active = session;
        lastResult = "Test starting.";
        executor.execute(() -> run(session));
        return null;
    }

    @Override
    public synchronized boolean start(UUID sessionId, PcmStream stream, Runnable onStopped) {
        if (api == null || executor == null || active != null) {
            return false;
        }
        return start(sessionId,stream,onStopped,null);
    }

    @Override
    public synchronized boolean start(UUID sessionId,PcmStream stream,Runnable onStopped,BroadcastAuthorization.Principal principal) {
        if (api==null || executor==null || active!=null) return false;
        Session session = new Session(api, sessionId, stream, onStopped,principal==null ? null : principal.playerId());
        active = session;
        lastResult = "Live upload starting.";
        executor.execute(() -> run(session));
        return true;
    }

    @Override
    public synchronized void stop(UUID sessionId) {
        if (active != null && active.id.equals(sessionId)) {
            stopBroadcast();
        }
    }

    synchronized boolean rotateToken() {
        if (ingress == null) {
            return false;
        }
        ingress.rotateToken();
        return true;
    }

    synchronized boolean stopBroadcast() {
        if (active == null) {
            return false;
        }
        active.stopped = true;
        if (active.stream != null) {
            active.stream.stop();
        }
        LockSupport.unpark(active.worker);
        return true;
    }

    synchronized String status() {
        String state = api == null ? "unavailable" : active == null ? "idle"
                : active.stopped ? "stopping" : "playing";
        String audio = active != null && active.stream != null ? active.stream.summary() : lastResult;
        String upload = ingress == null ? "Upload unavailable." : ingress.status();
        return "Broadcast: " + state + "; eligible SVC listeners=" + listenerCount(active == null ? null : active.owner)
                + "; category=" + CATEGORY + ". " + audio + " " + upload;
    }

    synchronized AudioIngress.SetupReport setupReport(boolean onlineMode) {
        return ingress == null
                ? new AudioIngress.SetupReport("WAITING_FOR_SVC", "Check that Simple Voice Chat is installed and has started.")
                : ingress.setupReport(onlineMode);
    }

    private void run(Session session) {
        session.worker = Thread.currentThread();
        StaticAudioChannel channel = null;
        OpusEncoder encoder = null;
        int frames = 0;
        String result = "stopped";
        try {
            if (session.stopped) {
                return;
            }
            channel = session.api.createStaticAudioChannel(session.id);
            if (channel == null) {
                throw new IllegalStateException("SVC could not create a static audio channel");
            }
            channel.setCategory(CATEGORY);
            channel.setBypassGroupIsolation(true);
            encoder = session.api.createEncoder(OpusEncoderMode.AUDIO);
            List<Listener> targets = null;
            long started = System.nanoTime();
            LOGGER.info("Broadcast {} started: {}, {} eligible listeners", session.id,
                    session.tone == null ? "live upload" : "test " + session.seconds + " seconds", listenerCount(session.owner));
            while (!session.stopped && !Thread.currentThread().isInterrupted()) {
                long now = System.nanoTime();
                long frameIndex = (now - started) / FRAME_NANOS;
                short[] frame;
                if (session.tone != null) {
                    session.tone.skipToFrame((int) Math.min(Integer.MAX_VALUE, frameIndex));
                    frame = session.tone.nextFrame();
                } else {
                    frame = session.stream.poll(now);
                }
                if (frame == null) {
                    break;
                }
                List<Listener> snapshot = listeners;
                if (snapshot != targets) {
                    updateTargets(channel, snapshot, session.owner);
                    targets = snapshot;
                }
                channel.send(encoder.encode(frame));
                frames++;
                long nextFrame = started + (frameIndex + 1) * FRAME_NANOS;
                while (!session.stopped && !Thread.currentThread().isInterrupted()) {
                    long remaining = nextFrame - System.nanoTime();
                    if (remaining <= 0) {
                        break;
                    }
                    LockSupport.parkNanos(remaining);
                }
            }
            result = session.stopped || Thread.currentThread().isInterrupted() ? "stopped" : "completed";
            if (session.stream != null) {
                String terminalReason = session.stream.terminalReason();
                if (terminalReason != null) {
                    result = terminalReason;
                }
            }
        } catch (Exception exception) {
            result = "failed (see server log)";
            LOGGER.error("Broadcast {} failed", session.id, exception);
        } finally {
            try {
                if (channel != null) {
                    channel.flush();
                    channel.clearTargets();
                }
            } catch (Exception exception) {
                LOGGER.warn("Could not flush the broadcast audio channel", exception);
            } finally {
                try {
                    if (encoder != null) {
                        encoder.close();
                    }
                } catch (Exception exception) {
                    result = "failed during encoder cleanup (see server log)";
                    LOGGER.error("Could not close the broadcast encoder", exception);
                } finally {
                    String metrics = session.stream == null ? "" : " " + session.stream.summary();
                    if (session.stream != null) {
                        session.stream.stop();
                    }
                    synchronized (this) {
                        if (active == session) {
                            active = null;
                            lastResult = "Last broadcast " + result + "; encoded frames=" + frames + "." + metrics;
                        }
                    }
                    LOGGER.info("Broadcast {} {}: {} encoded frames; worker finished{}", session.id, result, frames, metrics);
                    if (session.onStopped != null) {
                        try {
                            session.onStopped.run();
                        } catch (Exception exception) {
                            LOGGER.warn("Could not notify the upload connection of broadcast completion", exception);
                        }
                    }
                }
            }
        }
    }

    @Override
    public synchronized void close() {
        stopBroadcast();
        if (ingress != null) {
            ingress.close();
            ingress = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        // shutdownNow may discard a queued session before its finally block can run.
        active = null;
        lastResult = "Voice chat server stopped.";
        VoicechatServerApi previous = api;
        api = null;
        listeners = List.of();
        if (previous != null) {
            try {
                previous.unregisterVolumeCategory(CATEGORY);
            } catch (Exception exception) {
                LOGGER.warn("Could not unregister broadcast category during SVC shutdown", exception);
            }
        }
    }

    static boolean eligible(UUID player,UUID owner) { return !player.equals(owner); }
    private long listenerCount(UUID owner) {
        return listeners.stream().filter(target -> eligible(target.playerId, owner)).count();
    }
    static void updateTargets(StaticAudioChannel channel, List<Listener> snapshot, UUID owner) {
        channel.clearTargets();
        for (Listener target : snapshot) {
            if (eligible(target.playerId, owner)) channel.addTarget(target.connection);
        }
    }
    synchronized <T> CompletableFuture<T> authorize(BroadcastAuthorization.Action<T> action) {
        return ingress==null ? CompletableFuture.failedFuture(new IllegalStateException("Unavailable")) : ingress.authorize(action);
    }
    synchronized CompletableFuture<String> pairing(UUID player, boolean issue, boolean onlineMode) {
        return ingress == null ? CompletableFuture.failedFuture(new IllegalStateException("Unavailable"))
                : ingress.pairing(player, issue, onlineMode);
    }

    private static final class Session {
        private final VoicechatServerApi api;
        private final UUID id;
        private final TestTone tone;
        private final int seconds;
        private final PcmStream stream;
        private final Runnable onStopped;
        private final UUID owner;
        private volatile boolean stopped;
        private volatile Thread worker;

        private Session(VoicechatServerApi api, TestTone tone, int seconds) {
            this.api = api;
            this.id = UUID.randomUUID();
            this.tone = tone;
            this.seconds = seconds;
            this.stream = null;
            this.onStopped = null;
            this.owner=null;
        }

        private Session(VoicechatServerApi api, UUID id, PcmStream stream, Runnable onStopped,UUID owner) {
            this.api = api;
            this.id = id;
            this.tone = null;
            this.seconds = 0;
            this.stream = stream;
            this.onStopped = onStopped;
            this.owner=owner;
        }
    }
}
