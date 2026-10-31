import java.io.Serializable;
import java.awt.Point;
import java.awt.Color;
import java.util.ArrayList;

public class GameState implements Serializable {
    private static final long serialVersionUID = 1L;
    public ArrayList<SnakeData> snakes = new ArrayList<>();
    public ArrayList<FoodData> foods = new ArrayList<>();
}

class SnakeData implements Serializable {
    private static final long serialVersionUID = 1L;
    public int id;
    public String name; // 新增：玩家名稱
    public ArrayList<Point> body = new ArrayList<>();
    public Color color;
    public int dx = 1, dy = 0;
    public boolean alive = true;
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