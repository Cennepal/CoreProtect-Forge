package net.coreprotect.consumer;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import net.minecraft.nbt.CompoundTag;

/**
 * Immutable log entries queued by the server thread and written by the consumer thread.
 * All values are plain data (strings, copies of NBT) so they can be safely handed to another thread.
 */
public sealed interface Entry {

    /** A player or a non-player source such as "#tnt". */
    record Actor(String name, UUID uuid) {
        public static Actor of(String name) {
            return new Actor(name, null);
        }
    }

    record Location(String world, int x, int y, int z) {
    }

    /** action: 0 = removed, 1 = placed, 2 = clicked, 3 = killed. */
    record Block(long time, Actor actor, Location location, String type, String data, CompoundTag meta, int action) implements Entry {
    }

    /** action: 0 = removed from container, 1 = added to container. */
    record Container(long time, Actor actor, Location location, String type, int slot, int amount, CompoundTag tag, int action) implements Entry {
    }

    /** action: 0 = dropped, 1 = picked up. */
    record Item(long time, Actor actor, Location location, String type, int amount, CompoundTag tag, int action) implements Entry {
    }

    record Chat(long time, Actor actor, Location location, String message) implements Entry {
    }

    record Command(long time, Actor actor, Location location, String message) implements Entry {
    }

    /** action: 0 = logout, 1 = login. */
    record Session(long time, Actor actor, Location location, int action) implements Entry {
    }

    /** side: 0 = front, 1 = back. */
    record Sign(long time, Actor actor, Location location, int side, String text) implements Entry {
    }

    /** Recorded at login so renamed players keep their history. */
    record Login(long time, Actor actor) implements Entry {
    }

    /** Completed once every entry queued before it has been written. */
    record Flush(CompletableFuture<Void> future) implements Entry {
    }
}
