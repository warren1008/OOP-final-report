package arena;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleConsumer;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

public final class ArenaClient implements AutoCloseable {
    public static void main(String[] args) {
        openLobby();
    }

    /** 啟動玩家大廳，可選擇加入房間或在本機開房。 */
    public static void openLobby() {
        SwingUtilities.invokeLater(SnakeArena::new);
    }

    private final Socket socket = new Socket();
    private final ScheduledExecutorService sender = Executors.newSingleThreadScheduledExecutor(
            job -> ArenaWire.daemon("arena-input", job));
    private final AtomicReference<Double> angle = new AtomicReference<>();
    private final ArenaWire.Actions actions = new ArenaWire.Actions();
    private final int id;
    private volatile FrameState latest;
    private volatile String error;
    private volatile boolean closed;
    private long lastSend;

    public ArenaClient(String host, int port, String name) throws IOException {
        String playerName = name == null ? "" : name.strip();
        if (!ArenaWire.validName(playerName)) throw new IOException(ArenaWire.NAME_HINT);
        if (host == null || host.isBlank()) throw new IOException("請輸入房主的 IP 位址或主機名稱。");
        if (port < 1 || port > 65535) throw new IOException("連接埠必須介於 1 到 65535。");

        try {
            try {
                socket.connect(new InetSocketAddress(host.strip(), port), 5000);
            } catch (UnknownHostException ex) {
                throw new IOException("找不到這個主機，請確認房主位址。", ex);
            } catch (IOException ex) {
                throw new IOException("無法連上房間，請確認房間位址與連接埠。", ex);
            }
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(5000);
            var out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            var in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out.writeInt(ArenaWire.MAGIC);
            out.writeShort(ArenaWire.VERSION);
            out.writeUTF(playerName);
            out.flush();
            int status = in.readUnsignedByte();
            switch (status) {
                case ArenaWire.JOINED -> id = in.readInt();
                case ArenaWire.ROOM_FULL -> throw new IOException("房間已滿，最多容納 5 位玩家。");
                case ArenaWire.BAD_NAME -> throw new IOException(ArenaWire.NAME_HINT);
                default -> throw new IOException("遊戲版本不相容，請和房主使用同一版遊戲。");
            }
            socket.setSoTimeout(8000);
            lastSend = System.nanoTime();
            sender.scheduleWithFixedDelay(() -> send(out), 0, 40, TimeUnit.MILLISECONDS);
            ArenaWire.daemon("arena-snapshots", () -> receive(in)).start();
        } catch (SocketTimeoutException | EOFException ex) {
            close();
            throw new IOException("房間沒有回應，請確認房主已開始遊戲。", ex);
        } catch (IOException ex) {
            close();
            throw ex;
        }
    }

    public int id() {
        return id;
    }

    public FrameState frame() {
        return latest;
    }

    public String error() {
        return error;
    }

    public void steer(double heading) {
        if (Double.isFinite(heading)) angle.set(heading);
    }

    public void respawn() {
        enqueue(ArenaWire.RESPAWN);
    }

    public void setBoost(boolean active) {
        enqueue(active ? ArenaWire.BOOST_ON : ArenaWire.BOOST_OFF);
    }

    public void useSkill(int slot) {
        if (slot >= 0 && slot < FrameState.SKILL_SLOTS) enqueue(ArenaWire.FIRST_SKILL + slot);
    }

    private void enqueue(int action) {
        if (!closed && !actions.add(action)) terminate("操作佇列已滿，請重新加入房間。");
    }

    private void send(DataOutputStream out) {
        try {
            Double heading = angle.getAndSet(null);
            byte[] pending = actions.take();
            long now = System.nanoTime();
            if (pending.length > 0) ArenaWire.writeActions(out, pending);
            if (heading != null) {
                out.writeByte(ArenaWire.STEER);
                out.writeDouble(heading);
            }
            if (pending.length == 0 && heading == null) {
                if (now - lastSend < TimeUnit.SECONDS.toNanos(3)) return;
                out.writeByte(ArenaWire.HEARTBEAT);
            }
            out.flush();
            lastSend = now;
        } catch (IOException ex) {
            terminate("與房主的連線已中斷。");
        }
    }

    private void receive(DataInputStream in) {
        try {
            while (!closed) latest = ArenaWire.readFrame(in);
        } catch (SocketTimeoutException ex) {
            terminate("房主連線逾時，請重新加入房間。");
        } catch (IOException ex) {
            terminate("與房主的連線已中斷。");
        }
    }

    private synchronized void terminate(String message) {
        if (closed) return;
        closed = true;
        error = message;
        sender.shutdownNow();
        try {
            socket.close();
        } catch (IOException ignored) {}
    }

    @Override
    public void close() {
        terminate(null);
    }
}

/** Game window. The host joins through the same TCP client as its guests. */
final class SnakeArena {
    private final JFrame window = new JFrame("Snake Arena");
    private final CardLayout screens = new CardLayout();
    private final JPanel pages = new JPanel(screens);
    private final JLabel status = Theme.label("遊戲大廳",13,true,Theme.PAPER);
    private final LobbyPanel lobby = new LobbyPanel(this::connect);
    private final MatchPanel match = new MatchPanel(this::steer, this::boost, this::useSkill,
            this::respawn, this::roomDialog, this::leave);
    private final Timer refresh = new Timer(16,e->refresh());
    private Room room;
    private FrameState previous;

    SnakeArena() {
        window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        window.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if(confirmExit()) { closeRoom(); window.dispose(); }
            }
        });
        window.setIconImages(List.of(Theme.logo(32),Theme.logo(48),Theme.logo(128)));
        window.add(header(),BorderLayout.NORTH);
        pages.add(lobby,"lobby"); pages.add(match,"match"); window.add(pages,BorderLayout.CENTER);
        Rectangle screen=GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        window.setMinimumSize(new Dimension(Math.min(screen.width,830),Math.min(screen.height,540)));
        window.setSize(Math.min(screen.width,1140),Math.min(screen.height,720));
        window.setLocationRelativeTo(null); window.setVisible(true);
        refresh.setCoalesce(true);
    }
    private JPanel header() {
        JPanel bar=new JPanel(new BorderLayout());
        bar.setBackground(Theme.INK); bar.setBorder(new EmptyBorder(10,24,10,24));
        JPanel brand=Theme.clear(new FlowLayout(FlowLayout.LEFT,0,0));
        brand.add(new JLabel(new ImageIcon(Theme.logo(43))));
        JLabel title = new JLabel("SNAKE ARENA");
        title.setFont(Theme.latin(23, true));
        title.setForeground(Theme.PAPER);
        title.setBorder(new EmptyBorder(0, 12, 0, 0));
        brand.add(title);
        bar.add(brand,BorderLayout.WEST);
        JPanel end=Theme.clear(new FlowLayout(FlowLayout.RIGHT,8,6));
        JButton guide = new JButton("食物與技能");
        guide.setFont(Theme.font(13,true));
        guide.setForeground(Theme.PAPER);
        guide.setContentAreaFilled(false);
        guide.setBorder(new EmptyBorder(2,10,2,10));
        guide.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        guide.addActionListener(e -> {
            FoodGuide.show(window);
            match.focusField();
        });
        end.add(guide);
        Theme.Card pill=new Theme.Card(new FlowLayout(FlowLayout.CENTER,9,2),new Color(0x295044),new Color(0x537263),24);
        JLabel dot=new JLabel("●"); dot.setFont(Theme.latin(12,true)); dot.setForeground(Theme.GOLD);
        pill.add(dot); pill.add(status); end.add(pill); bar.add(end,BorderLayout.EAST); return bar;
    }
    private record Room(ArenaServer server,ArenaClient client,String address,int port,List<String> localAddresses) implements AutoCloseable {
        @Override public void close() {
            client.close();
            if(server!=null) server.close();
        }
    }
    private void connect(boolean hosting,String name,String address,int port) {
        new SwingWorker<Room,Void>() {
            @Override protected Room doInBackground() throws IOException {
                ArenaServer server=hosting ? new ArenaServer(port) : null;
                String destination=hosting ? "127.0.0.1" : address;
                try {
                    List<String> addresses=hosting ? lanAddresses() : List.of();
                    ArenaClient client=new ArenaClient(destination,port,name);
                    return new Room(server,client,destination,port,addresses);
                } catch(IOException | RuntimeException ex) {
                    if(server!=null) server.close();
                    throw ex;
                }
            }
            @Override protected void done() {
                lobby.busy(false);
                try {
                    Room result=get();
                    if(!window.isDisplayable()) { result.close(); return; }
                    room=result; previous=null;
                    match.open(hosting,port);
                    status.setText("房間已連線"); screens.show(pages,"match"); match.focusField(); refresh.start();
                } catch(ExecutionException ex) {
                    Throwable cause=ex.getCause();
                    lobby.error(cause instanceof IOException ? cause.getMessage() : "連線失敗，請確認網路後再試。");
                } catch(InterruptedException ex) {
                    Thread.currentThread().interrupt(); lobby.error("連線已取消。");
                }
            }
        }.execute();
    }
    private static List<String> lanAddresses() {
        List<InetAddress> addresses=new ArrayList<>();
        try {
            var interfaces=NetworkInterface.getNetworkInterfaces();
            while(interfaces!=null && interfaces.hasMoreElements()) {
                NetworkInterface nic=interfaces.nextElement();
                if(!nic.isUp() || nic.isLoopback()) continue;
                nic.inetAddresses().filter(a->a instanceof Inet4Address && !a.isLinkLocalAddress()).forEach(addresses::add);
            }
        } catch(SocketException ignored) { }
        return addresses.stream().sorted(Comparator.comparing(a->!a.isSiteLocalAddress())).map(InetAddress::getHostAddress).distinct().toList();
    }
    private void refresh() {
        if(room==null) return;
        String issue=room.client().error();
        if(issue!=null) {
            match.lost(issue); status.setText("已離線"); refresh.stop(); return;
        }
        FrameState frame=room.client().frame();
        if(frame!=null && frame!=previous) {
            previous=frame; match.update(frame,room.client().id());
        }
    }
    private void steer(double angle) { if(room!=null) room.client().steer(angle); }
    private void boost(boolean held) { if(room!=null) room.client().setBoost(held); }
    private void useSkill(int slot) { if(room!=null) room.client().useSkill(slot); }
    private void respawn() { if(room!=null) room.client().respawn(); }
    private void leave() {
        if(confirmExit()) {
            closeRoom(); screens.show(pages,"lobby"); status.setText("遊戲大廳");
        }
    }
    private boolean confirmExit() {
        if(room==null || room.server()==null || previous==null || previous.snakes().size()<2) return true;
        return JOptionPane.showOptionDialog(window,"離開後，房內其他玩家也會中斷連線。","關閉房間",
                JOptionPane.YES_NO_OPTION,JOptionPane.QUESTION_MESSAGE,null,
                new Object[]{"關閉房間","繼續遊戲"},"繼續遊戲")==0;
    }
    private void closeRoom() {
        refresh.stop(); match.reset(); previous=null;
        if(room!=null) { room.close(); room=null; }
    }
    private void roomDialog() {
        if(room==null) return;
        boolean hosting=room.server()!=null;
        JPanel details=Theme.clear(new GridBagLayout());
        GridBagConstraints c=new GridBagConstraints();
        c.gridx=0; c.gridy=0; c.fill=GridBagConstraints.HORIZONTAL; c.weightx=1; c.insets=new Insets(3,0,3,0);
        details.add(Theme.label(hosting ? "選擇朋友可連到的房主位址" : "房主位址",14,true,Theme.INK),c);
        List<String> addresses=hosting ? room.localAddresses() : List.of(room.address());
        JComboBox<String> choice=new JComboBox<>((addresses.isEmpty()?List.of("127.0.0.1"):addresses).toArray(String[]::new));
        choice.setFont(Theme.font(15,false)); choice.getAccessibleContext().setAccessibleName("房主位址"); c.gridy++; details.add(choice,c);
        c.gridy++; details.add(Theme.label("TCP 連線埠　"+room.port(),14,true,Theme.INK),c);
        if(hosting) {
            c.gridy++; details.add(Theme.label(addresses.isEmpty() ? "目前只找到本機位址，可在同一台電腦加入。" : "同一個區域網路的玩家可使用此位址加入。",13,false,Theme.MUTED),c);
        }
        int action=JOptionPane.showOptionDialog(window,details,"房間資訊",JOptionPane.DEFAULT_OPTION,JOptionPane.PLAIN_MESSAGE,
                null,new Object[]{"複製連線資訊","關閉"},"關閉");
        if(action==0 && room!=null) {
            String info="房主位址 "+choice.getSelectedItem()+"  /  TCP 連線埠 "+room.port();
            try { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(info),null); }
            catch(IllegalStateException ex) { JOptionPane.showMessageDialog(window,info,"連線資訊",JOptionPane.INFORMATION_MESSAGE); }
        }
    }
}

