package arena;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A complete, immutable view of one server tick. Coordinates are relative to the arena center. */
public record FrameState(long tick, double radius, List<Snake> snakes, List<Pellet> food,
                         List<Bomb> bombs) {
    public static final int MAX_PLAYERS = 5;
    public static final int BOMB_RADIUS = 130;
    public static final int THIEF_RADIUS = 180;
    public static final int TICKS_PER_SECOND = 25;
    public static final int SKILL_SLOTS = 3;

    public enum Kind { NORMAL, MEGA_GROWTH, DIET_FOOD, BOMB, GHOST_POWER, THIEF }

    public FrameState {
        snakes = List.copyOf(snakes);
        food = List.copyOf(food);
        bombs = List.copyOf(bombs);
    }

    public FrameState(long tick, double radius, List<Snake> snakes, List<Pellet> food) {
        this(tick, radius, snakes, food, List.of());
    }

    public record Point(double x, double y) {}

    /** Body points run from head to tail and remain available after a crash. */
    public record Snake(int id, String name, int colorIndex, boolean alive,
                        double angle, int length, List<Point> body, List<Kind> skills,
                        long ghostUntilTick, boolean boosting) {
        public Snake {
            body = List.copyOf(body);
            skills = List.copyOf(skills);
        }

        public Snake(int id, String name, int colorIndex, boolean alive,
                     double angle, int length, List<Point> body) {
            this(id, name, colorIndex, alive, angle, length, body, List.of(), 0, false);
        }

        public boolean isGhostAt(long tick) {
            return alive && ghostUntilTick > 0 && tick <= ghostUntilTick;
        }
    }

    public record Pellet(double x, double y, int colorIndex, Kind kind) {
        public Pellet(double x, double y, int colorIndex) {
            this(x, y, colorIndex, Kind.NORMAL);
        }
    }

    public record Bomb(int ownerId, double x, double y, long explodeTick) {}
}

/** The room sends one complete snapshot at a time over each ordered TCP stream. */
final class ArenaWire {
    static final int MAGIC = 0x534E4B41;
    static final int VERSION = 2;
    static final int JOINED = 1, ROOM_FULL = 2, BAD_NAME = 3, INCOMPATIBLE = 4;
    static final int STEER = 1, ACTION_BATCH = 2, HEARTBEAT = 3;
    static final int RESPAWN = 0, BOOST_ON = 1, BOOST_OFF = 2, FIRST_SKILL = 3;
    private static final int MAX_PENDING_ACTIONS = 128;
    private static final FrameState.Kind[] KINDS = FrameState.Kind.values();
    static final String NAME_HINT = "請輸入 1 到 20 個字的玩家名稱。";

    private ArenaWire() {}

    /** Preserve key presses in order and transfer each sender batch to one simulation tick. */
    static final class Actions {
        private static final byte[] EMPTY = {};
        private final byte[] entries = new byte[MAX_PENDING_ACTIONS];
        private int size;

        synchronized boolean add(int action) {
            if (size == entries.length) return false;
            entries[size++] = (byte) action;
            return true;
        }

        synchronized boolean addBatch(byte[] batch) {
            if (batch.length > entries.length - size) return false;
            System.arraycopy(batch, 0, entries, size, batch.length);
            size += batch.length;
            return true;
        }

        synchronized byte[] take() {
            if (size == 0) return EMPTY;
            byte[] actions = Arrays.copyOf(entries, size);
            size = 0;
            return actions;
        }
    }

    static void writeActions(DataOutputStream out, byte[] actions) throws IOException {
        out.writeByte(ACTION_BATCH);
        out.writeByte(actions.length);
        out.write(actions);
    }

    static byte[] readActions(DataInputStream in) throws IOException {
        int size = in.readUnsignedByte();
        if (size == 0 || size > MAX_PENDING_ACTIONS) throw new IOException("操作數量無效。");
        byte[] actions = new byte[size];
        in.readFully(actions);
        for (byte action : actions) {
            if (action < RESPAWN || action >= FIRST_SKILL + FrameState.SKILL_SLOTS) {
                throw new IOException("收到無效的玩家操作。");
            }
        }
        return actions;
    }

    static boolean validName(String name) {
        return !name.isBlank() && name.codePointCount(0, name.length()) <= 20
                && name.codePoints().noneMatch(Character::isISOControl);
    }

