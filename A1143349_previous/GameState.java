import java.io.Serializable;
import java.awt.Point;
import java.awt.Color;
import java.util.ArrayList;

public class GameState implements Serializable {
    private static final long serialVersionUID = 1L;
    public ArrayList<SnakeData> snakes = new ArrayList<>();
    public ArrayList<FoodData> foods = new ArrayList<>();
    public ArrayList<GameEvent> events = new ArrayList<>();
    public boolean finished;
    public int winnerId = -1;
    public String resultReason = "";
    public long roundId = 1;
    public long stateSequence;

    // The writer owns this copy; the game loop never changes objects being serialized.
    public GameState snapshot() {
        GameState copy = new GameState();
        copy.finished = finished; copy.winnerId = winnerId;
        copy.resultReason = resultReason; copy.roundId = roundId;
        copy.stateSequence = stateSequence;
        for (SnakeData snake : snakes) {
            SnakeData s = new SnakeData();
            s.id = snake.id; s.name = snake.name; s.color = snake.color;
            s.dx = snake.dx; s.dy = snake.dy; s.alive = snake.alive;
            s.connected = snake.connected;
            for (Point point : snake.body) s.body.add(new Point(point));
            copy.snakes.add(s);
        }
        for (FoodData food : foods) copy.foods.add(new FoodData(new Point(food.pos), food.color));
        copy.events.addAll(events);
        return copy;
    }
}

class SnakeData implements Serializable {
    private static final long serialVersionUID = 1L;
    public int id;
    public String name; // 新增：玩家名稱
    public ArrayList<Point> body = new ArrayList<>();
    public Color color;
    public int dx = 1, dy = 0;
    public boolean alive = true;
    public boolean connected = true;
}

class GameEvent implements Serializable {
    private static final long serialVersionUID = 1L;
    public final long sequence;
    public final int playerId;
    public final String playerName;
    public final String reason;
    public GameEvent(long sequence, int playerId, String playerName, String reason) {
        this.sequence = sequence; this.playerId = playerId;
        this.playerName = playerName; this.reason = reason;
    }
}

class FoodData implements Serializable {
    private static final long serialVersionUID = 1L;
    public Point pos;
    public Color color;
    public FoodData(Point pos, Color color) {
        this.pos = pos;
        this.color = color;
    }
}
