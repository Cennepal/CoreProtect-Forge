package net.coreprotect.command;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import net.coreprotect.CoreProtect;
import net.coreprotect.database.Database;
import net.coreprotect.lookup.Action;
import net.coreprotect.lookup.LookupParams;
import net.coreprotect.lookup.ParamParser;
import net.coreprotect.lookup.Query;
import net.coreprotect.utility.Chat;
import net.coreprotect.utility.WorldUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

/**
 * /co purge t:&lt;time&gt; [r:#world] [i:&lt;blocks&gt;] [#optimize]
 */
public final class PurgeCommand {

    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final int BATCH = 10_000;

    private PurgeCommand() {
    }

    public static void run(CommandSourceStack source, String[] args) throws ParamParser.ParseException {
        if (!Permissions.has(source, Permissions.PURGE)) {
            source.sendSystemMessage(Chat.error("You do not have permission to do that."));
            return;
        }
        LookupParams params = ParamParser.parse(source.getServer(), args, null, 0, 0, 0);
        if (!params.timeSpecified || params.maxTime != Long.MAX_VALUE) {
            source.sendSystemMessage(Chat.error("Please use \"/co purge t:<time>\"."));
            return;
        }
        if (!params.users.isEmpty() || !params.excludedUsers.isEmpty() || !params.actions.isEmpty() || params.preview || params.radius != null) {
            source.sendSystemMessage(Chat.error("Purges only support t:, r:#world, i:, e: and #optimize."));
            return;
        }
        long seconds = WorldUtil.now() - params.minTime;
        boolean player = source.getEntity() != null;
        long minimum = player ? 30L * 86400 : 86400;
        if (seconds < minimum) {
            source.sendSystemMessage(Chat.error("You can only purge data older than " + (player ? "30 days" : "24 hours") + "."));
            return;
        }
        if (CoreProtect.rollbackManager().isBusy()) {
            source.sendSystemMessage(Chat.error("A rollback/restore is in progress. Please try again later."));
            return;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            source.sendSystemMessage(Chat.error("Purge in progress. Please try again later."));
            return;
        }
        String target = params.world != null ? WorldUtil.shortWorldName(params.world) : "#global";
        source.sendSystemMessage(Chat.message("Data purge started on \"" + target + "\"."));
        source.sendSystemMessage(Chat.message("Please note that this may take some time."));
        source.sendSystemMessage(Chat.message("Do not restart your server until completed."));

        MinecraftServer server = source.getServer();
        Database database = CoreProtect.database();
        CoreProtect.worker().execute(() -> {
            try {
                long rows = purge(database, params);
                if (params.optimize) {
                    server.execute(() -> source.sendSystemMessage(Chat.message("Optimizing database. Please wait...")));
                    optimize(database);
                }
                server.execute(() -> {
                    source.sendSystemMessage(Chat.message("Data purge successful."));
                    source.sendSystemMessage(Chat.message(Chat.plural(rows, "row", "rows") + " of data deleted."));
                });
            }
            catch (Exception e) {
                CoreProtect.LOGGER.error("Purge failed", e);
                server.execute(() -> source.sendSystemMessage(Chat.error("Purge failed. Please try again later.")));
            }
            finally {
                RUNNING.set(false);
            }
        });
    }

    private static long purge(Database database, LookupParams params) throws SQLException, Query.InvalidException {
        long total = 0;
        Query query = new Query(database);
        // Everything older than the cutoff is deleted, so the lower time bound becomes the upper bound.
        LookupParams cutoff = params.copy();
        cutoff.maxTime = params.minTime - 1;
        cutoff.minTime = 0;
        boolean typed = !cutoff.include.isEmpty() || !cutoff.exclude.isEmpty();
        try (Connection connection = database.connect()) {
            Query.Resolved resolved = query.resolve(connection, cutoff);
            List<Action.Table> tables = new ArrayList<>();
            for (Action.Table table : Action.Table.values()) {
                if ((typed && !Query.hasType(table)) || (cutoff.world != null && !Query.hasLocation(table))) {
                    continue;
                }
                tables.add(table);
            }
            for (Action.Table table : tables) {
                Query.Where where = query.where(table, resolved, null, Query.Mode.PURGE);
                String name = database.table(table.table);
                String sql = database.isMySQL()
                        // Single-table DELETE (no alias) is the only form that supports LIMIT on every MySQL/MariaDB version.
                        ? "DELETE FROM " + name + " WHERE " + where.sql().replace("t.", "") + " LIMIT " + BATCH
                        : "DELETE FROM " + name + " WHERE id IN (SELECT t.id FROM " + name + " t WHERE " + where.sql() + " LIMIT " + BATCH + ")";
                while (true) {
                    int deleted;
                    try (PreparedStatement ps = connection.prepareStatement(sql)) {
                        where.bind(ps, 1);
                        deleted = ps.executeUpdate();
                    }
                    total += deleted;
                    if (deleted < BATCH) {
                        break;
                    }
                }
            }
        }
        return total;
    }

    private static void optimize(Database database) throws SQLException {
        try (Connection connection = database.connect(); Statement statement = connection.createStatement()) {
            if (database.isMySQL()) {
                for (Action.Table table : Action.Table.values()) {
                    try (ResultSet ignored = statement.executeQuery("OPTIMIZE TABLE " + database.table(table.table))) {
                        // Result set only contains status messages.
                    }
                }
            }
            else {
                statement.execute("VACUUM");
            }
        }
    }
}