    static Thread daemon(String name, Runnable action) {
        Thread thread = new Thread(action, name);
        thread.setDaemon(true);
        return thread;
    }

    static byte[] encode(FrameState frame) {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        try {
            out.writeLong(frame.tick());
            out.writeDouble(frame.radius());
            out.writeInt(frame.snakes().size());
            for (var snake : frame.snakes()) {
                out.writeInt(snake.id());
                out.writeUTF(snake.name());
                out.writeInt(snake.colorIndex());
                out.writeBoolean(snake.alive());
                out.writeDouble(snake.angle());
                out.writeInt(snake.length());
                out.writeInt(snake.body().size());
                for (var point : snake.body()) {
                    out.writeDouble(point.x());
                    out.writeDouble(point.y());
                }
                out.writeByte(snake.skills().size());
                for (var skill : snake.skills()) out.writeByte(skill.ordinal());
                out.writeLong(snake.ghostUntilTick());
                out.writeBoolean(snake.boosting());
            }
            out.writeInt(frame.food().size());
            for (var pellet : frame.food()) {
                out.writeDouble(pellet.x());
                out.writeDouble(pellet.y());
                out.writeInt(pellet.colorIndex());
                out.writeByte(pellet.kind().ordinal());
            }
            out.writeInt(frame.bombs().size());
            for (var bomb : frame.bombs()) {
                out.writeInt(bomb.ownerId());
                out.writeDouble(bomb.x());
                out.writeDouble(bomb.y());
                out.writeLong(bomb.explodeTick());
            }
        } catch (IOException ex) {
            // The destination is memory, and player names have already been bounded.
            throw new IllegalStateException("無法編碼房間狀態", ex);
        }
        return bytes.toByteArray();
    }

    static FrameState readFrame(DataInputStream in) throws IOException {
        long tick = in.readLong();
        double radius = finite(in);
        if (radius <= 0) throw invalidFrame();
        int players = count(in, FrameState.MAX_PLAYERS);
        var snakes = new ArrayList<FrameState.Snake>(players);
        for (int p = 0; p < players; p++) {
            int id = in.readInt();
            String name = in.readUTF();
            if (!validName(name)) throw invalidFrame();
            int color = in.readInt();
            boolean alive = in.readBoolean();
            double angle = finite(in);
            int length = count(in, 100_000);
            int points = count(in, 100_000);
            var body = new ArrayList<FrameState.Point>(points);
            for (int i = 0; i < points; i++) {
                body.add(new FrameState.Point(finite(in), finite(in)));
            }
            int slots = in.readUnsignedByte();
            if (slots > FrameState.SKILL_SLOTS) throw invalidFrame();
            var skills = new ArrayList<FrameState.Kind>(slots);
            for (int i = 0; i < slots; i++) {
                FrameState.Kind skill = kind(in);
                if (skill != FrameState.Kind.BOMB && skill != FrameState.Kind.GHOST_POWER
                        && skill != FrameState.Kind.THIEF) throw invalidFrame();
                skills.add(skill);
            }
            snakes.add(new FrameState.Snake(id, name, color, alive, angle, length, body,
                    skills, in.readLong(), in.readBoolean()));
        }
        int items = count(in, 20_000);
        var food = new ArrayList<FrameState.Pellet>(items);
        for (int i = 0; i < items; i++) {
            food.add(new FrameState.Pellet(finite(in), finite(in), in.readInt(), kind(in)));
        }
        int armed = count(in, 20_000);
        var bombs = new ArrayList<FrameState.Bomb>(armed);
        for (int i = 0; i < armed; i++) {
            bombs.add(new FrameState.Bomb(in.readInt(), finite(in), finite(in), in.readLong()));
        }
        return new FrameState(tick, radius, snakes, food, bombs);
    }

    private static FrameState.Kind kind(DataInputStream in) throws IOException {
        int value = in.readUnsignedByte();
        if (value >= KINDS.length) throw invalidFrame();
        return KINDS[value];
    }

    private static int count(DataInputStream in, int limit) throws IOException {
        int value = in.readInt();
        if (value < 0 || value > limit) throw invalidFrame();
        return value;
    }

    private static double finite(DataInputStream in) throws IOException {
        double value = in.readDouble();
        if (!Double.isFinite(value)) throw invalidFrame();
        return value;
    }

    private static IOException invalidFrame() {
        return new IOException("房間傳來無效的遊戲資料。");
    }
}
