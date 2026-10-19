package net.coreprotect.listener;

import java.util.List;

import net.coreprotect.CoreProtect;
import net.coreprotect.command.Inspector;
import net.coreprotect.config.Config;
import net.coreprotect.consumer.Entry;
import net.coreprotect.consumer.Log;
import net.coreprotect.utility.WorldUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDestroyBlockEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Forge event handlers. Inspector handlers run first (and cancel the action); logging handlers run last
 * and ignore cancelled events, so actions blocked by protection mods are not logged.
 */
public final class EventListener {

    private static boolean active() {
        return CoreProtect.isEnabled();
    }

    // ------------------------------------------------------------------------------------------------
    // Inspector
    // ------------------------------------------------------------------------------------------------

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onInspectLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !Inspector.isInspecting(player)) {
            return;
        }
        event.setCanceled(true);
        if (event.getAction() == PlayerInteractEvent.LeftClickBlock.Action.START && event.getHand() == InteractionHand.MAIN_HAND) {
            Inspector.inspectBlock(player, player.serverLevel(), event.getPos());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onInspectRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !Inspector.isInspecting(player)) {
            return;
        }
        event.setCanceled(true);
        if (event.getHand() == InteractionHand.MAIN_HAND) {
            Inspector.inspectInteraction(player, player.serverLevel(), event.getPos(), event.getFace());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onInspectBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player && Inspector.isInspecting(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onInspectPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && Inspector.isInspecting(player)) {
            event.setCanceled(true);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Blocks
    // ------------------------------------------------------------------------------------------------

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!active() || !Config.BLOCK_BREAK.get() || !(event.getLevel() instanceof ServerLevel level) || event.getPlayer() == null) {
            return;
        }
        Entry.Actor actor = WorldUtil.actor(event.getPlayer());
        BlockTracker tracker = CoreProtect.blockTracker();
        tracker.track(level, event.getPos(), actor, BlockTracker.Kind.DIRECT);
        if (Config.NATURAL_BREAK.get()) {
            tracker.trackNeighbors(level, event.getPos(), actor);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!active() || !Config.BLOCK_PLACE.get() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Entity entity = event.getEntity();
        if (entity == null || entity instanceof SnowGolem || event.getPlacedBlock().is(Blocks.FROSTED_ICE)) {
            return;
        }
        if (!(entity instanceof Player) && !Config.ENTITY_CHANGE.get()) {
            return;
        }
        Entry.Actor actor = WorldUtil.actor(entity);
        List<BlockSnapshot> snapshots = event instanceof BlockEvent.EntityMultiPlaceEvent multi
                ? multi.getReplacedBlockSnapshots()
                : List.of(event.getBlockSnapshot());
        for (BlockSnapshot snapshot : snapshots) {
            CompoundTag tag = snapshot.getTag();
            CoreProtect.blockTracker().track(level, snapshot.getPos(), snapshot.getReplacedBlock(), tag == null ? null : tag.copy(), actor, BlockTracker.Kind.DIRECT);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!active() || !(event.getEntity() instanceof ServerPlayer player) || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        CoreProtect.containerTracker().onBlockClicked(player, event.getPos());
        if (!Config.PLAYER_INTERACTIONS.get() && !Config.BUCKETS.get()) {
            return;
        }
        Entry.Actor actor = WorldUtil.actor(player);
        BlockTracker tracker = CoreProtect.blockTracker();
        tracker.track(level, event.getPos(), actor, BlockTracker.Kind.INTERACT);
        if (event.getFace() != null) {
            tracker.track(level, event.getPos().relative(event.getFace()), actor, BlockTracker.Kind.INTERACT);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!active() || !Config.BUCKETS.get() || !(event.getEntity() instanceof ServerPlayer player) || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        // Buckets, lily pads, boats, etc. act on the block the player is looking at, including fluids.
        Entry.Actor actor = WorldUtil.actor(player);
        double reach = player.getBlockReach();
        for (boolean fluids : new boolean[] { true, false }) {
            HitResult hit = player.pick(reach, 1.0F, fluids);
            if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
                CoreProtect.blockTracker().track(level, blockHit.getBlockPos(), actor, BlockTracker.Kind.INTERACT);
                CoreProtect.blockTracker().track(level, blockHit.getBlockPos().relative(blockHit.getDirection()), actor, BlockTracker.Kind.INTERACT);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onExplosion(ExplosionEvent.Detonate event) {
        if (!active() || !Config.EXPLOSIONS.get() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Entity source = event.getExplosion().getDirectSourceEntity();
        Entry.Actor actor = source == null ? Entry.Actor.of("#explosion") : Entry.Actor.of("#" + WorldUtil.entityName(source.getType()));
        BlockTracker tracker = CoreProtect.blockTracker();
        for (BlockPos pos : event.getAffectedBlocks()) {
            tracker.track(level, pos, actor, BlockTracker.Kind.DIRECT);
        }
        if (Config.NATURAL_BREAK.get()) {
            for (BlockPos pos : event.getAffectedBlocks()) {
                tracker.trackNeighbors(level, pos, actor);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onEntityDestroyBlock(LivingDestroyBlockEvent event) {
        if (!active() || !Config.ENTITY_CHANGE.get() || !(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }
        Entry.Actor actor = WorldUtil.actor(event.getEntity());
        CoreProtect.blockTracker().track(level, event.getPos(), actor, BlockTracker.Kind.DIRECT);
        if (Config.NATURAL_BREAK.get()) {
            CoreProtect.blockTracker().trackNeighbors(level, event.getPos(), actor);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onTrample(BlockEvent.FarmlandTrampleEvent event) {
        if (!active() || !Config.ENTITY_CHANGE.get() || !(event.getLevel() instanceof ServerLevel level) || event.getEntity() == null) {
            return;
        }
        Entry.Actor actor = WorldUtil.actor(event.getEntity());
        CoreProtect.blockTracker().track(level, event.getPos(), actor, BlockTracker.Kind.DIRECT);
        CoreProtect.blockTracker().track(level, event.getPos().above(), actor, BlockTracker.Kind.DIRECT);
    }

    // ------------------------------------------------------------------------------------------------
    // Containers and items
    // ------------------------------------------------------------------------------------------------

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onContainerOpen(PlayerContainerEvent.Open event) {
        if (active() && event.getEntity() instanceof ServerPlayer player) {
            CoreProtect.containerTracker().onOpen(player, event.getContainer());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onContainerClose(PlayerContainerEvent.Close event) {
        if (active() && event.getEntity() instanceof ServerPlayer player) {
            CoreProtect.containerTracker().onClose(player, event.getContainer());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onItemToss(ItemTossEvent event) {
        if (!active() || !(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack stack = event.getEntity().getItem();
        CoreProtect.containerTracker().onToss(player, stack);
        if (Config.ITEM_DROPS.get()) {
            Log.item(WorldUtil.actor(player), player.serverLevel(), event.getEntity().blockPosition(), stack, stack.getCount(), Log.ITEM_DROP);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onItemPickup(PlayerEvent.ItemPickupEvent event) {
        if (!active() || !Config.ITEM_PICKUPS.get() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack stack = event.getStack();
        ItemEntity item = event.getOriginalEntity();
        BlockPos pos = item != null ? item.blockPosition() : player.blockPosition();
        Log.item(WorldUtil.actor(player), player.serverLevel(), pos, stack, stack.getCount(), Log.ITEM_PICKUP);
    }

    // ------------------------------------------------------------------------------------------------
    // Entities
    // ------------------------------------------------------------------------------------------------

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (!active() || !Config.ENTITY_KILLS.get() || !(victim.level() instanceof ServerLevel level)) {
            return;
        }
        Entity killer = event.getSource().getEntity();
        if (killer == null && victim instanceof Enemy) {
            // Hostile mobs burning in daylight, drowning, etc. would only flood the database.
            return;
        }
        Entry.Actor actor = killer != null ? WorldUtil.actor(killer) : Entry.Actor.of("#" + event.getSource().getMsgId());

        CompoundTag data;
        if (victim instanceof Player player) {
            data = new CompoundTag();
            data.putString("Name", player.getGameProfile().getName());
        }
        else {
            data = EntityUtil.save(victim);
        }
        Log.entityKill(actor, level, victim.blockPosition(), WorldUtil.entityId(victim.getType()), data);
    }

    // ------------------------------------------------------------------------------------------------
    // Players
    // ------------------------------------------------------------------------------------------------

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onChat(ServerChatEvent event) {
        if (active() && Config.PLAYER_MESSAGES.get()) {
            ServerPlayer player = event.getPlayer();
            Log.chat(WorldUtil.actor(player), WorldUtil.location(player), event.getRawText());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onCommand(CommandEvent event) {
        if (!active() || !Config.PLAYER_COMMANDS.get() || event.getException() != null) {
            return;
        }
        if (event.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer player) {
            String command = event.getParseResults().getReader().getString();
            if (command.startsWith("/")) {
                command = command.substring(1);
            }
            String name = command.split(" ", 2)[0].toLowerCase(java.util.Locale.ROOT);
            int namespace = name.indexOf(':');
            if (namespace >= 0) {
                name = name.substring(namespace + 1);
            }
            if (Config.EXCLUDED_COMMANDS.get().contains(name)) {
                return;
            }
            Log.command(WorldUtil.actor(player), WorldUtil.location(player), "/" + command);
        }
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (active() && event.getEntity() instanceof ServerPlayer player) {
            Entry.Actor actor = WorldUtil.actor(player);
            Log.login(actor);
            if (Config.PLAYER_SESSIONS.get()) {
                Log.session(actor, WorldUtil.location(player), true);
            }
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Inspector.forget(player);
        if (active()) {
            CoreProtect.containerTracker().onLogout(player);
            if (Config.PLAYER_SESSIONS.get()) {
                Log.session(WorldUtil.actor(player), WorldUtil.location(player), false);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Ticking
    // ------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (!active()) {
            return;
        }
        if (event.phase == TickEvent.Phase.START) {
            CoreProtect.rollbackManager().tick();
        }
        else {
            CoreProtect.containerTracker().tick();
            CoreProtect.blockTracker().process();
        }
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (active() && event.getLevel() instanceof ServerLevel level) {
            CoreProtect.blockTracker().forget(level);
        }
    }
}
