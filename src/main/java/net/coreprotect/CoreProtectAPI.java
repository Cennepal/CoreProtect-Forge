package net.coreprotect;

import java.util.List;

import net.coreprotect.consumer.Entry;
import net.coreprotect.consumer.Log;
import net.coreprotect.lookup.Action;
import net.coreprotect.lookup.LookupParams;
import net.coreprotect.lookup.LookupRow;
import net.coreprotect.lookup.ParamParser;
import net.coreprotect.utility.WorldUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Public API for other mods.
 * <p>
 * Logging methods must be called on the server thread. Lookup methods block while the database is queried,
 * so call them from your own worker thread rather than the server thread.
 */
public final class CoreProtectAPI {

    private CoreProtectAPI() {
    }

    public static int apiVersion() {
        return 1;
    }

    /** True once the database is connected and logging is active. */
    public static boolean isEnabled() {
        return CoreProtect.isEnabled();
    }

    /**
     * Logs a block placement. {@code user} is a player name, or a "#name" for non-player sources.
     */
    public static void logPlacement(String user, ServerLevel level, BlockPos pos, BlockState state) {
        if (isEnabled()) {
            Log.blockPlace(actor(user, level), level, pos, state, WorldUtil.blockEntityData(level, pos));
        }
    }

    /**
     * Logs a block removal. Call this before the block is removed so its block entity data is captured.
     */
    public static void logRemoval(String user, ServerLevel level, BlockPos pos, BlockState state) {
        if (isEnabled()) {
            Log.blockBreak(actor(user, level), level, pos, state, WorldUtil.blockEntityData(level, pos));
        }
    }

    public static void logInteraction(String user, ServerLevel level, BlockPos pos) {
        if (isEnabled()) {
            Log.blockClick(actor(user, level), level, pos, level.getBlockState(pos));
        }
    }

    public static void logChat(ServerPlayer player, String message) {
        if (isEnabled()) {
            Log.chat(WorldUtil.actor(player), WorldUtil.location(player), message);
        }
    }

    public static void logCommand(ServerPlayer player, String command) {
        if (isEnabled()) {
            Log.command(WorldUtil.actor(player), WorldUtil.location(player), command.startsWith("/") ? command : "/" + command);
        }
    }

    /**
     * Returns the block history (placements, removals, interactions and kills) at a position, newest first.
     *
     * @param seconds how far back to look; 0 for no limit
     */
    public static List<LookupRow> blockLookup(ServerLevel level, BlockPos pos, int seconds) throws Exception {
        LookupParams params = new LookupParams();
        params.world = WorldUtil.worldName(level);
        params.global = true;
        params.exactLocations = new int[][] { { 0, pos.getX(), pos.getY(), pos.getZ() } };
        if (seconds > 0) {
            params.minTime = WorldUtil.now() - seconds;
            params.timeSpecified = true;
        }
        params.actions.add(Action.BLOCK);
        params.actions.add(Action.CLICK);
        params.actions.add(Action.KILL);
        return lookup(params, 1000);
    }

    /**
     * Runs a lookup using command syntax, e.g. {@code performLookup(level, pos, "u:Notch", "t:1h", "a:container")}.
     * Radius parameters are relative to {@code pos} in {@code level}.
     */
    public static List<LookupRow> performLookup(ServerLevel level, BlockPos pos, String... parameters) throws Exception {
        LookupParams params = ParamParser.parse(level.getServer(), parameters, WorldUtil.worldName(level), pos.getX(), pos.getY(), pos.getZ());
        if (params.actions.isEmpty()) {
            params.actions.add(Action.BLOCK);
            params.actions.add(Action.CLICK);
            params.actions.add(Action.KILL);
        }
        return lookup(params, 10_000);
    }

    private static List<LookupRow> lookup(LookupParams params, int limit) throws Exception {
        if (!isEnabled()) {
            return List.of();
        }
        CoreProtect.consumer().flushAndWait(5_000);
        return CoreProtect.lookupService().lookup(params, 1, limit, false).rows();
    }

    private static Entry.Actor actor(String user, ServerLevel level) {
        if (!user.startsWith("#")) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayerByName(user);
            if (player != null) {
                return WorldUtil.actor(player);
            }
        }
        return Entry.Actor.of(user);
    }
}
