import java.io.*;
import java.net.*;
import java.util.*;

public class Server {
    private int port;
    private String serverName;
    private List<String> bannedPhrases = new ArrayList<>();
    private Map<String, Client> clients = new HashMap<>();

    public Server(String configFilePath) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(configFilePath))) {
            port = Integer.parseInt(reader.readLine());
            serverName = reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                bannedPhrases.add(line);
            }
        }
    }

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server started on port " + port);
            while (true) {
                Socket socket = serverSocket.accept();
                Client client = new Client(socket, this);
                new Thread(client).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public synchronized boolean addClient(String username, Client client) {
        if (clients.containsKey(username)) return false;
        clients.put(username, client);
        broadcast("Server", username + " has joined the chat.", getClients());
        return true;
    }

    public synchronized void removeClient(String username) {
        clients.remove(username);
        broadcast("Server", username + " has left the chat.", getClients());
    }

    public synchronized void broadcast(String sender, String message, List<String> recipients) {
        for (String recipient : recipients) {
            Client client = clients.get(recipient);
            if (client != null) {
                client.sendMessage(sender, message);
            }
        }
    }

    public List<String> getClients() {
        return new ArrayList<>(clients.keySet());
    }

    public List<String> getClientsExcept(List<String> excludedUsers) {
        List<String> result = new ArrayList<>(clients.keySet());
        result.removeAll(excludedUsers);
        return result;
    }

    public boolean containsBannedPhrase(String message) {
        for (String phrase : bannedPhrases) {
            if (message.contains(phrase)) return true;
        }
        return false;
    }

    public synchronized Client getClient(String username) {
        return clients.get(username);
    }

    public List<String> getBannedPhrases() {
        return bannedPhrases;
    }

    public static void main(String[] args) {
        try {
            Server server = new Server("server_config");
            server.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
