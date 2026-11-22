package chat.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Server settings loaded from a plain text file:
 * <pre>
 * 12345          port
 * MyServer       server name
 * phrase1        banned phrases, one per line
 * phrase2
 * </pre>
 */
public record ServerConfig(int port, String serverName, List<String> bannedPhrases) {

    public ServerConfig {
        bannedPhrases = bannedPhrases.stream()
                .map(String::trim)
                .filter(p -> !p.isEmpty())
                .map(p -> p.toLowerCase(Locale.ROOT))
                .toList();
    }

    public static ServerConfig load(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.size() < 2) {
            throw new IOException("Config must contain at least a port and a server name: " + file);
        }
        int port = Integer.parseInt(lines.get(0).trim());
        String name = lines.get(1).trim();
        return new ServerConfig(port, name, new ArrayList<>(lines.subList(2, lines.size())));
    }
}
