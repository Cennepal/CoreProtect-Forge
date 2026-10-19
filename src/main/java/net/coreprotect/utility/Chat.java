package net.coreprotect.utility;

import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * Chat formatting helpers using CoreProtect's colour scheme (dark aqua highlights on white).
 */
public final class Chat {

    public static final ChatFormatting HIGHLIGHT = ChatFormatting.DARK_AQUA;
    public static final ChatFormatting TEXT = ChatFormatting.WHITE;
    public static final ChatFormatting MUTED = ChatFormatting.GRAY;

    private Chat() {
    }

    public static MutableComponent text(String text) {
        return Component.literal(text).withStyle(TEXT);
    }

    public static MutableComponent highlight(String text) {
        return Component.literal(text).withStyle(HIGHLIGHT);
    }

    public static MutableComponent muted(String text) {
        return Component.literal(text).withStyle(MUTED);
    }

    /** "CoreProtect - message" */
    public static MutableComponent message(String text) {
        return highlight("CoreProtect ").append(text("- " + text));
    }

    public static MutableComponent message(Component component) {
        return highlight("CoreProtect ").append(text("- ")).append(component);
    }

    public static MutableComponent error(String text) {
        return highlight("CoreProtect ").append(text("- ")).append(Component.literal(text).withStyle(ChatFormatting.WHITE));
    }

    public static MutableComponent header(String title) {
        return text("----- ").append(highlight(title)).append(text(" -----"));
    }

    public static MutableComponent command(String label, String command, String hover) {
        return Component.literal(label).withStyle(Style.EMPTY.withColor(HIGHLIGHT)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
    }

    public static MutableComponent suggest(String label, String command, String hover) {
        return Component.literal(label).withStyle(Style.EMPTY.withColor(HIGHLIGHT)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
    }

    /** CoreProtect's compact "0.25/h ago" format. */
    public static String timeAgo(long time, long now) {
        double seconds = Math.max(0, now - time);
        String value;
        if (seconds < 3600) {
            value = String.format(Locale.ROOT, "%.2f/m", seconds / 60.0);
        }
        else if (seconds < 86400) {
            value = String.format(Locale.ROOT, "%.2f/h", seconds / 3600.0);
        }
        else {
            value = String.format(Locale.ROOT, "%.2f/d", seconds / 86400.0);
        }
        return value + " ago";
    }

    /** Human readable duration, e.g. "2 days, 5 hours". */
    public static String duration(long seconds) {
        if (seconds <= 0) {
            return "0 seconds";
        }
        long[] units = { 604800, 86400, 3600, 60, 1 };
        String[] names = { "week", "day", "hour", "minute", "second" };
        StringBuilder sb = new StringBuilder();
        int parts = 0;
        for (int i = 0; i < units.length && parts < 3; i++) {
            long amount = seconds / units[i];
            if (amount > 0) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(amount).append(' ').append(names[i]).append(amount == 1 ? "" : "s");
                seconds -= amount * units[i];
                parts++;
            }
        }
        return sb.toString();
    }

    public static String plural(long count, String singular, String plural) {
        return count + " " + (count == 1 ? singular : plural);
    }
}
