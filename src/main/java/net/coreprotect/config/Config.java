package net.coreprotect.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * CoreProtect configuration. Stored in config/coreprotect-common.toml and reloaded automatically by Forge
 * whenever the file is edited while the server is running.
 */
public final class Config {

    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue USE_MYSQL;
    public static final ForgeConfigSpec.ConfigValue<String> TABLE_PREFIX;
    public static final ForgeConfigSpec.ConfigValue<String> MYSQL_HOST;
    public static final ForgeConfigSpec.IntValue MYSQL_PORT;
    public static final ForgeConfigSpec.ConfigValue<String> MYSQL_DATABASE;
    public static final ForgeConfigSpec.ConfigValue<String> MYSQL_USERNAME;
    public static final ForgeConfigSpec.ConfigValue<String> MYSQL_PASSWORD;
    public static final ForgeConfigSpec.BooleanValue MYSQL_USE_SSL;

    public static final ForgeConfigSpec.IntValue DEFAULT_RADIUS;
    public static final ForgeConfigSpec.IntValue MAX_RADIUS;
    public static final ForgeConfigSpec.IntValue LOOKUP_ROWS_PER_PAGE;
    public static final ForgeConfigSpec.IntValue INSPECTOR_ROWS_PER_PAGE;
    public static final ForgeConfigSpec.IntValue ROLLBACK_CHANGES_PER_TICK;
    public static final ForgeConfigSpec.BooleanValue ROLLBACK_ITEMS;
    public static final ForgeConfigSpec.BooleanValue ROLLBACK_ENTITIES;

    public static final ForgeConfigSpec.BooleanValue BLOCK_PLACE;
    public static final ForgeConfigSpec.BooleanValue BLOCK_BREAK;
    public static final ForgeConfigSpec.BooleanValue NATURAL_BREAK;
    public static final ForgeConfigSpec.BooleanValue EXPLOSIONS;
    public static final ForgeConfigSpec.BooleanValue ENTITY_CHANGE;
    public static final ForgeConfigSpec.BooleanValue BLOCK_BURN;
    public static final ForgeConfigSpec.BooleanValue LEAF_DECAY;
    public static final ForgeConfigSpec.BooleanValue BUCKETS;
    public static final ForgeConfigSpec.BooleanValue ITEM_TRANSACTIONS;
    public static final ForgeConfigSpec.BooleanValue PLAYER_INTERACTIONS;
    public static final ForgeConfigSpec.BooleanValue ITEM_DROPS;
    public static final ForgeConfigSpec.BooleanValue ITEM_PICKUPS;
    public static final ForgeConfigSpec.BooleanValue ENTITY_KILLS;
    public static final ForgeConfigSpec.BooleanValue PLAYER_MESSAGES;
    public static final ForgeConfigSpec.BooleanValue PLAYER_COMMANDS;
    public static final ForgeConfigSpec.BooleanValue PLAYER_SESSIONS;
    public static final ForgeConfigSpec.BooleanValue USERNAME_CHANGES;
    public static final ForgeConfigSpec.BooleanValue SIGN_TEXT;
    public static final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> EXCLUDED_COMMANDS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("Database settings. Changes to this section take effect after a server restart.").push("database");
        USE_MYSQL = b.comment("If false, a SQLite database is created at <world>/coreprotect/database.db.",
                "If true, the MySQL settings below are used.").define("use-mysql", false);
        TABLE_PREFIX = b.comment("Prefix for all CoreProtect table names (letters, digits and underscores only).")
                .define("table-prefix", "co_", o -> o instanceof String s && s.matches("[A-Za-z0-9_]{0,32}"));
        MYSQL_HOST = b.define("mysql-host", "127.0.0.1");
        MYSQL_PORT = b.defineInRange("mysql-port", 3306, 1, 65535);
        MYSQL_DATABASE = b.define("mysql-database", "database");
        MYSQL_USERNAME = b.define("mysql-username", "root");
        MYSQL_PASSWORD = b.define("mysql-password", "");
        MYSQL_USE_SSL = b.define("mysql-use-ssl", false);
        b.pop();

        b.comment("Command behaviour.").push("commands");
        DEFAULT_RADIUS = b.comment("Radius used for rollbacks and restores when no radius is given. 0 disables this.")
                .defineInRange("default-radius", 10, 0, 1_000_000);
        MAX_RADIUS = b.comment("Maximum radius allowed in commands. 0 disables the limit. Use r:#global to bypass it.")
                .defineInRange("max-radius", 100, 0, 1_000_000);
        LOOKUP_ROWS_PER_PAGE = b.defineInRange("lookup-rows-per-page", 4, 1, 100);
        INSPECTOR_ROWS_PER_PAGE = b.defineInRange("inspector-rows-per-page", 7, 1, 100);
        ROLLBACK_CHANGES_PER_TICK = b.comment("Maximum number of world changes applied per server tick during a rollback/restore.")
                .defineInRange("rollback-changes-per-tick", 2500, 1, 1_000_000);
        ROLLBACK_ITEMS = b.comment("If true, container transactions are included in rollbacks and restores.")
                .define("rollback-items", true);
        ROLLBACK_ENTITIES = b.comment("If true, killed entities are respawned by rollbacks (and removed again by restores).")
                .define("rollback-entities", true);
        b.pop();

        b.comment("What gets logged.").push("logging");
        BLOCK_PLACE = b.comment("Blocks placed by players and entities.").define("block-place", true);
        BLOCK_BREAK = b.comment("Blocks broken by players.").define("block-break", true);
        NATURAL_BREAK = b.comment("Blocks that break as a result of a player's action (torches, doors, plants, etc).")
                .define("natural-break", true);
        EXPLOSIONS = b.comment("Blocks destroyed by explosions (TNT, creepers, beds, ...).").define("explosions", true);
        ENTITY_CHANGE = b.comment("Blocks changed by entities (endermen, withers, dragons, ravagers, trampling).")
                .define("entity-change", true);
        BLOCK_BURN = b.comment("Blocks destroyed by fire.").define("block-burn", true);
        LEAF_DECAY = b.comment("Leaves that decay naturally.").define("leaf-decay", true);
        BUCKETS = b.comment("Liquids placed or picked up with buckets and other right-click block changes.")
                .define("buckets", true);
        ITEM_TRANSACTIONS = b.comment("Items taken from or put into containers (chests, furnaces, modded inventories, ...).")
                .define("item-transactions", true);
        PLAYER_INTERACTIONS = b.comment("Player clicks that change a block (doors, levers, buttons, repeaters, ...).")
                .define("player-interactions", true);
        ITEM_DROPS = b.define("item-drops", true);
        ITEM_PICKUPS = b.define("item-pickups", true);
        ENTITY_KILLS = b.define("entity-kills", true);
        PLAYER_MESSAGES = b.define("player-messages", true);
        PLAYER_COMMANDS = b.define("player-commands", true);
        PLAYER_SESSIONS = b.define("player-sessions", true);
        USERNAME_CHANGES = b.define("username-changes", true);
        SIGN_TEXT = b.define("sign-text", true);
        EXCLUDED_COMMANDS = b.comment("Commands that are never logged, e.g. authentication commands that contain passwords.")
                .defineListAllowEmpty(java.util.List.of("excluded-commands"),
                        () -> java.util.List.of("login", "l", "log", "register", "reg", "changepassword", "changepass", "changepw", "unregister"),
                        o -> o instanceof String);
        b.pop();

        SPEC = b.build();
    }

    private Config() {
    }
}
