package chat.common;

/**
 * Line-based text protocol shared by the server and the client.
 * <p>
 * Every line is {@code TYPE|field1|field2|...}. The last field is free text and may
 * itself contain {@code |}, which is why lines are split with a fixed field count.
 *
 * <pre>
 * client -> server   LOGIN|name            first line after connecting
 *                    any other line        chat message or /command
 *
 * server -> client   LOGIN_OK|serverName
 *                    LOGIN_FAIL|reason
 *                    MSG|time|sender|text  public message
 *                    PM|time|from|to|text  private message
 *                    SYS|time|text         server notification
 *                    ERR|text              error for this client only
 *                    USERS|a,b,c           current list of online users
 * </pre>
 */
public final class Protocol {

    public static final String LOGIN = "LOGIN";
    public static final String LOGIN_OK = "LOGIN_OK";
    public static final String LOGIN_FAIL = "LOGIN_FAIL";
    public static final String MSG = "MSG";
    public static final String PM = "PM";
    public static final String SYS = "SYS";
    public static final String ERR = "ERR";
    public static final String USERS = "USERS";

    public static final int MAX_USERNAME_LENGTH = 20;

    private Protocol() {
    }

    public static String encode(String type, String... fields) {
        StringBuilder sb = new StringBuilder(type);
        for (String field : fields) {
            sb.append('|').append(field.replace('\n', ' ').replace('\r', ' '));
        }
        return sb.toString();
    }

    /** Splits a line into its type and fields; the result always starts with the type. */
    public static String[] decode(String line) {
        int sep = line.indexOf('|');
        String type = sep < 0 ? line : line.substring(0, sep);
        return line.split("\\|", fieldCount(type) + 1);
    }

    private static int fieldCount(String type) {
        return switch (type) {
            case MSG -> 3;
            case PM -> 4;
            case SYS -> 2;
            default -> 1;
        };
    }
}
