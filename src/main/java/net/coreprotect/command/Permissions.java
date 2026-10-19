package net.coreprotect.command;

import java.util.ArrayList;
import java.util.List;

import net.coreprotect.CoreProtect;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.permission.PermissionAPI;
import net.minecraftforge.server.permission.events.PermissionGatherEvent;
import net.minecraftforge.server.permission.nodes.PermissionNode;
import net.minecraftforge.server.permission.nodes.PermissionTypes;

/**
 * Permission nodes, registered with Forge's permission API so permission mods (e.g. LuckPerms) can manage
 * them. Without a permission mod, operators (permission level 2+) have every node except give/networking.
 */
public final class Permissions {

    private static final List<PermissionNode<?>> NODES = new ArrayList<>();

    public static final PermissionNode<Boolean> CO = everyone("co");
    public static final PermissionNode<Boolean> HELP = op("help");
    public static final PermissionNode<Boolean> INSPECT = op("inspect");
    public static final PermissionNode<Boolean> LOOKUP = op("lookup");
    public static final PermissionNode<Boolean> LOOKUP_NEAR = op("lookup.near");
    public static final PermissionNode<Boolean> LOOKUP_BLOCK = op("lookup.block");
    public static final PermissionNode<Boolean> LOOKUP_CLICK = op("lookup.click");
    public static final PermissionNode<Boolean> LOOKUP_KILL = op("lookup.kill");
    public static final PermissionNode<Boolean> LOOKUP_CONTAINER = op("lookup.container");
    public static final PermissionNode<Boolean> LOOKUP_ITEM = op("lookup.item");
    public static final PermissionNode<Boolean> LOOKUP_CHAT = op("lookup.chat");
    public static final PermissionNode<Boolean> LOOKUP_COMMAND = op("lookup.command");
    public static final PermissionNode<Boolean> LOOKUP_SESSION = op("lookup.session");
    public static final PermissionNode<Boolean> LOOKUP_SIGN = op("lookup.sign");
    public static final PermissionNode<Boolean> LOOKUP_USERNAME = op("lookup.username");
    public static final PermissionNode<Boolean> ROLLBACK = op("rollback");
    public static final PermissionNode<Boolean> RESTORE = op("restore");
    public static final PermissionNode<Boolean> PURGE = op("purge");
    public static final PermissionNode<Boolean> TELEPORT = op("teleport");
    public static final PermissionNode<Boolean> RELOAD = op("reload");
    public static final PermissionNode<Boolean> STATUS = op("status");

    private Permissions() {
    }

    private static PermissionNode<Boolean> everyone(String name) {
        PermissionNode<Boolean> node = new PermissionNode<>(CoreProtect.MOD_ID, name, PermissionTypes.BOOLEAN, (player, uuid, context) -> true);
        NODES.add(node);
        return node;
    }

    private static PermissionNode<Boolean> op(String name) {
        PermissionNode<Boolean> node = new PermissionNode<>(CoreProtect.MOD_ID, name, PermissionTypes.BOOLEAN,
                (player, uuid, context) -> player != null && player.hasPermissions(2));
        NODES.add(node);
        return node;
    }

    public static void register(PermissionGatherEvent.Nodes event) {
        event.addNodes(NODES);
    }

    public static boolean has(CommandSourceStack source, PermissionNode<Boolean> node) {
        if (source.getEntity() instanceof ServerPlayer player) {
            try {
                return PermissionAPI.getPermission(player, node);
            }
            catch (RuntimeException e) {
                // Permission handler not initialised (or node not registered): fall back to the default.
                return node.getDefaultResolver().resolve(player, player.getUUID());
            }
        }
        // Console, RCON, and command blocks/functions with operator level.
        return source.hasPermission(2);
    }

    public static PermissionNode<Boolean> forTable(net.coreprotect.lookup.Action action) {
        return switch (action) {
            case BLOCK, BLOCK_PLACE, BLOCK_REMOVE -> LOOKUP_BLOCK;
            case CLICK -> LOOKUP_CLICK;
            case KILL -> LOOKUP_KILL;
            case CONTAINER, CONTAINER_ADD, CONTAINER_REMOVE -> LOOKUP_CONTAINER;
            case ITEM, ITEM_DROP, ITEM_PICKUP -> LOOKUP_ITEM;
            case CHAT -> LOOKUP_CHAT;
            case COMMAND -> LOOKUP_COMMAND;
            case SESSION, LOGIN, LOGOUT -> LOOKUP_SESSION;
            case SIGN -> LOOKUP_SIGN;
            case USERNAME -> LOOKUP_USERNAME;
        };
    }
}
