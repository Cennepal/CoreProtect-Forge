package net.coreprotect.database;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import net.coreprotect.CoreProtect;
import net.coreprotect.config.Config;

/**
 * Owns the database configuration and hands out JDBC connections.
 * <p>
 * Drivers are instantiated directly instead of going through {@link java.sql.DriverManager}, because the
 * drivers are loaded as jar-in-jar libraries in their own module layer, where DriverManager's caller
 * sensitive lookup is not reliable.
 */
public final class Database {

    public static final int SCHEMA_VERSION = 1;

    private final boolean mysql;
    private final String prefix;
    private final String url;
    private final Properties properties = new Properties();
    private final java.sql.Driver driver;

    public Database(Path worldDirectory) throws Exception {
        this.mysql = Config.USE_MYSQL.get();
        this.prefix = Config.TABLE_PREFIX.get();
        if (mysql) {
            this.driver = new com.mysql.cj.jdbc.Driver();
            this.url = "jdbc:mysql://" + Config.MYSQL_HOST.get() + ":" + Config.MYSQL_PORT.get() + "/" + Config.MYSQL_DATABASE.get();
            properties.setProperty("user", Config.MYSQL_USERNAME.get());
            properties.setProperty("password", Config.MYSQL_PASSWORD.get());
            properties.setProperty("useSSL", String.valueOf(Config.MYSQL_USE_SSL.get()));
            properties.setProperty("allowPublicKeyRetrieval", "true");
            properties.setProperty("characterEncoding", "UTF-8");
            properties.setProperty("useUnicode", "true");
            properties.setProperty("rewriteBatchedStatements", "true");
            properties.setProperty("cachePrepStmts", "true");
            properties.setProperty("connectTimeout", "10000");
        }
        else {
            this.driver = new org.sqlite.JDBC();
            Path dir = worldDirectory.resolve("coreprotect");
            Files.createDirectories(dir);
            this.url = "jdbc:sqlite:" + dir.resolve("database.db").toAbsolutePath();
            properties.setProperty("journal_mode", "WAL");
            properties.setProperty("synchronous", "NORMAL");
            properties.setProperty("busy_timeout", "30000");
            properties.setProperty("foreign_keys", "false");
        }
    }

    public boolean isMySQL() {
        return mysql;
    }

    public String table(String name) {
        return prefix + name;
    }

    public String describe() {
        return mysql ? "MySQL" : "SQLite";
    }

