package arena;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** A complete, immutable view of one server tick. Coordinates are relative to the arena center. */
public record FrameState(long tick, double radius, List<Snake> snakes, List<Pellet> food) {
    public static final int MAX_PLAYERS = 5;

    public FrameState {
        snakes = List.copyOf(snakes);
        food = List.copyOf(food);
    }

    public record Point(double x, double y) {}

    /** Body points run from head to tail and remain available after a crash. */
    public record Snake(int id, String name, int colorIndex, boolean alive,
                        double angle, int length, List<Point> body) {
        public Snake {
            body = List.copyOf(body);
        }
    }

    public record Pellet(double x, double y, int colorIndex) {}
}

/** The room sends one complete snapshot at a time over each ordered TCP stream. */
final class ArenaWire {
    static final int MAGIC = 0x534E4B41;
    static final int VERSION = 1;
    static final int JOINED = 1, ROOM_FULL = 2, BAD_NAME = 3, INCOMPATIBLE = 4;
    static final int STEER = 1, RESPAWN = 2, HEARTBEAT = 3;
    static final String NAME_HINT = "請輸入 1 到 20 個字的玩家名稱。";

    private ArenaWire() {}

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
            }
            out.writeInt(frame.food().size());
            for (var pellet : frame.food()) {
                out.writeDouble(pellet.x());
                out.writeDouble(pellet.y());
                out.writeInt(pellet.colorIndex());
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
            snakes.add(new FrameState.Snake(id, name, color, alive, angle, length, body));
        }
        int items = count(in, 20_000);
        var food = new ArrayList<FrameState.Pellet>(items);
        for (int i = 0; i < items; i++) {
            food.add(new FrameState.Pellet(finite(in), finite(in), in.readInt()));
        }
        return new FrameState(tick, radius, snakes, food);
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
