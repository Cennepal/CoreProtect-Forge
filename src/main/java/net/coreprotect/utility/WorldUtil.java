package net.coreprotect.utility;

import java.util.Locale;

import net.coreprotect.consumer.Entry;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class WorldUtil {

    private WorldUtil() {
    }

    public static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    public static String worldName(LevelAccessor level) {
        if (level instanceof Level l) {
            return l.dimension().location().toString();
        }
        return "minecraft:overworld";
    }

    public static ServerLevel level(MinecraftServer server, String world) {
        ResourceLocation id = ResourceLocation.tryParse(world);
        if (id == null) {
            return null;
        }
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    /** Short, readable dimension name: "overworld", "the_nether", or "modid:name" for modded dimensions. */
    public static String shortWorldName(String world) {
        return world.startsWith("minecraft:") ? world.substring("minecraft:".length()) : world;
    }

    public static Entry.Location location(LevelAccessor level, BlockPos pos) {
        return new Entry.Location(worldName(level), pos.getX(), pos.getY(), pos.getZ());
    }

    public static Entry.Location location(Entity entity) {
        BlockPos pos = entity.blockPosition();
        return new Entry.Location(worldName(entity.level()), pos.getX(), pos.getY(), pos.getZ());
    }

    public static Entry.Actor actor(Entity entity) {
        if (entity instanceof Player player) {
            return new Entry.Actor(player.getGameProfile().getName(), player.getUUID());
        }
        return Entry.Actor.of("#" + entityName(entity.getType()));
    }

    public static String entityName(EntityType<?> type) {
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        return "minecraft".equals(key.getNamespace()) ? key.getPath() : key.toString();
    }

    public static String blockId(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    public static String blockId(BlockState state) {
        return blockId(state.getBlock());
    }

    public static String itemId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    public static String entityId(EntityType<?> type) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
    }

    public static String serialize(BlockState state) {
        return BlockStateParser.serialize(state);
    }

    public static CompoundTag blockEntityData(LevelAccessor level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null || blockEntity.isRemoved()) {
            return null;
        }
        try {
            CompoundTag tag = blockEntity.saveWithoutMetadata();
            return tag.isEmpty() ? null : tag;
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    /** Turns a user-entered name like "stone" or "minecraft:stone" into a registry id string. */
    public static String normalizeId(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains(":") ? lower : "minecraft:" + lower;
    }

    /** "minecraft:oak_log" becomes "oak_log"; modded ids are kept intact. */
    public static String displayId(String id) {
        if (id == null) {
            return "unknown";
        }
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }
}