final class LobbyPanel extends JPanel {
    interface Start { void connect(boolean hosting, String name, String host, int port); }
    private final Start start;
    private final ArenaPoster poster = new ArenaPoster();
    private final Theme.Field name = new Theme.Field("輸入你的名字", "");
    private final Theme.Field host = new Theme.Field("IP 或主機名稱", "");
    private final Theme.Field port = new Theme.Field("5277", "5277");
    private final Theme.Button enter = new Theme.Button("建立房間並開始　→",true);
    private final JLabel message = Theme.label(" ",12,false,Theme.MUTED);
    private final JLabel hint = Theme.label("朋友可用你的 IP 與連線埠加入。",13,false,Theme.MUTED);
    private final JToggleButton hostMode = mode("建立房間"), joinMode = mode("加入房間");
    private final JPanel endpoint = Theme.clear(new BorderLayout(10,0));
    private final JPanel instructions = guide();
    private boolean hosting = true;

    LobbyPanel(Start start) {
        super(new BorderLayout(0,16));
        this.start=start;
        setBackground(Theme.CANVAS);
        setBorder(new EmptyBorder(22,24,16,24));
        JPanel main=Theme.clear(new BorderLayout(18,0));
        main.add(poster,BorderLayout.CENTER);
        Theme.Card card=new Theme.Card(new GridBagLayout(),Theme.PAPER,Theme.LINE,26);
        card.setPreferredSize(new Dimension(328,400));
        JPanel form=Theme.clear(new GridBagLayout());
        GridBagConstraints row=new GridBagConstraints();
        row.gridx=0; row.gridy=0; row.weightx=1; row.fill=GridBagConstraints.HORIZONTAL;
        JLabel title=Theme.label("進入競技場",26,true,Theme.INK);
        addRow(form,row,title,0);
        ButtonGroup group=new ButtonGroup(); group.add(hostMode); group.add(joinMode);
        hostMode.setSelected(true);
        Theme.Card tabs=new Theme.Card(new GridLayout(1,2,3,0),Theme.SOFT,null,13);
        tabs.setBorder(new EmptyBorder(3,3,3,3)); tabs.add(hostMode); tabs.add(joinMode);
        tabs.setPreferredSize(new Dimension(0,42)); addRow(form,row,tabs,17);
        name.getAccessibleContext().setAccessibleName("玩家暱稱");
        host.getAccessibleContext().setAccessibleName("房主位址");
        port.getAccessibleContext().setAccessibleName("連線埠");
        addRow(form,row,field("玩家暱稱",name),18);
        addRow(form,row,endpoint,13);
        addRow(form,row,hint,7);
        enter.setPreferredSize(new Dimension(0,47)); addRow(form,row,enter,19);
        message.setPreferredSize(new Dimension(0,34)); addRow(form,row,message,4);
        GridBagConstraints full=new GridBagConstraints();
        full.weightx=1; full.fill=GridBagConstraints.HORIZONTAL; full.insets=new Insets(14,21,5,21);
        card.add(form,full); main.add(card,BorderLayout.EAST);
        add(main,BorderLayout.CENTER); add(instructions,BorderLayout.SOUTH);
        configureEndpoint();
        hostMode.addActionListener(e->changeMode(true)); joinMode.addActionListener(e->changeMode(false));
        ActionListener submit=e->submit();
        enter.addActionListener(submit); name.addActionListener(submit);
        host.addActionListener(submit); port.addActionListener(submit);
        name.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { preview(); }
            public void removeUpdate(DocumentEvent e) { preview(); }
            public void changedUpdate(DocumentEvent e) { preview(); }
            private void preview() { poster.setNickname(name.getText()); }
        });
    }
    @Override public void doLayout() {
        instructions.setVisible(getHeight() >= 490);
        super.doLayout();
    }
    private static JToggleButton mode(String text) {
        JToggleButton b=new JToggleButton(text) {
            @Override protected void paintComponent(Graphics original) {
                Graphics2D g=(Graphics2D)original.create(); Theme.smooth(g);
                if (isSelected()) { g.setColor(Theme.PAPER); g.fillRoundRect(1,1,getWidth()-2,getHeight()-2,9,9); }
                if (isFocusOwner()) { g.setColor(Theme.MUTED); g.drawRoundRect(1,1,getWidth()-3,getHeight()-3,9,9); }
                g.dispose(); super.paintComponent(original);
            }
        };
        b.setOpaque(false); b.setContentAreaFilled(false); b.setBorderPainted(false); b.setFocusPainted(false);
        b.setFont(Theme.font(14,true)); b.setForeground(Theme.INK);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); return b;
    }
    private static void addRow(JPanel box,GridBagConstraints c,JComponent comp,int top) {
        c.insets=new Insets(top,0,0,0); box.add(comp,c); c.gridy++;
    }
    private static JPanel field(String caption, JTextField input) {
        JPanel box=Theme.clear(new BorderLayout(0,5));
        JLabel label=Theme.label(caption,13,true,Theme.INK); label.setLabelFor(input);
        box.add(label,BorderLayout.NORTH); box.add(input,BorderLayout.CENTER); return box;
    }
    private void changeMode(boolean create) {
        hosting=create; message.setText(" "); configureEndpoint();
        enter.setText(hosting ? "建立房間並開始　→" : "加入對戰　→");
        hint.setText(hosting ? "朋友可用你的 IP 與連線埠加入。" : "輸入房主提供的位址與連線埠。");
    }
    private void configureEndpoint() {
        endpoint.removeAll();
        JPanel portBox=field("連線埠",port);
        if (hosting) endpoint.add(portBox,BorderLayout.CENTER);
        else {
            endpoint.add(field("房主位址",host),BorderLayout.CENTER);
            portBox.setPreferredSize(new Dimension(76,64)); endpoint.add(portBox,BorderLayout.EAST);
        }
        endpoint.revalidate(); endpoint.repaint();
    }
    private void submit() {
        if (!enter.isEnabled()) return;
        String nickname=name.getText().strip(), address=host.getText().strip();
        if (nickname.isEmpty() || nickname.codePointCount(0,nickname.length())>20) {
            error("請輸入 1 至 20 個字的暱稱。"); name.requestFocusInWindow(); return;
        }
        if (!hosting && address.isEmpty()) { error("請輸入房主的 IP 或主機名稱。"); host.requestFocusInWindow(); return; }
        int number;
        try { number=Integer.parseInt(port.getText().strip()); if(number<1 || number>65535) throw new NumberFormatException(); }
        catch(NumberFormatException ex) { error("連線埠須介於 1 至 65535。"); port.requestFocusInWindow(); return; }
        busy(true); start.connect(hosting,nickname,address,number);
    }
    void busy(boolean value) {
        for (JComponent c : new JComponent[]{enter,name,host,port,hostMode,joinMode}) c.setEnabled(!value);
        message.setForeground(Theme.MUTED); message.setText(value ? "連線中…" : " ");
    }
    void error(String text) {
        message.setForeground(new Color(0xA43B2D));
        String safe=text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
        message.setText("<html>"+safe+"</html>"); message.setToolTipText(text);
    }
    private static JPanel guide() {
        Theme.Card strip=new Theme.Card(new GridLayout(1,3,0,0),Theme.PAPER,Theme.LINE,16);
        strip.setPreferredSize(new Dimension(0,64));
        strip.add(tip(0,"控制方向","移動滑鼠或按方向鍵"));
        strip.add(tip(1,"消耗長度加速","按住空白鍵，每秒消耗 1 節"));
        strip.add(tip(2,"釋放技能","按 Q、W、E 使用對應欄位"));
        return strip;
    }
    private static JPanel tip(int kind,String title,String detail) {
        JPanel box=Theme.clear(new FlowLayout(FlowLayout.LEFT,12,10));
        box.setBorder(new EmptyBorder(0,8,0,0));
        JComponent icon=new JComponent() {
            @Override protected void paintComponent(Graphics original) {
                Graphics2D g=(Graphics2D)original.create(); Theme.smooth(g);
                Color[] colors={new Color(0xE5EEE0),new Color(0xF8ECCB),new Color(0xFAE6DA)};
                g.setColor(colors[kind]); g.fillRoundRect(0,2,37,37,11,11); g.setColor(Theme.INK); g.setStroke(new BasicStroke(1.7f));
                if(kind==0) {
                    g.drawRoundRect(12,9,13,23,10,10); g.drawLine(18,10,18,17); g.drawLine(13,18,24,18);
                } else if(kind==1) {
                    g.setColor(Theme.snake(3)); g.fillOval(11,15,11,11); g.setColor(Theme.snake(0)); g.fillOval(23,9,6,6);
                    g.setColor(Theme.snake(2)); g.fillOval(21,27,5,5);
                } else {
                    g.drawOval(8,10,21,21); g.drawOval(15,17,7,7); g.drawLine(24,12,27,9);
                }
                g.dispose();
            }
        };
        icon.setPreferredSize(new Dimension(37,40)); box.add(icon);
        JPanel copy=Theme.clear(new GridLayout(2,1));
        copy.add(Theme.label(title,13,true,Theme.INK)); copy.add(Theme.label(detail,12,false,Theme.MUTED));
        box.add(copy); return box;
    }
}

