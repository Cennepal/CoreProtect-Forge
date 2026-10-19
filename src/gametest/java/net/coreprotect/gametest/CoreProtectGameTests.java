package net.coreprotect.gametest;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import com.mojang.authlib.GameProfile;

import net.coreprotect.CoreProtect;
import net.coreprotect.command.Inspector;
import net.coreprotect.consumer.Entry;
import net.coreprotect.listener.EventListener;
import net.coreprotect.lookup.Action;
import net.coreprotect.lookup.Formatter;
import net.coreprotect.lookup.LookupParams;
import net.coreprotect.lookup.LookupRow;
import net.coreprotect.lookup.LookupService;
import net.coreprotect.lookup.ParamParser;
import net.coreprotect.utility.WorldUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.FilteredText;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.ai.goal.BreakDoorGoal;
import net.minecraft.world.entity.ai.goal.DoorInteractGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.MenuProvider;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * End-to-end tests run on a real server with {@code ./gradlew runGameTestServer}. Each test uses its own
 * fake player name, so lookups and rollbacks filtered by user only see that test's data. Tests that run a
 * rollback use separate batches because only one rollback may run at a time.
 */
@GameTestHolder(CoreProtect.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CoreProtectGameTests {

    private static final String TEMPLATE = "empty";
    private static final int TIMEOUT = 600;
    /** Suffix that keeps player names unique per run, so data from earlier runs in the same world is ignored. */
    private static final String RUN = Long.toString(System.currentTimeMillis() % 46656, 36);

    // ------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------

    private static FakePlayer player(GameTestHelper helper, String name) {
        ServerLevel level = helper.getLevel();
        String unique = name + RUN;
        FakePlayer player = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes(("cp:" + unique).getBytes(StandardCharsets.UTF_8)), unique));
        Vec3 center = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(4, 2, 4)));
        player.moveTo(center.x, center.y, center.z, 0, 0);
        player.getInventory().clearContent();
        return player;
    }

    /** A fake operator whose chat output is written to the log, so failing commands are visible. */
    private static final class Admin extends FakePlayer {
        Admin(ServerLevel level, GameProfile profile) {
            super(level, profile);
        }

        @Override
        public void sendSystemMessage(Component message) {
            CoreProtect.LOGGER.info("[{}] {}", getGameProfile().getName(), message.getString());
        }

        @Override
        public void sendSystemMessage(Component message, boolean overlay) {
            sendSystemMessage(message);
        }
    }

    private static FakePlayer admin(GameTestHelper helper, String name) {
        ServerLevel level = helper.getLevel();
        FakePlayer admin = new Admin(level, new GameProfile(UUID.nameUUIDFromBytes(("cp:" + name).getBytes(StandardCharsets.UTF_8)), name));
        Vec3 center = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(4, 2, 4)));
        admin.moveTo(center.x, center.y, center.z, 0, 0);
        MinecraftServer server = level.getServer();
        // The game test server's operator level is 0, so add the entry with level 4 directly.
        server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        return admin;
    }

    private static void command(FakePlayer source, String command) {
        int result = source.getServer().getCommands().performPrefixedCommand(source.createCommandSourceStack(), command);
        CoreProtect.LOGGER.info("[{}] ran {} -> {} (busy={})", source.getGameProfile().getName(), command, result, CoreProtect.rollbackManager().isBusy());
    }

    private static List<LookupRow> lookup(GameTestHelper helper, String... args) {
        try {
            CoreProtect.consumer().flushAndWait(10_000);
            BlockPos center = helper.absolutePos(new BlockPos(4, 1, 4));
            LookupParams params = ParamParser.parse(helper.getLevel().getServer(), args, helper.getLevel().dimension().location().toString(),
                    center.getX(), center.getY(), center.getZ());
            if (params.actions.isEmpty()) {
                params.actions.add(Action.BLOCK);
                params.actions.add(Action.CLICK);
                params.actions.add(Action.KILL);
            }
            LookupService.Page page = CoreProtect.lookupService().lookup(params, 1, 1000, false);
            // Exercise the chat formatter as well.
            Formatter.rows(page.rows(), true);
            return page.rows();
        }
        catch (Exception e) {
            throw new GameTestAssertException("Lookup failed: " + e);
        }
    }

    private static void expectRows(GameTestHelper helper, List<LookupRow> rows, int count, String what) {
        if (rows.size() != count) {
            StringBuilder sb = new StringBuilder();
            for (LookupRow row : rows) {
                sb.append(" [").append(row.table).append(' ').append(row.action).append(' ').append(row.type).append(' ').append(row.user)
                        .append(" x").append(row.amount).append(' ').append(row.message).append(']');
            }
            expect(false, "Expected " + count + " " + what + " rows, found " + rows.size() + ":" + sb);
        }
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            // GameTestSequence keeps running after a failed step and reports the last failure, so log the first one.
            CoreProtect.LOGGER.error("Game test check failed: {}", message);
            throw new GameTestAssertException(message);
        }
    }

    private static boolean any(List<LookupRow> rows, Predicate<LookupRow> predicate) {
        return rows.stream().anyMatch(predicate);
    }

    private static void waitForRollback() {
        if (CoreProtect.rollbackManager().isBusy()) {
            throw new GameTestAssertException("Rollback still running");
        }
    }

    private static void place(FakePlayer player, ItemStack stack, BlockPos against, Direction face) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        Vec3 hit = Vec3.atCenterOf(against).relative(face, 0.5);
        stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, against, false)));
    }

    // ------------------------------------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------------------------------------

    @GameTest(template = TEMPLATE, batch = "cp_blocks", timeoutTicks = TIMEOUT)
    public static void blockBreakPlaceRollbackRestore(GameTestHelper helper) {
        FakePlayer player = player(helper, "cp_blocks");
        FakePlayer admin = admin(helper, "cp_admin1");
        BlockPos floor = new BlockPos(2, 1, 2);
        BlockPos broken = new BlockPos(3, 1, 2);
        helper.setBlock(floor, Blocks.STONE);
        helper.setBlock(broken, Blocks.DIRT);

        helper.startSequence()
                .thenExecute(() -> {
                    place(player, new ItemStack(Items.OAK_PLANKS), helper.absolutePos(floor), Direction.UP);
                    player.gameMode.destroyBlock(helper.absolutePos(broken));
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.OAK_PLANKS, floor.above());
                    helper.assertBlockPresent(Blocks.AIR, broken);
                    List<LookupRow> rows = lookup(helper, "u:cp_blocks" + RUN, "t:10m");
                    expectRows(helper, rows, 2, "block");
                    expect(any(rows, r -> r.action == 1 && "minecraft:oak_planks".equals(r.type)), "placement not logged");
                    expect(any(rows, r -> r.action == 0 && "minecraft:dirt".equals(r.type)), "break not logged");
                    command(admin, "/co rollback u:cp_blocks" + RUN + " t:10m r:#global");
                })
                .thenWaitUntil(CoreProtectGameTests::waitForRollback)
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.AIR, floor.above());
                    helper.assertBlockPresent(Blocks.DIRT, broken);
                    List<LookupRow> rows = lookup(helper, "u:cp_blocks" + RUN, "t:10m");
                    expect(rows.stream().allMatch(r -> r.rolledBack), "rows not flagged as rolled back");
                    command(admin, "/co restore u:cp_blocks" + RUN + " t:10m r:#global");
                })
                .thenWaitUntil(CoreProtectGameTests::waitForRollback)
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.OAK_PLANKS, floor.above());
                    helper.assertBlockPresent(Blocks.AIR, broken);
                    command(admin, "/co undo");
                })
                .thenWaitUntil(CoreProtectGameTests::waitForRollback)
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.AIR, floor.above());
                    helper.assertBlockPresent(Blocks.DIRT, broken);
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = "cp_door", timeoutTicks = TIMEOUT)
    public static void doorBreakLogsBothHalves(GameTestHelper helper) {
        FakePlayer player = player(helper, "cp_door");
        FakePlayer admin = admin(helper, "cp_admin2");
        BlockPos floor = new BlockPos(2, 1, 2);
        BlockPos lower = floor.above();
        helper.setBlock(floor, Blocks.STONE);
        helper.setBlock(lower, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        helper.setBlock(lower.above(), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));

        helper.startSequence()
                .thenExecute(() -> player.gameMode.destroyBlock(helper.absolutePos(lower)))
                .thenIdle(2)
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.AIR, lower);
                    helper.assertBlockPresent(Blocks.AIR, lower.above());
                    List<LookupRow> rows = lookup(helper, "u:cp_door" + RUN, "t:10m", "a:-block");
                    expectRows(helper, rows, 2, "door break");
                    command(admin, "/co rollback u:cp_door" + RUN + " t:10m r:#global");
                })
                .thenWaitUntil(CoreProtectGameTests::waitForRollback)
                .thenExecute(() -> {
                    helper.assertBlockProperty(lower, DoorBlock.HALF, DoubleBlockHalf.LOWER);
                    helper.assertBlockProperty(lower.above(), DoorBlock.HALF, DoubleBlockHalf.UPPER);
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = "cp_chest", timeoutTicks = TIMEOUT)
    public static void containerTransactionsRollback(GameTestHelper helper) {
        FakePlayer player = player(helper, "cp_chest");
        FakePlayer admin = admin(helper, "cp_admin3");
        BlockPos chestPos = new BlockPos(2, 1, 2);
        helper.setBlock(chestPos, Blocks.CHEST);
        ChestBlockEntity chest = (ChestBlockEntity) helper.getBlockEntity(chestPos);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 10));
        player.getInventory().setItem(0, new ItemStack(Items.DIRT, 3));

        helper.startSequence()
                .thenExecute(() -> {
                    player.openMenu(chest);
                    AbstractContainerMenu menu = player.containerMenu;
                    expect(menu != player.inventoryMenu, "chest menu did not open");
                    // Shift-click the diamonds out of the chest.
                    menu.clicked(0, 0, ClickType.QUICK_MOVE, player);
                })
                .thenIdle(1)
                .thenExecute(() -> {
                    AbstractContainerMenu menu = player.containerMenu;
                    // Hotbar slot 0 is the first slot after the 27 chest slots and 27 inventory slots.
                    menu.clicked(27 + 27, 0, ClickType.QUICK_MOVE, player);
                })
                .thenIdle(1)
                .thenExecute(player::closeContainer)
                .thenIdle(1)
                .thenExecute(() -> {
                    List<LookupRow> rows = lookup(helper, "u:cp_chest" + RUN, "t:10m", "a:container");
                    expectRows(helper, rows, 2, "container");
                    expect(any(rows, r -> r.action == 0 && "minecraft:diamond".equals(r.type) && r.amount == 10), "diamond removal not logged");
                    expect(any(rows, r -> r.action == 1 && "minecraft:dirt".equals(r.type) && r.amount == 3), "dirt addition not logged");
                    expect(chest.getItem(0).isEmpty() || chest.getItem(0).is(Items.DIRT), "unexpected chest contents");
                    command(admin, "/co rollback u:cp_chest" + RUN + " t:10m r:#global a:container");
                })
                .thenWaitUntil(CoreProtectGameTests::waitForRollback)
                .thenExecute(() -> {
                    int diamonds = 0;
                    int dirt = 0;
                    for (int i = 0; i < chest.getContainerSize(); i++) {
                        ItemStack stack = chest.getItem(i);
                        if (stack.is(Items.DIAMOND)) {
                            diamonds += stack.getCount();
                        }
                        if (stack.is(Items.DIRT)) {
                            dirt += stack.getCount();
                        }
                    }
                    expect(diamonds == 10, "expected 10 diamonds after rollback, found " + diamonds);
                    expect(dirt == 0, "expected no dirt after rollback, found " + dirt);
                    expect(chest.getItem(0).is(Items.DIAMOND), "diamonds were not returned to their original slot");
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = "cp_explosion", timeoutTicks = TIMEOUT)
    public static void explosionRollback(GameTestHelper helper) {
        FakePlayer admin = admin(helper, "cp_admin4");
        for (int x = 2; x <= 6; x++) {
            for (int z = 2; z <= 6; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE_BRICKS);
            }
        }
        BlockPos center = helper.absolutePos(new BlockPos(4, 2, 4));
        Vec3 adminPos = Vec3.atBottomCenterOf(center);
        admin.moveTo(adminPos.x, adminPos.y + 3, adminPos.z, 0, 0);

        helper.startSequence()
                .thenExecute(() -> helper.getLevel().explode(null, center.getX() + 0.5, center.getY(), center.getZ() + 0.5, 3.0F, Level.ExplosionInteraction.TNT))
                .thenIdle(2)
                .thenExecute(() -> {
                    List<LookupRow> rows = lookup(helper, "u:#explosion", "t:10m", "r:6", "a:-block");
                    expect(!rows.isEmpty(), "explosion was not logged");
                    expect(rows.stream().allMatch(r -> "minecraft:stone_bricks".equals(r.type)), "unexpected exploded blocks");
                    command(admin, "/co rollback u:#explosion t:10m r:10");
                })
                .thenWaitUntil(CoreProtectGameTests::waitForRollback)
                .thenExecute(() -> {
                    for (int x = 2; x <= 6; x++) {
                        for (int z = 2; z <= 6; z++) {
                            helper.assertBlockPresent(Blocks.STONE_BRICKS, new BlockPos(x, 1, z));
                        }
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = "cp_kill", timeoutTicks = TIMEOUT)
    public static void killRollbackRespawnsEntity(GameTestHelper helper) {
        FakePlayer player = player(helper, "cp_kill");
        FakePlayer admin = admin(helper, "cp_admin5");
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        Cow cow = helper.spawn(EntityType.COW, new BlockPos(4, 1, 4));
        cow.setPersistenceRequired();
        UUID uuid = cow.getUUID();

        helper.startSequence()
                .thenExecute(() -> cow.hurt(helper.getLevel().damageSources().playerAttack(player), 1000.0F))
                .thenIdle(30)
                .thenExecute(() -> {
                    expect(helper.getLevel().getEntity(uuid) == null, "cow did not die");
                    List<LookupRow> rows = lookup(helper, "u:cp_kill" + RUN, "t:10m", "a:kill");
                    expectRows(helper, rows, 1, "kill");
                    expect("minecraft:cow".equals(rows.get(0).type), "wrong entity type logged");
                    command(admin, "/co rollback u:cp_kill" + RUN + " t:10m r:#global");
                })
                .thenWaitUntil(CoreProtectGameTests::waitForRollback)
                .thenExecute(() -> {
                    Entity entity = helper.getLevel().getEntity(uuid);
                    expect(entity instanceof Cow living && living.isAlive() && living.getHealth() > 0, "cow was not respawned");
                    command(admin, "/co restore u:cp_kill" + RUN + " t:10m r:#global");
                })
                .thenWaitUntil(CoreProtectGameTests::waitForRollback)
                .thenExecute(() -> {
                    Entity entity = helper.getLevel().getEntity(uuid);
                    expect(entity == null || entity.isRemoved(), "restore did not remove the respawned cow");
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = "cp_misc", timeoutTicks = TIMEOUT)
    public static void signChatCommandSessionLogging(GameTestHelper helper) {
        FakePlayer player = player(helper, "cp_misc");
        BlockPos signPos = new BlockPos(2, 1, 2);
        helper.setBlock(signPos, Blocks.OAK_SIGN);
        SignBlockEntity sign = (SignBlockEntity) helper.getBlockEntity(signPos);

        helper.startSequence()
                .thenExecute(() -> {
                    sign.setAllowedPlayerEditor(player.getUUID());
                    sign.updateSignText(player, true, List.of(FilteredText.passThrough("hello"), FilteredText.passThrough("world"),
                            FilteredText.passThrough(""), FilteredText.passThrough("")));
                    MinecraftForge.EVENT_BUS.post(new ServerChatEvent(player, "hi there", Component.literal("hi there")));
                    command(player, "/help");
                    // Posting the real event would run Forge's network handlers, which need a connected client.
                    new EventListener().onLogin(new PlayerEvent.PlayerLoggedInEvent(player));
                })
                .thenIdle(1)
                .thenExecute(() -> {
                    List<LookupRow> signs = lookup(helper, "u:cp_misc" + RUN, "t:10m", "a:sign");
                    expectRows(helper, signs, 1, "sign");
                    expect(signs.get(0).message.startsWith("hello\nworld"), "wrong sign text: " + signs.get(0).message);
                    List<LookupRow> chat = lookup(helper, "u:cp_misc" + RUN, "t:10m", "a:chat");
                    expectRows(helper, chat, 1, "chat");
                    expect("hi there".equals(chat.get(0).message), "wrong chat message");
                    List<LookupRow> commands = lookup(helper, "u:cp_misc" + RUN, "t:10m", "a:command");
                    expectRows(helper, commands, 1, "command");
                    expect("/help".equals(commands.get(0).message), "wrong command: " + commands.get(0).message);
                    List<LookupRow> sessions = lookup(helper, "u:cp_misc" + RUN, "t:10m", "a:+session");
                    expectRows(helper, sessions, 1, "session");
                    List<LookupRow> usernames = lookup(helper, "u:cp_misc" + RUN, "a:username");
                    expect(!usernames.isEmpty(), "username not logged");
                    List<LookupRow> mixed = lookup(helper, "u:cp_misc" + RUN, "t:10m", "a:chat,command,sign");
                    expectRows(helper, mixed, 3, "mixed");
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = "cp_interact", timeoutTicks = TIMEOUT)
    public static void clicksAndBucketsAreLogged(GameTestHelper helper) {
        FakePlayer player = player(helper, "cp_interact");
        FakePlayer admin = admin(helper, "cp_admin6");
        BlockPos floor = new BlockPos(2, 1, 2);
        BlockPos lever = new BlockPos(4, 2, 4);
        helper.setBlock(floor, Blocks.STONE);
        helper.setBlock(lever.below(), Blocks.STONE);
        helper.setBlock(lever, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR));

        helper.startSequence()
                .thenExecute(() -> {
                    BlockPos abs = helper.absolutePos(lever);
                    player.gameMode.useItemOn(player, helper.getLevel(), ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atCenterOf(abs), Direction.UP, abs, false));
                    // Look straight down at the floor block and empty a water bucket onto it.
                    BlockPos floorAbs = helper.absolutePos(floor);
                    player.moveTo(floorAbs.getX() + 0.5, floorAbs.getY() + 2, floorAbs.getZ() + 0.5, 0, 90);
                    ItemStack bucket = new ItemStack(Items.WATER_BUCKET);
                    player.setItemInHand(InteractionHand.MAIN_HAND, bucket);
                    player.gameMode.useItem(player, helper.getLevel(), bucket, InteractionHand.MAIN_HAND);
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    helper.assertBlockProperty(lever, LeverBlock.POWERED, true);
                    helper.assertBlockPresent(Blocks.WATER, floor.above());
                    List<LookupRow> clicks = lookup(helper, "u:cp_interact" + RUN, "t:10m", "a:click");
                    expectRows(helper, clicks, 1, "click");
                    List<LookupRow> placed = lookup(helper, "u:cp_interact" + RUN, "t:10m", "a:+block");
                    expect(any(placed, r -> "minecraft:water".equals(r.type)), "bucket placement not logged");
                    command(admin, "/co rollback u:cp_interact" + RUN + " t:10m r:#global");
                })
                .thenWaitUntil(CoreProtectGameTests::waitForRollback)
                .thenExecute(() -> {
                    // The water source is gone (nearby flowing water may still be draining).
                    expect(!helper.getLevel().getFluidState(helper.absolutePos(floor.above())).isSource(), "water source was not rolled back");
                    // Clicks are not rolled back.
                    helper.assertBlockProperty(lever, LeverBlock.POWERED, true);
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = "cp_commands", timeoutTicks = TIMEOUT)
    public static void commandsAndInspector(GameTestHelper helper) {
        FakePlayer player = player(helper, "cp_cmd");
        FakePlayer admin = admin(helper, "cp_admin7");
        BlockPos pos = new BlockPos(2, 1, 2);
        helper.setBlock(pos, Blocks.GOLD_BLOCK);

        helper.startSequence()
                .thenExecute(() -> player.gameMode.destroyBlock(helper.absolutePos(pos)))
                .thenIdle(2)
                .thenExecute(() -> {
                    command(admin, "/co help");
                    command(admin, "/co status");
                    command(admin, "/co lookup u:cp_cmd" + RUN + " t:10m");
                    command(admin, "/co lookup 1:5");
                    command(admin, "/co near");
                    command(admin, "/co lookup i:gold_block t:10m r:20");
                    command(admin, "/co lookup u:cp_cmd" + RUN + " #count");
                    command(admin, "/co purge t:30d");
                    command(admin, "/co rollback u:cp_cmd" + RUN + " t:10m r:#global #preview");
                })
                .thenWaitUntil(CoreProtectGameTests::waitForRollback)
                .thenExecute(() -> {
                    expect(CoreProtect.rollbackManager().preview(admin.getUUID()) != null, "preview not stored");
                    // A preview must not change the world.
                    helper.assertBlockPresent(Blocks.AIR, pos);
                    command(admin, "/co cancel");
                    expect(CoreProtect.rollbackManager().preview(admin.getUUID()) == null, "preview not cancelled");

                    command(admin, "/co inspect");
                    expect(Inspector.isInspecting(admin), "inspector not enabled");
                    PlayerInteractEvent.LeftClickBlock event = new PlayerInteractEvent.LeftClickBlock(admin, helper.absolutePos(pos.below()), Direction.UP,
                            PlayerInteractEvent.LeftClickBlock.Action.START);
                    MinecraftForge.EVENT_BUS.post(event);
                    expect(event.isCanceled(), "inspector did not cancel the click");
                    command(admin, "/co inspect off");
                    expect(!Inspector.isInspecting(admin), "inspector not disabled");
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = "cp_mixins", timeoutTicks = TIMEOUT)
    public static void fireAndLeafDecayAreLogged(GameTestHelper helper) {
        BlockPos planks = new BlockPos(2, 1, 2);
        BlockPos leaves = new BlockPos(6, 1, 6);
        helper.setBlock(planks, Blocks.OAK_PLANKS);
        helper.setBlock(leaves, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, false).setValue(LeavesBlock.DISTANCE, 7));

        helper.startSequence()
                .thenExecute(() -> {
                    try {
                        Method burn = FireBlock.class.getDeclaredMethod("tryCatchFire", Level.class, BlockPos.class, int.class, RandomSource.class, int.class, Direction.class);
                        burn.setAccessible(true);
                        burn.invoke(Blocks.FIRE, helper.getLevel(), helper.absolutePos(planks), 1, helper.getLevel().random, 0, Direction.UP);
                    }
                    catch (ReflectiveOperationException e) {
                        throw new GameTestAssertException("Unable to call FireBlock.tryCatchFire: " + e);
                    }
                    BlockPos abs = helper.absolutePos(leaves);
                    helper.getLevel().getBlockState(abs).randomTick(helper.getLevel(), abs, helper.getLevel().random);
                })
                .thenIdle(1)
                .thenExecute(() -> {
                    helper.assertBlockNotPresent(Blocks.OAK_PLANKS, planks);
                    helper.assertBlockPresent(Blocks.AIR, leaves);
                    List<LookupRow> burned = lookup(helper, "u:#fire", "t:10m", "r:2", "a:-block");
                    expect(any(burned, r -> "minecraft:oak_planks".equals(r.type)), "burned block not logged");
                    List<LookupRow> decayed = lookup(helper, "u:#decay", "t:10m", "r:4", "a:-block");
                    expect(any(decayed, r -> "minecraft:oak_leaves".equals(r.type)), "leaf decay not logged");
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = "cp_chestbreak", timeoutTicks = TIMEOUT)
    public static void brokenChestContentsRollbackWithoutDuplication(GameTestHelper helper) {
        FakePlayer player = player(helper, "cp_cbreak");
        FakePlayer admin = admin(helper, "cp_admin8");
        BlockPos chestPos = new BlockPos(2, 1, 2);
        helper.setBlock(chestPos, Blocks.CHEST);
        ((ChestBlockEntity) helper.getBlockEntity(chestPos)).setItem(4, new ItemStack(Items.EMERALD, 5));

        helper.startSequence()
                .thenExecute(() -> player.gameMode.destroyBlock(helper.absolutePos(chestPos)))
                .thenIdle(2)
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.AIR, chestPos);
                    discardItems(helper);
                    command(admin, "/co rollback u:cp_cbreak" + RUN + " t:10m r:#global");
                })
                .thenWaitUntil(CoreProtectGameTests::waitForRollback)
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.CHEST, chestPos);
                    ItemStack restored = ((ChestBlockEntity) helper.getBlockEntity(chestPos)).getItem(4);
                    expect(restored.is(Items.EMERALD) && restored.getCount() == 5, "chest contents not restored: " + restored);
                    command(admin, "/co restore u:cp_cbreak" + RUN + " t:10m r:#global");
                })
                .thenWaitUntil(CoreProtectGameTests::waitForRollback)
                .thenIdle(1)
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.AIR, chestPos);
                    // Only look for the chest's contents; the test framework itself may drop its start button.
                    List<ItemEntity> dropped = items(helper).stream().filter(e -> e.getItem().is(Items.EMERALD)).toList();
                    expect(dropped.isEmpty(), "restore dropped items (duplication): " + dropped.stream()
                            .map(e -> e.getItem() + "@" + e.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)) + " age " + e.getAge()).toList());
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = "cp_double", timeoutTicks = TIMEOUT)
    public static void doubleChestTransactions(GameTestHelper helper) {
        FakePlayer player = player(helper, "cp_double");
        FakePlayer admin = admin(helper, "cp_admin12");
        BlockPos left = new BlockPos(2, 1, 2);
        BlockPos right = new BlockPos(3, 1, 2);
        helper.setBlock(left, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.LEFT));
        helper.setBlock(right, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.RIGHT));
        ((ChestBlockEntity) helper.getBlockEntity(right)).setItem(3, new ItemStack(Items.GOLD_INGOT, 7));

        helper.startSequence()
                .thenExecute(() -> {
                    BlockPos abs = helper.absolutePos(left);
                    MenuProvider provider = ((ChestBlock) Blocks.CHEST).getMenuProvider(helper.getLevel().getBlockState(abs), helper.getLevel(), abs);
                    expect(provider != null, "no double chest menu");
                    player.openMenu(provider);
                    AbstractContainerMenu menu = player.containerMenu;
                    expect(menu.slots.size() == 54 + 36, "not a double chest menu: " + menu.slots.size());
                    int slot = -1;
                    for (int i = 0; i < 54; i++) {
                        if (menu.getSlot(i).getItem().is(Items.GOLD_INGOT)) {
                            slot = i;
                        }
                    }
                    expect(slot >= 0, "gold not found in menu");
                    menu.clicked(slot, 0, ClickType.QUICK_MOVE, player);
                })
                .thenIdle(1)
                .thenExecute(player::closeContainer)
                .thenIdle(1)
                .thenExecute(() -> {
                    List<LookupRow> rows = lookup(helper, "u:cp_double" + RUN, "t:10m", "a:-container");
                    expectRows(helper, rows, 1, "double chest");
                    BlockPos abs = helper.absolutePos(right);
                    LookupRow row = rows.get(0);
                    expect(row.x == abs.getX() && row.z == abs.getZ() && row.slot == 3 && row.amount == 7,
                            "wrong location/slot: x" + row.x + " z" + row.z + " slot " + row.slot + " amount " + row.amount);
                    // Break one half; the rollback must reconnect it to the other half.
                    player.gameMode.destroyBlock(helper.absolutePos(left));
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    helper.assertBlockProperty(right, ChestBlock.TYPE, ChestType.SINGLE);
                    discardItems(helper);
                    command(admin, "/co rollback u:cp_double" + RUN + " t:10m r:#global a:block");
                })
                .thenWaitUntil(CoreProtectGameTests::waitForRollback)
                .thenExecute(() -> {
                    helper.assertBlockProperty(left, ChestBlock.TYPE, ChestType.LEFT);
                    helper.assertBlockProperty(right, ChestBlock.TYPE, ChestType.RIGHT);
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = "cp_column", timeoutTicks = TIMEOUT)
    public static void sugarCaneColumnIsLoggedAndRestored(GameTestHelper helper) {
        FakePlayer player = player(helper, "cp_column");
        FakePlayer admin = admin(helper, "cp_admin9");
        BlockPos base = new BlockPos(2, 1, 2);
        helper.setBlock(base.below(), Blocks.STONE);
        helper.setBlock(base.east().below(), Blocks.STONE);
        helper.setBlock(base, Blocks.DIRT);
        helper.setBlock(base.east(), Blocks.WATER);
        for (int i = 1; i <= 3; i++) {
            helper.setBlock(base.above(i), Blocks.SUGAR_CANE);
        }

        helper.startSequence()
                .thenExecute(() -> player.gameMode.destroyBlock(helper.absolutePos(base)))
                .thenIdle(10)
                .thenExecute(() -> {
                    for (int i = 1; i <= 3; i++) {
                        helper.assertBlockNotPresent(Blocks.SUGAR_CANE, base.above(i));
                    }
                    List<LookupRow> rows = lookup(helper, "u:cp_column" + RUN, "t:10m", "a:-block");
                    expect(rows.stream().filter(r -> "minecraft:sugar_cane".equals(r.type)).count() == 3, "sugar cane breaks not logged: " + rows.size());
                    discardItems(helper);
                    command(admin, "/co rollback u:cp_column" + RUN + " t:10m r:#global");
                })
                .thenWaitUntil(CoreProtectGameTests::waitForRollback)
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.DIRT, base);
                    for (int i = 1; i <= 3; i++) {
                        helper.assertBlockPresent(Blocks.SUGAR_CANE, base.above(i));
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = "cp_data", timeoutTicks = TIMEOUT)
    public static void purgeAndUsernameChanges(GameTestHelper helper) {
        FakePlayer admin = admin(helper, "cp_admin10");
        String oldName = "cp_old" + RUN;
        String newName = "cp_new" + RUN;
        UUID uuid = UUID.nameUUIDFromBytes(("cp:rename" + RUN).getBytes(StandardCharsets.UTF_8));
        BlockPos abs = helper.absolutePos(new BlockPos(1, 1, 1));
        Entry.Location location = new Entry.Location(helper.getLevel().dimension().location().toString(), abs.getX(), abs.getY(), abs.getZ());
        long now = WorldUtil.now();

        helper.startSequence()
                .thenExecute(() -> {
                    Entry.Actor before = new Entry.Actor(oldName, uuid);
                    CoreProtect.consumer().queue(new Entry.Login(now, before));
                    CoreProtect.consumer().queue(new Entry.Block(now - 40L * 86400, before, location, "minecraft:stone", "minecraft:stone", null, 0));
                    CoreProtect.consumer().queue(new Entry.Block(now, before, location, "minecraft:dirt", "minecraft:dirt", null, 1));
                    CoreProtect.consumer().queue(new Entry.Login(now, new Entry.Actor(newName, uuid)));
                })
                .thenExecute(() -> {
                    expectRows(helper, lookup(helper, "u:" + newName), 2, "renamed user");
                    expectRows(helper, lookup(helper, "u:" + oldName), 2, "previous name");
                    List<LookupRow> names = lookup(helper, "u:" + newName, "a:username");
                    expect(any(names, r -> oldName.equals(r.message)) && any(names, r -> newName.equals(r.message)), "username history incomplete");
                    command(admin, "/co purge t:30d");
                })
                .thenWaitUntil(() -> {
                    List<LookupRow> rows = lookup(helper, "u:" + newName);
                    if (rows.size() != 1) {
                        throw new GameTestAssertException("purge not finished: " + rows.size());
                    }
                    expect("minecraft:dirt".equals(rows.get(0).type), "purge removed recent data");
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = "cp_mixins", timeoutTicks = TIMEOUT)
    public static void endermanPickupIsLogged(GameTestHelper helper) {
        BlockPos center = new BlockPos(4, 1, 4);
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.BEDROCK);
            }
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            helper.setBlock(center.relative(direction), Blocks.DIRT);
        }
        EnderMan enderman = helper.spawn(EntityType.ENDERMAN, center);
        enderman.setNoAi(true);

        helper.startSequence()
                .thenExecute(() -> {
                    try {
                        Class<?> goalClass = Class.forName("net.minecraft.world.entity.monster.EnderMan$EndermanTakeBlockGoal");
                        Constructor<?> constructor = goalClass.getDeclaredConstructor(EnderMan.class);
                        constructor.setAccessible(true);
                        Goal goal = (Goal) constructor.newInstance(enderman);
                        for (int i = 0; i < 2000 && enderman.getCarriedBlock() == null; i++) {
                            goal.tick();
                        }
                    }
                    catch (ReflectiveOperationException e) {
                        throw new GameTestAssertException("Unable to run the enderman goal: " + e);
                    }
                    expect(enderman.getCarriedBlock() != null, "enderman did not pick up a block");
                })
                .thenIdle(1)
                .thenExecute(() -> {
                    List<LookupRow> rows = lookup(helper, "u:#enderman", "t:10m", "r:3", "a:-block");
                    expect(any(rows, r -> "minecraft:dirt".equals(r.type)), "enderman pickup not logged");
                    enderman.discard();
                })
                .thenSucceed();
    }

    @GameTest(template = TEMPLATE, batch = "cp_zombie", timeoutTicks = TIMEOUT)
    public static void zombieDoorBreakIsLoggedAndRolledBack(GameTestHelper helper) {
        FakePlayer admin = admin(helper, "cp_admin11");
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        BlockPos lower = new BlockPos(4, 1, 4);
        helper.setBlock(lower, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        helper.setBlock(lower.above(), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(4, 1, 5));
        long started = WorldUtil.now();
        zombie.setNoAi(true);

        helper.startSequence()
                .thenExecute(() -> {
                    BreakDoorGoal goal = new BreakDoorGoal(zombie, difficulty -> true);
                    try {
                        Field doorPos = DoorInteractGoal.class.getDeclaredField("doorPos");
                        doorPos.setAccessible(true);
                        doorPos.set(goal, helper.absolutePos(lower));
                    }
                    catch (ReflectiveOperationException e) {
                        throw new GameTestAssertException("Unable to set up the door goal: " + e);
                    }
                    for (int i = 0; i < 240; i++) {
                        goal.tick();
                    }
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    helper.assertBlockPresent(Blocks.AIR, lower);
                    helper.assertBlockPresent(Blocks.AIR, lower.above());
                    List<LookupRow> rows = lookup(helper, "u:#zombie", "t:10m", "r:3", "a:-block");
                    // #zombie is shared with earlier runs in the same world, so only count rows from this run.
                    expect(rows.stream().filter(r -> r.time >= started && "minecraft:oak_door".equals(r.type)).count() == 2, "door break not logged: " + rows.size());
                    zombie.discard();
                    discardItems(helper);
                    command(admin, "/co rollback u:#zombie t:10m r:5");
                })
                .thenWaitUntil(CoreProtectGameTests::waitForRollback)
                .thenExecute(() -> {
                    helper.assertBlockProperty(lower, DoorBlock.HALF, DoubleBlockHalf.LOWER);
                    helper.assertBlockProperty(lower.above(), DoorBlock.HALF, DoubleBlockHalf.UPPER);
                })
                .thenSucceed();
    }

    private static List<ItemEntity> items(GameTestHelper helper) {
        AABB box = new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(12);
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, box);
    }

    private static void discardItems(GameTestHelper helper) {
        items(helper).forEach(Entity::discard);
    }
}
