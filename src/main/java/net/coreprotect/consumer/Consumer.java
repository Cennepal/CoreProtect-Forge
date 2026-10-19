package net.coreprotect.consumer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import net.coreprotect.CoreProtect;
import net.coreprotect.database.Database;
import net.coreprotect.utility.NbtCodec;

/**
 * Background writer. The server thread only enqueues immutable {@link Entry} objects; this thread resolves
 * user/world/material ids and writes everything in batched transactions.
 */
public final class Consumer implements Runnable {

    private static final int MAX_BATCH = 20_000;
    private static final long IDLE_WAIT_MS = 500;

    private final Database database;
    private final ConcurrentLinkedQueue<Entry> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queueSize = new AtomicInteger();
    private final AtomicLong written = new AtomicLong();
    private final Object signal = new Object();
    private volatile boolean running = true;
    private volatile boolean paused = false;
    private volatile boolean flushRequested = false;
    private Thread thread;
    private Connection connection;

    private final Map<String, Integer> worlds = new HashMap<>();
    private final Map<String, Integer> materials = new HashMap<>();
    private final Map<String, Integer> blockData = new HashMap<>();
    private final Map<String, Integer> usersByUuid = new HashMap<>();
    private final Map<String, String> userNames = new HashMap<>();
    private final Map<String, Integer> usersByName = new HashMap<>();

    public Consumer(Database database) {
        this.database = database;
    }

    public void start() {
        thread = new Thread(this, "CoreProtect Consumer");
        // Daemon so a stuck database can never keep the JVM alive; shutdown() joins it explicitly.
        thread.setDaemon(true);
        thread.start();
    }

    public void queue(Entry entry) {
        if (!running) {
            return;
        }
        queue.add(entry);
        queueSize.incrementAndGet();
    }

    public int queueSize() {
        return queueSize.get();
    }

    public long writtenRows() {
        return written.get();
    }

    public boolean isPaused() {
        return paused;
    }

    /** Pauses or resumes writing. Used while a purge holds the database. */
    public void setPaused(boolean paused) {
        this.paused = paused;
        wake();
    }

