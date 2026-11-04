import java.awt.Color;
import java.awt.Point;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Multiplayer circular-map server. All winners are decided here, never by a Client. */
public class SnakeServer implements AutoCloseable {
    public static final int MAP_RADIUS = 40;
    public static final int DEFAULT_PORT = 12345;
    public static final long TIMEOUT_MS = 10000;
    private final Object lock = new Object();
    private final CopyOnWriteArrayList<ClientHandler> clients = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService timer = Executors.newScheduledThreadPool(2, task -> {
        Thread t = new Thread(task, "snake-server-timer"); t.setDaemon(true); return t;
    });
    private final Random random = new Random();
    private final long timeoutMs;
    private final ServerSocket listener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private GameState state = new GameState();
    private int nextId = 1;
    private long eventSequence;
    private long stateSequence;
    private boolean competitionStarted;
    private static final Color[] FOOD_COLORS = {Color.CYAN, Color.GREEN, Color.RED,
            Color.MAGENTA, Color.ORANGE, Color.YELLOW};

    public SnakeServer(int port) throws IOException { this(port, TIMEOUT_MS, 120); }

    // Shorter timeout / slower movement is used only by repeatable network tests.
    SnakeServer(int port, long timeoutMs, long tickMs) throws IOException {
        if (timeoutMs < 100 || tickMs < 1) throw new IllegalArgumentException("Invalid timing");
        this.timeoutMs = timeoutMs;
        listener = new ServerSocket(port);
        synchronized (lock) { for (int i = 0; i < 30; i++) spawnFood(); }
        timer.scheduleAtFixedRate(this::tick, tickMs, tickMs, TimeUnit.MILLISECONDS);
        timer.scheduleAtFixedRate(this::checkTimeouts, 100, 100, TimeUnit.MILLISECONDS);
        Thread accept = new Thread(this::acceptLoop, "snake-accept");
        accept.setDaemon(true); accept.start();
    }

    public int port() { return listener.getLocalPort(); }
    GameState snapshot() { synchronized (lock) { return state.snapshot(); } }

    private void acceptLoop() {
        while (!closed.get()) {
            try {
                Socket socket = listener.accept(); socket.setTcpNoDelay(true);
                ClientHandler c = new ClientHandler(socket); clients.add(c);
                Thread reader = new Thread(c::readLoop, "snake-reader");
                reader.setDaemon(true); reader.start();
            } catch (IOException e) { if (!closed.get()) System.err.println("Accept: " + e.getMessage()); }
        }
    }

    private void spawnFood() {
        for (int attempt = 0; attempt < 500; attempt++) {
            int x = random.nextInt(MAP_RADIUS * 2 - 1) - MAP_RADIUS + 1;
            int y = random.nextInt(MAP_RADIUS * 2 - 1) - MAP_RADIUS + 1;
            Point point = new Point(x, y);
            if (x * x + y * y >= MAP_RADIUS * MAP_RADIUS || occupied(point)) continue;
            if (state.foods.stream().noneMatch(f -> f.pos.equals(point))) {
                state.foods.add(new FoodData(point, FOOD_COLORS[random.nextInt(FOOD_COLORS.length)])); return;
            }
        }
    }

    private boolean occupied(Point point) {
        for (SnakeData s : state.snakes) if (s.body.contains(point)) return true;
        return false;
    }

    private void tick() {
        if (closed.get()) return;
        synchronized (lock) {
            if (!state.finished) {
                // Retain the original circular map and movement rules.
                for (SnakeData snake : state.snakes) {
                    if (!snake.connected || !snake.alive || snake.body.isEmpty()) continue;
                    Point head = snake.body.get(0);
                    Point next = new Point(head.x + snake.dx, head.y + snake.dy);
                    boolean collision = next.x * next.x + next.y * next.y > MAP_RADIUS * MAP_RADIUS;
                    for (SnakeData other : state.snakes) {
                        if (collision) break;
                        int limit = other == snake ? other.body.size() - 1 : other.body.size();
                        for (int i = 0; i < limit; i++) if (other.body.get(i).equals(next)) { collision = true; break; }
                    }
                    if (collision) {
                        snake.alive = false;
                        for (Point point : snake.body) state.foods.add(new FoodData(new Point(point), snake.color));
                        snake.body.clear(); continue;
                    }
                    boolean ate = false;
                    for (Iterator<FoodData> it = state.foods.iterator(); it.hasNext();) {
                        if (it.next().pos.equals(next)) { it.remove(); ate = true; break; }
                    }
                    snake.body.add(0, next);
                    if (!ate) snake.body.remove(snake.body.size() - 1); else spawnFood();
                }
                decideWinner("LAST_SURVIVOR");
            }
        }
        broadcast();
    }

    private void decideWinner(String reason) {
        if (state.finished || !competitionStarted) return;
        java.util.List<SnakeData> survivors = new ArrayList<>();
        for (SnakeData s : state.snakes) if (s.connected && s.alive) survivors.add(s);
        if (survivors.size() <= 1) {
            state.finished = true;
            state.winnerId = survivors.isEmpty() ? -1 : survivors.get(0).id;
            state.resultReason = survivors.isEmpty() ? "NO_SURVIVOR" : reason;
        }
    }