final class FoodGuide {
    private FoodGuide() {}

    static String name(FrameState.Kind kind) {
        return switch (kind) {
            case NORMAL -> "普通食物";
            case MEGA_GROWTH -> "成長";
            case DIET_FOOD -> "縮短";
            case BOMB -> "炸彈";
            case GHOST_POWER -> "穿透";
            case THIEF -> "偷取";
        };
    }

    static void show(Component owner) {
        JPanel guide = Theme.clear(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(3, 2, 3, 2);
        guide.add(entry(FrameState.Kind.MEGA_GROWTH, "Mega Growth", "吃下後立刻增加 10 節。"), c);
        guide.add(entry(FrameState.Kind.DIET_FOOD, "Diet Food", "吃下後減少 10 節，最多減到剩 4 節。"), c);
        guide.add(entry(FrameState.Kind.BOMB, "Bomb", "在蛇頭放置炸彈，10 秒後炸掉半徑 " + FrameState.BOMB_RADIUS
                + " 內的敵蛇節數。全身被炸中就會出局。"), c);
        guide.add(entry(FrameState.Kind.GHOST_POWER, "Ghost Power", "啟用後 10 秒內可和其他蛇互相穿過，仍要避開自己和邊界。"), c);
        guide.add(entry(FrameState.Kind.THIEF, "Thief", "從蛇頭距你 " + FrameState.THIEF_RADIUS
                + " 以內的每位對手偷取最多 2 節，對手保留 4 節。"), c);
        c.insets = new Insets(9, 2, 2, 2);
        guide.add(Theme.label("炸彈、穿透、偷取會依序存入 Q／W／E。滿三格時，最舊的技能會被取代。",13,true,Theme.INK), c);
        guide.add(Theme.label("按住空白鍵以 1.7 倍速前進，每加速 1 秒消耗 1 節，剩 4 節時停止加速。",13,false,Theme.MUTED), c);
        JOptionPane.showOptionDialog(owner, guide, "食物與技能", JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, new Object[]{"關閉"}, "關閉");
    }

    private static JPanel entry(FrameState.Kind kind, String english, String detail) {
        Theme.Card row = new Theme.Card(new BorderLayout(12, 0), Theme.PAPER, Theme.LINE, 12);
        row.setBorder(new EmptyBorder(6, 10, 6, 14));
        JComponent icon = new JComponent() {
            @Override protected void paintComponent(Graphics original) {
                Graphics2D g = (Graphics2D) original.create();
                Theme.smooth(g);
                ArenaView.paintFoodIcon(g, kind, getWidth() / 2.0, getHeight() / 2.0, 11);
                g.dispose();
            }
        };
        icon.setPreferredSize(new Dimension(30, 30));
        row.add(icon, BorderLayout.WEST);
        JPanel copy = Theme.clear(new GridLayout(2, 1));
        copy.add(Theme.label(english + "  ·  " + name(kind), 15, true, Theme.INK));
        copy.add(Theme.label(detail, 13, false, Theme.MUTED));
        row.add(copy, BorderLayout.CENTER);
        return row;
    }
}

final class MatchPanel extends JPanel {
    private final ArenaView view;
    private final SkillBar skillBar = new SkillBar();
    private final Scoreboard scores=new Scoreboard();
    private final JLabel roomLabel=Theme.label("",13,true,Theme.MUTED);
    private final JLabel resultTitle=new JLabel("",SwingConstants.CENTER);
    private final JLabel resultText=Theme.label("",15,false,Theme.MUTED);
    private final JPanel shade;
    private final Theme.Button retry=new Theme.Button("再玩一次",true);
    private final Theme.Button leaveOverlay=new Theme.Button("返回大廳",false);
    private final Theme.Button roomInfo=new Theme.Button("房間資訊",false);
    private boolean disconnected;

    MatchPanel(DoubleConsumer steer, java.util.function.Consumer<Boolean> boost,
               java.util.function.IntConsumer skill, Runnable restart, Runnable room, Runnable leave) {
        super(new BorderLayout(0,11));
        setBackground(Theme.CANVAS);
        setBorder(new EmptyBorder(14,20,11,20));
        view=new ArenaView(steer, boost, skill);
        JPanel field=new JPanel() {
            @Override public void doLayout() {
                for(Component child:getComponents()) child.setBounds(0,0,getWidth(),getHeight());
            }
            @Override public boolean isOptimizedDrawingEnabled() { return false; }
            @Override protected void paintChildren(Graphics original) {
                Graphics2D g=(Graphics2D)original.create(); Theme.smooth(g);
                Shape border=new RoundRectangle2D.Double(.5,.5,getWidth()-1,getHeight()-1,20,20);
                g.clip(border); super.paintChildren(g); g.setClip(null);
                g.setColor(Theme.LINE); g.draw(border); g.dispose();
            }
        };
        field.setLayout(null); field.setOpaque(false);
        shade=new JPanel(new GridBagLayout()) {
            @Override protected void paintComponent(Graphics original) {
                Graphics2D g=(Graphics2D)original.create();
                g.setColor(new Color(23,53,40,100)); g.fillRect(0,0,getWidth(),getHeight()); g.dispose();
            }
        };
        shade.setOpaque(false); shade.setVisible(false);
        Theme.Card box=new Theme.Card(new GridBagLayout(),Theme.PAPER,Theme.LINE,23);
        box.setBorder(new EmptyBorder(22,25,24,25));
        GridBagConstraints row=new GridBagConstraints(); row.gridx=0; row.fill=GridBagConstraints.HORIZONTAL;
        resultTitle.setForeground(Theme.INK);
        row.gridy=0; box.add(resultTitle,row);
        resultText.setHorizontalAlignment(SwingConstants.CENTER);
        row.gridy++; row.insets=new Insets(5,0,18,0); box.add(resultText,row);
        JPanel actions=Theme.clear(new FlowLayout(FlowLayout.CENTER,7,0));
        actions.add(retry); actions.add(leaveOverlay);
        row.gridy++; row.insets=new Insets(0,0,0,0); box.add(actions,row); shade.add(box);
        field.add(shade); field.add(view);
        JPanel stage=Theme.clear(new BorderLayout(14,0));
        stage.add(field,BorderLayout.CENTER); stage.add(scores,BorderLayout.EAST);
        add(stage,BorderLayout.CENTER);
        JPanel toolbar=Theme.clear(new BorderLayout(8,0));
        toolbar.add(skillBar,BorderLayout.WEST);
        toolbar.add(roomLabel,BorderLayout.CENTER);
        JPanel tools=Theme.clear(new FlowLayout(FlowLayout.RIGHT,6,0));
        Theme.Button exit=new Theme.Button("離開房間",false);
        tools.add(roomInfo); tools.add(exit); toolbar.add(tools,BorderLayout.EAST);
        add(toolbar,BorderLayout.SOUTH);
        retry.addActionListener(e->{ retry.setEnabled(false); restart.run(); });
        roomInfo.addActionListener(e->{ room.run(); focusField(); });
        exit.addActionListener(e->{ leave.run(); focusField(); });
        leaveOverlay.addActionListener(e->leave.run());
    }
    void focusField() { if (view.isShowing() && !shade.isVisible()) view.requestFocusInWindow(); }
    void open(boolean hosting,int port) {
        reset();
        roomLabel.setText((hosting ? "房主" : "已加入房間")+" · "+port);
    }
    void update(FrameState frame,int id) {
        if(disconnected) return;
        view.showFrame(frame,id); scores.update(frame,id);
        FrameState.Snake me=frame.snakes().stream().filter(s->s.id()==id).findFirst().orElse(null);
        if(me==null) return;
        skillBar.update(me.alive() ? me.skills() : List.of(), me.alive() && me.boosting());
        if(!me.alive() && !shade.isVisible()) {
            resultTitle.setFont(Theme.latin(38,true)); resultTitle.setText("GAME OVER");
            resultText.setText("最終長度　"+me.length()+" 節");
            retry.setVisible(true); retry.setEnabled(true); shade.setVisible(true);
            retry.requestFocusInWindow();
        } else if(me.alive() && shade.isVisible()) {
            shade.setVisible(false); view.requestFocusInWindow();
        }
    }
    void lost(String reason) {
        disconnected=true; view.reset(); skillBar.update(List.of(), false);
        resultTitle.setFont(Theme.font(24,true)); resultTitle.setText("連線已中斷");
        resultText.setText(reason); retry.setVisible(false); shade.setVisible(true); roomInfo.setEnabled(false);
        roomLabel.setText("●  已離線"); leaveOverlay.requestFocusInWindow();
    }
    void reset() {
        view.reset(); scores.update(null,-1); skillBar.update(List.of(), false); disconnected=false;
        roomInfo.setEnabled(true); shade.setVisible(false);
    }
    private static final class SkillBar extends JPanel {
        private List<FrameState.Kind> inventory = List.of();
        private boolean boosting;

