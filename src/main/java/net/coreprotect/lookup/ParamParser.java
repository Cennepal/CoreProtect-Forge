package net.coreprotect.lookup;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.coreprotect.utility.WorldUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Parses CoreProtect style parameters: u:, t:, r:, a:, i:, e: and # flags.
 */
public final class ParamParser {

    public static final class ParseException extends Exception {
        public ParseException(String message) {
            super(message);
        }
    }

    private static final Pattern TIME_PART = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)([wdhms])");

    private ParamParser() {
    }

    /**
     * @param args   parameter tokens (without the sub-command)
     * @param world  the executing player's world, or null for the console
     */
    public static LookupParams parse(MinecraftServer server, String[] args, String world, int x, int y, int z) throws ParseException {
        LookupParams params = new LookupParams();
        long now = WorldUtil.now();
        for (String raw : args) {
            if (raw.isEmpty()) {
                continue;
            }
            String arg = raw;
            String lower = arg.toLowerCase(Locale.ROOT);
            if (lower.startsWith("#")) {
                switch (lower) {
                    case "#preview" -> params.preview = true;
                    case "#count", "#sum" -> params.count = true;
                    case "#optimize" -> params.optimize = true;
                    case "#silent" -> params.silent = true;
                    case "#global" -> params.global = true;
                    default -> throw new ParseException("\"" + arg + "\" is not a supported parameter.");
                }
                continue;
            }
            int colon = arg.indexOf(':');
            if (colon <= 0) {
                throw new ParseException("\"" + arg + "\" is not a supported parameter.");
            }
            String key = lower.substring(0, colon);
            String value = arg.substring(colon + 1);
            if (value.isEmpty()) {
                throw new ParseException("\"" + arg + "\" is missing a value.");
            }
            switch (key) {
                case "u", "user", "users", "p", "player", "players" -> {
                    for (String user : value.split(",")) {
                        if (!user.isBlank()) {
                            params.users.add(user.trim());
                        }
                    }
                }
                case "t", "time" -> {
                    parseTime(params, value, now);
                }
                case "r", "radius" -> parseRadius(server, params, value, world, x, y, z);
                case "a", "action", "actions" -> {
                    for (String name : value.split(",")) {
                        Action action = Action.parse(name.trim());
                        if (action == null) {
                            throw new ParseException("\"" + name + "\" is not a valid action.");
                        }
                        params.actions.add(action);
                    }
                }
                case "i", "include", "b", "block", "blocks", "item", "items" -> {
                    for (String name : value.split(",")) {
                        if (name.isBlank()) {
                            continue;
                        }
                        String id = WorldUtil.normalizeId(name.trim());
                        if (!isKnownId(id)) {
                            throw new ParseException("\"" + name + "\" is an invalid block/entity name.");
                        }
                        params.include.add(id);
                    }
                }
                case "e", "exclude" -> {
                    for (String name : value.split(",")) {
                        if (name.isBlank()) {
                            continue;
                        }
                        String trimmed = name.trim();
                        String id = WorldUtil.normalizeId(trimmed);
                        if (!trimmed.startsWith("#") && isKnownId(id)) {
                            params.exclude.add(id);
                        }
                        else {
                            params.excludedUsers.add(trimmed);
                        }
                    }
                }
                default -> throw new ParseException("\"" + arg + "\" is not a supported parameter.");
            }
        }
        return params;
    }

    private static boolean isKnownId(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null) {
            return false;
        }
        return BuiltInRegistries.BLOCK.containsKey(location) || BuiltInRegistries.ITEM.containsKey(location)
                || BuiltInRegistries.ENTITY_TYPE.containsKey(location);
    }

    private static void parseTime(LookupParams params, String value, long now) throws ParseException {
        String text = value.toLowerCase(Locale.ROOT).replace(",", "");
        String[] range = text.split("-", -1);
        if (range.length > 2) {
            throw new ParseException("Invalid time \"" + value + "\". Examples: t:2w5d7h2m10s, t:2.50h, t:1h-2h");
        }
        long first = seconds(range[0], value);
        if (range.length == 2) {
            long second = seconds(range[1], value);
            long newer = Math.min(first, second);
            long older = Math.max(first, second);
            params.maxTime = now - newer;
            params.minTime = now - older;
        }
        else {
            params.minTime = now - first;
            params.maxTime = Long.MAX_VALUE;
        }
        params.timeSpecified = true;
        params.timeText = value;
    }

    private static long seconds(String text, String original) throws ParseException {
        Matcher matcher = TIME_PART.matcher(text);
        double total = 0;
        int end = 0;
        boolean found = false;
        while (matcher.find()) {
            if (matcher.start() != end) {
                break;
            }
            end = matcher.end();
            found = true;
            double amount = Double.parseDouble(matcher.group(1));
            total += switch (matcher.group(2)) {
                case "w" -> amount * 604800;
                case "d" -> amount * 86400;
                case "h" -> amount * 3600;
                case "m" -> amount * 60;
                default -> amount;
            };
        }
        if (!found || end != text.length() || total <= 0) {
            throw new ParseException("Invalid time \"" + original + "\". Examples: t:2w5d7h2m10s, t:2.50h, t:1h-2h");
        }
        return (long) Math.ceil(total);
    }

    private static void parseRadius(MinecraftServer server, LookupParams params, String value, String world, int x, int y, int z) throws ParseException {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("#")) {
            String name = lower.substring(1);
            if (name.equals("global")) {
                params.global = true;
                return;
            }
            String resolved = resolveWorld(server, name);
            if (resolved == null) {
                throw new ParseException("Please specify a valid world.");
            }
            params.world = resolved;
            params.global = true;
            return;
        }
        if (world == null) {
            throw new ParseException("Please specify a world radius (e.g. r:#overworld) when running this from the console.");
        }
        int radius;
        try {
            radius = Integer.parseInt(lower);
        }
        catch (NumberFormatException e) {
            throw new ParseException("Please enter a valid radius.");
        }
        if (radius < 0) {
            throw new ParseException("Please enter a valid radius.");
        }
        params.radius = radius;
        params.world = world;
        params.centerX = x;
        params.centerY = y;
        params.centerZ = z;
    }

    /** Resolves "overworld", "nether", "end", "the_nether", "minecraft:the_end" or a modded dimension id. */
    public static String resolveWorld(MinecraftServer server, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "world", "overworld" -> lower = "minecraft:overworld";
            case "nether", "world_nether", "the_nether" -> lower = "minecraft:the_nether";
            case "end", "world_the_end", "the_end" -> lower = "minecraft:the_end";
            default -> {
            }
        }
        if (!lower.contains(":")) {
            lower = "minecraft:" + lower;
        }
        ServerLevel level = WorldUtil.level(server, lower);
        return level == null ? null : lower;
    }
}
