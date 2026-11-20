package page.mjoe.svcbroadcast;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Clock;
import java.util.*;

/** Writers run on ingress control. Network/audio readers only see immutable snapshots. */
final class BroadcastAuthorization {
    @FunctionalInterface interface Action<T> { T apply(BroadcastAuthorization auth) throws Exception; }
    enum Logout { INHERIT, STOP, ALLOW }
    record Principal(UUID playerId, UUID deviceId) {}
    record Device(UUID id, String label, String digest) {}
    record Grant(UUID playerId, String name, long expiresAt, Logout logout, List<Device> devices) {}
    private record State(int version, boolean stopOnLogout, Map<UUID, Grant> grants) {}
    private record Pending(String digest, UUID player, long deadline, long grantExpiry) {}
    record Issued(Principal principal, String token, long expiresAt) {
        @Override public String toString() { return "Issued[redacted]"; }
    }
    static final class Denied extends IllegalArgumentException {
        Denied() { super("Broadcast authorization denied"); }
    }
    private static final Gson JSON = new Gson();
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path file;
    private final Clock clock;
    private final int maxDevices;
    private volatile State state;
    private volatile Set<UUID> online = Set.of();
    private final Map<UUID, Pending> pending = new HashMap<>();

    BroadcastAuthorization(Path directory, Clock clock, int maxDevices) throws IOException {
        this.clock = clock;
        this.maxDevices = maxDevices;
        if (maxDevices < 1 || maxDevices > 16) throw new IllegalArgumentException("Invalid device limit");
        PrivateFiles.directory(directory);
        file = directory.resolve("authorization.json");
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            try {
                var object=StrictJson.parse(new String(PrivateFiles.read(file, 1_048_576), StandardCharsets.UTF_8)).getAsJsonObject();
                if (!object.has("stopOnLogout") || !object.get("stopOnLogout").isJsonPrimitive() || !object.get("stopOnLogout").getAsJsonPrimitive().isBoolean()) throw new Denied();
                if (!object.keySet().equals(Set.of("version","stopOnLogout","grants")) || !object.get("grants").isJsonObject()
                        || !object.get("version").getAsJsonPrimitive().isNumber() || !object.get("version").getAsString().equals("1")) throw new Denied();
                for (var entry:object.getAsJsonObject("grants").entrySet()) {
                    var g=entry.getValue().getAsJsonObject();
                    if (!g.keySet().equals(Set.of("playerId","name","expiresAt","logout","devices"))
                            || !g.get("expiresAt").getAsJsonPrimitive().isNumber()
                            || !g.get("expiresAt").getAsString().matches("[0-9]{1,19}")
                            || !g.get("name").getAsJsonPrimitive().isString() || !g.get("devices").isJsonArray()) throw new Denied();
                    for (var item:g.getAsJsonArray("devices")) {
                        var d=item.getAsJsonObject();
                        if (!d.keySet().equals(Set.of("id","label","digest")) || !d.get("label").getAsJsonPrimitive().isString()
                                || !d.get("digest").getAsJsonPrimitive().isString()) throw new Denied();
                    }
                }
                State loaded = JSON.fromJson(object, State.class);
                validate(loaded);
                state = freeze(loaded);
            } catch (Exception error) { throw new IOException("Invalid authorization store"); }
        } else commit(new State(1, true, Map.of()));
    }
    private static State freeze(State value) {
        Map<UUID, Grant> copy = new HashMap<>();
        value.grants.forEach((id,g) -> copy.put(id, new Grant(g.playerId,g.name,g.expiresAt,g.logout,List.copyOf(g.devices))));
        return new State(1, value.stopOnLogout, Map.copyOf(copy));
    }
    private void validate(State value) {
        if (value == null || value.version != 1 || value.grants == null || value.grants.size() > 1024) throw new Denied();
        Set<String> hashes = new HashSet<>();
        for (var entry : value.grants.entrySet()) {
            Grant g = entry.getValue();
            if (g == null || !entry.getKey().equals(g.playerId) || g.name == null || g.name.length()>64
                    || g.expiresAt < 0 || g.logout == null || g.devices == null || g.devices.size()>16) throw new Denied();
            Set<UUID> ids = new HashSet<>();
            for (Device d : g.devices)
                if (d == null || d.id == null || !ids.add(d.id) || d.label == null || d.label.length()>64
                        || d.digest == null || !d.digest.matches("[0-9a-f]{64}") || !hashes.add(d.digest)) throw new Denied();
        }
    }
    private void commit(State next) throws IOException {
        validate(next);
        State frozen = freeze(next);
        PrivateFiles.write(file, JSON.toJson(frozen).getBytes(StandardCharsets.UTF_8));
        state = frozen;
    }
    private void put(Grant grant) throws IOException {
        Map<UUID,Grant> next = new HashMap<>(state.grants);
        next.put(grant.playerId, grant);
        commit(new State(1,state.stopOnLogout,next));
    }
    synchronized void grant(UUID id, String name, long expiresAt) throws IOException {
        if (expiresAt != 0 && expiresAt <= clock.millis()) throw new Denied();
        Grant old = state.grants.get(id);
        put(new Grant(id,name,expiresAt,old == null ? Logout.INHERIT : old.logout,old == null ? List.of() : old.devices));
        pending.remove(id);
    }
    synchronized void revoke(UUID id) throws IOException {
        Map<UUID,Grant> next = new HashMap<>(state.grants);
        next.remove(id);
        commit(new State(1,state.stopOnLogout,next));
        pending.remove(id);
    }
    synchronized void revokeDevice(UUID player, UUID device) throws IOException {
        Grant g = require(player);
        put(new Grant(player,g.name,g.expiresAt,g.logout,g.devices.stream().filter(d->!d.id.equals(device)).toList()));
    }
    synchronized void logoutPolicy(UUID id, Logout policy) throws IOException {
        Grant g = state.grants.get(id);
        if (g == null) throw new Denied();
        put(new Grant(id,g.name,g.expiresAt,policy,g.devices));
    }
    synchronized void defaultLogout(boolean stop) throws IOException { commit(new State(1,stop,state.grants)); }
    List<Device> devices(UUID id) { Grant g=state.grants.get(id); return g==null ? List.of() : g.devices; }
    String playerName(UUID id) { return state.grants.get(id).name; }
    Set<UUID> players() { return state.grants.keySet(); }
    void presence(Set<UUID> ids) { online=Set.copyOf(ids); }
    private Grant require(UUID id) {
        Grant g=state.grants.get(id);
        if (g == null || g.expiresAt != 0 && g.expiresAt <= clock.millis()) throw new Denied();
        return g;
    }
    void checkGrant(UUID player) { require(player); }
    synchronized String pairingCode(UUID player) {
        Grant g=require(player);
        pending.values().removeIf(p->p.deadline <= clock.millis());
        if (g.devices.size() >= maxDevices || !pending.containsKey(player) && pending.size()>=64) throw new Denied();
        String code=random();
        pending.put(player,new Pending(digest(code),player,clock.millis()+300_000,g.expiresAt));
        return code;
    }
    synchronized Issued redeem(String code, String label) throws IOException {
        if (code == null || !code.matches("[0-9a-f]{64}") || label == null || label.isBlank() || label.length()>64
                || label.chars().anyMatch(Character::isISOControl)) throw new Denied();
        String hash=digest(code);
        Pending found=null;
        for (Pending p : pending.values()) if (MessageDigest.isEqual(hash.getBytes(StandardCharsets.US_ASCII),p.digest.getBytes(StandardCharsets.US_ASCII))) found=p;
        if (found==null || found.deadline<=clock.millis()) throw new Denied();
        Grant g=require(found.player);
        if (g.expiresAt!=found.grantExpiry || g.devices.size()>=maxDevices) throw new Denied();
        UUID id=UUID.randomUUID();
        String token=random();
        List<Device> devices=new ArrayList<>(g.devices);
        devices.add(new Device(id,label,digest(token)));
        put(new Grant(g.playerId,g.name,g.expiresAt,g.logout,devices));
        pending.remove(g.playerId); // Only consume after durable credential commit.
        return new Issued(new Principal(g.playerId,id),token,g.expiresAt);
    }
    Principal authenticate(String bearer) {
        if (bearer==null || !bearer.matches("Bearer [0-9a-f]{64}")) return null;
        String hash=digest(bearer.substring(7));
        for (Grant g : state.grants.values()) for (Device d : g.devices)
            if (MessageDigest.isEqual(hash.getBytes(StandardCharsets.US_ASCII),d.digest.getBytes(StandardCharsets.US_ASCII))) {
                Principal p=new Principal(g.playerId,d.id);
                return permitted(p) ? p : null;
            }
        return null;
    }
    boolean permitted(Principal p) {
        if (p == null) return false;
        State current=state;
        Grant g=current.grants.get(p.playerId);
        if (g==null || g.expiresAt!=0 && g.expiresAt<=clock.millis()
                || g.devices.stream().noneMatch(d->d.id.equals(p.deviceId))) return false;
        boolean stop=g.logout==Logout.INHERIT ? current.stopOnLogout : g.logout==Logout.STOP;
        return !stop || online.contains(p.playerId);
    }
    private static String random() { byte[] b=new byte[32]; RANDOM.nextBytes(b); return HexFormat.of().formatHex(b); }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
