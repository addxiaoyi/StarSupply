package dev.starsupply.bukkit.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InventoryFingerprintTest {
    @Test
    void ignoresMapInsertionOrderButRetainsInventoryChanges() {
        Map<String, Object> firstComponents = ordered(
                "minecraft:item_model", "kaleidoscope_tavern:brandy",
                "minecraft:max_stack_size", 16,
                "minecraft:lore", List.of("熟成等级：2 / 6"));
        Map<String, Object> loadedComponents = ordered(
                "minecraft:item_model", "kaleidoscope_tavern:brandy",
                "minecraft:lore", List.of("熟成等级：2 / 6"),
                "minecraft:max_stack_size", 16);
        Map<String, Object> firstItem = ordered("type", "minecraft:paper", "amount", 1,
                "components", firstComponents);
        Map<String, Object> loadedItem = ordered("components", loadedComponents,
                "amount", 1, "type", "minecraft:paper");

        assertEquals(InventoryFingerprint.hash(new Object[] {firstItem}),
                InventoryFingerprint.hash(new Object[] {loadedItem}));

        loadedComponents.put("minecraft:max_stack_size", 15);
        assertNotEquals(InventoryFingerprint.hash(new Object[] {firstItem}),
                InventoryFingerprint.hash(new Object[] {loadedItem}));
        assertNotEquals(InventoryFingerprint.hash(new Object[] {firstItem, null}),
                InventoryFingerprint.hash(new Object[] {null, firstItem}));
        var fingerprints = new InventoryEscrow.Fingerprints("canonical-hash", "legacy-hash");
        assertTrue(fingerprints.matches("canonical-hash"));
        assertTrue(fingerprints.matches("legacy-hash"));
    }

    private static Map<String, Object> ordered(Object... entries) {
        var result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((String) entries[index], entries[index + 1]);
        }
        return result;
    }
}