    public Connection connect() throws SQLException {
        Connection connection = driver.connect(url, properties);
        if (connection == null) {
            throw new SQLException("Unable to connect to " + describe() + " database");
        }
        if (!mysql) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout = 30000");
            }
        }
        return connection;
    }

    /**
     * Creates any missing tables and indexes.
     */
    public void createTables() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            for (String sql : mysql ? mysqlSchema() : sqliteSchema()) {
                statement.execute(sql);
            }
            try (java.sql.ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table("version"))) {
                if (rs.next() && rs.getInt(1) == 0) {
                    statement.executeUpdate("INSERT INTO " + table("version") + " (time, version) VALUES (" + (System.currentTimeMillis() / 1000L) + ", " + SCHEMA_VERSION + ")");
                }
            }
        }
        CoreProtect.LOGGER.info("Using {} database", describe());
    }

    private List<String> sqliteSchema() {
        List<String> sql = new ArrayList<>();
        String id = "id INTEGER PRIMARY KEY";
        String location = "wid INTEGER NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL";
        sql.add("CREATE TABLE IF NOT EXISTS " + table("version") + " (" + id + ", time INTEGER NOT NULL, version INTEGER NOT NULL)");
        sql.add("CREATE TABLE IF NOT EXISTS " + table("user") + " (" + id + ", time INTEGER NOT NULL, user TEXT NOT NULL, uuid TEXT)");
        sql.add("CREATE TABLE IF NOT EXISTS " + table("username_log") + " (" + id + ", time INTEGER NOT NULL, uuid TEXT NOT NULL, user TEXT NOT NULL)");
        sql.add("CREATE TABLE IF NOT EXISTS " + table("world") + " (" + id + ", world TEXT NOT NULL)");
        sql.add("CREATE TABLE IF NOT EXISTS " + table("material_map") + " (" + id + ", material TEXT NOT NULL)");
        sql.add("CREATE TABLE IF NOT EXISTS " + table("blockdata_map") + " (" + id + ", data TEXT NOT NULL)");
        sql.add("CREATE TABLE IF NOT EXISTS " + table("block") + " (" + id + ", time INTEGER NOT NULL, user INTEGER NOT NULL, " + location
                + ", type INTEGER NOT NULL, data INTEGER NOT NULL, meta BLOB, action INTEGER NOT NULL, rolled_back INTEGER NOT NULL DEFAULT 0)");
        sql.add("CREATE TABLE IF NOT EXISTS " + table("container") + " (" + id + ", time INTEGER NOT NULL, user INTEGER NOT NULL, " + location
                + ", type INTEGER NOT NULL, slot INTEGER NOT NULL, amount INTEGER NOT NULL, metadata BLOB, action INTEGER NOT NULL, rolled_back INTEGER NOT NULL DEFAULT 0)");
        sql.add("CREATE TABLE IF NOT EXISTS " + table("item") + " (" + id + ", time INTEGER NOT NULL, user INTEGER NOT NULL, " + location
                + ", type INTEGER NOT NULL, amount INTEGER NOT NULL, metadata BLOB, action INTEGER NOT NULL)");
        sql.add("CREATE TABLE IF NOT EXISTS " + table("chat") + " (" + id + ", time INTEGER NOT NULL, user INTEGER NOT NULL, " + location + ", message TEXT NOT NULL)");
        sql.add("CREATE TABLE IF NOT EXISTS " + table("command") + " (" + id + ", time INTEGER NOT NULL, user INTEGER NOT NULL, " + location + ", message TEXT NOT NULL)");
        sql.add("CREATE TABLE IF NOT EXISTS " + table("session") + " (" + id + ", time INTEGER NOT NULL, user INTEGER NOT NULL, " + location + ", action INTEGER NOT NULL)");
        sql.add("CREATE TABLE IF NOT EXISTS " + table("sign") + " (" + id + ", time INTEGER NOT NULL, user INTEGER NOT NULL, " + location
                + ", side INTEGER NOT NULL, text TEXT NOT NULL)");

        sql.add("CREATE INDEX IF NOT EXISTS " + table("user_name_idx") + " ON " + table("user") + " (user)");
        sql.add("CREATE INDEX IF NOT EXISTS " + table("user_uuid_idx") + " ON " + table("user") + " (uuid)");
        sql.add("CREATE INDEX IF NOT EXISTS " + table("username_log_idx") + " ON " + table("username_log") + " (uuid, user)");
        sql.add("CREATE INDEX IF NOT EXISTS " + table("block_location_idx") + " ON " + table("block") + " (wid, x, z, time)");
        sql.add("CREATE INDEX IF NOT EXISTS " + table("block_user_idx") + " ON " + table("block") + " (user, time)");
        sql.add("CREATE INDEX IF NOT EXISTS " + table("block_type_idx") + " ON " + table("block") + " (type, time)");
        sql.add("CREATE INDEX IF NOT EXISTS " + table("block_time_idx") + " ON " + table("block") + " (time)");
        for (String t : new String[] { "container", "item", "chat", "command", "session", "sign" }) {
            sql.add("CREATE INDEX IF NOT EXISTS " + table(t + "_location_idx") + " ON " + table(t) + " (wid, x, z, time)");
            sql.add("CREATE INDEX IF NOT EXISTS " + table(t + "_user_idx") + " ON " + table(t) + " (user, time)");
            sql.add("CREATE INDEX IF NOT EXISTS " + table(t + "_time_idx") + " ON " + table(t) + " (time)");
        }
        sql.add("CREATE INDEX IF NOT EXISTS " + table("container_type_idx") + " ON " + table("container") + " (type, time)");
        sql.add("CREATE INDEX IF NOT EXISTS " + table("item_type_idx") + " ON " + table("item") + " (type, time)");
        return sql;
    }

    private List<String> mysqlSchema() {
        List<String> sql = new ArrayList<>();
        String id = "id BIGINT NOT NULL AUTO_INCREMENT, PRIMARY KEY (id)";
        String location = "wid INT NOT NULL, x INT NOT NULL, y INT NOT NULL, z INT NOT NULL";
        String engine = " ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4";
        String common = ", INDEX (wid, x, z, time), INDEX (user, time), INDEX (time)";
        sql.add("CREATE TABLE IF NOT EXISTS " + table("version") + " (" + id + ", time INT NOT NULL, version INT NOT NULL)" + engine);
        sql.add("CREATE TABLE IF NOT EXISTS " + table("user") + " (" + id + ", time INT NOT NULL, user VARCHAR(100) NOT NULL, uuid VARCHAR(64), INDEX (user), INDEX (uuid))" + engine);
        sql.add("CREATE TABLE IF NOT EXISTS " + table("username_log") + " (" + id + ", time INT NOT NULL, uuid VARCHAR(64) NOT NULL, user VARCHAR(100) NOT NULL, INDEX (uuid, user))" + engine);
        sql.add("CREATE TABLE IF NOT EXISTS " + table("world") + " (" + id + ", world VARCHAR(255) NOT NULL)" + engine);
        sql.add("CREATE TABLE IF NOT EXISTS " + table("material_map") + " (" + id + ", material VARCHAR(255) NOT NULL)" + engine);
        sql.add("CREATE TABLE IF NOT EXISTS " + table("blockdata_map") + " (" + id + ", data TEXT NOT NULL)" + engine);
        sql.add("CREATE TABLE IF NOT EXISTS " + table("block") + " (" + id + ", time INT NOT NULL, user INT NOT NULL, " + location
                + ", type INT NOT NULL, data INT NOT NULL, meta MEDIUMBLOB, action TINYINT NOT NULL, rolled_back TINYINT NOT NULL DEFAULT 0" + common + ", INDEX (type, time))" + engine);
        sql.add("CREATE TABLE IF NOT EXISTS " + table("container") + " (" + id + ", time INT NOT NULL, user INT NOT NULL, " + location
                + ", type INT NOT NULL, slot INT NOT NULL, amount INT NOT NULL, metadata MEDIUMBLOB, action TINYINT NOT NULL, rolled_back TINYINT NOT NULL DEFAULT 0" + common + ", INDEX (type, time))" + engine);
        sql.add("CREATE TABLE IF NOT EXISTS " + table("item") + " (" + id + ", time INT NOT NULL, user INT NOT NULL, " + location
                + ", type INT NOT NULL, amount INT NOT NULL, metadata MEDIUMBLOB, action TINYINT NOT NULL" + common + ", INDEX (type, time))" + engine);
        sql.add("CREATE TABLE IF NOT EXISTS " + table("chat") + " (" + id + ", time INT NOT NULL, user INT NOT NULL, " + location + ", message TEXT NOT NULL" + common + ")" + engine);
        sql.add("CREATE TABLE IF NOT EXISTS " + table("command") + " (" + id + ", time INT NOT NULL, user INT NOT NULL, " + location + ", message TEXT NOT NULL" + common + ")" + engine);
        sql.add("CREATE TABLE IF NOT EXISTS " + table("session") + " (" + id + ", time INT NOT NULL, user INT NOT NULL, " + location + ", action TINYINT NOT NULL" + common + ")" + engine);
        sql.add("CREATE TABLE IF NOT EXISTS " + table("sign") + " (" + id + ", time INT NOT NULL, user INT NOT NULL, " + location
                + ", side TINYINT NOT NULL, text TEXT NOT NULL" + common + ")" + engine);
        return sql;
    }
}
