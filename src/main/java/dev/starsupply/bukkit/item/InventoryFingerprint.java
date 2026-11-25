package dev.starsupply.bukkit.item;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.bukkit.configuration.serialization.ConfigurationSerializable;

final class InventoryFingerprint {
    private InventoryFingerprint() {}

    static String hash(Object[] slots) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var output = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                output.writeInt(slots.length);
                for (Object slot : slots) writeValue(output, slot);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        } catch (IOException failure) {
            throw new IllegalStateException("无法计算玩家库存指纹", failure);
        }
    }

    private static void writeValue(DataOutputStream output, Object value) throws IOException {
        if (value == null) {
            output.writeByte(0);
        } else if (value instanceof Map<?, ?> map) {
            output.writeByte(1);
            var sorted = new TreeMap<String, Object>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("物品序列化映射包含非文本键");
                }
                sorted.put(key, entry.getValue());
            }
            output.writeInt(sorted.size());
            for (var entry : sorted.entrySet()) {
                writeString(output, entry.getKey());
                writeValue(output, entry.getValue());
            }
        } else if (value instanceof Set<?> set) {
            output.writeByte(2);
            List<byte[]> values = set.stream().map(InventoryFingerprint::encode).sorted(InventoryFingerprint::compareBytes)
                    .toList();
            output.writeInt(values.size());
            for (byte[] bytes : values) writeBytes(output, bytes);
        } else if (value instanceof Collection<?> collection) {
            output.writeByte(3);
            output.writeInt(collection.size());
            for (Object item : collection) writeValue(output, item);
        } else if (value instanceof ConfigurationSerializable serializable) {
            output.writeByte(4);
            writeString(output, value.getClass().getName());
            writeValue(output, serializable.serialize());
        } else if (value.getClass().isArray()) {
            output.writeByte(5);
            writeString(output, value.getClass().getComponentType().getName());
            int length = Array.getLength(value);
            output.writeInt(length);
            for (int index = 0; index < length; index++) writeValue(output, Array.get(value, index));
        } else if (value instanceof String text) {
            output.writeByte(6);
            writeString(output, text);
        } else if (value instanceof Boolean bool) {
            output.writeByte(7);
            output.writeBoolean(bool);
        } else if (value instanceof Number number) {
            output.writeByte(8);
            writeString(output, value.getClass().getName());
            writeString(output, number.toString());
        } else if (value instanceof Character character) {
            output.writeByte(9);
            output.writeChar(character);
        } else if (value instanceof Enum<?> enumeration) {
            output.writeByte(10);
            writeString(output, value.getClass().getName());
            writeString(output, enumeration.name());
        } else if (value instanceof UUID uuid) {
            output.writeByte(11);
            output.writeLong(uuid.getMostSignificantBits());
            output.writeLong(uuid.getLeastSignificantBits());
        } else {
            throw new IllegalArgumentException("不支持的物品序列化值 " + value.getClass().getName());
        }
    }

    private static byte[] encode(Object value) {
        try {
            var bytes = new java.io.ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                writeValue(output, value);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("无法编码物品序列化值", impossible);
        }
    }

    private static int compareBytes(byte[] first, byte[] second) {
        return java.util.Arrays.compareUnsigned(first, second);
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        writeBytes(output, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(DataOutputStream output, byte[] value) throws IOException {
        output.writeInt(value.length);
        output.write(value);
    }
}
