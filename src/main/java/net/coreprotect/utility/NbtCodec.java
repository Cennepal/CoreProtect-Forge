package net.coreprotect.utility;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

/**
 * Serializes NBT for database storage. The first byte is a format marker: 0 = raw NBT, 1 = gzip NBT.
 */
public final class NbtCodec {

    private static final int COMPRESS_THRESHOLD = 256;

    private NbtCodec() {
    }

    public static byte[] encode(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return null;
        }
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            raw.write(0);
            try (DataOutputStream out = new DataOutputStream(raw)) {
                NbtIo.write(tag, out);
            }
            if (raw.size() <= COMPRESS_THRESHOLD) {
                return raw.toByteArray();
            }
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            compressed.write(1);
            NbtIo.writeCompressed(tag, compressed);
            return compressed.size() < raw.size() ? compressed.toByteArray() : raw.toByteArray();
        }
        catch (IOException e) {
            throw new IllegalStateException("Unable to encode NBT", e);
        }
    }

    public static CompoundTag decode(byte[] data) {
        if (data == null || data.length < 2) {
            return null;
        }
        try {
            ByteArrayInputStream in = new ByteArrayInputStream(data, 1, data.length - 1);
            if (data[0] == 1) {
                return NbtIo.readCompressed(in);
            }
            try (DataInputStream dataIn = new DataInputStream(in)) {
                return NbtIo.read(dataIn);
            }
        }
        catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
