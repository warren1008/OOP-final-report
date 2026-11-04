import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.List;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;

public class SnakeClient extends JFrame {
    private static final String SERVER_IP = "127.0.0.1";
    private static final int PORT = 12345;
    private static final int CELL_SIZE = 20;
    private static final int MAP_RADIUS = 40; // 必須與 Server 相同

    private GameState gameState;
    private ClientConnection connection;
    private final String host;
    private final int port;
    private final boolean dialogsEnabled;
    private final ClientConnection.Timing timing;
    private int generation;
    private boolean connected;
    private boolean disposed;
    private boolean firstSnapshot = true;
    private long lastEvent;
    private long resultShown = -1;
    private JLabel statusLabel;
    private JButton reconnectButton;
    private JDialog noticeDialog;
    private String noticeCode = "", noticeText = "", overlayTitle = "";
    private final List<String> noticeHistory = new ArrayList<>();
    private String playerName;
    private int myId = -1;
    private Point lastCameraPos = new Point(0, 0); // 記錄視角中心位置

    private GamePanel gamePanel;
    private JTextArea scoreArea;

    public SnakeClient(String name) {
        this(name, SERVER_IP, PORT);
    }

    public SnakeClient(String name, String host, int port) {
        this(name, host, port, true, ClientConnection.Timing.normal());
    }