        SkillBar() {
            setOpaque(false);
            setPreferredSize(new Dimension(363, 40));
            setToolTipText("Q／W／E 使用對應技能。空白鍵加速，每秒消耗 1 節。");
            getAccessibleContext().setAccessibleName("技能欄與加速狀態");
        }
        void update(List<FrameState.Kind> skills, boolean active) {
            if (skills.equals(inventory) && active == boosting) return;
            inventory = skills;
            boosting = active;
            getAccessibleContext().setAccessibleDescription(
                    String.join("、", skills.stream().map(FoodGuide::name).toList()));
            repaint();
        }
        @Override protected void paintComponent(Graphics original) {
            super.paintComponent(original);
            Graphics2D g = (Graphics2D) original.create();
            Theme.smooth(g);
            int y = (getHeight() - 32) / 2;
            for (int i = 0; i < 3; i++) {
                int x = i * 82;
                boolean filled = i < inventory.size();
                g.setColor(filled ? Theme.PAPER : Theme.SOFT);
                g.fillRoundRect(x, y, 77, 32, 10, 10);
                g.setColor(Theme.LINE);
                g.drawRoundRect(x, y, 77, 32, 10, 10);
                g.setColor(filled ? Theme.INK : Theme.MUTED);
                g.setFont(Theme.latin(14, true));
                g.drawString("QWE".substring(i, i + 1), x + 8, y + 21);
                if (filled) {
                    FrameState.Kind kind = inventory.get(i);
                    ArenaView.paintFoodIcon(g, kind, x + 33, y + 16, 8);
                    g.setColor(Theme.INK);
                    g.setFont(Theme.font(12,true));
                    g.drawString(FoodGuide.name(kind), x + 46, y + 21);
                } else {
                    g.setColor(Theme.MUTED);
                    g.drawLine(x + 43, y + 16, x + 51, y + 16);
                }
            }
            g.setColor(boosting ? Theme.INK : Theme.PAPER);
            g.fillRoundRect(251, y, 108, 32, 10, 10);
            g.setColor(boosting ? Theme.GOLD : Theme.LINE);
            g.drawRoundRect(251, y, 108, 32, 10, 10);
            g.setColor(boosting ? Theme.GOLD : Theme.MUTED);
            g.setFont(Theme.font(12,true));
            g.drawString(boosting ? "空白鍵  加速中" : "空白鍵  加速", 262, y + 21);
            g.dispose();
        }
    }
    private static final class Scoreboard extends JPanel {
        private FrameState frame;
        private int myId;
        private List<FrameState.Snake> ranks=List.of();
        Scoreboard() {
            setPreferredSize(new Dimension(240,400)); setOpaque(false);
            getAccessibleContext().setAccessibleName("即時長度排行榜與場地雷達");
        }
        void update(FrameState value,int id) {
            frame=value; myId=id;
            ranks=frame==null ? List.of() : frame.snakes().stream().filter(FrameState.Snake::alive)
                    .sorted(Comparator.comparingInt(FrameState.Snake::length).reversed().thenComparingInt(FrameState.Snake::id)).toList();
            repaint();
        }
        @Override protected void paintComponent(Graphics original) {
            Graphics2D g=(Graphics2D)original.create(); Theme.smooth(g);
            int w=getWidth(),h=getHeight();
            g.setColor(Theme.PAPER); g.fillRoundRect(0,0,w-1,h-1,20,20);
            g.setColor(Theme.LINE); g.drawRoundRect(0,0,w-1,h-1,20,20);
            FrameState.Snake me=frame==null ? null : frame.snakes().stream().filter(s->s.id()==myId).findFirst().orElse(null);
            boolean compact=h<445;
            int scoreHeight=compact ? 63 : 90;
            g.setColor(Theme.INK); g.fillRoundRect(10,10,w-20,scoreHeight,15,15);
            Shape old=g.getClip(); g.clip(new RoundRectangle2D.Double(10,10,w-20,scoreHeight,15,15));
            g.setColor(new Color(255,255,255,22));
            for(int r:new int[]{22,37,52,67}) g.drawOval(w-35-r,75-r,2*r,2*r);
            g.setClip(old);
            g.setFont(Theme.font(13,true)); g.setColor(new Color(0xC8DECB)); g.drawString("我的長度",23,compact ? 29 : 35);
            g.setColor(Color.WHITE); g.setFont(Theme.latin(compact ? 30 : 44,true));
            String length=me==null ? "—" : Integer.toString(me.length());
            g.drawString(length,22,scoreHeight-9); int scoreWidth=g.getFontMetrics().stringWidth(length);
            g.setColor(Theme.GOLD); g.setFont(Theme.font(14,true)); g.drawString("節",30+scoreWidth,scoreHeight-10);
            int titleY=scoreHeight+43;
            g.setColor(Theme.INK); g.setFont(Theme.font(19,true)); g.drawString("長度排行榜",16,titleY);
            String live=(frame==null ? 0 : frame.snakes().size())+" / 5";
            g.setFont(Theme.latin(14,true)); g.setColor(Theme.MUTED); g.drawString(live,w-16-g.getFontMetrics().stringWidth(live),titleY-1);
            int y=titleY+12, rh=Math.min(36,Math.max(25,(h-268)/5));
            for (int i=0;i<ranks.size();i++) {
                FrameState.Snake s=ranks.get(i); int mid=y+rh/2;
                if(s.id()==myId) { g.setColor(Theme.SOFT); g.fillRoundRect(9,y,w-18,rh-2,10,10); }
                g.setFont(Theme.latin(14,true)); g.setColor(Theme.MUTED); g.drawString(""+(i+1),19,mid+5);
                g.setColor(Theme.snake(s.colorIndex())); g.fillOval(39,mid-8,17,17);
                g.setColor(Color.WHITE); g.fillOval(46,mid-7,7,7); g.fillOval(50,mid-1,7,7);
                g.setColor(Theme.INK); g.fillOval(49,mid-5,3,3); g.fillOval(53,mid+1,3,3);
                g.setFont(Theme.font(14,s.id()==myId)); g.drawString(Theme.fit(s.name(),g.getFontMetrics(),w-131),65,mid+5);
                String number=Integer.toString(s.length()); g.setFont(Theme.latin(16,true));
                g.drawString(number,w-20-g.getFontMetrics().stringWidth(number),mid+6); y+=rh;
            }
            if(ranks.isEmpty()) { g.setFont(Theme.font(13,false)); g.setColor(Theme.MUTED); g.drawString("等待玩家入場",17,y+18); }
            int divider=Math.max(189,y+14);
            g.setColor(Theme.LINE); g.drawLine(16,divider,w-16,divider);
            g.setColor(Theme.INK); g.setFont(Theme.font(16,true)); g.drawString("場地雷達",16,divider+27);
            g.setColor(Theme.MUTED); g.setFont(Theme.font(11,false));
            g.drawString("雙圈為你的位置",w-16-g.getFontMetrics().stringWidth("雙圈為你的位置"),divider+26);
            double top=divider+38, size=Math.min(Math.min(w-48,h-top-14),175);
            if(size>30) radar(g,w/2.0,top+(h-top-8)/2.0,size/2.0);
            g.dispose();
        }
        private void radar(Graphics2D g,double cx,double cy,double r) {
            Ellipse2D circle=new Ellipse2D.Double(cx-r,cy-r,r*2,r*2);
            g.setColor(new Color(0xEBF2E7)); g.fill(circle);
            g.setColor(new Color(0xCAD9C8));
            for(double f:new double[]{.36,.69,1.0}) g.draw(new Ellipse2D.Double(cx-r*f,cy-r*f,2*r*f,2*r*f));
            g.draw(new Line2D.Double(cx-r,cy,cx+r,cy)); g.draw(new Line2D.Double(cx,cy-r,cx,cy+r));
            if(frame==null) return;
            for(FrameState.Snake s:frame.snakes()) {
                if(!s.alive() || s.body().isEmpty()) continue;
                FrameState.Point head=s.body().get(0);
                double x=cx+r*head.x()/frame.radius(),y=cy+r*head.y()/frame.radius();
                if(s.id()==myId) { g.setColor(Theme.INK); g.draw(new Ellipse2D.Double(x-6,y-6,12,12)); }
                g.setColor(Theme.snake(s.colorIndex())); g.fill(new Ellipse2D.Double(x-3.5,y-3.5,7,7));
            }
        }
    }
}

/** The camera and controls consume authoritative snapshots in world coordinates. */
final class ArenaView extends JPanel {
    private static final Color FIELD = new Color(249, 250, 240);
    private static final Color OUTSIDE = new Color(225, 235, 219);
    private static final Color GRID = new Color(223, 230, 216);
    private static final int[] KEYS = {
        KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_UP, KeyEvent.VK_DOWN,
        KeyEvent.VK_Q, KeyEvent.VK_W, KeyEvent.VK_E, KeyEvent.VK_SPACE
    };
    private static final double FOOD_RADIUS = 4.5;
    private static final Color DANGER = new Color(0xC44336);
    private static final int GRID_STEP = 32;
    private static final double TICK_NANOS = 40_000_000.0;
    private static final Font NAME_FONT = Theme.font(12, true);

