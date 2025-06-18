import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.List;

public class Client implements Runnable {
    private Socket socket;
    private Server server;
    private PrintWriter out;
    private BufferedReader in;
    private String username;
    private JTextArea textArea;
    private JTextField textField;


    public Client(Socket socket, Server server) throws IOException {
        this.socket = socket;
        this.server = server;
        this.out = new PrintWriter(socket.getOutputStream(), true);
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
    }


    public Client(String host, int port) throws IOException {
        this.socket = new Socket(host, port);
        this.out = new PrintWriter(socket.getOutputStream(), true);
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        initGUI();
    }

    private void initGUI() {
        JFrame frame = new JFrame("Client Chat");
        frame.setSize(400, 300);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        textArea = new JTextArea();
        textArea.setEditable(false);
        frame.add(new JScrollPane(textArea), BorderLayout.CENTER);

        textField = new JTextField();
        textField.addActionListener(e -> sendMessageFromGUI());
        frame.add(textField, BorderLayout.SOUTH);

        frame.setVisible(true);
    }

    private void sendMessageFromGUI() {
        String message = textField.getText();
        if (!message.trim().isEmpty()) {
            out.println(message);
        }
        textField.setText("");
    }

    public void sendMessage(String sender, String message) {
        out.println(sender + ": " + message);
    }

    private void processMessages(String message) {
        SwingUtilities.invokeLater(() -> {
            String currentText = textArea.getText();
            String updatedText = currentText + message + "\n";
            textArea.setText(updatedText);
            textArea.setCaretPosition(updatedText.length());
        });
    }


    @Override
    public void run() {
        try {

            if (server != null) {
                out.println("Enter your username:");
                username = in.readLine();

                if (!server.addClient(username, this)) {
                    out.println("Username already taken. Disconnecting.");
                    socket.close();
                    return;
                }

                out.println("Connected to server. Users online: " + server.getClients());
                handleMessages();
            }

            else {
                handleMessages();
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {

                server.removeClient(username);

            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void handleMessages() throws IOException {
        String message;
        while ((message = in.readLine()) != null) {
            if (server != null) {
                processServerMessage(message);
            } else {
                processMessages(message);
            }
        }
    }

    private void processServerMessage(String message) throws IOException {
        if (message.equalsIgnoreCase("/banned")) {
            out.println("Banned phrases: " + server.getBannedPhrases());
            return;
        }

        if (message.startsWith("/to ")) {
            String[] parts = message.substring(4).split(" ", 2);
            if (parts.length == 2) {
                String targetUser = parts[0];
                String userMessage = parts[1];
                sendToSpecificUser(targetUser, userMessage);
            } else {
                out.println("Invalid command. Use: /to <username> <message>");
            }
            return;
        }

        if (message.startsWith("/exclude ")) {
            String[] parts = message.substring(9).split(" ", 2);
            if (parts.length == 2) {
                List<String> excludedUsers = Arrays.asList(parts[0].split(","));
                String userMessage = parts[1];
                server.broadcast(username, userMessage, server.getClientsExcept(excludedUsers));
            } else {
                out.println("Invalid command. Use: /exclude <user1,user2,...> <message>");
            }
            return;
        }

        if (server.containsBannedPhrase(message)) {
            out.println("Message contains banned phrases and will not be sent.");
        } else {
            server.broadcast(username, message, server.getClients());
        }
    }

    private void sendToSpecificUser(String targetUser, String message) {
        Client targetClient = server.getClient(targetUser);
        if (targetClient != null) {
            targetClient.sendMessage(username, message);
        } else {
            sendMessage("Server", "User " + targetUser + " not found.");
        }
    }

    public static void main(String[] args) throws IOException {

                Client client = new Client("localhost", 12345);
                new Thread(client).start();
            }

    }

