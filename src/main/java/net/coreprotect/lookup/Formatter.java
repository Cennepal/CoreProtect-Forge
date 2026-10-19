package net.coreprotect.lookup;

import java.util.ArrayList;
import java.util.List;

import net.coreprotect.consumer.Log;
import net.coreprotect.utility.Chat;
import net.coreprotect.utility.NbtCodec;
import net.coreprotect.utility.WorldUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Turns lookup rows into chat lines.
 */
public final class Formatter {

    private Formatter() {
    }

    /**
     * @param showLocation true for normal lookups (adds a clickable coordinate line), false for the inspector
     */
    public static List<Component> rows(List<LookupRow> rows, boolean showLocation) {
        long now = WorldUtil.now();
        List<Component> lines = new ArrayList<>();
        for (LookupRow row : rows) {
            MutableComponent line = Chat.muted(Chat.timeAgo(row.time, now)).append(Chat.text(" - "));
            line.append(describe(row));
            lines.add(line);
            if (showLocation && row.table != Action.Table.USERNAME && row.world != null) {
                lines.add(location(row));
            }
        }
        return lines;
    }

    public static MutableComponent location(LookupRow row) {
        String world = WorldUtil.shortWorldName(row.world);
        String coords = "(x" + row.x + "/y" + row.y + "/z" + row.z + "/" + world + ")";
        return Chat.muted("                 ^ ").append(Chat.command(coords, "/co teleport " + row.world + " " + row.x + " " + row.y + " " + row.z, "Teleport to " + coords)
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }

    private static MutableComponent describe(LookupRow row) {
        String user = row.user == null ? "unknown" : row.user;
        MutableComponent out = Chat.highlight(user);
        MutableComponent action;
        switch (row.table) {
            case BLOCK -> {
                String verb = switch (row.action) {
                    case Log.BLOCK_BREAK -> "broke";
                    case Log.BLOCK_PLACE -> "placed";
                    case Log.BLOCK_CLICK -> "clicked";
                    default -> "killed";
                };
                String target = WorldUtil.displayId(row.type);
                if (row.action == Log.ENTITY_KILL && "minecraft:player".equals(row.type)) {
                    CompoundTag tag = NbtCodec.decode(row.meta);
                    if (tag != null && tag.contains("Name")) {
                        target = tag.getString("Name");
                    }
                }
                action = Chat.text(" " + verb + " ").append(Chat.highlight(target)).append(Chat.text("."));
            }
            case CONTAINER -> action = Chat.text(row.action == Log.CONTAINER_ADD ? " added " : " removed ")
                    .append(Chat.text("x" + row.amount + " ")).append(Chat.highlight(WorldUtil.displayId(row.type))).append(Chat.text("."));
            case ITEM -> action = Chat.text(row.action == Log.ITEM_PICKUP ? " picked up " : " dropped ")
                    .append(Chat.text("x" + row.amount + " ")).append(Chat.highlight(WorldUtil.displayId(row.type))).append(Chat.text("."));
            case CHAT, COMMAND -> action = Chat.text(": " + row.message);
            case SESSION -> action = Chat.text(row.action == 1 ? " logged in." : " logged out.");
            case SIGN -> action = Chat.text(row.action == 0 ? " - " : " - (back) ").append(Chat.text(row.message.replace('\n', ' ').trim()));
            case USERNAME -> action = Chat.text(" logged in as ").append(Chat.highlight(row.message)).append(Chat.text("."));
            default -> action = Chat.text("");
        }
        if (row.rolledBack) {
            action.withStyle(ChatFormatting.STRIKETHROUGH);
            out.withStyle(ChatFormatting.STRIKETHROUGH);
        }
        return out.append(action);
    }

    /** Footer with page navigation. */
    public static Component footer(int page, int pages, String command) {
        MutableComponent line = Chat.text("-----");
        if (pages <= 1) {
            return line;
        }
        MutableComponent nav = Chat.text("");
        if (page > 1) {
            nav.append(Chat.command("◀ ", command + " " + (page - 1), "Previous page"));
        }
        nav.append(Chat.text("Page " + page + "/" + pages + "."));
        if (page < pages) {
            nav.append(Chat.command(" ▶", command + " " + (page + 1), "Next page"));
        }
        nav.append(Chat.muted(" View older data by typing \"")).append(Chat.highlight(command + " <page>")).append(Chat.muted("\"."));
        return line.append("\n").append(nav);
    }
}
