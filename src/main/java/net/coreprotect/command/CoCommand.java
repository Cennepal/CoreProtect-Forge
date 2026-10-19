package net.coreprotect.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.coreprotect.CoreProtect;
import net.coreprotect.config.Config;
import net.coreprotect.lookup.Action;
import net.coreprotect.lookup.LookupParams;
import net.coreprotect.lookup.ParamParser;
import net.coreprotect.rollback.RollbackManager;
import net.coreprotect.utility.Chat;
import net.coreprotect.utility.WorldUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * The /co, /core and /coreprotect commands.
 */
public final class CoCommand {

    private static final Pattern PAGE = Pattern.compile("([0-9]+)(?::([0-9]+))?");

    private CoCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String name : new String[] { "co", "core", "coreprotect" }) {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(name)
                    .requires(source -> Permissions.has(source, Permissions.CO))
                    .executes(ctx -> {
                        help(ctx.getSource(), new String[0]);
                        return 1;
                    })
                    .then(Commands.argument("arguments", StringArgumentType.greedyString())
                            .suggests(CoCommand::suggest)
                            .executes(CoCommand::execute));
            dispatcher.register(root);
        }
    }

    private static int execute(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String[] args = StringArgumentType.getString(ctx, "arguments").trim().split("\\s+");
        if (!CoreProtect.isEnabled()) {
            source.sendSystemMessage(Chat.error("CoreProtect is disabled. Check the server log for database errors."));
            return 0;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        try {
            switch (sub) {
                case "help" -> help(source, rest);
                case "inspect", "i", "inspector" -> inspect(source, rest);
                case "lookup", "l", "page" -> lookup(source, rest, false);
                case "near" -> lookup(source, rest, true);
                case "rollback", "rb", "ro" -> rollback(source, rest, false);
                case "restore", "rs", "re" -> rollback(source, rest, true);
                case "undo" -> undo(source);
                case "apply" -> apply(source);
                case "cancel" -> cancel(source);
                case "purge" -> PurgeCommand.run(source, rest);
                case "reload" -> reload(source);
                case "status", "stats", "version" -> status(source);
                case "teleport", "tp" -> teleport(source, rest);
                default -> source.sendSystemMessage(Chat.error("Command \"" + args[0] + "\" not found. Type \"/co help\" for help."));
            }
        }
        catch (ParamParser.ParseException e) {
            source.sendSystemMessage(Chat.error(e.getMessage()));
            return 0;
        }
        return 1;
    }

    private static boolean require(CommandSourceStack source, net.minecraftforge.server.permission.nodes.PermissionNode<Boolean> node) {
        if (Permissions.has(source, node)) {
            return true;
        }
        source.sendSystemMessage(Chat.error("You do not have permission to do that."));
        return false;
    }

    // ------------------------------------------------------------------------------------------------

    private static void help(CommandSourceStack source, String[] args) {
        if (!require(source, Permissions.HELP)) {
            return;
        }
        source.sendSystemMessage(Chat.header("CoreProtect Help"));
        helpLine(source, "/co inspect", "Turns the block inspector on or off.");
        helpLine(source, "/co rollback <params>", "Rollback block data.");
        helpLine(source, "/co restore <params>", "Restore block data.");
        helpLine(source, "/co lookup <params>", "Advanced block data lookup.");
        helpLine(source, "/co purge t:<time>", "Delete old block data.");
        helpLine(source, "/co near [r:<radius>]", "Performs a lookup with a radius of 5.");
        helpLine(source, "/co undo", "Revert your last rollback/restore.");
        helpLine(source, "/co apply | cancel", "Apply or cancel a #preview.");
        helpLine(source, "/co status", "Displays the plugin status.");
        source.sendSystemMessage(Chat.text("Parameters:"));
        helpLine(source, "u:<users>", "Specify the user(s) to lookup, e.g. u:Notch,#tnt.");
        helpLine(source, "t:<time>", "Amount of time, e.g. t:2w,5d,7h,2m,10s, t:5d2h, t:2.50h, t:1h-2h.");
        helpLine(source, "r:<radius>", "Radius around you, or r:#global, r:#overworld, r:#the_nether.");
        helpLine(source, "a:<action>", "Restrict to an action: " + String.join(", ", Action.SUGGESTIONS) + ".");
        helpLine(source, "i:<include>", "Include specific blocks/items/entities, e.g. i:stone,diamond_ore.");
        helpLine(source, "e:<exclude>", "Exclude blocks/items/entities or users, e.g. e:tnt or e:Notch.");
        helpLine(source, "#preview", "Preview a rollback/restore. #count shows the number of rows.");
    }

    private static void helpLine(CommandSourceStack source, String command, String description) {
        source.sendSystemMessage(Chat.suggest(command, command.split(" ")[0] + (command.startsWith("/") ? " " : ""), "Click to use")
                .append(Chat.text(" - " + description)));
    }

    private static void inspect(CommandSourceStack source, String[] args) {
        if (!require(source, Permissions.INSPECT)) {
            return;
        }
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendSystemMessage(Chat.error("Only players can use the inspector."));
            return;
        }
        Boolean target = null;
        if (args.length > 0) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "on", "enable", "true" -> target = true;
                case "off", "disable", "false" -> target = false;
                default -> {
                }
            }
        }
        if (target != null && target == Inspector.isInspecting(player)) {
            source.sendSystemMessage(Chat.message("Inspector already " + (target ? "enabled" : "disabled") + "."));
            return;
        }
        boolean enabled = Inspector.toggle(player, target);
        source.sendSystemMessage(Chat.message("Inspector now " + (enabled ? "enabled" : "disabled") + "."));
    }

    private static LookupParams parse(CommandSourceStack source, String[] args) throws ParamParser.ParseException {
        String world = null;
        BlockPos pos = BlockPos.ZERO;
        if (source.getEntity() != null) {
            world = WorldUtil.worldName(source.getLevel());
            pos = BlockPos.containing(source.getPosition());
        }
        return ParamParser.parse(source.getServer(), args, world, pos.getX(), pos.getY(), pos.getZ());
    }

    private static boolean checkRadius(CommandSourceStack source, LookupParams params) {
        int max = Config.MAX_RADIUS.get();
        if (params.radius != null && !params.global && max > 0 && params.radius > max) {
            source.sendSystemMessage(Chat.error("The maximum radius is " + max + "."));
            return false;
        }
        return true;
    }

    private static void lookup(CommandSourceStack source, String[] args, boolean near) throws ParamParser.ParseException {
        if (!require(source, near ? Permissions.LOOKUP_NEAR : Permissions.LOOKUP)) {
            return;
        }
        // Page navigation: "/co lookup 2" or "/co lookup 2:10" pages through the previous lookup.
        Integer page = null;
        Integer perPage = null;
        List<String> params = new ArrayList<>();
        for (String arg : args) {
            Matcher matcher = PAGE.matcher(arg);
            if (matcher.matches()) {
                page = Math.max(1, Integer.parseInt(matcher.group(1)));
                if (matcher.group(2) != null) {
                    perPage = Math.max(1, Math.min(1000, Integer.parseInt(matcher.group(2))));
                }
            }
            else if (!arg.isEmpty()) {
                params.add(arg);
            }
        }
        if (params.isEmpty() && !near) {
            LookupRunner.Last last = LookupRunner.last(source);
            if (last == null) {
                source.sendSystemMessage(Chat.error("Please use \"/co lookup <params>\". Type \"/co help\" for examples."));
                return;
            }
            int rows = perPage != null ? perPage : last.inspector() ? Config.INSPECTOR_ROWS_PER_PAGE.get() : Config.LOOKUP_ROWS_PER_PAGE.get();
            LookupRunner.run(source, last.params(), page != null ? page : 1, rows, last.title(), last.inspector());
            return;
        }

        LookupParams parsed = parse(source, params.toArray(new String[0]));
        if (near) {
            if (source.getEntity() == null) {
                source.sendSystemMessage(Chat.error("Only players can use that command."));
                return;
            }
            if (parsed.radius == null) {
                BlockPos pos = BlockPos.containing(source.getPosition());
                parsed.radius = 5;
                parsed.world = WorldUtil.worldName(source.getLevel());
                parsed.centerX = pos.getX();
                parsed.centerY = pos.getY();
                parsed.centerZ = pos.getZ();
            }
        }
        if (parsed.preview) {
            source.sendSystemMessage(Chat.error("#preview can only be used with rollbacks and restores."));
            return;
        }
        if (parsed.users.isEmpty() && parsed.radius == null && !parsed.timeSpecified && parsed.include.isEmpty() && parsed.actions.isEmpty()
                && parsed.world == null && !parsed.global) {
            source.sendSystemMessage(Chat.error("Please specify a user or radius to lookup."));
            return;
        }
        if (parsed.actions.contains(Action.USERNAME) && parsed.users.isEmpty()) {
            source.sendSystemMessage(Chat.error("To use that action, please specify a user."));
            return;
        }
        if (!checkRadius(source, parsed)) {
            return;
        }
        if (parsed.actions.isEmpty()) {
            parsed.actions.add(Action.BLOCK);
            parsed.actions.add(Action.CLICK);
            parsed.actions.add(Action.KILL);
        }
        if (!LookupRunner.checkPermissions(source, parsed)) {
            return;
        }
        LookupRunner.run(source, parsed, page != null ? page : 1, perPage != null ? perPage : Config.LOOKUP_ROWS_PER_PAGE.get(),
                "CoreProtect Lookup Results", false);
    }

    private static void rollback(CommandSourceStack source, String[] args, boolean restore) throws ParamParser.ParseException {
        if (!require(source, restore ? Permissions.RESTORE : Permissions.ROLLBACK)) {
            return;
        }
        String verb = restore ? "restore" : "rollback";
        LookupParams params = parse(source, args);
        if (!params.timeSpecified) {
            source.sendSystemMessage(Chat.error("Please specify the amount of time to " + verb + "."));
            return;
        }
        if (params.radius == null && !params.global && params.world == null && params.users.isEmpty()) {
            int defaultRadius = Config.DEFAULT_RADIUS.get();
            if (source.getEntity() != null && defaultRadius > 0) {
                BlockPos pos = BlockPos.containing(source.getPosition());
                params.radius = defaultRadius;
                params.world = WorldUtil.worldName(source.getLevel());
                params.centerX = pos.getX();
                params.centerY = pos.getY();
                params.centerZ = pos.getZ();
            }
            else {
                source.sendSystemMessage(Chat.error("You did not specify a " + verb + " radius."));
                return;
            }
        }
        if (params.users.isEmpty() && params.radius == null && params.global && params.world == null
                && params.include.isEmpty() && params.exclude.isEmpty()) {
            // r:#global without a user would affect the entire server; require something to narrow it down.
            source.sendSystemMessage(Chat.error("You did not specify a " + verb + " user."));
            return;
        }
        if (!checkRadius(source, params)) {
            return;
        }
        if (params.actions.isEmpty()) {
            params.actions.add(Action.BLOCK);
            if (Config.ROLLBACK_ENTITIES.get()) {
                params.actions.add(Action.KILL);
            }
            if (Config.ROLLBACK_ITEMS.get()) {
                params.actions.add(Action.CONTAINER);
            }
        }
        for (Action action : params.actions) {
            if (action.table != Action.Table.BLOCK && action.table != Action.Table.CONTAINER || action == Action.CLICK) {
                source.sendSystemMessage(Chat.error("That action is not supported by the command."));
                return;
            }
        }
        ServerPlayer previewPlayer = null;
        if (params.preview) {
            if (!(source.getEntity() instanceof ServerPlayer player)) {
                source.sendSystemMessage(Chat.error("Only players can preview a " + verb + "."));
                return;
            }
            previewPlayer = player;
        }
        RollbackManager manager = CoreProtect.rollbackManager();
        manager.start(params, restore, previewPlayer, source::sendSystemMessage, LookupRunner.senderKey(source));
    }

    private static void undo(CommandSourceStack source) {
        RollbackManager manager = CoreProtect.rollbackManager();
        RollbackManager.Completed last = manager.lastCompleted(LookupRunner.senderKey(source));
        if (last == null) {
            source.sendSystemMessage(Chat.error("There is no rollback or restore to undo."));
            return;
        }
        boolean restore = !last.restore();
        if (!require(source, restore ? Permissions.RESTORE : Permissions.ROLLBACK)) {
            return;
        }
        LookupParams params = last.params().copy();
        params.preview = false;
        manager.start(params, restore, null, source::sendSystemMessage, LookupRunner.senderKey(source));
    }

    private static void apply(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendSystemMessage(Chat.error("Only players can use that command."));
            return;
        }
        RollbackManager manager = CoreProtect.rollbackManager();
        RollbackManager.Preview preview = manager.preview(player.getUUID());
        if (preview == null) {
            source.sendSystemMessage(Chat.error("No pending preview found."));
            return;
        }
        if (!require(source, preview.restore() ? Permissions.RESTORE : Permissions.ROLLBACK)) {
            return;
        }
        LookupParams params = preview.params().copy();
        params.preview = false;
        manager.cancelPreview(player);
        manager.start(params, preview.restore(), null, source::sendSystemMessage, LookupRunner.senderKey(source));
    }

    private static void cancel(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendSystemMessage(Chat.error("Only players can use that command."));
            return;
        }
        RollbackManager manager = CoreProtect.rollbackManager();
        if (manager.preview(player.getUUID()) == null) {
            source.sendSystemMessage(Chat.error("No pending preview found."));
            return;
        }
        manager.cancelPreview(player);
        source.sendSystemMessage(Chat.message("Preview cancelled."));
    }

    private static void reload(CommandSourceStack source) {
        if (!require(source, Permissions.RELOAD)) {
            return;
        }
        source.sendSystemMessage(Chat.message("Logging and command settings are reloaded automatically when config/coreprotect-common.toml is saved."));
        source.sendSystemMessage(Chat.message("Database settings take effect after a server restart."));
    }

    private static void status(CommandSourceStack source) {
        if (!require(source, Permissions.STATUS)) {
            return;
        }
        source.sendSystemMessage(Chat.header("CoreProtect"));
        source.sendSystemMessage(Chat.text("Version: ").append(Chat.highlight(CoreProtect.version())));
        source.sendSystemMessage(Chat.text("Database: ").append(Chat.highlight(CoreProtect.database().describe())));
        source.sendSystemMessage(Chat.text("Consumer: ").append(Chat.highlight(Chat.plural(CoreProtect.consumer().queueSize(), "item", "items") + " in queue"))
                .append(Chat.text(", ")).append(Chat.highlight(CoreProtect.consumer().writtenRows() + " rows written since startup")));
        if (CoreProtect.rollbackManager().isBusy()) {
            source.sendSystemMessage(Chat.text("A rollback/restore is in progress."));
        }
    }

    private static void teleport(CommandSourceStack source, String[] args) {
        if (!require(source, Permissions.TELEPORT)) {
            return;
        }
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendSystemMessage(Chat.error("Only players can teleport."));
            return;
        }
        if (args.length < 4) {
            source.sendSystemMessage(Chat.error("Please use \"/co teleport <world> <x> <y> <z>\"."));
            return;
        }
        String world = ParamParser.resolveWorld(source.getServer(), args[0]);
        ServerLevel level = world == null ? null : WorldUtil.level(source.getServer(), world);
        if (level == null) {
            source.sendSystemMessage(Chat.error("Please specify a valid world."));
            return;
        }
        int x;
        int y;
        int z;
        try {
            x = Integer.parseInt(args[1]);
            y = Integer.parseInt(args[2]);
            z = Integer.parseInt(args[3]);
        }
        catch (NumberFormatException e) {
            source.sendSystemMessage(Chat.error("Please enter valid coordinates."));
            return;
        }
        BlockPos pos = new BlockPos(x, Math.max(level.getMinBuildHeight(), Math.min(level.getMaxBuildHeight() - 2, y)), z);
        level.getChunk(pos);
        for (int i = 0; i < 64 && pos.getY() < level.getMaxBuildHeight() - 2; i++) {
            if (!level.getBlockState(pos).isSuffocating(level, pos) && !level.getBlockState(pos.above()).isSuffocating(level, pos.above())) {
                break;
            }
            pos = pos.above();
        }
        Vec3 target = Vec3.atBottomCenterOf(pos);
        player.teleportTo(level, target.x, target.y, target.z, player.getYRot(), player.getXRot());
        source.sendSystemMessage(Chat.message("Teleported to x" + x + "/y" + pos.getY() + "/z" + z + "."));
    }

    // ------------------------------------------------------------------------------------------------

    private static final List<String> SUBCOMMANDS = List.of("help", "inspect", "lookup", "near", "rollback", "restore", "undo", "apply", "cancel",
            "purge", "reload", "status", "teleport");
    private static final List<String> PREFIXES = List.of("u:", "t:", "r:", "a:", "i:", "e:", "#preview", "#count");

    private static CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        int lastSpace = remaining.lastIndexOf(' ');
        String token = remaining.substring(lastSpace + 1);
        SuggestionsBuilder sub = builder.createOffset(builder.getStart() + lastSpace + 1);
        String lower = token.toLowerCase(Locale.ROOT);
        if (lastSpace < 0) {
            for (String command : SUBCOMMANDS) {
                if (command.startsWith(lower)) {
                    sub.suggest(command);
                }
            }
            return sub.buildFuture();
        }
        String first = remaining.substring(0, remaining.indexOf(' ')).toLowerCase(Locale.ROOT);
        if (!List.of("lookup", "l", "rollback", "rb", "ro", "restore", "rs", "re", "purge", "near").contains(first)) {
            return sub.buildFuture();
        }
        int colon = lower.indexOf(':');
        if (colon < 0) {
            for (String prefix : PREFIXES) {
                if (prefix.startsWith(lower)) {
                    sub.suggest(prefix);
                }
            }
            return sub.buildFuture();
        }
        String key = lower.substring(0, colon);
        String value = lower.substring(colon + 1);
        int comma = value.lastIndexOf(',');
        String head = token.substring(0, colon + 1 + comma + 1);
        String part = value.substring(comma + 1);
        List<String> options = new ArrayList<>();
        switch (key) {
            case "u", "user", "users" -> options.addAll(Arrays.asList(ctx.getSource().getServer().getPlayerNames()));
            case "a", "action" -> options.addAll(Action.SUGGESTIONS);
            case "t", "time" -> options.addAll(List.of("1h", "6h", "1d", "3d", "1w", "30d"));
            case "r", "radius" -> {
                options.addAll(List.of("5", "10", "20", "#global"));
                for (ServerLevel level : ctx.getSource().getServer().getAllLevels()) {
                    options.add("#" + WorldUtil.shortWorldName(WorldUtil.worldName(level)));
                }
            }
            case "i", "e", "include", "exclude", "b", "block" -> {
                int count = 0;
                for (ResourceLocation id : BuiltInRegistries.BLOCK.keySet()) {
                    String name = "minecraft".equals(id.getNamespace()) ? id.getPath() : id.toString();
                    if (name.startsWith(part) && count++ < 50) {
                        options.add(name);
                    }
                }
                for (ResourceLocation id : BuiltInRegistries.ENTITY_TYPE.keySet()) {
                    String name = "minecraft".equals(id.getNamespace()) ? id.getPath() : id.toString();
                    if (name.startsWith(part) && count++ < 75) {
                        options.add(name);
                    }
                }
            }
            default -> {
            }
        }
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(part)) {
                sub.suggest(head + option);
            }
        }
        return sub.buildFuture();
    }
}
