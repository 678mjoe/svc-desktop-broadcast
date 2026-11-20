package page.mjoe.svcbroadcast;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.*;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class DeviceIngressTest {
    @TempDir Path dir;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void offlinePairingRespectsHostPolicyAndUsesTheLoggedInUuid(boolean allowed) throws Exception {
        var settings = new TlsSettings("127.0.0.1", 0, "127.0.0.1", 0, 1, allowed);
        try (var service = new AudioIngress(dir, 0, new Host(), System::nanoTime, settings, Clock.systemUTC())) {
            service.start();
            service.startCompletion().get(10, TimeUnit.SECONDS);
            UUID onlinePlayer = UUID.randomUUID();
            UUID offlinePlayer = UUID.nameUUIDFromBytes("OfflinePlayer:Player".getBytes(StandardCharsets.UTF_8));
            service.authorize(auth -> {
                auth.grant(onlinePlayer, "Player", 0);
                return null;
            }).get();
            assertThrows(ExecutionException.class, () -> service.pairing(offlinePlayer, false, false).get());
            service.authorize(auth -> {
                auth.grant(offlinePlayer, "Player", 0);
                return null;
            }).get();

            assertEquals(allowed ? "LOCAL_ONLY" : "ONLINE_MODE_REQUIRED", service.setupReport(false).code());
            assertTrue(service.pairing(onlinePlayer, false, true).get().startsWith("svcid1:"));
            if (!allowed) {
                for (boolean issue : new boolean[]{false, true}) {
                    var denied = assertThrows(ExecutionException.class, () -> service.pairing(offlinePlayer, issue, false).get());
                    assertInstanceOf(AudioIngress.OfflinePairingDisabled.class, denied.getCause());
                }
                return;
            }

            assertTrue(service.pairing(offlinePlayer, false, false).get().startsWith("svcid1:"));
            String offer = service.pairing(offlinePlayer, true, false).get();
            assertTrue(offer.startsWith("svcb1:"));
            String payload = new String(Base64.getUrlDecoder().decode(offer.substring(6)), StandardCharsets.UTF_8);
            String code = JsonParser.parseString(payload).getAsJsonObject().get("code").getAsString();
            String body = new Gson().toJson(Map.of("server_id", service.serverId().toString(), "code", code, "label", "Device"));
            var request = HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + service.tlsPort() + "/v1/pair"))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var client = HttpClient.newBuilder().sslContext(trust(service.caPem())).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            var credential = JsonParser.parseString(response.body()).getAsJsonObject();
            assertEquals(offlinePlayer.toString(), credential.get("player_id").getAsString());
            assertTrue(service.authorize(auth -> auth.devices(onlinePlayer).isEmpty()).get());
        }
    }

    @Test void identityLookupDoesNotConsumeOrRotatePairingCodesAndWorksAtDeviceLimit() throws Exception {
        try (var service = new AudioIngress(dir, 0, new Host(), System::nanoTime,
                new TlsSettings("127.0.0.1", 0, "127.0.0.1", 0, 1, false), Clock.systemUTC())) {
            service.start();
            service.startCompletion().get(10, TimeUnit.SECONDS);
            UUID player = UUID.randomUUID();
            assertThrows(ExecutionException.class, () -> service.pairing(player, false, true).get());
            String code = service.authorize(auth -> {
                auth.grant(player, "Player", 0);
                return auth.pairingCode(player);
            }).get();
            String identity = service.pairing(player, false, true).get();
            assertTrue(identity.startsWith("svcid1:"));
            var decoded = new String(Base64.getUrlDecoder().decode(identity.substring(7)), StandardCharsets.UTF_8);
            assertEquals(Set.of("version", "server_id", "url", "ca_pem"),
                    JsonParser.parseString(decoded).getAsJsonObject().keySet());
            service.authorize(auth -> auth.redeem(code, "Device")).get();
            assertEquals(identity, service.pairing(player, false, true).get());
            assertThrows(ExecutionException.class, () -> service.pairing(player, true, true).get());
        }
    }

    @Test void optionalPairingPacketRoundTripsWithoutLoggingTheOffer() {
        var packet = new PairingPacket(UUID.randomUUID(), PairingPacket.OFFER, "svcb1:private");
        var wire = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            PairingPacket.CODEC.encode(wire, packet);
            assertEquals(packet, PairingPacket.CODEC.decode(wire));
            assertFalse(packet.toString().contains(packet.data()));
        } finally {
            wire.release();
        }
    }

    @Test void setupDistinguishesLocalOnlyFromAnInvalidConfigAndOccupiedUploadPort() throws Exception {
        var file = dir.resolve(TlsSettings.FILE_NAME);
        try (var occupied = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            Files.writeString(file, "public_address=https://invalid-secret.example\n");
            try (var service = new AudioIngress(dir, 0, new Host(), true)) {
                service.start();
                assertThrows(ExecutionException.class, () -> service.startCompletion().get(5, TimeUnit.SECONDS));
                assertEquals("CONFIG", service.setupReport(true).code());
                assertFalse(service.setupReport(true).display().contains("invalid-secret"));
            }

            Files.writeString(file, "port=" + occupied.getLocalPort() + "\n");
            try (var service = new AudioIngress(dir, 0, new Host(), true)) {
                service.start();
                assertThrows(ExecutionException.class, () -> service.startCompletion().get(5, TimeUnit.SECONDS));
                assertEquals("UPLOAD_BIND", service.setupReport(true).code());
                assertTrue(service.status().contains("TLS=unavailable"));
            }
        }

        try (var service = new AudioIngress(dir, 0, new Host(), System::nanoTime,
                new TlsSettings("127.0.0.1", 0, "127.0.0.1", 0, 3, false), Clock.systemUTC())) {
            service.start();
            service.startCompletion().get(5, TimeUnit.SECONDS);
            assertEquals("LOCAL_ONLY", service.setupReport(true).code());
            assertTrue(service.setupReport(true).display().contains("public_address"));
            assertTrue(service.status().contains("TLS=ready:" + service.tlsPort()));
        }
    }

    @Test void trustedTlsPairingDeviceAuthenticationAndLivePolicyRevocation() throws Exception {
        var host=new Host();
        try (var service=new AudioIngress(dir,0,host,System::nanoTime,new TlsSettings("127.0.0.1",0,"127.0.0.1",1,3,false),Clock.systemUTC())) {
            service.start(); service.startCompletion().get(10,TimeUnit.SECONDS);
            UUID player=UUID.randomUUID();
            service.authorize(auth->{auth.grant(player,"Player",0);return null;}).get();
            String code=service.authorize(auth->auth.pairingCode(player)).get();
            HttpClient client=HttpClient.newBuilder().sslContext(trust(service.caPem())).build();
            URI pair=new URI("https://127.0.0.1:"+service.tlsPort()+"/v1/pair");
            String json=new Gson().toJson(Map.of("server_id",service.serverId().toString(),"code",code,"label","Mac"));
            var request=HttpRequest.newBuilder(pair).POST(HttpRequest.BodyPublishers.ofString(json)).build();
            var reply=client.send(request,HttpResponse.BodyHandlers.ofString());
            assertEquals(200,reply.statusCode());
            String token=JsonParser.parseString(reply.body()).getAsJsonObject().get("token").getAsString();
            assertFalse(Files.readString(dir.resolve("authorization.json")).contains(token));
            assertEquals(403,client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode());
            URI audio=new URI("wss://127.0.0.1:"+service.tlsPort()+"/v1/device/audio");
            assertThrows(ExecutionException.class,()->client.newWebSocketBuilder().header("Authorization","Bearer "+token).buildAsync(audio,new WebSocket.Listener(){}).get());
            service.presence(Set.of(player));
            CountDownLatch stopped=new CountDownLatch(1);
            var socket=client.newWebSocketBuilder().header("Authorization","Bearer "+token).buildAsync(audio,new WebSocket.Listener(){
                public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last){if(data.toString().contains("stopped"))stopped.countDown();ws.request(1);return null;}
            }).get(5,TimeUnit.SECONDS);
            socket.sendText("{\"type\":\"start\",\"version\":1,\"sample_rate\":48000,\"channels\":1,\"encoding\":\"pcm_s16le\",\"frame_samples\":960}",true).get();
            assertTrue(host.started.await(2,TimeUnit.SECONDS));
            assertEquals(player,host.owner.get().playerId());
            var second=service.authorize(auth->auth.redeem(auth.pairingCode(player),"second device")).get();
            CountDownLatch busy=new CountDownLatch(1);
            var competing=client.newWebSocketBuilder().header("Authorization","Bearer "+second.token()).buildAsync(audio,new WebSocket.Listener(){
                public CompletionStage<?> onText(WebSocket ws,CharSequence text,boolean last){if(text.toString().contains("busy"))busy.countDown();ws.request(1);return null;}
            }).get(5,TimeUnit.SECONDS);
            competing.sendText("{\"type\":\"start\",\"version\":1,\"sample_rate\":48000,\"channels\":1,\"encoding\":\"pcm_s16le\",\"frame_samples\":960}",true).get();
            assertTrue(busy.await(1,TimeUnit.SECONDS));
            assertFalse(host.stopped.await(50,TimeUnit.MILLISECONDS)); // A second device cannot preempt.
            competing.abort();
            service.presence(Set.of());
            assertTrue(stopped.await(1,TimeUnit.SECONDS));
            assertTrue(host.stopped.await(1,TimeUnit.SECONDS));
            socket.abort();
        }
    }
    @Test void unknownRootsWrongAddressAndLegacyPathFailClosed() throws Exception {
        var host=new Host();
        try (var service=new AudioIngress(dir,0,host,System::nanoTime,new TlsSettings("127.0.0.1",0,"127.0.0.1",1,3,false),Clock.systemUTC())) {
            service.start(); service.startCompletion().get(10,TimeUnit.SECONDS);
            URI address=URI.create("https://127.0.0.1:"+service.tlsPort()+"/v1/audio");
            assertThrows(Exception.class,()->HttpClient.newHttpClient().send(HttpRequest.newBuilder(address).build(),HttpResponse.BodyHandlers.discarding()));
            var client=HttpClient.newBuilder().sslContext(trust(service.caPem())).build();
            assertEquals(404,client.send(HttpRequest.newBuilder(address).build(),HttpResponse.BodyHandlers.discarding()).statusCode());
            URI wrong=URI.create("https://localhost:"+service.tlsPort()+"/v1/pair");
            assertThrows(Exception.class,()->client.send(HttpRequest.newBuilder(wrong).build(),HttpResponse.BodyHandlers.discarding()));
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"logout","device","grant","expiry","policy"})
    void activeAuthorizationChangesStopWithinOneSecond(String change) throws Exception {
        AtomicLong millis=new AtomicLong(System.currentTimeMillis());
        Clock wall=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(millis.get());}};
        Host host=new Host();
        try (var service=new AudioIngress(dir,0,host,System::nanoTime,new TlsSettings("127.0.0.1",0,"127.0.0.1",1,3,false),wall)) {
            service.start();service.startCompletion().get(10,TimeUnit.SECONDS);
            UUID player=UUID.randomUUID();service.presence(Set.of(player));
            var issued=service.authorize(auth->{auth.grant(player,"Player",millis.get()+60000);return auth.redeem(auth.pairingCode(player),"test");}).get();
            var client=HttpClient.newBuilder().sslContext(trust(service.caPem())).build();
            CountDownLatch closed=new CountDownLatch(1);
            WebSocket socket=client.newWebSocketBuilder().header("Authorization","Bearer "+issued.token()).buildAsync(
                URI.create("wss://127.0.0.1:"+service.tlsPort()+"/v1/device/audio"),new WebSocket.Listener(){
                    public CompletionStage<?> onText(WebSocket ws,CharSequence text,boolean last){if(text.toString().contains("stopped"))closed.countDown();ws.request(1);return null;}
                }).get(5,TimeUnit.SECONDS);
            socket.sendText("{\"type\":\"start\",\"version\":1,\"sample_rate\":48000,\"channels\":1,\"encoding\":\"pcm_s16le\",\"frame_samples\":960}",true).get();
            assertTrue(host.started.await(1,TimeUnit.SECONDS));
            // Legacy maintenance-token rotation cannot revoke independent device credentials.
            service.rotateToken();service.authorize(auth->null).get();
            assertFalse(host.stopped.await(50,TimeUnit.MILLISECONDS));
            switch(change) {
                case "logout" -> service.presence(Set.of());
                case "device" -> service.authorize(auth->{auth.revokeDevice(player,issued.principal().deviceId());return null;}).get();
                case "grant" -> service.authorize(auth->{auth.revoke(player);return null;}).get();
                case "expiry" -> millis.addAndGet(61000);
                case "policy" -> {
                    service.authorize(auth->{auth.logoutPolicy(player,BroadcastAuthorization.Logout.ALLOW);return null;}).get();
                    service.presence(Set.of());
                    assertFalse(host.stopped.await(150,TimeUnit.MILLISECONDS));
                    service.authorize(auth->{auth.logoutPolicy(player,BroadcastAuthorization.Logout.STOP);return null;}).get();
                }
            }
            assertTrue(closed.await(1,TimeUnit.SECONDS),change);
            assertTrue(host.stopped.await(1,TimeUnit.SECONDS),change);
            socket.abort();
        }
    }
    @Test void expiredServiceCertificateIsNotAcceptedByTlsClient() throws Exception {
        Clock old=Clock.offset(Clock.systemUTC(),Duration.ofDays(-100));
        try (var service=new AudioIngress(dir,0,new Host(),System::nanoTime,new TlsSettings("127.0.0.1",0,"127.0.0.1",1,3,false),old)) {
            service.start();service.startCompletion().get(10,TimeUnit.SECONDS);
            var client=HttpClient.newBuilder().sslContext(trust(service.caPem())).build();
            assertThrows(Exception.class,()->client.send(HttpRequest.newBuilder(URI.create("https://127.0.0.1:"+service.tlsPort()+"/v1/pair")).build(),HttpResponse.BodyHandlers.discarding()));
        }
    }

    @Test void renewedCertificateAndRestartKeepExistingDeviceTrustAndCredential() throws Exception {
        var original=TlsIdentity.load(dir.resolve("tls"),"127.0.0.1",Clock.offset(Clock.systemUTC(),Duration.ofDays(-70)));
        var auth=new BroadcastAuthorization(dir,Clock.systemUTC(),3);
        UUID player=UUID.randomUUID();auth.grant(player,"Player",0);
        auth.logoutPolicy(player,BroadcastAuthorization.Logout.ALLOW);
        var issued=auth.redeem(auth.pairingCode(player),"existing device");
        var client=HttpClient.newBuilder().sslContext(trust(original.caPem())).build();
        for(int restart=0;restart<2;restart++) {
            Host host=new Host();
            var service=new AudioIngress(dir,0,host,System::nanoTime,new TlsSettings("127.0.0.1",0,"127.0.0.1",1,3,false),Clock.systemUTC());
            try {
                service.start();service.startCompletion().get(10,TimeUnit.SECONDS);
                WebSocket socket=client.newWebSocketBuilder().header("Authorization","Bearer "+issued.token()).buildAsync(
                    URI.create("wss://127.0.0.1:"+service.tlsPort()+"/v1/device/audio"),new WebSocket.Listener(){}).get(5,TimeUnit.SECONDS);
                socket.sendText("{\"type\":\"start\",\"version\":1,\"sample_rate\":48000,\"channels\":1,\"encoding\":\"pcm_s16le\",\"frame_samples\":960}",true).get();
                assertTrue(host.started.await(1,TimeUnit.SECONDS));
                assertEquals(player,host.owner.get().playerId());
                socket.abort();
            } finally {service.close();service.closeCompletion().get(5,TimeUnit.SECONDS);}
        }
        var updated=TlsIdentity.load(dir.resolve("tls"),"127.0.0.1",Clock.systemUTC());
        assertEquals(original.serverId,updated.serverId);
        assertNotEquals(original.leaf.getSerialNumber(),updated.leaf.getSerialNumber());
    }

    static SSLContext trust(String pem) throws Exception {
        var cert=CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
        KeyStore keys=KeyStore.getInstance(KeyStore.getDefaultType());keys.load(null,null);keys.setCertificateEntry("server",cert);
        var factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(keys);
        SSLContext context=SSLContext.getInstance("TLS");context.init(null,factory.getTrustManagers(),null);return context;
    }
    static class Host implements AudioIngress.Host {
        AtomicReference<BroadcastAuthorization.Principal> owner=new AtomicReference<>();
        CountDownLatch started=new CountDownLatch(1),stopped=new CountDownLatch(1);
        Runnable release;
        public boolean start(UUID id,PcmStream stream,Runnable done){return false;}
        public boolean start(UUID id,PcmStream stream,Runnable done,BroadcastAuthorization.Principal p){owner.set(p);release=done;started.countDown();return true;}
        public void stop(UUID id){stopped.countDown();if(release!=null)release.run();}
    }
}
