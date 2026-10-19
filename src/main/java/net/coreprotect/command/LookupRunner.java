package net.coreprotect.command;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.coreprotect.CoreProtect;
import net.coreprotect.consumer.Consumer;
import net.coreprotect.lookup.Action;
import net.coreprotect.lookup.Formatter;
import net.coreprotect.lookup.LookupParams;
import net.coreprotect.lookup.LookupService;
import net.coreprotect.lookup.Query;
import net.coreprotect.utility.Chat;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * Runs lookups asynchronously and prints the results. Remembers the last lookup per sender so
 * "/co lookup &lt;page&gt;" can page through it.
 */
public final class LookupRunner {

    /** A lookup that can be re-run for another page. */
    public record Last(LookupParams params, String title, boolean inspector) {
    }

    private static final Map<String, Last> LAST = new ConcurrentHashMap<>();

    private LookupRunner() {
    }

    public static String senderKey(CommandSourceStack source) {
        return source.getEntity() != null ? source.getEntity().getStringUUID() : "#console";
    }

    public static Last last(CommandSourceStack source) {
        return LAST.get(senderKey(source));
    }

    public static void clear() {
        LAST.clear();
    }

    public static void forget(String key) {
        LAST.remove(key);
    }

    /**
     * @param title     header text; for the inspector this is the coordinates
     * @param inspector inspector style output (no location line per row)
     */
    public static void run(CommandSourceStack source, LookupParams params, int page, int perPage, String title, boolean inspector) {
        LookupService service = CoreProtect.lookupService();
        if (service == null) {
            source.sendSystemMessage(Chat.error("CoreProtect is not enabled."));
            return;
        }
        LAST.put(senderKey(source), new Last(params, title, inspector));
        if (!inspector) {
            source.sendSystemMessage(Chat.message("Lookup searching. Please wait..."));
        }
        MinecraftServer server = source.getServer();
        Consumer consumer = CoreProtect.consumer();
        CoreProtect.lookupExecutor().execute(() -> {
            try {
                consumer.flushAndWait(5_000);
                LookupService.Page result = service.lookup(params, page, perPage, params.count);
                server.execute(() -> print(source, params, result, title, inspector));
            }
            catch (Query.InvalidException e) {
                server.execute(() -> source.sendSystemMessage(Chat.error(e.getMessage())));
            }
            catch (Exception e) {
                CoreProtect.LOGGER.error("Lookup failed", e);
                server.execute(() -> source.sendSystemMessage(Chat.error("Lookup failed: " + e.getMessage())));
            }
        });
    }

    private static void print(CommandSourceStack source, LookupParams params, LookupService.Page result, String title, boolean inspector) {
        if (params.count) {
            source.sendSystemMessage(Chat.message(Chat.plural(result.total(), "row", "rows") + " found."));
            return;
        }
        if (result.total() == 0) {
            source.sendSystemMessage(Chat.message(inspector ? "No data found at " + title + "." : "No results found."));
            return;
        }
        if (result.rows().isEmpty()) {
            source.sendSystemMessage(Chat.message("No results found for that page."));
            return;
        }
        if (inspector) {
            source.sendSystemMessage(Chat.text("----- ").append(Chat.highlight("CoreProtect")).append(Chat.text(" ----- ")).append(Chat.muted(title)));
        }
        else {
            source.sendSystemMessage(Chat.header(title));
        }
        List<Component> lines = Formatter.rows(result.rows(), !inspector);
        for (Component line : lines) {
            source.sendSystemMessage(line);
        }
        if (result.pages() > 1) {
            source.sendSystemMessage(Formatter.footer(result.page(), result.pages(), "/co lookup"));
        }
        else if (!inspector) {
            source.sendSystemMessage(Chat.text("-----"));
        }
    }

    /** Checks the lookup.* permission for every requested action. */
    public static boolean checkPermissions(CommandSourceStack source, LookupParams params) {
        for (Action action : params.actions) {
            if (!Permissions.has(source, Permissions.forTable(action))) {
                source.sendSystemMessage(Chat.error("You do not have permission to do that."));
                return false;
            }
        }
        return true;
    }
}
