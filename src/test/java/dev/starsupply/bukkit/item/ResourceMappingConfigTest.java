package dev.starsupply.bukkit.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsupply.api.ResourceType;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ResourceMappingConfigTest {
    private record Mapping(ResourceType resource, int units) { }

    @Test
    void defaultConfigurationMapsEveryStrategicResourceToAnAvailableVanillaItem() throws IOException {
        InputStream source = getClass().getResourceAsStream("/config.yml");
        assertNotNull(source, "default config.yml must be packaged with the plugin");

        YamlConfiguration config;
        try (source; var reader = new InputStreamReader(source, StandardCharsets.UTF_8)) {
            config = YamlConfiguration.loadConfiguration(reader);
        }

        Map<String, Mapping> actual = new HashMap<>();
        for (Map<?, ?> rule : config.getMapList("resource-mappings")) {
            Object itemValue = rule.get("item");
            Object resourceValue = rule.get("resource");
            Object unitsValue = rule.get("units");
            assertTrue(itemValue instanceof String);
            assertTrue(resourceValue instanceof String);
            assertTrue(unitsValue instanceof Number);

            String itemId = (String) itemValue;
            if (!itemId.startsWith("minecraft:")) continue;

            Material material = Material.matchMaterial(itemId);
            assertNotNull(material, "unknown vanilla material in default mapping: " + itemId);
            Mapping mapping = new Mapping(ResourceType.valueOf((String) resourceValue),
                    ((Number) unitsValue).intValue());
            assertTrue(mapping.units() > 0, "units must be positive for " + itemId);
            assertNull(actual.putIfAbsent(itemId, mapping), "duplicate vanilla mapping: " + itemId);
        }

        assertEquals(Map.of(
                "minecraft:bread", new Mapping(ResourceType.FOOD, 1),
                "minecraft:honey_bottle", new Mapping(ResourceType.MEDICAL, 1),
                "minecraft:iron_ingot", new Mapping(ResourceType.EQUIPMENT, 1),
                "minecraft:cobblestone", new Mapping(ResourceType.MATERIALS, 1),
                "minecraft:scaffolding", new Mapping(ResourceType.ENGINEERING, 1),
                "minecraft:firework_rocket", new Mapping(ResourceType.STRATEGIC, 1)), actual);
    }
}
