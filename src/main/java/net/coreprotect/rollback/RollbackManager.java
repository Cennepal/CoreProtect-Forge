package net.coreprotect.rollback;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.coreprotect.CoreProtect;
import net.coreprotect.config.Config;
import net.coreprotect.consumer.Log;
import net.coreprotect.database.Database;
import net.coreprotect.lookup.Action;
import net.coreprotect.lookup.LookupParams;
import net.coreprotect.lookup.LookupRow;
import net.coreprotect.lookup.Query;
import net.coreprotect.utility.Chat;
import net.coreprotect.utility.NbtCodec;
import net.coreprotect.utility.WorldUtil;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Runs rollbacks, restores and previews. Database work happens on the worker thread; world changes are
 * applied on the server thread, a limited number per tick.
 */
public final class RollbackManager {

    /** Where results are reported. */
    public interface Reporter {
        void send(Component message);
    }

    private record BlockChange(String world, BlockPos pos, BlockState state, CompoundTag data) {
    }

    private record EntityChange(String world, CompoundTag data, boolean spawn) {
    }

    private record ItemChange(String world, BlockPos pos, ItemStack stack, int slot, boolean add) {
    }

    private record PreviewBlock(ServerLevel level, BlockPos pos) {
    }

    /** A pending preview, applied or cancelled with /co apply and /co cancel. */
    public record Preview(LookupParams params, boolean restore, List<PreviewBlock> blocks) {
    }

    /** The last rollback/restore per command sender, for /co undo. */
    public record Completed(LookupParams params, boolean restore) {
    }

    private final class Operation {
        final LookupParams params;
        final boolean restore;
        final boolean preview;
        final ServerPlayer previewPlayer;
        final Reporter reporter;
        final String senderKey;
        final long started = System.currentTimeMillis();

        List<BlockChange> blocks = List.of();
        List<EntityChange> entities = List.of();
        List<ItemChange> items = List.of();
        List<Long> blockIds = List.of();
        List<Long> containerIds = List.of();
        int index;
        int blocksChanged;
        int entitiesChanged;
        int itemsChanged;
        final java.util.HashSet<Long> chunks = new java.util.HashSet<>();
        final List<PreviewBlock> previewBlocks = new ArrayList<>();
        volatile boolean ready;
        volatile boolean aborted;
        boolean finishing;

        Operation(LookupParams params, boolean restore, ServerPlayer previewPlayer, Reporter reporter, String senderKey) {
            this.params = params;
            this.restore = restore;
            this.preview = previewPlayer != null;
            this.previewPlayer = previewPlayer;
            this.reporter = reporter;
            this.senderKey = senderKey;
        }

        String label() {
            return preview ? "Preview" : restore ? "Restore" : "Rollback";
        }
    }

    private final MinecraftServer server;
    private final Database database;
    private final Query query;
    private volatile Operation current;
    private final Map<UUID, Preview> previews = new ConcurrentHashMap<>();
    private final Map<String, Completed> completed = new ConcurrentHashMap<>();
    private final Map<String, BlockState> stateCache = new HashMap<>();

    public RollbackManager(MinecraftServer server, Database database) {
        this.server = server;
        this.database = database;
        this.query = new Query(database);
    }

    public boolean isBusy() {
        return current != null;
    }

    public Preview preview(UUID player) {
        return previews.get(player);
    }

    public Completed lastCompleted(String senderKey) {
        return completed.get(senderKey);
    }

    /**
     * Starts a rollback or restore. Must be called on the server thread.
     *
     * @param previewPlayer non-null to only show the changes to this player
     */
    public boolean start(LookupParams params, boolean restore, ServerPlayer previewPlayer, Reporter reporter, String senderKey) {
        if (current != null) {
            reporter.send(Chat.error("A rollback/restore is already in progress."));
            return false;
        }
        if (previewPlayer != null) {
            cancelPreview(previewPlayer);
        }
        Operation operation = new Operation(params, restore, previewPlayer, reporter, senderKey);
        current = operation;
        reporter.send(Chat.message(operation.label() + " started on \"" + target(params) + "\"."));
        CoreProtect.worker().execute(() -> load(operation));
        return true;
    }

