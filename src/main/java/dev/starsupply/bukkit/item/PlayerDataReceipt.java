package dev.starsupply.bukkit.item;

import java.io.DataInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.GZIPInputStream;

/** The bounded subset of NBT needed to verify CraftPlayer's on-disk BukkitValues receipt. */
final class PlayerDataReceipt {
    private static final long MAX_DECODED_BYTES = 32L * 1024 * 1024;
    private static final int MAX_DEPTH = 128;

    private PlayerDataReceipt() {}

    static boolean verify(Path path, String key, String expected) throws IOException {
        try (FileChannel file = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
             var gzip = new GZIPInputStream(Channels.newInputStream(file));
             var data = new DataInputStream(new BoundedStream(gzip))) {
            if (!readReceipt(data, key, expected)) return false;
            file.force(true);
            return true;
        } catch (NoSuchFileException missing) {
            return false;
        }
    }

    private static boolean readReceipt(DataInputStream data, String key, String expected) throws IOException {
        if (data.readUnsignedByte() != 10) throw new IOException("Player data root is not an NBT compound");
        data.readUTF();
        boolean foundBukkit = false;
        String receipt = null;
        for (int type; (type = data.readUnsignedByte()) != 0;) {
            String name = data.readUTF();
            if (name.equals("BukkitValues")) {
                if (type != 10 || foundBukkit) throw new IOException("Invalid BukkitValues compound");
                foundBukkit = true;
                receipt = readBukkitValues(data, key);
            } else {
                skipPayload(data, type, 1);
            }
        }
        // Read through the gzip trailer so a truncated or corrupt save cannot satisfy a receipt.
        if (data.read() != -1) throw new IOException("Trailing NBT player data");
        return expected.equals(receipt);
    }

    private static String readBukkitValues(DataInputStream data, String key) throws IOException {
        String receipt = null;
        for (int type; (type = data.readUnsignedByte()) != 0;) {
            String name = data.readUTF();
            if (name.equals(key)) {
                if (type != 8 || receipt != null) throw new IOException("Invalid inventory receipt tag");
                receipt = data.readUTF();
            } else {
                skipPayload(data, type, 2);
            }
        }
        return receipt;
    }

    private static void skipPayload(DataInputStream data, int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("Player NBT exceeds supported nesting depth");
        switch (type) {
            case 1 -> data.skipNBytes(1);
            case 2 -> data.skipNBytes(2);
            case 3, 5 -> data.skipNBytes(4);
            case 4, 6 -> data.skipNBytes(8);
            case 7 -> data.skipNBytes(readCount(data));
            case 8 -> data.skipNBytes(data.readUnsignedShort());
            case 9 -> skipList(data, depth);
            case 10 -> {
                for (int child; (child = data.readUnsignedByte()) != 0;) {
                    data.skipNBytes(data.readUnsignedShort());
                    skipPayload(data, child, depth + 1);
                }
            }
            case 11 -> data.skipNBytes(readCount(data) * 4L);
            case 12 -> data.skipNBytes(readCount(data) * 8L);
            default -> throw new IOException("Unsupported NBT tag type " + type);
        }
    }

    private static void skipList(DataInputStream data, int depth) throws IOException {
        int type = data.readUnsignedByte();
        int count = readCount(data);
        if (type > 12 || (type == 0 && count != 0)) throw new IOException("Invalid NBT list type");
        for (int index = 0; index < count; index++) skipPayload(data, type, depth + 1);
    }

    private static int readCount(DataInputStream data) throws IOException {
        int count = data.readInt();
        if (count < 0 || count > MAX_DECODED_BYTES) throw new IOException("Invalid NBT collection size");
        return count;
    }

    private static final class BoundedStream extends FilterInputStream {
        private long remaining = MAX_DECODED_BYTES;

        BoundedStream(InputStream source) {
            super(source);
        }

        @Override
        public int read() throws IOException {
            requireBudget(1);
            int value = in.read();
            if (value != -1) remaining--;
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            requireBudget(length);
            int count = in.read(bytes, offset, length);
            if (count > 0) remaining -= count;
            return count;
        }

        @Override
        public long skip(long length) throws IOException {
            requireBudget(length);
            long skipped = in.skip(length);
            remaining -= skipped;
            return skipped;
        }

        private void requireBudget(long count) throws IOException {
            if (count > remaining) throw new IOException("Player NBT exceeds supported size");
        }
    }
}
