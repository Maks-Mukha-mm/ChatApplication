package chat.server;

import chat.common.Protocol;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

public class ChatServerTest {

    private ChatServer server;
    private Thread serverThread;

    @Before
    public void startServer() throws IOException {
        server = new ChatServer(new ServerConfig(0, "TestServer", List.of("Forbidden")));
        server.bind();
        serverThread = new Thread(server::serve);
        serverThread.start();
    }

    @After
    public void stopServer() throws Exception {
        server.stop();
        serverThread.join(2000);
    }

    @Test
    public void userJoinsAndSeesUserList() throws IOException {
        try (TestClient anna = connect("anna")) {
            assertEquals("anna", anna.expect(Protocol.USERS)[1]);
            try (TestClient bob = connect("bob")) {
                assertTrue(anna.expect(Protocol.SYS)[2].contains("bob joined"));
                assertEquals("anna,bob", anna.expect(Protocol.USERS)[1]);
                assertEquals("anna,bob", bob.expect(Protocol.USERS)[1]);
            }
        }
    }

    @Test
    public void duplicateNameIsRejectedAndOriginalUserStays() throws IOException {
        try (TestClient first = connect("anna");
             TestClient second = new TestClient(server.getPort())) {
            second.send(Protocol.encode(Protocol.LOGIN, "anna"));
            assertTrue(second.expect(Protocol.LOGIN_FAIL)[1].contains("already taken"));

            // the same connection can retry with another name
            second.send(Protocol.encode(Protocol.LOGIN, "anna2"));
            second.expect(Protocol.LOGIN_OK);
            assertEquals(List.of("anna", "anna2"), server.getUsernames());

            first.send("still here");
            assertEquals("still here", second.expect(Protocol.MSG)[3]);
        }
    }

    @Test
    public void invalidNamesAreRejected() throws IOException {
        try (TestClient c = new TestClient(server.getPort())) {
            for (String bad : new String[]{"", "with space", "a|b", "Server", "x".repeat(21)}) {
                c.send(Protocol.encode(Protocol.LOGIN, bad));
                c.expect(Protocol.LOGIN_FAIL);
            }
        }
    }

    @Test
    public void messageIsBroadcastToEveryone() throws IOException {
        try (TestClient anna = connect("anna"); TestClient bob = connect("bob")) {
            anna.send("hello | world");
            String[] atBob = bob.expect(Protocol.MSG);
            assertEquals("anna", atBob[2]);
            assertEquals("hello | world", atBob[3]);
            assertEquals("hello | world", anna.expect(Protocol.MSG)[3]);
        }
    }

    @Test
    public void privateMessageReachesOnlyTheTarget() throws IOException {
        try (TestClient anna = connect("anna"); TestClient bob = connect("bob"); TestClient carl = connect("carl")) {
            anna.send("/to bob secret");
            String[] pm = bob.expect(Protocol.PM);
            assertArrayEquals(new String[]{"anna", "bob", "secret"}, new String[]{pm[2], pm[3], pm[4]});
            anna.expect(Protocol.PM); // echo for the sender
            carl.assertNothing(Protocol.PM);
        }
    }

    @Test
    public void excludeSkipsListedUsers() throws IOException {
        try (TestClient anna = connect("anna"); TestClient bob = connect("bob"); TestClient carl = connect("carl")) {
            anna.send("/exclude bob surprise party");
            assertEquals("surprise party", carl.expect(Protocol.MSG)[3]);
            bob.assertNothing(Protocol.MSG);
        }
    }

    @Test
    public void bannedPhrasesAreBlockedCaseInsensitiveEverywhere() throws IOException {
        try (TestClient anna = connect("anna"); TestClient bob = connect("bob")) {
            anna.send("this is FORBIDDEN");
            anna.expect(Protocol.ERR);
            anna.send("/to bob forbidden stuff");
            anna.expect(Protocol.ERR);
            bob.assertNothing(Protocol.MSG);
            bob.assertNothing(Protocol.PM);
        }
    }

    @Test
    public void newUserReceivesRecentHistory() throws IOException {
        try (TestClient anna = connect("anna")) {
            for (int i = 1; i <= ChatServer.HISTORY_SIZE + 5; i++) {
                anna.send("msg " + i);
            }
            for (int i = 1; i <= ChatServer.HISTORY_SIZE + 5; i++) {
                anna.expect(Protocol.MSG);
            }
            try (TestClient late = new TestClient(server.getPort())) {
                late.send(Protocol.encode(Protocol.LOGIN, "late"));
                late.expect(Protocol.LOGIN_OK);
                assertEquals("msg 6", late.expect(Protocol.MSG)[3]); // only the last 20 are kept
            }
        }
    }

    @Test
    public void leavingUpdatesOthers() throws IOException {
        try (TestClient anna = connect("anna")) {
            TestClient bob = connect("bob");
            anna.expect(Protocol.USERS);
            anna.expect(Protocol.USERS);
            bob.send("/quit");
            assertTrue(anna.expect(Protocol.SYS)[2].contains("bob left"));
            assertEquals("anna", anna.expect(Protocol.USERS)[1]);
            bob.close();
        }
    }

    // ---- helpers ------------------------------------------------------------------

    private TestClient connect(String name) throws IOException {
        TestClient c = new TestClient(server.getPort());
        c.send(Protocol.encode(Protocol.LOGIN, name));
        c.expect(Protocol.LOGIN_OK);
        return c;
    }

    /** Minimal client that talks the raw protocol. */
    private static class TestClient implements Closeable {
        private final Socket socket;
        private final BufferedReader in;
        private final PrintWriter out;

        TestClient(int port) throws IOException {
            socket = new Socket("localhost", port);
            socket.setSoTimeout(2000);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        }

        void send(String line) {
            out.println(line);
        }

        /** Skips other lines until one of the given type arrives. */
        String[] expect(String type) throws IOException {
            while (true) {
                String line;
                try {
                    line = in.readLine();
                } catch (SocketTimeoutException e) {
                    throw new AssertionError("Timed out waiting for " + type);
                }
                if (line == null) throw new AssertionError("Connection closed while waiting for " + type);
                String[] parts = Protocol.decode(line);
                if (parts[0].equals(type)) return parts;
            }
        }

        void assertNothing(String type) throws IOException {
            socket.setSoTimeout(300);
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    assertNotEquals("Unexpected " + line, type, Protocol.decode(line)[0]);
                }
            } catch (SocketTimeoutException expected) {
                // nothing more arrived
            } finally {
                socket.setSoTimeout(2000);
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
