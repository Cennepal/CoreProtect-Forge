package net.coreprotect.consumer;

import net.coreprotect.CoreProtect;
import net.coreprotect.utility.WorldUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Entry points used by listeners (and the public API) to record actions. Must be called on the server thread
 * (or any thread, as long as the arguments are not mutated afterwards).
 */
public final class Log {

    public static final int BLOCK_BREAK = 0;
    public static final int BLOCK_PLACE = 1;
    public static final int BLOCK_CLICK = 2;
    public static final int ENTITY_KILL = 3;

    public static final int CONTAINER_REMOVE = 0;
    public static final int CONTAINER_ADD = 1;

    public static final int ITEM_DROP = 0;
    public static final int ITEM_PICKUP = 1;

    private Log() {
    }

    private static void queue(Entry entry) {
        Consumer consumer = CoreProtect.consumer();
        if (consumer != null) {
            consumer.queue(entry);
        }
    }

    public static void blockBreak(Entry.Actor actor, LevelAccessor level, BlockPos pos, BlockState state, CompoundTag blockEntity) {
        if (state.isAir()) {
            return;
        }
        queue(new Entry.Block(WorldUtil.now(), actor, WorldUtil.location(level, pos), WorldUtil.blockId(state), WorldUtil.serialize(state), copy(blockEntity), BLOCK_BREAK));
    }

    public static void blockPlace(Entry.Actor actor, LevelAccessor level, BlockPos pos, BlockState state, CompoundTag blockEntity) {
        if (state.isAir()) {
            return;
        }
        queue(new Entry.Block(WorldUtil.now(), actor, WorldUtil.location(level, pos), WorldUtil.blockId(state), WorldUtil.serialize(state), copy(blockEntity), BLOCK_PLACE));
    }

    public static void blockClick(Entry.Actor actor, LevelAccessor level, BlockPos pos, BlockState state) {
        queue(new Entry.Block(WorldUtil.now(), actor, WorldUtil.location(level, pos), WorldUtil.blockId(state), WorldUtil.serialize(state), null, BLOCK_CLICK));
    }

    public static void entityKill(Entry.Actor actor, LevelAccessor level, BlockPos pos, String entityType, CompoundTag entityData) {
        queue(new Entry.Block(WorldUtil.now(), actor, WorldUtil.location(level, pos), entityType, null, copy(entityData), ENTITY_KILL));
    }

    public static void container(Entry.Actor actor, LevelAccessor level, BlockPos pos, ItemStack stack, int slot, int amount, int action) {
        if (stack.isEmpty() || amount <= 0) {
            return;
        }
        queue(new Entry.Container(WorldUtil.now(), actor, WorldUtil.location(level, pos), WorldUtil.itemId(stack.getItem()), slot, amount, copy(stack.getTag()), action));
    }

    public static void item(Entry.Actor actor, LevelAccessor level, BlockPos pos, ItemStack stack, int amount, int action) {
        if (stack.isEmpty() || amount <= 0) {
            return;
        }
        queue(new Entry.Item(WorldUtil.now(), actor, WorldUtil.location(level, pos), WorldUtil.itemId(stack.getItem()), amount, copy(stack.getTag()), action));
    }

    public static void chat(Entry.Actor actor, Entry.Location location, String message) {
        queue(new Entry.Chat(WorldUtil.now(), actor, location, message));
    }

    public static void command(Entry.Actor actor, Entry.Location location, String command) {
        queue(new Entry.Command(WorldUtil.now(), actor, location, command));
    }

    public static void session(Entry.Actor actor, Entry.Location location, boolean login) {
        queue(new Entry.Session(WorldUtil.now(), actor, location, login ? 1 : 0));
    }

    public static void login(Entry.Actor actor) {
        queue(new Entry.Login(WorldUtil.now(), actor));
    }

    public static void sign(Entry.Actor actor, LevelAccessor level, BlockPos pos, boolean front, String text) {
        queue(new Entry.Sign(WorldUtil.now(), actor, WorldUtil.location(level, pos), front ? 0 : 1, text));
    }

    private static CompoundTag copy(CompoundTag tag) {
        return tag == null || tag.isEmpty() ? null : tag.copy();
    }
}
