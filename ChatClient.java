import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.text.DefaultEditorKit;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

/**
 * Chat client: connects to a ChatServer over TCP and shows a bubble-style
 * conversation. Standalone file — only needs ChatClient.java on the other PC.
 *
 * Wire protocol: UTF-8 lines, free text base64 encoded.
 *   HELLO <name> -> WELCOME / ROSTER / MSG / SYS / TYPING
 */
public final class ChatClient extends JFrame {

    private static final Color BG = new Color(0x0F1116);
    private static final Color PANEL = new Color(0x161A22);
    private static final Color LINE = new Color(0x262C3A);
    private static final Color TEXT = new Color(0xE6EAF2);
    private static final Color MUTED = new Color(0x8B93A3);
    private static final Color ACCENT = new Color(0x2563EB);
    private static final Color GREEN = new Color(0x3ECF8E);
    private static final Color RED = new Color(0xFF6B6B);

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Path SAVE = Path.of(System.getProperty("user.home"), ".javachat_last");

    private final CardLayout cards = new CardLayout();
    private final JPanel root = new JPanel(cards);

    // connect screen
    private final JTextField nameField = new JTextField();
    private final JTextField addrField = new JTextField();
    private final JTextField portField = new JTextField("5050");
    private final JLabel errorLabel = new JLabel(" ");
    private final Round connectBtn = new Round("Kết nối", ACCENT);

    // chat screen
    private final Transcript transcript = new Transcript();
    private final JScrollPane scroll = new JScrollPane(transcript);
    private final DefaultListModel<String> rosterModel = new DefaultListModel<>();
    private final JList<String> rosterList = new JList<>(rosterModel);
    private final JLabel statusDot = new JLabel("●");
    private final JLabel statusText = new JLabel("Đang kết nối…");
    private final JLabel onlineLabel = new JLabel("0 người online");
    private final JLabel typingLabel = new JLabel(" ");
    private final JTextArea input = new JTextArea();
    private final JScrollPane inputScroll;
    private final Round sendBtn = new Round("Gửi", ACCENT);

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private String myName = "";
    private boolean connecting;
    private javax.swing.Timer typingTimer;
    private long lastTypingSent;
    private String lastTypingName = "";

    private ChatClient() {
        super("Java Chat");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(820, 560));
        setSize(1000, 680);
        setLocationRelativeTo(null);

        loadLast();
        styleField(nameField);
        styleField(addrField);
        styleField(portField);
        connectBtn.addActionListener(e -> doConnect());
        ActionListener enter = e -> doConnect();
        nameField.addActionListener(enter);
        addrField.addActionListener(enter);
        portField.addActionListener(enter);

