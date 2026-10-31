import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.List;

public class SnakeClient extends JFrame {
    private static final String SERVER_IP = "127.0.0.1";
    private static final int PORT = 12345;
    private static final int CELL_SIZE = 20;
    private static final int MAP_RADIUS = 40; // 必須與 Server 相同

    private GameState gameState;
    private PrintWriter out;
    private String playerName;
    private int myId = -1;
    private Point lastCameraPos = new Point(0, 0); // 記錄視角中心位置

    private GamePanel gamePanel;
    private JTextArea scoreArea;

    public SnakeClient(String name) {
        this.playerName = name;
        setTitle("多人連線貪吃蛇 - " + name);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // 1. 初始化遊戲主畫面
        gamePanel = new GamePanel();
        add(gamePanel, BorderLayout.CENTER);

        // 2. 初始化右側統計面板 (需求1)
        scoreArea = new JTextArea(25, 15);
        scoreArea.setEditable(false);
        scoreArea.setFont(new Font("微軟正黑體", Font.BOLD, 14));
        scoreArea.setBackground(new Color(230, 235, 240));
        scoreArea.setMargin(new Insets(10, 10, 10, 10));
        JScrollPane scrollPane = new JScrollPane(scoreArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("即時排行榜"));
        add(scrollPane, BorderLayout.EAST);

        // 鍵盤監聽綁定在 gamePanel
        gamePanel.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (out == null) return;
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_UP: out.println("UP"); break;
                    case KeyEvent.VK_DOWN: out.println("DOWN"); break;
                    case KeyEvent.VK_LEFT: out.println("LEFT"); break;
                    case KeyEvent.VK_RIGHT: out.println("RIGHT"); break;
                }
            }
        });

        pack();
        setLocationRelativeTo(null);
        connectToServer();
    }

    private void connectToServer() {
        new Thread(() -> {
            try {
                Socket socket = new Socket(SERVER_IP, PORT);
                out = new PrintWriter(socket.getOutputStream(), true);
                ObjectInputStream in = new ObjectInputStream(socket.getInputStream());

                out.println(playerName);
                myId = (Integer) in.readObject();

                while (true) {
                    gameState = (GameState) in.readObject();
                    updateScoreboard(); // 更新排行榜
                    gamePanel.repaint(); // 更新畫面
                }
            } catch (Exception e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(this, "無法連線到伺服器");
            }
        }).start();
    }

    private void updateScoreboard() {
        if (gameState == null) return;
        List<SnakeData> sorted = new ArrayList<>(gameState.snakes);
        // 依照身體長度從大到小排序
        sorted.sort((a, b) -> Integer.compare(b.body.size(), a.body.size()));

        StringBuilder sb = new StringBuilder();
        int rank = 1;
        for (SnakeData s : sorted) {
            if (s.alive) {
                sb.append(String.format(" %d. %s\n    長度: %d\n\n", rank++, s.name, s.body.size()));
            }
        }
        scoreArea.setText(sb.toString());
    }

    // 將原本的繪圖邏輯移至內部類別
    private class GamePanel extends JPanel {
        public GamePanel() {
            setPreferredSize(new Dimension(800, 600)); // 視窗視角大小固定為 800x600
            setFocusable(true);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (gameState == null) return;

            Graphics2D g2d = (Graphics2D) g;
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // 尋找自己的頭部以計算視角 (需求2: 跟隨視角)
            boolean isMySnakeAlive = false;
            for (SnakeData snake : gameState.snakes) {
                if (snake.id == myId) {
                    isMySnakeAlive = snake.alive;
                    if (!snake.body.isEmpty()) {
                        lastCameraPos = snake.body.get(0); // 隨時更新最後位置
                    }
                    break;
                }
            }

            // 繪製宇宙背景(超出圓形地圖的範圍)
            g2d.setColor(new Color(40, 45, 55));
            g2d.fillRect(0, 0, getWidth(), getHeight());

            // 計算 Camera 偏移量，讓 lastCameraPos 保持在畫面正中央
            int transX = getWidth() / 2 - lastCameraPos.x * CELL_SIZE;
            int transY = getHeight() / 2 - lastCameraPos.y * CELL_SIZE;
            g2d.translate(transX, transY); // 平移畫布

            // --- 以下繪製內容皆套用跟隨視角 ---

            // 繪製圓形地圖背景
            int radiusPx = MAP_RADIUS * CELL_SIZE;
            g2d.setColor(new Color(245, 248, 250));
            g2d.fillOval(-radiusPx, -radiusPx, radiusPx * 2, radiusPx * 2);

            // 繪製圓形地圖內的網格 (利用 Clip 裁切成圓形)
            g2d.setClip(new java.awt.geom.Ellipse2D.Float(-radiusPx, -radiusPx, radiusPx * 2, radiusPx * 2));
            g2d.setColor(new Color(220, 225, 230));
            for (int i = -MAP_RADIUS; i <= MAP_RADIUS; i++) {
                g2d.drawLine(i * CELL_SIZE, -radiusPx, i * CELL_SIZE, radiusPx);
                g2d.drawLine(-radiusPx, i * CELL_SIZE, radiusPx, i * CELL_SIZE);
            }
            g2d.setClip(null); // 解除裁切

            // 繪製食物
            for (FoodData food : gameState.foods) {
                g2d.setColor(food.color);
                g2d.fillOval(food.pos.x * CELL_SIZE + 2, food.pos.y * CELL_SIZE + 2, CELL_SIZE - 4, CELL_SIZE - 4);
            }

            // 繪製蛇
            for (SnakeData snake : gameState.snakes) {
                if (snake.body.isEmpty() || !snake.alive) continue;
                
                g2d.setColor(snake.color);
                for (int i = snake.body.size() - 1; i >= 0; i--) {
                    Point p = snake.body.get(i);
                    g2d.fillOval(p.x * CELL_SIZE, p.y * CELL_SIZE, CELL_SIZE, CELL_SIZE);

                    // 繪製眼睛與名字
                    if (i == 0) {
                        drawEyes(g2d, p.x * CELL_SIZE, p.y * CELL_SIZE, snake.dx, snake.dy);
                        g2d.setColor(Color.DARK_GRAY);
                        g2d.setFont(new Font("微軟正黑體", Font.BOLD, 12));
                        FontMetrics fm = g2d.getFontMetrics();
                        int textWidth = fm.stringWidth(snake.name);
                        g2d.drawString(snake.name, p.x * CELL_SIZE + (CELL_SIZE - textWidth) / 2, p.y * CELL_SIZE - 5);
                    }
                }
            }

            g2d.translate(-transX, -transY); // 還原畫布平移

            // --- 視角還原後，繪製 UI (Game Over 不會跟著畫面移動) ---
            if (!isMySnakeAlive && myId != -1) {
                g2d.setColor(new Color(0, 0, 0, 150));
                g2d.fillRect(0, 0, getWidth(), getHeight());
                g2d.setColor(Color.RED);
                g2d.setFont(new Font("Arial", Font.BOLD, 48));
                String msg = "GAME OVER";
                FontMetrics fm = g2d.getFontMetrics();
                g2d.drawString(msg, (getWidth() - fm.stringWidth(msg)) / 2, getHeight() / 2);
            }
        }
    }

    private void drawEyes(Graphics2D g2d, int x, int y, int dx, int dy) {
        g2d.setColor(Color.WHITE);
        int eyeSize = 8, pupilSize = 4;
        int e1X = 0, e1Y = 0, e2X = 0, e2Y = 0;

        if (dx == 1) { e1X = 12; e1Y = 2; e2X = 12; e2Y = 10; } 
        else if (dx == -1) { e1X = 0; e1Y = 2; e2X = 0; e2Y = 10; } 
        else if (dy == 1) { e1X = 2; e1Y = 12; e2X = 10; e2Y = 12; } 
        else { e1X = 2; e1Y = 0; e2X = 10; e2Y = 0; }

        g2d.fillOval(x + e1X, y + e1Y, eyeSize, eyeSize);
        g2d.fillOval(x + e2X, y + e2Y, eyeSize, eyeSize);

        g2d.setColor(Color.BLACK);
        g2d.fillOval(x + e1X + 2, y + e1Y + 2, pupilSize, pupilSize);
        g2d.fillOval(x + e2X + 2, y + e2Y + 2, pupilSize, pupilSize);
    }

    public static void main(String[] args) {
        String name = JOptionPane.showInputDialog(null, "請輸入您的名字:", "貪吃蛇登入", JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.trim().isEmpty()) name = "Guest";
        final String finalName = name;
        
        SwingUtilities.invokeLater(() -> {
            new SnakeClient(finalName).setVisible(true);
        });
    }
}