    private void broadcast() {
        GameState copy;
        synchronized (lock) { copy = state.snapshot(); copy.stateSequence = ++stateSequence; }
        for (ClientHandler c : clients) if (c.joined && !c.closed.get()) c.offer(copy);
    }

    private void checkTimeouts() {
        long now = System.nanoTime();
        for (ClientHandler c : clients) {
            long limit = c.joined ? timeoutMs : 5000;
            if ((now - c.lastReceived) / 1_000_000 >= limit) c.disconnect("TIMEOUT");
        }
    }

    private final class ClientHandler {
        final Socket socket;
        final AtomicBoolean closed = new AtomicBoolean();
        final BlockingQueue<Object> outgoing = new ArrayBlockingQueue<>(16);
        volatile long lastReceived = System.nanoTime();
        volatile boolean joined;
        SnakeData snake;
        Thread writer;
        ObjectOutputStream out;

        ClientHandler(Socket socket) { this.socket = socket; }

        void readLoop() {
            try {
                out = new ObjectOutputStream(socket.getOutputStream()); out.flush();
                BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String name = input.readLine();
                if (name == null) return;
                name = name.trim();
                if (name.isEmpty() || name.length() > 24) { reject("玩家名稱須為 1 至 24 個字元"); return; }
                synchronized (lock) {
                    if (closed.get() || SnakeServer.this.closed.get()) return;
                    if (state.finished) { reject("此局已結束，請等所有玩家離開後重新加入"); return; }
                    snake = new SnakeData(); snake.id = nextId++; snake.name = name;
                    snake.color = new Color(random.nextInt(160), 80 + random.nextInt(120), random.nextInt(160));
                    Point spawn;
                    do { spawn = new Point(random.nextInt(31) - 15, random.nextInt(31) - 15); } while (occupied(spawn));
                    snake.body.add(spawn); state.snakes.add(snake);
                    if (state.snakes.stream().filter(s -> s.connected && s.alive).count() >= 2) competitionStarted = true;
                    lastReceived = System.nanoTime();
                    outgoing.add(snake.id); joined = true;
                }
                writer = new Thread(this::writeLoop, "snake-writer-" + snake.id);
                writer.setDaemon(true); writer.start(); broadcast();
                String line;
                while (!closed.get() && (line = input.readLine()) != null) {
                    if (line.equals("LEAVE")) return;
                    if (line.equals("PING")) { lastReceived = System.nanoTime(); offer("PONG"); continue; }
                    if (!Set.of("UP", "DOWN", "LEFT", "RIGHT").contains(line)) continue;
                    lastReceived = System.nanoTime();
                    synchronized (lock) {
                        if (state.finished || !snake.alive) continue;
                        if (line.equals("UP") && snake.dy == 0) { snake.dx = 0; snake.dy = -1; }
                        else if (line.equals("DOWN") && snake.dy == 0) { snake.dx = 0; snake.dy = 1; }
                        else if (line.equals("LEFT") && snake.dx == 0) { snake.dx = -1; snake.dy = 0; }
                        else if (line.equals("RIGHT") && snake.dx == 0) { snake.dx = 1; snake.dy = 0; }
                    }
                }
            } catch (IOException e) {
                // EOF, abrupt close and write failure share one cleanup operation.
            } finally { disconnect("DISCONNECTED"); }
        }

        private void reject(String message) throws IOException {
            out.writeObject(-1); out.writeObject(message); out.flush();
        }

        void offer(Object value) {
            if (!closed.get() && !outgoing.offer(value)) disconnect("SLOW_CONNECTION");
        }

        private void writeLoop() {
            try {
                while (!closed.get()) {
                    Object value = outgoing.take();
                    out.reset(); out.writeObject(value); out.flush();
                }
            } catch (IOException | InterruptedException e) { disconnect("DISCONNECTED"); }
        }

        void disconnect(String reason) {
            synchronized (lock) {
                // Publish closed state and membership changes atomically with joining / winner checks.
                if (!closed.compareAndSet(false, true)) return;
                clients.remove(this);
                if (joined && snake != null && snake.connected) {
                    snake.connected = false; snake.alive = false; snake.body.clear();
                    if (!SnakeServer.this.closed.get()) {
                        state.events.add(new GameEvent(++eventSequence, snake.id, snake.name, reason));
                        if (state.events.size() > 32) state.events.remove(0);
                        decideWinner("OPPONENT_DISCONNECTED");
                        if (state.snakes.stream().noneMatch(s -> s.connected)) {
                            long nextRound = state.roundId + 1;
                            state = new GameState(); state.roundId = nextRound; competitionStarted = false;
                            for (int i = 0; i < 30; i++) spawnFood();
                        }
                    }
                }
            }
            try { socket.close(); } catch (IOException ignored) { }
            if (writer != null) writer.interrupt();
            if (!SnakeServer.this.closed.get()) broadcast();
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try { listener.close(); } catch (IOException ignored) { }
        for (ClientHandler c : clients) c.disconnect("SERVER_STOPPED");
        timer.shutdownNow();
    }

    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? DEFAULT_PORT : Integer.parseInt(args[0]);
        SnakeServer server = new SnakeServer(port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        System.out.println("Server started on port " + server.port() + "; PING=2s; timeout=10s.");
        new CountDownLatch(1).await();
    }
}