    SnakeClient(String name, String host, int port, boolean dialogsEnabled, ClientConnection.Timing timing) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Create Swing UI on EDT");
        this.host = host; this.port = port; this.dialogsEnabled = dialogsEnabled; this.timing = timing;
        this.playerName = name;
        setTitle("多人連線貪吃蛇 - " + name);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());

        JPanel status = new JPanel(new BorderLayout(16, 0));
        status.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        status.setBackground(new Color(234, 244, 248));
        statusLabel = new JLabel("正在建立連線…");
        statusLabel.setFont(new Font("Microsoft JhengHei", Font.BOLD, 17));
        statusLabel.setPreferredSize(new Dimension(780, 56));
        status.add(statusLabel, BorderLayout.CENTER);
        reconnectButton = new JButton("重新連線");
        reconnectButton.addActionListener(e -> connectToServer());
        status.add(reconnectButton, BorderLayout.EAST);
        add(status, BorderLayout.NORTH);

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

        for (String direction : new String[]{"UP", "DOWN", "LEFT", "RIGHT"}) {
            gamePanel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(direction), direction);
            gamePanel.getActionMap().put(direction, new AbstractAction() {
                public void actionPerformed(ActionEvent e) { sendDirection(direction); }
            });
        }

        pack();
        setLocationRelativeTo(null);
        connectToServer();
    }

    private void connectToServer() {
        if (disposed) return;
        int token = ++generation;
        if (connection != null) connection.close();
        if (noticeDialog != null) noticeDialog.dispose();
        connected = false; firstSnapshot = true; lastEvent = 0; resultShown = -1;
        myId = -1; gameState = null; lastCameraPos = new Point(); overlayTitle = "";
        noticeCode = ""; noticeText = "";
        statusLabel.setText("正在連線至 " + escape(host) + ":" + port + "…");
        statusLabel.setForeground(Color.DARK_GRAY);
        reconnectButton.setEnabled(false);
        connection = new ClientConnection(host, port, playerName, timing, new ClientConnection.Listener() {
            public void joined(int id) {
                onUi(token, () -> {
                    if (!connection.isJoined()) return;
                    connected = true; myId = id;
                    statusLabel.setForeground(new Color(20, 105, 80));
                    statusLabel.setText("已連線　玩家：" + escape(playerName) + "　伺服器：" + escape(host) + ":" + port);
                });
            }
            public void state(GameState snapshot) {
                onUi(token, () -> { if (connection.isJoined()) applyState(snapshot); });
            }
            public void failed(String code, String message) {
                onUi(token, () -> {
                    connected = false; reconnectButton.setEnabled(true);
                    overlayTitle = code.equals("CONNECT_FAILED") || code.equals("JOIN_TIMEOUT") || code.equals("JOIN_REJECTED") ? "連線失敗" : "連線已中斷";
                    announce(code, overlayTitle, message, JOptionPane.ERROR_MESSAGE);
                    gamePanel.repaint();
                });
            }
        });
        connection.start(); gamePanel.repaint();
    }

    private void onUi(int token, Runnable task) {
        SwingUtilities.invokeLater(() -> { if (!disposed && token == generation) task.run(); });
    }

    private void applyState(GameState snapshot) {
        gameState = snapshot;
        if (firstSnapshot) {
            for (GameEvent event : snapshot.events) lastEvent = Math.max(lastEvent, event.sequence);
            firstSnapshot = false;
        }
        if (snapshot.finished && resultShown != snapshot.roundId) {
            resultShown = snapshot.roundId;
            if (snapshot.winnerId == myId) {
                String message = snapshot.resultReason.equals("OPPONENT_DISCONNECTED") ?
                        "對手已斷線，僅剩您存活，Server 判定您獲勝。" : "僅剩您存活，Server 判定您獲勝。";
                announce("WIN", "對局結果", message, JOptionPane.INFORMATION_MESSAGE);
            } else {
                String winner = snapshot.snakes.stream().filter(s -> s.id == snapshot.winnerId).map(s -> s.name).findFirst().orElse("無存活玩家");
                announce("RESULT", "對局結果", "本局已結束，結果：" + winner + "。", JOptionPane.INFORMATION_MESSAGE);
            }
        }
        for (GameEvent event : snapshot.events) {
            if (event.sequence > lastEvent && event.playerId != myId && !snapshot.finished) {
                long living = snapshot.snakes.stream().filter(s -> s.connected && s.alive).count();
                String cause = event.reason.equals("TIMEOUT") ? "連線逾時" : "已斷線";
                announce("PLAYER_DISCONNECTED", "玩家離線通知", "玩家「" + event.playerName + "」" + cause +
                        "，仍有 " + living + " 名存活玩家，對局繼續。", JOptionPane.WARNING_MESSAGE);
            }
            lastEvent = Math.max(lastEvent, event.sequence);
        }
        updateScoreboard(); gamePanel.repaint();
    }

    private static String escape(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    private void announce(String code, String title, String message, int kind) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Notice must run on EDT");
        noticeCode = code; noticeText = message; noticeHistory.add(code);
        statusLabel.setText("<html>" + escape(message) + "</html>");
        statusLabel.setForeground(kind == JOptionPane.ERROR_MESSAGE ? new Color(165, 35, 35) : new Color(20, 105, 80));
        if (!dialogsEnabled) return;
        if (noticeDialog != null) noticeDialog.dispose();
        JLabel content = new JLabel("<html><body style='width:390px'>" + escape(message) + "</body></html>");
        content.setFont(new Font("Microsoft JhengHei", Font.PLAIN, 19));
        JOptionPane pane = new JOptionPane(content, kind, JOptionPane.DEFAULT_OPTION);
        noticeDialog = pane.createDialog(this, title);
        noticeDialog.setModal(false); noticeDialog.setVisible(true);
    }

    void sendDirection(String direction) {
        if (connected && connection != null && connection.isJoined() && gameState != null && !gameState.finished &&
                gameState.snakes.stream().anyMatch(s -> s.id == myId && s.alive)) connection.send(direction);
    }

    boolean isConnected() { return connected; }
    int playerId() { return myId; }
    GameState state() { return gameState; }
    String noticeCode() { return noticeCode; }
    String noticeText() { return noticeText; }
    int noticeCount() { return noticeHistory.size(); }
    boolean canSendInput() { return connected && connection != null && connection.isJoined() && gameState != null && !gameState.finished; }

    // Export actual live Swing components, not a recreated or mocked-up screen.
    void capture(Path file, boolean dialogOnly) throws IOException {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Capture on EDT");
        JComponent target = dialogOnly && noticeDialog != null ? noticeDialog.getRootPane() : getRootPane();
        BufferedImage image = new BufferedImage(target.getWidth(), target.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics(); target.printAll(g); g.dispose();
        ImageIO.write(image, "png", file.toFile());
    }

    @Override public void dispose() {
        disposed = true; generation++;
        if (connection != null) connection.close();
        if (noticeDialog != null) noticeDialog.dispose();
        super.dispose();
    }

    private void updateScoreboard() {
        if (gameState == null) { scoreArea.setText(""); return; }
        List<SnakeData> sorted = new ArrayList<>(gameState.snakes);
        // 依照身體長度從大到小排序
        sorted.sort((a, b) -> Integer.compare(b.body.size(), a.body.size()));

        StringBuilder sb = new StringBuilder();
        int rank = 1;
        for (SnakeData s : sorted) {
            if (s.alive && s.connected) {
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
            if (gameState == null) {
                drawOverlay(g, overlayTitle.isEmpty() ? "正在連線" : overlayTitle);
                return;
            }

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
            Shape boardClip = g2d.getClip();
            g2d.clip(new java.awt.geom.Ellipse2D.Float(-radiusPx, -radiusPx, radiusPx * 2, radiusPx * 2));
            g2d.setColor(new Color(220, 225, 230));
            for (int i = -MAP_RADIUS; i <= MAP_RADIUS; i++) {
                g2d.drawLine(i * CELL_SIZE, -radiusPx, i * CELL_SIZE, radiusPx);
                g2d.drawLine(-radiusPx, i * CELL_SIZE, radiusPx, i * CELL_SIZE);
            }
            g2d.setClip(boardClip); // 還原元件裁切，避免覆蓋狀態列或排行榜

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
            if (!connected) drawOverlay(g2d, overlayTitle.isEmpty() ? "連線已中斷" : overlayTitle);
            else if (gameState.finished) drawOverlay(g2d, gameState.winnerId == myId ? "您已獲勝" : "對局結束");
            else if (!isMySnakeAlive && myId != -1) drawOverlay(g2d, "GAME OVER");
        }

        private void drawOverlay(Graphics g, String message) {
            g.setColor(new Color(0, 0, 0, 150)); g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(Color.WHITE); g.setFont(new Font("Microsoft JhengHei", Font.BOLD, 42));
            FontMetrics fm = g.getFontMetrics();
            g.drawString(message, (getWidth() - fm.stringWidth(message)) / 2, getHeight() / 2);
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
        SwingUtilities.invokeLater(() -> {
            JTextField name = new JTextField(args.length > 0 ? args[0] : "Player", 18);
            JTextField host = new JTextField(args.length > 1 ? args[1] : SERVER_IP, 18);
            JTextField port = new JTextField(args.length > 2 ? args[2] : String.valueOf(PORT), 18);
            JPanel form = new JPanel(new GridLayout(0, 2, 10, 10));
            form.add(new JLabel("玩家名稱")); form.add(name);
            form.add(new JLabel("Server IP")); form.add(host);
            form.add(new JLabel("Port")); form.add(port);
            if (JOptionPane.showConfirmDialog(null, form, "多人貪吃蛇連線", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            try {
                int number = Integer.parseInt(port.getText().trim());
                if (number < 1 || number > 65535 || name.getText().trim().isEmpty() || name.getText().trim().length() > 24 || host.getText().trim().isEmpty()) throw new IllegalArgumentException();
                new SnakeClient(name.getText().trim(), host.getText().trim(), number).setVisible(true);
            } catch (IllegalArgumentException e) { JOptionPane.showMessageDialog(null, "請輸入玩家名稱、有效 IP 及 1 至 65535 的 Port。", "連線設定錯誤", JOptionPane.ERROR_MESSAGE); }
        });
    }
}
