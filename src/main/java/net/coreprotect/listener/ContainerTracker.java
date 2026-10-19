package net.coreprotect.listener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.coreprotect.config.Config;
import net.coreprotect.consumer.Entry;
import net.coreprotect.consumer.Log;
import net.coreprotect.utility.WorldUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.SlotItemHandler;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.minecraftforge.items.wrapper.PlayerInvWrapper;

/**
 * Logs items moved between players and block-entity inventories.
 * <p>
 * While a player has a block inventory open, the inventory and the player's own items are compared every
 * tick. A change in the block inventory is attributed to the viewer whose own items changed by the opposite
 * amount, so items moved by hoppers, furnaces smelting, or other automation are never blamed on a player.
 */
public final class ContainerTracker {

    record ItemKey(Item item, CompoundTag tag) {
        static ItemKey of(ItemStack stack) {
            CompoundTag tag = stack.getTag();
            return new ItemKey(stack.getItem(), tag == null || tag.isEmpty() ? null : tag.copy());
        }
    }

    private record LocKey(ServerLevel level, long pos) {
    }

    private record Ref(LocKey location, BlockEntity blockEntity, int index, Slot slot) {
    }

    private static final class OpenMenu {
        final AbstractContainerMenu menu;
        final List<Ref> refs;
        Map<ItemKey, Integer> playerItems;
        final Map<ItemKey, Integer> tossed = new HashMap<>();

        OpenMenu(AbstractContainerMenu menu, List<Ref> refs, Map<ItemKey, Integer> playerItems) {
            this.menu = menu;
            this.refs = refs;
            this.playerItems = playerItems;
        }
    }

    private record Change(LocKey location, int slot, ItemStack stack, int amount, boolean added) {
    }

    private record LastClick(ServerLevel level, BlockPos pos, long tick) {
    }

    private final Map<ServerPlayer, OpenMenu> open = new IdentityHashMap<>();
    private final Map<LocKey, Map<Integer, ItemStack>> snapshots = new HashMap<>();
    private final Map<ServerPlayer, LastClick> lastClicks = new IdentityHashMap<>();

    /** Remembers which block a player right-clicked, so modded menus can be mapped to that block. */
    public void onBlockClicked(ServerPlayer player, BlockPos pos) {
        lastClicks.put(player, new LastClick(player.serverLevel(), pos.immutable(), player.serverLevel().getGameTime()));
    }

    public void onOpen(ServerPlayer player, AbstractContainerMenu menu) {
        if (!Config.ITEM_TRANSACTIONS.get() || menu == player.inventoryMenu) {
            return;
        }
        if (open.containsKey(player)) {
            finish(player);
        }
        List<Ref> refs = resolve(player, menu);
        if (refs.isEmpty()) {
            return;
        }
        open.put(player, new OpenMenu(menu, refs, playerItems(player)));
        for (Ref ref : refs) {
            snapshots.computeIfAbsent(ref.location(), k -> new HashMap<>()).putIfAbsent(ref.index(), ref.slot().getItem().copy());
        }
    }

    public void onClose(ServerPlayer player, AbstractContainerMenu menu) {
        OpenMenu openMenu = open.get(player);
        if (openMenu != null && openMenu.menu == menu) {
            finish(player);
        }
    }

    public void onLogout(ServerPlayer player) {
        if (open.containsKey(player)) {
            finish(player);
        }
        lastClicks.remove(player);
    }

    /** Called when a player drops an item; it may have come straight out of an open container. */
    public void onToss(ServerPlayer player, ItemStack stack) {
        OpenMenu openMenu = open.get(player);
        if (openMenu != null && !stack.isEmpty()) {
            openMenu.tossed.merge(ItemKey.of(stack), stack.getCount(), Integer::sum);
        }
    }

    public void tick() {
        if (open.isEmpty()) {
            return;
        }
        List<ServerPlayer> closed = new ArrayList<>();
        for (Map.Entry<ServerPlayer, OpenMenu> entry : open.entrySet()) {
            if (entry.getKey().containerMenu != entry.getValue().menu || entry.getKey().isRemoved()) {
                closed.add(entry.getKey());
            }
        }
        for (ServerPlayer player : closed) {
            finish(player);
        }
        if (!open.isEmpty()) {
            process(new ArrayList<>(open.keySet()));
        }
    }

