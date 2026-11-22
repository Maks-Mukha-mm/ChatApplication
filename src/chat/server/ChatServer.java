package chat.server;

import chat.common.Protocol;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Multi-client chat server. Every connection is served by a {@link ClientHandler}
 * running on its own thread.
 */
public class ChatServer {

    static final int HISTORY_SIZE = 20;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final ServerConfig config;
    private final Map<String, ClientHandler> clients = new ConcurrentHashMap<>();
    private final Deque<String> history = new ArrayDeque<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile ServerSocket serverSocket;

    public ChatServer(ServerConfig config) {
        this.config = config;
    }

    /** Opens the server socket. Use port 0 in the config to get a free port (handy in tests). */
    public void bind() throws IOException {
        serverSocket = new ServerSocket(config.port());
    }

    /** Accepts connections until {@link #stop()} is called. */
    public void serve() {
        System.out.println(config.serverName() + " started on port " + getPort());
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                pool.execute(new ClientHandler(socket, this));
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    System.err.println("Failed to accept connection: " + e.getMessage());
                }
            }
        }
    }

    public void stop() throws IOException {
        serverSocket.close();
        for (ClientHandler client : clients.values()) {
            client.close();
        }
        pool.shutdownNow();
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    public String getServerName() {
        return config.serverName();
    }

    // ---- users ---------------------------------------------------------------

    /** @return an error message, or {@code null} if the name is acceptable */
    String validateUsername(String name) {
        if (name == null || name.isBlank()) return "Username cannot be empty.";
        if (name.length() > Protocol.MAX_USERNAME_LENGTH)
            return "Username is too long (max " + Protocol.MAX_USERNAME_LENGTH + " characters).";
        if (!name.matches("[\\p{L}\\p{N}_.-]+"))
            return "Use only letters, digits, '_', '.' and '-'.";
        if (name.equalsIgnoreCase("server")) return "This name is reserved.";
        return null;
    }

    /** Registers the user if the name is free. */
    boolean register(String name, ClientHandler handler) {
        return clients.putIfAbsent(name, handler) == null;
    }

    /** Removes the user only if the name still belongs to this handler. */
    void unregister(String name, ClientHandler handler) {
        clients.remove(name, handler);
    }

    ClientHandler getClient(String name) {
        return clients.get(name);
    }

    List<String> getUsernames() {
        List<String> names = new ArrayList<>(clients.keySet());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    // ---- messaging -------------------------------------------------------------

    void broadcastMessage(String sender, String text, Collection<String> excluded) {
        String line = Protocol.encode(Protocol.MSG, now(), sender, text);
        if (excluded.isEmpty()) {
            remember(line);
        }
        for (Map.Entry<String, ClientHandler> e : clients.entrySet()) {
            if (!excluded.contains(e.getKey())) {
                e.getValue().send(line);
            }
        }
    }

    void broadcastSystem(String text) {
        String line = Protocol.encode(Protocol.SYS, now(), text);
        clients.values().forEach(c -> c.send(line));
    }

    void broadcastUserList() {
        String line = Protocol.encode(Protocol.USERS, String.join(",", getUsernames()));
        clients.values().forEach(c -> c.send(line));
    }

    private void remember(String line) {
        synchronized (history) {
            history.addLast(line);
            while (history.size() > HISTORY_SIZE) {
                history.removeFirst();
            }
        }
    }

    List<String> getHistory() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    boolean containsBannedPhrase(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return config.bannedPhrases().stream().anyMatch(lower::contains);
    }

    List<String> getBannedPhrases() {
        return config.bannedPhrases();
    }

    static String now() {
        return LocalTime.now().format(TIME);
    }

    public static void main(String[] args) throws IOException {
        Path configFile = Path.of(args.length > 0 ? args[0] : "server_config");
        ChatServer server = new ChatServer(ServerConfig.load(configFile));
        server.bind();
        server.serve();
    }
}
