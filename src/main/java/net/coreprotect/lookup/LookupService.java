package net.coreprotect.lookup;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.coreprotect.database.Database;

/**
 * Runs paginated lookups across one or more tables.
 */
public final class LookupService {

    public record Page(List<LookupRow> rows, long total, Map<Action.Table, Long> counts, int page, int perPage) {
        public int pages() {
            return (int) Math.max(1, (total + perPage - 1) / perPage);
        }
    }

    private final Database database;
    private final Query query;

    public LookupService(Database database) {
        this.database = database;
        this.query = new Query(database);
    }

    public Query query() {
        return query;
    }

    public Page lookup(LookupParams params, int page, int perPage, boolean countOnly) throws SQLException, Query.InvalidException {
        try (Connection connection = database.connect()) {
            Query.Resolved resolved = query.resolve(connection, params);
            Map<Action.Table, Long> counts = new EnumMap<>(Action.Table.class);
            long total = 0;
            for (Action.Table table : params.tables()) {
                Query.Where where = query.where(table, resolved, params.codes(table), Query.Mode.LOOKUP);
                try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*)" + query.from(table) + " WHERE " + where.sql())) {
                    where.bind(ps, 1);
                    try (ResultSet rs = ps.executeQuery()) {
                        long count = rs.next() ? rs.getLong(1) : 0;
                        counts.put(table, count);
                        total += count;
                    }
                }
            }
            if (countOnly || total == 0) {
                return new Page(List.of(), total, counts, page, perPage);
            }

            long offset = (long) (page - 1) * perPage;
            Set<Action.Table> tables = params.tables();
            List<LookupRow> merged = new ArrayList<>();
            if (tables.size() == 1) {
                Action.Table table = tables.iterator().next();
                merged.addAll(fetch(connection, table, resolved, params, perPage, offset));
                return new Page(merged, total, counts, page, perPage);
            }
            // Several tables: the first offset + perPage rows of each table contain the requested page.
            long limit = offset + perPage;
            for (Action.Table table : tables) {
                if (counts.getOrDefault(table, 0L) > 0) {
                    merged.addAll(fetch(connection, table, resolved, params, limit, 0));
                }
            }
            merged.sort(LookupRow::compareNewestFirst);
            int from = (int) Math.min(offset, merged.size());
            int to = (int) Math.min(offset + perPage, merged.size());
            return new Page(new ArrayList<>(merged.subList(from, to)), total, counts, page, perPage);
        }
    }

    private List<LookupRow> fetch(Connection connection, Action.Table table, Query.Resolved resolved, LookupParams params, long limit, long offset) throws SQLException {
        Query.Where where = query.where(table, resolved, params.codes(table), Query.Mode.LOOKUP);
        String sql = query.select(table) + " WHERE " + where.sql() + " ORDER BY t.time DESC, t.id DESC LIMIT ? OFFSET ?";
        List<LookupRow> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            where.bind(ps, 1);
            ps.setLong(where.args().size() + 1, limit);
            ps.setLong(where.args().size() + 2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(Query.read(table, rs));
                }
            }
        }
        return rows;
    }
}