    /**
     * Returns a future that completes once every entry queued before this call has been written.
     */
    public CompletableFuture<Void> flush() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        if (!running) {
            future.complete(null);
            return future;
        }
        queue(new Entry.Flush(future));
        flushRequested = true;
        wake();
        return future;
    }

    /** Waits (off the server thread only) for pending entries to be written. */
    public void flushAndWait(long timeoutMs) {
        try {
            flush().get(timeoutMs, TimeUnit.MILLISECONDS);
        }
        catch (Exception ignored) {
            // Lookups still work against whatever has been written so far.
        }
    }

    private void wake() {
        synchronized (signal) {
            signal.notifyAll();
        }
    }

    /**
     * Stops accepting entries, writes everything that is still queued and closes the connection.
     */
    public void shutdown() {
        running = false;
        paused = false;
        wake();
        if (thread != null) {
            try {
                thread.join(TimeUnit.MINUTES.toMillis(5));
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                CoreProtect.LOGGER.error("CoreProtect consumer did not finish within 5 minutes; {} entries were not saved", queueSize.get());
            }
        }
    }

    @Override
    public void run() {
        List<Entry> batch = new ArrayList<>();
        long lastErrorLog = 0;
        int shutdownFailures = 0;
        while (true) {
            try {
                if (batch.isEmpty()) {
                    if (running && (paused || !flushRequested)) {
                        // Let entries accumulate so they are written in a single transaction.
                        synchronized (signal) {
                            if (running && (paused || !flushRequested)) {
                                signal.wait(IDLE_WAIT_MS);
                            }
                        }
                        if (paused && running) {
                            continue;
                        }
                    }
                    flushRequested = false;
                    Entry entry;
                    while (batch.size() < MAX_BATCH && (entry = queue.poll()) != null) {
                        batch.add(entry);
                    }
                    queueSize.addAndGet(-batch.size());
                    if (batch.isEmpty()) {
                        if (!running) {
                            break;
                        }
                        continue;
                    }
                    if (!queue.isEmpty()) {
                        flushRequested = true;
                    }
                }
                write(batch);
                completeFlushes(batch);
                batch.clear();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            catch (Throwable e) {
                closeConnection();
                long now = System.currentTimeMillis();
                if (now - lastErrorLog > 60_000 || !running) {
                    CoreProtect.LOGGER.error("CoreProtect was unable to write to the database; retrying", e);
                    lastErrorLog = now;
                }
                if (!running && ++shutdownFailures >= 3) {
                    CoreProtect.LOGGER.error("Discarding {} unsaved CoreProtect entries during shutdown", batch.size() + queueSize.get());
                    break;
                }
                try {
                    Thread.sleep(running ? 5000 : 1000);
                }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        completeFlushes(batch);
        Entry entry;
        while ((entry = queue.poll()) != null) {
            if (entry instanceof Entry.Flush flush) {
                flush.future().complete(null);
            }
        }
        closeConnection();
    }

    private static void completeFlushes(List<Entry> batch) {
        for (Entry entry : batch) {
            if (entry instanceof Entry.Flush flush) {
                flush.future().complete(null);
            }
        }
    }

    private void closeConnection() {
        if (connection != null) {
            try {
                connection.close();
            }
            catch (SQLException ignored) {
            }
            connection = null;
            // Cached ids may belong to a transaction that was rolled back.
            worlds.clear();
            materials.clear();
            blockData.clear();
            usersByUuid.clear();
            usersByName.clear();
            userNames.clear();
        }
    }

    private void write(List<Entry> batch) throws SQLException {
        if (connection == null) {
            connection = database.connect();
            connection.setAutoCommit(false);
        }
        try (Statements s = new Statements(connection)) {
            for (Entry entry : batch) {
                if (entry instanceof Entry.Login login) {
                    login(login);
                }
                else if (entry instanceof Entry.Block e) {
                    PreparedStatement ps = s.block();
                    ps.setLong(1, e.time());
                    ps.setInt(2, user(e.actor()));
                    location(ps, 3, e.location());
                    ps.setInt(7, material(e.type()));
                    ps.setInt(8, e.data() == null ? 0 : blockData(e.data()));
                    blob(ps, 9, NbtCodec.encode(e.meta()));
                    ps.setInt(10, e.action());
                    ps.addBatch();
                }
                else if (entry instanceof Entry.Container e) {
                    PreparedStatement ps = s.container();
                    ps.setLong(1, e.time());
                    ps.setInt(2, user(e.actor()));
                    location(ps, 3, e.location());
                    ps.setInt(7, material(e.type()));
                    ps.setInt(8, e.slot());
                    ps.setInt(9, e.amount());
                    blob(ps, 10, NbtCodec.encode(e.tag()));
                    ps.setInt(11, e.action());
                    ps.addBatch();
                }
                else if (entry instanceof Entry.Item e) {
                    PreparedStatement ps = s.item();
                    ps.setLong(1, e.time());
                    ps.setInt(2, user(e.actor()));
                    location(ps, 3, e.location());
                    ps.setInt(7, material(e.type()));
                    ps.setInt(8, e.amount());
                    blob(ps, 9, NbtCodec.encode(e.tag()));
                    ps.setInt(10, e.action());
                    ps.addBatch();
                }
                else if (entry instanceof Entry.Chat e) {
                    message(s.chat(), e.time(), e.actor(), e.location(), e.message());
                }
                else if (entry instanceof Entry.Command e) {
                    message(s.command(), e.time(), e.actor(), e.location(), e.message());
                }
                else if (entry instanceof Entry.Session e) {
                    PreparedStatement ps = s.session();
                    ps.setLong(1, e.time());
                    ps.setInt(2, user(e.actor()));
                    location(ps, 3, e.location());
                    ps.setInt(7, e.action());
                    ps.addBatch();
                }
                else if (entry instanceof Entry.Sign e) {
                    PreparedStatement ps = s.sign();
                    ps.setLong(1, e.time());
                    ps.setInt(2, user(e.actor()));
                    location(ps, 3, e.location());
                    ps.setInt(7, e.side());
                    ps.setString(8, e.text());
                    ps.addBatch();
                }
            }
            int rows = s.executeAll();
            connection.commit();
            written.addAndGet(rows);
        }
        catch (SQLException | RuntimeException e) {
            try {
                connection.rollback();
            }
            catch (SQLException ignored) {
            }
            throw e;
        }
    }

    private void message(PreparedStatement ps, long time, Entry.Actor actor, Entry.Location location, String message) throws SQLException {
        ps.setLong(1, time);
        ps.setInt(2, user(actor));
        location(ps, 3, location);
        ps.setString(7, message);
        ps.addBatch();
    }

    private void location(PreparedStatement ps, int index, Entry.Location location) throws SQLException {
        ps.setInt(index, world(location.world()));
        ps.setInt(index + 1, location.x());
        ps.setInt(index + 2, location.y());
        ps.setInt(index + 3, location.z());
    }

    private static void blob(PreparedStatement ps, int index, byte[] data) throws SQLException {
        if (data == null) {
            ps.setNull(index, Types.BLOB);
        }
        else {
            ps.setBytes(index, data);
        }
    }

    private int world(String name) throws SQLException {
        return mapId(worlds, "world", "world", name);
    }

    private int material(String name) throws SQLException {
        return mapId(materials, "material_map", "material", name);
    }

    private int blockData(String data) throws SQLException {
        return mapId(blockData, "blockdata_map", "data", data);
    }

    private int mapId(Map<String, Integer> cache, String table, String column, String value) throws SQLException {
        Integer id = cache.get(value);
        if (id != null) {
            return id;
        }
        try (PreparedStatement ps = connection.prepareStatement("SELECT id FROM " + database.table(table) + " WHERE " + column + " = ? ORDER BY id LIMIT 1")) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    id = rs.getInt(1);
                }
            }
        }
        if (id == null) {
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO " + database.table(table) + " (" + column + ") VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, value);
                ps.executeUpdate();
                id = generatedKey(ps);
            }
        }
        cache.put(value, id);
        return id;
    }

    private static int generatedKey(PreparedStatement ps) throws SQLException {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            if (keys.next()) {
                return (int) keys.getLong(1);
            }
        }
        throw new SQLException("No generated key returned");
    }

    private int user(Entry.Actor actor) throws SQLException {
        if (actor.uuid() != null) {
            String uuid = actor.uuid().toString();
            Integer id = usersByUuid.get(uuid);
            if (id != null) {
                return id;
            }
            try (PreparedStatement ps = connection.prepareStatement("SELECT id, user FROM " + database.table("user") + " WHERE uuid = ? ORDER BY id LIMIT 1")) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        id = rs.getInt(1);
                        userNames.put(uuid, rs.getString(2));
                    }
                }
            }
            if (id == null) {
                id = insertUser(actor.name(), uuid, actor.name());
            }
            usersByUuid.put(uuid, id);
            return id;
        }

        String key = actor.name().toLowerCase(Locale.ROOT);
        Integer id = usersByName.get(key);
        if (id != null) {
            return id;
        }
        try (PreparedStatement ps = connection.prepareStatement("SELECT id FROM " + database.table("user") + " WHERE user = ? AND uuid IS NULL ORDER BY id LIMIT 1")) {
            ps.setString(1, actor.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    id = rs.getInt(1);
                }
            }
        }
        if (id == null) {
            id = insertUser(actor.name(), null, null);
        }
        usersByName.put(key, id);
        return id;
    }

    private int insertUser(String name, String uuid, String logName) throws SQLException {
        long now = System.currentTimeMillis() / 1000L;
        int id;
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO " + database.table("user") + " (time, user, uuid) VALUES (?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, now);
            ps.setString(2, name);
            ps.setString(3, uuid);
            ps.executeUpdate();
            id = generatedKey(ps);
        }
        if (uuid != null) {
            userNames.put(uuid, name);
            logUsername(now, uuid, logName);
        }
        return id;
    }

    private void logUsername(long time, String uuid, String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO " + database.table("username_log") + " (time, uuid, user) VALUES (?, ?, ?)")) {
            ps.setLong(1, time);
            ps.setString(2, uuid);
            ps.setString(3, name);
            ps.executeUpdate();
        }
    }

    private void login(Entry.Login login) throws SQLException {
        int id = user(login.actor());
        String uuid = login.actor().uuid().toString();
        String known = userNames.get(uuid);
        if (known != null && !known.equals(login.actor().name())) {
            try (PreparedStatement ps = connection.prepareStatement("UPDATE " + database.table("user") + " SET user = ? WHERE id = ?")) {
                ps.setString(1, login.actor().name());
                ps.setInt(2, id);
                ps.executeUpdate();
            }
            logUsername(login.time(), uuid, login.actor().name());
            userNames.put(uuid, login.actor().name());
        }
    }

    /** Lazily prepared insert statements for one transaction. */
    private final class Statements implements AutoCloseable {
        private final Connection connection;
        private PreparedStatement block, container, item, chat, command, session, sign;
        private final List<PreparedStatement> used = new ArrayList<>();

        Statements(Connection connection) {
            this.connection = connection;
        }

        private PreparedStatement prepare(String sql) throws SQLException {
            PreparedStatement ps = connection.prepareStatement(sql);
            used.add(ps);
            return ps;
        }

        private static final String LOCATION = "time, user, wid, x, y, z";

        PreparedStatement block() throws SQLException {
            if (block == null) {
                block = prepare("INSERT INTO " + database.table("block") + " (" + LOCATION + ", type, data, meta, action, rolled_back) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)");
            }
            return block;
        }

        PreparedStatement container() throws SQLException {
            if (container == null) {
                container = prepare("INSERT INTO " + database.table("container") + " (" + LOCATION + ", type, slot, amount, metadata, action, rolled_back) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)");
            }
            return container;
        }

        PreparedStatement item() throws SQLException {
            if (item == null) {
                item = prepare("INSERT INTO " + database.table("item") + " (" + LOCATION + ", type, amount, metadata, action) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            }
            return item;
        }

        PreparedStatement chat() throws SQLException {
            if (chat == null) {
                chat = prepare("INSERT INTO " + database.table("chat") + " (" + LOCATION + ", message) VALUES (?, ?, ?, ?, ?, ?, ?)");
            }
            return chat;
        }

        PreparedStatement command() throws SQLException {
            if (command == null) {
                command = prepare("INSERT INTO " + database.table("command") + " (" + LOCATION + ", message) VALUES (?, ?, ?, ?, ?, ?, ?)");
            }
            return command;
        }

        PreparedStatement session() throws SQLException {
            if (session == null) {
                session = prepare("INSERT INTO " + database.table("session") + " (" + LOCATION + ", action) VALUES (?, ?, ?, ?, ?, ?, ?)");
            }
            return session;
        }

        PreparedStatement sign() throws SQLException {
            if (sign == null) {
                sign = prepare("INSERT INTO " + database.table("sign") + " (" + LOCATION + ", side, text) VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
            }
            return sign;
        }

        int executeAll() throws SQLException {
            int rows = 0;
            for (PreparedStatement ps : used) {
                for (int count : ps.executeBatch()) {
                    rows += Math.max(count, 1);
                }
            }
            return rows;
        }

        @Override
        public void close() {
            for (PreparedStatement ps : used) {
                try {
                    ps.close();
                }
                catch (SQLException ignored) {
                }
            }
        }
    }
}