    /** Called every tick on the server thread. */
    public void tick() {
        Operation operation = current;
        if (operation == null || !operation.ready || operation.finishing) {
            return;
        }
        if (operation.aborted) {
            current = null;
            return;
        }
        int budget = Config.ROLLBACK_CHANGES_PER_TICK.get();
        long deadline = System.nanoTime() + 40_000_000L;
        try {
            while (budget > 0 && System.nanoTime() < deadline) {
                int i = operation.index;
                if (i < operation.blocks.size()) {
                    applyBlock(operation, operation.blocks.get(i));
                }
                else if (i < operation.blocks.size() + operation.entities.size()) {
                    applyEntity(operation, operation.entities.get(i - operation.blocks.size()));
                }
                else if (i < operation.blocks.size() + operation.entities.size() + operation.items.size()) {
                    applyItem(operation, operation.items.get(i - operation.blocks.size() - operation.entities.size()));
                }
                else if (!operation.preview && i < 2 * operation.blocks.size() + operation.entities.size() + operation.items.size()) {
                    fixShapes(operation.blocks.get(i - operation.blocks.size() - operation.entities.size() - operation.items.size()));
                }
                else {
                    finish(operation);
                    return;
                }
                operation.index++;
                budget--;
            }
        }
        catch (RuntimeException e) {
            CoreProtect.LOGGER.error("Error while applying {}", operation.label().toLowerCase(), e);
            operation.reporter.send(Chat.error(operation.label() + " aborted: " + e.getMessage()));
            current = null;
        }
    }

    /** Aborts any running operation (used when the server stops). */
    public void abort() {
        Operation operation = current;
        if (operation != null) {
            operation.aborted = true;
            current = null;
        }
        previews.clear();
    }

