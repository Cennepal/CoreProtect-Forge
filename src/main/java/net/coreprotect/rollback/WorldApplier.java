package net.coreprotect.rollback;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Clearable;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

/**
 * Applies rollback/restore changes to the world. Server thread only.
 */
public final class WorldApplier {

    /** Clients are updated, but neighbours are not notified and nothing drops. */
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    private WorldApplier() {
    }

    /**
     * Sets a block (and its block entity data). Returns true if the world changed.
     */
    public static boolean setBlock(ServerLevel level, BlockPos pos, BlockState target, CompoundTag data) {
        if (level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)) {
            return false;
        }
        BlockState current = level.getBlockState(pos);
        if (current == target) {
            if (data == null) {
                return false;
            }
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity == null) {
                return false;
            }
            clear(blockEntity);
            loadData(level, pos, target, blockEntity, data);
            return true;
        }

        // Empty inventories first so nothing is dropped (and duplicated) when the block is replaced.
        BlockEntity existing = level.getBlockEntity(pos);
        if (existing != null) {
            clear(existing);
        }
        if (!level.setBlock(pos, target, FLAGS)) {
            return false;
        }
        if (data != null) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity != null) {
                loadData(level, pos, target, blockEntity, data);
            }
        }
        FluidState fluid = target.getFluidState();
        if (!fluid.isEmpty()) {
            level.scheduleTick(pos, fluid.getType(), fluid.getType().getTickDelay(level));
        }
        return true;
    }

    /** Updates a block's connection properties from its current neighbours, without changing the block type. */
    public static void fixShape(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return;
        }
        BlockState updated = Block.updateFromNeighbourShapes(state, level, pos);
        if (updated != state && updated.getBlock() == state.getBlock()) {
            level.setBlock(pos, updated, FLAGS);
        }
    }

    private static void loadData(ServerLevel level, BlockPos pos, BlockState state, BlockEntity blockEntity, CompoundTag data) {
        CompoundTag tag = data.copy();
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        try {
            blockEntity.load(tag);
        }
        catch (RuntimeException ignored) {
            // Data from an incompatible mod version; keep the block with default data.
        }
        blockEntity.setChanged();
        level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL);
    }

    private static void clear(BlockEntity blockEntity) {
        if (blockEntity instanceof Clearable) {
            Clearable.tryClear(blockEntity);
            return;
        }
        blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, null).ifPresent(handler -> {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (!stack.isEmpty()) {
                    handler.extractItem(slot, stack.getCount(), false);
                }
            }
        });
    }

    public static void preview(ServerPlayer player, BlockPos pos, BlockState state) {
        player.connection.send(new ClientboundBlockUpdatePacket(pos, state));
    }

    public static void resetPreview(ServerPlayer player, ServerLevel level, BlockPos pos) {
        if (level.isLoaded(pos)) {
            player.connection.send(new ClientboundBlockUpdatePacket(level, pos));
        }
    }

    /**
     * Respawns a killed entity. Returns true if an entity was added.
     */
    public static boolean spawnEntity(ServerLevel level, CompoundTag data) {
        if (data == null || !data.contains("id")) {
            return false;
        }
        if (data.hasUUID("UUID")) {
            UUID uuid = data.getUUID("UUID");
            if (level.getEntity(uuid) != null) {
                return false;
            }
        }
        Entity entity = EntityType.loadEntityRecursive(data.copy(), level, e -> e);
        if (entity == null) {
            return false;
        }
        if (entity instanceof LivingEntity living) {
            living.setHealth(living.getMaxHealth());
            living.deathTime = 0;
            living.hurtTime = 0;
        }
        entity.clearFire();
        entity.setDeltaMovement(0, 0, 0);
        entity.fallDistance = 0;
        return level.addFreshEntity(entity);
    }

    /** Removes an entity that a previous rollback respawned. */
    public static boolean removeEntity(ServerLevel level, CompoundTag data) {
        if (data == null || !data.hasUUID("UUID")) {
            return false;
        }
        Entity entity = level.getEntity(data.getUUID("UUID"));
        if (entity == null || entity.isRemoved()) {
            return false;
        }
        entity.discard();
        return true;
    }

    /**
     * Adds or removes items from the inventory at a position. Returns the number of items moved.
     */
    public static int changeItems(ServerLevel level, BlockPos pos, ItemStack stack, int slot, boolean add) {
        if (!level.isLoaded(pos)) {
            level.getChunk(pos);
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return 0;
        }
        int moved;
        if (blockEntity instanceof Container container) {
            moved = add ? insert(container, stack, slot) : extract(container, stack, slot);
        }
        else {
            IItemHandler handler = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, null).orElse(null);
            if (handler == null) {
                return 0;
            }
            moved = add ? insert(handler, stack, slot) : extract(handler, stack, slot);
        }
        if (moved > 0) {
            blockEntity.setChanged();
        }
        return moved;
    }

    private static int insert(Container container, ItemStack stack, int preferred) {
        int remaining = stack.getCount();
        int size = container.getContainerSize();
        int[] order = slotOrder(preferred, size);
        // First top up matching stacks (starting with the original slot), then use empty slots.
        for (int pass = 0; pass < 2 && remaining > 0; pass++) {
            for (int slot : order) {
                if (remaining <= 0) {
                    break;
                }
                ItemStack existing = container.getItem(slot);
                int limit = Math.min(container.getMaxStackSize(), stack.getMaxStackSize());
                if (pass == 0 && !existing.isEmpty() && ItemStack.isSameItemSameTags(existing, stack) && existing.getCount() < limit) {
                    int add = Math.min(remaining, limit - existing.getCount());
                    existing.grow(add);
                    container.setItem(slot, existing);
                    remaining -= add;
                }
                else if (pass == 1 && existing.isEmpty() && container.canPlaceItem(slot, stack)) {
                    int add = Math.min(remaining, limit);
                    container.setItem(slot, stack.copyWithCount(add));
                    remaining -= add;
                }
            }
        }
        return stack.getCount() - remaining;
    }

    private static int extract(Container container, ItemStack stack, int preferred) {
        int remaining = stack.getCount();
        for (int slot : slotOrder(preferred, container.getContainerSize())) {
            if (remaining <= 0) {
                break;
            }
            ItemStack existing = container.getItem(slot);
            if (!existing.isEmpty() && ItemStack.isSameItemSameTags(existing, stack)) {
                int take = Math.min(remaining, existing.getCount());
                existing.shrink(take);
                container.setItem(slot, existing.isEmpty() ? ItemStack.EMPTY : existing);
                remaining -= take;
            }
        }
        return stack.getCount() - remaining;
    }

    private static int insert(IItemHandler handler, ItemStack stack, int preferred) {
        ItemStack remaining = stack.copy();
        if (preferred >= 0 && preferred < handler.getSlots()) {
            remaining = handler.insertItem(preferred, remaining, false);
        }
        if (!remaining.isEmpty()) {
            remaining = ItemHandlerHelper.insertItemStacked(handler, remaining, false);
        }
        return stack.getCount() - remaining.getCount();
    }

    private static int extract(IItemHandler handler, ItemStack stack, int preferred) {
        int remaining = stack.getCount();
        for (int slot : slotOrder(preferred, handler.getSlots())) {
            if (remaining <= 0) {
                break;
            }
            ItemStack existing = handler.getStackInSlot(slot);
            if (!existing.isEmpty() && ItemStack.isSameItemSameTags(existing, stack)) {
                ItemStack taken = handler.extractItem(slot, Math.min(remaining, existing.getCount()), false);
                remaining -= taken.getCount();
            }
        }
        return stack.getCount() - remaining;
    }

    private static int[] slotOrder(int preferred, int size) {
        int[] order = new int[size];
        int index = 0;
        if (preferred >= 0 && preferred < size) {
            order[index++] = preferred;
        }
        for (int slot = 0; slot < size; slot++) {
            if (slot != preferred) {
                order[index++] = slot;
            }
        }
        return order;
    }
}
