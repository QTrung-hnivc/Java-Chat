import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Multi-user chat server with a small Swing console.
 *
 * Protocol: one UTF-8 line per message, every free-text field base64 so that
 * spaces or Vietnamese diacritics can never break the framing.
 *
 *   client -> server : HELLO <name> | MSG <text> | TYPING <0|1> | QUIT
 *   server -> client : WELCOME <name> <room> | ROSTER <name>...
 *                      MSG <from> <text> <ts> | SYS <text> | TYPING <name> <0|1>
 *
 * Headless mode (no window), useful on a spare machine:
 *   java ChatServer --console 5050
 */
public final class ChatServer extends JFrame {

    private static final Color BG = new Color(0x12141A);
    private static final Color PANEL = new Color(0x161A22);
    private static final Color LINE = new Color(0x262C3A);
    private static final Color TEXT = new Color(0xE6EAF2);
    private static final Color MUTED = new Color(0x8B93A3);
    private static final Color GREEN = new Color(0x3ECF8E);
    private static final Color RED = new Color(0xFF6B6B);
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JTextField portField = new JTextField("5050");
    private final RoundButton toggle = new RoundButton("Bật server");
    private final JLabel status = new JLabel("● Chưa chạy");
    private final JLabel count = new JLabel("0 người online");
    private final JLabel addr = new JLabel("—");
    private final JTextArea logArea = new JTextArea();
    private final DefaultListModel<String> roster = new DefaultListModel<>();
    private final JList<String> rosterList = new JList<>(roster);

    private Hub hub;

    private ChatServer() {
        super("Java Chat · Server");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(780, 520));
        setSize(880, 570);
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBackground(BG);
        root.setBorder(new EmptyBorder(16, 18, 16, 18));
        root.add(header(), BorderLayout.NORTH);
        root.add(body(), BorderLayout.CENTER);
        root.add(footer(), BorderLayout.SOUTH);
        setContentPane(root);

