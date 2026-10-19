package net.coreprotect.lookup;

/**
 * One row of lookup results from any table.
 */
public final class LookupRow {

    public Action.Table table;
    public long id;
    public long time;
    public String user;
    public String world;
    public int x;
    public int y;
    public int z;
    /** Block, item or entity id. */
    public String type;
    /** Serialized block state (block table only). */
    public String data;
    public int action;
    public boolean rolledBack;
    public int amount;
    public int slot;
    public byte[] meta;
    /** Chat message, command, sign text, or the new username. */
    public String message;

    public static int compareNewestFirst(LookupRow a, LookupRow b) {
        int cmp = Long.compare(b.time, a.time);
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(b.table.ordinal(), a.table.ordinal());
        return cmp != 0 ? cmp : Long.compare(b.id, a.id);
    }
}
