package chat.client;

import chat.common.Protocol;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Swing chat client: login dialog, coloured message view and a list of online users.
 */
public class ChatClient {

    private static final Color BACKGROUND = new Color(0xF7F8FA);
    private static final Color GREY = new Color(0x8A8F98);
    private static final Color PRIVATE = new Color(0x8E44AD);
    private static final Color ERROR = new Color(0xC0392B);

    private final Socket socket;
    private final BufferedReader in;
    private final PrintWriter out;
    private final String username;
    private final String serverName;

    private JFrame frame;
    private JTextPane chatPane;
    private JTextField inputField;
    private JButton sendButton;
    private final DefaultListModel<String> usersModel = new DefaultListModel<>();
    private JLabel usersLabel;

    private ChatClient(Socket socket, BufferedReader in, PrintWriter out, String username, String serverName) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.username = username;
        this.serverName = serverName;
    }

    // ---- connecting ------------------------------------------------------------

    public static void main(String[] args) {
        SwingUtilities.invokeLater(ChatClient::connectAndShow);
    }

    private static void connectAndShow() {
        JTextField host = new JTextField("localhost");
        JTextField port = new JTextField("12345");
        JTextField name = new JTextField();

        while (true) {
            JPanel form = new JPanel(new GridLayout(0, 1, 4, 4));
            form.add(new JLabel("Server"));
            form.add(host);
            form.add(new JLabel("Port"));
            form.add(port);
            form.add(new JLabel("Your name"));
            form.add(name);
            SwingUtilities.invokeLater(name::requestFocusInWindow);

            int choice = JOptionPane.showConfirmDialog(null, form, "Join chat",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) {
                System.exit(0);
            }

            try {
                ChatClient client = login(host.getText().trim(),
                        Integer.parseInt(port.getText().trim()), name.getText().trim());
                client.showWindow();
                return;
            } catch (LoginException e) {
                JOptionPane.showMessageDialog(null, e.getMessage(), "Cannot join", JOptionPane.WARNING_MESSAGE);
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(null, "Port must be a number.", "Cannot join", JOptionPane.WARNING_MESSAGE);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(null, "Cannot connect to " + host.getText() + ":" + port.getText()
                        + "\n" + e.getMessage(), "Connection error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private static ChatClient login(String host, int port, String name) throws IOException, LoginException {
        Socket socket = new Socket(host, port);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

        out.println(Protocol.encode(Protocol.LOGIN, name));
        String reply = in.readLine();
        String[] parts = reply == null ? new String[]{""} : Protocol.decode(reply);
        if (!Protocol.LOGIN_OK.equals(parts[0])) {
            socket.close();
            throw new LoginException(parts.length > 1 ? parts[1] : "Server closed the connection.");
        }
        return new ChatClient(socket, in, out, name, parts[1]);
    }

    private static class LoginException extends Exception {
        LoginException(String message) {
            super(message);
        }
    }

    // ---- window ----------------------------------------------------------------

    private void showWindow() {
        frame = new JFrame(serverName + " — " + username);
        frame.setSize(720, 480);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                out.println("/quit");
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
                System.exit(0);
            }
        });

        chatPane = new JTextPane();
        chatPane.setEditable(false);
        chatPane.setBackground(BACKGROUND);
        chatPane.setMargin(new Insets(8, 10, 8, 10));
        JScrollPane chatScroll = new JScrollPane(chatPane);
        chatScroll.setBorder(BorderFactory.createEmptyBorder());

        JList<String> usersList = new JList<>(usersModel);
        usersList.setCellRenderer(new UserCellRenderer());
        usersList.setToolTipText("Double-click to send a private message");
        usersList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                String user = usersList.getSelectedValue();
                if (e.getClickCount() == 2 && user != null && !user.equals(username)) {
                    inputField.setText("/to " + user + " ");
                    inputField.requestFocusInWindow();
                }
            }
        });
        usersLabel = new JLabel("Online");
        usersLabel.setBorder(BorderFactory.createEmptyBorder(8, 8, 6, 8));
        usersLabel.setFont(usersLabel.getFont().deriveFont(Font.BOLD));
        JPanel usersPanel = new JPanel(new BorderLayout());
        usersPanel.add(usersLabel, BorderLayout.NORTH);
        usersPanel.add(new JScrollPane(usersList), BorderLayout.CENTER);
        usersPanel.setPreferredSize(new Dimension(170, 0));

        inputField = new JTextField();
        inputField.addActionListener(e -> sendInput());
        sendButton = new JButton("Send");
        sendButton.addActionListener(e -> sendInput());
        JPanel inputPanel = new JPanel(new BorderLayout(6, 0));
        inputPanel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, chatScroll, usersPanel);
        split.setResizeWeight(1.0);
        split.setBorder(BorderFactory.createEmptyBorder());

        frame.add(split, BorderLayout.CENTER);
        frame.add(inputPanel, BorderLayout.SOUTH);
        frame.setVisible(true);
        inputField.requestFocusInWindow();

        Thread reader = new Thread(this::readLoop, "chat-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void sendInput() {
        String text = inputField.getText().strip();
        if (!text.isEmpty()) {
            out.println(text);
        }
        inputField.setText("");
        if (text.equalsIgnoreCase("/quit")) {
            frame.dispose();
        }
    }

    private void readLoop() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                String[] parts = Protocol.decode(line);
                SwingUtilities.invokeLater(() -> display(parts));
            }
        } catch (IOException ignored) {
            // socket closed
        }
        SwingUtilities.invokeLater(() -> {
            appendLine(null, "Disconnected from the server.", style(ERROR, false, true));
            inputField.setEnabled(false);
            sendButton.setEnabled(false);
        });
    }

    // ---- rendering ---------------------------------------------------------------

    private void display(String[] p) {
        switch (p[0]) {
            case Protocol.MSG -> {
                Color color = p[2].equals(username) ? new Color(0x2C7BE5) : colorFor(p[2]);
                appendLine(p[1], p[2] + ": ", style(color, true, false), p[3], style(Color.DARK_GRAY, false, false));
            }
            case Protocol.PM -> {
                String header = p[2].equals(username) ? "to " + p[3] + ": " : "from " + p[2] + ": ";
                appendLine(p[1], "🔒 " + header, style(PRIVATE, true, false), p[4], style(PRIVATE, false, true));
            }
            case Protocol.SYS -> appendLine(p[1], p[2], style(GREY, false, true));
            case Protocol.ERR -> appendLine(null, p[1], style(ERROR, false, false));
            case Protocol.USERS -> {
                usersModel.clear();
                if (p.length > 1 && !p[1].isEmpty()) {
                    Arrays.stream(p[1].split(",")).forEach(usersModel::addElement);
                }
                usersLabel.setText("Online (" + usersModel.size() + ")");
            }
            default -> { /* ignore unknown messages */ }
        }
    }

    /** Appends one line made of (text, style) pairs, prefixed with the time. */
    private void appendLine(String time, Object... textAndStyles) {
        StyledDocument doc = chatPane.getStyledDocument();
        try {
            if (time != null) {
                doc.insertString(doc.getLength(), time + "  ", style(GREY, false, false));
            }
            for (int i = 0; i + 1 < textAndStyles.length; i += 2) {
                doc.insertString(doc.getLength(), (String) textAndStyles[i], (AttributeSet) textAndStyles[i + 1]);
            }
            doc.insertString(doc.getLength(), "\n", null);
        } catch (BadLocationException ignored) {
        }
        chatPane.setCaretPosition(doc.getLength());
    }

    private static SimpleAttributeSet style(Color color, boolean bold, boolean italic) {
        SimpleAttributeSet s = new SimpleAttributeSet();
        StyleConstants.setForeground(s, color);
        StyleConstants.setBold(s, bold);
        StyleConstants.setItalic(s, italic);
        StyleConstants.setFontSize(s, 14);
        return s;
    }

    /** Stable colour per user name, so everyone keeps the same colour in every window. */
    static Color colorFor(String name) {
        float hue = (name.hashCode() & 0x7fffffff) % 360 / 360f;
        return Color.getHSBColor(hue, 0.65f, 0.75f);
    }

    private class UserCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
            String name = (String) value;
            label.setText("● " + name + (name.equals(username) ? " (you)" : ""));
            if (!selected) {
                label.setForeground(name.equals(username) ? new Color(0x2C7BE5) : colorFor(name));
            }
            label.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
            return label;
        }
    }
}
