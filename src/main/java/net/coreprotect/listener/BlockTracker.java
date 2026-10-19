package net.coreprotect.listener;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import net.coreprotect.config.Config;
import net.coreprotect.consumer.Entry;
import net.coreprotect.consumer.Log;
import net.coreprotect.utility.WorldUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Records the state of positions that are about to be changed by an action and compares them with the
 * world at the end of the server tick. This captures exactly what an action changed, including blocks that
 * broke as a consequence (torches, doors, plants), while ignoring actions that another mod cancelled.
 */
public final class BlockTracker {

    public enum Kind {
        /** A direct player or entity action on this position (break, place, explosion, ...). */
        DIRECT,
        /** A right-click on a block. Same-block state changes are logged as interactions. */
        INTERACT,
        /**
         * A position next to (or in a column above/below) a changed block. Only removals are logged. These are
         * watched for a short while, because some blocks (sugar cane, cactus, bamboo, ...) break a tick or more
         * after losing their support.
         */
        NEIGHBOR
    }

    private record Key(ServerLevel level, long pos) {
    }

    private static final class Pending {
        final ServerLevel level;
        final BlockPos pos;
        final BlockState oldState;
        CompoundTag oldData;
        Entry.Actor actor;
        Kind kind;
        int ticksLeft = NEIGHBOR_TICKS;

        Pending(ServerLevel level, BlockPos pos, BlockState oldState, CompoundTag oldData, Entry.Actor actor, Kind kind) {
            this.level = level;
            this.pos = pos;
            this.oldState = oldState;
            this.oldData = oldData;
            this.actor = actor;
            this.kind = kind;
        }
    }

    private static final int MAX_COLUMN = 32;
    private static final int NEIGHBOR_TICKS = MAX_COLUMN + 8;

    private final Map<Key, Pending> pending = new LinkedHashMap<>();

    /**
     * Tracks a position whose current state is still unchanged.
     */
    public void track(ServerLevel level, BlockPos pos, Entry.Actor actor, Kind kind) {
        if (!level.isLoaded(pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        CompoundTag data = state.hasBlockEntity() ? WorldUtil.blockEntityData(level, pos) : null;
        Pending existing = pending.get(new Key(level, pos.asLong()));
        if (existing != null && existing.oldState == state) {
            // Still unchanged: refresh the block entity data (a chest's contents may have changed since).
            existing.oldData = data;
        }
        track(level, pos, state, data, actor, kind);
    }

    /**
     * Tracks a position, supplying its state from before the change (for events fired after the change).
     */
    public void track(ServerLevel level, BlockPos pos, BlockState oldState, CompoundTag oldData, Entry.Actor actor, Kind kind) {
        Key key = new Key(level, pos.asLong());
        Pending existing = pending.get(key);
        if (existing == null) {
            pending.put(key, new Pending(level, pos.immutable(), oldState, oldData, actor, kind));
        }
        else if (rank(kind) > rank(existing.kind)) {
            existing.actor = actor;
            existing.kind = kind;
        }
    }

    private static int rank(Kind kind) {
        return switch (kind) {
            case NEIGHBOR -> 0;
            case INTERACT -> 1;
            case DIRECT -> 2;
        };
    }

    /**
     * Tracks the neighbours of a position, plus contiguous columns of the same block above and below it
     * (sugar cane, bamboo, cactus, kelp, vines, dripstone, scaffolding, ...).
     */
    public void trackNeighbors(ServerLevel level, BlockPos pos, Entry.Actor actor) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = pos.relative(direction);
            track(level, neighbor, actor, Kind.NEIGHBOR);
            if (direction.getAxis() == Direction.Axis.Y && level.isLoaded(neighbor)) {
                Block column = level.getBlockState(neighbor).getBlock();
                BlockPos next = neighbor.relative(direction);
                for (int i = 0; i < MAX_COLUMN && level.isLoaded(next) && !level.isOutsideBuildHeight(next); i++) {
                    BlockState state = level.getBlockState(next);
                    if (state.isAir() || state.getBlock() != column) {
                        break;
                    }
                    track(level, next, actor, Kind.NEIGHBOR);
                    next = next.relative(direction);
                }
            }
        }
    }

