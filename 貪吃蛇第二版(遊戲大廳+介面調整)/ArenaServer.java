package arena;

import static arena.FrameState.MAX_PLAYERS;

import arena.FrameState.Pellet;
import arena.FrameState.Point;
import arena.FrameState.Snake;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class ArenaServer implements AutoCloseable {
    public static void main(String[] args) throws IOException {
        ArenaServer server = new ArenaServer(5277);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "arena-shutdown"));
        System.out.println("伺服器已啟動，玩家可使用 TCP 連線埠 " + server.port() + " 加入房間。");
    }

    private final ArenaWorld world = new ArenaWorld();
    private final ServerSocket listener;
    private final Set<Peer> peers = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(
            job -> ArenaWire.daemon("arena-simulation", job));
    private volatile boolean closed;

    public ArenaServer(int port) throws IOException {
        if (port < 0 || port > 65535) throw new IOException("連接埠必須介於 0 到 65535。");
        listener = new ServerSocket();
        try {
            listener.bind(new InetSocketAddress(port));
        } catch (IOException ex) {
            listener.close();
            throw new IOException("無法建立房間，請確認連接埠是否已被使用。", ex);
        }
        clock.scheduleAtFixedRate(this::tick, 0, 40, TimeUnit.MILLISECONDS);
        Thread acceptThread = new Thread(this::acceptLoop, "arena-listener");
        acceptThread.setDaemon(false);
        acceptThread.start();
    }

    public int port() {
        return listener.getLocalPort();
    }

    private void acceptLoop() {
        try {
            while (!closed) {
                Socket socket = listener.accept();
                // Bound the number of incomplete handshakes as well as active players.
                if (peers.size() >= MAX_PLAYERS + 12) {
                    socket.close();
                    continue;
                }
                Peer peer = new Peer(socket);
                peers.add(peer);
                if (closed) {
                    peer.closeSocket();
                    peers.remove(peer);
                } else {
                    ArenaWire.daemon("arena-player-reader", peer::read).start();
                }
            }
        } catch (IOException ex) {
            if (!closed) close();
        }
    }

    private void tick() {
        FrameState frame;
        List<Peer> players;
        synchronized (world) {
            players = peers.stream().filter(peer -> peer.joined).toList();
            if (players.isEmpty()) return;
            for (Peer peer : players) {
                if (peer.retry.getAndSet(false)) world.respawn(peer.id);
                Double angle = peer.angle.getAndSet(null);
                if (angle != null) world.steer(peer.id, angle);
            }
            world.step();
            frame = world.snapshot();
        }
        byte[] packet = ArenaWire.encode(frame);
        for (Peer peer : players) peer.offer(packet);
    }

    @Override
    public void close() {
        closed = true;
        clock.shutdownNow();
        try {
            listener.close();
        } catch (IOException ignored) {}
        for (Peer peer : peers) peer.closeSocket();
    }

    private final class Peer {
        private final Socket socket;
        private final AtomicReference<Double> angle = new AtomicReference<>();
        private final AtomicBoolean retry = new AtomicBoolean();
        private volatile boolean joined;
        private int id = -1;
        private boolean stopped;
        private byte[] latest;

        Peer(Socket socket) {
            this.socket = socket;
        }

        void read() {
            try {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(5000);
                var in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                var out = new DataOutputStream(socket.getOutputStream());
                if (in.readInt() != ArenaWire.MAGIC || in.readUnsignedShort() != ArenaWire.VERSION) {
                    out.writeByte(ArenaWire.INCOMPATIBLE);
                    return;
                }
                String name = in.readUTF().strip();
                if (!ArenaWire.validName(name)) {
                    out.writeByte(ArenaWire.BAD_NAME);
                    return;
                }
                try {
                    synchronized (world) {
                        id = world.add(name);
                    }
                } catch (IllegalStateException full) {
                    out.writeByte(ArenaWire.ROOM_FULL);
                    return;
                }
                out.writeByte(ArenaWire.JOINED);
                out.writeInt(id);
                out.flush();
                socket.setSoTimeout(12_000);
                joined = true;
                ArenaWire.daemon("arena-player-writer", () -> write(out)).start();
                while (!closed) {
                    switch (in.readUnsignedByte()) {
                        case ArenaWire.STEER -> {
                            double requested = in.readDouble();
                            if (!Double.isFinite(requested)) return;
                            angle.set(requested);
                        }
                        case ArenaWire.RESPAWN -> retry.set(true);
                        case ArenaWire.HEARTBEAT -> { }
                        default -> { return; }
                    }
                }
            } catch (IOException ignored) {
                // EOF, heartbeat expiry and explicit close all release the same room slot.
            } finally {
                closeSocket();
                synchronized (world) {
                    joined = false;
                    if (id != -1) world.remove(id);
                }
                peers.remove(this);
            }
        }

        synchronized void offer(byte[] packet) {
            latest = packet;
            notifyAll();
        }

        void write(DataOutputStream out) {
            try {
                while (true) {
                    byte[] packet;
                    synchronized (this) {
                        while (!stopped && latest == null) wait();
                        if (stopped) return;
                        packet = latest;
                        latest = null;
                    }
                    // A slow player can hold up only this writer. Pending snapshots coalesce.
                    out.write(packet);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // Closing the socket also wakes the reader, which removes the player.
            } finally {
                closeSocket();
            }
        }

        void closeSocket() {
            synchronized (this) {
                stopped = true;
                notifyAll();
            }
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }
}

/** The host owns all movement, collisions, growth, and food placement. */
final class ArenaWorld {
    private static final double RADIUS = 1050;
    private static final double BEAD_RADIUS = 10;
    private static final double STEP_DISTANCE = 4.5;
    private static final double MAX_TURN = 0.17;
    private static final int SAMPLES_PER_BEAD = 2;
    private static final int INITIAL_LENGTH = 12;
    private static final int EXTRA_TRAIL = 12;
    private static final int FOOD_COUNT = 230;
    private static final int MAX_FOOD = FOOD_COUNT + 80;
    private static final double CONTACT_SQUARED = 4 * BEAD_RADIUS * BEAD_RADIUS;
    private static final double EAT_DISTANCE_SQUARED = 15 * 15;

    private final Random random = new Random();
    private final double ringRotation = random.nextDouble() * Math.PI * 2;
    private final Map<Integer, Player> players = new LinkedHashMap<>();
    private final List<Pellet> food = new ArrayList<>();
    private long tick;
    private int nextId = 1;

    ArenaWorld() {
        refillFood();
    }

    synchronized int add(String name) {
        if (players.size() >= MAX_PLAYERS) {
            throw new IllegalStateException("房間已滿，最多可容納五人");
        }
        int color = 0;
        boolean[] inUse = new boolean[MAX_PLAYERS];
        for (Player player : players.values()) {
            inUse[player.color] = true;
        }
        while (inUse[color]) {
            color++;
        }
        Player player = new Player(nextId++, name, color);
        spawn(player);
        players.put(player.id, player);
        return player.id;
    }

    synchronized void remove(int id) {
        players.remove(id);
    }

    synchronized void steer(int id, double angle) {
        Player player = players.get(id);
        if (player != null && player.alive && Double.isFinite(angle)) {
            player.targetAngle = Math.atan2(Math.sin(angle), Math.cos(angle));
        }
    }

    synchronized void respawn(int id) {
        Player player = players.get(id);
        if (player != null && !player.alive) {
            spawn(player);
        }
    }

    synchronized void step() {
        tick++;
        for (Player player : players.values()) {
            if (player.alive) {
                player.advance();
            }
        }

        // Resolve every contact against the same positions, including simultaneous head-on hits.
        Set<Player> crashed = new HashSet<>();
        for (Player player : players.values()) {
            if (player.alive && collided(player)) {
                crashed.add(player);
            }
        }
        for (Player player : crashed) {
            player.alive = false;
        }
        eatFood();
        for (Player player : crashed) {
            scatterFood(player);
        }
        refillFood();
    }

    synchronized FrameState snapshot() {
        List<Snake> snakes = new ArrayList<>(players.size());
        for (Player player : players.values()) {
            snakes.add(new Snake(player.id, player.name, player.color, player.alive,
                    player.angle, player.body.size(), player.body));
        }
        return new FrameState(tick, RADIUS, snakes, food);
    }

    private boolean collided(Player player) {
        Point head = player.body.get(0);
        double limit = RADIUS - BEAD_RADIUS;
        if (head.x() * head.x() + head.y() * head.y() >= limit * limit) {
            return true;
        }
        for (Player other : players.values()) {
            if (!other.alive) {
                continue;
            }
            // The two beads directly behind the head overlap it by design.
            int first = other == player ? 3 : 0;
            for (int i = first; i < other.body.size(); i++) {
                Point bead = other.body.get(i);
                if (distanceSquared(head.x(), head.y(), bead.x(), bead.y()) <= CONTACT_SQUARED) {
                    return true;
                }
            }
        }
        return false;
    }

    private void eatFood() {
        Iterator<Pellet> iter = food.iterator();
        while (iter.hasNext()) {
            Pellet pellet = iter.next();
            Player closest = null;
            double best = EAT_DISTANCE_SQUARED;
            for (Player player : players.values()) {
                if (!player.alive) {
                    continue;
                }
                Point head = player.body.get(0);
                double distance = distanceSquared(pellet.x(), pellet.y(), head.x(), head.y());
                if (distance < best) {
                    best = distance;
                    closest = player;
                }
            }
            if (closest != null) {
                iter.remove();
                closest.desiredLength++;
            }
        }
        for (Player player : players.values()) {
            if (player.alive && player.desiredLength > player.body.size()) {
                player.sampleBody();
            }
        }
    }

    private void refillFood() {
        while (food.size() < FOOD_COUNT) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = Math.sqrt(random.nextDouble()) * (RADIUS - 18);
            food.add(new Pellet(Math.cos(angle) * distance, Math.sin(angle) * distance,
                    random.nextInt(MAX_PLAYERS)));
        }
    }

    private void scatterFood(Player player) {
        for (int i = 2; i < player.body.size() && food.size() < MAX_FOOD; i += 3) {
            Point bead = player.body.get(i);
            food.add(new Pellet(bead.x(), bead.y(), player.color));
        }
    }

    private void spawn(Player player) {
        // Reserve a clear starting line for the body and the first second of travel.
        double base = ringRotation + player.color * Math.PI * 2 / MAX_PLAYERS;
        Spawn best = null;
        double bestClearance = -1;
        for (int i = 0; i < 32; i++) {
            double placement = base + i * 2.399963229728653; // golden angle
            double distance = RADIUS * (0.47 + (i % 3) * 0.1);
            Spawn candidate = new Spawn(new Point(Math.cos(placement) * distance,
                    Math.sin(placement) * distance), placement + Math.PI + 0.44);
            double clearance = clearance(candidate);
            if (clearance > bestClearance) {
                best = candidate;
                bestClearance = clearance;
            }
            if (bestClearance >= 110 * 110) {
                break;
            }
        }
        player.reset(best);
        // A small first meal lies in sight and gives a new player a reason to steer.
        for (int i = 0; i < 3 && food.size() < MAX_FOOD; i++) {
            double angle = best.angle + i * 0.08;
            double distance = 68 + i * 23;
            food.add(new Pellet(best.head.x() + Math.cos(angle) * distance,
                    best.head.y() + Math.sin(angle) * distance, random.nextInt(MAX_PLAYERS)));
        }
    }

    private double clearance(Spawn spawn) {
        double dx = Math.cos(spawn.angle);
        double dy = Math.sin(spawn.angle);
        double tailDistance = (INITIAL_LENGTH - 1) * SAMPLES_PER_BEAD * STEP_DISTANCE;
        double closest = Double.POSITIVE_INFINITY;
        for (Player player : players.values()) {
            if (!player.alive) {
                continue;
            }
            for (Point bead : player.body) {
                double along = (bead.x() - spawn.head.x()) * dx + (bead.y() - spawn.head.y()) * dy;
                along = Math.max(-tailDistance, Math.min(120, along));
                closest = Math.min(closest, distanceSquared(bead.x(), bead.y(),
                        spawn.head.x() + dx * along, spawn.head.y() + dy * along));
            }
        }
        return closest;
    }

    private static double distanceSquared(double ax, double ay, double bx, double by) {
        double dx = ax - bx;
        double dy = ay - by;
        return dx * dx + dy * dy;
    }

    private record Spawn(Point head, double angle) {}

    private static final class Player {
        final int id;
        final String name;
        final int color;
        final ArrayDeque<Point> trail = new ArrayDeque<>();
        List<Point> body = List.of();
        double angle;
        double targetAngle;
        int desiredLength;
        boolean alive;

        Player(int id, String name, int color) {
            this.id = id;
            this.name = name;
            this.color = color;
        }

        void reset(Spawn spawn) {
            alive = true;
            desiredLength = INITIAL_LENGTH;
            angle = Math.atan2(Math.sin(spawn.angle), Math.cos(spawn.angle));
            targetAngle = angle;
            trail.clear();
            for (int i = 0; i <= (INITIAL_LENGTH + EXTRA_TRAIL - 1) * SAMPLES_PER_BEAD; i++) {
                double distance = i * STEP_DISTANCE;
                trail.addLast(new Point(spawn.head.x() - Math.cos(angle) * distance,
                        spawn.head.y() - Math.sin(angle) * distance));
            }
            sampleBody();
        }

        void advance() {
            double delta = Math.atan2(Math.sin(targetAngle - angle), Math.cos(targetAngle - angle));
            angle += Math.max(-MAX_TURN, Math.min(MAX_TURN, delta));
            angle = Math.atan2(Math.sin(angle), Math.cos(angle));
            Point head = trail.getFirst();
            trail.addFirst(new Point(head.x() + Math.cos(angle) * STEP_DISTANCE,
                    head.y() + Math.sin(angle) * STEP_DISTANCE));
            // Keeping a few old samples lets a newly earned bead appear on the actual tail path.
            if (trail.size() > (desiredLength + EXTRA_TRAIL - 1) * SAMPLES_PER_BEAD + 1) {
                trail.removeLast();
            }
            sampleBody();
        }

        void sampleBody() {
            List<Point> result = new ArrayList<>(desiredLength);
            int sample = 0;
            for (Point point : trail) {
                if (sample++ % SAMPLES_PER_BEAD == 0) {
                    result.add(point);
                    if (result.size() == desiredLength) {
                        break;
                    }
                }
            }
            body = List.copyOf(result);
        }
    }
}
