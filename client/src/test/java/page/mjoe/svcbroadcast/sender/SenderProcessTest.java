package page.mjoe.svcbroadcast.sender;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SenderProcessTest {
    @TempDir Path temporary;

    @Test
    void preservesArgumentsAndUsesPipeEofToStop() throws Exception {
        Path launcher = launcher(List.of(java(), testPeer().toString(), "path with spaces & symbols"));
        var events = new LinkedBlockingQueue<String>();
        var exit = new CompletableFuture<String>();
        var process = new SenderProcess(launcher, List.of("start", "--profile=my-home", "--app=42:123"),
                event -> events.add(event.get("event").getAsString()), exit::complete);
        process.launch(true);
        try {
            assertEquals("capture_starting", events.poll(15, TimeUnit.SECONDS));
            assertEquals("capturing", events.poll(5, TimeUnit.SECONDS));
            assertFalse(exit.isDone());
            process.stop();
            assertEquals("stopped", events.poll(5, TimeUnit.SECONDS));
            assertNull(exit.get(5, TimeUnit.SECONDS));
        } finally {
            process.stop();
        }
    }

    @Test
    void invalidPathFailsWithoutLaunchingAProgram() throws Exception {
        Path launcher = launcher(List.of("relative/path"));
        assertThrows(IOException.class, () -> SenderProcess.command(launcher));
        var exit = new CompletableFuture<String>();
        new GameSender(launcher, () -> fail("The explicit launcher must override the bundled helper"))
                .query("apps", event -> fail("Unexpected sender event"), exit::complete)
                .launch(false);
        assertEquals("launcher_config", exit.get(5, TimeUnit.SECONDS));
    }

    @Test
    void pairingInputUsesOnlyStdinAndClosesAtTheEndOfTheOffer() throws Exception {
        var script = Files.writeString(temporary.resolve("PairPeer.java"), """
                public class PairPeer {
                    public static void main(String[] args) throws Exception {
                        if (args.length != 4 || !args[1].equals("pair")) System.exit(2);
                        var input = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
                        if (!"svcid1:public".equals(input.readLine()) || !"svcb1:private".equals(input.readLine())
                                || input.readLine() != null) System.exit(3);
                        System.out.println("{\\"event\\":\\"paired\\"}");
                    }
                }
                """);
        Path launcher = launcher(List.of(java(), script.toString()));
        var reply = new CompletableFuture<String>();
        var exit = new CompletableFuture<String>();
        new SenderProcess(launcher, List.of("pair"), "svcid1:public\nsvcb1:private\n",
                event -> reply.complete(event.get("event").getAsString()), exit::complete).launch(false);
        assertEquals("paired", reply.get(10, TimeUnit.SECONDS));
        assertNull(exit.get(5, TimeUnit.SECONDS));
    }

    private Path launcher(List<String> command) throws IOException {
        Path file = temporary.resolve("launcher.json");
        return Files.writeString(file, "{\"command\":" + new Gson().toJson(command) + "}");
    }

    private String java() {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private Path testPeer() throws IOException {
        return Files.writeString(temporary.resolve("Peer.java"), """
                public class Peer {
                    public static void main(String[] args) throws Exception {
                        if (args.length != 7 || !args[0].equals("path with spaces & symbols")
                                || !args[1].equals("mc-bridge") || !args[2].equals("start")
                                || !args[3].equals("--profile=my-home") || !args[4].equals("--app=42:123")
                                || !args[5].equals("--game-pid")
                                || Long.parseLong(args[6]) != ProcessHandle.current().parent().orElseThrow().pid()) {
                            System.exit(2);
                        }
                        System.out.println("{\\"event\\":\\"capture_starting\\"}");
                        System.out.println("{\\"event\\":\\"capturing\\"}");
                        if (System.in.read() == -1) System.out.println("{\\"event\\":\\"stopped\\"}");
                        else System.exit(3);
                    }
                }
                """);
    }
}
