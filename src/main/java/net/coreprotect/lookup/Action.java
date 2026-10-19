package net.coreprotect.lookup;

import java.util.List;
import java.util.Locale;

/**
 * Values accepted by the a: parameter.
 */
public enum Action {
    BLOCK(Table.BLOCK, "block", 0, 1),
    BLOCK_REMOVE(Table.BLOCK, "-block", 0),
    BLOCK_PLACE(Table.BLOCK, "+block", 1),
    CLICK(Table.BLOCK, "click", 2),
    KILL(Table.BLOCK, "kill", 3),
    CONTAINER(Table.CONTAINER, "container", 0, 1),
    CONTAINER_REMOVE(Table.CONTAINER, "-container", 0),
    CONTAINER_ADD(Table.CONTAINER, "+container", 1),
    ITEM(Table.ITEM, "item", 0, 1),
    ITEM_DROP(Table.ITEM, "-item", 0),
    ITEM_PICKUP(Table.ITEM, "+item", 1),
    CHAT(Table.CHAT, "chat"),
    COMMAND(Table.COMMAND, "command"),
    SESSION(Table.SESSION, "session", 0, 1),
    LOGOUT(Table.SESSION, "-session", 0),
    LOGIN(Table.SESSION, "+session", 1),
    SIGN(Table.SIGN, "sign"),
    USERNAME(Table.USERNAME, "username");

    public enum Table {
        BLOCK("block", "block"),
        CONTAINER("container", "container"),
        ITEM("item", "item"),
        CHAT("chat", "chat"),
        COMMAND("command", "command"),
        SESSION("session", "session"),
        SIGN("sign", "sign"),
        USERNAME("username_log", "username");

        public final String table;
        public final String permission;

        Table(String table, String permission) {
            this.table = table;
            this.permission = permission;
        }
    }

    public final Table table;
    public final String name;
    private final int[] codes;

    Action(Table table, String name, int... codes) {
        this.table = table;
        this.name = name;
        this.codes = codes;
    }

    public int[] codes() {
        return codes.clone();
    }

    public static Action parse(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "break", "broke", "remove", "removed" -> lower = "-block";
            case "place", "placed" -> lower = "+block";
            case "kills", "killed" -> lower = "kill";
            case "clicks", "interact", "interaction" -> lower = "click";
            case "chest", "containers" -> lower = "container";
            case "items" -> lower = "item";
            case "commands", "cmd" -> lower = "command";
            case "message", "messages" -> lower = "chat";
            case "login", "logins" -> lower = "+session";
            case "logout", "logouts" -> lower = "-session";
            case "sessions" -> lower = "session";
            case "signs" -> lower = "sign";
            case "usernames", "name", "names" -> lower = "username";
            default -> {
            }
        }
        for (Action action : values()) {
            if (action.name.equals(lower)) {
                return action;
            }
        }
        return null;
    }

    public static final List<String> SUGGESTIONS = List.of("block", "+block", "-block", "click", "kill", "container", "+container", "-container",
            "item", "+item", "-item", "chat", "command", "session", "+session", "-session", "sign", "username");
}