        inputScroll = new JScrollPane(input);
        root.setBackground(BG);
        root.add(connectCard(), "connect");
        root.add(chatCard(), "chat");
        setContentPane(root);

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });
    }

    // ------------------------------------------------------------ connect UI
    private JComponent connectCard() {
        JPanel outer = new JPanel(new GridBagLayout());
        outer.setBackground(BG);

        JPanel card = new JPanel(new GridBagLayout());
        card.setBackground(PANEL);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LINE), new EmptyBorder(28, 32, 26, 32)));

        JLabel title = new JLabel("JAVA CHAT");
        title.setFont(new Font("Segoe UI", Font.BOLD, 28));
        title.setForeground(TEXT);

        JLabel sub = new JLabel("Trò chuyện trong mạng LAN");
        sub.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        sub.setForeground(MUTED);

        JTextArea hint = new JTextArea(
                "Mở ChatServer trên một máy, rồi nhập địa chỉ IP hiện trên cửa sổ đó "
                + "(ví dụ 192.168.1.42). Hai máy cần cùng một mạng LAN.");
        hint.setEditable(false);
        hint.setOpaque(false);
        hint.setFocusable(false);
        hint.setLineWrap(true);
        hint.setWrapStyleWord(true);
        hint.setForeground(MUTED);
        hint.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        hint.setBorder(null);
        hint.setPreferredSize(new Dimension(360, 46));

        errorLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        errorLabel.setForeground(RED);
        errorLabel.setPreferredSize(new Dimension(360, 18));

        connectBtn.setPreferredSize(new Dimension(360, 42));

        card.add(title, cell(0, 0, 0, GridBagConstraints.NONE));
        card.add(sub, cell(1, 4, 0, GridBagConstraints.NONE));
        card.add(caption("TÊN HIỂN THỊ"), cell(2, 22, 0, GridBagConstraints.NONE));
        card.add(nameField, cell(3, 5, 0, GridBagConstraints.HORIZONTAL));
        card.add(caption("ĐỊA CHỈ IP CỦA SERVER"), cell(4, 14, 0, GridBagConstraints.NONE));
        card.add(addrField, cell(5, 5, 0, GridBagConstraints.HORIZONTAL));
        card.add(caption("CỔNG"), cell(6, 14, 0, GridBagConstraints.NONE));
        card.add(portField, cell(7, 5, 0, GridBagConstraints.HORIZONTAL));
        card.add(errorLabel, cell(8, 14, 0, GridBagConstraints.HORIZONTAL));
        card.add(connectBtn, cell(9, 6, 0, GridBagConstraints.HORIZONTAL));
        card.add(hint, cell(10, 16, 1, GridBagConstraints.HORIZONTAL));

        outer.add(card);
        return outer;
    }

    private static GridBagConstraints cell(int y, int top, int weighty, int fill) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = y;
        c.weightx = 1.0;
        c.weighty = weighty;
        c.fill = fill;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(top, 0, 0, 0);
        return c;
    }

    private static JLabel caption(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Segoe UI", Font.BOLD, 10));
        l.setForeground(MUTED);
        return l;
    }

    private static void styleField(JTextField f) {
        f.setPreferredSize(new Dimension(360, 38));
        f.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        f.setBackground(new Color(0x0B0D12));
        f.setForeground(TEXT);
        f.setCaretColor(TEXT);
        f.setSelectionColor(ACCENT);
        f.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LINE), new EmptyBorder(6, 10, 6, 10)));
    }

    // --------------------------------------------------------------- chat UI
    private JComponent chatCard() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(BG);
        p.add(header(), BorderLayout.NORTH);
        p.add(centerArea(), BorderLayout.CENTER);
        p.add(footer(), BorderLayout.SOUTH);
        return p;
    }

    private JComponent header() {
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 20));
        left.setOpaque(false);

        JLabel room = new JLabel("PHÒNG CHAT");
        room.setFont(new Font("Segoe UI", Font.BOLD, 15));
        room.setForeground(TEXT);

        statusDot.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        statusDot.setForeground(MUTED);
        statusText.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        statusText.setForeground(MUTED);

        left.add(room);
        left.add(Box.createHorizontalStrut(6));
        left.add(statusDot);
        left.add(statusText);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 18));
        right.setOpaque(false);
        onlineLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        onlineLabel.setForeground(MUTED);

        Round leave = new Round("Rời phòng", new Color(0x2A3140));
        leave.setPreferredSize(new Dimension(104, 32));
        leave.addActionListener(e -> leave());

        right.add(onlineLabel);
        right.add(Box.createHorizontalStrut(6));
        right.add(leave);

        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(PANEL);
        p.setPreferredSize(new Dimension(10, 60));
        p.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, LINE));
        p.add(left, BorderLayout.WEST);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    private JComponent centerArea() {
        scroll.setBorder(null);
        scroll.getViewport().setBackground(BG);
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(30);
        scroll.getVerticalScrollBar().setUI(new SlimBar());

        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(BG);
        p.add(scroll, BorderLayout.CENTER);
        p.add(sidePanel(), BorderLayout.EAST);
        return p;
    }

    private JComponent sidePanel() {
        JLabel title = new JLabel("NGƯỜI DÙNG");
        title.setFont(new Font("Segoe UI", Font.BOLD, 10));
        title.setForeground(MUTED);

        rosterList.setBackground(PANEL);
        rosterList.setFixedCellHeight(30);
        rosterList.setSelectionBackground(PANEL);
        rosterList.setBorder(null);
        rosterList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean sel, boolean focus) {
                JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, false, false);
                String n = String.valueOf(value);
                boolean me = n.equals(myName);
                String dot = me ? "#7FB0FF" : "#3ECF8E";
                l.setText("<html><font color='" + dot + "'>●</font>&nbsp; " + escape(n)
                        + (me ? " <font color='#6B7484'>(bạn)</font>" : "") + "</html>");
                l.setOpaque(false);
                l.setForeground(me ? new Color(0xBFD6FF) : TEXT);
                l.setBorder(new EmptyBorder(4, 2, 4, 2));
                l.setFont(new Font("Segoe UI", me ? Font.BOLD : Font.PLAIN, 13));
                return l;
            }
        });

        JScrollPane sc = new JScrollPane(rosterList);
        sc.setBorder(null);
        sc.getViewport().setBackground(PANEL);
        sc.getVerticalScrollBar().setUI(new SlimBar());

        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBackground(PANEL);
        p.setPreferredSize(new Dimension(212, 10));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 1, 0, 0, LINE), new EmptyBorder(16, 16, 16, 16)));
        p.add(title, BorderLayout.NORTH);
        p.add(sc, BorderLayout.CENTER);
        return p;
    }

    private JComponent footer() {
        typingLabel.setFont(new Font("Segoe UI", Font.ITALIC, 12));
        typingLabel.setForeground(MUTED);
        typingLabel.setBorder(new EmptyBorder(0, 2, 3, 0));

        input.setBackground(new Color(0x0B0D12));
        input.setForeground(TEXT);
        input.setCaretColor(TEXT);
        input.setSelectionColor(ACCENT);
        input.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        input.setBorder(new EmptyBorder(9, 10, 9, 10));
        input.setLineWrap(true);
        input.setWrapStyleWord(true);

        inputScroll.setBorder(BorderFactory.createLineBorder(LINE));
        inputScroll.setPreferredSize(new Dimension(10, 42));
        inputScroll.getViewport().setBackground(new Color(0x0B0D12));
        inputScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        inputScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        inputScroll.getVerticalScrollBar().setUI(new SlimBar());

        input.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "send-message");
        input.getActionMap().put("send-message", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                sendCurrent();
            }
        });
        input.getInputMap().put(KeyStroke.getKeyStroke("shift ENTER"), DefaultEditorKit.insertBreakAction);

        sendBtn.setPreferredSize(new Dimension(84, 42));
        sendBtn.addActionListener(e -> sendCurrent());

        input.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { typingPing(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { typingPing(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { }
        });

        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.add(inputScroll, BorderLayout.CENTER);
        row.add(sendBtn, BorderLayout.EAST);

        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBackground(PANEL);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, LINE), new EmptyBorder(8, 16, 12, 16)));
        p.add(typingLabel, BorderLayout.NORTH);
        p.add(row, BorderLayout.CENTER);
        return p;
    }

    // -------------------------------------------------------------- networking
    private void doConnect() {
        if (connecting) return;
        String name = nameField.getText().trim();
        String host = addrField.getText().trim();
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            errorLabel.setText("Cổng phải là số từ 1 đến 65535.");
            return;
        }
        if (name.isEmpty()) {
            errorLabel.setText("Hãy nhập tên hiển thị.");
            return;
        }
        if (host.isEmpty()) {
            errorLabel.setText("Hãy nhập địa chỉ IP của server.");
            return;
        }
        errorLabel.setText("Đang kết nối tới " + host + ":" + port + " …");
        connectBtn.setEnabled(false);
        connecting = true;

        new Thread(() -> {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(host, port), 5000);
                s.setTcpNoDelay(true);
                BufferedReader bin = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter bout = new PrintWriter(
                        new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
                bout.println("HELLO " + enc(name));
                SwingUtilities.invokeLater(() -> onConnected(s, bin, bout, name, host));
            } catch (Exception ex) {
                try { s.close(); } catch (IOException ignored) { }
                SwingUtilities.invokeLater(() -> {
                    connecting = false;
                    connectBtn.setEnabled(true);
                    errorLabel.setText("Không kết nối được: " + reason(ex));
                });
            }
        }, "connect").start();
    }

    private void onConnected(Socket s, BufferedReader bin, PrintWriter bout, String name, String host) {
        socket = s;
        in = bin;
        out = bout;
        myName = name;
        connecting = false;
        connectBtn.setEnabled(true);
        errorLabel.setText(" ");

        transcript.clear();
        rosterModel.clear();
        transcript.addDay("Hôm nay · " + LocalDate.now().format(DAY_FMT));
        statusDot.setForeground(GREEN);
        statusText.setText("Đã kết nối");
        typingLabel.setText(" ");
        cards.show(root, "chat");
        saveLast(name, host, portField.getText().trim());
        input.requestFocusInWindow();

        Thread t = new Thread(this::readLoop, "reader");
        t.setDaemon(true);
        t.start();
    }

    private void readLoop() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                String l = line;
                SwingUtilities.invokeLater(() -> handle(l));
            }
        } catch (IOException ignored) {
        }
        SwingUtilities.invokeLater(this::onDisconnected);
    }

    private void handle(String line) {
        String[] p = line.split(" ", 4);
        switch (p[0]) {
            case "WELCOME" -> {
                if (p.length > 1) myName = dec(p[1]);
                statusDot.setForeground(GREEN);
                statusText.setText("Đã kết nối");
                rosterList.repaint();
            }
            case "SYS" -> {
                if (p.length > 1) transcript.addSystem(dec(p[1]));
            }
            case "MSG" -> {
                if (p.length < 3) return;
                String from = dec(p[1]);
                String text = dec(p[2]);
                long ts = System.currentTimeMillis();
                if (p.length > 3) {
                    try { ts = Long.parseLong(p[3]); } catch (NumberFormatException ignored) { }
                }
                boolean atBottom = isAtBottom();
                transcript.addMessage(from, text, ts, from.equals(myName));
                if (atBottom) scrollToBottom();
            }
            case "ROSTER" -> {
                rosterModel.clear();
                for (int i = 1; i < p.length; i++) {
                    if (!p[i].isEmpty()) rosterModel.addElement(dec(p[i]));
                }
                onlineLabel.setText(rosterModel.size() + " người online");
            }
            case "TYPING" -> {
                if (p.length < 3) return;
                String who = dec(p[1]);
                boolean on = p[2].equals("1");
                if (who.equals(myName)) return;
                if (on) {
                    lastTypingName = who;
                    typingLabel.setText(who + " đang nhập…");
                    if (typingTimer == null) {
                        typingTimer = new javax.swing.Timer(2600, e -> typingLabel.setText(" "));
                        typingTimer.setRepeats(false);
                    }
                    typingTimer.restart();
                } else if (who.equals(lastTypingName)) {
                    typingLabel.setText(" ");
                    if (typingTimer != null) typingTimer.stop();
                }
            }
            default -> { }
        }
    }

    private void sendCurrent() {
        if (out == null) return;
        String text = input.getText().replace("\r", "").trim();
        if (text.isEmpty()) return;
        if (text.length() > 2000) text = text.substring(0, 2000);
        out.println("MSG " + enc(text));
        out.println("TYPING 0");
        input.setText("");
        adjustInputHeight();
        lastTypingSent = 0;
    }

    private void typingPing() {
        if (out == null) return;
        long now = System.currentTimeMillis();
        if (!input.getText().isBlank() && now - lastTypingSent > 1200) {
            lastTypingSent = now;
            out.println("TYPING 1");
        }
    }

    private void adjustInputHeight() {
        int lines = Math.min(5, Math.max(1, input.getText().split("\n", -1).length));
        int h = 20 * lines + 22;
        if (inputScroll.getPreferredSize().height != h) {
            inputScroll.setPreferredSize(new Dimension(10, h));
            inputScroll.revalidate();
            revalidate();
        }
    }

    private void leave() {
        if (out != null) {
            out.println("QUIT");
            out.flush();
        }
        closeSocket();
        transcript.clear();
        rosterModel.clear();
        typingLabel.setText(" ");
        onlineLabel.setText("0 người online");
        statusText.setText("Đã ngắt kết nối");
        statusDot.setForeground(MUTED);
        cards.show(root, "connect");
        errorLabel.setText(" ");
        connectBtn.setEnabled(true);
    }

    private void onDisconnected() {
        if (socket == null) return;
        closeSocket();
        statusDot.setForeground(RED);
        statusText.setText("Mất kết nối");
        errorLabel.setText("Mất kết nối tới server. Hãy kiểm tra server còn chạy không.");
        cards.show(root, "connect");
        rosterModel.clear();
        connectBtn.setEnabled(true);
    }

    private void closeSocket() {
        out = null;
        in = null;
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) { }
        }
        socket = null;
    }

    private void shutdown() {
        if (out != null) {
            out.println("QUIT");
            out.flush();
        }
        closeSocket();
    }

    private boolean isAtBottom() {
        JScrollBar b = scroll.getVerticalScrollBar();
        return b.getValue() + b.getVisibleAmount() >= b.getMaximum() - 80;
    }

    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> {
            JScrollBar b = scroll.getVerticalScrollBar();
            b.setValue(b.getMaximum());
        });
    }

    // ------------------------------------------------------------------ helpers
    private void loadLast() {
        String name = System.getProperty("user.name", "Khách");
        String host = "127.0.0.1";
        String port = "5050";
        try {
            if (Files.exists(SAVE)) {
                List<String> l = Files.readAllLines(SAVE, StandardCharsets.UTF_8);
                if (l.size() > 0 && !l.get(0).isBlank()) name = l.get(0);
                if (l.size() > 1 && !l.get(1).isBlank()) host = l.get(1);
                if (l.size() > 2 && !l.get(2).isBlank()) port = l.get(2);
            }
        } catch (Exception ignored) { }
        nameField.setText(name);
        addrField.setText(host);
        portField.setText(port);
    }

    private void saveLast(String name, String host, String port) {
        try {
            Files.write(SAVE, List.of(name, host, port), StandardCharsets.UTF_8);
        } catch (Exception ignored) { }
    }

    private static String reason(Exception ex) {
        if (ex instanceof SocketTimeoutException) return "hết thời gian chờ (sai IP hoặc firewall chặn).";
        if (ex instanceof ConnectException) return "server chưa bật hoặc sai địa chỉ/cổng.";
        if (ex instanceof UnknownHostException) return "không tìm thấy địa chỉ này.";
        String m = ex.getMessage();
        return m == null || m.isBlank() ? ex.getClass().getSimpleName() : m;
    }

    private static String enc(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String dec(String s) {
        try {
            return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final class Round extends JButton {
        private final Color base;
        private boolean hover;

        Round(String text, Color base) {
            super(text);
            this.base = base;
            setFont(new Font("Segoe UI", Font.BOLD, 13));
            setForeground(Color.WHITE);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(120, 38));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color c = base;
            if (!isEnabled()) c = new Color(0x333B4D);
            else if (hover) c = base.brighter();
            g.setColor(c);
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

        SwingUtilities.invokeLater(() -> new ChatClient().setVisible(true));
    }

    // ------------------------------------------------------------- transcript
    static final class Transcript extends JPanel {

        private static final Color BUBBLE_OTHER = new Color(0x1B2130);
        private static final Color BUBBLE_MINE = new Color(0x2D6BE0);
        private static final Color BORDER = new Color(0x2A3242);
        private static final Color SYS = new Color(0x7C8598);
        private static final int[] NAMES = {
                0x6EE7B7, 0xF0ABFC, 0xFDE68A, 0x93C5FD,
                0xFCA5A5, 0xA5B4FC, 0x7DD3FC, 0xFDBA74
        };
        private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
        private static final int PAD = 18;

        private static final class Entry {
            final int kind;          // 0 message, 1 system, 2 day separator
            final String from;
            final String text;
            final long ts;
            final boolean mine;

            Entry(int kind, String from, String text, long ts, boolean mine) {
                this.kind = kind;
                this.from = from;
                this.text = text;
                this.ts = ts;
                this.mine = mine;
            }
        }

        private static final class Block {
            Entry e;
            int y;
            int h;
            int bw;
            int bh;
            List<String> lines = Collections.emptyList();
        }

        private final List<Entry> entries = new ArrayList<>();
        private final List<Block> blocks = new ArrayList<>();
        private int laidOutWidth = -1;
        private int contentHeight = 0;

        private final Font nameFont = new Font("Segoe UI", Font.BOLD, 12);
        private final Font timeFont = new Font("Segoe UI", Font.PLAIN, 11);
        private final Font msgFont = new Font("Segoe UI", Font.PLAIN, 14);
        private final Font sysFont = new Font("Segoe UI", Font.ITALIC, 12);
        private final Font dayFont = new Font("Segoe UI", Font.BOLD, 11);
        private final Font avatarFont = new Font("Segoe UI", Font.BOLD, 13);

        Transcript() {
            setOpaque(true);
            setBackground(BG);
        }

        void clear() {
            entries.clear();
            blocks.clear();
            laidOutWidth = -1;
            contentHeight = 0;
            revalidate();
            repaint();
        }

        void addMessage(String from, String text, long ts, boolean mine) {
            entries.add(new Entry(0, from, text, ts, mine));
            invalidateLayout();
        }

        void addSystem(String text) {
            entries.add(new Entry(1, null, text, 0, false));
            invalidateLayout();
        }

        void addDay(String text) {
            entries.add(new Entry(2, null, text, 0, false));
            invalidateLayout();
        }

        private void invalidateLayout() {
            laidOutWidth = -1;
            revalidate();
            repaint();
        }

        @Override public Dimension getPreferredSize() {
            int w = getParent() != null ? getParent().getWidth() : getWidth();
            if (w <= 0) w = 700;
            ensureLayout(w);
            return new Dimension(w, Math.max(1, contentHeight));
        }

        private void ensureLayout(int width) {
            if (width == laidOutWidth) return;
            laidOutWidth = width;
            blocks.clear();

            FontMetrics fm = getFontMetrics(msgFont);
            FontMetrics sfm = getFontMetrics(sysFont);
            int maxBubble = Math.min((int) (width * 0.70), 560);
            int y = PAD;

            for (Entry e : entries) {
                Block b = new Block();
                b.e = e;
                if (e.kind == 2) {
                    b.y = y;
                    b.h = 38;
                    y += b.h;
                } else if (e.kind == 1) {
                    b.lines = wrap(e.text, sfm, Math.max(120, width - 2 * PAD));
                    b.h = b.lines.size() * sfm.getHeight() + 18;
                    b.y = y;
                    y += b.h;
                } else {
                    int textW = Math.max(60, maxBubble - 24);
                    b.lines = wrap(e.text, fm, textW);
                    int widest = 0;
                    for (String s : b.lines) widest = Math.max(widest, fm.stringWidth(s));
                    b.bw = Math.min(maxBubble, widest + 24);
                    b.bh = b.lines.size() * fm.getHeight() + 18;
                    b.y = y;
                    b.h = 15 + b.bh + 12;
                    y += b.h;
                }
                blocks.add(b);
            }
            contentHeight = y + PAD;
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(BG);
            g.fillRect(0, 0, getWidth(), getHeight());

            int w = getWidth();
            if (w <= 0) {
                g.dispose();
                return;
            }
            ensureLayout(w);

            if (entries.isEmpty()) {
                g.setFont(sysFont);
                g.setColor(MUTED);
                String t = "Chưa có tin nhắn — hãy gửi lời chào!";
                g.drawString(t, (w - g.getFontMetrics().stringWidth(t)) / 2, 70);
            }

            for (Block b : blocks) {
                if (b.y > getHeight() + 80 || b.y + b.h < -80) continue;
                paintBlock(g, b, w);
            }
            g.dispose();
        }

        private void paintBlock(Graphics2D g, Block b, int w) {
            Entry e = b.e;

            if (e.kind == 2) {
                g.setFont(dayFont);
                FontMetrics fm = g.getFontMetrics();
                int tw = fm.stringWidth(e.text) + 22;
                int x = (w - tw) / 2;
                int y = b.y + 6;
                g.setColor(new Color(0x1A1F2A));
                g.fillRoundRect(x, y, tw, 22, 11, 11);
                g.setColor(MUTED);
                g.drawString(e.text, x + 11, y + 15);
                return;
            }

            if (e.kind == 1) {
                g.setFont(sysFont);
                g.setColor(SYS);
                FontMetrics fm = g.getFontMetrics();
                int ty = b.y + 4 + fm.getAscent();
                for (String ln : b.lines) {
                    g.drawString(ln, (w - fm.stringWidth(ln)) / 2, ty);
                    ty += fm.getHeight();
                }
                return;
            }

            int metaY = b.y + 11;
            if (e.mine) {
                g.setFont(timeFont);
                g.setColor(MUTED);
                String t = time(e.ts);
                g.drawString(t, w - PAD - g.getFontMetrics().stringWidth(t), metaY);
            } else {
                int ax = PAD;
                Color c = colorFor(e.from);
                g.setColor(c);
                g.fillOval(ax, b.y + 15, 30, 30);

                g.setFont(avatarFont);
                g.setColor(new Color(0x0B0D12));
                String ini = initial(e.from);
                FontMetrics ifm = g.getFontMetrics();
                g.drawString(ini, ax + 15 - ifm.stringWidth(ini) / 2, b.y + 35);

                g.setFont(nameFont);
                g.setColor(c);
                int nx = ax + 40;
                g.drawString(e.from, nx, metaY);
                int nw = g.getFontMetrics().stringWidth(e.from);

                g.setFont(timeFont);
                g.setColor(MUTED);
                g.drawString("· " + time(e.ts), nx + nw + 6, metaY);
            }

            int bx = e.mine ? w - PAD - b.bw : PAD + 40;
            int by = b.y + 15;
            g.setColor(e.mine ? BUBBLE_MINE : BUBBLE_OTHER);
            g.fillRoundRect(bx, by, b.bw, b.bh, 18, 18);
            if (!e.mine) {
                g.setColor(BORDER);
                g.drawRoundRect(bx, by, b.bw, b.bh, 18, 18);
            }

            g.setFont(msgFont);
            g.setColor(e.mine ? Color.WHITE : TEXT);
            FontMetrics fm = g.getFontMetrics();
            int ty = by + 9 + fm.getAscent();
            for (String ln : b.lines) {
                g.drawString(ln, bx + 12, ty);
                ty += fm.getHeight();
            }
        }

        private static List<String> wrap(String text, FontMetrics fm, int maxW) {
            List<String> out = new ArrayList<>();
            for (String para : text.split("\n", -1)) {
                if (para.isEmpty()) {
                    out.add("");
                    continue;
                }
                int start = 0;
                int n = para.length();
                while (start < n) {
                    int width = 0;
                    int i = start;
                    int lastSpace = -1;
                    while (i < n) {
                        int cw = fm.charWidth(para.charAt(i));
                        if (width + cw > maxW) break;
                        width += cw;
                        if (para.charAt(i) == ' ') lastSpace = i;
                        i++;
                    }
                    if (i >= n) {
                        out.add(para.substring(start));
                        break;
                    }
                    if (i == start) {
                        i = start + 1;
                        lastSpace = -1;
                    }
                    int cut = lastSpace > start ? lastSpace : i;
                    out.add(para.substring(start, cut).stripTrailing());
                    start = lastSpace > start ? cut + 1 : cut;
                }
            }
            return out;
        }

        private static String time(long ts) {
            return Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
                    .toLocalTime().format(HM);
        }

        private static Color colorFor(String name) {
            int i = Math.floorMod(name.hashCode(), NAMES.length);
            return new Color(NAMES[i]);
        }

        private static String initial(String name) {
            if (name == null || name.isEmpty()) return "?";
            return new String(Character.toChars(name.codePointAt(0))).toUpperCase(Locale.ROOT);
        }
    }
}


