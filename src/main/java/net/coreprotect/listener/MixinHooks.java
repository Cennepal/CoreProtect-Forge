package net.coreprotect.listener;

import java.util.ArrayList;
import java.util.List;

import net.coreprotect.CoreProtect;
import net.coreprotect.config.Config;
import net.coreprotect.consumer.Entry;
import net.coreprotect.consumer.Log;
import net.coreprotect.utility.WorldUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Called from mixins for actions Forge has no event for.
 */
public final class MixinHooks {

    private MixinHooks() {
    }

    public static void signEdited(SignBlockEntity sign, Player player, boolean front) {
        if (!CoreProtect.isEnabled() || !Config.SIGN_TEXT.get() || !(sign.getLevel() instanceof ServerLevel level)) {
            return;
        }
        List<String> lines = new ArrayList<>();
        for (Component line : sign.getText(front).getMessages(false)) {
            lines.add(line.getString());
        }
        Log.sign(WorldUtil.actor(player), level, sign.getBlockPos(), front, String.join("\n", lines));
    }

    public static void blockBurned(Level level, BlockPos pos, BlockState before) {
        if (!CoreProtect.isEnabled() || !Config.BLOCK_BURN.get() || !(level instanceof ServerLevel)) {
            return;
        }
        BlockState after = level.getBlockState(pos);
        if (after == before || before.isAir() || after.getBlock() == before.getBlock()) {
            return;
        }
        Log.blockBreak(Entry.Actor.of("#fire"), level, pos, before, null);
    }

    public static void leafDecay(ServerLevel level, BlockPos pos, BlockState state) {
        if (!CoreProtect.isEnabled() || !Config.LEAF_DECAY.get()) {
            return;
        }
        Log.blockBreak(Entry.Actor.of("#decay"), level, pos, state, null);
    }

    public static void mobBrokeDoor(Entity mob, BlockPos pos) {
        if (!CoreProtect.isEnabled() || !Config.ENTITY_CHANGE.get() || !(mob.level() instanceof ServerLevel level)) {
            return;
        }
        // Tracked rather than logged directly, so the other half of the door is logged too.
        Entry.Actor actor = WorldUtil.actor(mob);
        CoreProtect.blockTracker().track(level, pos, actor, BlockTracker.Kind.DIRECT);
        CoreProtect.blockTracker().trackNeighbors(level, pos, actor);
    }

    public static void endermanTake(Entity enderman, BlockPos pos) {
        if (!CoreProtect.isEnabled() || !Config.ENTITY_CHANGE.get() || !(enderman.level() instanceof ServerLevel level)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!state.isAir()) {
            Log.blockBreak(WorldUtil.actor(enderman), level, pos, state, null);
        }
    }
}
