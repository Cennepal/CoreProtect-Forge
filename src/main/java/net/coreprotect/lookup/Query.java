package net.coreprotect.lookup;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.coreprotect.database.Database;

/**
 * Builds SQL for lookups, rollbacks and purges from {@link LookupParams}.
 */
public final class Query {

    public enum Mode {
        LOOKUP,
        ROLLBACK,
        RESTORE,
        PURGE
    }

    /** Thrown when a parameter refers to something that does not exist (e.g. an unknown user). */
    public static final class InvalidException extends Exception {
        public InvalidException(String message) {
            super(message);
        }
    }

    /** Parameters with names resolved to database ids. */
    public static final class Resolved {
        final LookupParams params;
        final Set<Integer> users = new LinkedHashSet<>();
        final Set<String> userUuids = new LinkedHashSet<>();
        final Set<Integer> excludedUsers = new LinkedHashSet<>();
        final Set<Integer> types = new LinkedHashSet<>();
        final Set<Integer> excludedTypes = new LinkedHashSet<>();
        Integer world;
        final List<int[]> locations = new ArrayList<>();
        /** True when the filters can never match (e.g. an included block was never logged). */
        boolean empty;

        Resolved(LookupParams params) {
            this.params = params;
        }
    }

    private final Database database;

    public Query(Database database) {
        this.database = database;
    }

    public Resolved resolve(Connection connection, LookupParams params) throws SQLException, InvalidException {
        Resolved resolved = new Resolved(params);
        for (String name : params.users) {
            Set<Integer> ids = userIds(connection, name, resolved.userUuids);
            if (ids.isEmpty()) {
                throw new InvalidException("User \"" + name + "\" not found.");
            }
            resolved.users.addAll(ids);
        }
        for (String name : params.excludedUsers) {
            resolved.excludedUsers.addAll(userIds(connection, name, new LinkedHashSet<>()));
        }
        if (!params.include.isEmpty()) {
            resolved.types.addAll(mapIds(connection, "material_map", "material", params.include));
            if (resolved.types.isEmpty()) {
                resolved.empty = true;
            }
        }
        if (!params.exclude.isEmpty()) {
            resolved.excludedTypes.addAll(mapIds(connection, "material_map", "material", params.exclude));
        }
        if (params.world != null) {
            Set<Integer> worlds = mapIds(connection, "world", "world", List.of(params.world));
            if (worlds.isEmpty()) {
                resolved.empty = true;
            }
            else {
                resolved.world = worlds.iterator().next();
            }
        }
        if (params.exactLocations != null) {
            for (int[] location : params.exactLocations) {
                // location = { worldIndex (unused), x, y, z }
                resolved.locations.add(location);
            }
        }
        return resolved;
    }