    public void cancelPreview(ServerPlayer player) {
        Preview preview = previews.remove(player.getUUID());
        if (preview != null) {
            for (PreviewBlock block : preview.blocks()) {
                if (block.level() == player.serverLevel()) {
                    WorldApplier.resetPreview(player, block.level(), block.pos());
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------

    private void applyBlock(Operation operation, BlockChange change) {
        ServerLevel level = WorldUtil.level(server, change.world());
        if (level == null) {
            return;
        }
        operation.chunks.add(((long) change.world().hashCode() << 32) ^ net.minecraft.world.level.ChunkPos.asLong(change.pos().getX() >> 4, change.pos().getZ() >> 4));
        if (operation.preview) {
            if (level == operation.previewPlayer.serverLevel()) {
                WorldApplier.preview(operation.previewPlayer, change.pos(), change.state());
                operation.previewBlocks.add(new PreviewBlock(level, change.pos()));
                operation.blocksChanged++;
            }
            return;
        }
        if (WorldApplier.setBlock(level, change.pos(), change.state(), change.data())) {
            operation.blocksChanged++;
        }
    }

    /**
     * Blocks are set without neighbour updates (so nothing pops off mid-rollback). Afterwards, reconnect the
     * shapes of changed blocks and their neighbours: double chests, fences, panes, walls, stairs, redstone
     * wire. Only property changes are applied; a block is never removed here.
     */
    private void fixShapes(BlockChange change) {
        ServerLevel level = WorldUtil.level(server, change.world());
        if (level == null) {
            return;
        }
        WorldApplier.fixShape(level, change.pos());
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            WorldApplier.fixShape(level, change.pos().relative(direction));
        }
    }

    private void applyEntity(Operation operation, EntityChange change) {
        if (operation.preview) {
            return;
        }
        ServerLevel level = WorldUtil.level(server, change.world());
        if (level == null) {
            return;
        }
        boolean changed = change.spawn() ? WorldApplier.spawnEntity(level, change.data()) : WorldApplier.removeEntity(level, change.data());
        if (changed) {
            operation.entitiesChanged++;
        }
    }

    private void applyItem(Operation operation, ItemChange change) {
        if (operation.preview) {
            return;
        }
        ServerLevel level = WorldUtil.level(server, change.world());
        if (level == null) {
            return;
        }
        operation.itemsChanged += WorldApplier.changeItems(level, change.pos(), change.stack(), change.slot(), change.add());
    }

    private void finish(Operation operation) {
        operation.finishing = true;
        if (operation.preview) {
            current = null;
            previews.put(operation.previewPlayer.getUUID(), new Preview(operation.params, operation.restore, operation.previewBlocks));
            report(operation);
            return;
        }
        completed.put(operation.senderKey, new Completed(operation.params, operation.restore));
        List<Long> blockIds = operation.blockIds;
        List<Long> containerIds = operation.containerIds;
        int flag = operation.restore ? 0 : 1;
        CoreProtect.worker().execute(() -> {
            try (Connection connection = database.connect()) {
                connection.setAutoCommit(false);
                markRolledBack(connection, "block", blockIds, flag);
                markRolledBack(connection, "container", containerIds, flag);
                connection.commit();
            }
            catch (SQLException e) {
                CoreProtect.LOGGER.error("Unable to update rollback flags", e);
            }
            server.execute(() -> {
                // Only now can another operation see the updated rolled_back flags.
                if (current == operation) {
                    current = null;
                }
                report(operation);
            });
        });
    }

    private void markRolledBack(Connection connection, String table, List<Long> ids, int flag) throws SQLException {
        final int batch = 500;
        for (int start = 0; start < ids.size(); start += batch) {
            List<Long> part = ids.subList(start, Math.min(ids.size(), start + batch));
            try (PreparedStatement ps = connection.prepareStatement("UPDATE " + database.table(table) + " SET rolled_back = ? WHERE id IN (" + Query.placeholders(part.size()) + ")")) {
                ps.setInt(1, flag);
                for (int i = 0; i < part.size(); i++) {
                    ps.setLong(i + 2, part.get(i));
                }
                ps.executeUpdate();
            }
        }
    }

    private void report(Operation operation) {
        LookupParams params = operation.params;
        Reporter r = operation.reporter;
        r.send(Chat.text("-----"));
        r.send(Chat.message(operation.label() + " completed for \"" + target(params) + "\"."));
        if (params.timeSpecified) {
            long seconds = WorldUtil.now() - params.minTime;
            r.send(Chat.text("Time range: " + (params.timeText != null ? params.timeText : Chat.duration(seconds)) + "."));
        }
        if (params.radius != null && !params.global) {
            r.send(Chat.text("Radius: " + Chat.plural(params.radius, "block", "blocks") + "."));
        }
        else if (params.world != null) {
            r.send(Chat.text("Restricted to world \"" + WorldUtil.shortWorldName(params.world) + "\"."));
        }
        if (!params.include.isEmpty()) {
            r.send(Chat.text("Included: \"" + String.join(", ", params.include.stream().map(WorldUtil::displayId).toList()) + "\"."));
        }
        if (!params.exclude.isEmpty()) {
            r.send(Chat.text("Excluded: \"" + String.join(", ", params.exclude.stream().map(WorldUtil::displayId).toList()) + "\"."));
        }
        if (!params.excludedUsers.isEmpty()) {
            r.send(Chat.text("Excluded users: \"" + String.join(", ", params.excludedUsers) + "\"."));
        }
        r.send(Chat.text("Modified " + Chat.plural(operation.chunks.size(), "chunk", "chunks") + "."));
        r.send(Chat.text("Approx. " + Chat.plural(operation.blocksChanged, "block", "blocks") + " changed."));
        if (!operation.preview) {
            if (operation.entitiesChanged > 0 || !operation.entities.isEmpty()) {
                r.send(Chat.text("Approx. " + Chat.plural(operation.entitiesChanged, "entity", "entities") + " changed."));
            }
            if (operation.itemsChanged > 0 || !operation.items.isEmpty()) {
                r.send(Chat.text("Approx. " + Chat.plural(operation.itemsChanged, "item", "items") + " changed."));
            }
        }
        double seconds = (System.currentTimeMillis() - operation.started) / 1000.0;
        r.send(Chat.text(String.format(java.util.Locale.ROOT, "Time taken: %.2f %s.", seconds, seconds == 1.0 ? "second" : "seconds")));
        if (operation.preview) {
            r.send(Chat.text("Use ").append(Chat.command("/co apply", "/co apply", "Apply the preview"))
                    .append(Chat.text(" to apply the changes, or ")).append(Chat.command("/co cancel", "/co cancel", "Cancel the preview"))
                    .append(Chat.text(" to cancel.")));
        }
        r.send(Chat.text("-----"));
    }

    public static String target(LookupParams params) {
        if (!params.users.isEmpty()) {
            return String.join(", ", params.users);
        }
        if (params.global && params.world == null) {
            return "#global";
        }
        if (params.world != null && params.radius == null) {
            return "#" + WorldUtil.shortWorldName(params.world);
        }
        return "#radius";
    }

    // ------------------------------------------------------------------------------------------------
    // Loading (worker thread)
    // ------------------------------------------------------------------------------------------------

    private void load(Operation operation) {
        try {
            CoreProtect.consumer().flushAndWait(10_000);
            LookupParams params = operation.params;
            try (Connection connection = database.connect()) {
                Query.Resolved resolved = query.resolve(connection, params);
                Query.Mode mode = operation.restore ? Query.Mode.RESTORE : Query.Mode.ROLLBACK;
                String order = operation.restore ? " ORDER BY t.time ASC, t.id ASC" : " ORDER BY t.time DESC, t.id DESC";

                Set<Integer> blockCodes = params.codes(Action.Table.BLOCK);
                blockCodes.remove(Log.BLOCK_CLICK);
                Map<String, LookupRow> finalRows = new LinkedHashMap<>();
                List<EntityChange> entities = new ArrayList<>();
                List<Long> blockIds = new ArrayList<>();
                if (!blockCodes.isEmpty()) {
                    Query.Where where = query.where(Action.Table.BLOCK, resolved, blockCodes, mode);
                    try (PreparedStatement ps = connection.prepareStatement(query.select(Action.Table.BLOCK) + " WHERE " + where.sql() + order)) {
                        where.bind(ps, 1);
                        ps.setFetchSize(10_000);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                if (operation.aborted) {
                                    return;
                                }
                                LookupRow row = Query.read(Action.Table.BLOCK, rs);
                                if (row.world == null) {
                                    continue;
                                }
                                if (row.action == Log.ENTITY_KILL) {
                                    if (Config.ROLLBACK_ENTITIES.get() && !"minecraft:player".equals(row.type)) {
                                        CompoundTag data = NbtCodec.decode(row.meta);
                                        if (data != null) {
                                            entities.add(new EntityChange(row.world, data, !operation.restore));
                                        }
                                    }
                                    blockIds.add(row.id);
                                    continue;
                                }
                                blockIds.add(row.id);
                                // The last row seen for a position decides its final state.
                                finalRows.put(row.world + "|" + row.x + "|" + row.y + "|" + row.z, row);
                            }
                        }
                    }
                }

                List<BlockChange> blocks = new ArrayList<>(finalRows.size());
                for (LookupRow row : finalRows.values()) {
                    boolean placeTarget = operation.restore ? row.action == Log.BLOCK_PLACE : row.action == Log.BLOCK_BREAK;
                    BlockState state = Blocks.AIR.defaultBlockState();
                    CompoundTag data = null;
                    if (placeTarget) {
                        state = parseState(row.data, row.type);
                        if (state == null) {
                            continue;
                        }
                        data = NbtCodec.decode(row.meta);
                    }
                    blocks.add(new BlockChange(row.world, new BlockPos(row.x, row.y, row.z), state, data));
                }
                blocks.sort(Comparator.comparing(BlockChange::world)
                        .thenComparingInt(b -> b.pos().getX() >> 4)
                        .thenComparingInt(b -> b.pos().getZ() >> 4)
                        .thenComparingInt(b -> b.pos().getY()));

                List<ItemChange> items = new ArrayList<>();
                List<Long> containerIds = new ArrayList<>();
                Set<Integer> containerCodes = params.codes(Action.Table.CONTAINER);
                if (!containerCodes.isEmpty() && !operation.preview) {
                    Query.Where where = query.where(Action.Table.CONTAINER, resolved, containerCodes, mode);
                    try (PreparedStatement ps = connection.prepareStatement(query.select(Action.Table.CONTAINER) + " WHERE " + where.sql() + order)) {
                        where.bind(ps, 1);
                        ps.setFetchSize(10_000);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                if (operation.aborted) {
                                    return;
                                }
                                LookupRow row = Query.read(Action.Table.CONTAINER, rs);
                                containerIds.add(row.id);
                                ItemStack stack = stack(row);
                                if (row.world == null || stack.isEmpty()) {
                                    continue;
                                }
                                // Rolling back a removal puts the items back; rolling back an addition takes them out.
                                boolean add = operation.restore ? row.action == Log.CONTAINER_ADD : row.action == Log.CONTAINER_REMOVE;
                                items.add(new ItemChange(row.world, new BlockPos(row.x, row.y, row.z), stack, row.slot, add));
                            }
                        }
                    }
                }

                operation.blocks = blocks;
                operation.entities = operation.preview ? List.of() : entities;
                operation.items = items;
                operation.blockIds = blockIds;
                operation.containerIds = containerIds;
                if (operation.preview) {
                    operation.blockIds = List.of();
                }
                operation.ready = true;
            }
        }
        catch (Query.InvalidException e) {
            fail(operation, e.getMessage());
        }
        catch (Exception e) {
            CoreProtect.LOGGER.error("Unable to load {} data", operation.label().toLowerCase(), e);
            fail(operation, operation.label() + " failed: " + e.getMessage());
        }
    }

    private void fail(Operation operation, String message) {
        server.execute(() -> {
            if (current == operation) {
                current = null;
            }
            operation.reporter.send(Chat.error(message));
        });
    }

    private BlockState parseState(String data, String type) {
        String key = data != null ? data : type;
        if (key == null) {
            return null;
        }
        synchronized (stateCache) {
            if (stateCache.containsKey(key)) {
                return stateCache.get(key);
            }
        }
        BlockState state = null;
        if (data != null) {
            try {
                state = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), data, false).blockState();
            }
            catch (CommandSyntaxException | RuntimeException ignored) {
                // Fall back to the block's default state (properties may have changed between versions).
            }
        }
        if (state == null && type != null) {
            ResourceLocation id = ResourceLocation.tryParse(type);
            if (id != null && BuiltInRegistries.BLOCK.containsKey(id)) {
                Block block = BuiltInRegistries.BLOCK.get(id);
                state = block.defaultBlockState();
            }
        }
        synchronized (stateCache) {
            stateCache.put(key, state);
        }
        return state;
    }

    private static ItemStack stack(LookupRow row) {
        ResourceLocation id = ResourceLocation.tryParse(row.type);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id) || row.amount <= 0) {
            return ItemStack.EMPTY;
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        ItemStack stack = new ItemStack(item, row.amount);
        CompoundTag tag = NbtCodec.decode(row.meta);
        if (tag != null) {
            stack.setTag(tag);
        }
        return stack;
    }
}
