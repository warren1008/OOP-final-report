import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Background transport for the existing object-stream protocol. No Swing work occurs here. */
final class ClientConnection implements AutoCloseable {
    interface Listener {
        void joined(int playerId);
        void state(GameState snapshot);
        void failed(String code, String message);
    }
    static final class Timing {
        final int connectMs;
        final long pingMs, timeoutMs;
        Timing(int connectMs, long pingMs, long timeoutMs) {
            if (connectMs < 1 || pingMs < 1 || timeoutMs < 1) throw new IllegalArgumentException();
            this.connectMs = connectMs; this.pingMs = pingMs; this.timeoutMs = timeoutMs;
        }
        static Timing normal() { return new Timing(5000, 2000, 10000); }
    }
    private final String host, name;
    private final int port;
    private final Listener listener;
    private final Timing timing;
    private final Socket socket = new Socket();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final BlockingQueue<String> outgoing = new ArrayBlockingQueue<>(64);
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "snake-client-monitor"); t.setDaemon(true); return t;
    });
    private volatile boolean joined, connected;
    private volatile long lastReceived = System.nanoTime(), connectedAt = lastReceived;
    private long lastState = -1;
    private Thread writer;

    ClientConnection(String host, int port, String name, Timing timing, Listener listener) {
        this.host = host; this.port = port; this.name = name; this.timing = timing; this.listener = listener;
    }
    void start() {
        Thread reader = new Thread(this::readLoop, "snake-client-reader"); reader.setDaemon(true); reader.start();
        monitor.scheduleAtFixedRate(this::pulse, 100, 100, TimeUnit.MILLISECONDS);
    }
    boolean isJoined() { return joined && !closed.get(); }
    void send(String line) {
        if (!closed.get() && !outgoing.offer(line)) fail("SEND_FAILED", "傳送佇列已滿，請重新連線。");
    }
    private void readLoop() {
        try {
            socket.connect(new InetSocketAddress(host, port), timing.connectMs);
            if (closed.get()) return;
            socket.setTcpNoDelay(true); connected = true; connectedAt = lastReceived = System.nanoTime();
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            out.write(name); out.newLine(); out.flush();
            writer = new Thread(() -> writeLoop(out), "snake-client-writer"); writer.setDaemon(true); writer.start();
            try (ObjectInputStream input = new ObjectInputStream(socket.getInputStream())) {
                Object welcome = input.readObject();
                if (!(welcome instanceof Integer)) throw new IOException("Invalid player ID");
                int id = (Integer) welcome;
                if (id < 0) { fail("JOIN_REJECTED", String.valueOf(input.readObject())); return; }
                if (closed.get()) return;
                joined = true; lastReceived = System.nanoTime(); listener.joined(id);
                while (!closed.get()) {
                    Object message = input.readObject();
                    if (closed.get()) return;
                    if ("PONG".equals(message)) { lastReceived = System.nanoTime(); continue; }
                    if (!(message instanceof GameState)) throw new IOException("Invalid state");
                    GameState state = (GameState) message;
                    if (state.snakes == null || state.foods == null || state.events == null) throw new IOException("Incomplete state");
                    lastReceived = System.nanoTime();
                    if (state.stateSequence <= lastState) continue;
                    lastState = state.stateSequence; listener.state(state);
                }
            }
        } catch (Exception e) {
            if (joined) fail("SERVER_DISCONNECTED", "與伺服器中斷連線，操作已停止。請確認 Server 後重新連線。");
            else fail("CONNECT_FAILED", "連線失敗：" + friendly(e) + "。請確認 IP、Port 與 Server 狀態。");
        }
    }
    private String friendly(Exception e) {
        if (e instanceof ConnectException) return "主機未啟動或拒絕連線";
        if (e instanceof UnknownHostException) return "找不到主機";
        if (e instanceof SocketTimeoutException) return "連線逾時";
        return "無法完成連線或加入";
    }
    private void writeLoop(BufferedWriter out) {
        try {
            while (!closed.get()) { String line = outgoing.take(); out.write(line); out.newLine(); out.flush(); }
        } catch (IOException e) {
            fail(joined ? "SERVER_DISCONNECTED" : "CONNECT_FAILED", joined ?
                    "與伺服器中斷連線，無法傳送資料。請確認 Server 後重新連線。" : "連線失敗，無法傳送玩家資料。");
        } catch (InterruptedException ignored) { }
    }
    private long lastPing;
    private void pulse() {
        if (closed.get()) return;
        long now = System.nanoTime();
        if (!joined) {
            if ((now - connectedAt) / 1_000_000 >= timing.connectMs)
                fail(connected ? "JOIN_TIMEOUT" : "CONNECT_FAILED", connected ?
                        "伺服器未在期限內完成加入程序，請重新連線。" : "連線逾時，請確認 IP、Port 與網路。");
            return;
        }
        if ((now - lastReceived) / 1_000_000 >= timing.timeoutMs) {
            String seconds = timing.timeoutMs % 1000 == 0 ? String.valueOf(timing.timeoutMs / 1000) : String.valueOf(timing.timeoutMs / 1000.0);
            fail("SERVER_TIMEOUT", "伺服器超過 " + seconds + " 秒未回應，連線已中斷。請確認網路後重新連線。");
        } else if ((now - lastPing) / 1_000_000 >= timing.pingMs) { lastPing = now; send("PING"); }
    }
    private void fail(String code, String message) {
        if (!shutdown()) return;
        listener.failed(code, message);
    }
    private boolean shutdown() {
        if (!closed.compareAndSet(false, true)) return false;
        monitor.shutdownNow();
        try { socket.close(); } catch (IOException ignored) { }
        if (writer != null) writer.interrupt();
        return true;
    }
    @Override public void close() { shutdown(); }
}
