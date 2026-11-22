package chat.server;

import chat.common.Protocol;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Serves one connected user: handles login, chat messages and commands.
 */
public class ClientHandler implements Runnable {

    private static final List<String> HELP = List.of(
            "/to <user> <message>          private message",
            "/exclude <u1,u2> <message>    message to everyone except listed users",
            "/users                        who is online",
            "/banned                       list of banned phrases",
            "/quit                         leave the chat"
    );

    private final Socket socket;
    private final ChatServer server;
    private final BufferedReader in;
    private final PrintWriter out;
    private String username;

    public ClientHandler(Socket socket, ChatServer server) throws IOException {
        this.socket = socket;
        this.server = server;
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
    }

    @Override
    public void run() {
        try {
            if (!login()) {
                return;
            }
            String line;
            while ((line = in.readLine()) != null) {
                if (!handle(line.strip())) {
                    break;
                }
            }
        } catch (IOException ignored) {
            // connection dropped
        } finally {
            if (username != null) {
                server.unregister(username, this);
                server.broadcastSystem(username + " left the chat.");
                server.broadcastUserList();
            }
            close();
        }
    }

    /** Reads LOGIN lines until a free, valid name is chosen. */
    private boolean login() throws IOException {
        String line;
        while ((line = in.readLine()) != null) {
            String[] parts = Protocol.decode(line);
            if (!Protocol.LOGIN.equals(parts[0]) || parts.length < 2) {
                send(Protocol.encode(Protocol.LOGIN_FAIL, "Please log in first."));
                continue;
            }
            String name = parts[1].strip();
            String error = server.validateUsername(name);
            if (error == null && !server.register(name, this)) {
                error = "Username \"" + name + "\" is already taken.";
            }
            if (error != null) {
                send(Protocol.encode(Protocol.LOGIN_FAIL, error));
                continue;
            }

            username = name;
            send(Protocol.encode(Protocol.LOGIN_OK, server.getServerName()));
            server.getHistory().forEach(this::send);
            sendSystem("Welcome to " + server.getServerName() + "! Type /help to see commands.");
            server.broadcastSystem(username + " joined the chat.");
            server.broadcastUserList();
            return true;
        }
        return false;
    }

    /** @return false if the client asked to leave */
    private boolean handle(String line) {
        if (line.isEmpty()) {
            return true;
        }
        if (!line.startsWith("/")) {
            if (rejectBanned(line)) return true;
            server.broadcastMessage(username, line, Set.of());
            return true;
        }

        String[] parts = line.split("\\s+", 3);
        switch (parts[0].toLowerCase(Locale.ROOT)) {
            case "/help" -> HELP.forEach(this::sendSystem);
            case "/users" -> {
                List<String> users = server.getUsernames();
                sendSystem("Online (" + users.size() + "): " + String.join(", ", users));
            }
            case "/banned" -> sendSystem("Banned phrases: " + server.getBannedPhrases());
            case "/to" -> privateMessage(parts);
            case "/exclude" -> excludeMessage(parts);
            case "/quit" -> {
                return false;
            }
            default -> sendError("Unknown command " + parts[0] + ". Type /help.");
        }
        return true;
    }

    private void privateMessage(String[] parts) {
        if (parts.length < 3) {
            sendError("Usage: /to <user> <message>");
            return;
        }
        String target = parts[1];
        String text = parts[2];
        ClientHandler recipient = server.getClient(target);
        if (recipient == null) {
            sendError("User " + target + " is not online.");
            return;
        }
        if (target.equals(username)) {
            sendError("You cannot send a private message to yourself.");
            return;
        }
        if (rejectBanned(text)) return;

        String pm = Protocol.encode(Protocol.PM, ChatServer.now(), username, target, text);
        recipient.send(pm);
        send(pm); // echo so the sender sees it in their window
    }

    private void excludeMessage(String[] parts) {
        if (parts.length < 3) {
            sendError("Usage: /exclude <user1,user2,...> <message>");
            return;
        }
        Set<String> excluded = new HashSet<>(Arrays.asList(parts[1].split(",")));
        excluded.remove(username);
        String text = parts[2];
        if (rejectBanned(text)) return;
        server.broadcastMessage(username, text, excluded);
    }

    private boolean rejectBanned(String text) {
        if (server.containsBannedPhrase(text)) {
            sendError("Message contains a banned phrase and was not sent.");
            return true;
        }
        return false;
    }

    void send(String line) {
        synchronized (out) {
            out.println(line);
        }
    }

    private void sendSystem(String text) {
        send(Protocol.encode(Protocol.SYS, ChatServer.now(), text));
    }

    private void sendError(String text) {
        send(Protocol.encode(Protocol.ERR, text));
    }

    void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
