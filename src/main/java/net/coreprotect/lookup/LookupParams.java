package net.coreprotect.lookup;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Parsed lookup / rollback / restore / purge parameters. Times are absolute (epoch seconds) so an operation
 * can be repeated later (undo, apply) with exactly the same result set.
 */
public final class LookupParams {

    public final Set<String> users = new LinkedHashSet<>();
    public final Set<String> excludedUsers = new LinkedHashSet<>();
    public final Set<String> include = new LinkedHashSet<>();
    public final Set<String> exclude = new LinkedHashSet<>();
    public final Set<Action> actions = EnumSet.noneOf(Action.class);

    /** Inclusive lower bound, or 0 for none. */
    public long minTime;
    /** Inclusive upper bound. */
    public long maxTime = Long.MAX_VALUE;
    public boolean timeSpecified;
    /** The time argument as typed, for messages. */
    public String timeText;

    /** Block radius around (centerX, centerZ) in centerWorld; null for none. */
    public Integer radius;
    /** Restricts results to this world (set by a radius or r:#world). */
    public String world;
    public int centerX;
    public int centerY;
    public int centerZ;
    public boolean global;

    /** Exact location(s), used by the inspector. */
    public int[][] exactLocations;

    public boolean preview;
    public boolean count;
    public boolean optimize;
    public boolean silent;

    public LookupParams copy() {
        LookupParams copy = new LookupParams();
        copy.users.addAll(users);
        copy.excludedUsers.addAll(excludedUsers);
        copy.include.addAll(include);
        copy.exclude.addAll(exclude);
        copy.actions.addAll(actions);
        copy.minTime = minTime;
        copy.maxTime = maxTime;
        copy.timeSpecified = timeSpecified;
        copy.timeText = timeText;
        copy.radius = radius;
        copy.world = world;
        copy.centerX = centerX;
        copy.centerY = centerY;
        copy.centerZ = centerZ;
        copy.global = global;
        copy.exactLocations = exactLocations;
        copy.preview = preview;
        copy.count = count;
        copy.optimize = optimize;
        copy.silent = silent;
        return copy;
    }

    /** Actions grouped by table. */
    public Set<Action.Table> tables() {
        Set<Action.Table> tables = EnumSet.noneOf(Action.Table.class);
        for (Action action : actions) {
            tables.add(action.table);
        }
        return tables;
    }

    /** Action codes requested for a table, or null if every action in that table is included. */
    public Set<Integer> codes(Action.Table table) {
        Set<Integer> codes = new LinkedHashSet<>();
        for (Action action : actions) {
            if (action.table == table) {
                for (int code : action.codes()) {
                    codes.add(code);
                }
            }
        }
        return codes;
    }
}
