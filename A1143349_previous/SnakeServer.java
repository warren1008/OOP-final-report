import java.awt.Point;
import java.awt.Color;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class SnakeServer {
    private static final int PORT = 12345;
    public static final int MAP_RADIUS = 40; // 圓形地圖半徑 (約5人遊玩大小)
    private static GameState gameState = new GameState();
    private static int nextSnakeId = 1;
    private static List<ClientHandler> clients = new CopyOnWriteArrayList<>();
    private static Random random = new Random();
    private static Color[] foodColors = {Color.CYAN, Color.GREEN, Color.RED, Color.MAGENTA, Color.ORANGE, Color.YELLOW};

    public static void main(String[] args) {
        System.out.println("伺服器已啟動，等待玩家連線...");
        for(int i = 0; i < 30; i++) spawnFood(); // 初始地圖較大，多放些食物
        new Thread(SnakeServer::gameLoop).start();

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            while (true) {
                Socket socket = serverSocket.accept();
                ClientHandler client = new ClientHandler(socket, nextSnakeId++);
                clients.add(client);
                new Thread(client).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void spawnFood() {
        // 在圓形範圍內隨機生成
        int r = random.nextInt(MAP_RADIUS - 1);
        double theta = random.nextDouble() * 2 * Math.PI;
        Point p = new Point((int)(r * Math.cos(theta)), (int)(r * Math.sin(theta)));
        Color c = foodColors[random.nextInt(foodColors.length)];
        gameState.foods.add(new FoodData(p, c));
    }

    private static void gameLoop() {
        while (true) {
            try {
                Thread.sleep(120); 
                synchronized (gameState) {
                    for (SnakeData snake : gameState.snakes) {
                        if (!snake.alive || snake.body.isEmpty()) continue;

                        Point head = snake.body.get(0);
                        Point newHead = new Point(head.x + snake.dx, head.y + snake.dy);

                        // 判斷 1: 是否超出圓形地圖邊界 (利用畢氏定理計算與中心點(0,0)的距離)
                        boolean collision = (newHead.x * newHead.x + newHead.y * newHead.y > MAP_RADIUS * MAP_RADIUS);

                        // 判斷 2: 是否撞到自己或其他蛇
                        if (!collision) {
                            for (SnakeData other : gameState.snakes) {
                                if (!other.alive && other.body.isEmpty()) continue;
                                int checkLimit = (other == snake) ? other.body.size() - 1 : other.body.size();
                                for (int i = 0; i < checkLimit; i++) {
                                    if (other.body.get(i).equals(newHead)) {
                                        collision = true;
                                        break;
                                    }
                                }
                                if (collision) break;
                            }
                        }

                        // 如果發生碰撞，判定死亡並變成食物
                        if (collision) {
                            snake.alive = false;
                            for (Point p : snake.body) {
                                gameState.foods.add(new FoodData(p, snake.color)); // 變成圓點食物
                            }
                            snake.body.clear(); 
                            continue; 
                        }

                        // 檢查是否吃到食物
                        boolean ate = false;
                        Iterator<FoodData> it = gameState.foods.iterator();
                        while (it.hasNext()) {
                            if (it.next().pos.equals(newHead)) {
                                ate = true;
                                it.remove();
                                spawnFood();
                                break;
                            }
                        }

                        snake.body.add(0, newHead);
                        if (!ate) {
                            snake.body.remove(snake.body.size() - 1);
                        }
                    }
                }
                for (ClientHandler client : clients) {
                    client.sendState(gameState);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    static class ClientHandler implements Runnable {
        private Socket socket;
        private ObjectOutputStream out;
        private BufferedReader in;
        private SnakeData mySnake;

        public ClientHandler(Socket socket, int id) throws IOException {
            this.socket = socket;
            out = new ObjectOutputStream(socket.getOutputStream());
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            
            mySnake = new SnakeData();
            mySnake.id = id;
            mySnake.color = new Color(random.nextInt(200), random.nextInt(200), random.nextInt(200));
            
            // 出生在靠近中心的隨機位置
            int r = random.nextInt(MAP_RADIUS / 2);
            double theta = random.nextDouble() * 2 * Math.PI;
            mySnake.body.add(new Point((int)(r * Math.cos(theta)), (int)(r * Math.sin(theta))));
        }

        public void sendState(GameState state) {
            try {
                out.reset(); 
                out.writeObject(state);
                out.flush();
            } catch (IOException e) {
                disconnect();
            }
        }
        
        private void disconnect() {
            clients.remove(this);
            synchronized(gameState) { 
                mySnake.alive = false;
                mySnake.body.clear();
            }
        }

        @Override
        public void run() {
            try {
                String name = in.readLine();
                mySnake.name = (name == null || name.trim().isEmpty()) ? "Player " + mySnake.id : name;
                synchronized (gameState) { gameState.snakes.add(mySnake); }
                out.writeObject(mySnake.id);
                out.flush();

                String input;
                while ((input = in.readLine()) != null) {
                    synchronized (gameState) {
                        if (!mySnake.alive) continue;
                        if (input.equals("UP") && mySnake.dy == 0) { mySnake.dx = 0; mySnake.dy = -1; }
                        else if (input.equals("DOWN") && mySnake.dy == 0) { mySnake.dx = 0; mySnake.dy = 1; }
                        else if (input.equals("LEFT") && mySnake.dx == 0) { mySnake.dx = -1; mySnake.dy = 0; }
                        else if (input.equals("RIGHT") && mySnake.dx == 0) { mySnake.dx = 1; mySnake.dy = 0; }
                    }
                }
            } catch (IOException e) {
                disconnect();
            }
        }
    }
}