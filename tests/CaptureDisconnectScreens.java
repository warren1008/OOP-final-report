import java.net.ServerSocket;
import java.nio.file.*;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;

/** Captures real Swing dialogs only after the real Socket scenario has produced its notice. */
public class CaptureDisconnectScreens {
    static final Path OUT = Paths.get("docs", "screenshots");
    static void await(BooleanSupplier test, long ms, String description) throws Exception {
        long end = System.nanoTime() + ms * 1_000_000;
        while (!test.getAsBoolean() && System.nanoTime() < end) Thread.sleep(20);
        if (!test.getAsBoolean()) throw new AssertionError(description);
    }
    static SnakeClient client(String name, int port) {
        return DisconnectTests.ui(() -> {
            SnakeClient c = new SnakeClient(name, "127.0.0.1", port);
            c.setVisible(true); return c;
        });
    }
    static void save(SnakeClient c, String stem) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try { c.capture(OUT.resolve(stem + ".png"), true); c.capture(OUT.resolve(stem + "-full.png"), false); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        System.out.println("CAPTURE " + stem + " " + DisconnectTests.ui(c::noticeCode));
    }
    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUT);
        int unused; try (ServerSocket reserve = new ServerSocket(0)) { unused = reserve.getLocalPort(); }
        SnakeClient failed = client("示範玩家", unused);
        try {
            await(() -> DisconnectTests.ui(() -> failed.noticeCode().equals("CONNECT_FAILED")), 6000, "connection failure");
            save(failed, "01-connection-failed");
        } finally { DisconnectTests.dispose(failed); }

        SnakeServer server = new SnakeServer(0);
        SnakeClient disconnected = client("示範玩家", server.port());
        try {
            await(() -> DisconnectTests.ui(() -> disconnected.state() != null), 3000, "real join");
            server.close();
            await(() -> DisconnectTests.ui(() -> disconnected.noticeCode().equals("SERVER_DISCONNECTED")), 3000, "server shutdown");
            save(disconnected, "02-server-disconnected");
        } finally { server.close(); DisconnectTests.dispose(disconnected); }

        // Freeze movement with a slower test Tick, while retaining real TCP, PING, cleanup and result logic.
        try (SnakeServer multiplayer = new SnakeServer(0, 10000, 60000)) {
            SnakeClient player = client("玩家甲", multiplayer.port());
            try (DisconnectTests.Peer b = new DisconnectTests.Peer(multiplayer.port(), "玩家乙", true);
                 DisconnectTests.Peer c = new DisconnectTests.Peer(multiplayer.port(), "玩家丙", true)) {
                await(() -> DisconnectTests.ui(() -> player.state() != null && player.state().snakes.size() == 3), 3000, "3 real clients");
                b.close();
                await(() -> DisconnectTests.ui(() -> player.noticeCode().equals("PLAYER_DISCONNECTED")), 3000, "multiplayer continues");
                if (DisconnectTests.ui(() -> player.state().finished)) throw new AssertionError("False winner");
                save(player, "03-player-left-continues");
                c.close();
                await(() -> DisconnectTests.ui(() -> player.noticeCode().equals("WIN")), 3000, "last survivor");
                if (!DisconnectTests.ui(() -> player.state().winnerId == player.playerId())) throw new AssertionError("Winner not from Server");
                save(player, "04-last-survivor");
            } finally { DisconnectTests.dispose(player); }
        }

        // The fixture accepts TCP and the object handshake, then deliberately sends no further replies.
        try (DisconnectTests.SilentServer silent = new DisconnectTests.SilentServer(true)) {
            SnakeClient timeout = client("示範玩家", silent.port());
            try {
                await(() -> DisconnectTests.ui(timeout::isConnected), 3000, "timeout fixture joined");
                long start = System.nanoTime();
                await(() -> DisconnectTests.ui(() -> timeout.noticeCode().equals("SERVER_TIMEOUT")), 11500, "production client timeout");
                long ms = (System.nanoTime() - start) / 1_000_000;
                if (ms < 9600 || ms > 11100) throw new AssertionError("Unexpected client timeout: " + ms);
                save(timeout, "05-server-timeout");
                System.out.println("MEASURED production Client timeout " + ms + " ms");
            } finally { DisconnectTests.dispose(timeout); }
        }
        System.out.println("CAPTURE RESULT 5 real GUI scenarios exported; originals contain no painted-in replacement messages.");
    }
}