    private Set<Integer> userIds(Connection connection, String name, Set<String> uuidsOut) throws SQLException {
        Set<Integer> ids = new LinkedHashSet<>();
        Set<String> uuids = new LinkedHashSet<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT id, uuid FROM " + database.table("user") + " WHERE LOWER(user) = ?")) {
            ps.setString(1, name.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getInt(1));
                    if (rs.getString(2) != null) {
                        uuids.add(rs.getString(2));
                    }
                }
            }
        }
        if (!name.startsWith("#")) {
            // Players who have since changed their name.
            Set<String> previous = new LinkedHashSet<>();
            try (PreparedStatement ps = connection.prepareStatement("SELECT uuid FROM " + database.table("username_log") + " WHERE LOWER(user) = ?")) {
                ps.setString(1, name.toLowerCase(Locale.ROOT));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (!uuids.contains(rs.getString(1))) {
                            previous.add(rs.getString(1));
                        }
                    }
                }
            }
            for (String uuid : previous) {
                try (PreparedStatement ps = connection.prepareStatement("SELECT id FROM " + database.table("user") + " WHERE uuid = ?")) {
                    ps.setString(1, uuid);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            ids.add(rs.getInt(1));
                        }
                    }
                }
                uuids.add(uuid);
            }
        }
        uuidsOut.addAll(uuids);
        return ids;
    }

    private Set<Integer> mapIds(Connection connection, String table, String column, Collection<String> values) throws SQLException {
        Set<Integer> ids = new LinkedHashSet<>();
        for (String value : values) {
            try (PreparedStatement ps = connection.prepareStatement("SELECT id FROM " + database.table(table) + " WHERE " + column + " = ?")) {
                ps.setString(1, value);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getInt(1));
                    }
                }
            }
        }
        return ids;
    }

    /** A WHERE clause plus its arguments. */
    public record Where(String sql, List<Object> args) {
        public void bind(PreparedStatement ps, int offset) throws SQLException {
            for (int i = 0; i < args.size(); i++) {
                Object arg = args.get(i);
                if (arg instanceof Long l) {
                    ps.setLong(offset + i, l);
                }
                else if (arg instanceof Integer n) {
                    ps.setInt(offset + i, n);
                }
                else {
                    ps.setString(offset + i, String.valueOf(arg));
                }
            }
        }
    }

    public static boolean hasLocation(Action.Table table) {
        return table != Action.Table.USERNAME;
    }

    public static boolean hasType(Action.Table table) {
        return table == Action.Table.BLOCK || table == Action.Table.CONTAINER || table == Action.Table.ITEM;
    }

    public static boolean hasRolledBack(Action.Table table) {
        return table == Action.Table.BLOCK || table == Action.Table.CONTAINER;
    }

    /**
     * Builds the WHERE clause for a table. Column references use the alias "t".
     */
    public Where where(Action.Table table, Resolved resolved, Set<Integer> actionCodes, Mode mode) {
        LookupParams params = resolved.params;
        StringBuilder sql = new StringBuilder();
        List<Object> args = new ArrayList<>();
        sql.append(resolved.empty ? "1 = 0" : "1 = 1");

        if (params.minTime > 0) {
            sql.append(" AND t.time >= ?");
            args.add(params.minTime);
        }
        if (params.maxTime != Long.MAX_VALUE) {
            sql.append(" AND t.time <= ?");
            args.add(params.maxTime);
        }

        if (table == Action.Table.USERNAME) {
            if (!resolved.userUuids.isEmpty()) {
                sql.append(" AND t.uuid IN (").append(placeholders(resolved.userUuids.size())).append(")");
                args.addAll(resolved.userUuids);
            }
            else if (!params.users.isEmpty()) {
                sql.append(" AND 1 = 0");
            }
            return new Where(sql.toString(), args);
        }

        if (!resolved.users.isEmpty()) {
            sql.append(" AND t.user IN (").append(placeholders(resolved.users.size())).append(")");
            args.addAll(resolved.users);
        }
        if (!resolved.excludedUsers.isEmpty()) {
            sql.append(" AND t.user NOT IN (").append(placeholders(resolved.excludedUsers.size())).append(")");
            args.addAll(resolved.excludedUsers);
        }
        if (hasType(table)) {
            if (!resolved.types.isEmpty()) {
                sql.append(" AND t.type IN (").append(placeholders(resolved.types.size())).append(")");
                args.addAll(resolved.types);
            }
            if (!resolved.excludedTypes.isEmpty()) {
                sql.append(" AND t.type NOT IN (").append(placeholders(resolved.excludedTypes.size())).append(")");
                args.addAll(resolved.excludedTypes);
            }
        }
        if (resolved.world != null) {
            sql.append(" AND t.wid = ?");
            args.add(resolved.world);
        }
        if (params.radius != null && !params.global) {
            sql.append(" AND t.x >= ? AND t.x <= ? AND t.z >= ? AND t.z <= ?");
            args.add(params.centerX - params.radius);
            args.add(params.centerX + params.radius);
            args.add(params.centerZ - params.radius);
            args.add(params.centerZ + params.radius);
        }
        if (!resolved.locations.isEmpty()) {
            sql.append(" AND (");
            boolean first = true;
            for (int[] location : resolved.locations) {
                if (!first) {
                    sql.append(" OR ");
                }
                first = false;
                // Repeating the world in each term lets the database use the location index for every OR branch.
                if (resolved.world != null) {
                    sql.append("(t.wid = ? AND t.x = ? AND t.y = ? AND t.z = ?)");
                    args.add(resolved.world);
                }
                else {
                    sql.append("(t.x = ? AND t.y = ? AND t.z = ?)");
                }
                args.add(location[1]);
                args.add(location[2]);
                args.add(location[3]);
            }
            sql.append(")");
        }
        if (actionCodes != null && !actionCodes.isEmpty() && (table == Action.Table.BLOCK || table == Action.Table.CONTAINER
                || table == Action.Table.ITEM || table == Action.Table.SESSION)) {
            sql.append(" AND t.action IN (").append(placeholders(actionCodes.size())).append(")");
            args.addAll(actionCodes);
        }
        if (hasRolledBack(table)) {
            if (mode == Mode.ROLLBACK) {
                sql.append(" AND t.rolled_back = 0");
            }
            else if (mode == Mode.RESTORE) {
                sql.append(" AND t.rolled_back = 1");
            }
        }
        return new Where(sql.toString(), args);
    }

    /**
     * SELECT clause for lookups; the column order is read by {@link #read}.
     */
    public String select(Action.Table table) {
        String t = database.table(table.table);
        String joins = " LEFT JOIN " + database.table("user") + " u ON u.id = t.user LEFT JOIN " + database.table("world") + " w ON w.id = t.wid";
        String material = " LEFT JOIN " + database.table("material_map") + " m ON m.id = t.type";
        return switch (table) {
            case BLOCK -> "SELECT t.id, t.time, u.user, w.world, t.x, t.y, t.z, m.material, d.data, t.action, t.rolled_back, t.meta FROM " + t + " t" + joins + material
                    + " LEFT JOIN " + database.table("blockdata_map") + " d ON d.id = t.data";
            case CONTAINER -> "SELECT t.id, t.time, u.user, w.world, t.x, t.y, t.z, m.material, t.slot, t.amount, t.metadata, t.action, t.rolled_back FROM " + t + " t" + joins + material;
            case ITEM -> "SELECT t.id, t.time, u.user, w.world, t.x, t.y, t.z, m.material, t.amount, t.metadata, t.action FROM " + t + " t" + joins + material;
            case CHAT, COMMAND -> "SELECT t.id, t.time, u.user, w.world, t.x, t.y, t.z, t.message FROM " + t + " t" + joins;
            case SESSION -> "SELECT t.id, t.time, u.user, w.world, t.x, t.y, t.z, t.action FROM " + t + " t" + joins;
            case SIGN -> "SELECT t.id, t.time, u.user, w.world, t.x, t.y, t.z, t.side, t.text FROM " + t + " t" + joins;
            case USERNAME -> "SELECT t.id, t.time, t.user, t.uuid, (SELECT u.user FROM " + database.table("user") + " u WHERE u.uuid = t.uuid ORDER BY u.id LIMIT 1) FROM " + t + " t";
        };
    }

    public String from(Action.Table table) {
        return " FROM " + database.table(table.table) + " t";
    }

    public static LookupRow read(Action.Table table, ResultSet rs) throws SQLException {
        LookupRow row = new LookupRow();
        row.table = table;
        row.id = rs.getLong(1);
        row.time = rs.getLong(2);
        if (table == Action.Table.USERNAME) {
            row.message = rs.getString(3);
            String current = rs.getString(5);
            row.user = current != null ? current : rs.getString(4);
            return row;
        }
        row.user = rs.getString(3);
        row.world = rs.getString(4);
        row.x = rs.getInt(5);
        row.y = rs.getInt(6);
        row.z = rs.getInt(7);
        switch (table) {
            case BLOCK -> {
                row.type = rs.getString(8);
                row.data = rs.getString(9);
                row.action = rs.getInt(10);
                row.rolledBack = rs.getInt(11) == 1;
                row.meta = rs.getBytes(12);
            }
            case CONTAINER -> {
                row.type = rs.getString(8);
                row.slot = rs.getInt(9);
                row.amount = rs.getInt(10);
                row.meta = rs.getBytes(11);
                row.action = rs.getInt(12);
                row.rolledBack = rs.getInt(13) == 1;
            }
            case ITEM -> {
                row.type = rs.getString(8);
                row.amount = rs.getInt(9);
                row.meta = rs.getBytes(10);
                row.action = rs.getInt(11);
            }
            case CHAT, COMMAND -> row.message = rs.getString(8);
            case SESSION -> row.action = rs.getInt(8);
            case SIGN -> {
                row.action = rs.getInt(8);
                row.message = rs.getString(9);
            }
            default -> {
            }
        }
        return row;
    }

    public static String placeholders(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('?');
        }
        return sb.toString();
    }
}