        toggle.addActionListener(e -> toggleServer());
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (hub != null) hub.stop();
            }
        });
    }

    private JComponent header() {
        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("JAVA CHAT · SERVER");
        title.setFont(new Font("Segoe UI", Font.BOLD, 18));
        title.setForeground(TEXT);

        JLabel sub = new JLabel("Máy này giữ phòng chat — những máy khác kết nối vào địa chỉ bên dưới");
        sub.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        sub.setForeground(MUTED);

        status.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        status.setForeground(MUTED);

        left.add(title);
        left.add(Box.createVerticalStrut(3));
        left.add(sub);
        left.add(Box.createVerticalStrut(6));
        left.add(status);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        JLabel pl = new JLabel("Cổng");
        pl.setForeground(MUTED);
        styleField(portField, 78);
        right.add(pl);
        right.add(portField);
        right.add(toggle);

        JPanel p = new JPanel(new BorderLayout(12, 0));
        p.setOpaque(false);
        p.add(left, BorderLayout.WEST);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    private JComponent body() {
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setBackground(new Color(0x0F1116));
        logArea.setForeground(new Color(0xC9D1E0));
        logArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        logArea.setBorder(new EmptyBorder(10, 12, 10, 12));

        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createLineBorder(LINE));
        logScroll.getVerticalScrollBar().setUI(new SlimBar());
        logScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        JPanel p = new JPanel(new BorderLayout(14, 0));
        p.setOpaque(false);
        p.add(logScroll, BorderLayout.CENTER);
        p.add(side(), BorderLayout.EAST);
        return p;
    }

    private JComponent side() {
        JLabel title = new JLabel("NGƯỜI DÙNG");
        title.setFont(new Font("Segoe UI", Font.BOLD, 11));
        title.setForeground(MUTED);

        count.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        count.setForeground(MUTED);

        rosterList.setBackground(PANEL);
        rosterList.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        rosterList.setFixedCellHeight(28);
        rosterList.setSelectionBackground(PANEL);
        rosterList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean sel, boolean focus) {
                JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, false, false);
                l.setText("<html><font color='#3ECF8E'>●</font>&nbsp; " + value + "</html>");
                l.setOpaque(false);
                l.setForeground(TEXT);
                return l;
            }
        });

        JScrollPane sc = new JScrollPane(rosterList);
        sc.setBorder(null);
        sc.getViewport().setBackground(PANEL);
        sc.getVerticalScrollBar().setUI(new SlimBar());

        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBackground(PANEL);
        p.setPreferredSize(new Dimension(220, 10));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LINE), new EmptyBorder(12, 12, 12, 12)));
        p.add(title, BorderLayout.NORTH);
        p.add(sc, BorderLayout.CENTER);
        p.add(count, BorderLayout.SOUTH);
        return p;
    }

    private JComponent footer() {
        JLabel cap = new JLabel("ĐỊA CHỈ LAN");
        cap.setFont(new Font("Segoe UI", Font.BOLD, 11));
        cap.setForeground(MUTED);

        addr.setFont(new Font("Consolas", Font.BOLD, 15));
        addr.setForeground(GREEN);

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.add(cap);
        left.add(Box.createVerticalStrut(2));
        left.add(addr);

        RoundButton copy = new RoundButton("Sao chép");
        copy.setPreferredSize(new Dimension(110, 34));
        copy.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(addr.getText()), null);
            copy.setText("Đã chép");
            javax.swing.Timer t = new javax.swing.Timer(1200, ev -> copy.setText("Sao chép"));
            t.setRepeats(false);
            t.start();
        });

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setOpaque(false);
        right.add(copy);

        JPanel p = new JPanel(new BorderLayout(10, 0));
        p.setBackground(PANEL);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LINE), new EmptyBorder(10, 14, 10, 14)));
        p.add(left, BorderLayout.WEST);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    private void toggleServer() {
        if (hub != null) stopServer();
        else startServer();
    }

    private void startServer() {
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            setStatus("Cổng không hợp lệ (1–65535)", RED);
            return;
        }
        Hub h = new Hub(port, this::appendLog, this::refreshRoster);
        try {
            h.start();
        } catch (IOException ex) {
            setStatus("Không mở được cổng " + port + " — có thể đang bị chương trình khác chiếm", RED);
            appendLog("Lỗi: " + ex.getMessage());
            return;
        }
        hub = h;
        toggle.setText("Tắt server");
        portField.setEnabled(false);
        setStatus("Đang chạy · cổng " + h.boundPort(), GREEN);
        addr.setText(lanIp() + ":" + h.boundPort());
        refreshRoster();
    }

    private void stopServer() {
        if (hub == null) return;
        hub.stop();
        hub = null;
        toggle.setText("Bật server");
        portField.setEnabled(true);
        setStatus("Đã dừng", MUTED);
        addr.setText("—");
        roster.clear();
        count.setText("0 người online");
    }

    private void setStatus(String text, Color color) {
        status.setText("● " + text);
        status.setForeground(color);
    }

    private void appendLog(String s) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> appendLog(s));
            return;
        }
        logArea.append(LocalTime.now().format(HMS) + "  " + s + "\n");
        if (logArea.getDocument().getLength() > 300_000) {
            logArea.setText(logArea.getText().substring(150_000));
        }
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void refreshRoster() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::refreshRoster);
            return;
        }
        roster.clear();
        if (hub != null) for (String n : hub.names()) roster.addElement(n);
        count.setText(roster.size() + " người online");
    }

    private static void styleField(JTextField f, int width) {
        f.setPreferredSize(new Dimension(width, 34));
        f.setBackground(new Color(0x0F1116));
        f.setForeground(TEXT);
        f.setCaretColor(TEXT);
        f.setFont(new Font("Consolas", Font.BOLD, 14));
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LINE), new EmptyBorder(4, 8, 4, 8)));
        f.setHorizontalAlignment(JTextField.CENTER);
    }

    private static String lanIp() {
        return Hub.lanIp();
    }

    private static final class RoundButton extends JButton {
        private boolean hover;

        RoundButton(String text) {
            super(text);
            setFont(new Font("Segoe UI", Font.BOLD, 13));
            setForeground(Color.WHITE);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(120, 34));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(hover ? new Color(0x3B82F6) : new Color(0x2563EB));
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            g.dispose();
            super.paintComponent(g0);
        }
    }

    private static final class SlimBar extends BasicScrollBarUI {
        @Override protected void configureScrollBarColors() {
            thumbColor = new Color(0x333B4D);
            trackColor = new Color(0x0F1116);
        }

        @Override protected JButton createDecreaseButton(int o) { return zero(); }
        @Override protected JButton createIncreaseButton(int o) { return zero(); }

        private JButton zero() {
            JButton b = new JButton();
            Dimension d = new Dimension(0, 0);
            b.setPreferredSize(d);
            b.setMinimumSize(d);
            b.setMaximumSize(d);
            return b;
        }

        @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
            g.setColor(trackColor);
            g.fillRect(r.x, r.y, r.width, r.height);
        }

        @Override protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(thumbColor);
            g2.fillRoundRect(r.x + 3, r.y + 2, Math.max(4, r.width - 6), Math.max(8, r.height - 4), 8, 8);
            g2.dispose();
        }
    }

    public static void main(String[] args) {
        UIManager.put("Label.font", new Font("Segoe UI", Font.PLAIN, 13));
        UIManager.put("Button.font", new Font("Segoe UI", Font.PLAIN, 13));
        UIManager.put("TextField.font", new Font("Segoe UI", Font.PLAIN, 13));
        UIManager.put("TextArea.font", new Font("Segoe UI", Font.PLAIN, 13));
        UIManager.put("List.font", new Font("Segoe UI", Font.PLAIN, 13));
        UIManager.put("OptionPane.messageFont", new Font("Segoe UI", Font.PLAIN, 13));

        if (args.length > 0 && args[0].equalsIgnoreCase("--console")) {
            try {
                // keep Vietnamese log lines readable when stdout is piped or redirected
                System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
                System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
            } catch (Exception ignored) { }
            int port = 5050;
            if (args.length > 1) {
                try { port = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) { }
            }
            Hub h = new Hub(port,
                    s -> System.out.println(LocalTime.now().format(HMS) + "  " + s),
                    () -> { });
            try {
                h.start();
            } catch (IOException ex) {
                System.err.println("Không mở được cổng " + port + ": " + ex.getMessage());
                System.exit(1);
            }
            System.out.println("Chat server đang chạy tại " + Hub.lanIp() + ":" + h.boundPort());
            System.out.println("Ctrl+C để dừng.");
            try { Thread.currentThread().join(); } catch (InterruptedException ignored) { }
            return;
        }

        SwingUtilities.invokeLater(() -> {
            ChatServer win = new ChatServer();
            win.setVisible(true);
            win.startServer();
        });
    }
}

