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
        clock.scheduleAtFixedRate(this::tick, 0, 1000 / FrameState.TICKS_PER_SECOND, TimeUnit.MILLISECONDS);
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
                for (byte action : peer.actions.take()) {
                    switch (action) {
                        case ArenaWire.RESPAWN -> world.respawn(peer.id);
                        case ArenaWire.BOOST_ON -> world.setBoost(peer.id, true);
                        case ArenaWire.BOOST_OFF -> world.setBoost(peer.id, false);
                        default -> world.useSkill(peer.id, action - ArenaWire.FIRST_SKILL);
                    }
                }
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
        private final ArenaWire.Actions actions = new ArenaWire.Actions();
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
                        case ArenaWire.ACTION_BATCH -> {
                            if (!actions.addBatch(ArenaWire.readActions(in))) return;
                        }
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
    private static final double BEAD_SPACING = 9;
    private static final double BOOST_MULTIPLIER = 1.7;
    private static final int MIN_LENGTH = 4;
    private static final int MAX_LENGTH = 100_000;
    private static final int SKILL_DURATION = 10 * FrameState.TICKS_PER_SECOND;
    private static final int BLAST_ANIMATION_TICKS = 8;
    private static final int MAX_BOMBS = 1024;
    private static final int INITIAL_LENGTH = 12;
    private static final int EXTRA_TRAIL = 64;
    private static final int FOOD_COUNT = 230;
    private static final int FOOD_PER_SPECIAL_KIND = 10;
    private static final int MAX_FOOD = FOOD_COUNT + 80;
    private static final double CONTACT_SQUARED = 4 * BEAD_RADIUS * BEAD_RADIUS;
    private static final double EAT_DISTANCE_SQUARED = 15 * 15;
    private static final double SPECIAL_EAT_DISTANCE_SQUARED = 19 * 19;

    private final Random random = new Random();
    private final double ringRotation = random.nextDouble() * Math.PI * 2;
    private final Map<Integer, Player> players = new LinkedHashMap<>();
    private final List<Pellet> food = new ArrayList<>();
    private final List<FrameState.Bomb> bombs = new ArrayList<>();
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
        bombs.removeIf(bomb -> bomb.ownerId() == id);
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

    synchronized void setBoost(int id, boolean held) {
        Player player = players.get(id);
        if (player != null && player.alive) {
            player.boostHeld = held;
        }
    }

    synchronized void useSkill(int id, int slot) {
        Player player = players.get(id);
        if (player == null || !player.alive || slot < 0 || slot >= player.skills.size()) return;
        FrameState.Kind kind = player.skills.get(slot);
        Point head = player.body.get(0);
        switch (kind) {
            case BOMB -> {
                if (bombs.size() >= MAX_BOMBS) return;
                bombs.add(new FrameState.Bomb(id, head.x(), head.y(), tick + SKILL_DURATION));
            }
            case GHOST_POWER -> player.ghostUntilTick = tick + SKILL_DURATION;
            case THIEF -> steal(player);
            default -> { return; }
        }
        player.skills.remove(slot);
    }

    private void steal(Player thief) {
        Point center = thief.body.get(0);
        int gained = 0;
        for (Player other : players.values()) {
            if (!other.alive || other == thief) continue;
            Point head = other.body.get(0);
            if (distanceSquared(center.x(), center.y(), head.x(), head.y())
                    > FrameState.THIEF_RADIUS * FrameState.THIEF_RADIUS) continue;
            int transfer = Math.min(2, Math.min(other.length - MIN_LENGTH, MAX_LENGTH - thief.length - gained));
            if (transfer > 0) {
                other.setLength(other.length - transfer);
                gained += transfer;
            }
        }
        if (gained > 0) thief.setLength(thief.length + gained);
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
        resolveBombs(crashed);
        for (Player player : players.values()) {
            if (player.alive && collided(player)) {
                crashed.add(player);
            }
        }
        for (Player player : crashed) {
            player.alive = false;
            player.boosting = false;
            player.ghostUntilTick = 0;
        }
        eatFood();
        refillFood();
        for (Player player : crashed) {
            scatterFood(player);
        }
    }

    private void resolveBombs(Set<Player> crashed) {
        bombs.removeIf(bomb -> tick - bomb.explodeTick() >= BLAST_ANIMATION_TICKS);
        List<FrameState.Bomb> exploding = bombs.stream().filter(bomb -> tick == bomb.explodeTick()).toList();
        if (exploding.isEmpty()) return;

        // All blasts on a tick see the original body. Each bead contributes at most one loss.
        for (Player player : players.values()) {
            if (!player.alive) continue;
            int hit = 0;
            for (Point bead : player.body) {
                for (FrameState.Bomb bomb : exploding) {
                    if (player.id != bomb.ownerId() && distanceSquared(bomb.x(), bomb.y(), bead.x(), bead.y())
                            <= FrameState.BOMB_RADIUS * FrameState.BOMB_RADIUS) {
                        hit++;
                        break;
                    }
                }
            }
            if (hit == player.length) crashed.add(player);
            else if (hit > 0) player.setLength(player.length - hit);
        }
    }

    synchronized FrameState snapshot() {
        List<Snake> snakes = new ArrayList<>(players.size());
        for (Player player : players.values()) {
            snakes.add(new Snake(player.id, player.name, player.color, player.alive,
                    player.angle, player.body.size(), player.body, player.skills,
                    player.ghostUntilTick, player.boosting));
        }
        return new FrameState(tick, RADIUS, snakes, food, bombs);
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
            if (other != player && (other.ghostUntilTick >= tick || player.ghostUntilTick >= tick)) {
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
            double radiusSquared = pellet.kind() == FrameState.Kind.NORMAL ? EAT_DISTANCE_SQUARED : SPECIAL_EAT_DISTANCE_SQUARED;
            double best = Double.POSITIVE_INFINITY;
            for (Player player : players.values()) {
                if (!player.alive) {
                    continue;
                }
                Point head = player.body.get(0);
                double distance = distanceSquared(pellet.x(), pellet.y(), head.x(), head.y());
                if (distance <= radiusSquared && distance < best) {
                    best = distance;
                    closest = player;
                }
            }
            if (closest != null) {
                iter.remove();
                switch (pellet.kind()) {
                    case NORMAL -> closest.setLength(closest.length + 1);
                    case MEGA_GROWTH -> closest.setLength(closest.length + 10);
                    case DIET_FOOD -> {
                        if (closest.length > MIN_LENGTH) closest.setLength(Math.max(MIN_LENGTH, closest.length - 10));
                    }
                    case BOMB, GHOST_POWER, THIEF -> {
                        if (closest.skills.size() == FrameState.SKILL_SLOTS) closest.skills.remove(0);
                        closest.skills.add(pellet.kind());
                    }
                }
            }
        }
    }

    private void refillFood() {
        FrameState.Kind[] kinds = FrameState.Kind.values();
        int[] counts = new int[kinds.length];
        for (Pellet pellet : food) counts[pellet.kind().ordinal()]++;
        for (FrameState.Kind kind : kinds) {
            if (kind == FrameState.Kind.NORMAL) continue;
            for (int count = counts[kind.ordinal()]; count < FOOD_PER_SPECIAL_KIND; count++) {
                food.add(randomPellet(kind));
            }
        }
        while (food.size() < FOOD_COUNT) {
            food.add(randomPellet(FrameState.Kind.NORMAL));
        }
    }

    private Pellet randomPellet(FrameState.Kind kind) {
        double angle = random.nextDouble() * Math.PI * 2;
        double distance = Math.sqrt(random.nextDouble()) * (RADIUS - 18);
        return new Pellet(Math.cos(angle) * distance, Math.sin(angle) * distance,
                random.nextInt(MAX_PLAYERS), kind);
    }

    private void scatterFood(Player player) {
        for (int i = 2; i < player.body.size() && food.size() < MAX_FOOD; i += 3) {
            Point bead = player.body.get(i);
            food.add(new Pellet(bead.x(), bead.y(), player.color, FrameState.Kind.NORMAL));
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
            FrameState.Kind kind = (i == 1) ? FrameState.Kind.BOMB : FrameState.Kind.NORMAL;
            food.add(new Pellet(best.head.x() + Math.cos(angle) * distance,
                    best.head.y() + Math.sin(angle) * distance, random.nextInt(MAX_PLAYERS), kind));
        }
    }

    private double clearance(Spawn spawn) {
        double dx = Math.cos(spawn.angle);
        double dy = Math.sin(spawn.angle);
        double tailDistance = (INITIAL_LENGTH - 1) * BEAD_SPACING;
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
    private record TrailSample(Point point, double distance) {}

    private static final class Player {
        final int id;
        final String name;
        final int color;
        final ArrayDeque<TrailSample> trail = new ArrayDeque<>();
        final List<FrameState.Kind> skills = new ArrayList<>(FrameState.SKILL_SLOTS);
        List<Point> body = List.of();
        double angle;
        double targetAngle;
        int length;
        int boostTicks;
        long ghostUntilTick;
        boolean boostHeld;
        boolean boosting;
        boolean alive;

        Player(int id, String name, int color) {
            this.id = id;
            this.name = name;
            this.color = color;
        }

        void reset(Spawn spawn) {
            alive = true;
            length = INITIAL_LENGTH;
            boostTicks = 0;
            boostHeld = false;
            boosting = false;
            ghostUntilTick = 0;
            skills.clear();
            angle = Math.atan2(Math.sin(spawn.angle), Math.cos(spawn.angle));
            targetAngle = angle;
            trail.clear();
            for (int i = 0; i < INITIAL_LENGTH + EXTRA_TRAIL; i++) {
                double distance = i * BEAD_SPACING;
                Point point = new Point(spawn.head.x() - Math.cos(angle) * distance,
                        spawn.head.y() - Math.sin(angle) * distance);
                if (i >= INITIAL_LENGTH && Math.hypot(point.x(), point.y()) > RADIUS - BEAD_RADIUS) break;
                trail.addLast(new TrailSample(point, -distance));
            }
            sampleBody();
        }

        void setLength(int requested) {
            length = Math.max(1, Math.min(MAX_LENGTH, requested));
            if (length <= MIN_LENGTH) boosting = false;
            sampleBody();
        }

        void advance() {
            double delta = Math.atan2(Math.sin(targetAngle - angle), Math.cos(targetAngle - angle));
            angle += Math.max(-MAX_TURN, Math.min(MAX_TURN, delta));
            angle = Math.atan2(Math.sin(angle), Math.cos(angle));
            boosting = boostHeld && length > MIN_LENGTH;
            double speed = boosting ? STEP_DISTANCE * BOOST_MULTIPLIER : STEP_DISTANCE;
            if (boosting && ++boostTicks == FrameState.TICKS_PER_SECOND) {
                length--;
                boostTicks = 0;
                if (length == MIN_LENGTH) boosting = false;
            }
            TrailSample previous = trail.getFirst();
            Point head = new Point(previous.point.x() + Math.cos(angle) * speed,
                    previous.point.y() + Math.sin(angle) * speed);
            double distance = previous.distance + speed;
            trail.addFirst(new TrailSample(head, distance));
            double cutoff = distance - (length + EXTRA_TRAIL) * BEAD_SPACING;
            while (trail.getLast().distance < cutoff) {
                trail.removeLast();
            }
            sampleBody();
        }

        void sampleBody() {
            // Sample by distance travelled so boosting does not stretch the snake or cause a false self-hit.
            Iterator<TrailSample> history = trail.iterator();
            TrailSample newer = history.next();
            TrailSample older = newer;
            double headDistance = newer.distance;
            List<Point> result = new ArrayList<>(length);
            result.add(newer.point);
            for (int i = 1; i < length; i++) {
                double target = headDistance - i * BEAD_SPACING;
                while (older.distance > target && history.hasNext()) {
                    newer = older;
                    older = history.next();
                }
                // If a large reward exhausts the available path, new beads grow out from the tail.
                if (target <= older.distance) {
                    result.add(older.point);
                } else {
                    double t = (target - older.distance) / (newer.distance - older.distance);
                    result.add(new Point(older.point.x() + (newer.point.x() - older.point.x()) * t,
                            older.point.y() + (newer.point.y() - older.point.y()) * t));
                }
            }
            body = List.copyOf(result);
        }
    }
}
