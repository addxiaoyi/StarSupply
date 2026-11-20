package dev.starsupply.bukkit.item;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayerDataReceiptTest {
    private static final String KEY = "starsupply:inventory_receipt_v1";
    private static final String RECEIPT = UUID.randomUUID().toString();
    @TempDir Path directory;

    @Test
    void requiresExactOperationInCurrentPlayerSave() throws IOException {
        Path playerData = directory.resolve("player.dat");
        assertFalse(PlayerDataReceipt.verify(playerData, KEY, RECEIPT));
        writeSave(playerData, RECEIPT, false);
        assertTrue(PlayerDataReceipt.verify(playerData, KEY, RECEIPT));
        assertFalse(PlayerDataReceipt.verify(playerData, KEY, UUID.randomUUID().toString()));
        writeSave(playerData, null, false);
        assertFalse(PlayerDataReceipt.verify(playerData, KEY, RECEIPT));
    }

    @Test
    void rejectsTruncatedAndDuplicateMarkersEvenWhenExpectedMarkerAppears() throws IOException {
        Path playerData = directory.resolve("player.dat");
        writeSave(playerData, RECEIPT, true);
        assertThrows(IOException.class, () -> PlayerDataReceipt.verify(playerData, KEY, RECEIPT));
        writeSave(playerData, RECEIPT, false);
        byte[] saved = Files.readAllBytes(playerData);
        Files.write(playerData, java.util.Arrays.copyOf(saved, saved.length - 3));
        assertThrows(IOException.class, () -> PlayerDataReceipt.verify(playerData, KEY, RECEIPT));
    }

    private static void writeSave(Path path, String receipt, boolean duplicates) throws IOException {
        try (var data = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(path)))) {
            data.writeByte(10);
            data.writeUTF("");
            data.writeByte(9);
            data.writeUTF("Inventory");
            data.writeByte(10);
            data.writeInt(1);
            // A matching receipt inside an unrelated item component is insufficient.
            writeString(data, KEY, RECEIPT);
            data.writeByte(0);
            data.writeByte(10);
            data.writeUTF("BukkitValues");
            if (receipt != null) writeString(data, KEY, receipt);
            if (duplicates) writeString(data, KEY, receipt);
            data.writeByte(0);
            data.writeByte(0);
        }
    }

    private static void writeString(DataOutputStream data, String key, String value) throws IOException {
        data.writeByte(8);
        data.writeUTF(key);
        data.writeUTF(value);
    }
}