/** Protocol engine shared by the window and the headless console mode. */
final class Hub {

    static final int MAX_NAME = 20;
    static final int MAX_TEXT = 2000;
    static final int HISTORY = 60;

    static final class Msg {
        final String from;
        final String text;
        final long ts;

        Msg(String from, String text, long ts) {
            this.from = from;
            this.text = text;
            this.ts = ts;
        }
    }

    private final int wantedPort;
    private final Consumer<String> log;
    private final Runnable onRoster;
    private final List<Conn> conns = new CopyOnWriteArrayList<>();
    private final Deque<Msg> history = new ArrayDeque<>();

    private ServerSocket server;
    private volatile boolean running;

    Hub(int port, Consumer<String> log, Runnable onRoster) {
        this.wantedPort = port;
        this.log = log;
        this.onRoster = onRoster;
    }

    void start() throws IOException {
        server = new ServerSocket(wantedPort);
        running = true;
        Thread t = new Thread(this::acceptLoop, "accept");
        t.setDaemon(true);
        t.start();
        log.accept("Server mở cổng " + boundPort());
    }

    int boundPort() {
        return server == null ? wantedPort : server.getLocalPort();
    }

    void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) { }
        for (Conn c : conns) c.close();
        conns.clear();
        onRoster.run();
        log.accept("Server đã dừng");
    }

    List<String> names() {
        List<String> out = new ArrayList<>();
        for (Conn c : conns) if (c.name != null) out.add(c.name);
        return out;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                s.setTcpNoDelay(true);
                Conn c = new Conn(s);
                Thread t = new Thread(c, "conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException ex) {
                if (running) log.accept("Lỗi chấp nhận kết nối: " + ex.getMessage());
                break;
            }
        }
    }

    private final class Conn implements Runnable {
        private final Socket socket;
        private final BufferedReader in;
        private final PrintWriter out;
        private volatile String name;
        private volatile boolean open = true;

        Conn(Socket s) throws IOException {
            socket = s;
            in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
        }

        void send(String line) {
            if (open) out.println(line);
        }

        void close() {
            open = false;
            try { socket.close(); } catch (IOException ignored) { }
        }

        @Override public void run() {
            boolean joined = false;
            try {
                String line = in.readLine();
                if (line != null && line.startsWith("HELLO ")) {
                    name = unique(decode(line.substring(6)));
                    conns.add(this);
                    joined = true;
                    send("WELCOME " + encode(name) + " " + encode("PHÒNG CHAT"));
                    sendRoster();
                    synchronized (history) {
                        for (Msg m : history) send(lineOf(m));
                    }
                    broadcastSys(name + " đã tham gia phòng");
                    sendRoster();
                    log.accept(name + " đã kết nối (" + socket.getInetAddress().getHostAddress() + ")");
                    while ((line = in.readLine()) != null) handle(line);
                }
            } catch (IOException ignored) {
            } finally {
                close();
                conns.remove(this);
                if (joined) {
                    if (running) {
                        broadcastSys(name + " đã rời phòng");
                        sendRoster();
                    }
                    log.accept(name + " đã rời phòng");
                }
            }
        }

        private void handle(String line) {
            int sp = line.indexOf(' ');
            String cmd = sp < 0 ? line : line.substring(0, sp);
            String rest = sp < 0 ? "" : line.substring(sp + 1);
            switch (cmd) {
                case "MSG" -> {
                    String text = decode(rest).strip();
                    if (text.isEmpty()) return;
                    if (text.length() > MAX_TEXT) text = text.substring(0, MAX_TEXT);
                    Msg m = new Msg(name, text, System.currentTimeMillis());
                    synchronized (history) {
                        history.addLast(m);
                        while (history.size() > HISTORY) history.removeFirst();
                    }
                    broadcast(lineOf(m));
                    log.accept(name + ": " + m.text.replace('\n', ' '));
                }
                case "TYPING" -> broadcastExcept(this, "TYPING " + encode(name) + " " + ("1".equals(rest) ? "1" : "0"));
                case "QUIT" -> close();
                default -> { }
            }
        }
    }

    private static String lineOf(Msg m) {
        return "MSG " + encode(m.from) + " " + encode(m.text) + " " + m.ts;
    }

    private void broadcast(String line) {
        for (Conn c : conns) c.send(line);
    }

    private void broadcastExcept(Conn skip, String line) {
        for (Conn c : conns) if (c != skip) c.send(line);
    }

    private void broadcastSys(String text) {
        broadcast("SYS " + encode(text));
    }

    private void sendRoster() {
        StringBuilder sb = new StringBuilder("ROSTER");
        for (Conn c : conns) if (c.name != null) sb.append(' ').append(encode(c.name));
        broadcast(sb.toString());
        onRoster.run();
    }

    private String unique(String raw) {
        String base = raw == null ? "" : raw.replace('\n', ' ').replace('\r', ' ').trim();
        base = base.replaceAll("\\s+", " ");
        if (base.isEmpty()) base = "Khách";
        if (base.length() > MAX_NAME) base = base.substring(0, MAX_NAME);
        String candidate = base;
        int n = 2;
        while (taken(candidate)) {
            String suffix = " (" + (n++) + ")";
            int room = Math.max(1, MAX_NAME - suffix.length());
            String stem = base.length() > room ? base.substring(0, room) : base;
            candidate = stem + suffix;
        }
        return candidate;
    }

    private boolean taken(String n) {
        for (Conn c : conns) if (n.equals(c.name)) return true;
        return false;
    }

    static String encode(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    static String decode(String s) {
        try {
            return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }

    /** First non-loopback IPv4 address, skipping VM / VPN adapters. */
    static String lanIp() {
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            String fallback = null;
            while (all.hasMoreElements()) {
                NetworkInterface ni = all.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName().toLowerCase(Locale.ROOT);
                String d = ni.getDisplayName() == null ? "" : ni.getDisplayName().toLowerCase(Locale.ROOT);
                boolean virtual = n.startsWith("virbr") || n.startsWith("vmnet") || n.startsWith("veth")
                        || n.startsWith("docker") || n.startsWith("br-") || n.startsWith("utun")
                        || d.contains("virtual") || d.contains("vmware") || d.contains("hyper-v")
                        || d.contains("vethernet") || d.contains("docker") || d.contains("wsl")
                        || d.contains("tailscale") || d.contains("zerotier") || d.contains("loopback");
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                        if (!virtual) return a.getHostAddress();
                        if (fallback == null) fallback = a.getHostAddress();
                    }
                }
            }
            if (fallback != null) return fallback;
        } catch (SocketException ignored) { }
        return "127.0.0.1";
    }
}
