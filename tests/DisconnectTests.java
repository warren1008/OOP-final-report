import javax.swing.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Real loopback Socket integration and live Swing tests, without external libraries. */
public class DisconnectTests {
    static int assertions, cases;
    interface Task { void run() throws Exception; }
    static void check(boolean ok, String message) { assertions++; if (!ok) throw new AssertionError(message); }
    static void waitFor(BooleanSupplier condition, long ms, String message) throws Exception {
        long end = System.nanoTime() + ms * 1_000_000;
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(20);
        check(condition.getAsBoolean(), message);
    }
    static <T> T ui(Supplier<T> query) {
        AtomicReference<T> result = new AtomicReference<>();
        try { SwingUtilities.invokeAndWait(() -> result.set(query.get())); }
        catch (Exception e) { throw new RuntimeException(e); }
        return result.get();
    }
    static SnakeClient client(String name, int port, ClientConnection.Timing timing) {
        return ui(() -> { SnakeClient c = new SnakeClient(name, "127.0.0.1", port, false, timing); c.setVisible(true); return c; });
    }
    static void dispose(SnakeClient client) { ui(() -> { client.dispose(); return null; }); }
    static JButton reconnectButton(java.awt.Container root) {
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof JButton && ((JButton) child).getText().equals("重新連線")) return (JButton) child;
            if (child instanceof java.awt.Container) { JButton found = reconnectButton((java.awt.Container) child); if (found != null) return found; }
        }
        return null;
    }
    static void scenario(String name, Task task) throws Exception {
        task.run(); cases++; System.out.println("PASS " + name);
    }

    static final class Peer implements AutoCloseable {
        final Socket socket;
        final BufferedWriter out;
        final ObjectInputStream in;
        final int id;
        final AtomicReference<GameState> latest = new AtomicReference<>();
        final AtomicBoolean ended = new AtomicBoolean();
        final ScheduledExecutorService ping = Executors.newSingleThreadScheduledExecutor();
        final List<Long> sequences = new CopyOnWriteArrayList<>();
        Peer(int port, String name, boolean heartbeat) throws Exception {
            socket = new Socket("127.0.0.1", port);
            out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            in = new ObjectInputStream(socket.getInputStream());
            send(name); id = (Integer) in.readObject();
            Thread reader = new Thread(() -> {
                try { while (!socket.isClosed()) { Object value = in.readObject(); if (value instanceof GameState) {
                    GameState state = (GameState) value;
                    sequences.add(state.stateSequence);
                    latest.updateAndGet(old -> old == null || old.stateSequence < state.stateSequence ? state : old);
                } } } catch (Exception ignored) { } finally { ended.set(true); }
            }, "test-peer-reader");
            reader.setDaemon(true); reader.start();
            if (heartbeat) ping.scheduleAtFixedRate(() -> { try { send("PING"); } catch (Exception ignored) { } }, 0, 100, TimeUnit.MILLISECONDS);
        }
        synchronized void send(String text) throws IOException { out.write(text); out.newLine(); out.flush(); }
        void abort() throws IOException { socket.setSoLinger(true, 0); close(); }
        @Override public void close() { ping.shutdownNow(); try { socket.close(); } catch (IOException ignored) { } }
    }

    static final class SilentServer implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0);
        volatile Socket socket;
        SilentServer(boolean handshake) throws IOException {
            Thread worker = new Thread(() -> {
                try {
                    socket = server.accept();
                    if (handshake) {
                        ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream()); out.flush();
                        new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
                        out.writeObject(1);
                        GameState state = new GameState(); state.stateSequence = 1;
                        SnakeData snake = new SnakeData(); snake.id = 1; snake.name = "測試玩家"; snake.color = java.awt.Color.GREEN;
                        snake.body.add(new java.awt.Point()); state.snakes.add(snake);
                        out.writeObject(state); out.flush();
                    }
                    while (socket.getInputStream().read() != -1) { /* Deliberately never reply. */ }
                } catch (IOException ignored) { }
            }, "silent-server-fixture");
            worker.setDaemon(true); worker.start();
        }
        int port() { return server.getLocalPort(); }
        @Override public void close() throws IOException { server.close(); if (socket != null) socket.close(); }
    }

    public static void main(String[] args) throws Exception {
        ClientConnection.Timing quick = new ClientConnection.Timing(800, 80, 900);
        scenario("single player does not win merely by joining", () -> {
            try (SnakeServer server = new SnakeServer(0, 1000, 60000); Peer solo = new Peer(server.port(), "單人", true)) {
                waitFor(() -> solo.latest.get() != null, 1500, "solo receives snapshot");
                check(!server.snapshot().finished, "no false winner before competition");
            }
        });
        scenario("3 players: one EOF leaves 2 alive; second disconnect creates server-authoritative winner", () -> {
            try (SnakeServer server = new SnakeServer(0, 2000, 60000);
                 Peer a = new Peer(server.port(), "甲", true); Peer b = new Peer(server.port(), "乙", true); Peer c = new Peer(server.port(), "丙", true)) {
                waitFor(() -> server.snapshot().snakes.size() == 3, 1500, "3 players joined");
                b.close();
                waitFor(() -> server.snapshot().events.size() == 1, 1500, "EOF cleans player");
                GameState after = server.snapshot();
                check(!after.finished, "3 to 2 must not award victory");
                check(after.snakes.stream().filter(s -> s.alive && s.connected).count() == 2, "2 survivors remain");
                c.abort();
                waitFor(() -> a.latest.get() != null && a.latest.get().finished, 1500, "winner arrives through Socket");
                check(a.latest.get().winnerId == a.id, "server chooses remaining player");
                check(a.latest.get().resultReason.equals("OPPONENT_DISCONNECTED"), "disconnect reason preserved");
                Thread.sleep(300);
                check(server.snapshot().events.stream().filter(e -> e.playerId == b.id).count() == 1, "EOF and writer failure not duplicated");
                check(server.snapshot().events.stream().filter(e -> e.playerId == c.id).count() == 1, "RST cleanup once");
            }
        });
        scenario("raw TCP half-close is treated as EOF", () -> {
            try (SnakeServer server = new SnakeServer(0, 2000, 60000); Peer a = new Peer(server.port(), "甲", true); Peer b = new Peer(server.port(), "乙", true)) {
                b.ping.shutdownNow(); b.socket.shutdownOutput();
                waitFor(() -> server.snapshot().finished, 1500, "half-close ends participation");
                check(server.snapshot().winnerId == a.id, "half-close winner is survivor");
            }
        });
        scenario("heartbeat timeout removes a client that still reads snapshots", () -> {
            try (SnakeServer server = new SnakeServer(0, 650, 60000); Peer a = new Peer(server.port(), "活躍", true); Peer b = new Peer(server.port(), "無心跳", false)) {
                waitFor(() -> server.snapshot().finished, 1800, "server timeout bounded");
                check(server.snapshot().winnerId == a.id, "timeout yields correct winner");
                check(server.snapshot().events.get(0).reason.equals("TIMEOUT"), "timeout distinct from EOF");
            }
        });
        scenario("all players leaving resets round; next solo does not inherit previous win", () -> {
            try (SnakeServer server = new SnakeServer(0, 2000, 60000)) {
                Peer a = new Peer(server.port(), "甲", true), b = new Peer(server.port(), "乙", true);
                b.close(); waitFor(() -> server.snapshot().finished, 1500, "first round ends");
                long round = server.snapshot().roundId; a.close();
                waitFor(() -> server.snapshot().snakes.isEmpty(), 1500, "all resources removed");
                try (Peer next = new Peer(server.port(), "新玩家", true)) {
                    waitFor(() -> next.latest.get() != null, 1500, "new round state");
                    check(next.latest.get().roundId > round && !next.latest.get().finished, "new round clean");
                }
            }
        });
        scenario("deep-copy snapshot is isolated from game mutation", () -> {
            try (SnakeServer server = new SnakeServer(0, 2000, 60000); Peer a = new Peer(server.port(), "甲", true)) {
                GameState copy = server.snapshot();
                copy.snakes.get(0).body.get(0).x = 9999; copy.foods.clear();
                check(server.snapshot().snakes.get(0).body.get(0).x != 9999, "body points copied");
                check(!server.snapshot().foods.isEmpty(), "food list copied");
            }
        });
        scenario("Swing connection refused shows one specific notice; EDT stays responsive", () -> {
            int port; try (ServerSocket unused = new ServerSocket(0)) { port = unused.getLocalPort(); }
            SnakeClient c = client("測試", port, quick);
            try {
                waitFor(() -> ui(() -> c.noticeCode().equals("CONNECT_FAILED")), 2500, "connection failed notice");
                check(ui(() -> c.noticeText().contains("連線失敗")), "friendly failure message");
                check(!ui(c::canSendInput), "failed client cannot send input");
                long start = System.nanoTime(); ui(() -> { c.getTitle(); return null; });
                check((System.nanoTime() - start) / 1_000_000 < 500, "EDT not blocked by network");
                Thread.sleep(300); check(ui(c::noticeCount) == 1, "failure delivered once");
            } finally { dispose(c); }
        });
        scenario("Swing: 3-to-2 notice continues play, 2-to-1 notice wins once", () -> {
            try (SnakeServer server = new SnakeServer(0, 2000, 60000)) {
                SnakeClient uiClient = client("甲", server.port(), quick);
                try (Peer b = new Peer(server.port(), "乙", true); Peer c = new Peer(server.port(), "丙", true)) {
                    waitFor(() -> ui(() -> uiClient.state() != null && uiClient.state().snakes.size() == 3), 2000, "Swing applied 3-player snapshot");
                    b.close();
                    waitFor(() -> ui(() -> uiClient.noticeCode().equals("PLAYER_DISCONNECTED")), 1500, "player-left GUI notice");
                    check(ui(() -> uiClient.noticeText().contains("2 名存活玩家")), "not falsely declared winner");
                    check(ui(uiClient::canSendInput), "remaining game accepts input");
                    int notices = ui(uiClient::noticeCount); Thread.sleep(250);
                    check(ui(uiClient::noticeCount) == notices, "same event not shown repeatedly");
                    c.close();
                    waitFor(() -> ui(() -> uiClient.noticeCode().equals("WIN")), 1500, "server win shown in GUI");
                    check(ui(() -> uiClient.state().winnerId == uiClient.playerId()), "GUI uses server winner ID");
                    check(!ui(uiClient::canSendInput), "finished game stops input");
                } finally { dispose(uiClient); }
            }
        });
        scenario("Server shutdown shows connection loss, never a fabricated victory", () -> {
            SnakeServer server = new SnakeServer(0, 2000, 60000);
            SnakeClient c = client("甲", server.port(), quick);
            try {
                waitFor(() -> ui(c::isConnected), 2000, "client joined"); server.close();
                waitFor(() -> ui(() -> c.noticeCode().equals("SERVER_DISCONNECTED")), 1500, "server-loss GUI notice");
                check(!ui(c::canSendInput), "input stopped");
                check(!ui(() -> c.noticeText().contains("獲勝")), "no client-side invented winner");
                Thread.sleep(1000); check(ui(c::noticeCount) == 1, "reader, writer, heartbeat share one close");
            } finally { server.close(); dispose(c); }
        });
        scenario("silent handshake times out without freezing Swing", () -> {
            try (SilentServer server = new SilentServer(false)) {
                SnakeClient c = client("甲", server.port(), new ClientConnection.Timing(400, 80, 900));
                try {
                    waitFor(() -> ui(() -> c.noticeCode().equals("JOIN_TIMEOUT")), 1800, "ObjectInputStream handshake cannot hang forever");
                    check(ui(() -> c.noticeText().contains("加入程序")), "join timeout distinct");
                } finally { dispose(c); }
            }
        });
        scenario("silent connected Server is detected by incoming-message deadline", () -> {
            try (SilentServer server = new SilentServer(true)) {
                SnakeClient c = client("甲", server.port(), quick);
                try {
                    waitFor(() -> ui(() -> c.noticeCode().equals("SERVER_TIMEOUT")), 2400, "silent Server deadline");
                    check(!ui(c::canSendInput), "silent-server input stopped");
                    check(ui(c::noticeCount) == 1, "timeout and socket exception not duplicated");
                } finally { dispose(c); }
            }
        });
        scenario("reconnect button recovers from refusal and Server restart without stale callbacks", () -> {
            int port; try (ServerSocket reserve = new ServerSocket(0)) { port = reserve.getLocalPort(); }
            SnakeClient c = client("重連", port, quick);
            SnakeServer first = null, second = null;
            try {
                waitFor(() -> ui(() -> c.noticeCode().equals("CONNECT_FAILED")), 2000, "initial refusal");
                first = new SnakeServer(port, 2000, 60000);
                ui(() -> { reconnectButton(c.getRootPane()).doClick(); return null; });
                waitFor(() -> ui(() -> c.isConnected() && c.state() != null), 2000, "button joins restored Server");
                check(ui(c::canSendInput), "reconnected input works");
                first.close();
                waitFor(() -> ui(() -> c.noticeCode().equals("SERVER_DISCONNECTED")), 2000, "restart loss shown");
                second = new SnakeServer(port, 2000, 60000);
                ui(() -> { reconnectButton(c.getRootPane()).doClick(); return null; });
                waitFor(() -> ui(() -> c.isConnected() && c.state() != null), 2000, "new Server low sequence accepted");
                Thread.sleep(400);
                check(ui(() -> c.isConnected() && c.noticeCode().isEmpty()), "old callbacks cannot replace new session");
                check(!ui(() -> c.state().finished), "new solo does not inherit old winner");
            } finally { if (first != null) first.close(); if (second != null) second.close(); dispose(c); }
        });
        scenario("production 10-second Server timeout is exercised on a real Socket", () -> {
            try (SnakeServer server = new SnakeServer(0, 10000, 60000); Peer a = new Peer(server.port(), "活躍", true); Peer silent = new Peer(server.port(), "無回應", false)) {
                long start = System.nanoTime();
                waitFor(() -> server.snapshot().finished, 11500, "production timeout completes within allowance");
                long elapsed = (System.nanoTime() - start) / 1_000_000;
                check(elapsed >= 9700 && elapsed <= 11100, "production timeout measured: " + elapsed);
                check(server.snapshot().events.get(0).reason.equals("TIMEOUT"), "production timeout event");
                System.out.println("MEASURED production timeout " + elapsed + " ms");
            }
        });
        System.out.println("RESULT " + cases + " scenarios, " + assertions + " assertions passed.");
    }
}