    private final DoubleConsumer steer;
    private final java.util.function.Consumer<Boolean> boost;
    private final java.util.function.IntConsumer useSkill;
    private final Timer animation;
    private final Set<Integer> pressed = new HashSet<>();
    private final WindowAdapter windowFocus = new WindowAdapter() {
        @Override public void windowLostFocus(WindowEvent e) { clearInput(); }
    };
    private Window window;
    private FrameState current;
    private FrameState previous;
    private FrameState.Snake player;
    private int myId = -1;
    private long receivedAt;
    private long animatedAt;
    private long lastMouseCommand;
    private double lastHeading = Double.NaN;
    private double cameraX;
    private double cameraY;
    private double blendNanos = TICK_NANOS;
    private boolean aiming;
    private int mouseX;
    private int mouseY;

    public ArenaView(DoubleConsumer steer, java.util.function.Consumer<Boolean> boost,
                     java.util.function.IntConsumer useSkill) {
        this.steer = steer;
        this.boost = boost;
        this.useSkill = useSkill;
        setOpaque(true);
        setBackground(FIELD);
        setPreferredSize(new Dimension(720, 540));
        setFocusable(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) { clearInput(); }
        });
        animation = new Timer(16, e -> animate());
        animation.setCoalesce(true);
        for (int key : KEYS) {
            bindKey(key, false);
            bindKey(key, true);
        }
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                aimAt(e);
            }
            @Override public void mouseMoved(MouseEvent e) { aimAt(e); }
            @Override public void mouseDragged(MouseEvent e) { aimAt(e); }
            @Override public void mouseExited(MouseEvent e) { aiming = false; }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addHierarchyListener(e -> {
            Window next = isShowing() ? SwingUtilities.getWindowAncestor(this) : null;
            if (next != window) {
                if (window != null) window.removeWindowFocusListener(windowFocus);
                window = next;
                if (window != null) window.addWindowFocusListener(windowFocus);
            }
            if (isShowing() && current != null) {
                animatedAt = System.nanoTime();
                animation.start();
            } else if (!isShowing()) {
                animation.stop();
                clearInput();
            }
        });
    }

    public void showFrame(FrameState frame, int myId) {
        if (frame == null) return;
        if (current != null && this.myId == myId && current.tick() == frame.tick()) return;
        boolean newSession = current == null || this.myId != myId || frame.tick() < current.tick();
        FrameState.Snake oldPlayer = player;
        previous = newSession ? null : current;
        current = frame;
        this.myId = myId;
        player = snakeWithId(frame, myId);
        receivedAt = System.nanoTime();
        blendNanos = previous == null ? TICK_NANOS
                : Math.min(3, Math.max(1, frame.tick() - previous.tick())) * TICK_NANOS;
        if (player != null && !player.body().isEmpty() &&
                (newSession || oldPlayer == null || (!oldPlayer.alive() && player.alive()))) {
            cameraX = player.body().get(0).x();
            cameraY = player.body().get(0).y();
            animatedAt = receivedAt;
            clearInput();
            requestFocusInWindow();
        }
        if (player == null || !player.alive()) clearInput();
        if (isShowing() && !animation.isRunning()) animation.start();
        repaint();
    }

    public void reset() {
        animation.stop();
        current = null;
        previous = null;
        player = null;
        myId = -1;
        cameraX = cameraY = 0;
        clearInput();
        repaint();
    }

    private void clearInput() {
        aiming = false;
        if (pressed.contains(KeyEvent.VK_SPACE)) boost.accept(false);
        pressed.clear();
        lastHeading = Double.NaN;
    }

    private void bindKey(int key, boolean released) {
        KeyStroke stroke = KeyStroke.getKeyStroke(key, 0, released);
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(stroke, stroke);
        getActionMap().put(stroke, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (player == null || !player.alive()) return;
                if (released) {
                    if (!pressed.remove(key)) return;
                } else if (!pressed.add(key)) return;
                if (key == KeyEvent.VK_SPACE) {
                    boost.accept(!released);
                    return;
                }
                if (key == KeyEvent.VK_Q || key == KeyEvent.VK_W || key == KeyEvent.VK_E) {
                    if (!released) useSkill.accept(key == KeyEvent.VK_Q ? 0 : key == KeyEvent.VK_W ? 1 : 2);
                    return;
                }
                aiming = false;
                int dx = direction(KeyEvent.VK_RIGHT) - direction(KeyEvent.VK_LEFT);
                int dy = direction(KeyEvent.VK_DOWN) - direction(KeyEvent.VK_UP);
                if (dx != 0 || dy != 0) sendHeading(Math.atan2(dy, dx));
            }
        });
    }

    private int direction(int arrow) {
        return pressed.contains(arrow) ? 1 : 0;
    }

    private void aimAt(MouseEvent e) {
        if (player == null || !player.alive()) return;
        pressed.remove(KeyEvent.VK_UP);
        pressed.remove(KeyEvent.VK_DOWN);
        pressed.remove(KeyEvent.VK_LEFT);
        pressed.remove(KeyEvent.VK_RIGHT);
        aiming = true;
        mouseX = e.getX();
        mouseY = e.getY();
    }

    private void sendHeading(double angle) {
        if (!Double.isNaN(lastHeading) && Math.cos(angle - lastHeading) > 0.99995) return;
        lastHeading = angle;
        steer.accept(angle);
    }

    private void animate() {
        if (current == null) return;
        long now = System.nanoTime();
        double elapsed = Math.min(0.10, (now - animatedAt) / 1.0e9);
        animatedAt = now;
        if (player != null && !player.body().isEmpty()) {
            FrameState.Snake old = interpolationSource(player);
            FrameState.Point head = pointAt(player, old, 0, blend(now));
            double follow = 1 - Math.exp(-elapsed * 15);
            cameraX += (head.x() - cameraX) * follow;
            cameraY += (head.y() - cameraY) * follow;
            if (aiming && player.alive() && now - lastMouseCommand >= TICK_NANOS) {
                double zoom = zoom();
                double dx = mouseX - getWidth() * 0.5 - (head.x() - cameraX) * zoom;
                double dy = mouseY - getHeight() * 0.5 - (head.y() - cameraY) * zoom;
                if (Math.hypot(dx, dy) >= 10) sendHeading(Math.atan2(dy, dx));
                lastMouseCommand = now;
            }
        }
        repaint();
    }

    private double blend(long now) {
        return Math.min(1.0, Math.max(0.0, (now - receivedAt) / blendNanos));
    }

    private double zoom() {
        return Math.max(0.98, Math.min(1.65, Math.min(getWidth() / 570.0, getHeight() / 420.0)));
    }

    private static FrameState.Snake snakeWithId(FrameState frame, int id) {
        if (frame != null) {
            for (FrameState.Snake snake : frame.snakes()) if (snake.id() == id) return snake;
        }
        return null;
    }

    private FrameState.Snake interpolationSource(FrameState.Snake snake) {
        FrameState.Snake old = snakeWithId(previous, snake.id());
        if (old == null || old.alive() != snake.alive() || old.body().isEmpty()) return null;
        return old;
    }

    private static FrameState.Point pointAt(FrameState.Snake snake, FrameState.Snake old, int i, double t) {
        FrameState.Point next = snake.body().get(i);
        if (old == null || t >= 1) return next;
        FrameState.Point from = old.body().get(Math.min(i, old.body().size() - 1));
        return new FrameState.Point(from.x() + t * (next.x() - from.x()), from.y() + t * (next.y() - from.y()));
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            Theme.smooth(g);
            double zoom = zoom();
            double halfWidth = getWidth() / (2.0 * zoom);
            double halfHeight = getHeight() / (2.0 * zoom);
            Rectangle2D viewport = new Rectangle2D.Double(cameraX - halfWidth, cameraY - halfHeight,
                    2 * halfWidth, 2 * halfHeight);
            g.translate(getWidth() * 0.5, getHeight() * 0.5);
            g.scale(zoom, zoom);
            g.translate(-cameraX, -cameraY);
            paintField(g, viewport, current == null ? 1050 : current.radius());
            if (current == null) return;
            double t = blend(System.nanoTime());
            if (player != null && player.alive() && !player.body().isEmpty()
                    && player.skills().contains(FrameState.Kind.THIEF)) {
                FrameState.Point head = pointAt(player, interpolationSource(player), 0, t);
                double r = FrameState.THIEF_RADIUS;
                g.setColor(new Color(33, 132, 108, 65));
                g.setStroke(new BasicStroke(1, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                        1, new float[]{2, 7}, 0));
                g.draw(new Ellipse2D.Double(head.x() - r, head.y() - r, 2 * r, 2 * r));
            }
            long dangerTick = Long.MAX_VALUE;
            for (FrameState.Bomb bomb : current.bombs()) {
                paintBomb(g, bomb, current.tick(), myId);
                if (bomb.explodeTick() > current.tick() && bomb.ownerId() != myId
                        && dangerTick > bomb.explodeTick() && overlapsBlast(player, bomb)) {
                    dangerTick = bomb.explodeTick();
                }
            }
            for (FrameState.Pellet food : current.food()) {
                if (viewport.intersects(food.x() - 10, food.y() - 10, 20, 20)) paintPellet(g, food);
            }
            for (FrameState.Snake snake : current.snakes()) {
                if (snake.alive() && snake.id() != myId) paintSnake(g, snake, t, viewport);
            }
            if (player != null) paintSnake(g, player, t, viewport);
            for (FrameState.Snake snake : current.snakes()) {
                if ((snake.alive() || snake.id() == myId) && !snake.body().isEmpty()) {
                    FrameState.Point head = pointAt(snake, interpolationSource(snake), 0, t);
                    paintName(g, snake, head, zoom);
                }
            }
            if (dangerTick != Long.MAX_VALUE) {
                Graphics2D screen = (Graphics2D) graphics.create();
                Theme.smooth(screen);
                paintWarning(screen, secondsLeft(dangerTick - current.tick()));
                screen.dispose();
            }
        } finally {
            g.dispose();
        }
    }

    private static void paintField(Graphics2D g, Rectangle2D view, double radius) {
        Ellipse2D arena = new Ellipse2D.Double(-radius, -radius, 2 * radius, 2 * radius);
        boolean borderVisible = !arena.contains(view);
        Graphics2D ground = (Graphics2D) g.create();
        if (borderVisible) {
            g.setColor(OUTSIDE);
            g.fill(view);
            ground.clip(arena);
            ground.setColor(FIELD);
            ground.fill(view);
        }
        int minX = (int) Math.floor(view.getMinX() / GRID_STEP) * GRID_STEP;
        int minY = (int) Math.floor(view.getMinY() / GRID_STEP) * GRID_STEP;
        ground.setStroke(new BasicStroke(0.7f));
        ground.setColor(GRID);
        for (int x = minX; x <= view.getMaxX(); x += GRID_STEP) {
            ground.draw(new Line2D.Double(x, view.getMinY(), x, view.getMaxY()));
        }
        for (int y = minY; y <= view.getMaxY(); y += GRID_STEP) {
            ground.draw(new Line2D.Double(view.getMinX(), y, view.getMaxX(), y));
        }
        ground.setColor(new Color(179, 195, 176));
        ground.setStroke(new BasicStroke(1.0f));
        int step = GRID_STEP * 4;
        for (int x = Math.floorDiv(minX, step) * step; x <= view.getMaxX(); x += step) {
            for (int y = Math.floorDiv(minY, step) * step; y <= view.getMaxY(); y += step) {
                ground.draw(new Line2D.Double(x - 3, y, x + 3, y));
                ground.draw(new Line2D.Double(x, y - 3, x, y + 3));
            }
        }
        ground.dispose();
        if (borderVisible) {
            g.setColor(new Color(121, 147, 120));
            g.setStroke(new BasicStroke(2.3f));
            g.draw(arena);
            g.setColor(new Color(168, 184, 150));
            g.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1, new float[]{5, 7}, 0));
            g.draw(new Ellipse2D.Double(7 - radius, 7 - radius, 2 * radius - 14, 2 * radius - 14));
        }
    }

    private static void paintPellet(Graphics2D g, FrameState.Pellet food) {
        boolean normal = food.kind() == FrameState.Kind.NORMAL;
        double r = normal ? FOOD_RADIUS : 2 * FOOD_RADIUS;
        double x = food.x(), y = food.y();
        disc(g, x, y + 1.4, r, new Color(51, 74, 52, 28));
        if (normal) {
            disc(g, x, y, r, Theme.snake(food.colorIndex()));
            disc(g, x - r * 0.28, y - r * 0.35, r * 0.27, new Color(255, 255, 255, 162));
        } else {
            paintFoodIcon(g, food.kind(), x, y, r);
        }
    }

    /** Draws the same symbol on the field, in the skill bar, and in the guide. */
    static void paintFoodIcon(Graphics2D original, FrameState.Kind kind, double x, double y, double radius) {
        Graphics2D g = (Graphics2D) original.create();
        g.translate(x, y);
        g.scale(radius / 10, radius / 10);
        Color color = switch (kind) {
            case NORMAL -> Theme.snake(0);
            case MEGA_GROWTH -> new Color(0xBC791A);
            case DIET_FOOD -> new Color(0x347FB5);
            case BOMB -> DANGER;
            case GHOST_POWER -> new Color(0x7864B1);
            case THIEF -> new Color(0x247E70);
        };
        disc(g, 0, 0, 10, color.darker());
        disc(g, 0, -0.45, 9.2, color);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (kind) {
            case NORMAL -> disc(g, -2.5, -3, 2.1, new Color(255, 255, 255, 165));
            case MEGA_GROWTH, DIET_FOOD -> {
                g.drawLine(-4, 0, 4, 0);
                if (kind == FrameState.Kind.MEGA_GROWTH) g.drawLine(0, -4, 0, 4);
            }
            case BOMB -> {
                disc(g, -1, 1.1, 4.4, Color.WHITE);
                g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new QuadCurve2D.Double(0.3, -1.9, 1, -6.7, 4, -5.3));
                disc(g, 4.6, -5.1, 1.1, Theme.GOLD);
            }
            case GHOST_POWER -> {
                Path2D ghost = new Path2D.Double();
                ghost.moveTo(-5.2, 4.5);
                ghost.lineTo(-5.2, -0.8);
                ghost.curveTo(-5.2, -7.6, 5.2, -7.6, 5.2, -0.8);
                ghost.lineTo(5.2, 4.5);
                ghost.lineTo(2.5, 2.8);
                ghost.lineTo(0, 4.8);
                ghost.lineTo(-2.5, 2.8);
                ghost.closePath();
                g.fill(ghost);
                disc(g, -1.9, -1, 0.85, color);
                disc(g, 1.9, -1, 0.85, color);
            }
            case THIEF -> {
                Path2D mask = new Path2D.Double();
                mask.moveTo(-6.1, -3.3);
                mask.quadTo(0, -1.6, 6.1, -3.3);
                mask.quadTo(5.2, 5.7, 0, 2.2);
                mask.quadTo(-5.2, 5.7, -6.1, -3.3);
                g.fill(mask);
                disc(g, -3.0, 0, 1.0, color);
                disc(g, 3.0, 0, 1.0, color);
            }
        }
        g.dispose();
    }

    private static boolean overlapsBlast(FrameState.Snake snake, FrameState.Bomb bomb) {
        if (snake == null || !snake.alive()) return false;
        double squared = FrameState.BOMB_RADIUS * FrameState.BOMB_RADIUS;
        for (FrameState.Point bead : snake.body()) {
            double dx = bead.x() - bomb.x(), dy = bead.y() - bomb.y();
            if (dx * dx + dy * dy <= squared) return true;
        }
        return false;
    }

    private static long secondsLeft(long ticks) {
        return (ticks + FrameState.TICKS_PER_SECOND - 1) / FrameState.TICKS_PER_SECOND;
    }

    private static void paintBomb(Graphics2D original, FrameState.Bomb bomb, long tick, int myId) {
        Graphics2D g = (Graphics2D) original.create();
        g.translate(bomb.x(), bomb.y());
        double r = FrameState.BOMB_RADIUS;
        long remaining = bomb.explodeTick() - tick;
        Ellipse2D range = new Ellipse2D.Double(-r, -r, 2 * r, 2 * r);
        if (remaining > 0) {
            boolean own = bomb.ownerId() == myId;
            g.setColor(own ? new Color(174, 133, 54, 14) : new Color(213, 71, 44, 20));
            g.fill(range);
            g.setColor(own ? new Color(168, 129, 57, 155) : new Color(195, 65, 47, 178));
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                    1, new float[]{6, 5}, 0));
            g.draw(range);
            paintFoodIcon(g, FrameState.Kind.BOMB, 0, 0, 12);
            String label = secondsLeft(remaining) + " s";
            g.setFont(Theme.latin(11, true));
            int width = g.getFontMetrics().stringWidth(label) + 9;
            g.setColor(Theme.INK);
            g.fill(new RoundRectangle2D.Double(-width / 2.0, 14, width, 15, 7, 7));
            g.setColor(Color.WHITE);
            g.drawString(label, (float) (4.5 - width / 2.0), 25.5f);
        } else {
            double age = Math.min(1, -remaining / 8.0);
            g.setColor(new Color(252, 165, 53, (int) (86 * (1 - age))));
            g.fill(range);
            double wave = r * (0.12 + 0.88 * age);
            g.setStroke(new BasicStroke(5));
            g.setColor(new Color(202, 76, 44, (int) (210 * (1 - age))));
            g.draw(new Ellipse2D.Double(-wave, -wave, wave * 2, wave * 2));
        }
        g.dispose();
    }

    private void paintWarning(Graphics2D g, long seconds) {
        int width = getWidth(), height = getHeight();
        g.setColor(DANGER);
        g.setStroke(new BasicStroke(4));
        g.drawRoundRect(2, 2, width - 5, height - 5, 17, 17);
        String label = "炸彈倒數　" + seconds + " 秒";
        g.setFont(Theme.font(14, true));
        FontMetrics fm = g.getFontMetrics();
        int boxWidth = fm.stringWidth(label) + 26;
        int x = width - boxWidth - 13;
        g.fillRoundRect(x, 13, boxWidth, 32, 12, 12);
        g.setColor(Color.WHITE);
        g.drawString(label, x + 13, 13 + (32 - fm.getHeight()) / 2 + fm.getAscent());
    }

    private void paintSnake(Graphics2D original, FrameState.Snake snake, double t, Rectangle2D view) {
        List<FrameState.Point> body = snake.body();
        if (body.isEmpty()) return;
        Graphics2D g = (Graphics2D) original.create();
        FrameState.Snake old = interpolationSource(snake);
        Color color = Theme.snake(snake.colorIndex());
        boolean ghost = snake.isGhostAt(current.tick());
        if (ghost) {
            color = new Color((color.getRed() + 210) / 2, (color.getGreen() + 200) / 2,
                    (color.getBlue() + 255) / 2);
            g.setComposite(AlphaComposite.SrcOver.derive(0.54f));
        }
        Color edge = color.darker();
        Color shade = new Color(30, 55, 39, 32);
        for (int i = body.size() - 1; i >= 1; i--) {
            FrameState.Point point = pointAt(snake, old, i, t);
            double x = point.x(), y = point.y();
            if (!view.intersects(x - 13, y - 13, 26, 26)) continue;
            double tail = Math.min(3, body.size() - i - 1);
            double r = 7.3 + tail * 0.85;
            if (ghost) {
                disc(g, x, y, r, color);
                g.setColor(new Color(105, 85, 158, 130));
                g.setStroke(new BasicStroke(0.8f));
                g.draw(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
            } else {
                disc(g, x + 0.3, y + 1.9, r + 0.4, shade);
                disc(g, x, y, r, edge);
                disc(g, x, y - 0.6, r - 0.4, color);
                disc(g, x - r * 0.23, y - r * 0.32, r * 0.53, new Color(255, 255, 255, 22));
            }
        }
        FrameState.Point head = pointAt(snake, old, 0, t);
        double angle = snake.angle();
        if (old != null && t < 1) {
            double turn = angle - old.angle();
            angle = old.angle() + Math.atan2(Math.sin(turn), Math.cos(turn)) * t;
        }
        Graphics2D face = (Graphics2D) g.create();
        face.translate(head.x(), head.y());
        disc(face, 0.4, 1.7, 11.4, shade);
        disc(face, 0, 0, 11.2, edge);
        disc(face, 0, -0.4, 10.8, color);
        face.rotate(angle);
        for (int side : new int[]{-1, 1}) {
            if (snake.boosting() && snake.alive()) {
                face.setColor(new Color(91, 124, 95, 151));
                face.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                face.draw(new Line2D.Double(-21, side * 13, -10, side * 13));
                face.draw(new Line2D.Double(-15, side * 16, -9, side * 16));
            }
            double eyeY = side * 6.8;
            disc(face, 4, eyeY, 5.1, new Color(255, 254, 246));
            disc(face, 5.8, eyeY + side * 0.4, 2.55, Theme.INK);
            disc(face, 6.4, eyeY - 0.55, 0.85, Color.WHITE);
        }
        face.dispose();
        g.dispose();
    }

    private static void disc(Graphics2D g, double x, double y, double r, Color color) {
        g.setColor(color);
        g.fill(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
    }

    private void paintName(Graphics2D g, FrameState.Snake snake, FrameState.Point head, double zoom) {
        Graphics2D label = (Graphics2D) g.create();
        label.translate(head.x(), head.y() - 15);
        label.scale(1 / zoom, 1 / zoom);
        label.setFont(NAME_FONT);
        FontMetrics fm = label.getFontMetrics();
        boolean ghost = snake.isGhostAt(current.tick());
        String name = snake.name();
        if (ghost) {
            long seconds = 1 + (snake.ghostUntilTick() - current.tick()) / FrameState.TICKS_PER_SECOND;
            name += " · 穿透 " + seconds + " 秒";
        }
        int w = fm.stringWidth(name) + 18;
        int h = fm.getHeight() + 4;
        double x = -w / 2.0, y = -h - 3;
        boolean local = snake.id() == myId;
        Color fill = ghost ? new Color(0x655493) : local ? Theme.INK : new Color(255, 255, 248, 244);
        label.setColor(fill);
        label.fill(new RoundRectangle2D.Double(x, y, w, h, 11, 11));
        Path2D pointer = new Path2D.Double();
        pointer.moveTo(-4, -4);
        pointer.lineTo(0, 0);
        pointer.lineTo(4, -4);
        pointer.closePath();
        label.fill(pointer);
        label.setColor(local || ghost ? Color.WHITE : Theme.INK);
        label.drawString(name, (float) x + 9, (float) y + fm.getAscent() + 2);
        label.dispose();
    }
}

/** Lobby artwork also previews the name tag used in the match. */
final class ArenaPoster extends JPanel {
    private final List<Point2D.Double> trail = new ArrayList<>();
    private String nickname = "";
    ArenaPoster() {
        setOpaque(false);
        Path2D curve = new Path2D.Double();
        curve.moveTo(-57, 458);
        curve.curveTo(48, 457, 162, 419, 140, 350);
        curve.curveTo(117, 286, 171, 244, 222, 264);
        curve.curveTo(287, 287, 291, 360, 373, 360);
        curve.curveTo(470, 360, 482, 279, 416, 211);
        PathIterator it = curve.getPathIterator(null, 0.3);
        double[] pos = new double[6];
        double lastX = 0, lastY = 0, gap = 0;
        while (!it.isDone()) {
            int type = it.currentSegment(pos);
            if (type == PathIterator.SEG_MOVETO) trail.add(new Point2D.Double(pos[0], pos[1]));
            else {
                double length = Point2D.distance(lastX,lastY,pos[0],pos[1]);
                for (double d = 17 - gap; d <= length; d += 17)
                    trail.add(new Point2D.Double(lastX + (pos[0]-lastX)*d/length, lastY+(pos[1]-lastY)*d/length));
                gap = (gap + length) % 17;
            }
            lastX=pos[0]; lastY=pos[1]; it.next();
        }
        trail.add(new Point2D.Double(lastX,lastY));
    }
    void setNickname(String name) { nickname=name.strip(); repaint(); }
    @Override protected void paintComponent(Graphics original) {
        Graphics2D g = (Graphics2D) original.create();
        Theme.smooth(g);
        int w = getWidth(), h = getHeight();
        Shape card = new RoundRectangle2D.Double(0.5,0.5,w-1,h-1,26,26);
        g.clip(card);
        g.setPaint(new GradientPaint(0,0,new Color(0xF1F6E8),w,h,new Color(0xDCEEDC)));
        g.fillRect(0,0,w,h);
        double scale = Math.min(w/580.0, h/465.0);
        double ox = (w - 580*scale) * 0.5, oy = (h - 465*scale) * 0.58;
        int step = Math.max(20,(int)Math.round(31*scale));
        g.setColor(new Color(74,121,87,25));
        for (int x = 12; x < w; x += step) g.drawLine(x,0,x,h);
        for (int y = 12; y < h; y += step) g.drawLine(0,y,w,y);
        Graphics2D a = (Graphics2D) g.create();
        a.translate(ox,oy); a.scale(scale,scale);
        // The edge of the circular playfield sweeps through the far corner.
        a.setStroke(new BasicStroke(2f,0,0,1,new float[]{7,8},0));
        a.setColor(new Color(95,143,110,70));
        a.draw(new Ellipse2D.Double(-385,-150,1040,1040));
        for (int[] cross : new int[][]{{87,174},{338,143},{39,354},{260,411},{544,245}}) {
            a.setColor(new Color(122,157,124,110)); a.setStroke(new BasicStroke(1.5f));
            a.drawLine(cross[0]-4,cross[1],cross[0]+4,cross[1]);
            a.drawLine(cross[0],cross[1]-4,cross[0],cross[1]+4);
        }
        double[][] dots={{62,238,5,1},{101,208,8,3},{291,192,5,2},{540,105,6,4},
                {330,244,8,3},{505,300,7,1},{232,349,6,4},{70,328,6,0},{334,416,5,2},
                {517,388,8,3},{463,439,4,0},{28,112,4,2},{546,190,5,2}};
        for (double[] dot : dots) {
            double x=dot[0],y=dot[1],r=dot[2];
            a.setColor(new Color(42,81,58,20)); a.fill(new Ellipse2D.Double(x-r,y-r+2,2*r,2*r));
            a.setColor(Theme.snake((int)dot[3])); a.fill(new Ellipse2D.Double(x-r,y-r,2*r,2*r));
            a.setColor(new Color(255,255,255,145)); a.fill(new Ellipse2D.Double(x-r*.48,y-r*.6,r*.65,r*.42));
        }
        for (int i=0;i<trail.size();i++) {
            Point2D p=trail.get(i); double r=radius(i);
            a.setColor(new Color(48,85,57,29));
            a.fill(new Ellipse2D.Double(p.getX()-r+2,p.getY()-r+6,r*2,r*2));
        }
        for (int i=0;i<trail.size();i++) {
            Point2D p=trail.get(i); float x=(float)p.getX(), y=(float)p.getY(), r=(float)radius(i);
            a.setPaint(new RadialGradientPaint(x-r*.31f,y-r*.43f,r*1.55f,
                    new float[]{0,.62f,1},new Color[]{new Color(0xFF8766),new Color(0xEF684B),new Color(0xC64633)}));
            a.fill(new Ellipse2D.Double(x-r,y-r,r*2,r*2));
            if (i%3==0 && i<trail.size()-2) {
                a.setColor(new Color(255,209,170,125));
                a.fill(new Ellipse2D.Double(x-r*.30,y-r*.70,5,3));
            }
        }
        Graphics2D face=(Graphics2D)a.create();
        face.translate(416,211); face.rotate(-2.32);
        for(int side : new int[]{-1,1}) {
            face.setColor(new Color(130,66,45,75)); face.fillOval(2,side*16-11,26,24);
            face.setColor(Theme.PAPER); face.fillOval(3,side*16-13,25,24);
            face.setColor(new Color(0x263C32)); face.fillOval(15,side*16-8,11,13);
            face.setColor(Color.WHITE); face.fillOval(20,side*16-6,4,4);
        }
        face.dispose();
        String tag=nickname.isEmpty() ? "你的名字" : nickname;
        a.setFont(Theme.font(14,true));
        FontMetrics fm=a.getFontMetrics(); tag=Theme.fit(tag,fm,145);
        int tw=fm.stringWidth(tag)+24;
        a.setColor(Theme.INK); a.fillRoundRect(412-tw/2,139,tw,29,16,16);
        a.fillPolygon(new int[]{407,417,412},new int[]{166,166,173},3);
        a.setColor(Color.WHITE); a.drawString(tag,412-fm.stringWidth(tag)/2,159);
        a.dispose();
        int inset = Math.max(21,Math.min(40,w/15));
        g.setFont(Theme.font(13,true)); g.setColor(Theme.MUTED);
        g.drawString("場 地 預 覽",inset, inset+6);
        g.setFont(Theme.font(Math.min(44,Math.max(30,w/13)),true)); g.setColor(Theme.INK);
        g.drawString("圓形競技場",inset,inset+59);
        // Compact occupancy tag, styled like a printed ticket.
        int by = h-45;
        g.setColor(new Color(255,254,249,240)); g.fillRoundRect(w-127,by,107,29,29,29);
        g.setColor(Theme.LINE); g.drawRoundRect(w-127,by,107,29,29,29);
        g.setColor(new Color(0x368261)); g.fillOval(w-115,by+10,8,8);
        g.setFont(Theme.font(12,true)); g.setColor(Theme.INK); g.drawString("1–5 人同場",w-100,by+19);
        g.setClip(null); g.setColor(Theme.LINE); g.draw(card);
        g.dispose();
    }
    private double radius(int i) {
        return i==trail.size()-1 ? 30 : 16+11.0*i/(trail.size()-1);
    }
}

final class Theme {
    static final Color INK = new Color(0x193E34), MUTED = new Color(0x526C60),
            CANVAS = new Color(0xF6F6EE), PAPER = new Color(0xFFFEF9),
            LINE = new Color(0xD2DBCD), SOFT = new Color(0xE9EFE3), GOLD = new Color(0xF6D36C);
    static final Color[] SNAKES = {new Color(0xE95D43), new Color(0x327DDB),
            new Color(0x4CA982), new Color(0xE5A629), new Color(0x9A74D1)};
    private static final Set<String> FAMILIES = new HashSet<>(Arrays.asList(
            GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
    private static final String UI = first("Microsoft JhengHei UI", "Microsoft JhengHei", "PingFang TC",
            "Noto Sans CJK TC", "Dialog");
    private static final String LATIN = first("Bahnschrift", "Inter", "Arial", "Dialog");
    private Theme() {}

    private static String first(String... options) {
        return Arrays.stream(options).filter(FAMILIES::contains).findFirst().orElse("Dialog");
    }
    static Font font(int size, boolean bold) { return new Font(UI, bold ? Font.BOLD : Font.PLAIN, size); }
    static Font latin(int size, boolean bold) { return new Font(LATIN, bold ? Font.BOLD : Font.PLAIN, size); }
    static Color snake(int index) { return SNAKES[Math.floorMod(index, SNAKES.length)]; }
    static void smooth(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }
    static JPanel clear(LayoutManager layout) {
        JPanel p = new JPanel(layout);
        p.setOpaque(false);
        return p;
    }
    static JLabel label(String text, int size, boolean bold, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(font(size, bold));
        label.setForeground(color);
        return label;
    }
    static class Card extends JPanel {
        final Color fill, outline;
        final int arc;
        Card(LayoutManager layout, Color fill, Color outline, int arc) {
            super(layout);
            this.fill = fill; this.outline = outline; this.arc = arc;
            setOpaque(false);
        }
        @Override protected void paintComponent(Graphics original) {
            Graphics2D g = (Graphics2D) original.create();
            smooth(g);
            g.setColor(fill);
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            if (outline != null) {
                g.setColor(outline);
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            }
            g.dispose();
            super.paintComponent(original);
        }
    }
    static final class Button extends JButton {
        private final boolean primary;
        Button(String text, boolean primary) {
            super(text);
            this.primary = primary;
            setOpaque(false);
            setBorder(new EmptyBorder(8, 14, 8, 14));
            setFocusPainted(false);
            setContentAreaFilled(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFont(font(15, true));
            setForeground(primary ? Color.WHITE : INK);
        }
        @Override protected void paintComponent(Graphics original) {
            Graphics2D g = (Graphics2D) original.create();
            smooth(g);
            Color c = primary ? INK : PAPER;
            if (!isEnabled()) c = primary ? MUTED : SOFT;
            else if (getModel().isPressed()) c = primary ? INK.darker() : LINE;
            else if (getModel().isRollover()) c = primary ? new Color(0x285C4C) : SOFT;
            g.setColor(c);
            g.fillRoundRect(1, 1, getWidth()-3, getHeight()-3, 13, 13);
            g.setColor(isFocusOwner() ? new Color(0xC28A21) : primary ? INK : LINE);
            g.setStroke(new BasicStroke(isFocusOwner() ? 2f : 1f));
            g.drawRoundRect(1, 1, getWidth()-3, getHeight()-3, 13, 13);
            g.dispose();
            super.paintComponent(original);
        }
    }
    static final class Field extends JTextField {
        private final String placeholder;
        Field(String placeholder, String value) {
            super(value);
            this.placeholder = placeholder;
            setOpaque(false);
            setBorder(new EmptyBorder(7, 12, 7, 12));
            setFont(font(15, false)); setForeground(INK); setCaretColor(INK);
            setSelectionColor(new Color(0xCEE5D4));
            setPreferredSize(new Dimension(100, 42));
            addFocusListener(new java.awt.event.FocusAdapter() {
                @Override public void focusGained(java.awt.event.FocusEvent e) { repaint(); }
                @Override public void focusLost(java.awt.event.FocusEvent e) { repaint(); }
            });
        }
        @Override protected void paintComponent(Graphics original) {
            Graphics2D g = (Graphics2D) original.create();
            smooth(g); g.setColor(PAPER);
            g.fillRoundRect(1, 1, getWidth()-3, getHeight()-3, 12, 12);
            g.setColor(isFocusOwner() ? new Color(0x438567) : LINE);
            g.setStroke(new BasicStroke(isFocusOwner() ? 1.7f : 1f));
            g.drawRoundRect(1, 1, getWidth()-3, getHeight()-3, 12, 12);
            if (getText().isEmpty()) {
                g.setFont(getFont()); g.setColor(MUTED);
                g.drawString(placeholder, 13, (getHeight()-g.getFontMetrics().getHeight())/2+g.getFontMetrics().getAscent());
            }
            g.dispose();
            super.paintComponent(original);
        }
    }
    static BufferedImage logo(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        smooth(g); g.scale(size/48.0,size/48.0);
        g.setColor(GOLD); g.fillRoundRect(0,0,48,48,14,14);
        Path2D s = new Path2D.Double();
        s.moveTo(9,33); s.curveTo(13,41,25,37,23,30); s.curveTo(21,25,14,27,15,21);
        s.curveTo(16,14,25,16,30,17);
        g.setStroke(new BasicStroke(6.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(INK); g.draw(s); g.fillOval(24,9,16,16);
        g.setColor(PAPER); g.fillOval(30,9,7,7); g.fillOval(34,14,7,7);
        g.setColor(INK); g.fillOval(34,11,3,3); g.fillOval(38,16,3,3);
        g.dispose();
        return image;
    }
    static String fit(String value, FontMetrics fm, int maxWidth) {
        if (fm.stringWidth(value) <= maxWidth) return value;
        int end = value.length();
        while (end > 0 && fm.stringWidth(value.substring(0,end) + "…") > maxWidth)
            end = value.offsetByCodePoints(end, -1);
        return value.substring(0,end) + "…";
    }
}
