package net.coreprotect.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.coreprotect.config.Config;
import net.coreprotect.lookup.Action;
import net.coreprotect.lookup.LookupParams;
import net.coreprotect.utility.WorldUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

/**
 * The block inspector ("/co inspect"): left-click a block to see its history, right-click a container to
 * see its transactions, right-click a sign to see its text history, right-click a block face to see what
 * happened next to it.
 */
public final class Inspector {

    private static final Set<UUID> INSPECTING = ConcurrentHashMap.newKeySet();

    private Inspector() {
    }

    public static boolean isInspecting(ServerPlayer player) {
        return INSPECTING.contains(player.getUUID());
    }

    public static boolean toggle(ServerPlayer player, Boolean enable) {
        boolean target = enable != null ? enable : !isInspecting(player);
        if (target) {
            INSPECTING.add(player.getUUID());
        }
        else {
            INSPECTING.remove(player.getUUID());
        }
        return target;
    }

    public static void forget(ServerPlayer player) {
        INSPECTING.remove(player.getUUID());
    }

    public static void clear() {
        INSPECTING.clear();
    }

    public static void inspectBlock(ServerPlayer player, ServerLevel level, BlockPos pos) {
        LookupParams params = base(level, List.of(pos));
        params.actions.add(Action.BLOCK);
        params.actions.add(Action.KILL);
        run(player, params, pos);
    }

    public static void inspectInteraction(ServerPlayer player, ServerLevel level, BlockPos pos, Direction face) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof SignBlockEntity) {
            LookupParams params = base(level, List.of(pos));
            params.actions.add(Action.SIGN);
            run(player, params, pos);
            return;
        }
        if (blockEntity != null && (blockEntity instanceof Container || blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, null).isPresent())) {
            List<BlockPos> positions = new ArrayList<>();
            positions.add(pos);
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                positions.add(pos.relative(ChestBlock.getConnectedDirection(state)));
            }
            LookupParams params = base(level, positions);
            params.actions.add(Action.CONTAINER);
            run(player, params, pos);
            return;
        }
        List<BlockPos> positions = new ArrayList<>();
        positions.add(pos);
        if (face != null) {
            positions.add(pos.relative(face));
        }
        LookupParams params = base(level, positions);
        params.actions.add(Action.BLOCK);
        params.actions.add(Action.CLICK);
        run(player, params, face != null && level.getBlockState(pos.relative(face)).isAir() ? pos.relative(face) : pos);
    }

    private static LookupParams base(ServerLevel level, List<BlockPos> positions) {
        LookupParams params = new LookupParams();
        params.world = WorldUtil.worldName(level);
        params.global = true;
        int[][] locations = new int[positions.size()][];
        for (int i = 0; i < positions.size(); i++) {
            BlockPos pos = positions.get(i);
            locations[i] = new int[] { 0, pos.getX(), pos.getY(), pos.getZ() };
        }
        params.exactLocations = locations;
        return params;
    }

    private static void run(ServerPlayer player, LookupParams params, BlockPos pos) {
        if (!Permissions.has(player.createCommandSourceStack(), Permissions.INSPECT)) {
            forget(player);
            return;
        }
        String title = "(x" + pos.getX() + "/y" + pos.getY() + "/z" + pos.getZ() + ")";
        LookupRunner.run(player.createCommandSourceStack(), params, 1, Config.INSPECTOR_ROWS_PER_PAGE.get(), title, true);
    }
}
