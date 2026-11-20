package page.mjoe.svcbroadcast;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Cross-language local TLS integration only. No Minecraft/audio devices/listeners. */
public final class InteropHarness {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath();
        Path directory=Files.createTempDirectory(root.resolve("server/build"),"interop-");
        var scheduler=Executors.newSingleThreadScheduledExecutor();
        AtomicInteger frames=new AtomicInteger();
        AtomicReference<BroadcastAuthorization.Principal> owner=new AtomicReference<>();
        class Host implements AudioIngress.Host {
            PcmStream current;
            Runnable finish;
            AtomicBoolean released=new AtomicBoolean(true);
            public boolean start(UUID id,PcmStream stream,Runnable stopped){return false;}
            public synchronized boolean start(UUID id,PcmStream stream,Runnable stopped,BroadcastAuthorization.Principal p) {
                current=stream;finish=stopped;owner.set(p);released.set(false);
                return true;
            }
            public synchronized void stop(UUID id) {if(current!=null)current.stop();if(!released.getAndSet(true))finish.run();}
            synchronized void tick() {
                if(current==null || released.get())return;
                if(current.poll(System.nanoTime())==null) {released.set(true);finish.run();}
                else frames.incrementAndGet();
            }
        }
        Host host=new Host();
        try (var service=new AudioIngress(directory,0,host,System::nanoTime,new TlsSettings("127.0.0.1",0,"127.0.0.1",0,3,true),Clock.systemUTC())) {
            service.start();service.startCompletion().get(15,TimeUnit.SECONDS);
            UUID player=UUID.randomUUID();
            service.authorize(auth->{auth.grant(player,"Interop",0);auth.logoutPolicy(player,BroadcastAuthorization.Logout.ALLOW);return null;}).get();
            Path identity = directory.resolve("identity.txt");
            PrivateFiles.write(identity, service.pairing(player, false, true).get().getBytes(StandardCharsets.UTF_8));
            UUID automaticPlayer = UUID.nameUUIDFromBytes("OfflinePlayer:InteropOffline".getBytes(StandardCharsets.UTF_8));
            service.authorize(auth -> {
                auth.grant(automaticPlayer, "Automatic interop", 0);
                auth.logoutPolicy(automaticPlayer, BroadcastAuthorization.Logout.ALLOW);
                return null;
            }).get();
            Path automatic = directory.resolve("automatic.txt");
            PrivateFiles.write(automatic, service.pairing(automaticPlayer, true, false).get().getBytes(StandardCharsets.UTF_8));
            String pair=service.pairing(player, true, true).get();
            Path pairing=directory.resolve("pair.txt");PrivateFiles.write(pairing,pair.getBytes(StandardCharsets.UTF_8));
            scheduler.scheduleAtFixedRate(host::tick,0,20,TimeUnit.MILLISECONDS);
            Process child=new ProcessBuilder(root.resolve("sender/.venv/bin/python").toString(),
                    root.resolve("sender/tests/interop_check.py").toString(),pairing.toString(),
                    directory.resolve("profiles").toString(), identity.toString(), player.toString(),
                    automatic.toString(), automaticPlayer.toString())
                    .directory(root.toFile()).inheritIO().start();
            if(!child.waitFor(30,TimeUnit.SECONDS)){child.destroyForcibly();throw new AssertionError("Interop timed out");}
            if(child.exitValue()!=0 || frames.get()==0 || owner.get()==null || !automaticPlayer.equals(owner.get().playerId()))
                throw new AssertionError("Cross-language TLS/identity upload failed");
            if (!service.authorize(auth -> auth.devices(player).size() == 1
                    && auth.devices(automaticPlayer).size() == 1).get())
                throw new AssertionError("Each pairing path should allocate one device, and lookup none");
            System.out.println("INTEROP PASS: trusted pairing, rejection cases, PCM upload and trusted owner; frames="+frames.get());
        } finally {scheduler.shutdownNow();}
    }
}
