package net.coreprotect;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.coreprotect.command.CoCommand;
import net.coreprotect.command.Inspector;
import net.coreprotect.command.LookupRunner;
import net.coreprotect.command.Permissions;
import net.coreprotect.config.Config;
import net.coreprotect.consumer.Consumer;
import net.coreprotect.database.Database;
import net.coreprotect.listener.BlockTracker;
import net.coreprotect.listener.ContainerTracker;
import net.coreprotect.listener.EventListener;
import net.coreprotect.lookup.LookupService;
import net.coreprotect.rollback.RollbackManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.server.permission.events.PermissionGatherEvent;

/**
 * CoreProtect for Forge: block logging, lookups, rollbacks and restores.
 * <p>
 * The mod only runs on the logical server (dedicated or integrated); clients do not need it installed.
 */
@Mod(CoreProtect.MOD_ID)
public final class CoreProtect {

    public static final String MOD_ID = "coreprotect";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static volatile MinecraftServer server;
    private static volatile Database database;
    private static volatile Consumer consumer;
    private static volatile LookupService lookupService;
    private static volatile RollbackManager rollbackManager;
    private static volatile BlockTracker blockTracker;
    private static volatile ContainerTracker containerTracker;
    private static volatile ExecutorService worker;
    private static volatile ExecutorService lookupExecutor;
    private static volatile boolean enabled;

    public CoreProtect() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        MinecraftForge.EVENT_BUS.register(new EventListener());
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
        MinecraftForge.EVENT_BUS.addListener(this::onPermissionNodes);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::onServerAboutToStart);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::onServerStopped);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        CoCommand.register(event.getDispatcher());
    }

    private void onPermissionNodes(PermissionGatherEvent.Nodes event) {
        Permissions.register(event);
    }

    private void onServerAboutToStart(ServerAboutToStartEvent event) {
        MinecraftServer minecraftServer = event.getServer();
        server = minecraftServer;
        try {
            Path worldDirectory = minecraftServer.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            Database db = new Database(worldDirectory);
            db.createTables();
            database = db;
            consumer = new Consumer(db);
            consumer.start();
            lookupService = new LookupService(db);
            rollbackManager = new RollbackManager(minecraftServer, db);
            blockTracker = new BlockTracker();
            containerTracker = new ContainerTracker();
            worker = Executors.newSingleThreadExecutor(threadFactory("CoreProtect Worker"));
            lookupExecutor = Executors.newFixedThreadPool(2, threadFactory("CoreProtect Lookup"));
            enabled = true;
            LOGGER.info("CoreProtect {} has been successfully enabled", version());
        }
        catch (Throwable e) {
            enabled = false;
            LOGGER.error("CoreProtect was unable to connect to its database and has been disabled. No actions will be logged.", e);
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        if (!enabled) {
            return;
        }
        rollbackManager.abort();
    }

    private void onServerStopped(ServerStoppedEvent event) {
        if (!enabled) {
            server = null;
            return;
        }
        try {
            containerTracker.finishAll();
            blockTracker.process();
        }
        catch (RuntimeException e) {
            LOGGER.error("Unable to save pending CoreProtect data", e);
        }
        enabled = false;
        shutdown(worker);
        shutdown(lookupExecutor);
        consumer.shutdown();
        Inspector.clear();
        LookupRunner.clear();
        consumer = null;
        database = null;
        lookupService = null;
        rollbackManager = null;
        blockTracker = null;
        containerTracker = null;
        worker = null;
        lookupExecutor = null;
        server = null;
        LOGGER.info("CoreProtect has been disabled");
    }

    private static void shutdown(ExecutorService executor) {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        }
        catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static java.util.concurrent.ThreadFactory threadFactory(String name) {
        AtomicInteger count = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, name + " #" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    public static String version() {
        return ModList.get().getModContainerById(MOD_ID).map(c -> c.getModInfo().getVersion().toString()).orElse("unknown");
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static MinecraftServer server() {
        return server;
    }

    public static Database database() {
        return database;
    }

    public static Consumer consumer() {
        return consumer;
    }

    public static LookupService lookupService() {
        return lookupService;
    }

    public static RollbackManager rollbackManager() {
        return rollbackManager;
    }

    public static BlockTracker blockTracker() {
        return blockTracker;
    }

    public static ContainerTracker containerTracker() {
        return containerTracker;
    }

    public static ExecutorService worker() {
        return worker;
    }

    public static ExecutorService lookupExecutor() {
        return lookupExecutor;
    }
}