    /** Flushes pending changes for every open menu (used when the server stops). */
    public void finishAll() {
        for (ServerPlayer player : new ArrayList<>(open.keySet())) {
            finish(player);
        }
        lastClicks.clear();
    }

    private void finish(ServerPlayer player) {
        OpenMenu openMenu = open.get(player);
        if (openMenu == null) {
            return;
        }
        try {
            process(List.of(player));
        }
        finally {
            open.remove(player);
            for (Ref ref : openMenu.refs) {
                if (!isViewed(ref.location())) {
                    snapshots.remove(ref.location());
                }
            }
        }
    }

    private boolean isViewed(LocKey location) {
        for (OpenMenu openMenu : open.values()) {
            for (Ref ref : openMenu.refs) {
                if (ref.location().equals(location)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Diffs the inventories viewed by the given players (and anyone else viewing the same inventories).
     */
    private void process(List<ServerPlayer> players) {
        // Every location viewed by these players, and every player viewing one of those locations.
        Map<LocKey, Map<Integer, Ref>> locations = new LinkedHashMap<>();
        for (ServerPlayer player : players) {
            OpenMenu openMenu = open.get(player);
            for (Ref ref : openMenu.refs) {
                locations.computeIfAbsent(ref.location(), k -> new LinkedHashMap<>()).putIfAbsent(ref.index(), ref);
            }
        }
        Map<ServerPlayer, OpenMenu> viewers = new LinkedHashMap<>();
        for (Map.Entry<ServerPlayer, OpenMenu> entry : open.entrySet()) {
            for (Ref ref : entry.getValue().refs) {
                if (locations.containsKey(ref.location())) {
                    viewers.put(entry.getKey(), entry.getValue());
                    break;
                }
            }
        }

        List<Change> changes = new ArrayList<>();
        for (Map.Entry<LocKey, Map<Integer, Ref>> entry : locations.entrySet()) {
            Map<Integer, ItemStack> snapshot = snapshots.computeIfAbsent(entry.getKey(), k -> new HashMap<>());
            for (Ref ref : entry.getValue().values()) {
                if (ref.blockEntity().isRemoved()) {
                    continue;
                }
                ItemStack now = ref.slot().getItem();
                ItemStack before = snapshot.getOrDefault(ref.index(), ItemStack.EMPTY);
                if (ItemStack.matches(before, now)) {
                    continue;
                }
                if (ItemStack.isSameItemSameTags(before, now)) {
                    int diff = now.getCount() - before.getCount();
                    changes.add(new Change(ref.location(), ref.index(), now.copy(), Math.abs(diff), diff > 0));
                }
                else {
                    if (!before.isEmpty()) {
                        changes.add(new Change(ref.location(), ref.index(), before, before.getCount(), false));
                    }
                    if (!now.isEmpty()) {
                        changes.add(new Change(ref.location(), ref.index(), now.copy(), now.getCount(), true));
                    }
                }
                snapshot.put(ref.index(), now.copy());
            }
        }

        // How much of each item every viewer gained (positive) or lost (negative) since last time.
        Map<ServerPlayer, Map<ItemKey, Integer>> deltas = new LinkedHashMap<>();
        for (Map.Entry<ServerPlayer, OpenMenu> entry : viewers.entrySet()) {
            Map<ItemKey, Integer> now = playerItems(entry.getKey());
            Map<ItemKey, Integer> delta = new HashMap<>();
            for (Map.Entry<ItemKey, Integer> item : now.entrySet()) {
                delta.merge(item.getKey(), item.getValue(), Integer::sum);
            }
            for (Map.Entry<ItemKey, Integer> item : entry.getValue().playerItems.entrySet()) {
                delta.merge(item.getKey(), -item.getValue(), Integer::sum);
            }
            for (Map.Entry<ItemKey, Integer> item : entry.getValue().tossed.entrySet()) {
                delta.merge(item.getKey(), item.getValue(), Integer::sum);
            }
            entry.getValue().playerItems = now;
            entry.getValue().tossed.clear();
            deltas.put(entry.getKey(), delta);
        }

        for (Change change : changes) {
            ItemKey key = ItemKey.of(change.stack());
            int remaining = change.amount();
            for (Map.Entry<ServerPlayer, Map<ItemKey, Integer>> entry : deltas.entrySet()) {
                if (remaining <= 0) {
                    break;
                }
                Integer playerDelta = entry.getValue().get(key);
                if (playerDelta == null || playerDelta == 0) {
                    continue;
                }
                // Items added to the container must have come from the player, and vice versa.
                int available = change.added() ? -playerDelta : playerDelta;
                if (available <= 0) {
                    continue;
                }
                int amount = Math.min(available, remaining);
                remaining -= amount;
                entry.getValue().put(key, playerDelta + (change.added() ? amount : -amount));
                Entry.Actor actor = WorldUtil.actor(entry.getKey());
                BlockPos pos = BlockPos.of(change.location().pos());
                Log.container(actor, change.location().level(), pos, change.stack(), change.slot(), amount,
                        change.added() ? Log.CONTAINER_ADD : Log.CONTAINER_REMOVE);
            }
        }
    }

    private static Map<ItemKey, Integer> playerItems(ServerPlayer player) {
        Map<ItemKey, Integer> items = new HashMap<>();
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                items.merge(ItemKey.of(stack), stack.getCount(), Integer::sum);
            }
        }
        ItemStack carried = player.containerMenu.getCarried();
        if (!carried.isEmpty()) {
            items.merge(ItemKey.of(carried), carried.getCount(), Integer::sum);
        }
        return items;
    }

    private List<Ref> resolve(ServerPlayer player, AbstractContainerMenu menu) {
        List<Ref> refs = new ArrayList<>();
        for (Slot slot : menu.slots) {
            Container container = slot.container;
            if (container instanceof Inventory) {
                continue;
            }
            if (container instanceof BlockEntity blockEntity) {
                add(refs, blockEntity, slot.getContainerSlot(), slot);
            }
            else if (container instanceof CompoundContainer compound) {
                int index = slot.getContainerSlot();
                Container first = compound.container1;
                if (index < first.getContainerSize()) {
                    if (first instanceof BlockEntity blockEntity) {
                        add(refs, blockEntity, index, slot);
                    }
                }
                else if (compound.container2 instanceof BlockEntity blockEntity) {
                    add(refs, blockEntity, index - first.getContainerSize(), slot);
                }
            }
            else if (slot instanceof SlotItemHandler handlerSlot && !isPlayerHandler(handlerSlot)) {
                BlockEntity blockEntity = clickedBlockEntity(player);
                if (blockEntity != null) {
                    add(refs, blockEntity, handlerSlot.getSlotIndex(), slot);
                }
            }
        }
        return refs;
    }

    private static boolean isPlayerHandler(SlotItemHandler slot) {
        Object handler = slot.getItemHandler();
        if (handler instanceof PlayerInvWrapper || handler instanceof net.minecraftforge.items.wrapper.PlayerMainInvWrapper
                || handler instanceof net.minecraftforge.items.wrapper.PlayerArmorInvWrapper
                || handler instanceof net.minecraftforge.items.wrapper.PlayerOffhandInvWrapper) {
            return true;
        }
        if (handler instanceof InvWrapper wrapper) {
            return wrapper.getInv() instanceof Inventory;
        }
        return false;
    }

    private BlockEntity clickedBlockEntity(ServerPlayer player) {
        LastClick click = lastClicks.get(player);
        if (click == null || click.level() != player.serverLevel() || player.serverLevel().getGameTime() - click.tick() > 5) {
            return null;
        }
        if (!click.level().isLoaded(click.pos())) {
            return null;
        }
        return click.level().getBlockEntity(click.pos());
    }

    private static void add(List<Ref> refs, BlockEntity blockEntity, int index, Slot slot) {
        if (!(blockEntity.getLevel() instanceof ServerLevel level) || blockEntity.isRemoved()) {
            return;
        }
        LocKey location = new LocKey(level, blockEntity.getBlockPos().asLong());
        for (Ref ref : refs) {
            if (ref.location().equals(location) && ref.index() == index) {
                return;
            }
        }
        refs.add(new Ref(location, blockEntity, index, Objects.requireNonNull(slot)));
    }
}
