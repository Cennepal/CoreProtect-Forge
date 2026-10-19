package net.coreprotect.listener;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;

public final class EntityUtil {

    private EntityUtil() {
    }

    /**
     * Saves an entity so it can be respawned by a rollback. Passengers are not included (they are logged
     * separately if they die), and the result is null for entities that cannot be serialized.
     */
    public static CompoundTag save(Entity entity) {
        String id = entity.getEncodeId();
        if (id == null) {
            return null;
        }
        try {
            CompoundTag tag = entity.saveWithoutId(new CompoundTag());
            tag.putString("id", id);
            tag.remove("Passengers");
            return tag;
        }
        catch (RuntimeException e) {
            return null;
        }
    }
}