    /** Clears everything tracked for a level (used when a level unloads). */
    public void forget(ServerLevel level) {
        pending.keySet().removeIf(key -> key.level() == level);
    }

    /**
     * Compares every tracked position with the world and logs the differences.
     */
    public void process() {
        if (pending.isEmpty()) {
            return;
        }
        Iterator<Pending> iterator = pending.values().iterator();
        while (iterator.hasNext()) {
            Pending p = iterator.next();
            if (!p.level.isLoaded(p.pos)) {
                iterator.remove();
                continue;
            }
            BlockState newState = p.level.getBlockState(p.pos);
            if (p.oldState == newState) {
                if (p.kind != Kind.NEIGHBOR || --p.ticksLeft <= 0) {
                    iterator.remove();
                }
                continue;
            }
            iterator.remove();
            log(p, p.oldState, newState);
        }
    }

    private static void log(Pending p, BlockState oldState, BlockState newState) {
        if (p.kind == Kind.NEIGHBOR && oldState.getBlock() instanceof FallingBlock) {
            // Sand and gravel fall rather than break; logging them would duplicate them on rollback.
            return;
        }
        boolean sameBlock = oldState.getBlock() == newState.getBlock();
        if (p.kind == Kind.INTERACT && !Config.BUCKETS.get() && !(sameBlock && !waterloggedChanged(oldState, newState))) {
            return;
        }
        if (sameBlock) {
            if (p.kind == Kind.NEIGHBOR) {
                // Shape updates (fence connections, redstone power, ...) are not player actions.
                return;
            }
            if (p.kind == Kind.INTERACT && !waterloggedChanged(oldState, newState)) {
                if (Config.PLAYER_INTERACTIONS.get()) {
                    Log.blockClick(p.actor, p.level, p.pos, newState);
                }
                return;
            }
            // Partial changes like a slab becoming a double slab: log as a replacement so it rolls back cleanly.
            Log.blockBreak(p.actor, p.level, p.pos, oldState, p.oldData);
            Log.blockPlace(p.actor, p.level, p.pos, newState, newData(p, newState));
            return;
        }

        if (!oldState.isAir() && !isFlowingFluid(oldState)) {
            Log.blockBreak(p.actor, p.level, p.pos, oldState, p.oldData);
        }
        if (!newState.isAir() && !isIncidentalFluid(p, oldState, newState)) {
            Log.blockPlace(p.actor, p.level, p.pos, newState, newData(p, newState));
        }
    }

    private static CompoundTag newData(Pending p, BlockState state) {
        return state.hasBlockEntity() ? WorldUtil.blockEntityData(p.level, p.pos) : null;
    }

    private static boolean waterloggedChanged(BlockState a, BlockState b) {
        return a.hasProperty(BlockStateProperties.WATERLOGGED) && b.hasProperty(BlockStateProperties.WATERLOGGED)
                && a.getValue(BlockStateProperties.WATERLOGGED) != b.getValue(BlockStateProperties.WATERLOGGED);
    }

    /**
     * Water flowing into a gap, or the water left behind by a broken waterlogged block, is not something the
     * actor placed.
     */
    private static boolean isIncidentalFluid(Pending p, BlockState oldState, BlockState newState) {
        if (!(newState.getBlock() instanceof LiquidBlock)) {
            return false;
        }
        return p.kind == Kind.NEIGHBOR || !newState.getFluidState().isSource()
                || oldState.getFluidState().getType().isSame(newState.getFluidState().getType());
    }

    private static boolean isFlowingFluid(BlockState state) {
        return state.getBlock() instanceof LiquidBlock && !state.getFluidState().isSource();
    }
}
